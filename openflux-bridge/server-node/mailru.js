'use strict';
/**
 * Mail.ru Docs channel for the OpenFlux Node exit (MyAppVPN): the exit joins a shared public
 * Mail.ru Cloud document (docs.datacloudmail.ru, the R7/OnlyOffice co-editing backend) over an
 * outbound WebSocket and serves the phpbox stream mux to a client that joined the same document
 * (upstream OpenFlux "stream mode": streamproxy + the mailru carrier). No listening socket, no
 * inbound HTTP: useful where the client can reach mail.ru but not the exit's own host.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Ported from upstream OpenFlux (github.com/p1neappleXpress/OpenFlux, release/0.13.0, GPL-3.0):
 *   - transport/mailru/mailru.go   document API (r7/edit), Engine.IO/Socket.IO handshake, editor
 *                                  auth, chat data path ("OFX1" + base64, echo filter by editor
 *                                  id), connectState → unLockDocument, make-before-break rotation
 *                                  (by age / by message count), send pacing, reconnect backoff;
 *   - transport/phpbox/frame.go, mux.go   frame format and the OPEN "\0fc" / OPEN_OK "fc" / ACK
 *                                  flow-control handshake (client side, which we must match);
 *   - deploy/phpbox/lib/mux.php    the exit side of that mux: windows, ack policy, outbox with
 *                                  control frames first and DATA round-robin over streams.
 * Destination policy and dialing (ports 80/443, IPv4 only, private ranges refused, several A
 * records) are app.js's own (parseOpenTarget / dialTarget), shared with the HTTP v2 sessions.
 *
 * Wire, per chat message: 42["message",{"type":"message","message":"OFX1<base64>"}]; the base64
 * holds whole mux frames type(1)|sid(4 BE)|len(4 BE)|payload. The Go client sends one frame per
 * message and concatenates what it receives, so the exit packs several whole frames per message
 * (the server's budget is per message, not per byte). One document = one client (stream ids are
 * per client mux): a reused stream id is taken as a new client and the old streams are dropped.
 *
 * Pure Node.js 20+, no dependencies: a small RFC 6455 client is included (Node 20 has no global
 * WebSocket without a flag, and Mail.ru's nginx answers 409 to an upgrade that does not offer
 * permessage-deflate, so the client offers it and inflates compressed server messages).
 */

const http = require('node:http');
const https = require('node:https');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const { EventEmitter } = require('node:events');

// ── constants ────────────────────────────────────────────────────────────────

const T_OPEN = 1, T_DATA = 2, T_CLOSE = 3, T_OPEN_OK = 4, T_OPEN_ERR = 5, T_ACK = 6;
const HDR = 9;
const MAX_FRAME = 4 * 1048576;
const CHAT_TAG = 'OFX1';
const FC = Buffer.from('fc');

const API_EDIT = 'https://cloud.mail.ru/api/v4/r7/edit';
const ORIGIN = 'https://docs.datacloudmail.ru';
// Mail.ru answers 409 to the WebSocket upgrade for some older UAs (upstream issue #164).
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0';
const UNLOCK_MSG = '42["message",{"type":"unLockDocument","unlock":true,"isSave":false,"releaseLocks":false,"deleteIndex":-1}]';

const READ_CHUNK = 65536;         // max DATA payload read from a destination per frame
const ACK_EVERY = 32768;          // ack after this many bytes reached a destination (or its queue emptied)
const STALL_MS = 20000;           // blocked on a window with no ack this long: assume the acks were lost
const HANDSHAKE_MS = 15000;
const ROTATE_AUTH_WAIT_MS = 8000;
const SILENT_MS = 75000;          // nothing from the server this long (pings are every 25 s): drop it
const RECENT_MS = 120000;         // closed/failed stream ids remembered (to tell an OPEN retry from a new client)

const DEFAULTS = {
  msgIntervalMs: 200,             // min gap between chat writes (R7 cuts a burst with 4004)
  rotateMs: 25000,                // rotate a busy connection before the server's ~45 s kick
  rotateMsgs: 60,                 // ...or after this many chat messages (4004 budget ~80-90)
  maxMessageBytes: 262144,        // mux bytes per chat message (before base64)
  streamWindow: 262144,           // DATA unacknowledged by the client, per stream (0 = no flow control)
  totalWindow: 524288,            // ... over all streams
  outStreamMax: 524288,           // DATA waiting for the carrier per stream before its destination is paused
  outAllMax: 2097152,             // ... over all streams
};

function nowMs() { return Date.now(); }

// ── WebSocket client (RFC 6455; permessage-deflate on receive) ───────────────

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const WS_MAX_MSG = 32 * 1048576;
const INFLATE_TAIL = Buffer.from([0x00, 0x00, 0xff, 0xff]);

class WsClient extends EventEmitter {
  constructor() {
    super();
    this.sock = null;
    this.closed = false;
    this.deflate = false;
    this.serverBits = 15;
    this.serverNoCtx = false;
    this.chunks = []; this.len = 0; this.needLen = 0;
    this.msgOp = 0; this.msgParts = []; this.msgLen = 0; this.msgCompressed = false;
    this.chain = Promise.resolve();
    this.inflater = null;
    this.infFail = null;
  }

  get bufferedAmount() { return this.sock ? this.sock.writableLength : 0; }

