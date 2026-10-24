'use strict';
// Tests for the Mail.ru Docs channel (mailru.js): framing, chat (de)serialization, the exit-side
// stream mux (OPEN/DATA/CLOSE/ACK + flow control) against an in-memory destination, and the channel
// lifecycle against a mocked WebSocket + mocked document API. Run: `node --test test/mailru.test.js`.

const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const http = require('node:http');
const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');

const m = require('../mailru.js');
const { encodeFrame, takeFrame, decodeChatData, encodeChatMessage, normalizeWeblink,
        reconnectBackoff, parseMailruConfig, StreamMux, MailruChannel,
        T_OPEN, T_DATA, T_CLOSE, T_OPEN_OK, T_OPEN_ERR, T_ACK, CHAT_TAG } = m;

// ── framing ────────────────────────────────────────────────────────────────────

test('encodeFrame / takeFrame round-trip, including a split buffer', () => {
  const f1 = encodeFrame(T_DATA, 7, Buffer.from('hello'));
  const f2 = encodeFrame(T_OPEN, 9, Buffer.from('example.com:443'));
  const cat = Buffer.concat([f1, f2]);
  const a = takeFrame(cat);
  assert.equal(a.frame.type, T_DATA);
  assert.equal(a.frame.sid, 7);
  assert.equal(a.frame.payload.toString(), 'hello');
  const b = takeFrame(a.rest);
  assert.equal(b.frame.type, T_OPEN);
  assert.equal(b.frame.sid, 9);
  assert.equal(b.frame.payload.toString(), 'example.com:443');
  assert.equal(b.rest.length, 0);
  // A partial frame yields null until the rest arrives.
  assert.equal(takeFrame(cat.subarray(0, 4)), null);
  assert.equal(takeFrame(f1.subarray(0, f1.length - 1)), null);
});

test('empty-payload frame (CLOSE) round-trips', () => {
  const f = encodeFrame(T_CLOSE, 42, null);
  const t = takeFrame(f);
  assert.equal(t.frame.type, T_CLOSE);
  assert.equal(t.frame.sid, 42);
  assert.equal(t.frame.payload.length, 0);
});

// ── chat (de)serialization ───────────────────────────────────────────────────

test('encodeChatMessage / decodeChatData round-trip with echo + human filtering', () => {
  const frames = Buffer.concat([encodeFrame(T_DATA, 1, Buffer.from('abc')), encodeFrame(T_DATA, 1, Buffer.from('xyz'))]);
  const wire = encodeChatMessage(frames);
  assert.ok(wire.startsWith('42["message"'));
  // The server rebroadcasts it as a "messages" array; one entry from a peer, one that is our echo.
  const payload = CHAT_TAG + frames.toString('base64');
  const broadcast = '42' + JSON.stringify(['message', { type: 'message', messages: [
    { message: payload, useridoriginal: 'peer-123' },
    { message: payload, useridoriginal: 'me-000' },         // our own echo: dropped
    { message: 'just a human saying hi', useridoriginal: 'human-1' },  // untagged: dropped
  ] }]);
  const got = decodeChatData(broadcast, (id) => id === 'me-000');
  assert.equal(got.length, 1);
  assert.equal(Buffer.concat(got).toString(), frames.toString());
});

test('decodeChatData ignores non-message / malformed frames', () => {
  assert.deepEqual(decodeChatData('2', () => false), []);
  assert.deepEqual(decodeChatData('garbage', () => false), []);
  assert.deepEqual(decodeChatData('42["message",{"type":"cursor","messages":[]}]', () => false), []);
});

// ── config + helpers ───────────────────────────────────────────────────────────

test('normalizeWeblink strips the public URL prefix', () => {
  assert.equal(normalizeWeblink('https://cloud.mail.ru/public/AbC/dEf/'), 'AbC/dEf');
  assert.equal(normalizeWeblink('  AbC/dEf  '), 'AbC/dEf');   // bare input: only trimmed (as upstream)
});

