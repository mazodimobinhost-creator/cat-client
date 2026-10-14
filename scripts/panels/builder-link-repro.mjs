// Part of `npm test` (it exits non-zero when a variant fails), and runnable on its own:
//   node scripts/panels/builder-link-repro.mjs [CAT_PANEL_WORKER=<path>]
//
// Reproduce the panel's Config Builder («⚡ ساخت ساب زنده») against the real worker.
//
// What the button does in the browser (function bGen()):
//   1. builds a link from CFG.links.sub + query (src/proto/fam/fw/ech/addr/ports/country/strict/limit),
//   2. immediately FETCHES that link,
//   3. shows either the config count, or an error note with a detail string
//      (b_fetch_error / b_bad_reply / b_empty_reply + «HTTP <status>»).
//
// So «when I create a link it shows an error» is reproducible right here: same worker code,
// same URLs, no browser needed. Every variant is fetched through the worker's own fetch
// handler, exactly like the page does it.
//
// THE BUG THIS FOUND (panel 6.67.2, fixed in 6.67.3): the page's link list came from
// `subLinks(origin, masterUuid, …)` — the raw UUID — while /sub/<key> accepts the subscription
// TOKEN once the owner has rotated one (a 6.67.0 feature). So after a rotation the builder
// fetched a URL that the worker itself answered with 404 «not found», and showed the user
// «the subscription is empty» / «the response is not a config subscription: HTTP 404».
// The last section below reproduces exactly that state, which is why it must stay green.
import { readFileSync } from 'node:fs';

const workerPath = process.env.CAT_PANEL_WORKER || new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
const mod = await import(workerPath);
const T = mod._testing;
const worker = mod.default;

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async list() { return { keys: [...this.m.keys()].map((name) => ({ name })) }; }
  async delete(k) { this.m.delete(k); }
}

const HOST = 'catpanel-builder.workers.dev';
const UUID = '11111111-2222-4333-8444-555555555555';
const ENV = { CAT_KV: new FakeKV(), UUID };

const req = (path, init = {}) =>
  worker.fetch(new Request('https://' + HOST + path, init), ENV, { waitUntil() {} });

// ── the same URL builder the page uses (function bUrl) ──────────────────────
function builderLink(kind, B) {
  T.kvCacheClear();
  const u = new URL(`https://${HOST}/${kind === 'sub' ? 'sub' : kind}/${UUID}`);
  const q = new URLSearchParams();
  if (B.src && B.src !== 'mixed') q.set('src', B.src);
  if (B.proto) q.set('proto', B.proto);
  if (B.fam) q.set('fam', B.fam);
  if (B.fw) { q.set('fw', B.fw); q.set('ech', B.ech ? '1' : '0'); } else if (B.ech) q.set('ech', '1');
  if (B.addr && B.addr.trim()) q.set('addr', B.addr.trim());
  const ps = Object.keys(B.ports || {}).filter((k) => B.ports[k]);
  if (ps.length) q.set('ports', ps.join(','));
  if (B.cc) { q.set('country', B.cc); if (B.strict) q.set('strict', '1'); }
  q.set('limit', String(B.limit || 24));
  u.search = q.toString();
  return u.toString();
}

const variants = [
  ['defaults', {}],
  ['isp mtn (ports 443,8443)', { ports: { 443: true, 8443: true }, limit: 24 }],
  ['isp mci (443,2053)', { ports: { 443: true, 2053: true } }],
  ['isp rtl (80,8080)', { ports: { 80: true, 8080: true } }],
  ['fw mci-ech', { fw: 'mci-ech', ech: 1, limit: 12 }],
  ['fw irancell-ff', { fw: 'irancell-ff', ech: 0 }],
  ['fw mci-ipv6', { fw: 'mci-ipv6', ech: 0 }],
  ['proto vless', { proto: 'vless' }],
  ['proto trojan', { proto: 'trojan' }],
  ['fam v6', { fam: 'v6' }],
  ['src ech + ech=1', { src: 'ech', ech: 1 }],
  ['country DE strict', { cc: 'DE', strict: 1 }],
  ['country DE fallback', { cc: 'DE', strict: 0 }],
  ['addr pinned', { addr: '104.16.0.1' }],
  ['limit 200', { limit: 200 }],
];

