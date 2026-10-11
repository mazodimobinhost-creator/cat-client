/**
 * CONTENT TYPES — the worker is both the backend and the frontend generator, so
 * every response it serves has to declare what it is. The owner asked for the
 * HTML/JS to be served "as static content with the right Content-Type" and for
 * the compiled panel file not to be treated as a second, separate frontend
 * build — this suite pins the headers that answer both questions.
 *
 * Run standalone:  node scripts/panels/content-type.test.mjs
 * Against a built artifact:  CAT_PANEL_WORKER=$PWD/dist-panel/catpanel.min.js node …
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const T = (await import(workerPath))._testing;
const HOST = 'content-type.workers.dev';
const UUID = await T.resolveUuid(HOST, {});

const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [], list_complete: true }),
  };
};
const get = (env, path, init) => T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() {} });

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};

console.log('\nContent types — every route declares what it serves\n');

const env = { CAT_KV: memKv(), OPEN_PANEL: 'true' };
const ct = async (path) => (await get(env, path)).headers.get('content-type') || '';

/* The panel page is generated HTML, served as HTML — not as a download and not
   as text. The browser has to render it, which is the whole point. */
{
  const root = await get(env, '/');
  ok('the root redirect never claims a body', root.status === 302 && !(root.headers.get('content-type') || '').includes('html'), root.headers.get('content-type'));

  const page = await ct('/panel');
  ok('/panel is HTML', /^text\/html;\s*charset=utf-8/.test(page), page);
  const login = await get(env, '/login');
  ok('/login is a redirect to the panel, with no body to type', login.status === 302 && login.headers.get('location') === '/panel', login.status + ' ' + login.headers.get('location'));
  ok('the user landing page is HTML', /text\/html/.test(await ct('/info/' + UUID)), await ct('/info/' + UUID));
}

/* Subscriptions: every client expects its own shape. */
{
  ok('/sub is plain text', /^text\/plain;\s*charset=utf-8/.test(await ct('/sub/' + UUID)), await ct('/sub/' + UUID));
  ok('/sub64 is plain text', /text\/plain/.test(await ct('/sub64/' + UUID)), await ct('/sub64/' + UUID));
  ok('/clash is YAML', /^text\/yaml;\s*charset=utf-8/.test(await ct('/clash/' + UUID)), await ct('/clash/' + UUID));
  ok('/singbox is JSON', /^application\/json;\s*charset=utf-8/.test(await ct('/singbox/' + UUID)), await ct('/singbox/' + UUID));
  ok('/qr.svg is an SVG image', /^image\/svg\+xml/.test(await ct('/qr.svg?text=hi')), await ct('/qr.svg?text=hi'));
}

/* API + probe routes are always JSON, including their error answers. */
{
  ok('/health is JSON', /^application\/json/.test(await ct('/health')), await ct('/health'));
  ok('/api/version is JSON', /^application\/json/.test(await ct('/api/version')), await ct('/api/version'));
  ok('an unauthorized API answer is still JSON', /^application\/json/.test(await ct('/api/settings')), await ct('/api/settings'));
  const missing = await get(env, '/definitely-not-a-route');
  ok('an unknown route answers a neutral 404', missing.status === 404, missing.status);
}

/* Caching: nothing that carries a secret or a live setting may be cached by a
   proxy, and the subscriptions carry tokens. */
{
  const sub = (await get(env, '/sub/' + UUID)).headers.get('cache-control') || '';
  ok('subscription responses are not cacheable', /no-store/.test(sub), sub);
  const panel = (await get(env, '/panel')).headers.get('cache-control') || '';
  ok('the panel page is not cacheable', /no-store/.test(panel), panel);
}

console.log('\ncontent types: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