test('parseMailruConfig: absent, disabled, enabled', () => {
  assert.equal(parseMailruConfig({}), null);
  assert.deepEqual(parseMailruConfig({ mailru: { enable: false, link: 'x' } }), { enabled: false, link: 'x', opts: {} });
  const p = parseMailruConfig({ mailru: { link: 'https://cloud.mail.ru/public/a/b', rotateMs: 1000 } });
  assert.equal(p.enabled, true);
  assert.equal(p.link, 'https://cloud.mail.ru/public/a/b');
  assert.equal(p.opts.rotateMs, 1000);
});

test('reconnectBackoff grows and stays capped', () => {
  for (let n = 1; n < 12; n++) {
    const d = reconnectBackoff(n);
    assert.ok(d >= 0 && d <= 15000 + 7500);
  }
});

// ── exit-side mux against a real loopback destination ──────────────────────────

// An echo TCP server; returns { port, close }.
function echoServer() {
  return new Promise((ok) => {
    const srv = net.createServer((s) => { s.on('error', () => {}); s.pipe(s); });
    srv.listen(0, '127.0.0.1', () => ok({ port: srv.address().port, close: () => srv.close(), srv }));
  });
}

// A test mux driver: collects frames the mux sends (its outbox) and lets the test feed frames in.
function makeMux(extra = {}) {
  const parseTarget = (payload) => {
    let txt = payload.toString('latin1');
    let caps = [];
    const z = txt.indexOf('\0');
    if (z >= 0) { caps = txt.slice(z + 1).split(','); txt = txt.slice(0, z); }
    const cut = txt.lastIndexOf(':');
    const host = txt.slice(0, cut), port = Number(txt.slice(cut + 1));
    if (!host || !port) return { err: 'dns' };
    return { host, port, caps };
  };
  // Dial real TCP (loopback allowed in tests).
  const dial = (st, host, port, live, done) => {
    const sock = net.connect({ host, port });
    st.sock = sock;
    sock.once('error', () => { if (live()) done('refused'); });
    sock.once('connect', () => { if (!live()) { sock.destroy(); return; } done(null, sock); });
  };
  const mux = new StreamMux(Object.assign({ parseTarget, dial }, extra));
  return mux;
}

// Drain the mux outbox into decoded frames (what the client would receive).
function drain(mux) {
  const out = [];
  for (;;) {
    const msg = mux.buildMessage(65536);
    if (!msg.length) break;
    let b = msg;
    for (;;) { const t = takeFrame(b); if (!t) break; out.push(t.frame); b = t.rest; }
  }
  return out;
}

async function waitFor(fn, ms = 2000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (fn()) return true; await new Promise((r) => setTimeout(r, 10)); }
  return false;
}

test('mux OPEN -> OPEN_OK, DATA echoes back, CLOSE', async () => {
  const echo = await echoServer();
  const mux = makeMux();
  try {
    mux.onPacket(encodeFrame(T_OPEN, 1, Buffer.from('127.0.0.1:' + echo.port)));
    assert.ok(await waitFor(() => drain(mux).some((f) => f.type === T_OPEN_OK)));
    mux.onPacket(encodeFrame(T_DATA, 1, Buffer.from('ping')));
    let echoed = null;
    assert.ok(await waitFor(() => {
      for (const f of drain(mux)) if (f.type === T_DATA && f.sid === 1) echoed = f.payload.toString();
      return echoed === 'ping';
    }));
    mux.onPacket(encodeFrame(T_CLOSE, 1, null));
    await waitFor(() => mux.activeStreams() === 0);
    assert.equal(mux.activeStreams(), 0);
  } finally { mux.close(); echo.close(); }
});

test('mux OPEN to a refused port -> OPEN_ERR', async () => {
  const mux = makeMux();
  try {
    // Port 1 on loopback is refused; the test mux dial allows any port.
    mux.onPacket(encodeFrame(T_OPEN, 5, Buffer.from('127.0.0.1:1')));
    let reason = null;
    assert.ok(await waitFor(() => {
      for (const f of drain(mux)) if (f.type === T_OPEN_ERR && f.sid === 5) reason = f.payload.toString();
      return reason !== null;
    }, 3000));
    assert.equal(reason, 'refused');
  } finally { mux.close(); }
});

