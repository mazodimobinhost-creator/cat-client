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
 *   OPEN_SUB        "true" → /sub (without uuid) also serves the master links
 *   PANEL_TITLE     header title     DNS_UPSTREAM  DoH upstream for /dns-query
 */

const CAT_PANEL_VERSION = '6.2.0';
const REPO = 'mazodimobinhost-creator/cat-client';
const REPO_URL = 'https://github.com/' + REPO;
const PANEL_SOURCE_URL = 'https://raw.githubusercontent.com/' + REPO + '/main/app/src/main/assets/panels/catclient.worker.js';

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
    plainPorts: PLAIN_PORTS.slice(0, 2),
    plainEnabled: true,
    protocols: { vless: true, trojan: true },
    sni: '',
    fingerprint: 'chrome',
    proxyIps: [],
    ipCountries: {},     // addr → ISO-2 (where this entry address lands for YOU)
    proxyCountries: {},  // proxy ip → ISO-2 (exit for Cloudflare-hosted sites)
    country: '',         // preferred exit country ('' = automatic)
    countryFallback: 'auto', // 'auto' = fastest other country when preferred is dead, 'none' = never leave it
    chain: '',          // socks5://user:pass@host:port or http://host:port — fixed egress
    chainMode: 'all',   // 'all' = every connection via chain (stable IP/country), 'cf' = only Cloudflare-hosted targets
    chainStrict: false, // true = never fall back to direct when the chain is down
    entryLimit: 48,
    includeHost: true,  // also emit the worker hostname itself as an address
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
  s.useDefaults = s.useDefaults !== false;
  s.tlsPorts = uniq((Array.isArray(s.tlsPorts) ? s.tlsPorts : splitCsv(s.tlsPorts)).map(Number).filter((p) => TLS_PORTS.includes(p)));
  if (!s.tlsPorts.length) s.tlsPorts = [443];
  s.plainPorts = uniq((Array.isArray(s.plainPorts) ? s.plainPorts : splitCsv(s.plainPorts)).map(Number).filter((p) => PLAIN_PORTS.includes(p)));
  if (!s.plainPorts.length) s.plainPorts = [80];
  s.plainEnabled = s.plainEnabled !== false;
  s.protocols = { vless: !(s.protocols && s.protocols.vless === false), trojan: !(s.protocols && s.protocols.trojan === false) };
  if (!s.protocols.vless && !s.protocols.trojan) s.protocols.vless = true;
  s.sni = String(s.sni || '').trim().toLowerCase().slice(0, 253);
  s.fingerprint = ['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', 'random', 'randomized'].includes(s.fingerprint) ? s.fingerprint : 'chrome';
  s.proxyIps = uniq(Array.isArray(s.proxyIps) ? s.proxyIps : splitCsv(s.proxyIps)).slice(0, 32);
  s.ipCountries = normalizeCountryMap(s.ipCountries, 500);
  s.proxyCountries = normalizeCountryMap(s.proxyCountries, 64);
  s.country = normalizeCountry(s.country) || '';
  s.countryFallback = s.countryFallback === 'none' ? 'none' : 'auto';
  s.chain = parseChain(s.chain) ? String(s.chain).trim() : '';
  s.chainMode = s.chainMode === 'cf' ? 'cf' : 'all';
  s.chainStrict = s.chainStrict === true;
  s.entryLimit = Math.min(200, Math.max(4, Number(s.entryLimit) || d.entryLimit));
  s.includeHost = s.includeHost !== false;
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

async function verifySession(request, env, settings, masterUuid) {
  const token = readCookie(request, SESSION_COOKIE);
  if (!token) return false;
  const [exp, sig] = token.split('.');
  if (!exp || !sig || Number(exp) < Date.now()) return false;
  const expected = await hmacHex(await sessionSecret(env, settings, masterUuid), String(exp));
  return expected === sig;
}

/** Owner check: session cookie, Bearer password, or x-cat-key header. */
async function isOwner(request, env, settings, masterUuid) {
  if (panelIsOpen(env, settings)) return true;
  if (await verifySession(request, env, settings, masterUuid)) return true;
  const auth = request.headers.get('authorization') || '';
  const key = auth.toLowerCase().startsWith('bearer ') ? auth.slice(7).trim() : (request.headers.get('x-cat-key') || '').trim();
  if (!key) return false;
  const pass = await panelPassword(env, settings, masterUuid);
  return (await sha256Hex(key)) === pass.hash;
}

async function checkLogin(env, settings, masterUuid, username, password) {
  const wantUser = String(env.PANEL_USER || '').trim();
  if (wantUser && String(username || '').trim() !== wantUser) return false;
  const pass = await panelPassword(env, settings, masterUuid);
  return (await sha256Hex(String(password || ''))) === pass.hash;
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

async function dialTarget(host, port, env, settings, log) {
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

async function handleTunnelConnection(ws, env, options = {}) {
  const earlyData = decodeEarlyData(options.earlyDataHeader);
  const clientStream = websocketReadable(ws, earlyData);
  const reader = clientStream.getReader();
  const settings = options.settings || await readSettings(env);
  const masterUuid = String(options.masterUuid || env.UUID || '').toLowerCase();
  const log = options.log || (() => {});

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
    dialed = await dialTarget(target.host, target.port, env, settings, log);
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
  const vless = String(env.VLESS_PATH || '/ws?ed=2048');
  const trojan = String(env.TROJAN_PATH || '/trojan');
  return {
    vlessPath: vless,
    trojanPath: trojan,
    vlessName: vless.split('?')[0],
    trojanName: trojan.split('?')[0],
  };
}

function isTunnelPath(pathname, env) {
  const p = tunnelPaths(env);
  return pathname === p.vlessName || pathname === p.trojanName || pathname === '/ws' || pathname.startsWith('/ws/');
}


/* ------------------------------------------------------------------ */
/* subscription content                                                */
/* ------------------------------------------------------------------ */

function effectiveSni(host, env, settings) {
  return String((settings && settings.sni) || env.SNI || host).trim().toLowerCase();
}

function addressList(host, env, settings) {
  const own = settings.ips || [];
  const fromEnv = splitCsv(env.CF_IPS).map((r) => splitAddrTag(r).addr).filter(Boolean);
  const defaults = settings.useDefaults ? DEFAULT_CLEAN_ADDRESSES : [];
  const list = uniq(own.concat(fromEnv, defaults));
  if (settings.includeHost && !list.some((a) => a.toLowerCase() === String(host).toLowerCase())) list.push(String(host));
  return list;
}

function configName(proto, addr, port, tls, cc) {
  // A leading flag lets every client (Cat Client, V2Box, Clash, sing-box) group
  // and pick by country; untagged entries keep the 🐱 prefix.
  return (cc ? flagOf(cc) : '🐱') + ' ' + (proto === 'vless' ? 'VL' : 'TR') + ' ' + addr + ':' + port + (tls ? '' : ' ⚡');
}

function wsParams(hostHeader, path, sni, fp, tls) {
  const params = [
    'security=' + (tls ? 'tls' : 'none'),
    'type=ws',
    'host=' + encodeURIComponent(hostHeader),
    'path=' + encodeURIComponent(path),
  ];
  if (tls) {
    params.push('sni=' + encodeURIComponent(sni));
    params.push('fp=' + encodeURIComponent(fp));
    params.push('alpn=' + encodeURIComponent('http/1.1'));
  }
  return params.join('&');
}

function vlessLink(ctx, addr, port, tls, cc) {
  return 'vless://' + ctx.uuid + '@' + formatAddr(addr) + ':' + port + '?encryption=none&' +
    wsParams(ctx.host, ctx.paths.vlessPath, ctx.sni, ctx.fp, tls) + '#' + encodeURIComponent(configName('vless', addr, port, tls, cc));
}

function trojanLink(ctx, addr, port, tls, cc) {
  return 'trojan://' + encodeURIComponent(ctx.trojanPass) + '@' + formatAddr(addr) + ':' + port + '?' +
    wsParams(ctx.host, ctx.paths.trojanPath, ctx.sni, ctx.fp, tls) + '#' + encodeURIComponent(configName('trojan', addr, port, tls, cc));
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
  if (q.proto === 'vless') ctx.protocols.trojan = false;
  if (q.proto === 'trojan') ctx.protocols.vless = false;
  if (q.port && q.port.length) {
    settings = Object.assign({}, settings, {
      tlsPorts: q.port.filter((p) => TLS_PORTS.includes(p)),
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
  const entries = [];
  const limit = settings.entryLimit;
  // Interleave: iterate ports in the outer loop so the first N entries span
  // many addresses on 443/80 rather than every port of one address.
  for (const { port, tls } of ports) {
    for (const addr of addresses) {
      const cc = ccOf(addr);
      if (ctx.protocols.vless) entries.push({ proto: 'vless', addr, port, tls, cc, link: vlessLink(ctx, addr, port, tls, cc), name: configName('vless', addr, port, tls, cc) });
      if (ctx.protocols.trojan) entries.push({ proto: 'trojan', addr, port, tls, cc, link: trojanLink(ctx, addr, port, tls, cc), name: configName('trojan', addr, port, tls, cc) });
      if (entries.length >= limit) return { ctx, entries, preferredCc };
    }
  }
  return { ctx, entries, preferredCc };
}

function subscriptionHeaders(user, title) {
  const expire = user && user.expiresAt ? Math.floor(user.expiresAt / 1000) : 0;
  const headers = {
    'profile-title': 'base64:' + b64encode(title),
    'profile-update-interval': '12',
    'subscription-userinfo': 'upload=0; download=0; total=0' + (expire ? '; expire=' + expire : ''),
  };
  return headers;
}

function yamlStr(value) {
  return JSON.stringify(String(value));
}

function subQuery(url) {
  if (!url || !url.searchParams) return {};
  const q = url.searchParams;
  return {
    addr: splitCsv(q.get('addr') || q.get('ip') || ''),
    port: splitCsv(q.get('port') || q.get('ports') || '').map(Number).filter((p) => p > 0),
    proto: String(q.get('proto') || '').toLowerCase(),
    limit: Number(q.get('limit') || q.get('count') || 0) || 0,
    country: normalizeCountry(q.get('country') || q.get('cc') || ''),
    strict: q.get('strict') === '1' || q.get('strict') === 'true',
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
    '# 🐱 Cat Panel ' + CAT_PANEL_VERSION + ' — Mihomo / Clash Meta',
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
    '  - DOMAIN-SUFFIX,ir,DIRECT',
    '  - GEOIP,IR,DIRECT',
    '  - MATCH,🐱 Cat',
    '',
  ].join('\n');
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
      out.tls = { enabled: true, server_name: ctx.sni, insecure: false, utls: { enabled: true, fingerprint: ctx.fp } };
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
      rules: [{ domain_suffix: ['.ir'], server: 'dns-direct' }],
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
        { domain_suffix: ['.ir'], outbound: 'direct' },
      ],
      final: '🐱 Cat',
      auto_detect_interface: true,
    },
  };
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
      info: origin + '/info/' + user.id,
    };
  }
  return {
    sub: origin + '/sub/' + uuid,
    sub64: origin + '/sub64/' + uuid,
    clash: origin + '/clash/' + uuid,
    singbox: origin + '/singbox/' + uuid,
    info: origin + '/info/' + uuid,
  };
}

