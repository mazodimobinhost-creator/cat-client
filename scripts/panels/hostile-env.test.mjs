/**
 * HOSTILE ENVIRONMENT — «Error 1101: Worker threw exception» audit.
 *
 * The owner hit Cloudflare's Error 1101 page and asked for two things: find
 * every place that can crash because a variable is undefined / a KV or env
 * binding is missing / a browser global (window, document) leaked into Worker
 * scope — and make a request-time crash answer with a JSON report carrying the
 * message and the stack instead of the opaque 1101 page.
 *
 * This suite pins the first half (nothing throws in a bare or hostile env) and
 * the second half (the report appears for the owner, never for a stranger),
 * because both are invisible locally and only fail in production.
 *
 * Run standalone:  node scripts/panels/hostile-env.test.mjs
 * Against a built artifact:  CAT_PANEL_WORKER=$PWD/dist-panel/catpanel.stealth.js node …
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';
import { readFileSync } from 'node:fs';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;
const sourcePath = new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url);

const mod = await import(workerPath);
const T = mod._testing;
const HOST = 'hostile-env.workers.dev';
const UUID = await T.resolveUuid(HOST, {});
const worker = mod.default;

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
    list: async () => ({ keys: [...m.keys()].map((name) => ({ name })), list_complete: true }),
  };
};

/** Every public route, in one table so a missing guard shows up as a table row. */
const ROUTES = [
  ['GET', '/'], ['GET', '/panel'], ['GET', '/login'], ['GET', '/health'], ['GET', '/api/health'],
  ['GET', '/api/version'], ['GET', '/api/settings'], ['GET', '/api/users'], ['GET', '/api/ips'],
  ['GET', '/sub/' + UUID], ['GET', '/sub64/' + UUID], ['GET', '/clash/' + UUID],
  ['GET', '/singbox/' + UUID], ['GET', '/info/' + UUID], ['GET', '/qr.svg?text=hi'],
  ['GET', '/dns-query?dns=AAABAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE'], ['GET', '/nope'],
  ['POST', '/api/login'], ['PUT', '/api/settings'], ['DELETE', '/api/users'],
];

/** Fire the whole table at a worker instance; returns the routes that THREW. */
async function sweep(env, label) {
  const threw = [];
  const statuses = {};
  for (const [method, path] of ROUTES) {
    const init = { method };
    if (method !== 'GET') init.body = '{}';
    try {
      const res = await worker.fetch(new Request('https://' + HOST + path, init), env, { waitUntil() {} });
      statuses[path] = res.status;
    } catch (e) {
      threw.push(path + ' → ' + (e && e.message));
    }
  }
  ok(label + ': no route throws out of fetch()', threw.length === 0, threw.join(' | '));
  return statuses;
}

console.log('\nHostile environment — a worker that must never 1101 on a missing binding\n');

/* ---------- A) a bare paste: no bindings, no variables at all ---------- */
{
  const statuses = await sweep({}, 'no bindings at all');
  await sweep(undefined, 'env argument omitted entirely');
  ok('…and the panel route still answers its setup screen', statuses['/panel'] === 200, statuses['/panel']);
  ok('…the health probe still answers ok', statuses['/health'] === 200, statuses['/health']);
  ok('…an owner-only API route answers 401, not 500', statuses['/api/settings'] === 401, statuses['/api/settings']);
  ok('…an unknown route is a neutral 404', statuses['/nope'] === 404, statuses['/nope']);
}

/* ---------- B) KV bound but broken: every call throws ---------- */
{
  const boom = () => { throw new Error('KV is down'); };
  const broken = { CAT_KV: { get: boom, put: boom, list: boom, delete: boom } };
  const statuses = await sweep(broken, 'CAT_KV that throws on every call');
  ok('…the panel still renders (a broken KV is not a crash)', statuses['/panel'] === 200, statuses['/panel']);
}

/* ---------- C) KV that returns junk that is not JSON ---------- */
{
  const junk = {
    CAT_KV: {
      get: async () => 'not json {{', put: async () => {}, delete: async () => {},
      list: async () => ({ keys: [], list_complete: true }),
    },
  };
  const statuses = await sweep(junk, 'CAT_KV returning junk');
  ok('…the panel still renders', statuses['/panel'] === 200, statuses['/panel']);
}