test('mux flow control: OPEN "\\0fc" -> OPEN_OK "fc" and the exit ACKs written client data', async () => {
  const echo = await echoServer();
  const mux = makeMux();
  try {
    mux.onPacket(encodeFrame(T_OPEN, 3, Buffer.from('127.0.0.1:' + echo.port + '\0fc')));
    let okFc = false;
    assert.ok(await waitFor(() => { for (const f of drain(mux)) if (f.type === T_OPEN_OK && f.sid === 3) okFc = f.payload.toString() === 'fc'; return okFc; }));
    // Send enough client DATA to cross ACK_EVERY (32 KiB) so the exit acks it.
    mux.onPacket(encodeFrame(T_DATA, 3, Buffer.alloc(40000, 0x41)));
    mux.flushAcks();
    let ackVal = -1;
    assert.ok(await waitFor(() => { mux.flushAcks(); for (const f of drain(mux)) if (f.type === T_ACK && f.sid === 3) ackVal = f.payload.readUInt32BE(0); return ackVal >= 40000; }, 3000));
    assert.ok(ackVal >= 40000);
  } finally { mux.close(); echo.close(); }
});

// ── real WsClient against a loopback server (handshake + unmasked frame parse) ──

test('WsClient: upgrades and parses an unmasked server text frame; pongs a ping', async () => {
  const { WsClient } = m;
  let serverSock = null;
  const srv = http.createServer();
  srv.on('upgrade', (req, socket) => {
    serverSock = socket;
    const key = req.headers['sec-websocket-key'];
    const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    // Server text frame "hello" (unmasked, fin).
    const payload = Buffer.from('hello');
    socket.write(Buffer.concat([Buffer.from([0x81, payload.length]), payload]));
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const port = srv.address().port;
  const ws = new WsClient();
  try {
    const got = new Promise((ok) => ws.on('text', ok));
    await ws.connect('ws://127.0.0.1:' + port + '/');
    assert.equal(await got, 'hello');
    // Server ping -> client must pong (opcode 0xA). Read one frame off the server socket.
    const pong = new Promise((ok) => { serverSock.once('data', (d) => ok(d)); });
    serverSock.write(Buffer.from([0x89, 0x00]));   // ping, empty
    const d = await pong;
    assert.equal(d[0] & 0x0f, 0xA);                // a pong frame
    assert.ok((d[1] & 0x80) !== 0);                // client frames are masked
  } finally { ws.close(); srv.close(); }
});

// ── channel lifecycle against a mocked WebSocket + document API ─────────────────

// A fake WsClient: same surface (connect/sendText/close + 'text'/'close' events) wired to a fake
// server that runs the Engine.IO/Socket.IO handshake, accepts the editor auth, and echoes chat
// "message" writes back as a "messages" broadcast from a peer id (so the mux on the other end would
// receive them). Several instances share one "room" so two of them relay to each other.
class FakeRoom {
  constructor() { this.peers = new Set(); }
  join(ws) { this.peers.add(ws); }
  leave(ws) { this.peers.delete(ws); }
  broadcast(fromID, message) {
    for (const ws of this.peers) {
      ws.emit('text', '42' + JSON.stringify(['message', { type: 'message', messages: [{ message, useridoriginal: fromID }] }]));
    }
  }
}

function fakeWsFactory(room, editorID) {
  return () => {
    const ws = new EventEmitter();
    ws.closed = false;
    ws.bufferedAmount = 0;
    ws.connect = async () => {
      room.join(ws);
      setImmediate(() => ws.emit('text', '0{"sid":"engine"}'));   // Engine.IO open
    };
    ws.sendText = (t) => {
      if (ws.closed) throw new Error('closed');
      if (t.startsWith('40')) { setImmediate(() => ws.emit('text', '40{"sid":"socket"}')); return true; }  // Socket.IO ack
      if (t === '3') return true;
      if (t.includes('"type":"auth"')) { setImmediate(() => ws.emit('text', '42["message",{"type":"auth","result":1}]')); return true; }
      // A chat data write: rebroadcast to the room from this connection's editor id.
      const i = t.indexOf('[');
      try {
        const arr = JSON.parse(t.slice(i));
        if (arr[1] && arr[1].type === 'message' && typeof arr[1].message === 'string') room.broadcast(editorID, arr[1].message);
      } catch {}
      return true;
    };
    ws.close = () => { if (ws.closed) return; ws.closed = true; room.leave(ws); ws.emit('close', new Error('local close')); };
    return ws;
  };
}

function fakeDoc(editorID) {
  return async () => ({
    token: 'jwt', docKey: 'KEY', wsURL: 'wss://fake/doc/KEY/c/?EIO=4&transport=websocket',
    fileType: 'docx', docURL: 'u', docTitle: 't', permissions: { edit: true }, callbackURL: 'cb', editorUserID: editorID,
  });
}

test('channel: connects through the mocked WS, serves a stream to a peer', async () => {
  const echo = await echoServer();
  const room = new FakeRoom();

  // The "exit" side: a channel whose mux dials destinations.
  const exitMux = makeMux();
  const exit = new MailruChannel({ link: 'https://cloud.mail.ru/public/a/b', rotateMs: 0, msgIntervalMs: 10 },
    { mux: exitMux, fetchDoc: fakeDoc('exit-id'), wsFactory: fakeWsFactory(room, 'exit-id') });
  exit.start();

  // The "client" side: a bare peer that writes mux frames into the room and reads the exit's replies.
  const clientWs = fakeWsFactory(room, 'client-id')();
  const clientRx = [];
  let clientBuf = Buffer.alloc(0);
  clientWs.on('text', (t) => {
    for (const pkt of decodeChatData(t, (id) => id === 'client-id')) {
      clientBuf = Buffer.concat([clientBuf, pkt]);
      for (;;) { const f = takeFrame(clientBuf); if (!f) break; clientRx.push(f.frame); clientBuf = Buffer.from(f.rest); }
    }
  });
  await clientWs.connect();
  // Minimal client handshake so the room has it joined (text events already flow via EventEmitter).
  clientWs.sendText('40{"token":"jwt"}');

  const sendClientFrame = (buf) => clientWs.sendText(encodeChatMessage(buf));

  try {
    assert.ok(await waitFor(() => exit.state.status === 'connected', 3000), 'exit connected');
    // Client opens a stream to the echo server through the exit.
    sendClientFrame(encodeFrame(T_OPEN, 1, Buffer.from('127.0.0.1:' + echo.port)));
    assert.ok(await waitFor(() => clientRx.some((f) => f.type === T_OPEN_OK && f.sid === 1), 3000), 'got OPEN_OK');
    // Client sends data; the echo server returns it; the exit relays it back as DATA.
    sendClientFrame(encodeFrame(T_DATA, 1, Buffer.from('roundtrip')));
    let got = '';
    assert.ok(await waitFor(() => { for (const f of clientRx) if (f.type === T_DATA && f.sid === 1) got = f.payload.toString(); return got === 'roundtrip'; }, 3000), 'got echo');
    assert.equal(got, 'roundtrip');
    const snap = exit.snapshot();
    assert.equal(snap.status, 'connected');
    assert.ok(snap.sends > 0 && snap.recvs > 0);
  } finally {
    exit.stop(); exitMux.close(); clientWs.close(); echo.close();
  }
});

test('channel: reconnects after the WS drops', async () => {
  const room = new FakeRoom();
  const mux = makeMux();
  const ch = new MailruChannel({ link: 'a/b', rotateMs: 0, msgIntervalMs: 10, maxReconnect: 10 },
    { mux, fetchDoc: fakeDoc('e'), wsFactory: fakeWsFactory(room, 'e') });
  ch.start();
  try {
    assert.ok(await waitFor(() => ch.state.status === 'connected', 3000), 'connected');
    const sess = ch.session;
    sess.ws.close();   // simulate a drop
    assert.ok(await waitFor(() => ch.state.reconnects >= 1 && ch.session && ch.session !== sess && ch.state.status === 'connected', 5000), 'reconnected');
  } finally { ch.stop(); mux.close(); }
});
