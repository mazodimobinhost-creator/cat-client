/**
 * ECH (Encrypted ClientHello) regression tests.
 * The panel ships the SHARED Cloudflare edge ECH in Xray's live-query form
 * («cloudflare-ech.com+udp://1.1.1.1») so every CF-fronted SNI gets its real
 * SNI encrypted — key rotation can't stale a value the client re-resolves.
 * Covers: default value in links, ?ech=1 gating, custom values, 'auto' (own
 * HTTPS RR via DoH + 24h KV cache), mihomo ech-opts in clash output (query
 * form → query-server-name, base64 → config), Xray echConfigList, and the
 * deliberate sing-box omission (FATAL-without-DNS-record hazard).
 * Runs on the readable source or an obfuscated artifact: CAT_PANEL_WORKER=<path>
 * Usage: node scripts/panels/ech.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

/* DNS stub must be installed BEFORE the module is imported (the worker keeps
 * its own fetch reference only at call time, but keep parity with the harness). */
const DoH_HITS = [];
globalThis.fetch = (input) => {
  const u = typeof input === 'string' ? input : (input && input.url) || String(input);
  if (String(u).includes('dns-query')) {
    DoH_HITS.push(String(u));
    return Promise.resolve({
      ok: true,
      json: async () => ({ Answer: [{ type: 64, data: '1 . alpn=h2 ech=AEX+DQBB3gAgACCD1sY0' }] }),
    });
  }
  return Promise.reject(new Error('net-stubbed-in-tests'));
};

const mod = await import(workerPath);
const worker = mod.default; const T = mod._testing;
let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } };
class FakeKV { constructor(){this.m=new Map();} async get(k){return this.m.has(k)?this.m.get(k):null;} async put(k,v){this.m.set(k,v);} async list(){return {keys:[...this.m.keys()].map((name)=>({name}))};} async delete(k){this.m.delete(k);} }
const HOST = 'catpanel-demo.workers.dev';
const MASTER = '11111111-2222-4333-8444-555555555555';
const SETTINGS_KEY = 'cat:v6:settings';
const DEFAULT_ECH = 'cloudflare-ech.com+udp://1.1.1.1';
function makeEnv(settings) {
  const kv = new FakeKV();
  if (settings) kv.m.set(SETTINGS_KEY, JSON.stringify(settings));
  return { kv, env: { CAT_KV: kv, UUID: MASTER } };
}
const req = (p, env) => worker.fetch(new Request('https://' + HOST + p), env, { waitUntil(){} });
const body = async (p, env) => (await req(p, env)).text();

