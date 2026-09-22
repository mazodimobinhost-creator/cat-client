/**
 * 🐱 Cat Panel — single-file Cloudflare Worker panel (VLESS / Trojan / WARP / DoH)
 *
 * Version: 4.0.0 — "purple night" edition. Real data plane (VLESS/Trojan raw TCP
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
 *  REMOTE         Optional wss:// relay for full-TCP tunnel mode (BackPack etc.)
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

const CAT_PANEL_VERSION = '4.0.0';
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

function htmlResponse(body, status = 200) {
  return new Response(body, {
    status,
    headers: { 'content-type': 'text/html; charset=utf-8' },
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
  // v4 data plane: VLESS/Trojan with native TCP relay, proxy-IP WS relay and
  // HTTP forwarding as the last resort (see handleTunnelConnection).
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

async function kvPut(env, key, value) {
  const store = kvBinding(env);
  if (!store) return false;
  try {
    await store.put(key, value);
    return true;
  } catch (e) {
    return false;
  }
}

const DEFAULT_SETTINGS = {
  title: 'Cat Panel',
  panelPassword: '',
  theme: 'violet',
  dns: {
    upstream: 'https://dns.google/dns-query',
    blockAds: false,
    blockNsfw: false,
  },
  tunnel: {
    proxyIps: [],
    preferConnect: true,
    fragment: '1-3',
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
  if (env.DNS_UPSTREAM) merged.dns.upstream = String(env.DNS_UPSTREAM);
  if (env.PROXY_IPS) merged.tunnel.proxyIps = splitCsv(env.PROXY_IPS);
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
  quotaGb: 0,
  usedBytes: 0,
  usedRequests: 0,
  expireAt: 0,
  deviceLimit: 0,
  enabled: true,
  note: '',
  createdAt: 0,
};

function normalizeUser(raw) {
  const user = Object.assign({}, USER_DEFAULTS, raw || {});
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
  return Math.max(0, quota - (Number(user.usedBytes) || 0));
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

/** Debounced traffic accounting so a busy tunnel does not hammer KV. */
const trafficBuffers = new Map();

function accountTraffic(env, uuid, sentBytes, receivedBytes) {
  if (!uuid) return;
  const key = uuid.toLowerCase();
  const entry = trafficBuffers.get(key) || { sent: 0, received: 0, lastFlush: Date.now(), users: null };
  entry.sent += sentBytes || 0;
  entry.received += receivedBytes || 0;
  trafficBuffers.set(key, entry);
  if (Date.now() - entry.lastFlush < 15000) return;
  void flushTraffic(env, key);
}

async function flushTraffic(env, uuidKey) {
  const entry = trafficBuffers.get(uuidKey);
  if (!entry) return;
  const delta = entry.sent + entry.received;
  entry.sent = 0;
  entry.received = 0;
  entry.lastFlush = Date.now();
  if (delta <= 0 || !hasKv(env)) return;
  const users = await readUsers(env);
  const user = findUserByUuid(users, uuidKey);
  if (!user) return;
  user.usedBytes = (Number(user.usedBytes) || 0) + delta;
  await writeUsers(env, users);
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
/* VLESS / Trojan relay — raw TCP through cloudflare:sockets when        */
/* available, WS relay through PROXY_IPS as the fallback, HTTP forward   */
/* as the last resort (keeps the old behaviour for plain HTTP traffic).  */
/* ------------------------------------------------------------------ */

let socketsModulePromise = null;

/** `cloudflare:sockets` only exists inside Workers; tests get null. */
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

/** SOCKS5-style address block: ATYP + ADDR [+ PORT]. Trojan includes the port, VLESS does not. */
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
    host = '[' + groups.join(':') + ']';
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
 */
function parseVlessHeader(bytes) {
  if (!bytes || bytes.length < 24) return null;
  const version = bytes[0];
  const uuidBytes = bytes.subarray(1, 17);
  const hex = Array.from(uuidBytes).map((b) => b.toString(16).padStart(2, '0')).join('');
  const uuid = hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' + hex.slice(20);
  const addonLength = bytes[17];
  const command = bytes[18];
  const offset = 19 + addonLength;
  if (bytes.length < offset + 3) return null;
  const port = (bytes[offset] << 8) | bytes[offset + 1];
  const address = parseSocksAddress(bytes, offset + 2, false);
  if (!address) return null;
  return {
    version: version,
    uuid: uuid,
    command: command,
    host: address.host,
    port: port,
    rest: address.rest,
  };
}

function trojanPassword(bytes) {
  if (!bytes || bytes.length < 56) return null;
  const hex = new TextDecoder().decode(bytes.subarray(0, 56));
  if (!/^[0-9a-f]{56}$/i.test(hex)) return null;
  const after = bytes.subarray(56);
  // CRLF after the password
  const text = new TextDecoder().decode(after.subarray(0, 2));
  const rest = text === '\r\n' ? after.subarray(2) : after;
  return { password: hex.toLowerCase(), rest: rest };
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
  // Final length field spans 8 bytes; the write above covers the low 64 bits.
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

async function trojanAuthorized(env, settings, hash) {
  const candidates = [];
  if (env.TROJAN_PASS) candidates.push(env.TROJAN_PASS);
  const users = await readUsers(env);
  users.forEach((user) => {
    if (user.enabled !== false && user.uuid) candidates.push(user.uuid);
  });
  for (const password of candidates) {
    const digest = await trojanHash(String(password));
    if (digest === hash) return { ok: true, password: String(password), users: users };
  }
  return { ok: false, users: users };
}

function isCloudflareIp(ip) {
  const value = ipToLong(ip);
  if (value === null) return false;
  return CF_CIDR_RANGES.some((range) => ipInCidr(value, range));
}

const CF_CIDR_RANGES = [
  '104.16.0.0/13', '104.24.0.0/14', '172.64.0.0/13', '162.158.0.0/15',
  '131.0.72.0/22', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22',
  '141.101.64.0/18', '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20',
  '197.234.240.0/22', '198.41.128.0/17', '173.245.48.0/20', '162.159.192.0/24',
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

function releaseAll(closeFns) {
  closeFns.forEach((fn) => {
    try { fn(); } catch (e) { /* already closed */ }
  });
}

/**
 * Relay raw TCP: client WS <-> (cloudflare:sockets | proxy-IP WS).
 * `headerBytes` is the unparsed protocol header for proxy-IP mode, which needs
 * to see the original VLESS/Trojan preamble.
 */
async function relayTcp(clientWs, options) {
  const firstPayload = options.firstPayload;
  const target = options.target;
  const proxyIps = options.proxyIps || [];
  const sent = { bytes: 0 };
  const received = { bytes: 0 };

  // 1. Native outbound TCP through Workers sockets (fastest, no extra hop).
  if (options.preferConnect && !isCloudflareIp(target.host)) {
    const sockets = await loadSockets();
    if (sockets) {
      let socket = null;
      try {
        socket = sockets.connect({ hostname: target.host, port: target.port });
        const writer = socket.writable.getWriter();
        if (firstPayload && firstPayload.byteLength) {
          await writer.write(firstPayload);
          sent.bytes += firstPayload.byteLength;
        }
        clientWs.addEventListener('message', async (event) => {
          try {
            const chunk = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : event.data;
            await writer.write(chunk);
            sent.bytes += chunk.byteLength || 0;
          } catch (e) {
            try { await writer.close(); } catch (err) {}
          }
        });
        const reader = socket.readable.getReader();
        for (;;) {
          const chunk = await reader.read();
          if (chunk.done) break;
          if (chunk.value && clientWs.readyState === 1) {
            clientWs.send(chunk.value);
            received.bytes += chunk.value.byteLength || 0;
          }
        }
        try { clientWs.close(1000); } catch (e) {}
      } catch (e) {
        // fall through to proxy-IP / HTTP fallback
      } finally {
        if (options.onClose) options.onClose(sent.bytes, received.bytes);
        if (socket && socket.close) { try { socket.close(); } catch (e) {} }
      }
      if (sent.bytes > 0 || received.bytes > 0) return true;
    }
  }

  // 2. WS relay to a proxy IP (BPB-style). The proxy speaks the same
  //    VLESS/Trojan protocol, so the original header frame is replayed.
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
        } catch (e) {}
      });
      clientWs.addEventListener('message', (event) => {
        try {
          remote.send(event.data);
          sent.bytes += event.data && event.data.byteLength ? event.data.byteLength : 0;
        } catch (e) {}
      });
      const closeBoth = () => {
        try { remote.close(); } catch (e) {}
        try { clientWs.close(); } catch (e) {}
        if (options.onClose) options.onClose(sent.bytes, received.bytes);
      };
      remote.addEventListener('close', closeBoth);
      clientWs.addEventListener('close', closeBoth);
      return true;
    } catch (e) {
      // try the next proxy IP
    }
  }

  // 3. Last resort: treat the payload as HTTP (keeps the legacy behaviour).
  if (options.httpFallback) {
    const parsed = parseVless(options.headerBytes || new Uint8Array(0));
    if (parsed) {
      await httpForward(clientWs, parsed);
      if (options.onClose) options.onClose(sent.bytes, received.bytes);
      return true;
    }
  }
  return false;
}

