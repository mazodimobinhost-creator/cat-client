// beta240 — real traffic through the Cloudflare Worker panel (workerd via miniflare, real cloudflare:sockets).
// Guards the «the panel opens but nothing connects» report:
//   1. compatibility dates ≥ 2026-03-17 deliver binary WebSocket frames as Blob — the handshake used to be
//      read as the text «[object Blob]» and every VLESS/Trojan connection was refused (compat date below is
//      deliberately after that switch);
//   2. a DIRECT socket that opens and closes with zero bytes (what Cloudflare does to a Worker dialing a
//      Cloudflare-hosted site) must be retried through ProxyIP with the same first payload, without losing
//      or reordering bytes and without dead-locking protocols where the client or the server speaks first;
//   3. SOCKS5 relays (with and without user/pass), the fixed SOCKS chain, /api/proxy-test and /api/ip-test;
//   4. /api/ip-test never calls a Cloudflare edge address «dead» from inside Cloudflare (skip:'edge').
// Usage: node scripts/panels/worker-realtraffic.test.mjs
// Local actors: TARGET (HTTP echo), DEADCF (opens TCP then closes with zero bytes = what a
// Cloudflare-hosted name does to a Worker's direct connect), PXIP (TCP relay to TARGET = a ProxyIP),
// SOCKS5 server (optional auth) that logs every CONNECT.
import net from 'node:net';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { Miniflare } from 'miniflare';
import WebSocket from 'ws';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = process.env.SRC || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const UUID = '11111111-2222-4333-8444-555555555555';
const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)));
let fails = 0;
const check = (n, c, x) => { console.log((c ? '✓ ' : '✗ ') + n + (x ? ' — ' + x : '')); if (!c) fails++; };

const target = net.createServer((s) => s.once('data', () => { s.end('HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\nhello'); }));
const TP = await listen(target);
const deadcf = net.createServer((s) => { s.on('data', () => {}); setTimeout(() => s.destroy(), 30); });
const DP = await listen(deadcf);
const pxip = net.createServer((s) => { const u = net.connect(TP, '127.0.0.1'); s.pipe(u); u.pipe(s); u.on('error', () => s.destroy()); s.on('error', () => u.destroy()); });
const PP = await listen(pxip);
const socksLog = [];
function socksServer(auth) {
  return net.createServer((c) => {
    let buf = Buffer.alloc(0), st = 0;
    c.on('error', () => {});
    c.on('data', function onData(d) {
      buf = Buffer.concat([buf, d]);
      if (st === 0 && buf.length >= 2 && buf.length >= 2 + buf[1]) { const m = [...buf.slice(2, 2 + buf[1])]; buf = buf.slice(2 + buf[1]); if (auth) { if (!m.includes(2)) return c.end(Buffer.from([5, 255])); c.write(Buffer.from([5, 2])); st = 1; } else { c.write(Buffer.from([5, 0])); st = 2; } }
      if (st === 1 && buf.length >= 2) { const ul = buf[1]; if (buf.length < 3 + ul) return; const pl = buf[2 + ul]; if (buf.length < 3 + ul + pl) return; const u = buf.slice(2, 2 + ul).toString(), p = buf.slice(3 + ul, 3 + ul + pl).toString(); buf = buf.slice(3 + ul + pl); const ok = u === auth.u && p === auth.p; c.write(Buffer.from([1, ok ? 0 : 1])); if (!ok) return c.end(); st = 2; }
      if (st === 2 && buf.length >= 5) {
        const at = buf[3]; let host, off;
        if (at === 1) { if (buf.length < 10) return; host = [...buf.slice(4, 8)].join('.'); off = 8; }
        else if (at === 3) { const l = buf[4]; if (buf.length < 7 + l) return; host = buf.slice(5, 5 + l).toString(); off = 5 + l; }
        else if (at === 4) { if (buf.length < 22) return; host = buf.slice(4, 20).toString('hex').match(/.{4}/g).join(':'); off = 20; }
        const port = buf.readUInt16BE(off); buf = buf.slice(off + 2); st = 3;
        socksLog.push(host + ':' + port);
        const realPort = (host === 'speed.cloudflare.com' || host === 'www.cloudflare.com') ? null : (host === 'site.example' ? TP : port);
        if (realPort === null) { c.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0])); return; }
        const u = net.connect(realPort, '127.0.0.1', () => { c.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 0])); if (buf.length) u.write(buf); c.removeListener('data', onData); c.pipe(u); u.pipe(c); });
        u.on('error', () => { try { c.end(Buffer.from([5, 5, 0, 1, 0, 0, 0, 0, 0, 0])); } catch (e) {} });
      }
    });
  });
}
const SP = await listen(socksServer(null));
const SAP = await listen(socksServer({ u: 'cat', p: 'p@ss' }));

