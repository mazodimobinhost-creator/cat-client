// Panel 6.80.0 — the four owner requests:
//   🗃️ clean-IP repository (IPv4 / IPv6 / domain / mixed, by operator, a count; the browser tests before adding),
//   🔐 advanced ECH (live ECH names × DNS → one list; every sub config takes the next line; only forms Xray can use),
//   ♻️ automatic usage reset every N days + ☁️ today's Cloudflare requests (warning at 80 %),
//   🔒 optional Reality lines (only when the adapter proved them; never for metered users).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const WORKER = process.env.CAT_PANEL_WORKER || new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
// DoH stub: names with ECH / names on Cloudflare
const ECH_NAMES = new Set(['cloudflare-ech.com', 'crypto.cloudflare.com', 'blog.cloudflare.com']);
const CF_NAMES = new Set(['www.visa.com', 'discord.com', 'medium.com', 'gitlab.com']);
globalThis.fetch = async (input) => {
  const u = new URL(typeof input === 'string' ? input : input.url);
  if (u.pathname === '/dns-query') {
    const name = u.searchParams.get('name');
    const type = u.searchParams.get('type');
    let Answer = [];
    if (type === 'HTTPS' && ECH_NAMES.has(name)) Answer = [{ type: 65, data: '1 . alpn="h2" ech=AEX+DQBB3gAgACCD1sY0' }];
    if (type === 'A') Answer = [{ type: 1, data: CF_NAMES.has(name) ? '104.16.9.9' : '93.184.216.34' }];
    return new Response(JSON.stringify({ Answer }), { headers: { 'content-type': 'application/dns-json' } });
  }
  throw new Error('net-stubbed');
};
const { _testing: T, default: worker } = await import(pathToFileURL(WORKER).href);

class FakeKV { constructor() { this.m = new Map(); } async get(k) { return this.m.has(k) ? this.m.get(k) : null; } async put(k, v) { this.m.set(k, v); } async list() { return { keys: [...this.m.keys()].map((name) => ({ name })) }; } async delete(k) { this.m.delete(k); } }
const MASTER = '11111111-2222-4333-8444-555555555555';
const HOST = 'catpanel-demo.workers.dev';
const DEFAULT_ECH = 'cloudflare-ech.com+udp://1.1.1.1';
function mkEnv(settings, extra) {
  const kv = new FakeKV();
  if (settings) kv.m.set('cat:v6:settings', JSON.stringify(settings));
  return { kv, env: Object.assign({ CAT_KV: kv, UUID: MASTER }, extra || {}) };
}
const call = async (env, p, init = {}) => {
  const r = await worker.fetch(new Request('https://' + HOST + p, Object.assign({ headers: { authorization: 'Bearer admin', 'content-type': 'application/json' } }, init)), env, { waitUntil() {} });
  const t = await r.text();
  try { return { status: r.status, j: JSON.parse(t), t }; } catch (e) { return { status: r.status, j: null, t }; }
};
const isV6 = (s) => /^[0-9a-f:]+$/i.test(s) && s.split(':').length >= 3;

test('ECH list: only forms Xray can query (udp:// or https://IP/dns-query) — tcp:// and named DoH are dropped', () => {
  const got = T.echPoolOf(['a.com+udp://1.1.1.1', 'A.com+https://8.8.8.8/dns-query', 'a.com+udp://[2606:4700:4700::1111]:53', 'a.com+tcp://1.1.1.1', 'a.com+https://dns.google/dns-query', 'nonsense', 'a.com+udp://1.1.1.1']);
  assert.deepEqual(got, ['a.com+udp://1.1.1.1', 'a.com+https://8.8.8.8/dns-query', 'a.com+udp://[2606:4700:4700::1111]:53']);
});

