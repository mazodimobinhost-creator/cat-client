/**
 * PANEL SECURITY + PER-USER LIMITS (panel 6.69.45)
 *
 * Pins: salted password hash (legacy sha256 still accepted), RFC 6238 TOTP,
 * login lockout per IP + audit log, 2FA-gated login, per-user traffic quota
 * (KV-safe meter → subscription-userinfo), simultaneous-IP limit and the
 * trojan auth map never serving a stale (disabled) user.
 *
 * Run standalone:  node scripts/panels/panel-security.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;
const mod = await import(workerPath);
const T = mod._testing;
const HOST = 'sec-test.workers.dev';

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};
const memKv = () => {
  const m = new Map(); let puts = 0;
  return {
    m, get puts() { return puts; },
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { puts++; m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [...m.keys()].map((name) => ({ name })), list_complete: true }),
  };
};
const ctx = { waitUntil() {} };

/* A) password hashing */
{
  const h = await T.hashPanelPassword('s3cret');
  ok('salted hash has the s1$salt$hash shape', /^s1\$[0-9a-f]{32}\$[0-9a-f]{64}$/.test(h), h);
  ok('two hashes of one password differ (salt)', h !== await T.hashPanelPassword('s3cret'));
  ok('salted hash verifies', await T.verifyPanelPassword('s3cret', h));
  ok('wrong password rejected', !(await T.verifyPanelPassword('S3cret', h)));
  ok('legacy unsalted sha256 still verifies', await T.verifyPanelPassword('admin', await T.sha256Hex('admin')));
}

/* B) TOTP — RFC 6238 appendix B (SHA-1 seed "12345678901234567890") */
{
  const secret = T.base32Encode(new TextEncoder().encode('12345678901234567890'));
  ok('base32 round-trip', new TextDecoder().decode(T.base32Decode(secret)) === '12345678901234567890');
  ok('RFC 6238 T=59 → 287082', (await T.totpCode(secret, Math.floor(59 / 30))) === '287082');
  ok('RFC 6238 T=1111111109 → 081804', (await T.totpCode(secret, Math.floor(1111111109 / 30))) === '081804');
  ok('RFC 6238 T=2000000000 → 279037', (await T.totpCode(secret, Math.floor(2000000000 / 30))) === '279037');
  ok('verifyTotp accepts the current code', await T.verifyTotp(secret, '287082', 59 * 1000));
  ok('verifyTotp accepts ±1 step drift', await T.verifyTotp(secret, '287082', 85 * 1000));
  ok('verifyTotp rejects a far code', !(await T.verifyTotp(secret, '287082', 200 * 1000)));
  ok('verifyTotp rejects non-digits', !(await T.verifyTotp(secret, 'abcdef', 59 * 1000)));
}

/* C) lockout curve */
ok('4 fails → no lock', T.loginLockMs(4) === 0);
ok('5 fails → 60 s', T.loginLockMs(5) === 60000);
ok('6 fails → 120 s', T.loginLockMs(6) === 120000);
ok('lock is capped at 30 min', T.loginLockMs(40) === 30 * 60 * 1000);

