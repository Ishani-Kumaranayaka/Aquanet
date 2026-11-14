package aquanet.common;

/** Immutable snapshot of a valve. openPercent is 0..100. */
public final class ValveInfo {
    public final String id;
    public final String name;
    public final int openPercent;
    public final String from; // tank id (upstream)
    public final String to;   // tank id (downstream)

    public ValveInfo(String id, String name, int openPercent, String from, String to) {
        this.id = id; this.name = name; this.openPercent = openPercent; this.from = from; this.to = to;
    }

    String toWire() {
        return String.join(",", id, Protocol.clean(name).replace(",", " "), Integer.toString(openPercent), from, to);
    }

    static ValveInfo fromWire(String s) {
        String[] p = s.split(",");
        return new ValveInfo(p[0], p[1], Integer.parseInt(p[2]), p[3], p[4]);
    }

    String toJson() {
        return "{\"id\":" + NetworkSnapshot.q(id) + ",\"name\":" + NetworkSnapshot.q(name)
                + ",\"open\":" + openPercent + ",\"from\":" + NetworkSnapshot.q(from)
                + ",\"to\":" + NetworkSnapshot.q(to) + "}";
    }
}
