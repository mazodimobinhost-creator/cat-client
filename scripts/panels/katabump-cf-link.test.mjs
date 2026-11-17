/**
 * beta225 follow-up — «can a KataBump panel really sit behind a Cloudflare panel?» answered with real processes, not mocks:
 *   • the REAL KataBump-style launcher (node-panel/index.js, CAT_HOST=katabump, one port, no TLS) runs in its own
 *     process exactly like Pterodactyl starts it;
 *   • a Cloudflare panel instance runs in this process behind a tiny HTTP server, with Cloudflare's two outbound
 *     rules simulated: fetch() to a bare IP or a non-80/443 port is refused (error 1003 / port ignored), while
 *     `cloudflare:sockets` connect() may reach any IP:port (real TCP here);
 *   • order that works: code made in the CF panel → pasted in the KataBump panel (KataBump calls the Worker);
 *   • the wrong order is refused by the CF panel with a clear message (it never calls the IP);
 *   • «exit = all» then really carries bytes phone→CF panel→KataBump→target (VLESS over plain WS on the one port);
 *   • «Real check» still runs the socket tunnel probe although the KataBump API cannot be fetched from Cloudflare;
 *   • a KataBump panel behind an orange-cloud domain (edge cf) is refused as an exit (CF→CF sockets are blocked).
 * Usage: node scripts/panels/katabump-cf-link.test.mjs  (needs `ws` from devDependencies for the launcher)
 */
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { Duplex } from 'node:stream';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 400) : '')); }
}
const freePort = () => new Promise((r) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
class FakeKV { constructor() { this.m = new Map(); } async get(k, o) { const v = this.m.has(k) ? this.m.get(k) : null; return v !== null && o && (o === 'json' || o.type === 'json') ? JSON.parse(v) : v; } async put(k, v) { this.m.set(k, v); } async delete(k) { this.m.delete(k); } async list() { return { keys: [...this.m.keys()].map((name) => ({ name })), list_complete: true }; } }

// ---- a target the exit must reach ----
const target = http.createServer((q, s) => s.end('TARGET-OK ' + q.url + ' from ' + q.socket.remoteAddress));
await new Promise((r) => target.listen(0, '127.0.0.1', r));
const TPORT = target.address().port;

// ---- the KataBump panel: real launcher, real adapter, this panel bundle ----
const src = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-kbcf-src-'));
fs.copyFileSync(workerPath, path.join(src, 'catclient.worker.js'));
fs.copyFileSync(path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs'), path.join(src, 'cat-panel-adapter.mjs'));
const shaOf = (f) => crypto.createHash('sha256').update(fs.readFileSync(path.join(src, f))).digest('hex');
fs.writeFileSync(path.join(src, 'cat-node-panel.sha256'), shaOf('catclient.worker.js') + '  catclient.worker.js\n' + shaOf('cat-panel-adapter.mjs') + '  cat-panel-adapter.mjs\n');
const kbApp = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-kbcf-app-'));
for (const f of ['index.js', 'package.json']) fs.copyFileSync(path.join(root, 'node-panel', f), path.join(kbApp, f));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(kbApp, 'node_modules'), 'dir');
fs.writeFileSync(path.join(kbApp, 'cat-config.json'), JSON.stringify({ UUID: '7c1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4', TROJAN_PASS: 'kb-pass', CAT_HOST: 'katabump' }));
const KPORT = await freePort();
const kb = await new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['index.js'], { cwd: kbApp, env: { PATH: process.env.PATH, HOME: process.env.HOME, SERVER_PORT: String(KPORT), CAT_PANEL_SOURCE_DIR: src, P_SERVER_UUID: 'f00d' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  const t = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('launcher did not start: ' + out)); }, 30000);
  const on = (d) => { out += d; if (/listening on 0\.0\.0\.0:/.test(out)) { clearTimeout(t); resolve({ child, out: () => out }); } };
  child.stdout.on('data', on); child.stderr.on('data', on);
  child.on('exit', (c) => { clearTimeout(t); reject(new Error('launcher exited ' + c + ': ' + out)); });
});
const kbReq = (method, p, body, headers = {}) => new Promise((resolve, reject) => {
  const data = body == null ? '' : JSON.stringify(body);
  const req = http.request('http://127.0.0.1:' + KPORT + p, { method, headers: Object.assign(body == null ? {} : { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) }, headers) }, (res) => {
    let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; });
    res.on('end', () => { let j = null; try { j = JSON.parse(b); } catch (e) { } resolve({ status: res.statusCode, headers: res.headers, j, txt: b }); });
  });
  req.on('error', reject); req.end(data);
});
const kbLogin = await kbReq('POST', '/api/login', { username: 'admin', password: 'admin' });
const kbCookie = { cookie: String((kbLogin.headers['set-cookie'] || [''])[0]).split(';')[0] };
check('KataBump panel: admin/admin login', kbLogin.status === 200, kbLogin.txt);

