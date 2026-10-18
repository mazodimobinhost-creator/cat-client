/**
 * 🐱 Cat Deploy Bot — standalone Telegram deploy bot for Cat Panel.
 *
 * Unlike the panel's built-in bot (management: users/links/ips), THIS bot
 * deploys the panel itself: it pulls the latest `catclient.worker.js` from
 * GitHub and pushes it straight to Cloudflare with YOUR API token.
 *
 *   /deploy  →  latest GitHub release  →  Cloudflare Workers API  →  ✅ URL
 *
 * Fully standalone (no panel, no KV binding). Provisioned once with the
 * "Deploy to Cloudflare" button, then configured with 4 secrets:
 *
 *   TG_TOKEN       Telegram bot token (@BotFather)
 *   CF_API_TOKEN   Cloudflare token: Workers Scripts:Edit + Workers KV Storage:Edit
 *   CF_ACCOUNT_ID  Cloudflare account id (dashboard → Workers → right side)
 *   TG_ADMIN_ID    your numeric Telegram id (@userinfobot)
 * Optional:
 *   WORKER_NAME    deployed worker name (default "cat-panel")
 *   GH_REPO        panel repo               (default mazodimobinhost-creator/cat-client)
 *
 * After adding secrets: open <bot-url>/register?key=<TG_TOKEN secret hash>
 * (the /start reply prints the exact link) — then send /deploy in Telegram.
 * The Cloudflare token lives ONLY in this worker's secrets — never in chat,
 * never in the repo. Send /revoke in BotFather if a token ever leaks.
 */

const BOT_VERSION = '2.0.0';
const KV_TITLE = 'cat-panel-kv';
const DEFAULT_REPO = 'mazodimobinhost-creator/cat-client';
const DEFAULT_WORKER = 'cat-panel';

const sha256hex = async (s) => {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(s)));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
};
const esc = (s) => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function cfg(env) {
  return {
    token: String(env.TG_TOKEN || '').trim(),
    cf: String(env.CF_API_TOKEN || '').trim(),
    acc: String(env.CF_ACCOUNT_ID || '').trim(),
    admin: String(env.TG_ADMIN_ID || '').trim(),
    name: String(env.WORKER_NAME || DEFAULT_WORKER).replace(/[^A-Za-z0-9_-]/g, '') || DEFAULT_WORKER,
    repo: String(env.GH_REPO || DEFAULT_REPO).replace(/[^A-Za-z0-9._/-]/g, '') || DEFAULT_REPO,
  };
}

async function tgApi(F, token, method, body) {
  const r = await F('https://api.telegram.org/bot' + token + '/' + method, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}) });
  try { return await r.json(); } catch (e) { return { ok: false }; }
}

async function cfApi(F, c, method, path, body, isForm) {
  const headers = { authorization: 'Bearer ' + c.cf };
  if (!isForm) headers['content-type'] = 'application/json';
  const r = await F('https://api.cloudflare.com/client/v4' + path, { method, headers, body: isForm ? body : body === undefined ? undefined : JSON.stringify(body) });
  let j = null; try { j = await r.json(); } catch (e) { }
  return { ok: r.ok, status: r.status, json: j };
}

function panelSourceUrl(c, ref) {
  if (ref) return 'https://raw.githubusercontent.com/' + c.repo + '/refs/heads/' + ref + '/app/src/main/assets/panels/catclient.worker.js'; // ref is regex-validated (slashes allowed)
  return 'https://github.com/' + c.repo + '/releases/latest/download/catclient.worker.js';
}