/* ---------- D) half-configured env values ---------- */
{
  const env = { CAT_KV: null, UUID: '', PANEL_PASSWORD: '', OPEN_PANEL: null, OPEN_SUB: undefined, PANEL_USER: 0 };
  const statuses = await sweep(env, 'null/undefined/empty env values');
  ok('…the panel still renders', statuses['/panel'] === 200, statuses['/panel']);
}

/* ---------- E) a crash must answer with a report, and only to the owner ---------- */
{
  // A request whose headers getter throws is a deterministic request-time crash:
  // handleRequest() reads headers long before any route logic.
  const boomRequest = (path) => ({
    url: 'https://' + HOST + path,
    method: 'GET',
    get headers() { throw new Error('boom headers'); },
  });

  T.kvCacheClear();
  const freshEnv = {};
  const fresh = await worker.fetch(boomRequest('/api/users'), freshEnv, { waitUntil() {} });
  const freshBody = await fresh.json();
  ok('a request-time crash answers 500, not an escaped throw', fresh.status === 500, fresh.status);
  ok('…with a JSON crash report instead of the opaque 1101 page', freshBody.error === 'panel_crash', JSON.stringify(freshBody).slice(0, 90));
  ok('…carrying the message', /boom headers/.test(freshBody.message || ''), freshBody.message);
  ok('…and a real stack (a string, like err.stack, plus a split copy)',
    typeof freshBody.stack === 'string' && /boom headers/.test(freshBody.stack) &&
      Array.isArray(freshBody.stackLines) && freshBody.stackLines.length > 0 &&
      /boom headers/.test(freshBody.stackLines.join('\n')),
    typeof freshBody.stack);
  ok('…and it names the version so a stale deploy is visible', freshBody.version === T.CAT_PANEL_VERSION, freshBody.version);
  // The exact shape: a person on a phone reads message + stack, a script reads
  // ok/success/error; nothing here may be missing or the wrong type.
  ok('…with the whole documented shape',
    freshBody.ok === false && freshBody.success === false && freshBody.error === 'panel_crash' &&
      typeof freshBody.title === 'string' && freshBody.title.length > 0 &&
      typeof freshBody.message === 'string' && freshBody.message.length > 0 &&
      typeof freshBody.at === 'string' && typeof freshBody.method === 'string' &&
      Array.isArray(freshBody.recent) && typeof freshBody.hint === 'string',
    JSON.stringify(Object.keys(freshBody)));
  ok('…as application/json and never cached',
    /^application\/json/.test(fresh.headers.get('content-type') || '') &&
      /no-store/.test(fresh.headers.get('cache-control') || ''),
    fresh.headers.get('content-type'));

  // Configured panel (password exists) → a stranger must NOT get the report.
  const kv = memKv();
  const env = { CAT_KV: kv };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.sha256Hex('owner-secret') });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();

  const strangerApi = await worker.fetch(boomRequest('/api/users'), env, { waitUntil() {} });
  const strangerApiBody = await strangerApi.json();
  ok('configured panel + stranger + /api crash → plain internal error, no stack',
    strangerApi.status === 500 && strangerApiBody.error === 'internal' && !strangerApiBody.stack,
    JSON.stringify(strangerApiBody).slice(0, 90));

  const strangerPage = await worker.fetch(boomRequest('/panel'), env, { waitUntil() {} });
  const pageBody = await strangerPage.text();
  ok('configured panel + stranger + page crash → the neutral page, nothing about the panel',
    !/panel_crash/.test(pageBody) && !/boom headers/.test(pageBody) && !pageBody.includes(T.CAT_PANEL_VERSION), strangerPage.status);

  const forced = await worker.fetch(boomRequest('/api/users'), Object.assign({ PANEL_DIAG: '1' }, env), { waitUntil() {} });
  ok('PANEL_DIAG=1 forces the report for everyone (the escape hatch)',
    (await forced.json()).error === 'panel_crash');

  const offBody = await (await worker.fetch(boomRequest('/api/users'), Object.assign({ PANEL_DIAG: '0' }, freshEnv), { waitUntil() {} })).text();
  ok('PANEL_DIAG=0 suppresses it even in the setup state', JSON.parse(offBody).error === 'internal');
  ok('…and the neutral API fallback leaks no error text', !offBody.includes('boom headers'), offBody.slice(0, 80));

  // The URL query is dropped from the report: ?p=<uuid> must never be echoed.
  const withQuery = await worker.fetch(boomRequest('/api/users?p=' + UUID), freshEnv, { waitUntil() {} });
  const queryBody = await withQuery.text();
  ok('the report never echoes the query string (the one-tap password lives there)',
    !queryBody.includes(UUID));
}

