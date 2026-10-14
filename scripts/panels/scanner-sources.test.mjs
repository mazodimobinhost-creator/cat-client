/**
 * «منابع اسکن» — the scanner's sources card (app side).
 *
 * The owner's two complaints: «فک کنم منبع نداره اسکن کنه» and «داخل اسکنر ای‌پی‌های
 * v6 نیست». The answer has two halves, and neither can be a claim:
 *
 *   * the KOTLIN half (what a run really walks) is pinned by JVM tests in
 *     `app/src/test/java/com/cat/client/IpScannerSourcesTest.kt`, which the Android
 *     CI job compiles and runs — no JDK in this sandbox, so they cannot run here;
 *   * the WIRING half (the card exists, the chips are real switches, the switches
 *     reach ScanOptions, and every string is translated in both languages) is
 *     pinned here, as source-level assertions, exactly like the other Kotlin-facing
 *     suites in this folder.
 *
 * A card that renders numbers from a different list than the scan walks would be
 * worse than no card, so the assertions below tie the two together.
 *
 * Usage: node scripts/panels/scanner-sources.test.mjs
 */
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';

const root = new URL('../../', import.meta.url);
const read = (p) => readFileSync(new URL(p, root), 'utf8');
const ipScanner = read('app/src/main/java/com/cat/client/IpScanner.kt');
const activity = read('app/src/main/java/com/cat/client/MainActivity.kt');
const stringsEn = read('app/src/main/res/values/strings.xml');
const stringsFa = read('app/src/main/res/values-fa/strings.xml');
const jvmTest = read('app/src/test/java/com/cat/client/IpScannerSourcesTest.kt');

let pass = 0;
let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};
const has = (haystack, needle) => haystack.includes(needle);

console.log('\nScanner sources (app)\n');

