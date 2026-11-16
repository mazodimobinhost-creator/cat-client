### npm test failure excerpt
```
  duration_ms: 0.591454
  type: 'test'
  ...
# Subtest: panel repair resolves its Worker from the prerelease list too
not ok 9 - panel repair resolves its Worker from the prerelease list too
  ---
  duration_ms: 0.736084
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
  duration_ms: 0.224425
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
#   diff: 'simple'
# }
# Node.js v22.23.4
# Subtest: scripts/panels/proxy-vip.test.mjs
not ok 57 - scripts/panels/proxy-vip.test.mjs
  ---
  duration_ms: 1611.270454
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/proxy-vip.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# ✓ syntax check passed
# (node:2992) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?dp=base is not specified and it doesn't parse as CommonJS.
# Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
# To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
# (Use `node --trace-warnings ...` to show where the warning was created)
# ✓ baseline: a plain config to a Cloudflare-hosted target dials the FIRST relay of the panel list
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
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
PASS devcop-parity
PASS bestping-custom
