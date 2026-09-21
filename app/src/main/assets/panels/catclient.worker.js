/**
 * 🐱 Cat Panel — single-file Cloudflare Worker panel (VLESS / Trojan / WARP)
 *
 * Version: 2.0.0
 *
 * HOW TO USE
 * 1. Cloudflare Dashboard → Workers & Pages → Create Worker → edit code → paste THIS file.
 * 2. Deploy. Open https://<your-worker>.<your-subdomain>.workers.dev → panel.
 * 3. Copy the subscription link into Cat Client (or any VLESS/Trojan/WARP client).
 *
 * DATA PLANE
 *  - VLESS + WebSocket  on  /ws        (TLS terminated at Cloudflare edge)
 *  - Trojan + WebSocket on  /trojan
 *  - WARP (WarpProxy) link is client-side (no worker data path)
 *
 *  Two modes:
 *  a) STANDALONE (default): the worker forwards HTTP/1.1 requests (browsing,
 *     API calls) to the destination parsed from the VLESS header. TLS is
 *     terminated at Cloudflare, so this mode carries HTTP(S) web traffic.
 *  b) REMOTE (full TCP): set REMOTE to a wss:// relay (e.g. a BackPack
 *     reverse tunnel or a remote VLESS-WS listener). Every frame is piped
 *     through verbatim — full fidelity for any protocol.
 *
 * SNI + CLEAN IP (Cloudflare IP whitelist)
 *  - Clients may connect to ANY Cloudflare edge IP ("ایپی سفید") using the
 *    worker hostname — or any SNI from SNI_LIST — as the TLS SNI.
 *  - The worker validates the incoming X-Forwarded-Sni header against the
 *    hostname, SNI and SNI_LIST and rejects unknown SNIs (403).
 *  - Set CF_IPS to a comma-separated list of clean Cloudflare IPs and the
 *    subscription will contain ready-to-use variants for every IP.
 *
 * ENVIRONMENT VARIABLES (Workers → Settings → Variables & Secrets, all optional)
 *  UUID           Stable UUID used in links (auto-derived from host when empty)
 *  SNI            Default SNI written into generated links (default: worker host)
 *  SNI_LIST       Comma-separated extra SNIs accepted by this worker
 *  CF_IPS         Comma-separated clean Cloudflare IPs to publish in /sub
 *  PORT           Port used in generated links (default 443)
 *  VLESS_PATH     VLESS WebSocket path (default /ws?ed=2048)
 *  TROJAN_PATH    Trojan WebSocket path (default /trojan)
 *  TROJAN_PASS    Trojan password (default: same as UUID)
 *  REMOTE         Optional wss:// relay for full-TCP tunnel mode
 *  PANEL_PASSWORD When set, the HTML panel requires ?p=<password>
 *  ENABLE_WARP    Set "false" to omit the warp:// link
 *  USER_TOTAL     subscription-userinfo total bytes (default 1 TiB)
 */

const CAT_PANEL_VERSION = '2.0.0';

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

function splitCsv(value) {
  return String(value || '').split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean);
}

function formatAddr(ipOrHost) {
  const v = String(ipOrHost).trim().replace(/^\[/, '').replace(/\]$/, '');
  return v.includes(':') ? '[' + v + ']' : v;
}

async function deriveUuid(host) {
  try {
    const digest = await crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode('cat-panel:uuid:' + host),
    );
    const b = new Uint8Array(digest);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const hex = Array.from(b).map((x) => x.toString(16).padStart(2, '0')).join('');
    return (
      hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) +
      '-' + hex.slice(16, 20) + '-' + hex.slice(20, 32)
    );
  } catch (e) {
    return crypto.randomUUID();
  }
}

async function resolveUuid(host, env) {
  const explicit = String(env.UUID || '').trim();
  return explicit || deriveUuid(host);
}

function effectiveSni(host, env) {
  const sni = String(env.SNI || '').trim().toLowerCase();
  return sni || String(host).toLowerCase();
}