/* ---------- 1) one list, two readers ---------- */
{
  ok('the four source keys exist in walk order',
    /val SCAN_SOURCE_KEYS: List<String> = listOf\("mine", "cloudflare", "ipv6", "iran"\)/.test(ipScanner));
  ok('the scan walks the SAME per-source lists the card reports from',
    /internal fun buildCandidateList\(options: ScanOptions\): List<String> \{[\s\S]{0,160}?sourceCandidates\(options\)\.values\.forEach \{ ips \+= it \}/
      .test(ipScanner),
    'buildCandidateList no longer derives from sourceCandidates');
  ok('sourceReport exists and is public (the UI reads it)',
    /fun sourceReport\(options: ScanOptions\): List<ScanSource>/.test(ipScanner));
  ok('the v6 drop rule covers every source, not just the range box',
    ipScanner.includes('if (!options.includeIpv6) {') &&
    /mine\.removeAll \{ isIpv6\(it\) \}[\s\S]{0,120}?cloudflare\.removeAll \{ isIpv6\(it\) \}[\s\S]{0,80}?ipv6\.clear\(\)[\s\S]{0,80}?iran\.removeAll \{ isIpv6\(it\) \}/.test(ipScanner));
  ok('v6 is derived from the v4 pool we already believe in (not a random /32 draw)',
    ipScanner.includes('v4Pool.forEach { v4 -> ipv6 += deriveIpv6Candidates(v4) }'));
}

/* ---------- 2) the card and its switches ---------- */
{
  ok('the card exists with a title, a hint, a chip row and a summary line',
    activity.includes('R.string.scanner_sources_title') && activity.includes('R.string.scanner_sources_hint') &&
    activity.includes('scannerSourceRow') && activity.includes('scannerSourceSummary'));
  ok('every chip is at least 44 dp tall (touch target)',
    /minHeight = dp\(44\)/.test(activity));
  ok('a chip click persists the switch and redraws the counts',
    /setOnClickListener \{[\s\S]{0,700}?saveScannerSource\(key, next\)[\s\S]{0,700}?renderScannerSources\(\)/.test(activity));
  ok('the counts come from IpScanner.sourceReport, not from a hardcoded list',
    /val report = IpScanner\.sourceReport\(options\)/.test(activity));
  ok('the total is summed from sources that are really enabled',
    /val total = report\.filter \{ it\.enabled \}\.sumOf \{ it\.count \}/.test(activity));
  ok('the empty case is a loud warning, not a silent zero',
    activity.includes('R.string.scanner_sources_empty') && /ERROR else TEXT_SECONDARY/.test(activity));
  ok('the force-v6 escape hatch still exists, as a labelled switch (not a hidden side-effect)',
    activity.includes('R.string.scanner_force_v6') &&
    /setOnClickListener \{[\s\S]{0,240}?scannerForceV6 = !scannerForceV6[\s\S]{0,240}?putBoolean\("scanner_force_v6", scannerForceV6\)/.test(activity),
    'the only control that could force v6 disappeared with the old chip');
  ok('…and forcing it also switches the source on (no «forced but off» state)',
    /if \(scannerForceV6\) saveScannerSource\("ipv6", true\)/.test(activity));
  ok('editing the range box recomputes the counts live',
    /afterTextChanged\(editable: android\.text\.Editable\?\) \{\s*renderScannerSources\(\)/.test(activity));
}

/* ---------- 3) the switches reach the scan ---------- */
{
  ok('the scan reads the cloudflare switch instead of hardcoding true',
    activity.includes('includeBuiltin = scannerSourceEnabled("cloudflare")'));
  ok('…and the Iran/community switch too',
    activity.includes('includeIranLibrary = scannerSourceEnabled("iran")'));
  ok('the v6 gate is the switch AND the probe (both must allow it)',
    /val ipv6 = \(scannerForceV6 \|\| withContext\(Dispatchers\.IO\) \{ IpScanner\.hasIpv6Connectivity\(\) \}\) &&\s*scannerSourceEnabled\("ipv6"\)/
      .test(activity));
  ok('switching v6 off also clears the old force flag (so the switch is not a lie)',
    /if \(key == "ipv6" && !next\) \{[\s\S]{0,200}?scannerForceV6 = false/.test(activity));
  ok('the status line names all four verdicts, including «you switched it off»',
    activity.includes('R.string.scanner_ipv6_off') && activity.includes('R.string.scanner_ipv6_forced') &&
    activity.includes('R.string.scanner_ipv6_detected') && activity.includes('R.string.scanner_ipv6_absent'));
  ok('the verdict prefers the switch over the probe (a switched-off source is never called «no v6 on this network»)',
    /!scannerSourceEnabled\("ipv6"\) -> R\.string\.scanner_ipv6_off[\s\S]{0,120}?scannerForceV6 -> R\.string\.scanner_ipv6_forced/.test(activity));
}

/* ---------- 4) every new string is in both languages ---------- */
{
  const keys = ['scanner_sources_title', 'scanner_sources_hint', 'scanner_src_mine', 'scanner_src_cloudflare',
    'scanner_src_iran', 'scanner_src_ipv6', 'scanner_sources_total', 'scanner_sources_empty', 'scanner_ipv6_off'];
  const missing = keys.filter((k) => !new RegExp(`<string name="${k}"`).test(stringsEn) || !new RegExp(`<string name="${k}"`).test(stringsFa));
  ok('all 9 new keys are translated in en + fa', missing.length === 0, missing.join(', '));
  const placeholders = /<string name="scanner_sources_total">([^<]*)<\/string>/;
  ok('the total string keeps both numbers (%1$d sources, %2$d candidates)',
    /%1\$d/.test(stringsEn.match(placeholders)[1]) && /%2\$d/.test(stringsEn.match(placeholders)[1]),
    stringsEn.match(placeholders)[1]);
  const faTotal = stringsFa.match(/<string name="scanner_sources_total">([^<]*)<\/string>/)[1];
  ok('…and the Persian one too (same placeholders, or Android throws)', /%1\$d/.test(faTotal) && /%2\$d/.test(faTotal), faTotal);
  ok('no string was left untranslated as an empty label',
    !/<string name="scanner_src_[a-z0-9_]+"><\/string>/.test(stringsEn) && !/<string name="scanner_src_[a-z0-9_]+"><\/string>/.test(stringsFa));
}

/* ---------- 5) the Kotlin half is really going to run in CI ---------- */
{
  ok('the JVM suite exists next to the other scanner tests',
    jvmTest.includes('class IpScannerSourcesTest'));
  ok('…and it pins the two complaints (no source / no v6)',
    jvmTest.includes('reportHasTheFourSourcesInWalkOrder') &&
    jvmTest.includes('ipv6IsFullyOffWhenTheGateSaysNo') &&
    jvmTest.includes('theUnionOfSourcesIsExactlyTheCandidateList'));
  const workflow = read('.github/workflows/android.yml');
  ok('the Android CI job runs the JVM tests (app/src/test is the default source set)',
    /Unit tests \(JVM\)|testDebugUnitTest|:app:test/.test(workflow));
}

console.log('\nscanner sources: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