/* ---------- F) /api/diag — the owner's own health report ---------- */
{
  const kv = memKv();
  const env = { CAT_KV: kv, OPEN_PANEL: 'true' };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.sha256Hex('owner-secret') });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();

  const anon = await T.handleRequest(new Request('https://' + HOST + '/api/diag'), env, { waitUntil() {} });
  ok('/api/diag is owner-only (anonymous gets 401)', anon.status === 401, anon.status);

  const login = await T.handleRequest(
    new Request('https://' + HOST + '/api/login', { method: 'POST', body: JSON.stringify({ password: 'owner-secret' }), headers: { 'content-type': 'application/json' } }),
    env, { waitUntil() {} });
  const cookie = (login.headers.get('set-cookie') || '').split(';')[0];
  ok('the owner can sign in', login.status === 200 && cookie.startsWith('cat_session='), login.status);

  const diag = await T.handleRequest(new Request('https://' + HOST + '/api/diag', { headers: { cookie } }), env, { waitUntil() {} });
  const body = await diag.json();
  ok('/api/diag answers the owner', diag.status === 200 && body.ok === true, diag.status);
  ok('…it proves KV really reads and writes right now', body.kv === 'read+write ok', body.kv);
  ok('…it lists the bindings this isolate can see', Array.isArray(body.bindings) && body.bindings.includes('CAT_KV'), JSON.stringify(body.bindings));
  ok('…it reports the running version', body.version === T.CAT_PANEL_VERSION, body.version);
  ok('…and it never returns the master UUID', !diag.headers.get('x') && !JSON.stringify(body).includes(UUID));

  // A KV that refuses every call: the owner still reaches diag with the panel
  // key (x-cat-key = the UUID while no password was ever set) — and the report
  // must shout FAILED instead of pretending KV is fine.
  const kvBoom = () => { throw new Error('KV permission denied'); };
  const brokenKv = { CAT_KV: { get: kvBoom, put: kvBoom, delete: kvBoom, list: kvBoom } };
  T.kvCacheClear(); // one isolate serves one env; the suite swaps envs, so drop the read cache
  const diagBroken = await T.handleRequest(
    new Request('https://' + HOST + '/api/diag', { headers: { 'x-cat-key': UUID } }), brokenKv, { waitUntil() {} });
  const brokenBody = await diagBroken.json();
  ok('a KV that refuses every call is REPORTED as FAILED, not swallowed',
    diagBroken.status === 200 && /FAILED/.test(brokenBody.kv || ''),
    diagBroken.status + ' ' + JSON.stringify(brokenBody).slice(0, 140));

  // No binding at all must say so out loud — that is the sentence the owner acts on.
  T.kvCacheClear();
  const noKv = await T.handleRequest(
    new Request('https://' + HOST + '/api/diag', { headers: { 'x-cat-key': UUID } }), {}, { waitUntil() {} });
  ok('a missing KV binding is named, not hidden', /NOT BOUND/.test((await noKv.json()).kv || ''));
}

