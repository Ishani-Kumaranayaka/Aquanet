# Website: accounts, roles and pages

The AquaNet website is an operations site, not a project brochure. Open http://localhost:8080

| Page | Who | Purpose |
|---|---|---|
| `login.html` | everyone | Sign in or create an account (new accounts are USER) |
| `user.html` | USER and ADMIN | View-only live tanks, pumps, valves, chart, alerts; report a problem; see "my reports" |
| `admin.html` | ADMIN only | Everything above plus pump/valve/mode/leak/reset control, user reports (resolve), account management (promote/demote/delete), activity log |

Default administrator (created on first start, stored in `data/users.txt`): **admin / admin123**. Change it before the demo
(log in, create a second admin by promoting an account, then delete the default).

## How it works (viva notes)
- Passwords are salted and hashed with PBKDF2-HMAC-SHA256 (`AuthService`). Plain passwords are never stored.
- Login creates a random session token sent as an HttpOnly cookie (`aqsid`). The server keeps token -> session in a ConcurrentHashMap.
- Every data route (`/api/state`, `/api/history`, `/api/alerts`, `/events`) needs a session (401 otherwise).
- `/api/command`, `/api/users`, `/api/audit` need the ADMIN role (403 otherwise). Roles are checked on the server, not just hidden in the page.
- 5 wrong passwords lock that username for 60 s.
- `/user.html` and `/admin.html` redirect to the login page when you are not allowed in.

## REST summary
| Method + route | Body | Role |
|---|---|---|
| POST /api/register | `user|password` | public |
| POST /api/login, /api/logout | `user|password` / none | public / any |
| GET /api/me | - | any |
| GET /api/state, /api/history, /api/alerts, /events (SSE) | - | any |
| POST /api/report | `tankId|text` | any |
| GET /api/reports | - | any (admin sees all, user sees own) |
| POST /api/reports | `id|note` (resolve) | ADMIN |
| POST /api/command | e.g. `PUMP|P1|ON` | ADMIN |
| GET/POST /api/users | `DELETE|name` or `ROLE|name|ADMIN` | ADMIN |
| GET /api/audit | - | ADMIN |

Files: `src/aquanet/web/WebServer.java`, `AuthService.java`, `ReportBoard.java`; `web/*.html`, `web/js/*.js`.
Note: other documents that mention `index.html`, `dashboard.html`, `about.html`, `architecture.html` or `download.html` describe the
first version of the site; this document replaces them.
