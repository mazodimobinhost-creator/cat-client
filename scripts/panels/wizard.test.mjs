/**
 * Cat Wizard test harness — fakes the Cloudflare API and checks the whole
 * install flow (token → subdomain → KV → upload → route → health).
 * Usage: node scripts/panels/wizard.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const wizardPath = path.join(here, '../../app/src/main/assets/panels/catclient.wizard.js');
const panelPath = path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', wizardPath], { stdio: 'pipe' });
console.log('✓ wizard syntax check passed');

const mod = await import(wizardPath);
const wizard = mod.default;
const T = mod._testing;
const panelSrc = readFileSync(panelPath, 'utf8');
// Version-proof pins: everything below compares against the real bundled source.
const PANEL_VERSION = (panelSrc.match(/CAT_PANEL_VERSION\s*=\s*'([^']+)'/) || [])[1];
// Simulated normal minified module (release asset / committed snapshot).
// Production panels such as BPB deploy a normal bundled/minified Worker rather
// than a large RC4/control-flow-obfuscated runtime.
const minPanelSrc = "var CAT_PANEL_VERSION = '" + PANEL_VERSION + "';\n" + '/* payload */\n'.repeat(300);
const stalePanelSrc = "var CAT_PANEL_VERSION = '6.60.0';\n" + '/* payload */\n'.repeat(300);
const blocked1101Src = "var CAT_PANEL_VERSION = '6.69.12';\n" + '/* payload */\n'.repeat(300);
const badVersionSrc = "var CAT_PANEL_VERSION = 'not-a-version';\n" + '/* payload */\n'.repeat(300);

let failures = 0;
function check(name, cond, extra) {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); }
}

/* ---------------- fake Cloudflare + GitHub ---------------- */
const state = {
  subdomain: '',
  kv: [],
  scripts: {},
  routes: {},
  calls: [],
  tokenValid: true,
  kvAllowed: true,
  settingsMode: 'normal',
  healthMode: 'ok',        // ok | mismatch | cached | malformed | down | hang | seq
  healthSeq: [],           // consumed one per probe when healthMode === 'seq'
  healthProbes: 0,
  sourceMode: 'ok',        // ok | stale-first | stale-all | bad-version | hang
  raceOnKv: '',            // worker name that "appears" while the KV namespace is created
};
T.__setTimeouts({ cf: 150, source: 150, health: 150 });
T.__setSleep({ early: 1, late: 1 });
const hang = (init) => new Promise((_, reject) => { init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))); });
const json = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json' } });

