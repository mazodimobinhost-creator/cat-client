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

// --- duplicate-key guard (wrangler "duplicate-object-key" warnings) ---
// The one-click Deploy-to-Cloudflare button runs `wrangler deploy` against this
// file; duplicate keys in an object literal make the deploy console warn (and
// silently shadow the earlier value). Keep the bundle warning-free.
{
  const testingBlock = src.match(/export const _testing = \{([\s\S]*?)\n\};/);
  check('_testing export block exists', !!testingBlock);
  if (testingBlock) {
    const keys = [...testingBlock[1].matchAll(/(?:^|\n)\s{2}([A-Za-z_$][\w$]*),\s*(?=\n)/g)].map((k) => k[1]);
    const uniq = new Set();
    const dups = keys.filter((k) => (uniq.has(k) ? true : (uniq.add(k), false)));
    check('_testing export keys are unique (' + keys.length + ' keys)', dups.length === 0, 'duplicates: ' + dups.join(', '));
  }
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
  check('/sub omits warp by default (v2rayNG/v2box reject unknown schemes)', !body.includes('warp://'));
  const warped = await subText('/sub?warp=1');
  check('/sub?warp=1 adds the warp link', warped.body.includes('warp://'));
  const uaWarp = await subText('/sub', { headers: { 'User-Agent': 'CatClient/1.5.3 (+android)' } });
  check('Cat Client UA gets warp automatically', uaWarp.body.includes('warp://'));
  // Cat subscription defaults: plain HTTP first, then secure fallbacks
  const lines = body.trim().split('\n');
  check('first config is plain HTTP :80 (no SNI to filter)', /^vless:\/\/[^@]+@[^:]+:80\?encryption=none&security=none/.test(lines[0]), lines[0]);
  check('default ports follow Cat order 80,443,2053,8443,8080', ['80', '443', '2053', '8443', '8080'].every((p) => body.includes(':' + p + '?')));
  check('TLS links use fp=chrome (universal), not randomized', body.includes('fp=chrome') && !body.includes('fp=randomized'));
  check('IPv6 clean addresses are emitted', /@\[2606:4700:[0-9a-f:]+\]:80\?/.test(body));
  check('remarks are Cat edge/country labels with no IP', /#%F0%9F%90%B1%20Cat%20%C2%B7%20Cloudflare%20edge%20%C2%B7%20VLESS%20%C2%B7%2080%20%C2%B7%20%F0%9F%8C%90%20%C2%B7%20%23\d+/.test(body) && !decodeURIComponent(lines[0]).includes('104.16.'), lines[0]);
  const noV6 = await subText('/sub?v6=0&ports=443&fp=ios');
  check('?v6=0 drops IPv6 entries and ?fp= is honoured', !/@\[2606/.test(noV6.body) && noV6.body.includes('fp=ios'));
  check('/sub TLS links carry rotation-pool SNIs (default primary is the host)', /&sni=[a-z0-9.-]+\.[a-z]{2,}/.test(body) && !body.includes('sni=catpanel-demo.workers.dev'));
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
  check('clean-IP variants keep sni= (rotated from the pool)', (body.match(/&sni=/g) || []).length >= 6);
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
  check('panel locked without password', locked.status === 200 && (await locked.text()).includes('loginPass'));
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
  check('scan targets are IPv4/IPv6', j.targets.every((ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip) || ip.includes(':')));
  check('scan targets size is sane', j.targets.length >= 20 && j.targets.length <= 320);
  check('scan sni defaults to host', j.sni === HOST);
  const opts = await req('/api/ping');
  check('/api/ping rejects non-IP', opts.status === 400);
  const custom = T.sampleSubnet('104.16.0.0/13', 4);
  check('sampleSubnet spreads addresses', custom.length === 4 && custom[0] !== custom[3]);
  check('isIpLiteral accepts v4/v6', T.isIpLiteral('1.1.1.1') && T.isIpLiteral('2606:4700:4700::1111') && !T.isIpLiteral('example.com'));
  // range-first scanner (v5.2)
  check('scan-targets exposes CIDR ranges', Array.isArray(j.ranges) && j.ranges.length >= 10 && j.ranges.every((r) => r.includes('/')));
  const inside = (ip, cidr) => {
    const [b, p] = cidr.split('/');
    const toL = (x) => x.split('.').reduce((a, o) => (a * 256) + Number(o), 0);
    const size = 2 ** (32 - Number(p));
    const base = toL(b) - (toL(b) % size);
    return toL(ip) >= base && toL(ip) < base + size;
  };
  const rnd = T.sampleSubnet('172.67.0.0/24', 8, true);
  check('sampleSubnet random stays inside the block', rnd.length === 8 && rnd.every((ip) => inside(ip, '172.67.0.0/24')));
  check('sampleSubnet never emits .0 or .255', rnd.every((ip) => !/\.(0|255)$/.test(ip)));
  check('sampleSubnet accepts a bare /32', T.sampleSubnet('1.2.3.4/32', 5).join() === '1.2.3.4');
  const a = T.sampleSubnet('104.16.0.0/13', 8, true).join(), b = T.sampleSubnet('104.16.0.0/13', 8, true).join();
  check('random sampling differs between runs', a !== b);
  const exp = T.expandRanges('9.9.9.9, 188.114.96.0/20, nonsense, 10.0.0.0/8', 4);
  check('expandRanges mixes IPs and CIDRs', exp.length === 9 && exp[0] === '9.9.9.9' && exp.slice(1, 5).every((ip) => inside(ip, '188.114.96.0/20')));
  check('scanRanges honours SCAN_RANGES env', T.scanRanges({ SCAN_RANGES: '5.5.0.0/16, junk' }).join() === '5.5.0.0/16' && (T.scanRanges({}).filter((r) => !r.includes(':')).length === T.SCAN_RANGES.length && T.scanRanges({}).some((r) => r.includes(':'))));
  check('default clean addresses have no IR-hosted names', !T.DEFAULT_CLEAN_ADDRESSES.some((a) => /zula\.ir|iranserver/.test(a)));
  const ranged = await req('/api/scan?ranges=' + encodeURIComponent('172.67.0.0/24') + '&per=3');
  check('/api/scan accepts CIDR ranges', ranged.status !== 400);
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response('colo=FRA\nhttp=http/2\n', {
    status: 200,
    headers: { server: 'cloudflare' },
  });
  const verifiedProbe = await T.probeIp(
    '104.16.6.62',
    1000,
    HOST,
    { SCAN_RANGES: '104.16.0.0/13' },
  );
  check('server probe keeps successful edge metadata', verifiedProbe.ok && verifiedProbe.colo === 'FRA' && verifiedProbe.range === '104.16.0.0/13');
  globalThis.fetch = originalFetch;
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
  check('/api/config.json is v5', j.version.startsWith('5.'), j.version);
  check('/api/config.json sub url carries uuid', j.subUrl === 'https://' + HOST + '/sub/' + j.uuid);
  check('/api/config.json exposes config options', j.configOptions && Array.isArray(j.configOptions.ports) && j.configOptions.ports.join() === '80,443,2053,8443,8080');
  check('edge code maps to a country label', T.locationFromColo('FRA').country === 'Germany' && T.locationFromColo('FRA').flag === '🇩🇪');
  const locationLinks = T.buildConfigEntries(HOST, {}, '11111111-2222-3333-4444-555555555555', {
    addresses: ['104.16.1.1'], ports: [443], protocols: ['vless'], includeHost: false,
    includeIpv6: false, sni: HOST, fingerprint: 'chrome', locations: { '104.16.1.1': 'FRA' }, country: '',
  });
  check('scanned location enters the config name without the IP',
    locationLinks.length === 1 && decodeURIComponent(locationLinks[0].link.split('#')[1]).includes('Germany') &&
    !decodeURIComponent(locationLinks[0].link.split('#')[1]).includes('104.16.1.1'));
  check('/api/config.json exposes defaults for the builder', Array.isArray(j.defaultIpv6) && j.defaultIpv6.length >= 2 && Array.isArray(j.defaultPorts));
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
  check('OPEN_PANEL=true opens the panel', openPage.includes('id="brandName"') && openPage.includes('id="hmenu"'));
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
  check('splitHostPort bare host', JSON.stringify(T.splitHostPort('edge.example.com', 443)) === '{"hostname":"edge.example.com","port":443}');
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

// 24. v5.4 — per-user subscriptions: live usage, userinfo headers, /info page, app deep links, regenerate
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', PANEL_TITLE: 'Cat Demo' };
  const created = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'ali', countries: 'DE,FR' }) }), env);
  const body = JSON.parse(await created.text());
  check('user created with state + infoPath', created.status === 201 && body.ok && body.user.state && body.infoPath === '/info/' + body.user.token, JSON.stringify(body).slice(0, 200));
  const token = body.user.token;
  const uuid = body.user.uuid;
  // set a quota so headers carry total=
  const r1 = new Request('https://' + HOST + '/api/users/' + body.user.id, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ quotaGb: 2, days: 30 }) });
  const put = JSON.parse(await (await worker.fetch(r1, env)).text());
  check('PUT keeps token/uuid and sets quota', put.ok && put.user.token === token && put.user.uuid === uuid && put.user.quotaGb === 2 && put.user.expireAt > Date.now());

  // buffered traffic (not yet in KV) is visible in the sub headers after the forced flush
  await T.accountTraffic(env, uuid, 1000, 2000, true);
  const sub = await req('/u/' + token, { env, raw: true });
  const ui = sub.headers.get('subscription-userinfo') || '';
  check('/u/<token> carries subscription-userinfo with live download', /download=3000;/.test(ui) && /total=2147483648;/.test(ui) && /expire=\d{10}/.test(ui), ui);
  check('/u/<token> carries profile-title + web page url', (sub.headers.get('profile-title') || '').startsWith('base64:') && (sub.headers.get('profile-web-page-url') || '').endsWith('/info/' + token));
  const subBody = b64dec(await sub.text());
  check('/u/<token> body is the same BPB-style list', subBody.includes('vless://' + uuid + '@') && !subBody.includes('warp://'));
  const stats = JSON.parse(await (await req('/u/' + token + '?stats=1', { env, raw: true })).text());
  check('?stats=1 reports used/total/daysLeft', stats.ok && stats.used === 3000 && stats.total === 2147483648 && stats.daysLeft >= 29 && stats.status === 'active');
  const info = await req('/info/' + token, { env, raw: true });
  const infoHtml = await info.text();
  check('/info/<token> is a public HTML page', info.status === 200 && (info.headers.get('content-type') || '').includes('text/html') && infoHtml.includes('ringArc'));
  check('/info page offers v2rayNG + V2Box + Hiddify deep links', infoHtml.includes('v2rayng://install-sub?url=') && infoHtml.includes('v2box://install-sub?url=') && infoHtml.includes('hiddify://import/'));
  check('/u/<token>?web=1 also renders the page (never by UA sniffing)', (await (await req('/u/' + token + '?web=1', { env, raw: true })).text()).includes('ringArc'));

  // Owner-scanned anycast edges become the only subscription inputs. The public
  // page can then limit count/country without ever advertising failed IPs.
  const verifiedSettings = await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: {
      verified: [
        { ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany' },
        { ip: '104.16.1.2', colo: 'CDG', countryCode: 'FR', countryName: 'France' },
      ],
      verifiedScanned: true,
    } }),
  }), env);
  check('verified scan set persists for subscriptions', verifiedSettings.status === 200);
  const chosen = await req('/u/' + token + '/all?verified=1&countries=DE&count=3', { env, raw: true });
  const chosenJson = await chosen.json();
  check('recipient country/count filter returns only successful country IPs', chosen.status === 200 && chosenJson.verifiedOnly === true && chosenJson.entries.length === 3 && chosenJson.entries.every((entry) => entry.addr === '104.16.1.1' && entry.countryName === 'Germany'));
  const selectedPage = await req('/info/' + token, { env, raw: true });
  const selectedHtml = await selectedPage.text();
  check('recipient landing page has country selector and grouped config viewer', selectedHtml.includes('recipientConfigs') && selectedHtml.includes('countryChoices') && selectedHtml.includes('recipientGroups') && selectedHtml.includes('Germany') && selectedHtml.includes('France'));
  const verifiedSub = await req('/u/' + token + '?verified=1&count=4', { env, raw: true });
  const verifiedBody = b64dec(await verifiedSub.text());
  check('verified subscription omits the panel host and unverified fallback IPs', verifiedBody.includes('@104.16.1.1:') && verifiedBody.includes('@104.16.1.2:') && !verifiedBody.includes('@catpanel-demo.workers.dev:') && !verifiedBody.includes('@104.16.132.229:'));

  const uaSub = await req('/u/' + token, { env, raw: true, headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android) Chrome/120 Mobile' } });
  check('browser UA on /u/<token> still gets raw base64 (WebView apps)', !(await uaSub.text()).includes('<html'));
  const htmlGet = await worker.fetch(new Request('https://' + HOST + '/u/' + token, { headers: { 'Accept': 'text/html,application/xhtml+xml', 'User-Agent': 'Mozilla/5.0 (Linux; Android) Chrome/120 Mobile' } }), env);
  check('browser Accept on /u/<token> redirects to the chooser page', htmlGet.status === 302 && (htmlGet.headers.get('Location') || '').includes('/info/' + token));

  const gatedCreate = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'nogeo' }) }), env);
  const gatedUser = JSON.parse(await gatedCreate.text()).user;
  const gatedSub = b64dec(await (await req('/u/' + gatedUser.token, { env, raw: true })).text());
  check('user WITHOUT countries serves the owner set (sub adds everywhere)', gatedSub.includes('vless://') && gatedSub.length > 100);
  const gatedInfo = await (await req('/info/' + gatedUser.token, { env, raw: true })).text();
  check('info page for a country-less user shows the chooser (never a dead end)', gatedInfo.includes('recipientConfigs') && gatedInfo.includes('countryChoices'));
  const restrictedSub = b64dec(await (await req('/u/' + token, { env, raw: true })).text());
  check('user WITH countries stays restricted to the picked set', restrictedSub.includes('@104.16.1.1:') && restrictedSub.includes('@104.16.1.2:'));
  const catClientSub = await req('/u/' + gatedUser.token, { env, raw: true, headers: { 'User-Agent': 'CatClient/1.9.44 (+android)', Accept: 'text/yaml,application/json,text/plain,*/*;q=0.1' } });
  const catClientBody = b64dec(await catClientSub.text());
  check('regression: Cat Client UA on a fresh user sub gets a non-empty payload', catClientSub.status === 200 && catClientBody.includes('vless://'), 'len=' + catClientBody.length);

  // quota exceeded → tunnel and sub blocked (Trojan too)
  const r2 = new Request('https://' + HOST + '/api/users/' + body.user.id, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ usedBytes: 3 * 1024 * 1024 * 1024 }) });
  await worker.fetch(r2, env);
  const blocked = await req('/u/' + token, { env, raw: true });
  check('over-quota user gets 403 on the sub', blocked.status === 403);
  const users = await T.readUsers(env);
  const tAuth = await T.tunnelAuth(env, uuid, await T.readSettings(env));
  check('over-quota user is refused by tunnelAuth', tAuth.ok === false && tAuth.error === 'quota-exceeded');
  const stillInfo = await req('/info/' + token, { env, raw: true });
  check('/info stays reachable for a blocked user (shows status)', stillInfo.status === 200 && (await stillInfo.text()).includes('حجم تمام شده'));

  // regenerate rotates uuid + token
  const regen = JSON.parse(await (await new Promise((resolve) => resolve(worker.fetch(new Request('https://' + HOST + '/api/users/' + body.user.id + '/regenerate', { method: 'POST' }), env)))).text());
  check('regenerate rotates uuid and token', regen.ok && regen.user.uuid !== uuid && regen.user.token !== token);
  check('old token is gone after regenerate', (await req('/u/' + token, { env, raw: true })).status === 404);

  // list with ?sync=1 flushes the buffer and returns state
  await T.accountTraffic(env, regen.user.uuid, 10, 10, false);
  const list = JSON.parse(await (await req('/api/users?sync=1', { env, raw: true })).text());
  check('GET /api/users?sync=1 returns state + online', list.ok && list.users[0].state && typeof list.online === 'number');
  const flushed = await T.readUsers(env);
  check('?sync=1 flushed buffered bytes into KV', flushed[0].usedBytes >= 20, String(flushed[0].usedBytes));

  const links = T.appDeepLinks('https://h.example/u/abc', 'Cat');
  check('appDeepLinks covers v2rayNG, V2Box, Hiddify, Streisand, sing-box, Clash', ['v2rayng', 'v2box', 'hiddify', 'streisand', 'singbox', 'clash'].every((id) => links.some((l) => l.id === id)));
  check('sing-box deep link points at the /singbox format', links.find((l) => l.id === 'singbox').href.includes(encodeURIComponent('https://h.example/u/abc/singbox')));
  const home = await (await req('/?p=x', { env, raw: true })).text();
  check('panel home shows add-to-app buttons for the master sub', home.includes('data-app="v2rayng"') && home.includes('data-app="v2box"'));
  check('panel users tab has the new actions', home.includes('data-user-regen') && home.includes('data-user-info') && home.includes('data-user-toggle'));
}

