'use strict';
// Self-contained tests for app.js: `node --test test/` (or `node test/server.test.js`).
// Spawns real server processes on random ports with temp config/data dirs.

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const net = require('node:net');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const APP = path.join(__dirname, '..', 'app.js');
const PERSONAL = 'personal-test-token-123456';
const DEMO = 'demo-test-token-123456';
const procs = [];

async function startServer(config, env = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'of2-'));
  const cfgPath = path.join(dir, 'config.json');
  fs.writeFileSync(cfgPath, JSON.stringify(config));
  const p = spawn(process.execPath, [APP], {
    env: { ...process.env, PORT: '0', OF_CONFIG: cfgPath, OF_DATA_DIR: path.join(dir, 'data'), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  procs.push(p);
  let out = '';
  p.stderr.on('data', (d) => process.stderr.write('[server] ' + d));
  const port = await new Promise((ok, fail) => {
    const t = setTimeout(() => fail(new Error('server did not start: ' + out)), 5000);
    p.stdout.on('data', (d) => {
      out += d;
      const m = /listening on (\d+)/.exec(out);
      if (m) { clearTimeout(t); ok(Number(m[1])); }
    });
  });
  p.logs = () => out;
  return { port, proc: p, dir, base: `http://127.0.0.1:${port}/n/` };
}

test.after(() => { for (const p of procs) p.kill(); });

function url(srv, params) {
  return srv.base + '?' + new URLSearchParams({ v: '2', ...params }).toString();
}

function sid() { return crypto.randomBytes(8).toString('hex'); }

/** Plain request; returns { status, headers, body(Buffer) }. */
function req(u, { method = 'GET', body } = {}) {
  return new Promise((ok, fail) => {
    const r = http.request(u, { method, agent: false }, (res) => {
      const parts = [];
      res.on('data', (c) => parts.push(c));
      res.on('end', () => ok({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
      res.on('error', fail);
    });
    r.on('error', fail);
    r.end(body);
  });
}

/**
 * Down poll that reads until `until(streamBytesAfterPreamble)` is true, then cuts the request.
 * Resolves { status, headers, preamble, body } where body = bytes after the preamble received.
 */
function down(srv, params, until, timeoutMs = 8000) {
  return new Promise((ok, fail) => {
    const r = http.request(url(srv, { r: 'down', ...params }), { agent: false }, (res) => {
      if (res.statusCode !== 200) {
        const parts = [];
        res.on('data', (c) => parts.push(c));
        res.on('end', () => ok({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
        return;
      }
      let all = Buffer.alloc(0);
      let finished = false;
      const done = (cut) => {
        if (finished) return;
        finished = true;
        clearTimeout(t);
        if (cut) r.destroy();
        ok({ status: 200, headers: res.headers, preamble: all.subarray(0, 4096), body: all.subarray(4096) });
      };
      const t = setTimeout(() => done(true), timeoutMs);
      res.on('data', (c) => {
        all = Buffer.concat([all, c]);
        if (all.length >= 4096 && until && until(all.subarray(4096))) done(true);
      });
      res.on('end', () => done(false));
      res.on('error', () => done(false));
    });
    r.on('error', (e) => { if (e.code !== 'ECONNRESET') fail(e); });
    r.end();
  });
}

function up(srv, s, off, body, k = PERSONAL) {
  return req(url(srv, { k, s, r: 'up', off: String(off) }), { method: 'POST', body });
}

function frame(type, id, payload = Buffer.alloc(0)) {
  const p = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const h = Buffer.alloc(9);
  h[0] = type; h.writeUInt32BE(id >>> 0, 1); h.writeUInt32BE(p.length, 5);
  return Buffer.concat([h, p]);
}

function parseFrames(buf) {
  const frames = [];
  let i = 0;
  while (buf.length - i >= 9) {
    const len = buf.readUInt32BE(i + 5);
    if (buf.length - i < 9 + len) break;
    frames.push({ type: buf[i], id: buf.readUInt32BE(i + 1), payload: buf.subarray(i + 9, i + 9 + len) });
    i += 9 + len;
  }
  return { frames, rest: buf.length - i };
}

/** Real frames (pads dropped). */
function real(buf) { return parseFrames(buf).frames.filter((f) => f.id !== 0xFFFFFFFF); }
function dataOf(buf, id) { return Buffer.concat(real(buf).filter((f) => f.type === 2 && f.id === id).map((f) => f.payload)); }
const has = (type, id) => (b) => real(b).some((f) => f.type === type && f.id === id);

async function echoServer() {
  const st = { conns: 0 };
  const srv = net.createServer((s) => { st.conns++; s.on('error', () => {}); s.pipe(s); });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  st.port = srv.address().port;
  st.close = () => srv.close();
  return st;
}

/** Collect the whole down byte stream of a session until `until(stream)`, re-polling with from=received. */
async function collect(srv, s, stream, until, maxPolls = 200) {
  for (let i = 0; i < maxPolls && !until(stream); i++) {
    const r = await down(srv, { k: PERSONAL, s, from: String(stream.length) }, until && ((b) => until(Buffer.concat([stream, b]))), 3000);
    assert.equal(r.status, 200);
    assert.equal(r.preamble.toString('latin1').slice(0, 4 + String(stream.length).length), 'OF2 ' + stream.length);
    stream = Buffer.concat([stream, r.body]);
  }
  return stream;
}

let echo;
let main;       // private targets allowed (test env), extra port allowed
test.before(async () => {
  echo = await echoServer();
  main = await startServer(
    { personal: { p1: PERSONAL }, demo: { token: DEMO, max: 5, seconds: 3600, userMb: 2048, dailyMb: 25600, until: 0 } },
    { OF_ALLOW_PRIVATE: '1', OF_ALLOW_PORTS: String(echo.port), OF_RUN_MS: '4000' },
  );
});
test.after(() => echo && echo.close());

test('unknown token -> 404 "no" with X-OF-Proto: 2', async () => {
  const r = await req(url(main, { k: 'wrong-token-xxxxxxxx', s: sid(), r: 'down', from: '0' }));
  assert.equal(r.status, 404);
  assert.equal(r.body.toString(), 'no');
  assert.equal(r.headers['x-of-proto'], '2');
  const r2 = await req(main.base);   // no query at all
  assert.equal(r2.status, 404);
});

test('demo admission: noclient, busy (max=0)', async () => {
  const srv = await startServer({ demo: { token: DEMO, max: 0, seconds: 3600, userMb: 2048, dailyMb: 25600, until: 0 } });
  let r = await req(url(srv, { k: DEMO, s: sid(), r: 'down', from: '0' }));
  assert.equal(r.status, 503);
  assert.equal(r.headers['x-of-reason'], 'noclient');
  assert.equal(r.headers['x-of-proto'], '2');
  r = await req(url(srv, { k: DEMO, s: sid(), c: 'short', r: 'down', from: '0' }));
  assert.equal(r.headers['x-of-reason'], 'noclient');
  r = await req(url(srv, { k: DEMO, s: sid(), c: 'install-12345678', r: 'down', from: '0' }));
  assert.equal(r.status, 503);
  assert.equal(r.headers['x-of-reason'], 'busy');
});

test('demo admission: expired (seconds=0), ended (until in the past)', async () => {
  let srv = await startServer({ demo: { token: DEMO, max: 5, seconds: 0, userMb: 2048, dailyMb: 25600, until: 0 } });
  let r = await req(url(srv, { k: DEMO, s: sid(), c: 'install-12345678', r: 'down', from: '0' }));
  assert.equal(r.status, 503);
  assert.equal(r.headers['x-of-reason'], 'expired');
  srv = await startServer({ demo: { token: DEMO, max: 5, seconds: 3600, userMb: 2048, dailyMb: 25600, until: 1000 } });
  r = await req(url(srv, { k: DEMO, s: sid(), c: 'install-12345678', r: 'down', from: '0' }));
  assert.equal(r.headers['x-of-reason'], 'ended');
});

test('demo admitted: X-OF-Left headers; same install replaces its old session', async () => {
  const s1 = sid(), s2 = sid();
  const c = 'install-' + sid();
  const r1 = await down(main, { k: DEMO, s: s1, c, from: '0' }, () => true);
  assert.equal(r1.status, 200);
  assert.ok(Number(r1.headers['x-of-left']) > 3500);
  assert.equal(r1.headers['x-of-left-mb'], '2048');
  const r2 = await down(main, { k: DEMO, s: s2, c, from: '0' }, () => true);
  assert.equal(r2.status, 200);
  const r3 = await up(main, s1, 0, frame(3, 1), DEMO);
  assert.equal(r3.status, 410);      // replaced
});

test('session create: from=0, exact 4096-byte preamble; 410 for unknown session with from>0', async () => {
  const s = sid();
  const r = await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  assert.equal(r.status, 200);
  assert.equal(r.headers['content-type'], 'application/octet-stream');
  assert.equal(r.headers['x-accel-buffering'], 'no');
  assert.equal(r.headers['cache-control'], 'no-store');
  assert.equal(r.headers['x-of-proto'], '2');
  const exp = Buffer.alloc(4096, 0x20); exp.write('OF2 0'); exp[4095] = 0x0a;
  assert.equal(r.preamble.length, 4096);
  assert.ok(r.preamble.equals(exp));
  const g = await req(url(main, { k: PERSONAL, s: sid(), r: 'down', from: '5' }));
  assert.equal(g.status, 410);
  assert.equal(g.body.toString(), 'gone');
  const u = await up(main, sid(), 0, frame(3, 1));
  assert.equal(u.status, 410);
  // resume offset beyond produced -> 410
  const bad = await req(url(main, { k: PERSONAL, s, r: 'down', from: '999999' }));
  assert.equal(bad.status, 410);
});

test('OPEN policy: port, ipv6 (and private targets blocked without the test env)', async () => {
  const strict = await startServer({ personal: { p1: PERSONAL } });
  const s = sid();
  const first = await down(strict, { k: PERSONAL, s, from: '0' }, () => true);
  assert.equal(first.status, 200);
  const body = Buffer.concat([
    frame(1, 1, '127.0.0.1:443'), frame(1, 2, '10.1.2.3:80\0fc'), frame(1, 3, 'localhost:80'),
    frame(1, 4, 'example.com:22'), frame(1, 5, '[::1]:443'), frame(1, 6, '2001:db8::1:443'),
    frame(1, 7, `127.0.0.1:${echo.port}`),
  ]);
  const u = await up(strict, s, 0, body);
  assert.equal(u.status, 200);
  assert.equal(u.body.toString(), 'ok ' + body.length);
  const want = { 1: 'blocked', 2: 'blocked', 3: 'blocked', 4: 'port', 5: 'ipv6', 6: 'ipv6', 7: 'port' };
  const allErr = (b) => Object.keys(want).every((id) => has(5, Number(id))(b));
  const r = await down(strict, { k: PERSONAL, s, from: '0' }, allErr);
  for (const f of real(r.body)) {
    assert.equal(f.type, 5, 'only OPEN_ERR expected');
    assert.equal(f.payload.toString(), want[f.id]);
  }
  assert.ok(allErr(r.body));
  assert.equal(echo.conns, 0);
});

test('OPEN to local echo (test env), padding to 4096, DATA round trip, CLOSE', async () => {
  const s = sid();
  await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  const before = echo.conns;
  const open = frame(1, 9, `127.0.0.1:${echo.port}\0fc`);
  let u = await up(main, s, 0, open);
  assert.equal(u.body.toString(), 'ok ' + open.length);
  // wait for OPEN_OK and the pad that aligns the burst to 4096
  let stream = await collect(main, s, Buffer.alloc(0), (b) => has(4, 9)(b) && b.length % 4096 === 0);
  assert.equal(stream.length % 4096, 0);
  const msg = Buffer.from('hello openflux v2');
  const d = frame(2, 9, msg);
  u = await up(main, s, open.length, d);
  assert.equal(u.body.toString(), 'ok ' + (open.length + d.length));
  stream = await collect(main, s, stream, (b) => dataOf(b, 9).length >= msg.length);
  assert.ok(dataOf(stream, 9).equals(msg));
  assert.equal(echo.conns, before + 1);
  // client CLOSE: no CLOSE echoed back by the server; further DATA ignored
  u = await up(main, s, open.length + d.length, frame(3, 9));
  assert.equal(u.status, 200);
});

test('re-sent OPEN does not redial (pending and connected)', async () => {
  const s = sid();
  await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  const before = echo.conns;
  const o = frame(1, 5, `127.0.0.1:${echo.port}`);
  const b1 = Buffer.concat([o, o]);                // second one while pending -> ignored
  await up(main, s, 0, b1);
  let stream = await collect(main, s, Buffer.alloc(0), has(4, 5));
  await up(main, s, b1.length, o);                 // connected -> OPEN_OK again
  stream = await collect(main, s, stream, (b) => real(b).filter((f) => f.type === 4 && f.id === 5).length >= 2);
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(real(stream).filter((f) => f.type === 4 && f.id === 5).length, 2);
  assert.equal(echo.conns, before + 1);
});

test('up offsets: duplicate prefix skipped, gap -> 409 need X', async () => {
  const s = sid();
  await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  const o = frame(1, 3, `127.0.0.1:${echo.port}`);
  const d1 = frame(2, 3, 'AAAA');
  const d2 = frame(2, 3, 'BBBB');
  let u = await up(main, s, 0, Buffer.concat([o, d1]));
  const n1 = o.length + d1.length;
  assert.equal(u.body.toString(), 'ok ' + n1);
  u = await up(main, s, 0, Buffer.concat([o, d1]));          // full duplicate
  assert.equal(u.status, 200);
  assert.equal(u.body.toString(), 'ok ' + n1);
  u = await up(main, s, n1 + 10, d2);                          // gap
  assert.equal(u.status, 409);
  assert.equal(u.body.toString(), 'need ' + n1);
  u = await up(main, s, n1 - 3, Buffer.concat([d1.subarray(d1.length - 3), d2]));   // overlap
  assert.equal(u.body.toString(), 'ok ' + (n1 + d2.length));
  const stream = await collect(main, s, Buffer.alloc(0), (b) => dataOf(b, 3).length >= 8);
  await new Promise((r) => setTimeout(r, 200));
  const more = await down(main, { k: PERSONAL, s, from: String(stream.length) }, null, 600);
  assert.equal(Buffer.concat([dataOf(stream, 3), dataOf(more.body, 3)]).toString(), 'AAAABBBB');
});

test('resume: cut the down mid-stream, re-poll from=received gets exactly the missing bytes', async () => {
  const s = sid();
  await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  const o = frame(1, 11, `127.0.0.1:${echo.port}`);
  await up(main, s, 0, o);
  const payload = crypto.randomBytes(600 * 1024);
  let off = o.length;
  for (let i = 0; i < payload.length; i += 60000) {
    const f = frame(2, 11, payload.subarray(i, i + 60000));
    const u = await up(main, s, off, f);
    off += f.length;
    assert.equal(u.body.toString(), 'ok ' + off);
  }
  await new Promise((r) => setTimeout(r, 300));      // let the echo come back and be retained
  // first poll: cut after ~100 KB (mid-frame, almost surely)
  const a = await down(main, { k: PERSONAL, s, from: '0' }, (b) => b.length >= 100000);
  assert.ok(a.body.length >= 100000);
  // second poll with from=0 again (nothing acknowledged) must replay identical bytes
  const again = await down(main, { k: PERSONAL, s, from: '0' }, (b) => b.length >= a.body.length);
  assert.ok(again.body.subarray(0, a.body.length).equals(a.body));
  // continue from the cut offset
  const b = await down(main, { k: PERSONAL, s, from: String(a.body.length) }, () => true);
  assert.ok(b.preamble.toString().startsWith('OF2 ' + a.body.length + ' '));
  const full = await collect(main, s, a.body, (x) => dataOf(x, 11).length >= payload.length);
  assert.ok(dataOf(full, 11).equals(payload));
  // and the re-polled bytes are exactly the continuation the first (replayed) poll had seen
  const overlap = Math.min(again.body.length, full.length);
  assert.ok(full.subarray(0, overlap).equals(again.body.subarray(0, overlap)));
  // below-acked offset is gone now
  const g = await req(url(main, { k: PERSONAL, s, r: 'down', from: '0' }));
  assert.equal(g.status, 410);
});

test('413 for bodies over 4 MiB', async () => {
  const s = sid();
  await down(main, { k: PERSONAL, s, from: '0' }, () => true);
  const r = await up(main, s, 0, Buffer.alloc(4 * 1024 * 1024 + 1));
  assert.equal(r.status, 413);
});

test('retention cap backpressure (small cap)', async () => {
  const CAP = 65536;
  const srv = await startServer({ personal: { p1: PERSONAL } },
    { OF_ALLOW_PRIVATE: '1', OF_ALLOW_PORTS: String(echo.port), OF_RETAIN_CAP: String(CAP), OF_RUN_MS: '3000' });
  const s = sid();
  await down(srv, { k: PERSONAL, s, from: '0' }, () => true);
  const o = frame(1, 1, `127.0.0.1:${echo.port}`);
  await up(srv, s, 0, o);
  const payload = crypto.randomBytes(512 * 1024);
  let off = o.length;
  for (let i = 0; i < payload.length; i += 32768) {
    const f = frame(2, 1, payload.subarray(i, i + 32768));
    const u = await up(srv, s, off, f);
    off += f.length;
    assert.equal(u.status, 200);
  }
  await new Promise((r) => setTimeout(r, 1000));
  // Nothing acknowledged yet: the server must have stopped near the cap (+ at most one read chunk + pads).
  const first = await down(srv, { k: PERSONAL, s, from: '0' }, null, 2500);
  assert.ok(first.body.length > 0);
  assert.ok(first.body.length <= CAP + 65536 + 2 * 4096 + 9, 'retained ' + first.body.length);
  assert.ok(dataOf(first.body, 1).length < payload.length);
  // Acknowledging by re-polling lets everything through.
  const full = await collect(srv, s, first.body, (x) => dataOf(x, 1).length >= payload.length, 500);
  assert.ok(dataOf(full, 1).equals(payload));
});

test('logs never contain tokens', async () => {
  for (const s of [main]) {
    const l = s.proc.logs();
    assert.ok(!l.includes(PERSONAL) && !l.includes(DEMO));
  }
});

test('idle keepalive: a 4096-byte pad frame every ~5 s', async () => {
  const srv = await startServer({ personal: { p1: PERSONAL } });
  const s = sid();
  const t0 = Date.now();
  const r = await down(srv, { k: PERSONAL, s, from: '0' }, (b) => b.length >= 4096, 8000);
  assert.ok(Date.now() - t0 >= 4500, 'pad came too early');
  const f = parseFrames(r.body.subarray(0, 4096));
  assert.equal(f.frames.length, 1);
  assert.equal(f.frames[0].type, 2);
  assert.equal(f.frames[0].id, 0xFFFFFFFF);
  assert.equal(f.frames[0].payload.length, 4096 - 9);
});

test('demo limit hit mid-session: session destroyed, next down/up gets 503 with the reason', async () => {
  const srv = await startServer(
    { demo: { token: DEMO, max: 5, seconds: 3600, userMb: 0.05, dailyMb: 25600, until: 0 } },
    { OF_ALLOW_PRIVATE: '1', OF_ALLOW_PORTS: String(echo.port) });
  const s = sid(), c = 'install-' + sid();
  const r = await down(srv, { k: DEMO, s, c, from: '0' }, () => true);
  assert.equal(r.status, 200);
  assert.equal(r.headers['x-of-left-mb'], '0');
  const body = Buffer.concat([frame(1, 1, `127.0.0.1:${echo.port}`), frame(2, 1, crypto.randomBytes(100000))]);
  await up(srv, s, 0, body, DEMO);
  await new Promise((ok) => setTimeout(ok, 500));
  const u = await up(srv, s, body.length, frame(3, 1), DEMO);
  assert.equal(u.status, 503);
  assert.equal(u.headers['x-of-reason'], 'expired');
  const d = await req(url(srv, { k: DEMO, s, c, r: 'down', from: '0' }));
  assert.equal(d.status, 503);
  assert.equal(d.headers['x-of-reason'], 'expired');
});

test('down rotation: a newer down takes over (old ends cleanly), acks, and resumes paused sockets', async () => {
  const CAP = 65536;
  const srv = await startServer({ personal: { p1: PERSONAL } },
    { OF_ALLOW_PRIVATE: '1', OF_ALLOW_PORTS: String(echo.port), OF_RETAIN_CAP: String(CAP) });
  const s = sid();
  await down(srv, { k: PERSONAL, s, from: '0' }, () => true);
  const o = frame(1, 1, `127.0.0.1:${echo.port}`);
  await up(srv, s, 0, o);
  const payload = crypto.randomBytes(512 * 1024);
  let off = o.length;
  for (let i = 0; i < payload.length; i += 32768) {
    const f = frame(2, 1, payload.subarray(i, i + 32768));
    assert.equal((await up(srv, s, off, f)).status, 200);
    off += f.length;
  }
  await new Promise((r) => setTimeout(r, 800));       // retained > cap: destination paused

  // Like the bridge: read a poll, and after every 16 KiB (< cap/2, so the server has not ended it
  // itself) open the next poll with from=received while the old one is still open.
  let stream = Buffer.alloc(0);
  let polls = 0, cleanEnds = 0, takeovers = 0;
  const rotateEvery = 16384;
  await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error('timeout; got ' + dataOf(stream, 1).length)), 30000);
    const poll = () => {
      polls++;
      const from = stream.length;
      let pre = Buffer.alloc(0), mine = 0, active = true, replaced = false;
      const r = http.request(url(srv, { k: PERSONAL, s, r: 'down', from: String(from) }), { agent: false }, (res) => {
        if (res.statusCode !== 200) return reject(new Error('status ' + res.statusCode));
        res.on('data', (c) => {
          if (!active) return;                                   // bytes after takeover are ignored
          if (pre.length < 4096) {
            const need = 4096 - pre.length;
            pre = Buffer.concat([pre, c.subarray(0, need)]);
            if (pre.length === 4096) assert.ok(pre.toString('latin1').startsWith('OF2 ' + from + ' '));
            c = c.subarray(need);
          }
          if (!c.length) return;
          stream = Buffer.concat([stream, c]);
          mine += c.length;
          if (dataOf(stream, 1).length >= payload.length) { active = false; clearTimeout(deadline); r.destroy(); return resolve(); }
          if (mine >= rotateEvery) { active = false; replaced = true; takeovers++; poll(); }
        });
        res.on('end', () => {
          if (replaced) cleanEnds++;                            // old response ended cleanly by the server
          if (active) { active = false; poll(); }               // server-side end (cap/2 or run time)
        });
        res.on('error', () => {});
      });
      r.on('error', () => {});
      r.end();
    };
    poll();
  });
  await new Promise((r) => setTimeout(r, 300));
  assert.ok(dataOf(stream, 1).equals(payload));
  assert.ok(takeovers >= 5, 'takeovers ' + takeovers);
  assert.ok(cleanEnds >= takeovers - 1, `clean ends ${cleanEnds} / ${takeovers}`);
});
