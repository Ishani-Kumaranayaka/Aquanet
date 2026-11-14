package aquanet.server;

import aquanet.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Serves ONE connected TCP client. Implements the line protocol documented in {@link Protocol}.
 *
 * Threading: each client uses two threads.
 *  - the READER thread (this Runnable, started from the server's thread pool) blocks on readLine() and
 *    processes incoming messages;
 *  - a WRITER thread takes outgoing lines from a queue and writes them to the socket.
 * The queue decouples everybody else (the simulation tick, chat broadcasts) from the socket: a slow or
 * frozen client can never block the simulation, its queue just fills up and further STATE/ALERT lines are
 * dropped. A single writer thread also guarantees lines are never interleaved on the wire.
 */
public class ClientHandler implements Runnable {

    private static final int QUEUE_CAPACITY = 200;
    /** Poison pill telling the writer thread to finish after flushing earlier lines. */
    private static final String CLOSE_MARKER = new String("\u0000close");

    private final AquaNetServer server;
    private final Socket socket;
    private final String clientId;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<String>(QUEUE_CAPACITY);
    private volatile String name = null;     // null until HELLO received
    private volatile boolean closed = false;

    public ClientHandler(AquaNetServer server, Socket socket, String clientId) {
        this.server = server;
        this.socket = socket;
        this.clientId = clientId;
    }

    public String getName() { return name == null ? clientId : name; }

    /** Queues a line for sending (non-blocking). Called by any thread. Returns false if dropped. */
    public boolean send(String line) {
        if (closed) return false;
        return outbox.offer(line); // offer() never blocks: returns false when the queue is full
    }

    /** Idempotent close: marks closed, wakes the writer, closes the socket (which also unblocks readLine). */
    public void close() {
        if (closed) return;
        closed = true;
        outbox.offer(CLOSE_MARKER);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        Thread writer = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            final BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer = new Thread(new Runnable() {
                public void run() { writeLoop(out); }
            }, "client-writer-" + clientId);
            writer.setDaemon(true);
            writer.start();

            String line;
            // readLine() returns null when the peer closes the connection (clean disconnect).
            while (!closed && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!handle(line)) break;
            }
        } catch (IOException e) {
            // connection reset / socket closed: treated as a disconnect
        } finally {
            close();
            server.clientGone(this);
        }
    }

    /** Writer thread body: drains the queue to the socket until the close marker or an I/O error. */
    private void writeLoop(BufferedWriter out) {
        try {
            while (true) {
                String line = outbox.poll(30, TimeUnit.SECONDS);
                if (line == null) { if (closed) return; else continue; }
                if (line == CLOSE_MARKER) { // identity comparison on purpose: unique sentinel object
                    out.flush();
                    return;
                }
                out.write(line);
                out.write('\n');
                // Flush when nothing else is waiting, so bursts are batched but nothing lingers.
                if (outbox.isEmpty()) out.flush();
            }
        } catch (IOException e) {
            close(); // peer is gone; make the reader thread terminate too
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Processes one request line. Returns false when the client asked to quit. */
    private boolean handle(String line) {
        String[] f = Protocol.split(line, 2);
        String verb = f[0].toUpperCase();
        String rest = f.length > 1 ? f[1] : "";

        if (verb.equals(Protocol.HELLO)) {
            String n = Protocol.clean(rest);
            if (n.isEmpty()) n = clientId;
            name = n;
            send(Protocol.join(Protocol.WELCOME, clientId, Integer.toString(Protocol.TICK_MILLIS)));
            send(Protocol.STATE + "|" + server.engine().snapshot().toWire()); // first picture immediately
            server.onHello(this);
        } else if (verb.equals(Protocol.PING)) {
            send(Protocol.PONG);
        } else if (verb.equals(Protocol.QUIT)) {
            send(Protocol.ACK + "|bye");
            return false;
        } else if (name == null) {
            send(Protocol.ERR + "|send HELLO|name first");
        } else if (verb.equals(Protocol.CMD)) {
            if (rest.isEmpty()) { send(Protocol.ERR + "|missing command"); return true; }
            AquaNetServer.log("CMD from " + name + ": " + rest);
            String reply = server.engine().execute(name, rest); // "OK|..." or "ERR|..."
            if (reply.startsWith("OK|")) send(Protocol.ACK + "|" + reply.substring(3));
            else if (reply.startsWith("ERR|")) send(reply);
            else send(Protocol.ACK + "|" + reply);
        } else if (verb.equals(Protocol.CHAT)) {
            String text = Protocol.clean(rest);
            if (!text.isEmpty()) {
                AquaNetServer.log("CHAT " + name + ": " + text);
                server.broadcast(Protocol.join(Protocol.CHAT, name, text));
            }
        } else {
            send(Protocol.ERR + "|unknown message '" + Protocol.clean(f[0]) + "'");
        }
        return true;
    }

    @Override
    public String toString() { return getName() + "@" + socket.getRemoteSocketAddress(); }
}
