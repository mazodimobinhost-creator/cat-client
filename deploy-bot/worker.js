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
 * "Deploy to Cloudflare" button, then configured with 5 secrets:
 *
 *   TG_TOKEN       Telegram bot token (@BotFather)
 *   CF_API_TOKEN   Cloudflare token: Workers Scripts:Edit + Workers KV Storage:Edit
 *   CF_ACCOUNT_ID  Cloudflare account id (dashboard → Workers → right side)
 *   TG_ADMIN_ID    your numeric Telegram id (@userinfobot)
 *   PANEL_UUID    stable master UUID; generate once and keep it unchanged
 * Optional:
 *   WORKER_NAME    deployed worker name (default "cat-panel")
 *   GH_REPO        panel repo               (default mazodimobinhost-creator/cat-client)
 *
 * After adding secrets: compute the first 16 hex chars of SHA-256(TG_TOKEN)
 * locally, then open <bot-url>/register?key=<hash>. Use commands only in the
 * bot's private chat. New panels are locked by default; UUID/KV/custom bindings
 * are preserved on update, and readiness requires a matching /health response.
 * The Cloudflare token lives ONLY in this worker's secrets — never in chat,
 * never in the repo. Send /revoke in BotFather if a token ever leaks.
 */

const BOT_VERSION = '2.3.0';
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
    panelUuid: String(env.PANEL_UUID || '').trim().toLowerCase(),
    name: String(env.WORKER_NAME || DEFAULT_WORKER).replace(/[^A-Za-z0-9_-]/g, '') || DEFAULT_WORKER,
    repo: String(env.GH_REPO || DEFAULT_REPO).replace(/[^A-Za-z0-9._/-]/g, '') || DEFAULT_REPO,
    autoHeal: String(env.AUTO_HEAL == null ? '1' : env.AUTO_HEAL).trim() !== '0',
    healFailures: Math.min(3, Math.max(2, Number.parseInt(env.HEAL_FAILURE_THRESHOLD || '2', 10) || 2)),
    safeRef: /^[A-Za-z0-9._/-]{1,120}$/.test(String(env.SAFE_REF || '').trim()) ? String(env.SAFE_REF).trim() : '',
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

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isUuid = (value) => UUID_RE.test(String(value || '').trim());

/** Read bindings before replacement so updates keep the panel identity and KV. */
async function readExistingSettings(F, c) {
  const res = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/settings');
  if (res.status === 404) return { ok: true, exists: false, bindings: [] };
  if (!res.ok) return { ok: false, error: 'could not read current Worker settings (' + res.status + '); update stopped to avoid losing its identity or bindings' };
  const bindings = res.json && res.json.result && res.json.result.bindings;
  if (!Array.isArray(bindings)) return { ok: false, error: 'Cloudflare returned no readable binding list; update stopped to avoid rotating panel access' };
  return { ok: true, exists: true, bindings };
}

/** Keep readable custom bindings and preserve secret values server-side. */
function inspectExistingBindings(bindings) {
  const result = { uuid: '', kvId: '', extras: [], keepSecrets: false, hasPanelPassword: false, unsafeManagedSecret: '' };
  for (const binding of bindings || []) {
    if (!binding || !binding.name) continue;
    const name = String(binding.name);
    const type = String(binding.type || '');
    if (name === 'UUID') {
      if (type === 'plain_text' && isUuid(binding.text)) result.uuid = String(binding.text).toLowerCase();
      else if (type === 'secret_text') result.unsafeManagedSecret = name;
      continue;
    }
    if (name === 'CAT_KV') {
      if (type === 'kv_namespace') result.kvId = String(binding.namespace_id || '');
      continue;
    }
    // These flags are deliberately forced closed on every deploy.
    if (name === 'OPEN_PANEL' || name === 'OPEN_SUB') {
      if (type === 'secret_text') result.unsafeManagedSecret = name;
      continue;
    }
    if (name === 'PANEL_PASSWORD') result.hasPanelPassword = true;
    if (type === 'secret_text') {
      result.keepSecrets = true;
      continue;
    }
    // Bindings returned by Cloudflare are JSON-safe metadata. Copy them so a
    // panel update does not silently remove an owner's custom plain-text/KV/etc.
    try { result.extras.push(JSON.parse(JSON.stringify(binding))); } catch (e) { }
  }
  return result;
}

