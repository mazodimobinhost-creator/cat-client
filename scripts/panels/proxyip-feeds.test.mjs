/**
 * ProxyIP feed formats: the daily-scanned validated pool + the space-separated country files.
 *
 * Research (2026-10): the ecosystem's freshest ProxyIP sources are NiREvil's — a DAILY-scanned
 * validated table (~76 countries, port 443 verified, per-IP risk score) shipped as markdown, and
 * per-country files where every line is SPACE-separated «ip port» (103.109.234.61 443). The panel's
 * txt parser died on both: markdown is not txt, and a space-separated line is not an address — so
 * owners who pasted those feeds got an empty pool with an «ok» status.
 * Pins:
 *   - md-daily: table rows parse to IPs; risk > 5 is dropped (the tail is CAPTCHA-spam exits);
 *     junk rows, high-risk rows and duplicate IPs never reach the pool;
 *   - txt: «ip port» lines pin their port exactly like «ip:port» (and both beat the 443 default);
 *   - end to end: refreshProxyRepos merges the new feed with the old ones, the healthy pool serves
 *     the parsed entries port-pinned, and DEFAULT_PROXY_REPOS carries the daily source (real URL).
 *
 * Usage: node scripts/panels/proxyip-feeds.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const loadModule = async (tag) => import(pathToFileURL(workerPath).href + '?panel=' + tag);

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 260) : '')); }
};

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
const UUID = '78d0b256-8174-444d-9553-84ec9d8f84ee';
const HOST = 'edge-pedre.catclient-0ltgml5i.workers.dev';

const mod = await loadModule('pxfeeds');
const T = mod._testing;

/* ── md-daily: NiREvil daily table ─────────────────────────────────────────── */
const MD_SAMPLE = [
  '<img src="https://img.shields.io/badge/validated_proxies-3-966600" />',
  '|   IP   |   ISP    |   Location   |  Risk Score  |',
  '|:-------|:---------|:------------:|:------------:|',
  '| <pre><code>35.210.99.51</code></pre> | Google LLC | Brussels Capital, Brussels | <img src="https://img.shields.io/badge/-0-C9A227" /> |',
  '| <pre><code>3.29.240.49</code></pre> | Amazon Data Services UAE | Dubai, Dubai | <img src="https://img.shields.io/badge/-2-C79F26" /> |',
  '| <pre><code>146.70.37.253</code></pre> | M247 Ltd | Dubai | <img src="https://img.shields.io/badge/-51-E74C3C" /> |',
  '| <pre><code>not-an-ip</code></pre> | Junk | Nowhere | <img src="https://img.shields.io/badge/-0-C9A227" /> |',
  '| <pre><code>35.210.99.51</code></pre> | Google LLC | duplicate row | <img src="https://img.shields.io/badge/-1-C9A227" /> |',
].join('\n');
{
  const list = T.parseProxyFeed('md-daily', MD_SAMPLE);
  const ips = list.map((x) => x.ip);
  check('md-daily parses validated rows', ips.includes('35.210.99.51') && ips.includes('3.29.240.49'), JSON.stringify(ips));
  check('md-daily drops risk > 5 (the CAPTCHA tail)', !ips.includes('146.70.37.253'), JSON.stringify(ips));
  check('md-daily drops junk cells', !ips.includes('not-an-ip'));
  check('md-daily on garbage → empty, no throw', T.parseProxyFeed('md-daily', 'no tables here').length === 0);
}

/* ── txt: the SPACE-separated country files ────────────────────────────────── */
{
  const list = T.parseProxyFeed('txt', '# header\n103.109.234.61 443\n103.121.48.87 8443\n198.51.100.7\nbad line here now\n');
  const by = Object.fromEntries(list.map((x) => [x.ip, x]));
  check('txt «ip port» lines keep their verified port', list.length === 3 && by['103.121.48.87'].ms === 9999, JSON.stringify(list));
  const pins = {};
  for (const x of list) pins[x.ip] = (x.port || null);
  check('txt space lines are port-pinned like ip:port', JSON.stringify(Object.keys(by)) === JSON.stringify(['103.109.234.61', '103.121.48.87', '198.51.100.7']), JSON.stringify(by));
  // the SPACE form must produce the SAME entries as the colon form
  const colon = T.parseProxyFeed('txt', '103.109.234.61:443\n103.121.48.87:8443\n198.51.100.7\n');
  check('txt space form == colon form', JSON.stringify(list.map((x) => x.ip)) === JSON.stringify(colon.map((x) => x.ip)), JSON.stringify({ list, colon }));
}

/* ── end to end: refresh merges the daily feed into the pool ───────────────── */
{
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
  await mod.default.fetch(new Request('https://' + HOST + '/api/ips', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ips: ['104.16.88.20'], source: 'manual' }) }), env, { waitUntil() {} });
  await mod.default.fetch(new Request('https://' + HOST + '/api/settings', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({
    proxyRepos: [
      { id: 'daily', name: 'nirevil daily', url: 'https://example.invalid/ProxyIP-Daily.md', kind: 'md-daily', cc: '', enabled: true },
      { id: 'cc-de', name: 'nirevil DE', url: 'https://example.invalid/DE.txt', kind: 'txt', cc: 'DE', enabled: true },
    ],
  }) }), env, { waitUntil() {} });
  const feed = async (url) => {
    if (String(url).includes('Daily')) return { ok: true, status: 200, text: async () => MD_SAMPLE };
    return { ok: true, status: 200, text: async () => '103.109.234.61 443\n103.121.48.87 8443\n' };
  };
  const rep = await T.refreshProxyRepos(env, feed);
  check('refresh merges md-daily + space-txt feeds (dedup incl. duplicate table rows)', rep.ok && rep.total === 4, JSON.stringify(rep));
  const pool = await T.proxyRepoHealthyPool(env, 20);
  const pmap = Object.fromEntries(pool.map((p) => [p.ip, p.cc]));
  check('daily IPs reach the pool port-pinned 443', '35.210.99.51:443' in pmap && '3.29.240.49:443' in pmap, JSON.stringify(pmap));
  check('high-risk IP never reaches the pool', !('146.70.37.253:443' in pmap));
  check('space-txt IPs keep verified ports + repo country tag', pmap['103.121.48.87:8443'] === 'DE' && pmap['103.109.234.61:443'] === 'DE', JSON.stringify(pmap));
}

/* ── the default list ships the daily source ───────────────────────────────── */
{
  const defs = T.sanitizeProxyRepos(null);
  const daily = defs.find((r) => r.id === 'nirevil-daily');
  check('DEFAULT_PROXY_REPOS carries the daily source', !!daily && daily.kind === 'md-daily' && daily.url.includes('ProxyIP-Daily.md'), JSON.stringify(daily));
  check('default repo list still fits the cap (≤10)', defs.length <= 10, String(defs.length));
}

if (failures) { console.error(failures + ' FAILURE(S)'); process.exit(1); }
console.log('PROXYIP FEED TESTS PASSED');
