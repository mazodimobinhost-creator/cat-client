/**
 * Cat Panel v6 worker test harness (Node 18+, no dependencies).
 * Usage: node scripts/panels/cat-panel.test.mjs
 * Covers: auth/session, master + per-user subscriptions (raw/b64/clash/singbox),
 * settings & users API, KV write budget, backup/restore, wire parsers, relay.
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');
const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();this.writes=0;this.reads=0;} async get(k){this.reads++;return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.writes++;this.m.set(k,v);} async list(){return {keys:[...this.m.keys()].map(name=>({name}))};} async delete(k){this.m.delete(k);} }
const HOST = 'catpanel-demo.workers.dev';
const KV = new FakeKV();
const ENV = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555' };
const MASTER = ENV.UUID;
function req(path, { method='GET', headers={}, body, env=ENV } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = typeof body === 'string' ? body : JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://' + HOST + path, init), env, { waitUntil(){} });
}
// health
{ const r = await req('/health'); const j = await r.json(); check('/health ok', r.status===200 && j.ok===true && j.version===T.CAT_PANEL_VERSION); }
// panel locked by default → login page
{ const r = await req('/'); const b = await r.text(); check('/ shows login when locked', r.status===200 && b.includes('/api/login')); }
// api unauthorized
{ const r = await req('/api/settings'); check('/api/settings 401 without session', r.status===401); }
// login with uuid
let cookie='';
{ const r = await req('/api/login', { method:'POST', body:{ password: 'wrong' } }); check('login wrong → 401', r.status===401);
  const r2 = await req('/api/login', { method:'POST', body:{ password: MASTER } }); const j = await r2.json(); cookie = (r2.headers.get('set-cookie')||'').split(';')[0];
  check('login with UUID ok', r2.status===200 && j.ok && cookie.startsWith('cat_session=')); }
const auth = { cookie };
{ const r = await req('/', { headers: auth }); const b = await r.text(); check('/ shows panel with cookie', b.includes('v-dash') && b.includes('CAT_PANEL') === false && b.includes('Cat Panel')); }
{ const r = await req('/api/settings', { headers: auth }); const j = await r.json(); check('settings GET', j.ok && j.uuid===MASTER && j.kv===true && j.passwordSource==='uuid' && j.links.sub.endsWith('/sub/'+MASTER)); }
// subscription master
{ const r = await req('/sub/' + MASTER); const b = await r.text();
  check('/sub/<uuid> 200', r.status===200);
  const lines = b.trim().split('\n');
  check('has vless + trojan', b.includes('vless://') && b.includes('trojan://'));
  check('first entry is TLS 443', /:443\?/.test(lines[0]), lines[0]);
  check('has plain :80 entries', /:80\?encryption=none&security=none/.test(b));
  check('host header + path', b.includes('host=' + HOST) && b.includes(encodeURIComponent('/ws?ed=2048')));
  check('userinfo header', (r.headers.get('subscription-userinfo')||'').includes('total=0'));
  check('entry limit respected', lines.length <= 48 && lines.length >= 20, String(lines.length));
  const r64 = await req('/sub64/' + MASTER); const b64 = await r64.text(); check('/sub64 is base64 of /sub', T.b64decode(b64) === b);
  const rb = await req('/sub/' + MASTER + '?b64=1'); check('?b64=1 works', T.b64decode(await rb.text()).includes('vless://'));
  const bad = await req('/sub/00000000-0000-4000-8000-000000000000'); check('unknown uuid 404', bad.status===404);
  const noKey = await req('/sub'); check('/sub without uuid 404 when OPEN_SUB unset', noKey.status===404);
  const open = await req('/sub', { env: Object.assign({}, ENV, { OPEN_SUB: 'true' }) }); check('/sub with OPEN_SUB serves master', open.status===200 && (await open.text()).includes('vless://'));
}
{ const r = await req('/clash/' + MASTER); const y = await r.text(); check('clash yaml', r.status===200 && y.includes('proxies:') && y.includes('type: vless') && y.includes('type: trojan') && y.includes('MATCH,🐱 Cat') && !y.includes('tls: false\n    password')); }
{ const r = await req('/singbox/' + MASTER); const j = await r.json(); check('singbox json', j.outbounds.some(o=>o.type==='vless') && j.outbounds[0].type==='selector' && j.route.final==='🐱 Cat'); }
// settings PUT
{ const r = await req('/api/settings', { method:'PUT', headers: auth, body:{ ips:['1.2.3.4','www.example.com','bad ip'], tlsPorts:[443,2053], plainEnabled:false, protocols:{vless:true,trojan:false}, entryLimit:10 } }); const j = await r.json();
  check('settings PUT', j.ok && j.persisted && j.settings.ips.length===3 && j.settings.tlsPorts.join()==='443,2053' && j.settings.protocols.trojan===false);
  const s = await req('/sub/' + MASTER); const b = await s.text(); const lines=b.trim().split('\n');
  check('owner ips first', lines[0].includes('@1.2.3.4:443') && lines[1].includes('@www.example.com:443'), lines[0]);
  check('trojan disabled + no plain + limit', !b.includes('trojan://') && !b.includes('security=none') && lines.length===10);
}
// password change
{ const writes = KV.writes; const r = await req('/api/settings', { method:'PUT', headers: auth, body:{ password:'s3cret' } }); const j = await r.json(); const newCookie=(r.headers.get('set-cookie')||'').split(';')[0];
  check('password set → new cookie', j.ok && j.settings.hasPassword && newCookie && newCookie!==cookie);
  const old = await req('/api/settings', { headers: auth }); check('old session invalid after password change', old.status===401);
  const bearer = await req('/api/settings', { headers:{ authorization:'Bearer s3cret' } }); check('bearer password works', bearer.status===200);
  const uuidLogin = await req('/api/login', { method:'POST', body:{ password: MASTER } }); check('uuid no longer a password', uuidLogin.status===401);
  auth.cookie = newCookie; check('one KV write for settings save', KV.writes === writes + 1, String(KV.writes - writes)); }
// users
let user;
{ const r = await req('/api/users', { method:'POST', headers: auth, body:{ name:'ali', days:30 } }); const j = await r.json(); user = j.user;
  check('create user', r.status===201 && j.ok && T.isUuid(user.id) && user.expiresAt > Date.now() && user.links.sub.endsWith('/u/'+user.id));
  const l = await req('/api/users', { headers: auth }); check('list users', (await l.json()).users.length===1);
  const s = await req('/u/' + user.id); const b = await s.text(); check('user sub works + uses user uuid', s.status===200 && b.includes('vless://'+user.id+'@'));
  check('user sub has expire', (s.headers.get('subscription-userinfo')||'').includes('expire='));
  const c = await req('/u/' + user.id + '/clash'); check('user clash', (await c.text()).includes('uuid: '+user.id));
  const sb = await req('/u/' + user.id + '/singbox'); check('user singbox', (await sb.json()).outbounds.some(o=>o.uuid===user.id));
  const info = await req('/info/' + user.id); check('user info page', info.status===200 && (await info.text()).includes('ali'));
  const tog = await req('/api/users/'+user.id+'/toggle', { method:'POST', headers: auth, body:{} }); check('toggle → disabled', (await tog.json()).user.enabled===false);
  const blocked = await req('/u/' + user.id); check('disabled user sub 403', blocked.status===403);
  await req('/api/users/'+user.id+'/toggle', { method:'POST', headers: auth, body:{} });
  const exp = await req('/api/users/'+user.id, { method:'PUT', headers: auth, body:{ expiresAt: Date.now()-1000 } }); check('set expired', (await exp.json()).user.status==='expired');
  const blocked2 = await req('/u/' + user.id); check('expired user sub 403', blocked2.status===403);
  const renew = await req('/api/users/'+user.id+'/renew', { method:'POST', headers: auth, body:{ days: 10 } }); const rj = await renew.json(); check('renew from now', rj.user.expiresAt > Date.now() + 9*86400000);
  const tun = await T.tunnelAuth(ENV, user.id, MASTER); check('tunnelAuth user ok', tun.ok);
  const tunM = await T.tunnelAuth(ENV, MASTER, MASTER); check('tunnelAuth master ok', tun.ok && tunM.master);
  const tunX = await T.tunnelAuth(ENV, '00000000-0000-4000-8000-000000000000', MASTER); check('tunnelAuth unknown rejected', !tunX.ok);
}
// ips import
{ const r = await req('/api/ips', { method:'POST', headers: auth, body:{ ips:'5.6.7.8\n9.9.9.9, cdn.example.org junk' } }); const j = await r.json(); check('ips import appends', j.ok && j.ips.includes('5.6.7.8') && j.ips.includes('1.2.3.4') && !j.ips.includes('junk')); }
// backup
{ const r = await req('/api/backup', { headers: auth }); const j = await r.json(); check('backup export', j.settings && j.users.length===1);
  const KV2 = new FakeKV(); const env2 = { CAT_KV: KV2, UUID: MASTER }; T.kvCacheClear();
  const login = await req('/api/login', { method:'POST', body:{ password: MASTER }, env: env2 }); const c2=(login.headers.get('set-cookie')||'').split(';')[0];
  const rs = await req('/api/backup', { method:'POST', headers:{cookie:c2}, body: j, env: env2 }); check('backup restore', (await rs.json()).ok);
  const lu = await req('/api/users', { headers:{ authorization:'Bearer s3cret' }, env: env2 }); check('restored users + password', lu.status===200 && (await lu.json()).users[0].name==='ali');
  T.kvCacheClear(); }
// open panel
{ const KV3 = new FakeKV(); const env3 = { CAT_KV: KV3, UUID: MASTER, OPEN_PANEL:'true' }; const r = await req('/', { env: env3 }); check('OPEN_PANEL serves panel without login', (await r.text()).includes('v-dash'));
  const r2 = await req('/api/settings', { env: env3 }); check('OPEN_PANEL api open', r2.status===200 && (await r2.json()).open===true); T.kvCacheClear(); }
// no KV
{ const envNo = { UUID: MASTER }; const r = await req('/sub/' + MASTER, { env: envNo }); check('works without KV', r.status===200); const h = await req('/health', { env: envNo }); check('health reports kv:false', (await h.json()).kv===false); }
// derived uuid
{ const u = await T.resolveUuid(HOST, {}); check('derived uuid stable + valid', T.isUuid(u) && u === await T.resolveUuid(HOST, {})); }
// qr
{ const r = await req('/qr.svg?text=hello'); check('qr svg', r.status===200 && (await r.text()).startsWith('<svg')); }
// geo endpoint shape (no network in test → ok:false but 200)
{ const r = await req('/api/geo?ip=1.1.1.1'); check('geo returns json', r.status===200 && typeof (await r.json()).ok==='boolean'); }
// scan targets
{ const r = await req('/api/scan-targets.json'); const j = await r.json(); check('scan targets', j.ranges.length>10 && j.sni===HOST); }
// wire parsers
{ const uuidHex = MASTER.replace(/-/g,''); const bytes = new Uint8Array([0, ...uuidHex.match(/../g).map(h=>parseInt(h,16)), 0, 1, 0x01,0xbb, 2, 11, ...new TextEncoder().encode('example.com'), 0x47,0x45,0x54]);
  const v = T.parseVlessHeader(bytes); check('vless header parse', v && v.uuid===MASTER && v.host==='example.com' && v.port===443 && v.command===1 && v.rest.length===3);
  check('sha224 vector', T.sha224Hex('abc')==='23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7');
  check('cf ip detection', T.isCloudflareIp('104.16.1.1') && !T.isCloudflareIp('8.8.8.8') && T.isCloudflareIp('2606:4700::1'));
  check('tunnel paths', T.isTunnelPath('/ws', {}) && T.isTunnelPath('/trojan', {}) && !T.isTunnelPath('/sub', {}));
}
// tunnel e2e with fake sockets
{
  const sent = []; let closed = false;
  const fakeSockets = { connect(opts) { const chunks=[]; let resolveRead; const readable = new ReadableStream({ start(c){ c.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\n\r\nhi')); c.close(); } });
    const writable = new WritableStream({ write(chunk){ sent.push(new TextDecoder().decode(chunk)); } }); return { opened: Promise.resolve(), readable, writable, close(){ closed = true; } }; } };
  T.__setSockets(fakeSockets);
  const out = []; const ws = { readyState: 1, listeners: {}, addEventListener(n,f){ (this.listeners[n]=this.listeners[n]||[]).push(f); }, send(d){ out.push(new Uint8Array(d)); }, close(){ this.readyState = 3; } };
  const uuidHex = MASTER.replace(/-/g,''); const header = new Uint8Array([0, ...uuidHex.match(/../g).map(h=>parseInt(h,16)), 0, 1, 0x00,0x50, 1, 93,184,216,34, ...new TextEncoder().encode('GET / HTTP/1.1\r\n\r\n')]);
  const job = T.handleTunnelConnection(ws, ENV, { earlyDataHeader: Buffer.from(header).toString('base64url'), masterUuid: MASTER });
  await new Promise(r=>setTimeout(r,50)); (ws.listeners.close||[]).forEach(f=>f({}));
  await job;
  check('tunnel: upstream got request', sent.join('').includes('GET / HTTP/1.1'));
  check('tunnel: downstream got vless response header + body', out.length>=1 && out[0][0]===0 && out[0][1]===0 && new TextDecoder().decode(out[0].subarray(2)).startsWith('HTTP/1.1 200'));
  check('tunnel: socket closed', closed);
}

// sub query overrides: pin one address / port / proto
{ const r = await req('/sub/' + MASTER + '?addr=1.2.3.4&port=443&proto=vless&limit=1'); const b = (await r.text()).trim().split('\n');
  check('?addr&port&proto&limit=1 → exactly one pinned vless config', b.length===1 && b[0].startsWith('vless://'+MASTER+'@1.2.3.4:443?'), b[0]);
  const c = await req('/clash/' + MASTER + '?addr=www.example.com&limit=2'); const y = await c.text();
  check('clash honours ?addr', y.includes('server: "www.example.com"') && !y.includes('1.2.3.4'));
  check('clash select group defaults to concrete proxy, Auto is fallback type', y.indexOf('type: select') < y.indexOf('⚡ Auto') && y.includes('type: fallback'));
  const sb = await (await req('/singbox/' + MASTER)).json(); const sel = sb.outbounds.find(o=>o.type==='selector');
  check('singbox selector default = first concrete outbound (stable exit)', sel.default !== '⚡ Auto' && sel.outbounds[0] === sel.default); }
// chain parsing + settings
{ const c = T.parseChain('socks5://user:p%40ss@1.2.3.4:1080'); check('parseChain socks5 w/ auth', c && c.type==='socks5' && c.user==='user' && c.pass==='p@ss' && c.host==='1.2.3.4' && c.port===1080);
  const h = T.parseChain('http://[2001:db8::1]:3128'); check('parseChain http v6', h && h.type==='http' && h.host==='2001:db8::1' && h.port===3128);
  check('parseChain rejects junk', !T.parseChain('vless://x') && !T.parseChain('socks5://host') && !T.parseChain('socks5://host:99999'));
  const r = await req('/api/settings', { method:'PUT', headers: auth, body:{ chain:'socks5://relay.example.net:1080', chainMode:'all' } }); const j = await r.json();
  check('settings accept chain', j.ok && j.settings.chain==='socks5://relay.example.net:1080' && j.settings.chainMode==='all');
  const g = await (await req('/api/settings', { headers: auth })).json(); check('settings GET exposes chain summary', g.chain && g.chain.type==='socks5' && g.chain.host==='relay.example.net');
  const bad = await (await req('/api/settings', { method:'PUT', headers: auth, body:{ chain:'garbage' } })).json(); check('invalid chain is dropped', bad.settings.chain==='');
  await req('/api/settings', { method:'PUT', headers: auth, body:{ chain:'' } }); }
// chain dial: fake SOCKS5 relay — every connection must go through it
{
  const dials = []; const relayWrites = [];
  function fakeRelaySocket() {
    let ctrl; const readable = new ReadableStream({ start(c){ ctrl = c; } });
    let stage = 0;
    const writable = new WritableStream({ write(chunk){ relayWrites.push(Array.from(chunk));
      if (stage===0) { ctrl.enqueue(new Uint8Array([5,0])); stage=1; return; }
      if (stage===1) { // CONNECT request: 5,1,0,atyp...
        const atyp = chunk[3]; const dom = atyp===3 ? new TextDecoder().decode(chunk.subarray(5,5+chunk[4])) : '';
        dials.push(dom); ctrl.enqueue(new Uint8Array([5,0,0,1, 9,9,9,9, 0,80])); stage=2; return; }
      // payload → echo back an HTTP answer
      ctrl.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\n\r\nvia-chain')); ctrl.close(); } });
    return { opened: Promise.resolve(), readable, writable, close(){} };
  }
  const connects = [];
  T.__setSockets({ connect(opts){ connects.push(opts.hostname+':'+opts.port); return fakeRelaySocket(); } });
  const envChain = { UUID: MASTER }; T.kvCacheClear();
  const settings = T.normalizeSettings({ chain: 'socks5://relay.example.net:1080', chainMode: 'all' });
  const out = []; const ws = { readyState: 1, listeners: {}, addEventListener(n,f){ (this.listeners[n]=this.listeners[n]||[]).push(f); }, send(d){ out.push(new Uint8Array(d)); }, close(){ this.readyState = 3; } };
  const uuidHex = MASTER.replace(/-/g,''); const header = new Uint8Array([0, ...uuidHex.match(/../g).map(h=>parseInt(h,16)), 0, 1, 0x01,0xbb, 2, 10, ...new TextEncoder().encode('google.com'), ...new TextEncoder().encode('GET / HTTP/1.1\r\n\r\n')]);
  const job = T.handleTunnelConnection(ws, envChain, { earlyDataHeader: Buffer.from(header).toString('base64url'), masterUuid: MASTER, settings });
  await new Promise(r=>setTimeout(r,80)); (ws.listeners.close||[]).forEach(f=>f({})); await job;
  check('chain: TCP went to the relay, not the destination', connects.length===1 && connects[0]==='relay.example.net:1080', connects.join());
  check('chain: SOCKS5 CONNECT carried the real destination', dials[0]==='google.com', dials.join());
  check('chain: payload relayed + reply delivered with vless header', out.length>=1 && out[0][0]===0 && new TextDecoder().decode(out[0].subarray(2)).includes('via-chain'));
  // http CONNECT variant
  const httpWrites = []; let hctrl;
  T.__setSockets({ connect(){ return { opened: Promise.resolve(), readable: new ReadableStream({ start(c){ hctrl=c; } }), writable: new WritableStream({ write(chunk){ const t=new TextDecoder().decode(chunk); httpWrites.push(t); if (t.startsWith('CONNECT')) hctrl.enqueue(new TextEncoder().encode('HTTP/1.1 200 Connection established\r\n\r\n')); else { hctrl.enqueue(new TextEncoder().encode('ok-http')); hctrl.close(); } } }), close(){} }; } });
  const d = await T.dialTarget('example.org', 443, envChain, T.normalizeSettings({ chain: 'http://u:p@proxy.example.net:3128' }), null);
  check('http CONNECT: request + basic auth sent', httpWrites[0].startsWith('CONNECT example.org:443 HTTP/1.1') && httpWrites[0].includes('Proxy-Authorization: Basic ' + Buffer.from('u:p').toString('base64')));
  check('http CONNECT: returns socket via chain', d.via==='chain');
  // chainMode=cf: non-CF destination goes direct
  const direct = []; T.__setSockets({ connect(opts){ direct.push(opts.hostname); return { opened: Promise.resolve(), readable: new ReadableStream({ start(c){ c.close(); } }), writable: new WritableStream(), close(){} }; } });
  const d2 = await T.dialTarget('example.org', 443, envChain, T.normalizeSettings({ chain: 'socks5://relay.example.net:1080', chainMode: 'cf' }), null);
  check('chainMode=cf: non-Cloudflare target dialled directly', d2.via==='direct' && direct[0]==='example.org');
  // strict chain: failure must not fall back
  T.__setSockets({ connect(){ throw new Error('relay down'); } });
  let threw = false; try { await T.dialTarget('example.org', 443, envChain, T.normalizeSettings({ chain: 'socks5://relay.example.net:1080', chainStrict: true }), null); } catch (e) { threw = true; }
  check('chainStrict: no fallback when relay is down', threw);
}

// countries: tags, preferred country first, strict filter, Clash fallback group, sing-box default
{
  T.kvCacheClear();
  const r = await (await req('/api/ips', { method:'POST', headers: auth, body:{ ips:['5.5.5.5#DE','6.6.6.6|TR','7.7.7.7'], replace:true } })).json();
  check('ips accept country tags, addresses stay clean', r.ips.join()==='5.5.5.5,6.6.6.6,7.7.7.7');
  const cs = await (await req('/api/countries', { headers: auth })).json();
  check('country summary groups addresses', cs.countries.map(c=>c.code).join()==='DE,TR' && cs.untagged.includes('7.7.7.7') && cs.countries[0].flag==='🇩🇪');
  const set = await (await req('/api/countries', { method:'PUT', headers: auth, body:{ country:'tr', countryFallback:'auto', ipCountries:{ '7.7.7.7':'nl' } } })).json();
  check('preferred country saved + manual tag', set.preferred==='TR' && set.countries.map(c=>c.code).join()==='DE,NL,TR');
  const sub = (await (await req('/sub/' + MASTER + '?limit=12')).text()).trim().split('\n');
  check('preferred country entries come first with flag names', sub[0].includes('@6.6.6.6:') && decodeURIComponent(sub[0].split('#')[1]).startsWith('🇹🇷'));
  check('other countries follow as fallback', sub.some(l=>l.includes('@5.5.5.5:')));
  const de = (await (await req('/sub/' + MASTER + '?country=DE&strict=1')).text()).trim().split('\n');
  check('?country=DE&strict=1 → only Germany', de.length>0 && de.every(l=>l.includes('@5.5.5.5:')));
  const y = await (await req('/clash/' + MASTER)).text();
  check('clash root = fallback [preferred country, Auto]', /name: "🐱 Cat"\n    type: fallback\n[\s\S]*?- "🇹🇷 Turkey"\n      - "⚡ Auto"/.test(y));
  check('clash has url-test group per country', y.includes('- name: "🇩🇪 Germany"\n    type: url-test') && y.includes('- name: "🇳🇱 Netherlands"'));
  const sb = await (await req('/singbox/' + MASTER)).json(); const sel = sb.outbounds.find(o=>o.tag==='🐱 Cat');
  check('singbox selector defaults to preferred country urltest', sel.default==='🇹🇷 Turkey' && sb.outbounds.some(o=>o.type==='urltest' && o.tag==='🇹🇷 Turkey'));
  await req('/api/countries', { method:'PUT', headers: auth, body:{ countryFallback:'none' } });
  const only = (await (await req('/sub/' + MASTER + '?limit=20')).text()).trim().split('\n');
  check('countryFallback=none → sub contains only preferred country', only.every(l=>l.includes('@6.6.6.6:')));
  const y2 = await (await req('/clash/' + MASTER)).text();
  check('countryFallback=none → clash root is select (no auto-leave)', /name: "🐱 Cat"\n    type: select/.test(y2));
  // proxy ip order honours preferred country
  const st = T.normalizeSettings({ country:'TR', proxyIps:['a.example','b.example'], proxyCountries:{ 'b.example':'TR' } });
  check('proxyIpList puts preferred-country proxy first', T.proxyIpList({}, st)[0]==='b.example');
  const colo = await req('/api/colo'); check('/api/colo is public JSON', colo.status===200 && (await colo.json()).ok===true);
  check('helpers', T.splitAddrTag('1.2.3.4#de').cc==='DE' && T.normalizeCountry('xx')==='' && T.countryLabel('')==='🌐 Other');
  await req('/api/countries', { method:'PUT', headers: auth, body:{ country:'', countryFallback:'auto' } });
  await req('/api/ips', { method:'POST', headers: auth, body:{ ips:[], replace:true } });
}

// routing toggles, fragment/TLS extras, full-Xray subscription
{
  T.kvCacheClear();
  const y0 = await (await req('/clash/' + MASTER)).text();
  check('defaults: bypass Iran on, ads off', y0.includes('GEOIP,IR,DIRECT') && y0.includes('GEOSITE,category-ir,DIRECT') && !y0.includes('category-ads-all'));
  const sv = await (await req('/api/settings', { method:'PUT', headers: auth, body:{ blockAds:true, bypassIran:false, fragment:{ enabled:true, packets:'1-3', length:'5-50', interval:'1-2' }, alpn:'h2', cipherSuites:'TLS_AES_128_GCM_SHA256:bad chars!' } })).json();
  check('settings normalise fragment/alpn/ciphers', sv.settings.fragment.packets==='1-3' && sv.settings.fragment.length==='5-50' && sv.settings.alpn==='h2' && sv.settings.cipherSuites==='TLS_AES_128_GCM_SHA256:badchars');
  const y1 = await (await req('/clash/' + MASTER)).text();
  check('clash: ads REJECT rule, no Iran rules when off', y1.includes('GEOSITE,category-ads-all,REJECT') && !y1.includes('GEOIP,IR,DIRECT'));
  const sb = await (await req('/singbox/' + MASTER)).json();
  check('singbox: ads rule_set + reject, tls_fragment, alpn', sb.route.rule_set.some(r=>r.tag==='geosite-ads') && sb.route.rules.some(r=>r.action==='reject') && sb.outbounds.some(o=>o.tls && o.tls_fragment===true && o.tls.alpn[0]==='h2'));
  const xr = await req('/xray/' + MASTER + '?raw=1&limit=2'); const cfgs = await xr.json();
  check('xray: full JSON configs list', Array.isArray(cfgs) && cfgs.length===2 && cfgs[0].outbounds[0].protocol==='vless' && cfgs[0].remarks);
  const o = cfgs[0].outbounds; const frag = o.find(x=>x.tag==='fragment');
  check('xray: fragment outbound + dialerProxy', frag && frag.settings.fragment.packets==='1-3' && o[0].streamSettings.sockopt.dialerProxy==='fragment');
  check('xray: tls extras + ws host', o[0].streamSettings.tlsSettings.cipherSuites.startsWith('TLS_AES') && o[0].streamSettings.wsSettings.headers.Host && o[0].streamSettings.tlsSettings.alpn[0]==='h2');
  check('xray: ads blocked, iran not bypassed', cfgs[0].routing.rules.some(r=>r.outboundTag==='block') && !cfgs[0].routing.rules.some(r=>(r.ip||[]).includes('geoip:ir')));
  const b64 = await (await req('/xray/' + MASTER + '?limit=1')).text();
  check('xray default body is base64 (v2rayNG import)', JSON.parse(Buffer.from(b64,'base64').toString()).length===1);
  const ux = await req('/u/' + MASTER + '/xray?raw=1&limit=1'); check('/u/<token>/xray works', ux.status===200);
  await req('/api/settings', { method:'PUT', headers: auth, body:{ blockAds:false, bypassIran:true, fragment:{ enabled:true }, alpn:'http/1.1', cipherSuites:'' } });
  const xr2 = (await (await req('/xray/' + MASTER + '?raw=1&limit=1')).json())[0];
  check('xray: iran bypass rules when on', xr2.routing.rules.some(r=>(r.ip||[]).includes('geoip:ir')) && xr2.routing.rules.some(r=>(r.domain||[]).includes('geosite:category-ir')));
  const links = (await (await req('/api/settings', { headers: auth })).json()).links; check('links expose xray', /\/xray\//.test(links.xray));
}
console.log(failures ? ('\n' + failures + ' FAILED') : '\nALL PASSED');
process.exit(failures ? 1 : 0);
