// The location setting meets the chain: which hop obeys it, what happens when it cannot be
// granted, and how the app says so.
//
// What this test is and what it is not (same label as docs/tests-and-labels.md):
//   * it READS the Kotlin — the planner's decision itself is executed in
//     app/src/test/java/com/cat/client/ConnectionChainTunnelTest.kt (JVM, run by CI). This file
//     guards the WIRING around it, which is exactly what was broken before: the request existed
//     in the app and was thrown away on the chain path, so «connect from Germany» did nothing.
//   * the claims it protects:
//       1. the service passes the user's country into the planner (it used to be null);
//       2. only the LAST configured slot is narrowed — the earlier hops are the road;
//       3. a wish must never kill the connection, but a fallback must never be silent either;
//       4. the flag shown to the user comes from the plan when it matched, and from the live
//          egress probe when it did not;
//       5. the picker offers what the chain can actually reach, and warns otherwise;
//       6. both locales carry the new string.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readMainActivity } from './main-activity-source.mjs';
import { readVpnService } from './vpn-service-source.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');

const vpn = readVpnService();
const chain = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'ConnectionChain.kt');
const activity = readMainActivity();
const en = read('app', 'src', 'main', 'res', 'values', 'strings.xml');
const fa = read('app', 'src', 'main', 'res', 'values-fa', 'strings.xml');

test('the request reaches the planner instead of being dropped', () => {
  assert.match(
    vpn,
    /connectWithConnectionChain\(\s*settings = chainSettings,[\s\S]{0,1400}?requestedExitCountryCode = locationPreferenceStore\.readSelectedCountryCode\(\),/,
    'the chain path no longer forwards the user\'s location choice',
  );
  assert.match(
    vpn,
    /connectWithConnectionChain\(\s*\n?\s*settings: ConnectionChainSettings,[\s\S]{0,400}requestedExitCountryCode: String\? = null,/,
    'the chain connect method lost its exit-country parameter',
  );
  assert.match(
    vpn,
    /ConnectionChainPlanner\.plans\(\s*\n?\s*settings,\s*\n?\s*sources,\s*\n?\s*delayRecords,\s*\n?\s*allowIpv6Literals = allowIpv6Literals,\s*\n?\s*requestedExitCountryCode = requestedExitCountryCode,/,
    'the planner is not told about the requested country',
  );
});

test('only the exit slot is narrowed, and an explicit pick still wins', () => {
  const start = chain.indexOf('val choices = configuredSlots.mapIndexed');
  assert.ok(start > 0, 'the per-slot choice narrowing is gone');
  const block = chain.slice(start, start + 1200);
  assert.match(block, /val isExitSlot = index == configuredSlots\.lastIndex/, 'every hop would obey the country');
  assert.match(block, /slotChoices\.filter \{ it\.countryCode == requestedExit \}\.ifEmpty \{ slotChoices \}/, 'the fallback to the unfiltered list disappeared');
  assert.match(block, /normalized\.hop\(slot\)\.mode == ConnectionChainHopMode\.Automatic/, 'a Fixed exit would be overridden by the country wish');
  // …and the plan reports the truth about what it did.
  assert.match(
    chain,
    /exitCountryMatched = requestedExitCountryCode == null \|\| exitCountry == requestedExitCountryCode/,
    'the plan stopped reporting whether the exit is the requested one',
  );
  assert.match(chain, /val exitCountryCode: String\? get\(\) = finalHop\.candidate\.countryCode/, 'the plan no longer reports where traffic leaves');
  assert.match(chain, /countryCode = ConnectionLocationPolicy\s*\n?\s*\.countryForProfile\(profile, Locale\.ENGLISH\)\s*\n?\s*\?\.code/, 'candidates lost their country');
});

test('a country that cannot be granted is logged, measured, and never fatal', () => {
  assert.match(vpn, /"mihomo\.chain\.exit",/, 'no log line states requested vs actual');
  assert.match(vpn, /"mihomo\.chain\.exit\.unavailable"/, 'a fallback is silent again');
  assert.match(
    vpn,
    /selectedCountryCode = requestedExitCountryCode\.takeIf \{ plan\.exitCountryMatched \}/,
    'the flag is no longer tied to what really happened',
  );
  // Staying null on a mismatch is what lets the live egress probe replace the flag.
  assert.match(
    vpn,
    /if \(startedRuntime\.selectedCountryCode == null\) \{\s*\n\s*refreshEgressCountryInBackground\(startedRuntime\)/,
    'the egress probe no longer runs — a wrong-country exit would show the requested flag',
  );
  const i = vpn.indexOf('if (!plan.exitCountryMatched)');
  assert.ok(i > 0, 'the mismatch branch is gone');
  // Only the branch itself: it must warn and continue, never throw or bail out of the attempt
  // loop (a country wish is a preference, not a precondition).
  const branch = vpn.slice(i, vpn.indexOf('return runtime.copy(', i));
  assert.ok(branch.length > 50 && branch.length < 800, 'the mismatch branch could not be isolated');
  assert.doesNotMatch(branch, /throw |return |break|continue/, 'a country wish can now kill the connection');
});

test('the picker offers what the chain can reach, and says when it cannot', () => {
  assert.match(activity, /private var chainExitCountryCodes: List<String>\? = null/, 'the chain\'s exit list is gone');
  assert.match(activity, /ConnectionChainPlanner\.exitCountryCodes\(\s*\n?\s*settings = settings,/, 'the list is not computed from the planner');
  assert.match(activity, /withContext\(Dispatchers\.IO\)[\s\S]{0,400}cachedConnectionChainSources\(\)/, 'the subscriptions are read on the main thread');
  assert.match(activity, /refreshChainExitCountries\(\)/, 'nothing refreshes the list');
  const dialog = activity.slice(
    activity.indexOf('private fun dialogLocationOptions()'),
    activity.indexOf('private fun handleLocationSelected('),
  );
  assert.match(dialog, /if \(chainCodes != null\)/, 'the dialog ignores the chain');
  assert.match(dialog, /addAll\(chainCodes\)/, 'the chain\'s countries are not offered');
  assert.match(dialog, /if \(stored != null && stored !in chainCodes\) add\(stored\)/, 'the stored choice would vanish from the list');
  assert.match(activity, /R\.string\.location_chain_unavailable/, 'picking an impossible country says nothing');
  for (const [name, xml] of [['en', en], ['fa', fa]]) {
    assert.match(xml, /<string name="location_chain_unavailable">/, `the warning is missing from the ${name} strings`);
  }
});

test('a Fixed exit slot is not narrowed, so its country still comes from the pick', () => {
  const start = chain.indexOf('fun exitCountryCodes(');
  assert.ok(start > 0, 'exitCountryCodes is gone');
  const helper = chain.slice(start, chain.indexOf('fun selectableFingerprints('));
  assert.match(helper, /if \(fixed != null && \(source\.subscriptionId != fixed\.subscriptionId/, 'a Fixed exit would list every country');
  assert.match(helper, /record\?\.status == ConnectionDelayStatus\.Failure/, 'a known-dead exit is still offered');
  assert.match(helper, /\}\.distinct\(\)\.sorted\(\)/, 'the codes are not a sorted distinct list');
});