  /** Resolves once the upgrade succeeded; rejects with "http <status>" / a network error. */
  connect(url, headers = {}, timeoutMs = HANDSHAKE_MS) {
    return new Promise((ok, fail) => {
      let u;
      try { u = new URL(url); } catch { return fail(new Error('bad websocket url')); }
      const secure = u.protocol === 'wss:' || u.protocol === 'https:';
      const key = crypto.randomBytes(16).toString('base64');
      const opts = {
        hostname: u.hostname,
        port: u.port || (secure ? 443 : 80),
        path: (u.pathname || '/') + (u.search || ''),
        method: 'GET',
        headers: Object.assign({
          'Connection': 'Upgrade',
          'Upgrade': 'websocket',
          'Sec-WebSocket-Version': '13',
          'Sec-WebSocket-Key': key,
          // Mail.ru's nginx answers 409 to an upgrade without permessage-deflate (issue #163).
          'Sec-WebSocket-Extensions': 'permessage-deflate; client_max_window_bits',
          'User-Agent': USER_AGENT,
        }, headers),
      };
      const req = (secure ? https : http).request(opts);
      const to = setTimeout(() => { req.destroy(new Error('handshake timeout')); }, timeoutMs);
      req.on('error', (e) => { clearTimeout(to); if (!this.closed) fail(e); });
      req.on('response', (res) => {
        clearTimeout(to);
        res.resume();
        fail(new Error('http ' + res.statusCode));   // a non-101 response: no upgrade
      });
      req.on('upgrade', (res, socket, head) => {
        clearTimeout(to);
        const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
        if (res.headers['sec-websocket-accept'] !== accept) {
          socket.destroy();
          return fail(new Error('bad accept'));
        }
        const ext = String(res.headers['sec-websocket-extensions'] || '');
        if (/permessage-deflate/i.test(ext)) {
          this.deflate = true;
          this.serverNoCtx = /server_no_context_takeover/i.test(ext);
          const m = /server_max_window_bits=(\d+)/i.exec(ext);
          if (m) this.serverBits = Number(m[1]);
        }
        this.sock = socket;
        socket.setNoDelay(true);
        socket.on('data', (d) => this._onData(d));
        socket.on('error', (e) => this._fail(e));
        socket.on('close', () => this._fail(new Error('closed')));
        if (head && head.length) this._onData(head);
        ok();
      });
      req.end();
    });
  }

  _fail(err) {
    if (this.closed) return;
    this.closed = true;
    try { if (this.sock) this.sock.destroy(); } catch {}
    this.emit('close', err);
  }

  close() {
    if (this.closed) return;
    try { if (this.sock) this.sock.end(this._encode(0x8, Buffer.alloc(0))); } catch {}
    this._fail(new Error('local close'));
  }

  /** Send one text message (client frames are masked; we never compress what we send). */
  sendText(str) {
    if (this.closed || !this.sock) throw new Error('ws closed');
    return this.sock.write(this._encode(0x1, Buffer.from(str, 'utf8')));
  }

  _encode(opcode, payload) {
    const len = payload.length;
    let header;
    if (len < 126) header = Buffer.allocUnsafe(2);
    else if (len < 65536) { header = Buffer.allocUnsafe(4); header.writeUInt16BE(len, 2); }
    else { header = Buffer.allocUnsafe(10); header.writeUInt32BE(0, 2); header.writeUInt32BE(len >>> 0, 6); }
    header[0] = 0x80 | opcode;
    header[1] = (len < 126 ? len : len < 65536 ? 126 : 127) | 0x80;   // mask bit
    const mask = crypto.randomBytes(4);
    const masked = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
    return Buffer.concat([header, mask, masked]);
  }

  _onData(buf) {
    this.chunks.push(buf); this.len += buf.length;
    this._parse();
  }

  _parse() {
    while (!this.closed) {
      const b = this.chunks.length === 1 ? this.chunks[0] : Buffer.concat(this.chunks, this.len);
      this.chunks = b.length ? [b] : []; this.len = b.length;
      if (b.length < 2) return;
      const fin = (b[0] & 0x80) !== 0;
      const rsv1 = (b[0] & 0x40) !== 0;
      const opcode = b[0] & 0x0f;
      if (b[1] & 0x80) { return this._fail(new Error('masked server frame')); }   // server frames are never masked
      let len = b[1] & 0x7f, off = 2;
      if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (b.length < 10) return; const hi = b.readUInt32BE(2); if (hi) return this._fail(new Error('frame too large')); len = b.readUInt32BE(6); off = 10; }
      if (len > WS_MAX_MSG) return this._fail(new Error('frame too large'));
      if (b.length < off + len) return;
      const payload = b.subarray(off, off + len);
      const rest = b.subarray(off + len);
      this.chunks = rest.length ? [Buffer.from(rest)] : []; this.len = rest.length;
      this._frame(fin, rsv1, opcode, payload);
    }
  }

  _frame(fin, rsv1, opcode, payload) {
    if (opcode === 0x8) return this._fail(new Error('server close'));
    if (opcode === 0x9) { try { this.sock.write(this._encode(0xA, payload)); } catch {} return; }   // ping → pong
    if (opcode === 0xA) return;   // pong
    if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
      if (opcode !== 0x0) { this.msgOp = opcode; this.msgParts = []; this.msgLen = 0; this.msgCompressed = rsv1; }
      this.msgParts.push(Buffer.from(payload)); this.msgLen += payload.length;
      if (!fin) return;
      const whole = this.msgParts.length === 1 ? this.msgParts[0] : Buffer.concat(this.msgParts, this.msgLen);
      this.msgParts = []; this.msgLen = 0;
      if (this.msgCompressed && this.deflate) return this._inflate(whole);
      if (this.msgOp === 0x1) this.emit('text', whole.toString('utf8'));
    }
  }

  // permessage-deflate: inflate each message with the RFC 7692 tail; keep the context between
  // messages unless the server negotiated no_context_takeover. Serialized through this.chain so
  // messages stay in order (zlib callbacks are async).
  _inflate(data) {
    if (!this.inflater) {
      this.inflater = zlib.createInflateRaw({ windowBits: Math.max(9, this.serverBits) });
      this.inflater.on('error', (e) => { this.infFail = e; this._fail(e); });
    }
    const inf = this.inflater;
    const noCtx = this.serverNoCtx;
    this.chain = this.chain.then(() => new Promise((done) => {
      if (this.closed || this.infFail) return done();
      const out = []; let n = 0;
      const onData = (c) => { out.push(c); n += c.length; };
      inf.on('data', onData);
      inf.write(Buffer.concat([data, INFLATE_TAIL]), () => {
        inf.flush(zlib.constants.Z_SYNC_FLUSH, () => {
          inf.removeListener('data', onData);
          if (!this.closed && !this.infFail && this.msgOp === 0x1) this.emit('text', Buffer.concat(out, n).toString('utf8'));
          if (noCtx) { inf.reset(); }
          done();
        });
      });
    }));
  }
}

