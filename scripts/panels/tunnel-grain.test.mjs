/**
 * Regression test for the downstream "grain bundling" loop in pumpTunnel (panel 6.41.0 – 6.69.41):
 * it raced `reader.read()` against a 1 ms silence timer and DROPPED the read promise whenever the
 * timer won, so the next chunk went to an orphaned read and was lost. Any download whose packets
 * arrive more than 1 ms apart (i.e. most real traffic) lost bytes mid-stream — corrupted TLS,
 * stalled pages, failed speed tests — while tiny replies (the old tests) still passed.
 *
 * Drives the real pumpTunnel with a fake remote socket that sends 300 chunks with gaps and checks
 * every byte reaches the WebSocket, in order, after the VLESS response header.
 */
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// CAT_PANEL_WORKER=<file> tests a built artifact (CI runs it on the minified panel too).
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const { _testing: T } = await import(pathToFileURL(workerPath).href);

let failures = 0;
function check(name, ok, extra) {
  console.log((ok ? '✓ ' : '✗ ') + name + (ok || extra === undefined ? '' : ' — ' + extra));
  if (!ok) failures++;
}

class FakeWs {
  constructor() { this.readyState = 1; this.sent = []; this.listeners = {}; this.closedP = new Promise((r) => { this.onClosed = r; }); }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener() {}
  send(data) { this.sent.push(new Uint8Array(data instanceof Uint8Array ? data : new Uint8Array(data))); }
  close() { this.readyState = 3; this.onClosed(); }
}

async function run(chunks, gapMs, sizeOf) {
  let i = 0;
  const expected = [];
  const socket = {
    readable: new ReadableStream({
      async pull(c) {
        if (i >= chunks) { c.close(); return; }
        if (gapMs(i)) await new Promise((r) => setTimeout(r, gapMs(i)));
        const b = new Uint8Array(sizeOf(i));
        for (let k = 0; k < b.length; k++) b[k] = (i * 7 + k) & 255;
        expected.push(b); i++;
        c.enqueue(b);
      },
    }),
    writable: new WritableStream({ write() {} }),
    close() {},
  };
  // client side stays open until the remote finishes (like a real download)
  let clientCtl;
  const clientReadable = new ReadableStream({ start(c) { clientCtl = c; } });
  const ws = new FakeWs();
  const header = new Uint8Array([0, 0]);
  const done = T.pumpTunnel(ws, clientReadable, socket, header, null, null);
  done.catch(() => {});
  // the panel closes the WebSocket once the remote side has finished sending
  await Promise.race([ws.closedP, new Promise((r) => setTimeout(r, 20_000))]);
  try { clientCtl.close(); } catch (_) { /* closed */ }
  const got = concat(ws.sent);
  const want = concat([header, ...expected]);
  return { got, want };
}
function concat(list) {
  const n = list.reduce((s, b) => s + b.byteLength, 0);
  const out = new Uint8Array(n); let o = 0;
  for (const b of list) { out.set(b, o); o += b.byteLength; }
  return out;
}
function same(a, b) { if (a.byteLength !== b.byteLength) return false; for (let k = 0; k < a.length; k++) if (a[k] !== b[k]) return false; return true; }

{
  const { got, want } = await run(300, (i) => (i % 3 === 0 ? 4 : 0), () => 1400);
  check('gapped download: every byte arrives in order (' + got.byteLength + '/' + want.byteLength + ')', same(got, want));
}
{
  const { got, want } = await run(120, () => 3, (i) => 200 + (i % 50) * 31);
  check('slow trickle (3 ms gaps, odd sizes): nothing lost (' + got.byteLength + '/' + want.byteLength + ')', same(got, want));
}
{
  const { got, want } = await run(400, () => 0, () => 16 * 1024);
  check('burst (no gaps, 6.4 MB): nothing lost, bundled ≤128 KB (' + got.byteLength + '/' + want.byteLength + ')', same(got, want));
}

{
  // Backpressure: while the client is slow (ws.bufferedAmount > 2 MB) the panel must stop reading the
  // remote instead of buffering everything; it resumes when the buffer drains. Workers (no
  // bufferedAmount) are unaffected — covered by the cases above.
  let pulls = 0;
  let total = 0;
  const socket = {
    readable: new ReadableStream({
      pull(c) { if (pulls >= 200) { c.close(); return; } pulls++; c.enqueue(new Uint8Array(64 * 1024)); },
    }, { highWaterMark: 0 }),
    writable: new WritableStream({ write() {} }),
    close() {},
  };
  const ws = new FakeWs();
  ws.slow = true;
  Object.defineProperty(ws, 'bufferedAmount', { get() { return ws.slow ? 3 * 1024 * 1024 : 0; } });
  const origSend = ws.send.bind(ws);
  ws.send = (d) => { total += d.byteLength || d.length || 0; origSend(d); };
  const clientReadable = new ReadableStream({ start() {} });
  T.pumpTunnel(ws, clientReadable, socket, new Uint8Array([0, 0]), null, null).catch(() => {});
  await new Promise((r) => setTimeout(r, 300));
  const pullsWhileSlow = pulls;
  check('slow client: remote reading pauses (' + pullsWhileSlow + ' chunk(s) read while 3 MB was queued)', pullsWhileSlow <= 3);
  ws.slow = false;
  await Promise.race([ws.closedP, new Promise((r) => setTimeout(r, 10_000))]);
  check('slow client: resumes and delivers everything after the buffer drains (' + pulls + '/200 chunks)', pulls === 200 && total === 2 + 200 * 64 * 1024);
}

if (failures) { console.error(failures + ' grain test(s) failed'); process.exit(1); }
console.log('tunnel grain tests passed');
process.exit(0);
