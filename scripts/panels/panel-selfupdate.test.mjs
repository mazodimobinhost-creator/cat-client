/**
 * The panel's own «check for updates» (/api/update-check) and «download worker.js»
 * (/api/update-download) — owner-only routes.
 *
 * Why this exists (same family as «the panel went back to the old version»):
 *   - the worker's source chain was: release asset → jsDelivr @latest → raw `main`, the last
 *     two serving the READABLE worker. `main` carried panel 5.23.13 while 6.5x was current,
 *     and Cloudflare disables deployments of the readable source (Error 1101);
 *   - the UI flags ANY difference between `latest` and `current` as «⬆️ update available»,
 *     so an OLDER mirror was advertised with a download button;
 *   - and since the shipped artifact is obfuscated, `latest` was always empty (the version
 *     marker was hidden), so the button only ever showed «?».
 *
 * Pins: a newer marked source is offered; an older or unmarked one never is; the readable
 * repository paths are never requested; the owner gate stays.
 *
 * Usage: node scripts/panels/panel-selfupdate.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

// ── network stub: url-substring → { status, body } ───────────────────────────
let behavior = {};
const requested = [];
globalThis.fetch = async (u) => {
  const url = String(u);
  requested.push(url);
  for (const [needle, v] of Object.entries(behavior)) {
    if (url.includes(needle)) return new Response(v.body ?? '', { status: v.status ?? 200 });
  }
  return new Response('', { status: 404 });
};
const RELEASE = '/releases/latest/download/catclient.worker.js';
const SNAPSHOT = 'dist-panel/catpanel.obf.js';
const marked = (v) => "/* CAT_PANEL_VERSION = '" + v + "' */\nconst _0x1=['a2V5'];\nexport default globalThis.__CAT_DEFAULT;\n";
const UNMARKED = 'const _0x1=["a2V5"];export default globalThis.__CAT_DEFAULT;'; // what every artifact looked like before 6.53.1

const worker = (await import(workerPath)).default;
let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 240) : '')); }
};

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
const UUID = '11111111-2222-4333-8444-555555555555';
const HOST = 'https://edge-pedre.catclient-0ltgml5i.workers.dev';
const open = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
const get = (p, env = open) => worker.fetch(new Request(HOST + p), env, { waitUntil() {} });
const readJson = async (r) => { try { return await r.json(); } catch { return null; } };

const cur = (await readJson(await get('/api/version'))).version;
check('panel reports its own version', /^\d+(\.\d+)+$/.test(cur), cur);

async function scenario(name, map, expect) {
  behavior = map;
  requested.length = 0;
  const rc = await get('/api/update-check');
  const jc = await readJson(rc);
  const rd = await get('/api/update-download');
  const bodyD = rd.status === 200 ? await rd.text() : null;
  const jd = rd.status === 200 ? null : await readJson(rd);
  expect(name, { rc, jc, rd, bodyD, jd });
}

// 1) a newer, marked release asset is offered and served
await scenario('newer release asset', { [RELEASE]: { body: marked('6.99.0') } }, (n, x) => {
  check(n + ': update-check → latest is the newer version', x.rc.status === 200 && x.jc.ok === true && x.jc.latest === '6.99.0' && x.jc.current === cur, JSON.stringify(x.jc));
  check(n + ': update-download serves it', x.rd.status === 200 && x.bodyD === marked('6.99.0') && /attachment; filename="catclient\.worker\.js"/.test(x.rd.headers.get('content-disposition') || ''), x.rd.status);
});

// 2) THE downgrade lure: release unreachable, the only source is an OLDER snapshot (5.23.13)
await scenario('release down, older snapshot', { [SNAPSHOT]: { body: marked('5.23.13') } }, (n, x) => {
  check(n + ': never reported as an update (latest clamps to current)', x.rc.status === 200 && x.jc.latest === cur, JSON.stringify(x.jc));
  check(n + ': update-download refuses to serve the older source (409)', x.rd.status === 409 && x.jd && x.jd.ok === false, x.rd.status);
});

