/**
 * Cat Wizard — one-click installer for Cat Panel (Cloudflare Worker).
 * ---------------------------------------------------------------------
 * Deploy THIS file once on your own Cloudflare account (the app does it for
 * you from the Cloud tab, or paste it in Workers & Pages → Create Worker).
 * Share the URL. Every visitor:
 *   1. taps "Get Cloudflare token" → the Cloudflare dashboard opens with the
 *      exact permissions pre-selected (Continue to summary → Create → Copy)
 *   2. pastes the token here
 *   3. taps Install → the wizard creates (on THEIR account): the workers.dev
 *      subdomain, a KV namespace, the Cat Panel worker with UUID / password
 *      bindings, enables the route and hands back panel URL + password + sub.
 *
 * The token is only used in-flight for that request and never stored.
 *
 * Env (all optional):
 *  PANEL_SOURCE_URL   Override where the Cat Panel worker source is fetched from.
 *  WIZARD_SOURCE_URL  Override for the "private wizard" self-copy source.
 *  WIZARD_PASSWORD    Invite code visitors must enter before installing.
 *  WIZARD_TITLE       Header title (default "Cat Wizard").
 *  DEFAULT_WORKER     Default worker name suggested to visitors (default "catpanel").
 */

const CAT_WIZARD_VERSION = '1.0.0';
const REPO = 'mazodimobinhost-creator/cat-client';
const REPO_URL = 'https://github.com/' + REPO;
const BRANCH = 'arena/01a0c678-cat-client';
const CF_API = 'https://api.cloudflare.com/client/v4';
const COMPAT_DATE = '2025-03-04';

const PANEL_SOURCES = [
  REPO_URL + '/releases/latest/download/catclient.worker.js',
  'https://raw.githubusercontent.com/' + REPO + '/' + BRANCH + '/app/src/main/assets/panels/catclient.worker.js',
  'https://raw.githubusercontent.com/' + REPO + '/main/app/src/main/assets/panels/catclient.worker.js',
];
const WIZARD_SOURCES = [
  REPO_URL + '/releases/latest/download/catclient.wizard.js',
  'https://raw.githubusercontent.com/' + REPO + '/' + BRANCH + '/app/src/main/assets/panels/catclient.wizard.js',
  'https://raw.githubusercontent.com/' + REPO + '/main/app/src/main/assets/panels/catclient.wizard.js',
];

/* Cloudflare "API token template URL" — opens the dashboard with the
 * permissions pre-selected. https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/ */
const TOKEN_PERMISSIONS = [
  { key: 'workers_scripts', type: 'edit' },
  { key: 'workers_kv_storage', type: 'edit' },
  { key: 'account_settings', type: 'read' },
  { key: 'user_details', type: 'read' },
];
const TOKEN_TEMPLATE_URL = 'https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=' +
  encodeURIComponent(JSON.stringify(TOKEN_PERMISSIONS)) + '&accountId=*&zoneId=all&name=Cat%20Panel';

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type, authorization',
};

/* injectable fetch so tests / local previews can fake the Cloudflare API */
let fetchImpl = (input, init) => fetch(input, init);
function __setFetch(fn) { fetchImpl = fn || ((input, init) => fetch(input, init)); }

/* ------------------------------------------------------------------ */
/* small helpers                                                       */
/* ------------------------------------------------------------------ */

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function jsonResponse(value, status = 200, extra = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: Object.assign({ 'content-type': 'application/json; charset=utf-8' }, CORS, extra),
  });
}

function slugWorkerName(value, fallback) {
  const slug = String(value || '').toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-{2,}/g, '-').replace(/^-+|-+$/g, '');
  return (slug || fallback || 'catpanel').slice(0, 54);
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(value || ''));
}

function newUuid() {
  if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
}

function randomSuffix(len = 6) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ------------------------------------------------------------------ */
/* Cloudflare API client                                               */
/* ------------------------------------------------------------------ */

