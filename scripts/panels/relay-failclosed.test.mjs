/**
 * A relay reference that cannot be resolved must FAIL CLOSED.
 *
 * Subscription links carry `?proxyip=relay:<id>` instead of the socks5 URL, and
 * the worker resolves that id from its own settings. The dangerous outcome is
 * not "an error" — it is a SILENT one: if the id is unknown (the relay was
 * removed, or the link was forged) and the worker falls back to dialing the
 * target DIRECTLY, the user keeps browsing while believing they are behind
 * their relay. That is worse than being disconnected, so the connection must be
 * closed instead.
 *
 * Run standalone:  node scripts/panels/relay-failclosed.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const enc = new TextEncoder();
const UUID = '78d0b256-8174-444d-9553-84ec9d8f84ee';
const uuidBytes = UUID.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16));
const HOST = 'relay-test.workers.dev';
const RELAY = 'socks5://alice:s3cret@192.0.2.9:1080';
const TARGET = '93.184.216.34'; // what the client is actually trying to reach

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, String(v)); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
class FakeWs {
  constructor() { this.readyState = 1; this.sent = []; this.listeners = {}; this.closed = null; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  emit(type, event) { (this.listeners[type] || []).forEach((fn) => fn(event)); }
  send(data) { this.sent.push(new Uint8Array(data instanceof Uint8Array ? data : new Uint8Array(data))); }
  close(code, reason) { if (this.closed) return; this.closed = { code, reason }; this.readyState = 3; this.emit('close', {}); }
}

/** Fake TCP: «192.0.2.9:1080» answers as a SOCKS5 server, everything else is a plain target. */
function makeSockets(record) {
  return {
    connect(addr) {
      const key = addr.hostname + ':' + addr.port;
      record.dials.push(key);
      const isSocks = key === '192.0.2.9:1080';
      let step = 0;
      let ctrl;
      const readable = new ReadableStream({ start(c) { ctrl = c; } });
      const writable = new WritableStream({
        write(chunk) {
          const b = new Uint8Array(chunk);
          if (isSocks && step === 0) { step = 1; ctrl.enqueue(new Uint8Array([5, 0])); return; }
          if (isSocks && step === 1) {
            step = 2;
            const atyp = b[3];
            let host; let off;
            if (atyp === 1) { host = Array.from(b.subarray(4, 8)).join('.'); off = 8; }
            else if (atyp === 3) { host = new TextDecoder().decode(b.subarray(5, 5 + b[4])); off = 5 + b[4]; }
            else { host = '?'; off = 4; }
            record.socksConnect = { host, port: (b[off] << 8) | b[off + 1] };
            ctrl.enqueue(new Uint8Array([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
            return;
          }
          record.written.push(b);
          ctrl.enqueue(enc.encode('HTTP/1.1 200 OK\r\n\r\nhello'));
        },
        close() { try { ctrl.close(); } catch { /* ignore */ } },
      });
      return { readable, writable, opened: Promise.resolve(), close() { try { ctrl.close(); } catch { /* ignore */ } } };
    },
  };
}

const vlessIp = (ip, port) => new Uint8Array([0, ...uuidBytes, 0, 1, port >> 8, port & 255, 1, ...ip.split('.').map(Number)]);

/** Fresh module per case (settings cache is module-level). */
async function panel(tag, proxyIps) {
  const mod = await import(workerPath + '?rfc=' + tag);
  const T = mod._testing;
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
  const settings = mod._testing.normalizeSettings({ useDefaults: false, includeHost: false, tlsPorts: [443], plainEnabled: false, ips: ['104.16.88.20'], proxyIps });
  await env.CAT_KV.put((T.KV_KEYS && T.KV_KEYS.settings) || 'cat_settings_v1', JSON.stringify(settings));
  return { T, env, settings };
}

/** Drive one connection through the worker exactly like a client would. */
async function connect(T, env, wsPath) {
  const record = { dials: [], written: [], socksConnect: null };
  T.__setSockets(makeSockets(record));
  const ws = new FakeWs();
  const done = T.handleTunnelConnection(ws, env, { masterUuid: UUID, path: wsPath });
  await sleep(20);
  ws.emit('message', { data: vlessIp(TARGET, 443) });
  await sleep(180);
  const closed = ws.closed;
  if (!closed) ws.close(1000, 'test end');
  await Promise.race([done, sleep(1200)]);
  record.closed = closed;
  return record;
}

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

console.log('\nRelay reference — fail closed, never fall back to a direct connection\n');

/* Build a link the way the panel does, so the path carries relay:<id>. */
const P0 = await panel('build', [RELAY]);
const built = P0.T.buildConfigEntries(HOST, P0.env, P0.settings, 'u1', null, {});
const sk = built.entries.filter((e) => e.name.startsWith('🧦'));
ok('a 🧦 entry exists to test with', sk.length > 0, sk.length);
const relayPath = sk[0].wsPath;
ok('its path carries a relay reference, not the credentials',
  /relay%3A[0-9a-f]{16}/i.test(relayPath) && !/s3cret|alice/.test(relayPath), relayPath);
const relayId = (/relay%3A([0-9a-f]{16})/i.exec(relayPath) || [])[1];
ok('the id is parseable', !!relayId, relayId);

/* ---------- case 1: the relay still exists → traffic goes THROUGH it ---------- */
{
  const P = await panel('known', [RELAY]);
  const r = await connect(P.T, P.env, relayPath);
  ok('known relay: the socks handshake happens', !!r.socksConnect, JSON.stringify(r.dials));
  ok('known relay: the client target is reached through the relay',
    r.socksConnect && r.socksConnect.host === TARGET, JSON.stringify(r.socksConnect));
  ok('known relay: the connection is not closed as an error', !r.closed || r.closed.code === 1000, JSON.stringify(r.closed));
}

/* ---------- case 2: the relay was REMOVED from the panel ---------- */
{
  const P = await panel('removed', ['1.2.3.4:443']); // relay no longer in settings
  const r = await connect(P.T, P.env, relayPath);
  ok('removed relay: the worker does NOT dial the target directly (fail closed)',
    !r.dials.includes(TARGET + ':443'), JSON.stringify(r.dials));
  ok('removed relay: the connection is closed, not silently served', !!r.closed, JSON.stringify(r.closed));
  ok('removed relay: nothing is written back to the client', r.written.length === 0, r.written.length);
}

/* ---------- case 3: a forged/unknown id ---------- */
{
  const P = await panel('forged', [RELAY]);
  const forged = relayPath.replace(/relay%3A[0-9a-f]{16}/i, 'relay%3Adeadbeefdeadbeef');
  const r = await connect(P.T, P.env, forged);
  ok('forged id: the worker does NOT dial the target directly',
    !r.dials.includes(TARGET + ':443'), JSON.stringify(r.dials));
  ok('forged id: the connection is closed', !!r.closed, JSON.stringify(r.closed));
}

console.log('\n' + (fail === 0 ? `relay fail-closed: ALL ${pass} PASSED` : `relay fail-closed: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
