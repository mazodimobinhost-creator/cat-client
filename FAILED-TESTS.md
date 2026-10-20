### npm test failure excerpt
```
# ✗ ech=1 links keep real worker host as inner SNI (no custom SNI spoof) — vless://11111111-2222-4333-8444-555555555555@www.speedtest.net:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=
# ✓ xray + ech=1 never stacks fragment outbound or finalmask on ECH
# ✗ 1 FAILED
# Subtest: scripts/panels/ech.test.mjs
not ok 18 - scripts/panels/ech.test.mjs
  ---
  duration_ms: 164.511482
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/ech.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# Emergency bridge / dnstt (panel 6.67.6)
# (node:2606) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
# Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
# To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
# (Use `node --trace-warnings ...` to show where the warning was created)
# Not implemented: Window's scrollTo() method
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
