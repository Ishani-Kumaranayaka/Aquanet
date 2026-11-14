# AquaNet - Presentation Outline (12 slides, about 12-15 minutes + 5-minute live demo)

Speaker names: [TEAM TO FILL]. Suggested split: Roles A, B, C, D each present the slides for their module; E opens and closes.

---

## Slide 1 - Title

**Content:** AquaNet - Real-Time Water Tank & Distribution Network Simulator. Course, team names, date.
**Speaker notes:** "We built a client-server simulator of a water network. It uses TCP, UDP and HTTP, all with plain Java sockets and no external libraries." (20 s)

## Slide 2 - Problem and motivation

**Content:** Operators must monitor tanks and control pumps and valves remotely; need live data, commands, alerts. Network programming concepts needed: sockets, protocols, concurrency.
**Speaker notes:** Explain the analogy with SCADA. "Our goal was not hydraulics, it was to build a realistic networked system where many users and sensors share one live state." (40 s)

## Slide 3 - Objectives and scope

**Content:** Bullet list: multi-client TCP server; UDP sensors; web dashboard with SSE; thread-safe simulation; Swing GUI; tests. Out of scope: security, persistence.
**Speaker notes:** Mention the 3 tanks, 1 pump, 2 valves, AUTO/MANUAL, leaks. Be clear on what is out of scope; it shows maturity. (40 s)

## Slide 4 - Architecture

**Content:** The diagram (ASCII/Mermaid from the report, redrawn): GUI/console clients <-TCP 5000-> Server (engine) <-UDP 5001- sensors; browser <-HTTP/SSE 8080-> server. Port table.
**Speaker notes:** Walk left to right. "One server owns the truth. Clients are views and remote controls. The engine is hidden behind the `NetworkController` interface, so TCP, web and tests use the same API." (1 min)

## Slide 5 - Why TCP and why UDP

**Content:** Comparison table (reliable/ordered/connection vs light/lossy). Mapping: commands, state, alerts -> TCP; sensor readings -> UDP; browser -> HTTP over TCP.
**Speaker notes:** Key viva point. "A lost level reading is replaced by the next one a second later, retransmitting it would be worse. Commands must never be lost." Mention sequence numbers and `--loss`. (1 min)

## Slide 6 - TCP protocol

**Content:** Line-based `KEYWORD|field|...` messages; table of client->server and server->client messages; example session `HELLO|alice` / `WELCOME` / `STATE|...` / `CMD|PUMP|P1|ON` / `ACK|...`.
**Speaker notes:** Why lines: TCP is a stream, we need framing; readable with telnet. Show `STATE` is pushed every second. (1 min)

## Slide 7 - Server and threading model

**Content:** Thread diagram: main (accept), sim-tick, reader+writer per client, udp-sensor, web workers. Rules: one lock, no I/O under the lock, immutable snapshots, bounded outbox queues.
**Speaker notes:** "The tick thread never writes to a socket. It puts lines in queues; the per-client writer threads do the slow work, so one frozen client cannot stop the simulation." Explain disconnect handling in the `finally` block. (1.5 min)

## Slide 8 - Simulation model

**Content:** Network sketch; mass-balance equation `L(t+dt) = L(t) + (Qin - Qout)dt`, clamps; constants (pump 80 L/s, valve max 60 L/s, leak 18 L/s); AUTO rules with hysteresis (pump ON < 40 %, OFF > 85 %); alert thresholds (20 % / 90 %).
**Speaker notes:** Keep it light. "Every transfer is clamped, so tanks never go negative or over capacity. Alerts fire only when the status changes." (1 min)

## Slide 9 - GUI client

**Content:** Screenshot of the Swing client; bullets: reader thread vs EDT, `invokeLater`, auto-reconnect with back-off, controls built from the snapshot, animation easing.
**Speaker notes:** "Swing is single-threaded. Network callbacks arrive on the reader thread, so every GUI update is wrapped in `invokeLater`." (1 min)

