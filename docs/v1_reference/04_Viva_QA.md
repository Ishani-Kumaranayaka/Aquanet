# AquaNet - Viva / Exam Questions and Model Answers

Short model answers. Learn the ideas, then say them in your own words and point to the real code (class names in brackets).

---

### Sockets and protocols

**1. What is a socket?**
One end point of a two-way communication link between two programs, identified by an IP address and a port. In Java: `Socket`/`ServerSocket` for TCP, `DatagramSocket` for UDP.

**2. Explain the difference between `ServerSocket` and `Socket`.**
`ServerSocket` only *listens* on a port and creates a new connected `Socket` for each client when `accept()` returns. The `Socket` is the actual connection with an input and an output stream. In AquaNet: `AquaNetServer` owns the `ServerSocket` on port 5000; each accepted `Socket` is handed to a `ClientHandler`.

**3. TCP vs UDP - what is the difference and where do you use each?**
TCP is connection-oriented, reliable (acknowledgements, retransmission), ordered, with flow and congestion control, but it is a byte stream with more overhead. UDP is connectionless, has no delivery, order or duplicate guarantee, but is light and keeps message boundaries. AquaNet uses TCP for commands, state and alerts (must not be lost or reordered) and UDP for sensor readings (small, frequent, perishable).

**4. Why UDP for sensors?**
A reading is only useful for about a second; if one is lost the next replaces it. Retransmitting an old value (TCP) is useless and can delay newer ones (head-of-line blocking). UDP has no handshake and no per-connection state, so many sensors are cheap. Sequence numbers let us detect problems (`SensorUdpListener`).

**5. UDP is unreliable - how does your program deal with it?**
Each datagram carries a per-tank sequence number. The listener counts lost (gaps), out-of-order and duplicate datagrams and logs a summary every 10 s. Missing readings are harmless because the next one arrives a second later; the displayed `measured` value just goes offline (`-1`) after 5 s without data. Malformed datagrams are ignored and counted. The true simulation never depends on UDP.

**6. What does the TCP three-way handshake do?**
SYN, SYN-ACK, ACK: both sides agree on initial sequence numbers and confirm that they can reach each other before data is sent. `new Socket(host, port)` triggers it and `accept()` returns after it completes.

**7. What is a port and why do we use 5000 / 5001 / 8080?**
A 16-bit number that selects the application on a host. Ports above 1023 do not need administrator rights. We chose 5000 (TCP clients), 5001 (UDP sensors), 8080 (HTTP, the usual alternative to 80). TCP and UDP port spaces are separate, so the same number could be used for both.

**8. Why a line-based text protocol?**
TCP is a stream with no message boundaries, so we must frame messages. One line per message with `\n` as the delimiter is simple, works with `BufferedReader.readLine()`, is human-readable (debug with `telnet`/`nc`), and easy to extend. Disadvantages: bigger than binary and the delimiter must not appear in the data - we use `Protocol.clean` to remove `|` and newlines from user text.

**9. How is a message structured?**
`KEYWORD|field|field...`, for example `CMD|VALVE|V1|75`. `Protocol.split(line, limit)` keeps extra `|` in the last field. The state snapshot is one big field using `#`, `;` and `,` as inner separators so it has no `|`.

**10. What is the difference between `ACK` and `STATE`?**
`ACK`/`ERR` answer one command from one client. `STATE` is *pushed* to every client each tick whether or not it asked. Push is better than polling here: lower latency, no wasted requests, all clients see the same tick.

---

### Threads and synchronization

**11. Why does the server need multiple threads?**
`accept()` and `readLine()` are blocking calls. With one thread, the server could serve only one client at a time and could not tick while waiting. AquaNet uses a tick thread, an accept loop, a reader thread and a writer thread per client, a UDP thread and web worker threads.

**12. Explain the `ServerSocket` accept loop.**
`while (running) { Socket s = serverSocket.accept(); create ClientHandler; pool.execute(handler); }` The main thread only accepts and delegates, so it is ready immediately for the next client. Each handler blocks on its own client. (`AquaNetServer.run`)

**13. How are multiple clients handled at the same time?**
Thread-per-client from a cached thread pool, all sharing one `SimulationEngine`. Commands from any client go through `engine.execute`, which is synchronised. The tick broadcasts the same `STATE` line to every client's outbox queue.

**14. Why a pool (`Executors.newCachedThreadPool`) rather than `new Thread` each time?**
It reuses threads and manages their lifecycle, and `shutdownNow()` stops them on exit. For huge numbers of clients one would use NIO/selectors or a bounded pool.

**15. What is a race condition? Give an example from AquaNet.**
Result depends on the timing of threads. Example: the tick thread computing new levels while a client thread changes a valve, or the UDP thread storing `measured` while the tick thread builds the snapshot. Without a lock one could read half-updated state.

**16. How do you synchronise access to the simulation?**
A single lock object in `SimulationEngine`; `execute`, `doTick`, `updateSensor`, `snapshot` and `setClientCount` all take it, for as short a time as possible. Snapshots are immutable so they can be shared without locks afterwards.

**17. What is a deadlock and how did you avoid it?**
Threads wait for each other's locks forever. We have only one engine lock and never call listeners, write to sockets or take another lock while holding it: events are collected in a list inside the lock and fired after leaving it.

**18. What does `volatile` do? Where do you use it?**
It guarantees visibility of a variable's latest value across threads (and prevents certain reordering), but not atomic compound actions. Used for flags such as `running`, `closed`, `name` (set in the reader thread, read elsewhere).

**19. Why are `CopyOnWriteArrayList` and `BlockingQueue` used?**
`CopyOnWriteArrayList` can be iterated safely while another thread adds or removes (client list, listeners, SSE clients) - good when reads are many and writes rare. `LinkedBlockingQueue` is a thread-safe hand-over between producer (tick thread) and consumer (writer thread).

