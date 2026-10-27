// Cat Panel on any Node.js host — KataBump, Lucity, Shiprr, Railway, a VPS… (Cat Client, GPL-3.0).
//
// This launcher is ALL you upload (plus package.json). On start it fetches the newest Cat Panel release
// from GitHub (the panel + its Node adapter), checks both against the release's SHA-256 list, caches them
// next to this file and runs the panel on the port the host gives (SERVER_PORT / PORT, default 8080).
// The panel then works exactly like on Cloudflare/Vercel: open /panel, sign in (admin / admin — change it),
// copy the subscription. Users and settings are kept in ./cat-data (or CAT_DATA_DIR) when the host has a disk.
//
// Optional environment variables:
//   CAT_PANEL_TAG        pin one release (e.g. v1.10.0-beta190) instead of the newest
//   CAT_DATA_DIR         where users/settings are stored (a volume on Lucity; KataBump: leave as is)
//   CAT_NO_DISK=1        do not store anything on disk (hosts without a persistent disk, e.g. Shiprr)
//   KV_REST_API_URL + KV_REST_API_TOKEN   Upstash Redis instead of the disk (works everywhere)
//   UUID / TROJAN_PASS   fixed main identity (otherwise the panel makes one and stores it)
//   CAT_PANEL_SOURCE_DIR a folder that already holds catclient.worker.js + cat-panel-adapter.mjs (offline)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = process.env.CAT_REPO || 'mazodimobinhost-creator/cat-client';
export const PANEL_ASSET = 'catclient.worker.js';
export const ADAPTER_ASSET = 'cat-panel-adapter.mjs';
export const SUMS_ASSET = 'cat-node-panel.sha256';
const SOCKETS_IMPORT = /import\((['"])cloudflare:sockets\1\)/g;
const log = (m) => console.log('[cat] ' + m);

/** «<sha256>  <name>» lines (sha256sum format) → Map(name → hex). */
export function parseSums(text) {
  const out = new Map();
  for (const line of String(text || '').split(/\r?\n/)) {
    const m = /^([0-9a-f]{64})\s+\*?(\S+)\s*$/i.exec(line.trim());
    if (m) out.set(m[2], m[1].toLowerCase());
  }
  return out;
}

/** The panel is a Cloudflare Worker: its one `import("cloudflare:sockets")` becomes the adapter's Node sockets. */
export function patchPanel(src) {
  const n = (String(src).match(SOCKETS_IMPORT) || []).length;
  if (n !== 1) throw new Error('panel bundle: expected one cloudflare:sockets import, found ' + n);
  return String(src).replace(SOCKETS_IMPORT, 'Promise.resolve(globalThis.__catSockets)');
}

/** Newest (by published_at, prereleases included) non-draft release that carries all three files, or exactly `tag`. */
export function pickRelease(list, tag) {
  const need = [PANEL_ASSET, ADAPTER_ASSET, SUMS_ASSET];
  let best = null;
  let bestAt = -1;
  for (const r of Array.isArray(list) ? list : []) {
    if (!r || r.draft) continue;
    if (tag && r.tag_name !== tag) continue;
    const assets = new Map((r.assets || []).map((a) => [a.name, a.browser_download_url]));
    if (!need.every((n) => assets.has(n))) continue;
    const at = Date.parse(r.published_at || r.created_at || '') || 0;
    if (best && at <= bestAt) continue;
    best = { tag: r.tag_name, urls: Object.fromEntries(need.map((n) => [n, assets.get(n)])) };
    bestAt = at;
  }
  return best;
}

/** Port the host hands us: KataBump/Pterodactyl → SERVER_PORT, most PaaS → PORT. */
export function portOf(env) {
  for (const k of ['SERVER_PORT', 'PORT']) { const p = Number(env[k]); if (Number.isInteger(p) && p > 0 && p < 65536) return p; }
  return 8080;
}

export function sha256(buf) { return crypto.createHash('sha256').update(buf).digest('hex'); }

async function fetchBuf(url, ms = 30000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { signal: ctl.signal, redirect: 'follow', headers: { 'user-agent': 'cat-node-panel', accept: 'application/octet-stream, application/json, */*' } });
    if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url);
    return Buffer.from(await r.arrayBuffer());
  } finally { clearTimeout(t); }
}

