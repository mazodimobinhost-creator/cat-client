/**
 * Every deploy path must upload the Worker with the SAME runtime compatibility
 * settings: wrangler.jsonc (deploy button / Workers Builds / deploy-worker.yml),
 * the Wizard (catclient.wizard.js) and the in-app deploy (CloudflareWorker.kt).
 * Also pins the evidence for dropping nodejs_compat: the Worker source must not
 * use a Node-only API. This does NOT prove a given date/flag set works on a live
 * Cloudflare account (it was not tested there) and does not claim to explain 1101.
 *
 * Run: node scripts/panels/compat-consistency.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (p) => readFileSync(path.join(repo, p), 'utf8');
let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } };

function stripJsonc(text) {
  let out = ''; let inStr = false; let esc = false; let line = false; let block = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]; const n = text[i + 1];
    if (line) { if (c === '\n') { line = false; out += c; } continue; }
    if (block) { if (c === '*' && n === '/') { block = false; i++; } continue; }
    if (inStr) { out += c; if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') { inStr = true; out += c; continue; }
    if (c === '/' && n === '/') { line = true; i++; continue; }
    if (c === '/' && n === '*') { block = true; i++; continue; }
    out += c;
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
}

const wrangler = JSON.parse(stripJsonc(read('wrangler.jsonc')));
const wizard = (await import(pathToFileURL(path.join(repo, 'app/src/main/assets/panels/catclient.wizard.js')).href))._testing;
const kt = read('app/src/main/java/com/cat/client/CloudflareWorker.kt');
const ktDate = (/\.put\("compatibility_date",\s*"([0-9-]+)"\)/.exec(kt) || [])[1];
const ktFlagsRaw = (/\.put\("compatibility_flags",\s*(JSONArray\([^)]*\)(?:\.put\("[^"]*"\))*)\)/.exec(kt) || [])[1] || '';
const ktFlags = [...ktFlagsRaw.matchAll(/\.put\("([^"]*)"\)/g)].map((m) => m[1]);

check('wrangler.jsonc declares compatibility_flags explicitly', Array.isArray(wrangler.compatibility_flags), JSON.stringify(wrangler.compatibility_flags));
check('compatibility_date: wrangler.jsonc == Wizard', wrangler.compatibility_date === wizard.COMPAT_DATE, wrangler.compatibility_date + ' vs ' + wizard.COMPAT_DATE);
check('compatibility_date: Kotlin in-app deploy == Wizard', ktDate === wizard.COMPAT_DATE, ktDate + ' vs ' + wizard.COMPAT_DATE);
check('compatibility_flags: wrangler.jsonc == Wizard', JSON.stringify(wrangler.compatibility_flags) === JSON.stringify(wizard.COMPAT_FLAGS));
check('compatibility_flags: Kotlin in-app deploy == Wizard', JSON.stringify(ktFlags) === JSON.stringify(wizard.COMPAT_FLAGS), JSON.stringify(ktFlags));
check('no nodejs_compat anywhere in the deploy paths', ![...wrangler.compatibility_flags, ...wizard.COMPAT_FLAGS, ...ktFlags].includes('nodejs_compat'));
check('Kotlin metadata line was parsed (guards against a silent regex miss)', !!ktDate && ktFlagsRaw.startsWith('JSONArray('));

const wf = read('.github/workflows/deploy-worker.yml');
check('deploy-worker.yml passes no compatibility flag/date on the command line', !/--compatibility-(flag|date)/.test(wf));

// Evidence for "nodejs_compat only if really needed": no Node-only API in the readable source.
const src = read('app/src/main/assets/panels/catclient.worker.js');
const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const nodeApis = [
  [/\bBuffer\b/, 'Buffer'], [/\bprocess\.(env|nextTick|version|platform)\b/, 'process.*'],
  [/from\s+['"]node:/, "import from 'node:'"], [/\brequire\s*\(/, 'require()'],
  [/\bAsyncLocalStorage\b/, 'AsyncLocalStorage'], [/\b__dirname\b|\b__filename\b/, '__dirname/__filename'],
];
for (const [re, label] of nodeApis) check('Worker source does not use ' + label, !re.test(code));

const srcV = (/CAT_PANEL_VERSION\s*=\s*'([0-9.]+)'/.exec(src) || [])[1];
check('Wizard PANEL_MIN_VERSION <= bundled CAT_PANEL_VERSION', wizard.compareVersions(wizard.PANEL_MIN_VERSION, srcV) <= 0, wizard.PANEL_MIN_VERSION + ' vs ' + srcV);

console.log(failures === 0 ? '\nCOMPAT CONSISTENCY PASSED' : '\n' + failures + ' COMPAT CHECK(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
