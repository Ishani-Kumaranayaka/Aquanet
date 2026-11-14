package aquanet.server;

import aquanet.common.NetworkController;
import aquanet.common.NetworkSnapshot;
import aquanet.common.PumpInfo;
import aquanet.common.Protocol;
import aquanet.common.TankInfo;
import aquanet.common.ValveInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The brain of the Galle water distribution system = the MAIN TANK.
 *
 * Real-world picture: one big reservoir ("Galle Main Reservoir") feeds many area tanks. Each area tank is a
 * separate program (aquanet.node.TankNode) connected to this server over TCP. The area tank measures its own
 * level, reports it every second and ASKS for water when it is low. This class decides who gets water.
 *
 * What the server owns:
 *   - the main tank's water level (finite!) and the source pump that refills it
 *   - the list of connected area tanks (registered via {@link #registerNode})
 *   - the allocation decision (GRANT or DENY) in {@link #handleRequest}
 *
 * Threading / locking: requests arrive on one handler thread PER TANK (see NodeHandler), commands arrive on GUI
 * client threads and on web threads, and the timer thread ticks once per second. They all touch the same main-tank
 * level, so every access is inside {@code synchronized (lock)}. Without it two tanks asking at the same instant
 * could both be granted the same last litres of water (a race condition).
 */
public class DistributionEngine implements NetworkController {

    // ----------------------------------------------------------------- tuning constants
    public static final String MAIN_ID = "MAIN";
    static final String MAIN_NAME = "Galle Main Reservoir";
    static final double MAIN_CAPACITY = 40000;      // litres
    static final double MAIN_START = 28000;
    static final double PUMP_RATE = 150;            // L/s from the treatment plant while the source pump is ON
    static final double MAIN_LEAK = 20;             // L/s when a leak is injected on the main tank
    static final double RESERVE_FRACTION = 0.05;    // last 5% of the main tank is kept for emergencies
    static final double GRANT_TARGET = 0.85;        // refill an area tank up to 85%
    static final double MIN_GRANT = 50;             // smaller grants are pointless
    static final long OFFLINE_AFTER_MS = 5000;      // no REPORT for 5 s -> OFFLINE
    static final long SENSOR_STALE_MS = 5000;
    static final long DENY_ALERT_COOLDOWN_MS = 15000;

    /** Lets the engine push a line to one connected sub tank without knowing about sockets. */
    interface NodeLink {
        void send(String line);
    }

    /** Everything the main tank knows about one area tank (guarded by 'lock'). */
    private static final class Node {
        final String id, name;
        final double capacity;
        double level, demand, inflow;
        boolean leaking, online = true;
        int supplyOpen = 100;           // % of the supply valve; 0 = closed by an administrator
        long lastSeen = System.currentTimeMillis();
        long lastDenyAlert = 0;
        double totalGranted;
        NodeLink link;
        Node(String id, String name, double capacity, double level, NodeLink link) {
            this.id = id; this.name = name; this.capacity = capacity; this.level = level; this.link = link;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private final Map<String, Double> sensorValue = new HashMap<String, Double>();
    private final Map<String, Long> sensorTime = new HashMap<String, Long>();
    private final Map<String, String> lastStatus = new HashMap<String, String>();

    private double mainLevel = MAIN_START;
    private boolean pumpOn = false;
    private boolean auto = true;
    private boolean mainLeak = false;
    private double grantedSinceTick = 0, mainInflow = 0, mainOutflow = 0;
    private long tick = 0;
    private int clients = 0;

    private final List<Consumer<NetworkSnapshot>> stateListeners = new CopyOnWriteArrayList<Consumer<NetworkSnapshot>>();
    private final List<Consumer<String>> eventListeners = new CopyOnWriteArrayList<Consumer<String>>();
    private ScheduledExecutorService scheduler;

    // ----------------------------------------------------------------- lifecycle

    public void start() {
        synchronized (lock) {
            if (scheduler != null) return;
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "main-tank-tick");
                    t.setDaemon(true);
                    return t;
                }
            });
            scheduler.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    try { tick(); } catch (RuntimeException e) { AquaNetServer.log("tick error: " + e); }
                }
            }, Protocol.TICK_MILLIS, Protocol.TICK_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        ScheduledExecutorService s;
        synchronized (lock) { s = scheduler; scheduler = null; }
        if (s != null) s.shutdownNow();
    }

    public void setClientCount(int n) { synchronized (lock) { clients = n; } }

    public boolean hasTank(String id) {
        synchronized (lock) { return MAIN_ID.equals(id) || nodes.containsKey(id); }
    }

    public void updateSensor(String tankId, double litres) {
        synchronized (lock) {
            sensorValue.put(tankId, litres);
            sensorTime.put(tankId, System.currentTimeMillis());
        }
    }

    public void addStateListener(Consumer<NetworkSnapshot> l) { stateListeners.add(l); }
    public void addEventListener(Consumer<String> l) { eventListeners.add(l); }

    public NetworkSnapshot snapshot() {
        synchronized (lock) { return buildSnapshot(); }
    }

    // ----------------------------------------------------------------- the 1-second tick

    private void tick() {
        List<String> events = new ArrayList<String>();
        NetworkSnapshot snap;
        synchronized (lock) {
            tick++;
            long now = System.currentTimeMillis();

            // AUTO mode: switch the source pump by the main tank level (with hysteresis)
            double pct = 100 * mainLevel / MAIN_CAPACITY;
            if (auto) {
                if (!pumpOn && pct < 40) { pumpOn = true; events.add(info("AUTO: source pump ON (main tank at " + (int) pct + "%)")); }
                else if (pumpOn && pct > 85) { pumpOn = false; events.add(info("AUTO: source pump OFF (main tank at " + (int) pct + "%)")); }
            }
            mainInflow = 0;
            if (pumpOn) {
                mainInflow = Math.min(PUMP_RATE, MAIN_CAPACITY - mainLevel);
                mainLevel += mainInflow;
            }
            double leak = 0;
            if (mainLeak) { leak = Math.min(MAIN_LEAK, mainLevel); mainLevel -= leak; }
            mainOutflow = grantedSinceTick + leak;
            grantedSinceTick = 0;
            mainLevel = Math.max(0, Math.min(MAIN_CAPACITY, mainLevel));

            // heartbeat check: a tank that stopped reporting is OFFLINE
            for (Node n : nodes.values()) {
                if (n.online && now - n.lastSeen > OFFLINE_AFTER_MS) {
                    n.online = false;
                    events.add("ALERT|CRIT|" + n.name + " tank is OFFLINE (no report for 5 s)");
                }
            }
            snap = buildSnapshot();
            statusAlerts(snap, events);
        }
        for (String e : events) fireEvent(e);
        for (Consumer<NetworkSnapshot> l : stateListeners) {
            try { l.accept(snap); } catch (RuntimeException ex) { AquaNetServer.log("listener error: " + ex); }
        }
    }

    /** Raise an alert only when a tank's status CHANGES (called with the lock held). */
    private void statusAlerts(NetworkSnapshot s, List<String> events) {
        for (TankInfo t : s.tanks) {
            String now = t.status();
            String old = lastStatus.put(t.id, now);
            if (old == null || old.equals(now)) continue;
            String name = t.name;
            if (TankInfo.OFFLINE.equals(now)) continue;            // reported separately above
            if (TankInfo.OFFLINE.equals(old) && TankInfo.OK.equals(now)) events.add(info(name + " is back ONLINE"));
            else if (TankInfo.LOW.equals(now)) events.add("ALERT|WARN|" + name + " is LOW (" + (int) t.percent() + "%)");
            else if (TankInfo.EMPTY.equals(now)) events.add("ALERT|CRIT|" + name + " is EMPTY");
            else if (TankInfo.OVERFLOW.equals(now)) events.add("ALERT|CRIT|" + name + " is overflowing");
            else if (TankInfo.HIGH.equals(now)) events.add("ALERT|WARN|" + name + " is nearly full");
            else if (TankInfo.OK.equals(now)) events.add(info(name + " level is back to normal"));
        }
    }

    private static String info(String m) { return "ALERT|INFO|" + m; }

    private void fireEvent(String e) {
        for (Consumer<String> l : eventListeners) {
            try { l.accept(e); } catch (RuntimeException ex) { AquaNetServer.log("event listener error: " + ex); }
        }
    }

    // ----------------------------------------------------------------- called by NodeHandler (one thread per area tank)

    /** @return null if accepted, otherwise the reason it was refused. */
    String registerNode(String id, String name, double capacity, double level, NodeLink link) {
        if (id == null || !id.matches("[A-Za-z0-9_]{2,16}")) return "id must be 2-16 letters, digits or _";
        if (MAIN_ID.equalsIgnoreCase(id)) return "id MAIN is reserved";
        if (!(capacity >= 100 && capacity <= 1_000_000)) return "capacity must be 100..1000000 litres";
        String cleanName = Protocol.clean(name).replace(",", " ").replace(";", " ").replace("#", " ");
        if (cleanName.isEmpty()) cleanName = id;
        level = Math.max(0, Math.min(capacity, level));
        String ev;
        synchronized (lock) {
            Node old = nodes.get(id);
            if (old != null && old.online) return "a tank with id " + id + " is already connected";
            Node n = new Node(id, cleanName, capacity, level, link);
            if (old != null) { n.supplyOpen = old.supplyOpen; n.totalGranted = old.totalGranted; }
            nodes.put(id, n);
            ev = info(cleanName + " tank connected (" + (int) capacity + " L)");
        }
        fireEvent(ev);
        return null;
    }

    void handleReport(String id, double level, double demand, double inflow, boolean leak) {
        synchronized (lock) {
            Node n = nodes.get(id);
            if (n == null) return;
            n.level = Math.max(0, Math.min(n.capacity, level));
            n.demand = Math.max(0, demand);
            n.inflow = Math.max(0, inflow);
            n.leaking = leak;
            n.lastSeen = System.currentTimeMillis();
            n.online = true;
        }
    }

    /**
     * THE ALLOCATION DECISION. An area tank asks for water; we answer with one protocol line:
     * "GRANT|id|litres" or "DENY|id|reason". The main tank's level is reduced inside the same lock, so two
     * tanks can never be given the same water twice.
     */
    String handleRequest(String id, double requested) {
        String alert = null;
        String reply;
        synchronized (lock) {
            Node n = nodes.get(id);
            if (n == null) return Protocol.join(Protocol.DENY, id, "not registered");
            n.lastSeen = System.currentTimeMillis();
            String why = null;
            if (n.supplyOpen <= 0) why = "supply closed by administrator";
            else if (!auto) why = "manual mode - waiting for the operator";
            double amount = 0;
            if (why == null) {
                amount = allocate(n, requested);
                if (amount < MIN_GRANT) why = "main tank is too low";
            }
            if (why != null) {
                reply = Protocol.join(Protocol.DENY, id, why);
                long now = System.currentTimeMillis();
                if (now - n.lastDenyAlert > DENY_ALERT_COOLDOWN_MS) {
                    n.lastDenyAlert = now;
                    alert = "ALERT|WARN|Request from " + n.name + " denied: " + why;
                }
            } else {
                mainLevel -= amount;
                grantedSinceTick += amount;
                n.totalGranted += amount;
                reply = Protocol.join(Protocol.GRANT, id, Long.toString(Math.round(amount)));
                alert = info("Granted " + Math.round(amount) + " L to " + n.name + " (main tank " + (int) (100 * mainLevel / MAIN_CAPACITY) + "%)");
            }
        }
        if (alert != null) fireEvent(alert);
        return reply;
    }

    /** How many litres we can give this tank right now (lock held). */
    private double allocate(Node n, double requested) {
        double wanted = Math.min(requested, n.capacity * GRANT_TARGET - n.level);      // never overfill
        double reserve = MAIN_CAPACITY * RESERVE_FRACTION;
        boolean critical = n.level < 0.10 * n.capacity;                                // emergency may use the reserve
        double available = critical ? mainLevel : mainLevel - reserve;
        double amount = Math.min(wanted, available);
        return amount * n.supplyOpen / 100.0;
    }

    void nodeGone(String id, NodeLink link) {
        String ev = null;
        synchronized (lock) {
            Node n = nodes.get(id);
            if (n != null && n.link == link && n.online) {
                n.online = false;
                ev = "ALERT|CRIT|" + n.name + " tank disconnected";
            }
        }
        if (ev != null) fireEvent(ev);
    }

    // ----------------------------------------------------------------- commands from GUI / web

    public String execute(String who, String command) {
        if (command == null) return "ERR|empty command";
        String[] p = command.trim().split("\\|");
        String c = p[0].toUpperCase();
        String reply, ev = null;
        List<Runnable> afterUnlock = new ArrayList<Runnable>();
        synchronized (lock) {
            try {
                switch (c) {
                    case "PUMP": {
                        if (p.length != 3 || !"P1".equals(p[1])) return "ERR|usage PUMP|P1|ON-or-OFF";
                        boolean on = p[2].equalsIgnoreCase("ON");
                        if (!on && !p[2].equalsIgnoreCase("OFF")) return "ERR|use ON or OFF";
                        pumpOn = on;
                        String sw = "";
                        if (auto) { auto = false; sw = " (mode switched to MANUAL)"; }
                        reply = "OK|source pump is " + (on ? "ON" : "OFF") + sw;
                        ev = info(who + ": source pump " + (on ? "ON" : "OFF") + sw);
                        break;
                    }
                    case "VALVE": {
                        if (p.length != 3 || !p[1].startsWith("V_")) return "ERR|usage VALVE|V_<tankId>|0..100";
                        Node n = nodes.get(p[1].substring(2));
                        if (n == null) return "ERR|unknown valve " + p[1];
                        int pct;
                        try { pct = Integer.parseInt(p[2].trim()); } catch (NumberFormatException e) { return "ERR|valve opening must be a number"; }
                        if (pct < 0 || pct > 100) return "ERR|valve opening must be between 0 and 100";
                        n.supplyOpen = pct;
                        final NodeLink l = n.link; final String line = Protocol.join(Protocol.SUPPLY, n.id, Integer.toString(pct));
                        if (n.online && l != null) afterUnlock.add(new Runnable() { public void run() { l.send(line); } });
                        reply = "OK|supply to " + n.name + " set to " + pct + "%";
                        ev = info(who + ": supply to " + n.name + " " + (pct == 0 ? "CLOSED" : "set to " + pct + "%"));
                        break;
                    }
                    case "MODE": {
                        if (p.length != 2) return "ERR|usage MODE|AUTO-or-MANUAL";
                        if (p[1].equalsIgnoreCase("AUTO")) auto = true;
                        else if (p[1].equalsIgnoreCase("MANUAL")) auto = false;
                        else return "ERR|use AUTO or MANUAL";
                        reply = "OK|mode is " + (auto ? "AUTO" : "MANUAL");
                        ev = info(who + ": mode " + (auto ? "AUTO (requests approved automatically)" : "MANUAL (requests wait for the operator)"));
                        break;
                    }
                    case "LEAK": {
                        if (p.length != 3) return "ERR|usage LEAK|tankId|ON-or-OFF";
                        boolean on = p[2].equalsIgnoreCase("ON");
                        if (!on && !p[2].equalsIgnoreCase("OFF")) return "ERR|use ON or OFF";
                        if (MAIN_ID.equals(p[1])) {
                            mainLeak = on;
                            reply = "OK|leak on main tank " + (on ? "injected" : "repaired");
                            ev = "ALERT|" + (on ? "CRIT" : "INFO") + "|" + who + ": leak " + (on ? "detected on" : "repaired on") + " " + MAIN_NAME;
                        } else {
                            Node n = nodes.get(p[1]);
                            if (n == null) return "ERR|unknown tank " + p[1];
                            if (!n.online || n.link == null) return "ERR|" + n.name + " is offline";
                            final NodeLink l = n.link; final String line = Protocol.join(Protocol.LEAK, n.id, on ? "ON" : "OFF");
                            afterUnlock.add(new Runnable() { public void run() { l.send(line); } });
                            reply = "OK|leak command sent to " + n.name;
                            ev = "ALERT|" + (on ? "CRIT" : "INFO") + "|" + who + ": leak " + (on ? "injected in" : "repair ordered for") + " " + n.name;
                        }
                        break;
                    }
                    case "SEND": {   // manual dispatch of water to one area (works in any mode)
                        if (p.length != 3) return "ERR|usage SEND|tankId|litres";
                        Node n = nodes.get(p[1]);
                        if (n == null) return "ERR|unknown tank " + p[1];
                        if (!n.online || n.link == null) return "ERR|" + n.name + " is offline";
                        double want;
                        try { want = Double.parseDouble(p[2]); } catch (NumberFormatException e) { return "ERR|litres must be a number"; }
                        double amount = Math.min(want, mainLevel);
                        amount = Math.min(amount, n.capacity - n.level);
                        if (amount < 1) return "ERR|nothing to send (main tank empty or target full)";
                        mainLevel -= amount; grantedSinceTick += amount; n.totalGranted += amount;
                        final NodeLink l = n.link; final String line = Protocol.join(Protocol.GRANT, n.id, Long.toString(Math.round(amount)));
                        afterUnlock.add(new Runnable() { public void run() { l.send(line); } });
                        reply = "OK|sent " + Math.round(amount) + " L to " + n.name;
                        ev = info(who + ": manually sent " + Math.round(amount) + " L to " + n.name);
                        break;
                    }
                    case "RESET": {
                        mainLevel = MAIN_START; pumpOn = false; auto = true; mainLeak = false; grantedSinceTick = 0;
                        for (Node n : nodes.values()) {
                            n.supplyOpen = 100;
                            final NodeLink l = n.link;
                            final String supplyLine = Protocol.join(Protocol.SUPPLY, n.id, "100");
                            if (n.online && l != null) afterUnlock.add(new Runnable() { public void run() { l.send(Protocol.RESET); l.send(supplyLine); } });
                        }
                        reply = "OK|system reset";
                        ev = info(who + ": system reset");
                        break;
                    }
                    default:
                        return "ERR|unknown command " + c;
                }
            } catch (RuntimeException e) {
                return "ERR|bad command: " + e.getMessage();
            }
        }
        for (Runnable r : afterUnlock) r.run();      // network writes happen outside the lock
        if (ev != null) fireEvent(ev);
        return reply;
    }

    // ----------------------------------------------------------------- snapshot (lock held)

    private NetworkSnapshot buildSnapshot() {
        long now = System.currentTimeMillis();
        List<TankInfo> tanks = new ArrayList<TankInfo>();
        tanks.add(new TankInfo(MAIN_ID, MAIN_NAME, mainLevel, MAIN_CAPACITY, mainInflow, mainOutflow, measured(MAIN_ID, now), mainLeak, true));
        List<ValveInfo> valves = new ArrayList<ValveInfo>();
        for (Node n : nodes.values()) {
            double out = n.demand + (n.leaking ? 12 : 0);
            tanks.add(new TankInfo(n.id, n.name, n.level, n.capacity, n.inflow, out, measured(n.id, now), n.leaking, n.online));
            valves.add(new ValveInfo("V_" + n.id, "Supply to " + n.name, n.supplyOpen, MAIN_ID, n.id));
        }
        List<PumpInfo> pumps = new ArrayList<PumpInfo>();
        pumps.add(new PumpInfo("P1", "Source pump", pumpOn, PUMP_RATE));
        return new NetworkSnapshot(tick, auto ? "AUTO" : "MANUAL", clients, tanks, pumps, valves);
    }

    private double measured(String id, long now) {
        Long t = sensorTime.get(id);
        if (t == null || now - t > SENSOR_STALE_MS) return -1;
        return sensorValue.get(id);
    }
}