function closeWithError(ws, status, message) {
  sendHttpError(ws, status, message);
}

/**
 * Cat Panel data plane: VLESS-WS and Trojan-WS with KV-backed auth,
 * per-user quota accounting and connection limits.
 */
async function handleTunnelConnection(ws, env, options = {}) {
  let done = false;
  ws.addEventListener('message', async (event) => {
    if (done) return;
    done = true;
    const raw = event.data;
    const bytes = raw instanceof ArrayBuffer
      ? new Uint8Array(raw)
      : raw && raw.byteLength !== undefined
        ? new Uint8Array(raw)
        : new TextEncoder().encode(String(raw || ''));

    const settings = await readSettings(env);
    const proxyIps = settings.tunnel.proxyIps.length ? settings.tunnel.proxyIps : splitCsv(env.PROXY_IPS);
    const preferConnect = settings.tunnel.preferConnect;

    const vless = parseVlessHeader(bytes);
    if (vless) {
      const auth = await tunnelAuth(env, vless.uuid, settings);
      if (!auth.ok) {
        closeWithError(ws, 403, 'Cat Panel: ' + auth.error);
        return;
      }
      if (vless.command === 1) {
        closeWithError(ws, 405, 'Cat Panel: UDP over WS is not supported');
        return;
      }
      if (auth.user && !acquireConnection(vless.uuid, auth.user.deviceLimit)) {
        closeWithError(ws, 429, 'Cat Panel: device limit reached');
        return;
      }
      const target = { host: vless.host, port: vless.port };
      const relayed = await relayTcp(ws, {
        firstPayload: vless.rest,
        headerBytes: bytes,
        target: target,
        proxyIps: proxyIps,
        preferConnect: preferConnect,
        path: options.path,
        httpFallback: true,
        onClose: (sent, received) => {
          if (auth.user) {
            accountTraffic(env, vless.uuid, sent, received);
            releaseConnection(vless.uuid);
          }
        },
      });
      if (!relayed) closeWithError(ws, 502, 'Cat Panel: no route to ' + target.host + ':' + target.port);
      return;
    }

    const trojan = trojanPassword(bytes);
    if (trojan) {
      const auth = await trojanAuthorized(env, settings, trojan.password);
      if (!auth.ok) {
        closeWithError(ws, 403, 'Cat Panel: trojan password rejected');
        return;
      }
      const request = parseTrojanRequest(trojan.rest);
      if (!request) {
        closeWithError(ws, 400, 'Cat Panel: malformed trojan request');
        return;
      }
      const relayed = await relayTcp(ws, {
        firstPayload: request.payload,
        headerBytes: bytes,
        target: { host: request.host, port: request.port },
        proxyIps: proxyIps,
        preferConnect: preferConnect,
        path: options.path,
      });
      if (!relayed) closeWithError(ws, 502, 'Cat Panel: no route to ' + request.host);
      return;
    }

    // REMOTE mode tunnels every frame to a websocket upstream untouched.
    if (env.REMOTE) {
      await tunnelToRemote(String(env.REMOTE), ws, bytes);
      return;
    }
    closeWithError(ws, 400, 'Cat Panel: unrecognised tunnel handshake');
  });
  ws.addEventListener('error', () => {});
  ws.addEventListener('close', () => { done = true; });
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
  const payload = address.rest.length > 2 && address.rest[0] === 13 && address.rest[1] === 10
    ? address.rest.subarray(2)
    : address.rest;
  return { command: command, host: address.host, port: address.port, payload: payload };
}

/* ------------------------------------------------------------------ */
/* subscription content                                                */
/* ------------------------------------------------------------------ */

function panelHosts(host, env) {
  const ips = splitCsv(env.CF_IPS);
  return { ips: ips, all: [String(host)].concat(ips) };
}

function panelPaths(env) {
  return {
    vlessPath: String(env.VLESS_PATH || '/ws?ed=2048'),
    trojanPath: String(env.TROJAN_PATH || '/trojan'),
    port: Number(env.PORT || 443),
  };
}

/** Build a VLESS-WS share link (used for the host itself and for clean IPs). */
function vlessLink(host, env, uuid, addr, name, overrides = {}) {
  const paths = panelPaths(env);
  const sni = overrides.sni || effectiveSni(host, env);
  const port = overrides.port || paths.port;
  const hostHeader = overrides.hostHeader || String(host);
  const path = overrides.path || paths.vlessPath;
  return 'vless://' + uuid + '@' + formatAddr(addr) + ':' + port +
    '?encryption=none&security=tls&sni=' + encodeURIComponent(sni) +
    '&type=ws&path=' + encodeURIComponent(path) +
    '&host=' + encodeURIComponent(hostHeader) +
    '&alpn=h2,http%2F1.1&fp=randomized' +
    '#' + encodeURIComponent(name);
}

/** Build a Trojan-WS share link. */
function trojanLink(host, env, uuid, addr, name, overrides = {}) {
  const paths = panelPaths(env);
  const sni = overrides.sni || effectiveSni(host, env);
  const port = overrides.port || paths.port;
  const hostHeader = overrides.hostHeader || String(host);
  const path = overrides.path || paths.trojanPath;
  const pass = String(env.TROJAN_PASS || uuid);
  return 'trojan://' + encodeURIComponent(pass) + '@' + formatAddr(addr) + ':' + port +
    '?security=tls&sni=' + encodeURIComponent(sni) +
    '&type=ws&path=' + encodeURIComponent(path) +
    '&host=' + encodeURIComponent(hostHeader) +
    '&alpn=h2,http%2F1.1&fp=randomized' +
    '#' + encodeURIComponent(name);
}

function buildSubLinks(host, env, uuid) {
  const ips = splitCsv(env.CF_IPS);
  const links = [];
  links.push(vlessLink(host, env, uuid, host, 'Cat VLESS WS'));
  links.push(trojanLink(host, env, uuid, host, 'Cat Trojan WS'));
  for (const ip of ips) {
    links.push(vlessLink(host, env, uuid, ip, 'Cat VLESS WS ' + ip));
    links.push(trojanLink(host, env, uuid, ip, 'Cat Trojan WS ' + ip));
  }
  if (String(env.ENABLE_WARP).toLowerCase() !== 'false') links.push('warp://#Cat WARP');
  return links;
}

function buildClashYaml(host, env, uuid) {
  const paths = panelPaths(env);
  const port = paths.port;
  const sni = effectiveSni(host, env);
  const vlessPath = paths.vlessPath;
  const trojanPath = paths.trojanPath;
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const ips = splitCsv(env.CF_IPS);
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
    '    - https://' + host + '/dns-query\n' +
    '    - 1.1.1.1\n' +
    '  fallback:\n' +
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
    '  - GEOIP,IR,direct\n' +
    '  - MATCH,Proxy\n'
  );
}

