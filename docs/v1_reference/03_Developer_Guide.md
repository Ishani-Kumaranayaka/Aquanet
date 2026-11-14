# AquaNet - Developer Guide

For students who need to read, debug or extend the code. Read together with `01_Project_Report.md` (design) and the Javadoc comments at the top of each class, which explain the "why".

## 1. Project layout

```
AquaNet/
  .project / .classpath         Eclipse project files (source folder: src, output: bin)
  src/aquanet/
    common/   Protocol, NetworkController, NetworkSnapshot, TankInfo, PumpInfo, ValveInfo
    server/   AquaNetServer, SimulationEngine, ClientHandler, SensorUdpListener
    client/   AquaNetClient, DashboardFrame, ConnectionManager, ClientListener,
              TankPanel, NetworkDiagramPanel, TrendChartPanel, ControlPanel,
              AlertChatPanel, Theme
    sensor/   SensorSimulator
    web/      WebServer
    test/     ConsoleClient, LoadTestClient, ProtocolSelfTest
  web/        index.html, dashboard.html, architecture.html, download.html, about.html,
              css/style.css, js/common.js, js/dashboard.js, favicon.svg
  scripts/    run-*.sh / run-*.bat helper scripts (compile + run)
  docs/       this documentation
```

Rules of the architecture:

* `common` depends on nothing else. Everything depends on `common`.
* `client`, `sensor`, `test` talk to the server **only through sockets** (they never import `server`).
* `server` and `web` talk through the **`NetworkController` interface** (the web package never imports `SimulationEngine`; `AquaNetServer` even loads `WebServer` by reflection).
* All state travels as **immutable snapshots**.

## 2. Package walkthrough

### 2.1 `aquanet.common`

| Class | Responsibility |
|-------|----------------|
| `Protocol` | Ports (5000/5001/8080), tick length (1000 ms), keyword constants, and `split`, `join`, `clean` helpers. The Javadoc at the top is the authoritative description of the wire protocol. |
| `NetworkController` | Interface: `snapshot()`, `execute(who, command)` returning `"OK\|..."` or `"ERR\|..."`, `addStateListener`, `addEventListener`. Implemented by `SimulationEngine`; used by `ClientHandler` (indirectly through the server) and `WebServer`. |
| `NetworkSnapshot` | Immutable picture of the whole network at one tick. `toWire()` / `fromWire()` (compact `#` and `;` format) and `toJson()`. |
| `TankInfo`, `PumpInfo`, `ValveInfo` | Immutable records with their own wire and JSON encoders. `TankInfo.status()` defines EMPTY/LOW/OK/HIGH/OVERFLOW thresholds. |

If you change the snapshot format, change **`toWire`, `fromWire`, `toJson` and `ProtocolSelfTest` together**, and tell the GUI and web teammates.

### 2.2 `aquanet.server`

| Class | Responsibility |
|-------|----------------|
| `AquaNetServer` | `main`. Parses flags (`--tcp-port`, `--udp-port`, `--http-port`, `--no-web`, or a bare TCP port number). Creates the `SimulationEngine`, registers a state listener and an event listener that **broadcast** lines to all clients, opens the `ServerSocket`, starts the UDP listener and the web server, starts the engine, and then runs the **accept loop** on the main thread. Keeps clients in a `CopyOnWriteArrayList`, executes each `ClientHandler` on a cached thread pool, and has a shutdown hook. |
| `ClientHandler` | One connected TCP client. The `run()` method is the **reader loop** (`readLine()` -> `handle(line)`). A second thread, the **writer**, drains a bounded outbox queue to the socket. `send()` only does `outbox.offer()`, so nobody ever blocks on a slow client. `handle()` implements HELLO, PING, QUIT, CMD, CHAT. `finally` always calls `close()` and `server.clientGone(this)`. |
| `SimulationEngine` | The model. Inner mutable classes `Tank`, `Pump`, `Valve`; one `lock`; `doTick()`; `autoControl()`; `physics()`; `detectAlerts()`; `execute()` for commands; `updateSensor()` for UDP readings; `buildSnapshot()`. Implements `NetworkController`. |
| `SensorUdpListener` | `DatagramSocket` receive loop with a 1 s timeout. Validates each datagram, calls `engine.updateSensor`, keeps per-tank sequence statistics, logs a summary every 10 s. |

### 2.3 `aquanet.client`

