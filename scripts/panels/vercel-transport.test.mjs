/**
 * Panel on Vercel: the REAL adapter (assets/vercel/panel-adapter.mjs) + the REAL panel bundle,
 * served by Node exactly like a Vercel Function, carrying VLESS and Trojan over WebSocket to a
 * local HTTP target. Also pins the Vercel subscription (links, Clash, sing-box) to WebSocket —
 * the only transport Vercel's edge forwards (httpupgrade gets 101 and then no bytes, measured
 * live) — and checks that an httpupgrade client is refused at once instead of hanging.
 * Needs the `ws` package (devDependency). CAT_PANEL_WORKER may point at a built artifact.
 */
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workerPath = process.env.CAT_PANEL_WORKER || path.join(root, 'app/src/main/assets/panels/catclient.worker.js');
let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('✓ ' + name);
  else { failures++; console.log('✗ ' + name + (detail !== undefined ? ' — ' + String(detail).slice(0, 300) : '')); }
}

// Lay the files out like the app deploys them: api/index.mjs + lib/catpanel.mjs (+ node_modules).
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cat-vercel-'));
fs.mkdirSync(path.join(dir, 'api')); fs.mkdirSync(path.join(dir, 'lib'));
fs.copyFileSync(path.join(root, 'app/src/main/assets/vercel/panel-adapter.mjs'), path.join(dir, 'api/index.mjs'));
const panel = fs.readFileSync(workerPath, 'utf8');
const shimmed = panel.replace(/import\((['"])cloudflare:sockets\1\)/g, 'Promise.resolve(globalThis.__catSockets)');
check('panel bundle has exactly one cloudflare:sockets import to shim', (panel.match(/import\((['"])cloudflare:sockets\1\)/g) || []).length === 1);
fs.writeFileSync(path.join(dir, 'lib/catpanel.mjs'), shimmed);
fs.symlinkSync(path.join(root, 'node_modules'), path.join(dir, 'node_modules'), 'dir');

const UUID = '3f1c2b4a-5d6e-4f70-8a9b-0c1d2e3f4a5b';
const TPASS = 'cat-trojan-pass';
process.env.UUID = UUID; process.env.TROJAN_PASS = TPASS; process.env.DATA_PLANE_ENABLED = 'true';
delete process.env.BLOB_READ_WRITE_TOKEN; delete process.env.KV_REST_API_URL;

const target = http.createServer((q, s) => s.end('TARGET-OK ' + q.url));
await new Promise((r) => target.listen(0, '127.0.0.1', r));
const TPORT = target.address().port;
const { default: server } = await import(pathToFileURL(path.join(dir, 'api/index.mjs')).href);
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const PORT = server.address().port;
const HOST = 'cat-test.vercel.app';

const uuidBytes = Buffer.from(UUID.replace(/-/g, ''), 'hex');
const vless = (payload, id = uuidBytes) => { const h = Buffer.from('127.0.0.1'); return Buffer.concat([Buffer.from([0]), id, Buffer.from([0, 1, TPORT >> 8, TPORT & 255, 2, h.length]), h, payload]); };
const trojan = (payload) => { const hash = crypto.createHash('sha224').update(TPASS).digest('hex'); const h = Buffer.from('127.0.0.1'); return Buffer.concat([Buffer.from(hash + '\r\n'), Buffer.from([1, 3, h.length]), h, Buffer.from([TPORT >> 8, TPORT & 255]), Buffer.from('\r\n'), payload]); };
const request = (p) => Buffer.from('GET ' + p + ' HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n');

function overWs(p, frame) {
  return new Promise((resolve) => {
    const ws = new WebSocket('ws://127.0.0.1:' + PORT + p);
    ws.binaryType = 'arraybuffer';
    let buf = Buffer.alloc(0);
    const t = setTimeout(() => { try { ws.close(); } catch (_) { } resolve('TIMEOUT ' + buf); }, 6000);
    ws.onopen = () => ws.send(frame);
    ws.onmessage = (e) => { buf = Buffer.concat([buf, Buffer.from(e.data)]); if (buf.includes('TARGET-OK')) { clearTimeout(t); ws.close(); resolve(buf.toString('latin1')); } };
    ws.onclose = (e) => { clearTimeout(t); resolve(buf.length ? buf.toString('latin1') : 'CLOSED ' + e.code); };
    ws.onerror = () => { };
  });
}
/** Exactly what Xray's httpupgrade dialer sends; optional early data in Sec-WebSocket-Protocol. */
function overHttpUpgrade(p, frame, { early = false } = {}) {
  return new Promise((resolve) => {
    const sock = net.connect(PORT, '127.0.0.1');
    let buf = Buffer.alloc(0); let upgraded = false; let status = '';
    const t = setTimeout(() => { sock.destroy(); resolve({ status, body: 'TIMEOUT ' + buf.toString('latin1') }); }, 6000);
    sock.on('connect', () => {
      const ed = early ? 'Sec-WebSocket-Protocol: ' + frame.toString('base64url') + '\r\n' : '';
      sock.write('GET ' + p + ' HTTP/1.1\r\nHost: ' + HOST + '\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nUser-Agent: Mozilla/5.0\r\n' + ed + '\r\n');
    });
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!upgraded) {
        const end = buf.indexOf('\r\n\r\n');
        if (end < 0) return;
        status = buf.subarray(0, end).toString('latin1');
        buf = buf.subarray(end + 4); upgraded = true;
        if (!/^HTTP\/1\.1 101/.test(status)) { clearTimeout(t); sock.destroy(); resolve({ status, body: buf.toString('latin1') }); return; }
        if (!early) sock.write(frame);
      }
      if (buf.includes('TARGET-OK')) { clearTimeout(t); sock.destroy(); resolve({ status, body: buf.toString('latin1') }); }
    });
    sock.on('close', () => { clearTimeout(t); resolve({ status, body: buf.length ? buf.toString('latin1') : 'CLOSED' }); });
    sock.on('error', () => { });
  });
}

// ---- subscription: only the transport Vercel's edge really carries ----
// Measured live on Vercel (iad1, 2026-10-27, project cat-hu-probe): the edge answers 101 for a
// keyless «httpupgrade» request but then forwards ONLY valid WebSocket frames — raw bytes after
// 101 never arrive, in either direction. So a Vercel sub must never hand out httpupgrade (or
// grpc / xhttp / tcp) lines: they would import fine and never connect.
const subRes = await fetch('http://127.0.0.1:' + PORT + '/sub/' + UUID, { headers: { host: HOST, 'x-forwarded-host': HOST } });
const subBody = await subRes.text();
const lines = (subBody.includes('://') ? subBody : Buffer.from(subBody, 'base64').toString('utf8')).split('\n').filter(Boolean);
const parse = (l) => { const u = new URL(l.replace(/^(vless|trojan):\/\//, 'http://')); return { scheme: l.split('://')[0], host: u.hostname, port: Number(u.port), q: u.searchParams, name: decodeURIComponent(u.hash.slice(1)) }; };
const parsed = lines.map(parse);
check('vercel sub answers with links', subRes.status === 200 && lines.length >= 4, subRes.status + ' / ' + lines.length);
check('every Vercel line is WebSocket (the only transport the edge forwards)', parsed.length > 0 && parsed.every((p) => p.q.get('type') === 'ws'), [...new Set(parsed.map((p) => p.q.get('type')))].join(','));
// 6.69.41: SNI is the project, the bare «vercel.app» or the mixed-case project (Vercel accepts all three, live-tested)
const sniOk = (sni) => /(^|\.)vercel\.app$/i.test(String(sni || '')); // 6.69.43: + random *.vercel.app names (edge accepts any)
check('Vercel lines: :443, TLS, host == project, SNI in the vercel.app family, alpn http/1.1', parsed.every((p) => p.port === 443 && p.q.get('security') === 'tls' && sniOk(p.q.get('sni')) && p.q.get('host') === HOST && p.q.get('alpn') === 'http/1.1'), JSON.stringify(parsed[0] && Object.fromEntries(parsed[0].q)));
check('Vercel sub serves all three SNIs', ['vercel.app', HOST].every((x) => parsed.some((p) => p.q.get('sni') === x)) && parsed.some((p) => p.q.get('sni') !== HOST && sniOk(p.q.get('sni')) && p.q.get('sni') !== 'vercel.app'));
check('Vercel names are unique', new Set(parsed.map((p) => p.name)).size === parsed.length);
check('both VLESS and Trojan are served', parsed.some((p) => p.scheme === 'vless') && parsed.some((p) => p.scheme === 'trojan'));
const clash = await (await fetch('http://127.0.0.1:' + PORT + '/clash/' + UUID, { headers: { 'x-forwarded-host': HOST } })).text();
check('Clash/mihomo: no httpupgrade proxies on Vercel', /network: ws/.test(clash) && !/v2ray-http-upgrade|network: httpupgrade/.test(clash));
check('Clash/mihomo carries the bare vercel.app SNI too', /(servername|sni): "?vercel\.app"?/.test(clash), (clash.match(/(servername|sni): .*/) || [''])[0]);
const sb = JSON.parse(await (await fetch('http://127.0.0.1:' + PORT + '/singbox/' + UUID, { headers: { 'x-forwarded-host': HOST } })).text());
const sbTypes = new Set((sb.outbounds || []).filter((o) => o.transport).map((o) => o.transport.type));
check('sing-box carries the bare vercel.app SNI too', (sb.outbounds || []).some((o) => o.tls && o.tls.server_name === 'vercel.app'), JSON.stringify((sb.outbounds || []).map((o) => o.tls && o.tls.server_name).filter(Boolean).slice(0, 4)));
check('sing-box: Vercel outbounds use ws only', sbTypes.size === 1 && sbTypes.has('ws'), [...sbTypes].join(','));

// ---- data plane over the real adapter ----
const vPath = new URL('http://x' + parsed.find((p) => p.scheme === 'vless').q.get('path')).pathname;
const tPath = new URL('http://x' + parsed.find((p) => p.scheme === 'trojan').q.get('path')).pathname;
const r1 = await overWs(vPath, vless(request('/ws-vless')));
check('VLESS over WebSocket reaches the target', r1.includes('TARGET-OK /ws-vless'), r1.slice(0, 120));
const r2 = await overWs(tPath, trojan(request('/ws-trojan')));
check('Trojan over WebSocket reaches the target', r2.includes('TARGET-OK /ws-trojan'), r2.slice(0, 120));
const vEd = parsed.find((p) => p.scheme === 'vless').q.get('path');
const r3 = await overWs(vEd, vless(request('/ws-ed')));
check('VLESS over WebSocket on the advertised ?ed= path reaches the target', r3.includes('TARGET-OK /ws-ed'), r3.slice(0, 120));
const badId = Buffer.from(uuidBytes); badId[3] ^= 0xff;
const r4 = await overWs(vPath, vless(request('/nope'), badId));
check('WebSocket with a wrong UUID is refused', !r4.includes('TARGET-OK'), r4.slice(0, 80));
// a keyless (httpupgrade) client gets a clear non-101 answer at once instead of a 101 that then hangs
const t0 = Date.now();
const h1 = await overHttpUpgrade(vPath, vless(request('/hu-vless')));
check('httpupgrade request is refused quickly (no silent hang)', !/^HTTP\/1\.1 101/.test(h1.status) && !h1.body.includes('TARGET-OK') && Date.now() - t0 < 3000, h1.status + ' ' + (Date.now() - t0) + 'ms');

server.close(); target.close();
fs.rmSync(dir, { recursive: true, force: true });
if (failures) { console.log(failures + ' vercel-transport check(s) FAILED'); process.exit(1); }
console.log('all vercel-transport checks passed');
process.exit(0);
