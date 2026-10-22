// Carrier fragment recommendation: panel → app.
//
// What this test is and what it is not (same honest label as
// docs/tests-and-labels.md):
//   * it RUNS the worker: /api/tune is really fetched (JSON for the panel page,
//     text/plain for the phone) with a fake KV, so the two representations are
//     proven to describe the SAME table;
//   * it READS the Kotlin for the parts a JVM test cannot reach from here (the
//     HTTP client, the settings card) — that half is a source-shape guard. The
//     parsing and the resolution themselves are executed in
//     app/src/test/.../PanelTuneTest.kt;
//   * the ids are cross-checked between the two languages here, because a drifted
//     id is exactly the failure that would look like «the setting does nothing».
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');

const mod = await import(join(root, 'app', 'src', 'main', 'assets', 'panels', 'catclient.worker.js'));
const T = mod._testing;
const worker = mod.default;

const tuneKt = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'PanelTune.kt');
const dpiKt = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'DpiBypass.kt');
const carrierKt = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'MobileCarrier.kt');
const activity = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'MainActivity.kt');
const vpn = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'CatClientVpnService.kt');
const page = read('app', 'src', 'main', 'assets', 'panels', 'catclient.worker.js');
const en = read('app', 'src', 'main', 'res', 'values', 'strings.xml');
const fa = read('app', 'src', 'main', 'res', 'values-fa', 'strings.xml');

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async list() { return { keys: [...this.m.keys()].map((name) => ({ name })) }; }
  async delete(k) { this.m.delete(k); }
}

const HOST = 'tune-demo.workers.dev';
const UUID = '11111111-2222-4333-8444-555555555555';

/**
 * A fresh KV per test is not enough: the worker keeps a settings cache in module scope, so
 * one test's write would be read by the next one. Clear it, then build the environment.
 */
function envFor() {
  T.kvCacheClear();
  return { CAT_KV: new FakeKV(), UUID };
}

function req(path, { method = 'GET', headers = {}, body, env } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) {
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
    init.headers['content-type'] = 'application/json';
  }
  return worker.fetch(new Request('https://' + HOST + path, init), env, { waitUntil() {} });
}

/** Owner proof in this harness: the master UUID is the password until one is set. */
function ownerHeaders() {
  return { 'x-cat-key': 'admin' };
}

test('the wire format the app parses is the one the panel emits', async () => {
  const env = envFor();
  const r = await req('/api/tune?format=text', { env });
  assert.equal(r.status, 200, 'the app cannot reach /api/tune?format=text');
  assert.match(r.headers.get('content-type') || '', /^text\/plain/, 'the app refuses anything that is not text/plain');
  const body = await r.text();
  const lines = body.trim().split('\n');
  assert.equal(lines[0], T.PANEL_TUNE_MAGIC, 'the magic line no longer matches PanelTune.MAGIC');
  assert.match(tuneKt, new RegExp(`MAGIC = "${T.PANEL_TUNE_MAGIC.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`), 'the two magic constants drifted apart');
  assert.match(lines[1], /^version=/, 'the version line is missing');
});

test('text and JSON describe the same table (no second source of truth)', async () => {
  const env = envFor();
  const put = await req('/api/tune', {
    method: 'PUT',
    headers: ownerHeaders(),
    body: { tune: { mci: 'pattn', irancell: 'gaming' } },
    env,
  });
  assert.equal(put.status, 200, 'the owner could not save a recommendation');
  const saved = await put.json();
  assert.deepEqual(saved.tune, { mci: 'pattn', irancell: 'gaming' });

  const asJson = await (await req('/api/tune', { env })).json();
  const asText = (await (await req('/api/tune?format=text', { env })).text()).trim().split('\n');

  for (const carrier of T.TUNE_CARRIERS) {
    const fromText = asText.find((l) => l.startsWith(carrier + '='));
    assert.equal(
      fromText ? fromText.slice(carrier.length + 1) : undefined,
      asJson.tune[carrier],
      `carrier ${carrier} differs between the JSON and the text view`,
    );
  }
  assert.equal(asJson.tune.irancell, 'gaming', 'the save did not survive a re-read');
  assert.equal(asJson.tune.mci, 'pattn');
});