// ── mux framing ──────────────────────────────────────────────────────────────

function encodeFrame(type, sid, payload) {
  const len = payload ? payload.length : 0;
  const b = Buffer.allocUnsafe(HDR + len);
  b[0] = type; b.writeUInt32BE(sid >>> 0, 1); b.writeUInt32BE(len, 5);
  if (len) payload.copy(b, HDR);
  return b;
}

/** Pull one whole frame off the front of buf → { frame:{type,sid,payload}, rest } or null. */
function takeFrame(buf) {
  if (buf.length < HDR) return null;
  const len = buf.readUInt32BE(5);
  if (buf.length - HDR < len) return null;
  return {
    frame: { type: buf[0], sid: buf.readUInt32BE(1), payload: buf.subarray(HDR, HDR + len) },
    rest: buf.subarray(HDR + len),
  };
}

// ── chat payload (de)serialization ───────────────────────────────────────────

/**
 * Decode the mux bytes a server chat broadcast carries from the other participant:
 *   42["message",{"type":"message","messages":[{message,useridoriginal},…]}]
 * Keeps only our-tagged entries (CHAT_TAG) whose useridoriginal is not ours (the server echoes our
 * own messages back), base64-decodes them. isOwn(id) → true for an id this exit authenticated as.
 * Returns an array of Buffers (one per kept entry), in order.
 */
function decodeChatData(text, isOwn) {
  const i = text.indexOf('[');
  if (i < 0) return [];
  let arr;
  try { arr = JSON.parse(text.slice(i)); } catch { return []; }
  if (!Array.isArray(arr) || arr.length < 2 || !arr[1] || arr[1].type !== 'message') return [];
  const msgs = Array.isArray(arr[1].messages) ? arr[1].messages : [];
  const out = [];
  for (const m of msgs) {
    if (!m || typeof m.message !== 'string') continue;
    if (isOwn && isOwn(m.useridoriginal)) continue;
    if (!m.message.startsWith(CHAT_TAG)) continue;
    try {
      const dec = Buffer.from(m.message.slice(CHAT_TAG.length), 'base64');
      if (dec.length) out.push(dec);
    } catch {}
  }
  return out;
}

/** The chat message that carries frameBytes (whole mux frames) to the peer. */
function encodeChatMessage(frameBytes) {
  const payload = CHAT_TAG + frameBytes.toString('base64');
  return '42' + JSON.stringify(['message', { type: 'message', message: payload }]);
}

// ── exit-side stream mux ───────────────────────────────────────────────────────
// The exit side of the phpbox stream mux: the client Dials destinations (OPEN), we dial them and
// carry bytes both ways (DATA), honour the client's flow-control windows (OPEN "\0fc" → OPEN_OK
// "fc", ACK) and acknowledge what we write to destinations. Ported from deploy/phpbox/lib/mux.php
// (exit role) and interoperable with transport/phpbox/mux.go (client role). Carrier-agnostic: it
// writes whole frames into an outbox the carrier drains (buildMessage) and feeds it received bytes
// (onPacket); dialing/target policy are injected (app.js's parseOpenTarget / dialTarget).

class StreamMux {
  /**
   * deps: { parseTarget(payload)->{host,port,caps}|{err}, dial(st,host,port,live,done),
   *         account(nBytes)?, log(msg)?, opts? }
   */
  constructor(deps) {
    this.d = deps;
    this.opts = Object.assign({}, DEFAULTS, deps.opts || {});
    this.streams = new Map();     // sid -> stream
    this.recent = new Map();      // sid -> expiry ms (closed/failed; an OPEN retry vs a new client)
    this.rxBuf = null;
    this.ctlQ = [];               // encoded control frames (OPEN_OK/OPEN_ERR/ACK), sent first
    this.bulkAll = 0;             // DATA bytes queued over all streams
    this.rrNext = -1;             // last stream served (round-robin)
    this.inAll = 0;               // dest->client bytes unacknowledged by the client, all streams
    this.dead = false;
    this.stats = { opened: 0, closed: 0, failed: 0, up: 0, down: 0 };
  }

