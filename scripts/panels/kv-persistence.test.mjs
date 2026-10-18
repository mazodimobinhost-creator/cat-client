/**
 * Persistence regression tests: a panel deployed WITHOUT its KV binding.
 *
 * This is the failure the user hit while «adding clean IPs to the configs»:
 * every write answered `ok:true` (and the UI toasted «3 ✓») while the value only
 * lived in one isolate's memory — so the list emptied again, the subscription
 * stayed empty and every save button looked broken.
 *
 * The tests pin three things:
 *   1. the API tells the truth (`persisted:false`) when there is no binding;
 *   2. /api/version reports `kv` and `needsUser` so the app can detect and
 *      repair a deployment (and log in with the username the panel requires);
 *   3. the panel page ships the «storage is not attached» banner and the
 *      empty-subscription explanation, in both languages.
 *
 * Usage: node scripts/panels/kv-persistence.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const worker = (await import(workerPath)).default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 220) : '')); }
};

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
const UUID = '11111111-2222-4333-8444-555555555555';
const HOST = 'https://edge-pedre.catclient-0ltgml5i.workers.dev';
const json = (env, p, method = 'GET', body) => worker.fetch(
  new Request(HOST + p, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body && JSON.stringify(body) }),
  env, { waitUntil() {} });

/* ── 1: no KV binding at all ────────────────────────────────────────────── */
{
  const env = { UUID, OPEN_PANEL: 'true' };   // deliberately no CAT_KV
  const add = await json(env, '/api/ips', 'POST', { ips: ['104.16.132.229#DE'], source: 'manual' });
  const addBody = await add.json();
  check('no KV → the import still answers ok (list is served from memory)', addBody.ok === true, JSON.stringify(addBody).slice(0, 120));
  check('no KV → the API says persisted:false instead of pretending', addBody.persisted === false, JSON.stringify(addBody).slice(0, 120));

  const version = await (await json(env, '/api/version')).json();
  check('no KV → /api/version reports kv:false (app can repair)', version.kv === false, JSON.stringify(version));
  check('/api/version reports needsUser=false for a panel without a username', version.needsUser === false, JSON.stringify(version));

  // A *fresh isolate* (second module instance) must lose the change — that is
  // exactly what the user saw after a refresh.
  const other = (await import(workerPath + '?isolate=2')).default;
  const settings = await (await other.fetch(new Request(HOST + '/api/settings'), env, { waitUntil() {} })).json();
  check('no KV → another isolate does NOT see the added IP', (settings.settings.ips || []).length === 0,
    JSON.stringify((settings.settings.ips || []).slice(0, 3)));

  // The page must warn instead of staying silent about it.
  const html = await (await json(env, '/panel')).text();
  check('panel page carries the storage warning banner', html.includes('id="kvWarn"'));
  check('banner text exists in Persian and English',
    html.includes('حافظهٔ پنل وصل نیست') && html.includes('storage is not attached'));
  check('empty-subscription explanation ships in both languages',
    html.includes('هیچ کانفیگی ساخته نشد') && html.includes('no config was built'));
  check('builder view carries the empty-subscription container', html.includes('id="bEmpty"'));
}

/* ── 2: with the KV binding everything is honest AND sticky ─────────────── */
{
  const kv = new FakeKV();
  const env = { CAT_KV: kv, UUID, OPEN_PANEL: 'true' };
  const add = await json(env, '/api/ips', 'POST', { ips: ['172.64.80.1'], source: 'manual' });
  const addBody = await add.json();
  check('KV bound → persisted:true', addBody.persisted === true, JSON.stringify(addBody).slice(0, 120));
  check('KV bound → the IP is in the stored list', (addBody.ips || []).includes('172.64.80.1'), JSON.stringify(addBody.ips));

  const other = (await import(workerPath + '?isolate=3')).default;
  const settings = await (await other.fetch(new Request(HOST + '/api/settings'), env, { waitUntil() {} })).json();
  check('KV bound → a fresh isolate sees the IP', (settings.settings.ips || []).includes('172.64.80.1'),
    JSON.stringify((settings.settings.ips || []).slice(0, 3)));

  const sub = await (await json(env, `/u/${UUID}`)).text();
  check('KV bound → the IP reaches the subscription', sub.includes('172.64.80.1'), sub.slice(0, 90));

  const version = await (await json(env, '/api/version')).json();
  check('KV bound → /api/version reports kv:true', version.kv === true, JSON.stringify(version));
}

/* ── 3: username-protected panel is announced publicly ──────────────────── */
{
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true', PANEL_USER: 'boss' };
  const version = await (await json(env, '/api/version')).json();
  check('PANEL_USER set → /api/version reports needsUser:true', version.needsUser === true, JSON.stringify(version));
  const bad = await json(env, '/api/login', 'POST', { password: UUID });
  check('password-only login is rejected on a username-protected panel (the app bug)',
    bad.status === 401, String(bad.status));
  const good = await json(env, '/api/login', 'POST', { username: 'boss', password: UUID });
  check('username+password login succeeds', good.status === 200, String(good.status));
}

console.log(failures === 0 ? 'KV PERSISTENCE TESTS PASSED' : `KV PERSISTENCE TESTS FAILED (${failures})`);
process.exit(failures === 0 ? 0 : 1);
