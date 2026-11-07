/**
 * ORBOT / TOR ENGINE (app side) — source-shape guard.
 *
 * The Kotlin half is compiled and executed by the Android CI job
 * (`app/src/test/java/com/cat/client/OrbotEngineTest.kt` runs the real normalizer, the real
 * YAML injection and a real TCP probe against a real local listener). What THIS file guards is
 * the wiring between the pieces, because every one of these lines is a place where a later
 * refactor could quietly turn «traffic goes through Tor» back into «the switch says so»:
 *
 *   1. the engine only accepts a LOOPBACK address (a remote address would hand the exit away);
 *   2. the injected outbound is a socks5 proxy with `udp: false` (Tor carries no UDP);
 *   3. the runtime builder switches the *effective* routing mode to the global-proxy shape, so
 *      no subscription rule can route DIRECT around the tunnel, and the DNS resolvers ride the
 *      same proxy;
 *   4. the service PROBES the loopback port before activating and logs the skip when Orbot is
 *      closed — an ON switch with a dead port must not look like a working tunnel;
 *   5. the UI row carries a live verdict and probes off the main thread.
 *
 * Usage: node scripts/panels/orbot-engine.test.mjs
 */
import { strict as assert } from 'node:assert';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readMainActivity } from './main-activity-source.mjs';
import { readVpnService } from './vpn-service-source.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.join(here, '../../app/src/main');
const read = (p) => readFileSync(path.join(app, p), 'utf8');
const root = path.join(here, '../..');
const readRoot = (p) => readFileSync(path.join(root, p), 'utf8');

let pass = 0;
let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};

console.log('\nOrbot / Tor engine — wiring\n');

const engine = read('java/com/cat/client/OrbotEngine.kt');
const runtime = read('java/com/cat/client/MihomoRuntime.kt');
const plan = read('java/com/cat/client/SessionPlan.kt');
const service = readVpnService();
const activity = readMainActivity();
const faStrings = read('res/values-fa/strings.xml');
const enStrings = read('res/values/strings.xml');