let bad = 0;
console.log('=== /sub (the link the builder shows first) ===');
for (const [name, B] of variants) {
  const link = builderLink('sub', B);
  const r = await req(link.slice(('https://' + HOST).length));
  const body = await r.text();
  const lines = body.trim() ? body.trim().split('\n').map((x) => x.trim()).filter(Boolean) : [];
  const uris = lines.filter((x) => /^[a-z][a-z0-9+.-]*:\/\//i.test(x));
  const ok = r.status === 200 && uris.length > 0;
  if (!ok) bad++;
  console.log(
    `${ok ? 'ok  ' : 'FAIL'} | ${name.padEnd(24)} | status=${r.status} ct=${(r.headers.get('content-type') || '').split(';')[0]} uris=${uris.length} bytes=${body.length}${ok ? '' : ' | first bytes: ' + JSON.stringify(body.slice(0, 120))}`,
  );
}

// /xray answers a base64 subscription on purpose (v2rayNG pastes the URL, not the JSON):
// the plain-text JSON list is what ?raw=1 returns for a human. A base64 body that does NOT
// decode to a JSON list is the failure mode worth catching — not the base64 itself.
console.log('=== /xray (the second link in the builder) ===');
const asJsonList = (body) => {
  const tryParse = (t) => { try { const v = JSON.parse(t); return Array.isArray(v) ? v : null; } catch { return null; } };
  return tryParse(body.trim()) || tryParse(Buffer.from(body.trim(), 'base64').toString('utf8'));
};
for (const [name, B] of [['xray plain', {}], ['xray fw', { fw: 'mci-ipv6-ff', ech: 0 }]]) {
  const link = builderLink('xray', B);
  const r = await req(link.slice(('https://' + HOST).length));
  const body = await r.text();
  const cfgs = r.status === 200 ? asJsonList(body) : null;
  const raw = await req(link.slice(('https://' + HOST).length) + (link.includes('?') ? '&' : '?') + 'raw=1');
  const rawCfgs = raw.status === 200 ? asJsonList(await raw.text()) : null;
  const ok = !!cfgs && cfgs.length > 0 && !!rawCfgs && rawCfgs.length === cfgs.length;
  if (!ok) bad++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} | ${name.padEnd(24)} | status=${r.status} base64 sub -> ${cfgs ? cfgs.length + ' configs' : 'NOT a JSON list'} | ?raw=1 -> ${rawCfgs ? rawCfgs.length + ' configs' : 'NOT a JSON list'} (${body.length} B)`);
}

console.log('=== ?target= (the same link for every client, BPB-style) ===');
for (const target of ['clash', 'singbox', 'xray', 'base64', 'sub']) {
  const r = await req(`/sub/${UUID}?target=${target}&limit=6`);
  const body = await r.text();
  const ok = r.status === 200 && body.trim().length > 0;
  if (!ok) bad++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} | target=${target.padEnd(8)} | status=${r.status} ct=${(r.headers.get('content-type') || '').split(';')[0]} bytes=${body.length} head=${JSON.stringify(body.trim().slice(0, 48))}`);
}

console.log('=== per-user links (/u/<id>…) as the Users tab hands them out ===');
const created = await req('/api/users', { method: 'POST', headers: { 'content-type': 'application/json', 'x-cat-key': UUID }, body: JSON.stringify({ name: 'repro-user' }) });
const createdJson = await created.json().catch(() => ({}));
const userId = (createdJson.user && createdJson.user.id) || (createdJson.users && createdJson.users.slice(-1)[0] && createdJson.users.slice(-1)[0].id);
if (!userId) { console.log('FAIL | could not create a user:', JSON.stringify(createdJson).slice(0, 160)); bad++; }
else {
  for (const suffix of ['', '/64', '/clash', '/singbox', '/xray']) {
    const r = await req(`/u/${userId}${suffix}`);
    const body = await r.text();
    const ok = r.status === 200 && body.trim().length > 0;
    if (!ok) bad++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} | /u/<id>${suffix.padEnd(9)} | status=${r.status} bytes=${body.length} head=${JSON.stringify(body.trim().slice(0, 44))}`);
  }
}

// The reported state, reproduced on purpose: rotate the subscription token, then fetch the
// links the panel hands out afterwards. A green run means the builder can no longer fetch its
// own link into a 404.
console.log('=== after a subscription-token rotation (the reported error) ===');
{
  const owner = { 'x-cat-key': UUID, 'content-type': 'application/json' };
  const rot = await req('/api/sub-token', { method: 'POST', headers: owner, body: JSON.stringify({ action: 'rotate' }) });
  const rotJson = await rot.json().catch(() => ({}));
  const token = rotJson.token || '';
  T.kvCacheClear();
  const settings = await (await req('/api/settings', { headers: { 'x-cat-key': UUID } })).json();
  const handed = String(((settings.links || {}).sub) || '');
  const handedPath = handed ? new URL(handed).pathname : '';
  const fresh = handed ? await req(handedPath + '?limit=4') : null;
  const freshBody = fresh ? await fresh.text() : '';
  const old = await req('/sub/' + UUID + '?limit=4');
  const ok = rot.status === 200 && /^[0-9a-f]{32}$/.test(token) && !!fresh && fresh.status === 200
    && /^vless:\/\//m.test(freshBody) && old.status === 404;
  if (!ok) bad++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} | !token → ${token ? 'rotated' : 'NOT rotated'} | panel link → ${handedPath} (status ${fresh ? fresh.status : 'n/a'}, ${freshBody.split('\n').filter((l) => l.startsWith('vless://')).length} uri lines) | old uuid link → status ${old.status}`);
  console.log('     (the old uuid link MUST be 404 — that is what a rotation is for — so the panel');
  console.log('      has to hand out, and fetch, the token link instead)');
  // Put it back so a second run starts from the same state.
  await req('/api/sub-token', { method: 'POST', headers: owner, body: JSON.stringify({ action: 'revoke' }) });
  T.kvCacheClear();
}

console.log('=== the other formats the page links to ===');
for (const kind of ['sub64', 'clash', 'singbox']) {
  const r = await req(`/${kind}/${UUID}`);
  const body = await r.text();
  const ok = r.status === 200 && body.trim().length > 0;
  if (!ok) bad++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} | ${kind.padEnd(24)} | status=${r.status} bytes=${body.length}`);
}

console.log(bad === 0 ? '\nALL VARIANTS OK — every link the builder can build is fetched back successfully' : `\n${bad} VARIANT(S) FAILED — reproduce above`);