async function readJsonBody(request) {
  try { return await request.json(); } catch (e) { return null; }
}

function subResponse(kind, host, env, settings, uuid, user, url) {
  const title = panelTitle(env, settings) + (user ? ' · ' + user.name : '');
  const headers = subscriptionHeaders(user, title);
  const q = subQuery(url);
  if (kind === 'clash') {
    return new Response(buildClashYaml(host, env, settings, uuid, user, q), {
      headers: Object.assign({ 'content-type': 'text/yaml; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' }, headers),
    });
  }
  if (kind === 'singbox') {
    return new Response(JSON.stringify(buildSingboxConfig(host, env, settings, uuid, user, q), null, 2), {
      headers: Object.assign({ 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' }, headers),
    });
  }
  const { entries } = buildConfigEntries(host, env, settings, uuid, user, q);
  const body = entries.map((e) => e.link).join('\n') + '\n';
  const wantB64 = kind === 'sub64' || (url && url.searchParams.get('b64') === '1');
  return text(wantB64 ? b64encode(body) : body, 200, headers);
}

function blockedSubResponse(reason) {
  // Clients keep whatever they have; a 403 with a reason is enough.
  return text('subscription ' + reason, 403);
}

async function handleApi(request, url, env, ctx) {
  const path = url.pathname;
  const method = request.method.toUpperCase();
  const host = url.hostname;
  const settings = await readSettings(env);
  const masterUuid = await resolveUuid(host, env);

  if (path === '/api/health' || path === '/health') return json({ ok: true, version: CAT_PANEL_VERSION, kv: !!kvBinding(env) });
  if (path === '/api/version') return json({ ok: true, version: CAT_PANEL_VERSION, repo: REPO_URL });
  if (path === '/api/scan-targets.json') return json({ ok: true, ranges: SCAN_RANGES, tlsPorts: TLS_PORTS, plainPorts: PLAIN_PORTS, sni: effectiveSni(host, env, settings), host });
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
        settings: Object.assign({}, settings, { passwordHash: undefined, hasPassword: !!settings.passwordHash }),
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
      return json({ ok: true, persisted: saved.persisted, settings: Object.assign({}, saved.settings, { passwordHash: undefined, hasPassword: !!saved.settings.passwordHash }) }, 200, extra);
    }
    return json({ ok: false, error: 'method' }, 405);
  }

  if (path === '/api/ips' && method === 'POST') {
    const body = (await readJsonBody(request)) || {};
    // Entries may carry a country tag: "1.2.3.4#DE" (what Cat Client's scanner
    // saw via /cdn-cgi/trace) → stored in ipCountries, address stays clean.
    const tags = Object.assign({}, settings.ipCountries);
    const incoming = uniq((Array.isArray(body.ips) ? body.ips : splitCsv(body.ips)).map((raw) => { const t = splitAddrTag(raw); if (t.cc) tags[t.addr] = t.cc; return t.addr; }).filter((s) => isIpv4(s) || isIpv6(s) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(s)));
    if (body.countries && typeof body.countries === 'object') Object.assign(tags, normalizeCountryMap(body.countries, 500));
    const next = body.replace ? incoming : uniq(incoming.concat(settings.ips));
    const saved = await writeSettings(env, { ips: next, ipCountries: tags });
    return json({ ok: true, persisted: saved.persisted, count: saved.settings.ips.length, ips: saved.settings.ips });
  }

  if (path === '/api/users' || path.startsWith('/api/users/')) {
    const users = await readUsers(env);
    const id = path.split('/')[3] ? decodeURIComponent(path.split('/')[3]).toLowerCase() : '';
    const action = path.split('/')[4] || '';
    const decorate = (u) => Object.assign({}, u, { status: userBlockedReason(u) || 'active', links: subLinks(origin, masterUuid, u) });
    if (method === 'GET' && !id) return json({ ok: true, users: users.map(decorate) });
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
      return json({ ok: true, persisted: saved.persisted, user: decorate(user) }, 201);
    }
    if (!id) return json({ ok: false, error: 'method' }, 405);
    const existing = findUser(users, id);
    if (!existing) return json({ ok: false, error: 'not found' }, 404);
    if (method === 'DELETE') {
      const saved = await writeUsers(env, users.filter((u) => u.id !== id));
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

  if (path === '/api/update-check') {
    try {
      const res = await fetch(PANEL_SOURCE_URL, { headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION }, cf: { cacheTtl: 600 } });
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
    }).catch(() => { safeCloseWs(server, 1011, 'internal'); });
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job);
    const headers = {};
    const protocol = request.headers.get('sec-websocket-protocol');
    if (protocol) headers['sec-websocket-protocol'] = protocol;
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  if (path === '/dns-query') return handleDoh(request, env);
  if (path === '/robots.txt') return text('User-agent: *\nDisallow: /\n');
  if (path === '/health' || path.startsWith('/api/')) return handleApi(request, url, env, ctx);

  if (path === '/qr.svg' || path === '/qr') {
    const payload = url.searchParams.get('text') || url.searchParams.get('data') || '';
    if (!payload) return text('missing text', 400);
    return new Response(qrSvg(payload.slice(0, 2000), { dark: '#0b0614', light: '#ffffff' }), { headers: { 'content-type': 'image/svg+xml', 'cache-control': 'public, max-age=86400' } });
  }

  const settings = await readSettings(env);
  const masterUuid = await resolveUuid(host, env);

  /* master subscriptions: /sub/<uuid> /sub64/<uuid> /clash/<uuid> /singbox/<uuid> */
  const master = path.match(/^\/(sub|sub64|clash|singbox)(?:\/([^/]+))?\/?$/);
  if (master) {
    const kind = master[1];
    const key = (master[2] || '').toLowerCase();
    if (key === masterUuid || (!key && isTrue(env.OPEN_SUB))) return subResponse(kind, host, env, settings, masterUuid, null, url);
    if (key && isUuid(key)) {
      // Allow a user token on the master paths too (v2rayNG users sometimes edit the URL).
      const user = findUser(await readUsers(env), key);
      if (user) {
        const blocked = userBlockedReason(user);
        return blocked ? blockedSubResponse(blocked) : subResponse(kind, host, env, settings, user.id, user, url);
      }
    }
    return text('not found', 404);
  }

  /* user subscriptions: /u/<token>[/clash|/singbox|/64] */
  const per = path.match(/^\/u\/([^/]+)(?:\/(clash|singbox|64))?\/?$/);
  if (per) {
    const token = decodeURIComponent(per[1]).toLowerCase();
    const kind = per[2] === '64' ? 'sub64' : (per[2] || 'sub');
    if (token === masterUuid) return subResponse(kind, host, env, settings, masterUuid, null, url);
    const user = findUser(await readUsers(env), token);
    if (!user) return text('not found', 404);
    const blocked = userBlockedReason(user);
    if (blocked) return blockedSubResponse(blocked);
    return subResponse(kind, host, env, settings, user.id, user, url);
  }

  /* per-user landing page */
  const info = path.match(/^\/info\/([^/]+)\/?$/);
  if (info) {
    const token = decodeURIComponent(info[1]).toLowerCase();
    const user = token === masterUuid ? null : findUser(await readUsers(env), token);
    if (token !== masterUuid && !user) return html(notFoundPage(), 404);
    return html(userInfoPage(url.origin, host, env, settings, token, user));
  }

  if (path === '/logout') {
    return new Response(null, { status: 302, headers: { location: '/', 'set-cookie': SESSION_COOKIE + '=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax' } });
  }

  if (path === '/' || path === '/login' || path === '/panel') {
    // Legacy ?p=<password> entry: set the cookie and redirect to a clean URL.
    const quick = url.searchParams.get('p');
    if (quick && (await checkLogin(env, settings, masterUuid, url.searchParams.get('u') || '', quick))) {
      const token = await makeSession(env, settings, masterUuid);
      return new Response(null, { status: 302, headers: { location: '/', 'set-cookie': sessionCookieHeader(token) } });
    }
    const owner = await isOwner(request, env, settings, masterUuid);
    if (!owner) return html(loginPage(env, settings, !!String(env.PANEL_USER || '').trim()));
    return html(panelPage(env, settings, host, masterUuid));
  }

  return html(notFoundPage(), 404);
}


/* ------------------------------------------------------------------ */
/* HTML                                                                */
/* ------------------------------------------------------------------ */

