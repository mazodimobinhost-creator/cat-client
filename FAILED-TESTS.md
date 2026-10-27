### npm test failure excerpt
```
#   diff: 'simple'
# }
# Node.js v22.23.4
# Subtest: scripts/panels/proxy-vip.test.mjs
not ok 37 - scripts/panels/proxy-vip.test.mjs
  ---
  duration_ms: 2780.790811
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/proxy-vip.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# ✓ syntax check passed
# (node:2843) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?dp=base is not specified and it doesn't parse as CommonJS.
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
