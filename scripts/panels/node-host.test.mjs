/**
 * Panel 6.69.45 «node» platform — the 2-file launcher (node-panel/index.js + package.json) run exactly like a
 * host runs it (`node index.js`, port from SERVER_PORT), with the REAL adapter and the REAL panel bundle:
 *   • the three ways a phone reaches a Node host → the configs match that way and nothing else:
 *       plain  (KataBump IP:port, no TLS)  → address = host, port = that port, security=none
 *       tls    (Lucity / Shiprr HTTPS edge) → host:443 TLS, SNI = Host = host
 *       cf     (domain on Cloudflare)       → the full Cloudflare set (clean IPs, CF ports)
 *   • VLESS and Trojan over WebSocket really reach a target through the launcher;
 *   • settings survive a restart (file storage under CAT_DATA_DIR);
 *   • a tampered download is refused (SHA-256 list), the pure helpers behave;
 *   • on a direct connection X-Forwarded-For cannot dodge the login lockout.
 * Needs `ws` (devDependency). CAT_PANEL_WORKER may point at a built artifact.
 */
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 300) : '')); }
}

const L = await import(pathToFileURL(path.join(root, 'node-panel/index.js')).href);

// ---- pure helpers ----
const sumsText = 'a'.repeat(64) + '  catclient.worker.js\n' + 'B'.repeat(64) + ' *cat-panel-adapter.mjs\njunk line\n';
const sums = L.parseSums(sumsText);
check('parseSums reads sha256sum lines (text and *binary), ignores junk', sums.get('catclient.worker.js') === 'a'.repeat(64) && sums.get('cat-panel-adapter.mjs') === 'b'.repeat(64) && sums.size === 2);
check('patchPanel swaps the one cloudflare:sockets import', L.patchPanel('x=import("cloudflare:sockets");') === 'x=Promise.resolve(globalThis.__catSockets);');
check('patchPanel refuses 0 or 2 imports', [() => L.patchPanel('nothing'), () => L.patchPanel("import('cloudflare:sockets');import('cloudflare:sockets')")].every((f) => { try { f(); return false; } catch (_) { return true; } }));
const asset = (name) => ({ name, browser_download_url: 'https://x/' + name });
const rels = [
  { tag_name: 'draft', draft: true, assets: [asset('catclient.worker.js'), asset('cat-panel-adapter.mjs'), asset('cat-node-panel.sha256')] },
  { tag_name: 'v-old-no-adapter', assets: [asset('catclient.worker.js')] },
  { tag_name: 'v2', published_at: '2026-10-27T15:00:00Z', assets: [asset('catclient.worker.js'), asset('cat-panel-adapter.mjs'), asset('cat-node-panel.sha256')] },
  { tag_name: 'v1', published_at: '2026-10-27T14:00:00Z', assets: [asset('catclient.worker.js'), asset('cat-panel-adapter.mjs'), asset('cat-node-panel.sha256')] },
];
check('pickRelease skips drafts and releases without the adapter', L.pickRelease(rels).tag === 'v2' && L.pickRelease(rels).urls['cat-panel-adapter.mjs'] === 'https://x/cat-panel-adapter.mjs');
check('pickRelease takes the newest by date even if the list is out of order', L.pickRelease([rels[3], rels[2]]).tag === 'v2');
check('pickRelease honours a pinned tag', L.pickRelease(rels, 'v1').tag === 'v1' && L.pickRelease(rels, 'nope') === null);
{
  const cdir = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-cfg-'));
  fs.writeFileSync(path.join(cdir, 'cat-config.json'), JSON.stringify({ UUID: 'u-1', TROJAN_PASS: 'p-1', PATH: '/evil', NODE_OPTIONS: '--require x', CAT_NO_DISK: 1, PANEL_PASSWORD: { x: 1 } }));
  const env = { TROJAN_PASS: 'from-env' };
  const used = L.applyConfigFile(cdir, env);
  check('cat-config.json: only allowed keys, env wins, no objects', env.UUID === 'u-1' && env.TROJAN_PASS === 'from-env' && env.CAT_NO_DISK === '1' && !('PATH' in env) && !('NODE_OPTIONS' in env) && !('PANEL_PASSWORD' in env) && used.join() === 'UUID,CAT_NO_DISK', JSON.stringify(env));
  fs.writeFileSync(path.join(cdir, 'cat-config.json'), '{broken');
  check('cat-config.json: broken JSON is ignored, not fatal', L.applyConfigFile(cdir, {}).length === 0);
  fs.rmSync(cdir, { recursive: true, force: true });
}
// the app ships the launcher in its assets (save-to-phone zip / auto-upload): must be byte-identical
for (const f of ['index.js', 'package.json', 'README.md']) {
  const a = fs.readFileSync(path.join(root, 'node-panel', f));
  const b = fs.existsSync(path.join(root, 'app/src/main/assets/node-panel', f)) ? fs.readFileSync(path.join(root, 'app/src/main/assets/node-panel', f)) : Buffer.alloc(0);
  check('app asset node-panel/' + f + ' is identical to node-panel/' + f, a.equals(b));
}
// beta224 SmarterASP (IIS): the web.config edge tag → original URL + forwarded headers
{
  const r1 = { url: '/__cat_edge/on/2001:db8::7/sub/x?y=1', headers: { 'x-forwarded-host': 'evil.example', 'x-forwarded-port': '9' } };
  check('iisEdge: tag on → https, client IP, original URL, forged x-forwarded-host/port dropped', L.iisEdge(r1) === true && r1.url === '/sub/x?y=1' && r1.headers['x-forwarded-proto'] === 'https' && r1.headers['x-forwarded-for'] === '2001:db8::7' && !('x-forwarded-host' in r1.headers) && !('x-forwarded-port' in r1.headers), JSON.stringify(r1));
  const r2 = { url: '/__cat_edge/OFF/198.51.100.4', headers: {} };
  check('iisEdge: tag off → http, bare tag → /', L.iisEdge(r2) && r2.url === '/' && r2.headers['x-forwarded-proto'] === 'http');
  const r3 = { url: '/__cat_edge/off/198.51.100.4/__cat_edge/on/9.9.9.9/sub/x', headers: {} };
  L.iisEdge(r3);
  check('iisEdge: a client-forged second tag stays an unknown path (cannot claim https or another IP)', r3.url === '/__cat_edge/on/9.9.9.9/sub/x' && r3.headers['x-forwarded-proto'] === 'http' && r3.headers['x-forwarded-for'] === '198.51.100.4');
  const r4 = { url: '/panel', headers: {} };
  check('iisEdge: no tag (rewrite missing) → plain http behind a proxy', L.iisEdge(r4) === false && r4.url === '/panel' && r4.headers['x-forwarded-proto'] === 'http');
  const here = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-codedir-'));
  check('codeDirOf: CAT_CODE_DIR inside the folder is used', L.codeDirOf(here, { CAT_CODE_DIR: 'App_Data/cat-code' }) === path.join(here, 'App_Data', 'cat-code'));
  check('codeDirOf: outside the folder (node_modules unreachable) → ./cat-code', L.codeDirOf(here, { CAT_CODE_DIR: '../elsewhere' }) === path.join(here, 'cat-code') && L.codeDirOf(here, {}) === path.join(here, 'cat-code'));
  fs.rmSync(here, { recursive: true, force: true });
}
check('portOf: SERVER_PORT (KataBump) > PORT > 8080', L.portOf({ SERVER_PORT: '25565', PORT: '3000' }) === 25565 && L.portOf({ PORT: '3000' }) === 3000 && L.portOf({ PORT: 'x' }) === 8080);

