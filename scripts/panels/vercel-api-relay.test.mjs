/**
 * 🔺 Vercel API relay (panel 6.82.0): lets the app build a Cat panel on Vercel when api.vercel.com is blocked on the
 * phone's line but the owner's Cloudflare panel is reachable. Pinned against the real worker with a mocked fetch:
 *   - only the OWNER can use it (master UUID as the first path part; anything else → 404, nothing fetched);
 *   - it reaches exactly https://api.vercel.com + the versioned path + the query (teamId passes through);
 *   - the user's own Bearer token, content-type and x-vercel-digest pass through; cookies / CF headers do not;
 *   - raw upload bodies arrive byte-exact; PUT / unversioned paths / missing token are refused;
 *   - responses carry x-cat-vrelay: 1 so the app can tell a relay answer from a page that merely says 404.
 * Usage: node scripts/panels/vercel-api-relay.test.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

let calls = [];
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  const headers = new Headers(init.headers || {});
  const body = init.body ? Buffer.from(init.body) : null;
  calls.push({ url, method: (init.method || 'GET').toUpperCase(), headers, body });
  if (url.startsWith('https://api.vercel.com/')) {
    const p = new URL(url);
    if (p.pathname === '/v2/user') return Response.json({ user: { username: 'owner', defaultTeamId: 'team_x' } });
    if (p.pathname === '/v2/files') return Response.json({ urls: [] });
    if (p.pathname === '/v11/projects') return Response.json({ error: { code: 'forbidden', message: 'Not authorized' } }, { status: 403 });
    return Response.json({ ok: true });
  }
  return new Response('', { status: 404 });
};
const mod = await import(workerPath);
const worker = mod.default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const UUID = '41111111-2222-4333-8444-555555555555';
const HOST = 'cat-relay.example.workers.dev';
const env = { CAT_KV: new FakeKV(), UUID };
const ctx = { waitUntil() {} };
const TOKEN = 'Bearer abcDEF1234567890xyz';
const call = (p, init = {}) => worker.fetch(new Request('https://' + HOST + p, init), env, ctx);
const vercelCalls = () => calls.filter((c) => c.url.startsWith('https://api.vercel.com/'));

{
  calls = [];
  const r = await call('/vrelay/' + UUID + '/v2/user', { headers: { authorization: TOKEN, cookie: 'cat_session=secret', 'cf-connecting-ip': '5.6.7.8' } });
  const j = await r.json();
  const c = vercelCalls()[0];
  check('owner GET is relayed and answered', r.status === 200 && j.user && j.user.username === 'owner', String(r.status));
  check('relay marks its answers (x-cat-vrelay: 1)', r.headers.get('x-cat-vrelay') === '1');
  check('exactly one upstream call to api.vercel.com/v2/user', vercelCalls().length === 1 && c.url === 'https://api.vercel.com/v2/user', c && c.url);
  check('the Bearer token passes through untouched', c.headers.get('authorization') === TOKEN);
  check('cookies and CF headers are NOT forwarded', !c.headers.get('cookie') && !c.headers.get('cf-connecting-ip'));
}
{
  calls = [];
  const r = await call('/vrelay/' + UUID.toUpperCase() + '/v9/projects?limit=1&teamId=team_x', { headers: { authorization: TOKEN } });
  check('query string (teamId) passes through; UUID case-insensitive', r.status === 200 && vercelCalls()[0].url === 'https://api.vercel.com/v9/projects?limit=1&teamId=team_x', vercelCalls()[0] && vercelCalls()[0].url);
}
{
  calls = [];
  const bytes = Buffer.from([0, 1, 2, 250, 255, 10, 13, 0x7f]);
  const r = await call('/vrelay/' + UUID + '/v2/files', { method: 'POST', body: bytes, headers: { authorization: TOKEN, 'content-type': 'application/octet-stream', 'x-vercel-digest': 'abc123' } });
  const c = vercelCalls()[0];
  check('raw upload body arrives byte-exact', r.status === 200 && c && c.body && Buffer.compare(c.body, bytes) === 0);
  check('content-type + x-vercel-digest pass through', c.headers.get('content-type') === 'application/octet-stream' && c.headers.get('x-vercel-digest') === 'abc123');
}
{
  calls = [];
  const r = await call('/vrelay/' + UUID + '/v11/projects', { method: 'POST', body: '{"name":"x"}', headers: { authorization: TOKEN, 'content-type': 'application/json' } });
  const j = await r.json();
  check('Vercel errors come back with their own status + JSON', r.status === 403 && j.error && j.error.code === 'forbidden');
}
{
  calls = [];
  const bad = [
    ['wrong UUID', '/vrelay/51111111-2222-4333-8444-555555555555/v2/user', { headers: { authorization: TOKEN } }, 404],
    ['no UUID', '/vrelay//v2/user', { headers: { authorization: TOKEN } }, 404],
    ['unversioned path', '/vrelay/' + UUID + '/user', { headers: { authorization: TOKEN } }, 400],
    ['path traversal', '/vrelay/' + UUID + '/v2/../../x', { headers: { authorization: TOKEN } }, 0],
    ['missing token', '/vrelay/' + UUID + '/v2/user', {}, 401],
    ['PUT refused', '/vrelay/' + UUID + '/v2/user', { method: 'PUT', body: 'x', headers: { authorization: TOKEN } }, 405],
  ];
  for (const [name, p, init, status] of bad) {
    const r = await call(p, init);
    check('refused: ' + name, (status === 0 ? r.status !== 200 || !vercelCalls().length : r.status === status), 'status ' + r.status);
  }
  check('nothing refused ever reached api.vercel.com', vercelCalls().length === 0, String(vercelCalls().length));
  check('no other upstream host is ever fetched by the relay', calls.every((c) => !/vrelay/.test(c.url)));
}
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all Vercel API relay checks passed');