  log(m) { if (this.d.log) this.d.log(m); }
  activeStreams() { return this.streams.size; }
  outboxBytes() { return this.bulkAll + this.ctlQ.reduce((a, b) => a + b.length, 0); }

  // ---- receive: reassemble frames, apply them ----

  onPacket(buf) {
    if (this.dead) return;
    let b = this.rxBuf ? Buffer.concat([this.rxBuf, buf]) : buf;
    this.rxBuf = null;
    for (;;) {
      const t = takeFrame(b);
      if (!t) break;
      if (t.frame.payload.length > MAX_FRAME) { this.log('protocol: frame too large'); this.close(); return; }
      b = t.rest;
      this.applyFrame(t.frame.type, t.frame.sid, t.frame.payload);
      if (this.dead) return;
    }
    if (b.length) this.rxBuf = Buffer.from(b);   // copy: don't pin the whole chat buffer
  }

  applyFrame(type, sid, payload) {
    if (type === T_OPEN) return this.onOpen(sid, payload);
    const st = this.streams.get(sid);
    if (type === T_DATA) {
      if (!st || !payload.length) return;
      this.stats.up += payload.length;
      if (this.d.account) this.d.account(payload.length);
      st.lastIo = nowMs();
      if (st.state === 'open') { st.sock.write(payload); this.wrote(st, payload.length); }
      else { st.queue.push(Buffer.from(payload)); st.qbytes += payload.length; }
    } else if (type === T_ACK) {
      if (st && st.fc && payload.length === 4) this.onAck(st, payload.readUInt32BE(0));
    } else if (type === T_CLOSE) {
      if (st) this.closeStream(st, false);
    }
    // OPEN_OK/OPEN_ERR from a client are not expected on the exit side: ignored.
  }

  onOpen(sid, payload) {
    const have = this.streams.get(sid);
    if (have) { if (have.state === 'open') this.sendFrame(T_OPEN_OK, sid, have.fc ? FC : null); return; }  // retry: answer again, never redial
    if (this.recent.has(sid)) return;   // a just-closed stream: the client's lost CLOSE crossed its own OPEN retry
    const t = this.d.parseTarget(payload);
    if (t.err) { this.stats.failed++; return this.sendFrame(T_OPEN_ERR, sid, Buffer.from(t.err)); }
    const wantFc = this.opts.streamWindow > 0 && t.caps.includes('fc');

    const st = {
      sid, state: 'resolving', sock: null, queue: [], qbytes: 0, timer: null,
      clientClosed: false, failed: false, lastIo: nowMs(), paused: false,
      fc: null, want: wantFc,
    };
    this.streams.set(sid, st);
    const live = () => !this.dead && this.streams.get(sid) === st;
    this.d.dial(st, t.host, t.port, live, (reason, sock) => {
      if (reason) {
        if (st.failed) return;
        st.failed = true;
        if (live()) { this.streams.delete(sid); this.remember(sid); this.stats.failed++; this.sendFrame(T_OPEN_ERR, sid, Buffer.from(reason)); }
        return;
      }
      this.onConnected(st, sock, live);
    });
  }

  onConnected(st, sock, live) {
    const sid = st.sid;
    st.state = 'open';
    st.lastIo = nowMs();
    sock.setNoDelay(true);
    if (st.want) st.fc = { sent: 0, acked: 0, wrote: 0, ackedSent: 0, since: 0 };
    this.stats.opened++;
    this.sendFrame(T_OPEN_OK, sid, st.fc ? FC : null);
    for (const q of st.queue) { sock.write(q); this.wrote(st, q.length); }
    st.queue = []; st.qbytes = 0;
    this.maybePause(st);
    sock.on('data', (chunk) => {
      if (st.clientClosed || !live()) return;
      st.lastIo = nowMs();
      this.stats.down += chunk.length;
      if (this.d.account) this.d.account(chunk.length);
      // Bound a DATA frame to READ_CHUNK so one message packs predictably (the client reassembles
      // frames across messages regardless): a big read becomes several 64 KB DATA frames.
      for (let off = 0; off < chunk.length; off += READ_CHUNK) {
        const piece = chunk.subarray(off, Math.min(off + READ_CHUNK, chunk.length));
        if (st.fc) { st.fc.sent = (st.fc.sent + piece.length) >>> 0; this.inAll += piece.length; }
        this.sendFrame(T_DATA, sid, piece);
      }
      this.maybePause(st);
    });
    sock.on('error', () => {});
    sock.on('close', () => { if (!st.clientClosed && live()) this.closeStream(st, true); });
  }

  remember(sid) { this.recent.set(sid, nowMs() + RECENT_MS); }

  closeStream(st, notify) {
    const sid = st.sid;
    if (this.streams.get(sid) !== st) return;
    this.streams.delete(sid);
    this.remember(sid);
    clearTimeout(st.timer);
    if (st.sock) st.sock.destroy();
    if (st.fc) { this.inAll -= (st.fc.sent - st.fc.acked) >>> 0; st.fc = null; }
    this.stats.closed++;
    if (notify) this.sendFrame(T_CLOSE, sid, null);
  }

  // ---- flow control ----

  /** Pause a destination socket while its stream (or all streams) owe a window to the client. */
  maybePause(st) {
    const over = this.streamBlocked(st);
    if (over && !st.paused && st.sock && st.state === 'open') { st.paused = true; st.sock.pause(); }
    else if (!over && st.paused && st.sock && st.state === 'open') { st.paused = false; st.sock.resume(); }
  }

