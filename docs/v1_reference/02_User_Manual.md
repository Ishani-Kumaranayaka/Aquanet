# AquaNet - User Manual

This manual explains how to start and use every part of AquaNet: the server, the Swing GUI client, the console client, the website dashboard, the sensor simulator and the test tools.

## 1. What you need

* **JDK 11 or newer** (check with `java -version` and `javac -version`).
* **Eclipse IDE for Java Developers** (recommended) or just a terminal.
* A web browser (Chrome, Firefox, Edge, Safari) for the dashboard.
* For a multi-computer demo: all computers on the same network and the server's IP address.

## 2. Ports used

| Port | Protocol | Used by |
|------|----------|---------|
| 5000 | TCP | GUI client, console client, load tester, sensor gateway link |
| 5001 | UDP | Sensor simulator -> server |
| 8080 | TCP (HTTP) | Website, REST API, live event stream |

## 3. Getting the project running

### 3.1 In Eclipse

1. `File > Import > General > Existing Projects into Workspace`, choose the `AquaNet` folder, `Finish`.
2. If Eclipse complains about the JRE: right-click the project, `Properties > Java Build Path > Libraries`, and make sure a JRE/JDK 11+ is selected. Also set `Java Compiler > Compiler compliance level` to 11 or higher.
3. Eclipse compiles automatically (`Project > Build Automatically`).
4. To run a class: open it, then `Run > Run As > Java Application` (details in section 9).

> Important: the server looks for the website in a folder called `web` **relative to the working directory**. Eclipse uses the project folder as the working directory by default, so this just works. If you start the server from another folder, the website will answer "404 Not Found".

### 3.2 From a terminal (no Eclipse)

From the project folder (`AquaNet`):

```bash
# compile everything into ./bin
mkdir -p bin
javac -d bin $(find src -name "*.java")          # Linux / macOS
# Windows (cmd):  dir /s /b src\*.java > sources.txt   then   javac -d bin @sources.txt

# run (one terminal per program)
java -cp bin aquanet.server.AquaNetServer
java -cp bin aquanet.client.AquaNetClient
java -cp bin aquanet.sensor.SensorSimulator
```

Shortcut: the `scripts/` folder has `run-server`, `run-client`, `run-console`, `run-sensors` and `run-loadtest` as `.sh` (Linux/macOS) and `.bat` (Windows). They compile and then start the program, and pass any arguments on, e.g. `scripts/run-client.sh localhost 5000 alice`.

## 4. The server

Main class: `aquanet.server.AquaNetServer`

```bash
java -cp bin aquanet.server.AquaNetServer [tcpPort] [--tcp-port N] [--udp-port N] [--http-port N] [--no-web]
```

| Option | Meaning | Default |
|--------|---------|---------|
| `tcpPort` (a bare number) or `--tcp-port N` | TCP port for clients | 5000 |
| `--udp-port N` | UDP port for sensors | 5001 |
| `--http-port N` | HTTP port for the website | 8080 |
| `--no-web` | Do not start the website | web enabled |

Start it first. You should see something like:

```
==========================================
  AquaNet - Water Network Simulator Server
==========================================
[12:00:00.123] TCP clients : port 5000
[12:00:00.124] UDP sensors : port 5001
[12:00:00.125] Web UI      : http://localhost:8080/
[12:00:00.125] Tick        : 1000 ms.  Press Ctrl+C to stop.
```

The console then logs every client connection and disconnection, every command (`CMD from alice: PUMP|P1|ON`), chat messages, alerts, web commands, and every 10 seconds a **UDP summary** (datagrams received/lost/out of order/duplicate per tank).

Stop the server with `Ctrl+C` in the terminal, or the red **Terminate** button in Eclipse's Console view. Clients show "disconnected" and (the GUI) will keep trying to reconnect.

### 4.1 What the simulation does

