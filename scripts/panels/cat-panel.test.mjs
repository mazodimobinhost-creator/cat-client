/**
 * Cat Panel worker test harness (runs on Node 18+, no dependencies).
 * Usage: node scripts/panels/cat-panel.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

// --- syntax check ---
const src = readFileSync(workerPath, 'utf8');
new Function('return 0'); // warm
const { execFileSync } = await import('node:child_process');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

const mod = await import(workerPath);
const worker = mod.default;
const T = mod._testing;

let failures = 0;
function check(name, cond, extra) {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); }
}

const HOST = 'catpanel-demo.workers.dev';
// v5: subscriptions need the UUID in the path (BPB-style) and the panel is
// locked by default. Tests that exercise the content use OPEN_* to keep the
// short URLs; dedicated checks below cover the locked behaviour.
const OPEN = { OPEN_SUB: 'true', OPEN_PANEL: 'true' };
function req(url, { headers = {}, env = {}, method = 'GET', raw = false } = {}) {
  const r = new Request('https://' + HOST + url, { headers, method });
  return worker.fetch(r, raw ? env : Object.assign({}, OPEN, env));
}
const b64dec = (t) => decodeURIComponent(escape(atob(t.trim())));
async function subText(url, opts) {
  const res = await req(url, opts);
  const text = await res.text();
  const decoded = text.includes('://') ? text : b64dec(text);
  return { res, body: decoded };
}

// 1. /sub basic
{
  const { res, body } = await subText('/sub');
  check('/sub returns 200', res.status === 200);
  check('/sub has vless link', body.includes('vless://'));
  check('/sub has trojan link', body.includes('trojan://'));
  check('/sub has warp link', body.includes('warp://'));
  check('/sub has sni param', body.includes('sni=catpanel-demo.workers.dev'));
  check('/sub has host param', body.includes('host=catpanel-demo.workers.dev'));
  check('/sub subscription-userinfo header', (res.headers.get('subscription-userinfo') || '').includes('total='));
  const m = body.match(/vless:\/\/([0-9a-f-]{36})@/);
  check('/sub contains stable UUID', !!m);
  const { body: body2 } = await subText('/sub');
  check('UUID stable across requests', body2.includes('vless://' + m[1] + '@'));
  const locked = await req('/sub', { raw: true });
  check('/sub without uuid is refused when not OPEN_SUB', locked.status === 401);
  const withUuid = await subText('/sub/' + m[1], { raw: true });
  check('/sub/<uuid> works without OPEN_SUB', withUuid.res.status === 200 && withUuid.body.includes('vless://' + m[1] + '@'));
  const wrong = await req('/sub/00000000-0000-4000-8000-000000000000', { raw: true });
  check('/sub/<wrong uuid> is 404', wrong.status === 404);
  const rawTxt = await req('/sub/' + m[1] + '/raw', { raw: true });
  check('/sub/<uuid>/raw is plain text', (await rawTxt.text()).startsWith('vless://'));
  const clash = await req('/sub/' + m[1] + '/clash', { raw: true });
  check('/sub/<uuid>/clash yields yaml', (await clash.text()).includes('proxies:'));
}

// 2. explicit UUID env
{
  const { body } = await subText('/sub', { env: { UUID: '11111111-2222-4333-8444-555555555555' } });
  check('explicit UUID env respected', body.includes('vless://11111111-2222-4333-8444-555555555555@'));
}

// 3. clean IPs
{
  const { body } = await subText('/sub', { env: { CF_IPS: '104.16.1.1, 172.64.148.100, [2606:4700:4700::1111]' } });
  check('clean-IP vless variant (v4)', body.includes('@104.16.1.1:443'));
  check('clean-IP trojan variant', body.includes('@104.16.1.1:443') && /trojan:\/\/[0-9a-f-]+@104\.16\.1\.1:443/.test(body));
  check('clean-IP v6 bracketed', body.includes('@[2606:4700:4700::1111]:443'));
  check('clean-IP variants keep sni', (body.match(/sni=catpanel-demo\.workers\.dev/g) || []).length >= 6);
}

// 4. custom SNI
{
  const { body } = await subText('/sub', { env: { SNI: 'my.sni.example' } });
  check('custom SNI in links', body.includes('sni=my.sni.example'));
  check('host param still worker host', body.includes('host=catpanel-demo.workers.dev'));
}

// 5. SNI whitelist gate
{
  const env = { SNI_LIST: 'my.sni.example,alt.example' };
  const bad = await req('/sub', { headers: { 'X-Forwarded-Sni': 'evil.example' }, env });
  check('unknown SNI rejected (403)', bad.status === 403);
  const ok1 = await req('/sub', { headers: { 'X-Forwarded-Sni': HOST }, env });
  check('host SNI accepted', ok1.status === 200);
  const ok2 = await req('/sub', { headers: { 'X-Forwarded-Sni': 'My.SNI.example' }, env });
  check('whitelisted SNI accepted (case-insensitive)', ok2.status === 200);
  const ok3 = await req('/sub', { env });
  check('direct-by-hostname (no SNI header) accepted', ok3.status === 200);
}

// 6. /clash
{
  const res = await req('/clash', { env: { CF_IPS: '104.16.1.1' } });
  const body = await res.text();
  check('/clash 200 + yaml', res.status === 200 && body.includes('proxies:'));
  check('/clash vless proxy', body.includes('type: vless'));
  check('/clash trojan proxy', body.includes('type: trojan'));
  check('/clash servername (SNI)', body.includes('servername:'));
  check('/clash iran direct rule', body.includes('GEOSITE,iran,direct'));
  check('/clash includes clean-IP proxy', body.includes('server: 104.16.1.1'));
}

// 7. /health
{
  const res = await req('/health');
  const j = JSON.parse(await res.text());
  check('/health ok', j.ok === true && j.panel === 'cat-panel');
}

// 8. panel html + password
{
  const res = await req('/');
  const body = await res.text();
  check('panel html 200', res.status === 200);
  check('panel html has sub link', body.includes('https://' + HOST + '/sub'));
  check('panel html is v3 shell', body.includes('Cat Panel') && body.includes('catpanel.tab') && body.includes('CAT_STATE'));
  check('panel html has clean-IP scanner tab', body.includes('data-tab-panel="scanner"') && body.includes('scanStart'));
  check('panel html has DoH tab', body.includes('/dns-query') && body.includes('data-tab-panel="dns"'));
  check('panel html has deep link', body.includes('catclient://add-sub?url='));
  check('panel html is bilingual', body.includes('خانه') && body.includes('Home'));
  const locked = await req('/', { env: { PANEL_PASSWORD: 'secret123' } });
  check('panel locked without password', locked.status === 200 && (await locked.text()).includes('name="p"'));
  const unlocked = await req('/?p=secret123', { env: { PANEL_PASSWORD: 'secret123' } });
  check('panel unlocks with password', (await unlocked.text()).includes('Cat Panel'));
}

// 9. parseVless
{
  const enc = new TextEncoder();
  const domain = 'example.com';
  const db = Array.from(enc.encode(domain));
  const vless = [0, 1, 1, db.length, ...db, 0x01, 0xBB,
    ...enc.encode('GET / HTTP/1.1\r\nHost: example.com\r\n\r\n')];
  const p = T.parseVless(new Uint8Array(vless));
  check('parseVless domain host', p && p.host === 'example.com');
  check('parseVless port', p && p.port === 443);
  check('parseVless rest payload', p && p.rest.length === 'GET / HTTP/1.1\r\nHost: example.com\r\n\r\n'.length);

  const v4 = [0, 1, 0, 1, 2, 3, 4, 0x1f, 0x40, ...enc.encode('GET /a HTTP/1.1\r\n\r\n')];
  const p4 = T.parseVless(new Uint8Array(v4));
  check('parseVless ipv4 host', p4 && p4.host === '1.2.3.4' && p4.port === 8000);

  const v6 = [0, 1, 2, ...Array(15).fill(0), 1, 0x01, 0xbb, ...enc.encode('GET / HTTP/1.1\r\n\r\n')];
  const p6 = T.parseVless(new Uint8Array(v6));
  check('parseVless ipv6 host', p6 && p6.host === '[0:0:0:0:0:0:0:1]' && p6.port === 443);

  check('parseVless rejects short', T.parseVless(new Uint8Array([0, 1])) === null);
}

// 10. parseHttpRequest
{
  const p = T.parseHttpRequest(new TextEncoder().encode('POST /x?q=1 HTTP/1.1\r\nHost: a\r\nContent-Type: t\r\n\r\nBODY'));
  check('parseHttpRequest method/target', p.method === 'POST' && p.target === '/x?q=1');
  check('parseHttpRequest body', p.body === 'BODY');
  check('parseHttpRequest headers', p.headers.length === 2);
}

// 11. httpForward end-to-end with stubbed fetch
{
  const fakeWs = { sent: [], closed: null, send(d) { this.sent.push(d); }, close(c) { this.closed = c; } };
  const origFetch = globalThis.fetch;

  globalThis.fetch = async (url, opts) => {
    if (String(url).includes('boom.example')) throw new Error('dns fail');
    return new Response('hello cat', { status: 200, headers: { 'content-type': 'text/plain', 'x-keep': 'yes' } });
  };
  await T.httpForward(fakeWs, {
    host: 'example.com', port: 443,
    rest: new TextEncoder().encode('GET / HTTP/1.1\r\nHost: example.com\r\n\r\n'),
  });
  const head = new TextDecoder().decode(fakeWs.sent[0]);
  check('httpForward 200 head', head.startsWith('HTTP/1.1 200 OK'));
  check('httpForward echoes content-type', head.toLowerCase().includes('content-type: text/plain'));
  check('httpForward connection close', head.toLowerCase().includes('connection: close'));
  check('httpForward body streamed', fakeWs.sent.some((d) => new TextDecoder().decode(d).includes('hello cat')));
  check('httpForward closes ws', fakeWs.closed === 1000);

  const ws2 = { sent: [], closed: null, send(d) { this.sent.push(d); }, close(c) { this.closed = c; } };
  await T.httpForward(ws2, {
    host: 'boom.example', port: 443,
    rest: new TextEncoder().encode('GET / HTTP/1.1\r\nHost: boom.example\r\n\r\n'),
  });
  check('httpForward 502 on fetch failure', new TextDecoder().decode(ws2.sent[0]).includes('502'));

  const ws3 = { sent: [], closed: null, send(d) { this.sent.push(d); }, close(c) { this.closed = c; } };
  await T.httpForward(ws3, {
    host: 'example.com', port: 443,
    rest: new TextEncoder().encode('CONNECT example.com:443 HTTP/1.1\r\n\r\n'),
  });
  check('httpForward 405 on CONNECT', new TextDecoder().decode(ws3.sent[0]).includes('405'));

  globalThis.fetch = origFetch;
}

// 12. sub64
{
  const a = await subText('/sub');
  const b = await req('/sub64');
  const dec = b64dec(await b.text());
  check('/sub64 decodes to /sub', dec === a.body);
  const q = await subText('/sub?ips=1.2.3.4,5.6.7.8&ports=443,2053,80&proto=vless&sni=cdn.example.com');
  check('query addresses replace defaults', q.body.includes('@1.2.3.4:443') && q.body.includes('@5.6.7.8:2053'));
  check('query ports add plain-http variants', q.body.includes('@1.2.3.4:80?encryption=none&security=none'));
  check('query proto filter drops trojan', !q.body.includes('trojan://'));
  check('query sni applies to tls links', q.body.includes('sni=cdn.example.com'));
  check('host header stays the worker host', q.body.includes('host=' + HOST));
}

// 13. OPTIONS CORS
{
  const res = await new Promise((resolve) => {
    const r = new Request('https://' + HOST + '/sub', { method: 'OPTIONS' });
    worker.fetch(r, {}).then(resolve);
  });
  check('OPTIONS 204 + CORS', res.status === 204 && res.headers.get('access-control-allow-origin') === '*');
}

// 14. new subscription formats
{
  const singbox = JSON.parse(await (await req('/singbox')).text());
  check('/singbox is valid JSON with outbounds', Array.isArray(singbox.outbounds) && singbox.outbounds.some((o) => o.type === 'vless'));
  check('/singbox has trojan outbound', singbox.outbounds.some((o) => o.type === 'trojan'));
  check('/singbox points DoH at the worker', JSON.stringify(singbox.dns).includes('/dns-query'));
  const all = JSON.parse(await (await req('/all')).text());
  check('/all lists links', Array.isArray(all.links) && all.links.length >= 3);
  check('/all exposes sni whitelist', Array.isArray(all.sniWhitelist) && all.sniWhitelist.includes(HOST));
  const yaml = await (await req('/clash')).text();
  check('/clash contains DoH nameserver', yaml.includes('https://' + HOST + '/dns-query'));
}

// 15. QR encoder fixtures (verified against the reference implementation)
{
  const fnv = (s) => {
    let h = 0x811c9dc5;
    for (const ch of s) { h ^= ch.charCodeAt(0); h = (h * 0x01000193) >>> 0; }
    return h.toString(16).padStart(8, '0');
  };
  const fixtures = [
    ['HELLO', 'M', 21, 4, '493ae778'],
    ['https://catpanel-demo.workers.dev/sub', 'M', 29, 5, '9621b278'],
    ['https://catpanel-demo.workers.dev/sub', 'L', 29, 7, '722d5814'],
    ['سلام دنیا', 'M', 25, 3, 'eb63eae0'],
    ['x'.repeat(300), 'Q', 81, 0, '4bd6ed8c'],
  ];
  let ok = true;
  for (const [text, ecl, size, mask, hash] of fixtures) {
    const qr = T.qrEncode(text, ecl);
    let bits = '';
    for (const row of qr.modules) for (const cell of row) bits += cell ? '1' : '0';
    if (qr.size !== size || qr.mask !== mask || fnv(bits) !== hash) {
      ok = false;
      console.error('  fixture mismatch:', JSON.stringify(text.slice(0, 20)), ecl, qr.size, qr.mask, fnv(bits));
    }
  }
  check('QR fixtures match reference matrices', ok);
  const svg = T.qrSvg('https://catpanel-demo.workers.dev/sub');
  check('qrSvg returns an svg path', svg.startsWith('<svg') && svg.includes('<path d="M') && svg.includes('</svg>'));
  const res = await req('/qr.svg?d=hello&size=6');
  const svgBody = await res.text();
  check('/qr.svg serves svg', res.status === 200 && (res.headers.get('content-type') || '').includes('image/svg+xml') && svgBody.includes('<svg'));
  const bad = await req('/qr.svg');
  check('/qr.svg needs payload', bad.status === 400);
}

// 16. scanner + dns api
{
  const res = await req('/api/scan-targets.json', { env: { CF_IPS: '104.16.6.62' } });
  const j = JSON.parse(await res.text());
  check('scan targets include CF_IPS first', j.targets[0] === '104.16.6.62');
  check('scan targets are IPv4', j.targets.every((ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip)));
  check('scan targets size is sane', j.targets.length >= 20 && j.targets.length <= 200);
  check('scan sni defaults to host', j.sni === HOST);
  const opts = await req('/api/ping');
  check('/api/ping rejects non-IP', opts.status === 400);
  const custom = T.sampleSubnet('104.16.0.0/13', 4);
  check('sampleSubnet spreads addresses', custom.length === 4 && custom[0] !== custom[3]);
  check('isIpLiteral accepts v4/v6', T.isIpLiteral('1.1.1.1') && T.isIpLiteral('2606:4700:4700::1111') && !T.isIpLiteral('example.com'));
}

// 17. DoH resolver passthrough
{
  const origFetch = globalThis.fetch;
  let seen = null;
  globalThis.fetch = async (url, init) => {
    seen = { url: String(url), init };
    return new Response('dns-bytes', { status: 200, headers: { 'content-type': 'application/dns-message' } });
  };
  const res = await req('/dns-query?dns=AAAA', { env: { DNS_UPSTREAM: 'https://dns.google/dns-query' } });
  const text = await res.text();
  check('/dns-query forwards to upstream', seen && seen.url.startsWith('https://dns.google/dns-query?dns=AAAA'));
  check('/dns-query returns dns-message type', (res.headers.get('content-type') || '').includes('application/dns-message') && text === 'dns-bytes');
  globalThis.fetch = origFetch;
}

// 18. panel api json
{
  const jLocked = await req('/api/config.json', { raw: true, env: { CF_IPS: '1.2.3.4', PANEL_PASSWORD: 'x' } });
  check('/api/config.json needs auth when locked', jLocked.status === 401);
  const cookieX = 'catpanel_auth=' + (await T.sha256Hex('x'));
  const j = JSON.parse(await (await req('/api/config.json', { headers: { cookie: cookieX }, env: { CF_IPS: '1.2.3.4', PANEL_PASSWORD: 'x' } })).text());
  check('/api/config.json is v5', j.version === '5.0.0', j.version);
  check('/api/config.json sub url carries uuid', j.subUrl === 'https://' + HOST + '/sub/' + j.uuid);
  check('/api/config.json exposes config options', j.configOptions && Array.isArray(j.configOptions.ports) && j.configOptions.ports[0] === 443);
  check('/api/config.json exposes doh url', j.dohUrl === 'https://' + HOST + '/dns-query');
  check('/api/config.json flags locked panel', j.panelLocked === true);
  check('/api/config.json embeds scan targets', Array.isArray(j.scanTargets) && j.scanTargets.length > 10);
  const health = JSON.parse(await (await req('/health')).text());
  check('/health reports doh + scanner', health.doh === 'https://' + HOST + '/dns-query' && health.scanTargets > 10);
}

// 18b. panel lock — UUID is the password until one is configured
{
  const lockedPage = await (await req('/', { raw: true })).text();
  check('panel locked by default shows login', lockedPage.includes('id="loginForm"') && !lockedPage.includes('nav class="tabs"'));
  const openPage = await (await req('/', { raw: true, env: { OPEN_PANEL: 'true' } })).text();
  check('OPEN_PANEL=true opens the panel', openPage.includes('nav class="tabs"') || openPage.includes('class="tabs"'));
  const uuidHere = (await (await req('/api/config.json')).json()).uuid;
  const viaUuid = await req('/?p=' + uuidHere, { raw: true });
  check('uuid unlocks the panel and sets the cookie', viaUuid.status === 200 && (viaUuid.headers.get('set-cookie') || '').includes('catpanel_auth='));
  const cookie = (viaUuid.headers.get('set-cookie') || '').split(';')[0];
  const withCookie = await (await req('/', { raw: true, headers: { cookie } })).text();
  check('cookie session keeps the panel open', withCookie.includes('data-tab-panel="home"'));
  const badLogin = await req('/api/login', { raw: true, method: 'POST' });
  check('wrong password → 401', badLogin.status === 401);
}

// 19. v3.1 panel surface: themes, custom DoH/DoT, single-config builder
{
  const body = await (await req('/')).text();
  check('panel has theme picker', body.includes('themeMenu') && body.includes('data-theme-pick="orchid"') && body.includes('data-theme-pick="mono"'));
  check('panel exposes 5 themes', (body.match(/data-theme-pick=/g) || []).length === 5);
  check('panel has custom DoH + DoT fields', body.includes('id="dohCustom"') && body.includes('id="dotCustom"'));
  check('panel lists DoT presets', body.includes('one.one.one.one') && body.includes('dns.adguard-dns.com'));
  check('panel has single-config builder', body.includes('id="singleBuild"') && body.includes('id="singleAddr"'));
  check('single builder ships deep links', body.includes('catclient://scan?sni=') && body.includes('catclient://add-sub?url='));
  const state = JSON.parse(await (await req('/api/config.json')).text());
  check('config json exposes dot presets', Array.isArray(state.dotPresets) && state.dotPresets.some((p) => p.host === 'dns.google'));
}

// 20. resolver helpers
{
  check('safeUpstreamOverride accepts https', T.safeUpstreamOverride('https://dns.google/dns-query') === 'https://dns.google/dns-query');
  check('safeUpstreamOverride rejects http', T.safeUpstreamOverride('http://dns.google/dns-query') === null);
  check('safeUpstreamOverride rejects bare IPs', T.safeUpstreamOverride('https://1.1.1.1/dns-query') === null);
  check('safeUpstreamOverride rejects junk', T.safeUpstreamOverride('not a url') === null);

  const origFetch = globalThis.fetch;
  let seen = null;
  globalThis.fetch = async (url) => {
    seen = String(url);
    return new Response(JSON.stringify({ Answer: [{ name: 'dns.google', type: 1, data: '8.8.8.8' }] }), {
      status: 200,
      headers: { 'content-type': 'application/dns-json' },
    });
  };
  const resolved = await T.resolveHost('dns.google', {});
  check('resolveHost returns answers', resolved.ok === true && resolved.answers[0] === '8.8.8.8', JSON.stringify(resolved));
  check('resolveHost queries the upstream', seen.includes('name=dns.google'));
  const bad = await T.resolveHost('not a host!', {});
  check('resolveHost rejects invalid names', bad.ok === false);

  const override = await req('/dns-query?dns=AAAA&u=' + encodeURIComponent('https://dns.quad9.net/dns-query'));
  check('/dns-query?u= override works', override.status === 200 && seen.includes('dns.quad9.net'));
  const denied = await req('/dns-query?dns=AAAA&u=http%3A%2F%2Fevil.example%2Fdns-query');
  check('/dns-query ignores non-https override', denied.status === 200 && seen.includes('178.22.122.100'));
  const resolveRoute = JSON.parse(await (await req('/api/resolve?host=dns.google')).text());
  check('/api/resolve route works', resolveRoute.ok === true && resolveRoute.answers.length === 1);
  globalThis.fetch = origFetch;
}

// 21. scan progress helpers in the panel client
{
  const body = await (await req('/')).text();
  check('scanner reports live percentage', body.includes('(pct+"%)"') || body.includes('pct+"%"'));
  check('scanner status mentions best ping', body.includes('best: '));
}


// 22. users / settings / backup / scan API + tunnel parsers
{
  const encoder = new TextEncoder();
  const uuid = '11111111-2222-3333-4444-555555555555';
  const uuidBytes = uuid.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16));
  const domain = Array.from(encoder.encode('example.com'));
  // Real Xray wire format: atyp 1 = IPv4, 2 = domain, 3 = IPv6 (NOT the SOCKS numbering).
  const frame = new Uint8Array([0, ...uuidBytes, 0, 1, 0x01, 0xBB, 2, domain.length, ...domain, 0x47, 0x45, 0x54]);
  const parsed = T.parseVlessHeader(frame);
  check('parseVlessHeader uuid', parsed && parsed.uuid === uuid, parsed && parsed.uuid);
  check('parseVlessHeader command/port', parsed && parsed.command === 1 && parsed.port === 443);
  check('parseVlessHeader host', parsed && parsed.host === 'example.com', parsed && parsed.host);
  check('parseVlessHeader payload survives', parsed && parsed.rest.length === 3 && parsed.rest[0] === 0x47);
  const v4frame = new Uint8Array([0, ...uuidBytes, 0, 1, 0x00, 0x50, 1, 1, 1, 1, 1]);
  const v4 = T.parseVlessHeader(v4frame);
  check('parseVlessHeader ipv4', v4 && v4.host === '1.1.1.1' && v4.port === 80, v4 && v4.host);
  const v6frame = new Uint8Array([0, ...uuidBytes, 0, 1, 0x01, 0xBB, 3, ...new Array(15).fill(0), 1]);
  const v6 = T.parseVlessHeader(v6frame);
  check('parseVlessHeader ipv6', v6 && v6.host === '0:0:0:0:0:0:0:1', v6 && v6.host);
  const udpDns = new Uint8Array([0, ...uuidBytes, 0, 2, 0x00, 0x35, 1, 8, 8, 8, 8]);
  const dns = T.parseVlessHeader(udpDns);
  check('parseVlessHeader udp dns command', dns && dns.command === 2 && dns.port === 53 && dns.host === '8.8.8.8');
  const withAddons = new Uint8Array([0, ...uuidBytes, 2, 0xAA, 0xBB, 1, 0x01, 0xBB, 2, domain.length, ...domain]);
  const addons = T.parseVlessHeader(withAddons);
  check('parseVlessHeader skips addons', addons && addons.command === 1 && addons.host === 'example.com' && addons.port === 443);
  check('parseVlessHeader rejects junk', T.parseVlessHeader(new Uint8Array([1, 2, 3])) === null);
  check('parseVlessHeader rejects bad atyp', T.parseVlessHeader(new Uint8Array([0, ...uuidBytes, 0, 1, 0x01, 0xBB, 9, 1, 2, 3, 4])) === null);
  // early data (Xray ?ed=2048 puts the first frame in Sec-WebSocket-Protocol as base64url)
  const early = T.decodeEarlyData(Buffer.from(frame).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''));
  check('decodeEarlyData round-trips base64url', early && early.byteLength === frame.byteLength && early[17] === 0);
  check('decodeEarlyData empty → null', T.decodeEarlyData('') === null);
  check('splitHostPort host:port', JSON.stringify(T.splitHostPort('1.2.3.4:8443', 443)) === '{"hostname":"1.2.3.4","port":8443}');
  check('splitHostPort bare host', JSON.stringify(T.splitHostPort('bpb.yousef.isegaro.com', 443)) === '{"hostname":"bpb.yousef.isegaro.com","port":443}');
  check('splitHostPort [v6]:port', T.splitHostPort('[2606:4700::1]:2053', 443).port === 2053);
  check('proxyIpList falls back to defaults', T.proxyIpList({}, T.DEFAULT_SETTINGS).length === T.DEFAULT_PROXY_IPS.length);
  check('proxyIpList honours env', T.proxyIpList({ PROXYIP: '9.9.9.9' }, T.DEFAULT_SETTINGS)[0] === '9.9.9.9');
  check('proxyIpList prefers settings', T.proxyIpList({ PROXYIP: '9.9.9.9' }, { tunnel: { proxyIps: ['8.8.8.8'] } })[0] === '8.8.8.8');

  const trojanFrame = new Uint8Array([0x01, 0x03, 12, ...Array.from(encoder.encode('hysteria.com')), 0x01, 0xBB, 13, 10, 65]);
  const trojan = T.parseTrojanRequest(trojanFrame);
  check('parseTrojanRequest host/port', trojan && trojan.host === 'hysteria.com' && trojan.port === 443);
  check('parseTrojanRequest payload', trojan && trojan.payload.length === 1 && trojan.payload[0] === 65);
  const password = await T.trojanHash('secret-pass');
  check('trojanHash is sha224 hex', password.length === 56 && /^[0-9a-f]+$/.test(password));
  const hex = encoder.encode(password);
  const trojanPw = T.trojanPassword(new Uint8Array([...hex, 13, 10, 1, 1, 1, 0, 0, 53]));
  check('trojanPassword strips hash + CRLF', trojanPw && trojanPw.password === password);

  check('isCloudflareIp detects CF edge', T.isCloudflareIp('104.16.1.1') === true && T.isCloudflareIp('8.8.8.8') === false);

  const settings = await T.readSettings({});
  check('settings defaults exist', settings.dns && settings.tunnel && settings.scan);
  check('no KV binding is reported', T.kvBinding({}) === null && T.kvBinding({ CAT_KV: {} }) !== null);

  const user = T.normalizeUser({ name: 'u1', quotaGb: 1, usedBytes: 5 });
  check('user quota math', T.userTrafficLeft(user) === 1024 * 1024 * 1024 - 5);
  check('unlimited quota stays infinite', T.userTrafficLeft(T.normalizeUser({ quotaGb: 0 })) === Infinity);
  check('expired detection', T.userReasonBlocked(T.normalizeUser({ expireAt: Date.now() - 1000 })) === 'expired');
  check('disabled detection', T.userReasonBlocked(T.normalizeUser({ enabled: false })) === 'disabled');
  check('healthy user passes', T.userReasonBlocked(T.normalizeUser({ quotaGb: 5 })) === null);

  const usersRoute = await req('/api/users');
  check('users API refuses without KV', usersRoute.status === 409);
  const version = JSON.parse(await (await req('/api/version')).text());
  check('/api/version lists features', version.ok === true && version.features.includes('users'));
  const irIps = JSON.parse(await (await req('/api/ir-ips')).text());
  check('/api/ir-ips ships an Iran library', irIps.count > 20 && irIps.ips.includes('104.16.0.1'));
  const scan = JSON.parse(await (await req('/api/scan?ips=1.1.1.1,8.8.8.8')).text());
  check('/api/scan requires a valid list', scan.ok === false || Array.isArray(scan.results));
  const settingsRoute = await req('/api/settings');
  check('/api/settings returns defaults', settingsRoute.status === 200);
  check('Iranian resolvers are presets', JSON.parse(await (await req('/api/config.json')).text()).dnsPresets.some((p) => p.id === 'shecan'));
}

// 23. tunnel relay picks the proxy-IP websocket when TCP sockets are absent
{
  const encoder = new TextEncoder();
  const uuid = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const uuidBytes = uuid.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16));
  const frame = new Uint8Array([0, ...uuidBytes, 0, 1, 0x01, 0xBB, 1, 1, 2, 3, 4]);
  const sent = [];
  let relayed = null;
  const fakeClient = {
    readyState: 1,
    listeners: {},
    send(chunk) { sent.push(chunk); },
    close() {},
    addEventListener(name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn); },
  };
  const remoteSent = [];
  const fakeRemote = {
    accept() {},
    send(chunk) { remoteSent.push(chunk); },
    close() {},
    addEventListener(name, fn) { (this['on' + name] = fn); },
  };
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    relayed = String(url);
    return { webSocket: fakeRemote };
  };
  const ok = await T.relayTcp(fakeClient, {
    firstPayload: new Uint8Array([1, 2, 3]),
    headerBytes: frame,
    target: { host: 'example.org', port: 8443 },
    proxyIps: ['proxy.example.net'],
    preferConnect: false,
    path: '/tunnel',
  });
  check('relayTcp uses the proxy websocket', ok === true && relayed === 'https://proxy.example.net/tunnel');
  check('relayTcp replays the protocol header', remoteSent.length === 1 && remoteSent[0].byteLength === frame.byteLength);
  (fakeClient.listeners.message || []).forEach((fn) => fn({ data: new Uint8Array([9, 9]).buffer }));
  check('relayTcp pipes client frames upstream', remoteSent.length === 2);
  globalThis.fetch = origFetch;
}

console.log(failures === 0 ? '\nALL TESTS PASSED' : '\n' + failures + ' TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