function buildSingboxConfig(host, env, uuid) {
  const paths = panelPaths(env);
  const port = paths.port;
  const sni = effectiveSni(host, env);
  const ips = splitCsv(env.CF_IPS).slice(0, 6);
  const trojanPass = String(env.TROJAN_PASS || uuid);
  const outbounds = [];
  const tags = [];
  const push = (tag, outbound) => {
    tags.push(tag);
    outbounds.push(outbound);
  };

  push('Cat VLESS', {
    type: 'vless',
    tag: 'Cat VLESS',
    server: host,
    server_port: port,
    uuid: uuid,
    tls: { enabled: true, server_name: sni, utls: { enabled: true, fingerprint: 'chrome' } },
    transport: { type: 'ws', path: paths.vlessPath, headers: { Host: host } },
  });
  push('Cat Trojan', {
    type: 'trojan',
    tag: 'Cat Trojan',
    server: host,
    server_port: port,
    password: trojanPass,
    tls: { enabled: true, server_name: sni, utls: { enabled: true, fingerprint: 'chrome' } },
    transport: { type: 'ws', path: paths.trojanPath, headers: { Host: host } },
  });
  ips.forEach((ip, index) => {
    push('Cat VLESS ' + ip, {
      type: 'vless',
      tag: 'Cat VLESS ' + ip,
      server: ip,
      server_port: port,
      uuid: uuid,
      tls: { enabled: true, server_name: sni, utls: { enabled: true, fingerprint: 'chrome' } },
      transport: { type: 'ws', path: paths.vlessPath, headers: { Host: host } },
    });
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
        { type: 'selector', tag: 'proxy', outbounds: tags, default: tags[0] },
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

function buildAllConfigs(host, env, uuid) {
  const ips = splitCsv(env.CF_IPS);
  return {
    panel: 'cat-panel',
    version: CAT_PANEL_VERSION,
    host: host,
    sni: effectiveSni(host, env),
    uuid: uuid,
    port: panelPaths(env).port,
    paths: { vless: panelPaths(env).vlessPath, trojan: panelPaths(env).trojanPath },
    cleanIps: ips,
    sniWhitelist: Array.from(allowedSnis(host, env)),
    remoteTunnel: !!env.REMOTE,
    doh: 'https://' + host + '/dns-query',
    subscription: 'https://' + host + '/sub',
    links: buildSubLinks(host, env, uuid),
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

/** Pick `count` evenly spread addresses from a CIDR block. */
function sampleSubnet(cidr, count = 4) {
  const parts = String(cidr).split('/');
  const base = ipToLong(parts[0]);
  if (base === null) return [];
  const prefix = Number(parts[1]);
  if (!Number.isInteger(prefix) || prefix < 8 || prefix > 32) return [];
  const hostBits = 32 - prefix;
  const total = Math.pow(2, Math.min(hostBits, 20));
  const step = Math.max(1, Math.floor(total / (count + 1)));
  const out = [];
  for (let i = 1; i <= count; i++) out.push(longToIp((base + i * step) >>> 0));
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
  const custom = splitCsv(env.SCAN_IPS);
  custom.forEach((item) => {
    if (item.includes('/')) sampleSubnet(item, 4).forEach(push);
    else push(item);
  });
  for (const range of SCAN_RANGES) sampleSubnet(range, 4).forEach(push);
  return out;
}

/** Server-side latency probe: TCP+TLS+HTTP against https://<ip>/cdn-cgi/trace. */
async function probeIp(ip, timeoutMs = 4000) {
  const started = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const answer = await fetch('https://' + formatAddr(ip) + '/cdn-cgi/trace', {
      signal: controller.signal,
      headers: { 'user-agent': 'CatPanel/' + CAT_PANEL_VERSION },
      cf: { cacheTtl: 0, cacheEverything: false },
    });
    const ms = Date.now() - started;
    clearTimeout(timer);
    if (!answer.ok) return { ip: ip, ok: false, ms: ms, status: answer.status };
    const text = await answer.text();
    const colo = (text.match(/^colo=(\S+)/m) || [])[1] || '';
    return { ip: ip, ok: true, ms: ms, colo: colo };
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
    '--bg:#06030c;--bg-soft:#0b0517;--surface:rgba(255,255,255,.045);--surface-2:rgba(255,255,255,.08);',
    '--line:rgba(168,85,247,.24);--line-soft:rgba(255,255,255,.08);',
    '--text:#f4f4f5;--muted:#a1a1aa;--dim:#71717a;',
    '--accent:#a855f7;--accent-2:#7c3aed;--accent-3:#d946ef;--on-accent:#fff;',
    '--glow-a:rgba(168,85,247,.22);--glow-b:rgba(217,70,239,.16);',
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
    'min-height:100vh;line-height:1.7;padding-bottom:96px;',
    'background-image:radial-gradient(900px 500px at 12% -8%,var(--glow-a),transparent 60%),radial-gradient(700px 420px at 96% 4%,var(--glow-b),transparent 62%)}',
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
    'nav.tabs{position:fixed;inset-inline:0;bottom:0;z-index:40;backdrop-filter:blur(20px);background:color-mix(in srgb,var(--bg) 88%,transparent);border-top:1px solid var(--line-soft);padding:8px 10px calc(8px + env(safe-area-inset-bottom))}',
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

function panelState(host, env, uuid, request) {
  const ips = splitCsv(env.CF_IPS);
  const cf = (request && request.cf) || {};
  return {
    version: CAT_PANEL_VERSION,
    title: String(env.PANEL_TITLE || 'Cat Panel'),
    host: host,
    sni: effectiveSni(host, env),
    uuid: uuid,
    port: panelPaths(env).port,
    vlessPath: panelPaths(env).vlessPath,
    trojanPath: panelPaths(env).trojanPath,
    trojanPass: String(env.TROJAN_PASS || uuid),
    cleanIps: ips,
    sniList: Array.from(allowedSnis(host, env)),
    remote: !!env.REMOTE,
    warp: String(env.ENABLE_WARP).toLowerCase() !== 'false',
    panelLocked: !!String(env.PANEL_PASSWORD || ''),
    colo: cf.colo || '',
    country: cf.country || '',
    city: cf.city || '',
    asn: cf.asOrganization || '',
    dnsUpstream: dohUpstream(env),
    dnsPresets: DNS_PRESETS,
    irIps: IR_CLEAN_IPS,
    hasKv: hasKv(env),
    usersApi: '/api/users',
    dotPresets: DNS_PRESETS.map((p) => ({ name: p.name, host: p.dot })),
    repo: CAT_REPO,
    subUrl: 'https://' + host + '/sub',
    clashUrl: 'https://' + host + '/clash',
    singboxUrl: 'https://' + host + '/singbox',
    allUrl: 'https://' + host + '/all',
    dohUrl: 'https://' + host + '/dns-query',
    qrBase: 'https://' + host + '/qr.svg',
    scanTargets: scanTargets(env),
    deepLink: 'catclient://add-sub?url=' + encodeURIComponent('https://' + host + '/sub') + '&name=' + encodeURIComponent('Cat Panel'),
  };
}

function loginHtml(title, error) {
  return '<!doctype html><html data-theme="dark" data-lang="fa"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1"><title>' + esc(title) + '</title>' +
    '<style>' + css() + '</style></head><body data-lang="fa">' +
    '<div class="wrap" style="max-width:420px;padding-top:12vh">' +
    '<div class="card glow" style="text-align:center">' +
    '<div class="brand" style="justify-content:center;margin-bottom:12px"><span class="cat">' + catLogo(28) + '</span>' + esc(title) + '</div>' +
    '<p style="margin-bottom:14px">رمز پنل را وارد کنید / Enter the panel password</p>' +
    '<form method="get" action="/">' +
    '<label class="field"><span>Password</span><input type="password" name="p" autofocus autocomplete="current-password"></label>' +
    '<button class="btn" type="submit" style="width:100%">ورود / Unlock</button>' +
    '</form></div></div></body></html>';
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
    '<header class="top"><div class="top-inner">' +
    '<div class="brand"><span class="cat">' + catLogo(26) + '</span><span><b id="brandName">' + esc(state.title) + '</b>' +
    '<small id="brandSub">پنل کلودفلر شخصی شما</small></span></div>' +
    '<span class="spacer"></span>' +
    '<span class="pill ok" id="onlinePill">آنلاین</span>' +
    '<button class="icon-btn" id="themeBtn" title="تم / Theme">🎨</button>' +
    '<button class="icon-btn" id="langBtn" title="Language">EN</button>' +
    '<div class="theme-menu" id="themeMenu">' + themeMenuHtml() + '</div>' +
    '</div></header>' +

    '<div class="wrap">' + homeTabHtml(state) + configsTabHtml(state) + scannerTabHtml() +
      dnsTabHtml(state) + usersTabHtml(state) + toolsTabHtml(state) + helpTabHtml(state) + '</div>' +

    '<nav class="tabs"><div class="inner">' +
    navButton('home', 'خانه', '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>') +
    navButton('configs', 'کانفیگ‌ها', '<path d="M4 6h16M4 12h16M4 18h10"/>') +
    navButton('scanner', 'اسکنر', '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>') +
    navButton('users', 'کاربران', '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-5.5 6.5-5.5S15.5 16.4 15.5 20"/><path d="M17 8.5a3 3 0 1 0 0-6"/><path d="M17.5 14.2c2.6.5 4 2.3 4 5.3"/>') +
    navButton('dns', 'DNS', '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z"/><path d="M3.5 9h17M3.5 15h17M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"/>') +
    navButton('tools', 'ابزارها', '<path d="M14.7 6.3a4 4 0 0 1-5.4 5.4L4 17v3h3l5.3-5.3a4 4 0 0 1 5.4-5.4l-2.6 2.6"/>') +
    navButton('help', 'راهنما', '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.3"/><path d="M12 17h.01"/>') +
    '</div></nav>' +

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
    statCard('آی‌پی‌های تمیز', String(state.cleanIps.length)) +
    '</div></div>' +

    '<div class="card"><h2><span class="dot"></span><span data-i18n="subTitle">لینک سابسکریپشن</span></h2>' +
    '<div class="chips" id="subFormats">' +
    '<button class="chip active" data-format="sub" data-url="' + esc(state.subUrl) + '">لینک ساب</button>' +
    '<button class="chip" data-format="sub64" data-url="' + esc(state.subUrl) + '64">Base64</button>' +
    '<button class="chip" data-format="clash" data-url="' + esc(state.clashUrl) + '">Clash / Mihomo</button>' +
    '<button class="chip" data-format="singbox" data-url="' + esc(state.singboxUrl) + '">Sing-box</button>' +
    '<button class="chip" data-format="all" data-url="' + esc(state.allUrl) + '">همه‌چیز (JSON)</button>' +
    '</div>' +
    '<div class="link-row" style="margin-top:10px"><span class="grow" id="subUrlText">' + esc(state.subUrl) + '</span>' +
    '<button class="btn tiny" data-copy-target="subUrlText">کپی</button>' +
    '<button class="btn ghost tiny" data-qr-target="subUrlText">QR</button></div>' +
    '<div class="row" style="margin-top:10px">' +
    '<a class="btn" href="' + esc(state.deepLink) + '">🐱 افزودن به Cat Client</a>' +
    '<button class="btn ghost" id="downloadSub">دانلود فایل کانفیگ</button>' +
    '<button class="btn ghost" id="copyAllLinks">کپی همهٔ کانفیگ‌ها</button>' +
    '</div>' +
    '<p class="muted" style="margin-top:8px">در Cat Client → سابسکریپشن → + → لینک را وارد کن. هر بار «بروزرسانی» بزنی، آی‌پی‌های تمیز و تنظیمات پنل دوباره خوانده می‌شوند.</p>' +
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
  return '<section class="tab" data-tab-panel="configs">' +
    '<div class="card glow"><h2><span class="dot"></span><span data-i18n="singleTitle">ساخت کانفیگ تکی</span></h2>' +
    '<p>یک آدرس دلخواه بده (آی‌پی تمیز، دامنهٔ خودت یا دامنهٔ دیگر) و کانفیگ تکی VLESS/Trojan با SNI پنل بساز — آمادهٔ QR، کپی یا فرستادن به اپ.</p>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>آدرس سرور (IP یا دامنه)</span><input id="singleAddr" dir="ltr" value="' + esc(state.host) + '"></label>' +
    '<label class="field"><span>نام کانفیگ</span><input id="singleName" value="Cat Single"></label>' +
    '<label class="field"><span>SNI</span><input id="singleSni" dir="ltr" value="' + esc(state.sni) + '"></label>' +
    '<label class="field"><span>پورت</span><input id="singlePort" type="number" min="1" max="65535" value="' + esc(String(state.port)) + '"></label>' +
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
    '<div class="card"><h2><span class="dot"></span><span data-i18n="configsTitle">همهٔ کانفیگ‌های آماده</span></h2>' +
    '<label class="field"><span>جستجو</span><input id="cfgSearch" placeholder="نام یا آی‌پی…"></label>' +
    '<div class="row"><button class="btn ghost tiny" data-copy-target="cfgAllText">کپی همه</button>' +
    '<button class="btn ghost tiny" id="downloadCfg">دانلود txt</button>' +
    '<button class="btn ghost tiny" id="refreshCfg">ساخت دوباره</button></div>' +
    '<div class="table-wrap" style="margin-top:12px"><table><thead><tr>' +
    '<th>#</th><th>نام</th><th>نوع</th><th>آدرس</th><th>عملیات</th></tr></thead>' +
    '<tbody id="cfgTable"></tbody></table></div>' +
    '<pre id="cfgAllText" style="display:none"></pre></div></section>';
}

function scannerTabHtml() {
  return '<section class="tab" data-tab-panel="scanner">' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="scannerTitle">اسکنر آی‌پی تمیز کلودفلر</span></h2>' +
    '<p>از همین مرورگر، سرعت واقعی هر آی‌پی را از شبکهٔ خودت اندازه می‌گیریم (نه از سرور). ستون «تأخیر» تقریبی است ولی برای پیدا کردن آی‌پی تمیزِ اپراتورت کافی است.</p>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>SNI (دامنهٔ پنل)</span><input id="scanSni"></label>' +
    '<label class="field"><span>تعداد هم‌زمان (concurrency)</span><input id="scanConc" type="number" min="1" max="64" value="12"></label>' +
    '<label class="field"><span>تایم‌اوت هر تست (ms)</span><input id="scanTimeout" type="number" min="500" max="8000" value="2500"></label>' +
    '<label class="field"><span>تعداد آی‌پی برای اسکن</span><input id="scanLimit" type="number" min="8" max="400" value="80"></label>' +
    '</div>' +
    '<label class="field"><span>آی‌پی یا رنج دلخواه (با کاما جدا کن)</span><textarea id="scanCustom" rows="2" placeholder="104.16.6.62, 172.67.0.0/24"></textarea></label>' +
    '<div class="row"><button class="btn" id="scanStart">شروع اسکن</button>' +
    '<button class="btn ghost" id="scanStop" disabled>توقف</button>' +
    '<button class="btn ghost tiny" id="scanClear">پاک کردن نتایج</button></div>' +
    '<div class="bar" style="margin-top:12px"><i id="scanBar"></i></div>' +
    '<p class="muted" id="scanStatus" style="margin-top:8px">آماده.</p>' +
    '<div class="table-wrap" style="margin-top:12px"><table><thead><tr>' +
    '<th><input type="checkbox" id="scanAll" style="width:auto"></th><th>آی‌پی</th><th>تأخیر</th><th>وضعیت</th><th>عملیات</th>' +
    '</tr></thead><tbody id="scanTable"></tbody></table></div>' +
    '<div class="row" style="margin-top:12px">' +
    '<button class="btn" id="buildFromIps">ساخت کانفیگ با آی‌پی‌های انتخابی</button>' +
    '<button class="btn ghost" id="copyBestIps">کپی آی‌پی‌های برتر</button>' +
    '</div>' +
    '<div class="grid two" style="margin-top:12px">' +
    '<label class="field"><span>راهنمای نتیجه</span><pre style="max-height:150px">• زیر ۳۰۰ms عالی است\n• ۳۰۰ تا ۷۰۰ms قابل قبول\n• بالای ۷۰۰ms یا خطا: استفاده نکن</pre></label>' +
    '</div>' +
    '</div>' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="scannerHowto">چطور از نتیجه استفاده کنم؟</span></h2>' +
    '<p>۱) آی‌پی‌های خوب را تیک بزن. ۲) «ساخت کانفیگ با آی‌پی‌های انتخابی» را بزن. ۳) لینک ساخته‌شده را کپی کن یا مستقیم با دکمهٔ «افزودن به Cat Client» وارد اپ کن. ۴) در اپ، SNI همان دامنهٔ پنل می‌ماند و فقط آدرس سرور به آی‌پی تغییر می‌کند.</p>' +
    '</div></section>';
}

function dnsTabHtml(state) {
  const dotRows = state.dotPresets.map((p) =>
    '<tr><td>' + esc(p.name) + '</td><td dir="ltr"><code>' + esc(p.host) +
    '</code></td><td><button class="btn ghost tiny" data-dot="' + esc(p.host) + '">کپی / بررسی</button></td></tr>').join('');
  return '<section class="tab" data-tab-panel="dns">' +
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
  return '<section class="tab" data-tab-panel="users">' +
    '<div class="card glow"><h2><span class="dot"></span><span data-i18n="usersTitle">کاربران پنل</span></h2>' +
    '<p>هر کاربر لینک سابسکریپشن، UUID و رمز Trojan مستقل خودش را دارد؛ حجم، تاریخ انقضا و تعداد دستگاه هم قابل تنظیم است. برای ذخیره‌سازی به بایندینگ KV نیاز است.</p>' +
    '<p class="muted" id="kvState">' + (state.hasKv ? '✅ KV متصل است — کاربران ذخیره می‌شوند.' : '⚠️ KV وصل نیست — فقط UUID اصلی کار می‌کند. یک Namespace بساز و با نام <code>CAT_KV</code> به ورکر بایند کن.') + '</p>' +
    '<div class="card" style="background:transparent;border-style:dashed"><h2><span class="dot"></span><span>کاربر جدید</span></h2>' +
    '<div class="grid two">' +
    '<label class="field"><span>نام</span><input id="uName" placeholder="Ali"></label>' +
    '<label class="field"><span>حجم (GB) — 0 یعنی نامحدود</span><input id="uQuota" type="number" min="0" step="1" value="0"></label>' +
    '<label class="field"><span>انقضا (روز) — 0 یعنی بدون انقضا</span><input id="uDays" type="number" min="0" step="1" value="0"></label>' +
    '<label class="field"><span>محدودیت دستگاه — 0 یعنی آزاد</span><input id="uDevices" type="number" min="0" step="1" value="0"></label>' +
    '</div>' +
    '<div class="row" style="margin-top:12px"><button class="btn" id="uCreate">ساخت کاربر</button>' +
    '<button class="btn ghost tiny" id="uReload">بارگذاری مجدد</button></div></div>' +
    '<div class="table-wrap"><table><thead><tr><th>#</th><th>کاربر</th><th>حجم</th><th>انقضا</th><th>سابسکریپشن</th><th>عملیات</th></tr></thead>' +
    '<tbody id="userTable"><tr><td colspan="6">در حال بارگذاری…</td></tr></tbody></table></div>' +
    '</div></section>';
}

function toolsTabHtml(state) {
  return '<section class="tab" data-tab-panel="tools">' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="toolsTitle">ابزارها و تنظیمات پنل</span></h2>' +
    '<div class="grid two">' +
    '<label class="field"><span>عنوان پنل</span><input id="tTitle" value="' + esc(state.title) + '"></label>' +
    '<label class="field"><span>رمز ورود پنل (خالی = بدون رمز)</span><input id="tPass" type="password" placeholder="••••••"></label>' +
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
  return '<section class="tab" data-tab-panel="help">' +
    '<div class="card"><h2><span class="dot"></span><span data-i18n="helpTitle">راهنمای پنل</span></h2>' +
    '<div class="steps">' +
    '<div class="step">Cloudflare → Workers &amp; Pages → Create Worker → کد را کامل جای‌گذاری کن → Deploy.</div>' +
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
    ' fa:{subTitle:"لینک سابسکریپشن",stepsTitle:"سه قدم تا اتصال",infoTitle:"اطلاعات اتصال",configsTitle:"همهٔ کانفیگ‌های آماده",singleTitle:"ساخت کانفیگ تکی",usersTitle:"کاربران پنل",toolsTitle:"ابزارها و تنظیمات",scannerTitle:"اسکنر آی‌پی تمیز",scannerHowto:"راهنمای نتیجه",dnsTitle:"DNS رمزنگاری‌شده (DoH)",dnsUpstreamTitle:"سرورهای بالادستی",dnsUseTitle:"چطور استفاده کنم؟",dnsCustomTitle:"DoH و DoT سفارشی",dotTitle:"هاست‌های DoT پیشنهادی",helpTitle:"راهنمای پنل",envTitle:"متغیرهای پنل",faqTitle:"پرسش‌های پرتکرار",online:"آنلاین",copied:"کپی شد",scanReady:"آماده.",scanning:"در حال اسکن…",done:"تمام شد"},',
    ' en:{subTitle:"Subscription link",stepsTitle:"Three steps to connect",infoTitle:"Connection details",configsTitle:"Ready-made configs",singleTitle:"Build a single config",usersTitle:"Panel users",toolsTitle:"Tools & settings",scannerTitle:"Clean-IP scanner",scannerHowto:"How to use the results",dnsTitle:"Encrypted DNS (DoH)",dnsUpstreamTitle:"Upstream resolvers",dnsUseTitle:"How to use it",dnsCustomTitle:"Custom DoH & DoT",dotTitle:"Suggested DoT hosts",helpTitle:"Panel guide",envTitle:"Panel variables",faqTitle:"FAQ",online:"online",copied:"Copied",scanReady:"Ready.",scanning:"Scanning…",done:"Finished"}',
    '};',
    'var lang="fa",theme="dark";',
    'try{lang=localStorage.getItem("catpanel.lang")||"fa";theme=localStorage.getItem("catpanel.theme")||"dark";}catch(e){}',
    'function applyLang(){',
    ' document.documentElement.setAttribute("data-lang",lang);document.body.setAttribute("data-lang",lang);',
    ' document.body.style.direction=lang==="fa"?"rtl":"ltr";',
    ' var d=I18N[lang];',
    ' $$("[data-i18n]").forEach(function(el){var k=el.getAttribute("data-i18n");if(d[k])el.textContent=d[k];});',
    ' $("#langBtn").textContent=lang==="fa"?"EN":"فا";',
    ' $("[data-nav-label=home]").textContent=lang==="fa"?"خانه":"Home";',
    ' $("[data-nav-label=configs]").textContent=lang==="fa"?"کانفیگ‌ها":"Configs";',
    ' $("[data-nav-label=scanner]").textContent=lang==="fa"?"اسکنر":"Scanner";',
    ' $("[data-nav-label=users]").textContent=lang==="fa"?"کاربران":"Users";',
    ' $("[data-nav-label=tools]").textContent=lang==="fa"?"ابزارها":"Tools";',
    ' $("[data-nav-label=dns]").textContent="DNS";',
    ' $("[data-nav-label=help]").textContent=lang==="fa"?"راهنما":"Help";',
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
    ' $$("nav.tabs button").forEach(function(b){b.classList.toggle("active",b.getAttribute("data-tab")===name)});',
    ' $$(".tab").forEach(function(s){s.classList.toggle("active",s.getAttribute("data-tab-panel")===name)});',
    ' try{localStorage.setItem("catpanel.tab",name)}catch(e){}',
    ' if(name==="users")loadUsers();if(name==="tools"){loadSelf();loadSettings();}',
    ' if(name==="scanner"){}',
    ' try{window.scrollTo({top:0,behavior:"smooth"})}catch(e){try{window.scrollTo(0,0)}catch(e2){}}',
    '}',
    '$$("nav.tabs button").forEach(function(btn){btn.addEventListener("click",function(){showTab(btn.getAttribute("data-tab"))})});',
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
    '/* ---- links ---- */',
    'function vlessLink(addr,name,sni){',
    ' return "vless://"+S.uuid+"@"+addr+":"+S.port+"?encryption=none&security=tls&sni="+encodeURIComponent(sni||S.sni)+"&type=ws&path="+encodeURIComponent(S.vlessPath)+"&host="+encodeURIComponent(S.host)+"&alpn=h2,http%2F1.1&fp=randomized#"+encodeURIComponent(name);',
    '}',
    'function trojanLink(addr,name,sni){',
    ' return "trojan://"+encodeURIComponent(S.trojanPass)+"@"+addr+":"+S.port+"?security=tls&sni="+encodeURIComponent(sni||S.sni)+"&type=ws&path="+encodeURIComponent(S.trojanPath)+"&host="+encodeURIComponent(S.host)+"&alpn=h2,http%2F1.1&fp=randomized#"+encodeURIComponent(name);',
    '}',
    'function allLinks(){var out=[];var hosts=[S.host].concat(S.cleanIps||[]);hosts.forEach(function(h){',
    ' out.push({name:h===S.host?"Cat VLESS":"Cat VLESS "+h,type:"VLESS",addr:h,link:vlessLink(h,h===S.host?"Cat VLESS":"Cat VLESS "+h)});',
    ' out.push({name:h===S.host?"Cat Trojan":"Cat Trojan "+h,type:"Trojan",addr:h,link:trojanLink(h,h===S.host?"Cat Trojan":"Cat Trojan "+h)});});',
    ' if(S.warp)out.push({name:"Cat WARP",type:"WARP",addr:"—",link:"warp://#Cat WARP"});',
    ' return out;}',
    'var CFG=allLinks();',
    'function renderConfigs(){var q=($("#cfgSearch").value||"").toLowerCase();var rows=CFG.filter(function(c){return !q||c.name.toLowerCase().indexOf(q)>=0||c.addr.toLowerCase().indexOf(q)>=0});',
    ' var html=rows.map(function(c,i){return "<tr><td>"+(i+1)+"</td><td>"+c.name+"</td><td><span class=pill>"+c.type+"</span></td><td dir=ltr>"+c.addr+"</td>"+',
    ' "<td><button class=\\"btn tiny\\" data-copy=\\""+encodeURIComponent(c.link)+"\\">کپی</button> <button class=\\"btn ghost tiny\\" data-qr=\\""+encodeURIComponent(c.link)+"\\">QR</button> <a class=\\"btn ghost tiny\\" href=\\"catclient://add-sub?url="+encodeURIComponent(c.link)+"&name="+encodeURIComponent(c.name)+"\\">افزودن</a></td></tr>"}).join("");',
    ' $("#cfgTable").innerHTML=html||"<tr><td colspan=5>موردی نیست</td></tr>";',
    ' $("#cfgAllText").textContent=CFG.map(function(c){return c.link}).join("\\n");',
    '}',
    'document.addEventListener("click",function(ev){var c=ev.target.closest("[data-copy]");if(c){copyText(decodeURIComponent(c.getAttribute("data-copy")));return;}',
    ' var q=ev.target.closest("[data-qr]");if(q){openQr(decodeURIComponent(q.getAttribute("data-qr")));}});',
    '$("#cfgSearch").addEventListener("input",renderConfigs);',
    'function download(name,text){var b=new Blob([text],{type:"text/plain;charset=utf-8"});var a=document.createElement("a");a.href=URL.createObjectURL(b);a.download=name;a.click();setTimeout(function(){URL.revokeObjectURL(a.href)},2000);}',
    '$("#downloadCfg").addEventListener("click",function(){download("cat-panel-configs.txt",CFG.map(function(c){return c.link}).join("\\n"))});',
    '$("#copyAllLinks").addEventListener("click",function(){copyText(CFG.map(function(c){return c.link}).join("\\n"))});',
    '$("#refreshCfg").addEventListener("click",function(){CFG=allLinks();renderConfigs();toast(I18N[lang].done)});',
    '$("#downloadSub").addEventListener("click",function(){fetch(S.subUrl).then(function(r){return r.text()}).then(function(t){download("cat-panel-sub.txt",t);toast(I18N[lang].done)})});',
    '$$("#subFormats .chip").forEach(function(chip){chip.addEventListener("click",function(){',
    ' $$("#subFormats .chip").forEach(function(c){c.classList.remove("active")});chip.classList.add("active");',
    ' $("#subUrlText").textContent=chip.getAttribute("data-url");});});',
    '/* ---- single-config builder ---- */',
    'var singleProto="vless";',
    '$$("#singleProto .chip").forEach(function(chip){chip.addEventListener("click",function(){',
    ' $$("#singleProto .chip").forEach(function(c){c.classList.remove("active")});chip.classList.add("active");',
    ' singleProto=chip.getAttribute("data-proto");buildSingle();});});',
    'function buildSingle(){',
    ' var addr=($("#singleAddr").value||"").trim();var name=($("#singleName").value||"Cat Single").trim();',
    ' var sni=($("#singleSni").value||S.sni).trim();var port=Number($("#singlePort").value||S.port);',
    ' var hostHeader=($("#singleHost").value||S.host).trim();var path=($("#singlePath").value||S.vlessPath).trim();',
    ' if(!addr){toast("آدرس سرور را وارد کن");return "";}',
    ' if(singleProto==="vless"){',
    '  return "vless://"+S.uuid+"@"+addr+":"+port+"?encryption=none&security=tls&sni="+encodeURIComponent(sni)+',
    '   "&type=ws&path="+encodeURIComponent(path)+"&host="+encodeURIComponent(hostHeader)+"&alpn=h2,http%2F1.1&fp=randomized#"+encodeURIComponent(name);}',
    ' return "trojan://"+encodeURIComponent(S.trojanPass)+"@"+addr+":"+port+"?security=tls&sni="+encodeURIComponent(sni)+',
    '  "&type=ws&path="+encodeURIComponent(path.indexOf("trojan")>=0?path:S.trojanPath)+"&host="+encodeURIComponent(hostHeader)+"&alpn=h2,http%2F1.1&fp=randomized#"+encodeURIComponent(name);}',
    '$("#singleBuild").addEventListener("click",function(){var link=buildSingle();if(!link)return;',
    ' $("#singleOut").textContent=link;$("#singleAdd").setAttribute("href","catclient://add-sub?url="+encodeURIComponent(link)+"&name="+encodeURIComponent("Cat Single"));',
    ' $("#singleScan").onclick=function(){location.href="catclient://scan?sni="+encodeURIComponent($("#singleSni").value||S.sni);};',
    ' copyText(link);});',
    '$("#singleCopy").addEventListener("click",function(){var link=$("#singleOut").textContent;if(!link||link==="—"){link=buildSingle();$("#singleOut").textContent=link;}copyText(link);});',
    '$("#singleQr").addEventListener("click",function(){var link=$("#singleOut").textContent;if(!link||link==="—"){link=buildSingle();$("#singleOut").textContent=link;}openQr(link);});',
    '/* ---- users ---- */',
    'function escHtml(v){return String(v).replace(/[&<>]/g,function(c){return c==="&"?"&amp;":(c==="<"?"&lt;":"&gt;")})}',
    'function userRow(u,i){',
    ' var gb=1073741824;var pct=u.quotaGb>0?Math.min(100,Math.round((u.usedBytes/(u.quotaGb*gb))*100)):0;',
    ' var exp=u.expireAt?new Date(u.expireAt).toLocaleDateString("fa-IR"):"—";',
    ' var quota=u.quotaGb>0?(u.quotaGb+" GB · "+(u.usedBytes/gb).toFixed(2)+" GB · "+pct+"%"+""):"—";',
    ' return "<tr><td>"+(i+1)+"</td><td><b>"+escHtml(u.name||"user")+"</b><div class=\'muted\' style=\'font-size:11px;direction:ltr\'>"+String(u.uuid).slice(0,18)+"…</div></td>"+',
    '  "<td>"+quota+"</td><td>"+exp+"</td><td><span class=\'pill\'>/u/"+String(u.token).slice(0,8)+"…</span></td>"+',
    '  "<td><button class=\'btn ghost tiny\' data-user-sub=\'"+u.token+"\'>کپی ساب</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-qr=\'"+u.token+"\'>QR</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-reset=\'"+u.id+"\'>ریست مصرف</button> "+',
    '  "<button class=\'btn ghost tiny\' data-user-del=\'"+u.id+"\'>حذف</button></td></tr>";}',
    'function loadUsers(){var tb=$("#userTable");if(!tb)return;',
    ' fetch(S.usersApi).then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok){tb.innerHTML="<tr><td colspan=6>"+(j.error==="kv-required"?"بدون KV نمی‌شود کاربر ساخت — یک Namespace بساز و با نام CAT_KV بایند کن.":"خطا: "+j.error)+"</td></tr>";return;}',
    '  tb.innerHTML=(j.users||[]).length?j.users.map(userRow).join(""):"<tr><td colspan=6>هنوز کاربری نساخته‌ای</td></tr>";',
    ' }).catch(function(){tb.innerHTML="<tr><td colspan=6>دریافت لیست ناموفق بود</td></tr>"});}',
    'document.addEventListener("click",function(ev){',
    ' var sub=ev.target.closest("[data-user-sub]");',
    ' if(sub){copyText(location.origin+"/u/"+sub.getAttribute("data-user-sub"));return;}',
    ' var qr=ev.target.closest("[data-user-qr]");',
    ' if(qr){openQr(location.origin+"/u/"+qr.getAttribute("data-user-qr"));return;}',
    ' var reset=ev.target.closest("[data-user-reset]");',
    ' if(reset){fetch(S.usersApi+"/"+reset.getAttribute("data-user-reset"),{method:"PUT",headers:{"content-type":"application/json"},body:JSON.stringify({usedBytes:0,usedRequests:0})}).then(loadUsers);return;}',
    ' var del=ev.target.closest("[data-user-del]");',
    ' if(del){if(!confirm("کاربر حذف شود؟"))return;fetch(S.usersApi+"/"+del.getAttribute("data-user-del"),{method:"DELETE"}).then(loadUsers);return;}});',
    'if($("#uCreate"))$("#uCreate").addEventListener("click",function(){',
    ' fetch(S.usersApi,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({name:($("#uName").value||"user").trim(),quotaGb:Number($("#uQuota").value||0),days:Number($("#uDays").value||0),deviceLimit:Number($("#uDevices").value||0)})})',
    ' .then(function(r){return r.json()}).then(function(j){',
    '  if(!j.ok){toast(j.hint||j.error||"خطا");return;}toast("کاربر ساخته شد");$("#uName").value="";loadUsers();});});',
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
    ' var pass=$("#tPass").value;if(pass)payload.panelPassword=pass;',
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
    ' var first=(S.irIps||[])[0];if(!first)return;$("#singleAddr").value=first;showTab("configs");toast("آی‌پی در سازندهٔ کانفیگ گذاشته شد");});',
    '/* ---- scanner ---- */',
    'function sampleTargets(limit,custom){var list=(custom&&custom.length?custom:(S.scanTargets||[])).slice();',
    ' for(var i=list.length-1;i>0;i--){var j=Math.floor(Math.random()*(i+1));var t=list[i];list[i]=list[j];list[j]=t;}',
    ' return limit&&list.length>limit?list.slice(0,limit):list;}',
    'function expandCustom(text){var out=[];(text||"").split(/[\\s,;]+/).forEach(function(item){',
    ' item=item.trim();if(!item)return;',
    ' if(item.indexOf("/")<0){out.push(item);return;}',
    ' var p=item.split("/"),parts=p[0].split(".").map(Number),prefix=Number(p[1]);',
    ' if(parts.length!==4||parts.some(function(n){return isNaN(n)})||prefix<8||prefix>32)return;',
    ' var base=((parts[0]<<24)>>>0)+(parts[1]<<16)+(parts[2]<<8)+parts[3];var hostBits=32-prefix;var total=Math.pow(2,Math.min(hostBits,12));var step=Math.max(1,Math.floor(total/16));',
    ' for(var i=1;i<=16;i++){var v=(base+i*step)>>>0;out.push([(v>>>24)&255,(v>>>16)&255,(v>>>8)&255,v&255].join("."));}});return out;}',
    'var scanResults=[],scanRunning=false,scanAbort=null;',
    'function pingIp(ip,timeout){return new Promise(function(resolve){',
    ' var ctrl=typeof AbortController!=="undefined"?new AbortController():null;',
    ' var started=(performance&&performance.now)?performance.now():Date.now();',
    ' var done=false;var timer=setTimeout(function(){if(!done){done=true;if(ctrl)ctrl.abort();resolve(null)}},timeout);',
    ' fetch("https://"+ip+"/cdn-cgi/trace?ts="+Math.random().toString(36).slice(2),{mode:"no-cors",cache:"no-store",signal:ctrl?ctrl.signal:undefined,credentials:"omit"})',
    ' .then(function(){if(done)return;done=true;clearTimeout(timer);var ms=((performance&&performance.now)?performance.now():Date.now())-started;resolve(Math.round(ms))})',
    ' .catch(function(){if(done)return;done=true;clearTimeout(timer);resolve(null)});});}',
    'function renderScan(){var rows=scanResults.map(function(r,i){var cls=r.ms===null?"bad":(r.ms<300?"good":(r.ms<700?"mid":"bad"));',
    ' var msText=r.ms===null?("خطا"):(r.ms+" ms");',
    ' return "<tr><td><input type=checkbox style=\\"width:auto\\" data-ip-check=\\""+r.ip+"\\""+(r.selected?" checked":"")+"></td><td dir=ltr>"+r.ip+"</td>"+',
    ' "<td class=\\"ms "+cls+"\\">"+msText+"</td><td>"+(r.colo?("<span class=pill>"+r.colo+"</span>"):"—")+"</td>"+',
    ' "<td><button class=\\"btn ghost tiny\\" data-copy-ip=\\""+r.ip+"\\">کپی</button> <button class=\\"btn tiny\\" data-use-ip=\\""+r.ip+"\\">انتخاب</button></td></tr>"}).join("");',
    ' $("#scanTable").innerHTML=rows||"<tr><td colspan=5>هنوز نتیجه‌ای نیست</td></tr>";',
    '}',
    'document.addEventListener("click",function(ev){',
    ' var c=ev.target.closest("[data-copy-ip]");if(c){copyText(c.getAttribute("data-copy-ip"));return;}',
    ' var u=ev.target.closest("[data-use-ip]");if(u){var ip=u.getAttribute("data-use-ip");scanResults.forEach(function(r){if(r.ip===ip)r.selected=!r.selected});renderScan();return;}',
    '});',
    'document.addEventListener("change",function(ev){var cb=ev.target.closest("[data-ip-check]");if(cb){var ip=cb.getAttribute("data-ip-check");scanResults.forEach(function(r){if(r.ip===ip)r.selected=cb.checked});}});',
    '$("#scanClear").addEventListener("click",function(){scanResults=[];renderScan();$("#scanStatus").textContent=I18N[lang].scanReady;$("#scanBar").style.width="0"});',
    '$("#scanStop").addEventListener("click",function(){scanRunning=false;if(scanAbort)scanAbort.abort();$("#scanStatus").textContent="متوقف شد."});',
    '$("#scanStart").addEventListener("click",function(){',
    ' if(scanRunning)return;',
    ' var sni=$("#scanSni").value.trim()||S.sni;var conc=Math.max(1,Math.min(64,Number($("#scanConc").value)||12));',
    ' var timeout=Math.max(500,Math.min(8000,Number($("#scanTimeout").value)||2500));var limit=Math.max(4,Math.min(400,Number($("#scanLimit").value)||80));',
    ' var custom=expandCustom($("#scanCustom").value);var targets=sampleTargets(limit,custom);',
    ' if(!targets.length){toast("آی‌پی‌ای برای اسکن نیست");return;}',
    ' scanRunning=true;scanAbort=typeof AbortController!=="undefined"?new AbortController():null;scanResults=[];renderScan();',
    ' $("#scanStart").disabled=true;$("#scanStop").disabled=false;$("#scanStatus").textContent=I18N[lang].scanning+" 0/"+targets.length;',
    ' var index=0,done=0;',
    ' function next(){',
    '  if(!scanRunning||index>=targets.length){',
    '   if(done>=targets.length&&scanRunning){scanRunning=false;$("#scanStart").disabled=false;$("#scanStop").disabled=true;$("#scanStatus").textContent=I18N[lang].done+" · "+scanResults.filter(function(r){return r.ms!==null}).length+" آی‌پی سالم";scanResults.sort(function(a,b){if(a.ms===null)return 1;if(b.ms===null)return -1;return a.ms-b.ms});renderScan();}',
    '   return;}',
    '  var ip=targets[index++];',
    '  pingIp(ip,timeout).then(function(ms){done++;scanResults.push({ip:ip,ms:ms,colo:"",selected:ms!==null&&ms<400});',
    '   var pct=Math.round(done/targets.length*100);$("#scanBar").style.width=pct+"%";',
    '   var alive=scanResults.filter(function(r){return r.ms!==null});var best=alive.length?Math.min.apply(null,alive.map(function(r){return r.ms})):null;',
    '   $("#scanStatus").textContent=(lang==="fa"?"در حال اسکن… ":"Scanning… ")+done+"/"+targets.length+" ("+pct+"%)"+(best!==null?(" · "+(lang==="fa"?"بهترین: ":"best: ")+best+"ms"):"");',
    '   if(done%4===0||done===targets.length)renderScan();next();});',
    ' }',
    ' for(var k=0;k<conc;k++)next();',
    '});',
    'function selectedIps(){return scanResults.filter(function(r){return r.selected&&r.ms!==null}).map(function(r){return r.ip})}',
    '$("#copyBestIps").addEventListener("click",function(){var top=scanResults.filter(function(r){return r.ms!==null}).sort(function(a,b){return a.ms-b.ms}).slice(0,10).map(function(r){return r.ip});copyText(top.join("\\n"))});',
    '$("#buildFromIps").addEventListener("click",function(){var ips=selectedIps();if(!ips.length){toast("اول چند آی‌پی را انتخاب کن");return;}',
    ' var lines=[];ips.forEach(function(ip){lines.push(vlessLink(ip,"Cat VLESS "+ip));lines.push(trojanLink(ip,"Cat Trojan "+ip))});',
    ' var text=lines.join("\\n");$("#dnsCustomText").textContent=text;',
    ' var deep="catclient://add-sub?url="+encodeURIComponent(lines[0])+"&name="+encodeURIComponent("Cat Panel clean IP");',
    ' var scanDeep="catclient://scan?sni="+encodeURIComponent(S.host)+"&ip="+encodeURIComponent(ips.join(","));',
    ' var win=window.open("","_blank");if(win){win.document.write("<pre style=\\"font:13px monospace;white-space:pre-wrap;padding:16px\\">"+text.replace(/</g,"&lt;")+"</pre>")}',
    ' copyText(text);toast("کانفیگ‌ها ساخته و کپی شد ("+ips.length+" آی‌پی)");',
    ' if(confirm("این آی‌پی‌ها را داخل اسکنر اپ Cat Client هم اعمال کنم؟ (فرانتینگ) — اگر اپ را نداری، «لغو» را بزن و کانفیگ کپی‌شده را استفاده کن.")){location.href=scanDeep;}',
    ' else if(confirm("اولین کانفیگ را در Cat Client باز کنم؟")){location.href=deep;}});',
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
    'if($("#scanCustom")&&!$("#scanCustom").value&&(S.cleanIps||[]).length)$("#scanCustom").value=S.cleanIps.join(", ");',
    'applyLang();applyTheme();renderConfigs();renderDns();',
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

async function panelPassword(env) {
  const settings = await readSettings(env);
  return String(env.PANEL_PASSWORD || settings.panelPassword || '').trim();
}

/** Authed = no password configured, or the cookie carries the right hash. */
async function requirePanelAuth(request, env) {
  const password = await panelPassword(env);
  if (!password) return { ok: true, open: true };
  const expected = await sha256Hex(password);
  if (cookieValue(request, AUTH_COOKIE) === expected) return { ok: true, open: false };
  return {
    ok: false,
    open: false,
    response: jsonResponse({ ok: false, error: 'unauthorized', login: '/login' }, 401, CORS),
  };
}

async function handleLogin(request, env) {
  const password = await panelPassword(env);
  let body = null;
  try {
    body = await request.json();
  } catch (e) {
    const form = await request.formData().catch(() => null);
    body = form ? { password: form.get('password') } : null;
  }
  const supplied = String((body && body.password) || '');
  if (!password) {
    return jsonResponse({ ok: true, note: 'no password configured' }, 200, CORS);
  }
  const offenders = Number(await kvGet(env, 'catpanel:brute:' + (request.headers.get('cf-connecting-ip') || 'unknown')) || 0);
  if (offenders >= BRUTE_LIMIT) {
    return jsonResponse({ ok: false, error: 'too-many-attempts' }, 429, CORS);
  }
  if (supplied && supplied === password) {
    const token = await sha256Hex(password);
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: Object.assign({}, CORS, {
        'content-type': 'application/json; charset=utf-8',
        'set-cookie': AUTH_COOKIE + '=' + token + '; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax',
      }),
    });
  }
  void BRUTE_WINDOW_MS;
  await kvPut(env, 'catpanel:brute:' + (request.headers.get('cf-connecting-ip') || 'unknown'), String(offenders + 1));
  return jsonResponse({ ok: false, error: 'invalid-password', attemptsLeft: Math.max(0, BRUTE_LIMIT - offenders - 1) }, 401, CORS);
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
  const users = await readUsers(env);
  const id = path.startsWith('/api/users/') ? decodeURIComponent(path.slice('/api/users/'.length)) : null;

  if (request.method === 'GET') {
    if (id) {
      const user = users.find((item) => item.id === id || item.token === id);
      return user ? jsonResponse({ ok: true, user: user }, 200, CORS) : jsonResponse({ ok: false, error: 'not-found' }, 404, CORS);
    }
    return jsonResponse({ ok: true, count: users.length, users: users }, 200, CORS);
  }

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
      expireAt: days > 0 ? Date.now() + days * 86400000 : 0,
      createdAt: Date.now(),
    });
    users.push(user);
    await writeUsers(env, users);
    return jsonResponse({ ok: true, user: user, subPath: '/u/' + user.token }, 201, CORS);
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
    users[index] = normalizeUser(Object.assign({}, current, body || {}, {
      id: current.id,
      token: current.token,
      expireAt: days === null ? current.expireAt : (days > 0 ? Date.now() + days * 86400000 : 0),
    }));
    await writeUsers(env, users);
    return jsonResponse({ ok: true, user: users[index] }, 200, CORS);
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

async function handleUserSubscription(request, url, env, host, path) {
  const rest = path.slice('/u/'.length).split('/');
  const token = decodeURIComponent(rest[0] || '');
  const format = (rest[1] || '').toLowerCase();
  const users = await readUsers(env);
  const user = findUserByToken(users, token);
  if (!user) return new Response('Not Found', { status: 404, headers: CORS });
  if (userReasonBlocked(user)) {
    const reason = userReasonBlocked(user);
    return new Response('Cat Panel: ' + reason, { status: 403, headers: CORS });
  }
  const uuid = user.uuid;
  const usageHeader = [
    'upload=0',
    'download=' + (Number(user.usedBytes) || 0),
    'total=' + (userQuotaBytes(user) || 0),
    'expire=' + (Number(user.expireAt) ? Math.floor(Number(user.expireAt) / 1000) : 0),
  ].join('; ');
  const headers = Object.assign({}, CORS, { 'subscription-userinfo': usageHeader });
  if (format === 'clash' || format === 'mihomo' || format === 'yaml') {
    return new Response(buildClashYaml(host, env, uuid), {
      headers: Object.assign({}, headers, { 'content-type': 'text/yaml; charset=utf-8' }),
    });
  }
  if (format === 'singbox' || format === 'sing-box' || format === 'json') {
    return new Response(JSON.stringify(buildSingboxConfig(host, env, uuid), null, 2), {
      headers: Object.assign({}, headers, { 'content-type': 'application/json; charset=utf-8' }),
    });
  }
  if (format === 'all') {
    return jsonResponse(Object.assign({ ok: true, user: { name: user.name, token: user.token } }, buildAllConfigs(host, env, uuid)), 200, headers);
  }
  const links = buildSubLinks(host, env, uuid).join('\n') + '\n';
  if (format === 'b64' || format === 'base64') {
    return new Response(b64encode(links), {
      headers: Object.assign({}, headers, { 'content-type': 'text/plain; charset=utf-8' }),
    });
  }
  return new Response(links, {
    headers: Object.assign({}, headers, { 'content-type': 'text/plain; charset=utf-8' }),
  });
}

async function handlePanelRequest(request, url, env, host, uuid, state) {
  const panelPass = await panelPassword(env);
  if (panelPass) {
    const expected = await sha256Hex(panelPass);
    const authed = cookieValue(request, AUTH_COOKIE) === expected || url.searchParams.get('p') === panelPass;
    if (!authed) return htmlResponse(loginHtml(state.title, ''));
  }
  return htmlResponse(panelShell(state));
}

async function fetchHandler(request, env) {
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

  /* data plane */
  if (path === vlessName || path === trojanName) {
    const pair = new WebSocketPair();
    const client = Object.values(pair)[0];
    const server = Object.values(pair)[1];
    server.accept();
    handleDataWebSocket(server, env);
    return new Response(null, { status: 101, statusText: 'Switching Protocols', webSocket: client });
  }

  /* subscriptions */
  if (path === '/sub' || path === '/sub/') {
    const body = buildSubLinks(host, env, uuid).join('\n');
    const wantsBase64 = url.searchParams.get('b64') === '1';
    return new Response(wantsBase64 ? b64encode(body) : body, {
      headers: Object.assign(
        {
          'content-type': 'text/plain; charset=utf-8',
          'subscription-userinfo': subUserInfoHeader(env),
          'profile-update-interval': '6',
        },
        CORS,
      ),
    });
  }
  if (path === '/sub64' || path === '/sub64/') {
    const body = buildSubLinks(host, env, uuid).join('\n');
    return new Response(b64encode(body), {
      headers: Object.assign(
        { 'content-type': 'text/plain; charset=utf-8', 'subscription-userinfo': subUserInfoHeader(env) },
        CORS,
      ),
    });
  }
  if (path === '/clash' || path === '/mihomo' || path === '/clash.yaml') {
    return new Response(buildClashYaml(host, env, uuid), {
      headers: Object.assign({ 'content-type': 'text/yaml; charset=utf-8' }, CORS),
    });
  }
  if (path === '/singbox' || path === '/sing-box' || path === '/singbox.json') {
    return new Response(buildSingboxConfig(host, env, uuid), {
      headers: Object.assign({ 'content-type': 'application/json; charset=utf-8' }, CORS),
    });
  }
  if (path === '/all' || path === '/api/all' || path === '/all.json') {
    return jsonResponse(buildAllConfigs(host, env, uuid), 200, CORS);
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
    return jsonResponse(panelState(host, env, uuid, request), 200, CORS);
  }
  if (path === '/api/scan-targets.json') {
    return jsonResponse({ sni: effectiveSni(host, env), port: paths.port, targets: scanTargets(env) }, 200, CORS);
  }
  if (path === '/api/ping') {
    const ip = url.searchParams.get('ip') || '';
    if (!isIpLiteral(ip)) return jsonResponse({ ok: false, error: 'ip required' }, 400, CORS);
    return jsonResponse(await probeIp(ip, Number(url.searchParams.get('timeout') || 4000)), 200, CORS);
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
    const list = splitCsv(url.searchParams.get('ips')).filter(isIpLiteral).slice(0, 64);
    if (!list.length) return jsonResponse({ ok: false, error: 'ips required' }, 400, CORS);
    const timeout = Math.max(1000, Math.min(8000, Number(url.searchParams.get('timeout') || 4000)));
    const concurrency = Math.max(1, Math.min(32, Number(url.searchParams.get('concurrency') || 16)));
    const results = [];
    let cursor = 0;
    const workers = Array.from({ length: Math.min(concurrency, list.length) }, async () => {
      for (;;) {
        const index = cursor++;
        if (index >= list.length) return;
        results.push(await probeIp(list[index], timeout));
      }
    });
    await Promise.all(workers);
    const sorted = results.sort((a, b) => (a.ok === b.ok ? (a.ms || 99999) - (b.ms || 99999) : a.ok ? -1 : 1));
    return jsonResponse({ ok: true, count: sorted.length, alive: sorted.filter((r) => r.ok).length, results: sorted }, 200, CORS);
  }

  if (path === '/api/ir-ips') {
    return jsonResponse({ ok: true, count: IR_CLEAN_IPS.length, ips: IR_CLEAN_IPS }, 200, CORS);
  }

  if (path === '/api/proxy-ips') {
    return jsonResponse({ ok: true, ips: splitCsv(env.PROXY_IPS), note: 'PROXY_IPS env or settings.tunnel.proxyIps' }, 200, CORS);
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
  if (path === '/u' || path.startsWith('/u/')) {
    return handleUserSubscription(request, url, env, host, path);
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
      scanTargets: scanTargets(env).length,
    }, 200, CORS);
  }

  if (path === '/' || path === '/index.html' || path === '/panel') {
    const state = panelState(host, env, uuid, request);
    return handlePanelRequest(request, url, env, host, uuid, state);
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
  parseVlessHeader,
  parseSocksAddress,
  parseTrojanRequest,
  trojanPassword,
  trojanHash,
  sha224Hex,
  isCloudflareIp,
  relayTcp,
  handleTunnelConnection,
  readSettings,
  writeSettings,
  readUsers,
  writeUsers,
  normalizeUser,
  userTrafficLeft,
  userReasonBlocked,
  tunnelAuth,
  kvBinding,
  parseHttpRequest,
  buildSubLinks,
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
  sampleSubnet,
  probeIp,
  probeDnsUpstream,
  resolveHost,
  safeUpstreamOverride,
  panelState,
  panelShell,
  loginHtml,
  handleUsersApi,
  handleUserSubscription,
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
  dohUpstream,
  isIpLiteral,
  fetchHandler,
};
