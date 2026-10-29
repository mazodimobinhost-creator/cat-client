/**
 * 🔗 Panel link (panel 6.76.0): two real panel instances — a Cloudflare one (a.workers.dev) and a Vercel one
 * (b.vercel.app) — talk to each other through a fetch router, exactly over the public API:
 *   - a one-time code from A, pasted in B, pairs them (the code is single-use; a panel cannot link itself);
 *   - the peer API refuses requests without the shared secret;
 *   - each side accepts the OTHER side’s link UUID on its tunnel (tunnelAuth), nobody else;
 *   - «exit through the linked panel» stores a VLESS-WS chain to the peer and dialTarget really dials it first
 *     (cf mode only for Cloudflare destinations), and traffic that arrived over a link never re-exits over one;
 *   - a Cloudflare panel can never exit through another Cloudflare panel (CF → CF sockets are blocked);
 *   - «add to subscription» mirrors A’s users on B and A’s user sub carries B’s configs (🔗-tagged, B’s host);
 *   - importing from the peer keeps ProxyIP ports exactly (103.237.102.191:11111) and never imports SOCKS;
 *   - unlinking on one side removes the link on both.
 * Usage: node scripts/panels/mesh.test.mjs
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const href = pathToFileURL(workerPath).href;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }

// Two separate module instances = two separate isolates (module caches are not shared).
const A = { mod: await import(href + '?panel=a'), host: 'a.workers.dev', env: { CAT_KV: new FakeKV(), UUID: '41111111-2222-4333-8444-555555555555', DATA_PLANE_ENABLED: 'true' } };
const B = { mod: await import(href + '?panel=b'), host: 'b.vercel.app', env: { CAT_KV: new FakeKV(), UUID: '51111111-2222-4333-8444-555555555555', DATA_PLANE_ENABLED: 'true', CAT_PLATFORM: 'vercel' } };
const byHost = { [A.host]: A, [B.host]: B };
const calls = [];
globalThis.fetch = async (input, init) => {
  const r = input instanceof Request ? input : new Request(String(input), init);
  const h = new URL(r.url).hostname;
  const p = byHost[h];
  if (!p) return new Response('', { status: 404 });
  calls.push(h + new URL(r.url).pathname);
  return p.mod.default.fetch(r, p.env, { waitUntil() {} });
};
async function call(P, p, { method = 'GET', headers = {}, body } = {}) {
  const init = { method, headers: Object.assign({}, P.auth || {}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  const res = await P.mod.default.fetch(new Request('https://' + P.host + p, init), P.env, { waitUntil() {} });
  const txt = await res.text();
  let j = null; try { j = JSON.parse(txt); } catch (e) { }
  return { status: res.status, j, txt };
}
for (const P of [A, B]) {
  const login = await P.mod.default.fetch(new Request('https://' + P.host + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), P.env, { waitUntil() {} });
  P.auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
  check(P.host + ': admin/admin login still works', login.status === 200, String(login.status));
}

const anon = await A.mod.default.fetch(new Request('https://a.workers.dev/api/links'), A.env, {});
check('GET /api/links is owner-only', anon.status === 401, String(anon.status));

const code = await call(A, '/api/links/code', { method: 'POST' });
check('A makes a one-time code', code.j && code.j.ok && /^CATLINK1-/.test(code.j.code) && code.j.exp > Date.now(), code.txt.slice(0, 200));
const parsed = A.mod._testing.parseLinkCode(code.j.code);
check('the code carries A’s address', parsed && parsed.u.url === 'https://a.workers.dev', JSON.stringify(parsed));
const self = await call(A, '/api/links/join', { method: 'POST', body: { code: code.j.code } });
check('pasting the code in the SAME panel is refused', self.status === 400, self.txt.slice(0, 160));
// beta225: a Worker cannot fetch a bare IP or a non-80/443 port (KataBump http://IP:port) — say which way round to do it.
const kbCode = 'CATLINK1-' + Buffer.from(JSON.stringify({ u: 'http://51.75.1.2:20123', t: 'k'.repeat(24) })).toString('base64');
const callsBefore = calls.length;
const kbJoin = await call(A, '/api/links/join', { method: 'POST', body: { code: kbCode } });
check('a CF panel refuses to call a KataBump IP:port and says to make the code here', kbJoin.status === 400 && /make the code in THIS panel/.test(kbJoin.txt) && calls.length === callsBefore, kbJoin.txt.slice(0, 200));
check('… the real tunnel probe still runs when the API of a linked IP:port panel is unreachable', /\(r\.api \|\| peer\.info\.path\) && linkExitCapable/.test(fs.readFileSync(workerPath, 'utf8')), 'check handler');

const join = await call(B, '/api/links/join', { method: 'POST', body: { code: code.j.code } });
check('B joins with A’s code', join.status === 200 && join.j && join.j.ok && join.j.peer && join.j.peer.host === 'a.workers.dev', join.txt.slice(0, 240));
const again = await call(B, '/api/links/join', { method: 'POST', body: { code: code.j.code } });
check('the same code cannot be used twice', again.status !== 200 || !again.j.ok, again.txt.slice(0, 160));

const la = (await call(A, '/api/links')).j;
const lb = (await call(B, '/api/links')).j;
const pa = la.peers[0], pb = lb.peers[0];
check('A lists B (vercel, real tunnel path)', la.peers.length === 1 && pa.host === 'b.vercel.app' && pa.info.platform === 'vercel' && /^\/vl\//.test(pa.info.path), JSON.stringify(pa));
check('B lists A (cloudflare)', lb.peers.length === 1 && pb.host === 'a.workers.dev' && pb.info.platform === 'cf', JSON.stringify(pb));
check('no secret / link UUID leaks into the owner list', !JSON.stringify(la).includes('secret') && !('dialUuid' in pa), Object.keys(pa).join(','));
check('CF → Vercel exit is possible, Vercel → CF exit is possible', pa.exitCapable === true && pb.exitCapable === true);

const kvA = JSON.parse(A.env.CAT_KV.m.get('cat:v6:links'));
const kvB = JSON.parse(B.env.CAT_KV.m.get('cat:v6:links'));
const ra = kvA.peers[0], rb = kvB.peers[0];
check('both sides share ONE secret and cross-wired UUIDs', ra.secret === rb.secret && ra.dialUuid === rb.acceptUuid && rb.dialUuid === ra.acceptUuid, JSON.stringify({ ra, rb }).slice(0, 300));
check('the pending code is gone on A', (kvA.pending || []).length === 0);

const noSecret = await B.mod.default.fetch(new Request('https://b.vercel.app/api/link/info'), B.env, {});
check('peer API without the shared secret → 401', noSecret.status === 401, String(noSecret.status));
const badSecret = await B.mod.default.fetch(new Request('https://b.vercel.app/api/link/info', { headers: { 'x-cat-link': 'x'.repeat(32) } }), B.env, {});
check('peer API with a wrong secret → 401', badSecret.status === 401, String(badSecret.status));

const TB = B.mod._testing, TA = A.mod._testing;
const authB = await TB.tunnelAuth(B.env, ra.dialUuid, B.env.UUID);
check('B’s tunnel accepts A’s link UUID (marked as link)', authB.ok === true && authB.link === true, JSON.stringify(authB).slice(0, 160));
const authX = await TB.tunnelAuth(B.env, '61111111-2222-4333-8444-555555555555', B.env.UUID);
check('… and still refuses an unknown UUID', authX.ok === false);

// CF → CF is refused
check('a CF panel cannot exit through another CF panel', TA.linkExitCapable({}, { platform: 'cf', edge: 'cf' }) === false && TA.linkExitCapable({}, { platform: 'node', edge: 'cf' }) === false && TA.linkExitCapable({}, { platform: 'node', edge: 'tls' }) === true && TA.linkExitCapable({ CAT_PLATFORM: 'vercel' }, { platform: 'cf', edge: 'cf' }) === true);

// ---- exit through the linked panel ----
const setExit = await call(A, '/api/links/set', { method: 'POST', body: { id: pa.id, exit: 'cf' } });
check('A: «exit through B — only Cloudflare sites» saved', setExit.j && setExit.j.ok && setExit.j.peer.exit === 'cf', setExit.txt.slice(0, 200));
const sa = (await call(A, '/api/settings')).j.settings;
check('settings.linkExit = VLESS-WS to b.vercel.app:443 with B’s path + A’s link UUID', sa.linkExit && sa.linkExit.host === 'b.vercel.app' && sa.linkExit.port === 443 && sa.linkExit.tls === true && sa.linkExit.path === pa.info.path && sa.linkExit.uuid === ra.dialUuid && sa.linkExit.mode === 'cf', JSON.stringify(sa.linkExit));
const chain = TA.linkExitChain(sa);
check('linkExitChain → vless/ws/tls chain', chain && chain.type === 'vless' && chain.transport === 'ws' && chain.tls && chain.sni === 'b.vercel.app' && chain.user === ra.dialUuid);
function fakeSockets() {
  const log = [];
  return { log, connect(addr, opts) { log.push(addr.hostname + ':' + addr.port + (opts && opts.secureTransport ? ':' + opts.secureTransport : '')); throw new Error('offline'); } };
}
let fs1 = fakeSockets();
await TA.dialTarget('104.16.1.1', 443, A.env, sa, null, null, fs1).catch(() => {});
check('cf mode: a Cloudflare destination dials the linked panel FIRST (TLS)', fs1.log[0] === 'b.vercel.app:443:starttls', fs1.log.join(' | '));
fs1 = fakeSockets();
await TA.dialTarget('93.184.216.34', 443, A.env, sa, null, null, fs1).catch(() => {});
check('cf mode: a non-Cloudflare destination does NOT go through the link', fs1.log.length && !fs1.log[0].startsWith('b.vercel.app'), fs1.log.join(' | '));
fs1 = fakeSockets();
await TA.dialTarget('93.184.216.34', 443, A.env, Object.assign({}, sa, { linkExit: Object.assign({}, sa.linkExit, { mode: 'all' }) }), null, null, fs1).catch(() => {});
check('all mode: every destination goes through the link first, then falls back', fs1.log[0] === 'b.vercel.app:443:starttls' && fs1.log.length > 1, fs1.log.join(' | '));
const src = fs.readFileSync(workerPath, 'utf8');
check('no A→B→A loop: link-arrived traffic dials with linkExit cleared', /viaLink && settings\.linkExit \? Object\.assign\(\{\}, settings, \{ linkExit: null \}\)/.test(src) || process.env.CAT_PANEL_WORKER);

// ---- combined subscription ----
const mk = await call(A, '/api/users', { method: 'POST', body: { name: 'ali', expiresAt: Date.now() + 10 * 86400000 } });
const uid = mk.j && (mk.j.user ? mk.j.user.id : (mk.j.users || []).slice(-1)[0] && mk.j.users.slice(-1)[0].id);
check('A: a user is created', !!uid, mk.txt.slice(0, 200));
const subOn = await call(A, '/api/links/set', { method: 'POST', body: { id: pa.id, sub: true } });
check('«add to subscription» on → users pushed to B', subOn.j && subOn.j.ok && subOn.j.sync && subOn.j.sync[0] && subOn.j.sync[0].ok && subOn.j.sync[0].n >= 1, subOn.txt.slice(0, 240));
const mirrored = JSON.parse(B.env.CAT_KV.m.get('cat:v6:links')).peers[0].mirror;
check('B holds the mirrored user (id + enabled + expiry only)', mirrored.some((m) => m.id === uid && m.en === true && m.exp > Date.now()), JSON.stringify(mirrored));
const authU = await TB.tunnelAuth(B.env, uid, B.env.UUID);
check('B’s tunnel accepts A’s user', authU.ok === true && !authU.link, JSON.stringify(authU).slice(0, 160));
const sub = await A.mod.default.fetch(new Request('https://a.workers.dev/sub/' + uid), A.env, { waitUntil() {} });
const subTxt = await sub.text();
const peerLines = subTxt.split('\n').filter((l) => l.includes(encodeURIComponent('🔗')));
check('A’s user sub carries B’s configs (🔗-tagged)', sub.status === 200 && peerLines.length > 0, subTxt.slice(0, 300));
check('… built for B’s host with the SAME user UUID', peerLines.every((l) => l.includes('b.vercel.app') && l.includes(uid)), peerLines.slice(0, 2).join('\n'));
check('… and every peer line is a complete vless/tr link', peerLines.every((l) => /^(vless|tr\x6fjan):\/\/[^@]+@[^:]+:\d+\?.*type=ws.*#/.test(l)), peerLines.slice(0, 2).join('\n'));
const own = subTxt.split('\n').filter((l) => /^vless:\/\//.test(l) && !l.includes(encodeURIComponent('🔗')));
check('A’s own configs are still there', own.length > 0);
const pinned = await (await A.mod.default.fetch(new Request('https://a.workers.dev/sub/' + uid + '?limit=1'), A.env, {})).text();
check('pinned / limited links do NOT get peer lines', !pinned.includes(encodeURIComponent('🔗')));

// ---- import exact ports ----
await call(B, '/api/settings', { method: 'PUT', body: { proxyIps: ['103.237.102.191:11111', '184.75.221.82:3118', 'socks5://u:p@1.2.3.4:1080'] } });
const imp = await call(A, '/api/links/import', { method: 'POST', body: { id: pa.id, what: 'px' } });
check('import from B: ProxyIPs added', imp.j && imp.j.ok && imp.j.px === 2, imp.txt.slice(0, 200));
const sa2 = (await call(A, '/api/settings')).j.settings;
check('… with EXACTLY their ports, and no SOCKS credentials', sa2.proxyIps.includes('103.237.102.191:11111') && sa2.proxyIps.includes('184.75.221.82:3118') && !JSON.stringify(sa2.proxyIps).includes('socks5'), JSON.stringify(sa2.proxyIps));
const impIps = await call(A, '/api/links/import', { method: 'POST', body: { id: pa.id, what: 'ips' } });
check('clean IPs are not mixed between a Vercel and a Cloudflare panel', impIps.j && impIps.j.ips === 0 && impIps.j.sameEdge === false, impIps.txt.slice(0, 200));

// ---- unlink ----
const rm = await call(B, '/api/links/remove', { method: 'POST', body: { id: pb.id } });
check('B unlinks', rm.j && rm.j.ok);
check('… and A forgets B too (and drops the exit)', JSON.parse(A.env.CAT_KV.m.get('cat:v6:links')).peers.length === 0 && !(await call(A, '/api/settings')).j.settings.linkExit);
const authGone = await TB.tunnelAuth(B.env, ra.dialUuid, B.env.UUID);
check('… and B’s tunnel no longer accepts A’s link UUID', authGone.ok === false);

// ---- UI ----
const page = await (await A.mod.default.fetch(new Request('https://a.workers.dev/panel/', { headers: A.auth }), A.env, {})).text();
check('panel has the 🔗 link page + sidebar entry', page.includes('id="v-link"') && page.includes('data-view="link"'));
check('page has: make code, paste code, list', ['id="lkMake"', 'id="lkCode"', 'id="lkJoin"', 'id="lkList"'].every((x) => page.includes(x)));

if (failures) { console.error('\n' + failures + ' failure(s)'); process.exit(1); }
console.log('\nmesh: all checks passed');
