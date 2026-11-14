package aquanet.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable picture of the whole water network at one tick.
 * Wire form (no '|' inside):  tick#mode#clients#tank;tank;...#pump;pump#valve;valve
 * where each tank/pump/valve is a comma separated record.
 */
public final class NetworkSnapshot {
    public final long tick;
    public final String mode;   // "AUTO" or "MANUAL"
    public final int clients;   // connected TCP clients
    public final List<TankInfo> tanks;
    public final List<PumpInfo> pumps;
    public final List<ValveInfo> valves;

    public NetworkSnapshot(long tick, String mode, int clients,
                           List<TankInfo> tanks, List<PumpInfo> pumps, List<ValveInfo> valves) {
        this.tick = tick; this.mode = mode; this.clients = clients;
        this.tanks = Collections.unmodifiableList(new ArrayList<TankInfo>(tanks));
        this.pumps = Collections.unmodifiableList(new ArrayList<PumpInfo>(pumps));
        this.valves = Collections.unmodifiableList(new ArrayList<ValveInfo>(valves));
    }

    public TankInfo tank(String id) {
        for (TankInfo t : tanks) if (t.id.equals(id)) return t;
        return null;
    }

    public String toWire() {
        return tick + "#" + mode + "#" + clients + "#" + join(tanksW()) + "#" + join(pumpsW()) + "#" + join(valvesW());
    }

    private List<String> tanksW() { List<String> l = new ArrayList<String>(); for (TankInfo t : tanks) l.add(t.toWire()); return l; }
    private List<String> pumpsW() { List<String> l = new ArrayList<String>(); for (PumpInfo p : pumps) l.add(p.toWire()); return l; }
    private List<String> valvesW() { List<String> l = new ArrayList<String>(); for (ValveInfo v : valves) l.add(v.toWire()); return l; }
    private static String join(List<String> l) { return String.join(";", l); }

    public static NetworkSnapshot fromWire(String s) {
        String[] p = s.split("#", -1);
        List<TankInfo> tanks = new ArrayList<TankInfo>();
        List<PumpInfo> pumps = new ArrayList<PumpInfo>();
        List<ValveInfo> valves = new ArrayList<ValveInfo>();
        for (String x : p[3].split(";")) if (!x.isEmpty()) tanks.add(TankInfo.fromWire(x));
        for (String x : p[4].split(";")) if (!x.isEmpty()) pumps.add(PumpInfo.fromWire(x));
        for (String x : p[5].split(";")) if (!x.isEmpty()) valves.add(ValveInfo.fromWire(x));
        return new NetworkSnapshot(Long.parseLong(p[0]), p[1], Integer.parseInt(p[2]), tanks, pumps, valves);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tick\":").append(tick).append(",\"mode\":").append(q(mode))
          .append(",\"clients\":").append(clients).append(",\"tanks\":[");
        for (int i = 0; i < tanks.size(); i++) { if (i > 0) sb.append(','); sb.append(tanks.get(i).toJson()); }
        sb.append("],\"pumps\":[");
        for (int i = 0; i < pumps.size(); i++) { if (i > 0) sb.append(','); sb.append(pumps.get(i).toJson()); }
        sb.append("],\"valves\":[");
        for (int i = 0; i < valves.size(); i++) { if (i > 0) sb.append(','); sb.append(valves.get(i).toJson()); }
        sb.append("]}");
        return sb.toString();
    }

    /** JSON string quoting. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String jsonQuote(String s) { return q(s); }
}
