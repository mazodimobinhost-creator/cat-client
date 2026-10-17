/**
 * SECRETS, TOKENS AND BACKUPS — the owner asked for four things that all touch
 * «what leaves the panel and who can read it», so they are pinned in one suite:
 *
 *   1. a subscription TOKEN independent of the UUID, rotatable and revocable
 *      (after a rotation the old links must be rejected);
 *   2. UUID rotation, and the guard that refuses it while the UUID *is* the
 *      password (otherwise the owner locks themselves out);
 *   3. a JSON backup that MASKED the secrets by default and a restore that skips
 *      masked fields — a masked backup must never wipe a live secret;
 *   4. the diagnostics page's data (the owner-facing «no curl needed» view).
 *
 * Run standalone:  node scripts/panels/secrets-tokens.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const T = (await import(workerPath))._testing;
const HOST = 'tokens.workers.dev';
const UUID = await T.resolveUuid(HOST, {});
const OWNER = 'owner-secret';

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};

const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [], list_complete: true }),
  };
};

/** A configured panel with a real password, owned by OWNER. */
async function panel(extra = {}) {
  const kv = memKv();
  const env = Object.assign({ CAT_KV: kv }, extra);
  const settings = Object.assign({}, await T.readSettings(env), {
    passwordHash: await T.sha256Hex(OWNER),
    // A socks5 chain with credentials, a bot token and a PAT: the three secrets the
    // backup has to handle, all in different shapes.
    chain: 'socks5://proxy-user:socks-pass@10.0.0.9:1080',
    tgToken: '123456:AAF-SECRET-BOT-TOKEN',
    ghPat: 'ghp_SECRETPAT',
    ips: ['1.2.3.4', '5.6.7.8'],
    proxyIps: ['socks5://user2:pw2@9.9.9.9:443'],
  });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();
  return { kv, env, settings };
}
const get = (env, path, init) => T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() {} });
const asOwner = (path, init = {}) => Object.assign({}, init, { headers: Object.assign({ 'x-cat-key': OWNER }, init.headers || {}) });

console.log('\nSecrets, sub tokens, UUID rotation and backups\n');

/* ---------- 1) an independent subscription token ---------- */
{
  const { env } = await panel();
  T.kvCacheClear();

  // No token yet: the UUID is the sub key (every existing link keeps working).
  ok('without a token, subscriptions use the master UUID',
    (await get(env, '/sub/' + UUID)).status === 200);

  const rotated = await (await get(env, '/api/sub-token', asOwner('/api/sub-token', {
    method: 'POST', body: JSON.stringify({ action: 'rotate' }),
  }))).json();
  ok('rotation returns a 32-hex token', rotated.ok === true && /^[0-9a-f]{32}$/.test(rotated.token || ''), JSON.stringify(rotated).slice(0, 90));
  ok('…and warns that old links die', /stop(s)? working/i.test(rotated.warning || ''), rotated.warning);
  T.kvCacheClear();

  ok('the new token works on every master path',
    (await get(env, '/sub/' + rotated.token)).status === 200 &&
      (await get(env, '/clash/' + rotated.token)).status === 200 &&
      (await get(env, '/singbox/' + rotated.token)).status === 200);
  ok('the OLD uuid link is rejected after rotation',
    (await get(env, '/sub/' + UUID)).status === 404, 'old link still served');
  ok('a random key is still rejected', (await get(env, '/sub/' + 'f'.repeat(32))).status === 404);

  /* The panel's OWN links have to follow the rotation too. This is the half that was
     missing: the Config Builder fetches the link it just built (function bGen), so a link
     built from the raw UUID answered 404 the moment the token was rotated and the user saw
     «the subscription is empty — no config was built / the response is not a config
     subscription: HTTP 404». Reported as «when I create a link it gives this error». */
  const handed = await (await get(env, '/api/settings', asOwner('/api/settings'))).json();
  ok('the master link the panel hands out uses the new token',
    String((handed.links || {}).sub || '').endsWith('/sub/' + rotated.token), (handed.links || {}).sub);
  ok('…and that handed-out link really serves configs',
    (await get(env, new URL(handed.links.sub).pathname)).status === 200);
  ok('…the page is given the same key to build its links with', handed.subKey === rotated.token, handed.subKey);
  ok('…every format the page offers follows the rotation',
    ['sub64', 'clash', 'singbox', 'xray'].every((k) => String((handed.links || {})[k] || '').endsWith('/' + k + '/' + rotated.token)),
    JSON.stringify(handed.links));
  ok('…the info/QR page accepts the token and refuses the dead uuid',
    (await get(env, '/info/' + rotated.token)).status === 200 && (await get(env, '/info/' + UUID)).status === 404);
  ok('…and the /u/ shape accepts the master token only',
    (await get(env, '/u/' + rotated.token)).status === 200 && (await get(env, '/u/' + UUID)).status === 404);
  const tgLink = await T.tgCommand('/link', {
    origin: 'https://' + HOST, host: HOST, env, settings: await T.readSettings(env), masterUuid: UUID,
  });
  ok('…and the Telegram bot hands out the live link, not the dead one',
    String(tgLink).includes('/sub/' + rotated.token) && !String(tgLink).includes('/sub/' + UUID),
    String(tgLink).slice(0, 110));

  const revoked = await (await get(env, '/api/sub-token', asOwner('/api/sub-token', {
    method: 'POST', body: JSON.stringify({ action: 'revoke' }),
  }))).json();
  T.kvCacheClear();
  ok('revocation clears the token', revoked.ok === true && revoked.token === '');
  ok('…the tokenized link is dead now', (await get(env, '/sub/' + rotated.token)).status === 404);
  ok('…and the UUID link works again', (await get(env, '/sub/' + UUID)).status === 200);

  // Only the owner may rotate.
  const anon = await get(env, '/api/sub-token', { method: 'POST', body: '{}' });
  ok('a stranger cannot rotate the token', anon.status === 401, anon.status);
}

