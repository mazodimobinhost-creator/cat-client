/**
 * Error log + daily usage — the point of this file is the WRITE BUDGET.
 *
 * A naive 「write on every error」 log can burn the whole KV write quota in one
 * bad hour. These tests pin the behaviour that keeps writes low: batched
 * flushes, error collapsing, a 1000/day hard cap, and honest reporting (the
 * counter is isolate-local, so it is marked `approx` and never pretends to be
 * Cloudflare's billed request count).
 *
 * Run standalone:  node scripts/panels/error-log.test.mjs
 */
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;
const { _testing: T } = await import(workerPath);

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

function mockKv() {
  const store = new Map();
  let writes = 0;
  return {
    store,
    get writeCount() { return writes; },
    async get(k) { return store.has(k) ? store.get(k) : null; },
    async put(k, v) { writes++; store.set(k, String(v)); },
    async delete(k) { store.delete(k); },
  };
}
const mkEnv = (kv, extra) => Object.assign({ CAT_KV: kv, UUID: '11111111-2222-4333-8444-555555555555' }, extra || {});
const rec = async (kv) => JSON.parse((await kv.get(T.USAGE_KEY)) || 'null');

console.log('\nError log + daily usage (low-write log)\n');

/* 1 */ {
  const kv = mockKv(); const env = mkEnv(kv);
  T.countRequest(); T.countRequest();
  await T.flushUsage(env);
  ok('idle: a couple of requests do NOT write to KV', kv.writeCount === 0, kv.writeCount);
  const c = await T.readUsage(env);
  ok('no KV record yet → zeroed usage', c.reqs === 0 && c.errors.length === 0 && c.noKv === false);
}

/* 2 */ {
  const kv = mockKv(); const env = mkEnv(kv);
  T.recordError('unhandled', 'boom one');
  T.recordError('unhandled', 'boom two');
  ok('recordError touches no KV at all', kv.writeCount === 0, kv.writeCount);
  const wrote = await T.flushUsage(env, true);
  ok('forced flush persists the queue', wrote === true && kv.writeCount === 1, kv.writeCount);
  const r = await rec(kv);
  ok('both errors stored, newest first', r.errors.length === 2 && r.errors[0].d === 'boom two' && r.errors[1].d === 'boom one', JSON.stringify(r.errors));
  ok('one flush = one write counter bump', r.writes === 1, r.writes);
  ok('day is the UTC date', /^\d{4}-\d{2}-\d{2}$/.test(r.day) && r.day === T.usageDay(), r.day);
}

/* 3 — collapsing: the same error repeated must not flood the ring or KV */
{
  const kv = mockKv(); const env = mkEnv(kv);
  for (let i = 0; i < 25; i++) T.recordError('relay', 'dial failed');
  await T.flushUsage(env, true);
  const r = await rec(kv);
  ok('25 identical errors collapse into ONE entry', r.errors.filter((e) => e.d === 'dial failed').length === 1, r.errors.length);
  ok('collapsed entry keeps a count', r.errors[0].n === 25, r.errors[0].n);
  T.recordError('relay', 'dial failed');
  T.recordError('relay', 'other failure');
  await T.flushUsage(env, true);
  const r2 = await rec(kv);
  ok('a different message starts a new entry', r2.errors.length === 2 && r2.errors[0].d === 'other failure', JSON.stringify(r2.errors.map((e) => e.d)));
}

/* 4 — request counter rides the same single write */
{
  const kv = mockKv(); const env = mkEnv(kv);
  for (let i = 0; i < 120; i++) T.countRequest();
  ok('counting 120 requests writes nothing', kv.writeCount === 0, kv.writeCount);
  await T.flushUsage(env, true);
  const r = await rec(kv);
  ok('flush stores the accumulated request count', r.reqs === 120, r.reqs);
  await T.flushUsage(env, true);
  const r2 = await rec(kv);
  ok('a second flush with no new traffic does not double-count', r2.reqs === 120, r2.reqs);
  ok('but it does count the write itself', r2.writes === 2, r2.writes);
}

