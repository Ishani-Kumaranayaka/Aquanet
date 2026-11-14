package aquanet.common;

/** Immutable snapshot of one water tank. Litres, litres per second. */
public final class TankInfo {
    public static final String OK = "OK", LOW = "LOW", HIGH = "HIGH", EMPTY = "EMPTY", OVERFLOW = "OVERFLOW", OFFLINE = "OFFLINE";

    public final String id;
    public final String name;
    public final double level;     // true simulated level (litres)
    public final double capacity;  // litres
    public final double inflow;    // L/s
    public final double outflow;   // L/s (valves + demand + leak)
    public final double measured;  // last UDP sensor reading, -1 if no sensor online
    public final boolean leaking;
    public final boolean online;   // false = the sub tank program stopped reporting

    public TankInfo(String id, String name, double level, double capacity,
                    double inflow, double outflow, double measured, boolean leaking) {
        this(id, name, level, capacity, inflow, outflow, measured, leaking, true);
    }

    public TankInfo(String id, String name, double level, double capacity,
                    double inflow, double outflow, double measured, boolean leaking, boolean online) {
        this.id = id; this.name = name; this.level = level; this.capacity = capacity;
        this.inflow = inflow; this.outflow = outflow; this.measured = measured; this.leaking = leaking;
        this.online = online;
    }

    public double percent() { return capacity <= 0 ? 0 : 100.0 * level / capacity; }

    public String status() {
        if (!online) return OFFLINE;
        double p = percent();
        if (level <= 0.5) return EMPTY;
        if (p >= 99.5) return OVERFLOW;
        if (p < 20) return LOW;
        if (p > 90) return HIGH;
        return OK;
    }

    String toWire() {
        return String.join(",", Protocol.clean(id).replace(",", ""), Protocol.clean(name).replace(",", " "),
                f(level), f(capacity), f(inflow), f(outflow), f(measured), leaking ? "1" : "0", online ? "1" : "0");
    }

    static TankInfo fromWire(String s) {
        String[] p = s.split(",");
        return new TankInfo(p[0], p[1], d(p[2]), d(p[3]), d(p[4]), d(p[5]), d(p[6]), "1".equals(p[7]), p.length < 9 || "1".equals(p[8]));
    }

    String toJson() {
        return "{\"id\":" + NetworkSnapshot.q(id) + ",\"name\":" + NetworkSnapshot.q(name)
                + ",\"level\":" + f(level) + ",\"capacity\":" + f(capacity)
                + ",\"percent\":" + f(percent()) + ",\"inflow\":" + f(inflow)
                + ",\"outflow\":" + f(outflow) + ",\"measured\":" + f(measured)
                + ",\"leaking\":" + leaking + ",\"online\":" + online + ",\"status\":" + NetworkSnapshot.q(status()) + "}";
    }

    static String f(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    static double d(String s) { return Double.parseDouble(s); }
}
