package aquanet.server;

import aquanet.common.Protocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Talks to ONE area tank program for as long as it stays connected (runs on its own thread).
 *
 * Reading: a blocking readLine() loop - NODE (register), REPORT (heartbeat + level), REQUEST (please send water).
 * Writing: replies from this thread, and pushes (SUPPLY / LEAK / RESET / GRANT) from other threads through
 * {@link #send}, which is synchronized so two threads never interleave half-lines on the same socket.
 */
class NodeHandler implements Runnable, DistributionEngine.NodeLink {
    private final DistributionEngine engine;
    private final Socket socket;
    private PrintWriter out;
    private String nodeId;           // set after a successful NODE registration

    NodeHandler(DistributionEngine engine, Socket socket) {
        this.engine = engine;
        this.socket = socket;
    }

    public synchronized void send(String line) {
        if (out != null) out.println(line);       // PrintWriter(autoflush) never throws; check errors via checkError()
    }

    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            synchronized (this) {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            }
            String line;
            while ((line = in.readLine()) != null) {                 // null = the other side closed the connection
                if (!handle(line.trim())) break;
            }
        } catch (SocketTimeoutException e) {
            AquaNetServer.log("Tank " + nodeId + " timed out (no data for 8 s)");
        } catch (IOException e) {
            // connection reset etc. - treated like a disconnect
        } finally {
            if (nodeId != null) engine.nodeGone(nodeId, this);
            try { socket.close(); } catch (IOException ignored) { }
            AquaNetServer.log("Tank program disconnected" + (nodeId == null ? "" : ": " + nodeId));
        }
    }

    /** @return false to end the connection. */
    private boolean handle(String line) {
        if (line.isEmpty()) return true;
        String[] f = Protocol.split(line, 6);
        String kind = f[0].toUpperCase();
        try {
            if (kind.equals(Protocol.NODE) && f.length >= 5) {
                String err = engine.registerNode(f[1], f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]), this);
                if (err != null) { send(Protocol.join(Protocol.ERR, err)); return false; }
                nodeId = f[1];
                send(Protocol.join(Protocol.REGISTERED, nodeId, Integer.toString(Protocol.TICK_MILLIS)));
            } else if (nodeId == null) {
                send(Protocol.join(Protocol.ERR, "send NODE|id|name|capacity|level first"));
                return false;
            } else if (kind.equals(Protocol.REPORT) && f.length >= 6) {
                engine.handleReport(nodeId, Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]), "1".equals(f[5].trim()));
            } else if (kind.equals(Protocol.REQUEST) && f.length >= 3) {
                send(engine.handleRequest(nodeId, Double.parseDouble(f[2])));
            } else if (kind.equals(Protocol.PING)) {
                send(Protocol.PONG);
            } else if (kind.equals(Protocol.BYE)) {
                return false;
            } else {
                send(Protocol.join(Protocol.ERR, "unknown or incomplete message"));
            }
        } catch (NumberFormatException e) {
            send(Protocol.join(Protocol.ERR, "bad number in message"));
        }
        return true;
    }
}