async function cf(token, method, path, body, opts = {}) {
  const headers = { authorization: 'Bearer ' + token };
  let payload;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers['content-type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetchImpl(CF_API + path, { method, headers, body: payload });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch (e) { json = { success: res.ok, errors: [{ message: text.slice(0, 300) }] }; }
  if (opts.raw) return { status: res.status, json };
  return { status: res.status, json, ok: res.ok && json && json.success !== false };
}

function cfError(result, fallback) {
  const errors = result && result.json && Array.isArray(result.json.errors) ? result.json.errors : [];
  const msg = errors.map((e) => (e && (e.message || e.code)) || '').filter(Boolean).join('; ');
  return msg || fallback || ('HTTP ' + (result && result.status));
}

async function verifyToken(token) {
  const verify = await cf(token, 'GET', '/user/tokens/verify');
  if (!verify.ok) return { ok: false, error: 'invalid-token', detail: cfError(verify, 'token rejected') };
  const accounts = await cf(token, 'GET', '/accounts?per_page=50');
  const list = (accounts.ok && Array.isArray(accounts.json.result)) ? accounts.json.result : [];
  if (!list.length) return { ok: false, error: 'no-account', detail: cfError(accounts, 'token cannot list accounts (needs Account Settings: Read)') };
  return {
    ok: true,
    status: verify.json.result && verify.json.result.status,
    accounts: list.map((a) => ({ id: a.id, name: a.name || '' })),
  };
}

async function detectScopes(token, accountId) {
  const workers = await cf(token, 'GET', '/accounts/' + accountId + '/workers/subdomain', undefined, { raw: true });
  const kv = await cf(token, 'GET', '/accounts/' + accountId + '/storage/kv/namespaces?per_page=1', undefined, { raw: true });
  return {
    workers: workers.status !== 401 && workers.status !== 403,
    kv: kv.status !== 401 && kv.status !== 403,
    subdomain: (workers.json && workers.json.result && workers.json.result.subdomain) || '',
  };
}

async function ensureSubdomain(token, accountId, log) {
  const current = await cf(token, 'GET', '/accounts/' + accountId + '/workers/subdomain');
  const existing = current.ok && current.json.result && current.json.result.subdomain;
  if (existing) return existing;
  const candidate = 'catpanel-' + randomSuffix(8);
  log('subdomain', 'info', 'no workers.dev subdomain yet — creating ' + candidate);
  const created = await cf(token, 'PUT', '/accounts/' + accountId + '/workers/subdomain', { subdomain: candidate });
  const made = created.ok && created.json.result && created.json.result.subdomain;
  if (!made) throw new Error('workers.dev subdomain: ' + cfError(created, 'could not create'));
  return made;
}

async function ensureKv(token, accountId, title) {
  const base = '/accounts/' + accountId + '/storage/kv/namespaces';
  const listing = await cf(token, 'GET', base + '?per_page=100');
  if (listing.ok && Array.isArray(listing.json.result)) {
    const hit = listing.json.result.find((ns) => ns && ns.title === title);
    if (hit) return { id: hit.id, created: false };
  }
  const created = await cf(token, 'POST', base, { title });
  const id = created.ok && created.json.result && created.json.result.id;
  if (!id) throw new Error('KV: ' + cfError(created, 'could not create namespace'));
  return { id, created: true };
}

/** Existing bindings of a worker (so a re-install keeps the UUID/password). */
async function existingBindings(token, accountId, name) {
  const res = await cf(token, 'GET', '/accounts/' + accountId + '/workers/scripts/' + name + '/settings', undefined, { raw: true });
  const bindings = res.json && res.json.result && Array.isArray(res.json.result.bindings) ? res.json.result.bindings : [];
  const out = {};
  for (const b of bindings) {
    if (!b || !b.name) continue;
    if (b.type === 'plain_text') out[b.name] = String(b.text || '');
    if (b.type === 'kv_namespace') out['kv:' + b.name] = String(b.namespace_id || '');
  }
  return out;
}

async function uploadWorker(token, accountId, name, script, bindings) {
  const metadata = {
    main_module: 'worker.js',
    bindings,
    compatibility_date: COMPAT_DATE,
    compatibility_flags: ['nodejs_compat'],
  };
  const form = new FormData();
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }), 'metadata.json');
  form.append('worker.js', new Blob([script], { type: 'application/javascript+module' }), 'worker.js');
  const res = await cf(token, 'PUT', '/accounts/' + accountId + '/workers/scripts/' + name, form);
  if (!res.ok) throw new Error('upload: ' + cfError(res, 'worker upload failed'));
  return res.json.result || {};
}

async function enableRoute(token, accountId, name) {
  const res = await cf(token, 'POST', '/accounts/' + accountId + '/workers/scripts/' + name + '/subdomain', { enabled: true, previews_enabled: false });
  return res.ok;
}

/* ------------------------------------------------------------------ */
/* sources                                                             */
/* ------------------------------------------------------------------ */

const sourceCache = new Map();

async function fetchSource(kind, env) {
  const override = kind === 'wizard' ? env.WIZARD_SOURCE_URL : env.PANEL_SOURCE_URL;
  const marker = kind === 'wizard' ? 'CAT_WIZARD_VERSION' : 'CAT_PANEL_VERSION';
  const urls = [].concat(override ? [String(override)] : [], kind === 'wizard' ? WIZARD_SOURCES : PANEL_SOURCES);
  const cached = sourceCache.get(kind);
  if (cached && Date.now() - cached.at < 10 * 60 * 1000) return cached;
  let lastError = '';
  for (const url of urls) {
    try {
      const res = await fetchImpl(url, { headers: { 'user-agent': 'cat-wizard/' + CAT_WIZARD_VERSION }, redirect: 'follow' });
      if (!res.ok) { lastError = url + ' → HTTP ' + res.status; continue; }
      const text = await res.text();
      if (!text.includes(marker) || text.length < 2000) { lastError = url + ' → not a Cat ' + kind + ' source'; continue; }
      const versionMatch = text.match(new RegExp(marker + "\\s*=\\s*'([^']+)'"));
      const entry = { text, url, version: versionMatch ? versionMatch[1] : '?', at: Date.now() };
      sourceCache.set(kind, entry);
      return entry;
    } catch (e) {
      lastError = url + ' → ' + (e && e.message ? e.message : e);
    }
  }
  throw new Error('could not download the ' + kind + ' source (' + lastError + ')');
}

/* ------------------------------------------------------------------ */
/* the install flow (async generator → streamed as NDJSON)             */
/* ------------------------------------------------------------------ */