test('a non-owner can read the recommendation but cannot change it', async () => {
  const env = envFor();
  const anon = await req('/api/tune?format=text', { env });
  assert.equal(anon.status, 200, 'reading is public on purpose (no secret in the payload)');
  const denied = await req('/api/tune', { method: 'PUT', body: { tune: { mci: 'pattn' } }, env });
  assert.equal(denied.status, 401, 'an anonymous writer was allowed to retune the app');
  const after = await (await req('/api/tune', { env })).json();
  assert.deepEqual(after.tune, {}, 'the denied write still changed the table');
});

test('unknown carriers, unknown presets and junk are dropped, never served', async () => {
  assert.deepEqual(T.sanitizeTune({ mci: 'PATTN', irancell: 'nope', rightel: ' auto ', evil: 'mci' }), { mci: 'pattn' });
  assert.deepEqual(T.sanitizeTune(null), {});
  assert.deepEqual(T.sanitizeTune('mci'), {});
  assert.deepEqual(T.sanitizeTune({ mci: { $ne: 1 } }), {});

  // …and a blob edited by hand (restore, hand-written KV, an older panel) is sanitized on
  // the way OUT too: the phone must never receive a value it would silently drop.
  const env = envFor();
  await env.CAT_KV.put('cat:v6:settings', JSON.stringify({ tune: { mci: 'pattn', irancell: '../../etc/passwd' } }));
  const j = await (await req('/api/tune', { env })).json();
  assert.deepEqual(j.tune, { mci: 'pattn' }, 'a hostile value reached the phone');
  const text = await (await req('/api/tune?format=text', { env })).text();
  assert.ok(!text.includes('etc/passwd'), 'a hostile value reached the phone over the text view');
});

