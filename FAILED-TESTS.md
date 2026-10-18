# Android failure report (5a527b1)


## files with compile errors
## log tail
### kotlin-precheck.log

Welcome to Gradle 8.9!

Here are the highlights of this release:
 - Enhanced Error and Warning Messages
 - IDE Integration Improvements
 - Daemon JVM Information

For more details see https://docs.gradle.org/8.9/release-notes.html

Starting a Gradle Daemon (subsequent builds will be faster)
Daemon JVM discovery is an incubating feature.

> Configure project :app
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:113:41: Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:113:84: Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:142:9: 'jvmTarget: String' is deprecated. Please migrate to the compilerOptions DSL. More details are here: https://kotl.in/u1r8ln

> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig
> Task :app:generateDebugResValues
> Task :app:checkDebugAarMetadata
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:mergeDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest FAILED
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:30:9-54 Error:
	Attribute application@fullBackupContent value=(@xml/backup_rules) from AndroidManifest.xml:30:9-54
	is also present at [ca.psiphon:psiphontunnel:2.0.41] AndroidManifest.xml:6:18-88 value=(@xml/ca_psiphon_psiphontunnel_backup_rules).
	Suggestion: add 'tools:replace="android:fullBackupContent"' to <application> element at AndroidManifest.xml:26:5-164:19 to override.

See https://developer.android.com/r/studio-ui/build/manifest-merger for more information about the manifest merger.


FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:processDebugMainManifest'.
> Manifest merger failed : Attribute application@fullBackupContent value=(@xml/backup_rules) from AndroidManifest.xml:30:9-54
  	is also present at [ca.psiphon:psiphontunnel:2.0.41] AndroidManifest.xml:6:18-88 value=(@xml/ca_psiphon_psiphontunnel_backup_rules).
  	Suggestion: add 'tools:replace="android:fullBackupContent"' to <application> element at AndroidManifest.xml:26:5-164:19 to override.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 1m 20s