async function* runInstall(input, env) {
  const events = [];
  const log = (step, level, msg, data) => { events.push({ step, level, msg, data }); };
  const flush = function* () { while (events.length) yield events.shift(); };

  const token = String(input.token || '').trim();
  const kind = input.kind === 'wizard' ? 'wizard' : 'panel';
  const workerName = slugWorkerName(input.workerName, kind === 'wizard' ? 'cat-wizard' : String(env.DEFAULT_WORKER || 'catpanel'));
  const customPassword = String(input.password || '').trim();
  if (!token) throw new Error('token missing');

  log('verify', 'info', 'verifying token'); yield* flush();
  const verified = await verifyToken(token);
  if (!verified.ok) throw new Error(verified.detail || verified.error);
  const account = (input.accountId && verified.accounts.find((a) => a.id === input.accountId)) || verified.accounts[0];
  log('verify', 'ok', 'token OK · account: ' + (account.name || account.id), { account }); yield* flush();

  log('source', 'info', 'downloading latest Cat ' + (kind === 'wizard' ? 'Wizard' : 'Panel') + ' source'); yield* flush();
  const source = await fetchSource(kind, env);
  log('source', 'ok', 'source v' + source.version + ' (' + Math.round(source.text.length / 1024) + ' KB)', { version: source.version, url: source.url }); yield* flush();

  log('subdomain', 'info', 'checking workers.dev subdomain'); yield* flush();
  const subdomain = await ensureSubdomain(token, account.id, log);
  const workerUrl = 'https://' + workerName + '.' + subdomain + '.workers.dev';
  log('subdomain', 'ok', subdomain + '.workers.dev', { subdomain }); yield* flush();

  const previous = await existingBindings(token, account.id, workerName).catch(() => ({}));
  if (Object.keys(previous).length) { log('existing', 'info', 'worker "' + workerName + '" exists — updating in place, secrets are kept'); yield* flush(); }

  const bindings = [];
  let uuid = '';
  let kvBound = false;
  if (kind === 'panel') {
    uuid = isUuid(previous.UUID) ? previous.UUID : (isUuid(input.uuid) ? String(input.uuid).toLowerCase() : newUuid());
    bindings.push({ type: 'plain_text', name: 'UUID', text: uuid });
    if (customPassword) bindings.push({ type: 'secret_text', name: 'PANEL_PASSWORD', text: customPassword });
    log('kv', 'info', 'creating KV storage (users, clean IPs, ports)'); yield* flush();
    try {
      const kv = previous['kv:CAT_KV']
        ? { id: previous['kv:CAT_KV'], created: false }
        : await ensureKv(token, account.id, workerName + '-catpanel');
      bindings.push({ type: 'kv_namespace', name: 'CAT_KV', namespace_id: kv.id });
      kvBound = true;
      log('kv', 'ok', kv.created ? 'KV namespace created' : 'KV namespace reused', { id: kv.id });
    } catch (e) {
      log('kv', 'warn', 'KV skipped (' + (e && e.message ? e.message : e) + ') — panel still works, settings live in the link');
    }
    yield* flush();
  } else if (env.WIZARD_PASSWORD) {
    bindings.push({ type: 'secret_text', name: 'WIZARD_PASSWORD', text: String(env.WIZARD_PASSWORD) });
  }

  log('upload', 'info', 'uploading worker "' + workerName + '"'); yield* flush();
  await uploadWorker(token, account.id, workerName, source.text, bindings);
  log('upload', 'ok', 'worker deployed'); yield* flush();

  log('route', 'info', 'enabling workers.dev route'); yield* flush();
  const routed = await enableRoute(token, account.id, workerName);
  log('route', routed ? 'ok' : 'warn', routed ? 'route enabled' : 'could not toggle the route (enable it in the dashboard if the URL 404s)'); yield* flush();

  log('check', 'info', 'waiting for ' + workerUrl); yield* flush();
  let online = false;
  let version = '';
  for (let attempt = 1; attempt <= 6 && !online; attempt++) {
    try {
      const res = await fetchImpl(workerUrl + '/health', { headers: { 'cache-control': 'no-cache' } });
      if (res.ok) {
        const body = await res.json().catch(() => ({}));
        online = body && body.ok === true;
        version = body && body.version ? String(body.version) : '';
      }
    } catch (e) { /* propagation */ }
    if (!online) await sleep(attempt < 3 ? 1500 : 3000);
  }
  log('check', online ? 'ok' : 'warn', online ? 'online · v' + version : 'not reachable yet — workers.dev needs up to a minute for a brand-new subdomain'); yield* flush();

  const password = customPassword || uuid;
  const result = kind === 'panel'
    ? {
      ok: true,
      kind,
      workerName,
      workerUrl,
      panelUrl: workerUrl + '/?p=' + encodeURIComponent(password),
      subUrl: workerUrl + '/sub/' + uuid,
      subClash: workerUrl + '/sub/' + uuid + '/clash',
      subSingbox: workerUrl + '/sub/' + uuid + '/singbox',
      deepLink: 'catclient://add-sub?url=' + encodeURIComponent(workerUrl + '/sub/' + uuid) + '&name=' + encodeURIComponent('Cat Panel'),
      uuid,
      password,
      customPassword: !!customPassword,
      kvBound,
      online,
      panelVersion: version || source.version,
      account: account.name || account.id,
    }
    : { ok: true, kind, workerName, workerUrl, online, account: account.name || account.id, wizardVersion: source.version };
  yield { done: true, result };
}

/* ------------------------------------------------------------------ */
/* invite code + abuse guard                                           */
/* ------------------------------------------------------------------ */

const attempts = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const rec = attempts.get(ip) || { count: 0, since: now };
  if (now - rec.since > 60 * 60 * 1000) { rec.count = 0; rec.since = now; }
  rec.count += 1;
  attempts.set(ip, rec);
  return rec.count > 20;
}

function inviteOk(env, supplied) {
  const required = String(env.WIZARD_PASSWORD || '').trim();
  return !required || required === String(supplied || '').trim();
}

/* ------------------------------------------------------------------ */
/* UI                                                                  */
/* ------------------------------------------------------------------ */