const BASE_CSS = `
:root{--bg:#07060d;--bg2:#0c0a16;--card:#110e1f;--card2:#161229;--line:#241d3b;--line2:#2f2650;--text:#ece8ff;--mute:#9b93c2;--dim:#6b6490;
--violet:#8b5cf6;--violet2:#a78bfa;--fuchsia:#d946ef;--pink:#ec4899;--green:#22c55e;--amber:#f59e0b;--red:#ef4444;--cyan:#06b6d4;--blue:#3b82f6;--lime:#a3e635;
--r:16px;--sh:0 10px 40px rgba(0,0,0,.45)}
*{box-sizing:border-box;margin:0;padding:0}
html{-webkit-text-size-adjust:100%}
body{background:radial-gradient(1200px 600px at 80% -10%,rgba(139,92,246,.18),transparent 60%),radial-gradient(900px 500px at -10% 110%,rgba(217,70,239,.12),transparent 60%),var(--bg);color:var(--text);font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Vazirmatn","Noto Sans Arabic",Tahoma,sans-serif;min-height:100vh;line-height:1.5}
a{color:var(--violet2);text-decoration:none}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none}
input,select,textarea{font:inherit;color:var(--text);background:var(--bg2);border:1px solid var(--line);border-radius:12px;padding:10px 12px;width:100%;outline:none;transition:border-color .15s,box-shadow .15s}
input:focus,select:focus,textarea:focus{border-color:var(--violet);box-shadow:0 0 0 3px rgba(139,92,246,.18)}
textarea{min-height:110px;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px;direction:ltr;text-align:left}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;direction:ltr;unicode-bidi:embed}
.card{background:linear-gradient(180deg,var(--card),var(--bg2));border:1px solid var(--line);border-radius:var(--r);box-shadow:var(--sh)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:10px 16px;border-radius:12px;border:1px solid var(--line2);background:var(--card2);font-weight:600;font-size:14px;transition:transform .08s,background .15s,border-color .15s;white-space:nowrap}
.btn:hover{border-color:var(--violet);background:#1c1636}.btn:active{transform:translateY(1px)}
.btn.p{background:linear-gradient(135deg,var(--violet),var(--fuchsia));border-color:transparent;color:#fff;box-shadow:0 6px 24px rgba(139,92,246,.35)}
.btn.g{border-color:rgba(34,197,94,.5);color:var(--green)}.btn.r{border-color:rgba(239,68,68,.5);color:#fda4af}.btn.a{border-color:rgba(245,158,11,.5);color:#fcd34d}.btn.c{border-color:rgba(6,182,212,.5);color:#67e8f9}
.btn.sm{padding:6px 10px;font-size:12px;border-radius:10px}
.btn:disabled{opacity:.5;cursor:not-allowed}
.chip{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;border:1px solid var(--line2);background:var(--bg2)}
.chip.v{color:#c4b5fd;border-color:rgba(139,92,246,.5);background:rgba(139,92,246,.12)}.chip.t{color:#f0abfc;border-color:rgba(217,70,239,.5);background:rgba(217,70,239,.12)}
.chip.ok{color:#86efac;border-color:rgba(34,197,94,.5);background:rgba(34,197,94,.1)}.chip.bad{color:#fda4af;border-color:rgba(239,68,68,.5);background:rgba(239,68,68,.1)}.chip.warn{color:#fcd34d;border-color:rgba(245,158,11,.5);background:rgba(245,158,11,.1)}
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
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title><style>${BASE_CSS}
.wrap{min-height:100vh;display:grid;place-items:center;padding:20px}
.box{width:100%;max-width:380px;padding:28px 24px}
.logo{width:64px;height:64px;border-radius:20px;background:linear-gradient(135deg,var(--violet),var(--fuchsia));display:grid;place-items:center;font-size:34px;margin:0 auto 14px;box-shadow:0 10px 30px rgba(139,92,246,.4)}
h1{font-size:22px;text-align:center}.sub{text-align:center;margin-bottom:22px}
label{display:block;font-size:13px;color:var(--mute);margin:12px 0 6px}
.err{color:#fda4af;font-size:13px;min-height:18px;margin-top:10px;text-align:center}
</style></head><body><div class="wrap"><form class="card box" id="f">
<div class="logo">🐱</div><h1>${escapeHtml(title)}</h1><div class="sub mute small">${fa ? 'برای ورود رمز پنل را وارد کن' : 'Enter the panel password'}</div>
${needsUser ? `<label>${fa ? 'نام کاربری' : 'Username'}</label><input id="u" autocomplete="username">` : ''}
<label>${fa ? 'رمز عبور' : 'Password'}</label><input id="p" type="password" autocomplete="current-password" autofocus>
<div class="err" id="e"></div>
<button class="btn p" style="width:100%;margin-top:6px" type="submit">${fa ? 'ورود' : 'Sign in'}</button>
<div class="dim small" style="text-align:center;margin-top:16px">${fa ? 'رمز پیش‌فرض همان UUID پنل است' : 'Default password is the panel UUID'}</div>
</form></div>
<script>
document.getElementById('f').addEventListener('submit',function(ev){ev.preventDefault();var e=document.getElementById('e');e.textContent='';
var u=document.getElementById('u');fetch('/api/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({password:document.getElementById('p').value,username:u?u.value:''})})
.then(function(r){return r.json()}).then(function(j){if(j.ok)location.href='/';else e.textContent=${JSON.stringify(fa ? 'رمز اشتباه است' : 'Wrong password')}}).catch(function(){e.textContent='network'})});
</script></body></html>`;
}

function notFoundPage() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>404</title><style>${BASE_CSS}body{display:grid;place-items:center;min-height:100vh}</style></head><body><div style="text-align:center"><div style="font-size:64px">🐱</div><div class="mute">nothing here</div></div></body></html>`;
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
    sub: 'لینک اشتراک (همهٔ کلاینت‌ها)', clash: 'Clash / Mihomo', singbox: 'sing-box / Hiddify', copy: 'کپی', qr: 'QR', open: 'باز کردن در Cat Client',
    never: 'بدون انقضا', left: 'روز مانده', expired: 'منقضی شده', disabled: 'غیرفعال', active: 'فعال', apps: 'باز کردن در', hint: 'لینک را کپی کن و در کلاینت از بخش «افزودن اشتراک» وارد کن.',
  } : {
    sub: 'Subscription link (all clients)', clash: 'Clash / Mihomo', singbox: 'sing-box / Hiddify', copy: 'Copy', qr: 'QR', open: 'Open in Cat Client',
    never: 'never expires', left: 'days left', expired: 'expired', disabled: 'disabled', active: 'active', apps: 'Open in', hint: 'Copy the link and add it as a subscription in your client.',
  };
  const status = blocked === 'expired' ? ['bad', t.expired] : blocked === 'disabled' ? ['bad', t.disabled] : ['ok', t.active];
  const linkRow = (label, url) => `<div class="lk"><div class="small mute">${label}</div><div class="row" style="flex-wrap:nowrap"><input class="mono" readonly value="${escapeHtml(url)}"><button class="btn sm" data-copy="${escapeHtml(url)}">${t.copy}</button><button class="btn sm" data-qr="${escapeHtml(url)}">${t.qr}</button></div></div>`;
  const catLink = 'catclient://add-sub?url=' + encodeURIComponent(links.sub) + '&name=' + encodeURIComponent(title + ' ' + name);
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)} · ${escapeHtml(name)}</title><style>${BASE_CSS}
.wrap{max-width:640px;margin:0 auto;padding:22px 14px 60px}
.head{display:flex;align-items:center;gap:14px;margin-bottom:18px}
.logo{width:52px;height:52px;border-radius:16px;background:linear-gradient(135deg,var(--violet),var(--fuchsia));display:grid;place-items:center;font-size:28px;flex:none}
.lk{padding:12px 14px;border-top:1px solid var(--line)}.lk:first-child{border-top:0}
.lk input{font-size:12px}
.exp{padding:14px;display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:14px}
.bar{height:8px;border-radius:999px;background:#241d3b;overflow:hidden;margin-top:8px}.bar i{display:block;height:100%;background:linear-gradient(90deg,var(--violet),var(--fuchsia))}
.apps{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px;padding:14px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.7);display:none;place-items:center;z-index:50;padding:20px}.modal.show{display:grid}
</style></head><body><div class="wrap">
<div class="head"><div class="logo">🐱</div><div><div class="b" style="font-size:20px">${escapeHtml(name)}</div><div class="mute small">${escapeHtml(title)} · ${escapeHtml(host)}</div></div><span class="chip ${status[0]}" style="margin-inline-start:auto">${status[1]}</span></div>
<div class="card exp"><div><div class="small mute">${fa ? 'اعتبار زمانی' : 'Validity'}</div><div class="b">${expires ? (daysLeft > 0 ? daysLeft + ' ' + t.left : t.expired) : t.never}</div>${expires ? `<div class="dim small mono">${expires.toISOString().slice(0, 10)}</div>` : ''}</div><div style="font-size:32px">${expires ? '⏳' : '♾️'}</div></div>
<div class="card">
${linkRow(t.sub, links.sub)}
${linkRow(t.clash, links.clash)}
${linkRow(t.singbox, links.singbox)}
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
  return `<!doctype html><html lang="${fa ? 'fa' : 'en'}" dir="${fa ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#07060d"><title>${escapeHtml(title)}</title>