// 3) an OLDER release asset (a panel newer than the last release) is not an update either
await scenario('older release asset', { [RELEASE]: { body: marked('5.0.0') } }, (n, x) => {
  check(n + ': latest clamps to current → UI shows «up to date»', x.jc.latest === cur, JSON.stringify(x.jc));
  check(n + ': download refused', x.rd.status === 409, x.rd.status);
});

// 4) same version: up to date, re-download allowed (repair / re-deploy)
await scenario('same version', { [RELEASE]: { body: marked(cur) } }, (n, x) => {
  check(n + ': latest === current', x.jc.latest === cur && x.jc.current === cur, JSON.stringify(x.jc));
  check(n + ': re-download allowed', x.rd.status === 200 && x.bodyD === marked(cur), x.rd.status);
});

// 5) unmarked sources (how every obfuscated artifact looked before 6.53.1) are not trusted
await scenario('unmarked sources', { [RELEASE]: { body: UNMARKED }, [SNAPSHOT]: { body: UNMARKED } }, (n, x) => {
  check(n + ': update-check → 502 (cannot tell)', x.rc.status === 502 && x.jc.ok === false, x.rc.status);
  check(n + ': update-download → 502', x.rd.status === 502, x.rd.status);
});

// 6) fallback works: release unmarked, snapshot newer + marked
await scenario('release unmarked, newer snapshot', { [RELEASE]: { body: UNMARKED }, [SNAPSHOT]: { body: marked('6.99.0') } }, (n, x) => {
  check(n + ': snapshot used as the fallback', x.jc && x.jc.latest === '6.99.0', JSON.stringify(x.jc));
});

// 6b) blocked 1101 version (6.69.12) is skipped and falls back to valid snapshot
await scenario('release is blocked 6.69.12, valid snapshot', { [RELEASE]: { body: marked('6.69.12') }, [SNAPSHOT]: { body: marked('6.99.0') } }, (n, x) => {
  check(n + ': blocked 6.69.12 skipped, snapshot used', x.jc && x.jc.latest === '6.99.0', JSON.stringify(x.jc));
});

// 7) source discipline, across everything requested above
behavior = { [RELEASE]: { status: 404 }, [SNAPSHOT]: { status: 404 } };
requested.length = 0;
await get('/api/update-check');
check('first source is the release asset', requested[0] && requested[0].includes(RELEASE), requested[0]);
check('the committed OBFUSCATED snapshot is the fallback', requested.some((u) => u.includes(SNAPSHOT)), requested.join(' , '));
check('the READABLE worker path is never requested (anti-1101)', requested.every((u) => !u.includes('/app/src/main/assets/panels/')), requested.join(' , '));
check('no raw `main` / jsDelivr @latest worker fetch', requested.every((u) => !/raw\.githubusercontent\.com|@latest/.test(u)), requested.join(' , '));

// 8) the owner gate is intact
requested.length = 0;
const closed = { CAT_KV: new FakeKV(), UUID };
const a = await get('/api/update-check', closed);
const b = await get('/api/update-download', closed);
check('anonymous update-check → 401, no network', a.status === 401 && requested.length === 0, a.status + ' / ' + requested.length);
check('anonymous update-download → 401, no network', b.status === 401 && requested.length === 0, b.status + ' / ' + requested.length);

// 9) Pre-release discovery when /releases/latest points to an older stable release
{
  const PRERELEASE_ASSET = 'https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta999/catclient.worker.js';
  behavior = {
    [RELEASE]: { body: marked('5.0.0') },
    [SNAPSHOT]: { status: 404 },
    '/releases?per_page=8': {
      body: JSON.stringify([
        { draft: true, prerelease: true, assets: [{ name: 'catclient.worker.js', browser_download_url: 'https://github.com/draft/catclient.worker.js' }] },
        { draft: false, prerelease: true, assets: [{ name: 'catclient.worker.js', browser_download_url: PRERELEASE_ASSET }] },
      ]),
    },
    [PRERELEASE_ASSET]: { body: marked('6.99.5') },
  };
  const rc = await get('/api/update-check');
  const jc = await readJson(rc);
  check('prerelease asset discovered when /releases/latest is older', rc.status === 200 && jc && jc.latest === '6.99.5', JSON.stringify(jc));
}

