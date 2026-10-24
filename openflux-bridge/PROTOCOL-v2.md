# OpenFlux resumable stream protocol (v2) — MyAppVPN

v1 = the old `phpbox.php` exit (no longer supported by the bridge) (one PHP process per session; a session dies when its
HTTP request is cut). v2 keeps the same **frame format** but makes the **session independent of
HTTP requests**: it lives in a persistent server process (Node.js via Passenger on Beget), and
both directions are byte-offset sequenced so a cut request loses nothing.

## Frames (unchanged from v1)

`type(1) | stream_id(4, BE) | len(4, BE) | payload`

| type | name | direction | payload |
|---|---|---|---|
| 1 | OPEN | c→s | `host:port` (IPv6 literal may be `[v6]:port`), optionally followed by `\0fc` (ignore the suffix) |
| 2 | DATA | both | bytes |
| 3 | CLOSE | both | empty |
| 4 | OPEN_OK | s→c | empty (never "fc": the server does not do per-stream acks) |
| 5 | OPEN_ERR | s→c | reason text (`port`, `blocked`, `ipv6`, `timeout`, `refused`, `dns`, …) |
| 6 | ACK | c→s | ignore |

Stream id `0xFFFFFFFF` is reserved for padding/keepalive DATA frames; clients drop them.

## Endpoint

Base URL ends with `/` (e.g. `http://host/n/`). Query parameters on every request:
`k` token, `s` session id (client-chosen, `[A-Za-z0-9]{8,64}`), `c` install id (`[A-Za-z0-9-]{8,64}`,
required for the demo token, optional otherwise), `r` = `down` | `up`, `v=2`.

Every response carries `X-OF-Proto: 2`.

### Auth / admission (same semantics as the PHP exit)

Config file `config.json` next to the server:
```json
{
  "personal": { "p1": "token…" },
  "demo": { "token": "demo-…", "max": 8, "seconds": 3600, "userMb": 2048, "dailyMb": 25600, "until": 1792011599 }
}
```
- Unknown token → **404** body `no`.
- Personal token: no limits, any number of concurrent sessions.
- Demo token: admission is checked when a **new** session is created (first `down` with `from=0` and
  an unknown `s`). Refusals → **503** with header `X-OF-Reason`:
  `noclient` (missing/short `c`), `ended` (now > `until`), `expired` (trial started more than
  `seconds` ago — wall-clock from FIRST admission of that `c` — or that install used ≥ `userMb`),
  `quota` (all demo traffic today ≥ `dailyMb`), `busy` (live demo sessions ≥ `max`; a live session
  of the same `c` is replaced, not counted). Each `down` response of a demo session sends
  `X-OF-Left: <seconds>` and `X-OF-Left-MB: <MB>`. When a demo session hits a limit mid-way it is
  destroyed (sockets closed); the next `down`/`up` gets the 503 with the reason.
- Counters (trial start per `c`, bytes per `c`, bytes per UTC day) persist to `data/state.json`
  (debounced write, atomic rename) so a process restart doesn't reset them.

### `GET ?r=down&from=N`

`N` = number of down-stream bytes the client has already received for this session (0 for a new
session). The server:
1. If session `s` is unknown: `from=0` → create (after admission); `from>0` → **410** `gone`.
2. Discards its retained down-bytes below `N` (they are acknowledged).
3. If `N` is outside `[acked, produced]` → **410** `gone`.
4. Responds `200`, `Content-Type: application/octet-stream`, `X-Accel-Buffering: no`,
   `Cache-Control: no-store`, then writes a **4096-byte preamble**: the ASCII text `OF2 <N>`
   followed by spaces up to 4095 bytes and a final `\n` (exactly 4096 bytes, so the front nginx's
   4 KiB buffer flushes). After the preamble comes
   the raw down byte stream **starting exactly at offset N**.
5. Keeps streaming new bytes as they are produced. Writes are padded with a keepalive/pad DATA
   frame (stream `0xFFFFFFFF`) so that output reaches a 4096-byte boundary after every burst, and
   a 4096-byte pad frame is sent every 5 s when idle. **Pad frames are part of the sequenced byte
   stream** (they count toward offsets) — the client just drops them after parsing.
6. Ends the response cleanly after ~100 s; the client immediately re-polls with its new offset.
   Only one `down` per session at a time: a newer `down` replaces (ends) the older one.

### `POST ?r=up&off=M` (body = frame bytes)

`M` = up-stream offset of the first body byte. Server keeps `upRecv` (bytes accepted so far):
- `M == upRecv`: accept all; `M < upRecv`: skip the already-accepted prefix, accept the tail;
  `M > upRecv`: **409** body `need <upRecv>`.
- Unknown session → **410** `gone`.
- Success → **200** body `ok <upRecv>` (new value).
Max body 4 MiB (413 above).

### Session lifetime / flow control

