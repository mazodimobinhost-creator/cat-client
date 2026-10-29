// Guard for the tunnel section: the entry hop, and a scanned IP as a leg of a chain.
//
// What this test is and what it is not (honest label, same convention as
// docs/tests-and-labels.md):
//   * it reads the Kotlin sources — it proves the SHAPE of the wiring, not its
//     behaviour on a phone. Behaviour needs the Android build (CI) and a device.
//   * the claims it protects are the ones that broke silently before:
//       1. a chain hop can carry a pinned address, and the planner patches only the
//          leg that is dialled from OUTSIDE the hop (proxyIndex 0) — patching the
//          other blocks would strip the address a chained config needs upstream;
//       2. the store round-trips the entry slot (it used to force `before = off` on
//          save and ignore it on read, so an entry hop could never exist);
//       3. the settings page renders a row for every slot, and the restore path no
//          longer refuses the entry slot;
//       4. the ProxyIP scanner meets the chain the user built: with an active chain
//          the scanned IP is pinned as a leg, and the chain is left as it was
//          (the default the owner chose), instead of silently fronting;
//       5. both locales carry the new strings with matching placeholders.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readMainActivity } from './main-activity-source.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');

const chain = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'ConnectionChain.kt');
const activity = readMainActivity();
const en = read('app', 'src', 'main', 'res', 'values', 'strings.xml');
const fa = read('app', 'src', 'main', 'res', 'values-fa', 'strings.xml');

const hopClass = chain.slice(
  chain.indexOf('data class ConnectionChainHop('),
  chain.indexOf('data class ConnectionChainSettings('),
);
// render() sits after the preference store's own companion object, so the end marker has
// to be searched for AFTER the start — otherwise the slice is empty and every assertion
// about it passes on nothing.
const renderStart = chain.indexOf('    fun render(');
const renderEnd = chain.indexOf('    private companion object {', renderStart);
assert.ok(renderStart > 0 && renderEnd > renderStart, 'could not locate MihomoProxyBlock.render');
const render = chain.slice(renderStart, renderEnd);
const planner = chain.slice(
  chain.indexOf('    private fun toRuntimePlan('),
  chain.indexOf('    private fun resolveProxyChain('),
);
const vpn = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'CatClientVpnService.kt');
const store = chain.slice(
  chain.indexOf('class ConnectionChainPreferenceStore'),
  chain.indexOf('data class ConnectionChainSource('),
);