/* ---------- F2) /api/selfcheck — the owner can prove the handler works ---------- */
{
  const kv = memKv();
  const env = { CAT_KV: kv };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.sha256Hex('owner-secret') });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();

  const anon = await T.handleRequest(new Request('https://' + HOST + '/api/selfcheck'), env, { waitUntil() {} });
  ok('/api/selfcheck is owner-only (a stranger cannot even reach the throw)',
    anon.status === 401, anon.status);

  const login = await T.handleRequest(
    new Request('https://' + HOST + '/api/login', { method: 'POST', body: JSON.stringify({ password: 'owner-secret' }), headers: { 'content-type': 'application/json' } }),
    env, { waitUntil() {} });
  const cookie = (login.headers.get('set-cookie') || '').split(';')[0];

  const alive = await T.handleRequest(new Request('https://' + HOST + '/api/selfcheck', { headers: { cookie } }), env, { waitUntil() {} });
  const aliveBody = await alive.json();
  ok('the owner gets a plain health answer without the flag',
    alive.status === 200 && aliveBody.ok === true && aliveBody.version === T.CAT_PANEL_VERSION,
    JSON.stringify(aliveBody).slice(0, 90));

  // The whole point: this throws inside the worker on purpose. request.headers is
  // a real Headers here, because the owner gate reads the session cookie first.
  const thrown = await (await worker.fetch({
    url: 'https://' + HOST + '/api/selfcheck?throw=1',
    method: 'GET',
    headers: new Headers({ cookie }),
  }, env, { waitUntil() {} })).json();
  ok('?throw=1 turns into the JSON crash report (this is the 1101 test)',
    thrown.error === 'panel_crash' && thrown.title === 'Worker Exception Handled' &&
      /PANEL SELF-TEST/.test(thrown.message) && /PANEL SELF-TEST/.test(thrown.stack),
    JSON.stringify(thrown.message).slice(0, 80));
  ok('…and the report names the route it happened on', thrown.at === '/api/selfcheck', thrown.at);

  // A stranger asking for the same throw is refused by the owner gate BEFORE the
  // throw: nobody can force a report out of someone else's panel.
  const stranger = await T.handleRequest(new Request('https://' + HOST + '/api/selfcheck?throw=1'), env, { waitUntil() {} });
  const strangerBody = await stranger.text();
  ok('a stranger cannot force the throw', stranger.status === 401 && !/PANEL SELF-TEST/.test(strangerBody), stranger.status);
}

/* ---------- G) no browser global may appear in Worker scope ---------- */
{
  // The panel page the worker *serves* is one big template string full of
  // document/window calls — that is frontend code and it belongs there. A real
  // reference in the worker's own scope would be invisible locally and fatal on
  // the edge, so the check parses the file and looks at identifiers only: an AST
  // cannot confuse «inside a string» with «executed here». (acorn ships with
  // javascript-obfuscator, which the build already needs.)
  const { parse } = await import('acorn');
  const src = readFileSync(sourcePath, 'utf8');
  const ast = parse(src, { ecmaVersion: 'latest', sourceType: 'module' });
  const FORBIDDEN = new Set(['window', 'document', 'navigator', 'localStorage', 'sessionStorage', 'alert']);
  const hits = [];
  const visit = (node, parent) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) { for (const child of node) visit(child, parent); return; }
    if (node.type === 'Identifier' && FORBIDDEN.has(node.name)) {
      const isProperty = parent && parent.type === 'MemberExpression' && parent.property === node && !parent.computed;
      const isKey = parent && (parent.type === 'Property' || parent.type === 'MethodDefinition') && parent.key === node && !parent.computed;
      if (!isProperty && !isKey) hits.push(node.name + ' @' + node.start);
    }
    for (const key of Object.keys(node)) {
      if (key === 'type' || key === 'start' || key === 'end') continue;
      visit(node[key], node);
    }
  };
  visit(ast, null);
  ok('the worker code (its own scope, not the page it serves) never references a browser global',
    hits.length === 0, hits.slice(0, 3).join(', '));

  // The same parse proves the file is one valid module — a truncated paste is
  // exactly what turns into 1101 on every route with nothing to catch it.
  ok('the source parses as a single valid ES module', ast.type === 'Program' && ast.sourceType === 'module');
}