| Class | Responsibility |
|-------|----------------|
| `AquaNetClient` | `main`. Sets the Nimbus look and feel and creates the window **inside `SwingUtilities.invokeLater`**. Optional args: host, port, name. |
| `DashboardFrame` | The main window. Builds the layout and implements the `ClientListener`. It is the **only place** where network events are turned into GUI updates; each callback wraps its work in `invokeLater`. |
| `ClientListener` | Callback interface (`onConnected`, `onState`, `onAlert`, `onAck`, `onError`, `onChat`, `onDisconnected`, `onLatency`). Called from the **network thread**, not the EDT. |
| `ConnectionManager` | The TCP layer (no Swing). Connect with timeout, reader loop, parse lines, reconnect with exponential back-off, ping every 5 s, synchronised `sendCommand` / `sendChat`. A generation counter discards events of stale connections. |
| `TankPanel` | Custom painted tank with eased animation (the server updates once a second; the panel redraws about 25 times a second). |
| `NetworkDiagramPanel` | Schematic pipes. Its layout is computed from the snapshot, so extra tanks/pumps/valves appear automatically. |
| `TrendChartPanel` | Hand-drawn history chart (no chart library) of fill percentages. |
| `ControlPanel` | Dynamically built controls (pump toggles, valve sliders, leak buttons, mode, reset). Uses a `syncing` flag so programmatic updates do not echo back as commands. |
| `AlertChatPanel` | Alert log and chat box. |
| `Theme` | Colours and fonts. |

### 2.4 `aquanet.sensor`

`SensorSimulator`: `main` parses `[host] [tcpPort] [intervalMs] --loss --fault --udp`. A daemon thread `sensor-tcp` connects as a TCP client (`HELLO|sensor-gw`) and mirrors the true tank levels from `STATE` messages (and reconnects every 2 s). The main thread sends one `SENSOR|tank|litres|seq` datagram per tank per interval using an unconnected `DatagramSocket`, adding Gaussian noise, optional loss and optional faults.

### 2.5 `aquanet.web` and `web/`

`WebServer` uses `com.sun.net.httpserver.HttpServer` with contexts `/` (static files), `/api/state`, `/api/history`, `/api/alerts`, `/api/command`, `/events` (SSE). Its listeners (`onState`, `onAlert`) run on the simulation thread, so they only **enqueue** strings; each SSE worker thread writes to its own browser. The `web/js/dashboard.js` file fetches history/alerts once, opens `new EventSource('/events')`, and posts commands with `fetch('/api/command', ...)`.

### 2.6 `aquanet.test`

* `ConsoleClient` - two threads: main reads stdin and writes commands; a reader thread prints server messages.
* `LoadTestClient` - one object per simulated client, each with a reader thread and a worker thread; prints a table at the end.
* `ProtocolSelfTest` - plain `main` with `check(name, boolean)`; no JUnit.

## 3. Data flow, tick by tick

```
t = 0.000 s   sim-tick thread wakes up (scheduleAtFixedRate)                SimulationEngine.safeTick -> doTick
              |  lock acquired
              |  tick++
              |  if AUTO: autoControl()      pump hysteresis, valve targets (+-10 % per tick)
              |  physics()                   valves, then pump, then demand, then leaks
              |  detectAlerts()              compares status with last status, collects ALERT lines
              |  snap = buildSnapshot()      immutable; measured = -1 if sensor older than 5 s
              |  lock released
              |
              +--> fireEvents(events)        eventListeners: AquaNetServer logs + broadcast("ALERT|...")
              |                                              WebServer.onAlert -> ring buffer + SSE queues
              +--> stateListeners:
                      AquaNetServer: broadcast("STATE|" + snap.toWire())
                           for each ClientHandler: outbox.offer(line)     (non-blocking)
                      WebServer.onState: record history, enqueue "event: state\ndata: <json>"
                                                                           (non-blocking)

(other threads, at any time)
  writer thread of each client:   outbox.poll() -> socket -> GUI/console client
  SSE worker of each browser:     queue.poll()  -> socket -> EventSource -> dashboard.js
  UDP thread:   receive() -> parse -> engine.updateSensor(tank, litres)  (lock, store measured + timestamp)
  handler / web thread: execute(who, "PUMP|P1|ON")                       (lock, change state, collect events)
```

