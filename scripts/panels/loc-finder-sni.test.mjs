/**
 * 6.85.0 exit finder — production edge omits SNI when connect() is given an IP (workerd#6903),
 * so every feed IP used to come back «no-trace». Pinned against the real worker:
 *   - a hostname ProxyIP is dialed by name (never resolved to an IP first);
 *   - fetch /meta + resolveOverride proves the country, but a short-circuit that would stamp
 *     every IP with the Worker's own country is rejected;
 *   - TLS 1.2 ClientHello carries the probe SNI; a Cloudflare cert + geo of that IP is kept;
 *   - «تغییر ریجن» is its own hamburger page (#v-region), not on Countries.
 * Usage: node scripts/panels/loc-finder-sni.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

const OWN = '198.51.100.9';
const META = {
  '10.8.0.1': { clientIp: '10.8.0.1', country: 'DE', colo: { iata: 'FRA', cca2: 'DE' } },
  '10.8.0.2': { clientIp: OWN, country: 'NL', colo: 'AMS' }, // short-circuit: same IP as the Worker
};
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  const over = init.cf && init.cf.resolveOverride;
  if (url.includes('/meta')) {
    if (!over) return Response.json({ clientIp: OWN, country: 'NL', colo: 'AMS' });
    if (META[over]) return Response.json(META[over]);
    return new Response('nope', { status: 404 });
  }
  if (url.includes('ip-api.com/json/10.8.0.3')) return Response.json({ status: 'success', countryCode: 'DE' });
  if (url.includes('proxifly')) return new Response('');
  return new Response('', { status: 404 });
};

const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }

check('tls ClientHello carries the SNI hostname in plaintext', new TextDecoder('latin1').decode(T.tlsClientHello('speed.cloudflare.com')).includes('speed.cloudflare.com'));
check('parseCfMeta reads country + colo.iata + clientIp', JSON.stringify(T.parseCfMeta({ country: 'DE', clientIp: '1.2.3.4', colo: { iata: 'FRA', cca2: 'DE' } })) === JSON.stringify({ loc: 'DE', colo: 'FRA', exitIp: '1.2.3.4' }));

class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '81111111-2222-4333-8444-555555555555' };
await ENV.CAT_KV.put('cat_prepo_cache_v1', JSON.stringify({
  ts: Date.now(), per: {},
  ips: ['10.8.0.1:2053#DE', '10.8.0.2:443#DE', '10.8.0.3:8443#DE', 'px.de.example:443#DE'],
}));
const dials = [];
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://sni.workers.dev' + p, init), ENV, { waitUntil(){} });
}
T.__setSockets({
  connect(opts) {
    const ip = String(opts.hostname);
    dials.push(ip + ':' + opts.port);
    if (ip === '10.8.0.3') {
      let ctl;
      const readable = new ReadableStream({ start(c) { ctl = c; } });
      const writable = new WritableStream({ write() {
        const s = 'xxxxCloudflare Inc ECC CA-3xxxxspeed.cloudflare.comxxxx';
        ctl.enqueue(new Uint8Array([0x16, 0x03, 0x03, 0x00, s.length, ...new TextEncoder().encode(s)]));
        ctl.close();
      } });
      return { opened: Promise.resolve(), readable, writable, close() {} };
    }
    if (ip === 'px.de.example') {
      let ctl;
      const tls = {
        readable: new ReadableStream({ start(c) { ctl = c; } }),
        writable: new WritableStream({ write() {
          ctl.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\n\r\nfl=1\nip=203.0.113.9\ncolo=FRA\nloc=DE\n'));
          ctl.close();
        } }),
      };
      return { opened: Promise.resolve(), startTls() { return tls; }, readable: tls.readable, writable: tls.writable, close() {} };
    }
    throw new Error('connection refused');
  },
});

const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
T.__resetPxMeta();
const j = await (await req('/api/locations/fetch', { method: 'POST', headers: auth, body: { cc: 'DE', kind: 'px', want: 4 } })).json();
check('finder answers ok', j.ok === true, JSON.stringify(j).slice(0, 240));
check('/meta + resolveOverride keeps 10.8.0.1:2053 as DE (exact feed port)', (j.added || []).some((e) => e.addr === '10.8.0.1:2053' && e.cc === 'DE' && e.colo === 'FRA'), JSON.stringify(j.added));
check('short-circuit (same clientIp as the Worker) is NOT stored', !(j.added || []).some((e) => e.addr === '10.8.0.2:443') && !(j.other || []).some((e) => e.addr === '10.8.0.2:443'), JSON.stringify([j.added, j.other, j.errors]));
check('ClientHello+Cloudflare cert + geo keeps 10.8.0.3:8443 as DE', (j.added || []).some((e) => e.addr === '10.8.0.3:8443' && e.cc === 'DE'), JSON.stringify(j.added));
check('hostname ProxyIP is dialed by name, never pre-resolved to an IP', dials.some((d) => d.startsWith('px.de.example:')), dials.join(','));
check('…and that hostname is kept as a DE exit', (j.added || []).some((e) => e.addr === 'px.de.example:443' && e.cc === 'DE'), JSON.stringify(j.added));

const html = await (await req('/panel/', { headers: auth })).text();
check('hamburger has a dedicated تغییر ریجن page', html.includes('data-view="region"') && html.includes('id="v-region"') && html.includes('id="plcCard"') && !html.includes("['#plcCard','#locLib'"));
check('region card is titled تغییر ریجن', html.includes('id="plcTitle">تغییر ریجن') || html.includes('تغییر ریجن'));

if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('loc-finder-sni: all checks passed');