test('a hop carries a pinned address, and only while it is on', () => {
  assert.match(hopClass, /val serverOverride: String\? = null/, 'the hop lost its pinned address');
  assert.match(
    hopClass,
    /fun fixed\(\s*subscriptionId: String,\s*fingerprint: String,\s*serverOverride: String\? = null,/,
    'fixed() no longer takes the pinned address',
  );
  const normalized = chain.slice(
    chain.indexOf('    private fun ConnectionChainHop.normalized('),
    chain.indexOf('class ConnectionChainPreferenceStore'),
  );
  assert.match(normalized, /if \(mode == ConnectionChainHopMode.Off\)/, 'an off hop must drop the pin');
  assert.match(
    normalized,
    /serverOverride = serverOverride\?\.trim\(\)\?\.takeIf/,
    'a stored pin is no longer trimmed/validated on the way in',
  );
  assert.match(
    normalized,
    /connectionChainEndpointOrNull\(value\) != null/,
    'an unparseable pin would reach the runtime and break a connection',
  );
});

test('the render patches one address, and refuses to pretend it patched one', () => {
  assert.match(render, /serverOverride: String\? = null,/, 'render() lost the pin parameter');
  assert.match(render, /connectionChainReplaceField\(line, "server", pinned\.ip\)/, 'server is not patched');
  assert.match(render, /connectionChainReplaceField\(line, "port", pinned\.port\.toString\(\)\)/, 'port is not patched');
  assert.match(render, /connectionChainIsInlineProxyMap\(line\)/, 'inline `- { … }` proxies are not handled');
  assert.match(
    render,
    /if \(!serverReplaced && pinned != null\) \{\s*throw IOException/,
    'a pin that could not be applied would be silently ignored',
  );
  assert.match(render, /if \(!nameReplaced\) throw IOException/, 'the pre-existing name guard disappeared');
});

test('only the leg dialled from outside the hop takes the pin', () => {
  assert.match(
    planner,
    /val pinned = hop\.serverOverride\.takeIf \{ proxyIndex == 0 \}/,
    'the pin is applied to the wrong blocks of a hop',
  );
  assert.match(
    planner,
    /serverOverride = pinned,/,
    'the resolved hop no longer reports its pin',
  );
  assert.match(
    planner,
    /proxy\.block\.render\(proxy\.runtimeName, dialerProxy, pinned\)/,
    'the planner stopped handing the pin to render()',
  );
  assert.match(
    chain,
    /\.map \{ combination -> toRuntimePlan\(combination, normalized,/,
    'plans() stopped passing the settings (the pins) into the plan builder',
  );
});

test('the entry slot survives the store', () => {
  assert.ok(
    !/settings\.copy\(before = ConnectionChainHop\.off\(\)\)/.test(store),
    'save() still forces the entry hop off — an entry can never exist',
  );
  assert.match(store, /before = root\.optJSONObject\("before"\)\.toHop\(\)/, 'read() still drops the entry hop');
  assert.match(
    chain,
    /serverOverride\?\.let \{ pinned -> put\("serverOverride", pinned\) \}/,
    'the pinned address is not persisted',
  );
  assert.match(
    chain,
    /val pinned = optString\("serverOverride"\)\.takeIf\(String::isNotBlank\)/,
    'the pinned address is not read back',
  );
  assert.match(
    chain,
    /internal fun ConnectionChainSettings\.firstConfiguredSlot\(\)/,
    'the helper that names the first configured slot disappeared',
  );
});

test('the settings page can show and edit every slot', () => {
  assert.match(
    activity,
    /ConnectionChainSlot\.entries\.forEachIndexed \{ index, slot ->/,
    'the page renders only some slots again',
  );
  assert.match(
    activity,
    /ConnectionChainSlot\.entries\s*\n\s*\.map \{ slot -> settings\.hop\(slot\) \}/,
    'the fixed-source list ignores the entry slot again',
  );
  assert.ok(
    !/if \(chainSlot != ConnectionChainSlot\.Before\)/.test(activity),
    'the restore path refuses to reopen the entry picker',
  );
  assert.match(activity, /private fun editConnectionChainHopIp\(/, 'no way to edit a hop address');
  assert.match(
    activity,
    /R\.string\.connection_chain_hop_ip_chip/,
    'the hop row no longer shows the address chip',
  );
  assert.match(activity, /minimumHeight = dp\(44\)/, 'the chip lost its touch target');
});

test('the scanner meets the chain instead of overwriting it', () => {
  const hook = activity.slice(
    activity.indexOf('private fun applyProxyIpAsEntry('),
    activity.indexOf('private fun applySniOverride('),
  );
  assert.match(hook, /val chain = connectionChainPreferenceStore\.read\(\)/, 'the scanner ignores the chain');
  assert.match(hook, /if \(chain\.isActive && entrySlot != null\)/, 'the chain branch is gone');
  assert.match(
    hook,
    /withHop\(entrySlot, chain\.hop\(entrySlot\)\.copy\(serverOverride = pinned\)\)/,
    'the scanned IP is not pinned onto the chain',
  );
  assert.match(hook, /R\.string\.scanner_applied_chain/, 'the toast no longer says what happened');
  assert.match(
    hook,
    /val previousValue = frontingIpPreferenceStore\.readFrontingIp\(\)/,
    'the fronting path (chains off) must stay',
  );
});

test('the connection details report the pinned address, not the config it replaced', () => {
  // The endpoint readout is how a user checks «which IP am I actually going through».
  // Reporting the subscription's address while the socket dials the pin would be a lie
  // that looks like a working tunnel.
  // Start at the candidate line: `val finalPin` sits just above the log call, so a slice
  // anchored on the log string would miss the very line this test is about.
  const start = vpn.indexOf('val finalCandidate = plan.finalHop.candidate');
  assert.ok(start > 0, 'could not locate the chain health path in CatClientVpnService.kt');
  const healthy = vpn.slice(start, vpn.indexOf('} catch (error: Throwable) {', start));
  assert.match(healthy, /val finalPin = plan\.finalHop\.serverOverride\?\.let\(::connectionChainEndpointOrNull\)/,
    'the runtime no longer resolves the final hop pin');
  assert.match(healthy, /ip = finalPin\?\.ip \?: finalCandidate\.profile\.server/,
    'the reported IP ignores the pin');
  assert.match(healthy, /port = finalPin\?\.port \?: finalCandidate\.profile\.port/,
    'the reported port ignores the pin');
  assert.match(healthy, /pinned=\$\{plan\.hops\.count \{ it\.serverOverride != null \}\}/,
    'the healthy log line no longer says how many legs are pinned');
});

test('both locales carry the new strings with the same placeholders', () => {
  const names = [
    'connection_chain_hop_ip_chip',
    'connection_chain_hop_ip_value',
    'connection_chain_hop_ip_action',
    'connection_chain_hop_ip_title',
    'connection_chain_hop_ip_hint',
    'connection_chain_hop_ip_detail',
    'connection_chain_hop_ip_save',
    'connection_chain_hop_ip_clear',
    'connection_chain_hop_ip_invalid',
    'scanner_applied_chain',
  ];
  for (const name of names) {
    const enMatch = en.match(new RegExp(`<string name="${name}">([^<]*)</string>`));
    const faMatch = fa.match(new RegExp(`<string name="${name}">([^<]*)</string>`));
    assert.ok(enMatch, `values/strings.xml is missing ${name}`);
    assert.ok(faMatch, `values-fa/strings.xml is missing ${name}`);
    const placeholders = (value) => (value.match(/%\d\$s/g) || []).sort().join(',');
    assert.equal(
      placeholders(enMatch[1]),
      placeholders(faMatch[1]),
      `${name}: the two locales disagree about its placeholders`,
    );
  }
});
