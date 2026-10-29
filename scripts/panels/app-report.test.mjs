/**
 * beta220 «📨 report to panel»: Cat Client's opt-in report endpoint.
 *
 * Pins: public POST /api/app-report keeps only anonymised, size-capped fields; one report per minute per IP;
 * a daily cap; no KV → honest 503; owner-only GET/DELETE /api/app-reports with per operator × engine rates;
 * the panel page ships the card in both languages.
 *
 * Usage: node scripts/panels/app-report.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const worker = (await import(workerPath)).default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 240) : '')); }
};
class FakeKV {
  constructor() { this.m = new Map(); this.puts = 0; }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.puts++; this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
const UUID = '11111111-2222-4333-8444-555555555555';
const HOST = 'https://edge-report.catclient-test.workers.dev';
const call = (env, p, { method = 'GET', body, ip, auth } = {}) => {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (ip) headers['cf-connecting-ip'] = ip;
  if (auth) headers['authorization'] = 'Bearer ' + auth;
  return worker.fetch(new Request(HOST + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env, { waitUntil() {} });
};
const report = {
  app: 'cat-client', v: '1.10.0-beta220', sdk: 34, op: 'mci', opc: '43211', net: 'cellular', eng: 'cat-core',
  kind: 'vless/ws/tls', st: 'none', ms: -1, kbps: -1, hops: 2, dns: 'fake-ip',
  note: 'connected but nothing loads <script>x</script>', log: 'realCheck.result status=none',
  server: '1.2.3.4', uuid: UUID,
};

/* 1: no KV → honest 503 */
{
  const env = { UUID };
  const r = await call(env, '/api/app-report', { method: 'POST', body: report, ip: '10.0.0.1' });
  check('no KV → 503 with an honest reason', r.status === 503, r.status);
}

/* 2: stored anonymised, rate-limited, owner view */
{
  const kv = new FakeKV();
  const env = { UUID, CAT_KV: kv };
  const bad = await call(env, '/api/app-report', { method: 'POST', body: { hello: 1 }, ip: '10.0.0.2' });
  check('non-Cat body is rejected (400)', bad.status === 400, bad.status);
  const get = await call(env, '/api/app-report', { ip: '10.0.0.3' });
  check('GET on the public endpoint → 405', get.status === 405, get.status);

  const ok = await call(env, '/api/app-report', { method: 'POST', body: report, ip: '10.0.0.4' });
  const okBody = await ok.json();
  check('a Cat report is kept (one KV write)', ok.status === 200 && okBody.ok === true && kv.puts === 1, JSON.stringify(okBody) + ' puts=' + kv.puts);
  const again = await call(env, '/api/app-report', { method: 'POST', body: report, ip: '10.0.0.4' });
  check('second report from the same IP within a minute → 429', again.status === 429, again.status);
  check('rate-limited report costs no KV write', kv.puts === 1, kv.puts);

  const stored = JSON.parse(kv.m.get('cat_app_reports_v1'));
  const item = stored.items[0];
  check('stored item keeps operator/engine/kind/status', item.op === 'mci' && item.eng === 'cat-core' && item.kind === 'vless/ws/tls' && item.st === 'none', JSON.stringify(item));
  check('unknown fields (server, uuid) are dropped', !('server' in item) && !('uuid' in item) && !JSON.stringify(stored).includes(UUID), JSON.stringify(item));
  check('angle brackets are stripped from free text', !item.note.includes('<') && !item.note.includes('>'), item.note);

  const big = await call(env, '/api/app-report', { method: 'POST', body: Object.assign({}, report, { log: 'x'.repeat(9000) }), ip: '10.0.0.5' });
  check('oversized report is refused', big.status === 413 || big.status === 400, big.status);

  const anon = await call(env, '/api/app-reports');
  check('owner view needs sign-in', anon.status === 401, anon.status);
  const owner = await call(env, '/api/app-reports', { auth: 'admin' });
  const ob = await owner.json();
  check('owner view lists the report and per operator × engine stats', owner.status === 200 && ob.total === 1 && ob.stats[0].key === 'mci · cat-core' && ob.stats[0].none === 1, JSON.stringify(ob).slice(0, 200));
  const del = await call(env, '/api/app-reports', { method: 'DELETE', auth: 'admin' });
  check('owner can clear reports', del.status === 200 && !kv.m.has('cat_app_reports_v1'), del.status);
}

/* 3: daily cap */
{
  const kv = new FakeKV();
  const day = new Date().toISOString().slice(0, 10);
  kv.m.set('cat_app_reports_v1', JSON.stringify({ day, n: 150, items: [] }));
  const r = await call({ UUID, CAT_KV: kv }, '/api/app-report', { method: 'POST', body: report, ip: '10.0.0.9' });
  check('daily cap → 429 without a write', r.status === 429 && kv.puts === 0, r.status + ' puts=' + kv.puts);
}

/* 4: panel page ships the card in both languages */
{
  const fs = await import('node:fs');
  const src = fs.readFileSync(workerPath, 'utf8');
  check('panel page has the reports card + loader', src.includes('arStats') && src.includes('/api/app-reports'));
  check('card text exists in Persian and English', src.includes('گزارش‌های برنامه') && src.includes('App reports (opt-in)'));
}

if (failures) { console.error(`\napp report: ${failures} FAILED`); process.exit(1); }
console.log('\napp report: all passed');