function catLogo(size) {
  return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 64 64" fill="none" aria-hidden="true">' +
    '<path d="M14 26 10 9l14 9h16l14-9-4 17c3 4 4 8 4 13 0 13-11 20-22 20S10 52 10 39c0-5 1-9 4-13Z" fill="url(#g)"/>' +
    '<circle cx="24" cy="38" r="3.4" fill="#fff"/><circle cx="40" cy="38" r="3.4" fill="#fff"/>' +
    '<path d="M29 46h6l-3 3.5Z" fill="#fff"/><defs><linearGradient id="g" x1="10" y1="9" x2="54" y2="59"><stop stop-color="#a855f7"/><stop offset="1" stop-color="#6d28d9"/></linearGradient></defs></svg>';
}

function css() {
  return [
    ':root{--bg:#06030c;--bg2:#0d0618;--card:rgba(255,255,255,.045);--card2:rgba(255,255,255,.08);--line:rgba(168,85,247,.28);',
    '--text:#f5f3ff;--muted:#b7aed1;--accent:#a855f7;--accent2:#7c3aed;--ok:#34d399;--warn:#fbbf24;--err:#fb7185;--radius:18px}',
    '*{box-sizing:border-box}html,body{margin:0;min-height:100%}',
    'body{font-family:"Vazirmatn",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:radial-gradient(1200px 600px at 80% -10%,rgba(124,58,237,.35),transparent 60%),radial-gradient(800px 500px at -10% 110%,rgba(168,85,247,.22),transparent 60%),var(--bg);color:var(--text);line-height:1.6}',
    'body[data-lang="fa"]{direction:rtl}body[data-lang="en"]{direction:ltr}',
    '.wrap{max-width:720px;margin:0 auto;padding:22px 16px 60px}',
    '.top{display:flex;align-items:center;gap:12px;margin-bottom:22px}.top .brand{display:flex;align-items:center;gap:10px;font-weight:800;font-size:20px}',
    '.top small{display:block;font-weight:500;color:var(--muted);font-size:12px}.spacer{flex:1}',
    '.icon-btn{border:1px solid var(--line);background:var(--card);color:var(--text);border-radius:12px;padding:7px 12px;cursor:pointer;font:inherit}',
    '.card{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:18px;margin-bottom:14px;backdrop-filter:blur(10px)}',
    '.card h2{margin:0 0 6px;font-size:16px;display:flex;align-items:center;gap:8px}.card h2 .n{width:26px;height:26px;border-radius:50%;display:inline-grid;place-items:center;background:linear-gradient(135deg,var(--accent),var(--accent2));color:#fff;font-size:13px;font-weight:800}',
    '.muted{color:var(--muted);font-size:13px}p{margin:6px 0}',
    '.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:14px;padding:12px 18px;font:inherit;font-weight:700;cursor:pointer;color:#fff;background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 10px 30px rgba(124,58,237,.35);text-decoration:none;transition:transform .12s}',
    '.btn:hover{transform:translateY(-1px)}.btn:disabled{opacity:.5;cursor:not-allowed;transform:none}',
    '.btn.ghost{background:var(--card2);box-shadow:none;border:1px solid var(--line)}.btn.block{width:100%}.btn.sm{padding:8px 12px;font-size:13px;border-radius:10px}',
    '.field{display:block;margin:12px 0}.field span{display:block;font-size:13px;color:var(--muted);margin-bottom:6px}',
    '.field input{width:100%;border:1px solid var(--line);background:rgba(0,0,0,.35);color:var(--text);border-radius:12px;padding:12px 14px;font:inherit;font-size:14px;direction:ltr;text-align:left}',
    '.field input:focus{outline:2px solid var(--accent);outline-offset:1px}',
    '.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.grow{flex:1;min-width:180px}',
    '.steps{display:grid;gap:8px;margin:10px 0}.step{display:flex;gap:10px;align-items:flex-start;background:rgba(0,0,0,.25);border:1px solid rgba(255,255,255,.06);border-radius:12px;padding:10px 12px;font-size:13px}',
    '.step b{display:inline-grid;place-items:center;min-width:22px;height:22px;border-radius:50%;background:var(--card2);font-size:12px}',
    'details{margin-top:10px}summary{cursor:pointer;color:var(--muted);font-size:13px}',
    '.log{background:#000;border:1px solid rgba(255,255,255,.08);border-radius:12px;padding:12px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;direction:ltr;text-align:left;max-height:260px;overflow:auto;white-space:pre-wrap}',
    '.log .ok{color:var(--ok)}.log .warn{color:var(--warn)}.log .error{color:var(--err)}.log .info{color:#c4b5fd}',
    '.result{display:none}.result.show{display:block}.kv{display:grid;gap:10px}.kv div{background:rgba(0,0,0,.3);border:1px solid rgba(255,255,255,.06);border-radius:12px;padding:10px 12px}',
    '.kv label{display:block;font-size:12px;color:var(--muted);margin-bottom:4px}.kv code{display:block;direction:ltr;text-align:left;word-break:break-all;font-size:13px}',
    '.pill{display:inline-block;padding:3px 10px;border-radius:999px;font-size:12px;border:1px solid var(--line);background:var(--card2)}.pill.ok{color:var(--ok)}.pill.warn{color:var(--warn)}',
    '.qr{display:grid;place-items:center;margin:12px 0}.qr img{background:#fff;border-radius:14px;padding:8px;width:200px;height:200px}',
    '.hide{display:none!important}.err{color:var(--err);font-size:13px}',
    '.progress{height:6px;border-radius:999px;background:rgba(255,255,255,.08);overflow:hidden;margin:10px 0}.progress i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--accent),var(--accent2));transition:width .4s}',
    'footer{color:var(--muted);font-size:12px;text-align:center;margin-top:20px}footer a{color:var(--accent)}',
  ].join('');
}

