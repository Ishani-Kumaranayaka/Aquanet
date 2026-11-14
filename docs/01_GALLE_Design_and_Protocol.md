# AquaNet - Galle Water Tank & Distribution System

## The idea
One big **main reservoir** (Galle Main Reservoir) supplies several **area tanks** (Galle Fort, Karapitiya, Unawatuna, Hikkaduwa).
Each area tank is its own program (`aquanet.node.TankNode`), usually on its own laptop. It measures its own water level, reports it
every second and **asks the main tank for water when it is low**. The main tank (the server) decides who gets water.
This is a small copy of a real water-board **SCADA** system (remote tank sensors -> control room -> valves).

```
                         +--------------------------+
 Treatment plant ------> |  MAIN TANK = SERVER      |  <--HTTP 8080-->  Browser (user / admin website)
   (source pump P1)      |  Galle Main Reservoir    |  <--TCP 5000--->  Swing control screens
                         +------------+-------------+
        TCP 5002 (report / request / grant)  |  UDP 5001 (sensor readings)
       +------------+----------------+-------+-----+--------------+
   [TankNode]   [TankNode]       [TankNode]     [TankNode]
   Galle Fort   Karapitiya       Unawatuna      Hikkaduwa
```

## Who does what
**Area tank (client):** simulates its own level and water use, reports every second (REPORT), asks for water below 30% (REQUEST),
adds the water it is granted, sends UDP sensor readings, reconnects automatically.
**Main tank (server):** keeps the main tank level, runs the source pump (AUTO: ON below 40%, OFF above 85%), accepts many area tanks
(one thread each), decides GRANT or DENY, pushes admin commands (supply valve, leak, reset), marks a silent tank OFFLINE after 5 s,
sends the live picture to the control screens and the website.

## Allocation rules (class `DistributionEngine`, method `handleRequest`)
1. Supply valve of that area closed by an admin -> `DENY supply closed by administrator`.
2. MANUAL mode -> `DENY manual mode` (the operator uses SEND instead).
3. Grant = min(requested, litres needed to reach 85%, water available). Water available = main level minus a 5% reserve;
   a tank under 10% (emergency) may use the reserve too.
4. Grant is multiplied by the supply valve opening (50% open = half the water).
5. Less than 50 L -> `DENY main tank is too low`.
6. The main level is reduced inside `synchronized(lock)`, so two tanks asking at the same instant can never get the same water.

## Messages
Area tank -> server (TCP 5002, text lines, fields split by `|`):
| Message | Meaning |
|---|---|
| `NODE|id|name|capacity|level` | register (first message) |
| `REPORT|id|level|demand|inflow|leak` | every second (also the heartbeat) |
| `REQUEST|id|litres` | please send water |
| `PING` / `BYE` | keep-alive test / disconnect |

Server -> area tank: `REGISTERED|id|1000`, `GRANT|id|litres`, `DENY|id|reason`, `SUPPLY|id|percent`, `LEAK|id|ON/OFF`, `RESET`, `ERR|text`, `PONG`.
Sensor -> server (UDP 5001): `SENSOR|id|level|seq`.
Control screens (TCP 5000): `HELLO`, `CMD|...`, `CHAT`, `STATE|...`, `ALERT|...`, `ACK`, `ERR` (see `common/Protocol.java`).
Admin commands: `PUMP|P1|ON`, `VALVE|V_KARAPITIYA|0..100`, `MODE|AUTO/MANUAL`, `LEAK|id|ON/OFF`, `SEND|id|litres`, `RESET`.

## Threads (viva)
Server: 1 accept thread per port, 1 handler thread per area tank, 1 handler + writer thread per control screen, 1 timer thread (1 s tick),
1 UDP receive thread, a pool for web requests. Area tank program: tick thread, TCP reader thread, Swing event thread.
Shared main-tank data is guarded by one lock (`DistributionEngine.lock`).

## Failure behaviour
Unplug an area laptop or close its program: within 5 s the dashboards show that tank OFFLINE (grey) and raise a CRIT alert.
Restart it with the same area: it re-registers and shows ONLINE again. Duplicate ids are refused.