/* 5 — the ring keeps only the last 50 */
{
  const kv = mockKv(); const env = mkEnv(kv);
  for (let i = 0; i < 70; i++) T.recordError('e' + i, 'msg ' + i);
  await T.flushUsage(env, true);
  const r = await rec(kv);
  ok('70 errors → ring keeps 50', r.errors.length === T.ERROR_RING && r.errors.length === 50, r.errors.length);
  ok('ring keeps the newest (msg 69 first)', r.errors[0].d === 'msg 69', r.errors[0].d);
  ok('the oldest (msg 0..19) is gone', !r.errors.some((e) => e.d === 'msg 0'));
}

/* 6 — a new UTC day restarts the counter */
{
  const kv = mockKv(); const env = mkEnv(kv);
  await kv.put(T.USAGE_KEY, JSON.stringify({ day: '2001-01-01', reqs: 9999, writes: 990, errors: [{ t: 1, k: 'old', d: 'yesterday', n: 1 }] }));
  T.countRequest();
  await T.flushUsage(env, true);
  const r = await rec(kv);
  ok('new day resets the request counter', r.reqs === 1, r.reqs);
  ok('new day resets the write budget', r.writes === 1, r.writes);
  ok('but the error ring survives the day rollover', r.errors.some((e) => e.d === 'yesterday'));
}

/* 7 — /api/errors is owner-only and reports the truth, not a guess */
{
  const kv = mockKv(); const env = mkEnv(kv);
  T.recordError('unhandled', 'secret detail');
  const anon = await T.handleRequest(new Request('https://x.workers.dev/api/errors'), env, { waitUntil() { } });
  ok('anonymous GET /api/errors → 401', anon.status === 401, anon.status);

  const open = mkEnv(mockKv(), { OPEN_PANEL: 'true' });
  T.recordError('unhandled', 'visible detail');
  const res = await T.handleRequest(new Request('https://x.workers.dev/api/errors'), open, { waitUntil() { } });
  const j = await res.json();
  ok('owner GET /api/errors → 200', res.status === 200 && j.ok === true, res.status);
  ok('usage is flagged approximate (isolate-local counter)', j.usage && j.usage.approx === true);
  ok('budget is reported', j.usage && j.usage.budget === T.DAILY_WRITE_BUDGET && j.usage.budget === 1000, JSON.stringify(j.usage));
  ok('errors are returned', Array.isArray(j.errors) && j.errors.length >= 1, JSON.stringify(j.errors));
  ok('GET flushed the pending queue (page open = 1 write)', j.usage.pending === 0, j.usage.pending);
}

/* 8 — DELETE clears */
{
  const kv = mockKv(); const env = mkEnv(kv, { OPEN_PANEL: 'true' });
  T.recordError('a', 'b');
  await T.flushUsage(env, true);
  const del = await T.handleRequest(new Request('https://x.workers.dev/api/errors', { method: 'DELETE' }), env, { waitUntil() { } });
  const j = await del.json();
  ok('DELETE clears the log', j.ok === true && j.cleared === true);
  ok('cleared log is empty', (await rec(kv)).errors.length === 0);
}

/* 9 — THE BUDGET (must run last: it latches the exhausted flag) */
{
  const kv = mockKv(); const env = mkEnv(kv);
  await kv.put(T.USAGE_KEY, JSON.stringify({ day: T.usageDay(), reqs: 5, writes: T.DAILY_WRITE_BUDGET, errors: [] }));
  T.recordError('burst', 'budget already spent');
  for (let i = 0; i < T.FLUSH_BATCH + 2; i++) T.recordError('burst', 'more ' + i);
  const before = kv.writeCount;
  const wrote = await T.flushUsage(env); // NOT forced
  ok('budget spent → unforced flush writes nothing', wrote === false && kv.writeCount === before, kv.writeCount);
  const env2 = mkEnv(mockKv(), { OPEN_PANEL: 'true' });
  const kv2 = env2.CAT_KV;
  await kv2.put(T.USAGE_KEY, JSON.stringify({ day: T.usageDay(), reqs: 5, writes: T.DAILY_WRITE_BUDGET, errors: [] }));
  const res = await T.handleRequest(new Request('https://x.workers.dev/api/errors'), env2, { waitUntil() { } });
  const j = await res.json();
  ok('the panel is TOLD the budget is exhausted (never silently stale)', j.usage && j.usage.exhausted === true, JSON.stringify(j.usage));
}

console.log('\n' + (fail === 0 ? `error log: ALL ${pass} PASSED` : `error log: ${pass} passed, ${fail} FAILED`) + '\n');
if (fail > 0) process.exit(1);
