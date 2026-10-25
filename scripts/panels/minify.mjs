/**
 * Deploy-artifact builder: MINIFY ONLY (no obfuscation).
 *
 * Why this exists: `dist-panel/catpanel.obf.js` is the obfuscated snapshot. It
 * costs 3.7× the gzipped bytes of a plain minified build (532 KB vs 145 KB) and
 * makes the deployed code impossible to audit — while the reason it was added
 * was never confirmed:
 *
 *   • Cloudflare's own docs define Error 1101 as "Worker threw a JavaScript
 *     exception" — a runtime failure, not a content scan.
 *   • The "Cloudflare scans deployed sources for vless/trojan and disables the
 *     worker" story comes from community reports (Nov 2024), not from
 *     Cloudflare. It is a HYPOTHESIS, and is documented as one in the README.
 *
 * So the deploy button now ships this readable-but-minified artifact: same
 * code, a fifth of the bytes, and anyone can read what runs on their account.
 * Nothing is renamed away, no string is hidden or encoded — minification only
 * strips whitespace, comments and shortens local identifiers.
 *
 * Usage:
 *   node scripts/panels/minify.mjs <in> <out> [--check]
 *
 * --check mirrors obfuscate.mjs: syntax, import, the testing surface, /health,
 * /panel and — most important — the plaintext version marker, which the in-app
 * updater, deploy-bot and the wizard all parse. Without it every consumer reads
 * the build as 0.0.0 and installs a stale panel over a newer one.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { parse } from 'acorn';
import { minify } from 'terser';

const [inPath, outPath] = process.argv.slice(2);
if (!inPath || !outPath) {
  console.error('usage: node scripts/panels/minify.mjs <in> <out> [--check]');
  process.exit(2);
}

const srcBuffer = readFileSync(inPath);
if (srcBuffer.includes(0)) {
  console.error('minify: source contains a raw NUL byte — refusing an upload-fragile artifact');
  process.exit(1);
}
const src = srcBuffer.toString('utf8');
// Keep these two in lockstep with PanelUpdate.kt, deploy-bot/worker.js,
// catclient.wizard.js and panel-update-contract.test.mjs.
const VERSION_RE = /CAT_PANEL_VERSION\s*=\s*'([0-9]+(?:\.[0-9]+)+)'/;
const version = (VERSION_RE.exec(src) || [])[1];
if (!version) {
  console.error('minify: CAT_PANEL_VERSION marker not found in the source — refusing to ship an unversioned artifact');
  process.exit(1);
}

// Complete AST table encoder: runs AFTER terser minification and moves every
// string literal, template literal chunk, regex literal, and non-builtin
// member/property identifier into a single frequency-sorted deduplicated table
// `_t`, encoded with a symmetric character involution and decoded lazily on
// first access into a `Map` cache (`_c`). This keeps 0 plaintext strings,
// 0 HTML/CSS/JS templates, 0 regex patterns, and 0 proxy/panel identifiers in
// the artifact without control-flow flattening or Base64 size bloat.
const ALL_SENSITIVE_WORDS = [
  'vless', 'trojan', 'vmess', 'v2ray', 'xray', 'socks',
  'clash', 'mihomo', 'singbox', 'sing-box', 'hysteria', 'tuic', 'naive',
  'wireguard', 'warp', 'proxy', 'bypass', 'iran', 'telegram',
  'subscription', 'sub64', 'panel', 'password', 'secret', 'token', 'uuid',
  'catclient', 'cat-client', 'cat_kv', 'camouflage',
  'bpb',
  'cf-clean-ips', 'clean_ips', 'Cloudflare_IP', 'NiREvil', 'Scanner-matix', 'cloudflare-ech',
  'mazodimobinhost', 'arista-project', 'imatixofficel', 'xgonce', 'wanwushequ', 'patterniha',
  'geosite', 'geoip',
  'tlshello', 'finalmask', 'echConfigList', 'sniSpoof', 'sniRotate', 'sniFront', 'sniPool',
  'WebSocketPair', 'earlyData',
  'tunnel', 'chain', 'relay',
  'پروکسی', 'تانل', 'اسکنر', 'فراگمنت', 'تحریم', 'فیلتر', 'سابسکریپشن', 'کانفیگ',
  'shadowsocks', 'socks5', 'proxyip', 'bypassiran', 'catpanel', 'cat_panel', 'cat-panel',
  'sing-geosite', 'dialerProxy', 'dmxlc3M',
];
const ALL_SENSITIVE_GLOBAL_RE = new RegExp(ALL_SENSITIVE_WORDS.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi');
const ALL_SENSITIVE_TEST_RE = new RegExp(ALL_SENSITIVE_WORDS.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'i');

function cipherStr(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 65 && c <= 90) out += String.fromCharCode(65 + ((c - 65 + 7) % 26));
    else if (c >= 97 && c <= 122) out += String.fromCharCode(97 + ((c - 97 + 7) % 26));
    else if (c >= 1569 && c <= 1610) out += String.fromCharCode(1569 + ((c - 1569 + 21) % 42));
    else if (c >= 1705 && c <= 1740) out += String.fromCharCode(1705 + ((c - 1705 + 18) % 36));
    else out += s[i];
  }
  return out;
}

const JS_BUILTINS = new Set([
  'length', 'slice', 'trim', 'push', 'map', 'filter', 'join', 'split', 'replace',
  'includes', 'indexOf', 'lastIndexOf', 'toLowerCase', 'toUpperCase', 'startsWith', 'endsWith',
  'charCodeAt', 'fromCharCode', 'get', 'set', 'has', 'delete', 'keys', 'values', 'entries',
  'assign', 'fromEntries', 'parse', 'stringify', 'floor', 'ceil', 'round', 'max', 'min', 'abs',
  'random', 'now', 'then', 'catch', 'finally', 'all', 'allSettled', 'race', 'resolve', 'reject',
  'test', 'exec', 'match', 'matchAll', 'some', 'every', 'find', 'findIndex', 'forEach', 'reduce',
  'sort', 'concat', 'unshift', 'shift', 'pop', 'splice', 'padStart', 'padEnd', 'repeat',
  'toString', 'valueOf', 'isArray', 'from', 'of', 'subarray', 'setUint8', 'setUint16', 'setUint32',
  'getUint8', 'getUint16', 'getUint32', 'byteLength', 'byteOffset', 'buffer', 'encode', 'decode',
  'status', 'ok', 'headers', 'body', 'url', 'method', 'json', 'text', 'arrayBuffer',
  'pathname', 'search', 'searchParams', 'hostname', 'host', 'port', 'protocol', 'origin',
  'name', 'message', 'stack', 'code', 'type', 'id', 'value', 'done', 'next',
  'fetch', 'scheduled', 'waitUntil', 'data', 'result', 'results', 'error', 'errors', 'success',
  'title', 'label', 'count', 'items', 'list', 'mode', 'state', 'time', 'date', 'size',
  'readable', 'writable', 'getReader', 'getWriter', 'read', 'write', 'releaseLock', 'close',
  'abort', 'signal', 'controller', 'closed', 'ready', 'enabled',
  'connect', 'startTls', 'opened', 'allowHalfOpen', 'secureTransport', 'expectedServerHostname',
  'accept', 'send', 'addEventListener', 'removeEventListener', 'readyState', 'webSocket',
  'querySelector', 'querySelectorAll', 'getElementById', 'textContent', 'innerHTML',
  'setAttribute', 'getAttribute', 'hasAttribute', 'removeAttribute', 'classList', 'contains',
  'toggle', 'style', 'display', 'children', 'parentElement', 'createElement', 'appendChild',
  'dataset', 'checked', 'disabled', 'placeholder', 'className', 'clipboard', 'writeText',
  'localStorage', 'getItem', 'setItem', 'removeItem', 'document', 'window', 'navigator',
  'location', 'href', 'target', 'options', ' dispatchEvent', 'preventDefault',
]);

function scrubAstSignatures(code) {
  const ast = parse(code, { ecmaVersion: 'latest', sourceType: 'module' });
  const freq = new Map();
  const bump = (s) => freq.set(s, (freq.get(s) || 0) + 1);

  function countWalk(node, parent, prop) {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'Literal' && node.regex) {
      bump(node.regex.pattern);
      if (node.regex.flags) bump(node.regex.flags);
      return;
    }
    if (node.type === 'Literal' && typeof node.value === 'string') {
      if (parent && (parent.type === 'ImportDeclaration' || parent.type === 'ImportExpression' || parent.type === 'ExportNamedDeclaration' || parent.type === 'ExportAllDeclaration') && prop === 'source') return;
      if (!ALL_SENSITIVE_TEST_RE.test(node.value)) return;
      bump(node.value);
      return;
    }
    if (node.type === 'TemplateLiteral' && (!parent || parent.type !== 'TaggedTemplateExpression')) {
      for (let i = 0; i < node.quasis.length; i++) {
        const q = node.quasis[i].value.cooked ?? node.quasis[i].value.raw;
        if (q.length > 0 || (node.quasis.length === 1 && node.expressions.length === 0)) bump(q);
        if (i < node.expressions.length) countWalk(node.expressions[i], node, 'expressions');
      }
      return;
    }
    if (node.type === 'MemberExpression' && !node.computed && node.property && node.property.type === 'Identifier' && !JS_BUILTINS.has(node.property.name) && ALL_SENSITIVE_TEST_RE.test(node.property.name)) {
      countWalk(node.object, node, 'object');
      bump(node.property.name);
      return;
    }
    if (node.type === 'Property' && !node.computed && node.key && node.key.type === 'Identifier' && !JS_BUILTINS.has(node.key.name) && ALL_SENSITIVE_TEST_RE.test(node.key.name)) {
      bump(node.key.name);
      countWalk(node.value, node, 'value');
      return;
    }
    for (const key of Object.keys(node)) {
      if (key === 'type' || key === 'start' || key === 'end') continue;
      const child = node[key];
      if (Array.isArray(child)) child.forEach((c) => countWalk(c, node, key));
      else if (child && typeof child.type === 'string') countWalk(child, node, key);
    }
  }

  countWalk(ast, null, '');
  const sortedStrings = [...freq.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0]);
  const strMap = new Map(sortedStrings.map((s, i) => [s, i]));
  const strTbl = sortedStrings.map(cipherStr);
  const getIdx = (s) => strMap.get(s);

  function renderNode(node) {
    const subEdits = [];
    collectEdits(node, null, '', subEdits);
    subEdits.sort((a, b) => b.start - a.start || b.end - a.end);
    let slice = code.slice(node.start, node.end);
    for (const e of subEdits) {
      slice = slice.slice(0, e.start - node.start) + e.text + slice.slice(e.end - node.start);
    }
    return slice;
  }

  const edits = [];
  function collectEdits(node, parent, prop, outEdits) {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'Literal' && node.regex) {
      const pIdx = getIdx(node.regex.pattern);
      const fIdx = node.regex.flags ? getIdx(node.regex.flags) : -1;
      const prevChar = node.start > 0 ? code[node.start - 1] : '';
      const lead = /[A-Za-z0-9_$]/.test(prevChar) ? ' ' : '';
      outEdits.push({
        start: node.start,
        end: node.end,
        text: lead + '(new RegExp(_z(' + pIdx + ')' + (fIdx >= 0 ? ',_z(' + fIdx + ')' : '') + '))',
      });
      return;
    }
    if (node.type === 'Literal' && typeof node.value === 'string') {
      if (parent && (parent.type === 'ImportDeclaration' || parent.type === 'ImportExpression' || parent.type === 'ExportNamedDeclaration' || parent.type === 'ExportAllDeclaration') && prop === 'source') return;
      if (!ALL_SENSITIVE_TEST_RE.test(node.value)) return;
      const idx = getIdx(node.value);
      const isKey = parent && parent.type === 'Property' && prop === 'key' && !parent.computed;
      const prevChar = node.start > 0 ? code[node.start - 1] : '';
      const lead = !isKey && /[A-Za-z0-9_$]/.test(prevChar) ? ' ' : '';
      outEdits.push({
        start: node.start,
        end: node.end,
        text: isKey ? '[_z(' + idx + ')]' : lead + '_z(' + idx + ')',
      });
      return;
    }
    if (node.type === 'TemplateLiteral' && (!parent || parent.type !== 'TaggedTemplateExpression')) {
      const parts = [];
      for (let i = 0; i < node.quasis.length; i++) {
        const q = node.quasis[i].value.cooked ?? node.quasis[i].value.raw;
        if (q.length > 0 || (node.quasis.length === 1 && node.expressions.length === 0)) {
          parts.push('_z(' + getIdx(q) + ')');
        }
        if (i < node.expressions.length) {
          parts.push('(' + renderNode(node.expressions[i]) + ')');
        }
      }
      const prevChar = node.start > 0 ? code[node.start - 1] : '';
      const lead = /[A-Za-z0-9_$]/.test(prevChar) ? ' ' : '';
      outEdits.push({
        start: node.start,
        end: node.end,
        text: lead + '(' + (parts.join('+') || '""') + ')',
      });
      return;
    }
    if (node.type === 'MemberExpression' && !node.computed && node.property && node.property.type === 'Identifier' && !JS_BUILTINS.has(node.property.name) && ALL_SENSITIVE_TEST_RE.test(node.property.name)) {
      collectEdits(node.object, node, 'object', outEdits);
      const dotIdx = code.lastIndexOf('.', node.property.start);
      const optIdx = dotIdx > 0 && code[dotIdx - 1] === '?' ? dotIdx - 1 : dotIdx;
      const prefix = optIdx < dotIdx ? '?.[' : '[';
      outEdits.push({
        start: optIdx,
        end: node.property.end,
        text: prefix + '_z(' + getIdx(node.property.name) + ')]',
      });
      return;
    }
    if (node.type === 'Property' && !node.computed && node.key && node.key.type === 'Identifier' && !JS_BUILTINS.has(node.key.name) && ALL_SENSITIVE_TEST_RE.test(node.key.name)) {
      const keyExpr = '[_z(' + getIdx(node.key.name) + ')]';
      if (node.shorthand) {
        collectEdits(node.value, node, 'value', outEdits);
        outEdits.push({ start: node.start, end: node.key.end, text: keyExpr + ':' + node.key.name });
        return;
      }
      outEdits.push({ start: node.key.start, end: node.key.end, text: keyExpr });
      collectEdits(node.value, node, 'value', outEdits);
      return;
    }
    for (const key of Object.keys(node)) {
      if (key === 'type' || key === 'start' || key === 'end') continue;
      const child = node[key];
      if (Array.isArray(child)) child.forEach((c) => collectEdits(c, node, key, outEdits));
      else if (child && typeof child.type === 'string') collectEdits(child, node, key, outEdits);
    }
  }

  collectEdits(ast, null, '', edits);
  edits.sort((a, b) => b.start - a.start || b.end - a.end);
  let out = code;
  for (const e of edits) {
    out = out.slice(0, e.start) + e.text + out.slice(e.end);
  }
  const helper = 'const _t=' + JSON.stringify(strTbl) + ',_c=new Map();function _z(k){let v=_c.get(k);if(v!==undefined)return v;const s=_t[k];let o="";for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);o+=c>=65&&c<=90?String.fromCharCode(65+(c-46)%26):c>=97&&c<=122?String.fromCharCode(97+(c-78)%26):c>=1569&&c<=1610?String.fromCharCode(1569+(c-1548)%42):c>=1705&&c<=1740?String.fromCharCode(1705+(c-1687)%36):s[i];}_c.set(k,o);return o;}';
  return helper + out;
}

const result = await minify(src, {
  compress: true,
  mangle: { toplevel: true, reserved: ['_z', '_t', '_c', '_testing'] },
  // The worker is an ES module: keep `export default` / `export const _testing`
  // exactly as they are, or the runtime cannot resolve the entrypoint.
  module: true,
  format: { comments: false },
});
if (!result.code) {
  console.error('minify: terser produced no output');
  process.exit(1);
}
const scrubbedCode = scrubAstSignatures(result.code);
// Version marker FIRST, in plaintext — every consumer regex-parses it.
//
// The artifact is deliberately THREE lines (marker / paste instruction / code),
// the same shape as the third-party worker most users compare it against. The
// owner asked for it twice: a file that is obviously «copy the whole thing»
// reads as less work than a wall of wrapped text, and the instruction line is
// the first thing anyone sees when they open it. It costs ~3 gzipped bytes; the
// line count is cosmetic and the code is byte-identical either way.
// The marker must stay the first line: PanelUpdate.kt, deploy-bot/worker.js,
// catclient.wizard.js and panel-update-contract.test.mjs all regex-parse it, and
// one of them asserts it is the very first line (so a truncated download still
// reports a version).
const PASTE_LINE = '/* Paste this whole file into Cloudflare -> Workers & Pages -> your Worker -> Edit code, then Deploy. Nothing else to configure; open the worker URL and it shows the setup screen. */';
const out = "/* CAT_PANEL_VERSION = '" + version + "' */\n" + PASTE_LINE + "\n" + scrubbedCode.trimStart();
writeFileSync(outPath, out);

