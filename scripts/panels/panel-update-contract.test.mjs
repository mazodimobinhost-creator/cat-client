/**
 * Panel-update contract — the shipped worker artifact must be identifiable.
 *
 * Why this exists (the «panel went back to the old version» report):
 * CI previously shipped only an obfuscated worker. Its string-array encoding hid `const CAT_PANEL_VERSION = '6.x.y'`, so every consumer that
 * identifies a build by regex saw «no version»:
 *   - PanelUpdate.kt treated its own bundle as 0.0.0, rejected the (unmarked)
 *     release asset and then accepted the stale `main` copy from jsDelivr
 *     (5.23.13) as an «upgrade» — one tap on «Update panel» replaced a live 6.x
 *     panel with 5.23.13.
 *   - deploy-bot and the wizard reported the version as «?».
 * Nothing tested the REAL artifact (the wizard test used a hand-written fake that
 * already contained the marker), so it shipped for nine releases.
 *
 * This test pins, against the artifact that actually ships:
 *   1. the exact regexes the consumers use — extracted from THEIR source, not
 *      copied — all find the same version;
 *   2. that version is the one the running worker reports at /api/version;
 *   3. the marker is the first line (a truncated download still carries it);
 *   4. the module check PanelUpdate does (`export default`) holds;
 *   5. the deploy artifact remains compact enough for Worker startup;
 *   6. the call-site guards that stop a downgrade are still wired in.
 *
 * CAT_PANEL_WORKER=<file>  test that file. When it is the readable source an
 * minified artifact is built first (needs `npm i --no-save terser`);
 * when it is already minified in CI it is tested as is.
 */
import { readFileSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const ROOT = resolve(new URL('../..', import.meta.url).pathname);
const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const SOURCE = join(ROOT, 'app/src/main/assets/panels/catclient.worker.js');
const target = process.env.CAT_PANEL_WORKER || SOURCE;

let failures = 0;
function check(name, ok, detail) {
  if (ok) console.log('  ✓ ' + name);
  else { failures++; console.log('  ✗ ' + name + (detail ? '  → ' + detail : '')); }
}

// ── 0) get the artifact that ships ───────────────────────────────────────────
let artifactPath = target;
let sourceVersion = null;
const targetText = readFileSync(target, 'utf8');
if (targetText.includes('export default {')) {
  sourceVersion = (/CAT_PANEL_VERSION\s*=\s*'([0-9]+(?:\.[0-9]+)+)'/.exec(targetText) || [])[1] || null;
  artifactPath = join(mkdtempSync(join(tmpdir(), 'cat-contract-')), 'artifact.js');
  try {
    execFileSync(process.execPath, [join(ROOT, 'scripts/panels/minify.mjs'), target, artifactPath], { stdio: 'pipe' });
  } catch (e) {
    console.log('✗ could not build the minified artifact — run: npm i --no-save terser\n' + String(e.stderr || e.message).slice(0, 400));
    process.exit(1);
  }
}
const artifact = readFileSync(artifactPath, 'utf8');
console.log('artifact: ' + artifactPath + ' (' + artifact.length + ' bytes)');

// ── 1) the consumers' own regexes, extracted from their sources ──────────────
// app: PanelUpdate.PANEL_VERSION_MARKER (a Kotlin string literal → undo its escaping)
const kt = read('app/src/main/java/com/cat/client/PanelUpdate.kt');
const ktLiteral = /PANEL_VERSION_MARKER\s*=\s*Regex\("((?:[^"\\]|\\.)*)"\)/.exec(kt);
check('PanelUpdate.kt still defines PANEL_VERSION_MARKER', !!ktLiteral);
const APP_RE = ktLiteral ? new RegExp(ktLiteral[1].replace(/\\\\/g, '\\')) : null;

// deploy-bot: the regex literal on the `const ver = …` line
const bot = read('deploy-bot/worker.js');
const botLiteral = /code\.match\(\/(CAT_PANEL_VERSION[^/]*)\/\)/.exec(bot);
check('deploy-bot still reads the marker with a regex literal', !!botLiteral);
const BOT_RE = botLiteral ? new RegExp(botLiteral[1]) : null;

// wizard: builds `marker + "\\s*=\\s*'([^']+)'"` dynamically — pin the construction
const wiz = read('app/src/main/assets/panels/catclient.wizard.js');
const wizBuilds = wiz.includes("new RegExp(marker + \"\\\\s*=\\\\s*'([^']+)'\")");
check('wizard still builds its marker regex as «marker + \\s*=\\s*\'([^\']+)\'»', wizBuilds);
const WIZ_RE = new RegExp("CAT_PANEL_VERSION\\s*=\\s*'([^']+)'");

const found = {};
for (const [name, re] of [['app (PanelUpdate.kt)', APP_RE], ['deploy-bot', BOT_RE], ['wizard', WIZ_RE]]) {
  const m = re ? re.exec(artifact) : null;
  found[name] = m ? m[1] : null;
  check(name + ' finds a version in the shipped artifact', !!m, m ? '' : 'NOT FOUND — «Update panel» would see 0.0.0 / «?»');
}
const versions = new Set(Object.values(found));
check('all consumers agree on one version', versions.size === 1 && !versions.has(null), JSON.stringify(found));
const marked = found['app (PanelUpdate.kt)'];

