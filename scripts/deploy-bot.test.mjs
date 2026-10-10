/**
 * Cat Deploy Bot tests (no dependencies, mocked outbound fetch).
 * Covers: webhook/admin gates, private-only credential delivery, locked first
 * deploy, random/stable UUIDs, KV/binding/secret preservation, health reporting,
 * rollback, /setup, /status, and honest failures.
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const botPath = path.join(here, '../deploy-bot/worker.js');
execFileSync(process.execPath, ['--check', botPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');
const mod = await import(botPath);
const bot = mod.default; const T = mod._testing;
let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } };

const PANEL_CODE = "const CAT_PANEL_VERSION = '9.9.9';\nexport default {fetch(){}};";
const PANEL_UUID = '6f3a9e42-18be-4b8e-9b24-f2db119d1a73';
function mkBot(t, initialBindings = null) {
  const calls = [];
  const tgReplies = [];
  const state = {
    bindings: initialBindings ? JSON.parse(JSON.stringify(initialBindings)) : null,
    metadata: null,
    script: '',
  };
  const F = async (url, opt = {}) => {
    const u = String(url); calls.push({ u, opt });
    if (u.startsWith('https://api.telegram.org/')) {
      const m = u.split('/').pop();
      tgReplies.push({ m, body: JSON.parse(opt.body || '{}') });
      return { ok: true, json: async () => ({ ok: true, result: true }) };
    }
    if (u.includes('raw.githubusercontent.com') && u.endsWith('catclient.worker.js')) return { ok: true, json: async () => ({}), text: async () => PANEL_CODE };
    if (u.includes('/releases/latest/download/')) return { ok: true, json: async () => ({}), text: async () => PANEL_CODE };
    if (u.includes('/workers/scripts/cat-panel/settings')) {
      return state.bindings
        ? { ok: true, status: 200, json: async () => ({ result: { bindings: state.bindings } }) }
        : { ok: false, status: 404, json: async () => ({ success: false, errors: [{ code: 10007, message: 'script not found' }] }) };
    }
    if (u.includes('/storage/kv/namespaces')) {
      if (opt.method === 'POST') return { ok: true, status: 200, json: async () => ({ result: { id: 'kv-new' } }) };
      return { ok: true, status: 200, json: async () => ({ result: [] }) };
    }
    if (u.endsWith('/workers/scripts/cat-panel') && opt.method === 'PUT') {
      const metadata = JSON.parse(await opt.body.get('metadata').text());
      const previousSecrets = metadata.keep_secrets
        ? (state.bindings || []).filter((binding) => binding.type === 'secret_text')
        : [];
      state.metadata = metadata;
      state.script = await opt.body.get('worker.js').text();
      state.bindings = [...metadata.bindings, ...previousSecrets];
      return { ok: true, status: 200, json: async () => ({ result: {} }) };
    }
    if (u.includes('/workers/scripts/cat-panel') && u.includes('/schedules')) return { ok: true, status: 200, json: async () => ({}) };
    if (u.includes('/workers/scripts/cat-panel/subdomain') && opt.method === 'POST') return { ok: true, status: 200, json: async () => ({}) };
    if (u.includes('/workers/subdomain')) return { ok: true, status: 200, json: async () => ({ result: { subdomain: 'me' } }) }; // real API returns the bare label
    if (u === 'https://cat-panel.me.workers.dev/health') return { ok: true, status: 200, json: async () => ({ ok: true, version: '9.9.9', kv: true }) };
    if (u.includes('/accounts/acc1') && !u.includes('/workers') && !u.includes('/storage')) return { ok: true, status: 200, json: async () => ({ result: { id: 'acc1' } }) };
    if (u.includes('/workers/scripts/cat-panel')) return { ok: true, status: 200, json: async () => ({ result: {} }) };
    if (u.includes('/workers/scripts')) return { ok: true, status: 200, json: async () => ({ result: [{ id: 'cat-panel' }] }) };
    if (u.includes('/user/tokens/verify')) return { ok: true, status: 200, json: async () => ({ result: { status: 'active' } }) };
    return { ok: false, status: 404, json: async () => ({}) };
  };
  return { F, calls, tgReplies, state };
}
const ENV = (t) => ({ TG_TOKEN: t, CF_API_TOKEN: 'cf-tok', CF_ACCOUNT_ID: 'acc1', TG_ADMIN_ID: '42', PANEL_UUID, __fetch: null });

// 1) admin gate: webhook rejects strangers, asks for TG_ADMIN_ID when unset
{
  const b = mkBot('sec'); const env = ENV('sec'); env.__fetch = b.F;
  env.TG_ADMIN_ID = '';
  const sec = (await T.sha256hex('sec')).slice(0, 32);
  const req = (fromId, text, chat = { id: Number(fromId), type: 'private' }) => new Request('https://bot.workers.dev/tg/' + sec, { method: 'POST', headers: { 'x-telegram-bot-api-secret-token': sec, 'content-type': 'application/json' }, body: JSON.stringify({ message: { chat, from: { id: fromId }, text } }) });
  const r1 = await bot.fetch(req('42', '/help'), env);
  check('webhook accepts admin', r1.status === 200);
  check('unset TG_ADMIN_ID → honest id prompt', b.tgReplies.some((x) => x.body.text && x.body.text.includes('TG_ADMIN_ID') && x.body.text.includes('42')));
  env.TG_ADMIN_ID = '42';
  const r2 = await bot.fetch(req('999', '/deploy'), env);
  check('webhook 200 for stranger too (silently gated)', r2.status === 200);
  check('stranger gets ⛔, no deploy ran', b.tgReplies.some((x) => (x.body.text || '').includes('⛔')) && !b.calls.some((x) => x.u.includes('/workers/scripts/')));
  const group = await bot.fetch(req('42', '/deploy', { id: -100123, type: 'supergroup' }), env);
  check('admin cannot deploy from a group chat', group.status === 200 && b.tgReplies.some((x) => (x.body.text || '').includes('گفت‌وگوی خصوصی')) && !b.calls.some((x) => x.u.includes('api.cloudflare.com')));
  const r3 = await bot.fetch(new Request('https://bot.workers.dev/tg/wrongsecret', { method: 'POST', headers: { 'x-telegram-bot-api-secret-token': 'nope' }, body: '{}' }), env);
  check('webhook 403 on bad secret', r3.status === 403);
}
// 1b) /deploy is private-chat-only and deployment credentials are protected in Telegram
{
  const b = mkBot('private-sec'); const env = ENV('private-sec'); env.__fetch = b.F;
  const secret = (await T.sha256hex('private-sec')).slice(0, 32);
  const req = new Request('https://bot.workers.dev/tg/' + secret, {
    method: 'POST',
    headers: { 'x-telegram-bot-api-secret-token': secret, 'content-type': 'application/json' },
    body: JSON.stringify({ message: { chat: { id: 42, type: 'private' }, from: { id: 42 }, text: '/deploy' } }),
  });
  await bot.fetch(req, env);
  const progress = b.tgReplies.find((x) => x.body.chat_id === 42 && (x.body.text || '').startsWith('⏳'));
  const final = b.tgReplies.find((x) => x.body.chat_id === 42 && (x.body.text || '').includes('UUID اصلی'));
  check('deploy progress is sent to the private chat and protected', progress && progress.body.protect_content === true);
  check('credential-bearing deploy result is protected from forwarding', final && final.body.protect_content === true && final.body.text.includes('/sub/'));
}
// 2) full /deploy flow
{
  const b = mkBot('sec2'); const env = ENV('sec2'); env.__fetch = b.F;
  const rep = await T.runCommand(b.F, T.cfg(env), '/deploy');
  const upCall = b.calls.find((x) => x.u.endsWith('/workers/scripts/cat-panel') && x.opt.method === 'PUT');
  check('deploy PUTs module script', !!upCall);
  const form = upCall && upCall.opt.body;
  const metaPart = form && (form.get ? form.get('metadata') : null);
  const meta = metaPart ? JSON.parse(await metaPart.text()) : null;
  const bindings = meta && Object.fromEntries(meta.bindings.map((binding) => [binding.name, binding]));
  check('metadata: main_module + newly-created CAT_KV binding', meta && meta.main_module === 'worker.js' && bindings.CAT_KV && bindings.CAT_KV.namespace_id === 'kv-new', meta && JSON.stringify(meta.bindings));
  check('fresh deploy binds the stable PANEL_UUID secret as UUID', bindings && bindings.UUID && bindings.UUID.type === 'plain_text' && bindings.UUID.text === PANEL_UUID);
  check('panel and root subscription are locked by default', bindings && bindings.OPEN_PANEL.text === 'false' && bindings.OPEN_SUB.text === 'false');
  check('fresh deploy has no secret-preservation flag', meta && !meta.keep_secrets);
  const codePart = form && (form.get ? form.get('worker.js') : null);
  check('script body is the fetched panel', codePart && (await codePart.text()).includes('CAT_PANEL_VERSION = ' + "'9.9.9'"));
  check('cron schedule set (12h)', b.calls.some((x) => x.u.includes('/schedules') && x.opt.body.includes('0 */12 * * *')));
  check('subdomain enabled + URL in reply', b.calls.some((x) => x.u.includes('/subdomain')) && rep.includes('cat-panel.me.workers.dev'), rep.slice(0, 120));
  check('reply carries fetched version', rep.includes('9.9.9'));
  const masterUuid = bindings && bindings.UUID && bindings.UUID.text;
  check('reply includes the stable master subscription and initial password', masterUuid === PANEL_UUID && rep.includes('/sub/' + masterUuid) && rep.includes('رمز اولیهٔ پنل = UUID ثابت PANEL_UUID'));
  check('fresh install created and bound one KV namespace', b.calls.some((x) => x.u.includes('/storage/kv/namespaces') && x.opt.method === 'POST'));
  check('post-deploy health gate: reply reports health OK', b.calls.some((x) => x.u === 'https://cat-panel.me.workers.dev/health') && rep.includes('health OK'), rep.slice(0, 160));
}
// 2a) update preserves UUID, the actually bound KV, readable bindings and opaque secrets
{
  const stableUuid = '11111111-2222-4333-8444-555555555555';
  const b = mkBot('sec2a', [
    { type: 'plain_text', name: 'UUID', text: stableUuid },
    { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-from-wizard' },
    { type: 'plain_text', name: 'PANEL_USER', text: 'owner' },
    { type: 'plain_text', name: 'CF_IPS', text: '203.0.113.10' },
    { type: 'plain_text', name: 'OPEN_PANEL', text: 'true' },
    { type: 'plain_text', name: 'OPEN_SUB', text: 'true' },
    { type: 'secret_text', name: 'PANEL_PASSWORD', text: 'do-not-copy' },
    { type: 'secret_text', name: 'CUSTOM_SECRET', text: 'also-do-not-copy' },
  ]);
  const env = ENV('sec2a'); env.__fetch = b.F; env.PANEL_UUID = stableUuid;
  const reply = await T.runCommand(b.F, T.cfg(env), '/deploy');
  const meta = b.state.metadata;
  const byName = Object.fromEntries(meta.bindings.map((binding) => [binding.name, binding]));
  check('update keeps existing UUID', byName.UUID && byName.UUID.text === stableUuid && reply.includes(stableUuid));
  check('update reuses the bound Wizard KV, not a new namespace', byName.CAT_KV && byName.CAT_KV.namespace_id === 'kv-from-wizard' && !b.calls.some((x) => x.u.includes('/storage/kv/namespaces')));
  check('update preserves readable owner bindings', byName.PANEL_USER && byName.PANEL_USER.text === 'owner' && byName.CF_IPS && byName.CF_IPS.text === '203.0.113.10');
  check('update forces both public access flags off', byName.OPEN_PANEL && byName.OPEN_PANEL.text === 'false' && byName.OPEN_SUB && byName.OPEN_SUB.text === 'false');
  check('opaque secrets preserved via keep_secrets, never re-uploaded in metadata', meta.keep_secrets === true && !meta.bindings.some((x) => x.type === 'secret_text') && b.state.bindings.some((x) => x.name === 'PANEL_PASSWORD') && !JSON.stringify(meta).includes('do-not-copy'));
  check('bot does not disclose preserved custom password', reply.includes('مقدار مخفی آن را نمی‌خواند') && !reply.includes('do-not-copy') && !reply.includes('also-do-not-copy'));
}
// 2a2) legacy panel is migrated to the configured PANEL_UUID, then stays stable
{
  const b = mkBot('legacy', [
    { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-legacy' },
    { type: 'plain_text', name: 'OPEN_PANEL', text: 'true' },
    { type: 'plain_text', name: 'OPEN_SUB', text: 'true' },
  ]);
  const env = ENV('legacy'); env.__fetch = b.F;
  const firstReply = await T.runCommand(b.F, T.cfg(env), '/deploy');
  const firstUuid = b.state.bindings.find((binding) => binding.name === 'UUID').text;
  check('legacy panel is migrated to PANEL_UUID with a fresh sub link and a rotation warning', firstUuid === PANEL_UUID && firstReply.includes('/sub/' + firstUuid) && firstReply.includes('لینک‌های اشتراک قبلی عوض می‌شوند'));
  const secondReply = await T.runCommand(b.F, T.cfg(env), '/deploy');
  const secondUuid = b.state.bindings.find((binding) => binding.name === 'UUID').text;
  check('later update keeps the pinned PANEL_UUID and stops warning about rotation', secondUuid === PANEL_UUID && !secondReply.includes('لینک‌های اشتراک قبلی عوض می‌شوند'));
}
// 2aa) do not replace a worker if its current settings cannot be read
{
  const b = mkBot('sec2aa'); const env = ENV('sec2aa'); env.__fetch = b.F;
  const F2 = async (u, o = {}) => String(u).includes('/workers/scripts/cat-panel/settings')
    ? { ok: false, status: 403, json: async () => ({}) }
    : b.F(u, o);
  const reply = await T.runCommand(F2, T.cfg(env), '/deploy');
  check('settings read failure aborts before upload', reply.includes('update stopped to avoid losing') && !b.calls.some((x) => x.u.endsWith('/workers/scripts/cat-panel') && x.opt.method === 'PUT'));
  const b2 = mkBot('secret-open', [{ type: 'secret_text', name: 'OPEN_PANEL', text: '' }]);
  const env2 = ENV('secret-open'); env2.__fetch = b2.F;
  const reply2 = await T.runCommand(b2.F, T.cfg(env2), '/deploy');
  check('unreadable secret open flag aborts safely', reply2.includes('existing OPEN_PANEL is a secret binding') && !b2.calls.some((x) => x.u.endsWith('/workers/scripts/cat-panel') && x.opt.method === 'PUT'));
}
// 2b) health mismatch → honest ⚠ + rollback pointer (anti-1101 gate)
{
  const b = mkBot('sec2b'); const env = ENV('sec2b'); env.__fetch = b.F;
  const orig = b.F;
  const F2 = async (u, o) => { if (String(u) === 'https://cat-panel.me.workers.dev/health') return { ok: true, status: 200, json: async () => ({ ok: false, version: '6.20.0' }) }; return orig(u, o); };
  b.F = F2;
  const rep2 = await T.runCommand(F2, T.cfg(env), '/deploy');
  check('health mismatch is not reported as ready and points to rollback', rep2.includes('آپلود شد، اما سلامت آن تأیید نشد') && rep2.includes('نسخهٔ پاسخ‌داده‌شده: 6.20.0') && rep2.includes('/rollback') && !rep2.includes('✅ <b>Cat Panel'), rep2.slice(0, 240));
}
// 2c) /doctor — full status report
{
  const b = mkBot('sec2c'); const env = ENV('sec2c'); env.__fetch = b.F;
  const d = await T.runCommand(b.F, T.cfg(env), '/doctor');
  check('/doctor: account + worker + health status is reported', d.includes('🩺') && d.includes('حساب کلادفلر') && d.includes('✅ health v9.9.9') && !d.includes('🔴'), d);
  const b2 = mkBot('sec2d'); const env2 = ENV('sec2d'); env2.__fetch = b2.F;
  const orig = b2.F;
  const F3 = async (u, o) => { if (String(u) === 'https://cat-panel.me.workers.dev/health') return { ok: false, status: 500, json: async () => ({}) }; return orig(u, o); };
  b2.F = F3;
  const d2 = await T.runCommand(F3, T.cfg(env2), '/doctor');
  check('/doctor: dead worker → red 1101 guidance with /deploy', d2.includes('🔴 health HTTP 500') && d2.includes('1101'), d2);
}
// 2e) /rollback — rolls back to the previous version
{
  const b = mkBot('sec2e'); const env = ENV('sec2e'); env.__fetch = b.F;
  const orig = b.F;
  const F4 = async (u, o) => {
    const uu = String(u);
    if (uu.includes('/workers/scripts/cat-panel/versions') && (uu.endsWith('/versions') || uu.endsWith('/versions?per_page=100')) ) return { ok: true, status: 200, json: async () => ({ result: [{ id: 'v2', number: 2 }, { id: 'v1', number: 1 }] }) };
    if (uu.includes('/versions/v2/rollback')) return { ok: true, status: 200, json: async () => ({ result: { id: 'v1' } }) };
    return orig(u, o);
  };
  b.F = F4;
  const rb = await T.runCommand(F4, T.cfg(env), '/rollback');
  check('/rollback: rolls back to previous version', rb.includes('⏪') && rb.includes('(1)'), rb.slice(0, 140));
  const b2 = mkBot('sec2f'); const env2 = ENV('sec2f'); env2.__fetch = b2.F;
  const rb2 = await T.runCommand(b2.F, T.cfg(env2), '/rollback');
  check('/rollback: single version → honest no-op', rb2.includes('🟡'), rb2.slice(0, 140));
}
// 3) KV created when missing
{
  const b = mkBot('sec3'); const env = ENV('sec3'); env.__fetch = b.F;
  b.calls.length = 0;
  const orig = b.F;
  const F2 = async (u, o) => { if (String(u).includes('/storage/kv/namespaces') && (!o || o.method !== 'POST')) return { ok: true, status: 200, json: async () => ({ result: [] }) }; return orig(u, o); };
  b.F = F2;
  const rep = await T.runCommand(F2, T.cfg(env), '/deploy');
  check('missing KV → created and bound', b.calls.some((x) => String(x.u).includes('/storage/kv/namespaces') && x.opt.method === 'POST') && rep.includes('✅'));
}
// 4) /deploy branch arg uses raw.githubusercontent
{
  const b = mkBot('sec4'); const env = ENV('sec4'); env.__fetch = b.F;
  await T.runCommand(b.F, T.cfg(env), '/deploy arena/01a0ebed-cat-client');
  check('branch deploy pulls raw branch source', b.calls.some((x) => x.u.includes('raw.githubusercontent.com') && x.u.includes('refs/heads/arena/01a0ebed-cat-client')));
}
// 5) honest /setup + /status + bad source
{
  const b = mkBot('sec5'); const env = ENV('sec5'); env.__fetch = b.F; delete env.CF_API_TOKEN; delete env.CF_ACCOUNT_ID;
  const setup = await T.runCommand(b.F, T.cfg(env), '/deploy');
  check('unconfigured /deploy → checklist, no CF call', setup.includes('Secrets needed') && setup.includes('CF_API_TOKEN'));
  const b2 = mkBot('sec6'); const env2 = ENV('sec6'); env2.__fetch = b2.F;
  const st = await T.runCommand(b2.F, T.cfg(env2), '/status');
  check('/status verifies token + lists workers', st.includes('token: ✅') && st.includes('cat-panel'));
  const b3 = mkBot('sec7'); const env3 = ENV('sec7'); env3.__fetch = (u) => String(u).includes('releases/latest') ? { ok: true, text: async () => 'garbage' } : b3.F(u, {});
  const bad = await T.runCommand(env3.__fetch, T.cfg(env3), '/deploy');
  check('non-panel source rejected with clear error', bad.includes('failed') && bad.includes('does not look like'));
  const b4 = mkBot('sec8'); const env4 = ENV('sec8'); env4.__fetch = () => ({ ok: false, status: 404, text: async () => '' });
  const missing = await T.runCommand(env4.__fetch, T.cfg(env4), '/deploy');
  check('missing release → honest 404 message', missing.includes('source 404'));
}
// 5b) PANEL_UUID is required and blocks deploy before any Cloudflare/Telegram write
{
  const noUuid = (() => { const e = ENV('secU1'); e.__fetch = null; delete e.PANEL_UUID; return e; })();
  const b1 = mkBot('secU1'); noUuid.__fetch = b1.F;
  const blocked = await T.runCommand(b1.F, T.cfg(noUuid), '/deploy');
  check('missing PANEL_UUID → clear setup checklist, no Cloudflare call', blocked.includes('PANEL_UUID') && blocked.includes('required') && !b1.calls.some((x) => x.u.includes('api.cloudflare.com')), blocked.slice(0, 200));
  const badUuid = (() => { const e = ENV('secU2'); e.__fetch = null; e.PANEL_UUID = 'not-a-uuid'; return e; })();
  const b2 = mkBot('secU2'); badUuid.__fetch = b2.F;
  const rejected = await T.runCommand(b2.F, T.cfg(badUuid), '/deploy');
  check('invalid PANEL_UUID → honest error, no upload', rejected.includes('PANEL_UUID') && rejected.includes('invalid') && !b2.calls.some((x) => x.u.includes('api.cloudflare.com')), rejected.slice(0, 200));
  const b3 = mkBot('secU3'); const env3 = ENV('secU3'); env3.__fetch = b3.F; env3.PANEL_UUID = 'BAD';
  const direct = await T.deployPanel(b3.F, T.cfg(env3), '');
  check('deployPanel refuses to invent a UUID when the secret is invalid', direct.ok === false && !b3.calls.some((x) => x.u.includes('api.cloudflare.com')), direct.error);
  const b4 = mkBot('secU4'); const env4 = ENV('secU4'); env4.__fetch = b4.F;
  const h = await (await bot.fetch(new Request('https://bot.workers.dev/health'), env4)).json();
  check('/health reports configured=true only with a valid PANEL_UUID', h.configured === true, JSON.stringify(h));
  const env5 = ENV('secU5'); env5.__fetch = b4.F; delete env5.PANEL_UUID;
  const h2 = await (await bot.fetch(new Request('https://bot.workers.dev/health'), env5)).json();
  check('/health reports configured=false without PANEL_UUID', h2.configured === false, JSON.stringify(h2));
}
// 6) /register webhook key gate
{
  const b = mkBot('sec9'); const env = ENV('sec9'); env.__fetch = b.F;
  const key = (await T.sha256hex('sec9')).slice(0, 16);
  const ok = await bot.fetch(new Request('https://bot.workers.dev/register?key=' + key), env);
  check('/register with valid key sets webhook', ok.status === 200 && b.calls.some((x) => x.u.includes('setWebhook') && x.opt.body.includes('/tg/')));
  const bad = await bot.fetch(new Request('https://bot.workers.dev/register?key=wrong'), env);
  check('/register with bad key → 403', bad.status === 403);
}
// 7) /health
{
  const env = ENV('t'); env.TG_TOKEN = ''; env.CF_API_TOKEN = ''; env.CF_ACCOUNT_ID = ''; env.TG_ADMIN_ID = '';
  const h = await (await bot.fetch(new Request('https://bot.workers.dev/health'), env)).json();
  check('/health honest configured=false', h.ok === true && h.configured === false);
}
console.log(failures ? '\n' + failures + ' FAILED' : '\nDEPLOY BOT ALL PASSED');
process.exit(failures ? 1 : 0);
