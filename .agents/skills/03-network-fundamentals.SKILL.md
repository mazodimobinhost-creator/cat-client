---
name: network-fundamentals
description: Use for any question about internet, DNS, TLS, HTTP, proxies, VPN protocols, CDN, Cloudflare (DNS, proxy, Workers, Pages, Tunnel, WARP), and connection troubleshooting.
---

# Networking fundamentals and Cloudflare

## Diagnose by layer (always go in this order)
1. **Local link**: Wi-Fi/mobile data up? Other sites work?
2. **DNS**: `dig example.com`, `dig @1.1.1.1 example.com`, `dig +https` / DoH test. Compare answers from different resolvers. Wrong or private IPs, NXDOMAIN, or timeouts = DNS interference.
3. **TCP**: `nc -vz host 443` or `curl -v --connect-timeout 8`. Timeout = IP/port blocked or packets dropped. Reset (RST) right after connect = active interference.
4. **TLS**: `openssl s_client -connect host:443 -servername host`. Works with one SNI and fails with another = SNI-based filtering. Check cert chain and expiry.
5. **HTTP**: `curl -I -v https://host`. Check status, redirects, headers (`cf-ray`, `server`, `alt-svc`).
6. **Application**: cookies, auth, rate limits, WAF challenges.
Report which layer breaks. Do not say "blocked" without evidence for the layer.

## Useful tools
`curl`, `dig`/`nslookup`, `mtr`/`traceroute`, `openssl s_client`, `nc`, `ping` (ICMP is often blocked and proves little), `tcpdump`/Wireshark, `nmap` (only on your own hosts), browser DevTools Network tab, `wrk`/`hey` for load.

## DNS essentials
- Record types: A/AAAA (IP), CNAME (alias), TXT, MX, NS. Low TTL while migrating.
- DoH/DoT encrypt DNS to the resolver; they do not hide the destination IP or SNI.
- DNS poisoning shows as wrong answers; DoH to a reachable resolver usually fixes only that part.

## TLS and SNI
- SNI is sent in cleartext in the ClientHello unless ECH (Encrypted Client Hello) is used. Filters often match SNI. ECH is supported by Cloudflare but client/browser support varies.
- TLS 1.3 hides the certificate. Use valid certs; mismatched certs cause failures and security warnings.
- Keep time correct on servers/clients (TLS fails with wrong clock).

## HTTP versions
- HTTP/1.1 over TCP, HTTP/2 over TCP (multiplexed), HTTP/3 over QUIC (UDP 443). Some networks throttle or block UDP/QUIC; disabling HTTP/3 can restore access.

## Cloudflare
- **Proxy (orange cloud)**: hides origin IP, adds CDN/WAF/DDoS. Grey cloud = DNS only.
- **SSL modes**: use Full (strict) with a valid origin cert (Cloudflare Origin CA is fine). Flexible causes redirect loops and is insecure.
- **Workers**: JS/TS at the edge. Deploy with `wrangler`. Config in `wrangler.toml`/`wrangler.jsonc`. Know limits: CPU time, subrequest count, request/response size, no raw TCP sockets except via the `connect()` API and its restrictions. Store secrets with `wrangler secret put`, never in code. Bindings: KV (eventually consistent), D1 (SQLite), R2 (objects), Durable Objects (strong consistency/state).
- **Pages**: static sites + Functions. **Tunnel (`cloudflared`)**: expose a service without opening ports. **WARP**: client VPN-like tunnel to Cloudflare. **Zero Trust**: access policies.
- **Rules**: Cache Rules, Page Rules/Redirect Rules, WAF custom rules, Rate Limiting. Always test with `curl -I` and the `cf-cache-status` header.
- API tokens: create scoped tokens (specific zone/permission), never the Global API Key.
- Proxying arbitrary traffic through a Worker may violate Cloudflare's terms and can get the account limited. Warn the user before building such things; use it for your own apps and APIs.

## VPN / proxy protocol overview (concepts)
- WireGuard (UDP, fast, easily fingerprinted), OpenVPN (TCP/UDP, easily fingerprinted), IPsec/L2TP (mostly blocked on filtered networks).
- Proxy-style transports: Shadowsocks, Trojan, VMess, VLESS (with TLS/Reality/WebSocket/gRPC), Hysteria2/TUIC (QUIC-based), NaiveProxy, SOCKS5/HTTP proxy (no encryption by itself).
- Tooling: Xray-core, sing-box, v2ray-core; clients like v2rayNG, Hiddify, NekoBox, Streisand; panels like 3x-ui and Marzban.
- Details for filtered networks are in the `iran-internet-circumvention` skill.

## Hardening servers
SSH keys only, disable root password login, firewall (ufw) allow only needed ports, fail2ban, unattended security updates, non-default SSH port is optional (not security by itself), regular backups, monitor with simple uptime checks.

## Rules for answers
- Cite the layer and the test that shows it. Give exact commands the user can run.
- Mark anything you could not test from your own environment.
- Prefer current official docs (Cloudflare docs, Xray docs) over memory for syntax; versions change often.
