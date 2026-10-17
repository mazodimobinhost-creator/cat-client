import http from 'node:http';
import { connect, isIP } from 'node:net';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { XrayRuntime } from './xray-runtime.js';
import { AuthStore } from './auth-store.js';
import { isPublicIp, probeCandidate } from './candidate-scanner.js';
import { buildAutoPlan } from './auto-plan.js';
import { buildVlessWsInbound } from './inbound-builder.js';
import { BackupStore } from './backup-store.js';
import { TelegramBot } from './telegram-bot.js';

export const VERSION = '0.11.0';
const startedAt = Date.now();
const dataDir = process.env.CAT_DATA_DIR || join(dirname(fileURLToPath(import.meta.url)), 'data');
const stateFile = join(dataDir, 'state.json');
const adminToken = process.env.CAT_ADMIN_TOKEN || '';
const auth = new AuthStore(dataDir);
const backups = new BackupStore(dataDir, process.env.CAT_BACKUP_KEY);
const telegram = new TelegramBot({ token: process.env.CAT_TELEGRAM_BOT_TOKEN, adminId: process.env.CAT_TELEGRAM_ADMIN_ID, secret: process.env.CAT_TELEGRAM_WEBHOOK_SECRET });
const xray = new XrayRuntime({
  binary: process.env.CAT_XRAY_BINARY || join(dataDir, 'bin', 'xray'),
  expectedSha256: process.env.CAT_XRAY_SHA256 || '',
  dataDir: join(dataDir, 'xray'),
});

