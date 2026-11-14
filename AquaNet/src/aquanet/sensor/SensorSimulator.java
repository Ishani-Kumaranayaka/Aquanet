package aquanet.sensor;

import aquanet.common.NetworkSnapshot;
import aquanet.common.Protocol;
import aquanet.common.TankInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class SensorSimulator {

    /** Latest true tank state from the TCP link (written by the reader thread, read by main). */
    private static final Map<String, TankInfo> tanks = new ConcurrentHashMap<String, TankInfo>();
    private static volatile boolean tcpUp = false;

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int tcpPort = Protocol.TCP_PORT;
        int udpPort = Protocol.UDP_PORT;
        long interval = Protocol.TICK_MILLIS;
        double loss = 0;
        String faultTank = null;

        int positional = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--loss") && i + 1 < args.length) loss = Double.parseDouble(args[++i]);
            else if (a.equals("--fault") && i + 1 < args.length) faultTank = args[++i];
            else if (a.equals("--udp") && i + 1 < args.length) udpPort = Integer.parseInt(args[++i]);
            else if (a.startsWith("--")) { usage(); return; }
            else {
                if (positional == 0) host = a;
                else if (positional == 1) tcpPort = Integer.parseInt(a);
                else if (positional == 2) interval = Long.parseLong(a);
                positional++;
            }
        }
        final String fHost = host;
        final int fTcpPort = tcpPort;

        // Background thread: keeps a TCP connection alive and mirrors the tank levels.
        Thread t = new Thread(new Runnable() {
            public void run() { tcpLoop(fHost, fTcpPort); }
        }, "sensor-tcp");
        t.setDaemon(true);
        t.start();

        InetAddress addr = InetAddress.getByName(host);
        Random rnd = new Random();
        Map<String, Long> seqs = new TreeMap<String, Long>();
        long sent = 0, dropped = 0, lastReport = System.currentTimeMillis();

        System.out.println("SensorSimulator -> udp://" + host + ":" + udpPort + " every " + interval
                + " ms, loss=" + loss + (faultTank != null ? ", faulty sensor=" + faultTank : ""));

        // An unconnected DatagramSocket on an ephemeral local port; the destination is given
        // per packet. No connect/accept: if the server is down, send() just goes nowhere.
        try (DatagramSocket udp = new DatagramSocket()) {
            while (true) {
                for (TankInfo tank : tanks.values()) {
                    long seq = seqs.containsKey(tank.id) ? seqs.get(tank.id) + 1 : 1;
                    seqs.put(tank.id, seq);   // seq advances even if the packet is dropped -> gap visible

                    double measured = tank.level + rnd.nextGaussian() * 0.01 * tank.level;
                    if (tank.id.equals(faultTank) && rnd.nextDouble() < 0.15) {
                        // broken sensor: wildly wrong value (negative or above capacity)
                        measured = rnd.nextBoolean() ? -rnd.nextDouble() * 500
                                                     : tank.capacity * (1.5 + rnd.nextDouble());
                    }
                    measured = Math.round(measured * 100.0) / 100.0;

                    if (loss > 0 && rnd.nextDouble() < loss) { dropped++; continue; }

                    String line = Protocol.join(Protocol.SENSOR, tank.id,
                            String.format(java.util.Locale.ROOT, "%.2f", measured), Long.toString(seq));
                    byte[] data = line.getBytes(StandardCharsets.UTF_8);
                    udp.send(new DatagramPacket(data, data.length, addr, udpPort));
                    sent++;
                }
                long now = System.currentTimeMillis();
                if (now - lastReport >= 10000) {
                    System.out.println("[sensor] tcp=" + (tcpUp ? "up" : "DOWN") + " tanks=" + tanks.size()
                            + " sent=" + sent + " dropped=" + dropped);
                    lastReport = now;
                }
                Thread.sleep(interval);
            }
        }
    }

    /** Connect, read STATE lines, reconnect (every 2 s) whenever the link drops. */
    private static void tcpLoop(String host, int port) {
        while (true) {
            try (Socket s = new Socket(host, port)) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                out.println(Protocol.join(Protocol.HELLO, "sensor-gw"));
                tcpUp = true;
                System.out.println("[sensor] TCP connected to " + host + ":" + port);
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.startsWith(Protocol.STATE + Protocol.SEP)) continue;
                    try {
                        NetworkSnapshot snap = NetworkSnapshot.fromWire(Protocol.split(line, 2)[1]);
                        for (TankInfo ti : snap.tanks) tanks.put(ti.id, ti);
                    } catch (RuntimeException bad) {
                        System.err.println("[sensor] bad STATE ignored: " + bad);
                    }
                }
            } catch (IOException e) {
                // fall through to retry
            }
            if (tcpUp) System.out.println("[sensor] TCP link lost, retrying...");
            tcpUp = false;
            try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
        }
    }

    private static void usage() {
        System.err.println("Usage: SensorSimulator [host] [tcpPort] [intervalMs] [--loss P] [--fault TANKID] [--udp PORT]");
    }
}
