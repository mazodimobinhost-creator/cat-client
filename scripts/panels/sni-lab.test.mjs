/**
 * SNI LAB — the LIVE SNI test the owner asked for, run against the real worker module.
 *
 * The feature answers one question the old pool could not: «which of these fronting SNIs can
 * THIS line actually use right now?». It splits into two verdicts on purpose, and the split is
 * what these tests pin:
 *
 *   1. `POST /api/sni-lab` (owner-only) — the worker asks Cloudflare's own /cdn-cgi/trace for
 *      every candidate and records `{sni, cf, status, ms}`. A hostname Cloudflare does not
 *      serve can never go into the rotation pool (CF answers 403 when the TLS SNI differs from
 *      the Host and is not a CF hostname), so this check decides *eligibility*.
 *   2. the page's own `📶 test from my network` — the browser probes each candidate from the
 *      owner's line, because a probe from the worker's colo says nothing about Iran's DPI.
 *      That half cannot be executed here (it needs a real carrier), so what IS executed is the
 *      contract around it: the lab never writes settings by itself, and the page hands the
 *      result to the settings form the same way a human typing the pool would.
 *
 * Everything below runs against the real module with a fake KV — no account, no network.
 *
 * Usage: node scripts/panels/sni-lab.test.mjs
 */
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';

const workerPath = process.env.CAT_PANEL_WORKER ||
  new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
const mod = await import(workerPath);
const T = mod._testing;
const worker = mod.default;

const HOST = 'sni-lab.workers.dev';
const UUID = '11111111-2222-4333-8444-555555555555';
const OWNER = 'owner-lab';

let pass = 0;
let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};

const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [], list_complete: true }),
    _map: m,
  };
};

async function panel() {
  const kv = memKv();
  const env = { CAT_KV: kv, UUID };
  const settings = Object.assign({}, await T.readSettings(env), { passwordHash: await T.sha256Hex(OWNER) });
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  T.kvCacheClear();
  return { kv, env };
}
const call = (env, path, init) => T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() { } });
const asOwner = (init = {}) => Object.assign({}, init, { headers: Object.assign({ 'x-cat-key': OWNER }, init.headers || {}) });

console.log('\nSNI Lab — live SNI testing (panel 6.67.4)\n');

/* ---------- 1) the endpoint: owner-only, and honest about what it could not check -------- */
{
  const { env } = await panel();

  const anon = await call(env, '/api/sni-lab');
  ok('an anonymous caller gets 401 (the table describes the owner\'s network)', anon.status === 401, anon.status);
  const anonPost = await call(env, '/api/sni-lab', { method: 'POST', body: JSON.stringify({ candidates: ['icook.tw'] }) });
  ok('…and cannot trigger the subrequests either', anonPost.status === 401, anonPost.status);

  const empty = await call(env, '/api/sni-lab', asOwner({ method: 'POST', body: JSON.stringify({ candidates: [] }) }));
  ok('an empty candidate list is rejected, not answered with a fake table', empty.status === 400, empty.status);

  const read = await (await call(env, '/api/sni-lab', asOwner())).json();
  ok('a fresh panel reports an empty run, not an invented one',
    read.ok === true && read.at === 0 && Array.isArray(read.results) && read.results.length === 0,
    JSON.stringify(read).slice(0, 90));
  ok('…and the defaults it ships are listed for the page to offer',
    Array.isArray(read.defaults) && read.defaults.length >= 5, JSON.stringify(read.defaults));
  ok('…and the effective pool it will save into', Array.isArray(read.pool) && read.pool.length > 0, JSON.stringify(read.pool));
}