/* ---------- H) the ERROR PATH itself must survive hostile thrown values ---------- */
{
  // kvGetJson() consults its process-wide cache BEFORE the binding, which is
  // correct in production (one isolate = one Worker = one env) but means this
  // suite must drop the cache whenever it switches envs — otherwise the empty env
  // below inherits the passwordHash written by the sections above and the setup
  // state (which is what makes the report reachable) disappears.
  T.kvCacheClear();
  // Whatever a request (or a binding) throws at us — a bare string, null, a Proxy
  // that throws on every property read, an object whose message getter throws —
  // fetch() must still answer a JSON 500. If any of this escaped, the edge would
  // print the opaque «Error 1101» page instead, which is the whole failure this
  // file exists to remove.
  const bare = {};
  const boobyTraps = [
    ['a plain string', () => 'plain string error'],
    ['null', () => null],
    ['undefined', () => undefined],
    ['a number', () => 42],
    ['a symbol', () => Symbol('nope')],
    ['an object without a message', () => ({ code: 7 })],
    ['an object whose message getter throws', () => ({ get message() { throw new Error('inner'); } })],
    ['an object whose toString throws', () => ({ toString() { throw new Error('nope'); }, valueOf() { throw new Error('nope'); } })],
    ['a Proxy that throws on every read', () => new Proxy({}, { get() { throw new Error('proxy get'); } })],
  ];
  for (const [label, make] of boobyTraps) {
    const req = {
      get url() { throw make(); },            // handleRequest() reads url first
      method: 'GET',
      headers: { get: () => null },
    };
    let res = null; let escaped = null;
    try { res = await worker.fetch(req, bare, { waitUntil() {} }); } catch (e) { escaped = e; }
    ok('hostile throw (' + label + '): nothing escapes fetch()', escaped === null,
      escaped && (escaped.message || 'thrown'));
    if (!res) continue;
    const body = await res.text();
    let parsed = null; try { parsed = JSON.parse(body); } catch (e) { /* not JSON */ }
    ok('hostile throw (' + label + '): answers a JSON crash report',
      res.status === 500 && !!parsed && parsed.error === 'panel_crash' && parsed.success === false,
      res.status + ' ' + body.slice(0, 70));
    ok('hostile throw (' + label + '): message stays a readable string',
      !!parsed && typeof parsed.message === 'string' && parsed.message.length > 0 &&
        typeof parsed.stack === 'string',
      parsed && JSON.stringify(parsed.message));
  }

  // A readable URL + a hostile error object on a configured panel: the stranger path
  // must stay neutral (no raw text, no stack) and still be JSON for /api.
  const kv2 = memKv();
  const env2 = { CAT_KV: kv2, PANEL_DIAG: '0' };
  const settings2 = Object.assign({}, await T.readSettings(env2), { passwordHash: await T.sha256Hex('owner-secret') });
  await kv2.put(T.KV_KEYS.settings, JSON.stringify(settings2));
  T.kvCacheClear();
  const neutral = await worker.fetch({
    url: 'https://' + HOST + '/api/users',
    method: 'GET',
    get headers() { throw new Proxy({}, { get() { throw new Error('secret-detail'); } }); },
  }, env2, { waitUntil() {} });
  const neutralBody = await neutral.text();
  ok('PANEL_DIAG=0 + hostile error + /api → neutral JSON, no leak',
    neutral.status === 500 && JSON.parse(neutralBody).error === 'internal' && !neutralBody.includes('secret-detail'),
    neutralBody.slice(0, 80));

  // …and PANEL_DIAG=1 carries everything even when the URL itself is unreadable.
  const forced = await (await worker.fetch({
    get url() { throw new Error('owner-visible detail'); },
    method: 'GET',
    headers: { get: () => null },
  }, Object.assign({}, env2, { PANEL_DIAG: '1' }), { waitUntil() {} })).json();
  ok('PANEL_DIAG=1 forces the full report even for a hostile error',
    forced.error === 'panel_crash' && String(forced.message).includes('owner-visible'),
    JSON.stringify(forced.message));
}