**20. Why does each client have a writer thread and an outbox queue?**
If the tick thread wrote to sockets directly, one slow or frozen client would block the tick and therefore everybody. With a bounded queue and `offer()` (non-blocking), a slow client only loses its own lines. A single writer also prevents two threads interleaving bytes in the same stream.

**21. What happens when a client disconnects?**
Graceful: client sends `QUIT`, server answers `ACK|bye`. Abrupt: `readLine()` returns `null` or throws `IOException`. In both cases the `finally` block of `ClientHandler.run` calls `close()` (idempotent, closes the socket, wakes the writer) and `server.clientGone()` removes it from the list and updates the client count. Other clients are unaffected and the server keeps running.

**22. How does the server notice a dead client that does not send anything?**
A closed or reset connection gives an I/O error at once. A silently lost peer (cable pulled) is noticed when writes fail, when the TCP stack times out, or when the outbox fills. A heartbeat (PING every few seconds and a timeout) would make it faster - a possible improvement.

---

### GUI

**23. Why `SwingUtilities.invokeLater`?**
Swing is not thread-safe: components must be created and changed only on the Event Dispatch Thread. Network callbacks arrive on the reader thread, so `DashboardFrame` wraps each GUI update in `invokeLater`, which queues it for the EDT. This also keeps blocking I/O off the EDT so the window never freezes.

**24. How does the GUI avoid freezing during network operations?**
Connecting and reading happen in `ConnectionManager`'s own thread; writes are short; the EDT only paints and handles events. A Swing `Timer` (running on the EDT) drives animation.

**25. Why does the GUI animate between server updates?**
The server updates once a second, which would look jumpy. `TankPanel` eases its displayed level toward the real level about 25 times per second; the server remains the source of truth.

---

### Web

**26. What is HTTP and how does your web server work?**
HTTP is a text request/response protocol over TCP: request line, headers, blank line, optional body; response with status line, headers, body. `WebServer` uses the JDK's `com.sun.net.httpserver.HttpServer` with handlers per path (`/`, `/api/state`, `/api/history`, `/api/alerts`, `/api/command`, `/events`) and a thread pool.

**27. What is Server-Sent Events and how does it compare to WebSocket?**
SSE: the browser sends a normal GET; the server answers `Content-Type: text/event-stream` and keeps the connection open, writing `event:`/`data:` blocks. `EventSource` parses them and reconnects automatically. It is one-way, plain HTTP, no handshake upgrade. WebSocket upgrades the connection to a full-duplex, framed protocol: bidirectional and lower overhead, but more complex. Our dashboard only receives data (commands use `POST /api/command`), so SSE is simpler and sufficient.

**28. How do you keep a slow browser from blocking the simulation?**
The state listener only puts the string into that browser's queue (non-blocking); the worker thread serving `/events` writes it. If a queue grows past a limit the client is dropped. Idle streams get a `: keep-alive` comment every 15 s, which also makes dead connections fail quickly.

**29. Is the web server secure? What about path traversal?**
Not in a production sense (no HTTPS or login). But static file serving rejects `..`, backslashes and NUL characters and checks that the normalised path still starts with the web root.

---

### Design and miscellaneous

**30. Why separate `NetworkController` from `SimulationEngine`?**
It is the contract between the model and the front-ends. TCP handlers, the web server and tests depend only on the interface (decoupling, easier testing, can replace the engine). `AquaNetServer` even loads the web server by reflection, so the server runs without it.

**31. Why immutable snapshot objects?**
They can be handed to many threads (clients, web, history) without locking and without anyone seeing a half-updated state. The engine builds a new one each tick.

**32. How does the AUTO mode avoid rapid pump on/off switching?**
Hysteresis: the pump turns ON below 40 % and OFF above 85 % of T1, not at a single threshold. Valves also move at most 10 points per tick.

**33. How does the simulation keep tanks within physical limits?**
Every transfer is clamped: valve flow limited by available water upstream and free space downstream; the pump limited by free space; drains limited by the current level. So `0 <= level <= capacity`.

**34. Why scheduleAtFixedRate, and what if one tick throws an exception?**
Fixed-rate keeps a steady 1 s period (no drift from processing time). An uncaught exception would silently cancel all future runs, so `safeTick` catches `Throwable`.

**35. What would you do for security and scalability?**
TLS (`SSLServerSocket`/HTTPS), login and roles, HMAC-signed sensor packets, input limits and rate limiting; for scalability NIO (`Selector`) or a bounded pool, delta updates, database logging.

**36. What did you learn / what was the hardest part?** [TEAM TO FILL - e.g. "avoiding deadlock by firing events outside the lock", "framing", "EDT discipline".]

---

### Quick "trap" questions

| Question | Short answer |
|----------|--------------|
| Does TCP preserve message boundaries? | No, it is a stream; that is why we frame with newlines. |
| Does one `send()` on UDP equal one `receive()`? | Yes, if it arrives; boundaries are kept but delivery is not guaranteed. |
| Can the same port number be used for TCP and UDP? | Yes, they are separate port spaces. |
| What does `readLine()` return at end of stream? | `null`. |
| What does `accept()` return? | A new connected `Socket` for that client. |
| Is `synchronized` a guarantee against deadlock? | No, it prevents races; deadlock needs careful lock ordering. |
| Why `setTcpNoDelay(true)`? | Disables Nagle's algorithm so small line messages are sent immediately. |
| Why not update Swing from the reader thread? | Swing is single-threaded; use the EDT. |
| What is `SO_TIMEOUT` for the UDP socket? | Makes `receive()` time out after 1 s so the loop can print summaries and notice shutdown. |