T.__setFetch(async (input, init = {}) => {
  const url = typeof input === 'string' ? input : input.url;
  const method = (init.method || 'GET').toUpperCase();
  state.calls.push(method + ' ' + url);
  const auth = (init.headers && (init.headers.authorization || init.headers.Authorization)) || '';

  if (url.startsWith('https://github.com/') || url.startsWith('https://raw.githubusercontent.com/') || url.startsWith('https://ghproxy.net/')) {
    if (state.sourceMode === 'hang' && !url.endsWith('catclient.wizard.js')) return hang(init);
    if (url.endsWith('catclient.worker.js') && url.includes('/releases/latest/')) return new Response('nope', { status: 404 });
    if (url.endsWith('catclient.worker.js') || url.endsWith('catpanel.min.js')) {
      if (state.sourceMode === 'stale-all') return new Response(stalePanelSrc, { status: 200 });
      if (state.sourceMode === 'blocked-1101') return new Response(blocked1101Src, { status: 200 });
      if (state.sourceMode === 'bad-version') return new Response(badVersionSrc, { status: 200 });
      if (state.sourceMode === 'stale-first' && url === T.PANEL_SOURCES[0]) return new Response(stalePanelSrc, { status: 200 });
      return new Response(minPanelSrc, { status: 200 });
    }
    if (url.endsWith('catclient.wizard.js')) return new Response(readFileSync(wizardPath, 'utf8'), { status: 200 });
    return new Response('nf', { status: 404 });
  }

  if (url.startsWith('https://api.cloudflare.com/client/v4')) {
    const p = url.slice('https://api.cloudflare.com/client/v4'.length);
    if (auth !== 'Bearer good-token' || !state.tokenValid) return json({ success: false, errors: [{ code: 10000, message: 'Authentication error' }] }, 401);
    if (p === '/user/tokens/verify') return json({ success: true, result: { id: 't1', status: 'active' } });
    if (p.startsWith('/accounts?')) return json({ success: true, result: [{ id: 'acc-1', name: 'Amir Account' }] });
    if (p === '/accounts/acc-1/workers/subdomain' && method === 'GET') {
      return state.subdomain ? json({ success: true, result: { subdomain: state.subdomain } }) : json({ success: false, errors: [{ code: 10007, message: 'not found' }] }, 404);
    }
    if (p === '/accounts/acc-1/workers/subdomain' && method === 'PUT') {
      state.subdomain = JSON.parse(init.body).subdomain;
      return json({ success: true, result: { subdomain: state.subdomain } });
    }
    if (p.startsWith('/accounts/acc-1/storage/kv/namespaces')) {
      if (!state.kvAllowed) return json({ success: false, errors: [{ code: 10000, message: 'Authentication error' }] }, 403);
      if (method === 'GET') return json({ success: true, result: state.kv });
      if (method === 'POST') { const ns = { id: 'kv-' + (state.kv.length + 1), title: JSON.parse(init.body).title }; state.kv.push(ns); if (state.raceOnKv) state.scripts[state.raceOnKv] = { bindings: [{ type: 'secret_text', name: 'THEIRS' }], script: 'theirs', meta: {} }; return json({ success: true, result: ns }); }
    }
    const scriptMatch = p.match(/^\/accounts\/acc-1\/workers\/scripts\/([a-z0-9-]+)(\/settings|\/subdomain)?$/);
    if (scriptMatch) {
      const name = scriptMatch[1];
      const sub = scriptMatch[2] || '';
      if (sub === '/settings' && method === 'GET') {
        if (state.settingsMode === 'network') throw new TypeError('network down');
        if (state.settingsMode === 'timeout') return hang(init);
        if (state.settingsMode === 'malformed') return json({ success: true, result: {} });
        if (/^(401|403|500)$/.test(state.settingsMode)) return json({ success: false, errors: [{ message: 'settings error' }] }, Number(state.settingsMode));
        const s = state.scripts[name];
        return s ? json({ success: true, result: { bindings: s.bindings } }) : json({ success: false, errors: [{ code: 10007, message: 'script not found' }] }, 404);
      }
      if (sub === '/subdomain' && method === 'POST') { state.routes[name] = JSON.parse(init.body).enabled; return json({ success: true, result: { enabled: true } }); }
      if (sub === '' && method === 'PUT') {
        const form = init.body;
        const meta = JSON.parse(await form.get('metadata').text());
        const script = await form.get('worker.js').text();
        state.scripts[name] = { bindings: meta.bindings, script, meta };
        return json({ success: true, result: { id: name } });
      }
    }
    return json({ success: false, errors: [{ message: 'unhandled ' + method + ' ' + p }] }, 500);
  }

  if (new URL(url).pathname === '/health') {
    const name = new URL(url).hostname.split('.')[0];
    if (!state.scripts[name]) return new Response('nf', { status: 404 });
    state.healthProbes++;
    const isWizard = state.scripts[name].script.includes('CAT_WIZARD_VERSION');
    const real = isWizard ? T.CAT_WIZARD_VERSION : PANEL_VERSION;
    let mode = state.healthMode;
    if (mode === 'seq') mode = state.healthSeq.length ? state.healthSeq.shift() : 'ok';
    if (mode === 'hang') return hang(init);
    if (mode === 'down') return new Response('error code: 1101', { status: 500 });
    if (mode === 'malformed') return new Response('<html>not json</html>', { status: 200 });
    if (mode === 'mismatch') return json({ ok: true, version: '6.0.1' });
    if (mode === 'cached') return new Response(JSON.stringify({ ok: true, version: real }), { status: 200, headers: { 'content-type': 'application/json', 'cf-cache-status': 'HIT', age: '120' } });
    return json({ ok: true, version: real });
  }
  return new Response('unhandled ' + url, { status: 500 });
});