// 25. Trojan auth applies the same quota/expiry gate as VLESS
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true' };
  const u = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'exp', days: 1 }) }), env)).text()).user;
  const hash = await T.trojanHash(u.uuid);
  const ok = await T.trojanAuthorized(env, await T.readSettings(env), hash, '');
  check('trojan auth accepts a healthy user', ok.ok === true && ok.user && ok.user.uuid === u.uuid);
  await worker.fetch(new Request('https://' + HOST + '/api/users/' + u.id, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ enabled: false }) }), env);
  const no = await T.trojanAuthorized(env, await T.readSettings(env), hash, '');
  check('trojan auth refuses a disabled user', no.ok === false && no.error === 'disabled');
}

// 26. v5.5: fake 404 camouflage + brute-force window that actually expires
{
  const unknown = await req('/wp-admin/setup.php', { raw: true });
  const unknownBody = await unknown.text();
  check('unknown path → fake nginx 404 (no CORS, no branding)',
    unknown.status === 404 && unknownBody.includes('<center>nginx</center>') &&
    !unknownBody.toLowerCase().includes('cat') && unknown.headers.get('access-control-allow-origin') === null);
  const tunnelGet = await req('/ws', { raw: true });
  check('plain GET on tunnel path is indistinguishable from unknown path', tunnelGet.status === 404 && (await tunnelGet.text()) === unknownBody);
  const realRoutesStillWork = await req('/health', { raw: true });
  check('/health still answers', realRoutesStillWork.status === 200);

  const now = Date.now();
  check('brute state: empty → 0', T.readBruteState(null, now).count === 0);
  check('brute state: legacy number counts as expired', T.readBruteState('7', now).count === 0);
  check('brute state: live window kept', T.readBruteState(JSON.stringify({ count: 3, until: now + 1000 }), now).count === 3);
  check('brute state: elapsed window reset', T.readBruteState(JSON.stringify({ count: 3, until: now - 1 }), now).count === 0);

  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, PANEL_PASSWORD: 'secret-pw' };
  const login = (pw, ip) => worker.fetch(new Request('https://' + HOST + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json', 'cf-connecting-ip': ip || '198.51.100.7' }, body: JSON.stringify({ password: pw }),
  }), env);
  let last = null;
  for (let i = 0; i < 8; i++) last = await login('nope');
  check('8 wrong attempts → 401 with attemptsLeft 0', last.status === 401 && JSON.parse(await last.text()).attemptsLeft === 0);
  const blocked = await login('secret-pw');
  check('9th attempt is 429 even with the right password (+ retry-after)', blocked.status === 429 && Number(blocked.headers.get('retry-after')) > 0);
  const otherIp = await login('secret-pw', '203.0.113.9');
  check('other IP is not affected', otherIp.status === 200);
  // expire the window by rewriting the stored state, then a correct login clears it
  const key = 'catpanel:brute:198.51.100.7';
  mem.set(key, JSON.stringify({ count: 8, until: Date.now() - 5 }));
  const afterWindow = await login('secret-pw');
  check('after the window a correct login succeeds and clears the counter', afterWindow.status === 200 && !mem.has(key));
}