function allowedSnis(host, env) {
  const set = new Set();
  set.add(String(host).toLowerCase());
  set.add(effectiveSni(host, env));
  splitCsv(env.SNI_LIST).forEach((s) => set.add(s.toLowerCase()));
  return set;
}

/**
 * SNI whitelist gate. When a client connects through a clean Cloudflare IP,
 * the TLS SNI travels in X-Forwarded-Sni; it must match the worker host or
 * one of the configured SNIs.
 */
function sniAllowed(request, host, env) {
  const sni = (request.headers.get('X-Forwarded-Sni') || '').trim().toLowerCase();
  if (!sni) return true; // connected directly by hostname
  return allowedSnis(host, env).has(sni);
}

/* ------------------------------------------------------------------ */
/* VLESS wire format                                                   */
/* ------------------------------------------------------------------ */

/**
 * Parse a VLESS over WebSocket first packet:
 *   1 version, 1 cmd, 1 atyp, addr, 2 port, then payload (HTTP request,
 *   because TLS was already terminated at the Cloudflare edge).
 */
function parseVless(bytes) {
  if (!bytes || bytes.length < 6) return null;
  const atyp = bytes[2];
  let offset = 3;
  let host = '';
  if (atyp === 0) {
    if (bytes.length < offset + 6) return null;
    host = bytes[offset] + '.' + bytes[offset + 1] + '.' + bytes[offset + 2] + '.' + bytes[offset + 3];
    offset += 4;
  } else if (atyp === 1) {
    const len = bytes[offset];
    if (bytes.length < offset + 1 + len + 2) return null;
    host = new TextDecoder().decode(bytes.subarray(offset + 1, offset + 1 + len));
    offset += 1 + len;
  } else if (atyp === 2) {
    if (bytes.length < offset + 18) return null;
    const groups = [];
    for (let i = 0; i < 16; i += 2) {
      groups.push(((bytes[offset + i] << 8) | bytes[offset + i + 1]).toString(16));
    }
    host = '[' + groups.join(':') + ']';
    offset += 16;
  } else {
    return null;
  }
  if (bytes.length < offset + 2) return null;
  const port = (bytes[offset] << 8) | bytes[offset + 1];
  return { host, port, rest: bytes.slice(offset + 2) };
}

/** Parse an HTTP/1.1 request whose first line may be origin- or proxy-form. */
function parseHttpRequest(raw) {
  const text = new TextDecoder().decode(raw);
  const headerEnd = text.indexOf('\r\n\r\n');
  const head = headerEnd >= 0 ? text.slice(0, headerEnd) : text;
  const body = headerEnd >= 0 ? text.slice(headerEnd + 4) : '';
  const lines = head.split('\r\n');
  const requestLine = (lines[0] || 'GET / HTTP/1.1').split(' ');
  const method = (requestLine[0] || 'GET').toUpperCase();
  const target = requestLine[1] || '/';
  const headers = [];
  for (let i = 1; i < lines.length; i++) {
    const idx = lines[i].indexOf(':');
    if (idx > 0) headers.push([lines[i].slice(0, idx).trim(), lines[i].slice(idx + 1).trim()]);
  }
  return { method, target, headers, body };
}

/* ------------------------------------------------------------------ */
/* standalone HTTP-forward data plane                                  */
/* ------------------------------------------------------------------ */

const REASONS = {
  200: 'OK', 201: 'Created', 204: 'No Content', 301: 'Moved Permanently',
  302: 'Found', 303: 'See Other', 304: 'Not Modified', 307: 'Temporary Redirect',
  308: 'Permanent Redirect', 400: 'Bad Request', 401: 'Unauthorized',
  403: 'Forbidden', 404: 'Not Found', 405: 'Method Not Allowed',
  408: 'Request Timeout', 429: 'Too Many Requests', 500: 'Internal Server Error',
  502: 'Bad Gateway', 503: 'Service Unavailable', 504: 'Gateway Timeout',
};