const HOST = 'cat-wizard.demo.workers.dev';
function req(url, { method = 'GET', body, env = {} } = {}) {
  const headers = { Host: HOST, 'cf-connecting-ip': '1.2.3.' + Math.floor(Math.random() * 250) };
  if (body !== undefined) headers['content-type'] = 'application/json';
  return wizard.fetch(new Request('https://' + HOST + url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env, { waitUntil() {} });
}
async function installEvents(body, env = {}) {
  const res = await req('/api/install', { method: 'POST', body, env });
  const text = await res.text();
  const events = text.split('\n').filter(Boolean).map((l) => JSON.parse(l));
  return { res, events, done: events.find((e) => e.done) };
}

/* 1. token template URL */
{
  check('token template points at profile/api-tokens', T.TOKEN_TEMPLATE_URL.startsWith('https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys='));
  const decoded = JSON.parse(decodeURIComponent(new URL(T.TOKEN_TEMPLATE_URL).searchParams.get('permissionGroupKeys')));
  check('template has Workers Scripts edit', decoded.some((p) => p.key === 'workers_scripts' && p.type === 'edit'));
  check('template has Workers KV edit', decoded.some((p) => p.key === 'workers_kv_storage' && p.type === 'edit'));
  check('template has Account Settings read (needed to list accounts)', decoded.some((p) => p.key === 'account_settings' && p.type === 'read'));
  check('template scoped to all accounts + zones', T.TOKEN_TEMPLATE_URL.includes('accountId=*') && T.TOKEN_TEMPLATE_URL.includes('zoneId=all'));
  const r = await req('/token');
  check('/token redirects to the template', r.status === 302 && r.headers.get('location') === T.TOKEN_TEMPLATE_URL);
}

/* 2. page + health */
{
  const r = await req('/');
  const html = await r.text();
  check('/ serves the wizard page', r.status === 200 && html.includes('Cat Wizard') && html.includes('id="installBtn"'));
  check('page embeds the token button with template URL', html.includes('id="tokenBtn"') && html.includes('permissionGroupKeys='));
  check('page is Persian by default with EN toggle', html.includes('lang="fa"') && html.includes('id="langBtn"'));
  check('page has no invite field without WIZARD_PASSWORD', !html.includes('id="invite"'));
  const locked = await (await req('/', { env: { WIZARD_PASSWORD: 'secret' } })).text();
  check('page shows invite field with WIZARD_PASSWORD', locked.includes('id="invite"'));
  new Function(T.clientJs()); // parses
  check('client script parses', true);
  const h = await (await req('/health')).json();
  check('/health reports wizard version', h.ok && h.wizard === 'cat-wizard' && h.version === T.CAT_WIZARD_VERSION);
}

/* 3. verify */
{
  const bad = await (await req('/api/verify', { method: 'POST', body: { token: 'bad' } })).json();
  check('verify rejects a bad token', bad.ok === false && bad.error === 'invalid-token');
  const good = await (await req('/api/verify', { method: 'POST', body: { token: 'good-token' } })).json();
  check('verify accepts the token and lists the account', good.ok === true && good.accounts[0].id === 'acc-1' && good.accounts[0].name === 'Amir Account');
  check('verify reports scopes', good.scopes && good.scopes.workers === true && good.scopes.kv === true);
  const missing = await req('/api/verify', { method: 'POST', body: {} });
  check('verify without token → 400', missing.status === 400);
  const inv = await req('/api/verify', { method: 'POST', body: { token: 'good-token' }, env: { WIZARD_PASSWORD: 'secret' } });
  check('invite code enforced', inv.status === 403);
  const invOk = await req('/api/verify', { method: 'POST', body: { token: 'good-token', invite: 'secret' }, env: { WIZARD_PASSWORD: 'secret' } });
  check('invite code accepted', invOk.status === 200);
}

/* 4. full install (fresh account: no subdomain, no KV, no worker) */
let first;
{
  const { res, events, done } = await installEvents({ token: 'good-token', workerName: 'My Panel!!' });
  check('install streams NDJSON', (res.headers.get('content-type') || '').includes('ndjson') && events.length > 5);
  check('install finishes ok', done && done.result && done.result.ok === true, JSON.stringify(done));
  first = done.result;
  check('worker name slugified', first.workerName === 'my-panel');
  check('subdomain auto-created', /^edge-[a-z0-9]{8}$/.test(state.subdomain) && first.workerUrl === 'https://my-panel.' + state.subdomain + '.workers.dev');
  check('KV namespace created + bound', first.kvBound && state.kv.length === 1 && state.kv[0].title === 'my-panel-catpanel');
  const s = state.scripts['my-panel'];
  check('panel script uploaded with the version marker', s && PANEL_VERSION && s.script.includes("CAT_PANEL_VERSION = '" + PANEL_VERSION + "'"));
  check('branch minified snapshot is preferred over releases/latest', events.some((e) => e.step === 'source' && e.level === 'ok' && e.data && e.data.url.includes('raw.githubusercontent.com') && e.data.url.includes('catpanel.min.js')));
  check('normal minified artifact accepted', !events.some((e) => e.step === 'source' && e.level === 'warn'));
  const bind = Object.fromEntries(s.bindings.map((b) => [b.name, b]));
  check('UUID bound as plain_text', bind.UUID && bind.UUID.type === 'plain_text' && /^[0-9a-f-]{36}$/.test(bind.UUID.text));
  check('CAT_KV bound', bind.CAT_KV && bind.CAT_KV.type === 'kv_namespace' && bind.CAT_KV.namespace_id === 'kv-1');
  check('PANEL_PASSWORD is bound as secret_text (never plain_text)', bind.PANEL_PASSWORD && bind.PANEL_PASSWORD.type === 'secret_text' && !s.bindings.some((b) => b.type === 'plain_text' && b.name === 'PANEL_PASSWORD'));
  check('CF_API_TOKEN + CF_ACCOUNT_ID + CF_WORKER_NAME bound for direct in-panel self-update', bind.CF_API_TOKEN && bind.CF_API_TOKEN.type === 'secret_text' && bind.CF_ACCOUNT_ID && bind.CF_ACCOUNT_ID.text === 'acc-1' && bind.CF_WORKER_NAME && bind.CF_WORKER_NAME.text === 'my-panel');
  check('generated password is independent of the UUID and long enough', bind.PANEL_PASSWORD.text.length >= 24 && bind.PANEL_PASSWORD.text !== bind.UUID.text && !bind.PANEL_PASSWORD.text.includes(bind.UUID.text));
  check('no OPEN_PANEL / OPEN_SUB binding is set by the Wizard', !bind.OPEN_PANEL && !bind.OPEN_SUB);
  check('compat date + flags come from the single Wizard constants (no nodejs_compat)', s.meta.compatibility_date === T.COMPAT_DATE && JSON.stringify(s.meta.compatibility_flags) === JSON.stringify(T.COMPAT_FLAGS) && !s.meta.compatibility_flags.includes('nodejs_compat'));
  check('route enabled', state.routes['my-panel'] === true);
  check('online after health probe', first.online === true && first.panelVersion === PANEL_VERSION);
  check('result: password is the generated secret, not the UUID', first.password === bind.PANEL_PASSWORD.text && first.password !== first.uuid && first.customPassword === false && first.passwordGenerated === true);
  check('result: panel URL carries no password / UUID in the query string', !/[?&]p=/.test(first.panelUrl) && !first.panelUrl.includes(first.password) && !first.panelUrl.includes(first.uuid));
  check('password + token never appear in any streamed log event', !JSON.stringify(events.filter((e) => !e.done)).includes(first.password) && !JSON.stringify(events.filter((e) => !e.done)).includes('good-token'));
  check('result: sub URL /sub/<uuid>', first.subUrl === first.workerUrl + '/sub/' + first.uuid);
  check('result: deep link for the app', first.deepLink.startsWith('catclient://add-sub?url=') && decodeURIComponent(first.deepLink).includes(first.subUrl));
}

/* 5. existing workers fail closed before KV/upload/route mutation */
{
  const beforeScript = state.scripts['my-panel'];
  const beforeKv = state.kv.length;
  const beforeCalls = state.calls.length;
  const { done } = await installEvents({ token: 'good-token', workerName: 'my-panel', password: 'Hunter2!' });
  const calls = state.calls.slice(beforeCalls);
  check('existing worker update is blocked', done && done.ok === false && /already exists|preserving/i.test(done.msg), JSON.stringify(done));
  check('existing script is untouched', state.scripts['my-panel'] === beforeScript);
  check('existing path creates no KV', state.kv.length === beforeKv && !calls.some((x) => x.startsWith('POST https://api.cloudflare.com/client/v4/accounts/acc-1/storage/kv')));
  check('existing path performs no PUT upload', !calls.some((x) => x.includes('/workers/scripts/my-panel') && x.startsWith('PUT ')));
  check('existing path does not toggle route', !calls.some((x) => x.includes('/workers/scripts/my-panel/subdomain') && x.startsWith('POST ')));
}

/* 5a. HTTP 200 with an empty binding list is still an existing Worker */
{
  state.scripts.emptyexisting = { bindings: [], script: 'existing', meta: {} };
  const beforeCalls = state.calls.length;
  const { done } = await installEvents({ token: 'good-token', workerName: 'emptyexisting' });
  const calls = state.calls.slice(beforeCalls);
  check('existing worker with bindings=[] is blocked', done && done.ok === false && /already exists/i.test(done.msg), JSON.stringify(done));
  check('bindings=[] path performs no PUT', !calls.some((x) => x.includes('/workers/scripts/emptyexisting') && x.startsWith('PUT ')));
}

/* 5b. ambiguous existence checks all fail closed before mutation */
{
  for (const mode of ['401', '403', '500', 'network', 'malformed', 'timeout']) {
    state.settingsMode = mode;
    const name = 'guard-' + mode;
    const beforeKv = state.kv.length;
    const beforeCalls = state.calls.length;
    const { done } = await installEvents({ token: 'good-token', workerName: name });
    const calls = state.calls.slice(beforeCalls);
    check('settings ' + mode + ' is inconclusive', done && done.ok === false && /inconclusive/i.test(done.msg), JSON.stringify(done));
    check('settings ' + mode + ' does not create KV', state.kv.length === beforeKv);
    check('settings ' + mode + ' does not upload', !calls.some((x) => x.includes('/workers/scripts/' + name) && x.startsWith('PUT ')));
    check('settings ' + mode + ' does not toggle route', !calls.some((x) => x.includes('/workers/scripts/' + name + '/subdomain') && x.startsWith('POST ')));
  }
  state.settingsMode = 'normal';
}


/* 5c. race reduction: a Worker that appears after the first 404 must not be overwritten */
{
  state.raceOnKv = 'race-x';
  const beforeCalls = state.calls.length;
  const { done } = await installEvents({ token: 'good-token', workerName: 'race-x' });
  const calls = state.calls.slice(beforeCalls);
  check('worker that appears between 404 and upload is not overwritten', done && done.ok === false && /appeared|preserve/i.test(done.msg), JSON.stringify(done));
  check('race: the other worker is untouched', state.scripts['race-x'].script === 'theirs' && state.scripts['race-x'].bindings[0].name === 'THEIRS');
  check('race: no PUT upload and no route toggle', !calls.some((x) => x.includes('/workers/scripts/race-x') && (x.startsWith('PUT ') || x.includes('/subdomain') && x.startsWith('POST '))));
  state.raceOnKv = '';
}

/* 5d. source gate: stale / malformed / hung sources are never deployed */
{
  T.sourceCache.clear(); state.sourceMode = 'stale-first';
  const a = await installEvents({ token: 'good-token', workerName: 'src-a' });
  const srcEvent = a.events.find((e) => e.step === 'source' && e.level === 'ok');
  check('stale first mirror is skipped, a current one is used', a.done.result && a.done.result.ok && srcEvent.data.url !== T.PANEL_SOURCES[0] && srcEvent.data.version === PANEL_VERSION, JSON.stringify(a.done));
  T.sourceCache.clear(); state.sourceMode = 'stale-all';
  let beforeCalls = state.calls.length;
  const b = await installEvents({ token: 'good-token', workerName: 'src-b' });
  check('only stale sources → install refused, nothing uploaded', b.done.ok === false && /stale source/.test(b.done.msg) && !state.scripts['src-b'] && !state.calls.slice(beforeCalls).some((x) => x.startsWith('PUT ') && x.includes('/workers/scripts/')), JSON.stringify(b.done));
  T.sourceCache.clear(); state.sourceMode = 'bad-version';
  const c = await installEvents({ token: 'good-token', workerName: 'src-c' });
  check('malformed source version → install refused', c.done.ok === false && /unknown or malformed/.test(c.done.msg) && !state.scripts['src-c'], JSON.stringify(c.done));
  T.sourceCache.clear(); state.sourceMode = 'blocked-1101';
  const c1101 = await installEvents({ token: 'good-token', workerName: 'src-1101' });
  check('blocked 1101 version 6.69.12 → install refused, nothing uploaded', c1101.done.ok === false && /blocked 1101 version v6\.69\.12/.test(c1101.done.msg) && !state.scripts['src-1101'], JSON.stringify(c1101.done));
  T.sourceCache.clear(); state.sourceMode = 'hang';
  const d = await installEvents({ token: 'good-token', workerName: 'src-d' });
  check('hung source download → timeout, install refused', d.done.ok === false && /timed out/.test(d.done.msg) && !state.scripts['src-d'], JSON.stringify(d.done));
  state.sourceMode = 'ok'; T.sourceCache.clear();
  check('PANEL_MIN_VERSION never exceeds the bundled source version', T.compareVersions(T.PANEL_MIN_VERSION, PANEL_VERSION) <= 0, T.PANEL_MIN_VERSION + ' vs ' + PANEL_VERSION);
  check('compareVersions handles numeric order and malformed input', T.compareVersions('6.68.0', '6.9.0') === 1 && T.compareVersions('6.68', '6.68.0') === 0 && T.compareVersions('x', '1') === null);
}

/* 5e. health verification: version-exact, cache-proof, 3 consecutive, fail-safe on failure */
{
  const cases = [
    ['mismatch', 'version mismatch'],
    ['cached', 'cached response'],
    ['malformed', 'malformed health body'],
    ['down', 'HTTP 500'],
    ['hang', 'timed out'],
  ];
  for (const [mode, needle] of cases) {
    state.healthMode = mode; T.sourceCache.clear();
    const name = 'hf-' + mode; const beforeCalls = state.calls.length; const kvBefore = state.kv.length;
    const { done, events } = await installEvents({ token: 'good-token', workerName: name });
    const calls = state.calls.slice(beforeCalls);
    check('health ' + mode + ' → install not reported online', done.ok === false && !done.result, JSON.stringify(done));
    check('health ' + mode + ' → reason is reported (' + needle + ')', events.some((e) => e.step === 'check' && e.level === 'error' && e.msg.includes(needle)), JSON.stringify(events.filter((e) => e.step === 'check')));
    check('health ' + mode + ' → only the new route is disabled', state.routes[name] === false);
    check('health ' + mode + ' → worker, secrets and KV are kept (no delete)', !!state.scripts[name] && state.scripts[name].bindings.some((b) => b.name === 'PANEL_PASSWORD' && b.type === 'secret_text') && state.scripts[name].bindings.some((b) => b.name === 'CAT_KV') && state.kv.length >= kvBefore && !calls.some((x) => x.startsWith('DELETE ')));
  }
  // exactly three consecutive healthy answers are required, and a failure resets the counter
  state.healthMode = 'seq'; state.healthSeq = ['ok', 'ok', 'down', 'ok', 'ok', 'ok']; state.healthProbes = 0; T.sourceCache.clear();
  const r1 = await installEvents({ token: 'good-token', workerName: 'hs-seq' });
  check('a failure between healthy answers resets the counter (6 probes, not 3)', r1.done.result && r1.done.result.ok && state.healthProbes === 6, 'probes=' + state.healthProbes);
  state.healthMode = 'ok'; state.healthProbes = 0; T.sourceCache.clear();
  const r2 = await installEvents({ token: 'good-token', workerName: 'hs-ok' });
  check('three healthy answers with the exact expected version are accepted (3 probes)', r2.done.result && r2.done.result.ok && state.healthProbes === 3 && r2.done.result.panelVersion === PANEL_VERSION, 'probes=' + state.healthProbes);
  check('route stays enabled after a verified install', state.routes['hs-ok'] === true);
  check('no DELETE request was made anywhere during the whole suite', !state.calls.some((x) => x.startsWith('DELETE ')));
}

/* 5f. password generation + custom password */
{
  const samples = new Set(Array.from({ length: 50 }, () => T.randomPassword(24)));
  check('randomPassword: 24 chars, unambiguous alphabet, no repeats in 50 samples', samples.size === 50 && [...samples].every((p) => /^[A-HJ-NP-Za-km-z2-9]{24}$/.test(p)));
  T.sourceCache.clear();
  const { done } = await installEvents({ token: 'good-token', workerName: 'custom-pass', password: 'Hunter2-custom!', username: 'owner' });
  const b = Object.fromEntries(state.scripts['custom-pass'].bindings.map((x) => [x.name, x]));
  check('custom password: secret_text binding, returned as given, marked custom', b.PANEL_PASSWORD.type === 'secret_text' && b.PANEL_PASSWORD.text === 'Hunter2-custom!' && done.result.customPassword === true && done.result.passwordGenerated === false);
}

/* 6. token without KV scope still installs */
{
  state.kvAllowed = false;
  const { done, events } = await installEvents({ token: 'good-token', workerName: 'nokv' });
  check('install without KV scope succeeds', done.result.ok && done.result.kvBound === false);
  check('KV warning logged', events.some((e) => e.step === 'kv' && e.level === 'warn'));
  const bind = Object.fromEntries(state.scripts.nokv.bindings.map((b) => [b.name, b]));
  check('no KV binding but UUID still set', !bind.CAT_KV && bind.UUID);
  state.kvAllowed = true;
}

/* 7. private wizard copy */
{
  const blocked = await req('/api/install', { method: 'POST', body: { token: 'good-token', kind: 'wizard', workerName: 'my-wizard' }, env: { WIZARD_PASSWORD: 'inv' } });
  check('wizard install blocked without invite when WIZARD_PASSWORD set', blocked.status === 403 && !state.scripts['my-wizard']);
  const { done: done2 } = await installEvents({ token: 'good-token', kind: 'wizard', workerName: 'my-wizard', invite: 'inv' }, { WIZARD_PASSWORD: 'inv' });
  check('wizard self-copy deploys', done2.result && done2.result.kind === 'wizard' && done2.result.workerUrl.startsWith('https://my-wizard.'));
  check('wizard copy is the wizard source', state.scripts['my-wizard'].script.includes('CAT_WIZARD_VERSION'));
  const bind = Object.fromEntries(state.scripts['my-wizard'].bindings.map((b) => [b.name, b]));
  check('wizard copy inherits the invite code', bind.WIZARD_PASSWORD && bind.WIZARD_PASSWORD.text === 'inv');
}

/* 8. bad token surfaces a clean error event */
{
  const { done } = await installEvents({ token: 'bad' });
  check('bad token → done:false with message', done && done.ok === false && /Authentication|rejected/i.test(done.msg), JSON.stringify(done));
  const r = await req('/api/install', { method: 'POST', body: {} });
  check('install without token → 400', r.status === 400);
}

/* 9. the panel itself advertises the wizard permissions */
{
  check('panel deep-links back into Cat Client', panelSrc.includes('catclient://add-sub') && panelSrc.includes('catclient://scan'));
}

/* 10. deploy source is the compact, auditable minified module */
{
  check('source chain targets the minified snapshot / release asset',
    T.PANEL_SOURCES.some((u) => u.includes('dist-panel/catpanel.min.js')) && T.PANEL_SOURCES.some((u) => u.includes('releases/latest/download/catclient.worker.js')));
  check('source chain no longer targets the heavy obfuscated snapshot',
    T.PANEL_SOURCES.every((u) => !u.includes('catpanel.obf.js')));
}

console.log(failures === 0 ? '\nWIZARD TESTS PASSED' : '\n' + failures + ' WIZARD TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
