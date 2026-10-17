/**
 * Stealth deploy artifact — the file that is deployed to Cloudflare must not be
 * identifiable as a proxy panel from its own source.
 *
 * WHY THIS EXISTS (owner's request): «من میخام ورکر بسازم، ناخناش کن… نمیخام
 * کد اونجا مشخص باشه» — the owner wants the worker that sits on Cloudflare to
 * carry no recognizable fingerprint: nothing that says vless/trojan/clash/
 * singbox/panel/telegram/subscription to anyone (or anything) that reads the
 * deployed script.
 *
 * WHAT THE PLAIN OBFUSCATED BUILD (catpanel.obf.js) STILL LEAKS. javascript-
 * obfuscator encodes string *literals* (the whole panel HTML, the Persian UI
 * text, the share-link prefixes) but it does NOT touch:
 *   1. regex literals  — `/^(socks5h?|socks|https?):\/\//`, `/clash|mihomo|stash/i`,
 *                        `/\/(sub|sub64|clash|singbox|xray)(?:\/([^/]+))?\/?$/`,
 *                        and the marker regex `/CAT_PANEL_VERSION\s*=\s*'…'/`.
 *   2. object/JSON keys — `'clash':`, `'singbox':`, `'subscription-userinfo':`,
 *                        `'bypassIran'`, `'tgToken'`, `'proxyCountries'`…
 *   3. member properties — `env.CAT_KV`, `env.UUID`, `env.PANEL_PASSWORD`…
 *   4. the first-line version marker `/* CAT_PANEL_VERSION = '6.66.5' *​/`.
 * Everything else is already rc4-encoded noise.
 *
 * WHAT THIS SCRIPT DOES
 *   1. acorn parses the readable source (module, top-level await allowed) and the
 *      scrubber rewrites exactly the four leak classes above with offset surgery:
 *        regex literal          → new RegExp('<pattern>'[, '<flags>'])
 *        string literal/key     → 'cl'+'ash'   (value is identical at runtime;
 *                                 the obfuscator then encrypts both halves)
 *        identifier property    → obj['bypass'+'Iran']
 *        `cloudflare:sockets`   → 'cloudflare:' + 'sockets'
 *      A concatenation stays a *runtime* value, and workerd resolves it (proved
 *      with a probe worker: `await import('cloudflare:' + 'sockets')` returns the
 *      real module). No semantics change: only how the source reads.
 *   2. javascript-obfuscator with the same hardened profile as the in-app
 *      updater snapshot (rc4 string array, hex identifiers, control-flow
 *      flattening, transformObjectKeys) but with NEUTRAL smuggled globals
 *      (`__d`/`__t` instead of `__CAT_DEFAULT`/`__CAT_TESTING`).
 *   3. NO version marker line. The marker is a *source-artifact* contract (the
 *      in-app updater, deploy-bot and wizard parse it out of the files they
 *      download); the deployed worker is identified by `/api/version` instead —
 *      nothing in the app or wizard reads the marker out of a *deployed* worker
 *      (verified: PanelUpdate.deployedVersion() calls /api/version).
 *   4. Re-roll: the rc4 string array is base64 noise, so a signature can appear
 *      there by pure chance. A build whose plaintext scan is dirty is thrown away
 *      and rebuilt (up to 16 rolls) — never shipped, never silently accepted.
 *
 * Usage:
 *   node scripts/panels/stealth.mjs <in> <out> [--check]
 *
 * --check gates (all must pass on the file that is actually written):
 *   node --check · import · default.fetch · _testing.panelPage ·
 *   /health ok · /panel login page · /api/version == source marker ·
 *   marker-free · zero plaintext signatures.
 */