// ---- the Cloudflare panel, with Cloudflare's outbound rules ----
const CF = await import(pathToFileURL(workerPath).href + '?panel=cf');
const TA = CF._testing;
const cfEnv = { CAT_KV: new FakeKV(), UUID: '81111111-2222-4333-8444-555555555555', DATA_PLANE_ENABLED: 'true' };
const realFetch = globalThis.fetch;
const cfFetchRefused = [];
globalThis.fetch = async (input, init) => {
  const u = new URL(input instanceof Request ? input.url : String(input));
  const port = Number(u.port) || (u.protocol === 'https:' ? 443 : 80);
  if (net.isIP(u.hostname.replace(/^\[|\]$/g, '')) || (port !== 80 && port !== 443)) {
    cfFetchRefused.push(u.host);
    return new Response('error code: 1003', { status: 403 }); // what a Worker gets for a bare IP
  }
  return realFetch(input, init);
};
const dials = [];
const realSockets = {
  connect(addr) {
    dials.push(addr.hostname + ':' + addr.port);
    const s = net.connect({ host: addr.hostname, port: addr.port });
    const web = Duplex.toWeb(s);
    return {
      readable: web.readable, writable: web.writable,
      opened: new Promise((res, rej) => { s.once('connect', () => res({})); s.once('error', rej); }),
      closed: new Promise((res) => s.once('close', () => res())),
      close() { s.destroy(); return Promise.resolve(); },
    };
  },
};
TA.__setSockets(realSockets);
const cfServer = http.createServer(async (req, res) => {
  const chunks = []; for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const r = await CF.default.fetch(new Request('http://localhost:' + CFPORT + req.url, { method: req.method, headers: req.headers, body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body }), cfEnv, { waitUntil() {} });
  res.writeHead(r.status, Object.fromEntries(r.headers)); res.end(Buffer.from(await r.arrayBuffer()));
});
await new Promise((r) => cfServer.listen(0, r)); // dual-stack: «localhost» works on ::1 and 127.0.0.1
const CFPORT = cfServer.address().port;
async function cf(p, { method = 'GET', body } = {}) {
  const init = { method, headers: Object.assign({}, cfAuth) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  const r = await CF.default.fetch(new Request('http://localhost:' + CFPORT + p, init), cfEnv, { waitUntil() {} });
  const txt = await r.text(); let j = null; try { j = JSON.parse(txt); } catch (e) { }
  return { status: r.status, j, txt };
}
let cfAuth = {};
const cfLogin = await CF.default.fetch(new Request('http://localhost:' + CFPORT + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), cfEnv, { waitUntil() {} });
cfAuth = { cookie: (cfLogin.headers.get('set-cookie') || '').split(';')[0] };
check('Cloudflare panel: admin/admin login', cfLogin.status === 200);

// ---- wrong order: code from KataBump pasted in the CF panel ----
const kbCode = await kbReq('POST', '/api/links/code', {}, kbCookie);
check('KataBump makes a link code carrying its http://IP:port', kbCode.j && kbCode.j.ok && TA.parseLinkCode(kbCode.j.code).u.url === 'http://127.0.0.1:' + KPORT, kbCode.txt.slice(0, 200));
const wrong = await cf('/api/links/join', { method: 'POST', body: { code: kbCode.j.code } });
check('CF panel refuses the KataBump code with the right advice and never fetches the IP', wrong.status === 400 && /make the code in THIS panel/.test(wrong.txt) && cfFetchRefused.length === 0, wrong.txt.slice(0, 200));

// ---- right order: code from the CF panel pasted in KataBump ----
const code = await cf('/api/links/code', { method: 'POST' });
check('CF panel makes a code', code.j && code.j.ok, code.txt.slice(0, 200));
const join = await kbReq('POST', '/api/links/join', { code: code.j.code }, kbCookie);
check('KataBump joins with the CF code (KataBump → Worker over HTTPS-capable fetch)', join.status === 200 && join.j && join.j.ok && join.j.peer && join.j.peer.host === 'localhost', join.txt.slice(0, 300));
const links = (await cf('/api/links')).j;
const peer = links && links.peers && links.peers[0];
check('CF panel lists KataBump: node / plain / that one port / no TLS / tunnel path known', peer && peer.info.platform === 'node' && peer.info.edge === 'plain' && peer.info.port === KPORT && peer.info.tls === false && /^\//.test(peer.info.path) && peer.exitCapable === true, JSON.stringify(peer));

const setExit = await cf('/api/links/set', { method: 'POST', body: { id: peer.id, exit: 'all' } });
check('CF panel: «exit through KataBump — all sites» saved without pressing Check first', setExit.j && setExit.j.ok && setExit.j.peer.exit === 'all', setExit.txt.slice(0, 200));
const settings = (await cf('/api/settings')).j.settings;
check('linkExit = plain VLESS-WS to 127.0.0.1:<KataBump port>', settings.linkExit && settings.linkExit.host === '127.0.0.1' && settings.linkExit.port === KPORT && settings.linkExit.tls === false, JSON.stringify(settings.linkExit));

// ---- real bytes: CF panel → KataBump → target ----
const dlog = [];
const d = await TA.dialTarget('127.0.0.1', TPORT, cfEnv, settings, (m) => dlog.push(m), null, realSockets);
const w = d.socket.writable.getWriter();
await w.write(new TextEncoder().encode('GET /through-katabump HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n'));
w.releaseLock();
let got = d.leftover && d.leftover.byteLength ? new TextDecoder().decode(d.leftover) : '';
const rd = d.socket.readable.getReader();
const until = Date.now() + 8000;
while (!/TARGET-OK/.test(got) && Date.now() < until) {
  const r = await Promise.race([rd.read(), new Promise((res) => setTimeout(() => res({ timeout: true }), 8000))]);
  if (r.timeout || r.done) break;
  got += new TextDecoder().decode(r.value);
}
try { d.socket.close(); } catch (e) { }
check('a request from the CF panel really leaves through the KataBump panel', d.via === 'link' && dials[0] === '127.0.0.1:' + KPORT && /TARGET-OK \/through-katabump/.test(got), JSON.stringify({ via: d.via, dials, dlog, got: got.slice(0, 120) }));

// ---- «Real check»: KataBump API unreachable from Cloudflare, tunnel probe still real ----
const chk = await cf('/api/links/check', { method: 'POST', body: { id: peer.id } });
check('Real check: API part fails like on Cloudflare (fetch to IP:port refused) …', chk.j && chk.j.ok && chk.j.api === false && cfFetchRefused.length > 0, chk.txt.slice(0, 300));
check('… but the socket tunnel probe still runs through KataBump (not skipped)', chk.j && chk.j.tunnel && chk.j.tunnel.skipped !== true, JSON.stringify(chk.j && chk.j.tunnel));

// ---- KataBump behind an orange-cloud domain cannot be a CF exit ----
check('KataBump behind Cloudflare (edge cf) is not offered as a CF panel exit', TA.linkExitCapable(cfEnv, { platform: 'node', edge: 'cf' }) === false && TA.linkExitCapable(cfEnv, { platform: 'node', edge: 'plain' }) === true);

globalThis.fetch = realFetch;
kb.child.kill('SIGTERM');
cfServer.close(); target.close();
for (const dir of [src, kbApp]) fs.rmSync(dir, { recursive: true, force: true });
if (failures) { console.error('katabump-cf-link: ' + failures + ' check(s) failed'); process.exit(1); }
console.log('katabump-cf-link: all checks passed');
process.exit(0);
