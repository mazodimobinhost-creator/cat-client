/**
 * Generated config → the path the CLIENT sends → the order the WORKER dials. (proxyip-sub.test.mjs checks the
 * subscription text; this checks that the text actually steers the data plane.)
 *
 * Why: a «🎯 per-ProxyIP» config only exists to pin ONE relay for ITS connection (`?proxyip=` on the WS path),
 * and a «🧦» config to exit through the owner's own SOCKS5 proxy. Clash, sing-box and Xray used to put the
 * generic path on those entries, so every «🎯» config behaved identically; and a SOCKS5 line pasted with the app
 * scanner's «#SOCKS5 <ip>» remark never parsed at all. Both are invisible in the subscription text, so this test
 * drives handleTunnelConnection with the very path taken from each generated link, with a fake TCP layer that
 * also speaks SOCKS5 (greeting, CONNECT, data).
 *
 * Usage: node scripts/panels/proxyip-dataplane.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 260) : '')); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const enc = new TextEncoder();
const dec = (v) => { try { return decodeURIComponent(v || ''); } catch { return String(v || ''); } };

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
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

/** Fake TCP layer. Hosts in `socks` (host:port) behave like a SOCKS5 server; `refuse` hosts reject the dial. */
function makeSockets(record, { socks = new Set(), refuse = new Set() } = {}) {
  return {
    connect(addr) {
      const key = addr.hostname + ':' + addr.port;
      record.dials.push(key);
      if (refuse.has(addr.hostname) || refuse.has(key)) throw new Error('ECONNREFUSED');
      const isSocks = socks.has(key);
      let step = 0;
      let ctrl;
      const readable = new ReadableStream({ start(c) { ctrl = c; } });
      const writable = new WritableStream({
        write(chunk) {
          const b = new Uint8Array(chunk);
          if (isSocks && step === 0) { step = 1; ctrl.enqueue(new Uint8Array([5, 0])); return; } // greeting → no auth
          if (isSocks && step === 1) { // CONNECT 05 01 00 atyp addr port
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

const UUID = '78d0b256-8174-444d-9553-84ec9d8f84ee';
const uuidBytes = UUID.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16));
const HOST = 'edge-pedre.catclient-0ltgml5i.workers.dev';
const ipBytes = (ip) => ip.split('.').map(Number);
const vlessIp = (ip, port, payload = []) => new Uint8Array([0, ...uuidBytes, 0, 1, port >> 8, port & 255, 1, ...ipBytes(ip), ...payload]);
const vlessDomain = (host, port, payload = []) => { const d = Array.from(enc.encode(host)); return new Uint8Array([0, ...uuidBytes, 0, 1, port >> 8, port & 255, 2, d.length, ...d, ...payload]); };
const trojanIp = (hash, ip, port, payload = []) => new Uint8Array([...enc.encode(hash), 13, 10, 1, 1, ...ipBytes(ip), port >> 8, port & 255, 13, 10, ...payload]);

/** A fresh panel = a fresh module instance (settings cache AND relay cooldowns are module-level). */
async function panel(tag, settings) {
  const mod = await import(pathToFileURL(workerPath).href + '?dp=' + tag);
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
  const call = (p, method = 'GET', body) => mod.default.fetch(
    new Request('https://' + HOST + p, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body && JSON.stringify(body) }),
    env, { waitUntil() {} });
  await call('/api/ips', 'POST', { ips: ['104.16.88.20'], source: 'manual' });
  await call('/api/settings', 'PUT', settings);
  return { mod, T: mod._testing, env, call };
}
const maybeB64 = (t) => (/^(vless|trojan):\/\//.test(t.trim()) ? t : Buffer.from(t.trim(), 'base64').toString('utf8'));
const links = async (P, q = '?limit=40') => maybeB64(await (await P.call('/sub/' + UUID + q)).text()).split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l));
/** What a client does with a share link: URL-decode the `path` parameter ONCE and send it as the request target. */
const clientPath = (link) => dec((/[?&]path=([^&#]*)/.exec(link.split('#')[0]) || [])[1]);
const nameOf = (link) => dec(link.split('#').slice(1).join('#'));
const pick = (ls, proto, needle) => ls.find((l) => l.startsWith(proto + '://') && nameOf(l).includes(needle));

async function run(P, wsPath, frame, opts) {
  const record = { dials: [], written: [], socksConnect: null };
  P.T.__setSockets(makeSockets(record, opts));
  const ws = new FakeWs();
  const done = P.T.handleTunnelConnection(ws, P.env, { masterUuid: UUID, path: wsPath });
  await sleep(25);
  ws.emit('message', { data: frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength) });
  await sleep(150);
  ws.close(1000, 'done');
  await Promise.race([done, sleep(1500)]);
  return record;
}

const A = '203.0.113.10';
const B = '203.0.113.11:8443';
const C = '198.51.100.7';
const CF_TARGET = ['104.16.5.5', 443]; // isCloudflareIp ⇒ the destination is itself behind Cloudflare ⇒ a ProxyIP is needed
const SETTINGS = { proxyIps: [A, B, C] };

// ── 1) 🎯 pins ONE relay for ITS connection (links) ────────────────────────────
{ const P = await panel('base', SETTINGS); const ls = await links(P);
  const plain = ls.find((l) => !/🎯|🧦/.test(nameOf(l)) && l.startsWith('vless://'));
  const base = await run(P, clientPath(plain), vlessIp(...CF_TARGET));
  check('baseline: a plain config to a Cloudflare-hosted target dials the FIRST relay of the panel list', base.dials[0] === A + ':443', base.dials.join(','));
  const pinB = pick(ls, 'vless', '203.0.113.11:8443');
  check('the 🎯 link for ' + B + ' carries a real pin in its client path', /[?&]proxyip=/.test(clientPath(pinB)), clientPath(pinB)); }
{ const P = await panel('vless-pin', SETTINGS); const ls = await links(P);
  const r = await run(P, clientPath(pick(ls, 'vless', '203.0.113.11:8443')), vlessIp(...CF_TARGET));
  check('VLESS 🎯 ' + B + ': the worker dials THAT relay first (host AND port from the pin)', r.dials[0] === '203.0.113.11:8443', r.dials.join(',')); }
{ const P = await panel('trojan-pin', SETTINGS); const ls = await links(P);
  const hash = await P.T.trojanHash(UUID);
  const r = await run(P, clientPath(pick(ls, 'trojan', C)), trojanIp(hash, ...CF_TARGET));
  check('Trojan 🎯 ' + C + ': the worker dials that relay first (default port = the target port)', r.dials[0] === C + ':443', r.dials.join(',')); }
{ const P = await panel('fallback', SETTINGS); const ls = await links(P);
  const r = await run(P, clientPath(pick(ls, 'vless', '203.0.113.11:8443')), vlessIp(...CF_TARGET), { refuse: new Set(['203.0.113.11']) });
  check('a pin is a FIRST choice, not a cage: when it refuses, the panel list takes over', r.dials[0] === '203.0.113.11:8443' && r.dials[1] === A + ':443', r.dials.join(',')); }
{ const P = await panel('noncf', SETTINGS); const ls = await links(P);
  const r = await run(P, clientPath(pick(ls, 'vless', '203.0.113.11:8443')), vlessDomain('example.org', 80));
  check('a target that is NOT behind Cloudflare is dialled directly (a ProxyIP is only for Cloudflare-hosted sites)', r.dials[0] === 'example.org:80', r.dials.join(',')); }
{ const P = await panel('legacy', SETTINGS); const ls = await links(P);
  const seed = clientPath(ls.find((l) => !/🎯|🧦/.test(nameOf(l)) && l.startsWith('vless://'))).split('?')[0];
  const r = await run(P, seed + '?ed=2560?proxyip=203.0.113.11%253A8443', vlessIp(...CF_TARGET)); // the old «?ed=…?proxyip=…» shape in the wild
  check('links made by older panels («?ed=2560?proxyip=…») still pin the relay', r.dials[0] === '203.0.113.11:8443', r.dials.join(',')); }

// ── 2) the SAME pin from every other output format (they used the generic path) ──
{ const P = await panel('formats', SETTINGS);
  const pathsB = {};
  const clash = await (await P.call('/clash/' + UUID + '?limit=40')).text();
  const cb = clash.split(/\n\s*- name:/).slice(1).find((b) => /\btype:\s*vless\b/.test(b) && b.includes('203.0.113.11:8443'));
  pathsB.clash = cb && (/\n\s*path:\s*"?([^"\n]+)"?/.exec(cb) || [])[1];
  const sb = JSON.parse(await (await P.call('/singbox/' + UUID + '?limit=40')).text()).outbounds.find((o) => o.type === 'vless' && (o.tag || '').includes('203.0.113.11:8443'));
  pathsB['sing-box'] = sb && sb.transport && sb.transport.path;
  const xt = await (await P.call('/xray/' + UUID + '?limit=40')).text();
  const xdoc = JSON.parse(maybeB64(xt).trim().startsWith('[') ? maybeB64(xt) : xt);
  const xo = (Array.isArray(xdoc) ? xdoc : [xdoc]).find((o) => (o.remarks || '').includes('203.0.113.11:8443') && /VLESS/i.test(JSON.stringify(o.outbounds && o.outbounds[0] && o.outbounds[0].protocol)));
  const walk = (n, out) => { if (n && typeof n === 'object') { if (n.wsSettings && n.wsSettings.path) out.push(n.wsSettings.path); Object.values(n).forEach((v) => walk(v, out)); } return out; };
  pathsB.xray = xo && walk(xo, [])[0];
  for (const [fmt, p] of Object.entries(pathsB)) {
    const r = p ? await run(P, p, vlessIp(...CF_TARGET)) : { dials: [] };
    check(fmt + ' 🎯 ' + B + ': the path it sends makes the worker dial that relay first', r.dials[0] === '203.0.113.11:8443', fmt + ' path=' + p + ' dials=' + r.dials.join(','));
  } }

// ── 3) 🧦 your own SOCKS5 proxy — end to end, including the app scanner's «#remark» line ──
const SOCKS = '83.147.217.103:1080';
const LINE = 'socks5://83.147.217.103:1080#SOCKS5 83.147.217.103'; // verbatim: what the app's proxy scanner exports
for (const [tag, label, settings] of [
  ['socks-clean', 'clean «socks5://ip:port»', { proxyIps: ['socks5://83.147.217.103:1080'] }],
  ['socks-line', 'the app scanner line pasted RAW into the box («#SOCKS5 ip» remark)', { proxyIps: LINE }],
  ['socks-tme', 'a t.me/socks share', { proxyIps: 'https://t.me/socks?server=83.147.217.103&port=1080' }],
]) {
  const P = await panel(tag, settings); const ls = await links(P);
  const link = ls.find((l) => l.startsWith('vless://') && nameOf(l).startsWith('🧦'));
  check(label + ': the sub has a 🧦 config for it', !!link && nameOf(link).includes('83.147.217.103:1080'), JSON.stringify(ls.map(nameOf).filter((n) => /🎯|🧦/.test(n))));
  if (!link) continue;
  const r = await run(P, clientPath(link), vlessDomain('example.org', 80, Array.from(enc.encode('GET / HTTP/1.1\r\n\r\n'))), { socks: new Set([SOCKS]) });
  check(label + ': the worker exits through that SOCKS5 server (first dial)', r.dials[0] === SOCKS, r.dials.join(','));
  check(label + ': … which is asked to CONNECT to the real target', r.socksConnect && r.socksConnect.host === 'example.org' && r.socksConnect.port === 80, JSON.stringify(r.socksConnect));
  check(label + ': … and the client payload reaches the far side through it', r.written.map((b) => new TextDecoder().decode(b)).join('') === 'GET / HTTP/1.1\r\n\r\n', JSON.stringify(r.written.map((b) => new TextDecoder().decode(b))));
}

console.log(failures ? '\nPROXYIP DATA-PLANE TESTS FAILED (' + failures + ')' : '\nPROXYIP DATA-PLANE TESTS PASSED');
process.exit(failures ? 1 : 0);