/** Fetch the panel worker script (branch or latest release) and deploy it. */
async function deployPanel(F, c, ref) {
  const src = await F(panelSourceUrl(c, ref), { redirect: 'follow', headers: { 'user-agent': 'cat-deploy-bot' } });
  if (!src.ok) return { ok: false, error: 'source ' + src.status + (ref ? ' — branch «' + ref + '» پیدا نشد' : ' — releases/latest در دسترس نیست') };
  const code = await src.text();
  if (!code.includes('export default') || !code.includes('CAT_PANEL_VERSION')) return { ok: false, error: 'source fetched but does not look like catclient.worker.js' };
  const ver = (code.match(/CAT_PANEL_VERSION = '([^']+)'/) || [])[1] || '?';

  // KV namespace: reuse or create (panel needs CAT_KV).
  let kvId = '';
  const list = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/storage/kv/namespaces?per_page=100');
  const ns = ((list.json && list.json.result) || []).find((n) => n.title === KV_TITLE);
  if (ns) kvId = ns.id;
  else {
    const made = await cfApi(F, c, 'POST', '/accounts/' + c.acc + '/storage/kv/namespaces', { title: KV_TITLE });
    if (!made.ok) return { ok: false, error: 'KV create failed (' + made.status + ')' };
    kvId = made.json && made.json.result && made.json.result.id;
  }

  // Upload the module worker with bindings (fresh installs start open, like the button).
  const meta = {
    main_module: 'worker.js',
    compatibility_date: '2026-09-01',
    bindings: [
      { type: 'kv_namespace', name: 'CAT_KV', namespace_id: kvId },
      { type: 'plain_text', name: 'OPEN_PANEL', text: 'true' },
      { type: 'plain_text', name: 'OPEN_SUB', text: 'true' },
    ],
  };
  const form = new FormData();
  form.append('metadata', new Blob([JSON.stringify(meta)], { type: 'application/json' }), 'metadata.json');
  form.append('worker.js', new Blob([code], { type: 'application/javascript+module' }), 'worker.js');
  const up = await cfApi(F, c, 'PUT', '/accounts/' + c.acc + '/workers/scripts/' + c.name, form, true);
  if (!up.ok) {
    const errs = up.json && up.json.errors && up.json.errors[0];
    return { ok: false, error: 'upload ' + up.status + (errs ? ' — ' + errs.code + ' ' + errs.message : '') };
  }

  // Cron (repo feed refresh) + workers.dev subdomain.
  await cfApi(F, c, 'PUT', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/schedules', [{ cron: '0 */12 * * *' }]).catch(() => ({}));
  await cfApi(F, c, 'POST', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/subdomain', { enabled: true, previews_enabled: true }).catch(() => ({}));
  const sub = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/subdomain');
  const subd = sub.json && sub.json.result && sub.json.result.subdomain;
  const url = subd ? 'https://' + c.name + '.' + subd + '.workers.dev' : '';
  // Post-deploy health gate: upload returning 200 is NOT enough — the RUNNING
  // worker must answer /health with the SAME version. This catches half-applied
  // scripts and the whole 1101 class before the user ever sees them.
  let health = { ok: false, status: 0 };
  if (url) {
    try {
      const hr = await F(url + '/health', { redirect: 'follow' });
      const hj = await hr.json().catch(() => null);
      health = { ok: !!(hr.ok && hj && hj.ok === true && hj.version === ver), status: hr.status, version: hj && hj.version };
    } catch (e) { health = { ok: false, status: 0, error: String((e && e.message) || e).slice(0, 60) }; }
  }
  return { ok: true, ver, url, ref: ref || 'latest release', health };
}

const setupChecklist = (c) =>
  '⚙️ <b>Secrets needed</b> (Workers → your bot → Settings → Variables → + Add):\n' +
  (c.token ? '✅' : '▫️') + ' <code>TG_TOKEN</code>\n' +
  (c.cf ? '✅' : '▫️') + ' <code>CF_API_TOKEN</code> (Workers Scripts:Edit + KV:Edit)\n' +
  (c.acc ? '✅' : '▫️') + ' <code>CF_ACCOUNT_ID</code>\n' +
  (c.admin ? '✅' : '▫️') + ' <code>TG_ADMIN_ID</code>\n' +
  'Then open /register link from /start.';

async function runCommand(F, c, text) {
  const parts = String(text || '').trim().split(/\s+/);
  const cmd = (parts[0] || '').toLowerCase().replace(/@.*$/, '');
  const arg = parts.slice(1);
  switch (cmd) {
    case '/start': case '/help': {
      const ready = c.token && c.cf && c.acc && c.admin;
      return '🐱 <b>Cat Deploy Bot</b> ' + BOT_VERSION + '\n🚀 یک دکمه = آخرین نسخهٔ پنل روی کلادفلر تو\n\n' +
        '/deploy [branch] — deploy/update the panel\n/doctor — چک کامل: توکن/ورکر/KV/health\n/rollback — برگشت به نسخهٔ قبلی\n/status — panel + token health\n\n' +
        (ready ? '✅ configured — send /deploy' : setupChecklist(c)) +
        '\n\nℹ️ من فقط دیپلوی می‌کنم؛ مدیریت پنل (کاربران/لینک‌ها) ربات داخل خود پنل است.';
    }
    case '/setup': return setupChecklist(c);
    case '/deploy': {
      if (!c.token || !c.cf || !c.acc) return setupChecklist(c);
      const ref = String(arg[0] || '').trim();
      if (ref && !/^[A-Za-z0-9._/-]{1,120}$/.test(ref)) return 'bad branch name';
      const ping = await tgApi(F, c.token, 'sendMessage', { text: '⏳ گرفتن ' + (ref || 'آخرین رلیز') + ' و دیپلوی روی کلادفلر…' });
      const res = await deployPanel(F, c, ref);
      if (!res.ok) return '🔴 deploy failed:\n<code>' + esc(res.error) + '</code>';
      const hl = res.health && res.health.ok
        ? '🫀 health OK — پنل زنده است و همین نسخه را سرو می‌کند'
        : '⚠️ health FAILED ' + (res.health && res.health.status ? '(HTTP ' + res.health.status + (res.health.version ? ' — سرو‌شده: ' + res.health.version : '') + ')' : (res.health && res.health.error ? '(' + res.health.error + ')' : '')) + '\n⏪ برگشت به نسخهٔ قبل: /rollback';
      return '✅ <b>Cat Panel ' + esc(res.ver) + '</b> deployed\n' + hl + '\n📦 منبع: ' + esc(res.ref) + '\n🔗 ' + (res.url ? '<code>' + esc(res.url) + '</code>' : '(workers.dev off — از داشبورد کلادفلر باز کن)') + '\n\n⚠️ دامنهٔ پنل عوض نشده؛ تنظیمات قبلی در KV می‌ماند.';
    }
    case '/doctor': {
      if (!c.cf || !c.acc) return setupChecklist(c);
      const acc = await cfApi(F, c, 'GET', '/accounts/' + c.acc);
      const scripts = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts');
      const has = scripts.ok && ((scripts.json && scripts.json.result) || []).some((x) => x.id === c.name || x.name === c.name);
      const kv = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/storage/kv/namespaces?per_page=100');
      const kvok = kv.ok && ((kv.json && kv.json.result) || []).some((n) => n.title === KV_TITLE);
      const sub = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/subdomain');
      const subd = sub.json && sub.json.result && sub.json.result.subdomain;
      let healthLine = '🟡 health — نشناختم (workers.dev خاموش؟)';
      if (subd) {
        try {
          const hr = await F('https://' + c.name + '.' + subd + '.workers.dev/health', { redirect: 'follow' });
          const hj = await hr.json().catch(() => null);
          healthLine = hr.ok && hj && hj.ok === true
            ? '✅ health v' + hj.version + ' — ورکر زنده است'
            : '🔴 health HTTP ' + hr.status + ' — ورکر down (ارور 1101؟) → /deploy بزن؛ اگر تکرار شد /rollback';
        } catch (e) { healthLine = '🔴 health: ' + esc(String((e && e.message) || e).slice(0, 60)); }
      }
      return '🩺 <b>گزارش وضعیت</b>\n' + [
        (acc.ok ? '✅' : '🔴') + ' حساب کلادفلر' + (acc.ok ? '' : ' — توکن یا Account ID نادرست'),
        (has ? '✅' : '🔴') + ' ورکر «' + c.name + '»' + (has ? '' : ' — وجود ندارد؛ اول /deploy'),
        (kvok ? '✅' : '🟡') + ' KV («' + KV_TITLE + '»)',
        healthLine,
      ].join('\n') + '\n\nقانون طلایی: آپدیت فقط با /deploy یا Actions — هیچ‌وقت paste در مرورگر موبایل.';
    }
    case '/rollback': {
      if (!c.cf || !c.acc) return setupChecklist(c);
      const list = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/versions');
      if (!list.ok) return '🔴 versions list failed (' + list.status + ')';
      const vs = Array.isArray(list.json && list.json.result) ? list.json.result : [];
      if (vs.length < 2) return '🟡 فقط یک نسخهٔ دیپلوی‌شده هست — چیزی برای برگرداندن نیست';
      const prev = vs[1];
      const rb = await cfApi(F, c, 'POST', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/versions/' + encodeURIComponent(prev.id) + '/rollback', {});
      if (!rb.ok) { const e0 = rb.json && rb.json.errors && rb.json.errors[0]; return '🔴 rollback failed (' + rb.status + (e0 ? ' — ' + esc(e0.code + ' ' + e0.message) : '') + ')'; }
      return '⏪ به نسخهٔ قبلی برگشت (' + esc(String(prev.number || prev.id).slice(0, 16)) + ') — حالا /doctor را بزن';
    }
    case '/status': {
      if (!c.cf || !c.acc) return setupChecklist(c);
      const v = await cfApi(F, c, 'GET', '/user/tokens/verify');
      const tokenOk = v.ok && v.json && v.json.result && v.json.result.status === 'active';
      const scr = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts');
      const names = ((scr.json && scr.json.result) || []).map((x) => x.id);
      return '🪪 token: ' + (tokenOk ? '✅ active' : '🔴 invalid (' + v.status + ')') + '\n📦 workers: ' + (names.length ? esc(names.join(', ')) : 'none') + '\n🎯 target: <code>' + esc(c.name) + '</code>\n📂 repo: <code>' + esc(c.repo) + '</code>';
    }
    default: return 'unknown — /help';
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const F = env.__fetch || fetch;
    const c = cfg(env);
    if (url.pathname === '/health') return new Response(JSON.stringify({ ok: true, bot: BOT_VERSION, configured: !!(c.token && c.cf && c.acc && c.admin) }), { headers: { 'content-type': 'application/json' } });

    // Self-register the Telegram webhook: /register?key=<first16(sha256(TG_TOKEN))>
    if (url.pathname === '/register') {
      if (!c.token) return new Response('set TG_TOKEN first', { status: 400 });
      const key = (await sha256hex(c.token)).slice(0, 16);
      if (url.searchParams.get('key') !== key) return new Response('bad key', { status: 403 });
      const secret = (await sha256hex(c.token)).slice(0, 32);
      const res = await tgApi(F, c.token, 'setWebhook', { url: url.origin + '/tg/' + secret, secret_token: secret, allowed_updates: ['message'], drop_pending_updates: true });
      return new Response(res.ok ? '🟢 webhook registered' : '🔴 ' + JSON.stringify(res), { status: res.ok ? 200 : 502, headers: { 'content-type': 'text/plain; charset=utf-8' } });
    }

    if (request.method === 'POST' && url.pathname.startsWith('/tg/')) {
      if (!c.token) return new Response(JSON.stringify({ ok: true, ignored: true }), { headers: { 'content-type': 'application/json' } });
      const secret = (await sha256hex(c.token)).slice(0, 32);
      if (url.pathname !== '/tg/' + secret || request.headers.get('x-telegram-bot-api-secret-token') !== secret) return new Response(JSON.stringify({ ok: false }), { status: 403, headers: { 'content-type': 'application/json' } });
      const up = await request.json().catch(() => ({}));
      const msg = up.message || {};
      const chatId = msg.chat && msg.chat.id;
      const from = msg.from && String(msg.from.id);
      if (!chatId || !msg.text) return new Response(JSON.stringify({ ok: true }), { headers: { 'content-type': 'application/json' } });
      let reply;
      if (!c.admin) reply = '⚙️ TG_ADMIN_ID خالی است. آیدی تو: <code>' + esc(from) + '</code> — آن را در Secrets بگذار و /register را دوباره باز کن.';
      else if (from !== c.admin) reply = '⛔ فقط ادمین (TG_ADMIN_ID)';
      else reply = await runCommand(F, c, msg.text);
      const withMenu = /^\s*\/(start|help)\b/.test(String(msg.text || ''));
      await tgApi(F, c.token, 'sendMessage', Object.assign({ chat_id: chatId, text: reply, parse_mode: 'HTML', disable_web_page_preview: true }, withMenu ? { reply_markup: { keyboard: [['/deploy'], ['/status', '/help']], resize_keyboard: true, is_persistent: true } } : {}));
      return new Response(JSON.stringify({ ok: true }), { headers: { 'content-type': 'application/json' } });
    }

    return new Response('🐱 Cat Deploy Bot — see docs/deploy-bot.md', { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8' } });
  },
};

export const _testing = { cfg, deployPanel, runCommand, sha256hex, panelSourceUrl, BOT_VERSION };