function panelSourceUrl(c, ref) {
  if (ref) return 'https://raw.githubusercontent.com/' + c.repo + '/refs/heads/' + ref + '/app/src/main/assets/panels/catclient.worker.js'; // ref is regex-validated (slashes allowed)
  return 'https://github.com/' + c.repo + '/releases/latest/download/catclient.worker.js';
}

function cmpVer(a, b) {
  const pa = String(a || '').split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b || '').split('.').map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** Fetch the panel worker script (branch or latest release) and deploy it. */
async function deployPanel(F, c, ref) {
  if (!isUuid(c.panelUuid)) {
    return { ok: false, error: 'PANEL_UUID is missing or invalid. Add a stable UUID as the bot Worker Secret before deploying; no Cloudflare request was made.' };
  }
  const branchUrl = 'https://raw.githubusercontent.com/' + c.repo + '/refs/heads/arena/c5b36768-cat-client/dist-panel/catpanel.min.js';
  let src = await F(panelSourceUrl(c, ref), { redirect: 'follow', headers: { 'user-agent': 'cat-deploy-bot' } });
  if (!src.ok && !ref) {
    try {
      const bRes = await F(branchUrl, { redirect: 'follow', headers: { 'user-agent': 'cat-deploy-bot' } });
      if (bRes && bRes.ok) src = bRes;
    } catch (e) { /* keep original error */ }
  }
  if (!src.ok) return { ok: false, error: 'source ' + src.status + (ref ? ' — branch «' + ref + '» پیدا نشد' : ' — releases/latest در دسترس نیست') };
  let code = await src.text();
  if (!code.includes('export default') || !code.includes('CAT_PANEL_VERSION')) return { ok: false, error: 'source fetched but does not look like catclient.worker.js' };
  let ver = (code.match(/CAT_PANEL_VERSION = '([^']+)'/) || [])[1] || '?';
  if (ver === '6.69.12') return { ok: false, error: 'source v6.69.12 is blocked (known 1101 production failure)' };
  if (!ref && ver !== '?' && cmpVer(ver, '6.69.25') < 0) {
    try {
      const bRes = await F(branchUrl, { redirect: 'follow', headers: { 'user-agent': 'cat-deploy-bot' } });
      if (bRes && bRes.ok) {
        const bCode = await bRes.text();
        const bVer = (bCode.match(/CAT_PANEL_VERSION = '([^']+)'/) || [])[1] || '';
        if (bCode.includes('export default') && bVer && bVer !== '6.69.12' && cmpVer(bVer, ver) > 0) {
          code = bCode;
          ver = bVer;
        }
      }
    } catch (e) { /* keep release source */ }
  }

  // Read before replacing metadata: preserve identity, storage and user bindings.
  const previous = await readExistingSettings(F, c);
  if (!previous.ok) return { ok: false, error: previous.error };
  const old = inspectExistingBindings(previous.bindings);
  if (old.unsafeManagedSecret) {
    return { ok: false, error: 'existing ' + old.unsafeManagedSecret + ' is a secret binding; remove it or repair the worker in Cloudflare Dashboard before updating' };
  }
  // The bot's PANEL_UUID Worker Secret is the persistent source of truth.
  // Never mint a replacement during deploy; the deployed panel gets this value
  // as its compatibility binding named UUID.
  const uuid = c.panelUuid;
  const uuidRotated = previous.exists && old.uuid !== uuid;

  // Reuse the actual bound namespace first (including Wizard-created KV names),
  // then the legacy fixed name; never silently replace a panel's data store.
  let kvId = old.kvId;
  let kvReused = !!kvId;
  if (!kvId) {
    const list = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/storage/kv/namespaces?per_page=100');
    if (!list.ok) return { ok: false, error: 'KV list failed (' + list.status + '); update stopped to protect existing panel data' };
    const namespaces = Array.isArray(list.json && list.json.result) ? list.json.result : [];
    const ns = namespaces.find((n) => n && n.title === KV_TITLE);
    if (ns && ns.id) { kvId = ns.id; kvReused = true; }
    else {
      const made = await cfApi(F, c, 'POST', '/accounts/' + c.acc + '/storage/kv/namespaces', { title: KV_TITLE });
      if (!made.ok) return { ok: false, error: 'KV create failed (' + made.status + ')' };
      kvId = made.json && made.json.result && made.json.result.id;
      if (!kvId) return { ok: false, error: 'KV create returned no namespace id; update stopped before upload' };
    }
  }

  // Locked by default. The stable PANEL_UUID secret supplies the Worker UUID
  // binding, initial password and master subscription secret on every deploy.
  const bindings = [
    { type: 'plain_text', name: 'UUID', text: uuid },
    { type: 'kv_namespace', name: 'CAT_KV', namespace_id: kvId },
    { type: 'plain_text', name: 'OPEN_PANEL', text: 'false' },
    { type: 'plain_text', name: 'OPEN_SUB', text: 'false' },
    ...old.extras,
  ];
  const meta = {
    main_module: 'worker.js',
    compatibility_date: '2026-09-01',
    bindings,
    ...(old.keepSecrets ? { keep_secrets: true } : {}),
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
  // Upload success is not readiness: only the running, matching version passes.
  let health = { ok: false, status: 0 };
  if (url) {
    try {
      const hr = await F(url + '/health', { redirect: 'follow' });
      const hj = await hr.json().catch(() => null);
      health = { ok: !!(hr.ok && hj && hj.ok === true && hj.version === ver), status: hr.status, version: hj && hj.version };
    } catch (e) { health = { ok: false, status: 0, error: String((e && e.message) || e).slice(0, 60) }; }
  }
  return {
    ok: true, ver, url, ref: ref || 'latest release', health, uuid,
    subUrl: url ? url + '/sub/' + uuid : '',
    firstInstall: !previous.exists && !kvReused,
    uuidRotated,
    kvReused,
    hasPanelPassword: old.hasPanelPassword,
  };
}

const setupChecklist = (c) => {
  const panelUuidValid = isUuid(c.panelUuid);
  const panelUuidNote = panelUuidValid
    ? ''
    : c.panelUuid
      ? '\n❗ PANEL_UUID is present but invalid. Replace it with a valid UUID Secret; deploy is blocked until fixed.'
      : '\n❗ PANEL_UUID is required. Generate one once locally, add it as a Secret, and keep it unchanged across bot redeploys.';
  return '⚙️ <b>Secrets needed</b> (Workers → your bot → Settings → Variables → + Add → type Secret):\n' +
    (c.token ? '✅' : '▫️') + ' <code>TG_TOKEN</code>\n' +
    (c.cf ? '✅' : '▫️') + ' <code>CF_API_TOKEN</code> (Workers Scripts:Edit + KV:Edit)\n' +
    (c.acc ? '✅' : '▫️') + ' <code>CF_ACCOUNT_ID</code>\n' +
    (c.admin ? '✅' : '▫️') + ' <code>TG_ADMIN_ID</code>\n' +
    (panelUuidValid ? '✅' : c.panelUuid ? '🔴' : '▫️') + ' <code>PANEL_UUID</code> (stable UUID Secret; copied to the deployed panel as <code>UUID</code>)' +
    panelUuidNote + '\nThen compute the TG_TOKEN hash locally and open /register?key=… (see docs/deploy-bot.md).';
};

async function workerOrigin(F, c) {
  const sub = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/subdomain');
  const label = sub.json && sub.json.result && sub.json.result.subdomain;
  return label ? 'https://' + c.name + '.' + label + '.workers.dev' : '';
}

async function probePanel(F, c) {
  const origin = await workerOrigin(F, c);
  if (!origin) return { ok: false, status: 0, error: 'workers.dev subdomain is unavailable' };
  try {
    const response = await F(origin + '/health', { redirect: 'follow', headers: { 'cache-control': 'no-cache' } });
    const body = await response.json().catch(() => null);
    return { ok: !!(response.ok && body && body.ok === true), status: response.status, version: body && body.version || '', origin };
  } catch (error) {
    return { ok: false, status: 0, error: String(error && error.message || error), origin };
  }
}

async function rollbackPrevious(F, c) {
  const list = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/versions');
  const versions = Array.isArray(list.json && list.json.result) ? list.json.result : [];
  if (!list.ok || versions.length < 2) return { ok: false, error: list.ok ? 'no previous version' : 'versions list ' + list.status };
  const previous = versions[1];
  const result = await cfApi(F, c, 'POST', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/versions/' + encodeURIComponent(previous.id) + '/rollback', {});
  return { ok: result.ok, status: result.status, version: previous.number || previous.id };
}

/** Repair only after a real failed health probe; preserve every existing binding. */
async function repairPanel(F, c) {
  const attempts = [];
  for (let i = 0; i < c.healFailures; i++) {
    const probe = await probePanel(F, c);
    attempts.push(probe);
    // Any successful probe clears the run. This prevents a single transient edge
    // failure from turning into a destructive redeploy.
    if (probe.ok) return { ok: true, action: 'none', before: probe, attempts };
  }
  const before = attempts[attempts.length - 1];
  const deployed = await deployPanel(F, c, c.safeRef);
  if (deployed.ok && deployed.health && deployed.health.ok) return { ok: true, action: 'redeployed', before, attempts, deployed };
  const rollback = await rollbackPrevious(F, c);
  const after = rollback.ok ? await probePanel(F, c) : { ok: false };
  return { ok: false, action: rollback.ok ? 'rolled-back' : 'failed', before, attempts, deployed, rollback, after };
}

async function runCommand(F, c, text, chatId) {
  const parts = String(text || '').trim().split(/\s+/);
  const cmd = (parts[0] || '').toLowerCase().replace(/@.*$/, '');
  const arg = parts.slice(1);
  switch (cmd) {
    case '/start': case '/help': {
      const ready = c.token && c.cf && c.acc && c.admin && isUuid(c.panelUuid);
      return '🐱 <b>Cat Deploy Bot</b> ' + BOT_VERSION + '\n🚀 یک دکمه = آخرین نسخهٔ پنل روی کلادفلر تو\n\n' +
        '/deploy [branch] — deploy/update the panel\n/doctor — چک کامل: توکن/ورکر/KV/health\n/repair — ترمیم 1101؛ فقط اگر health خراب باشد\n/save — نمایش نسخهٔ ذخیره‌شده و bindingهای محافظت‌شده\n/rollback — برگشت به نسخهٔ قبلی\n/status — panel + token health\n\n' +
        (ready ? '✅ configured — send /deploy' : setupChecklist(c)) +
        '\n\nℹ️ من فقط دیپلوی می‌کنم؛ مدیریت پنل (کاربران/لینک‌ها) ربات داخل خود پنل است.';
    }
    case '/setup': return setupChecklist(c);
    case '/deploy': {
      if (!c.token || !c.cf || !c.acc || !isUuid(c.panelUuid)) return setupChecklist(c);
      const ref = String(arg[0] || '').trim();
      if (ref && !/^[A-Za-z0-9._/-]{1,120}$/.test(ref)) return 'bad branch name';
      if (chatId !== undefined && chatId !== null) {
        await tgApi(F, c.token, 'sendMessage', { chat_id: chatId, text: '⏳ گرفتن ' + (ref || 'آخرین رلیز') + ' و دیپلوی روی کلادفلر…', protect_content: true });
      }
      const res = await deployPanel(F, c, ref);
      if (!res.ok) return '🔴 deploy failed:\n<code>' + esc(res.error) + '</code>';
      const verified = !!(res.health && res.health.ok);
      const title = verified
        ? '✅ <b>Cat Panel ' + esc(res.ver) + '</b> دیپلوی و سلامت نسخه تأیید شد'
        : '⚠️ <b>Cat Panel ' + esc(res.ver) + '</b> آپلود شد، اما سلامت آن تأیید نشد';
      const healthLine = verified
        ? '🫀 health OK — Worker همان نسخه را سرو می‌کند'
        : '🔴 health ناموفق' + (res.health && res.health.status ? ' (HTTP ' + res.health.status + (res.health.version ? '؛ نسخهٔ پاسخ‌داده‌شده: ' + esc(res.health.version) : '') + ')' : (res.health && res.health.error ? ' (' + esc(res.health.error) + ')' : '')) + ' — پنل را آماده/سالم فرض نکن؛ /doctor یا /rollback را اجرا کن.';
      const lines = [
        title,
        healthLine,
        '📦 منبع: ' + esc(res.ref),
        '🔗 پنل: ' + (res.url ? '<code>' + esc(res.url) + '</code>' : '(نشانی workers.dev در دسترس نیست؛ از داشبورد Cloudflare بررسی کن)'),
        '🧬 UUID اصلی (از PANEL_UUID): <code>' + esc(res.uuid) + '</code>',
      ];
      if (res.subUrl) lines.push('📥 اشتراک اصلی: <code>' + esc(res.subUrl) + '</code>');
      if (res.firstInstall && !res.hasPanelPassword) {
        lines.push('🔑 رمز اولیهٔ پنل = UUID ثابت PANEL_UUID بالاست؛ پس از ورود، اگر خواستی آن را از تنظیمات پنل تغییر بده.');
      } else if (res.hasPanelPassword) {
        lines.push('🔒 binding رمز اختصاصی قبلی حفظ شد؛ ربات مقدار مخفی آن را نمی‌خواند.');
      } else {
        lines.push('🔑 اگر رمز پنل را قبلاً عوض کرده‌ای، همان رمز ذخیره‌شده حفظ است؛ در غیر این صورت رمز پیش‌فرض PANEL_UUID است.');
      }
      if (res.uuidRotated) lines.push('⚠️ UUID قبلی خالی یا متفاوت بود؛ secret ثابت PANEL_UUID روی Worker اعمال شد. لینک‌های اشتراک قبلی عوض می‌شوند و باید لینک جدید را وارد کنی؛ داده‌های KV به همین صورت حفظ می‌شوند.');
      lines.push(res.kvReused ? '💾 KV قبلی دوباره وصل شد.' : '💾 KV تازه ساخته شد.');
      return lines.join('\n');
    }
    case '/save': {
      if (!c.cf || !c.acc) return setupChecklist(c);
      const settings = await readExistingSettings(F, c);
      if (!settings.ok || !settings.exists) return '🔴 ورکر یا تنظیماتش خوانده نشد؛ چیزی برای ذخیره‌سازی تأیید نشد.';
      const versions = await cfApi(F, c, 'GET', '/accounts/' + c.acc + '/workers/scripts/' + c.name + '/versions');
      const list = Array.isArray(versions.json && versions.json.result) ? versions.json.result : [];
      const names = settings.bindings.map((b) => b && b.name).filter(Boolean);
      return '💾 <b>نسخهٔ امن ثبت است</b>\nCloudflare هر deploy را به‌صورت version نگه می‌دارد.' +
        '\n📦 آخرین نسخه: <code>' + esc(list[0] && (list[0].number || list[0].id) || 'نامشخص') + '</code>' +
        '\n🧷 bindingهای قابل بازیابی: <code>' + esc(names.join(', ') || 'none') + '</code>' +
        '\n🔐 مقدار Secretها خوانده یا چاپ نشد؛ در deploy با keep_secrets حفظ می‌شود.';
    }
    case '/repair': case '/heal': {
      if (!c.cf || !c.acc || !isUuid(c.panelUuid)) return setupChecklist(c);
      const result = await repairPanel(F, c);
      if (result.action === 'none') return '✅ health سالم است؛ برای جلوگیری از ریسک، هیچ deploy یا تغییری انجام نشد.';
      if (result.action === 'redeployed') return '🛠 خودترمیم انجام شد: health ' + c.healFailures + ' بار خراب بود، نسخهٔ معتبر دوباره deploy شد و health v' + esc(result.deployed.ver) + ' تأیید شد. KV، D1، UUID و Secretهای موجود حفظ شدند.';
      return '🔴 ترمیم نسخهٔ تازه سالم نشد؛ ' + (result.action === 'rolled-back' ? 'برای جلوگیری از قطع بیشتر به نسخهٔ قبلی rollback شد.' : 'rollback هم در دسترس نبود.') + ' /doctor را بررسی کن.';
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
      const rb = await rollbackPrevious(F, c);
      if (!rb.ok) return rb.error === 'no previous version' ? '🟡 فقط یک نسخهٔ دیپلوی‌شده هست — چیزی برای برگرداندن نیست' : '🔴 rollback failed (' + esc(rb.status || rb.error) + ')';
      return '⏪ به نسخهٔ قبلی برگشت (' + esc(String(rb.version).slice(0, 16)) + ') — حالا /doctor را بزن';
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
    if (url.pathname === '/health') return new Response(JSON.stringify({ ok: true, bot: BOT_VERSION, configured: !!(c.token && c.cf && c.acc && c.admin && isUuid(c.panelUuid)) }), { headers: { 'content-type': 'application/json' } });

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
      const chat = msg.chat || {};
      const chatId = chat.id;
      const from = msg.from && String(msg.from.id);
      if (!chatId || !msg.text) return new Response(JSON.stringify({ ok: true }), { headers: { 'content-type': 'application/json' } });
      const privateChat = chat.type === 'private' && String(chatId) === String(from);
      let reply;
      if (!privateChat) reply = '🔒 برای امنیت، ربات دیپلوی را فقط در گفت‌وگوی خصوصی با خودش استفاده کن.';
      else if (!c.admin) reply = '⚙️ TG_ADMIN_ID خالی است. آیدی تو: <code>' + esc(from) + '</code> — آن را در Secrets بگذار و /register را دوباره باز کن.';
      else if (from !== c.admin) reply = '⛔ فقط ادمین (TG_ADMIN_ID)';
      else reply = await runCommand(F, c, msg.text, chatId);
      const text = String(msg.text || '');
      const withMenu = /^\s*\/(start|help)\b/.test(text);
      const isDeploy = /^\s*\/deploy(?:@\w+)?(?:\s|$)/i.test(text);
      const outgoing = Object.assign({ chat_id: chatId, text: reply, parse_mode: 'HTML', disable_web_page_preview: true }, withMenu ? { reply_markup: { keyboard: [['/deploy'], ['/status', '/help']], resize_keyboard: true, is_persistent: true } } : {});
      // Deployment replies include the master UUID/subscription credential.
      if (isDeploy) outgoing.protect_content = true;
      await tgApi(F, c.token, 'sendMessage', outgoing);
      return new Response(JSON.stringify({ ok: true }), { headers: { 'content-type': 'application/json' } });
    }

    return new Response('🐱 Cat Deploy Bot — see docs/deploy-bot.md', { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8' } });
  },
  async scheduled(_event, env, ctx) {
    const F = env.__fetch || fetch;
    const c = cfg(env);
    if (!c.autoHeal || !c.cf || !c.acc || !isUuid(c.panelUuid)) return;
    ctx.waitUntil((async () => {
      const result = await repairPanel(F, c);
      if (result.action === 'none' || !c.token || !c.admin) return;
      const text = result.action === 'redeployed'
        ? '🛠 خودترمیم خودکار: health خراب بود؛ نسخهٔ ' + esc(result.deployed.ver) + ' دوباره deploy و تأیید شد.'
        : '🚨 خودترمیم خودکار موفق نشد؛ وضعیت: ' + esc(result.action) + '. /doctor را اجرا کن.';
      await tgApi(F, c.token, 'sendMessage', { chat_id: c.admin, text, parse_mode: 'HTML', protect_content: true });
    })());
  },
};

export const _testing = { cfg, deployPanel, repairPanel, probePanel, rollbackPrevious, runCommand, sha256hex, panelSourceUrl, BOT_VERSION };
