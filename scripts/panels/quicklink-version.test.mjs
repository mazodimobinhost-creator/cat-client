/**
 * QUICK LINKS AND THE APP/PANEL HANDSHAKE — two smaller items from the owner's
 * list, pinned together because both are about «the two halves must never lie to
 * each other»:
 *
 *   1. `?p=<password|uuid>` (the link the Android app opens, an old bookmark, a
 *      link pasted in a chat) must turn into a session cookie and then LEAVE the
 *      URL — on success *and* on failure. While it survived a failed attempt the
 *      master UUID sat in the address bar, in the browser history and in the
 *      Referer of everything the login page loaded afterwards.
 *   2. the app announces itself (`av` = its own version, `pv` = the panel version
 *      it ships). The panel remembers that and warns when the two disagree —
 *      the state that looks like a broken worker but is only a version skew.
 *
 * Run standalone:  node scripts/panels/quicklink-version.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const T = (await import(workerPath))._testing;
const HOST = 'quick.workers.dev';
const UUID = await T.resolveUuid(HOST, {});
const OWNER = 'owner-secret';
const VERSION = T.CAT_PANEL_VERSION;

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

/** A panel with a real password (= the UUID is no longer the password). */
async function panel(extra = {}, settingsPatch = {}) {
  const kv = memKv();
  const env = Object.assign({ CAT_KV: kv }, extra);
  const settings = Object.assign({}, await T.readSettings(env), {
    passwordHash: await T.sha256Hex(OWNER),
    ips: ['1.2.3.4'],
  }, settingsPatch);
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();
  return { kv, env, settings };
}
/** A fresh install: no KV, no password — the UUID is the password. */
async function freshPanel() {
  const env = {};
  const settings = Object.assign({}, await T.readSettings(env));
  T.kvCacheClear();
  return { env, settings };
}
const get = (env, path, init) => T.handleRequest(new Request('https://' + HOST + path, Object.assign({ redirect: 'manual' }, init)), env, { waitUntil() {} });
const asOwner = (init = {}) => Object.assign({}, init, { headers: Object.assign({ 'x-cat-key': OWNER }, init.headers || {}) });

console.log('\nQuick links (?p=) and the app/panel version handshake\n');

/* ---------- 1) ?p= must not survive in the URL ---------- */
{
  const { env } = await panel();
  // With a stealth path configured (the default «panel») the panel answers on
  // /panel/ — the root is camouflage. Both entries must drop the secret, so both
  // are checked: the root hop and the real panel path the app opens.
  const ROOT = (await T.readSettings(env)).panelPath || 'panel';

  // A bare-root link (a link pasted in a chat) must not carry the secret onward.
  const hop = await get(env, '/?p=guessed-wrong&ips=9.9.9.9');
  const hopLoc = hop.headers.get('location') || '';
  ok('a root link hops to the panel path without the secret',
    hop.status === 302 && !/guessed-wrong/.test(hopLoc) && /ips=9\.9\.9\.9/.test(hopLoc) && /stale=1/.test(hopLoc), hopLoc);

  // Wrong password: the secret is dropped, the failure is a flag, never an echo.
  const bad = await get(env, '/' + ROOT + '/?p=guessed-wrong&u=someone');
  const badLoc = bad.headers.get('location') || '';
  ok('a FAILED ?p= still redirects (302)', bad.status === 302, bad.status);
  ok('…to a URL with no secret in it', !/guessed-wrong|p=|u=/.test(badLoc), badLoc);
  ok('…carrying the stale flag instead', /stale=1/.test(badLoc), badLoc);
  ok('…and no session cookie is set', !(bad.headers.get('set-cookie') || '').includes('cat_session'), bad.headers.get('set-cookie') || '(none)');

  // Right password: cookie + clean URL.
  const good = await get(env, '/' + ROOT + '/?p=' + encodeURIComponent(OWNER) + '&u=admin');
  const goodLoc = good.headers.get('location') || '';
  ok('a correct ?p= sets the session cookie', (good.headers.get('set-cookie') || '').includes('cat_session='), good.headers.get('set-cookie') || '(none)');
  ok('…and redirects to a clean URL too', good.status === 302 && !/owner-secret|p=/.test(goodLoc) && !/stale=1/.test(goodLoc), goodLoc);

  // Unrelated parameters (the scanner's ?ips= import) survive the strip.
  const withIps = await get(env, '/' + ROOT + '/?p=' + encodeURIComponent(OWNER) + '&ips=9.9.9.9');
  const ipsLoc = withIps.headers.get('location') || '';
  ok('…keeping the params that are not secrets', /ips=9\.9\.9\.9/.test(ipsLoc) && !/[?&]p=/.test(ipsLoc), ipsLoc);

  // The stale hint reaches the login page, and the UUID is nowhere in it.
  const login = await get(env, '/' + ROOT + '/?stale=1');
  const html = await login.text();
  ok('the login page answers the stale flag', login.status === 200 && /stale/.test(html), login.status);
  ok('…without echoing the UUID into the page', !html.includes(UUID), 'uuid leaked into the login HTML');
}

