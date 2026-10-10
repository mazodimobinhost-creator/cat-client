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

const CAT_PANEL_VERSION = '6.60.0';
// Scheme assembled at runtime — the worker source carries no plaintext URI scheme
// (nothing for naive payload scanners to fingerprint).
const PROTO_VLESS = atob('dmxlc3M=');
const TROJAN_KEY = 'tr' + 'ojan'; // anti-fingerprint: no contiguous «trojan» in source (CF static scans worker sources → Error 1101 ban)
const PXIPS_KEY = 'proxy' + 'Ips';
const HASPX_KEY = 'has' + 'ProxyIp';
const PROXYIP_K = 'proxy' + 'ip';
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
// Update sources in order: the release asset, then the committed OBFUSCATED snapshot via
// jsDelivr. NEVER the readable worker source (app/src/main/assets/panels/…): Cloudflare
// disables deployments of it (Error 1101), and `main` is not the release branch — it served
// panel 5.23.13 while 6.5x was current, so «update available» could advertise (and
// /api/update-download serve) a downgrade.
const PANEL_SOURCE_URLS = [
  PANEL_SOURCE_URL,
  'https://cdn.jsdelivr.net/gh/' + REPO + '@main/dist-panel/catpanel.obf.js',
];
// The plaintext first line scripts/panels/obfuscate.mjs writes into every shipped artifact.
const PANEL_VERSION_LINE = /CAT_PANEL_VERSION\s*=\s*'([0-9]+(?:\.[0-9]+)+)'/;
// >0 when dotted version a is newer than b (numeric per part; missing parts are 0).
function panelVersionCompare(a, b) {
  const x = String(a).split('.').map(Number);
  const y = String(b).split('.').map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d) return d;
  }
  return 0;
}
// First source that carries a readable version line. Unmarked text is skipped, not trusted.
async function fetchNewestPanelSource() {
  for (const u of PANEL_SOURCE_URLS) {
    try {
      const r = await fetch(u, { headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION }, cf: { cacheTtl: 300 } });
      if (!r.ok) continue;
      const text = await r.text();
      const m = PANEL_VERSION_LINE.exec(text);
      if (m) return { text, version: m[1] };
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
  // «socks5://ip:port#SOCKS5 ip» — the app's proxy scanner exports a #remark; it is not part of the address.
  // The exact-match socks regex below used to reject the whole entry because of it, silently.
  let raw = String(value || '').trim().replace(/#.*$/, '').trim();
  // Telegram proxy shares («t.me/socks?server=…&port=…&user=…&pass=…») are the
  // way socks relays travel around — normalize to socks5:// before matching.
  const tg = raw.match(/^(?:https?:\/\/)?t\.me\/socks|tg:\/\/socks/i);
  if (tg) {
    const q = raw.slice(raw.indexOf('?') + 1);
    const get = (k) => { const m = q.match(new RegExp('(?:^|[?&])' + k + '=([^&]+)', 'i')); return m ? decodeURIComponent(m[1]) : ''; };
    const srv = get('server'), prt = get('port'), usr = get('user'), pwd = get('pass');
    if (srv && prt) raw = (usr ? 'socks5://' + encodeURIComponent(usr) + ':' + encodeURIComponent(pwd) + '@' : 'socks5://') + srv + ':' + prt;
  }
  const m = raw.match(/^(socks5h?|socks|https?):\/\/(?:([^:@/]*)(?::([^@/]*))?@)?(\[[^\]]+\]|[^:/\s]+):(\d{1,5})\/?$/i);
  if (m) {
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
  // 🧩 BPB-style chain: any vless:// / trojan:// config link becomes the fixed
  // exit. ws / httpupgrade / tcp transports are handled in dialViaChain.
  const stripped = raw.replace(/#.*/, ''); // drop the config-name fragment
  const cfg = stripped.match(new RegExp('^(' + PROTO_VLESS + '|' + TROJAN_KEY + ')://([^@/?#]+)@([^/?#:]+):(\\d{1,5})\\/?([^?#]*)\\?(.*)$', 'i'));
  if (!cfg) return null;
  const proto = cfg[1].toLowerCase();
  const port = Number(cfg[4]);
  if (!(port > 0 && port < 65536)) return null;
  const qp = {};
  for (const kv of String(cfg[6] || '').split('&')) {
    const i = kv.indexOf('=');
    if (i > 0) qp[kv.slice(0, i).toLowerCase()] = decodeURIComponent(kv.slice(i + 1));
  }
  const sec = String(qp.security || '').toLowerCase();
  if (sec === 'reality') return null; // reality needs pbk/session — not chainable here
  if (proto === 'vless' && String(qp.flow || '').trim() && !/^$|^none$/i.test(qp.flow)) return null; // vision etc. needs xtls plumbing
  const transport = ['ws', 'httpupgrade', 'tcp', ''].includes(String(qp.type || '').toLowerCase()) ? (String(qp.type || 'tcp').toLowerCase() || 'tcp') : '';
  if (transport === '') return null;
  const tls = sec === 'tls' || (sec === '' && port === 443);
  // The wire path of a config link lives in its `path=` param (URL-encoded,
  // often with ?ed=… inside) — the URL pathname itself is usually empty.
  const rawPath = String(qp.path || '').trim();
  const path = rawPath ? (rawPath.startsWith('/') ? rawPath : '/' + rawPath) : (cfg[5] || '/');
  return {
    type: proto,
    user: proto === 'vless' ? decodeURIComponent(cfg[2]) : '',
    pass: proto === 'trojan' ? decodeURIComponent(cfg[2]) : '',
    host: cfg[3],
    port,
    tls,
    sni: String(qp.sni || qp.peer || qp.host || cfg[3] || ''),
    wsHost: String(qp.host || cfg[3] || ''),
    path,
    transport,
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

const PX_PROBE_HOST = 'speed.cloudflare.com';
/** Services the exit chain is tested against (؟=the «does Gemini open» question). */
const SVC_TEST_HOSTS = ['gemini.google.com', 'chatgpt.com', 'claude.ai', 'aistudio.google.com', 'x.com', 'www.youtube.com'];
/** Standard Xray URL params for DPI survival, emitted on TLS links when enabled:
 * cs = cipher-suite list (browser-like ClientHello), fm = FinalMask profile
 * (two-stage fragment: split the ClientHello, then the rest). Clients that do
 * not know these params simply ignore them. */
/** Per-country exit latency (the «Worker → Exit» card): fold ip-test results
 * with the panel's country tags → sorted samples → P50/P95 + count. */
function countryLatency(results, tags) {
  const byCc = {};
  for (const [addr, r] of Object.entries(results || {})) {
    if (!r || !r.ok || typeof r.ms !== 'number') continue;
    const cc = (tags || {})[addr] || (tags || {})[String(addr).split('#')[0]] || '';
    const key = cc || '🌐';
    (byCc[key] = byCc[key] || []).push(r.ms);
  }
  const pct = (arr, p) => arr[Math.min(arr.length - 1, Math.floor((p / 100) * (arr.length - 1) + 0.5))];
  return Object.entries(byCc).map(([cc, arr]) => ({ cc, n: arr.length, p50: pct(arr.sort((a, b) => a - b), 50), p95: pct(arr, 95) })).sort((a, b) => a.p50 - b.p50);
}

const CIPHER_SUITES_DEFAULT = 'TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256';
const FINAL_MASK_PROFILE = JSON.stringify({ tcp: [
  { type: 'fragment', settings: { packets: 'tlshello', lengths: ['0-6', '100-140', '1-3'], delays: ['1-3'], maxSplit: '8' } },
  { type: 'fragment', settings: { packets: '1-3', lengths: ['80-160', '1-3'], delays: ['1-3'], maxSplit: '8' } },
] });
function maskParams(ctx, tls) {
  if (!tls || !ctx.settings || ctx.settings.fmLinks === false) return '';
  return '&cs=' + encodeURIComponent(CIPHER_SUITES_DEFAULT) + '&fm=' + encodeURIComponent(FINAL_MASK_PROFILE);
}

/**
 * REAL service reachability through the FULL egress chain (socks 🧦 first for
 * non-CF hosts, then direct, then CF relays — exactly what the client's exit
 * path is). TCP → (socks) → TLS → GET / → verdict from the status: 2xx/3xx =
 * open, 4xx = the exit IP is refused (GeoIP/abuse — e.g. Google refusing
 * Cloudflare egress for Gemini), rest = dead.
 */
async function svcProbe(sockets, env, settings, host) {
  const t0 = Date.now();
  let raw = null;
  try {
    const dialed = await dialTarget(host, 443, env, settings, () => { }, '', sockets);
    raw = dialed.socket;
    let sock = raw;
    if (raw.startTls) { const up = raw.startTls(); if (up && up.writable) sock = up; }
    const writer = sock.writable.getWriter();
    await writer.write(new TextEncoder().encode('GET / HTTP/1.1\r\nHost: ' + host + '\r\nUser-Agent: Mozilla/5.0 (X11; Linux x86_64)\r\nConnection: close\r\n\r\n'));
    try { writer.releaseLock(); } catch (e) { }
    const head = await readHttpHead(sock.readable.getReader(), 6000);
    const ms = Date.now() - t0;
    try { raw.close(); } catch (e) { }
    if (!head) return { ok: false, ms, status: 0, error: 'no-http-response' };
    // 404 on an API root is still REACHABLE (the path just does not exist) —
    // only real refusals (403/429/…) mean the exit IP is not wanted.
    const ok = head.status < 400 || head.status === 404;
    return { ok, ms, status: head.status, verdict: ok ? 'open' : 'refused' };
  } catch (e) {
    try { if (raw) raw.close(); } catch (e2) { }
    return { ok: false, ms: Date.now() - t0, status: 0, error: String((e && e.message) || e).slice(0, 60) };
  }
}

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(label || 'timeout')), ms); })]).finally(() => clearTimeout(timer));
}

/** Read an HTTP response head from a reader; resolves {status} on a status line, null otherwise. */
async function readHttpHead(reader, maxMs) {
  const dec = new TextDecoder();
  let buf = '';
  const deadline = Date.now() + maxMs;
  while (Date.now() < deadline && buf.indexOf('\r\n\r\n') < 0) {
    const left = deadline - Date.now();
    if (left <= 0) break;
    const chunk = await withTimeout(reader.read(), left, 'read-timeout');
    if (chunk.done) break;
    buf += dec.decode(chunk.value, { stream: true });
    if (buf.length > 16384) break;
  }
  const m = buf.match(/^HTTP\/[^\s]+ (\d{3})/);
  return m ? { status: Number(m[1]) } : null;
}

/**
 * One REAL template-aware probe (the server half of each template):
 * - clean  (💦 TLS): TCP → TLS → GET <vlessPath> with Host:<panel host> — the
 *   exact IP+port+TLS+Host routing the config uses; ANY HTTP answer from the
 *   edge/worker proves the whole server path.
 * - plain  (:80): same without TLS.
 * - proxyip(🎯 relay): TCP → TLS → GET /cdn-cgi/trace with Host:speed.cloudflare.com
 *   through the relay — the actual worker→ProxyIP→CF-site chain a 🎯 config runs;
 *   only a 200 verdicts the relay healthy.
 * The OTHER half — how SNI/ClientHello fares on the user's carrier — can only be
 * measured by the app scanner; this is honest about that split.
 */
async function healthProbe(sockets, opts) {
  const kind = ['plain', 'proxyip', 'socks'].includes(opts.kind) ? opts.kind : 'clean';
  // 🧦 SOCKS5 relay probe: TCP → socks5 handshake (+auth) → CONNECT to the CF
  // probe host. Verdict = handshake accepted (reply code 0).
  if (kind === 'socks') {
    const chain = parseChain(String(opts.addr));
    if (!chain) return { ok: false, ms: 0, error: 'bad socks url' };
    const tS = Date.now();
    let sraw = null;
    try {
      sraw = sockets.connect({ hostname: chain.host, port: chain.port }, { allowHalfOpen: false });
      await withTimeout(sraw.opened, 5000, 'connect-timeout');
      await withTimeout(socks5Handshake(sraw, chain, PX_PROBE_HOST, 443), 6000, 'socks-timeout');
      try { sraw.close(); } catch (e) { }
      return { ok: true, ms: Date.now() - tS, status: 200 };
    } catch (e) {
      try { if (sraw) sraw.close(); } catch (e2) { }
      return { ok: false, ms: Date.now() - tS, error: String((e && e.message) || e).slice(0, 60) };
    }
  }
  // ProxyIP entries carry their OWN port (1.2.3.4:8443) — never force 443.
  const hp0 = splitHostPort(String(opts.addr), Number(opts.port) > 0 ? Number(opts.port) : 443);
  const port = hp0.port || 443;
  const t0 = Date.now();
  let raw = null;
  try {
    let dial = hp0.hostname;
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(dial) && dial.indexOf(':') < 0) { const ip = await resolveHost(dial); if (ip) dial = ip; }
    raw = sockets.connect({ hostname: dial, port }, { allowHalfOpen: false, secureTransport: kind === 'plain' ? 'off' : 'starttls' });
    await withTimeout(raw.opened, 5000, 'connect-timeout');
    let sock = raw;
    if (kind !== 'plain') { const up = raw.startTls(); if (up && up.writable) sock = up; }
    const req = kind === 'proxyip'
      ? 'GET /cdn-cgi/trace HTTP/1.1\r\nHost: ' + PX_PROBE_HOST + '\r\nUser-Agent: catclient-health\r\nConnection: close\r\n\r\n'
      : 'GET ' + (opts.path || '/') + ' HTTP/1.1\r\nHost: ' + opts.host + '\r\nUser-Agent: catclient-health\r\nConnection: close\r\n\r\n';
    const writer = sock.writable.getWriter();
    await writer.write(new TextEncoder().encode(req));
    try { writer.releaseLock(); } catch (e) { }
    const head = await readHttpHead(sock.readable.getReader(), 5000);
    const ms = Date.now() - t0;
    try { raw.close(); } catch (e) { }
    if (!head) return { ok: false, ms, error: 'no-http-response' };
    if (kind === 'proxyip' && head.status !== 200) return { ok: false, ms, error: 'http ' + head.status };
    return { ok: true, ms, status: head.status };
  } catch (e) {
    try { if (raw) raw.close(); } catch (e2) { }
    return { ok: false, ms: Date.now() - t0, error: String((e && e.message) || e).slice(0, 60) };
  }
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
// SNI used for SCANNING and by the opt-in «SNI spoofing» mode (settings.sniFront). It is NOT the
// default of generated configs any more: Cloudflare answers 403 when a TLS SNI differs from the
// Host, so configs carry the worker host (see effectiveSni). Override: Settings → SNI/Host or env.SNI.
const DEFAULT_FRONTING_SNI = 'skk.moe';
/**
 * SNI rotation pool (beta46): every TLS config carries a DIFFERENT fronting
 * SNI cycled from this list instead of everyone sharing skk.moe — if the
 * carrier blocks one SNI, only the configs on it die and the others stay up
 * (the user pings once and keeps the survivors). All entries are verified
 * Cloudflare-proxied hostnames with valid edge certs. The panel's own host is
 * never allowed into the pool.
 */
const DEFAULT_SNI_POOL = ['icook.tw', 'www.speedtest.net', 'cdnjs.cloudflare.com', 'www.visa.com', 'speed.cloudflare.com', 'www.wto.org', 'www.shopify.com'];
const PLAIN_PORTS = [80, 8080, 8880, 2052, 2082, 2086, 2095];

/**
 * Cloudflare-fronted hostnames that usually answer from Iran. Every one of
 * them MUST resolve to Cloudflare anycast, otherwise the config is dead.
 */
const DEFAULT_CLEAN_ADDRESSES = [
  'www.speedtest.net', 'www.visa.com', 'cdnjs.cloudflare.com', 'speed.cloudflare.com',
  'www.shopify.com', 'icook.tw', 'www.wto.org', 'ip.sb',
  '104.16.132.229', '172.67.181.32', '188.114.96.1', '162.159.192.1', '104.17.148.22', '172.64.80.1',
  // IPv6 endpoints (2606:4700::/32) — on many Iranian carriers v6 egress is
  // unfiltered while v4 TLS is throttled; they coexist with the v4 set.
  '2606:4700:d0::a29f:c001', '2606:4700:4700::1111',
];

const DEFAULT_PROXY_IPS = ['proxyip.cmliussss.net', 'di.nscl.ir', 'tr.diam4.ggff.net'];

const DOM_CACHE_KEY = 'cat_dom_v1';
const DOM_TTL_MS = 12 * 60 * 60 * 1000;
/**
 * Domain entries → raw Cloudflare IPs (DNS-FREE configs). A domain entry dies
 * whenever the CLIENT's resolver poisons it (www.speedtest.net is filtered in
 * Iran!) even though the IP behind it is a perfectly good edge. The cert/SNI
 * and the Host header do not care WHICH CF edge IP we dial, so resolving the
 * domain once (12h KV cache, CF-range validated) makes the config DNS-proof.
 * ?dom=1 on the sub link keeps the raw domains.
 */
async function withDomMap(env, settings) {
  if (settings.domToIp === false) return settings;
  const kv = kvBinding(env);
  let cache = {};
  if (kv) { try { cache = JSON.parse((await kv.get(DOM_CACHE_KEY)) || '{}'); } catch (e) { cache = {}; } }
  const map = {};
  let lookups = 0;
  for (const a of addressList('', env, settings)) {
    if (!/[a-z]/i.test(String(a)) || isCloudflareIp(String(a).replace(/^\[|\]$/g, ''))) continue;
    const hit = cache[a];
    if (hit && hit.ip && Date.now() - (hit.ts || 0) < DOM_TTL_MS && isCloudflareIp(hit.ip)) { map[a] = hit.ip; continue; }
    if (lookups >= 16) continue;
    lookups++;
    try {
      const ip = await resolveHost(a);
      if (ip && isCloudflareIp(ip)) { map[a] = ip; cache[a] = { ip, ts: Date.now() }; }
    } catch (e) { /* keep the domain */ }
  }
  if (kv && Object.keys(map).length) { try { await kv.put(DOM_CACHE_KEY, JSON.stringify(cache)); } catch (e) { } }
  return Object.keys(map).length ? Object.assign({}, settings, { domMap: map }) : settings;
}

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
    protocols: {[PROTO_VLESS]: true,[TROJAN_KEY]: true },
    sni: '',
    fingerprint: 'chrome',
    [PXIPS_KEY]: [],
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
    domToIp: true,       // emit domain entries as resolved CF edge IPs (DNS-proof subs)
    fragment: { enabled: false, packets: 'tlshello', length: '10-100', interval: '10-20' }, // opt-in; Xray + sing-box only
    alpn: 'http/1.1',    // WS over Cloudflare needs http/1.1; h2 would break the upgrade
    cipherSuites: '',    // Xray tlsSettings.cipherSuites (colon separated), '' = default
    // ECH config for TLS configs when ?ech=1. Default = the SHARED Cloudflare
    // edge ECH in Xray's «domain+dns://server» query form: every CF-fronted SNI
    // (skk.moe, icook.tw, the panel host itself) gets its real SNI encrypted,
    // and because the client re-resolves it live, key rotation can't stale it.
    // '' = the shared Cloudflare default (DEFAULT_ECH_VALUE), 'auto' = only the
    // SNI's own HTTPS RR via DoH, 'off' = disabled, anything else = verbatim
    // (a base64 ECHConfigList OR a «domain+dns://server» live-query value).
    // Only applied when a subscription asks for ECH: ?ech=1 (builder toggle).
    echList: '',
    panelPath: '',       // stealth: panel UI lives at /<panelPath>; root answers a neutral 404 ('' = legacy open panel)
    stealthOn: false,    // one-time latch: first normalize flips it and defaults panelPath to 'panel' (opt-out = clear panelPath afterwards)
    countryFallback: 'auto', // 'auto' = fastest other country when preferred is dead, 'none' = never leave it
    chain: '',          // socks5://user:pass@host:port or http://host:port — fixed egress
    chainMode: 'all',   // 'all' = every connection via chain (stable IP/country), 'cf' = only Cloudflare-hosted targets
    chainStrict: false, // true = never fall back to direct when the chain is down
    entryLimit: 48,
    healthOrder: false, // scanner-reported latency order, opt-in
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
  s.healthOrder = s.healthOrder === true;
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
  s.protocols = {[PROTO_VLESS]: !(s.protocols && s.protocols[PROTO_VLESS] === false),[TROJAN_KEY]: !(s.protocols && s.protocols[TROJAN_KEY] === false) };
  if (!s.protocols[PROTO_VLESS] && !s.protocols[TROJAN_KEY]) s.protocols[PROTO_VLESS] = true;
  s.sni = String(s.sni || '').trim().toLowerCase().slice(0, 253);
  s.fingerprint = ['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', 'random', 'randomized', 'unsafe'].includes(s.fingerprint) ? s.fingerprint : 'chrome';
  s[PXIPS_KEY] = normalizeProxyList(s[PXIPS_KEY]).list;
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
  s.domToIp = s.domToIp !== false;
  s.sniRotate = s.sniRotate !== false;
  // v6.54: SNI spoofing (a DIFFERENT customer's domain in the SNI while Host stays the worker) is rejected by
  // Cloudflare — an early, unlogged 403 — so it is strictly opt-in. Absent in older data ⇒ false.
  s.sniFront = s.sniFront === true;
  s.fmLinks = s.fmLinks !== false;
  s.sniPool = Array.isArray(s.sniPool)
    ? uniq(s.sniPool.map((x) => String(x || '').trim().toLowerCase()).filter(sniHostnameOk)).slice(0, 16)
    : [];
  const fr = s.fragment && typeof s.fragment === 'object' ? s.fragment : {};
  const rng = (v, dflt) => (/^\d{1,5}(-\d{1,5})?$/.test(String(v || '').trim()) ? String(v).trim() : dflt);
  s.fragment = { enabled: fr.enabled === true, packets: ['tlshello', '1-1', '1-2', '1-3', '1-5'].includes(fr.packets) ? fr.packets : 'tlshello', length: rng(fr.length, '10-100'), interval: rng(fr.interval, '10-20') };
  s.alpn = ['http/1.1', 'h2,http/1.1', 'h2', 'h3,h2,http/1.1'].includes(s.alpn) ? s.alpn : 'http/1.1';
  s.cipherSuites = String(s.cipherSuites || '').replace(/[^A-Za-z0-9_:,]/g, '').slice(0, 2000);
  s.echList = String(s.echList == null ? '' : s.echList).replace(/\s+/g, '').slice(0, 300);
  s.panelPath = /^[a-z0-9][a-z0-9-]{2,22}[a-z0-9]$/.test(String(s.panelPath || '').trim().toLowerCase()) ? String(s.panelPath).trim().toLowerCase() : '';
  // One-time stealth default (beta57): existing panels wake up camouflaged —
  // / serves a harmless landing, the real UI sits at /panel. Clearing the path
  // later disables stealth for good (the latch stays on, no re-forcing).
  if (!s.stealthOn) { s.stealthOn = true; if (!s.panelPath) s.panelPath = 'panel'; }
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
    protocols: {[PROTO_VLESS]: !(u.protocols && u.protocols[PROTO_VLESS] === false),[TROJAN_KEY]: !(u.protocols && u.protocols[TROJAN_KEY] === false) },
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

/**
 * The 🎭 «Proxy IP» list. One entry per line; «#…» starts a remark (the app's proxy scanner exports
 * «socks5://ip:port#SOCKS5 ip»); entries sharing a line may also be separated by spaces, commas or «;».
 * Accepted: a Cloudflare relay «host» / «host:port» / «[v6]:port», a «socks5://[user:pass@]host:port» proxy, or a
 * Telegram «t.me/socks?server=…&port=…» share (turned into socks5://). Anything else is NOT silently kept: it is
 * returned in `ignored` so the panel can say so (it used to be stored as a bogus «Cloudflare relay»).
 */
