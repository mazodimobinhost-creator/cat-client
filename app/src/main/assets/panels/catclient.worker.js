/**
 * 🐱 Cat Panel — lean single-file Cloudflare Worker (VLESS / Trojan over WebSocket)
 *
 * Version 6 — "lean edition", rebuilt from scratch to stay far inside the
 * Cloudflare free-tier limits (CPU time per request, 100k requests/day,
 * 1 000 KV writes/day). What changed versus 5.x and WHY:
 *
 *  - NO per-connection traffic accounting. v5 flushed byte counters into KV
 *    every 45 s / 20 MB; with a handful of active users that burned the 1 000
 *    daily KV writes before noon and the worker started throwing errors.
 *    KV is now written ONLY when the owner clicks save (settings / users).
 *  - NO server-side IP scanning. v5 probed 24+ Cloudflare IPs from inside the
 *    worker on first visits ("auto country discovery") plus an /api/scan API.
 *    Each probe is a subrequest the worker pays for. Scanning now happens on
 *    the device (Cat Client's native scanner or the owner's browser) — exactly
 *    what BPB and ZEUS do — and the result list is pasted / pushed into the
 *    panel once.
 *  - Minimal data plane. The relay is a plain pipe between the WebSocket and a
 *    `cloudflare:sockets` TCP socket: no byte counters, no live-connection
 *    maps, no device-limit bookkeeping on the hot path. Less CPU per chunk →
 *    no "CPU limit exceeded" throttling under sustained downloads.
 *  - One cached KV read per isolate for settings + users (60 s TTL). A new
 *    tunnel connection costs zero KV operations on a warm isolate.
 *  - ~10x smaller script → faster cold starts (the whole file is parsed on
 *    every cold start, that is CPU time too).
 *
 * ROUTES
 *   /                      panel (ZEUS-style dashboard, Persian + English)
 *   /sub/<uuid>            share links (raw; ?b64=1 for base64)   master
 *   /sub64/<uuid>          base64 share links                     master
 *   /clash/<uuid>          Mihomo / Clash Meta YAML               master
 *   /singbox/<uuid>        sing-box / Hiddify JSON                master
 *   /u/<token>[/clash|/singbox|/64]   the same for a panel user
 *   /info/<token>          per-user landing page (links + QR)
 *   /qr.svg?text=…         offline QR
 *   /dns-query             DoH proxy (GET ?dns= / POST dns-message)
 *   /health /api/health    {"ok":true}
 *   /api/login             POST {password[,username]} → session cookie
 *   /api/settings          GET / PUT            (owner)
 *   /api/users[/<id>]      GET / POST / PUT / DELETE (owner)
 *   /api/backup            GET / POST           (owner)
 *   /api/ips               POST {ips:[…]} import clean IPs (owner)
 *   /api/self              visitor ip / colo / country from request.cf (owner)
 *   /api/geo?ip=           cached ipwho.is lookup (used by Cat Client)
 *   /api/scan-targets.json Cloudflare ranges for device-side scanners
 *   WS upgrade on VLESS_PATH / TROJAN_PATH / /ws*   → data plane
 *
 * ENVIRONMENT (Workers → Settings → Variables; all optional)
 *   UUID            master UUID (default: derived from the worker hostname)
 *   PANEL_PASSWORD  panel password (default: the UUID; can also be set in UI)
 *   PANEL_USER      optional username the login form must also match
 *   CAT_KV          KV namespace binding (users + settings persist here)
 *   PROXYIP         comma list of proxy IPs for Cloudflare-hosted destinations
 *   SNI             default SNI / Host written into links (default: worker host)
 *   CF_IPS          comma list of clean IPs / domains added to every subscription
 *   VLESS_PATH      default /ws?ed=2048      TROJAN_PATH  default /trojan
 *   TROJAN_PASS     trojan password (default: UUID)
 *   OPEN_PANEL      "true" → panel readable without password until one is set
 *   Stealth: set settings.panelPath (Panel → Settings) to move the UI to /<path>;
 *   the root then answers a neutral 404 (scanners see nothing). /api + /sub stay put.
 *   OPEN_SUB        "true" → /sub (without uuid) also serves the master links
 *   PANEL_TITLE     header title     DNS_UPSTREAM  DoH upstream for /dns-query
 */

const CAT_PANEL_VERSION = '6.26.0';
/* Teal cat brand mark (replaces the legacy spider glyph) — n namespaces the
 * gradient id so several instances can live on one page. */
function catLogo(n) {
  return '<svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" style="display:block;width:100%;height:100%">' +
    '<defs><linearGradient id="cg' + n + '" x1="0" y1="0" x2="1" y2="1">' +
    '<stop offset="0" stop-color="#00e1c1"/><stop offset="1" stop-color="#2ef2d6"/></linearGradient></defs>' +
    '<path fill="url(#cg' + n + ')" d="M32 13.5C27.5 7 19 3.8 9.5 4.9c1.8 5.7 2.2 11.6 1.2 16.9C7.1 27 5.5 32.3 5.5 37.6 5.5 50.4 17.3 58.5 32 58.5s26.5-8.1 26.5-20.9c0-5.3-1.6-10.6-5.2-15.8-1-5.3-.6-11.2 1.2-16.9C45 3.8 36.5 7 32 13.5Z"/>' +
    '<circle cx="22.8" cy="34.5" r="3.2" fill="#05302a"/><circle cx="41.2" cy="34.5" r="3.2" fill="#05302a"/>' +
    '<path fill="#05302a" d="M28.6 44h6.8L32 49z"/></svg>';
}
const REPO = 'mazodimobinhost-creator/cat-client';
const REPO_URL = 'https://github.com/' + REPO;
const PANEL_SOURCE_URL = 'https://github.com/' + REPO + '/releases/latest/download/catclient.worker.js';
// Update sources in order: the release asset, then jsDelivr (usually reachable
// where github.com is filtered), then the raw file on the default branch.
const PANEL_SOURCE_URLS = [
  PANEL_SOURCE_URL,
  'https://cdn.jsdelivr.net/gh/' + REPO + '@latest/app/src/main/assets/panels/catclient.worker.js',
  'https://raw.githubusercontent.com/' + REPO + '/main/app/src/main/assets/panels/catclient.worker.js',
];
async function fetchNewestPanelSource() {
  for (const u of PANEL_SOURCE_URLS) {
    try {
      const r = await fetch(u, { headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION }, cf: { cacheTtl: 300 } });
      if (r.ok) return r;
    } catch (e) { /* try the next mirror */ }
  }
  return null;
}

/* ------------------------------------------------------------------ */
/* small utils                                                         */
/* ------------------------------------------------------------------ */

function splitCsv(value) {
  return String(value || '')
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function uniq(list) {
  const seen = new Set();
  const out = [];
  for (const item of list || []) {
    const key = String(item).trim();
    if (!key || seen.has(key.toLowerCase())) continue;
    seen.add(key.toLowerCase());
    out.push(key);
  }
  return out;
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(value || ''));
}

function bytesToHex(bytes) {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function hexToUuid(hex) {
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20, 32);
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(text)));
  return bytesToHex(new Uint8Array(digest));
}

async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(String(secret)), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(String(message)));
  return bytesToHex(new Uint8Array(sig));
}

function b64encode(text) {
  const bytes = new TextEncoder().encode(String(text));
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function b64decode(text) {
  const bin = atob(String(text).replace(/-/g, '+').replace(/_/g, '/').replace(/\s+/g, ''));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

function b64urlEncodeBytes(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function isTrue(value) {
  return /^(1|true|yes|on)$/i.test(String(value || '').trim());
}

function ipToLong(ip) {
  const parts = String(ip).split('.').map((p) => Number(p));
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return null;
  return ((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3];
}

function isIpv4(value) {
  return ipToLong(value) !== null;
}

function isIpv6(value) {
  const s = String(value || '').replace(/^\[/, '').replace(/\]$/, '');
  return s.includes(':') && /^[0-9a-f:.]+$/i.test(s);
}

function formatAddr(addr) {
  const s = String(addr).trim();
  return isIpv6(s) && !s.startsWith('[') ? '[' + s + ']' : s;
}

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: Object.assign({
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
    }, extra),
  });
}

function text(body, status = 200, extra = {}) {
  return new Response(body, {
    status,
    headers: Object.assign({ 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' }, extra),
  });
}

function html(body, status = 200, extra = {}) {
  return new Response(body, {
    status,
    headers: Object.assign({ 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' }, extra),
  });
}

/** socks5://[user:pass@]host:port | http://[user:pass@]host:port → parts or null. */
function parseChain(value) {
  const m = String(value || '').trim().match(/^(socks5h?|socks|https?):\/\/(?:([^:@/]*)(?::([^@/]*))?@)?(\[[^\]]+\]|[^:/\s]+):(\d{1,5})\/?$/i);
  if (!m) return null;
  const port = Number(m[5]);
  if (!(port > 0 && port < 65536)) return null;
  return {
    type: /^socks/i.test(m[1]) ? 'socks5' : 'http',
    user: m[2] ? decodeURIComponent(m[2]) : '',
    pass: m[3] ? decodeURIComponent(m[3]) : '',
    host: m[4].replace(/^\[|\]$/g, ''),
    port,
  };
}

/* ------------------------------------------------------------------ */
/* identity                                                            */
/* ------------------------------------------------------------------ */

async function deriveUuid(host) {
  try {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('cat-panel:uuid:' + host));
    const b = new Uint8Array(digest);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    return hexToUuid(bytesToHex(b));
  } catch (e) {
    return crypto.randomUUID();
  }
}

async function resolveUuid(host, env) {
  const explicit = String(env.UUID || '').trim().toLowerCase();
  return isUuid(explicit) ? explicit : deriveUuid(host);
}

/* ------------------------------------------------------------------ */
/* KV: ONE settings record + ONE users record, cached per isolate.     */
/* Writes happen only on owner actions.                                */
/* ------------------------------------------------------------------ */

const KV_KEYS = { settings: 'cat:v6:settings', users: 'cat:v6:users' };
const KV_READ_TTL_MS = 60 * 1000;
const kvCache = new Map(); // key → { value, at }

function kvBinding(env) {
  if (env && env.CAT_KV && typeof env.CAT_KV.get === 'function') return env.CAT_KV;
  for (const name of Object.keys(env || {})) {
    const candidate = env[name];
    if (candidate && typeof candidate.get === 'function' && typeof candidate.put === 'function' && typeof candidate.list === 'function') return candidate;
  }
  return null;
}

function kvCacheClear() {
  kvCache.clear();
}

async function kvGetJson(env, key, fallback) {
  const hit = kvCache.get(key);
  if (hit && Date.now() - hit.at < KV_READ_TTL_MS) return hit.value;
  const store = kvBinding(env);
  if (!store) return fallback;
  try {
    const raw = await store.get(key);
    const value = raw ? JSON.parse(raw) : fallback;
    kvCache.set(key, { value, at: Date.now() });
    return value;
  } catch (e) {
    return hit ? hit.value : fallback;
  }
}

async function kvPutJson(env, key, value) {
  const store = kvBinding(env);
  kvCache.set(key, { value, at: Date.now() });
  if (!store) return false;
  try {
    await store.put(key, JSON.stringify(value));
    return true;
  } catch (e) {
    return false;
  }
}

const TLS_PORTS = [443, 2053, 2083, 2087, 2096, 8443];
// Default fronting SNI for generated configs. NEVER the panel host: the domain in
// the SNI field of every TLS handshake is what DPI sees — using the panel's own
// address there is what gets panels blocked. Override in Settings → SNI/Host or env.SNI.
const DEFAULT_FRONTING_SNI = 'skk.moe';
const PLAIN_PORTS = [80, 8080, 8880, 2052, 2082, 2086, 2095];

/**
 * Cloudflare-fronted hostnames that usually answer from Iran. Every one of
 * them MUST resolve to Cloudflare anycast, otherwise the config is dead.
 */
const DEFAULT_CLEAN_ADDRESSES = [
  'www.speedtest.net', 'www.visa.com', 'cdnjs.cloudflare.com', 'speed.cloudflare.com',
  'www.shopify.com', 'icook.tw', 'www.wto.org', 'ip.sb',
  '104.16.132.229', '172.67.181.32', '188.114.96.1', '162.159.192.1', '104.17.148.22', '172.64.80.1',
];

const DEFAULT_PROXY_IPS = ['proxyip.cmliussss.net', 'di.nscl.ir', 'tr.diam4.ggff.net'];

function defaultSettings() {
  return {
    title: '',
    passwordHash: '',   // sha256(password); empty → PANEL_PASSWORD env or UUID
    lang: 'fa',
    ips: [],            // owner's clean IPs / domains (first in every subscription)
    useDefaults: true,  // append DEFAULT_CLEAN_ADDRESSES after the owner's list
    tlsPorts: TLS_PORTS.slice(0, 3),
    // BPB signature order: 443 → 8080. (Previously 80 came before 8080 and the
    // entry limit ran out before any :8080 config was emitted.)
    plainPorts: [8080, 80],
    plainEnabled: true,
    protocols: { vless: true, trojan: true },
    sni: '',
    fingerprint: 'chrome',
    proxyIps: [],
    extraSnis: [],       // Spoof section: per-SNI configs (🧬) — each host must sit on Cloudflare
    ipCountries: {},     // addr → ISO-2 (where this entry address lands for YOU)
    ipSources: {},       // addr → {src,ms,at} provenance: scanner origin + latency from the sender's network (worker tests add their own status)
    proxyCountries: {},  // proxy ip → ISO-2 (exit for Cloudflare-hosted sites)
    country: '',         // preferred exit country ('' = automatic)
    bypassIran: true,    // Iranian sites/apps go DIRECT (looks like no VPN to them)
    tgToken: '',         // Telegram bot token (or TG_BOT_TOKEN env)
    tgAdmins: [],        // Telegram user ids allowed to drive the bot (or TG_ADMIN_ID env)
    ghPat: '',           // GitHub fine-grained token (Actions read/write) — drives the deploy workflow
    ghRepo: '',          // owner/repo the workflow lives in
    ghRef: '',           // branch to deploy ('' = main)
    ghWorkflow: 'deploy-worker.yml',
    blockAds: false,     // ad networks → REJECT (geosite category-ads-all)
    blockQuic: false,    // UDP 443 (QUIC/HTTP3) → REJECT, pushes apps to TCP+TLS we can carry
    fragment: { enabled: false, packets: 'tlshello', length: '10-100', interval: '10-20' }, // opt-in; Xray + sing-box only
    alpn: 'http/1.1',    // WS over Cloudflare needs http/1.1; h2 would break the upgrade
    cipherSuites: '',    // Xray tlsSettings.cipherSuites (colon separated), '' = default
    panelPath: '',       // stealth: panel UI lives at /<panelPath>; root answers a neutral 404 ('' = legacy open panel)
    countryFallback: 'auto', // 'auto' = fastest other country when preferred is dead, 'none' = never leave it
    chain: '',          // socks5://user:pass@host:port or http://host:port — fixed egress
    chainMode: 'all',   // 'all' = every connection via chain (stable IP/country), 'cf' = only Cloudflare-hosted targets
    chainStrict: false, // true = never fall back to direct when the chain is down
    entryLimit: 48,
    includeHost: true,  // also emit the worker hostname itself as an address
    installedAt: 0,     // first-save timestamp → "panel uptime" on the Overview
    updatedAt: 0,
  };
}

function normalizeSettings(raw) {
  const d = defaultSettings();
  const s = Object.assign({}, d, raw && typeof raw === 'object' ? raw : {});
  s.title = String(s.title || '').slice(0, 60);
  s.passwordHash = String(s.passwordHash || '');
  s.lang = s.lang === 'en' ? 'en' : 'fa';
  s.ips = uniq(Array.isArray(s.ips) ? s.ips : splitCsv(s.ips)).slice(0, 400);
  s.repoAuto = s.repoAuto === true;
  s.repos = sanitizeRepos(s.repos);
  s.proxyRepoAuto = s.proxyRepoAuto !== false; // default ON — repo ProxyIPs become separate 🎯 configs
  s.proxyRepos = sanitizeProxyRepos(s.proxyRepos);
  s.subRotate = ['off', 'fetch', 'daily'].includes(s.subRotate) ? s.subRotate : 'fetch';
  s.pinnedIps = uniq((Array.isArray(s.pinnedIps) ? s.pinnedIps : splitCsv(s.pinnedIps)).map((x) => { const t = splitAddrTag(x); let a = t.addr; const pin = pinnedPortOf(a); if (pin) a = a.slice(0, a.lastIndexOf(':')); return a.replace(/^\[/, '').replace(/\]$/, ''); })).slice(0, 5);
  const wRaw = (s.warp && typeof s.warp === 'object') ? s.warp : {};
  s.warp = {
    mode: ['off', 'on', 'chain'].includes(wRaw.mode) ? wRaw.mode : 'off',
    secretKey: String(wRaw.secretKey || '').trim().slice(0, 64),
    publicKey: String(wRaw.publicKey || '').trim().slice(0, 64),
    reserved: (() => { const parts = String(wRaw.reserved || '').split(',').map((x) => parseInt(x, 10)).filter((n) => Number.isInteger(n) && n >= 0 && n <= 255).slice(0, 3); return parts.length === 3 ? parts.join(',') : ''; })(),
    endpoint: String(wRaw.endpoint || '').trim().slice(0, 120) || 'engage.cloudflareclient.com:2408',
  };
  s.extSubs = (Array.isArray(s.extSubs) ? s.extSubs : [])
    .filter((x) => x && typeof x === 'object' && /^https:\/\/[^\s"'<>]+$/.test(String(x.url || '')))
    .slice(0, 5)
    // Links copied from Telegram/HTML arrive with &amp; — sanitize at save time
    .map((x, i) => ({ name: String(x.name || 'ext' + (i + 1)).slice(0, 40), url: String(x.url).trim().replace(/&amp;/g, '&') }));
  s.useDefaults = s.useDefaults !== false;
  s.tlsPorts = uniq((Array.isArray(s.tlsPorts) ? s.tlsPorts : splitCsv(s.tlsPorts)).map(Number).filter((p) => p >= 1 && p <= 65535));
  if (!s.tlsPorts.length) s.tlsPorts = [443];
  s.plainPorts = uniq((Array.isArray(s.plainPorts) ? s.plainPorts : splitCsv(s.plainPorts)).map(Number).filter((p) => p >= 1 && p <= 65535));
  if (!s.plainPorts.length) s.plainPorts = [80];
  s.plainEnabled = s.plainEnabled !== false;
  s.protocols = { vless: !(s.protocols && s.protocols.vless === false), trojan: !(s.protocols && s.protocols.trojan === false) };
  if (!s.protocols.vless && !s.protocols.trojan) s.protocols.vless = true;
  s.sni = String(s.sni || '').trim().toLowerCase().slice(0, 253);
  s.fingerprint = ['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', 'random', 'randomized', 'unsafe'].includes(s.fingerprint) ? s.fingerprint : 'chrome';
  s.proxyIps = uniq(Array.isArray(s.proxyIps) ? s.proxyIps : splitCsv(s.proxyIps)).slice(0, 32);
  s.extraSnis = uniq(Array.isArray(s.extraSnis) ? s.extraSnis : splitCsv(s.extraSnis))
    .map((v) => String(v).trim().toLowerCase().replace(/^https?:\/\//, '').split('/')[0])
    .filter((v) => /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(v))
    .slice(0, 8);
  s.ipCountries = normalizeCountryMap(s.ipCountries, 500);
  s.ipSources = (s.ipSources && typeof s.ipSources === 'object' && !Array.isArray(s.ipSources)) ? s.ipSources : {};
  s.proxyCountries = normalizeCountryMap(s.proxyCountries, 64);
  s.country = normalizeCountry(s.country) || '';
  s.countryFallback = s.countryFallback === 'none' ? 'none' : 'auto';
  s.bypassIran = s.bypassIran !== false;
  s.tgToken = /^\d{6,}:[A-Za-z0-9_-]{20,}$/.test(String(s.tgToken || '').trim()) ? String(s.tgToken).trim() : '';
  s.tgAdmins = uniq((Array.isArray(s.tgAdmins) ? s.tgAdmins : splitCsv(s.tgAdmins)).map((v) => String(v).trim()).filter((v) => /^-?\d{1,20}$/.test(v))).slice(0, 10);
  s.ghPat = /^(?:ghp|github_pat)_[A-Za-z0-9_]{20,255}$/.test(String(s.ghPat || '').trim()) ? String(s.ghPat).trim() : '';
  s.ghRepo = /^[\w.-]+\/[\w.-]{1,100}$/.test(String(s.ghRepo || '').trim()) ? String(s.ghRepo).trim() : '';
  s.ghRef = /^[A-Za-z0-9._/-]{1,120}$/.test(String(s.ghRef || '').trim()) ? String(s.ghRef).trim() : '';
  s.ghWorkflow = /^[\w.-]+\.ya?ml$/.test(String(s.ghWorkflow || '').trim()) ? String(s.ghWorkflow).trim() : 'deploy-worker.yml';
  s.blockAds = s.blockAds === true;
  s.blockQuic = s.blockQuic === true;
  const fr = s.fragment && typeof s.fragment === 'object' ? s.fragment : {};
  const rng = (v, dflt) => (/^\d{1,5}(-\d{1,5})?$/.test(String(v || '').trim()) ? String(v).trim() : dflt);
  s.fragment = { enabled: fr.enabled === true, packets: ['tlshello', '1-1', '1-2', '1-3', '1-5'].includes(fr.packets) ? fr.packets : 'tlshello', length: rng(fr.length, '10-100'), interval: rng(fr.interval, '10-20') };
  s.alpn = ['http/1.1', 'h2,http/1.1', 'h2', 'h3,h2,http/1.1'].includes(s.alpn) ? s.alpn : 'http/1.1';
  s.cipherSuites = String(s.cipherSuites || '').replace(/[^A-Za-z0-9_:,]/g, '').slice(0, 2000);
  s.panelPath = /^[a-z0-9][a-z0-9-]{2,22}[a-z0-9]$/.test(String(s.panelPath || '').trim().toLowerCase()) ? String(s.panelPath).trim().toLowerCase() : '';
  s.chain = parseChain(s.chain) ? String(s.chain).trim() : '';
  s.chainMode = s.chainMode === 'cf' ? 'cf' : 'all';
  s.chainStrict = s.chainStrict === true;
  s.entryLimit = Math.min(200, Math.max(4, Number(s.entryLimit) || d.entryLimit));
  s.includeHost = s.includeHost !== false;
  s.installedAt = Number(s.installedAt) || 0;
  s.updatedAt = Number(s.updatedAt) || 0;
  return s;
}

async function readSettings(env) {
  return normalizeSettings(await kvGetJson(env, KV_KEYS.settings, null));
}

async function writeSettings(env, patch) {
  const current = await readSettings(env);
  const next = normalizeSettings(Object.assign({}, current, patch || {}, { updatedAt: Date.now() }));
  const persisted = await kvPutJson(env, KV_KEYS.settings, next);
  return { settings: next, persisted };
}

function normalizeUser(raw) {
  const u = raw && typeof raw === 'object' ? raw : {};
  const id = isUuid(u.id) ? String(u.id).toLowerCase() : (isUuid(u.uuid) ? String(u.uuid).toLowerCase() : crypto.randomUUID());
  return {
    id,
    name: String(u.name || '').trim().slice(0, 40) || ('cat-' + id.slice(0, 6)),
    enabled: u.enabled !== false,
    createdAt: Number(u.createdAt) || Date.now(),
    expiresAt: Number(u.expiresAt) || 0,  // 0 → never
    note: String(u.note || '').slice(0, 200),
    protocols: { vless: !(u.protocols && u.protocols.vless === false), trojan: !(u.protocols && u.protocols.trojan === false) },
  };
}

async function readUsers(env) {
  const list = await kvGetJson(env, KV_KEYS.users, []);
  return (Array.isArray(list) ? list : []).map(normalizeUser);
}

async function writeUsers(env, users) {
  const list = (users || []).map(normalizeUser).slice(0, 500);
  const persisted = await kvPutJson(env, KV_KEYS.users, list);
  return { users: list, persisted };
}

function userBlockedReason(user) {
  if (!user) return 'unknown';
  if (!user.enabled) return 'disabled';
  if (user.expiresAt && Date.now() > user.expiresAt) return 'expired';
  return null;
}

function findUser(users, id) {
  const key = String(id || '').toLowerCase();
  return users.find((u) => u.id === key) || null;
}

/* ------------------------------------------------------------------ */
/* panel auth                                                          */
/* ------------------------------------------------------------------ */

async function panelPassword(env, settings, masterUuid) {
  // Precedence: owner-set password (hash) > PANEL_PASSWORD env > UUID.
  if (settings.passwordHash) return { hash: settings.passwordHash, source: 'panel' };
  const fromEnv = String(env.PANEL_PASSWORD || '').trim();
  if (fromEnv) return { hash: await sha256Hex(fromEnv), source: 'env' };
  return { hash: await sha256Hex(masterUuid), source: 'uuid' };
}

function panelIsOpen(env, settings) {
  return isTrue(env.OPEN_PANEL) && !settings.passwordHash && !String(env.PANEL_PASSWORD || '').trim();
}

const SESSION_COOKIE = 'cat_session';
const SESSION_TTL_MS = 7 * 24 * 3600 * 1000;

async function sessionSecret(env, settings, masterUuid) {
  const pass = await panelPassword(env, settings, masterUuid);
  return 'cat:session:' + pass.hash + ':' + masterUuid;
}

async function makeSession(env, settings, masterUuid) {
  const exp = Date.now() + SESSION_TTL_MS;
  const sig = await hmacHex(await sessionSecret(env, settings, masterUuid), String(exp));
  return exp + '.' + sig;
}

function readCookie(request, name) {
  const header = request.headers.get('cookie') || '';
  for (const part of header.split(';')) {
    const [k, ...rest] = part.trim().split('=');
    if (k === name) return decodeURIComponent(rest.join('='));
  }
  return '';
}

/** Constant-time hex compare — session/password checks must not leak via timing. */
function safeEqualHex(a, b) {
  const A = String(a || ''); const B = String(b || '');
  if (A.length !== B.length) return false;
  let diff = 0;
  for (let i = 0; i < A.length; i++) diff |= A.charCodeAt(i) ^ B.charCodeAt(i);
  return diff === 0;
}

async function verifySession(request, env, settings, masterUuid) {
  const token = readCookie(request, SESSION_COOKIE);
  if (!token) return false;
  const [exp, sig] = token.split('.');
  if (!exp || !sig || Number(exp) < Date.now()) return false;
  const expected = await hmacHex(await sessionSecret(env, settings, masterUuid), String(exp));
  return safeEqualHex(expected, sig);
}

/** Owner check: session cookie, Bearer password, or x-cat-key header. */
async function isOwner(request, env, settings, masterUuid) {
  if (panelIsOpen(env, settings)) return true;
  if (await verifySession(request, env, settings, masterUuid)) return true;
  const auth = request.headers.get('authorization') || '';
  const key = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : (request.headers.get('x-cat-key') || '').trim();
  if (!key) return false;
  const pass = await panelPassword(env, settings, masterUuid);
  return safeEqualHex(await sha256Hex(key), pass.hash);
}

async function checkLogin(env, settings, masterUuid, username, password) {
  const wantUser = String(env.PANEL_USER || '').trim();
  if (wantUser && String(username || '').trim() !== wantUser) return false;
  const pass = await panelPassword(env, settings, masterUuid);
  return safeEqualHex(await sha256Hex(String(password || '')), pass.hash);
}

function sessionCookieHeader(token) {
  return SESSION_COOKIE + '=' + encodeURIComponent(token) + '; Path=/; Max-Age=' + Math.floor(SESSION_TTL_MS / 1000) + '; HttpOnly; Secure; SameSite=Lax';
}

/* QR encoder (byte mode, versions 1-40, levels L/M/Q/H)               */
/* Verified against the reference implementation and decoded by jsQR.  */
/* ------------------------------------------------------------------ */

const QR_BLOCKS = [1,1,1,1,1,1,1,1,1,1,2,2,1,2,2,4,1,2,4,4,2,4,4,4,2,4,6,5,2,4,6,6,2,5,8,8,4,5,8,8,4,5,8,11,4,8,10,11,4,9,12,16,4,9,16,16,6,10,12,18,6,10,17,16,6,11,16,19,6,13,18,21,7,14,21,25,8,16,20,25,8,17,23,25,9,17,23,34,9,18,25,30,10,20,27,32,12,21,29,35,12,23,34,37,12,25,34,40,13,26,35,42,14,28,38,45,15,29,40,48,16,31,43,51,17,33,45,54,18,35,48,57,19,37,51,60,19,38,53,63,20,40,56,66,21,43,59,70,22,45,62,74,24,47,65,77,25,49,68,81];
const QR_ECC = [7,10,13,17,10,16,22,28,15,26,18,22,20,18,26,16,26,24,18,22,18,16,24,28,20,18,18,26,24,22,22,26,30,22,20,24,18,26,24,28,20,30,28,24,24,22,26,28,26,22,24,22,30,24,20,24,22,24,30,24,24,28,24,30,28,28,28,28,30,26,28,28,28,26,26,26,28,26,30,28,28,26,28,30,28,28,30,24,30,28,30,30,30,28,30,30,26,28,30,30,28,28,28,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30,30,28,30,30];

const QR_ECL_BITS = { L: 1, M: 0, Q: 3, H: 2 };

function qrSymbolSize(version) {
  return version * 4 + 17;
}

function qrRawDataModules(version) {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const numAlign = Math.floor(version / 7) + 2;
    result -= (25 * numAlign - 10) * numAlign - 55;
    if (version >= 7) result -= 36;
  }
  return result;
}

function qrTotalCodewords(version) {
  return Math.floor(qrRawDataModules(version) / 8);
}

function qrEcInfo(version, ecl) {
  const i = (version - 1) * 4 + ['L', 'M', 'Q', 'H'].indexOf(ecl);
  return { blocks: QR_BLOCKS[i], ecc: QR_ECC[i] };
}

function qrDataCodewords(version, ecl) {
  const info = qrEcInfo(version, ecl);
  return qrTotalCodewords(version) - info.blocks * info.ecc;
}

function qrPickVersion(byteLen, ecl) {
  for (let v = 1; v <= 40; v++) {
    const bits = qrDataCodewords(v, ecl) * 8;
    if (4 + (v < 10 ? 8 : 16) + byteLen * 8 <= bits) return v;
  }
  return null;
}

function gfMul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = ((z << 1) ^ ((z >>> 7) * 0x11d)) & 0xff;
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function rsDivisor(degree) {
  const result = new Array(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = gfMul(result[j], root);
      if (j + 1 < result.length) result[j] ^= result[j + 1];
    }
    root = gfMul(root, 0x02);
  }
  return result;
}

function rsRemainder(data, divisor) {
  const result = new Array(divisor.length).fill(0);
  for (const b of data) {
    const factor = b ^ result.shift();
    result.push(0);
    for (let i = 0; i < result.length; i++) result[i] ^= gfMul(divisor[i], factor);
  }
  return result;
}

function qrAppendBits(buffer, value, len) {
  for (let i = len - 1; i >= 0; i--) buffer.push((value >>> i) & 1);
}

function qrAlignmentPositions(version) {
  if (version === 1) return [];
  const numAlign = Math.floor(version / 7) + 2;
  const step = version === 32 ? 26 : Math.ceil((version * 4 + 4) / (numAlign * 2 - 2)) * 2;
  const positions = [6];
  for (let pos = qrSymbolSize(version) - 7; positions.length < numAlign; pos -= step) {
    positions.splice(1, 0, pos);
  }
  return positions;
}

function qrBuildMatrix(version, ecl, codewords, mask) {
  const size = qrSymbolSize(version);
  const modules = Array.from({ length: size }, () => new Array(size).fill(false));
  const isFunction = Array.from({ length: size }, () => new Array(size).fill(false));

  const setFunctionModule = (x, y, isDark) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    modules[y][x] = isDark;
    isFunction[y][x] = true;
  };

  for (let i = 0; i < size; i++) {
    setFunctionModule(6, i, i % 2 === 0);
    setFunctionModule(i, 6, i % 2 === 0);
  }

  const drawFinder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        setFunctionModule(cx + dx, cy + dy, dist !== 2 && dist !== 4);
      }
    }
  };
  drawFinder(3, 3);
  drawFinder(size - 4, 3);
  drawFinder(3, size - 4);

  const alignPositions = qrAlignmentPositions(version);
  for (const ax of alignPositions) {
    for (const ay of alignPositions) {
      if ((ax === 6 && ay === 6) || (ax === 6 && ay === size - 7) || (ax === size - 7 && ay === 6)) continue;
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          setFunctionModule(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
  }

  const data = (QR_ECL_BITS[ecl] << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  const formatBits = ((data << 10) | rem) ^ 0x5412;
  for (let i = 0; i <= 5; i++) setFunctionModule(8, i, ((formatBits >>> i) & 1) !== 0);
  setFunctionModule(8, 7, ((formatBits >>> 6) & 1) !== 0);
  setFunctionModule(8, 8, ((formatBits >>> 7) & 1) !== 0);
  setFunctionModule(7, 8, ((formatBits >>> 8) & 1) !== 0);
  for (let i = 9; i < 15; i++) setFunctionModule(14 - i, 8, ((formatBits >>> i) & 1) !== 0);
  for (let i = 0; i < 8; i++) setFunctionModule(size - 1 - i, 8, ((formatBits >>> i) & 1) !== 0);
  for (let i = 8; i < 15; i++) setFunctionModule(8, size - 15 + i, ((formatBits >>> i) & 1) !== 0);
  setFunctionModule(8, size - 8, true);

  if (version >= 7) {
    let vrem = version;
    for (let i = 0; i < 12; i++) vrem = (vrem << 1) ^ ((vrem >>> 11) * 0x1f25);
    const bits = (version << 12) | vrem;
    for (let i = 0; i < 18; i++) {
      const bit = ((bits >>> i) & 1) !== 0;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      setFunctionModule(a, b, bit);
      setFunctionModule(b, a, bit);
    }
  }

  let bitIndex = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - vert : vert;
        if (isFunction[y][x] || bitIndex >= codewords.length * 8) continue;
        modules[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) !== 0;
        bitIndex++;
      }
    }
  }

  const maskFn = [
    (x, y) => (x + y) % 2 === 0,
    (x, y) => y % 2 === 0,
    (x, y) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
    (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
    (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ][mask];
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (!isFunction[y][x] && maskFn(x, y)) modules[y][x] = !modules[y][x];
    }
  }
  return { modules, isFunction };
}

function qrPenalty(modules) {
  const size = modules.length;
  const at = (x, y) => (modules[y][x] ? 1 : 0);
  let result = 0;

  for (let a = 0; a < size; a++) {
    for (const vertical of [false, true]) {
      let runColor = vertical ? at(0, a) : at(a, 0);
      let runLen = 1;
      for (let b = 1; b < size; b++) {
        const c = vertical ? at(b, a) : at(a, b);
        if (c === runColor) {
          runLen++;
          if (runLen === 5) result += 3;
          else if (runLen > 5) result += 1;
        } else {
          runColor = c;
          runLen = 1;
        }
      }
    }
  }

  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const c = modules[y][x];
      if (c === modules[y][x + 1] && c === modules[y + 1][x] && c === modules[y + 1][x + 1]) result += 3;
    }
  }

  const P1 = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
  const P2 = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
  const windowMatches = (get, a, start, pattern) => {
    for (let k = 0; k < 11; k++) if (get(a, start + k) !== pattern[k]) return false;
    return true;
  };
  for (let a = 0; a < size; a++) {
    for (let b = 0; b + 11 <= size; b++) {
      if (windowMatches(at, a, b, P1)) result += 40;
      if (windowMatches(at, a, b, P2)) result += 40;
      if (windowMatches((x, y) => at(y, x), a, b, P1)) result += 40;
      if (windowMatches((x, y) => at(y, x), a, b, P2)) result += 40;
    }
  }

  let darkCount = 0;
  for (const row of modules) for (const c of row) if (c) darkCount++;
  const total = size * size;
  result += (Math.ceil(Math.abs(darkCount * 20 - total * 10) / total) - 1) * 10;
  return result;
}

function qrEncode(text, ecl = 'M') {
  const level = QR_ECL_BITS[ecl] === undefined ? 'M' : ecl;
  const bytes = Array.from(new TextEncoder().encode(String(text)));
  const version = qrPickVersion(bytes.length, level);
  if (!version) throw new Error('QR payload too long');
  const capacity = qrDataCodewords(version, level);
  const buffer = [];
  qrAppendBits(buffer, 0b0100, 4);
  qrAppendBits(buffer, bytes.length, version < 10 ? 8 : 16);
  for (const b of bytes) qrAppendBits(buffer, b, 8);
  const capacityBits = capacity * 8;
  qrAppendBits(buffer, 0, Math.min(4, capacityBits - buffer.length));
  qrAppendBits(buffer, 0, (8 - (buffer.length % 8)) % 8);
  for (let pad = 0xec; buffer.length < capacityBits; pad ^= 0xec ^ 0x11) {
    qrAppendBits(buffer, pad, 8);
  }
  const dataBytes = [];
  for (let k = 0; k < buffer.length; k += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j++) byte = (byte << 1) | buffer[k + j];
    dataBytes.push(byte);
  }

  const info = qrEcInfo(version, level);
  const rawCodewords = qrTotalCodewords(version);
  const numShortBlocks = info.blocks - (rawCodewords % info.blocks);
  const shortBlockLen = Math.floor(rawCodewords / info.blocks);
  const shortBlockDataLen = shortBlockLen - info.ecc;
  const divisor = rsDivisor(info.ecc);
  const dataBlocks = [];
  const eccBlocks = [];
  for (let b = 0, k = 0; b < info.blocks; b++) {
    const datLen = shortBlockDataLen + (b < numShortBlocks ? 0 : 1);
    const dat = dataBytes.slice(k, k + datLen);
    k += datLen;
    dataBlocks.push(dat);
    eccBlocks.push(rsRemainder(dat, divisor));
  }
  const codewords = [];
  const maxDataLen = shortBlockDataLen + (numShortBlocks < info.blocks ? 1 : 0);
  for (let i = 0; i < maxDataLen; i++) {
    for (let j = 0; j < info.blocks; j++) {
      if (i < dataBlocks[j].length) codewords.push(dataBlocks[j][i]);
    }
  }
  for (let i = 0; i < info.ecc; i++) {
    for (let j = 0; j < info.blocks; j++) codewords.push(eccBlocks[j][i]);
  }

  let best = null;
  for (let mask = 0; mask < 8; mask++) {
    const built = qrBuildMatrix(version, level, codewords, mask);
    const score = qrPenalty(built.modules);
    if (!best || score < best.score) {
      best = { score: score, mask: mask, modules: built.modules };
    }
  }
  return { version: version, ecl: level, mask: best.mask, size: qrSymbolSize(version), modules: best.modules };
}

/** SVG QR code — dark modules in `dark`, background in `light`. */
function qrSvg(text, options = {}) {
  const ecl = options.ecl || 'M';
  const moduleSize = options.moduleSize || 4;
  const quietZone = options.quietZone === undefined ? 3 : options.quietZone;
  const dark = options.dark || '#12061f';
  const light = options.light || '#ffffff';
  const qr = qrEncode(text, ecl);
  const dim = (qr.size + quietZone * 2) * moduleSize;
  let path = '';
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.modules[y][x]) {
        path += 'M' + (x + quietZone) * moduleSize + ' ' + (y + quietZone) * moduleSize +
          'h' + moduleSize + 'v' + moduleSize + 'h-' + moduleSize + 'z';
      }
    }
  }
  return '<svg xmlns="http://www.w3.org/2000/svg" width="' + dim + '" height="' + dim +
    '" viewBox="0 0 ' + dim + ' ' + dim + '" shape-rendering="crispEdges">' +
    '<rect width="' + dim + '" height="' + dim + '" fill="' + light + '"/>' +
    '<path d="' + path + '" fill="' + dark + '"/></svg>';
}

/* ------------------------------------------------------------------ */
/* VLESS wire format                                                   */
/* ------------------------------------------------------------------ */

/** Sec-WebSocket-Protocol carries base64url early data (Xray `?ed=2048`). */
function decodeEarlyData(header) {
  const value = String(header || '').trim();
  if (!value) return null;
  try {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
    const bin = atob(padded);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  } catch (e) {
    return null;
  }
}

function toBytes(data) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (data && data.buffer instanceof ArrayBuffer) return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
  return new TextEncoder().encode(String(data || ''));
}

function concatBytes(a, b) {
  if (!a || !a.byteLength) return b;
  if (!b || !b.byteLength) return a;
  const out = new Uint8Array(a.byteLength + b.byteLength);
  out.set(a, 0);
  out.set(b, a.byteLength);
  return out;
}

/** Client WebSocket → ReadableStream of Uint8Array (early data first). */
function websocketReadable(ws, earlyData) {
  let cancelled = false;
  return new ReadableStream({
    start(controller) {
      if (earlyData && earlyData.byteLength) controller.enqueue(earlyData);
      ws.addEventListener('message', (event) => {
        if (cancelled) return;
        controller.enqueue(toBytes(event.data));
      });
      ws.addEventListener('close', (event) => {
        // Complete the close handshake: the peer sent Close, we must answer with
        // our own or the runtime keeps the request open ("Worker's code had hung").
        try { ws.close(1000, 'client closed'); } catch (e) { /* already closed */ }
        if (cancelled) return;
        cancelled = true;
        try { controller.close(); } catch (e) { /* already closed */ }
      });
      ws.addEventListener('error', (err) => {
        if (cancelled) return;
        cancelled = true;
        try { controller.error(err); } catch (e) { /* already closed */ }
      });
    },
    cancel() {
      cancelled = true;
      try { ws.close(1000); } catch (e) { /* ignore */ }
    },
  });
}

function safeCloseWs(ws, code, reason) {
  try {
    // 0 CONNECTING, 1 OPEN, 2 CLOSING (peer sent Close, ours still owed).
    if (ws.readyState === WS_OPEN || ws.readyState === 0 || ws.readyState === 2) ws.close(code || 1000, reason || '');
  } catch (e) { /* ignore */ }
}

/**
 * SOCKS5-style address block: ATYP + ADDR [+ PORT]. Trojan includes the port,
 * VLESS carries the port before the address.
 */
function parseSocksAddress(bytes, offset, withPort = true) {
  const atyp = bytes[offset];
  let cursor = offset + 1;
  let host = '';
  const suffix = withPort ? 2 : 0;
  if (atyp === 1) {
    if (bytes.length < cursor + 4 + suffix) return null;
    host = bytes[cursor] + '.' + bytes[cursor + 1] + '.' + bytes[cursor + 2] + '.' + bytes[cursor + 3];
    cursor += 4;
  } else if (atyp === 3) {
    if (bytes.length < cursor + 1) return null;
    const len = bytes[cursor];
    cursor += 1;
    if (bytes.length < cursor + len + suffix) return null;
    host = new TextDecoder().decode(bytes.subarray(cursor, cursor + len));
    cursor += len;
  } else if (atyp === 4) {
    if (bytes.length < cursor + 16 + suffix) return null;
    const groups = [];
    for (let i = 0; i < 16; i += 2) groups.push(((bytes[cursor + i] << 8) | bytes[cursor + i + 1]).toString(16));
    host = groups.join(':');
    cursor += 16;
  } else {
    return null;
  }
  let port = 0;
  if (withPort) {
    if (bytes.length < cursor + 2) return null;
    port = (bytes[cursor] << 8) | bytes[cursor + 1];
    cursor += 2;
  }
  return { atyp: atyp, host: host, port: port, rest: bytes.slice(cursor) };
}

/**
 * VLESS request header as specified by Xray:
 *   [version 1][uuid 16][addonLen 1][command 1][port 2][atyp 1][address][payload]
 * VLESS atyp: 1 = IPv4, 2 = domain, 3 = IPv6 (differs from SOCKS!).
 */
function parseVlessHeader(bytes) {
  if (!bytes || bytes.length < 24) return null;
  const version = bytes[0];
  const uuidBytes = bytes.subarray(1, 17);
  const hex = Array.from(uuidBytes).map((b) => b.toString(16).padStart(2, '0')).join('');
  const uuid = hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' + hex.slice(20);
  const addonLength = bytes[17];
  const command = bytes[18 + addonLength];
  const offset = 19 + addonLength;
  if (bytes.length < offset + 3) return null;
  const port = (bytes[offset] << 8) | bytes[offset + 1];
  const vlessAtyp = bytes[offset + 2];
  // Map VLESS atyp (1 v4, 2 domain, 3 v6) onto the SOCKS layout (1 v4, 3 domain, 4 v6).
  const socksAtyp = vlessAtyp === 1 ? 1 : vlessAtyp === 2 ? 3 : vlessAtyp === 3 ? 4 : 0;
  if (!socksAtyp) return null;
  const patched = bytes.slice(offset + 2);
  patched[0] = socksAtyp;
  const address = parseSocksAddress(patched, 0, false);
  if (!address) return null;
  return {
    version: version,
    uuid: uuid,
    command: command,
    host: address.host,
    port: port,
    isDomain: vlessAtyp === 2,
    rest: address.rest,
  };
}

function trojanPassword(bytes) {
  if (!bytes || bytes.length < 56) return null;
  const hex = new TextDecoder().decode(bytes.subarray(0, 56));
  if (!/^[0-9a-f]{56}$/i.test(hex)) return null;
  const after = bytes.subarray(56);
  const text = new TextDecoder().decode(after.subarray(0, 2));
  const rest = text === '\r\n' ? after.subarray(2) : after;
  return { password: hex.toLowerCase(), rest: rest };
}

/**
 * Trojan request: CMD(1) + ATYP(1) + ADDR + PORT(2) + CRLF + payload.
 * (The 56-byte password hash is stripped by `trojanPassword` before this.)
 */
function parseTrojanRequest(bytes) {
  if (!bytes || bytes.length < 7) return null;
  const command = bytes[0];
  if (command !== 1 && command !== 3 && command !== 0) return null;
  const address = parseSocksAddress(bytes, 1);
  if (!address) return null;
  const payload = address.rest.length >= 2 && address.rest[0] === 13 && address.rest[1] === 10
    ? address.rest.subarray(2)
    : address.rest;
  return { command: command, host: address.host, port: address.port, payload: payload };
}

/* SHA-224 in pure JS: the WebCrypto of both Workers and Node lack it. */
const SHA224_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

function sha224Hex(input) {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input;
  const h = [0xc1059ed8, 0x367cd507, 0x3070dd17, 0xf70e5939, 0xffc00b31, 0x68581511, 0x64f98fa7, 0xbefa4fa4];
  const bitLength = bytes.length * 8;
  const padded = new Uint8Array((((bytes.length + 9) >> 6) + 1) << 6);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 4, bitLength >>> 0, false);
  view.setUint32(padded.length - 8, Math.floor(bitLength / 0x100000000), false);
  const w = new Uint32Array(64);
  const rotr = (x, n) => ((x >>> n) | (x << (32 - n))) >>> 0;
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
      const s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
      const ch = ((e & f) ^ (~e & g)) >>> 0;
      const temp1 = (hh + S1 + ch + SHA224_K[i] + w[i]) >>> 0;
      const S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const temp2 = (S0 + maj) >>> 0;
      hh = g; g = f; f = e;
      e = (d + temp1) >>> 0;
      d = c; c = b; b = a;
      a = (temp1 + temp2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }
  return h.slice(0, 7).map((value) => value.toString(16).padStart(8, '0')).join('');
}

/** Hash used by Trojan clients: lowercase hex SHA-224 of the password. */
async function trojanHash(password) {
  return sha224Hex(String(password));
}

const CF_CIDR_RANGES = [
  '104.16.0.0/13', '104.24.0.0/14', '172.64.0.0/13', '162.158.0.0/15',
  '131.0.72.0/22', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22',
  '141.101.64.0/18', '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20',
  '197.234.240.0/22', '198.41.128.0/17', '173.245.48.0/20', '162.159.192.0/24',
  '162.159.0.0/16', '199.27.128.0/21',
];

function ipInCidr(ipLong, cidr) {
  const parts = String(cidr).split('/');
  const base = ipToLong(parts[0]);
  if (base === null) return false;
  const prefix = Number(parts[1]);
  if (!Number.isInteger(prefix) || prefix < 0 || prefix > 32) return false;
  const mask = prefix === 0 ? 0 : (0xFFFFFFFF << (32 - prefix)) >>> 0;
  return ((ipLong & mask) >>> 0) === ((base & mask) >>> 0);
}

function parseV6Hextets(addr) {
  let s = String(addr || '').trim().toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
  if (!s.includes(':')) return null;
  const dbl = s.split('::');
  if (dbl.length > 2) return null;
  const head = dbl[0] ? dbl[0].split(':') : [];
  const tail = dbl.length === 2 ? (dbl[1] ? dbl[1].split(':') : []) : [];
  if (head.length + tail.length > 8) return null;
  const mid = new Array(8 - head.length - tail.length).fill('0');
  return head.concat(mid, tail).map((h) => (h || '0'));
}


const CF_V6_PREFIXES = ['2400:cb00', '2405:b500', '2405:8100', '2606:4700', '2803:f800', '2a06:98c0', '2a06:98c1', '2a06:98c2', '2a06:98c3', '2a06:98c4', '2a06:98c5', '2a06:98c6', '2a06:98c7', '2c0f:f248'];

function isCloudflareIp(ip) {
  const value = ipToLong(ip);
  if (value !== null) return CF_CIDR_RANGES.some((range) => ipInCidr(value, range));
  const h = parseV6Hextets(ip);
  if (!h) return false;
  const head = (parseInt(h[0], 16).toString(16) + ':' + parseInt(h[1], 16).toString(16)).toLowerCase();
  return CF_V6_PREFIXES.includes(head);
}

/* ------------------------------------------------------------------ */
/* data plane                                                          */
/*   client WS --(VLESS/Trojan header)--> parse → connect() → pipe     */
/*   destinations behind Cloudflare (or refused) go via a proxy IP      */
/*   UDP is DNS-only (port 53) and is answered through DoH              */
/* ------------------------------------------------------------------ */

const WS_OPEN = 1;

let socketsModulePromise = null;
function __setSockets(mod) { socketsModulePromise = Promise.resolve(mod || null); }
function loadSockets() {
  if (!socketsModulePromise) {
    socketsModulePromise = (async () => {
      try {
        const mod = await import('cloudflare:sockets');
        return mod && typeof mod.connect === 'function' ? mod : null;
      } catch (e) {
        return null;
      }
    })();
  }
  return socketsModulePromise;
}

function dohUpstream(env) {
  const value = String(env.DNS_UPSTREAM || '').trim();
  return /^https:\/\//.test(value) ? value : 'https://cloudflare-dns.com/dns-query';
}

async function resolveDnsOverDoh(query, env) {
  const answer = await fetch(dohUpstream(env), {
    method: 'POST',
    headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' },
    body: query,
  });
  return new Uint8Array(await answer.arrayBuffer());
}

function splitHostPort(value, fallbackPort) {
  const raw = String(value || '').trim();
  const bracket = raw.match(/^\[([^\]]+)\](?::(\d+))?$/);
  if (bracket) return { hostname: bracket[1], port: Number(bracket[2] || fallbackPort) };
  const parts = raw.split(':');
  if (parts.length === 2 && /^\d+$/.test(parts[1])) return { hostname: parts[0], port: Number(parts[1]) };
  return { hostname: raw, port: fallbackPort };
}

function proxyIpList(env, settings) {
  const fromSettings = settings && Array.isArray(settings.proxyIps) ? settings.proxyIps : [];
  const fromEnv = splitCsv(env.PROXY_IPS || env.PROXYIP || env.PROXY_IP);
  const list = fromSettings.length ? fromSettings : fromEnv;
  const all = (list.length ? list : DEFAULT_PROXY_IPS).map((e) => String(e).trim()).filter(Boolean);
  // Preferred country first: Cloudflare-hosted destinations exit through the
  // proxy ip, so its country is what ip-check sites show for those sites.
  const pref = settings && settings.country;
  if (!pref) return all;
  const tags = (settings && settings.proxyCountries) || {};
  return all.filter((p) => tags[p] === pref).concat(all.filter((p) => tags[p] !== pref));
}

/* ---- countries ------------------------------------------------------- */
const PX_FA_NAMES = { DE:'آلمان', FR:'فرانسه', US:'آمریکا', GB:'بریتانیا', NL:'هلند', TR:'ترکیه', AE:'امارات', FI:'فنلاند', SE:'سوئد', PL:'لهستان', AT:'اتریش', CH:'سوئیس', IT:'ایتالیا', ES:'اسپانیا', CZ:'چک', RO:'رومانی', BG:'بلغارستان', HU:'مجارستان', CA:'کانادا', SG:'سنگاپور', JP:'ژاپن', HK:'هنگ‌کنگ', IN:'هند', KR:'کرهٔ جنوبی', IR:'ایران', BR:'برزیل', AU:'استرالیا', ZA:'آفریقای جنوبی', IL:'اسرائیل', RU:'روسیه', UA:'اوکراین', MY:'مالزی', ID:'اندونزی', TH:'تایلند', VN:'ویتنام', PH:'فیلیپین', KZ:'قزاقستان', AZ:'آذربایجان', AM:'ارمنستان', GE:'گرجستان', QA:'قطر', KW:'کویت', SA:'عربستان', IQ:'عراق', MX:'مکزیک', AR:'آرژانتین', EG:'مصر', NO:'نروژ', DK:'دانمارک', IE:'ایرلند' };
const COUNTRY_NAMES = { DE: 'Germany', NL: 'Netherlands', FR: 'France', GB: 'United Kingdom', US: 'United States', TR: 'Turkey', AE: 'UAE', FI: 'Finland', SE: 'Sweden', PL: 'Poland', AT: 'Austria', CH: 'Switzerland', IT: 'Italy', ES: 'Spain', CZ: 'Czechia', RO: 'Romania', BG: 'Bulgaria', HU: 'Hungary', UA: 'Ukraine', RU: 'Russia', AM: 'Armenia', GE: 'Georgia', KZ: 'Kazakhstan', IN: 'India', SG: 'Singapore', JP: 'Japan', KR: 'Korea', HK: 'Hong Kong', TW: 'Taiwan', AU: 'Australia', CA: 'Canada', BR: 'Brazil', IR: 'Iran', IQ: 'Iraq', OM: 'Oman', QA: 'Qatar', SA: 'Saudi Arabia', BH: 'Bahrain', KW: 'Kuwait', IE: 'Ireland', NO: 'Norway', DK: 'Denmark', BE: 'Belgium', PT: 'Portugal', GR: 'Greece', RS: 'Serbia', LT: 'Lithuania', LV: 'Latvia', EE: 'Estonia', MD: 'Moldova', CY: 'Cyprus', IL: 'Israel', EG: 'Egypt', ZA: 'South Africa', MY: 'Malaysia', TH: 'Thailand', VN: 'Vietnam', ID: 'Indonesia', PH: 'Philippines', MX: 'Mexico', AR: 'Argentina', CL: 'Chile', PK: 'Pakistan', AZ: 'Azerbaijan', UZ: 'Uzbekistan' };

function normalizeCountry(code) {
  const c = String(code || '').trim().toUpperCase();
  return /^[A-Z]{2}$/.test(c) && c !== 'XX' ? c : '';
}

function normalizeCountryMap(raw, max) {
  const out = {};
  if (!raw || typeof raw !== 'object') return out;
  let n = 0;
  for (const k of Object.keys(raw)) {
    const addr = String(k).trim().replace(/^\[|\]$/g, '');
    const cc = normalizeCountry(raw[k]);
    if (!addr || !cc || n >= max) continue;
    out[addr] = cc; n++;
  }
  return out;
}

function flagOf(cc) {
  cc = normalizeCountry(cc);
  if (!cc) return '';
  return String.fromCodePoint(0x1f1e6 + cc.charCodeAt(0) - 65, 0x1f1e6 + cc.charCodeAt(1) - 65);
}

function countryLabel(cc) {
  cc = normalizeCountry(cc);
  return cc ? flagOf(cc) + ' ' + (COUNTRY_NAMES[cc] || cc) : '🌐 Other';
}

/** "1.2.3.4#DE" / "1.2.3.4|DE" / "1.2.3.4=DE" → { addr, cc }. */
function splitAddrTag(raw) {
  const m = String(raw || '').trim().match(/^(.*?)[#|=]([A-Za-z]{2})$/);
  if (m) return { addr: m[1].trim(), cc: normalizeCountry(m[2]) };
  return { addr: String(raw || '').trim(), cc: '' };
}

/**
 * Address may pin the port its scan verified: `1.2.3.4:2053` or `[2001:db8::1]:8443`.
 * A pinned address is emitted ONLY on that port — no cross-product with the panel's
 * port list (scan result keeps its own verified entry point).
 */
function pinnedPortOf(addr) {
  const m = String(addr || '').trim().match(/^(?:\[[0-9a-f:.]+\]|[^\[\]:]+):(\d{1,5})$/i);
  const p = m ? Number(m[1]) : 0;
  return p >= 1 && p <= 65535 ? p : 0;
}

function countryOfAddr(addr, env, settings) {
  const a = String(addr).replace(/^\[|\]$/g, '');
  if (settings.ipCountries && settings.ipCountries[a]) return settings.ipCountries[a];
  for (const raw of splitCsv(env.CF_IPS)) { const t = splitAddrTag(raw); if (t.addr === a && t.cc) return t.cc; }
  return '';
}

/** Country summary used by the panel + /api/countries. */
function countrySummary(host, env, settings) {
  const by = {};
  for (const a of addressList(host, env, settings)) { const cc = countryOfAddr(a, env, settings) || '??'; (by[cc] = by[cc] || []).push(a); }
  const proxies = {};
  for (const p of proxyIpList(env, settings)) proxies[p] = (settings.proxyCountries && settings.proxyCountries[p]) || '';
  const countries = Object.keys(by).filter((c) => c !== '??').sort().map((cc) => ({ code: cc, label: countryLabel(cc), flag: flagOf(cc), addresses: by[cc], proxies: Object.keys(proxies).filter((p) => proxies[p] === cc) }));
  return { preferred: settings.country || '', fallback: settings.countryFallback, countries, untagged: by['??'] || [], proxies };
}

/* ---- chain outbound: SOCKS5 / HTTP CONNECT over cloudflare:sockets ----
 * This is what makes the exit IP (and therefore the "country") STABLE: with a
 * chain every connection leaves from your own relay instead of whichever
 * Cloudflare datacenter the anycast route happened to land in. */

async function readExactly(reader, pending, n) {
  let buf = pending || new Uint8Array(0);
  while (buf.byteLength < n) {
    const { value, done } = await reader.read();
    if (done) throw new Error('chain closed during handshake');
    buf = concatBytes(buf, toBytes(value));
  }
  return { head: buf.subarray(0, n), rest: buf.subarray(n) };
}

async function socks5Handshake(socket, chain, host, port) {
  const writer = socket.writable.getWriter();
  const reader = socket.readable.getReader();
  try {
    const wantAuth = !!(chain.user || chain.pass);
    await writer.write(new Uint8Array(wantAuth ? [5, 2, 0, 2] : [5, 1, 0]));
    let r = await readExactly(reader, null, 2);
    if (r.head[0] !== 5) throw new Error('not a socks5 proxy');
    if (r.head[1] === 2) {
      const u = new TextEncoder().encode(chain.user), p = new TextEncoder().encode(chain.pass);
      await writer.write(concatBytes(concatBytes(new Uint8Array([1, u.length]), u), concatBytes(new Uint8Array([p.length]), p)));
      r = await readExactly(reader, r.rest, 2);
      if (r.head[1] !== 0) throw new Error('socks5 auth failed');
    } else if (r.head[1] !== 0) {
      throw new Error('socks5 auth method rejected');
    }
    let addr;
    const v4 = ipToLong(host);
    if (v4 !== null) addr = new Uint8Array([1, (v4 >>> 24) & 255, (v4 >>> 16) & 255, (v4 >>> 8) & 255, v4 & 255]);
    else if (isIpv6(host)) {
      const h = parseV6Hextets(host);
      addr = new Uint8Array(17); addr[0] = 4;
      h.forEach((x, i) => { const v = parseInt(x, 16); addr[1 + i * 2] = v >> 8; addr[2 + i * 2] = v & 255; });
    } else {
      const d = new TextEncoder().encode(host);
      addr = concatBytes(new Uint8Array([3, d.length]), d);
    }
    await writer.write(concatBytes(concatBytes(new Uint8Array([5, 1, 0]), addr), new Uint8Array([port >> 8, port & 255])));
    r = await readExactly(reader, r.rest, 4);
    if (r.head[1] !== 0) throw new Error('socks5 connect refused (' + r.head[1] + ')');
    const atyp = r.head[3];
    let need = atyp === 1 ? 4 + 2 : atyp === 4 ? 16 + 2 : 0;
    if (atyp === 3) { const l = await readExactly(reader, r.rest, 1); need = l.head[0] + 2; r = { head: null, rest: l.rest }; }
    r = await readExactly(reader, r.rest, need);
    return r.rest.byteLength ? r.rest.slice() : null;
  } finally {
    writer.releaseLock();
    reader.releaseLock();
  }
}

async function httpConnectHandshake(socket, chain, host, port) {
  const writer = socket.writable.getWriter();
  const reader = socket.readable.getReader();
  try {
    const target = (isIpv6(host) ? '[' + host + ']' : host) + ':' + port;
    let req = 'CONNECT ' + target + ' HTTP/1.1\r\nHost: ' + target + '\r\nProxy-Connection: keep-alive\r\n';
    if (chain.user || chain.pass) req += 'Proxy-Authorization: Basic ' + btoa(chain.user + ':' + chain.pass) + '\r\n';
    await writer.write(new TextEncoder().encode(req + '\r\n'));
    let buf = new Uint8Array(0);
    for (;;) {
      const { value, done } = await reader.read();
      if (done) throw new Error('chain closed during CONNECT');
      buf = concatBytes(buf, toBytes(value));
      const text = new TextDecoder().decode(buf);
      const end = text.indexOf('\r\n\r\n');
      if (end >= 0) {
        if (!/^HTTP\/1\.[01] 2\d\d/.test(text)) throw new Error('CONNECT refused: ' + text.split('\r\n')[0]);
        const headerBytes = new TextEncoder().encode(text.slice(0, end + 4)).byteLength;
        return buf.byteLength > headerBytes ? buf.slice(headerBytes) : null;
      }
      if (buf.byteLength > 8192) throw new Error('CONNECT reply too large');
    }
  } finally {
    writer.releaseLock();
    reader.releaseLock();
  }
}

async function dialViaChain(sockets, chain, host, port) {
  const socket = sockets.connect({ hostname: chain.host, port: chain.port }, { allowHalfOpen: false });
  if (socket.opened) await socket.opened;
  try {
    const leftover = chain.type === 'socks5'
      ? await socks5Handshake(socket, chain, host, port)
      : await httpConnectHandshake(socket, chain, host, port);
    return { socket, leftover };
  } catch (e) {
    try { socket.close(); } catch (e2) { /* ignore */ }
    throw e;
  }
}

async function dialTarget(host, port, env, settings, log, proxyOverride) {
  const sockets = await loadSockets();
  if (!sockets) throw new Error('cloudflare:sockets unavailable');
  const targetIsCf = isCloudflareIp(host);
  const chain = parseChain((settings && settings.chain) || env.CHAIN || '');
  const useChain = chain && ((settings && settings.chainMode === 'cf') ? targetIsCf : true);
  let lastError = null;
  if (useChain) {
    try {
      const dialed = await dialViaChain(sockets, chain, host, port);
      if (log) log('dial ok chain:' + chain.type + ' → ' + host + ':' + port);
      return { socket: dialed.socket, via: 'chain', leftover: dialed.leftover };
    } catch (e) {
      lastError = e;
      if (log) log('chain failed: ' + (e && e.message ? e.message : e));
      if (settings && settings.chainStrict) throw e;
    }
  }
  const attempts = [];
  if (!targetIsCf) attempts.push({ hostname: host, port, via: 'direct' });
  // Per-connection override: /?proxyip= on the WS path (🎯 PX configs) wins.
  if (proxyOverride) {
    const parsed = splitHostPort(proxyOverride, port);
    attempts.push({ hostname: parsed.hostname, port: parsed.port || port, via: 'proxy:' + proxyOverride });
  }
  for (const proxy of proxyIpList(env, settings)) {
    const parsed = splitHostPort(proxy, port);
    attempts.push({ hostname: parsed.hostname, port: parsed.port || port, via: 'proxy:' + proxy });
  }
  for (const attempt of attempts) {
    try {
      const socket = sockets.connect({ hostname: attempt.hostname, port: attempt.port }, { allowHalfOpen: false });
      if (socket.opened) await socket.opened;
      if (log) log('dial ok ' + attempt.via + ' → ' + attempt.hostname + ':' + attempt.port);
      return { socket, via: attempt.via, leftover: null };
    } catch (e) {
      lastError = e;
      if (log) log('dial failed ' + attempt.via + ': ' + (e && e.message ? e.message : e));
    }
  }
  throw lastError || new Error('no route to ' + host + ':' + port);
}

/**
 * The pipe. Upstream uses pipeTo() (runtime-native, no JS per chunk);
 * downstream is a tight read loop that only prefixes the protocol response
 * to the first chunk. Nothing is counted — that was the CPU hog in v5.
 */
async function pumpTunnel(ws, clientReadable, socket, responseHeader, leftover) {
  const upstream = clientReadable.pipeTo(socket.writable, { preventAbort: false }).catch(() => {});
  const downstream = (async () => {
    const reader = socket.readable.getReader();
    let header = responseHeader;
    try {
      if (leftover && leftover.byteLength && ws.readyState === WS_OPEN) {
        ws.send(header ? concatBytes(header, leftover) : leftover);
        header = null;
      }
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (!chunk.value || !chunk.value.byteLength) continue;
        if (ws.readyState !== WS_OPEN) break;
        if (header) { ws.send(concatBytes(header, chunk.value)); header = null; }
        else ws.send(chunk.value);
      }
    } catch (e) { /* remote closed */ }
    if (header && ws.readyState === WS_OPEN) { try { ws.send(header); } catch (e) { /* ignore */ } }
    safeCloseWs(ws, 1000, 'remote closed');
    try { await reader.cancel(); } catch (e) { /* ignore */ }
  })();
  await Promise.all([upstream, downstream]);
  try { socket.close(); } catch (e) { /* ignore */ }
}

/* Trojan hash → uuid map, rebuilt only when the user list changes. */
let trojanMapCache = { key: '', map: null };
async function trojanCandidates(env, masterUuid, users) {
  const key = masterUuid + '|' + (env.TROJAN_PASS || '') + '|' + users.map((u) => u.id).join(',');
  if (trojanMapCache.key === key && trojanMapCache.map) return trojanMapCache.map;
  const map = new Map();
  if (env.TROJAN_PASS) map.set(await trojanHash(env.TROJAN_PASS), { uuid: masterUuid, master: true });
  map.set(await trojanHash(masterUuid), { uuid: masterUuid, master: true });
  for (const u of users) map.set(await trojanHash(u.id), { uuid: u.id, user: u });
  trojanMapCache = { key, map };
  return map;
}

async function tunnelAuth(env, uuid, masterUuid) {
  const id = String(uuid || '').toLowerCase();
  if (id === masterUuid) return { ok: true, master: true };
  const users = await readUsers(env);
  const user = findUser(users, id);
  if (!user) return { ok: false, error: 'unknown' };
  const blocked = userBlockedReason(user);
  if (blocked) return { ok: false, error: blocked, user };
  return { ok: true, user };
}

/** Last Online: remember that identity <id> just connected (KV, throttled to one write / 5 min). */
async function markSeen(env, id) {
  try {
    const kv = kvBinding(env);
    if (!kv || !id) return;
    const key = 'seen:' + id;
    const prev = await kv.get(key);
    const now = Date.now();
    if (prev && now - Number(prev) < 5 * 60 * 1000) return;
    await kv.put(key, String(now));
  } catch (e) { /* best effort */ }
}

async function readSeen(env, id) {
  try {
    const kv = kvBinding(env);
    if (!kv || !id) return 0;
    return Number(await kv.get('seen:' + id)) || 0;
  } catch (e) { return 0; }
}

async function handleTunnelConnection(ws, env, options = {}) {
  const earlyData = decodeEarlyData(options.earlyDataHeader);
  const clientStream = websocketReadable(ws, earlyData);
  const reader = clientStream.getReader();
  const settings = options.settings || await readSettings(env);
  const masterUuid = String(options.masterUuid || env.UUID || '').toLowerCase();
  const log = options.log || (() => {});
  // 🎯 PX configs carry /?proxyip=<ip> on the WS path — that relay wins for THIS connection.
  // Port-bearing relays arrive double-encoded (path is encoded once, the
  // proxyip VALUE once more) — decode twice, keep the value if the second
  // decode is not valid percent-encoding.
  const pxOverride = (() => { let v = ((options.path || '').match(/[?&](?:proxyip|pyip)=([^&]+)/) || [])[1] || ''; try { v = decodeURIComponent(v); } catch (e) { } try { v = decodeURIComponent(v); } catch (e) { } return v; })();

  let first;
  try { first = await reader.read(); } catch (e) { safeCloseWs(ws, 1011, 'read failed'); return; }
  if (first.done || !first.value || !first.value.byteLength) { safeCloseWs(ws, 1002, 'empty handshake'); return; }
  const bytes = first.value;

  let target = null;
  let firstPayload = null;
  let responseHeader = null;
  let isDns = false;

  const vless = parseVlessHeader(bytes);
  if (vless) {
    const auth = await tunnelAuth(env, vless.uuid, masterUuid);
    if (!auth.ok) { log('vless rejected (' + auth.error + ')'); safeCloseWs(ws, 1008, 'unauthorized'); return; }
    markSeen(env, auth.user ? auth.user.id : masterUuid);
    if (auth.user && auth.user.protocols && auth.user.protocols.vless === false) { safeCloseWs(ws, 1008, 'protocol disabled'); return; }
    if (vless.command === 2) {
      if (vless.port !== 53) { safeCloseWs(ws, 1003, 'udp only for dns'); return; }
      isDns = true;
    } else if (vless.command !== 1) { safeCloseWs(ws, 1003, 'unsupported command'); return; }
    target = { host: vless.host, port: vless.port };
    firstPayload = vless.rest;
    responseHeader = new Uint8Array([vless.version, 0]);
  } else {
    const trojan = trojanPassword(bytes);
    if (!trojan) { safeCloseWs(ws, 1002, 'unrecognised handshake'); return; }
    const users = await readUsers(env);
    const candidates = await trojanCandidates(env, masterUuid, users);
    const match = candidates.get(trojan.password);
    const blocked = match && match.user ? userBlockedReason(match.user) : null;
    if (!match || blocked) { log('trojan rejected'); safeCloseWs(ws, 1008, 'unauthorized'); return; }
    markSeen(env, match.user ? match.user.id : masterUuid);
    if (match.user && match.user.protocols && match.user.protocols.trojan === false) { safeCloseWs(ws, 1008, 'protocol disabled'); return; }
    const request = parseTrojanRequest(trojan.rest);
    if (!request) { safeCloseWs(ws, 1002, 'malformed trojan request'); return; }
    if (request.command === 3) { safeCloseWs(ws, 1003, 'udp associate unsupported'); return; }
    target = { host: request.host, port: request.port };
    firstPayload = request.payload;
  }

  if (isDns) {
    let buffer = firstPayload || new Uint8Array(0);
    const pumpDns = async (chunk) => {
      buffer = concatBytes(buffer, chunk);
      while (buffer.byteLength >= 2) {
        const len = (buffer[0] << 8) | buffer[1];
        if (buffer.byteLength < 2 + len) break;
        const query = buffer.slice(2, 2 + len);
        buffer = buffer.slice(2 + len);
        try {
          const answer = await resolveDnsOverDoh(query, env);
          const frame = new Uint8Array(2 + answer.byteLength);
          frame[0] = answer.byteLength >> 8;
          frame[1] = answer.byteLength & 255;
          frame.set(answer, 2);
          const payload = responseHeader ? concatBytes(responseHeader, frame) : frame;
          responseHeader = null;
          if (ws.readyState === WS_OPEN) ws.send(payload);
        } catch (e) { log('dns failed'); }
      }
    };
    await pumpDns(new Uint8Array(0));
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        await pumpDns(chunk.value);
      }
    } catch (e) { /* client gone */ }
    safeCloseWs(ws, 1000, 'dns done');
    return;
  }

  let dialed;
  try {
    dialed = await dialTarget(target.host, target.port, env, settings, log, pxOverride || null);
  } catch (e) {
    log('no route to ' + target.host + ':' + target.port);
    safeCloseWs(ws, 1011, 'dial failed');
    return;
  }

  const upstreamReadable = new ReadableStream({
    start(controller) { if (firstPayload && firstPayload.byteLength) controller.enqueue(firstPayload); },
    async pull(controller) {
      try {
        const chunk = await reader.read();
        if (chunk.done) controller.close(); else controller.enqueue(chunk.value);
      } catch (e) { controller.error(e); }
    },
    cancel() { try { reader.cancel(); } catch (e) { /* ignore */ } },
  });

  try {
    await pumpTunnel(ws, upstreamReadable, dialed.socket, responseHeader, dialed.leftover);
  } catch (e) {
    log('tunnel error');
  } finally {
    safeCloseWs(ws, 1000, 'done');
  }
}

function tunnelPaths(env) {
  // BPB-style per-deployment stealth paths: /vl/<seed>?ed=2560 (vless) and
  // /tr/<seed>?ed=2560 (trojan). The seed derives from the deployment UUID, so
  // every panel gets a different stable path. VLESS_PATH / TROJAN_PATH env vars
  // still win. Legacy '/ws' and '/trojan' remain accepted (see isTunnelPath).
  const overrideV = String(env.VLESS_PATH || '').trim();
  const overrideT = String(env.TROJAN_PATH || '').trim();
  const seed = (() => {
    try {
      const B62 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
      let n = BigInt('0x' + sha224Hex(String(env.UUID || 'cat-panel')));
      let s = '';
      while (s.length < 23) { s += B62[Number(n % 62n)]; n /= 62n; }
      return s;
    } catch (e) { return 'CatPanelSeedFallback0123'; }
  })();
  const vless = overrideV || '/vl/' + seed + '?ed=2560';
  const trojan = overrideT || '/tr/' + seed + '?ed=2560';
  return {
    vlessPath: vless,
    trojanPath: trojan,
    vlessName: vless.split('?')[0],
    trojanName: trojan.split('?')[0],
  };
}

function isTunnelPath(pathname, env) {
  const p = tunnelPaths(env);
  if (pathname === p.vlessName || pathname === p.trojanName) return true;
  if (pathname === '/ws' || pathname.startsWith('/ws/') || pathname === '/trojan') return true;
  // BPB-style prefixes (seed is obfuscation, shape is what matters)
  if (/^\/vl\/[0-9a-z_-]{4,64}$/i.test(pathname) || /^\/tr\/[0-9a-z_-]{4,64}$/i.test(pathname)) return true;
  return false;
}


/* ------------------------------------------------------------------ */
/* subscription content                                                */
/* ------------------------------------------------------------------ */

/** Owner-visible event log (KV ring, last 50) — real actions only. */
const EVENTS_KEY = 'cat_events_v1';
async function readEvents(env) {
  const kv = kvBinding(env);
  if (!kv) return [];
  try { const a = JSON.parse((await kv.get(EVENTS_KEY)) || '[]'); return Array.isArray(a) ? a : []; } catch { return []; }
}
async function pushEvent(env, ev, d) {
  const kv = kvBinding(env);
  if (!kv) return; // no KV → nothing persists; skip silently (events must never break the data path)
  try {
    const list = await readEvents(env);
    list.unshift({ t: Date.now(), ev: String(ev || 'event').slice(0, 24), d: String(d || '').slice(0, 120) });
    await kv.put(EVENTS_KEY, JSON.stringify(list.slice(0, 50)));
  } catch { /* ignore */ }
}

/** Repo library: live clean-IP feeds (12h auto-refresh, dead-IP replacement). */
const REPO_CACHE_KEY = 'cat_repo_cache_v1';
const REPO_HEALTH_KEY = 'cat_repo_health_v1';
const REPO_TTL_MS = 12 * 3600 * 1000;
const REPO_FAILS_DROP = 3;
const DEFAULT_REPOS = [
  { id: 'arista', name: 'Arista Clean IPs (hourly)', url: 'https://raw.githubusercontent.com/arista-project/cf-clean-ips/main/ip.txt', kind: 'txt', enabled: true },
  { id: 'matix', name: 'Matix Scanner (10min, speed-sorted)', url: 'https://raw.githubusercontent.com/imatixofficel/Scanner-matix/main/data/clean_ips.json', kind: 'json-speed', enabled: true },
];
function sanitizeRepos(list) {
  const src = Array.isArray(list) && list.length ? list : DEFAULT_REPOS;
  const out = [];
  for (const r of src.slice(0, 10)) {
    if (!r || typeof r !== 'object') continue;
    const url = String(r.url || '').trim();
    if (!/^https:\/\/[^\s"'<>]+$/.test(url)) continue;
    const kind = r.kind === 'json-speed' ? 'json-speed' : 'txt';
    const id = (String(r.id || '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 20)) || 'repo' + (out.length + 1);
    out.push({ id, name: String(r.name || 'repo').slice(0, 48), url, kind, enabled: r.enabled !== false });
  }
  if (!out.length) for (const r of DEFAULT_REPOS) out.push(Object.assign({}, r));
  return out;
}
async function readJsonKv(env, key, fallback) {
  const kv = kvBinding(env);
  if (!kv) return fallback;
  try { const v = await kv.get(key); return v ? Object.assign({}, fallback, JSON.parse(v)) : fallback; } catch { return fallback; }
}
/** txt: one address per line (`ip`, `ip:port`, `ip#CC`); json-speed: Matix {results:[{ip,ms,status}]}. */
function parseRepoFeed(kind, body) {
  const out = [];
  if (kind === 'json-speed') {
    let j = null; try { j = JSON.parse(body); } catch { return out; }
    const rs = Array.isArray(j && j.results) ? j.results : [];
    for (const r of rs) {
      if (!r || typeof r.ip !== 'string') continue;
      if (r.status && r.status !== 'online') continue;
      const ip = r.ip.trim();
      if (!(isIpv4(ip) || isIpv6(ip))) continue;
      out.push({ ip, cc: '', ms: Number(r.ms) || 9999 });
    }
    out.sort((a, b) => a.ms - b.ms);
    return out.slice(0, 400);
  }
  for (const raw of String(body || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;
    const t = splitAddrTag(line);
    let a = t.addr;
    const pin = pinnedPortOf(a);
    if (pin) a = a.slice(0, a.lastIndexOf(':'));
    a = a.replace(/^\[/, '').replace(/\]$/, '');
    if (isIpv4(a) || isIpv6(a)) out.push({ ip: a, cc: t.cc || '', ms: 9999 });
  }
  return out.slice(0, 400);
}
async function refreshRepos(env, fetchImpl) {
  const kv = kvBinding(env);
  if (!kv) return { ok: false, error: 'kv' };
  const F = fetchImpl || fetch;
  const settings = await readSettings(env);
  const repos = (settings.repos || []).filter((r) => r.enabled !== false);
  const health = await readJsonKv(env, REPO_HEALTH_KEY, { f: {} });
  const per = {};
  const merged = new Map();
  await Promise.allSettled(repos.map(async (r) => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 8000);
    try {
      const res = await F(r.url, { signal: ctl.signal });
      clearTimeout(timer);
      if (!res || !res.ok) throw new Error('http ' + (res && res.status));
      const list = parseRepoFeed(r.kind, await res.text());
      const alive = list.filter((x) => (health.f[x.ip] || 0) < REPO_FAILS_DROP);
      for (const x of alive) if (!merged.has(x.ip)) merged.set(x.ip, x);
      per[r.id] = { ts: Date.now(), ok: true, count: alive.length };
    } catch (e) {
      clearTimeout(timer);
      per[r.id] = { ts: Date.now(), ok: false, error: String((e && e.message) || e).slice(0, 60) };
    }
  }));
  const ips = Array.from(merged.values()).map((x) => (x.cc ? x.ip + '#' + x.cc : x.ip)).slice(0, 500);
  const cache = { ts: Date.now(), per, ips };
  await kv.put(REPO_CACHE_KEY, JSON.stringify(cache));
  pushEvent(env, 'repo-refresh', repos.map((r) => r.id + (per[r.id] && per[r.id].ok ? ' ' + per[r.id].count : ' fail')).join(' · ').slice(0, 110));
  return { ok: true, ts: cache.ts, total: ips.length, per };
}
/** Fresh, healthy library entries [{ip, cc}] — dead ones (≥3 fail reports) are gone. */
async function repoHealthyPool(env, cap) {
  const cache = await readJsonKv(env, REPO_CACHE_KEY, { ts: 0, ips: [] });
  const health = await readJsonKv(env, REPO_HEALTH_KEY, { f: {} });
  const out = [];
  for (const it of Array.isArray(cache.ips) ? cache.ips : []) {
    const t = splitAddrTag(String(it));
    let ip = t.addr;
    const pin = pinnedPortOf(ip);
    if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
    ip = ip.replace(/^\[/, '').replace(/\]$/, '');
    if ((health.f[ip] || 0) >= REPO_FAILS_DROP) continue;
    out.push({ ip, cc: t.cc || '' });
    if (out.length >= (cap || 500)) break;
  }
  return out;
}
/** ?repoAuto=on: append up to 8 library IPs (pinned to :443, country-tagged) AFTER the user's own list. */
async function withRepoPool(env, settings, url) {
  try {
    if (!settings || settings.repoAuto !== true) return settings;
    if (url && (url.searchParams.get('norepo') === '1' || url.searchParams.get('norepo') === 'true')) return settings;
    const pool = await repoHealthyPool(env, 8);
    if (!pool.length) return settings;
    const ips = [];
    const cc = {};
    for (const r of pool) { const line = r.ip + ':443'; ips.push(line); if (r.cc) cc[line] = r.cc; }
    return Object.assign({}, settings, { ips: uniq(settings.ips.concat(ips)).slice(0, 400), ipCountries: Object.assign({}, settings.ipCountries, cc) });
  } catch { return settings; }
}
/** Panel-page lazy trigger: if the cache is older than 12h, refresh in the background. */
async function maybeRepoRefresh(env, ctx) {
  try {
    const kv = kvBinding(env);
    if (!kv) return;
    const cache = await readJsonKv(env, REPO_CACHE_KEY, { ts: 0 });
    if (Date.now() - (cache.ts || 0) < REPO_TTL_MS) return;
    const job = refreshRepos(env).catch(() => {});
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job);
  } catch { /* ignore */ }
}

/** ProxyIP repo library: same 12h/replace mechanics, feeds of PROXY IPs (IPs or domains). */
const PROXY_REPO_CACHE_KEY = 'cat_prepo_cache_v1';
const PROXY_REPO_HEALTH_KEY = 'cat_prepo_health_v1';
const PROXY_REPO_TTL_MS = 12 * 3600 * 1000;
const PROXY_REPO_FAILS_DROP = 3;
const DEFAULT_PROXY_REPOS = [
  { id: 'xgonce', name: 'XGonce ProxyIP (CSV, 6h, speed-sorted)', url: 'https://raw.githubusercontent.com/xgonce/Cloudflare_IP/main/result.csv', kind: 'csv-proxy', enabled: true },
  { id: 'wanwu-de', name: 'Wanwu ProxyIP · Germany', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/DE.txt', kind: 'txt', cc: 'DE', enabled: true },
  { id: 'wanwu-gb', name: 'Wanwu ProxyIP · UK', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/GB.txt', kind: 'txt', cc: 'GB', enabled: true },
  { id: 'wanwu-us', name: 'Wanwu ProxyIP · USA', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/US.txt', kind: 'txt', cc: 'US', enabled: true },
  { id: 'wanwu-tr', name: 'Wanwu ProxyIP · Türkiye', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/TR.txt', kind: 'txt', cc: 'TR', enabled: true },
  { id: 'wanwu-fr', name: 'Wanwu ProxyIP · France', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/FR.txt', kind: 'txt', cc: 'FR', enabled: true },
  { id: 'wanwu-nl', name: 'Wanwu ProxyIP · Netherlands', url: 'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/NL.txt', kind: 'txt', cc: 'NL', enabled: true },
];
function sanitizeProxyRepos(list) {
  const src = Array.isArray(list) && list.length ? list : DEFAULT_PROXY_REPOS;
  const out = [];
  for (const r of src.slice(0, 10)) {
    if (!r || typeof r !== 'object') continue;
    const url = String(r.url || '').trim();
    if (!/^https:\/\/[^\s"'<>]+$/.test(url)) continue;
    const kind = r.kind === 'csv-proxy' || r.kind === 'json-speed' ? r.kind : 'txt';
    const ccRaw = String(r.cc || '').trim().toUpperCase();
    const id = (String(r.id || '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 20)) || 'prepo' + (out.length + 1);
    out.push({ id, name: String(r.name || 'repo').slice(0, 48), url, kind, cc: /^[A-Z]{2}$/.test(ccRaw) ? ccRaw : '', enabled: r.enabled !== false });
  }
  if (!out.length) for (const r of DEFAULT_PROXY_REPOS) out.push(Object.assign({}, r));
  return out;
}
/** ProxyIP entries may be IPv4/IPv6/domains (resolved at dial time), optional #CC. */
function parseProxyFeed(kind, body) {
  const out = [];
  if (kind === 'json-speed') {
    let j = null; try { j = JSON.parse(body); } catch { return out; }
    const rs = Array.isArray(j && j.results) ? j.results : [];
    for (const r of rs) {
      if (!r || typeof r.ip !== 'string') continue;
      if (r.status && r.status !== 'online') continue;
      const ip = r.ip.trim();
      if (!(isIpv4(ip) || isIpv6(ip))) continue;
      out.push({ ip, cc: '', ms: Number(r.ms) || 9999 });
    }
    out.sort((a, b) => a.ms - b.ms);
    return out.slice(0, 400);
  }
  if (kind === 'csv-proxy') {
    // xgonce result.csv: IP,cf-meta-ip,PORT,speedMbps,CC,COLO,TCPms,TLSms
    // Port column is KEPT: each proxy IP has its own working port, and a bare
    // ip emitted on every panel port produced mostly-dead configs.
    for (const line of String(body || '').split(/\r?\n/).slice(1)) {
      const c = line.split(',');
      const ip = (c[0] || '').trim();
      if (!ip || !(isIpv4(ip) || isIpv6(ip))) continue;
      const p = parseInt(c[2], 10);
      const port = p >= 1 && p <= 65535 ? p : null;
      const ccRaw = (c[4] || '').trim().toUpperCase();
      const ms = Number(c[6]);
      out.push({ ip, port, cc: /^[A-Z]{2}$/.test(ccRaw) ? ccRaw : '', ms: ms >= 1 && ms <= 5000 ? ms : 9999 });
    }
    out.sort((a, b) => a.ms - b.ms);
    return out.slice(0, 400);
  }
  for (const raw of String(body || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;
    const t = splitAddrTag(line);
    let ip = t.addr;
    const pin = pinnedPortOf(ip);
    if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
    ip = ip.replace(/^\[/, '').replace(/\]$/, '');
    if (isIpv4(ip) || isIpv6(ip) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(ip)) out.push({ ip, cc: t.cc || '', ms: 9999 });
  }
  return out.slice(0, 400);
}
async function refreshProxyRepos(env, fetchImpl) {
  const kv = kvBinding(env);
  if (!kv) return { ok: false, error: 'kv' };
  const F = fetchImpl || fetch;
  const settings = await readSettings(env);
  const repos = (settings.proxyRepos || []).filter((r) => r.enabled !== false);
  const health = await readJsonKv(env, PROXY_REPO_HEALTH_KEY, { f: {} });
  const per = {};
  const merged = new Map();
  await Promise.allSettled(repos.map(async (r) => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 8000);
    try {
      const res = await F(r.url, { signal: ctl.signal });
      clearTimeout(timer);
      if (!res || !res.ok) throw new Error('http ' + (res && res.status));
      const list = parseProxyFeed(r.kind, await res.text());
      const alive = list.filter((x) => (health.f[x.ip] || 0) < PROXY_REPO_FAILS_DROP);
      for (const x of alive) { if (!x.cc && r.cc) x.cc = r.cc; if (!merged.has(x.ip)) merged.set(x.ip, x); }
      per[r.id] = { ts: Date.now(), ok: true, count: alive.length };
    } catch (e) {
      clearTimeout(timer);
      per[r.id] = { ts: Date.now(), ok: false, error: String((e && e.message) || e).slice(0, 60) };
    }
  }));
  const ips = Array.from(merged.values()).map((x) => {
    const addr = x.ip + ':' + (x.port || 443); // every pool IP is pinned to ONE port
    return x.cc ? addr + '#' + x.cc : addr;
  }).slice(0, 400);
  const cache = { ts: Date.now(), per, ips };
  await kv.put(PROXY_REPO_CACHE_KEY, JSON.stringify(cache));
  pushEvent(env, 'prepo-refresh', repos.map((r) => r.id + (per[r.id] && per[r.id].ok ? ' ' + per[r.id].count : ' fail')).join(' · ').slice(0, 110));
  return { ok: true, ts: cache.ts, total: ips.length, per };
}
async function proxyRepoHealthyPool(env, cap) {
  const cache = await readJsonKv(env, PROXY_REPO_CACHE_KEY, { ts: 0, ips: [] });
  const health = await readJsonKv(env, PROXY_REPO_HEALTH_KEY, { f: {} });
  const out = [];
  for (const it of Array.isArray(cache.ips) ? cache.ips : []) {
    const t = splitAddrTag(String(it));
    let ip = t.addr;
    const pin = pinnedPortOf(ip);
    if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
    ip = ip.replace(/^\[/, '').replace(/\]$/, '');
    if ((health.f[ip] || 0) >= PROXY_REPO_FAILS_DROP) continue;
    // Port flows through: feed port wins, otherwise 443 — so 🎯 relay dials and
    // toAddrs imports each pool IP on exactly ONE port instead of all of them.
    out.push({ ip: ip + ':' + (pin || 443), cc: t.cc || '' });
    if (out.length >= (cap || 400)) break;
  }
  return out;
}
/** ?proxyRepoAuto=on: append up to 6 healthy ProxyIPs AFTER the user's own list (cap 32). */
async function withProxyRepoPool(env, settings, url) {
  try {
    if (!settings || settings.proxyRepoAuto !== true) return settings;
    if (url && (url.searchParams.get('norepo') === '1' || url.searchParams.get('norepo') === 'true')) return settings;
    const pool = await proxyRepoHealthyPool(env, 6);
    if (!pool.length) return settings;
    const ips = [];
    const cc = {};
    for (const r of pool) { ips.push(r.ip); if (r.cc) cc[r.ip] = r.cc; }
    return Object.assign({}, settings, { proxyIps: uniq(settings.proxyIps.concat(ips)).slice(0, 32), proxyCountries: Object.assign({}, settings.proxyCountries, cc) });
  } catch { return settings; }
}
async function maybeProxyRepoRefresh(env, ctx) {
  try {
    const kv = kvBinding(env);
    if (!kv) return;
    const cache = await readJsonKv(env, PROXY_REPO_CACHE_KEY, { ts: 0 });
    if (Date.now() - (cache.ts || 0) < PROXY_REPO_TTL_MS) return;
    const job = refreshProxyRepos(env).catch(() => {});
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job);
  } catch { /* ignore */ }
}

/** ECH (Encrypted ClientHello): ECHConfigList of the SNI from its DNS HTTPS
 * record (type 65), cached in KV for 24h. Feeds ?ech=1 config variants — the
 * «ECH / SIIT-Hex64» style tickets other panels advertise. */
const ECH_CACHE_KEY = 'cat_ech_v1';
async function echConfigList(sni, env) {
  const kv = kvBinding(env);
  if (!kv || !sni) return '';
  const cached = await readJsonKv(env, ECH_CACHE_KEY, {});
  const now = Date.now();
  const hit = cached[sni];
  if (hit && now - (hit.ts || 0) < 24 * 3600 * 1000) return hit.ech || '';
  try {
    const res = await fetch('https://cloudflare-dns.com/dns-query?name=' + encodeURIComponent(sni) + '&type=HTTPS', { headers: { accept: 'application/dns-json' } });
    if (!res.ok) throw new Error('http ' + res.status);
    const j = await res.json();
    let ech = '';
    for (const a of (j && j.Answer) || []) {
      if (a.type !== 64) continue;
      const m = /ech=([A-Za-z0-9+\/=]+)/.exec(String(a.data || ''));
      if (m) { ech = m[1]; break; }
    }
    cached[sni] = { ts: now, ech };
    const keys = Object.keys(cached);
    if (keys.length > 20) delete cached[keys[0]];
    await kv.put(ECH_CACHE_KEY, JSON.stringify(cached));
    return ech;
  } catch { return (hit && hit.ech) || ''; }
}

/** WARP (wireguard) outbounds for the Xray JSON output. Keys are the USER'S
 * OWN (from wgcf / Aether export) — the worker never registers with Cloudflare,
 * so nothing here can trip abuse systems. mode: off | on (vless → warp) |
 * chain (vless → warp → warp-hub = WARP-in-WARP). */
function buildWarpOutbounds(settings) {
  const w = (settings && settings.warp) || {};
  if (w.mode === 'off' || !w.secretKey || !w.publicKey) return [];
  const mk = (tag, dialerProxy) => {
    const o = {
      tag,
      protocol: 'wireguard',
      settings: {
        secretKey: w.secretKey,
        peers: [{ publicKey: w.publicKey, endpoint: w.endpoint || 'engage.cloudflareclient.com:2408' }],
        address: ['172.16.0.2/32', 'fd01:5ca1:ab1e:80fa:ab85:6e2a:2b09:2b04/128'],
        mtu: 1280,
      },
      streamSettings: { sockopt: { tcpKeepAliveIdle: 100, tcpNoDelay: true } },
    };
    if (w.reserved && /^[0-9]+(,[0-9]+)*$/.test(w.reserved)) o.settings.reserved = w.reserved.split(',').map(Number).filter((n) => n >= 0 && n <= 255).slice(0, 3);
    if (dialerProxy) o.streamSettings.sockopt.dialerProxy = dialerProxy;
    return o;
  };
  const out = [mk('warp', w.mode === 'chain' ? 'warp-hub' : undefined)];
  if (w.mode === 'chain') out.push(mk('warp-hub', undefined));
  return out;
}

/** External subscriptions: fetch-through with a 12h KV cache and a hard size
 * cap — lets clients pull e.g. raw.githubusercontent.com subs THROUGH the
 * panel's own domain (raw GitHub is often unreachable from Iran). */
const EXT_CACHE_PREFIX = 'cat_ext_';
const EXT_TTL_MS = 12 * 3600 * 1000;
const EXT_MAX_BYTES = 384 * 1024;
async function extSubContent(env, url, fetchImpl) {
  url = String(url || '').replace(/&amp;/g, '&');
  const kv = kvBinding(env);
  const key = EXT_CACHE_PREFIX + (await sha256Hex(url)).slice(0, 24);
  if (kv) { try { const v = await kv.get(key); if (v) { const j = JSON.parse(v); if (Date.now() - (j.ts || 0) < EXT_TTL_MS && j.body) return j.body; } } catch { } }
  const F = fetchImpl || fetch;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 8000);
  try {
    const res = await F(url, { signal: ctl.signal });
    clearTimeout(timer);
    if (!res || !res.ok) throw new Error('http ' + (res && res.status));
    const body = String(await res.text()).slice(0, EXT_MAX_BYTES);
    if (kv) { try { await kv.put(key, JSON.stringify({ ts: Date.now(), body })); } catch { } }
    return body;
  } catch (e) {
    clearTimeout(timer);
    throw e;
  }
}
/** URI lines out of an external sub (base64 or plain). */
const EXT_URI_SCHEMES = /^(?:vless|vmess|trojan|ss|ssr|hysteria|hysteria2|hy2|tuic|socks|socks5|snell|anytls|wireguard|juicity|mieru):/;
function parseExtUris(body) {
  let text = String(body || '');
  if (!EXT_URI_SCHEMES.test(text)) { try { text = b64decode(text); } catch { } }
  return text.split(/\r?\n/).map((l) => l.trim()).filter((l) => EXT_URI_SCHEMES.test(l)).slice(0, 100);
}

function effectiveSni(host, env, settings) {
  void host; // deliberately NOT a fallback — see DEFAULT_FRONTING_SNI
  return String((settings && settings.sni) || env.SNI || DEFAULT_FRONTING_SNI).trim().toLowerCase();
}

function addressList(host, env, settings) {
  const own = settings.ips || [];
  const fromEnv = splitCsv(env.CF_IPS).map((r) => splitAddrTag(r).addr).filter(Boolean);
  const defaults = settings.useDefaults ? DEFAULT_CLEAN_ADDRESSES : [];
  const list = uniq(own.concat(fromEnv, defaults));
  if (settings.includeHost && !list.some((a) => a.toLowerCase() === String(host).toLowerCase())) list.push(String(host));
  return list;
}

function configName(proto, addr, port, tls, cc, host, index) {
  // BPB-style remarks: "💦 12. VLESS - Clean IP : 8080". Entries on the panel's
  // own address are 🔌 WorkerOnly; everything else is a clean IP (💦). A country
  // flag (when known) still leads the name so clients can group by country.
  const protoName = proto === 'vless' ? 'VLESS' : 'TROJAN';
  const isWorker = !!host && String(addr).toLowerCase().replace(/^\[/, '').replace(/\]$/, '') === String(host).toLowerCase();
  const label = (isWorker ? 'WorkerOnly' : 'Clean IP') + (tls ? ' TLS' : '');
  const n = index ? index + '. ' : '';
  return (cc ? flagOf(cc) + ' ' : '') + (isWorker ? '🔌 ' : '💦 ') + n + protoName + ' - ' + label + ' : ' + port;
}

function wsParams(hostHeader, path, sni, fp, tls, ech) {
  const params = [
    'security=' + (tls ? 'tls' : 'none'),
    'type=ws',
    'host=' + encodeURIComponent(hostHeader),
    'path=' + encodeURIComponent(path),
  ];
  if (tls) {
    params.push('sni=' + encodeURIComponent(sni));
    params.push('fp=' + encodeURIComponent(fp));
    if (ech) params.push('ech=' + encodeURIComponent(ech));
    params.push('alpn=' + encodeURIComponent('http/1.1'));
  }
  return params.join('&');
}

function vlessLink(ctx, addr, port, tls, cc, opts) {
  const name = (opts && opts.name) || configName('vless', addr, port, tls, cc, ctx.host);
  const path = (opts && opts.path) || ctx.paths.vlessPath;
  return 'vless://' + ctx.uuid + '@' + formatAddr(addr) + ':' + port + '?encryption=none&' +
    wsParams(ctx.host, path, ctx.sni, ctx.fp, tls, ctx.ech) + '#' + encodeURIComponent(name);
}

function trojanLink(ctx, addr, port, tls, cc, opts) {
  const name = (opts && opts.name) || configName('trojan', addr, port, tls, cc, ctx.host);
  const path = (opts && opts.path) || ctx.paths.trojanPath;
  return 'trojan://' + encodeURIComponent(ctx.trojanPass) + '@' + formatAddr(addr) + ':' + port + '?' +
    wsParams(ctx.host, path, ctx.sni, ctx.fp, tls, ctx.ech) + '#' + encodeURIComponent(name);
}

/** Link context for one identity (master or a panel user). */
function linkContext(host, env, settings, uuid, user) {
  const protocols = {
    vless: settings.protocols.vless && !(user && user.protocols.vless === false),
    trojan: settings.protocols.trojan && !(user && user.protocols.trojan === false),
  };
  return {
    host: String(host).toLowerCase(),
    uuid: String(uuid).toLowerCase(),
    trojanPass: user ? String(uuid).toLowerCase() : String(env.TROJAN_PASS || uuid).toLowerCase(),
    sni: effectiveSni(host, env, settings),
    fp: settings.fingerprint || 'chrome',
    ech: settings.echList || '',
    paths: tunnelPaths(env),
    protocols,
    settings,
    user: user || null,
  };
}

/** Every (address × port × protocol) entry; Cat order = TLS 443 first, then plain :80. */
function buildConfigEntries(host, env, settings, uuid, user, q) {
  const ctx = linkContext(host, env, settings, uuid, user);
  q = q || {};
  // Per-link overrides (?addr=a,b&port=443&proto=vless&limit=1) let a user pin
  // ONE address → one Cloudflare entry point → a stable exit.
  let addresses = addressList(host, env, settings);
  if (q.addr && q.addr.length) addresses = uniq(q.addr);
  // Country: ?country=DE (link) beats the panel's preferred country. Entries of
  // that country come FIRST; with ?strict=1 (or countryFallback=none) nothing
  // else is emitted, otherwise the other countries follow as fallback.
  const ccOf = (a) => countryOfAddr(a, env, settings);
  const wantCc = normalizeCountry(q.country) || settings.country || '';
  const strict = q.strict || (wantCc && settings.countryFallback === 'none');
  const wantedAddrs = wantCc ? addresses.filter((a) => ccOf(a) === wantCc) : [];
  if (wantCc && wantedAddrs.length) addresses = strict ? wantedAddrs : wantedAddrs.concat(addresses.filter((a) => ccOf(a) !== wantCc));
  const preferredCc = wantedAddrs.length ? wantCc : '';
  // Auto-rotation: 'fetch' reshuffles the address order on EVERY sub update (a
  // fresh set each refresh within the entry limit), 'daily' keeps one shuffled
  // arrangement per UTC day, 'off' is the stable BPB-like order. The port walk
  // is untouched, so the first configs stay TLS :443 either way.
  const ROT_VALUES = ['off', 'fetch', 'daily'];
  const rot = ROT_VALUES.includes(String(q.rotate)) ? String(q.rotate) : (ROT_VALUES.includes(settings.subRotate) ? settings.subRotate : 'off');
  const shuffleArr = (arr, seed) => { for (let ri = arr.length - 1; ri > 0; ri--) { seed = (seed * 1103515245 + 12345) & 0x7fffffff; const rj = seed % (ri + 1); const tt = arr[ri]; arr[ri] = arr[rj]; arr[rj] = tt; } return arr; };
  const rseed0 = rot === 'fetch' ? Math.floor(Math.random() * 2147483647) : (Number(new Date().toISOString().slice(0, 10).replace(/-/g, '')) % 2147483646) + 1;
  // Owner-pinned «fixed IPs» (settings.pinnedIps) always lead the sub and are
  // never shuffled — rotation applies to the REST of the list only.
  const pinKeys = (settings.pinnedIps || []).map((p) => String(p).trim().toLowerCase()).filter(Boolean);
  const pinKeyOf = (a) => { const pin = pinnedPortOf(a); const b = pin ? a.slice(0, a.lastIndexOf(':')) : a; return b.replace(/^\[/, '').replace(/\]$/, '').toLowerCase(); };
  const fixedAddrs = pinKeys.length ? addresses.filter((a) => pinKeys.includes(pinKeyOf(a))) : [];
  const rotAddrs = pinKeys.length ? addresses.filter((a) => !pinKeys.includes(pinKeyOf(a))) : addresses;
  if (rot !== 'off' && rotAddrs.length > 1) shuffleArr(rotAddrs, rseed0);
  addresses = fixedAddrs.concat(rotAddrs);
  if (q.proto === 'vless') ctx.protocols.trojan = false;
  if (q.proto === 'trojan') ctx.protocols.vless = false;
  if (q.port && q.port.length) {
    settings = Object.assign({}, settings, {
      tlsPorts: q.port.filter((p) => !PLAIN_PORTS.includes(p)),
      plainPorts: q.port.filter((p) => PLAIN_PORTS.includes(p)),
      plainEnabled: q.port.some((p) => PLAIN_PORTS.includes(p)),
    });
    if (!settings.tlsPorts.length && !settings.plainPorts.length) settings = Object.assign({}, settings, { tlsPorts: [443] });
  }
  if (q.limit) settings = Object.assign({}, settings, { entryLimit: Math.min(200, Math.max(1, q.limit)) });
  // Interleave TLS and plain ports (443, 80, 2053, 8080, …) so both kinds
  // survive the entry limit.
  const tls = settings.tlsPorts.map((p) => ({ port: p, tls: true }));
  const plain = settings.plainEnabled ? settings.plainPorts.map((p) => ({ port: p, tls: false })) : [];
  const ports = [];
  for (let i = 0; i < Math.max(tls.length, plain.length); i++) {
    if (tls[i]) ports.push(tls[i]);
    if (plain[i]) ports.push(plain[i]);
  }
  // Scan-pinned addresses (`ip:port`) bring their own verified port; make sure it
  // exists in the port walk even if the panel never enabled it explicitly.
  for (const p of uniq(addresses.map(pinnedPortOf).filter(Boolean))) if (!ports.some((x) => Number(x.port) === p)) ports.push({ port: Number(p), tls: !PLAIN_PORTS.includes(p) });
  const entries = [];
  let vi = 0;
  let ti = 0;
  const limit = settings.entryLimit;
  // Interleave: iterate ports in the outer loop so the first N entries span
  // many addresses on 443/80 rather than every port of one address.
  outer: for (const { port, tls } of ports) {
    for (const addr of addresses) {
      const pin = pinnedPortOf(addr); // pinned address → only its verified port (ports may arrive as strings)
      if (pin && pin !== Number(port)) continue;
      // A pinned `ip:port` (what the scanner imports) must connect to the BARE
      // host — the port is carried by `port`, never inside the address, or the
      // link would become `[1.2.3.4:443]:443` and the config could not dial.
      // Country lookup stays on the original string (ipCountries is keyed by it).
      const bare = pin ? addr.slice(0, addr.lastIndexOf(':')).replace(/^\[/, '').replace(/\]$/, '') : addr;
      const cc = ccOf(addr);
      if (ctx.protocols.vless) { vi++; const nm = configName('vless', bare, port, tls, cc, host, vi); entries.push({ proto: 'vless', addr: bare, port, tls, cc, link: vlessLink(ctx, bare, port, tls, cc, { name: nm }), name: nm }); }
      if (ctx.protocols.trojan) { ti++; const tm = configName('trojan', bare, port, tls, cc, host, ti); entries.push({ proto: 'trojan', addr: bare, port, tls, cc, link: trojanLink(ctx, bare, port, tls, cc, { name: tm }), name: tm }); }
      if (entries.length >= limit) break outer;
    }
  }
  // Spoof section (Panel → 🎭): extra-SNI configs (🧬) and per-ProxyIP configs
  // (🎯, path /?proxyip=) — deliberately named apart from the flag-named clean-IP
  // entries. Skipped when a link pins ?addr= / ?limit= (deliberate single exit),
  // or when a STRICT country is requested (strict=1 must mean ONLY that country).
  // NOTE: `!q.addr` alone is always false for an empty-array query (truthy []!),
  // which silently disabled PX/SNI-spoof configs on every real sub URL.
  if (!(q.addr && q.addr.length) && !q.limit && !(strict && wantCc)) {
    const tlsPort = (settings.tlsPorts && settings.tlsPorts[0]) || 443;
    for (const sniHost of (settings.extraSnis || [])) {
      if (entries.length >= 200) break;
      if (!sniHost || sniHost === ctx.sni) continue;
      const sctx = Object.assign({}, ctx, { sni: sniHost });
      const name = '🧬 SNI ' + sniHost;
      if (ctx.protocols.vless) entries.push({ proto: 'vless', addr: host, port: tlsPort, tls: true, cc: '', link: vlessLink(sctx, host, tlsPort, true, '', { name }), name });
      if (ctx.protocols.trojan) entries.push({ proto: 'trojan', addr: host, port: tlsPort, tls: true, cc: '', link: trojanLink(sctx, host, tlsPort, true, '', { name }), name });
    }
    const faUi = settings.lang !== 'en';
    let pxi = 0;
    for (const px of proxyIpList(env, settings)) {
      if (entries.length >= 224) break;
      pxi++;
      const pxcc = (settings.proxyCountries || {})[px] || '';
      // Screenshot style: «🎯 3. 🇩🇪 آلمان · 1.2.3.4» — a separate config PER
      // ProxyIP that exits through that relay (?proxyip= on the WS path).
      const pxlabel = pxcc ? (faUi ? (PX_FA_NAMES[pxcc] || pxcc) : (COUNTRY_NAMES[pxcc] || pxcc)) : '';
      const pxname = '🎯 ' + pxi + '. ' + (pxcc ? flagOf(pxcc) + ' ' + pxlabel + ' · ' : '') + px;
      if (ctx.protocols.vless) entries.push({ proto: 'vless', addr: host, port: tlsPort, tls: true, cc: pxcc, link: vlessLink(ctx, host, tlsPort, true, pxcc, { name: pxname, path: ctx.paths.vlessPath + '?proxyip=' + encodeURIComponent(px) }), name: pxname });
      if (ctx.protocols.trojan) entries.push({ proto: 'trojan', addr: host, port: tlsPort, tls: true, cc: pxcc, link: trojanLink(ctx, host, tlsPort, true, pxcc, { name: pxname, path: ctx.paths.trojanPath + '?proxyip=' + encodeURIComponent(px) }), name: pxname });
    }
  }
  // ?fam=v4|v6 — strict address-family filter over the FINAL entry list, so the
  // always-on worker-host anchor and domain defaults are dropped too: the link
  // then means exactly "only raw IPs of this family".
  if (q.fam === 'v4' || q.fam === 'v6') {
    const famOfAddr = (a) => {
      const pin = pinnedPortOf(a);
      const b = (pin ? a.slice(0, a.lastIndexOf(':')) : a).replace(/^\[/, '').replace(/\]$/, '');
      return isIpv6(b) ? 'v6' : isIpv4(b) ? 'v4' : '';
    };
    return { ctx, entries: entries.filter((e) => famOfAddr(String(e.addr)) === q.fam), preferredCc };
  }
  return { ctx, entries, preferredCc };
}

function subscriptionHeaders(user, title, webUrl) {
  const expire = user && user.expiresAt ? Math.floor(user.expiresAt / 1000) : 0;
  const headers = {
    'profile-title': 'base64:' + b64encode(title),
    'profile-update-interval': '12',
    'subscription-userinfo': 'upload=0; download=0; total=0' + (expire ? '; expire=' + expire : ''),
  };
  if (webUrl) headers['profile-web-page-url'] = webUrl;
  return headers;
}

function yamlStr(value) {
  return JSON.stringify(String(value));
}

function subQuery(url) {
  if (!url || !url.searchParams) return {};
  // Links copied from Telegram/HTML surfaces carry &amp; instead of & (the
  // entity survives the clipboard) — every "amp;param" would be LOST here and
  // ports/limit/strict silently ignored. Normalize before parsing.
  const q = String(url.search || '').includes('&amp;')
    ? new URLSearchParams(String(url.search).replace(/&amp;/g, '&'))
    : url.searchParams;
  return {
    addr: splitCsv(q.get('addr') || q.get('ip') || ''),
    port: splitCsv(q.get('port') || q.get('ports') || '').map(Number).filter((p) => p > 0),
    proto: String(q.get('proto') || '').toLowerCase(),
    limit: Number(q.get('limit') || q.get('count') || 0) || 0,
    country: normalizeCountry(q.get('country') || q.get('cc') || ''),
    strict: q.get('strict') === '1' || q.get('strict') === 'true',
    fam: String(q.get('fam') || '').toLowerCase(),
    rotate: String(q.get('rotate') || '').toLowerCase(),
    ech: q.get('ech') === '1',
    noext: q.get('noext') === '1' || q.get('noext') === 'true',
  };
}

/** Country groups shared by Clash + sing-box: [{ cc, name, entries }], preferred first. */
function countryGroups(entries, preferredCc) {
  const by = new Map();
  for (const e of entries) { const k = e.cc || ''; if (!by.has(k)) by.set(k, []); by.get(k).push(e); }
  const keys = Array.from(by.keys()).sort((a, b) => (a === preferredCc ? -1 : b === preferredCc ? 1 : (a === '' ? 1 : b === '' ? -1 : a.localeCompare(b))));
  return keys.map((cc) => ({ cc, name: countryLabel(cc), entries: by.get(cc) }));
}

function buildClashYaml(host, env, settings, uuid, user, q) {
  const { ctx, entries, preferredCc } = buildConfigEntries(host, env, settings, uuid, user, q);
  const proxies = entries.map((e) => {
    const base = [
      '  - name: ' + yamlStr(e.name),
      '    type: ' + e.proto,
      '    server: ' + yamlStr(e.addr.replace(/^\[|\]$/g, '')),
      '    port: ' + e.port,
      e.proto === 'vless' ? '    uuid: ' + ctx.uuid : '    password: ' + yamlStr(ctx.trojanPass),
      '    udp: true',
      '    network: ws',
      '    ws-opts:',
      '      path: ' + yamlStr(e.proto === 'vless' ? ctx.paths.vlessPath : ctx.paths.trojanPath),
      '      headers:',
      '        Host: ' + yamlStr(ctx.host),
    ];
    if (e.tls) {
      base.push('    tls: true', '    servername: ' + yamlStr(ctx.sni), '    client-fingerprint: ' + ctx.fp, '    skip-cert-verify: false');
      if (e.proto === 'vless') base.push('    alpn: ["http/1.1"]');
    } else if (e.proto === 'vless') {
      base.push('    tls: false');
    } else {
      // Trojan without TLS is not a thing in Clash; skip.
      return null;
    }
    return base.join('\n');
  }).filter(Boolean);
  const usable = entries.filter((e) => e.tls || e.proto === 'vless');
  const names = usable.map((e) => '      - ' + yamlStr(e.name)).join('\n');
  const groups = countryGroups(usable, preferredCc);
  const tagged = groups.some((g) => g.cc);
  const groupYaml = [];
  if (tagged) {
    // One url-test group per country (fastest server INSIDE that country) …
    for (const g of groups) groupYaml.push('  - name: ' + yamlStr(g.name), '    type: url-test', '    url: https://www.gstatic.com/generate_204', '    interval: 300', '    tolerance: 150', '    proxies:', g.entries.map((e) => '      - ' + yamlStr(e.name)).join('\n'));
  }
  const groupNames = groups.map((g) => '      - ' + yamlStr(g.name)).join('\n');
  let root;
  if (tagged && preferredCc && settings.countryFallback !== 'none') {
    // … and the root is a FALLBACK: stay in the preferred country while any of
    // its servers is alive, otherwise jump to the fastest of the rest (⚡ Auto).
    root = ['  - name: "🐱 Cat"', '    type: fallback', '    url: https://www.gstatic.com/generate_204', '    interval: 120', '    proxies:', '      - ' + yamlStr(countryLabel(preferredCc)), '      - "⚡ Auto"'];
  } else if (tagged) {
    root = ['  - name: "🐱 Cat"', '    type: select', '    proxies:', groupNames, '      - "⚡ Auto"'];
  } else {
    root = ['  - name: "🐱 Cat"', '    type: select', '    proxies:', names, '      - "⚡ Auto"'];
  }
  return [
    '# list v' + CAT_PANEL_VERSION,
    'mixed-port: 7890',
    'allow-lan: false',
    'mode: rule',
    'log-level: warning',
    'ipv6: true',
    'unified-delay: true',
    'tcp-concurrent: true',
    'dns:',
    '  enable: true',
    '  listen: 0.0.0.0:1053',
    '  enhanced-mode: fake-ip',
    '  fake-ip-range: 198.18.0.1/16',
    '  nameserver:',
    '    - https://1.1.1.1/dns-query',
    '    - https://8.8.8.8/dns-query',
    '  default-nameserver:',
    '    - 1.1.1.1',
    '    - 8.8.8.8',
    'proxies:',
    proxies.join('\n'),
    'proxy-groups:',
    root.join('\n'),
    groupYaml.join('\n'),
    '  - name: "⚡ Auto"',
    '    type: ' + (tagged ? 'url-test' : 'fallback'),
    '    url: https://www.gstatic.com/generate_204',
    '    interval: 300',
    '    tolerance: 150',
    '    proxies:',
    names,
    'rules:',
    '  - GEOIP,private,DIRECT,no-resolve',
    settings.blockAds ? '  - GEOSITE,category-ads-all,REJECT' : null,
    settings.blockQuic ? '  - AND,((NETWORK,udp),(DST-PORT,443)),REJECT' : null,
    settings.bypassIran ? '  - DOMAIN-SUFFIX,ir,DIRECT\n  - GEOSITE,category-ir,DIRECT\n  - GEOIP,IR,DIRECT' : null,
    '  - MATCH,🐱 Cat',
    '',
  ].filter((l) => l !== null).join('\n');
}

function buildSingboxConfig(host, env, settings, uuid, user, q) {
  const { ctx, entries, preferredCc } = buildConfigEntries(host, env, settings, uuid, user, q);
  const outbounds = entries.map((e) => {
    const out = {
      type: e.proto,
      tag: e.name,
      server: e.addr.replace(/^\[|\]$/g, ''),
      server_port: e.port,
      transport: { type: 'ws', path: e.proto === 'vless' ? ctx.paths.vlessPath : ctx.paths.trojanPath, headers: { Host: ctx.host } },
    };
    if (e.proto === 'vless') out.uuid = ctx.uuid; else out.password = ctx.trojanPass;
    if (e.tls) {
      out.tls = { enabled: true, server_name: ctx.sni, insecure: false, alpn: settings.alpn.split(','), utls: { enabled: true, fingerprint: ctx.fp } };
      if (settings.fragment.enabled) out.tls_fragment = true; // sing-box ≥1.12 TLS record fragmentation (DPI evasion)
    } else if (e.proto === 'trojan') {
      return null;
    }
    return out;
  }).filter(Boolean);
  const tags = outbounds.map((o) => o.tag);
  const usable = entries.filter((e) => e.tls || e.proto === 'vless');
  const groups = countryGroups(usable, preferredCc);
  const tagged = groups.some((g) => g.cc);
  const groupOutbounds = tagged ? groups.map((g) => ({ type: 'urltest', tag: g.name, outbounds: g.entries.map((e) => e.name), url: 'https://www.gstatic.com/generate_204', interval: '5m', tolerance: 150 })) : [];
  const rootList = tagged ? groups.map((g) => g.name).concat(['⚡ Auto']) : tags.concat(['⚡ Auto']);
  const rootDefault = tagged ? (preferredCc ? countryLabel(preferredCc) : groups[0].name) : (tags[0] || '⚡ Auto');
  return {
    log: { level: 'warn' },
    dns: {
      servers: [
        { tag: 'dns-remote', address: 'https://1.1.1.1/dns-query', detour: '🐱 Cat' },
        { tag: 'dns-direct', address: 'https://8.8.8.8/dns-query', detour: 'direct' },
      ],
      rules: [].concat(settings.blockAds ? [{ rule_set: ['geosite-ads'], action: 'reject' }] : [], settings.bypassIran ? [{ domain_suffix: ['.ir'], server: 'dns-direct' }, { rule_set: ['geosite-ir'], server: 'dns-direct' }] : []),
      final: 'dns-remote',
      independent_cache: true,
    },
    inbounds: [
      { type: 'tun', tag: 'tun-in', address: ['172.19.0.1/30'], auto_route: true, strict_route: true, stack: 'mixed', sniff: true },
      { type: 'mixed', tag: 'mixed-in', listen: '127.0.0.1', listen_port: 2080, sniff: true },
    ],
    outbounds: [
      // Default = the FIRST entry (pinned exit), not auto-select: auto picks a
      // different Cloudflare entry on every start → different exit country.
      // sing-box has no "fallback" group type: the selector defaults to the
      // preferred country's url-test; switch to ⚡ Auto by hand if it dies.
      { type: 'selector', tag: '🐱 Cat', outbounds: rootList, default: rootDefault },
      { type: 'urltest', tag: '⚡ Auto', outbounds: tags, url: 'https://www.gstatic.com/generate_204', interval: '10m', tolerance: 300 },
    ].concat(groupOutbounds, outbounds, [{ type: 'direct', tag: 'direct' }]),
    route: {
      rules: [
        { action: 'sniff' },
        { protocol: 'dns', action: 'hijack-dns' },
        { ip_is_private: true, outbound: 'direct' },
      ].concat(
        settings.blockAds ? [{ rule_set: ['geosite-ads'], action: 'reject' }] : [],
        settings.blockQuic ? { network: 'udp', port: 443, action: 'reject' } : null,
        settings.bypassIran ? [{ domain_suffix: ['.ir'], outbound: 'direct' }, { rule_set: ['geosite-ir', 'geoip-ir'], outbound: 'direct' }] : [],
      ),
      rule_set: [].concat(
        settings.blockAds ? [{ tag: 'geosite-ads', type: 'remote', format: 'binary', url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs', download_detour: '🐱 Cat' }] : [],
        settings.bypassIran ? [
          { tag: 'geosite-ir', type: 'remote', format: 'binary', url: 'https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geosite-ir.srs', download_detour: '🐱 Cat' },
          { tag: 'geoip-ir', type: 'remote', format: 'binary', url: 'https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geoip-ir.srs', download_detour: '🐱 Cat' },
        ] : [],
      ),
      final: '🐱 Cat',
      auto_detect_interface: true,
    },
  };
}

/** Full Xray JSON configs (v2rayNG / V2Box / Hiddify): fragment + TLS extras
 * + routing. Share links cannot carry these, so this endpoint exists. */
function buildXrayConfigs(host, env, settings, uuid, user, q) {
  const { ctx, entries } = buildConfigEntries(host, env, settings, uuid, user, q);
  const usable = entries.filter((e) => e.tls || e.proto === 'vless');
  const frag = settings.fragment;
  const rules = [{ type: 'field', ip: ['geoip:private'], outboundTag: 'direct' }];
  if (settings.blockAds) rules.push({ type: 'field', domain: ['geosite:category-ads-all'], outboundTag: 'block' });
  if (settings.blockQuic) rules.push({ type: 'field', network: 'udp', port: 443, outboundTag: 'block' });
  if (settings.bypassIran) rules.push({ type: 'field', domain: ['geosite:category-ir', 'domain:ir'], outboundTag: 'direct' }, { type: 'field', ip: ['geoip:ir'], outboundTag: 'direct' });
  rules.push({ type: 'field', port: '0-65535', outboundTag: 'proxy' });
  const warpOut = buildWarpOutbounds(settings);
  const fragOn = !!(frag && frag.enabled);
  const one = (e) => {
    const stream = {
      network: 'ws',
      security: e.tls ? 'tls' : 'none',
      wsSettings: { path: e.proto === 'vless' ? ctx.paths.vlessPath : ctx.paths.trojanPath, headers: { Host: ctx.host } },
      sockopt: (function () { const so = { tcpKeepAliveIdle: 100, tcpNoDelay: true }; const dial = warpOut.length ? 'warp' : (fragOn ? 'fragment' : undefined); if (dial) so.dialerProxy = dial; return so; })(),
    };
    if (e.tls) {
      stream.tlsSettings = { serverName: ctx.sni, fingerprint: ctx.fp, alpn: settings.alpn.split(','), allowInsecure: false };
      if (settings.cipherSuites) stream.tlsSettings.cipherSuites = settings.cipherSuites;
    }
    const proxy = e.proto === 'vless'
      ? { tag: 'proxy', protocol: 'vless', settings: { vnext: [{ address: e.addr, port: e.port, users: [{ id: ctx.uuid, encryption: 'none', level: 8 }] }] }, streamSettings: stream }
      : { tag: 'proxy', protocol: 'trojan', settings: { servers: [{ address: e.addr, port: e.port, password: ctx.trojanPass, level: 8 }] }, streamSettings: stream };
    const outbounds = [proxy];
    if (warpOut.length) {
      // warp dials either the hub (WARP-in-WARP) or the fragment/edge directly.
      for (const wo of warpOut) {
        if (wo.tag === 'warp' && wo.streamSettings.sockopt && !wo.streamSettings.sockopt.dialerProxy && fragOn) wo.streamSettings.sockopt.dialerProxy = 'fragment';
        outbounds.push(wo);
      }
    }
    if (frag.enabled) outbounds.push({ tag: 'fragment', protocol: 'freedom', settings: { fragment: { packets: frag.packets, length: frag.length, interval: frag.interval } }, streamSettings: { sockopt: { tcpKeepAliveIdle: 100, tcpNoDelay: true } } });
    outbounds.push({ tag: 'direct', protocol: 'freedom', settings: {} }, { tag: 'block', protocol: 'blackhole', settings: { response: { type: 'http' } } });
    return {
      remarks: e.name,
      log: { loglevel: 'warning' },
      dns: { servers: ['https://1.1.1.1/dns-query', settings.bypassIran ? { address: '8.8.8.8', domains: ['geosite:category-ir', 'domain:ir'], skipFallback: true } : 'https://8.8.8.8/dns-query'].filter(Boolean), queryStrategy: 'UseIP' },
      inbounds: [
        { tag: 'socks-in', port: 10808, listen: '127.0.0.1', protocol: 'socks', settings: { auth: 'noauth', udp: true }, sniffing: { enabled: true, destOverride: ['http', 'tls'], routeOnly: true } },
        { tag: 'http-in', port: 10809, listen: '127.0.0.1', protocol: 'http', sniffing: { enabled: true, destOverride: ['http', 'tls'], routeOnly: true } },
      ],
      outbounds,
      routing: { domainStrategy: 'IPIfNonMatch', rules },
    };
  };
  return usable.map(one);
}

/* ------------------------------------------------------------------ */
/* DoH proxy + geo                                                     */
/* ------------------------------------------------------------------ */

async function handleDoh(request, env) {
  const upstream = dohUpstream(env);
  if (request.method === 'GET') {
    const dns = new URL(request.url).searchParams.get('dns');
    if (!dns) return text('missing dns', 400);
    const res = await fetch(upstream + '?dns=' + encodeURIComponent(dns), { headers: { accept: 'application/dns-message' } });
    return new Response(res.body, { status: res.status, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
  }
  if (request.method === 'POST') {
    const res = await fetch(upstream, { method: 'POST', headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' }, body: request.body });
    return new Response(res.body, { status: res.status, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
  }
  return text('method not allowed', 405);
}

const GEO_CACHE = new Map();
const GEO_TTL_MS = 10 * 60 * 1000;
async function resolveHost(name) {
  try {
    const res = await fetch('https://cloudflare-dns.com/dns-query?name=' + encodeURIComponent(name) + '&type=A', { headers: { accept: 'application/dns-json' } });
    const data = await res.json();
    const a = (data.Answer || []).find((r) => r.type === 1);
    return a ? a.data : '';
  } catch (e) { return ''; }
}

async function geoLookup(ip) {
  const key = String(ip || '').trim().replace(/^\[|\]$/g, '');
  const hit = GEO_CACHE.get(key);
  if (hit && Date.now() - hit.at < GEO_TTL_MS) return hit.value;
  let value = { ok: false, ip: key };
  try {
    let target = key;
    if (!isIpv4(key) && !isIpv6(key)) {
      target = await resolveHost(key);
      if (!target) { GEO_CACHE.set(key, { value, at: Date.now() }); return value; }
    }
    const res = await fetch('https://ipwho.is/' + encodeURIComponent(target), { headers: { accept: 'application/json', 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION } });
    const data = await res.json();
    value = {
      ok: !!data.success,
      host: target !== key ? key : undefined,
      ip: data.ip || target,
      country: data.country || '',
      countryCode: data.country_code || '',
      city: data.city || '',
      isp: (data.connection && (data.connection.isp || data.connection.org)) || '',
      asn: (data.connection && data.connection.asn) || 0,
    };
  } catch (e) { /* keep ok:false */ }
  GEO_CACHE.set(key, { value, at: Date.now() });
  return value;
}

function selfInfo(request) {
  const cf = request.cf || {};
  return {
    ip: request.headers.get('cf-connecting-ip') || '',
    colo: cf.colo || '',
    country: cf.country || '',
    city: cf.city || '',
    asn: cf.asn || 0,
    asOrganization: cf.asOrganization || '',
    httpProtocol: cf.httpProtocol || '',
    tlsVersion: cf.tlsVersion || '',
  };
}

const SCAN_RANGES = [
  '104.16.0.0/13', '104.24.0.0/14', '172.64.0.0/13', '162.158.0.0/15',
  '131.0.72.0/22', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22',
  '141.101.64.0/18', '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20',
  '197.234.240.0/22', '198.41.128.0/17', '173.245.48.0/20', '162.159.192.0/24',
];


/* ------------------------------------------------------------------ */
/* router                                                              */
/* ------------------------------------------------------------------ */

function panelTitle(env, settings) {
  return String(settings.title || env.PANEL_TITLE || 'Cat Panel').slice(0, 60);
}

function subLinks(origin, uuid, user) {
  if (user) {
    return {
      sub: origin + '/u/' + user.id,
      sub64: origin + '/u/' + user.id + '/64',
      clash: origin + '/u/' + user.id + '/clash',
      singbox: origin + '/u/' + user.id + '/singbox',
      xray: origin + '/u/' + user.id + '/xray',
      info: origin + '/info/' + user.id,
    };
  }
  return {
    sub: origin + '/sub/' + uuid,
    sub64: origin + '/sub64/' + uuid,
    clash: origin + '/clash/' + uuid,
    singbox: origin + '/singbox/' + uuid,
    xray: origin + '/xray/' + uuid,
    info: origin + '/info/' + uuid,
  };
}

async function readJsonBody(request) {
  try { return await request.json(); } catch (e) { return null; }
}

async function subResponse(kind, host, env, settings, uuid, user, url) {
  const title = panelTitle(env, settings) + (user ? ' · ' + user.name : '');
  const headers = subscriptionHeaders(user, title, url && url.origin ? url.origin + '/info/' + uuid : '');
  const q = subQuery(url);
  if (q.ech) {
    try {
      const echList = await echConfigList(effectiveSni(host, env, settings), env);
      if (echList) settings = Object.assign({}, settings, { echList });
    } catch { /* ECH is best-effort */ }
  }
  if (kind === 'clash') {
    return new Response(buildClashYaml(host, env, settings, uuid, user, q), {
      headers: Object.assign({ 'content-type': 'text/yaml; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' }, headers),
    });
  }
  if (kind === 'xray') {
    const cfgs = buildXrayConfigs(host, env, settings, uuid, user, q);
    const body = JSON.stringify(cfgs, null, 2);
    // v2rayNG wants the JSON list base64'd like any subscription; ?raw=1 for humans.
    return text(url && url.searchParams.get('raw') === '1' ? body : b64encode(body), 200, Object.assign({ 'content-type': 'text/plain; charset=utf-8' }, headers));
  }
  if (kind === 'singbox') {
    return new Response(JSON.stringify(buildSingboxConfig(host, env, settings, uuid, user, q), null, 2), {
      headers: Object.assign({ 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' }, headers),
    });
  }
  const { entries } = buildConfigEntries(host, env, settings, uuid, user, q);
  let body = entries.map((e) => e.link).join('\n') + '\n';
  // External subs (URI lists) are appended AFTER our own configs — ?noext=1 or
  // single-exit links (?addr/?limit) skip them.
  if (!q.noext && !(q.addr && q.addr.length) && !q.limit && (settings.extSubs || []).length && kind !== 'clash' && kind !== 'singbox') {
    const results = await Promise.allSettled(settings.extSubs.slice(0, 5).map((x) => extSubContent(env, x.url)));
    const lines = [];
    for (const r of results) {
      if (r.status !== 'fulfilled') continue;
      for (const l of parseExtUris(r.value)) { lines.push(l); if (lines.length >= 100) break; }
      if (lines.length >= 100) break;
    }
    if (lines.length) body += lines.join('\n') + '\n';
  }
  const wantB64 = kind === 'sub64' || (url && url.searchParams.get('b64') === '1');
  return text(wantB64 ? b64encode(body) : body, 200, headers);
}

function blockedSubResponse(reason) {
  // Clients keep whatever they have; a 403 with a reason is enough.
  return text('subscription ' + reason, 403);
}

/* ------------------------------------------------------------------ */
/* Telegram bot — manage the panel from chat (admins only)              */
/* Costs nothing while idle: Telegram only calls the webhook when YOU    */
/* send a message; each command = the same single KV write the UI does. */
/* ------------------------------------------------------------------ */

function tgConfig(env, settings) {
  const token = (settings && settings.tgToken) || String(env.TG_BOT_TOKEN || '').trim();
  const admins = ((settings && settings.tgAdmins) || []).concat(splitCsv(env.TG_ADMIN_ID || env.TG_ADMINS));
  return token ? { token, admins: uniq(admins.map(String)) } : null;
}

async function tgSecret(token) { return (await sha256Hex('cat-tg:' + token)).slice(0, 32); }

async function tgApi(token, method, body) {
  const res = await fetch('https://api.telegram.org/bot' + token + '/' + method, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}) });
  return res.json().catch(() => ({ ok: false }));
}

function tgEsc(t) { return String(t).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

/** Executes one bot command; returns HTML text to answer with. */
/* Telegram-triggered deploys: the panel drives a GitHub Actions workflow
 * (repository_dispatch) which runs `wrangler deploy` with a Cloudflare API
 * token that lives ONLY in GitHub secrets — never in the panel, never in KV. */
function deployCfg(settings) {
  return { repo: String((settings && settings.ghRepo) || ''), workflow: String((settings && settings.ghWorkflow) || 'deploy-worker.yml'), ref: String((settings && settings.ghRef) || 'main') };
}
async function ghApi(method, path, settings, body, fetchImpl) {
  const F = fetchImpl || fetch;
  const res = await F('https://api.github.com' + path, { method, headers: { authorization: 'Bearer ' + (settings.ghPat || ''), accept: 'application/vnd.github+json', 'user-agent': 'cat-panel', 'x-github-api-version': '2022-11-28', 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  let j = null; try { j = await res.json(); } catch (e) { }
  return { status: res.status, ok: res.ok, body: j };
}
async function tgCommand(text, { origin, host, env, settings, masterUuid }) {
  const parts = String(text || '').trim().split(/\s+/);
  const cmd = (parts[0] || '').toLowerCase().replace(/@.*$/, '');
  const arg = parts.slice(1);
  const users = await readUsers(env);
  const byName = (q) => { q = String(q || '').toLowerCase(); return users.find((u) => u.id === q || u.id.startsWith(q) || u.name.toLowerCase() === q); };
  const userLine = (u) => { const r = userBlockedReason(u); const left = u.expiresAt ? Math.ceil((u.expiresAt - Date.now()) / 86400000) + 'd' : '∞'; return (r ? (r === 'expired' ? '⏰' : '⛔') : '🟢') + ' <b>' + tgEsc(u.name) + '</b> · ' + left + ' · <code>' + u.id.slice(0, 8) + '</code>'; };
  const links = (u) => { const l = subLinks(origin, masterUuid, u); return '🔗 <code>' + tgEsc(l.sub) + '</code>\n🧩 Clash: <code>' + tgEsc(l.clash) + '</code>\n📦 Xray: <code>' + tgEsc(l.xray) + '</code>' + (u ? '\nℹ️ ' + tgEsc(l.info) : ''); };
  switch (cmd) {
    case '/start': case '/help':
      return '🐱 <b>' + tgEsc(panelTitle(env, settings)) + '</b> ' + CAT_PANEL_VERSION + '\n\n' +
        '/users — list users\n/add &lt;name&gt; [days] — create user\n/renew &lt;name&gt; [days] — extend\n/toggle &lt;name&gt; — enable/disable\n/del &lt;name&gt; — delete\n/link [name] — subscription links\n/ips — clean-ip list\n/country [CC|off] — preferred exit country\n/status — panel info\n/deploy [branch] — deploy the panel via GitHub Actions\n/deploys — last deploy runs';
    case '/status': {
      const c = countrySummary(host, env, settings);
      return '🌐 ' + tgEsc(host) + '\n👥 users: ' + users.length + '\n🧹 ips: ' + settings.ips.length + '\n🌍 country: ' + (c.preferred ? flagOf(c.preferred) + ' ' + c.preferred : 'auto') + '\n🔗 chain: ' + (settings.chain ? 'on' : 'off') + '\n🛡 ads: ' + (settings.blockAds ? 'blocked' : 'off') + ' · iran: ' + (settings.bypassIran ? 'direct' : 'via vpn') + '\n💾 kv: ' + (kvBinding(env) ? 'on' : 'OFF');
    }
    case '/users':
      return users.length ? users.map(userLine).join('\n') : 'no users yet — /add <name> [days]';
    case '/add': {
      if (!arg[0]) return 'usage: /add <name> [days]';
      if (byName(arg[0])) return 'exists: ' + tgEsc(arg[0]);
      const days = Number(arg[1]) || 0;
      const user = normalizeUser({ name: arg[0], expiresAt: days ? Date.now() + days * 86400000 : 0 });
      await writeUsers(env, users.concat([user]));
      return '✅ created ' + userLine(user) + '\n\n' + links(user);
    }
    case '/renew': {
      const u = byName(arg[0]); if (!u) return 'not found';
      const days = Number(arg[1]) || 30;
      const base = u.expiresAt && u.expiresAt > Date.now() ? u.expiresAt : Date.now();
      const next = normalizeUser(Object.assign({}, u, { expiresAt: base + days * 86400000, enabled: true }));
      await writeUsers(env, users.map((x) => (x.id === u.id ? next : x)));
      return '🔁 ' + userLine(next);
    }
    case '/toggle': {
      const u = byName(arg[0]); if (!u) return 'not found';
      const next = normalizeUser(Object.assign({}, u, { enabled: !u.enabled }));
      await writeUsers(env, users.map((x) => (x.id === u.id ? next : x)));
      return (next.enabled ? '▶️ enabled ' : '⏸ disabled ') + userLine(next);
    }
    case '/del': {
      const u = byName(arg[0]); if (!u) return 'not found';
      await writeUsers(env, users.filter((x) => x.id !== u.id));
      return '🗑 deleted ' + tgEsc(u.name);
    }
    case '/link': {
      if (!arg[0]) return '👑 master\n' + links(null);
      const u = byName(arg[0]); if (!u) return 'not found';
      return userLine(u) + '\n' + links(u);
    }
    case '/ips':
      return settings.ips.length ? settings.ips.map((ip) => '<code>' + tgEsc(ip) + '</code>' + (settings.ipCountries[ip] ? ' ' + flagOf(settings.ipCountries[ip]) : '')).join('\n') : 'empty — paste ip#CC lines in the panel or send: /ips add 1.2.3.4#DE';
    case '/country': {
      if (!arg[0]) { const c = countrySummary(host, env, settings); return (c.preferred ? flagOf(c.preferred) + ' ' + c.preferred : 'auto') + '\n' + c.countries.map((x) => x.label + ' · ' + x.addresses.length).join('\n'); }
      const cc = arg[0].toLowerCase() === 'off' ? '' : normalizeCountry(arg[0]);
      if (arg[0].toLowerCase() !== 'off' && !cc) return 'usage: /country DE  |  /country off';
      await writeSettings(env, { country: cc });
      return cc ? '🌍 preferred country → ' + flagOf(cc) + ' ' + cc : '🌍 country → auto';
    }
    case '/deploy': {
      if (!settings.ghPat || !settings.ghRepo) return '⚠️ Deploy bot is not configured yet.\nIn the panel set the GitHub repo (owner/repo) + a fine-grained token with Actions read/write.\nThe Cloudflare API token is NEVER stored here — it lives only in GitHub secrets (see docs/telegram-deploy.md).';
      const ref = String(arg[0] || '').trim() || deployCfg(settings).ref;
      if (!/^[A-Za-z0-9._/-]{1,120}$/.test(ref)) return 'bad branch name';
      const r = await ghApi('POST', '/repos/' + settings.ghRepo + '/actions/workflows/' + encodeURIComponent(deployCfg(settings).workflow) + '/dispatches', settings, { ref });
      if (r.status === 204) return '🚀 Deploy queued: ' + tgEsc(settings.ghRepo + '@' + ref) + '\nThe workflow will post the result here when it finishes.';
      if (r.status === 401) return '🔴 GitHub token rejected (401) — re-check the token in the panel.';
      if (r.status === 404) return '🔴 Repo or workflow file not found (404) — check owner/repo and that ' + tgEsc(deployCfg(settings).workflow) + ' exists on branch ' + tgEsc(ref) + '.';
      return '🔴 GitHub error ' + r.status + (r.body && r.body.message ? ' — ' + tgEsc(r.body.message) : '');
    }
    case '/deploys': {
      if (!settings.ghPat || !settings.ghRepo) return '⚠️ Deploy bot is not configured yet — set the GitHub repo + token in the panel first.';
      const r = await ghApi('GET', '/repos/' + settings.ghRepo + '/actions/workflows/' + encodeURIComponent(deployCfg(settings).workflow) + '/runs?per_page=3', settings);
      const runs = (r.body && r.body.workflow_runs) || [];
      if (!r.ok) return '🔴 GitHub error ' + r.status + (r.body && r.body.message ? ' — ' + tgEsc(r.body.message) : '');
      if (!runs.length) return 'no deploy runs yet — send /deploy';
      return runs.map((x) => (x.conclusion === 'success' ? '✅' : x.conclusion === 'failure' ? '❌' : '⏳') + ' ' + x.status + (x.conclusion ? ' → ' + x.conclusion : '') + ' · ' + tgEsc(String(x.head_branch)) + ' · #' + x.run_number + '\n' + x.html_url).join('\n\n');
    }
    default:
      return 'unknown command — /help';
  }
}

async function handleTelegramWebhook(request, url, env, settings, masterUuid) {
  const cfg = tgConfig(env, settings);
  if (!cfg) return json({ ok: false, error: 'telegram not configured' }, 404);
  const secret = await tgSecret(cfg.token);
  if (url.pathname !== '/tg/' + secret || request.headers.get('x-telegram-bot-api-secret-token') !== secret) return json({ ok: false }, 403);
  const update = (await readJsonBody(request)) || {};
  const msg = update.message || update.edited_message || {};
  const chatId = msg.chat && msg.chat.id;
  const fromId = msg.from && String(msg.from.id);
  if (!chatId || !msg.text) return json({ ok: true, ignored: true });
  let reply;
  if (!cfg.admins.length) reply = '⚠️ no admin configured. Your id: <code>' + tgEsc(fromId) + '</code> — put it in panel → Telegram → admins.';
  else if (!cfg.admins.includes(fromId)) reply = '⛔ not allowed';
  else reply = await tgCommand(msg.text, { origin: url.origin, host: url.hostname, env, settings, masterUuid });
  await tgApi(cfg.token, 'sendMessage', { chat_id: chatId, text: reply, parse_mode: 'HTML', disable_web_page_preview: true });
  return json({ ok: true });
}

async function handleApi(request, url, env, ctx) {
  const path = url.pathname;
  const method = request.method.toUpperCase();
  const host = url.hostname;
  const settings = await readSettings(env);
  const masterUuid = await resolveUuid(host, env);

  if (path === '/api/health' || path === '/health') return json({ ok: true, version: CAT_PANEL_VERSION, kv: !!kvBinding(env) });
  if (path === '/api/version') {
    // Stealth hygiene: the repo URL only ships to the owner — anonymous probes get a bare version.
    const owner = await isOwner(request, env, settings, masterUuid);
    return json(owner ? { ok: true, panel: 'cat-panel', version: CAT_PANEL_VERSION, repo: REPO_URL } : { ok: true, panel: 'cat-panel', version: CAT_PANEL_VERSION });
  }
  if (path === '/api/scan-targets.json') return json({ ok: true, ranges: SCAN_RANGES, tlsPorts: TLS_PORTS, plainPorts: PLAIN_PORTS, sni: effectiveSni(host, env, settings), host });
  if (path === '/api/ech') {
    if (method !== 'GET') return json({ ok: false, error: 'method' }, 405);
    const sni = effectiveSni(host, env, settings);
    const ech = await echConfigList(sni, env);
    return json({ ok: true, sni, has: !!ech, len: ech.length });
  }

  if (path === '/api/colo') {
    // Public + free (no KV, no subrequest): which Cloudflare datacenter THIS
    // connection landed in. Cat Client's scanner calls it through each entry
    // address with the panel SNI to tag that address with a country.
    const cf = request.cf || {};
    return json({ ok: true, colo: cf.colo || '', country: cf.country || '', ip: request.headers.get('cf-connecting-ip') || '' }, 200, { 'access-control-allow-origin': '*' });
  }

  if (path === '/api/geo') {
    const ip = url.searchParams.get('ip') || request.headers.get('cf-connecting-ip') || '';
    if (!ip) return json({ ok: false, error: 'missing ip' }, 400);
    return json(await geoLookup(ip), 200, { 'cache-control': 'public, max-age=600' });
  }
  if (path === '/api/login') {
    if (method !== 'POST') return json({ ok: false, error: 'method' }, 405);
    const body = (await readJsonBody(request)) || {};
    const ok = await checkLogin(env, settings, masterUuid, body.username, body.password);
    if (!ok) return json({ ok: false, error: 'invalid' }, 401);
    const token = await makeSession(env, settings, masterUuid);
    return json({ ok: true }, 200, { 'set-cookie': sessionCookieHeader(token) });
  }
  if (path === '/api/logout') {
    return json({ ok: true }, 200, { 'set-cookie': SESSION_COOKIE + '=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax' });
  }

  /* ---- everything below is owner-only ---- */
  if (!(await isOwner(request, env, settings, masterUuid))) return json({ ok: false, error: 'unauthorized' }, 401);
  const origin = url.origin;

  if (path === '/api/self') return json(Object.assign({ ok: true }, selfInfo(request)));

  if (path === '/api/settings') {
    if (method === 'GET') {
      const users = await readUsers(env);
      return json({
        ok: true,
        version: CAT_PANEL_VERSION,
        host,
        uuid: masterUuid,
        kv: !!kvBinding(env),
        open: panelIsOpen(env, settings),
        passwordSource: (await panelPassword(env, settings, masterUuid)).source,
        settings: Object.assign({}, settings, { passwordHash: undefined, hasPassword: !!settings.passwordHash, tgToken: settings.tgToken ? '••••' + settings.tgToken.slice(-4) : '', ghPat: settings.ghPat ? '••••' + settings.ghPat.slice(-4) : '' }),
        telegram: { configured: !!tgConfig(env, settings) },
        defaults: { addresses: DEFAULT_CLEAN_ADDRESSES, proxyIps: DEFAULT_PROXY_IPS, tlsPorts: TLS_PORTS, plainPorts: PLAIN_PORTS },
        links: subLinks(origin, masterUuid, null),
        paths: tunnelPaths(env),
        sni: effectiveSni(host, env, settings),
        chain: (() => { const c = parseChain(settings.chain); return c ? { type: c.type, host: c.host, port: c.port, auth: !!(c.user || c.pass) } : null; })(),
        countries: countrySummary(host, env, settings),
        userCount: users.length,
        env: { hasUuid: isUuid(env.UUID), hasPanelPassword: !!env.PANEL_PASSWORD, hasProxyIp: !!(env.PROXYIP || env.PROXY_IPS), hasCfIps: !!env.CF_IPS },
      });
    }
    if (method === 'PUT' || method === 'POST') {
      const body = (await readJsonBody(request)) || {};
      const patch = Object.assign({}, body);
      delete patch.passwordHash;
      if (typeof patch.tgToken === 'string' && /^•/.test(patch.tgToken)) delete patch.tgToken;
      if (typeof patch.ghPat === 'string' && /^•/.test(patch.ghPat)) delete patch.ghPat;
      if (typeof body.password === 'string') {
        patch.passwordHash = body.password.trim() ? await sha256Hex(body.password.trim()) : '';
      }
      delete patch.password;
      const saved = await writeSettings(env, patch);
      const extra = {};
      if (typeof body.password === 'string') {
        // Password changed → old sessions die; hand back a fresh one.
        extra['set-cookie'] = sessionCookieHeader(await makeSession(env, saved.settings, masterUuid));
      }
      await pushEvent(env, 'settings', 'update');
      return json({ ok: true, persisted: saved.persisted, settings: Object.assign({}, saved.settings, { passwordHash: undefined, hasPassword: !!saved.settings.passwordHash, tgToken: saved.settings.tgToken ? '••••' + saved.settings.tgToken.slice(-4) : '', ghPat: saved.settings.ghPat ? '••••' + saved.settings.ghPat.slice(-4) : '' }) }, 200, extra);
    }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/events') {
    if (method === 'GET') return json({ ok: true, events: await readEvents(env) });
    if (method === 'POST') { const b = (await readJsonBody(request)) || {}; await pushEvent(env, b.ev, b.d); return json({ ok: true }); }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/repos') {
    const kv = kvBinding(env);
    if (method === 'GET') {
      const cache = await readJsonKv(env, REPO_CACHE_KEY, { ts: 0, per: {}, ips: [] });
      const health = await readJsonKv(env, REPO_HEALTH_KEY, { f: {} });
      const pool = await repoHealthyPool(env, 500);
      const ccs = {};
      for (const p of pool) if (p.cc) ccs[p.cc] = (ccs[p.cc] || 0) + 1;
      return json({ ok: true, kv: !!kv, ts: cache.ts || 0, ttlH: 12, total: pool.length, dead: Object.keys(health.f || {}).length, auto: settings.repoAuto === true, repos: (settings.repos || []).map((r) => { const st = (cache.per || {})[r.id]; return Object.assign({}, r, { count: st ? st.count : null, ok: st ? st.ok !== false : null, ts: st ? st.ts : 0 }); }), ccs });
    }
    if (method === 'POST') {
      const body = (await readJsonBody(request)) || {};
      if (body.action === 'refresh') return json(await refreshRepos(env));
      if (body.action === 'set') {
        const repos = sanitizeRepos(body.repos);
        const saved = await writeSettings(env, { repos });
        await pushEvent(env, 'repo-set', repos.length + ' repos');
        return json({ ok: true, persisted: saved.persisted, repos });
      }
      if (body.action === 'auto') {
        const saved = await writeSettings(env, { repoAuto: body.enabled === true });
        await pushEvent(env, 'repo-auto', body.enabled === true ? 'on' : 'off');
        return json({ ok: true, persisted: saved.persisted, auto: saved.settings.repoAuto === true });
      }
      if (body.action === 'health') {
        const health = await readJsonKv(env, REPO_HEALTH_KEY, { f: {} });
        const dead = (Array.isArray(body.dead) ? body.dead : splitCsv(body.dead)).map((x) => String(x).trim()).filter(Boolean);
        const cache = await readJsonKv(env, REPO_CACHE_KEY, { ts: 0, per: {}, ips: [] });
        // One report = +1 fail. At 3 fails the entry leaves the pool immediately
        // AND stays blocked in future refreshes until the feed drops it.
        const alive = (cache.ips || []).filter((it) => {
          const t = splitAddrTag(String(it));
          let ip = t.addr; const pin = pinnedPortOf(ip);
          if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
          if (!dead.includes(ip)) return true;
          health.f[ip] = (health.f[ip] || 0) + 1;
          return health.f[ip] < REPO_FAILS_DROP;
        });
        const removed = (cache.ips || []).length - alive.length;
        if (kv) {
          await kv.put(REPO_HEALTH_KEY, JSON.stringify({ f: health.f }));
          if (removed) await kv.put(REPO_CACHE_KEY, JSON.stringify(Object.assign({}, cache, { ips: alive, ts: cache.ts || Date.now() })));
        }
        await pushEvent(env, 'repo-dead', dead.length + ' reported' + (removed ? ' · ' + removed + ' dropped' : ''));
        return json({ ok: true, reported: dead.length, dropped: removed, total: alive.length });
      }
      if (body.action === 'import') {
        const ccw = normalizeCountry(body.cc) || '';
        const limit = Math.min(24, Math.max(1, Number(body.limit) || 16));
        const pool = (await repoHealthyPool(env, 500)).filter((p) => (ccw ? p.cc === ccw : true)).slice(0, limit);
        if (!pool.length) return json({ ok: false, error: 'empty' }, 404);
        const cc = {};
        for (const p of pool) if (p.cc) cc[p.ip] = p.cc;
        const saved = await writeSettings(env, { ips: uniq(settings.ips.concat(pool.map((p) => p.ip))).slice(0, 400), ipCountries: Object.assign({}, settings.ipCountries, cc) });
        await pushEvent(env, 'repo-import', (ccw || 'all') + ' ' + pool.length);
        return json({ ok: true, persisted: saved.persisted, added: pool.length, total: saved.settings.ips.length });
      }
      return json({ ok: false, error: 'action' }, 400);
    }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/prepos') {
    const kv = kvBinding(env);
    if (method === 'GET') {
      const cache = await readJsonKv(env, PROXY_REPO_CACHE_KEY, { ts: 0, per: {}, ips: [] });
      const health = await readJsonKv(env, PROXY_REPO_HEALTH_KEY, { f: {} });
      const pool = await proxyRepoHealthyPool(env, 400);
      const ccs = {};
      for (const p of pool) if (p.cc) ccs[p.cc] = (ccs[p.cc] || 0) + 1;
      return json({ ok: true, kv: !!kv, ts: cache.ts || 0, ttlH: 12, total: pool.length, dead: Object.keys(health.f || {}).length, auto: settings.proxyRepoAuto === true, repos: (settings.proxyRepos || []).map((r) => { const st = (cache.per || {})[r.id]; return Object.assign({}, r, { count: st ? st.count : null, ok: st ? st.ok !== false : null, ts: st ? st.ts : 0 }); }), ccs });
    }
    if (method === 'POST') {
      const body = (await readJsonBody(request)) || {};
      if (body.action === 'refresh') return json(await refreshProxyRepos(env));
      if (body.action === 'set') {
        const repos = sanitizeProxyRepos(body.repos);
        const saved = await writeSettings(env, { proxyRepos: repos });
        await pushEvent(env, 'prepo-set', repos.length + ' repos');
        return json({ ok: true, persisted: saved.persisted, repos });
      }
      // Import the healthy ProxyIP pool AS connection addresses (💦 configs).
      // A ProxyIP is worker-side relay first — but any pool IP that also
      // terminates TLS for our hostname works as an entry; users test in the
      // app. Country tags ride along (ip#CC) so names/flags keep working.
      if (body.action === 'toAddrs') {
        const cap = Math.min(256, Math.max(1, Number(body.limit) || 64));
        const pool = await proxyRepoHealthyPool(env, cap);
        if (!pool.length) return json({ ok: true, added: 0, count: (settings.ips || []).length });
        const tags = Object.assign({}, settings.ipCountries);
        const clean = pool.map((p) => (p.cc ? p.ip + '#' + p.cc : p.ip)).map((raw) => { const t = splitAddrTag(raw); if (t.cc) tags[t.addr] = t.cc; return t.addr; }).filter((a) => { const pin = pinnedPortOf(a); let b = pin ? a.slice(0, a.lastIndexOf(':')) : a; b = b.replace(/^\[/, '').replace(/\]$/, ''); return isIpv4(b) || isIpv6(b) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(b); });
        const next = uniq(clean.concat(settings.ips || [])).slice(0, 400);
        const saved = await writeSettings(env, { ips: next, ipCountries: tags });
        await pushEvent(env, 'px-to-addrs', '+' + Math.max(0, next.length - (settings.ips || []).length) + ' ips');
        return json({ ok: true, added: Math.max(0, next.length - (settings.ips || []).length), count: saved.settings.ips.length, persisted: saved.persisted });
      }
      if (body.action === 'auto') {
        const saved = await writeSettings(env, { proxyRepoAuto: body.enabled === true });
        await pushEvent(env, 'prepo-auto', body.enabled === true ? 'on' : 'off');
        return json({ ok: true, persisted: saved.persisted, auto: saved.settings.proxyRepoAuto === true });
      }
      if (body.action === 'health') {
        const health = await readJsonKv(env, PROXY_REPO_HEALTH_KEY, { f: {} });
        const dead = (Array.isArray(body.dead) ? body.dead : splitCsv(body.dead)).map((x) => String(x).trim()).filter(Boolean);
        const cache = await readJsonKv(env, PROXY_REPO_CACHE_KEY, { ts: 0, per: {}, ips: [] });
        const alive = (cache.ips || []).filter((it) => {
          const t = splitAddrTag(String(it));
          let ip = t.addr; const pin = pinnedPortOf(ip);
          if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
          if (!dead.includes(ip)) return true;
          health.f[ip] = (health.f[ip] || 0) + 1;
          return health.f[ip] < PROXY_REPO_FAILS_DROP;
        });
        const removed = (cache.ips || []).length - alive.length;
        if (kv) {
          await kv.put(PROXY_REPO_HEALTH_KEY, JSON.stringify({ f: health.f }));
          if (removed) await kv.put(PROXY_REPO_CACHE_KEY, JSON.stringify(Object.assign({}, cache, { ips: alive, ts: cache.ts || Date.now() })));
        }
        await pushEvent(env, 'prepo-dead', dead.length + ' reported' + (removed ? ' · ' + removed + ' dropped' : ''));
        return json({ ok: true, reported: dead.length, dropped: removed, total: alive.length });
      }
      if (body.action === 'import') {
        const ccw = normalizeCountry(body.cc) || '';
        const limit = Math.min(16, Math.max(1, Number(body.limit) || 8));
        const pool = (await proxyRepoHealthyPool(env, 400)).filter((p) => (ccw ? p.cc === ccw : true)).slice(0, limit);
        if (!pool.length) return json({ ok: false, error: 'empty' }, 404);
        const cc = {};
        for (const p of pool) if (p.cc) cc[p.ip] = p.cc;
        const saved = await writeSettings(env, { proxyIps: uniq(settings.proxyIps.concat(pool.map((p) => p.ip))).slice(0, 32), proxyCountries: Object.assign({}, settings.proxyCountries, cc) });
        await pushEvent(env, 'prepo-import', (ccw || 'all') + ' ' + pool.length);
        return json({ ok: true, persisted: saved.persisted, added: pool.length, total: saved.settings.proxyIps.length });
      }
      return json({ ok: false, error: 'action' }, 400);
    }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/ips' && method === 'POST') {
    const body = (await readJsonBody(request)) || {};
    // Entries may carry a country tag: "1.2.3.4#DE" (what Cat Client's scanner
    // saw via /cdn-cgi/trace) → stored in ipCountries, address stays clean.
    const tags = Object.assign({}, settings.ipCountries);
    const incoming = uniq((Array.isArray(body.ips) ? body.ips : splitCsv(body.ips)).map((raw) => { const t = splitAddrTag(raw); if (t.cc) tags[t.addr] = t.cc; return t.addr; }).filter((s) => { const pin = pinnedPortOf(s); let a = pin ? s.slice(0, s.lastIndexOf(':')) : s; a = a.replace(/^\[/, '').replace(/\]$/, ''); return isIpv4(a) || isIpv6(a) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(a); }));
    if (body.countries && typeof body.countries === 'object') Object.assign(tags, normalizeCountryMap(body.countries, 500));
    const next = body.replace ? incoming : uniq(incoming.concat(settings.ips));
    // Provenance: where each IP came from + latency measured on the SENDER's
    // network («🏷 از اسکنر · 📶 320ms از شبکهٔ فرستنده») + worker checks later
    // write their own status via /api/ip-test. Shown per-row in the IP list.
    const src = String(body.source || '').slice(0, 24);
    const pings = (body.pingMs && typeof body.pingMs === 'object') ? body.pingMs : {};
    const srcMap = Object.assign({}, settings.ipSources || {});
    for (const k of Object.keys(pings).slice(0, 400)) if (pings[k] != null) srcMap[k] = { src: src || 'import', ms: Number(pings[k]) || 0, at: Date.now() };
    const saved = await writeSettings(env, { ips: next, ipCountries: tags, ipSources: srcMap });
    await pushEvent(env, body.replace ? 'ips-replace' : 'ips-add', String(next.length) + ' ips');
    return json({ ok: true, persisted: saved.persisted, count: saved.settings.ips.length, ips: saved.settings.ips });
  }

  if (path === '/api/users' || path.startsWith('/api/users/')) {
    const users = await readUsers(env);
    const id = path.split('/')[3] ? decodeURIComponent(path.split('/')[3]).toLowerCase() : '';
    const action = path.split('/')[4] || '';
    const decorate = (u, lastOnline) => Object.assign({}, u, { status: userBlockedReason(u) || 'active', lastOnline: lastOnline || 0, links: subLinks(origin, masterUuid, u) });
    if (method === 'GET' && !id) {
      const seen = await Promise.all(users.map((u) => readSeen(env, u.id)));
      return json({ ok: true, users: users.map((u, i) => decorate(u, seen[i])) });
    }
    if (method === 'POST' && !id) {
      const body = (await readJsonBody(request)) || {};
      const user = normalizeUser({
        id: isUuid(body.id) ? body.id : crypto.randomUUID(),
        name: body.name,
        note: body.note,
        enabled: body.enabled,
        protocols: body.protocols,
        expiresAt: body.days ? Date.now() + Number(body.days) * 86400000 : (Number(body.expiresAt) || 0),
      });
      if (findUser(users, user.id)) return json({ ok: false, error: 'exists' }, 409);
      const saved = await writeUsers(env, users.concat([user]));
      await pushEvent(env, 'user-add', user.name || user.id);
      return json({ ok: true, persisted: saved.persisted, user: decorate(user) }, 201);
    }
    if (!id) return json({ ok: false, error: 'method' }, 405);
    const existing = findUser(users, id);
    if (!existing) return json({ ok: false, error: 'not found' }, 404);
    existing.lastOnline = await readSeen(env, existing.id);
    if (method === 'DELETE') {
      const saved = await writeUsers(env, users.filter((u) => u.id !== id));
      await pushEvent(env, 'user-del', existing.name || id);
      return json({ ok: true, persisted: saved.persisted });
    }
    if (method === 'PUT' || method === 'PATCH' || method === 'POST') {
      const body = (await readJsonBody(request)) || {};
      let next = Object.assign({}, existing);
      if (action === 'renew') {
        const days = Number(body.days) || 30;
        const base = existing.expiresAt && existing.expiresAt > Date.now() ? existing.expiresAt : Date.now();
        next.expiresAt = base + days * 86400000;
        next.enabled = true;
      } else if (action === 'toggle') {
        next.enabled = !existing.enabled;
      } else {
        if (typeof body.name === 'string') next.name = body.name;
        if (typeof body.note === 'string') next.note = body.note;
        if (typeof body.enabled === 'boolean') next.enabled = body.enabled;
        if (body.protocols) next.protocols = body.protocols;
        if (body.days !== undefined) next.expiresAt = Number(body.days) ? Date.now() + Number(body.days) * 86400000 : 0;
        else if (body.expiresAt !== undefined) next.expiresAt = Number(body.expiresAt) || 0;
      }
      next = normalizeUser(next);
      const saved = await writeUsers(env, users.map((u) => (u.id === id ? next : u)));
      return json({ ok: true, persisted: saved.persisted, user: decorate(next) });
    }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/backup') {
    if (method === 'GET') {
      const users = await readUsers(env);
      return json({ ok: true, version: CAT_PANEL_VERSION, exportedAt: new Date().toISOString(), settings, users }, 200, {
        'content-disposition': 'attachment; filename="cat-panel-backup.json"',
      });
    }
    if (method === 'POST') {
      const body = (await readJsonBody(request)) || {};
      let persisted = true;
      if (body.settings && typeof body.settings === 'object') persisted = (await writeSettings(env, body.settings)).persisted && persisted;
      if (Array.isArray(body.users)) persisted = (await writeUsers(env, body.users)).persisted && persisted;
      return json({ ok: true, persisted });
    }
  }

  if (path === '/api/countries' && method === 'GET') {
    return json(Object.assign({ ok: true }, countrySummary(host, env, settings)));
  }

  if (path === '/api/countries' && (method === 'PUT' || method === 'POST')) {
    // { country, countryFallback, ipCountries?, proxyCountries? } — ONE KV write.
    const body = (await readJsonBody(request)) || {};
    const patch = {};
    if ('country' in body) patch.country = normalizeCountry(body.country) || '';
    if ('countryFallback' in body) patch.countryFallback = body.countryFallback === 'none' ? 'none' : 'auto';
    if (body.ipCountries && typeof body.ipCountries === 'object') patch.ipCountries = Object.assign({}, settings.ipCountries, normalizeCountryMap(body.ipCountries, 500));
    if (body.proxyCountries && typeof body.proxyCountries === 'object') patch.proxyCountries = Object.assign({}, settings.proxyCountries, normalizeCountryMap(body.proxyCountries, 64));
    if (body.clearIp) { patch.ipCountries = Object.assign({}, settings.ipCountries); for (const a of splitCsv(body.clearIp)) delete patch.ipCountries[a]; }
    const saved = await writeSettings(env, patch);
    return json(Object.assign({ ok: true, persisted: saved.persisted }, countrySummary(host, env, saved.settings)));
  }

  if (path === '/api/proxy-geo' && method === 'POST') {
    // Owner click: geo-locate the proxy ips (≤32 cached lookups) and tag them.
    const list = proxyIpList(env, settings).slice(0, 32);
    const found = {};
    for (const p of list) {
      try { const g = await geoLookup(splitHostPort(p, 443).hostname); const cc = normalizeCountry(g && (g.country_code || g.countryCode)); if (cc) found[p] = cc; } catch (e) { /* skip */ }
    }
    const saved = await writeSettings(env, { proxyCountries: Object.assign({}, settings.proxyCountries, found) });
    return json({ ok: true, persisted: saved.persisted, proxyCountries: saved.settings.proxyCountries, found });
  }

  if (path === '/api/telegram' && method === 'GET') {
    const cfg = tgConfig(env, settings);
    return json({ ok: true, configured: !!cfg, admins: cfg ? cfg.admins : [], fromEnv: !settings.tgToken && !!env.TG_BOT_TOKEN });
  }
  if (path === '/api/telegram/webhook' && method === 'POST') {
    // Owner click: register <origin>/tg/<secret> with Telegram (one subrequest).
    const cfg = tgConfig(env, settings);
    if (!cfg) return json({ ok: false, error: 'set the bot token first' }, 400);
    const secret = await tgSecret(cfg.token);
    const r = await tgApi(cfg.token, 'setWebhook', { url: origin + '/tg/' + secret, secret_token: secret, allowed_updates: ['message'], drop_pending_updates: true });
    const me = r.ok ? await tgApi(cfg.token, 'getMe', {}) : null;
    return json({ ok: !!r.ok, description: r.description || '', bot: me && me.ok ? me.result.username : '' }, r.ok ? 200 : 502);
  }

  if (path === '/api/ip-test' && method === 'POST') {
    // «تست از سمت ورکر»: dial each ip:port from Cloudflare's edge — TCP/TLS
    // reachability + latency + the real exit country. The OTHER half of the
    // two-sided check (the app already proves the IP from the user's network).
    const body = (await readJsonBody(request)) || {};
    const list = (Array.isArray(body.ips) ? body.ips : splitCsv(body.ips)).map((x) => String(x).trim()).filter(Boolean).slice(0, 64);
    if (!list.length) return json({ ok: true, results: {} });
    const sockets = await loadSockets();
    if (!sockets) return json({ ok: false, error: 'cloudflare:sockets unavailable' }, 501);
    const results = {};
    await Promise.all(list.map(async (raw) => {
      const t = splitAddrTag(raw);
      const hostPort = pinnedPortOf(t.addr) ? t.addr : (t.addr + ':443');
      const hp = splitHostPort(hostPort, 443);
      const t0 = Date.now();
      try {
        const sock = sockets.connect({ hostname: hp.hostname, port: hp.port }, { allowHalfOpen: false });
        await sock.opened;
        try { sock.close(); } catch (e) { }
        results[raw] = { ok: true, ms: Date.now() - t0 };
      } catch (e) {
        results[raw] = { ok: false, ms: Date.now() - t0, error: String(e && e.message ? e.message : e).slice(0, 80) };
      }
      try { const g = await geoLookup(hp.hostname); if (g && g.ok && g.countryCode) results[raw].cc = normalizeCountry(g.countryCode) || ''; } catch (e) { }
    }));
    await pushEvent(env, 'ip-test', Object.values(results).filter((r) => r.ok).length + '/' + list.length + ' reachable');
    return json({ ok: true, results });
  }

  if (path === '/api/chain-test' && method === 'POST') {
    // One outbound connection through the chain; reports whether the handshake
    // works. Costs the owner one click, never runs on its own.
    const body = (await readJsonBody(request)) || {};
    const chain = parseChain(body.chain || settings.chain);
    if (!chain) return json({ ok: false, error: 'invalid chain url' }, 400);
    const sockets = await loadSockets();
    if (!sockets) return json({ ok: false, error: 'cloudflare:sockets unavailable (preview?)' }, 501);
    const t0 = Date.now();
    try {
      const dialed = await dialViaChain(sockets, chain, 'www.gstatic.com', 80);
      const writer = dialed.socket.writable.getWriter();
      await writer.write(new TextEncoder().encode('GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n'));
      writer.releaseLock();
      const reader = dialed.socket.readable.getReader();
      const { value } = await reader.read();
      reader.releaseLock();
      try { dialed.socket.close(); } catch (e) { /* ignore */ }
      const head = new TextDecoder().decode(toBytes(value || new Uint8Array(0))).split('\r\n')[0];
      return json({ ok: /HTTP\/1\.[01] 204/.test(head), status: head, ms: Date.now() - t0, type: chain.type, host: chain.host });
    } catch (e) {
      return json({ ok: false, error: String(e && e.message ? e.message : e), ms: Date.now() - t0 }, 502);
    }
  }

  if (path === '/api/update-download') {
    try {
      const res = await fetchNewestPanelSource();
      if (!res) return json({ ok: false, error: 'all sources failed' }, 502);
      return new Response(res.body, { headers: { 'content-type': 'text/javascript; charset=utf-8', 'content-disposition': 'attachment; filename="catclient.worker.js"', 'cache-control': 'no-store' } });
    } catch (e) {
      return json({ ok: false, error: 'fetch failed' }, 502);
    }
  }
  if (path === '/api/update-check') {
    try {
      const res = await fetchNewestPanelSource();
      if (!res) return json({ ok: false, error: 'all sources failed' }, 502);
      const src = await res.text();
      const m = src.match(/CAT_PANEL_VERSION\s*=\s*'([^']+)'/);
      return json({ ok: true, current: CAT_PANEL_VERSION, latest: m ? m[1] : '', source: PANEL_SOURCE_URL });
    } catch (e) {
      return json({ ok: false, error: 'fetch failed' }, 502);
    }
  }

  return json({ ok: false, error: 'not found' }, 404);
}

async function handleRequest(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;
  const host = url.hostname;

  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS', 'access-control-allow-headers': 'content-type,authorization,x-cat-key' } });
  }

  /* data plane */
  const upgrade = (request.headers.get('Upgrade') || '').toLowerCase();
  if (upgrade === 'websocket' && isTunnelPath(path, env)) {
    const settings = await readSettings(env);
    const masterUuid = await resolveUuid(host, env);
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    const job = handleTunnelConnection(server, env, {
      earlyDataHeader: request.headers.get('sec-websocket-protocol') || '',
      masterUuid,
      settings,
      path: url.pathname + (url.search || ''),
    }).catch(() => { safeCloseWs(server, 1011, 'internal'); });
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job);
    const headers = {};
    const protocol = request.headers.get('sec-websocket-protocol');
    if (protocol) headers['sec-websocket-protocol'] = protocol;
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  if (path === '/dns-query') return handleDoh(request, env);
  if (path === '/robots.txt') return text('User-agent: *\nDisallow: /\n');
  if (path.startsWith('/tg/') && request.method === 'POST') { const settings = await readSettings(env); return handleTelegramWebhook(request, url, env, settings, await resolveUuid(url.hostname, env)); }
  if (path === '/health' || path.startsWith('/api/')) return handleApi(request, url, env, ctx);

  if (path === '/qr.svg' || path === '/qr') {
    const payload = url.searchParams.get('text') || url.searchParams.get('data') || '';
    if (!payload) return text('missing text', 400);
    return new Response(qrSvg(payload.slice(0, 2000), { dark: '#0b0614', light: '#ffffff' }), { headers: { 'content-type': 'image/svg+xml', 'cache-control': 'public, max-age=86400' } });
  }

  const settings = await readSettings(env);
  const masterUuid = await resolveUuid(host, env);

  /* external-sub fetch-through: /ext/<n>/<key> — key = master uuid or a user
   * token (same gating as /sub). Content cached 12h, size-capped, so clients
   * can pull blocked raw-GitHub subs through the panel's own domain. */
  const extm = path.match(/^\/ext\/([0-9]+)(?:\/([^/?]+))?\/?$/);
  if (extm) {
    const idx = Math.min(4, Math.max(0, Number(extm[1]) - 1));
    const key = (extm[2] || url.searchParams.get('k') || '').toLowerCase();
    const own = key === masterUuid;
    let subUser = null;
    if (!own && key && isUuid(key)) subUser = findUser(await readUsers(env), key);
    if (!own && !subUser) return text('not found', 404);
    const sub = settings.extSubs[idx];
    if (!sub) return text('no such ext sub', 404);
    try {
      const content = await extSubContent(env, sub.url);
      return text(content, 200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*', 'subscription-userinfo': 'total=0' });
    } catch (e) {
      return json({ ok: false, error: 'fetch', message: String((e && e.message) || e) }, 502);
    }
  }

  /* Subscription format the way BPB does it: the SAME link works for every
   * client — ?target= beats the User-Agent, the UA decides otherwise
   * (clash/mihomo/stash → yaml, sing-box/SFI/SFA/Hiddify → singbox). */
  const uaKind = () => {
    const ua = (request.headers.get('user-agent') || '').toLowerCase();
    if (/clash|mihomo|stash/.test(ua)) return 'clash';
    if (/sing-?box|\bsfi\b|\bsfa\b|hiddify/.test(ua)) return 'singbox';
    return '';
  };
  const targetKind = (fallback) => {
    const map = { clash: 'clash', mihomo: 'clash', stash: 'clash', singbox: 'singbox', 'sing-box': 'singbox', sfi: 'singbox', sfa: 'singbox', xray: 'xray', json: 'xray', base64: 'sub64', '64': 'sub64', sub: 'sub' };
    const qp = (url.searchParams.get('target') || url.searchParams.get('flag') || '').toLowerCase();
    return map[qp] || uaKind() || fallback;
  };

  /* master subscriptions: /sub/<uuid> /sub64/<uuid> /clash/<uuid> /singbox/<uuid> */
  const master = path.match(/^\/(sub|sub64|clash|singbox|xray)(?:\/([^/]+))?\/?$/);
  if (master) {
    const kind = targetKind(master[1]);
    const key = (master[2] || '').toLowerCase();
    if (key === masterUuid || (!key && isTrue(env.OPEN_SUB))) return subResponse(kind, host, env, await withProxyRepoPool(env, await withRepoPool(env, settings, url), url), masterUuid, null, url);
    if (key && isUuid(key)) {
      // Allow a user token on the master paths too (v2rayNG users sometimes edit the URL).
      const user = findUser(await readUsers(env), key);
      if (user) {
        const blocked = userBlockedReason(user);
        return blocked ? blockedSubResponse(blocked) : subResponse(kind, host, env, await withProxyRepoPool(env, await withRepoPool(env, settings, url), url), user.id, user, url);
      }
    }
    return text('not found', 404);
  }

  /* user subscriptions: /u/<token>[/clash|/singbox|/64] */
  const per = path.match(/^\/u\/([^/]+)(?:\/(clash|singbox|xray|64))?\/?$/);
  if (per) {
    const token = decodeURIComponent(per[1]).toLowerCase();
    const kind = targetKind(per[2] === '64' ? 'sub64' : (per[2] || 'sub'));
    if (token === masterUuid) return subResponse(kind, host, env, await withProxyRepoPool(env, await withRepoPool(env, settings, url), url), masterUuid, null, url);
    const user = findUser(await readUsers(env), token);
    if (!user) return text('not found', 404);
    const blocked = userBlockedReason(user);
    if (blocked) return blockedSubResponse(blocked);
    return subResponse(kind, host, env, await withProxyRepoPool(env, await withRepoPool(env, settings, url), url), user.id, user, url);
  }

  /* per-user landing page */
  const info = path.match(/^\/info\/([^/]+)\/?$/);
  if (info) {
    const token = decodeURIComponent(info[1]).toLowerCase();
    const user = token === masterUuid ? null : findUser(await readUsers(env), token);
    if (token !== masterUuid && !user) return html(notFoundPage(), 404);
    return html(userInfoPage(url.origin, host, env, settings, token, user));
  }

  /* Stealth mode: when settings.panelPath is set, the panel UI only exists at
   * /<panelPath>. Everything else — including /, /login and /panel — answers
   * with a bare, brand-free 404 so workers.dev crawlers and automated
   * abuse-reporters have nothing to fingerprint. The data plane (/api, /sub,
   * /u, tunnels, DoH) is untouched. */
  const pp = String(settings.panelPath || '');
  const atPanel = pp !== '' && (path === '/' + pp || path === '/' + pp + '/');
  if (pp !== '' && !atPanel) {
    if (path === '/logout') {
      return new Response(null, { status: 302, headers: { location: '/' + pp, 'set-cookie': SESSION_COOKIE + '=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax' } });
    }
    return stealthNotFound();
  }
  const panelBase = atPanel ? '/' + pp : '/';

  if (path === '/logout') {
    return new Response(null, { status: 302, headers: { location: panelBase, 'set-cookie': SESSION_COOKIE + '=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax' } });
  }

  if (path === '/' || path === '/login' || path === '/panel' || atPanel) {
    // Legacy ?p=<password> entry: set the cookie and redirect to a clean URL.
    const quick = url.searchParams.get('p');
    if (quick && (await checkLogin(env, settings, masterUuid, url.searchParams.get('u') || '', quick))) {
      const token = await makeSession(env, settings, masterUuid);
      return new Response(null, { status: 302, headers: { location: panelBase + (atPanel ? '/' : ''), 'set-cookie': sessionCookieHeader(token) } });
    }
    const owner = await isOwner(request, env, settings, masterUuid);
    if (!owner) return html(loginPage(env, settings, !!String(env.PANEL_USER || '').trim()));
    maybeRepoRefresh(env, ctx);
    maybeProxyRepoRefresh(env, ctx);
    return html(panelPage(env, settings, host, masterUuid));
  }

  return html(notFoundPage(), 404);
}


/* ------------------------------------------------------------------ */
/* HTML                                                                */
/* ------------------------------------------------------------------ */

const BASE_CSS = `
:root{--bg:#000000;--bg2:#000000;--card:#0a0a0d;--card2:#121216;--line:rgba(255,255,255,.06);--line2:rgba(255,255,255,.16);--text:#ffffff;--mute:#c9c9ce;--dim:#8f8f96;
--violet:#00e1c1;--violet2:#2ef2d6;--fuchsia:#00b398;--pink:#b9a6ff;--green:#00e1c1;--amber:#ffab00;--red:#ff6b6b;--cyan:#9db4ff;--blue:#9db4ff;--lime:#2ef2d6;
--r:14px;--sh:0 8px 32px rgba(0,0,0,.6);--input-bg:#121216;--nav-bg:rgba(0,225,193,.14);--glow:rgba(0,225,193,.4);--flat:#0a0a0d;--b05:rgba(255,255,255,.05)}
*{box-sizing:border-box;margin:0;padding:0}
html{-webkit-text-size-adjust:100%}
body{background:var(--bg);color:var(--text);-apple-system,BlinkMacSystemFont,'SF Pro Display','SF Pro Text',system-ui,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;min-height:100vh;line-height:1.5}
a{color:var(--violet2);text-decoration:none}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none}
input,select,textarea{font:inherit;color:var(--text);background:var(--input-bg);border:1px solid var(--line);border-radius:12px;padding:11px 14px;width:100%;outline:none;transition:border-color .2s,box-shadow .2s;font-size:13px}
input:focus,select:focus,textarea:focus{border-color:var(--glow);box-shadow:0 0 0 4px rgba(0,225,193,.08)}
textarea{min-height:110px;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px;direction:ltr;text-align:left}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;direction:ltr;unicode-bidi:embed}
.card{background:var(--flat);border:1px solid var(--b05);border-radius:16px;box-shadow:var(--sh)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:9px 18px;border-radius:14px;border:1px solid var(--line);background:var(--input-bg);color:var(--text);font-weight:600;font-size:12px;transition:background .2s,border-color .2s;white-space:nowrap;-webkit-tap-highlight-color:transparent}
.btn:hover{background:var(--nav-bg);border-color:var(--glow)}.btn:active{background:var(--nav-bg)}
.btn.p{background:var(--input-bg);border-color:var(--line);color:var(--text)}
.btn.g,.btn.r,.btn.a,.btn.c{background:var(--input-bg);border-color:var(--line);color:var(--text)}
.btn.sm{padding:5px 12px;font-size:11px;border-radius:10px}
.btn:disabled{opacity:.55;cursor:not-allowed}
.btn,.chip,.nav button,.pick button,.ib,.side a,.side button,th,td{-webkit-user-select:none;user-select:none}
button,a,.btn,.chip,.side a,.side button{outline:none}
.chip{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:20px;font-size:10px;font-weight:600;border:0;background:rgba(255,255,255,.06);color:var(--mute)}
.chip.v{color:var(--cyan);background:rgba(43,127,255,.15)}.chip.t{color:var(--pink);background:rgba(123,97,255,.15)}
.chip.ok{color:#34c759;background:rgba(22,163,74,.15)}.chip.bad{color:#ff6b6b;background:rgba(255,35,82,.15)}.chip.warn{color:var(--amber);background:rgba(255,171,0,.15)}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.grid{display:grid;gap:14px}
.mute{color:var(--mute)}.dim{color:var(--dim)}.small{font-size:12px}.b{font-weight:700}
.sw{position:relative;width:46px;height:26px;border-radius:999px;background:#2a2342;border:1px solid var(--line2);transition:background .15s;flex:none}
.sw::after{content:"";position:absolute;top:3px;inset-inline-start:3px;width:18px;height:18px;border-radius:50%;background:#fff;transition:transform .15s}
.sw.on{background:linear-gradient(135deg,var(--violet),var(--fuchsia));border-color:transparent}
[dir=rtl] .sw.on::after{transform:translateX(-20px)}[dir=ltr] .sw.on::after{transform:translateX(20px)}
.toast{position:fixed;bottom:22px;inset-inline-start:50%;transform:translateX(-50%);background:#1a1430;border:1px solid var(--violet);color:#fff;padding:10px 18px;border-radius:12px;z-index:99;box-shadow:var(--sh);font-size:14px;opacity:0;transition:opacity .2s;pointer-events:none;max-width:92vw;text-align:center}
[dir=rtl] .toast{transform:translateX(50%)}
.toast.show{opacity:1}
.qrbox{background:#fff;border-radius:14px;padding:10px;display:inline-block;line-height:0}
.qrbox img,.qrbox svg{width:220px;height:220px;max-width:70vw;max-height:70vw}
`;

function loginPage(env, settings, needsUser) {
  const fa = settings.lang !== 'en';
  const title = panelTitle(env, settings);
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title><style>${BASE_CSS}
.wrap{min-height:100vh;display:grid;place-items:center;padding:20px}
.box{width:100%;max-width:380px;padding:28px 24px}
.logo{width:56px;height:56px;display:grid;place-items:center;margin:0 auto 14px;filter:drop-shadow(0 10px 26px rgba(0,225,193,.28))}
h1{font-size:17px;font-weight:700;text-align:center}.sub{text-align:center;margin-bottom:22px}
label{display:block;font-size:11px;font-weight:600;color:var(--mute);margin:12px 0 6px;text-transform:uppercase;letter-spacing:.5px}
.err{color:#fda4af;font-size:13px;min-height:18px;margin-top:10px;text-align:center}
</style></head><body><div class="wrap"><form class="card box" id="f">
<div class="logo">${catLogo(1)}</div><h1>${escapeHtml(title)}</h1><div class="sub mute small">${fa ? 'برای ورود رمز پنل را وارد کن' : 'Enter the panel password'}</div>
${needsUser ? `<label>${fa ? 'نام کاربری' : 'Username'}</label><input id="u" autocomplete="username">` : ''}
<label>${fa ? 'رمز عبور' : 'Password'}</label><input id="p" type="password" autocomplete="current-password" autofocus>
<div class="err" id="e"></div>
<button class="btn p" style="width:100%;margin-top:6px" type="submit">${fa ? 'ورود' : 'Sign in'}</button>
<div class="dim small" style="text-align:center;margin-top:16px">${fa ? 'رمز پیش‌فرض همان UUID پنل است' : 'Default password is the panel UUID'}</div>
</form></div>
<script>
document.getElementById('f').addEventListener('submit',function(ev){ev.preventDefault();var e=document.getElementById('e');e.textContent='';
var u=document.getElementById('u');fetch('/api/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({password:document.getElementById('p').value,username:u?u.value:''})})
.then(function(r){return r.json()}).then(function(j){if(j.ok)location.href=location.pathname+location.search;else e.textContent=${JSON.stringify(fa ? 'رمز اشتباه است' : 'Wrong password')}}).catch(function(){e.textContent='network'})});
</script></body></html>`;
}

function notFoundPage() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>404</title><style>${BASE_CSS}body{display:grid;place-items:center;min-height:100vh}</style></head><body><div style="text-align:center"><div class="mute">404</div></div></body></html>`;
}

/** Bare nginx-style 404 used in stealth mode — no branding, no engine hints. */
function stealthNotFound() {
  return new Response('<html>\r\n<head><title>404 Not Found</title>\r\n</head>\r\n<body>\r\n<center><h1>404 Not Found</h1>\r\n</center>\r\n<hr>\r\n<center>nginx</center>\r\n</body>\r\n</html>\r\n', { status: 404, headers: { 'content-type': 'text/html', 'cache-control': 'max-age=0, private' } });
}

function userInfoPage(origin, host, env, settings, token, user) {
  const fa = settings.lang !== 'en';
  const title = panelTitle(env, settings);
  const links = subLinks(origin, token, user);
  const name = user ? user.name : (fa ? 'اشتراک اصلی' : 'Master subscription');
  const blocked = user ? userBlockedReason(user) : null;
  const expires = user && user.expiresAt ? new Date(user.expiresAt) : null;
  const daysLeft = expires ? Math.ceil((expires.getTime() - Date.now()) / 86400000) : null;
  const t = fa ? {
    sub: 'لینک اشتراک (همهٔ کلاینت‌ها)', clash: 'Clash / Mihomo', singbox: 'sing-box / Hiddify', xray: 'Xray کامل (v2rayNG / V2Box — با Fragment)', copy: 'کپی', qr: 'QR', open: 'باز کردن در Cat Client',
    never: 'بدون انقضا', left: 'روز مانده', expired: 'منقضی شده', disabled: 'غیرفعال', active: 'فعال', apps: 'باز کردن در', hint: 'لینک را کپی کن و در کلاینت از بخش «افزودن اشتراک» وارد کن.',
  } : {
    sub: 'Subscription link (all clients)', clash: 'Clash / Mihomo', singbox: 'sing-box / Hiddify', xray: 'Full Xray (v2rayNG / V2Box — with fragment)', copy: 'Copy', qr: 'QR', open: 'Open in Cat Client',
    never: 'never expires', left: 'days left', expired: 'expired', disabled: 'disabled', active: 'active', apps: 'Open in', hint: 'Copy the link and add it as a subscription in your client.',
  };
  const status = blocked === 'expired' ? ['bad', t.expired] : blocked === 'disabled' ? ['bad', t.disabled] : ['ok', t.active];
  const linkRow = (label, url) => `<div class="lk"><div class="small mute">${label}</div><div class="row" style="flex-wrap:nowrap"><input class="mono" readonly value="${escapeHtml(url)}"><button class="btn sm" data-copy="${escapeHtml(url)}">${t.copy}</button><button class="btn sm" data-qr="${escapeHtml(url)}">${t.qr}</button></div></div>`;
  const catLink = 'catclient://add-sub?url=' + encodeURIComponent(links.sub) + '&name=' + encodeURIComponent(title + ' ' + name);
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)} · ${escapeHtml(name)}</title><style>${BASE_CSS}
.wrap{max-width:640px;margin:0 auto;padding:22px 14px 60px}
.head{display:flex;align-items:center;gap:14px;margin-bottom:18px}
.logo{width:52px;height:52px;display:grid;place-items:center;flex:none}
.lk{padding:12px 14px;border-top:1px solid var(--line)}.lk:first-child{border-top:0}
.lk input{font-size:12px}
.exp{padding:14px;display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:14px}
.bar{height:8px;border-radius:999px;background:#241d3b;overflow:hidden;margin-top:8px}.bar i{display:block;height:100%;background:linear-gradient(90deg,var(--violet),var(--fuchsia))}
.apps{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px;padding:14px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.7);display:none;place-items:center;z-index:50;padding:20px}.modal.show{display:grid}
</style></head><body><div class="wrap">
<div class="head"><div class="logo">${catLogo(2)}</div><div><div class="b" style="font-size:20px">${escapeHtml(name)}</div><div class="mute small">${escapeHtml(title)} · ${escapeHtml(host)}</div></div><span class="chip ${status[0]}" style="margin-inline-start:auto">${status[1]}</span></div>
<div class="card exp"><div><div class="small mute">${fa ? 'اعتبار زمانی' : 'Validity'}</div><div class="b">${expires ? (daysLeft > 0 ? daysLeft + ' ' + t.left : t.expired) : t.never}</div>${expires ? `<div class="dim small mono">${expires.toISOString().slice(0, 10)}</div>` : ''}</div><div style="font-size:32px">${expires ? '⏳' : '♾️'}</div></div>
<div class="card">
${linkRow(t.sub, links.sub)}
${linkRow(t.clash, links.clash)}
${linkRow(t.singbox, links.singbox)}
${linkRow(t.xray, links.xray)}
</div>
<div class="card" style="margin-top:14px"><div class="small mute" style="padding:12px 14px 0">${t.apps}</div><div class="apps">
<a class="btn p" href="${escapeHtml(catLink)}">🐱 Cat Client</a>
<a class="btn" href="v2rayng://install-sub?url=${encodeURIComponent(links.sub)}&name=${encodeURIComponent(name)}">v2rayNG</a>
<a class="btn" href="hiddify://import/${escapeHtml(links.sub)}#${encodeURIComponent(name)}">Hiddify</a>
<a class="btn" href="streisand://import/${escapeHtml(links.sub)}#${encodeURIComponent(name)}">Streisand</a>
<a class="btn" href="clash://install-config?url=${encodeURIComponent(links.clash)}&name=${encodeURIComponent(name)}">Clash</a>
<a class="btn" href="sing-box://import-remote-profile?url=${encodeURIComponent(links.singbox)}#${encodeURIComponent(name)}">sing-box</a>
</div></div>
<div class="dim small" style="margin-top:14px;text-align:center">${t.hint}</div>
</div>
<div class="modal" id="m" onclick="this.classList.remove('show')"><div class="qrbox" id="qr"></div></div>
<div class="toast" id="toast"></div>
<script>
function toast(m){var t=document.getElementById('toast');t.textContent=m;t.classList.add('show');clearTimeout(t._t);t._t=setTimeout(function(){t.classList.remove('show')},1800)}
function copy(v){if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(v).then(function(){toast('✓')},function(){fallback(v)})}else fallback(v)}
function fallback(v){var i=document.createElement('textarea');i.value=v;document.body.appendChild(i);i.select();try{document.execCommand('copy');toast('✓')}catch(e){}document.body.removeChild(i)}
document.addEventListener('click',function(e){var b=e.target.closest('[data-copy]');if(b){copy(b.getAttribute('data-copy'));return}var q=e.target.closest('[data-qr]');if(q){document.getElementById('qr').innerHTML='<img src="/qr.svg?text='+encodeURIComponent(q.getAttribute('data-qr'))+'">';document.getElementById('m').classList.add('show')}});
</script></body></html>`;
}

function panelPage(env, settings, host, masterUuid) {
  const fa = settings.lang !== 'en';
  const title = panelTitle(env, settings);
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#07060d"><title>${escapeHtml(title)}</title>
<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(catLogo('f'))}">
<style>${BASE_CSS}
.top{position:sticky;top:0;z-index:20;background:var(--flat);border-bottom:1px solid var(--line)}
.topin{max-width:1180px;margin:0 auto;padding:10px 14px;display:flex;align-items:center;gap:10px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:16px}
.brand .lg{width:36px;height:36px;display:grid;place-items:center}
.brand .v{font-size:10px;color:var(--mute);background:var(--input-bg);border:1px solid var(--line);padding:2px 8px;border-radius:20px;font-weight:600}
.tools{display:flex;gap:8px;margin-inline-start:auto;flex-wrap:wrap;justify-content:flex-end}
.burger{display:none}
.top.menu-open .tools{display:grid;position:absolute;top:100%;left:0;right:0;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;padding:12px 14px;background:var(--flat);border-bottom:1px solid var(--line);box-shadow:0 18px 40px rgba(0,0,0,.5)}
@media(max-width:1079px){.tools{display:none}.burger{display:grid}}
.ib{width:36px;height:36px;border-radius:12px;display:grid;place-items:center;border:1px solid var(--line);background:var(--input-bg);color:var(--text);transition:background .2s,border-color .2s}
.ib:hover{background:var(--nav-bg);border-color:var(--glow)}
.ib.on{background:var(--nav-bg);border-color:var(--glow);color:var(--violet)}
.ib svg{width:16px;height:16px}
.main{max-width:1180px;margin:0 auto;padding:16px 14px 90px}
.view{display:none}.view.on{display:block}
.sec{padding:16px 18px;margin-bottom:14px}
.sec h2{font-size:13px;font-weight:700;display:flex;align-items:center;gap:8px;margin-bottom:12px;color:var(--text)}
.sec h2 .ic{display:none}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
.st{padding:14px 10px;border-radius:16px;background:var(--flat);border:1px solid var(--b05);position:relative;overflow:hidden;text-align:center;transition:border-color .3s,transform .3s}
.st:hover{border-color:var(--glow);transform:translateY(-3px)}
.st .k{font-size:11px;color:var(--mute)}
.st .n{font-size:26px;font-weight:800;color:var(--violet);margin-top:2px}
.st .s{font-size:11px;color:var(--dim);margin-top:2px}
.st .ic{display:none}
.st[data-c=violet] .n,.st[data-c=green] .n,.st[data-c=amber] .n,.st[data-c=cyan] .n,.st[data-c=pink] .n{color:var(--violet)}
.bar{height:6px;border-radius:999px;background:rgba(255,255,255,.08);overflow:hidden}.bar i{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#00e1c1,#2ef2d6)}
.bar.w i{background:linear-gradient(90deg,#ffab00,#ffd54d)}.bar.d i{background:linear-gradient(90deg,#ff6b6b,#ff9b9b)}
.fab{width:44px;height:44px;border-radius:14px;display:grid;place-items:center;font-size:18px;border:1px solid var(--line);background:var(--input-bg);color:var(--text)}
.fab:hover{background:var(--nav-bg);border-color:var(--glow)}
.tbl{width:100%;border-collapse:separate;border-spacing:0 6px}
.tbl th{font-size:11px;color:var(--mute);font-weight:600;padding:4px 10px;text-align:start;text-transform:uppercase;letter-spacing:.4px}
.tbl td{background:var(--flat);padding:10px;border-top:1px solid var(--b05);border-bottom:1px solid var(--b05);vertical-align:middle}
[dir=rtl] .tbl td:first-child,[dir=ltr] .tbl td:last-child{border-inline-end:1px solid var(--b05);border-start-end-radius:14px;border-end-end-radius:14px}
[dir=rtl] .tbl td:last-child,[dir=ltr] .tbl td:first-child{border-inline-start:1px solid var(--b05);border-start-start-radius:14px;border-end-start-radius:14px}
.tbl tr:hover td{border-color:rgba(255,255,255,.16)}
.act{display:flex;gap:6px;flex-wrap:wrap}
.act .ib{width:30px;height:30px}
.ucard{display:none}
@media(max-width:860px){.tbl{display:none}.ucard{display:block}}
.uc{padding:12px 14px;margin-bottom:10px;background:var(--flat);border:1px solid var(--b05);border-radius:16px}
.uc .hd{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.uc .hd .nm{font-weight:700;font-size:14px}
.kv{display:grid;grid-template-columns:1fr 1fr;gap:8px;font-size:12px;margin:8px 0}
.kv div span{display:block;color:var(--dim);font-size:11px}
.frm label{display:block;font-size:11px;font-weight:600;color:var(--mute);margin:12px 0 6px;text-transform:uppercase;letter-spacing:.5px}
.frm .two{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:640px){.frm .two{grid-template-columns:1fr}}
.pick{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.pick button{padding:5px 12px;border-radius:10px;border:1px solid var(--line);background:var(--input-bg);font-size:11px;font-weight:600;color:var(--text)}
.pick button.on{background:var(--nav-bg);border-color:var(--glow);color:var(--violet)}
.proto{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.proto label{display:flex;align-items:center;gap:10px;margin:0;padding:12px;border:1px solid var(--b05);border-radius:14px;background:var(--flat);cursor:pointer;color:var(--text)}
.proto label.on{border-color:var(--glow);background:var(--nav-bg)}
.proto .ic{display:none}
.drawer{position:fixed;inset:0;z-index:40;display:none}.drawer.show{display:block}
.drawer .bg{position:absolute;inset:0;background:rgba(0,0,0,.65)}
.drawer .pn{position:absolute;top:0;bottom:0;inset-inline-end:0;width:min(520px,100%);background:var(--bg);border-inline-start:1px solid var(--line);overflow:auto;padding:18px 16px 40px;box-shadow:var(--sh)}
.drawer .pn h3{display:flex;align-items:center;gap:10px;font-size:15px;font-weight:700;margin-bottom:6px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.72);display:none;place-items:center;z-index:50;padding:20px}.modal.show{display:grid}
.modal .in{text-align:center}
.note{padding:10px 12px;border-radius:12px;font-size:12px;border:1px solid}
.note.i{background:rgba(43,127,255,.12);border-color:rgba(43,127,255,.25);color:var(--cyan)}
.note.w{background:rgba(255,171,0,.12);border-color:rgba(255,171,0,.25);color:var(--amber)}
.note.e{background:rgba(255,35,82,.12);border-color:rgba(255,35,82,.25);color:var(--red)}
.note.g{background:rgba(22,163,74,.12);border-color:rgba(22,163,74,.25);color:var(--green)}
.lk{display:flex;gap:8px;align-items:center;padding:8px 0;border-top:1px solid var(--line)}.lk:first-child{border-top:0}
.lk input{font-size:12px;flex:1}
.ipl{display:flex;flex-wrap:wrap;gap:6px;max-height:220px;overflow:auto;padding:4px 0}
.ipl .chip{cursor:pointer;background:var(--input-bg);color:var(--text);font-size:11px}
.ipl .chip:hover{background:var(--nav-bg);border-color:var(--glow)}
.res{display:grid;gap:8px}
.res div{padding:10px 12px;border:1px solid var(--b05);border-radius:12px;background:var(--flat);font-size:12px}
.nav{position:fixed;bottom:0;inset-inline:0;z-index:25;display:flex;background:rgba(0,0,0,.94);border-top:1px solid var(--line);padding:6px 8px calc(6px + env(safe-area-inset-bottom))}
.nav button{flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;padding:6px 2px;border-radius:10px;font-size:9.5px;color:var(--mute);background:none;border:0}
.nav button.on{color:var(--violet)}
.side{position:fixed;top:0;bottom:0;inset-inline-start:0;width:260px;background:var(--flat);border-inline-end:1px solid var(--line);display:none;flex-direction:column;z-index:30;overflow-y:auto;overflow-x:hidden;box-shadow:0 8px 32px rgba(0,0,0,.6)}
.side .sbrand{display:flex;align-items:center;gap:10px;padding:16px 18px;border-bottom:1px solid var(--line);font-weight:700;font-size:15px}
.side .sbrand .lg{width:32px;height:32px;display:grid;place-items:center}
.side a,.side button{display:flex;align-items:center;gap:10px;padding:11px 14px;border-radius:12px;border:0;background:transparent;color:var(--mute);font-size:13px;font-weight:500;cursor:pointer;text-align:start;width:100%}
.side a:hover,.side button:hover{color:var(--text);background:rgba(255,255,255,.03)}
.side button.on,.side a.on{color:var(--violet);background:var(--nav-bg);border:1px solid var(--glow)}
.side a svg,.side button svg{width:18px;height:18px;flex:0 0 auto}
.sb-nav{flex:1;min-height:0;overflow-y:auto;display:flex;flex-direction:column;gap:2px;padding:10px}
.side .sb-nav button.on{color:var(--violet);background:var(--nav-bg);border:1px solid var(--glow)}
.nav-acc{margin:0 -10px}
.nav-acc-head{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 18px;font-size:13px;font-weight:600;color:var(--text);background:#111;border:0;border-bottom:1px solid #2a2a2a;cursor:pointer;width:100%;text-align:start}
.nav-acc-head .ar{color:var(--dim);font-size:11px}
.nav-acc-body{display:none;flex-direction:column;background:#000}
[dir=rtl] .nav-acc-body{padding:0}
.nav-acc-body button{border-radius:0!important;border:0!important;border-bottom:1px solid #1d1d1d!important;background:#000!important;margin:0!important;padding:11px 16px!important}
.nav-acc-body button.on{background:#171717!important;color:#fff!important;box-shadow:inset 3px 0 0 #fff;border:0!important}
[dir=rtl] .nav-acc-body button.on{box-shadow:inset -3px 0 0 #fff}
.sb-foot{padding:16px 20px;border-top:1px solid var(--line);font-size:11px;color:var(--mute);display:flex;align-items:center;gap:6px}
.sb-foot .dot{width:8px;height:8px;border-radius:50%;background:var(--green);display:inline-block;animation:sbpulse 2s infinite}
@keyframes sbpulse{0%,100%{opacity:1}50%{opacity:.3}}
.side .sb-out{color:var(--red)!important}
.side .sgap{flex:1}
.side .sfoot{font-size:10px;color:var(--dim);padding:8px 12px}
@media(min-width:1080px){
 .side{display:flex}
 .top{display:none}
 .nav{display:none}
 .main{margin-inline-start:260px}
}
.search{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
.search input{flex:1;min-width:200px}.search select{width:auto}
.hr{height:1px;background:var(--line);margin:14px 0}
.empty{text-align:center;padding:40px 10px;color:var(--dim)}
.empty div{font-size:40px}
code{background:var(--input-bg);border:1px solid var(--line);border-radius:6px;padding:1px 6px;font-size:12px;direction:ltr;unicode-bidi:embed}
.skel{height:14px;border-radius:6px;background:linear-gradient(90deg,var(--input-bg),var(--line),var(--input-bg));background-size:200% 100%;animation:sk 1.2s infinite}
@keyframes sk{0%{background-position:200% 0}100%{background-position:-200% 0}}
</style></head><body>
<div class="side" id="sideNav">
 <div class="sbrand"><div class="lg">${catLogo(3)}</div><span id="sideTitle">${escapeHtml(title)}</span></div>
 <div class="sb-nav">
  <button data-view="dash"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"/></svg> <span data-i="n_dash"></span></button>
  <div class="nav-acc">
   <button type="button" class="nav-acc-head" onclick="var b=document.getElementById('accBody');var o=b.style.display!=='flex';b.style.display=o?'flex':'none';this.querySelector('.ar').textContent=o?'▴':'▾'"><span><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg> <span data-i="n_manage"></span></span><span class="ar">▾</span></button>
   <div class="nav-acc-body" id="accBody" style="display:flex">
    <button data-view="clients"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2m8-10a4 4 0 100-8 4 4 0 000 8zm13 10v-2a4 4 0 00-3-3.87m-4-12a4 4 0 010 7.75"/></svg> <span data-i="n_clients"></span></button>
    <button data-view="inbounds"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg> <span data-i="n_inbounds"></span></button>
    <button data-view="scan"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/><path d="M11 8v3l2 2"/></svg> <span data-i="n_scan"></span></button>
    <button data-view="build"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 000 1.4l1.6 1.6a1 1 0 001.4 0l3.77-3.77a6 6 0 01-7.94 7.94l-6.91 6.91a2.12 2.12 2.12 0 01-3-3l6.91-6.91a6 6 0 017.94-7.94l-3.76 3.76z"/></svg> <span data-i="n_build"></span></button>
    <button data-view="nodes"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="5" r="3"/><circle cx="5" cy="19" r="3"/><circle cx="19" cy="19" r="3"/><line x1="12" y1="8" x2="5" y2="16"/><line x1="12" y1="8" x2="19" y2="16"/></svg> <span data-i="n_nodes"></span></button>
    <button data-view="spoof"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v4m0 12v4M2 12h4m12 0h4"/><circle cx="12" cy="12" r="4"/></svg> <span data-i="n_spoof"></span></button>
    <button data-view="settings"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 1v2m0 18v2M4.22 4.22l1.42 1.42m12.72 12.72l1.42 1.42M1 12h2m18 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg> <span data-i="n_set"></span></button>
   </div>
  </div>
  <button data-view="backup"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><path d="M17 21v-8H7v8M7 3v5h8"/></svg> <span data-i="n_bak"></span></button>
  <button data-view="about"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4m0-4h.01"/></svg> <span data-i="n_about"></span></button>
 </div>
 <a class="sb-out" href="/logout">⏻ <span data-i="n_logout"></span></a>
 <div class="sb-foot"><span class="dot"></span> Cat Panel v${CAT_PANEL_VERSION}</div>
</div>
<div class="top"><div class="topin">
 <div class="brand"><div class="lg">${catLogo(4)}</div><span id="brandTitle">${escapeHtml(title)}</span><span class="v">v${CAT_PANEL_VERSION}</span></div>
 <div class="tools">
  <button class="ib" data-c="violet" data-view="dash" title="Dashboard"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"/></svg></button>
  <button class="ib" data-c="green" data-view="clients" title="Clients"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2m8-10a4 4 0 100-8 4 4 0 000 8zm13 10v-2a4 4 0 00-3-3.87m-4-12a4 4 0 010 7.75"/></svg></button>
  <button class="ib" data-c="blue" data-view="inbounds" title="Inbounds"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg></button>
  <button class="ib" data-c="cyan" data-view="scan" title="Clean IP"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/><path d="M11 8v3l2 2"/></svg></button>
  <button class="ib" data-c="lime" data-view="build" title="Config builder"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 000 1.4l1.6 1.6a1 1 0 001.4 0l3.77-3.77a6 6 0 01-7.94 7.94l-6.91 6.91a2.12 2.12 2.12 0 01-3-3l6.91-6.91a6 6 0 017.94-7.94l-3.76 3.76z"/></svg></button>
  <button class="ib" data-c="lime" data-view="spoof" title="SNI &amp; ProxyIP"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v4m0 12v4M2 12h4m12 0h4"/><circle cx="12" cy="12" r="4"/></svg></button>
  <button class="ib" data-c="gray" data-view="settings" title="Settings"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 1v2m0 18v2M4.22 4.22l1.42 1.42m12.72 12.72l1.42 1.42M1 12h2m18 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg></button>
  <button class="ib" data-c="amber" data-view="backup" title="Backup"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><path d="M17 21v-8H7v8M7 3v5h8"/></svg></button>
  <button class="ib" data-c="green" id="btnUpdate" title="Update"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5m-7 7l7-7 7 7"/></svg></button>
  <button class="ib" id="btnRot" title="Fixed IP"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z"/></svg></button>
  <button class="ib" data-c="blue" id="btnLang" title="Language"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z"/></svg></button>
  <button class="ib" data-c="pink" data-view="about" title="About"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4m0-4h.01"/></svg></button>
  <a class="ib" data-c="red" href="/logout" title="Logout"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4m7 14l5-5-5-5m5 5H9"/></svg></a>
 </div>
 <button class="ib burger" id="btnBurger" title="Menu" aria-label="Menu"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16"/></svg></button>
</div></div>

<div class="main">

<!-- ================= DASHBOARD ================= -->
<section class="view on" id="v-dash">
 <div class="card sec" id="heroCard">
  <h2><span class="ic">⚡</span><span data-i="hero_inuse"></span><button class="btn sm" type="button" data-view="build" style="margin-inline-start:auto">🛠 <span data-i="hero_build"></span></button></h2>
  <div class="row" id="heroChips" style="flex-wrap:wrap;gap:8px;margin-top:6px"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">📊</span><span data-i="stats"></span></h2>
  <div class="note w" id="noIpsNote" style="display:none;margin-top:10px"><span data-i="no_ips"></span> <button class="btn sm" data-view="nodes" style="vertical-align:middle">🕸️ <span data-i="n_nodes"></span></button></div>
  <div class="stats">
   <div class="st" data-c="violet"><div class="ic">👥</div><div class="k" data-i="st_users"></div><div class="n" id="stUsers">–</div><div class="s" data-i="st_users_s"></div></div>
   <div class="st" data-c="green"><div class="ic">✅</div><div class="k" data-i="st_active"></div><div class="n" id="stActive">–</div><div class="s" data-i="st_active_s"></div></div>
   <div class="st" data-c="amber"><div class="ic">⏳</div><div class="k" data-i="st_exp"></div><div class="n" id="stExp">–</div><div class="s" data-i="st_exp_s"></div></div>
   <div class="st" data-c="cyan"><div class="ic">📡</div><div class="k" data-i="st_ips"></div><div class="n" id="stIps">–</div><div class="s" id="stIpsS"></div></div>
   <div class="st" data-c="pink"><div class="ic">🧩</div><div class="k" data-i="st_cfg"></div><div class="n" id="stCfg">–</div><div class="s" id="stCfgS"></div></div>
  </div>
  <div class="hr"></div>
  <div class="row" style="justify-content:space-between">
   <div class="row small"><span class="chip" id="chipKv"></span><span class="chip" id="chipPass"></span><span class="chip mono" id="chipHost"></span></div>
   <div class="row"><button class="btn sm c" id="btnMasterLinks" data-i="master_links"></button><button class="btn sm" id="btnSelf" data-i="self"></button></div>
  </div>
  <div id="selfBox" class="small mute" style="margin-top:8px"></div>
 </div>

 <div class="card sec">
  <h2><span class="ic">🖥</span><span data-i="ov_info"></span></h2>
  <div class="kv" style="margin:6px 0">
   <div><span data-i="ov_loc"></span><span class="mono" id="ovLoc">…</span></div>
   <div><span data-i="ov_up"></span><span id="ovUp">…</span></div>
   <div><span data-i="ov_ver"></span><span class="mono" id="ovVer">${CAT_PANEL_VERSION}</span></div>
   <div><span>KV</span><span class="chip" id="ovKv">…</span></div>
  </div>
  <div class="row" style="margin-top:10px">
   <button class="btn sm" type="button" id="btnOvUpdate" data-i="ov_check"></button>
   <button class="btn sm" type="button" data-view="backup" data-i="s_backup"></button>
   <button class="btn sm" type="button" data-view="settings" data-i="settings"></button>
  </div>
  <div class="small mute" id="ovUpdateBox" style="margin-top:8px"></div>
 </div>

 <div class="card sec">
  <h2><span class="ic">🟢</span><span data-i="ov_services"></span></h2>
  <div class="row" id="ovServices" style="flex-wrap:wrap;gap:8px;margin-top:4px"></div>
 </div>

 <div class="card sec">
  <h2><span class="ic">🧾</span><span data-i="ev_title"></span><button class="btn sm" id="evRefresh" type="button" style="margin-inline-start:auto">⟳</button></h2>
  <table class="tbl"><thead><tr><th data-i="ev_time"></th><th data-i="ev_ev"></th><th data-i="ev_d"></th></tr></thead><tbody id="evRows"></tbody></table>
  <div class="small dim" id="evEmpty" data-i="ev_empty" style="display:none"></div>
 </div>
</section>

<!-- ================= CLIENTS (users) ================= -->
<section class="view" id="v-clients">
 <div class="card sec">
  <div class="row" style="justify-content:space-between;margin-bottom:12px">
   <h2 style="margin:0"><span class="ic">👥</span><span data-i="users"></span></h2>
   <div class="row">
    <button class="fab" data-c="green" id="btnAdd" title="+">＋</button>
    <button class="fab" data-c="blue" id="btnBulk" title="Add bulk">🧑‍🤝‍🧑</button>
    <button class="fab" data-c="violet" id="btnRefresh" title="refresh">🔄</button>
    <button class="fab" data-c="cyan" id="btnSync" title="sync">🚀</button>
   </div>
  </div>
  <div class="search">
   <input id="q" data-ph="search">
   <select id="flt"><option value="all" data-i="f_all"></option><option value="active" data-i="f_active"></option><option value="expired" data-i="f_expired"></option><option value="disabled" data-i="f_disabled"></option></select>
   <select id="srt"><option value="new" data-i="s_new"></option><option value="exp" data-i="s_exp"></option><option value="name" data-i="s_name"></option></select>
  </div>
  <table class="tbl"><thead><tr>
   <th data-i="h_user"></th><th data-i="h_proto"></th><th data-i="h_links"></th><th data-i="h_time"></th><th data-i="h_seen"></th><th data-i="h_status"></th><th data-i="h_act"></th>
  </tr></thead><tbody id="rows"></tbody></table>
  <div class="ucard" id="cards"></div>
  <div class="empty" id="empty" style="display:none"><div>🐾</div><div data-i="no_users"></div></div>
 </div>
</section>

<!-- ================= INBOUNDS ================= -->
<section class="view" id="v-inbounds">
 <div class="card sec">
  <h2><span class="ic">🧩</span><span data-i="n_inbounds"></span></h2>
  <div class="stats" style="margin-top:8px">
   <div class="st" data-c="cyan"><div class="ic">🧩</div><div class="k" data-i="ib_count"></div><div class="n" id="ibCount">–</div><div class="s">VLESS · Trojan</div></div>
   <div class="st" data-c="green"><div class="ic">🔌</div><div class="k" data-i="ib_ports"></div><div class="n" id="ibPorts">–</div><div class="s">TLS</div></div>
   <div class="st" data-c="violet"><div class="ic">👥</div><div class="k" data-i="st_users"></div><div class="n" id="ibUsers">–</div><div class="s" data-i="st_users_s"></div></div>
  </div>
  <div class="hr"></div>
  <table class="tbl"><thead><tr>
   <th data-i="ib_inbound"></th><th>Endpoint</th><th data-i="h_proto"></th><th data-i="h_act"></th>
  </tr></thead><tbody id="ibRows"></tbody></table>
  <div class="small dim" style="margin-top:8px" data-i="ib_hint"></div>
 </div>
</section>

<!-- ================= CLEAN IP / SCAN ================= -->
<section class="view" id="v-scan">
 <div class="card sec">
  <h2><span class="ic">📡</span><span data-i="scan_title"></span><button class="btn sm" id="btnLocRefresh" type="button" style="margin-inline-start:auto" data-i="loc_refresh"></button></h2>
  <div class="row" style="align-items:center;gap:8px;margin-bottom:10px"><span class="chip v" id="locNow" style="font-size:12px">…</span></div>
  <div class="note i" data-i="scan_why"></div>
  <label data-i="scan_cat"></label>
  <div class="pick" id="scanFam"><button type="button" data-v="" class="on" data-i="b_both"></button><button type="button" data-v="v4">IPv4</button><button type="button" data-v="v6">IPv6</button></div>
  <label data-i="scan_region"></label>
  <div class="pick" id="scanRegion"></div>
  <label data-i="scan_cc"></label>
  <div class="pick" id="scanCc"></div>
  <div class="two" style="margin-top:12px">
   <div><label data-i="scan_search"></label><input id="scanSearch" data-ph="scan_search_ph"></div>
   <div><label data-i="scan_cidr"></label><input id="scanCidr" class="mono" dir="ltr" data-ph="scan_cidr_ph"></div>
  </div>
  <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr));margin-top:12px">
   <a class="btn p" id="btnScanApp" href="#">📱 <span data-i="scan_app"></span></a>
   <button class="btn c" id="btnBrowserTest" type="button">🌐 <span data-i="scan_browser"></span></button>
   <button class="btn" id="btnCidrAdd" type="button">➕ <span data-i="cidr_add"></span></button>
   <a class="btn" href="https://github.com/${REPO}#clean-ip" target="_blank" rel="noopener">📖 <span data-i="scan_guide"></span></a>
  </div>
  <div class="note w small" style="margin-top:10px" data-i="scan_note_browser"></div>
  <div id="scanRes" class="res" style="margin-top:12px"></div>
  <div id="scanList" style="margin-top:6px"></div>
 </div>
</section>

<!-- ================= CONFIG BUILDER ================= -->
<section class="view" id="v-build">
 <div class="card sec">
  <h2><span class="ic">🛠️</span><span data-i="b_title"></span></h2>
  <div class="note i small" data-i="b_hint"></div>
  <label data-i="b_isp"></label>
  <div class="pick" id="bIsp">
   <button type="button" data-isp="mtn" data-i="isp_mtn"></button>
   <button type="button" data-isp="mci" data-i="isp_mci"></button>
   <button type="button" data-isp="rtl" data-i="isp_rtl"></button>
   <button type="button" data-isp="tdsl" data-i="isp_tdsl"></button>
   <button type="button" data-isp="direct" data-i="isp_direct"></button>
  </div>
  <div class="small mute" id="bIspNote" style="margin-top:6px;min-height:16px"></div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="b_proto"></label><div class="pick" id="bProto"><button type="button" data-v="" class="on" data-i="b_both"></button><button type="button" data-v="vless">VLESS</button><button type="button" data-v="trojan">TROJAN</button></div></div>
   <div><label data-i="b_fam"></label><div class="pick" id="bFam"><button type="button" data-v="" class="on" data-i="b_both"></button><button type="button" data-v="v4">IPv4</button><button type="button" data-v="v6">IPv6</button></div></div>
  </div>
  <label data-i="b_ports"></label>
  <div class="pick" id="bPorts"></div>
  <label data-i="b_cc"></label>
  <div class="pick" id="bCc"></div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="b_ech"></label><div class="pick" id="bEch"><button type="button" data-v="0" class="on" data-i="b_ech_off"></button><button type="button" data-v="1">ECH ⚡</button></div><div class="small dim" id="bEchState" style="margin-top:6px"></div></div>
   <div><label data-i="b_limit"></label><input id="bLimit" type="number" min="1" max="200" value="24"></div>
   <div><label data-i="b_strict"></label><div class="pick" id="bStrict"><button type="button" data-v="0" class="on" data-i="b_fb_ok"></button><button type="button" data-v="1" data-i="b_only"></button></div></div>
  </div>
  <div class="row" style="margin-top:14px">
   <button class="btn p" id="bGen" type="button">⚡ <span data-i="b_gen"></span></button>
   <button class="btn" id="bCopyAll" type="button" style="display:none">📋 <span data-i="b_copy"></span> (<span id="bCount">0</span>)</button>
   <button class="btn" id="bQr" type="button" style="display:none">▦ QR</button>
  </div>
  <label data-i="b_link" style="margin-top:12px"></label>
  <input id="bLink" readonly class="mono" dir="ltr" value="">
  <label data-i="b_open" style="margin-top:10px"></label>
  <div class="row" id="bApps" style="display:none">
   <a class="btn sm p" id="bCat" href="#">🐱 Cat Client</a>
   <a class="btn sm" id="bV2rn" href="#">v2rayNG</a>
   <a class="btn sm" id="bHid" href="#">Hiddify</a>
  </div>
  <label data-i="b_prev" style="margin-top:12px"></label>
  <textarea id="bPrev" readonly style="min-height:130px"></textarea>
 </div>
 <div class="card sec">
  <h2><span class="ic">🧩</span><span data-i="b_frag"></span></h2>
  <div class="small mute" data-i="b_frag_hint"></div>
  <div class="two" style="margin-top:8px">
   <div><label>Fragment</label><input id="bFrag" dir="ltr" value="tlshello,100-200,5-10"></div>
   <div><label data-i="b_fp"></label><select id="bFp"><option value="">—</option><option>chrome</option><option>firefox</option><option>safari</option><option>ios</option><option>android</option><option>edge</option><option>random</option></select></div>
  </div>
  <div class="row" style="margin-top:8px"><button class="btn sm" id="bFragCopy" type="button">📋 <span data-i="copy_all"></span></button></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🧬</span><span data-i="aether_title"></span></h2>
  <div class="note i small" data-i="aether_hint"></div>
  <label data-i="aether_mode"></label>
  <div class="pick" id="aePick"><button type="button" data-v="warp">WARP</button><button type="button" data-v="gool" class="on" data-i="aether_gool"></button><button type="button" data-v="masque">MASQUE/H2</button></div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="aether_name"></label><input id="aeName" value="Omni WARP-in-WARP"></div>
   <div><label data-i="aether_family"></label><div class="pick" id="aeFam"><button type="button" data-v="both" class="on" data-i="b_both"></button><button type="button" data-v="v4">IPv4</button><button type="button" data-v="v6">IPv6</button></div></div>
  </div>
  <label data-i="b_link" style="margin-top:12px"></label>
  <input id="aeLink" readonly class="mono" dir="ltr" value="">
  <div class="row" style="margin-top:10px;flex-wrap:wrap;gap:8px">
   <button class="btn p" id="aeCopy" type="button">📋 <span data-i="copy_all"></span></button>
   <button class="btn" id="aeQr" type="button">▦ QR</button>
   <a class="btn" id="aeOpen" href="#">⚡ <span data-i="aether_open"></span></a>
  </div>
 </div>
</section>

<!-- ================= NODES (clean IPs) ================= -->
<section class="view" id="v-nodes">
 <div class="card sec">
  <h2><span class="ic">📡</span><span data-i="rp_title"></span><span class="chip v" id="rpState">…</span><button class="btn sm" id="rpRefresh" type="button" style="margin-inline-start:auto">⟳ <span data-i="rp_refresh"></span></button></h2>
  <div class="note i small" data-i="rp_hint"></div>
  <div class="res" id="rpRows" style="margin-top:10px"></div>
  <label data-i="rp_cc"></label>
  <div class="ipl" id="rpCcs"></div>
  <div class="row" style="margin-top:10px;flex-wrap:wrap;gap:8px">
   <button class="btn sm p" id="rpAuto" type="button"></button>
   <button class="btn sm" id="rpAdd" type="button">➕ <span data-i="rp_add"></span></button>
   <span class="small dim" data-i="rp_src"></span>
  </div>
  <div class="note w small" style="margin-top:8px" data-i="rp_dead_note"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">📥</span><span data-i="ip_import"></span></h2>
  <div class="small mute" data-i="ip_import_hint"></div>
  <textarea id="ipPaste" placeholder="104.16.1.1#DE&#10;104.16.1.1:2053#DE&#10;www.example.com"></textarea>
  <div class="row" style="margin-top:10px">
   <button class="btn p" id="btnIpAppend" data-i="ip_append"></button>
   <button class="btn a" id="btnIpReplace" data-i="ip_replace"></button>
  </div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🌍</span><span data-i="cc_title"></span> <span class="chip" id="ccState"></span></h2>
  <div class="note i small" data-i="cc_why"></div>
  <div class="ipl" id="ccList" style="margin-top:10px"></div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="cc_fallback"></label><select id="ccFallback"><option value="auto" data-i="cc_fb_auto"></option><option value="none" data-i="cc_fb_none"></option></select></div>
   <div><label data-i="cc_proxy"></label><div class="row"><button class="btn sm" id="btnProxyGeo" data-i="cc_proxy_btn"></button><span class="small mute" id="proxyGeoOut"></span></div></div>
  </div>
  <div class="small dim" style="margin-top:8px" data-i="cc_hint"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🧹</span><span data-i="ip_list"></span> <span class="chip v" id="ipCount">0</span><button class="btn sm" id="btnRot2" type="button" style="margin-inline-start:auto"></button></h2>
  <div class="small mute" data-i="ip_list_hint"></div>
  <div class="ipl" id="ipList"></div>
  <div class="row" style="margin-top:10px"><button class="btn r sm" id="btnIpClear" data-i="ip_clear"></button><button class="btn sm" id="btnIpCopy" data-i="copy_all"></button><button class="btn p sm" id="btnIpTest" type="button" style="margin-inline-start:auto">🩺 <span data-i="ip_test_btn"></span></button></div>
  <div class="small mute" id="ipTestOut" style="margin-top:6px"></div>
 </div>
</section>

<!-- ================= SETTINGS ================= -->
<section class="view" id="v-spoof">
 <form class="card sec frm" id="fWarp">
  <h2><span class="ic">🌐</span><span data-i="warp_title"></span></h2>
  <div class="note i small" data-i="warp_hint"></div>
  <label data-i="warp_mode"></label>
  <div class="pick" id="warpMode"><button type="button" data-v="off" data-i="warp_off"></button><button type="button" data-v="on">WARP</button><button type="button" data-v="chain" data-i="warp_chain"></button></div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="warp_sk"></label><input name="secretKey" class="mono" dir="ltr" autocomplete="off"></div>
   <div><label data-i="warp_pk"></label><input name="publicKey" class="mono" dir="ltr" autocomplete="off"></div>
  </div>
  <div class="two" style="margin-top:10px">
   <div><label data-i="warp_reserved"></label><input name="reserved" class="mono" dir="ltr" placeholder="12,34,56"></div>
   <div><label data-i="warp_endpoint"></label><input name="endpoint" class="mono" dir="ltr" placeholder="engage.cloudflareclient.com:2408"></div>
  </div>
  <div class="note w small" style="margin-top:10px" data-i="warp_warn"></div>
  <div class="row" style="margin-top:12px"><button class="btn p" type="submit" data-i="save"></button></div>
 </form>
 <div class="card sec">
  <h2><span class="ic">🔀</span><span data-i="ext_title"></span></h2>
  <div class="small mute" data-i="ext_hint"></div>
  <div class="res" id="extRows" style="margin-top:10px"></div>
  <div class="row" style="margin-top:10px;flex-wrap:wrap;gap:8px">
   <button class="btn sm" id="btnExtAdd" type="button">➕ <span data-i="ext_add"></span></button>
   <button class="btn sm p" id="btnExtPreset" type="button">⚡ <span data-i="ext_preset"></span></button>
  </div>
  <div class="note i small" id="extCoreNote" style="margin-top:8px"></div>
  <div class="small dim mono" dir="ltr" id="extLinkDemo" style="margin-top:8px"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🕵️</span><span data-i="mitm_title"></span></h2>
  <div class="small mute" data-i="mitm_body"></div>
  <div class="row" style="margin-top:10px"><a class="btn sm" href="https://github.com/patterniha/MITM-DomainFronting" target="_blank" rel="noopener">📖 GitHub — MITM + DomainFronting</a></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🛰️</span><span data-i="pp_title"></span><span class="chip v" id="ppState">…</span><button class="btn sm" id="ppRefresh" type="button" style="margin-inline-start:auto">⟳ <span data-i="rp_refresh"></span></button></h2>
  <div class="note i small" data-i="pp_hint"></div>
  <div class="row" style="margin-top:10px;flex-wrap:wrap;gap:8px"><button class="btn sm p" id="btnPxAddrs" type="button">📥 <span data-i="px_addrs"></span></button></div>
  <div class="note i small" style="margin-top:8px" data-i="px_addrs_hint"></div>
  <div class="res" id="ppRows" style="margin-top:10px"></div>
  <label data-i="pp_cc"></label>
  <div class="ipl" id="ppCcs"></div>
  <label data-i="pp_countries" style="margin-top:10px"></label>
  <div class="ipl" id="ppCountries"></div>
  <div class="row" style="margin-top:10px;flex-wrap:wrap;gap:8px">
   <button class="btn sm p" id="ppAuto" type="button"></button>
   <button class="btn sm" id="ppAdd" type="button">➕ <span data-i="rp_add"></span></button>
   <span class="small dim" data-i="pp_src"></span>
  </div>
  <div class="note w small" style="margin-top:8px" data-i="pp_dead_note"></div>
 </div>
 <form class="card sec frm" id="fSpoof">
  <h2><span class="ic">🎭</span><span data-i="spoof"></span></h2>
  <div class="small dim" data-i="spoof_hint"></div>
  <label data-i="s_extra_sni"></label>
  <textarea name="extraSnis" style="min-height:56px" data-ph="s_extra_sni_ph"></textarea>
  <div class="small dim" data-i="s_extra_sni_hint"></div>
  <label data-i="s_proxy"></label>
  <textarea name="proxyIps" style="min-height:70px" data-ph="s_proxy_ph"></textarea>
  <div class="small dim" data-i="s_proxy_hint"></div>
  <div class="row" style="margin-top:14px"><button class="btn p" type="submit" data-i="save"></button></div>
 </form>
</section>

<section class="view" id="v-settings">
 <form class="card sec frm" id="fSettings">
  <h2><span class="ic">⚙️</span><span data-i="settings"></span></h2>
  <div class="two">
   <div><label data-i="s_title"></label><input name="ptitle" maxlength="60"></div>
   <div><label data-i="s_lang"></label><select name="plang"><option value="fa">فارسی</option><option value="en">English</option></select></div>
  </div>
  <label data-i="s_pass"></label>
  <div class="row"><input name="password" type="password" autocomplete="new-password" data-ph="s_pass_ph" style="flex:1"><span class="chip" id="passState"></span></div>
  <label data-i="s_stealth"></label>
  <div class="row"><input name="panelPath" class="mono" dir="ltr" spellcheck="false" data-ph="s_stealth_ph" style="flex:1"><button type="button" class="btn sm" id="btnPathRnd">🎲</button></div>
  <div class="small dim" data-i="s_stealth_hint"></div>
  <div class="hr"></div>
  <label data-i="s_protocols"></label>
  <div class="proto">
   <label id="pVless"><span class="ic" style="background:rgba(0,225,193,.2);color:#c4b5fd">✈️</span><div><div class="b">VLESS</div><div class="dim small" data-i="p_vless"></div></div><input type="checkbox" name="pv" style="width:auto;margin-inline-start:auto"></label>
   <label id="pTrojan"><span class="ic" style="background:rgba(0,179,152,.2);color:#f0abfc">🛡️</span><div><div class="b">Trojan</div><div class="dim small" data-i="p_trojan"></div></div><input type="checkbox" name="pt" style="width:auto;margin-inline-start:auto"></label>
  </div>
  <div class="two">
   <div><label data-i="s_tls"></label><div class="pick" id="pickTls"></div><div class="row" style="margin-top:8px"><input id="addTls" class="mono" dir="ltr" inputmode="numeric" placeholder="1-65535" maxlength="5" style="max-width:120px"><button type="button" class="btn sm" id="btnAddTls">➕</button></div></div>
   <div><label data-i="s_plain"></label><div class="pick" id="pickPlain"></div><div class="row" style="margin-top:8px"><input id="addPlain" class="mono" dir="ltr" inputmode="numeric" placeholder="1-65535" maxlength="5" style="max-width:120px"><button type="button" class="btn sm" id="btnAddPlain">➕</button></div><div class="row small" style="margin-top:8px"><span class="sw" id="swPlain"></span><span data-i="s_plain_on"></span></div></div>
  </div>
  <div><label data-i="s_rot"></label>
   <div class="pick" id="subRotatePick"><button type="button" data-v="off" data-i="s_rot_off"></button><button type="button" data-v="fetch" data-i="s_rot_fetch"></button><button type="button" data-v="daily" data-i="s_rot_daily"></button></div>
   <div class="small dim" style="margin-top:6px" data-i="s_rot_hint"></div>
  </div>
  <div class="two" style="margin-top:12px">
   <div><label data-i="s_sni"></label><input name="sni" class="mono" data-ph="s_sni_ph"></div>
   <div><label data-i="s_fp"></label><select name="fingerprint"><option>chrome</option><option>firefox</option><option>safari</option><option>ios</option><option>android</option><option>edge</option><option>random</option><option>randomized</option><option>unsafe</option></select></div>
  </div>
  <div class="two">
   <div><label data-i="s_limit"></label><input name="entryLimit" type="number" min="4" max="200"></div>
   <div><label data-i="s_flags"></label><div class="row small" style="margin-top:6px"><span class="sw" id="swDefaults"></span><span data-i="s_defaults"></span></div><div class="row small" style="margin-top:8px"><span class="sw" id="swHost"></span><span data-i="s_host"></span></div></div>
  </div>
  <div class="hr"></div>
  <label data-i="s_route"></label>
  <div class="row small" style="margin-top:6px"><span class="sw" id="swIran"></span><span data-i="s_iran"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swAds"></span><span data-i="s_ads"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swQuic"></span><span data-i="s_quic"></span></div>
  <div class="small dim" data-i="s_route_hint"></div>
  <div class="hr"></div>
  <label data-i="s_frag"></label>
  <div class="row small" style="margin-top:6px"><span class="sw" id="swFrag"></span><span data-i="s_frag_on"></span></div>
  <div class="two" style="margin-top:8px">
   <div><label>packets</label><select name="fragPackets"><option>tlshello</option><option>1-1</option><option>1-2</option><option>1-3</option><option>1-5</option></select></div>
   <div><label>length / interval</label><div class="row"><input name="fragLength" class="mono" placeholder="10-100" style="flex:1"><input name="fragInterval" class="mono" placeholder="10-20" style="flex:1"></div></div>
  </div>
  <div class="two" style="margin-top:8px">
   <div><label>ALPN</label><select name="alpn"><option>http/1.1</option><option>h2,http/1.1</option><option>h2</option><option>h3,h2,http/1.1</option></select></div>
   <div><label>Cipher suites (Xray) · <button type="button" class="btn sm" id="btnPattn" data-i="pattn_btn" style="padding:2px 10px"></button></label><input name="cipherSuites" class="mono" dir="ltr" placeholder="TLS_ECDHE_..:TLS_.."></div>
  </div>
  <div class="small dim" data-i="s_frag_hint"></div>
  <div class="hr"></div>
  <label><span data-i="s_tg"></span> <span class="chip" id="tgState"></span></label>
  <div class="two">
   <div><label>Bot token</label><input name="tgToken" class="mono" dir="ltr" placeholder="123456:ABC…"></div>
   <div><label data-i="s_tg_admins"></label><input name="tgAdmins" class="mono" dir="ltr" placeholder="123456789, 987654321"></div>
  </div>
  <div class="row" style="margin-top:8px"><button class="btn sm" type="button" id="btnTgHook" data-i="s_tg_hook"></button><span class="small mute" id="tgHookOut"></span></div>
  <div class="small dim" data-i="s_tg_hint"></div>
  <div class="hr"></div>
  <label data-i="s_gh_title"></label>
  <div class="two" style="margin-top:8px">
   <div><label data-i="s_gh_repo"></label><input name="ghRepo" class="mono" dir="ltr" placeholder="owner/repo"></div>
   <div><label data-i="s_gh_ref"></label><input name="ghRef" class="mono" dir="ltr" placeholder="main"></div>
  </div>
  <div class="two" style="margin-top:8px">
   <div><label data-i="s_gh_pat"></label><input name="ghPat" class="mono" dir="ltr" placeholder="github_pat_…"></div>
   <div><label data-i="s_gh_wf"></label><input name="ghWorkflow" class="mono" dir="ltr" placeholder="deploy-worker.yml"></div>
  </div>
  <div class="small dim" data-i="s_gh_hint"></div>
  <div class="hr"></div>
  <label><span data-i="s_chain"></span> <span class="chip" id="chainState"></span></label>
  <input name="chain" class="mono" dir="ltr" data-ph="s_chain_ph">
  <div class="small dim" data-i="s_chain_hint"></div>
  <div class="two" style="margin-top:8px">
   <div><label data-i="s_chain_mode"></label><select name="chainMode"><option value="all" data-i="s_chain_all"></option><option value="cf" data-i="s_chain_cf"></option></select></div>
   <div><label data-i="s_chain_strict"></label><div class="row small" style="margin-top:6px"><span class="sw" id="swStrict"></span><span data-i="s_chain_strict_on"></span></div></div>
  </div>
  <div class="row" style="margin-top:8px"><button class="btn sm" type="button" id="btnChainTest" data-i="s_chain_test"></button><span class="small mute" id="chainTestOut"></span></div>
  <div class="row" style="margin-top:16px"><button class="btn p" type="submit" data-i="save"></button><span class="small mute" id="saveState"></span></div>
 </form>
 <div class="card sec">
  <h2><span class="ic">🔗</span><span data-i="paths"></span></h2>
  <div class="small mute" id="pathsBox"></div>
 </div>
</section>

<!-- ================= BACKUP ================= -->
<section class="view" id="v-backup">
 <div class="card sec">
  <h2><span class="ic">💾</span><span data-i="backup"></span></h2>
  <div class="small mute" data-i="backup_hint"></div>
  <div class="row" style="margin-top:12px"><a class="btn p" href="/api/backup" download="cat-panel-backup.json">⬇️ <span data-i="backup_dl"></span></a>
  <label class="btn" style="margin:0">⬆️ <span data-i="backup_up"></span><input type="file" id="restoreFile" accept="application/json" style="display:none"></label></div>
  <div id="restoreState" class="small" style="margin-top:10px"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🧯</span><span data-i="limits"></span></h2>
  <div class="small mute" data-i="limits_text"></div>
 </div>
</section>

<!-- ================= ABOUT ================= -->
<section class="view" id="v-about">
 <div class="card sec">
  <h2><span class="ic">🐱</span>Cat Panel v${CAT_PANEL_VERSION}</h2>
  <div class="small mute" data-i="about_text"></div>
  <div class="row" style="margin-top:12px"><a class="btn" href="${REPO_URL}" target="_blank" rel="noopener">GitHub</a><a class="btn" href="${REPO_URL}/releases" target="_blank" rel="noopener">Cat Client APK</a></div>
  <div id="updateBox" class="small" style="margin-top:12px"></div>
 </div>
</section>
</div>

<!-- bottom nav (mobile) -->
<div class="nav">
 <button data-view="dash"><span>📊</span><i data-i="n_dash"></i></button>
 <button data-view="clients"><span>👥</span><i data-i="n_clients"></i></button>
 <button data-view="inbounds"><span>🧩</span><i data-i="n_inbounds"></i></button>
 <button data-view="scan"><span>📡</span><i data-i="n_scan"></i></button>
 <button data-view="build"><span>🛠️</span><i data-i="n_build"></i></button>
 <button data-view="spoof"><span>🎭</span><i data-i="n_spoof"></i></button>
 <button data-view="settings"><span>⚙️</span><i data-i="n_set"></i></button>
 <button data-view="backup"><span>💾</span><i data-i="n_bak"></i></button>
</div>

<!-- user drawer -->
<div class="drawer" id="drawer"><div class="bg" data-close></div><div class="pn frm">
 <div class="row" style="justify-content:space-between"><h3><span class="ic" style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:rgba(0,225,193,.2)">👤</span><span id="dTitle"></span></h3><button class="ib" data-c="red" data-close>✕</button></div>
 <div class="small mute" data-i="d_sub"></div>
 <form id="fUser">
  <label data-i="u_name"></label>
  <div class="row"><input name="uname" maxlength="40" required style="flex:1"><button class="btn sm" type="button" id="btnRandName">🎲 <span data-i="u_rand"></span></button></div>
  <label data-i="u_protocols"></label>
  <div class="proto">
   <label id="uVless"><span class="ic" style="background:rgba(0,225,193,.2);color:#c4b5fd">✈️</span><div><div class="b">VLESS</div><div class="dim small" data-i="p_vless"></div></div><input type="checkbox" name="pv" checked style="width:auto;margin-inline-start:auto"></label>
   <label id="uTrojan"><span class="ic" style="background:rgba(0,179,152,.2);color:#f0abfc">🛡️</span><div><div class="b">Trojan</div><div class="dim small" data-i="p_trojan"></div></div><input type="checkbox" name="pt" checked style="width:auto;margin-inline-start:auto"></label>
  </div>
  <label data-i="u_days"></label>
  <input name="days" type="number" min="0" placeholder="0">
  <div class="pick" id="pickDays"></div>
  <label data-i="u_note"></label>
  <input name="note" maxlength="200">
  <div class="row" style="margin-top:10px"><span class="sw on" id="swEnabled"></span><span class="small" data-i="u_enabled"></span></div>
  <div class="note w small" style="margin-top:14px" data-i="u_noquota"></div>
  <div class="row" style="margin-top:18px"><button class="btn p" type="submit" data-i="save"></button><button class="btn" type="button" data-close data-i="cancel"></button></div>
 </form>
 <div id="dLinks" style="margin-top:18px"></div>
</div></div>

<div class="modal" id="qrModal" onclick="this.classList.remove('show')"><div class="in"><div class="qrbox" id="qrBox"></div><div class="small mute" style="margin-top:10px" id="qrLabel"></div></div></div>
<div class="toast" id="toast"></div>

<script>
(function(){
'use strict';
var HOST=${JSON.stringify(host)}, UUID=${JSON.stringify(masterUuid)}, VERSION=${JSON.stringify(CAT_PANEL_VERSION)};
var I18N={
fa:{stats:'آمار و وضعیت پنل',st_users:'کل کاربران',st_users_s:'تعریف‌شده در پنل',st_active:'فعال',st_active_s:'بدون انقضا یا غیرفعال',st_exp:'منقضی / غیرفعال',st_exp_s:'نیاز به تمدید',st_ips:'آی‌پی تمیز',st_cfg:'کانفیگ در هر ساب',
master_links:'لینک‌های اشتراک اصلی',self:'اطلاعات اتصال من',users:'لیست کاربران',search:'جستجوی نام یا UUID…',f_all:'همه',f_active:'فعال',f_expired:'منقضی',f_disabled:'غیرفعال',s_new:'جدیدترین',s_exp:'نزدیک‌ترین انقضا',s_name:'نام',
h_user:'کاربر',h_proto:'پروتکل',h_links:'لینک ساب',h_time:'زمان',h_seen:'آخرین آنلاین',h_status:'وضعیت',h_act:'عملیات',seen_never:'هرگز',seen_now:'همین حالا',seen_min:'%1 دقیقه پیش',no_users:'هنوز کاربری نساختی. با دکمهٔ + اولین کاربر را بساز.',
scan_title:'آی‌پی تمیز و اسکنر',scan_why:'اسکن روی دستگاه خودت انجام می‌شود (نه داخل ورکر). این دقیقاً روشی است که BPB و ZEUS استفاده می‌کنند: ورکر هیچ درخواستی خرج نمی‌کند و نتیجه از شبکهٔ واقعی تو (همان اپراتور) به دست می‌آید.',
scan_app:'اسکن با Cat Client',scan_browser:'تست دامنه‌ها در مرورگر',scan_guide:'راهنمای اسکنرها',warp_title:'WARP روی خروجی Xray (کلیدهای خودت)',warp_hint:'اتصال به پنل داخل تونل WARP خودت می‌رود (آی‌پی واقعی‌ات حتی برای ورکر پنهان می‌شود). کلیدها را از wgcf یا خروجی Aether بردار — ورکر هیچ‌وقت با کلادفلر ثبت‌نام نمی‌کند (بن نمی‌شود). با روشن‌بودن WARP، فرگمنت کنار گذاشته می‌شود (تونل UDP است).',warp_mode:'حالت',warp_off:'خاموش',warp_chain:'WARP-در-WARP (زنجیره)',warp_sk:'SecretKey وایرگارد',warp_pk:'PublicKey همتا (Cloudflare)',warp_reserved:'reserved (اختیاری — با ویرگول)',warp_endpoint:'اندپوینت',warp_warn:'هیچ کلیدی را که مال خودت نیست اینجا نگذار. برای خاموش‌کردن موقت، حالت را «خاموش» بگذار — کلیدها می‌مانند.',ext_title:'ساب‌های خارجی (ترکیب با ساب تو)',ext_hint:'محتوای ساب‌های خارجی از دامنهٔ خود پنل سرو می‌شود (raw.github از ایران باز نمی‌شود) + اگر لیست URI باشد بعد از کانفیگ‌های خودت به ساب اضافه می‌شود. ?noext=1 = بدون این‌ها.',ext_add:'افزودن ساب',ext_preset:'ساب آمادهٔ سرورلس (PattNG)',ext_core:'ساب سرورلس به هستهٔ Xray تازه نیاز دارد (PattNG یا v2rayNG ≥2.2.6) و باید مستقیم در اپ ایمپورت شود، نه داخل ساب پنل.',ext_empty:'هنوز ساب خارجی نداری — پیش‌تنظیم سرورلس را امتحان کن.',ext_name:'نام',ext_url:'آدرس https ساب',mitm_title:'MITM + DomainFronting (سمت کلاینت)',mitm_body:'روشِ پترنیها برای باز کردن مستقیم یوتیوب/اینستاگرام/واتس‌اپ/فیسبوک/رددیت بدون سرور — راه‌اندازی روی خود دستگاه (ویندوز/لینوکس/مک/اندروید بدون روت) انجام می‌شود؛ سرتیفیکیت شخصی بساز و به سیستم اعتماد بده. راهنمای کامل در مخزن:',aether_title:'کانفیگ‌های ویژهٔ Aether (PattNG)',aether_hint:'لینک aether:// می‌سازد — با دکمهٔ باز کردن مستقیم در PattNG (هستهٔ Aether) باز می‌شود. WARP تک‌لایه، WARP-در-WARP (Gool) و MASQUE/HTTP-2 با فرگمنت.',aether_mode:'نوع',aether_gool:'WARP-در-WARP (Gool)',aether_name:'نام کانفیگ',aether_family:'خانوادهٔ آی‌پی',aether_open:'افزودن به PattNG',pp_countries:'افزودن مخزن کشوری (وان‌وو):',px_addrs:'افزودن استخر سالم به لیست اتصال (به‌عنوان آی‌پی)',px_addrs_hint:'پروکسی‌آی‌پی‌های سالم استخر، با تگ کشورشان به لیست آی‌پی‌های اتصال اضافه می‌شوند و کانفیگ 💦 می‌گیرند. هر پروکسی‌آی‌پی لزوماً به‌عنوان آدرس اتصال جواب نمی‌دهد — بعد از افزودن با تست اتصال اپ/اسکنر فیلترشان کن.',px_none:'چیزی برای افزودن نبود — اول «بروزرسانی» استخر را بزن',b_ech:'ECH (رمزگذاری ClientHello — سبک تیکه‌های ECH/SIIT)',b_ech_off:'خاموش',ech_none:'SNI فعلی ECH ندارد (یا دسترسی DNS نبود) — خاموش نگه دار',rot_btn_off:'ایپی ثابت (چرخش روشنه — بزن تا ثابت شه)',rot_btn_on:'ایپی ثابته (بزن تا چرخش روشن شه)',rot_fixed_lbl:'ایپی ثابت',rot_rot_lbl:'چرخش',rot_now_fixed:'📌 چرخش خاموش شد — ایپی‌ها ثابت ماندند',rot_now_rotating:'⚡ چرخش روشن شد — هر آپدیت ست تازه',hero_inuse:'در حال استفاده (همین لحظه)',hero_ports:'پورت‌ها',hero_ips:'آی‌پی تمیز',hero_px:'رله ProxyIP',hero_exit:'خروجی ثابت',hero_rot:'چرخش',hero_warn:'⚠️ SNI هنوز پیش‌فرض skk.moe است — اگر اپراتورت آن را ببندد همهٔ کانفیگ‌ها با هم می‌میرند. از اسکنر SNI اپ، SNI سالم بگیر و «⚡ SNI اصلی پنل شود» را بزن.',hero_build:'کانفیگ‌ساز',ip_pin:'پین به‌عنوان ایپی ثابت (همیشه اول ساب)',ip_unpin:'برداشتن پین',pin_saved:'📌 این آی‌پی همیشه اول ساب می‌ماند — حتی با چرخش',pin_removed:'پین برداشته شد',s_rot:'چرخش خودکار کانفیگ‌ها',s_rot_off:'ثابت (مثل BPB)',s_rot_fetch:'هر بروزرسانی',s_rot_daily:'روزانه',s_rot_hint:'با هر آپدیت ساب، چیدمان آی‌پی‌ها و شمارهٔ کانفیگ‌ها عوض می‌شود — هر بار ستِ تازه می‌گیری. «روزانه» در طول روز ثابت است؛ «ثابت» همان ترتیب همیشگی است.',pp_title:'مخزن‌های ProxyIP (آپدیت ۱۲ساعته)',pp_hint:'فیدهای عمومی ProxyIP (آی‌پی یا دامنه)؛ هر ۱۲ ساعت خودکار بروز می‌شوند. ProxyIP = IP واسط برای بازکردن سایت‌های کلادفلریِ روی همان IP؛ خراب‌ها بعد از ۳ گزارش حذف و جایگزین می‌شوند.',pp_cc:'کشورهای استخر ProxyIP — + = افزودن ۸ عدد آن کشور به لیست ProxyIP پنل',pp_auto:'افزودن خودکار ۶ ProxyIP تازه به ساب‌ها',pp_src:'منابع: xgonce/Cloudflare_IP · wanwushequ/ProxyIP',pp_dead_note:'گزارش مرده: POST /api/prepos {action:"health",dead:[…]} ×۳ — بعدش جایگزین می‌شود.',rp_title:'مخزن‌ها (آپدیت ۱۲ساعته)',rp_refresh:'بروزرسانی',rp_hint:'فیدهای عمومی آی‌پی تمیز؛ هر ۱۲ ساعت خودکار بروز می‌شوند (cron کلادفلر + باز شدن پنل). مخزن خراب‌ها بعد از ۳ گزارش از استخر حذف و در بروزرسانی بعدی جایگزین می‌شود.',rp_cc:'کشورهای استخر مخزن — + = افزودن ۱۶ آی‌پی آن کشور به لیست پنل',rp_auto:'افزودن خودکار ۸ آی‌پی تازه به ساب‌ها',rp_add:'مخزن جدید',rp_add_url:'آدرس raw مخزن (https://…)',rp_add_name:'نام مخزن',rp_empty:'استخر مخزن خالی است — «بروزرسانی» را بزن.',rp_nokv:'بدون KV ذخیره نمی‌شود',rp_src:'منابع: arista-project/cf-clean-ips · imatixofficel/Scanner-matix',rp_dead_note:'گزارش آی‌پی مرده؟ سه بار «health» با POST /api/repos {action:"health",dead:[…]} — بعدش خودکار عوضش می‌کند.',n_build:'کانفیگ‌ساز',b_title:'کانفیگ‌ساز',b_hint:'برای هر اپراتور، کشور و پورت یک لینک سابِ دقیق می‌سازد؛ تنظیمات اصلی پنل را تغییر نمی‌دهد.',b_isp:'پروفایل اپراتور (پیشنهاد — روی خط خودت تست کن)',isp_mtn:'ایرانسل (MTN)',isp_mci:'همراه اول (MCI)',isp_rtl:'رایتل / شاتل',isp_tdsl:'مخابرات',isp_direct:'مستقیم / خودکار',b_isp_mtn_n:'ایرانسل: فرگمنت حتماً روشن؛ پورت‌های 443 و 8443 با اثر انگشت chrome.',b_isp_mci_n:'همراه اول: 443 و 2053؛ اگر IPv6 داری خانواده را روی «هر دو» بگذار.',b_isp_rtl_n:'رایتل/شاتل: پورت‌های بدون TLS (80/8080) معمولاً بهتر جواب می‌دهد؛ فرگمنت کوتاه.',b_isp_tdsl_n:'مخابرات: 443 با اثر انگشت iOS معمولاً پایدارتر است.',b_isp_direct_n:'آماده‌سازی‌ای اعمال نشد — فیلترها را خودت انتخاب کن.',b_proto:'پروتکل',b_fam:'خانوادهٔ آی‌پی',b_both:'هر دو',b_ports:'پورت‌ها (چندتایی)',b_cc:'کشور خروجی',b_cc_all:'همه کشورها',b_limit:'تعداد کانفیگ (۱ تا ۲۰۰)',b_strict:'رفتار کشور',b_fb_ok:'سقوط به بقیهٔ کشورها',b_only:'فقط همین کشور',b_gen:'ساخت ساب زنده',b_copy:'کپی همه',b_link:'لینک ساب ساخته‌شده',b_prev:'پیش‌نمایش زنده (اولین خط‌ها)',b_open:'باز کردن در',b_frag:'فرگمنت و اثر انگشت (تنظیمِ خودِ کلاینت)',b_frag_hint:'فرگمنت داخل لینک ساب نمی‌آید؛ در خود کلاینت واردش کن (v2rayNG: ویرایش کانفیگ → Fragment). مقدارش با پروفایل اپراتور عوض می‌شود.',b_fp:'اثر انگشت TLS',scan_cat:'دستهٔ آی‌پی',scan_region:'منطقه',scan_cc:'کشورهای لیست پنل',scan_search:'جستجوی کشور',scan_search_ph:'آلمان یا DE…',scan_cidr:'افزودن از رنج CIDR یا دامنه',scan_cidr_ph:'104.16.0.0/24 یا cdn.example.com',cidr_add:'افزودن به لیست',cidr_ok:'%1 آی‌پی اضافه شد',cidr_bad:'رنج نامعتبر است (نمونه: 104.16.0.0/24)',loc_now:'لوکیشن فعلی',loc_refresh:'بروزرسانی',loc_fail:'لوکیشن خوانده نشد',scan_jump:'⚙ ساب فقط این کشور',scan_empty:'با این فیلتر آی‌پی‌ای نیست.',scan_note_browser:'مرورگر نمی‌تواند آی‌پی خام را تست کند (محدودیت SNI/گواهی) — برای آی‌پی خام از «اسکن با Cat Client» استفاده کن؛ تستِ مرورگر فقط دامنه‌ها را می‌سنجد.',reg_eu:'🇪🇺 اروپا',reg_me:'🕌 خاورمیانه',reg_as:'🌏 آسیا',reg_am:'🌎 آمریکا',reg_af:'🌍 آفریقا',ev_title:'گزارش رویدادها',ev_time:'زمان',ev_ev:'رویداد',ev_d:'شرح',ev_empty:'هنوز رویدادی ثبت نشده است.',ev_ago_h:'%1 ساعت پیش',ev_ago_d:'%1 روز پیش',ip_import:'وارد کردن نتیجهٔ اسکن', proxyip_import: 'ProxyIPها از Cat Client وارد شد — ذخیره کن',ip_import_hint:'آی‌پی یا دامنهٔ تمیز را اینجا بچسبان (هر خط یکی یا با کاما). پورت هم می‌پذیرد: 104.16.1.1:2053#DE — آن IP فقط و فقط روی همان پورتِ تأییدشده ساخته می‌شود، نه پورت‌های دیگر. از دکمهٔ ارسال به پنل در Cat Client یا هر اسکنر دیگری.',
ip_append:'افزودن به لیست',ip_replace:'جایگزینی کل لیست',ip_test_btn:'تست از ورکر',ip_test_reach:'از سمت ورکر در دسترس',ip_list:'لیست آی‌پی‌های پنل',ip_list_hint:'این‌ها اول هر اشتراک قرار می‌گیرند. برای حذف روی هر مورد بزن.',ip_clear:'پاک کردن همه',copy_all:'کپی همه',cc_title:'کشورها',cc_why:'هر آدرس را با کشوری که برای تو از آن خارج می‌شود برچسب بزن (از اسکنر Cat Client به شکل ip#DE بچسبان، یا دستی از منوی هر آی‌پی). روی یک کشور بزن تا کانفیگ‌ها فقط از همان کشور باشند؛ اگر همهٔ آی‌پی‌های آن کشور بسته شوند، به سریع‌ترین کشور دیگر می‌رود.',cc_auto:'🤖 خودکار (همهٔ کشورها)',cc_fallback:'وقتی همهٔ آی‌پی‌های کشور انتخابی بسته شد',cc_fb_auto:'برو سریع‌ترین کشور دیگر (پیشنهادی)',cc_fb_none:'هیچ‌وقت کشور عوض نشود (قطع شود)',cc_proxy:'Proxy IP‌ها',cc_proxy_btn:'🌍 تشخیص کشور Proxy IP‌ها',cc_hint:'در Clash/Mihomo و Cat Client جابه‌جایی خودکار است؛ در V2Box/sing-box کشور پیش‌فرض انتخاب می‌شود و بقیه در لیست می‌مانند. لینک فقط-یک-کشور: دکمهٔ 🔗 کنار هر کشور (?country=XX&strict=1).',cc_untagged:'بدون کشور',cc_link:'لینک فقط این کشور',
settings:'تنظیمات پنل',s_title:'عنوان پنل',s_lang:'زبان',s_pass:'رمز پنل',s_stealth:'مسیر مخفی پنل',s_stealth_ph:'خالی = مخفی‌کاری خاموش',s_stealth_hint:'با تنظیم این مسیر، آدرس اصلی پنل یک ۴۰۴ خنثی می‌دهد و پنل فقط روی /این‌مسیر بالا می‌آید — جلوی ربات‌های اسکن workers.dev را می‌گیرد. لینک جدید بعد از ذخیره: دامنه/مسیر',s_pass_ph:'خالی = بدون تغییر',s_protocols:'پروتکل‌ها',p_vless:'سبک و پرسرعت',p_trojan:'جایگزین امن',
s_tls:'پورت‌های TLS',s_plain:'پورت‌های بدون TLS (HTTP)',s_plain_on:'کانفیگ‌های بدون TLS هم ساخته شود',s_sni:'SNI / Host',s_sni_ph:'پیش‌فرض: skk.moe — آدرس پنل هرگز در SNI نمی‌رود',s_fp:'فینگرپرینت TLS',s_limit:'حداکثر کانفیگ در هر ساب',s_port_bad:'پورت نامعتبر — عددی بین ۱ تا ۶۵۵۳۵ بزن',
s_flags:'گزینه‌ها',s_defaults:'افزودن آدرس‌های پیش‌فرض بعد از لیست من',s_host:'خود آدرس ورکر هم به‌عنوان آدرس اضافه شود',s_proxy:'Proxy IP (برای سایت‌های پشت کلودفلر)',s_proxy_ph:'خالی = لیست پیش‌فرض',s_proxy_hint:'هر خط یک آدرس یا host:port. فقط وقتی مقصد خودش پشت کلودفلر باشد استفاده می‌شود.',s_route:'مسیریابی',s_iran:'سایت‌ها و اپ‌های ایرانی مستقیم (بدون VPN) — اسنپ، بانک، دیجی‌کالا عادی کار می‌کنند',s_ads:'مسدودسازی تبلیغات (شبکه‌های تبلیغاتی)',s_quic:'مسدودسازی QUIC/HTTP3 (UDP 443) — مثل BPB؛ بعضی اپراتورها UDP را خراب می‌کنند، بلاکش کلاینت را به TCP می‌فرستد',s_route_hint:'در خروجی Clash / sing-box / Xray اعمال می‌شود. لینک‌های ساده vless:// قانون ندارند؛ آن‌ها را کلاینت تعیین می‌کند (Cat Client خودش همین‌ها را دارد).',s_frag:'Fragment و TLS پیشرفته',s_frag_on:'Fragment فعال (شکستن TLS ClientHello برای عبور از فیلتر)',s_frag_confirm:'Fragment روی همهٔ لینک‌های «Xray کامل» و sing-box اعمال می‌شود (بعد از ذخیره). روی بعضی اپراتورها سرعت کمی کم می‌شود. فعال شود؟',s_frag_hint:'Fragment و Cipher suites فقط در لینک «Xray کامل» و sing-box اعمال می‌شود (لینک ساده نمی‌تواند حمل‌شان کند). ALPN را روی http/1.1 بگذار؛ h2 روی WebSocket کلودفلر کار نمی‌کند.',s_tg:'ربات تلگرام',s_tg_admins:'آیدی عددی ادمین‌ها',s_tg_hook:'🤖 اتصال ربات (Webhook)',s_tg_hint:'از @BotFather یک ربات بساز و توکنش را اینجا بگذار؛ آیدی عددی‌ات را از @userinfobot بگیر. اول ذخیره کن، بعد «اتصال ربات». دستورها: /users /add /renew /toggle /del /link /ips /country /status. تا پیامی نفرستی هیچ هزینه‌ای ندارد.',tg_ok:'وصل شد',tg_off:'غیرفعال',s_gh_title:'🚀 دیپلوی خودکار (ربات ← GitHub Actions ← کلادفلر)',s_gh_repo:'مخزن گیت‌هاب (owner/repo)',s_gh_ref:'برنچ دیپلوی',s_gh_pat:'توکن گیت‌هاب (Actions: read/write)',s_gh_wf:'فایل ورک‌فلو',s_gh_hint:'در تلگرام: /deploy [برنچ] و /deploys. توکن کلادفلر هیچ‌وقت اینجا وارد نمی‌شود — فقط یک‌بار در GitHub Secrets (CLOUDFLARE_API_TOKEN، CLOUDFLARE_ACCOUNT_ID، TELEGRAM_BOT_TOKEN، TELEGRAM_CHAT_ID). راهنمای کامل: docs/telegram-deploy.md',s_chain:'خروجی ثابت (IP و کشور ثابت)',s_chain_ph:'socks5://user:pass@1.2.3.4:1080  یا  http://host:3128',s_chain_hint:'ورکر همهٔ ترافیک را از این سرور (VPS خودت) بیرون می‌فرستد؛ در نتیجه IP و کشور همیشه یکی است. خالی = خروجی خود کلودفلر (کشور ممکن است عوض شود).',s_chain_mode:'کدام مقصدها',s_chain_all:'همهٔ سایت‌ها (کاملاً ثابت)',s_chain_cf:'فقط سایت‌های پشت کلودفلر (به‌جای Proxy IP)',s_chain_strict:'سخت‌گیرانه',s_chain_strict_on:'اگر سرور زنجیره در دسترس نبود، قطع شو (نشت نکن)',s_chain_test:'🧪 تست زنجیره',chain_off:'غیرفعال',chain_ok:'وصل شد',chain_fail:'ناموفق',
save:'ذخیره تغییرات',cancel:'انصراف',saved:'ذخیره شد',
n_clients:'کاربران',n_inbounds:'اینباندها',n_about:'درباره',n_logout:'خروج',ov_info:'اطلاعات پنل',ov_loc:'موقعیت',ov_up:'آپتایم',ov_ver:'نسخه',ov_check:'بررسی آپدیت',ov_services:'سرویس‌ها',svc_run:'فعال',svc_idle:'خاموش',ib_count:'اینباندها',ib_ports:'پورت‌ها',ib_inbound:'اینباند',ib_copy:'کپی لینک ساب',ib_hint:'لینک کپی‌شده فقط کانفیگ‌های همان پروتکل و پورت را می‌دهد (?proto=&port=). ترافیک روی Cloudflare Workers قابل شمارش نیست.',bulk_count:'چند کاربر ساخته شود؟',bulk_prefix:'پیشوند نام (مثلاً user)',bulk_done:'ساخته شد: ',n_spoof:'SNI و ProxyIP',spoof:'SNI و ProxyIP (اسپوف)',spoof_hint:'کانفیگ‌های این بخش جدا از ایپی‌های تمیز و با نام مخصوص خودشان ساخته می‌شوند: 🧬 SNI … و 🎯 PX … — اول «ذخیره تغییرات» را بزن، بعد ساب را دوباره آپدیت کن.',s_extra_sni:'SNIهای اضافه (هر خط یکی — حداکثر ۸)',s_extra_sni_ph:'speedtest.example.com',s_extra_sni_hint:'برای هر دامنه یک کانفیگ با servername همان دامنه ساخته می‌شود (دامنه باید پشت کلادفلر باشد) — وقتی SNI دامنه‌ی خودت فیلتر شده. اسپوف SNI.',pattn_btn:'PattN ✨',pattn_filled:'پیش‌تنظیم PattN پر شد — cipher suites + ALPN http/1.1 + fingerprint=unsafe + Fragment — حالا ذخیره کن',saved_nokv:'ذخیره شد (موقت — KV وصل نیست!)',paths:'مسیرها و اتصال',
backup:'پشتیبان‌گیری',backup_hint:'یک فایل JSON شامل تنظیمات و کاربران. برای انتقال پنل به ورکر/اکانت دیگر همین فایل را بازگردانی کن.',backup_dl:'دانلود پشتیبان',backup_up:'بازگردانی',
limits:'چرا این نسخه بن نمی‌شود؟',limits_text:'کلودفلر رایگان: ۱۰۰هزار درخواست/روز، ۱۰ms CPU برای هر درخواست، ۱۰۰۰ نوشتن KV/روز. نسخهٔ ۶ هیچ آمار مصرفی در KV نمی‌نویسد (فقط وقتی تو ذخیره می‌زنی)، هیچ اسکنی داخل ورکر انجام نمی‌دهد، و رلهٔ ترافیک یک pipe ساده بدون شمارنده است. نتیجه: مصرف CPU و KV نزدیک صفر، مثل BPB.',
about_text:'پنل تک‌فایلی Cat برای Cloudflare Worker. نسخهٔ lean: بدون حسابداری ترافیک، بدون اسکن سمت سرور، رلهٔ کم‌مصرف. مجوز GPL — سورس در گیت‌هاب.',
n_dash:'داشبورد',n_scan:'اسکنر IP',n_nodes:'نودها',n_manage:'مدیریت',no_ips:'هنوز هیچ نود تمیزی ثبت نکردی — کانفیگ‌ها فقط با آدرس ورکر ساخته می‌شوند. از اسکنر بفرست یا دستی اضافه کن:',n_set:'تنظیمات',n_bak:'پشتیبان',
d_new:'کاربر جدید',d_edit:'ویرایش کاربر',d_sub:'نام، پروتکل‌ها و مدت اعتبار',u_name:'نام کاربری',u_rand:'تصادفی',u_protocols:'پروتکل‌های مجاز',u_days:'مدت اعتبار (روز) — ۰ یعنی نامحدود',u_note:'یادداشت',u_enabled:'فعال',
u_noquota:'این نسخه حجم مصرفی را نمی‌شمارد (شمارش حجم همان چیزی بود که KV را پر و ورکر را بن می‌کرد). محدودیت فقط زمانی است.',
unlimited:'نامحدود',days:'روز',left:'مانده',expired:'منقضی',disabled:'غیرفعال',active:'فعال',copied:'کپی شد',deleted:'حذف شد',confirm_del:'این کاربر حذف شود؟',renew:'تمدید ۳۰ روز',toggle:'فعال/غیرفعال',edit:'ویرایش',del:'حذف',qr:'QR',info:'صفحهٔ کاربر',
kv_on:'KV متصل',kv_off:'KV وصل نیست — داده‌ها ذخیره نمی‌شوند!',pass_uuid:'رمز = UUID (تغییرش بده!)',pass_env:'رمز از ENV',pass_set:'رمز تنظیم شده',pass_open:'پنل باز است — رمز بگذار!',
self_wait:'در حال دریافت…',browser_note:'مرورگر فقط دامنه‌ها را می‌تواند تست کند (آی‌پی خام گواهی TLS ندارد). برای اسکن آی‌پی از Cat Client استفاده کن.',
update_check:'بررسی نسخهٔ جدید…',update_ok:'آخرین نسخه را داری',update_new:'نسخهٔ جدید موجود است: ',update_how:'از تب «پنل من» در Cat Client یا با چسباندن فایل جدید در Workers به‌روزرسانی کن.',update_how2:'⬇️ را بزن تا worker.js جدید از خود پنل دانلود شود (گیت‌هاب لازم نیست). بعد در کلادفلر: Workers → پنلت → Edit code → کل کد را با فایل جدید عوض کن → Deploy.',
sync_hint:'اشتراک اصلی را در Cat Client باز می‌کند',restore_ok:'بازگردانی شد',restore_bad:'فایل نامعتبر',sub:'ساب',clash:'Clash',singbox:'sing-box'},
en:{stats:'Panel status',st_users:'Users',st_users_s:'defined in panel',st_active:'Active',st_active_s:'not expired / disabled',st_exp:'Expired / disabled',st_exp_s:'need renewal',st_ips:'Clean IPs',st_cfg:'Configs per sub',
master_links:'Master subscription links',self:'My connection info',users:'Users',search:'Search name or UUID…',f_all:'All',f_active:'Active',f_expired:'Expired',f_disabled:'Disabled',s_new:'Newest',s_exp:'Expiring soon',s_name:'Name',
h_user:'User',h_proto:'Protocol',h_links:'Sub links',h_time:'Time',h_seen:'Last online',h_status:'Status',h_act:'Actions',seen_never:'never',seen_now:'now',seen_min:'%1 min ago',no_users:'No users yet — tap + to create one.',
scan_title:'Clean IP & scanner',scan_why:'Scanning runs on YOUR device, not inside the worker — exactly what BPB and ZEUS do. The worker spends zero requests and results reflect your real network.',
scan_app:'Scan with Cat Client',scan_browser:'Test domains in browser',scan_guide:'Scanner guide',warp_title:'WARP on Xray output (your own keys)',warp_hint:'Your connection to the panel rides inside your own WARP tunnel (your real IP stays hidden even from the worker). Grab keys from wgcf or an Aether export — the worker never registers with Cloudflare (nothing to ban). With WARP on, fragment is bypassed (the tunnel is UDP).',warp_mode:'Mode',warp_off:'Off',warp_chain:'WARP-in-WARP (chained)',warp_sk:'WireGuard SecretKey',warp_pk:'Peer PublicKey (Cloudflare)',warp_reserved:'reserved (optional, comma sep)',warp_endpoint:'Endpoint',warp_warn:'Never paste keys that are not yours. Set mode to Off to disable temporarily — keys are kept.',ext_title:'External subs (merged into yours)',ext_hint:'External sub content is served through the panel\u2019s own domain (raw.github is unreachable from Iran) + URI-list subs are appended after your own configs. ?noext=1 = skip them.',ext_add:'Add sub',ext_preset:'Serverless preset (PattNG)',ext_core:'The Serverless sub needs a recent Xray core (PattNG or v2rayNG ≥2.2.6) and must be imported directly into the app, not merged into the panel sub.',ext_empty:'No external subs yet — try the Serverless preset.',ext_name:'Name',ext_url:'https sub URL',mitm_title:'MITM + DomainFronting (client-side)',mitm_body:'Patterniha\u2019s method to open YouTube/Instagram/WhatsApp/Facebook/Reddit directly without a server — set up on the device (Win/Linux/mac/Android, no root). Create a PERSONAL certificate and trust it. Full guide:',aether_title:'Aether special configs (PattNG)',aether_hint:'Builds aether:// links — the open button launches PattNG (Aether core) directly. Single WARP, WARP-in-WARP (Gool) and MASQUE/HTTP-2 with fragment.',aether_mode:'Type',aether_gool:'WARP-in-WARP (Gool)',aether_name:'Config name',aether_family:'Address family',aether_open:'Add to PattNG',pp_countries:'Add a country repo (Wanwu):',px_addrs:'Import healthy pool as connection IPs',px_addrs_hint:'Healthy pool ProxyIPs are added to your connection-IP list with their country tags and get 💦 configs. Not every ProxyIP also works as an entry address — test/filter them with the app or scanner after importing.',px_none:'Nothing to import — hit pool Refresh first',b_ech:'ECH (encrypted ClientHello — ECH/SIIT-style configs)',b_ech_off:'Off',ech_none:'Current SNI has no ECH (or DNS unreachable) — keep it off',rot_btn_off:'Fixed IP (rotation ON — tap to freeze)',rot_btn_on:'Fixed IP active (tap to resume rotation)',rot_fixed_lbl:'Fixed IP',rot_rot_lbl:'Rotating',rot_now_fixed:'📌 Rotation off — IPs stay fixed',rot_now_rotating:'⚡ Rotation on — fresh set every update',hero_inuse:'In use right now',hero_ports:'Ports',hero_ips:'Clean IPs',hero_px:'ProxyIP relays',hero_exit:'Fixed exit',hero_rot:'Rotation',hero_warn:'⚠️ SNI is still the default skk.moe — if your carrier blocks it, every config dies together. Scan a healthy SNI in the app and hit “⚡ Make panel main SNI”.',hero_build:'Builder',ip_pin:'Pin as fixed IP (always first in sub)',ip_unpin:'Unpin',pin_saved:'📌 This IP stays first — even with rotation on',pin_removed:'Pin removed',s_rot:'Auto config rotation',s_rot_off:'Stable (BPB-like)',s_rot_fetch:'Every update',s_rot_daily:'Daily',s_rot_hint:'Each sub refresh reshuffles the IP order and numbering — a fresh set every time. Daily keeps one arrangement per day; Stable keeps the classic order.',pp_title:'ProxyIP repos (12h auto-update)',pp_hint:'Public ProxyIP feeds (IPs or domains); refreshed every 12h. A ProxyIP is the relay address for opening Cloudflare-fronted sites; IPs reported dead 3× are replaced.',pp_cc:'ProxyIP pool countries — + adds 8 of that country to the panel ProxyIP list',pp_auto:'Auto-append 6 fresh ProxyIPs to subs',pp_src:'Sources: xgonce/Cloudflare_IP · wanwushequ/ProxyIP',pp_dead_note:'Dead? POST /api/prepos {action:"health",dead:[…]} ×3 — replaced automatically.',rp_title:'Repos (12h auto-update)',rp_refresh:'Refresh',rp_hint:'Public clean-IP feeds; refreshed every 12 hours (Cloudflare cron + panel open). IPs reported dead 3× are dropped and replaced on the next refresh.',rp_cc:'Repo pool countries — + adds 16 IPs of that country to your panel list',rp_auto:'Auto-append 8 fresh IPs to subs',rp_add:'Add repo',rp_add_url:'Raw repo URL (https://…)',rp_add_name:'Repo name',rp_empty:'Repo pool is empty — hit Refresh.',rp_nokv:'no KV, not persisted',rp_src:'Sources: arista-project/cf-clean-ips · imatixofficel/Scanner-matix',rp_dead_note:'Dead IP? POST /api/repos {action:"health",dead:[…]} three times — it gets replaced automatically.',n_build:'Config builder',b_title:'Config builder',b_hint:'Build a precise subscription link per carrier, country and port set — never touches the main panel settings.',b_isp:'Carrier profile (advisory — test on your line)',isp_mtn:'Irancell (MTN)',isp_mci:'MCI (Hamrah-e Aval)',isp_rtl:'Rightel / Shatel',isp_tdsl:'TCI',isp_direct:'Direct / Auto',b_isp_mtn_n:'Irancell: keep fragment ON; ports 443 & 8443 with chrome fingerprint.',b_isp_mci_n:'MCI: 443 & 2053; if you have IPv6 keep the family on Both.',b_isp_rtl_n:'Rightel/Shatel: plain ports (80/8080) often work better; short fragment.',b_isp_tdsl_n:'TCI: 443 with iOS fingerprint is usually the most stable.',b_isp_direct_n:'No preset applied — choose the filters yourself.',b_proto:'Protocol',b_fam:'Address family',b_both:'Both',b_ports:'Ports (multi)',b_cc:'Exit country',b_cc_all:'All countries',b_limit:'Config count (1–200)',b_strict:'Country behaviour',b_fb_ok:'Fall back to others',b_only:'Only this country',b_gen:'Build live sub',b_copy:'Copy all',b_link:'Built subscription link',b_prev:'Live preview (first lines)',b_open:'Open in',b_frag:'Fragment & fingerprint (client-side settings)',b_frag_hint:'Fragment is NOT carried in the link — set it in your client (v2rayNG: edit config → Fragment). The value follows the carrier profile.',b_fp:'TLS fingerprint',scan_cat:'IP category',scan_region:'Region',scan_cc:'Panel list countries',scan_search:'Search country',scan_search_ph:'Germany or DE…',scan_cidr:'Add from CIDR range or domain',scan_cidr_ph:'104.16.0.0/24 or cdn.example.com',cidr_add:'Add to list',cidr_ok:'Added %1 IPs',cidr_bad:'Invalid range (example: 104.16.0.0/24)',loc_now:'Current exit',loc_refresh:'Refresh',loc_fail:'Could not read location',scan_jump:'⚙ Sub for this country only',scan_empty:'No IPs match this filter.',scan_note_browser:'Browsers cannot probe raw IPs (SNI/certificate limits) — use “Scan with Cat Client” for raw IPs; the browser test only probes domains.',reg_eu:'🇪🇺 Europe',reg_me:'🕌 Middle East',reg_as:'🌏 Asia',reg_am:'🌎 Americas',reg_af:'🌍 Africa',ev_title:'Events log',ev_time:'Time',ev_ev:'Event',ev_d:'Detail',ev_empty:'No events yet.',ev_ago_h:'%1 h ago',ev_ago_d:'%1 d ago',ip_import:'Import scan results', proxyip_import: 'ProxyIPs imported from Cat Client — press Save',ip_import_hint:'Paste clean IPs or domains (one per line or comma separated). A port may be pinned too: 104.16.1.1:2053#DE — that address is emitted only on its verified port. From Cat Client (Send to Cat Panel) or any other scanner.',
ip_append:'Append',ip_replace:'Replace list',ip_test_btn:'Test from worker',ip_test_reach:'reachable from the worker',ip_list:'Panel IP list',ip_list_hint:'These come first in every subscription. Tap one to remove it.',ip_clear:'Clear all',copy_all:'Copy all',cc_title:'Countries',cc_why:'Tag each address with the country it exits from FOR YOU (paste ip#DE from the Cat Client scanner, or pick from the menu next to each ip). Click a country to serve configs from it only; when all of its ips die, the fastest other country takes over.',cc_auto:'🤖 Automatic (all countries)',cc_fallback:'When every ip of the chosen country is dead',cc_fb_auto:'switch to the fastest other country (recommended)',cc_fb_none:'never leave the country (fail instead)',cc_proxy:'Proxy IPs',cc_proxy_btn:'🌍 Detect proxy-IP countries',cc_hint:'Clash/Mihomo and Cat Client switch automatically; V2Box/sing-box get the chosen country as default with the rest listed. Single-country link: 🔗 next to each country (?country=XX&strict=1).',cc_untagged:'untagged',cc_link:'link for this country only',
settings:'Panel settings',s_title:'Panel title',s_lang:'Language',s_pass:'Panel password',s_stealth:'Hidden panel path',s_stealth_ph:'empty = stealth off',s_stealth_hint:'When set, the root address answers a neutral 404 and the panel only loads at /this-path — defeats workers.dev scanners. New link after saving: domain/path',s_pass_ph:'empty = unchanged',s_protocols:'Protocols',p_vless:'light & fast',p_trojan:'secure alternative',
s_tls:'TLS ports',s_plain:'Non-TLS ports (HTTP)',s_plain_on:'also emit non-TLS configs',s_sni:'SNI / Host',s_sni_ph:'default: skk.moe — your panel host is never exposed in SNI',s_fp:'TLS fingerprint',s_limit:'Max configs per sub',s_port_bad:'Invalid port — enter a number between 1 and 65535',
s_flags:'Options',s_defaults:'append default addresses after mine',s_host:'also include the worker hostname',s_proxy:'Proxy IP (for Cloudflare-hosted sites)',s_proxy_ph:'empty = built-in list',s_proxy_hint:'One per line, host or host:port. Only used when the destination itself is behind Cloudflare.',s_route:'Routing',s_iran:'Iranian sites & apps go direct (no VPN) — banking, Snapp, Digikala work normally',s_ads:'Block ads (ad networks)',s_quic:'Block QUIC/HTTP3 (UDP 443) — BPB-style; on carriers where UDP breaks, clients fall back to TCP+TLS',s_route_hint:'Applied to Clash / sing-box / Xray output. Plain vless:// links carry no rules; the client decides (Cat Client has the same rules built in).',s_frag:'Fragment & advanced TLS',s_frag_on:'Fragment on (split the TLS ClientHello to slip past DPI)',s_frag_confirm:'Fragment will apply to every "Full Xray" and sing-box link (after Save). Some carriers get slightly slower. Enable?',s_frag_hint:'Fragment and cipher suites only apply to the "Full Xray" link and sing-box (a share link cannot carry them). Keep ALPN at http/1.1; h2 breaks WebSocket on Cloudflare.',s_tg:'Telegram bot',s_tg_admins:'admin numeric ids',s_tg_hook:'🤖 Connect bot (webhook)',s_tg_hint:'Create a bot with @BotFather and paste its token; get your numeric id from @userinfobot. Save first, then “Connect bot”. Commands: /users /add /renew /toggle /del /link /ips /country /status. Costs nothing until you message it.',tg_ok:'connected',tg_off:'off',s_gh_title:'🚀 Auto-deploy (bot → GitHub Actions → Cloudflare)',s_gh_repo:'GitHub repo (owner/repo)',s_gh_ref:'deploy branch',s_gh_pat:'GitHub token (Actions: read/write)',s_gh_wf:'workflow file',s_gh_hint:'Telegram commands: /deploy [branch] and /deploys. The Cloudflare token is never stored here — it goes once into GitHub Secrets (CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID). Full guide: docs/telegram-deploy.md',s_chain:'Fixed exit (stable IP & country)',s_chain_ph:'socks5://user:pass@1.2.3.4:1080  or  http://host:3128',s_chain_hint:'The worker sends all traffic out through this server (your own VPS), so the IP/country never changes. Empty = Cloudflare egress (country may vary).',s_chain_mode:'Which destinations',s_chain_all:'everything (fully stable)',s_chain_cf:'only Cloudflare-hosted sites (instead of Proxy IP)',s_chain_strict:'Strict',s_chain_strict_on:'if the chain is down, fail instead of leaking',s_chain_test:'🧪 Test chain',chain_off:'off',chain_ok:'connected',chain_fail:'failed',
save:'Save',cancel:'Cancel',saved:'Saved',
n_clients:'Clients',n_inbounds:'Inbounds',n_about:'About',n_logout:'Log out',ov_info:'Panel info',ov_loc:'Location',ov_up:'Uptime',ov_ver:'Version',ov_check:'Check for Update',ov_services:'Services',svc_run:'RUNNING',svc_idle:'IDLE',ib_count:'Inbounds',ib_ports:'Ports',ib_inbound:'Inbound',ib_copy:'Copy sub URL',ib_hint:'The copied URL serves only that protocol+port (?proto=&port=). Traffic counting is not possible on Cloudflare Workers.',bulk_count:'How many users?',bulk_prefix:'Name prefix (e.g. user)',bulk_done:'Created: ',n_spoof:'SNI & ProxyIP',spoof:'SNI & ProxyIP (spoofing)',spoof_hint:'Configs from this section are built apart from the clean-IP list under their own names: 🧬 SNI … and 🎯 PX … — press Save first, then refresh the subscription.',s_extra_sni:'Extra SNI hosts (one per line — max 8)',s_extra_sni_ph:'speedtest.example.com',s_extra_sni_hint:'Each host gets its own config with that servername (the host must be behind Cloudflare) — for when your own panel SNI gets filtered. SNI spoofing.',pattn_btn:'PattN ✨',pattn_filled:'PattN preset filled — cipher suites + ALPN http/1.1 + fingerprint unsafe + fragment — now press Save',saved_nokv:'Saved (volatile — KV not bound!)',paths:'Paths & connection',
backup:'Backup',backup_hint:'A JSON file with settings and users. Restore it on another worker/account to move the panel.',backup_dl:'Download backup',backup_up:'Restore',
limits:'Why this version does not get banned',limits_text:'Cloudflare free tier: 100k requests/day, 10 ms CPU per request, 1 000 KV writes/day. v6 writes KV only when you save, never scans from the worker, and the relay is a plain pipe with no counters. CPU and KV usage stay near zero, like BPB.',
about_text:'Single-file Cat panel for Cloudflare Workers. Lean edition: no traffic accounting, no server-side scanning, low-CPU relay. GPL — source on GitHub.',
n_dash:'Dashboard',n_scan:'IP Scanner',n_nodes:'Nodes',n_manage:'Manage',no_ips:'No clean nodes yet — configs fall back to the worker address. Send from the scanner or add manually:',n_set:'Settings',n_bak:'Backup',
d_new:'New user',d_edit:'Edit user',d_sub:'Name, protocols and validity',u_name:'Username',u_rand:'random',u_protocols:'Allowed protocols',u_days:'Validity (days) — 0 = unlimited',u_note:'Note',u_enabled:'Enabled',
u_noquota:'This version does not meter traffic (traffic metering is what filled KV and got workers throttled). Limits are time-based only.',
unlimited:'unlimited',days:'days',left:'left',expired:'expired',disabled:'disabled',active:'active',copied:'Copied',deleted:'Deleted',confirm_del:'Delete this user?',renew:'Renew 30 days',toggle:'Enable/disable',edit:'Edit',del:'Delete',qr:'QR',info:'User page',
kv_on:'KV bound',kv_off:'KV NOT bound — nothing persists!',pass_uuid:'password = UUID (change it!)',pass_env:'password from ENV',pass_set:'password set',pass_open:'panel is OPEN — set a password!',
self_wait:'loading…',browser_note:'Browsers can only test domains (raw IPs have no TLS certificate). Use Cat Client to scan IPs.',
update_check:'Checking for updates…',update_ok:'You are on the latest version',update_new:'New version available: ',update_how:'Update from the “My Panel” tab in Cat Client or paste the new file into Workers.',update_how2:'Tap ⬇️ to download the new worker.js straight from this panel (no GitHub needed). Then in Cloudflare: Workers → your panel → Edit code → replace all code with the new file → Deploy.',
sync_hint:'Opens the master subscription in Cat Client',restore_ok:'Restored',restore_bad:'Invalid file',sub:'Sub',clash:'Clash',singbox:'sing-box'}};
var lang=document.documentElement.lang==='en'?'en':'fa';
function t(k){return (I18N[lang][k]!==undefined?I18N[lang][k]:I18N.fa[k])||k}
function $(s,r){return (r||document).querySelector(s)}
function $$(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s))}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function toast(m,bad){var el=$('#toast');el.textContent=m;el.style.borderColor=bad?'var(--red)':'var(--violet)';el.classList.add('show');clearTimeout(el._t);el._t=setTimeout(function(){el.classList.remove('show')},2000)}
function copy(v){function fb(){var i=document.createElement('textarea');i.value=v;document.body.appendChild(i);i.select();try{document.execCommand('copy');toast(t('copied'))}catch(e){}document.body.removeChild(i)}
 if(navigator.clipboard&&navigator.clipboard.writeText)navigator.clipboard.writeText(v).then(function(){toast(t('copied'))},fb);else fb()}
function api(path,opt){opt=opt||{};var o={method:opt.method||'GET',headers:{}};if(opt.body!==undefined){o.headers['content-type']='application/json';o.body=JSON.stringify(opt.body)}
 return fetch(path,o).then(function(r){if(r.status===401){location.href='/';throw new Error('401')}return r.json()})}
function applyI18n(){document.documentElement.lang=lang;document.documentElement.dir=lang==='fa'?'rtl':'ltr';
 $$('[data-i]').forEach(function(el){el.textContent=t(el.getAttribute('data-i'))});$$('[data-ph]').forEach(function(el){el.placeholder=t(el.getAttribute('data-ph'))})}
function fmtDate(ms){if(!ms)return '';var d=new Date(ms);return d.toISOString().slice(0,10)}
function daysLeft(u){if(!u.expiresAt)return null;return Math.ceil((u.expiresAt-Date.now())/86400000)}
function statusOf(u){if(!u.enabled)return 'disabled';if(u.expiresAt&&Date.now()>u.expiresAt)return 'expired';return 'active'}
function randName(){var a='abcdefghjkmnpqrstuvwxyz23456789',s='';for(var i=0;i<6;i++)s+=a[Math.floor(Math.random()*a.length)];return 'cat-'+s}

var CFG=null, USERS=[], editing=null, dirtyScan=false;

/* ---------- views ---------- */
function show(v){$$('.view').forEach(function(s){s.classList.toggle('on',s.id==='v-'+v)});$$('[data-view]').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-view')===v)});
 location.hash=v;window.scrollTo(0,0)}
$$('[data-view]').forEach(function(b){b.addEventListener('click',function(){show(b.getAttribute('data-view'))})});

/* ---------- load ---------- */
(function(){try{var p=new URLSearchParams(location.search).get('proxyips');if(p){window.__pendingProxyIps=p.split(',').map(function(s){return s.trim()}).filter(Boolean).slice(0,32);history.replaceState(null,'',location.pathname)}}catch(e){}})();
(function(){try{var q=new URLSearchParams(location.search).get('ips');if(q){$('#ipPaste').value=q.split(',').join('\\n');history.replaceState(null,'',location.pathname);setTimeout(function(){var n=document.querySelector('[data-view="nodes"');if(n)n.click();importIps(false)},300)}}catch(e){}})();
function load(){return api('/api/settings').then(function(j){CFG=j;renderCfg();return api('/api/users')}).then(function(j){USERS=j.users||[];renderUsers();renderStats();renderHero();renderOverview();renderInbounds();renderScanChips();renderB();loadEvents();loadLoc();rpLoad();ppLoad();aeBuild();var nn=$('#noIpsNote');if(nn)nn.style.display=(CFG.settings.ips&&CFG.settings.ips.length)?'none':'block'})}
function renderOverview(){
 var s=CFG.settings;
 var st=function(k){return t(k)};
 var act=function(on){return on?'<span class="chip ok">'+st('svc_run')+'</span>':'<span class="chip">'+st('svc_idle')+'</span>'};
 var chips=[
  ['VLESS',s.protocols.vless],['Trojan',s.protocols.trojan],
  ['Fragment',!!(s.fragment&&s.fragment.enabled)],
  ['Chain',!!s.chain],['Telegram',!!(CFG.telegram&&CFG.telegram.configured)],
  ['Stealth /'+(s.panelPath||''),!!s.panelPath],
  ['DoH',true],['KV',CFG.kv===true||CFG.kv===undefined?!!CFG.kv:!!CFG.kv]
 ];
 $('#ovServices').innerHTML=chips.map(function(c){return '<span class="chip" style="font-size:12px;padding:6px 10px">'+c[0]+' '+act(c[1])+'</span>'}).join('');
 $('#ovKv').textContent=CFG.kv?st('kv_on'):st('kv_off');$('#ovKv').className='chip '+(CFG.kv?'ok':'warn');
 $('#ovVer').textContent=CFG.version||'';
 $('#ovLoc').textContent='Cloudflare — checking…';
 fetch('/api/colo').then(function(r){return r.json()}).then(function(j){var v=(j.colo||'?')+(j.country?' · '+j.country:'');$('#ovLoc').textContent='Cloudflare '+v}).catch(function(){$('#ovLoc').textContent='Cloudflare'});
 var up='';
 if(s.installedAt){var d=Math.floor((Date.now()-s.installedAt)/86400000);up=d>0?d+'d':Math.max(1,Math.floor((Date.now()-s.installedAt)/3600000))+'h'}
 $('#ovUp').textContent=up||'—';
}
function renderInbounds(){
 var s=CFG.settings;var host=CFG.host;var rows=[];
 var tls=s.tlsPorts||[443];var plain=s.plainEnabled?(s.plainPorts||[]):[];
 ['vless','trojan'].forEach(function(p){
  if(!s.protocols[p])return;
  tls.forEach(function(pt){rows.push({p:p,pt:pt,tls:true})});
  plain.forEach(function(pt){rows.push({p:p,pt:pt,tls:false})});
 });
 $('#ibCount').textContent=rows.length;
 $('#ibPorts').textContent=(tls||[]).length+' + '+(plain||[]).length;
 $('#ibUsers').textContent=(USERS&&USERS.length?USERS.length:1);
 $('#ibRows').innerHTML=rows.map(function(r){
  var path=r.p==='vless'?t_paths().vless:t_paths().trojan;
  var url='https://'+host+path+(r.tls?'':'')+'?proto='+r.p+'&port='+r.pt;
  return '<tr><td><span class="chip '+(r.tls?'ok':'')+'" style="font-size:11px">'+r.p.toUpperCase()+' :'+r.pt+(r.tls?' TLS':'')+'</span></td>'+
   '<td class="mono" style="font-size:11px;max-width:220px;overflow:hidden;text-overflow:ellipsis">'+esc(host+path)+'</td>'+
   '<td>'+esc(r.p)+'</td>'+
   '<td><button class="btn sm" data-copy="'+esc(url)+'" data-i="ib_copy"></button></td></tr>';
 }).join('')||'<tr><td colspan="4" class="dim">—</td></tr>';
 applyI18n();
}
function t_paths(){return CFG.paths||{vlessPath:'/vless',trojanPath:'/trojan'}}
$('#btnOvUpdate').addEventListener('click',function(){var b=$('#ovUpdateBox');b.textContent=t('update_check');api('/api/update-check').then(function(j){if(!j.ok||!j.latest){b.textContent='?';return}b.innerHTML=j.latest===j.current?'<span class="chip ok">\u2713 '+esc(j.current)+'</span>':'<span class="chip warn">\u2b06\ufe0f '+esc(j.latest)+'</span> <a class="btn sm p" href="/api/update-download" style="vertical-align:middle">\u2b07\ufe0f worker.js</a><div class="small mute" style="margin-top:6px">'+t('update_how2')+'</div>'})});
$('#btnBulk').addEventListener('click',function(){
 var n=Number(prompt(t('bulk_count'),'5'));if(!n||n<1)return;
 var prefix=prompt(t('bulk_prefix'),'user');if(prefix===null)return;
 var chain=Promise.resolve();var made=0;
 for(var i=1;i<=n;i++){(function(name){chain=chain.then(function(){return api('/api/users',{method:'POST',body:{name:name}})}).then(function(){made++})})(prefix+'-'+i)}
 chain.then(function(){toast(t('bulk_done')+made);return load()}).catch(function(){toast('error',true)});
});
function renderStats(){var active=USERS.filter(function(u){return statusOf(u)==='active'}).length;
 $('#stUsers').textContent=USERS.length;$('#stActive').textContent=active;$('#stExp').textContent=USERS.length-active;
 var ips=CFG.settings.ips.length;$('#stIps').textContent=ips;$('#stIpsS').textContent=(CFG.settings.useDefaults?'+ '+CFG.defaults.addresses.length+' default':'');
 var addrs=ips+(CFG.settings.useDefaults?CFG.defaults.addresses.length:0)+(CFG.settings.includeHost?1:0);
 var ports=CFG.settings.tlsPorts.length+(CFG.settings.plainEnabled?CFG.settings.plainPorts.length:0);var protos=(CFG.settings.protocols.vless?1:0)+(CFG.settings.protocols.trojan?1:0);
 $('#stCfg').textContent=Math.min(CFG.settings.entryLimit,addrs*ports*protos);$('#stCfgS').textContent=addrs+' × '+ports+' × '+protos;
 var kv=$('#chipKv');kv.textContent=(CFG.kv?'🟢 ':'🔴 ')+t(CFG.kv?'kv_on':'kv_off');kv.className='chip '+(CFG.kv?'ok':'bad');
 var ps=$('#chipPass');var k=CFG.open?'pass_open':CFG.passwordSource==='panel'?'pass_set':CFG.passwordSource==='env'?'pass_env':'pass_uuid';ps.textContent=t(k);ps.className='chip '+(k==='pass_set'||k==='pass_env'?'ok':'warn');
 $('#chipHost').textContent=CFG.host;$('#passState').textContent=t(k);$('#passState').className='chip '+(k==='pass_set'||k==='pass_env'?'ok':'warn');}
function renderCfg(){var s=CFG.settings,f=$('#fSettings');f.elements.panelPath.value=s.panelPath||'';f.elements.ptitle.value=s.title||'';f.elements.plang.value=s.lang;f.elements.sni.value=s.sni||'';f.elements.fingerprint.value=s.fingerprint;f.elements.entryLimit.value=s.entryLimit;var sf=$('#fSpoof');sf.elements.extraSnis.value=(s.extraSnis||[]).join('\\n');sf.elements.proxyIps.value=(s.proxyIps||[]).join('\\n');if(window.__pendingProxyIps){var cur=sf.elements.proxyIps.value.split(/[\\s,]+/).filter(Boolean),add=window.__pendingProxyIps;window.__pendingProxyIps=null;sf.elements.proxyIps.value=add.concat(cur.filter(function(x){return add.indexOf(x)<0})).slice(0,32).join('\\n');setTimeout(function(){var n=document.querySelector('[data-view=\"spoof\"]');if(n)n.click();sf.elements.proxyIps.scrollIntoView({behavior:'smooth',block:'center'});toast(t('proxyip_import'))},200)}f.elements.chain.value=s.chain||'';f.elements.tgToken.value=s.tgToken||'';f.elements.tgAdmins.value=(s.tgAdmins||[]).join(', ');f.elements.ghRepo.value=s.ghRepo||'';f.elements.ghRef.value=s.ghRef||'';f.elements.ghPat.value=s.ghPat||'';f.elements.ghWorkflow.value=s.ghWorkflow||'deploy-worker.yml';var tg=$('#tgState');tg.textContent=CFG.telegram&&CFG.telegram.configured?t('tg_ok'):t('tg_off');tg.className='chip '+(CFG.telegram&&CFG.telegram.configured?'ok':'');var srp=$('#subRotatePick');if(srp)$$('#subRotatePick button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-v')===(s.subRotate||'fetch'))});renderRotBtn();renderWarp();renderExt();
if(srp&&!srp.__w){srp.__w=1;srp.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;$$('#subRotatePick button').forEach(function(x){x.classList.remove('on')});b.classList.add('on')})}
$('#swIran').classList.toggle('on',s.bypassIran!==false);$('#swAds').classList.toggle('on',!!s.blockAds);$('#swQuic').classList.toggle('on',!!s.blockQuic);$('#swFrag').classList.toggle('on',!!(s.fragment&&s.fragment.enabled));f.elements.fragPackets.value=(s.fragment||{}).packets||'tlshello';f.elements.fragLength.value=(s.fragment||{}).length||'';f.elements.fragInterval.value=(s.fragment||{}).interval||'';f.elements.alpn.value=s.alpn||'http/1.1';f.elements.cipherSuites.value=s.cipherSuites||'';f.elements.chainMode.value=s.chainMode||'all';$('#swStrict').classList.toggle('on',!!s.chainStrict);var cs=$('#chainState');cs.textContent=CFG.chain?(CFG.chain.type+' · '+CFG.chain.host):t('chain_off');cs.className='chip '+(CFG.chain?'ok':'');
 f.elements.pv.checked=s.protocols.vless;f.elements.pt.checked=s.protocols.trojan;syncProto('#pVless','#pTrojan');
 $('#swPlain').classList.toggle('on',s.plainEnabled);$('#swDefaults').classList.toggle('on',s.useDefaults);$('#swHost').classList.toggle('on',s.includeHost);
 pick('#pickTls',CFG.defaults.tlsPorts,s.tlsPorts);pick('#pickPlain',CFG.defaults.plainPorts,s.plainPorts);
 if(s.title)$('#brandTitle').textContent=s.title;
 $('#pathsBox').innerHTML='<div class="lk"><span>VLESS</span><code>'+esc(CFG.paths.vlessPath)+'</code></div><div class="lk"><span>Trojan</span><code>'+esc(CFG.paths.trojanPath)+'</code></div><div class="lk"><span>SNI</span><code>'+esc(CFG.sni)+'</code></div><div class="lk"><span>UUID</span><code>'+esc(CFG.uuid)+'</code><button class="btn sm" data-copy="'+esc(CFG.uuid)+'">📋</button></div>'+
  (CFG.env.hasUuid?'':'<div class="note w small" style="margin-top:8px">UUID از نام ورکر مشتق شده؛ برای ثابت ماندن بعد از تغییر نام، متغیر UUID را در Workers → Settings تنظیم کن.</div>');
 renderIps();}
function renderHero(){var el=$('#heroChips');if(!el||!CFG||!CFG.settings)return;var s=CFG.settings,def=String(s.sni||'').trim()==='';
var tls=(s.tlsPorts||[]).slice(0,3).join('/'),pl=s.plainEnabled?((s.plainPorts||[]).slice(0,3).join('/')):null;
var ROT={fetch:t('s_rot_fetch'),daily:t('s_rot_daily'),off:t('s_rot_off')};
var chips=[['🎯 SNI',def?'skk.moe ⚠️':(s.sni||''),def],['🚪 '+t('hero_ports'),tls+(pl?' · '+pl:''),false],['💦 '+t('hero_ips'),(s.ips||[]).length+((s.pinnedIps&&s.pinnedIps.length)?' (📌'+s.pinnedIps.length+')':''),false],['🎭 '+t('hero_px'),(s.proxyIps||[]).length,false],['⛓ '+t('hero_exit'),s.chain?t('chain_ok'):t('chain_off'),false],['🔄 '+t('hero_rot'),ROT[s.subRotate||'fetch']||'',false]];
el.innerHTML=chips.map(function(c){return '<span class="chip mono"'+(c[2]?' style="border-color:#e05252;color:#ffb4b4"':'')+'>'+c[0]+': <b>'+esc(String(c[1]))+'</b></span>'}).join('')
+(def?'<div class="note w small" style="margin-top:8px">'+t('hero_warn')+'</div>':'');}
function pick(sel,all,chosen){var box=$(sel);box.innerHTML='';all.concat((chosen||[]).filter(function(p){return all.indexOf(p)<0})).forEach(function(p){var b=document.createElement('button');b.type='button';b.textContent=p;b.dataset.v=p;if(chosen.indexOf(p)>=0)b.classList.add('on');b.onclick=function(){b.classList.toggle('on')};box.appendChild(b)})}
function picked(sel){return $$('button.on',$(sel)).map(function(b){return Number(b.dataset.v)})}
function addPortTo(sel,inputId){var v=Number(($(inputId).value||'').trim());if(!(v>=1&&v<=65535)){toast(t('s_port_bad'),true);return}var b=document.createElement('button');b.type='button';b.textContent=v;b.dataset.v=v;b.classList.add('on');b.onclick=function(){b.classList.toggle('on')};$(sel).appendChild(b);$(inputId).value=''}
$('#btnAddTls').addEventListener('click',function(){addPortTo('#pickTls','#addTls')});
$('#btnAddPlain').addEventListener('click',function(){addPortTo('#pickPlain','#addPlain')});
function syncProto(a,b){[a,b].forEach(function(s){var l=$(s);l.classList.toggle('on',$('input',l).checked)})}
$$('#pVless input,#pTrojan input').forEach(function(i){i.addEventListener('change',function(){syncProto('#pVless','#pTrojan')})});
$$('#uVless input,#uTrojan input').forEach(function(i){i.addEventListener('change',function(){syncProto('#uVless','#uTrojan')})});
$$('.sw').forEach(function(s){s.addEventListener('click',function(){s.classList.toggle('on')})});

$('#fSettings').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var body={title:f.elements.ptitle.value,panelPath:f.elements.panelPath.value.trim().toLowerCase(),lang:f.elements.plang.value,sni:f.elements.sni.value,fingerprint:f.elements.fingerprint.value,entryLimit:Number(f.elements.entryLimit.value),
 subRotate:(function(){var b=document.querySelector('#subRotatePick button.on');return b?b.getAttribute('data-v'):'fetch'})(),protocols:{vless:f.elements.pv.checked,trojan:f.elements.pt.checked},tlsPorts:picked('#pickTls'),plainPorts:picked('#pickPlain'),plainEnabled:$('#swPlain').classList.contains('on'),useDefaults:$('#swDefaults').classList.contains('on'),includeHost:$('#swHost').classList.contains('on'),chain:f.elements.chain.value.trim(),tgToken:f.elements.tgToken.value.trim(),tgAdmins:f.elements.tgAdmins.value.split(/[\\s,]+/).filter(Boolean),ghRepo:f.elements.ghRepo.value.trim(),ghRef:f.elements.ghRef.value.trim(),ghPat:f.elements.ghPat.value.trim(),ghWorkflow:f.elements.ghWorkflow.value.trim(),bypassIran:$('#swIran').classList.contains('on'),blockAds:$('#swAds').classList.contains('on'),blockQuic:$('#swQuic').classList.contains('on'),fragment:{enabled:$('#swFrag').classList.contains('on'),packets:f.elements.fragPackets.value,length:f.elements.fragLength.value.trim(),interval:f.elements.fragInterval.value.trim()},alpn:f.elements.alpn.value,cipherSuites:f.elements.cipherSuites.value.trim(),chainMode:f.elements.chainMode.value,chainStrict:$('#swStrict').classList.contains('on')};
 if(f.elements.password.value)body.password=f.elements.password.value;var changedLang=body.lang!==lang;
 api('/api/settings',{method:'PUT',body:body}).then(function(j){if(!j.ok)throw 0;f.elements.password.value='';toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);if(changedLang){location.reload();return}return load()}).catch(function(){toast('error',true)})});

$('#fSpoof').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var body={extraSnis:f.elements.extraSnis.value.split(/[\\s,]+/).filter(Boolean),proxyIps:f.elements.proxyIps.value.split(/[\\s,]+/).filter(Boolean)};
 api('/api/settings',{method:'PUT',body:body}).then(function(j){if(!j.ok)throw 0;toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);return load()}).catch(function(){toast('error',true)})});
$('#btnPattn').addEventListener('click',function(){var f=$('#fSettings');f.elements.alpn.value='http/1.1';f.elements.cipherSuites.value='TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256';f.elements.fingerprint.value='unsafe';$('#swFrag').classList.add('on');f.elements.fragPackets.value='tlshello';f.elements.fragLength.value='1-3';f.elements.fragInterval.value='1';toast(t('pattn_filled'))});
document.addEventListener('click',function(e){var b=e.target.closest('[data-cc]');if(!b)return;api('/api/countries',{method:'PUT',body:{country:b.getAttribute('data-cc')}}).then(function(){toast(t('saved'));return load()}).catch(function(){toast('error',true)})});
document.addEventListener('change',function(e){var sel=e.target.closest('[data-ipcc]');if(!sel)return;var ip=sel.getAttribute('data-ipcc'),cc=sel.value;var body=cc?{ipCountries:{}}:{clearIp:ip};if(cc)body.ipCountries[ip]=cc;api('/api/countries',{method:'PUT',body:body}).then(function(){return load()}).catch(function(){toast('error',true)})});
$('#btnTgHook').addEventListener('click',function(){var o=$('#tgHookOut');o.textContent='…';api('/api/telegram/webhook',{method:'POST'}).then(function(j){o.textContent=j.ok?'🟢 @'+j.bot:'🔴 '+(j.error||j.description||'');return load()}).catch(function(){o.textContent='🔴'})});
$('#swFrag').addEventListener('click',function(){if($('#swFrag').classList.contains('on')&&!confirm(t('s_frag_confirm'))){$('#swFrag').classList.remove('on')}});
$('#btnPathRnd').addEventListener('click',function(){var c='abcdefghijklmnopqrstuvwxyz0123456789',s='';for(var i=0;i<10;i++)s+=c[Math.floor(Math.random()*c.length)];$('#fSettings').elements.panelPath.value=s;});
$('#ccFallback').addEventListener('change',function(){api('/api/countries',{method:'PUT',body:{countryFallback:$('#ccFallback').value}}).then(function(){toast(t('saved'));return load()})});
$('#btnProxyGeo').addEventListener('click',function(){var o=$('#proxyGeoOut');o.textContent='…';api('/api/proxy-geo',{method:'POST'}).then(function(j){var f=j.found||{};o.textContent=Object.keys(f).map(function(k){return flag(f[k])+' '+k}).join('  ')||'—';return load()}).catch(function(){o.textContent='✗'})});
$('#btnChainTest').addEventListener('click',function(){var o=$('#chainTestOut');var c=$('#fSettings').elements.chain.value.trim();if(!c){o.textContent=t('chain_off');return}o.textContent='…';api('/api/chain-test',{method:'POST',body:{chain:c}}).then(function(j){o.textContent=(j.ok?'🟢 '+t('chain_ok')+' · '+j.ms+'ms':'🔴 '+t('chain_fail')+' · '+(j.error||j.status||''))}).catch(function(e){o.textContent='🔴 '+t('chain_fail')+' · '+(e&&e.message||'')})});

/* ---------- users ---------- */
function protoChips(u){var h='';if(u.protocols.vless)h+='<span class="chip v">VLESS</span> ';if(u.protocols.trojan)h+='<span class="chip t">Trojan</span>';return h}
function seenCell(u){
 var ts=u.lastOnline||0;
 if(!ts)return '<span class="dim small">'+t('seen_never')+'</span>';
 var m=Math.floor((Date.now()-ts)/60000);
 var v=m<6?t('seen_now'):(m<60?(t('seen_min')||'').replace('%1',m):(m<1440?Math.floor(m/60)+'h':Math.floor(m/1440)+'d'));
 return '<span class="small" style="color:'+(m<6?'var(--green)':'var(--mute)')+'">'+v+'</span>';
}
function timeCell(u){var d=daysLeft(u);if(d===null)return '<span class="chip">♾️ '+t('unlimited')+'</span>';var total=Math.max(1,Math.round((u.expiresAt-u.createdAt)/86400000));var pct=Math.max(0,Math.min(100,Math.round(d/total*100)));
 var cls=d<=0?'d':d<=5?'w':'';return '<div class="small">'+(d>0?d+' '+t('days')+' '+t('left'):t('expired'))+' <span class="dim">· '+fmtDate(u.expiresAt)+'</span></div><div class="bar '+cls+'" style="margin-top:4px;width:120px"><i style="width:'+pct+'%"></i></div>'}
function statusChip(u){var s=statusOf(u);return '<span class="chip '+(s==='active'?'ok':'bad')+'">'+(s==='active'?'🟢':s==='expired'?'⏰':'⛔')+' '+t(s)+'</span>'}
function linkBtns(u){return '<div class="act"><button class="btn sm g" data-copy="'+esc(u.links.sub)+'">🔗 '+t('sub')+'</button><button class="btn sm c" data-copy="'+esc(u.links.clash)+'">'+t('clash')+'</button><button class="btn sm" data-copy="'+esc(u.links.singbox)+'">'+t('singbox')+'</button><button class="btn sm" data-copy="'+esc(u.links.xray)+'">Xray</button><button class="btn sm" data-qr="'+esc(u.links.sub)+'" data-qrl="'+esc(u.name)+'">▦</button><a class="btn sm" href="'+esc(u.links.info)+'" target="_blank" rel="noopener">↗</a></div>'}
function actBtns(u){return '<div class="act"><button class="ib" data-c="violet" data-edit="'+u.id+'" title="'+t('edit')+'">✏️</button><button class="ib" data-c="green" data-renew="'+u.id+'" title="'+t('renew')+'">🔁</button><button class="ib" data-c="amber" data-toggle="'+u.id+'" title="'+t('toggle')+'">'+(u.enabled?'⏸':'▶️')+'</button><button class="ib" data-c="red" data-del="'+u.id+'" title="'+t('del')+'">🗑</button></div>'}
function filtered(){var q=($('#q').value||'').toLowerCase(),f=$('#flt').value,s=$('#srt').value;var list=USERS.filter(function(u){if(q&&u.name.toLowerCase().indexOf(q)<0&&u.id.indexOf(q)<0)return false;if(f!=='all'&&statusOf(u)!==f)return false;return true});
 list.sort(function(a,b){if(s==='name')return a.name.localeCompare(b.name);if(s==='exp'){var x=a.expiresAt||9e15,y=b.expiresAt||9e15;return x-y}return b.createdAt-a.createdAt});return list}
function renderUsers(){var list=filtered();$('#empty').style.display=USERS.length?'none':'block';
 $('#rows').innerHTML=list.map(function(u){return '<tr><td><div class="b">'+esc(u.name)+'</div><div class="dim small mono">'+u.id.slice(0,8)+'…</div>'+(u.note?'<div class="dim small">'+esc(u.note)+'</div>':'')+'</td><td>'+protoChips(u)+'</td><td>'+linkBtns(u)+'</td><td>'+timeCell(u)+'</td><td>'+seenCell(u)+'</td><td>'+statusChip(u)+'</td><td>'+actBtns(u)+'</td></tr>'}).join('');
 $('#cards').innerHTML=list.map(function(u){return '<div class="uc"><div class="hd"><span class="nm">'+esc(u.name)+'</span>'+statusChip(u)+'<span style="margin-inline-start:auto">'+protoChips(u)+'</span></div><div class="kv"><div><span>'+t('h_time')+'</span>'+timeCell(u)+'</div><div><span>UUID</span><span class="mono" style="color:var(--mute)">'+u.id.slice(0,13)+'…</span></div></div>'+linkBtns(u)+'<div style="height:8px"></div>'+actBtns(u)+'</div>'}).join('')}
['input','change'].forEach(function(e){$('#q').addEventListener(e,renderUsers);$('#flt').addEventListener(e,renderUsers);$('#srt').addEventListener(e,renderUsers)});

document.addEventListener('click',function(e){var b;
 if((b=e.target.closest('[data-copy]'))){copy(b.getAttribute('data-copy'));return}
 if((b=e.target.closest('[data-qr]'))){showQr(b.getAttribute('data-qr'),b.getAttribute('data-qrl')||'');return}
 if((b=e.target.closest('[data-edit]'))){openDrawer(USERS.filter(function(u){return u.id===b.getAttribute('data-edit')})[0]);return}
 if((b=e.target.closest('[data-renew]'))){api('/api/users/'+b.getAttribute('data-renew')+'/renew',{method:'POST',body:{days:30}}).then(function(){toast('✓');return load()});return}
 if((b=e.target.closest('[data-toggle]'))){api('/api/users/'+b.getAttribute('data-toggle')+'/toggle',{method:'POST',body:{}}).then(function(){toast('✓');return load()});return}
 if((b=e.target.closest('[data-del]'))){if(!confirm(t('confirm_del')))return;api('/api/users/'+b.getAttribute('data-del'),{method:'DELETE'}).then(function(){toast(t('deleted'));return load()});return}
 if((b=e.target.closest('[data-close]'))){closeDrawer();return}
 if((b=e.target.closest('[data-ippin]'))){var pv=ipKey(b.getAttribute('data-ippin'));var pins=(CFG.settings.pinnedIps||[]).filter(function(p){return ipKey(p)!==pv});pins.push(b.getAttribute('data-ippin'));api('/api/settings',{method:'PUT',body:{pinnedIps:pins.slice(-5)}}).then(function(j){if(!j.ok)throw 0;CFG.settings.pinnedIps=j.settings.pinnedIps;toast(t('pin_saved'));return load()}).catch(function(){toast('error',true)});return}
 if((b=e.target.closest('[data-ipunpin]'))){var uv=ipKey(b.getAttribute('data-ipunpin'));var upins=(CFG.settings.pinnedIps||[]).filter(function(p){return ipKey(p)!==uv});api('/api/settings',{method:'PUT',body:{pinnedIps:upins}}).then(function(j){if(!j.ok)throw 0;CFG.settings.pinnedIps=j.settings.pinnedIps;toast(t('pin_removed'));return load()}).catch(function(){toast('error',true)});return}
 if((b=e.target.closest('[data-ipdel]'))){var ip=b.getAttribute('data-ipdel');var next=CFG.settings.ips.filter(function(x){return x!==ip});api('/api/ips',{method:'POST',body:{ips:next,replace:true}}).then(function(){return load()});return}
});
function showQr(text,label){$('#qrBox').innerHTML='<img alt="QR" src="/qr.svg?text='+encodeURIComponent(text)+'">';$('#qrLabel').textContent=label;$('#qrModal').classList.add('show')}

/* ---------- drawer ---------- */
function openDrawer(u){editing=u||null;var f=$('#fUser');$('#dTitle').textContent=u?t('d_edit')+': '+u.name:t('d_new');f.elements.uname.value=u?u.name:randName();f.elements.note.value=u?u.note:'';
 f.elements.pv.checked=u?u.protocols.vless:true;f.elements.pt.checked=u?u.protocols.trojan:true;syncProto('#uVless','#uTrojan');
 var d=u?(u.expiresAt?Math.max(0,Math.ceil((u.expiresAt-Date.now())/86400000)):0):30;f.elements.days.value=d;$('#swEnabled').classList.toggle('on',u?u.enabled:true);
 var pk=$('#pickDays');pk.innerHTML='';[0,7,30,60,90,180,365].forEach(function(n){var b=document.createElement('button');b.type='button';b.textContent=n?n+' '+t('days'):t('unlimited');b.onclick=function(){f.elements.days.value=n};pk.appendChild(b)});
 $('#dLinks').innerHTML=u?'<div class="card" style="padding:12px"><div class="small mute b" style="margin-bottom:6px">'+t('h_links')+'</div>'+[['sub',u.links.sub],['clash',u.links.clash],['singbox',u.links.singbox],['info',u.links.info]].map(function(p){return '<div class="lk"><span class="small" style="min-width:56px">'+t(p[0])+'</span><input class="mono" readonly value="'+esc(p[1])+'"><button class="btn sm" type="button" data-copy="'+esc(p[1])+'">📋</button><button class="btn sm" type="button" data-qr="'+esc(p[1])+'" data-qrl="'+esc(u.name)+'">▦</button></div>'}).join('')+'</div>':'';
 $('#drawer').classList.add('show')}
function closeDrawer(){$('#drawer').classList.remove('show');editing=null}
$('#btnAdd').addEventListener('click',function(){openDrawer(null)});
$('#btnRandName').addEventListener('click',function(){$('#fUser').elements.uname.value=randName()});
$('#fUser').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;if(!f.elements.pv.checked&&!f.elements.pt.checked){toast('protocol?',true);return}
 var body={name:f.elements.uname.value.trim(),note:f.elements.note.value,protocols:{vless:f.elements.pv.checked,trojan:f.elements.pt.checked},enabled:$('#swEnabled').classList.contains('on'),days:Number(f.elements.days.value)||0};
 var p=editing?api('/api/users/'+editing.id,{method:'PUT',body:body}):api('/api/users',{method:'POST',body:body});
 p.then(function(j){if(!j.ok)throw 0;toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);closeDrawer();return load()}).catch(function(){toast('error',true)})});

/* ---------- master links / self ---------- */
$('#btnMasterLinks').addEventListener('click',function(){openMasterLinks()});
function openMasterLinks(){var L=CFG.links;var u={id:CFG.uuid,name:t('master_links'),links:L,protocols:CFG.settings.protocols,enabled:true,expiresAt:0,createdAt:0,note:''};
 $('#dTitle').textContent=t('master_links');$('#fUser').style.display='none';$('#dLinks').innerHTML='<div class="card" style="padding:12px">'+[['sub',L.sub],['clash',L.clash],['singbox',L.singbox],['info',L.info]].map(function(p){return '<div class="lk"><span class="small" style="min-width:56px">'+t(p[0])+'</span><input class="mono" readonly value="'+esc(p[1])+'"><button class="btn sm" type="button" data-copy="'+esc(p[1])+'">📋</button><button class="btn sm" type="button" data-qr="'+esc(p[1])+'" data-qrl="master">▦</button></div>'}).join('')+'<div class="row" style="margin-top:10px"><a class="btn p" href="catclient://add-sub?url='+encodeURIComponent(L.sub)+'&name='+encodeURIComponent(CFG.settings.title||'Cat Panel')+'">🐱 Cat Client</a><a class="btn" href="v2rayng://install-sub?url='+encodeURIComponent(L.sub)+'&name=CatPanel">v2rayNG</a><a class="btn" href="hiddify://import/'+esc(L.sub)+'">Hiddify</a></div></div>';
 $('#drawer').classList.add('show');$$('[data-close]').forEach(function(b){b.addEventListener('click',function(){$('#fUser').style.display=''},{once:true})})}
$('#btnSync').addEventListener('click',function(){toast(t('sync_hint'));location.href='catclient://add-sub?url='+encodeURIComponent(CFG.links.sub)+'&name='+encodeURIComponent(CFG.settings.title||'Cat Panel')});
$('#btnRefresh').addEventListener('click',function(){load().then(function(){toast('✓')})});
$('#btnSelf').addEventListener('click',function(){var b=$('#selfBox');b.textContent=t('self_wait');api('/api/self').then(function(j){b.innerHTML='<span class="chip mono">'+esc(j.ip)+'</span> <span class="chip">'+esc(j.country)+(j.city?' · '+esc(j.city):'')+'</span> <span class="chip">colo '+esc(j.colo)+'</span> <span class="chip">AS'+esc(j.asn)+' '+esc(j.asOrganization)+'</span> <span class="chip">'+esc(j.httpProtocol)+' / '+esc(j.tlsVersion)+'</span>'})});

/* ---------- clean IP ---------- */
var CC_LIST=['','DE','NL','FR','GB','US','TR','AE','FI','SE','PL','AT','CH','IT','ES','CZ','RO','BG','HU','UA','RU','AM','GE','KZ','IN','SG','JP','KR','HK','TW','AU','CA','BR','IQ','OM','QA','SA','BH','KW','IE','NO','DK','BE','PT','GR','RS','LT','LV','EE','MD','CY','IL','EG','ZA','MY','TH','VN','ID','PH','MX','AR','CL','PK','AZ','UZ'];
function flag(cc){return cc?String.fromCodePoint(0x1f1e6+cc.charCodeAt(0)-65,0x1f1e6+cc.charCodeAt(1)-65):'🌐'}
function ccSelect(ip,cur){return '<select data-ipcc="'+esc(ip)+'" title="country" style="width:auto;padding:0 4px;height:22px;font-size:12px">'+CC_LIST.map(function(c){return '<option value="'+c+'"'+(c===cur?' selected':'')+'>'+flag(c)+(c?' '+c:'')+'</option>'}).join('')+'</select>'}
function renderCountries(){var S=CFG.countries||{countries:[],untagged:[]};var st=$('#ccState');st.textContent=S.preferred?flag(S.preferred)+' '+S.preferred:t('cc_auto');st.className='chip '+(S.preferred?'ok':'');$('#ccFallback').value=S.fallback||'auto';
 var h='<span class="chip'+(S.preferred?'':' ok')+'" data-cc="" style="cursor:pointer">'+t('cc_auto')+'</span>';
 S.countries.forEach(function(c){h+='<span class="chip'+(c.code===S.preferred?' ok':'')+'" data-cc="'+c.code+'" style="cursor:pointer">'+esc(c.label)+' · '+c.addresses.length+(c.proxies.length?' · P'+c.proxies.length:'')+'</span><button class="ib" data-copy="'+esc(CFG.links.sub+'?country='+c.code+'&strict=1')+'" title="'+t('cc_link')+'">🔗</button>'});
 if(S.untagged.length)h+='<span class="chip">🌐 '+t('cc_untagged')+' · '+S.untagged.length+'</span>';$('#ccList').innerHTML=h}
function ipKey(a){var st=String(a);var c=st.lastIndexOf(':');if(c>-1&&/^\\d{1,5}$/.test(st.slice(c+1))&&st.indexOf(':')===c)st=st.slice(0,c);return st.replace(/^\\[/,'').replace(/\\]$/,'').toLowerCase()}
function renderIps(){var ips=CFG.settings.ips,tags=CFG.settings.ipCountries||{},pins=CFG.settings.pinnedIps||[],SRC=CFG.settings.ipSources||{};$('#ipCount').textContent=ips.length;var TT=window.__ipTest||{};
$('#ipList').innerHTML=ips.length?ips.map(function(ip){var isPin=pins.some(function(p){return ipKey(p)===ipKey(ip)});var st=TT[ip]||TT[ipKey(ip)];var w=st?(st.ok?'<span style="color:#34d399">worker\u2713'+st.ms+'ms'+(st.cc?' '+flag(st.cc):'')+'</span>':'<span style="color:#f87171">worker\u2717</span>'):'';
var so=SRC[ip]||SRC[ipKey(ip)]||SRC[String(ip).split('#')[0]]||SRC[ipKey(String(ip).split('#')[0])];var src=so?'<span style="color:#7dd3fc">\ud83c\udff7 '+(so.src==='scanner'?'\u0627\u0632 \u0627\u0633\u06a9\u0646\u0631':'\u0648\u0631\u0648\u062f \u062f\u0633\u062a\u06cc')+' \u00b7 \ud83d\udcf6 '+so.ms+'ms</span>':'';
return '<span class="chip mono'+(isPin?' v':'')+'">'+ccSelect(ip,tags[ip]||'')+' '+esc(ip)+(src?' '+src:'')+(w?' '+w:'')+' <b data-ip'+(isPin?'unpin':'pin')+'="'+esc(ip)+'" title="'+t(isPin?'ip_unpin':'ip_pin')+'" style="cursor:pointer">'+(isPin?'\ud83d\udccc':'\ud83d\udccd')+'</b> <b data-ipdel="'+esc(ip)+'" title="remove" style="cursor:pointer">\u2715</b></span>'}).join(''):'<span class="dim small">\u2014</span>';renderCountries();renderRotBtn();
 $('#btnScanApp').href='catclient://scan?sni='+encodeURIComponent(CFG.sni||CFG.host)+'&panel='+encodeURIComponent(location.origin)}
function importIps(replace){var raw=$('#ipPaste').value;var ips=raw.split(/[\\s,;]+/).map(function(s){s=s.trim();if(/^\\[[0-9a-f:]+\\]:\\d{1,5}$/i.test(s))return s;s=s.replace(/^\\[/,'').replace(/\\]$/,'').replace(/[#|=][A-Za-z]{2}$/,'');return /^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-f:]+|[a-z0-9.-]+\\.[a-z]{2,})(?::\\d{1,5})?$/i.test(s)?s:''}).filter(Boolean);
if(!ips.length){toast('0',true);return}api('/api/ips',{method:'POST',body:{ips:ips,replace:!!replace}}).then(function(j){toast(j.count+' ✓');$('#ipPaste').value='';return load()})}
$('#btnIpAppend').addEventListener('click',function(){importIps(false)});$('#btnIpReplace').addEventListener('click',function(){importIps(true)});
$('#btnIpClear').addEventListener('click',function(){if(!confirm('?'))return;api('/api/ips',{method:'POST',body:{ips:[],replace:true}}).then(function(){return load()})});
$('#btnIpCopy').addEventListener('click',function(){copy(CFG.settings.ips.join('\\n'))});
$('#btnIpTest').addEventListener('click',function(){var ips=CFG.settings.ips.slice(0,64);if(!ips.length){toast(t('scan_empty'),true);return}var out=$('#ipTestOut');out.textContent='… 0/'+ips.length;$('#btnIpTest').disabled=true;
api('/api/ip-test',{method:'POST',body:{ips:ips}}).then(function(j){window.__ipTest=j.results||{};var ok=Object.values(window.__ipTest).filter(function(r){return r.ok}).length;out.textContent='🩺 '+ok+' / '+ips.length+' '+t('ip_test_reach');renderIps()}).catch(function(e){out.textContent='✗ '+esc(e&&e.message||e)}).finally(function(){$('#btnIpTest').disabled=false})});
$('#btnBrowserTest').addEventListener('click',function(){var box=$('#scanRes');var targets=CFG.settings.ips.concat(CFG.settings.useDefaults?CFG.defaults.addresses:[]).filter(function(a){return !/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(a)&&a.indexOf(':')<0});
 box.innerHTML='<div class="note w small" style="margin-bottom:8px">'+t('browser_note')+'</div>';var rows={};targets.forEach(function(h){var d=document.createElement('div');d.innerHTML='<span class="mono">'+esc(h)+'</span><span class="dim">…</span>';box.appendChild(d);rows[h]=d.lastChild});
 var i=0;function next(){if(i>=targets.length)return;var h=targets[i++];var t0=performance.now();var ctl=('AbortController' in window)?new AbortController():null;var timer=setTimeout(function(){if(ctl)ctl.abort()},4000);
  fetch('https://'+h+'/cdn-cgi/trace?'+Date.now(),{mode:'no-cors',cache:'no-store',signal:ctl?ctl.signal:undefined}).then(function(){var ms=Math.round(performance.now()-t0);rows[h].innerHTML='<span style="color:'+(ms<400?'var(--green)':ms<900?'var(--amber)':'var(--red)')+'">'+ms+' ms</span>'},function(){rows[h].innerHTML='<span style="color:var(--red)">✗</span>'}).then(function(){clearTimeout(timer);next()})}
 next();next();next()});

/* ---------- config builder + scanner filters + events ---------- */
var CC_FA={DE:'آلمان',NL:'هلند',GB:'بریتانیا',FR:'فرانسه',TR:'ترکیه',AE:'امارات',FI:'فنلاند',SE:'سوئد',CA:'کانادا',SG:'سنگاپور',JP:'ژاپن',US:'آمریکا',IR:'ایران',IT:'ایتالیا',ES:'اسپانیا',CH:'سوئیس',AT:'اتریش',BE:'بلژیک',DK:'دانمارک',NO:'نروژ',PL:'لهستان',UA:'اوکراین',RU:'روسیه',CN:'چین',HK:'هنگ‌کنگ',IN:'هند',KR:'کرهٔ جنوبی',AU:'استرالیا',BR:'برزیل',IL:'اسرائیل',SA:'عربستان',QA:'قطر',KW:'کویت',IQ:'عراق',OM:'عمان',AZ:'آذربایجان',AM:'ارمنستان',GE:'گرجستان',KZ:'قزاقستان',MY:'مالزی',TH:'تایلند',VN:'ویتنام',PH:'فیلیپین',ID:'اندونزی',ZA:'آفریقای جنوبی',EG:'مصر',MA:'مراکش',AR:'آرژانتین',MX:'مکزیک'};
var REGIONS={eu:['DE','NL','GB','FR','FI','SE','NO','DK','IT','ES','CH','AT','BE','PL','UA','RU'],me:['TR','AE','SA','QA','KW','IQ','OM','IL','AZ','AM','GE','IR'],as:['SG','JP','IN','KR','CN','HK','MY','TH','VN','PH','ID','KZ'],am:['US','CA','BR','AR','MX'],af:['ZA','EG','MA']};
var REGKEY={eu:'reg_eu',me:'reg_me',as:'reg_as',am:'reg_am',af:'reg_af'};
function ccName(cc){return (lang==='fa'&&CC_FA[cc])?CC_FA[cc]:cc}
function ccFlag(cc){if(!cc||cc.length!==2)return '\u{1F3F3}';return String.fromCodePoint(127397+cc.charCodeAt(0),127397+cc.charCodeAt(1))}
function famOf(a){var st=String(a);var c=st.lastIndexOf(':');
 if(c>-1&&/^\\d{1,5}$/.test(st.slice(c+1))&&st.indexOf(':')===c)st=st.slice(0,c);
 st=st.replace(/^\[/,'').replace(/\]$/,'');
 if(/^\\d{1,3}(\\.\\d{1,3}){3}$/.test(st))return 'v4';
 if(/^[0-9a-f:]+$/i.test(st)&&st.indexOf(':')>-1)return 'v6';
 return ''}
/* builder state */
var B={proto:'',fam:'',ports:{},cc:'',strict:0,limit:24,ech:0},bText='',bLive=false,bT=null;
var ISP={mtn:{p:['443','8443'],fam:'',frag:'tlshello,10-50,5-10',fp:'chrome',n:'b_isp_mtn_n'},mci:{p:['443','2053'],fam:'',frag:'tlshello,100-200,5-10',fp:'chrome',n:'b_isp_mci_n'},rtl:{p:['80','8080','443'],fam:'',frag:'tlshello,10-30,3-8',fp:'chrome',n:'b_isp_rtl_n'},tdsl:{p:['443'],fam:'',frag:'tlshello,50-150,4-8',fp:'ios',n:'b_isp_tdsl_n'},direct:{p:[],fam:'',frag:'',fp:'',n:'b_isp_direct_n'}};
function pickSel(box,btn){$$('#'+box+' button').forEach(function(x){x.classList.remove('on')});if(btn)btn.classList.add('on')}
function bPortList(){var st=CFG.settings;var l=(st.tlsPorts||[]).map(String);if(st.plainEnabled)l=l.concat((st.plainPorts||[]).map(String));return l.filter(function(v,i,a){return a.indexOf(v)===i})}
function ccList(){var m=CFG.settings.ipCountries||{};return Object.keys(m).filter(function(c){return m[c]&&c&&c!=='??'}).sort()}
function renderB(){var pb=$('#bPorts');if(!pb)return;
 pb.innerHTML=bPortList().map(function(p){return '<button type="button" data-v="'+p+'" class="'+(B.ports[p]?'on':'')+'">'+esc(p)+'</button>'}).join('');
 var cb=$('#bCc');cb.innerHTML='<button type="button" data-v="" class="'+(B.cc?'':'on')+'">'+esc(t('b_cc_all'))+'</button>'+ccList().map(function(c){return '<button type="button" data-v="'+c+'" class="'+(B.cc===c?'on':'')+'">'+ccFlag(c)+' '+esc(ccName(c))+'</button>'}).join('')}
function bUrl(kind){var u=new URL(CFG.links[kind]||CFG.links.sub);var q=new URLSearchParams();
 if(B.proto)q.set('proto',B.proto);if(B.fam)q.set('fam',B.fam);if(B.ech)q.set('ech','1');
 var ps=Object.keys(B.ports).filter(function(k){return B.ports[k]});if(ps.length)q.set('ports',ps.join(','));
 if(B.cc){q.set('country',B.cc);if(B.strict)q.set('strict','1')}
 q.set('limit',String(B.limit||24));u.search=q.toString();return u.toString()}
function bGen(){var link=bUrl('sub');$('#bLink').value=link;$('#bApps').style.display='flex';$('#bCopyAll').style.display='';$('#bQr').style.display='';
 $('#bCat').href='catclient://add-sub?url='+encodeURIComponent(link)+'&name='+encodeURIComponent((CFG.settings.title||'Cat Panel')+' · '+t('b_title'));
 $('#bV2rn').href='v2rayng://install-sub?url='+encodeURIComponent(link)+'&name=CatPanel';
 $('#bHid').href='hiddify://import/'+link;
 $('#bQr').setAttribute('data-qr',link);$('#bQr').setAttribute('data-qrl',t('b_title'));
 api('/api/events',{method:'POST',body:{ev:'builder',d:(link.split('?')[1]||'').slice(0,110)}});
 fetch(link,{cache:'no-store'}).then(function(r){return r.ok?r.text():''}).then(function(tx){bText=tx||'';var ls=bText.trim()?bText.trim().split('\\n'):[];$('#bCount').textContent=ls.length;$('#bPrev').value=ls.slice(0,10).join('\\n')}).catch(function(){bText='';$('#bCount').textContent='0';$('#bPrev').value=''})}
function bMaybe(){if(bLive){clearTimeout(bT);bT=setTimeout(bGen,300)}}
$$('#bIsp button').forEach(function(b){b.addEventListener('click',function(){var k=b.getAttribute('data-isp');pickSel('bIsp',b);var sp=ISP[k]||{};B.ports={};(sp.p||[]).forEach(function(p){B.ports[p]=true});B.fam=sp.fam||'';if(sp.frag)$('#bFrag').value=sp.frag;if(sp.fp)$('#bFp').value=sp.fp;$('#bIspNote').textContent=t(sp.n||'b_isp_direct_n');renderB();bMaybe()})});
$$('#bProto button').forEach(function(b){b.addEventListener('click',function(){pickSel('bProto',b);B.proto=b.getAttribute('data-v');bMaybe()})});
$$('#bFam button').forEach(function(b){b.addEventListener('click',function(){pickSel('bFam',b);B.fam=b.getAttribute('data-v');bMaybe()})});
var bPortsBox=$('#bPorts');if(bPortsBox)bPortsBox.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;b.classList.toggle('on');B.ports[b.getAttribute('data-v')]=b.classList.contains('on');bMaybe()});
var bCcBox=$('#bCc');if(bCcBox)bCcBox.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;pickSel('bCc',b);B.cc=b.getAttribute('data-v');bMaybe()});
$$('#bStrict button').forEach(function(b){b.addEventListener('click',function(){pickSel('bStrict',b);B.strict=Number(b.getAttribute('data-v'))||0;bMaybe()})});
$$('#bEch button').forEach(function(b){b.addEventListener('click',function(){pickSel('bEch',b);B.ech=Number(b.getAttribute('data-v'))||0;bMaybe();if(B.ech&&!bEchChecked){bEchChecked=1;api('/api/ech').then(function(j){var el=$('#bEchState');if(el)el.textContent=j.has?(('⚡ ECH ✓ ('+j.sni+')')):(t('ech_none'))}).catch(function(){var el=$('#bEchState');if(el)el.textContent=t('ech_none')})}})});
var bEchChecked=0;
if($('#bLimit'))$('#bLimit').addEventListener('change',function(){B.limit=Math.min(200,Math.max(1,Number(this.value)||24));this.value=B.limit;bMaybe()});
if($('#bGen'))$('#bGen').addEventListener('click',function(){bLive=true;bGen()});
if($('#bCopyAll'))$('#bCopyAll').addEventListener('click',function(){copy(bText)});
if($('#bFragCopy'))$('#bFragCopy').addEventListener('click',function(){copy($('#bFrag').value)});
/* scanner */
var SF={fam:'',region:'',cc:'',q:''};
function loadLoc(){var c=$('#locNow');if(!c)return;c.textContent='…';
 fetch('/cdn-cgi/trace',{cache:'no-store'}).then(function(r){return r.text()}).then(function(tx){var m=/ip=([^\\s]+)/.exec(tx),l=/loc=([A-Za-z]{2})/.exec(tx);
  if(!m){c.textContent=t('loc_fail');return}var cc=l?l[1].toUpperCase():'';
  c.innerHTML=ccFlag(cc)+' '+esc(ccName(cc))+' <span class="dim mono">('+esc(m[1])+')</span>'}).catch(function(){c.textContent=t('loc_fail')})}
if($('#btnLocRefresh'))$('#btnLocRefresh').addEventListener('click',loadLoc);
function scanAll(){var st=CFG.settings;var out=[];(st.ips||[]).concat(st.useDefaults?CFG.defaults.addresses:[]).forEach(function(a){out.push({a:a,cc:(st.ipCountries||{})[a]||''})});return out}
function renderScanChips(){var box=$('#scanRegion');if(!box)return;var ccs={};scanAll().forEach(function(x){if(x.cc)ccs[x.cc]=(ccs[x.cc]||0)+1});
 box.innerHTML='<button type="button" data-v="" class="'+(SF.region?'':'on')+'">'+esc(t('b_cc_all'))+'</button>'+Object.keys(REGIONS).map(function(r){return '<button type="button" data-v="'+r+'" class="'+(SF.region===r?'on':'')+'">'+esc(t(REGKEY[r]))+'</button>'}).join('');
 var cc=$('#scanCc');cc.innerHTML=Object.keys(ccs).sort().map(function(c){return '<button type="button" data-v="'+c+'" class="'+(SF.cc===c?'on':'')+'">'+ccFlag(c)+' '+esc(ccName(c))+' <span class="dim">'+ccs[c]+'</span></button>'}).join('');
 renderScanRes()}
function renderScanRes(){var box=$('#scanList');if(!box)return;var st=CFG.settings;
 var all=scanAll().filter(function(x){if(SF.fam&&famOf(x.a)!==SF.fam)return false;
  if(SF.cc&&x.cc!==SF.cc)return false;
  if(SF.region&&REGIONS[SF.region]&&REGIONS[SF.region].indexOf(x.cc)<0)return false;
  if(SF.q){var hay=(x.cc+' '+ccName(x.cc)+' '+x.a).toLowerCase();if(hay.indexOf(SF.q)<0)return false}return true});
 var by={};all.forEach(function(x){var k=x.cc||'';(by[k]=by[k]||[]).push(x.a)});
 var keys=Object.keys(by).sort();
 box.innerHTML=keys.length?keys.map(function(c){var addrs=by[c];
  return '<div class="card" style="padding:10px 12px;margin-bottom:8px"><div class="row" style="align-items:center;gap:8px"><b>'+ccFlag(c)+' '+esc(ccName(c))+'</b><span class="chip">'+addrs.length+'</span><span style="flex:1"></span>'+(c?'<button class="btn sm" type="button" data-scancc="'+c+'">'+esc(t('scan_jump'))+'</button>':'')+'<button class="btn sm" type="button" data-copy="'+esc(addrs.join('\\n'))+'">\u{1F4CB}</button></div><div class="ipl" style="margin-top:6px">'+addrs.map(function(a){return '<span class="chip mono">'+esc(a)+'</span>'}).join('')+'</div></div>'}).join('')
  :'<div class="empty"><div>\u{1F4E1}</div><span>'+esc(t('scan_empty'))+'</span></div>'}
$$('#scanFam button').forEach(function(b){b.addEventListener('click',function(){pickSel('scanFam',b);SF.fam=b.getAttribute('data-v');renderScanRes()})});
var srg=$('#scanRegion');if(srg)srg.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;pickSel('scanRegion',b);SF.region=b.getAttribute('data-v');renderScanRes()});
var scc=$('#scanCc');if(scc)scc.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;pickSel('scanCc',b);SF.cc=b.getAttribute('data-v');renderScanRes()});
if($('#scanSearch'))$('#scanSearch').addEventListener('input',function(){SF.q=this.value.trim().toLowerCase();renderScanRes()});
document.addEventListener('click',function(e){var b=e.target.closest('[data-scancc]');if(!b)return;B.cc=b.getAttribute('data-scancc');B.strict=0;show('build');renderB();bLive=true;bGen()});
if($('#btnCidrAdd'))$('#btnCidrAdd').addEventListener('click',function(){var raw=$('#scanCidr').value.split(/[\\s,]+/).filter(Boolean);var ips=[];var bad=false;
 raw.forEach(function(r){var m=r.match(/^(\\d{1,3}(?:\\.\\d{1,3}){3})\\/(\\d{1,2})$/);
  if(m){var b=m[1].split('.').map(Number);var bits=Number(m[2]);
   if(b.some(function(x){return x>255})||bits<8||bits>32){bad=true;return}
   var size=Math.min(16,Math.pow(2,32-bits));var step=Math.max(1,Math.floor(Math.pow(2,32-bits)/size));
   var base=((b[0]<<24)|(b[1]<<16)|(b[2]<<8)|b[3])>>>0;
   for(var i=0;i<size;i++){var v=(base+i*step)>>>0;ips.push([(v>>>24)&255,(v>>>16)&255,(v>>>8)&255,v&255].join('.'))}}
  else if(/^[a-z0-9.-]+\\.[a-z]{2,}$/i.test(r))ips.push(r);
  else if(r)bad=true});
 if(!ips.length){toast(t('cidr_bad'));return}
 api('/api/ips',{method:'POST',body:{ips:ips}}).then(function(j){toast((t('cidr_ok').replace('%1',ips.length))+(j&&j.persisted===false?' (memory)':''));$('#scanCidr').value='';return load()}).catch(function(){toast(t('cidr_bad'))})});
if($('#btnScanApp'))$('#btnScanApp').addEventListener('click',function(){api('/api/events',{method:'POST',body:{ev:'scan',d:'app'}})});
if($('#btnBrowserTest'))$('#btnBrowserTest').addEventListener('click',function(){api('/api/events',{method:'POST',body:{ev:'scan',d:'browser'}})});
/* events log */
function ago(ts){var sec=Math.max(0,(Date.now()-ts)/1000);if(sec<60)return t('seen_now');var m=Math.floor(sec/60);if(m<60)return t('seen_min').replace('%1',m);var h=Math.floor(m/60);if(h<24)return t('ev_ago_h').replace('%1',h);return t('ev_ago_d').replace('%1',Math.floor(h/24))}
function loadEvents(){var tb=$('#evRows');if(!tb)return;
 api('/api/events').then(function(j){var ev=(j&&j.events)||[];
  var em=$('#evEmpty');if(em)em.style.display=ev.length?'none':'block';
  tb.innerHTML=ev.slice(0,15).map(function(x){return '<tr><td class="dim small" style="white-space:nowrap">'+esc(ago(x.t))+'</td><td><span class="chip">'+esc(x.ev)+'</span></td><td class="small dim">'+esc(x.d||'')+'</td></tr>'}).join('')}).catch(function(){})}
if($('#evRefresh'))$('#evRefresh').addEventListener('click',loadEvents);

/* ---------- repo library ---------- */
var RP={d:null};
function agoH(ts){if(!ts)return '—';var h=Math.floor((Date.now()-ts)/3600000);if(h<1)return t('seen_now');if(h<48)return t('ev_ago_h').replace('%1',h);return t('ev_ago_d').replace('%1',Math.floor(h/24))}
function rpRender(){if(!RP.d)return;var box=$('#rpRows');if(!box)return;
 var st=$('#rpState');if(st){var fresh=RP.d.ts&&(Date.now()-RP.d.ts<12*3600000);st.textContent=RP.d.kv?('⚡ '+RP.d.total):(t('rp_nokv'));st.className='chip '+(RP.d.kv?(fresh?'ok':'warn'):'')}
 var rows=box;rows.innerHTML=(RP.d.repos||[]).map(function(r){var c=[];c.push('<div class="card" style="padding:10px 12px;margin-bottom:6px"><div class="row" style="align-items:center;gap:8px;flex-wrap:wrap">');
  c.push('<b>'+esc(r.name)+'</b>');
  c.push(r.ok===null?'<span class="chip">'+esc('—')+'</span>':(r.ok?'<span class="chip ok">'+(r.count||0)+'</span>':'<span class="chip bad">✗</span>'));
  c.push('<span class="dim small">'+esc(agoH(r.ts))+'</span>');
  c.push('<span style="flex:1"></span>');
  c.push('<button class="btn sm" type="button" data-rptoggle="'+esc(r.id)+'">'+(r.enabled!==false?'⏸':'▶')+'</button>');
  c.push('<button class="btn sm r" type="button" data-rpdel="'+esc(r.id)+'">✕</button>');
  c.push('</div><div class="small dim mono" dir="ltr" style="margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(r.url)+'</div></div>');
  return c.join('')}).join('');
 var ccbox=$('#rpCcs');var ccs=RP.d.ccs||{};var keys=Object.keys(ccs).sort();
 ccbox.innerHTML=keys.length?keys.map(function(c){return '<span class="chip" style="font-size:11px">'+ccFlag(c)+' '+esc(ccName(c))+' <b>'+ccs[c]+'</b> <button class="btn sm" type="button" data-rpimp="'+esc(c)+'" style="padding:1px 8px;margin:0">+</button></span>'}).join(''):'<span class="small dim">'+esc(t('rp_empty'))+'</span>';
 var ab=$('#rpAuto');if(ab){ab.innerHTML=(RP.d.auto?'⚡ ':'⏸ ')+t('rp_auto');ab.className='btn sm '+(RP.d.auto?'p':'')}}
function rpLoad(){return api('/api/repos').then(function(j){RP.d=j;rpRender()}).catch(function(){})}
function rpPost(b){return api('/api/repos',{method:'POST',body:b})}
if($('#rpRefresh'))$('#rpRefresh').addEventListener('click',function(){var b=this;b.disabled=true;rpPost({action:'refresh'}).then(function(j){toast(j.ok?('⚡ '+j.total):'✗');return rpLoad()}).catch(function(){toast('✗',true)}).then(function(){b.disabled=false})});
if($('#rpAuto'))$('#rpAuto').addEventListener('click',function(){rpPost({action:'auto',enabled:!(RP.d&&RP.d.auto)}).then(function(){return rpLoad()})});
if($('#rpAdd'))$('#rpAdd').addEventListener('click',function(){var url=prompt(t('rp_add_url'));if(!url)return;var name=prompt(t('rp_add_name'),'repo'+((RP.d&&RP.d.repos||[]).length+1));if(!name)return;
 var list=(RP.d&&RP.d.repos||[]).slice(0,9);list.push({id:'r'+Date.now().toString(36),name:name,url:url,kind:/\\.json($|\\?)/i.test(url)?'json-speed':'txt',enabled:true});
 rpPost({action:'set',repos:list}).then(function(){return rpPost({action:'refresh'})}).then(function(j){toast(j.ok?('⚡ '+j.total):'✗');return rpLoad()})});
document.addEventListener('click',function(e){var d=e.target.closest('[data-rpdel]');if(d){var id=d.getAttribute('data-rpdel');var list=(RP.d.repos||[]).filter(function(r){return r.id!==id});if(!list.length)list=null;rpPost({action:'set',repos:list||undefined}).then(function(){return rpLoad()});return}
 var tg=e.target.closest('[data-rptoggle]');if(tg){var id2=tg.getAttribute('data-rptoggle');var list2=(RP.d.repos||[]).map(function(r){return r.id===id2?Object.assign({},r,{enabled:r.enabled===false}):r});rpPost({action:'set',repos:list2}).then(function(){return rpLoad()});return}
 var im=e.target.closest('[data-rpimp]');if(im){rpPost({action:'import',cc:im.getAttribute('data-rpimp'),limit:16}).then(function(j){toast(j.ok?('⚡ +'+j.added):t('rp_empty'));return load()})}});
if($('#btnUpdate')===null){} /* noop guard */

/* ---------- proxyIP repo library ---------- */
var PP={d:null};
function ppRender(){if(!PP.d)return;var box=$('#ppRows');if(!box)return;
 var st=$('#ppState');if(st){var fresh=PP.d.ts&&(Date.now()-PP.d.ts<12*3600000);st.textContent=PP.d.kv?('⚡ '+PP.d.total):(t('rp_nokv'));st.className='chip '+(PP.d.kv?(fresh?'ok':'warn'):'')}
 box.innerHTML=(PP.d.repos||[]).map(function(r){var c=[];c.push('<div class="card" style="padding:10px 12px;margin-bottom:6px"><div class="row" style="align-items:center;gap:8px;flex-wrap:wrap">');
  c.push('<b>'+esc(r.name)+'</b>');
  c.push(r.ok===null?'<span class="chip">—</span>':(r.ok?'<span class="chip ok">'+(r.count||0)+'</span>':'<span class="chip bad">✗</span>'));
  c.push('<span class="dim small">'+esc(agoH(r.ts))+'</span>');
  c.push('<span style="flex:1"></span>');
  c.push('<button class="btn sm" type="button" data-pptoggle="'+esc(r.id)+'">'+(r.enabled!==false?'⏸':'▶')+'</button>');
  c.push('<button class="btn sm r" type="button" data-ppdel="'+esc(r.id)+'">✕</button>');
  c.push('</div><div class="small dim mono" dir="ltr" style="margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(r.url)+'</div></div>');
  return c.join('')}).join('');
 var ccbox=$('#ppCcs');var ccs=PP.d.ccs||{};var keys=Object.keys(ccs).sort();
 ccbox.innerHTML=keys.length?keys.map(function(c){return '<span class="chip" style="font-size:11px">'+ccFlag(c)+' '+esc(ccName(c))+' <b>'+ccs[c]+'</b> <button class="btn sm" type="button" data-ppimp="'+esc(c)+'" style="padding:1px 8px;margin:0">+</button></span>'}).join(''):'<span class="small dim">'+esc(t('rp_empty'))+'</span>';
 var ab=$('#ppAuto');if(ab){ab.innerHTML=(PP.d.auto?'⚡ ':'⏸ ')+t('pp_auto');ab.className='btn sm '+(PP.d.auto?'p':'')}
 renderPpCountries()}
function ppLoad(){return api('/api/prepos').then(function(j){PP.d=j;ppRender()}).catch(function(){})}
function ppPost(b){return api('/api/prepos',{method:'POST',body:b})}
if($('#ppRefresh'))$('#ppRefresh').addEventListener('click',function(){var b=this;b.disabled=true;ppPost({action:'refresh'}).then(function(j){toast(j.ok?('⚡ '+j.total):'✗');return ppLoad()}).catch(function(){toast('✗',true)}).then(function(){b.disabled=false})});
if($('#ppAuto'))$('#ppAuto').addEventListener('click',function(){ppPost({action:'auto',enabled:!(PP.d&&PP.d.auto)}).then(function(){return ppLoad()})});
if($('#ppAdd'))$('#ppAdd').addEventListener('click',function(){var url=prompt(t('rp_add_url'));if(!url)return;var name=prompt(t('rp_add_name'),'prepo'+((PP.d&&PP.d.repos||[]).length+1));if(!name)return;
 var kind=/.json($|[?])/i.test(url)?'json-speed':(/.csv($|[?])/i.test(url)?'csv-proxy':'txt');
 var list=(PP.d&&PP.d.repos||[]).slice(0,9);list.push({id:'p'+Date.now().toString(36),name:name,url:url,kind:kind,enabled:true});
 ppPost({action:'set',repos:list}).then(function(){return ppPost({action:'refresh'})}).then(function(j){toast(j.ok?('⚡ '+j.total):'✗');return ppLoad()})});
if($('#btnPxAddrs'))$('#btnPxAddrs').addEventListener('click',function(){var b=this;b.disabled=true;ppPost({action:'toAddrs',limit:64}).then(function(j){toast(j.added?('+ '+j.added+' ⚡'):t('px_none'));return load()}).catch(function(){toast(t('px_none'),true)}).then(function(){b.disabled=false})});
document.addEventListener('click',function(e){var d=e.target.closest('[data-ppdel]');if(d){var id=d.getAttribute('data-ppdel');var list=(PP.d.repos||[]).filter(function(r){return r.id!==id});ppPost({action:'set',repos:list}).then(function(){return ppLoad()});return}
 var tg=e.target.closest('[data-pptoggle]');if(tg){var id2=tg.getAttribute('data-pptoggle');var list2=(PP.d.repos||[]).map(function(r){return r.id===id2?Object.assign({},r,{enabled:r.enabled===false}):r});ppPost({action:'set',repos:list2}).then(function(){return ppLoad()});return}
 var im=e.target.closest('[data-ppimp]');if(im){ppPost({action:'import',cc:im.getAttribute('data-ppimp'),limit:8}).then(function(j){toast(j.ok?('⚡ +'+j.added):t('rp_empty'));return load()})}});

/* ---------- fixed-IP quick toggle ---------- */
function rotFixed(){return (CFG.settings&&CFG.settings.subRotate||'fetch')==='off'}
function renderRotBtn(){var fixed=rotFixed();var b1=$('#btnRot');if(b1){b1.classList.toggle('on',fixed);b1.title=t(fixed?'rot_btn_on':'rot_btn_off')}var b2=$('#btnRot2');if(b2){b2.innerHTML=(fixed?'📌 ':'⚡ ')+t(fixed?'rot_fixed_lbl':'rot_rot_lbl');b2.className='btn sm'+(fixed?' p':'')}}
function toggleRot(){var next=rotFixed()?'fetch':'off';api('/api/settings',{method:'PUT',body:{subRotate:next}}).then(function(j){if(!j.ok)throw 0;CFG.settings.subRotate=next;renderRotBtn();var srp=$('#subRotatePick');if(srp)$$('#subRotatePick button').forEach(function(x){x.classList.toggle('on',x.getAttribute('data-v')===next)});toast(next==='off'?t('rot_now_fixed'):t('rot_now_rotating'))}).catch(function(){toast('error',true)})}
if($('#btnRot'))$('#btnRot').addEventListener('click',toggleRot);
if($('#btnRot2'))$('#btnRot2').addEventListener('click',toggleRot);

/* ---------- WARP form + ext subs + aether builder + country chips ---------- */
function renderWarp(){var w=(CFG.settings.warp||{}),f=$('#fWarp');if(!f)return;$$('#warpMode button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-v')===(w.mode||'off'))});f.elements.secretKey.value=w.secretKey||'';f.elements.publicKey.value=w.publicKey||'';f.elements.reserved.value=w.reserved||'';f.elements.endpoint.value=w.endpoint||''}
$$('#warpMode button').forEach(function(b){b.addEventListener('click',function(){$$('#warpMode button').forEach(function(x){x.classList.remove('on')});b.classList.add('on')})});
if($('#fWarp'))$('#fWarp').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var mode=(document.querySelector('#warpMode button.on')||{getAttribute:function(){return 'off'}}).getAttribute('data-v');
api('/api/settings',{method:'PUT',body:{warp:{mode:mode,secretKey:f.elements.secretKey.value.trim(),publicKey:f.elements.publicKey.value.trim(),reserved:f.elements.reserved.value.trim(),endpoint:f.elements.endpoint.value.trim()}}}).then(function(j){if(!j.ok)throw 0;CFG.settings.warp=j.settings.warp;toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted)}).catch(function(){toast('error',true)})});
/* ext subs */
var SERVERLESS_SUB='https://raw.githubusercontent.com/patterniha/Serverless-for-Iran/refs/heads/main/Subscription/Serverless-for-Iran.json';
function renderExt(){var box=$('#extRows');if(!box)return;var list=CFG.settings.extSubs||[];
 box.innerHTML=list.length?list.map(function(x,i){var link=location.origin+'/ext/'+(i+1)+'/'+CFG.uuid;
 return '<div class="card" style="padding:10px 12px;margin-bottom:6px"><div class="row" style="align-items:center;gap:8px;flex-wrap:wrap"><b>'+esc(x.name)+'</b><span style="flex:1"></span><button class="btn sm" type="button" data-copy="'+esc(link)+'">🔗 /ext/'+(i+1)+'</button><button class="btn sm" type="button" data-qr="'+esc(link)+'" data-qrl="'+esc(x.name)+'">▦</button><button class="btn sm r" type="button" data-extdel="'+i+'">✕</button></div><div class="small dim mono" dir="ltr" style="margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(x.url)+'</div></div>'}).join('')
 :'<span class="small dim">'+esc(t('ext_empty'))+'</span>';
 var core=$('#extCoreNote');if(core){core.setAttribute('data-i','ext_core');core.textContent=t('ext_core');core.style.display=list.some(function(x){return /Serverless-for-Iran/.test(x.url||'')})?'':'none'}
 var demo=$('#extLinkDemo');if(demo)demo.textContent=list.length?('/ext/1/'+CFG.uuid):''}
if($('#btnExtAdd'))$('#btnExtAdd').addEventListener('click',function(){var name=prompt(t('ext_name'),'ext'+((CFG.settings.extSubs||[]).length+1));if(!name)return;var url=prompt(t('ext_url'));if(!url)return;
 var list=(CFG.settings.extSubs||[]).slice(0,4);list.push({name:name,url:url});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt()}).catch(function(){toast('error',true)})});
if($('#btnExtPreset'))$('#btnExtPreset').addEventListener('click',function(){
 var list=(CFG.settings.extSubs||[]).filter(function(x){return x.url!==SERVERLESS_SUB});if(list.length>=5)list=list.slice(0,4);list.push({name:'Serverless-for-Iran (PattNG)',url:SERVERLESS_SUB});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt();toast('⚡ ✓')}).catch(function(){toast('error',true)})});
document.addEventListener('click',function(e){var d=e.target.closest('[data-extdel]');if(!d)return;var i=Number(d.getAttribute('data-extdel'));var list=(CFG.settings.extSubs||[]).filter(function(x,xi){return xi!==i});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt()}).catch(function(){toast('error',true)})});
/* aether builder */
var AE={mode:'gool',fam:'both'};
function aeBuild(){var name=($('#aeName')&&$('#aeName').value.trim())||'Omni';var ip=AE.fam==='both'?'&ip=both':('&ip='+AE.fam);var u='';
 if(AE.mode==='warp')u='aether://?protocol=warp'+ip+'&scan=balanced#'+encodeURIComponent(name+' WARP');
 else if(AE.mode==='gool')u='aether://?protocol=gool'+ip+'&scan=balanced#'+encodeURIComponent(name+' WARP-in-WARP');
 else{var fm=JSON.stringify({tcp:[{type:'fragment',settings:{packets:'tlshello',lengths:['100-200'],interval:'5-10'}}]});
  u='aether://?protocol=masque&transport=h2&fingerPrint=semi-python'+ip+'&fm='+encodeURIComponent(fm)+'#'+encodeURIComponent(name+' MASQUE/H2')}
 $('#aeLink').value=u;$('#aeQr').setAttribute('data-qr',u);$('#aeQr').setAttribute('data-qrl',name);$('#aeOpen').href=u;return u}
$$('#aePick button').forEach(function(b){b.addEventListener('click',function(){pickSel('aePick',b);AE.mode=b.getAttribute('data-v');aeBuild()})});
$$('#aeFam button').forEach(function(b){b.addEventListener('click',function(){pickSel('aeFam',b);AE.fam=b.getAttribute('data-v');aeBuild()})});
if($('#aeName'))$('#aeName').addEventListener('input',aeBuild);
if($('#aeCopy'))$('#aeCopy').addEventListener('click',function(){copy($('#aeLink').value)});
if($('#aeBuild')===null){} /* noop */
/* proxy repo country chips */
var PP_COUNTRIES=['CA','CH','DE','FI','FR','GB','HK','IN','JP','KR','LV','NL','PL','RU','SE','SG','TW','US'];
function renderPpCountries(){var box=$('#ppCountries');if(!box)return;var have={};(PP.d&&PP.d.repos||[]).forEach(function(r){have[r.id]=1});
 box.innerHTML=PP_COUNTRIES.map(function(c){var added=have['wanwu-'+c.toLowerCase()];
 return '<button type="button" class="btn sm'+(added?' p':'')+'" data-ppcc="'+c+'">'+ccFlag(c)+' '+(lang==='fa'?(CC_FA[c]||c):c)+(added?' ✓':'')+'</button>'}).join('')}
document.addEventListener('click',function(e){var b=e.target.closest('[data-ppcc]');if(!b)return;var c=b.getAttribute('data-ppcc');
 var list=(PP.d&&PP.d.repos||[]).slice(0,9);if(list.some(function(r){return r.id==='wanwu-'+c.toLowerCase()}))return;
 list.push({id:'wanwu-'+c.toLowerCase(),name:'Wanwu ProxyIP · '+c,url:'https://raw.githubusercontent.com/wanwushequ/ProxyIP/main/'+c+'.txt',kind:'txt',cc:c,enabled:true});
 ppPost({action:'set',repos:list}).then(function(){return ppPost({action:'refresh'})}).then(function(j){toast(j.ok?('⚡ '+j.total):'✗');return ppLoad()})});

if($('#btnBurger'))$('#btnBurger').addEventListener('click',function(){document.querySelector('.top').classList.toggle('menu-open')});
document.addEventListener('click',function(e){if(e.target.closest&&e.target.closest('.tools button,.tools a')){var tp=document.querySelector('.top');if(tp)tp.classList.remove('menu-open')}},true);

/* ---------- backup ---------- */
$('#restoreFile').addEventListener('change',function(){var f=this.files[0];if(!f)return;var r=new FileReader();r.onload=function(){try{var j=JSON.parse(r.result);if(!j.settings&&!j.users)throw 0;api('/api/backup',{method:'POST',body:{settings:j.settings,users:j.users}}).then(function(){$('#restoreState').textContent=t('restore_ok');return load()})}catch(e){$('#restoreState').textContent=t('restore_bad')}};r.readAsText(f)});

/* ---------- update / lang ---------- */
$('#btnUpdate').addEventListener('click',function(){show('about');var b=$('#updateBox');b.textContent=t('update_check');api('/api/update-check').then(function(j){if(!j.ok||!j.latest){b.textContent='?';return}
 b.innerHTML=j.latest===j.current?'<span class="chip ok">✓ '+t('update_ok')+' ('+esc(j.current)+')</span>':'<span class="chip warn">⬆️ '+t('update_new')+esc(j.latest)+'</span> <a class="btn sm p" href="/api/update-download" style="vertical-align:middle">⬇️ worker.js</a><div class="small mute" style="margin-top:6px">'+t('update_how2')+'</div>'})});
$('#btnLang').addEventListener('click',function(){var next=lang==='fa'?'en':'fa';api('/api/settings',{method:'PUT',body:{lang:next}}).then(function(){location.reload()})});

applyI18n();
var h=(location.hash||'#dash').slice(1);if(['dash','clients','inbounds','scan','nodes','spoof','settings','backup','about'].indexOf(h)<0)h='dash';show(h);
load().catch(function(){toast('load error',true)});
api('/api/update-check').then(function(j){if(!j.ok||!j.latest||j.latest===j.current)return;var b=$('#updateBox');if(b)b.innerHTML='<span class="chip warn">\u2b06\ufe0f '+t('update_new')+esc(j.latest)+'</span><div class="small mute" style="margin-top:6px">'+t('update_how')+'</div>';toast(t('update_new')+j.latest)}).catch(function(){});
})();
</script></body></html>`;
}


/* ------------------------------------------------------------------ */
/* entry                                                               */
/* ------------------------------------------------------------------ */

export default {
  /** Cloudflare cron (wrangler.jsonc triggers → every 12h): refresh clean-IP + ProxyIP feeds. */
  async scheduled(controller, env, ctx) {
    void controller;
    try {
      const job = Promise.allSettled([refreshRepos(env), refreshProxyRepos(env)]);
      if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job); else await job;
    } catch { /* cron must never throw */ }
  },
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env || {}, ctx);
    } catch (e) {
      return json({ ok: false, error: 'internal', message: String(e && e.message ? e.message : e) }, 500);
    }
  },
};

export const _testing = {
  CAT_PANEL_VERSION,
  splitCsv, uniq, isUuid, b64encode, b64decode, sha256Hex, hmacHex, safeEqualHex,
  deriveUuid, resolveUuid,
  KV_KEYS, kvBinding, kvCacheClear, KV_READ_TTL_MS,
  defaultSettings, normalizeSettings, readSettings, writeSettings,
  normalizeUser, readUsers, writeUsers, userBlockedReason, findUser,
  panelPassword, panelIsOpen, makeSession, verifySession, isOwner, checkLogin,
  qrEncode, qrSvg,
  decodeEarlyData, websocketReadable, safeCloseWs, parseSocksAddress, parseVlessHeader, trojanPassword, parseTrojanRequest,
  sha224Hex, trojanHash, isCloudflareIp, CF_CIDR_RANGES,
  __setSockets, loadSockets, splitHostPort, proxyIpList, parseChain, dialViaChain, socks5Handshake, httpConnectHandshake, subQuery, DEFAULT_PROXY_IPS, buildXrayConfigs, tgCommand, tgSecret, tgConfig, deployCfg, ghApi, normalizeCountry, splitAddrTag, pinnedPortOf, flagOf, countryLabel, countrySummary, countryGroups, countryOfAddr, dialTarget, pumpTunnel, tunnelAuth, handleTunnelConnection, tunnelPaths, isTunnelPath,
  DEFAULT_REPOS, REPO_TTL_MS, sanitizeRepos, parseRepoFeed, refreshRepos, repoHealthyPool, withRepoPool, maybeRepoRefresh,
  DEFAULT_PROXY_REPOS, PROXY_REPO_TTL_MS, sanitizeProxyRepos, parseProxyFeed, refreshProxyRepos, proxyRepoHealthyPool, withProxyRepoPool, maybeProxyRepoRefresh, echConfigList,
  buildWarpOutbounds, parseExtUris, extSubContent,
  effectiveSni, addressList, buildConfigEntries, vlessLink, trojanLink, linkContext, buildClashYaml, buildSingboxConfig, subscriptionHeaders,
  TLS_PORTS, PLAIN_PORTS, DEFAULT_CLEAN_ADDRESSES, SCAN_RANGES,
  handleRequest, handleApi, selfInfo, geoLookup,
  loginPage, panelPage, userInfoPage,
};
