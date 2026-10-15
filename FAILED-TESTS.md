# Unit test failure report (bbb2fde)

    - java.lang.AssertionError: expected: java.lang.Integer&lt;100&gt; but was: java.lang.Long&lt;100&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected: java.lang.Integer&lt;100&gt; but was: java.lang.Long&lt;100&gt;
    - java.lang.AssertionError: expected: java.lang.Integer&lt;20&gt; but was: java.lang.Long&lt;20&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected: java.lang.Integer&lt;20&gt; but was: java.lang.Long&lt;20&gt;

## files with compile errors
## log tail
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes

> Task :app:testDebugUnitTest

SmartRouteHistoryTest > aggregatesSuccessLatencyAndFailureStreak FAILED
    java.lang.AssertionError at SmartRouteHistoryTest.kt:20

SmartRouteHistoryTest > boundsHistoryToRecentSamples FAILED
    java.lang.AssertionError at SmartRouteHistoryTest.kt:30

132 tests completed, 2 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/cat-client/cat-client/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 6s
24 actionable tasks: 6 executed, 18 up-to-date
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
  ✗ the asset declares the same version as the worker source  → 6.67.6 vs 6.68.0
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy still uploads the obfuscated bundle (never the paste file)
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …right after the minify step, i.e. before the in-place obfuscation

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
  ✗ the asset declares the same version as the worker source  → 6.67.6 vs 6.68.0
  ✓ raw size is in the paste range (300–600 KB)
  ✓ gzip stays under the 155 KB warning line
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy still uploads the obfuscated bundle (never the paste file)
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …right after the minify step, i.e. before the in-place obfuscation

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