  streamBlocked(st) {
    const sid = st.sid;
    const bulkStream = st.bulk ? st.bulkLen : 0;
    if (bulkStream >= this.opts.outStreamMax || this.bulkAll >= this.opts.outAllMax) return true;
    if (!st.fc) return false;
    const inflight = (st.fc.sent - st.fc.acked) >>> 0;
    if (inflight === 0 || (inflight < this.opts.streamWindow && this.inAll < this.opts.totalWindow)) { st.fc.since = 0; return false; }
    if (!st.fc.since) st.fc.since = nowMs();
    else if (nowMs() - st.fc.since > STALL_MS) {   // no ack for too long: assume lost, carry on
      this.inAll -= inflight; st.fc.acked = st.fc.sent; st.fc.since = 0;
      return false;
    }
    return true;
  }

  onAck(st, v) {
    const delta = (v - st.fc.acked) >>> 0;
    if (delta !== 0 && delta <= ((st.fc.sent - st.fc.acked) >>> 0)) {   // never beyond what was sent
      st.fc.acked = v; st.fc.since = 0; this.inAll -= delta;
      this.maybePause(st);
    }
  }

  /** Bytes reached a destination: owe the client an ack for them. */
  wrote(st, n) {
    if (st.fc && n > 0) { st.fc.wrote = (st.fc.wrote + n) >>> 0; st.ackDirty = true; }
  }

  /** Queue owed acks: after a chunk reached a destination, or when its write queue emptied. */
  flushAcks() {
    for (const st of this.streams.values()) {
      if (!st.fc || !st.ackDirty) continue;
      const due = (st.fc.wrote - st.fc.ackedSent) >>> 0;
      const queued = st.sock ? st.sock.writableLength : 0;
      if (due >= ACK_EVERY || (due > 0 && queued === 0)) {
        st.fc.ackedSent = st.fc.wrote; st.ackDirty = false;
        const p = Buffer.allocUnsafe(4); p.writeUInt32BE(st.fc.wrote >>> 0, 0);
        this.sendFrame(T_ACK, st.sid, p);
      }
    }
  }

  // ---- outbox: control frames first, DATA round-robin over streams ----

  sendFrame(type, sid, payload) {
    if (this.dead) return;
    if (type === T_DATA || type === T_CLOSE) {
      const st = this.streams.get(sid);
      const frame = encodeFrame(type, sid, payload);
      if (!st) {   // a CLOSE for a stream already gone (notify on dest close): send as control
        if (type === T_CLOSE) this.ctlQ.push(frame);
        return;
      }
      if (!st.bulk) { st.bulk = []; st.bulkLen = 0; }
      st.bulk.push(frame); st.bulkLen += frame.length; this.bulkAll += frame.length;
    } else {
      this.ctlQ.push(encodeFrame(type, sid, payload));
    }
  }

  /** Whole frames for one chat message, up to cap bytes: control frames first, then streams in turn. */
  buildMessage(cap) {
    const parts = [];
    let used = 0;
    while (this.ctlQ.length && used + this.ctlQ[0].length <= cap) {
      const f = this.ctlQ.shift(); parts.push(f); used += f.length;
    }
    const sids = [];
    for (const st of this.streams.values()) if (st.bulk && st.bulk.length) sids.push(st.sid);
    if (!sids.length) {
      // a CLOSE for a gone stream may sit in ctlQ even when no stream has bulk
      return parts.length ? Buffer.concat(parts, used) : Buffer.alloc(0);
    }
    sids.sort((a, b) => a - b);
    let start = 0;
    for (let i = 0; i < sids.length; i++) if (sids[i] > this.rrNext) { start = i; break; }
    const order = sids.slice(start).concat(sids.slice(0, start));
    outer:
    for (const sid of order) {
      const st = this.streams.get(sid);
      if (!st || !st.bulk) continue;
      while (st.bulk.length) {
        const f = st.bulk[0];
        if (used + f.length > cap) { if (used === 0 && f.length > cap) { /* oversized single frame: send alone */ } else { this.rrNext = sid; break outer; } }
        st.bulk.shift(); st.bulkLen -= f.length; this.bulkAll -= f.length;
        parts.push(f); used += f.length;
        this.maybePause(st);
      }
      if (!st.bulk.length) { st.bulk = null; st.bulkLen = 0; }
      this.rrNext = sid;
    }
    return parts.length ? Buffer.concat(parts, used) : Buffer.alloc(0);
  }

  // ---- housekeeping ----

  /** Close streams with no data either way for idleMs (a lost CLOSE must not leak a socket). */
  sweepIdle(idleMs) {
    if (this.dead || idleMs <= 0) return;
    const now = nowMs();
    for (const st of [...this.streams.values()]) {
      if (st.state !== 'open' || (st.sock && st.sock.writableLength) || st.qbytes) continue;
      if (now - st.lastIo >= idleMs) this.closeStream(st, true);
    }
    for (const [sid, exp] of this.recent) if (exp < now) this.recent.delete(sid);
  }

  close() {
    if (this.dead) return;
    this.dead = true;
    for (const st of this.streams.values()) { clearTimeout(st.timer); if (st.sock) st.sock.destroy(); }
    this.streams.clear();
    this.ctlQ = []; this.bulkAll = 0; this.rxBuf = null;
  }
}

