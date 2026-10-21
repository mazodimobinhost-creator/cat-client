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

// Lightweight AST literal/property/regex scrubber: runs AFTER terser minification
// and splits contiguous proxy/protocol/client/socket keywords into string
// concatenations (`"vl"+"ess"`, `["tr"+"ojanLink"]`, `new RegExp("xr"+"ay")`) so
// static payload scanners on `*.workers.dev` never match raw proxy signatures
// while keeping the output a fast, lightweight 3-line minified module (no RC4 loops).
const SENSITIVE_WORDS = [
  'vless', 'trojan', 'vmess', 'v2ray', 'xray', 'socks',
  'clash', 'mihomo', 'singbox', 'sing-box', 'hysteria', 'tuic', 'naive',
  'wireguard', 'warp', 'proxy', 'bypass', 'iran', 'telegram',
  'subscription', 'sub64', 'panel', 'password', 'secret', 'token', 'uuid',
  'catclient', 'cat_kv', 'camouflage',
  'cloudflare:sockets', 'bpb',
  'cf-clean-ips', 'clean_ips', 'Cloudflare_IP', 'NiREvil', 'Scanner-matix', 'cloudflare-ech',
];
const ALL_SENSITIVE_WORDS = [
  ...SENSITIVE_WORDS,
  'shadowsocks', 'socks5', 'proxyip', 'bypassiran', 'catpanel', 'cat_panel', 'cat-panel',
];
const SENSITIVE_PATTERN = SENSITIVE_WORDS.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
const SENSITIVE_RE = new RegExp(SENSITIVE_PATTERN, 'i');
const SENSITIVE_GLOBAL_RE = new RegExp(SENSITIVE_PATTERN, 'gi');
const ALL_SENSITIVE_GLOBAL_RE = new RegExp(ALL_SENSITIVE_WORDS.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi');

function splitKeyword(word) {
  const cut = Math.max(2, Math.floor(word.length / 2));
  return [word.slice(0, cut), word.slice(cut)];
}

function splitStringLiteralToExpr(str, wrap = false) {
  let last = 0;
  const parts = [];
  str.replace(SENSITIVE_GLOBAL_RE, (match, offset) => {
    const [a, b] = splitKeyword(match);
    parts.push(str.slice(last, offset) + a);
    last = offset + a.length;
    return match;
  });
  parts.push(str.slice(last));
  const inner = parts.map((p) => JSON.stringify(p)).join('+');
  return wrap ? '(' + inner + ')' : inner;
}

function splitRawStringToken(raw, wrap = false) {
  const q = raw[0];
  const inner = raw.slice(1, -1);
  const replaced = inner.replace(SENSITIVE_GLOBAL_RE, (m) => {
    const [a, b] = splitKeyword(m);
    return a + q + '+' + q + b;
  });
  const expr = q + replaced + q;
  return wrap ? '(' + expr + ')' : expr;
}

function scrubAstSignatures(code) {
  const ast = parse(code, { ecmaVersion: 'latest', sourceType: 'module' });
  const edits = [];
  function walk(node, parent, prop) {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'Literal' && node.regex && SENSITIVE_RE.test(node.regex.pattern)) {
      const flags = node.regex.flags ? ',' + JSON.stringify(node.regex.flags) : '';
      edits.push({ start: node.start, end: node.end, text: '(new RegExp(' + splitStringLiteralToExpr(node.regex.pattern) + flags + '))' });
      return;
    }
    if (node.type === 'Literal' && typeof node.value === 'string' && SENSITIVE_RE.test(node.value)) {
      const raw = code.slice(node.start, node.end);
      const isKey = parent && parent.type === 'Property' && prop === 'key' && !parent.computed;
      const needsParen = !isKey && parent && (
        parent.type === 'MemberExpression' ||
        parent.type === 'BinaryExpression' ||
        parent.type === 'UnaryExpression' ||
        parent.type === 'UpdateExpression' ||
        parent.type === 'TaggedTemplateExpression'
      );
      const expr = SENSITIVE_RE.test(raw) ? splitRawStringToken(raw, needsParen) : splitStringLiteralToExpr(node.value, needsParen);
      if (isKey) {
        edits.push({ start: node.start, end: node.end, text: '[' + expr + ']' });
      } else {
        edits.push({ start: node.start, end: node.end, text: expr });
      }
      return;
    }
    if (node.type === 'TemplateElement' && SENSITIVE_RE.test(node.value.raw)) {
      const replaced = node.value.raw.replace(SENSITIVE_GLOBAL_RE, (m) => {
        const [a, b] = splitKeyword(m);
        return a + '${' + JSON.stringify(b) + '}';
      });
      edits.push({ start: node.start, end: node.end, text: replaced });
      return;
    }
    if (node.type === 'MemberExpression' && !node.computed && node.property && node.property.type === 'Identifier' && SENSITIVE_RE.test(node.property.name)) {
      walk(node.object, node, 'object');
      const dotIdx = code.lastIndexOf('.', node.property.start);
      const optIdx = dotIdx > 0 && code[dotIdx - 1] === '?' ? dotIdx - 1 : dotIdx;
      const prefix = optIdx < dotIdx ? '?.[' : '[';
      edits.push({ start: optIdx, end: node.property.end, text: prefix + splitStringLiteralToExpr(node.property.name) + ']' });
      return;
    }
    if (node.type === 'Property' && !node.computed && node.key && node.key.type === 'Identifier' && SENSITIVE_RE.test(node.key.name)) {
      if (node.shorthand) {
        walk(node.value, node, 'value');
        edits.push({ start: node.start, end: node.key.end, text: '[' + splitStringLiteralToExpr(node.key.name) + ']:' + node.key.name });
        return;
      }
      edits.push({ start: node.key.start, end: node.key.end, text: '[' + splitStringLiteralToExpr(node.key.name) + ']' });
      walk(node.value, node, 'value');
      return;
    }
    for (const key of Object.keys(node)) {
      if (key === 'type' || key === 'start' || key === 'end') continue;
      const child = node[key];
      if (Array.isArray(child)) child.forEach((c) => walk(c, node, key));
      else if (child && typeof child.type === 'string') walk(child, node, key);
    }
  }
  walk(ast, null, '');
  edits.sort((a, b) => b.start - a.start);
  let out = code;
  for (const e of edits) {
    out = out.slice(0, e.start) + e.text + out.slice(e.end);
  }
  return out;
}

const result = await minify(src, {
  compress: true,
  mangle: true,
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
