# Android failure report (fe346b9)


## files with compile errors
## log tail
### step18.log
✓ domain → raw CF IP in sub (DNS-free)
✓ ?dom=1 / domToIp=false keeps domains
✓ poisoned cache entry ignored (must be CF-range)
✓ default: every TLS config carries sni == worker host (no rotation)
✓ default: no SNI suffix in the remarks
✓ default: sni= in every TLS link equals host=
✓ SNI rotation: TLS configs carry >1 distinct SNI
✓ SNI rotation: pool = defaults, never the panel host
✓ SNI rotation: remarks carry the SNI suffix
✓ SNI rotation: links carry matching sni= param
✓ ?sni= pins a single SNI
✓ sniRotate=false → single shared SNI (skk.moe default)
✓ custom SNI pool: panel host + skk.moe + invalid filtered, deduped
✓ 🎯 configs dial the domMap IP of the worker host
✓ panel: health&test button renamed + px chips + hint
✓ health: clean probe ok on any HTTP answer (routing works)
✓ health: clean probe dials TLS + config Host + vless path
✓ health: plain probe skips TLS, keeps Host template
✓ health: proxyip probe uses relay template (trace via CF host)
✓ health: proxyip 403 → relay NOT healthy (http 403)
✓ health: silent endpoint → no-http-response
✓ health: px probed on its OWN port (8443)
✓ health: addr with :port suffix split defensively (port honored)
✓ health: hint explains entry-port vs relay-port
✓ socksRelayList parses socks5://user:pass@ip:port, skips CF relays
✓ dial plan: CF target → CF relays then socks
✓ dial plan: non-CF target → socks FIRST then direct then CF relays
✓ sub: 🧦 config emitted with an encoded relay reference (never the credentials)
✓ sub: the 🧦 path exposes no username or password
✓ health: socks probe ok (no auth)
✓ health: socks probe with user:pass auth
✓ health: socks method rejected → relay✗ with reason
✓ panel: socks placeholder mentions socks5:// form
✓ panel: IPCheck.ing exit-test link + hint
✓ panel inline script parses (no syntax errors)
✓ svc: gemini through 🧦 socks-first chain → open
✓ svc: gemini dialled the SOCKS relay (not direct/CF)
✓ svc: CF egress refused by Google → ⚠ 403 verdict
✓ panel: 🧪 service-test button + gemini hint + chips row
✓ svc: default host list covers Gemini/ChatGPT/X/YouTube
✓ fm/cs: TLS links carry cs= and fm= (two-stage fragment profile)
✓ fm/cs: fm payload is valid JSON with two fragment stages
✓ fm/cs: :80 plain links stay clean (no fm)
✓ fm/cs: toggle off → legacy links
✓ fm/cs: CIPHER_SUITES_DEFAULT is a colon-separated TLS list
✓ Irancell F&F preset pins 188.114.97.6 and Xray TLS options
✓ Irancell F&F uses BlueKnight finalMask + exact cipher suites
✓ Irancell F&F also emits a Trojan URI with the same exact TLS profile
✓ Xray JSON carries preset fingerprint, ALPN, cipher suites and finalmask
✓ F&F finalmask replaces (does not stack with) survival Fragment outbound
✓ MCI ECH preset emits its documented address, ECH resolver and no F&F
✓ MCI IPv6 + F&F preset pins IPv6 and omits the Irancell-only cipher list
✓ Clash rejects an Xray-only firewall preset instead of silently dropping finalmask
✓ sing-box rejects an Xray-only firewall preset instead of silently dropping finalmask
✓ firewall presets are per-link and do not persist into panel settings
✓ v6: 2606:4700 endpoints are recognized CF IPs
✓ v6: defaults include IPv6 endpoints
✓ v6: sub emits bracketed IPv6 endpoint link
✓ svc: hosts cover ChatGPT, Claude, Gemini web + API and AI Studio
✓ svc: 404 on an API root counts as reachable (open)
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
- empty address list falls back to built-in configs + worker host — vless://11111111-2222-4333-8444-555555555555@162.159.192.1:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2560&
