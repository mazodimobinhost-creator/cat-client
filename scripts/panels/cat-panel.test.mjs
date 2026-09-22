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
function req(url, { headers = {}, env = {}, method = 'GET' } = {}) {
  const r = new Request('https://' + HOST + url, { headers, method });
  return worker.fetch(r, env);
}

// 1. /sub basic
{
  const res = await req('/sub');
  const body = await res.text();
  check('/sub returns 200', res.status === 200);
  check('/sub has vless link', body.includes('vless://'));
  check('/sub has trojan link', body.includes('trojan://'));
  check('/sub has warp link', body.includes('warp://'));
  check('/sub has sni param', body.includes('sni=catpanel-demo.workers.dev'));
  check('/sub has host param', body.includes('host=catpanel-demo.workers.dev'));
  check('/sub subscription-userinfo header', (res.headers.get('subscription-userinfo') || '').includes('total='));
  const m = body.match(/vless:\/\/([0-9a-f-]{36})@/);
  check('/sub contains stable UUID', !!m);
  const res2 = await req('/sub');
  const body2 = await res2.text();
  check('UUID stable across requests', body2.includes('vless://' + m[1] + '@'));
}

// 2. explicit UUID env
{
  const res = await req('/sub', { env: { UUID: '11111111-2222-4333-8444-555555555555' } });
  const body = await res.text();
  check('explicit UUID env respected', body.includes('vless://11111111-2222-4333-8444-555555555555@'));
}

// 3. clean IPs
{
  const res = await req('/sub', { env: { CF_IPS: '104.16.1.1, 172.64.148.100, [2606:4700:4700::1111]' } });
  const body = await res.text();
  check('clean-IP vless variant (v4)', body.includes('@104.16.1.1:443'));
  check('clean-IP trojan variant', body.includes('@104.16.1.1:443') && /trojan:\/\/[0-9a-f-]+@104\.16\.1\.1:443/.test(body));
  check('clean-IP v6 bracketed', body.includes('@[2606:4700:4700::1111]:443'));
  check('clean-IP variants keep sni', (body.match(/sni=catpanel-demo\.workers\.dev/g) || []).length >= 6);
}

// 4. custom SNI
{
  const res = await req('/sub', { env: { SNI: 'my.sni.example' } });
  const body = await res.text();
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
  const a = await req('/sub');
  const b = await req('/sub64');
  const dec = decodeURIComponent(escape(atob(await b.text())));
  check('/sub64 decodes to /sub', dec === (await a.text()));
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
  const j = JSON.parse(await (await req('/api/config.json', { env: { CF_IPS: '1.2.3.4', PANEL_PASSWORD: 'x' } })).text());
  check('/api/config.json is v3', j.version.startsWith('3.'));
  check('/api/config.json exposes doh url', j.dohUrl === 'https://' + HOST + '/dns-query');
  check('/api/config.json flags locked panel', j.panelLocked === true);
  check('/api/config.json embeds scan targets', Array.isArray(j.scanTargets) && j.scanTargets.length > 10);
  const health = JSON.parse(await (await req('/health')).text());
  check('/health reports doh + scanner', health.doh === 'https://' + HOST + '/dns-query' && health.scanTargets > 10);
}

console.log(failures === 0 ? '\nALL TESTS PASSED' : '\n' + failures + ' TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
