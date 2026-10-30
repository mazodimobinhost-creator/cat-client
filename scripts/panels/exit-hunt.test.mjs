/**
 * 📍 Exit hunt (panel 6.72.0): «give me a working exit in country X».
 * Pinned against the real worker with a fake KV, a mocked list fetch and an injected socket layer:
 *   - owner only, country code required;
 *   - a candidate is kept ONLY when its real exit (ip-api echo through the proxy) is in that country;
 *   - dead proxies are skipped, never added; at most `want` are added;
 *   - the exact listed port is kept (here 10808 / 1080), tagged with the country;
 *   - the page has the picker + button and both languages carry the strings.
 * Usage: node scripts/panels/exit-hunt.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const LIST = 'socks5://10.1.0.1:10808\nsocks5://10.1.0.2:1080\nsocks5://10.1.0.3:1080\nhttp://10.1.0.9:8080\nsocks5://10.1.0.4:4145\n';
let fetched = [];
globalThis.fetch = async (u) => { fetched.push(String(u)); return String(u).includes('proxifly') ? new Response(LIST) : new Response('', { status: 404 }); };
const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '31111111-2222-4333-8444-555555555555' };
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://hunt.workers.dev' + p, init), ENV, { waitUntil(){} });
}
// 10.1.0.1 exits in DE, 10.1.0.2 is dead, 10.1.0.3 exits in NL (wrong country), 10.1.0.4 exits in DE.
const EXIT = { '10.1.0.1': 'DE', '10.1.0.3': 'NL', '10.1.0.4': 'DE' };
T.__setSockets({
  connect(opts) {
    const host = String(opts.hostname);
    if (!EXIT[host]) throw new Error('connection refused');
    let ctl; let writes = 0;
    return {
      opened: Promise.resolve(undefined),
      readable: new ReadableStream({ start(c) { ctl = c; c.enqueue(new Uint8Array([5, 0])); } }),
      writable: new WritableStream({ write() {
        writes++;
        if (writes === 2) ctl.enqueue(new Uint8Array([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
        if (writes === 3) { ctl.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\ncontent-type: application/json\r\n\r\n{"status":"success","query":"203.0.113.' + host.split('.')[3] + '","country":"X","countryCode":"' + EXIT[host] + '"}')); ctl.close(); }
      } }),
      close() {},
    };
  },
});
const anon = await req('/api/exit-hunt', { method: 'POST', body: { cc: 'DE' } });
check('anonymous caller cannot start a hunt', anon.status === 401 || anon.status === 302, String(anon.status));
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
check('admin/admin login still works', login.status === 200);
const bad = await req('/api/exit-hunt', { method: 'POST', headers: auth, body: { cc: 'Germany' } });
check('a country code is required', bad.status === 400);
const r = await req('/api/exit-hunt', { method: 'POST', headers: auth, body: { cc: 'de', want: 3 } });
const j = await r.json();
const urls = (j.found || []).map((f) => f.url).sort();
check('only proxies whose real exit is DE are kept', JSON.stringify(urls) === JSON.stringify(['socks5://10.1.0.1:10808', 'socks5://10.1.0.4:4145']), JSON.stringify(j));
check('list came from the per-country proxifly feed', fetched.some((u) => u.includes('/proxies/countries/DE/data.txt')));
check('http:// and dead entries are not counted as found', j.listed === 4 && j.tried === 4, JSON.stringify(j));
const st = await (await req('/api/settings', { headers: auth })).json();
const px = st.settings.proxyIps; const pxs = Array.isArray(px) ? px.join('\n') : String(px);
check('found exits saved with their exact ports', pxs.includes('socks5://10.1.0.1:10808') && pxs.includes('socks5://10.1.0.4:4145'), pxs);
check('dead and wrong-country proxies are not saved', !pxs.includes('10.1.0.2') && !pxs.includes('10.1.0.3'), pxs);
check('saved exits are tagged DE', (st.settings.proxyCountries || {})['socks5://10.1.0.1:10808'] === 'DE', JSON.stringify(st.settings.proxyCountries));
const again = await (await req('/api/exit-hunt', { method: 'POST', headers: auth, body: { cc: 'DE', want: 1 } })).json();
check('a second hunt skips relays that are already saved', !(again.found || []).some((f) => urls.includes(f.url)), JSON.stringify(again));
T.__setSockets(null);
const html = T.panelPage({}, { lang: 'fa' }, 'hunt.workers.dev', 'MASTER-UUID');
check('page has the country picker and the 📍 button', html.includes('id="ehCc"') && html.includes('id="ehGo"') && html.includes("'/api/exit-hunt'"));
check('both languages carry eh_btn/eh_ok/eh_none', (html.match(/eh_btn:/g) || []).length >= 2 && (html.match(/eh_none:/g) || []).length >= 2);
check('🚪 port tester button + 6 TLS ports on the page', html.includes('id="btnPortTest"') && html.includes('[443,8443,2053,2083,2087,2096]'));
{ const a = await req('/info/' + ENV.UUID); const b = await req('/status/' + ENV.UUID);
  check('/status/<uuid> is the same user page as /info/<uuid>', a.status === 200 && b.status === 200 && (await b.text()).length > 500, a.status + '/' + b.status); }
if (failures) { console.error(failures + ' failed'); process.exit(1); }
console.log('exit-hunt: all passed');
