package aquanet.server;

import aquanet.common.NetworkController;
import aquanet.common.NetworkSnapshot;
import aquanet.common.Protocol;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Entry point of the AquaNet server.
 *
 * Starts: the simulation engine (tick thread), the TCP accept loop (main thread) with a cached thread
 * pool running one {@link ClientHandler} per client, the UDP sensor listener, and optionally the web
 * server (loaded by reflection so this module compiles without the web package).
 *
 * Usage: {@code java aquanet.server.AquaNetServer [tcpPort] [--tcp-port N] [--udp-port N] [--http-port N] [--no-web]}
 *
 * Broadcast design: the engine calls our state/event listeners on ITS thread; we merely put lines into each
 * client's outbox queue (non-blocking), the per-client writer threads do the actual socket writes.
 */
public class AquaNetServer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); // thread-safe

    /** Timestamped, thread-safe console logging used by all server classes. */
    public static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }

    private final DistributionEngine engine = new DistributionEngine();
    // Thread-safe list: iterated by the tick thread while accept/handler threads add and remove.
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<ClientHandler>();
    private final AtomicInteger clientSeq = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private SensorUdpListener udp;
    private NodeServer nodes;
    private Object webServer; // aquanet.web.WebServer, accessed only reflectively

    public DistributionEngine engine() { return engine; }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        int tcpPort = Protocol.TCP_PORT;
        int httpPort = Protocol.HTTP_PORT;
        int udpPort = Protocol.UDP_PORT;
        boolean web = true;
        int nodePort = Protocol.NODE_PORT;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--no-web")) web = false;
                else if (a.equals("--tcp-port") && i + 1 < args.length) tcpPort = Integer.parseInt(args[++i]);
                else if (a.equals("--node-port") && i + 1 < args.length) nodePort = Integer.parseInt(args[++i]);
                else if (a.equals("--udp-port") && i + 1 < args.length) udpPort = Integer.parseInt(args[++i]);
                else if (a.equals("--http-port") && i + 1 < args.length) httpPort = Integer.parseInt(args[++i]);
                else if (a.matches("\\d+")) tcpPort = Integer.parseInt(a); // bare number = TCP port override
                else { System.err.println("Unknown argument: " + a); System.err.println("Flags: --no-web --tcp-port N --node-port N --udp-port N --http-port N"); return; }
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + e.getMessage());
            return;
        }
        new AquaNetServer().run(tcpPort, udpPort, httpPort, nodePort, web);
    }

    // ------------------------------------------------------------------ lifecycle

    private void run(int tcpPort, int udpPort, int httpPort, int nodePort, boolean web) {
        System.out.println("==========================================");
        System.out.println("  AquaNet - Galle Main Tank (Distribution Server)");
        System.out.println("==========================================");

        // Listeners: push every tick's state and every alert to all clients.
        engine.addStateListener(new Consumer<NetworkSnapshot>() {
            public void accept(NetworkSnapshot s) { broadcast(Protocol.STATE + "|" + s.toWire()); }
        });
        engine.addEventListener(new Consumer<String>() {
            public void accept(String alert) {
                log(alert);
                broadcast(alert); // already of the form ALERT|LEVEL|message
            }
        });

        try {
            serverSocket = new ServerSocket(tcpPort);
        } catch (IOException e) {
            System.err.println("Cannot listen on TCP port " + tcpPort + ": " + e.getMessage());
            return;
        }
        try {
            udp = new SensorUdpListener(engine, udpPort);
            udp.start();
        } catch (SocketException e) {
            log("WARNING: UDP sensor listener not started on port " + udpPort + ": " + e.getMessage());
            udp = null;
        }
        try {
            nodes = new NodeServer(engine, nodePort);
            nodes.start();
        } catch (IOException e) {
            System.err.println("Cannot listen on node port " + nodePort + ": " + e.getMessage());
            return;
        }
        if (web) startWeb(httpPort);

        engine.start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() { shutdown(); }
        }, "shutdown-hook"));

        log("TCP clients : port " + tcpPort);
        log("Area tanks  : TCP port " + nodePort + "  (run aquanet.node.TankNode on each area laptop)");
        log("UDP sensors : port " + udpPort + (udp == null ? " (DISABLED)" : ""));
        log("Web UI      : " + (webServer != null ? "http://localhost:" + httpPort + "/" : "not running"));
        log("Tick        : " + Protocol.TICK_MILLIS + " ms.  Press Ctrl+C to stop.");

        // Accept loop: accept() blocks until a client connects; each client gets its own handler task.
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true); // small line messages: don't wait to coalesce packets
                String id = "C" + clientSeq.incrementAndGet();
                ClientHandler h = new ClientHandler(this, s, id);
                clients.add(h);
                engine.setClientCount(clients.size());
                log("Client connected: " + id + " from " + s.getRemoteSocketAddress() + " (total " + clients.size() + ")");
                pool.execute(h);
            } catch (IOException e) {
                if (running) log("accept error: " + e.getMessage());
            }
        }
    }

    /** Loads aquanet.web.WebServer reflectively; the server still works if the class is absent. */
    private void startWeb(int httpPort) {
        try {
            Class<?> c = Class.forName("aquanet.web.WebServer");
            Object ws = c.getConstructor(NetworkController.class, int.class, String.class)
                         .newInstance(engine, httpPort, "web");
            Method start = c.getMethod("start");
            start.invoke(ws);
            webServer = ws;
        } catch (ClassNotFoundException e) {
            log("NOTE: aquanet.web.WebServer not found - running without the website.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log("WARNING: web server failed to start: " + cause);
        }
    }

    // ------------------------------------------------------------------ called by ClientHandler

    /** Sends a line to every connected client (non-blocking, see ClientHandler.send). */
    void broadcast(String line) {
        for (ClientHandler h : clients) h.send(line);
    }

    void onHello(ClientHandler h) {
        log("Client identified: " + h);
    }

    /** Called once from a handler's finally-block when its connection ends. */
    void clientGone(ClientHandler h) {
        if (clients.remove(h)) {
            engine.setClientCount(clients.size());
            log("Client disconnected: " + h + " (total " + clients.size() + ")");
        }
    }

    // ------------------------------------------------------------------ shutdown

    private void shutdown() {
        if (!running) return;
        running = false;
        log("Shutting down...");
        engine.stop();
        if (udp != null) udp.stop();
        if (nodes != null) nodes.stop();
        if (webServer != null) {
            try { webServer.getClass().getMethod("stop").invoke(webServer); } catch (Exception ignored) { }
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (ClientHandler h : clients) h.close();
        pool.shutdownNow();
        try { pool.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
