/**
 * Panel on Vercel (6.69.38+): with env.CAT_PLATFORM = 'vercel' (set by assets/vercel/panel-adapter.mjs)
 * every subscription line must be dialable on Vercel — TLS :443 only, Vercel edge addresses or the
 * project domain, SNI == Host == the project domain, no Cloudflare clean IPs / alt ports / plain :80.
 * The same script without CAT_PLATFORM must keep its Cloudflare behaviour.
 *
 * Usage: node scripts/panels/vercel-platform.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const worker = (await import(workerPath)).default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 300) : '')); }
};

const UUID = '719f3599-09b0-4c6f-9f8d-bd78f0d36c57';
const HOST = 'cat-panel-v7k2m.vercel.app';
const VERCEL = new Set(['76.76.21.21', '64.29.17.65', '216.198.79.65', HOST]);

async function subLines(env, query = '') {
  const res = await worker.fetch(new Request('https://' + HOST + '/sub/' + UUID + query), env, { waitUntil() {} });
  const body = await res.text();
  let plain = body;
  try { const d = Buffer.from(body, 'base64').toString('utf8'); if (/:\/\//.test(d)) plain = d; } catch (_) { }
  return { status: res.status, lines: plain.split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l)) };
}
function parse(line) {
  const u = new URL(line.replace(/^(vless|trojan):/, 'http:'));
  return { host: u.hostname.replace(/^\[|\]$/g, ''), port: Number(u.port || 80), q: u.searchParams };
}

const vercelEnv = { UUID, TROJAN_PASS: 'p-vercel-test', CAT_PLATFORM: 'vercel' };
const v = await subLines(vercelEnv);
check('vercel sub answers 200 with links', v.status === 200 && v.lines.length >= 4, v.status + ' / ' + v.lines.length);
const parsed = v.lines.map(parse);
check('every vercel line is :443', parsed.every((p) => p.port === 443), parsed.map((p) => p.port).join(','));
check('every vercel line is TLS', parsed.every((p) => p.q.get('security') === 'tls'));
check('every vercel address is a Vercel edge address or the project host', parsed.every((p) => VERCEL.has(p.host)), parsed.map((p) => p.host).join(','));
check('Host == project domain on every vercel line', parsed.every((p) => p.q.get('host') === HOST));
// 6.69.41/43: the edge accepts any vercel.app name as SNI (live-tested, even made-up names); vercel.com / nextjs.org → 421
check('every vercel SNI is a *.vercel.app name', parsed.every((p) => /(^|\.)vercel\.app$/i.test(p.q.get('sni') || '')), parsed.map((p) => p.q.get('sni')).join(','));
const fragN = parsed.filter((p) => p.q.get('sni') === 'vercel.app' && p.q.get('fm')).length;
const bareN = parsed.filter((p) => p.q.get('sni') === 'vercel.app' && !p.q.get('fm')).length, exactN = parsed.filter((p) => p.q.get('sni') === HOST).length;
const mixedN = parsed.filter((p) => p.q.get('sni') !== HOST && (p.q.get('sni') || '').toLowerCase() === HOST).length;
const rndSnis = [...new Set(parsed.map((p) => p.q.get('sni')).filter((x) => x !== 'vercel.app' && x.toLowerCase() !== HOST))];
const rndN = parsed.filter((p) => rndSnis.includes(p.q.get('sni'))).length;
check('vercel sub carries the five SNI groups in equal numbers (frag/bare/rnd/Aa/project)', exactN > 0 && [fragN, bareN, rndN, mixedN].every((n) => n === exactN), [fragN, bareN, rndN, mixedN, exactN].join('/'));
check('vercel sub is exactly 5 groups × 3 edge addresses+project × VLESS/Trojan (40 lines)', v.lines.length === 40, v.lines.length);
check('✂️ Frag lines come first and split only the ClientHello', parsed[0].q.get('fm') && parsed.filter((p) => p.q.get('fm')).every((p) => /tlshello/.test(p.q.get('fm')) && /fragment/.test(p.q.get('fm'))));
check('random SNI is one stable *.vercel.app name that hides the project name', rndSnis.length === 1 && /^w[a-z0-9]{6,}\.vercel\.app$/.test(rndSnis[0]) && !rndSnis[0].includes('cat-panel'), rndSnis.join(','));
const again = (await subLines(vercelEnv)).lines.map(parse);
check('random SNI survives a subscription refresh', again.some((p) => p.q.get('sni') === rndSnis[0]));
check('SNI-variant lines are named apart', v.lines.filter((l) => /SNI%20vercel\.app/.test(l)).length === bareN && v.lines.filter((l) => /SNI%20Aa/.test(l)).length === mixedN && v.lines.filter((l) => /SNI%20rnd/.test(l)).length === rndN && v.lines.filter((l) => /Frag/.test(l)).length === fragN);
check('no vercel line uses a non-vercel.app SNI (vercel.com → 421)', !parsed.some((p) => /vercel\.com$/.test(p.q.get('sni') || '')));
check('vercel.com is no longer a default address (it only duplicated 76.76.21.x)', !parsed.some((p) => p.host === 'vercel.com'));
// Custom domain (the strongest option): Host = SNI = the domain, first; the other groups keep the project host.
const domEnv = Object.assign({}, vercelEnv, { CAT_VERCEL_PROD_URL: 'vpn.example.ir' });
const dom = (await subLines(domEnv)).lines.map(parse);
const domLines = dom.filter((p) => p.q.get('sni') === 'vpn.example.ir');
check('custom domain adds a Domain group first with Host = SNI = domain', dom.length === 48 && domLines.length === 8 && dom[0].q.get('sni') === 'vpn.example.ir' && domLines.every((p) => p.q.get('host') === 'vpn.example.ir' && !p.q.get('fm')), dom.length + ' / ' + domLines.length);
check('custom domain leaves the other groups on the project host', dom.filter((p) => p.q.get('sni') !== 'vpn.example.ir').every((p) => p.q.get('host') === HOST));
const domPinned = (await subLines(domEnv, '?sni=vpn.example.ir')).lines.map(parse);
check('?sni=<custom domain> pins Host to it as well', domPinned.length > 0 && domPinned.every((p) => p.q.get('sni') === 'vpn.example.ir' && p.q.get('host') === 'vpn.example.ir'));
const fakeDom = (await subLines(Object.assign({}, vercelEnv, { CAT_VERCEL_PROD_URL: 'other-project.vercel.app' }))).lines;
check('a vercel.app production URL is not treated as a custom domain', fakeDom.length === 40, fakeDom.length);
const clashDom = await (await worker.fetch(new Request('https://' + HOST + '/sub/' + UUID + '?target=clash'), domEnv, { waitUntil() {} })).text();
check('clash export carries the custom Host on Domain proxies', /Host: "vpn\.example\.ir"/.test(clashDom) && /Host: "cat-panel-v7k2m\.vercel\.app"/.test(clashDom));
const sbDom = await (await worker.fetch(new Request('https://' + HOST + '/sub/' + UUID + '?target=singbox'), domEnv, { waitUntil() {} })).text();
check('sing-box export carries the custom Host on Domain outbounds', /"Host": "vpn\.example\.ir"/.test(sbDom) && /"server_name": "vpn\.example\.ir"/.test(sbDom));
const pinned = (await subLines(vercelEnv, '?sni=' + HOST)).lines.map(parse);
check('?sni= pins one SNI (no variants)', pinned.length > 0 && pinned.every((p) => p.q.get('sni') === HOST && p.q.get('host') === HOST) && pinned.length === exactN, pinned.length + ' vs ' + exactN);
const lim = (await subLines(vercelEnv, '?limit=6')).lines;
check('?limit= still caps the vercel sub', lim.length > 0 && lim.length <= 6, lim.length);
check('no ECH / cipher-suite pinning on vercel lines', parsed.every((p) => !p.q.get('ech') && !p.q.get('cs')));
check('vercel sub carries the project host itself', parsed.some((p) => p.host === HOST));
check('vercel sub carries both VLESS and Trojan', v.lines.some((l) => l.startsWith('vless://')) && v.lines.some((l) => l.startsWith('trojan://')));
const ported = (await subLines(vercelEnv, '?port=80,8443,443')).lines.map(parse);
check('?port= cannot re-add non-443 ports on vercel', ported.length > 0 && ported.every((p) => p.port === 443), ported.map((p) => p.port).join(','));

// 6.69.39: no 🎯 ProxyIP / 🧦 / 🧬 duplicates on Vercel (it dials every destination directly).
check('vercel sub has no ProxyIP/SOCKS/SNI-spoof entries', !v.lines.some((l) => /proxyip|pyip|%F0%9F%8E%AF|%F0%9F%A7%A6|%F0%9F%A7%AC/i.test(l)), v.lines.find((l) => /proxyip|%F0%9F%8E%AF/i.test(l)));
// A Cloudflare clean IP can never reach a Vercel project: only Vercel's own networks survive.
const mixed = (await subLines(Object.assign({}, vercelEnv, { CF_IPS: '104.16.1.1,188.114.98.0,76.76.21.99,216.150.16.7' }))).lines.map(parse);
check('vercel keeps scanned Vercel IPs from the list', mixed.some((p) => p.host === '76.76.21.99') && mixed.some((p) => p.host === '216.150.16.7'), mixed.map((p) => p.host).join(','));
check('vercel drops Cloudflare IPs from the list', !mixed.some((p) => p.host === '104.16.1.1' || p.host === '188.114.98.0'));
// Without storage a new user would only live in one instance's memory (the «sub shows 0 configs» bug).
const login = await worker.fetch(new Request('https://' + HOST + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), vercelEnv, { waitUntil() {} });
const cookie = String(login.headers.get('set-cookie') || '').split(';')[0];
check('vercel panel login admin/admin works', login.status === 200 && cookie.includes('='), login.status);
const addUser = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: JSON.stringify({ name: 'ali' }) }), vercelEnv, { waitUntil() {} });
const addBody = await addUser.text();
check('vercel without storage refuses to create a user (409, explains storage)', addUser.status === 409 && /storage/i.test(addBody), addUser.status + ' ' + addBody);
// …and with storage attached the same request creates the user.
const memKv = (() => { const m = new Map(); return { async get(k) { return m.has(k) ? m.get(k) : null; }, async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); }, async list() { return { keys: [...m.keys()].map((name) => ({ name })), list_complete: true }; } }; })();
const storedEnv = Object.assign({}, vercelEnv, { CAT_KV: memKv });
const login2 = await worker.fetch(new Request('https://' + HOST + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), storedEnv, { waitUntil() {} });
const cookie2 = String(login2.headers.get('set-cookie') || '').split(';')[0];
const add2 = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json', cookie: cookie2, origin: 'https://' + HOST }, body: JSON.stringify({ name: 'ali' }) }), storedEnv, { waitUntil() {} });
check('vercel with storage creates the user', add2.status === 201, add2.status + ' ' + (await add2.text()).slice(0, 120));

// 6.69.40: the first-sign-in warning must give Vercel steps on Vercel, not Cloudflare KV bindings.
const vLogin = await (await worker.fetch(new Request('https://' + HOST + '/panel/', { headers: { 'accept-language': 'fa' } }), vercelEnv, { waitUntil() {} })).text();
check('vercel login page explains Vercel Blob + the in-app fix', /Vercel Blob/.test(vLogin) && /همین حالا درستش کن|Fix it now/.test(vLogin), vLogin.slice(0, 120));
check('vercel login page has no Cloudflare KV binding steps', !/Storage &amp; Databases → KV|Bindings → KV namespace/.test(vLogin));
const cfLogin = await (await worker.fetch(new Request('https://cf.example.workers.dev/panel/', { headers: { 'accept-language': 'fa' } }), { UUID, TROJAN_PASS: 'p-cf' }, { waitUntil() {} })).text();
check('cloudflare login page keeps the KV binding steps', /Bindings → KV namespace/.test(cfLogin) && !/Vercel Blob/.test(cfLogin));

// 6.69.44 🟧 Cloudflare front: two lines first (VLESS + Trojan), address = Host = SNI = the front's name.
const frontEnv = Object.assign({}, vercelEnv, { CAT_CF_FRONT: 'https://cat-front.you.workers.dev/' });
const fr = (await subLines(frontEnv)).lines;
const frP = fr.map(parse);
check('CF front adds exactly two lines first (VLESS + Trojan)', fr.length === 42 && fr[0].startsWith('vless://') && fr[1].startsWith('trojan://') && /Cloudflare%20front/.test(fr[0]), fr.length + ' ' + fr[0].slice(0, 90));
check('CF front lines: address = Host = SNI = the front, :443 TLS, no fm/ech', frP.slice(0, 2).every((p) => p.host === 'cat-front.you.workers.dev' && p.port === 443 && p.q.get('security') === 'tls' && p.q.get('host') === 'cat-front.you.workers.dev' && p.q.get('sni') === 'cat-front.you.workers.dev' && !p.q.get('fm') && !p.q.get('ech')));
check('CF front lines keep the panel tunnel paths', frP[0].q.get('path') === parsed.find((p) => p.q.get('path') && p.q.get('path').startsWith('/vl/')).q.get('path'));
check('the other 40 lines are unchanged with a CF front', frP.slice(2).every((p) => p.q.get('host') === HOST));
const frPin = (await subLines(frontEnv, '?sni=cat-front.you.workers.dev')).lines.map(parse);
check('?sni=<front> gives only the CF front lines', frPin.length === 2 && frPin.every((p) => p.host === 'cat-front.you.workers.dev' && p.q.get('host') === 'cat-front.you.workers.dev'), frPin.length);
check('a vercel.app name is never taken as the CF front', (await subLines(Object.assign({}, vercelEnv, { CAT_CF_FRONT: 'x.vercel.app' }))).lines.length === 40);
const clashFront = await (await worker.fetch(new Request('https://' + HOST + '/sub/' + UUID + '?target=clash'), frontEnv, { waitUntil() {} })).text();
check('clash export carries the CF front server/Host', /server: "?cat-front\.you\.workers\.dev"?/.test(clashFront) && /Host: "cat-front\.you\.workers\.dev"/.test(clashFront));
const cfOnly = (await subLines({ UUID, TROJAN_PASS: 'p-cf', CAT_CF_FRONT: 'cat-front.you.workers.dev' })).lines;
check('CF front lines only exist on Vercel panels', !cfOnly.some((l) => /Cloudflare%20front/.test(l)));

// 6.69.43 settings: vercelDomain is sanitised (scheme/path stripped, vercel.app refused).
const putDom = async (v) => (await (await worker.fetch(new Request('https://' + HOST + '/api/settings', { method: 'PUT', headers: { 'content-type': 'application/json', cookie: cookie2, origin: 'https://' + HOST }, body: JSON.stringify({ vercelDomain: v }) }), storedEnv, { waitUntil() {} })).json()).settings.vercelDomain;
check('settings: vercelDomain keeps a real domain (scheme/path stripped)', (await putDom('https://VPN.Example.ir/panel/')) === 'vpn.example.ir');
check('settings: vercelDomain refuses vercel.app names', (await putDom('x.vercel.app')) === '');
check('settings: saved vercelDomain drives the Domain group', ((await putDom('cdn.example.org')) === 'cdn.example.org') && (await subLines(storedEnv)).lines.map(parse).filter((p) => p.q.get('sni') === 'cdn.example.org' && p.q.get('host') === 'cdn.example.org').length === 8);
await putDom('');
const putFront = async (v) => (await (await worker.fetch(new Request('https://' + HOST + '/api/settings', { method: 'PUT', headers: { 'content-type': 'application/json', cookie: cookie2, origin: 'https://' + HOST }, body: JSON.stringify({ vercelCfFront: v }) }), storedEnv, { waitUntil() {} })).json()).settings.vercelCfFront;
check('settings: vercelCfFront keeps a real name and refuses vercel.app', (await putFront('HTTPS://Cat-Front.You.Workers.dev/x')) === 'cat-front.you.workers.dev' && (await putFront('a.vercel.app')) === '');
await putFront('');
const frontJs = await worker.fetch(new Request('https://' + HOST + '/api/vercel-front.js', { headers: { cookie } }), vercelEnv, { waitUntil() {} });
const frontJsText = await frontJs.text();
check('owner gets the front script with this project as origin', frontJs.status === 200 && frontJsText.includes("const DEFAULT_ORIGIN = '" + HOST + "';") && /webSocket/.test(frontJsText));
check('anonymous callers cannot read the front script', (await worker.fetch(new Request('https://' + HOST + '/api/vercel-front.js'), vercelEnv, { waitUntil() {} })).status === 401);
// 6.69.43 /api/vercel-doctor: owner-only; dials each SNI group with a WebSocket upgrade and reports Vercel's answer.
const T0 = (await import(workerPath))._testing;
const dialed = [];
T0.__setSockets({ connect(addr, opts) {
  dialed.push(opts && opts.servername);
  const sni = String((opts && opts.servername) || '').toLowerCase();
  let reqText = '';
  let push;
  const readable = new ReadableStream({ start(c) { push = c; } });
  const writable = new WritableStream({ write(chunk) {
    reqText += new TextDecoder().decode(chunk);
    const hostH = ((reqText.match(/\r\nHost: ([^\r]+)/) || [])[1] || '').toLowerCase();
    const ok = (sni.endsWith('vercel.app') && hostH.endsWith('.vercel.app')) || (sni === hostH);
    push.enqueue(new TextEncoder().encode('HTTP/1.1 ' + (ok ? '101 Switching Protocols' : '421 Misdirected Request') + '\r\n\r\n'));
    push.close();
  } });
  return { opened: Promise.resolve(), readable, writable, close() {} };
} });
const noAuth = await worker.fetch(new Request('https://' + HOST + '/api/vercel-doctor', { method: 'POST', headers: { origin: 'https://' + HOST } }), vercelEnv, { waitUntil() {} });
check('vercel-doctor refuses anonymous callers', noAuth.status === 401, noAuth.status);
const doc = await (await worker.fetch(new Request('https://' + HOST + '/api/vercel-doctor', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: '{}' }), vercelEnv, { waitUntil() {} })).json();
const byId = Object.fromEntries((doc.results || []).map((r) => [r.id, r]));
check('vercel-doctor reports 101 for vercel.app / random / mixed / project SNIs', doc.ok && ['bare', 'rnd', 'mixed', 'project'].every((id) => byId[id] && byId[id].ok && byId[id].status === 101), JSON.stringify(doc).slice(0, 300));
check('vercel-doctor shows the nextjs.org fronting control as 421', byId.front && byId.front.status === 421 && !byId.front.ok);
check('vercel-doctor dials with the planned SNIs', dialed.includes('vercel.app') && dialed.includes('nextjs.org'), dialed.join(','));
const docCf = await worker.fetch(new Request('https://w.example.workers.dev/api/vercel-doctor', { method: 'POST', headers: { origin: 'https://w.example.workers.dev' } }), { UUID, TROJAN_PASS: 'p-cf' }, { waitUntil() {} });
const docFront = await (await worker.fetch(new Request('https://' + HOST + '/api/vercel-doctor', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: '{}' }), frontEnv, { waitUntil() {} })).json();
check('vercel-doctor checks the CF front first (unreachable here → not ok, no crash)', docFront.ok && docFront.front === 'cat-front.you.workers.dev' && docFront.results[0].id === 'cf' && docFront.results[0].ok === false, JSON.stringify(docFront.results && docFront.results[0]));
const realFetch = globalThis.fetch;
globalThis.fetch = async (u) => String(u).startsWith('https://cat-front.you.workers.dev/__cat_front') ? new Response(JSON.stringify({ ok: true, front: 'cat-cf-front', origin: HOST })) : realFetch(u);
const docFront2 = await (await worker.fetch(new Request('https://' + HOST + '/api/vercel-doctor', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: '{}' }), frontEnv, { waitUntil() {} })).json();
check('vercel-doctor: a front that points at this project is ok', docFront2.results[0].id === 'cf' && docFront2.results[0].ok === true, JSON.stringify(docFront2.results[0]));
globalThis.fetch = async (u) => String(u).startsWith('https://cat-front.you.workers.dev/__cat_front') ? new Response(JSON.stringify({ ok: true, front: 'cat-cf-front', origin: 'someone-else.vercel.app' })) : realFetch(u);
const docFront3 = await (await worker.fetch(new Request('https://' + HOST + '/api/vercel-doctor', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: '{}' }), frontEnv, { waitUntil() {} })).json();
check('vercel-doctor: a front pointing at another project is NOT ok', docFront3.results[0].ok === false && docFront3.results[0].origin === 'someone-else.vercel.app');
globalThis.fetch = realFetch;
check('vercel-doctor is not offered on Cloudflare', docCf.status !== 200, docCf.status);
T0.__setSockets(null);

const cf = (await subLines({ UUID, TROJAN_PASS: 'p-cf-test' })).lines.map(parse);
check('cloudflare behaviour unchanged: non-Vercel addresses still present', cf.some((p) => !VERCEL.has(p.host)));
check('cloudflare behaviour unchanged: alternate ports still present', cf.some((p) => p.port !== 443));

if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
// 6.69.41: the panel itself explains the three SNIs and points to the app's real per-line scanner — on Vercel only.
const vPanel = await worker.fetch(new Request('https://' + HOST + '/panel/', { headers: { cookie } }), vercelEnv, { waitUntil() {} });
const vHtml = await vPanel.text();
check('vercel panel shows the SNI + scanner note', /class="note i vnote"/.test(vHtml) && /vercel\.app/.test(vHtml) && /(اسکنر آی‌پی Vercel|Vercel IP scanner)/.test(vHtml), vPanel.status + ' ' + vHtml.slice(0, 80));
const T = (await import(workerPath))._testing;
const cfPanelHtml = T.panelPage({}, { lang: 'fa' }, 'w.example.workers.dev', UUID);
check('cloudflare panel has no Vercel note', /id="v-dash"/.test(cfPanelHtml) && !/class="note i vnote"/.test(cfPanelHtml));
const vPage = T.panelPage({ CAT_PLATFORM: 'vercel' }, { lang: 'fa' }, HOST, UUID);
check('vercel panel has the 🔬 Vercel scanner card (server + my-internet test + custom domain)', /id="vDoctor"/.test(vPage) && /id="vdSrv"/.test(vPage) && /id="vdNet"/.test(vPage) && /id="vdDom"/.test(vPage) && /76\.76\.21\.21/.test(vPage));
check('cloudflare panel has no Vercel scanner card', !/id="vDoctor"/.test(cfPanelHtml));
check('the scanner card script does not break out of its <script>', (vPage.split('id="vDoctor"')[1] || '').split('</script>')[0].indexOf('<script>window.__VD=') >= 0);
check('vercel panelPage carries the note twice (dashboard + scanner)', (T.panelPage({ CAT_PLATFORM: 'vercel' }, { lang: 'en' }, HOST, UUID).match(/class="note i vnote"/g) || []).length === 2);
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all vercel-platform checks passed');
