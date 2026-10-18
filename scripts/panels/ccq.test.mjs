/**
 * Nightly country-quality sampler (cronCountryQuality) regression tests.
 * Locks in: quota-safe rotation (8 countries × 2 IPs per night), merge-not-wipe
 * of the existing table, the UTC-day guard, and fail-safety without sockets.
 * Runs against the readable source — or an obfuscated artifact via
 * CAT_PANEL_WORKER=<path> (release-parity proof, same switch as the main suite).
 * Usage: node scripts/panels/ccq.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');
const mod = await import(workerPath);
const T = mod._testing;
let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } };
class FakeKV {
  constructor() { this.m = new Map(); this.writes = 0; }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.writes++; this.m.set(k, v); }
  async list() { return { keys: [...this.m.keys()].map((name) => ({ name })) }; }
  async delete(k) { this.m.delete(k); }
}
const SETTINGS_KEY = 'cat:v6:settings';
const QUALITY_KEY = 'cat_cc_quality_v1';
const DAY_KEY = 'cat_ccq_day_v1';
const MASTER = '11111111-2222-4333-8444-555555555555';

function makeEnv(ips, ipCountries, prevQuality) {
  const kv = new FakeKV();
  /* exactly what the panel persists: clean addresses in ips + an addr→CC map */
  kv.m.set(SETTINGS_KEY, JSON.stringify({ ips, ipCountries, updatedAt: Date.now() }));
  if (prevQuality) kv.m.set(QUALITY_KEY, JSON.stringify(prevQuality));
  return { kv, env: { CAT_KV: kv, UUID: MASTER } };
}

/* fake TCP sockets module — the sampler only needs connect().opened + close() */
let connects = [];
T.__setSockets({
  connect({ hostname, port }) {
    connects.push(hostname + ':' + port);
    return { opened: Promise.resolve(), close() {} };
  },
});

const CCS = ['DE', 'NL', 'FR', 'GB', 'US', 'CA', 'JP', 'SG', 'IN', 'TR', 'AE', 'RU', 'BR', 'AU', 'KR', 'IT', 'ES', 'SE', 'PL', 'CH'];
const ips = [];
const ipCountries = {};
CCS.forEach((cc, i) => { for (let k = 0; k < 3; k++) { const a = '10.0.' + i + '.' + k; ips.push(a); ipCountries[a] = cc; } });
const prevTable = { at: 1, cc: { ZZ1: { p50: 10, p95: 12, n: 2, at: 1 }, ZZ2: { p50: 20, p95: 22, n: 2, at: 1 } } };

/* ── case 1: one nightly run — cap + merge ─────────────────────────────── */
const realNow = Date.now.bind(Date);
const DAY = 86400000;
/* the sampler reads BOTH Date.now() and new Date(), so shift the whole Date */
function fakeDay(offsetDays) {
  const Real = Date, delta = offsetDays * DAY;
  class FakeDate extends Real {
    constructor(...args) { if (args.length === 0) super(realNow() + delta); else super(...args); }
    static now() { return realNow() + delta; }
  }
  globalThis.Date = FakeDate;
  return () => { globalThis.Date = Real; };
}
let { kv, env } = makeEnv(ips, ipCountries, prevTable);
T.kvCacheClear(); connects = [];
await T.cronCountryQuality(env);
const table1 = JSON.parse(kv.m.get(QUALITY_KEY));
const prevKeys = new Set(['ZZ1', 'ZZ2']);
const sampled1 = Object.keys(table1.cc).filter((k) => !prevKeys.has(k));
check('merge keeps previous rows (table is never wiped)', !!table1.cc.ZZ1 && !!table1.cc.ZZ2);
check('at most 8 countries sampled in one night', sampled1.length <= 8 && sampled1.length > 0, 'got ' + sampled1.length);
check('at most 2 IPs probed per country (≤16 connects total)', connects.length <= 16 && connects.length > 0, 'got ' + connects.length);
check('every sampled country has n ≤ 2', sampled1.every((k) => table1.cc[k].n <= 2));
check('country tags match the seeded list', sampled1.every((k) => CCS.includes(k)));
check('day marker written (one run per UTC day)', JSON.parse(kv.m.get(DAY_KEY)).day === new Date().toISOString().slice(0, 10));

/* ── case 2: same-day second run is a no-op ────────────────────────────── */
const writesBefore = kv.writes; connects = [];
await T.cronCountryQuality(env);
check('second run same day performs zero probes', connects.length === 0, 'got ' + connects.length);
check('second run same day performs zero writes', kv.writes === writesBefore, 'writes ' + writesBefore + '→' + kv.writes);

/* ── case 3: next night rotates the window (different countries + IPs) ── */
const restore3 = fakeDay(1);
T.kvCacheClear(); connects = [];
await T.cronCountryQuality(env);
restore3();
const table2 = JSON.parse(kv.m.get(QUALITY_KEY));
const sampled2 = Object.keys(table2.cc).filter((k) => !prevKeys.has(k));
const set1 = new Set(sampled1);
const rotatedOut = sampled2.filter((k) => !set1.has(k));
check('next night samples a different window (rotation works)', rotatedOut.length > 0, 'overlap ' + (sampled2.length - rotatedOut.length) + '/' + sampled2.length);
check('merge still keeps old rows after rotation', !!table2.cc.ZZ1 && !!table2.cc.ZZ2);
check('night 2 adds at most 8 new countries (cumulative cap holds)', (sampled2.length - sampled1.length) <= 8 && sampled2.length <= 16, 'cumulative ' + sampled2.length);

/* ── case 4: no sockets module → silent no-op, no throw, no write ─────── */
T.__setSockets(null);
T.kvCacheClear();
const restore4 = fakeDay(2);
const { kv: kv3, env: env3 } = makeEnv(ips, ipCountries, null);
let threw = null;
try { await T.cronCountryQuality(env3); } catch (e) { threw = e; }
restore4();
check('no sockets → cron never throws', threw === null, threw && threw.message);
check('no sockets → no quality write', kv3.m.get(QUALITY_KEY) === undefined && kv3.writes === 0, 'writes ' + kv3.writes);

/* ── case 5: every probe unreachable → skipped rows, old data survives ── */
T.__setSockets({ connect() { throw new Error('tcp refused'); } });
T.kvCacheClear();
const restore5 = fakeDay(3);
({ kv, env } = makeEnv(ips, ipCountries, prevTable));
let threw2 = null;
try { await T.cronCountryQuality(env); } catch (e) { threw2 = e; }
restore5();
const table3 = JSON.parse(kv.m.get(QUALITY_KEY));
check('all probes failing → no throw', threw2 === null, threw2 && threw2.message);
check('all probes failing → previous table survives untouched', !!table3.cc.ZZ1 && !!table3.cc.ZZ2 && Object.keys(table3.cc).length === 2);
check('all probes failing → day marker still written (no retry storm)', !!kv.m.get(DAY_KEY));
T.__setSockets(null);

console.log(failures ? '✗ ' + failures + ' FAILED' : 'ALL PASSED');
process.exit(failures ? 1 : 0);