// 10) Direct in-panel self-update (/api/update-apply) using bound CF_API_TOKEN + CF_ACCOUNT_ID + CF_WORKER_NAME without re-asking for token
{
  let uploadedMeta = null;
  let uploadedScript = '';
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (u, init = {}) => {
    const url = String(u);
    if (url.includes(RELEASE)) return new Response(marked('6.99.9'), { status: 200 });
    if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre/settings')) {
      return new Response(JSON.stringify({
        success: true,
        result: {
          bindings: [
            { type: 'plain_text', name: 'UUID', text: UUID },
            { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-123' },
            { type: 'd1', name: 'DB', id: 'd1-123' },
            { type: 'secret_text', name: 'PANEL_PASSWORD' },
            { type: 'secret_text', name: 'CF_API_TOKEN' },
          ],
        },
      }), { status: 200 });
    }
    if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre') && init.method === 'PUT') {
      const form = init.body;
      uploadedMeta = JSON.parse(await form.get('metadata').text());
      uploadedScript = await form.get('worker.js').text();
      return new Response(JSON.stringify({ success: true, result: { id: 'edge-pedre' } }), { status: 200 });
    }
    return new Response('', { status: 404 });
  };
  try {
    const boundEnv = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true', CF_API_TOKEN: 'cf-bound-tok', CF_ACCOUNT_ID: 'acc-direct', CF_WORKER_NAME: 'edge-pedre' };
    const chk = await readJson(await get('/api/update-check', boundEnv));
    check('update-check reports directReady=true when CF_API_TOKEN is bound', chk && chk.directReady === true, JSON.stringify(chk));
    const applyRes = await worker.fetch(new Request(HOST + '/api/update-apply', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }), boundEnv, { waitUntil() {} });
    const applyJson = await readJson(applyRes);
    check('update-apply updates worker in-place without asking for token', applyRes.status === 200 && applyJson && applyJson.ok === true && applyJson.to === '6.99.9' && applyJson.worker === 'edge-pedre', JSON.stringify(applyJson));
    const byName = Object.fromEntries((uploadedMeta ? uploadedMeta.bindings : []).map((b) => [b.name, b]));
    check('update-apply preserves UUID, CAT_KV, D1, CF_ACCOUNT_ID, CF_WORKER_NAME and keep_secrets', uploadedMeta && uploadedMeta.keep_secrets === true && byName.UUID && byName.UUID.text === UUID && byName.CAT_KV && byName.CAT_KV.namespace_id === 'kv-123' && byName.DB && byName.DB.id === 'd1-123' && byName.CF_ACCOUNT_ID && byName.CF_ACCOUNT_ID.text === 'acc-direct' && byName.CF_WORKER_NAME && byName.CF_WORKER_NAME.text === 'edge-pedre' && uploadedScript.includes("6.99.9"), JSON.stringify(uploadedMeta));
  } finally {
    globalThis.fetch = prevFetch;
  }
}