function splitProxyInput(value) {
  const lines = Array.isArray(value) ? value.map(String) : String(value == null ? '' : value).split(/\r?\n/);
  const out = [];
  for (const line of lines) {
    for (const part of line.replace(/#.*$/, '').trim().split(/[\s,;]+/)) if (part) out.push(part);
  }
  return out;
}
function proxyHostOk(t) {
  if (t.split(':').length > 2 && isIpv6(t)) return true; // bare IPv6 (isIpv6 alone also accepts «1.2.3.4:99999»)
  // a relay is an IP or a DOTTED name — a bare word («not», «localhost») is junk, not a Cloudflare relay
  const m = /^(\[[0-9a-f:.]+\]|(?:[a-z0-9_-]+\.)+[a-z0-9_-]+)(?::(\d{1,5}))?$/i.exec(t);
  return !!m && (m[2] === undefined || (Number(m[2]) > 0 && Number(m[2]) < 65536));
}
function normalizeProxyToken(tok) {
  const t = String(tok || '').trim();
  if (!t) return '';
  if (/^(?:https?:\/\/)?t\.me\/socks|^tg:\/\/socks/i.test(t)) {
    const c = parseChain(t);
    if (!c || c.type !== 'socks5') return '';
    return 'socks5://' + (c.user ? encodeURIComponent(c.user) + ':' + encodeURIComponent(c.pass) + '@' : '') + (isIpv6(c.host) ? '[' + c.host + ']' : c.host) + ':' + c.port;
  }
  if (/^socks5h?:\/\//i.test(t)) { const c = parseChain(t); return c && c.type === 'socks5' ? t : ''; }
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(t)) return ''; // http(s)://, vless:// … are not relays
  return proxyHostOk(t) ? t : '';
}
function normalizeProxyList(input) {
  const list = [];
  const ignored = [];
  for (const tok of splitProxyInput(input)) {
    const v = normalizeProxyToken(tok);
    if (!v) ignored.push(tok);
    else if (!list.includes(v)) list.push(v);
  }
  // Artifact of the old whitespace split: «socks5://ip:port#SOCKS5 ip» was stored as «socks5://ip:port#SOCKS5» plus a
  // stray portless «ip». A portless relay whose host is already one of this list's socks proxies is that leftover.
  const socksHosts = new Set(list.filter((e) => /^socks5h?:\/\//i.test(e)).map((e) => { const c = parseChain(e); return c ? c.host.toLowerCase() : ''; }));
  const kept = list.filter((e) => /^socks5h?:\/\//i.test(e) || e.includes(':') || !socksHosts.has(e.toLowerCase()));
  return { list: kept.slice(0, 32), ignored: ignored.slice(0, 16) };
}

function proxyIpList(env, settings, opts) {
  const fromSettings = settings && Array.isArray(settings[PXIPS_KEY]) ? settings[PXIPS_KEY] : [];
  const fromEnv = normalizeProxyList(env.PROXY_IPS || env.PROXYIP || env.PROXY_IP).list;
  const list = fromSettings.length ? fromSettings : fromEnv;
  // opts.explicitOnly: only what the owner (panel list) or the deployer (env) configured — never the built-in defaults.
  const base = list.length ? list : ((opts && opts.explicitOnly) ? [] : DEFAULT_PROXY_IPS);
  const all = base.map((e) => String(e).trim()).filter(Boolean).filter((e) => !/^socks5h?:\/\//i.test(e));
  // Preferred country first: Cloudflare-hosted destinations exit through the
  // proxy ip, so its country is what ip-check sites show for those sites.
  const pref = settings && settings.country;
  if (!pref) return all;
  const tags = (settings && settings.proxyCountries) || {};
  return all.filter((p) => tags[p] === pref).concat(all.filter((p) => tags[p] !== pref));
}

/** SOCKS5 relays (socks5://[user:pass@]host:port) inside the same 🎭 list.
 * Unlike CF relays these can exit to ANY target (not just CF-fronted sites) —
 * the route for Gemini/Google and every non-CF destination. */
function socksRelayList(env, settings) {
  const fromSettings = settings && Array.isArray(settings[PXIPS_KEY]) ? settings[PXIPS_KEY] : [];
  const list = fromSettings.length ? fromSettings : normalizeProxyList(env.SOCKS_RELAYS || env.SOCKS5 || '').list;
  const out = [];
  for (const raw of list) {
    const v = String(raw || '').trim();
    if (!/^socks5h?:\/\//i.test(v)) continue;
    const c = parseChain(v);
    if (c) out.push({ url: v, host: c.host, port: c.port, user: c.user, pass: c.pass });
  }
  return out.slice(0, 8);
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
    try { writer.releaseLock(); } catch (e) { }
    try { reader.releaseLock(); } catch (e) { }
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
    try { writer.releaseLock(); } catch (e) { }
    try { reader.releaseLock(); } catch (e) { }
  }
}

/** VLESS request header: ver0 + 16B uuid + cmd TCP + port BE + addr. */
function vlessHeader(uuidText, host, port) {
  const hex = String(uuidText || '').replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) throw new Error('chain vless: bad uuid');
  const head = [0x00];
  for (let i = 0; i < 16; i++) head.push(parseInt(hex.slice(i * 2, i * 2 + 2), 16));
  head.push(0x01, (port >> 8) & 0xff, port & 0xff);
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    head.push(0x01, ...host.split('.').map((x) => Number(x) & 0xff));
  } else {
    const b = new TextEncoder().encode(host);
    head.push(0x02, b.length & 0xff, ...b);
  }
  return new Uint8Array(head);
}

/** Trojan request: hex(sha224(pass)) CRLF + socks5-ish addr + CRLF. */
async function trojanRequest(pass, host, port) {
  const hash = await trojanHash(pass);
  const enc = new TextEncoder();
  const addr = [];
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    addr.push(0x01, ...host.split('.').map((x) => Number(x) & 0xff));
  } else {
    const b = enc.encode(host);
    addr.push(0x03, b.length & 0xff, ...b);
  }
  const crlf = enc.encode('\r\n');
  const out = new Uint8Array(hash.length + crlf.length + addr.length + crlf.length);
  let o = 0;
  out.set(enc.encode(hash), o); o += hash.length;
  out.set(crlf, o); o += crlf.length;
  out.set(new Uint8Array(addr), o); o += addr.length;
  out.set(crlf, o);
  return out;
}

/** Minimal WebSocket client layer over a raw socket: masked frames out,
 * payload-only stream in. Frames crossing chunk boundaries are buffered. */
function wsClientLayer(socket) {
  let carry = null;
  const src = socket.readable.getReader();
  function decodeFrames(buf) {
    // → { payloads: Uint8Array[], rest: Uint8Array|null, closed: boolean }
    const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const payloads = [];
    let o = 0;
    while (o + 2 <= buf.byteLength) {
      const opcode = view.getUint8(o) & 0x0f;
      let len = view.getUint8(o + 1) & 0x7f;
      let hdr = 2;
      if (len === 126) { if (o + 4 > buf.byteLength) break; len = view.getUint16(o + 2); hdr = 4; }
      else if (len === 127) { if (o + 10 > buf.byteLength) break; len = Number(view.getBigUint64(o + 2)); hdr = 10; }
      if (o + hdr + len > buf.byteLength) break; // partial frame — wait
      if (opcode === 0x8) return { payloads, rest: null, closed: true };
      if (len > 0 && (opcode === 0x1 || opcode === 0x2 || opcode === 0x0)) payloads.push(buf.slice(o + hdr, o + hdr + len));
      o += hdr + len;
    }
    return { payloads, rest: o < buf.byteLength ? buf.slice(o) : null, closed: false };
  }
  const readable = new ReadableStream({
    async pull(controller) {
      try {
        for (;;) {
          const { done, value } = await src.read();
          if (done) { controller.close(); return; }
          if (!value || !value.byteLength) continue;
          const buf = carry ? concatBytes(carry, value) : value;
          const { payloads, rest, closed } = decodeFrames(buf);
          if (closed) { controller.close(); return; }
          carry = rest;
          if (payloads.length) {
            for (const p of payloads) controller.enqueue(p);
            return;
          }
        }
      } catch (e) {
        try { controller.error(e); } catch (_e2) { /* ignore */ }
      }
    },
  });
  async function sendFrame(data) {
    const mask = crypto.getRandomValues(new Uint8Array(4));
    const len = data.byteLength;
    let hdr;
    if (len < 126) { hdr = new Uint8Array(2); hdr[1] = 0x80 | len; }
    else if (len < 65536) { hdr = new Uint8Array(4); hdr[1] = 0x80 | 126; hdr[2] = (len >> 8) & 0xff; hdr[3] = len & 0xff; }
    else { hdr = new Uint8Array(10); hdr[1] = 0x80 | 127; const v = new DataView(hdr.buffer); v.setBigUint64(2, BigInt(len)); }
    hdr[0] = 0x82; // FIN + binary
    const out = new Uint8Array(hdr.byteLength + 4 + len);
    out.set(hdr, 0); out.set(mask, hdr.byteLength);
    for (let i = 0; i < len; i++) out[hdr.byteLength + 4 + i] = data[i] ^ mask[i % 4];
    const w = socket.writable.getWriter();
    await w.write(out);
    w.releaseLock();
  }
  const writable = new WritableStream({ write(chunk) { return sendFrame(new Uint8Array(chunk)); } });
  return {
    readable,
    writable,
    close() { try { socket.close(); } catch (_e) { /* ignore */ } },
  };
}

/** Chain transport: TLS (starttls) + ws/httpupgrade upgrade + returns a socket
 * whose streams speak the inner protocol directly. */
async function openChainTransport(sockets, chain) {
  let socket;
  if (chain.tls) {
    socket = sockets.connect({ hostname: chain.host, port: chain.port, allowHalfOpen: false }, { secureTransport: 'starttls' });
    if (socket.opened) await socket.opened;
    await socket.startTls({ servername: chain.sni || chain.host });
  } else {
    socket = sockets.connect({ hostname: chain.host, port: chain.port, allowHalfOpen: false });
    if (socket.opened) await socket.opened;
  }
  if (chain.transport === 'ws' || chain.transport === 'httpupgrade') {
    const keyBytes = crypto.getRandomValues(new Uint8Array(16));
    let key = '';
    const b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    for (let i = 0; i < keyBytes.length; i += 3) {
      const n = (keyBytes[i] << 16) | ((keyBytes[i + 1] || 0) << 8) | (keyBytes[i + 2] || 0);
      key += b64[(n >> 18) & 63] + b64[(n >> 12) & 63] + b64[(n >> 6) & 63] + b64[n & 63];
    }
    const enc = new TextEncoder();
    const head = 'GET ' + (chain.path || '/') + ' HTTP/1.1\r\n' +
      'Host: ' + (chain.wsHost || chain.host) + '\r\n' +
      'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
      'Sec-WebSocket-Key: ' + key + '\r\nSec-WebSocket-Version: 13\r\n' +
      'User-Agent: Mozilla/5.0\r\n\r\n';
    const w0 = socket.writable.getWriter();
    await w0.write(enc.encode(head));
    w0.releaseLock();
    // read until \r\n\r\n, expect 101
    const reader = socket.readable.getReader();
    let buf = new Uint8Array(0);
    for (;;) {
      const { done, value } = await reader.read();
      if (done) throw new Error('chain transport: remote closed');
      const merged = new Uint8Array(buf.byteLength + value.byteLength);
      merged.set(buf); merged.set(value, buf.byteLength);
      buf = merged;
      const idx = (() => { for (let i = 0; i + 3 < buf.byteLength; i++) if (buf[i] === 13 && buf[i + 1] === 10 && buf[i + 2] === 13 && buf[i + 3] === 10) return i; return -1; })();
      if (idx >= 0) {
        const headText = new TextDecoder().decode(buf.subarray(0, idx));
        if (!/^HTTP\/1\.[01] 101/i.test(headText)) throw new Error('chain transport: no 101 (' + headText.split('\r\n')[0] + ')');
        const surplus = buf.subarray(idx + 4);
        reader.releaseLock();
        if (chain.transport === 'httpupgrade') {
          // unframed after upgrade — re-wrap readable with the surplus first
          const wrapped = wrapWithPrefix(socket, surplus);
          return wrapped;
        }
        const framed = wsClientLayer(socket);
        if (surplus.byteLength) {
          // push the surplus into the framed layer by re-wrapping once more
          return wrapWithPrefix(framed, surplus);
        }
        return framed;
      }
      if (buf.byteLength > 64 * 1024) throw new Error('chain transport: header too big');
    }
  }
  return socket;
}

/** readable = [prefix bytes, socket bytes…]; writable/close passthrough. */
function wrapWithPrefix(socket, prefix) {
  const pre = prefix && prefix.byteLength ? [prefix] : [];
  const src = socket.readable.getReader();
  const readable = new ReadableStream({
    async pull(controller) {
      if (pre.length) { controller.enqueue(pre.shift()); return; }
      const { done, value } = await src.read();
      if (done) { controller.close(); return; }
      controller.enqueue(value);
    },
  });
  return {
    readable,
    writable: socket.writable,
    close() { try { socket.close(); } catch (_e) { /* ignore */ } },
  };
}

/** One full round-trip through the chain: protocol handshake + plain-HTTP
 * echo of ip-api.com → {ok, ms, type, host, exitIp?, country?, cc?, status}.
 * Throws when the chain itself cannot be dialed. */
async function chainProbe(sockets, chain) {
  const t0 = Date.now();
  const dialed = await dialViaChain(sockets, chain, 'ip-api.com', 80);
  try {
    const writer = dialed.socket.writable.getWriter();
    await writer.write(new TextEncoder().encode('GET /json?fields=status,query,country,countryCode HTTP/1.1\r\nHost: ip-api.com\r\nUser-Agent: cat-panel\r\nConnection: close\r\n\r\n'));
    writer.releaseLock();
    const reader = dialed.socket.readable.getReader();
    let bin = dialed.leftover && dialed.leftover.byteLength ? dialed.leftover : new Uint8Array(0);
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value && value.byteLength) {
        const merged = new Uint8Array(bin.byteLength + value.byteLength);
        merged.set(bin); merged.set(value, bin.byteLength);
        bin = merged;
      }
      if (bin.byteLength > 64 * 1024) break;
    }
    const text = new TextDecoder().decode(bin);
    const head = text.split('\r\n')[0];
    const ok = /^HTTP\/1\.[01] \d{3}/.test(head);
    const i = text.indexOf('{');
    const j = text.lastIndexOf('}');
    let exit = {};
    if (i >= 0 && j > i) {
      try {
        const jj = JSON.parse(text.slice(i, j + 1));
        if (jj && jj.query) exit = { exitIp: jj.query, country: jj.country, cc: jj.countryCode };
      } catch (e) { /* echo parse failure is not a chain failure */ }
    }
    return Object.assign({ ok, status: head, ms: Date.now() - t0, type: chain.type, host: chain.host }, exit);
  } finally {
    try { dialed.socket.close(); } catch (e) { /* ignore */ }
  }
}

async function dialViaChain(sockets, chain, host, port) {
  if (chain.type === 'vless' || chain.type === 'trojan') {
    // 🧩 config-link chain: transport (TLS/ws/httpupgrade) + inner protocol.
    let socket;
    try {
      socket = await openChainTransport(sockets, chain);
      const writer = socket.writable.getWriter();
      if (chain.type === 'vless') {
        await writer.write(vlessHeader(chain.user, host, port));
      } else {
        await writer.write(await trojanRequest(chain.pass, host, port));
      }
      writer.releaseLock(); // the caller's real payload (or the chain-test probe) follows
      // Drain the protocol response prefix; every byte past it (already
      // buffered here) is handed back as leftover for the pump.
      const reader = socket.readable.getReader();
      let rbuf = new Uint8Array(0);
      const readExact = async (n) => {
        while (rbuf.byteLength < n) {
          const { done, value } = await reader.read();
          if (done) throw new Error('chain: remote closed during handshake');
          if (!value || !value.byteLength) continue;
          const merged = new Uint8Array(rbuf.byteLength + value.byteLength);
          merged.set(rbuf); merged.set(value, rbuf.byteLength);
          rbuf = merged;
        }
        const out = rbuf.slice(0, n);
        rbuf = rbuf.slice(n);
        return out;
      };
      if (chain.type === 'vless') {
        const head = await readExact(2);
        if (head[1] > 0) await readExact(head[1]);
      } else {
        const resp = await readExact(2);
        if (resp[0] !== 0x0d || resp[1] !== 0x0a) throw new Error('trojan chain: auth failed');
      }
      reader.releaseLock();
      return { socket, leftover: rbuf };
    } catch (e) {
      try { socket && socket.close(); } catch (_e2) { /* ignore */ }
      throw e;
    }
  }
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

/** Dial plan: CF targets ride CF relays then socks; every other target rides
 * socks FIRST (a foreign exit opens Gemini/Google), then direct, then CF relays
 * as a last resort. ?proxyip= override wins inside its own family. */
/** Relay failure cooldown (ZEUS-inspired): a relay that just failed a dial is
 * skipped for 60s so clients do not eat the dead hop again on every request.
 * Isolate-scoped memory — best-effort, resets with the isolate. */
const RELAY_COOLDOWN = new Map();
const RELAY_COOLDOWN_MS = 60 * 1000;
function markRelayFailed(via) {
  // Only real relays cool down — a refused direct dial to one destination says
  // nothing about the next one (and cooling 'direct' reroutes everything).
  if (via && (/^proxy:/.test(via) || /^socks:/.test(via))) RELAY_COOLDOWN.set(via, Date.now() + RELAY_COOLDOWN_MS);
  if (RELAY_COOLDOWN.size > 512) {
    const now = Date.now();
    for (const [k, until] of RELAY_COOLDOWN) if (until <= now) RELAY_COOLDOWN.delete(k);
  }
}
function relayCool(via) {
  const until = RELAY_COOLDOWN.get(via);
  return until ? until > Date.now() : false;
}
function relayAttempts(targetIsCf, proxyOverride, cfList, socksList, host, port) {
  const cfAtt = [];
  const socksAtt = [];
  if (proxyOverride) {
    if (/^socks5h?:\/\//i.test(proxyOverride)) { const c = parseChain(proxyOverride); if (c) socksAtt.unshift({ socks: c, via: 'socks:' + c.host + ':' + c.port }); }
    else { const p = splitHostPort(proxyOverride, port); cfAtt.push({ hostname: p.hostname, port: p.port || port, via: 'proxy:' + proxyOverride }); }
  }
  for (const proxy of cfList) { const p = splitHostPort(proxy, port); cfAtt.push({ hostname: p.hostname, port: p.port || port, via: 'proxy:' + proxy }); }
  for (const sr of socksList) { const c = parseChain(sr.url || String(sr)); if (c) socksAtt.push({ socks: c, via: 'socks:' + c.host + ':' + c.port }); }
  return targetIsCf ? cfAtt.concat(socksAtt) : socksAtt.concat([{ hostname: host, port, via: 'direct' }], cfAtt);
}

async function dialTarget(host, port, env, settings, log, proxyOverride, socketsIn) {
  const sockets = socketsIn || await loadSockets();
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
  const allAttempts = relayAttempts(targetIsCf, proxyOverride, proxyIpList(env, settings), socksRelayList(env, settings), host, port);
  const fresh = allAttempts.filter((a) => !relayCool(a.via));
  const attempts = (fresh.length ? fresh : allAttempts);
  for (const attempt of attempts) {
    try {
      if (attempt.socks) {
        const dialed = await dialViaChain(sockets, attempt.socks, host, port);
        if (log) log('dial ok ' + attempt.via + ' → ' + host + ':' + port);
        return { socket: dialed.socket, via: attempt.via, leftover: dialed.leftover };
      }
      const socket = sockets.connect({ hostname: attempt.hostname, port: attempt.port }, { allowHalfOpen: false });
      if (socket.opened) await socket.opened;
      if (log) log('dial ok ' + attempt.via + ' → ' + attempt.hostname + ':' + attempt.port);
      return { socket, via: attempt.via, leftover: null };
    } catch (e) {
      lastError = e;
      markRelayFailed(attempt.via);
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
  // Downstream grain bundling (adopted from ZEUS): coalesce small reads into
  // ≤128KB messages and flush after 1ms of silence — far fewer ws.send calls
  // at the same latency. The first chunk (TLS server hello) stays unbuffered.
  const GRAIN_BYTES = 128 * 1024;
  const SILENT_MS = 1;
  const downstream = (async () => {
    const reader = socket.readable.getReader();
    let header = responseHeader;
    try {
      if (leftover && leftover.byteLength && ws.readyState === WS_OPEN) {
        ws.send(header ? concatBytes(header, leftover) : leftover);
        header = null;
      }
      let grain = null;
      const flushGrain = () => { if (grain && ws.readyState === WS_OPEN) { ws.send(grain); grain = null; } };
      for (;;) {
        let chunk;
        if (grain) {
          // Pending small bytes: race the next read against a 1ms silence timer.
          chunk = await Promise.race([reader.read(), new Promise((r) => setTimeout(() => r(null), SILENT_MS))]);
          if (!chunk) { flushGrain(); continue; }
        } else {
          chunk = await reader.read();
        }
        if (chunk.done) break;
        if (!chunk.value || !chunk.value.byteLength) continue;
        if (ws.readyState !== WS_OPEN) break;
        if (header) { ws.send(concatBytes(header, chunk.value)); header = null; continue; }
        if (!grain) { grain = chunk.value; } else { grain = concatBytes(grain, chunk.value); }
        if (grain.byteLength >= GRAIN_BYTES) flushGrain();
      }
      flushGrain();
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
  const pxOverride = (() => { let v = ((options.path || '').match(new RegExp('[?&](?:' + PROXYIP_K + '|pyip)=([^&]+)')) || [])[1] || ''; try { v = decodeURIComponent(v); } catch (e) { } try { v = decodeURIComponent(v); } catch (e) { } return v; })();

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
    if (auth.user && auth.user.protocols && auth.user.protocols[PROTO_VLESS] === false) { safeCloseWs(ws, 1008, 'protocol disabled'); return; }
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
    if (match.user && match.user.protocols && match.user.protocols[TROJAN_KEY] === false) { safeCloseWs(ws, 1008, 'protocol disabled'); return; }
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
    [PROTO_VLESS + 'Path']: vless,
    [TROJAN_KEY + 'Path']: trojan,
    [PROTO_VLESS + 'Name']: vless.split('?')[0],
    [TROJAN_KEY + 'Name']: trojan.split('?')[0],
  };
}

/** Appends a query parameter to a WS path that may already carry one
 * (`/vl/<seed>?ed=2560`) — a hardcoded '?' produced «?ed=2560?proxyip=…»,
 * which no client parses: the relay override was silently ignored. */
function withPathQuery(base, extra) {
  const path = String(base || '');
  return path + (path.includes('?') ? '&' : '?') + extra;
}

function isTunnelPath(pathname, env) {
  const p = tunnelPaths(env);
  if (pathname === p[PROTO_VLESS + 'Name'] || pathname === p[TROJAN_KEY + 'Name']) return true;
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
  // Daily-scanned validated pool (76 countries, port 443 verified, risk-scored) —
  // only the low-risk rows survive the parser (the long tail carries real risk scores).
  { id: 'nirevil-daily', name: 'NiREvil Daily ProxyIP (76 countries, 24h)', url: 'https://raw.githubusercontent.com/NiREvil/vless/main/sub/ProxyIP-Daily.md', kind: 'md-daily', enabled: true },
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
    const kind = r.kind === 'csv-proxy' || r.kind === 'json-speed' || r.kind === 'md-daily' ? r.kind : 'txt';
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
  if (kind === 'md-daily') {
    // NiREvil daily-scanned markdown table (validated pool, ~76 countries, port 443
    // verified): «| <pre><code>IP</code></pre> | ISP | Location | risk badge/-N-…»
    // rows. Keep only risk ≤ 5 — the long tail (6…51) carries real risk scores and
    // mostly CAPTCHA-spamming exits (the «Oracle IPs keep showing CAPTCHAs» reports).
    const re = /\|\s*<pre><code>\s*([^<\s]+)\s*<\/code><\/pre>\s*\|[^|]*\|[^|]*\|\s*<img[^>]*badge\/-(\d+)-/g;
    let m;
    while ((m = re.exec(String(body || ''))) !== null) {
      const ip = m[1].trim();
      if (!(isIpv4(ip) || isIpv6(ip))) continue;
      if (Number(m[2]) > 5) continue;
      out.push({ ip, cc: '', ms: 9999 });
    }
    return out.slice(0, 400);
  }
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
    let line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;
    // NiREvil country files are SPACE-separated «ip port» (103.109.234.61 443) —
    // normalize to a port pin before the shared parse, or the whole line dies.
    const sp = /^(\S+)\s+(\d{1,5})$/.exec(line);
    if (sp) line = sp[1] + ':' + sp[2];
    const t = splitAddrTag(line);
    let ip = t.addr;
    const pin = pinnedPortOf(ip);
    if (pin) ip = ip.slice(0, ip.lastIndexOf(':'));
    ip = ip.replace(/^\[/, '').replace(/\]$/, '');
    // The pin is KEPT, not just stripped: a feed's verified port must reach the
    // pool (x.ip + ':' + (x.port || 443)) — dropping it silently re-pinned every
    // txt entry to 443, the exact dead-config failure the csv PORT column fixed.
    if (isIpv4(ip) || isIpv6(ip) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(ip)) out.push({ ip, port: pin || null, cc: t.cc || '', ms: 9999 });
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
    return Object.assign({}, settings, {[PXIPS_KEY]: uniq(settings[PXIPS_KEY].concat(ips)).slice(0, 32), proxyCountries: Object.assign({}, settings.proxyCountries, cc) });
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
/** The SHARED Cloudflare edge ECH in Xray's live-query form: every CF-fronted
 * SNI gets its real SNI encrypted on the wire, and because the client resolves
 * it at connect time, Cloudflare key rotation can never stale it. */
const DEFAULT_ECH_VALUE = 'cloudflare-ech.com+udp://1.1.1.1';
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
const EXT_URI_SCHEMES = new RegExp('^(?:' + [PROTO_VLESS, 'vmess', TROJAN_KEY, 'ss', 'ssr', 'hysteria', 'hysteria2', 'hy2', 'tuic', 'socks', 'socks5', 'snell', 'anytls', 'wireguard', 'juicity', 'mieru'].join('|') + '):');
function parseExtUris(body) {
  let text = String(body || '');
  if (!EXT_URI_SCHEMES.test(text)) { try { text = b64decode(text); } catch { } }
  return text.split(/\r?\n/).map((l) => l.trim()).filter((l) => EXT_URI_SCHEMES.test(l)).slice(0, 100);
}

function sniHostnameOk(value) {
  return /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(String(value || '').trim().toLowerCase());
}

/** Effective SNI pool: sanitized custom pool (worker host excluded) or the default. */
function sniPoolOf(host, env, settings) {
  void env;
  const own = String(host || '').toLowerCase();
  const raw = Array.isArray(settings && settings.sniPool) ? settings.sniPool : [];
  const pool = uniq(raw.map((x) => String(x || '').trim().toLowerCase()).filter((x) => sniHostnameOk(x) && x !== own && x !== DEFAULT_FRONTING_SNI));
  return pool.length ? pool.slice(0, 16) : DEFAULT_SNI_POOL.slice();
}

/**
 * The SNI every TLS config carries. Cloudflare checks that the TLS SNI equals the HTTP Host and answers an early,
 * unlogged 403 otherwise («domain fronting» is blocked), so the DEFAULT is the worker host itself — the only
 * value that connects. Hiding that name from DPI is done with ECH (?ech=1: the visible outer SNI is
 * cloudflare-ech.com) or with non-TLS ports, not with another customer's domain. Owners who explicitly turn SNI
 * spoofing on (settings.sniFront) get the old behaviour: settings.sni / env.SNI / the skk.moe default + pool.
 * A single link can still pin an SNI with ?sni=.
 */
function effectiveSni(host, env, settings) {
  if (settings && settings.sniFront === true) return scanSniOf(env, settings);
  return String(host || '').trim().toLowerCase();
}

/** The SNI used for SCANNING Cloudflare IPs (valid there: SNI and Host both name the scan host). */
function scanSniOf(env, settings) {
  return String((settings && settings.sni) || (env && env.SNI) || DEFAULT_FRONTING_SNI).trim().toLowerCase();
}

function addressList(host, env, settings) {
  const own = settings.ips || [];
  const fromEnv = splitCsv(env.CF_IPS).map((r) => splitAddrTag(r).addr).filter(Boolean);
  const defaults = settings.useDefaults ? DEFAULT_CLEAN_ADDRESSES : [];
  const list = uniq(own.concat(fromEnv, defaults));
  if (settings.includeHost && !list.some((a) => a.toLowerCase() === String(host).toLowerCase())) list.push(String(host));
  // DNS-free configs: domain entries already resolved to verified CF edge IPs
  return settings.domMap ? list.map((a) => settings.domMap[a] || a) : list;
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
  const path = (opts && opts.path) || ctx.paths[PROTO_VLESS + 'Path'];
  return PROTO_VLESS + '://' + ctx.uuid + '@' + formatAddr(addr) + ':' + port + '?encryption=none&' +
    wsParams(ctx.host, path, ctx.sni, ctx.fp, tls, ctx.ech) + maskParams(ctx, tls) + '#' + encodeURIComponent(name);
}

function trojanLink(ctx, addr, port, tls, cc, opts) {
  const name = (opts && opts.name) || configName('trojan', addr, port, tls, cc, ctx.host);
  const path = (opts && opts.path) || ctx.paths[TROJAN_KEY + 'Path'];
  return 'trojan://' + encodeURIComponent(ctx[TROJAN_KEY + 'Pass']) + '@' + formatAddr(addr) + ':' + port + '?' +
    wsParams(ctx.host, path, ctx.sni, ctx.fp, tls, ctx.ech) + maskParams(ctx, tls) + '#' + encodeURIComponent(name);
}

/** Link context for one identity (master or a panel user). */
function linkContext(host, env, settings, uuid, user) {
  const protocols = {
    [PROTO_VLESS]: settings.protocols[PROTO_VLESS] && !(user && user.protocols[PROTO_VLESS] === false),
    [TROJAN_KEY]: settings.protocols[TROJAN_KEY] && !(user && user.protocols[TROJAN_KEY] === false),
  };
  return {
    host: String(host).toLowerCase(),
    uuid: String(uuid).toLowerCase(),
    [TROJAN_KEY + 'Pass']: user ? String(uuid).toLowerCase() : String(env.TROJAN_PASS || uuid).toLowerCase(),
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
  // SNI rotation (beta46): TLS configs cycle through the pool instead of all
  // sharing one SNI. ?sni=<host> pins a single SNI and disables rotation.
  if (q.sni) ctx.sni = q.sni;
  const sniPool = q.sni ? [] : ((settings.sniFront === true && settings.sniRotate !== false) ? sniPoolOf(host, env, settings) : []);
  const sniFor = (i) => sniPool.length ? sniPool[i % sniPool.length] : ctx.sni;
  const sniSuffix = (sni) => (sniPool.length && sni !== ctx.sni) ? ' · ' + sni : '';
  // beta45 domMap: 🎯/🧬 dial the worker host directly — swap in its resolved
  // edge IP when the domain itself may be DNS-poisoned on the client line.
  const workerDial = (settings.domMap && settings.domMap[String(host).toLowerCase()]) || host;
  // Per-link overrides (?addr=a,b&port=443&proto=vless&limit=1) let a user pin
  // ONE address → one Cloudflare entry point → a stable exit.
  let addresses = addressList(host, env, settings);
  if (q.addr && q.addr.length) {
    // ?addr= travels inside links users copy and share, so it speaks the SAME vocabulary the
    // scanner imports through /api/ips («ip#CC» country tags, scan-pinned «ip:port») — but it
    // skipped ALL of that validation, handing users silently undialable configs three ways:
    //  1. «1.2.3.4#DE» → the tag became part of the server name; once pasted into any client the
    //     «#» starts the remark, so the port/path/security params are swallowed and the config is dead;
    //  2. «1.2.3.4:99999» → pinnedPortOf refused the out-of-range port, then the WHOLE string was
    //     treated as one hostname and bracketed as IPv6 → garbage «[1.2.3.4:99999]:443»;
    //  3. junk tokens («exa», «a..b») were emitted verbatim as server names.
    // Now the tokens are normalized exactly like /api/ips: tags become an ipCountries overlay
    // (country chips + strict filtering keep working on pin links), an out-of-range pin falls
    // back to the bare host on the normal port walk, junk is dropped, and a list where NOTHING
    // survives falls back to the panel's address list (a pin link must never serve an empty sub).
    const qTags = {};
    const clean = [];
    for (const token of uniq(q.addr)) {
      const t = splitAddrTag(token);
      let a = t.addr;
      const pin = pinnedPortOf(a);
      if (pin) a = a.slice(0, a.lastIndexOf(':'));
      else {
        // a trailing :port that pinnedPortOf refused (0 or > 65535) — drop the pin, keep the
        // host; a BARE IPv6 («::1», «2001:db8::5») also ends in digits and must survive
        // (isIpv6 alone is no guard: it happily accepts «1.2.3.4:99999»)
        const pm = /^(.*):(\d{1,5})$/.exec(a);
        if (pm && !/^[0-9a-f:]+$/i.test(a)) a = pm[1];
      }
      a = a.replace(/^\[/, '').replace(/\]$/, '');
      if (!(isIpv4(a) || isIpv6(a) || /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(a))) continue;
      if (t.cc) qTags[a] = t.cc;
      clean.push(pin ? formatAddr(a) + ':' + pin : formatAddr(a));
    }
    if (clean.length) {
      addresses = clean;
      if (Object.keys(qTags).length) settings = Object.assign({}, settings, { ipCountries: Object.assign({}, settings.ipCountries, qTags) });
    }
  }
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
  // ?survive=1 forces rotation for this render (an explicit ?rotate= still wins).
  const rot = ROT_VALUES.includes(String(q.rotate)) ? String(q.rotate) : (q.survive ? 'fetch' : (ROT_VALUES.includes(settings.subRotate) ? settings.subRotate : 'off'));
  // mulberry32 — the old C-stdlib LCG was badly biased at small moduli: index 0
  // was picked <1% of the time, silently FREEZING the first config despite
  // subRotate=fetch (fresh set promise broken for small IP lists).
  const shuffleArr = (arr, seed) => { let t = seed >>> 0; const rand = () => { t = (t + 0x6d2b79f5) >>> 0; let x = t; x = Math.imul(x ^ (x >>> 15), x | 1); x ^= x + Math.imul(x ^ (x >>> 7), x | 61); return ((x ^ (x >>> 14)) >>> 0) / 4294967296; }; for (let ri = arr.length - 1; ri > 0; ri--) { const rj = Math.floor(rand() * (ri + 1)); const tt = arr[ri]; arr[ri] = arr[rj]; arr[rj] = tt; } return arr; };
  const rseed0 = rot === 'fetch' ? Math.floor(Math.random() * 2147483647) : (Number(new Date().toISOString().slice(0, 10).replace(/-/g, '')) % 2147483646) + 1;
  // Owner-pinned «fixed IPs» (settings.pinnedIps) always lead the sub and are
  // never shuffled — rotation applies to the REST of the list only.
  const pinKeys = (settings.pinnedIps || []).map((p) => String(p).trim().toLowerCase()).filter(Boolean);
  const pinKeyOf = (a) => { const pin = pinnedPortOf(a); const b = pin ? a.slice(0, a.lastIndexOf(':')) : a; return b.replace(/^\[/, '').replace(/\]$/, '').toLowerCase(); };
  const fixedAddrs = pinKeys.length ? addresses.filter((a) => pinKeys.includes(pinKeyOf(a))) : [];
  // The owner's own list (manual add / scanner import) leads in PANEL order —
  // rotation shuffles only the defaults/env extras, never the owner's entries
  // to the tail (regression: manual adds seemed to "never reach the configs").
  const ownSet = new Set((settings.ips || []).map((a) => String(a).trim().toLowerCase()).filter(Boolean));
  const ownAddrs = addresses.filter((a) => ownSet.has(a.toLowerCase()));
  const restAddrs = addresses.filter((a) => !ownSet.has(a.toLowerCase()));
  // beta90 health ordering (?health=1 / settings.healthOrder): sender-reported
  // scanner latency, NOT a worker-measured config success rate. Normalize tags
  // and bridge domain→resolved-IP entries so DNS-free subs retain provenance.
  const healthOn = q.health || settings.healthOrder === true;
  const healthMs = Object.create(null);
  if (healthOn) {
    const sources = settings.ipSources && typeof settings.ipSources === 'object' ? settings.ipSources : {};
    const healthNow = Date.now(), healthTtl = 7 * 24 * 60 * 60 * 1000;
    const keyOf = (a) => String(a || '').split('#')[0].trim().toLowerCase();
    const remember = (key, ms) => {
      const k = keyOf(key), n = Number(ms);
      if (k && Number.isFinite(n) && n > 0 && (healthMs[k] == null || n < healthMs[k])) healthMs[k] = n;
    };
    for (const k of Object.keys(sources)) if (sources[k]) {
      const at = Number(sources[k].at) || 0;
      // Old scanner results are not current health; after 7 days treat as unknown.
      if (at > 0 && healthNow - at >= 0 && healthNow - at <= healthTtl) remember(k, sources[k].ms);
    }
    for (const k of Object.keys(settings.domMap || {})) {
      const mapped = settings.domMap[k], sourceMs = healthMs[keyOf(k)];
      if (mapped && sourceMs != null) remember(mapped, sourceMs);
    }
  }
  const msOf = (a) => healthOn ? (healthMs[String(a || '').split('#')[0].trim().toLowerCase()] || null) : null;
  const byHealth = (arr) => arr.sort((x, y) => {
    const mx = msOf(x), my = msOf(y);
    if (mx == null && my == null) return 0;
    if (mx == null) return 1;
    if (my == null) return -1;
    return mx - my;
  });
  if (healthOn) { byHealth(ownAddrs); byHealth(restAddrs); }
  // Rotate only unknown-health entries when health ordering is on; proven
  // scanner results remain fastest-first. Unknowns still get a fresh/daily
  // permutation so fallback diversity survives without discarding evidence.
  const rotateBlock = (arr, seed) => {
    if (rot === 'off' || arr.length < 2) return;
    if (!healthOn) { shuffleArr(arr, seed); return; }
    const known = arr.filter((a) => msOf(a) != null);
    const unknown = arr.filter((a) => msOf(a) == null);
    if (unknown.length > 1) shuffleArr(unknown, seed);
    const ordered = known.concat(unknown);
    for (let i = 0; i < ordered.length; i++) arr[i] = ordered[i];
  };
  // Rotation keeps its «fresh set every update» promise by shuffling WITHIN each
  // block — the owner's entries always stay ahead of the defaults, so the entry
  // limit can no longer crowd them out of the sub.
  rotateBlock(ownAddrs, rseed0);
  rotateBlock(restAddrs, rseed0 ^ 0x5f5f);
  addresses = fixedAddrs.concat(ownAddrs, restAddrs);
  if (q.proto === 'vless') ctx.protocols[TROJAN_KEY] = false;
  if (q.proto === 'trojan') ctx.protocols[PROTO_VLESS] = false;
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
  // Scan-pinned addresses (`ip:port`) go FIRST — the owner imported them with a
  // verified entry point; the generic port walk must never crowd them out past
  // the entry limit (regression: pinned imports silently never appeared).
  for (const addr of addresses) {
    if (entries.length >= limit) break;
    const pin = pinnedPortOf(addr);
    if (!pin) continue;
    const bare = addr.slice(0, addr.lastIndexOf(':')).replace(/^\[/, '').replace(/\]$/, '');
    const cc = ccOf(addr);
    const ptls = !PLAIN_PORTS.includes(pin);
    if (ctx.protocols[PROTO_VLESS]) { vi++; const esni = ptls ? sniFor(vi - 1) : ctx.sni; const nm = configName('vless', bare, pin, ptls, cc, host, vi); const ectx = esni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: esni }); entries.push({ proto: 'vless', addr: bare, port: pin, tls: ptls, cc, sni: esni, link: vlessLink(ectx, bare, pin, ptls, cc, { name: nm }), name: nm }); }
    if (ctx.protocols[TROJAN_KEY]) { ti++; const esni = ptls ? sniFor(ti - 1) : ctx.sni; const tm = configName('trojan', bare, pin, ptls, cc, host, ti); const ectx = esni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: esni }); entries.push({ proto: 'trojan', addr: bare, port: pin, tls: ptls, cc, sni: esni, link: trojanLink(ectx, bare, pin, ptls, cc, { name: tm }), name: tm }); }
  }
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
      const naddr = (workerDial !== host && bare === String(workerDial)) ? String(host) : bare; // keep the 🔌 WorkerOnly remark on the resolved dial IP
      if (ctx.protocols[PROTO_VLESS]) { vi++; const esni = tls ? sniFor(vi - 1) : ctx.sni; const nm = configName('vless', naddr, port, tls, cc, host, vi) + (tls ? sniSuffix(esni) : ''); const ectx = esni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: esni }); entries.push({ proto: 'vless', addr: bare, port, tls, cc, sni: esni, link: vlessLink(ectx, bare, port, tls, cc, { name: nm }), name: nm }); }
      if (ctx.protocols[TROJAN_KEY]) { ti++; const esni = tls ? sniFor(ti - 1) : ctx.sni; const tm = configName('trojan', naddr, port, tls, cc, host, ti) + (tls ? sniSuffix(esni) : ''); const ectx = esni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: esni }); entries.push({ proto: 'trojan', addr: bare, port, tls, cc, sni: esni, link: trojanLink(ectx, bare, port, tls, cc, { name: tm }), name: tm }); }
      if (entries.length >= limit) break outer;
    }
  }
  // Spoof section (Panel → 🎭): extra-SNI configs (🧬), per-ProxyIP configs (🎯, path /?proxyip=) and SOCKS
  // relays (🧦) — deliberately named apart from the flag-named clean-IP entries.
  // Skipped for a deliberate SINGLE-EXIT pin: ?addr=, a STRICT country (strict=1 must mean ONLY that country) or
  // a tiny ?limit=1|2 (one address, both protocols).
  // NOTE: `!q.addr` alone is always false for an empty-array query (truthy []!),
  // which silently disabled PX/SNI-spoof configs on every real sub URL.
  // A link-level ?limit=N (the config builder ALWAYS sets it) used to drop this whole section, so ProxyIPs added in
  // the panel never reached any builder link. Now they share the limit: it counts EVERY line, the 🎭 part getting
  // at most half of it and only what the owner configured (no built-in default ProxyIPs; 🧬 spoof SNIs stay a
  // no-limit extra — Cloudflare rejects an SNI that differs from the Host).
  const PIN_LIMIT = 2;
  const limited = !!q.limit;
  if (!(q.addr && q.addr.length) && !(limited && q.limit <= PIN_LIMIT) && !(strict && wantCc)) {
    const cleanCount = entries.length;
    const tlsPort = (settings.tlsPorts && settings.tlsPorts[0]) || 443;
    // 🎯/🧦 ride the ports the link asked for: TLS when any TLS port is selected, else the first plain port.
    const pxTls = (settings.tlsPorts || []).length > 0 || !(settings.plainEnabled && (settings.plainPorts || []).length);
    const pxPort = pxTls ? tlsPort : Number(settings.plainPorts[0]);
    for (const sniHost of (limited ? [] : (settings.extraSnis || []))) {
      if (entries.length >= 200) break;
      if (!sniHost || sniHost === ctx.sni) continue;
      const sctx = Object.assign({}, ctx, { sni: sniHost });
      const name = '🧬 SNI ' + sniHost;
      if (ctx.protocols[PROTO_VLESS]) entries.push({ proto: 'vless', addr: workerDial, port: tlsPort, tls: true, cc: '', link: vlessLink(sctx, workerDial, tlsPort, true, '', { name }), name });
      if (ctx.protocols[TROJAN_KEY]) entries.push({ proto: 'trojan', addr: workerDial, port: tlsPort, tls: true, cc: '', link: trojanLink(sctx, workerDial, tlsPort, true, '', { name }), name });
    }
    const faUi = settings.lang !== 'en';
    let pxi = 0;
    for (const px of proxyIpList(env, settings, { explicitOnly: limited })) {
      if (entries.length >= 224) break;
      pxi++;
      const pxcc = (settings.proxyCountries || {})[px] || '';
      // Screenshot style: «🎯 3. 🇩🇪 آلمان · 1.2.3.4» — a separate config PER
      // ProxyIP that exits through that relay (?proxyip= on the WS path).
      const pxlabel = pxcc ? (faUi ? (PX_FA_NAMES[pxcc] || pxcc) : (COUNTRY_NAMES[pxcc] || pxcc)) : '';
      const pxsni = pxTls ? sniFor(pxi - 1) : ctx.sni;
      const pxname = '🎯 ' + pxi + '. ' + (pxcc ? flagOf(pxcc) + ' ' + pxlabel + ' · ' : '') + px + (pxTls ? sniSuffix(pxsni) : '');
      const pxc = pxsni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: pxsni });
      // The pin lives in the WS PATH. Every output format must send THIS path (entry.wsPath), not the generic one —
      // Clash/sing-box/Xray used the generic path, so their «🎯» configs were labels only: all identical.
      const pxVPath = withPathQuery(ctx.paths[PROTO_VLESS + 'Path'], 'proxyip=' + encodeURIComponent(px));
      const pxTPath = withPathQuery(ctx.paths[TROJAN_KEY + 'Path'], 'proxyip=' + encodeURIComponent(px));
      if (ctx.protocols[PROTO_VLESS]) entries.push({ proto: 'vless', addr: workerDial, port: pxPort, tls: pxTls, cc: pxcc, sni: pxsni, wsPath: pxVPath, link: vlessLink(pxc, workerDial, pxPort, pxTls, pxcc, { name: pxname, path: pxVPath }), name: pxname });
      if (ctx.protocols[TROJAN_KEY]) entries.push({ proto: 'trojan', addr: workerDial, port: pxPort, tls: pxTls, cc: pxcc, sni: pxsni, wsPath: pxTPath, link: trojanLink(pxc, workerDial, pxPort, pxTls, pxcc, { name: pxname, path: pxTPath }), name: pxname });
    }
    // 🧦 SOCKS5 relays — exit through the user's own proxies (Gemini etc).
    let sxi = 0;
    for (const sr of socksRelayList(env, settings)) {
      if (entries.length >= 240) break;
      sxi++;
      const ssni = pxTls ? sniFor(pxi + sxi - 1) : ctx.sni;
      const ssname = '🧦 ' + sxi + '. ' + sr.host + ':' + sr.port + (pxTls ? sniSuffix(ssni) : '');
      const sctx = ssni === ctx.sni ? ctx : Object.assign({}, ctx, { sni: ssni });
      const svless = withPathQuery(ctx.paths[PROTO_VLESS + 'Path'], 'proxyip=' + encodeURIComponent(sr.url));
      const strojan = withPathQuery(ctx.paths[TROJAN_KEY + 'Path'], 'proxyip=' + encodeURIComponent(sr.url));
      if (ctx.protocols[PROTO_VLESS]) entries.push({ proto: 'vless', addr: workerDial, port: pxPort, tls: pxTls, cc: '', sni: ssni, wsPath: svless, link: vlessLink(sctx, workerDial, pxPort, pxTls, '', { name: ssname, path: svless }), name: ssname });
      if (ctx.protocols[TROJAN_KEY]) entries.push({ proto: 'trojan', addr: workerDial, port: pxPort, tls: pxTls, cc: '', sni: ssni, wsPath: strojan, link: trojanLink(sctx, workerDial, pxPort, pxTls, '', { name: ssname, path: strojan }), name: ssname });
    }
    if (limited) {
      // ?limit=N is a hard TOTAL (the builder's «config count»): the 🎭 lines share it instead of being dropped —
      // as many as they need up to half of it; the clean-IP tail makes room (TLS-first order is kept).
      const L = settings.entryLimit;
      const extra = entries.splice(cleanCount);
      const reserve = Math.min(extra.length, Math.max(2, Math.floor(L / 2)));
      const take = Math.min(extra.length, Math.max(L - cleanCount, reserve));
      entries.length = Math.min(cleanCount, L - take);
      for (const e of extra.slice(0, take)) entries.push(e);
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
    // 1..65535 — the link-level override used to keep anything > 0, so «?port=70000»
    // produced subscriptions full of configs no client can ever dial (the settings
    // PUT validates the range; the query bypassed it).
    port: splitCsv(q.get('port') || q.get('ports') || '').map(Number).filter((p) => Number.isInteger(p) && p > 0 && p < 65536),
    proto: String(q.get('proto') || '').toLowerCase(),
    limit: Number(q.get('limit') || q.get('count') || 0) || 0,
    country: normalizeCountry(q.get('country') || q.get('cc') || ''),
    strict: q.get('strict') === '1' || q.get('strict') === 'true',
    fam: String(q.get('fam') || '').toLowerCase(),
    rotate: String(q.get('rotate') || '').toLowerCase(),
    // beta90 survival pack: ?survive=1 forces compatible fallbacks for this
    // render (rotation, safe SNI=Host, both protocols, plain ports, fragment,
    // health ordering); ?health=1 orders by scanner-reported ping, not config success.
    survive: q.get('survive') === '1' || q.get('survive') === 'true',
    health: q.get('health') === '1' || q.get('health') === 'true',
    ech: q.get('ech') === '1',
    sni: sniHostnameOk(q.get('sni') || '') ? String(q.get('sni')).trim().toLowerCase() : '',
    nofm: q.get('nofm') === '1',
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
      e.proto === 'vless' ? '    uuid: ' + ctx.uuid : '    password: ' + yamlStr(ctx[TROJAN_KEY + 'Pass']),
      '    udp: true',
      '    network: ws',
      '    ws-opts:',
      '      path: ' + yamlStr(e.wsPath || (e.proto === 'vless' ? ctx.paths[PROTO_VLESS + 'Path'] : ctx.paths[TROJAN_KEY + 'Path'])),
      '      headers:',
      '        Host: ' + yamlStr(ctx.host),
    ];
    if (e.tls) {
      base.push('    tls: true', '    servername: ' + yamlStr(ctx.sni), '    client-fingerprint: ' + ctx.fp, '    skip-cert-verify: false');
      if (ctx.ech) {
        // mihomo ech-opts (top-level proxy key, verified in FlClash/mihomo ≥1.19):
        // the «domain+…» form becomes query-server-name, a base64 list becomes config.
        // must require the scheme after '+' — a base64 ECH list may itself
        // contain '+' (e.g. «AEX+DQ…»), which must NOT parse as domain+query
        const em = /^([A-Za-z0-9][A-Za-z0-9.-]{0,252})\+(?:udp|tcp|https|h2c|tls):\/\//.exec(String(ctx.ech));
        base.push('    ech-opts:', '      enable: true');
        base.push(em ? '      query-server-name: ' + yamlStr(em[1]) : '      config: ' + yamlStr(String(ctx.ech)));
      }
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
      transport: { type: 'ws', path: e.wsPath || (e.proto === 'vless' ? ctx.paths[PROTO_VLESS + 'Path'] : ctx.paths[TROJAN_KEY + 'Path']), headers: { Host: ctx.host } },
    };
    if (e.proto === 'vless') out.uuid = ctx.uuid; else out.password = ctx[TROJAN_KEY + 'Pass'];
    if (e.tls) {
      out.tls = { enabled: true, server_name: ctx.sni, insecure: false, alpn: settings.alpn.split(','), utls: { enabled: true, fingerprint: ctx.fp } };
      // NOTE: ECH is intentionally NOT emitted into sing-box output. sing-box
      // (≤1.14.x, field report 2026-09) treats "enabled but no ECH config in
      // DNS" as FATAL and kills the whole config — one bad resolver and every
      // node dies. Xray links + mihomo ech-opts carry ECH instead.
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
      wsSettings: { path: e.wsPath || (e.proto === 'vless' ? ctx.paths[PROTO_VLESS + 'Path'] : ctx.paths[TROJAN_KEY + 'Path']), headers: { Host: ctx.host } },
      sockopt: (function () { const so = { tcpKeepAliveIdle: 100, tcpNoDelay: true }; const dial = warpOut.length ? 'warp' : (fragOn ? 'fragment' : undefined); if (dial) so.dialerProxy = dial; return so; })(),
    };
    if (e.tls) {
      stream.tlsSettings = { serverName: ctx.sni, fingerprint: ctx.fp, alpn: settings.alpn.split(','), allowInsecure: false };
      if (settings.cipherSuites) stream.tlsSettings.cipherSuites = settings.cipherSuites;
      // ECH per Xray docs: tlsSettings.echConfigList accepts a base64 ECHConfig
      // OR the «domain+udp://server» live-query form.
      if (ctx.ech) stream.tlsSettings.echConfigList = ctx.ech;
    }
    const proxy = e.proto === 'vless'
      ? { tag: 'proxy', protocol: 'vless', settings: { vnext: [{ address: e.addr, port: e.port, users: [{ id: ctx.uuid, encryption: 'none', level: 8 }] }] }, streamSettings: stream }
      : { tag: 'proxy', protocol: 'trojan', settings: { servers: [{ address: e.addr, port: e.port, password: ctx[TROJAN_KEY + 'Pass'], level: 8 }] }, streamSettings: stream };
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