/* ---------- 2) rotating the UUID, and the lock-out guard ---------- */
{
  const { env, kv } = await panel();
  T.kvCacheClear();

  const done = await (await get(env, '/api/rotate-uuid', asOwner('/api/rotate-uuid', { method: 'POST' }))).json();
  ok('the owner can rotate the UUID', done.ok === true && /^[0-9a-f-]{36}$/.test(done.uuid || ''), JSON.stringify(done).slice(0, 90));
  ok('…with the warning about old links', /stop working/i.test(done.warning || ''));

  // The rotation is stored, so a *different* isolate — same KV, empty cache — sees it.
  T.kvCacheClear();
  const fresh = Object.assign({}, env);
  ok('the new UUID is the one the worker resolves now',
    (await T.resolveUuid(HOST, fresh)) === done.uuid, await T.resolveUuid(HOST, fresh));
  ok('…so the old UUID no longer serves subscriptions', (await get(env, '/sub/' + UUID)).status === 404);
  ok('…and the new one does', (await get(env, '/sub/' + done.uuid)).status === 200);

  // An install whose password IS the UUID must not be rottable: the owner would be
  // locked out of their own panel (the password and every session derive from it).
  const kv2 = memKv();
  const env2 = { CAT_KV: kv2 };
  T.kvCacheClear();
  const refused = await get(env2, '/api/rotate-uuid', {
    method: 'POST', headers: { 'x-cat-key': UUID },
  });
  const refusedBody = await refused.json();
  ok('rotation is refused while the UUID is the password (lock-out guard)',
    refused.status === 400 && refusedBody.error === 'set_password_first', JSON.stringify(refusedBody).slice(0, 90));
  void kv;
}

