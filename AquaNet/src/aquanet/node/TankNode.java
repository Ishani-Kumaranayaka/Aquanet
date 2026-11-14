package aquanet.node;

import aquanet.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TankNode {

    //  configuration

    /** Plain settings holder filled from the command line or the start dialog. */
    static final class Config {
        String id = "TANK1";
        String name = null;          // null = same as id
        String host = "localhost";
        int port = Protocol.NODE_PORT;
        int udpPort = Protocol.UDP_PORT;
        double capacity = 4000;
        double level = 2500;
        double demand = 14;
        boolean headless = false;
        boolean noUdp = false;
        String displayName() { return name == null || name.isEmpty() ? id : name; }
    }

    /** Name, id, capacity, demand for each preset area. */
    static final String[][] AREAS = {
        // key, id, display name, capacity, demand
        {"fort", "FORT", "Galle Fort", "3000", "10"},
        {"karapitiya", "KARAPITIYA", "Karapitiya", "5000", "18"},
        {"unawatuna", "UNAWATUNA", "Unawatuna", "3500", "12"},
        {"hikkaduwa", "HIKKADUWA", "Hikkaduwa", "4500", "15"},
    };

    /** Applies a preset: sets id/name/capacity/demand and a start level of 60 % of capacity. */
    static boolean applyArea(Config c, String key) {
        for (String[] a : AREAS) {
            if (a[0].equalsIgnoreCase(key)) {
                c.id = a[1]; c.name = a[2];
                c.capacity = Double.parseDouble(a[3]);
                c.demand = Double.parseDouble(a[4]);
                c.level = Math.round(c.capacity * 0.6);
                return true;
            }
        }
        return false;
    }

    private static void usage(String err) {
        if (err != null) System.err.println("Error: " + err);
        System.err.println("Usage: java aquanet.node.TankNode [--area fort|karapitiya|unawatuna|hikkaduwa]\n"
                + "   [--id ID] [--name NAME] [--host localhost] [--port 5002] [--udp-port 5001]\n"
                + "   [--capacity 4000] [--level 2500] [--demand 14] [--headless] [--no-udp]");
    }

    /** Parses args; returns null (after printing usage) on error. Two passes: preset first, flags override. */
    static Config parseArgs(String[] args) {
        Config c = new Config();
        try {
            for (int i = 0; i < args.length; i++) {            // pass 1: preset
                if (args[i].equals("--area")) {
                    if (i + 1 >= args.length || !applyArea(c, args[i + 1])) {
                        usage("unknown --area"); return null;
                    }
                }
            }
            for (int i = 0; i < args.length; i++) {            // pass 2: explicit flags win
                String a = args[i];
                switch (a) {
                    case "--area": i++; break;
                    case "--id": c.id = args[++i]; break;
                    case "--name": c.name = args[++i]; break;
                    case "--host": c.host = args[++i]; break;
                    case "--port": c.port = Integer.parseInt(args[++i]); break;
                    case "--udp-port": c.udpPort = Integer.parseInt(args[++i]); break;
                    case "--capacity": c.capacity = Double.parseDouble(args[++i]); break;
                    case "--level": c.level = Double.parseDouble(args[++i]); break;
                    case "--demand": c.demand = Double.parseDouble(args[++i]); break;
                    case "--headless": c.headless = true; break;
                    case "--no-udp": c.noUdp = true; break;
                    case "-h": case "--help": usage(null); return null;
                    default: usage("unknown option " + a); return null;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            usage("missing value for last option"); return null;
        } catch (NumberFormatException e) {
            usage("bad number: " + e.getMessage()); return null;
        }
        return validate(c);
    }

    static Config validate(Config c) {
        if (!c.id.matches("[A-Za-z0-9_]{2,16}")) { usage("id must be 2-16 chars of A-Z a-z 0-9 _"); return null; }
        if (c.capacity <= 0 || c.demand < 0) { usage("capacity must be > 0 and demand >= 0"); return null; }
        if (c.level < 0) c.level = 0;
        if (c.level > c.capacity) c.level = c.capacity;
        c.name = Protocol.clean(c.displayName());
        return c;
    }

    //  UI abstraction

    /** What the network/tick code needs from a user interface (Swing window or console). */
    interface Ui {
        void log(String line);
        void update(TankModel.Snapshot s);
        void fatal(String message);   // e.g. server rejected our id; no more retrying
    }

    /** headless: everything goes to the console. */
    static final class ConsoleUi implements Ui {
        private long lastStatus;
        public synchronized void log(String line) {
            System.out.println(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " " + line);
        }
        public synchronized void update(TankModel.Snapshot s) {
            long now = System.currentTimeMillis();
            if (now - lastStatus >= 5000) {            // a status line every 5 s is enough
                lastStatus = now;
                log(String.format(Locale.ROOT, "[status] level=%.0f L (%.0f%%) demand=%.1f inflow=%.1f pending=%.0f leak=%s supply=%.0f%% %s",
                        s.level, s.fraction() * 100, s.demand, s.inflow, s.pending, s.leak ? "ON" : "off",
                        s.supplyPct, s.connected ? "connected" : "DISCONNECTED"));
            }
        }
        public void fatal(String message) { log("FATAL: " + message); }
    }

    //  node state

    private final Config cfg;
    private final TankModel model;
    private volatile Ui ui;
    private volatile boolean running = true;
    private volatile boolean fatal = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tick");
        t.setDaemon(false);
        return t;
    });
    private Thread netThread;

    // Socket + writer are shared by the tick thread, the reader/net thread and the Swing thread
    // (manual request button), so every write goes through send() which locks writeLock.
    private final Object writeLock = new Object();
    private Socket socket;
    private BufferedWriter writer;

    // UDP field-sensor
    private DatagramSocket udp;
    private InetAddress udpAddr;
    private final AtomicLong sensorSeq = new AtomicLong();
    private final Random rnd = new Random();

    TankNode(Config cfg, Ui ui) {
        this.cfg = cfg;
        this.ui = ui;
        this.model = new TankModel(cfg.id, cfg.name, cfg.capacity, cfg.level, cfg.demand);
    }

    void setUi(Ui ui) { this.ui = ui; }
    TankModel model() { return model; }
    Config config() { return cfg; }

    // lifecycle

    void start() {
        if (!cfg.noUdp) {
            try {
                udp = new DatagramSocket();
                udpAddr = InetAddress.getByName(cfg.host);
            } catch (IOException e) {
                ui.log("UDP sensor disabled: " + e.getMessage());
                udp = null;
            }
        }
        ui.log("Tank '" + cfg.name + "' (" + cfg.id + ") capacity " + Math.round(cfg.capacity)
                + " L, start level " + Math.round(cfg.level) + " L, base demand " + cfg.demand + " L/s");
        netThread = new Thread(this::connectionLoop, "node-net");
        netThread.setDaemon(true);
        netThread.start();
        scheduler.scheduleAtFixedRate(this::tick, Protocol.TICK_MILLIS, Protocol.TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private boolean shutDown;

    /** Clean shutdown: stop ticking, say BYE so the server does not wait for a timeout, close sockets. */
    void shutdown() {
        synchronized (this) {
            if (shutDown) return;
            shutDown = true;
        }
        running = false;
        scheduler.shutdownNow();
        send(Protocol.BYE, true);
        closeSocket();
        if (udp != null) udp.close();
        if (netThread != null) netThread.interrupt();
    }

    //  tick thread

    /** Runs once per second on the "tick" thread. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            TankModel.Snapshot s = model.tick(now);       // lock is held only inside this call

            if (s.connected) {
                // Heartbeat + status over TCP. Not logged (would flood the log every second).
                send(Protocol.join(Protocol.REPORT, cfg.id, String.valueOf(Math.round(s.level)),
                        fmt(s.demand), fmt(s.inflow), s.leak ? "1" : "0"), false);
            }
            sendSensor(s.level);
            if (s.requestLitres > 0) {
                send(Protocol.join(Protocol.REQUEST, cfg.id, String.valueOf(s.requestLitres)), true);
            }
            ui.update(s);
        } catch (RuntimeException e) {
            // never let an exception cancel the periodic task (scheduleAtFixedRate stops on exceptions)
            ui.log("tick error: " + e);
        }
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }
 private void sendSensor(double level) {
        if (udp == null) return;
        try {
            double noisy = Math.max(0, level * (1 + 0.01 * rnd.nextGaussian()));
            String msg = Protocol.join(Protocol.SENSOR, cfg.id, String.format(Locale.ROOT, "%.1f", noisy),
                    String.valueOf(sensorSeq.incrementAndGet()));
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(b, b.length, udpAddr, cfg.udpPort));
        } catch (IOException e) {
            // UDP send errors (e.g. ICMP port unreachable) are ignored on purpose
        }
    }

    //  network (TCP)

    /** Writes one line. Synchronized so lines from different threads never interleave. */
    private boolean send(String line, boolean logIt) {
        synchronized (writeLock) {
            if (writer == null) return false;
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                closeSocket();      // makes the reader thread's readLine() fail -> reconnect
                return false;
            }
        }
        if (logIt) ui.log("-> " + line);
        return true;
    }

    private void closeSocket() {
        synchronized (writeLock) {
            writer = null;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
                socket = null;
            }
        }
    }
    private void connectionLoop() {
        boolean loggedDown = false;
        while (running && !fatal) {
            boolean everConnected = false;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 3000);
                s.setTcpNoDelay(true);   // tiny messages: do not let Nagle delay them
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                synchronized (writeLock) {
                    socket = s;
                    writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                }
                everConnected = true;
                loggedDown = false;
                registered = false;
                ui.log("TCP connected to " + cfg.host + ":" + cfg.port);
                send(Protocol.join(Protocol.NODE, cfg.id, cfg.name, String.valueOf(Math.round(cfg.capacity)),
                        String.valueOf(Math.round(model.currentLevel()))), true);
                String line;
                while (running && (line = in.readLine()) != null) {
                    handle(line.trim());
                }
                if (running && !fatal) ui.log("Server closed the connection");
            } catch (IOException e) {
                if (running && !fatal && !loggedDown) {
                    ui.log((everConnected ? "Connection lost: " : "Cannot reach main tank: ") + e.getMessage()
                            + " - retrying every 3 s");
                }
                loggedDown = !everConnected;
            } finally {
                closeSocket();
                model.setConnected(false);
                TankModel.Snapshot s = model.snapshot();
                ui.update(s);
            }
            if (running && !fatal) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private volatile boolean registered;

    /** Handles one line pushed by the server (runs on the "node-net" thread). */
    private void handle(String line) {
        if (line.isEmpty()) return;
        String[] f = Protocol.split(line, 3);
        String cmd = f[0];
        if (!cmd.equals(Protocol.PONG)) ui.log("<- " + line);
        switch (cmd) {
            case Protocol.REGISTERED:
                registered = true;
                model.setConnected(true);
                model.setLastReply(line);
                break;
            case Protocol.GRANT:
                if (f.length >= 3 && forMe(f[1])) {
                    try {
                        model.addPending(Double.parseDouble(f[2]));
                        model.setLastReply("GRANT " + f[2] + " L");
                    } catch (NumberFormatException e) { ui.log("bad GRANT: " + line); }
                }
                break;
            case Protocol.DENY: {
                String reason = f.length >= 3 ? f[2] : "(no reason)";
                model.recordDeny(reason, System.currentTimeMillis());
                model.setLastReply("DENY: " + reason);
                break;
            }
            case Protocol.SUPPLY:
                if (f.length >= 3 && forMe(f[1])) {
                    try {
                        model.setSupply(Double.parseDouble(f[2]));
                        model.setLastReply("SUPPLY " + f[2] + "%");
                    } catch (NumberFormatException e) { ui.log("bad SUPPLY: " + line); }
                }
                break;
            case Protocol.LEAK:
                if (f.length >= 3 && forMe(f[1])) {
                    boolean on = f[2].equalsIgnoreCase("ON");
                    model.setLeak(on);
                    model.setLastReply("LEAK " + (on ? "ON" : "OFF"));
                    ui.log(on ? "admin injected leak" : "admin repaired leak");
                }
                break;
            case Protocol.RESET:
                model.reset();
                model.setLastReply("RESET");
                ui.log("admin reset: level back to " + Math.round(cfg.level) + " L");
                break;
            case Protocol.ERR: {
                String msg = line.length() > 4 ? line.substring(4) : "error";
                model.setLastReply("ERR: " + msg);
                if (!registered) {
                    // refused during registration (duplicate id, bad id...): retrying cannot help
                    fatal = true;
                    ui.fatal("Server refused registration: " + msg);
                    if (cfg.headless) {      // nothing more to do in console mode: exit
                        new Thread(() -> { shutdown(); System.exit(3); }, "exit").start();
                    }
                }
                break;
            }
            case Protocol.PONG:
                break;
            default:
                ui.log("unknown message from server: " + line);
        }
        ui.update(model.snapshot());
    }

    private boolean forMe(String id) { return id.equalsIgnoreCase(cfg.id); }

    // actions from the UI

    /** 'Request water now' button. */
    void manualRequest() {
        long litres = model.manualRequest(System.currentTimeMillis());
        if (litres <= 0) { ui.log("Tank is already above 85% - nothing to request"); return; }
        if (!send(Protocol.join(Protocol.REQUEST, cfg.id, String.valueOf(litres)), true)) {
            ui.log("Not connected - request not sent");
        }
    }

    void toggleLeak() {
        ui.log(model.toggleLeak() ? "local leak ON (+12 L/s)" : "local leak OFF");
        ui.update(model.snapshot());
    }

    void togglePause() {
        ui.log(model.togglePause() ? "demand paused" : "demand resumed");
        ui.update(model.snapshot());
    }

    //  main

    public static void main(String[] args) {
        Config cfg;
        boolean noGui = java.awt.GraphicsEnvironment.isHeadless();
        if (args.length == 0 && !noGui) {
            cfg = TankNodeWindow.askConfig();          // small start dialog
            if (cfg == null) return;                  // cancelled
        } else {
            cfg = parseArgs(args);
            if (cfg == null) { System.exit(2); return; }
        }
        if (noGui) cfg.headless = true;

        final TankNode node;
        if (cfg.headless) {
            node = new TankNode(cfg, new ConsoleUi());
            Runtime.getRuntime().addShutdownHook(new Thread(node::shutdown, "shutdown"));
            node.start();
        } else {
            node = new TankNode(cfg, new ConsoleUi());
            final TankNode n = node;
            javax.swing.SwingUtilities.invokeLater(() -> {
                TankNodeWindow w = new TankNodeWindow(n);
                n.setUi(w);
                w.setVisible(true);
                n.start();
            });
        }
    }
}
