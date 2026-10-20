### npm test failure excerpt
```
  duration_ms: 1.200374
  type: 'test'
  ...
# Subtest: the scanner meets the chain instead of overwriting it
not ok 41 - the scanner meets the chain instead of overwriting it
  ---
  duration_ms: 0.884352
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/chain-tunnel.test.mjs:162:1'
  failureType: 'testCodeFailure'
  error: 'the scanner ignores the chain'
  code: 'ERR_ASSERTION'
  name: 'AssertionError'
  expected:
  actual: |-
    private fun applyProxyIpAsEntry(ip: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("proxyip", ip))
            val nowMs = System.currentTimeMillis()
            val parsed = VerifiedEndpointParser.parse(
                raw = ip,
```
PASS cat-panel
PASS ccq
PASS ech
PASS panel-dom
PASS wizard
PASS multipart-upload
PASS chain-protocols
PASS android-guard
PASS link-shape
PASS panel-integrity
PASS kv-persistence
PASS panel-update-contract
PASS panel-selfupdate
PASS sni-host
PASS proxyip-sub
PASS proxyip-dataplane
PASS addr-port-query
PASS proxyip-feeds
PASS browser-probe
PASS ws-probe
PASS survival
PASS hostile-env
PASS content-type
PASS paste-artifact
PASS secrets-tokens
PASS quicklink-version
FAIL chain-tunnel
```
                    maxAgeMs = 86_400_000L,
                )
                if (rebuiltUri != null) {
                    val updated = userSubscriptionManager.replaceProfileShareLink(
                        subscriptionId = selectedSubId,
                        targetTag = targetProfile.tag,
                        updatedShareLink = rebuiltUri,
                        nowMs = nowMs,
                    )
                    if (updated != null) {
                        connectionProfiles = connectionProfiles.map { if (it.tag == targetProfile.tag) updated else it }
                        val selectedTypes = connectionSelectionPreferenceStore
                            .readAutomaticTypes(selectedSubId, connectionProfiles)
                            .ifEmpty { ConnectionTypeSelectionPolicy.availableTypes(connectionProfiles).toSet() }
                        handleConnectionSelected(updated, selectedTypes)
                        Toast.makeText(
                            this,
                            "🎯 ProxyIP applied: ${proxyEndpoint.authority} (${updated.displayTag})",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }
                }
            }
            Toast.makeText(this, "🎯 ProxyIP copied: $ip", Toast.LENGTH_LONG).show()
        }
    
        /** Put an SNI into the live configs (servername of every TLS proxy) and reconnect; null clears it. */
        
  operator: 'match'
  stack: |-
    TestContext.<anonymous> (file:///home/runner/work/cat-client/cat-client/scripts/panels/chain-tunnel.test.mjs:167:10)
    Test.runInAsyncScope (node:async_hooks:214:14)
    Test.run (node:internal/test_runner/test:1047:25)
    Test.processPendingSubtests (node:internal/test_runner/test:744:18)
    Test.postRun (node:internal/test_runner/test:1173:19)
    Test.run (node:internal/test_runner/test:1101:12)
    async Test.processPendingSubtests (node:internal/test_runner/test:744:7)
  ...
# Subtest: the connection details report the pinned address, not the config it replaced
ok 7 - the connection details report the pinned address, not the config it replaced
  ---
  duration_ms: 0.297446
  type: 'test'
  ...
# Subtest: both locales carry the new strings with the same placeholders
ok 8 - both locales carry the new strings with the same placeholders
  ---
  duration_ms: 1.176628
  type: 'test'
  ...
1..8
# tests 8
# suites 0
# pass 7
# fail 1
# cancelled 0
# skipped 0
# todo 0
# duration_ms 17.434642
```
PASS sni-lab
PASS builder-link-repro