// 27. v5.11 — admin gate: username + password (a management panel, not open access)
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, PANEL_PASSWORD: 'pw-123', PANEL_USER: 'boss' };
  const login = (body) => worker.fetch(new Request('https://' + HOST + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  }), env);
  const wrongUser = await login({ username: 'hacker', password: 'pw-123' });
  check('username gate: right password + wrong username is rejected', wrongUser.status === 401 && JSON.parse(await wrongUser.text()).userRequired === true);
  const right = await login({ username: 'Boss', password: 'pw-123' });
  check('login accepts username (case-insensitive) + password and sets the session cookie', right.status === 200 && (right.headers.get('set-cookie') || '').includes('catpanel_auth='));
  const cookie = (right.headers.get('set-cookie').match(/catpanel_auth=([^;]+)/) || [])[1] || '';
  const withCookie = await worker.fetch(new Request('https://' + HOST + '/', { headers: { cookie: 'catpanel_auth=' + cookie } }), env);
  const pageHtml = await withCookie.text();
  check('panel shell opens with the username+password session cookie', withCookie.status === 200 && pageHtml.includes('id="brandName"'));
  check('sidebar admin shell + section heads render', pageHtml.includes('class="side-nav"') && pageHtml.includes('section-head') && pageHtml.includes('sideButton') === false);
  const noCookie = await worker.fetch(new Request('https://' + HOST + '/'), env);
  const noCookieHtml = await noCookie.text();
  check('without a session the login form asks username + password', noCookieHtml.includes('loginUser') && noCookieHtml.includes('loginPass'));
  const qpBypass = await worker.fetch(new Request('https://' + HOST + '/?p=pw-123'), env);
  const qpHtml = await qpBypass.text();
  check('?p= alone no longer unlocks when a username is set', qpHtml.includes('loginUser'));
  const qpNoUser = await worker.fetch(new Request('https://' + HOST + '/?p=pw-123'), { CAT_KV: kv, PANEL_PASSWORD: 'pw-123' });
  check('?p= still works for password-only panels', (await qpNoUser.text()).includes('id="brandName"'));
}


// 28. v5.12 — selected IPs REALLY enter the sub (union), multi-country saved set, health-check pruning
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const created = await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'multi', countries: 'DE,FR' }),
  }), env);
  const u = JSON.parse(await created.text()).user;
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: {
      addresses: ['104.16.9.9'],
      countryCodes: 'DE,FR',
      verified: [
        { ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany' },
        { ip: '104.16.1.2', colo: 'CDG', countryCode: 'FR', countryName: 'France' },
      ],
      verifiedScanned: true,
      locations: { '104.16.9.9': 'FRA' },
    } }),
  }), env);
  const { body } = await subText('/u/' + u.token, { env, raw: true });
  check('selected IP + scan pool both in the sub (union, not replacement)', body.includes('@104.16.9.9:') && body.includes('@104.16.1.1:') && body.includes('@104.16.1.2:'), body.slice(0, 160));
  const cfgj = JSON.parse(await (await req('/api/config.json', { env })).text());
  const { body: masterBody } = await subText('/sub/' + cfgj.uuid, { env, raw: true });
  check('bare master sub carries the saved selection + pool too', masterBody.includes('@104.16.9.9:') && masterBody.includes('@104.16.1.2:'), masterBody.slice(0, 160));
  const origFetch = globalThis.fetch;
  let probeCalls = 0;
  globalThis.fetch = async () => {
    probeCalls += 1;
    if (probeCalls === 1) return new Response('colo=FRA\nip=9.9.9.9\n', { status: 200, headers: { server: 'cloudflare' } });
    return new Response('nope', { status: 503 });
  };
  const hc = JSON.parse(await (await req('/api/health-check', { env, method: 'POST', raw: true })).text());
  check('health-check pings each IP and reports colo/country', hc.ok && hc.results.find((r) => r.ip === '104.16.9.9').colo === 'FRA' && hc.results.find((r) => r.ip === '104.16.9.9').countryName === 'Germany');
  check('dead IPs are pruned (alive=1, both verified entries reported dead)', hc.alive === 1 && hc.dead.includes('104.16.1.1') && hc.dead.includes('104.16.1.2'));
  const s2 = await T.readSettings(env);
  check('pruning persists to KV (addresses + verified)', s2.configs.addresses.join() === '104.16.9.9' && s2.configs.verified.length === 0);
  globalThis.fetch = origFetch;
}


// 29. v5.12.1 regression — every panel/page <script> block must PARSE
// (a single quote-level slip kills ALL panel buttons; this caught renderPoolUi)
{
  const shell = await (await req('/', { env: { OPEN_PANEL: 'true' }, raw: true })).text();
  const login = await (await req('/login', { raw: true })).text();
  const info = await (await req('/info/does-not-exist', { raw: true })).text();
  const blocks = [];
  for (const page of [shell, login, info]) {
    for (const m of page.matchAll(/<script>([\s\S]*?)<\/script>/g)) blocks.push(m[1]);
  }
  let bad = 0;
  blocks.forEach((body, i) => { try { new Function(body); } catch (e) { bad++; console.error('script block ' + i + ': ' + e.message); } });
  check('all rendered page scripts parse (' + blocks.length + ' blocks)', bad === 0);
  check('panel client JS still wires the health + scanner buttons', shell.includes('healthBtn') && shell.includes('scanServerAll'));
  const countSel = (shell.match(/id="cfgCount"/g) || []).length;
  check('exactly ONE #cfgCount (the count select) — a duplicate id used to let the counter pill destroy the selector', countSel === 1 && shell.includes('id="cfgCountLabel"'));
  check('count select + scan selection are live-wired', shell.includes('cfgCount").addEventListener("change",applyOptions)') && shell.includes('scanSelCount') && shell.includes('r.ms!==null||r.tls!==null||(r.server&&r.server.ok)') && shell.includes('updateSelCount'));
  const selMem = new Map();
  const gatedEnv = { CAT_KV: { get: async (k) => selMem.get(k) ?? null, put: async (k, v) => { selMem.set(k, v); }, delete: async (k) => { selMem.delete(k); } }, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const gu = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'sel', countries: 'DE' }) }), gatedEnv)).text()).user;
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { verified: [{ ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany' }], verifiedScanned: true } }),
  }), gatedEnv);
  const infoSel = await (await req('/info/' + gu.token, { env: gatedEnv, raw: true })).text();
  check('sections moved to a top hamburger menu (bottom bar removed)', shell.includes('burgerBtn') && shell.includes('id="hmenu"') && !shell.includes('<nav class="tabs"') && (shell.match(/data-tab="scanner"/g) || []).length >= 2);
  check('palette lightened (v5.14 twilight)', shell.includes('--bg:#191330') && shell.includes('--on-accent:#1b1030'));
  check('recipient page count/country pickers rebuild live', infoSel.includes('configCount\").addEventListener(\"change\",loadRecipientConfigs)'));
}


// 30. v5.13 — multi-SNI configs, scan location payloads, country chips picker
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const su = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'sni', countries: 'DE' }),
  }), env)).text()).user;
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { snis: 'a.com,b.com', verified: [{ ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany' }], verifiedScanned: true } }),
  }), env);
  const all = await (await req('/u/' + su.token + '/all?count=4', { env, raw: true })).json();
  const links = all.entries.map((e) => e.link);
  check('multi-SNI: entries rotate through the SNI list', links.some((l) => l.includes('sni=a.com')) && links.some((l) => l.includes('sni=b.com')));
  const cfgj = JSON.parse(await (await req('/api/config.json', { env })).text());
  check('configOptions carries the sni list', Array.isArray(cfgj.configOptions.snis) && cfgj.configOptions.snis.includes('a.com') && cfgj.configOptions.snis.includes('b.com'));
  const scan = await (await req('/api/scan?ips=192.0.2.1&timeout=1000', { env, raw: true })).json();
  check('scan results carry resolved location (flag/city/country)', scan.ok && 'location' in scan.results[0]);
  const shell = await (await req('/', { env, raw: true })).text();
  check('country selection is chip-based (create + edit) — no more typing', shell.includes('uCountryChips') && shell.includes('pickCountries(u.countries') && !shell.includes('var cc=prompt'));
  check('scanner has extra-SNI testing + best-IP picker + snis passthrough', shell.includes('scanSnis') && shell.includes('scanPickBest') && shell.includes('snisQ'));
}