// ── 2) the marker tells the truth ────────────────────────────────────────────
const mod = await import(pathToFileURL(artifactPath).href);
const env = { CAT_KV: { get: async () => null, put: async () => {}, delete: async () => {} }, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true', OPEN_SUB: 'true' };
const res = await mod.default.fetch(new Request('https://x.workers.dev/api/version'), env, { waitUntil() {} });
const live = await res.json();
check('/api/version of the artifact reports panel=cat-panel (what deployedVersion() requires)', live.panel === 'cat-panel');
check('marker equals the version the running artifact reports', marked === live.version, marked + ' vs ' + live.version);
if (sourceVersion) check('marker equals the readable source version', marked === sourceVersion, marked + ' vs ' + sourceVersion);

// the panel's OWN «check for updates» (/api/update-check): fed the real artifact text, pretending
// it is a newer release — before 6.53.1 this always answered `latest: ""` (button showed «?»)
const asNewer = artifact.replace("CAT_PANEL_VERSION = '" + marked + "'", "CAT_PANEL_VERSION = '99.1.0'");
const realFetch = globalThis.fetch;
globalThis.fetch = async (u) => String(u).includes('/releases/latest/download/catclient.worker.js')
  ? new Response(asNewer, { status: 200 }) : new Response('', { status: 404 });
const uc = await mod.default.fetch(new Request('https://x.workers.dev/api/update-check'), env, { waitUntil() {} });
const ucj = await uc.json();
globalThis.fetch = realFetch;
check("the panel's own update-check reads the shipped artifact's version line", ucj.ok === true && ucj.latest === '99.1.0', JSON.stringify(ucj));

// ── 3/4/5) shape ─────────────────────────────────────────────────────────────
check('marker is the very first line (survives a truncated download)', /^\/\* CAT_PANEL_VERSION = '[0-9]+(?:\.[0-9]+)+' \*\/\n/.test(artifact));
check("artifact passes PanelUpdate's module check (contains «export default»)", artifact.includes('export default'));
// 6.76.0: 800 000 → 860 000 characters (🔗 link panels); the release guard counts bytes and stays at 900 000.
check('normal minified artifact stays below 860 K characters', artifact.length < 860000, String(artifact.length));

// ── 6) the downgrade guards are still wired in ───────────────────────────────
const pu = kt;
const cw = read('app/src/main/java/com/cat/client/CloudflareWorker.kt');
const ma = read('app/src/main/java/com/cat/client/MainActivity.kt');
check('PanelUpdate has isDowngrade()', /fun isDowngrade\(/.test(pu));
check('PanelUpdate gates the repository fallback (acceptRepositoryFallback)', /fun acceptRepositoryFallback\(/.test(pu) && /acceptRepositoryFallback\(bundledVersion/.test(pu));
check('PanelUpdate no longer treats source readability as an Error 1101 detector', !/looksReadable\(/.test(pu));
check('CloudflareWorker.updateBuiltIn refuses a downgrade', /PanelUpdate\.isDowngrade\(fromVersion, script\.version\)/.test(cw));
check('MainActivity has the shared blockPanelDowngrade() built on isDowngrade',
  /private fun blockPanelDowngrade\([^)]*\): Boolean \{\s*if \(!PanelUpdate\.isDowngrade\(deployed, candidate\)\) return false/.test(ma));
check('«Update panel» dialog blocks a downgrade before anything else', /blockPanelDowngrade\(deployed, newest\.version\)\) return@launch/.test(ma));
check('«Manage panel» dialog blocks a downgrade before copying/uploading', /blockPanelDowngrade\(deployed, script\.version\)\) return@setPositiveButton/.test(ma));
check('«Manage panel» no longer calls an OLDER source an update (was `newest.version != deployed`)', !/newest != null && newest\.version != deployed/.test(ma));

// The Cloudflare deploy job must build the exact artifact named by wrangler.jsonc,
// then compare the live version with the source marker. A generic /health ok is
// insufficient: an old snapshot can answer health successfully.
const wranglerConfig = read('wrangler.jsonc');
const configuredMain = (/'main'\s*:\s*'([^']+)'/.exec(wranglerConfig) || /"main"\s*:\s*"([^"]+)"/.exec(wranglerConfig) || [])[1];
const deployWorkflow = read('.github/workflows/deploy-worker.yml');
// The entrypoint is either the stealth build (deploy artifact for Cloudflare —
// deliberately marker-free) or the minified build. Either way it must be one of
// the two artifacts this repo builds, never a stale or hand-made file.
check('Wrangler configured entrypoint is one of the two built deploy artifacts',
  configuredMain === 'dist-panel/catpanel.stealth.js' || configuredMain === 'dist-panel/catpanel.min.js',
  configuredMain);
const deployBuildCommand = configuredMain && 'node scripts/panels/' + (configuredMain === 'dist-panel/catpanel.stealth.js' ? 'stealth.mjs' : 'minify.mjs') + ' app/src/main/assets/panels/catclient.worker.js ' + configuredMain + ' --check';
check('deploy workflow builds the current source into Wrangler’s actual entrypoint', !!deployBuildCommand && deployWorkflow.includes(deployBuildCommand), deployBuildCommand);
check('deploy workflow checks live /api/version against the source marker', deployWorkflow.includes('$URL/api/version') && deployWorkflow.includes('"$VERSION_JSON" "$PVER"'));

console.log(failures ? '\nPANEL UPDATE CONTRACT FAILED (' + failures + ')' : '\nPANEL UPDATE CONTRACT PASSED');
process.exit(failures ? 1 : 0);