import { readFileSync, writeFileSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { gzipSync } from 'node:zlib';
import { pathToFileURL } from 'node:url';
import * as acorn from 'acorn';
import JavaScriptObfuscator from 'javascript-obfuscator';

const [inPath, outPath] = process.argv.slice(2);
if (!inPath || !outPath) {
  console.error('usage: node scripts/panels/stealth.mjs <in> <out> [--check]');
  process.exit(2);
}

const src = readFileSync(inPath, 'utf8');
const VERSION_RE = /CAT_PANEL_VERSION\s*=\s*'([0-9]+(?:\.[0-9]+)+)'/;
const version = (VERSION_RE.exec(src) || [])[1];
if (!version) {
  console.error('stealth: CAT_PANEL_VERSION marker not found in the source');
  process.exit(1);
}

/* ------------------------------------------------------------------ */
/* 1) scrub                                                           */
/* ------------------------------------------------------------------ */

/**
 * Words that must never appear in plaintext in the deployed script. Matched as
 * case-insensitive substrings, so one entry covers a family: `panel` also hides
 * `panelPath`/`panelPage`/`PANEL_PASSWORD`, `token` hides `tgToken`, `iran`
 * hides `bypassIran`, `subscription` hides `subscription-userinfo`.
 * Keep every entry ≥ 4 characters: shorter words match base64 noise by chance
 * and would make the re-roll loop spin forever.
 */
const SENSITIVE = [
  'vless', 'trojan', 'vmess', 'v2ray', 'xray', 'shadowsocks', 'socks',
  'clash', 'mihomo', 'singbox', 'sing-box', 'hysteria', 'tuic', 'naive',
  'wireguard', 'warp', 'proxyip', 'proxy', 'bypass', 'iran', 'telegram',
  'subscription', 'sub64', 'panel', 'password', 'secret', 'token', 'uuid',
  'catpanel', 'cat_panel', 'cat-panel', 'catclient', 'cat_kv', 'camouflage',
  'cloudflare:sockets',
];
const esc = (w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const SENSITIVE_RE = new RegExp(SENSITIVE.map(esc).join('|'), 'i');

/** `'clash'` → `'cl'+'ash'` (the value is unchanged; both halves get encrypted). */
function splitLiteral(value) {
  if (value.length < 2) return null;
  const cut = Math.max(1, Math.ceil(value.length / 2));
  const a = value.slice(0, cut), b = value.slice(cut);
  return JSON.stringify(a) + '+' + JSON.stringify(b);
}

const ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'module' });
const edits = [];
const seen = new Set();
const push = (start, end, text, why) => {
  if (seen.has(start)) return;
  seen.add(start);
  edits.push({ start, end, text, why });
};

/**
 * Walk every child node generically. An earlier hand-written key whitelist
 * missed `ExpressionStatement.expression`, so statement-level calls such as
 * `socksAtt.unshift({ socks: c, … })` were never visited and their plaintext
 * keys sailed into the artifact. Anything that looks like a node is walked.
 */
function walk(node, parent) {
  if (!node || typeof node.type !== 'string') return;
  let skipChildren = false;

  // (1) regex literal → new RegExp('<pattern>'[, '<flags>'])
  if (node.type === 'Literal' && node.regex) {
    const flags = node.regex.flags ? ', ' + JSON.stringify(node.regex.flags) : '';
    push(node.start, node.end, 'new RegExp(' + JSON.stringify(node.regex.pattern) + flags + ')', 'regex');
    skipChildren = true;
  }

  // (2) string literal / object key
  if (!skipChildren && node.type === 'Literal' && typeof node.value === 'string' && SENSITIVE_RE.test(node.value)) {
    const split = splitLiteral(node.value);
    const isKey = parent && parent.type === 'Property' && parent.key === node && !parent.computed;
    const isMethodKey = parent && (parent.type === 'MethodDefinition' || parent.type === 'PropertyDefinition') &&
      parent.key === node && !parent.computed;
    if (split) {
      if (isKey || isMethodKey) push(node.start, node.end, '[' + split + ']', 'string key');
      else push(node.start, node.end, split, 'string literal');
    }
  }

  // (3) identifier property: obj.bypassIran → obj['bypass'+'Iran']
  //     The edit must swallow the dot (`obj.prop` → NOT `obj.['prop']`), so the
  //     range starts where the object ends; `obj?.prop` keeps its optionality as
  //     `obj?.['prop']`.
  if (node.type === 'Identifier' && SENSITIVE_RE.test(node.name)) {
    const isMemberProp = parent && parent.type === 'MemberExpression' && parent.property === node && !parent.computed;
    const isObjKey = parent && parent.type === 'Property' && parent.key === node && !parent.computed && !parent.shorthand;
    const isMethodName = parent && (parent.type === 'MethodDefinition' || parent.type === 'PropertyDefinition') &&
      parent.key === node && !parent.computed;
    if (isMemberProp) {
      const bridge = src.slice(parent.object.end, node.start);
      const optional = bridge.includes('?');
      push(parent.object.end, node.end, (optional ? '?.' : '') + '[' + splitLiteral(node.name) + ']', 'member property');
    } else if (parent && parent.type === 'Property' && parent.key === node && !parent.computed && parent.shorthand) {
      // Shorthand: `{ masterUuid }` — the name is both key and binding, so the
      // property must be expanded (`{ ['master'+'Uuid']: masterUuid }`): the key
      // becomes an encrypted string while the binding keeps being renamed by the
      // obfuscator at every use site. `lastSeenAt`-style identifiers reached the
      // artifact this way (`masterUuid` leaked `uuid`).
      push(node.start, node.end, '[' + splitLiteral(node.name) + ']:' + node.name, 'shorthand');
    } else if (isObjKey || isMethodName) {
      push(node.start, node.end, '[' + splitLiteral(node.name) + ']', 'identifier key');
    }
  }

  for (const key of Object.keys(node)) {
    if (key === 'type') continue;
    const value = node[key];
    if (Array.isArray(value)) {
      for (const child of value) if (child && typeof child.type === 'string') walk(child, node);
    } else if (value && typeof value === 'object' && typeof value.type === 'string') {
      walk(value, node);
    }
  }
}
walk(ast, null);