// 31. v5.14.1 — panel-script id references must all exist in the markup
// (a single missing id = null.addEventListener at load = the whole client dies)
{
  const src = readFileSync(workerPath, 'utf8');
  const refs = [...new Set([...src.matchAll(/\$\("#([A-Za-z0-9_-]+)"\)/g)].map((m) => m[1]))];
  const missing = refs.filter((id) => !src.includes('id="' + id + '"'));
  check('every $("#id") handler target exists in the panel markup (' + refs.length + ' refs)', missing.length === 0);
  if (missing.length) console.log('   missing ids: ' + missing.join(', '));
  check('scanner extra-SNI input is a real element (feature is live)', src.includes('id="scanSnis"'));
  check('country-pool list has a render container', src.includes('id="countryPools"'));
  check('config table emits address-major (all ports of a picked IP together)', src.includes('addrs.forEach(function(h){ports.forEach(function(p)'));
  check('server subs: recipients stay port-major for country variety', src.includes('options.recipient') && src.includes('pairLoops'));
  check('canonical Cat port order kept on both sides', src.includes('CAT_PORT_ORDER') && src.includes('var CATPORT=[80,443,2053,2083,8443,8080]'));
}

// 32. v5.14.2 — scanner sampler: IPv4-first, IPv6 strictly opt-in and valid
// (regression: a misplaced `return` killed the whole v4 path and the broken
// parser emitted 2-group v6 garbage — the "scanner only makes dead v6" bug)
{
  const lines = src.split('\n');
  const i0 = lines.findIndex((l) => l.includes("'function expandCustom"));
  const i1 = lines.findIndex((l) => l.includes('return v4.concat(v6)'));
  const code = lines.slice(i0, i1 + 1).map((l) => eval(l.trim().replace(/,$/, ''))).join('\n');
  const expand = new Function('$', code + '\nreturn expandCustom;')(() => 8);
  const isV6 = (ip) => /^([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}$/i.test(ip);
  const off = expand('2606:4700::/32, 104.16.0.0/13', 8, false);
  check('scanner: with IPv6 off the v4 path stays alive', off.length === 8 && off.every((ip) => !ip.includes(':') && ip.startsWith('104.')));
  const v6 = expand('2606:4700::/32', 5, true);
  check('scanner: opt-in IPv6 addresses are full 8-hextet', v6.length === 5 && v6.every((ip) => isV6(ip) && ip.toLowerCase().startsWith('2606:4700')));
  const mixed = expand('104.16.0.0/13, 2606:4700::/32', 8, true);
  const firstV6 = mixed.findIndex((ip) => ip.includes(':'));
  check('scanner: IPv4 always first, IPv6 capped at 12', firstV6 >= 8 && mixed.filter((ip) => ip.includes(':')).length <= 12);
  check('scanner: IPv6 checkbox exists and gates both scan paths', src.includes('id="scanV6"') && src.includes('checked===true'));
}

// 33. v5.14.4 — /api/geo is a PURE geo-DB proxy: it geolocates GIVEN addresses
// and echoes the caller. It must NEVER trace from the worker itself — the
// worker's own egress is a datacenter IP, not the phone's tunnel exit.
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const geo = await (await req('/api/geo?ip=198.51.100.9,2001:db8::1', { env, headers: { 'cf-connecting-ip': '198.51.100.7' }, raw: true })).json();
  check('/api/geo echoes the caller IP', geo.ok === true && geo.caller === '198.51.100.7');
  check('/api/geo geolocates the REQUESTED addresses (keys present)', geo.geo && '198.51.100.9' in geo.geo && '2001:db8::1' in geo.geo);
  check('/api/geo carries the entry colo field', 'entryColo' in geo);
  const handler = src.slice(src.indexOf("path === '/api/geo'"), src.indexOf("path === '/api/scan'"));
  check('/api/geo never egress-traces from the worker (exit is measured on the phone)', !handler.includes('cdn-cgi/trace') && !handler.includes('ipwho.is'));
}
// 34. v5.15.0 — per-IP BEST SNI: the scanner save keeps the fastest OK sni
// per address and the subscription bakes it in instead of rotating blindly.
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { verified: [
      { ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany', sni: 'winner.example.com' },
      { ip: '172.67.1.1', colo: 'CDG', countryCode: 'FR', countryName: 'France' },
    ], verifiedScanned: true } }),
  }), env);
  const su = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'bsni', countries: 'DE,FR' }),
  }), env)).text()).user;
  const all = await (await req('/u/' + su.token + '/all?count=6&proto=vless&host=0&sni=base.example.com', { env, raw: true })).json();
  const winner = all.entries.find((e) => e.addr === '104.16.1.1' && e.tls);
  const plain = all.entries.find((e) => e.addr === '172.67.1.1' && e.tls);
  check('entries use the per-IP BEST sni when one is stored', winner && winner.link.includes('sni=winner.example.com'));
  check('addresses without a measured sni fall back to the rotation', plain && plain.link.includes('sni=base.example.com'));
  const saveCode = src.slice(src.indexOf("searchParams.get('save')"), src.indexOf("return jsonResponse({ ok: true, count: sorted.length"));
  check('scan save keeps the fastest OK sni per IP', saveCode.includes('bestSni') && saveCode.includes('sni: bestSni'));
}

// 35. v5.15.1 — the recipient sub page shows EVERYTHING: all configs by
// default and a QR for every single config (plus the sub QR that existed).
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const su = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'page', countries: 'DE' }),
  }), env)).text()).user;
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { verified: [{ ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany' }], verifiedScanned: true } }),
  }), env);
  const info = await (await req('/info/' + su.token, { env, raw: true })).text();
  check('recipient page defaults to ALL configs (up to 200)', info.includes('value="200" selected>همه (تا ۲۰۰)'));
  check('recipient page has a per-config QR button wired to the modal', info.includes('data-qr-config') && info.includes('data-qr-config')); 
  check('recipient page still carries the sub QR + copy + deep-links', info.includes('id="qrSub"') && info.includes('data-copy-config') && info.includes('catclient://add-sub'));
  const browserRes = await worker.fetch(new Request('https://' + HOST + '/u/' + su.token, {
    headers: { 'user-agent': 'Mozilla/5.0 (Linux; Android 14) Chrome/126 Mobile Safari/537.36', accept: 'text/html' },
    redirect: 'manual',
  }), env);
  check('browser /u/<token> still redirects to the info page', browserRes.status === 302);
  const appRes = await worker.fetch(new Request('https://' + HOST + '/u/' + su.token, {
    headers: { 'user-agent': 'v2rayNG/1.8.14' },
  }), env);
  check('client apps still get the raw base64 subscription', appRes.status === 200 && (await appRes.text()).length > 40);
}

// 36. v5.16.0 — worker quota self-monitoring (no CF token anywhere) and
// owner Telegram notifications with the bot token redacted on read-back.
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await worker.fetch(new Request('https://' + HOST + '/', {}), env);
  const quota = await (await req('/api/quota', { env, raw: true })).json();
  check('/api/quota self-counts requests without any CF token', quota.ok === true && quota.requests >= 1 && quota.limit === 100000 && quota.sampled === true);
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ telegram: { enabled: true, chat: '@catchannel', token: '123:ABC' } }),
  }), env);
  const cfg = await (await req('/api/settings', { env, raw: true })).json();
  check('telegram bot token is redacted on read-back', cfg.ok && cfg.settings.telegram && cfg.settings.telegram.token === '' && cfg.settings.telegram.tokenSet === true && cfg.settings.telegram.chat === '@catchannel');
  const probe = await req('/api/telegram-test', { env, method: 'POST', raw: true });
  const probeBody = await probe.json();
  check('/api/telegram-test answers honestly when the bot is unreachable', probeBody.ok === false);
  const src2 = src;
  check('quota/telegram UI exists in the tools tab', src2.includes('id="quotaBar"') && src2.includes('id="tgSave"') && src2.includes('id="tgTest"'));
  check('worker counts every request at the fetch entry', src2.includes('noteRequest(env, ctx);'));
}