function i18n() {
  return {
    fa: {
      sub: 'نصب پنل کلودفلر شخصی در یک دقیقه',
      s1: 'دریافت توکن کلودفلر',
      s1p: 'دکمهٔ زیر صفحهٔ API Token کلودفلر را با دسترسی‌های لازم باز می‌کند. فقط کافی است Continue to summary → Create Token را بزنی و توکن را کپی کنی.',
      s1b: 'دریافت توکن از کلودفلر',
      s1h1: 'اگر حساب کلودفلر نداری، همان‌جا با ایمیل رایگان بساز.',
      s1h2: 'در صفحهٔ باز شده چیزی را تغییر نده؛ پایین صفحه Continue to summary و بعد Create Token.',
      s1h3: 'توکن فقط یک بار نمایش داده می‌شود — Copy را بزن.',
      s2: 'توکن را اینجا بچسبان',
      s2p: 'توکن فقط برای همین نصب استفاده می‌شود و هیچ‌جا ذخیره نمی‌شود. بعد از نصب می‌توانی آن را از کلودفلر حذف کنی.',
      token: 'Cloudflare API Token',
      invite: 'کد دعوت',
      check: 'بررسی توکن',
      s3: 'نصب پنل',
      name: 'نام ورکر (اختیاری)',
      pass: 'رمز پنل (اختیاری — پیش‌فرض UUID)',
      adv: 'تنظیمات بیشتر',
      install: 'نصب Cat Panel روی حساب من',
      installWizard: 'ساخت ویزارد خصوصی خودم',
      wizardHint: 'یک نسخهٔ خصوصی از همین ویزارد روی حساب خودت می‌سازد تا لینکش را به دیگران بدهی.',
      log: 'گزارش نصب',
      done: 'پنل آماده است 🎉',
      panel: 'آدرس پنل (با رمز باز می‌شود)',
      password: 'رمز پنل',
      subl: 'لینک سابسکریپشن (کپی کن داخل Cat Client / v2rayNG)',
      uuid: 'UUID',
      open: 'باز کردن پنل',
      copy: 'کپی',
      copied: 'کپی شد',
      app: 'افزودن به Cat Client',
      apk: 'دانلود اپ',
      keep: 'این آدرس و رمز را یک‌جا نگه دار. بعد از ورود، از تب «کانفیگ‌ها» پورت و SNI را انتخاب کن؛ آی‌پی تمیز خودکار داخل کانفیگ‌ها می‌رود.',
      wizardDone: 'ویزارد خصوصی آماده شد',
      wizardUrl: 'آدرس ویزارد تو',
      offline: 'هنوز بالا نیامده — یک دقیقه بعد آدرس را دوباره باز کن.',
      online: 'آنلاین',
      accountOk: 'حساب: ',
      needToken: 'اول توکن را بچسبان',
      failed: 'نصب ناموفق: ',
      scopeWarn: 'توکن دسترسی Workers KV ندارد؛ پنل نصب می‌شود ولی ذخیره‌سازی تنظیمات محدود است.',
    },
    en: {
      sub: 'Your own Cloudflare panel in one minute',
      s1: 'Get a Cloudflare token',
      s1p: 'The button opens the Cloudflare API-token page with the right permissions pre-selected. Just hit Continue to summary → Create Token and copy it.',
      s1b: 'Get token from Cloudflare',
      s1h1: 'No Cloudflare account? Create a free one right there.',
      s1h2: 'Do not change anything on that page; scroll down, Continue to summary, then Create Token.',
      s1h3: 'The token is shown once — press Copy.',
      s2: 'Paste the token here',
      s2p: 'The token is used only for this install and is never stored. You can revoke it in Cloudflare afterwards.',
      token: 'Cloudflare API Token',
      invite: 'Invite code',
      check: 'Check token',
      s3: 'Install the panel',
      name: 'Worker name (optional)',
      pass: 'Panel password (optional — UUID by default)',
      adv: 'More options',
      install: 'Install Cat Panel on my account',
      installWizard: 'Create my own private wizard',
      wizardHint: 'Deploys a private copy of this wizard on your account so you can share your own link.',
      log: 'Install log',
      done: 'Your panel is ready 🎉',
      panel: 'Panel URL (opens unlocked)',
      password: 'Panel password',
      subl: 'Subscription link (paste into Cat Client / v2rayNG)',
      uuid: 'UUID',
      open: 'Open panel',
      copy: 'Copy',
      copied: 'Copied',
      app: 'Add to Cat Client',
      apk: 'Download app',
      keep: 'Keep this URL and password together. After logging in, pick ports and SNI in the Configs tab; clean IPs are baked into the configs automatically.',
      wizardDone: 'Private wizard ready',
      wizardUrl: 'Your wizard URL',
      offline: 'Not up yet — reopen the URL in a minute.',
      online: 'online',
      accountOk: 'Account: ',
      needToken: 'Paste the token first',
      failed: 'Install failed: ',
      scopeWarn: 'Token lacks Workers KV; the panel installs but settings storage is limited.',
    },
  };
}