// Apply from the end so earlier offsets stay valid.
const ordered = edits.slice().sort((a, b) => b.start - a.start);
let scrubbed = src;
for (const edit of ordered) {
  scrubbed = scrubbed.slice(0, edit.start) + edit.text + scrubbed.slice(edit.end);
}
const byWhy = {};
for (const e of edits) byWhy[e.why] = (byWhy[e.why] || 0) + 1;

/* ------------------------------------------------------------------ */
/* 2) obfuscate (neutral smuggled globals, no marker)                 */
/* ------------------------------------------------------------------ */

const DEFAULT_MARK = 'export default {';
const TESTING_MARK = 'export const _testing = {';
if (!scrubbed.includes(DEFAULT_MARK) || !scrubbed.includes(TESTING_MARK)) {
  console.error('stealth: expected export markers not found — source shape changed?');
  process.exit(1);
}
const prepped = scrubbed
  .replace(DEFAULT_MARK, 'const __d = {')
  .replace(TESTING_MARK, 'const __t = {')
  + '\n;Object.assign(globalThis, { __d: __d, __t: __t });\n';

const OPTIONS = {
  compact: true,
  controlFlowFlattening: true,
  controlFlowFlatteningThreshold: 0.25,
  deadCodeInjection: false,
  identifierNamesGenerator: 'hexadecimal',
  renameGlobals: true,
  selfDefending: false,
  debugProtection: false,
  stringArray: true,
  stringArrayEncoding: ['rc4'],
  stringArrayRotate: true,
  stringArrayShuffle: true,
  stringArrayThreshold: 1,
  transformObjectKeys: true,
  unicodeEscapeSequence: false,
  reservedStrings: [],
};

/**
 * The scan the artifact must pass: every sensitive word, case-insensitive —
 * but only in the *code*, never inside the string literals. The rc4 string array
 * is one megabyte of base64, and a 4–6 letter word matches it by pure chance
 * (that is what the obfuscate.mjs re-roll loop fights). Runtime decrypts those
 * literals back into the panel's real strings in memory; a source scan sees only
 * noise. What must be clean is everything a reader actually reads: identifiers,
 * regexes, object keys, comments.
 */
