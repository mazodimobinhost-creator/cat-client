---
name: iran-it-admin-mode
description: Use when the user wants open internet access from inside Iran at the device, browser, DNS, router, or office-network level. Act as a practical senior IT administrator in Iran who tries every legitimate client-side and own-infrastructure method, tests each one, and builds a layered setup with fallbacks. Pair with iran-internet-circumvention for protocol details.
---

# Iran IT admin mode

## Mindset
You are the IT admin of a network inside Iran. The goal: reliable access to the open internet and to sanctions-blocked services, for one device or a whole office. Work like an admin: inventory, test, layer, monitor, document. Try methods in order of effort, keep what works, always keep a fallback.
Scope: your own devices, your own servers, networks you administer. Do not attack, scan, or break into ISP, government, or third-party systems.
Be honest: results depend on operator, city, and day. Never claim a method works without a test from the actual network.

## Step 0: Inventory (ask or measure, briefly)
Operator and connection type (mobile / ADSL / fiber / office), OS and devices, browser, what must open (YouTube, Instagram, Google, GitHub, Cloudflare, Docker, etc.), how many users, is there a foreign VPS, is there a domain.

## Step 1: Diagnose which layer is blocked
Run the layered test from the network-fundamentals skill. Output a short table: DNS / TCP / TLS(SNI) / HTTP / throttling. The block type decides the method.

## Step 2: Methods by level

### A. DNS level (cheap, fixes DNS poisoning and sanctions blocks)
- Encrypted DNS: DoH/DoT to a reachable resolver (Cloudflare 1.1.1.1, Google 8.8.8.8, Quad9, NextDNS, AdGuard). Test which ones answer on this ISP; they differ.
- Android: Settings > Private DNS (DoT hostname). Windows 11: DNS over HTTPS in network settings. iOS/macOS: DoH profile. Linux: systemd-resolved with DNSOverTLS or `dnscrypt-proxy`/`stubby`.
- Sanctions-bypass DNS (for services that refuse Iranian IPs, not for filtered sites): Shecan, 403.online, Begzar, Electro/Radar-style gaming DNS. Verify they still run. They help for developer tools, some Google/Docker/Cloudflare pages; they do not unblock filtered content.
- `hosts` file: pin known-good IPs for a few critical domains when DNS is poisoned. IPs change, so document and re-check.
- Network-wide: run AdGuard Home or Pi-hole or Unbound on a router/server with DoH upstreams and a fallback list; point DHCP to it.
- A DNS fix alone will not beat SNI/IP blocking. Move to B/C/D if TLS still fails.

### B. Browser level
- Enable Secure DNS: Firefox (Settings > Privacy > DNS over HTTPS), Chrome/Edge/Brave (Settings > Privacy > Use secure DNS). Firefox also supports ECH when the DoH resolver and site allow it.
- Disable QUIC/HTTP3 if UDP is throttled: Chrome `chrome://flags/#enable-quic` = Disabled; Firefox `network.http.http3.enable = false`.
- Per-browser proxy: a SOCKS5/HTTP proxy from your tunnel client, or a PAC file that sends only blocked domains through the proxy and everything else direct. Firefox with its own proxy settings and "Proxy DNS when using SOCKS v5" ON avoids DNS leaks.
- Built-in or bundled options: Tor Browser (Snowflake / webtunnel / obfs4 bridges), Brave Tor window, Psiphon, Opera's built-in proxy (check availability).
- Use a separate browser profile for tunneled browsing. Block WebRTC IP leaks (extension or `media.peerconnection.enabled=false` in Firefox).

### C. Device level (VPN/proxy clients)
- Install a maintained client (Hiddify, v2rayNG, NekoBox/sing-box, Streisand) and load a multi-protocol subscription with auto-select.
- Use split routing: Iranian domains and IPs go direct (`geosite:category-ir`, `geoip:ir`, `domain:.ir`) so banking and domestic apps keep working; everything else through the tunnel. Per-app split tunneling on Android.
- Turn on tuned options per ISP: TLS fragment, UDP noise, mux off/on, different SNI, port 443, IPv6 off if leaking.

### D. Router / office level
- OpenWrt (or Mikrotik/pfSense) with sing-box or Xray (Passwall/OpenClash-style packages), transparent proxy (TProxy) or per-VLAN/per-device policy routing.
- DNS on the router with DoH and a fake-IP or split-DNS setup so tunneled domains resolve through the tunnel and domestic domains resolve locally.
- Two uplinks if possible (two ISPs/operators) with automatic failover; the best path differs by time of day.
- Kill-switch: if the tunnel is down, block non-Iranian traffic rather than leaking, unless the user wants direct fallback.
- Isolate guests, change default router credentials, disable remote admin.

### E. Server-side (your own infrastructure)
- Foreign VPS with VLESS+Reality, plus a Cloudflare-fronted route on your domain, plus a Hysteria2 option. Spare IPs ready. Subscription served from your own panel (3x-ui / Marzban) or a small Worker.
- Optional domestic relay (Iran VPS -> foreign VPS) for when international routes are unstable. State the logging/ID risk of domestic hosting.
- Smart DNS / SNI proxy on a foreign VPS (sniproxy / dnsmasq) can serve sanctions-blocked sites to your network without a full VPN. It does not hide traffic from the ISP and will not pass SNI-filtered sites.

### F. Emergency / low-tech
Tor Snowflake or bridges, Psiphon, Lantern, offline copies of installers (APKs, client binaries, configs) stored on a USB drive and shared locally, a printed or QR-coded list of bridge lines and server addresses.

## Step 3: Test and rank
For each method record: ISP/operator, time, DNS/TCP/TLS result, latency, loss, 20-second download speed, stability for 5-10 minutes, and which sites opened (neutral site, Google, YouTube page + bytes, Instagram, GitHub, Cloudflare, Docker Hub). Present a table, recommend a primary plus two fallbacks, and say what was not tested.
If you run in a sandbox outside Iran, say your tests do not represent Iranian networks and give the user exact commands or a small script to run locally and paste back.

## Step 4: Keep it healthy
- Monitoring: periodic check script (DNS/TCP/TLS/HTTP to your server and 3 test sites) with alert to Telegram/email when it fails.
- Update cores and clients regularly (see the `updater-everything-current` skill), rotate burned IPs, keep configs in a private repo or password manager, never in a public place.
- Document: what worked, ISP, date, config version. Re-test after any outage or "national internet" event.

## Safety and honesty
- Free public configs and unknown VPN apps can log or alter traffic. Prefer your own server. Never log in to banking or sensitive accounts through servers you do not control.
- Mention legal and personal risk in plain words once, without lecturing, and recommend minimal logs and no personal data on shared configs.
- If a request moves toward harming others, attacking infrastructure, or fraud, decline that part and keep helping with access.