11 actionable tasks: 11 executed
FAIL cat-panel
```
✓ src: qos drops non-Cloudflare IPs and bare domains
✓ src: qos never empties the sub (list left intact when nothing is a CF IP)
✓ src: host serves only the worker hostname
✓ src: custom serves only ?addr=
✓ panel: entry-source picker, note box and src= link param
✓ latency: per-country P50/P95 sorted, dead IPs excluded
✓ panel: latency card container + title + neighbor badge + svc note
✓ panel: best-ms summary wired
✓ panel: inline script still parses
✓ neighbor: import ok with neighborIps
✓ neighbor: flagged IP gets src=neighbor
✓ neighbor: regular scanner IP keeps src=scanner
✓ neighbor: crown badge wired in UI
✓ stealth: one-time latch defaults panelPath=panel
✓ stealth: explicit path respected
✓ stealth: opting out stays off
✓ camo: zero fingerprint words
✓ camo: realistic page with decoy meta
✓ panel: manual-add card wired
✓ panel: inline script still parses (manual add)
✓ stealth: settings PUT ok
✓ stealth: / serves camouflage
✓ stealth: /panel serves the real login
✓ stealth: unknown path = neutral 404
✓ stealth: robots.txt quiet
✓ stealth: reset to legacy open
✓ manual: import ok
✓ manual: src=manual stored
✓ beta58: stealth on for routing tests
✓ beta58: /?ips= hops to the real panel path
✓ beta58: /?p= quick-login hops to /panel
✓ beta58: plain / still camouflaged
✓ beta58: camo hit nudges the repo pools
✓ beta58: sub hit nudges the repo pools
✓ beta58: stealth reset
✓ beta58: ?ips= never auto-imports
✓ beta59: free preset button wired
✓ beta59: ECH field note on the toggle
✓ beta59: inline script still parses
✓ dns cache: roundtrip
✓ dns cache: miss returns null
✓ cooldown: attempts listed before
✓ cooldown: relayAttempts still returns all (filtering happens at dial)
✓ cooldown: relayCool flags the failed one
✓ cooldown: direct dial never cools
✓ cooldown: socks relays cool too
✓ t.me socks: plain link parses
✓ t.me socks: auth link parses
✓ sub query tolerates &amp; links (Telegram copy)
✓ ext sub fetch sanitizes &amp;
✓ generated TLS configs put the worker host into the sni param (Cloudflare answers 403 on a mismatch)
✓ 30 addresses still yield plain-port entries (entry limit is shared between waves)
✓ wave rotation keeps address diversity (>= 12 distinct addresses)
✓ entry limit still respected with many addresses
✓ owner IPs lead a sub that also carries free library IPs
✓ free library IPs still appear, on :443, behind the owner
✓ ?norepo=1 still drops the library IPs

1 FAILED
- panel version is 6.69.0
```
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✗ the asset declares the same version as the worker source  → 6.69.0 vs 6.69.1
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
FAIL builder-link-repro
```
node:internal/modules/cjs/loader:1433
  throw err;
  ^

Error: Cannot find module '/home/runner/work/cat-client/cat-client/scripts/panels/builder-link-repro.test.mjs'
    at Function._resolveFilename (node:internal/modules/cjs/loader:1430:15)
    at defaultResolveImpl (node:internal/modules/cjs/loader:1040:19)
    at resolveForCJSWithHooks (node:internal/modules/cjs/loader:1045:22)
    at Function._load (node:internal/modules/cjs/loader:1216:25)
    at wrapModuleLoad (node:internal/modules/cjs/loader:254:19)
    at Function.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:171:5)
    at node:internal/main/run_main_module:36:49 {
  code: 'MODULE_NOT_FOUND',
  requireStack: []
}

Node.js v22.23.3
```
FAIL cat-panel
```
✓ src: qos never empties the sub (list left intact when nothing is a CF IP)
✓ src: host serves only the worker hostname
✓ src: custom serves only ?addr=
✓ panel: entry-source picker, note box and src= link param
✓ latency: per-country P50/P95 sorted, dead IPs excluded
✓ panel: latency card container + title + neighbor badge + svc note
✓ panel: best-ms summary wired
✓ panel: inline script still parses
✓ neighbor: import ok with neighborIps
✓ neighbor: flagged IP gets src=neighbor
✓ neighbor: regular scanner IP keeps src=scanner
✓ neighbor: crown badge wired in UI
✓ stealth: one-time latch defaults panelPath=panel
✓ stealth: explicit path respected
✓ stealth: opting out stays off
✓ camo: zero fingerprint words
✓ camo: realistic page with decoy meta
✓ panel: manual-add card wired
✓ panel: inline script still parses (manual add)
✓ stealth: settings PUT ok
✓ stealth: / serves camouflage
✓ stealth: /panel serves the real login
✓ stealth: unknown path = neutral 404
✓ stealth: robots.txt quiet
✓ stealth: reset to legacy open
✓ manual: import ok
✓ manual: src=manual stored
✓ beta58: stealth on for routing tests
✓ beta58: /?ips= hops to the real panel path
✓ beta58: /?p= quick-login hops to /panel
✓ beta58: plain / still camouflaged
✓ beta58: camo hit nudges the repo pools
✓ beta58: sub hit nudges the repo pools
✓ beta58: stealth reset
✓ beta58: ?ips= never auto-imports
✓ beta59: free preset button wired
✓ beta59: ECH field note on the toggle
✓ beta59: inline script still parses
✓ dns cache: roundtrip
✓ dns cache: miss returns null
✓ cooldown: attempts listed before
✓ cooldown: relayAttempts still returns all (filtering happens at dial)
✓ cooldown: relayCool flags the failed one
✓ cooldown: direct dial never cools
✓ cooldown: socks relays cool too
✓ t.me socks: plain link parses
✓ t.me socks: auth link parses
✓ sub query tolerates &amp; links (Telegram copy)
✓ ext sub fetch sanitizes &amp;
✓ generated TLS configs put the worker host into the sni param (Cloudflare answers 403 on a mismatch)
✓ 30 addresses still yield plain-port entries (entry limit is shared between waves)
✓ wave rotation keeps address diversity (>= 12 distinct addresses)
✓ entry limit still respected with many addresses
✓ owner IPs lead a sub that also carries free library IPs
✓ free library IPs still appear, on :443, behind the owner
✓ ?norepo=1 still drops the library IPs

2 FAILED
- manual domain kept as address
- panel version is 6.69.0
```
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✗ the asset declares the same version as the worker source  → 6.69.0 vs 6.69.2
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
FAIL builder-link-repro
```
node:internal/modules/cjs/loader:1433
  throw err;
  ^

Error: Cannot find module '/home/runner/work/cat-client/cat-client/scripts/panels/builder-link-repro.test.mjs'
    at Function._resolveFilename (node:internal/modules/cjs/loader:1430:15)
    at defaultResolveImpl (node:internal/modules/cjs/loader:1040:19)
    at resolveForCJSWithHooks (node:internal/modules/cjs/loader:1045:22)
    at Function._load (node:internal/modules/cjs/loader:1216:25)
    at wrapModuleLoad (node:internal/modules/cjs/loader:254:19)
    at Function.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:171:5)
    at node:internal/main/run_main_module:36:49 {
  code: 'MODULE_NOT_FOUND',
  requireStack: []
}

Node.js v22.23.3
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✗ the asset declares the same version as the worker source  → 6.69.0 vs 6.69.3
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✗ the asset declares the same version as the worker source  → 6.69.0 vs 6.69.4
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
