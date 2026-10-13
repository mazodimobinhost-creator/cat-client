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
FAIL cat-panel
```
✓ syntax check passed
✓ /health anonymous = bare ok (no fingerprint)
✓ / serves camouflage when locked
✓ /panel shows login when locked
✓ /api/settings 401 without session
(node:2701) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
✓ /sub/<uuid> 200
✓ has vless + trojan
✓ BPB-style remark
✓ first entry is TLS 443
✓ plain ports default include :80
✗ has BPB-signature :8080 plain entries (443 → 8080 order)
file:///home/runner/work/cat-client/cat-client/scripts/panels/cat-panel.test.mjs:108
  check(':8080 remark is BPB-style «Clean IP : 8080»', decodeURIComponent(l8080.split('#')[1]).includes('Clean IP : 8080'), decodeURIComponent(l8080.split('#')[1]));
                                                                                ^

TypeError: Cannot read properties of undefined (reading 'split')
    at file:///home/runner/work/cat-client/cat-client/scripts/panels/cat-panel.test.mjs:108:81

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
(node:2998) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
FAIL cat-panel
```
✓ Clash rejects an Xray-only firewall preset instead of silently dropping finalmask
✓ sing-box rejects an Xray-only firewall preset instead of silently dropping finalmask
✓ firewall presets are per-link and do not persist into panel settings
✓ v6: 2606:4700 endpoints are recognized CF IPs
✓ v6: defaults include IPv6 endpoints
✓ v6: sub emits bracketed IPv6 endpoint link
✓ svc: host list now covers Claude + AI Studio
✓ svc: 404 on an API root counts as reachable (open)
✓ panel: v6 accepted hint + svc exit chip handler
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

4 FAILED
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
FAIL cat-panel
```
✓ v6: sub emits bracketed IPv6 endpoint link
✓ svc: host list now covers Claude + AI Studio
✓ svc: 404 on an API root counts as reachable (open)
✓ panel: v6 accepted hint + svc exit chip handler
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

5 FAILED
- owner ips first — vless://11111111-2222-4333-8444-555555555555@172.64.151.179:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2560&sni=catpanel-demo.workers.dev&fp=chrome&alpn=http%2F1.1&cs=TLS_AES_256_GCM_SHA384%3ATLS_CHACHA20_POLY1305_SHA256%3ATLS_AES_128_GCM_SHA256%3ATLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256%3ATLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256&fm=%7B%22tcp%22%3A%5B%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%22tlshello%22%2C%22lengths%22%3A%5B%220-6%22%2C%22100-140%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%2C%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%221-3%22%2C%22lengths%22%3A%5B%2280-160%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%5D%7D#%F0%9F%92%A6%201.%20VLESS%20-%20Clean%20IP%20TLS%20%3A%20443
- preferred country entries come first with flag names
- other countries follow as fallback
- clash has url-test group per country
- pinned fixed IP leads EVERY fetch-rotated sub
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
- manual domain kept as address
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
PASS paste-artifact
PASS secrets-tokens
PASS quicklink-version