/* ---------- 1b) a fresh, passwordless install: the UUID link still works ---------- */
{
  const { env, settings } = await freshPanel();
  const one = await get(env, '/' + (settings.panelPath || 'panel') + '/?p=' + encodeURIComponent('admin') + '&u=admin');
  ok('on a fresh install admin bootstrap quick link still signs the owner in',
    (one.headers.get('set-cookie') || '').includes('cat_session='), one.status + ' ' + (one.headers.get('location') || ''));
  ok('…and the UUID does not stay in the URL', !(one.headers.get('location') || '').includes(UUID), one.headers.get('location') || '');
}

/* ---------- 2) the app/panel handshake ---------- */
{
  const { env, kv } = await panel();
  const ROOT = '/' + ((await T.readSettings(env)).panelPath || 'panel') + '/';

  // Nothing reported yet — the banner must not appear out of thin air.
  const plain = await (await get(env, ROOT, asOwner())).text();
  ok('with no app report there is no version banner', !/id="verWarn"/.test(plain));

  // The app opens the panel announcing itself: matching versions → no banner.
  const same = await (await get(env, ROOT + '?av=1.9.3&pv=' + VERSION, asOwner())).text();
  ok('a matching report shows no banner', !/id="verWarn"/.test(same));
  T.kvCacheClear();
  const stored = JSON.parse(await kv.get(T.KV_KEYS.appSeen));
  ok('…but it is remembered (av/pv in KV)', stored.av === '1.9.3' && stored.pv === VERSION, JSON.stringify(stored));

  // A skewed app: an older panel bundled in the app than what the Worker runs.
  const older = await (await get(env, ROOT + '?av=1.9.3&pv=6.0.0', asOwner())).text();
  ok('a skew renders the banner', /id="verWarn"/.test(older));
  ok('…naming both versions', older.includes(T.CAT_PANEL_VERSION) && /6\.0\.0/.test(older));
  ok('…and saying which side is older', /app is older/.test(older));
  ok('…with dismiss text that is translated in both languages',
    /ver_title/.test(older) && /ver_body/.test(older) && /ver_dismiss/.test(older));

  // A later open WITHOUT the params (the owner's own browser) must still warn:
  // the values come back from KV, not from this request.
  const laterBrowser = await (await get(env, ROOT, asOwner())).text();
  ok('…and the warning survives an open with no params at all (read back from KV)',
    /id="verWarn"/.test(laterBrowser) && /6\.0\.0/.test(laterBrowser));

  // The banner is owner-facing only: a stranger gets the login page.
  const stranger = await (await get(env, ROOT + '?av=1.9.3&pv=6.0.0')).text();
  ok('a stranger never sees the banner', !/id="verWarn"/.test(stranger) && !/6\.0\.0/.test(stranger));

  // A newer app (panel on the Worker is behind) says so too.
  const newer = await (await get(env, ROOT + '?av=1.9.9&pv=99.0.0', asOwner())).text();
  ok('a newer app is reported as «panel is older»', /panel is older/.test(newer));

  // Garbage in the params must not be able to break the page or the KV record.
  const junk = await get(env, ROOT + '?av=%3Cscript%3E&pv=' + encodeURIComponent('x'.repeat(200)), asOwner());
  const junkHtml = await junk.text();
  // The panel page legitimately contains <script> tags, so the check is that the
  // junk never became markup of its own and the page still rendered normally.
  ok('junk version params are neutralised',
    junk.status === 200 && /id="verWarn"/.test(junkHtml) && !/script>alert/.test(junkHtml), junk.status);
  T.kvCacheClear();
  const junkStored = JSON.parse(await kv.get(T.KV_KEYS.appSeen));
  ok('…and cannot overwrite the last real report',
    junkStored.av === '1.9.9' && junkStored.pv === '99.0.0', JSON.stringify(junkStored).slice(0, 80));
  ok('…while still staying short', String(junkStored.pv).length <= 24, JSON.stringify(junkStored).slice(0, 80));

  // The diagnostics page reports the handshake as data, without a second source of truth.
  const diag = await (await get(env, '/api/diag', asOwner())).json();
  ok('diag exposes the handshake', diag.app && typeof diag.app.match === 'string', JSON.stringify(diag.app).slice(0, 120));
  ok('…and names the app version it saw', diag.app.appVersion === '1.9.9', diag.app.appVersion);
  ok('…with an explicit mismatch verdict', /NO/.test(diag.app.match), diag.app.match);
}

/* ---------- 2b) a rotated UUID is refused by the quick link ---------- */
{
  const { env } = await panel();
  const rot = await (await get(env, '/api/rotate-uuid', asOwner({ method: 'POST', body: '{}' }))).json();
  ok('the UUID can be rotated while a password exists', rot.ok === true && /^[0-9a-f-]{36}$/.test(rot.uuid || ''), JSON.stringify(rot).slice(0, 90));
  T.kvCacheClear();
  const old = await get(env, '/' + ((await T.readSettings(env)).panelPath || 'panel') + '/?p=' + encodeURIComponent(UUID));
  ok('the pre-rotation quick link is refused (and stripped)',
    old.status === 302 && !(old.headers.get('set-cookie') || '').includes('cat_session=') && /stale=1/.test(old.headers.get('location') || ''),
    old.status + ' ' + (old.headers.get('location') || ''));
}

console.log('\nquick links & version handshake: ' + pass + ' passed, ' + fail + ' FAILED\n');
assert.equal(fail, 0, fail + ' check(s) failed');