* Three tanks: **T1 Main Reservoir** (10 000 L), **T2 District A** (4 000 L), **T3 District B** (4 000 L).
* **P1** is the pump that fills T1 from an external source.
* **V1** moves water T1 -> T2 and **V2** moves water T1 -> T3; each can be opened 0-100 %.
* T2 and T3 have a **customer demand** that varies like a wave with some randomness.
* **AUTO mode** (default): the server switches the pump and adjusts the valves by itself. **MANUAL mode**: only you control them. If you operate the pump or a valve while in AUTO, the system switches to MANUAL for you.
* You can **inject a leak** into any tank to see the effect, and **RESET** to start again.
* Tank colours/status: **OK** (20-90 %), **LOW** (< 20 %), **HIGH** (> 90 %), **EMPTY**, **OVERFLOW**.
* Alerts come in three levels: INFO, WARN, CRIT.

## 5. The Swing GUI client

Main class: `aquanet.client.AquaNetClient`

```bash
java -cp bin aquanet.client.AquaNetClient [host] [port] [name]
```

With arguments, the fields are pre-filled and the client connects at once. Without arguments, type them in the window.

### 5.1 Connecting

1. Enter the **host** (`localhost`, or the server computer's IP address such as `192.168.1.20`), the **port** (5000) and your **name**.
2. Press **Connect**. The status bar shows the connection state and the round-trip time (ping).
3. If the server is not reachable, the client retries automatically (waiting 1, 2, 4 ... up to 15 seconds). **Disconnect** stops the retries.

### 5.2 Window layout

| Area | What it shows |
|------|---------------|
| Top | Connection bar (host, port, name, Connect/Disconnect) |
| Centre top | One animated panel per tank: water level, percent, litres, in/out flow, leak warning, sensor reading (if a UDP sensor is online) |
| Centre | Schematic pipe diagram (source -> pump -> tanks -> valves), with moving arrows where water flows; and a live trend chart of the fill percentage of every tank |
| Right | Controls, alert log and chat |
| Bottom | Status bar |

### 5.3 Controls

| Control | Effect | Protocol line sent |
|---------|--------|--------------------|
| Pump ON/OFF toggle | Starts/stops pump P1 | `CMD\|PUMP\|P1\|ON` |
| Valve slider (0-100) | Sets the opening; the command is sent when you **release** the slider | `CMD\|VALVE\|V1\|75` |
| AUTO / MANUAL toggle | Selects who controls pump and valves | `CMD\|MODE\|AUTO` |
| Leak buttons (per tank) | Inject / repair a leak | `CMD\|LEAK\|T2\|ON` |
| RESET | Back to the starting state | `CMD\|RESET` |
| Chat box | Message to all connected users | `CHAT\|text` |

Every action is also seen by **all other clients and the website** within about one second.

### 5.4 Alerts

The alert log lists time-stamped messages coloured by level (INFO, WARN, CRIT). Typical alerts: "T3 below 20%", "LEAK detected on T2 ...", "AUTO: P1 switched ON", "Network RESET by alice".

## 6. The console client (text mode)

Main class: `aquanet.test.ConsoleClient`

```bash
java -cp bin aquanet.test.ConsoleClient [host] [port] [name] [--quiet]
```

`--quiet` prints only every 10th `STATE` line. Type commands on the keyboard:

| You type | Sent to server |
|----------|----------------|
| `pump P1 on` / `pump P1 off` | `CMD\|PUMP\|P1\|ON` / `OFF` |
| `valve V1 50` | `CMD\|VALVE\|V1\|50` |
| `mode auto` / `mode manual` | `CMD\|MODE\|AUTO` / `MANUAL` |
| `leak T2 on` / `leak T2 off` | `CMD\|LEAK\|T2\|ON` / `OFF` |
| `reset` | `CMD\|RESET` |
| `chat hello everyone` | `CHAT\|hello everyone` |
| `ping` | `PING` |
| `raw <line>` | the line unchanged (good for testing invalid input) |
| `quit` | `QUIT` |

It is the easiest way to **see the protocol**: the server messages `WELCOME`, `STATE`, `ALERT`, `ACK`, `ERR`, `CHAT`, `PONG` are printed as they arrive.

You can also use any raw TCP tool: `telnet localhost 5000` or `nc localhost 5000`, then type `HELLO|me`, press Enter, and watch `STATE|...` lines arrive every second. Try `CMD|PUMP|P1|ON`.

## 7. The website dashboard

Open **http://localhost:8080/** (replace `localhost` by the server's IP from another computer, e.g. `http://192.168.1.20:8080/`).

| Page | Content |
|------|---------|
| `index.html` (home) | Introduction with links |
| `dashboard.html` | The live dashboard: tank levels, equipment, alerts, and control buttons |
| `architecture.html` | Explanation of how the system works |
| `download.html` | How to get and run the project |
| `about.html` | About the project |

The dashboard loads recent history and alerts, then receives a live stream (Server-Sent Events) so it updates every second **without reloading**. If the server restarts, the browser reconnects automatically.

Commands from the dashboard are sent as `POST /api/command` and appear in the server log as "command from web".

### 7.1 Useful URLs (for testing with a browser or `curl`)

| URL | Description |
|-----|-------------|
| `http://localhost:8080/api/state` | Current snapshot as JSON |
| `http://localhost:8080/api/history` | Last 120 ticks of tank percentages |
| `http://localhost:8080/api/alerts` | Last 50 alerts |
| `http://localhost:8080/events` | Raw event stream (never ends) |

```bash
curl http://localhost:8080/api/state
curl -N http://localhost:8080/events                         # watch the live stream
curl -X POST -H "Content-Type: text/plain" -d "PUMP|P1|ON" http://localhost:8080/api/command
```

## 8. The sensor simulator (UDP)

Main class: `aquanet.sensor.SensorSimulator`

```bash
java -cp bin aquanet.sensor.SensorSimulator [host] [tcpPort] [intervalMs] [--loss P] [--fault TANKID] [--udp PORT]
```

| Argument | Meaning | Default |
|----------|---------|---------|
| `host` | Server host | `localhost` |
| `tcpPort` | Server TCP port; used only to learn the true tank levels so the fake readings look realistic | 5000 |
| `intervalMs` | Time between readings | 1000 |
| `--loss P` | Drop each datagram with probability P (0 to 1), to demonstrate that UDP is unreliable | 0 |
| `--fault TANKID` | That tank's sensor sometimes reports wild values (a broken sensor) | off |
| `--udp PORT` | Server UDP port | 5001 |

Examples:

```bash
java -cp bin aquanet.sensor.SensorSimulator                           # normal sensors
java -cp bin aquanet.sensor.SensorSimulator localhost 5000 500 --loss 0.3
java -cp bin aquanet.sensor.SensorSimulator localhost 5000 1000 --fault T2
java -cp bin aquanet.sensor.SensorSimulator 192.168.1.20             # remote server
```

What you will see:

* Sensor console: a line every 10 s with `sent=` and `dropped=` counts.
* Tank panels / dashboard: a **measured** value next to each true level (about 1 % noise). If the sensor stops, the measured value disappears after 5 seconds.
* Server console: the **UDP summary** every 10 s, showing `received`, `lost`, `outOfOrder`, `dup` and `malformed` counts per tank. With `--loss 0.3`, `lost` grows.

## 9. Running things in Eclipse

1. Open the class (for example `aquanet.server.AquaNetServer`), then `Run > Run As > Java Application`.
2. To pass arguments: `Run > Run Configurations...`, select the configuration under *Java Application*, open the **Arguments** tab, type them into **Program arguments** (for example `--http-port 9090`), click **Run**.
3. Each program runs in its own Console tab. Use the *Display Selected Console* button (monitor icon) to switch between them, and the red square to stop one.
4. Start order for a demo: **server -> GUI client(s) -> sensor simulator -> browser**.

## 10. Test tools

### 10.1 Protocol self-test (no server needed)

```bash
java -cp bin aquanet.test.ProtocolSelfTest
```

Prints a PASS/FAIL line for each check (message encoding/decoding, tank status thresholds, JSON validity, helper functions) and a summary.

### 10.2 Load test

```bash
java -cp bin aquanet.test.LoadTestClient [host] [port] [numClients] [seconds]
# defaults: localhost 5000 10 10
java -cp bin aquanet.test.LoadTestClient localhost 5000 50 60
```

Opens many headless clients, each sending a PING every 500 ms, then prints a table with the number of `STATE` messages received and the average round-trip time per client. Every client should receive about one STATE per second.

## 11. Running on several computers

1. On the server computer find its IP address (`ipconfig` on Windows, `ip addr` or `ifconfig` on Linux/macOS).
2. Make sure the firewall allows **TCP 5000, UDP 5001 and TCP 8080** (see troubleshooting).
3. On other computers start the client with the server's IP: `java -cp bin aquanet.client.AquaNetClient 192.168.1.20`.
4. Sensors: `java -cp bin aquanet.sensor.SensorSimulator 192.168.1.20`.
5. Browsers: `http://192.168.1.20:8080/`.

## 12. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Server prints `Cannot listen on TCP port 5000: Address already in use` | Another AquaNet server (or other program) already uses the port; often an old run still alive in Eclipse | Stop the old process (red square in Eclipse; check the Console drop-down), or use `--tcp-port 5100` and connect clients to that port. On Linux/macOS: `lsof -i :5000`; on Windows: `netstat -ano \| findstr :5000` then end that PID |
| Server log: `UDP sensor listener not started on port 5001` | UDP port busy | Stop the other process or use `--udp-port` (and `--udp` on the sensor) |
| Server log: `web server failed to start` / port 8080 busy | Another web server (Tomcat, Jenkins, another AquaNet) uses 8080 | Use `--http-port 9090` and open `http://localhost:9090/` |
| Client: `Connection refused` | Server not running, wrong host/port, or server on another machine | Start the server first; check host and port; use the server's real IP, not `localhost`, from other computers |
| Client: connection times out | Firewall blocks the port, wrong IP, different network, VPN | Allow the ports in the firewall; ping the server; join the same network/disable VPN |
| GUI shows "disconnected" and keeps retrying | Server stopped or network dropped | Restart the server; the client reconnects automatically |
| Browser: "404 Not Found" for `/` | Server started from the wrong folder, so `web/` is not found | Start with the project folder as working directory (Eclipse default); in a terminal `cd` to the `AquaNet` folder first |
| Dashboard loads but numbers never change | The event stream is blocked by a proxy/antivirus, or the server stopped | Open `/api/state` to see if the server answers; reload; try another browser; check the server is running |
| Dashboard works on the server PC but not from another PC | Firewall, or you used `localhost` | Use the server's IP; open TCP 8080 in the firewall |
| Measured sensor value is empty / `-1` | No sensor running, or sensor stopped more than 5 s ago, or UDP blocked | Start `SensorSimulator`; check the server UDP summary; open UDP 5001 in the firewall |
| Sensor runs but the server never receives anything (remote server) | UDP is blocked by firewall (UDP gives no error message when packets are lost) | Open UDP 5001 inbound on the server; check the host argument |
| Sensor prints `TCP link lost, retrying...` | Server not running or TCP port wrong | Start the server; pass the right `tcpPort` |
| `ERR\|send HELLO\|name first` | You typed a command before `HELLO` (raw telnet) | Send `HELLO\|yourname` first |
| `ERR\|unknown pump 'P9'` etc. | Wrong object id | Use ids shown in the GUI: pump `P1`, valves `V1`/`V2`, tanks `T1`-`T3` |
| `Error: Could not find or load main class aquanet...` | Wrong classpath/working directory, or not compiled | Run from the project folder with `-cp bin`; compile first; use the full class name including package |
| Eclipse shows red errors about `javax.swing` or the JRE | Wrong JRE selected | `Project > Properties > Java Build Path > Libraries`, select JDK 11+ |
| GUI window opens but is blank / tiny | High-DPI scaling or very small screen | Resize or maximise the window; try another look and feel by editing `AquaNetClient` (see Developer Guide) |
| Many clients but some do not get updates | A client is too slow, its queue (200 lines) overflowed and lines were dropped | Close it and reconnect |
| `java.net.BindException: Permission denied` | Ports below 1024 need admin rights | Use ports above 1024 (the defaults are fine) |

## 13. Quick demo checklist

1. Start the server. 2. Start two GUI clients (different names). 3. Open the website. 4. Switch to MANUAL, turn P1 ON, set V1 to 100 %. 5. Inject a leak on T2 and watch the alerts in all three places. 6. Start the sensor simulator with `--loss 0.3` and look at the UDP summary in the server console. 7. Press RESET.
