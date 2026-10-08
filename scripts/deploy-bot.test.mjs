/**
 * Cat Deploy Bot tests (no dependencies, mocked outbound fetch).
 * Covers: secret gate, admin gate, full /deploy flow (KV reuse-or-create,
 * module upload with CAT_KV + vars, cron, subdomain URL), honest /setup,
 * /status token verify, masked failures.
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
function mkBot(t) {
  const calls = [];
  const tgReplies = [];
  const F = async (url, opt) => {
    const u = String(url); calls.push({ u, opt });
    if (u.startsWith('https://api.telegram.org/')) {
      const m = u.split('/').pop();
      tgReplies.push({ m, body: opt && JSON.parse(opt.body || '{}') });
      return { ok: true, json: async () => ({ ok: true, result: true }) };
    }
    if (u.includes('raw.githubusercontent.com') && u.endsWith('catclient.worker.js')) return { ok: true, json: async () => ({}), text: async () => PANEL_CODE };
    if (u.includes('/releases/latest/download/')) return { ok: true, json: async () => ({}), text: async () => PANEL_CODE };
    if (u.includes('/storage/kv/namespaces')) {
      if ((opt && opt.method) === 'POST') return { ok: true, status: 200, json: async () => ({ result: { id: 'kv-new' } }) };
      return { ok: true, status: 200, json: async () => ({ result: [{ id: 'kv1', title: 'cat-panel-kv' }] }) };
    }
    if (u.includes('/workers/scripts/cat-panel') && u.includes('/schedules')) return { ok: true, status: 200, json: async () => ({}) };
    if (u.includes('/workers/scripts/cat-panel/subdomain') && (opt && opt.method) === 'POST') return { ok: true, status: 200, json: async () => ({}) };
    if (u.includes('/workers/subdomain')) return { ok: true, status: 200, json: async () => ({ result: { subdomain: 'me' } }) }; // real API returns the bare label
    if (u === 'https://cat-panel.me.workers.dev/health') return { ok: true, status: 200, json: async () => ({ ok: true, version: '9.9.9', kv: true }) };
    if (u.includes('/accounts/acc1') && !u.includes('/workers') && !u.includes('/storage')) return { ok: true, status: 200, json: async () => ({ result: { id: 'acc1' } }) };
    if (u.includes('/workers/scripts/cat-panel')) return { ok: true, status: 200, json: async () => ({ result: {} }) };
    if (u.includes('/workers/scripts')) return { ok: true, status: 200, json: async () => ({ result: [{ id: 'cat-panel' }] }) };
    if (u.includes('/user/tokens/verify')) return { ok: true, status: 200, json: async () => ({ result: { status: 'active' } }) };
    return { ok: false, status: 404, json: async () => ({}) };
  };
  return { F, calls, tgReplies };
}
const ENV = (t) => ({ TG_TOKEN: t, CF_API_TOKEN: 'cf-tok', CF_ACCOUNT_ID: 'acc1', TG_ADMIN_ID: '42', __fetch: null });

// 1) admin gate: webhook rejects strangers, asks for TG_ADMIN_ID when unset
{
  const b = mkBot('sec'); const env = ENV('sec'); env.__fetch = b.F;
  env.TG_ADMIN_ID = '';
  const sec = (await T.sha256hex('sec')).slice(0, 32);
  const req = (fromId, text) => new Request('https://bot.workers.dev/tg/' + sec, { method: 'POST', headers: { 'x-telegram-bot-api-secret-token': sec, 'content-type': 'application/json' }, body: JSON.stringify({ message: { chat: { id: 42 }, from: { id: fromId }, text } }) });
  const r1 = await bot.fetch(req('42', '/help'), env);
  check('webhook accepts admin', r1.status === 200);
  check('unset TG_ADMIN_ID → honest id prompt', b.tgReplies.some((x) => x.body.text && x.body.text.includes('TG_ADMIN_ID') && x.body.text.includes('42')));
  env.TG_ADMIN_ID = '42';
  const r2 = await bot.fetch(req('999', '/deploy'), env);
  check('webhook 200 for stranger too (silently gated)', r2.status === 200);
  check('stranger gets ⛔, no deploy ran', b.tgReplies.some((x) => (x.body.text || '').includes('⛔')) && !b.calls.some((x) => x.u.includes('/workers/scripts/')));
  const r3 = await bot.fetch(new Request('https://bot.workers.dev/tg/wrongsecret', { method: 'POST', headers: { 'x-telegram-bot-api-secret-token': 'nope' }, body: '{}' }), env);
  check('webhook 403 on bad secret', r3.status === 403);
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
  check('metadata: main_module + CAT_KV binding + OPEN vars', meta && meta.main_module === 'worker.js' && meta.bindings.some((x) => x.name === 'CAT_KV' && x.namespace_id === 'kv1') && meta.bindings.some((x) => x.name === 'OPEN_PANEL' && x.text === 'true'), meta && JSON.stringify(meta.bindings));
  const codePart = form && (form.get ? form.get('worker.js') : null);
  check('script body is the fetched panel', codePart && (await codePart.text()).includes('CAT_PANEL_VERSION = ' + "'9.9.9'"));
  check('cron schedule set (12h)', b.calls.some((x) => x.u.includes('/schedules') && x.opt.body.includes('0 */12 * * *')));
  check('subdomain enabled + URL in reply', b.calls.some((x) => x.u.includes('/subdomain')) && rep.includes('cat-panel.me.workers.dev'), rep.slice(0, 120));
  check('reply carries fetched version', rep.includes('9.9.9'));
  check('deploy reused existing KV (no create call)', !b.calls.some((x) => x.u.includes('/storage/kv/namespaces') && x.opt.method === 'POST'));
  check('post-deploy health gate: reply reports health OK', b.calls.some((x) => x.u === 'https://cat-panel.me.workers.dev/health') && rep.includes('health OK'), rep.slice(0, 160));
}
// 2b) health mismatch → honest ⚠ + rollback pointer (anti-1101 gate)
{
  const b = mkBot('sec2b'); const env = ENV('sec2b'); env.__fetch = b.F;
  const orig = b.F;
  const F2 = async (u, o) => { if (String(u) === 'https://cat-panel.me.workers.dev/health') return { ok: true, status: 200, json: async () => ({ ok: false, version: '6.20.0' }) }; return orig(u, o); };
  b.F = F2;
  const rep2 = await T.runCommand(F2, T.cfg(env), '/deploy');
  check('health mismatch → ⚠ + /rollback pointer', rep2.includes('health FAILED') && rep2.includes('سرو‌شده: 6.20.0') && rep2.includes('/rollback'), rep2.slice(0, 200));
}
// 2c) /doctor — full status report
{
  const b = mkBot('sec2c'); const env = ENV('sec2c'); env.__fetch = b.F;
  const d = await T.runCommand(b.F, T.cfg(env), '/doctor');
  check('/doctor: account + worker + KV + health all green', d.includes('🩺') && d.includes('حساب کلادفلر') && d.includes('✅ health v9.9.9') && !d.includes('🔴'), d);
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
