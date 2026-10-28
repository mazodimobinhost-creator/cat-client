/**
 * ⚡ In-panel self-update on Vercel (panel 6.75.0): «update» must NOT ask for a Cloudflare token there.
 * Pinned against the real worker with a fake KV and a mocked fetch (GitHub release + Vercel REST API):
 *   - a Vercel panel with no token answers needsSetup + platform «vercel» (the page then asks for a VERCEL token);
 *   - with CAT_VERCEL_TOKEN (set by the app on deploy/update) it redeploys THIS project, no question asked:
 *     project looked up by the domain label, 4 files uploaded by sha1, one production deployment,
 *     lib/catpanel.mjs has the single sockets import swapped for the adapter shim;
 *   - a personal-scope miss (404) retries with the account's default team;
 *   - the token/project/team are kept in KV so later updates work even without the env var;
 *   - a Node-host panel never asks for any token (it is updated from the app);
 *   - Cloudflare panels keep the old path (needsSetup without platform «vercel»).
 * Usage: node scripts/panels/vercel-self-update.test.mjs
 */
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

const IMP = 'import(' + "'cloudflare" + ':sockets' + "')";
const NEW_PANEL = "/* CAT_PANEL_VERSION='99.0.0' */\nconst CAT_PANEL_VERSION='99.0.0';\nasync function s(){const m=await " + IMP + ";return m}\nexport default {fetch(){return new Response('ok')}};\n";
const ADAPTER = "const panelModule = await import('../lib/catpanel.mjs');\nconst server = {};\nexport default server;\n";
let calls = [];
let projectNeedsTeam = false;
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  const method = (init.method || 'GET').toUpperCase();
  calls.push({ url, method, headers: init.headers || {}, body: init.body });
  if (url.endsWith('/releases/latest/download/catclient.worker.js')) return new Response(NEW_PANEL);
  if (url.endsWith('/releases/latest/download/cat-panel-adapter.mjs')) return new Response(ADAPTER);
  if (url.startsWith('https://api.vercel.com/')) {
    const p = new URL(url);
    if (p.pathname === '/v2/user') return Response.json({ user: { id: 'u1', defaultTeamId: 'team_abc' } });
    if (p.pathname.startsWith('/v9/projects/')) {
      if (projectNeedsTeam && p.searchParams.get('teamId') !== 'team_abc') return Response.json({ error: { code: 'not_found', message: 'Project not found' } }, { status: 404 });
      return Response.json({ id: 'prj_123', name: decodeURIComponent(p.pathname.split('/').pop()), resourceConfig: { functionDefaultRegions: ['fra1'] } });
    }
    if (p.pathname === '/v2/files' && method === 'POST') return Response.json({});
    if (p.pathname === '/v13/deployments' && method === 'POST') return Response.json({ id: 'dpl_1', url: 'x.vercel.app', readyState: 'QUEUED' });
    return Response.json({ error: { message: 'unexpected ' + p.pathname } }, { status: 400 });
  }
  return new Response('', { status: 404 });
};
const mod = await import(workerPath);
const T = mod._testing;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const UUID = '31111111-2222-4333-8444-555555555555';
const HOST = 'cat-panel-zz9.vercel.app';

check('exports the platform-aware updater', typeof T.selfUpdatePanel === 'function' && typeof T.selfUpdateVercelPanel === 'function' && typeof T.vercelPatchPanel === 'function');

// 1) Vercel, no token anywhere → needsSetup for a VERCEL token (never cf-token-missing)
{
  const env = { CAT_KV: new FakeKV(), UUID, CAT_PLATFORM: 'vercel' };
  calls = [];
  const r = await T.selfUpdatePanel(env, HOST, {});
  check('vercel without token → needsSetup', r.ok === false && r.needsSetup === true && r.platform === 'vercel' && r.error === 'vercel-token-missing', JSON.stringify(r));
  check('vercel without token never touches the Cloudflare API', !calls.some((c) => c.url.includes('api.cloudflare.com')));
  check('hasDirectUpdateCreds false without a Vercel token', (await T.hasDirectUpdateCreds(env)) === false);
}

