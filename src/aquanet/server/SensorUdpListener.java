package aquanet.server;

import aquanet.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Receives sensor readings over UDP: one datagram = one line {@code SENSOR|tankId|levelLitres|seq}.
 *
 * Why UDP? It is connectionless and cheap - ideal for a stream of frequent readings where an old value
 * is worthless anyway. The price: the network may LOSE, DUPLICATE or REORDER datagrams. To make that
 * visible, each sensor numbers its datagrams ({@code seq}); this class compares sequence numbers per tank
 * and logs a summary every 10 seconds.
 *
 * Threading: one dedicated thread runs the receive loop. {@code socket.receive()} blocks, so we set a
 * 1 s timeout (SO_TIMEOUT) to be able to print the periodic summary and to notice shutdown requests.
 * Malformed datagrams are ignored and only counted - a UDP server must never trust its input.
 */
public class SensorUdpListener implements Runnable {

    /** Per-tank sequence statistics (only used by the listener thread, so no locking needed). */
    private static final class Stats {
        long lastSeq = -1;
        long received, outOfOrder, duplicates, lost;
    }

    private final DistributionEngine engine;
    private final int port;
    private final Map<String, Stats> stats = new HashMap<String, Stats>();
    private long malformed = 0;
    private volatile boolean running = true;   // volatile: written by main/shutdown thread, read by ours
    private DatagramSocket socket;
    private Thread thread;

    public SensorUdpListener(DistributionEngine engine, int port) {
        this.engine = engine;
        this.port = port;
    }

    /** Opens the socket (so a port clash is reported immediately) and starts the receive thread. */
    public void start() throws SocketException {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(1000);
        thread = new Thread(this, "udp-sensor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close(); // unblocks receive() with a SocketException
    }

    @Override
    public void run() {
        byte[] buf = new byte[512]; // a sensor line is tiny; longer datagrams are truncated and rejected
        long nextSummary = System.currentTimeMillis() + 10000;
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet); // blocks up to 1 s
                handle(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
            } catch (SocketTimeoutException e) {
                // normal: nothing arrived this second
            } catch (IOException e) {
                if (running) AquaNetServer.log("UDP receive error: " + e);
            } catch (RuntimeException e) {
                malformed++; // never let one bad packet kill the listener thread
            }
            if (System.currentTimeMillis() >= nextSummary) {
                nextSummary += 10000;
                logSummary();
            }
        }
    }

    /** Parses and applies one datagram; malformed input is counted and dropped. */
    private void handle(String text) {
        String[] f = Protocol.split(text.trim(), -1);
        if (f.length != 4 || !f[0].equals(Protocol.SENSOR)) { malformed++; return; }
        String tank = f[1].trim().toUpperCase();
        double level;
        long seq;
        try {
            level = Double.parseDouble(f[2].trim());
            seq = Long.parseLong(f[3].trim());
        } catch (NumberFormatException e) { malformed++; return; }
        if (Double.isNaN(level) || Double.isInfinite(level) || level < 0 || seq < 0 || !engine.hasTank(tank)) {
            malformed++;
            return;
        }
        engine.updateSensor(tank, level);

        Stats s = stats.get(tank);
        if (s == null) { s = new Stats(); stats.put(tank, s); }
        s.received++;
        if (s.lastSeq < 0) {
            s.lastSeq = seq;                       // first datagram: nothing to compare with
        } else if (seq == s.lastSeq) {
            s.duplicates++;
        } else if (seq > s.lastSeq) {
            s.lost += seq - s.lastSeq - 1;         // numbers skipped = presumed lost (may arrive late)
            s.lastSeq = seq;
        } else {
            s.outOfOrder++;                        // an older datagram arrived after a newer one
            if (s.lost > 0) s.lost--;              // it was only late, not lost after all
        }
    }

    private void logSummary() {
        if (stats.isEmpty() && malformed == 0) return;
        StringBuilder sb = new StringBuilder("UDP summary (10s):");
        for (Map.Entry<String, Stats> e : stats.entrySet()) {
            Stats s = e.getValue();
            sb.append(' ').append(e.getKey()).append("[rx=").append(s.received)
              .append(" lost=").append(s.lost).append(" outOfOrder=").append(s.outOfOrder)
              .append(" dup=").append(s.duplicates).append(']');
        }
        sb.append(" malformed=").append(malformed);
        AquaNetServer.log(sb.toString());
    }
}