## Slide 10 - Website, REST and SSE

**Content:** Screenshot of the dashboard; endpoint table (`/api/state`, `/api/history`, `/api/alerts`, `/api/command`, `/events`); SSE vs WebSocket mini-table.
**Speaker notes:** "SSE is a long-lived HTTP response; the browser's `EventSource` reconnects by itself. Our dashboard only receives, commands go by POST, so we did not need WebSocket." (1 min)

## Slide 11 - Testing and results

**Content:** Test plan summary (25 cases), results table [TEAM TO FILL: pass/fail counts], load-test numbers [TEAM TO FILL], UDP loss experiment [TEAM TO FILL], a Wireshark screenshot (optional).
**Speaker notes:** Present measured numbers: clients tested, STATE jitter, average RTT, UDP loss observed. Be honest about bugs found and fixed. (1 min)

## Slide 12 - Limitations, future work, conclusion

**Content:** Limitations (no security, thread-per-client, simplified physics). Future: TLS, authentication, database logging, WebSocket/RMI, NIO, configurable topology. Conclusion: what we learned. "Questions?"
**Speaker notes:** Link every future item to a limitation. Thank the audience. (1 min)

---

## Live demo script (5 minutes)

**Preparation (before the presentation):** compile the project; open three terminals/Eclipse consoles; have a browser tab ready on `http://localhost:8080/dashboard.html`; start with a freshly restarted server. Close other programs using ports 5000, 5001, 8080. Have a backup recorded video or screenshots in case of failure.

| Time | Action | What to say / what to show |
|------|--------|----------------------------|
| 0:00 | Start the server (`aquanet.server.AquaNetServer`) | "The console shows TCP 5000, UDP 5001 and web 8080. The tick is 1000 ms." |
| 0:30 | Start GUI client #1 as "alice", connect | Tanks, pipe diagram and trend chart appear. "Server pushes a STATE every second." |
| 1:00 | Start GUI client #2 as "bob" (or a `ConsoleClient`) | "Server log shows two clients; the client count is in every STATE." Optionally chat from bob to alice. |
| 1:30 | Open the website dashboard | "Same data, over HTTP and SSE, no reload." Point at the server log line "SSE client connected". |
| 2:00 | In alice: switch to MANUAL, turn P1 ON, set V1 to 100 % | Watch T2 rise on all three screens, including bob and the browser. "One command, every client sees it within a second." |
| 2:45 | Inject a leak on T2 from the **website** | Alert appears in alice, bob and the browser; T2 outflow jumps, level falls. "Commands work from the web too and share the same engine." |
| 3:15 | Start `SensorSimulator --loss 0.3` | "UDP sensors: the measured value appears beside the true level." Show the server console UDP summary (`lost=` rising) after 10 s. "UDP does not retransmit; we detect loss by sequence number." |
| 3:45 | Kill client #2 abruptly (Terminate in Eclipse) | "The server removes it; client count drops; nobody else is affected." |
| 4:15 | Press **AUTO** and RESET (or repair the leak) | "In AUTO the server runs the pump with hysteresis and opens the valves according to demand." |
| 4:40 | Optional: run `LoadTestClient localhost 5000 30 10` in a terminal | Show the summary table: every client got about 10 STATE messages. |
| 5:00 | Wrap up | "Questions?" |

**Fallbacks:**

| Problem | Quick answer |
|---------|-------------|
| Port in use | Terminate old processes; or start with `--tcp-port 5100` and pass the port to the clients |
| Browser dashboard blank | Open `/api/state` to confirm the server answers; reload the page |
| GUI cannot connect | Check server console; use `ConsoleClient` or `telnet` to show the protocol |
| Projector problems | Use the recorded video |

## Tips for presenters

* Practise the demo at least twice with the actual projector resolution.
* Each person speaks about the part they wrote; be ready to open the code (class names are on slides 6-10).
* Expect questions from `04_Viva_QA.md`; assign one "expert" per topic.
