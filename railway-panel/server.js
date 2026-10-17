import http from 'node:http';
import { isIP } from 'node:net';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const VERSION = '0.1.0';
const startedAt = Date.now();
const dataDir = process.env.CAT_DATA_DIR || join(dirname(fileURLToPath(import.meta.url)), 'data');
const stateFile = join(dataDir, 'state.json');
const adminToken = process.env.CAT_ADMIN_TOKEN || '';

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
function authorized(req) {
  if (!adminToken) return false;
  const header = req.headers.authorization || '';
  const supplied = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (supplied.length !== adminToken.length) return false;
  return Buffer.from(supplied).equals(Buffer.from(adminToken));
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
    if (url.pathname.startsWith('/api/') && !authorized(req)) return json(res, adminToken ? 401 : 503, { ok: false, error: adminToken ? 'unauthorized' : 'admin_token_not_configured' });
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
  http.createServer(handler).listen(port, '0.0.0.0', () => console.log(`Cat Railway Panel ${VERSION} listening on ${port}`));
}