const SKIP_RESPONSE_HEADERS = new Set([
  'transfer-encoding', 'content-encoding', 'content-length',
  'connection', 'keep-alive', 'upgrade', 'proxy-connection', 'server', 'cf-ray',
  'expect-ct', 'alt-svc', 'nel', 'report-to',
]);

function sendHttpError(ws, status, message) {
  try {
    const msg = String(message || '');
    const body = 'HTTP/1.1 ' + status + ' ' + (REASONS[status] || 'Error') + '\r\n' +
      'Content-Type: text/plain; charset=utf-8\r\n' +
      'Content-Length: ' + new TextEncoder().encode(msg).byteLength + '\r\n' +
      'Connection: close\r\n\r\n' + msg;
    ws.send(new TextEncoder().encode(body));
    ws.close(1011);
  } catch (e) { /* socket already gone */ }
}

/**
 * Forward the HTTP request hidden inside a VLESS/Trojan-WS stream to its
 * target and stream the response back over the WebSocket.
 */
async function httpForward(ws, parsed) {
  const req = parseHttpRequest(parsed.rest);
  if (req.method === 'CONNECT') {
    sendHttpError(ws, 405, 'CONNECT is not supported (TLS is terminated at the edge). Use REMOTE mode for full TCP.');
    return;
  }
  let url;
  if (/^https?:\/\//i.test(req.target)) {
    url = req.target;
  } else {
    const useHttps = parsed.port === 443 || parsed.port === 8443;
    const path = req.target.startsWith('/') ? req.target : '/' + req.target;
    url = (useHttps ? 'https://' : 'http://') + parsed.host + path;
  }

  const outHeaders = [['accept-encoding', 'identity'], ['user-agent', 'Mozilla/5.0 (compatible; CatPanel/' + CAT_PANEL_VERSION + ')']];
  const seen = new Set();
  for (const [k, v] of req.headers) {
    const lk = k.toLowerCase();
    if (lk === 'host' || lk === 'connection' || lk === 'upgrade' || lk === 'keep-alive' ||
        lk.startsWith('proxy-') || lk === 'transfer-encoding' || lk === 'content-length' ||
        lk === 'accept-encoding' || lk === 'user-agent') continue;
    if (seen.has(lk)) continue;
    seen.add(lk);
    outHeaders.push([lk, v]);
  }

  let resp;
  try {
    resp = await fetch(url, {
      method: req.method,
      headers: outHeaders,
      body: ['POST', 'PUT', 'PATCH'].includes(req.method) && req.body ? req.body : undefined,
      redirect: 'manual',
    });
  } catch (e) {
    sendHttpError(ws, 502, 'Upstream unreachable: ' + (e && e.message ? e.message : e));
    return;
  }

  let head = 'HTTP/1.1 ' + resp.status + ' ' + (REASONS[resp.status] || 'OK') + '\r\n';
  for (const [k, v] of resp.headers) {
    if (SKIP_RESPONSE_HEADERS.has(k.toLowerCase())) continue;
    head += k + ': ' + v + '\r\n';
  }
  head += 'Connection: close\r\n\r\n';
  try { ws.send(new TextEncoder().encode(head)); } catch (e) { return; }

  if (resp.status === 204 || resp.status === 304 || req.method === 'HEAD') {
    try { ws.close(1000); } catch (e) {}
    return;
  }

  const reader = resp.body ? resp.body.getReader() : null;
  if (reader) {
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value && value.byteLength > 0) ws.send(value);
      }
    } catch (e) { /* connection torn down */ }
  }
  try { ws.close(1000); } catch (e) {}
}

/* ------------------------------------------------------------------ */
/* REMOTE full-TCP tunnel mode                                         */
/* ------------------------------------------------------------------ */