function pageHtml(env, host) {
  const title = String(env.WIZARD_TITLE || 'Cat Wizard');
  const inviteRequired = !!String(env.WIZARD_PASSWORD || '').trim();
  const state = {
    version: CAT_WIZARD_VERSION,
    title,
    host,
    tokenUrl: TOKEN_TEMPLATE_URL,
    inviteRequired,
    defaultWorker: String(env.DEFAULT_WORKER || 'catpanel'),
    repo: REPO_URL,
    i18n: i18n(),
  };
  const t = state.i18n.fa;
  return '<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' +
    '<meta name="theme-color" content="#06030c"><title>' + esc(title) + '</title>' +
    '<link rel="icon" href="data:image/svg+xml,' + encodeURIComponent(catLogo(48)) + '">' +
    '<style>' + css() + '</style></head><body data-lang="fa">' +
    '<div class="wrap">' +
    '<header class="top"><div class="brand">' + catLogo(34) + '<span>' + esc(title) + '<small data-i18n="sub">' + t.sub + '</small></span></div>' +
    '<span class="spacer"></span><button class="icon-btn" id="langBtn">EN</button></header>' +

    '<section class="card"><h2><span class="n">1</span><span data-i18n="s1">' + t.s1 + '</span></h2>' +
    '<p class="muted" data-i18n="s1p">' + t.s1p + '</p>' +
    '<a class="btn block" id="tokenBtn" href="' + esc(TOKEN_TEMPLATE_URL) + '" target="_blank" rel="noopener">🔑 <span data-i18n="s1b">' + t.s1b + '</span></a>' +
    '<div class="steps"><div class="step"><b>۱</b><span data-i18n="s1h1">' + t.s1h1 + '</span></div>' +
    '<div class="step"><b>۲</b><span data-i18n="s1h2">' + t.s1h2 + '</span></div>' +
    '<div class="step"><b>۳</b><span data-i18n="s1h3">' + t.s1h3 + '</span></div></div></section>' +

    '<section class="card"><h2><span class="n">2</span><span data-i18n="s2">' + t.s2 + '</span></h2>' +
    '<p class="muted" data-i18n="s2p">' + t.s2p + '</p>' +
    '<label class="field"><span data-i18n="token">' + t.token + '</span><input id="token" type="password" autocomplete="off" spellcheck="false" placeholder="••••••••••••••••••••••••••••••••••••••••"></label>' +
    (inviteRequired ? '<label class="field"><span data-i18n="invite">' + t.invite + '</span><input id="invite" type="text" autocomplete="off"></label>' : '') +
    '<div class="row"><button class="btn ghost sm" id="showTok">👁</button><button class="btn ghost sm" id="verifyBtn" data-i18n="check">' + t.check + '</button><span id="acct" class="pill hide"></span></div>' +
    '<p class="err hide" id="verifyErr"></p></section>' +

    '<section class="card"><h2><span class="n">3</span><span data-i18n="s3">' + t.s3 + '</span></h2>' +
    '<details><summary data-i18n="adv">' + t.adv + '</summary>' +
    '<label class="field"><span data-i18n="name">' + t.name + '</span><input id="wname" value="' + esc(state.defaultWorker) + '"></label>' +
    '<label class="field"><span data-i18n="pass">' + t.pass + '</span><input id="wpass" autocomplete="new-password"></label></details>' +
    '<div class="progress"><i id="bar"></i></div>' +
    '<button class="btn block" id="installBtn">🚀 <span data-i18n="install">' + t.install + '</span></button>' +
    '<div class="row" style="margin-top:10px"><button class="btn ghost sm" id="wizardBtn" data-i18n="installWizard">' + t.installWizard + '</button><span class="muted" data-i18n="wizardHint">' + t.wizardHint + '</span></div>' +
    '<p class="err hide" id="installErr"></p></section>' +

    '<section class="card result" id="result">' +
    '<h2>✅ <span id="resultTitle" data-i18n="done">' + t.done + '</span> <span class="pill" id="onlinePill"></span></h2>' +
    '<div class="kv" id="panelResult">' +
    '<div><label data-i18n="panel">' + t.panel + '</label><code id="rPanel"></code><div class="row" style="margin-top:6px"><a class="btn sm" id="rOpen" target="_blank" rel="noopener" data-i18n="open">' + t.open + '</a><button class="btn ghost sm" data-copy="rPanel" data-i18n="copy">' + t.copy + '</button></div></div>' +
    '<div><label data-i18n="password">' + t.password + '</label><code id="rPass"></code><button class="btn ghost sm" data-copy="rPass" data-i18n="copy" style="margin-top:6px">' + t.copy + '</button></div>' +
    '<div><label data-i18n="subl">' + t.subl + '</label><code id="rSub"></code><div class="row" style="margin-top:6px"><button class="btn ghost sm" data-copy="rSub" data-i18n="copy">' + t.copy + '</button><a class="btn ghost sm" id="rApp" data-i18n="app">' + t.app + '</a><a class="btn ghost sm" id="rApk" target="_blank" rel="noopener" href="' + esc(REPO_URL) + '/releases/latest" data-i18n="apk">' + t.apk + '</a></div></div>' +
    '<div class="qr"><img id="rQr" alt="QR" hidden></div>' +
    '<p class="muted" data-i18n="keep">' + t.keep + '</p>' +
    '</div>' +
    '<div class="kv hide" id="wizardResult"><div><label data-i18n="wizardUrl">' + t.wizardUrl + '</label><code id="rWiz"></code><div class="row" style="margin-top:6px"><a class="btn sm" id="rWizOpen" target="_blank" rel="noopener" data-i18n="open">' + t.open + '</a><button class="btn ghost sm" data-copy="rWiz" data-i18n="copy">' + t.copy + '</button></div></div></div>' +
    '</section>' +

    '<section class="card"><h2>📜 <span data-i18n="log">' + t.log + '</span></h2><div class="log" id="log">…</div></section>' +
    '<footer>Cat Wizard v' + CAT_WIZARD_VERSION + ' · <a href="' + esc(REPO_URL) + '" target="_blank" rel="noopener">GitHub</a> · ' + esc(host) + '</footer>' +
    '</div>' +
    '<script>window.CAT_WIZARD=' + JSON.stringify(state).replace(/</g, '\\u003c') + ';</script>' +
    '<script>' + clientJs() + '</script>' +
    '</body></html>';
}

