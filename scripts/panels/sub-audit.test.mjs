/**
 * 🔎 Whole-subscription audit (beta204): every config the panel hands out, in every format, across a matrix of
 * real-world settings (own clean IPs with pinned ports, IPv6, ip#CC, hostnames, plain ports, ProxyIPs with their
 * own ports, SOCKS5 relays, per-user firewall profiles / IPs, ?fw= ?survive= ?proto= ?port= ?addr= ?sni= ?ech=
 * ?fam= ?rotate= …). Nothing is dialled — this checks that every link / YAML / JSON entry is complete and sane:
 *   - vless/trojan URI shape, UUID / password, address (IPv6 bracketed, no «#CC» glued on), port 1–65535;
 *   - TLS links on TLS ports only, plain links on plain ports only; TLS links carry a valid SNI; ws links a path;
 *   - no «undefined» / «null» / «NaN» / empty value anywhere;
 *   - a scan-pinned IP:port only ever appears on exactly that port (no guessed port);
 *   - ProxyIPs keep exactly their configured ports; SOCKS credentials appear only inside the encoded path;
 *   - Clash YAML / sing-box JSON / Xray JSON parse, every referenced tag exists, every port is a number.
 * Usage: node scripts/panels/sub-audit.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const mod = await import(workerPath);
const worker = mod.default;
const DEFAULT_PX = (mod._testing && mod._testing.DEFAULT_PROXY_IPS) || [];

let failures = 0, passes = 0;
const seen = new Set();
function check(name, cond, extra) {
  if (cond) { passes++; return; }
  const key = name + '|' + (extra || '').slice(0, 60);
  if (seen.has(key)) { failures++; return; }
  seen.add(key); failures++;
  console.error('✗ ' + name + (extra ? ' — ' + extra : ''));
}
class FakeKV { constructor() { this.m = new Map(); } async get(k, o) { const v = this.m.has(k) ? this.m.get(k) : null; return v != null && o && (o === 'json' || o.type === 'json') ? JSON.parse(v) : v; } async put(k, v) { this.m.set(k, String(v)); } async delete(k) { this.m.delete(k); } async list() { return { keys: [...this.m.keys()].map((name) => ({ name })), list_complete: true }; } }
const UUID = '41111111-2222-4333-8444-555555555555';
const TROJAN_PASS = 'audit-pass-7x';
const TLS = [443, 2053, 2083, 2087, 2096, 8443];
const PLAIN = [80, 8080, 8880, 2052, 2082, 2086, 2095];
const PROXY_IPS = ['103.237.102.191:11111', '184.75.221.82:3118'];
const SOCKS = 'socks5://u1:p%40ss@5.6.7.8:3128';
const PINNED = { '104.17.2.2': 2053, '172.64.1.1': 80, '2606:4700::7': 8443 };
const HOSTNAME_RE = /^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9-]{2,63}$/i;
const FPS = new Set(['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', '360', 'qq', 'random', 'randomized', 'unsafe', 'randomizednoalpn']);

function validateLink(link, ctx) {
  const tag = ctx.label;
  const m = /^(vless|trojan):\/\/([^@]+)@(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9._-]+):(\d+)(\/?)\?([^#]*)#(.*)$/.exec(link);
  check(tag + ': link shape', !!m, link.slice(0, 160));
  if (!m) return null;
  const [, proto, cred, addr, portS, , qs, frag] = m;
  const port = Number(portS);
  check(tag + ': port range', port >= 1 && port <= 65535, link.slice(0, 120));
  if (proto === 'vless') check(tag + ': vless uuid', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(cred), cred);
  else check(tag + ': trojan password non-empty', decodeURIComponent(cred).length >= 3, cred);
  check(tag + ': no #CC glued in the address', !/#/.test(addr));
  const bare = addr.replace(/^\[|\]$/g, '');
  if (bare.includes(':')) check(tag + ': IPv6 is bracketed', addr.startsWith('['), addr);
  else check(tag + ': address is IPv4 or hostname', /^\d{1,3}(\.\d{1,3}){3}$/.test(bare) ? bare.split('.').every((o) => Number(o) <= 255) : HOSTNAME_RE.test(bare), bare);
  const p = new URLSearchParams(qs);
  for (const [k, v] of p) {
    check(tag + ': no undefined/null/NaN in ' + k, !/undefined|^null$|NaN|\[object/.test(v), k + '=' + v);
    if (!['path', 'alpn', 'fp', 'ech', 'pqv', 'fm', 'spx', 'extra', 'pbk', 'sid', 'serviceName', 'mode', 'headerType', 'allowInsecure', 'insecure', 'flow', 'encryption', 'packetEncoding', 'fragment', 'ed', 'eh', 'cipherSuites', 'finalmask', 'finalMask'].includes(k)) check(tag + ': empty param ' + k, v !== '', k);
  }
  check(tag + ': name present', decodeURIComponent(frag || '').trim().length > 0);
  check(tag + ': name has no undefined/NaN', !/undefined|NaN/.test(decodeURIComponent(frag || '')), decodeURIComponent(frag).slice(0, 80));
  const sec = p.get('security') || 'none';
  const type = p.get('type') || 'tcp';
  if (sec === 'tls') {
    check(tag + ': TLS link not on a plain-only port', !PLAIN.includes(port) || ctx.allowAnyPort, port + ' ' + link.slice(0, 100));
    const sni = p.get('sni') || '';
    check(tag + ': TLS link has a valid SNI', HOSTNAME_RE.test(sni), 'sni=' + sni + ' ' + link.slice(0, 100));
    const fp = p.get('fp');
    if (fp) check(tag + ': fingerprint known', FPS.has(fp), fp);
    const alpn = p.get('alpn');
    if (alpn) check(tag + ': alpn values valid', alpn.split(',').every((a) => ['h2', 'http/1.1', 'h3'].includes(a)), alpn);
  } else {
    check(tag + ': security none/tls only', sec === 'none', sec);
    check(tag + ': plain link not on a TLS-only port', !TLS.includes(port) || ctx.allowAnyPort, port + ' ' + link.slice(0, 100));
  }
  if (type === 'ws' || type === 'xhttp' || type === 'httpupgrade') {
    const pth = p.get('path') || '';
    check(tag + ': transport path starts with /', pth.startsWith('/'), pth);
    check(tag + ': one «?» in path at most', (pth.match(/\?/g) || []).length <= 1, pth);
    check(tag + ': host header present', HOSTNAME_RE.test(p.get('host') || ''), 'host=' + p.get('host'));
    const pq = pth.includes('?') ? new URLSearchParams(pth.split('?')[1]) : new URLSearchParams();
    const pxi = pq.get('proxyip') || pq.get('pxip');
    if (pxi) {
      const known = PROXY_IPS.concat(DEFAULT_PX);
      check(tag + ': proxyip is one of the configured / built-in entries, with its own port', known.includes(pxi) || /^relay:[0-9a-f]+$/.test(pxi), pxi);
    }
  } else if (type !== 'tcp' && type !== 'grpc') {
    check(tag + ': known transport', false, type);
  }
  check(tag + ': socks credentials never in clear outside path', !link.replace(/path=[^&#]*/, '').includes('p%40ss') && !link.replace(/path=[^&#]*/, '').includes('p@ss'));
  if (Object.prototype.hasOwnProperty.call(PINNED, bare)) check(tag + ': pinned ' + bare + ' only on its own port ' + PINNED[bare], port === PINNED[bare], bare + ':' + port);
  return { proto, addr: bare, port, sec, type, name: decodeURIComponent(frag || '') };
}

function decodeSub(text) {
  const t = text.trim();
  if (/^(vless|trojan|vmess|ss|hy2|tuic):\/\//m.test(t)) return t;
  try { return Buffer.from(t, 'base64').toString('utf8'); } catch { return t; }
}

function auditLinesText(text, ctx) {
  const lines = decodeSub(text).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const links = lines.filter((l) => /^(vless|trojan):\/\//.test(l));
  check(ctx.label + ': sub not empty', links.length > 0 || ctx.allowEmpty, 'lines=' + lines.length);
  const other = lines.filter((l) => !/^(vless|trojan):\/\//.test(l));
  check(ctx.label + ': only vless/trojan lines', other.length === 0, other[0] && other[0].slice(0, 80));
  const parsed = links.map((l) => validateLink(l, ctx)).filter(Boolean);
  const names = parsed.map((x) => x.name);
  check(ctx.label + ': config names unique', new Set(names).size === names.length, names.filter((n, i) => names.indexOf(n) !== i).slice(0, 3).join(' | '));
  const exact = links.filter((l, i) => links.indexOf(l) !== i);
  check(ctx.label + ': no duplicate config', exact.length === 0, exact[0] && exact[0].slice(0, 100));
  return parsed;
}

function auditClash(text, ctx) {
  const tag = ctx.label + ' [clash]';
  check(tag + ': has proxies', /^proxies:/m.test(text));
  check(tag + ': no undefined/NaN/null values', !/: (undefined|NaN|null)\s*$/m.test(text) && !/undefined|NaN/.test(text), (text.match(/.*(undefined|NaN).*/) || [''])[0].slice(0, 120));
  const names = [...text.matchAll(/^\s*- \{?\s*name: ("[^"]*"|'[^']*'|[^,}\n]+)/gm)].map((m) => m[1].replace(/^["']|["']$/g, '').trim());
  check(tag + ': proxy names unique (mihomo refuses duplicates)', new Set(names).size === names.length, names.filter((n, i) => names.indexOf(n) !== i).slice(0, 2).join(' | '));
  const ports = [...text.matchAll(/\bport: ([^,\n}]+)/g)].map((m) => m[1].trim());
  check(tag + ': every port is a number', ports.length > 0 && ports.every((p) => /^\d+$/.test(p) && Number(p) <= 65535), ports.find((p) => !/^\d+$/.test(p)));
  const servers = [...text.matchAll(/\bserver: ([^,\n}]+)/g)].map((m) => m[1].trim().replace(/^["']|["']$/g, ''));
  check(tag + ': servers sane', servers.length > 0 && servers.every((sv) => sv && !/#|undefined/.test(sv)), servers.find((sv) => !sv || /#|undefined/.test(sv)));
  const groupRefs = [...text.matchAll(/^\s*proxies: \[([^\]]*)\]/gm)].flatMap((m) => m[1].split(',').map((x) => x.trim().replace(/^["']|["']$/g, '')).filter(Boolean));
  const groupNames = [...text.matchAll(/^\s*- \{?\s*name: ("[^"]*"|'[^']*'|[^,}\n]+),\s*type: (select|url-test|fallback|load-balance)/gm)].map((m) => m[1].replace(/^["']|["']$/g, '').trim());
  const known = new Set(names.concat(groupNames, ['DIRECT', 'REJECT', 'PASS', 'GLOBAL']));
  const missing = groupRefs.filter((r) => !known.has(r));
  check(tag + ': every group member exists', missing.length === 0, missing.slice(0, 3).join(' | '));
}

function auditSingbox(text, ctx) {
  const tag = ctx.label + ' [sing-box]';
  let j = null;
  try { j = JSON.parse(text); } catch (e) { check(tag + ': JSON parses', false, e.message); return; }
  const obs = j.outbounds || [];
  const tags = new Set(obs.map((o) => o.tag));
  check(tag + ': outbound tags unique', tags.size === obs.length);
  for (const o of obs) {
    if (o.type === 'vless' || o.type === 'trojan') {
      check(tag + ': server_port is a number', Number.isInteger(o.server_port) && o.server_port > 0 && o.server_port < 65536, o.tag + ' ' + o.server_port);
      check(tag + ': server set', typeof o.server === 'string' && o.server && !/[#\[\]]/.test(o.server), o.server);
      if (o.tls && o.tls.enabled) check(tag + ': tls server_name valid', HOSTNAME_RE.test(o.tls.server_name || ''), o.tag + ' ' + (o.tls.server_name));
      if (o.transport && o.transport.type === 'ws') check(tag + ': ws path', String(o.transport.path || '').startsWith('/'), o.transport.path);
      if (Object.prototype.hasOwnProperty.call(PINNED, o.server)) check(tag + ': pinned port kept', o.server_port === PINNED[o.server], o.server + ':' + o.server_port);
      check(tag + ': TLS on TLS ports / plain on plain ports', (o.tls && o.tls.enabled) ? !PLAIN.includes(o.server_port) : !TLS.includes(o.server_port), o.tag + ' ' + o.server_port);
    }
    if (Array.isArray(o.outbounds)) {
      const miss = o.outbounds.filter((t) => !tags.has(t));
      check(tag + ': group members exist', miss.length === 0, o.tag + ' → ' + miss.slice(0, 2).join(','));
      check(tag + ': group not empty', o.outbounds.length > 0, o.tag);
      if (o.default) check(tag + ': group default exists', o.outbounds.includes(o.default), o.tag + ' default ' + o.default);
    }
  }
  const rules = (j.route && j.route.rules) || [];
  for (const r of rules) check(tag + ": route rule is an object", r && typeof r === "object"); for (const r of rules.filter(Boolean)) if (r.outbound) check(tag + ': route outbound exists', tags.has(r.outbound), r.outbound);
  if (j.route && j.route.final) check(tag + ': route.final exists', tags.has(j.route.final), j.route.final);
  check(tag + ': no undefined/NaN', !/undefined|NaN/.test(text));
}

function auditXray(text, ctx) {
  const tag = ctx.label + ' [xray]';
  let j = null;
  const raw = /^\s*[\[{]/.test(text) ? text : Buffer.from(text.trim(), 'base64').toString('utf8'); // v2rayNG wants it base64
  try { j = JSON.parse(raw); } catch (e) { check(tag + ': JSON parses', false, e.message); return; }
  text = raw;
  const list = Array.isArray(j) ? j : [j];
  check(tag + ': at least one config', list.length > 0);
  check(tag + ': no undefined/NaN', !/undefined|NaN/.test(text));
  for (const c of list) {
    const obs = c.outbounds || [];
    const tags = new Set(obs.map((o) => o.tag).filter(Boolean));
    for (const o of obs) {
      const ss = o.settings || {};
      const servers = (ss.vnext || ss.servers || []);
      if (o.protocol === 'vless' || o.protocol === 'trojan') {
        check(tag + ': outbound has a server', servers.length > 0, o.tag);
        for (const sv of servers) {
          check(tag + ': port number', Number.isInteger(sv.port) && sv.port > 0 && sv.port < 65536, o.tag + ' ' + sv.port);
          check(tag + ': address sane', typeof sv.address === 'string' && sv.address && !/[#\[\]]/.test(sv.address), sv.address);
          if (Object.prototype.hasOwnProperty.call(PINNED, sv.address)) check(tag + ': pinned port kept', sv.port === PINNED[sv.address], sv.address + ':' + sv.port);
        }
        const st = o.streamSettings || {};
        if (st.security === 'tls') check(tag + ': tls serverName valid', HOSTNAME_RE.test((st.tlsSettings || {}).serverName || ''), o.tag);
        if (st.network === 'ws') check(tag + ': ws path', String((st.wsSettings || {}).path || '').startsWith('/'), o.tag);
        const dp = (st.sockopt || {}).dialerProxy;
        if (dp) check(tag + ': dialerProxy exists', tags.has(dp), dp);
      }
      if (o.proxySettings && o.proxySettings.tag) check(tag + ': proxySettings tag exists', tags.has(o.proxySettings.tag), o.proxySettings.tag);
    }
    const routing = c.routing || {};
    const bal = new Set((routing.balancers || []).map((b) => b.tag));
    for (const b of routing.balancers || []) {
      const sel = b.selector || [];
      check(tag + ': balancer selector matches outbounds', sel.every((pfx) => obs.some((o) => String(o.tag || '').startsWith(pfx))), b.tag + ' ' + sel.join(','));
      if (b.fallbackTag) check(tag + ': balancer fallback exists', tags.has(b.fallbackTag), b.fallbackTag);
    }
    for (const r of routing.rules || []) {
      if (r.outboundTag) check(tag + ': rule outboundTag exists', tags.has(r.outboundTag), r.outboundTag);
      if (r.balancerTag) check(tag + ': rule balancerTag exists', bal.has(r.balancerTag), r.balancerTag);
    }
    if (c.observatory) check(tag + ': observatory selector matches', (c.observatory.subjectSelector || []).every((pfx) => obs.some((o) => String(o.tag || '').startsWith(pfx))));
    if (c.burstObservatory) check(tag + ': burst observatory selector matches', (c.burstObservatory.subjectSelector || []).every((pfx) => obs.some((o) => String(o.tag || '').startsWith(pfx))));
  }
}

async function makePanel(platformEnv) {
  const ENV = Object.assign({ CAT_KV: new FakeKV(), UUID, TROJAN_PASS }, platformEnv || {});
  const call = async (p, { method = 'GET', headers = {}, body, host = 'audit.workers.dev' } = {}) => {
    const init = { method, headers: Object.assign({}, headers) };
    if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
    return worker.fetch(new Request('https://' + host + p, init), ENV, { waitUntil() {} });
  };
  const login = await call('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
  const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
  check('login admin/admin', login.status === 200, String(login.status));
  return { ENV, call, auth };
}

const SCENARIOS = [
  { name: 'defaults', settings: {} },
  {
    name: 'mixed own IPs + pinned + IPv6 + plain + ProxyIP + SOCKS',
    settings: {
      ips: ['104.16.1.1', '104.17.2.2:2053', '[2606:4700::7]:8443', '2606:4700::8', '188.114.98.0#DE', 'cdn.example-clean.com', '172.64.1.1:80'],
      tlsPorts: [443, 2053, 8443], plainPorts: [80, 8080], plainEnabled: true,
      proxyIps: PROXY_IPS.concat([SOCKS]),
    },
  },
  { name: 'ech + random-case SNI + fragment', settings: { ips: ['104.16.9.9'], ech: true, sniRandomCase: true, fragment: true, plainEnabled: false } },
  { name: 'trojan only, ipv6 only family', settings: { ips: ['2606:4700::9', '104.16.3.3'], protocols: { vless: false, trojan: true } } },
];
const QUERIES = ['', '?survive=1', '?proto=trojan', '?proto=vless', '?port=2053', '?port=80', '?addr=104.18.0.1:2083', '?sni=www.speedtest.net', '?ech=1', '?ech=0', '?fam=v6', '?fam=v4', '?rotate=fetch', '?limit=3', '?strict=1&country=DE', '?health=1', '?type=xhttp', '?nofm=1'];

const FW = ['mci-ech', 'mci-ipv6', 'mci-ipv6-ff', 'irancell-ff', 'irancell-ff-98'];
for (const platform of [{ label: 'cf', env: {} }, { label: 'vercel', env: { CAT_PLATFORM: 'vercel', DATA_PLANE_ENABLED: '1' } }]) {
  for (const sc of SCENARIOS) {
    const P = await makePanel(platform.env);
    const host = platform.label === 'vercel' ? 'audit-x.vercel.app' : 'audit.workers.dev';
    const put = await P.call('/api/settings', { method: 'PUT', headers: P.auth, body: sc.settings, host });
    check(platform.label + '/' + sc.name + ': settings saved', put.status === 200, String(put.status));
    const mk = await (await P.call('/api/users', { method: 'POST', headers: P.auth, host, body: { name: 'u1', fw: 'irancell-ff', ips: ['104.19.4.4:2096'], days: 30 } })).json();
    const uid = mk.user && mk.user.id;
    check(platform.label + '/' + sc.name + ': user created', !!uid);
    const fwq = FW.map((f) => '?fw=' + f);
    for (const q of QUERIES.concat(fwq)) {
      const label = platform.label + '/' + sc.name + ' ' + (q || '(plain)');
      const ctx = { label, allowEmpty: /proto=trojan|proto=vless|port=80|fam=v6|fam=v4|strict=1|type=xhttp/.test(q) || (sc.settings.protocols && /vless/.test(q)), allowAnyPort: false };
      for (const pre of ['/sub/' + UUID, '/u/' + uid]) {
        const r = await P.call(pre + q, { host });
        check(label + ' ' + pre.slice(0, 3) + ': HTTP 200', r.status === 200, String(r.status));
        if (r.status !== 200) continue;
        const body = await r.text();
        auditLinesText(body, Object.assign({}, ctx, { label: label + ' ' + pre.slice(0, 3) }));
        if (platform.label === 'cf' && sc.name.startsWith('mixed') && q === '' && pre.startsWith('/sub')) {
          const all = decodeSub(body);
          check('ProxyIP 103.237.102.191 keeps :11111', all.includes('proxyip%3D103.237.102.191%253A11111'));
          check('ProxyIP 184.75.221.82 keeps :3118', all.includes('proxyip%3D184.75.221.82%253A3118'));
          check('SOCKS5 relay present as an opaque token', /proxyip%3Drelay%253A[0-9a-f]+/.test(all) && !all.includes('5.6.7.8'));
          check('pinned 104.17.2.2:2053 present', all.includes('@104.17.2.2:2053?'));
          check('pinned 172.64.1.1:80 present as plain', /@172\.64\.1\.1:80\?[^#]*security=none/.test(all));
          check('pinned IPv6 [2606:4700::7]:8443 present', all.includes('@[2606:4700::7]:8443?'));
          check('ip#CC tag went to the name, not the address', all.includes('@188.114.98.0:') && !all.includes('188.114.98.0#'));
          check('no TLS link on a plain port anywhere', !/:(80|8080|8880|2052|2082|2086|2095)\?[^#]*security=tls/.test(all));
        }
      }
      if (['', '?survive=1', '?fw=mci-ipv6-ff', '?proto=trojan', '?port=2053'].includes(q)) {
        for (const [fmt, fn] of [['clash', auditClash], ['singbox', auditSingbox], ['xray', auditXray]]) {
          if (fmt !== 'xray' && q.startsWith('?fw=')) {
            // firewall presets are Xray-only by design: Clash / sing-box answer 400 with an explanation
            check(label + ' /' + fmt + ': fw preset refused with 400', (await P.call('/' + fmt + '/' + UUID + q, { host })).status === 400);
            continue;
          }
          const r1 = await P.call('/' + fmt + '/' + UUID + q, { host });
          check(label + ' /' + fmt + ': HTTP 200', r1.status === 200, String(r1.status));
          if (r1.status === 200) fn(await r1.text(), ctx);
          const r2 = await P.call('/u/' + uid + '/' + fmt + q, { host });
          if (fmt !== 'xray' || r2.status === 200) {
            check(label + ' /u/' + fmt + ': HTTP 200', r2.status === 200, String(r2.status));
            if (r2.status === 200) fn(await r2.text(), Object.assign({}, ctx, { label: label + ' (user)' }));
          }
        }
        const r64 = await P.call('/u/' + uid + '/64' + q, { host });
        check(label + ' /u/64: HTTP 200', r64.status === 200, String(r64.status));
        if (r64.status === 200) auditLinesText(await r64.text(), Object.assign({}, ctx, { label: label + ' /u/64' }));
      }
    }
  }
}

// ---- the app's push paths (scanner → POST /api/ips, ProxyIP page → PUT /api/settings) ----
{
  const P = await makePanel({});
  const lines = ['1.2.3.4:2053#DE', '[2606:4700::1]:8443#NL'];
  const r = await (await P.call('/api/ips', { method: 'POST', headers: P.auth, body: { ips: lines, source: 'scanner', pingMs: { [lines[0]]: 120, [lines[1]]: 90 }, countries: { [lines[0]]: 'DE' }, neighborIps: [lines[1]] } })).json();
  check('app push: IPs stored with exactly their ports', JSON.stringify((r.ips || []).slice(0, 2)) === JSON.stringify(['1.2.3.4:2053', '[2606:4700::1]:8443']), JSON.stringify(r.ips));
  const st = (await (await P.call('/api/settings', { headers: P.auth })).json()).settings;
  const so = (k) => st.ipSources[k] || st.ipSources[k + '#DE'] || st.ipSources[k + '#NL'];
  check('app push: scanner pings reachable from the stored address (the page and health order strip «#CC»)', so('1.2.3.4:2053') && so('1.2.3.4:2053').ms === 120 && so('[2606:4700::1]:8443').src === 'neighbor', JSON.stringify(st.ipSources));
  check('app push: countries keyed by the stored address', st.ipCountries['1.2.3.4:2053'] === 'DE' && st.ipCountries['[2606:4700::1]:8443'] === 'NL');
  const px = (await (await P.call('/api/settings', { method: 'PUT', headers: P.auth, body: { proxyIps: PROXY_IPS.concat([SOCKS, '5.5.5.5']), proxyCountries: { [PROXY_IPS[0]]: 'SG' } } })).json()).settings;
  check('ProxyIP page: both ProxyIPs kept with their exact ports, SOCKS kept, port-less entry not guessed', JSON.stringify(px.proxyIps) === JSON.stringify(PROXY_IPS.concat([SOCKS])), JSON.stringify(px.proxyIps));
  await P.call('/api/settings', { method: 'PUT', headers: P.auth, body: { sni: 'www.speedtest.net', sniFront: true, sniRotate: false, extraSnis: [] } });
  const subTxt = await (await P.call('/sub/' + UUID)).text();
  const snis = [...subTxt.matchAll(/[?&]sni=([^&#]+)/g)].map((m) => m[1]);
  check('app «make main SNI»: every TLS config carries exactly the verified SNI', snis.length > 0 && snis.every((x) => x === 'www.speedtest.net'), [...new Set(snis)].join(','));
  check('settings page port buttons get numbers (no duplicate «443» buttons)', st.tlsPorts.every((p) => typeof p === 'number') && st.plainPorts.every((p) => typeof p === 'number'), JSON.stringify(st.tlsPorts));
}

console.log(passes + ' checks passed, ' + failures + ' failed');
if (failures) process.exit(1);
console.log('sub-audit: all checks passed');