const codeOnly = (text) => text
  .replace(/"(?:[^"\\]|\\.)*"/g, '""')
  .replace(/'(?:[^'\\]|\\.)*'/g, "''")
  .replace(/`(?:[^`\\]|\\.)*`/g, '``');

const scanHits = (text) => {
  const code = codeOnly(text);
  const hits = {};
  for (const word of SENSITIVE) {
    const n = (code.match(new RegExp(esc(word), 'gi')) || []).length;
    if (n) hits[word] = n;
  }
  return hits;
};

/** Raw (unstripped) matches — reported, never fatal: base64 noise. */
const rawHits = (text) => {
  const raw = {};
  for (const word of SENSITIVE) {
    const n = (text.match(new RegExp(esc(word), 'gi')) || []).length;
    if (n) raw[word] = n;
  }
  return raw;
};

const dumpIdx = process.argv.indexOf('--dump-scrubbed');
if (dumpIdx > -1 && process.argv[dumpIdx + 1]) {
  writeFileSync(process.argv[dumpIdx + 1], scrubbed);
  console.log('stealth: scrubbed source → ' + process.argv[dumpIdx + 1]);
}
const srcHits = scanHits(src), scrubbedHits = scanHits(scrubbed);
console.log('stealth: source code scan = ' + JSON.stringify(srcHits));
console.log('stealth: scrubbed code scan = ' + JSON.stringify(scrubbedHits));

let out = '';
let attempts = 0;
let hits = {};
do {
  attempts += 1;
  const result = JavaScriptObfuscator.obfuscate(prepped, OPTIONS);
  out = result.getObfuscatedCode()
    + '\nexport default globalThis.__d;\nexport const _testing = globalThis.__t;\n';
  hits = scanHits(out);
} while (Object.keys(hits).length > 0 && attempts < 16);
if (Object.keys(hits).length > 0) {
  const code = codeOnly(out);
  for (const word of Object.keys(hits)) {
    const rx = new RegExp(esc(word), 'gi');
    let m, shown = 0;
    while ((m = rx.exec(code)) && shown < 3) {
      console.error('    ' + word + ': …' + code.slice(Math.max(0, m.index - 55), m.index + 55).replace(/\n/g, ' ') + '…');
      shown += 1;
    }
  }
  console.error('stealth: signatures survived ' + attempts + ' independent builds: ' + JSON.stringify(hits));
  console.error('stealth: refusing to ship an identifiable artifact');
  process.exit(1);
}
if (attempts > 1) {
  console.log('stealth: re-rolled ' + (attempts - 1) + 'x (signature noise in the rc4 string array)');
}
writeFileSync(outPath, out);

const bytes = statSync(outPath).size;
const gz = gzipSync(Buffer.from(out), { level: 9 }).length;
const lines = out.split('\n').length;
console.log('stealth: ' + outPath + ' · ' + bytes + ' bytes · gzip ' + gz + ' · lines ' + lines);
console.log('stealth: scrubbed ' + edits.length + ' spots = ' + JSON.stringify(byWhy));
console.log('stealth: code scan clean (' + SENSITIVE.length + ' words) · base64 noise (harmless): ' + JSON.stringify(rawHits(out)));

/* ------------------------------------------------------------------ */
/* 3) optional verification                                           */
/* ------------------------------------------------------------------ */
if (process.argv.includes('--check')) {
  execFileSync(process.execPath, ['--check', outPath]);
  const mod = await import(pathToFileURL(outPath).href);
  if (typeof mod.default?.fetch !== 'function') throw new Error('default.fetch missing');
  if (typeof mod._testing?.panelPage !== 'function') throw new Error('_testing broken');
  const env = {
    CAT_KV: { get: async () => null, put: async () => {}, delete: async () => {} },
    UUID: '11111111-2222-4333-8444-555555555555',
    OPEN_PANEL: 'true', OPEN_SUB: 'true',
  };
  const get = (path) => mod.default.fetch(new Request('https://x.workers.dev' + path), env, { waitUntil() {} });

  const health = await (await get('/health')).json();
  if (health.ok !== true) throw new Error('health smoke failed: ' + JSON.stringify(health));
  const panelHtml = await (await get('/panel')).text();
  if (!panelHtml.includes('login') && !panelHtml.includes('v-dash')) throw new Error('panel smoke failed');
  const versionJson = await (await get('/api/version')).json();
  if (versionJson.version !== version) throw new Error('worker reports ' + versionJson.version + ', source says ' + version);
  const sub = await get('/sub/' + env.UUID);
  if (sub.status !== 200) throw new Error('/sub smoke failed: HTTP ' + sub.status);

  if (/CAT_PANEL_VERSION/i.test(out)) throw new Error('the deploy artifact must not carry the version marker');
  const leftover = scanHits(out);
  if (Object.keys(leftover).length) throw new Error('plaintext signatures: ' + JSON.stringify(leftover));

  console.log('verify: syntax OK · import OK · _testing OK · /health OK · /panel OK · /sub OK · /api/version '
    + version + ' · marker-free · 0 signatures');
}
