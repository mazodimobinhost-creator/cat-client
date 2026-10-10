FAIL cat-panel
```
✓ syntax check passed
✓ /health anonymous = bare ok (no fingerprint)
✓ / serves camouflage when locked
✓ /panel shows login when locked
✓ /api/settings 401 without session
(node:2468) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ login wrong → 401
✓ login with UUID ok
✓ manual v4 leads the sub
✓ manual v6 bracketed in link
✓ manual domain kept as address
✓ pinned v4 emitted on its verified port only
✓ pinned v6 emitted bracketed on 8443
✓ cronSelfCheck: KV ping stored
✓ cc-quality anonymous → 401
✓ cc-quality owner → ok shape
✓ cronCountryQuality: no sockets → survives
✓ domain-check anonymous → 401
✓ domain-check: CF domain detected
✓ built-in v6 pool exported (11 anycast)
✓ /panel shows panel with cookie
✓ settings GET
✓ Clean-IP auto-append defaults on, migrates legacy implicit false, and keeps an explicit opt-out
✓ /sub/<uuid> 200
✓ has vless + trojan
✓ BPB-style remark
✓ first entry is TLS 443
✓ plain ports default include :80
✗ has BPB-signature :8080 plain entries (443 → 8080 order)
file:///home/runner/work/cat-client/cat-client/scripts/panels/cat-panel.test.mjs:110
  check(':8080 remark is BPB-style «Clean IP : 8080»', decodeURIComponent(l8080.split('#')[1]).includes('Clean IP : 8080'), decodeURIComponent(l8080.split('#')[1]));
                                                                                ^

TypeError: Cannot read properties of undefined (reading 'split')
    at file:///home/runner/work/cat-client/cat-client/scripts/panels/cat-panel.test.mjs:110:81

Node.js v22.23.3
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
FAIL survival
```
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:2762) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ preset: daily rotation
✓ preset: safe SNI = Host (spoofing/rotation off)
✓ preset: fragment on
✓ preset: plain ports on
✓ preset: both protocols
✓ preset: defaults + host + health ordering
✓ preset is idempotent (nothing left to change)
✓ survival without apply → 400
✓ settings dialed back (test setup)
✓ seed: 3 ips with provenance latency
✓ baseline sub: plain configs OFF (as configured)
✓ baseline sub: vless OFF, trojan only (as configured)
✓ baseline sub: original panel order
✗ survive: plain ports forced back on
✓ survive: both protocols forced back on
✓ survive: SNI pinned to worker Host (not spoofed)
✓ survive: scanner-proven fast address stays first
✓ survive applies to Base64 format
✓ survive applies to Clash/Mihomo format
✓ survive applies to sing-box format
✓ survive applies to Xray format
✓ survive render did NOT persist anything
✓ health ordering: 40ms leads, 120ms next, unknown last
✓ health ordering expires stale scans after 7 days
✓ health ordering carries domain Ping through DNS-to-IP mapping
✓ telemetry: users carry lastOnline (panel shows «never connected»)
```
