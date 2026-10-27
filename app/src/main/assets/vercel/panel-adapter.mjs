// Cat Client — runs the Cat Panel (a Cloudflare-Worker script) on a Vercel Node.js Function.
// Generated and uploaded by the Cat Client app ("Panel on Vercel"). Licence: GPL-3.0 (Cat Client).
//
// Vercel Functions accept WebSocket upgrades (GA since 2026-10-20) and may open outbound TCP
// with node:net, so the panel's own VLESS/Trojan-over-WebSocket data plane works here as it does
// on Cloudflare. This file only swaps the platform layer:
//   WebSocketPair + Response(101)  -> `ws` WebSocketServer (noServer) bound to the panel's pair
//   cloudflare:sockets connect()   -> node:net / node:tls with Web streams
//   request.cf / cf-connecting-ip  -> Vercel x-vercel-ip-* / x-forwarded-for headers
//   env.CAT_KV                     -> Vercel Blob (private store the app creates and connects:
//                                     BLOB_STORE_ID / BLOB_READ_WRITE_TOKEN), or Upstash Redis REST
//                                     when KV_REST_API_URL/TOKEN exist; without either the panel runs
//                                     memory-only and refuses to create users (they would vanish).
import http from 'node:http';
import net from 'node:net';
import dns from 'node:dns';
import tls from 'node:tls';
import { Readable, Duplex } from 'node:stream';
import { AsyncLocalStorage } from 'node:async_hooks';
import { WebSocketServer } from 'ws';

/* ------------------------------ sockets ------------------------------ */
function webSocketFromNode(sock, opened) {
  let web = null;
  const streams = () => (web ||= Duplex.toWeb(sock));
  let closedResolve;
  let closedReject;
  const closed = new Promise((res, rej) => { closedResolve = res; closedReject = rej; });
  closed.catch(() => {});
  sock.once('close', (hadError) => (hadError ? closedReject(new Error('socket error')) : closedResolve()));
  sock.on('error', () => {});
  return {
    get readable() { return streams().readable; },
    get writable() { return streams().writable; },
    opened,
    closed,
    close() { try { sock.destroy(); } catch (_) { } return closed.catch(() => {}); },
    startTls(options = {}) {
      const servername = options.expectedServerHostname || sock.__catHost;
      const secure = tls.connect({ socket: sock, servername: net.isIP(servername) ? undefined : servername, ALPNProtocols: ['http/1.1'] });
      const ready = new Promise((res, rej) => { secure.once('secureConnect', () => res({})); secure.once('error', rej); });
      ready.catch(() => {});
      return webSocketFromNode(secure, ready);
    },
  };
}

// 5-minute DNS cache for outgoing tunnels (each new connection used to resolve again).
const dnsCache = new Map();
function cachedLookup(hostname, options, callback) {
  if (typeof options === 'function') { callback = options; options = {}; }
  const opts = typeof options === 'number' ? { family: options } : (options || {});
  const key = hostname + '|' + (opts.family || 0);
  const reply = (list) => (opts.all ? callback(null, list) : callback(null, list[0].address, list[0].family));
  const hit = dnsCache.get(key);
  if (hit && hit.exp > Date.now()) { reply(hit.list); return; }
  dns.lookup(hostname, { family: opts.family || 0, hints: opts.hints, all: true }, (err, list) => {
    if (err || !list || !list.length) { callback(err || Object.assign(new Error('ENOTFOUND ' + hostname), { code: 'ENOTFOUND' })); return; }
    if (dnsCache.size >= 1024) dnsCache.clear();
    dnsCache.set(key, { list, exp: Date.now() + 300_000 });
    reply(list);
  });
}

function connect(address, options = {}) {
  const host = typeof address === 'string' ? address.replace(/:\d+$/, '').replace(/^\[|\]$/g, '') : String(address.hostname).replace(/^\[|\]$/g, '');
  const port = typeof address === 'string' ? Number((address.match(/:(\d+)$/) || [])[1] || 443) : Number(address.port);
  const mode = String(options.secureTransport || 'off');
  let sock;
  let opened;
  if (mode === 'on') {
    sock = tls.connect({ host, port, lookup: cachedLookup, servername: net.isIP(host) ? undefined : host, ALPNProtocols: ['http/1.1'] });
    opened = new Promise((res, rej) => { sock.once('secureConnect', () => res({ remoteAddress: sock.remoteAddress })); sock.once('error', rej); });
  } else {
    sock = net.connect({ host, port, lookup: cachedLookup });
    opened = new Promise((res, rej) => { sock.once('connect', () => res({ remoteAddress: sock.remoteAddress })); sock.once('error', rej); });
  }
  sock.setNoDelay(true);
  sock.setKeepAlive(true, 20_000);
  sock.setTimeout(330_000, () => sock.destroy());
  sock.__catHost = host;
  opened.catch(() => {});
  return webSocketFromNode(sock, opened);
}
globalThis.__catSockets = { connect };