test('the ids the panel offers are exactly the ids the app knows', async () => {
  const enumIds = [...dpiKt.matchAll(/^\s{4}[A-Z]+\(\"([a-z]+)\",/gm)].map((m) => m[1]);
  assert.ok(enumIds.length >= 7, 'could not read the preset ids out of DpiBypass.kt');
  assert.deepEqual(
    [...T.TUNE_PRESETS].sort(),
    [...enumIds].sort(),
    'the panel can offer a preset the app would silently drop (or misses one)',
  );

  const carrierIds = [...carrierKt.matchAll(/^\s{4}[A-Z]+\("([a-z]+)"\),/gm)].map((m) => m[1]);
  for (const carrier of T.TUNE_CARRIERS) {
    assert.ok(carrierIds.includes(carrier), `the app does not know carrier «${carrier}»`);
  }

  // The app's own ladder: manual pick → panel recommendation → built-in carrier table.
  assert.match(dpiKt, /fun forCarrier\(carrier: MobileCarrier\)/, 'the built-in carrier table is gone');
  assert.match(dpiKt, /fun resolve\(storedId: String\?, carrier: MobileCarrier, tune: PanelTune\? = null\)/, 'resolve() changed shape');
  assert.match(dpiKt, /AUTO_ID = "auto"/, 'the «automatic» choice disappeared');
  const auto = dpiKt.slice(dpiKt.indexOf('fun presetId(): String'), dpiKt.indexOf('fun presetId(): String') + 500);
  assert.match(auto, /AUTO_ID/, 'the store no longer round-trips «auto»');
});

test('the connect path resolves the preset instead of trusting the stored id', () => {
  const i = vpn.indexOf('ByeDpiProxy.start(');
  assert.ok(i > 0, 'ByeDpiProxy.start is gone from the service');
  const before = vpn.slice(Math.max(0, i - 900), i);
  assert.match(before, /DpiFragmentPreset\.resolve\(/, 'the service went back to byId(stored) — carrier and panel are ignored');
  assert.match(before, /CarrierDetector\.detect\(this\)/, 'the carrier is no longer detected at connect time');
  assert.match(before, /panelTuneStore\.read\(\)/, 'the panel recommendation is no longer read at connect time');
  assert.match(vpn, /panelTuneStore = PanelTuneStore\(this\)/, 'the service never builds the tune store');
});

test('the app never sends a fragment-relevant request before it has a URL', () => {
  assert.match(tuneKt, /https:\/\/\$base.*\/api\/tune\?format=text|\+ "\/api\/tune\?format=text"/, 'the fetch URL changed');
  assert.match(tuneKt, /if \(base\.isBlank\(\)\) return null/, 'a blank base URL would be dialled');
  assert.match(tuneKt, /instanceFollowRedirects = false/, 'a redirect could hand the phone someone else’s page');
  assert.match(tuneKt, /!type\.startsWith\("text\/plain"\)\) return null/, 'an HTML answer would be parsed as a table');
  assert.match(tuneKt, /if \(parsed\.isEmpty\) return null/, 'an empty table would overwrite a good cache');
  const refresh = tuneKt.slice(tuneKt.indexOf('fun refresh('));
  assert.match(refresh, /store\.save\(body, parsed\.version\)/, 'a good answer is not cached');
});

test('the settings card shows the decision and keeps every carrier reachable', () => {
  assert.match(activity, /renderFragmentCarrierLine\(\)/, 'the carrier line is never rendered');
  assert.match(activity, /R\.string\.dpi_carrier_line/, 'the line text is not wired');
  assert.match(activity, /R\.string\.dpi_preset_auto to DpiFragmentPreset\.AUTO_ID/, '«Automatic» is missing from the chips');
  assert.match(activity, /R\.string\.dpi_preset_pattn to DpiFragmentPreset\.PATTN\.id/, 'the PattN preset exists in the enum but is still not offered');
  assert.match(activity, /fun presetLabelRes\(preset: DpiFragmentPreset\)/, 'preset labels are no longer resolved');
  for (const res of ['dpi_preset_auto', 'dpi_preset_pattn', 'dpi_carrier_line', 'dpi_tune_from_panel']) {
    assert.match(en, new RegExp(`name="${res}"`), `${res} is missing from the English strings`);
    assert.match(fa, new RegExp(`name="${res}"`), `${res} is missing from the Persian strings`);
  }
  // A default-network callback is what keeps the line honest when the phone switches
  // carriers — NOT the telephony broadcast: TelephonyManager.ACTION_NETWORK_OPERATOR_CHANGED
  // does not exist in the public SDK (it broke the Kotlin compile once), and
  // CONNECTIVITY_ACTION is deprecated.
  assert.match(activity, /registerDefaultNetworkCallback\(carrierChangeCallback\)/, 'the line freezes on the carrier seen at open time');
  assert.match(activity, /unregisterNetworkCallback\(carrierChangeCallback\)/, 'a leaked callback after onStop');
  // …and no CODE line may mention it again (the comment above it explains why, so the guard
  // has to ignore comments rather than the whole file).
  const codeLines = activity.split('\n').filter((l) => {
    const t = l.trim();
    return !t.startsWith('*') && !t.startsWith('//') && !t.startsWith('/*');
  });
  assert.ok(!codeLines.some((l) => l.includes('ACTION_NETWORK_OPERATOR_CHANGED')), 'back to a constant that does not exist');

  const line = activity.slice(
    activity.indexOf('private fun renderFragmentCarrierLine()'),
    activity.indexOf('private fun carrierLabelRes('),
  );
  assert.ok(line.length > 100, 'could not locate renderFragmentCarrierLine');
  assert.match(line, /CarrierDetector\.detect\(this, refresh = true\)/, 'a stale cached carrier is shown');
  assert.match(line, /DpiFragmentPreset\.resolve\(stored, carrier, tune\)/, 'the line no longer shows what will actually be applied');
  assert.match(line, /dpi_tune_from_panel/, 'the line does not say when the recommendation came from the panel');
});

test('the panel page offers the same ids and both locales carry the labels', () => {
  for (const key of ['tune_title', 'tune_hint', 'tune_auto', 'tune_save', 'tune_saved', 'tune_fail',
    'tune_mci', 'tune_irancell', 'tune_rightel', 'tune_tci',
    'dpi_preset_default', 'dpi_preset_mci', 'dpi_preset_irancell', 'dpi_preset_rightel',
    'dpi_preset_tci', 'dpi_preset_gaming', 'dpi_preset_pattn']) {
    const occurrences = page.split(`${key}:'`).length - 1;
    assert.ok(occurrences >= 2, `i18n key ${key} is not in both locales (found ${occurrences})`);
  }
  assert.match(page, /data-tune="mci"/, 'the MCI select is gone from the settings page');
  assert.match(page, /id="btnTune"/, 'the save button is gone');
  assert.match(page, /api\('\/api\/tune',\{method:'PUT'/, 'the page stopped using the tune endpoint');
  // The labels must come from the server list, not from a hand-written copy that drifts.
  const load = page.slice(page.indexOf('function loadTune()'), page.indexOf('function saveTune()'));
  assert.match(load, /j\.presets/, 'the page builds its own preset list instead of using the panel’s');
});
