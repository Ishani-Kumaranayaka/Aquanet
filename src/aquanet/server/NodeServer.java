package aquanet.server;

import aquanet.common.Protocol;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accepts the TCP connections of the AREA TANK programs (aquanet.node.TankNode) on Protocol.NODE_PORT.
 *
 * Classic multi-threaded server: one thread blocks in accept(); every accepted socket is handed to a
 * {@link NodeHandler} that runs on its own pool thread, so many tanks can talk to the main tank at the same time.
 */
class NodeServer implements Runnable {
    private final DistributionEngine engine;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = true;
    private Thread thread;

    NodeServer(DistributionEngine engine, int port) throws IOException {
        this.engine = engine;
        this.server = new ServerSocket(port);
    }

    void start() {
        thread = new Thread(this, "node-accept");
        thread.setDaemon(true);
        thread.start();
    }

    public void run() {
        while (running) {
            try {
                Socket s = server.accept();                 // blocks until a tank program connects
                s.setTcpNoDelay(true);
                s.setSoTimeout(8000);                        // reports come every 1 s; 8 s of silence = dead link
                AquaNetServer.log("Tank program connected from " + s.getRemoteSocketAddress());
                pool.execute(new NodeHandler(engine, s));
            } catch (IOException e) {
                if (running) AquaNetServer.log("node accept error: " + e.getMessage());
            }
        }
    }

    void stop() {
        running = false;
        try { server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }
}
