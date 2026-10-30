/**
 * Panel 6.80.0 «🔒 Reality on KataBump» — the real launcher + adapter + panel, with a stand-in for the Xray binary
 * (CAT_XRAY_BIN: a tiny TLS server per inbound of the config the adapter writes) and a test CA (NODE_EXTRA_CA_CERTS):
 *   • off by default; turning it on needs confirm:true; only on a direct host;
 *   • one port carries both: a TLS ClientHello goes to the inbound of ITS SNI, HTTP / WebSocket still reach the panel;
 *   • the adapter proves the path through the public port before the panel lists any Reality line;
 *   • the sub gets one VLESS-Reality line per operator profile (pbk/sid/sni/flow/port), a user with a volume quota none;
 *   • pure helpers: ClientHello SNI parser (real Node ClientHello, split in two), unzip (deflate), X25519 key format,
 *     the Xray JSON (no way into the host's private ranges).
 * Needs `ws` (devDependency) and openssl (skipped with a note when missing).
 */
import http from 'node:http';
import net from 'node:net';
import tls from 'node:tls';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
const adapterPath = path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 400) : '')); }
}
const L = await import(pathToFileURL(path.join(root, 'node-panel/index.js')).href);

// ---- pure helpers (adapter imported in the layout a host gets) ----
const lay = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-reality-lay-'));
fs.mkdirSync(path.join(lay, 'api')); fs.mkdirSync(path.join(lay, 'lib'));
fs.copyFileSync(adapterPath, path.join(lay, 'api', 'index.mjs'));
fs.writeFileSync(path.join(lay, 'lib', 'catpanel.mjs'), L.patchPanel(fs.readFileSync(workerPath, 'utf8')));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(lay, 'node_modules'), 'dir');
process.env.CAT_PLATFORM = 'node';
const A = await import(pathToFileURL(path.join(lay, 'api', 'index.mjs')).href);

{
  // a real ClientHello from Node's TLS client
  const hello = await new Promise((resolve) => {
    const s = net.createServer((c) => { c.once('data', (d) => { resolve(d); c.destroy(); s.close(); }); });
    s.listen(0, '127.0.0.1', () => { const t = tls.connect({ host: '127.0.0.1', port: s.address().port, servername: 'www.microsoft.com' }); t.on('error', () => {}); });
  });
  check('ClientHello SNI parser: a real hello → its SNI', A.parseClientHelloSni(hello) === 'www.microsoft.com', A.parseClientHelloSni(hello));
  check('ClientHello SNI parser: half a hello → «wait for more» (null), HTTP → null', A.parseClientHelloSni(hello.subarray(0, 60)) === null && A.parseClientHelloSni(Buffer.from('GET / HTTP/1.1\r\n\r\n')) === null);
  const kp = A.realityKeyPair();
  check('X25519 pair in Xray text form (43 base64url chars each) + 8-hex shortId', /^[A-Za-z0-9_-]{43}$/.test(kp.priv) && /^[A-Za-z0-9_-]{43}$/.test(kp.pub) && /^[0-9a-f]{8}$/.test(kp.sid));
  const pubAgain = crypto.createPublicKey({ key: { kty: 'OKP', crv: 'X25519', d: kp.priv, x: kp.pub }, format: 'jwk' }).export({ format: 'jwk' }).x;
  check('the public half really belongs to the private half', pubAgain === kp.pub);
  const cfg = A.realityXrayJson(kp, ['6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4'], 30000);
  const ib = cfg.inbounds;
  check('Xray JSON: one VLESS-Reality inbound per profile on 127.0.0.1, vision flow, target = its SNI:443',
    ib.length === A.REALITY_PROFILES.length && ib.every((x, i) => x.listen === '127.0.0.1' && x.port === 30000 + i && x.settings.clients[0].flow === 'xtls-rprx-vision'
      && x.streamSettings.security === 'reality' && x.streamSettings.realitySettings.target === A.REALITY_PROFILES[i].sni + ':443' && x.streamSettings.realitySettings.privateKey === kp.priv));
  check('Xray JSON: the host\'s private ranges are blackholed', cfg.routing.rules[0].outboundTag === 'block' && cfg.routing.rules[0].ip.includes('127.0.0.0/8') && cfg.routing.rules[0].ip.includes('10.0.0.0/8'));
  let zipOk = 'skipped';
  try {
    const zf = path.join(lay, 't.zip');
    execFileSync('python3', ['-c', 'import zipfile,sys;z=zipfile.ZipFile(sys.argv[1],"w",zipfile.ZIP_DEFLATED);z.writestr("LICENSE","x"*50);z.writestr("xray","ELF"+"y"*5000);z.writestr("geoip.dat","g");z.close()', zf]);
    const out = A.unzipOne(fs.readFileSync(zf), 'xray');
    zipOk = out.length === 5003 && out.toString('latin1', 0, 4) === 'ELFy';
  } catch (e) { zipOk = 'skipped (' + e.message.slice(0, 60) + ')'; }
  check('unzipOne pulls the deflated «xray» out of a release-style zip', zipOk === true || String(zipOk).startsWith('skipped'), zipOk);
  if (zipOk !== true) console.log('  note: unzip check ' + zipOk);
}