const mf = new Miniflare({ modules: true, script: readFileSync(SRC, 'utf8'), compatibilityDate: '2026-04-01', compatibilityFlags: ['nodejs_compat'], kvNamespaces: ['CAT_KV'], bindings: { UUID, OPEN_PANEL: 'true', DATA_PLANE_ENABLED: process.env.DP || 'true' }, port: 0, host: '127.0.0.1' });
const base = (await mf.ready).toString().replace(/\/$/, '');
const inb = await (await fetch(base + '/api/inbounds')).text();
const VPATH = (inb.match(/\/vl\/[A-Za-z0-9_-]{4,64}/) || [])[0];
if (!VPATH) { console.error('✗ no VLESS path in /api/inbounds'); process.exit(1); }
const api = async (p, body, method) => { const r = await fetch(base + p, { method: method || (body ? 'POST' : 'GET'), headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }); const t = await r.text(); try { return JSON.parse(t); } catch (e) { return { status: r.status, text: t.slice(0, 200) }; } };

function vlessHeader(host, port, payload) {
  const u = Buffer.from(UUID.replace(/-/g, ''), 'hex');
  let a; if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) a = Buffer.from([1, ...host.split('.').map(Number)]); else a = Buffer.concat([Buffer.from([2, host.length]), Buffer.from(host)]);
  const p = Buffer.alloc(2); p.writeUInt16BE(port);
  return Buffer.concat([Buffer.from([0]), u, Buffer.from([0, 1]), p, a, payload || Buffer.alloc(0)]);
}
function vlessFetch(host, port, opts = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(base.replace('http', 'ws') + VPATH + '?ed=2560');
    let got = Buffer.alloc(0), done = false;
    const fin = (why) => { if (done) return; done = true; try { ws.close(); } catch (e) {} resolve({ body: got.length > 2 ? got.slice(2).toString() : '', why }); };
    const req = Buffer.from('GET / HTTP/1.1\r\nHost: ' + host + '\r\n\r\n');
    ws.on('open', () => { if (opts.split) { ws.send(vlessHeader(host, port)); setTimeout(() => ws.send(req), 50); } else ws.send(vlessHeader(host, port, req)); });
    ws.on('message', (d) => { got = Buffer.concat([got, Buffer.from(d)]); if (got.toString().includes('hello')) fin('ok'); });
    ws.on('close', (c, rs) => fin('closed ' + c + ' ' + rs)); ws.on('error', (e) => fin('err ' + e.message));
    setTimeout(() => fin('timeout'), opts.timeout || 12000);
  });
}

