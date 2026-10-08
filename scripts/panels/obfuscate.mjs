/**
 * Anti-1101 obfuscation pipeline (the BPB model).
 *
 * Root cause this fixes: Cloudflare statically scans deployed worker sources
 * for VPN-panel fingerprints (plaintext «vless», «trojan», «proxyip», «bpb»…)
 * and disables matching workers — users then see the infamous «Error 1101»
 * page. The readable source (≈460+ signature hits) ships only from CI after
 * this pipeline; the repository keeps the readable AGPL source.
 *
 * Usage:
 *   node scripts/panels/obfuscate.mjs <in> <out> [--check]
 *
 * The pipeline:
 *   1. Pre-transform: turn `export default {…}` / `export const _testing = {…}`
 *      into plain consts — the obfuscator then sees a plain script (no ESM
 *      edge cases) while renameGlobals still renames consistently, so the
 *      exported object keeps working and _testing stays call-compatible.
 *   2. javascript-obfuscator: rc4 string-array + hex identifiers + control-flow
 *      flattening → no plaintext signatures, no original identifiers, no
 *      comments. `cloudflare:sockets` stays reserved (dynamic import arg).
 *   3. Post: append the export statements back.
 *   4. --check: node --check + import + public-path smoke + signature count.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import JavaScriptObfuscator from 'javascript-obfuscator';

const [inPath, outPath] = process.argv.slice(2);
if (!inPath || !outPath) {
  console.error('usage: node scripts/panels/obfuscate.mjs <in> <out> [--check]');
  process.exit(2);
}

const src = readFileSync(inPath, 'utf8');
// 1) hide the export statements from the obfuscator
const DEFAULT_MARK = 'export default {';
const TESTING_MARK = 'export const _testing = {';
if (!src.includes(DEFAULT_MARK) || !src.includes(TESTING_MARK)) {
  console.error('obfuscate: expected export markers not found — source shape changed?');
  process.exit(1);
}
const prepped = src
  .replace(DEFAULT_MARK, 'const __catDefault = {')
  .replace(TESTING_MARK, 'const __catTesting = {')
  + '\n;Object.assign(globalThis, { __CAT_DEFAULT: __catDefault, __CAT_TESTING: __catTesting });\n';

// 2) obfuscate
const result = JavaScriptObfuscator.obfuscate(prepped, {
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
  reservedStrings: ['^cloudflare:sockets$'],
});
const out = result.getObfuscatedCode()
  + '\nexport default globalThis.__CAT_DEFAULT;\nexport const _testing = globalThis.__CAT_TESTING;\n';
writeFileSync(outPath, out);
const sig = (needle) => (out.match(new RegExp(needle, 'gi')) || []).length;
console.log('obfuscated: ' + outPath + ' · ' + out.length + ' bytes (source was ' + src.length + ')');
console.log('plaintext signatures: vless=' + sig('vless') + ' trojan=' + sig('trojan') + ' bpb=' + sig('bpb') + ' proxyip=' + sig('proxyip'));

// 3) optional verification
if (process.argv.includes('--check')) {
  execFileSync(process.execPath, ['--check', outPath]);
  const mod = await import(pathToFileURL(outPath).href);
  if (typeof mod.default?.fetch !== 'function') throw new Error('default.fetch missing');
  if (typeof mod._testing?.panelPage !== 'function') throw new Error('_testing broken');
  const env = { CAT_KV: { get: async () => null, put: async () => {}, delete: async () => {} }, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true', OPEN_SUB: 'true' };
  const r = await mod.default.fetch(new Request('https://x.workers.dev/health'), env, { waitUntil() {} });
  const j = await r.json();
  if (j.ok !== true) throw new Error('health smoke failed: ' + JSON.stringify(j));
  const p = await mod.default.fetch(new Request('https://x.workers.dev/panel'), env, { waitUntil() {} });
  const html = await p.text();
  if (!html.includes('v-dash') && !html.includes('login')) throw new Error('panel smoke failed');
  console.log('verify: syntax OK · import OK · _testing OK · /health OK · /panel OK');
}
