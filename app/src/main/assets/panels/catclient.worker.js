/**
 * 🐱 Cat Panel — single-file Cloudflare Worker panel (VLESS / Trojan / WARP / DoH)
 *
 * Version: 5.6.0 — "purple night" edition. Real data plane (VLESS/Trojan raw TCP
 * relay via cloudflare:sockets + proxy-IP WS fallback), KV-backed users with quota,
 * expiry and device limits, panel password sessions, Iranian resolver presets,
 * clean-IP library, server-side scan API, DoH/DoT, themes, QR, backup/restore.
 *
 * WHAT YOU GET
 *  - Proxy data plane: VLESS-over-WebSocket, Trojan-over-WebSocket, WARP link.
 *  - One subscription link for every client:
 *      /sub        plain share links (VLESS + Trojan + clean-IP variants + WARP)
 *      /sub64      the same, base64-encoded (v2rayNG-style clients)
 *      /clash      Mihomo / Clash Meta YAML (with rules + DNS)
 *      /singbox    sing-box / Hiddify JSON
 *      /all        everything in one JSON document
 *  - Panel (this page): Persian/English, dark "purple night" or light theme,
 *    offline QR codes, in-browser Cloudflare clean-IP scanner, encrypted-DNS
 *    (DoH) resolver with live upstream latency, per-IP config builder and a
 *    one-tap "Open in Cat Client" deep link.
 *  - DoH server: /dns-query (GET ?dns= base64url and POST application/dns-message).
 *
 * HOW TO USE
 *  1. Cloudflare Dashboard → Workers & Pages → Create Worker → edit code →
 *     paste THIS whole file → Deploy.
 *  2. Open https://<your-worker>.<your-subdomain>.workers.dev — that is the panel.
 *  3. Copy the subscription link into Cat Client (or any VLESS/Trojan client).
 *
 * ENVIRONMENT VARIABLES (Workers → Settings → Variables & Secrets, all optional)
 *  UUID           Stable UUID used in links (auto-derived from the host when empty)
 *  SNI            Default SNI written into generated links (default: worker host)
 *  SNI_LIST       Comma-separated extra SNIs the worker accepts (extra fronting hosts)
 *  CF_IPS         Comma-separated clean Cloudflare IPs published as ready-made variants
 *  PORT           Port used in generated links (default 443)
 *  VLESS_PATH     VLESS WebSocket path (default /ws?ed=2048)
 *  TROJAN_PATH    Trojan WebSocket path (default /trojan)
 *  TROJAN_PASS    Trojan password (default: the UUID)
 *  REMOTE         Optional wss:// relay for full-TCP tunnel mode
 *  PANEL_PASSWORD When set, the panel asks for it (?p=<password>) — the data
 *                 plane and subscriptions keep working for already-connected clients
 *  ENABLE_WARP    Set "false" to omit the warp:// link
 *  USER_TOTAL     subscription-userinfo total bytes (default 1 TiB)
 *  DNS_UPSTREAM   Upstream DoH resolver used by /dns-query
 *                 (default https://cloudflare-dns.com/dns-query)
 *  PANEL_TITLE    Header title shown in the panel (default "Cat Panel")
 *
 * SNI + CLEAN IP
 *  Clients may connect to ANY Cloudflare edge IP while keeping the panel
 *  hostname as the TLS SNI/Host. The worker validates X-Forwarded-Sni against
 *  its own hostname and SNI_LIST and rejects unknown SNIs (403), which is what
 *  makes clean-IP fronting safe.
 */

const CAT_PANEL_VERSION = '5.14.0';
/* Cloudflare "API token template" URL — opens the dashboard with the exact
 * permissions the app / wizard need pre-selected (Workers Scripts + KV edit,
 * Account Settings read). Same link the Cat Wizard uses. */
const CF_TOKEN_TEMPLATE_URL = 'https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=' +
  encodeURIComponent(JSON.stringify([
    { key: 'workers_scripts', type: 'edit' },
    { key: 'workers_kv_storage', type: 'edit' },
    { key: 'account_settings', type: 'read' },
    { key: 'user_details', type: 'read' },
  ])) + '&accountId=*&zoneId=all&name=Cat%20Panel';
const CAT_REPO = 'https://github.com/mazodimobinhost-creator/cat-client';
const CAT_CODE_URLS = [
  'https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/main/app/src/main/assets/panels/catclient.worker.js',
  'https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/master/app/src/main/assets/panels/catclient.worker.js',
];

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

function splitCsv(value) {
  return String(value || '')
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function formatAddr(ipOrHost) {
  const v = String(ipOrHost).trim().replace(/^\[/, '').replace(/\]$/, '');
  return v.includes(':') ? '[' + v + ']' : v;
}

function b64encode(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function b64urlEncode(str) {
  return b64encode(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function yamlQuote(s) {
  return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function jsonResponse(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value, null, 2), {
    status,
    headers: Object.assign(
      { 'content-type': 'application/json; charset=utf-8' },
      extraHeaders,
    ),
  });
}

/**
 * Camouflage for unknown paths: a stock "nginx"-looking 404 with no CORS / JSON / branding, so
 * a probe that hits the worker on a random path sees a boring static host, not a proxy panel
 * (same idea as a boring static host fake page). Real routes never reach this.
 */
function notFoundHtml() {
  return '<!DOCTYPE html>\n<html>\n<head><title>404 Not Found</title></head>\n<body>\n' +
    '<center><h1>404 Not Found</h1></center>\n<hr><center>nginx</center>\n</body>\n</html>\n';
}

function notFoundResponse() {
  return new Response(notFoundHtml(), {
    status: 404,
    headers: { 'content-type': 'text/html', 'cache-control': 'no-store' },
  });
}

function htmlResponse(body, status = 200) {
  // Panel pages carry live state (and change with every in-app panel update), so
  // never let a browser cache them — a stale shell outlives an update otherwise.
  return new Response(body, {
    status,
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
  });
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
    const hex = Array.from(b)
      .map((x) => x.toString(16).padStart(2, '0'))
      .join('');
    return (
      hex.slice(0, 8) +
      '-' +
      hex.slice(8, 12) +
      '-' +
      hex.slice(12, 16) +
      '-' +
      hex.slice(16, 20) +
      '-' +
      hex.slice(20, 32)
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
 * the TLS SNI travels in X-Forwarded-Sni; it must match the worker host or one
 * of the configured SNIs.
 */
function sniAllowed(request, host, env) {
  const sni = (request.headers.get('X-Forwarded-Sni') || '').trim().toLowerCase();
  if (!sni) return true; // connected directly by hostname
  return allowedSnis(host, env).has(sni);
}

/* ------------------------------------------------------------------ */
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
  return { host: host, port: port, rest: bytes.slice(offset + 2) };
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
  return { method: method, target: target, headers: headers, body: body };
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
  for (const pair of req.headers) {
    const k = pair[0];
    const v = pair[1];
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
  for (const pair of resp.headers) {
    if (SKIP_RESPONSE_HEADERS.has(pair[0].toLowerCase())) continue;
    head += pair[0] + ': ' + pair[1] + '\r\n';
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
        const chunk = await reader.read();
        if (chunk.done) break;
        if (chunk.value && chunk.value.byteLength > 0) ws.send(chunk.value);
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

function handleDataWebSocket(ws, env, options = {}) {
  return handleTunnelConnection(ws, env, options);
}

/* ------------------------------------------------------------------ */
/* storage layer — Cloudflare KV is optional; everything degrades to    */
/* env vars + in-memory when no KV binding is present.                  */
/* ------------------------------------------------------------------ */

const KV_KEYS = {
  settings: 'catpanel:settings',
  users: 'catpanel:users',
  traffic: 'catpanel:traffic',
};

function kvBinding(env) {
  return env && (env.CAT_KV || env.CATCLIENT_KV || env.PANEL_KV || env.KV || env.BK_KV) || null;
}

function hasKv(env) {
  return kvBinding(env) !== null;
}

async function kvGet(env, key) {
  const store = kvBinding(env);
  if (!store) return null;
  try {
    const value = await store.get(key);
    return value === undefined ? null : value;
  } catch (e) {
    return null;
  }
}

async function kvPut(env, key, value, options) {
  const store = kvBinding(env);
  if (!store) return false;
  try {
    if (options) await store.put(key, value, options);
    else await store.put(key, value);
    return true;
  } catch (e) {
    return false;
  }
}

async function kvDelete(env, key) {
  const store = kvBinding(env);
  if (!store || typeof store.delete !== 'function') return false;
  try {
    await store.delete(key);
    return true;
  } catch (e) {
    return false;
  }
}

const DEFAULT_SETTINGS = {
  title: 'Cat Panel',
  panelPassword: '',
  panelUser: '',
  theme: 'violet',
  dns: {
    upstream: 'https://178.22.122.100/dns-query',
    blockAds: false,
    blockNsfw: false,
  },
  tunnel: {
    proxyIps: [],
    preferConnect: true,
    fragment: '1-3',
  },
  configs: {
    addresses: [],      // clean IPs / domains baked into every subscription
    ports: [],          // [] = DEFAULT_PORTS (80,443,2053,8443,8080 — Cat order). TLS: 443 2053 2083 2087 2096 8443 · plain: 80 8080 8880 2052 2082 2086 2095
    sni: '',            // '' = worker host
    protocols: ['vless', 'trojan'],
    includeHost: true,
    includeIpv6: true,  // add Cloudflare IPv6 anycast entries (the panel does)
    fingerprint: 'chrome',
    // Successful worker probes are the only safe source for anycast country labels.
    // `verifiedScanned` distinguishes an empty scan from an unconfigured panel.
    verified: [],
    verifiedScanned: false,
    verifiedAt: 0,
  },
  scan: {
    ranges: [],
    concurrency: 24,
    timeoutMs: 4000,
  },
  masterUuid: '',
  updatedAt: 0,
};

function deepMerge(base, patch) {
  if (!patch || typeof patch !== 'object') return base;
  const out = Array.isArray(base) ? base.slice() : Object.assign({}, base);
  Object.keys(patch).forEach((key) => {
    const value = patch[key];
    if (value && typeof value === 'object' && !Array.isArray(value) && base && typeof base[key] === 'object' && !Array.isArray(base[key])) {
      out[key] = deepMerge(base[key], value);
    } else if (value !== undefined) {
      out[key] = value;
    }
  });
  return out;
}

/** Settings = built-in defaults + env overrides + KV overrides (KP last). */
async function readSettings(env) {
  const stored = await kvGet(env, KV_KEYS.settings);
  let parsed = null;
  try {
    parsed = stored ? JSON.parse(stored) : null;
  } catch (e) {
    parsed = null;
  }
  const merged = deepMerge(DEFAULT_SETTINGS, parsed || {});
  if (env.PANEL_TITLE) merged.title = String(env.PANEL_TITLE);
  if (env.PANEL_PASSWORD) merged.panelPassword = String(env.PANEL_PASSWORD);
  if (env.PANEL_USER) merged.panelUser = String(env.PANEL_USER);
  if (env.DNS_UPSTREAM) merged.dns.upstream = String(env.DNS_UPSTREAM);
  if (env.PROXY_IPS || env.PROXYIP) merged.tunnel.proxyIps = splitCsv(env.PROXY_IPS || env.PROXYIP);
  if (env.UUID) merged.masterUuid = String(env.UUID);
  return merged;
}

async function writeSettings(env, patch) {
  const current = await readSettings(env);
  const next = deepMerge(current, patch || {});
  next.updatedAt = Date.now();
  const ok = await kvPut(env, KV_KEYS.settings, JSON.stringify(next));
  return { settings: next, persisted: ok };
}

/* ------------------------------------------------------------------ */
/* users — KV-backed accounts with quota, expiry and device limits      */
/* ------------------------------------------------------------------ */

const USER_DEFAULTS = {
  countries: [],
  quotaGb: 0,
  usedBytes: 0,
  usedRequests: 0,
  expireAt: 0,
  deviceLimit: 0,
  enabled: true,
  note: '',
  createdAt: 0,
  lastSeenAt: 0,
};

function normalizeUser(raw) {
  const user = Object.assign({}, USER_DEFAULTS, raw || {});
  user.countries = Array.isArray(user.countries)
    ? user.countries.map((c) => String(c || '').trim().toUpperCase()).filter((c) => /^[A-Z]{2}$/.test(c))
    : (user.countries ? String(user.countries).split(/[\s,;]+/).map((c) => c.trim().toUpperCase()).filter((c) => /^[A-Z]{2}$/.test(c)) : []);
  user.quotaGb = Number(user.quotaGb) || 0;
  user.usedBytes = Number(user.usedBytes) || 0;
  user.usedRequests = Number(user.usedRequests) || 0;
  user.expireAt = Number(user.expireAt) || 0;
  user.deviceLimit = Number(user.deviceLimit) || 0;
  user.enabled = user.enabled !== false;
  return user;
}

async function readUsers(env) {
  const raw = await kvGet(env, KV_KEYS.users);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(normalizeUser) : [];
  } catch (e) {
    return [];
  }
}

async function writeUsers(env, users) {
  return kvPut(env, KV_KEYS.users, JSON.stringify(users.map(normalizeUser)));
}

function userQuotaBytes(user) {
  return Math.max(0, (Number(user.quotaGb) || 0) * 1024 * 1024 * 1024);
}

/** 0 = unlimited. */
function userTrafficLeft(user) {
  const quota = userQuotaBytes(user);
  if (quota <= 0) return Infinity;
  return Math.max(0, quota - userLiveUsed(user));
}

/** Written usage + bytes still sitting in this isolate's buffer. */
function userLiveUsed(user) {
  return (Number(user.usedBytes) || 0) + bufferedBytes(user && user.uuid);
}

function userExpired(user, now) {
  const at = Number(user.expireAt) || 0;
  return at > 0 && at <= (now || Date.now());
}

function userReasonBlocked(user, now) {
  if (!user) return 'unknown-user';
  if (user.enabled === false) return 'disabled';
  if (userExpired(user, now)) return 'expired';
  if (userTrafficLeft(user) <= 0) return 'quota-exceeded';
  return null;
}

function findUserByUuid(users, uuid) {
  const needle = String(uuid || '').toLowerCase();
  return users.find((user) => String(user.uuid || '').toLowerCase() === needle) || null;
}

function findUserByToken(users, token) {
  const needle = String(token || '');
  return users.find((user) => String(user.token || '') === needle) || null;
}

/** Allow-list of UUIDs that may open a tunnel: env UUID + KV users + master. */
async function tunnelAuth(env, uuid, settings) {
  const master = String(env.UUID || (settings && settings.masterUuid) || '').toLowerCase();
  if (master && uuid.toLowerCase() === master) {
    return { ok: true, user: null, role: 'master' };
  }
  const users = await readUsers(env);
  const user = findUserByUuid(users, uuid);
  if (!user) return { ok: false, error: 'unknown-uuid', users: users };
  const blocked = userReasonBlocked(user);
  if (blocked) return { ok: false, error: blocked, user: user, users: users };
  return { ok: true, user: user, users: users, role: 'user' };
}

/**
 * Debounced traffic accounting so a busy tunnel does not hammer KV.
 *
 * Runtime lessons for reliable usage accounting:
 *  - a connection that closes must flush *unconditionally* (`force`), otherwise
 *    an isolate that is evicted before the next timed flush silently loses the
 *    whole session ("usage never goes up");
 *  - flushes are serialised through one promise chain so two connections
 *    closing at once cannot race a read-modify-write on the users list;
 *  - a failed KV write (daily limit) puts the bytes back into the buffer.
 *  - buffered-but-unwritten bytes count toward the quota (`bufferedBytes`).
 */
const trafficBuffers = globalThis.__catTraffic || (globalThis.__catTraffic = new Map());
const trafficState = globalThis.__catTrafficState || (globalThis.__catTrafficState = { busySince: 0, lastFlush: Date.now() });
const TRAFFIC_FLUSH_INTERVAL_MS = 15000;
const TRAFFIC_FLUSH_THRESHOLD = 5 * 1024 * 1024;
/** A flush older than this is assumed dead (its request context was torn down). */
const TRAFFIC_FLUSH_STALE_MS = 5000;

function bufferedBytes(uuid) {
  const entry = trafficBuffers.get(String(uuid || '').toLowerCase());
  return entry ? entry.sent + entry.received : 0;
}

function bufferedTotal() {
  let total = 0;
  trafficBuffers.forEach((entry) => { total += entry.sent + entry.received; });
  return total;
}

function accountTraffic(env, uuid, sentBytes, receivedBytes, force) {
  if (!uuid) return Promise.resolve();
  const key = uuid.toLowerCase();
  const entry = trafficBuffers.get(key) || { sent: 0, received: 0 };
  entry.sent += sentBytes || 0;
  entry.received += receivedBytes || 0;
  trafficBuffers.set(key, entry);
  const dueByTime = Date.now() - trafficState.lastFlush >= TRAFFIC_FLUSH_INTERVAL_MS;
  const dueBySize = bufferedTotal() >= TRAFFIC_FLUSH_THRESHOLD;
  if (!force && !dueByTime && !dueBySize) return Promise.resolve();
  return flushTraffic(env);
}

/**
 * Write buffered bytes to KV. Never shares promises between requests: in
 * Workers a promise/timer created inside one request's I/O context may never
 * settle once that request is cancelled, so a shared chain would wedge every
 * later caller. Instead a busy flag skips overlapping flushes — the bytes
 * simply stay buffered (and still count via `userLiveUsed`) until the next one.
 */
async function flushTraffic(env) {
  const now = Date.now();
  if (trafficState.busySince && now - trafficState.busySince < TRAFFIC_FLUSH_STALE_MS) return false;
  if (!hasKv(env)) { trafficState.lastFlush = now; return false; }
  const snapshot = new Map();
  trafficBuffers.forEach((entry, key) => {
    const delta = entry.sent + entry.received;
    if (delta > 0) snapshot.set(key, delta);
  });
  if (!snapshot.size) { trafficState.lastFlush = now; return false; }
  trafficState.busySince = now;
  // Take the bytes out of the buffer only now, so a skipped flush loses nothing.
  snapshot.forEach((delta, key) => {
    const entry = trafficBuffers.get(key);
    if (entry) { entry.sent = 0; entry.received = 0; }
  });
  try {
    const users = await readUsers(env);
    let changed = false;
    snapshot.forEach((delta, key) => {
      const user = findUserByUuid(users, key);
      if (!user) return;
      user.usedBytes = (Number(user.usedBytes) || 0) + delta;
      user.lastSeenAt = Date.now();
      changed = true;
    });
    if (changed) await writeUsers(env, users);
    trafficState.lastFlush = Date.now();
    return true;
  } catch (e) {
    // KV write failed (daily limit?) — put the bytes back so nothing is lost.
    snapshot.forEach((delta, key) => {
      const entry = trafficBuffers.get(key) || { sent: 0, received: 0 };
      entry.received += delta;
      trafficBuffers.set(key, entry);
    });
    return false;
  } finally {
    trafficState.busySince = 0;
  }
}

/* ------------------------------------------------------------------ */
/* connection limits — best effort per-user device cap                  */
/* ------------------------------------------------------------------ */

const liveConnections = new Map();

function acquireConnection(uuid, limit) {
  const key = uuid.toLowerCase();
  const count = (liveConnections.get(key) || 0) + 1;
  liveConnections.set(key, count);
  if (limit > 0 && count > limit) {
    liveConnections.set(key, count - 1);
    return false;
  }
  return true;
}

function releaseConnection(uuid) {
  const key = String(uuid || '').toLowerCase();
  const count = (liveConnections.get(key) || 1) - 1;
  if (count <= 0) liveConnections.delete(key);
  else liveConnections.set(key, count);
}

/* ------------------------------------------------------------------ */
/* VLESS / Trojan data plane — direct edge-to-origin relay                  */
/*                                                                      */
/*  client (Xray/Mihomo/sing-box) --WSS--> Cloudflare edge --> worker    */
/*     --cloudflare:sockets TCP--> destination                          */
/*     --(destination is a Cloudflare IP / refused?)--> PROXY_IP relay  */
/*  UDP is only allowed for DNS (port 53) and is answered through DoH.  */
/* ------------------------------------------------------------------ */

const WS_OPEN = 1;

/** Relay hosts used when the destination itself sits behind Cloudflare. */
const DEFAULT_PROXY_IPS = [
  'proxyip.cmliussss.net',
  'di.nscl.ir',
  'tr.diam4.ggff.net',
];

async function refreshProxyIps(env, source) {
  const answer = await fetch(source, { headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION } });
  const text = await answer.text();
  let ips = [];
  try {
    const data = JSON.parse(text);
    const body = Array.isArray(data) ? data : (Array.isArray(data.body) ? data.body : (Array.isArray(data.ips) ? data.ips : []));
    ips = body.map((item) => (item && item.ip) ? item.ip : String(item || '')).filter(Boolean);
  } catch (e) {
    ips = text.split(/\s+/);
  }
  ips = Array.from(new Set(ips.map((ip) => String(ip).trim()).filter(Boolean))).slice(0, 64);
  if (!ips.length) return { ok: false, error: 'empty source' };
  const settings = await readSettings(env);
  settings.tunnel = Object.assign({}, settings.tunnel, { proxyIps: ips });
  const saved = await writeSettings(env, { tunnel: { proxyIps: ips } });
  return { ok: true, count: ips.length, ips: ips, persisted: !!saved.persisted };
}

function proxyIpList(env, settings) {
  const fromSettings = settings && settings.tunnel && Array.isArray(settings.tunnel.proxyIps)
    ? settings.tunnel.proxyIps
    : [];
  const fromEnv = splitCsv(env.PROXY_IPS || env.PROXYIP || env.PROXY_IP);
  const list = fromSettings.length ? fromSettings : fromEnv;
  return (list.length ? list : DEFAULT_PROXY_IPS).map((entry) => String(entry).trim()).filter(Boolean);
}

/** "host:port" / "[v6]:port" / "host" → { hostname, port }. */
function splitHostPort(value, fallbackPort) {
  const raw = String(value || '').trim();
  const bracket = raw.match(/^\[([^\]]+)\](?::(\d+))?$/);
  if (bracket) return { hostname: bracket[1], port: Number(bracket[2] || fallbackPort) };
  const parts = raw.split(':');
  if (parts.length === 2 && /^\d+$/.test(parts[1])) return { hostname: parts[0], port: Number(parts[1]) };
  return { hostname: raw, port: fallbackPort };
}

let socketsModulePromise = null;

/** Test hook: inject a fake `cloudflare:sockets` implementation. */
function __setSockets(mod) {
  socketsModulePromise = Promise.resolve(mod || null);
}

/** `cloudflare:sockets` only exists inside Workers; tests and Node get null. */
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

async function trojanAuthorized(env, settings, hash, masterUuid) {
  const candidates = [];
  if (env.TROJAN_PASS) candidates.push(String(env.TROJAN_PASS));
  if (masterUuid) candidates.push(String(masterUuid));
  const users = await readUsers(env);
  users.forEach((user) => {
    if (user.uuid) candidates.push(String(user.uuid)); // the gate below reports disabled/expired/quota
  });
  for (const password of candidates) {
    const digest = await trojanHash(password);
    if (digest === hash) {
      const user = findUserByUuid(users, password);
      // Same gate as VLESS: expiry / quota / disabled apply to Trojan too.
      const blocked = user ? userReasonBlocked(user) : null;
      if (blocked) return { ok: false, error: blocked, user: user, users: users };
      return { ok: true, password: password, user: user, users: users };
    }
  }
  return { ok: false, users: users };
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

let cfV6HeadsCache = null;
function cfV6Heads() {
  if (!cfV6HeadsCache) {
    cfV6HeadsCache = SCAN_RANGES6.map((cidr) => {
      const h = parseV6Hextets(cidr.split('/')[0]);
      return h ? parseInt(h[0], 16) * 0x10000 + parseInt(h[1], 16) : -1;
    });
  }
  return cfV6HeadsCache;
}

function isCloudflareIp(ip) {
  const value = ipToLong(ip);
  if (value !== null) return CF_CIDR_RANGES.some((range) => ipInCidr(value, range));
  const h = parseV6Hextets(ip);
  if (!h) return false;
  const head32 = parseInt(h[0], 16) * 0x10000 + parseInt(h[1], 16);
  return cfV6Heads().indexOf(head32) >= 0;
}

/**
 * Answer a DNS query carried inside the tunnel via DoH so UDP/53 works even
 * though Workers have no UDP sockets (same trick the panel uses).
 */
async function resolveDnsOverDoh(query, env) {
  const upstream = dohUpstream(env);
  const answer = await fetch(upstream, {
    method: 'POST',
    headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' },
    body: query,
  });
  return new Uint8Array(await answer.arrayBuffer());
}

/**
 * Open the outbound TCP socket. Destinations behind Cloudflare cannot be
 * dialled from a Worker, so those (and refused dials) go through a proxy IP.
 */
async function dialTarget(host, port, env, settings, log) {
  const sockets = await loadSockets();
  if (!sockets) throw new Error('cloudflare:sockets unavailable');
  const attempts = [];
  const targetIsCf = isCloudflareIp(host);
  if (!targetIsCf) attempts.push({ hostname: host, port: port, via: 'direct' });
  proxyIpList(env, settings).forEach((proxy) => {
    const parsed = splitHostPort(proxy, port);
    attempts.push({ hostname: parsed.hostname, port: parsed.port || port, via: 'proxy:' + proxy });
  });
  let lastError = null;
  for (const attempt of attempts) {
    try {
      const socket = sockets.connect({ hostname: attempt.hostname, port: attempt.port }, { allowHalfOpen: false });
      if (socket.opened) await socket.opened;
      if (log) log('dial ok ' + attempt.via + ' → ' + attempt.hostname + ':' + attempt.port);
      return { socket: socket, via: attempt.via };
    } catch (e) {
      lastError = e;
      if (log) log('dial failed ' + attempt.via + ': ' + (e && e.message ? e.message : e));
    }
  }
  throw lastError || new Error('no route to ' + host + ':' + port);
}

/**
 * Pipe: client WS → TCP socket, TCP socket → client WS (with an optional
 * protocol response header prefixed to the first downstream chunk).
 */
async function pumpTunnel(ws, clientReadable, socket, responseHeader, counters) {
  const writer = socket.writable.getWriter();
  const reader = clientReadable.getReader();
  const upstream = (async () => {
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (chunk.value && chunk.value.byteLength) {
          await writer.write(chunk.value);
          counters.sent += chunk.value.byteLength;
        }
      }
    } catch (e) { /* client went away */ }
    try { await writer.close(); } catch (e) { /* ignore */ }
    // Client is gone: tear the TCP leg down too instead of waiting for the
    // remote to notice the half-close (keeps the worker from idling on dead sockets).
    if (ws.readyState !== WS_OPEN) {
      try { socket.close(); } catch (e) { /* ignore */ }
    }
  })();
  const downstream = (async () => {
    const reader = socket.readable.getReader();
    let header = responseHeader;
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (!chunk.value || !chunk.value.byteLength) continue;
        if (ws.readyState !== WS_OPEN) break;
        const payload = header ? concatBytes(header, chunk.value) : chunk.value;
        header = null;
        ws.send(payload);
        counters.received += chunk.value.byteLength;
      }
    } catch (e) { /* remote closed */ }
    if (header && ws.readyState === WS_OPEN) {
      // Remote closed without data: still deliver the protocol response.
      try { ws.send(header); } catch (e) { /* ignore */ }
    }
    safeCloseWs(ws, 1000, 'remote closed');
    // A peer that vanished without a Close frame never fires `close` on our
    // side, so the upstream read would wait forever and workerd would report
    // the request as hung. Cancelling the reader resolves that pending read.
    try { await reader.cancel(); } catch (e) { /* ignore */ }
  })();
  await Promise.all([upstream, downstream]);
  try { socket.close(); } catch (e) { /* ignore */ }
}

/** Legacy shim kept for the tests: relay through a proxy WebSocket. */
async function relayTcp(clientWs, options) {
  const proxyIps = options.proxyIps || [];
  const sent = { bytes: 0 };
  const received = { bytes: 0 };
  for (const proxyIp of proxyIps) {
    try {
      const upstream = await fetch('https://' + proxyIp + (options.path || '/'), {
        headers: { Upgrade: 'websocket', Connection: 'Upgrade' },
      });
      const remote = upstream.webSocket;
      if (!remote) continue;
      remote.accept();
      if (options.headerBytes && options.headerBytes.byteLength) remote.send(options.headerBytes);
      remote.addEventListener('message', (event) => {
        try {
          clientWs.send(event.data);
          received.bytes += event.data && event.data.byteLength ? event.data.byteLength : 0;
        } catch (e) { /* ignore */ }
      });
      clientWs.addEventListener('message', (event) => {
        try {
          remote.send(event.data);
          sent.bytes += event.data && event.data.byteLength ? event.data.byteLength : 0;
        } catch (e) { /* ignore */ }
      });
      const closeBoth = () => {
        try { remote.close(); } catch (e) { /* ignore */ }
        try { clientWs.close(); } catch (e) { /* ignore */ }
        if (options.onClose) options.onClose(sent.bytes, received.bytes);
      };
      remote.addEventListener('close', closeBoth);
      clientWs.addEventListener('close', closeBoth);
      return true;
    } catch (e) {
      // try the next proxy IP
    }
  }
  return false;
}

/**
 * Cat Panel data plane. `request` is the upgrade request (early data lives
 * in Sec-WebSocket-Protocol); `masterUuid` is the panel UUID from env/host.
 */
async function handleTunnelConnection(ws, env, options = {}) {
  const earlyData = decodeEarlyData(options.earlyDataHeader);
  // Attach the message listener *before* the first await or early frames are lost.
  const clientStream = websocketReadable(ws, earlyData);
  const reader = clientStream.getReader();
  const settings = await readSettings(env);
  const masterUuid = String(options.masterUuid || env.UUID || settings.masterUuid || '').toLowerCase();
  const counters = { sent: 0, received: 0 };
  const log = options.log || (() => {});

  let first;
  try {
    first = await reader.read();
  } catch (e) {
    safeCloseWs(ws, 1011, 'read failed');
    return;
  }
  if (first.done || !first.value || !first.value.byteLength) {
    safeCloseWs(ws, 1002, 'empty handshake');
    return;
  }
  const bytes = first.value;
  let target = null;
  let firstPayload = null;
  let responseHeader = null;
  let accountUuid = null;
  let user = null;
  let isDns = false;

  const vless = parseVlessHeader(bytes);
  if (vless) {
    const master = masterUuid && vless.uuid.toLowerCase() === masterUuid;
    if (!master) {
      const auth = await tunnelAuth(env, vless.uuid, settings);
      if (!auth.ok) {
        log('vless rejected ' + vless.uuid + ' (' + auth.error + ')');
        safeCloseWs(ws, 1008, 'unauthorized');
        return;
      }
      user = auth.user;
    }
    accountUuid = vless.uuid;
    if (vless.command === 2) {
      if (vless.port !== 53) {
        safeCloseWs(ws, 1003, 'udp only for dns');
        return;
      }
      isDns = true;
    } else if (vless.command !== 1) {
      safeCloseWs(ws, 1003, 'unsupported command');
      return;
    }
    target = { host: vless.host, port: vless.port };
    firstPayload = vless.rest;
    responseHeader = new Uint8Array([vless.version, 0]);
  } else {
    const trojan = trojanPassword(bytes);
    if (!trojan) {
      safeCloseWs(ws, 1002, 'unrecognised handshake');
      return;
    }
    const auth = await trojanAuthorized(env, settings, trojan.password, masterUuid);
    if (!auth.ok) {
      log('trojan rejected');
      safeCloseWs(ws, 1008, 'unauthorized');
      return;
    }
    const request = parseTrojanRequest(trojan.rest);
    if (!request) {
      safeCloseWs(ws, 1002, 'malformed trojan request');
      return;
    }
    if (request.command === 3) {
      safeCloseWs(ws, 1003, 'udp associate unsupported');
      return;
    }
    user = auth.user;
    accountUuid = auth.password;
    target = { host: request.host, port: request.port };
    firstPayload = request.payload;
    responseHeader = null;
  }

  if (user && !acquireConnection(accountUuid, user.deviceLimit)) {
    safeCloseWs(ws, 1008, 'device limit reached');
    return;
  }
  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    if (user) releaseConnection(accountUuid);
    if (!accountUuid) return;
    // Forced flush: the session is over, write it now (reliability note —
    // waiting for the next timed flush loses the bytes when the isolate dies).
    const pending = accountTraffic(env, accountUuid, counters.sent, counters.received, true);
    if (options.ctx && typeof options.ctx.waitUntil === 'function') options.ctx.waitUntil(pending.catch(() => {}));
  };

  if (isDns) {
    // VLESS UDP frames: [len 2][dns payload] — answer each through DoH.
    // Count both directions here as well as in the TCP pump. The previous
    // implementation only counted DNS answers, so upload was always reported
    // as zero for UDP/DNS traffic.
    let buffer = firstPayload || new Uint8Array(0);
    if (firstPayload && firstPayload.byteLength) counters.sent += firstPayload.byteLength;
    const pumpDns = async (chunk) => {
      if (chunk && chunk.byteLength) counters.sent += chunk.byteLength;
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
          counters.received += answer.byteLength;
        } catch (e) {
          log('dns failed: ' + (e && e.message ? e.message : e));
        }
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
    finish();
    safeCloseWs(ws, 1000, 'dns done');
    return;
  }

  let dialed;
  try {
    dialed = await dialTarget(target.host, target.port, env, settings, log);
  } catch (e) {
    log('no route to ' + target.host + ':' + target.port + ' — ' + (e && e.message ? e.message : e));
    finish();
    safeCloseWs(ws, 1011, 'dial failed');
    return;
  }

  // Upstream = payload that arrived with the header, then every later frame.
  const upstreamReadable = new ReadableStream({
    start(controller) {
      if (firstPayload && firstPayload.byteLength) controller.enqueue(firstPayload);
    },
    async pull(controller) {
      try {
        const chunk = await reader.read();
        if (chunk.done) controller.close();
        else controller.enqueue(chunk.value);
      } catch (e) {
        controller.error(e);
      }
    },
    cancel() { try { reader.cancel(); } catch (e) { /* ignore */ } },
  });

  try {
    await pumpTunnel(ws, upstreamReadable, dialed.socket, responseHeader, counters);
  } catch (e) {
    log('tunnel error: ' + (e && e.message ? e.message : e));
  } finally {
    finish();
    safeCloseWs(ws, 1000, 'done');
  }
}

/* ------------------------------------------------------------------ */
/* subscription content                                                */
/* ------------------------------------------------------------------ */

function panelPaths(env) {
  return {
    vlessPath: String(env.VLESS_PATH || '/ws?ed=2048'),
    trojanPath: String(env.TROJAN_PATH || '/trojan'),
    port: Number(env.PORT || 443),
  };
}

/**
 * Well-known Cloudflare-fronted addresses that usually work from Iran.
 * Every hostname here MUST resolve to Cloudflare anycast (verified 2026-09):
 * a non-Cloudflare address can never reach the worker, so it silently
 * produces dead configs (zula.ir / iranserver.com were such cases).
 */
const DEFAULT_CLEAN_ADDRESSES = [
  'www.speedtest.net', 'www.visa.com', 'cf.090227.xyz', 'ip.sb', 'cdnjs.cloudflare.com', 'speed.cloudflare.com',
  'www.shopify.com', 'discord.com', 'icook.tw', 'www.wto.org',
  '104.16.132.229', '172.67.181.32', '188.114.96.1', '162.159.192.1', '104.17.148.22', '172.64.80.1',
];
const TLS_PORTS = [443, 2053, 2083, 2087, 2096, 8443];
const PLAIN_PORTS = [80, 8080, 8880, 2052, 2082, 2086, 2095];
/**
 * Default port set for reliable fronting:
 * plain-HTTP 80 first — no TLS handshake means no SNI for DPI to match, which is
 * why "VLESS - IPv4 : 80" is usually the first config that comes up — then TLS.
 */
const DEFAULT_PORTS = [80, 443, 2053, 8443, 8080];
const MAX_SUB_ADDRESSES = 40;
/** Hard cap on links per subscription — url-test groups with hundreds of nodes make every client sluggish. */
const MAX_SUB_ENTRIES = 200;
const DEFAULT_SUB_ENTRIES = 8;
/**
 * Cloudflare IPv6 anycast for dual-stack phones (the panel emits IPv6 entries too;
 * many Iranian mobile carriers hand out v6 that is less policed than v4).
 */
const DEFAULT_CLEAN_IPV6 = ['2606:4700::6810:84e5', '2606:4700::6812:1a2e', '2606:4700:3030::ac43:b58a', '2606:4700:3032::6815:3ef9', '2400:cb00::6815:3ef9', '2a06:98c0::6815:3ef9'];
/* Cloudflare IPv6 anycast (the pool the CloudflareScanner ipv6.txt walks). */
const SCAN_RANGES6 = [
  '2400:cb00::/32', '2405:b500::/32', '2405:8100::/32', '2606:4700::/32',
  '2803:f800::/32', '2a06:98c0::/32', '2a06:98c1::/32', '2a06:98c2::/32',
  '2a06:98c3::/32', '2a06:98c4::/32', '2a06:98c5::/32', '2a06:98c6::/32',
  '2a06:98c7::/32', '2c0f:f248::/32',
];

function panelHosts(host, env) {
  const ips = splitCsv(env.CF_IPS);
  return { ips: ips, all: [String(host)].concat(ips) };
}

function validAddress(value) {
  const v = String(value || '').trim().replace(/^\[/, '').replace(/\]$/, '');
  if (!v) return false;
  if (isIpLiteral(v)) return true;
  return /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/i.test(v);
}

/**
 * Everything that shapes a subscription, resolved in priority order:
 *   query string (?ips=&ports=&sni=&proto=) → KV settings → env → defaults.
 * The query form keeps the panel fully usable without KV: the UI just builds
 * a sub URL that carries the user's choices.
 */
const EDGE_LOCATIONS = {
  ABQ: { city: 'Albuquerque', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ACC: { city: 'Accra', country: 'Ghana', iso: 'GH', flag: '🇬🇭' },
  ADL: { city: 'Adelaide', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  AGA: { city: 'Agadir', country: 'Morocco', iso: 'MA', flag: '🇲🇦' },
  AGR: { city: 'Agra', country: 'India', iso: 'IN', flag: '🇮🇳' },
  AKL: { city: 'Auckland', country: 'New Zealand', iso: 'NZ', flag: '🇳🇿' },
  ALA: { city: 'Almaty', country: 'Kazakhstan', iso: 'KZ', flag: '🇰🇿' },
  ALG: { city: 'Algiers', country: 'Algeria', iso: 'DZ', flag: '🇩🇿' },
  AMD: { city: 'Ahmedabad', country: 'India', iso: 'IN', flag: '🇮🇳' },
  AMM: { city: 'Amman', country: 'Jordan', iso: 'JO', flag: '🇯🇴' },
  AMS: { city: 'Amsterdam', country: 'Netherlands', iso: 'NL', flag: '🇳🇱' },
  ANC: { city: 'Anchorage', country: 'United States', iso: 'US', flag: '🇺🇸' },
  AOI: { city: 'Ancona', country: 'Italy', iso: 'IT', flag: '🇮🇹' },
  ARN: { city: 'Stockholm', country: 'Sweden', iso: 'SE', flag: '🇸🇪' },
  ASU: { city: 'Asuncion', country: 'Paraguay', iso: 'PY', flag: '🇵🇾' },
  ATH: { city: 'Athens', country: 'Greece', iso: 'GR', flag: '🇬🇷' },
  ATL: { city: 'Atlanta', country: 'United States', iso: 'US', flag: '🇺🇸' },
  AUH: { city: 'Abu Dhabi', country: 'United Arab Emirates', iso: 'AE', flag: '🇦🇪' },
  BAH: { city: 'Manama', country: 'Bahrain', iso: 'BH', flag: '🇧🇭' },
  BAL: { city: 'Baltimore', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BAQ: { city: 'Barranquilla', country: 'Colombia', iso: 'CO', flag: '🇨🇴' },
  BBI: { city: 'Bhubaneswar', country: 'India', iso: 'IN', flag: '🇮🇳' },
  BBU: { city: 'Bucharest', country: 'Romania', iso: 'RO', flag: '🇷🇴' },
  BCN: { city: 'Barcelona', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  BEG: { city: 'Belgrade', country: 'Serbia', iso: 'RS', flag: '🇷🇸' },
  BEL: { city: 'Belem', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  BER: { city: 'Berlin', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  BGF: { city: 'Bangui', country: 'Central African Republic', iso: 'CF', flag: '🇨🇫' },
  BGW: { city: 'Baghdad', country: 'Iraq', iso: 'IQ', flag: '🇮🇶' },
  BHD: { city: 'Belfast', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  BHX: { city: 'Birmingham', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  BIO: { city: 'Bilbao', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  BIQ: { city: 'Biarritz', country: 'France', iso: 'FR', flag: '🇫🇷' },
  BJS: { city: 'Beijing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  BKI: { city: 'Kota Kinabalu', country: 'Malaysia', iso: 'MY', flag: '🇲🇾' },
  BKK: { city: 'Bangkok', country: 'Thailand', iso: 'TH', flag: '🇹🇭' },
  BKO: { city: 'Bamako', country: 'Mali', iso: 'ML', flag: '🇲🇱' },
  BMG: { city: 'Bloomington', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BNA: { city: 'Nashville', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BNU: { city: 'Blumenau', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  BOD: { city: 'Bordeaux', country: 'France', iso: 'FR', flag: '🇫🇷' },
  BOG: { city: 'Bogota', country: 'Colombia', iso: 'CO', flag: '🇨🇴' },
  BOI: { city: 'Boise', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BOM: { city: 'Mumbai', country: 'India', iso: 'IN', flag: '🇮🇳' },
  BOS: { city: 'Boston', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BRU: { city: 'Brussels', country: 'Belgium', iso: 'BE', flag: '🇧🇪' },
  BSB: { city: 'Brasilia', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  BSR: { city: 'Basra', country: 'Iraq', iso: 'IQ', flag: '🇮🇶' },
  BTS: { city: 'Bratislava', country: 'Slovakia', iso: 'SK', flag: '🇸🇰' },
  BUD: { city: 'Budapest', country: 'Hungary', iso: 'HU', flag: '🇭🇺' },
  BUE: { city: 'Buenos Aires', country: 'Argentina', iso: 'AR', flag: '🇦🇷' },
  BUF: { city: 'Buffalo', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BUH: { city: 'Bucharest', country: 'Romania', iso: 'RO', flag: '🇷🇴' },
  BUR: { city: 'Burbank', country: 'United States', iso: 'US', flag: '🇺🇸' },
  BWN: { city: 'Bandar Seri Begawan', country: 'Brunei', iso: 'BN', flag: '🇧🇳' },
  BZE: { city: 'Belize City', country: 'Belize', iso: 'BZ', flag: '🇧🇿' },
  CAI: { city: 'Cairo', country: 'Egypt', iso: 'EG', flag: '🇪🇬' },
  CAN: { city: 'Guangzhou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CBB: { city: 'Cochabamba', country: 'Bolivia', iso: 'BO', flag: '🇧🇴' },
  CBR: { city: 'Canberra', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  CCU: { city: 'Kolkata', country: 'India', iso: 'IN', flag: '🇮🇳' },
  CDG: { city: 'Paris', country: 'France', iso: 'FR', flag: '🇫🇷' },
  CDT: { city: 'Tarragona', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  CEB: { city: 'Cebu', country: 'Philippines', iso: 'PH', flag: '🇵🇭' },
  CGK: { city: 'Jakarta', country: 'Indonesia', iso: 'ID', flag: '🇮🇩' },
  CGO: { city: 'Zhengzhou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CGP: { city: 'Chattogram', country: 'Bangladesh', iso: 'BD', flag: '🇧🇩' },
  CGQ: { city: 'Changchun', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CGR: { city: 'Campo Grande', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  CHC: { city: 'Christchurch', country: 'New Zealand', iso: 'NZ', flag: '🇳🇿' },
  CHS: { city: 'Charleston', country: 'United States', iso: 'US', flag: '🇺🇸' },
  CJB: { city: 'Coimbatore', country: 'India', iso: 'IN', flag: '🇮🇳' },
  CJU: { city: 'Jeju', country: 'South Korea', iso: 'KR', flag: '🇰🇷' },
  CKG: { city: 'Chongqing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CMB: { city: 'Colombo', country: 'Sri Lanka', iso: 'LK', flag: '🇱🇰' },
  CMH: { city: 'Columbus', country: 'United States', iso: 'US', flag: '🇺🇸' },
  CNF: { city: 'Belo Horizonte', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  CNX: { city: 'Chiang Mai', country: 'Thailand', iso: 'TH', flag: '🇹🇭' },
  COD: { city: 'Cody', country: 'United States', iso: 'US', flag: '🇺🇸' },
  COK: { city: 'Kochi', country: 'India', iso: 'IN', flag: '🇮🇳' },
  COR: { city: 'Cordoba', country: 'Argentina', iso: 'AR', flag: '🇦🇷' },
  CPH: { city: 'Copenhagen', country: 'Denmark', iso: 'DK', flag: '🇩🇰' },
  CPT: { city: 'Cape Town', country: 'South Africa', iso: 'ZA', flag: '🇿🇦' },
  CRL: { city: 'Charleroi', country: 'Belgium', iso: 'BE', flag: '🇧🇪' },
  CRP: { city: 'Corpus Christi', country: 'United States', iso: 'US', flag: '🇺🇸' },
  CSX: { city: 'Changsha', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CTU: { city: 'Chengdu', country: 'China', iso: 'CN', flag: '🇨🇳' },
  CUR: { city: 'Willemstad', country: 'Curacao', iso: 'CW', flag: '🇨🇼' },
  CVG: { city: 'Cincinnati', country: 'United States', iso: 'US', flag: '🇺🇸' },
  CWB: { city: 'Curitiba', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  CZX: { city: 'Changzhou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  DAC: { city: 'Dhaka', country: 'Bangladesh', iso: 'BD', flag: '🇧🇩' },
  DAR: { city: 'Dar es Salaam', country: 'Tanzania', iso: 'TZ', flag: '🇹🇿' },
  DAY: { city: 'Dayton', country: 'United States', iso: 'US', flag: '🇺🇸' },
  DEL: { city: 'Delhi', country: 'India', iso: 'IN', flag: '🇮🇳' },
  DEN: { city: 'Denver', country: 'United States', iso: 'US', flag: '🇺🇸' },
  DFW: { city: 'Dallas', country: 'United States', iso: 'US', flag: '🇺🇸' },
  DMM: { city: 'Dammam', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  DOH: { city: 'Doha', country: 'Qatar', iso: 'QA', flag: '🇶🇦' },
  DPS: { city: 'Denpasar', country: 'Indonesia', iso: 'ID', flag: '🇮🇩' },
  DTW: { city: 'Detroit', country: 'United States', iso: 'US', flag: '🇺🇸' },
  DUB: { city: 'Dublin', country: 'Ireland', iso: 'IE', flag: '🇮🇪' },
  DUR: { city: 'Durban', country: 'South Africa', iso: 'ZA', flag: '🇿🇦' },
  DUS: { city: 'Dusseldorf', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  DVO: { city: 'Davao', country: 'Philippines', iso: 'PH', flag: '🇵🇭' },
  DXB: { city: 'Dubai', country: 'United Arab Emirates', iso: 'AE', flag: '🇦🇪' },
  EBB: { city: 'Kampala', country: 'Uganda', iso: 'UG', flag: '🇺🇬' },
  EDI: { city: 'Edinburgh', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  ENU: { city: 'Enugu', country: 'Nigeria', iso: 'NG', flag: '🇳🇬' },
  EPR: { city: 'Esperance', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  EZE: { city: 'Buenos Aires', country: 'Argentina', iso: 'AR', flag: '🇦🇷' },
  FAE: { city: 'Faroe Islands', country: 'Faroe Islands', iso: 'FO', flag: '🇫🇴' },
  FAO: { city: 'Faro', country: 'Portugal', iso: 'PT', flag: '🇵🇹' },
  FAT: { city: 'Fresno', country: 'United States', iso: 'US', flag: '🇺🇸' },
  FCO: { city: 'Rome', country: 'Italy', iso: 'IT', flag: '🇮🇹' },
  FIH: { city: 'Kinshasa', country: 'Congo, Democratic Republic', iso: 'CD', flag: '🇨🇩' },
  FLN: { city: 'Florianopolis', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  FOC: { city: 'Fuzhou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  FOR: { city: 'Fortaleza', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  FRA: { city: 'Frankfurt', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  FUK: { city: 'Fukuoka', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  GBE: { city: 'Gaborone', country: 'Botswana', iso: 'BW', flag: '🇧🇼' },
  GDN: { city: 'Gdansk', country: 'Poland', iso: 'PL', flag: '🇵🇱' },
  GIG: { city: 'Rio de Janeiro', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  GIZ: { city: 'Jazan', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  GND: { city: 'St. George\'s', country: 'Grenada', iso: 'GD', flag: '🇬🇩' },
  GOT: { city: 'Gothenburg', country: 'Sweden', iso: 'SE', flag: '🇸🇪' },
  GRU: { city: 'Sao Paulo', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  GUA: { city: 'Guatemala City', country: 'Guatemala', iso: 'GT', flag: '🇬🇹' },
  GUM: { city: 'Hagatna', country: 'Guam', iso: 'GU', flag: '🇬🇺' },
  GVA: { city: 'Geneva', country: 'Switzerland', iso: 'CH', flag: '🇨🇭' },
  GYN: { city: 'Goiania', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  HAK: { city: 'Haikou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  HAM: { city: 'Hamburg', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  HAN: { city: 'Hanoi', country: 'Vietnam', iso: 'VN', flag: '🇻🇳' },
  HAV: { city: 'Havana', country: 'Cuba', iso: 'CU', flag: '🇨🇺' },
  HEL: { city: 'Helsinki', country: 'Finland', iso: 'FI', flag: '🇫🇮' },
  HET: { city: 'Hohhot', country: 'China', iso: 'CN', flag: '🇨🇳' },
  HFA: { city: 'Haifa', country: 'Israel', iso: 'IL', flag: '🇮🇱' },
  HKG: { city: 'Hong Kong', country: 'Hong Kong', iso: 'HK', flag: '🇭🇰' },
  HNL: { city: 'Honolulu', country: 'United States', iso: 'US', flag: '🇺🇸' },
  HRE: { city: 'Harare', country: 'Zimbabwe', iso: 'ZW', flag: '🇿🇼' },
  HYD: { city: 'Hyderabad', country: 'India', iso: 'IN', flag: '🇮🇳' },
  IAD: { city: 'Washington', country: 'United States', iso: 'US', flag: '🇺🇸' },
  IAH: { city: 'Houston', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ICN: { city: 'Seoul', country: 'South Korea', iso: 'KR', flag: '🇰🇷' },
  IND: { city: 'Indianapolis', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ISB: { city: 'Islamabad', country: 'Pakistan', iso: 'PK', flag: '🇵🇰' },
  IST: { city: 'Istanbul', country: 'Turkey', iso: 'TR', flag: '🇹🇷' },
  ISU: { city: 'Sulaymaniyah', country: 'Iraq', iso: 'IQ', flag: '🇮🇶' },
  JAX: { city: 'Jacksonville', country: 'United States', iso: 'US', flag: '🇺🇸' },
  JED: { city: 'Jeddah', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  JIB: { city: 'Djibouti', country: 'Djibouti', iso: 'DJ', flag: '🇩🇯' },
  JNB: { city: 'Johannesburg', country: 'South Africa', iso: 'ZA', flag: '🇿🇦' },
  JOG: { city: 'Yogyakarta', country: 'Indonesia', iso: 'ID', flag: '🇮🇩' },
  JTR: { city: 'Santorini', country: 'Greece', iso: 'GR', flag: '🇬🇷' },
  JUL: { city: 'Juliaca', country: 'Peru', iso: 'PE', flag: '🇵🇪' },
  KBP: { city: 'Kyiv', country: 'Ukraine', iso: 'UA', flag: '🇺🇦' },
  KEF: { city: 'Reykjavik', country: 'Iceland', iso: 'IS', flag: '🇮🇸' },
  KHH: { city: 'Kaohsiung', country: 'Taiwan', iso: 'TW', flag: '🇹🇼' },
  KHI: { city: 'Karachi', country: 'Pakistan', iso: 'PK', flag: '🇵🇰' },
  KIN: { city: 'Kingston', country: 'Jamaica', iso: 'JM', flag: '🇯🇲' },
  KIX: { city: 'Osaka', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  KLD: { city: 'Tver', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  KMG: { city: 'Kunming', country: 'China', iso: 'CN', flag: '🇨🇳' },
  KRK: { city: 'Krakow', country: 'Poland', iso: 'PL', flag: '🇵🇱' },
  KTM: { city: 'Kathmandu', country: 'Nepal', iso: 'NP', flag: '🇳🇵' },
  KUL: { city: 'Kuala Lumpur', country: 'Malaysia', iso: 'MY', flag: '🇲🇾' },
  KWE: { city: 'Guiyang', country: 'China', iso: 'CN', flag: '🇨🇳' },
  KWI: { city: 'Kuwait City', country: 'Kuwait', iso: 'KW', flag: '🇰🇼' },
  LAN: { city: 'Lansing', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LAS: { city: 'Las Vegas', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LAX: { city: 'Los Angeles', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LCA: { city: 'Larnaca', country: 'Cyprus', iso: 'CY', flag: '🇨🇾' },
  LED: { city: 'St. Petersburg', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  LEX: { city: 'Lexington', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LFW: { city: 'Lome', country: 'Togo', iso: 'TG', flag: '🇹🇬' },
  LGA: { city: 'New York', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LHE: { city: 'Lahore', country: 'Pakistan', iso: 'PK', flag: '🇵🇰' },
  LHR: { city: 'London', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  LIM: { city: 'Lima', country: 'Peru', iso: 'PE', flag: '🇵🇪' },
  LIS: { city: 'Lisbon', country: 'Portugal', iso: 'PT', flag: '🇵🇹' },
  LJU: { city: 'Ljubljana', country: 'Slovenia', iso: 'SI', flag: '🇸🇮' },
  LOS: { city: 'Lagos', country: 'Nigeria', iso: 'NG', flag: '🇳🇬' },
  LPA: { city: 'Las Palmas', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  LPB: { city: 'La Paz', country: 'Bolivia', iso: 'BO', flag: '🇧🇴' },
  LPL: { city: 'Liverpool', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  LSA: { city: 'Los Angeles', country: 'United States', iso: 'US', flag: '🇺🇸' },
  LUN: { city: 'Lusaka', country: 'Zambia', iso: 'ZM', flag: '🇿🇲' },
  LUX: { city: 'Luxembourg', country: 'Luxembourg', iso: 'LU', flag: '🇱🇺' },
  LXR: { city: 'Luxor', country: 'Egypt', iso: 'EG', flag: '🇪🇬' },
  MAA: { city: 'Chennai', country: 'India', iso: 'IN', flag: '🇮🇳' },
  MAD: { city: 'Madrid', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  MAN: { city: 'Manchester', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  MAO: { city: 'Manaus', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  MBA: { city: 'Mombasa', country: 'Kenya', iso: 'KE', flag: '🇰🇪' },
  MCI: { city: 'Kansas City', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MCN: { city: 'Macon', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MCT: { city: 'Muscat', country: 'Oman', iso: 'OM', flag: '🇴🇲' },
  MDC: { city: 'Manado', country: 'Indonesia', iso: 'ID', flag: '🇮🇩' },
  MDE: { city: 'Medellin', country: 'Colombia', iso: 'CO', flag: '🇨🇴' },
  MDL: { city: 'Mandalay', country: 'Myanmar', iso: 'MM', flag: '🇲🇲' },
  MED: { city: 'Madinah', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  MEL: { city: 'Melbourne', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  MEM: { city: 'Memphis', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MEX: { city: 'Mexico City', country: 'Mexico', iso: 'MX', flag: '🇲🇽' },
  MFE: { city: 'McAllen', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MFM: { city: 'Macao', country: 'Macao', iso: 'MO', flag: '🇲🇴' },
  MGL: { city: 'Montgomery', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MHD: { city: 'Mashhad', country: 'Iran', iso: 'IR', flag: '🇮🇷' },
  MIA: { city: 'Miami', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MLE: { city: 'Male', country: 'Maldives', iso: 'MV', flag: '🇲🇻' },
  MLU: { city: 'Monroe', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MNL: { city: 'Manila', country: 'Philippines', iso: 'PH', flag: '🇵🇭' },
  MOW: { city: 'Moscow', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  MPM: { city: 'Maputo', country: 'Mozambique', iso: 'MZ', flag: '🇲🇿' },
  MRS: { city: 'Marseille', country: 'France', iso: 'FR', flag: '🇫🇷' },
  MRU: { city: 'Port Louis', country: 'Mauritius', iso: 'MU', flag: '🇲🇺' },
  MSN: { city: 'Madison', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MSP: { city: 'Minneapolis', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MSQ: { city: 'Minsk', country: 'Belarus', iso: 'BY', flag: '🇧🇾' },
  MSS: { city: 'Massena', country: 'United States', iso: 'US', flag: '🇺🇸' },
  MUC: { city: 'Munich', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  MVD: { city: 'Montevideo', country: 'Uruguay', iso: 'UY', flag: '🇺🇾' },
  MXP: { city: 'Milan', country: 'Italy', iso: 'IT', flag: '🇮🇹' },
  NAG: { city: 'Nagpur', country: 'India', iso: 'IN', flag: '🇮🇳' },
  NAY: { city: 'Beijing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  NBG: { city: 'New Orleans', country: 'United States', iso: 'US', flag: '🇺🇸' },
  NCL: { city: 'Newcastle', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  NDJ: { city: 'N\'Djamena', country: 'Chad', iso: 'TD', flag: '🇹🇩' },
  NGB: { city: 'Ningbo', country: 'China', iso: 'CN', flag: '🇨🇳' },
  NGO: { city: 'Nagoya', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  NKG: { city: 'Nanjing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  NRT: { city: 'Tokyo', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  NUA: { city: 'Nuuk', country: 'Greenland', iso: 'GL', flag: '🇬🇱' },
  NUE: { city: 'Nuremberg', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  OKA: { city: 'Okinawa', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  OKC: { city: 'Oklahoma City', country: 'United States', iso: 'US', flag: '🇺🇸' },
  OMA: { city: 'Omaha', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ONT: { city: 'Ontario', country: 'United States', iso: 'US', flag: '🇺🇸' },
  OOL: { city: 'Gold Coast', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  OPO: { city: 'Porto', country: 'Portugal', iso: 'PT', flag: '🇵🇹' },
  ORD: { city: 'Chicago', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ORF: { city: 'Norfolk', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ORK: { city: 'Cork', country: 'Ireland', iso: 'IE', flag: '🇮🇪' },
  ORN: { city: 'Oran', country: 'Algeria', iso: 'DZ', flag: '🇩🇿' },
  OSL: { city: 'Oslo', country: 'Norway', iso: 'NO', flag: '🇳🇴' },
  OTP: { city: 'Bucharest', country: 'Romania', iso: 'RO', flag: '🇷🇴' },
  OUA: { city: 'Ouagadougou', country: 'Burkina Faso', iso: 'BF', flag: '🇧🇫' },
  PAD: { city: 'Paderborn', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  PAP: { city: 'Port-au-Prince', country: 'Haiti', iso: 'HT', flag: '🇭🇹' },
  PAT: { city: 'Patna', country: 'India', iso: 'IN', flag: '🇮🇳' },
  PBM: { city: 'Paramaribo', country: 'Suriname', iso: 'SR', flag: '🇸🇷' },
  PDX: { city: 'Portland', country: 'United States', iso: 'US', flag: '🇺🇸' },
  PEK: { city: 'Beijing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  PEN: { city: 'Penang', country: 'Malaysia', iso: 'MY', flag: '🇲🇾' },
  PER: { city: 'Perth', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  PHL: { city: 'Philadelphia', country: 'United States', iso: 'US', flag: '🇺🇸' },
  PHX: { city: 'Phoenix', country: 'United States', iso: 'US', flag: '🇺🇸' },
  PIT: { city: 'Pittsburgh', country: 'United States', iso: 'US', flag: '🇺🇸' },
  PKX: { city: 'Beijing', country: 'China', iso: 'CN', flag: '🇨🇳' },
  PLM: { city: 'Palembang', country: 'Indonesia', iso: 'ID', flag: '🇮🇩' },
  PNS: { city: 'Pensacola', country: 'United States', iso: 'US', flag: '🇺🇸' },
  POL: { city: 'Pemba', country: 'Mozambique', iso: 'MZ', flag: '🇲🇿' },
  POS: { city: 'Port of Spain', country: 'Trinidad and Tobago', iso: 'TT', flag: '🇹🇹' },
  POZ: { city: 'Poznan', country: 'Poland', iso: 'PL', flag: '🇵🇱' },
  PRG: { city: 'Prague', country: 'Czechia', iso: 'CZ', flag: '🇨🇿' },
  PRY: { city: 'Pretoria', country: 'South Africa', iso: 'ZA', flag: '🇿🇦' },
  PTY: { city: 'Panama City', country: 'Panama', iso: 'PA', flag: '🇵🇦' },
  PUJ: { city: 'Punta Cana', country: 'Dominican Republic', iso: 'DO', flag: '🇩🇴' },
  PVG: { city: 'Shanghai', country: 'China', iso: 'CN', flag: '🇨🇳' },
  QRO: { city: 'Queretaro', country: 'Mexico', iso: 'MX', flag: '🇲🇽' },
  RAK: { city: 'Marrakesh', country: 'Morocco', iso: 'MA', flag: '🇲🇦' },
  RDU: { city: 'Raleigh', country: 'United States', iso: 'US', flag: '🇺🇸' },
  REC: { city: 'Recife', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  RFD: { city: 'Rockford', country: 'United States', iso: 'US', flag: '🇺🇸' },
  RGA: { city: 'Rio Gallegos', country: 'Argentina', iso: 'AR', flag: '🇦🇷' },
  RGL: { city: 'Rio Grande', country: 'Argentina', iso: 'AR', flag: '🇦🇷' },
  RIC: { city: 'Richmond', country: 'United States', iso: 'US', flag: '🇺🇸' },
  RIX: { city: 'Riga', country: 'Latvia', iso: 'LV', flag: '🇱🇻' },
  RMB: { city: 'Baghdad', country: 'Iraq', iso: 'IQ', flag: '🇮🇶' },
  RME: { city: 'Rome', country: 'United States', iso: 'US', flag: '🇺🇸' },
  RMQ: { city: 'Taichung', country: 'Taiwan', iso: 'TW', flag: '🇹🇼' },
  ROA: { city: 'Roanoke', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ROC: { city: 'Rochester', country: 'United States', iso: 'US', flag: '🇺🇸' },
  ROV: { city: 'Rostov-on-Don', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  RSU: { city: 'Yeosu', country: 'South Korea', iso: 'KR', flag: '🇰🇷' },
  RUH: { city: 'Riyadh', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  RUN: { city: 'Saint-Denis', country: 'Reunion', iso: 'RE', flag: '🇷🇪' },
  SAN: { city: 'San Diego', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SAP: { city: 'San Pedro Sula', country: 'Honduras', iso: 'HN', flag: '🇭🇳' },
  SAT: { city: 'San Antonio', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SAW: { city: 'Istanbul', country: 'Turkey', iso: 'TR', flag: '🇹🇷' },
  SCL: { city: 'Santiago', country: 'Chile', iso: 'CL', flag: '🇨🇱' },
  SCQ: { city: 'Santiago de Compostela', country: 'Spain', iso: 'ES', flag: '🇪🇸' },
  SDF: { city: 'Louisville', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SDQ: { city: 'Santo Domingo', country: 'Dominican Republic', iso: 'DO', flag: '🇩🇴' },
  SEA: { city: 'Seattle', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SFO: { city: 'San Francisco', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SIN: { city: 'Singapore', country: 'Singapore', iso: 'SG', flag: '🇸🇬' },
  SJC: { city: 'San Jose', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SJD: { city: 'San Jose del Cabo', country: 'Mexico', iso: 'MX', flag: '🇲🇽' },
  SJJ: { city: 'Sarajevo', country: 'Bosnia and Herzegovina', iso: 'BA', flag: '🇧🇦' },
  SJU: { city: 'San Juan', country: 'Puerto Rico', iso: 'PR', flag: '🇵🇷' },
  SKG: { city: 'Thessaloniki', country: 'Greece', iso: 'GR', flag: '🇬🇷' },
  SKP: { city: 'Skopje', country: 'North Macedonia', iso: 'MK', flag: '🇲🇰' },
  SLC: { city: 'Salt Lake City', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SMF: { city: 'Sacramento', country: 'United States', iso: 'US', flag: '🇺🇸' },
  SNU: { city: 'Santa Clara', country: 'Cuba', iso: 'CU', flag: '🇨🇺' },
  SOF: { city: 'Sofia', country: 'Bulgaria', iso: 'BG', flag: '🇧🇬' },
  SOU: { city: 'Southampton', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  SSE: { city: 'Srinagar', country: 'India', iso: 'IN', flag: '🇮🇳' },
  SSG: { city: 'Malabo', country: 'Equatorial Guinea', iso: 'GQ', flag: '🇬🇶' },
  STI: { city: 'Santiago', country: 'Dominican Republic', iso: 'DO', flag: '🇩🇴' },
  STL: { city: 'St. Louis', country: 'United States', iso: 'US', flag: '🇺🇸' },
  STN: { city: 'London', country: 'United Kingdom', iso: 'GB', flag: '🇬🇧' },
  STR: { city: 'Stuttgart', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  SUV: { city: 'Suva', country: 'Fiji', iso: 'FJ', flag: '🇫🇯' },
  SVO: { city: 'Moscow', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  SVX: { city: 'Yekaterinburg', country: 'Russia', iso: 'RU', flag: '🇷🇺' },
  SXB: { city: 'Strasbourg', country: 'France', iso: 'FR', flag: '🇫🇷' },
  SXF: { city: 'Berlin', country: 'Germany', iso: 'DE', flag: '🇩🇪' },
  SYD: { city: 'Sydney', country: 'Australia', iso: 'AU', flag: '🇦🇺' },
  SYX: { city: 'Sanya', country: 'China', iso: 'CN', flag: '🇨🇳' },
  SZV: { city: 'Suzhou', country: 'China', iso: 'CN', flag: '🇨🇳' },
  SZX: { city: 'Shenzhen', country: 'China', iso: 'CN', flag: '🇨🇳' },
  TAE: { city: 'Daegu', country: 'South Korea', iso: 'KR', flag: '🇰🇷' },
  TAI: { city: 'Taiz', country: 'Yemen', iso: 'YE', flag: '🇾🇪' },
  TAK: { city: 'Takamatsu', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  TAS: { city: 'Tashkent', country: 'Uzbekistan', iso: 'UZ', flag: '🇺🇿' },
  TAY: { city: 'Tartu', country: 'Estonia', iso: 'EE', flag: '🇪🇪' },
  TBZ: { city: 'Tabriz', country: 'Iran', iso: 'IR', flag: '🇮🇷' },
  TEN: { city: 'Tongren', country: 'China', iso: 'CN', flag: '🇨🇳' },
  TGD: { city: 'Podgorica', country: 'Montenegro', iso: 'ME', flag: '🇲🇪' },
  TGU: { city: 'Tegucigalpa', country: 'Honduras', iso: 'HN', flag: '🇭🇳' },
  THR: { city: 'Tehran', country: 'Iran', iso: 'IR', flag: '🇮🇷' },
  TIA: { city: 'Tirana', country: 'Albania', iso: 'AL', flag: '🇦🇱' },
  TIF: { city: 'Taif', country: 'Saudi Arabia', iso: 'SA', flag: '🇸🇦' },
  TLL: { city: 'Tallinn', country: 'Estonia', iso: 'EE', flag: '🇪🇪' },
  TLV: { city: 'Tel Aviv', country: 'Israel', iso: 'IL', flag: '🇮🇱' },
  TNA: { city: 'Jinan', country: 'China', iso: 'CN', flag: '🇨🇳' },
  TNG: { city: 'Tangier', country: 'Morocco', iso: 'MA', flag: '🇲🇦' },
  TNR: { city: 'Antananarivo', country: 'Madagascar', iso: 'MG', flag: '🇲🇬' },
  TPE: { city: 'Taipei', country: 'Taiwan', iso: 'TW', flag: '🇹🇼' },
  TRN: { city: 'Turin', country: 'Italy', iso: 'IT', flag: '🇮🇹' },
  TSE: { city: 'Astana', country: 'Kazakhstan', iso: 'KZ', flag: '🇰🇿' },
  TSN: { city: 'Tianjin', country: 'China', iso: 'CN', flag: '🇨🇳' },
  TUN: { city: 'Tunis', country: 'Tunisia', iso: 'TN', flag: '🇹🇳' },
  TUS: { city: 'Tucson', country: 'United States', iso: 'US', flag: '🇺🇸' },
  TVC: { city: 'Traverse City', country: 'United States', iso: 'US', flag: '🇺🇸' },
  TYO: { city: 'Tokyo', country: 'Japan', iso: 'JP', flag: '🇯🇵' },
  TYS: { city: 'Knoxville', country: 'United States', iso: 'US', flag: '🇺🇸' },
  UDI: { city: 'Uberlandia', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  UET: { city: 'Quetta', country: 'Pakistan', iso: 'PK', flag: '🇵🇰' },
  UIO: { city: 'Quito', country: 'Ecuador', iso: 'EC', flag: '🇪🇨' },
  ULN: { city: 'Ulaanbaatar', country: 'Mongolia', iso: 'MN', flag: '🇲🇳' },
  URC: { city: 'Urumqi', country: 'China', iso: 'CN', flag: '🇨🇳' },
  VAN: { city: 'Van', country: 'Turkey', iso: 'TR', flag: '🇹🇷' },
  VAP: { city: 'Valparaiso', country: 'Chile', iso: 'CL', flag: '🇨🇱' },
  VCP: { city: 'Campinas', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  VIE: { city: 'Vienna', country: 'Austria', iso: 'AT', flag: '🇦🇹' },
  VNO: { city: 'Vilnius', country: 'Lithuania', iso: 'LT', flag: '🇱🇹' },
  VTE: { city: 'Vientiane', country: 'Laos', iso: 'LA', flag: '🇱🇦' },
  WAW: { city: 'Warsaw', country: 'Poland', iso: 'PL', flag: '🇵🇱' },
  WDH: { city: 'Windhoek', country: 'Namibia', iso: 'NA', flag: '🇳🇦' },
  WMI: { city: 'Warsaw', country: 'Poland', iso: 'PL', flag: '🇵🇱' },
  WUH: { city: 'Wuhan', country: 'China', iso: 'CN', flag: '🇨🇳' },
  XAP: { city: 'Chapeco', country: 'Brazil', iso: 'BR', flag: '🇧🇷' },
  XFN: { city: 'Xiangyang', country: 'China', iso: 'CN', flag: '🇨🇳' },
  XIY: { city: 'Xi\'an', country: 'China', iso: 'CN', flag: '🇨🇳' },
  XMN: { city: 'Xiamen', country: 'China', iso: 'CN', flag: '🇨🇳' },
  YHZ: { city: 'Halifax', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YOW: { city: 'Ottawa', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YUL: { city: 'Montreal', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YVR: { city: 'Vancouver', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YWG: { city: 'Winnipeg', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YYC: { city: 'Calgary', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  YYZ: { city: 'Toronto', country: 'Canada', iso: 'CA', flag: '🇨🇦' },
  ZAG: { city: 'Zagreb', country: 'Croatia', iso: 'HR', flag: '🇭🇷' },
  ZDM: { city: 'Ramon', country: 'Israel', iso: 'IL', flag: '🇮🇱' },
  ZRH: { city: 'Zurich', country: 'Switzerland', iso: 'CH', flag: '🇨🇭' },
};

function locationFromColo(colo) {
  const key = String(colo || '').trim().toUpperCase();
  return EDGE_LOCATIONS[key] || (key ? { city: key, country: 'Cloudflare edge', flag: '🌐' } : { city: 'Auto edge', country: 'Cloudflare edge', flag: '🌐' });
}

function parseLocationMap(raw) {
  const out = {};
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    Object.keys(raw).forEach((key) => { out[String(key).toLowerCase()] = String(raw[key] || '').toUpperCase(); });
    return out;
  }
  splitCsv(raw).forEach((item) => {
    const pair = String(item).split('=');
    if (pair.length === 2 && pair[0].trim() && pair[1].trim()) out[pair[0].trim().toLowerCase()] = pair[1].trim().toUpperCase();
  });
  return out;
}

function locationFromCodeOrColo(raw) {
  const code = String(raw || '').trim().toUpperCase();
  const byColo = locationFromColo(code);
  if (code.length !== 2 || byColo.country !== 'Cloudflare edge') return byColo;
  const found = Object.values(EDGE_LOCATIONS).find((item) => countryCodeFromFlag(item.flag) === code);
  return found || { city: 'Auto edge', country: 'Cloudflare edge', flag: '🌐' };
}

function configLocation(options, addr) {
  const key = String(addr || '').replace(/^\[|\]$/g, '').toLowerCase();
  const code = (options.locations && options.locations[key]) || options.country || '';
  return locationFromCodeOrColo(code);
}

function normalizedVerifiedEntries(settings) {
  const raw = settings && settings.configs && Array.isArray(settings.configs.verified)
    ? settings.configs.verified
    : [];
  const seen = new Set();
  return raw.map((item) => {
    const ip = String(item && (item.ip || item.address) || '').trim().replace(/^\[|\]$/g, '');
    if (!validAddress(ip) || seen.has(ip.toLowerCase())) return null;
    const location = locationFromColo(item && (item.colo || item.location || ''));
    const code = String(item && (item.countryCode || '') || '').trim().toUpperCase();
    seen.add(ip.toLowerCase());
    return {
      ip: ip,
      colo: String(item && item.colo || '').trim().toUpperCase(),
      countryCode: code || countryCodeFromFlag(location.flag),
      countryName: String(item && item.countryName || location.country || 'Cloudflare edge'),
      checkedAt: Number(item && item.checkedAt) || 0,
    };
  }).filter(Boolean);
}

function countryCodeFromFlag(flag) {
  const points = Array.from(String(flag || '')).map((char) => char.codePointAt(0));
  if (points.length !== 2 || points.some((point) => point < 0x1f1e6 || point > 0x1f1ff)) return '';
  return points.map((point) => String.fromCharCode(point - 0x1f1e6 + 65)).join('');
}

function countryPools(entries) {
  const map = new Map();
  for (const entry of entries || []) {
    const loc = locationFromCodeOrColo(entry.colo || entry.countryCode || '');
    const code = String(entry.countryCode || (loc && loc.iso) || '').toUpperCase();
    const flag = (loc && loc.flag) || flagFromCountry(code);
    const name = String(entry.countryName || (loc && loc.country) || 'Cloudflare edge').trim() || 'Cloudflare edge';
    const key = code || '-';
    if (!map.has(key)) map.set(key, { code: code, name: name, flag: flag, count: 0, ips: [] });
    const pool = map.get(key);
    pool.count += 1;
    if (entry.ip && pool.ips.length < 48 && !pool.ips.includes(entry.ip)) pool.ips.push(entry.ip);
  }
  return Array.from(map.values()).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
}

/* Auto country discovery: before the owner ever runs a scan, probe a small IP
 * pool in the background so the recipient chooser has real countries with
 * real flags within seconds of the first visit. */
let verifiedPoolJob = null;
function probeCandidates() {
  const seen = new Set();
  const out = [];
  const all = DEFAULT_CLEAN_ADDRESSES.concat(IR_CLEAN_IPS, COMMUNITY_IPS);
  for (const raw of all) {
    const ip = String(raw).trim();
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(ip) || seen.has(ip)) continue;
    seen.add(ip);
    out.push(ip);
    if (out.length >= 24) break;
  }
  return out;
}
async function ensureVerifiedPool(env, host) {
  try {
    const current = await readSettings(env);
    if (current.configs && current.configs.verifiedScanned === true) return;
    if (verifiedPoolJob) return verifiedPoolJob;
    verifiedPoolJob = (async () => {
      try {
        const results = await Promise.all(probeCandidates().map((ip) => probeIp(ip, 3500, host, env)));
        const verified = results
          .filter((r) => r && r.ok && r.colo)
          .map((r) => ({ ip: r.ip, colo: r.colo, countryCode: r.countryCode, countryName: r.countryName, checkedAt: Date.now() }));
        if (!verified.length) return;
        const fresh = await readSettings(env);
        const existing = normalizedVerifiedEntries(fresh);
        const merged = verified.concat(existing.filter((e) => !verified.some((v) => v.ip === e.ip)));
        await writeSettings(env, {
          configs: {
            verified: merged.slice(0, 240),
            verifiedScanned: true,
            verifiedAt: Date.now(),
          },
        });
      } catch (e) {
        // best-effort background discovery; a later request retries
      } finally {
        verifiedPoolJob = null;
      }
    })();
    return verifiedPoolJob;
  } catch (e) {
    return null;
  }
}

function unionAddresses() {
  const out = [];
  const seen = new Set();
  for (const list of arguments) {
    for (const item of list || []) {
      const key = String(item).toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
      if (!key || seen.has(key)) continue;
      seen.add(key);
      out.push(key);
    }
  }
  return out;
}

function configOptions(url, host, env, settings, allowedCountries) {
  const cfg = (settings && settings.configs) || {};
  const q = url && url.searchParams ? url.searchParams : new URLSearchParams();
  const fromQuery = splitCsv(q.get('ips') || q.get('addresses'));
  const fromSettings = Array.isArray(cfg.addresses) ? cfg.addresses : [];
  const fromEnv = splitCsv(env.CF_IPS);
  const verifiedEntries = normalizedVerifiedEntries(settings);
  const ownerGate = Array.isArray(allowedCountries) ? allowedCountries : null;
  const savedCountryCodes = Array.isArray(cfg.countryCodes)
    ? cfg.countryCodes
    : splitCsv(typeof cfg.countryCodes === 'string' ? cfg.countryCodes : '');
  const queryCountries = splitCsv(q.get('countries') || q.get('country'));
  let requestedCountryCodes = (queryCountries.length
    ? queryCountries
    : savedCountryCodes.length
      ? savedCountryCodes.concat(splitCsv(cfg.country))
      : splitCsv(cfg.country).concat(splitCsv(env.COUNTRY)))
    .map((value) => String(value).trim().toUpperCase())
    .filter(Boolean);
  requestedCountryCodes = Array.from(new Set(requestedCountryCodes));
  let addresses = fromQuery.length ? fromQuery : fromSettings.length ? fromSettings : fromEnv.length ? fromEnv : DEFAULT_CLEAN_ADDRESSES;
  const locations = parseLocationMap(q.get('locs') || cfg.locations || {});

  // A Cloudflare anycast IP has no permanent country. Once the panel has a
  // successful probe, use that probe's colo for both filtering and labels.
  // Explicit ?ips= remains an escape hatch for a hand-built config.
  const verifiedScanComplete = cfg.verifiedScanned === true;
  const strictVerified = q.get('verified') === '1';
  const useVerified = (verifiedScanComplete || strictVerified) && q.get('verified') !== '0' && !fromQuery.length;
  const explicitManual = fromQuery.length ? fromQuery : fromSettings;
  if (useVerified) {
    const selected = requestedCountryCodes.length
      ? verifiedEntries.filter((entry) => requestedCountryCodes.includes(entry.countryCode))
      : verifiedEntries;
    // UNION, never replacement: hand-picked/selected addresses always survive
    // a scan; verified scan results are ADDED on top. Strict ?verified=1
    // (recipient page) stays scan-only.
    addresses = strictVerified
      ? selected.map((entry) => entry.ip)
      : unionAddresses(explicitManual, selected.map((entry) => entry.ip));
    selected.forEach((entry) => {
      if (entry.colo) locations[entry.ip.toLowerCase()] = entry.colo;
    });
  } else if (verifiedEntries.length) {
    verifiedEntries.forEach((entry) => {
      if (entry.colo) locations[entry.ip.toLowerCase()] = entry.colo;
    });
  }
  if (requestedCountryCodes.length && Object.keys(locations).length) {
    addresses = addresses.filter((address) => {
      const raw = String(locations[String(address).toLowerCase()] || '').toUpperCase();
      const foundLoc = locationFromCodeOrColo(raw);
      const code = String((foundLoc && foundLoc.iso) || raw).toUpperCase();
      if (!isIpLiteral(address)) return true;
      // Hand-picked addresses the owner deliberately chose stay even before
      // their country is known; only scanned pool entries are country-filtered.
      if (!raw) return explicitManual.includes(address);
      return requestedCountryCodes.includes(code);
    });
  }
  // Multi-location subs: rotate countries so consecutive configs alternate
  // (DE, FR, NL, DE, FR, NL…) instead of coming out as country blocks.
  if (requestedCountryCodes.length > 1 && Object.keys(locations).length) {
    const groups = new Map();
    addresses.forEach((address) => {
      const raw = String(locations[String(address).toLowerCase()] || '').toUpperCase();
      const found = locationFromCodeOrColo(raw);
      const code = String((found && found.iso) || raw || '??').toUpperCase();
      if (!groups.has(code)) groups.set(code, []);
      groups.get(code).push(address);
    });
    const rotated = [];
    let added = true;
    while (added) {
      added = false;
      groups.forEach((group) => {
        if (group.length) {
          rotated.push(group.shift());
          added = true;
        }
      });
    }
    if (rotated.length && rotated.length >= addresses.length) addresses = rotated;
  }
  addresses = addresses.filter(validAddress).slice(0, MAX_SUB_ADDRESSES);
  // Safety net: if every address was pruned (e.g. health-check removed all),
  // fall back to the built-in clean set so the sub never goes silently empty.
  if (!addresses.length && !ownerGate) addresses = DEFAULT_CLEAN_ADDRESSES.slice(0, 12);

  const portSource = splitCsv(q.get('ports') || q.get('port'));
  const envPorts = splitCsv(env.PORTS || env.PORT);
  let ports = (portSource.length ? portSource : Array.isArray(cfg.ports) && cfg.ports.length ? cfg.ports : envPorts.length ? envPorts : DEFAULT_PORTS)
    .map((p) => Number(p)).filter((p) => TLS_PORTS.includes(p) || PLAIN_PORTS.includes(p));
  if (!ports.length) ports = DEFAULT_PORTS.slice();
  ports = Array.from(new Set(ports)).slice(0, 8);

  const sniRaw = String(q.get('sni') || cfg.sni || env.SNI || '').trim().toLowerCase();
  const sni = sniRaw && validAddress(sniRaw) && !isIpLiteral(sniRaw) ? sniRaw : String(host).toLowerCase();
  const snisExtra = splitCsv(q.get('snis')).concat(Array.isArray(cfg.snis) ? cfg.snis.map(String) : splitCsv(typeof cfg.snis === 'string' ? cfg.snis : ''));
  const snis = [sni].concat(snisExtra.map((v) => v.trim().toLowerCase()))
    .filter((v, i, arr) => v && arr.indexOf(v) === i && validAddress(v) && !isIpLiteral(v))
    .slice(0, 4);

  const protoRaw = splitCsv(q.get('proto') || q.get('protocols')).map((p) => p.toLowerCase());
  const protocols = (protoRaw.length ? protoRaw : Array.isArray(cfg.protocols) && cfg.protocols.length ? cfg.protocols : ['vless', 'trojan'])
    .filter((p) => p === 'vless' || p === 'trojan');
  const includeHost = q.has('host')
    ? q.get('host') !== '0'
    : (strictVerified || useVerified ? false : cfg.includeHost !== false);
  const fragment = q.get('fragment') === '1';
  const fpRaw = String(q.get('fp') || cfg.fingerprint || env.FINGERPRINT || 'chrome').toLowerCase();
  const fingerprint = /^(chrome|firefox|safari|ios|android|edge|360|qq|random|randomized)$/.test(fpRaw) ? fpRaw : 'chrome';
  const includeV6 = q.has('v6') ? q.get('v6') !== '0' : cfg.includeIpv6 !== false;
  const pathName = url && url.pathname ? String(url.pathname) : '';
  const recipientPath = pathName === '/u' || pathName.startsWith('/u/') || pathName.startsWith('/info/');
  if (ownerGate) {
    // Per-user gate: the owner picks each user's countries first; until then the
    // user gets zero configs, and afterwards only from the picked set.
    if (!ownerGate.length) {
      return {
        addresses: [], ports: DEFAULT_PORTS.slice(), sni: String(host).toLowerCase(),
        protocols: ['vless', 'trojan'], includeHost: false, fragment: false,
        fingerprint: 'chrome', includeIpv6: false, locations: {},
        country: '', countryCodes: [], verifiedEntries: [],
        entryLimit: 0, count: 0, max: MAX_SUB_ENTRIES,
      };
    }
    requestedCountryCodes = requestedCountryCodes.length
      ? requestedCountryCodes.filter((code) => ownerGate.includes(code))
      : ownerGate.slice();
  }
  const requestedCount = Number(q.get('count') || cfg.entryLimit || (recipientPath ? DEFAULT_SUB_ENTRIES : MAX_SUB_ENTRIES));
  const entryLimit = Number.isFinite(requestedCount) ? Math.max(1, Math.min(MAX_SUB_ENTRIES, Math.floor(requestedCount))) : DEFAULT_SUB_ENTRIES;
  return {
    addresses: addresses,
    ports: ports,
    sni: sni,
    snis: snis,
    protocols: protocols.length ? protocols : ['vless'],
    includeHost: includeHost,
    fragment: fragment,
    fingerprint: fingerprint,
    includeIpv6: includeV6,
    locations: locations,
    country: requestedCountryCodes[0] || String(cfg.country || env.COUNTRY || '').trim().toUpperCase(),
    countryCodes: requestedCountryCodes,
    entryLimit: entryLimit,
    verifiedOnly: useVerified,
    verifiedEntries: verifiedEntries,
  };
}

function defaultConfigOptions(host, env) {
  return configOptions(null, host, env, null);
}

function linkParams(host, env, opts, port, kind) {
  const paths = panelPaths(env);
  const tls = TLS_PORTS.includes(port);
  const path = kind === 'vless' ? paths.vlessPath : paths.trojanPath;
  const common = '&type=ws&path=' + encodeURIComponent(path) + '&host=' + encodeURIComponent(String(host));
  if (!tls) return 'security=none' + common;
  // chrome is widely understood by current clients; randomized is Xray-only.
  return 'security=tls&sni=' + encodeURIComponent(opts.sni) + '&fp=' + encodeURIComponent(opts.fingerprint || 'chrome') + '&alpn=' + encodeURIComponent('http/1.1') + common;
}

function addrKind(addr, host) {
  const v = String(addr || '').replace(/^\[/, '').replace(/\]$/, '');
  if (v.toLowerCase() === String(host || '').toLowerCase()) return 'Domain';
  if (ipToLong(v) !== null) return 'IPv4';
  if (v.includes(':')) return 'IPv6';
  return 'CDN';
}

/** Location-first Cat remark; the address is deliberately never put in the name. */
function configName(kind, addr, port, index, host, options) {
  const label = kind === 'vless' ? 'VLESS' : 'Trojan';
  const location = configLocation(options || {}, addr);
  // Keep the address in the URL/table, never in the client-facing remark.
  return '🐱 Cat · ' + location.country + ' · ' + label + ' · ' + port + ' · ' + location.flag + ' · #' + String(index).padStart(2, '0');
}

/** Build a VLESS-WS share link (used for the host itself and for clean IPs). */
function vlessLink(host, env, uuid, addr, name, overrides = {}) {
  const opts = Object.assign(defaultConfigOptions(host, env), overrides.sni ? { sni: String(overrides.sni).toLowerCase() } : {}, overrides.fingerprint ? { fingerprint: overrides.fingerprint } : {});
  const port = Number(overrides.port || panelPaths(env).port);
  const hostHeader = overrides.hostHeader || String(host);
  const params = overrides.path
    ? linkParams(hostHeader, Object.assign({}, env, { VLESS_PATH: overrides.path }), opts, port, 'vless')
    : linkParams(hostHeader, env, opts, port, 'vless');
  return 'vless://' + uuid + '@' + formatAddr(addr) + ':' + port + '?encryption=none&' + params + '#' + encodeURIComponent(name);
}

/** Build a Trojan-WS share link. */
function trojanLink(host, env, uuid, addr, name, overrides = {}) {
  const opts = Object.assign(defaultConfigOptions(host, env), overrides.sni ? { sni: String(overrides.sni).toLowerCase() } : {}, overrides.fingerprint ? { fingerprint: overrides.fingerprint } : {});
  const port = Number(overrides.port || panelPaths(env).port);
  const hostHeader = overrides.hostHeader || String(host);
  const pass = String(env.TROJAN_PASS || uuid);
  const params = overrides.path
    ? linkParams(hostHeader, Object.assign({}, env, { TROJAN_PATH: overrides.path }), opts, port, 'trojan')
    : linkParams(hostHeader, env, opts, port, 'trojan');
  return 'trojan://' + encodeURIComponent(pass) + '@' + formatAddr(addr) + ':' + port + '?' + params + '#' + encodeURIComponent(name);
}

/** Every (address × port × protocol) combination as structured entries. */
/**
 * Cat ordering: for every port (80 first) emit Domain → IPv4 → IPv6 → CDN
 * domains, VLESS before Trojan. Clients that connect to "the first that works"
 * hit the plain-HTTP clean-IP entries before anything SNI-dependent.
 */
function buildConfigEntries(host, env, uuid, opts) {
  const options = opts || defaultConfigOptions(host, env);
  const addresses = [];
  const seen = new Set();
  const push = (a) => {
    const key = String(a).toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
    if (!key || seen.has(key)) return;
    seen.add(key);
    addresses.push(String(a).replace(/^\[/, '').replace(/\]$/, ''));
  };
  if (options.includeHost) push(String(host));
  const v4 = options.addresses.filter((a) => ipToLong(a) !== null);
  const names = options.addresses.filter((a) => ipToLong(a) === null && !isIpLiteral(a));
  const v6 = options.addresses.filter((a) => isIpLiteral(a) && ipToLong(a) === null);
  v4.forEach(push);
  if (options.includeIpv6 !== false) {
    (v6.length ? v6 : (options.verifiedOnly ? [] : DEFAULT_CLEAN_IPV6)).forEach(push);
  }
  names.forEach(push);
  const entries = [];
  const entryLimit = Math.min(MAX_SUB_ENTRIES, Number(options.entryLimit) || DEFAULT_SUB_ENTRIES);
  let index = 0;
  options.protocols.forEach((kind) => {
    options.ports.forEach((port) => {
      addresses.forEach((addr) => {
        if (entries.length >= entryLimit) return;
        index += 1;
        const snis = (options.snis && options.snis.length) ? options.snis : [options.sni];
        const sni = snis[index % snis.length];
        const name = configName(kind, addr, port, index, host, options);
        const overrides = { port: port, sni: sni, fingerprint: options.fingerprint };
        const link = kind === 'vless'
          ? vlessLink(host, env, uuid, addr, name, overrides)
          : trojanLink(host, env, uuid, addr, name, overrides);
        const location = configLocation(options, addr);
        entries.push({
          name: name,
          kind: kind,
          addr: addr,
          port: port,
          tls: TLS_PORTS.includes(port),
          link: link,
          countryCode: countryCodeFromFlag(location.flag),
          countryName: location.country,
          city: location.city,
          flag: location.flag,
        });
      });
    });
  });
  return entries;
}

function buildSubLinks(host, env, uuid, opts, includeWarp) {
  const links = buildConfigEntries(host, env, uuid, opts).map((e) => e.link);
  // `warp://` is a Cat Client extension; v2rayNG / v2box / Streisand reject unknown
  // schemes and may drop the whole subscription, so it is opt-in (?warp=1).
  if (includeWarp && String(env.ENABLE_WARP).toLowerCase() !== 'false') links.push('warp://#🐱 Cat WARP');
  return links;
}

function buildClashYaml(host, env, uuid, opts) {
  const options = opts || defaultConfigOptions(host, env);
  const paths = panelPaths(env);
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const proxyNames = [];
  const proxyBlocks = [];
  const addProxy = (name, block) => {
    proxyNames.push(name);
    proxyBlocks.push('  - name: ' + yamlQuote(name) + '\n' + block);
  };
  const tlsBlock = (port) => TLS_PORTS.includes(port)
    ? '    tls: true\n    servername: ' + options.sni + '\n    client-fingerprint: ' + (options.fingerprint === 'randomized' ? 'random' : (options.fingerprint || 'chrome')) + '\n'
    : '    tls: false\n';
  const wsBlock = (path) =>
    '    network: ws\n' +
    '    ws-opts:\n' +
    '      path: ' + yamlQuote(path) + '\n' +
    '      headers:\n' +
    '        Host: ' + host + '\n' +
    '      max-early-data: 2048\n' +
    '      early-data-header-name: Sec-WebSocket-Protocol\n';
  buildConfigEntries(host, env, uuid, options).forEach((e) => {
    if (e.kind === 'vless') {
      addProxy(e.name,
        '    type: vless\n    server: ' + e.addr + '\n    port: ' + e.port + '\n    uuid: ' + uuid + '\n    udp: true\n' +
        tlsBlock(e.port) + wsBlock(paths.vlessPath.split('?')[0]));
    } else {
      addProxy(e.name,
        '    type: trojan\n    server: ' + e.addr + '\n    port: ' + e.port + '\n    password: ' + yamlQuote(trojanPass) + '\n    udp: true\n' +
        tlsBlock(e.port) + wsBlock(paths.trojanPath));
    }
  });
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
    '    - https://' + host + '/dns-query\n' +
    '    - 178.22.122.100\n' +
    '  fallback:\n' +
    '    - 8.8.8.8\n' +
    'proxies:\n' + proxyBlocks.join('\n') + '\n' +
    'proxy-groups:\n' +
    '  - name: "Proxy"\n' +
    '    type: select\n' +
    '    proxies:\n      - "Auto"\n' + group + '\n' +
    '  - name: "Auto"\n' +
    '    type: url-test\n' +
    '    url: "https://www.gstatic.com/generate_204"\n' +
    '    interval: 300\n' +
    '    tolerance: 50\n' +
    '    proxies:\n' + group + '\n' +
    'rules:\n' +
    '  - GEOIP,LAN,direct\n' +
    '  - GEOSITE,iran,direct\n' +
    '  - GEOIP,IR,direct\n' +
    '  - MATCH,Proxy\n'
  );
}

function buildSingboxConfig(host, env, uuid, opts) {
  const options = opts || defaultConfigOptions(host, env);
  const paths = panelPaths(env);
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const outbounds = [];
  const tags = [];
  buildConfigEntries(host, env, uuid, options).forEach((e) => {
    const tls = e.tls
      ? { enabled: true, server_name: options.sni, alpn: ['http/1.1'], utls: { enabled: true, fingerprint: options.fingerprint === 'randomized' ? 'random' : (options.fingerprint || 'chrome') } }
      : { enabled: false };
    const transport = {
      type: 'ws',
      path: (e.kind === 'vless' ? paths.vlessPath : paths.trojanPath).split('?')[0],
      headers: { Host: host },
      max_early_data: 2048,
      early_data_header_name: 'Sec-WebSocket-Protocol',
    };
    tags.push(e.name);
    outbounds.push(e.kind === 'vless'
      ? { type: 'vless', tag: e.name, server: e.addr, server_port: e.port, uuid: uuid, tls: tls, transport: transport }
      : { type: 'trojan', tag: e.name, server: e.addr, server_port: e.port, password: trojanPass, tls: tls, transport: transport });
  });
  return JSON.stringify(
    {
      log: { level: 'warn', timestamp: true },
      dns: {
        servers: [
          { tag: 'cat-doh', address: 'https://' + host + '/dns-query', detour: 'proxy' },
          { tag: 'local', address: 'local', detour: 'direct' },
        ],
        rules: [{ clash_mode: 'direct', server: 'local' }],
        final: 'cat-doh',
        strategy: 'prefer_ipv4',
      },
      inbounds: [
        { type: 'mixed', tag: 'mixed-in', listen: '127.0.0.1', listen_port: 2080 },
        { type: 'tun', tag: 'tun-in', address: ['172.19.0.1/30'], auto_route: true, strict_route: false, stack: 'mixed' },
      ],
      outbounds: outbounds.concat([
        { type: 'selector', tag: 'proxy', outbounds: ['auto'].concat(tags), default: 'auto' },
        { type: 'urltest', tag: 'auto', outbounds: tags, url: 'https://www.gstatic.com/generate_204', interval: '5m' },
        { type: 'direct', tag: 'direct' },
      ]),
      route: {
        rules: [
          { action: 'sniff' },
          { protocol: 'dns', action: 'hijack-dns' },
          { ip_is_private: true, outbound: 'direct' },
          { geoip: ['ir'], outbound: 'direct' },
        ],
        final: 'proxy',
        auto_detect_interface: true,
      },
    },
    null,
    2,
  );
}

function buildAllConfigs(host, env, uuid, opts) {
  const options = opts || defaultConfigOptions(host, env);
  const entries = buildConfigEntries(host, env, uuid, options);
  const groups = {};
  entries.forEach((entry) => {
    const key = entry.countryCode || 'EDGE';
    if (!groups[key]) groups[key] = { countryCode: key, countryName: entry.countryName, flag: entry.flag, entries: [] };
    groups[key].entries.push(entry);
  });
  return {
    panel: 'cat-panel',
    version: CAT_PANEL_VERSION,
    host: host,
    sni: options.sni,
    uuid: uuid,
    ports: options.ports,
    protocols: options.protocols,
    paths: { vless: panelPaths(env).vlessPath, trojan: panelPaths(env).trojanPath },
    cleanIps: options.addresses,
    addresses: options.addresses,
    sniWhitelist: Array.from(allowedSnis(host, env)),
    remoteTunnel: !!env.REMOTE,
    doh: 'https://' + host + '/dns-query',
    subscription: 'https://' + host + '/sub/' + uuid,
    verifiedOnly: !!options.verifiedOnly,
    countryCodes: options.countryCodes || [],
    entries: entries,
    groups: Object.values(groups),
    links: entries.map((entry) => entry.link),
  };
}

function subUserInfoHeader(env) {
  const total = Number(env.USER_TOTAL || 1099511627776);
  return 'upload=0; download=0; total=' + total;
}

/* ------------------------------------------------------------------ */
/* encrypted DNS (DoH) resolver                                        */
/* ------------------------------------------------------------------ */

const DNS_PRESETS = [
  // Iran-friendly resolvers first: IP-based endpoints survive DNS filtering.
  { id: 'shecan', name: 'Shecan (ایران)', url: 'https://178.22.122.100/dns-query', dot: 'shecan.ir', sni: 'shecan.ir', ir: true },
  { id: 'electro', name: 'Electro (ایران)', url: 'https://78.157.42.100/dns-query', dot: 'electrotm.org', sni: 'electrotm.org', ir: true },
  { id: 'radar', name: 'Radar (ایران)', url: 'https://10.202.10.10/dns-query', dot: 'radar.game', sni: 'radar.game', ir: true },
  { id: 'online403', name: '403.online (ایران)', url: 'https://10.202.10.202/dns-query', dot: '403.online', sni: '403.online', ir: true },
  { id: 'begzar', name: 'Begzar (ایران)', url: 'https://185.55.226.26/dns-query', dot: 'begzar.ir', sni: 'begzar.ir', ir: true },
  { id: 'alidns', name: 'AliDNS', url: 'https://223.5.5.5/dns-query', dot: 'dns.alidns.com', sni: 'dns.alidns.com' },
  { id: 'yandex', name: 'Yandex', url: 'https://77.88.8.8/dns-query', dot: 'common.dot.dns.yandex.net', sni: 'common.dot.dns.yandex.net' },
  { id: 'cloudflare', name: 'Cloudflare', url: 'https://cloudflare-dns.com/dns-query', dot: 'one.one.one.one' },
  { id: 'google', name: 'Google', url: 'https://dns.google/dns-query', dot: 'dns.google' },
  { id: 'quad9', name: 'Quad9', url: 'https://dns.quad9.net/dns-query', dot: 'dns.quad9.net' },
  { id: 'adguard', name: 'AdGuard', url: 'https://dns.adguard-dns.com/dns-query', dot: 'dns.adguard-dns.com' },
  { id: 'mullvad', name: 'Mullvad', url: 'https://dns.mullvad.net/dns-query', dot: 'dns.mullvad.net' },
  { id: 'controld', name: 'ControlD', url: 'https://freedns.controld.com/p0', dot: 'p0.freedns.controld.com' },
];

function dohUpstream(env) {
  return String((env && env.DNS_UPSTREAM) || DEFAULT_DOH_UPSTREAM).trim();
}

/** Default upstream when no env/KV override exists (Shecan: reachable from Iran). */
const DEFAULT_DOH_UPSTREAM = 'https://178.22.122.100/dns-query';

const DNS_QUERY_NAME = 'cloudflare.com';

/** Query <name> A over the given DoH upstream and return the latency in ms. */
async function probeDnsUpstream(url, name) {
  const started = Date.now();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const answer = await fetch(
      url + (url.includes('?') ? '&' : '?') + 'name=' + encodeURIComponent(name) + '&type=A',
      {
        headers: { accept: 'application/dns-json' },
        signal: controller.signal,
        cf: { cacheTtl: 0, cacheEverything: false },
      },
    );
    clearTimeout(timer);
    const ms = Date.now() - started;
    if (!answer.ok) return { ok: false, ms: ms, error: 'HTTP ' + answer.status };
    const text = await answer.text();
    let answers = [];
    try {
      const parsed = JSON.parse(text);
      if (Array.isArray(parsed.Answer)) {
        answers = parsed.Answer.slice(0, 3).map((a) => String(a.data)).filter(Boolean);
      }
    } catch (e) { /* upstream ignored the JSON content type */ }
    return { ok: true, ms: ms, answers: answers };
  } catch (e) {
    return { ok: false, ms: Date.now() - started, error: e && e.message ? e.message : String(e) };
  }
}

/** Resolve a hostname through the configured DoH upstream (used to sanity-check DoT hosts). */
async function resolveHost(host, env) {
  const name = String(host || '').trim();
  if (!/^[a-z0-9.-]+$/i.test(name)) return { ok: false, error: 'invalid hostname' };
  const upstream = dohUpstream(env || {});
  const started = Date.now();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const answer = await fetch(
      upstream + (upstream.includes('?') ? '&' : '?') + 'name=' + encodeURIComponent(name) + '&type=A',
      { headers: { accept: 'application/dns-json' }, signal: controller.signal },
    );
    clearTimeout(timer);
    if (!answer.ok) return { ok: false, error: 'HTTP ' + answer.status };
    const parsed = await answer.json();
    const answers = Array.isArray(parsed.Answer) ? parsed.Answer.map((a) => String(a.data)) : [];
    if (!answers.length) return { ok: false, error: 'no answer', ms: Date.now() - started };
    return { ok: true, ms: Date.now() - started, answers: answers.slice(0, 4), dot: name };
  } catch (e) {
    return { ok: false, error: e && e.message ? e.message : String(e) };
  }
}

/** Only https URLs to public hosts may override the resolver (no SSRF into internal nets). */
function safeUpstreamOverride(value) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  try {
    const parsed = new URL(raw);
    if (parsed.protocol !== 'https:') return null;
    if (isIpLiteral(parsed.hostname)) return null;
    return parsed.toString();
  } catch (e) {
    return null;
  }
}

async function handleDnsQuery(request, env) {
  const url = new URL(request.url);
  const upstream = safeUpstreamOverride(url.searchParams.get('u')) || dohUpstream(env);
  const cors = {
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET, POST, OPTIONS',
    'access-control-allow-headers': '*',
  };
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });

  try {
    if (request.method === 'GET') {
      const target = upstream + (upstream.includes('?') ? '&' : '?') + 'dns=' + encodeURIComponent(url.searchParams.get('dns') || '');
      const answer = await fetch(target, {
        headers: { accept: 'application/dns-message' },
        cf: { cacheTtl: 60, cacheEverything: true },
      });
      return new Response(answer.body, {
        status: answer.status,
        headers: Object.assign(
          { 'content-type': 'application/dns-message', 'cache-control': 'max-age=60' },
          cors,
        ),
      });
    }
    if (request.method === 'POST') {
      const body = await request.arrayBuffer();
      const answer = await fetch(upstream, {
        method: 'POST',
        headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' },
        body: body,
      });
      return new Response(answer.body, {
        status: answer.status,
        headers: Object.assign(
          { 'content-type': 'application/dns-message', 'cache-control': 'no-store' },
          cors,
        ),
      });
    }
    return new Response('Method Not Allowed', { status: 405, headers: cors });
  } catch (e) {
    return new Response('DNS upstream error: ' + (e && e.message ? e.message : e), {
      status: 502,
      headers: cors,
    });
  }
}

/* ------------------------------------------------------------------ */
/* clean-IP scanner support                                            */
/* ------------------------------------------------------------------ */

/** Cloudflare (and a few CDN) ranges that are useful as fronting targets. */
/**
 * Clean-IP library for Iranian networks. These are public Cloudflare anycast
 * edge addresses that Iranian ISPs route without throttling; every entry is
 * verified by the scanner before you use it.
 */
const COMMUNITY_IPS = (
  '3.33.186.0,3.134.222.0,3.145.231.0,3.149.118.0,3.151.20.0,5.10.214.158,5.10.215.17,5.10.246.126,5.10.246.199,5.175.141.25,5.175.141.170,8.34.146.208,8.39.125.182,8.39.204.127,8.39.214.238,8.44.0.49,15.197.167.0,18.159.105.97,18.184.27.249,18.184.55.249,18.192.93.64,18.193.131.26,18.196.70.197,18.197.218.69,18.226.130.0,18.227.168.0,27.50.48.147,35.157.26.0,45.8.211.3,45.85.119.87,45.85.119.207,45.95.241.94,45.95.241.150,45.95.241.156,45.130.125.4,45.130.125.10,45.130.125.21,45.130.125.72,45.130.125.74,45.130.125.78,45.130.125.105,45.130.125.113,45.130.125.142,45.130.125.143,45.130.125.150,45.130.125.201,45.130.125.208,45.130.125.213,45.130.125.215,45.130.125.221,45.130.125.242,45.131.4.165,45.142.120.54,63.176.8.0,64.68.192.77,77.232.140.134,77.232.140.234,89.116.46.198,94.159.111.170,103.21.244.252,103.31.79.231,103.137.248.227,103.160.204.34,104.16.0.79,104.16.1.195,104.16.4.103,104.16.6.65,104.16.6.213,104.16.8.3,104.16.8.158,104.16.12.213,104.16.12.217,104.16.17.254,104.16.18.124,104.16.19.75,104.16.21.76,104.16.21.147,104.16.25.72,104.16.25.169,104.16.26.52,104.16.29.8,104.16.29.59,104.16.30.219,104.16.32.142,104.16.33.15,104.16.34.135,104.16.35.74,104.16.40.98,104.16.41.3,104.16.45.69,104.16.45.222,104.16.48.21,104.16.55.193,104.16.58.95,104.16.58.152,104.16.62.237,104.16.66.36,104.16.67.52,104.16.68.159,104.16.72.105,104.16.72.139,104.16.72.162,104.16.72.175,104.16.72.251,104.16.73.106,104.16.75.62,104.16.76.151,104.16.81.57,104.16.84.139,104.16.85.45,104.16.85.66,104.16.85.118,104.16.86.1,104.16.88.13,104.16.99.97,104.16.102.151,104.16.103.153,104.16.106.32,104.16.109.73,104.16.109.123,104.16.111.154,104.16.117.74,104.16.117.236,104.16.118.195,104.16.119.47,104.16.119.172,104.16.124.237,104.16.125.15,104.16.126.179,104.16.133.39,104.16.136.68,104.16.137.26,104.16.142.161,104.16.144.192,104.16.146.233,104.16.150.134,104.16.153.152,104.16.154.164,104.16.155.240,104.16.160.25,104.16.166.8,104.16.167.65,104.16.167.201,104.16.169.7,104.16.169.210,104.16.174.177,104.16.174.219,104.16.174.226,104.16.174.252,104.16.174.254,104.16.177.19,104.16.178.142,104.16.183.101,104.16.185.169,104.16.185.215,104.16.186.250,104.16.188.123,104.16.195.129,104.16.200.146,104.16.200.209,104.16.202.194,104.16.203.41,104.16.205.204,104.16.206.18,104.16.206.227,104.16.208.61,104.16.209.28,104.16.209.198,104.16.210.247,104.16.214.69,104.16.217.227,104.16.219.230,104.16.221.169,104.16.222.39,104.16.225.21,104.16.225.113,104.16.226.215,104.16.226.236,104.16.230.42,104.16.231.25,104.16.232.193,104.16.234.250,104.16.238.175,104.16.238.188,104.16.238.246,104.16.242.124,104.16.246.91,104.16.246.219,104.16.247.196,104.16.250.250,104.16.252.189,104.16.254.25,104.17.8.27,104.17.8.199,104.17.10.103,104.17.11.146,104.17.14.132,104.17.16.41,104.17.19.207,104.17.20.55,104.17.20.165,104.17.29.102,104.17.29.132,104.17.32.54,104.17.35.109,104.17.39.120,104.17.42.10,104.17.44.130,104.17.47.58,104.17.48.20,104.17.50.198,104.17.53.130,104.17.59.4,104.17.61.237,104.17.62.222,104.17.63.200,104.17.64.100,104.17.65.196,104.17.67.170,104.17.68.185,104.17.68.240,104.17.71.74,104.17.72.206,104.17.77.188,104.17.80.176,104.17.88.212,104.17.98.110,104.17.100.35,104.17.100.130,104.17.101.35,104.17.102.103,104.17.107.119,104.17.116.0,104.17.118.129,104.17.120.37,104.17.121.10,104.17.121.12,104.17.121.19,104.17.121.31,104.17.121.70,104.17.121.97,104.17.121.206,104.17.121.208,104.17.121.228,104.17.121.231,104.17.122.42,104.17.122.212,104.17.123.99,104.17.124.173,104.17.125.27,104.17.126.186,104.17.127.75,104.17.128.87,104.17.129.158,104.17.130.174,104.17.130.182,104.17.130.240,104.17.131.141,104.17.131.211,104.17.133.25,104.17.135.110,104.17.137.90,104.17.140.138,104.17.143.170,104.17.144.231,104.17.145.43,104.17.150.61,104.17.154.62,104.17.161.58,104.17.163.84,104.17.164.52,104.17.168.232,104.17.170.119,104.17.175.36,104.17.177.55,104.17.177.111,104.17.177.135,104.17.177.224,104.17.178.196,104.17.179.9,104.17.179.133,104.17.182.97,104.17.192.89,104.17.196.201,104.17.201.182,104.17.203.240,104.17.205.171,104.17.208.18,104.17.213.11,104.17.214.22,104.17.216.63,104.17.217.253,104.17.223.228,104.17.225.199,104.17.225.214,104.17.229.103,104.17.231.69,104.17.231.139,104.17.231.226,104.17.232.36,104.17.232.114,104.17.234.197,104.17.244.23,104.17.244.253,104.17.246.243,104.17.250.70,104.17.250.104,104.18.3.4,104.18.3.97,104.18.4.67,104.18.5.219,104.18.7.66,104.18.8.33,104.18.10.0,104.18.10.128,104.18.11.0,104.18.15.211,104.18.18.17,104.18.22.45,104.18.22.119,104.18.22.254,104.18.25.230,104.18.27.8,104.18.28.32,104.18.28.234,104.18.32.1,104.18.32.35,104.18.32.47,104.18.34.232,104.18.35.76,104.18.35.161,104.18.40.89,104.18.45.245,104.18.49.39,104.18.53.172,104.18.60.24,104.18.60.169,104.18.62.210,104.18.62.244,104.18.63.223,104.18.66.34,104.18.68.3,104.18.69.227,104.18.71.193,104.18.72.78,104.18.72.191,104.18.73.152,104.18.74.159,104.18.77.32,104.18.77.190,104.18.80.206,104.18.83.84,104.18.85.239,104.18.87.139,104.18.90.58,104.18.94.25,104.18.94.47,104.18.94.58,104.18.94.65,104.18.94.92,104.18.94.125,104.18.94.137,104.18.94.138,104.18.94.146,104.18.94.176,104.18.97.178,104.18.119.56,104.18.123.203,104.18.126.19,104.18.136.158,104.18.139.192,104.18.142.23,104.18.142.253,104.18.144.33,104.18.146.150,104.18.152.94,104.18.152.119,104.18.153.188,104.18.154.96,104.18.158.155,104.18.162.104,104.18.162.227,104.18.166.201,104.18.174.242,104.18.175.9,104.18.175.54,104.18.175.177,104.18.178.10,104.18.180.227,104.18.184.82,104.18.188.3,104.18.190.52,104.18.191.123,104.18.191.143,104.18.193.222,104.18.197.115,104.18.198.141,104.18.200.76,104.18.200.97,104.18.202.99,104.18.203.92,104.18.207.81,104.18.209.90,104.18.213.35,104.18.220.84,104.18.220.113,104.18.231.91,104.18.231.231,104.18.238.121,104.18.239.152,104.18.239.162,104.18.242.113,104.18.244.93,104.18.245.234,104.18.248.67,104.18.249.94,104.19.1.1,104.19.7.158,104.19.7.250,104.19.9.3,104.19.9.203,104.19.15.150,104.19.15.225,104.19.19.88,104.19.20.140,104.19.22.164,104.19.22.251,104.19.26.136,104.19.26.229,104.19.28.32,104.19.30.121,104.19.33.207,104.19.36.84,104.19.42.130,104.19.45.224,104.19.48.145,104.19.48.188,104.19.48.249,104.19.52.56,104.19.52.241,104.19.54.50,104.19.54.238,104.19.55.158,104.19.64.41,104.19.66.72,104.19.71.16,104.19.74.136,104.19.75.86,104.19.75.119,104.19.77.44,104.19.79.151,104.19.84.69,104.19.84.192,104.19.87.119,104.19.89.109,104.19.90.42,104.19.91.182,104.19.93.221,104.19.96.59,104.19.96.167,104.19.99.238,104.19.102.26,104.19.102.163,104.19.103.19,104.19.105.216,104.19.107.2,104.19.108.38,104.19.109.207,104.19.111.121,104.19.114.245,104.19.115.133,104.19.119.226,104.19.120.26,104.19.120.27,104.19.124.90,104.19.127.93,104.19.130.210,104.19.131.246,104.19.132.178,104.19.133.89,104.19.150.130,104.19.154.46,104.19.155.206,104.19.158.161,104.19.160.142,104.19.164.103,104.19.168.192,104.19.169.61,104.19.171.217,104.19.174.31,104.19.175.116,104.19.180.169,104.19.184.2,104.19.185.8,104.19.186.216,104.19.196.48,104.19.205.118,104.19.208.108,104.19.209.252,104.19.213.171,104.19.215.231,104.19.220.225,104.19.220.237,104.19.228.247,104.19.232.212,104.19.235.64,104.19.237.239,104.19.238.44,104.19.238.233,104.19.240.189,104.19.240.202,104.19.242.53,104.19.244.250,104.19.245.193,104.19.250.20,104.19.250.128,104.19.253.144,104.19.254.245,104.19.255.51,104.20.2.1,104.20.7.154,104.20.9.79,104.20.11.160,104.20.15.82,104.20.19.160,104.20.19.253,104.20.21.92,104.20.21.111,104.20.23.175,104.20.24.38,104.20.28.141,104.20.29.199,104.20.34.76,104.20.35.32,104.20.36.28,104.20.39.21,104.20.42.140,104.20.44.53,104.20.44.170,104.20.47.122,104.20.50.184,104.20.51.178,104.20.62.55,104.20.62.190,104.20.66.92,104.20.75.10,104.20.77.131,104.20.149.108,104.20.151.9,104.20.156.248,104.20.157.73,104.20.181.114,104.20.224.95,104.20.251.248,104.21.3.21,104.21.3.125,104.21.5.205,104.21.7.233,104.21.8.198,104.21.9.108,104.21.11.206,104.21.12.23,104.21.15.216,104.21.16.204,104.21.19.124,104.21.20.203,104.21.21.29,104.21.23.79,104.21.24.116,104.21.25.67,104.21.33.129,104.21.40.63,104.21.42.74,104.21.44.121,104.21.48.20,104.21.48.242,104.21.50.119,104.21.50.157,104.21.51.16,104.21.51.208,104.21.55.49,104.21.59.164,104.21.65.141,104.21.65.250,104.21.69.130,104.21.72.94,104.21.75.91,104.21.78.216,104.21.79.249,104.21.83.92,104.21.84.200,104.21.92.225,104.21.93.98,104.21.93.170,104.21.94.81,104.21.95.137,104.21.102.18,104.21.102.127,104.21.102.207,104.21.104.51,104.21.109.193,104.21.110.177,104.21.111.204,104.21.112.26,104.21.112.133,104.21.117.110,104.21.119.39,104.21.123.57,104.21.194.41,104.21.195.90,104.21.196.27,104.21.198.255,104.21.200.123,104.21.201.24,104.21.206.211,104.21.209.83,104.21.209.200,104.21.211.74,104.21.217.41,104.21.217.255,104.21.218.3,104.21.218.14,104.21.220.157,104.21.221.37,104.21.222.39,104.21.225.94,104.21.226.162,104.21.228.32,104.21.229.49,104.21.230.183,104.21.231.123,104.21.235.44,104.22.4.136,104.22.11.129,104.22.13.192,104.22.38.61,104.22.51.45,104.22.51.241,104.22.60.6,104.22.75.105,104.23.99.219,104.23.113.202,104.23.123.239,104.24.1.69,104.24.9.148,104.24.12.68,104.24.16.52,104.24.16.182,104.24.17.46,104.24.21.190,104.24.22.168,104.24.24.243,104.24.27.9,104.24.29.170,104.24.32.13,104.24.36.50,104.24.36.163,104.24.38.86,104.24.42.48,104.24.44.23,104.24.48.194,104.24.49.84,104.24.49.220,104.24.50.26,104.24.59.223,104.24.60.160,104.24.62.187,104.24.78.181,104.24.78.223,104.24.82.19,104.24.82.86,104.24.83.105,104.24.84.148,104.24.87.3,104.24.91.52,104.24.95.9,104.24.133.212,104.24.135.192,104.24.137.130,104.24.144.253,104.24.145.43,104.24.145.185,104.24.150.165,104.24.150.236,104.24.153.112,104.24.154.5,104.24.155.198,104.24.157.163,104.24.160.221,104.24.161.123,104.24.163.98,104.24.166.63,104.24.166.114,104.24.168.182,104.24.170.103,104.24.172.243,104.24.178.155,104.24.179.140,104.24.183.89,104.24.184.76,104.24.184.203,104.24.185.137,104.24.187.161,104.24.187.238,104.24.189.34,104.24.191.249,104.24.194.105,104.24.194.247,104.24.196.82,104.24.196.120,104.24.197.165,104.24.198.236,104.24.199.168,104.24.201.170,104.24.201.229,104.24.201.244,104.24.203.12,104.24.203.130,104.24.203.189,104.24.211.86,104.24.211.129,104.24.212.2,104.24.214.181,104.24.214.227,104.24.215.239,104.24.224.196,104.24.225.78,104.24.233.132,104.24.233.142,104.24.234.30,104.24.245.71,104.24.249.73,104.24.253.134,104.24.253.223,104.24.254.74,104.24.255.11,104.24.255.57,104.24.255.191,104.25.0.16,104.25.4.95,104.25.6.177,104.25.7.150,104.25.13.217,104.25.25.244,104.25.28.222,104.25.29.7,104.25.31.66,104.25.32.139,104.25.33.15,104.25.35.227,104.25.36.48,104.25.38.47,104.25.39.36,104.25.44.151,104.25.47.106,104.25.49.140,104.25.50.192,104.25.55.202,104.25.57.197,104.25.58.138,104.25.62.57,104.25.63.11,104.25.63.151,104.25.64.147,104.25.64.185,104.25.65.253,104.25.75.134,104.25.90.84,104.25.91.75,104.25.92.198,104.25.93.249,104.25.95.123,104.25.95.182,104.25.96.112,104.25.99.169,104.25.105.122,104.25.107.158,104.25.108.25,104.25.110.122,104.25.112.182,104.25.115.102,104.25.123.166,104.25.123.173,104.25.125.241,104.25.128.220,104.25.131.97,104.25.134.44,104.25.134.200,104.25.135.71,104.25.136.51,104.25.138.38,104.25.142.76,104.25.153.166,104.25.154.239,104.25.155.53,104.25.156.44,104.25.162.190,104.25.163.102,104.25.163.198,104.25.166.53,104.25.171.254,104.25.179.159,104.25.186.159,104.25.187.2,104.25.193.8,104.25.197.21,104.25.201.113,104.25.202.95,104.25.203.232,104.25.204.117,104.25.204.143,104.25.206.98,104.25.209.14,104.25.217.211,104.25.219.210,104.25.225.41,104.25.226.116,104.25.231.50,104.25.232.45,104.25.235.38,104.25.242.215,104.25.244.30,104.25.245.206,104.25.255.254,104.26.1.237,104.26.12.210,104.26.13.231,104.26.192.144,104.27.1.129,104.27.1.133,104.27.2.57,104.27.3.155,104.27.5.173,104.27.8.254,104.27.9.232,104.27.11.91,104.27.11.183,104.27.16.154,104.27.23.251,104.27.24.1,104.27.27.171,104.27.28.31,104.27.28.95,104.27.30.22,104.27.34.44,104.27.35.232,104.27.36.209,104.27.38.85,104.27.41.55,104.27.44.196,104.27.50.52,104.27.62.140,104.27.62.210,104.27.62.243,104.27.65.143,104.27.68.184,104.27.69.136,104.27.72.231,104.27.77.155,104.27.89.1,104.27.90.67,104.27.90.240,104.27.91.219,104.27.91.245,104.27.93.201,104.27.94.121,104.27.96.222,104.27.97.42,104.27.97.174,104.27.98.25,104.27.101.40,104.27.106.9,104.27.107.62,104.27.108.214,104.27.112.141,104.27.115.85,104.27.116.217,104.27.117.109,104.27.119.141,104.27.122.56,104.27.122.196,104.27.195.99,104.27.196.92,104.27.197.62,104.27.200.212,104.27.200.255,104.27.203.169,104.27.204.103,104.27.204.123,104.29.105.26,104.31.16.65,104.31.16.136,104.31.16.181,104.31.16.196,104.31.16.213,104.129.166.19,104.129.167.55,104.254.140.203,108.162.192.67,108.162.192.182,108.162.192.206,108.162.192.252,108.162.193.0,108.162.193.38,108.162.193.77,108.162.193.95,108.162.193.111,108.162.193.157,108.162.193.176,108.162.193.193,108.162.194.237,108.162.195.196,108.162.196.40,108.162.196.200,108.162.196.215,108.162.198.203,108.165.216.238,109.122.198.64,109.122.198.127,116.202.132.205,138.197.183.219,138.201.170.108,141.11.202.7,141.101.114.7,145.223.100.111,150.241.123.57,154.83.2.50,154.92.9.160,154.198.173.9,155.46.167.212,155.46.213.135,156.243.246.32,159.242.242.228,159.246.55.180,160.153.0.179,162.158.22.223,162.159.0.1,162.159.1.94,162.159.4.34,162.159.11.33,162.159.23.190,162.159.25.11,162.159.32.35,162.159.32.73,162.159.38.61,162.159.39.76,162.159.49.160,162.159.62.69,162.159.81.233,162.159.82.236,162.159.90.121,162.159.94.202,162.159.136.213,162.159.141.203,162.159.152.72,162.159.160.47,162.159.196.209,162.159.207.97,162.159.229.163,162.159.229.214,162.159.231.231,162.159.233.245,162.159.243.248,162.159.246.59,162.159.250.246,162.159.255.15,162.251.82.187,166.1.36.83,167.68.5.193,167.68.5.254,167.68.42.219,167.71.45.93,168.100.6.118,168.100.6.249,168.100.6.250,172.64.33.124,172.64.35.226,172.64.41.221,172.64.43.172,172.64.48.93,172.64.52.47,172.64.68.185,172.64.72.217,172.64.75.68,172.64.75.245,172.64.76.137,172.64.78.154,172.64.78.193,172.64.80.85,172.64.81.208,172.64.84.42,172.64.88.53,172.64.89.56,172.64.91.247,172.64.106.208,172.64.148.183,172.64.149.185,172.64.150.2,172.64.151.44,172.64.155.24,172.64.155.71,172.64.157.244,172.64.158.29,172.64.162.23,172.64.185.247,172.64.190.72,172.64.235.140,172.65.9.134,172.65.12.104,172.65.13.222,172.65.14.217,172.65.20.231,172.65.29.31,172.65.57.200,172.65.61.61,172.65.64.243,172.65.92.30,172.65.103.23,172.65.108.158,172.65.118.5,172.65.119.199,172.65.121.98,172.65.121.243,172.65.130.220,172.65.137.125,172.65.145.115,172.65.166.81,172.65.174.3,172.65.176.48,172.65.180.194,172.65.181.102,172.65.188.62,172.65.190.103,172.65.202.130,172.65.223.246,172.65.229.187,172.65.237.80,172.65.243.12,172.66.0.127,172.66.0.216,172.66.3.193,172.66.40.20,172.66.42.237,172.66.44.189,172.66.44.216,172.66.45.59,172.66.46.129,172.66.47.74,172.66.138.94,172.66.142.92,172.66.145.69,172.66.145.154,172.66.149.177,172.66.161.73,172.66.162.223,172.66.167.29,172.66.169.179,172.66.170.137,172.66.171.239,172.66.176.233,172.66.177.246,172.66.196.47,172.66.204.75,172.66.212.215,172.66.213.38,172.66.217.11,172.66.217.57,172.66.217.237,172.67.26.173,172.67.32.82,172.67.64.65,172.67.65.108,172.67.68.213,172.67.73.248,172.67.77.246,172.67.81.110,172.67.82.117,172.67.82.219,172.67.84.174,172.67.85.18,172.67.85.213,172.67.90.82,172.67.91.1,172.67.91.87,172.67.97.134,172.67.105.91,172.67.113.56,172.67.113.116,172.67.123.23,172.67.123.252,172.67.125.209,172.67.128.147,172.67.132.112,172.67.133.37,172.67.135.177,172.67.137.141,172.67.140.136,172.67.141.59,172.67.142.53,172.67.142.201,172.67.144.174,172.67.147.25,172.67.147.237,172.67.149.93,172.67.159.153,172.67.160.50,172.67.162.28,172.67.165.8,172.67.173.109,172.67.177.134,172.67.179.119,172.67.184.41,172.67.186.37,172.67.186.42,172.67.186.149,172.67.187.20,172.67.187.137,172.67.188.112,172.67.188.188,172.67.188.203,172.67.190.227,172.67.193.195,172.67.197.170,172.67.200.48,172.67.203.95,172.67.211.167,172.67.213.109,172.67.213.229,172.67.214.41,172.67.220.179,172.67.223.97,172.67.225.117,172.67.226.65,172.67.229.179,172.67.233.105,172.67.235.81,172.67.236.206,172.67.251.49,172.67.251.233,172.67.252.19,172.67.254.250,172.68.62.168,172.68.118.177,172.70.184.144,172.71.94.40,172.71.129.222,172.71.160.32,172.71.165.178,178.250.187.110,185.7.240.137,185.148.105.52,185.162.228.138,185.193.29.151,185.193.30.76,185.193.30.94,185.193.30.191,188.95.12.119,188.114.96.6,188.114.97.6,188.114.98.0,188.114.98.219,188.114.99.0,188.114.99.29,190.93.244.229,190.93.246.54,190.93.247.176,191.101.251.190,192.65.217.32,193.9.49.31,194.36.55.99,194.152.44.103,195.85.23.12,195.85.23.175,195.85.23.225,195.85.23.236,195.85.59.47,198.41.196.44,198.41.197.10,198.41.199.1,198.41.200.203,198.41.202.5,198.41.203.182,198.41.204.132,198.41.206.71,198.41.209.26,198.41.209.180,198.41.214.192,198.41.215.140,198.41.215.203,198.41.216.87,198.41.217.98,198.41.217.242,199.33.231.30,199.33.233.153,199.33.233.225,199.59.243.225,199.181.197.1,199.181.197.53,199.181.197.56,199.181.197.67,199.181.197.71,199.181.197.73,199.181.197.77,199.181.197.90,199.181.197.92,199.181.197.101,199.181.197.103,199.181.197.108,199.181.197.109,199.181.197.111,199.181.197.119,199.181.197.120,199.181.197.122,199.181.197.123,199.181.197.126,199.181.197.127,199.181.197.131,199.181.197.132,199.181.197.133,199.181.197.135,199.181.197.140,199.181.197.145,199.181.197.146,199.181.197.147,199.181.197.149,199.181.197.151,199.181.197.152,199.181.197.153,199.181.197.155,199.181.197.158,199.181.197.172,199.181.197.174,199.181.197.179,199.181.197.188,199.181.197.189,199.181.197.195,199.181.197.203,199.181.197.205,199.181.197.211,199.181.197.215,199.181.197.218,199.181.197.225,199.181.197.229,199.181.197.231,199.181.197.233,199.181.197.234,199.181.197.239,199.181.197.243,199.181.197.246,199.181.197.247,199.181.197.248,199.181.197.250,199.181.197.252,199.181.197.253,199.181.197.254,199.181.197.255,203.32.121.53,209.46.30.18'
).split(',');

const IR_CLEAN_IPS = [
  '104.16.0.1', '104.16.132.229', '104.17.0.1', '104.17.148.22', '104.18.0.1',
  '104.19.0.1', '104.20.0.1', '104.21.0.1', '104.22.0.1', '104.24.0.1',
  '104.25.0.1', '104.26.0.1', '104.27.0.1', '104.28.0.1', '104.31.0.1',
  '172.64.0.1', '172.64.80.1', '172.65.0.1', '172.66.0.1', '172.67.0.1',
  '172.68.0.1', '172.69.0.1', '172.70.0.1', '172.71.0.1',
  '162.158.0.1', '162.158.80.1', '162.159.0.1', '162.159.128.1', '162.159.192.1',
  '141.101.64.1', '141.101.90.1', '108.162.192.1', '108.162.220.1',
  '188.114.96.1', '190.93.240.1', '197.234.240.1', '198.41.128.1',
  '103.21.244.1', '103.22.200.1', '103.31.4.1', '131.0.72.1', '173.245.48.1',
];

const SCAN_RANGES = [
  '104.16.0.0/13', '104.24.0.0/14', '172.64.0.0/13', '162.158.0.0/15',
  '131.0.72.0/22', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22',
  '141.101.64.0/18', '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20',
  '197.234.240.0/22', '198.41.128.0/17', '173.245.48.0/20', '162.159.192.0/24',
  '162.159.0.0/16', '199.27.128.0/21',
  '92.223.0.0/16', '89.187.163.0/24',
];

function ipToLong(ip) {
  const parts = String(ip).split('.').map((p) => Number(p));
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return null;
  return ((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3];
}

function longToIp(value) {
  const v = value >>> 0;
  return [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255].join('.');
}

/**
 * Pick `count` addresses spread over a CIDR block. Each slot gets a random
 * offset inside its slice (when `random` is true) so repeated scans of the same
 * range keep discovering new hosts instead of re-testing the same 4 IPs.
 */
function sampleSubnet6(cidr, count = 4, random = false) {
  const parts = String(cidr).split('/');
  const hextets = parseV6Hextets(parts[0]);
  if (!hextets) return [];
  const prefix = parts.length > 1 ? Number(parts[1]) : 128;
  if (!Number.isInteger(prefix) || prefix < 16 || prefix > 128) return [];
  const fixed = Math.floor(prefix / 16);
  const rem = prefix % 16;
  const out = [];
  const seen = new Set();
  const want = Math.max(1, count);
  for (let i = 0; i < want * 3 && out.length < want; i++) {
    const copy = hextets.slice();
    for (let h = fixed + (rem ? 1 : 0); h < 8; h++) copy[h] = random ? Math.floor(Math.random() * 0x10000).toString(16) : (i + 1).toString(16);
    if (rem) {
      const keepMask = 0xffff ^ ((1 << (16 - rem)) - 1);
      const partial = (parseInt(hextets[fixed] || '0', 16) || 0) & keepMask;
      const randPart = random ? Math.floor(Math.random() * (1 << (16 - rem))) : (i + 1);
      copy[fixed] = (partial | randPart).toString(16);
    }
    const ip = copy.join(':');
    if (!seen.has(ip)) { seen.add(ip); out.push(ip); }
  }
  return out;
}

function sampleSubnet(cidr, count = 8, random = false) {
  if (String(cidr).includes(':')) return sampleSubnet6(cidr, count, random);
  const parts = String(cidr).split('/');
  const base = ipToLong(parts[0]);
  if (base === null) return [];
  const prefix = parts.length > 1 ? Number(parts[1]) : 32;
  if (!Number.isInteger(prefix) || prefix < 8 || prefix > 32) return [];
  if (prefix === 32) return [longToIp(base)];
  const hostBits = 32 - prefix;
  const netBase = (base >>> 0) - ((base >>> 0) % Math.pow(2, hostBits));
  const total = Math.pow(2, Math.min(hostBits, 20));
  const want = Math.max(1, Math.min(count, total - 1));
  const slice = total / want;
  const out = [];
  const seen = new Set();
  for (let i = 0; i < want; i++) {
    const jitter = random ? Math.floor(Math.random() * slice) : Math.floor(slice / 2);
    let offset = Math.floor(i * slice + jitter);
    if (offset < 1) offset = 1;
    if (offset > total - 1) offset = total - 1;
    if ((offset & 255) === 0 && offset + 1 <= total - 1) offset += 1; // skip x.x.x.0
    if ((offset & 255) === 255 && offset - 1 >= 1) offset -= 1; // skip x.x.x.255
    const ip = longToIp((netBase + offset) >>> 0);
    if (!seen.has(ip)) { seen.add(ip); out.push(ip); }
  }
  return out;
}

/** Expand a comma/space separated list of IPs and CIDR ranges into candidate IPs. */
function expandRanges(text, perRange = 8, random = true) {
  const out = [];
  const seen = new Set();
  splitCsv(text).forEach((item) => {
    const list = item.includes('/') ? sampleSubnet(item, perRange, random) : isIpLiteral(item) ? [item] : [];
    list.forEach((ip) => { if (!seen.has(ip)) { seen.add(ip); out.push(ip); } });
  });
  return out;
}

function scanTargets(env) {
  const out = [];
  const seen = new Set();
  const push = (ip) => {
    if (!ip || seen.has(ip)) return;
    seen.add(ip);
    out.push(ip);
  };
  splitCsv(env.CF_IPS).forEach(push);
  IR_CLEAN_IPS.forEach(push);
  expandRanges(env.SCAN_IPS, 8, true).forEach(push);
  for (const range of scanRanges(env)) sampleSubnet(range, range.includes(':') ? 3 : 6, true).forEach(push);
  return out;
}

/** CIDR ranges the scanner walks: SCAN_RANGES env (comma separated) or the built-in Cloudflare list. */
function scanRanges(env) {
  const custom = splitCsv(env && env.SCAN_RANGES).filter((r) => r.includes('/') && sampleSubnet(r, 1).length);
  return custom.length ? custom : SCAN_RANGES.concat(SCAN_RANGES6);
}

function ipStringInCidr(ip, cidr) {
  const value = ipToLong(ip);
  const parts = String(cidr || '').split('/');
  const base = ipToLong(parts[0]);
  const prefix = Number(parts[1]);
  if (value === null || base === null || !Number.isInteger(prefix) || prefix < 0 || prefix > 32) return false;
  if (prefix === 0) return true;
  const mask = (0xffffffff << (32 - prefix)) >>> 0;
  return (value & mask) === (base & mask);
}

function rangeForIp(ip, env) {
  return scanRanges(env).find((range) => ipStringInCidr(ip, range)) || '';
}

/**
 * Server-side latency probe. Bare `https://<ip>/cdn-cgi/trace` answers 403
 * on most edges (no hostname), so we ask the edge for *this panel's* /health
 * with `resolveOverride` — that proves the IP can front the worker domain.
 * Falls back to the trace endpoint for non-Cloudflare CDNs.
 */
async function probeIp(ip, timeoutMs = 4000, host, env = {}) {
  const started = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const target = isCloudflareIp(ip) && host
    ? 'https://' + host + '/cdn-cgi/trace'
    : 'https://' + formatAddr(ip) + '/cdn-cgi/trace';
  try {
    const answer = await fetch(target, {
      signal: controller.signal,
      headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION },
      cf: Object.assign({ cacheTtl: 0, cacheEverything: false }, isCloudflareIp(ip) && host ? { resolveOverride: ip } : {}),
    });
    const ms = Date.now() - started;
    clearTimeout(timer);
    const text = await answer.text().catch(() => '');
    const colo = (text.match(/^colo=(\S+)/m) || [])[1] || '';
    const location = locationFromColo(colo);
    const countryCode = countryCodeFromFlag(location.flag);
    // Cloudflare edges reply 200 for trace; 403 with the header "server: cloudflare" still proves reachability.
    const cfServer = (answer.headers.get('server') || '').toLowerCase().includes('cloudflare');
    const ok = answer.ok || (cfServer && answer.status < 500);
    return {
      ip: ip,
      ok: ok,
      ms: ms,
      status: answer.status,
      colo: colo,
      countryCode: countryCode,
      countryName: location.country,
      range: rangeForIp(ip, env),
      location: location,
      cf: cfServer,
    };
  } catch (e) {
    clearTimeout(timer);
    return { ip: ip, ok: false, ms: Date.now() - started, error: e && e.message ? e.message : String(e) };
  }
}

/* ------------------------------------------------------------------ */
/* panel styling — purple / black / white                              */
/* ------------------------------------------------------------------ */

function catLogo(size) {
  const s = size || 30;
  return '<svg viewBox="0 0 48 48" width="' + s + '" height="' + s + '" aria-hidden="true">' +
    '<defs><linearGradient id="catg" x1="0" y1="0" x2="1" y2="1">' +
    '<stop offset="0%" stop-color="#d946ef"/><stop offset="100%" stop-color="#7c3aed"/></linearGradient></defs>' +
    '<path d="M11 20 8.5 6.5c-.2-1 1-1.7 1.8-1.1L20 12.6c2.6-.7 5.4-.7 8 0l9.7-7.2c.8-.6 2 .1 1.8 1.1L37 20c1.9 2.6 3 5.8 3 9.2C40 38.7 32.8 45 24 45S8 38.7 8 29.2c0-3.4 1.1-6.6 3-9.2Z" fill="url(#catg)"/>' +
    '<ellipse cx="17.5" cy="28" rx="3.1" ry="3.6" fill="#0a0510"/><ellipse cx="30.5" cy="28" rx="3.1" ry="3.6" fill="#0a0510"/>' +
    '<path d="M24 34.5c-1.6 0-2.6 1.3-2.2 2.6.4 1.4 1.4 2.4 2.2 2.4s1.8-1 2.2-2.4c.4-1.3-.6-2.6-2.2-2.6Z" fill="#f4f4f5"/>' +
    '<path d="M38 12.5 34.5 20l6.8-1.6c1-.2 1.3-1.5.5-2.2l-2.4-2.1.9-3.1c.3-1.2-1.3-1.9-2-.8Z" fill="#fde047"/></svg>';
}

function css() {
  return [
    '*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}',
    ':root{',
    '--bg:#191330;--bg-soft:#221a44;--surface:rgba(255,255,255,.11);--surface-2:rgba(255,255,255,.17);',
    '--line:rgba(216,178,255,.5);--line-soft:rgba(255,255,255,.18);',
    '--text:#ffffff;--muted:#ddd6f3;--dim:#beb5d9;',
    '--accent:#c084fc;--accent-2:#a78bfa;--accent-3:#f472d0;--on-accent:#1b1030;',
    '--glow-a:rgba(192,132,252,.42);--glow-b:rgba(244,114,208,.32);',
    '--ok:#34d399;--warn:#fbbf24;--bad:#f87171;--radius:18px;',
    '}',
    /* theme picker: violet (default), oled, orchid, mono, light */
    'html[data-theme="violet"]{}',
    'html[data-theme="oled"]{--bg:#000000;--bg-soft:#050505;--surface:rgba(255,255,255,.035);--surface-2:rgba(255,255,255,.06);',
    '--line:rgba(139,92,246,.22);--line-soft:rgba(255,255,255,.07);--accent:#8b5cf6;--accent-2:#7c3aed;--accent-3:#a855f7;',
    '--glow-a:rgba(139,92,246,.16);--glow-b:rgba(124,58,237,.12)}',
    'html[data-theme="orchid"]{--bg:#0b0410;--bg-soft:#12061c;--surface:rgba(255,255,255,.05);--surface-2:rgba(255,255,255,.08);',
    '--line:rgba(236,72,153,.26);--line-soft:rgba(255,255,255,.09);--accent:#ec4899;--accent-2:#db2777;--accent-3:#d946ef;',
    '--glow-a:rgba(236,72,153,.20);--glow-b:rgba(217,70,239,.14)}',
    'html[data-theme="mono"]{--bg:#0b0b0f;--bg-soft:#111116;--surface:rgba(255,255,255,.05);--surface-2:rgba(255,255,255,.09);',
    '--line:rgba(255,255,255,.16);--line-soft:rgba(255,255,255,.10);--text:#f4f4f5;--muted:#a1a1aa;--dim:#71717a;',
    '--accent:#e5e7eb;--accent-2:#d4d4d8;--accent-3:#fafafa;--on-accent:#0b0b0f;',
    '--glow-a:rgba(255,255,255,.06);--glow-b:rgba(255,255,255,.04)}',
    'html[data-theme="light"]{',
    '--bg:#f6f3fc;--bg-soft:#ffffff;--surface:#ffffff;--surface-2:#f3eefc;',
    '--line:rgba(124,58,237,.22);--line-soft:rgba(20,10,40,.08);',
    '--text:#12061f;--muted:#5b5566;--dim:#8a8494;',
    '--accent:#7c3aed;--accent-2:#6d28d9;--accent-3:#c026d3;--on-accent:#fff;',
    '--glow-a:rgba(124,58,237,.12);--glow-b:rgba(192,38,211,.10)}',
    'html[data-theme="light"] .card{box-shadow:0 12px 34px rgba(76,29,149,.08)}',
    '.theme-menu{position:absolute;top:52px;inset-inline-end:12px;z-index:50;display:none;flex-direction:column;gap:4px;padding:8px;',
    'min-width:190px;background:var(--bg-soft);border:1px solid var(--line);border-radius:16px;box-shadow:0 22px 60px rgba(0,0,0,.45)}',
    '.theme-menu.show{display:flex}',
    '.theme-menu button{display:flex;align-items:center;gap:9px;background:none;border:0;color:var(--text);font:inherit;font-size:13px;',
    'padding:8px 10px;border-radius:10px;cursor:pointer;text-align:start}',
    '.theme-menu button:hover{background:var(--surface-2)}',
    '.theme-menu button.active{background:var(--surface-2);font-weight:700}',
    '.swatches{display:flex;gap:3px}',
    '.swatches i{width:12px;height:12px;border-radius:50%;display:block;border:1px solid rgba(255,255,255,.25)}',
    'body{font-family:"Vazirmatn",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);',
    'min-height:100vh;line-height:1.7;padding-bottom:44px;',
    'background-image:radial-gradient(900px 500px at 12% -8%,var(--glow-a),transparent 60%),radial-gradient(700px 420px at 96% 4%,var(--glow-b),transparent 62%),url("' + catWatermarkUri() + '");',
    'background-position:12% -8%,96% 4%,right -70px bottom -60px;',
    'background-size:auto,auto,min(46vw,440px);background-repeat:no-repeat}',
    'body[data-lang="en"]{direction:ltr}',
    'body[data-lang="fa"]{direction:rtl}',
    '.wrap{width:100%;max-width:1000px;margin:0 auto;padding:16px}',
    'a{color:var(--accent);text-decoration:none}',
    'header.top{position:sticky;top:0;z-index:30;backdrop-filter:blur(18px);background:color-mix(in srgb,var(--bg) 82%,transparent);border-bottom:1px solid var(--line-soft)}',
    '.top-inner{max-width:1000px;margin:0 auto;padding:10px 16px;display:flex;align-items:center;gap:10px}',
    '.brand{display:flex;align-items:center;gap:10px;font-weight:800;font-size:18px;letter-spacing:.2px}',
    '.brand .cat{display:flex;align-items:center;justify-content:center;width:42px;height:42px;border-radius:14px;background:linear-gradient(140deg,rgba(168,85,247,.28),rgba(124,58,237,.12));border:1px solid var(--line)}',
    '.brand small{display:block;font-weight:500;font-size:11.5px;color:var(--muted);letter-spacing:.3px}',
    '.spacer{flex:1}',
    '.icon-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:38px;min-width:38px;padding:0 10px;border-radius:12px;border:1px solid var(--line-soft);background:var(--surface);color:var(--text);font:inherit;font-size:12.5px;font-weight:600;cursor:pointer}',
    '.icon-btn:hover{border-color:var(--line);background:var(--surface-2)}',
    '.pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:11.5px;font-weight:600;border:1px solid var(--line);background:rgba(168,85,247,.12);color:var(--text)}',
    '.pill.ok{border-color:rgba(52,211,153,.35);background:rgba(52,211,153,.12);color:#6ee7b7}',
    '.pill.warn{border-color:rgba(251,191,36,.35);background:rgba(251,191,36,.12);color:#fcd34d}',
    '.card{background:var(--surface);border:1px solid var(--line-soft);border-radius:var(--radius);padding:18px;margin-bottom:14px;backdrop-filter:blur(12px);box-shadow:0 18px 50px rgba(10,5,20,.28)}',
    'html[data-theme="light"] .card{box-shadow:0 12px 34px rgba(76,29,149,.08)}',
    '.card.glow{border-color:var(--line);box-shadow:0 0 0 1px rgba(168,85,247,.08),0 24px 60px rgba(124,58,237,.18)}',
    'h1{font-size:22px;font-weight:800;margin-bottom:6px}',
    'h2{font-size:15px;font-weight:700;margin-bottom:10px;display:flex;align-items:center;gap:8px}',
    'h2 .dot{width:8px;height:8px;border-radius:50%;background:linear-gradient(120deg,var(--accent),var(--accent-3))}',
    'p{color:var(--muted);font-size:13.5px}',
    '.muted{color:var(--muted);font-size:12.5px}',
    '.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}',
    '.grid{display:grid;gap:10px}',
    '.grid.two{grid-template-columns:repeat(auto-fit,minmax(180px,1fr))}',
    '.grid.three{grid-template-columns:repeat(auto-fit,minmax(140px,1fr))}',
    '.stat{background:var(--surface);border:1px solid var(--line-soft);border-radius:14px;padding:12px}',
    '.stat .k{font-size:11px;color:var(--dim);text-transform:uppercase;letter-spacing:.6px}',
    '.stat .v{font-size:14px;font-weight:700;word-break:break-all;margin-top:2px}',
    'code,pre,.mono{font-family:ui-monospace,SFMono-Regular,"JetBrains Mono",Menlo,monospace}',
    'code{background:var(--surface-2);border:1px solid var(--line-soft);border-radius:8px;padding:2px 7px;font-size:12px;word-break:break-all}',
    'pre{background:var(--surface-2);border:1px solid var(--line-soft);border-radius:12px;padding:12px;font-size:12.5px;white-space:pre-wrap;word-break:break-all;max-height:240px;overflow:auto}',
    'button.btn{display:inline-flex;align-items:center;justify-content:center;gap:7px;background:linear-gradient(120deg,var(--accent-2),var(--accent),var(--accent-3));color:var(--on-accent);border:0;border-radius:12px;padding:11px 16px;font:inherit;font-size:13.5px;font-weight:700;cursor:pointer;box-shadow:0 10px 26px rgba(124,58,237,.28)}',
    'button.btn.ghost{background:var(--surface);color:var(--text);border:1px solid var(--line);box-shadow:none}',
    'button.btn.tiny{padding:7px 11px;font-size:12px;border-radius:10px}',
    'button.btn:disabled{opacity:.5;cursor:not-allowed;box-shadow:none}',
    'button.btn:not(:disabled):hover{filter:brightness(1.08)}',
    'input,select,textarea{width:100%;background:var(--surface-2);border:1px solid var(--line-soft);border-radius:12px;color:var(--text);padding:11px 12px;font:inherit;font-size:13px}',
    'input:focus,select:focus,textarea:focus{outline:none;border-color:var(--line)}',
    'label.field{display:block;margin-bottom:10px}',
    'label.field span{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}',
    '/* v5.14 hamburger menu (replaces the bottom tab bar) */',
    '.burger{display:inline-flex;align-items:center;justify-content:center}',
    '.burger svg{stroke:currentColor;fill:none;stroke-width:2;stroke-linecap:round}',
    '.hmenu{position:absolute;top:54px;inset-inline-start:12px;z-index:70;display:none;flex-direction:column;gap:4px;min-width:250px;padding:8px;',
    'background:var(--bg-soft);border:1px solid var(--line);border-radius:18px;box-shadow:0 26px 70px rgba(0,0,0,.55)}',
    '.hmenu.show{display:flex}',
    '.hmenu button{display:flex;align-items:center;gap:11px;padding:10px 12px;border-radius:14px;border:1px solid transparent;',
    'background:none;color:var(--muted);font:inherit;font-size:13.5px;font-weight:700;cursor:pointer;text-align:start}',
    '.hmenu button svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex-shrink:0}',
    '.hmenu button:hover{background:var(--surface-2);color:var(--text)}',
    '.hmenu button.active{color:var(--on-accent);background:linear-gradient(135deg,var(--accent),var(--accent-3));box-shadow:0 10px 26px var(--glow-a)}',
    'nav.tabs .inner{max-width:1000px;margin:0 auto;display:grid;grid-template-columns:repeat(5,1fr);gap:6px}',
    'nav.tabs button{background:none;border:0;color:var(--dim);font:inherit;font-size:11px;font-weight:600;display:flex;flex-direction:column;align-items:center;gap:4px;padding:7px 2px;border-radius:12px;cursor:pointer}',
    'nav.tabs button.active{color:var(--text);background:linear-gradient(180deg,rgba(168,85,247,.22),transparent)}',
    'nav.tabs svg{width:21px;height:21px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}',
    '.tab{display:none;animation:fade .25s ease}',
    '.tab.active{display:block}',
    '@keyframes fade{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}',
    '.hero{display:flex;gap:16px;align-items:center;flex-wrap:wrap}',
    '.orb{position:relative;width:96px;height:96px;flex:0 0 auto;display:grid;place-items:center;border-radius:50%;background:radial-gradient(circle at 32% 28%,rgba(255,255,255,.28),transparent 55%),linear-gradient(140deg,var(--accent-2),var(--accent-3));box-shadow:0 0 0 10px rgba(168,85,247,.12),0 18px 46px rgba(124,58,237,.42)}',
    '.orb span{position:absolute;inset:0;border-radius:50%;border:1px solid rgba(255,255,255,.35);animation:pulse 2.6s ease-out infinite}',
    '@keyframes pulse{0%{transform:scale(.92);opacity:.85}100%{transform:scale(1.35);opacity:0}}',
    '.link-row{display:flex;gap:8px;align-items:center;background:var(--surface-2);border:1px solid var(--line-soft);border-radius:12px;padding:8px 10px;margin-bottom:8px}',
    '.link-row .grow{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:ui-monospace,monospace;font-size:12px;color:var(--muted);direction:ltr;text-align:left}',
    '.chips{display:flex;gap:6px;flex-wrap:wrap}',
    '.chip{border:1px solid var(--line-soft);background:var(--surface);border-radius:999px;padding:6px 12px;font-size:12px;font-weight:600;color:var(--muted);cursor:pointer}',
    '.chip.active{color:var(--text);border-color:var(--line);background:rgba(168,85,247,.16)}',
    '.table-wrap{overflow:auto;border:1px solid var(--line-soft);border-radius:14px}',
    'table{width:100%;border-collapse:collapse;font-size:12.5px;min-width:520px}',
    'th,td{padding:9px 11px;text-align:start;border-bottom:1px solid var(--line-soft);white-space:nowrap}',
    'th{background:var(--surface-2);color:var(--muted);font-weight:600;position:sticky;top:0;backdrop-filter:blur(10px)}',
    'tr:last-child td{border-bottom:0}',
    '.ms{font-weight:700;font-variant-numeric:tabular-nums}',
    '.ms.good{color:var(--ok)}.ms.mid{color:var(--warn)}.ms.bad{color:var(--bad)}',
    '.bar{height:8px;border-radius:999px;background:var(--surface-2);overflow:hidden;border:1px solid var(--line-soft)}',
    'td.acts{white-space:normal;min-width:260px}td.acts .btn{margin:2px 0}',
    '.apps{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px;margin-top:12px}',
    '.apps .app{display:flex;flex-direction:column;gap:2px;padding:11px 13px;border-radius:14px;background:var(--surface);border:1px solid var(--line);color:var(--text);text-decoration:none;transition:.18s}',
    '.apps .app:hover{border-color:var(--accent);transform:translateY(-1px)}.apps .app b{font-size:13.5px}.apps .app span{font-size:11px;color:var(--muted)}',
    '.bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--accent-2),var(--accent-3));transition:width .2s}',
    '.toast{position:fixed;inset-inline:0;bottom:104px;z-index:60;display:flex;justify-content:center;pointer-events:none}',
    '.toast span{background:linear-gradient(120deg,var(--accent-2),var(--accent-3));color:#fff;padding:9px 16px;border-radius:999px;font-size:13px;font-weight:700;box-shadow:0 14px 30px rgba(124,58,237,.35);opacity:0;transform:translateY(10px);transition:.22s}',
    '.toast.show span{opacity:1;transform:none}',
    '.modal{position:fixed;inset:0;z-index:70;background:rgba(4,2,10,.72);backdrop-filter:blur(6px);display:none;align-items:center;justify-content:center;padding:18px}',
    '.modal.show{display:flex}',
    '.modal .box{background:var(--bg-soft);border:1px solid var(--line);border-radius:22px;padding:18px;max-width:420px;width:100%;text-align:center;box-shadow:0 30px 80px rgba(0,0,0,.5)}',
    '.modal img{width:min(300px,72vw);height:auto;background:#fff;border-radius:16px;padding:10px;margin:0 auto}',
    'details{background:var(--surface);border:1px solid var(--line-soft);border-radius:14px;padding:12px;margin-bottom:8px}',
    'details summary{cursor:pointer;font-weight:700;font-size:13.5px}',
    'details p{margin-top:8px}',
    '.steps{counter-reset:s;display:grid;gap:10px}',
    '.step{position:relative;padding-inline-start:42px;font-size:13.5px;color:var(--muted)}',
    '.step::before{counter-increment:s;content:counter(s);position:absolute;inset-inline-start:0;top:0;width:28px;height:28px;border-radius:50%;display:grid;place-items:center;background:linear-gradient(120deg,var(--accent-2),var(--accent-3));color:#fff;font-weight:700;font-size:13px}',
    '.switch{display:inline-flex;align-items:center;gap:8px;font-size:12.5px;color:var(--muted);cursor:pointer}',
    '.switch input{width:auto;accent-color:var(--accent)}',
    '@media(max-width:560px){.wrap{padding:12px}.card{padding:15px}.top-inner{padding:9px 12px}.brand{font-size:16px}table{min-width:440px}}',
    '/* ── v5.11 admin shell ─────────────────────────────────────── */',
    '.shell{display:flex;align-items:stretch;min-height:100vh}',
    '.main{flex:1;min-width:0;display:flex;flex-direction:column}',
    '.side{display:none}',
    '@media(min-width:1024px){',
    '.burger{display:none}',
    '.hmenu{display:none!important}',
    '.side{display:flex;flex-direction:column;gap:6px;position:sticky;top:0;height:100vh;width:272px;flex-shrink:0;padding:20px 14px;',
    'border-inline-end:1px solid var(--line-soft);background:linear-gradient(180deg,color-mix(in srgb,var(--bg-soft) 88%,transparent),var(--bg));backdrop-filter:blur(16px)}',
    'nav.tabs{display:none!important}',
    'body{padding-bottom:28px}',
    '.wrap{max-width:1120px}',
    '}',
    '.side-brand{display:flex;align-items:center;gap:10px;padding:4px 10px 14px;font-weight:800;font-size:16.5px}',
    '.side-brand small{display:block;font-weight:600;font-size:10.5px;color:var(--muted);letter-spacing:.4px}',
    '.side-nav{display:flex;flex-direction:column;gap:4px}',
    '.side-nav button{display:flex;align-items:center;gap:11px;padding:10px 12px;border-radius:14px;border:1px solid transparent;',
    'background:none;color:var(--muted);font:inherit;font-size:13.5px;font-weight:700;cursor:pointer;text-align:start;transition:background .16s,color .16s}',
    '.side-nav button svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex-shrink:0}',
    '.side-nav button:hover{background:var(--surface);color:var(--text)}',
    '.side-nav button.active{color:var(--on-accent);background:linear-gradient(135deg,var(--accent),var(--accent-3));box-shadow:0 10px 26px var(--glow-a)}',
    '.side-txt small{display:block;font-weight:500;font-size:10.5px;opacity:.75;margin-top:1px}',
    '.side-foot{margin-top:auto;display:flex;align-items:center;gap:8px;padding:12px 10px 2px;border-top:1px solid var(--line-soft);font-size:11px;color:var(--dim)}',
    '.section-head{display:flex;align-items:center;gap:12px;margin:4px 2px 14px}',
    '.sh-icon{width:44px;height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:21px;border-radius:15px;',
    'background:linear-gradient(135deg,var(--glow-a),var(--glow-b));border:1px solid var(--line);box-shadow:0 8px 22px var(--glow-a)}',
    '.section-head h1{font-size:20px;font-weight:800;letter-spacing:.2px}',
    '.section-head p{font-size:12.5px;color:var(--muted);margin-top:2px}',
    '.login-wrap{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:18px;',
    'background-image:radial-gradient(700px 420px at 50% -10%,var(--glow-a),transparent 60%)}',
    '.login-card{width:100%;max-width:410px;padding:28px 24px;text-align:center}',
    '.login-logo{width:64px;height:64px;margin:0 auto 12px;display:flex;align-items:center;justify-content:center;border-radius:20px;',
    'background:linear-gradient(135deg,var(--accent),var(--accent-3));box-shadow:0 16px 44px var(--glow-a)}',
    '/* v5.12 brighter, harmonious surfaces */',
    '.card{background:linear-gradient(168deg,rgba(255,255,255,.12),rgba(255,255,255,.055));border:1px solid var(--line-soft);',
    'box-shadow:0 16px 44px rgba(0,0,0,.30);backdrop-filter:blur(12px)}',
    '.card h2{letter-spacing:.2px}',
    '.field input,.field select,.field textarea{background:rgba(255,255,255,.10)}',
    '.config-item{background:var(--surface-2)}',
    'html[data-theme="oled"] .card{background:linear-gradient(168deg,rgba(255,255,255,.055),rgba(255,255,255,.02))}',
    'html[data-theme="light"] .card{background:#ffffff;box-shadow:0 12px 34px rgba(76,29,149,.09)}',
    '.card{border-radius:20px}',
    '.card h2{font-size:15.5px;font-weight:800;gap:9px}',
    '.btn{font-weight:700}',
  ].join('');
}

/**
 * Panel themes. `violet` is the Cat Client signature (purple night) and the
 * default; the others are one-tap alternatives for people who want a different
 * look on the same panel.
 */
const PANEL_THEMES = [
  { id: 'violet', nameFa: 'بنفش شب', nameEn: 'Violet night', swatch: ['#0b0517', '#7c3aed', '#d946ef'] },
  { id: 'oled', nameFa: 'مشکی خالص', nameEn: 'Pure black', swatch: ['#000000', '#8b5cf6', '#a855f7'] },
  { id: 'orchid', nameFa: 'ارکیده', nameEn: 'Orchid', swatch: ['#0b0410', '#db2777', '#ec4899'] },
  { id: 'mono', nameFa: 'تکرنگ', nameEn: 'Monochrome', swatch: ['#0b0b0f', '#e5e7eb', '#71717a'] },
  { id: 'light', nameFa: 'روشن', nameEn: 'Violet light', swatch: ['#ffffff', '#7c3aed', '#c026d3'] },
];

function themeMenuHtml() {
  return PANEL_THEMES.map((theme) =>
    '<button type="button" data-theme-pick="' + theme.id + '">' +
    '<span class="swatches"><i style="background:' + theme.swatch[0] + '"></i>' +
    '<i style="background:' + theme.swatch[1] + '"></i><i style="background:' + theme.swatch[2] + '"></i></span>' +
    '<span>' + esc(theme.nameFa) + ' · ' + esc(theme.nameEn) + '</span></button>',
  ).join('');
}

/* ------------------------------------------------------------------ */
/* panel page                                                          */
/* ------------------------------------------------------------------ */

function panelState(host, env, uuid, request, settings) {
  const options = configOptions(null, host, env, settings || null);
  const ips = options.addresses;
  const cf = (request && request.cf) || {};
  return {
    version: CAT_PANEL_VERSION,
    tokenTemplateUrl: CF_TOKEN_TEMPLATE_URL,
    title: String(env.PANEL_TITLE || 'Cat Panel'),
    host: host,
    sni: effectiveSni(host, env),
    uuid: uuid,
    port: panelPaths(env).port,
    vlessPath: panelPaths(env).vlessPath,
    trojanPath: panelPaths(env).trojanPath,
    trojanPass: String(env.TROJAN_PASS || uuid),
    cleanIps: ips,
    configOptions: options,
    tlsPorts: TLS_PORTS,
    plainPorts: PLAIN_PORTS,
    defaultAddresses: DEFAULT_CLEAN_ADDRESSES,
    defaultIpv6: DEFAULT_CLEAN_IPV6,
    defaultPorts: DEFAULT_PORTS,
    proxyIps: proxyIpList(env, settings || null),
    sniList: Array.from(allowedSnis(host, env)),
    remote: !!env.REMOTE,
    warp: String(env.ENABLE_WARP).toLowerCase() !== 'false',
    panelLocked: !(String(env.OPEN_PANEL || '').toLowerCase() === 'true') || !!String(env.PANEL_PASSWORD || ''),
    colo: cf.colo || '',
    country: cf.country || '',
    edgeLocations: EDGE_LOCATIONS,
    verifiedScanned: !!(settings && settings.configs && settings.configs.verifiedScanned === true),
    countryPools: countryPools(normalizedVerifiedEntries(settings)),
    city: cf.city || '',
    asn: cf.asOrganization || '',
    dnsUpstream: dohUpstream(env),
    dnsPresets: DNS_PRESETS,
    irIps: IR_CLEAN_IPS,
    hasKv: hasKv(env),
    usersApi: '/api/users',
    dotPresets: DNS_PRESETS.map((p) => ({ name: p.name, host: p.dot })),
    repo: CAT_REPO,
    subUrl: 'https://' + host + '/sub/' + uuid,
    subRawUrl: 'https://' + host + '/sub/' + uuid + '/raw',
    clashUrl: 'https://' + host + '/sub/' + uuid + '/clash',
    singboxUrl: 'https://' + host + '/sub/' + uuid + '/singbox',
    allUrl: 'https://' + host + '/sub/' + uuid + '/all',
    dohUrl: 'https://' + host + '/dns-query',
    qrBase: 'https://' + host + '/qr.svg',
    scanTargets: scanTargets(env),
    scanRanges: scanRanges(env),
    deepLink: 'catclient://add-sub?url=' + encodeURIComponent('https://' + host + '/sub/' + uuid) + '&name=' + encodeURIComponent('Cat Panel'),
  };
}

/** "Add to app" buttons for a subscription URL (rendered server-side, refreshed client-side). */
function appButtonsHtml(subUrl, title) {
  return appDeepLinks(subUrl, title || 'Cat Panel')
    .filter((a) => a.id !== 'catclient')
    .map((a) => '<a class="app" data-app="' + a.id + '" href="' + esc(a.href) + '"><b>' + esc(a.label) + '</b><span>افزودن خودکار</span></a>')
    .join('');
}

/** White Cat watermark (data-uri SVG) used as the panel background art. */
function catWatermarkUri() {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">' +
    '<path d="M11 20 8.5 6.5c-.2-1 1-1.7 1.8-1.1L20 12.6c2.6-.7 5.4-.7 8 0l9.7-7.2c.8-.6 2 .1 1.8 1.1L37 20c1.9 2.6 3 5.8 3 9.2C40 38.7 32.8 45 24 45S8 38.7 8 29.2c0-3.4 1.1-6.6 3-9.2Z" fill="#ffffff"/>' +
    '<ellipse cx="17.5" cy="28" rx="3.1" ry="3.6" fill="#0e0a1a"/><ellipse cx="30.5" cy="28" rx="3.1" ry="3.6" fill="#0e0a1a"/>' +
    '<path d="M24 34.5c-1.6 0-2.6 1.3-2.2 2.6.4 1.4 1.4 2.4 2.2 2.4s1.8-1 2.2-2.4c.4-1.3-.6-2.6-2.2-2.6Z" fill="#0e0a1a"/></svg>';
  return 'data:image/svg+xml,' + encodeURIComponent(svg);
}

function loginHtml(title, error, userRequired) {
  return '<!doctype html><html data-theme="dark" data-lang="fa"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1"><title>' + esc(title) + '</title>' +
    '<style>' + css() + '</style></head><body data-lang="fa">' +
    '<div class="login-wrap"><div class="login-card card glow">' +
    '<div class="login-logo"><span class="cat">' + catLogo(34) + '</span></div>' +
    '<h1 style="font-size:20px;font-weight:800">' + esc(title) + '</h1>' +
    '<p class="muted" style="margin-top:6px">پنل مدیریت — ورود مخصوص مدیر / Admin sign-in</p>' +
    (error ? '<p class="warn" style="margin-top:10px">' + esc(error) + '</p>' : '') +
    '<form method="get" action="/" id="loginForm" style="margin-top:12px">' +
    (userRequired ? '<label class="field" style="text-align:start;margin-top:10px"><span>Username · نام کاربری</span><input id="loginUser" autocomplete="username" placeholder="admin"></label>' : '') +
    '<label class="field" style="text-align:start;margin-top:10px"><span>Password · رمز پنل</span><input type="password" id="loginPass" autofocus autocomplete="current-password" placeholder="••••••••"></label>' +
    '<button class="btn" type="submit" style="width:100%;margin-top:14px">ورود امن / Sign in</button>' +
    '</form>' +
    '<p class="muted" style="margin-top:14px;font-size:11.5px">تا وقتی رمز جدا نگذاشته‌ای، رمز پنل همان <b>UUID</b> است. / Until you set one, the password is the panel UUID.</p>' +
    '</div></div>' +
    '<script>document.getElementById("loginForm").addEventListener("submit",function(ev){ev.preventDefault();var p=document.getElementById("loginPass").value;var u=document.getElementById("loginUser");' +
    'fetch("/api/login",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({password:p,username:u?u.value:""})}).then(function(r){return r.json()}).then(function(j){' +
    'if(j.ok){location.href="/";}else{alert(j.error==="too-many-attempts"?"تلاش زیاد — ۱۰ دقیقه صبر کن":"نام کاربری یا رمز اشتباه است");}}).catch(function(){location.href="/?p="+encodeURIComponent(p)});});</script>' +
    '</body></html>';
}

function panelShell(state) {
  const safeState = JSON.stringify(state).replace(/</g, '\\u003c');
  return '<!doctype html><html data-theme="dark" data-lang="fa"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' +
    '<meta name="theme-color" content="#06030c">' +
    '<meta name="description" content="Cat Panel — Cloudflare Worker VPN panel">' +
    '<title>' + esc(state.title) + '</title>' +
    '<link rel="icon" href="data:image/svg+xml,' + encodeURIComponent(catLogo(48)) + '">' +
    '<style>' + css() + '</style></head>' +
    '<body data-lang="fa">' +
    '<div class="shell">' +
    '<aside class="side">' +
    '<div class="side-brand"><span class="cat">' + catLogo(26) + '</span><span><b>' + esc(state.title) + '</b><small>پنل مدیریت Cat</small></span></div>' +
    '<nav class="side-nav">' +
    sideButton('home', 'خانه', 'وضعیت و لینک‌ها', '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>') +
    sideButton('configs', 'کانفیگ‌ها', 'ساخت و خروجی کانفیگ', '<path d="M4 6h16M4 12h16M4 18h10"/>') +
    sideButton('scanner', 'اسکنر', 'IP سالم کلودفلر', '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>') +
    sideButton('users', 'کاربران', 'اشتراک اختصاصی هر نفر', '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-5.5 6.5-5.5S15.5 16.4 15.5 20"/><path d="M17 8.5a3 3 0 1 0 0-6"/><path d="M17.5 14.2c2.6.5 4 2.3 4 5.3"/>') +
    sideButton('dns', 'DNS', 'DNS رمزنگاری‌شده', '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z"/><path d="M3.5 9h17M3.5 15h17M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"/>') +
    sideButton('tools', 'ابزارها', 'تنظیمات و بکاپ', '<path d="M14.7 6.3a4 4 0 0 1-5.4 5.4L4 17v3h3l5.3-5.3a4 4 0 0 1 5.4-5.4l-2.6 2.6"/>') +
    sideButton('help', 'راهنما', 'نصب و رفع اشکال', '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.3"/><path d="M12 17h.01"/>') +
    '</nav>' +
    '<div class="side-foot"><span class="pill ok">آنلاین</span><span dir="ltr">v' + CAT_PANEL_VERSION + '</span></div>' +
    '</aside>' +
    '<div class="main">' +
    '<header class="top"><div class="top-inner">' +
    '<button class="icon-btn burger" id="burgerBtn" title="منو"><svg viewBox="0 0 24 24" width="20" height="20"><path d="M4 7h16M4 12h16M4 17h16"/></svg></button>' +
    '<div class="hmenu" id="hmenu">' +
    sideButton('home', 'خانه', 'وضعیت و لینک‌ها', '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>') +
    sideButton('configs', 'کانفیگ‌ها', 'ساخت و خروجی کانفیگ', '<path d="M4 6h16M4 12h16M4 18h10"/>') +
    sideButton('scanner', 'اسکنر', 'IP سالم کلودفلر', '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>') +
    sideButton('users', 'کاربران', 'اشتراک اختصاصی هر نفر', '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-5.5 6.5-5.5S15.5 16.4 15.5 20"/><path d="M17 8.5a3 3 0 1 0 0-6"/><path d="M17.5 14.2c2.6.5 4 2.3 4 5.3"/>') +
    sideButton('dns', 'DNS', 'DNS رمزنگاری‌شده', '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z"/><path d="M3.5 9h17M3.5 15h17M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"/>') +
    sideButton('tools', 'ابزارها', 'تنظیمات و بکاپ', '<path d="M14.7 6.3a4 4 0 0 1-5.4 5.4L4 17v3h3l5.3-5.3a4 4 0 0 1 5.4-5.4l-2.6 2.6"/>') +
    sideButton('help', 'راهنما', 'نصب و رفع اشکال', '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.3"/><path d="M12 17h.01"/>') +
    '</div>' +
    '<div class="brand"><span class="cat">' + catLogo(26) + '</span><span><b id="brandName">' + esc(state.title) + '</b>' +
    '<small id="brandSub">پنل کلودفلر شخصی شما</small></span></div>' +
    '<span class="spacer"></span>' +
    '<span class="pill ok" id="onlinePill">آنلاین</span>' +
    '<button class="icon-btn" id="themeBtn" title="تم / Theme">🎨</button>' +
    '<button class="icon-btn" id="langBtn" title="Language">EN</button>' +
    '<div class="theme-menu" id="themeMenu">' + themeMenuHtml() + '</div>' +
    '</div></header>' +

    '<div class="wrap">' + homeTabHtml(state) + configsTabHtml(state) + scannerTabHtml(state) +
      dnsTabHtml(state) + usersTabHtml(state) + toolsTabHtml(state) + helpTabHtml(state) + '</div>' +

    '</div></div>' +

    '<div class="toast" id="toast"><span id="toastText"></span></div>' +
    '<div class="modal" id="qrModal"><div class="box">' +
    '<h2 style="justify-content:center"><span class="dot"></span><span id="qrTitle">QR</span></h2>' +
    '<img id="qrImg" alt="QR code">' +
    '<p class="muted" id="qrHint" style="margin-top:10px;word-break:break-all"></p>' +
    '<div class="row" style="justify-content:center;margin-top:12px">' +
    '<button class="btn tiny" id="qrCopy">کپی لینک</button>' +
    '<button class="btn ghost tiny" id="qrClose">بستن</button></div></div></div>' +

    '<script>window.CAT_STATE=' + safeState + ';</script>' +
    '<script>' + panelClientJs() + '</script>' +
    '</body></html>';
}

function navButton(id, label, path) {
  return '<button data-tab="' + id + '" class="' + (id === 'home' ? 'active' : '') + '">' +
    '<svg viewBox="0 0 24 24">' + path + '</svg>' +
    '<span data-nav-label="' + id + '">' + esc(label) + '</span></button>';
}

function sideButton(id, label, desc, path) {
  return '<button data-tab="' + id + '" class="' + (id === 'home' ? 'active' : '') + '">' +
    '<svg viewBox="0 0 24 24">' + path + '</svg>' +
    '<span class="side-txt"><span data-nav-label="' + id + '">' + esc(label) + '</span><small>' + esc(desc) + '</small></span></button>';
}

function sectionHead(icon, title, sub) {
  return '<div class="section-head"><span class="sh-icon">' + icon + '</span><div><h1>' + title + '</h1><p>' + sub + '</p></div></div>';
}

function homeTabHtml(state) {
  return '<section class="tab active" data-tab-panel="home">' +
    '<div class="card glow"><div class="hero">' +
    '<div class="orb"><span></span><span style="animation-delay:.6s"></span><span style="animation-delay:1.2s"></span></div>' +
    '<div style="flex:1;min-width:220px">' +
    '<h1 id="heroTitle">پنل فعال است</h1>' +
    '<p id="heroSub">این Worker روی شبکهٔ کلودفلر اجرا می‌شود؛ با یک لینک، همهٔ دستگاه‌هایت را وصل کن.</p>' +
    '<div class="row" style="margin-top:10px">' +
    '<span class="pill">v' + CAT_PANEL_VERSION + '</span>' +
    '<span class="pill">VLESS-WS</span><span class="pill">Trojan-WS</span>' +
    (state.warp ? '<span class="pill">WARP</span>' : '') +
    '<span class="pill">DoH</span>' +
    (state.remote ? '<span class="pill warn">REMOTE</span>' : '') +
    '</div></div></div>' +
    '<div class="grid three" style="margin-top:16px">' +
    statCard('نود کلودفلر', state.colo ? state.colo + (state.country ? ' · ' + state.country : '') : '—') +
    statCard('SNI پیش‌فرض', state.sni) +
    statCard('آدرس‌های تمیز', String(state.cleanIps.length)) +
    statCard('پورت‌ها', (state.configOptions ? state.configOptions.ports : [443]).join(' · ')) +
    statCard('پروکسی‌آی‌پی', String((state.proxyIps || []).length)) +
    statCard('قفل پنل', state.panelLocked ? 'فعال' : 'باز') +
    '</div></div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="subTitle">لینک سابسکریپشن</span></h2>' +
    '<div class="chips" id="subFormats">' +
    '<button class="chip active" data-fmt="" data-url="' + esc(state.subUrl) + '">لینک ساب</button>' +
    '<button class="chip" data-fmt="/raw" data-url="' + esc(state.subRawUrl) + '">متن ساده</button>' +
    '<button class="chip" data-fmt="/clash" data-url="' + esc(state.clashUrl) + '">Clash / Mihomo</button>' +
    '<button class="chip" data-fmt="/singbox" data-url="' + esc(state.singboxUrl) + '">Sing-box</button>' +
    '<button class="chip" data-fmt="/all" data-url="' + esc(state.allUrl) + '">همه‌چیز (JSON)</button>' +
    '</div>' +
    '<div class="link-row" style="margin-top:10px"><span class="grow" id="subUrlText">' + esc(state.subUrl) + '</span>' +
    '<button class="btn tiny" data-copy-target="subUrlText">کپی</button>' +
    '<button class="btn ghost tiny" data-qr-target="subUrlText">QR</button></div>' +
    '<div class="row" style="margin-top:10px">' +
    '<a class="btn" id="homeDeepLink" href="' + esc(state.deepLink) + '">🐱 افزودن به Cat Client</a>' +
    '<button class="btn ghost" id="downloadSub">دانلود فایل کانفیگ</button>' +
    '<button class="btn ghost" id="copyAllLinks">کپی همهٔ کانفیگ‌ها</button>' +
    '</div>' +
    '<div class="apps" id="homeApps">' + appButtonsHtml(state.subUrl, state.title) + '</div>' +
    '<p class="muted" style="margin-top:8px">لینک شامل UUID توست — آن را فقط به کسانی بده که می‌خواهی وصل شوند. در Cat Client → سابسکریپشن → + → لینک را وارد کن؛ هر «بروزرسانی» آخرین آی‌پی‌ها و پورت‌های تنظیم‌شده در تب «کانفیگ‌ها» را می‌گیرد.</p>' +
    '</div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="stepsTitle">سه قدم تا اتصال</span></h2>' +
    '<div class="steps">' +
    '<div class="step">این صفحه یعنی Worker فعال است؛ لینک ساب را کپی کن.</div>' +
    '<div class="step">در Cat Client (یا v2rayNG / Hiddify / Clash Meta) افزودن سابسکریپشن را بزن و لینک را بچسبان.</div>' +
    '<div class="step">اگر سرعت کم بود، از تب «اسکنر» آی‌پی تمیز نزدیک اپراتورت را پیدا کن و کانفیگ بساز.</div>' +
    '</div></div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="infoTitle">اطلاعات اتصال</span></h2>' +
    '<div class="grid two">' +
    statCard('UUID', state.uuid) +
    statCard('هاست پنل', state.host) +
    statCard('پورت', String(state.port)) +
    statCard('مسیر VLESS', state.vlessPath) +
    statCard('مسیر Trojan', state.trojanPath) +
    statCard('رمز Trojan', state.trojanPass) +
    '</div>' +
    '<p class="muted" style="margin-top:10px">UUID در متغیر <code>UUID</code> قابل تغییر است؛ تا وقتی خالی باشد از روی دامنهٔ Worker ساخته می‌شود.</p>' +
    '</div></section>';
}

function statCard(key, value) {
  return '<div class="stat"><div class="k">' + esc(key) + '</div><div class="v" dir="ltr">' + esc(value) + '</div></div>';
}

function configsTabHtml(state) {
  const o = state.configOptions || { addresses: [], ports: [443], sni: state.sni, protocols: ['vless', 'trojan'], includeHost: true };
  const portChip = (p, tls) => '<button class="chip' + (o.ports.includes(p) ? ' active' : '') + '" data-port="' + p + '" data-tls="' + (tls ? 1 : 0) + '">' + p + (tls ? '' : ' <small>http</small>') + '</button>';
  return '<section class="tab" data-tab-panel="configs">' + sectionHead('⚙️', 'کانفیگ‌ها', 'ساخت کانفیگ با انتخاب کشور، تعداد و پورت') +
    '<div class="card glow"><h2><span class="dot"></span><span data-i18n="cfgBuilderTitle">تنظیم کانفیگ‌های Cat</span></h2>' +
    '<p>این‌جا تعیین می‌کنی کانفیگ‌های سابسکریپشن با <b>چه آدرس‌هایی</b> (آی‌پی تمیز / دامنه)، <b>چه پورت‌هایی</b> و <b>چه SNI‌ای</b> ساخته شوند. هر ترکیبِ آدرس × پورت × پروتکل یک کانفیگ می‌شود؛ اپ همه را می‌گیرد و خودش سریع‌ترین را انتخاب می‌کند.</p>' +
    '<label class="field" style="margin-top:12px"><span>آدرس‌های تمیز (آی‌پی یا دامنه — هر خط یا با کاما)</span>' +
    '<textarea id="cfgAddresses" rows="4" dir="ltr" placeholder="104.16.132.229&#10;www.speedtest.net">' + esc(o.addresses.join('\n')) + '</textarea></label>' +
    '<div class="row" style="margin-top:6px">' +
    '<button class="btn ghost tiny" id="cfgUseDefaults">آدرس‌های پیش‌فرض</button>' +
    '<button class="btn ghost tiny" id="cfgUseIr">کتابخانهٔ ایران</button>' +
    '<button class="btn ghost tiny" id="cfgFromScan">از نتیجهٔ اسکنر</button>' +
    '<button class="btn ghost tiny" id="cfgClearAddr">پاک کردن</button>' +
    '</div>' +
    '<div class="grid two" style="margin-top:10px">' +
    '<label class="field"><span>تعداد کانفیگ — خودت انتخاب کن (پیش‌فرض ۸، نه صدتا!)</span><select id="cfgCount"><option value="3">۳ کانفیگ</option><option value="6">۶ کانفیگ</option><option value="8" selected>۸ کانفیگ</option><option value="12">۱۲ کانفیگ</option><option value="20">۲۰ کانفیگ</option><option value="40">۴۰ کانفیگ</option></select></label>' +
    '<div class="field"><span>لوکیشن — فقط از همین کشورها کانفیگ بساز (IP از استخر همان کشور می‌آید و پرچم واقعی‌اش روی کانفیگ می‌نشیند)</span><div class="chips" id="cfgCountries"><button class="chip active" type="button" data-cc="">همه</button>' + ((state && state.countryPools) || []).filter((p) => p.code).map((p) => '<button class="chip" type="button" data-cc="' + esc(p.code) + '">' + esc(p.flag + ' ' + p.name + ' · ' + p.count) + '</button>').join('') + '</div></div>' +
    '</div>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>SNI (خالی = دامنهٔ ورکر)</span><input id="cfgSni" dir="ltr" value="' + esc(o.sni === state.host ? '' : o.sni) + '" placeholder="' + esc(state.host) + '"></label>' +
    '<label class="field"><span>SNIهای بیشتر (با کاما — بین کانفیگ‌ها می‌چرخند و دسترسی را بهتر می‌کنند)</span><input id="cfgSnis" dir="ltr" placeholder="speed.cloudflare.com,cdn.jsdelivr.net"></label>' +
    '<label class="field"><span>پروتکل‌ها</span><div class="chips" id="cfgProtos" style="margin-top:6px">' +
    '<button class="chip' + (o.protocols.includes('vless') ? ' active' : '') + '" data-proto="vless">VLESS</button>' +
    '<button class="chip' + (o.protocols.includes('trojan') ? ' active' : '') + '" data-proto="trojan">Trojan</button>' +
    '<button class="chip' + (o.includeHost !== false ? ' active' : '') + '" data-flag="host" title="خود دامنهٔ ورکر هم به‌عنوان آدرس اضافه شود">+ خود ورکر</button>' +
    '<button class="chip' + (o.includeIpv6 !== false ? ' active' : '') + '" data-flag="v6" title="آی‌پی‌های IPv6 کلودفلر هم اضافه شود">+ IPv6</button>' +
    '</div></label>' +
    '<label class="field"><span>Fingerprint (uTLS)</span><select id="cfgFp">' +
    ['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', 'random'].map((f) => '<option value="' + f + '"' + ((o.fingerprint || 'chrome') === f ? ' selected' : '') + '>' + f + (f === 'chrome' ? ' (پیش‌فرض — همهٔ کلاینت‌ها)' : f === 'ios' ? ' (پایدار)' : '') + '</option>').join('') +
    '</select></label></div>' +
    '<label class="field" style="margin-top:8px"><span>پورت‌ها — TLS (امن) / HTTP (وقتی TLS اختلال دارد)</span>' +
    '<div class="chips" id="cfgPorts">' + state.tlsPorts.map((p) => portChip(p, true)).join('') + state.plainPorts.map((p) => portChip(p, false)).join('') + '</div></label>' +
    '<div class="row" style="margin-top:12px">' +
    '<button class="btn" id="cfgApply">اعمال و ساخت لینک</button>' +
    '<button class="btn ghost" id="cfgSave">ذخیره در پنل (KV)</button>' +
    '<span class="muted" id="cfgSaveState" style="font-size:12px"></span>' +
    '</div>' +
    '<p class="muted" style="margin-top:8px">بدون KV هم کار می‌کند: «اعمال» تنظیمات را داخل خود لینک ساب می‌گذارد. با KV، لینک کوتاه <code>/sub/UUID</code> همیشه آخرین تنظیمات را می‌دهد.</p>' +
    '</div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="connectHowTitle">چرا وصل نمی‌شود؟ راهنمای اتصال Cat</span></h2>' +
    '<p>تونل این پنل با VLESS و Trojan روی WebSocket تست شده و سالم است. اگر کانفیگ وصل نمی‌شود، تقریباً همیشه مشکل <b>مسیر رسیدن به کلودفلر</b> است، نه خود پنل:</p>' +
    '<p>• دامنهٔ <code>workers.dev</code> در ایران روی SNI فیلتر است؛ کانفیگی که آدرسش خودِ ورکر باشد از خیلی اپراتورها بالا نمی‌آید. کانفیگ‌های <b>آی‌پی تمیز</b> (آدرس = IP، SNI/Host = دامنهٔ ورکر) را امتحان کن — این حالت برای شبکه‌های محدودشده طراحی شده است.<br>' +
    '• کانفیگ‌های <b>پورت 80 (بدون TLS)</b> اول لیست‌اند؛ چون SNI روی خط نمی‌رود، وقتی TLS اختلال دارد معمولاً سریع‌تر جواب می‌دهند.<br>' +
    '• در اپ، گزینهٔ <b>Fragment</b> را روشن کن (طول 100-200، تأخیر 1-1، بسته tlshello) تا SNI تکه‌تکه ارسال شود؛ Cat Client / MahsaNG / v2rayNG این را دارند.<br>' +
    '• اگر یک دامنهٔ شخصی روی کلودفلر داری، آن را به‌عنوان Custom Domain به ورکر وصل کن و در فیلد SNI بنویس — پایدارترین راه است.<br>' +
    '• آی‌پی‌های تازه را از تب «اسکنر» بگیر (روی رنج‌ها اسکن می‌کند) و با «گذاشتن داخل کانفیگ‌ها» همین‌جا اعمال کن؛ پورت ۴۴۳ + SNI دامنهٔ ورکر.</p>' +
    '<div class="row"><button class="btn ghost tiny" id="cfgCopyFragmentHint">کپی تنظیم Fragment پیشنهادی</button><button class="btn ghost tiny" data-goto-tab="scanner">رفتن به اسکنر</button></div>' +
    '</div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="subTitle">لینک سابسکریپشن</span></h2>' +
    '<div class="chips" id="cfgSubFormats">' +
    '<button class="chip active" data-fmt="">لینک ساب (Base64)</button>' +
    '<button class="chip" data-fmt="/raw">متن ساده</button>' +
    '<button class="chip" data-fmt="/clash">Clash / Mihomo</button>' +
    '<button class="chip" data-fmt="/singbox">Sing-box</button>' +
    '<button class="chip" data-fmt="/all">JSON</button>' +
    '</div>' +
    '<div class="link-row" style="margin-top:10px"><span class="grow" id="cfgSubUrl">' + esc(state.subUrl) + '</span>' +
    '<button class="btn tiny" data-copy-target="cfgSubUrl">کپی</button>' +
    '<button class="btn ghost tiny" data-qr-target="cfgSubUrl">QR</button></div>' +
    '<div class="row" style="margin-top:10px">' +
    '<a class="btn" id="cfgDeepLink" href="' + esc(state.deepLink) + '">🐱 افزودن به Cat Client</a>' +
    '<button class="btn ghost" id="downloadCfg">دانلود txt</button>' +
    '<button class="btn ghost" id="copyAllLinks">کپی همهٔ کانفیگ‌ها</button>' +
    '</div>' +
    '<div class="apps" id="cfgApps">' + appButtonsHtml(state.subUrl, state.title) + '</div></div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="configsTitle">کانفیگ‌های ساخته‌شده</span> <span class="pill" id="cfgCountLabel">0</span></h2>' +
    '<label class="field"><span>جستجو</span><input id="cfgSearch" placeholder="نام یا آی‌پی…"></label>' +
    '<div class="row"><button class="btn ghost tiny" id="cfgPingAll">پینگ همه (از مرورگر)</button>' +
    '<button class="btn ghost tiny" id="refreshCfg">ساخت دوباره</button></div>' +
    '<div class="table-wrap" style="margin-top:12px"><table><thead><tr>' +
    '<th>#</th><th>نام</th><th>آدرس</th><th>پورت</th><th>پینگ</th><th>عملیات</th></tr></thead>' +
    '<tbody id="cfgTable"></tbody></table></div>' +
    '<pre id="cfgAllText" style="display:none"></pre></div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="singleTitle">ساخت کانفیگ تکی</span></h2>' +
    '<p>یک آدرس دلخواه بده و همین حالا یک کانفیگ بساز — برای تست سریع یک آی‌پی.</p>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>آدرس سرور (IP یا دامنه)</span><input id="singleAddr" dir="ltr" value="' + esc(state.host) + '"></label>' +
    '<label class="field"><span>نام کانفیگ</span><input id="singleName" value="Cat Single"></label>' +
    '<label class="field"><span>SNI</span><input id="singleSni" dir="ltr" value="' + esc(o.sni) + '"></label>' +
    '<label class="field"><span>پورت</span><input id="singlePort" type="number" min="1" max="65535" value="' + esc(String(o.ports[0] || 443)) + '"></label>' +
    '<label class="field"><span>Host هدر</span><input id="singleHost" dir="ltr" value="' + esc(state.host) + '"></label>' +
    '<label class="field"><span>مسیر WebSocket</span><input id="singlePath" dir="ltr" value="' + esc(state.vlessPath) + '"></label>' +
    '</div>' +
    '<div class="chips" id="singleProto">' +
    '<button class="chip active" data-proto="vless">VLESS + WS</button>' +
    '<button class="chip" data-proto="trojan">Trojan + WS</button>' +
    '</div>' +
    '<div class="row" style="margin-top:12px">' +
    '<button class="btn" id="singleBuild">ساخت کانفیگ</button>' +
    '<button class="btn ghost tiny" id="singleCopy">کپی</button>' +
    '<button class="btn ghost tiny" id="singleQr">QR</button>' +
    '<a class="btn ghost tiny" id="singleAdd" href="#">افزودن به Cat Client</a>' +
    '<button class="btn ghost tiny" id="singleScan">اعمال در اسکنر اپ</button>' +
    '</div>' +
    '<pre id="singleOut" style="margin-top:10px">—</pre></div>' +
    '</section>';
}

function scannerTabHtml(state) {
  const ranges = (state && state.scanRanges) || SCAN_RANGES;
  const sniSuggestions = Array.from(new Set([
    String((state && state.sni) || (state && state.host) || '').trim(),
    'skk.moe',
    'www.speedtest.net',
    'cdnjs.cloudflare.com',
    'speed.cloudflare.com',
  ].filter(Boolean))).slice(0, 6);
  const sniChips = sniSuggestions.map((value) =>
    '<button class="chip" type="button" data-sni-suggestion="' + esc(value) + '">' + esc(value) + '</button>',
  ).join('');
  return '<section class="tab" data-tab-panel="scanner">' + sectionHead('🛰️', 'اسکنر آی‌پی', 'پیدا کردن IP سالم کلودفلر — IPv4 و IPv6 — و افزودن خودکار به کانفیگ‌ها') +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="scannerTitle">اسکنر آی‌پی تمیز کلودفلر</span></h2>' +
    '<p>دو اسکنر داری: <b>«از مرورگر»</b> سرعت واقعی هر آی‌پی را روی اینترنت خودت می‌سنجد (همان چیزی که برای اپراتور تو مهم است). <b>«از ورکر»</b> می‌گوید آن آی‌پی برای دامنهٔ پنل جواب می‌دهد یا نه (از سمت کلودفلر). نتیجهٔ خوب = هردو سبز.</p>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>حالت اسکن مرورگر</span><select id="scanMode"><option value="http">HTTP :80 — دقیق‌ترین از مرورگر (پیشنهادی)</option><option value="https">HTTPS :443 — فقط دسترسی TCP/TLS</option></select></label>' +
    '<label class="field"><span>تعداد هم‌زمان</span><input id="scanConc" type="number" min="1" max="32" value="8"></label>' +
    '<label class="field"><span>تایم‌اوت هر تست (ms)</span><input id="scanTimeout" type="number" min="500" max="8000" value="2000"></label>' +
    '<label class="field"><span>تعداد آی‌پی برای اسکن</span><input id="scanLimit" type="number" min="8" max="400" value="80"></label>' +
    '</div>' +
    '<label class="field" style="margin-top:10px"><span>SNI دامنهٔ پنل یا هاست پیشنهادی</span><input id="scanSni" dir="ltr" value="' + esc(String((state && state.sni) || (state && state.host) || '')) + '" placeholder="mypanel.workers.dev"></label>' +
    '<div class="chips" id="scanSniSuggestions" style="margin-top:8px">' + sniChips + '</div>' +
    '<div class="grid two" style="margin-top:8px">' +
    '<label class="field" style="grid-column:1/-1"><span>رنج‌های آی‌پی (CIDR) — هر بار از داخل هر رنج، آی‌پی‌های تازه و تصادفی تست می‌شود</span><textarea id="scanCustom" rows="3" dir="ltr" placeholder="104.16.0.0/13, 172.64.0.0/13, 188.114.96.0/20">' + esc(ranges.join(', ')) + '</textarea></label>' +
    '<label class="field"><span>تعداد آی‌پی از هر رنج</span><input id="scanPerRange" type="number" min="1" max="64" value="8"></label>' +
    '<label class="field"><span>&nbsp;</span><button class="btn ghost tiny" id="scanRangesReset" type="button">بازگشت به رنج‌های پیش‌فرض کلودفلر</button></label>' +
    '</div>' +
    '<p class="muted">می‌توانی تک‌آی‌پی هم بنویسی (مثلاً 104.16.6.62)، اما اسکن اصلی روی رنج‌ها انجام می‌شود؛ خالی بگذاری از کتابخانهٔ داخلی استفاده می‌شود.</p>' +
    '<div class="row"><button class="btn" id="scanStart">شروع اسکن از مرورگر</button>' +
    '<button class="btn ghost" id="scanServerAll">اسکن از ورکر</button>' +
    '<button class="btn ghost" id="scanStop" disabled>توقف</button>' +
    '<button class="btn ghost tiny" id="scanClear">پاک کردن</button></div>' +
    '<div class="bar" style="margin-top:12px"><i id="scanBar"></i></div>' +
    '<p class="muted" id="scanStatus" style="margin-top:8px">آماده.</p>' +
    '<div class="table-wrap" style="margin-top:12px"><table><thead><tr>' +
    '<th><input type="checkbox" id="scanAll" style="width:auto"></th><th>آی‌پی</th><th>مرورگر</th><th>ورکر</th><th>عملیات</th>' +
    '</tr></thead><tbody id="scanTable"></tbody></table></div>' +
    '<div class="row" style="margin-top:12px">' +
    '<button class="btn" id="useIpsInConfigs">📥 گذاشتن آی‌پی‌های انتخابی داخل کانفیگ‌ها</button><span class="pill" id="scanSelCount">0 انتخاب</span>' +
    '<button class="btn ghost" id="buildFromIps">کپی کانفیگ با انتخابی‌ها</button>' +
    '<button class="btn ghost" id="copyBestIps">کپی آی‌پی‌های برتر</button>' +
    '</div>' +
    '<p class="muted" style="margin-top:8px">«گذاشتن داخل کانفیگ‌ها» آی‌پی‌ها را به تب «کانفیگ‌ها» می‌برد؛ آن‌جا پورت و SNI را انتخاب کن و «اعمال» بزن — لینک ساب خودش عوض می‌شود و اپ با «بروزرسانی» همه را می‌گیرد.</p>' +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="scannerPools">IPها به تفکیک کشور</span></h2>' +
    '<p class="muted">هر کشور یک بخش جدا با پرچم و رنج خودش است؛ برچسب کانفیگ‌ها هم از همین دسته‌بندی می‌آید. داده از «اسکن از ورکر» یا شناسایی خودکار می‌آید.</p>' +
    ((state && state.countryPools && state.countryPools.length) ? state.countryPools.map((p) => '<div class="config-group"><h3>' + esc(p.flag + ' ' + p.name) + (p.code ? ' <span class="pill">' + esc(p.code) + '</span>' : '') + '<span class="cnt">' + p.count + ' IP</span></h3><div class="tags">' + p.ips.map((ip) => '<span class="pill" dir="ltr">' + esc(ip) + '</span>').join('') + (p.count > p.ips.length ? '<span class="pill">…</span>' : '') + '</div></div>').join('') : '<p class="muted">هنوز دسته‌بندی‌ای ساخته نشده — یک بار «اسکن از ورکر» را بزن یا چند لحظه صبر کن تا شناسایی خودکار تمام شود؛ بعد هلند 🇳🇱، آلمان 🇩🇪، فرانسه 🇫🇷 و… هرکدام جدا می‌آیند.</p>') +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span>چک سلامت آی‌پی‌ها — پینگ واقعی از ورکر</span></h2>' +
    '<p class="muted">همهٔ آی‌پی‌های انتخابی و استخر کشورها یک‌جا پینگ می‌شوند؛ کشور، کلو و پرچم هر کدام شناسایی می‌شود و آی‌پی‌های مرده خودکار از کانفیگ‌ها و لینک ساب حذف می‌شوند.</p>' +
    '<div class="row"><button class="btn" id="healthBtn">🩺 چک سلامت و حذف مرده‌ها</button><span class="muted" id="healthState"></span></div>' +
    '<div id="healthResults" style="margin-top:10px"></div>' +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="scannerHowto">راهنمای نتیجه</span></h2>' +
    '<p>• مرورگر زیر ۳۰۰ms = عالی · ۳۰۰–۷۰۰ = قابل قبول · ✗ = از شبکهٔ تو بسته است.<br>• ستون «ورکر» ✓ یعنی آن آی‌پی برای دامنهٔ پنل تو جواب می‌دهد.<br>• قبل از اسکن، VPN را خاموش کن تا نتیجه مال اپراتور خودت باشد.</p>' +
    '</div></section>';
}

function dnsTabHtml(state) {
  const dotRows = state.dotPresets.map((p) =>
    '<tr><td>' + esc(p.name) + '</td><td dir="ltr"><code>' + esc(p.host) +
    '</code></td><td><button class="btn ghost tiny" data-dot="' + esc(p.host) + '">کپی / بررسی</button></td></tr>').join('');
  return '<section class="tab" data-tab-panel="dns">' + sectionHead('🔐', 'DNS رمزنگاری‌شده', 'DoH و DoT برای عبور امن از فیلترینگ') +
    '<div class="card glow"><h2><span class="dot"></span><span data-i18n="dnsTitle">DNS رمزنگاری‌شده (DoH)</span></h2>' +
    '<p>این Worker در نقش یک رزولور DoH هم کار می‌کند. دستگاهت می‌تواند کوئری‌های DNS را رمزنگاری‌شده به همین دامنه بفرستد؛ نتیجه از طریق کلودفلر بیرون می‌رود و اپراتور نمی‌تواند داخل آن را ببیند.</p>' +
    '<div class="link-row" style="margin-top:12px"><span class="grow" id="dohUrlText">' + esc(state.dohUrl) + '</span>' +
    '<button class="btn tiny" data-copy-target="dohUrlText">کپی</button>' +
    '<button class="btn ghost tiny" data-qr-target="dohUrlText">QR</button></div>' +
    '<div class="row" style="margin-top:10px"><button class="btn ghost tiny" id="dohTest">تست تأخیر همهٔ سرورها</button>' +
    '<span class="muted" id="dohStatus">آماده.</span></div>' +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="dnsUpstreamTitle">سرورهای بالادستی</span></h2>' +
    '<div class="table-wrap"><table><thead><tr><th>نام</th><th>آدرس</th><th>تأخیر</th><th></th></tr></thead>' +
    '<tbody id="dnsTable"></tbody></table></div>' +
    '<p class="muted" style="margin-top:10px">سرور پیش‌فرض: <code id="dnsCurrent">' + esc(state.dnsUpstream) + '</code> — با متغیر <code>DNS_UPSTREAM</code> قابل تغییر است.</p>' +
    '<pre id="dnsCustomText" style="display:none"></pre></div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="dnsCustomTitle">DoH و DoT سفارشی</span></h2>' +
    '<div class="grid two">' +
    '<label class="field"><span>آدرس DoH دلخواه (سرور خودت یا هر رزولور)</span><input id="dohCustom" dir="ltr" placeholder="https://dns.example.com/dns-query"></label>' +
    '<label class="field"><span>هاست DoT دلخواه (برای Private DNS اندروید)</span><input id="dotCustom" dir="ltr" placeholder="dns.example.com"></label>' +
    '</div>' +
    '<div class="row">' +
    '<button class="btn tiny" id="dohCustomTest">تست DoH دلخواه</button>' +
    '<button class="btn ghost tiny" id="dohCustomApply">استفاده در /dns-query این پنل</button>' +
    '<button class="btn ghost tiny" id="dotCustomCheck">بررسی DoT</button>' +
    '</div>' +
    '<pre id="dnsCustomResult" style="margin-top:10px">—</pre>' +
    '<p class="muted">DoT را نمی‌شود از داخل Worker پروکسی کرد (کلودفلر فقط HTTPS می‌دهد)؛ برای اندروید کافی است هاست DoT را در <b>Private DNS</b> بگذاری. بررسی DoT اینجا فقط رزولوشن نام را تست می‌کند.</p>' +
    '<h2 style="margin-top:16px"><span class="dot"></span><span data-i18n="dotTitle">هاست‌های DoT پیشنهادی</span></h2>' +
    '<div class="table-wrap"><table><thead><tr><th>نام</th><th>هاست DoT</th><th>عملیات</th></tr></thead><tbody>' + dotRows + '</tbody></table></div>' +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="dnsUseTitle">چطور استفاده کنم؟</span></h2>' +
    '<div class="steps" id="dnsSteps">' +
    '<div class="step">Cat Client → تنظیمات → DNS رمزنگاری‌شده → حالت سفارشی (DoH) و همین آدرس را وارد کن.</div>' +
    '<div class="step">در مرورگر (Chrome یا Firefox): Settings → Privacy → Secure DNS → Custom → همین آدرس.</div>' +
    '<div class="step">در اندروید اگر برنامهٔ جدا می‌خواهی: Intra یا RethinkDNS را با همین آدرس DoH تنظیم کن (Private DNS اندروید فقط DoT است).</div>' +
    '<div class="step">در Mihomo/Clash: بخش dns → nameserver → همین آدرس (کانفیگ /clash از قبل تنظیم شده است).</div>' +
    '</div></div></section>';
}

function usersTabHtml(state) {
  return '<section class="tab" data-tab-panel="users">' + sectionHead('👥', 'کاربران', 'برای هر نفر کشور انتخاب کن و لینک اختصاصی بگیر') +
    '<div class="card glow"><h2><span class="dot"></span><span data-i18n="usersTitle">کاربران پنل</span></h2>' +
    '<p>هر کاربر لینک سابسکریپشن، UUID و رمز Trojan مستقل خودش را دارد؛ حجم، تاریخ انقضا و تعداد دستگاه هم قابل تنظیم است. برای ذخیره‌سازی به بایندینگ KV نیاز است.</p>' +
    '<p class="muted" id="kvState">' + (state.hasKv ? '✅ KV متصل است — کاربران ذخیره می‌شوند.' : '⚠️ KV وصل نیست — فقط UUID اصلی کار می‌کند. یک Namespace بساز و با نام <code>CAT_KV</code> به ورکر بایند کن.') + '</p>' +
    '<div class="card" style="background:transparent;border-style:dashed"><h2><span class="dot"></span><span>کاربر جدید</span></h2>' +
    '<div class="grid two">' +
    '<label class="field"><span>نام</span><input id="uName" placeholder="Ali"></label>' +
    '<label class="field"><span>حجم (GB) — 0 یعنی نامحدود</span><input id="uQuota" type="number" min="0" step="1" value="0"></label>' +
    '<label class="field"><span>انقضا (روز) — 0 یعنی بدون انقضا</span><input id="uDays" type="number" min="0" step="1" value="0"></label>' +
    '<label class="field"><span>محدودیت دستگاه — 0 یعنی آزاد</span><input id="uDevices" type="number" min="0" step="1" value="0"></label>' +
    '<label class="field" style="grid-column:1/-1"><span>کشورهای کاربر — با یک کلیک انتخاب کن (تا کشوری نگذاری کانفیگی نمی‌گیرد)</span><div class="chips" id="uCountryChips"></div><input id="uCountries" dir="ltr" placeholder="یا اینجا اضافه کن: NL,DE,FR" style="margin-top:8px"></label>' +
    '</div>' +
    '<div class="row" style="margin-top:12px"><button class="btn" id="uCreate">ساخت کاربر</button>' +
    '<button class="btn ghost tiny" id="uReload">بارگذاری مجدد</button>' +
    '<label class="field" style="margin:0;flex-direction:row;align-items:center;gap:8px"><input type="checkbox" id="uAuto" checked style="width:auto"><span style="margin:0">تازه‌سازی خودکار هر ۲۰ ثانیه</span></label></div></div>' +
    '<div class="grid three" style="margin-top:12px"><div class="stat"><div class="k">کاربران</div><div class="v" id="uCount">—</div></div>' +
    '<div class="stat"><div class="k">اتصال‌های زنده</div><div class="v" id="uOnline">—</div></div>' +
    '<div class="stat"><div class="k">مصرف کل</div><div class="v" id="uTotalUsed">—</div></div></div>' +
    '<div class="table-wrap" style="margin-top:12px"><table><thead><tr><th>#</th><th>کاربر</th><th>مصرف</th><th>انقضا</th><th>وضعیت</th><th>عملیات</th></tr></thead>' +
    '<tbody id="userTable"><tr><td colspan="6">در حال بارگذاری…</td></tr></tbody></table></div>' +
    '<p class="muted" style="margin-top:8px">«صفحهٔ کاربر» یک صفحهٔ عمومی است (بدون رمز پنل) که کاربر در آن مصرف، انقضا و دکمه‌های افزودن به v2rayNG / V2Box / Hiddify / Streisand را می‌بیند — لینک همان را برایش بفرست.</p>' +
    '</div></section>';
}

function toolsTabHtml(state) {
  return '<section class="tab" data-tab-panel="tools">' + sectionHead('🧰', 'ابزارها و تنظیمات', 'رمز و نام کاربری پنل، عنوان، بکاپ و بازیابی') +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="toolsTitle">ابزارها و تنظیمات پنل</span></h2>' +
    '<div class="grid two">' +
    '<label class="field"><span>عنوان پنل</span><input id="tTitle" value="' + esc(state.title) + '"></label>' +
    '<label class="field"><span>رمز ورود پنل (خالی = بدون رمز)</span><input id="tPass" type="password" placeholder="••••••"></label>' +
    '<label class="field"><span>نام کاربری پنل (خالی = فقط رمز)</span><input id="tUser" dir="ltr" placeholder="admin"></label>' +
    '<label class="field"><span>DoH بالادستی</span><input id="tDns" dir="ltr" value="' + esc(state.dnsUpstream) + '"></label>' +
    '<label class="field"><span>UUID اصلی (env: UUID)</span><input id="tUuid" dir="ltr" value="' + esc(state.uuid) + '"></label>' +
    '<label class="field"><span>پروکسی‌آی‌پی‌ها (با کاما)</span><input id="tProxyIps" dir="ltr" placeholder="1.2.3.4,5.6.7.8"></label>' +
    '<label class="field"><span>SNI-های مجاز (با کاما)</span><input id="tSnis" dir="ltr" value="' + esc((state.sniList || []).join(',')) + '"></label>' +
    '</div>' +
    '<div class="row" style="margin-top:12px"><button class="btn" id="tSave">ذخیره در KV</button>' +
    '<button class="btn ghost tiny" id="tBackup">دانلود بکاپ JSON</button>' +
    '<button class="btn ghost tiny" id="tRestoreBtn">بازیابی بکاپ</button>' +
    '<input type="file" id="tRestoreFile" accept="application/json" style="display:none"></div>' +
    '<pre id="tResult" style="margin-top:10px">—</pre></div>' +

    '<div class="card"><h2><span class="dot"></span><span>وضعیت ورکر</span></h2>' +
    '<div class="table-wrap"><table><tbody id="selfTable"><tr><td>در حال خواندن…</td></tr></tbody></table></div>' +
    '<div class="row" style="margin-top:12px"><button class="btn ghost tiny" id="selfReload">به‌روزرسانی</button>' +
    '<button class="btn ghost tiny" id="scanServer">اسکن سرور روی همهٔ آی‌پی‌های کتابخانه</button></div>' +
    '<pre id="selfScanOut" style="margin-top:10px">—</pre></div>' +

    '<div class="card"><h2><span class="dot"></span><span>کتابخانهٔ آی‌پی تمیز (مناسب ایران)</span></h2>' +
    '<p class="muted">این آی‌پی‌ها روی شبکه‌های ایران معمولاً بدون افت کار می‌کنند. «اسکن» تأخیر واقعی را از سمت ورکر می‌سنجد.</p>' +
    '<pre id="irIpsOut" style="max-height:180px;overflow:auto;direction:ltr">' + IR_CLEAN_IPS.join('\n') + '</pre>' +
    '<div class="row"><button class="btn ghost tiny" data-copy-target="irIpsOut">کپی همه</button>' +
    '<button class="btn ghost tiny" id="irIpsUse">ساخت کانفیگ با این آی‌پی‌ها</button></div></div>' +
    '</section>';
}

function helpTabHtml(state) {
  const envRows = [
    ['UUID', state.uuid, 'شناسهٔ اتصال (auto از دامنه)'],
    ['SNI', state.sni, 'SNI پیش‌فرض لینک‌ها'],
    ['SNI_LIST', state.sniList.join(', '), 'لیست SNIهای مجاز'],
    ['CF_IPS', state.cleanIps.join(', ') || '(خالی)', 'آی‌پی‌های تمیز برای ساخت کانفیگ'],
    ['PORT', String(state.port), 'پورت لینک‌ها'],
    ['VLESS_PATH', state.vlessPath, 'مسیر WebSocket ولز'],
    ['TROJAN_PATH', state.trojanPath, 'مسیر WebSocket تروجان'],
    ['PANEL_PASSWORD', state.panelLocked ? 'فعال' : 'غیرفعال', 'رمز ورود به پنل'],
    ['REMOTE', state.remote ? 'فعال' : 'غیرفعال', 'تونل کامل TCP'],
    ['DNS_UPSTREAM', state.dnsUpstream, 'رزولور بالادستی DoH'],
  ];
  const rows = envRows.map((r) =>
    '<tr><td><code>' + esc(r[0]) + '</code></td><td dir="ltr">' + esc(r[1]) +
    '</td><td class="muted">' + esc(r[2]) + '</td></tr>').join('');
  return '<section class="tab" data-tab-panel="help">' + sectionHead('📖', 'راهنما', 'نصب، اتصال و رفع اشکال') +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="helpTitle">راهنمای پنل</span></h2>' +
    '<div class="steps">' +
    '<div class="step"><b>راه سریع (ویزارد):</b> <a href="' + esc(CF_TOKEN_TEMPLATE_URL) + '" target="_blank" rel="noopener">این لینک</a> صفحهٔ API Token کلودفلر را با دسترسی‌های آماده باز می‌کند → Continue to summary → Create Token → توکن را در اپ Cat Client (تب Cloud) یا در Cat Wizard بچسبان؛ پنل + KV + رمز خودکار ساخته می‌شود.</div>' +
    '<div class="step"><b>راه دستی:</b> Cloudflare → Workers &amp; Pages → Create Worker → کد را کامل جای‌گذاری کن → Deploy.</div>' +
    '<div class="step">Settings → Variables &amp; Secrets → هر متغیری که لازم داری اضافه کن (جدول پایین).</div>' +
    '<div class="step">آدرس Worker را باز کن؛ همین پنل بالا می‌آید. برای قفل‌کردن، PANEL_PASSWORD بگذار و آدرس را با <code>?p=رمز</code> باز کن.</div>' +
    '<div class="step">لینک ساب را در Cat Client وارد کن و اتصال را تست کن.</div>' +
    '</div>' +
    '<div class="row" style="margin-top:12px">' +
    '<button class="btn" id="copyCode">📥 کپی کد کامل پنل</button>' +
    '<a class="btn ghost" href="' + esc(state.repo) + '" target="_blank" rel="noopener">مخزن گیت‌هاب</a>' +
    '<a class="btn ghost" href="' + esc(state.repo) + '/releases" target="_blank" rel="noopener">آخرین نسخهٔ اپ</a>' +
    '</div></div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="envTitle">متغیرهای پنل</span></h2>' +
    '<div class="table-wrap"><table><thead><tr><th>متغیر</th><th>مقدار فعلی</th><th>توضیح</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table></div></div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="faqTitle">پرسش‌های پرتکرار</span></h2>' +
    '<details><summary>سرعت کم است، چه کنم؟</summary><p>تب اسکنر → اسکن آی‌پی تمیز → آی‌پی‌های زیر ۳۰۰ms را تیک بزن → کانفیگ بساز. برای هر اپراتور (همراه اول، ایرانسل، مخابرات) آی‌پی بهتری وجود دارد.</p></details>' +
    '<details><summary>کلاینت وصل نمی‌شود ولی پنل باز است؟</summary><p>مسیر یا UUID را تغییر داده‌ای؟ بعد از تغییر متغیرها، ساب را در اپ دوباره بروزرسانی کن. اگر <code>REMOTE</code> را فعال کرده‌ای باید رلهٔ wss درست باشد، وگرنه آن را خالی بگذار (حالت پیش‌فرض).</p></details>' +
    '<details><summary>آیا اتصال امن است؟</summary><p>پنل و کانفیگ‌ها روی حساب کلودفلر خودت اجرا می‌شوند؛ هیچ لاگی از ترافیک ذخیره نمی‌شود. برای امنیت بیشتر PANEL_PASSWORD بگذار و SNI_LIST را فقط دامنه‌های خودت نگه دار.</p></details>' +
    '<details><summary>روی اپراتور خاصی کار نمی‌کند؟</summary><p>آی‌پی دیگری از لیست اسکنر انتخاب کن یا SNI را به دامنهٔ سالم دیگری تغییر بده (SNI_LIST). بعضی اپراتورها بعضی آی‌پی‌ها را بسته‌اند.</p></details>' +
    '<details><summary>چطور کانفیگ Warp بگیرم؟</summary><p>در لینک <code>/sub</code> یک آیتم <code>warp://</code> هست؛ در Cat Client مستقیم اضافه می‌شود. برای حذف، <code>ENABLE_WARP=false</code> بگذار.</p></details>' +
    '</div>' +
    '<div class="card"><p class="muted" dir="rtl">Cat Panel v' + CAT_PANEL_VERSION + ' · بدون لاگ · ساخته‌شده برای Cat Client · ' + esc(state.host) + '</p></div>' +
    '</section>';
}

/* ------------------------------------------------------------------ */
/* panel client script                                                 */
/* ------------------------------------------------------------------ */

function panelClientJs() {
  return [
    '(function(){',
    'var S=window.CAT_STATE||{};',
    'var $=function(s,r){return (r||document).querySelector(s)};',
    'var $$=function(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s))};',
    'var I18N={',
    ' fa:{subTitle:"لینک سابسکریپشن",stepsTitle:"سه قدم تا اتصال",infoTitle:"اطلاعات اتصال",configsTitle:"همهٔ کانفیگ‌های آماده",singleTitle:"ساخت کانفیگ تکی",cfgBuilderTitle:"تنظیم کانفیگ‌های Cat",connectHowTitle:"چرا وصل نمی‌شود؟ راهنمای اتصال Cat",usersTitle:"کاربران پنل",toolsTitle:"ابزارها و تنظیمات",scannerTitle:"اسکنر آی‌پی تمیز",scannerHowto:"راهنمای نتیجه",dnsTitle:"DNS رمزنگاری‌شده (DoH)",dnsUpstreamTitle:"سرورهای بالادستی",dnsUseTitle:"چطور استفاده کنم؟",dnsCustomTitle:"DoH و DoT سفارشی",dotTitle:"هاست‌های DoT پیشنهادی",helpTitle:"راهنمای پنل",envTitle:"متغیرهای پنل",faqTitle:"پرسش‌های پرتکرار",online:"آنلاین",copied:"کپی شد",scanReady:"آماده.",scanning:"در حال اسکن…",done:"تمام شد"},',
    ' en:{subTitle:"Subscription link",stepsTitle:"Three steps to connect",infoTitle:"Connection details",configsTitle:"Ready-made configs",singleTitle:"Build a single config",cfgBuilderTitle:"Cat config builder",connectHowTitle:"Why does Cat connection fail?",usersTitle:"Panel users",toolsTitle:"Tools & settings",scannerTitle:"Clean-IP scanner",scannerHowto:"How to use the results",dnsTitle:"Encrypted DNS (DoH)",dnsUpstreamTitle:"Upstream resolvers",dnsUseTitle:"How to use it",dnsCustomTitle:"Custom DoH & DoT",dotTitle:"Suggested DoT hosts",helpTitle:"Panel guide",envTitle:"Panel variables",faqTitle:"FAQ",online:"online",copied:"Copied",scanReady:"Ready.",scanning:"Scanning…",done:"Finished"}',
    '};',
    'var lang="fa",theme="dark";',
    'try{lang=localStorage.getItem("catpanel.lang")||"fa";theme=localStorage.getItem("catpanel.theme")||"dark";}catch(e){}',
    'function applyLang(){',
    ' document.documentElement.setAttribute("data-lang",lang);document.body.setAttribute("data-lang",lang);',
    ' document.body.style.direction=lang==="fa"?"rtl":"ltr";',
    ' var d=I18N[lang];',
    ' $$("[data-i18n]").forEach(function(el){var k=el.getAttribute("data-i18n");if(d[k])el.textContent=d[k];});',
    ' $("#langBtn").textContent=lang==="fa"?"EN":"فا";',
    ' $$("[data-nav-label=home]").forEach(function(el){el.textContent=lang==="fa"?"خانه":"Home";});',
    ' $$("[data-nav-label=configs]").forEach(function(el){el.textContent=lang==="fa"?"کانفیگ‌ها":"Configs";});',
    ' $$("[data-nav-label=scanner]").forEach(function(el){el.textContent=lang==="fa"?"اسکنر":"Scanner";});',
    ' $$("[data-nav-label=users]").forEach(function(el){el.textContent=lang==="fa"?"کاربران":"Users";});',
    ' $$("[data-nav-label=tools]").forEach(function(el){el.textContent=lang==="fa"?"ابزارها":"Tools";});',
    ' $$("[data-nav-label=dns]").forEach(function(el){el.textContent="DNS";});',
    ' $$("[data-nav-label=help]").forEach(function(el){el.textContent=lang==="fa"?"راهنما":"Help";});',
    ' $("#brandSub").textContent=lang==="fa"?"پنل کلودفلر شخصی شما":"Your personal Cloudflare panel";',
    ' $("#heroTitle").textContent=lang==="fa"?"پنل فعال است":"Panel is live";',
    ' $("#heroSub").textContent=lang==="fa"?"این Worker روی شبکهٔ کلودفلر اجرا می‌شود؛ با یک لینک، همهٔ دستگاه‌هایت را وصل کن.":"This worker runs on Cloudflare edge; connect every device with one link.";',
    ' $("#onlinePill").textContent=d.online;',
    '}',
    'var THEMES=' + JSON.stringify(PANEL_THEMES.map((t) => ({ id: t.id, fa: t.nameFa, en: t.nameEn }))) + ';',
    'var THEME_BG={violet:"#06030c",oled:"#000000",orchid:"#0b0410",mono:"#0b0b0f",light:"#f6f3fc"};',
    'if(theme==="dark")theme="violet";if(theme==="light")theme="light";',
    'function applyTheme(){document.documentElement.setAttribute("data-theme",theme);',
    ' var m=document.querySelector("meta[name=theme-color]");if(m)m.setAttribute("content",THEME_BG[theme]||"#06030c");',
    ' $$("[data-theme-pick]").forEach(function(b){b.classList.toggle("active",b.getAttribute("data-theme-pick")===theme)});}',
    '$("#themeBtn").addEventListener("click",function(ev){ev.stopPropagation();$("#themeMenu").classList.toggle("show")});',
    'document.addEventListener("click",function(ev){var pick=ev.target.closest("[data-theme-pick]");',
    ' if(pick){theme=pick.getAttribute("data-theme-pick");try{localStorage.setItem("catpanel.theme",theme)}catch(e){}applyTheme();',
    '  $("#themeMenu").classList.remove("show");toast(lang==="fa"?"تم تغییر کرد":"Theme updated");return;}',
    ' if(!ev.target.closest("#themeMenu")&&!ev.target.closest("#themeBtn"))$("#themeMenu").classList.remove("show");});',
    'function toast(msg){var t=$("#toast");$("#toastText").textContent=msg;t.classList.add("show");setTimeout(function(){t.classList.remove("show")},1500);}',
    'function copyText(text){',
    ' if(navigator.clipboard&&navigator.clipboard.writeText){return navigator.clipboard.writeText(text).then(function(){toast(I18N[lang].copied)})}',
    ' var ta=document.createElement("textarea");ta.value=text;document.body.appendChild(ta);ta.select();try{document.execCommand("copy");toast(I18N[lang].copied)}catch(e){}document.body.removeChild(ta);return Promise.resolve();',
    '}',
    'function showTab(name){',
    ' $$("[data-tab]").forEach(function(b){b.classList.toggle("active",b.getAttribute("data-tab")===name)});',
    ' $$(".tab").forEach(function(s){s.classList.toggle("active",s.getAttribute("data-tab-panel")===name)});',
    ' try{localStorage.setItem("catpanel.tab",name)}catch(e){}',
    ' if(name==="users")loadUsers();if(name==="tools"){loadSelf();loadSettings();}',
    ' if(name==="scanner"){}',
    ' try{window.scrollTo({top:0,behavior:"smooth"})}catch(e){try{window.scrollTo(0,0)}catch(e2){}}',
    '}',
    '$$("[data-tab]").forEach(function(btn){btn.addEventListener("click",function(){showTab(btn.getAttribute("data-tab"));var hm=$("#hmenu");if(hm)hm.classList.remove("show")});});',
    ' $("#burgerBtn").addEventListener("click",function(ev){ev.stopPropagation();$("#hmenu").classList.toggle("show")});',
    ' document.addEventListener("click",function(ev){var hm=$("#hmenu");if(hm&&hm.classList.contains("show")&&!ev.target.closest("#hmenu")&&ev.target.id!=="burgerBtn")hm.classList.remove("show")});',
    '$("#langBtn").addEventListener("click",function(){lang=lang==="fa"?"en":"fa";try{localStorage.setItem("catpanel.lang",lang)}catch(e){}applyLang();renderConfigs();renderDns();});',

    'document.addEventListener("click",function(ev){',
    ' var t=ev.target.closest("[data-copy-target]");',
    ' if(t){var el=document.getElementById(t.getAttribute("data-copy-target"));if(el)copyText((el.value!==undefined?el.value:el.textContent).trim());return;}',
    ' var q=ev.target.closest("[data-qr-target]");',
    ' if(q){var el2=document.getElementById(q.getAttribute("data-qr-target"));if(el2)openQr((el2.value!==undefined?el2.value:el2.textContent).trim());return;}',
    '});',
    'function openQr(text){if(!text)return;$("#qrImg").src=S.qrBase+"?d="+encodeURIComponent(text)+"&size=9";$("#qrHint").textContent=text;$("#qrModal").classList.add("show");',
    ' $("#qrCopy").onclick=function(){copyText(text)};}',
    '$("#qrClose").addEventListener("click",function(){$("#qrModal").classList.remove("show")});',
    '$("#qrModal").addEventListener("click",function(e){if(e.target.id==="qrModal")$("#qrModal").classList.remove("show")});',
    '/* ---- config builder (addresses × ports × protocols) ---- */',
    'var OPT=S.configOptions||{addresses:[],ports:[443],sni:S.sni,protocols:["vless","trojan"],includeHost:true};',
    'var TLS_PORTS=S.tlsPorts||[443,2053,2083,2087,2096,8443];',
    'function parseAddrList(text){var seen={},out=[];(text||"").split(/[\\s,;]+/).forEach(function(a){a=a.trim().replace(/^\\[|\\]$/g,"");if(!a||seen[a])return;seen[a]=1;out.push(a)});return out.slice(0,40);}',
    'function readOptions(){',
    ' var ports=$$("#cfgPorts .chip.active").map(function(c){return Number(c.getAttribute("data-port"))});if(!ports.length)ports=[443];',
    ' var protos=$$("#cfgProtos .chip.active[data-proto]").map(function(c){return c.getAttribute("data-proto")});if(!protos.length)protos=["vless"];',
    ' var host=$("#cfgProtos .chip[data-flag=host]").classList.contains("active");',
    ' var sni=($("#cfgSni").value||"").trim().toLowerCase()||S.host;',
    ' var snis=($("#cfgSnis").value||"").split(/[;, ]+/).map(function(s){return s.trim().toLowerCase()}).filter(function(s){return s&&s.indexOf(".")>0&&s.indexOf(":")<0}).slice(0,4);',
    ' var fp=($("#cfgFp")&&$("#cfgFp").value)||"chrome";var v6=!$("#cfgProtos .chip[data-flag=v6]")||$("#cfgProtos .chip[data-flag=v6]").classList.contains("active");',
    ' return {addresses:parseAddrList($("#cfgAddresses").value),ports:ports,protocols:protos,includeHost:host,sni:sni,snis:snis,fingerprint:fp,includeIpv6:v6,locations:OPT.locations||{},country:OPT.country||"",entryLimit:Number($("#cfgCount")&&$("#cfgCount").value)||8,countries:$$("#cfgCountries .chip.active[data-cc]").map(function(c){return c.getAttribute("data-cc")}).filter(Boolean)};}',
    'function subQuery(o){var q=[];if(o.addresses.length)q.push("ips="+encodeURIComponent(o.addresses.join(",")));q.push("ports="+o.ports.join(","));q.push("proto="+o.protocols.join(","));if(o.sni&&o.sni!==S.host)q.push("sni="+encodeURIComponent(o.sni));if(!o.includeHost)q.push("host=0");if(o.fingerprint&&o.fingerprint!=="chrome")q.push("fp="+o.fingerprint);if(o.includeIpv6===false)q.push("v6=0");if(o.snis&&o.snis.length>1)q.push("snis="+encodeURIComponent(o.snis.join(",")));q.push("count="+(o.entryLimit||8));if(o.countries&&o.countries.length)q.push("countries="+o.countries.join(","));var locs=Object.keys(o.locations||{}).map(function(k){return k+"="+o.locations[k]}).join(",");if(locs)q.push("locs="+encodeURIComponent(locs));return "?"+q.join("&");}',
    'var cfgFmt="",cfgSavedInKv=false;',
    'function subUrlFor(fmt){var base="https://"+S.host+"/sub/"+S.uuid+(fmt||"");return cfgSavedInKv?base:base+subQuery(OPT);}',
    'function refreshSubUrl(){var u=subUrlFor(cfgFmt);$("#cfgSubUrl").textContent=u;$("#subUrlText").textContent=subUrlFor("");',
    ' var deep="catclient://add-sub?url="+encodeURIComponent(subUrlFor(""))+"&name="+encodeURIComponent(S.title||"Cat Panel");$("#cfgDeepLink").setAttribute("href",deep);var d2=$("#homeDeepLink");if(d2)d2.setAttribute("href",deep);refreshApps(subUrlFor(""));}',
    'function appLinks(sub){var enc=encodeURIComponent(sub),tag=encodeURIComponent(S.title||"Cat Panel"),base=sub.replace(/\\/?$/,"");',
    ' return {v2rayng:"v2rayng://install-sub?url="+enc+"&name="+tag,v2box:"v2box://install-sub?url="+enc+"&name="+tag,hiddify:"hiddify://import/"+sub+"#"+tag,streisand:"streisand://import/"+sub,v2raytun:"v2raytun://import/"+sub,',
    '  singbox:"sing-box://import-remote-profile?url="+encodeURIComponent(base+"/singbox")+"#"+tag,clash:"clash://install-config?url="+encodeURIComponent(base+"/clash")+"&name="+tag,shadowrocket:"sub://"+btoa(sub)};}',
    'function refreshApps(sub){var L=appLinks(sub);$$(".apps a[data-app]").forEach(function(a){var k=a.getAttribute("data-app");if(L[k])a.setAttribute("href",L[k]);});}',
    'function linkParams(port,kind,sni){var tls=TLS_PORTS.indexOf(Number(port))>=0;var path=kind==="vless"?S.vlessPath:S.trojanPath;',
    ' var common="&type=ws&path="+encodeURIComponent(path)+"&host="+encodeURIComponent(S.host);',
    ' return tls?("security=tls&sni="+encodeURIComponent(sni||OPT.sni||S.sni)+"&fp="+encodeURIComponent(OPT.fingerprint||"chrome")+"&alpn="+encodeURIComponent("http/1.1")+common):("security=none"+common);}',
    'function isV4(a){return /^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(a)}function isV6(a){return a.indexOf(":")>=0}',
    'function addrKind(a){if(a.toLowerCase()===S.host.toLowerCase())return "Domain";if(isV4(a))return "IPv4";if(isV6(a))return "IPv6";return "CDN";}',
    'function locationForAddr(a){var code=(OPT.locations||{})[String(a).toLowerCase()]||OPT.country||"";var x=(S.edgeLocations||{})[String(code).toUpperCase()];if(x)return x;var up=String(code).toUpperCase(),ev=S.edgeLocations||{};for(var ek in ev){if(ev[ek]&&String(ev[ek].iso||"").toUpperCase()===up)return ev[ek]}var countries={DE:["Germany","🇩🇪"],NL:["Netherlands","🇳🇱"],FR:["France","🇫🇷"],GB:["United Kingdom","🇬🇧"],TR:["Turkey","🇹🇷"],US:["United States","🇺🇸"],SG:["Singapore","🇸🇬"],JP:["Japan","🇯🇵"],KR:["South Korea","🇰🇷"],AE:["United Arab Emirates","🇦🇪"]};var c=countries[String(code).toUpperCase()]||["Cloudflare edge","🌐"];return {city:"Auto edge",country:c[0],flag:c[1]};}',
    'function fmtAddr(a){return isV6(a)?"["+a+"]":a}',
    'function vlessLink(addr,name,sni,port){port=port||OPT.ports[0]||443;return "vless://"+S.uuid+"@"+addr+":"+port+"?encryption=none&"+linkParams(port,"vless",sni)+"#"+encodeURIComponent(name);}',
    'function trojanLink(addr,name,sni,port){port=port||OPT.ports[0]||443;return "trojan://"+encodeURIComponent(S.trojanPass)+"@"+addr+":"+port+"?"+linkParams(port,"trojan",sni)+"#"+encodeURIComponent(name);}',
    'function allLinks(){var out=[];var addrs=[],seen={};function push(a){a=String(a).replace(/^\\[/,"").replace(/\\]$/,"");var k=a.toLowerCase();if(!a||seen[k])return;seen[k]=1;addrs.push(a);}',
    ' function poolIps(countries){var pools=S.countryPools||[];var want=countries.map(function(c){return String(c).toUpperCase()});var ips=[],locs={};pools.forEach(function(p){if(want.indexOf(String(p.code||"").toUpperCase())<0)return;(p.ips||[]).forEach(function(ip){ips.push(ip);locs[ip.toLowerCase()]=p.code})});OPT.locations=Object.assign({},OPT.locations||{},locs);return ips;}',
    ' if(OPT.includeHost!==false)push(S.host);var manual=(OPT.addresses||[]);var list=manual.slice();if(OPT.countries&&OPT.countries.length){poolIps(OPT.countries).forEach(function(ip){if(list.indexOf(ip)<0)list.push(ip)})}list.filter(isV4).forEach(push);',
    ' var v6=list.filter(function(a){return !isV4(a)&&isV6(a)});if(OPT.includeIpv6!==false)(v6.length?v6:(S.defaultIpv6||[])).forEach(push);list.filter(function(a){return !isV4(a)&&!isV6(a)}).forEach(push);',
    ' var idx=0;OPT.protocols.forEach(function(k){OPT.ports.forEach(function(p){addrs.forEach(function(h){idx++;var kind=addrKind(h);',
    '  var loc=locationForAddr(h);var name="🐱 Cat · "+loc.country+" · "+(k==="vless"?"VLESS":"Trojan")+" · "+p+" · "+loc.flag;',
    '  out.push({name:name,type:k==="vless"?"VLESS":"Trojan",addr:h,port:p,tls:TLS_PORTS.indexOf(Number(p))>=0,link:k==="vless"?vlessLink(fmtAddr(h),name,OPT.sni,p):trojanLink(fmtAddr(h),name,OPT.sni,p),ms:null});});});});',
    ' out=out.slice(0,Math.max(1,Number(OPT.entryLimit)||8));',
    ' if(S.warp)out.push({name:"🐱 Cat WARP",type:"WARP",addr:"—",port:"",tls:true,link:"warp://#Cat WARP",ms:null});',
    ' return out;}',
    'var CFG=allLinks();',
    'function msClass(ms){return ms===null?"":(ms<0?"bad":(ms<300?"good":(ms<700?"mid":"bad")))}',
    'function renderConfigs(){var q=($("#cfgSearch").value||"").toLowerCase();var rows=CFG.filter(function(c){return !q||c.name.toLowerCase().indexOf(q)>=0||c.addr.toLowerCase().indexOf(q)>=0});',
    ' var html=rows.map(function(c,i){var ms=c.ms===null?"—":(c.ms<0?"✗":c.ms+" ms");return "<tr><td>"+(i+1)+"</td><td>"+c.name+"</td><td dir=ltr>"+c.addr+"</td><td dir=ltr>"+c.port+(c.tls?"":" <span class=pill>http</span>")+"</td><td class=\\"ms "+msClass(c.ms)+"\\" data-cfg-ms=\\""+i+"\\">"+ms+"</td>"+',
    ' "<td><button class=\\"btn tiny\\" data-copy=\\""+encodeURIComponent(c.link)+"\\">کپی</button> <button class=\\"btn ghost tiny\\" data-qr=\\""+encodeURIComponent(c.link)+"\\">QR</button> <a class=\\"btn ghost tiny\\" href=\\"catclient://add-sub?url="+encodeURIComponent(c.link)+"&name="+encodeURIComponent(c.name)+"\\">افزودن</a></td></tr>"}).join("");',
    ' $("#cfgTable").innerHTML=html||"<tr><td colspan=6>موردی نیست</td></tr>";$("#cfgCountLabel").textContent=String(CFG.length);',
    ' $("#cfgAllText").textContent=CFG.map(function(c){return c.link}).join("\\n");',
    '}',
    'document.addEventListener("click",function(ev){var c=ev.target.closest("[data-copy]");if(c){copyText(decodeURIComponent(c.getAttribute("data-copy")));return;}',
    ' var q=ev.target.closest("[data-qr]");if(q){openQr(decodeURIComponent(q.getAttribute("data-qr")));}});',
    '$("#cfgSearch").addEventListener("input",renderConfigs);',
    'function download(name,text){var b=new Blob([text],{type:"text/plain;charset=utf-8"});var a=document.createElement("a");a.href=URL.createObjectURL(b);a.download=name;a.click();setTimeout(function(){URL.revokeObjectURL(a.href)},2000);}',
    '$("#downloadCfg").addEventListener("click",function(){download("cat-panel-configs.txt",CFG.map(function(c){return c.link}).join("\\n"))});',
    '$("#copyAllLinks").addEventListener("click",function(){copyText(CFG.map(function(c){return c.link}).join("\\n"))});',
    '$("#refreshCfg").addEventListener("click",function(){CFG=allLinks();renderConfigs();toast(I18N[lang].done)});',
    '$("#downloadSub").addEventListener("click",function(){fetch(subUrlFor("/raw")).then(function(r){return r.text()}).then(function(t){download("cat-panel-sub.txt",t);toast(I18N[lang].done)})});',
    '$$("#subFormats .chip").forEach(function(chip){chip.addEventListener("click",function(){',
    ' $$("#subFormats .chip").forEach(function(c){c.classList.remove("active")});chip.classList.add("active");',
    ' $("#subUrlText").textContent=subUrlFor(chip.getAttribute("data-fmt")||"");});});',
    'if($("#cfgCopyFragmentHint"))$("#cfgCopyFragmentHint").addEventListener("click",function(){copyText("Fragment: packets=tlshello, length=100-200, interval=1-1");toast(lang==="fa"?"تنظیم Fragment کپی شد":"Fragment settings copied");});',
    '$$("[data-goto-tab]").forEach(function(b){b.addEventListener("click",function(){showTab(b.getAttribute("data-goto-tab"));});});',
    '$$("#cfgSubFormats .chip").forEach(function(chip){chip.addEventListener("click",function(){',
    ' $$("#cfgSubFormats .chip").forEach(function(c){c.classList.remove("active")});chip.classList.add("active");cfgFmt=chip.getAttribute("data-fmt")||"";refreshSubUrl();});});',
    '$$("#cfgPorts .chip, #cfgProtos .chip").forEach(function(chip){chip.addEventListener("click",function(){chip.classList.toggle("active")});});',
    '$("#cfgCountries").addEventListener("click",function(ev2){var chip=ev2.target.closest(".chip");if(!chip||!this.contains(chip))return;var box=chip.parentNode;var isAll=chip.getAttribute("data-cc")==="";$$("#cfgCountries .chip").forEach(function(c){if(isAll){c.classList.toggle("active",c===chip)}else if(c!==chip&&c.getAttribute("data-cc")===""){c.classList.remove("active")}});if(!isAll)chip.classList.toggle("active");if(!box.querySelector(".chip.active"))box.querySelector("[data-cc]").classList.add("active")});',
    '$("#cfgCount").addEventListener("change",applyOptions);',
    '$("#cfgUseDefaults").addEventListener("click",function(){$("#cfgAddresses").value=(S.defaultAddresses||[]).join("\\n")});',
    '$("#cfgUseIr").addEventListener("click",function(){$("#cfgAddresses").value=(S.irIps||[]).slice(0,24).join("\\n")});',
    '$("#cfgClearAddr").addEventListener("click",function(){$("#cfgAddresses").value=""});',
    '$("#cfgFromScan").addEventListener("click",function(){var picked=scanResults.filter(function(r){return r.server&&r.server.ok}).sort(function(a,b){return a.server.ms-b.server.ms}).slice(0,12);if(!picked.length)picked=scanResults.filter(function(r){return r.ms!==null}).sort(function(a,b){return a.ms-b.ms}).slice(0,12);var ips=picked.map(function(r){return r.ip});',
    ' if(!ips.length){toast("اول در تب اسکنر اسکن کن");showTab("scanner");return;}OPT.locations={};picked.forEach(function(r){var code=r.server&&r.server.colo;if(code)OPT.locations[r.ip.toLowerCase()]=code;});$("#cfgAddresses").value=ips.join("\\n");toast(ips.length+" آی‌پی با موقعیت از اسکنر آمد");});',
    'function applyOptions(){OPT=readOptions();cfgSavedInKv=false;CFG=allLinks();renderConfigs();refreshSubUrl();$("#cfgSaveState").textContent="";}',
    '$("#cfgApply").addEventListener("click",function(){applyOptions();toast(CFG.length+" کانفیگ ساخته شد — لینک ساب به‌روز شد");});',
    '$("#cfgSave").addEventListener("click",function(){applyOptions();var o=OPT;',
    ' fetch("/api/settings",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({configs:{addresses:o.addresses,ports:o.ports,protocols:o.protocols,includeHost:o.includeHost,includeIpv6:o.includeIpv6!==false,fingerprint:o.fingerprint||"chrome",sni:o.sni===S.host?"":o.sni,snis:(o.snis||[]).join(","),locations:o.locations||{},country:o.country||"",countryCodes:(o.countries||[]).join(","),entryLimit:o.entryLimit||8}})})',
    ' .then(function(r){return r.json()}).then(function(j){if(j.ok&&j.persisted){cfgSavedInKv=true;refreshSubUrl();$("#cfgSaveState").textContent="ذخیره شد — لینک کوتاه فعال است ✅";toast("در KV ذخیره شد");}',
    '  else{$("#cfgSaveState").textContent=j.ok?"KV وصل نیست — لینک با تنظیمات داخلش استفاده می‌شود":"خطا: "+j.error;}}).catch(function(){$("#cfgSaveState").textContent="خطا در ذخیره";});});',
    'function ccFlag(cc){if(!cc||cc.length!==2)return"";return String.fromCodePoint(127397+cc.charCodeAt(0),127397+cc.charCodeAt(1));}',
    '$("#healthBtn").addEventListener("click",async function(){var b=this;b.disabled=true;$("#healthState").textContent="در حال پینگ آی‌پی‌ها…";',
    ' try{var j=await(await fetch("/api/health-check",{method:"POST"})).json();if(!j.ok)throw new Error(j.error||"failed");',
    ' var html=j.results.map(function(x){var cc=(x.countryCode||"").toUpperCase();var nm=x.countryName||cc||"—";',
    '  return `<div class="config-item"><b>`+(x.ok?"✅":"❌")+" "+ccFlag(cc)+" "+nm+(x.colo?" · "+x.colo:"")+`</b><small dir="ltr">`+x.ip+" · "+(x.ok?(x.ms+"ms"):"مرده — حذف شد")+`</small></div>`;}).join("");',
    ' $("#healthResults").innerHTML=html||`<p class="muted">آی‌پی‌ای برای تست نیست — اول اسکن کن.</p>`;',
    ' $("#healthState").textContent="زنده: "+j.alive+" از "+j.checked+(j.dead.length?" — مرده‌ها از کانفیگ‌ها حذف شدند ✅":"");',
    ' toast("سلامت آی‌پی‌ها چک شد");applyOptions();}catch(e){$("#healthState").textContent="خطا: "+e.message;}b.disabled=false;});',
    '/* browser-side ping of every config address (TCP+TLS reachability from YOUR network) */',
    'function snisQ(){var v=($("#scanSnis")||{}).value||"";v=v.trim();return v?"&snis="+encodeURIComponent(v):""}',
    'function pingAddr(addr,port,timeout){return new Promise(function(resolve){',
    ' var ctrl=typeof AbortController!=="undefined"?new AbortController():null;var started=performance.now();var done=false;',
    ' var timer=setTimeout(function(){if(!done){done=true;if(ctrl)ctrl.abort();resolve(-1)}},timeout);',
    ' var tls=TLS_PORTS.indexOf(Number(port))>=0;var url=(tls?"https":"http")+"://"+addr+":"+port+"/cdn-cgi/trace?_="+Math.random().toString(36).slice(2);',
    ' fetch(url,{mode:"no-cors",cache:"no-store",credentials:"omit",signal:ctrl?ctrl.signal:undefined,redirect:"manual"})',
    ' .then(function(){if(done)return;done=true;clearTimeout(timer);resolve(Math.round(performance.now()-started))})',
    ' .catch(function(err){if(done)return;done=true;clearTimeout(timer);',
    '  /* TypeError = TCP/TLS failed. Any other error (e.g. CORS opaque) means the edge answered. */',
    '  resolve(err&&err.name==="AbortError"?-1:(err&&err.name==="TypeError"?-1:Math.round(performance.now()-started)))});});}',
    '$("#cfgPingAll").addEventListener("click",function(){var btn=this;btn.disabled=true;var idx=0;var list=CFG.filter(function(c){return c.type!=="WARP"});',
    ' function next(){if(idx>=list.length){btn.disabled=false;CFG.sort(function(a,b){var x=a.ms===null||a.ms<0?99999:a.ms,y=b.ms===null||b.ms<0?99999:b.ms;return x-y});renderConfigs();toast("پینگ تمام شد");return;}',
    '  var c=list[idx++];pingAddr(c.addr,c.port,3000).then(function(ms){c.ms=ms;renderConfigs();next();});}',
    ' for(var k=0;k<6;k++)next();});',
    '/* ---- single-config builder ---- */',
    'var singleProto="vless";',
    '$$("#singleProto .chip").forEach(function(chip){chip.addEventListener("click",function(){',
    ' $$("#singleProto .chip").forEach(function(c){c.classList.remove("active")});chip.classList.add("active");',
    ' singleProto=chip.getAttribute("data-proto");buildSingle();});});',
    'function buildSingle(){',
    ' var addr=($("#singleAddr").value||"").trim();var name=($("#singleName").value||"Cat Single").trim();',
    ' var sni=($("#singleSni").value||OPT.sni||S.sni).trim();var port=Number($("#singlePort").value||OPT.ports[0]||443);',
    ' var hostHeader=($("#singleHost").value||S.host).trim();var path=($("#singlePath").value||S.vlessPath).trim();',
    ' if(!addr){toast("آدرس سرور را وارد کن");return "";}',
    ' var tls=TLS_PORTS.indexOf(port)>=0;var sec=tls?("security=tls&sni="+encodeURIComponent(sni)+"&fp=randomized&alpn="+encodeURIComponent("http/1.1")):"security=none";',
    ' if(singleProto==="vless"){',
    '  return "vless://"+S.uuid+"@"+addr+":"+port+"?encryption=none&"+sec+"&type=ws&path="+encodeURIComponent(path)+"&host="+encodeURIComponent(hostHeader)+"#"+encodeURIComponent(name);}',
    ' return "trojan://"+encodeURIComponent(S.trojanPass)+"@"+addr+":"+port+"?"+sec+"&type=ws&path="+encodeURIComponent(path.indexOf("trojan")>=0?path:S.trojanPath)+"&host="+encodeURIComponent(hostHeader)+"#"+encodeURIComponent(name);}',
    '$("#singleBuild").addEventListener("click",function(){var link=buildSingle();if(!link)return;',
    ' $("#singleOut").textContent=link;$("#singleAdd").setAttribute("href","catclient://add-sub?url="+encodeURIComponent(link)+"&name="+encodeURIComponent("Cat Single"));',
    ' $("#singleScan").onclick=function(){location.href="catclient://scan?sni="+encodeURIComponent($("#singleSni").value||S.sni);};',
    ' copyText(link);});',
    '$("#singleCopy").addEventListener("click",function(){var link=$("#singleOut").textContent;if(!link||link==="—"){link=buildSingle();$("#singleOut").textContent=link;}copyText(link);});',
    '$("#singleQr").addEventListener("click",function(){var link=$("#singleOut").textContent;if(!link||link==="—"){link=buildSingle();$("#singleOut").textContent=link;}openQr(link);});',
    '/* ---- users ---- */',
    'function escHtml(v){return String(v).replace(/[&<>]/g,function(c){return c==="&"?"&amp;":(c==="<"?"&lt;":"&gt;")})}',
    'function fmtB(b){b=Number(b)||0;if(b<1024)return b+" B";var u=["KB","MB","GB","TB"],i=-1;do{b/=1024;i++}while(b>=1024&&i<u.length-1);return (b>=100?Math.round(b):b.toFixed(2))+" "+u[i]}',
    'function userRow(u,i){',
    ' var st=u.state||{};var gb=1073741824;var total=u.quotaGb>0?u.quotaGb*gb:0;var used=st.used!==undefined?st.used:(u.usedBytes||0);var pct=total>0?Math.min(100,Math.round(used/total*100)):0;',
    ' var exp=u.expireAt?new Date(u.expireAt).toLocaleDateString("fa-IR")+(st.daysLeft>=0?" ("+st.daysLeft+" روز)":""):"نامحدود";',
    ' var usage="<div dir=ltr style=\'font-size:11.5px\'>"+fmtB(used)+(total>0?" / "+u.quotaGb+" GB":" · ∞")+"</div><div class=\'bar\' style=\'margin-top:4px;min-width:90px\'><i style=\'width:"+pct+"%"+(pct>=90?";background:var(--bad)":"")+"\'></i></div>";',
    ' var status=st.status||(u.enabled===false?"disabled":"active");var badge=status==="active"?"<span class=\'pill ok\'>فعال</span>":(status==="expired"?"<span class=\'pill warn\'>منقضی</span>":(status==="quota-exceeded"?"<span class=\'pill warn\'>حجم تمام</span>":"<span class=\'pill\'>غیرفعال</span>"));',
    ' var online=st.online?"<div class=\'muted\' style=\'font-size:11px\'>🟢 "+st.online+" اتصال زنده</div>":"";',
    ' return "<tr><td>"+(i+1)+"</td><td><b>"+escHtml(u.name||"user")+"</b><div class=\'muted\' style=\'font-size:11px;direction:ltr\'>"+String(u.uuid).slice(0,18)+"…</div>"+online+"</td>"+',
    '  "<td>"+usage+"</td><td>"+exp+"</td><td>"+badge+"</td>"+',
    '  "<td class=\'acts\'><button class=\'btn tiny\' data-user-sub=\'"+u.token+"\'>کپی ساب</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-info=\'"+u.token+"\'>صفحهٔ کاربر</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-qr=\'"+u.token+"\'>QR</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-edit=\'"+u.id+"\'>ویرایش</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-toggle=\'"+u.id+"\' data-enabled=\'"+(u.enabled!==false?1:0)+"\'>"+(u.enabled!==false?"غیرفعال":"فعال")+"</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-reset=\'"+u.id+"\'>ریست مصرف</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-regen=\'"+u.id+"\'>UUID جدید</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-del=\'"+u.id+"\'>حذف</button></td></tr>";}',
    'var USERS=[];',
    'function loadUsers(sync){var tb=$("#userTable");if(!tb)return;',
    ' fetch(S.usersApi+(sync?"?sync=1":"")).then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok){tb.innerHTML="<tr><td colspan=6>"+(j.error==="kv-required"?"بدون KV نمی‌شود کاربر ساخت — یک Namespace بساز و با نام CAT_KV بایند کن.":"خطا: "+j.error)+"</td></tr>";return;}',
    '  USERS=j.users||[];tb.innerHTML=USERS.length?USERS.map(userRow).join(""):"<tr><td colspan=6>هنوز کاربری نساخته‌ای</td></tr>";',
    '  var tot=0;USERS.forEach(function(u){tot+=(u.state&&u.state.used)||u.usedBytes||0});',
    '  if($("#uCount"))$("#uCount").textContent=USERS.length;if($("#uOnline"))$("#uOnline").textContent=j.online||0;if($("#uTotalUsed"))$("#uTotalUsed").textContent=fmtB(tot);',
    ' }).catch(function(){tb.innerHTML="<tr><td colspan=6>دریافت لیست ناموفق بود</td></tr>"});}',
    'setInterval(function(){var a=$("#uAuto");if(a&&a.checked&&!document.hidden&&$("#userTable")&&document.querySelector(".tab.active[data-tab-panel=users]"))loadUsers(true)},20000);',
    'function userPut(id,body){return fetch(S.usersApi+"/"+id,{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify(body)}).then(function(r){return r.json()});}',
    'document.addEventListener("click",function(ev){',
    ' var sub=ev.target.closest("[data-user-sub]");',
    ' if(sub){copyText(location.origin+"/u/"+sub.getAttribute("data-user-sub"));return;}',
    ' var info=ev.target.closest("[data-user-info]");',
    ' if(info){var iu=location.origin+"/info/"+info.getAttribute("data-user-info");copyText(iu);window.open(iu,"_blank");return;}',
    ' var qr=ev.target.closest("[data-user-qr]");',
    ' if(qr){openQr(location.origin+"/u/"+qr.getAttribute("data-user-qr"));return;}',
    ' var ed=ev.target.closest("[data-user-edit]");',
    ' if(ed){var id=ed.getAttribute("data-user-edit");var u=USERS.filter(function(x){return x.id===id})[0]||{};',
    '  var name=prompt("نام کاربر",u.name||"");if(name===null)return;var q=prompt("حجم (GB) — 0 نامحدود",String(u.quotaGb||0));if(q===null)return;',
    '  var d=prompt("انقضا از امروز (روز) — 0 بدون انقضا، خالی = بدون تغییر","");if(d===null)return;var dev=prompt("محدودیت دستگاه — 0 آزاد",String(u.deviceLimit||0));if(dev===null)return;',
    '  pickCountries(u.countries||[]).then(function(cc){if(cc===null)return;',
    '   var body={name:name,quotaGb:Number(q)||0,deviceLimit:Number(dev)||0,countries:cc};if(d.trim()!=="")body.days=Number(d)||0;',
    '   userPut(id,body).then(function(j2){toast(j2.ok?"ذخیره شد":(j2.error||"خطا"));loadUsers();});});return;}',
    ' var tg=ev.target.closest("[data-user-toggle]");',
    ' if(tg){userPut(tg.getAttribute("data-user-toggle"),{enabled:tg.getAttribute("data-enabled")!=="1"}).then(function(){loadUsers()});return;}',
    ' var reset=ev.target.closest("[data-user-reset]");',
    ' if(reset){if(!confirm("مصرف این کاربر صفر شود؟"))return;userPut(reset.getAttribute("data-user-reset"),{usedBytes:0,usedRequests:0}).then(function(){loadUsers()});return;}',
    ' var rg=ev.target.closest("[data-user-regen]");',
    ' if(rg){if(!confirm("UUID و لینک ساب این کاربر عوض شود؟ لینک قبلی از کار می‌افتد."))return;fetch(S.usersApi+"/"+rg.getAttribute("data-user-regen")+"/regenerate",{method:"POST"}).then(function(r){return r.json()}).then(function(j){toast(j.ok?"لینک جدید ساخته شد":(j.error||"خطا"));loadUsers();});return;}',
    ' var del=ev.target.closest("[data-user-del]");',
    ' if(del){if(!confirm("کاربر حذف شود؟"))return;fetch(S.usersApi+"/"+del.getAttribute("data-user-del"),{method:"DELETE"}).then(loadUsers);return;}});',
    'var CHIP_CODES=[].concat((S.countryPools||[]).filter(function(p){return p.code}).map(function(p){return p.code}),["NL","DE","FR","US","GB","TR","SE","JP","SG","AE"]).filter(function(v,i,a){return a.indexOf(v)===i}).slice(0,16);',
    'var ucBox=$("#uCountryChips");if(ucBox){ucBox.innerHTML=CHIP_CODES.map(function(cc){return `<button type=\'button\' class=\'chip\' data-ucc=\'>`+cc+`>`+(flagOf(cc)||"")+" "+cc+"</button>"}).join("");}',
    'if(ucBox)ucBox.addEventListener("click",function(ev){var b=ev.target.closest("[data-ucc]");if(!b)return;b.classList.toggle("active");var codes=$$("#uCountryChips .chip.active").map(function(x){return x.getAttribute("data-ucc")});var extra=($("#uCountries").value||"").split(/[;, ]+/).map(function(s){return s.trim().toUpperCase()}).filter(function(s){return s&&CHIP_CODES.indexOf(s)<0});$("#uCountries").value=codes.concat(extra).join(",");});',
    'function pickCountries(current){var codes=CHIP_CODES.slice();(current||[]).forEach(function(c){if(codes.indexOf(String(c).toUpperCase())<0)codes.push(String(c).toUpperCase())});',
    ' return new Promise(function(resolve){var ov=document.createElement("div");ov.style.cssText="position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99;display:flex;align-items:center;justify-content:center;padding:18px";',
    '  var box=document.createElement("div");box.className="card glow";box.style.cssText="max-width:430px;width:100%";',
    '  box.innerHTML=`<h2 style=\'margin-bottom:10px\'>🌍 کشورهای این کاربر</h2><div class=\'chips\' id=\'pkChips\'></div><div class=\'row\' style=\'margin-top:12px\'><button class=\'btn\' id=\'pkSave\'>ذخیره</button><button class=\'btn ghost\' id=\'pkCancel\'>انصراف</button></div>`;',
    '  ov.appendChild(box);document.body.appendChild(ov);var chosen=(current||[]).map(function(c){return String(c).toUpperCase()});var chipsBox=box.querySelector("#pkChips");',
    '  function paint(){chipsBox.innerHTML=codes.map(function(cc){return `<button type=\'button\' class=\'chip\'+(chosen.indexOf(cc)>=0?" active":"")+\' data-pk=\'>`+cc+`>`+(flagOf(cc)||"")+" "+cc+"</button>"}).join("");}',
    '  paint();chipsBox.addEventListener("click",function(ev){var b=ev.target.closest("[data-pk]");if(!b)return;var cc=b.getAttribute("data-pk");var i=chosen.indexOf(cc);if(i>=0)chosen.splice(i,1);else chosen.push(cc);paint();});',
    '  box.querySelector("#pkSave").onclick=function(){document.body.removeChild(ov);resolve(chosen.join(","))};',
    '  box.querySelector("#pkCancel").onclick=function(){document.body.removeChild(ov);resolve(null)};',
    ' });}',
    'if($("#uCreate"))$("#uCreate").addEventListener("click",function(){',
    ' fetch(S.usersApi,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({name:($("#uName").value||"user").trim(),quotaGb:Number($("#uQuota").value||0),days:Number($("#uDays").value||0),deviceLimit:Number($("#uDevices").value||0),countries:($("#uCountries").value||"")})})',
    ' .then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok){toast(j.hint||j.error||"خطا");return;}var il=location.origin+"/info/"+j.user.token;toast(j.user.countries&&j.user.countries.length?"کاربر ساخته شد — لینک صفحهٔ کاربر (انتخاب کانفیگ) کپی شد":"کاربر ساخته شد — هنوز کشوری ندارد؛ ویرایش کن و کشور بگذار");copyText(il);$("#uName").value="";$("#uCountries").value="";loadUsers();});});',
    'if($("#uReload"))$("#uReload").addEventListener("click",loadUsers);',
    '/* ---- tools ---- */',
    'function loadSelf(){var tb=$("#selfTable");if(!tb)return;',
    ' fetch("/api/self").then(function(r){return r.json()}).then(function(j){',
    '  var rows=[["آی‌پی",j.ip],["کشور",(j.country||"—")+" / "+(j.city||"—")],["کولو",j.colo],["ASN",j.asn],["TLS",j.tlsVersion],["HTTP",j.httpProtocol],["نسخهٔ پنل",j.version]];',
    '  tb.innerHTML=rows.map(function(r){return "<tr><td>"+r[0]+"</td><td dir=ltr>"+(r[1]||"—")+"</td></tr>"}).join("");});}',
    'if($("#selfReload"))$("#selfReload").addEventListener("click",loadSelf);',
    'function loadSettings(){fetch("/api/settings").then(function(r){return r.json()}).then(function(j){if(!j.ok)return;',
    ' var st=j.settings;$("#tTitle").value=st.title||"";$("#tDns").value=(st.dns&&st.dns.upstream)||"";',
    ' $("#tProxyIps").value=((st.tunnel&&st.tunnel.proxyIps)||[]).join(",");',
    ' $("#tResult").textContent=j.hasKv?"KV متصل است":"KV وصل نیست — تغییرات فقط تا ری‌استارت زنده می‌ماند";});}',
    'if($("#tSave"))$("#tSave").addEventListener("click",function(){',
    ' var payload={title:$("#tTitle").value.trim(),dns:{upstream:$("#tDns").value.trim()},tunnel:{proxyIps:($("#tProxyIps").value||"").split(",").map(function(x){return x.trim()}).filter(Boolean)}};',
    ' var pass=$("#tPass").value;if(pass)payload.panelPassword=pass;var puser=$("#tUser").value;if(puser)payload.panelUser=puser;',
    ' fetch("/api/settings",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(payload)})',
    '  .then(function(r){return r.json()}).then(function(j){',
    '   $("#tResult").textContent=j.ok?(j.persisted?"ذخیره شد ✅":"در KV ذخیره نشد (بایندینگ KV نداری)"):("خطا: "+j.error);',
    '   if(j.ok&&j.settings&&j.settings.title){var b=$("#brandName");if(b)b.textContent=j.settings.title;}});});',
    'if($("#tBackup"))$("#tBackup").addEventListener("click",function(){',
    ' fetch("/api/backup").then(function(r){return r.json()}).then(function(j){download("cat-panel-backup.json",JSON.stringify(j,null,2));toast("بکاپ گرفته شد")});});',
    'if($("#tRestoreBtn")&&$("#tRestoreFile")){',
    ' $("#tRestoreBtn").addEventListener("click",function(){$("#tRestoreFile").click()});',
    ' $("#tRestoreFile").addEventListener("change",function(ev){var f=ev.target.files[0];if(!f)return;var reader=new FileReader();',
    '  reader.onload=function(){fetch("/api/backup",{method:"POST",headers:{"content-type":"application/json"},body:String(reader.result)})',
    '   .then(function(r){return r.json()}).then(function(j){toast(j.ok?"بازیابی شد":"خطا");loadUsers();loadSettings();});};',
    '  reader.readAsText(f);});}',
    'if($("#scanServer"))$("#scanServer").addEventListener("click",function(){',
    ' var out=$("#selfScanOut");out.textContent="اسکن ۳۲ آی‌پی…";',
    ' var ips=(S.irIps||[]).slice(0,32).join(",");',
    ' fetch("/api/scan?ips="+encodeURIComponent(ips)+"&concurrency=16&timeout=4000").then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok){out.textContent="خطا: "+j.error;return;}',
    '  out.textContent=j.results.map(function(r){return r.ip+"  "+(r.ok?r.ms+" ms"+(r.colo?"  "+r.colo:""):"x")}).join("\\n");',
    '  toast(j.alive+" آی‌پی پاسخ داد");});});',
    'if($("#irIpsUse"))$("#irIpsUse").addEventListener("click",function(){',
    ' $("#cfgAddresses").value=(S.irIps||[]).slice(0,24).join("\\n");applyOptions();showTab("configs");toast("کتابخانهٔ ایران داخل کانفیگ‌ها گذاشته شد");});',
    '/* ---- scanner ---- */',
    'function sampleTargets(limit,custom){var list=(custom&&custom.length?custom:(S.scanTargets||[])).slice();',
    ' for(var i=list.length-1;i>0;i--){var j=Math.floor(Math.random()*(i+1));var t=list[i];list[i]=list[j];list[j]=t;}',
    ' return limit&&list.length>limit?list.slice(0,limit):list;}',
    'function perRange(){return Math.max(1,Math.min(64,Number($("#scanPerRange")&&$("#scanPerRange").value)||8));}',
    '/* Range-first expansion: every CIDR is split into `per` equal slices and one',
    '   random host is drawn from each slice, so each run tests fresh addresses. */',
    'function expandCustom(text,per){per=per||perRange();var out=[],seen={};function push(ip){if(!seen[ip]){seen[ip]=1;out.push(ip)}}',
    ' (text||"").split(/[\\s,;]+/).forEach(function(item){',
    ' item=item.trim();if(!item)return;',
    ' if(item.indexOf("/")<0){if(/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(item))push(item);return;}',
    ' var p=item.split("/"),parts=p[0].split(".").map(Number),prefix=Number(p[1]);',
    ' if(parts.length!==4||parts.some(function(n){return isNaN(n)||n<0||n>255})||prefix<8||prefix>32)return;',
    ' var base=((parts[0]<<24)>>>0)+(parts[1]<<16)+(parts[2]<<8)+parts[3];var hostBits=32-prefix;var size=Math.pow(2,hostBits);base=base-(base%size);',
    ' var total=Math.pow(2,Math.min(hostBits,20));var want=Math.max(1,Math.min(per,total-1));var slice=total/want;',
    ' for(var i=0;i<want;i++){var off=Math.floor(i*slice+Math.random()*slice);if(off<1)off=1;if(off>total-1)off=total-1;if((off&255)===0)off+=1;else if((off&255)===255)off-=1;var v=(base+off)>>>0;push([(v>>>24)&255,(v>>>16)&255,(v>>>8)&255,v&255].join("."));}});return out;}',
    'if($("#scanRangesReset"))$("#scanRangesReset").addEventListener("click",function(){$("#scanCustom").value=(S.scanRanges||[]).join(", ");toast(lang==="fa"?"رنج‌های پیش‌فرض برگشت":"Default ranges restored");});',
    'var scanResults=[],scanRunning=false,scanAbort=null;',
    '/* Browser probe. HTTP:80 gives a real round-trip on Cloudflare edges (they answer',
    '   /cdn-cgi/trace on plain HTTP). HTTPS:443 only proves TCP+TLS reachability because',
    '   the certificate never matches a bare IP. TypeError => unreachable; anything else',
    '   (opaque response, CORS error) => the edge answered. */',
    'function pingIp(ip,timeout,mode){return new Promise(function(resolve){',
    ' var ctrl=typeof AbortController!=="undefined"?new AbortController():null;',
    ' var started=(performance&&performance.now)?performance.now():Date.now();',
    ' var done=false;var timer=setTimeout(function(){if(!done){done=true;if(ctrl)ctrl.abort();resolve(null)}},timeout);',
    ' var url=(mode==="https"?"https://"+ip+":443":"http://"+ip+":80")+"/cdn-cgi/trace?ts="+Math.random().toString(36).slice(2);',
    ' fetch(url,{mode:"no-cors",cache:"no-store",credentials:"omit",redirect:"manual",signal:ctrl?ctrl.signal:undefined})',
    ' .then(function(){if(done)return;done=true;clearTimeout(timer);resolve(Math.round(((performance&&performance.now)?performance.now():Date.now())-started))})',
    ' .catch(function(err){if(done)return;done=true;clearTimeout(timer);',
    '  if(err&&err.name==="AbortError"){resolve(null);return;}',
    '  if(err&&err.name==="TypeError"){resolve(null);return;}',
    '  resolve(Math.round(((performance&&performance.now)?performance.now():Date.now())-started));});});}',
    'function renderScan(){var rows=scanResults.map(function(r,i){var cls=r.ms===null?"bad":(r.ms<300?"good":(r.ms<700?"mid":"bad"));',
    ' var msText=r.ms===null?"✗":(r.ms+" ms");var loc=r.server&&r.server.location?(r.server.location.flag+" "+r.server.location.city+", "+r.server.location.country):(r.server&&r.server.colo?r.server.colo:"🌐 Auto");var srv=r.server===undefined?"—":(r.server&&r.server.ok?("✓ "+(r.server.ms||"")+"ms"):"✗");',
    ' var sniRow=(r.server&&r.server.snisOk)?Object.keys(r.server.snisOk).filter(function(s){return r.server.snisOk[s].ok}).map(function(s){return "✓ "+s}).join("<br>"):"";',
    ' return `<tr><td><input type="checkbox" style="width:auto" data-ip-check="`+r.ip+`"${r.selected?" checked":""}></td><td dir="ltr"><b>`+r.ip+`</b><br><small>`+loc+`</small>${sniRow?"<small>"+`+sniRow+`+"</small>":""}</td><td class="ms ${cls}">${msText}</td><td class="ms ${r.server&&r.server.ok?"good":(r.server===undefined?"":"bad")}">${srv}</td><td><button class="btn ghost tiny" data-copy-ip="`+r.ip+`">کپی</button> <button class="btn tiny" data-use-ip="`+r.ip+`">انتخاب</button></td></tr>`;}).join("");',
    ' $("#scanTable").innerHTML=rows||"<tr><td colspan=5>هنوز نتیجه‌ای نیست</td></tr>";',
    '}',
    'document.addEventListener("click",function(ev){',
    ' var c=ev.target.closest("[data-copy-ip]");if(c){copyText(c.getAttribute("data-copy-ip"));return;}',
    ' var u=ev.target.closest("[data-use-ip]");if(u){var ip=u.getAttribute("data-use-ip");scanResults.forEach(function(r){if(r.ip===ip)r.selected=!r.selected});renderScan();return;}',
    '});',
    'document.addEventListener("change",function(ev){var cb=ev.target.closest("[data-ip-check]");if(cb){var ip=cb.getAttribute("data-ip-check");var hit=scanResults.filter(function(r){return r.ip===ip})[0];var reachable=!!hit&&(hit.ms!==null||(hit.server&&hit.server.ok));if(cb.checked&&!reachable){cb.checked=false;toast("این آی‌پی زنده نیست — فقط سبزها را تیک بزن");}else{scanResults.forEach(function(r){if(r.ip===ip)r.selected=cb.checked});updateSelCount();}}',
    ' if(ev.target.id==="scanAll"){scanResults.forEach(function(r){r.selected=ev.target.checked&&(r.ms!==null||(r.server&&r.server.ok))});renderScan();updateSelCount();}});',
    '$("#scanClear").addEventListener("click",function(){scanResults=[];renderScan();$("#scanStatus").textContent=I18N[lang].scanReady;$("#scanBar").style.width="0"});',
    '$("#scanPickBest").addEventListener("click",function(){var alive=scanResults.filter(function(r){return (r.ms!==null)||(r.server&&r.server.ok)});',
    ' alive.sort(function(a,b){var x=a.ms!==null?a.ms:((a.server&&a.server.ms)||99999),y=b.ms!==null?b.ms:((b.server&&b.server.ms)||99999);return x-y});scanResults.forEach(function(r){r.selected=false});alive.slice(0,8).forEach(function(r){r.selected=true});renderScan();updateSelCount();toast(alive.length?(Math.min(8,alive.length)+" تندترین آی‌پی انتخاب شد"):"آی‌پی زنده‌ای نیست");});',
    '$("#scanStop").addEventListener("click",function(){scanRunning=false;if(scanAbort)scanAbort.abort();$("#scanStatus").textContent="متوقف شد.";$("#scanStart").disabled=false;$("#scanStop").disabled=true;});',
    'function finishScan(){scanRunning=false;$("#scanStart").disabled=false;$("#scanStop").disabled=true;',
    ' scanResults.sort(function(a,b){if(a.ms===null&&b.ms===null)return 0;if(a.ms===null)return 1;if(b.ms===null)return -1;return a.ms-b.ms});renderScan();',
    ' var alive=scanResults.filter(function(r){return r.ms!==null});',
    ' $("#scanStatus").textContent=I18N[lang].done+" · "+alive.length+" آی‌پی سالم از "+scanResults.length+(alive.length?" — حالا «گذاشتن داخل کانفیگ‌ها» را بزن":" — حالت HTTP را امتحان کن یا رنج دلخواه بده");',
    ' if(alive.length)verifyOnServer(alive.slice(0,24).map(function(r){return r.ip}));}',
    'function verifyOnServer(ips){if(!ips.length)return;',
    ' fetch("/api/scan?ips="+encodeURIComponent(ips.join(","))+"&timeout=4000&concurrency=12").then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok)return;var map={};(j.results||[]).forEach(function(r){map[r.ip]=r});',
    '  scanResults.forEach(function(r){if(map[r.ip])r.server=map[r.ip]});renderScan();}).catch(function(){});}',
    '$("#scanStart").addEventListener("click",function(){',
    ' if(scanRunning)return;',
    ' var mode=$("#scanMode").value||"http";var conc=Math.max(1,Math.min(32,Number($("#scanConc").value)||8));',
    ' var timeout=Math.max(500,Math.min(8000,Number($("#scanTimeout").value)||2000));var limit=Math.max(4,Math.min(400,Number($("#scanLimit").value)||60));',
    ' var custom=expandCustom($("#scanCustom").value);var targets=sampleTargets(limit,custom);',
    ' if(!targets.length){toast("آی‌پی‌ای برای اسکن نیست");return;}',
    ' scanRunning=true;scanAbort=typeof AbortController!=="undefined"?new AbortController():null;scanResults=[];renderScan();',
    ' $("#scanStart").disabled=true;$("#scanStop").disabled=false;$("#scanStatus").textContent=I18N[lang].scanning+" 0/"+targets.length;',
    ' var index=0,done=0;',
    ' function next(){',
    '  if(!scanRunning)return;',
    '  if(index>=targets.length){if(done>=targets.length)finishScan();return;}',
    '  var ip=targets[index++];',
    '  pingIp(ip,timeout,mode).then(function(ms){if(!scanRunning)return;done++;scanResults.push({ip:ip,ms:ms,selected:ms!==null&&ms<400});',
    '   var pct=Math.round(done/targets.length*100);$("#scanBar").style.width=pct+"%";',
    '   var alive=scanResults.filter(function(r){return r.ms!==null});var best=alive.length?Math.min.apply(null,alive.map(function(r){return r.ms})):null;',
    '   $("#scanStatus").textContent=(lang==="fa"?"در حال اسکن… ":"Scanning… ")+done+"/"+targets.length+" ("+pct+"%)"+" · "+alive.length+" سالم"+(best!==null?(" · "+(lang==="fa"?"بهترین: ":"best: ")+best+"ms"):"");',
    '   if(done%4===0||done===targets.length)renderScan();next();});',
    ' }',
    ' for(var k=0;k<conc;k++)next();',
    '});',
    '$("#scanServerAll").addEventListener("click",function(){var btn=this;btn.disabled=true;',
    ' var custom=expandCustom($("#scanCustom").value);var targets=sampleTargets(Math.min(96,Number($("#scanLimit").value)||80),custom);',
    ' $("#scanStatus").textContent="اسکن از ورکر روی "+targets.length+" آی‌پی…";',
    ' fetch("/api/scan?ips="+encodeURIComponent(targets.join(","))+"&timeout=4000&concurrency=16&save=1").then(function(r){return r.json()}).then(function(j){btn.disabled=false;',
    '  if(!j.ok){$("#scanStatus").textContent="خطا: "+j.error;return;}',
    '  var existing={};scanResults.forEach(function(r){existing[r.ip]=r});',
    '  (j.results||[]).forEach(function(r){if(existing[r.ip]){existing[r.ip].server=r;}else{scanResults.push({ip:r.ip,ms:null,server:r,selected:r.ok});}});',
    '  scanResults.sort(function(a,b){var x=a.server&&a.server.ok?a.server.ms:99999,y=b.server&&b.server.ok?b.server.ms:99999;return x-y});renderScan();',
    '  $("#scanStatus").textContent="ورکر: "+j.alive+" آی‌پی برای دامنهٔ پنل جواب دادند"+(j.saved?" و در ساب ذخیره شدند":" (KV ذخیره نشد)")+". برای سرعت واقعی، اسکن مرورگر را هم بزن.";',
    '  mergePools(j.results||[]);renderPoolUi();',
    '  var alive=(j.results||[]).filter(function(r){return r.ok}).sort(function(a,b){return (a.ms||9999)-(b.ms||9999)}).slice(0,24);',
    '  if(alive.length){var cur=parseAddrList($("#cfgAddresses").value);alive.forEach(function(r){if(OPT.locations&&r.colo)OPT.locations[String(r.ip).toLowerCase()]=r.colo;if(cur.indexOf(r.ip)<0)cur.push(r.ip)});$("#cfgAddresses").value=cur.slice(0,40).join("\\n");applyOptions();toast(alive.length+" آی‌پی موفق خودکار به کانفیگ‌ها اضافه شد ✅");}',
    ' }).catch(function(){btn.disabled=false;$("#scanStatus").textContent="اسکن ورکر ناموفق بود";});});',
    'function flagOf(code){var x=(S.edgeLocations||{});for(var k in x){if(x[k]&&String(x[k].iso||"").toUpperCase()===String(code||"").toUpperCase())return x[k].flag}return "";}' +
'function mergePools(results){var map={};(S.countryPools||[]).forEach(function(p){map[p.code||"-"]=p});' +
' results.forEach(function(r){if(!r.ok||!r.ip)return;var code=String(r.countryCode||"").toUpperCase();var key=code||"-";var p=map[key]||(map[key]={code:code,name:r.countryName||"Cloudflare edge",flag:flagOf(code),count:0,ips:[]});if(!p.flag)p.flag=code?flagOf(code):"";if(p.ips.indexOf(r.ip)<0){p.ips.push(r.ip);p.count+=1}});' +
' S.countryPools=Object.keys(map).map(function(k){return map[k]}).sort(function(a,b){return b.count-a.count});}' +
    'function renderPoolUi(){var el=$("#countryPools");var pools=S.countryPools||[]; if(el){el.innerHTML=pools.length?pools.map(function(p){return `<div class="config-group"><h3><span>`+(p.flag||"")+" "+p.name+`</span><span class="cnt">`+p.count+` IP</span></h3><div class="tags">`+p.ips.map(function(ip){return `<span class="pill" dir="ltr">`+ip+`</span>`}).join("")+(p.count>p.ips.length?`<span class="pill">…</span>`:"")+`</div></div>`}).join(""):`<p class="muted">هنوز IPای دسته‌بندی نشده — یک بار «اسکن از ورکر» را بزن.</p>`;} var box=$("#cfgCountries");if(box){var chips=`<button class="chip active" type="button" data-cc="">همه</button>`+pools.filter(function(p){return p.code}).map(function(p){return `<button class="chip" type="button" data-cc="`+p.code+`">`+(p.flag||"")+" "+p.name+" · "+p.count+`</button>`}).join("");box.innerHTML=chips;}}',
    'function updateSelCount(){var el=$("#scanSelCount");if(el)el.textContent=selectedIps().length+" انتخاب";}',
'function selectedIps(){return scanResults.filter(function(r){return r.selected&&(r.server===undefined?r.ms!==null:r.server&&r.server.ok)}).map(function(r){return r.ip})}',
    '$("#copyBestIps").addEventListener("click",function(){var top=scanResults.filter(function(r){return r.server===undefined?r.ms!==null:r.server&&r.server.ok}).sort(function(a,b){return (a.server?a.server.ms:a.ms)-(b.server?b.server.ms:b.ms)}).slice(0,10).map(function(r){return r.ip});if(!top.length){toast("نتیجه‌ای نیست");return;}copyText(top.join("\\n"))});',
    '$("#useIpsInConfigs").addEventListener("click",function(){var ips=selectedIps();if(!ips.length){toast("اول چند آی‌پی را تیک بزن");return;}',
    ' ips.forEach(function(ip){var hit=scanResults.filter(function(r){return r.ip===ip})[0];if(hit&&hit.server&&hit.server.colo&&OPT.locations)OPT.locations[ip.toLowerCase()]=hit.server.colo});',
    ' var cur=parseAddrList($("#cfgAddresses").value);ips.forEach(function(ip){if(cur.indexOf(ip)<0)cur.push(ip)});$("#cfgAddresses").value=cur.slice(0,40).join("\\n");',
    ' if(ips.filter(function(ip){var h=scanResults.filter(function(r){return r.ip===ip})[0];return !(h&&h.server&&h.server.ok&&h.server.colo)}).length)verifyOnServer(ips);',
    ' applyOptions();showTab("configs");toast(ips.length+" آی‌پی به کانفیگ‌ها اضافه شد — لینک ساب به‌روز است");});',
    '$("#buildFromIps").addEventListener("click",function(){var ips=selectedIps();if(!ips.length){toast("اول چند آی‌پی را انتخاب کن");return;}',
    ' var lines=[];ips.forEach(function(ip){var loc=locationForAddr(ip);OPT.ports.forEach(function(p){if(OPT.protocols.indexOf("vless")>=0)lines.push(vlessLink(ip,"🐱 Cat · "+loc.country+" · VLESS · "+p+" · "+loc.flag,OPT.sni,p));if(OPT.protocols.indexOf("trojan")>=0)lines.push(trojanLink(ip,"🐱 Cat · "+loc.country+" · Trojan · "+p+" · "+loc.flag,OPT.sni,p));})});',
    ' copyText(lines.join("\\n"));toast(lines.length+" کانفیگ کپی شد");',
    ' if(confirm("این آی‌پی‌ها را به‌عنوان فرانتینگ در اپ Cat Client هم اعمال کنم؟")){location.href="catclient://scan?sni="+encodeURIComponent(S.host)+"&ip="+encodeURIComponent(ips.join(","));}});',
    '/* ---- DNS ---- */',
    'function renderDns(){var d=I18N[lang];var rows=(S.dnsPresets||[]).map(function(p){',
    ' var current=p.url===S.dnsUpstream?" <span class=pill>پیش‌فرض</span>":"";',
    ' return "<tr><td>"+p.name+current+"</td><td dir=ltr><code>"+p.url+"</code></td><td class=ms data-dns-ms=\\""+p.url+"\\">—</td>"+',
    ' "<td><button class=\\"btn ghost tiny\\" data-copy=\\""+encodeURIComponent(p.url)+"\\">کپی</button> <button class=\\"btn tiny\\" data-dns-test=\\""+p.url+"\\">تست</button></td></tr>"}).join("");',
    ' $("#dnsTable").innerHTML=rows;}',
    'function probeDns(url,rowEl,btn){if(btn)btn.disabled=true;rowEl.textContent="…";',
    ' fetch("/api/dns-probe?u="+encodeURIComponent(url)).then(function(r){return r.json()}).then(function(j){',
    '  rowEl.textContent=j.ok?(j.ms+" ms"):("خطا");rowEl.className="ms "+(j.ok?(j.ms<80?"good":(j.ms<200?"mid":"bad")):"bad");if(btn)btn.disabled=false;',
    ' }).catch(function(){rowEl.textContent="خطا";if(btn)btn.disabled=false});}',
    'document.addEventListener("click",function(ev){var t=ev.target.closest("[data-dns-test]");if(!t)return;',
    ' var url=t.getAttribute("data-dns-test");var row=document.querySelector("[data-dns-ms=\\""+url+"\\"]");if(row)probeDns(url,row,t);});',
    'function dnsResult(html){$("#dnsCustomResult").innerHTML=html;}',
    '$("#dohCustomTest").addEventListener("click",function(){',
    ' var url=($("#dohCustom").value||"").trim();if(!url){toast("آدرس DoH را وارد کن");return;}',
    ' dnsResult("در حال تست…");',
    ' fetch("/api/dns-probe?u="+encodeURIComponent(url)).then(function(r){return r.json()}).then(function(j){',
    '  dnsResult(j.ok?("<b>DoH سالم</b> — تأخیر "+j.ms+"ms"+(j.answers?(" · نمونه پاسخ: "+j.answers):"")):"DoH پاسخ نداد — "+(j.error||"خطا"));',
    ' }).catch(function(){dnsResult("تست ناموفق")});});',
    '$("#dohCustomApply").addEventListener("click",function(){',
    ' var url=($("#dohCustom").value||"").trim();if(!url){toast("آدرس DoH را وارد کن");return;}',
    ' try{localStorage.setItem("catpanel.dohCustom",url)}catch(e){}',
    ' dnsResult("در این نسخه، سرور بالادستی با متغیر <code>DNS_UPSTREAM</code> عوض می‌شود: <code>"+url+"</code><br>می‌توانی همین را در Variables ورکر بگذاری، یا موقتاً از <code>/dns-query?u="+url+"</code> استفاده کنی.");',
    ' copyText(url);});',
    'function checkDot(){var host=($("#dotCustom").value||"").trim();if(!host){toast("هاست DoT را وارد کن");return;}',
    ' dnsResult("در حال بررسی "+host+" …");',
    ' fetch("/api/resolve?host="+encodeURIComponent(host)).then(function(r){return r.json()}).then(function(j){',
    '  dnsResult(j.ok?("<b>DoT قابل استفاده است</b> — "+host+" → "+(j.answers||[]).join(", ")):"رزولوشن ناموفق — "+(j.error||"خطا"));',
    '  copyText(host);}).catch(function(){dnsResult("بررسی ناموفق")});}',
    '$("#dotCustomCheck").addEventListener("click",checkDot);',
    'document.addEventListener("click",function(ev){var d=ev.target.closest("[data-dot]");if(d){var h=d.getAttribute("data-dot");',
    ' $("#dotCustom").value=h;checkDot();}});',
    '$("#dohTest").addEventListener("click",function(){var status=$("#dohStatus");status.textContent="در حال تست…";',
    ' var list=(S.dnsPresets||[]);var pending=list.length;var best=null;',
    ' list.forEach(function(p){var row=document.querySelector("[data-dns-ms=\\""+p.url+"\\"]");if(!row)return;',
    '  probeDns(p.url,row,null);var wait=setInterval(function(){if(row.textContent.indexOf("ms")>0){clearInterval(wait);pending--;',
    '    var ms=parseInt(row.textContent,10);if(!isNaN(ms)&&(!best||ms<best.ms))best={name:p.name,ms:ms};',
    '    if(pending<=0)status.textContent=best?("سریع‌ترین: "+best.name+" ("+best.ms+"ms)"):"تست ناموفق";}},400);});});',
    '/* ---- misc ---- */',
    '$("#copyCode").addEventListener("click",function(){var btn=this;var urls=[].concat(["' + CAT_CODE_URLS.join('","') + '"]);',
    ' (function tryNext(i){if(i>=urls.length){btn.textContent="کپی نشد — از گیت‌هاب بگیر";return;}',
    '  fetch(urls[i]).then(function(r){if(!r.ok)throw 0;return r.text()}).then(function(t){if(t.length<2000)throw 0;return copyText(t)}).then(function(){btn.textContent="✓ کپی شد"}).catch(function(){tryNext(i+1)});})(0);});',
    '/* defaults: scan the panel host as SNI and start from the deployed clean IPs */',
    'if($("#scanSni")&&!$("#scanSni").value)$("#scanSni").value=S.sni||"";',
    '$$("#scanSniSuggestions [data-sni-suggestion]").forEach(function(chip){chip.addEventListener("click",function(){',
    '  $("#scanSni").value=chip.getAttribute("data-sni-suggestion")||"";',
    '});});',
    'applyLang();applyTheme();renderConfigs();renderDns();refreshSubUrl();',
    'if(S.hasKv&&S.configOptions&&S.configOptions.fromKv){cfgSavedInKv=true;refreshSubUrl();}',
    'var savedTab=null;try{savedTab=localStorage.getItem("catpanel.tab")}catch(e){}',
    'if(savedTab)showTab(savedTab);else showTab("home");',
    '})();',
  ].join('\n');
}

/* ------------------------------------------------------------------ */
/* request routing                                                     */
/* ------------------------------------------------------------------ */

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': '*',
};

function isIpLiteral(value) {
  const raw = String(value || '').trim();
  if (!raw) return false;
  if (ipToLong(raw) !== null) return true;
  const v6 = raw.replace(/^\[/, '').replace(/\]$/, '');
  return /^[0-9a-fA-F:]{3,45}$/.test(v6) && v6.includes(':');
}

/* ------------------------------------------------------------------ */
/* panel authentication (password from env or KV, cookie session)       */
/* ------------------------------------------------------------------ */

const AUTH_COOKIE = 'catpanel_auth';
const BRUTE_LIMIT = 8;
const BRUTE_WINDOW_MS = 10 * 60 * 1000;

/** Parses the stored brute-force counter; legacy plain numbers (no window) count as expired. */
function readBruteState(raw, now) {
  if (!raw) return { count: 0, until: 0 };
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') {
      const until = Number(parsed.until) || 0;
      if (until <= now) return { count: 0, until: 0 };
      return { count: Number(parsed.count) || 0, until: until };
    }
  } catch (e) { /* legacy value */ }
  return { count: 0, until: 0 };
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(value)));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function cookieValue(request, name) {
  const header = request.headers.get('cookie') || '';
  const parts = header.split(';');
  for (const part of parts) {
    const index = part.indexOf('=');
    if (index < 0) continue;
    if (part.slice(0, index).trim() === name) return part.slice(index + 1).trim();
  }
  return null;
}

async function panelUser(env) {
  const settings = await readSettings(env);
  return String(env.PANEL_USER || settings.panelUser || '').trim();
}

/** Session cookie value: hash of username+password when a username is set, else the password hash. */
async function panelAuthHash(env, hostForUuid) {
  const password = await panelPassword(env, hostForUuid);
  if (!password) return '';
  const user = await panelUser(env);
  return sha256Hex(user ? user + '\n' + password : password);
}

async function panelPassword(env, hostForUuid) {
  const settings = await readSettings(env);
  const explicit = String(env.PANEL_PASSWORD || settings.panelPassword || '').trim();
  if (explicit) return explicit;
  if (String(env.OPEN_PANEL || '').toLowerCase() === 'true') return '';
  // Locked by default (Cat behaviour): the UUID is the password until one is set.
  return hostForUuid ? await resolveUuid(hostForUuid, env) : String(env.UUID || '').trim();
}

/** Authed = no password configured, or the cookie carries the right hash. */
async function requirePanelAuth(request, env) {
  const host = (request.headers.get('Host') || new URL(request.url).hostname || '').toLowerCase();
  const password = await panelPassword(env, host);
  if (!password) return { ok: true, open: true };
  const expected = await panelAuthHash(env, host);
  if (cookieValue(request, AUTH_COOKIE) === expected) return { ok: true, open: false };
  return {
    ok: false,
    open: false,
    response: jsonResponse({ ok: false, error: 'unauthorized', login: '/login' }, 401, CORS),
  };
}

/** Probe every configured address from the panel edge; prune the dead ones. */
async function healthCheck(env, host) {
  const settings = await readSettings(env);
  const cfg = settings.configs || {};
  const manual = Array.isArray(cfg.addresses) ? cfg.addresses : [];
  const verified = normalizedVerifiedEntries(settings);
  const targets = unionAddresses(manual, verified.map((entry) => entry.ip)).slice(0, 64);
  const results = [];
  const batch = 12;
  for (let i = 0; i < targets.length; i += batch) {
    const group = targets.slice(i, i + batch);
    const probed = await Promise.all(group.map((ip) => probeIp(ip, 4200, host, env).catch(() => null)));
    probed.forEach((probe, idx) => {
      results.push({
        ip: group[idx],
        ok: !!(probe && probe.ok),
        ms: probe ? probe.ms : 0,
        colo: probe ? probe.colo : '',
        countryCode: probe ? probe.countryCode : '',
        countryName: probe ? probe.countryName : '',
      });
    });
  }
  const aliveSet = new Set(results.filter((r) => r.ok).map((r) => r.ip.toLowerCase()));
  const dead = results.filter((r) => !r.ok).map((r) => r.ip);
  await writeSettings(env, { configs: {
    addresses: manual.filter((ip) => aliveSet.has(String(ip).toLowerCase())),
    verified: verified.filter((entry) => aliveSet.has(String(entry.ip).toLowerCase())),
    lastHealth: { at: Date.now(), results: results },
  } });
  return jsonResponse({
    ok: true,
    checked: results.length,
    alive: results.length - dead.length,
    dead: dead,
    results: results,
  }, 200, CORS);
}

async function handleLogin(request, env) {
  const host = (request.headers.get('Host') || new URL(request.url).hostname || '').toLowerCase();
  const password = await panelPassword(env, host);
  const expectedUser = await panelUser(env);
  let body = null;
  try {
    body = await request.json();
  } catch (e) {
    const form = await request.formData().catch(() => null);
    body = form ? { password: form.get('password'), username: form.get('username') } : null;
  }
  const supplied = String((body && body.password) || '');
  const suppliedUser = String((body && body.username) || '').trim();
  if (!password) {
    return jsonResponse({ ok: true, note: 'no password configured' }, 200, CORS);
  }
  const bruteKey = 'catpanel:brute:' + (request.headers.get('cf-connecting-ip') || 'unknown');
  const bruteRaw = await kvGet(env, bruteKey);
  const brute = readBruteState(bruteRaw, Date.now());
  if (brute.count >= BRUTE_LIMIT) {
    const retryAfter = Math.max(1, Math.ceil((brute.until - Date.now()) / 1000));
    return jsonResponse({ ok: false, error: 'too-many-attempts', retryAfterSec: retryAfter }, 429,
      Object.assign({ 'retry-after': String(retryAfter) }, CORS));
  }
  const userOk = !expectedUser || suppliedUser.toLowerCase() === expectedUser.toLowerCase();
  if (userOk && supplied && supplied === password) {
    const token = await panelAuthHash(env, host);
    if (bruteRaw) await kvDelete(env, bruteKey);
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: Object.assign({}, CORS, {
        'content-type': 'application/json; charset=utf-8',
        'set-cookie': AUTH_COOKIE + '=' + token + '; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax',
      }),
    });
  }
  // Sliding window: the counter expires BRUTE_WINDOW_MS after the last failure (KV TTL as a
  // backstop; the JSON `until` is what is checked, so a KV without TTL support still unlocks).
  const next = { count: brute.count + 1, until: Date.now() + BRUTE_WINDOW_MS };
  await kvPut(env, bruteKey, JSON.stringify(next), { expirationTtl: Math.ceil(BRUTE_WINDOW_MS / 1000) });
  return jsonResponse({ ok: false, error: 'invalid-password', userRequired: !!expectedUser, attemptsLeft: Math.max(0, BRUTE_LIMIT - next.count) }, 401, CORS);
}

function redactSettings(settings) {
  const copy = JSON.parse(JSON.stringify(settings || {}));
  if (copy.panelPassword) copy.panelPassword = '••••••';
  return copy;
}

/* ------------------------------------------------------------------ */
/* users API                                                            */
/* ------------------------------------------------------------------ */

function newToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function newUuid() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

async function handleUsersApi(request, url, env, path) {
  const auth = await requirePanelAuth(request, env);
  if (!auth.ok) return auth.response;
  if (!hasKv(env)) {
    return jsonResponse({
      ok: false,
      error: 'kv-required',
      hint: 'Bind a KV namespace as CAT_KV (or KV) to store users; the panel still works with the master UUID from env.',
    }, 409, CORS);
  }
  if (url.searchParams.get('sync') === '1') await flushTraffic(env).catch(() => {});
  const users = await readUsers(env);
  const idPart = path.startsWith('/api/users/') ? decodeURIComponent(path.slice('/api/users/'.length)) : '';
  const id = idPart ? idPart.split('/')[0] : null;
  const action = idPart && idPart.includes('/') ? idPart.split('/')[1] : '';
  const withState = (user) => Object.assign({}, user, { state: userState(user), infoPath: '/info/' + user.token, subPath: '/u/' + user.token });

  if (request.method === 'GET') {
    if (id) {
      const user = users.find((item) => item.id === id || item.token === id);
      return user ? jsonResponse({ ok: true, user: withState(user) }, 200, CORS) : jsonResponse({ ok: false, error: 'not-found' }, 404, CORS);
    }
    return jsonResponse({ ok: true, count: users.length, users: users.map(withState), online: Array.from(liveConnections.entries()).reduce((a, e) => a + e[1], 0) }, 200, CORS);
  }

  if (request.method === 'POST' && id && action === 'regenerate') {
    const index = users.findIndex((item) => item.id === id);
    if (index < 0) return jsonResponse({ ok: false, error: 'not-found' }, 404, CORS);
    // New UUID + token: the old subscription link and configs stop working at once.
    users[index] = normalizeUser(Object.assign({}, users[index], { uuid: newUuid(), token: newToken() }));
    await writeUsers(env, users);
    return jsonResponse({ ok: true, user: withState(users[index]) }, 200, CORS);
  }
  if (request.method === 'POST' && id) return jsonResponse({ ok: false, error: 'unknown-action' }, 404, CORS);

  if (request.method === 'POST') {
    let body = null;
    try {
      body = await request.json();
    } catch (e) {
      return jsonResponse({ ok: false, error: 'invalid-json' }, 400, CORS);
    }
    const name = String((body && body.name) || '').trim().slice(0, 40) || 'user-' + (users.length + 1);
    const days = Number((body && body.days) || 0);
    const user = normalizeUser({
      id: newToken(),
      token: newToken(),
      uuid: (body && body.uuid) || newUuid(),
      name: name,
      quotaGb: (body && body.quotaGb) || 0,
      deviceLimit: (body && body.deviceLimit) || 0,
      note: (body && body.note) || '',
      countries: (body && body.countries) || '',
      expireAt: days > 0 ? Date.now() + days * 86400000 : 0,
      createdAt: Date.now(),
    });
    users.push(user);
    await writeUsers(env, users);
    return jsonResponse({ ok: true, user: withState(user), subPath: '/u/' + user.token, infoPath: '/info/' + user.token }, 201, CORS);
  }

  if (request.method === 'PUT' || request.method === 'PATCH') {
    if (!id) return jsonResponse({ ok: false, error: 'id-required' }, 400, CORS);
    const index = users.findIndex((item) => item.id === id);
    if (index < 0) return jsonResponse({ ok: false, error: 'not-found' }, 404, CORS);
    let body = null;
    try {
      body = await request.json();
    } catch (e) {
      return jsonResponse({ ok: false, error: 'invalid-json' }, 400, CORS);
    }
    const current = users[index];
    const days = body && body.days !== undefined ? Number(body.days) : null;
    const patch = {};
    ['name', 'quotaGb', 'deviceLimit', 'enabled', 'note', 'usedBytes', 'usedRequests', 'uuid', 'countries'].forEach((key) => {
      if (body && body[key] !== undefined) patch[key] = body[key];
    });
    if (patch.name !== undefined) patch.name = String(patch.name || '').trim().slice(0, 40) || current.name;
    if (patch.usedBytes !== undefined) trafficBuffers.delete(String(current.uuid).toLowerCase());
    users[index] = normalizeUser(Object.assign({}, current, patch, {
      id: current.id,
      token: current.token,
      expireAt: days === null ? current.expireAt : (days > 0 ? Date.now() + days * 86400000 : 0),
    }));
    await writeUsers(env, users);
    return jsonResponse({ ok: true, user: withState(users[index]) }, 200, CORS);
  }

  if (request.method === 'DELETE') {
    if (!id) return jsonResponse({ ok: false, error: 'id-required' }, 400, CORS);
    const next = users.filter((item) => item.id !== id);
    if (next.length === users.length) return jsonResponse({ ok: false, error: 'not-found' }, 404, CORS);
    await writeUsers(env, next);
    return jsonResponse({ ok: true, removed: users.length - next.length }, 200, CORS);
  }

  return jsonResponse({ ok: false, error: 'method-not-allowed' }, 405, CORS);
}

/* ------------------------------------------------------------------ */
/* per-user subscription links                                          */
/* ------------------------------------------------------------------ */

/** Snapshot of a user's quota/expiry for headers, the info page and the app. */
function userState(user, now) {
  const at = now || Date.now();
  const used = userLiveUsed(user);
  const total = userQuotaBytes(user);
  const expireAt = Number(user.expireAt) || 0;
  const daysLeft = expireAt > 0 ? Math.max(0, Math.ceil((expireAt - at) / 86400000)) : -1;
  const blocked = userReasonBlocked(user, at);
  return {
    name: user.name || 'user',
    used: used,
    total: total,
    remaining: total > 0 ? Math.max(0, total - used) : -1,
    pct: total > 0 ? Math.min(100, Math.round((used / total) * 1000) / 10) : 0,
    expireAt: expireAt,
    daysLeft: daysLeft,
    status: blocked || 'active',
    deviceLimit: Number(user.deviceLimit) || 0,
    online: liveConnections.get(String(user.uuid || '').toLowerCase()) || 0,
    lastSeenAt: Number(user.lastSeenAt) || 0,
  };
}

function subscriptionUserinfo(state) {
  return [
    'upload=0',
    'download=' + Math.max(0, Math.floor(state.used)),
    'total=' + Math.max(0, Math.floor(state.total)),
    'expire=' + (state.expireAt > 0 ? Math.floor(state.expireAt / 1000) : 0),
  ].join('; ');
}

/**
 * Deep links understood by the popular clients (the clients supported by Cat Panel).
 * `sub` must be the full https URL of the subscription.
 */
function appDeepLinks(sub, name) {
  const enc = encodeURIComponent(sub);
  const tag = encodeURIComponent(name || 'Cat Panel');
  return [
    { id: 'catclient', label: 'Cat Client', href: 'catclient://add-sub?url=' + enc + '&name=' + tag },
    { id: 'v2rayng', label: 'v2rayNG', href: 'v2rayng://install-sub?url=' + enc + '&name=' + tag },
    { id: 'v2box', label: 'V2Box', href: 'v2box://install-sub?url=' + enc + '&name=' + tag },
    { id: 'hiddify', label: 'Hiddify', href: 'hiddify://import/' + sub + '#' + tag },
    { id: 'streisand', label: 'Streisand', href: 'streisand://import/' + sub },
    { id: 'v2raytun', label: 'v2rayTun', href: 'v2raytun://import/' + sub },
    { id: 'singbox', label: 'sing-box', href: 'sing-box://import-remote-profile?url=' + encodeURIComponent(sub.replace(/\/?$/, '') + '/singbox') + '#' + tag },
    { id: 'clash', label: 'Clash / Mihomo', href: 'clash://install-config?url=' + encodeURIComponent(sub.replace(/\/?$/, '') + '/clash') + '&name=' + tag },
    { id: 'shadowrocket', label: 'Shadowrocket', href: 'sub://' + b64encode(sub) },
  ];
}

function wantsHtmlPage(request) {
  const accept = String((request.headers.get('Accept') || '')).toLowerCase();
  const ua = String((request.headers.get('User-Agent') || '')).toLowerCase();
  const isClient = /v2ray|clash|mihomo|sing|hiddify|streisand|nekobox|shadowrocket|surfboard|loon|stash|v2box|sfi|sfa|husi|catclient/.test(ua);
  return !isClient && accept.includes('text/html');
}

async function handleUserSubscription(request, url, env, host, path, ctx) {
  const isInfo = path.startsWith('/info/');
  const rest = path.slice(isInfo ? '/info/'.length : '/u/'.length).split('/');
  const token = decodeURIComponent(rest[0] || '');
  const format = (rest[1] || '').toLowerCase();
  // Make the number the app sees match the panel: write this isolate's buffer first.
  await flushTraffic(env).catch(() => {});
  const users = await readUsers(env);
  const user = findUserByToken(users, token);
  if (!user) return new Response('Not Found', { status: 404, headers: CORS });
  const state = userState(user);
  const subUrl = 'https://' + host + '/u/' + user.token;
  const title = String(env.PANEL_TITLE || 'Cat Panel');

  if (url.searchParams.get('stats') === '1') {
    return jsonResponse(Object.assign({ ok: true, ts: Date.now() }, state), 200, Object.assign({ 'cache-control': 'no-store' }, CORS));
  }
  // The recipient page is intentionally strict: it never advertises the panel's
  // fallback address list. It shows only the successful worker-probe set saved
  // by the owner, then lets the recipient choose a count and countries.
  const userCountries = Array.isArray(user.countries) ? user.countries : [];
  const gated = userCountries.length === 0;
  if (!isInfo && !format && wantsHtmlPage(request)) {
    // A human opening the subscription link gets the chooser page (count + countries).
    return Response.redirect('https://' + host + '/info/' + encodeURIComponent(user.token), 302);
  }
  const settings = await readSettings(env);
  if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(Promise.resolve(ensureVerifiedPool(env, host)).catch(() => {}));
  const landingUrl = new URL(url.toString());
  landingUrl.searchParams.set('verified', '1');
  const landingOptions = configOptions(landingUrl, host, env, settings, userCountries);
  const landingCatalog = buildAllConfigs(host, env, user.uuid, landingOptions);
  const landingCountries = landingOptions.verifiedEntries
    .map((entry) => {
      const loc = locationFromCodeOrColo(entry.colo || entry.countryCode || '');
      const code = String(entry.countryCode || (loc && loc.iso) || '').toUpperCase();
      return code ? { code: code, name: entry.countryName || loc.country, flag: loc.flag, count: 1 } : null;
    })
    .filter(Boolean)
    .reduce((out, entry) => {
      const found = out.find((item) => item.code === entry.code);
      if (found) found.count += 1;
      else out.push(entry);
      return out;
    }, []);
  // Graphical page ONLY on explicit request (/info/<token> or ?web=1): sniffing
  // User-Agent sniffing breaks WebView/Cronet based apps.
  if (isInfo || url.searchParams.get('web') === '1') {
    return htmlResponse(userInfoHtml({
      title: title,
      host: host,
      user: user,
      state: state,
      subUrl: subUrl,
      apps: appDeepLinks(subUrl, title + ' | ' + state.name),
      allUrl: subUrl + '/all?verified=1',
      catalog: landingCatalog,
      countries: gated ? [] : landingCountries,
      userCountries: userCountries,
      gated: gated,
      verifiedScanned: settings.configs && settings.configs.verifiedScanned === true,
    }));
  }
  if (state.status !== 'active') {
    return new Response('Cat Panel: ' + state.status, { status: 403, headers: CORS });
  }
  const uuid = user.uuid;
  const options = configOptions(url, host, env, settings, userCountries);
  const headers = Object.assign({}, CORS, {
    'subscription-userinfo': subscriptionUserinfo(state),
    'profile-title': 'base64:' + b64encode(title + ' | ' + state.name),
    'profile-update-interval': '6',
    'profile-web-page-url': 'https://' + host + '/info/' + user.token,
    'support-url': 'https://' + host + '/info/' + user.token,
    'cache-control': 'no-store',
  });
  if (format === 'clash' || format === 'mihomo' || format === 'yaml') {
    return new Response(buildClashYaml(host, env, uuid, options), {
      headers: Object.assign({}, headers, { 'content-type': 'text/yaml; charset=utf-8' }),
    });
  }
  if (format === 'singbox' || format === 'sing-box' || format === 'json') {
    return new Response(buildSingboxConfig(host, env, uuid, options), {
      headers: Object.assign({}, headers, { 'content-type': 'application/json; charset=utf-8' }),
    });
  }
  if (format === 'all') {
    return jsonResponse(Object.assign({ ok: true, user: { name: user.name, token: user.token }, usage: state }, buildAllConfigs(host, env, uuid, options)), 200, headers);
  }
  const wantsWarp = url.searchParams.get('warp') === '1' || /catclient/i.test(request.headers.get('User-Agent') || '');
  const links = buildSubLinks(host, env, uuid, options, wantsWarp).join('\n') + '\n';
  if (format !== 'raw' && format !== 'txt') {
    return new Response(b64encode(links), {
      headers: Object.assign({}, headers, { 'content-type': 'text/plain; charset=utf-8' }),
    });
  }
  return new Response(links, {
    headers: Object.assign({}, headers, { 'content-type': 'text/plain; charset=utf-8' }),
  });
}

function fmtBytes(b) {
  b = Number(b) || 0;
  if (b < 1024) return b + ' B';
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
  return (b >= 100 ? Math.round(b) : b.toFixed(2)) + ' ' + u[i];
}

/** Public per-user page (/info/<token>): usage ring, expiry, one-tap app import. */
function userInfoHtml(d) {
  const st = d.state;
  const statusFa = st.status === 'active' ? 'فعال' : st.status === 'expired' ? 'منقضی' : st.status === 'quota-exceeded' ? 'حجم تمام شده' : 'غیرفعال';
  const usedText = fmtBytes(st.used);
  const totalText = st.total > 0 ? fmtBytes(st.total) : 'نامحدود';
  const remainText = st.remaining < 0 ? 'نامحدود' : fmtBytes(st.remaining);
  const expiryText = st.daysLeft < 0 ? 'نامحدود' : st.daysLeft === 0 ? 'پایان‌یافته' : st.daysLeft + ' روز';
  const initial = esc(String(st.name).trim().charAt(0).toUpperCase() || 'C');
  const countries = Array.isArray(d.countries) ? d.countries : [];
  const countryControls = countries.length
    ? countries.map((country) => '<label class="country-choice"><input type="checkbox" data-country="' + esc(country.code) + '" checked><span>' + esc(country.flag + ' ' + country.name) + (country.count ? ' <b class="cnt">' + country.count + '</b>' : '') + '</span></label>').join('')
    : '<p class="muted">IPهای تمیز به‌صورت خودکار در حال شناسایی‌اند و این صفحه چند لحظهٔ دیگر خودش تازه می‌شود. اگر باز هم خالی بود، از مالک پنل بخواه یک بار «اسکن از ورکر» را بزند.</p>';
  const boot = JSON.stringify({
    subUrl: d.subUrl,
    allUrl: d.allUrl,
    name: st.name,
    countries: countries,
    entries: d.catalog && d.catalog.entries ? d.catalog.entries : [],
    verifiedOnly: !!(d.catalog && d.catalog.verifiedOnly),
    verifiedScanned: !!d.verifiedScanned,
    gated: !!d.gated,
  }).replace(/</g, '\\u003c');
  const appButtons = d.apps.map((a) => '<a class="app" href="' + esc(a.href) + '" data-app="' + a.id + '"><b>' + esc(a.label) + '</b><span>افزودن خودکار</span></a>').join('');
  return '<!doctype html><html lang="fa" dir="rtl" data-theme="dark"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="robots" content="noindex,nofollow">' +
    '<meta name="theme-color" content="#06030c"><title>' + esc(d.title) + ' · ' + esc(st.name) + '</title>' +
    '<style>' + css() + infoCss() + '</style></head><body data-lang="fa"><div class="bg"></div><div class="wrap info">' +
    '<header class="ihead"><div class="avatar">' + initial + '</div><div class="grow"><h1>' + esc(st.name) + '</h1>' +
    '<div class="tags"><span class="pill ' + (st.status === 'active' ? 'ok' : 'warn') + '" id="statusTag">' + statusFa + '</span>' +
    '<span class="pill">انقضا: <b id="expiryTag">' + expiryText + '</b></span>' +
    '<span class="pill">آنلاین: <b id="onlineTag">' + st.online + '</b></span></div></div>' +
    '<div class="brand"><span class="cat">🐱</span><small>' + esc(d.title) + '</small></div></header>' +

    '<section class="card glow usage"><div class="ring"><svg viewBox="0 0 120 120"><defs><linearGradient id="rg" x1="0" y1="0" x2="1" y2="1">' +
    '<stop offset="0" stop-color="#7c3aed"/><stop offset="1" stop-color="#d946ef"/></linearGradient></defs>' +
    '<circle class="bgc" cx="60" cy="60" r="50"></circle><circle class="fgc" id="ringArc" cx="60" cy="60" r="50" stroke-dasharray="314.16" stroke-dashoffset="314.16"></circle></svg>' +
    '<div class="lbl"><b id="ringPct">' + (st.total > 0 ? st.pct + '%' : '∞') + '</b><span>مصرف</span></div></div>' +
    '<div class="mini"><div class="mbox"><label>مصرف شده</label><b id="uUsed">' + usedText + '</b></div>' +
    '<div class="mbox"><label>باقی‌مانده</label><b id="uRemain" class="ok">' + remainText + '</b></div>' +
    '<div class="mbox"><label>سقف</label><b id="uLimit">' + totalText + '</b></div>' +
    '<div class="mbox"><label>محدودیت دستگاه</label><b>' + (st.deviceLimit > 0 ? st.deviceLimit : 'نامحدود') + '</b></div></div>' +
    '<div class="bar" style="margin-top:14px"><i id="usageBar" style="width:' + (st.total > 0 ? st.pct : 0) + '%"></i></div>' +
    '<p class="muted" style="margin-top:8px">عدد مصرف از شمارندهٔ واقعی سرویس خوانده می‌شود و هر ۲۰ ثانیه تازه می‌شود.</p></section>' +

    '<section class="card"><h2><span class="dot"></span>لینک اشتراک</h2>' +
    '<div class="link-row"><span class="grow mono" id="subUrl">' + esc(d.subUrl) + '</span><button class="btn tiny" id="copySub">کپی</button>' +
    '<button class="btn ghost tiny" id="qrSub">QR</button></div>' +
    '<p class="muted" style="margin-top:8px">این لینک را در هر برنامه‌ای (v2rayNG، V2Box، Hiddify، Streisand، sing-box، Clash) به‌عنوان Subscription اضافه کن؛ حجم و انقضا هم داخل برنامه دیده می‌شود.</p>' +
    '<div class="apps">' + appButtons + '</div>' +
    '<div class="row" style="margin-top:10px"><a class="btn ghost" href="' + esc(d.subUrl) + '/raw" download="cat-configs.txt">دانلود فایل کانفیگ‌ها</a>' +
    '<a class="btn ghost" href="' + esc(d.subUrl) + '/clash">Clash YAML</a><a class="btn ghost" href="' + esc(d.subUrl) + '/singbox">sing-box JSON</a></div></section>' +

    (d.gated
    ? '<section class="card" id="recipientConfigs"><h2><span class="dot"></span>کانفیگی هنوز فعال نشده</h2>' +
    '<p class="muted">مالک پنل هنوز کشوری برای حساب تو انتخاب نکرده است. به او بگو در تب «کاربران»، کشورهای دلخواهت را (مثلاً 🇳🇱 هلند یا 🇩🇪 آلمان) برایت تعیین کند؛ بعد از آن همین صفحه هم تعداد کانفیگ و هم لوکیشن را از تو می‌پرسد و فقط از همان کشورها کانفیگ می‌سازد.</p></section>'
    : '<section class="card" id="recipientConfigs"><h2><span class="dot"></span>انتخاب کانفیگ‌ها</h2>' +
    '<p>تعداد کانفیگ و کشورهای دلخواهت را انتخاب کن. خروجی فقط از IPهایی ساخته می‌شود که آخرین اسکن پنل با موفقیت به آن‌ها پاسخ داده؛ هر کشور در گروه خودش نمایش داده می‌شود.</p>' +
    '<div class="grid two" style="margin-top:12px"><label class="field"><span>تعداد کانفیگ</span><select id="configCount"><option value="3">۳ کانفیگ</option><option value="6" selected>۶ کانفیگ</option><option value="10">۱۰ کانفیگ</option><option value="20">۲۰ کانفیگ</option><option value="40">۴۰ کانفیگ</option><option value="80">۸۰ کانفیگ</option></select></label>' +
    '<div class="field"><span>کشورها</span><div class="country-choices" id="countryChoices">' + countryControls + '</div></div></div>' +
    '<div class="row" style="margin-top:12px"><button class="btn" id="loadRecipientConfigs">نمایش کانفیگ‌های انتخابی</button><span class="muted" id="recipientStatus"></span></div>' +
    '<div class="link-row" style="margin-top:10px"><span class="grow mono" id="selectedSubUrl">' + esc(d.allUrl || d.subUrl) + '</span><button class="btn tiny" id="copySelectedSub">کپی لینک انتخابی</button><a class="btn ghost tiny" id="addSelectedSub" href="' + esc('catclient://add-sub?url=' + encodeURIComponent(d.subUrl) + '&name=' + encodeURIComponent(st.name)) + '">افزودن به Cat Client</a></div>' +
    '<div id="recipientGroups" style="margin-top:14px"></div></section>') +

    '<div class="modal" id="qrModal"><div class="box"><img id="qrImg" alt="QR"><p class="mono" id="qrHint"></p><button class="btn" id="qrClose">بستن</button></div></div>' +
    '<div class="toast" id="toast"><span></span></div>' +
    '<footer class="muted" style="text-align:center;margin:24px 0 8px;font-size:11px">Cat Panel ' + CAT_PANEL_VERSION + '</footer></div>' +
    '<script>(function(){var D=' + boot + ';function $(s){return document.querySelector(s)}function $$(s){return Array.prototype.slice.call(document.querySelectorAll(s))}' +
    'function toast(t){var el=$("#toast");el.firstChild.textContent=t;el.classList.add("show");setTimeout(function(){el.classList.remove("show")},1800)}' +
    'function copy(t){if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(function(){toast("کپی شد")},function(){fallback(t)})}else fallback(t)}' +
    'function fallback(t){var ta=document.createElement("textarea");ta.value=t;document.body.appendChild(ta);ta.select();try{document.execCommand("copy");toast("کپی شد")}catch(e){}document.body.removeChild(ta)}' +
    'function escH(v){return String(v==null?"":v).replace(/[&<>]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;"}[c]})}' +
    'function selectedConfigUrl(){var count=Number($("#configCount").value||6);var countries=$$("#countryChoices [data-country]:checked").map(function(c){return c.getAttribute("data-country")});var u=D.allUrl+"&count="+encodeURIComponent(count);if(countries.length)u+="&countries="+encodeURIComponent(countries.join(","));return u;}' +
    'function refreshSelectedLink(){var u=selectedConfigUrl();$("#selectedSubUrl").textContent=u;$("#addSelectedSub").setAttribute("href","catclient://add-sub?url="+encodeURIComponent(u)+"&name="+encodeURIComponent(D.name||"Cat Panel"));return u;}' +
    'function renderRecipientEntries(entries){var groups={};(entries||[]).forEach(function(e){var key=e.countryCode||"EDGE";(groups[key]||(groups[key]={name:e.countryName||"Cloudflare edge",flag:e.flag||"🌐",entries:[]})).entries.push(e)});var keys=Object.keys(groups);$("#recipientGroups").innerHTML=keys.length?keys.map(function(k){var g=groups[k];return "<div class=\\"config-group\\"><h3>"+escH(g.flag+" "+g.name)+" <span class=pill>"+g.entries.length+"</span></h3><div class=\\"config-list\\">"+g.entries.map(function(e){return "<div class=\\"config-item\\"><div><b>"+escH(e.name)+"</b><small dir=ltr>"+escH(e.addr)+":"+escH(e.port)+"</small></div><div class=\\"row\\"><button class=\\"btn ghost tiny\\" data-copy-config=\\""+encodeURIComponent(e.link)+"\\">کپی</button><a class=\\"btn tiny\\" href=\\"catclient://add-sub?url="+encodeURIComponent(e.link)+"&name="+encodeURIComponent(e.name)+"\\">افزودن</a></div></div>"}).join("")+"</div></div>"}).join(""):"<p class=muted>برای انتخاب فعلی، IP موفقی پیدا نشد. کشور دیگری یا تعداد بیشتری انتخاب کن.</p>";}' +
    'function loadRecipientConfigs(){var u=refreshSelectedLink();$("#recipientStatus").textContent="در حال ساخت…";fetch(u,{cache:"no-store"}).then(function(r){return r.json()}).then(function(j){if(!j||!j.ok)throw new Error("failed");renderRecipientEntries(j.entries||[]);$("#recipientStatus").textContent=(j.entries||[]).length+" کانفیگ موفق";}).catch(function(){$("#recipientStatus").textContent="ساخت لینک ناموفق بود";});}' +
    'document.addEventListener("click",function(ev){var c=ev.target.closest("[data-copy-config]");if(c){copy(decodeURIComponent(c.getAttribute("data-copy-config")));}});' +
    '$("#configCount").addEventListener("change",loadRecipientConfigs);$("#countryChoices").addEventListener("change",loadRecipientConfigs);$("#copySelectedSub").onclick=function(){copy(refreshSelectedLink())};$("#loadRecipientConfigs").onclick=loadRecipientConfigs;renderRecipientEntries(D.entries||[]);loadRecipientConfigs();if(!D.verifiedScanned){try{if(!sessionStorage.getItem("catinfo_r")){sessionStorage.setItem("catinfo_r","1");setTimeout(function(){location.reload()},12000)}}catch(e){}}' +
    '$("#copySub").onclick=function(){copy(D.subUrl)};' +
    '$("#qrSub").onclick=function(){$("#qrImg").src="/qr.svg?d="+encodeURIComponent(D.subUrl)+"&size=8";$("#qrHint").textContent=D.subUrl;$("#qrModal").classList.add("show")};' +
    '$("#qrClose").onclick=function(){$("#qrModal").classList.remove("show")};' +
    'function fmt(b){b=Number(b)||0;if(b<1024)return b+" B";var u=["KB","MB","GB","TB"],i=-1;do{b/=1024;i++}while(b>=1024&&i<u.length-1);return (b>=100?Math.round(b):b.toFixed(2))+" "+u[i]}' +
    'function apply(s){if(!s||!s.ok)return;$("#uUsed").textContent=fmt(s.used);$("#uRemain").textContent=s.remaining<0?"نامحدود":fmt(s.remaining);$("#uLimit").textContent=s.total>0?fmt(s.total):"نامحدود";' +
    ' var pct=s.total>0?s.pct:0;$("#ringPct").textContent=s.total>0?pct+"%":"∞";$("#ringArc").style.strokeDashoffset=String(314.16-314.16*Math.min(100,pct)/100);$("#usageBar").style.width=pct+"%";' +
    ' $("#onlineTag").textContent=s.online;$("#expiryTag").textContent=s.daysLeft<0?"نامحدود":(s.daysLeft===0?"پایان‌یافته":s.daysLeft+" روز");' +
    ' var st=$("#statusTag");st.className="pill "+(s.status==="active"?"ok":"warn");st.textContent=s.status==="active"?"فعال":(s.status==="expired"?"منقضی":(s.status==="quota-exceeded"?"حجم تمام شده":"غیرفعال"));}' +
    'setTimeout(function(){$("#ringArc").style.strokeDashoffset=String(314.16-314.16*Math.min(100,' + (st.total > 0 ? st.pct : 0) + ')/100)},80);' +
    'function poll(){fetch(D.subUrl+"?stats=1",{cache:"no-store"}).then(function(r){return r.json()}).then(apply).catch(function(){})}' +
    'setInterval(poll,20000);document.addEventListener("visibilitychange",function(){if(!document.hidden)poll()});' +
    '})();</script></body></html>';
}

function infoCss() {
  return [
    '.wrap.info{max-width:760px;padding-top:26px}',
    '.ihead{display:flex;align-items:center;gap:14px;margin-bottom:18px}',
    '.ihead h1{font-size:22px;margin:0 0 6px}.ihead .grow{flex:1;min-width:0}',
    '.avatar{width:56px;height:56px;border-radius:18px;display:grid;place-items:center;font-size:24px;font-weight:800;color:#fff;background:linear-gradient(135deg,var(--accent-2),var(--accent-3));box-shadow:0 12px 30px var(--glow-a)}',
    '.tags{display:flex;flex-wrap:wrap;gap:6px}.tags .pill b{margin-inline-start:4px}',
    '.usage{display:flex;gap:22px;align-items:center;flex-wrap:wrap}',
    '.ring{position:relative;width:132px;height:132px;flex-shrink:0;margin-inline:auto}.ring svg{width:100%;height:100%;transform:rotate(-90deg)}',
    '.ring .bgc{fill:none;stroke:var(--surface-2);stroke-width:10}.ring .fgc{fill:none;stroke:url(#rg);stroke-width:10;stroke-linecap:round;transition:stroke-dashoffset 1s cubic-bezier(.16,1,.3,1)}',
    '.ring .lbl{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center}.ring .lbl b{font-size:24px}.ring .lbl span{font-size:11px;color:var(--muted)}',
    '.usage .mini{flex:1;min-width:220px;display:grid;grid-template-columns:1fr 1fr;gap:10px}',
    '.mbox{padding:12px 14px;border-radius:14px;background:var(--surface);border:1px solid var(--line-soft)}.mbox label{display:block;font-size:11px;color:var(--muted);margin-bottom:4px}.mbox b{font-size:15px;direction:ltr;display:inline-block}.mbox b.ok{color:var(--ok)}',
    '.modal{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.7);z-index:50;padding:18px}.modal.show{display:flex}',
    '.modal .box{background:#fff;color:#111;border-radius:20px;padding:18px;max-width:360px;width:100%;text-align:center}.modal img{width:100%;max-width:300px;display:block;margin:0 auto 10px}.modal p{font-size:10.5px;word-break:break-all;direction:ltr;color:#444;margin-bottom:12px}',
    '.card+.card{margin-top:18px}.cnt{font-size:10px;background:#a855f7;color:#fff;border-radius:999px;padding:2px 8px;margin-inline-start:8px}.config-group{padding:12px;border-radius:16px;background:var(--surface-2,var(--surface));border:1px solid var(--line-soft,var(--line));margin-top:12px}.config-group+.config-group{margin-top:14px}.config-group h3{font-size:14px;margin-bottom:8px;display:flex;align-items:center;gap:7px}.country-choices{display:flex;flex-wrap:wrap;gap:7px;min-height:38px}.country-choice{display:inline-flex;align-items:center;gap:6px;padding:8px 10px;border-radius:12px;background:var(--surface-2);border:1px solid var(--line-soft);font-size:12px;cursor:pointer}.country-choice input{width:auto;accent-color:#a855f7}.config-group{padding:12px;border-radius:16px;background:var(--surface);border:1px solid var(--line-soft);margin-top:10px}.config-group h3{font-size:14px;margin-bottom:8px;display:flex;align-items:center;gap:7px}.config-list{display:grid;gap:7px}.config-item{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 10px;border-radius:12px;background:var(--surface-2);border:1px solid var(--line-soft)}.config-item b{display:block;font-size:12px}.config-item small{display:block;color:var(--muted);margin-top:3px;direction:ltr}.config-item .row{margin:0;flex-shrink:0}',
  ].join('\n');
}

async function handlePanelRequest(request, url, env, host, uuid, state) {
  const panelPass = await panelPassword(env, host);
  const panelUserName = await panelUser(env);
  if (panelPass) {
    const expected = panelUserName ? await sha256Hex(panelUserName + '\n' + panelPass) : await sha256Hex(panelPass);
    const supplied = url.searchParams.get('p') || url.searchParams.get('uuid') || '';
    // With a username set, ?p= alone is not a login — the form asks for both.
    const authed = cookieValue(request, AUTH_COOKIE) === expected || (!panelUserName && supplied && supplied === panelPass);
    if (!authed) return htmlResponse(loginHtml(state.title, '', panelUserName));
    if (supplied && supplied === panelPass) {
      // Log in via ?p= once and set the cookie so the URL can be shared without the secret.
      return new Response(panelShell(state), {
        status: 200,
        headers: {
          'content-type': 'text/html; charset=utf-8',
          'set-cookie': AUTH_COOKIE + '=' + expected + '; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax',
        },
      });
    }
  }
  return htmlResponse(panelShell(state));
}

async function fetchHandler(request, env, ctx) {
  const url = new URL(request.url);
  const host = (request.headers.get('Host') || url.hostname || '').toLowerCase();
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

  if (!sniAllowed(request, host, env)) {
    return new Response('Forbidden SNI', { status: 403, headers: CORS });
  }

  const uuid = await resolveUuid(host, env);
  const paths = panelPaths(env);
  const vlessName = paths.vlessPath.split('?')[0];
  const trojanName = paths.trojanPath.split('?')[0];
  const path = url.pathname;

  /* data plane — any WebSocket upgrade on the VLESS/Trojan path (or /ws, /trojan) */
  const upgrade = (request.headers.get('Upgrade') || '').toLowerCase();
  const tunnelPaths = new Set([vlessName, trojanName, '/ws', '/trojan', '/vless', '/tunnel']);
  if (upgrade === 'websocket' && (tunnelPaths.has(path) || path.startsWith(vlessName + '/') || path.startsWith('/ws/'))) {
    const pair = new WebSocketPair();
    const client = Object.values(pair)[0];
    const server = Object.values(pair)[1];
    server.accept();
    handleTunnelConnection(server, env, {
      path: path,
      earlyDataHeader: request.headers.get('sec-websocket-protocol') || '',
      masterUuid: uuid,
      ctx: ctx || null,
    }).catch(() => {
      try { server.close(1011, 'tunnel error'); } catch (e) { /* ignore */ }
    });
    return new Response(null, { status: 101, statusText: 'Switching Protocols', webSocket: client });
  }
  if (path === vlessName || path === trojanName) {
    // A plain GET on the tunnel path (scanner / censor probe) sees the same fake 404 as any
    // unknown path; only a WebSocket upgrade reveals the endpoint.
    return notFoundResponse();
  }

  /* subscriptions — the UUID is the secret. /sub/<uuid>[/clash|singbox|b64|all] */
  const subMatch = path.match(/^\/(sub|sub64|clash|mihomo|singbox|sing-box|all)(?:\/([^/]+))?(?:\/([a-z0-9-]+))?\/?$/i);
  if (subMatch) {
    const kind = subMatch[1].toLowerCase();
    const suppliedUuid = String(subMatch[2] || url.searchParams.get('uuid') || url.searchParams.get('u') || '').toLowerCase();
    const openSub = String(env.OPEN_SUB || '').toLowerCase() === 'true';
    let subUuid = uuid;
    if (suppliedUuid && suppliedUuid !== uuid.toLowerCase()) {
      const kvUsers = await readUsers(env);
      const user = findUserByUuid(kvUsers, suppliedUuid) || findUserByToken(kvUsers, suppliedUuid);
      if (!user) return new Response('Not Found', { status: 404, headers: CORS });
      const blocked = userReasonBlocked(user);
      if (blocked) return new Response('Cat Panel: ' + blocked, { status: 403, headers: CORS });
      subUuid = user.uuid;
    } else if (!suppliedUuid && !openSub) {
      return new Response('Cat Panel: use /sub/<uuid> (copy the link from the panel)', { status: 401, headers: CORS });
    }
    const settings = await readSettings(env);
    const options = configOptions(url, host, env, settings);
    let format = String(subMatch[3] || '').toLowerCase();
    if (kind === 'clash' || kind === 'mihomo') format = 'clash';
    if (kind === 'singbox' || kind === 'sing-box') format = 'singbox';
    if (kind === 'all') format = 'all';
    if (kind === 'sub64' || url.searchParams.get('b64') === '1') format = format || 'b64';
    const usage = subUserInfoHeader(env);
    const headers = Object.assign({ 'subscription-userinfo': usage, 'profile-update-interval': '6', 'profile-title': 'base64:' + b64encode(String(env.PANEL_TITLE || 'Cat Panel')) }, CORS);
    if (format === 'clash') {
      return new Response(buildClashYaml(host, env, subUuid, options), {
        headers: Object.assign({ 'content-type': 'text/yaml; charset=utf-8' }, headers),
      });
    }
    if (format === 'singbox') {
      return new Response(buildSingboxConfig(host, env, subUuid, options), {
        headers: Object.assign({ 'content-type': 'application/json; charset=utf-8' }, headers),
      });
    }
    if (format === 'all') return jsonResponse(buildAllConfigs(host, env, subUuid, options), 200, headers);
    const wantsWarp = url.searchParams.get('warp') === '1' || /catclient/i.test(request.headers.get('User-Agent') || '');
    const body = buildSubLinks(host, env, subUuid, options, wantsWarp).join('\n') + '\n';
    const wantsRaw = format === 'raw' || format === 'txt' || url.searchParams.get('raw') === '1';
    // Default is base64 (every client accepts it; some reject plain text).
    return new Response(wantsRaw ? body : b64encode(body), {
      headers: Object.assign({ 'content-type': 'text/plain; charset=utf-8' }, headers),
    });
  }

  /* QR codes */
  if (path === '/qr.svg' || path === '/qr') {
    const data = url.searchParams.get('d') || url.searchParams.get('data') || '';
    if (!data) return new Response('Missing ?d=', { status: 400, headers: CORS });
    const moduleSize = Math.max(2, Math.min(16, Number(url.searchParams.get('size') || 6)));
    let svg;
    try {
      svg = qrSvg(data, {
        ecl: (url.searchParams.get('ecl') || 'M').toUpperCase(),
        moduleSize: moduleSize,
        dark: url.searchParams.get('dark') || '#12061f',
        light: url.searchParams.get('light') || '#ffffff',
      });
    } catch (e) {
      return new Response('QR error: ' + (e && e.message ? e.message : e), { status: 400, headers: CORS });
    }
    return new Response(svg, {
      headers: Object.assign(
        { 'content-type': 'image/svg+xml; charset=utf-8', 'cache-control': 'public, max-age=86400' },
        CORS,
      ),
    });
  }

  /* API */
  if (path === '/api/config.json') {
    const auth = await requirePanelAuth(request, env);
    if (!auth.ok && (url.searchParams.get('uuid') || '').toLowerCase() !== uuid.toLowerCase()) return auth.response;
    const settings = await readSettings(env);
    return jsonResponse(panelState(host, env, uuid, request, settings), 200, CORS);
  }
  if (path === '/api/scan-targets.json') {
    return jsonResponse({ sni: effectiveSni(host, env), port: paths.port, targets: scanTargets(env), ranges: scanRanges(env) }, 200, CORS);
  }
  if (path === '/api/ping') {
    const ip = url.searchParams.get('ip') || '';
    if (!isIpLiteral(ip)) return jsonResponse({ ok: false, error: 'ip required' }, 400, CORS);
    return jsonResponse(await probeIp(ip, Number(url.searchParams.get('timeout') || 4000), host, env), 200, CORS);
  }
  if (path === '/api/resolve') {
    const target = url.searchParams.get('host') || '';
    return jsonResponse(await resolveHost(target, env), 200, CORS);
  }
  if (path === '/api/dns-probe') {
    const upstream = url.searchParams.get('u') || dohUpstream(env);
    const name = url.searchParams.get('name') || DNS_QUERY_NAME;
    return jsonResponse(await probeDnsUpstream(upstream, name), 200, CORS);
  }

  /* ---- panel API (settings / users / backup / scan / info) ---- */
  if (path === '/api/version') {
    return jsonResponse({
      ok: true,
      panel: 'cat-panel',
      version: CAT_PANEL_VERSION,
      kv: hasKv(env),
      features: ['vless-ws', 'trojan-ws', 'tcp-relay', 'proxy-ip', 'users', 'quota', 'dns', 'scan', 'qr', 'subs', 'backup'],
    }, 200, CORS);
  }

  if (path === '/api/self') {
    const cf = request.cf || {};
    return jsonResponse({
      ok: true,
      ip: request.headers.get('cf-connecting-ip') || null,
      country: cf.country || null,
      city: cf.city || null,
      colo: cf.colo || null,
      asn: cf.asn || null,
      tlsVersion: cf.tlsVersion || null,
      httpProtocol: cf.httpProtocol || null,
      panel: 'cat-panel',
      version: CAT_PANEL_VERSION,
    }, 200, CORS);
  }

  if (path === '/api/settings') {
    const auth = await requirePanelAuth(request, env);
    if (!auth.ok) return auth.response;
    if (request.method === 'GET') {
      const settings = await readSettings(env);
      return jsonResponse({ ok: true, settings: redactSettings(settings), hasKv: hasKv(env) }, 200, CORS);
    }
    if (request.method === 'POST' || request.method === 'PUT') {
      let patch = null;
      try {
        patch = await request.json();
      } catch (e) {
        return jsonResponse({ ok: false, error: 'invalid-json' }, 400, CORS);
      }
      const result = await writeSettings(env, patch || {});
      return jsonResponse({
        ok: true,
        persisted: result.persisted,
        settings: redactSettings(result.settings),
      }, 200, CORS);
    }
    return jsonResponse({ ok: false, error: 'method-not-allowed' }, 405, CORS);
  }

  if (path === '/api/users' || path.startsWith('/api/users/')) {
    return handleUsersApi(request, url, env, path);
  }

  if (path === '/api/backup') {
    const auth = await requirePanelAuth(request, env);
    if (!auth.ok) return auth.response;
    if (request.method === 'GET') {
      const settings = await readSettings(env);
      const users = await readUsers(env);
      return jsonResponse({
        ok: true,
        version: CAT_PANEL_VERSION,
        exportedAt: new Date().toISOString(),
        settings: redactSettings(settings),
        users: users,
      }, 200, CORS);
    }
    if (request.method === 'POST') {
      let payload = null;
      try {
        payload = await request.json();
      } catch (e) {
        return jsonResponse({ ok: false, error: 'invalid-json' }, 400, CORS);
      }
      const restored = { settings: false, users: false };
      if (payload && payload.settings) {
        restored.settings = (await writeSettings(env, payload.settings)).persisted;
      }
      if (payload && Array.isArray(payload.users)) {
        restored.users = await writeUsers(env, payload.users.map(normalizeUser));
      }
      return jsonResponse({ ok: true, restored: restored, hasKv: hasKv(env) }, 200, CORS);
    }
    return jsonResponse({ ok: false, error: 'method-not-allowed' }, 405, CORS);
  }

  if (path === '/api/scan') {
    const perRange = Math.max(1, Math.min(32, Number(url.searchParams.get('per') || 8)));
    const list = expandRanges([url.searchParams.get('ips'), url.searchParams.get('ranges')].filter(Boolean).join(','), perRange, true).slice(0, 96);
    if (!list.length) return jsonResponse({ ok: false, error: 'ips or ranges required' }, 400, CORS);
    const timeout = Math.max(1000, Math.min(8000, Number(url.searchParams.get('timeout') || 4000)));
    const concurrency = Math.max(1, Math.min(32, Number(url.searchParams.get('concurrency') || 16)));
    const extraSnis = splitCsv(url.searchParams.get('snis')).map((s) => s.trim().toLowerCase())
      .filter((s) => s && validAddress(s) && !isIpLiteral(s) && s !== String(host).toLowerCase())
      .slice(0, 3);
    const results = [];
    let cursor = 0;
    const workers = Array.from({ length: Math.min(concurrency, list.length) }, async () => {
      for (;;) {
        const index = cursor++;
        if (index >= list.length) return;
        const probe = await probeIp(list[index], timeout, host, env);
        const enriched = Object.assign({}, probe, { location: locationFromColo(probe.colo) });
        if (extraSnis.length && probe.ok) {
          enriched.snisOk = {};
          for (const altSni of extraSnis) {
            const alt = await probeIp(list[index], timeout, altSni, env).catch(() => null);
            enriched.snisOk[altSni] = { ok: !!(alt && alt.ok), ms: alt ? alt.ms : 0 };
          }
        }
        results.push(enriched);
      }
    });
    await Promise.all(workers);
    const sorted = results.sort((a, b) => (a.ok === b.ok ? (a.ms || 99999) - (b.ms || 99999) : a.ok ? -1 : 1));
    const alive = sorted.filter((result) => result.ok);
    let saved = false;
    if (url.searchParams.get('save') === '1') {
      const auth = await requirePanelAuth(request, env);
      if (!auth.ok) return auth.response;
      const verified = alive.map((result) => ({
        ip: result.ip,
        colo: result.colo || '',
        countryCode: result.countryCode || '',
        countryName: result.countryName || 'Cloudflare edge',
        range: result.range || '',
        checkedAt: Date.now(),
      }));
      const persisted = await writeSettings(env, {
        configs: { verified: verified, verifiedScanned: true, verifiedAt: Date.now() },
      });
      saved = persisted.persisted;
    }
    return jsonResponse({ ok: true, count: sorted.length, alive: alive.length, saved: saved, results: sorted }, 200, CORS);
  }

  if (path === '/token' || path === '/api/token-url') {
    if (path === '/token') return Response.redirect(CF_TOKEN_TEMPLATE_URL, 302);
    return jsonResponse({ ok: true, url: CF_TOKEN_TEMPLATE_URL }, 200, CORS);
  }

  if (path === '/api/ir-ips') {
    const irPool = IR_CLEAN_IPS.concat(COMMUNITY_IPS);
    return jsonResponse({ ok: true, count: irPool.length, ips: irPool }, 200, CORS);
  }

  if (path === '/api/proxy-ips') {
    const settings = await readSettings(env);
    return jsonResponse({ ok: true, ips: proxyIpList(env, settings), defaults: DEFAULT_PROXY_IPS, note: 'settings.tunnel.proxyIps > PROXY_IPS env > built-in defaults' }, 200, CORS);
  }

  /* BPB-style proxy-ip service: /proxy-ip (plain list) and /proxy-ip/get (JSON). */
  if (path === '/proxy-ip' || path === '/proxyip' || path === '/proxy-ip/get') {
    const settings = await readSettings(env);
    const ips = proxyIpList(env, settings);
    if (path === '/proxy-ip/get') {
      return jsonResponse({ success: true, body: ips.map((ip) => ({ ip: ip })), message: '' }, 200, CORS);
    }
    if (url.searchParams.get('json') === '1') {
      return jsonResponse({ ok: true, count: ips.length, ips: ips }, 200, CORS);
    }
    const one = url.searchParams.get('all') === '1' ? ips.join('\n') + '\n' : ips[Math.floor(Math.random() * ips.length)] + '\n';
    return new Response(one, { status: 200, headers: Object.assign({}, CORS, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' }) });
  }

  if (path === '/api/proxy-ips/refresh' && request.method === 'POST') {
    const auth = await requirePanelAuth(request, env);
    if (!auth.ok) return auth.response;
    let source = String(env.PROXY_IP_SOURCE || '').trim();
    try {
      const body = await request.json();
      if (body && body.source) source = String(body.source).trim();
    } catch (e) { /* body optional */ }
    if (!source) return jsonResponse({ ok: false, error: 'source-required (set PROXY_IP_SOURCE or pass {source})' }, 400, CORS);
    const result = await refreshProxyIps(env, source);
    return jsonResponse(result, 200, CORS);
  }

  if (path === '/api/health-check' && request.method === 'POST') {
    const auth = await requirePanelAuth(request, env);
    if (!auth.ok) return auth.response;
    return healthCheck(env, host);
  }

  if (path === '/api/login' && request.method === 'POST') {
    return handleLogin(request, env);
  }

  if (path === '/api/logout') {
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: Object.assign({}, CORS, {
        'content-type': 'application/json; charset=utf-8',
        'set-cookie': 'catpanel_auth=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax',
      }),
    });
  }

  /* per-user subscription: /u/<token>[/format] */
  if (path === '/u' || path.startsWith('/u/') || path.startsWith('/info/')) {
    return handleUserSubscription(request, url, env, host, path, ctx);
  }

  /* encrypted DNS resolver */
  if (path === '/dns-query' || path === '/dns-query/') {
    return handleDnsQuery(request, env);
  }

  if (path === '/health') {
    const ips = splitCsv(env.CF_IPS);
    const cf = request.cf || {};
    return jsonResponse({
      ok: true,
      panel: 'cat-panel',
      version: CAT_PANEL_VERSION,
      sni: effectiveSni(host, env),
      uuid: String(env.UUID || '').trim() ? 'explicit' : 'derived',
      remote: !!env.REMOTE,
      cleanIps: ips.length,
      sniWhitelist: Array.from(allowedSnis(host, env)),
      doh: 'https://' + host + '/dns-query',
      dnsUpstream: dohUpstream(env),
      colo: cf.colo || null,
      locked: !!(await panelPassword(env, host)),
      proxyIps: proxyIpList(env, await readSettings(env)).length,
      scanTargets: scanTargets(env).length,
    }, 200, CORS);
  }

  if (path === '/' || path === '/index.html' || path === '/panel') {
    const settings = await readSettings(env);
    const state = panelState(host, env, uuid, request, settings);
    return handlePanelRequest(request, url, env, host, uuid, state);
  }

  return notFoundResponse();
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await fetchHandler(request, env || {}, ctx);
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
  CF_TOKEN_TEMPLATE_URL,
  parseVless,
  parseVlessHeader,
  parseSocksAddress,
  parseTrojanRequest,
  trojanPassword,
  trojanHash,
  sha224Hex,
  isCloudflareIp,
  relayTcp,
  decodeEarlyData,
  __setSockets,
  proxyIpList,
  splitHostPort,
  dialTarget,
  DEFAULT_PROXY_IPS,
  websocketReadable,
  handleTunnelConnection,
  readSettings,
  writeSettings,
  readUsers,
  writeUsers,
  normalizeUser,
  userTrafficLeft,
  userLiveUsed,
  bufferedBytes,
  accountTraffic,
  flushTraffic,
  userReasonBlocked,
  tunnelAuth,
  trojanAuthorized,
  kvBinding,
  parseHttpRequest,
  buildSubLinks,
  buildConfigEntries,
  configOptions,
  defaultConfigOptions,
  locationFromColo,
  configName,
  TLS_PORTS,
  PLAIN_PORTS,
  DEFAULT_CLEAN_ADDRESSES,
  buildClashYaml,
  buildSingboxConfig,
  buildAllConfigs,
  httpForward,
  sendHttpError,
  sniAllowed,
  effectiveSni,
  allowedSnis,
  resolveUuid,
  deriveUuid,
  qrEncode,
  qrSvg,
  vlessLink,
  trojanLink,
  scanTargets,
  scanRanges,
  sampleSubnet,
  expandRanges,
  probeIp,
  probeDnsUpstream,
  resolveHost,
  safeUpstreamOverride,
  panelState,
  panelShell,
  loginHtml,
  handleUsersApi,
  handleUserSubscription,
  userState,
  subscriptionUserinfo,
  appDeepLinks,
  userInfoHtml,
  handleLogin,
  requirePanelAuth,
  sha256Hex,
  newUuid,
  newToken,
  redactSettings,
  IR_CLEAN_IPS,
  DNS_PRESETS,
  SCAN_RANGES,
  DEFAULT_SETTINGS,
  deepMerge,
  panelPassword,
  readBruteState,
  notFoundHtml,
  dohUpstream,
  isIpLiteral,
  fetchHandler,
};