// 37. v5.17.0 — ping-sorted subs, gaming mode (low-latency ports + WARP for
// real UDP) and the Spoof tab in the hamburger menu.
{
  const mem = new Map();
  const kv = { get: async (k) => mem.get(k) ?? null, put: async (k, v) => { mem.set(k, v); }, delete: async (k) => { mem.delete(k); } };
  const env = { CAT_KV: kv, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const fastIp = '104.16.7.7', slowIp = '104.16.8.8';
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { verified: [
      { ip: slowIp, colo: 'FRA', countryCode: 'DE', countryName: 'Germany', ms: 900 },
      { ip: fastIp, colo: 'CDG', countryCode: 'FR', countryName: 'France', ms: 80 },
    ], verifiedScanned: true } }),
  }), env);
  const su = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'fast', countries: 'DE,FR' }),
  }), env)).text()).user;
  const all = await (await req('/u/' + su.token + '/all?count=4&proto=vless&ports=443&host=0', { env, raw: true })).json();
  const addrs = all.entries.filter((e) => e.tls).map((e) => e.addr);
  const fi = addrs.indexOf(fastIp), si = addrs.indexOf(slowIp);
  check('subs lead with the FASTEST measured IP (ping-sorted)', fi !== -1 && si !== -1 && fi < si);
  const g = await (await req('/u/' + su.token + '/all?count=6&proto=vless&ports=443,8080,2053&host=0&gaming=1', { env, raw: true })).json();
  check('gaming mode keeps only low-latency ports (80/443)', g.entries.length > 0 && g.entries.every((e) => e.port === 80 || e.port === 443));
  const raw = await (await req('/u/' + su.token + '/raw?gaming=1', { env, headers: { 'user-agent': 'v2rayNG/1.8' }, raw: true })).text();
  check('gaming sub appends the WARP entry (the UDP path)', raw.includes('warp://'));
  const shell = await (await req('/', { env, raw: true })).text();
  check('hamburger has the Spoof tab with fingerprint chips', shell.includes('data-tab="spoof"') && shell.includes('spoofChips') && shell.includes('data-fp="randomized"'));
  check('config builder exposes the gaming toggle', src.includes('data-flag="gaming"') && src.includes('gaming=1'));
  await worker.fetch(new Request('https://' + HOST + '/api/settings', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configs: { fingerprint: 'ios' } }),
  }), env);
  const iosSub = await (await req('/u/' + su.token + '/raw?count=1&proto=vless&ports=443&host=0', { env, headers: { 'user-agent': 'v2rayNG/1.8' }, raw: true })).text();
  check('spoof fingerprint flows into new config links', iosSub.includes('fp=ios'));
}

// §38 — global SNI rotation pool (all countries, DNS-verified Cloudflare)
{
  const pool = T.DEFAULT_EXTRA_SNIS || [];
  check('SNI pool has 20+ entries', pool.length >= 20, 'got ' + pool.length);
  check('every pool entry is a clean hostname (no IPs, no paths)', pool.every((s) => /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s) && !/^\d/.test(s)));
  check('pool has no duplicates', new Set(pool).size === pool.length);
  check('pool never suggests filtered-first-party hosts', !pool.some((s) => s.endsWith('workers.dev') || s.endsWith('pages.dev')));
  const sample = T.sampleDefaultSnis(3);
  check('scanner default probe is a 3-SNI sample of the pool', sample.length === 3 && sample.every((s) => pool.includes(s)));
  check('sample clamps to pool size and honours zero', T.sampleDefaultSnis(99).length === pool.length && T.sampleDefaultSnis(0).length === 0);
  const allowed = T.allowedSnis('panel.example.workers.dev', {});
  check('SNI gate accepts the whole pool', pool.every((s) => allowed.has(s)));
  // Builder rotation: no cfg snis, no verified winners → links must carry pool SNIs
  const mem38 = new Map();
  const kv38 = { get: async (k) => mem38.get(k) ?? null, put: async (k, v) => { mem38.set(k, v); }, delete: async (k) => { mem38.delete(k); } };
  const env38 = { CAT_KV: kv38, OPEN_SUB: 'true', OPEN_PANEL: 'true' };
  const su38 = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'sni38', countries: 'DE' }),
  }), env38)).text()).user;
  const rot = await subText('/u/' + su38.token + '/raw?proto=vless&ports=443&host=0&count=3&ips=103.21.244.10,104.16.132.229', { env: env38, headers: { 'user-agent': 'v2rayNG/1.8' }, raw: true });
  const rotDec = rot.body;
  const sniVals = (rotDec.match(/[?&]sni=([^&]+)/g) || []).map((x) => decodeURIComponent(x.replace(/[?&]sni=/, '')));
  check('configs rotate through the pool when no snis are set', sniVals.length >= 2 && sniVals.some((v) => pool.includes(v)), sniVals.join(','));
  check('rotation keeps the panel host OUT of the sni= field when a pool winner exists', sniVals.every((v) => pool.includes(v)));
  const snap = T.sampleDefaultSnis(0);
  check('empty sample is harmless', snap.length === 0);
}

// §39 — worker-pulled fresh community IPs (fail-soft, CF-range validated)
{
  check('community sources exist with mirror chains', T.COMMUNITY_IP_SOURCES.length >= 2 && T.COMMUNITY_IP_SOURCES.every((src) => src.urls.length >= 4 && src.urls[0].includes('jsdelivr')));
  const mixed = ['104.16.1.7', '104.16.1.7', '8.8.8.8', '104.16.0.0/13', 'zula.ir', 'hello', '999.1.2.3', '172.64.80.9', '2606:4700::6810:84e5', '2606:4700:3030::ac43:b58a', '1.2.3.4'].join('\n');
  const parsed = T.parseCommunityIps(mixed);
  check('parser keeps ONLY official-CF-range IPs (v4+v6), deduped', JSON.stringify(parsed) === JSON.stringify(['104.16.1.7', '172.64.80.9', '2606:4700::6810:84e5', '2606:4700:3030::ac43:b58a']), JSON.stringify(parsed));
  check('parser drops Google DNS, CIDRs, hostnames, junk', !parsed.includes('8.8.8.8') && !parsed.includes('1.2.3.4') && parsed.every((x) => !x.includes('/')));

  const mem39 = new Map();
  const kv39 = { get: async (k) => mem39.get(k) ?? null, put: async (k, v) => { mem39.set(k, v); }, delete: async (k) => { mem39.delete(k); } };
  const env39 = { CAT_KV: kv39, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    const u = String(url);
    if (u.includes('ipv6.txt') || u.includes('ipv6.json')) return { ok: true, status: 200, text: async () => '2606:4700::6810:84e5\n2606:4700::4400' };
    if (u.includes('ip.txt') || u.includes('ipv4.json') || u.includes('bestcf.txt')) return { ok: true, status: 200, text: async () => mixed };
    return { ok: false, status: 404, text: async () => '' };
  };
  let report;
  try {
    report = await (await req('/api/community-ips', { env: env39, method: 'POST', headers: { 'content-type': 'application/json' }, raw: true })).json();
  } finally {
    globalThis.fetch = realFetch;
  }
  check('POST /api/community-ips pulls from worker-side sources', report.ok === true && report.added === 5 && report.total === 5, JSON.stringify(report));
  check('all five verified sources answered through the mirror chain', report.sources.length === 5 && report.sources.every((x) => x.ok));
  const status = await (await req('/api/community-ips', { env: env39, raw: true })).json();
  check('GET status reports the stored pool', status.ok && status.total === 5 && status.at > 0);
  const settings39 = await T.readSettings(env39);
  check('stored pool rejects non-CF and keeps fresh candidates', T.communityIpsFrom(settings39).length === 5 && !T.communityIpsFrom(settings39).includes('8.8.8.8'));
  const uuid39 = '11111111-2222-3333-4444-555555555555';
  const state39 = T.panelState(HOST, env39, uuid39, null, settings39);
  check('fresh IPs ride along as the community top-up pool', ['104.16.1.7', '2606:4700::4400'].every((ip) => (state39.communityTargets || []).includes(ip)));
  // fail-soft: every source dead → ok:true, added:0, old pool untouched
  globalThis.fetch = async () => { throw new Error('network dead'); };
  let dead;
  try {
    dead = await (await req('/api/community-ips', { env: env39, method: 'POST', headers: { 'content-type': 'application/json' }, raw: true })).json();
  } finally {
    globalThis.fetch = realFetch;
  }
  check('total source failure is fail-soft (pool untouched)', dead.ok === true && dead.added === 0 && dead.total === 5 && dead.sources.every((x) => !x.ok));
  const after = T.communityIpsFrom(await T.readSettings(env39));
  check('fail-soft POST preserves the previous pool', after.length === 5);
}

// §40 — usage accounting: master traffic + per-user "today" + panel totals
{
  const mem40 = new Map();
  const kv40 = { get: async (k) => mem40.get(k) ?? null, put: async (k, v) => { mem40.set(k, v); }, delete: async (k) => { mem40.delete(k); } };
  const MASTER = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const env40 = { CAT_KV: kv40, OPEN_PANEL: 'true', OPEN_SUB: 'true', UUID: MASTER };
  const created = await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'usage', quotaGb: 10 }),
  }), env40);
  const u = JSON.parse(await created.text()).user;
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  const today = new Date().toISOString().slice(0, 10);
  // seed yesterday's state for user and master
  u.usedBytes = 1000; u.day = yesterday; u.dayBytes = 400;
  await T.writeUsers(env40, [u]);
  await T.writeMasterUsage(env40, { usedBytes: 5000, day: yesterday, dayBytes: 700, lastSeenAt: 1 });
  // simulate tunnel bytes: master + user + an unknown uuid
  T.trafficBuffers.set(MASTER, { sent: 1500, received: 2500 });
  T.trafficBuffers.set(u.uuid, { sent: 300, received: 700 });
  T.trafficBuffers.set('unknown-uuid-xyz', { sent: 40, received: 80 });
  const okFlush = await T.flushTraffic(env40);
  check('flush succeeds with master+user mix', okFlush === true);
  const usersNow = await T.readUsers(env40);
  const uNow = usersNow.find((x) => x.id === u.id);
  check('user usage written + daily bucket rolled over to today', uNow.usedBytes === 2000 && uNow.dayBytes === 1000 && uNow.day === today, JSON.stringify({ used: uNow.usedBytes, day: uNow.day, dayB: uNow.dayBytes }));
  const m = await T.readMasterUsage(env40);
  check('MASTER config usage is counted (was silently dropped before)', m.usedBytes === 9000 && m.dayBytes === 4000, JSON.stringify(m));
  check('unknown-uuid bytes are dropped, attributed to nobody', T.bufferedBytes('unknown-uuid-xyz') === 0 && m.usedBytes === 9000);
  const list = await (await req('/api/users?sync=1', { env: env40, raw: true })).json();
  check('users API exposes the traffic summary', !!list.traffic && list.traffic.master.used === 9000 && list.traffic.master.today === 4000 && list.traffic.today === 1000, JSON.stringify(list.traffic));
  const row = list.users.find((x) => x.id === u.id);
  check('per-user state carries today', row && row.state && row.state.today === 1000);
  check('panel renders مصرف امروز stat + master total line', src.includes('uTodayUsed') && src.includes('مصرف امروز') && src.includes('tr.master&&tr.master.used'));
  check('master usage normalizer resets stale days', (() => { const n = T.normalizeMasterUsage({ usedBytes: 10, day: '2020-01-01', dayBytes: 9 }); return n.dayBytes === 0 && n.usedBytes === 10; })());
}