/* D) login guard + audit + 2FA through the real router */
{
  const kv = memKv();
  const env = { CAT_KV: kv };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.hashPanelPassword('owner-pw') });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();
  const login = (pw, ip, otp) => T.handleRequest(new Request('https://' + HOST + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json', 'cf-connecting-ip': ip },
    body: JSON.stringify({ username: 'admin', password: pw, otp }),
  }), env, ctx);
  const good = await login('owner-pw', '198.51.100.7');
  ok('salted password signs in', good.status === 200, good.status);
  const cookie = (good.headers.get('set-cookie') || '').split(';')[0];
  for (let i = 0; i < T.LOGIN_FAIL_LIMIT; i++) await login('nope', '203.0.113.9');
  const locked = await login('owner-pw', '203.0.113.9');
  ok('after 5 wrong passwords the IP is locked (429), even with the right one', locked.status === 429, locked.status);
  ok('…with a retry-after header', Number(locked.headers.get('retry-after')) > 0);
  const other = await login('owner-pw', '198.51.100.8');
  ok('another IP is not affected', other.status === 200, other.status);
  const audit = await (await T.handleRequest(new Request('https://' + HOST + '/api/login-audit', { headers: { cookie } }), env, ctx)).json();
  ok('audit log lists attempts (owner only)', audit.ok && audit.entries.length >= 6 && audit.entries.some((e) => !e.ok) && audit.entries.some((e) => e.ok));
  const anonAudit = await T.handleRequest(new Request('https://' + HOST + '/api/login-audit'), env, ctx);
  ok('audit log is not public', anonAudit.status === 401, anonAudit.status);

  // 2FA setup → enable → login needs the code
  const setup = await (await T.handleRequest(new Request('https://' + HOST + '/api/2fa/setup', { method: 'POST', headers: { cookie } }), env, ctx)).json();
  ok('2FA setup returns a base32 secret + otpauth uri', setup.ok && /^[A-Z2-7]{32}$/.test(setup.secret) && setup.uri.startsWith('otpauth://totp/'));
  const badEnable = await T.handleRequest(new Request('https://' + HOST + '/api/2fa/enable', { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: JSON.stringify({ secret: setup.secret, code: '000000' }) }), env, ctx);
  ok('enabling with a wrong code is refused', badEnable.status === 400);
  const code = await T.totpCode(setup.secret, Math.floor(Date.now() / 30000));
  const en = await T.handleRequest(new Request('https://' + HOST + '/api/2fa/enable', { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: JSON.stringify({ secret: setup.secret, code }) }), env, ctx);
  ok('enabling with the right code works', en.status === 200, en.status);
  T.kvCacheClear();
  const st = await (await T.handleRequest(new Request('https://' + HOST + '/api/settings', { headers: { cookie } }), env, ctx)).json();
  ok('settings say totpEnabled and never leak the secret', st.settings.totpEnabled === true && st.settings.totpSecret === undefined && !JSON.stringify(st).includes(setup.secret));
  const noOtp = await (await login('owner-pw', '198.51.100.20')).json();
  ok('password alone now answers otp_required', noOtp.error === 'otp_required');
  const withOtp = await login('owner-pw', '198.51.100.20', await T.totpCode(setup.secret, Math.floor(Date.now() / 30000)));
  ok('password + code signs in', withOtp.status === 200, withOtp.status);
  const bearer = await T.handleRequest(new Request('https://' + HOST + '/api/users', { headers: { authorization: 'Bearer owner-pw' } }), env, ctx);
  ok('Bearer password is refused while 2FA is on', bearer.status === 401, bearer.status);
  const backup = await (await T.handleRequest(new Request('https://' + HOST + '/api/backup', { headers: { cookie } }), env, ctx)).json();
  ok('the backup masks the TOTP secret', backup.settings && backup.settings.totpSecret !== setup.secret);
}

/* E) quota + subscription-userinfo + IP limit */
{
  const kv = memKv();
  const env = { CAT_KV: kv };
  const u = T.normalizeUser({ name: 'q', quotaGB: 1, maxIps: 2 });
  ok('quotaGB → quotaBytes', u.quotaBytes === 1073741824, u.quotaBytes);
  ok('maxIps kept', u.maxIps === 2);
  ok('no quota → never "quota"', T.userBlockedReason(T.normalizeUser({ name: 'x' }), { up: 9e12, down: 9e12 }) === null);
  const h0 = T.subscriptionHeaders(u, 'Cat', '', { up: 10, down: 20 });
  ok('subscription-userinfo carries real usage + total', h0['subscription-userinfo'] === 'upload=10; download=20; total=1073741824', h0['subscription-userinfo']);
  ok('profile-title / profile-update-interval present', h0['profile-title'].startsWith('base64:') && h0['profile-update-interval'] === '12');
  const before = kv.puts;
  await T.addTraffic(env, u.id, 1000, 2000);
  await T.addTraffic(env, u.id, 1000, 2000);
  ok('small traffic stays in memory (no KV write per connection)', kv.puts === before, kv.puts - before);
  ok('…but is already visible to the gate', (await T.readTraffic(env, u.id)).down === 4000);
  await T.addTraffic(env, u.id, 0, 70 * 1024 * 1024);
  ok('≥64 MB pending flushes once to KV', kv.puts === before + 1, kv.puts - before);
  await T.addTraffic(env, u.id, 0, 1073741824);
  const tr = await T.readTraffic(env, u.id, true);
  ok('over-quota user is blocked with reason quota', T.userBlockedReason(u, tr) === 'quota');
  await T.resetTraffic(env, u.id);
  ok('reset-usage zeroes the meter', (await T.readTraffic(env, u.id, true)).down === 0);
  ok('IP 1 admitted', await T.admitUserIp(env, u, '10.0.0.1'));
  ok('IP 2 admitted', await T.admitUserIp(env, u, '10.0.0.2'));
  ok('IP 3 refused (maxIps=2)', !(await T.admitUserIp(env, u, '10.0.0.3')));
  ok('a known IP reconnects fine', await T.admitUserIp(env, u, '10.0.0.1'));
}

/* F) trojan map never serves a stale user */
{
  const env = {};
  const master = '11111111-1111-4111-8111-111111111111';
  const a = T.normalizeUser({ id: '22222222-2222-4222-8222-222222222222', name: 'a' });
  const m1 = await T.trojanCandidates(env, master, [a]);
  const disabled = Object.assign({}, a, { enabled: false });
  const m2 = await T.trojanCandidates(env, master, [disabled]);
  const entry = [...m2.values()].find((v) => v.user && v.user.id === a.id);
  ok('disabling a user rebuilds the trojan map', m1 !== m2 && entry && entry.user.enabled === false);
}

console.log(`\npanel security: ${pass} passed, ${fail} FAILED`);
assert.equal(fail, 0, fail + ' checks failed');
