package aquanet.node;

import java.util.Random;
final class TankModel {

    /** Extra drain (litres per second) while a leak is ON. */
    static final double LEAK_LPS = 12.0;
    /** Maximum rate at which granted water flows into the tank (litres per second). */
    static final double MAX_DELIVERY_LPS = 80.0;

    /** Immutable copy of the state, handed to the UI / network code so they never need the lock. */
    static final class Snapshot {
        final String id, name;
        final double level, capacity, demand, inflow, pending, supplyPct;
        final boolean leak, paused, connected;
        final String lastReply, lastDeny;
        /** Litres to auto-request this tick, or 0 for "no request". */
        final long requestLitres;

        Snapshot(String id, String name, double level, double capacity, double demand,
                 double inflow, double pending, double supplyPct, boolean leak, boolean paused,
                 boolean connected, String lastReply, String lastDeny, long requestLitres) {
            this.id = id; this.name = name; this.level = level; this.capacity = capacity;
            this.demand = demand; this.inflow = inflow; this.pending = pending;
            this.supplyPct = supplyPct; this.leak = leak; this.paused = paused;
            this.connected = connected; this.lastReply = lastReply; this.lastDeny = lastDeny;
            this.requestLitres = requestLitres;
        }

        double fraction() { return capacity <= 0 ? 0 : level / capacity; }
    }

    private final String id, name;
    private final double capacity, startLevel, baseDemand;
    private final Random random = new Random();

    private double level;
    private double demand;          // litres/s consumed by customers during the last tick
    private double inflow;          // litres/s delivered into the tank during the last tick
    private double pending;         // litres granted by the server but not yet delivered
    private double supplyPct = 100; // supply valve opening reported by the server
    private boolean leak, paused, connected;
    private long tickCount;
    private long lastRequestMs;     // 0 = never
    private long lastDenyMs;        // 0 = never
    private String lastReply = "-";
    private String lastDeny = "";

    TankModel(String id, String name, double capacity, double startLevel, double baseDemand) {
        this.id = id; this.name = name; this.capacity = capacity;
        this.startLevel = startLevel; this.baseDemand = baseDemand;
        this.level = startLevel;
    }

 synchronized Snapshot tick(long nowMs) {
        tickCount++;
        // Daily-pattern demand: base * (1 + 0.35 sin(2 pi t / 120s)) with +-10 % random noise.
        double wave = 1 + 0.35 * Math.sin(2 * Math.PI * tickCount / 120.0);
        double noise = 0.9 + 0.2 * random.nextDouble();
        demand = paused ? 0 : baseDemand * wave * noise;
        double drain = demand + (leak ? LEAK_LPS : 0);
        level = Math.max(0, level - drain);

        // Delivery of granted water: at most 80 L/s move from "pending" into the tank.
        double move = Math.min(pending, MAX_DELIVERY_LPS);
        double room = capacity - level;
        if (move > room) move = Math.max(0, room);
        level += move;
        pending -= move;
        inflow = move;
        if (level >= capacity - 0.001) pending = 0;   // tank full: cannot accept any more
        if (pending < 0.001) pending = 0;

        // Auto request: low (<30 %), nothing on the way, rate limited, and only if we are connected.
        long req = 0;
        if (connected && level < 0.30 * capacity && pending <= 0
                && (lastRequestMs == 0 || nowMs - lastRequestMs >= 5000)
                && (lastDenyMs == 0 || nowMs - lastDenyMs >= 8000)) {
            req = wantedLitres();
            if (req > 0) lastRequestMs = nowMs;
        }
        return snapshot(req);
    }

    /** Litres needed to bring the tank to 85 % of capacity (caller must hold the lock). */
    private long wantedLitres() {
        return Math.round(0.85 * capacity - level);
    }

    /** Manual request: returns litres to ask for (0 if the tank is already above 85 %). */
    synchronized long manualRequest(long nowMs) {
        long l = wantedLitres();
        if (l > 0) lastRequestMs = nowMs;
        return l;
    }

    synchronized Snapshot snapshot() { return snapshot(0); }

    private Snapshot snapshot(long req) {
        return new Snapshot(id, name, level, capacity, demand, inflow, pending, supplyPct, leak,
                paused, connected, lastReply, lastDeny, req);
    }

    synchronized void addPending(double litres) { if (litres > 0) pending += litres; }

    synchronized void recordDeny(String reason, long nowMs) {
        lastDenyMs = nowMs;
        lastDeny = reason;
    }

    synchronized void setLeak(boolean on) { leak = on; }
    synchronized boolean toggleLeak() { leak = !leak; return leak; }
    synchronized boolean togglePause() { paused = !paused; return paused; }
    synchronized void setSupply(double pct) { supplyPct = pct; }
    synchronized void setConnected(boolean c) { connected = c; }
    synchronized void setLastReply(String s) { lastReply = s; }

    /** Admin RESET: back to the starting level, forget any water on the way. */
    synchronized void reset() {
        level = startLevel;
        pending = 0;
        lastRequestMs = 0;
        lastDenyMs = 0;
        lastDeny = "";
    }

    synchronized double currentLevel() { return level; }

    String id() { return id; }
    String name() { return name; }
    double capacity() { return capacity; }
}
