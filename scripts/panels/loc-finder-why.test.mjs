/**
 * 6.82.1 exit finder — pinned against the real worker with a fake KV and an injected socket layer:
 *   - a feed entry inside Cloudflare's own ranges is skipped (a Worker cannot dial it) and counted, never "tested";
 *   - TCP ok but TLS/HTTP failing on speed.cloudflare.com → one retry on www.cloudflare.com before calling it dead;
 *   - the panel's own ProxyIPs are candidates too, keep their port and are stored under their REAL country;
 *   - when nothing is found the answer carries the failure reasons and the page prints them («علت: …»).
 * Usage: node scripts/panels/loc-finder-why.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = async () => new Response('', { status: 404 });
const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '71111111-2222-4333-8444-555555555555' };
// 104.16.1.1 = Cloudflare-owned (must be skipped); 10.3.0.1 answers 403 on speed.cloudflare.com but 200 on www.cloudflare.com
// (really in DE); 10.3.0.2 refuses; 10.3.0.3 answers 403 on both hosts.
await ENV.CAT_KV.put('cat_prepo_cache_v1', JSON.stringify({ ts: Date.now(), per: {}, ips: ['104.16.1.1:443#DE', '10.3.0.1:2053#DE', '10.3.0.2:443#DE', '10.3.0.3:8443#DE'] }));
const LOC = { '10.3.0.1': 'DE', '10.3.0.3': 'DE', '10.9.0.9': 'CA' };
const dials = []; const hosts = [];
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://why.workers.dev' + p, init), ENV, { waitUntil(){} });
}
T.__setSockets({
  connect(opts) {
    const ip = String(opts.hostname);
    dials.push(ip + ':' + opts.port);
    if (!LOC[ip]) throw new Error('connection refused');
    let sni = '';
    let ctl;
    const tls = {
      readable: new ReadableStream({ start(c) { ctl = c; } }),
      writable: new WritableStream({ write() {
        const okHost = ip === '10.9.0.9' || (ip === '10.3.0.1' && sni === 'www.cloudflare.com');
        ctl.enqueue(new TextEncoder().encode(okHost
          ? 'HTTP/1.1 200 OK\r\n\r\nfl=1\nip=198.51.100.7\ncolo=FRA\nloc=' + LOC[ip] + '\n'
          : 'HTTP/1.1 403 Forbidden\r\ncontent-type: text/html\r\n\r\n<html>blocked</html>'));
        ctl.close();
      } }),
    };
    return { opened: Promise.resolve(), startTls(o) { sni = o.expectedServerHostname; hosts.push(ip + '@' + sni); return tls; }, readable: tls.readable, writable: tls.writable, close() {} };
  },
});
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
await req('/api/settings', { method: 'PUT', headers: auth, body: { proxyIps: '10.9.0.9:3118' } });
const j = await (await req('/api/locations/fetch', { method: 'POST', headers: auth, body: { cc: 'DE', kind: 'px', want: 3 } })).json();
check('finder answers ok', j.ok === true, JSON.stringify(j).slice(0, 200));
check('Cloudflare-owned feed IP is never dialed', !dials.some((d) => d.startsWith('104.16.1.1')), dials.join(','));
check('…and is counted as skipped, not tested', j.skippedCf === 1, String(j.skippedCf));
check('403 on the first host → retried on www.cloudflare.com → found in DE', (j.added || []).some((e) => e.addr === '10.3.0.1:2053' && e.cc === 'DE'), JSON.stringify(j.added));
check('retry host really used', hosts.includes('10.3.0.1@speed.cloudflare.com') && hosts.includes('10.3.0.1@www.cloudflare.com'), hosts.join(','));
check('refused entry is not retried on the second host', !hosts.includes('10.3.0.2@www.cloudflare.com'));
check('own ProxyIP tested with ITS port and kept under its real country', (j.other || []).some((e) => e.addr === '10.9.0.9:3118' && e.cc === 'CA'), JSON.stringify(j.other));
check('failure reasons are reported', j.errors && Object.keys(j.errors).some((k) => /refused/.test(k)) && Object.keys(j.errors).some((k) => /403/.test(k)), JSON.stringify(j.errors));
const html = await (await req('/panel/', { headers: auth })).text();
check('page prints the reasons line', html.includes('function locWhy(') && html.includes('locWhy(j,a)'));
check('no client template literal slipped in', !/function locWhy\([^)]*\)\{[^}]*\$\{/.test(html));
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('loc-finder-why: all checks passed');
