# OpenFlux v2 exit (Node.js) — MyAppVPN

> **Disclaimer / Дисклеймер.** Educational project. The author is not responsible for how it is used.
> In Russia, VPN services for bypassing blocks are restricted by law. Using a hosting account as a
> proxy may violate the host's terms. Use at your own risk.
> Учебный проект. Автор не несёт ответственности за использование. В РФ VPN-сервисы для обхода
> блокировок ограничены законом. Используя хостинг как прокси, вы можете нарушить его правила.

Server side of the resumable OpenFlux protocol v2 (spec: [`../PROTOCOL-v2.md`](../PROTOCOL-v2.md)).
Unlike the PHP exit (`../server/phpbox.php`), a session lives in one persistent Node.js process and
is independent of HTTP requests: a cut `down`/`up` request loses no bytes, the client just re-polls
with its offset.

- Pure Node.js 20+, **no npm dependencies** (`node:` built-ins only).
- One process, all session state in memory; demo counters persist to `data/state.json`.
- Only TCP to ports 80/443, IPv4 only, private/loopback/link-local/CGNAT/multicast/reserved
  targets are refused after DNS resolution.

## Files

| file | purpose |
|---|---|
| `app.js` | the server |
| `config.example.json` | copy to `config.json` (same folder) and put real tokens in it |
| `data/` | `state.json` (trial start / bytes per install, bytes per UTC day) — created automatically |
| `test/server.test.js` | self-contained tests (spawns the server on random ports) |

## Config

`config.json` next to `app.js`:

```json
{
  "personal": { "p1": "long-random-token-1", "p2": "long-random-token-2" },
  "demo": { "token": "demo-long-random-token", "max": 8, "seconds": 3600, "userMb": 2048, "dailyMb": 25600, "until": 1792011599 }
}
```

- `personal`: name → token (30+ random characters). No limits.
- `demo` (optional): one public token with limits — `max` concurrent installs, `seconds` of trial
  (wall-clock from first admission of an install), `userMb` per install, `dailyMb` for all demo
  traffic per UTC day, `until` = unix time when the demo ends (`0` = never).
- The file is re-read on `SIGHUP` and checked every 60 s (reloaded when its mtime changes). Tokens
  and request URLs are never logged.

## Run locally

```sh
cp config.example.json config.json   # edit the tokens
node app.js                          # listens on $PORT or 3000
PORT=8080 node app.js
```

Smoke check: `curl -i "http://127.0.0.1:3000/n/?k=wrong&s=abcdefgh&r=down&from=0"` → `404 no`
with `X-OF-Proto: 2`. Point the bridge at `http://127.0.0.1:3000/n/` (URL must end with `/` and
not with `.php` so the client uses v2).

Tests: `node --test test/server.test.js` (≈20 s).

Optional env (all have sane defaults; nothing needs to be set on Beget):