/* ---------- 3) the backup masks secrets, and restore respects the mask ---------- */
{
  const { env } = await panel();
  T.kvCacheClear();

  const res = await get(env, '/api/backup?x=1', asOwner('/api/backup'));
  const raw = await res.text();
  const backup = JSON.parse(raw);
  ok('the backup is an attachment', /attachment; filename="cat-panel-backup.json"/.test(res.headers.get('content-disposition') || ''), res.headers.get('content-disposition'));
  ok('…and never cached', /no-store/.test(res.headers.get('cache-control') || ''));
  ok('the socks5 proxy password is NOT in the file', !raw.includes('socks-pass'), 'socks5 credential leaked');
  ok('…neither the bot token nor the PAT', !raw.includes('AAF-SECRET-BOT-TOKEN') && !raw.includes('ghp_SECRETPAT'));
  ok('…nor the master UUID', !raw.includes(UUID), 'uuid leaked');
  ok('…and the panel password hash is masked', !raw.includes(await T.sha256Hex(OWNER)));
  ok('the masked fields are listed for the reader', Array.isArray(backup.masked) && backup.masked.length >= 4, JSON.stringify(backup.masked));
  ok('the response explains the mask/restore rule', /skipped by restore/i.test(backup.restoreNote || ''), backup.restoreNote);
  ok('the backup still carries the data it exists for',
    backup.settings.ips.length === 2 && backup.settings.proxyIps.length === 1 && backup.settings.chain === '***MASKED-BY-CAT-PANEL***',
    JSON.stringify({ ips: backup.settings.ips, chain: backup.settings.chain }));

  // …and the explicit request still works, with a warning attached.
  const withSecrets = await (await get(env, '/api/backup?includeSecrets=1', asOwner('/api/backup?includeSecrets=1'))).json();
  ok('?includeSecrets=1 keeps the real values', String(withSecrets.settings.chain).includes('socks-pass'));
  ok('…and says out loud that the file is sensitive', /passwords and tokens/i.test(withSecrets.secrets || ''), withSecrets.secrets);

  // Restore the masked file: the live secrets must survive, the data must come back.
  // Only the DATA is wiped here. The secret stays live on purpose: that is the
  // situation a masked backup is designed for («restore my addresses without
  // touching the proxy password I cannot read back»).
  const wiped = Object.assign({}, await T.readSettings(env), { ips: [] });
  await T.writeSettings(env, wiped);
  T.kvCacheClear();
  const restored = await (await get(env, '/api/backup', asOwner('/api/backup', {
    method: 'POST', body: JSON.stringify(backup),
  }))).json();
  T.kvCacheClear();
  const after = await T.readSettings(env);
  ok('restore reports what it skipped', Array.isArray(restored.skippedMaskedFields) && restored.skippedMaskedFields.includes('chain'), JSON.stringify(restored).slice(0, 110));
  ok('…brings the addresses back', after.ips.length === 2, JSON.stringify(after.ips));
  ok('…and does NOT wipe the live socks5 chain with the mask',
    after.chain === 'socks5://proxy-user:socks-pass@10.0.0.9:1080', JSON.stringify(after.chain));
  ok('…and never stores the literal mask in its place',
    after.chain !== '***MASKED-BY-CAT-PANEL***' && !String(after.tgToken || '').includes('MASKED'));

  // A stranger cannot download or restore anything.
  ok('a stranger cannot download the backup', (await get(env, '/api/backup')).status === 401);
  ok('a stranger cannot restore', (await get(env, '/api/backup', { method: 'POST', body: '{}' })).status === 401);
}

/* ---------- 4) the diagnostics page has its data (and leaks nothing) ---------- */
{
  const { env } = await panel();
  T.kvCacheClear();
  const diag = await (await get(env, '/api/diag', asOwner('/api/diag'))).json();
  const asText = JSON.stringify(diag);
  ok('diag answers the owner with the page fields',
    diag.ok === true && typeof diag.kv === 'string' && Array.isArray(diag.bindings) &&
      typeof diag.version === 'string' && typeof diag.subToken === 'string' &&
      typeof diag.uuidSource === 'string' && typeof diag.passwordSource === 'string',
    JSON.stringify(Object.keys(diag)));
  ok('…including the request counter the page shows',
    !!diag.usage && typeof diag.usage.reqs === 'number', JSON.stringify(diag.usage).slice(0, 90));
  ok('…and the KV probe result, not a guess', /ok|NOT BOUND|FAILED|bound/.test(diag.kv), diag.kv);
  ok('…and it never returns the UUID or a secret',
    !asText.includes(UUID) && !asText.includes('socks-pass') && !asText.includes('AAF-SECRET-BOT-TOKEN'),
    'diag leaked a secret');
  ok('the diagnostics page markup exists in the panel HTML',
    (await T.panelPage({}, { lang: 'fa' }, HOST, UUID)).includes('diagOut'));
}

console.log('\nsecrets & tokens: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
