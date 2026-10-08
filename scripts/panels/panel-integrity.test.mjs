/**
 * Panel-page integrity tests.
 *
 * The panel HTML ships as ONE big template literal with ~700 inline i18n keys
 * and one inline script. A single mistyped character there kills the whole page
 * («some buttons don't work»: every handler is dead because the script never
 * parses) — and nothing else in CI noticed, because the worker file itself stays
 * syntactically valid. This test therefore:
 *
 *   1. extracts the inline <script> from the rendered panel page and runs a real
 *      `node --check` on it;
 *   2. compares the fa and en i18n key sets and fails on any key that exists in
 *      one language only (a missing fa key = an empty button/title);
 *   3. verifies every `data-i="key"` / `data-ph="key"` in the markup has a
 *      translation.
 *
 * Usage: node scripts/panels/panel-integrity.test.mjs   (CAT_PANEL_WORKER=… to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ worker source parses');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const worker = (await import(workerPath)).default;
const KV = { m: new Map(), async get(k) { return this.m.get(k) ?? null; }, async put(k, v) { this.m.set(k, v); }, async list() { return { keys: [] }; } };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HTML_HOST = 'https://p.workers.dev';

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 200) : '')); }
};

const html = await (await worker.fetch(new Request(HTML_HOST + '/panel'), env, {})).text();

/* ── 1: the inline panel script must parse ─────────────────────────────── */
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
check('panel page carries exactly one inline script', scripts.length === 1, String(scripts.length));
const inline = scripts[0] || '';
check('inline script is not empty', inline.length > 10000, String(inline.length));

const dir = mkdtempSync(path.join(tmpdir(), 'catpanel-integrity-'));
const scriptFile = path.join(dir, 'panel-inline.js');
writeFileSync(scriptFile, inline);
let parseError = '';
try {
  execFileSync(process.execPath, ['--check', scriptFile], { stdio: 'pipe' });
} catch (e) {
  parseError = String(e.stderr || e.stdout || e.message).slice(0, 400);
}
check('inline panel script parses (a broken script kills every button)', parseError === '', parseError);

/* ── 2: fa and en must carry the same keys ─────────────────────────────── */
function tableKeys(lang) {
  const marker = lang === 'fa' ? 'var I18N={' : 'var I18N={';
  const i = inline.indexOf(marker);
  if (i < 0) return null;
  // isolate the language branch: fa first, en second
  const rest = inline.slice(i);
  const faStart = rest.indexOf('fa:{');
  const enStart = rest.indexOf('en:{');
  const faEnd = rest.indexOf('},', faStart); // the fa branch closes with }, before en:
  if (faStart < 0 || enStart < 0) return null;
  const slice = lang === 'fa' ? rest.slice(faStart + 3, enStart) : rest.slice(enStart + 3, rest.indexOf('};', enStart));
  const keys = new Set();
  for (const m of slice.matchAll(/(?:^|[,{\n])\s*([A-Za-z_][A-Za-z0-9_]*)\s*:/g)) keys.add(m[1]);
  return keys;
}
const fa = tableKeys('fa');
const en = tableKeys('en');
check('both i18n tables found', !!fa && !!en, `fa=${fa && fa.size} en=${en && en.size}`);
if (fa && en) {
  const faOnly = [...fa].filter((k) => !en.has(k));
  const enOnly = [...en].filter((k) => !fa.has(k));
  check('no key exists in fa only (empty label in English)', faOnly.length === 0, faOnly.join(', '));
  check('no key exists in en only (empty label in Persian)', enOnly.length === 0, enOnly.join(', '));
  check('both tables are substantial', fa.size > 300 && en.size > 300, `fa=${fa.size} en=${en.size}`);
}

/* ── 3: every data-i / data-ph reference has a translation ─────────────── */
const refs = new Set();
for (const m of html.matchAll(/data-(?:i|ph)="([A-Za-z0-9_]+)"/g)) refs.add(m[1]);
const missing = [...refs].filter((k) => !(fa && fa.has(k)) || !(en && en.has(k)));
check('every data-i/data-ph key is translated in both languages', missing.length === 0, missing.join(', '));
console.log(`  (${refs.size} markup keys checked)`);

console.log(failures === 0 ? 'PANEL INTEGRITY PASSED' : `PANEL INTEGRITY FAILED (${failures})`);
process.exit(failures === 0 ? 0 : 1);