/* ---------- 2) the worker half: it really asks the edge, and stores what it learned ------- */
{
  const { env, kv } = await panel();
  // The worker-side probe is the only place that can ask «is this Cloudflare?». Swap the
  // global fetch for the duration of one run, the way the other suites inject sockets.
  const realFetch = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url, init) => {
    seen.push(String(url));
    if (String(url).includes('/cdn-cgi/trace')) {
      if (String(url).startsWith('https://icook.tw')) {
        return new Response('fl=123abc\nh=icook.tw\ncolo=FRA\n', { status: 200, headers: { server: 'cloudflare' } });
      }
      if (String(url).startsWith('https://www.speedtest.net')) {
        // A hostname on Cloudflare WITHOUT the header (an origin that hid it) — the trace
        // body still proves it: colo=/fl= only exist behind Cloudflare's edge.
        return new Response('fl=456def\ncolo=AMS\n', { status: 200 });
      }
      // A plain origin: no CF header, no trace body.
      return new Response('<html>not found</html>', { status: 404, headers: { server: 'nginx' } });
    }
    return realFetch(url, init);
  };
  let posted;
  try {
    const res = await call(env, '/api/sni-lab', asOwner({
      method: 'POST',
      body: JSON.stringify({ candidates: ['icook.tw', 'www.speedtest.net', 'example.com', 'not a hostname', HOST, 'icook.tw'] }),
    }));
    posted = await res.json();
  } finally {
    globalThis.fetch = realFetch;
  }

  ok('the run reports one row per unique, well-formed candidate',
    posted.ok === true && posted.results.length === 3,
    JSON.stringify(posted).slice(0, 160));
  const byName = Object.fromEntries(posted.results.map((r) => [r.sni, r]));
  ok('a Cloudflare-fronted hostname is marked cf', byName['icook.tw'] && byName['icook.tw'].cf === true, JSON.stringify(byName['icook.tw']));
  ok('…the trace body alone is enough when the server header is stripped',
    byName['www.speedtest.net'] && byName['www.speedtest.net'].cf === true, JSON.stringify(byName['www.speedtest.net']));
  ok('a plain origin is NOT marked cf, and its status is reported', byName['example.com'] && byName['example.com'].cf === false && byName['example.com'].status === 404, JSON.stringify(byName['example.com']));
  ok('garbage and the worker\'s own hostname never reach the network',
    !seen.some((u) => u.includes('not a hostname')) && !seen.some((u) => u.includes(HOST)),
    seen.join(' '));
  ok('every probe was a /cdn-cgi/trace fetch, timed', seen.length === 3 && seen.every((u) => u.endsWith('/cdn-cgi/trace')) && posted.results.every((r) => typeof r.ms === 'number'));

  T.kvCacheClear();
  const storedRaw = await kv.get('cat_sni_lab_v1');
  ok('the run is persisted under its own KV key (the panel can show it after a reload)',
    !!storedRaw && JSON.parse(storedRaw).results.length === 3, String(storedRaw).slice(0, 80));

  const reread = await (await call(env, '/api/sni-lab', asOwner())).json();
  ok('…and read back unchanged', reread.at > 0 && reread.results.length === 3, JSON.stringify(reread).slice(0, 90));

  // The candidate cap: 12 subrequests per click is the budget (Workers allow 50, the page
  // offers at most 12, and a bounded run keeps the endpoint cheap for the owner).
  const bigList = Array.from({ length: 20 }, (_, i) => `h${i}.example.com`);
  const realFetch2 = globalThis.fetch;
  let probes = 0;
  globalThis.fetch = async (url, init) => {
    if (String(url).includes('/cdn-cgi/trace')) { probes++; return new Response('colo=FRA', { status: 200 }); }
    return realFetch2(url, init);
  };
  let capped;
  try {
    capped = await (await call(env, '/api/sni-lab', asOwner({
      method: 'POST', body: JSON.stringify({ candidates: bigList }),
    }))).json();
  } finally {
    globalThis.fetch = realFetch2;
  }
  ok('a long list is capped at 12 probes per run', capped.results.length === 12 && probes === 12, `rows=${capped.results.length} probes=${probes}`);
}

