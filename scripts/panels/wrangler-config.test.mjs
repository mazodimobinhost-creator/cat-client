/**
 * wrangler.jsonc is the deploy contract for EVERY path that publishes the panel
 * (the README button, the repo's deploy workflow, and Cloudflare Workers Builds).
 * This test is the guard for the three things that were wrong or missing in it,
 * because none of them fails locally — they fail inside a Cloudflare build:
 *
 *   1. `main` must resolve to a committed artifact, and that artifact must be the
 *      version the source declares (otherwise /api/version and the code disagree).
 *   2. The KV binding must be named CAT_KV, must use only fields Wrangler knows
 *      (the old file carried a `name` field, which Wrangler reported as
 *      «Unexpected fields found in kv_namespaces[0]»), and an `id` — if one is
 *      ever committed — must be a real 32-hex namespace id. A placeholder string
 *      in an active `id` field cannot resolve and breaks the deploy.
 *   3. compatibility_date must be a real date that is not in the future.
 *
 * Run standalone:  node scripts/panels/wrangler-config.test.mjs
 */
import { strict as assert } from 'node:assert';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const read = (p) => readFileSync(path.join(repo, p), 'utf8');

/** JSONC → JSON: strips // and /* *\/ comments without touching string contents. */
function stripJsonc(text) {
  let out = '';
  let inStr = false; let esc = false; let line = false; let block = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]; const n = text[i + 1];
    if (line) { if (c === '\n') { line = false; out += c; } continue; }
    if (block) { if (c === '*' && n === '/') { block = false; i++; } continue; }
    if (inStr) {
      out += c;
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; out += c; continue; }
    if (c === '/' && n === '/') { line = true; i++; continue; }
    if (c === '/' && n === '*') { block = true; i++; continue; }
    out += c;
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
}

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

console.log('\nwrangler.jsonc — the deploy contract for Workers Builds\n');

const raw = read('wrangler.jsonc');
let cfg;
try { cfg = JSON.parse(stripJsonc(raw)); } catch (e) { ok('wrangler.jsonc parses as JSONC', false, e.message); }
ok('wrangler.jsonc parses as JSONC', !!cfg);

/* ---------- 1) main ---------- */
ok('main points at the minified deploy artifact',
  cfg?.main === 'dist-panel/catpanel.min.js', cfg?.main);
ok('…and that file is actually committed', existsSync(path.join(repo, cfg?.main || 'x')));
{
  const marker = (s) => (/CAT_PANEL_VERSION\s*=\s*'([0-9.]+)'/.exec(s) || [])[1] || null;
  const sourceV = marker(read('app/src/main/assets/panels/catclient.worker.js'));
  const deployV = marker(read(cfg.main));
  ok('the deployed artifact declares a version', !!deployV, deployV);
  ok('the deployed artifact matches the source version (no stale deploy file)',
    !!sourceV && sourceV === deployV, `source=${sourceV} deploy=${deployV}`);
}

/* ---------- 2) KV binding ---------- */
const kvList = cfg?.kv_namespaces;
ok('exactly one KV namespace is declared', Array.isArray(kvList) && kvList.length === 1, JSON.stringify(kvList));
const kv = kvList?.[0] || {};
ok('the KV binding is named CAT_KV', kv.binding === 'CAT_KV', kv.binding);
{
  const allowed = new Set(['binding', 'id', 'preview_id', 'remote']);
  const unknown = Object.keys(kv).filter((k) => !allowed.has(k));
  ok('the KV entry carries no field Wrangler does not know', unknown.length === 0, unknown.join(','));
}
if ('id' in kv) {
  ok('a committed KV id is a real 32-hex namespace id (never a placeholder)',
    /^[0-9a-f]{32}$/.test(String(kv.id)), JSON.stringify(kv.id));
} else {
  ok('no id committed → Wrangler provisions/keeps the namespace itself (documented behaviour)', true);
}
if ('preview_id' in kv) {
  ok('a committed preview_id is a real 32-hex id too', /^[0-9a-f]{32}$/.test(String(kv.preview_id)), kv.preview_id);
}
{
  // The placeholder must stay inert: commented out in the file, never parsed.
  ok('the id placeholder in the file is commented out, not live',
    /^\s*\/\/\s*"id":/m.test(raw) && !/"id"\s*:/.test(stripJsonc(raw)), 'checked against the comment-stripped config');
}

/* ---------- 3) compatibility_date ---------- */
{
  const raw2 = cfg?.compatibility_date;
  const today = new Date().toISOString().slice(0, 10);
  ok('compatibility_date is a real YYYY-MM-DD date', /^\d{4}-\d{2}-\d{2}$/.test(String(raw2)), raw2);
  ok('compatibility_date is not in the future (Cloudflare rejects those)', String(raw2) <= today, `${raw2} vs today ${today}`);
  ok('compatibility_date is not ancient (>= 2024-01-01)',
    String(raw2) >= '2024-01-01', raw2);
}

/* ---------- the other deploys must match this path ---------- */
{
  const wf = read('.github/workflows/deploy-worker.yml');
  ok('the repo deploy workflow deploys with --no-bundle (same bytes Cloudflare Builds upload)',
    /wrangler@latest deploy --no-bundle/.test(wf) || /wrangler.*deploy --no-bundle/.test(wf),
    'deploy step');
  ok('…and it still builds the artifact named by main',
    wf.includes('node scripts/panels/minify.mjs app/src/main/assets/panels/catclient.worker.js ' + cfg.main + ' --check'));
}
{
  ok('vars stay strings (Workers rejects non-string vars)', ['OPEN_PANEL', 'OPEN_SUB'].every((k) => typeof cfg?.vars?.[k] === 'string'));
  ok('the 12-hourly feeds cron is declared', Array.isArray(cfg?.triggers?.crons) && cfg.triggers.crons.length >= 1, JSON.stringify(cfg?.triggers?.crons));
  ok('Workers Logs are switched on for deploys', cfg?.observability?.enabled === true, JSON.stringify(cfg?.observability));
}

console.log('\n' + (fail === 0 ? `wrangler config: ALL ${pass} PASSED` : `wrangler config: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
