/**
 * 🌍 Exit locations (panel 6.74.0) — the panel finds ProxyIP / SOCKS exits of a country BY ITSELF and a user can be
 * pinned to one country. Pinned against the real worker with a fake KV, mocked feeds and an injected socket layer:
 *   - owner only; a country code is required;
 *   - a ProxyIP is kept ONLY when Cloudflare's /cdn-cgi/trace seen THROUGH it says loc=<that country>;
 *     a working exit in another country is stored under its REAL country; dead ones are never stored;
 *   - SOCKS exits are kept only when the ip-api echo through them is in that country; listed ports are kept exactly;
 *   - «ping all» re-tests for real and marks failures; «remove dead» drops them;
 *   - a user pinned to DE gets ONLY the DE exits in their subscription (no built-in default ProxyIPs), other users
 *     keep the panel default; the SOCKS relay of a pinned user resolves on the server (relay:<id>), creds never leak;
 *   - the page carries the library card, the per-user picker and both languages.
 * Usage: node scripts/panels/locations.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const SOCKS_LIST = 'socks5://10.1.0.1:10808\nsocks5://10.1.0.2:1080\nsocks5://10.1.0.3:4145\n';
globalThis.fetch = async (u) => (String(u).includes('proxifly') ? new Response(SOCKS_LIST) : new Response('', { status: 404 }));
const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '61111111-2222-4333-8444-555555555555' };
// Repo ProxyIP cache: 10.2.0.1 (DE, port 2053) really exits in DE; 10.2.0.2 is tagged DE but really exits in NL;
// 10.2.0.3 is dead; 10.2.0.4 (US) is never tried for DE.
await ENV.CAT_KV.put('cat_prepo_cache_v1', JSON.stringify({ ts: Date.now(), per: {}, ips: ['10.2.0.1:2053#DE', '10.2.0.2:443#DE', '10.2.0.3:8443#DE', '10.2.0.4:443#US'] }));
const PXLOC = { '10.2.0.1': 'DE', '10.2.0.2': 'NL', '10.2.0.4': 'US' };
const SXEXIT = { '10.1.0.1': 'DE', '10.1.0.3': 'FR' };
let dead = new Set(['10.2.0.3', '10.1.0.2']);
const dials = [];
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://loc.workers.dev' + p, init), ENV, { waitUntil(){} });
}
T.__setSockets({
  connect(opts, o) {
    const host = String(opts.hostname);
    dials.push(host + ':' + opts.port);
    if (dead.has(host)) throw new Error('connection refused');
    if (o && o.secureTransport === 'starttls') {
      if (!PXLOC[host]) throw new Error('connection refused');
      let ctl;
      const tls = {
        readable: new ReadableStream({ start(c) { ctl = c; } }),
        writable: new WritableStream({ write() { ctl.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\ncontent-type: text/plain\r\n\r\nfl=1\nh=speed.cloudflare.com\nip=198.51.100.' + host.split('.')[3] + '\nts=1\nvisit_scheme=https\nuag=x\ncolo=FRA\nsliver=none\nhttp=http/1.1\nloc=' + PXLOC[host] + '\ntls=TLSv1.3\nsni=plaintext\nwarp=off\n')); ctl.close(); } }),
      };
      return { opened: Promise.resolve(), startTls() { return tls; }, readable: tls.readable, writable: tls.writable, close() {} };
    }
    if (!SXEXIT[host]) throw new Error('connection refused');
    let ctl; let writes = 0;
    return {
      opened: Promise.resolve(undefined),
      readable: new ReadableStream({ start(c) { ctl = c; c.enqueue(new Uint8Array([5, 0])); } }),
      writable: new WritableStream({ write() {
        writes++;
        if (writes === 2) ctl.enqueue(new Uint8Array([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
        if (writes === 3) { ctl.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\ncontent-type: application/json\r\n\r\n{"status":"success","query":"203.0.113.' + host.split('.')[3] + '","country":"X","countryCode":"' + SXEXIT[host] + '"}')); ctl.close(); }
      } }),
      close() {},
    };
  },
});
const anon = await req('/api/locations/fetch', { method: 'POST', body: { cc: 'DE' } });
check('anonymous caller cannot fetch locations', anon.status === 401 || anon.status === 302, String(anon.status));
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
check('admin/admin login still works', login.status === 200);
check('a country code is required', (await req('/api/locations/fetch', { method: 'POST', headers: auth, body: { cc: 'Germany' } })).status === 400);

const f1 = await (await req('/api/locations/fetch', { method: 'POST', headers: auth, body: { cc: 'de', kind: 'both', want: 3 } })).json();
const added = (f1.added || []).map((e) => e.addr).sort();
check('DE: only exits PROVEN in DE are added (ProxyIP via trace loc=, SOCKS via ip-api)', JSON.stringify(added) === JSON.stringify(['10.2.0.1:2053', 'socks5://10.1.0.1:10808']), JSON.stringify(f1));
check('the ProxyIP keeps its own feed port 2053 (no 443 guess)', added.includes('10.2.0.1:2053') && dials.includes('10.2.0.1:2053'), dials.join(','));
check('a ProxyIP tagged DE that really exits in NL is stored as NL, not DE', (f1.other || []).some((e) => e.addr === '10.2.0.2:443' && e.cc === 'NL'), JSON.stringify(f1.other));
check('dead / wrong-country entries are never stored', !(f1.pool || []).some((e) => /10\.2\.0\.3|10\.1\.0\.2|10\.1\.0\.3/.test(e.addr)), JSON.stringify(f1.pool));
check('a US ProxyIP is not tried for DE', !dials.some((d) => d.startsWith('10.2.0.4')));
check('measured ms + Cloudflare colo are recorded', (f1.pool || []).some((e) => e.addr === '10.2.0.1:2053' && e.colo === 'FRA' && e.ms >= 0 && e.exitIp === '198.51.100.1'));

const g = await (await req('/api/locations', { headers: auth })).json();
const de = (g.countries || []).find((c) => c.cc === 'DE');
check('GET /api/locations summarises countries (DE: 1 ProxyIP + 1 SOCKS healthy)', de && de.px === 1 && de.socks === 1 && de.ok === 2, JSON.stringify(g.countries));
check('choices carry Persian names + flags', (g.choices || []).some((c) => c.cc === 'DE' && c.fa === 'آلمان' && c.flag === '🇩🇪'));
check('NL appears under its real country', (g.countries || []).some((c) => c.cc === 'NL'));

// pin a user to DE
const pool = g.pool;
const deExits = pool.filter((p) => p.cc === 'DE' && p.ok).map((p) => p.addr);
const cu = await (await req('/api/users', { method: 'POST', headers: auth, body: { name: 'berlin', exits: deExits.concat(['1.2.3.4', 'junk!!']), exitCc: 'de', protocols: { vless: true, trojan: true } } })).json();
check('user keeps only valid exits (a portless ProxyIP is refused, never guessed)', cu.user && JSON.stringify(cu.user.exits.slice().sort()) === JSON.stringify(deExits.slice().sort()) && cu.user.exitCc === 'DE', JSON.stringify(cu.user && cu.user.exits));
const other = await (await req('/api/users', { method: 'POST', headers: auth, body: { name: 'plain', protocols: { vless: true, trojan: true } } })).json();
async function subText(u) {
  const p = new URL(u.links.sub).pathname;
  const t = await (await req(p)).text();
  return t.includes('://') ? t : Buffer.from(t.trim(), 'base64').toString('utf8');
}
const s1 = await subText(cu.user);
check('pinned user: 🎯 config through 10.2.0.1:2053 (exact port)', /proxyip(=|%3D)10\.2\.0\.1(%3A|%253A|:)2053/i.test(s1), s1.split('\n').filter((l) => /proxyip/i.test(l)).slice(0, 3).join(' | '));
check('pinned user: no built-in default ProxyIPs', !/cmliussss|nscl\.ir|diam4/i.test(s1));
check('pinned user: the 🎯 config is labelled with the German flag', s1.includes(encodeURIComponent('🇩🇪')) || s1.includes('🇩🇪'));
check('pinned user: SOCKS relay goes by reference, its address never travels', /proxyip(=|%3D)relay(%3A|%253A|:)[0-9a-f]{16}/i.test(s1) && !s1.includes('10.1.0.1'));
const s2 = await subText(other.user);
check('other users keep the panel default exits (the whole repo pool, not just the pin)', /10\.2\.0\.4/.test(s2) && !/relay(%3A|%253A|:)/i.test(s2.split('\n').filter((l) => l.includes('10.1.0.1')).join('')), s2.split('\n').map((l) => decodeURIComponent(l.split('#')[1] || '')).slice(0, 12).join(' | '));
check('the pinned user’s relay reference resolves on the server', T.resolveRelayToken ? !!T.resolveRelayToken((await (await req('/api/settings', { headers: auth })).json()).settings, (s1.match(/relay(?:%3A|%253A|:)([0-9a-f]{16})/i) || [])[1] ? 'relay:' + (s1.match(/relay(?:%3A|%253A|:)([0-9a-f]{16})/i) || [])[1] : '') : true);
const up = await (await req('/api/users/' + cu.user.id, { method: 'PUT', headers: auth, body: { exits: [], exitCc: '' } })).json();
check('clearing the location returns the user to the panel default', up.user && up.user.exits.length === 0 && up.user.exitCc === '');

// ── beta245: several countries per user ──
{
  const nlExit = pool.filter((p) => p.cc === 'NL' && p.ok).map((p) => p.addr);
  const mu = await (await req('/api/users', { method: 'POST', headers: auth, body: { name: 'multi', exits: deExits.concat(nlExit), exitCcs: ['de', 'NL', 'de', 'zz!'], protocols: { vless: true, trojan: true } } })).json();
  check('beta245: a user keeps several countries (deduped, valid only) and exitCc = the first', mu.user && JSON.stringify(mu.user.exitCcs) === '["DE","NL"]' && mu.user.exitCc === 'DE', JSON.stringify(mu.user && [mu.user.exitCcs, mu.user.exitCc]));
  check('beta245: …with exits from both countries, exact ports', mu.user && mu.user.exits.includes('10.2.0.1:2053') && mu.user.exits.includes('10.2.0.2:443'), JSON.stringify(mu.user && mu.user.exits));
  const many = Array.from({ length: 14 }, (_, i) => '10.9.0.' + (i + 1) + ':2053');
  const cap = await (await req('/api/users/' + mu.user.id, { method: 'PUT', headers: auth, body: { exits: many, exitCcs: ['DE', 'NL', 'FR', 'US', 'GB', 'JP', 'CA'] } })).json();
  check('beta245: caps are 12 exits and 6 countries', cap.user && cap.user.exits.length === 12 && cap.user.exitCcs.length === 6, JSON.stringify(cap.user && [cap.user.exits.length, cap.user.exitCcs]));
  const old = await (await req('/api/users/' + mu.user.id, { method: 'PUT', headers: auth, body: { exits: deExits, exitCc: 'FR' } })).json();
  check('beta245: an older page that only sends exitCc still works (one country)', old.user && JSON.stringify(old.user.exitCcs) === '["FR"]' && old.user.exitCc === 'FR', JSON.stringify(old.user && old.user.exitCcs));
  await req('/api/users/' + mu.user.id, { method: 'PUT', headers: auth, body: { exits: deExits.concat(nlExit), exitCcs: ['DE', 'NL'] } });
  const gc = await (await req('/api/locations', { headers: auth })).json();
  const cDE = (gc.countries || []).find((c) => c.cc === 'DE'), cNL = (gc.countries || []).find((c) => c.cc === 'NL');
  check('beta245: the library counts a 2-country user under BOTH countries', cDE && cNL && cNL.users >= 1 && cDE.users >= 1, JSON.stringify(gc.countries));
  const s3 = await subText(mu.user);
  check('beta245: that user’s sub carries both exits with exact ports', /10\.2\.0\.1(%3A|%253A|:)2053/i.test(s3) && /10\.2\.0\.2(%3A|%253A|:)443/i.test(s3), s3.split('\n').filter((l) => /proxyip/i.test(l)).slice(0, 4).join(' | '));
  await req('/api/users/' + mu.user.id, { method: 'DELETE', headers: auth });
}
// ── beta245: ➕ library → the panel's own ProxyIP/SOCKS list ──
{
  const before = (await (await req('/api/settings', { headers: auth })).json()).settings;
  const one = await (await req('/api/locations/to-panel', { method: 'POST', headers: auth, body: { addrs: ['10.2.0.1:2053'] } })).json();
  const st = (await (await req('/api/settings', { headers: auth })).json()).settings;
  const list = [].concat(st.proxyIps || []).join('\n');
  check('beta245: ➕ adds a library ProxyIP to the panel list with its exact port + country tag', one.ok && one.added === 1 && list.includes('10.2.0.1:2053') && (st.proxyCountries || {})['10.2.0.1:2053'] === 'DE', JSON.stringify([one, list, st.proxyCountries]));
  const all = await (await req('/api/locations/to-panel', { method: 'POST', headers: auth, body: { cc: 'DE' } })).json();
  const list2 = [].concat((await (await req('/api/settings', { headers: auth })).json()).settings.proxyIps || []).join('\n');
  check('beta245: «➕ all» adds the rest of the country (SOCKS too), never a duplicate', all.ok && all.added === 1 && all.skipped === 1 && list2.includes('socks5://10.1.0.1:10808') && list2.split('10.2.0.1:2053').length === 2, JSON.stringify([all, list2]));
  const no = await req('/api/locations/to-panel', { method: 'POST', headers: auth, body: { addrs: ['9.9.9.9:443'] } });
  check('beta245: an address that is not a tested library entry is refused (404)', no.status === 404);
  check('beta245: anonymous callers cannot add', (await req('/api/locations/to-panel', { method: 'POST', body: { cc: 'DE' } })).status === 401);
  await req('/api/settings', { method: 'PUT', headers: auth, body: { proxyIps: [].concat(before.proxyIps || []).join('\n') } });
}
// ── beta245: 📶 per-operator scanner memory ──
{
  const r1 = await (await req('/api/scan-ops', { method: 'POST', headers: auth, body: { op: 'mtn', hits: [{ a: '188.114.98.10:2053', ms: 80 }, { a: '[2606:4700::1]:443', ms: 120 }, { a: '1.2.3.4', ms: 5 }, { a: 'junk!!', ms: 5 }] } })).json();
  check('beta245: scanner memory keeps only real IP:port hits (portless/junk dropped)', r1.ok && r1.op === 'mtn' && r1.kept === 2, JSON.stringify(r1));
  const g1 = await (await req('/api/scan-ops', { headers: auth })).json();
  const m = (g1.learned || {}).mtn || [];
  check('beta245: …stored under the operator with exact ports and the measured ms', m.some((h) => h.a === '188.114.98.10:2053' && h.ms === 80) && m.some((h) => h.a === '[2606:4700::1]:443'), JSON.stringify(m));
  await req('/api/scan-ops', { method: 'POST', headers: auth, body: { op: 'mtn', hits: [{ a: '188.114.98.10:2053', ms: 60 }] } });
  const m2 = ((await (await req('/api/scan-ops', { headers: auth })).json()).learned || {}).mtn || [];
  check('beta245: a re-test replaces the old entry (no duplicates, newest ms)', m2.filter((h) => h.a === '188.114.98.10:2053').length === 1 && m2[0].ms === 60, JSON.stringify(m2));
  check('beta245: unknown operator without a network hint → 400', (await req('/api/scan-ops', { method: 'POST', headers: auth, body: { op: 'xx', hits: [] } })).status === 400);
  const rq = new Request('https://loc.workers.dev/api/scan-ops', { headers: auth });
  Object.defineProperty(rq, 'cf', { value: { asn: 44244, asOrganization: 'MTN Irancell' } });
  const g2 = await (await worker.fetch(rq, ENV, { waitUntil(){} })).json();
  check('beta245: operator auto-detected from the ASN of the request (44244 → Irancell)', g2.op === 'mtn' && g2.asn === 44244, JSON.stringify([g2.op, g2.asn]));
  check('beta245: anonymous callers cannot read the memory', (await req('/api/scan-ops')).status === 401);
}

// ping all: the DE ProxyIP dies
dead.add('10.2.0.1');
const pg = await (await req('/api/locations/ping', { method: 'POST', headers: auth, body: {} })).json();
const pe = (pg.pool || []).find((e) => e.addr === '10.2.0.1:2053');
check('ping all re-tests for real and marks the dead exit ✗', pg.ok && pe && pe.ok === false && pe.fails === 1, JSON.stringify(pe));
check('ping all keeps the healthy SOCKS ✓', (pg.pool || []).some((e) => e.kind === 'socks' && e.ok));
const rm = await (await req('/api/locations/remove', { method: 'POST', headers: auth, body: { dead: true } })).json();
check('«remove dead» drops only the dead ones', rm.removed === 1 && !(rm.pool || []).some((e) => e.addr === '10.2.0.1:2053') && (rm.pool || []).some((e) => e.kind === 'socks'), JSON.stringify(rm));

T.__setSockets(null);
const html = T.panelPage({}, { lang: 'fa' }, 'loc.workers.dev', 'MASTER-UUID');
for (const id of ['locLib', 'locCc', 'locKind', 'locFetch', 'locPingAll', 'locDropDead', 'locGrid', 'uLocBox', 'uLocGrid', 'uLocCc', 'uLocFetch', 'uLocPing'])
  check('page has #' + id, html.includes('id="' + id + '"'));
check('page calls the location API', html.includes("'/api/locations/fetch'") && html.includes("'/api/locations/ping'") && html.includes("'/api/locations/remove'"));
check('the user form saves exits + every picked country (exitCcs) + exitCc for old apps', html.includes('body.exits=UL.exits.slice(0,12)') && html.includes('body.exitCcs=UL.ccs.slice(0,6)') && html.includes("body.exitCc=UL.ccs[0]||''"));
check('beta245: both country pickers accept several countries', /id="uLocCc" multiple/.test(html) && /id="locCc" multiple/.test(html));
check('beta245: tapping a country toggles it (several per user, max 6)', html.includes('else if(i>=0)UL.ccs.splice(i,1);else if(UL.ccs.length<6)UL.ccs.push(cc)'));
check('beta245: «Find» runs each ticked country one after another', html.includes('async function locFetchMany(') && html.includes("locFetchMany(ccs,$('#uLocKind').value") && html.includes("locFetchMany(ccs,$('#locKind').value"));
check('6.86.0: hamburger has a dedicated تغییر ریجن page', html.includes('data-view="region"') && html.includes('id="v-region"') && html.includes('id="plcCard"') && html.includes('تغییر ریجن') && !html.includes("['#plcCard','#locLib'"));
check('beta245: library rows + countries have ➕ to-panel buttons', html.includes('data-locadd="') && html.includes('data-locadd-cc="') && html.includes("'/api/locations/to-panel'"));
check('beta245: scanner has the operator picker + suggestion button', html.includes('id="rsOp"') && html.includes('id="rsOpGo"') && html.includes("'/api/scan-ops'"));

if (failures) { console.error('\n' + failures + ' LOCATION CHECK(S) FAILED'); process.exit(1); }
console.log('\nlocations: all passed');