// 11) beta240 tunnels: DATA_PLANE_ENABLED is added when missing, an owner value is kept, and the confirmed
//     «turn tunnels on» (POST /api/data-plane) forces it to true. Without it every VLESS/Trojan got 403.
{
  const prevFetch = globalThis.fetch;
  const run = async (existingDp, path, body) => {
    let meta = null;
    globalThis.fetch = async (u, init = {}) => {
      const url = String(u);
      if (url.includes(RELEASE)) return new Response(marked('6.99.9'), { status: 200 });
      if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre/settings')) {
        const bindings = [{ type: 'plain_text', name: 'UUID', text: UUID }, { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-123' }, { type: 'secret_text', name: 'CF_API_TOKEN' }];
        if (existingDp !== null) bindings.push({ type: 'plain_text', name: 'DATA_PLANE_ENABLED', text: existingDp });
        return new Response(JSON.stringify({ success: true, result: { bindings } }), { status: 200 });
      }
      if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre') && init.method === 'PUT') {
        meta = JSON.parse(await init.body.get('metadata').text());
        return new Response(JSON.stringify({ success: true, result: { id: 'edge-pedre' } }), { status: 200 });
      }
      return new Response('', { status: 404 });
    };
    const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true', CF_API_TOKEN: 'cf-bound-tok', CF_ACCOUNT_ID: 'acc-direct', CF_WORKER_NAME: 'edge-pedre' };
    const res = await worker.fetch(new Request(HOST + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }), env, { waitUntil() {} });
    const dps = (meta ? meta.bindings : []).filter((b) => b.name === 'DATA_PLANE_ENABLED');
    return { status: res.status, dps };
  };
  try {
    let r = await run(null, '/api/update-apply', {});
    check('self-update adds DATA_PLANE_ENABLED=true when the Worker never had it', r.status === 200 && r.dps.length === 1 && r.dps[0].text === 'true', JSON.stringify(r));
    r = await run('false', '/api/update-apply', {});
    check('self-update keeps an owner-set DATA_PLANE_ENABLED=false (exactly one binding)', r.status === 200 && r.dps.length === 1 && r.dps[0].text === 'false', JSON.stringify(r));
    r = await run('false', '/api/data-plane', { confirm: true });
    check('confirmed «turn tunnels on» forces DATA_PLANE_ENABLED=true (exactly one binding)', r.status === 200 && r.dps.length === 1 && r.dps[0].text === 'true', JSON.stringify(r));
  } finally {
    globalThis.fetch = prevFetch;
  }
}