/* ── 1: default settings → shared CF ECH in links when ?ech=1 ─────────── */
{
  const { env } = makeEnv(null);
  T.kvCacheClear();
  const raw = await body('/sub/' + MASTER + '?raw=1&ech=1', env);
  const enc = encodeURIComponent(DEFAULT_ECH); // cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1
  check('default ECH value rides every TLS link (?ech=1)', raw.includes('ech=' + enc) && raw.includes('vless://'), raw.slice(0, 80));
  check('+ and :// are fully percent-encoded (Xray must read "+", not a space)', enc === 'cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1');
  const off = await body('/sub/' + MASTER + '?raw=1', env);
  check('no ech param without ?ech=1 (opt-in preserved)', !off.includes('ech='));
}
/* ── 2: custom value used verbatim ─────────────────────────────────────── */
{
  const { env } = makeEnv({ echList: 'ech.example.test+https://dns.example/dns-query' });
  T.kvCacheClear();
  const raw = await body('/sub/' + MASTER + '?raw=1&ech=1', env);
  check('custom ECH value is used verbatim (encoded)',
    raw.includes('ech=' + encodeURIComponent('ech.example.test+https://dns.example/dns-query')));
}
/* ── 3: 'auto' → the SNI's own HTTPS RR via DoH (+24h KV cache) ────────── */
{
  const { kv, env } = makeEnv({ echList: 'auto' });
  T.kvCacheClear();
  const raw = await body('/sub/' + MASTER + '?raw=1&ech=1', env);
  check("'auto' fetches the SNI's own record via DoH", DoH_HITS.length >= 1);
  check("'auto' → the record's base64 ECHConfigList lands in links", raw.includes('ech=AEX%2BDQBB3gAgACCD1sY0'), raw.slice(0, 90));
  check('fetched ECH list cached in KV (24h)', !!kv.m.get('cat_ech_v1') && kv.m.get('cat_ech_v1').includes('AEX'));
}
/* ── 4: 'off' → disabled even with ?ech=1 ─────────────────────────────── */
{
  const { env } = makeEnv({ echList: 'off' });
  T.kvCacheClear();
  check("'off' = ECH disabled (no ech param)", !(await body('/sub/' + MASTER + '?raw=1&ech=1', env)).includes('ech='));
  check("'off' = no echConfigList in xray json", !(await body('/xray/' + MASTER + '?raw=1&ech=1', env)).includes('echConfigList'));
  check("'off' = no ech-opts in clash", !(await body('/clash/' + MASTER + '?ech=1', env)).includes('ech-opts'));
}
/* ── 5: clash / mihomo ech-opts ───────────────────────────────────────── */
{
  const { env } = makeEnv(null);
  T.kvCacheClear();
  const yaml = await body('/clash/' + MASTER + '?ech=1', env);
  check('clash: ech-opts emitted under the proxy', yaml.includes('ech-opts:') && yaml.includes('enable: true'));
  check('clash: query form → query-server-name', yaml.includes('query-server-name:') && yaml.includes('cloudflare-ech.com') && !yaml.includes('config:'));
  const y2 = await body('/clash/' + MASTER, env);
  check('clash: no ech-opts without ?ech=1', !y2.includes('ech-opts'));

  const { env: env2 } = makeEnv({ echList: 'AEX+DQBB3gAgACCD1sY0' });
  T.kvCacheClear();
  const yaml2 = await body('/clash/' + MASTER + '?ech=1', env2);
  check('clash: base64 (+ inside!) → config, not query-server-name',
    yaml2.includes('config:') && !yaml2.includes('query-server-name') && yaml2.includes('AEX+DQBB3gAgACCD1sY0'),
    yaml2.split('\n').filter((l) => l.includes('ech') || l.includes('config')).slice(0, 3).join(' | '));
}
/* ── 6: xray json echConfigList ───────────────────────────────────────── */
{
  const { env } = makeEnv(null);
  T.kvCacheClear();
  const json = await body('/xray/' + MASTER + '?raw=1&ech=1', env);
  check('xray: tlsSettings.echConfigList carries the shared CF value',
    json.includes('"echConfigList": "cloudflare-ech.com+udp://1.1.1.1"'));
  check('xray: echConfigList sits inside tlsSettings', /"tlsSettings"[^}]*"echConfigList"/.test(json));
}
/* ── 7: sing-box stays clean on purpose (FATAL hazard) ────────────────── */
{
  const { env } = makeEnv(null);
  T.kvCacheClear();
  const sb = await body('/singbox/' + MASTER + '?ech=1', env);
  check('sing-box: ECH deliberately NOT emitted (no-FATAL guarantee)', !sb.includes('"ech"'), sb.slice(0, 90));
}
/* ── 8: settings API round-trip keeps the value ───────────────────────── */
{
  const { env } = makeEnv(null);
  T.kvCacheClear();
  const r = await req('/api/ech', env);
  const j = await r.json();
  check('/api/ech reports the effective + shared values', j.ok === true && j.effective === DEFAULT_ECH && j.shared === DEFAULT_ECH, JSON.stringify(j).slice(0, 140));
}
console.log(failures ? '✗ ' + failures + ' FAILED' : 'ALL PASSED');
process.exit(failures ? 1 : 0);