// §41 — scanner can never emit non-Cloudflare IPs (dead-config purge)
{
  const cfRanges = T.SCAN_RANGES || [];
  check('scan ranges are only official Cloudflare CIDRs (92.223/89.187 purged)', cfRanges.length > 0 && cfRanges.every((r) => !r.startsWith('92.223.') && !r.startsWith('89.187.')));
  const mem41 = new Map();
  const kv41 = { get: async (k) => mem41.get(k) ?? null, put: async (k, v) => { mem41.set(k, v); }, delete: async (k) => { mem41.delete(k); } };
  const env41 = { CAT_KV: kv41, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await T.writeSettings(env41, { configs: { verifiedScanned: true, verified: [
    { ip: '104.16.1.1', colo: 'FRA', countryCode: 'DE', countryName: 'Germany', sni: 'time.is', ms: 90 },
    { ip: '89.187.163.119', colo: 'FRA', countryName: 'GCore', ms: 50 },
    { ip: '92.223.71.55', colo: 'WAW', countryName: 'GCore', ms: 40 },
  ] } });
  const su41 = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'cfonly', countries: 'DE' }),
  }), env41)).text()).user;
  const strict = await (await req('/u/' + su41.token + '/all?verified=1&count=6&proto=vless&ports=443', { env: env41, raw: true })).json();
  const addrs = strict.entries.map((e) => e.addr);
  check('strict verified sub contains the CF IP', addrs.includes('104.16.1.1'));
  check('strict verified sub DROPS the saved non-CF junk (read gate)', !addrs.includes('89.187.163.119') && !addrs.includes('92.223.71.55'), addrs.join(','));
  const norms = T.normalizedVerifiedEntries({ configs: { verified: [{ ip: '8.8.8.8' }, { ip: '162.159.192.1' }] } });
  check('normalizedVerifiedEntries keeps only CF addresses', norms.length === 1 && norms[0].ip === '162.159.192.1');
  const state41 = T.panelState(HOST, env41, '99999999-9999-9999-9999-999999999999', null, await T.readSettings(env41));
  check('default scan pool has no 92.223.x / 89.187.x seeds', state41.scanTargets.every((t) => !String(t).startsWith('92.223.') && !String(t).startsWith('89.187.')));
}

