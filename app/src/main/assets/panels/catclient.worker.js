/**
 * 🐱 Cat Panel — single-file Cloudflare Worker panel (VLESS / Trojan / WARP / DoH)
 *
 * Version: 3.0.0 — "purple night" edition
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

const CAT_PANEL_VERSION = '3.0.0';
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

function handleDataWebSocket(ws, env) {
  let started = false;
  ws.addEventListener('message', async (event) => {
    if (started) return; // only the first frame carries the VLESS header
    started = true;
    const data = event.data;

    if (env.REMOTE) {
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
  { id: 'cloudflare', name: 'Cloudflare', url: 'https://cloudflare-dns.com/dns-query' },
  { id: 'google', name: 'Google', url: 'https://dns.google/dns-query' },
  { id: 'quad9', name: 'Quad9', url: 'https://dns.quad9.net/dns-query' },
  { id: 'adguard', name: 'AdGuard', url: 'https://dns.adguard-dns.com/dns-query' },
];

function dohUpstream(env) {
  return String(env.DNS_UPSTREAM || DNS_PRESETS[0].url).trim();
}

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
    return { ok: true, ms: ms };
  } catch (e) {
    return { ok: false, ms: Date.now() - started, error: e && e.message ? e.message : String(e) };
  }
}

async function handleDnsQuery(request, env) {
  const url = new URL(request.url);
  const upstream = dohUpstream(env);
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
    '--ok:#34d399;--warn:#fbbf24;--bad:#f87171;--radius:18px;',
    '}',
    'html[data-theme="light"]{',
    '--bg:#f6f3fc;--bg-soft:#ffffff;--surface:#ffffff;--surface-2:#f3eefc;',
    '--line:rgba(124,58,237,.22);--line-soft:rgba(20,10,40,.08);',
    '--text:#12061f;--muted:#5b5566;--dim:#8a8494;',
    '--accent:#7c3aed;--accent-2:#6d28d9;--accent-3:#c026d3;--on-accent:#fff;',
    '}',
    'body{font-family:"Vazirmatn",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);',
    'min-height:100vh;line-height:1.7;padding-bottom:96px;',
    'background-image:radial-gradient(900px 500px at 12% -8%,rgba(168,85,247,.22),transparent 60%),radial-gradient(700px 420px at 96% 4%,rgba(217,70,239,.16),transparent 62%)}',
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

function loginHtml(title) {
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
    '<div class="brand"><span class="cat">' + catLogo(26) + '</span><span>' + esc(state.title) +
    '<small id="brandSub">پنل کلودفلر شخصی شما</small></span></div>' +
    '<span class="spacer"></span>' +
    '<span class="pill ok" id="onlinePill">آنلاین</span>' +
    '<button class="icon-btn" id="themeBtn" title="تم">🌙</button>' +
    '<button class="icon-btn" id="langBtn" title="Language">EN</button>' +
    '</div></header>' +

    '<div class="wrap">' + homeTabHtml(state) + configsTabHtml(state) + scannerTabHtml() + dnsTabHtml(state) + helpTabHtml(state) + '</div>' +

    '<nav class="tabs"><div class="inner">' +
    navButton('home', 'خانه', '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>') +
    navButton('configs', 'کانفیگ‌ها', '<path d="M4 6h16M4 12h16M4 18h10"/>') +
    navButton('scanner', 'اسکنر', '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>') +
    navButton('dns', 'DNS', '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z"/><path d="M3.5 9h17M3.5 15h17M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"/>') +
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
    '<div class="card"><h2><span class="dot"></span><span data-i18n="dnsUseTitle">چطور استفاده کنم؟</span></h2>' +
    '<div class="steps" id="dnsSteps">' +
    '<div class="step">Cat Client → تنظیمات → DNS رمزنگاری‌شده → حالت سفارشی (DoH) و همین آدرس را وارد کن.</div>' +
    '<div class="step">در مرورگر (Chrome یا Firefox): Settings → Privacy → Secure DNS → Custom → همین آدرس.</div>' +
    '<div class="step">در اندروید اگر برنامهٔ جدا می‌خواهی: Intra یا RethinkDNS را با همین آدرس DoH تنظیم کن (Private DNS اندروید فقط DoT است).</div>' +
    '<div class="step">در Mihomo/Clash: بخش dns → nameserver → همین آدرس (کانفیگ /clash از قبل تنظیم شده است).</div>' +
    '</div></div></section>';
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
    ' fa:{subTitle:"لینک سابسکریپشن",stepsTitle:"سه قدم تا اتصال",infoTitle:"اطلاعات اتصال",configsTitle:"همهٔ کانفیگ‌های آماده",scannerTitle:"اسکنر آی‌پی تمیز",scannerHowto:"راهنمای نتیجه",dnsTitle:"DNS رمزنگاری‌شده (DoH)",dnsUpstreamTitle:"سرورهای بالادستی",dnsUseTitle:"چطور استفاده کنم؟",helpTitle:"راهنمای پنل",envTitle:"متغیرهای پنل",faqTitle:"پرسش‌های پرتکرار",online:"آنلاین",copied:"کپی شد",scanReady:"آماده.",scanning:"در حال اسکن…",done:"تمام شد"},',
    ' en:{subTitle:"Subscription link",stepsTitle:"Three steps to connect",infoTitle:"Connection details",configsTitle:"Ready-made configs",scannerTitle:"Clean-IP scanner",scannerHowto:"How to use the results",dnsTitle:"Encrypted DNS (DoH)",dnsUpstreamTitle:"Upstream resolvers",dnsUseTitle:"How to use it",helpTitle:"Panel guide",envTitle:"Panel variables",faqTitle:"FAQ",online:"online",copied:"Copied",scanReady:"Ready.",scanning:"Scanning…",done:"Finished"}',
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
    ' $("[data-nav-label=dns]").textContent="DNS";',
    ' $("[data-nav-label=help]").textContent=lang==="fa"?"راهنما":"Help";',
    ' $("#brandSub").textContent=lang==="fa"?"پنل کلودفلر شخصی شما":"Your personal Cloudflare panel";',
    ' $("#heroTitle").textContent=lang==="fa"?"پنل فعال است":"Panel is live";',
    ' $("#heroSub").textContent=lang==="fa"?"این Worker روی شبکهٔ کلودفلر اجرا می‌شود؛ با یک لینک، همهٔ دستگاه‌هایت را وصل کن.":"This worker runs on Cloudflare edge; connect every device with one link.";',
    ' $("#onlinePill").textContent=d.online;',
    '}',
    'function applyTheme(){document.documentElement.setAttribute("data-theme",theme);$("#themeBtn").textContent=theme==="dark"?"🌙":"☀️";',
    ' var m=document.querySelector("meta[name=theme-color]");if(m)m.setAttribute("content",theme==="dark"?"#06030c":"#f6f3fc");}',
    'function toast(msg){var t=$("#toast");$("#toastText").textContent=msg;t.classList.add("show");setTimeout(function(){t.classList.remove("show")},1500);}',
    'function copyText(text){',
    ' if(navigator.clipboard&&navigator.clipboard.writeText){return navigator.clipboard.writeText(text).then(function(){toast(I18N[lang].copied)})}',
    ' var ta=document.createElement("textarea");ta.value=text;document.body.appendChild(ta);ta.select();try{document.execCommand("copy");toast(I18N[lang].copied)}catch(e){}document.body.removeChild(ta);return Promise.resolve();',
    '}',
    'function showTab(name){',
    ' $$("nav.tabs button").forEach(function(b){b.classList.toggle("active",b.getAttribute("data-tab")===name)});',
    ' $$(".tab").forEach(function(s){s.classList.toggle("active",s.getAttribute("data-tab-panel")===name)});',
    ' try{localStorage.setItem("catpanel.tab",name)}catch(e){}',
    ' try{window.scrollTo({top:0,behavior:"smooth"})}catch(e){try{window.scrollTo(0,0)}catch(e2){}}',
    '}',
    '$$("nav.tabs button").forEach(function(btn){btn.addEventListener("click",function(){showTab(btn.getAttribute("data-tab"))})});',
    '$("#langBtn").addEventListener("click",function(){lang=lang==="fa"?"en":"fa";try{localStorage.setItem("catpanel.lang",lang)}catch(e){}applyLang();renderConfigs();renderDns();});',
    '$("#themeBtn").addEventListener("click",function(){theme=theme==="dark"?"light":"dark";try{localStorage.setItem("catpanel.theme",theme)}catch(e){}applyTheme();});',
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
    '   $("#scanBar").style.width=Math.round(done/targets.length*100)+"%";$("#scanStatus").textContent=I18N[lang].scanning+" "+done+"/"+targets.length;',
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

async function handlePanelRequest(request, url, env, host, uuid, state) {
  const panelPass = String(env.PANEL_PASSWORD || '');
  if (panelPass && url.searchParams.get('p') !== panelPass) {
    return htmlResponse(loginHtml(state.title));
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
  if (path === '/api/dns-probe') {
    const upstream = url.searchParams.get('u') || dohUpstream(env);
    const name = url.searchParams.get('name') || DNS_QUERY_NAME;
    return jsonResponse(await probeDnsUpstream(upstream, name), 200, CORS);
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
  panelState,
  panelShell,
  loginHtml,
  dohUpstream,
  isIpLiteral,
  fetchHandler,
};