const json = (res, status, body) => {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'content-length': Buffer.byteLength(text), 'x-content-type-options': 'nosniff' });
  res.end(text);
};
const hostname = value => {
  const text = String(value || '').trim().toLowerCase().replace(/\.$/, '');
  return text.length <= 253 && text.split('.').length >= 2 && text.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
};
export function normalizeCandidate(input) {
  const address = String(input?.address || '').trim().replace(/^\[|\]$/g, '');
  const sni = String(input?.sni || '').trim().toLowerCase();
  const port = Number(input?.port || 443);
  const country = String(input?.country || '').trim().toUpperCase();
  if (!isIP(address) || !isPublicIp(address) || !hostname(sni) || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  if (country && !/^[A-Z]{2}$/.test(country)) return null;
  const evidence = input?.evidence && typeof input.evidence === 'object' ? {
    ok: input.evidence.ok === true,
    tls: input.evidence.tls === true,
    httpStatus: Number(input.evidence.httpStatus) || 0,
    latencyMs: Math.max(0, Number(input.evidence.latencyMs) || 0),
    checkedAt: Math.max(0, Number(input.evidence.checkedAt) || 0),
  } : undefined;
  return { address, ipVersion: isIP(address), sni, port, ...(country ? { country } : {}), ...(evidence ? { evidence } : {}) };
}
async function readState() {
  try {
    const parsed = JSON.parse(await readFile(stateFile, 'utf8'));
    return { candidates: Array.isArray(parsed.candidates) ? parsed.candidates.map(normalizeCandidate).filter(Boolean).slice(0, 500) : [] };
  } catch { return { candidates: [] }; }
}
async function saveState(state) {
  await mkdir(dataDir, { recursive: true });
  const temp = `${stateFile}.${process.pid}.tmp`;
  await writeFile(temp, JSON.stringify(state, null, 2), { mode: 0o600 });
  await rename(temp, stateFile);
}
async function authorized(req) {
  const header = req.headers.authorization || '';
  const supplied = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (adminToken && supplied.length === adminToken.length && Buffer.from(supplied).equals(Buffer.from(adminToken))) return { role: 'admin', username: 'service-token' };
  return auth.authenticate(supplied);
}
async function body(req, max = 64 * 1024) {
  let text = '';
  for await (const chunk of req) {
    text += chunk;
    if (text.length > max) throw new Error('body_too_large');
  }
  return JSON.parse(text || '{}');
}
export async function handler(req, res) {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/login')) {
      const html = await readFile(join(dirname(fileURLToPath(import.meta.url)), 'public', 'index.html'), 'utf8');
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-security-policy': "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'", 'x-frame-options': 'DENY', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer' });
      return res.end(html);
    }
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true, service: 'cat-railway-panel', version: VERSION });
    if (req.method === 'GET' && url.pathname === '/api/version') return json(res, 200, { ok: true, version: VERSION, target: 'railway' });
    if (req.method === 'GET' && url.pathname === '/runtime') return json(res, 200, { ok: true, node: process.version, platform: process.platform, arch: process.arch, uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000), memory: process.memoryUsage().rss });
    if (req.method === 'GET' && url.pathname.startsWith('/sub/')) {
      const token = decodeURIComponent(url.pathname.slice(5));
      const subscription = await auth.subscription(token);
      if (!subscription) return json(res, 404, { ok: false, error: 'subscription_not_found' });
      const raw = subscription.links.join('\n');
      const output = url.searchParams.get('format') === 'raw' ? raw : Buffer.from(raw).toString('base64');
      res.writeHead(200, {
        'content-type': 'text/plain; charset=utf-8',
        'cache-control': 'no-store',
        'subscription-userinfo': `upload=0; download=0; total=${subscription.quotaBytes || 0}; expire=${subscription.expiresAt ? Math.floor(subscription.expiresAt / 1000) : 0}`,
        'x-content-type-options': 'nosniff',
      });
      return res.end(output);
    }
    if (req.method === 'POST' && url.pathname === '/api/telegram/webhook') {
      const result = await telegram.handle(req, await body(req), {
        status: async () => { const s = xray.status(); return `Cat Railway ${VERSION}\nXray: ${s.running ? 'running' : 'stopped'}\nUptime: ${s.uptimeSeconds}s`; },
        users: async () => { const users = await auth.listUsers(); return `Users: ${users.filter(x => x.role === 'user').length}\nActive: ${users.filter(x => x.role === 'user' && x.enabled).length}`; },
        restartXray: async () => { const result = await xray.restart(); return result.ok ? 'Xray restarted' : `Xray restart failed: ${result.error || 'unknown'}`; },
      });
      return json(res, result.status, { ok: result.status === 200 });
    }
    if (req.method === 'POST' && url.pathname === '/api/login') {
      const input = await body(req);
      const result = await auth.login(input.username, input.password);
      return json(res, result ? 200 : 401, result ? { ok: true, ...result } : { ok: false, error: 'invalid_credentials' });
    }
    const actor = url.pathname.startsWith('/api/') ? await authorized(req) : null;
    if (url.pathname.startsWith('/api/') && !actor) return json(res, 401, { ok: false, error: 'unauthorized' });
    if (req.method === 'GET' && url.pathname === '/api/me') return json(res, 200, { ok: true, user: actor });
    if (req.method === 'GET' && url.pathname === '/api/users') return actor.role === 'admin' ? json(res, 200, { ok: true, users: await auth.listUsers() }) : json(res, 403, { ok: false, error: 'forbidden' });
    if (req.method === 'POST' && url.pathname === '/api/users') {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      try { return json(res, 201, { ok: true, user: await auth.createUser(await body(req)) }); }
      catch (error) { return json(res, 400, { ok: false, error: error.message === 'user_exists' ? 'user_exists' : 'invalid_user' }); }
    }
    const linksRoute = /^\/api\/users\/([a-f0-9]{32})\/links$/.exec(url.pathname);
    if (req.method === 'PUT' && linksRoute) {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      try { return json(res, 200, { ok: true, ...(await auth.setLinks(linksRoute[1], (await body(req)).links)) }); }
      catch { return json(res, 404, { ok: false, error: 'user_not_found' }); }
    }
    if (req.method === 'POST' && url.pathname === '/api/telegram/setup') {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      try { await telegram.setupWebhook(process.env.RAILWAY_PUBLIC_DOMAIN); return json(res, 200, { ok: true }); }
      catch (error) { return json(res, 503, { ok: false, error: error.message }); }
    }
    if (req.method === 'GET' && url.pathname === '/api/backup') {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      try { return json(res, 200, { ok: true, backup: await backups.export() }); }
      catch { return json(res, 503, { ok: false, error: 'backup_key_not_configured' }); }
    }
    if (req.method === 'POST' && url.pathname === '/api/restore') {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      try { return json(res, 200, await backups.restore((await body(req, 3 * 1024 * 1024)).backup)); }
      catch (error) { return json(res, 400, { ok: false, error: error.message === 'backup_key_not_configured' ? error.message : 'invalid_or_tampered_backup' }); }
    }
    if (req.method === 'GET' && url.pathname === '/api/xray/status') return json(res, 200, { ok: true, ...xray.status(), binary: await xray.verifyBinary() });
    if (req.method === 'POST' && url.pathname === '/api/xray/config') {
      const config = await body(req, 1024 * 1024);
      const result = await xray.installConfig(config);
      return json(res, result.ok ? 200 : 400, result);
    }
    if (req.method === 'POST' && url.pathname === '/api/xray/apply') {
      const result = await xray.applyConfig(await body(req, 1024 * 1024));
      return json(res, result.ok ? 200 : 409, result);
    }
    if (req.method === 'POST' && url.pathname === '/api/xray/start') { const result = await xray.start(); return json(res, result.ok ? 200 : 409, result); }
    if (req.method === 'POST' && url.pathname === '/api/xray/stop') return json(res, 200, await xray.stop());
    if (req.method === 'POST' && url.pathname === '/api/xray/restart') { const result = await xray.restart(); return json(res, result.ok ? 200 : 409, result); }
    if (req.method === 'GET' && url.pathname === '/api/candidates') return json(res, 200, { ok: true, ...(await readState()) });
    if (req.method === 'POST' && url.pathname === '/api/auto-plan') {
      const state = await readState();
      const plan = buildAutoPlan(state.candidates, await body(req));
      return json(res, plan.ok ? 200 : 422, plan);
    }
    if (req.method === 'POST' && url.pathname === '/api/auto-inbound') {
      if (actor.role !== 'admin') return json(res, 403, { ok: false, error: 'forbidden' });
      const input = await body(req);
      const state = await readState();
      const plan = buildAutoPlan(state.candidates, input);
      if (!plan.ok) return json(res, 422, plan);
      const user = await auth.getUser(String(input.userId || ''));
      const users = (await auth.listUsers()).filter(x => x.role === 'user' && x.enabled);
      const built = buildVlessWsInbound({ user, users, plan, publicHost: process.env.RAILWAY_PUBLIC_DOMAIN, localPort: Number(process.env.CAT_XRAY_WS_PORT || 18080), pathToken: process.env.CAT_TUNNEL_PATH_TOKEN });
      if (!built.ok) return json(res, 422, built);
      const applied = await xray.applyConfig(built.config);
      if (!applied.ok) return json(res, 409, applied);
      await auth.setLinks(user.id, [built.link]);
      return json(res, 201, { ok: true, link: built.link, primary: built.primary, fallbacks: built.fallbacks });
    }
    if (req.method === 'POST' && url.pathname === '/api/candidates/probe') {
      const wanted = normalizeCandidate(await body(req));
      if (!wanted) return json(res, 400, { ok: false, error: 'invalid_candidate' });
      const state = await readState();
      const index = state.candidates.findIndex(x => x.address === wanted.address && x.port === wanted.port && x.sni === wanted.sni);
      if (index < 0) return json(res, 404, { ok: false, error: 'candidate_not_registered' });
      const evidence = await probeCandidate(state.candidates[index]);
      state.candidates[index] = { ...state.candidates[index], evidence };
      await saveState(state);
      return json(res, evidence.ok ? 200 : 424, { ok: evidence.ok, candidate: state.candidates[index] });
    }
    if (req.method === 'POST' && url.pathname === '/api/candidates') {
      const candidate = normalizeCandidate(await body(req));
      if (!candidate) return json(res, 400, { ok: false, error: 'invalid_candidate' });
      const state = await readState();
      state.candidates = [candidate, ...state.candidates.filter(x => `${x.address}:${x.port}:${x.sni}` !== `${candidate.address}:${candidate.port}:${candidate.sni}`)].slice(0, 500);
      await saveState(state);
      return json(res, 201, { ok: true, candidate });
    }
    return json(res, 404, { ok: false, error: 'not_found' });
  } catch (error) {
    const code = error?.message === 'body_too_large' ? 413 : 400;
    return json(res, code, { ok: false, error: code === 413 ? 'body_too_large' : 'bad_request' });
  }
}

