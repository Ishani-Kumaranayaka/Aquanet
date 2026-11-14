package aquanet.test;

import aquanet.common.NetworkSnapshot;
import aquanet.common.Protocol;
import aquanet.common.PumpInfo;
import aquanet.common.TankInfo;
import aquanet.common.ValveInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal text-mode AquaNet client (no GUI) - good for learning the protocol.
 *
 * Usage: java aquanet.test.ConsoleClient [host] [port] [name] [--quiet]
 *   --quiet : print STATE only every 10th message (default prints every one)
 *
 * Commands typed on STDIN and the protocol line each one becomes:
 *   pump P1 on|off   -> CMD|PUMP|P1|ON
 *   valve V1 50      -> CMD|VALVE|V1|50
 *   mode auto|manual -> CMD|MODE|AUTO
 *   leak T2 on|off   -> CMD|LEAK|T2|ON
 *   reset            -> CMD|RESET
 *   chat hello all   -> CHAT|hello all
 *   ping             -> PING
 *   raw <line>       -> sent unchanged
 *   quit             -> QUIT
 *
 * Socket structure: a java.net.Socket (TCP) gives two streams. The server pushes STATE
 * every tick without being asked, so reading must not block typing: the main thread reads
 * STDIN and writes to the socket while a second thread blocks in readLine() on the socket.
 */
public class ConsoleClient {

    private static int stateCount = 0;

    public static void main(String[] args) throws Exception {
        String host = "localhost"; int port = Protocol.TCP_PORT; String name = "console";
        boolean quiet = false; int pos = 0;
        for (String a : args) {
            if (a.equals("--quiet")) quiet = true;
            else if (pos == 0) { host = a; pos++; }
            else if (pos == 1) { port = Integer.parseInt(a); pos++; }
            else if (pos == 2) { name = a; pos++; }
        }
        final boolean fq = quiet;
        final Socket socket = new Socket(host, port);
        final PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out.println(Protocol.join(Protocol.HELLO, Protocol.clean(name)));

        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    String line;
                    while ((line = in.readLine()) != null) show(line, fq);
                    System.out.println("[server closed the connection]");
                } catch (IOException e) {
                    System.out.println("[connection closed]");
                }
                // Reader ended -> nothing more to do (STDIN may still be blocked).
                try { System.out.flush(); } catch (RuntimeException e) { }
                System.exit(0);
            }
        }, "console-reader");
        reader.setDaemon(true);
        reader.start();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = stdin.readLine()) != null) {
            String wire = translate(line.trim());
            if (wire == null) continue;
            out.println(wire);
            if (wire.equals(Protocol.QUIT)) break;
        }
        // STDIN ended (EOF): leave politely, give the last replies a moment to arrive.
        out.println(Protocol.QUIT);
        Thread.sleep(500);
        socket.close();
    }

    /** Translate a user-friendly command into a protocol line; null = nothing to send. */
    static String translate(String s) {
        if (s.isEmpty()) return null;
        String[] w = s.split("\\s+");
        String c = w[0].toLowerCase();
        if (c.equals("pump") && w.length == 3) return Protocol.join(Protocol.CMD, "PUMP", w[1].toUpperCase(), w[2].toUpperCase());
        if (c.equals("valve") && w.length == 3) return Protocol.join(Protocol.CMD, "VALVE", w[1].toUpperCase(), w[2]);
        if (c.equals("mode") && w.length == 2) return Protocol.join(Protocol.CMD, "MODE", w[1].toUpperCase());
        if (c.equals("leak") && w.length == 3) return Protocol.join(Protocol.CMD, "LEAK", w[1].toUpperCase(), w[2].toUpperCase());
        if (c.equals("reset")) return Protocol.join(Protocol.CMD, "RESET");
        if (c.equals("chat") && s.length() > 5) return Protocol.join(Protocol.CHAT, Protocol.clean(s.substring(5)));
        if (c.equals("ping")) return Protocol.PING;
        if (c.equals("quit") || c.equals("exit")) return Protocol.QUIT;
        if (c.equals("raw") && s.length() > 4) return s.substring(4);
        System.out.println("? commands: pump P1 on | valve V1 50 | mode auto | leak T2 on | reset | chat text | ping | raw LINE | quit");
        return null;
    }

    /** Pretty-print one server line. */
    static synchronized void show(String line, boolean quiet) {
        String[] p = Protocol.split(line, 3);
        String type = p[0];
        if (type.equals(Protocol.STATE) && p.length >= 2) {
            stateCount++;
            if (quiet && stateCount % 10 != 1) return;
            try {
                NetworkSnapshot s = NetworkSnapshot.fromWire(line.substring(Protocol.STATE.length() + 1));
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("--- tick %d  mode=%s  clients=%d ---%n", s.tick, s.mode, s.clients));
                sb.append(String.format("%-4s %-14s %9s %9s %6s %7s %7s %9s %-8s%n",
                        "ID", "TANK", "level(L)", "cap(L)", "%", "in", "out", "sensor", "status"));
                for (TankInfo t : s.tanks) {
                    sb.append(String.format(java.util.Locale.ROOT, "%-4s %-14s %9.1f %9.0f %5.1f%% %7.2f %7.2f %9s %-8s%s%n",
                            t.id, t.name, t.level, t.capacity, t.percent(), t.inflow, t.outflow,
                            t.measured < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f", t.measured),
                            t.status(), t.leaking ? " LEAK" : ""));
                }
                sb.append("pumps:");
                for (PumpInfo pu : s.pumps) sb.append(' ').append(pu.id).append('=').append(pu.on ? "ON" : "off");
                sb.append("  valves:");
                for (ValveInfo v : s.valves) sb.append(' ').append(v.id).append('=').append(v.openPercent).append('%');
                System.out.println(sb);
            } catch (RuntimeException e) {
                System.out.println("[unparsable STATE: " + e + "]");
            }
        } else if (type.equals(Protocol.ALERT)) {
            System.out.println("!! ALERT " + (p.length > 1 ? p[1] : "") + ": " + (p.length > 2 ? p[2] : ""));
        } else if (type.equals(Protocol.CHAT)) {
            System.out.println("<" + (p.length > 1 ? p[1] : "?") + "> " + (p.length > 2 ? p[2] : ""));
        } else {
            System.out.println(line);   // WELCOME, ACK, ERR, PONG
        }
    }
}
