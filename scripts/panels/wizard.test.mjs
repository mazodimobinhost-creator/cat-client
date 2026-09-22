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
};
const json = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json' } });

T.__setFetch(async (input, init = {}) => {
  const url = typeof input === 'string' ? input : input.url;
  const method = (init.method || 'GET').toUpperCase();
  state.calls.push(method + ' ' + url);
  const auth = (init.headers && (init.headers.authorization || init.headers.Authorization)) || '';

  if (url.startsWith('https://github.com/') || url.startsWith('https://raw.githubusercontent.com/')) {
    if (url.endsWith('catclient.worker.js')) {
      // first mirror fails to prove the fallback chain works
      if (url.includes('/releases/latest/')) return new Response('nope', { status: 404 });
      return new Response(panelSrc, { status: 200 });
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
      if (method === 'POST') { const ns = { id: 'kv-' + (state.kv.length + 1), title: JSON.parse(init.body).title }; state.kv.push(ns); return json({ success: true, result: ns }); }
    }
    const scriptMatch = p.match(/^\/accounts\/acc-1\/workers\/scripts\/([a-z0-9-]+)(\/settings|\/subdomain)?$/);
    if (scriptMatch) {
      const name = scriptMatch[1];
      const sub = scriptMatch[2] || '';
      if (sub === '/settings' && method === 'GET') {
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

  if (url.endsWith('/health')) {
    const name = new URL(url).hostname.split('.')[0];
    if (!state.scripts[name]) return new Response('nf', { status: 404 });
    const isWizard = state.scripts[name].script.includes('CAT_WIZARD_VERSION');
    return json({ ok: true, version: isWizard ? T.CAT_WIZARD_VERSION : '5.5.0' });
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
  check('subdomain auto-created', /^catpanel-[a-z0-9]{8}$/.test(state.subdomain) && first.workerUrl === 'https://my-panel.' + state.subdomain + '.workers.dev');
  check('KV namespace created + bound', first.kvBound && state.kv.length === 1 && state.kv[0].title === 'my-panel-catpanel');
  const s = state.scripts['my-panel'];
  check('panel script uploaded (real Cat Panel source)', s && s.script.includes("CAT_PANEL_VERSION = '5.5.0'"));
  check('fallback source used after releases/latest 404', events.some((e) => e.step === 'source' && e.level === 'ok' && e.data && e.data.url.includes('raw.githubusercontent.com')));
  const bind = Object.fromEntries(s.bindings.map((b) => [b.name, b]));
  check('UUID bound as plain_text', bind.UUID && bind.UUID.type === 'plain_text' && /^[0-9a-f-]{36}$/.test(bind.UUID.text));
  check('CAT_KV bound', bind.CAT_KV && bind.CAT_KV.type === 'kv_namespace' && bind.CAT_KV.namespace_id === 'kv-1');
  check('no PANEL_PASSWORD when none supplied', !bind.PANEL_PASSWORD);
  check('nodejs_compat + compat date set', s.meta.compatibility_flags.includes('nodejs_compat') && s.meta.compatibility_date);
  check('route enabled', state.routes['my-panel'] === true);
  check('online after health probe', first.online === true && first.panelVersion === '5.5.0');
  check('result: password = UUID by default', first.password === first.uuid && first.customPassword === false);
  check('result: panel URL unlocks with ?p=<uuid>', first.panelUrl === first.workerUrl + '/?p=' + first.uuid);
  check('result: sub URL /sub/<uuid>', first.subUrl === first.workerUrl + '/sub/' + first.uuid);
  check('result: deep link for the app', first.deepLink.startsWith('catclient://add-sub?url=') && decodeURIComponent(first.deepLink).includes(first.subUrl));
}

/* 5. re-install keeps UUID; custom password becomes a secret binding */
{
  const { done } = await installEvents({ token: 'good-token', workerName: 'my-panel', password: 'Hunter2!' });
  check('re-install keeps the same UUID', done.result.uuid === first.uuid);
  check('re-install reuses the KV namespace', state.kv.length === 1 && done.result.kvBound);
  const bind = Object.fromEntries(state.scripts['my-panel'].bindings.map((b) => [b.name, b]));
  check('custom password bound as secret_text', bind.PANEL_PASSWORD && bind.PANEL_PASSWORD.type === 'secret_text' && bind.PANEL_PASSWORD.text === 'Hunter2!');
  check('panel URL uses the custom password', done.result.password === 'Hunter2!' && done.result.panelUrl === done.result.workerUrl + '/?p=' + encodeURIComponent('Hunter2!'));
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
  check('panel help links to a wizard/token template', panelSrc.includes('permissionGroupKeys') || panelSrc.includes('/wizard'));
}

console.log(failures === 0 ? '\nWIZARD TESTS PASSED' : '\n' + failures + ' WIZARD TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
