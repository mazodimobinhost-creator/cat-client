/**
 * beta228 — SmarterASP's «60-Day Free Linux Trial» (W60LX-US) runs the SAME zip the app saves for SmarterASP as a
 * plain Node app (start command `node index.js`, the host gives PORT; no IIS, so web.config is just ignored).
 * Real launcher process from the exact zip layout, with the vendored `ws` (no npm install), and checks:
 *   • it starts on 0.0.0.0:$PORT without CAT_BEHIND_IIS, cat-config.json says CAT_HOST=smarterasp;
 *   • /api/version, /sub/<uuid> (configs) and a real WebSocket 101 on the first config's path;
 *   • GET /cat-config.json never returns the UUID (the launcher itself does not serve its folder).
 * beta230: started through server.js (the runtime's default Startup file); /health = the Health path.
 * beta231: the Linux runtime loads the Startup file from its own runner (argv[1] is not index.js) — the panel must
 *   still start; and a fresh folder with no GitHub access starts from the zip's cat-bundled/ (SHA-256 checked).
 * beta232: the site folder is READ-ONLY for the runtime → the zip's prebuilt cat-code runs as is; storage in a temp dir.
 * Usage: node scripts/panels/smarterasp-linux.test.mjs
 */
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 400) : '')); }
}
const freePort = () => new Promise((r) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
function copyTree(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name); const b = path.join(to, e.name);
    if (e.isDirectory()) copyTree(a, b); else fs.copyFileSync(a, b);
  }
}