test('ECH list rides the sub round-robin while ECH is on with its default value; a custom ECH value wins', async () => {
  T.kvCacheClear();
  const pool = ['crypto.cloudflare.com+udp://8.8.8.8', 'blog.cloudflare.com+https://1.1.1.1/dns-query'];
  const { env } = mkEnv({ echList: DEFAULT_ECH, echPool: pool });
  const raw = (await call(env, '/sub/' + MASTER + '?raw=1&ech=1')).t;
  const vals = raw.split('\n').filter((l) => /security=tls/.test(l)).map((l) => new URL(l.replace(/^\w+:\/\//, 'http://')).searchParams.get('ech')).filter(Boolean);
  assert.ok(vals.length >= 2, raw.slice(0, 200));
  assert.ok(vals.every((v) => pool.includes(v)), vals.join(' | '));
  assert.ok(pool.every((p) => vals.includes(p)), 'both lines are used');
  T.kvCacheClear();
  const { env: env2 } = mkEnv({ echList: 'ech.example.test+udp://9.9.9.9', echPool: pool });
  const raw2 = (await call(env2, '/sub/' + MASTER + '?raw=1&ech=1')).t;
  assert.ok(raw2.includes('ech=' + encodeURIComponent('ech.example.test+udp://9.9.9.9')) && !raw2.includes(encodeURIComponent(pool[0])));
});

test('/api/ech-pool: names come back only if their HTTPS record has ECH now; DNS list marks DoH-capable resolvers', async () => {
  T.kvCacheClear();
  const { env } = mkEnv(null);
  const r = await call(env, '/api/ech-pool?n=10');
  assert.equal(r.status, 200);
  assert.ok(r.j.domains.length >= 1 && r.j.domains.every((d) => ECH_NAMES.has(d)), JSON.stringify(r.j));
  const d = await call(env, '/api/ech-pool?what=dns&n=10');
  assert.equal(d.j.dns.length, 10);
  for (const x of d.j.dns) assert.equal(x.doh, ['1.1.1.1', '1.0.0.1', '8.8.8.8', '8.8.4.4', '9.9.9.9', '149.112.112.112', '94.140.14.14', '94.140.15.15'].includes(x.ip), x.ip);
  const saved = await call(env, '/api/settings', { method: 'PUT', body: JSON.stringify({ echPool: ['cloudflare-ech.com+udp://8.8.8.8', 'x.com+tcp://1.1.1.1'] }) });
  assert.deepEqual(saved.j.settings.echPool, ['cloudflare-ech.com+udp://8.8.8.8'], 'saved list is cleaned');
  const anon = await worker.fetch(new Request('https://' + HOST + '/api/ech-pool'), env, { waitUntil() {} });
  assert.equal(anon.status, 401);
});

test('/api/ip-repo: IPv4 / IPv6 / domain / mixed by operator — every candidate on :443, domains only if on Cloudflare', async () => {
  T.kvCacheClear();
  const { env } = mkEnv(null);
  const r = await call(env, '/api/ip-repo?kinds=v4,v6,dom&op=mtn&n=6');
  assert.equal(r.status, 200);
  const c = r.j.cands;
  assert.ok(c.length >= 6, c.length);
  assert.deepEqual(c.slice(0, 3).map((x) => x.k).sort(), ['dom', 'v4', 'v6'], 'mixed = interleaved');
  for (const x of c) {
    assert.ok(/:443$/.test(x.a), x.a);
    if (x.k === 'v4') assert.ok(/^188\.114\.9[6-9]\.\d{1,3}:443$/.test(x.a), 'Irancell ranges: ' + x.a);
    if (x.k === 'v6') { const m = /^\[([0-9a-f:]+)\]:443$/.exec(x.a); assert.ok(m && isV6(m[1]), x.a); }
    if (x.k === 'dom') assert.ok(CF_NAMES.has(x.a.replace(/:443$/, '')), 'only CF names: ' + x.a);
  }
  const v6 = await call(env, '/api/ip-repo?kinds=v6&op=mci&n=4');
  assert.ok(v6.j.cands.length && v6.j.cands.every((x) => x.k === 'v6' && /^\[2(a06:98c1|606:4700):/.test(x.a)), JSON.stringify(v6.j.cands));
  for (let i = 0; i < 50; i++) { const ip = T.genV4('104.16'); const p = ip.split('.').map(Number); assert.ok(p.length === 4 && p.every((n) => n >= 0 && n <= 255) && p[3] >= 1 && p[3] <= 254, ip); }
  // the page adds exactly what answered, on that port
  const add = await call(env, '/api/ips', { method: 'POST', body: JSON.stringify({ ips: ['188.114.97.7:443', '[2606:4700:3030::6815:30c9]:443', 'www.visa.com:443'], replace: false }) });
  assert.equal(add.status, 200, add.t);
  const st = await call(env, '/api/settings');
  for (const a of ['188.114.97.7:443', '[2606:4700:3030::6815:30c9]:443', 'www.visa.com:443']) assert.ok(st.j.settings.ips.includes(a), a + ' in ' + JSON.stringify(st.j.settings.ips));
});

test('♻️ resetDays: kept 0..365, saved by the users API, and the meter starts again once the period is over', async () => {
  assert.equal(T.normalizeUser({ id: MASTER.replace('1111', '2222'), resetDays: 999 }).resetDays, 365);
  assert.equal(T.normalizeUser({ id: MASTER.replace('1111', '2222'), resetDays: -3 }).resetDays, 0);
  T.kvCacheClear();
  const { env, kv } = mkEnv(null);
  const mk = await call(env, '/api/users', { method: 'POST', body: JSON.stringify({ name: 'monthly', days: 60, quotaGB: 10, resetDays: 30 }) });
  const u = mk.j.user || mk.j;
  assert.equal(u.resetDays, 30, mk.t);
  const old = Date.now() - 31 * 86400000;
  await kv.put('traffic:' + u.id, JSON.stringify({ up: 5e9, down: 4e9, since: old }));
  const t = await T.trafficOf(env, Object.assign({}, u), true);
  assert.equal(t.up + t.down, 0);
  assert.equal(JSON.parse(kv.m.get('traffic:' + u.id)).up, 0, 'one KV write: the meter is zero');
  await kv.put('traffic:' + u.id, JSON.stringify({ up: 7, down: 3, since: Date.now() - 2 * 86400000 }));
  assert.equal((await T.trafficOf(env, Object.assign({}, u), true)).up, 7, 'not due yet → untouched');
  await kv.put('traffic:' + u.id, JSON.stringify({ up: 7, down: 3, since: old }));
  assert.equal((await T.trafficOf(env, Object.assign({}, u, { resetDays: 0 }), true)).up, 7, 'resetDays 0 → never');
});

test('☁️ /api/cf-quota: this panel\'s own count when no token can read analytics (marked local), n/a off Cloudflare', async () => {
  T.kvCacheClear();
  const { env } = mkEnv(null);
  const r = await call(env, '/api/cf-quota?fresh=1');
  assert.equal(r.status, 200);
  assert.equal(r.j.src, 'local');
  assert.equal(r.j.limit, 100000);
  assert.ok(r.j.pct >= 0 && r.j.pct <= 100 && /^\d{4}-\d{2}-\d{2}$/.test(r.j.day), r.t);
  const n = await call(mkEnv(null, { CAT_PLATFORM: 'node' }).env, '/api/cf-quota');
  assert.equal(n.j.na, true);
});

test('🔒 Reality lines: only from a proven adapter, only on a direct port, never for a metered user or a pinned link', () => {
  const ENV = { CAT_PLATFORM: 'node', CAT_NODE_EDGE: 'plain', CAT_NODE_PORT: '25565' };
  const st = { allowed: true, on: true, status: 'running', verified: true, pub: 'A'.repeat(43), sid: '0a1b2c3d', profiles: [{ id: 'all', sni: 'www.speedtest.net' }, { id: 'mtn', sni: 'www.microsoft.com' }] };
  globalThis.__catReality = { status: () => st, sync: async () => true, allowed: () => true };
  try {
    const l = T.realityLines('51.77.1.2', ENV, MASTER, null, {});
    assert.equal(l.length, 2);
    const u = new URL(l[1].replace(/^vless:\/\//, 'http://'));
    assert.equal(u.username, MASTER);
    assert.equal(u.hostname, '51.77.1.2');
    assert.equal(u.port, '25565');
    for (const [k, v] of Object.entries({ encryption: 'none', flow: 'xtls-rprx-vision', security: 'reality', sni: 'www.microsoft.com', fp: 'chrome', pbk: 'A'.repeat(43), sid: '0a1b2c3d', type: 'tcp' })) assert.equal(u.searchParams.get(k), v, k);
    assert.ok(decodeURIComponent(u.hash).includes('Reality · Irancell'));
    assert.equal(T.realityLines('51.77.1.2', ENV, MASTER, { quotaBytes: 1 }, {}).length, 0, 'metered user');
    assert.equal(T.realityLines('51.77.1.2', ENV, MASTER, null, { addr: ['1.2.3.4'] }).length, 0, 'pinned link');
    assert.equal(T.realityLines('51.77.1.2', Object.assign({}, ENV, { CAT_NODE_EDGE: 'tls' }), MASTER, null, {}).length, 0, 'behind a TLS proxy');
    st.verified = false;
    assert.equal(T.realityLines('51.77.1.2', ENV, MASTER, null, {}).length, 0, 'not proven');
  } finally { delete globalThis.__catReality; }
  assert.equal(T.realityLines('51.77.1.2', ENV, MASTER, null, {}).length, 0, 'no adapter (Cloudflare / Vercel)');
});