// ---- the full path: launcher on a «KataBump» folder ----
let ossl = true;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-reality-'));
const key = path.join(tmp, 'k.pem');
const cert = path.join(tmp, 'c.pem');
try {
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert, '-days', '2', '-subj', '/CN=cat-reality-test',
    '-addext', 'subjectAltName=DNS:www.speedtest.net,DNS:www.microsoft.com,DNS:www.cloudflare.com,DNS:cdn.jsdelivr.net'], { stdio: 'ignore' });
} catch (e) { ossl = false; }
if (!ossl) {
  console.log('  note: openssl missing — the end-to-end Reality checks are skipped');
} else {
  // stand-in for Xray: «xray run -c cfg» → a TLS server on every inbound that answers with the SNI it got
  const fake = path.join(tmp, 'fake-xray.mjs');
  fs.writeFileSync(fake, `#!${process.execPath}
import fs from 'node:fs'; import tls from 'node:tls';
const cfg = JSON.parse(fs.readFileSync(process.argv[process.argv.indexOf('-c') + 1], 'utf8'));
fs.writeFileSync(${JSON.stringify(path.join(tmp, 'seen.json'))}, JSON.stringify(cfg));
for (const ib of cfg.inbounds) tls.createServer({ key: fs.readFileSync(${JSON.stringify(key)}), cert: fs.readFileSync(${JSON.stringify(cert)}) }, (c) => {
  c.on('data', () => c.end('REALITY-OK ' + c.servername + ' ' + ib.tag + '\\n')); c.on('error', () => {});
}).listen(ib.port, '127.0.0.1');
`, { mode: 0o755 });

  const src = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-reality-src-'));
  fs.copyFileSync(workerPath, path.join(src, 'catclient.worker.js'));
  fs.copyFileSync(adapterPath, path.join(src, 'cat-panel-adapter.mjs'));
  const shaOf = (f) => crypto.createHash('sha256').update(fs.readFileSync(path.join(src, f))).digest('hex');
  fs.writeFileSync(path.join(src, 'cat-node-panel.sha256'), shaOf('catclient.worker.js') + '  catclient.worker.js\n' + shaOf('cat-panel-adapter.mjs') + '  cat-panel-adapter.mjs\n');
  const app = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-reality-app-'));
  for (const f of ['index.js', 'package.json']) fs.copyFileSync(path.join(root, 'node-panel', f), path.join(app, f));
  fs.symlinkSync(path.join(root, 'node_modules'), path.join(app, 'node_modules'), 'dir');
  const UUID = '6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4';
  fs.writeFileSync(path.join(app, 'cat-config.json'), JSON.stringify({ UUID, TROJAN_PASS: 'cat-reality-pass', CAT_HOST: 'katabump' }));
  const PORT = await new Promise((r) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
  const proc = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['index.js'], { cwd: app, env: { PATH: process.env.PATH, HOME: process.env.HOME, SERVER_PORT: String(PORT), CAT_PANEL_SOURCE_DIR: src, CAT_XRAY_BIN: fake, NODE_EXTRA_CA_CERTS: cert }, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    const t = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('launcher did not start: ' + out)); }, 30000);
    const on = (d) => { out += d; if (/listening on 0\.0\.0\.0:/.test(out)) { clearTimeout(t); resolve({ child, out: () => out }); } };
    child.stdout.on('data', on); child.stderr.on('data', on);
    child.on('exit', (c) => { clearTimeout(t); reject(new Error('launcher exited ' + c + ': ' + out)); });
  });
  const req = (method, p, body, headers = {}) => new Promise((resolve, reject) => {
    const data = body == null ? '' : JSON.stringify(body);
    const r = http.request('http://127.0.0.1:' + PORT + p, { method, headers: Object.assign({ authorization: 'Bearer admin' }, body == null ? {} : { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) }, headers) }, (res) => {
      let b = ''; res.setEncoding('utf8'); res.on('data', (c) => { b += c; }); res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    r.on('error', reject); r.end(data);
  });
  const subLines = async (key2) => { const r = await req('GET', '/sub/' + key2); const t = r.body.includes('://') ? r.body : Buffer.from(r.body, 'base64').toString('utf8'); return t.split('\n').filter(Boolean); };
  try {
    let st = JSON.parse((await req('GET', '/api/node/reality')).body);
    check('Reality: offered on the direct host, OFF by default, no lines in the sub', st.allowed === true && st.on === false && st.status === 'off' && !(await subLines(UUID)).some((l) => /security=reality/.test(l)), JSON.stringify(st));
    const noConfirm = await req('POST', '/api/node/reality', { on: true });
    check('Reality: turning it on without confirm:true is refused', noConfirm.status === 400 && /confirm/.test(noConfirm.body), noConfirm.body);
    await req('POST', '/api/node/reality', { on: true, confirm: true });
    for (let i = 0; i < 60; i++) { st = JSON.parse((await req('GET', '/api/node/reality')).body); if (st.status !== 'starting') break; await new Promise((r) => setTimeout(r, 250)); }
    check('Reality: Xray started and the path was proven through the public port (real cert for the SNI)', st.on && st.status === 'running' && st.verified === true && /^[A-Za-z0-9_-]{43}$/.test(st.pub), JSON.stringify(st));
    const seen = JSON.parse(fs.readFileSync(path.join(tmp, 'seen.json'), 'utf8'));
    check('Reality: Xray knows the owner\'s UUID', seen.inbounds.every((x) => x.settings.clients.some((c) => c.id === UUID)));
    const lines = (await subLines(UUID)).filter((l) => /security=reality/.test(l));
    const ok = lines.length === 4 && lines.every((l) => {
      const u = new URL(l.replace(/^vless:\/\//, 'http://'));
      return u.username === UUID && Number(u.port) === PORT && u.searchParams.get('flow') === 'xtls-rprx-vision' && u.searchParams.get('pbk') === st.pub && u.searchParams.get('sid') === st.sid
        && u.searchParams.get('type') === 'tcp' && u.searchParams.get('fp') === 'chrome' && A.REALITY_PROFILES.some((p) => p.sni === u.searchParams.get('sni'));
    });
    check('Reality: one VLESS-Reality line per operator profile in the sub (pbk/sid/sni/flow/port)', ok, lines.join(' | '));
    const viaSni = (sni) => new Promise((resolve) => {
      const c = tls.connect({ host: '127.0.0.1', port: PORT, servername: sni, ca: fs.readFileSync(cert) }, () => c.write('hi'));
      let b = ''; c.on('data', (d) => { b += d; }); c.on('end', () => resolve(b.trim())); c.on('error', (e) => resolve('ERR ' + e.message));
    });
    const r1 = await viaSni('www.microsoft.com');
    const r2 = await viaSni('cdn.jsdelivr.net');
    check('one port, TLS → the Xray inbound of ITS SNI', r1 === 'REALITY-OK www.microsoft.com r-mtn' && r2 === 'REALITY-OK cdn.jsdelivr.net r-tci', r1 + ' / ' + r2);
    const health = await req('GET', '/health');
    const ver = await req('GET', '/api/version');
    check('one port, HTTP still reaches the panel while Reality runs', health.status === 200 && ver.status === 200 && /6\.\d+\.\d+/.test(ver.body), health.status + ' ' + ver.body.slice(0, 80));
    const paths = JSON.parse((await req('GET', '/api/settings')).body).paths;
    const up = await new Promise((resolve) => {
      const c = net.connect(PORT, '127.0.0.1', () => c.write('GET ' + paths.vlessPath + ' HTTP/1.1\r\nHost: 127.0.0.1:' + PORT + '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: Y2F0LXJlYWxpdHktdGVzdA==\r\nSec-WebSocket-Version: 13\r\n\r\n'));
      let b = ''; c.on('data', (d) => { b += d; if (b.includes('\r\n\r\n')) { c.destroy(); resolve(b.split('\r\n')[0]); } }); c.on('error', (e) => resolve('ERR ' + e.message));
      setTimeout(() => { c.destroy(); resolve('timeout ' + b.slice(0, 40)); }, 5000);
    });
    check('one port, the WebSocket tunnel still upgrades (101) while Reality runs', /^HTTP\/1\.1 101/.test(up), up);
    // a user with a volume quota is metered on WS only → no Reality lines, not in Xray's list
    const mk = JSON.parse((await req('POST', '/api/users', { name: 'metered', quotaGB: 5, days: 30 })).body);
    const free = JSON.parse((await req('POST', '/api/users', { name: 'free', days: 30 })).body);
    const mu = mk.user || mk; const fu = free.user || free;
    const mLines = (await subLines(mu.id)).filter((l) => /security=reality/.test(l));
    const midLines = (await subLines(UUID)).filter((l) => /security=reality/.test(l));
    for (let i = 0; i < 40; i++) { const s2 = JSON.parse(fs.readFileSync(path.join(tmp, 'seen.json'), 'utf8')); if (s2.inbounds[0].settings.clients.some((c) => c.id === fu.id)) break; await new Promise((r) => setTimeout(r, 250)); }
    await new Promise((r) => setTimeout(r, 500));
    const fLines = (await subLines(fu.id)).filter((l) => /security=reality/.test(l));
    check('a restart for a new user list keeps the lines in the sub meanwhile', midLines.length === 4, midLines.length);
    const seen2 = JSON.parse(fs.readFileSync(path.join(tmp, 'seen.json'), 'utf8'));
    const ids = seen2.inbounds[0].settings.clients.map((c) => c.id);
    check('a user with a volume quota gets no Reality lines and is not in Xray; a user without one is', mLines.length === 0 && fLines.length === 4 && ids.includes(fu.id) && !ids.includes(mu.id), JSON.stringify({ m: mLines.length, f: fLines.length, ids }));
    st = JSON.parse((await req('POST', '/api/node/reality', { on: false })).body);
    check('Reality off → status off and the lines leave the sub', st.on === false && st.status === 'off' && !(await subLines(UUID)).some((l) => /security=reality/.test(l)), JSON.stringify(st));
  } catch (e) {
    check('Reality end-to-end ran', false, (e && e.stack) || e);
  } finally {
    proc.child.kill('SIGTERM');
  }
}

console.log(failures ? '\n' + failures + ' FAILED' : '\nALL PASSED');
process.exit(failures ? 1 : 0);