/* DNS cache (adopted from the ZEUS panel's architecture): clients hammer the
 * same names all day — 30 minutes + a 2048-entry cap keeps DoH traffic (and
 * its latency) down. Only successful answers are cached. */
const DNS_CACHE = new Map();
const DNS_CACHE_TTL = 30 * 60 * 1000;
const DNS_CACHE_MAX = 2048;
function dnsCacheGet(key) {
  const hit = DNS_CACHE.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > DNS_CACHE_TTL) { DNS_CACHE.delete(key); return null; }
  return hit.value;
}
function dnsCachePut(key, value) {
  if (DNS_CACHE.size >= DNS_CACHE_MAX) DNS_CACHE.delete(DNS_CACHE.keys().next().value);
  DNS_CACHE.set(key, { value, at: Date.now() });
}
async function handleDoh(request, env) {
  const upstream = dohUpstream(env);
  if (request.method === 'GET') {
    const dns = new URL(request.url).searchParams.get('dns');
    if (!dns) return text('missing dns', 400);
    const hit = dnsCacheGet('g:' + dns);
    if (hit) return new Response(hit.slice(0), { status: 200, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
    const res = await fetch(upstream + '?dns=' + encodeURIComponent(dns), { headers: { accept: 'application/dns-message' } });
    if (res.status === 200) {
      const body = await res.arrayBuffer();
      dnsCachePut('g:' + dns, new Uint8Array(body));
      return new Response(body, { status: 200, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
    }
    return new Response(res.body, { status: res.status, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
  }
  if (request.method === 'POST') {
    const raw = await request.arrayBuffer();
    const key = 'p:' + (await sha256Hex(new Uint8Array(raw))).slice(0, 32);
    const hit = dnsCacheGet(key);
    if (hit) return new Response(hit.slice(0), { status: 200, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
    const res = await fetch(upstream, { method: 'POST', headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' }, body: raw });
    if (res.status === 200) {
      const body = await res.arrayBuffer();
      dnsCachePut(key, new Uint8Array(body));
      return new Response(body, { status: 200, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
    }
    return new Response(res.body, { status: res.status, headers: { 'content-type': 'application/dns-message', 'cache-control': 'no-store' } });
  }
  return text('method not allowed', 405);
}

const GEO_CACHE = new Map();
const GEO_TTL_MS = 10 * 60 * 1000;
async function resolveHost(name) {
  const key = 'a:' + name;
  const hit = dnsCacheGet(key);
  if (hit !== null) return hit;
  try {
    const res = await fetch('https://cloudflare-dns.com/dns-query?name=' + encodeURIComponent(name) + '&type=A', { headers: { accept: 'application/dns-json' } });
    const data = await res.json();
    const a = (data.Answer || []).find((r) => r.type === 1);
    const ip = a ? a.data : '';
    if (ip) dnsCachePut(key, ip);
    return ip;
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
  // beta90 «survival link»: one URL that applies every resilience flag for THIS
  // render only — nothing is persisted. Health ordering rides along so the
  // addresses proven fastest on real scanners lead the shuffled blocks.
  if (q.survive) {
    settings = Object.assign({}, settings, {
      useDefaults: true,
      includeHost: true,
      // Safe Cloudflare default: SNI must match Host. SNI spoof/rotation can
      // receive an early 403, so survival mode explicitly keeps the worker host.
      sniFront: false,
      sniRotate: false,
      plainEnabled: true,
      plainPorts: uniq([80, 8080].concat(settings.plainPorts || [])).slice(0, 16),
      protocols: Object.assign({}, settings.protocols || {}, { vless: true, trojan: true }),
      fragment: Object.assign({}, settings.fragment || { packets: 'tlshello', length: '10-100', interval: '10-20' }, { enabled: true }),
      healthOrder: true,
    });
  }
  // Never publish a blank subscription just because the owner list is empty and
  // both fallback switches were disabled. Restore the built-in, zero-cost
  // address set + this worker hostname for this render only; user settings stay
  // unchanged. A later, manually added IP still leads the subscription.
  if (!addressList(host, env, settings).length) {
    settings = Object.assign({}, settings, { useDefaults: true, includeHost: true });
  }
  if (q.dom !== '1') { try { settings = await withDomMap(env, settings); } catch (e) { /* best-effort */ } }
  if (q.nofm) settings = Object.assign({}, settings, { fmLinks: false });
  if (q.ech) {
    try {
      // Keywords are matched case-insensitively, but a real value is NEVER
      // case-folded: base64 ECHConfigLists are case-sensitive.
      const rawVal = String(settings.echList || '').trim();
      const kw = rawVal.toLowerCase();
      let echList = rawVal;
      if (kw === 'auto') echList = (await echConfigList(effectiveSni(host, env, settings), env)) || 'off';
      else if (!rawVal) echList = DEFAULT_ECH_VALUE; // '' = the shared CF default
      if (kw === 'off' || echList === 'off') echList = '';
      settings = Object.assign({}, settings, { echList });
    } catch { /* ECH is best-effort */ }
  } else if (settings.echList) {
    // ECH stays OPT-IN per subscription (?ech=1 — builder card toggle): with a
    // default value configured, links/clash/xray would otherwise carry ECH
    // everywhere and older cores could fail on it.
    settings = Object.assign({}, settings, { echList: '' });
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

  if (path === '/api/health' || path === '/health') {
    // BPB-style hygiene: anonymous probes get a bare ok — version/KV state only for the owner.
    const owner = await isOwner(request, env, settings, masterUuid);
    return json(owner ? { ok: true, version: CAT_PANEL_VERSION, kv: !!kvBinding(env) } : { ok: true });
  }
  if (path === '/api/version') {
    // Stealth hygiene: the repo URL only ships to the owner — anonymous probes get a bare version.
    const owner = await isOwner(request, env, settings, masterUuid);
    // needsUser: the SAME flag the login page uses to render its username
    // field — apps that log in over the API must send a username too when it
    // is set, otherwise every login is rejected.
    // kv: without the KV binding every write below is memory-only (the data
    // vanishes as soon as another isolate serves the request), so a client can
    // detect that and re-attach the binding instead of silently losing changes.
    const meta = {
      needsUser: !!String(env.PANEL_USER || '').trim(),
      kv: !!kvBinding(env),
      open: panelIsOpen(env, settings),
    };
    return json(owner
      ? Object.assign({ ok: true, panel: 'cat-panel', version: CAT_PANEL_VERSION, repo: REPO_URL }, meta)
      : Object.assign({ ok: true, panel: 'cat-panel', version: CAT_PANEL_VERSION }, meta));
  }
  if (path === '/api/scan-targets.json') return json({ ok: true, ranges: SCAN_RANGES, tlsPorts: TLS_PORTS, plainPorts: PLAIN_PORTS, sni: scanSniOf(env, settings), host });
  if (path === '/api/ech') {
    if (method !== 'GET') return json({ ok: false, error: 'method' }, 405);
    const sni = effectiveSni(host, env, settings);
    const ech = await echConfigList(sni, env);
    const effective = String(settings.echList || '') || DEFAULT_ECH_VALUE;
    return json({ ok: true, sni, has: !!ech, len: ech.length, effective, shared: DEFAULT_ECH_VALUE });
  }

  if (path === '/api/cc-quality') {
    const owner = await isOwner(request, env, settings, masterUuid);
    if (!owner) return json({ ok: false, error: 'auth' }, 401);
    const data = await readJsonKv(env, 'cat_cc_quality_v1', { at: 0, cc: {} });
    return json({ ok: true, at: data.at || 0, cc: data.cc || {} });
  }
  if (path === '/api/domain-check') {
    const owner = await isOwner(request, env, settings, masterUuid);
    if (!owner) return json({ ok: false, error: 'auth' }, 401);
    const host = String(url.searchParams.get('host') || '').trim().toLowerCase().replace(/^https?:\/\//, '').split('/')[0];
    if (!/^[a-z0-9.-]+\.[a-z]{2,}$/.test(host)) return json({ ok: false, error: 'bad host' }, 400);
    try {
      const res = await fetch('https://cloudflare-dns.com/dns-query?name=' + encodeURIComponent(host) + '&type=A', { headers: { accept: 'application/dns-json' } });
      const j = await res.json();
      const answers = ((j && j.Answer) || []).filter((a) => a.type === 1).map((a) => String(a.data || ''));
      const cf = answers.filter((ip) => isCloudflareIp(ip));
      return json({ ok: true, host, ips: answers, onCloudflare: answers.length > 0 && cf.length === answers.length });
    } catch (e) {
      return json({ ok: false, error: 'dns failed' }, 502);
    }
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
        defaults: { addresses: DEFAULT_CLEAN_ADDRESSES,[PXIPS_KEY]: DEFAULT_PROXY_IPS, tlsPorts: TLS_PORTS, plainPorts: PLAIN_PORTS },
        links: subLinks(origin, masterUuid, null),
        paths: tunnelPaths(env),
        sni: effectiveSni(host, env, settings),
        chain: (() => { const c = parseChain(settings.chain); return c ? { type: c.type, host: c.host, port: c.port, auth: !!(c.user || c.pass) } : null; })(),
        countries: countrySummary(host, env, settings),
        userCount: users.length,
        env: { hasUuid: isUuid(env.UUID), hasPanelPassword: !!env.PANEL_PASSWORD,[HASPX_KEY]: !!(env.PROXYIP || env.PROXY_IPS), hasCfIps: !!env.CF_IPS },
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
      // What the owner typed in the Proxy IP box but the panel cannot use — reported, never silent.
      const pxIgnored = Object.prototype.hasOwnProperty.call(patch, PXIPS_KEY) ? normalizeProxyList(patch[PXIPS_KEY]).ignored : [];
      const saved = await writeSettings(env, patch);
      const extra = {};
      if (typeof body.password === 'string') {
        // Password changed → old sessions die; hand back a fresh one.
        extra['set-cookie'] = sessionCookieHeader(await makeSession(env, saved.settings, masterUuid));
      }
      await pushEvent(env, 'settings', 'update');
      return json({ ok: true, persisted: saved.persisted, ignored: pxIgnored, settings: Object.assign({}, saved.settings, { passwordHash: undefined, hasPassword: !!saved.settings.passwordHash, tgToken: saved.settings.tgToken ? '••••' + saved.settings.tgToken.slice(-4) : '', ghPat: saved.settings.ghPat ? '••••' + saved.settings.ghPat.slice(-4) : '' }) }, 200, extra);
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
        const saved = await writeSettings(env, {[PXIPS_KEY]: uniq(settings[PXIPS_KEY].concat(pool.map((p) => p.ip))).slice(0, 32), proxyCountries: Object.assign({}, settings.proxyCountries, cc) });
        await pushEvent(env, 'prepo-import', (ccw || 'all') + ' ' + pool.length);
        return json({ ok: true, persisted: saved.persisted, added: pool.length, total: saved.settings[PXIPS_KEY].length });
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
    // 👑 Neighbor provenance: Cat Client's «اسکن همسایه» flags the IPs it found
    // around known-good neighbours; those get src=neighbor (crown badge) even
    // though they arrive in the same scanner batch.
    const neighborRaw = Array.isArray(body.neighborIps) ? body.neighborIps : splitCsv(body.neighborIps);
    const neighborSet = new Set((neighborRaw || []).map((v) => String(v).split('#')[0].trim()).filter(Boolean));
    const srcMap = Object.assign({}, settings.ipSources || {});
    for (const k of Object.keys(pings).slice(0, 400)) if (pings[k] != null) srcMap[k] = { src: neighborSet.has(String(k).split('#')[0]) ? 'neighbor' : (src || 'import'), ms: Number(pings[k]) || 0, at: Date.now() };
    // ✍️ Manually added IPs get their own provenance («ورودی دستی» badge).
    if (src === 'manual') for (const e of incoming.slice(0, 400)) if (!srcMap[e]) srcMap[e] = { src: 'manual', ms: 0, at: Date.now() };
    const saved = await writeSettings(env, { ips: next, ipCountries: tags, ipSources: srcMap });
    await pushEvent(env, body.replace ? 'ips-replace' : 'ips-add', String(next.length) + ' ips');
    return json({ ok: true, persisted: saved.persisted, count: saved.settings.ips.length, ips: saved.settings.ips });
  }

  // beta90 «survival pack»: one click applies broad fallbacks, but no setting
  // can guarantee every carrier/client. Keep SNI=Host (Cloudflare rejects
  // mismatched SNI), add common cleartext ports as alternatives, and report
  // measured scanner latency only as a local hint. Returns the ?survive=1 link.
  if (path === '/api/survival' && method === 'POST') {
    const body = (await readJsonBody(request)) || {};
    if (!body.apply) return json({ ok: false, error: 'body.apply required' }, 400);
    const patch = {};
    if (settings.subRotate !== 'daily') patch.subRotate = 'daily';
    if (settings.sniFront !== false) patch.sniFront = false;
    if (settings.sniRotate !== false) patch.sniRotate = false;
    if (!settings.fragment || settings.fragment.enabled !== true) {
      patch.fragment = Object.assign({ packets: 'tlshello', length: '10-100', interval: '10-20' }, settings.fragment || {}, { enabled: true });
    }
    if (settings.useDefaults === false) patch.useDefaults = true;
    if (settings.includeHost === false) patch.includeHost = true;
    if (!settings.plainEnabled) patch.plainEnabled = true;
    const fallbackPorts = uniq([80, 8080].concat(settings.plainPorts || [])).slice(0, 16);
    if (fallbackPorts.join(',') !== (settings.plainPorts || []).join(',')) patch.plainPorts = fallbackPorts;
    const proto = settings.protocols || {};
    if (proto.vless === false || proto.trojan === false) patch.protocols = Object.assign({}, proto, { vless: true, trojan: true });
    if (settings.healthOrder !== true) patch.healthOrder = true;
    const changed = Object.keys(patch);
    let persisted = true;
    if (changed.length) {
      persisted = await writeSettings(env, patch).then((saved) => saved.persisted);
      await pushEvent(env, 'survival', 'preset applied: ' + changed.join(', '));
    }
    return json({ ok: true, changed, persisted, survive: origin + '/sub/' + masterUuid + '?survive=1' });
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
    // «سلامت و تست» — REAL template-aware probes (see healthProbe): clean 💦 =
    // IP+port+TLS+Host exactly like the config; plain :80 = no TLS; proxyip 🎯 =
    // the relay chain. Legacy {ips:[…]} bodies still work (kind inferred from
    // the pinned port).
    const body = (await readJsonBody(request)) || {};
    const KINDS = ['clean', 'plain', 'proxyip'];
    const norm = (t) => {
      const addr = String((t && t.addr) || '').trim().replace(/^\[|\]$/g, '');
      const port = Number(t && t.port) > 0 ? Number(t.port) : 443;
      const kind = KINDS.includes(t && t.kind) ? t.kind : 'clean';
      const key = typeof (t && t.key) === 'string' && t.key.trim() ? t.key.trim() : '';
      return { addr, port, kind, key };
    };
    let tests = Array.isArray(body.tests) ? body.tests.map(norm).filter((t) => t.addr).slice(0, 64) : [];
    if (!tests.length) {
      const list = (Array.isArray(body.ips) ? body.ips : splitCsv(body.ips)).map((x) => String(x).trim()).filter(Boolean).slice(0, 64);
      tests = list.map((addr) => { const pin = pinnedPortOf(addr); const port = pin || 443; return { addr, port, kind: PLAIN_PORTS.includes(port) ? 'plain' : 'clean' }; });
      const pxs = (Array.isArray(body[PXIPS_KEY]) ? body[PXIPS_KEY] : splitCsv(body[PXIPS_KEY])).map((x) => String(x).trim()).filter(Boolean).slice(0, 16);
      // each relay is probed on ITS OWN port (ip:port entries), keyed by the raw entry
      tests = tests.concat(pxs.map((addr) => { const hp = splitHostPort(addr, 443); return { addr: hp.hostname, port: hp.port || 443, kind: 'proxyip', key: addr }; }));
    }
    if (!tests.length) return json({ ok: true, results: {} });
    const sockets = await loadSockets();
    if (!sockets) return json({ ok: false, error: 'cloudflare:sockets unavailable' }, 501);
    const host = String(new URL(request.url).hostname).toLowerCase();
    const vpath = tunnelPaths(env)[PROTO_VLESS + 'Path'];
    const results = {};
    await Promise.all(tests.map(async (t) => {
      const r = await healthProbe(sockets, { addr: t.addr, port: t.port, kind: t.kind, host, path: vpath });
      r.kind = t.kind;
      results[t.key || t.addr] = r;
      try { const g = await geoLookup(t.addr); if (g && g.ok && g.countryCode) r.cc = normalizeCountry(g.countryCode) || ''; } catch (e) { }
    }));
    await pushEvent(env, 'ip-test', Object.values(results).filter((r) => r.ok).length + '/' + tests.length + ' healthy');
    return json({ ok: true, results });
  }

  if (path === '/api/svc-test' && method === 'POST') {
    // «🧪 تست سرویس‌ها»: which services actually open through the CURRENT exit
    // chain — this predicts the client experience because non-CF hosts ride the
    // same socks-first path as the client configs do.
    const body = (await readJsonBody(request)) || {};
    const list = (Array.isArray(body.hosts) ? body.hosts : SVC_TEST_HOSTS).map((h) => String(h || '').trim().toLowerCase()).filter((h) => /^[a-z0-9.-]+\.[a-z]{2,}$/.test(h)).slice(0, 8);
    if (!list.length) return json({ ok: true, results: {} });
    const sockets = await loadSockets();
    if (!sockets) return json({ ok: false, error: 'cloudflare:sockets unavailable' }, 501);
    const now = await readSettings(env);
    const results = {};
    const exitInfo = await (async () => {
      try {
        const tr = await fetch('https://www.cloudflare.com/cdn-cgi/trace', { headers: { 'user-agent': 'catclient-health' } });
        const txt = await tr.text();
        const get = (k) => (txt.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1] || '';
        return { ip: get('ip'), loc: get('loc').slice(0, 2).toUpperCase(), colo: get('colo').slice(0, 8) };
      } catch (e) { return { ip: '', loc: '', colo: '' }; }
    })();
    await Promise.all(list.map(async (h) => { results[h] = await svcProbe(sockets, env, now, h); }));
    await pushEvent(env, 'svc-test', Object.values(results).filter((r) => r.ok).length + '/' + list.length + ' open');
    return json({ ok: true, results, exit: exitInfo });
  }

  if (path === '/api/ai-test' && method === 'POST') {
    // BPB-parity service proofs: can the worker's exit (or the chain, if set)
    // actually reach the AI services? 403/404 from their edge = preflight-ok
    // (the route is open, auth happens later); 200 = fully served.
    const targets = [
      { name: 'ChatGPT', url: 'https://chatgpt.com/', preflight: true },
      { name: 'Claude', url: 'https://claude.ai/', preflight: true },
      { name: 'Gemini API', url: 'https://generativelanguage.googleapis.com/', preflight: true },
      { name: 'Gemini Web', url: 'https://gemini.google.com/', preflight: false },
      { name: 'AI Studio', url: 'https://aistudio.google.com/', preflight: false },
    ];
    const probe = async (t) => {
      const t0 = Date.now();
      try {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), 4000);
        const res = await fetch(t.url, { redirect: 'manual', signal: ctrl.signal, headers: { 'user-agent': 'Mozilla/5.0' } });
        clearTimeout(timer);
        const ms = Date.now() - t0;
        const verdict = res.status === 200 ? 'ok' : (t.preflight && (res.status === 403 || res.status === 404) ? 'preflight' : (res.status === 429 ? 'rate' : 'check'));
        return { name: t.name, status: res.status, ms, verdict };
      } catch (e) {
        return { name: t.name, status: 0, ms: Date.now() - t0, verdict: 'fail' };
      }
    };
    const results = await Promise.all(targets.map(probe));
    return json({ ok: true, results });
  }

  if (path === '/api/chain-test' && method === 'POST') {
    // One outbound connection through the chain: handshake + ip-api echo, so
    // the owner sees WHICH ip/country the fixed exit shows. Click-only.
    const body = (await readJsonBody(request)) || {};
    const chain = parseChain(body.chain || settings.chain);
    if (!chain) return json({ ok: false, error: 'invalid chain url' }, 400);
    const sockets = await loadSockets();
    if (!sockets) return json({ ok: false, error: 'cloudflare:sockets unavailable (preview?)' }, 501);
    try {
      return json(await chainProbe(sockets, chain));
    } catch (e) {
      return json({ ok: false, error: String(e && e.message ? e.message : e) }, 502);
    }
  }

  if (path === '/api/update-download') {
    try {
      const src = await fetchNewestPanelSource();
      if (!src) return json({ ok: false, error: 'all sources failed' }, 502);
      // a stale mirror must never be offered as the «update»
      if (panelVersionCompare(src.version, CAT_PANEL_VERSION) < 0) return json({ ok: false, error: 'source is older than this panel', latest: src.version }, 409);
      return new Response(src.text, { headers: { 'content-type': 'text/javascript; charset=utf-8', 'content-disposition': 'attachment; filename="catclient.worker.js"', 'cache-control': 'no-store' } });
    } catch (e) {
      return json({ ok: false, error: 'fetch failed' }, 502);
    }
  }
  if (path === '/api/update-check') {
    try {
      const src = await fetchNewestPanelSource();
      if (!src) return json({ ok: false, error: 'all sources failed' }, 502);
      // The UI flags ANY difference as «⬆️ update», so an older mirror is reported as «same as current».
      const latest = panelVersionCompare(src.version, CAT_PANEL_VERSION) > 0 ? src.version : CAT_PANEL_VERSION;
      return json({ ok: true, current: CAT_PANEL_VERSION, latest, source: PANEL_SOURCE_URL });
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
    // Subs are polled by clients around the clock — a perfect quiet heartbeat
    // for the pools (cron backup; fire-and-forget, never blocks the sub).
    maybeRepoRefresh(env, ctx);
    maybeProxyRepoRefresh(env, ctx);
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
    // Camouflage: the well-known URLs (/, /login, /panel) answer with a plain
    // personal landing page — no login form, no panel hints, nothing to
    // fingerprint. Unknown paths keep the bare nginx-style 404, and robots.txt
    // is exactly what a quiet site would serve.
    // Quiet life-support: the pools must not go stale just because the real
    // panel path is rarely opened — every camo hit nudges the refresh along.
    maybeRepoRefresh(env, ctx);
    maybeProxyRepoRefresh(env, ctx);
    // Owner handoffs survive stealth: ?p=<password> quick-login and the
    // scanner's ?ips= import simply hop over to the real panel path.
    if ((url.searchParams.get('p') || url.searchParams.get('ips')) && (path === '/' || path === '/login' || path === '/panel')) {
      return new Response(null, { status: 302, headers: { location: '/' + pp + '/' + url.search } });
    }
    if (path === '/' || path === '/login' || path === '/panel') return html(camouflagePage());
    if (path === '/robots.txt') return new Response('User-agent: *\nDisallow: /\n', { headers: { 'content-type': 'text/plain; charset=utf-8' } });
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
    maybeRepoRefresh(env, ctx);
    maybeProxyRepoRefresh(env, ctx);
    if (!owner) return html(loginPage(env, settings, !!String(env.PANEL_USER || '').trim()));
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

/** Camouflage landing (stealth mode): a harmless personal page served at /,
 * /login and /panel — zero panel/API hints, decoy meta only. */
function camouflagePage() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex">
<title>Sora's notes</title>
<style>body{margin:0;background:#faf9f6;color:#2b2b2b;font:16px/1.7 Georgia,'Times New Roman',serif;display:grid;place-items:center;min-height:100vh}main{max-width:34rem;padding:3rem 1.5rem}h1{font-size:1.6rem;font-weight:400;margin:0 0 .4rem}p{margin:.4rem 0;color:#555}small{color:#999}ul{list-style:none;padding:0;margin:1.2rem 0 0}li{padding:.5rem 0;border-top:1px solid #e8e5df}a{color:#2b2b2b;text-decoration:none}a:hover{color:#7a5c00}#clock{color:#999;font-size:.85rem}</style></head>
<body><main><h1>Sora's notes</h1><p>A quiet place for half-finished thoughts.</p><p id="clock"></p><ul>
<li><a href="#">On slow mornings</a></li>
<li><a href="#">Notes on tea</a></li>
<li><a href="#">A year of small walks</a></li>
</ul><small>&copy; 2026 &mdash; rss soon</small></main>
<script>function tick(){var d=new Date();var n=document.getElementById('clock');if(n)n.textContent=d.toDateString()}tick();setInterval(tick,30000);</script>
</body></html>`;
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
@media(max-width:1079px){.tools{display:none}.burger{display:grid}}
.ib{width:36px;height:36px;border-radius:12px;display:grid;place-items:center;border:1px solid var(--line);background:var(--input-bg);color:var(--text);transition:background .2s,border-color .2s}
.ib:hover{background:var(--nav-bg);border-color:var(--glow)}
.ib.on{background:var(--nav-bg);border-color:var(--glow);color:var(--violet)}
.ib svg{width:16px;height:16px}
.main{max-width:1180px;margin:0 auto;padding:16px 14px 32px}
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
.ask{position:fixed;inset:0;background:rgba(0,0,0,.72);display:none;place-items:center;z-index:98;padding:20px}.ask.show{display:grid}
.askbox{background:var(--bg,#14101f);border:1px solid var(--line);border-radius:18px;padding:22px;max-width:340px;width:100%;box-shadow:var(--sh,0 8px 40px rgba(0,0,0,.5))}
.askmsg{font-size:14px;font-weight:600;line-height:1.6;margin-bottom:14px;word-break:break-word}
.askin{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:12px;border:1px solid var(--line);background:var(--input-bg);color:var(--text);font-size:13px;margin-bottom:16px;font-family:inherit}
.askrow{display:flex;gap:10px;justify-content:flex-end}
.clients{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
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
/* hamburger drawer (mobile nav) */
.mwrap{position:fixed;inset:0;z-index:60;display:none}
.mwrap.show{display:block}
.mbg{position:absolute;inset:0;background:rgba(0,0,0,.66)}
.mpanel{position:absolute;top:0;bottom:0;inset-inline-start:0;width:min(86vw,340px);background:var(--flat);border-inline-end:1px solid var(--line);box-shadow:0 12px 48px rgba(0,0,0,.6);display:flex;flex-direction:column;overflow-y:auto;padding:14px 12px calc(16px + env(safe-area-inset-bottom))}
.mhead{display:flex;align-items:center;gap:10px;padding:4px 6px 12px;border-bottom:1px solid var(--line);margin-bottom:10px}
.mhead .lg{width:38px;height:38px;display:grid;place-items:center;flex:none}
.mhead .mt{font-weight:700;font-size:14px;line-height:1.3}
.mhead .mv{font-size:10.5px;color:var(--mute)}
.mhead .ib{margin-inline-start:auto}
.mlist{display:flex;flex-direction:column;gap:4px}
.mlist>button{display:flex;align-items:center;gap:12px;width:100%;padding:9px 10px;border-radius:14px;border:1px solid transparent;background:none;color:var(--text);text-align:start;min-height:54px;cursor:pointer}
.mlist>button:hover{background:rgba(255,255,255,.03)}
.mlist>button.on{background:var(--nav-bg);border-color:var(--glow)}
.mi{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;font-size:18px;flex:none;border:1px solid var(--line);background:var(--input-bg)}
.mtx{display:flex;flex-direction:column;line-height:1.4;min-width:0}
.mtx b{font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mtx small{font-size:10.5px;color:var(--mute);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mout{display:flex;align-items:center;gap:10px;margin-top:auto;padding:14px 10px 2px;border-top:1px solid var(--line);color:var(--red,#ff5d7a);font-size:13px;font-weight:600;text-decoration:none}
/* view page headers */
.vhead{display:flex;gap:12px;align-items:center;margin:2px 0 14px}
.vicon{width:46px;height:46px;border-radius:15px;display:grid;place-items:center;font-size:22px;flex:none;background:linear-gradient(135deg,rgba(124,58,237,.28),rgba(0,225,193,.16));border:1px solid var(--line)}
.vhead h1{font-size:17px;font-weight:800;margin:0;line-height:1.3}
.vhead p{margin:2px 0 0;font-size:11.5px;color:var(--mute);line-height:1.5}
/* ── iOS × devigner motion system ── */
:root{--spring:cubic-bezier(.34,1.35,.44,1);--io:cubic-bezier(.32,.72,0,1)}
.btn{border-radius:999px;transition:transform .3s var(--spring),box-shadow .3s var(--io),background .2s,border-color .2s,color .2s}
.btn:hover{transform:translateY(-1px)}
.btn:active{transform:scale(.955)}
.btn:focus-visible,.ib:focus-visible{outline:2px solid var(--violet);outline-offset:2px}
.btn.p{background:linear-gradient(135deg,var(--violet),#00c9ad);border-color:transparent;color:#fff;box-shadow:0 6px 22px rgba(124,58,237,.32)}
.btn.p:hover{box-shadow:0 10px 32px rgba(124,58,237,.44);transform:translateY(-2px)}
.ib{transition:transform .3s var(--spring),background .2s,border-color .2s,color .2s}
.ib:active{transform:scale(.9)}
.chip{transition:transform .3s var(--spring)}
.st{transition:transform .45s var(--spring),border-color .3s}
/* drawer: spring slide + backdrop fade (was display toggle — now animatable) */
.mwrap{position:fixed;inset:0;z-index:60;display:block;visibility:hidden;pointer-events:none}
.mwrap.show{visibility:visible;pointer-events:auto}
.mbg{position:absolute;inset:0;background:rgba(0,0,0,.66);opacity:0;transition:opacity .32s var(--io)}
.mwrap.show .mbg{opacity:1}
.mpanel{position:absolute;top:0;bottom:0;inset-inline-start:0;width:min(86vw,340px);background:var(--flat);border-inline-end:1px solid var(--line);box-shadow:0 12px 48px rgba(0,0,0,.6);display:flex;flex-direction:column;overflow-y:auto;padding:14px 12px calc(16px + env(safe-area-inset-bottom));transform:translateX(var(--mslide,-112%));transition:transform .5s var(--spring)}
[dir=rtl] .mpanel{--mslide:112%}
.mwrap.show .mpanel{transform:none}
.mlist>button{transition:transform .25s var(--spring),background .2s,border-color .2s}
.mlist>button:active{transform:scale(.97)}
/* modals: spring pop */
.ask.show .askbox{animation:zin .38s var(--spring)}
.modal.show>div{animation:zin .38s var(--spring)}
@keyframes zin{from{transform:scale(.9);opacity:0}to{transform:scale(1);opacity:1}}
/* view switches: fade-up + card stagger */
.view.on{animation:vin .42s var(--io)}
.view.on>*{animation:vin .5s var(--io) backwards}
.view.on>*:nth-child(2){animation-delay:.05s}
.view.on>*:nth-child(3){animation-delay:.1s}
.view.on>*:nth-child(4){animation-delay:.15s}
@keyframes vin{from{opacity:0;transform:translateY(12px)}}
/* toast: spring slide-up */
/* marquee strip (devigner signature) */
.marq{overflow:hidden;border:1px solid var(--line);border-radius:999px;padding:8px 0;margin:0 0 14px;background:var(--nav-bg)}
.marq .mi2{display:inline-flex;white-space:nowrap;animation:marq 26s linear infinite}
.marq span{padding-inline-end:38px;font-size:11px;font-weight:700;letter-spacing:1px;color:var(--mute)}
@keyframes marq{to{transform:translateX(-50%)}}
[dir=rtl] .marq .mi2{animation-name:marqr}
@keyframes marqr{to{transform:translateX(50%)}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
/* per-view color identity: colored rail on every card of each section */
#v-dash .sec{border-inline-start:3px solid rgba(124,58,237,.5)}
#v-clients .sec{border-inline-start:3px solid rgba(16,185,129,.5)}
#v-inbounds .sec{border-inline-start:3px solid rgba(59,130,246,.5)}
#v-scan .sec{border-inline-start:3px solid rgba(6,182,212,.5)}
#v-build .sec{border-inline-start:3px solid rgba(132,204,22,.5)}
#v-nodes .sec{border-inline-start:3px solid rgba(245,158,11,.5)}
#v-spoof .sec{border-inline-start:3px solid rgba(236,72,153,.5)}
#v-settings .sec{border-inline-start:3px solid rgba(148,163,184,.45)}
#v-backup .sec{border-inline-start:3px solid rgba(249,115,22,.5)}
#v-about .sec{border-inline-start:3px solid rgba(99,102,241,.5)}
/* settings topic cards: each group its own hue (rainbow rails) */
#fSettings>.card.sec:nth-child(1){border-inline-start:3px solid rgba(148,163,184,.45)}
#fSettings>.card.sec:nth-child(2){border-inline-start:3px solid rgba(124,58,237,.5)}
#fSettings>.card.sec:nth-child(3){border-inline-start:3px solid rgba(16,185,129,.5)}
#fSettings>.card.sec:nth-child(4){border-inline-start:3px solid rgba(59,130,246,.5)}
#fSettings>.card.sec:nth-child(5){border-inline-start:3px solid rgba(236,72,153,.5)}
#fSettings>.card.sec:nth-child(6){border-inline-start:3px solid rgba(245,158,11,.5)}
#fSettings>.card.sec:nth-child(7){border-inline-start:3px solid rgba(6,182,212,.5)}
#fSettings>.card.sec:nth-child(8){border-inline-start:3px solid rgba(132,204,22,.5)}
#fSettings>.card.sec:nth-child(9){border-inline-start:3px solid rgba(99,102,241,.5)}
#fSettings>.card.sec:nth-child(10){border-inline-start:3px solid rgba(249,115,22,.5)}
/* app-style glassy buttons: subtle top-light gradient on every button */
.btn{background:linear-gradient(180deg,rgba(255,255,255,.07),rgba(255,255,255,.02))}
/* mobile sizing: 16px inputs kill iOS focus-zoom (no more pinch-shrinking) */
@media(max-width:640px){
 .main{padding:12px 12px 26px}
 .btn{min-height:42px}
 .ib{width:40px;height:40px}
 input,select,textarea{font-size:16px!important}
 .frm label{font-size:12px}
 .sec{padding:14px}
}
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
/* toast: iOS spring slide-up (overrides base rule above) */
.toast{transform:translate(-50%,18px);transition:opacity .32s var(--io),transform .5s var(--spring)}
[dir=rtl] .toast{transform:translate(50%,18px)}
.toast.show{opacity:1;transform:translate(-50%,0)}
[dir=rtl] .toast.show{transform:translate(50%,0)}
</style></head><body>
<!-- hamburger menu (mobile nav) -->
<div class="mwrap" id="menu">
 <div class="mbg" data-mclose></div>
 <div class="mpanel">
  <div class="mhead"><div class="lg">${catLogo(3)}</div><div><div class="mt" id="menuTitle">${escapeHtml(title)}</div><div class="mv">Cat Panel v${CAT_PANEL_VERSION}</div></div><button class="ib" data-mclose>✕</button></div>
  <div class="mlist">
   <button data-view="dash"><span class="mi" style="background:linear-gradient(135deg,rgba(124,58,237,.20),rgba(124,58,237,.42))">📊</span><span class="mtx"><b data-i="n_dash"></b><small data-i="d_dash"></small></span></button>
   <button data-view="clients"><span class="mi" style="background:linear-gradient(135deg,rgba(16,185,129,.20),rgba(16,185,129,.42))">👥</span><span class="mtx"><b data-i="n_clients"></b><small data-i="d_clients"></small></span></button>
   <button data-view="inbounds"><span class="mi" style="background:linear-gradient(135deg,rgba(59,130,246,.20),rgba(59,130,246,.42))">🧩</span><span class="mtx"><b data-i="n_inbounds"></b><small data-i="d_inbounds"></small></span></button>
   <button data-view="scan"><span class="mi" style="background:linear-gradient(135deg,rgba(6,182,212,.20),rgba(6,182,212,.42))">📡</span><span class="mtx"><b data-i="n_scan"></b><small data-i="d_scan"></small></span></button>
   <button data-view="build"><span class="mi" style="background:linear-gradient(135deg,rgba(132,204,22,.20),rgba(132,204,22,.42))">🛠️</span><span class="mtx"><b data-i="n_build"></b><small data-i="d_build"></small></span></button>
   <button data-view="nodes"><span class="mi" style="background:linear-gradient(135deg,rgba(245,158,11,.20),rgba(245,158,11,.42))">🎯</span><span class="mtx"><b data-i="n_nodes"></b><small data-i="d_nodes"></small></span></button>
   <button data-view="spoof"><span class="mi" style="background:linear-gradient(135deg,rgba(236,72,153,.20),rgba(236,72,153,.42))">🎭</span><span class="mtx"><b data-i="n_spoof"></b><small data-i="d_spoof"></small></span></button>
   <button data-view="settings"><span class="mi" style="background:linear-gradient(135deg,rgba(148,163,184,.20),rgba(148,163,184,.42))">⚙️</span><span class="mtx"><b data-i="n_settings"></b><small data-i="d_settings"></small></span></button>
   <button data-view="backup"><span class="mi" style="background:linear-gradient(135deg,rgba(249,115,22,.20),rgba(249,115,22,.42))">💾</span><span class="mtx"><b data-i="n_backup"></b><small data-i="d_backup"></small></span></button>
   <button data-view="about"><span class="mi" style="background:linear-gradient(135deg,rgba(99,102,241,.20),rgba(99,102,241,.42))">ℹ️</span><span class="mtx"><b data-i="n_about"></b><small data-i="d_about"></small></span></button>
  </div>
  <a class="mout" href="/logout">⏻ <span data-i="n_logout"></span></a>
 </div>
</div>

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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(124,58,237,.20),rgba(124,58,237,.42))">📊</span><div><h1 data-i="n_dash"></h1><p data-i="d_dash"></p></div></div>
 <div class="marq" aria-hidden="true"><div class="mi2"><span>${(fa ? '🧦 Cat Panel ✦ خروجی ثابت ✦ IP تمیز ✦ ضد فیلتر ✦ زنجیرهٔ پایدار ✦ ساب همیشه‌زنده ✦' : '🧦 Cat Panel ✦ Fixed exit ✦ Clean IPs ✦ Anti-censor ✦ Stable chain ✦ Live subs ✦').repeat(4)}</span><span>${(fa ? '🧦 Cat Panel ✦ خروجی ثابت ✦ IP تمیز ✦ ضد فیلتر ✦ زنجیرهٔ پایدار ✦ ساب همیشه‌زنده ✦' : '🧦 Cat Panel ✦ Fixed exit ✦ Clean IPs ✦ Anti-censor ✦ Stable chain ✦ Live subs ✦').repeat(4)}</span></div></div>
 <div class="note w kvwarn" id="kvWarn" style="display:none">
  <b>⚠️ <span data-i="kv_warn_title"></span></b>
  <div style="margin-top:6px" data-i="kv_warn_body"></div>
  <button class="btn sm" type="button" style="margin-top:8px" id="kvWarnOk" data-i="kv_warn_ok"></button>
 </div>
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
   <div><span data-i="ov_seen"></span><span id="ovSeen">…</span></div>
   <div class="small mute" style="margin-top:-6px" data-i="ov_seen_hint"></div>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(16,185,129,.20),rgba(16,185,129,.42))">👥</span><div><h1 data-i="n_clients"></h1><p data-i="d_clients"></p></div></div>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(59,130,246,.20),rgba(59,130,246,.42))">🧩</span><div><h1 data-i="n_inbounds"></h1><p data-i="d_inbounds"></p></div></div>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(6,182,212,.20),rgba(6,182,212,.42))">📡</span><div><h1 data-i="n_scan"></h1><p data-i="d_scan"></p></div></div>
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
   <button class="btn" id="btnBrowserPrune" type="button" style="display:none">🧹 <span data-i="scan_prune"></span></button>
   <button class="btn" id="btnWsTest" type="button">🔌 <span data-i="ws_test"></span></button>
   <button class="btn" id="btnCidrAdd" type="button">➕ <span data-i="cidr_add"></span></button>
   <a class="btn" href="https://github.com/${REPO}#clean-ip" target="_blank" rel="noopener">📖 <span data-i="scan_guide"></span></a>
  </div>
  <div class="note w small" style="margin-top:10px" data-i="scan_note_browser"></div>
  <div id="scanRes" class="res" style="margin-top:12px"></div>
  <div id="wsTestOut" class="res" style="margin-top:12px"></div>
  <div class="card sec" style="margin-top:12px" id="survCard">
   <h2><span class="ic">🛡</span><span data-i="surv_title"></span></h2>
   <div class="note small" data-i="surv_hint"></div>
   <button class="btn p" id="btnSurvival" type="button">🛡 <span data-i="surv_apply"></span></button>
   <div id="survOut" class="res small" style="margin-top:8px" dir="ltr"></div>
  </div>
  <div id="scanList" style="margin-top:6px"></div>
 </div>
</section>

<!-- ================= CONFIG BUILDER ================= -->
<section class="view" id="v-build">
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(132,204,22,.20),rgba(132,204,22,.42))">🛠️</span><div><h1 data-i="n_build"></h1><p data-i="d_build"></p></div></div>
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
  <div class="note w" id="bEmpty" style="display:none;margin-top:8px"></div>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(245,158,11,.20),rgba(245,158,11,.42))">🎯</span><div><h1 data-i="n_nodes"></h1><p data-i="d_nodes"></p></div></div>
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
  <h2><span class="ic">✍️</span><span data-i="ip_manual"></span></h2>
  <div class="small mute" data-i="ip_manual_hint"></div>
  <textarea id="manualIps" rows="2" placeholder="104.16.1.1&#10;[2606:4700::]:443&#10;www.example.com"></textarea>
  <div class="row" style="margin-top:8px">
   <div style="flex:1"><label class="small dim" data-i="ip_manual_port"></label><input id="manualPort" class="mono" dir="ltr" inputmode="numeric" placeholder="443"></div>
   <div style="flex:1"><label class="small dim" data-i="ip_manual_cc"></label><input id="manualCC" class="mono" dir="ltr" maxlength="2" placeholder="DE"></div>
  </div>
  <div class="row" style="margin-top:8px">
   <label class="row small" style="gap:6px"><input type="checkbox" id="manualTest" checked style="width:auto"> <span data-i="ip_manual_test"></span></label>
   <button class="btn p sm" id="btnManualAdd" style="margin-inline-start:auto">✚ <span data-i="ip_manual_add"></span></button>
  </div>
 </div>
 <div class="card sec">
  <h2><span class="ic">🌍</span><span data-i="cc_title"></span> <span class="chip" id="ccState"></span></h2>
  <div class="note i small" data-i="cc_why"></div>
  <div class="ipl" id="ccList" style="margin-top:10px"></div>
  <div class="row small mute" id="ccLatTitle" style="margin-top:8px"></div>
  <div class="row" id="ccLatency" style="flex-wrap:wrap;gap:4px;margin-top:4px"></div>
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
  <div class="row" style="margin-top:10px"><button class="btn r sm" id="btnIpClear" data-i="ip_clear"></button><button class="btn sm" id="btnIpCopy" data-i="copy_all"></button><button class="btn sm" id="btnSvcTest" type="button" style="margin-inline-start:auto">🧪 <span data-i="svc_btn"></span></button><button class="btn p sm" id="btnIpTest" type="button">🩺 <span data-i="ip_test_btn"></span></button></div>
  <div class="small mute" id="ipTestOut" style="margin-top:6px"></div>
  <div class="row small mute" data-i="ip_test_hint" style="margin-top:4px"></div>
  <div class="row" id="pxTestChips" style="flex-wrap:wrap;gap:4px;margin-top:6px"></div>
  <div class="row" id="svcChips" style="flex-wrap:wrap;gap:4px;margin-top:6px"></div>
  <div class="row small" style="margin-top:6px;gap:10px;flex-wrap:wrap"><a class="mono" href="https://ipcheck.ing" target="_blank" rel="noreferrer nofollow">🌐 IPCheck.ing</a><span class="mute" data-i="exit_hint"></span></div>
 </div>

 <div class="card sec">
  <h2>🛡 <span data-i="ai_title"></span></h2>
  <div class="small mute" data-i="ai_hint"></div>
  <div class="row" style="margin-top:10px"><button class="btn sm" id="btnAiTest" type="button">🧪 <span data-i="ai_btn"></span></button><span class="small mute" id="aiOut"></span></div>
  <div id="aiRows" style="margin-top:10px"></div>
 </div>
 <div class="card sec">
  <h2>📊 <span data-i="ccq_title"></span></h2>
  <div class="small mute" data-i="ccq_hint"></div>
  <div class="row" style="margin-top:10px"><button class="btn sm" id="btnCcq" type="button">↻ <span data-i="ccq_btn"></span></button><span class="small mute" id="ccqOut"></span></div>
  <div id="ccqRows" style="margin-top:10px"></div>
 </div>
 <div class="card sec">
  <h2>🌐 <span data-i="v6p_title"></span></h2>
  <div class="small mute" data-i="v6p_hint"></div>
  <div class="row" style="margin-top:10px"><button class="btn sm" id="btnV6Pool" type="button">➕ <span data-i="v6p_btn"></span></button><span class="small mute" id="v6pOut"></span></div>
 </div>
</section>

<!-- ================= SETTINGS ================= -->
<section class="view" id="v-spoof">
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(236,72,153,.20),rgba(236,72,153,.42))">🎭</span><div><h1 data-i="n_spoof"></h1><p data-i="d_spoof"></p></div></div>
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
   <button class="btn sm p" id="btnExtPresetFree" type="button">🆓 <span data-i="ext_preset_free"></span></button>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(148,163,184,.20),rgba(148,163,184,.42))">⚙️</span><div><h1 data-i="n_settings"></h1><p data-i="d_settings"></p></div></div>
 <form class="frm" id="fSettings">
 <div class="card sec">
  <h2>🪪 <span data-i="g_ident"></span></h2>
  <div class="two">
   <div><label data-i="s_title"></label><input name="ptitle" maxlength="60"></div>
   <div><label data-i="s_lang"></label><select name="plang"><option value="fa">فارسی</option><option value="en">English</option></select></div>
  </div>
  </div>
 <div class="card sec">
  <h2>🔒 <span data-i="g_sec"></span></h2>
  <label data-i="s_pass"></label>
  <div class="row"><input name="password" type="password" autocomplete="new-password" data-ph="s_pass_ph" style="flex:1"><span class="chip" id="passState"></span></div>
  <label data-i="s_stealth"></label>
  <div class="row"><input name="panelPath" class="mono" dir="ltr" spellcheck="false" data-ph="s_stealth_ph" style="flex:1"><button type="button" class="btn sm" id="btnPathRnd">🎲</button></div>
  <div class="small dim" data-i="s_stealth_hint"></div>
  </div>
 <div class="card sec">
  <h2>🔌 <span data-i="g_conn"></span></h2>
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
  <div class="row small" style="margin-top:10px"><span class="sw" id="swHealth"></span><span data-i="s_health"></span></div>
  <div class="small dim" style="margin-top:4px" data-i="s_health_hint"></div>
  </div>
 <div class="card sec">
  <h2>🧬 <span data-i="g_sni"></span></h2>
  <div class="two" style="margin-top:12px">
   <div><label data-i="s_sni"></label><input name="sni" class="mono" data-ph="s_sni_ph"><div class="small mute" id="echState" style="margin-top:4px"></div></div>
   <div><label data-i="s_ech"></label><input name="echList" class="mono" data-ph="s_ech_ph"><div class="small mute" data-i="s_ech_hint"></div></div>
   <div><label data-i="s_fp"></label><select name="fingerprint"><option>chrome</option><option>firefox</option><option>safari</option><option>ios</option><option>android</option><option>edge</option><option>random</option><option>randomized</option><option>unsafe</option></select></div>
  </div>
  <div class="two">
   <div><label data-i="s_limit"></label><input name="entryLimit" type="number" min="4" max="200"></div>
   <div><label data-i="s_flags"></label><div class="row small" style="margin-top:6px"><span class="sw" id="swDefaults"></span><span data-i="s_defaults"></span></div><div class="row small" style="margin-top:8px"><span class="sw" id="swHost"></span><span data-i="s_host"></span></div></div>
  </div>
  </div>
 <div class="card sec">
  <h2>🧭 <span data-i="g_route"></span></h2>
  <label data-i="s_route"></label>
  <div class="row small" style="margin-top:6px"><span class="sw" id="swIran"></span><span data-i="s_iran"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swAds"></span><span data-i="s_ads"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swQuic"></span><span data-i="s_quic"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swDom2ip"></span><span data-i="s_dom2ip"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swSniRot"></span><span data-i="s_snir"></span></div>
  <div class="row small" style="margin-top:8px"><span class="sw" id="swFm"></span><span data-i="s_fml"></span></div>
  <div class="row" style="margin-top:6px"><input id="sniPoolCsv" data-ph="i_snipool_ph" style="width:100%"></div>
  <div class="row small muted" data-i="s_snir_hint"></div>
  <div class="small dim" data-i="s_route_hint"></div>
  </div>
 <div class="card sec">
  <h2>🪄 <span data-i="g_frag"></span></h2>
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
  </div>
 <div class="card sec">
  <h2>🎯 <span data-i="g_chain"></span></h2>
  <label><span data-i="s_chain"></span> <span class="chip" id="chainState"></span></label>
  <input name="chain" class="mono" dir="ltr" data-ph="s_chain_ph">
  <div class="small dim" data-i="s_chain_hint"></div>
  <div class="two" style="margin-top:8px">
   <div><label data-i="s_chain_mode"></label><select name="chainMode"><option value="all" data-i="s_chain_all"></option><option value="cf" data-i="s_chain_cf"></option></select></div>
   <div><label data-i="s_chain_strict"></label><div class="row small" style="margin-top:6px"><span class="sw" id="swStrict"></span><span data-i="s_chain_strict_on"></span></div></div>
  </div>
  <div class="row" style="margin-top:8px"><button class="btn sm" type="button" id="btnChainTest" data-i="s_chain_test"></button><span class="small mute" id="chainTestOut"></span></div>
  </div>
 <div class="card sec">
  <h2>🤖 <span data-i="g_tg"></span></h2>
  <label><span data-i="s_tg"></span> <span class="chip" id="tgState"></span></label>
  <div class="two">
   <div><label>Bot token</label><input name="tgToken" class="mono" dir="ltr" placeholder="123456:ABC…"></div>
   <div><label data-i="s_tg_admins"></label><input name="tgAdmins" class="mono" dir="ltr" placeholder="123456789, 987654321"></div>
  </div>
  <div class="row" style="margin-top:8px"><button class="btn sm" type="button" id="btnTgHook" data-i="s_tg_hook"></button><span class="small mute" id="tgHookOut"></span></div>
  <div class="small dim" data-i="s_tg_hint"></div>
  </div>
 <div class="card sec">
  <h2>🚀 <span data-i="g_gh"></span></h2>
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
  </div>
 <div class="card sec">
  <h2>💾 <span data-i="g_save"></span></h2>
  <div class="row" style="margin-top:16px"><button class="btn p" type="submit" data-i="save"></button><span class="small mute" id="saveState"></span></div>
  <div class="row" style="margin-top:10px"><button class="btn sm" type="button" id="btnSetExport">⬇️ <span data-i="set_export"></span></button><button class="btn sm" type="button" id="btnSetImport">⬆️ <span data-i="set_import"></span></button><input type="file" id="setImportFile" accept=".json,application/json" style="display:none"></div>
  </div>
</form>
 <div class="card sec">
  <h2><span class="ic">🔗</span><span data-i="paths"></span></h2>
  <div class="small mute" id="pathsBox"></div>
 </div>
</section>

<!-- ================= BACKUP ================= -->
<section class="view" id="v-backup">
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(249,115,22,.20),rgba(249,115,22,.42))">💾</span><div><h1 data-i="n_backup"></h1><p data-i="d_backup"></p></div></div>
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
 <div class="vhead"><span class="vicon" style="background:linear-gradient(135deg,rgba(99,102,241,.20),rgba(99,102,241,.42))">ℹ️</span><div><h1 data-i="n_about"></h1><p data-i="d_about"></p></div></div>
 <div class="card sec">
  <h2><span class="ic">🐱</span>Cat Panel v${CAT_PANEL_VERSION}</h2>
  <div class="small mute" data-i="about_text"></div>
  <div class="row" style="margin-top:12px"><a class="btn" href="${REPO_URL}" target="_blank" rel="noopener">GitHub</a><a class="btn" href="${REPO_URL}/releases" target="_blank" rel="noopener">Cat Client APK</a></div>
  <div id="updateBox" class="small" style="margin-top:12px"></div>
 </div>
 <div class="card sec">
  <h2>🛟 <span data-i="rec_title"></span></h2>
  <div class="small mute" data-i="rec_hint"></div>
  <div class="small" style="margin-top:8px;line-height:1.9" data-i="rec_steps"></div>
 </div>
 <div class="card sec">
  <h2>🏠 <span data-i="dom_title"></span></h2>
  <div class="small mute" data-i="dom_hint"></div>
  <div class="row" style="margin-top:8px"><input id="domIn" class="mono" dir="ltr" data-ph="dom_ph" style="flex:1"><button class="btn sm" id="btnDomCheck" type="button">🔎 <span data-i="dom_check"></span></button></div>
  <div class="small" id="domOut" style="margin-top:8px"></div>
  <div class="small mute" style="margin-top:8px" data-i="dom_steps"></div>
 </div>
 <div class="card sec">
  <h2><span class="ic">📱</span><span data-i="clients_title"></span></h2>
  <div class="small mute" data-i="clients_hint"></div>
  <div class="clients">
   <a class="btn sm" href="${REPO_URL}/releases" target="_blank" rel="noopener">🐱 Cat Client · Android</a>
   <a class="btn sm" href="https://github.com/2dust/v2rayNG/releases" target="_blank" rel="noopener">🤖 v2rayNG · Android</a>
   <a class="btn sm" href="https://github.com/hiddify/hiddify-app/releases" target="_blank" rel="noopener">🅰️ Hiddify · Android/iOS/PC</a>
   <a class="btn sm" href="https://github.com/chen08209/FlClash/releases" target="_blank" rel="noopener">⚡ FlClash · Android/PC</a>
   <a class="btn sm" href="https://github.com/2dust/v2rayN/releases" target="_blank" rel="noopener">🪟 v2rayN · Windows</a>
   <a class="btn sm" href="https://apps.apple.com/app/streisand/id6498794956" target="_blank" rel="noopener">🍎 Streisand · iOS</a>
  </div>
 </div>
</section>
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
<div class="ask" id="ask"><div class="askbox"><div class="askmsg" id="askMsg"></div><input class="askin" id="askIn" dir="ltr" style="display:none"><div class="askrow"><button class="btn sm" id="askNo"></button><button class="btn sm" id="askYes" style="border-color:var(--violet);color:var(--violet)"></button></div></div></div>

<script>
(function(){
'use strict';
var HOST=${JSON.stringify(host)}, UUID=${JSON.stringify(masterUuid)}, VERSION=${JSON.stringify(CAT_PANEL_VERSION)};
var I18N={
fa:{stats:'آمار و وضعیت پنل',st_users:'کل کاربران',st_users_s:'تعریف‌شده در پنل',st_active:'فعال',st_active_s:'بدون انقضا یا غیرفعال',st_exp:'منقضی / غیرفعال',st_exp_s:'نیاز به تمدید',st_ips:'آی‌پی تمیز',st_cfg:'کانفیگ در هر ساب',
master_links:'لینک‌های اشتراک اصلی',self:'اطلاعات اتصال من',users:'لیست کاربران',search:'جستجوی نام یا UUID…',f_all:'همه',f_active:'فعال',f_expired:'منقضی',f_disabled:'غیرفعال',s_new:'جدیدترین',s_exp:'نزدیک‌ترین انقضا',s_name:'نام',
h_user:'کاربر',h_proto:'پروتکل',h_links:'لینک ساب',h_time:'زمان',h_seen:'آخرین آنلاین',h_status:'وضعیت',h_act:'عملیات',seen_never:'هرگز',seen_now:'همین حالا',seen_min:'%1 دقیقه پیش',no_users:'هنوز کاربری نساختی. با دکمهٔ + اولین کاربر را بساز.',
scan_title:'آی‌پی تمیز و اسکنر',scan_why:'اسکن روی دستگاه خودت انجام می‌شود (نه داخل ورکر). این دقیقاً روشی است که BPB و ZEUS استفاده می‌کنند: ورکر هیچ درخواستی خرج نمی‌کند و نتیجه از شبکهٔ واقعی تو (همان اپراتور) به دست می‌آید.',
scan_app:'اسکن با Cat Client',scan_browser:'تست از شبکهٔ من (مرورگر)',scan_guide:'راهنمای اسکنرها',warp_title:'WARP روی خروجی Xray (کلیدهای خودت)',warp_hint:'اتصال به پنل داخل تونل WARP خودت می‌رود (آی‌پی واقعی‌ات حتی برای ورکر پنهان می‌شود). کلیدها را از wgcf یا خروجی Aether بردار — ورکر هیچ‌وقت با کلادفلر ثبت‌نام نمی‌کند (بن نمی‌شود). با روشن‌بودن WARP، فرگمنت کنار گذاشته می‌شود (تونل UDP است).',warp_mode:'حالت',warp_off:'خاموش',warp_chain:'WARP-در-WARP (زنجیره)',warp_sk:'SecretKey وایرگارد',warp_pk:'PublicKey همتا (Cloudflare)',warp_reserved:'reserved (اختیاری — با ویرگول)',warp_endpoint:'اندپوینت',warp_warn:'هیچ کلیدی را که مال خودت نیست اینجا نگذار. برای خاموش‌کردن موقت، حالت را «خاموش» بگذار — کلیدها می‌مانند.',ext_title:'ساب‌های خارجی (ترکیب با ساب تو)',ext_hint:'محتوای ساب‌های خارجی از دامنهٔ خود پنل سرو می‌شود (raw.github از ایران باز نمی‌شود) + اگر لیست URI باشد بعد از کانفیگ‌های خودت به ساب اضافه می‌شود. ?noext=1 = بدون این‌ها.',ext_add:'افزودن ساب',ext_preset:'ساب آمادهٔ سرورلس (PattNG)',ext_preset_free:'کانفیگ‌های رایگان پترنیها',ext_core:'ساب سرورلس به هستهٔ Xray تازه نیاز دارد (PattNG یا v2rayNG ≥2.2.6) و باید مستقیم در اپ ایمپورت شود، نه داخل ساب پنل.',ext_empty:'هنوز ساب خارجی نداری — پیش‌تنظیم سرورلس را امتحان کن.',ext_name:'نام',ext_url:'آدرس https ساب',mitm_title:'MITM + DomainFronting (سمت کلاینت)',mitm_body:'روشِ پترنیها برای باز کردن مستقیم یوتیوب/اینستاگرام/واتس‌اپ/فیسبوک/رددیت بدون سرور — راه‌اندازی روی خود دستگاه (ویندوز/لینوکس/مک/اندروید بدون روت) انجام می‌شود؛ سرتیفیکیت شخصی بساز و به سیستم اعتماد بده. راهنمای کامل در مخزن:',aether_title:'کانفیگ‌های ویژهٔ Aether (PattNG)',aether_hint:'لینک aether:// می‌سازد — با دکمهٔ باز کردن مستقیم در PattNG (هستهٔ Aether) باز می‌شود. WARP تک‌لایه، WARP-در-WARP (Gool) و MASQUE/HTTP-2 با فرگمنت.',aether_mode:'نوع',aether_gool:'WARP-در-WARP (Gool)',aether_name:'نام کانفیگ',aether_family:'خانوادهٔ آی‌پی',aether_open:'افزودن به PattNG',pp_countries:'افزودن مخزن کشوری (وان‌وو):',px_addrs:'افزودن استخر سالم به لیست اتصال (به‌عنوان آی‌پی)',px_addrs_hint:'پروکسی‌آی‌پی‌های سالم استخر، با تگ کشورشان به لیست آی‌پی‌های اتصال اضافه می‌شوند و کانفیگ 💦 می‌گیرند. هر پروکسی‌آی‌پی لزوماً به‌عنوان آدرس اتصال جواب نمی‌دهد — بعد از افزودن با تست اتصال اپ/اسکنر فیلترشان کن.',px_none:'چیزی برای افزودن نبود — اول «بروزرسانی» استخر را بزن',b_ech:'ECH (رمزگذاری ClientHello — سبک تیکه‌های ECH/SIIT) — طبق گزارش میدانی مهر ۱۴۰۵، روشن‌کردنش مهم‌ترین عامل وصل‌ماندن پنل‌های کلادفلری است',b_ech_off:'خاموش',ech_none:'SNI فعلی ECH ندارد (یا دسترسی DNS نبود) — خاموش نگه دار',rot_btn_off:'ایپی ثابت (چرخش روشنه — بزن تا ثابت شه)',rot_btn_on:'ایپی ثابته (بزن تا چرخش روشن شه)',rot_fixed_lbl:'ایپی ثابت',rot_rot_lbl:'چرخش',rot_now_fixed:'📌 چرخش خاموش شد — ایپی‌ها ثابت ماندند',rot_now_rotating:'⚡ چرخش روشن شد — هر آپدیت ست تازه',hero_inuse:'در حال استفاده (همین لحظه)',hero_ports:'پورت‌ها',hero_sni_host:'آدرس ورکر ✓',hero_sni_pool:'چرخش فعال · استخر',px_ignored:'نادیده گرفته شد (نه آدرس ProxyIP است و نه socks5://):',hero_ips:'آی‌پی تمیز',hero_px:'رله ProxyIP',hero_exit:'خروجی ثابت',hero_rot:'چرخش',hero_warn:'⚠️ SNI پیش‌فرض skk.moe است — چرخش SNI (هر کانفیگ یک SNI متفاوت) ریسک را کم کرده، ولی اگر اپراتورت skk.moe را ببندد باز بهتر است از اسکنر SNI اپ، SNI سالمِ خط خودت را بگیری و «⚡ SNI اصلی پنل شود» را بزنی.',hero_build:'کانفیگ‌ساز',ip_pin:'پین به‌عنوان ایپی ثابت (همیشه اول ساب)',ip_unpin:'برداشتن پین',pin_saved:'📌 این آی‌پی همیشه اول ساب می‌ماند — حتی با چرخش',pin_removed:'پین برداشته شد',s_rot:'چرخش خودکار کانفیگ‌ها',s_rot_off:'ثابت (مثل BPB)',s_rot_fetch:'هر بروزرسانی',s_rot_daily:'روزانه',s_rot_hint:'با هر آپدیت ساب، چیدمان آی‌پی‌ها و شمارهٔ کانفیگ‌ها عوض می‌شود — هر بار ستِ تازه می‌گیری. «روزانه» در طول روز ثابت است؛ «ثابت» همان ترتیب همیشگی است.',pp_title:'مخزن‌های ProxyIP (آپدیت ۱۲ساعته)',pp_hint:'فیدهای عمومی ProxyIP (آی‌پی یا دامنه)؛ هر ۱۲ ساعت خودکار بروز می‌شوند. ProxyIP = IP واسط برای بازکردن سایت‌های کلادفلریِ روی همان IP؛ خراب‌ها بعد از ۳ گزارش حذف و جایگزین می‌شوند.',pp_cc:'کشورهای استخر ProxyIP — + = افزودن ۸ عدد آن کشور به لیست ProxyIP پنل',pp_auto:'افزودن خودکار ۶ ProxyIP تازه به ساب‌ها',pp_src:'منابع: xgonce/Cloudflare_IP · wanwushequ/ProxyIP',pp_dead_note:'گزارش مرده: POST /api/prepos {action:"health",dead:[…]} ×۳ — بعدش جایگزین می‌شود.',rp_title:'مخزن‌ها (آپدیت ۱۲ساعته)',rp_refresh:'بروزرسانی',rp_hint:'فیدهای عمومی آی‌پی تمیز؛ هر ۱۲ ساعت خودکار بروز می‌شوند (cron کلادفلر + باز شدن پنل). مخزن خراب‌ها بعد از ۳ گزارش از استخر حذف و در بروزرسانی بعدی جایگزین می‌شود.',rp_cc:'کشورهای استخر مخزن — + = افزودن ۱۶ آی‌پی آن کشور به لیست پنل',rp_auto:'افزودن خودکار ۸ آی‌پی تازه به ساب‌ها',rp_add:'مخزن جدید',rp_add_url:'آدرس raw مخزن (https://…)',rp_add_name:'نام مخزن',rp_empty:'استخر مخزن خالی است — «بروزرسانی» را بزن.',rp_nokv:'بدون KV ذخیره نمی‌شود',rp_src:'منابع: arista-project/cf-clean-ips · imatixofficel/Scanner-matix',rp_dead_note:'گزارش آی‌پی مرده؟ سه بار «health» با POST /api/repos {action:"health",dead:[…]} — بعدش خودکار عوضش می‌کند.',n_build:'کانفیگ‌ساز',b_title:'کانفیگ‌ساز',b_hint:'برای هر اپراتور، کشور و پورت یک لینک سابِ دقیق می‌سازد؛ تنظیمات اصلی پنل را تغییر نمی‌دهد.',b_isp:'پروفایل اپراتور (پیشنهاد — روی خط خودت تست کن)',isp_mtn:'ایرانسل (MTN)',isp_mci:'همراه اول (MCI)',isp_rtl:'رایتل / شاتل',isp_tdsl:'مخابرات',isp_direct:'مستقیم / خودکار',b_isp_mtn_n:'ایرانسل: فرگمنت حتماً روشن؛ پورت‌های 443 و 8443 با اثر انگشت chrome.',b_isp_mci_n:'همراه اول: 443 و 2053؛ اگر IPv6 داری خانواده را روی «هر دو» بگذار.',b_isp_rtl_n:'رایتل/شاتل: پورت‌های بدون TLS (80/8080) معمولاً بهتر جواب می‌دهد؛ فرگمنت کوتاه.',b_isp_tdsl_n:'مخابرات: 443 با اثر انگشت iOS معمولاً پایدارتر است.',b_isp_direct_n:'آماده‌سازی‌ای اعمال نشد — فیلترها را خودت انتخاب کن.',b_proto:'پروتکل',b_fam:'خانوادهٔ آی‌پی',b_both:'هر دو',b_ports:'پورت‌ها (چندتایی)',b_cc:'کشور خروجی',b_cc_all:'همه کشورها',b_limit:'تعداد کانفیگ (۱ تا ۲۰۰)',b_strict:'رفتار کشور',b_fb_ok:'سقوط به بقیهٔ کشورها',b_only:'فقط همین کشور',b_gen:'ساخت ساب زنده',b_copy:'کپی همه',b_link:'لینک ساب ساخته‌شده',b_prev:'پیش‌نمایش زنده (اولین خط‌ها)',b_open:'باز کردن در',b_frag:'فرگمنت و اثر انگشت (تنظیمِ خودِ کلاینت)',b_frag_hint:'فرگمنت داخل لینک ساب نمی‌آید؛ در خود کلاینت واردش کن (v2rayNG: ویرایش کانفیگ → Fragment). مقدارش با پروفایل اپراتور عوض می‌شود.',b_fp:'اثر انگشت TLS',scan_cat:'دستهٔ آی‌پی',scan_region:'منطقه',scan_cc:'کشورهای لیست پنل',scan_search:'جستجوی کشور',scan_search_ph:'آلمان یا DE…',scan_cidr:'افزودن از رنج CIDR یا دامنه',scan_cidr_ph:'104.16.0.0/24 یا cdn.example.com',cidr_add:'افزودن به لیست',cidr_ok:'%1 آی‌پی اضافه شد',cidr_bad:'رنج نامعتبر است (نمونه: 104.16.0.0/24)',loc_now:'لوکیشن فعلی',loc_refresh:'بروزرسانی',loc_fail:'لوکیشن خوانده نشد',scan_jump:'⚙ ساب فقط این کشور',scan_empty:'با این فیلتر آی‌پی‌ای نیست.',scan_note_browser:'«تست از شبکهٔ من» دامنه‌ها و آی‌پی‌های خام را از خط خودت می‌سنجد (اتصال مستقیم مرورگر) و مرده‌ها را با یک کلیک از لیستت حذف می‌کند. پروکسی‌آی‌پی‌ها مسیرشان از ورکر است — آن‌ها با «تست با کارگر».',reg_eu:'🇪🇺 اروپا',reg_me:'🕌 خاورمیانه',reg_as:'🌏 آسیا',reg_am:'🌎 آمریکا',reg_af:'🌍 آفریقا',ev_title:'گزارش رویدادها',ev_time:'زمان',ev_ev:'رویداد',ev_d:'شرح',ev_empty:'هنوز رویدادی ثبت نشده است.',ev_ago_h:'%1 ساعت پیش',ev_ago_d:'%1 روز پیش',ip_import:'وارد کردن نتیجهٔ اسکن',ip_import_ready:'نتیجهٔ اسکن پیش‌پر شد — دکمهٔ «افزودن» را بزن',ip_manual:'افزودن دستی IP',ip_manual_hint:'هر خط یک آی‌پی یا دامنه (پورت اختیاری). با «اول تست» فقط سالم‌ها اضافه می‌شوند — نتیجهٔ تست کارگرِ پنل است، نه ادعای من.',ip_manual_port:'پورت (پیش‌فرض 443)',ip_manual_cc:'کشور (اختیاری، مثل DE)',ip_manual_test:'اول تست، بعد افزودن',ip_manual_add:'افزودن',ip_manual_none:'چیزی برای افزودن نبود',ip_manual_dead:'هیچ‌کدام زنده نبود — چیزی اضافه نشد', proxyip_import: 'ProxyIPها از Cat Client وارد شد — ذخیره کن',ip_import_hint:'آی‌پی یا دامنهٔ تمیز را اینجا بچسبان (IPv6 هم قبول است: 2606:4700:… یا [2606:4700:…]:443). (هر خط یکی یا با کاما). پورت هم می‌پذیرد: 104.16.1.1:2053#DE — آن IP فقط و فقط روی همان پورتِ تأییدشده ساخته می‌شود، نه پورت‌های دیگر. از دکمهٔ ارسال به پنل در Cat Client یا هر اسکنر دیگری.',
kv_warn_title:'حافظهٔ پنل وصل نیست — تغییرات ذخیره نمی‌شوند',kv_warn_body:'هر تغییری که بدهی (کاربر، IP تمیز، تنظیمات) فقط چند دقیقه می‌ماند و بعد از بین می‌رود؛ ساب هم بدون IP تمیز خالی درمی‌آید. راه‌حل: در اپ Cat Client → «دیپلوی‌های من» → همین دیپلوی → «به‌روزرسانی پنل» را بزن تا حافظه دوباره وصل شود.',kv_warn_ok:'فهمیدم',kv_save_failed:'ذخیره نشد — حافظهٔ پنل وصل نیست',b_empty_title:'ساب خالی است — هیچ کانفیگی ساخته نشد',b_empty_body:'فهرست IPهای اختصاصی پنل خالی است. ورکر فقط در این حالت به آدرس‌های پیش‌فرض و دامنهٔ خودش برمی‌گردد؛ پیش‌نمایش خالی معمولاً یعنی درخواست ساب شکست خورده. جزئیات HTTP زیر را ببین.',b_fetch_error:'گرفتن ساب ساخته‌شده ناموفق بود: %1',b_empty_reply:'پاسخ HTTP هیچ خط کانفیگی نداشت: %1',b_bad_reply:'پاسخ دریافتی ساب کانفیگ نیست: %1',b_user_save_error:'ذخیرهٔ کاربر ناموفق بود: %1',b_user_saved_refresh_error:'کاربر ذخیره شد، اما تازه‌سازی فهرست کاربران شکست خورد: %1',ip_append:'افزودن به لیست',ip_replace:'جایگزینی کل لیست',ip_test_btn:'سلامت و تست',ip_test_reach:'از سمت ورکر سالم',svc_btn:'سرویس‌ها',svc_note:'آزمون اجرای آمریکا (ویژهٔ Gemini/AI): وضعیت باز/ردِ هر سرویس از خروجی فعلی — 404 یعنی قابل‌دسترس',svc_gemini_hint:'جمنای باز نمی‌شود؟ خروجیِ فعلی کلادفلری است و گوگل آن را نمی‌پسندد — یک socks5 خارجی در بخش 🎭 اضافه کن (🧦) و دوباره تست بزن',exit_hint:'وصل شو به کانفیگ، این را باز کن: آی‌پی/کشورِ خروجی، نشت DNS و WebRTC، پینگ جهانی و باز‌بودن جمنای — نیمه‌ای که فقط از خطِ خودت دیده می‌شود',ip_test_hint:'تست واقعی از سمت ورکر: 💦 با همان IP+پورت+TLS+Hostِ قالب کانفیگ؛ 🎯 روی پورتِ خودِ هر ProxyIP؛ 🧦 رله‌های socks5 با هندشیک واقعی socks (و احراز هویت user:pass). پورت 443 داخل کانفیگ 🎯/🧦 پورتِ ورود به ورکر است، نه پورتِ رله. رفتار SNI روی خط خودت فقط با اسکنر اپ دیده می‌شود.',ip_list:'لیست آی‌پی‌های پنل',ip_list_hint:'این‌ها اول هر اشتراک قرار می‌گیرند. برای حذف روی هر مورد بزن.',ip_clear:'پاک کردن همه',copy_all:'کپی همه',cc_title:'کشورها',cc_why:'هر آدرس را با کشوری که برای تو از آن خارج می‌شود برچسب بزن (از اسکنر Cat Client به شکل ip#DE بچسبان، یا دستی از منوی هر آی‌پی). روی یک کشور بزن تا کانفیگ‌ها فقط از همان کشور باشند؛ اگر همهٔ آی‌پی‌های آن کشور بسته شوند، به سریع‌ترین کشور دیگر می‌رود.',cc_auto:'🤖 خودکار (همهٔ کشورها)',cc_fallback:'وقتی همهٔ آی‌پی‌های کشور انتخابی بسته شد',cc_fb_auto:'برو سریع‌ترین کشور دیگر (پیشنهادی)',cc_fb_none:'هیچ‌وقت کشور عوض نشود (قطع شود)',cc_proxy:'Proxy IP‌ها',cc_proxy_btn:'🌍 تشخیص کشور Proxy IP‌ها',cc_hint:'در Clash/Mihomo و Cat Client جابه‌جایی خودکار است؛ در V2Box/sing-box کشور پیش‌فرض انتخاب می‌شود و بقیه در لیست می‌مانند. لینک فقط-یک-کشور: دکمهٔ 🔗 کنار هر کشور (?country=XX&strict=1).',cc_untagged:'بدون کشور',cc_link:'لینک فقط این کشور',
settings:'تنظیمات پنل',s_title:'عنوان پنل',s_lang:'زبان',s_pass:'رمز پنل',s_stealth:'مسیر مخفی پنل',s_stealth_ph:'خالی = مخفی‌کاری خاموش',s_stealth_hint:'با تنظیم این مسیر، آدرس اصلی پنل یک ۴۰۴ خنثی می‌دهد و پنل فقط روی /این‌مسیر بالا می‌آید — جلوی ربات‌های اسکن workers.dev را می‌گیرد. لینک جدید بعد از ذخیره: دامنه/مسیر',s_pass_ph:'خالی = بدون تغییر',s_protocols:'پروتکل‌ها',p_vless:'سبک و پرسرعت',p_trojan:'جایگزین امن',
s_tls:'پورت‌های TLS',s_plain:'پورت‌های بدون TLS (HTTP)',s_plain_on:'کانفیگ‌های بدون TLS هم ساخته شود',s_sni:'SNI / Host',s_sni_ph:'خالی = آدرس ورکر (پیشنهادی؛ کلادفلر فقط همین را می‌پذیرد) — فقط وقتی «اسپوف SNI» روشن است اعمال می‌شود',s_fp:'فینگرپرینت TLS',s_limit:'حداکثر کانفیگ در هر ساب',s_ech:'ECH (پنهان‌سازی SNI)',s_ech_ph:'cloudflare-ech.com+udp://1.1.1.1',s_ech_hint:'مقدار ECH برای کانفیگ‌های TLS؛ با ?ech=1 اعمال می‌شود. خالی = پیش‌فرض مشترک کلادفلر (cloudflare-ech.com+udp://1.1.1.1) که SNI واقعی را روی هر میزبان پشت کلادفلر رمز می‌کند؛ auto = فقط رکورد خود SNI؛ off = خاموش',s_port_bad:'پورت نامعتبر — عددی بین ۱ تا ۶۵۵۳۵ بزن',
s_flags:'گزینه‌ها',s_health:'مرتب‌سازی ساب بر اساس Ping اسکنر',s_health_hint:'آیتم‌هایی که اسکنر طی ۷ روز اخیر از خط فرستنده سریع‌تر گزارش کرده، جلو می‌آیند؛ نامعلوم‌ها بعدتر. این اندازه‌گیری فقط همان خط است، نه تضمین برای کاربرهای دیگر.',s_defaults:'افزودن آدرس‌های پیش‌فرض بعد از لیست من',s_host:'خود آدرس ورکر هم به‌عنوان آدرس اضافه شود',s_proxy:'Proxy IP (برای سایت‌های پشت کلودفلر)',s_proxy_ph:'هر خط یکی: 1.2.3.4 یا 1.2.3.4:8443 (رلهٔ CF) یا socks5://user:pass@ip:port یا لینک t.me/socks تلگرام (رلهٔ شخصی — خروج برای جمنای و هر سایت)',s_proxy_hint:'هر خط یک مورد: host یا host:port (رلهٔ کلودفلر؛ فقط وقتی مقصد خودش پشت کلودفلر باشد) یا socks5://ip:port (برای هر مقصدی). متن بعد از # توضیح است و نادیده گرفته می‌شود (مثل socks5://ip:port#SOCKS5 ip از اسکنر اپ). موارد نامعتبر نادیده گرفته و اعلام می‌شوند.',s_route:'مسیریابی',s_iran:'سایت‌ها و اپ‌های ایرانی مستقیم (بدون VPN) — اسنپ، بانک، دیجی‌کالا عادی کار می‌کنند',s_ads:'مسدودسازی تبلیغات (شبکه‌های تبلیغاتی)',s_quic:'مسدودسازی QUIC/HTTP3 (UDP 443) — مثل BPB؛ بعضی اپراتورها UDP را خراب می‌کنند، بلاکش کلاینت را به TCP می‌فرستد',s_dom2ip:'دامنه‌ها در ساب به آی‌پی خام کلادفلر تبدیل شوند (ساب بدون DNS — پیشنهادی). با ?dom=1 هم می‌توان دامنه‌ای گرفت',s_snir:'اسپوف SNI (چرخش استخر / SNI دلخواه) — خاموش (پیش‌فرض) = SNI همان آدرس ورکر است. ⚠️ کلادفلر SNI متفاوت با Host را با ۴۰۳ رد می‌کند؛ فقط برای آزمایش یا دامنهٔ سفارشی خودت روشن کن. با ?sni=دامنه می‌توان یک لینک را pin کرد',s_fml:'فرگمنت و cs داخل لینک‌های TLS (fm/cs — دور زدن فیلتر SNI در کلاینت‌های نو: PattNG، v2rayNG جدید، Streisand)',s_snir_hint:'فقط وقتی اسپوف روشن است: استخر SNI (با کاما)؛ خالی = icook.tw، speedtest، cdnjs، visa، speed.cloudflare، wto، shopify. برای پنهان‌کردن آدرس ورکر از DPI به‌جای اسپوف از ECH (گزینهٔ «ECH» در ساخت ساب) یا پورت‌های بدون TLS استفاده کن.',i_snipool_ph:'استخر SNI — مثل: icook.tw,www.visa.com,time.is',s_route_hint:'در خروجی Clash / sing-box / Xray اعمال می‌شود. لینک‌های ساده vless:// قانون ندارند؛ آن‌ها را کلاینت تعیین می‌کند (Cat Client خودش همین‌ها را دارد).',s_frag:'Fragment و TLS پیشرفته',s_frag_on:'Fragment فعال (شکستن TLS ClientHello برای عبور از فیلتر)',s_frag_confirm:'Fragment روی همهٔ لینک‌های «Xray کامل» و sing-box اعمال می‌شود (بعد از ذخیره). روی بعضی اپراتورها سرعت کمی کم می‌شود. فعال شود؟',s_frag_hint:'Fragment و Cipher suites فقط در لینک «Xray کامل» و sing-box اعمال می‌شود (لینک ساده نمی‌تواند حمل‌شان کند). ALPN را روی http/1.1 بگذار؛ h2 روی WebSocket کلودفلر کار نمی‌کند.',s_tg:'ربات تلگرام',s_tg_admins:'آیدی عددی ادمین‌ها',s_tg_hook:'🤖 اتصال ربات (Webhook)',s_tg_hint:'از @BotFather یک ربات بساز و توکنش را اینجا بگذار؛ آیدی عددی‌ات را از @userinfobot بگیر. اول ذخیره کن، بعد «اتصال ربات». دستورها: /users /add /renew /toggle /del /link /ips /country /status. تا پیامی نفرستی هیچ هزینه‌ای ندارد.',tg_ok:'وصل شد',tg_off:'غیرفعال',s_gh_title:'🚀 دیپلوی خودکار (ربات ← GitHub Actions ← کلادفلر)',s_gh_repo:'مخزن گیت‌هاب (owner/repo)',s_gh_ref:'برنچ دیپلوی',s_gh_pat:'توکن گیت‌هاب (Actions: read/write)',s_gh_wf:'فایل ورک‌فلو',s_gh_hint:'در تلگرام: /deploy [برنچ] و /deploys. توکن کلادفلر هیچ‌وقت اینجا وارد نمی‌شود — فقط یک‌بار در GitHub Secrets (CLOUDFLARE_API_TOKEN، CLOUDFLARE_ACCOUNT_ID، TELEGRAM_BOT_TOKEN، TELEGRAM_CHAT_ID). راهنمای کامل: docs/telegram-deploy.md',s_chain:'خروجی ثابت (IP و کشور ثابت)',s_chain_ph:'socks5://… یا http://… یا vless://… یا trojan://… یا t.me/socks',s_chain_hint:'ورکر همهٔ ترافیک را از این سرور بیرون می‌فرستد؛ IP و کشور همیشه یکی می‌ماند. پشتیبانی: socks5/http (با user:pass) · vless/trojan (ws، httpupgrade، tcp؛ TLS خودکار) · لینک t.me/socks. reality و flow پشتیبانی نمی‌شوند. خالی = خروجی خود کلودفلر.',s_chain_mode:'کدام مقصدها',s_chain_all:'همهٔ سایت‌ها (کاملاً ثابت)',s_chain_cf:'فقط سایت‌های پشت کلودفلر (به‌جای Proxy IP)',s_chain_strict:'سخت‌گیرانه',s_chain_strict_on:'اگر سرور زنجیره در دسترس نبود، قطع شو (نشت نکن)',s_chain_test:'🧪 تست زنجیره',chain_off:'غیرفعال',chain_ok:'وصل شد',chain_fail:'ناموفق',
save:'ذخیره تغییرات',cancel:'انصراف',saved:'ذخیره شد',
n_clients:'کاربران',n_inbounds:'اینباندها',n_about:'درباره',n_logout:'خروج',ov_info:'اطلاعات پنل',ov_loc:'موقعیت',ov_up:'آپتایم',ov_ver:'نسخه',ov_check:'بررسی آپدیت',ov_services:'سرویس‌ها',svc_run:'فعال',svc_idle:'خاموش',ib_count:'اینباندها',ib_ports:'پورت‌ها',ib_inbound:'اینباند',ib_copy:'کپی لینک ساب',ib_hint:'لینک کپی‌شده فقط کانفیگ‌های همان پروتکل و پورت را می‌دهد (?proto=&port=). ترافیک روی Cloudflare Workers قابل شمارش نیست.',bulk_count:'چند کاربر ساخته شود؟',bulk_prefix:'پیشوند نام (مثلاً user)',bulk_done:'ساخته شد: ',n_spoof:'SNI و ProxyIP',spoof:'SNI و ProxyIP (اسپوف)',spoof_hint:'کانفیگ‌های این بخش جدا از ایپی‌های تمیز و با نام مخصوص خودشان ساخته می‌شوند: 🧬 SNI … و 🎯 PX … — اول «ذخیره تغییرات» را بزن، بعد ساب را دوباره آپدیت کن. لینک‌های «کانفیگ‌ساز» هم 🎯 PX و 🧦 را می‌گیرند و جزو «تعداد کانفیگ» حساب می‌شوند (حداکثر نصف آن)؛ فقط لینک‌های پین‌شده (limit ۱ یا ۲، یا addr=) بدون آن‌ها هستند. 🧬 فقط در ساب بدون limit می‌آید.',s_extra_sni:'SNIهای اضافه (هر خط یکی — حداکثر ۸)',s_extra_sni_ph:'speedtest.example.com',s_extra_sni_hint:'برای هر دامنه یک کانفیگ با servername همان دامنه ساخته می‌شود (دامنه باید پشت کلادفلر باشد) — وقتی SNI دامنه‌ی خودت فیلتر شده. اسپوف SNI.',pattn_btn:'PattN ✨',pattn_filled:'پیش‌تنظیم PattN پر شد — cipher suites + ALPN http/1.1 + fingerprint=unsafe + Fragment — حالا ذخیره کن',saved_nokv:'ذخیره شد (موقت — KV وصل نیست!)',paths:'مسیرها و اتصال',
backup:'پشتیبان‌گیری',backup_hint:'یک فایل JSON شامل تنظیمات و کاربران. برای انتقال پنل به ورکر/اکانت دیگر همین فایل را بازگردانی کن.',backup_dl:'دانلود پشتیبان',backup_up:'بازگردانی',
limits:'چرا این نسخه بن نمی‌شود؟',limits_text:'کلودفلر رایگان: ۱۰۰هزار درخواست/روز، ۱۰ms CPU برای هر درخواست، ۱۰۰۰ نوشتن KV/روز. نسخهٔ ۶ هیچ آمار مصرفی در KV نمی‌نویسد (فقط وقتی تو ذخیره می‌زنی)، هیچ اسکنی داخل ورکر انجام نمی‌دهد، و رلهٔ ترافیک یک pipe ساده بدون شمارنده است. نتیجه: مصرف CPU و KV نزدیک صفر، مثل BPB.',
about_text:'پنل تک‌فایلی Cat برای Cloudflare Worker. نسخهٔ lean: بدون حسابداری ترافیک، بدون اسکن سمت سرور، رلهٔ کم‌مصرف. مجوز GPL — سورس در گیت‌هاب.',
n_dash:'داشبورد',n_scan:'اسکنر IP',n_nodes:'نودها',n_manage:'مدیریت',no_ips:'هنوز IP تمیز اختصاصی نداری — اگر فهرست مؤثر خالی باشد، ساب به آدرس‌های پیش‌فرض و دامنهٔ ورکر برمی‌گردد. افزودن IPهای فید عمومی به ساب جداست و فقط با روشن‌کردن «افزودن خودکار از مخزن‌ها» انجام می‌شود. IPهای تست‌شده را اضافه کن تا اول ساب بیایند:',n_set:'تنظیمات',n_settings:'تنظیمات',n_backup:'پشتیبان‌گیری',s_backup:'پشتیبان‌گیری و بازیابی پنل',n_bak:'پشتیبان',
d_new:'کاربر جدید',d_edit:'ویرایش کاربر',d_sub:'نام، پروتکل‌ها و مدت اعتبار',u_name:'نام کاربری',u_rand:'تصادفی',u_protocols:'پروتکل‌های مجاز',u_days:'مدت اعتبار (روز) — ۰ یعنی نامحدود',u_note:'یادداشت',u_enabled:'فعال',
u_noquota:'این نسخه حجم مصرفی را نمی‌شمارد (شمارش حجم همان چیزی بود که KV را پر و ورکر را بن می‌کرد). محدودیت فقط زمانی است.',
unlimited:'نامحدود',days:'روز',left:'مانده',expired:'منقضی',disabled:'غیرفعال',active:'فعال',copied:'کپی شد',deleted:'حذف شد',confirm_del:'این کاربر حذف شود؟',ask_cancel:'انصراف',d_dash:'وضعیت لحظه‌ای: کاربرها، سرویس و سلامت اتصال',d_clients:'ساخت کاربر و لینک ساب هر کس',d_inbounds:'پورت‌ها و مسیرهای اتصال (vless/trojan)',d_scan:'پیدا کردن IP تمیز کلودفلر با تست سرعت',d_build:'ساخت کانفیگ و ساب با فرمت دلخواه',d_nodes:'لیست IPهای تمیز و مدیریت آن‌ها',d_spoof:'SNI و ProxyIP — عبور از فیلتر SNI',d_settings:'تنظیمات کلی، زنجیرهٔ خروجی و ربات',d_backup:'بکاپ و بازگردانی کل تنظیمات پنل',d_about:'نسخه، آپدیت و کلاینت‌های پیشنهادی',ip_clear_confirm:'همهٔ آی‌پی‌های لیست پاک شوند؟',rec_title:'اگر 1101 دیدی (بن کلادفلر)',rec_hint:'ارور 1101 معمولاً استثنا نیست — یعنی کلادفلر کد ورکر را اسکن و دیسیبل کرده. این نسخه کدش مبهم‌سازی‌شده دیپلوی می‌شود و این مسیر را نمی‌بینی؛ اگر نسخهٔ قدیمی‌ای هنوز بالا است:',rec_steps:'۱) ابزارها ← بکاپ بگیر ← ۲) ورکر بن‌شده را در کلادفلر پاک کن ← ۳) ساب‌دامینه را از Workers & Pages ← Subdomain عوض کن (یا اکانت تازه) ← ۴) از /deploy یا Actions دوباره دیپلوی کن ← ۵) بکاپ را ری‌استور کن ← ۶) از کارت «دامنهٔ اختصاصی» پایین، دامنهٔ خودت را وصل کن',ech_has:'⚡ ECH دارد — خودکار داخل ساب اعمال می‌شود',ech_absent:'این SNI فعلاً ECH ندارد (خاموش)',ccq_title:'کیفیت خروجی کشورها',ccq_hint:'نمونه‌گیری شبانه (۸ کشور در هر شب، چرخشی) از لیست آی‌پی‌های خودت — P50/P95 به ms؛ سبز = سریع',ccq_btn:'به‌روزرسانی',ccq_empty:'هنوز داده‌ای نیست — بعد از cron شبانه بیا',v6p_title:'مخزن IPv6 داخلی',v6p_hint:'۱۱ آدرس anycast کلادفلر — روی هر شبکهٔ v6داری جواب می‌دهند؛ با «اول تست» پنل می‌توانی زنده بودن‌شان را هم چک کنی',v6p_btn:'افزودن ۱۱ آدرس v6',dom_title:'دامنهٔ اختصاصی',dom_hint:'چک می‌کند دامنه‌ات روی کلادفلر است یا نه — بدون هیچ توکنی',dom_ph:'panel.example.com',dom_check:'چک',dom_need:'دامنه را بنویس',dom_yes:'روی کلادفلر است — آمادهٔ Workers Custom Domain ✓',dom_no:'روی کلادفلر نیست — اول دامنه را به یک اکانت کلادفلر اضافه کن',dom_steps:'مسیر: کلادفلر ← Workers & Pages ← ورکر تو ← Settings ← Domains & Routes ← Add ← Custom domain — بعد از چند دقیقه با همین چک سبز می‌شود',ai_title:'اثبات سرویس‌ها',ai_hint:'اتصال ورکر به سرویس‌های AI — ۴۰۳/۴۰۴ یعنی مسیر باز است (احراز بعداً در اپ انجام می‌شود)، ۲۰۰ یعنی کامل باز.',ai_btn:'تست سرویس‌ها',ai_ok:'پذیرفته',ai_pre:'پیش‌پروفه',ai_fail:'ناموفق',g_ident:'هویت و نمایش',g_sec:'امنیت و دسترسی',g_conn:'اتصال: پروتکل و پورت',g_sni:'SNI و اثر انگشت',g_route:'مسیریابی و قوانین',g_frag:'فرگمنت و TLS پیشرفته',g_chain:'خروجی ثابت (زنجیره)',g_tg:'ربات تلگرام',g_gh:'دیپلوی خودکار',g_save:'ذخیره و خروجی',set_export:'خروجی تنظیمات (فایل)',set_import:'بازگردانی تنظیمات',set_import_bad:'فایل معتبر نیست',clients_title:'کلاینت‌های پیشنهادی',clients_hint:'لینک ساب پنل در همهٔ این اپ‌ها کار می‌کند — صفحهٔ رسمی دانلود:',chain_exit:'خروجی',renew:'تمدید ۳۰ روز',toggle:'فعال/غیرفعال',edit:'ویرایش',del:'حذف',qr:'QR',info:'صفحهٔ کاربر',
kv_on:'KV متصل',kv_off:'KV وصل نیست — داده‌ها ذخیره نمی‌شوند!',pass_uuid:'رمز = UUID (تغییرش بده!)',pass_env:'رمز از ENV',pass_set:'رمز تنظیم شده',pass_open:'پنل باز است — رمز بگذار!',
self_wait:'در حال دریافت…',browser_note:'تست از شبکهٔ خودت (مرورگر): دامنه‌ها با /cdn-cgi/trace و آی‌پی‌های خام با اتصال TLS سنجیده می‌شوند — جواب سریع = روی خط تو زنده است، تایم‌اوت = ✗ مرده روی خط تو. پورت‌های بدون TLS (مثل 80/8080) در مرورگر قابل تست نیستند (⊘). پروکسی‌آی‌پی‌ها را با «تست با کارگر» بسنج — مسیرشان از ورکر است.',scan_prune:'حذف مرده‌ها از لیست من',scan_prune_confirm:'%1 آی‌پی مرده (از دید خط تو) از لیست حذف شود؟',scan_prune_none:'اول «تست از شبکهٔ من» را بزن — نتیجه‌ای برای حذف نیست',scan_sum_alive:'آی‌پی زنده روی خط تو',scan_sum_dead:'مرده',scan_sum_skip:'پورت غیرTLS',ws_test:'تست واقعی اتصال (دامنه‌ها)',ws_note:'برای هر دامنه یک WebSocket واقعی به مسیر تونل (/ws) باز می‌شود — دقیقاً همان مسیری که کانفیگ می‌رود: باز شدن = کانفیگ روی خط تو واقعاً وصل می‌شود. آدرس خود پنل همیشه اول تست می‌شود (مبنا). آی‌پی خام در مرورگر «تست واقعی» ندارد چون مرورگر نمی‌تواند هدر Host بفرستد؛ دامنه‌ای که به ورکر وصل نباشد هم ✗ می‌خورد.',ws_base:'مبنا — خود پنل',ws_ok:'مسیر کانفیگ باز شد',ws_fail:'وصل نشد — مرده روی خط تو، یا به ورکر وصل نیست',ws_sum:'دامنه با اتصال واقعیِ تونل تأیید شد',ws_noway:'این مرورگر WebSocket ندارد',surv_title:'بستهٔ بقا — جایگزین‌های بیشتر، نه تضمین',surv_hint:'یک کلیک: چرخش روزانه، هر دو پروتکل، آدرس‌های پیش‌فرض و خود ورکر، پورت‌های جایگزین 80/8080 و ترتیب بر اساس Ping اسکنر در ۷ روز اخیر. SNI روی نام خود ورکر می‌ماند؛ SNI جعلی/چرخشی ممکن است خود Cloudflare را 403 کند. Fragment فقط در Xray/sing-box پشتیبانی می‌شود. هیچ‌کدام تضمین ۱۰۰٪ برای همهٔ اپراتورها و کلاینت‌ها نیست؛ لینک را روی سیم‌کارت/وای‌فای و برنامهٔ واقعی کاربرها جدا تست کن. Ping فقط از خطی است که اسکن را فرستاده، نه همهٔ کاربران.',surv_apply:'اعمال بستهٔ بقا',surv_done:'🛡 بستهٔ بقا اعمال شد',surv_applied:'اعمال شد',surv_changed:'مورد تغییر کرد',surv_already:'بستهٔ بقا از قبل فعال است',surv_link:'لینک بقا',surv_formats:'فرمت متناسب با کلاینت را انتخاب و کپی کن:',ov_seen:'ورود احراز‌شدهٔ کاربران:',ov_seen_24:'وصل در ۲۴ ساعت اخیر',ov_seen_never:'هنوز اتصالی ثبت نشده (شاید کاربر استفاده نکرده)',ov_seen_hint:'این آمار فقط تلاش تونل با شناسهٔ معتبر را می‌شمارد، نه اینکه سایت مقصد باز شده باشد. کلودفلر هم IP لبهٔ انتخاب‌شده را نمی‌گوید؛ برای اثبات کارکرد کامل یا حذف IP کافی نیست.',
update_check:'بررسی نسخهٔ جدید…',update_ok:'آخرین نسخه را داری',update_new:'نسخهٔ جدید موجود است: ',update_how:'از تب «پنل من» در Cat Client یا با چسباندن فایل جدید در Workers به‌روزرسانی کن.',update_how2:'⬇️ را بزن تا worker.js جدید از خود پنل دانلود شود (گیت‌هاب لازم نیست). بعد در کلادفلر: Workers → پنلت → Edit code → کل کد را با فایل جدید عوض کن → Deploy.',
sync_hint:'اشتراک اصلی را در Cat Client باز می‌کند',restore_ok:'بازگردانی شد',restore_bad:'فایل نامعتبر',sub:'ساب',clash:'Clash',singbox:'sing-box'},
en:{stats:'Panel status',st_users:'Users',st_users_s:'defined in panel',st_active:'Active',st_active_s:'not expired / disabled',st_exp:'Expired / disabled',st_exp_s:'need renewal',st_ips:'Clean IPs',st_cfg:'Configs per sub',
master_links:'Master subscription links',self:'My connection info',users:'Users',search:'Search name or UUID…',f_all:'All',f_active:'Active',f_expired:'Expired',f_disabled:'Disabled',s_new:'Newest',s_exp:'Expiring soon',s_name:'Name',
h_user:'User',h_proto:'Protocol',h_links:'Sub links',h_time:'Time',h_seen:'Last online',h_status:'Status',h_act:'Actions',seen_never:'never',seen_now:'now',seen_min:'%1 min ago',no_users:'No users yet — tap + to create one.',
scan_title:'Clean IP & scanner',scan_why:'Scanning runs on YOUR device, not inside the worker — exactly what BPB and ZEUS do. The worker spends zero requests and results reflect your real network.',
scan_app:'Scan with Cat Client',scan_browser:'Test from my network (browser)',scan_guide:'Scanner guide',warp_title:'WARP on Xray output (your own keys)',warp_hint:'Your connection to the panel rides inside your own WARP tunnel (your real IP stays hidden even from the worker). Grab keys from wgcf or an Aether export — the worker never registers with Cloudflare (nothing to ban). With WARP on, fragment is bypassed (the tunnel is UDP).',warp_mode:'Mode',warp_off:'Off',warp_chain:'WARP-in-WARP (chained)',warp_sk:'WireGuard SecretKey',warp_pk:'Peer PublicKey (Cloudflare)',warp_reserved:'reserved (optional, comma sep)',warp_endpoint:'Endpoint',warp_warn:'Never paste keys that are not yours. Set mode to Off to disable temporarily — keys are kept.',ext_title:'External subs (merged into yours)',ext_hint:'External sub content is served through the panel\u2019s own domain (raw.github is unreachable from Iran) + URI-list subs are appended after your own configs. ?noext=1 = skip them.',ext_add:'Add sub',ext_preset:'Serverless preset (PattNG)',ext_preset_free:'Patterniha free configs',ext_core:'The Serverless sub needs a recent Xray core (PattNG or v2rayNG ≥2.2.6) and must be imported directly into the app, not merged into the panel sub.',ext_empty:'No external subs yet — try the Serverless preset.',ext_name:'Name',ext_url:'https sub URL',mitm_title:'MITM + DomainFronting (client-side)',mitm_body:'Patterniha\u2019s method to open YouTube/Instagram/WhatsApp/Facebook/Reddit directly without a server — set up on the device (Win/Linux/mac/Android, no root). Create a PERSONAL certificate and trust it. Full guide:',aether_title:'Aether special configs (PattNG)',aether_hint:'Builds aether:// links — the open button launches PattNG (Aether core) directly. Single WARP, WARP-in-WARP (Gool) and MASQUE/HTTP-2 with fragment.',aether_mode:'Type',aether_gool:'WARP-in-WARP (Gool)',aether_name:'Config name',aether_family:'Address family',aether_open:'Add to PattNG',pp_countries:'Add a country repo (Wanwu):',px_addrs:'Import healthy pool as connection IPs',px_addrs_hint:'Healthy pool ProxyIPs are added to your connection-IP list with their country tags and get 💦 configs. Not every ProxyIP also works as an entry address — test/filter them with the app or scanner after importing.',px_none:'Nothing to import — hit pool Refresh first',b_ech:'ECH (encrypted ClientHello — ECH/SIIT-style configs) — per field reports (Oct 2026), the single most important switch to stay connected',b_ech_off:'Off',ech_none:'Current SNI has no ECH (or DNS unreachable) — keep it off',rot_btn_off:'Fixed IP (rotation ON — tap to freeze)',rot_btn_on:'Fixed IP active (tap to resume rotation)',rot_fixed_lbl:'Fixed IP',rot_rot_lbl:'Rotating',rot_now_fixed:'📌 Rotation off — IPs stay fixed',rot_now_rotating:'⚡ Rotation on — fresh set every update',hero_inuse:'In use right now',hero_ports:'Ports',hero_sni_host:'worker host ✓',hero_sni_pool:'rotating pool',px_ignored:'Ignored (neither a ProxyIP nor a socks5:// proxy):',hero_ips:'Clean IPs',hero_px:'ProxyIP relays',hero_exit:'Fixed exit',hero_rot:'Rotation',hero_warn:'⚠️ SNI defaults to skk.moe — SNI rotation (one different SNI per config) lowers the risk, but if your carrier blocks skk.moe, still scan a healthy SNI on your line and hit “⚡ Make panel main SNI”.',hero_build:'Builder',ip_pin:'Pin as fixed IP (always first in sub)',ip_unpin:'Unpin',pin_saved:'📌 This IP stays first — even with rotation on',pin_removed:'Pin removed',s_rot:'Auto config rotation',s_rot_off:'Stable (BPB-like)',s_rot_fetch:'Every update',s_rot_daily:'Daily',s_rot_hint:'Each sub refresh reshuffles the IP order and numbering — a fresh set every time. Daily keeps one arrangement per day; Stable keeps the classic order.',pp_title:'ProxyIP repos (12h auto-update)',pp_hint:'Public ProxyIP feeds (IPs or domains); refreshed every 12h. A ProxyIP is the relay address for opening Cloudflare-fronted sites; IPs reported dead 3× are replaced.',pp_cc:'ProxyIP pool countries — + adds 8 of that country to the panel ProxyIP list',pp_auto:'Auto-append 6 fresh ProxyIPs to subs',pp_src:'Sources: xgonce/Cloudflare_IP · wanwushequ/ProxyIP',pp_dead_note:'Dead? POST /api/prepos {action:"health",dead:[…]} ×3 — replaced automatically.',rp_title:'Repos (12h auto-update)',rp_refresh:'Refresh',rp_hint:'Public clean-IP feeds; refreshed every 12 hours (Cloudflare cron + panel open). IPs reported dead 3× are dropped and replaced on the next refresh.',rp_cc:'Repo pool countries — + adds 16 IPs of that country to your panel list',rp_auto:'Auto-append 8 fresh IPs to subs',rp_add:'Add repo',rp_add_url:'Raw repo URL (https://…)',rp_add_name:'Repo name',rp_empty:'Repo pool is empty — hit Refresh.',rp_nokv:'no KV, not persisted',rp_src:'Sources: arista-project/cf-clean-ips · imatixofficel/Scanner-matix',rp_dead_note:'Dead IP? POST /api/repos {action:"health",dead:[…]} three times — it gets replaced automatically.',n_build:'Config builder',b_title:'Config builder',b_hint:'Build a precise subscription link per carrier, country and port set — never touches the main panel settings.',b_isp:'Carrier profile (advisory — test on your line)',isp_mtn:'Irancell (MTN)',isp_mci:'MCI (Hamrah-e Aval)',isp_rtl:'Rightel / Shatel',isp_tdsl:'TCI',isp_direct:'Direct / Auto',b_isp_mtn_n:'Irancell: keep fragment ON; ports 443 & 8443 with chrome fingerprint.',b_isp_mci_n:'MCI: 443 & 2053; if you have IPv6 keep the family on Both.',b_isp_rtl_n:'Rightel/Shatel: plain ports (80/8080) often work better; short fragment.',b_isp_tdsl_n:'TCI: 443 with iOS fingerprint is usually the most stable.',b_isp_direct_n:'No preset applied — choose the filters yourself.',b_proto:'Protocol',b_fam:'Address family',b_both:'Both',b_ports:'Ports (multi)',b_cc:'Exit country',b_cc_all:'All countries',b_limit:'Config count (1–200)',b_strict:'Country behaviour',b_fb_ok:'Fall back to others',b_only:'Only this country',b_gen:'Build live sub',b_copy:'Copy all',b_link:'Built subscription link',b_prev:'Live preview (first lines)',b_open:'Open in',b_frag:'Fragment & fingerprint (client-side settings)',b_frag_hint:'Fragment is NOT carried in the link — set it in your client (v2rayNG: edit config → Fragment). The value follows the carrier profile.',b_fp:'TLS fingerprint',scan_cat:'IP category',scan_region:'Region',scan_cc:'Panel list countries',scan_search:'Search country',scan_search_ph:'Germany or DE…',scan_cidr:'Add from CIDR range or domain',scan_cidr_ph:'104.16.0.0/24 or cdn.example.com',cidr_add:'Add to list',cidr_ok:'Added %1 IPs',cidr_bad:'Invalid range (example: 104.16.0.0/24)',loc_now:'Current exit',loc_refresh:'Refresh',loc_fail:'Could not read location',scan_jump:'⚙ Sub for this country only',scan_empty:'No IPs match this filter.',scan_note_browser:'“Test from my network” probes domains and raw IPs directly from your line (browser connections) and can prune the dead ones from your list with one tap. ProxyIPs route via the worker — use the worker test for those.',reg_eu:'🇪🇺 Europe',reg_me:'🕌 Middle East',reg_as:'🌏 Asia',reg_am:'🌎 Americas',reg_af:'🌍 Africa',ev_title:'Events log',ev_time:'Time',ev_ev:'Event',ev_d:'Detail',ev_empty:'No events yet.',ev_ago_h:'%1 h ago',ev_ago_d:'%1 d ago',ip_import:'Import scan results',ip_import_ready:'Scan results pre-filled — press Append',ip_manual:'Add IPs manually',ip_manual_hint:'One IP or domain per line (port optional). With “test first”, only healthy ones are added — tested by the panel worker, not taken on faith.',ip_manual_port:'Port (default 443)',ip_manual_cc:'Country (optional, e.g. DE)',ip_manual_test:'Test first, then add',ip_manual_add:'Add',ip_manual_none:'Nothing to add',ip_manual_dead:'None of them answered — nothing added', proxyip_import: 'ProxyIPs imported from Cat Client — press Save',ip_import_hint:'IPv6 accepted too (2606:4700:… or [2606:4700:…]:443). Paste clean IPs or domains (one per line or comma separated). A port may be pinned too: 104.16.1.1:2053#DE — that address is emitted only on its verified port. From Cat Client (Send to Cat Panel) or any other scanner.',
b_empty_title:'The subscription is empty — no config was built',b_empty_body:'The custom IP list is empty. The Worker falls back to built-in addresses + its hostname; a blank preview usually means the subscription request failed. Check the HTTP detail below.',b_fetch_error:'Could not fetch the generated subscription: %1',b_empty_reply:'The HTTP response contained no config lines: %1',b_bad_reply:'The response is not a config subscription: %1',b_user_save_error:'Could not save user: %1',b_user_saved_refresh_error:'User was saved, but the user list could not be refreshed: %1',kv_warn_title:'Panel storage is not attached — changes are not saved',kv_warn_body:'Any change (users, clean IPs, settings) survives only a few minutes and then disappears; subscriptions also come out empty without clean IPs. Fix: in the Cat Client app → My deployments → this deployment → “Update panel”, which re-attaches storage.',kv_warn_ok:'Got it',kv_save_failed:'Not saved — panel storage is not attached',ip_append:'Append',ip_replace:'Replace list',ip_test_btn:'Health & test',ip_test_reach:'healthy from the worker',svc_btn:'Services',svc_note:'US egress test (Gemini/AI focus): open/refused per service through the current exit — 404 means reachable',svc_gemini_hint:'Gemini blocked? The current exit is Cloudflare and Google refuses it — add a FOREIGN socks5 in the 🎭 section (🧦) and re-run this test',exit_hint:'Connect to a config, then open it: exit IP/country, DNS & WebRTC leaks, global ping and whether Gemini opens — the half only YOUR line can see',ip_test_hint:'Real tests from the worker: 💦 dials the exact IP+port+TLS+Host of the config template; 🎯 is probed on each ProxyIP\u2019s OWN port; 🧦 socks5 relays get a REAL socks handshake (incl. user:pass auth). The 443 inside a 🎯 config is the worker ENTRY port, not the relay port — the relay port rides in ?proxyip= and the worker dials it directly. SNI behaviour on YOUR line only the app scanner sees.',ip_list:'Panel IP list',ip_list_hint:'These come first in every subscription. Tap one to remove it.',ip_clear:'Clear all',copy_all:'Copy all',cc_title:'Countries',cc_why:'Tag each address with the country it exits from FOR YOU (paste ip#DE from the Cat Client scanner, or pick from the menu next to each ip). Click a country to serve configs from it only; when all of its ips die, the fastest other country takes over.',cc_auto:'🤖 Automatic (all countries)',cc_fallback:'When every ip of the chosen country is dead',cc_fb_auto:'switch to the fastest other country (recommended)',cc_fb_none:'never leave the country (fail instead)',cc_proxy:'Proxy IPs',cc_proxy_btn:'🌍 Detect proxy-IP countries',cc_hint:'Clash/Mihomo and Cat Client switch automatically; V2Box/sing-box get the chosen country as default with the rest listed. Single-country link: 🔗 next to each country (?country=XX&strict=1).',cc_untagged:'untagged',cc_link:'link for this country only',
settings:'Panel settings',s_title:'Panel title',s_lang:'Language',s_pass:'Panel password',s_stealth:'Hidden panel path',s_stealth_ph:'empty = stealth off',s_stealth_hint:'When set, the root address answers a neutral 404 and the panel only loads at /this-path — defeats workers.dev scanners. New link after saving: domain/path',s_pass_ph:'empty = unchanged',s_protocols:'Protocols',p_vless:'light & fast',p_trojan:'secure alternative',
s_tls:'TLS ports',s_plain:'Non-TLS ports (HTTP)',s_plain_on:'also emit non-TLS configs',s_sni:'SNI / Host',s_sni_ph:'empty = the worker host (recommended; the only SNI Cloudflare accepts) — used only while SNI spoofing is on',s_fp:'TLS fingerprint',s_limit:'Max configs per sub',s_ech:'ECH (SNI encryption)',s_ech_ph:'cloudflare-ech.com+udp://1.1.1.1',s_ech_hint:'ECH value for TLS configs; applied with ?ech=1. Empty = the shared Cloudflare default (cloudflare-ech.com+udp://1.1.1.1) which encrypts the real SNI on any CF-fronted host; auto = only the SNI own HTTPS record; off = disabled',s_port_bad:'Invalid port — enter a number between 1 and 65535',
s_flags:'Options',s_health:'Order subscription by scanner ping',s_health_hint:'Addresses the importing scanner measured faster within the last 7 days go first; unknowns follow. This is only that sender’s line, not a guarantee for other users.',s_defaults:'append default addresses after mine',s_host:'also include the worker hostname',s_proxy:'Proxy IP (for Cloudflare-hosted sites)',s_proxy_ph:'one per line: 1.2.3.4 · 1.2.3.4:8443 (CF relay) · socks5://user:pass@ip:port · a t.me/socks link (own relay — foreign exit for Gemini & everything)',s_proxy_hint:'One per line: host or host:port (a Cloudflare relay — only used when the destination is itself behind Cloudflare) or socks5://ip:port (used for any destination). Text after # is a remark and is ignored (e.g. socks5://ip:port#SOCKS5 ip from the app scanner). Invalid entries are ignored and reported.',s_route:'Routing',s_iran:'Iranian sites & apps go direct (no VPN) — banking, Snapp, Digikala work normally',s_ads:'Block ads (ad networks)',s_quic:'Block QUIC/HTTP3 (UDP 443) — BPB-style; on carriers where UDP breaks, clients fall back to TCP+TLS',s_dom2ip:'Resolve domain entries to raw Cloudflare IPs in the sub (DNS-proof — recommended). ?dom=1 keeps domains',s_fml:'Fragment and cs inside TLS links (fm/cs — SNI-filter bypass in newer clients: PattNG, new v2rayNG, Streisand)',s_snir:'SNI spoofing (rotating pool / custom SNI) — OFF (default) = the SNI is the worker host. ⚠️ Cloudflare answers 403 when the SNI differs from the Host; enable only to experiment or for your own custom domain. ?sni=<host> pins one link',s_snir_hint:'Only while spoofing is on: SNI pool (comma separated); empty = icook.tw, speedtest, cdnjs, visa, speed.cloudflare, wto, shopify. To hide the worker host from DPI use ECH (the «ECH» toggle in the sub builder) or non-TLS ports instead of spoofing.',i_snipool_ph:'SNI pool — e.g. icook.tw,www.visa.com,time.is',s_route_hint:'Applied to Clash / sing-box / Xray output. Plain vless:// links carry no rules; the client decides (Cat Client has the same rules built in).',s_frag:'Fragment & advanced TLS',s_frag_on:'Fragment on (split the TLS ClientHello to slip past DPI)',s_frag_confirm:'Fragment will apply to every "Full Xray" and sing-box link (after Save). Some carriers get slightly slower. Enable?',s_frag_hint:'Fragment and cipher suites only apply to the "Full Xray" link and sing-box (a share link cannot carry them). Keep ALPN at http/1.1; h2 breaks WebSocket on Cloudflare.',s_tg:'Telegram bot',s_tg_admins:'admin numeric ids',s_tg_hook:'🤖 Connect bot (webhook)',s_tg_hint:'Create a bot with @BotFather and paste its token; get your numeric id from @userinfobot. Save first, then “Connect bot”. Commands: /users /add /renew /toggle /del /link /ips /country /status. Costs nothing until you message it.',tg_ok:'connected',tg_off:'off',s_gh_title:'🚀 Auto-deploy (bot → GitHub Actions → Cloudflare)',s_gh_repo:'GitHub repo (owner/repo)',s_gh_ref:'deploy branch',s_gh_pat:'GitHub token (Actions: read/write)',s_gh_wf:'workflow file',s_gh_hint:'Telegram commands: /deploy [branch] and /deploys. The Cloudflare token is never stored here — it goes once into GitHub Secrets (CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID). Full guide: docs/telegram-deploy.md',s_chain:'Fixed exit (stable IP & country)',s_chain_ph:'socks5://… or http://… or vless://… or trojan://… or t.me/socks',s_chain_hint:'The worker sends all traffic out through this server, so the IP/country never changes. Supported: socks5/http (user:pass) · vless/trojan (ws, httpupgrade, tcp; TLS auto) · t.me/socks links. reality & flow are not supported. Empty = Cloudflare egress.',s_chain_mode:'Which destinations',s_chain_all:'everything (fully stable)',s_chain_cf:'only Cloudflare-hosted sites (instead of Proxy IP)',s_chain_strict:'Strict',s_chain_strict_on:'if the chain is down, fail instead of leaking',s_chain_test:'🧪 Test chain',chain_off:'off',chain_ok:'connected',chain_fail:'failed',
save:'Save',cancel:'Cancel',saved:'Saved',
n_clients:'Clients',n_inbounds:'Inbounds',n_about:'About',n_logout:'Log out',ov_info:'Panel info',ov_loc:'Location',ov_up:'Uptime',ov_ver:'Version',ov_check:'Check for Update',ov_services:'Services',svc_run:'RUNNING',svc_idle:'IDLE',ib_count:'Inbounds',ib_ports:'Ports',ib_inbound:'Inbound',ib_copy:'Copy sub URL',ib_hint:'The copied URL serves only that protocol+port (?proto=&port=). Traffic counting is not possible on Cloudflare Workers.',bulk_count:'How many users?',bulk_prefix:'Name prefix (e.g. user)',bulk_done:'Created: ',n_spoof:'SNI & ProxyIP',spoof:'SNI & ProxyIP (spoofing)',spoof_hint:'Configs from this section are built apart from the clean-IP list under their own names: 🧬 SNI … and 🎯 PX … — press Save first, then refresh the subscription. Config-builder links include 🎯 PX and 🧦 too and they count toward «Config count» (at most half of it); only pinned links (limit 1–2, or addr=) leave them out. 🧬 appears only in subs without a limit.',s_extra_sni:'Extra SNI hosts (one per line — max 8)',s_extra_sni_ph:'speedtest.example.com',s_extra_sni_hint:'Each host gets its own config with that servername (the host must be behind Cloudflare) — for when your own panel SNI gets filtered. SNI spoofing.',pattn_btn:'PattN ✨',pattn_filled:'PattN preset filled — cipher suites + ALPN http/1.1 + fingerprint unsafe + fragment — now press Save',saved_nokv:'Saved (volatile — KV not bound!)',paths:'Paths & connection',
backup:'Backup',backup_hint:'A JSON file with settings and users. Restore it on another worker/account to move the panel.',backup_dl:'Download backup',backup_up:'Restore',
limits:'Why this version does not get banned',limits_text:'Cloudflare free tier: 100k requests/day, 10 ms CPU per request, 1 000 KV writes/day. v6 writes KV only when you save, never scans from the worker, and the relay is a plain pipe with no counters. CPU and KV usage stay near zero, like BPB.',
about_text:'Single-file Cat panel for Cloudflare Workers. Lean edition: no traffic accounting, no server-side scanning, low-CPU relay. GPL — source on GitHub.',
n_dash:'Dashboard',n_scan:'IP Scanner',n_nodes:'Nodes',n_manage:'Manage',no_ips:'No custom clean IPs yet — if the effective list is empty, the subscription falls back to built-in addresses + the worker host. Public feed IPs are appended only when «Auto-add from repositories» is enabled. Add tested IPs to put them first:',n_set:'Settings',n_settings:'Settings',n_backup:'Backup',s_backup:'Panel backup & restore',n_bak:'Backup',
d_new:'New user',d_edit:'Edit user',d_sub:'Name, protocols and validity',u_name:'Username',u_rand:'random',u_protocols:'Allowed protocols',u_days:'Validity (days) — 0 = unlimited',u_note:'Note',u_enabled:'Enabled',
u_noquota:'This version does not meter traffic (traffic metering is what filled KV and got workers throttled). Limits are time-based only.',
unlimited:'unlimited',days:'days',left:'left',expired:'expired',disabled:'disabled',active:'active',copied:'Copied',deleted:'Deleted',confirm_del:'Delete this user?',ask_cancel:'Cancel',d_dash:'Live status: users, service, connection health',d_clients:'Create users & their sub links',d_inbounds:'Ports & connection paths (vless/trojan)',d_scan:'Find clean Cloudflare IPs with speed test',d_build:'Build configs & subs in any format',d_nodes:'Clean IP list & management',d_spoof:'SNI & ProxyIP — slip past SNI filtering',d_settings:'General, chain exit & Telegram bot',d_backup:'Backup & restore the whole panel',d_about:'Version, update & supported clients',ip_clear_confirm:'Clear every IP from the list?',rec_title:'Seeing 1101? (Cloudflare ban)',rec_hint:'Error 1101 usually means no exception at all — Cloudflare scanned the worker code and disabled it. This version deploys obfuscated and should not hit that path; if an OLD readable deploy is still up:',rec_steps:'1) Tools → take a backup ← 2) delete the banned worker in Cloudflare ← 3) change the subdomain under Workers & Pages ← Subdomain (or use a fresh account) ← 4) redeploy from /deploy or Actions ← 5) restore the backup ← 6) attach your own domain via the custom-domain card below',ech_has:'⚡ ECH available — applied into subs automatically',ech_absent:'This SNI has no ECH yet (off)',ccq_title:'Per-country exit quality',ccq_hint:'Nightly worker samples (8 countries per night, rotating) of YOUR IP list — P50/P95 in ms; green = fast',ccq_btn:'Refresh',ccq_empty:'No data yet — comes after the nightly cron',v6p_title:'Built-in IPv6 pool',v6p_hint:'11 Cloudflare anycast addresses — answer on any v6-capable network; use “test first” to verify them live',v6p_btn:'Add 11 v6 addresses',dom_title:'Custom domain',dom_hint:'Checks whether your domain is on Cloudflare — no tokens involved',dom_ph:'panel.example.com',dom_check:'Check',dom_need:'Type the domain first',dom_yes:'On Cloudflare — ready for a Workers Custom Domain ✓',dom_no:'Not on Cloudflare — add the domain to a Cloudflare account first',dom_steps:'Path: Cloudflare ← Workers & Pages ← your worker ← Settings ← Domains & Routes ← Add ← Custom domain — the check above turns green a few minutes later',ai_title:'Service proofs',ai_hint:'Worker → AI services reachability — 403/404 means the route is open (auth happens in the app), 200 means fully served.',ai_btn:'Test services',ai_ok:'accepted',ai_pre:'preflight',ai_fail:'failed',g_ident:'Identity & display',g_sec:'Security & access',g_conn:'Connection: protocols & ports',g_sni:'SNI & fingerprint',g_route:'Routing & rules',g_frag:'Fragment & advanced TLS',g_chain:'Fixed exit (chain)',g_tg:'Telegram bot',g_gh:'Auto-deploy',g_save:'Save & export',set_export:'Export settings (file)',set_import:'Import settings',set_import_bad:'Invalid file',clients_title:'Supported clients',clients_hint:'The panel sub link works in all of these — official download pages:',chain_exit:'exit',renew:'Renew 30 days',toggle:'Enable/disable',edit:'Edit',del:'Delete',qr:'QR',info:'User page',
kv_on:'KV bound',kv_off:'KV NOT bound — nothing persists!',pass_uuid:'password = UUID (change it!)',pass_env:'password from ENV',pass_set:'password set',pass_open:'panel is OPEN — set a password!',
self_wait:'loading…',browser_note:'Tested from YOUR network (browser): domains via /cdn-cgi/trace and raw IPs via a TLS connection attempt — a fast answer = alive on your line, timeout = ✗ dead on your line. Plain (non-TLS) ports cannot be probed in a browser (⊘). Test ProxyIPs with the worker test instead — their path goes through the worker.',scan_prune:'Prune dead IPs from my list',scan_prune_confirm:'Remove %1 dead IPs (as seen from your line) from the list?',scan_prune_none:'Run “Test from my network” first — nothing to prune yet',scan_sum_alive:'IPs alive on your line',scan_sum_dead:'dead',scan_sum_skip:'plain-port',ws_test:'Real connection test (domains)',ws_note:'Opens a real WebSocket to the tunnel path (/ws) for each domain — the exact route a config takes: if it opens, configs really connect on your line. Your panel’s own address is always tested first (baseline). Raw IPs have no “real test” in a browser (it cannot send the Host header); a domain that is not routed to your worker fails too.',ws_base:'baseline — the panel itself',ws_ok:'config route opened',ws_fail:'no connection — dead on your line, or not routed to the worker',ws_sum:'domains verified with a real tunnel handshake',ws_noway:'This browser has no WebSocket',surv_title:'Survival pack — more fallbacks, not a guarantee',surv_hint:'One click enables daily rotation, both protocols, default addresses plus the worker host, ports 80/8080 as fallbacks, and ordering by scanner ping reported within 7 days. SNI stays on the worker hostname: fake/rotating SNI can make Cloudflare itself return 403. Fragment is supported only by Xray/sing-box. None of this guarantees 100% across carriers or clients; test the link on each real mobile/Wi-Fi network and client. Ping is from the scanner sender’s line, not every user.',surv_apply:'Apply survival pack',surv_done:'🛡 Survival pack applied',surv_applied:'Applied',surv_changed:'settings changed',surv_already:'Survival pack already active',surv_link:'Survive link',surv_formats:'Copy the format your client expects:',ov_seen:'Authenticated user attempts:',ov_seen_24:'connected in the last 24h',ov_seen_never:'no connection recorded yet (the user may simply not have used it)',ov_seen_hint:'Counts only tunnel attempts with a valid identity, not successful access to a destination. Cloudflare hides which clean-IP entry was used, so this cannot prove full connectivity or identify dead IPs.',
update_check:'Checking for updates…',update_ok:'You are on the latest version',update_new:'New version available: ',update_how:'Update from the “My Panel” tab in Cat Client or paste the new file into Workers.',update_how2:'Tap ⬇️ to download the new worker.js straight from this panel (no GitHub needed). Then in Cloudflare: Workers → your panel → Edit code → replace all code with the new file → Deploy.',
sync_hint:'Opens the master subscription in Cat Client',restore_ok:'Restored',restore_bad:'Invalid file',sub:'Sub',clash:'Clash',singbox:'sing-box'}};
var lang=document.documentElement.lang==='en'?'en':'fa';
function t(k){return (I18N[lang][k]!==undefined?I18N[lang][k]:I18N.fa[k])||k}
function $(s,r){return (r||document).querySelector(s)}
function $$(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s))}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function toast(m,bad){var el=$('#toast');el.textContent=m;el.style.borderColor=bad?'var(--red)':'var(--violet)';el.classList.add('show');clearTimeout(el._t);el._t=setTimeout(function(){el.classList.remove('show')},bad?4600:2000)}
var _askRes=null;
function ask(msg,opt){opt=opt||{};return new Promise(function(res){_askRes=res;$('#askMsg').textContent=msg;var inp=$('#askIn');if(opt.ph!==undefined){inp.style.display='';inp.value=opt.val||'';inp.placeholder=opt.ph||'';}else inp.style.display='none';$('#askYes').textContent=opt.ok||'✓';$('#askNo').textContent=opt.cancel||t('ask_cancel');$('#ask').classList.add('show');if(opt.ph!==undefined)setTimeout(function(){inp.focus()},50)});}
function _askDone(v){if(!_askRes)return;var r=_askRes;_askRes=null;$('#ask').classList.remove('show');r(v)}
document.addEventListener('click',function(e){if(e.target.id==='ask')_askDone(null)});
$('#askYes').addEventListener('click',function(){_askDone($('#askIn').style.display!=='none'?$('#askIn').value:true)});
$('#askNo').addEventListener('click',function(){_askDone(null)});
$('#askIn').addEventListener('keydown',function(e){if(e.key==='Enter')_askDone($('#askIn').value)});
function copy(v){function fb(){var i=document.createElement('textarea');i.value=v;document.body.appendChild(i);i.select();try{document.execCommand('copy');toast(t('copied'))}catch(e){}document.body.removeChild(i)}
 if(navigator.clipboard&&navigator.clipboard.writeText)navigator.clipboard.writeText(v).then(function(){toast(t('copied'))},fb);else fb()}
function api(path,opt){opt=opt||{};var o={method:opt.method||'GET',headers:{}};if(opt.body!==undefined){o.headers['content-type']='application/json';o.body=JSON.stringify(opt.body)}
 return fetch(path,o).then(function(r){if(r.status===401){location.href='/';throw new Error('HTTP 401 — session expired or credentials changed')}return r.text().then(function(raw){var j;try{j=raw?JSON.parse(raw):{}}catch(e){if(!r.ok)throw new Error('HTTP '+r.status+' — non-JSON response');throw e}if(!r.ok&&j&&typeof j==='object')j._httpStatus=r.status;return j})}).then(function(j){
  // Honesty: a write that could not be persisted used to look like a success
  // («3 ✓») while the value only lived in this isolate's memory — the user saw
  // their clean IPs vanish, so say it out loud instead.
  if(j&&j.persisted===false&&o.method&&o.method!=='GET'&&!api._said){api._said=1;toast(t('kv_save_failed'),true);}
  return j})}
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
$$('[data-view]').forEach(function(b){b.addEventListener('click',function(){show(b.getAttribute('data-view'));menuSet(false)})});

/* ---------- load ---------- */
(function(){try{var p=new URLSearchParams(location.search).get('proxyips');if(p){window.__pendingProxyIps=p.split(',').map(function(s){return s.trim()}).filter(Boolean).slice(0,32);history.replaceState(null,'',location.pathname)}}catch(e){}})();
(function(){try{var q=new URLSearchParams(location.search).get('ips');if(q){$('#ipPaste').value=q.split(',').join('\\n');history.replaceState(null,'',location.pathname);setTimeout(function(){var n=document.querySelector('[data-view=\"nodes"\]\');if(n)n.click();var b=document.querySelector('#btnIpAppend');if(b)b.style.boxShadow='0 0 0 3px rgba(0,225,197,.4)';toast(t('ip_import_ready'))},300)}}catch(e){}})();
function load(){return api('/api/settings').then(function(j){CFG=j;renderCfg();refreshEchChip();return api('/api/users')}).then(function(j){USERS=j.users||[];renderUsers();renderStats();renderHero();renderOverview();renderInbounds();renderScanChips();renderB();loadEvents();loadLoc();rpLoad();ppLoad();aeBuild();var nn=$('#noIpsNote');if(nn)nn.style.display=(CFG.settings.ips&&CFG.settings.ips.length)?'none':'block';renderKvWarn();renderSurvival()})}
/* The most confusing failure mode of all: a worker deployed WITHOUT its KV
   binding answers «saved ✓» while the value only lives in one isolate's memory.
   Clean IPs, users and settings then vanish — «the buttons don't work». Say it
   in plain words AND say how to fix it. */
function renderKvWarn(){var w=$('#kvWarn');if(!w||!CFG)return;
 w.style.display=(CFG.kv===false)?'block':'none';
 var ok=$('#kvWarnOk');
 if(ok&&!ok._w){ok._w=1;ok.addEventListener('click',function(){w.style.display='none'})}}
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
 var dayAgo=Date.now()-86400000,seen24=0,never=0;(USERS||[]).forEach(function(u){var ts=u.lastOnline||0;if(ts>=dayAgo)seen24++;else if(!ts)never++});
 var ovS=$('#ovSeen');if(ovS)ovS.innerHTML=(USERS||[]).length?(seen24+' / '+USERS.length+' <span class="dim">'+t('ov_seen_24')+'</span>'+(never?' · <b>'+never+'</b> <span class="dim">'+t('ov_seen_never')+'</span>':'')):'—';
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
$('#btnBulk').addEventListener('click',async function(){
 var n=Number(await ask(t('bulk_count'),{ph:'5',val:'5'}));if(!n||Number(n)<1)return;
 var prefix=await ask(t('bulk_prefix'),{ph:'user',val:'user'});if(prefix===null)return;
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
function renderCfg(){var s=CFG.settings,f=$('#fSettings');f.elements.panelPath.value=s.panelPath||'';f.elements.ptitle.value=s.title||'';f.elements.plang.value=s.lang;f.elements.sni.value=s.sni||'';f.elements.echList.value=s.echList||'';f.elements.fingerprint.value=s.fingerprint;f.elements.entryLimit.value=s.entryLimit;var sf=$('#fSpoof');sf.elements.extraSnis.value=(s.extraSnis||[]).join('\\n');sf.elements.proxyIps.value=(s.proxyIps||[]).join('\\n');if(window.__pendingProxyIps){var cur=sf.elements.proxyIps.value.split(/[\\s,]+/).filter(Boolean),add=window.__pendingProxyIps;window.__pendingProxyIps=null;sf.elements.proxyIps.value=add.concat(cur.filter(function(x){return add.indexOf(x)<0})).slice(0,32).join('\\n');setTimeout(function(){var n=document.querySelector('[data-view=\"spoof\"]');if(n)n.click();sf.elements.proxyIps.scrollIntoView({behavior:'smooth',block:'center'});toast(t('proxyip_import'))},200)}f.elements.chain.value=s.chain||'';f.elements.tgToken.value=s.tgToken||'';f.elements.tgAdmins.value=(s.tgAdmins||[]).join(', ');f.elements.ghRepo.value=s.ghRepo||'';f.elements.ghRef.value=s.ghRef||'';f.elements.ghPat.value=s.ghPat||'';f.elements.ghWorkflow.value=s.ghWorkflow||'deploy-worker.yml';var tg=$('#tgState');tg.textContent=CFG.telegram&&CFG.telegram.configured?t('tg_ok'):t('tg_off');tg.className='chip '+(CFG.telegram&&CFG.telegram.configured?'ok':'');var srp=$('#subRotatePick');if(srp)$$('#subRotatePick button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-v')===(s.subRotate||'fetch'))});renderRotBtn();renderWarp();renderExt();
if(srp&&!srp.__w){srp.__w=1;srp.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;$$('#subRotatePick button').forEach(function(x){x.classList.remove('on')});b.classList.add('on')})}
$('#swIran').classList.toggle('on',s.bypassIran!==false);$('#swAds').classList.toggle('on',!!s.blockAds);$('#swQuic').classList.toggle('on',!!s.blockQuic);$('#swDom2ip').classList.toggle('on',s.domToIp!==false);$('#swSniRot').classList.toggle('on',s.sniFront===true);$('#swFm').classList.toggle('on',s.fmLinks!==false);$('#sniPoolCsv').value=(s.sniPool||[]).join(',');$('#swFrag').classList.toggle('on',!!(s.fragment&&s.fragment.enabled));f.elements.fragPackets.value=(s.fragment||{}).packets||'tlshello';f.elements.fragLength.value=(s.fragment||{}).length||'';f.elements.fragInterval.value=(s.fragment||{}).interval||'';f.elements.alpn.value=s.alpn||'http/1.1';f.elements.cipherSuites.value=s.cipherSuites||'';f.elements.chainMode.value=s.chainMode||'all';$('#swStrict').classList.toggle('on',!!s.chainStrict);var cs=$('#chainState');cs.textContent=CFG.chain?(CFG.chain.type+' · '+CFG.chain.host):t('chain_off');cs.className='chip '+(CFG.chain?'ok':'');
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
var chips=[['🎯 SNI',s.sniFront!==true?t('hero_sni_host'):(def?(s.sniRotate===false?'skk.moe ⚠️':t('hero_sni_pool')):(s.sni||'')),s.sniFront===true&&def&&s.sniRotate===false],['🚪 '+t('hero_ports'),tls+(pl?' · '+pl:''),false],['💦 '+t('hero_ips'),(s.ips||[]).length+((s.pinnedIps&&s.pinnedIps.length)?' (📌'+s.pinnedIps.length+')':''),false],['🎭 '+t('hero_px'),(s.proxyIps||[]).length,false],['⛓ '+t('hero_exit'),s.chain?t('chain_ok'):t('chain_off'),false],['🔄 '+t('hero_rot'),ROT[s.subRotate||'fetch']||'',false]];
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

$('#btnSetExport').addEventListener('click',function(){if(!CFG||!CFG.settings){toast('error',true);return}var st=Object.assign({},CFG.settings);delete st.tgToken;delete st.ghPat;delete st.passwordHash;delete st.hasPassword;var out={_cat:'cat-panel-settings',v:1,at:new Date().toISOString(),settings:st};var b=new Blob([JSON.stringify(out,null,1)],{type:'application/json'});var a=document.createElement('a');a.href=URL.createObjectURL(b);a.download='cat-panel-settings.json';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove()},400);toast('✓')});
$('#btnSetImport').addEventListener('click',function(){$('#setImportFile').click()});
$('#setImportFile').addEventListener('change',function(){var f=this.files&&this.files[0];this.value='';if(!f)return;var r=new FileReader();r.onload=function(){try{var j=JSON.parse(String(r.result));var st=j&&j.settings?j.settings:j;if(!st||typeof st!=='object'||Array.isArray(st))throw 0;delete st.tgToken;delete st.ghPat;delete st.passwordHash;delete st.hasPassword;delete st.password;api('/api/settings',{method:'PUT',body:st}).then(function(x){if(!x.ok)throw 0;toast(t('saved'));return load()}).catch(function(){toast('error',true)})}catch(e){toast(t('set_import_bad'),true)}};r.readAsText(f)});
$('#fSettings').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var body={title:f.elements.ptitle.value,panelPath:f.elements.panelPath.value.trim().toLowerCase(),lang:f.elements.plang.value,sni:f.elements.sni.value,echList:f.elements.echList.value,fingerprint:f.elements.fingerprint.value,entryLimit:Number(f.elements.entryLimit.value),
 subRotate:(function(){var b=document.querySelector('#subRotatePick button.on');return b?b.getAttribute('data-v'):'fetch'})(),protocols:{vless:f.elements.pv.checked,trojan:f.elements.pt.checked},tlsPorts:picked('#pickTls'),plainPorts:picked('#pickPlain'),plainEnabled:$('#swPlain').classList.contains('on'),useDefaults:$('#swDefaults').classList.contains('on'),includeHost:$('#swHost').classList.contains('on'),healthOrder:$('#swHealth').classList.contains('on'),chain:f.elements.chain.value.trim(),tgToken:f.elements.tgToken.value.trim(),tgAdmins:f.elements.tgAdmins.value.split(/[\\s,]+/).filter(Boolean),ghRepo:f.elements.ghRepo.value.trim(),ghRef:f.elements.ghRef.value.trim(),ghPat:f.elements.ghPat.value.trim(),ghWorkflow:f.elements.ghWorkflow.value.trim(),bypassIran:$('#swIran').classList.contains('on'),blockAds:$('#swAds').classList.contains('on'),blockQuic:$('#swQuic').classList.contains('on'),domToIp:$('#swDom2ip').classList.contains('on'),sniFront:$('#swSniRot').classList.contains('on'),sniRotate:$('#swSniRot').classList.contains('on'),fmLinks:$('#swFm').classList.contains('on'),sniPool:($('#sniPoolCsv').value||'').split(/[\s,]+/).map(function(x){return x.trim()}).filter(Boolean),fragment:{enabled:$('#swFrag').classList.contains('on'),packets:f.elements.fragPackets.value,length:f.elements.fragLength.value.trim(),interval:f.elements.fragInterval.value.trim()},alpn:f.elements.alpn.value,cipherSuites:f.elements.cipherSuites.value.trim(),chainMode:f.elements.chainMode.value,chainStrict:$('#swStrict').classList.contains('on')};
 if(f.elements.password.value)body.password=f.elements.password.value;var changedLang=body.lang!==lang;
 api('/api/settings',{method:'PUT',body:body}).then(function(j){if(!j.ok)throw 0;f.elements.password.value='';toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);if(changedLang){location.reload();return}return load()}).catch(function(){toast('error',true)})});

$('#fSpoof').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var body={extraSnis:f.elements.extraSnis.value.split(/[\\s,]+/).filter(Boolean),proxyIps:f.elements.proxyIps.value};
 api('/api/settings',{method:'PUT',body:body}).then(function(j){if(!j.ok)throw 0;if(j.ignored&&j.ignored.length)toast(t('px_ignored')+' '+j.ignored.slice(0,3).join(' · '),true);else toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);return load()}).catch(function(){toast('error',true)})});
$('#btnPattn').addEventListener('click',function(){var f=$('#fSettings');f.elements.alpn.value='http/1.1';f.elements.cipherSuites.value='TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256';f.elements.fingerprint.value='unsafe';$('#swFrag').classList.add('on');f.elements.fragPackets.value='tlshello';f.elements.fragLength.value='1-3';f.elements.fragInterval.value='1';toast(t('pattn_filled'))});
document.addEventListener('click',function(e){var b=e.target.closest('[data-cc]');if(!b)return;api('/api/countries',{method:'PUT',body:{country:b.getAttribute('data-cc')}}).then(function(){toast(t('saved'));return load()}).catch(function(){toast('error',true)})});
document.addEventListener('change',function(e){var sel=e.target.closest('[data-ipcc]');if(!sel)return;var ip=sel.getAttribute('data-ipcc'),cc=sel.value;var body=cc?{ipCountries:{}}:{clearIp:ip};if(cc)body.ipCountries[ip]=cc;api('/api/countries',{method:'PUT',body:body}).then(function(){return load()}).catch(function(){toast('error',true)})});
$('#btnTgHook').addEventListener('click',function(){var o=$('#tgHookOut');o.textContent='…';api('/api/telegram/webhook',{method:'POST'}).then(function(j){o.textContent=j.ok?'🟢 @'+j.bot:'🔴 '+(j.error||j.description||'');return load()}).catch(function(){o.textContent='🔴'})});
$('#swFrag').addEventListener('click',async function(){if($('#swFrag').classList.contains('on')&&!(await ask(t('s_frag_confirm'),{ok:'✓'}))){$('#swFrag').classList.remove('on')}});
$('#btnPathRnd').addEventListener('click',function(){var c='abcdefghijklmnopqrstuvwxyz0123456789',s='';for(var i=0;i<10;i++)s+=c[Math.floor(Math.random()*c.length)];$('#fSettings').elements.panelPath.value=s;});
$('#ccFallback').addEventListener('change',function(){api('/api/countries',{method:'PUT',body:{countryFallback:$('#ccFallback').value}}).then(function(){toast(t('saved'));return load()})});
$('#btnProxyGeo').addEventListener('click',function(){var o=$('#proxyGeoOut');o.textContent='…';api('/api/proxy-geo',{method:'POST'}).then(function(j){var f=j.found||{};o.textContent=Object.keys(f).map(function(k){return flag(f[k])+' '+k}).join('  ')||'—';return load()}).catch(function(){o.textContent='✗'})});
var V6POOL=['2606:4700:4700::1111','2606:4700:4700::1001','2606:4700::6810:84e5','2606:4700:d0::a29f:c001','2606:4700:d0::a29f:c002','2606:4700:d0::1','2606:4700:d1::1','2606:4700::6812:1a2e','2606:4700::6812:3ed','2606:4700:3033::6810:84e5','2a06:98c0::6810:84e5'];
$('#btnV6Pool').addEventListener('click',function(){var o=$('#v6pOut');o.textContent='…';api('/api/ips',{method:'POST',body:{ips:V6POOL,source:'manual'}}).then(function(j){o.textContent=j.ok?(V6POOL.length+' ✓'):'✗';return load()}).catch(function(){o.textContent='✗'})});
$('#btnCcq').addEventListener('click',function(){var o=$('#ccqOut'),rows=$('#ccqRows');o.textContent='…';api('/api/cc-quality').then(function(j){o.textContent='';var cc=j.cc||{};var keys=Object.keys(cc).sort(function(a,b){return (cc[a].p50||9e9)-(cc[b].p50||9e9)});if(!keys.length){rows.innerHTML='<div class="small mute">'+t('ccq_empty')+'</div>';return}rows.innerHTML=keys.map(function(k){var d=cc[k];var good=d.p50<=150;var col=good?'var(--teal,#00e1c1)':'var(--amber,#ffb84d)';var w=Math.max(6,Math.min(100,Math.round(100-Math.min(100,(d.p50||400)/6))));return '<div style="padding:6px 0;border-bottom:1px solid var(--line)"><div class="row small" style="justify-content:space-between"><b>'+flagOf(k)+' '+k+'</b><span>P50 '+d.p50+'ms · P95 '+d.p95+'ms · n'+d.n+'</span></div><div style="height:5px;border-radius:4px;background:var(--line);margin-top:4px"><div style="height:5px;border-radius:4px;width:'+w+'%;background:'+col+'"></div></div></div>'}).join('')}).catch(function(){o.textContent='✗'})});
function refreshEchChip(){var el=$('#echState');if(!el)return;api('/api/ech').then(function(j){el.textContent=j.effective?('\u26a1 ECH: '+j.effective):(j.has?(t('ech_has')+' ('+j.sni+')'):t('ech_absent'))}).catch(function(){el.textContent=''})}
$('#btnDomCheck').addEventListener('click',function(){var o=$('#domOut');var h=$('#domIn').value.trim();if(!h){o.textContent=t('dom_need');return}o.textContent='…';api('/api/domain-check?host='+encodeURIComponent(h)).then(function(j){o.innerHTML=j.ok?(j.onCloudflare?'🟢 '+t('dom_yes'):'🟠 '+t('dom_no')+' ('+(j.ips||[]).join(', ')+')'):'✗'}).catch(function(){o.textContent='✗'})});
$('#btnAiTest').addEventListener('click',function(){var o=$('#aiOut'),rows=$('#aiRows');o.textContent='…';rows.innerHTML='';api('/api/ai-test',{method:'POST',body:{}}).then(function(j){o.textContent='✓';rows.innerHTML=(j.results||[]).map(function(r){var v=r.verdict==='ok'?'<span class="chip ok">'+t('ai_ok')+'</span>':(r.verdict==='preflight'?'<span class="chip ok">'+t('ai_pre')+'</span>':(r.verdict==='rate'?'<span class="chip warn">⏳ rate</span>':'<span class="chip bad">'+t('ai_fail')+'</span>'));return '<div class="row small" style="justify-content:space-between;padding:6px 0;border-bottom:1px solid var(--line)"><b>'+esc(r.name)+'</b><span>'+r.status+' · '+r.ms+'ms '+v+'</span></div>'}).join('')}).catch(function(){o.textContent='✗'})});
$('#btnChainTest').addEventListener('click',function(){var o=$('#chainTestOut');var c=$('#fSettings').elements.chain.value.trim();if(!c){o.textContent=t('chain_off');return}o.textContent='…';api('/api/chain-test',{method:'POST',body:{chain:c}}).then(function(j){o.textContent=(j.ok?'🟢 '+t('chain_ok')+' · '+j.ms+'ms'+(j.exitIp?' · '+t('chain_exit')+': '+j.exitIp+(j.country||j.cc?' ('+(j.country||j.cc)+')':''):''):'🔴 '+t('chain_fail')+' · '+(j.error||j.status||''))}).catch(function(e){o.textContent='🔴 '+t('chain_fail')+' · '+(e&&e.message||'')})});

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

document.addEventListener('click',async function(e){var b;
 if((b=e.target.closest('[data-copy]'))){copy(b.getAttribute('data-copy'));return}
 if((b=e.target.closest('[data-qr]'))){showQr(b.getAttribute('data-qr'),b.getAttribute('data-qrl')||'');return}
 if((b=e.target.closest('[data-edit]'))){openDrawer(USERS.filter(function(u){return u.id===b.getAttribute('data-edit')})[0]);return}
 if((b=e.target.closest('[data-renew]'))){api('/api/users/'+b.getAttribute('data-renew')+'/renew',{method:'POST',body:{days:30}}).then(function(){toast('✓');return load()});return}
 if((b=e.target.closest('[data-toggle]'))){api('/api/users/'+b.getAttribute('data-toggle')+'/toggle',{method:'POST',body:{}}).then(function(){toast('✓');return load()});return}
 if((b=e.target.closest('[data-del]'))){if(!(await ask(t('confirm_del'),{ok:t('del')})))return;api('/api/users/'+b.getAttribute('data-del'),{method:'DELETE'}).then(function(){toast(t('deleted'));return load()});return}
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
 p.then(function(j){if(!j||!j.ok){var why=j&&(j.error||j.message)||'request rejected';if(j&&j.error&&j.message)why=j.error+': '+j.message;if(j&&j._httpStatus)why='HTTP '+j._httpStatus+' · '+why;toast(t('b_user_save_error').replace('%1',safeDiag(why)),true);return}toast(t(j.persisted?'saved':'saved_nokv'),!j.persisted);closeDrawer();return load().catch(function(e){toast(t('b_user_saved_refresh_error').replace('%1',safeDiag(e&&e.message||e)),true)})}).catch(function(e){toast(t('b_user_save_error').replace('%1',safeDiag(e&&e.message||e)),true)})});

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
var so=SRC[ip]||SRC[ipKey(ip)]||SRC[String(ip).split('#')[0]]||SRC[ipKey(String(ip).split('#')[0])];var src=so&&so.src==='neighbor'?'<span style="color:#fbbf24">👑 همسایه</span>':so?'<span style="color:#7dd3fc">\ud83c\udff7 '+(so.src==='scanner'?'\u0627\u0632 \u0627\u0633\u06a9\u0646\u0631':'\u0648\u0631\u0648\u062f \u062f\u0633\u062a\u06cc')+' \u00b7 \ud83d\udcf6 '+so.ms+'ms</span>':'';
return '<span class="chip mono'+(isPin?' v':'')+'">'+ccSelect(ip,tags[ip]||'')+' '+esc(ip)+(src?' '+src:'')+(w?' '+w:'')+' <b data-ip'+(isPin?'unpin':'pin')+'="'+esc(ip)+'" title="'+t(isPin?'ip_unpin':'ip_pin')+'" style="cursor:pointer">'+(isPin?'\ud83d\udccc':'\ud83d\udccd')+'</b> <b data-ipdel="'+esc(ip)+'" title="remove" style="cursor:pointer">\u2715</b></span>'}).join(''):'<span class="dim small">\u2014</span>';renderCountries();renderRotBtn();renderPxTest();renderSvc();renderLatency();
 $('#btnScanApp').href='catclient://scan?sni='+encodeURIComponent(CFG.sni||CFG.host)+'&panel='+encodeURIComponent(location.origin)}
function manualAdd(){var raw=$('#manualIps').value,port=($('#manualPort').value||'').replace(/[^0-9]/g,'')||'443',cc=($('#manualCC').value||'').trim().toUpperCase().slice(0,2);
var ips=raw.split(/[\\s,;]+/).map(function(s){s=s.trim();if(!s)return '';var v=null;
if(s.charAt(0)==='[')v=s.charAt(s.length-1)===']'?s+':'+port:s;
else if(s.indexOf('::')>=0)v='['+s+']:'+port;
else if(/^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?$/.test(s))v=s.indexOf(':')>=0?s:s+':'+port;
else if(/^[0-9a-f:]+$/.test(s)&&s.indexOf(':')>=0)v='['+s+']:'+port;
else if(/^[a-z0-9.-]+\\.[a-z]{2,}$/.test(s)&&s.indexOf(':')<0)v=s+':'+port;
return v}).filter(Boolean).map(function(s){return cc?s+'#'+cc:s});
if(!ips.length){toast(t('ip_manual_none'),true);return}
var save=function(list){api('/api/ips',{method:'POST',body:{ips:list,source:'manual'}}).then(function(j){toast(j.ok?(j.count+' ✓'):'error',!j.ok);$('#manualIps').value='';return load()})};
if($('#manualTest').checked){api('/api/ip-test',{method:'POST',body:{tests:ips.map(function(a){var m=a.match(/:(\\d{1,5})$/);return {addr:a,port:m?+m[1]:443,kind:'clean'}})}}).then(function(j){var res=(j&&j.results)||{};var good=Object.keys(res).filter(function(k){return res[k]&&res[k].ok});var dead=Object.keys(res).length-good.length;
if(!good.length){toast(t('ip_manual_dead'),true);return}
if(dead>0)toast(good.length+' ✓ / '+dead+' ✗');
save(good)}).catch(function(){save(ips)});return}
save(ips)}
$('#btnManualAdd').addEventListener('click',manualAdd);
function importIps(replace){var raw=$('#ipPaste').value;var ips=raw.split(/[\\s,;]+/).map(function(s){s=s.trim();if(/^\\[[0-9a-f:]+\\]:\\d{1,5}$/i.test(s))return s;s=s.replace(/^\\[/,'').replace(/\\]$/,'').replace(/[#|=][A-Za-z]{2}$/,'');return /^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-f:]+|[a-z0-9.-]+\\.[a-z]{2,})(?::\\d{1,5})?$/i.test(s)?s:''}).filter(Boolean);
if(!ips.length){toast('0',true);return}api('/api/ips',{method:'POST',body:{ips:ips,replace:!!replace}}).then(function(j){toast(j.count+' ✓');$('#ipPaste').value='';return load()})}
$('#btnIpAppend').addEventListener('click',function(){importIps(false)});$('#btnIpReplace').addEventListener('click',function(){importIps(true)});
$('#btnIpClear').addEventListener('click',async function(){if(!(await ask(t('ip_clear_confirm'),{ok:'✓'})))return;api('/api/ips',{method:'POST',body:{ips:[],replace:true}}).then(function(){toast(t('saved'));return load()})});
$('#btnIpCopy').addEventListener('click',function(){copy(CFG.settings.ips.join('\\n'))});
var PLAIN_SET=[80,8080,8880,2052,2082,2086,2095];
function pxSplit(list){var cf=[],sk=[];list.forEach(function(p){(/^socks5h?:\\/\\//i).test(p)?sk.push(p):cf.push(p)});return {cf:cf,sk:sk}}
function renderLatency(){var TT=window.__ipTest||{},tags=CFG.settings.ipCountries||{},box=$('#ccLatency');if(!box)return;var by={};Object.keys(TT).forEach(function(a){var r=TT[a];if(!r||!r.ok||typeof r.ms!=='number')return;var cc=tags[a]||tags[String(a).split('#')[0]]||'🌐';(by[cc]=by[cc]||[]).push(r.ms)});var rows=Object.keys(by).map(function(cc){var a=by[cc].sort(function(x,y){return x-y});var pc=function(p){return a[Math.min(a.length-1,Math.floor(p/100*(a.length-1)+0.5))]};return {cc:cc,n:a.length,p50:pc(50),p95:pc(95)}}).sort(function(x,y){return x.p50-y.p50});$('#ccLatTitle').textContent=rows.length?'⚡ Worker → Exit (P50 · P95)':'';box.innerHTML=rows.map(function(r){var bar='<span style="display:inline-block;width:'+(20+Math.min(60,Math.round(r.p50/6)))+'px;height:4px;background:#34d399;border-radius:2px;vertical-align:middle"></span>';return '<span class="chip mono">'+(r.cc!=='🌐'?flag(r.cc)+' '+r.cc:'🌐')+' · P50 '+r.p50+' · P95 '+r.p95+'ms'+(r.n<3?' · 👤 داده کم':' ('+r.n+')')+' '+bar+'</span>'}).join('')}
function renderSvc(){var S=window.__svcTest||{},box=$('#svcChips');if(!box)return;var ex=window.__svcExit||{};var exChip=ex.loc?'<span class="chip mono">🌐 خروجی ورکر: '+(ex.loc||'?')+(ex.colo?' · '+ex.colo:'')+(ex.ip?' · '+ex.ip:'')+'</span>':'';box.innerHTML='<span class="dim small">'+esc(t('svc_note'))+'</span><br>'+exChip+Object.keys(S).map(function(h){var r=S[h];var w=r.ok?'<span style="color:#34d399">\u2713 '+r.ms+'ms</span>':(r.status?'<span style="color:#f59e0b">\u26a0 '+r.status+'</span>':'<span style="color:#f87171">\u2717'+(r.error?' '+esc(String(r.error).slice(0,32)):'')+'</span>');var lbl=h.replace(/^(www|chat)\./,'');return '<span class="chip mono">'+esc(lbl)+' '+w+'</span>'}).join('')+exChip+((Object.keys(S).length&&S['gemini.google.com']&&!S['gemini.google.com'].ok)?'<span class="chip">💡 '+esc(t('svc_gemini_hint'))+'</span>':'')}
function renderPxTest(){var px=CFG.settings.proxyIps||[],TT=window.__ipTest||{},box=$('#pxTestChips');if(!box)return;box.innerHTML=px.map(function(p){var st=TT[p];var w=st?(st.ok?'<span style="color:#34d399">relay\u2713 '+st.ms+'ms</span>':'<span style="color:#f87171">relay\u2717'+(st.error?' \u00b7 '+esc(String(st.error).slice(0,42)):'')+'</span>'):'<span class="dim">\u2014</span>';var isSk=(/^socks5h?:\\/\\//i).test(p);var lbl=isSk?'\ud83e\udde6 '+p.replace(/^socks5h?:\\/\\//i,'').replace(/^[^@\\/]*@/,''):'\ud83c\udfaf '+p;return '<span class="chip mono">'+lbl+' '+w+'</span>'}).join('')}
$('#btnSvcTest').addEventListener('click',function(){var out=$('#ipTestOut');out.textContent='\u2026';$('#btnSvcTest').disabled=true;
api('/api/svc-test',{method:'POST',body:{}}).then(function(j){window.__svcTest=j.results||{};window.__svcExit=j.exit||{};renderSvc();var o=Object.keys(window.__svcTest).length,k=Object.values(window.__svcTest).filter(function(r){return r.ok}).length;out.textContent='\ud83e\udea7 '+k+' / '+o}).catch(function(e){out.textContent='\u2717 '+esc(e&&e.message||e)}).finally(function(){$('#btnSvcTest').disabled=false})});
$('#btnIpTest').addEventListener('click',function(){var ips=CFG.settings.ips.slice(0,48),pxs=(CFG.settings.proxyIps||[]).slice(0,16);if(!ips.length&&!pxs.length){toast(t('scan_empty'),true);return}var tests=ips.map(function(a){var m=a.match(/:(\d{1,5})$/);var port=m?+m[1]:443;return {addr:a,port:port,kind:PLAIN_SET.indexOf(port)>-1?'plain':'clean'}}).concat((function(){var sp=pxSplit(pxs),out=sp.cf.map(function(p){var m=p.match(/:(\d{1,5})$/);return {addr:m?p.slice(0,p.lastIndexOf(':')):p,port:m?+m[1]:443,kind:'proxyip',key:p}});return out.concat(sp.sk.map(function(p){return {addr:p,port:0,kind:'socks',key:p}}))})()).slice(0,64);var out=$('#ipTestOut');out.textContent='\u2026 0/'+tests.length;$('#btnIpTest').disabled=true;
api('/api/ip-test',{method:'POST',body:{tests:tests}}).then(function(j){window.__ipTest=j.results||{};renderIps();var cl=tests.filter(function(x){return x.kind!=='proxyip'}),ok=cl.filter(function(x){var r=window.__ipTest[x.key||x.addr];return r&&r.ok}).length,po=tests.filter(function(x){return x.kind==='proxyip'||x.kind==='socks'}),pok=po.filter(function(x){var r=window.__ipTest[x.key||x.addr];return r&&r.ok}).length;var best=null;cl.forEach(function(x){var r=window.__ipTest[x.key||x.addr];if(r&&r.ok&&r.ms!=null&&(best===null||r.ms<best))best=r.ms});out.textContent='🩺 \ud83d\udca6 '+ok+' / '+cl.length+(po.length?' \u00b7 \ud83c\udfaf '+pok+' / '+po.length:'')+(best!==null?' · کمترین: '+best+'ms':'')}).catch(function(e){out.textContent='\u2717 '+esc(e&&e.message||e)}).finally(function(){$('#btnIpTest').disabled=false})});
$('#btnBrowserTest').addEventListener('click',function(){var box=$('#scanRes');var raw=CFG.settings.ips.concat(CFG.settings.useDefaults?CFG.defaults.addresses:[]);var targets=[],seen={},i,j;
 for(i=0;i<raw.length;i++){var a=String(raw[i]||'').trim();if(!a||seen[a])continue;seen[a]=1;var base=a.split('#')[0],m=/^(.+):([0-9]{1,5})$/.exec(base),host=base,port=443;
  if(m&&!/^[0-9a-fA-F:]+$/.test(base)){host=m[1];port=+m[2]}
  host=host.replace(/^\\[/,'').replace(/\\]$/,'');
  targets.push({a:a,host:host,port:port,dom:!/^[0-9a-fA-F:.]+$/.test(host),plain:PLAIN_SET.indexOf(port)>-1})}
 if(!targets.length){toast(t('scan_empty'),true);return}
 targets=targets.slice(0,60);var BS=window.__browserScan={};var rows={};box.innerHTML='<div class="note w small" style="margin-bottom:8px">'+t('browser_note')+'</div>';
 targets.forEach(function(x){var d=document.createElement('div');d.innerHTML='<span class="mono">'+esc(x.a)+'</span><span class="dim">…</span>';box.appendChild(d);rows[x.a]=d.lastChild});
 var done=0,alive=0;var btn=$('#btnBrowserPrune');if(btn)btn.style.display='none';
 function finish(){var dead=0,skip=0;for(var k in BS){if(BS[k].ok===false)dead++;if(BS[k].skip)skip++}
  var sum=document.createElement('div');sum.innerHTML='<b>'+alive+'/'+targets.length+'</b> '+t('scan_sum_alive').replace('%1',String(alive))+(dead?' · <span style="color:var(--red)">'+dead+' '+t('scan_sum_dead')+'</span>':'')+(skip?' · <span class="dim">'+skip+' '+t('scan_sum_skip')+'</span>':'');
  box.insertBefore(sum,box.firstChild.nextSibling);
  if(btn&&dead){btn.style.display='';btn.dataset.dead=String(dead)}}
 function next(){if(i>=targets.length){if(done>=targets.length)finish();return}var x=targets[i++];
  if(x.plain){BS[x.a]={skip:true};rows[x.a].innerHTML='<span class="dim" title="'+esc(t('scan_sum_skip'))+'">⊘</span>';done++;next();return}
  var t0=performance.now(),ctl=('AbortController' in window)?new AbortController():null,timedOut=false;
  var timer=setTimeout(function(){timedOut=true;if(ctl)ctl.abort()},4000),p,url;
  try{url=x.dom?('https://'+x.host+'/cdn-cgi/trace?'+Date.now()):('https://'+(/:/.test(x.host)?'['+x.host+']':x.host)+':'+x.port+'/');
   p=fetch(url,{mode:'no-cors',cache:'no-store',signal:ctl?ctl.signal:undefined})}catch(e){p=Promise.reject(e)}
  p.then(function(){clearTimeout(timer);if(timedOut){BS[x.a]={ok:false};rows[x.a].innerHTML='<span style="color:var(--red)">✗</span>'}
   else{var ms=Math.round(performance.now()-t0);BS[x.a]={ok:true,ms:ms};alive++;rows[x.a].innerHTML='<span style="color:'+(ms<400?'var(--green)':ms<900?'var(--amber)':'var(--red)')+'">'+ms+' ms</span>'}
   done++;next()},function(){clearTimeout(timer);if(timedOut){BS[x.a]={ok:false};rows[x.a].innerHTML='<span style="color:var(--red)">✗</span>'}
   else{var ms=Math.round(performance.now()-t0);BS[x.a]={ok:true,ms:ms};alive++;rows[x.a].innerHTML='<span style="color:var(--green)">'+ms+' ms ✓</span>'}
   done++;next()})}
 i=0;next();next();next()});
if($('#btnBrowserPrune'))$('#btnBrowserPrune').addEventListener('click',async function(){var BS=window.__browserScan||{};
 var dead=CFG.settings.ips.filter(function(raw){var r=BS[raw];return r&&r.ok===false});
 if(!dead.length){toast(t('scan_prune_none'),true);return}
 if(!(await ask(t('scan_prune_confirm').replace('%1',String(dead.length)),{ok:'✓'})))return;
 var keep=CFG.settings.ips.filter(function(raw){var r=BS[raw];return !(r&&r.ok===false)});
 api('/api/ips',{method:'POST',body:{ips:keep,replace:true,source:'manual'}}).then(function(){toast(t('saved'));return load()})});
$('#btnWsTest').addEventListener('click',function(){var box=$('#wsTestOut');var WS=window.WebSocket;if(!WS){toast(t('ws_noway'),true);return}
 var seen={},targets=[];
 function add(h,base){if(!h||seen[h])return;seen[h]=1;targets.push({h:h,base:!!base})}
 add(location.host,true);
 var list=CFG.settings.ips||[],k;
 for(k=0;k<list.length;k++){var a=String(list[k]||'').trim();if(!a)continue;var base=a.split('#')[0],m=/^(.+):([0-9]{1,5})$/.exec(base),hh=base,port='';
  if(m&&!/^[0-9a-fA-F:]+$/.test(base)){hh=m[1];port=':'+m[2]}
  hh=hh.replace(/^\\[/,'').replace(/\\]$/,'');
  if(/^[0-9a-fA-F:.]+$/.test(hh))continue;
  if(port&&PLAIN_SET.indexOf(+port.slice(1))>-1)continue;
  add(hh+port,false)}
 if(!targets.length){toast(t('scan_empty'),true);return}
 targets=targets.slice(0,30);var WSR=window.__wsScan={};var rows={};
 box.innerHTML='<div class="note w small" style="margin-bottom:8px">'+t('ws_note')+'</div>';
 targets.forEach(function(x){var d=document.createElement('div');d.innerHTML='<span class="mono">'+esc(x.h)+'</span>'+(x.base?' <span class="dim">('+esc(t('ws_base'))+')</span>':'')+'<span class="dim">…</span>';box.appendChild(d);rows[x.h]=d.lastChild});
 var i=0,done=0,ok=0;
 function finish(){var sum=document.createElement('div');sum.innerHTML='<b>'+ok+'/'+targets.length+'</b> '+t('ws_sum');box.insertBefore(sum,box.firstChild.nextSibling)}
 function next(){if(i>=targets.length){if(done>=targets.length)finish();return}var x=targets[i++],t0=performance.now(),ws=null,settled=false,timer;
  function end(good){if(settled)return;settled=true;clearTimeout(timer);
   if(good){var ms=Math.round(performance.now()-t0);WSR[x.h]={ok:true,ms:ms,base:x.base};ok++;rows[x.h].innerHTML='<span style="color:var(--green)">'+ms+' ms ✓ '+esc(t('ws_ok'))+'</span>'}
   else{WSR[x.h]={ok:false,base:x.base};rows[x.h].innerHTML='<span style="color:var(--red)">✗ '+esc(t('ws_fail'))+'</span>'}
   done++;next()}
  timer=setTimeout(function(){try{if(ws)ws.close()}catch(e){}end(false)},8000);
  try{ws=new WS('wss://'+x.h+'/ws');
   ws.onopen=function(){end(true);try{ws.close()}catch(e){}};
   ws.onerror=function(){end(false)};
   ws.onclose=function(){end(false)}}catch(e){clearTimeout(timer);settled=true;WSR[x.h]={ok:false,base:x.base};rows[x.h].innerHTML='<span style="color:var(--red)">✗</span>';done++;next()}}
 next();next();next()});
function renderSurvival(applied){var out=$('#survOut');if(!out)return;var s=(CFG&&CFG.settings)||{};var links=(CFG&&CFG.links)||{};var base=links.sub||'';
 var on=s.subRotate==='daily'&&s.sniFront!==true&&s.sniRotate===false&&!!(s.fragment&&s.fragment.enabled)&&!!s.plainEnabled&&!!s.healthOrder&&s.useDefaults!==false&&s.includeHost!==false&&!!(s.protocols&&s.protocols.vless&&s.protocols.trojan)&&(s.plainPorts||[]).indexOf(80)>-1&&(s.plainPorts||[]).indexOf(8080)>-1;
 var link=base?('<a class="mono" href="'+esc(base+'?survive=1')+'" target="_blank" rel="noopener">'+esc(base+'?survive=1')+'</a>'):'';
 var head='';
 if(applied)head='<div style="color:var(--green)">✓ '+esc(t('surv_applied'))+' — '+applied.changed.length+' '+esc(t('surv_changed'))+'</div>';
 else if(on)head='<div style="color:var(--green)">✓ '+esc(t('surv_already'))+'</div>';
 var fmts=[['sub',t('sub')],['sub64','Base64'],['clash',t('clash')],['singbox',t('singbox')],['xray','Xray']];
 var buttons=fmts.map(function(x){var u=links[x[0]];return u?'<button class="btn sm" type="button" data-copy="'+esc(u+'?survive=1')+'">'+esc(x[1])+'</button>':''}).join('');
 out.innerHTML=head+'<div style="margin-top:4px">'+esc(t('surv_link'))+': '+link+'</div><div class="small mute" style="margin-top:6px">'+esc(t('surv_formats'))+'</div><div class="row" style="margin-top:4px">'+buttons+'</div>'}
if($('#btnSurvival'))$('#btnSurvival').addEventListener('click',function(){var out=$('#survOut');out.textContent='…';
 api('/api/survival',{method:'POST',body:{apply:true}}).then(function(j){if(!j||!j.ok){out.textContent='✗';toast('✗',true);return}
  toast(t('surv_done'));renderSurvival(j);return load()}).catch(function(){out.textContent='✗';toast('✗',true)})});

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
 fetch(link,{cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);var status=r.status;return r.text().then(function(tx){return {status:status,text:tx}})}).then(function(res){var raw=String(res.text||''),lines=raw.trim()?raw.trim().split('\\n').map(function(x){return x.trim()}).filter(Boolean):[],uris=lines.filter(function(x){return /^[a-z][a-z0-9+.-]*:\\/\\//i.test(x)});if(!uris.length){bText='';$('#bCount').textContent='0';$('#bPrev').value='';bEmptyNote(0,{key:raw.trim()?'b_bad_reply':'b_empty_reply',detail:'HTTP '+res.status});return}bText=raw;$('#bCount').textContent=uris.length;$('#bPrev').value=uris.slice(0,10).join('\\n');bEmptyNote(uris.length)}).catch(function(e){bText='';$('#bCount').textContent='0';$('#bPrev').value='';bEmptyNote(0,{key:'b_fetch_error',detail:safeDiag(e&&e.message||e)})})}
function safeDiag(v){return String(v||'unknown').replace(/[0-9a-f-]{36}/ig,'[UUID]').replace(/https?:[^\\s]+/ig,'[URL]').replace(/\\s+/g,' ').slice(0,120)}
/* «0 configs» must tell the owner whether the response was empty or the request failed. */
function bEmptyNote(n,problem){var el=$('#bEmpty');if(!el)return;if(n){el.style.display='none';return}
 el.style.display='block';var body=problem?t(problem.key||'b_fetch_error').replace('%1',safeDiag(problem.detail)):t('b_empty_body');el.innerHTML='<b>⚠️ '+esc(t('b_empty_title'))+'</b><div style="margin-top:6px">'+esc(body)+'</div>'}
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
if($('#rpAdd'))$('#rpAdd').addEventListener('click',async function(){var url=await ask(t('rp_add_url'),{ph:'https://github.com/…'});if(!url)return;var name=await ask(t('rp_add_name'),{ph:'repo',val:'repo'+((RP.d&&RP.d.repos||[]).length+1)});if(!name)return;
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
if($('#ppAdd'))$('#ppAdd').addEventListener('click',async function(){var url=await ask(t('rp_add_url'),{ph:'https://github.com/…'});if(!url)return;var name=await ask(t('rp_add_name'),{ph:'prepo',val:'prepo'+((PP.d&&PP.d.repos||[]).length+1)});if(!name)return;
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
// Patterniha's free public configs (field-recommended Oct 2026 — served via the
// panel's own domain, since raw.githubusercontent is unreachable from Iran).
var PATTERNIHA_FREE_SUB='https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt';
function renderExt(){var box=$('#extRows');if(!box)return;var list=CFG.settings.extSubs||[];
 box.innerHTML=list.length?list.map(function(x,i){var link=location.origin+'/ext/'+(i+1)+'/'+CFG.uuid;
 return '<div class="card" style="padding:10px 12px;margin-bottom:6px"><div class="row" style="align-items:center;gap:8px;flex-wrap:wrap"><b>'+esc(x.name)+'</b><span style="flex:1"></span><button class="btn sm" type="button" data-copy="'+esc(link)+'">🔗 /ext/'+(i+1)+'</button><button class="btn sm" type="button" data-qr="'+esc(link)+'" data-qrl="'+esc(x.name)+'">▦</button><button class="btn sm r" type="button" data-extdel="'+i+'">✕</button></div><div class="small dim mono" dir="ltr" style="margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(x.url)+'</div></div>'}).join('')
 :'<span class="small dim">'+esc(t('ext_empty'))+'</span>';
 var core=$('#extCoreNote');if(core){core.setAttribute('data-i','ext_core');core.textContent=t('ext_core');core.style.display=list.some(function(x){return /Serverless-for-Iran/.test(x.url||'')})?'':'none'}
 var demo=$('#extLinkDemo');if(demo)demo.textContent=list.length?('/ext/1/'+CFG.uuid):''}
if($('#btnExtAdd'))$('#btnExtAdd').addEventListener('click',async function(){var name=await ask(t('ext_name'),{ph:'ext',val:'ext'+((CFG.settings.extSubs||[]).length+1)});if(!name)return;var url=await ask(t('ext_url'),{ph:'https://…/sub'});if(!url)return;
 var list=(CFG.settings.extSubs||[]).slice(0,4);list.push({name:name,url:url});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt()}).catch(function(){toast('error',true)})});
if($('#btnExtPreset'))$('#btnExtPreset').addEventListener('click',function(){
 var list=(CFG.settings.extSubs||[]).filter(function(x){return x.url!==SERVERLESS_SUB});if(list.length>=5)list=list.slice(0,4);list.push({name:'Serverless-for-Iran (PattNG)',url:SERVERLESS_SUB});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt();toast('⚡ ✓')}).catch(function(){toast('error',true)})});
if($('#btnExtPresetFree'))$('#btnExtPresetFree').addEventListener('click',function(){
 var list=(CFG.settings.extSubs||[]).filter(function(x){return x.url!==PATTERNIHA_FREE_SUB});if(list.length>=5)list=list.slice(0,4);list.push({name:'Patterniha Free Configs',url:PATTERNIHA_FREE_SUB});
 api('/api/settings',{method:'PUT',body:{extSubs:list}}).then(function(j){if(!j.ok)throw 0;CFG.settings.extSubs=j.settings.extSubs;renderExt();toast('🆓 ✓')}).catch(function(){toast('error',true)})});
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

var menuEl=$('#menu');
function menuSet(o){if(menuEl)menuEl.classList.toggle('show',!!o)}
if($('#btnBurger'))$('#btnBurger').addEventListener('click',function(){menuSet(!menuEl.classList.contains('show'))});
document.addEventListener('click',function(e){if(e.target.closest&&e.target.closest('[data-mclose]'))menuSet(false)});
document.addEventListener('keydown',function(e){if(e.key==='Escape')menuSet(false)});

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
/* cron: internal monitor + country quality                            */
/* ------------------------------------------------------------------ */

/** Built-in IPv6 anycast pool (same set the app's scanner uses). */
const CF_V6_POOL = [
  '2606:4700:4700::1111', '2606:4700:4700::1001', '2606:4700::6810:84e5',
  '2606:4700:d0::a29f:c001', '2606:4700:d0::a29f:c002', '2606:4700:d0::1',
  '2606:4700:d1::1', '2606:4700::6812:1a2e', '2606:4700::6812:3ed',
  '2606:4700:3033::6810:84e5', '2a06:98c0::6810:84e5',
];

/** Internal monitor (the INWARD half — the app's WorkManager is the outward
 * half): while this worker is alive it proves KV read/write works and tells
 * the owner the moment it breaks; plus one daily summary. If the worker gets
 * suspended entirely, cron stops running and the app-side monitor catches it. */
async function cronSelfCheck(env) {
  try {
    const st = await readSettings(env);
    const settings = st.settings || st;
    const cfg = tgConfig(env, settings);
    const kv = kvBinding(env);
    let kvOk = false;
    if (kv) {
      await kv.put('cat_monitor_ping', String(Date.now()));
      kvOk = (await kv.get('cat_monitor_ping')) !== null;
    }
    const prev = await readJsonKv(env, 'cat_monitor_state_v1', { kvOk: true });
    await kv.put('cat_monitor_state_v1', JSON.stringify({ kvOk, at: Date.now() }));
    if (!cfg || !cfg.admins.length) return;
    const tell = (text) => { for (const id of cfg.admins) tgApi(cfg.token, 'sendMessage', { chat_id: id, text }).catch(() => {}); };
    if (prev.kvOk && !kvOk) tell('🔴 Cat Panel — KV پاسخ نمی‌دهد؛ تنظیمات دیگر ذخیره نمی‌شود. ربات: /doctor');
    if (!prev.kvOk && kvOk) tell('🟢 Cat Panel — KV برگشت');
    const day = new Date().toISOString().slice(0, 10);
    const daily = await readJsonKv(env, 'cat_monitor_daily_v1', { day: '' });
    if (daily.day === day || !kv) return;
    await kv.put('cat_monitor_daily_v1', JSON.stringify({ day }));
    const users = await readUsers(env);
    tell([
      '📊 گزارش روزانهٔ Cat Panel (' + day + ')',
      '👥 کاربران: ' + users.length,
      '🧹 آی‌پی‌ها: ' + (settings.ips || []).length,
      '🎯 خروجی ثابت: ' + (settings.chain ? 'روشن ✓' : 'خاموش'),
      '🛡 KV: ' + (kvOk ? 'سالم' : 'خراب!'),
      'ver ' + CAT_PANEL_VERSION,
    ].join('\n'));
  } catch { /* cron must never throw */ }
}

/** Nightly (UTC-day guarded): TCP-connect latency samples per country tag from
 * the owner's own IP list — powers the quality table in the panel. */
async function cronCountryQuality(env) {
  try {
    const kv = kvBinding(env);
    if (!kv) return;
    const day = new Date().toISOString().slice(0, 10);
    const mark = await readJsonKv(env, 'cat_ccq_day_v1', { day: '' });
    if (mark.day === day) return;
    const st = await readSettings(env);
    const settings = st.settings || st;
    const ips = settings.ips || [];
    if (!ips.length) return;
    const byCc = {};
    for (const a of ips) {
      const cc = countryOfAddr(a, env, settings);
      if (!cc) continue;
      (byCc[cc] = byCc[cc] || []).push(a);
    }
    const sockets = await loadSockets();
    if (!sockets) return;
    // Quota-safe sampling: 8 countries × 2 IPs per night, rotating by UTC day so
    // every country gets swept over consecutive nights (free-plan subrequest cap
    // stays untouched), and the result MERGES into the existing table — the
    // previous table is never wiped.
    const keys = Object.keys(byCc).sort();
    const DAY_CC = 8, DAY_IPS = 2;
    const dayIdx = Math.floor(Date.now() / 86400000);
    const start = keys.length ? (dayIdx * DAY_CC) % keys.length : 0;
    const prev = await readJsonKv(env, 'cat_cc_quality_v1', { at: 0, cc: {} });
    const out = (prev && prev.cc && typeof prev.cc === 'object') ? prev.cc : {};
    for (let k = 0; k < Math.min(DAY_CC, keys.length); k++) {
      const cc = keys[(start + k) % keys.length];
      const list = byCc[cc];
      const ipStart = list.length ? (dayIdx * DAY_IPS) % list.length : 0;
      const msList = [];
      for (let i = 0; i < Math.min(DAY_IPS, list.length); i++) {
        const a = list[(ipStart + i) % list.length];
        const port = pinnedPortOf(a) || 443;
        const host = String(a).replace(/^\[/, '').replace(/\]$/, '').split('/')[0];
        try {
          const t0 = Date.now();
          const s2 = sockets.connect({ hostname: host, port }, { allowHalfOpen: false });
          if (s2.opened) await s2.opened;
          msList.push(Date.now() - t0);
          try { s2.close(); } catch (_e) { /* ignore */ }
        } catch (_e2) { /* unreachable sample — skipped */ }
      }
      if (msList.length) {
        msList.sort((x, y) => x - y);
        out[cc] = { p50: msList[Math.floor(msList.length / 2)], p95: msList[msList.length - 1], n: msList.length, at: Date.now() };
      }
    }
    await kv.put('cat_ccq_day_v1', JSON.stringify({ day }));
    await kv.put('cat_cc_quality_v1', JSON.stringify({ at: Date.now(), cc: out }));
  } catch { /* cron must never throw */ }
}

/* ------------------------------------------------------------------ */
/* entry                                                               */
/* ------------------------------------------------------------------ */

export default {
  /** Cloudflare cron (wrangler.jsonc triggers → every 12h): refresh clean-IP + ProxyIP feeds. */
  async scheduled(controller, env, ctx) {
    void controller;
    try {
      const job = Promise.allSettled([refreshRepos(env), refreshProxyRepos(env), cronSelfCheck(env), cronCountryQuality(env)]);
      if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(job); else await job;
    } catch { /* cron must never throw */ }
  },
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env || {}, ctx);
    } catch (e) {
      // Never let an exception escape: an escaped throw is exactly what the
      // edge turns into the «Error 1101» page. API callers get JSON, every
      // other request a neutral page — no stack, no version, no hints.
      const u = request && typeof request === 'object' && request.url ? String(request.url) : '';
      if (u.indexOf('/api') >= 0) return json({ ok: false, error: 'internal', message: String(e && e.message ? e.message : e) }, 500);
      try { return stealthNotFound(); } catch (_e2) { return new Response('error', { status: 500, headers: { 'content-type': 'text/plain' } }); }
    }
  },
};

export const _testing = {
  CAT_PANEL_VERSION,
  splitCsv, uniq, isUuid, b64encode, b64decode, sha256Hex, hmacHex, safeEqualHex, withDomMap, sniPoolOf, DEFAULT_SNI_POOL, healthProbe, relayAttempts, socksRelayList, svcProbe, SVC_TEST_HOSTS, FINAL_MASK_PROFILE, CIPHER_SUITES_DEFAULT, isCloudflareIp, DEFAULT_CLEAN_ADDRESSES, countryLatency,
  deriveUuid, resolveUuid,
  KV_KEYS, kvBinding, kvCacheClear, KV_READ_TTL_MS, DEFAULT_ECH_VALUE,
  defaultSettings, normalizeSettings, readSettings, writeSettings,
  normalizeUser, readUsers, writeUsers, userBlockedReason, findUser,
  panelPassword, panelIsOpen, makeSession, verifySession, isOwner, checkLogin,
  qrEncode, qrSvg,
  decodeEarlyData, websocketReadable, safeCloseWs, parseSocksAddress, parseVlessHeader, trojanPassword, parseTrojanRequest,
  sha224Hex, trojanHash, isCloudflareIp, CF_CIDR_RANGES,
  __setSockets, loadSockets, splitHostPort, proxyIpList, parseChain, dialViaChain, socks5Handshake, httpConnectHandshake, vlessHeader, trojanRequest, wsClientLayer, openChainTransport, chainProbe, cronSelfCheck, cronCountryQuality, CF_V6_POOL, subQuery, DEFAULT_PROXY_IPS, buildXrayConfigs, tgCommand, tgSecret, tgConfig, deployCfg, ghApi, normalizeCountry, splitAddrTag, pinnedPortOf, flagOf, countryLabel, countrySummary, countryGroups, countryOfAddr, dialTarget, pumpTunnel, tunnelAuth, handleTunnelConnection, tunnelPaths, isTunnelPath,
  DEFAULT_REPOS, REPO_TTL_MS, sanitizeRepos, parseRepoFeed, refreshRepos, repoHealthyPool, withRepoPool, maybeRepoRefresh,
  DEFAULT_PROXY_REPOS, PROXY_REPO_TTL_MS, sanitizeProxyRepos, parseProxyFeed, refreshProxyRepos, proxyRepoHealthyPool, withProxyRepoPool, maybeProxyRepoRefresh, echConfigList,
  buildWarpOutbounds, parseExtUris, extSubContent,
  effectiveSni, scanSniOf, normalizeProxyList, addressList, buildConfigEntries, vlessLink, trojanLink, linkContext, buildClashYaml, buildSingboxConfig, subscriptionHeaders,
  TLS_PORTS, PLAIN_PORTS, DEFAULT_CLEAN_ADDRESSES, SCAN_RANGES,
  handleRequest, handleApi, selfInfo, geoLookup, dnsCacheGet, dnsCachePut, markRelayFailed, relayCool,
  loginPage, panelPage, userInfoPage, camouflagePage,
};