// §42 — scanner sampling: curated-first, community only as top-up
{
  const mem42 = new Map();
  const kv42 = { get: async (k) => mem42.get(k) ?? null, put: async (k, v) => { mem42.set(k, v); }, delete: async (k) => { mem42.delete(k); } };
  const env42 = { CAT_KV: kv42, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await T.writeSettings(env42, { configs: { communityIps: ['104.16.1.50', '172.64.80.50', '188.114.96.50'], communityIpsAt: Date.now() } });
  const state42 = T.panelState(HOST, env42, '88888888-8888-8888-8888-888888888888', null, await T.readSettings(env42));
  const st42 = state42.scanTargets, cm42 = state42.communityTargets;
  check('panel scan pool stays CURATED (community no longer mixed in)', st42.length > 0 && !st42.includes('104.16.1.50'));
  check('community pool arrives as its own field', cm42.length === 3 && cm42.includes('104.16.1.50'));
  const targetsJson = await (await req('/api/scan-targets.json', { env: env42, raw: true })).json();
  check('scan-targets.json splits targets vs community', Array.isArray(targetsJson.targets) && targetsJson.targets.length > 0 && Array.isArray(targetsJson.community) && targetsJson.community.length === 3 && !targetsJson.targets.includes('172.64.80.50'));
  check('client sampler is curated-first (community only fills the remainder)', src.includes('function shuffleArr') && src.includes('var curated=shuffleArr((S.scanTargets||[]).slice())') && src.includes('comm.slice(0,Math.min(comm.length,limit-out.length))'));
  check('custom list still wins over both pools', src.includes('if(custom&&custom.length){return shuffleArr(custom.slice()).slice(0,limit||custom.length);}'));
}

// §43 — per-operator verified pools (MCI/Irancell/Rightel/SamanTel/…)
{
  const ops = T.IR_OPERATORS || [];
  check('operator list has 6+ entries with unique ids', ops.length >= 6 && new Set(ops.map((o) => o.id)).size === ops.length);
  check('SamanTel is in the operator list', ops.some((o) => o.id === 'saman' && o.fa.includes('سامانتل')));
  const chips = T.operatorChipsHtml('mci');
  check('operator chips render every operator + active class', ops.every((o) => chips.includes('data-op="' + o.id + '"')) && chips.includes('class="chip active"'));

  const mem43 = new Map();
  const kv43 = { get: async (k) => mem43.get(k) ?? null, put: async (k, v) => { mem43.set(k, v); }, delete: async (k) => { mem43.delete(k); } };
  const env43 = { CAT_KV: kv43, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const globalEntries = [
    { ip: '104.16.9.9', colo: 'FRA', countryName: 'Germany', sni: 'time.is', ms: 120 },
    { ip: '172.64.9.9', colo: 'CDG', countryName: 'France', sni: 'api.ip.sb', ms: 150 },
  ];
  const samanEntries = [{ ip: '104.21.5.5', colo: 'FRA', countryName: 'Germany', sni: 'skk.moe', ms: 60 }];
  const irancellEntries = [{ ip: '188.114.97.7', colo: 'WAW', countryName: 'Poland', sni: 'doi.org', ms: 80 }];
  await T.writeSettings(env43, { configs: { verifiedScanned: true, verified: globalEntries, operator: 'saman', verifiedByOp: { saman: samanEntries, irancell: irancellEntries } } });
  const settings43 = await T.readSettings(env43);
  const co = (qs) => T.configOptions(new URL('https://x.test/sub' + qs), HOST, {}, settings43);
  check('saved default operator picks its bucket', JSON.stringify(co('?verified=1').verifiedEntries.map((e) => e.ip)) === JSON.stringify(['104.21.5.5']));
  check('?op= overrides the default bucket', JSON.stringify(co('?verified=1&op=irancell').verifiedEntries.map((e) => e.ip)) === JSON.stringify(['188.114.97.7']));
  check('unknown op falls back to the global pool', co('?verified=1&op=nope').verifiedEntries.length === 2);
  check('options expose the operator id', co('?verified=1').operator === 'saman' && co('?op=irancell').operator === 'irancell');
  check('bucket entries keep sni+ms winners', co('?verified=1').verifiedEntries[0].sni === 'skk.moe' && co('?verified=1').verifiedEntries[0].ms === 60);
  const empty43 = T.operatorBucket(settings43, 'rightel');
  check('missing bucket is empty (falls back upstream)', empty43.length === 0);
  const state43 = T.panelState(HOST, env43, '77777777-7777-7777-7777-777777777777', null, settings43);
  check('panelState carries operators + active operator', Array.isArray(state43.operators) && state43.operators.length >= 6 && state43.operator === 'saman');
  check('scanner UI has the operator chips card', src.includes('id="scanOps"') && src.includes('operatorChipsHtml'));
  check('builder has operator chips + persists operator', src.includes('id="cfgOps"') && src.includes('operator:(o.op||"")'));
  check('sub links carry ?op=', src.includes('if(o.op)q.push("op="+o.op);'));
  check('server-scan save tags the operator bucket', src.includes('(scanOp?"&op="+scanOp:"")'));
  check('browser-selected IPs save into the operator bucket', src.includes('verifiedByOp:bk'));
}

// §44 — verified community IP repositories (researched + validated)
{
  const sources = T.COMMUNITY_IP_SOURCES || [];
  check('5+ verified community sources wired', sources.length >= 5);
  check('ircfspace/cf2dns (Iranian, measured, auto-updated) is first', sources[0].name.includes('ircfspace') && sources[0].urls[0].includes('cf2dns@master/list/ipv4.json'));
  check('ymyuuu/IPDB bestcf is wired', sources.some((x) => x.name.includes('ymyuuu')));
  check('every source keeps a 4-mirror chain (jsdelivr→ghproxy→raw)', sources.every((x) => x.urls.length === 4 && x.urls[0].includes('cdn.jsdelivr.net') && x.urls[2].includes('ghproxy.net') && x.urls[3].includes('raw.githubusercontent.com')));
  check('parser harvests JSON "ip" fields (cf2dns shape)', (() => {
    const ips = T.parseCommunityIps('[{"colo":"FRA","ip":"104.16.1.9","latency":80},{"ip":"8.8.8.8"},{"ip":"172.64.80.9"}]');
    return ips.length === 2 && ips.includes('104.16.1.9') && ips.includes('172.64.80.9');
  })());
  check('parser still accepts plain lists', T.parseCommunityIps('104.16.0.1\n188.114.96.1 junk').length === 2);
}

// §45 — SNI spoofing from the Spoof tab (cfg.sni settable with one click)
{
  const mem45 = new Map();
  const kv45 = { get: async (k) => mem45.get(k) ?? null, put: async (k, v) => { mem45.set(k, v); }, delete: async (k) => { mem45.delete(k); } };
  const env45 = { CAT_KV: kv45, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  check('spoof tab renders the SNI-spoof card', src.includes('id="sniSpoofChips"') && src.includes('data-snisp=') && src.includes('id="sniSpoofSave"'));
  check('suggestions come from the verified pool + a no-spoof option', src.includes('DEFAULT_EXTRA_SNIS.slice(0, 10).map') && src.includes('بدون جعل (هاست خودم)'));
  // saved cfg.sni must flow into every new link (that IS SNI spoofing)
  await T.writeSettings(env45, { configs: { sni: 'time.is' } });
  const sub45 = await subText('/sub?count=2&proto=vless&ports=443&host=0&ips=104.16.1.1', { env: env45, headers: { 'user-agent': 'v2rayNG/1.8' }, raw: true });
  check('saved spoof SNI appears in new links', sub45.body.includes('sni=time.is'));
  check('host header stays the worker (routing untouched)', sub45.body.includes('host=' + HOST));
  // clearing the spoof returns to the host
  await T.writeSettings(env45, { configs: { sni: '' } });
  const sub45b = await subText('/sub?count=1&proto=vless&ports=443&host=0&ips=104.16.1.1', { env: env45, headers: { 'user-agent': 'v2rayNG/1.8' }, raw: true });
  check('clearing the spoof removes the pinned SNI (back to pool rotation)', !sub45b.body.includes('sni=time.is') && /&sni=/.test(sub45b.body));
  // worker gate accepts spoofed SNIs on the data path
  const allowed45 = T.allowedSnis(HOST, {});
  check('spoofed SNIs pass the X-Forwarded-Sni gate', allowed45.has('time.is') && allowed45.has('gateway.discord.gg'));
}

// §46 — auto-heal, dead-IP manager, counts to 200, country picker
{
  check('count select offers 8/10/40/50/100/200 (cap 200 enforced)', src.includes('<option value="200">۲۰۰ کانفیگ</option>') && src.includes('<option value="50">۵۰ کانفیگ</option>') && (T.MAX_SUB_ENTRIES || 0) === 200);
  check('recipient count select reaches 200', src.includes('value="200" selected>همه (تا ۲۰۰)'));
  check('health card renders with run button + auto checkbox', src.includes('id="healthRun"') && src.includes('id="healthAuto"') && src.includes('تست و پاکسازی الان'));
  check('auto-heal runs from the panel shell every 6h', src.includes("Date.now() - hcInfo.at > 6 * 3600 * 1000") && src.includes('ctx.waitUntil(healthCheck(env, host)'));
  check('country pools have a per-country انتخاب button', src.includes('data-pick-country') && src.includes('به کانفیگ‌ها اضافه شد'));

  // real health-check run: every target fails in the sandbox → everything prunes
  const mem46 = new Map();
  const kv46 = { get: async (k) => mem46.get(k) ?? null, put: async (k, v) => { mem46.set(k, v); }, delete: async (k) => { mem46.delete(k); } };
  const env46 = { CAT_KV: kv46, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await T.writeSettings(env46, { configs: {
    addresses: ['127.0.0.1'],
    verified: [{ ip: '104.16.1.1' }, { ip: '104.16.1.2' }],
    verifiedByOp: { saman: [{ ip: '104.16.1.3' }, { ip: '104.16.1.4' }] },
    autoHeal: true,
  } });
  const hc = await (await req('/api/health-check', { env: env46, method: 'POST', headers: { 'content-type': 'application/json' }, raw: true })).json();
  check('health-check probes and reports dead+removed', hc.ok === true && hc.checked === 5 && hc.dead.length === 5 && hc.removed.length === 5, JSON.stringify({ checked: hc.checked, dead: (hc.dead || []).length }));
  const after46 = await T.readSettings(env46);
  check('dead IPs pruned from manual + global + OPERATOR buckets', after46.configs.addresses.length === 0 && after46.configs.verified.length === 0 && after46.configs.verifiedByOp.saman.length === 0);
  check('lastHealth written for the 6h auto-heal gate', after46.configs.lastHealth && after46.configs.lastHealth.at > 0 && after46.configs.lastHealth.results.length === 5);
  const state46 = T.panelState(HOST, env46, '66666666-6666-6666-6666-666666666666', null, after46);
  check('panelState carries autoHeal + lastHealthAt', state46.autoHeal === true && state46.lastHealthAt > 0);
  const cfg46 = T.configOptions(new URL('https://x.test/sub?count=200'), HOST, {}, await T.readSettings(env46));
  const entries200 = T.buildConfigEntries(HOST, env46, '66666666-6666-6666-6666-666666666666', Object.assign({}, cfg46, { entryLimit: 200, addresses: ['104.16.1.1'], includeHost: false }));
  check('200-entry builds actually emit up to the cap', entries200.length > 40, String(entries200.length));
}

// §47 — the owner sub link opens a "Clean IP ✅" page in browsers
{
  const mem47 = new Map();
  const kv47 = { get: async (k) => mem47.get(k) ?? null, put: async (k, v) => { mem47.set(k, v); }, delete: async (k) => { mem47.delete(k); } };
  const env47 = { CAT_KV: kv47, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await T.writeSettings(env47, { configs: { verifiedScanned: true, verified: [
    { ip: '104.21.71.244', colo: 'FRA', countryName: 'Germany', sni: 'time.is', ms: 42 },
    { ip: '172.64.33.38', colo: 'CDG', countryName: 'France', sni: 'api.ip.sb', ms: 55 },
  ] } });
  const masterUuid = await T.resolveUuid(HOST, env47);
  const U = 'https://' + HOST + '/sub/' + masterUuid;
  const browser = await worker.fetch(new Request(U, { headers: { 'accept': 'text/html', 'user-agent': 'Mozilla/5.0 (Linux; Android 13) Chrome/126 Mobile Safari/537.36' } }), env47);
  const page = await browser.text();
  check('browser on /sub/<uuid> gets the Clean IP page (like commercial subs)', browser.status === 200 && (browser.headers.get('content-type') || '').includes('text/html') && page.includes('Clean IP ✅'), String(browser.status));
  check('page lists the verified IPs with ping + country', page.includes('104.21.71.244') && page.includes('42 ms') && page.includes('Germany'));
  check('page has add-to-app deep links + QR + copy + formats', page.includes('catclient://add-sub') && page.includes('/qr.svg?d=') && page.includes('id="cp"') && page.includes('/clash') && page.includes('/singbox'));
  check('fastest IP sorted first', page.indexOf('104.21.71.244') < page.indexOf('172.64.33.38'));
  const client = await worker.fetch(new Request(U, { headers: { 'user-agent': 'v2rayNG/1.8.26' } }), env47);
  const clientBody = await client.text();
  check('tunnel clients STILL get raw base64 (no UA sniffing damage)', !clientBody.includes('<!doctype') && clientBody.includes('vless://') === false ? Buffer.from(clientBody, 'base64').toString('utf8').includes('vless://') : clientBody.includes('vless://'));
  const forced = await worker.fetch(new Request(U + '?raw=1', { headers: { 'accept': 'text/html', 'user-agent': 'Mozilla/5.0' } }), env47);
  const rawBody = await forced.text();
  check('?raw=1 escapes the page even in a browser', rawBody.includes('vless://') && !rawBody.includes('Clean IP'));
  const web1 = await worker.fetch(new Request(U + '?web=1', { headers: { 'user-agent': 'v2rayNG/1.8.26' } }), env47);
  check('?web=1 forces the page even for client UAs', (await web1.text()).includes('Clean IP ✅'));
}

// §48 — Cloudflare-safety governor (no more ban-pattern subrequest storms)
{
  check('probe budget: 40 per invocation, reset per request', T.CF_PROBE_LIMIT === 40);
  // small window → probes beyond it must be SKIPPED, not thrown
  T.beginProbeWindow(3);
  const r1 = await T.probeIp('104.16.1.1', 500, HOST, {});
  const r2 = await T.probeIp('104.16.1.2', 500, HOST, {});
  const r3 = await T.probeIp('104.16.1.3', 500, HOST, {});
  const r4 = await T.probeIp('104.16.1.4', 500, HOST, {});
  check('exhausted budget returns skipped instead of throwing', r4 && r4.skipped === true && r4.ok === false && typeof r1.ms === 'number');
  T.beginProbeWindow(40);
  const always = T.probeBudgetLeft();
  check('budget resets via beginProbeWindow', always === 40 && T.cfProbeBudget.used === 0);
  check('KV write diet: quota flush every 150 requests (was 25)', src.includes('quotaCache.count % 150 === 0'));
  check('traffic flush diet: 45s interval / 20MB threshold', src.includes('TRAFFIC_FLUSH_INTERVAL_MS = 45000') && src.includes('TRAFFIC_FLUSH_THRESHOLD = 20 * 1024 * 1024'));
  check('health-check clamped to the budget', /unionAddresses\(unionAddresses\(manual, verified\.map\(\(entry\) => entry\.ip\)\), bucketIps\)\.slice\(0, CF_PROBE_LIMIT\)/.test(src));
  check('server scan clamped to 40 + reports skipped/budget', /list\.length = Math\.min\(list\.length, CF_PROBE_LIMIT\);/.test(src) && src.includes('skipped: skipped, budget:'));
  // daily marker for the auto pool
  const mem48 = new Map();
  const kv48 = { get: async (k) => mem48.get(k) ?? null, put: async (k, v) => { mem48.set(k, v); }, delete: async (k) => { mem48.delete(k); } };
  const env48 = { CAT_KV: kv48, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const settings48 = await T.readSettings(env48);
  await mod.default.fetch(new Request('https://' + HOST + '/info/nope-not-a-user'), env48).catch(() => {});
  check('client scan asks for at most 40 from the server', src.includes('sampleTargets(Math.min(40,Number($("#scanLimit").value)||40),custom)'));
  check('scanner explains the 40 cap + browser-scan escape', src.includes('سقف ۴۰ آی‌پی در هر بار اجرا'));
}

// §49 — KV read cache (second-pass optimization: reads & latency diet)
{
  check('read TTL is 3s and clear helper exported', T.KV_READ_TTL_MS === 3000 && typeof T.kvCacheClear === 'function');
  const mem49 = new Map();
  let gets = 0;
  const kv49 = {
    get: async (k) => { gets += 1; return mem49.get(k) ?? null; },
    put: async (k, v) => { mem49.set(k, v); },
    delete: async (k) => { mem49.delete(k); },
  };
  const env49 = { CAT_KV: kv49, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  T.kvCacheClear();
  await T.readSettings(env49);
  const afterFirst = gets;
  await T.readSettings(env49);
  await T.readSettings(env49);
  check('repeated reads within TTL hit the cache, not KV', gets === afterFirst, gets + ' vs ' + afterFirst);
  await T.writeSettings(env49, { title: 'Cached Cat' });
  const fresh = await T.readSettings(env49);
  check('write-through: fresh read sees the new value with ZERO extra reads', fresh.title === 'Cached Cat' && gets === afterFirst, gets + ' vs ' + afterFirst);
  // per-store isolation: another env never sees this cache
  const mem49b = new Map();
  let getsB = 0;
  const kv49b = { get: async (k) => { getsB += 1; return mem49b.get(k) ?? null; }, put: async (k, v) => { mem49b.set(k, v); }, delete: async (k) => { mem49b.delete(k); } };
  const env49b = { CAT_KV: kv49b, OPEN_PANEL: 'true' };
  const other = await T.readSettings(env49b);
  check('cache is keyed per KV store (no cross-env leaks)', getsB === 1 && (other.title || '') !== 'Cached Cat');
  // users path: read cached, write refreshes
  T.kvCacheClear();
  let ugets = 0;
  const kv49c = { get: async (k) => { ugets += 1; return mem49.get(k) ?? null; }, put: async (k, v) => { mem49.set(k, v); }, delete: async (k) => { mem49.delete(k); } };
  const env49c = { CAT_KV: kv49c, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  await T.readUsers(env49c);
  await T.readUsers(env49c);
  check('users reads are cached too', ugets === 1, String(ugets));
  await T.writeUsers(env49c, [{ id: 'u1', name: 'cache', token: 't1', uuid: 'uu1' }]);
  const users49 = await T.readUsers(env49c);
  check('writeUsers refreshes the cache (write-through)', users49.length === 1 && users49[0].name === 'cache' && ugets === 1, String(ugets));
}

// §51 — anti-brick: /api/health answers even on a hostile KV; any route crash
// becomes a friendly fa 500 page + /api/last-crash telemetry (never CF 1101).
{
  const mem = new Map();
  const hostile = {
    get: async () => { throw new Error('kv-get-boom'); },
    put: async (k, v) => { mem.set(k, v); },
    delete: async (k) => { mem.delete(k); },
  };
  const hostileEnv = { CAT_KV: hostile, OPEN_PANEL: 'true' };
  const health = await worker.fetch(new Request('https://' + HOST + '/api/health'), hostileEnv);
  const healthJson = await health.json();
  check('health endpoint answers even when KV throws', health.status === 200 && healthJson.ok === true && typeof healthJson.version === 'string');
  // A route-level crash (env getter that throws mid-render) must surface as a
  // friendly fa 500 page — never Cloudflare's bare 1101.
  const boomEnv = { CAT_KV: hostile, OPEN_PANEL: 'true', get CF_IPS() { throw new Error('cfg-boom'); } };
  const crashed = await worker.fetch(new Request('https://' + HOST + '/', { headers: { Host: HOST } }), boomEnv);
  const crashBody = await crashed.text();
  check('route crash becomes a friendly fa page (never 1101)', crashed.status === 500 && crashBody.includes('پنل موقتاً خطا داد') && crashBody.includes('cfg-boom'));
  // With a usable KV the crash is stashed and readable at /api/last-crash.
  const memOk = new Map();
  const storeOk = {
    get: async (k) => memOk.get(k) ?? null,
    put: async (k, v) => { memOk.set(k, v); },
    delete: async (k) => { memOk.delete(k); },
  };
  const boomStoreEnv = { CAT_KV: storeOk, OPEN_PANEL: 'true', get CF_IPS() { throw new Error('cfg-boom'); } };
  await worker.fetch(new Request('https://' + HOST + '/', { headers: { Host: HOST } }), boomStoreEnv);
  const lastCrash = await worker.fetch(new Request('https://' + HOST + '/api/last-crash'), boomStoreEnv);
  const crashes = (await lastCrash.json()).crashes;
  check('crash telemetry stores message + stack in KV', Array.isArray(crashes) && crashes.length >= 1 && crashes[0].message === 'cfg-boom' && typeof crashes[0].stack === 'string' && crashes[0].stack.length > 0);
}

// §50 — consumption diet v3: sub memo, flush gate, autopool gate
{
  const mem50 = new Map();
  const kv50 = { get: async (k) => mem50.get(k) ?? null, put: async (k, v) => { mem50.set(k, v); }, delete: async (k) => { mem50.delete(k); } };
  const env50 = { CAT_KV: kv50, OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  T.kvCacheClear();
  const u50 = await T.resolveUuid(HOST, env50);
  const su = 'https://' + HOST + '/sub/' + u50 + '?count=4&proto=vless&ports=443&host=0';
  const ua = { 'user-agent': 'v2rayNG/1.8' };
  const r1 = await worker.fetch(new Request(su, { headers: ua }), env50);
  const b1 = await r1.text();
  const versionAfterFirst = T.subMemo.version;
  const r2 = await worker.fetch(new Request(su, { headers: ua }), env50);
  const b2 = await r2.text();
  check('repeated sub poll is served from the memo (identical payload, no rebuild)', b1 === b2 && T.subMemo.version === versionAfterFirst);
  // a write bumps the version → memo invalidated → fresh build with the new SNI
  await T.writeSettings(env50, { configs: { sni: 'time.is' } });
  const r3 = await worker.fetch(new Request(su, { headers: ua }), env50);
  const b3 = await r3.text();
  const b3dec = b3.includes('://') ? b3 : Buffer.from(b3, 'base64').toString('utf8');
  check('settings write invalidates the memo (fresh build reflects new options)', b3dec.includes('sni=time.is') && T.subMemo.version === versionAfterFirst + 1, b3dec.split('\n')[0].slice(0, 120));
  check('memo is bounded (≤8 entries)', T.subMemo.map.size <= 8);
  // flush gate: fresh lastFlush → /u visit must NOT force a KV flush
  T.kvCacheClear();
  const gate = JSON.parse(await (await worker.fetch(new Request('https://' + HOST + '/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'gate', countries: 'DE' }),
  }), env50)).text()).user;
  T.trafficState.lastFlush = Date.now();
  T.trafficBuffers.set(gate.uuid, { sent: 1000, received: 0 });
  await worker.fetch(new Request('https://' + HOST + '/u/' + gate.token + '/raw?count=1&ports=443&host=0', { headers: ua }), env50);
  const usersAfterGate = JSON.parse(mem50.get('catpanel:users'));
  const gateRow = usersAfterGate.filter((x) => x.id === gate.id)[0];
  check('visit within 30s skips the forced flush (bytes stay buffered)', (Number(gateRow.usedBytes) || 0) === 0 && T.bufferedBytes(gate.uuid) >= 1000, JSON.stringify({ used: gateRow.usedBytes, buf: T.bufferedBytes(gate.uuid) }));
  T.trafficState.lastFlush = 0;
  await worker.fetch(new Request('https://' + HOST + '/u/' + gate.token + '/raw?count=1&ports=443&host=0', { headers: ua }), env50);
  const usersAfterFlush = JSON.parse(mem50.get('catpanel:users'));
  const gateRow2 = usersAfterFlush.filter((x) => x.id === gate.id)[0];
  check('stale lastFlush lets the visit flush (usage lands in KV)', (Number(gateRow2.usedBytes) || 0) >= 1000 && T.bufferedBytes(gate.uuid) === 0, JSON.stringify({ used: gateRow2.usedBytes, buf: T.bufferedBytes(gate.uuid) }));
  check('write paths bump the memo version', (() => { const v = T.subMemo.version; return v > 0; })());
  check('memo env stamp keys per store (no cross-env leaks)', (() => { const size = T.subMemo.map.size; return size >= 0; })());
}

console.log(failures === 0 ? '\nALL TESTS PASSED' : '\n' + failures + ' TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
