/**
 * Panel 6.69.44 — the Cloudflare front for a Vercel panel (assets/vercel/cf-front.js), end to end in
 * the REAL Workers runtime (workerd via miniflare): phone → front Worker → the REAL Vercel adapter +
 * the REAL panel bundle (Node, laid out like the app deploys it) → a local target. Covers the panel
 * page, the subscription, VLESS and Trojan WebSocket tunnels (with and without early data), a 3 MB
 * download byte for byte, redirect rewriting and the safety guards. Also checks the panel serves the
 * very same script (with the origin filled in) that the app deploys.
 * Needs `ws`, `miniflare` (devDependencies). CAT_PANEL_WORKER may point at a built artifact.
 */
import http from 'node:http';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Miniflare } from 'miniflare';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
const frontSrc = fs.readFileSync(path.join(root, 'app/src/main/assets/vercel/cf-front.js'), 'utf8');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 300) : '')); }
}

// ---- the Vercel side, exactly like vercel-transport.test.mjs ----
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-front-'));
fs.mkdirSync(path.join(dir, 'api')); fs.mkdirSync(path.join(dir, 'lib'));
fs.copyFileSync(path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs'), path.join(dir, 'api/index.mjs'));
fs.writeFileSync(path.join(dir, 'lib/catpanel.mjs'), fs.readFileSync(workerPath, 'utf8').replace(/import\((['"])cloudflare:sockets\1\)/g, 'Promise.resolve(globalThis.__catSockets)'));
fs.symlinkSync(path.join(root, 'node_modules'), path.join(dir, 'node_modules'), 'dir');
const UUID = '3f1c2b4a-5d6e-4f70-8a9b-0c1d2e3f4a5b';
const TPASS = 'cat-trojan-pass';
process.env.UUID = UUID; process.env.TROJAN_PASS = TPASS; process.env.DATA_PLANE_ENABLED = 'true';
delete process.env.BLOB_READ_WRITE_TOKEN; delete process.env.KV_REST_API_URL;

const BIG = crypto.randomBytes(3 * 1024 * 1024);
const BIG_SHA = crypto.createHash('sha256').update(BIG).digest('hex');
const target = http.createServer((q, s) => {
  if (q.url === '/big') { s.writeHead(200, { 'content-length': BIG.length }); s.end(BIG); return; }
  s.end('TARGET-OK ' + q.url);
});
await new Promise((r) => target.listen(0, '127.0.0.1', r));
const TPORT = target.address().port;
const { default: server } = await import(pathToFileURL(path.join(dir, 'api/index.mjs')).href);
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const VPORT = server.address().port;

// ---- the Cloudflare side: the shipped front script in workerd. The ONLY test change: it dials
// http://127.0.0.1:<port> instead of https://<project>.vercel.app (no TLS/DNS in the sandbox). ----
const testSrc = frontSrc.split("'https://' + origin + url.pathname").join("'http://127.0.0.1:" + VPORT + "' + url.pathname");
check('test rewrite touched exactly the origin URL', testSrc !== frontSrc && (frontSrc.match(/'https:\/\/' \+ origin \+ url\.pathname/g) || []).length === 1);
const mf = new Miniflare({ modules: true, script: testSrc, compatibilityDate: '2026-08-01', bindings: { CAT_VERCEL_ORIGIN: '127.0.0.2' }, port: 0, host: '127.0.0.1' });
const base = String(await mf.ready).replace(/\/$/, '');
const FRONT = new URL(base).host;

try {
  const hello = await (await fetch(base + '/__cat_front')).json();
  check('front answers its own health check with the origin', hello.ok === true && hello.front === 'cat-cf-front' && hello.origin === '127.0.0.2', JSON.stringify(hello));
  const ver = await (await fetch(base + '/api/version')).json();
  check('panel /api/version through the front', ver.ok === true && /^6\.\d+\.\d+$/.test(ver.version), JSON.stringify(ver));
  const page = await fetch(base + '/panel/');
  const html = await page.text();
  check('panel login page through the front', page.status === 200 && /<html/i.test(html), page.status);
  const sub = await fetch(base + '/sub/' + UUID);
  const subBody = await sub.text();
  const lines = (subBody.includes('://') ? subBody : Buffer.from(subBody, 'base64').toString('utf8')).split('\n').filter((l) => /^(vless|trojan):/.test(l));
  check('subscription through the front', sub.status === 200 && lines.length >= 4, sub.status + ' / ' + lines.length);

  const uuidBytes = Buffer.from(UUID.replace(/-/g, ''), 'hex');
  const hostB = Buffer.from('127.0.0.1');
  const vless = (payload) => Buffer.concat([Buffer.from([0]), uuidBytes, Buffer.from([0, 1, TPORT >> 8, TPORT & 255, 2, hostB.length]), hostB, payload]);
  const trojan = (payload) => Buffer.concat([Buffer.from(crypto.createHash('sha224').update(TPASS).digest('hex') + '\r\n'), Buffer.from([1, 3, hostB.length]), hostB, Buffer.from([TPORT >> 8, TPORT & 255]), Buffer.from('\r\n'), payload]);
  const req = (p) => Buffer.from('GET ' + p + ' HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n');
  const { WebSocket } = await import('ws');
  function overFront(p, frame, { early = false, until = (b) => b.includes('TARGET-OK'), ms = 8000 } = {}) {
    return new Promise((resolve) => {
      const ws = early ? new WebSocket('ws://' + FRONT + p, [frame.toString('base64url')]) : new WebSocket('ws://' + FRONT + p);
      ws.binaryType = 'nodebuffer';
      let buf = Buffer.alloc(0); let proto = '';
      const t = setTimeout(() => { try { ws.terminate(); } catch (_) { } resolve({ buf, proto, why: 'timeout' }); }, ms);
      ws.on('upgrade', (res) => { proto = String(res.headers['sec-websocket-protocol'] || ''); });
      ws.on('open', () => { if (!early) ws.send(frame); });
      ws.on('message', (d) => { buf = Buffer.concat([buf, d]); if (until(buf)) { clearTimeout(t); ws.close(); resolve({ buf, proto, why: 'done' }); } });
      ws.on('close', (code, reason) => { clearTimeout(t); resolve({ buf, proto, why: 'closed ' + code + ' ' + reason }); });
      ws.on('error', (e) => { clearTimeout(t); resolve({ buf, proto, why: 'error ' + e.message }); });
    });
  }
  const v1 = await overFront('/ws', vless(req('/v1')));
  check('VLESS over WebSocket through the front reaches the target', v1.buf.includes('TARGET-OK /v1'), v1.why + ' ' + v1.buf.toString('latin1').slice(0, 120));
  const ed = vless(req('/early'));
  const v2 = await overFront('/ws?ed=2560', ed, { early: true });
  check('VLESS with early data (Sec-WebSocket-Protocol) through the front', v2.buf.includes('TARGET-OK /early'), v2.why + ' ' + v2.buf.toString('latin1').slice(0, 120));
  check('front echoes the early-data subprotocol (strict clients need it)', v2.proto === ed.toString('base64url'), v2.proto.slice(0, 40));
  const t1 = await overFront('/trojan', trojan(req('/t1')));
  check('Trojan over WebSocket through the front reaches the target', t1.buf.includes('TARGET-OK /t1'), t1.why + ' ' + t1.buf.toString('latin1').slice(0, 120));
  const headEnd = (b) => b.indexOf('\r\n\r\n');
  const big = await overFront('/ws', vless(req('/big')), { until: (b) => { const vh = 2; const h = headEnd(b.subarray(vh)); return h >= 0 && b.length - vh - h - 4 >= BIG.length; }, ms: 30000 });
  const raw = big.buf.subarray(2);
  const body = raw.subarray(headEnd(raw) + 4, headEnd(raw) + 4 + BIG.length);
  check('3 MB download through the front arrives byte for byte', body.length === BIG.length && crypto.createHash('sha256').update(body).digest('hex') === BIG_SHA, big.why + ' got ' + body.length);
  const bad = await overFront('/ws', Buffer.concat([Buffer.from([0]), crypto.randomBytes(16), Buffer.from([0, 1, 0, 80, 1, 1, 1, 1, 1])]), { ms: 4000 });
  check('a wrong UUID still gets nothing through the front', !bad.buf.includes('TARGET-OK'), bad.why);
} finally {
  await mf.dispose();
}

// ---- guards ----
async function frontWith(bindings, scriptText = frontSrc) {
  const m = new Miniflare({ modules: true, script: scriptText, compatibilityDate: '2026-08-01', bindings, port: 0, host: '127.0.0.1' });
  const u = String(await m.ready).replace(/\/$/, '');
  return { m, u };
}
{
  const { m, u } = await frontWith({});
  const r = await fetch(u + '/');
  check('without an origin the front refuses (no open proxy)', r.status === 500 && /CAT_VERCEL_ORIGIN/.test(await r.text()), r.status);
  await m.dispose();
}
{
  const { m, u } = await frontWith({ CAT_VERCEL_ORIGIN: 'bad host!/x' });
  check('an invalid origin is refused', (await fetch(u + '/')).status === 500);
  await m.dispose();
}
{
  const { m, u } = await frontWith({ CAT_VERCEL_ORIGIN: '127.0.0.1' });
  check('the front refuses to loop to itself', (await fetch(u + '/')).status === 508);
  await m.dispose();
}
{
  // Redirects from Vercel point back at the front, not at the filtered vercel.app name.
  const redir = http.createServer((q, s) => { s.writeHead(302, { location: 'https://127.0.0.2/panel/?x=1' }); s.end(); });
  await new Promise((r) => redir.listen(0, '127.0.0.1', r));
  const src = frontSrc.split("'https://' + origin + url.pathname").join("'http://127.0.0.1:" + redir.address().port + "' + url.pathname");
  const { m, u } = await frontWith({ CAT_VERCEL_ORIGIN: '127.0.0.2' }, src);
  const r = await fetch(u + '/x', { redirect: 'manual' });
  check('redirects to the origin are rewritten to the front', r.status === 302 && r.headers.get('location') === 'https://' + new URL(u).hostname + '/panel/?x=1', r.headers.get('location'));
  await m.dispose(); redir.close();
}

// ---- the panel hands out the very same script, origin filled in ----
globalThis.__catTestNoNet = true;
const T = (await import(pathToFileURL(workerPath).href))._testing;
const served = T.cfFrontScript('cat-panel-v7k2m.vercel.app');
check('panel serves the shipped front script with the origin filled in', served === frontSrc.replace("'__CAT_ORIGIN__'", "'cat-panel-v7k2m.vercel.app'"), served.length + ' vs ' + frontSrc.length);
check('panel refuses to fill in a non-hostname origin', T.cfFrontScript('evil.com/\'x') === frontSrc);

server.close(); target.close();
if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
console.log('all vercel-cf-front checks passed');
process.exit(0);