// ── Mail.ru document carrier / channel ─────────────────────────────────────────
// Owns the outbound WebSocket to the shared document: the r7/edit lookup, the Engine.IO/Socket.IO
// handshake, the editor auth, the read loop (feeds the mux), a paced writer loop (drains the mux's
// outbox into chat messages), connection rotation before the server's ~45 s anti-abuse kick, and
// reconnect with backoff. Ported from transport/mailru/mailru.go (client/exit are symmetric on
// this channel). fetchDoc is injectable so the mocked-WebSocket test can supply doc info.

function normalizeWeblink(link) {
  link = String(link || '').trim();
  for (const p of ['https://cloud.mail.ru/public/', 'http://cloud.mail.ru/public/',
                   'https://cloud.mail.ru/', 'http://cloud.mail.ru/']) {
    if (link.startsWith(p)) return link.slice(p.length).replace(/^\/+|\/+$/g, '');
  }
  return link;
}

function randUserID() { return String(crypto.randomInt(0, 1e9)).padStart(10, '0'); }

/** POST the public-document editor API and extract the fields needed to open the WebSocket. */
function httpPostJson(url, bodyObj, headers) {
  return new Promise((ok, fail) => {
    const u = new URL(url);
    const body = Buffer.from(JSON.stringify(bodyObj));
    const req = https.request({
      hostname: u.hostname, port: u.port || 443, path: u.pathname + u.search, method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json', 'Content-Length': body.length, 'User-Agent': USER_AGENT }, headers),
    }, (res) => {
      const parts = [];
      res.on('data', (c) => parts.push(c));
      res.on('end', () => ok({ status: res.statusCode, body: Buffer.concat(parts).toString('utf8') }));
    });
    req.on('error', fail);
    req.setTimeout(HANDSHAKE_MS, () => req.destroy(new Error('timeout')));
    req.end(body);
  });
}

async function fetchDocInfo(weblink) {
  const r = await httpPostJson(API_EDIT, { 'x-email': 'anonym', public: '/' + weblink, platform: 'desktop_web' }, {
    'Accept': 'application/json, text/plain, */*', 'Accept-Language': 'en-US,en;q=0.9', 'X-Api-Version': '4',
    'Referer': 'https://cloud.mail.ru/public/' + weblink + '?weblink=' + weblink,
  });
  if (r.status !== 200) throw new Error('edit API status ' + r.status);
  let res;
  try { res = JSON.parse(r.body); } catch { throw new Error('edit API bad JSON'); }
  const doc = res.document, ec = res.editorConfig;
  if (!doc || !ec) throw new Error('edit API missing document/editorConfig');
  const api = res.api || '', key = doc.key || '';
  if (!api || !key) throw new Error('edit API missing api/key');
  return {
    token: res.token || '',
    docKey: key,
    wsURL: api.replace(/^https/, 'wss') + '/doc/' + key + '/c/?EIO=4&transport=websocket',
    fileType: doc.fileType || '',
    docURL: doc.url || '',
    docTitle: doc.title || '',
    permissions: (doc.permissions && typeof doc.permissions === 'object') ? doc.permissions : {},
    callbackURL: ec.callbackUrl || '',
    editorUserID: (ec.user && ec.user.id) || '',
  };
}

function buildAuthMessage(info, username) {
  const auth = {
    type: 'auth', docid: info.docKey, documentCallbackUrl: info.callbackURL, token: 'fghhfgsjdgfjs',
    user: { id: info.editorUserID, username, indexUser: -1 },
    editorType: 0, lastOtherSaveTime: -1, block: [], documentFormatSave: 65, view: false, isCloseCoAuthoring: false,
    openCmd: { c: 'open', id: info.docKey, userid: info.editorUserID, format: info.fileType, url: info.docURL,
      title: info.docTitle, lcid: 25, nobase64: true, convertToOrigin: '.pdf.xps.oxps.djvu' },
    lang: 'ru', mode: 'edit', permissions: info.permissions, IsAnonymousUser: false, timezoneOffset: -180,
    coEditingMode: 'fast', jwtOpen: info.token, time: 1000, supportAuthChangesAck: true,
  };
  return '42' + JSON.stringify(['message', auth]);
}

function reconnectBackoff(n) {
  if (n < 1) n = 1;
  let shift = n - 1; if (shift > 5) shift = 5;
  let d = 500 * (1 << shift);
  if (d > 15000) d = 15000;
  return d + crypto.randomInt(0, Math.floor(d / 2) + 1);
}

class MailruChannel {
  /**
   * opts: { link, enabled?, maxReconnect?, ...DEFAULTS overrides }
   * deps: { mux: StreamMux, fetchDoc?(weblink)->info, wsFactory?()->WsClient, log?(msg), onState?(state) }
   */
  constructor(opts, deps) {
    this.opts = Object.assign({}, DEFAULTS, opts);
    this.weblink = normalizeWeblink(opts.link);
    this.mux = deps.mux;
    this.fetchDoc = deps.fetchDoc || fetchDocInfo;
    this.wsFactory = deps.wsFactory || (() => new WsClient());
    this.logFn = deps.log || (() => {});
    this.onState = deps.onState || (() => {});
    this.maxReconnect = opts.maxReconnect || 999999;

    this.running = false;
    this.baseUserID = randUserID();
    this.userCounter = 0;
    this.userID = null;              // kept across reconnects/rotations
    this.session = null;             // active connection
    this.ourIDs = new Set();         // every editor id we authed as (echo filter)
    this.reconnecting = false;
    this.rotating = false;
    this.writerTimer = null;
    this.rotateTimer = null;
    this.lastWriteAt = 0;
    this.state = { status: 'idle', since: nowMs(), connectedAt: 0, reconnects: 0, rotations: 0, lastError: '', sends: 0, recvs: 0 };
  }