async function tunnelToRemote(remoteUrl, clientWs, firstData) {
  let remote;
  try {
    remote = new WebSocket(remoteUrl);
  } catch (e) {
    sendHttpError(clientWs, 502, 'REMOTE websocket init failed');
    return;
  }
  remote.binaryType = 'arraybuffer';
  const opened = new Promise((resolve, reject) => {
    remote.addEventListener('open', () => resolve(), { once: true });
    remote.addEventListener('error', () => reject(new Error('remote open failed')), { once: true });
  });
  try {
    await opened;
  } catch (e) {
    sendHttpError(clientWs, 502, 'REMOTE unavailable: ' + remoteUrl);
    return;
  }
  if (firstData) { try { remote.send(firstData); } catch (e) {} }
  const pipe = (from, to) => {
    from.addEventListener('message', (e) => {
      if (to.readyState === 1) { try { to.send(e.data); } catch (err) {} }
    });
  };
  pipe(clientWs, remote);
  pipe(remote, clientWs);
  const done = () => {
    try { remote.close(); } catch (e) {}
    try { clientWs.close(); } catch (e) {}
  };
  clientWs.addEventListener('close', done);
  remote.addEventListener('close', done);
  remote.addEventListener('error', done);
}

function handleDataWebSocket(ws, env) {
  let started = false;
  ws.addEventListener('message', async (event) => {
    if (started) return; // only the first frame carries the VLESS header
    started = true;
    const data = event.data;

    if (env.REMOTE) {
      // full-TCP mode: pipe everything verbatim to the relay, first frame included
      await tunnelToRemote(String(env.REMOTE), ws, data);
      return;
    }
    let bytes;
    if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
    else if (typeof data === 'string') bytes = new TextEncoder().encode(data);
    else if (data && data.byteLength !== undefined) bytes = new Uint8Array(data);
    else bytes = new Uint8Array(0);
    const parsed = parseVless(bytes);
    if (!parsed) {
      sendHttpError(ws, 400, 'Malformed VLESS header');
      return;
    }
    await httpForward(ws, parsed);
  });
  ws.addEventListener('error', () => {});
}

/* ------------------------------------------------------------------ */
/* subscription content                                                */
/* ------------------------------------------------------------------ */

function panelHosts(host, env) {
  const ips = splitCsv(env.CF_IPS);
  return { ips, all: [String(host), ...ips] };
}

function buildSubLinks(host, env, uuid) {
  const port = Number(env.PORT || 443);
  const sni = effectiveSni(host, env);
  const vlessPath = String(env.VLESS_PATH || '/ws?ed=2048');
  const trojanPath = String(env.TROJAN_PATH || '/trojan');
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const hostParam = encodeURIComponent(String(host));
  const sniParam = encodeURIComponent(sni);
  const { ips } = panelHosts(host, env);
  const links = [];

  const vless = (addr, name) =>
    'vless://' + uuid + '@' + formatAddr(addr) + ':' + port +
    '?encryption=none&security=tls&sni=' + sniParam +
    '&type=ws&path=' + encodeURIComponent(vlessPath) +
    '&host=' + hostParam + '&alpn=h2,http/1.1&fp=randomized#' + encodeURIComponent(name);
  const trojan = (addr, name) =>
    'trojan://' + trojanPass + '@' + formatAddr(addr) + ':' + port +
    '?security=tls&sni=' + sniParam +
    '&type=ws&path=' + encodeURIComponent(trojanPath) +
    '&host=' + hostParam + '&alpn=h2,http/1.1&fp=randomized#' + encodeURIComponent(name);

  links.push(vless(String(host), 'Cat VLESS WS'));
  links.push(trojan(String(host), 'Cat Trojan WS'));
  for (const ip of ips) {
    links.push(vless(ip, 'Cat VLESS WS ' + ip));
    links.push(trojan(ip, 'Cat Trojan WS ' + ip));
  }
  if (String(env.ENABLE_WARP).toLowerCase() !== 'false') links.push('warp://#Cat WARP');
  return links;
}