// ---- lay out what a host gets: the 2 files (+ node_modules from npm install) and a release source ----
const src = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-src-'));
fs.copyFileSync(workerPath, path.join(src, 'catclient.worker.js'));
fs.copyFileSync(path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs'), path.join(src, 'cat-panel-adapter.mjs'));
const shaOf = (f) => crypto.createHash('sha256').update(fs.readFileSync(path.join(src, f))).digest('hex');
fs.writeFileSync(path.join(src, 'cat-node-panel.sha256'), shaOf('catclient.worker.js') + '  catclient.worker.js\n' + shaOf('cat-panel-adapter.mjs') + '  cat-panel-adapter.mjs\n');
const app = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-app-'));
for (const f of ['index.js', 'package.json']) fs.copyFileSync(path.join(root, 'node-panel', f), path.join(app, f));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(app, 'node_modules'), 'dir');

// tampered source → refused
const bad = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-bad-'));
for (const f of ['catclient.worker.js', 'cat-panel-adapter.mjs', 'cat-node-panel.sha256']) fs.copyFileSync(path.join(src, f), path.join(bad, f));
fs.appendFileSync(path.join(bad, 'cat-panel-adapter.mjs'), '\n// tampered\n');
const badDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-badcode-'));
let refused = '';
try { await L.prepareCode(badDir, { CAT_PANEL_SOURCE_DIR: bad }); } catch (e) { refused = e.message; }
check('a file that does not match the SHA-256 list is refused', /SHA-256 mismatch/.test(refused) && !fs.existsSync(path.join(badDir, 'api', 'index.mjs')), refused);

