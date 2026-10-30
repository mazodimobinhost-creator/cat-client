# Android failure report (e4e7678)


## files with compile errors
## log tail
### step18.log
✓ expirationTtl is honoured in memory
✓ first traffic write goes out
✓ traffic is throttled (49 updates → 1 write)
✓ throttled value is served fresh from memory
✓ dirty traffic is flushed on a later request after the interval
✓ another instance reads the users list
✓ tunnel requests reuse the cached read for 15 min
✓ panel/subscription requests re-read after 15 s
✓ a failed Blob read is retried, not mistaken for "no users"
✓ a persistent Blob failure surfaces as an error
✓ list() decodes hex paths back to keys
✓ delete removes the blob
all vercel-storage checks passed
✓ panel bundle has exactly one cloudflare:sockets import to shim
✓ vercel sub answers with links
✓ every Vercel line is WebSocket (the only transport the edge forwards)
✓ Vercel lines: :443, TLS, host == project, SNI in the vercel.app family, alpn http/1.1
✓ Vercel sub serves all three SNIs
✓ Vercel names are unique
✓ both VLESS and Trojan are served
✓ Clash/mihomo: no httpupgrade proxies on Vercel
✓ Clash/mihomo carries the bare vercel.app SNI too
✓ sing-box carries the bare vercel.app SNI too
✓ sing-box: Vercel outbounds use ws only
✓ VLESS over WebSocket reaches the target
✓ Trojan over WebSocket reaches the target
✓ VLESS over WebSocket on the advertised ?ed= path reaches the target
✓ WebSocket with a wrong UUID is refused
✓ httpupgrade request is refused quickly (no silent hang)
all vercel-transport checks passed
✓ test rewrite touched exactly the origin URL
✓ front answers its own health check with the origin
✓ panel /api/version through the front
✓ panel login page through the front
✓ subscription through the front
✓ VLESS over WebSocket through the front reaches the target
✓ VLESS with early data (Sec-WebSocket-Protocol) through the front
✓ front echoes the early-data subprotocol (strict clients need it)
✓ Trojan over WebSocket through the front reaches the target
✓ 3 MB download through the front arrives byte for byte
✓ a wrong UUID still gets nothing through the front
✓ without an origin the front refuses (no open proxy)
✓ an invalid origin is refused
✓ the front refuses to loop to itself
✓ redirects to the origin are rewritten to the front
✓ panel serves the shipped front script with the origin filled in
✓ panel refuses to fill in a non-hostname origin
all vercel-cf-front checks passed
✓ parseSums reads sha256sum lines (text and *binary), ignores junk
✓ patchPanel swaps the one cloudflare:sockets import
✓ patchPanel refuses 0 or 2 imports
✓ pickRelease skips drafts and releases without the adapter
✓ pickRelease takes the newest by date even if the list is out of order
✓ pickRelease honours a pinned tag
[cat] settings from cat-config.json: UUID, CAT_NO_DISK
✓ cat-config.json: only allowed keys, env wins, no objects
[cat] ⚠ cat-config.json is not valid JSON — ignored
✓ cat-config.json: broken JSON is ignored, not fatal
✓ app asset node-panel/index.js is identical to node-panel/index.js
✓ app asset node-panel/package.json is identical to node-panel/package.json
✓ app asset node-panel/README.md is identical to node-panel/README.md
✓ iisEdge: tag on → https, client IP, original URL, forged x-forwarded-host/port dropped
✓ iisEdge: tag off → http, bare tag → /
✓ iisEdge: a client-forged second tag stays an unknown path (cannot claim https or another IP)
✓ iisEdge: no tag (rewrite missing) → plain http behind a proxy
✓ codeDirOf: CAT_CODE_DIR inside the folder is used
✓ codeDirOf: outside the folder (node_modules unreachable) → ./cat-code
✓ portOf: SERVER_PORT (KataBump) > PORT > 8080
✓ a file that does not match the SHA-256 list is refused
✓ launcher starts on SERVER_PORT and reports the source + storage
✓ /api/version answers through the launcher
✓ plain host: exactly one VLESS + one Trojan line
✓ plain host: address = the host, port = its own port, no TLS, ws, Host = host
✓ plain host: ?addr= / ?port= cannot turn it into a Cloudflare line
✓ HTTPS edge: one VLESS + one Trojan on host:443 TLS, SNI = Host = host
✓ behind Cloudflare: the full Cloudflare set (clean IPs + several CF ports)
✓ VLESS over WebSocket reaches the target through the launcher
✓ Trojan over WebSocket reaches the target through the launcher
✓ owner can sign in through the launcher
✓ plain http: the session cookie drops Secure (else browsers never keep it on http://IP:port)
✓ HTTPS edge: the session cookie keeps Secure
✓ signed-in panel explains the direct Node mode with the real address:port
✓ HTTPS edge: panel says TLS on 443 with the host name
✓ settings save on the Node host
✓ storage file written to cat-data (mode 0600)
✓ restart reuses the prepared panel
✓ identity read from cat-config.json (names logged, values never)
✗ the sub for the UUID from cat-config.json works after the restart
✓ settings survive a restart (file storage)
✓ direct mode: forged X-Forwarded-For does not dodge the login lockout
✓ the front name saves on the Node host (settings)
✓ HTTPS host + front: one VLESS + one Trojan 🟧 line first, front name as address/Host/SNI on 443 TLS
✗ HTTPS host + front: the host's own TLS lines stay (host:443, SNI = host)
✓ front lines keep the panel path / identity (same ws path as the host lines)
✗ request through our front (x-cat-front + cf-ray): host TLS lines + 🟧 lines, never the Cloudflare clean-IP set — [["cat-front-ab12cd.someone.workers.dev",443],["cat-front-ab12cd.someone.workers.dev",443],["104.19.150.96",443],["104.19.150.96",443],["cat-panel-production.up.railway.app",443],["cat-panel-production.up.railway.app",443],["104.17.100.64",443],["104.17.100.64",443],["104.16.192.204",443],["104.16.1
✓ HTTPS host panel: says the front is active (name shown)
✓ direct Node panel: <body data-plat="node"> + the CSS that hides the Cloudflare-only sections, shows «server IPs» (6.79.3: Locations stay)
✓ …but the hidden sections stay in the page and 🔗 Link panels is never hidden
✓ Node host behind Cloudflare: no data-plat (all Cloudflare features stay)
✓ plain edge + front: the 🟧 lines are still TLS on 443 (the front itself speaks https to the host)
✗ front cleared → back to exactly the host's two lines
✓ web.config: httpPlatformHandler runs index.js with PORT from IIS and the IIS mode on
✓ web.config: the edge tag is exactly what iisEdge reads ({HTTPS}/{REMOTE_ADDR}{UNENCODED_URL})
✓ IIS mode: listens on 127.0.0.1 only, panel cached in App_Data/cat-code, users in App_Data/cat-data
✓ IIS https: one VLESS + one Trojan on site:443 TLS, SNI = Host = the site (forged x-forwarded-host ignored)
✓ IIS http: configs on site:80 without TLS (never IIS's private port)
✓ IIS without the rewrite tag: still site:80, never the private port 45531
✓ IIS: VLESS over WebSocket through the tagged URL reaches the target
✓ IIS: the login lockout counts the real client IP from the tag, not a forged X-Forwarded-For
✓ hostOf: CAT_HOST wins; Railway / IIS guessed; Pterodactyl alone is NOT guessed
✓ directHost: only an explicit CAT_HOST=katabump (Bot-Hosting / Pterodactyl vars alone stay proxied)
✓ CONFIG_KEYS: CAT_HOST / CAT_CF_FRONT / CAT_KEEPALIVE allowed, still nothing like PATH / NODE_OPTIONS
✓ containerMemoryMb: cgroup v2 «max» → 0, a number → MB, v1 fallback
✓ keepWarm (IIS): remembers the public https address (never 127.0.0.1) and wakes /api/version when idle
✓ keepWarm: CAT_KEEPALIVE=0 turns it off
✓ KataBump: the launcher logs «host: katabump (direct port…)» from cat-config.json
✓ KataBump: a client-sent X-Forwarded-Proto cannot turn the configs into TLS lines
✗ KataBump: cf-ray from a non-Cloudflare address does not unlock the clean-IP set — 32
✓ KataBump: forged X-Forwarded-Proto + X-Forwarded-For + CF-Connecting-IP do not dodge the login lockout
5 failure(s)
