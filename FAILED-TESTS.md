### npm test failure excerpt
```
# ✓ Dark/DraculaPro: textSecondary >= 4.5:1 on every surface
# 50 CONTRAST CHECK(S) FAILED
# ✗ Dark/DraculaPro: textTertiary >= 4.5:1 on every surface — 3.90
# Subtest: scripts/panels/android-contrast.test.mjs
not ok 3 - scripts/panels/android-contrast.test.mjs
  ---
  duration_ms: 50.046615
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/android-contrast.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# Subtest: guard fails on the exact beta77 crash shape (prefs + view in initializers)
ok 5 - guard fails on the exact beta77 crash shape (prefs + view in initializers)
  ---
  duration_ms: 55.176877
  type: 'test'
  ...
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
