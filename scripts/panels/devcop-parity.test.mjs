/**
 * Panel features matching the DevCop panel tutorial (independent implementation):
 *  - validity that starts at the first real connection (firstUseDays)
 *  - per-user operator profile (fw) on Xray links only
 *  - per-user clean addresses, kept exactly as tested (port never changed)
 *  - ⚡ quick user + scanner «save fastest to user» UI hooks
 *  - installable panel (web app manifest + icon)
 * Usage: node scripts/panels/devcop-parity.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = () => Promise.reject(new Error('offline-in-tests'));
const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0; const failed = [];
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; failed.push(name); console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();this.writes=0;} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.writes++;this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const HOST = 'devcop-parity.workers.dev';
const KV = new FakeKV();
const ENV = { CAT_KV: KV, UUID: '21111111-2222-4333-8444-555555555555' };
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://' + HOST + p, init), ENV, { waitUntil(){} });
}
const login = await req('/api/login', { method:'POST', body:{ username:'admin', password:'admin' } });
const auth = { cookie: (login.headers.get('set-cookie')||'').split(';')[0] };
check('admin/admin login still works (default kept)', login.status === 200 && auth.cookie.length > 10);

// 1) first-connection clock
let u;
{ const r = await req('/api/users', { method:'POST', headers: auth, body:{ name:'first', days:0, firstUseDays:30 } }); const j = await r.json(); u = j.user;
  check('user created with firstUseDays=30 and no expiry yet', r.status === 201 && u.firstUseDays === 30 && u.expiresAt === 0 && u.status === 'active', JSON.stringify(j)); }
{ const s = await req('/u/' + u.id); check('pending user sub works (not expired)', s.status === 200);
  check('pending user sub has no expire= yet', !(s.headers.get('subscription-userinfo')||'').includes('expire=')); }
{ const users = await T.readUsers(ENV); const live = users.find(x => x.id === u.id);
  const closed = []; const ws = { close(c, r){ closed.push(c); }, readyState: 1 };
  const ok = await T.admitLimitedUser(ENV, live, '198.51.100.7', ws, () => {});
  check('first connection is admitted', ok === true && closed.length === 0);
  T.kvCacheClear && T.kvCacheClear();
  const after = (await T.readUsers(ENV)).find(x => x.id === u.id);
  const want = Date.now() + 30 * 86400000;
  check('first connection starts the 30-day clock (stored)', Math.abs(after.expiresAt - want) < 10000, String(after.expiresAt));
  const w = KV.writes; await T.admitLimitedUser(ENV, after, '198.51.100.7', ws, () => {});
  check('later connections do not write KV again', KV.writes === w); }
{ const r = await req('/api/users', { headers: auth }); const j = await r.json(); const x = j.users.find(y => y.id === u.id);
  check('listed user now shows a real expiry', x.expiresAt > Date.now() + 29 * 86400000); }

// 2) per-user clean addresses: exact ports, junk dropped, max 20
let v;
{ const r = await req('/api/users', { method:'POST', headers: auth, body:{ name:'ips', days:10, ips:['103.237.102.191:11111', '184.75.221.82:3118', '104.16.1.2', 'junk value', '2606:4700::1111'] } }); const j = await r.json(); v = j.user;
  check('per-user IPs kept with their exact ports', JSON.stringify(v.ips) === JSON.stringify(['103.237.102.191:11111', '184.75.221.82:3118', '104.16.1.2', '[2606:4700::1111]']), JSON.stringify(v.ips));
  const s = await req('/u/' + v.id); const b = await s.text(); const raw = Buffer.from(b, 'base64').toString('utf8').includes('://') ? Buffer.from(b, 'base64').toString('utf8') : b;
  check('user sub uses the user IP with its exact port 11111', raw.includes('@103.237.102.191:11111'), raw.slice(0, 300));
  check('user sub never re-ports a pinned IP to 443/80', !raw.includes('@103.237.102.191:443') && !raw.includes('@103.237.102.191:80?') && !raw.includes('@184.75.221.82:443'));
  const many = Array.from({ length: 30 }, (_, i) => '104.17.0.' + (i + 1));
  const p = await req('/api/users/' + v.id, { method:'PUT', headers: auth, body:{ ips: many } }); const pj = await p.json();
  check('per-user IP list capped at 20', pj.user.ips.length === 20);
  const c = await req('/api/users/' + v.id, { method:'PUT', headers: auth, body:{ ips: [] } }); const cj = await c.json();
  check('empty list clears per-user IPs (back to panel IPs)', cj.user.ips.length === 0); }

// 3) per-user operator profile
{ const r = await req('/api/users', { method:'POST', headers: auth, body:{ name:'irancell', days:10, fw:'irancell-ff-98' } }); const j = await r.json(); const w = j.user;
  check('fw preset stored', w.fw === 'irancell-ff-98');
  const s = await req('/u/' + w.id); const b = await s.text(); const raw = b.includes('://') ? b : Buffer.from(b, 'base64').toString('utf8');
  check('Irancell F&F user link uses 188.114.98.0 + fp=unsafe', raw.includes('188.114.98.0') && /fp=unsafe/.test(raw), raw.slice(0, 300));
  const c = await req('/u/' + w.id + '/clash'); check('the same user\'s Clash link still works (preset skipped)', c.status === 200);
  const bad = await req('/api/users/' + w.id, { method:'PUT', headers: auth, body:{ fw:'made-up' } }); const bj = await bad.json();
  check('unknown preset id is rejected to automatic', bj.user.fw === ''); }

// 4) installable panel
{ const r = await req('/app.webmanifest'); const j = await r.json();
  check('manifest served as application/manifest+json', r.status === 200 && (r.headers.get('content-type')||'').startsWith('application/manifest+json'));
  check('manifest standalone with an icon', j.display === 'standalone' && j.start_url === '/' && j.icons.length === 1);
  const i = await req('/app-icon.svg'); check('icon is an SVG', i.status === 200 && (await i.text()).startsWith('<svg'));
  const page = await (await req('/panel/', { headers: auth })).text();
  check('panel page links the manifest', page.includes('rel="manifest" href="/app.webmanifest"'));
  check('panel has ⚡ quick user, install button, first-use switch, preset and IP fields',
    ['id="btnQuick"', 'id="btnInstall"', 'id="swFirst"', 'id="uFw"', 'name="uips"', 'id="btnUserIpsFast"', 'btnScanToUser'].every(k => page.includes(k)));
  check('fa + en strings for the new fields', (page.match(/u_first:'/g)||[]).length === 2 && (page.match(/scan_to_user_ok:'/g)||[]).length === 2); }

// 5) the UI really drives it (jsdom, same harness style as panel-dom.test.mjs)
{
  const { JSDOM } = await import('jsdom');
  const env2 = { CAT_KV: new FakeKV(), UUID: '31111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
  const H = 'https://ui.workers.dev';
  const html = await (await worker.fetch(new Request(H + '/panel'), env2, {})).text();
  const errors = []; const sent = [];
  const dom = new JSDOM(html, { url: H + '/', runScripts: 'dangerously', pretendToBeVisual: true, beforeParse(window) {
    window.fetch = async (p2, init = {}) => {
      const url = new URL(p2, H);
      if (url.pathname.startsWith('/api/users')) sent.push({ m: init.method || 'GET', p: url.pathname, b: init.body ? JSON.parse(init.body) : null });
      return worker.fetch(new Request(url, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env2, {});
    };
    window.confirm = () => true;
    window.addEventListener('error', (e) => errors.push(String(e.error && e.error.stack || e.message)));
    window.console.error = (...x) => errors.push(x.join(' '));
  } });
  const { window } = dom; const { document } = window; const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  await sleep(300);
  check('UI loads without JS errors', errors.length === 0, errors.join('\n'));
  document.querySelector('#btnQuick').click(); await sleep(300);
  const quick = sent.find(x => x.m === 'POST' && x.p === '/api/users');
  check('⚡ quick user: 30 days from first connection, both protocols', quick && quick.b.firstUseDays === 30 && quick.b.days === 0 && quick.b.protocols.vless && quick.b.protocols.trojan, JSON.stringify(quick));
  check('⚡ quick user opens its links drawer', document.querySelector('#drawer').classList.contains('show') && document.querySelector('#dLinks').textContent.length > 0);
  check('pending user shows «from first connection» in the list', document.querySelector('#rows').innerHTML.includes('⏳'));
  // edit: first-use stays on, pick a preset, type IPs
  const f = document.querySelector('#fUser');
  check('first-use switch is on for a pending user', document.querySelector('#swFirst').classList.contains('on'));
  check('preset select lists automatic + the 5 operator profiles', document.querySelectorAll('#uFw option').length === 6, String(document.querySelectorAll('#uFw option').length));
  f.elements.fw.value = 'mci-ipv6'; f.elements.uips.value = '103.237.102.191:11111\n184.75.221.82:3118'; f.elements.days.value = '45';
  sent.length = 0; f.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(300);
  const put = sent.find(x => x.m === 'PUT');
  check('saving sends firstUseDays=45, days=0, preset and the exact IP:port list', put && put.b.firstUseDays === 45 && put.b.days === 0 && put.b.fw === 'mci-ipv6' && JSON.stringify(put.b.ips) === JSON.stringify(['103.237.102.191:11111', '184.75.221.82:3118']), JSON.stringify(put));
  // scanner → save fastest to a user
  window.__browserScan = { '104.16.1.2:2053': { ok: true, ms: 120 }, '104.16.1.3': { ok: true, ms: 80 }, '104.16.1.4': { ok: false } };
  check('«5 fastest» fills the user field fastest-first, ports kept, dead skipped', (() => { document.querySelector('#btnUserIpsFast').click(); return f.elements.uips.value === '104.16.1.3\n104.16.1.2:2053'; })(), f.elements.uips.value);
  check('still no JS errors', errors.length === 0, errors.join('\n'));
  window.close();
}

if (failures) { console.error('\nFAILED (' + failures + '):\n - ' + failed.join('\n - ')); process.exit(1); }
console.log('\nall devcop-parity checks passed');
