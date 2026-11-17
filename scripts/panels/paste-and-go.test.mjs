/**
 * PASTE-AND-GO: the experience of someone who copies the file into a brand-new
 * Worker and has configured nothing — no KV namespace, no environment variables.
 *
 * Before this, that state was a dead end: `/panel` answered a login form that
 * said «the default password is the panel UUID» without ever showing the UUID,
 * and `deriveUuid()` (sha256('cat-panel:uuid:' + host)) is not something a person
 * can guess while staring at the screen. The person concludes the paste is broken.
 *
 * The fix is deliberately narrow: the setup card and the root redirect exist ONLY
 * while the install is unconfigured (no KV binding AND no password). The moment a
 * KV namespace is bound as CAT_KV — or any password exists — both disappear and
 * the previous behaviour (camouflage at the root, plain login at the panel path)
 * comes back byte for byte.
 *
 * Run standalone:  node scripts/panels/paste-and-go.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const T = (await import(workerPath))._testing;
const HOST = 'brand-new.workers.dev';
const UUID = await T.resolveUuid(HOST, {});

const memKv = () => { const m = new Map(); return { get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => { m.set(k, String(v)); }, delete: async (k) => { m.delete(k); }, list: async () => ({ keys: [] }) }; };
const get = async (env, path, init = {}) => T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() { } });

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

console.log('\nPaste-and-go — a fresh Worker with nothing configured\n');

/* ---------- A) the state a plain paste lands in ---------- */
{
  const env = {}; // exactly what a paste gives you

  const root = await get(env, '/');
  ok('the worker root does not answer a fake blog to its own owner', root.status === 302, 'status ' + root.status);
  ok('…it redirects to the panel path', root.headers.get('location') === '/panel/', root.headers.get('location'));

  const panel = await get(env, '/panel');
  const html = await panel.text();
  ok('the panel path answers the setup screen (not a dead-end login)', panel.status === 200 && html.includes('type="password"'), panel.status);
  ok('the setup screen never exposes the panel UUID', !html.includes(UUID), UUID);
  ok('…shows the admin bootstrap username', /Username:|نام کاربری:/.test(html) && html.includes('<code>admin</code>'));
  ok('…requires an immediate password change', /Change the password|رمز عبور را.*تغییر/.test(html));
  ok('…and the KV steps by name (CAT_KV)', html.includes('CAT_KV'));
  ok('…and warns that nothing is stored and anyone can sign in',
    /nothing is stored|هیچ تنظیمی ذخیره نمی‌شود/.test(html));

  // The documented bootstrap credentials have to actually work.
  const tap = await get(env, '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) });
  const cookie = (tap.headers.get('set-cookie') || '').split(';')[0];
  ok('admin / admin signs in (200 + session cookie)', tap.status === 200 && /cat_session=[0-9]+\.[0-9a-f]{64}/.test(cookie), `${tap.status} ${cookie.slice(0, 40)}`);
  const inside = await get(env, '/panel', { headers: { cookie } });
  const insideHtml = await inside.text();
  ok('…and the real panel then renders', insideHtml.includes('id="v-home"') && insideHtml.length > 100000, insideHtml.length + 'B');

  // 6.67: a wrong ?p= no longer renders the login page in place — it redirects to
  // a URL with the value stripped (a secret must not sit in the address bar) and
  // sticks a ?stale=1 flag on it, which the login page turns into a hint.
  const bad = await get(env, '/panel?p=wrong-password-here');
  const badLoc = bad.headers.get('location') || '';
  ok('a wrong one-tap password does not sign in',
    bad.status === 302 && !/cat_session=/.test(bad.headers.get('set-cookie') || '') && !/wrong-password-here/.test(badLoc) && /stale=1/.test(badLoc),
    bad.status + ' ' + badLoc);
  const badPage = await get(env, '/panel?stale=1');
  ok('…and the login page asks for the password again', (await badPage.text()).includes('id=\"f\"'));
}

/* ---------- B) a configured panel must be untouched ---------- */
{
  const kv = memKv();
  const env = { CAT_KV: kv };
  const settings = await T.readSettings(env);
  await kv.put((T.KV_KEYS && T.KV_KEYS.settings) || 'cat_settings_v1', JSON.stringify(settings));
  T.kvCacheClear(); // settings are cached per isolate — a direct KV write must invalidate it

  // KV bound but NO password: still first-run. Workers Builds auto-provisions the
  // KV namespace, so this is exactly what the first deploy of a fresh Worker
  // looks like — hiding the card here was the bug.
  const kvOnly = await get(env, '/panel');
  const kvOnlyHtml = await kvOnly.text();
  ok('KV bound but no password yet → the setup card stays (this is the auto-provisioned state)',
    !kvOnlyHtml.includes(UUID) && kvOnlyHtml.includes('<code>admin</code>') && /Storage \(KV\) is bound|ذخیره‌سازی \(KV\) بایند شده/.test(kvOnlyHtml));
  ok('…and it says the one remaining step is to set a password',
    /Change the password|رمز عبور را.*تغییر/.test(kvOnlyHtml));

  // The real configured state: a password exists.
  settings.passwordHash = await T.sha256Hex('owner-password-here');
  await kv.put((T.KV_KEYS && T.KV_KEYS.settings) || 'cat_settings_v1', JSON.stringify(settings));
  T.kvCacheClear(); // settings are cached per isolate — a direct KV write must invalidate it

  const root = await get(env, '/');
  ok('with a password set, the root redirects to the real panel', root.status === 302 && root.headers.get('location') === '/panel/', root.status);

  const panel = await get(env, '/panel');
  const html = await panel.text();
  ok('…and the panel path is a plain login form', panel.status === 200 && html.includes('type="password"') && !/<div class="card" style="margin-top:14px/.test(html));
  ok('…with no UUID printed anywhere', !html.includes(UUID));
  ok('…and no setup card', !html.includes('CAT_KV'));
}
{
  // A password is enough on its own to close the setup card, even without KV.
  const kv = memKv();
  const env = { CAT_KV: kv, PANEL_PASSWORD: 'owner-set-password' };
  const panel = await get(env, '/panel');
  const html = await panel.text();
  ok('a PANEL_PASSWORD env var also closes the setup card', !html.includes(UUID));
}

console.log('\n' + (fail === 0 ? `paste-and-go: ALL ${pass} PASSED` : `paste-and-go: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