// the release files the launcher would download (served locally, SHA-256 checked like the real thing)
const src = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-sa-src-'));
fs.copyFileSync(workerPath, path.join(src, 'catclient.worker.js'));
fs.copyFileSync(path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs'), path.join(src, 'cat-panel-adapter.mjs'));
const shaOf = (f) => crypto.createHash('sha256').update(fs.readFileSync(path.join(src, f))).digest('hex');
fs.writeFileSync(path.join(src, 'cat-node-panel.sha256'), shaOf('catclient.worker.js') + '  catclient.worker.js\n' + shaOf('cat-panel-adapter.mjs') + '  cat-panel-adapter.mjs\n');

// the zip layout NodeHostController.files(SMARTERASP) writes
const assets = path.join(root, 'app/src/main/assets/node-panel');
const app = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-sa-linux-'));
for (const f of ['index.js', 'package.json', 'README.md']) fs.copyFileSync(path.join(assets, f), path.join(app, f));
fs.copyFileSync(path.join(assets, 'smarterasp/web.config'), path.join(app, 'web.config'));
fs.copyFileSync(path.join(assets, 'smarterasp/server.js'), path.join(app, 'server.js'));
fs.mkdirSync(path.join(app, 'App_Data'));
fs.copyFileSync(path.join(assets, 'smarterasp/App_Data-README.txt'), path.join(app, 'App_Data/README.txt'));
copyTree(path.join(assets, 'smarterasp/ws'), path.join(app, 'node_modules/ws'));
const UUID = '5d2b8f3a-1c4e-4f6a-9b7d-0e1f2a3b4c5d';
fs.writeFileSync(path.join(app, 'cat-config.json'), JSON.stringify({ UUID, TROJAN_PASS: 'sa-pass', CAT_HOST: 'smarterasp' }, null, 2));

const PORT = await freePort();
const child = await new Promise((resolve, reject) => {
  const c = spawn(process.execPath, ['server.js'], { cwd: app, env: { PATH: process.env.PATH, HOME: process.env.HOME, PORT: String(PORT), NODE_PATH: '', CAT_PANEL_SOURCE_DIR: src }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  const t = setTimeout(() => { c.kill('SIGKILL'); reject(new Error('launcher did not start: ' + out)); }, 30000);
  const on = (d) => { out += d; if (/listening on /.test(out)) { clearTimeout(t); resolve({ c, out: () => out }); } };
  c.stdout.on('data', on); c.stderr.on('data', on);
  c.on('exit', (code) => { clearTimeout(t); reject(new Error('launcher exited ' + code + ': ' + out)); });
});
const get = (p) => new Promise((resolve, reject) => {
  http.get({ host: '127.0.0.1', port: PORT, path: p, headers: { host: 'site1.example.test' } }, (res) => {
    let b = ''; res.setEncoding('utf8'); res.on('data', (d) => { b += d; }); res.on('end', () => resolve({ status: res.statusCode, body: b }));
  }).on('error', reject);
});

try {
  const log = child.out();
  check('listens on 0.0.0.0:$PORT like a Linux Node app (no IIS)', log.includes('listening on 0.0.0.0:' + PORT) && !/behind IIS/.test(log), log);
  const v = await get('/api/version');
  check('/api/version answers cat-panel', v.status === 200 && v.body.includes('cat-panel'), v.body);
  const sub = await get('/sub/' + UUID);
  const text = sub.body.includes('://') ? sub.body : Buffer.from(sub.body.trim(), 'base64').toString('utf8');
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  check('/sub/<uuid> gives configs', sub.status === 200 && lines.some((l) => l.startsWith('vless://')), sub.body.slice(0, 200));
  const first = lines.find((l) => l.startsWith('vless://')) || '';
  const wsPath = decodeURIComponent((/[?&]path=([^&#]+)/.exec(first) || [])[1] || '/');
  const status = await new Promise((resolve) => {
    const s = net.connect(PORT, '127.0.0.1', () => {
      s.write('GET ' + wsPath + ' HTTP/1.1\r\nHost: site1.example.test\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ' + crypto.randomBytes(16).toString('base64') + '\r\n\r\n');
    });
    let b = ''; s.on('data', (d) => { b += d; const m = /^HTTP\/1\.1 (\d+)/.exec(b); if (m) { s.destroy(); resolve(Number(m[1])); } });
    s.on('error', () => resolve(0)); setTimeout(() => { s.destroy(); resolve(-1); }, 8000);
  });
  check('WebSocket on the config path → 101 (vendored ws, no npm install)', status === 101, status);
  const h = await get('/health');
  check('started through server.js (the Linux runtime default) and /health answers 200 for its Health path', h.status === 200, h.status + ' ' + h.body.slice(0, 80));
  const leak = await get('/cat-config.json');
  check('GET /cat-config.json never returns the UUID', !leak.body.includes(UUID), leak.status + ' ' + leak.body.slice(0, 120));
} finally {
  child.c.kill('SIGKILL');
}

// beta231/232 — the Linux runtime loads the Startup file from its own runner (CommonJS require, argv[1] = the
// runner), runs «from website files» that may be READ-ONLY, and GitHub may be unreachable:
//   A) the zip as the app now writes it: cat-code/ prebuilt (patched like the launcher does) → starts with no write;
//   B) an older zip with cat-bundled/ and nothing prebuilt → code goes to a temp folder linked to ./node_modules.
const L = await import(pathToFileURL(path.join(assets, 'index.js')).href);
const runner = path.join(os.tmpdir(), 'cat-host-runner-' + process.pid + '.cjs');
fs.writeFileSync(runner, "require(process.argv[2]);\n");
for (const mode of ['prebuilt', 'bundled']) {
  const app2 = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-sa-ro-' + mode + '-'));
  copyTree(app, app2);
  for (const d of ['cat-code', 'cat-data']) fs.rmSync(path.join(app2, d), { recursive: true, force: true });
  if (mode === 'prebuilt') {
    // what NodeHostController.files(SMARTERASP) zips: cat-code/{api/index.mjs, lib/catpanel.mjs, version.txt}
    fs.mkdirSync(path.join(app2, 'cat-code/api'), { recursive: true }); fs.mkdirSync(path.join(app2, 'cat-code/lib'));
    fs.copyFileSync(path.join(src, 'cat-panel-adapter.mjs'), path.join(app2, 'cat-code/api/index.mjs'));
    fs.writeFileSync(path.join(app2, 'cat-code/lib/catpanel.mjs'), L.patchPanel(fs.readFileSync(path.join(src, 'catclient.worker.js'), 'utf8')));
    fs.writeFileSync(path.join(app2, 'cat-code/version.txt'), 'bundled-1.10.0-test\n');
  } else {
    fs.mkdirSync(path.join(app2, 'cat-bundled'));
    for (const f of ['catclient.worker.js', 'cat-panel-adapter.mjs', 'cat-node-panel.sha256']) fs.copyFileSync(path.join(src, f), path.join(app2, 'cat-bundled', f));
    fs.writeFileSync(path.join(app2, 'cat-bundled', 'version.txt'), '1.10.0-test\n');
  }
  const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-sa-tmp-'));
  spawnSync('chmod', ['-R', 'a-w', app2]);
  const P2 = await freePort();
  let out2 = '';
  const c2 = spawn(process.execPath, [runner, path.join(app2, 'server.js')], { cwd: app2, env: { PATH: process.env.PATH, HOME: process.env.HOME, TMPDIR: tmpHome, PORT: String(P2), NODE_PATH: '', CAT_REPO: 'invalid.invalid/none', CAT_KEEPALIVE: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
  c2.stdout.on('data', (d) => { out2 += d; }); c2.stderr.on('data', (d) => { out2 += d; });
  const up = await new Promise((resolve) => {
    const t = setTimeout(() => resolve(false), 40000);
    const iv = setInterval(() => { if (/listening on /.test(out2)) { clearTimeout(t); clearInterval(iv); resolve(true); } }, 100);
    c2.on('exit', () => { clearTimeout(t); clearInterval(iv); resolve(false); });
  });
  try {
    const roOk = (() => { try { fs.writeFileSync(path.join(app2, '.probe'), '1'); return false; } catch (_) { return true; } })();
    check(mode + ': read-only folder, loaded by a host runner, no GitHub → the panel starts', up && roOk && /loaded by the host runner/.test(out2) && !/could not start/.test(out2), out2);
    if (mode === 'prebuilt') check('prebuilt: runs the zip\'s cat-code as is (update skipped, nothing written)', /running the cached panel bundled-1\.10\.0-test/.test(out2) && !/read-only — panel code goes to/.test(out2), out2);
    else check('bundled: code goes to a temp folder (node_modules linked) and is SHA-256 checked', /read-only — panel code goes to /.test(out2) && /first start: using the panel that came in the zip/.test(out2) && /ready \(SHA-256 checked\)/.test(out2), out2);
    check(mode + ': storage falls back to a temp folder, not «none»', /is not writable — storage: /.test(out2), out2);
    if (up) {
      const h2 = await new Promise((resolve) => { http.get({ host: '127.0.0.1', port: P2, path: '/health' }, (res) => { res.resume(); resolve(res.statusCode); }).on('error', () => resolve(0)); });
      check(mode + ': /health 200', h2 === 200, h2);
      const sub2 = await new Promise((resolve) => { http.get({ host: '127.0.0.1', port: P2, path: '/sub/' + UUID, headers: { host: 'site1.example.test' } }, (res) => { let b = ''; res.on('data', (d) => { b += d; }); res.on('end', () => resolve(b)); }).on('error', () => resolve('')); });
      check(mode + ': /sub/<uuid> gives configs', sub2.includes('vless://'), sub2.slice(0, 120));
    }
  } finally { c2.kill('SIGKILL'); spawnSync('chmod', ['-R', 'u+w', app2]); }
}
fs.rmSync(runner, { force: true });
console.log(failures ? 'smarterasp-linux: ' + failures + ' failed' : 'smarterasp-linux: all checks passed');
process.exit(failures ? 1 : 0);
