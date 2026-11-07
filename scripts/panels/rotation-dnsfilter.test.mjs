/**
 * ⏱️ timed IP rotation + 🛡️ DNS filter (panel 6.77.0), against the real worker with a fake KV:
 *   - timed rotation is OFF by default and only a tick (rotTimed: true) turns it on; minutes are clamped 5…1440;
 *   - while on, two sub fetches in the same window give the same order, the next window gives another order;
 *   - scan-pinned addresses keep their exact port through the rotation (no port is guessed);
 *   - the sub asks clients to refresh (profile-update-interval) only while timed rotation is on;
 *   - the settings page says plainly that the box must be ticked, and the box renders unticked;
 *   - DNS filter: panel default + per-user override ('none' = off for that user), unknown values dropped;
 *   - the filtering resolver is really asked, and a dead filter falls back to the normal resolver.
 * Usage: node scripts/panels/rotation-dnsfilter.test.mjs
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
let dohCalls = [];
let dohFail = new Set();
globalThis.fetch = async (input, init) => {
  const u = String(input instanceof Request ? input.url : input);
  if (/dns-query/.test(u)) {
    dohCalls.push(u);
    if (dohFail.has(u)) return new Response('down', { status: 502 });
    return new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { 'content-type': 'application/dns-message' } });
  }
  return new Response('', { status: 404 });
};
const mod = await import(workerPath);
const worker = mod.default, T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '41111111-2222-4333-8444-555555555555' };
async function req(p, { method = 'GET', headers = {}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://rot.workers.dev' + p, init), ENV, { waitUntil() {} });
}
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
check('admin/admin login still works', login.status === 200);

let s = (await (await req('/api/settings', { headers: auth })).json()).settings;
check('timed rotation is OFF by default (needs a tick)', s.rotTimed === false && s.rotMinutes === 30, JSON.stringify({ t: s.rotTimed, m: s.rotMinutes }));
check('DNS filter is off by default', s.dnsFilter === '');

const ips = Array.from({ length: 24 }, (_, i) => '104.16.' + (i + 1) + '.' + (i + 7)).concat(['104.17.9.9:2053']);
await req('/api/settings', { method: 'PUT', headers: auth, body: { ips, tlsPorts: [443, 2053], plainEnabled: false, entryLimit: 12, subRotate: 'off', useDefaults: false, includeHost: false } });
const sub = async () => { const r = await req('/sub/' + ENV.UUID + '?noext=1'); return { r, lines: (await r.text()).split('\n').filter((l) => l.startsWith('vless://')) }; };
const hostsOf = (lines) => lines.map((l) => l.split('@')[1].split('?')[0]);
const a0 = await sub();
check('without the tick the sub refresh header stays at 12 h', a0.r.headers.get('profile-update-interval') === '12', a0.r.headers.get('profile-update-interval'));
const stable1 = hostsOf(a0.lines).join(','), stable2 = hostsOf((await sub()).lines).join(',');
check('rotation «stable» + no tick → same order every time', stable1 === stable2);

const put = await (await req('/api/settings', { method: 'PUT', headers: auth, body: { rotTimed: true, rotMinutes: 2 } })).json();
check('ticked: rotTimed saved, minutes clamped to ≥5', put.settings.rotTimed === true && put.settings.rotMinutes === 5, JSON.stringify({ t: put.settings.rotTimed, m: put.settings.rotMinutes }));
check('… and 1440 is the upper bound', (await (await req('/api/settings', { method: 'PUT', headers: auth, body: { rotMinutes: 99999 } })).json()).settings.rotMinutes === 1440);
await req('/api/settings', { method: 'PUT', headers: auth, body: { rotMinutes: 10 } });

const realNow = Date.now;
const base = Math.floor(realNow() / 600000) * 600000 + 1000;
Date.now = () => base;
const w1 = await sub();
const w1b = await sub();
check('same 10-min window → same order', hostsOf(w1.lines).join(',') === hostsOf(w1b.lines).join(','));
check('ticked: sub asks the client to refresh every hour', w1.r.headers.get('profile-update-interval') === '1', w1.r.headers.get('profile-update-interval'));
const orders = new Set([hostsOf(w1.lines).join(',')]);
for (let k = 1; k <= 4; k++) { Date.now = () => base + k * 600000; orders.add(hostsOf((await sub()).lines).join(',')); }
Date.now = realNow;
check('next windows bring a different arrangement', orders.size >= 3, String(orders.size));
const all = [...orders].join(',');
check('the scan-pinned 104.17.9.9 only ever appears on its own port 2053', !/104\.17\.9\.9:(?!2053)/.test(all), all.slice(0, 200));

const page = await (await req('/panel/', { headers: auth })).text();
check('settings page has the tick box + minutes field', page.includes('id="rotTimed"') && page.includes('id="rotMinutes"'));
check('… which renders unticked (no checked attribute)', !/id="rotTimed"[^>]*checked/.test(page));
check('… and says plainly «tick it to switch it on»', page.includes('حتماً تیک') || page.includes('MUST tick'));

// ---- DNS filter ----
const mk = await (await req('/api/users', { method: 'POST', headers: auth, body: { name: 'kid', dnsFilter: 'family' } })).json();
check('user created with the family filter', mk.user && mk.user.dnsFilter === 'family', JSON.stringify(mk.user && mk.user.dnsFilter));
const bad = await (await req('/api/users/' + mk.user.id, { method: 'PUT', headers: auth, body: { dnsFilter: 'evil' } })).json();
check('unknown filter value is dropped (→ panel default)', bad.user.dnsFilter === '', bad.user.dnsFilter);
await req('/api/settings', { method: 'PUT', headers: auth, body: { dnsFilter: 'ads' } });
const S = { dnsFilter: 'ads' };
check('panel default applies to users without their own choice', T.dnsFilterFor(S, { dnsFilter: '' }) === 'ads' && T.dnsFilterFor(S, null) === 'ads');
check('per-user choice wins, «none» switches it off for that user', T.dnsFilterFor(S, { dnsFilter: 'adult' }) === 'adult' && T.dnsFilterFor(S, { dnsFilter: 'none' }) === '');
dohCalls = [];
await T.resolveDnsOverDoh(new Uint8Array([0]), {}, 'family');
check('the filtering resolver is really asked', dohCalls[0] === T.DNS_FILTERS.family, dohCalls.join(' | '));
dohCalls = []; dohFail = new Set([T.DNS_FILTERS.ads]);
const ans = await T.resolveDnsOverDoh(new Uint8Array([0]), {}, 'ads');
check('a dead filter falls back to the normal resolver (internet keeps working)', dohCalls.length === 2 && /cloudflare-dns\.com/.test(dohCalls[1]) && ans.length === 3, dohCalls.join(' | '));
dohCalls = []; dohFail = new Set();
await T.resolveDnsOverDoh(new Uint8Array([0]), {}, '');
check('no filter → only the normal resolver', dohCalls.length === 1 && /cloudflare-dns\.com/.test(dohCalls[0]));
const src = fs.readFileSync(workerPath, 'utf8');
check('tunnel DNS uses the user’s filter', process.env.CAT_PANEL_WORKER || src.includes('resolveDnsOverDoh(query, env, dnsFilterFor(settings, dnsUser))'));
check('user modal has the DNS filter select', page.includes('id="uDnsf"') && page.includes('value="family"') && page.includes('id="setDnsf"'));

if (failures) { console.error('\n' + failures + ' failure(s)'); process.exit(1); }
console.log('\nrotation-dnsfilter: all checks passed');