function yamlQuote(s) {
  return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function buildClashYaml(host, env, uuid) {
  const port = Number(env.PORT || 443);
  const sni = effectiveSni(host, env);
  const vlessPath = String(env.VLESS_PATH || '/ws?ed=2048');
  const trojanPath = String(env.TROJAN_PATH || '/trojan');
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const { ips } = panelHosts(host, env);
  const proxyNames = [];
  const proxyBlocks = [];

  const addProxy = (name, block) => {
    proxyNames.push(name);
    proxyBlocks.push('  - name: ' + yamlQuote(name) + '\n' + block);
  };
  const vlessBlock = (addr) =>
    '    type: vless\n' +
    '    server: ' + addr + '\n' +
    '    port: ' + port + '\n' +
    '    uuid: ' + uuid + '\n' +
    '    network: ws\n' +
    '    udp: true\n' +
    '    tls: true\n' +
    '    servername: ' + sni + '\n' +
    '    ws-opts:\n' +
    '      path: ' + vlessPath + '\n' +
    '      headers:\n' +
    '        Host: ' + host;
  const trojanBlock = (addr) =>
    '    type: trojan\n' +
    '    server: ' + addr + '\n' +
    '    port: ' + port + '\n' +
    '    password: ' + trojanPass + '\n' +
    '    network: ws\n' +
    '    udp: true\n' +
    '    tls: true\n' +
    '    servername: ' + sni + '\n' +
    '    ws-opts:\n' +
    '      path: ' + trojanPath + '\n' +
    '      headers:\n' +
    '        Host: ' + host;

  addProxy('Cat VLESS', vlessBlock(host));
  addProxy('Cat Trojan', trojanBlock(host));
  for (const ip of ips) {
    addProxy('Cat VLESS ' + ip, vlessBlock(ip));
    addProxy('Cat Trojan ' + ip, trojanBlock(ip));
  }

  const group = proxyNames.map((n) => '      - ' + yamlQuote(n)).join('\n');
  return (
    '# Cat Panel v' + CAT_PANEL_VERSION + ' — Mihomo/Clash config\n' +
    '# Generated for https://' + host + '\n' +
    'mixed-port: 7890\n' +
    'allow-lan: false\n' +
    'mode: rule\n' +
    'log-level: info\n' +
    'ipv6: false\n' +
    'dns:\n' +
    '  enable: true\n' +
    '  enhanced-mode: fake-ip\n' +
    '  fake-ip-range: 198.18.0.1/16\n' +
    '  nameserver:\n' +
    '    - 1.1.1.1\n' +
    '    - 8.8.8.8\n' +
    'proxies:\n' + proxyBlocks.join('\n') + '\n' +
    'proxy-groups:\n' +
    '  - name: "Proxy"\n' +
    '    type: select\n' +
    '    proxies:\n' + group + '\n' +
    '  - name: "Auto"\n' +
    '    type: url-test\n' +
    '    url: "https://www.gstatic.com/generate_204"\n' +
    '    interval: 300\n' +
    '    tolerance: 50\n' +
    '    proxies:\n' + group + '\n' +
    'rules:\n' +
    '  - GEOIP,LAN,direct\n' +
    '  - GEOSITE,iran,direct\n' +
    '  - MATCH,Proxy\n'
  );
}

function subUserInfoHeader(env) {
  const total = Number(env.USER_TOTAL || 1099511627776);
  return 'upload=0; download=0; total=' + total;
}

/* ------------------------------------------------------------------ */
/* panel HTML                                                          */
/* ------------------------------------------------------------------ */

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

const CODE_URLS = [
  'https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/main/app/src/main/assets/panels/catclient.worker.js',
  'https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/master/app/src/main/assets/panels/catclient.worker.js',
  'https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/arena/01a0c3ae-cat-client/app/src/main/assets/panels/catclient.worker.js',
];

function loginHtml() {
  return (
    '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">\n' +
    '<title>Cat Panel — Login</title><style>' + css() +
    '.form input{width:100%;box-sizing:border-box;background:#18181b;border:1px solid #27272a;border-radius:10px;color:#fff;padding:12px;margin:10px 0 16px;font-size:15px}</style></head><body>\n' +
    '<div class="card"><h1>🐱 Cat Panel</h1><p>Enter the panel password (PANEL_PASSWORD variable).</p>\n' +
    '<form class="form" method="get" action="/"><input type="password" name="p" placeholder="Password" autofocus><button type="submit">Unlock</button></form></div></body></html>'
  );
}

function css() {
  return (
    '*{box-sizing:border-box;margin:0;padding:0}' +
    'body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#000;color:#e4e4e7;min-height:100vh;padding:24px;display:flex;justify-content:center}' +
    '.wrap{width:100%;max-width:760px}' +
    '.card{background:linear-gradient(135deg,#0a0a0a,#170b2b);border:1px solid #2e1065;border-radius:20px;padding:28px;margin-bottom:18px;box-shadow:0 0 50px rgba(124,58,237,.18)}' +
    'h1{font-size:26px;background:linear-gradient(90deg,#a855f7,#d946ef);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:6px}' +
    'h2{color:#c4b5fd;font-size:15px;margin:20px 0 8px;letter-spacing:.4px;text-transform:uppercase}' +
    'p{color:#a1a1aa;line-height:1.6;font-size:14px}' +
    'code,pre{background:#18181b;border:1px solid #27272a;border-radius:10px;padding:12px;font-family:ui-monospace,SFMono-Regular,monospace;font-size:12.5px;word-break:break-all;color:#c4b5fd}' +
    'pre{white-space:pre-wrap}' +
    '.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}' +
    'button{background:linear-gradient(90deg,#7c3aed,#a855f7);color:#fff;border:0;padding:10px 18px;border-radius:10px;font-weight:600;cursor:pointer;font-size:13.5px}' +
    'button:hover{filter:brightness(1.1)}' +
    'a{color:#c4b5fd}' +
    'table{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}' +
    'td,th{border:1px solid #27272a;padding:8px 10px;text-align:left;color:#d4d4d8}' +
    'th{color:#a855f7;background:#12081f}' +
    '.badge{display:inline-block;background:#2e1065;color:#d8b4fe;border-radius:999px;padding:2px 10px;font-size:12px;margin-right:6px}' +
    '.muted{color:#71717a;font-size:12.5px;margin-top:8px}' +
    '.fa{direction:rtl;text-align:right}'
  );
}

function panelHtml(ctx) {
  const { host, sni, uuid, env, links, ips, port, vlessPath, trojanPath, panelPass } = ctx;
  const subUrl = 'https://' + host + '/sub';
  const clashUrl = 'https://' + host + '/clash';
  const ipList = ips.length ? ips.join(', ') : '(not set — add CF_IPS to publish clean-IP variants)';
  const sniList = (env.SNI_LIST || '').toString();
  const rows = links
    .map((l) => '<tr><td>' + esc(l.split('#').pop()) + '</td><td><code>' + esc(l) + '</code></td></tr>')
    .join('\n');
  return (
    '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">\n' +
    '<title>Cat Panel</title><style>' + css() + '</style></head><body><div class="wrap">\n' +
    '<div class="card"><h1>🐱 Cat Panel</h1>\n' +
    '<p>Your personal Cloudflare Worker proxy is online. <span class="badge">v' + CAT_PANEL_VERSION + '</span><span class="badge">VLESS-WS</span><span class="badge">Trojan-WS</span><span class="badge">WARP</span></p>\n' +
    '<h2>Subscription</h2>\n' +
    '<pre id="sub">' + esc(subUrl) + '</pre>\n' +
    '<div class="row">' +
    '<button onclick="navigator.clipboard.writeText(document.getElementById(\'sub\').textContent).then(function(){this.textContent=\'✓ Copied\'}).bind(this)">📋 Copy sub</button>' +
    '<button onclick="window.open(\'catclient://add-sub?url=\'+encodeURIComponent(document.getElementById(\'sub\').textContent))">Open in Cat Client</button>' +
    '<a href="' + esc(clashUrl) + '"><button>Mihomo/Clash YAML</button></a>' +
    '</div>\n' +
    '<p class="muted">Import the sub link: Cat Client → Subscriptions → + → paste URL. (On a phone, "Open in Cat Client" needs the app installed.)</p></div>\n' +
    '<div class="card"><h2>Connection settings</h2>\n' +
    '<table>' +
    '<tr><th>UUID</th><td><code>' + esc(uuid) + '</code></td></tr>' +
    '<tr><th>SNI</th><td><code>' + esc(sni) + '</code></td></tr>' +
    '<tr><th>SNI whitelist</th><td><code>' + esc([host, sni].concat(sniList ? sniList.split(',') : []).join(', ')) + '</code></td></tr>' +
    '<tr><th>Port</th><td>' + port + '</td></tr>' +
    '<tr><th>VLESS path</th><td><code>' + esc(vlessPath) + '</code></td></tr>' +
    '<tr><th>Trojan path</th><td><code>' + esc(trojanPath) + '</code></td></tr>' +
    '<tr><th>REMOTE tunnel</th><td>' + (env.REMOTE ? 'on' : 'off (standalone HTTP-forward mode)') + '</td></tr>' +
    '<tr><th>Panel password</th><td>' + (panelPass ? 'on' : 'off') + '</td></tr>' +
    '</table></div>\n' +
    '<div class="card"><h2>Clean Cloudflare IPs (IP whitelist)</h2>\n' +
    '<p>Point your client at any of these Cloudflare edge IPs and keep the SNI as <code>' + esc(sni) + '</code>. The worker accepts connections whose TLS SNI matches its hostname or the whitelist — that is what makes clean-IP fronting safe.</p>\n' +
    '<pre id="ips">' + esc(ipList) + '</pre>\n' +
    '<div class="row"><button onclick="navigator.clipboard.writeText(document.getElementById(\'ips\').textContent).then(function(){this.textContent=\'✓ Copied\'}).bind(this)">Copy IPs</button></div>' +
    '<p class="muted">The subscription already contains one VLESS + Trojan variant per IP (server=IP, sni=' + esc(sni) + '). You can also find fast IPs for your ISP inside Cat Client → Advanced → IP Scanner (SNI + Spoof).</p></div>\n' +
    '<div class="card"><h2>All configs</h2>\n' +
    '<table><tr><th>Name</th><th>Share link</th></tr>\n' + rows + '\n</table></div>\n' +
    '<div class="card"><h2>How to (re)deploy this panel</h2>\n' +
    '<p>1. Cloudflare Dashboard → Workers &amp; Pages → <b>Create Worker</b> → edit code → paste this worker source (whole file).</p>\n' +
    '<p>2. (Optional) Settings → Variables &amp; Secrets: add UUID, SNI, SNI_LIST, CF_IPS, REMOTE, PANEL_PASSWORD…</p>\n' +
    '<p>3. Deploy, open the worker URL — you are in the panel.</p>\n' +
    '<div class="row"><button id="copycode" onclick="copyCode(this)">📥 Copy worker code</button><a href="https://github.com/mazodimobinhost-creator/cat-client/blob/main/app/src/main/assets/panels/catclient.worker.js" target="_blank"><button>Source on GitHub</button></a></div>\n' +
    '<script>async function copyCode(btn){var urls=[' + CODE_URLS.map((u) => "'" + u + "'").join(',') + '];try{for(var i=0;i<urls.length;i++){var r=await fetch(urls[i]);if(r.ok){var t=await r.text();if(t.length>1000){await navigator.clipboard.writeText(t);btn.textContent="✓ Copied";return;}}}}catch(e){}btn.textContent="Copy failed — open GitHub link";}document.getElementById(\'copycode\').addEventListener(\'click\',function(){});</script></div>\n' +
    '<div class="card"><h2>متن فارسی</h2>\n' +
    '<p class="fa">این پنل روی Cloudflare Worker اجرا می‌شود. لینک سابسکریپشن را در Cat Client → Subscriptions → + وارد کنید. برای اتصال با <b>آی‌پی سفید</b> (IP تمیز) سرور را روی هر IP لیست‌شده بگذارید و SNI همان دامنهٔ پنل را نگه دارید؛ Worker هر SNI بیگانه را رد می‌کند.</p></div>\n' +
    '<div class="card"><p class="muted">Cat Panel v' + CAT_PANEL_VERSION + ' · zero logging · your worker, your account</p></div>\n' +
    '</div></body></html>'
  );
}

/* ------------------------------------------------------------------ */
/* request routing                                                     */
/* ------------------------------------------------------------------ */

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, OPTIONS',
  'access-control-allow-headers': '*',
};