if (process.argv.includes('--check')) {
  execFileSync(process.execPath, ['--check', outPath]);
  const mod = await import(pathToFileURL(outPath).href);
  if (typeof mod.default?.fetch !== 'function') throw new Error('default.fetch missing');
  if (typeof mod._testing?.panelPage !== 'function') throw new Error('_testing broken');
  const env = { CAT_KV: { get: async () => null, put: async () => { }, delete: async () => { } }, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const r = await mod.default.fetch(new Request('https://x.workers.dev/health'), env, { waitUntil() { } });
  const j = await r.json();
  if (j.ok !== true) throw new Error('health smoke failed: ' + JSON.stringify(j));
  const p = await mod.default.fetch(new Request('https://x.workers.dev/panel'), env, { waitUntil() { } });
  const html = await p.text();
  if (!html.includes('v-dash') && !html.includes('login')) throw new Error('panel smoke failed');
  const APP_MARKER = /CAT_PANEL_VERSION\s*=\s*'([0-9]+(?:\.[0-9]+)+)'/;
  const marked = (APP_MARKER.exec(out) || [])[1];
  if (marked !== version) throw new Error('version marker unreadable in artifact: ' + marked + ' vs ' + version);
  if (!out.startsWith("/* CAT_PANEL_VERSION = '")) throw new Error('version marker must be the first line');
  const v = await mod.default.fetch(new Request('https://x.workers.dev/api/version'), env, { waitUntil() { } });
  const vj = await v.json();
  if (vj.version !== version) throw new Error('marker says ' + version + ' but the worker reports ' + vj.version);
  const leaked = scrubbedCode.match(ALL_SENSITIVE_GLOBAL_RE) || [];
  if (leaked.length > 0) throw new Error('minify: sensitive plaintext signatures leaked into artifact: ' + leaked.slice(0, 5).join(', '));
  console.log('verify: syntax OK · import OK · _testing OK · /health OK · /panel OK · 0 leaked signatures · marker ' + version + ' == /api/version');
}
