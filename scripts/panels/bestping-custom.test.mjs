/**
 * Panel 6.71.0 — what was learned from BPB and Zeus (independent implementation):
 *  - «🚀 Best Ping» Xray config: every usable entry behind a leastPing balancer + observatory
 *  - sing-box early data (max_early_data + Sec-WebSocket-Protocol) instead of a path-only «?ed=»
 *  - 🧪 custom single config: exact IP:port, TLS by port family, Host/SNI, random-case SNI,
 *    random /vl/ path, early data, fingerprint, ALPN, fragment, ECH — four output shapes
 * Usage: node scripts/panels/bestping-custom.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = () => Promise.reject(new Error('offline-in-tests'));
const mod = await import(workerPath);
const worker = mod.default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const HOST = 'bestping-custom.workers.dev';
const UUID = '31111111-2222-4333-8444-555555555555';
const ENV = { CAT_KV: new FakeKV(), UUID };
function req(p, { method='GET', headers={}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://' + HOST + p, init), ENV, { waitUntil(){} });
}
const login = await req('/api/login', { method:'POST', body:{ username:'admin', password:'admin' } });
const auth = { cookie: (login.headers.get('set-cookie')||'').split(';')[0] };
check('admin/admin login still works', login.status === 200 && auth.cookie.length > 10);
await req('/api/settings', { method:'PUT', headers: auth, body:{ ips:['104.16.1.1:2053', '104.17.2.2', '[2606:4700::6810:1]:8443'] } });

// ---------- 1) Best Ping ----------
{ const cfgs = await (await req('/xray/' + UUID + '?raw=1&limit=6')).json();
  const bp = cfgs[cfgs.length - 1];
  check('Best Ping is appended LAST (config #1 keeps its meaning)', /Best Ping/.test(bp.remarks) && !/Best Ping/.test(cfgs[0].remarks), cfgs.map(c=>c.remarks).join(' | '));
  const bpOuts = bp.outbounds.filter(o => /^bp-\d+$/.test(o.tag));
  check('Best Ping holds every other config as bp-N exits', bpOuts.length === cfgs.length - 1, bpOuts.length + ' vs ' + (cfgs.length - 1));
  check('leastPing balancer over bp- with fallback', bp.routing.balancers && bp.routing.balancers[0].strategy.type === 'leastPing' && bp.routing.balancers[0].selector[0] === 'bp-' && bp.routing.balancers[0].fallbackTag === 'bp-1');
  check('observatory probes through the tunnel (generate_204, interval 30s)', bp.observatory && bp.observatory.probeUrl === 'https://www.gstatic.com/generate_204' && bp.observatory.probeInterval === '30s' && bp.observatory.subjectSelector[0] === 'bp-');
  check('the catch-all routes to the balancer, not to a single proxy', bp.routing.rules.some(r => r.balancerTag === 'best' && r.port === '0-65535') && !bp.routing.rules.some(r => r.outboundTag === 'proxy'));
  check('direct/block still present (iran bypass / ads rules keep working)', bp.outbounds.some(o => o.tag === 'direct') && bp.outbounds.some(o => o.tag === 'block'));
  const addrs = bpOuts.map(o => { const s = o.settings; const v = (s.vnext || s.servers)[0]; return v.address + ':' + v.port; });
  check('exact ports kept inside Best Ping (2053 stays 2053, 8443 stays 8443)', addrs.includes('104.16.1.1:2053') && addrs.some(a => a.endsWith(':8443') && a.includes('2606:4700::6810:1')) && !addrs.includes('104.16.1.1:443'), addrs.join(','));
}
{ const cfgs = await (await req('/xray/' + UUID + '?raw=1&limit=6&bp=0')).json();
  check('?bp=0 removes Best Ping from one link', !cfgs.some(c => /Best Ping/.test(c.remarks))); }
{ const cfgs = await (await req('/xray/' + UUID + '?raw=1&limit=1')).json();
  check('one exit (VLESS+Trojan on one IP:port) never gets a Best Ping wrapper', cfgs.length >= 1 && !cfgs.some(c => /Best Ping/.test(c.remarks)), cfgs.map(c=>c.remarks).join(' | ')); }
{ await req('/api/settings', { method:'PUT', headers: auth, body:{ bestPing:false, bestPingInterval: 3 } });
  const s = await (await req('/api/settings', { headers: auth })).json();
  check('bestPing setting can be switched off, interval is clamped to ≥10', s.settings.bestPing === false && s.settings.bestPingInterval === 10, JSON.stringify({ b: s.settings.bestPing, i: s.settings.bestPingInterval }));
  const off = await (await req('/xray/' + UUID + '?raw=1&limit=4')).json();
  const forced = await (await req('/xray/' + UUID + '?raw=1&limit=4&bp=1')).json();
  check('setting off → no Best Ping; ?bp=1 forces it back for one link', !off.some(c => /Best Ping/.test(c.remarks)) && /Best Ping/.test(forced[forced.length - 1].remarks) && forced[forced.length - 1].observatory.probeInterval === '10s');
  await req('/api/settings', { method:'PUT', headers: auth, body:{ bestPing:true, bestPingInterval: 30 } }); }

// ---------- 2) sing-box early data ----------
{ const sb = await (await req('/singbox/' + UUID + '?limit=4')).json();
  const ws = sb.outbounds.filter(o => o.transport && o.transport.type === 'ws');
  check('sing-box ws outbounds carry max_early_data=2560 + Sec-WebSocket-Protocol', ws.length > 0 && ws.every(o => o.transport.max_early_data === 2560 && o.transport.early_data_header_name === 'Sec-WebSocket-Protocol'), JSON.stringify(ws[0] && ws[0].transport));
  check('sing-box path is untouched (proxyip pins in the query keep working)', ws.every(o => /\?ed=2560/.test(o.transport.path))); }

// ---------- 3) custom single config ----------
async function cc(body) { const r = await req('/api/custom-config', { method:'POST', headers: auth, body }); return { status: r.status, j: await r.json() }; }
{ const r = await req('/api/custom-config', { method:'POST', body:{ addr:'104.16.1.1' } });
  check('custom config needs the owner login', r.status === 401); }
{ const { status, j } = await cc({ addr:'104.16.1.1:2053', port: 443 });
  check('custom: exact port from the address wins (2053, not 443)', status === 200 && j.used.port === 2053 && j.link.includes('@104.16.1.1:2053?') && j.warnings.includes('port_from_addr'), JSON.stringify(j).slice(0, 300));
  check('custom: 2053 → TLS automatically, SNI = Host = worker', j.used.tls === true && j.used.host === HOST && j.used.sni === HOST && j.link.includes('security=tls'));
  check('custom: default path is the deployment path with ed=2560', /^\/vl\/[0-9a-z_-]+\?ed=2560$/i.test(j.used.path), j.used.path);
  const x = JSON.parse(j.xray); const o = x.outbounds[0];
  check('custom: Xray JSON is a full config with the same exit', o.protocol === 'vless' && o.settings.vnext[0].address === '104.16.1.1' && o.settings.vnext[0].port === 2053 && o.streamSettings.wsSettings.headers.Host === HOST && x.inbounds.length === 2);
  const sb = JSON.parse(j.singbox);
  check('custom: sing-box outbound with early data + utls', sb.server_port === 2053 && sb.transport.max_early_data === 2560 && sb.tls.utls.fingerprint === 'chrome');
  check('custom: Clash snippet present with ws-opts + servername', j.clash.includes('type: vless') && j.clash.includes('ws-opts:') && j.clash.includes('servername: ' + JSON.stringify(HOST).slice(1, -1)) || j.clash.includes('servername: "' + HOST + '"'), j.clash); }
{ const { j } = await cc({ addr:'104.17.2.2', port: 8080 });
  check('custom: 8080 → no TLS automatically (Cloudflare plain port)', j.used.tls === false && j.link.includes('security=none') && !j.link.includes('sni=')); }
{ const { j } = await cc({ addr:'104.17.2.2', port: 80, tls:'on' });
  check('custom: TLS forced on a plain port is warned about', j.warnings.includes('tls_on_plain_port')); }
{ const { j } = await cc({ addr:'2606:4700::6810:1', port: 8443, proto:'trojan' });
  check('custom: IPv6 is bracketed in the link, trojan uses the trojan password', j.link.startsWith('trojan://') && j.link.includes('@[2606:4700::6810:1]:8443?')); }
{ const { j } = await cc({ addr:'104.16.1.1', port: 443, pathMode:'random', sniCase: true, ed: false, fp:'firefox', alpn:'http/1.1' });
  const p = j.used.path;
  check('custom: random BPB-style path /vl/<16-32>, no ed when switched off', /^\/vl\/[a-z0-9]{16,32}$/.test(p), p);
  check('custom: random-case SNI still names the worker (case-insensitive)', j.used.sni.toLowerCase() === HOST && j.link.includes('sni=' + encodeURIComponent(j.used.sni)));
  check('custom: fingerprint is honoured', j.link.includes('fp=firefox') && JSON.parse(j.xray).outbounds[0].streamSettings.tlsSettings.fingerprint === 'firefox');
  const rr = await req('/' + p.replace(/^\//, ''), { headers: { Upgrade: 'websocket' } });
  check('custom: the worker data plane accepts the random path (101 or a WebSocket answer)', rr.status === 101 || rr.webSocket || rr.status !== 404, String(rr.status)); }
{ const { status, j } = await cc({ addr:'104.16.1.1', pathMode:'custom', path:'/hello' });
  check('custom: a path the data plane would reject is refused up front', status === 400 && j.error === 'path'); }
{ const { status, j } = await cc({ addr:'104.16.1.1', pathMode:'custom', path:'/vl/myown1234?x=1' });
  check('custom: an accepted own path keeps its query and gains ed=2560', status === 200 && j.used.path === '/vl/myown1234?x=1&ed=2560', j.used && j.used.path); }
{ const { j } = await cc({ addr:'104.16.1.1', frag: true, fragValue:'tlshello,10-50,5-10' });
  const x = JSON.parse(j.xray);
  check('custom: fragment → freedom fragment outbound + dialerProxy in Xray, tls_fragment in sing-box', x.outbounds.some(o => o.tag === 'fragment' && o.settings.fragment.length === '10-50') && x.outbounds[0].streamSettings.sockopt.dialerProxy === 'fragment' && JSON.parse(j.singbox).tls_fragment === true); }
{ const { j } = await cc({ addr:'104.16.1.1', frag: true, ech: true, sniCase: true });
  check('custom: ECH wins over fragment and the case trick (both switched off, warned)', j.used.ech === true && !j.used.frag && j.used.sni === HOST && j.warnings.includes('frag_ech') && j.link.includes('ech=')); }
{ const { j } = await cc({ addr:'104.16.1.1', sni:'www.speedtest.net' });
  check('custom: SNI ≠ Host is warned about (Cloudflare 403)', j.warnings.includes('sni_differs')); }
{ const bad = await cc({ addr:'not an address!' }); const badP = await cc({ addr:'104.16.1.1', port: 70000 }); const badF = await cc({ addr:'104.16.1.1', frag: true, fragValue:'x,y' });
  check('custom: invalid address / port / fragment are rejected with a reason', bad.j.error === 'addr' && badP.j.error === 'port' && badF.j.error === 'frag'); }
{ const u = await (await req('/api/users', { method:'POST', headers: auth, body:{ name:'cc-user', days: 10 } })).json();
  const { j } = await cc({ addr:'104.16.1.1', user: u.user.id });
  check('custom: built for a panel user → that user’s own id in the link', j.link.includes(u.user.id + '@104.16.1.1:443'));
  const missing = await cc({ addr:'104.16.1.1', user:'00000000-0000-4000-8000-000000000000' });
  check('custom: unknown user → 404', missing.status === 404); }
{ const { j } = await cc({});
  check('custom: an empty form still builds a working worker-domain config', j.ok && j.used.addr === HOST && j.used.port === 443 && j.used.tls === true); }

// ---------- 4) the panel page exposes both ----------
{ const html = await (await req('/panel', { headers: auth })).text();
  check('panel page has the 🧪 custom config card + builder button', html.includes('id="ccCard"') && html.includes('id="ccGen"') && html.includes('/api/custom-config'));
  check('panel page has the Best Ping switch', html.includes('id="swBestPing"') && html.includes('s_bestping')); }

if (failures) { console.log(failures + ' bestping-custom check(s) FAILED'); process.exit(1); }
console.log('all bestping-custom checks passed');