async function fetchHandler(request, env) {
  const url = new URL(request.url);
  const host = (request.headers.get('Host') || url.hostname || '').toLowerCase();
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

  if (!sniAllowed(request, host, env)) {
    return new Response('Forbidden SNI', { status: 403, headers: CORS });
  }

  const uuid = await resolveUuid(host, env);
  const sni = effectiveSni(host, env);
  const vlessPath = String(env.VLESS_PATH || '/ws?ed=2048');
  const trojanPath = String(env.TROJAN_PATH || '/trojan');
  const vlessName = vlessPath.split('?')[0];
  const trojanName = trojanPath.split('?')[0];
  const path = url.pathname;

  if (path === vlessName || path === trojanName) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    handleDataWebSocket(server, env);
    return new Response(null, { status: 101, statusText: 'Switching Protocols', webSocket: client });
  }

  if (path === '/sub' || path === '/sub/') {
    const body = buildSubLinks(host, env, uuid).join('\n');
    return new Response(body, {
      headers: Object.assign({
        'content-type': 'text/plain; charset=utf-8',
        'subscription-userinfo': subUserInfoHeader(env),
      }, CORS),
    });
  }
  if (path === '/sub64' || path === '/sub64/') {
    const body = buildSubLinks(host, env, uuid).join('\n');
    const b64 = btoa(unescape(encodeURIComponent(body)));
    return new Response(b64, { headers: Object.assign({ 'content-type': 'text/plain; charset=utf-8' }, CORS) });
  }
  if (path === '/clash' || path === '/mihomo' || path === '/clash.yaml') {
    return new Response(buildClashYaml(host, env, uuid), {
      headers: Object.assign({ 'content-type': 'text/yaml; charset=utf-8' }, CORS),
    });
  }
  if (path === '/health') {
    const { ips } = panelHosts(host, env);
    return new Response(
      JSON.stringify({
        ok: true,
        panel: 'cat-panel',
        version: CAT_PANEL_VERSION,
        sni: sni,
        uuid: !!String(env.UUID || '').trim() ? 'explicit' : 'derived',
        remote: !!env.REMOTE,
        cleanIps: ips.length,
      }),
      { headers: Object.assign({ 'content-type': 'application/json' }, CORS) },
    );
  }
  if (path === '/' || path === '/index.html') {
    const panelPass = String(env.PANEL_PASSWORD || '');
    if (panelPass && url.searchParams.get('p') !== panelPass) {
      return new Response(loginHtml(), { headers: { 'content-type': 'text/html; charset=utf-8' } });
    }
    const { ips } = panelHosts(host, env);
    return new Response(
      panelHtml({
        host, sni, uuid, env,
        links: buildSubLinks(host, env, uuid),
        ips,
        port: Number(env.PORT || 443),
        vlessPath, trojanPath,
        panelPass: !!panelPass,
      }),
      { headers: { 'content-type': 'text/html; charset=utf-8' } },
    );
  }
  return new Response('Not Found', { status: 404, headers: CORS });
}

export default {
  async fetch(request, env) {
    try {
      return await fetchHandler(request, env || {});
    } catch (e) {
      return new Response('Cat Panel error: ' + (e && e.message ? e.message : e), {
        status: 500,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }
  },
};

/* Test hooks (ignored by Cloudflare) */
export const _testing = {
  parseVless,
  parseHttpRequest,
  buildSubLinks,
  buildClashYaml,
  httpForward,
  sendHttpError,
  sniAllowed,
  effectiveSni,
  allowedSnis,
  resolveUuid,
  deriveUuid,
};