function proxyTunnelUpgrade(req, client, head) {
  const token = process.env.CAT_TUNNEL_PATH_TOKEN || '';
  const expected = /^[A-Za-z0-9_-]{20,64}$/.test(token) ? `/cat-tunnel/${token}` : '';
  const path = runSafeUrl(req.url);
  if (!expected || path !== expected) return client.destroy();
  const upstream = connect({ host: '127.0.0.1', port: Number(process.env.CAT_XRAY_WS_PORT || 18080) });
  upstream.once('connect', () => {
    const headers = Object.entries(req.headers).map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : value}`).join('\r\n');
    upstream.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n${headers}\r\n\r\n`);
    if (head?.length) upstream.write(head);
    client.pipe(upstream).pipe(client);
  });
  upstream.on('error', () => client.destroy());
  client.on('error', () => upstream.destroy());
}
function runSafeUrl(value) { try { return new URL(value, 'http://localhost').pathname; } catch { return ''; } }

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8080);
  try { await auth.bootstrap(process.env.CAT_INITIAL_ADMIN_PASSWORD || ''); }
  catch (error) { console.error(`Startup refused: ${error.message}`); process.exit(1); }
  const server = http.createServer(handler);
  server.on('upgrade', proxyTunnelUpgrade);
  server.listen(port, '0.0.0.0', () => console.log(`Cat Railway Panel ${VERSION} listening on ${port}`));
}