function clientJs() {
  return [
    '(function(){',
    'var S=window.CAT_WIZARD||{};var $=function(s){return document.querySelector(s)};var lang="fa";',
    'function t(k){return (S.i18n[lang]||{})[k]||(S.i18n.fa||{})[k]||k}',
    'function applyLang(){document.body.setAttribute("data-lang",lang);document.documentElement.setAttribute("dir",lang==="fa"?"rtl":"ltr");document.documentElement.setAttribute("lang",lang);',
    ' document.querySelectorAll("[data-i18n]").forEach(function(el){el.textContent=t(el.getAttribute("data-i18n"))});$("#langBtn").textContent=lang==="fa"?"EN":"فا";}',
    '$("#langBtn").addEventListener("click",function(){lang=lang==="fa"?"en":"fa";try{localStorage.setItem("catwiz.lang",lang)}catch(e){}applyLang()});',
    'try{lang=localStorage.getItem("catwiz.lang")||"fa"}catch(e){}applyLang();',
    '$("#showTok").addEventListener("click",function(){var i=$("#token");i.type=i.type==="password"?"text":"password"});',
    'function logLine(level,msg){var el=$("#log");if(el.textContent==="…")el.textContent="";var d=document.createElement("div");d.className=level;d.textContent="["+new Date().toLocaleTimeString()+"] "+msg;el.appendChild(d);el.scrollTop=el.scrollHeight}',
    'function payload(extra){var p={token:$("#token").value.trim(),invite:$("#invite")?$("#invite").value.trim():""};for(var k in extra)p[k]=extra[k];return p}',
    'function copyText(text,btn){var done=function(){var old=btn.textContent;btn.textContent=t("copied");setTimeout(function(){btn.textContent=old},1200)};',
    ' if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(text).then(done,function(){fallback()})}else fallback();',
    ' function fallback(){var ta=document.createElement("textarea");ta.value=text;document.body.appendChild(ta);ta.select();try{document.execCommand("copy")}catch(e){}document.body.removeChild(ta);done()}}',
    'document.querySelectorAll("[data-copy]").forEach(function(b){b.addEventListener("click",function(){copyText($("#"+b.getAttribute("data-copy")).textContent,b)})});',
    '$("#verifyBtn").addEventListener("click",function(){var err=$("#verifyErr");err.classList.add("hide");$("#acct").classList.add("hide");',
    ' if(!$("#token").value.trim()){err.textContent=t("needToken");err.classList.remove("hide");return}',
    ' $("#verifyBtn").disabled=true;fetch("/api/verify",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(payload({}))}).then(function(r){return r.json()}).then(function(j){',
    '  $("#verifyBtn").disabled=false;if(!j.ok){err.textContent=j.error+(j.detail?" — "+j.detail:"");err.classList.remove("hide");return}',
    '  var a=$("#acct");a.textContent=t("accountOk")+(j.accounts[0].name||j.accounts[0].id)+(j.scopes&&!j.scopes.kv?" · ⚠ KV":"");a.className="pill ok";logLine("ok","token OK · "+(j.accounts[0].name||j.accounts[0].id));',
    '  if(j.scopes&&!j.scopes.kv)logLine("warn",t("scopeWarn"));',
    ' }).catch(function(e){$("#verifyBtn").disabled=false;err.textContent=String(e);err.classList.remove("hide")})});',
    'var STEPS=["verify","source","subdomain","kv","upload","route","check"];',
    'function install(kind){var err=$("#installErr");err.classList.add("hide");$("#result").classList.remove("show");',
    ' if(!$("#token").value.trim()){err.textContent=t("needToken");err.classList.remove("hide");return}',
    ' $("#installBtn").disabled=true;$("#wizardBtn").disabled=true;$("#bar").style.width="4%";$("#log").textContent="…";',
    ' var body=payload({kind:kind,workerName:$("#wname").value,password:$("#wpass").value});',
    ' fetch("/api/install",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)}).then(function(res){',
    '  if(!res.ok&&res.headers.get("content-type")&&res.headers.get("content-type").indexOf("json")>=0&&res.headers.get("content-type").indexOf("ndjson")<0){return res.json().then(function(j){throw new Error(j.error||("HTTP "+res.status))})}',
    '  var reader=res.body.getReader();var dec=new TextDecoder();var buf="";',
    '  function handle(line){if(!line.trim())return;var ev;try{ev=JSON.parse(line)}catch(e){return}',
    '   if(ev.done){if(ev.ok===false){throw new Error(ev.msg||"failed")}showResult(ev.result);return}',
    '   logLine(ev.level||"info",(ev.step?ev.step+": ":"")+ev.msg);var i=STEPS.indexOf(ev.step);if(i>=0)$("#bar").style.width=Math.round(((i+1)/STEPS.length)*96)+"%"}',
    '  return reader.read().then(function step(r){if(r.done){if(buf)handle(buf);return}buf+=dec.decode(r.value,{stream:true});var parts=buf.split("\\n");buf=parts.pop();parts.forEach(handle);return reader.read().then(step)})',
    ' }).then(function(){$("#installBtn").disabled=false;$("#wizardBtn").disabled=false}).catch(function(e){$("#installBtn").disabled=false;$("#wizardBtn").disabled=false;$("#bar").style.width="0";err.textContent=t("failed")+e.message;err.classList.remove("hide");logLine("error",e.message)})}',
    'function showResult(r){$("#bar").style.width="100%";var box=$("#result");box.classList.add("show");var pill=$("#onlinePill");pill.textContent=r.online?t("online"):t("offline");pill.className="pill "+(r.online?"ok":"warn");',
    ' if(r.kind==="wizard"){$("#resultTitle").textContent=t("wizardDone");$("#panelResult").classList.add("hide");$("#wizardResult").classList.remove("hide");$("#rWiz").textContent=r.workerUrl;$("#rWizOpen").href=r.workerUrl;}',
    ' else{$("#resultTitle").textContent=t("done");$("#panelResult").classList.remove("hide");$("#wizardResult").classList.add("hide");$("#rPanel").textContent=r.panelUrl;$("#rOpen").href=r.panelUrl;$("#rPass").textContent=r.password;$("#rSub").textContent=r.subUrl;$("#rApp").href=r.deepLink;',
    '  var qr=$("#rQr");if(r.online){qr.src=r.workerUrl+"/qr.svg?d="+encodeURIComponent(r.subUrl)+"&size=6";qr.hidden=false}else{qr.hidden=true}}',
    ' box.scrollIntoView({behavior:"smooth"})}',
    '$("#installBtn").addEventListener("click",function(){install("panel")});',
    '$("#wizardBtn").addEventListener("click",function(){install("wizard")});',
    '})();',
  ].join('');
}