In the GUI client: `ConnectionManager` reader thread: `readLine()` -> `handleLine()` -> `listener.onState(snapshot)` -> `DashboardFrame` -> `SwingUtilities.invokeLater(...)` -> update panels on the EDT.

Order guarantee: events produced during a tick are fired **before** the `STATE` of that tick. A command executed from another thread may run between ticks and its events are fired immediately after the lock is released; the new state becomes visible with the next `STATE`.

## 4. Threads, locks and rules to remember

1. **Only `SimulationEngine` mutates simulation state, and only while holding its `lock`.** Do not add fields that are changed outside the lock.
2. **Never call a listener or write to a socket while holding the lock.** Collect events in a list and fire after leaving `synchronized`.
3. **Never block the tick thread.** Use `offer()` on queues, not `put()`.
4. **GUI code runs on the EDT.** Do not call Swing methods from `ConnectionManager` callbacks directly; use `invokeLater`.
5. **Do not block the EDT.** No `readLine()`, `connect()`, `Thread.sleep()` in listeners or actions.
6. **Snapshots are immutable.** Add fields as `final` and extend the constructor.
7. **Strings on the wire must not contain `|` or newlines.** Use `Protocol.clean` for user text; snapshot names also remove `,`.

## 5. How to add a new tank

Example: add **T4 "Industrial Zone"** fed by a new valve **V3** from T1.

1. **Engine** (`SimulationEngine` constructor): create the tank and register it, then the valve:
   ```java
   Tank t4 = new Tank("T4", "Industrial Zone", 3000, 1500, 15, 5, 1.0);   // id, name, capacity, startLevel, demandBase, demandAmp, demandPhase
   tanks.put("T4", t4);
   valves.put("V3", new Valve("V3", "Valve T1-T4", t1, t4, 50));
   ```
   (The fields `t1,t2,t3` are final fields; use a local variable or add a field for T4.)
   The `for (Tank t : tanks.values()) t.status = t.computeStatus();` line in the constructor and the rest of the engine loops over all tanks, valves and pumps, so **physics, AUTO valve control, leaks, alerts and snapshots work automatically**.
2. **Clients**: `ControlPanel` and `NetworkDiagramPanel` are built from the snapshot, so the new tank and valve appear with sliders and leak buttons without changes. `DashboardFrame` creates a `TankPanel` per tank id it sees in the snapshot (`tankPanels` map).
3. **Web**: `web/js/dashboard.js` rebuilds its cards from `s.tanks`, `s.pumps` and `s.valves` whenever the set of ids changes, so nothing to change.
4. **Sensors**: `SensorSimulator` sends a reading for every tank it sees in `STATE`, and the server accepts any known tank id (`engine.hasTank`) - nothing to change.
5. **Docs/tests**: update the constants table in the report, add a test case.

## 6. How to add a new pump

Example: **P2**, a booster pump that also fills T1.

1. In the `SimulationEngine` constructor: `pumps.put("P2", new Pump("P2", "Booster Pump"));`
2. In `physics()` the loop `for (Pump p : pumps.values())` already adds `PUMP_RATE` for every ON pump to **T1**. To give P2 another rate or target tank, add fields to `Pump` (for example `double rate; Tank target;`), set them in the constructor and use them in `physics()` instead of `PUMP_RATE` / `t1`.
3. In `autoControl()` the pump logic uses `pumps.get("P1")`. Decide the AUTO rule for P2 (for example ON when T1 < 25 %, OFF when > 60 %) and add it.
4. The `PUMP|P2|ON` command works automatically (`pumps.get(...)` lookup). The GUI creates a toggle for each pump in the snapshot.
5. Run `ProtocolSelfTest` and test with `ConsoleClient` (`pump P2 on`).

## 7. How to add a new command

1. Add a branch in `SimulationEngine.executeLocked` (return `ok(...)`/`err(...)`, add events to the list).
2. Document it in `NetworkController` and `Protocol` Javadoc.
3. `ClientHandler` forwards any `CMD|...` automatically; `WebServer` forwards the POST body; nothing else needed.
4. Add a button in `ControlPanel` and `web/js/dashboard.js`, and a shortcut in `ConsoleClient` if you like.

## 8. Running each main class in Eclipse

