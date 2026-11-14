package aquanet.common;

/** Immutable snapshot of a pump. */
public final class PumpInfo {
    public final String id;
    public final String name;
    public final boolean on;
    public final double flowRate; // L/s when on

    public PumpInfo(String id, String name, boolean on, double flowRate) {
        this.id = id; this.name = name; this.on = on; this.flowRate = flowRate;
    }

    String toWire() {
        return String.join(",", id, Protocol.clean(name).replace(",", " "), on ? "1" : "0", TankInfo.f(flowRate));
    }

    static PumpInfo fromWire(String s) {
        String[] p = s.split(",");
        return new PumpInfo(p[0], p[1], "1".equals(p[2]), TankInfo.d(p[3]));
    }

    String toJson() {
        return "{\"id\":" + NetworkSnapshot.q(id) + ",\"name\":" + NetworkSnapshot.q(name)
                + ",\"on\":" + on + ",\"flowRate\":" + TankInfo.f(flowRate) + "}";
    }
}