/* ------------------------------------------------------------------ */
/* router                                                              */
/* ------------------------------------------------------------------ */

async function readJson(request) {
  try { return await request.json(); } catch (e) { return {}; }
}

async function fetchHandler(request, env, ctx) {
  const url = new URL(request.url);
  const host = (request.headers.get('Host') || url.hostname || '').toLowerCase();
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

  if (path === '/health') {
    return jsonResponse({ ok: true, wizard: 'cat-wizard', version: CAT_WIZARD_VERSION, inviteRequired: !!String(env.WIZARD_PASSWORD || '').trim(), tokenUrl: TOKEN_TEMPLATE_URL });
  }
  if (path === '/token' || path === '/get-token') {
    return Response.redirect(TOKEN_TEMPLATE_URL, 302);
  }
  if (path === '/api/token-url') {
    return jsonResponse({ ok: true, url: TOKEN_TEMPLATE_URL, permissions: TOKEN_PERMISSIONS });
  }

  if (path === '/api/verify' && request.method === 'POST') {
    const body = await readJson(request);
    if (!inviteOk(env, body.invite)) return jsonResponse({ ok: false, error: 'invite-required' }, 403);
    const ip = request.headers.get('cf-connecting-ip') || 'local';
    if (rateLimited(ip)) return jsonResponse({ ok: false, error: 'rate-limited' }, 429);
    const token = String(body.token || '').trim();
    if (!token) return jsonResponse({ ok: false, error: 'token-missing' }, 400);
    const verified = await verifyToken(token);
    if (!verified.ok) return jsonResponse(verified, 200);
    const scopes = await detectScopes(token, verified.accounts[0].id).catch(() => null);
    return jsonResponse(Object.assign({}, verified, { scopes }));
  }

  if ((path === '/api/install' || path === '/api/deploy') && request.method === 'POST') {
    const body = await readJson(request);
    if (!inviteOk(env, body.invite)) return jsonResponse({ ok: false, error: 'invite-required' }, 403);
    const ip = request.headers.get('cf-connecting-ip') || 'local';
    if (rateLimited(ip)) return jsonResponse({ ok: false, error: 'rate-limited' }, 429);
    if (!String(body.token || '').trim()) return jsonResponse({ ok: false, error: 'token-missing' }, 400);

    const { readable, writable } = new TransformStream();
    const writer = writable.getWriter();
    const enc = new TextEncoder();
    const pump = (async () => {
      try {
        for await (const ev of runInstall(body, env)) {
          await writer.write(enc.encode(JSON.stringify(ev) + '\n'));
        }
      } catch (e) {
        await writer.write(enc.encode(JSON.stringify({ done: true, ok: false, level: 'error', msg: e && e.message ? e.message : String(e) }) + '\n')).catch(() => {});
      } finally {
        await writer.close().catch(() => {});
      }
    })();
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(pump);
    return new Response(readable, {
      status: 200,
      headers: Object.assign({ 'content-type': 'application/x-ndjson; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }, CORS),
    });
  }

  if (path === '/' || path === '/index.html' || path === '/wizard') {
    return new Response(pageHtml(env, host), {
      headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'referrer-policy': 'no-referrer' },
    });
  }
  return new Response('Not Found', { status: 404, headers: CORS });
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await fetchHandler(request, env || {}, ctx);
    } catch (e) {
      return new Response('Cat Wizard error: ' + (e && e.message ? e.message : e), { status: 500, headers: { 'content-type': 'text/plain; charset=utf-8' } });
    }
  },
};

export const _testing = {
  CAT_WIZARD_VERSION,
  TOKEN_TEMPLATE_URL,
  TOKEN_PERMISSIONS,
  PANEL_SOURCES,
  WIZARD_SOURCES,
  __setFetch,
  runInstall,
  verifyToken,
  slugWorkerName,
  fetchSource,
  sourceCache,
  pageHtml,
  clientJs,
};