| Class | Purpose | Typical program arguments |
|-------|---------|---------------------------|
| `aquanet.server.AquaNetServer` | Server | none; or `--http-port 9090`, `--no-web` |
| `aquanet.client.AquaNetClient` | GUI client | none; or `localhost 5000 alice` |
| `aquanet.test.ConsoleClient` | Text client | `localhost 5000 bob` |
| `aquanet.sensor.SensorSimulator` | UDP sensors | none; or `localhost 5000 1000 --loss 0.3` |
| `aquanet.test.LoadTestClient` | Load test | `localhost 5000 50 60` |
| `aquanet.test.ProtocolSelfTest` | Self-test | none |

Steps:

1. In **Package Explorer** expand `src > aquanet > <package>`, right-click the class.
2. `Run As > Java Application`. The first run creates a *launch configuration*.
3. To add arguments: `Run As > Run Configurations...` (or `Run > Run Configurations...`), choose the class under *Java Application*, tab **Arguments**, field **Program arguments**, then **Apply** and **Run**. (Use **VM arguments** only for JVM options such as `-Xmx256m`.)
4. Working directory: tab **Arguments > Working directory**; keep the default (the project folder) so that the `web/` folder is found.
5. Several programs can run at once. Use the **Console view** drop-down ("Display Selected Console") and the red **Terminate** button. Use the **Debug** button instead of Run to set breakpoints (double-click a line number).
6. If you get "address already in use", an older run is still alive: open the `Debug` view or the Console drop-down and terminate it.

Useful Eclipse tips: `Ctrl+Shift+T` open a class; `Ctrl+Shift+F` format; `F3` jump to declaration; `Ctrl+Alt+H` call hierarchy; the **Debug** perspective's *Threads* list shows `sim-tick`, `client-writer-*`, `udp-sensor`, `web-worker-*` - great for the viva.

## 9. Exporting a runnable JAR from Eclipse

1. Run the server once from Eclipse so a launch configuration for `AquaNetServer` exists.
2. `File > Export... > Java > Runnable JAR file > Next`.
3. **Launch configuration**: choose `AquaNetServer - AquaNet`.
4. **Export destination**: for example `C:\AquaNet\aquanet-server.jar`.
5. **Library handling**: "Extract required libraries into generated JAR" (there are none, but this is the default).
6. `Finish`. Repeat with the `AquaNetClient` configuration for `aquanet-client.jar`; you can also export one JAR and run any class with `java -cp aquanet.jar <mainclass>`.
7. Run:
   ```bash
   java -jar aquanet-server.jar
   java -jar aquanet-client.jar localhost 5000 alice
   java -cp aquanet-server.jar aquanet.sensor.SensorSimulator
   ```
8. **Important:** put the `web` folder **next to where you start the server** (the server reads `web/` from the current directory, it is not inside the JAR). Example folder: `aquanet-server.jar` + `web/`.

Command-line alternative:

```bash
javac -d bin $(find src -name "*.java")
printf "Main-Class: aquanet.server.AquaNetServer\n" > manifest.txt
jar cfm aquanet.jar manifest.txt -C bin .
java -jar aquanet.jar
```

## 10. Debugging tips

* Watch the raw protocol: `telnet localhost 5000` or `nc localhost 5000`, type `HELLO|me`.
* Add logging with `AquaNetServer.log("...")` (timestamped and thread-safe).
* Capture packets with Wireshark: filter `tcp.port == 5000` or `udp.port == 5001` (capture on the loopback interface, `lo` on Linux, "Npcap Loopback Adapter" on Windows).
* A thread dump (`jstack <pid>` or Ctrl+Break on Windows) shows who is blocked where.
* If the clock seems to stop: an exception in the tick? `safeTick` prints `[engine] tick failed`.
* If the GUI freezes, something blocks the EDT: look at the stack trace of `AWT-EventQueue-0`.
* Compare `level` against `measured` to check the sensor path.

## 11. Coding conventions

* Java 11 compatible: no records, no text blocks, no `var` needed. No external libraries.
* Prefer small classes, comment the **why**, especially for threading decisions.
* UTF-8 everywhere (`StandardCharsets.UTF_8`), never the platform default.
* Always close sockets in `finally` or use try-with-resources.
* Run `ProtocolSelfTest` before every merge.

## 12. Known integration points to double check before the demo

* The server sends `WELCOME|C1|1000` (client id is a string such as `C1`). `ConnectionManager` keeps only the digits of the id, so `C1` becomes `1` for the GUI.
* The server's web root is the relative folder `web`; start from the project folder.