// 12) 6.83.0 🌍 panel region (Cloudflare Workers placement): the owner picks Smart / a cloud region / default; the
//     panel re-uploads itself with `placement` in the metadata (bindings + secrets kept), every later self-update
//     keeps it, an invalid region uploads nothing, and GET shows where the Worker really runs (Cloudflare trace).
{
  const prevFetch = globalThis.fetch;
  const kv = new FakeKV();
  let metas = [];
  let cfPlacement = undefined;
  globalThis.fetch = async (u, init = {}) => {
    const url = String(u);
    if (url.includes(RELEASE)) return new Response(marked('6.99.9'), { status: 200 });
    if (url.includes('/cdn-cgi/trace')) return new Response('fl=1\nh=speed.cloudflare.com\ncolo=FRA\nloc=DE\n', { status: 200 });
    if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre/settings')) {
      const bindings = [{ type: 'plain_text', name: 'UUID', text: UUID }, { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-123' }, { type: 'secret_text', name: 'CF_API_TOKEN' }];
      const result = { bindings };
      if (cfPlacement) result.placement = cfPlacement;
      return new Response(JSON.stringify({ success: true, result }), { status: 200 });
    }
    if (url.endsWith('/accounts/acc-direct/workers/scripts/edge-pedre') && init.method === 'PUT') {
      metas.push(JSON.parse(await init.body.get('metadata').text()));
      return new Response(JSON.stringify({ success: true, result: { id: 'edge-pedre' } }), { status: 200 });
    }
    return new Response('', { status: 404 });
  };
  const mk = (store) => ({ CAT_KV: store, UUID, OPEN_PANEL: 'true', CF_API_TOKEN: 'cf-bound-tok', CF_ACCOUNT_ID: 'acc-direct', CF_WORKER_NAME: 'edge-pedre' });
  let env = mk(kv);
  const go = async (method, p, body, headers = {}) => {
    const init = { method, headers: Object.assign({ 'content-type': 'application/json' }, headers) };
    if (body !== undefined) init.body = JSON.stringify(body);
    const res = await worker.fetch(new Request(HOST + p, init), env, { waitUntil() {} });
    let j = null; try { j = await res.json(); } catch {}
    return { status: res.status, j };
  };
  const last = () => metas[metas.length - 1];
  try {
    let r = await go('GET', '/api/placement', undefined, { 'cf-placement': 'local-EWR' });
    check('region GET: default «off», region lists, live colo from Cloudflare trace, cf-placement header parsed',
      r.status === 200 && r.j.ok && r.j.value === 'off' && r.j.platform === 'cf' && r.j.canApply === true
        && r.j.regions.gcp.some((e) => e.startsWith('europe-west3|DE|')) && r.j.regions.aws.length > 10 && r.j.regions.azure.length > 10
        && r.j.live && r.j.live.colo === 'FRA' && r.j.header && r.j.header.kind === 'local' && r.j.header.colo === 'EWR', JSON.stringify(r.j).slice(0, 200));
    r = await go('POST', '/api/placement', { placement: 'gcp:europe-west3' });
    check('region POST without confirm → 400, nothing uploaded', r.status === 400 && metas.length === 0);
    r = await go('POST', '/api/placement', { confirm: true, placement: 'gcp:mars-1' });
    check('unknown region → 400, nothing uploaded', r.status === 400 && metas.length === 0, JSON.stringify(r.j));
    r = await go('POST', '/api/placement', { confirm: true, placement: 'GCP:europe-west3' });
    check('region POST re-uploads with placement.region (bindings + secrets kept)',
      r.status === 200 && r.j.placement === 'gcp:europe-west3' && JSON.stringify(last().placement) === JSON.stringify({ region: 'gcp:europe-west3' })
        && last().keep_secrets === true && last().bindings.some((b) => b.name === 'CAT_KV') && last().bindings.some((b) => b.name === 'UUID' && b.text === UUID), JSON.stringify(last()).slice(0, 300));
    r = await go('GET', '/api/placement');
    check('region GET now reports the saved region', r.j.value === 'gcp:europe-west3');
    r = await go('POST', '/api/update-apply', {});
    check('a later self-update keeps the region', r.status === 200 && JSON.stringify(last().placement) === JSON.stringify({ region: 'gcp:europe-west3' }));
    r = await go('POST', '/api/placement', { confirm: true, placement: 'smart' });
    check('Smart → placement.mode = smart', r.status === 200 && JSON.stringify(last().placement) === JSON.stringify({ mode: 'smart' }));
    r = await go('POST', '/api/placement', { confirm: true, placement: 'azure:westeurope' });
    check('Azure region accepted', r.status === 200 && last().placement.region === 'azure:westeurope');
    r = await go('POST', '/api/placement', { confirm: true, placement: 'off' });
    check('Default → no placement key at all', r.status === 200 && !('placement' in last()));
    r = await go('POST', '/api/update-apply', {});
    check('…and the next self-update does not add one back', r.status === 200 && !('placement' in last()));
    // A panel whose placement was set in the Cloudflare dashboard (nothing in KV): the self-update keeps it.
    env = mk(new FakeKV()); cfPlacement = { region: 'aws:eu-central-1' };
    r = await go('POST', '/api/update-apply', {});
    check('a region set in the Cloudflare dashboard survives the self-update', r.status === 200 && last().placement && last().placement.region === 'aws:eu-central-1', JSON.stringify(last().placement));
    env = mk(new FakeKV()); cfPlacement = { mode: 'smart' };
    r = await go('POST', '/api/update-apply', {});
    check('dashboard Smart survives the self-update', r.status === 200 && last().placement && last().placement.mode === 'smart');
    const anon = await worker.fetch(new Request(HOST + '/api/placement'), { CAT_KV: new FakeKV(), UUID }, { waitUntil() {} });
    check('region API is owner-only', anon.status === 401);
  } finally {
    globalThis.fetch = prevFetch;
  }
}

console.log(failures ? '\nPANEL SELF-UPDATE TESTS FAILED (' + failures + ')' : '\nPANEL SELF-UPDATE TESTS PASSED');
process.exit(failures ? 1 : 0);
