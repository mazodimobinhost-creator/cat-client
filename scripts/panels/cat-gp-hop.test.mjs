/**
 * 🌐 Cat GP second hop (panel 6.82.2): the user's own Apps Script may hand each request to /gp/<uuid> so sites see a
 * Cloudflare exit. Pinned against the real worker with a mocked fetch:
 *   - only the owner / an enabled user UUID; anything else → 404 and nothing is fetched;
 *   - wire format {u,m,h,b,ct} → {s,h,b} (base64, byte-exact), Set-Cookie stays a list;
 *   - CF / forwarding / proxy headers are dropped, a loop marker is added and an incoming one is refused;
 *   - bare IPs, the panel itself, non-http(s) URLs and odd methods are refused without fetching.
 * Usage: node scripts/panels/cat-gp-hop.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

let calls = [];
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  const headers = new Headers(init.headers || {});
  const body = init.body ? Buffer.from(init.body) : null;
  calls.push({ url, method: (init.method || 'GET').toUpperCase(), headers, body, redirect: init.redirect });
  if (url.startsWith('https://site.example/')) {
    const h = new Headers({ 'content-type': 'application/octet-stream', 'x-site': 'yes' });
    h.append('set-cookie', 'a=1; Path=/');
    h.append('set-cookie', 'b=2; Path=/');
    return new Response(new Uint8Array([0, 1, 2, 250, 255, ...Buffer.from(body ? 'echo:' + body.toString() : 'hello')]), { status: 203, headers: h });
  }
  return new Response('', { status: 404 });
};
const worker = (await import(workerPath)).default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const UUID = '41111111-2222-4333-8444-555555555555';
const HOST = 'cat-gp.example.workers.dev';
const env = { CAT_KV: new FakeKV(), UUID };
const ctx = { waitUntil() {} };
const call = (p, init = {}) => worker.fetch(new Request('https://' + HOST + p, init), env, ctx);
const post = (p, o, headers = {}) => call(p, { method: 'POST', body: JSON.stringify(o), headers: Object.assign({ 'content-type': 'application/json' }, headers) });
const siteCalls = () => calls.filter((c) => c.url.startsWith('https://site.example/'));

{
  calls = [];
  const r = await post('/gp/' + UUID, { u: 'https://site.example/x?y=1', m: 'GET', h: { Accept: 'text/html', 'CF-Connecting-IP': '5.6.7.8', 'X-Forwarded-For': '1.1.1.1', Host: 'evil', 'Proxy-Authorization': 'x' } });
  const j = await r.json();
  check('owner UUID relays and answers JSON', r.status === 200 && r.headers.get('x-cat-gp') === '1' && j.s === 203, JSON.stringify(j).slice(0, 120));
  const bytes = Buffer.from(j.b, 'base64');
  check('body arrives byte-exact (base64)', bytes.length === 10 && bytes[3] === 250 && bytes.subarray(5).toString() === 'hello');
  check('headers come back, Set-Cookie as a list', j.h['x-site'] === 'yes' && Array.isArray(j.h['set-cookie']) && j.h['set-cookie'].length === 2, JSON.stringify(j.h));
  const c = siteCalls()[0];
  check('exactly the requested URL is fetched once', siteCalls().length === 1 && c.url === 'https://site.example/x?y=1' && c.method === 'GET');
  check('CF / forwarding / proxy / host headers are dropped', !c.headers.get('cf-connecting-ip') && !c.headers.get('x-forwarded-for') && !c.headers.get('proxy-authorization') && c.headers.get('accept') === 'text/html');
  check('loop marker added, redirects not followed by default', c.headers.get('x-cat-gp-hop') === '1' && c.redirect === 'manual');
}
{
  calls = [];
  const r = await post('/gp/' + UUID, { u: 'https://site.example/up', m: 'POST', h: {}, b: Buffer.from('abc').toString('base64'), ct: 'text/plain' });
  const j = await r.json();
  const c = siteCalls()[0];
  check('POST body + content type pass through', c && c.body.toString() === 'abc' && c.headers.get('content-type') === 'text/plain' && Buffer.from(j.b, 'base64').subarray(5).toString() === 'echo:abc');
}
{
  const ON = '81111111-2222-4333-8444-555555555555', OFF = '91111111-2222-4333-8444-555555555555';
  await env.CAT_KV.put('cat:v6:users', JSON.stringify([{ id: ON, name: 'me', enabled: true }, { id: OFF, name: 'old', enabled: false }]));
  calls = [];
  const r1 = await post('/gp/' + ON, { u: 'https://site.example/', m: 'GET' });
  check('enabled user UUID may use it', r1.status === 200 && siteCalls().length === 1);
  calls = [];
  const r2 = await post('/gp/' + OFF, { u: 'https://site.example/', m: 'GET' });
  check('disabled user UUID → 404, nothing fetched', r2.status === 404 && siteCalls().length === 0, String(r2.status));
  await env.CAT_KV.delete('cat:v6:users');
}
{
  const g = await call('/gp/' + UUID);
  check('GET answers a health line (for the app\'s test)', g.status === 200 && (await g.json()).relay === 'cat-gp-hop');
  calls = [];
  const refused = [
    ['wrong UUID', '/gp/51111111-2222-4333-8444-555555555555', { u: 'https://site.example/' }, {}, 404],
    ['no UUID', '/gp/', { u: 'https://site.example/' }, {}, 404],
  ];
  for (const [name, p, o, h, status] of refused) {
    const r = await post(p, o, h);
    check('refused: ' + name, r.status === status, String(r.status));
  }
  const errs = [
    ['bare IPv4', { u: 'http://1.2.3.4/' }, 'bare IP'],
    ['IPv6 literal', { u: 'http://[2001:db8::1]/' }, 'bare IP'],
    ['the panel itself', { u: 'https://' + HOST + '/panel/' }, 'panel itself'],
    ['non-http scheme', { u: 'file:///etc/passwd' }, 'bad url'],
    ['odd method', { u: 'https://site.example/', m: 'CONNECT' }, 'method'],
  ];
  for (const [name, o, word] of errs) {
    const j = await (await post('/gp/' + UUID, o)).json();
    check('refused: ' + name, typeof j.e === 'string' && j.e.includes(word), JSON.stringify(j));
  }
  const loop = await (await post('/gp/' + UUID, { u: 'https://site.example/' }, { 'x-cat-gp-hop': '1' })).json();
  check('refused: relay loop', loop.e === 'relay loop');
  check('nothing refused was fetched', siteCalls().length === 0, String(siteCalls().length));
}
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all Cat GP hop checks passed');