/** Puts api/index.mjs (adapter) + lib/catpanel.mjs (patched panel) under `codeDir`; returns the version tag. */
export async function prepareCode(codeDir, env = process.env) {
  const stamp = path.join(codeDir, 'version.txt');
  const have = fs.existsSync(path.join(codeDir, 'api', 'index.mjs')) && fs.existsSync(path.join(codeDir, 'lib', 'catpanel.mjs'));
  const cached = have ? fs.readFileSync(stamp, 'utf8').trim() : '';
  let files;
  let tag;
  try {
    if (env.CAT_PANEL_SOURCE_DIR) {
      const dir = env.CAT_PANEL_SOURCE_DIR;
      files = { [PANEL_ASSET]: fs.readFileSync(path.join(dir, PANEL_ASSET)), [ADAPTER_ASSET]: fs.readFileSync(path.join(dir, ADAPTER_ASSET)) };
      const sumsFile = path.join(dir, SUMS_ASSET);
      if (fs.existsSync(sumsFile)) files[SUMS_ASSET] = fs.readFileSync(sumsFile);
      tag = 'local';
    } else {
      let rel;
      if (env.CAT_PANEL_TAG) {
        const base = 'https://github.com/' + REPO + '/releases/download/' + encodeURIComponent(env.CAT_PANEL_TAG) + '/';
        rel = { tag: env.CAT_PANEL_TAG, urls: { [PANEL_ASSET]: base + PANEL_ASSET, [ADAPTER_ASSET]: base + ADAPTER_ASSET, [SUMS_ASSET]: base + SUMS_ASSET } };
      } else {
        const list = JSON.parse((await fetchBuf('https://api.github.com/repos/' + REPO + '/releases?per_page=15', 20000)).toString('utf8'));
        rel = pickRelease(list);
        if (!rel) throw new Error('no release with ' + ADAPTER_ASSET + ' yet');
      }
      tag = rel.tag;
      if (have && cached === tag) { log('panel ' + tag + ' (cached)'); return tag; }
      log('downloading panel ' + tag + ' …');
      files = {};
      for (const n of [SUMS_ASSET, PANEL_ASSET, ADAPTER_ASSET]) files[n] = await fetchBuf(rel.urls[n]);
    }
    if (files[SUMS_ASSET]) {
      const sums = parseSums(files[SUMS_ASSET].toString('utf8'));
      for (const n of [PANEL_ASSET, ADAPTER_ASSET]) {
        const want = sums.get(n);
        if (!want) throw new Error(SUMS_ASSET + ' has no line for ' + n);
        if (sha256(files[n]) !== want) throw new Error('SHA-256 mismatch for ' + n + ' — download refused');
      }
    } else if (!env.CAT_PANEL_SOURCE_DIR) throw new Error(SUMS_ASSET + ' missing');
    const panel = patchPanel(files[PANEL_ASSET].toString('utf8'));
    const adapter = files[ADAPTER_ASSET].toString('utf8');
    if (!adapter.includes("import('../lib/catpanel.mjs')") || !adapter.includes('export default server')) throw new Error('adapter shape changed');
    for (const d of ['api', 'lib']) fs.mkdirSync(path.join(codeDir, d), { recursive: true });
    fs.writeFileSync(path.join(codeDir, 'lib', 'catpanel.mjs.tmp'), panel);
    fs.writeFileSync(path.join(codeDir, 'api', 'index.mjs.tmp'), adapter);
    fs.renameSync(path.join(codeDir, 'lib', 'catpanel.mjs.tmp'), path.join(codeDir, 'lib', 'catpanel.mjs'));
    fs.renameSync(path.join(codeDir, 'api', 'index.mjs.tmp'), path.join(codeDir, 'api', 'index.mjs'));
    fs.writeFileSync(stamp, tag + '\n');
    log('panel ' + tag + ' ready (SHA-256 checked)');
    return tag;
  } catch (e) {
    if (have) { log('update skipped (' + e.message + '); running the cached panel ' + (cached || '?')); return cached || 'cached'; }
    throw e;
  }
}

function writable(dir) {
  try { fs.mkdirSync(dir, { recursive: true }); const f = path.join(dir, '.w'); fs.writeFileSync(f, '1'); fs.unlinkSync(f); return true; } catch (_) { return false; }
}

export async function main(env = process.env) {
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 20) log('⚠ Node ' + process.versions.node + ' — please pick Node 20 or newer in the host settings (22 recommended).');
  if (!globalThis.crypto) globalThis.crypto = crypto.webcrypto;
  const port = portOf(env);
  // The code cache must sit next to this file: the adapter imports `ws` from ./node_modules.
  const codeDir = path.join(HERE, 'cat-code');
  const tag = await prepareCode(codeDir, env);
  env.CAT_PLATFORM = 'node';
  env.CAT_PUBLIC_PORT = env.CAT_PUBLIC_PORT || String(port);
  if (!env.DATA_PLANE_ENABLED) env.DATA_PLANE_ENABLED = 'true';
  const hasRedis = !!((env.KV_REST_API_URL || env.UPSTASH_REDIS_REST_URL) && (env.KV_REST_API_TOKEN || env.UPSTASH_REDIS_REST_TOKEN));
  if (!hasRedis && env.CAT_NO_DISK !== '1') {
    const dataDir = path.resolve(HERE, env.CAT_DATA_DIR || 'cat-data');
    if (writable(dataDir)) { env.CAT_KV_DIR = dataDir; log('storage: ' + dataDir); }
    else log('⚠ ' + dataDir + ' is not writable — running without permanent storage');
  } else log('storage: ' + (hasRedis ? 'Upstash Redis' : 'none (CAT_NO_DISK=1) — users vanish on restart'));
  const { default: server } = await import(pathToFileURL(path.join(codeDir, 'api', 'index.mjs')).href);
  server.keepAliveTimeout = 65000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '0.0.0.0', resolve); });
  log('Cat Panel ' + tag + ' listening on 0.0.0.0:' + port);
  log('open  http://<this server address>:' + port + '/panel   (or your https domain + /panel) — sign in admin / admin and change it');
  return server;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((e) => {
    console.error('[cat] could not start: ' + ((e && e.message) || e));
    console.error('[cat] the host must reach github.com once to fetch the panel (later starts reuse the cached copy).');
    process.exit(1);
  });
}