/* ---------- 3) the page half: what can be checked without a carrier ----------------------- */
{
  const page = await T.panelPage({}, { lang: 'fa' }, HOST, UUID);
  const source = readFileSync(workerPath, 'utf8');

  ok('the settings page carries the lab card (test / edge / save)',
    page.includes('id="labNet"') && page.includes('id="labEdge"') && page.includes('id="labSave"') && page.includes('id="labOut"'),
    'card markup missing');
  ok('…with both button labels and the hint in the panel language',
    page.includes('آزمایشگاه SNI') && page.includes('تست از شبکهٔ من') && page.includes('بررسی لبه'),
    'persian labels missing');
  const en = await T.panelPage({}, { lang: 'en' }, HOST, UUID);
  ok('…and the same keys exist in English (no untranslated row)',
    en.includes('SNI Lab') && en.includes('Test from my network') && en.includes('Edge check'),
    'english labels missing');
  // Source-shape checks can only run on the READABLE source: the obfuscated artifacts rename
  // identifiers and encode string literals, so a regex would be testing the obfuscator. CI
  // re-runs this suite twice on purpose (readable source, then the obfuscated artifact), so
  // gating here loses nothing — and the behavioral checks above run on every artifact.
  const readableSource = /function labCandidates\(/.test(source) && source.includes('lean single-file');
  if (readableSource) {
    ok('the browser probe is cross-origin and no-cors (a readable cross-origin GET is impossible)',
      /mode:'no-cors'/.test(source) && /fetch\('https:\/\/'\+sni/.test(source), 'labNet body changed');
    ok('…and it reads its candidates from the pool field plus the defaults',
      /labCandidates\(\)/.test(source) && /sniLabDefaults/.test(source), 'candidate source missing');
    ok('the saved-list button only offers rows that passed BOTH verdicts',
      /LAB\.pool\[s\]&&LAB\.pool\[s\]\.cf&&LAB\.net\[s\]/.test(source), 'save filter changed');
    ok('the lab never writes settings by itself — saving goes through the settings form',
      /requestSubmit/.test(source) && /#sniPoolCsv/.test(source), 'save path changed');
  } else {
    console.log('  · source-shape checks skipped (artifact is minified/obfuscated — identifiers renamed)');
  }

  // The invariant that has to hold on EVERY artifact, and it is not a regex: running the lab
  // must not touch settings. The pool only ever changes through the settings form.
  const realFetch3 = globalThis.fetch;
  globalThis.fetch = async (url, init) => (String(url).includes('/cdn-cgi/trace')
    ? new Response('colo=FRA\n', { status: 200 })
    : realFetch3(url, init));
  let before;
  let after;
  try {
    const { env: env4 } = await panel();
    const kv4 = env4.CAT_KV;
    await kv4.put(T.KV_KEYS.settings, JSON.stringify(Object.assign({}, JSON.parse(await kv4.get(T.KV_KEYS.settings)), { sniPool: ['kept.example'] })));
    T.kvCacheClear();
    before = JSON.parse(await kv4.get(T.KV_KEYS.settings));
    await call(env4, '/api/sni-lab', asOwner({ method: 'POST', body: JSON.stringify({ candidates: ['icook.tw'] }) }));
    T.kvCacheClear();
    after = JSON.parse(await kv4.get(T.KV_KEYS.settings));
  } finally {
    globalThis.fetch = realFetch3;
  }
  ok('a run changes the lab record but NEVER the settings (the pool is only written by the form)',
    JSON.stringify(before.sniPool) === JSON.stringify(after.sniPool) && JSON.stringify(before) === JSON.stringify(after),
    `before=${JSON.stringify(before.sniPool)} after=${JSON.stringify(after.sniPool)}`);
  // Behavioral, not a source regex: CI re-runs this suite against the obfuscated artifact
  // (in-place), where string literals are encoded — property names survive, text nodes do not.
  const { env: env3 } = await panel();
  const settingsPayload = await (await call(env3, '/api/settings', asOwner())).json();
  ok('the settings payload advertises the defaults so the page can offer them',
    Array.isArray(settingsPayload.sniLabDefaults) && settingsPayload.sniLabDefaults.length >= 5
      && Array.isArray(settingsPayload.sniLab),
    JSON.stringify(settingsPayload.sniLabDefaults || null));
}

console.log('\nsni lab: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