  setState(status, err) {
    this.state.status = status;
    this.state.since = nowMs();
    if (err) this.state.lastError = err;
    this.onState(this.snapshot());
  }

  snapshot() {
    const s = this.state;
    return {
      enabled: true, weblink: this.weblink, status: s.status, sinceMs: s.since,
      connectedForSec: s.connectedAt ? Math.floor((nowMs() - s.connectedAt) / 1000) : 0,
      reconnects: s.reconnects, rotations: s.rotations, lastError: s.lastError,
      streams: this.mux ? this.mux.activeStreams() : 0,
      outboxBytes: this.mux ? this.mux.outboxBytes() : 0,
      sends: s.sends, recvs: s.recvs,
    };
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.setState('connecting');
    this.connect(0);
    this.writerTimer = setInterval(() => this.tick(), Math.max(10, this.opts.msgIntervalMs));
    if (this.opts.rotateMs > 0) this.rotateTimer = setInterval(() => this.maybeRotate(), 2000);
  }

  stop() {
    if (!this.running) return;
    this.running = false;
    if (this.writerTimer) clearInterval(this.writerTimer);
    if (this.rotateTimer) clearInterval(this.rotateTimer);
    if (this.session) { try { this.session.ws.close(); } catch {} }
    this.session = null;
    this.setState('stopped');
  }

  async connect(attempt) {
    if (!this.running) return;
    const existing = this.session;
    if (existing) { this.userID = existing.userID; }
    else if (!this.userID) { this.userID = this.baseUserID + String((++this.userCounter) % 1000).padStart(3, '0'); }
    let sess;
    try {
      sess = await this.dialAndAuth(this.userID);
    } catch (e) {
      this.logFn('[M-DOCS] connect failed: ' + e.message);
      this.setState('reconnecting', e.message);
      this.scheduleReconnect(attempt);
      return;
    }
    this.installSession(sess);
    this.readLoop(sess, attempt);
  }

  async dialAndAuth(userID) {
    const info = await this.fetchDoc(this.weblink);
    const ws = this.wsFactory();
    const headers = { 'Accept-Language': 'en-US,en;q=0.9', 'Origin': ORIGIN };
    await ws.connect(info.wsURL, headers);
    const sess = {
      ws, info, userID, active: false, retired: false, authed: false,
      authedResolve: null, sentCount: 0, startAt: nowMs(),
    };
    sess.authedP = new Promise((r) => { sess.authedResolve = r; });
    if (info.editorUserID) this.ourIDs.add(info.editorUserID);
    // Engine.IO/Socket.IO handshake, then editor auth.
    await this.handshake(sess, info.token);
    ws.sendText(buildAuthMessage(info, userID));
    return sess;
  }

  /** Consume the Engine.IO open (0{...}) and the Socket.IO ack (40...), answering any ping. */
  handshake(sess, token) {
    const ws = sess.ws;
    return new Promise((ok, fail) => {
      let stage = 0;   // 0 = await engine open, 1 = await socket.io ack
      const to = setTimeout(() => { cleanup(); fail(new Error('handshake timeout')); }, HANDSHAKE_MS);
      const onText = (t) => {
        if (t === '2') { try { ws.sendText('3'); } catch {} return; }
        if (stage === 0 && t.startsWith('0{')) { stage = 1; try { ws.sendText('40' + JSON.stringify({ token })); } catch (e) { cleanup(); return fail(e); } return; }
        if (stage === 1 && t.startsWith('40')) { cleanup(); return ok(); }
      };
      const onClose = (e) => { cleanup(); fail(e || new Error('closed during handshake')); };
      const cleanup = () => { clearTimeout(to); ws.removeListener('text', onText); ws.removeListener('close', onClose); };
      ws.on('text', onText);
      ws.on('close', onClose);
    });
  }

  installSession(sess) {
    sess.active = true;
    this.session = sess;
    sess.startAt = nowMs();
    this.state.connectedAt = nowMs();
    this.setState('connected');
  }

  readLoop(sess, attempt) {
    const ws = sess.ws;
    const onText = (t) => this.onMessage(sess, t);
    const onClose = (e) => {
      ws.removeListener('text', onText);
      if (sess.retired) { this.logFn('[M-DOCS] retired carrier closed'); return; }
      const stillActive = this.session === sess;
      if (stillActive) this.setState('reconnecting', e && e.message);
      if (stillActive) {
        let next = attempt;
        if (nowMs() - sess.startAt > 15000) next = -1;   // a long-lived connection: reset the backoff
        this.scheduleReconnect(next);
      }
    };
    ws.on('text', onText);
    ws.once('close', onClose);
  }

  onMessage(sess, text) {
    if (text === '2') { try { sess.ws.sendText('3'); } catch {} return; }
    if (text === '3') return;
    if (text.includes('"type":"auth"') && text.includes('"result":1')) { sess.authed = true; sess.authedResolve(); return; }
    if (text.includes('disconnectReason')) { this.logFn('[M-DOCS] server disconnectReason: ' + text.slice(0, 200)); return; }
    if (text.includes('"type":"connectState"')) { try { sess.ws.sendText(UNLOCK_MSG); } catch {} return; }
    if (text.includes('"messages"') && sess.active) {
      for (const pkt of decodeChatData(text, (id) => this.isOwnID(id))) {
        this.state.recvs++;
        this.mux.onPacket(pkt);
      }
    }
  }

