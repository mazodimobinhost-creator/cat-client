### npm test failure excerpt
```
  duration_ms: 27.391079
  type: 'test'
  ...
# Subtest: /api/ech-pool: names come back only if their HTTPS record has ECH now; DNS list marks DoH-capable resolvers
not ok 88 - /api/ech-pool: names come back only if their HTTPS record has ECH now; DNS list marks DoH-capable resolvers
  ---
  duration_ms: 11.323477
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/panel-680.test.mjs:64:1'
  failureType: 'testCodeFailure'
  error: '{"ok":true,"domains":[],"checked":12}'
  code: 'ERR_ASSERTION'
  name: 'AssertionError'
  expected: true
  actual: false
  operator: '=='
  stack: |-
    TestContext.<anonymous> (file:///home/runner/work/cat-client/cat-client/scripts/panels/panel-680.test.mjs:69:10)
    async Test.run (node:internal/test_runner/test:1054:7)
    async Test.processPendingSubtests (node:internal/test_runner/test:744:7)
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
