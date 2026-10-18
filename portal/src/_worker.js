const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers } });
const enc = new TextEncoder();
const hex = bytes => [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, '0')).join('');
const bytes = n => crypto.getRandomValues(new Uint8Array(n));
const token = n => btoa(String.fromCharCode(...bytes(n))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const digest = async value => hex(await crypto.subtle.digest('SHA-256', enc.encode(value)));
const hmac = async (key, value, raw = false) => {
  const k = await crypto.subtle.importKey('raw', typeof key === 'string' ? enc.encode(key) : key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const out = await crypto.subtle.sign('HMAC', k, enc.encode(value));
  return raw ? new Uint8Array(out) : hex(out);
};
const safeEqual = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let x = 0; for (let i = 0; i < a.length; i++) x |= a.charCodeAt(i) ^ b.charCodeAt(i); return x === 0;
};
const body = async request => {
  if (Number(request.headers.get('content-length') || 0) > 32_768) throw new Error('body_too_large');
  return request.json().catch(() => { throw new Error('invalid_json'); });
};
const normalizeEmail = value => String(value || '').trim().toLowerCase();
const passwordHash = async (password, salt = hex(bytes(16))) => {
  if (typeof password !== 'string' || password.length < 10 || password.length > 200) throw new Error('weak_password');
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const out = await crypto.subtle.deriveBits({ name: 'PBKDF2', salt: enc.encode(salt), iterations: 210_000, hash: 'SHA-256' }, base, 256);
  return `pbkdf2-sha256$210000$${salt}$${hex(out)}`;
};
const verifyPassword = async (password, stored) => {
  const [kind, iterations, salt, expected] = String(stored || '').split('$');
  if (kind !== 'pbkdf2-sha256' || iterations !== '210000' || !salt || !expected) return false;
  const actual = (await passwordHash(password, salt)).split('$')[3];
  return safeEqual(actual, expected);
};
const cookie = (value, maxAge = 604800) => `cat_portal=${value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${maxAge}`;
async function issueSession(env, userId, request) {
  const plain = token(32), now = Date.now(), expires = now + 7 * 86400_000;
  await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,created_at,expires_at,user_agent) VALUES(?,?,?,?,?)')
    .bind(await digest(plain), userId, now, expires, String(request.headers.get('user-agent') || '').slice(0, 240)).run();
  return { plain, expires };
}
async function actor(env, request) {
  const plain = (request.headers.get('cookie') || '').match(/(?:^|;\s*)cat_portal=([^;]+)/)?.[1];
  if (!plain) return null;
  return env.DB.prepare(`SELECT u.id,u.email,u.display_name,u.telegram_id,u.created_at
    FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.enabled=1`)
    .bind(await digest(plain), Date.now()).first();
}
async function verifyTelegramInit(initData, botToken, maxAge = 600) {
  if (!botToken || typeof initData !== 'string' || initData.length > 8192) return null;
  const params = new URLSearchParams(initData), supplied = params.get('hash') || '';
  params.delete('hash'); params.delete('signature');
  const check = [...params.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}=${v}`).join('\n');
  const secret = await hmac('WebAppData', botToken, true);
  if (!safeEqual(await hmac(secret, check), supplied)) return null;
  const authDate = Number(params.get('auth_date'));
  if (!Number.isFinite(authDate) || Math.abs(Date.now() / 1000 - authDate) > maxAge) return null;
  try { const user = JSON.parse(params.get('user') || 'null'); return user?.id ? user : null; } catch { return null; }
}
async function telegramCall(env, method, payload) {
  if (!env.TELEGRAM_BOT_TOKEN) throw new Error('telegram_not_configured');
  const ctrl = new AbortController(), timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const response = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload), signal: ctrl.signal });
    if (!response.ok) throw new Error('telegram_api_error');
  } finally { clearTimeout(timer); }
}
async function telegramUser(env, tg, displayName) {
  const existing = await env.DB.prepare('SELECT id FROM users WHERE telegram_id=?').bind(String(tg.id)).first();
  if (existing) return existing.id;
  const id = crypto.randomUUID(), now = Date.now();
  await env.DB.prepare('INSERT INTO users(id,email,password_hash,display_name,telegram_id,enabled,created_at) VALUES(?,?,?,?,?,1,?)')
    .bind(id, null, null, String(displayName || tg.username || tg.first_name || `tg-${tg.id}`).slice(0, 80), String(tg.id), now).run();
  await env.DB.prepare('INSERT INTO audit_events(id,user_id,action,created_at) VALUES(?,?,?,?)').bind(crypto.randomUUID(), id, 'account.telegram_created', now).run();
  return id;
}
async function api(request, env, ctx, url) {
  if (request.method === 'GET' && url.pathname === '/api/health') return json({ ok: true, service: 'cat-portal', version: '0.1.0', time: new Date().toISOString() });
  if (request.method === 'POST' && url.pathname === '/api/register') {
    const input = await body(request), email = normalizeEmail(input.email);
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) return json({ error: 'invalid_email' }, 400);
    try {
      const id = crypto.randomUUID(), now = Date.now();
      await env.DB.prepare('INSERT INTO users(id,email,password_hash,display_name,telegram_id,enabled,created_at) VALUES(?,?,?,?,NULL,1,?)')
        .bind(id, email, await passwordHash(input.password), String(input.displayName || email.split('@')[0]).slice(0, 80), now).run();
      const session = await issueSession(env, id, request);
      return json({ ok: true }, 201, { 'set-cookie': cookie(session.plain) });
    } catch (error) { return json({ error: String(error).includes('UNIQUE') ? 'account_exists' : error.message }, 400); }
  }
  if (request.method === 'POST' && url.pathname === '/api/login') {
    const input = await body(request), email = normalizeEmail(input.email);
    const user = await env.DB.prepare('SELECT id,password_hash FROM users WHERE email=? AND enabled=1').bind(email).first();
    if (!user || !await verifyPassword(input.password, user.password_hash)) return json({ error: 'invalid_credentials' }, 401);
    const session = await issueSession(env, user.id, request);
    return json({ ok: true }, 200, { 'set-cookie': cookie(session.plain) });
  }
  if (request.method === 'POST' && url.pathname === '/api/telegram/miniapp') {
    const input = await body(request), tg = await verifyTelegramInit(input.initData, env.TELEGRAM_BOT_TOKEN);
    if (!tg) return json({ error: 'invalid_telegram_init_data' }, 401);
    const userId = await telegramUser(env, tg);
    const session = await issueSession(env, userId, request);
    return json({ ok: true }, 200, { 'set-cookie': cookie(session.plain) });
  }
  if (request.method === 'POST' && url.pathname === '/api/telegram/webhook') {
    if (!env.TELEGRAM_WEBHOOK_SECRET || !safeEqual(request.headers.get('x-telegram-bot-api-secret-token') || '', env.TELEGRAM_WEBHOOK_SECRET)) return json({ error: 'unauthorized' }, 401);
    const update = await body(request), message = update.message;
    if (message?.from?.id && /^\/start(?:@\w+)?/.test(message.text || '')) {
      await telegramUser(env, message.from);
      ctx.waitUntil(telegramCall(env, 'sendMessage', { chat_id: message.chat.id, text: 'حساب Cat شما آماده است. برای ورود امن، مینی‌اپ را باز کنید.', reply_markup: { inline_keyboard: [[{ text: 'باز کردن Cat Portal', web_app: { url: env.PORTAL_URL } }]] } }).catch(() => {}));
    }
    return json({ ok: true });
  }
  if (request.method === 'POST' && url.pathname === '/api/logout') {
    const plain = (request.headers.get('cookie') || '').match(/(?:^|;\s*)cat_portal=([^;]+)/)?.[1];
    if (plain) await env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(await digest(plain)).run();
    return json({ ok: true }, 200, { 'set-cookie': cookie('', 0) });
  }
  const user = await actor(env, request);
  if (request.method === 'GET' && url.pathname === '/api/me') return user ? json({ ok: true, user }) : json({ error: 'unauthorized' }, 401);
  if (request.method === 'GET' && url.pathname === '/api/deployments') {
    if (!user) return json({ error: 'unauthorized' }, 401);
    const rows = await env.DB.prepare('SELECT id,name,target,url,status,last_probe_at,created_at FROM deployments WHERE user_id=? ORDER BY created_at DESC').bind(user.id).all();
    return json({ ok: true, deployments: rows.results || [] });
  }
  return json({ error: 'not_found' }, 404);
}
export { verifyTelegramInit };
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (url.pathname.startsWith('/api/')) return await api(request, env, ctx, url);
      return env.ASSETS.fetch(request);
    } catch (error) {
      console.error('portal_request_failed', { path: url.pathname, type: error?.name || 'Error' });
      const status = error?.message === 'body_too_large' ? 413 : 500;
      return json({ error: status === 500 ? 'internal_error' : error.message, requestId: crypto.randomUUID() }, status);
    }
  },
  async scheduled(_event, env) {
    await env.DB.prepare('DELETE FROM sessions WHERE expires_at<=?').bind(Date.now()).run();
  },
};
