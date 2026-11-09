### npm test failure excerpt
```
  duration_ms: 1.022383
  type: 'test'
  ...
# Subtest: panel repair resolves its Worker from the prerelease list too
not ok 9 - panel repair resolves its Worker from the prerelease list too
  ---
  duration_ms: 1.097304
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
