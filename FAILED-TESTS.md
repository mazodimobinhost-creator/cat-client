# Android failure report (3b337b4)


## files with compile errors
## log tail
FAIL cat-panel
```
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
✓ panel: manual-add card wired
✓ panel: inline script still parses (manual add)
✓ panel path: settings PUT ok
✓ panel path: / redirects transparently
✓ panel path: /panel serves the real login
✓ panel path: unknown path is ordinary 404
✓ stealth: robots.txt quiet
✓ stealth: reset to legacy open
✓ manual: import ok
✓ manual: src=manual stored
✓ beta58: stealth on for routing tests
✓ beta58: /?ips= hops to the real panel path
✓ beta58: /?p= quick-login hops to /panel
✓ beta58: plain / redirects transparently
✓ beta58: panel entry nudges the repo pools
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

6 FAILED
- proxyIpList puts preferred-country proxy first — ["proxyip.cmliussss.net:443","di.nscl.ir:443","tr.diam4.ggff.net:443"]
- user own ProxyIP still present
- per-ProxyIP configs: numbered + flag + Persian country — 🎯 1. proxyip.cmliussss.net:443
- per-ProxyIP configs keep the SNI remark suffix while spoofing is on — 🎯 1. proxyip.cmliussss.net:443 · icook.tw
- PX config carries a parseable proxyip relay path — vless://11111111-2222-4333-8444-555555555555@catpanel-demo.workers.dev:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2560%26proxyip%3Dproxyip.cmliussss.net%253A443&sni=catpanel-demo.workers.dev&fp=chrome&alpn=http%2F1.1&cs=TLS_AES_256_GCM_SHA384%3ATLS_CHACHA20_POLY1305_SHA256%3ATLS_AES_128_GCM_SHA256%3ATLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256%3ATLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256&fm=%7B%22tcp%22%3A%5B%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%22tlshello%22%2C%22lengths%22%3A%5B%220-6%22%2C%22100-140%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%2C%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%221-3%22%2C%22lengths%22%3A%5B%2280-160%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%5D%7D#%F0%9F%8E%AF%201.%20proxyip.cmliussss.net%3A443
- en locale → English country label — 🎯 1. proxyip.cmliussss.net:443
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
FAIL proxyip-dataplane
```
✓ syntax check passed
(node:3313) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?dp=base is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✗ baseline: a plain config to a Cloudflare-hosted target dials the FIRST relay of the panel list — 203.0.113.11:8443
✓ the 🎯 link for 203.0.113.11:8443 carries a real pin in its client path
✓ VLESS 🎯 203.0.113.11:8443: the worker dials THAT relay first (host AND port from the pin)
file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:107
const clientPath = (link) => dec((/[?&]path=([^&#]*)/.exec(link.split('#')[0]) || [])[1]);
                                                                ^

TypeError: Cannot read properties of undefined (reading 'split')
    at clientPath (file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:107:65)
    at file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:142:26

Node.js v22.23.3
```
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