<link rel="icon" href="data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#8b5cf6"/><text x="32" y="44" font-size="36" text-anchor="middle">🐱</text></svg>')}">
<style>${BASE_CSS}
.top{position:sticky;top:0;z-index:20;background:rgba(7,6,13,.8);backdrop-filter:blur(14px);border-bottom:1px solid var(--line)}
.topin{max-width:1180px;margin:0 auto;padding:10px 14px;display:flex;align-items:center;gap:10px}
.brand{display:flex;align-items:center;gap:10px;font-weight:800;letter-spacing:.5px;font-size:18px}
.brand .lg{width:38px;height:38px;border-radius:12px;background:linear-gradient(135deg,var(--violet),var(--fuchsia));display:grid;place-items:center;font-size:20px;box-shadow:0 6px 20px rgba(139,92,246,.4)}
.brand .v{font-size:11px;color:var(--violet2);background:rgba(139,92,246,.15);border:1px solid rgba(139,92,246,.4);padding:1px 8px;border-radius:999px;font-weight:600}
.tools{display:flex;gap:8px;margin-inline-start:auto;flex-wrap:wrap;justify-content:flex-end}
.ib{width:38px;height:38px;border-radius:50%;display:grid;place-items:center;border:1.5px solid;background:var(--bg2);font-size:16px;transition:transform .1s,box-shadow .15s;position:relative}
.ib:hover{transform:translateY(-1px)}.ib.on{box-shadow:0 0 0 3px rgba(255,255,255,.06)}
.ib[data-c=red]{border-color:var(--red);color:#fda4af;box-shadow:0 0 14px rgba(239,68,68,.25)}
.ib[data-c=gray]{border-color:#6b6490;color:#c7c2e0}
.ib[data-c=green]{border-color:var(--green);color:#86efac;box-shadow:0 0 14px rgba(34,197,94,.25)}
.ib[data-c=amber]{border-color:var(--amber);color:#fcd34d;box-shadow:0 0 14px rgba(245,158,11,.25)}
.ib[data-c=cyan]{border-color:var(--cyan);color:#67e8f9;box-shadow:0 0 14px rgba(6,182,212,.25)}
.ib[data-c=violet]{border-color:var(--violet);color:#c4b5fd;box-shadow:0 0 14px rgba(139,92,246,.35)}
.ib[data-c=pink]{border-color:var(--pink);color:#f9a8d4;box-shadow:0 0 14px rgba(236,72,153,.25)}
.ib[data-c=blue]{border-color:var(--blue);color:#93c5fd;box-shadow:0 0 14px rgba(59,130,246,.25)}
.ib.on{background:linear-gradient(135deg,rgba(139,92,246,.35),rgba(217,70,239,.35))}
.main{max-width:1180px;margin:0 auto;padding:16px 14px 90px}
.view{display:none}.view.on{display:block}
.sec{padding:14px 16px;margin-bottom:14px}
.sec h2{font-size:15px;display:flex;align-items:center;gap:8px;margin-bottom:12px}
.sec h2 .ic{width:30px;height:30px;border-radius:10px;display:grid;place-items:center;font-size:15px;background:rgba(139,92,246,.15);border:1px solid rgba(139,92,246,.4)}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}
.st{padding:14px;border-radius:14px;background:var(--bg2);border:1px solid var(--line);position:relative;overflow:hidden}
.st .k{font-size:12px;color:var(--mute)}.st .n{font-size:26px;font-weight:800;margin-top:2px;letter-spacing:-.5px}.st .s{font-size:11px;color:var(--dim)}
.st .ic{position:absolute;top:10px;inset-inline-end:10px;width:30px;height:30px;border-radius:10px;display:grid;place-items:center;border:1px solid}
.st[data-c=violet] .n{color:#c4b5fd}.st[data-c=violet] .ic{border-color:var(--violet);color:#c4b5fd}
.st[data-c=green] .n{color:#86efac}.st[data-c=green] .ic{border-color:var(--green);color:#86efac}
.st[data-c=amber] .n{color:#fcd34d}.st[data-c=amber] .ic{border-color:var(--amber);color:#fcd34d}
.st[data-c=cyan] .n{color:#67e8f9}.st[data-c=cyan] .ic{border-color:var(--cyan);color:#67e8f9}
.st[data-c=pink] .n{color:#f9a8d4}.st[data-c=pink] .ic{border-color:var(--pink);color:#f9a8d4}
.bar{height:7px;border-radius:999px;background:#241d3b;overflow:hidden}.bar i{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,var(--green),var(--lime))}
.bar.w i{background:linear-gradient(90deg,var(--amber),#fde047)}.bar.d i{background:linear-gradient(90deg,var(--red),var(--pink))}
.fab{width:50px;height:50px;border-radius:50%;display:grid;place-items:center;font-size:22px;border:1.5px solid}
.fab[data-c=green]{border-color:var(--green);color:#86efac;box-shadow:0 0 18px rgba(34,197,94,.35)}
.fab[data-c=violet]{border-color:var(--violet);color:#c4b5fd;box-shadow:0 0 18px rgba(139,92,246,.4)}
.fab[data-c=amber]{border-color:var(--amber);color:#fcd34d;box-shadow:0 0 18px rgba(245,158,11,.35)}
.fab[data-c=cyan]{border-color:var(--cyan);color:#67e8f9;box-shadow:0 0 18px rgba(6,182,212,.35)}
.tbl{width:100%;border-collapse:separate;border-spacing:0 8px}
.tbl th{font-size:12px;color:var(--mute);font-weight:600;padding:4px 10px;text-align:start}
.tbl td{background:var(--bg2);padding:10px;border-top:1px solid var(--line);border-bottom:1px solid var(--line);vertical-align:middle}
[dir=rtl] .tbl td:first-child,[dir=ltr] .tbl td:last-child{border-inline-end:1px solid var(--line);border-start-end-radius:14px;border-end-end-radius:14px}
[dir=rtl] .tbl td:last-child,[dir=ltr] .tbl td:first-child{border-inline-start:1px solid var(--line);border-start-start-radius:14px;border-end-start-radius:14px}
.tbl tr:hover td{border-color:var(--line2)}
.act{display:flex;gap:6px;flex-wrap:wrap}
.act .ib{width:32px;height:32px;font-size:13px}
.ucard{display:none}
@media(max-width:860px){.tbl{display:none}.ucard{display:block}}
.uc{padding:12px 14px;margin-bottom:10px;background:var(--bg2);border:1px solid var(--line);border-radius:14px}
.uc .hd{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.uc .hd .nm{font-weight:700;font-size:15px}
.kv{display:grid;grid-template-columns:1fr 1fr;gap:8px;font-size:12px;margin:8px 0}
.kv div span{display:block;color:var(--dim);font-size:11px}
.frm label{display:block;font-size:13px;color:var(--mute);margin:12px 0 6px}
.frm .two{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:640px){.frm .two{grid-template-columns:1fr}}
.pick{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.pick button{padding:4px 10px;border-radius:999px;border:1px solid var(--line2);font-size:12px;color:var(--violet2)}
.pick button.on{background:rgba(139,92,246,.2);border-color:var(--violet)}
.proto{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.proto label{display:flex;align-items:center;gap:10px;margin:0;padding:12px;border:1px solid var(--line);border-radius:12px;background:var(--bg2);cursor:pointer;color:var(--text)}
.proto label.on{border-color:var(--violet);background:rgba(139,92,246,.1)}
.proto .ic{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;font-size:16px}
.drawer{position:fixed;inset:0;z-index:40;display:none}.drawer.show{display:block}
.drawer .bg{position:absolute;inset:0;background:rgba(0,0,0,.65)}
.drawer .pn{position:absolute;top:0;bottom:0;inset-inline-end:0;width:min(520px,100%);background:var(--bg);border-inline-start:1px solid var(--line);overflow:auto;padding:18px 16px 40px;box-shadow:var(--sh)}
.drawer .pn h3{display:flex;align-items:center;gap:10px;font-size:17px;margin-bottom:6px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.72);display:none;place-items:center;z-index:50;padding:20px}.modal.show{display:grid}
.modal .in{text-align:center}
.note{padding:10px 12px;border-radius:12px;font-size:13px;border:1px solid}
.note.i{background:rgba(6,182,212,.08);border-color:rgba(6,182,212,.35);color:#a5f3fc}
.note.w{background:rgba(245,158,11,.08);border-color:rgba(245,158,11,.35);color:#fde68a}
.note.e{background:rgba(239,68,68,.08);border-color:rgba(239,68,68,.35);color:#fecaca}
.note.g{background:rgba(34,197,94,.08);border-color:rgba(34,197,94,.35);color:#bbf7d0}
.lk{display:flex;gap:8px;align-items:center;padding:8px 0;border-top:1px solid var(--line)}.lk:first-child{border-top:0}
.lk input{font-size:12px;flex:1}
.ipl{display:flex;flex-wrap:wrap;gap:6px;max-height:220px;overflow:auto;padding:4px 0}
.ipl .chip{cursor:pointer}
.ipl .chip:hover{border-color:var(--red)}
.res{max-height:300px;overflow:auto;font-size:12px}
.res div{display:flex;justify-content:space-between;padding:6px 8px;border-bottom:1px solid var(--line)}
.nav{position:fixed;bottom:0;inset-inline:0;background:rgba(7,6,13,.92);backdrop-filter:blur(14px);border-top:1px solid var(--line);display:flex;justify-content:space-around;padding:6px 4px calc(6px + env(safe-area-inset-bottom));z-index:30}
.nav button{display:flex;flex-direction:column;align-items:center;gap:2px;font-size:11px;color:var(--dim);padding:6px 10px;border-radius:12px;min-width:60px}
.nav button span{font-size:18px}.nav button.on{color:#c4b5fd;background:rgba(139,92,246,.12)}
@media(min-width:861px){.nav{display:none}}
.search{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
.search input{flex:1;min-width:200px}.search select{width:auto}
.hr{height:1px;background:var(--line);margin:14px 0}
.empty{text-align:center;padding:40px 10px;color:var(--dim)}
.empty div{font-size:40px}
code{background:var(--bg2);border:1px solid var(--line);border-radius:6px;padding:1px 6px;font-size:12px;direction:ltr;unicode-bidi:embed}
.skel{height:14px;border-radius:6px;background:linear-gradient(90deg,var(--card2),var(--line),var(--card2));background-size:200% 100%;animation:sk 1.2s infinite}
@keyframes sk{0%{background-position:200% 0}100%{background-position:-200% 0}}
</style></head><body>
<div class="top"><div class="topin">
 <div class="brand"><div class="lg">🐱</div><span id="brandTitle">${escapeHtml(title)}</span><span class="v">v${CAT_PANEL_VERSION}</span></div>
 <div class="tools">
  <button class="ib" data-c="violet" data-view="dash" title="Dashboard">👥</button>
  <button class="ib" data-c="cyan" data-view="scan" title="Clean IP">📡</button>
  <button class="ib" data-c="gray" data-view="settings" title="Settings">⚙️</button>
  <button class="ib" data-c="amber" data-view="backup" title="Backup">💾</button>
  <button class="ib" data-c="green" id="btnUpdate" title="Update">⬆️</button>
  <button class="ib" data-c="blue" id="btnLang" title="Language">🌐</button>
  <button class="ib" data-c="pink" data-view="about" title="About">ℹ️</button>
  <a class="ib" data-c="red" href="/logout" title="Logout">⏻</a>
 </div>
</div></div>

<div class="main">

<!-- ================= DASHBOARD ================= -->
<section class="view on" id="v-dash">
 <div class="card sec">
  <h2><span class="ic">📊</span><span data-i="stats"></span></h2>
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
  <div class="row" style="justify-content:space-between;margin-bottom:12px">
   <h2 style="margin:0"><span class="ic">👥</span><span data-i="users"></span></h2>
   <div class="row">
    <button class="fab" data-c="green" id="btnAdd" title="+">＋</button>
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
   <th data-i="h_user"></th><th data-i="h_proto"></th><th data-i="h_links"></th><th data-i="h_time"></th><th data-i="h_status"></th><th data-i="h_act"></th>
  </tr></thead><tbody id="rows"></tbody></table>
  <div class="ucard" id="cards"></div>
  <div class="empty" id="empty" style="display:none"><div>🐾</div><div data-i="no_users"></div></div>
 </div>
</section>

<!-- ================= CLEAN IP / SCAN ================= -->
<section class="view" id="v-scan">
 <div class="card sec">
  <h2><span class="ic">📡</span><span data-i="scan_title"></span></h2>
  <div class="note i" data-i="scan_why"></div>
  <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr));margin-top:12px">
   <a class="btn p" id="btnScanApp" href="#">📱 <span data-i="scan_app"></span></a>
   <button class="btn c" id="btnBrowserTest">🌐 <span data-i="scan_browser"></span></button>
   <a class="btn" href="https://github.com/${REPO}#clean-ip" target="_blank" rel="noopener">📖 <span data-i="scan_guide"></span></a>
  </div>
  <div id="scanRes" class="res" style="margin-top:12px"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">📥</span><span data-i="ip_import"></span></h2>
  <div class="small mute" data-i="ip_import_hint"></div>
  <textarea id="ipPaste" placeholder="104.16.1.1#DE&#10;172.67.2.3#TR&#10;www.example.com"></textarea>
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
  <h2><span class="ic">🧹</span><span data-i="ip_list"></span> <span class="chip v" id="ipCount">0</span></h2>
  <div class="small mute" data-i="ip_list_hint"></div>
  <div class="ipl" id="ipList"></div>
  <div class="row" style="margin-top:10px"><button class="btn r sm" id="btnIpClear" data-i="ip_clear"></button><button class="btn sm" id="btnIpCopy" data-i="copy_all"></button></div>
 </div>
</section>

<!-- ================= SETTINGS ================= -->
<section class="view" id="v-settings">
 <form class="card sec frm" id="fSettings">
  <h2><span class="ic">⚙️</span><span data-i="settings"></span></h2>
  <div class="two">
   <div><label data-i="s_title"></label><input name="ptitle" maxlength="60"></div>
   <div><label data-i="s_lang"></label><select name="plang"><option value="fa">فارسی</option><option value="en">English</option></select></div>
  </div>
  <label data-i="s_pass"></label>
  <div class="row"><input name="password" type="password" autocomplete="new-password" data-ph="s_pass_ph" style="flex:1"><span class="chip" id="passState"></span></div>
  <div class="hr"></div>
  <label data-i="s_protocols"></label>
  <div class="proto">
   <label id="pVless"><span class="ic" style="background:rgba(139,92,246,.2);color:#c4b5fd">✈️</span><div><div class="b">VLESS</div><div class="dim small" data-i="p_vless"></div></div><input type="checkbox" name="pv" style="width:auto;margin-inline-start:auto"></label>
   <label id="pTrojan"><span class="ic" style="background:rgba(217,70,239,.2);color:#f0abfc">🛡️</span><div><div class="b">Trojan</div><div class="dim small" data-i="p_trojan"></div></div><input type="checkbox" name="pt" style="width:auto;margin-inline-start:auto"></label>
  </div>
  <div class="two">
   <div><label data-i="s_tls"></label><div class="pick" id="pickTls"></div></div>
   <div><label data-i="s_plain"></label><div class="pick" id="pickPlain"></div><div class="row small" style="margin-top:8px"><span class="sw" id="swPlain"></span><span data-i="s_plain_on"></span></div></div>
  </div>
  <div class="two">
   <div><label data-i="s_sni"></label><input name="sni" class="mono" data-ph="s_sni_ph"></div>
   <div><label data-i="s_fp"></label><select name="fingerprint"><option>chrome</option><option>firefox</option><option>safari</option><option>ios</option><option>android</option><option>edge</option><option>random</option><option>randomized</option></select></div>
  </div>
  <div class="two">
   <div><label data-i="s_limit"></label><input name="entryLimit" type="number" min="4" max="200"></div>
   <div><label data-i="s_flags"></label><div class="row small" style="margin-top:6px"><span class="sw" id="swDefaults"></span><span data-i="s_defaults"></span></div><div class="row small" style="margin-top:8px"><span class="sw" id="swHost"></span><span data-i="s_host"></span></div></div>
  </div>
  <label data-i="s_proxy"></label>
  <textarea name="proxyIps" style="min-height:70px" data-ph="s_proxy_ph"></textarea>
  <div class="small dim" data-i="s_proxy_hint"></div>
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
 <button data-view="dash"><span>👥</span><i data-i="n_dash"></i></button>
 <button data-view="scan"><span>📡</span><i data-i="n_scan"></i></button>
 <button data-view="settings"><span>⚙️</span><i data-i="n_set"></i></button>
 <button data-view="backup"><span>💾</span><i data-i="n_bak"></i></button>
</div>

<!-- user drawer -->
<div class="drawer" id="drawer"><div class="bg" data-close></div><div class="pn frm">
 <div class="row" style="justify-content:space-between"><h3><span class="ic" style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:rgba(139,92,246,.2)">👤</span><span id="dTitle"></span></h3><button class="ib" data-c="red" data-close>✕</button></div>
 <div class="small mute" data-i="d_sub"></div>
 <form id="fUser">
  <label data-i="u_name"></label>
  <div class="row"><input name="uname" maxlength="40" required style="flex:1"><button class="btn sm" type="button" id="btnRandName">🎲 <span data-i="u_rand"></span></button></div>
  <label data-i="u_protocols"></label>
  <div class="proto">
   <label id="uVless"><span class="ic" style="background:rgba(139,92,246,.2);color:#c4b5fd">✈️</span><div><div class="b">VLESS</div><div class="dim small" data-i="p_vless"></div></div><input type="checkbox" name="pv" checked style="width:auto;margin-inline-start:auto"></label>
   <label id="uTrojan"><span class="ic" style="background:rgba(217,70,239,.2);color:#f0abfc">🛡️</span><div><div class="b">Trojan</div><div class="dim small" data-i="p_trojan"></div></div><input type="checkbox" name="pt" checked style="width:auto;margin-inline-start:auto"></label>
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
h_user:'کاربر',h_proto:'پروتکل',h_links:'لینک ساب',h_time:'زمان',h_status:'وضعیت',h_act:'عملیات',no_users:'هنوز کاربری نساختی. با دکمهٔ + اولین کاربر را بساز.',
scan_title:'آی‌پی تمیز و اسکنر',scan_why:'اسکن روی دستگاه خودت انجام می‌شود (نه داخل ورکر). این دقیقاً روشی است که BPB و ZEUS استفاده می‌کنند: ورکر هیچ درخواستی خرج نمی‌کند و نتیجه از شبکهٔ واقعی تو (همان اپراتور) به دست می‌آید.',
scan_app:'اسکن با Cat Client',scan_browser:'تست دامنه‌ها در مرورگر',scan_guide:'راهنمای اسکنرها',ip_import:'وارد کردن نتیجهٔ اسکن',ip_import_hint:'آی‌پی‌ها یا دامنه‌های تمیز را (هر خط یکی، یا با کاما) اینجا بچسبان. از Cat Client، اسکنر ircf، CFScanner یا هر ابزار دیگری.',
ip_append:'افزودن به لیست',ip_replace:'جایگزینی کل لیست',ip_list:'لیست آی‌پی‌های پنل',ip_list_hint:'این‌ها اول هر اشتراک قرار می‌گیرند. برای حذف روی هر مورد بزن.',ip_clear:'پاک کردن همه',copy_all:'کپی همه',cc_title:'کشورها',cc_why:'هر آدرس را با کشوری که برای تو از آن خارج می‌شود برچسب بزن (از اسکنر Cat Client به شکل ip#DE بچسبان، یا دستی از منوی هر آی‌پی). روی یک کشور بزن تا کانفیگ‌ها فقط از همان کشور باشند؛ اگر همهٔ آی‌پی‌های آن کشور بسته شوند، به سریع‌ترین کشور دیگر می‌رود.',cc_auto:'🤖 خودکار (همهٔ کشورها)',cc_fallback:'وقتی همهٔ آی‌پی‌های کشور انتخابی بسته شد',cc_fb_auto:'برو سریع‌ترین کشور دیگر (پیشنهادی)',cc_fb_none:'هیچ‌وقت کشور عوض نشود (قطع شود)',cc_proxy:'Proxy IP‌ها',cc_proxy_btn:'🌍 تشخیص کشور Proxy IP‌ها',cc_hint:'در Clash/Mihomo و Cat Client جابه‌جایی خودکار است؛ در V2Box/sing-box کشور پیش‌فرض انتخاب می‌شود و بقیه در لیست می‌مانند. لینک فقط-یک-کشور: دکمهٔ 🔗 کنار هر کشور (?country=XX&strict=1).',cc_untagged:'بدون کشور',cc_link:'لینک فقط این کشور',
settings:'تنظیمات پنل',s_title:'عنوان پنل',s_lang:'زبان',s_pass:'رمز پنل',s_pass_ph:'خالی = بدون تغییر',s_protocols:'پروتکل‌ها',p_vless:'سبک و پرسرعت',p_trojan:'جایگزین امن',
s_tls:'پورت‌های TLS',s_plain:'پورت‌های بدون TLS (HTTP)',s_plain_on:'کانفیگ‌های بدون TLS هم ساخته شود',s_sni:'SNI / Host',s_sni_ph:'پیش‌فرض: آدرس ورکر',s_fp:'فینگرپرینت TLS',s_limit:'حداکثر کانفیگ در هر ساب',
s_flags:'گزینه‌ها',s_defaults:'افزودن آدرس‌های پیش‌فرض بعد از لیست من',s_host:'خود آدرس ورکر هم به‌عنوان آدرس اضافه شود',s_proxy:'Proxy IP (برای سایت‌های پشت کلودفلر)',s_proxy_ph:'خالی = لیست پیش‌فرض',s_proxy_hint:'هر خط یک آدرس یا host:port. فقط وقتی مقصد خودش پشت کلودفلر باشد استفاده می‌شود.',s_chain:'خروجی ثابت (IP و کشور ثابت)',s_chain_ph:'socks5://user:pass@1.2.3.4:1080  یا  http://host:3128',s_chain_hint:'ورکر همهٔ ترافیک را از این سرور (VPS خودت) بیرون می‌فرستد؛ در نتیجه IP و کشور همیشه یکی است. خالی = خروجی خود کلودفلر (کشور ممکن است عوض شود).',s_chain_mode:'کدام مقصدها',s_chain_all:'همهٔ سایت‌ها (کاملاً ثابت)',s_chain_cf:'فقط سایت‌های پشت کلودفلر (به‌جای Proxy IP)',s_chain_strict:'سخت‌گیرانه',s_chain_strict_on:'اگر سرور زنجیره در دسترس نبود، قطع شو (نشت نکن)',s_chain_test:'🧪 تست زنجیره',chain_off:'غیرفعال',chain_ok:'وصل شد',chain_fail:'ناموفق',
save:'ذخیره تغییرات',cancel:'انصراف',saved:'ذخیره شد',saved_nokv:'ذخیره شد (موقت — KV وصل نیست!)',paths:'مسیرها و اتصال',
backup:'پشتیبان‌گیری',backup_hint:'یک فایل JSON شامل تنظیمات و کاربران. برای انتقال پنل به ورکر/اکانت دیگر همین فایل را بازگردانی کن.',backup_dl:'دانلود پشتیبان',backup_up:'بازگردانی',
limits:'چرا این نسخه بن نمی‌شود؟',limits_text:'کلودفلر رایگان: ۱۰۰هزار درخواست/روز، ۱۰ms CPU برای هر درخواست، ۱۰۰۰ نوشتن KV/روز. نسخهٔ ۶ هیچ آمار مصرفی در KV نمی‌نویسد (فقط وقتی تو ذخیره می‌زنی)، هیچ اسکنی داخل ورکر انجام نمی‌دهد، و رلهٔ ترافیک یک pipe ساده بدون شمارنده است. نتیجه: مصرف CPU و KV نزدیک صفر، مثل BPB.',
about_text:'پنل تک‌فایلی Cat برای Cloudflare Worker. نسخهٔ lean: بدون حسابداری ترافیک، بدون اسکن سمت سرور، رلهٔ کم‌مصرف. مجوز GPL — سورس در گیت‌هاب.',
n_dash:'کاربران',n_scan:'آی‌پی',n_set:'تنظیمات',n_bak:'پشتیبان',
d_new:'کاربر جدید',d_edit:'ویرایش کاربر',d_sub:'نام، پروتکل‌ها و مدت اعتبار',u_name:'نام کاربری',u_rand:'تصادفی',u_protocols:'پروتکل‌های مجاز',u_days:'مدت اعتبار (روز) — ۰ یعنی نامحدود',u_note:'یادداشت',u_enabled:'فعال',
u_noquota:'این نسخه حجم مصرفی را نمی‌شمارد (شمارش حجم همان چیزی بود که KV را پر و ورکر را بن می‌کرد). محدودیت فقط زمانی است.',
unlimited:'نامحدود',days:'روز',left:'مانده',expired:'منقضی',disabled:'غیرفعال',active:'فعال',copied:'کپی شد',deleted:'حذف شد',confirm_del:'این کاربر حذف شود؟',renew:'تمدید ۳۰ روز',toggle:'فعال/غیرفعال',edit:'ویرایش',del:'حذف',qr:'QR',info:'صفحهٔ کاربر',
kv_on:'KV متصل',kv_off:'KV وصل نیست — داده‌ها ذخیره نمی‌شوند!',pass_uuid:'رمز = UUID (تغییرش بده!)',pass_env:'رمز از ENV',pass_set:'رمز تنظیم شده',pass_open:'پنل باز است — رمز بگذار!',
self_wait:'در حال دریافت…',browser_note:'مرورگر فقط دامنه‌ها را می‌تواند تست کند (آی‌پی خام گواهی TLS ندارد). برای اسکن آی‌پی از Cat Client استفاده کن.',
update_check:'بررسی نسخهٔ جدید…',update_ok:'آخرین نسخه را داری',update_new:'نسخهٔ جدید موجود است: ',update_how:'از تب «پنل من» در Cat Client یا با چسباندن فایل جدید در Workers به‌روزرسانی کن.',
sync_hint:'اشتراک اصلی را در Cat Client باز می‌کند',restore_ok:'بازگردانی شد',restore_bad:'فایل نامعتبر',sub:'ساب',clash:'Clash',singbox:'sing-box'},
en:{stats:'Panel status',st_users:'Users',st_users_s:'defined in panel',st_active:'Active',st_active_s:'not expired / disabled',st_exp:'Expired / disabled',st_exp_s:'need renewal',st_ips:'Clean IPs',st_cfg:'Configs per sub',
master_links:'Master subscription links',self:'My connection info',users:'Users',search:'Search name or UUID…',f_all:'All',f_active:'Active',f_expired:'Expired',f_disabled:'Disabled',s_new:'Newest',s_exp:'Expiring soon',s_name:'Name',
h_user:'User',h_proto:'Protocol',h_links:'Sub links',h_time:'Time',h_status:'Status',h_act:'Actions',no_users:'No users yet — tap + to create one.',
scan_title:'Clean IP & scanner',scan_why:'Scanning runs on YOUR device, not inside the worker — exactly what BPB and ZEUS do. The worker spends zero requests and results reflect your real network.',
scan_app:'Scan with Cat Client',scan_browser:'Test domains in browser',scan_guide:'Scanner guide',ip_import:'Import scan results',ip_import_hint:'Paste clean IPs or domains (one per line or comma separated) from Cat Client, ircf scanner, CFScanner or any other tool.',
ip_append:'Append',ip_replace:'Replace list',ip_list:'Panel IP list',ip_list_hint:'These come first in every subscription. Tap one to remove it.',ip_clear:'Clear all',copy_all:'Copy all',cc_title:'Countries',cc_why:'Tag each address with the country it exits from FOR YOU (paste ip#DE from the Cat Client scanner, or pick from the menu next to each ip). Click a country to serve configs from it only; when all of its ips die, the fastest other country takes over.',cc_auto:'🤖 Automatic (all countries)',cc_fallback:'When every ip of the chosen country is dead',cc_fb_auto:'switch to the fastest other country (recommended)',cc_fb_none:'never leave the country (fail instead)',cc_proxy:'Proxy IPs',cc_proxy_btn:'🌍 Detect proxy-IP countries',cc_hint:'Clash/Mihomo and Cat Client switch automatically; V2Box/sing-box get the chosen country as default with the rest listed. Single-country link: 🔗 next to each country (?country=XX&strict=1).',cc_untagged:'untagged',cc_link:'link for this country only',
settings:'Panel settings',s_title:'Panel title',s_lang:'Language',s_pass:'Panel password',s_pass_ph:'empty = unchanged',s_protocols:'Protocols',p_vless:'light & fast',p_trojan:'secure alternative',
s_tls:'TLS ports',s_plain:'Non-TLS ports (HTTP)',s_plain_on:'also emit non-TLS configs',s_sni:'SNI / Host',s_sni_ph:'default: worker host',s_fp:'TLS fingerprint',s_limit:'Max configs per sub',
s_flags:'Options',s_defaults:'append default addresses after mine',s_host:'also include the worker hostname',s_proxy:'Proxy IP (for Cloudflare-hosted sites)',s_proxy_ph:'empty = built-in list',s_proxy_hint:'One per line, host or host:port. Only used when the destination itself is behind Cloudflare.',s_chain:'Fixed exit (stable IP & country)',s_chain_ph:'socks5://user:pass@1.2.3.4:1080  or  http://host:3128',s_chain_hint:'The worker sends all traffic out through this server (your own VPS), so the IP/country never changes. Empty = Cloudflare egress (country may vary).',s_chain_mode:'Which destinations',s_chain_all:'everything (fully stable)',s_chain_cf:'only Cloudflare-hosted sites (instead of Proxy IP)',s_chain_strict:'Strict',s_chain_strict_on:'if the chain is down, fail instead of leaking',s_chain_test:'🧪 Test chain',chain_off:'off',chain_ok:'connected',chain_fail:'failed',
save:'Save',cancel:'Cancel',saved:'Saved',saved_nokv:'Saved (volatile — KV not bound!)',paths:'Paths & connection',
backup:'Backup',backup_hint:'A JSON file with settings and users. Restore it on another worker/account to move the panel.',backup_dl:'Download backup',backup_up:'Restore',
limits:'Why this version does not get banned',limits_text:'Cloudflare free tier: 100k requests/day, 10 ms CPU per request, 1 000 KV writes/day. v6 writes KV only when you save, never scans from the worker, and the relay is a plain pipe with no counters. CPU and KV usage stay near zero, like BPB.',
about_text:'Single-file Cat panel for Cloudflare Workers. Lean edition: no traffic accounting, no server-side scanning, low-CPU relay. GPL — source on GitHub.',
n_dash:'Users',n_scan:'IPs',n_set:'Settings',n_bak:'Backup',
d_new:'New user',d_edit:'Edit user',d_sub:'Name, protocols and validity',u_name:'Username',u_rand:'random',u_protocols:'Allowed protocols',u_days:'Validity (days) — 0 = unlimited',u_note:'Note',u_enabled:'Enabled',
u_noquota:'This version does not meter traffic (traffic metering is what filled KV and got workers throttled). Limits are time-based only.',
unlimited:'unlimited',days:'days',left:'left',expired:'expired',disabled:'disabled',active:'active',copied:'Copied',deleted:'Deleted',confirm_del:'Delete this user?',renew:'Renew 30 days',toggle:'Enable/disable',edit:'Edit',del:'Delete',qr:'QR',info:'User page',
kv_on:'KV bound',kv_off:'KV NOT bound — nothing persists!',pass_uuid:'password = UUID (change it!)',pass_env:'password from ENV',pass_set:'password set',pass_open:'panel is OPEN — set a password!',
self_wait:'loading…',browser_note:'Browsers can only test domains (raw IPs have no TLS certificate). Use Cat Client to scan IPs.',
update_check:'Checking for updates…',update_ok:'You are on the latest version',update_new:'New version available: ',update_how:'Update from the “My Panel” tab in Cat Client or paste the new file into Workers.',
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
function load(){return api('/api/settings').then(function(j){CFG=j;renderCfg();return api('/api/users')}).then(function(j){USERS=j.users||[];renderUsers();renderStats()})}
function renderStats(){var active=USERS.filter(function(u){return statusOf(u)==='active'}).length;
 $('#stUsers').textContent=USERS.length;$('#stActive').textContent=active;$('#stExp').textContent=USERS.length-active;
 var ips=CFG.settings.ips.length;$('#stIps').textContent=ips;$('#stIpsS').textContent=(CFG.settings.useDefaults?'+ '+CFG.defaults.addresses.length+' default':'');
 var addrs=ips+(CFG.settings.useDefaults?CFG.defaults.addresses.length:0)+(CFG.settings.includeHost?1:0);
 var ports=CFG.settings.tlsPorts.length+(CFG.settings.plainEnabled?CFG.settings.plainPorts.length:0);var protos=(CFG.settings.protocols.vless?1:0)+(CFG.settings.protocols.trojan?1:0);
 $('#stCfg').textContent=Math.min(CFG.settings.entryLimit,addrs*ports*protos);$('#stCfgS').textContent=addrs+' × '+ports+' × '+protos;
 var kv=$('#chipKv');kv.textContent=(CFG.kv?'🟢 ':'🔴 ')+t(CFG.kv?'kv_on':'kv_off');kv.className='chip '+(CFG.kv?'ok':'bad');
 var ps=$('#chipPass');var k=CFG.open?'pass_open':CFG.passwordSource==='panel'?'pass_set':CFG.passwordSource==='env'?'pass_env':'pass_uuid';ps.textContent=t(k);ps.className='chip '+(k==='pass_set'||k==='pass_env'?'ok':'warn');
 $('#chipHost').textContent=CFG.host;$('#passState').textContent=t(k);$('#passState').className='chip '+(k==='pass_set'||k==='pass_env'?'ok':'warn');}
function renderCfg(){var s=CFG.settings,f=$('#fSettings');f.elements.ptitle.value=s.title||'';f.elements.plang.value=s.lang;f.elements.sni.value=s.sni||'';f.elements.fingerprint.value=s.fingerprint;f.elements.entryLimit.value=s.entryLimit;f.elements.proxyIps.value=(s.proxyIps||[]).join('\\n');f.elements.chain.value=s.chain||'';f.elements.chainMode.value=s.chainMode||'all';$('#swStrict').classList.toggle('on',!!s.chainStrict);var cs=$('#chainState');cs.textContent=CFG.chain?(CFG.chain.type+' · '+CFG.chain.host):t('chain_off');cs.className='chip '+(CFG.chain?'ok':'');
 f.elements.pv.checked=s.protocols.vless;f.elements.pt.checked=s.protocols.trojan;syncProto('#pVless','#pTrojan');
 $('#swPlain').classList.toggle('on',s.plainEnabled);$('#swDefaults').classList.toggle('on',s.useDefaults);$('#swHost').classList.toggle('on',s.includeHost);
 pick('#pickTls',CFG.defaults.tlsPorts,s.tlsPorts);pick('#pickPlain',CFG.defaults.plainPorts,s.plainPorts);
 if(s.title)$('#brandTitle').textContent=s.title;
 $('#pathsBox').innerHTML='<div class="lk"><span>VLESS</span><code>'+esc(CFG.paths.vlessPath)+'</code></div><div class="lk"><span>Trojan</span><code>'+esc(CFG.paths.trojanPath)+'</code></div><div class="lk"><span>SNI</span><code>'+esc(CFG.sni)+'</code></div><div class="lk"><span>UUID</span><code>'+esc(CFG.uuid)+'</code><button class="btn sm" data-copy="'+esc(CFG.uuid)+'">📋</button></div>'+
  (CFG.env.hasUuid?'':'<div class="note w small" style="margin-top:8px">UUID از نام ورکر مشتق شده؛ برای ثابت ماندن بعد از تغییر نام، متغیر UUID را در Workers → Settings تنظیم کن.</div>');
 renderIps();}
function pick(sel,all,chosen){var box=$(sel);box.innerHTML='';all.forEach(function(p){var b=document.createElement('button');b.type='button';b.textContent=p;b.dataset.v=p;if(chosen.indexOf(p)>=0)b.classList.add('on');b.onclick=function(){b.classList.toggle('on')};box.appendChild(b)})}
function picked(sel){return $$('button.on',$(sel)).map(function(b){return Number(b.dataset.v)})}
function syncProto(a,b){[a,b].forEach(function(s){var l=$(s);l.classList.toggle('on',$('input',l).checked)})}
$$('#pVless input,#pTrojan input').forEach(function(i){i.addEventListener('change',function(){syncProto('#pVless','#pTrojan')})});
$$('#uVless input,#uTrojan input').forEach(function(i){i.addEventListener('change',function(){syncProto('#uVless','#uTrojan')})});
$$('.sw').forEach(function(s){s.addEventListener('click',function(){s.classList.toggle('on')})});

$('#fSettings').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var body={title:f.elements.ptitle.value,lang:f.elements.plang.value,sni:f.elements.sni.value,fingerprint:f.elements.fingerprint.value,entryLimit:Number(f.elements.entryLimit.value),
 proxyIps:f.elements.proxyIps.value.split(/[\\s,]+/).filter(Boolean),protocols:{vless:f.elements.pv.checked,trojan:f.elements.pt.checked},tlsPorts:picked('#pickTls'),plainPorts:picked('#pickPlain'),plainEnabled:$('#swPlain').classList.contains('on'),useDefaults:$('#swDefaults').classList.contains('on'),includeHost:$('#swHost').classList.contains('on'),chain:f.elements.chain.value.trim(),chainMode:f.elements.chainMode.value,chainStrict:$('#swStrict').classList.contains('on')};
 if(f.elements.password.value)body.password=f.elements.password.value;var changedLang=body.lang!==lang;
 api('/api/settings',{method:'PUT',body:body}).then(function(j){if(!j.ok)throw 0;f.elements.password.value='';toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);if(changedLang){location.reload();return}return load()}).catch(function(){toast('error',true)})});

document.addEventListener('click',function(e){var b=e.target.closest('[data-cc]');if(!b)return;api('/api/countries',{method:'PUT',body:{country:b.getAttribute('data-cc')}}).then(function(){toast(t('saved'));return load()}).catch(function(){toast('error',true)})});
document.addEventListener('change',function(e){var sel=e.target.closest('[data-ipcc]');if(!sel)return;var ip=sel.getAttribute('data-ipcc'),cc=sel.value;var body=cc?{ipCountries:{}}:{clearIp:ip};if(cc)body.ipCountries[ip]=cc;api('/api/countries',{method:'PUT',body:body}).then(function(){return load()}).catch(function(){toast('error',true)})});
$('#ccFallback').addEventListener('change',function(){api('/api/countries',{method:'PUT',body:{countryFallback:$('#ccFallback').value}}).then(function(){toast(t('saved'));return load()})});
$('#btnProxyGeo').addEventListener('click',function(){var o=$('#proxyGeoOut');o.textContent='…';api('/api/proxy-geo',{method:'POST'}).then(function(j){var f=j.found||{};o.textContent=Object.keys(f).map(function(k){return flag(f[k])+' '+k}).join('  ')||'—';return load()}).catch(function(){o.textContent='✗'})});
$('#btnChainTest').addEventListener('click',function(){var o=$('#chainTestOut');var c=$('#fSettings').elements.chain.value.trim();if(!c){o.textContent=t('chain_off');return}o.textContent='…';api('/api/chain-test',{method:'POST',body:{chain:c}}).then(function(j){o.textContent=(j.ok?'🟢 '+t('chain_ok')+' · '+j.ms+'ms':'🔴 '+t('chain_fail')+' · '+(j.error||j.status||''))}).catch(function(e){o.textContent='🔴 '+t('chain_fail')+' · '+(e&&e.message||'')})});

/* ---------- users ---------- */
function protoChips(u){var h='';if(u.protocols.vless)h+='<span class="chip v">VLESS</span> ';if(u.protocols.trojan)h+='<span class="chip t">Trojan</span>';return h}
function timeCell(u){var d=daysLeft(u);if(d===null)return '<span class="chip">♾️ '+t('unlimited')+'</span>';var total=Math.max(1,Math.round((u.expiresAt-u.createdAt)/86400000));var pct=Math.max(0,Math.min(100,Math.round(d/total*100)));
 var cls=d<=0?'d':d<=5?'w':'';return '<div class="small">'+(d>0?d+' '+t('days')+' '+t('left'):t('expired'))+' <span class="dim">· '+fmtDate(u.expiresAt)+'</span></div><div class="bar '+cls+'" style="margin-top:4px;width:120px"><i style="width:'+pct+'%"></i></div>'}
function statusChip(u){var s=statusOf(u);return '<span class="chip '+(s==='active'?'ok':'bad')+'">'+(s==='active'?'🟢':s==='expired'?'⏰':'⛔')+' '+t(s)+'</span>'}
function linkBtns(u){return '<div class="act"><button class="btn sm g" data-copy="'+esc(u.links.sub)+'">🔗 '+t('sub')+'</button><button class="btn sm c" data-copy="'+esc(u.links.clash)+'">'+t('clash')+'</button><button class="btn sm" data-copy="'+esc(u.links.singbox)+'">'+t('singbox')+'</button><button class="btn sm" data-qr="'+esc(u.links.sub)+'" data-qrl="'+esc(u.name)+'">▦</button><a class="btn sm" href="'+esc(u.links.info)+'" target="_blank" rel="noopener">↗</a></div>'}
function actBtns(u){return '<div class="act"><button class="ib" data-c="violet" data-edit="'+u.id+'" title="'+t('edit')+'">✏️</button><button class="ib" data-c="green" data-renew="'+u.id+'" title="'+t('renew')+'">🔁</button><button class="ib" data-c="amber" data-toggle="'+u.id+'" title="'+t('toggle')+'">'+(u.enabled?'⏸':'▶️')+'</button><button class="ib" data-c="red" data-del="'+u.id+'" title="'+t('del')+'">🗑</button></div>'}
function filtered(){var q=($('#q').value||'').toLowerCase(),f=$('#flt').value,s=$('#srt').value;var list=USERS.filter(function(u){if(q&&u.name.toLowerCase().indexOf(q)<0&&u.id.indexOf(q)<0)return false;if(f!=='all'&&statusOf(u)!==f)return false;return true});
 list.sort(function(a,b){if(s==='name')return a.name.localeCompare(b.name);if(s==='exp'){var x=a.expiresAt||9e15,y=b.expiresAt||9e15;return x-y}return b.createdAt-a.createdAt});return list}
function renderUsers(){var list=filtered();$('#empty').style.display=USERS.length?'none':'block';
 $('#rows').innerHTML=list.map(function(u){return '<tr><td><div class="b">'+esc(u.name)+'</div><div class="dim small mono">'+u.id.slice(0,8)+'…</div>'+(u.note?'<div class="dim small">'+esc(u.note)+'</div>':'')+'</td><td>'+protoChips(u)+'</td><td>'+linkBtns(u)+'</td><td>'+timeCell(u)+'</td><td>'+statusChip(u)+'</td><td>'+actBtns(u)+'</td></tr>'}).join('');
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
function renderIps(){var ips=CFG.settings.ips,tags=CFG.settings.ipCountries||{};$('#ipCount').textContent=ips.length;$('#ipList').innerHTML=ips.length?ips.map(function(ip){return '<span class="chip mono">'+ccSelect(ip,tags[ip]||'')+' '+esc(ip)+' <b data-ipdel="'+esc(ip)+'" title="remove" style="cursor:pointer">✕</b></span>'}).join(''):'<span class="dim small">—</span>';renderCountries();
 $('#btnScanApp').href='catclient://scan?sni='+encodeURIComponent(CFG.host)+'&panel='+encodeURIComponent(location.origin)}
function importIps(replace){var raw=$('#ipPaste').value;var ips=raw.split(/[\\s,;]+/).map(function(s){return s.trim().replace(/^\\[|\\]$/g,'')}).filter(function(s){s=s.replace(/[#|=][A-Za-z]{2}$/,'');return /^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(s)||/^[0-9a-f:]+$/i.test(s)&&s.indexOf(':')>=0||/^[a-z0-9.-]+\\.[a-z]{2,}$/i.test(s)});
 if(!ips.length){toast('0',true);return}api('/api/ips',{method:'POST',body:{ips:ips,replace:!!replace}}).then(function(j){toast(j.count+' ✓');$('#ipPaste').value='';return load()})}
$('#btnIpAppend').addEventListener('click',function(){importIps(false)});$('#btnIpReplace').addEventListener('click',function(){importIps(true)});
$('#btnIpClear').addEventListener('click',function(){if(!confirm('?'))return;api('/api/ips',{method:'POST',body:{ips:[],replace:true}}).then(function(){return load()})});
$('#btnIpCopy').addEventListener('click',function(){copy(CFG.settings.ips.join('\\n'))});
$('#btnBrowserTest').addEventListener('click',function(){var box=$('#scanRes');var targets=CFG.settings.ips.concat(CFG.settings.useDefaults?CFG.defaults.addresses:[]).filter(function(a){return !/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(a)&&a.indexOf(':')<0});
 box.innerHTML='<div class="note w small" style="margin-bottom:8px">'+t('browser_note')+'</div>';var rows={};targets.forEach(function(h){var d=document.createElement('div');d.innerHTML='<span class="mono">'+esc(h)+'</span><span class="dim">…</span>';box.appendChild(d);rows[h]=d.lastChild});
 var i=0;function next(){if(i>=targets.length)return;var h=targets[i++];var t0=performance.now();var ctl=('AbortController' in window)?new AbortController():null;var timer=setTimeout(function(){if(ctl)ctl.abort()},4000);
  fetch('https://'+h+'/cdn-cgi/trace?'+Date.now(),{mode:'no-cors',cache:'no-store',signal:ctl?ctl.signal:undefined}).then(function(){var ms=Math.round(performance.now()-t0);rows[h].innerHTML='<span style="color:'+(ms<400?'var(--green)':ms<900?'var(--amber)':'var(--red)')+'">'+ms+' ms</span>'},function(){rows[h].innerHTML='<span style="color:var(--red)">✗</span>'}).then(function(){clearTimeout(timer);next()})}
 next();next();next()});

/* ---------- backup ---------- */
$('#restoreFile').addEventListener('change',function(){var f=this.files[0];if(!f)return;var r=new FileReader();r.onload=function(){try{var j=JSON.parse(r.result);if(!j.settings&&!j.users)throw 0;api('/api/backup',{method:'POST',body:{settings:j.settings,users:j.users}}).then(function(){$('#restoreState').textContent=t('restore_ok');return load()})}catch(e){$('#restoreState').textContent=t('restore_bad')}};r.readAsText(f)});

/* ---------- update / lang ---------- */
$('#btnUpdate').addEventListener('click',function(){show('about');var b=$('#updateBox');b.textContent=t('update_check');api('/api/update-check').then(function(j){if(!j.ok||!j.latest){b.textContent='?';return}
 b.innerHTML=j.latest===j.current?'<span class="chip ok">✓ '+t('update_ok')+' ('+esc(j.current)+')</span>':'<span class="chip warn">⬆️ '+t('update_new')+esc(j.latest)+'</span><div class="small mute" style="margin-top:6px">'+t('update_how')+'</div>'})});
$('#btnLang').addEventListener('click',function(){var next=lang==='fa'?'en':'fa';api('/api/settings',{method:'PUT',body:{lang:next}}).then(function(){location.reload()})});

applyI18n();
var h=(location.hash||'#dash').slice(1);if(['dash','scan','settings','backup','about'].indexOf(h)<0)h='dash';show(h);
load().catch(function(){toast('load error',true)});
})();
</script></body></html>`;
}


/* ------------------------------------------------------------------ */
/* entry                                                               */
/* ------------------------------------------------------------------ */

export default {
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
  splitCsv, uniq, isUuid, b64encode, b64decode, sha256Hex, hmacHex,
  deriveUuid, resolveUuid,
  KV_KEYS, kvBinding, kvCacheClear, KV_READ_TTL_MS,
  defaultSettings, normalizeSettings, readSettings, writeSettings,
  normalizeUser, readUsers, writeUsers, userBlockedReason, findUser,
  panelPassword, panelIsOpen, makeSession, verifySession, isOwner, checkLogin,
  qrEncode, qrSvg,
  decodeEarlyData, websocketReadable, safeCloseWs, parseSocksAddress, parseVlessHeader, trojanPassword, parseTrojanRequest,
  sha224Hex, trojanHash, isCloudflareIp, CF_CIDR_RANGES,
  __setSockets, loadSockets, splitHostPort, proxyIpList, parseChain, dialViaChain, socks5Handshake, httpConnectHandshake, subQuery, DEFAULT_PROXY_IPS, normalizeCountry, splitAddrTag, flagOf, countryLabel, countrySummary, countryGroups, countryOfAddr, dialTarget, pumpTunnel, tunnelAuth, handleTunnelConnection, tunnelPaths, isTunnelPath,
  effectiveSni, addressList, buildConfigEntries, vlessLink, trojanLink, linkContext, buildClashYaml, buildSingboxConfig, subscriptionHeaders,
  TLS_PORTS, PLAIN_PORTS, DEFAULT_CLEAN_ADDRESSES, SCAN_RANGES,
  handleRequest, handleApi, selfInfo, geoLookup,
  loginPage, panelPage, userInfoPage,
};