console.log('base', base, 'target', TP, 'deadcf', DP, 'pxip', PP, 'socks', SP, SAP);
// 1) plain direct
let r = await vlessFetch('127.0.0.1', TP);
check('direct target answers', r.body.includes('hello'), r.why);
// 2) CF-hosted-like target (opens then closes with no data) with a ProxyIP configured → must retry via ProxyIP
let s = await api('/api/settings', { proxyIps: ['127.0.0.1:' + PP] }, 'PUT');
check('settings saved (proxyIps)', s && s.ok !== false, JSON.stringify(s).slice(0, 160));
r = await vlessFetch('127.0.0.1', DP);
check('no-data close → retried via ProxyIP', r.body.includes('hello'), r.why);
r = await vlessFetch('127.0.0.1', DP, { split: true });
check('no-data close (header and payload in separate frames) → retried via ProxyIP', r.body.includes('hello'), r.why);
r = await vlessFetch('127.0.0.1', DP);
check('second visit of the same dead-direct host still works', r.body.includes('hello'), r.why);
// 3) SOCKS relay (no auth) for a domain target
s = await api('/api/settings', { proxyIps: ['socks5://127.0.0.1:' + SP] }, 'PUT');
check('settings saved (socks relay)', s && s.ok !== false, JSON.stringify(s).slice(0, 160));
socksLog.length = 0;
r = await vlessFetch('site.example', 80);
check('domain target rides the SOCKS relay', r.body.includes('hello') && socksLog.includes('site.example:80'), r.why + ' log=' + socksLog.join(','));
// 4) SOCKS with auth (special chars in password)
s = await api('/api/settings', { proxyIps: ['socks5://cat:' + encodeURIComponent('p@ss') + '@127.0.0.1:' + SAP] }, 'PUT');
check('settings saved (socks auth)', s && s.ok !== false, JSON.stringify(s).slice(0, 200));
socksLog.length = 0;
r = await vlessFetch('site.example', 80);
check('SOCKS relay with user/pass carries traffic', r.body.includes('hello') && socksLog.includes('site.example:80'), r.why + ' log=' + socksLog.join(','));
// 5) proxy-test & ip-test socks
let pt = await api('/api/proxy-test', {});
check('/api/proxy-test reports the auth relay ok', pt && Array.isArray(pt.results) && pt.results.length && pt.results.every((x) => x.ok), JSON.stringify(pt).slice(0, 300));
let it = await api('/api/ip-test', { tests: [{ addr: '127.0.0.1:' + SP, kind: 'socks' }, { addr: 'socks5://cat:p%40ss@127.0.0.1:' + SAP, kind: 'socks' }, { addr: 'socks5://cat:bad@127.0.0.1:' + SAP, kind: 'socks' }] });
const vals = Object.values((it && it.results) || {});
check('/api/ip-test socks: 2 ok + 1 bad-auth fails', vals.filter((x) => x.ok).length === 2 && vals.filter((x) => !x.ok).length === 1, JSON.stringify(it).slice(0, 400));
// 6) chain via settings.chain (socks) for every destination
s = await api('/api/settings', { proxyIps: [], chain: 'socks5://127.0.0.1:' + SP }, 'PUT');
check('settings saved (chain)', s && s.ok !== false, JSON.stringify(s).slice(0, 160));
socksLog.length = 0;
r = await vlessFetch('site.example', 80);
check('fixed exit chain (socks5) carries traffic', r.body.includes('hello') && socksLog.includes('site.example:80'), r.why + ' log=' + socksLog.join(','));

