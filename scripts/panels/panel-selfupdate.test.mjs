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

console.log(failures ? '\nPANEL SELF-UPDATE TESTS FAILED (' + failures + ')' : '\nPANEL SELF-UPDATE TESTS PASSED');
process.exit(failures ? 1 : 0);