/* ---------- I) WHO gets the stack: owner vs stranger, asserted one by one ---------- */
{
  // A configured panel (a password exists) is the only state where ownership can be
  // proven, so it is the only state where the split matters. `/api/geo` is used as
  // the crash route because it is reached BEFORE the owner gate: that is exactly the
  // situation the rule is about — the crash happens, and the answer depends on WHO
  // asked. `/panel` covers the HTML side.
  const kv = memKv();
  const env = { CAT_KV: kv };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.sha256Hex('owner-secret') });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  const SECRET = 'database exploded at line 42';      // must never reach a stranger
  const UUID_IN_QUERY = '11111111-2222-3333-4444-555555555555';

  /** A request that authenticates like the owner (or not) and then throws on the
      first header read that the route does after the gate. */
  const crashReq = (path, opts = {}) => ({
    url: 'https://' + HOST + path,
    method: 'GET',
    cf: {},
    headers: {
      get(name) {
        const key = String(name).toLowerCase();
        if (key === 'x-cat-key') return opts.key || null;
        if (key === 'cookie' || key === 'authorization') return null;
        if (key === 'upgrade') return opts.upgrade === undefined ? null : opts.upgrade;
        if (opts.crashHeader && key === opts.crashHeader) throw new Error(SECRET);
        return null;
      },
    },
  });

  /* --- the owner, proving ownership with the header the README documents --- */
  T.kvCacheClear();
  const owner = await worker.fetch(crashReq('/api/geo?p=' + UUID_IN_QUERY, { key: 'owner-secret', crashHeader: 'cf-connecting-ip' }), env, { waitUntil() {} });
  const ownerBody = await owner.json();
  ok('owner (x-cat-key) gets the report', owner.status === 500 && ownerBody.error === 'panel_crash', owner.status + ' ' + JSON.stringify(ownerBody).slice(0, 60));
  ok('…with the real message', ownerBody.message === SECRET, JSON.stringify(ownerBody.message));
  ok('…with a stack string and stackLines',
    typeof ownerBody.stack === 'string' && /database exploded/.test(ownerBody.stack) &&
      Array.isArray(ownerBody.stackLines) && ownerBody.stackLines.length > 0);
  ok('…naming the path but NEVER the query string',
    ownerBody.at === '/api/geo' && !JSON.stringify(ownerBody).includes(UUID_IN_QUERY), ownerBody.at);

  /* --- a stranger: nothing but a generic body --- */
  const stranger = await worker.fetch(crashReq('/api/geo', { crashHeader: 'cf-connecting-ip' }), env, { waitUntil() {} });
  const strangerText = await stranger.text();
  ok('stranger on /api gets JSON (no 1101 page) but a generic body',
    stranger.status === 500 && JSON.parse(strangerText).error === 'internal', strangerText.slice(0, 60));
  ok('…with the documented generic message', JSON.parse(strangerText).message === 'unhandled error');
  ok('…no stack, no stackLines key at all', !/stack/.test(strangerText), strangerText.slice(0, 90));
  ok('…no path and no route name', !strangerText.includes('/api/geo'), 'path leaked');
  ok('…no error text', !strangerText.includes('database exploded'), 'message leaked');
  ok('…and not even the panel_crash marker', !/panel_crash|Worker Exception Handled/.test(strangerText));
  ok('…and no version disclosure', !strangerText.includes(T.CAT_PANEL_VERSION), strangerText.slice(0, 90));

  /* --- a stranger on the HTML routes: the neutral page, unchanged --- */
  const contentCrash = (path) => ({
    url: 'https://' + HOST + path,
    method: 'GET',
    cf: {},
    headers: { get(name) { if (String(name).toLowerCase() === 'upgrade') throw new Error(SECRET); return null; } },
  });
  const page = await worker.fetch(contentCrash('/panel'), env, { waitUntil() {} });
  const pageBody = await page.text();
  ok('stranger on a page route gets the neutral answer',
    !/panel_crash/.test(pageBody) && !pageBody.includes(SECRET) && !pageBody.includes('Worker Exception Handled'),
    page.status);

  /* --- PANEL_DIAG is the owner's switch, in both directions --- */
  const forcedOpen = await worker.fetch(crashReq('/api/geo', { crashHeader: 'cf-connecting-ip' }), Object.assign({}, env, { PANEL_DIAG: '1' }), { waitUntil() {} });
  ok('PANEL_DIAG=1 shows the details to everyone (the escape hatch)',
    (await forcedOpen.json()).message === SECRET);
  const forcedShut = await worker.fetch(crashReq('/api/geo', { key: 'owner-secret', crashHeader: 'cf-connecting-ip' }), Object.assign({}, env, { PANEL_DIAG: '0' }), { waitUntil() {} });
  const shutText = await forcedShut.text();
  ok('PANEL_DIAG=0 keeps even the owner in the dark (documented switch)',
    JSON.parse(shutText).error === 'internal' && !shutText.includes(SECRET), shutText.slice(0, 70));

  /* --- the unclassifiable caller is treated as a stranger by crashReportMinimal --- */
  T.kvCacheClear();
  const minimal = await (await T.crashReportMinimal(new Error(SECRET), {})).json();
  ok('crashReportMinimal() with no PANEL_DIAG → generic, byte-for-byte the /api fallback',
    JSON.stringify(minimal) === JSON.stringify({ ok: false, success: false, error: 'internal', message: 'unhandled error' }),
    JSON.stringify(minimal));
  const minimalOpen = await (await T.crashReportMinimal(new Error(SECRET), { PANEL_DIAG: '1' })).json();
  ok('crashReportMinimal() with PANEL_DIAG=1 → the full report (same shape as the owner path)',
    minimalOpen.error === 'panel_crash' && minimalOpen.message === SECRET && typeof minimalOpen.stack === 'string' &&
      minimalOpen.title === 'Worker Exception Handled' && minimalOpen.at === '');
}

console.log('\nhostile env: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