const UUID = '6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4';
const TPASS = 'cat-node-trojan';
const freePort = () => new Promise((r) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
const PORT = await freePort();
const dataDir = path.join(app, 'cat-data');

function start(viaFile = false) {
  return new Promise((resolve, reject) => {
    const ids = viaFile ? {} : { UUID, TROJAN_PASS: TPASS };
    const child = spawn(process.execPath, ['index.js'], {
      cwd: app,
      env: Object.assign({ PATH: process.env.PATH, HOME: process.env.HOME, SERVER_PORT: String(PORT), CAT_PANEL_SOURCE_DIR: src }, ids),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    const t = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('launcher did not start: ' + out)); }, 30000);
    const on = (d) => { out += d; if (/listening on 0\.0\.0\.0:/.test(out)) { clearTimeout(t); resolve({ child, out: () => out }); } };
    child.stdout.on('data', on); child.stderr.on('data', on);
    child.on('exit', (c) => { clearTimeout(t); reject(new Error('launcher exited ' + c + ': ' + out)); });
  });
}
const stop = (p) => new Promise((r) => { p.child.removeAllListeners('exit'); p.child.once('exit', r); p.child.kill('SIGTERM'); });

let proc = await start();
check('launcher starts on SERVER_PORT and reports the source + storage', /panel local ready \(SHA-256 checked\)/.test(proc.out()) && /storage: .*cat-data/.test(proc.out()), proc.out());

const base = 'http://127.0.0.1:' + PORT;
const get = (p, headers = {}) => new Promise((resolve, reject) => {
  const req = http.request(base + p, { headers }, (res) => { let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; }); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: b })); });
  req.on('error', reject); req.end();
});
const send = (method, p, body, headers = {}) => new Promise((resolve, reject) => {
  const data = body == null ? '' : JSON.stringify(body);
  const req = http.request(base + p, { method, headers: Object.assign({ 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) }, headers) }, (res) => { let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; }); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: b })); });
  req.on('error', reject); req.end(data);
});

const ver = await get('/api/version');
check('/api/version answers through the launcher', ver.status === 200 && /6\.\d+\.\d+/.test(ver.body), ver.status + ' ' + ver.body.slice(0, 120));

