# How to run AquaNet (Galle) - step by step

## 1. Install (every laptop)
1. **JDK 17 or newer** (Eclipse Temurin from adoptium.net). Check in a terminal: `javac -version`.
2. **Eclipse IDE for Java Developers** (you already have it).
3. **Chrome or Edge** for the website. Nothing else - no libraries needed.

## 2. Put the project in Eclipse (every laptop)
1. Unzip `AquaNet_FULL_PROJECT.zip` (if you work from member zips: unzip `00_Base_Project.zip` first, then your own zip into the same `AquaNet` folder).
2. Eclipse: **File > Import > General > Existing Projects into Workspace > Browse** -> select the `AquaNet` folder (the one that contains `src`) -> **Finish**.
3. Package Explorer shows **AquaNet > src > aquanet.common / server / node / client / web ...** with no red X marks.

## 3. Run on ONE laptop first (test)
Always start in this order. To run a class: open its `.java` file in the editor, click inside it, then **Run > Run As > Java Application**.
1. `aquanet.server.AquaNetServer` - the main tank. Wait for the banner and "Area tanks : TCP port 5002".
2. `aquanet.node.TankNode` four times (a window asks which area; pick a different one each time). Each shows a tank window.
   (Run the class again for each area: Run > Run As > Java Application again, or use Run Configurations > Arguments `--area karapitiya`.)
3. `aquanet.client.AquaNetClient` - Connect. You see the main tank and all area tanks, the pipe diagram, controls, alerts.
4. Browser: **http://localhost:8080** - sign in `admin` / `admin123` (full control) or create a user account (view only + report problems).

## 4. Run on THREE laptops (real network demo)
1. Connect all laptops to the **same Wi-Fi** (or one hotspot).
2. **Member 1 (server laptop):** open Command Prompt, run `ipconfig`, note the **IPv4 address** (e.g. `192.168.1.20`). Run `AquaNetServer`.
   When Windows asks, click **Allow access** for Java (private networks). If you missed it: Windows Security > Firewall > Allow an app > Java.
3. **Member 2 and Member 3:** in Eclipse run `TankNode`; in the dialog choose the area and type the server IP. Or use Run Configurations > Arguments:
   `--area karapitiya --host 192.168.1.20`.
   Suggested: Member 2 runs Galle Fort + Karapitiya, Member 3 runs Unawatuna + Hikkaduwa (or one area each).
4. Anyone: `AquaNetClient` with Host = `192.168.1.20`; browser `http://192.168.1.20:8080`.
5. Test the network: on a member laptop, `ping 192.168.1.20` must answer.

## 5. Demo script (5 minutes)
1. Show server + 4 area tanks + dashboard: "each tank is a separate program talking over TCP".
2. Watch an area tank drop below 30%: its window shows `REQUEST`, then `GRANT`; the main tank level falls.
3. Admin (website): move the **Supply to Karapitiya** slider to 0 -> its next request is DENIED ("supply closed").
4. Switch **MANUAL**, then use the website/GUI to continue; show requests waiting for the operator (DENY manual mode).
5. Inject a **leak** in Unawatuna -> level falls faster, CRIT alert.
6. **Close one area program** -> dashboards show OFFLINE within 5 s; start it again -> ONLINE.
7. Open the website as a normal user: view only; send a problem report; the admin resolves it.

## 6. Troubleshooting
| Problem | Fix |
|---|---|
| "Address already in use" | An old server is still running. Eclipse Console: red square (Terminate), or Task Manager > end `javaw.exe`/OpenJDK. |
| Client says "Connection refused" | Start `AquaNetServer` first; check the Host/IP. |
| Area tank stays "Reconnecting" on another laptop | Wrong IP, different Wi-Fi, or firewall: allow Java, or allow TCP 5000, 5002, 8080 and UDP 5001. |
| "Selection does not contain a main type" | You selected a folder/package. Open the `.java` file, click inside, then Run As > Java Application. |
| Website shows "not found" | Server must run from the project folder (Eclipse default). Run Configurations > Arguments > Working directory = Default. |
| Red X in Package Explorer | Right-click project > Properties > Java Compiler > set compliance 11 or higher; Project > Clean. |
| ERR duplicate id | Two programs use the same area. Use different areas. |
