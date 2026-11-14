/**
 * 🧼 «Cat Clean» shell (panel 6.73.0, beta199) — DevCop-level layout and features, pinned against the real worker:
 *   - settings.ipPools is normalized server-side (name ≤40, ≤100 addresses each, ≤20 pools, junk dropped, ports kept);
 *   - the panel page carries the tabbed user modal, links tab, range scanner, pools card, bulk bar, settings accordions;
 *   - /status/<id> is the DevCop-style page: stats, 4 subscription formats, per-config list with «copy all», dark toggle,
 *     relative subscription fetch (works on any host), and a blocked user is shown as blocked.
 * Usage: node scripts/panels/clean-shell.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = async () => new Response('', { status: 404 });
const mod = await import(workerPath);
const worker = mod.default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '51111111-2222-4333-8444-555555555555' };
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://clean.workers.dev' + p, init), ENV, { waitUntil(){} });
}
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
check('admin/admin login still works', login.status === 200);

// ---- ipPools normalization
const many = Array.from({ length: 130 }, (_, i) => '104.16.' + Math.floor(i / 200) + '.' + (i % 250 + 1) + ':2053');
const put = await req('/api/settings', { method: 'PUT', headers: auth, body: { ipPools: [
  { name: 'Irancell Tehran', ips: ['188.114.98.0:443', '[2606:4700::1]:2053', 'not an ip!!', '104.17.1.2'] },
  { name: 'x'.repeat(80), ips: many },
  { name: '', ips: ['1.2.3.4'] },
  'junk',
  ...Array.from({ length: 25 }, (_, i) => ({ name: 'p' + i, ips: ['1.1.1.' + (i + 1)] })),
] } });
const pj = await put.json();
const P = (pj.settings && pj.settings.ipPools) || [];
check('PUT /api/settings accepts ipPools', put.status === 200 && pj.ok, JSON.stringify(pj).slice(0, 200));
check('pool count is capped at 20', P.length <= 20 && P.length > 0, String(P.length));
check('first pool keeps its name', P[0] && P[0].name === 'Irancell Tehran');
check('pinned ports are kept exactly (443 / [v6]:2053)', P[0] && P[0].ips.includes('188.114.98.0:443') && P[0].ips.some((x) => x.includes('2606:4700::1') && x.endsWith(':2053')), P[0] && P[0].ips.join(','));
check('junk addresses are dropped', P[0] && !P[0].ips.some((x) => /not an ip/.test(x)));
check('pool name capped at 40 chars', P[1] && P[1].name.length <= 40);
check('≤100 addresses per pool, ports kept', P[1] && P[1].ips.length <= 100 && P[1].ips.every((x) => x.endsWith(':2053')), P[1] && String(P[1].ips.length));
check('nameless / non-object pools are dropped', !P.some((p) => !p || !p.name));
const g = await (await req('/api/settings', { headers: auth })).json();
check('ipPools survive a reload (persisted)', JSON.stringify(g.settings.ipPools) === JSON.stringify(P));

// ---- panel page markup
const page = await (await req('/panel', { headers: auth })).text();
for (const id of ['uTabs', 'uTabLinks', 'uPoolPick', 'uPoolUse', 'uConnSum', 'rsRanges', 'rsPresetCf', 'rsStart', 'rsStop', 'rsToPanel', 'rsToUser', 'rsToPool', 'poolList', 'poolAdd', 'bulkBar', 'selAll', 'setSaveBar', 'btnSetRestore'])
  check('panel has #' + id, page.includes('id="' + id + '"'));
for (const k of ['base', 'quota', 'conn', 'ip', 'links']) check('user modal tab ' + k, page.includes('data-ut="' + k + '"') && (k === 'links' || page.includes('data-up="' + k + '"')));
check('range scanner keeps the exact tested port (rsKey ip:port)', /function rsKey\(ip,port\)/.test(page));
check('bulk delete asks first', /k==='del'&&!\(await ask\(/.test(page));
check('quick user warns when no clean IP', page.includes('آیپی تمیز قرار دهید'));
check('settings accordions open the section of anything scrolled to', page.includes('Element.prototype.scrollIntoView=function'));

// ---- /status page
const u = await (await req('/api/users', { method: 'POST', headers: auth, body: { name: 'mina', days: 10, quotaGB: 5, maxIps: 2, protocols: { vless: true, trojan: true } } })).json();
const st = await req('/status/' + u.user.id);
const sh = await st.text();
check('/status/<id> answers 200 HTML', st.status === 200 && /text\/html/.test(st.headers.get('content-type') || ''));
check('status shows the user name + active pill', sh.includes('mina') && sh.includes('pill ok'));
check('status shows 4 formats (Base64 / sing-box / Xray JSON / Clash Meta)', (sh.match(/class="lc"/g) || []).length >= 4 && ['Base64', 'sing-box', 'Xray JSON', 'Clash Meta'].every((x) => sh.includes(x)));
check('status keeps carrier links (MCI IPv6 / Irancell F&F)', sh.includes('fw=mci-ipv6') && sh.includes('fw=irancell-ff'));
check('status has stats: quota 5 GB, devices 2, days', sh.includes('5.00 GB') && sh.includes('>2<') && /10 روز مانده|10 days left|9 روز مانده/.test(sh));
check('status has per-config list + copy all + dark toggle', sh.includes('id="cfgs"') && sh.includes('id="cpAll"') && sh.includes('id="dk"'));
check('status fetches the sub by a RELATIVE path', /var SUBP="\/[^/"]/.test(sh), (sh.match(/var SUBP=[^,]*/) || [''])[0]);
check('status keeps the Cat Client deep link', sh.includes('catclient://add-sub?url='));
await req('/api/users/' + u.user.id + '/toggle', { method: 'POST', headers: auth, body: {} });
const sh2 = await (await req('/status/' + u.user.id)).text();
check('a disabled user is shown as disabled', sh2.includes('pill bad'));
check('unknown token → 404', (await req('/status/nope-nope')).status === 404);

if (failures) { console.error('\n' + failures + ' CLEAN-SHELL CHECK(S) FAILED'); process.exit(1); }
console.log('\nclean-shell: all passed');
