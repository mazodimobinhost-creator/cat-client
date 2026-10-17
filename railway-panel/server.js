import http from 'node:http';
import { isIP } from 'node:net';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { XrayRuntime } from './xray-runtime.js';
import { AuthStore } from './auth-store.js';

export const VERSION = '0.3.0';
const startedAt = Date.now();
const dataDir = process.env.CAT_DATA_DIR || join(dirname(fileURLToPath(import.meta.url)), 'data');
const stateFile = join(dataDir, 'state.json');
const adminToken = process.env.CAT_ADMIN_TOKEN || '';
const auth = new AuthStore(dataDir);
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
  if (!isIP(address) || !hostname(sni) || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  if (address === '127.0.0.1' || address === '::1' || address === '0.0.0.0' || address === '::') return null;
  return { address, ipVersion: isIP(address), sni, port };
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
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true, service: 'cat-railway-panel', version: VERSION });
    if (req.method === 'GET' && url.pathname === '/api/version') return json(res, 200, { ok: true, version: VERSION, target: 'railway' });
    if (req.method === 'GET' && url.pathname === '/runtime') return json(res, 200, { ok: true, node: process.version, platform: process.platform, arch: process.arch, uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000), memory: process.memoryUsage().rss });
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
    if (req.method === 'GET' && url.pathname === '/api/xray/status') return json(res, 200, { ok: true, ...xray.status(), binary: await xray.verifyBinary() });
    if (req.method === 'POST' && url.pathname === '/api/xray/config') {
      const config = await body(req, 1024 * 1024);
      const result = await xray.installConfig(config);
      return json(res, result.ok ? 200 : 400, result);
    }
    if (req.method === 'POST' && url.pathname === '/api/xray/start') { const result = await xray.start(); return json(res, result.ok ? 200 : 409, result); }
    if (req.method === 'POST' && url.pathname === '/api/xray/stop') return json(res, 200, await xray.stop());
    if (req.method === 'POST' && url.pathname === '/api/xray/restart') { const result = await xray.restart(); return json(res, result.ok ? 200 : 409, result); }
    if (req.method === 'GET' && url.pathname === '/api/candidates') return json(res, 200, { ok: true, ...(await readState()) });
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

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8080);
  try { await auth.bootstrap(process.env.CAT_INITIAL_ADMIN_PASSWORD || ''); }
  catch (error) { console.error(`Startup refused: ${error.message}`); process.exit(1); }
  http.createServer(handler).listen(port, '0.0.0.0', () => console.log(`Cat Railway Panel ${VERSION} listening on ${port}`));
}