// 2) Vercel with CAT_VERCEL_TOKEN → direct redeploy
const kv2 = new FakeKV();
{
  const env = { CAT_KV: kv2, UUID, CAT_PLATFORM: 'vercel', CAT_VERCEL_TOKEN: 'vt_secret', CAT_VERCEL_REGION: 'fra1' };
  check('hasDirectUpdateCreds true with CAT_VERCEL_TOKEN', (await T.hasDirectUpdateCreds(env)) === true);
  calls = [];
  const r = await T.selfUpdatePanel(env, HOST, {});
  check('vercel redeploy ok + deploying', r.ok === true && r.deploying === true && r.platform === 'vercel' && r.to === '99.0.0', JSON.stringify(r));
  const proj = calls.find((c) => c.url.includes('/v9/projects/'));
  check('project looked up by the domain label', proj && proj.url.includes('/v9/projects/cat-panel-zz9'), proj && proj.url);
  check('Bearer = the saved Vercel token', calls.filter((c) => c.url.startsWith('https://api.vercel.com/')).every((c) => c.headers.authorization === 'Bearer vt_secret'));
  const ups = calls.filter((c) => c.url.startsWith('https://api.vercel.com/v2/files'));
  check('4 files uploaded with a correct sha1 digest', ups.length === 4 && ups.every((c) => c.headers['x-vercel-digest'] === crypto.createHash('sha1').update(Buffer.from(c.body)).digest('hex')), String(ups.length));
  const bodies = ups.map((c) => Buffer.from(c.body).toString('utf8'));
  const lib = bodies.find((b) => b.includes("CAT_PANEL_VERSION='99.0.0'"));
  check('lib has the sockets import swapped for the shim', lib && !lib.includes(IMP) && lib.includes('Promise.resolve(globalThis.__catSockets)'));
  check('adapter uploaded unchanged', bodies.includes(ADAPTER));
  const vj = bodies.find((b) => b.includes('"rewrites"'));
  check('vercel.json = same layout as the app (region, 300 s, lib/**, rewrite)', vj && JSON.parse(vj).regions[0] === 'fra1' && JSON.parse(vj).functions['api/index.mjs'].maxDuration === 300 && JSON.parse(vj).functions['api/index.mjs'].includeFiles === 'lib/**' && JSON.parse(vj).rewrites[0].destination === '/api/index');
  const pkg = bodies.find((b) => b.includes('"engines"'));
  check('package.json keeps ws + @vercel/blob', pkg && JSON.parse(pkg).dependencies.ws && JSON.parse(pkg).dependencies['@vercel/blob'] && JSON.parse(pkg).type === 'module');
  const dep = calls.find((c) => c.url.startsWith('https://api.vercel.com/v13/deployments'));
  const db = dep && JSON.parse(dep.body);
  check('one production deployment of THIS project', dep && db.project === 'prj_123' && db.target === 'production' && db.files.length === 4 && db.files.every((f) => f.sha && f.size > 0) && dep.url.includes('forceNew=1'));
  check('no env vars rewritten (users/UUID/Blob stay)', !calls.some((c) => c.url.includes('/env')));
  const saved = JSON.parse(await kv2.get('cat:v6:vcdeploy'));
  check('token + project kept in KV for later updates', saved.token === 'vt_secret' && saved.projectId === 'prj_123');
}

// 3) env token gone, KV still has it → update still works without asking
{
  const env = { CAT_KV: kv2, UUID, CAT_PLATFORM: 'vercel' };
  check('KV-saved token counts as direct-update ready', (await T.hasDirectUpdateCreds(env)) === true);
  const r = await T.selfUpdatePanel(env, HOST, {});
  check('KV-saved token redeploys', r.ok === true && r.deploying === true, JSON.stringify(r));
}

// 4) personal scope 404 → default team retry
{
  projectNeedsTeam = true;
  const env = { CAT_KV: new FakeKV(), UUID, CAT_PLATFORM: 'vercel' };
  calls = [];
  const r = await T.selfUpdatePanel(env, HOST, { token: 'vt_once' });
  check('token typed once in the page works', r.ok === true, JSON.stringify(r));
  check('404 in personal scope → retried with defaultTeamId', calls.some((c) => c.url.includes('/v9/projects/') && c.url.includes('teamId=team_abc')) && calls.filter((c) => c.url.includes('/v13/deployments')).every((c) => c.url.includes('teamId=team_abc')));
  const saved = JSON.parse(await env.CAT_KV.get('cat:v6:vcdeploy'));
  check('team scope saved', saved.teamId === 'team_abc' && saved.token === 'vt_once');
  projectNeedsTeam = false;
}

// 5) patcher refuses 0 or 2 imports
check('patcher: none → null', T.vercelPatchPanel('const a=1;') === null);
check('patcher: two → null', T.vercelPatchPanel(IMP + ';' + IMP) === null);
check('patcher: double quotes too', (T.vercelPatchPanel('x(' + IMP.replace(/'/g, '"') + ')') || '').includes('__catSockets'));

// 6) Node host → never a token prompt
{
  const r = await T.selfUpdatePanel({ CAT_KV: new FakeKV(), UUID, CAT_PLATFORM: 'node' }, 'p.example.org', {});
  check('node host → «update from the app», no needsSetup', r.ok === false && !r.needsSetup && r.platform === 'node');
}

// 7) Cloudflare keeps its own path
{
  const r = await T.selfUpdatePanel({ CAT_KV: new FakeKV(), UUID }, 'p.workers.dev', {});
  check('cloudflare without token → cf-token-missing (unchanged)', r.error === 'cf-token-missing' && r.needsSetup === true && r.platform !== 'vercel');
}

// 8) page wiring
{
  const fs = await import('node:fs');
  const src = fs.readFileSync(workerPath, 'utf8');
  check('update button carries the platform', src.includes('data-plat="\'+esc(j.platform||\'\')+\'"'));
  check('page asks for a Vercel token on Vercel panels', src.includes("ph:vc?'Vercel Token…':'Cloudflare API Token…'"));
  check('page waits for the new deployment via /api/version', src.includes("fetch('/api/version?r='"));
}

if (failures) { console.error('\n' + failures + ' check(s) failed'); process.exit(1); }
console.log('\nall vercel self-update checks passed');
