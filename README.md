# AquaNet - Galle Water Tank & Distribution System

Java network programming project (Eclipse, no external libraries, Java 11+).
A main reservoir (server) supplies area tanks (separate programs). Area tanks report their level and ask for water when low;
the server decides who gets water. Live screens: Swing control screen + website (admin and user roles).

* Design, messages, threads: `docs/01_GALLE_Design_and_Protocol.md`
* **How to run (step by step): `docs/02_GALLE_Run_Guide.md`**
* Team split (3 members): `docs/03_GALLE_Team_Split_3_Members.md`
* Older general documents (first version of the project): `docs/v1_reference/`

Quick start in Eclipse: import the project, run `aquanet.server.AquaNetServer`, then `aquanet.node.TankNode` (once per area),
then `aquanet.client.AquaNetClient`, and open http://localhost:8080 (admin / admin123).
Ports: TCP 5000 (control screens), TCP 5002 (area tanks), UDP 5001 (sensors), HTTP 8080 (website).