// ---- edge cases for the first-reply guard ----
await api('/api/settings', { proxyIps: ['127.0.0.1:' + PP], chain: '' }, 'PUT');
const crypto = await import('node:crypto');
const echo = net.createServer((sk) => sk.pipe(sk)); const EP = await listen(echo);
const twoStep = net.createServer((sk) => { let n = 0; sk.on('data', () => { if (++n === 2) sk.end('HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\nhello'); }); }); const TSP = await listen(twoStep);
const banner = net.createServer((sk) => { sk.write('SSH-2.0-cat\r\n'); sk.on('data', (d) => { if (d.toString().includes('ping')) sk.end('hello'); }); }); const BP = await listen(banner);
function rawTunnel(host, port, script) {
  return new Promise((resolve) => {
    const ws = new WebSocket(base.replace('http', 'ws') + VPATH + '?ed=2560');
    let got = Buffer.alloc(0), first = true, done = false;
    const fin = (why) => { if (done) return; done = true; try { ws.close(); } catch (e) {} resolve({ got, why }); };
    ws.on('open', () => script.open(ws));
    ws.on('message', (d) => { let b = Buffer.from(d); if (first) { b = b.slice(2); first = false; } got = Buffer.concat([got, b]); if (script.until(got)) fin('ok'); });
    ws.on('close', (c, r) => fin('closed ' + c + ' ' + r)); ws.on('error', (e) => fin('err ' + e.message));
    setTimeout(() => fin('timeout'), 15000);
  });
}
const big = crypto.randomBytes(3 * 1024 * 1024);
let e1 = await rawTunnel('127.0.0.1', EP, { open: (ws) => { ws.send(vlessHeader('127.0.0.1', EP, big.slice(0, 1000))); for (let o = 1000; o < big.length; o += 60000) ws.send(big.slice(o, o + 60000)); }, until: (g) => g.length >= big.length });
check('3 MB echo through the guard is byte-exact', e1.got.length === big.length && e1.got.equals(big), e1.why + ' len=' + e1.got.length);
let e2 = await rawTunnel('127.0.0.1', TSP, { open: (ws) => { ws.send(vlessHeader('127.0.0.1', TSP, Buffer.from('POST / HTTP/1.1\r\n\r\n'))); setTimeout(() => ws.send(Buffer.from('body')), 100); }, until: (g) => g.toString().includes('hello') });
check('server that answers only after the 2nd client frame (no deadlock)', e2.got.toString().includes('hello'), e2.why);
let e3 = await rawTunnel('127.0.0.1', BP, { open: (ws) => { ws.send(vlessHeader('127.0.0.1', BP)); }, until: (g) => g.toString().includes('SSH-2.0') });
var this_sent = false;
check('server-speaks-first banner reaches the client', e3.got.toString().includes('SSH-2.0'), e3.why);
let e4 = await rawTunnel('127.0.0.1', BP, { open: (ws) => { ws.send(vlessHeader('127.0.0.1', BP)); setTimeout(() => ws.send(Buffer.from('ping')), 1200); }, until: (g) => g.toString().includes('hello') });
check('banner server + late client frame (after the 800 ms wait) still works', e4.got.toString().includes('SSH-2.0') && e4.got.toString().includes('hello'), e4.why + ' ' + JSON.stringify(e4.got.toString()));
const burst = await Promise.all(Array.from({ length: 12 }, (_, i) => vlessFetch('127.0.0.1', i % 2 ? DP : TP)));
check('12 concurrent tunnels (half need the ProxyIP retry) all answer', burst.every((x) => x.body.includes('hello')), burst.map((x) => x.why).join(','));

// ---- IP test on the Cloudflare edge: CF addresses are skipped (tested in the browser), others probed for real ----
let ipt = await api('/api/ip-test', { tests: [{ addr: '104.16.132.229', port: 443, kind: 'clean' }, { addr: '127.0.0.1:' + TP, port: TP, kind: 'plain' }] });
const rr = (ipt && ipt.results) || {};
check('/api/ip-test: Cloudflare IP → skip:edge (not «dead»)', rr['104.16.132.229'] && rr['104.16.132.229'].skip === 'edge' && rr['104.16.132.229'].ok === null, JSON.stringify(rr));
check('/api/ip-test: non-Cloudflare address still probed for real', rr['127.0.0.1:' + TP] && rr['127.0.0.1:' + TP].ok === true, JSON.stringify(rr));
check('/api/ip-test reports the viewer country field', ipt && 'viewer' in ipt, JSON.stringify(ipt).slice(0, 120));
let dp = await api('/api/data-plane');
check('/api/data-plane says on when DATA_PLANE_ENABLED=true', dp && dp.on === true, JSON.stringify(dp));
await mf.dispose(); process.exit(fails ? 1 : 0);