- Session destroyed after 60 s with no `down` attached and no `up`, or on a demo limit.
- Retained (unacknowledged) down bytes are capped at 8 MiB per session: when the cap is reached the
  server pauses reading from that session's destination sockets (backpressure) until the client
  acknowledges more by re-polling.
- Destination dialing: async `net.connect`, 8 s timeout per address, ports 80/443 only, IPv4 only
  (resolve with `dns.lookup` family 4, 5-min cache), refuse private/loopback/link-local/CGNAT/
  multicast/reserved ranges after resolution. Re-sent OPEN for an existing/pending stream id: answer
  OPEN_OK again if connected, ignore if pending — never redial.

### Destination streams (exit side)

- A destination host with several A records: addresses are tried in order, 8 s connect timeout each
  (`OF_DIAL_MS`), 16 s for all of them; `OPEN_ERR` carries the last failure.
- A stream with no data in either direction for 300 s (`OF_STREAM_IDLE_MS`) is closed: the exit sends
  `CLOSE` and drops the socket.
- Up backpressure: an up POST is held (polled every 25 ms, up to 25 s) while the session has more than
  4 MiB queued towards destinations **or** one destination has more than 1 MiB queued
  (`OF_STREAM_PENDING_MAX`). A connected destination over that cap that hasn't drained at all for 20 s
  (`OF_STREAM_STUCK_MS`) is closed (`CLOSE` to the client) so it can't stall the whole session.
- When a session ends while a `down` is attached (demo limit, protocol error, replaced), the exit
  first sends `CLOSE` for every open stream, then ends the response; all sockets are destroyed.

## Extensions (capabilities) — optional, backward compatible

A client that supports them adds `&caps=ack,mbb` to its `down` requests. An exit that knows the
parameter answers with `X-OF-Caps: <the supported subset>` and `X-OF-Run-Ms: <ms>` (how long this
response will run before the exit ends it). No `caps` parameter → no such headers, nothing changes.
A client that gets no `X-OF-Caps` header (old exit, or a proxy stripped it) behaves exactly as without
extensions. The capabilities are remembered per session.

### `ack` — acknowledge without re-polling

- The client reports its received down offset `N` (all bytes below `N` are held):
  - `&ack=N` on every up POST, and
  - while only downloading, `POST ?r=ack&ack=N` (empty body; `GET` is accepted too) about every 1 s
    or every 1 MiB received, whichever comes first (and every 30 s while idle pads arrive).
  Answer: `200 ok <acked>`; unknown session → 410 (or 503 with the stored reason).
- The exit releases retained bytes below `N` like a new poll's `from` — but never beyond what the
  **current** response has written (its byte stream must stay contiguous). `N` beyond what exists is
  ignored.
- So the client never rotates the poll to acknowledge, and the exit ends `ack` responses only after
  `OF_RUN_ACK_MS` (default 300 s, below Beget's ~370 s cut). Ending at half the retention cap is kept
  only as a safety net when no ack arrived for 5 s.
- An ack can overtake a new poll (`from=X` sent, then `ack=Y>X` arrives first): for an `ack` session the
  exit then starts that response at its acknowledged offset (preamble `OF2 <acked>`) instead of 410;
  the client accepts any preamble offset in `[from, received]` and drops what it already has.

### `mbb` — make-before-break

- About 5 s before `X-OF-Run-Ms` runs out, the client opens the next poll with
  `&from=<received>&mbb=1` while still reading the current one.
- The exit installs the new response first (it starts at `from`), then ends the old one cleanly
  (`res.end()`); without `mbb=1` the old one is ended first, as always. The client reads the old body
  to its end (never closes it unread) and drops bytes below its received offset — offsets make the
  overlap trivial.
- Not used with `poll=short` (short polls end by design).

## Client (bridge) behaviour

- Only v2 exits are supported. A host that answers the first poll with 200 but neither
  `X-OF-Proto: 2` nor a v2 preamble (e.g. the old `phpbox.php`) is reported as
  `OFSTATUS unsupported` and the session ends.
- `-caps off` disables the extensions (A/B tests); default `on`.
- One session id for the whole carrier life. Down loop: `GET down&from=recv`; parse the 4096-byte
  preamble (`OF2 <n>`; must equal `recv` — with `ack`, anything in `[from, recv]`), feed the bytes
  to the mux, `recv += n`. Without `ack` the poll is rotated every 4 MiB (the only way to acknowledge).
  On end/error: re-poll after 200 ms (20 ms after a clean end with `ack` or short polls) (backoff up to 2 s on repeated network errors). **410** → the session is
  gone → report session end (mux closes, new session). **503/404** → status line (`OFSTATUS busy|quota|expired|ended|noclient|badtoken`) and pause.
- Up: frames queued with offsets; POST batches (≤ 1 MiB) sequentially with `off`; keep bytes until
  `ok <upRecv>` acknowledges them; on network error/5xx retry the same batch (bounded backoff);
  **409 need X** → resend from X; **410** → session end.
