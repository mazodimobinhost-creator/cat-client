/**
 * ProxyIP validation + socks5 credential safety.
 *
 * Every case here is a bug that was reported against a shipped file, so each
 * one is pinned separately instead of being folded into a generic test:
 *   1. «-bad.com:443» used to be stored as «-bad.com:443:443» (the invalid host
 *      was not recognised as host:port, so the default port was appended to the
 *      whole token). A hostname label must not start or end with a hyphen.
 *   2. «[2606:4700::6812:1a2e]:443» and «2606:4700::6812:1a2e» are the same
 *      relay: dedupe has to happen AFTER the default port is pinned, not before.
 *   3. A socks5 relay's username/password must never appear in a subscription.
 *
 * Run standalone:  node scripts/panels/proxyip-validation.test.mjs
 */
import { strict as assert } from 'node:assert';
import { _testing as T } from '../../app/src/main/assets/panels/catclient.worker.js';

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

console.log('\nProxyIP validation + socks5 credential safety\n');

/* ---------- 1) hostname labels: no leading or trailing hyphen ---------- */
ok('isProxyHostName rejects a leading hyphen', T.isProxyHostName('-bad.com') === false);
ok('isProxyHostName rejects a trailing hyphen', T.isProxyHostName('bad-.com') === false);
ok('isProxyHostName rejects a bare hyphen label', T.isProxyHostName('-') === false);
ok('isProxyHostName accepts a normal hostname', T.isProxyHostName('ok.example.com') === true);
ok('isProxyHostName accepts a hyphen inside a label', T.isProxyHostName('my-proxy.example.com') === true);

{
  const r = T.normalizeProxyList('-bad.com:443');
  ok('«-bad.com:443» is REJECTED, not stored', r.list.length === 0, JSON.stringify(r.list));
  ok('…and it is reported as ignored, not silently dropped', r.ignored.includes('-bad.com:443'), JSON.stringify(r.ignored));
  ok('…and it is NEVER mangled into «-bad.com:443:443»', !r.list.some((e) => e.includes(':443:443')));
}
{
  const r = T.normalizeProxyList('bad-.com:443');
  ok('«bad-.com:443» is rejected too', r.list.length === 0, JSON.stringify(r.list));
}
{
  const r = T.normalizeProxyList('-bad.com');
  ok('a portless «-bad.com» is rejected instead of becoming «-bad.com:443»', r.list.length === 0, JSON.stringify(r.list));
}
{
  const r = T.normalizeProxyList('ok.example.com:443 1.2.3.4 1.2.3.4:8443');
  ok('only valid explicit host:port endpoints survive the validator', r.list.length === 2, JSON.stringify(r.list));
  ok('valid hosts keep their own port', r.list.includes('1.2.3.4:8443') && r.list.includes('ok.example.com:443'), JSON.stringify(r.list));
}

/* ---------- 2) IPv6: an explicit port is mandatory ---------- */
const V6 = '2606:4700::6812:1a2e';
ok('bare IPv6 is rejected rather than silently pinned to 443', T.normalizeProxyList(V6).list.length === 0, JSON.stringify(T.normalizeProxyList(V6).list));
ok('bracketed IPv6 keeps its port', T.normalizeProxyList('[' + V6 + ']:443').list[0] === '[' + V6 + ']:443');
{
  const r = T.normalizeProxyList('[' + V6 + ']:443 ' + V6);
  ok('only the explicit IPv6 endpoint survives', r.list.length === 1, JSON.stringify(r.list));
  ok('…and the stored form is the bracketed one', r.list[0] === '[' + V6 + ']:443', JSON.stringify(r.list));
}
{
  const r = T.normalizeProxyList(V6 + ' [' + V6 + ']:8443');
  ok('portless IPv6 is rejected while its explicit 8443 endpoint survives', r.list.length === 1 && r.list[0] === '[' + V6 + ']:8443', JSON.stringify(r.list));
}

/* ---------- 3) socks5 credentials must not reach a subscription ---------- */
const UUID = '11111111-2222-4333-8444-555555555555';
const mkKv = () => { const m = new Map(); return { get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => { m.set(k, String(v)); }, delete: async (k) => { m.delete(k); } }; };
const SETTINGS_KEY = (T.KV_KEYS && T.KV_KEYS.settings) || 'cat_settings_v1';
const RELAY = 'socks5://alice:s3cret@1.2.3.4:1080';

{
  const env = { CAT_KV: mkKv(), UUID, OPEN_SUB: 'true', OPEN_PANEL: 'true' };
  await env.CAT_KV.put(SETTINGS_KEY, JSON.stringify({ ips: ['104.16.0.1'], proxyIps: [RELAY], protocols: ['vless'], tlsPorts: ['443'] }));
  for (const p of ['/sub/' + UUID, '/clash/' + UUID, '/singbox/' + UUID]) {
    const t = await (await T.handleRequest(new Request('https://x.workers.dev' + p), env, { waitUntil() { } })).text();
    ok(p + ' carries no username', !t.includes('alice'));
    ok(p + ' carries no password', !t.includes('s3cret'));
    ok(p + ' carries no socks5 URL', !t.includes('socks5://'));
  }
  const sub = await (await T.handleRequest(new Request('https://x.workers.dev/sub/' + UUID), env, { waitUntil() { } })).text();
  ok('the link carries an opaque relay reference instead', /relay(%253A|%3A|:)[0-9a-f]{16}/i.test(sub), sub.slice(0, 200));
}