  isOwnID(id) { return !!id && this.ourIDs.has(id); }

  tick() {
    if (!this.running) return;
    const sess = this.session;
    if (!sess || !sess.ws || sess.ws.closed) return;
    this.mux.flushAcks();
    if (this.opts.msgIntervalMs > 0 && this.lastWriteAt && nowMs() - this.lastWriteAt < this.opts.msgIntervalMs) return;
    if (sess.ws.bufferedAmount > 4 * this.opts.maxMessageBytes) return;   // let the socket drain
    const msg = this.mux.buildMessage(this.opts.maxMessageBytes);
    if (!msg.length) return;
    try { sess.ws.sendText(encodeChatMessage(msg)); } catch (e) { this.logFn('[M-DOCS] write error: ' + e.message); return; }
    this.lastWriteAt = nowMs();
    this.state.sends++;
    sess.sentCount++;
    if (this.opts.rotateMsgs > 0 && sess.sentCount === this.opts.rotateMsgs) this.maybeRotate(sess);
  }

  maybeRotate(target) {
    if (!this.running || this.rotating) return;
    const sess = target || this.session;
    if (!sess || sess !== this.session) return;
    if (!target && (this.opts.rotateMs <= 0 || nowMs() - sess.startAt < this.opts.rotateMs)) return;
    this.rotating = true;
    this.rotateCarrier(sess).finally(() => { this.rotating = false; });
  }

  async rotateCarrier(old) {
    if (!this.running) return;
    let sess;
    try { sess = await this.dialAndAuth(old.userID); }
    catch (e) { this.logFn('[M-DOCS] rotation failed, keeping current carrier: ' + e.message); return; }
    this.readLoop(sess, 0);
    await Promise.race([sess.authedP, new Promise((r) => setTimeout(r, ROTATE_AUTH_WAIT_MS))]);
    if (!this.running) { sess.retired = true; try { sess.ws.close(); } catch {} return; }
    old.active = false;
    this.installSession(sess);
    old.retired = true;
    try { old.ws.close(); } catch {}
    this.state.rotations++;
  }

  scheduleReconnect(attempt) {
    const next = attempt + 1;
    if (!this.running || next >= this.maxReconnect) return;
    if (this.reconnecting) return;
    this.reconnecting = true;
    const d = reconnectBackoff(next);
    setTimeout(() => {
      this.reconnecting = false;
      if (!this.running) return;
      this.state.reconnects++;
      this.connect(next);
    }, d);
  }
}

// ── public entry ───────────────────────────────────────────────────────────────

/**
 * config.json's optional "mailru" section -> { enabled, link, opts } or null when absent.
 * { "mailru": { "enable": true, "link": "https://cloud.mail.ru/public/.../..." , ...tuning } }
 */
function parseMailruConfig(j) {
  const m = j && typeof j.mailru === 'object' ? j.mailru : null;
  if (!m) return null;
  const enabled = m.enable !== false;   // present and not explicitly disabled
  const link = typeof m.link === 'string' ? m.link.trim() : '';
  if (!enabled || !link) return { enabled: false, link, opts: {} };
  const opts = {};
  for (const k of ['msgIntervalMs', 'rotateMs', 'rotateMsgs', 'maxMessageBytes', 'streamWindow', 'totalWindow', 'outStreamMax', 'outAllMax']) {
    if (typeof m[k] === 'number' && m[k] >= 0) opts[k] = m[k];
  }
  return { enabled: true, link, opts };
}

/**
 * Build and start a Mail.ru channel. deps: { parseTarget, dial, account?, log?, onState?, fetchDoc?, wsFactory? }
 * parseTarget/dial are app.js's shared destination helpers. idleMs sweeps idle streams.
 * Returns { channel, mux, snapshot(), stop() } or null when the config disables it.
 */
function startMailruChannel(cfg, deps, idleMs) {
  const parsed = parseMailruConfig(cfg);
  if (!parsed || !parsed.enabled) return null;
  const mux = new StreamMux({ parseTarget: deps.parseTarget, dial: deps.dial, account: deps.account, log: deps.log, opts: parsed.opts });
  const channel = new MailruChannel(Object.assign({ link: parsed.link }, parsed.opts), {
    mux, fetchDoc: deps.fetchDoc, wsFactory: deps.wsFactory, log: deps.log, onState: deps.onState,
  });
  const sweep = setInterval(() => mux.sweepIdle(idleMs || 300000), Math.max(250, Math.min(5000, Math.floor((idleMs || 300000) / 4))));
  channel.start();
  return {
    channel, mux,
    snapshot: () => channel.snapshot(),
    stop: () => { clearInterval(sweep); channel.stop(); mux.close(); },
  };
}

module.exports = {
  WsClient, StreamMux, MailruChannel,
  encodeFrame, takeFrame, decodeChatData, encodeChatMessage,
  normalizeWeblink, fetchDocInfo, buildAuthMessage, reconnectBackoff,
  parseMailruConfig, startMailruChannel,
  T_OPEN, T_DATA, T_CLOSE, T_OPEN_OK, T_OPEN_ERR, T_ACK, CHAT_TAG, DEFAULTS,
};