/* ---------- 1) the engine itself ---------- */
{
  ok('the engine exists as its own unit', engine.includes('object OrbotEnginePolicy'));
  ok('…with Orbot\'s SOCKS5 defaults (127.0.0.1:9050)',
    engine.includes('const val DEFAULT_HOST = "127.0.0.1"') && engine.includes('const val DEFAULT_PORT = 9050'));
  ok('…and a single proxy name that cannot collide with a subscription name',
    engine.includes('const val PROXY_NAME = "cat-orbot"'));
  ok('the address must be loopback (a remote SOCKS proxy would own the exit)',
    engine.includes('fun normalizeHost(') && /require\(loopback\)/.test(engine));
  ok('…and the port is validated as a real port', /require\(value in 1\.\.65535\)/.test(engine));
  ok('the injected outbound is socks5 with UDP off (Tor has no UDP relay)',
    /append\("    type: socks5\\n"\)/.test(engine) && /append\("    udp: false\\n"\)/.test(engine));
  ok('injection is idempotent (replace an entry with the same name, never duplicate it)',
    /fun injectProxy\(/.test(engine) && engine.includes('removeAt(existing)'));
  ok('the probe is a real TCP connect with a hard timeout, not a guess',
    engine.includes('fun probe(') && engine.includes('socket.connect(InetSocketAddress(host, port), timeoutMs)') &&
    engine.includes('DEFAULT_TIMEOUT_MS = 400'));
  ok('…and a failure keeps its reason for the log', /OrbotProbeResult\(false, null,/.test(engine));
  ok('the switch and its address are stored per install',
    engine.includes('class OrbotEnginePreferenceStore') && engine.includes('fun readActiveSettings()'));
  ok('readActiveSettings returns nothing while the switch is off',
    /if \(!isEnabled\(\)\) return null/.test(engine));
}

/* ---------- 2) the runtime shape ---------- */
{
  ok('the runtime builder takes the Orbot hop', /orbot: OrbotProxySettings\? = null,\n\s*\): String \{/.test(runtime));
  ok('…and forces the global-proxy shape while it is on',
    runtime.includes('RoutingMode.GlobalProxy else routingMode'));
  ok('…so the subscription rules are stripped in that shape',
    /val keysToReplace = if \(effectiveMode == RoutingMode.Subscription\)/.test(runtime));
  ok('…and the target is the Orbot proxy itself',
    /orbotTarget != null -> orbotTarget/.test(runtime));
  ok('the effective mode (not the stored one) decides which rules are written',
    runtime.includes('when (effectiveMode) {') && runtime.includes('val measureTarget = when (effectiveMode) {'));
  ok('DNS resolvers are pinned to the same proxy (no lookup leaks around Tor)',
    runtime.includes('val proxySuffix = dnsProxyGroup?.let { "#$it" }.orEmpty()'));
  ok('the proxy block is injected into the config the core reads',
    runtime.includes('OrbotEnginePolicy.injectProxy(base, host = orbot.host, port = orbot.port)'));
  ok('the plan and the runtime document carry the hop end to end',
    plan.includes('val orbot: OrbotProxySettings? = null') && plan.includes('orbot = preferences.orbot,') &&
    runtime.includes('val orbot: OrbotProxySettings? = null') && runtime.includes('orbot = document.orbot,'));
}

/* ---------- 3) the service decides honestly ---------- */
{
  ok('the service owns the engine store',
    service.includes('orbotEnginePreferenceStore = OrbotEnginePreferenceStore(this)'));
  ok('the hop is resolved once per attempt', service.includes('orbot = resolveOrbotHop(),'));
  {
    // The whole rule lives in one function: probe first, and a probe that did not answer must
    // end in `return null` (the normal path), never in a hop.
    const body = service.slice(
      service.indexOf('private suspend fun resolveOrbotHop()'),
      service.indexOf('private fun recordPathOutcome('),
    );
    const skippedAt = body.indexOf('orbot.engine.skipped');
    const nullAt = body.lastIndexOf('return null');
    ok('the probe runs off the main thread (the connect path is entered from Dispatchers.Main)',
    /withContext\(Dispatchers\.IO\) \{ OrbotProbe\.probe\(/.test(service) &&
    service.includes('private suspend fun resolveOrbotHop()') &&
    service.includes('private suspend fun captureSessionPlanPreferences('));
  ok('…and no socket is opened at all when neither the switch nor the watchdog can act',
    /if \(!switchOn && !pathWatchdogStore\.isArmed\(\)\) return null/.test(service));
  ok('…and a switch that is ON while Orbot is closed does NOT activate the path',
      /fun resolveOrbotHop\(\): OrbotProxySettings\?/.test(body) && skippedAt > 0 && nullAt > skippedAt,
      'skipped@' + skippedAt + ' null@' + nullAt);
  }
  ok('…it is reported instead of ignored', service.includes('"orbot.engine.skipped"'));
  ok('…and an active hop is logged with its latency and the shape it forced',
    service.includes('"orbot.engine.active"') && service.includes('routing=global'));
}

/* ---------- 4) the UI row ---------- */
{
  ok('the row lives in the settings section', activity.includes('getString(R.string.orbot_section)'));
  ok('the switch persists the choice and reconnects when a session is running',
    activity.includes('orbotEnginePreferenceStore.setEnabled(isChecked)') &&
    activity.includes('reconnectForConnectionOptionChange()'));
  ok('the probe button is a real 44 dp target', /orbotProbeButton[\s\S]{0,400}?minHeight = dp\(44\)/.test(activity));
  ok('the probe runs off the main thread (a hanging connect must not freeze the page)',
    activity.includes('kotlinx.coroutines.withContext(Dispatchers.IO) { OrbotProbe.probe(host, port) }'));
  ok('four verdicts exist, so «ON but nothing listens» cannot read as «on Tor»',
    activity.includes('R.string.orbot_off') && activity.includes('R.string.orbot_idle') &&
    activity.includes('R.string.orbot_ready') && activity.includes('R.string.orbot_absent'));
  ok('the row is rendered at build time, not only after a click', /renderOrbotRow\(\)\n/.test(activity));
}

/* ---------- 5) both languages, and the JVM test is in the source set ---------- */
{
  const keys = ['orbot_section', 'orbot_title', 'orbot_detail', 'orbot_off', 'orbot_idle', 'orbot_ready', 'orbot_absent', 'orbot_probe'];
  const missingEn = keys.filter((k) => !enStrings.includes('name="' + k + '"'));
  const missingFa = keys.filter((k) => !faStrings.includes('name="' + k + '"'));
  ok('all 8 strings exist in English', missingEn.length === 0, missingEn.join(','));
  ok('…and in Persian', missingFa.length === 0, missingFa.join(','));
  // Placeholders must match between languages or getString() crashes at runtime.
  const holders = (xml, k) => (xml.match(new RegExp('name="' + k + '">([^<]*)<'))?.[1].match(/%\\d\\$[sd]/g) || []).sort().join(',');
  ok('the placeholders agree across languages',
    keys.every((k) => holders(enStrings, k) === holders(faStrings, k)),
    keys.map((k) => k + ':' + holders(enStrings, k) + '/' + holders(faStrings, k)).join(' '));

  ok('the JVM test ships in the default unit-test source set',
    existsSync(path.join(app, '../test/java/com/cat/client/OrbotEngineTest.kt')));
  ok('…and it exercises the real probe against a real listener, not a mock',
    readRoot('app/src/test/java/com/cat/client/OrbotEngineTest.kt').includes('ServerSocket(0)'));
}

/* ---------- 6) the multi-path watchdog (item 4) ---------- */
{
  const watchdog = read('java/com/cat/client/PathWatchdog.kt');
  const watchdogTest = readRoot('app/src/test/java/com/cat/client/PathWatchdogTest.kt');

  ok('the watchdog is a pure decision engine (no clock, no Android I/O in the policy)',
    watchdog.includes('internal object PathWatchdog') && watchdog.includes('internal object PathWatchdogPolicy'));
  ok('…and both thresholds are constants, not magic numbers inline',
    watchdog.includes('const val FAILURES_BEFORE_SWITCH = 2') &&
    watchdog.includes('const val PRIMARY_RETRY_INTERVAL_MS'));
  ok('the fallback is never taken without a fallback that answers',
    watchdog.includes('if (!fallbackAvailable) return PathWatchdogDecision.Stay("no-fallback")'));
  ok('a disarmed watchdog never moves', watchdog.includes('if (!armed) return PathWatchdogDecision.Stay("disarmed")'));
  ok('a fallback that disappears sends the app back to the primary',
    watchdog.includes('ReturnToPrimary("fallback-unavailable")'));
  ok('the fallback is temporary (retry window), so it cannot become a one-way trip',
    watchdog.includes('ReturnToPrimary("primary-retry-window")'));
  ok('its state survives a process restart',
    watchdog.includes('class PathWatchdogStore') && watchdog.includes('fun readState()') &&
    watchdog.includes('fun saveState('));

  ok('the service consults it once per attempt', service.includes('PathWatchdog.decide('));
  ok('…records the primary-path outcome around the attempt itself',
    service.includes('val attemptUsedOrbot = preferences.orbot != null') &&
    service.includes('recordPathOutcome(') && /catch \(error: Throwable\) \{\n\s+recordPathOutcome\(/.test(service));
  ok('…and an attempt that rode Orbot is NOT counted as a primary outcome',
    /if \(usedOrbot\) return/.test(service));
  ok('an automatic switch is logged with the reason it happened',
    service.includes('reason=${decision.reason}') && service.includes('"orbot.watchdog.return"'));
  ok('the UI carries the second switch and its own verdict line',
    activity.includes('pathWatchdogStore.setArmed(isChecked)') &&
    activity.includes('R.string.watchdog_off') && activity.includes('R.string.watchdog_on_primary') &&
    activity.includes('R.string.watchdog_on_fallback'));
  ok('the JVM tests for the policy ship in the unit-test source set',
    existsSync(path.join(app, '../test/java/com/cat/client/PathWatchdogTest.kt')));

  const wKeys = ['watchdog_title', 'watchdog_detail', 'watchdog_off', 'watchdog_on_primary', 'watchdog_on_fallback'];
  ok('all 5 watchdog strings exist in English and Persian',
    wKeys.every((k) => enStrings.includes('name="' + k + '"') && faStrings.includes('name="' + k + '"')),
    wKeys.filter((k) => !enStrings.includes('name="' + k + '"') || !faStrings.includes('name="' + k + '"')).join(','));
  const holders = (xml, k) => (xml.match(new RegExp('name="' + k + '">([^<]*)<'))?.[1].match(/%\d\$[sd]/g) || []).sort().join(',');
  ok('…with matching placeholders in both languages',
    wKeys.every((k) => holders(enStrings, k) === holders(faStrings, k)),
    wKeys.map((k) => k + ':' + holders(enStrings, k) + '/' + holders(faStrings, k)).join(' '));
  ok('the policy tests cover the anti-flap rules (one failure is noise, one success resets)',
    watchdogTest.includes('oneFailureIsNoise') && watchdogTest.includes('oneSuccessResetsTheRun'));
}

console.log('\norbot engine: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
