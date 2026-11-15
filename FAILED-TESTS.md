### npm test failure excerpt
```
  duration_ms: 0.531577
  type: 'test'
  ...
# Subtest: panel repair resolves its Worker from the prerelease list too
not ok 9 - panel repair resolves its Worker from the prerelease list too
  ---
  duration_ms: 0.6324
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/app-prerelease-update.test.mjs:10:1'
  failureType: 'testCodeFailure'
  error: |-
    The input did not match the regular expression /readText\(RELEASES_URL/. Input:
    
    'suspend fun releaseAsset(name: String, maxBytes: Int): String {\n' +
      '        if (!name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {\n' +
      '            throw IOException("GitHub asset name is invalid")\n' +
      '        }\n' +
      "        // GitHub's /releases/latest/download excludes pre-releases, exactly like\n" +
      '        // /releases/latest. Resolve the asset from the newest non-draft release\n' +
      '        // first, otherwise the repair/update buttons silently reinstall an old\n' +
      '        // Worker even though the APK itself is current. The list URL itself is\n' +
--
    Test.run (node:internal/test_runner/test:1101:12)
    async startSubtestAfterBootstrap (node:internal/test_runner/harness:296:3)
  ...
# Subtest: drafts are rejected while signed prereleases can be selected
not ok 10 - drafts are rejected while signed prereleases can be selected
  ---
  duration_ms: 0.190028
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/app-prerelease-update.test.mjs:17:1'
  failureType: 'testCodeFailure'
  error: |-
    The input did not match the regular expression /getBoolean\("draft"\)/. Input:
    
    'fun parseRelease(json: String, variant: ApkVariant?): AppRelease {\n' +
      '        try {\n' +
      '            val response = JSONObject(json)\n' +
      '            if (response.optBoolean("draft", false)) {\n' +
      '                throw IOException("GitHub release is a draft")\n' +
      '            }\n' +
      '            val tag = response.getString("tag_name")\n' +
      '            val version = AppUpdatePolicy.normalizedVersion(tag)\n' +
--
#   ✓ deploy workflow builds the current source into Wrangler’s actual entrypoint
#   ✓ deploy workflow checks live /api/version against the source marker
# PANEL UPDATE CONTRACT FAILED (1)
# Subtest: scripts/panels/panel-update-contract.test.mjs
not ok 52 - scripts/panels/panel-update-contract.test.mjs
  ---
  duration_ms: 2180.280597
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/panel-update-contract.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# Not implemented: Window's scrollTo() method
# (node:2919) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
# Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
# To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
# (Use `node --trace-warnings ...` to show where the warning was created)
# Rendered panel (jsdom) — error-card buttons + relay warning
--
#   diff: 'simple'
# }
# Node.js v22.23.4
# Subtest: scripts/panels/paste-artifact.test.mjs
not ok 55 - scripts/panels/paste-artifact.test.mjs
  ---
  duration_ms: 71.264041
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# Not implemented: Window's scrollTo() method
# (node:2951) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
# Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
# To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
# (Use `node --trace-warnings ...` to show where the warning was created)
# ✓ page script runs without errors
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
FAIL panel-update-contract
```
artifact: /tmp/cat-contract-Axj3gA/artifact.js (911402 bytes)
  ✓ PanelUpdate.kt still defines PANEL_VERSION_MARKER
  ✓ deploy-bot still reads the marker with a regex literal
  ✓ wizard still builds its marker regex as «marker + \s*=\s*'([^']+)'»
  ✓ app (PanelUpdate.kt) finds a version in the shipped artifact
  ✓ deploy-bot finds a version in the shipped artifact
  ✓ wizard finds a version in the shipped artifact
  ✓ all consumers agree on one version
  ✓ /api/version of the artifact reports panel=cat-panel (what deployedVersion() requires)
  ✓ marker equals the version the running artifact reports
  ✓ marker equals the readable source version
  ✓ the panel's own update-check reads the shipped artifact's version line
  ✓ marker is the very first line (survives a truncated download)
  ✓ artifact passes PanelUpdate's module check (contains «export default»)
  ✗ normal minified artifact stays below 910 K characters  → 911402
  ✓ PanelUpdate has isDowngrade()
  ✓ PanelUpdate gates the repository fallback (acceptRepositoryFallback)
  ✓ PanelUpdate no longer treats source readability as an Error 1101 detector
  ✓ CloudflareWorker.updateBuiltIn refuses a downgrade
  ✓ MainActivity has the shared blockPanelDowngrade() built on isDowngrade
  ✓ «Update panel» dialog blocks a downgrade before anything else
  ✓ «Manage panel» dialog blocks a downgrade before copying/uploading
  ✓ «Manage panel» no longer calls an OLDER source an update (was `newest.version != deployed`)
  ✓ Wrangler configured entrypoint is one of the two built deploy artifacts
  ✓ deploy workflow builds the current source into Wrangler’s actual entrypoint
  ✓ deploy workflow checks live /api/version against the source marker

PANEL UPDATE CONTRACT FAILED (1)
```
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
  ✓ the asset declares the same version as the worker source
  ✗ raw size is in the paste range (300–970 KB)  → 970365
  ✓ gzip stays under the 295 KB regression budget
  ✓ UTF-8 round-trips without losing a character
  ✓ import("cloudflare:sockets") stays a literal string argument for workerd
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

paste artifact: 21 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:204:8
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

Node.js v22.23.4
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
PASS devcop-parity
PASS bestping-custom
