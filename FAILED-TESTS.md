# Android failure report (7767bf6)


## files with compile errors
## log tail
FAIL cat-panel
```
✓ panel: v6 accepted hint + svc exit chip handler
✓ src: mixed (default) keeps the owner IPs and the host anchor
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

1 FAILED
- empty address list falls back to built-in configs + worker host — vless://11111111-2222-4333-8444-555555555555@www.speedtest.net:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2
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
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
