# Team split - 3 members

| | Member 1: Main Tank Server | Member 2: Area Tank Programs | Member 3: Dashboards & Website |
|---|---|---|---|
| Role | Server / network core | Client programs / sensors | Control screen + web + docs |
| Packages | `aquanet.server`, `aquanet.test`, `aquanet.common` (protocol) | `aquanet.node`, `aquanet.sensor` | `aquanet.client`, `aquanet.web`, `web/` |
| Main files | `DistributionEngine`, `NodeServer`, `NodeHandler`, `AquaNetServer`, `ClientHandler`, `SensorUdpListener` | `TankNode` (+ window/model), `SensorSimulator` | `DashboardFrame`, `TankPanel`, `NetworkDiagramPanel`, `ConnectionManager`, `WebServer`, `AuthService`, `ReportBoard`, `web/*.html`, `web/js/*` |
| Network topics to explain | ServerSocket, accept loop, thread per tank, synchronized allocation, heartbeat/OFFLINE, UDP listener | Socket client, reader thread + tick thread, auto-reconnect, UDP DatagramSocket sensor, request/reply | Swing client socket + invokeLater, HTTP server, Server-Sent Events, REST commands, login sessions and roles |
| Zip | `Member1_Main_Tank_Server.zip` | `Member2_Area_Tank_Programs.zip` | `Member3_Dashboards_and_Website.zip` |

Everybody must be able to explain the full picture in `01_GALLE_Design_and_Protocol.md`, plus their own part in detail.

## 4-week plan
| Week | Member 1 | Member 2 | Member 3 |
|---|---|---|---|
| 1 | Run the base project, read `Protocol.java`, explain NODE/REPORT/REQUEST/GRANT | Run `TankNode` against the server, explain the tick and request rule | Run the Swing client and the website, explain STATE and the REST API |
| 2 | Change allocation numbers (reserve, target %), test DENY cases | Add a 5th area (new preset), test reconnect | Add a feature to the admin page (e.g. announcement or "send water" button) |
| 3 | Load test (`LoadTestClient`), threading notes | Packet-loss test with `SensorSimulator --loss`, UDP notes | Screenshots of every page, user manual |
| 4 | Report: architecture + threads | Report: node design + protocols | Report: dashboards + security; slides; rehearse the demo together |

Optional extensions: save reports/logs to a file, add TLS, real database (SQLite), mobile-friendly user page.