| env | default | meaning |
|---|---|---|
| `OF_CONFIG` / `OF_DATA_DIR` | `./config.json` / `./data` | paths |
| `OF_RETAIN_CAP` | 8 MiB | unacknowledged down bytes per session before destinations are paused |
| `OF_RUN_MS` | 100000 | length of a down response (clients without `ack`) |
| `OF_RUN_ACK_MS` | 300000 | length of a down response for `ack` clients (keep below Beget's ~370 s cut) |
| `OF_IDLE_MS` | 60000 | session GC (no down attached, no up) |
| `OF_STREAM_IDLE_MS` | 300000 | a stream with no data either way is closed (`CLOSE` to the client) |
| `OF_STREAM_PENDING_MAX` | 1 MiB | bytes queued towards one destination before up POSTs are held |
| `OF_STREAM_STUCK_MS` | 20000 | a destination over that cap that doesn't drain at all is closed |
| `OF_DIAL_MS` | 8000 | connect timeout per destination address (several A records are tried in order, 16 s total) |
| `OF_MAX_SESSIONS` | 2000 | |

**Test-only, never in production:** `OF_ALLOW_PRIVATE=1` (allow private/loopback targets),
`OF_ALLOW_PORTS=a,b` (extra destination ports), `OF_TEST_HOSTS='{"name":["ip",…]}'` (fake DNS).

### Protocol extensions (since the ack/mbb update)

Details in [`../PROTOCOL-v2.md`](../PROTOCOL-v2.md#extensions-capabilities--optional-backward-compatible).
A client that sends `&caps=ack,mbb` on `down` gets `X-OF-Caps: ack,mbb` and `X-OF-Run-Ms`:

- **ack** — the client reports its received offset with `&ack=N` on up POSTs and
  `POST ?r=ack&ack=N` (≈ every 1 s / 1 MiB while downloading). The exit frees retained bytes at once,
  so a download never has to end its poll to acknowledge; `ack` polls run `OF_RUN_ACK_MS`.
- **mbb** — ~5 s before the poll's end the client opens the next one with `&mbb=1`; the exit
  attaches it first and then ends the old response cleanly (no gap, connection reused).

Old clients (no `caps`) get exactly the previous behaviour; new clients detect old exits by the
missing `X-OF-Caps` header and fall back to the previous behaviour too.

## Deploy on Beget (Phusion Passenger)

Passenger starts `app.js` itself and hooks `http.createServer().listen()`, so `PORT` doesn't matter
there. Requests arrive with the base path (`/n/`); the app routes by the query string only.

1. Enable SSH for the account and check the Node binary: `which node` / `node -v` (need 20+;
   if the system one is older, install a user-level Node, e.g. via nvm, and use its full path).
2. Layout — the app root must be **inside the site directory but OUTSIDE `public_html`**
   (so `config.json` and `data/` are never served as static files):

   ```
   /home/u/user/example.com/
   ├── public_html/
   │   └── .htaccess
   └── ofexit/              ← PassengerAppRoot
       ├── app.js
       ├── config.json
       ├── data/
       └── tmp/restart.txt
   ```

3. `public_html/.htaccess`:

   ```apache
   PassengerEnabled On
   PassengerNodejs /home/u/user/.local/bin/node
   PassengerAppRoot /home/u/user/example.com/ofexit
   PassengerAppType node
   PassengerStartupFile app.js
   PassengerBaseURI /n
   ```

   (Use your real paths: `PassengerNodejs` = output of `which node`.)
4. Check: `http://example.com/n/` → `no` (404). Then in the app: OpenFlux URL
   `http://example.com/n/` + your token.
5. Restart after updating `app.js`: `mkdir -p ofexit/tmp && touch ofexit/tmp/restart.txt`.
   Config changes don't need a restart (picked up within 60 s).

Notes:
- **Single process is required** — sessions are in memory. Node apps under Passenger handle
  concurrent requests in one process by default; don't raise the instance count for this app.
- Passenger may stop the app after a few idle minutes; that's harmless (counters are in
  `data/state.json`, clients just open a new session).
- Responses carry `X-Accel-Buffering: no` and a 4096-byte preamble + 4096-aligned writes so the
  front nginx's 4 KiB buffer flushes promptly.
- `*.beget.tech` subdomains have no HTTPS (token visible on the path); attach your own domain with
  a free certificate and use `https://`.

## Private stats page

Open `http://your-site/n/?r=stats&k=<admin token>` (add `&format=json` for JSON). Admins are the
personal token ids listed in `"admin": ["p1"]` in `config.json` (default: `p1`). Any other token —
including other personal tokens and the demo token — gets the same `404 no` as a wrong token.

The page shows counts only (no tokens, no install ids, no destinations): open sessions per
personal token, last activity, traffic today / 7 days, total sessions; for the demo — slots in use,
devices in their trial now, trials started today / total, demo traffic today vs the daily cap.
The URL contains your token: don't share it.

Страница статистики: `http://ваш-сайт/n/?r=stats&k=<ваш токен>` — только для токенов из `admin`
(по умолчанию `p1`), остальным — `404 no`. Только цифры, без токенов и адресов. Ссылку не передавайте:
в ней ваш токен.
