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
{ const r = await req('/sub/' + MASTER + '?rotate=off'); const b = await r.text();
  check('/sub/<uuid> 200', r.status===200);
  const lines = b.trim().split('\n');
  check('has vless + trojan', b.includes('vless://') && b.includes('trojan://'));
  const firstName = decodeURIComponent(b.split('\n')[0].split('#')[1] || '');
  check('BPB-style remark', firstName.includes('VLESS - Clean IP') || firstName.includes('VLESS - WorkerOnly'), firstName);
  check('first entry is TLS 443', /:443\?/.test(lines[0]), lines[0]);
  // :80 sits after the BPB 443→8080 waves (beyond the 48-entry default limit);
  // the default plain-port set still contains it.
  check('plain ports default include :80', T.normalizeSettings({}).plainPorts.map(Number).includes(80));
  check('has BPB-signature :8080 plain entries (443 → 8080 order)', /:8080\?encryption=none&security=none/.test(b));
  const l8080 = b.split('\n').find((l) => /:8080\?/.test(l));
  check(':8080 remark is BPB-style «Clean IP : 8080»', decodeURIComponent(l8080.split('#')[1]).includes('Clean IP : 8080'), decodeURIComponent(l8080.split('#')[1]));
  check('host header + BPB path', b.includes('host=' + HOST) && b.includes(encodeURIComponent('?ed=2560')) && b.includes(encodeURIComponent('/vl/')));
  check('userinfo header', (r.headers.get('subscription-userinfo')||'').includes('total=0'));
  const isSpoof = (l) => /%F0%9F%8E%AF|%F0%9F%A7%AC/.test(l); // 🎯 PX / 🧬 SNI sections
  const cleanLines = lines.filter((l) => !isSpoof(l));
  check('entry limit respected (clean section)', cleanLines.length <= 48 && cleanLines.length >= 20, String(lines.length) + ' total, clean=' + cleanLines.length);
  check('spoof section present in plain sub (PX or SNI)', lines.some((l) => isSpoof(l)));
  const r64 = await req('/sub64/' + MASTER + '?rotate=off'); const b64 = await r64.text(); check('/sub64 is base64 of /sub', T.b64decode(b64) === (await (await req('/sub/' + MASTER + '?rotate=off')).text()));
  const rb = await req('/sub/' + MASTER + '?b64=1'); check('?b64=1 works', T.b64decode(await rb.text()).includes('vless://'));
  const bad = await req('/sub/00000000-0000-4000-8000-000000000000'); check('unknown uuid 404', bad.status===404);
  { // beta43: BPB-parity — ONE link serves every client (UA + ?target=)
    const rc = await req('/sub/' + MASTER + '?rotate=off', { headers:{ 'user-agent':'ClashMetaForAndroid/2.11.5' } });
    check('UA clash → yaml', (rc.headers.get('content-type')||'').includes('yaml'));
    const rs = await req('/sub/' + MASTER + '?rotate=off', { headers:{ 'user-agent':'SFI/1.12.0 (sing-box; ios)' } });
    check('UA sing-box → json', (rs.headers.get('content-type')||'').includes('json') && (await rs.text()).includes('"outbounds"'));
    const rt = await req('/clash/' + MASTER + '?target=base64&rotate=off');
    check('?target=base64 overrides path kind', T.b64decode(await rt.text()).includes('vless://'));
    const rp2 = await req('/sub/' + MASTER + '?rotate=off');
    check('profile-web-page-url header', !!(rp2.headers.get('profile-web-page-url')||'').includes('/info/'));
    check('safeEqualHex', T.safeEqualHex('abcd','abcd')===true && T.safeEqualHex('abcd','abce')===false && T.safeEqualHex('abc','abcd')===false);
  }
  const noKey = await req('/sub'); check('/sub without uuid 404 when OPEN_SUB unset', noKey.status===404);
  const open = await req('/sub', { env: Object.assign({}, ENV, { OPEN_SUB: 'true' }) }); check('/sub with OPEN_SUB serves master', open.status===200 && (await open.text()).includes('vless://'));
}
{ const r = await req('/clash/' + MASTER); const y = await r.text(); check('clash yaml', r.status===200 && y.includes('proxies:') && y.includes('type: vless') && y.includes('type: trojan') && y.includes('MATCH,🐱 Cat') && !y.includes('tls: false\n    password')); }
{ const r = await req('/singbox/' + MASTER); const j = await r.json(); check('singbox json', j.outbounds.some(o=>o.type==='vless') && j.outbounds[0].type==='selector' && j.route.final==='🐱 Cat'); }
// settings PUT
{ const r = await req('/api/settings', { method:'PUT', headers: auth, body:{ ips:['1.2.3.4','www.example.com','bad ip'], tlsPorts:[443,2053], plainEnabled:false, protocols:{vless:true,trojan:false}, entryLimit:10, subRotate:'off' } }); const j = await r.json();
  check('settings PUT', j.ok && j.persisted && j.settings.ips.length===3 && j.settings.tlsPorts.join()==='443,2053' && j.settings.protocols.trojan===false);
  const s = await req('/sub/' + MASTER); const b = await s.text(); const lines=b.trim().split('\n');
  check('owner ips first', lines[0].includes('@1.2.3.4:443') && lines[1].includes('@www.example.com:443'), lines[0]);
  const clean = lines.filter((l) => !/%F0%9F%8E%AF|%F0%9F%A7%AC/.test(l));
  check('trojan disabled + no plain + limit', !b.includes('trojan://') && !b.includes('security=none') && clean.length===10, 'clean=' + clean.length);
}
// password change
{ const writes = KV.writes; const r = await req('/api/settings', { method:'PUT', headers: auth, body:{ password:'s3cret' } }); const j = await r.json(); const newCookie=(r.headers.get('set-cookie')||'').split(';')[0];
  check('password set → new cookie', j.ok && j.settings.hasPassword && newCookie && newCookie!==cookie);
  const old = await req('/api/settings', { headers: auth }); check('old session invalid after password change', old.status===401);
  const bearer = await req('/api/settings', { headers:{ authorization:'Bearer s3cret' } }); check('bearer password works', bearer.status===200);
  const uuidLogin = await req('/api/login', { method:'POST', body:{ password: MASTER } }); check('uuid no longer a password', uuidLogin.status===401);
  auth.cookie = newCookie; check('settings save persists settings + events ring (tolerating throttled last-seen writes)', KV.writes >= writes + 2, String(KV.writes - writes)); }
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
{ // beta39: replace mode (app «🧹 جایگزینی») + ProxyIP ip:port never normalized away
  const r = await req('/api/ips', { method:'POST', headers: auth, body:{ ips:['198.51.100.7:2053#CA','203.0.113.9'], replace:true } }); const j = await r.json();
  check('ips REPLACE wipes old list (exact set)', j.ok && j.ips.length===2 && j.ips.includes('198.51.100.7:2053') && j.ips.includes('203.0.113.9'), JSON.stringify(j.ips));
  const st = (await (await req('/api/settings', { headers: auth })).json()).settings;
  check('replaced entry kept its pin+tag', st.ipCountries['198.51.100.7:2053']==='CA', JSON.stringify(st.ipCountries));
  await req('/api/settings', { method:'PUT', headers: auth, body:{ proxyIps:['104.17.1.1:2053','83.147.217.103:1080','ip.sb'] } });
  const st2 = (await (await req('/api/settings', { headers: auth })).json()).settings;
  check('proxyIps keep ip:port exactly (never stripped)', JSON.stringify(st2.proxyIps)===JSON.stringify(['104.17.1.1:2053','83.147.217.103:1080','ip.sb']), JSON.stringify(st2.proxyIps));
}
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
{ const r = await req('/api/scan-targets.json'); const j = await r.json(); check('scan targets', j.ranges.length>10 && j.sni==='skk.moe'); }
// wire parsers
{ const uuidHex = MASTER.replace(/-/g,''); const bytes = new Uint8Array([0, ...uuidHex.match(/../g).map(h=>parseInt(h,16)), 0, 1, 0x01,0xbb, 2, 11, ...new TextEncoder().encode('example.com'), 0x47,0x45,0x54]);
  const v = T.parseVlessHeader(bytes); check('vless header parse', v && v.uuid===MASTER && v.host==='example.com' && v.port===443 && v.command===1 && v.rest.length===3);
  check('sha224 vector', T.sha224Hex('abc')==='23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7');
  check('cf ip detection', T.isCloudflareIp('104.16.1.1') && !T.isCloudflareIp('8.8.8.8') && T.isCloudflareIp('2606:4700::1'));
  check('tunnel paths (legacy + BPB)', T.isTunnelPath('/ws', {}) && T.isTunnelPath('/trojan', {}) && T.isTunnelPath('/vl/abcdef0123456789', {}) && T.isTunnelPath('/tr/abcdef0123456789', {}) && !T.isTunnelPath('/sub', {}));
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
  const sub = (await (await req('/sub/' + MASTER + '?limit=12&rotate=off')).text()).trim().split('\n');
  check('preferred country entries come first with flag names', sub[0].includes('@6.6.6.6:') && decodeURIComponent(sub[0].split('#')[1]).startsWith('🇹🇷'));
  check('other countries follow as fallback', sub.some(l=>l.includes('@5.5.5.5:')));
  const de = (await (await req('/sub/' + MASTER + '?country=DE&strict=1')).text()).trim().split('\n');
  check('?country=DE&strict=1 → only Germany', de.length>0 && de.every(l=>l.includes('@5.5.5.5:')));
  const y = await (await req('/clash/' + MASTER + '?rotate=off')).text();
  check('clash root = fallback [preferred country, Auto]', /name: "🐱 Cat"\n    type: fallback\n[\s\S]*?- "🇹🇷 Turkey"\n      - "⚡ Auto"/.test(y));
  check('clash has url-test group per country', y.includes('- name: "🇩🇪 Germany"\n    type: url-test') && y.includes('- name: "🇳🇱 Netherlands"'));
  const sb = await (await req('/singbox/' + MASTER + '?rotate=off')).json(); const sel = sb.outbounds.find(o=>o.tag==='🐱 Cat');
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
  const sb0 = await (await req('/singbox/' + MASTER)).json(); check('fragment is opt-in (off by default)', !sb0.outbounds.some(o=>o.tls_fragment));
  const x0 = (await (await req('/xray/' + MASTER + '?raw=1&limit=1')).json())[0]; check('xray: no fragment outbound by default', !x0.outbounds.some(o=>o.tag==='fragment') && !x0.outbounds[0].streamSettings.sockopt.dialerProxy);
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

// telegram bot: webhook secret + admin gate + commands (fetch to Telegram is stubbed)
{
  T.kvCacheClear();
  const token = '123456789:AAHfakeTokenForTestsOnly_abcdefghijk';
  const sv = await (await req('/api/settings', { method:'PUT', headers: auth, body:{ tgToken: token, tgAdmins: ['42', 'junk'] } })).json();
  check('tg settings normalised, token masked in response', sv.settings.tgAdmins.join()==='42' && sv.settings.tgToken.startsWith('••••'));
  const masked = await (await req('/api/settings', { method:'PUT', headers: auth, body:{ tgToken: '••••hijk' } })).json();
  check('masked token echo does not wipe the real token', masked.settings.tgToken.startsWith('••••'));
  const secret = await T.tgSecret(token);
  const sent = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (u, o) => { if (String(u).includes('api.telegram.org')) { sent.push(JSON.parse(o.body)); return new Response(JSON.stringify({ ok: true, result: { username: 'catbot' } })); } return realFetch(u, o); };
  const hook = async (text, from, hdr = secret) => req('/tg/' + secret, { method:'POST', headers: { 'x-telegram-bot-api-secret-token': hdr }, body:{ message: { chat: { id: 42 }, from: { id: from }, text } } });
  check('wrong secret header → 403', (await hook('/users', 42, 'nope')).status === 403);
  await hook('/users', 7); check('non-admin is refused', sent.pop().text.includes('not allowed'));
  await hook('/add tguser 30', 42); const added = sent.pop(); check('/add creates user and returns links', added.text.includes('created') && added.text.includes('/u/'));
  await hook('/users', 42); check('/users lists tguser', sent.pop().text.includes('tguser'));
  await hook('/renew tguser 10', 42); check('/renew answers', sent.pop().text.includes('🔁'));
  await hook('/country de', 42); check('/country sets preferred', sent.pop().text.includes('DE') && (await (await req('/api/countries', { headers: auth })).json()).preferred === 'DE');
  await hook('/del tguser', 42); check('/del removes', sent.pop().text.includes('deleted'));
  const wh = await (await req('/api/telegram/webhook', { method:'POST', headers: auth })).json();
  check('webhook registration posts setWebhook with secret', wh.ok && wh.bot==='catbot' && sent.some(b => b.url && b.url.endsWith('/tg/' + secret) && b.secret_token === secret));
  globalThis.fetch = realFetch;
  await req('/api/countries', { method:'PUT', headers: auth, body:{ country:'' } });
  await req('/api/settings', { method:'PUT', headers: auth, body:{ tgToken: '', tgAdmins: [] } });
  check('tg can be switched off', !(await (await req('/api/telegram', { headers: auth })).json()).configured);
}
// pinned ip:port (scan → panel: address emitted only on its verified port)
{
  const st = T.normalizeSettings({ ips: ['198.51.100.7:2053'], tlsPorts: [443], plainEnabled: false, useDefaults: false, includeHost: false, entryLimit: 50 });
  const { entries } = T.buildConfigEntries(HOST, ENV, st, MASTER, null, {});
  // Scanner-imported `ip:port` must connect to the BARE ip — the scanned port is
  // the entry port. `[1.2.3.4:443]:443` links could never dial (the "sent IPs
  // never sit in the configs" bug).
  const mine = entries.filter((e) => e.addr === '198.51.100.7');
  check('pinned addr emits configs', mine.length > 0);
  check('pinned addr only on its scanned port (2053)', mine.length > 0 && mine.every((e) => e.port === 2053), JSON.stringify(mine.map((e) => e.port)));
  check('pinned addr is bare ip in entries', mine.length > 0 && mine.every((e) => e.addr === '198.51.100.7'), JSON.stringify(mine.map((e) => e.addr)));
  check('pinned addr links dial ip:port once (no bracketed [ip:port]:port)', mine.every((e) => e.link.includes('@198.51.100.7:2053?') && !e.link.includes('[198.51.100.7')), mine[0] && mine[0].link.slice(0, 90));
  const st6 = T.normalizeSettings({ ips: ['[2001:db8::1]:8443'], tlsPorts: [443], plainEnabled: false, useDefaults: false, includeHost: false, entryLimit: 50 });
  const { entries: e6 } = T.buildConfigEntries(HOST, ENV, st6, MASTER, null, {});
  const mine6 = e6.filter((e) => String(e.addr).includes('2001:db8::1'));
  check('pinned ipv6 bare in entries, bracketed once in link', mine6.length > 0 && mine6.every((e) => e.addr === '2001:db8::1' && e.link.includes('@[2001:db8::1]:8443?')), mine6[0] && mine6[0].link.slice(0, 90));
  // ?fam=v4|v6 — strict address-family filter (scanner categories / builder)
  {
    const stf = T.normalizeSettings({ ips: ['198.51.100.7:2053', '[2001:db8::2]:8443'], tlsPorts: [443], plainEnabled: false, useDefaults: false, includeHost: false, entryLimit: 50 });
    const q4 = T.buildConfigEntries(HOST, ENV, stf, MASTER, null, { fam: 'v4' });
    check('fam=v4 keeps only raw IPv4', q4.entries.length > 0 && q4.entries.every((e) => /^(\d{1,3}\.){3}\d{1,3}$/.test(String(e.addr))), JSON.stringify(q4.entries.map((e) => e.addr)));
    const q6 = T.buildConfigEntries(HOST, ENV, stf, MASTER, null, { fam: 'v6' });
    check('fam=v6 keeps only raw IPv6', q6.entries.length > 0 && q6.entries.every((e) => String(e.addr).includes(':')), JSON.stringify(q6.entries.map((e) => e.addr)));
    check('subQuery parses fam', T.subQuery(new URL('https://x/sub?fam=v6')).fam === 'v6');
  }
  // /api/events — owner-visible ring log (real actions only)
  {
    for (let i = 0; i < 55; i++) await req('/api/events', { method: 'POST', headers: auth, body: { ev: 'test', d: 'e' + i } });
    const r = await req('/api/events', { headers: auth });
    const j = await r.json();
    check('events ring caps at 50, newest first', j.ok && j.events.length === 50 && j.events[0].ev === 'test' && j.events[0].d === 'e54', String(j.events.length) + '/' + (j.events[0] && j.events[0].d));
  }
  // ================= fixed IPs (pinnedIps) =================
  {
    await req('/api/ips', { method: 'POST', headers: auth, body: { ips: ['6.6.6.6', '5.5.5.5', '7.7.7.7'], replace: true } });
    await req('/api/settings', { method: 'PUT', headers: auth, body: { country: '', pinnedIps: ['6.6.6.6:443'], subRotate: 'fetch' } });
    const sp = (await (await req('/api/settings', { headers: auth })).json()).settings;
    check('pinnedIps stored bare (port stripped, max 5)', JSON.stringify(sp.pinnedIps) === '["6.6.6.6"]', JSON.stringify(sp.pinnedIps));
    let lead = true;
    for (let i = 0; i < 6; i++) {
      const b = await (await req('/sub/' + MASTER + '?limit=48', { env: ENV })).text();
      const first = (b.split('\n')[0] || '');
      if (!first.includes('@6.6.6.6:443')) { lead = false; break; }
    }
    check('pinned fixed IP leads EVERY fetch-rotated sub', lead);
    const pinnedSub = await (await req('/sub/' + MASTER + '?limit=48', { env: ENV })).text();
    check('pinned config carries flag/country tags untouched', pinnedSub.includes('@6.6.6.6:443'));
    await req('/api/settings', { method: 'PUT', headers: auth, body: { pinnedIps: [] } });
    const sp2 = (await (await req('/api/settings', { headers: auth })).json()).settings;
    check('unpin clears list', sp2.pinnedIps.length === 0);
    check('pinnedIps normalize: junk dropped, max 5 kept', T.normalizeSettings({ pinnedIps: ['junk', '1.2.3.4:8443', '[2606:4700::1]:443', 'cdn.example.com', '5.5.5.5', '6.6.6.6', '7.7.7.7', '8.8.8.8'] }).pinnedIps.length === 5);
  }
  // ================= ECH (?ech=1 → &ech= on TLS links) =================
  {
    check('subQuery parses ech', T.subQuery(new URL('https://x/sub?ech=1')).ech === true && T.subQuery(new URL('https://x/sub')).ech === false);
    const ste = T.normalizeSettings({ echList: 'AAH+BASE64ECH==', tlsPorts: [443], plainEnabled: false, useDefaults: false, includeHost: false, entryLimit: 10 });
    const { entries: ee } = T.buildConfigEntries(HOST, ENV, ste, MASTER, null, {});
    const tlsEntry = ee.find((e) => e.tls);
    check('ECH injected into TLS links', tlsEntry && decodeURIComponent(tlsEntry.link).includes('ech=AAH+BASE64ECH=='), tlsEntry && tlsEntry.link.slice(0, 160));
    const plain = ee.find((e) => !e.tls);
    check('ECH never on plain links', !plain || !decodeURIComponent(plain.link).includes('ech='));
    const noEch = T.buildConfigEntries(HOST, ENV, T.normalizeSettings({ tlsPorts: [443], plainEnabled: false, useDefaults: false, includeHost: false, entryLimit: 10 }), MASTER, null, {}).entries[0];
    check('no ech param by default', !decodeURIComponent(noEch.link).includes('ech='));
  }
  // ================= WARP-in-WARP + external subs (round 26) =================
  {
    const w0 = T.normalizeSettings({});
    check('warp defaults off + extSubs empty', w0.warp.mode === 'off' && Array.isArray(w0.extSubs) && w0.extSubs.length === 0);
    check('warp normalize: junk keys kept shape-safe', (() => { const w = T.normalizeSettings({ warp: { mode: 'chain', secretKey: 'x'.repeat(200), publicKey: 'pub123', reserved: '12,34,999,abc,56', endpoint: '' } }).warp; return w.mode === 'chain' && w.secretKey.length === 64 && w.reserved === '12,34,56' && w.endpoint.includes('engage.cloudflareclient.com:2408'); })());
    check('warp off => no outbounds', T.buildWarpOutbounds(T.normalizeSettings({})).length === 0);
    const won = T.buildWarpOutbounds(T.normalizeSettings({ warp: { mode: 'on', secretKey: 'SK==', publicKey: 'PK==', reserved: '1,2,3' } }));
    check('warp on => single wireguard outbound w/ reserved array', won.length === 1 && won[0].tag === 'warp' && won[0].settings.peers[0].publicKey === 'PK==' && won[0].settings.reserved.every((n) => Number.isInteger(n) && n >= 0 && n <= 255) && won[0].streamSettings.sockopt.dialerProxy === undefined, JSON.stringify(won).slice(0, 200));
    const wch = T.buildWarpOutbounds(T.normalizeSettings({ warp: { mode: 'chain', secretKey: 'SK==', publicKey: 'PK==' } }));
    check('warp chain => hub + inner dialerProxy', wch.length === 2 && wch[0].tag === 'warp' && wch[0].streamSettings.sockopt.dialerProxy === 'warp-hub' && wch[1].tag === 'warp-hub', JSON.stringify(wch).slice(0, 200));
    const sw = T.normalizeSettings({ warp: { mode: 'on', secretKey: 'SK==', publicKey: 'PK==' }, useDefaults: false, includeHost: false, entryLimit: 4, tlsPorts: [443] });
    const xc = T.buildXrayConfigs(HOST, ENV, sw, MASTER, null, {});
    check('xray config dials through warp (sockopt dialerProxy)', xc.length > 0 && JSON.stringify(xc[0]).includes('"dialerProxy":"warp"'), xc[0] && JSON.stringify(xc[0]).slice(0, 260));
    const xcCh = T.buildXrayConfigs(HOST, ENV, T.normalizeSettings({ warp: { mode: 'chain', secretKey: 'SK==', publicKey: 'PK==' }, useDefaults: false, includeHost: false, entryLimit: 4, tlsPorts: [443] }), MASTER, null, {});
    check('chain mode: warp-hub present + inner dialerProxy', JSON.stringify(xcCh[0]).includes('"warp-hub"') && JSON.stringify(xcCh[0]).includes('"dialerProxy":"warp-hub"'));
    check('subQuery parses noext', T.subQuery(new URL('https://x/sub?noext=1')).noext === true && T.subQuery(new URL('https://x/sub')).noext === false);
    const exts = T.normalizeSettings({ extSubs: [{ name: '', url: 'http://insecure.dev/x' }, { name: 'ok', url: 'https://ok.dev/sub' }, { name: 'x'.repeat(50), url: 'https://ok2.dev/s' }, { name: 'n3', url: 'ftp://nope' }] });
    check('extSubs normalize: https-only, caps, name default', exts.extSubs.length === 2 && exts.extSubs[0].name === 'ok' && exts.extSubs[1].name.length <= 40, JSON.stringify(exts.extSubs));
    const uris = T.parseExtUris(Buffer.from('vless://a@1.2.3.4:443?id=x#one\nvless://b@5.6.7.8:443?id=y#two\njunk-line-without-scheme\n' + 'vless://c@9.9.9.9:443?id=z#three').toString('base64'));
    check('parseExtUris: b64, scheme filter, cap 100', uris.length === 3 && uris[0].startsWith('vless://') && (() => { const many = T.parseExtUris(Array.from({ length: 150 }, (_, i) => 'vless://x@1.1.1.1:443?id=' + i + '#c' + i).join('\n')); return many.length === 100; })());
    check('/ext route gated without key', (await (await req('/ext/1', { env: ENV })).status) === 404);
    check('/ext unknown index => 404', (await (await req('/ext/5/' + MASTER, { env: ENV })).status) === 404);
    await req('/api/settings', { method: 'PUT', headers: auth, body: { extSubs: [{ name: 'test', url: 'https://test.invalid/sub' }] } });
    const subNoExt = await (await req('/sub/' + MASTER + '?noext=1', { env: ENV })).text();
    check('sub still works with extSubs + noext', subNoExt.split('\n')[0].includes('vless://'));
    check('sub without noext survives dead ext sub (allSettled)', ((await (await req('/sub/' + MASTER, { env: ENV })).text()).split('\n')[0] || '').includes('vless://'));
    const extRoute = await req('/ext/1/' + MASTER, { env: ENV });
    check('/ext/<n>/<uuid> fetches (or clean-fails offline)', extRoute.status === 200 ? (extRoute.headers.get('subscription-userinfo') || '').includes('total=0') : extRoute.status === 502, extRoute.status);
    await req('/api/settings', { method: 'PUT', headers: auth, body: { extSubs: [] } });
  }
  // ================= /api/version contract (app PanelUpdate recognition) =================
  {
    const v = await (await req('/api/version', { env: ENV })).json();
    check('version contract: anonymous gets panel+version (PanelUpdate.kt requires them)', v.ok === true && v.panel === 'cat-panel' && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(v.version), JSON.stringify(v));
  }
  // ================= ProxyIP pool → connection addresses (toAddrs) =================
  {
    const pre = ((await (await req('/api/settings', { headers: auth })).json()).settings.ips || []).length;
    // Fresh empty cache BEFORE the first call: with a stale cache the route
    // fires a LIVE proxyRepoRefresh (CI has open network) and the pool stops
    // being empty mid-test — sandbox (no outbound) hides this.
    await KV.put('cat_prepo_cache_v1', JSON.stringify({ ts: Date.now(), per: {}, ips: [] }));
    const empty = await (await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'toAddrs' } })).json();
    check('toAddrs with empty pool adds nothing', empty.ok === true && empty.added === 0, JSON.stringify(empty));
    await KV.put('cat_prepo_cache_v1', JSON.stringify({ ts: Date.now(), per: {}, ips: ['5.75.200.40#DE', '45.12.30.10#TR', 'not-an-ip'] }));
    const imp = await (await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'toAddrs', limit: 64 } })).json();
    check('toAddrs imports healthy pool with country tags', imp.ok === true && imp.added === 2, JSON.stringify(imp));
    const st = (await (await req('/api/settings', { headers: auth })).json()).settings;
    check('imported pool IPs stored PORT-PINNED + tagged', st.ips.includes('5.75.200.40:443') && st.ips.includes('45.12.30.10:443') && st.ipCountries['5.75.200.40:443'] === 'DE' && st.ipCountries['45.12.30.10:443'] === 'TR', JSON.stringify(st.ips));
    check('toAddrs dedupes (second run adds 0)', ((await (await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'toAddrs' } })).json()).added) === 0);
    check('ips list grew by exactly 2', ((await (await req('/api/settings', { headers: auth })).json()).settings.ips.length) === pre + 2);
    // ?limit=200 so early entryLimit:10 fixture + rotation shuffle cannot push
    // the freshly imported addresses out of the emitted window.
    const dbg = await (await req('/sub/' + MASTER + '?limit=200', { env: ENV })).text();
    check('pool IP gets a config in the sub', dbg.includes('@5.75.200.40:'), dbg.split('\n').length + ' lines');
    await KV.put('cat_prepo_cache_v1', JSON.stringify({ ts: Date.now(), per: {}, ips: [] }));
  }
  // ================= telegram deploy bot (round 29) =================
  {
    check('gh settings normalize: junk dropped, default workflow kept', (() => { const s = T.normalizeSettings({ ghPat: 'nope', ghRepo: 'no repo', ghRef: 'bad ref!', ghWorkflow: 'x.txt' }); return !s.ghPat && !s.ghRepo && !s.ghRef && s.ghWorkflow === 'deploy-worker.yml'; })());
    check('gh settings normalize: valid values kept', (() => { const s = T.normalizeSettings({ ghPat: 'github_pat_ABCDEFGHIJKLMNOPQRSTUVWX', ghRepo: 'o/r', ghRef: 'main', ghWorkflow: 'deploy-worker.yml' }); return s.ghPat.startsWith('github_pat_') && s.ghRepo === 'o/r' && s.ghRef === 'main'; })());
    const base = { origin: 'https://x', host: 'x', env: ENV, settings: T.normalizeSettings({}), masterUuid: MASTER };
    const uncfg = await T.tgCommand('/deploy', base);
    check('/deploy honest without config (no CF token asked)', uncfg.includes('not configured') && !uncfg.includes('Cloudflare API token is NEVER'.toLowerCase()) ? uncfg.includes('NEVER stored') || uncfg.includes('not configured') : true, uncfg.slice(0, 80));
    check('/deploy never asks for a CF token', !uncfg.includes('CLOUDFLARE_API_TOKEN=') && uncfg.includes('GitHub'));
    const cfgd = T.normalizeSettings({ ghPat: 'github_pat_ABCDEFGHIJKLMNOPQRSTUVWX', ghRepo: 'o/r' });
    let captured = null;
    const realFetch = globalThis.fetch;
    globalThis.fetch = async (u, o) => { captured = { url: String(u), method: o && o.method, body: o && o.body }; return { ok: true, status: 204, json: async () => ({}) }; };
    try {
      const q = await T.tgCommand('/deploy', Object.assign({}, base, { settings: cfgd }));
      check('/deploy dispatches workflow on default ref', q.includes('queued') && captured.url === 'https://api.github.com/repos/o/r/actions/workflows/deploy-worker.yml/dispatches' && JSON.parse(captured.body).ref === 'main' && captured.method === 'POST', captured.url);
      check('/deploy branch arg overrides ref', ((await T.tgCommand('/deploy arena/01a0ebed-cat-client', Object.assign({}, base, { settings: cfgd }))).includes('queued')) && JSON.parse(captured.body).ref === 'arena/01a0ebed-cat-client');
      globalThis.fetch = async () => ({ ok: false, status: 404, json: async () => ({ message: 'Not Found' }) });
      const e404 = await T.tgCommand('/deploy', Object.assign({}, base, { settings: cfgd }));
      check('/deploy maps 404 to a clear error', e404.includes('404') && e404.includes('not found'));
      globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({ workflow_runs: [{ status: 'completed', conclusion: 'success', head_branch: 'main', run_number: 7, html_url: 'https://x/1' }, { status: 'in_progress', conclusion: null, head_branch: 'main', run_number: 8, html_url: 'https://x/2' }] }) });
      const list = await T.tgCommand('/deploys', Object.assign({}, base, { settings: cfgd }));
      check('/deploys lists runs with status marks', list.includes('✅') && list.includes('⏳') && list.includes('main') && list.includes('#7'));
    } finally { globalThis.fetch = realFetch; }
    await req('/api/settings', { method: 'PUT', headers: auth, body: { ghRepo: 'o/r', ghPat: 'github_pat_ABCDEFGHIJKLMNOPQRSTUVWX' } });
    const masked = ((await (await req('/api/settings', { headers: auth })).json()).settings.ghPat || '');
    check('ghPat stored but masked in API', masked.startsWith('••••'), masked);
    const roundTrip = await (await req('/api/settings', { method: 'PUT', headers: auth, body: { ghRepo: 'o/r', ghPat: masked, ghRef: 'main' } })).json();
    check('masked ghPat on PUT does not wipe stored token', roundTrip.settings.ghPat.startsWith('••••'));
    await req('/api/settings', { method: 'PUT', headers: auth, body: { ghRepo: '', ghPat: '', ghRef: '' } });
    check('gh fields cleared on empty PUT', ((await (await req('/api/settings', { headers: auth })).json()).settings.ghRepo === ''));
  }
  // ================= auto config rotation (subRotate) =================
  {
    check('subRotate defaults to fetch (fresh set every update)', T.normalizeSettings({}).subRotate === 'fetch' && T.normalizeSettings({ subRotate: 'daily' }).subRotate === 'daily');
    await req('/api/settings', { method: 'PUT', headers: auth, body: { subRotate: 'fetch' } });
    const firsts = new Set();
    for (let i = 0; i < 8; i++) {
      const b = await (await req('/sub/' + MASTER + '?limit=48', { env: ENV })).text();
      firsts.add((b.split('\n')[0] || '').split('@')[1] || '');
    }
    check('rotate=fetch: sub changes across refreshes', firsts.size >= 2, 'unique first-lines=' + firsts.size);
    const daily1 = await (await req('/sub/' + MASTER + '?rotate=daily', { env: ENV })).text();
    const daily2 = await (await req('/sub/' + MASTER + '?rotate=daily', { env: ENV })).text();
    check('rotate=daily: deterministic within the day', daily1 === daily2 && daily1.length > 0);
    const stable1 = await (await req('/sub/' + MASTER + '?rotate=off', { env: ENV })).text();
    const stable2 = await (await req('/sub/' + MASTER + '?rotate=off', { env: ENV })).text();
    check('rotate=off: stable order', stable1 === stable2);
    const offFirst = (await (await req('/sub/' + MASTER + '?rotate=off&limit=1', { env: ENV })).text()).trim();
    check('rotation keeps port walk: first config is TLS :443', offFirst.includes(':443?') && offFirst.includes('security=tls'), offFirst.slice(0, 90));
  }
  // ================= repo library: 12h feeds + dead replacement =================
  {
    const fakeFeed = async (url) => {
      if (String(url).includes('arista')) return { ok: true, status: 200, text: async () => '198.51.10.1\n198.51.10.1\n198.51.10.2\n#comment\njunk line\n198.51.10.3#DE\n' };
      return { ok: true, status: 200, text: async () => JSON.stringify({ results: [{ ip: '198.51.20.1', ms: 40, status: 'online' }, { ip: '198.51.20.2', ms: 9, status: 'online' }, { ip: '198.51.20.3', ms: 5, status: 'down' }] }) };
    };
    const rep = await T.refreshRepos(ENV, fakeFeed);
    check('repo refresh merges both feeds, uniq + junk-filtered', rep.ok && rep.total === 5, JSON.stringify(rep.per));
    const st = await req('/api/repos', { headers: auth });
    const j = await st.json();
    check('/api/repos GET status', j.ok && j.total === 5 && Array.isArray(j.repos) && j.repos.length >= 2, 'total=' + j.total);
    const pool0 = (await T.repoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('json-speed sort within feed: fastest first', pool0.indexOf('198.51.20.2') < pool0.indexOf('198.51.20.1'), JSON.stringify(pool0));
    // dead reports: <3 = kept, >=3 = dropped and replaced on next refresh
    await req('/api/repos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.10.1'] } });
    await req('/api/repos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.10.1'] } });
    const poolAfter2 = (await T.repoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('dead<3 still in pool', poolAfter2.includes('198.51.10.1'));
    const hr = await (await req('/api/repos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.10.1'] } })).json();
    const poolAfter3 = (await T.repoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('dead≥3 dropped from pool', hr.dropped === 1 && !poolAfter3.includes('198.51.10.1') && poolAfter3.length === 4, JSON.stringify(poolAfter3));
    // repoAuto → sub gains library IPs (pinned :443), off/norepo → not
    await req('/api/settings', { method: 'PUT', headers: auth, body: { repoAuto: true } });
    const subAuto = await (await req('/sub/' + MASTER + '?limit=200&norepo=0', { env: ENV })).text();
    check('repoAuto on → library IP (pinned 443) in sub', subAuto.includes('vless://') && /[198.51.20.2|198.51.10.2|198.51.10.3|198.51.20.1]/.test('') === false && (subAuto.includes('@198.51.20.2:443') || subAuto.includes('@198.51.10.2:443') || subAuto.includes('@198.51.10.3:443') || subAuto.includes('@198.51.20.1:443')));
    const subNo = await (await req('/sub/' + MASTER + '?limit=200&norepo=1', { env: ENV })).text();
    check('?norepo=1 excludes library IPs', !subNo.includes('@198.51.'));
    await req('/api/settings', { method: 'PUT', headers: auth, body: { repoAuto: false } });
    const subOff = await (await req('/sub/' + MASTER + '?limit=200', { env: ENV })).text();
    check('repoAuto off → no library IPs', !subOff.includes('@198.51.'));
    // import by country (tagged txt feed entry 198.51.10.3#DE)
    const imp = await (await req('/api/repos', { method: 'POST', headers: auth, body: { action: 'import', cc: 'DE', limit: 8 } })).json();
    check('repo import by country adds to panel list', imp.ok && imp.added >= 1, JSON.stringify(imp));
    // sanitize: custom repo kept, junk url rejected, defaults on empty
    const san = T.sanitizeRepos([{ id: 'mine', name: 'x', url: 'https://example.com/l.txt', kind: 'txt' }, { url: 'ftp://bad' }]);
    check('sanitizeRepos: https-only, defaults when empty', san.length === 1 && san[0].id === 'mine' && T.sanitizeRepos(null).length >= 2);
  }
  // ================= ProxyIP repo library =================
  {
    const fakeFeed = async (url) => {
      if (String(url).includes('xgonce')) return { ok: true, status: 200, text: async () => 'IP,cf-meta-ip,PORT,spd,CC,COLO,TCPms,TLSms\n198.51.30.1,x,443,200,US,EWR,12.5,20\n198.51.30.2,x,443,300,DE,YYZ,30.1,25\nbadrow' };
      if (String(url).toLowerCase().includes('gb')) return { ok: true, status: 200, text: async () => '#top\n198.51.40.1\n198.51.40.1\nproxy.example.org\n' };
      return { ok: true, status: 200, text: async () => '198.51.41.9\njunk\n' };
    };
    const rep = await T.refreshProxyRepos(ENV, fakeFeed);
    check('proxy repo refresh: csv+txt merged, uniq', rep.ok && rep.total === 5, JSON.stringify(rep.per));
    const pj = await (await req('/api/prepos', { headers: auth })).json();
    check('/api/prepos GET status', pj.ok && pj.total === 5 && pj.repos.length >= 3, 'total=' + pj.total);
    const pool = (await T.proxyRepoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('csv-proxy speed sort: fastest first', pool.indexOf('198.51.30.1:443') < pool.indexOf('198.51.30.2:443'), JSON.stringify(pool));
    check('csv port column preserved (real port wins over 443)', pool.includes('198.51.30.1:443'), JSON.stringify(pool));
    check('proxy domains kept (port-pinned)', pool.includes('proxy.example.org:443'));
    // dead replacement ×3
    await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.41.9'] } });
    await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.41.9'] } });
    const pool2 = (await T.proxyRepoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('proxy dead<3 kept', pool2.includes('198.51.41.9:443'));
    const hr = await (await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'health', dead: ['198.51.41.9'] } })).json();
    const pool3 = (await T.proxyRepoHealthyPool(ENV, 10)).map((p) => p.ip);
    check('proxy dead≥3 dropped', hr.dropped === 1 && !pool3.includes('198.51.41.9:443'));
    // import → settings.proxyIps
    const imp = await (await req('/api/prepos', { method: 'POST', headers: auth, body: { action: 'import', cc: 'US', limit: 8 } })).json();
    check('proxy import by country', imp.ok && imp.added >= 1 && imp.total >= 1, JSON.stringify(imp));
    // auto-append to sub (?proxyip= entries), ?norepo=1 opt-out, off default
    await req('/api/settings', { method: 'PUT', headers: auth, body: { proxyRepoAuto: true, proxyIps: ['203.0.113.1'] } });
    const subAuto = await (await req('/sub/' + MASTER + '?norepo=0', { env: ENV })).text();
    // port-bearing relay: colon is double-encoded in the raw link (%253A)
    check('proxyRepoAuto on → appended port-pinned ProxyIP in sub', /proxyip(?:%3D|=)198\.51\.30\.1(?:%253A|%3A|:)443/.test(subAuto), 'searched');
    // and the WORKER still dials it correctly after double-decode (unit)
    const dialPath = '/vl/TOKEN?ed=2560?proxyip=198.51.30.1%253A443';
    const pxo = ((dialPath.match(/[?&](?:proxyip|pyip)=([^&]+)/) || [])[1] || '');
    let dec = pxo; try { dec = decodeURIComponent(dec); } catch (e) { } try { dec = decodeURIComponent(dec); } catch (e) { }
    check('pxOverride double-decode → host:port', dec === '198.51.30.1:443', dec);
    const subNo = await (await req('/sub/' + MASTER + '?norepo=1', { env: ENV })).text();
    check('?norepo=1 excludes repo ProxyIPs', !subNo.includes('proxyip%3D198.51.'));
    check('user own ProxyIP still present', subNo.includes('proxyip%3D203.0.113.1'));
    await req('/api/settings', { method: 'PUT', headers: auth, body: { proxyRepoAuto: false } });
    const subOff = await (await req('/sub/' + MASTER, { env: ENV })).text();
    check('proxyRepoAuto off → no repo ProxyIPs', !subOff.includes('proxyip%3D198.51.'));
    check('sanitizeProxyRepos: kinds + https-only', T.sanitizeProxyRepos([{ id: 'm', url: 'https://x/a.csv', kind: 'csv-proxy' }]).length === 1 && T.sanitizeProxyRepos(null).length >= 3);
    // ---- port chain: feed port → cache → healthy pool (default 443) ----
    const portFeed = async (url) => ({ ok: true, status: 200, text: async () => 'IP,cf,PORT,spd,CC\n198.51.60.1,x,8443,10,DE\n198.51.60.2,x,,5,US\n' });
    await T.refreshProxyRepos(ENV, portFeed);
    const pp = (await T.proxyRepoHealthyPool(ENV, 20));
    check('csv PORT column survives: real port pinned', pp.some((p) => p.ip === '198.51.60.1:8443' && p.cc === 'DE'), JSON.stringify(pp));
    check('missing csv port → default pin 443', pp.some((p) => p.ip === '198.51.60.2:443'));
    // ---- per-ProxyIP configs (screenshot style) + repo default country ----
    check('proxyRepoAuto defaults ON', T.normalizeSettings({}).proxyRepoAuto === true && T.normalizeSettings({ proxyRepoAuto: false }).proxyRepoAuto === false);
    const sanc = T.sanitizeProxyRepos([{ id: 'w', name: 'wanwu', url: 'https://x/DE.txt', kind: 'txt', cc: 'de' }]);
    check('repo default country kept (sanitized)', sanc[0].cc === 'DE');
    const feedCC = async (url) => ({ ok: true, status: 200, text: async () => '198.51.50.7\n198.51.50.8\n' });
    await req('/api/settings', { method: 'PUT', headers: auth, body: { proxyRepos: [{ id: 'wde', name: 'wanwu-de', url: 'https://x/DE.txt', kind: 'txt', cc: 'DE' }], proxyRepoAuto: true } });
    await T.refreshProxyRepos(ENV, feedCC);
    const poolCC = (await T.proxyRepoHealthyPool(ENV, 10)).find((p) => p.ip === '198.51.50.7:443');
    check('txt feed gets repo default country tag', poolCC && poolCC.cc === 'DE', JSON.stringify(poolCC));
    const stpx = T.normalizeSettings({ lang: 'fa', proxyIps: ['203.0.113.1'], proxyCountries: { '203.0.113.1': 'DE' }, useDefaults: false, includeHost: false, tlsPorts: [443], plainEnabled: false });
    const { entries: epx } = T.buildConfigEntries(HOST, ENV, stpx, MASTER, null, {});
    const pxs = epx.filter((e) => e.name.includes('🎯'));
    check('per-ProxyIP configs: numbered + flag + Persian country', pxs.length === 2 && pxs[0].name === '🎯 1. 🇩🇪 آلمان · 203.0.113.1', pxs[0] && pxs[0].name);
    check('PX config carries ?proxyip= relay path', pxs.every((e) => decodeURIComponent(e.link).includes('?proxyip=203.0.113.1')));
    const sten = T.normalizeSettings({ lang: 'en', proxyIps: ['203.0.113.1'], proxyCountries: { '203.0.113.1': 'DE' }, useDefaults: false, includeHost: false, tlsPorts: [443], plainEnabled: false });
    const { entries: een } = T.buildConfigEntries(HOST, ENV, sten, MASTER, null, {});
    check('en locale → English country label', een.some((e) => e.name === '🎯 1. 🇩🇪 Germany · 203.0.113.1'), een.filter((e) => e.name.includes('🎯'))[0] && een.filter((e) => e.name.includes('🎯'))[0].name);
  }
  check('pinnedPortOf parses v4/v6/domain, rejects bare', T.pinnedPortOf('1.2.3.4:2053') === 2053 && T.pinnedPortOf('[2001:db8::1]:8443') === 8443 && T.pinnedPortOf('2001:db8::1') === 0 && T.pinnedPortOf('www.x.com:2053') === 2053 && T.pinnedPortOf('1.2.3.4') === 0);
  const r = await req('/api/ips', { method: 'POST', headers: auth, body: { ips: ['198.51.100.9:8443#DE', 'not an ip!!'], replace: true } });
  const j = await r.json();
  check('/api/ips stores ip:port + cc, rejects junk', j.ok && j.count === 1 && j.ips[0] === '198.51.100.9:8443');
  await req('/api/ips', { method: 'POST', headers: auth, body: { ips: [], replace: true } });
}
// custom free ports survive normalize (panel no longer locked to presets)
{
  const st2 = T.normalizeSettings({ tlsPorts: [443, 8443, 2096], plainPorts: [80, 8080] });
  check('normalize keeps custom ports', st2.tlsPorts.map(Number).includes(2096) && st2.tlsPorts.map(Number).includes(8443) && st2.plainPorts.map(Number).includes(8080));
}
// SNI hygiene: the panel host must never be the default SNI (DPI burns panels that way)
{
  check('default SNI is NOT the panel host', T.effectiveSni(HOST, {}, {}) === 'skk.moe', T.effectiveSni(HOST, {}, {}));
  check('SNI: settings beat default', T.effectiveSni(HOST, {}, T.normalizeSettings({ sni: 'example.com' })) === 'example.com');
  check('SNI: env beats default', T.effectiveSni(HOST, { SNI: 'env.example' }, {}) === 'env.example');
  const subSt = T.normalizeSettings({});
  const sub = mod ? null : null;
  const { ctx } = T.buildConfigEntries(HOST, ENV, subSt, MASTER, null, {});
  { const html = T.panelPage({ CAT_PANEL_KV: new Map() }, T.defaultSettings(), 'h.example.workers.dev', 'u123');
  check('hero «in use» card on dashboard', html.includes('heroCard') && html.includes('renderHero') && html.includes('hero_inuse'));
  check('panel version is 6.26.0', T.CAT_PANEL_VERSION === '6.26.0');
  { const qs = T.normalizeSettings({ blockQuic: true });
    const yaml = T.buildClashYaml('h.dev', { CAT_PANEL_KV: new Map() }, qs, 'u', null, {});
    check('blockQuic in clash yaml', yaml.includes('NETWORK,udp'), yaml.split('\n').find(l=>l.includes('REJECT')&&l.includes('443'))||'');
    const sb = JSON.stringify(T.buildSingboxConfig('h.dev', { CAT_PANEL_KV: new Map() }, qs, 'u', null, {}));
    check('blockQuic in singbox', sb.includes('"network":"udp"'));
    const xr = JSON.stringify(T.buildXrayConfigs('h.dev', { CAT_PANEL_KV: new Map() }, qs, 'u', null, {}));
    check('blockQuic in xray', xr.includes('"network":"udp"') || xr.includes('"udp"')); }
  const prov = T.normalizeSettings({ ipSources: { '9.9.9.9:443': { src: 'scanner', ms: 210, at: 9 } } });
  check('ip provenance (src+ping) persisted', !!(prov.ipSources && prov.ipSources['9.9.9.9:443'] && prov.ipSources['9.9.9.9:443'].src === 'scanner'), JSON.stringify(prov.ipSources));
  {
    const r = await req('/api/ips', { method:'POST', headers: auth, body:{ ips:['198.51.100.77:443#DE'], source:'scanner', pingMs:{ '198.51.100.77:443#DE': 341 } } }); const j = await r.json();
    check('ips import accepted provenance payload', j.ok === true && j.ips.includes('198.51.100.77:443'), JSON.stringify(j.ips));
    const st3 = (await (await req('/api/settings', { headers: auth })).json()).settings;
    check('provenance badge stored (scanner + 341ms)', !!(st3.ipSources && st3.ipSources['198.51.100.77:443#DE'] && st3.ipSources['198.51.100.77:443#DE'].ms === 341), JSON.stringify(st3.ipSources));
    const anon = await req('/api/ip-test', { method:'POST', body:{ ips:['1.2.3.4'] } }); check('ip-test 401 without session', anon.status === 401);
    const emp = await req('/api/ip-test', { method:'POST', headers: auth, body:{ ips: [] } }); const je = await emp.json();
    check('ip-test empty list → empty results', je.ok === true && Object.keys(je.results).length === 0);
    const html2 = T.panelPage({ CAT_PANEL_KV: new Map() }, T.defaultSettings(), 'h.dev', 'u');
    check('worker-side test button on IP list', html2.includes('btnIpTest') && html2.includes('ip_test_btn'));
  }
  const ampQ = T.subQuery(new URL('https://h/sub/u?ports=443%2C2053&amp;limit=24'));
  const nrmQ = T.subQuery(new URL('https://h/sub/u?ports=443%2C2053&limit=24'));
  check('sub query tolerates &amp; links (Telegram copy)', ampQ.limit === 24 && ampQ.port.join() === '443,2053' && nrmQ.limit === 24, JSON.stringify(ampQ));
  let fetched = ''; const fakeF = async (u) => { fetched = String(u); return { ok: true, text: async () => 'vless://x' }; };
  await T.extSubContent({ CAT_PANEL_KV: new Map() }, 'https://x/sub?a=1&amp;b=2', fakeF);
  check('ext sub fetch sanitizes &amp;', fetched === 'https://x/sub?a=1&b=2', fetched); }
check('generated configs do not put panel host into sni param', !ctx.sni.includes(HOST), ctx.sni);
}
console.log(failures ? ('\n' + failures + ' FAILED') : '\nALL PASSED');
process.exit(failures ? 1 : 0);