/* --------------------------- WebSocketPair --------------------------- */
// The panel does: pair = new WebSocketPair(); server.accept(); ...; return new Response(null, {status: 101, webSocket: client}).
// `server` is what the panel talks to; `client` is handed back to the runtime, which here means:
// bind it to the real `ws` connection after the HTTP upgrade completes.
function toArrayBuffer(data) {
  if (data instanceof ArrayBuffer) return data;
  if (Array.isArray(data)) data = Buffer.concat(data);
  return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
}
function safeCode(code) {
  const c = Number(code) || 1000;
  return c === 1000 || (c >= 3000 && c <= 4999) || [1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011].includes(c) ? c : 1000;
}
class ServerSide extends EventTarget {
  constructor() { super(); this.readyState = 0; this.ws = null; this.queue = []; this.closedEarly = null; }
  accept() { if (this.readyState === 0) this.readyState = 1; }
  // Bytes accepted but not yet written to the client — the panel pauses the remote above 2 MB.
  get bufferedAmount() { return this.ws ? this.ws.bufferedAmount : this.queue.reduce((n, c) => n + (c.length || 0), 0); }
  bind(ws) {
    this.ws = ws;
    // WebSocket ping every 25 s: keeps mobile-carrier NAT from dropping a quiet tunnel. Every client
    // (Xray, sing-box, mihomo) answers pings itself; no pong is required, so nothing is cut.
    const beat = setInterval(() => { try { if (ws.readyState === 1) ws.ping(); } catch (_) { } }, 25_000);
    beat.unref?.();
    ws.once('close', () => clearInterval(beat));
    ws.on('message', (data, isBinary) => {
      const ev = new Event('message');
      ev.data = isBinary ? toArrayBuffer(data) : Buffer.from(toArrayBuffer(data)).toString('utf8');
      this.dispatchEvent(ev);
    });
    ws.on('close', (code, reason) => {
      if (this.readyState === 3) return;
      this.readyState = 3;
      const ev = new Event('close');
      ev.code = code; ev.reason = String(reason || ''); ev.wasClean = true;
      this.dispatchEvent(ev);
    });
    ws.on('error', (err) => { const ev = new Event('error'); ev.error = err; ev.message = String(err && err.message || err); this.dispatchEvent(ev); });
    for (const chunk of this.queue) ws.send(chunk);
    this.queue = [];
    if (this.closedEarly) ws.close(...this.closedEarly);
  }
  send(data) {
    if (this.readyState !== 1) throw new Error('WebSocket is not open');
    const payload = typeof data === 'string' ? data : Buffer.from(data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
    if (this.ws) this.ws.send(payload); else this.queue.push(payload);
  }
  close(code, reason) {
    if (this.readyState >= 2) return;
    this.readyState = 2;
    const args = [safeCode(code), String(reason || '').slice(0, 120)];
    if (this.ws) { try { this.ws.close(...args); } catch (_) { try { this.ws.terminate(); } catch (__) { } } } else this.closedEarly = args;
    setTimeout(() => { if (this.ws && this.readyState !== 3) { try { this.ws.terminate(); } catch (_) { } } }, 5000).unref?.();
  }
}
class WebSocketPair {
  constructor() {
    const server = new ServerSide();
    const client = { __catServer: server };
    this[0] = client;
    this[1] = server;
  }
}
globalThis.WebSocketPair = WebSocketPair;

// Response(null, {status: 101, webSocket}) is a Workers-only shape; Node's Response rejects 101.
const NativeResponse = globalThis.Response;
class CatResponse extends NativeResponse {
  constructor(body, init) {
    if (init && Number(init.status) === 101) {
      super(null, { status: 200, headers: init.headers });
      Object.defineProperty(this, '__catUpgrade', { value: init.webSocket || null });
    } else {
      super(body, init);
    }
  }
  static [Symbol.hasInstance](value) { return value instanceof NativeResponse; }
}
globalThis.Response = CatResponse;

/* -------------------------------- KV --------------------------------- */
function upstashKv(url, token) {
  const call = async (cmd) => {
    const r = await fetch(url.replace(/\/$/, ''), { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' }, body: JSON.stringify(cmd) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || j.error) throw new Error('kv: ' + (j.error || r.status));
    return j.result;
  };
  return {
    async get(key, type) {
      const v = await call(['GET', 'cat:' + key]);
      if (v == null) return null;
      const t = typeof type === 'string' ? type : (type && type.type) || 'text';
      if (t === 'json') { try { return JSON.parse(v); } catch (_) { return null; } }
      if (t === 'arrayBuffer') return new TextEncoder().encode(v).buffer;
      return v;
    },
    async put(key, value, opts) {
      const v = typeof value === 'string' ? value : value instanceof ArrayBuffer ? new TextDecoder().decode(value) : String(value);
      const cmd = ['SET', 'cat:' + key, v];
      if (opts && Number(opts.expirationTtl) > 0) cmd.push('EX', String(Math.max(60, Math.floor(opts.expirationTtl))));
      await call(cmd);
    },
    async delete(key) { await call(['DEL', 'cat:' + key]); },
    async list(opts = {}) {
      const prefix = 'cat:' + (opts.prefix || '');
      let cursor = String(opts.cursor || '0');
      const keys = [];
      const limit = Math.min(1000, Number(opts.limit) || 1000);
      do {
        const [next, batch] = await call(['SCAN', cursor, 'MATCH', prefix.replace(/[*?[\]]/g, '\\$&') + '*', 'COUNT', '200']);
        cursor = String(next);
        for (const k of batch || []) keys.push({ name: String(k).slice(4) });
      } while (cursor !== '0' && keys.length < limit);
      return { keys: keys.slice(0, limit), list_complete: cursor === '0', cursor: cursor === '0' ? undefined : cursor };
    },
  };
}

/*
 * Vercel Blob as the panel's KV. Hobby includes ~10,000 reads and ~2,000 writes a month, so keys
 * are split by value:
 *   PERSIST  settings / users / uuid: written at once (owner actions only, a handful a month).
 *   THROTTLE traffic counters, last-seen, login audit: newest value kept in memory and written at
 *            most once per interval per instance (dirty values ride along the next request).
 *   memory   everything else (Cloudflare IP caches, rate-limit counters, event ring, monitor):
 *            per-instance only, exactly like a cold Worker isolate.
 * Reads are cached per instance: 15 s while serving HTTP (panel, subscriptions: a user created a
 * moment ago on another instance is found), 15 min while serving tunnels (the bulk of requests).
 */
const reqScope = new AsyncLocalStorage();
const KV_PERSIST = /^cat:v6:(settings|users|uuid|cfdeploy)$/;
const KV_THROTTLE = [[/^traffic:/, 3 * 3600e3], [/^seen:/, 6 * 3600e3], [/^cat:v6:appseen$/, 12 * 3600e3], [/^cat:v6:loginaudit$/, 30 * 60e3], [/^cat_sni_lab_v1$/, 6 * 3600e3]];
const KV_PREFIX = 'catkv/';
export function blobKv(client, clock = () => Date.now()) {
  const mem = new Map(); // key -> { value, at, exp }
  const lastWrite = new Map();
  const dirty = new Map();
  const throttleOf = (key) => { for (const [re, ms] of KV_THROTTLE) if (re.test(key)) return ms; return 0; };
  const stored = (key) => KV_PERSIST.test(key) || throttleOf(key) > 0;
  // Hex keeps blob paths to [0-9a-f] (no %-escapes a server could decode differently) and stays
  // prefix-preserving for list().
  const hex = (t) => Buffer.from(String(t), 'utf8').toString('hex');
  const path = (key) => KV_PREFIX + hex(key);
  const ttl = () => ((reqScope.getStore() || {}).tunnel ? 15 * 60e3 : 15e3);
  const write = async (key, value) => { lastWrite.set(key, clock()); dirty.delete(key); await client.put(path(key), value); };
  const flushDue = async () => {
    for (const [key, value] of [...dirty]) {
      if (!lastWrite.has(key) || clock() - lastWrite.get(key) >= throttleOf(key)) { try { await write(key, value); } catch (_) { dirty.set(key, value); } }
    }
  };
  const textOf = (t, v) => {
    if (v == null) return null;
    if (t === 'json') { try { return JSON.parse(v); } catch (_) { return null; } }
    if (t === 'arrayBuffer') return new TextEncoder().encode(v).buffer;
    return v;
  };
  return {
    async get(key, type) {
      const t = typeof type === 'string' ? type : (type && type.type) || 'text';
      const hit = mem.get(key);
      const now = clock();
      if (hit && hit.exp && now > hit.exp) mem.delete(key);
      else if (hit && (!stored(key) || dirty.has(key) || now - hit.at < ttl())) return textOf(t, hit.value);
      if (!stored(key)) return null;
      if (dirty.size) await flushDue();
      // One retry: the panel treats a failed read as "no users", and a following owner save would
      // then overwrite the real list. Errors still propagate after the retry.
      let value;
      try { value = await client.get(path(key)); } catch (_) { value = await client.get(path(key)); }
      mem.set(key, { value, at: clock() });
      return textOf(t, value);
    },
    async put(key, value, opts) {
      const v = typeof value === 'string' ? value : value instanceof ArrayBuffer ? new TextDecoder().decode(value) : String(value);
      const exp = opts && Number(opts.expirationTtl) > 0 ? clock() + Math.max(60, Number(opts.expirationTtl)) * 1000 : 0;
      mem.set(key, { value: v, at: clock(), exp });
      if (KV_PERSIST.test(key)) return write(key, v);
      const every = throttleOf(key);
      if (!every) return;
      if (!lastWrite.has(key) || clock() - lastWrite.get(key) >= every) return write(key, v);
      dirty.set(key, v);
    },
    async delete(key) {
      mem.delete(key);
      dirty.delete(key);
      if (stored(key)) await client.del(path(key));
    },
    async list(opts = {}) {
      const prefix = String(opts.prefix || '');
      const names = new Set([...mem.keys()].filter((k) => k.startsWith(prefix)));
      for (const p of await client.list(KV_PREFIX + hex(prefix))) {
        const h = p.slice(KV_PREFIX.length);
        if (/^([0-9a-f]{2})+$/.test(h)) names.add(Buffer.from(h, 'hex').toString('utf8'));
      }
      const keys = [...names].sort().slice(0, Math.min(1000, Number(opts.limit) || 1000)).map((name) => ({ name }));
      return { keys, list_complete: true };
    },
    _flush: flushDue,
  };
}

async function vercelBlobClient() {
  const sdk = await import('@vercel/blob');
  const auth = process.env.BLOB_READ_WRITE_TOKEN ? { token: process.env.BLOB_READ_WRITE_TOKEN } : {};
  return {
    async get(p) {
      const r = await sdk.get(p, { access: 'private', useCache: false, ...auth });
      if (!r || r.statusCode !== 200 || !r.stream) return null;
      const chunks = [];
      for await (const c of r.stream) chunks.push(Buffer.from(c));
      return Buffer.concat(chunks).toString('utf8');
    },
    async put(p, text) {
      await sdk.put(p, text, { access: 'private', addRandomSuffix: false, allowOverwrite: true, contentType: 'application/json', ...auth });
    },
    async del(p) { await sdk.del(p, auth); },
    async list(prefix) {
      const out = [];
      let cursor;
      do {
        const r = await sdk.list({ prefix, cursor, limit: 1000, ...auth });
        for (const b of r.blobs || []) out.push(b.pathname);
        cursor = r.hasMore ? r.cursor : undefined;
      } while (cursor);
      return out;
    },
  };
}

const kvUrl = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '';
const kvToken = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';
const hasBlob = !!(process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN);
let CAT_KV;
let KV_KIND = 'memory';
if (kvUrl && kvToken) { CAT_KV = upstashKv(kvUrl, kvToken); KV_KIND = 'upstash'; }
else if (hasBlob) {
  try { CAT_KV = blobKv(await vercelBlobClient()); KV_KIND = 'blob'; } catch (e) { console.error('blob storage unavailable: ' + ((e && e.message) || e)); }
}

/* ------------------------------- panel -------------------------------- */
// Layout written by the app: api/index.mjs (this file) + lib/catpanel.mjs (the panel with
// `import("cloudflare:sockets")` swapped for the socket shim). vercel.json includeFiles keeps lib/.
const panelModule = await import('../lib/catpanel.mjs');
const worker = panelModule.default;
if (panelModule._testing && typeof panelModule._testing.__setSockets === 'function') panelModule._testing.__setSockets(globalThis.__catSockets);

const ENV_SKIP = /^(AWS_|LAMBDA_|NODE_|NOW_|VERCEL_|_|PATH$|HOME$|PWD$|LANG$|TZ$|KV_|UPSTASH_|REDIS_|BLOB_)/;
function buildEnv() {
  const env = {};
  for (const [k, v] of Object.entries(process.env)) if (!ENV_SKIP.test(k) && /^[A-Z][A-Z0-9_]*$/.test(k)) env[k] = v;
  if (CAT_KV) env.CAT_KV = CAT_KV;
  env.CAT_PLATFORM = 'vercel';
  env.CAT_STORAGE = KV_KIND;
  return env;
}
const ENV = buildEnv();

function waitUntilFn() {
  const ctx = globalThis[Symbol.for('@vercel/request-context')];
  const store = ctx && typeof ctx.get === 'function' ? ctx.get() : null;
  return (p) => {
    const promise = Promise.resolve(p).catch(() => {});
    if (store && typeof store.waitUntil === 'function') store.waitUntil(promise);
  };
}

function clientIp(req) {
  const xff = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return String(req.headers['x-real-ip'] || xff || req.socket.remoteAddress || '');
}

function toRequest(req, withBody) {
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || 'localhost');
  const url = 'https://' + host + (req.url || '/');
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) {
    if (v == null || k.startsWith(':')) continue;
    if (Array.isArray(v)) v.forEach((x) => headers.append(k, x)); else headers.set(k, String(v));
  }
  const ip = clientIp(req);
  if (ip && !headers.has('cf-connecting-ip')) headers.set('cf-connecting-ip', ip);
  const init = { method: req.method, headers };
  if (withBody && req.method !== 'GET' && req.method !== 'HEAD') { init.body = Readable.toWeb(req); init.duplex = 'half'; }
  const request = new Request(url, init);
  const decode = (x) => { try { return decodeURIComponent(String(x || '')); } catch (_) { return String(x || ''); } };
  const cf = {
    country: String(req.headers['x-vercel-ip-country'] || ''),
    city: decode(req.headers['x-vercel-ip-city']),
    region: String(req.headers['x-vercel-ip-country-region'] || ''),
    latitude: String(req.headers['x-vercel-ip-latitude'] || ''),
    longitude: String(req.headers['x-vercel-ip-longitude'] || ''),
    timezone: String(req.headers['x-vercel-ip-timezone'] || ''),
    colo: String(req.headers['x-vercel-id'] || '').split(':')[0] || 'vercel',
    asn: 0,
  };
  try { Object.defineProperty(request, 'cf', { value: cf }); } catch (_) { }
  return request;
}

async function writeResponse(res, response) {
  const headers = {};
  response.headers.forEach((v, k) => { if (k !== 'set-cookie' && k !== 'content-length' && k !== 'transfer-encoding' && k !== 'content-encoding') headers[k] = v; });
  const cookies = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  if (cookies.length) headers['set-cookie'] = cookies;
  res.writeHead(response.status, headers);
  if (!response.body) { res.end(); return; }
  const body = Readable.fromWeb(response.body);
  body.on('error', () => { try { res.destroy(); } catch (_) { } });
  body.pipe(res);
}

const server = http.createServer((req, res) => reqScope.run({ tunnel: false }, async () => {
  try {
    const response = await worker.fetch(toRequest(req, true), ENV, { waitUntil: waitUntilFn(), passThroughOnException() {} });
    await writeResponse(res, response);
  } catch (e) {
    if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('panel error');
  }
}));

const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false, maxPayload: 2 * 1024 * 1024, handleProtocols: (protocols) => protocols.values().next().value ?? false });
server.on('upgrade', (req, socket, head) => reqScope.run({ tunnel: true }, async () => {
  socket.on('error', () => {});
  try {
    const response = await worker.fetch(toRequest(req, false), ENV, { waitUntil: waitUntilFn(), passThroughOnException() {} });
    const pairClient = response && response.__catUpgrade;
    if (!pairClient || !pairClient.__catServer) {
      const text = response ? await response.text().catch(() => '') : '';
      socket.end('HTTP/1.1 ' + (response ? response.status : 500) + ' Not Upgraded\r\ncontent-type: text/plain\r\nconnection: close\r\ncontent-length: ' + Buffer.byteLength(text) + '\r\n\r\n' + text);
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => pairClient.__catServer.bind(ws));
  } catch (e) {
    socket.destroy();
  }
}));

export default server;