const subLines = async (headers) => {
  const r = await get('/sub/' + UUID, headers);
  const text = r.body.includes('://') ? r.body : Buffer.from(r.body, 'base64').toString('utf8');
  return text.split('\n').filter((l) => /^(vless|trojan):\/\//.test(l));
};
const parse = (l) => { const u = new URL(l.replace(/^(vless|trojan):\/\//, 'http://')); return { scheme: l.split('://')[0], host: u.hostname, port: Number(u.port), q: u.searchParams }; };

// plain (KataBump IP:port)
const plain = (await subLines({})).map(parse);
check('plain host: exactly one VLESS + one Trojan line', plain.length === 2 && plain.some((p) => p.scheme === 'vless') && plain.some((p) => p.scheme === 'trojan'), plain.length);
check('plain host: address = the host, port = its own port, no TLS, ws, Host = host', plain.every((p) => p.host === '127.0.0.1' && p.port === PORT && p.q.get('security') === 'none' && p.q.get('type') === 'ws' && p.q.get('host') === '127.0.0.1' && !p.q.get('sni')), JSON.stringify(plain.map((p) => [p.host, p.port, p.q.get('security')])));
const pinTry = (await subLines({}).then(() => get('/sub/' + UUID + '?addr=104.16.1.1&port=443', {})));
const pinLines = (pinTry.body.includes('://') ? pinTry.body : Buffer.from(pinTry.body, 'base64').toString('utf8')).split('\n').filter((l) => /:\/\//.test(l)).map(parse);
check('plain host: ?addr= / ?port= cannot turn it into a Cloudflare line', pinLines.length === 2 && pinLines.every((p) => p.host === '127.0.0.1' && p.port === PORT));

// tls (Lucity / Shiprr)
const tlsHost = 'cat-x.shiprr.dev';
const tls = (await subLines({ 'x-forwarded-proto': 'https', 'x-forwarded-host': tlsHost, 'x-forwarded-for': '203.0.113.9' })).map(parse);
// 6.79.3: the host's own IPv4s (if its name resolves on this machine) may add «🏠 Server IP» lines — same SNI/Host.
check('HTTPS edge: one VLESS + one Trojan on host:443 TLS, SNI = Host = host', tls.filter((p) => p.host === tlsHost).length === 2 && tls.every((p) => (p.host === tlsHost || /^\d+\.\d+\.\d+\.\d+$/.test(p.host)) && p.port === 443 && p.q.get('security') === 'tls' && p.q.get('sni') === tlsHost && p.q.get('host') === tlsHost && p.q.get('type') === 'ws'), JSON.stringify(tls.map((p) => [p.host, p.port, p.q.get('sni')])));

// cf (kdns.fr on Cloudflare)
const cfHost = 'cat.kdns.fr';
const cf = (await subLines({ 'cf-ray': '8f00aa-FRA', 'cf-connecting-ip': '198.51.100.7', 'x-forwarded-proto': 'https', 'x-forwarded-host': cfHost })).map(parse);
check('behind Cloudflare: the full Cloudflare set (clean IPs + several CF ports)', cf.length > 6 && cf.some((p) => p.host !== cfHost) && new Set(cf.map((p) => p.port)).size > 1 && cf.every((p) => p.q.get('host') === cfHost), cf.length + ' ' + [...new Set(cf.map((p) => p.port))].join(','));

// tunnels through the launcher
const target = http.createServer((q, s) => s.end('TARGET-OK ' + q.url));
await new Promise((r) => target.listen(0, '127.0.0.1', r));
const TPORT = target.address().port;
const idBytes = Buffer.from(UUID.replace(/-/g, ''), 'hex');
const hostB = Buffer.from('127.0.0.1');
const reqB = Buffer.from('GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n');
const vlessFrame = Buffer.concat([Buffer.from([0]), idBytes, Buffer.from([0, 1, TPORT >> 8, TPORT & 255, 2, hostB.length]), hostB, reqB]);
const trojanFrame = Buffer.concat([Buffer.from(crypto.createHash('sha224').update(TPASS).digest('hex') + '\r\n'), Buffer.from([1, 3, hostB.length]), hostB, Buffer.from([TPORT >> 8, TPORT & 255]), Buffer.from('\r\n'), reqB]);
function overWs(p, frame) {
  return new Promise((resolve) => {
    const ws = new WebSocket('ws://127.0.0.1:' + PORT + p);
    ws.binaryType = 'arraybuffer';
    let buf = Buffer.alloc(0);
    const t = setTimeout(() => { try { ws.close(); } catch (_) { } resolve('TIMEOUT ' + buf); }, 8000);
    ws.onopen = () => ws.send(frame);
    ws.onmessage = (e) => { buf = Buffer.concat([buf, Buffer.from(e.data)]); if (buf.includes('TARGET-OK')) { clearTimeout(t); ws.close(); resolve(buf.toString('latin1')); } };
    ws.onclose = (e) => { clearTimeout(t); resolve(buf.length ? buf.toString('latin1') : 'CLOSED ' + e.code); };
    ws.onerror = () => { };
  });
}
const vPath = plain.find((p) => p.scheme === 'vless').q.get('path');
const tPath = plain.find((p) => p.scheme === 'trojan').q.get('path');
check('VLESS over WebSocket reaches the target through the launcher', /TARGET-OK \/hello/.test(await overWs(vPath, vlessFrame)));
check('Trojan over WebSocket reaches the target through the launcher', /TARGET-OK \/hello/.test(await overWs(tPath, trojanFrame)));

// login + settings persist across a restart (file storage)
const login = await send('POST', '/api/login', { username: 'admin', password: 'admin' });
const cookie = String(login.headers['set-cookie'] || '').split(';')[0];
check('owner can sign in through the launcher', login.status === 200 && /=/.test(cookie), login.status + ' ' + login.body.slice(0, 120));
check('plain http: the session cookie drops Secure (else browsers never keep it on http://IP:port)', !/;\s*Secure/i.test(String(login.headers['set-cookie'])) && /HttpOnly/.test(String(login.headers['set-cookie'])), login.headers['set-cookie']);
const loginTls = await send('POST', '/api/login', { username: 'admin', password: 'admin' }, { 'x-forwarded-proto': 'https', 'x-forwarded-host': 'cat-x.shiprr.dev' });
check('HTTPS edge: the session cookie keeps Secure', /;\s*Secure/.test(String(loginTls.headers['set-cookie'])), loginTls.headers['set-cookie']);
const page = await get('/panel', { cookie });
check('signed-in panel explains the direct Node mode with the real address:port', page.status === 200 && page.body.includes('127.0.0.1:' + PORT) && page.body.includes('🖥️') && /KataBump/.test(page.body), page.status + ' ' + page.body.length);
const tlsPage = await get('/panel', { cookie, 'x-forwarded-proto': 'https', 'x-forwarded-host': tlsHost });
check('HTTPS edge: panel says TLS on 443 with the host name', tlsPage.body.includes(tlsHost) && /Lucity|Shiprr/.test(tlsPage.body));
const put = await send('PUT', '/api/settings', { country: 'DE' }, { cookie, origin: base });
check('settings save on the Node host', put.status === 200, put.status + ' ' + put.body.slice(0, 160));
await stop(proc);
check('storage file written to cat-data (mode 0600)', fs.existsSync(path.join(dataDir, 'cat-kv.json')) && (fs.statSync(path.join(dataDir, 'cat-kv.json')).mode & 0o077) === 0);
// KataBump has no env editor: the restart gets its identity from cat-config.json (what the app uploads)
fs.writeFileSync(path.join(app, 'cat-config.json'), JSON.stringify({ UUID, TROJAN_PASS: TPASS }));
proc = await start(true);
check('restart reuses the prepared panel', /panel local ready/.test(proc.out()));
check('identity read from cat-config.json (names logged, values never)', /settings from cat-config\.json: UUID, TROJAN_PASS/.test(proc.out()) && !proc.out().includes(UUID), proc.out());
const fromFile = (await subLines({})).map(parse);
check('the sub for the UUID from cat-config.json works after the restart', fromFile.length === 2 && fromFile.every((p) => p.port === PORT));
const login2 = await send('POST', '/api/login', { username: 'admin', password: 'admin' });
const cookie2 = String(login2.headers['set-cookie'] || '').split(';')[0];
const after = await get('/api/settings', { cookie: cookie2 });
let country = '';
try { const j = JSON.parse(after.body); country = (j.settings || j).country; } catch (_) { }
check('settings survive a restart (file storage)', after.status === 200 && country === 'DE', after.status + ' ' + after.body.slice(0, 160));

// direct connection: a forged X-Forwarded-For must not give a fresh login budget
let lockedAt = 0;
for (let i = 1; i <= 12; i++) {
  const r = await send('POST', '/api/login', { username: 'admin', password: 'wrong-' + i }, { 'x-forwarded-for': '10.0.' + i + '.1' });
  if (r.status === 429) { lockedAt = i; break; }
}
check('direct mode: forged X-Forwarded-For does not dodge the login lockout', lockedAt > 0, 'never locked');

// ---- 6.79.0 🟧 Cloudflare front on a Node host with its own HTTPS name (Railway / Bot-Hosting / Lucity / Shiprr) ----
const FRONT = 'cat-front-ab12cd.someone.workers.dev';
const putFront = await send('PUT', '/api/settings', { vercelCfFront: FRONT }, { cookie: cookie2 });
check('the front name saves on the Node host (settings)', putFront.status === 200, putFront.status + ' ' + putFront.body.slice(0, 160));
const railHost = 'cat-panel-production.up.railway.app';
const viaRail = (await subLines({ 'x-forwarded-proto': 'https', 'x-forwarded-host': railHost, 'x-forwarded-for': '203.0.113.9' })).map(parse);
const frontLines = viaRail.filter((p) => p.host === FRONT);
check('HTTPS host + front: one VLESS + one Trojan 🟧 line first, front name as address/Host/SNI on 443 TLS', frontLines.length === 2 && viaRail.slice(0, 2).every((p) => p.host === FRONT) && frontLines.every((p) => p.port === 443 && p.q.get('security') === 'tls' && p.q.get('sni') === FRONT && p.q.get('host') === FRONT && p.q.get('type') === 'ws'), JSON.stringify(viaRail.map((p) => [p.host, p.port, p.q.get('sni')])));
check('HTTPS host + front: the host\'s own TLS lines stay (host:443, SNI = host)', viaRail.filter((p) => p.host === railHost).length === 2 && viaRail.filter((p) => p.host === railHost).every((p) => p.port === 443 && p.q.get('sni') === railHost));
check('front lines keep the panel path / identity (same ws path as the host lines)', frontLines.every((f) => viaRail.some((h) => h.host === railHost && h.scheme === f.scheme && h.q.get('path') === f.q.get('path'))));
// the front's own subrequest: Cloudflare may add cf-ray on the way out — it must not turn into the clean-IP set
const viaFront = (await subLines({ 'x-cat-front': FRONT, 'cf-ray': '8f00bb-FRA', 'cf-worker': 'someone.workers.dev', 'x-forwarded-proto': 'https', 'x-forwarded-host': railHost })).map(parse);
check('request through our front (x-cat-front + cf-ray): host TLS lines + 🟧 lines, never the Cloudflare clean-IP set', viaFront.filter((p) => !/^\d+\.\d+\.\d+\.\d+$/.test(p.host)).length === 4 && viaFront.every((p) => p.host === FRONT || p.host === railHost || (/^\d+\.\d+\.\d+\.\d+$/.test(p.host) && p.q.get('sni') === railHost)) && viaFront.every((p) => p.port === 443), JSON.stringify(viaFront.map((p) => [p.host, p.port])));
const tlsPanel = await get('/panel', { cookie: cookie2, 'x-forwarded-proto': 'https', 'x-forwarded-host': railHost });
check('HTTPS host panel: says the front is active (name shown)', tlsPanel.body.includes(FRONT) && tlsPanel.body.includes('🟧'), tlsPanel.status);
check('direct Node panel: <body data-plat="node"> + the CSS that hides the Cloudflare-only sections, shows «server IPs» (6.79.3: Locations stay)', /<body data-plat="node">/.test(tlsPanel.body) && /\[data-plat=node\] :is\(#v-scan,#v-nodes,#fSpoof,#v-addrtab\),body:not\(\[data-plat=node\]\) #v-nodeips\{display:none!important\}/.test(tlsPanel.body) && /\[data-plat=vercel\] :is\(#v-loc,#v-loc-cards,#v-socks\)/.test(tlsPanel.body) && tlsPanel.body.includes('id="v-nodeips"'));
check('…but the hidden sections stay in the page and 🔗 Link panels is never hidden', tlsPanel.body.includes('id="v-scan"') && tlsPanel.body.includes('id="v-link"') && !/data-plat[^{]*#v-link/.test(tlsPanel.body));
const cfPanel = await get('/panel', { cookie: cookie2, 'cf-ray': '8f00aa-FRA', 'x-forwarded-proto': 'https', 'x-forwarded-host': 'cat.kdns.fr' });
check('Node host behind Cloudflare: no data-plat (all Cloudflare features stay)', cfPanel.status === 200 && /<body>/.test(cfPanel.body) && !/<body data-plat/.test(cfPanel.body));
const plainWithFront = (await subLines({})).map(parse);
check('plain edge + front: the 🟧 lines are still TLS on 443 (the front itself speaks https to the host)', plainWithFront.filter((p) => p.host === FRONT).length === 2 && plainWithFront.filter((p) => p.host === FRONT).every((p) => p.port === 443 && p.q.get('security') === 'tls'));
await send('PUT', '/api/settings', { vercelCfFront: '' }, { cookie: cookie2 });
const noFront = (await subLines({})).map(parse);
check('front cleared → back to exactly the host\'s two lines', noFront.length === 2 && noFront.every((p) => p.host === '127.0.0.1'));

await stop(proc);

// ---- beta224 SmarterASP.NET: what IIS runs (httpPlatformHandler → node index.js on 127.0.0.1:PORT) ----
const iisApp = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-iis-'));
for (const f of ['index.js', 'package.json']) fs.copyFileSync(path.join(root, 'node-panel', f), path.join(iisApp, f));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(iisApp, 'node_modules'), 'dir');
const webConfig = fs.readFileSync(path.join(root, 'app/src/main/assets/node-panel/smarterasp/web.config'), 'utf8');
const envOf = (name) => (new RegExp('name="' + name + '" value="([^"]*)"').exec(webConfig) || [])[1];
check('web.config: httpPlatformHandler runs index.js with PORT from IIS and the IIS mode on', /modules="httpPlatformHandler"/.test(webConfig) && /arguments="index\.js"/.test(webConfig) && envOf('PORT') === '%HTTP_PLATFORM_PORT%' && envOf('CAT_BEHIND_IIS') === '1');
check('web.config: the edge tag is exactly what iisEdge reads ({HTTPS}/{REMOTE_ADDR}{UNENCODED_URL})', webConfig.includes('url="/__cat_edge/{HTTPS}/{REMOTE_ADDR}{UNENCODED_URL}" appendQueryString="false"'));
const IPORT = await freePort();
const iisProc = await new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['index.js'], {
    cwd: iisApp,
    env: { PATH: process.env.PATH, HOME: process.env.HOME, PORT: String(IPORT), CAT_PANEL_SOURCE_DIR: src, UUID, TROJAN_PASS: TPASS, CAT_BEHIND_IIS: envOf('CAT_BEHIND_IIS'), CAT_DATA_DIR: envOf('CAT_DATA_DIR').replace(/\\/g, '/'), CAT_CODE_DIR: envOf('CAT_CODE_DIR').replace(/\\/g, '/') },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  const t = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('IIS launcher did not start: ' + out)); }, 30000);
  const on = (d) => { out += d; if (/listening on 127\.0\.0\.1:/.test(out)) { clearTimeout(t); resolve({ child, out: () => out }); } };
  child.stdout.on('data', on); child.stderr.on('data', on);
  child.on('exit', (c) => { clearTimeout(t); reject(new Error('IIS launcher exited ' + c + ': ' + out)); });
});
check('IIS mode: listens on 127.0.0.1 only, panel cached in App_Data/cat-code, users in App_Data/cat-data', /behind IIS/.test(iisProc.out()) && fs.existsSync(path.join(iisApp, 'App_Data', 'cat-code', 'api', 'index.mjs')) && /storage: .*App_Data[\\/]cat-data/.test(iisProc.out()), iisProc.out());
const iisBase = 'http://127.0.0.1:' + IPORT;
const iisSub = async (p, headers) => {
  const r = await new Promise((resolve, reject) => {
    const req = http.request(iisBase + p, { headers }, (res) => { let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; }); res.on('end', () => resolve(b)); });
    req.on('error', reject); req.end();
  });
  const text = r.includes('://') ? r : Buffer.from(r, 'base64').toString('utf8');
  return text.split('\n').filter((l) => /^(vless|trojan):\/\//.test(l)).map(parse);
};
const siteHost = 'catuser-001-site1.example-tempurl.com';
// parse() reads the link as http://, where :80 is the default and URL.port is empty
const webPort80 = (p) => p.port === 80 || p.port === 0;
const iisTls = await iisSub('/__cat_edge/on/203.0.113.9/sub/' + UUID, { host: siteHost, 'x-forwarded-host': 'evil.example' });
check('IIS https: one VLESS + one Trojan on site:443 TLS, SNI = Host = the site (forged x-forwarded-host ignored)', iisTls.length === 2 && iisTls.every((p) => p.host === siteHost && p.port === 443 && p.q.get('security') === 'tls' && p.q.get('sni') === siteHost && p.q.get('host') === siteHost), JSON.stringify(iisTls.map((p) => [p.host, p.port, p.q.get('security')])));
const iisHttp = await iisSub('/__cat_edge/off/203.0.113.9/sub/' + UUID, { host: siteHost });
check('IIS http: configs on site:80 without TLS (never IIS\'s private port)', iisHttp.length === 2 && iisHttp.every((p) => p.host === siteHost && webPort80(p) && p.q.get('security') === 'none'), JSON.stringify(iisHttp.map((p) => [p.host, p.port, p.q.get('security')])));
const iisBare = await iisSub('/sub/' + UUID, { host: siteHost });
check('IIS without the rewrite tag: still site:80, never the private port ' + IPORT, iisBare.length === 2 && iisBare.every((p) => p.host === siteHost && webPort80(p)), JSON.stringify(iisBare.map((p) => [p.host, p.port])));
const iisVPath = iisHttp.find((p) => p.scheme === 'vless').q.get('path');
const viaIis = await new Promise((resolve) => {
  const ws = new WebSocket('ws://127.0.0.1:' + IPORT + '/__cat_edge/on/203.0.113.9' + iisVPath);
  ws.binaryType = 'arraybuffer';
  let buf = Buffer.alloc(0);
  const t = setTimeout(() => { try { ws.close(); } catch (_) { } resolve('TIMEOUT'); }, 8000);
  ws.onopen = () => ws.send(vlessFrame);
  ws.onmessage = (e) => { buf = Buffer.concat([buf, Buffer.from(e.data)]); if (buf.includes('TARGET-OK')) { clearTimeout(t); ws.close(); resolve(buf.toString('latin1')); } };
  ws.onclose = (e) => { clearTimeout(t); resolve(buf.length ? buf.toString('latin1') : 'CLOSED ' + e.code); };
  ws.onerror = () => { };
});
check('IIS: VLESS over WebSocket through the tagged URL reaches the target', /TARGET-OK \/hello/.test(viaIis), viaIis.slice(0, 120));
let iisLockedAt = 0;
for (let i = 1; i <= 12; i++) {
  const r = await new Promise((resolve, reject) => {
    const data = JSON.stringify({ username: 'admin', password: 'wrong-' + i });
    const req = http.request(iisBase + '/__cat_edge/on/203.0.113.77/api/login', { method: 'POST', headers: { host: siteHost, 'content-type': 'application/json', 'content-length': Buffer.byteLength(data), 'x-forwarded-for': '10.9.' + i + '.1' } }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.on('error', reject); req.end(data);
  });
  if (r === 429) { iisLockedAt = i; break; }
}
check('IIS: the login lockout counts the real client IP from the tag, not a forged X-Forwarded-For', iisLockedAt > 0, 'never locked');
await stop(iisProc);
fs.rmSync(iisApp, { recursive: true, force: true });

// ---- 6.79.0 host awareness (launcher) ----
check('hostOf: CAT_HOST wins; Railway / IIS guessed; Pterodactyl alone is NOT guessed', L.hostOf({ CAT_HOST: 'KataBump' }) === 'katabump' && L.hostOf({ RAILWAY_ENVIRONMENT: 'production' }) === 'railway' && L.hostOf({ CAT_BEHIND_IIS: '1' }) === 'smarterasp' && L.hostOf({ P_SERVER_UUID: 'x' }) === '' && L.hostOf({ CAT_HOST: 'evil' }) === '');
check('directHost: only an explicit CAT_HOST=katabump (Bot-Hosting / Pterodactyl vars alone stay proxied)', L.directHost({ CAT_HOST: 'katabump' }) && !L.directHost({ CAT_HOST: 'bothosting', P_SERVER_UUID: 'x' }) && !L.directHost({ P_SERVER_UUID: 'x' }));
check('CONFIG_KEYS: CAT_HOST / CAT_CF_FRONT / CAT_KEEPALIVE allowed, still nothing like PATH / NODE_OPTIONS', ['CAT_HOST', 'CAT_CF_FRONT', 'CAT_KEEPALIVE'].every((k) => L.CONFIG_KEYS.includes(k)) && !L.CONFIG_KEYS.some((k) => /^(PATH|NODE_OPTIONS|CAT_HOST_DIRECT|CAT_PLATFORM)$/.test(k)));
check('containerMemoryMb: cgroup v2 «max» → 0, a number → MB, v1 fallback', L.containerMemoryMb(() => 'max\n') === 0 && L.containerMemoryMb((f) => (f.endsWith('memory.max') ? '322961408\n' : '')) === 308 && L.containerMemoryMb((f) => { if (f.endsWith('memory.max')) throw new Error('no'); return '268435456'; }) === 256);
{
  const { EventEmitter } = await import('node:events');
  const srv = new EventEmitter();
  const hits = [];
  const kw = L.keepWarm(srv, {}, (u) => { hits.push(u); return Promise.resolve({ body: { cancel() {} } }); }, 60);
  srv.emit('request', { headers: { host: 'cat-user-001-site1.example-tempurl.com', 'x-forwarded-proto': 'https' } });
  srv.emit('request', { headers: { host: '127.0.0.1:9000' } });
  await new Promise((r) => setTimeout(r, 200));
  clearInterval(kw.timer);
  check('keepWarm (IIS): remembers the public https address (never 127.0.0.1) and wakes /api/version when idle', kw.origin() === 'https://cat-user-001-site1.example-tempurl.com' && hits.length >= 1 && hits.every((u) => u === 'https://cat-user-001-site1.example-tempurl.com/api/version'), JSON.stringify(hits));
  check('keepWarm: CAT_KEEPALIVE=0 turns it off', L.keepWarm(new EventEmitter(), { CAT_KEEPALIVE: '0' }, () => {}) === null);
}

// ---- 6.79.0 KataBump = direct port: forwarded headers come from the client, so they are not believed ----
const kbApp = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-node-kb-'));
for (const f of ['index.js', 'package.json']) fs.copyFileSync(path.join(root, 'node-panel', f), path.join(kbApp, f));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(kbApp, 'node_modules'), 'dir');
fs.writeFileSync(path.join(kbApp, 'cat-config.json'), JSON.stringify({ UUID, TROJAN_PASS: TPASS, CAT_HOST: 'katabump' }));
const KPORT = await freePort();
const kbProc = await new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['index.js'], { cwd: kbApp, env: { PATH: process.env.PATH, HOME: process.env.HOME, SERVER_PORT: String(KPORT), CAT_PANEL_SOURCE_DIR: src, P_SERVER_UUID: 'f00d' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  const t = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('KataBump launcher did not start: ' + out)); }, 30000);
  const on = (d) => { out += d; if (/listening on 0\.0\.0\.0:/.test(out)) { clearTimeout(t); resolve({ child, out: () => out }); } };
  child.stdout.on('data', on); child.stderr.on('data', on);
  child.on('exit', (c) => { clearTimeout(t); reject(new Error('KataBump launcher exited ' + c + ': ' + out)); });
});
check('KataBump: the launcher logs «host: katabump (direct port…)» from cat-config.json', /host: katabump \(direct port/.test(kbProc.out()) && /settings from cat-config\.json: UUID, TROJAN_PASS, CAT_HOST/.test(kbProc.out()), kbProc.out());
const kbReq = (method, p, headers = {}, body) => new Promise((resolve, reject) => {
  const data = body == null ? '' : JSON.stringify(body);
  const req = http.request('http://127.0.0.1:' + KPORT + p, { method, headers: Object.assign(body == null ? {} : { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) }, headers) }, (res) => { let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; }); res.on('end', () => resolve({ status: res.statusCode, body: b })); });
  req.on('error', reject); req.end(data);
});
const kbLines = async (headers) => { const r = await kbReq('GET', '/sub/' + UUID, headers); const text = r.body.includes('://') ? r.body : Buffer.from(r.body, 'base64').toString('utf8'); return text.split('\n').filter((l) => /^(vless|trojan):\/\//.test(l)).map(parse); };
const kbForgedTls = await kbLines({ 'x-forwarded-proto': 'https', 'x-forwarded-host': 'evil.example', 'x-forwarded-port': '443' });
check('KataBump: a client-sent X-Forwarded-Proto cannot turn the configs into TLS lines', kbForgedTls.length === 2 && kbForgedTls.every((p) => p.port === KPORT && p.q.get('security') === 'none'), JSON.stringify(kbForgedTls.map((p) => [p.host, p.port, p.q.get('security')])));
const kbForgedCf = await kbLines({ 'cf-ray': '8f00cc-FRA', 'cf-connecting-ip': '198.51.100.7', 'x-forwarded-proto': 'https', host: 'cat.kdns.fr' });
check('KataBump: cf-ray from a non-Cloudflare address does not unlock the clean-IP set', kbForgedCf.length === 2 && kbForgedCf.every((p) => p.q.get('security') === 'none'), kbForgedCf.length);
let kbLockedAt = 0;
for (let i = 1; i <= 12; i++) {
  const r = await kbReq('POST', '/api/login', { 'x-forwarded-proto': 'https', 'x-forwarded-for': '10.7.' + i + '.1', 'cf-ray': 'x', 'cf-connecting-ip': '10.8.' + i + '.1' }, { username: 'admin', password: 'wrong-' + i });
  if (r.status === 429) { kbLockedAt = i; break; }
}
check('KataBump: forged X-Forwarded-Proto + X-Forwarded-For + CF-Connecting-IP do not dodge the login lockout', kbLockedAt > 0, 'never locked');
await stop(kbProc);
fs.rmSync(kbApp, { recursive: true, force: true });

target.close();
for (const d of [src, app, bad, badDir]) fs.rmSync(d, { recursive: true, force: true });
if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
console.log('all node-host checks passed');