/* ---------- the reference resolves server-side, from the panel's settings ---------- */
{
  const settings = { proxyIps: [RELAY, '1.2.3.4:443'] };
  const tok = T.relayTokenFor(RELAY);
  ok('the token is an opaque reference, not the URL', /^relay:[0-9a-f]{16}$/.test(tok), tok);
  ok('the token contains no credential', !tok.includes('s3cret') && !tok.includes('alice'));
  ok('the worker resolves it back to the real relay', T.resolveRelayToken(settings, tok) === RELAY, T.resolveRelayToken(settings, tok));
  ok('an unknown reference resolves to nothing (never a guess)', T.resolveRelayToken(settings, 'relay:deadbeefdeadbeef') === '');
  ok('a plain ProxyIP passes through untouched', T.resolveRelayToken(settings, '1.2.3.4:443') === '1.2.3.4:443');
  ok('a relay removed from the settings stops resolving', T.resolveRelayToken({ proxyIps: ['1.2.3.4:443'] }, tok) === '');
  ok('the hash is stable across calls', T.fnv1a64Hex(RELAY) === T.fnv1a64Hex(RELAY));
  ok('different relays get different ids', T.fnv1a64Hex(RELAY) !== T.fnv1a64Hex('socks5://bob:other@5.6.7.8:1080'));
}

/* ---------- 4) shared validator + persistence across panel, app API and Telegram bot ---------- */
{
  const envWithKv = { CAT_KV: mkKv(), UUID };
  T.kvCacheClear();
  const s0 = await T.readSettings(envWithKv);
  const r1 = await T.addCleanIpEntries(envWithKv, s0, ['104.16.1.1:8443#DE', '[2606:4700::1]:2053#NL', '104.16.1.1:8443', '-bad_host'], { source: 'app' });
  ok('shared clean-IP validator reports added, duplicates, invalid and persisted=true',
    r1.persisted === true && r1.noKv === false &&
    r1.added.includes('104.16.1.1:8443') && r1.added.includes('[2606:4700::1]:2053') &&
    r1.duplicates.includes('104.16.1.1:8443') && r1.invalid.includes('-bad_host'),
    JSON.stringify(r1));

  const s1 = await T.readSettings(envWithKv);
  const botIpsReply = await T.tgCommand('/ips add 104.16.1.1:8443#DE 104.16.1.2:2083#FR -bad_host', {
    origin: 'https://x.workers.dev', host: 'x.workers.dev', env: envWithKv, settings: s1, masterUuid: UUID,
  });
  ok('Telegram /ips add reports stored, duplicate, invalid and CAT_KV persistence',
    botIpsReply.includes('stored (1)') && botIpsReply.includes('104.16.1.2:2083') &&
    botIpsReply.includes('duplicate (1)') && botIpsReply.includes('104.16.1.1:8443') &&
    botIpsReply.includes('invalid (1)') && botIpsReply.includes('persisted in CAT_KV'),
    botIpsReply);

  const s2 = await T.readSettings(envWithKv);
  const botProxyReply = await T.tgCommand('/proxyips add 103.237.102.191:11111#SG 184.75.221.82:3118#CA 103.237.102.191:11111 1.2.3.4', {
    origin: 'https://x.workers.dev', host: 'x.workers.dev', env: envWithKv, settings: s2, masterUuid: UUID,
  });
  ok('Telegram /proxyips add preserves 103.237.102.191:11111 and 184.75.221.82:3118 while rejecting bare IP',
    botProxyReply.includes('stored (2)') &&
    botProxyReply.includes('103.237.102.191:11111') &&
    botProxyReply.includes('184.75.221.82:3118') &&
    botProxyReply.includes('duplicate (1)') &&
    botProxyReply.includes('invalid (1)') && botProxyReply.includes('1.2.3.4'),
    botProxyReply);

  const envNoKv = { UUID };
  T.kvCacheClear();
  const sNoKv = await T.readSettings(envNoKv);
  const noKvReply = await T.tgCommand('/proxyips add 103.237.102.191:11111#SG', {
    origin: 'https://x.workers.dev', host: 'x.workers.dev', env: envNoKv, settings: sNoKv, masterUuid: UUID,
  });
  ok('Telegram /proxyips add without CAT_KV explicitly warns no-KV',
    noKvReply.includes('no-KV'), noKvReply);
}

console.log('\n' + (fail === 0 ? `proxyip validation: ALL ${pass} PASSED` : `proxyip validation: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
