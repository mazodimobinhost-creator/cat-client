---
name: iran-internet-circumvention
description: Use for anything about internet in Iran: how filtering works, which tools, protocols, configs and domestic services work, how to build and test reliable access, and how to keep users safe. Verify current state before giving specifics, because it changes weekly.
---

# Internet in Iran: filtering, methods, configs

## Golden rules
1. The situation changes by week, ISP, city, and time of day. Treat everything below as a map, not a guarantee. Before recommending a specific setup, search for recent reports (Telegram channels, GitHub issues of Xray/sing-box/Hiddify, ntc.party, OONI, Cloudflare Radar, IODA) and say what is current and what is not.
2. Never invent configs, IPs, domains, keys, or "this works now" claims. If you did not test from the target network, say so.
3. Always test per operator (MCI/Hamrah-e Aval, Irancell, Rightel, and fixed lines such as TCI/Mokhaberat, Shatel, Asiatech, Pars Online, Mobinnet). A config that works on one often fails on another.
4. Protect the user: explain that unknown free configs/servers let the owner see unencrypted traffic and can inject or log. Never send passwords or sensitive logins through unknown servers. Mention that using or sharing circumvention tools can carry legal risk in Iran and that personal data and server logs should be minimized.

## How filtering typically works (layers)
- **DNS**: poisoned or forged answers for blocked domains. Fix: DoH/DoT to a reachable resolver, or resolve through the tunnel.
- **IP blocking**: whole IPs/ranges of blocked services and of burned proxy servers, including many cloud/VPS ranges. Fix: new IP, CDN-fronted routes, relays.
- **SNI/TLS inspection (DPI)**: blocks by the server name in the TLS ClientHello; may also fingerprint ClientHello shape. Fix: Reality, fragmenting the ClientHello, ECH where available, CDN with an allowed hostname.
- **Protocol fingerprinting**: OpenVPN, WireGuard, L2TP/IPsec, plain Shadowsocks/VMess often detected or throttled. Fix: TLS-looking transports.
- **Throttling**: UDP/QUIC and some HTTPS flows are slowed rather than blocked, especially on mobile. Fix: try TCP-based transports; disable HTTP/3; try different ports/SNI.
- **Whitelist / "National Information Network" mode**: during severe restrictions, international traffic is cut or limited to approved services, while domestic services keep working. Fix: only partial, see domestic section.
- **Sanctions blocking (different from filtering)**: many foreign services refuse Iranian IPs (403/blocked page). Fix: a VPN/proxy exit outside Iran, or DNS-based sanctions bypass services (e.g. Shecan, 403.online, Begzar; verify they still operate).

## Method toolbox (from most to least commonly useful; verify current state)
1. **VLESS + Reality (`xtls-rprx-vision`) over TCP**, direct to a VPS. No domain or certificate needed. Choose a decoy SNI/dest that supports TLS 1.3 and H2, is not blocked, and is not a CDN-heavy site that changes its certs. Weak point: the VPS IP gets blocked, then you must change the IP.
2. **Behind Cloudflare CDN**: VLESS/Trojan/VMess over WebSocket+TLS, gRPC, HTTPUpgrade, or XHTTP, with your own domain proxied (orange cloud). Direct IPs stay hidden. Try "clean IPs": scan Cloudflare ranges from the user's own network for low latency and no loss, then use that IP as the connect address while keeping your domain as SNI/Host. Clean IPs differ per ISP and expire.
3. **TLS/TCP fragment** (Xray `freedom` fragment, sing-box `tls_fragment`, built into Hiddify/NekoBox/v2rayNG-newer): splits the ClientHello so SNI DPI misses it. Needs tuning per ISP (`length`, `interval`). Helps most with CDN-based configs.
4. **UDP noise / WireGuard obfuscation** (Xray noise, Hiddify warp options): sometimes lets WARP/WireGuard through.
5. **Hysteria2 / TUIC (QUIC)**: very fast where UDP is open; fails or throttles on others. Use port hopping; keep a TCP fallback.
6. **Trojan or Shadowsocks-2022 with real TLS/ShadowTLS/Naive** as alternates.
7. **Chaining / relay**: client -> server inside Iran (domestic VPS, reachable even when international is limited) -> foreign server. Use a tunnel (WireGuard, GOST, Rathole, Backhaul, Waterwall) or Xray dokodemo/forward. Gives stability and shifts blocking away from the foreign IP, but concentrates risk on the domestic server (logging, KYC). Mention this trade-off.
8. **Cloudflare WARP** as outbound on your own server (to reach Google/Netflix-type sites that block datacenter IPs) or as a client fallback; availability differs per ISP.
9. **Tor (Snowflake, obfs4, meek, webtunnel bridges)** and **Psiphon / Conduit**: good emergency options that need no server. Bridges via the Tor bridge bot/email or bridges page, if reachable.
10. **Multi-config subscriptions with auto-select**: sing-box `urltest`/`selector`, Clash `fallback`/`url-test`, Hiddify auto. Ship several protocols/servers/ports so the client switches itself.

## Templates (replace placeholders; generate fresh values)
Generate values: `xray uuid`, `xray x25519` (private/public key), `openssl rand -hex 8` (shortId).

**Xray server, VLESS + Reality**
```json
{
  "inbounds": [{
    "port": 443,
    "protocol": "vless",
    "settings": {
      "clients": [{ "id": "<UUID>", "flow": "xtls-rprx-vision" }],
      "decryption": "none"
    },
    "streamSettings": {
      "network": "tcp",
      "security": "reality",
      "realitySettings": {
        "dest": "<decoy.example.com>:443",
        "serverNames": ["<decoy.example.com>"],
        "privateKey": "<PRIVATE_KEY>",
        "shortIds": ["<SHORT_ID>"]
      }
    }
  }],
  "outbounds": [{ "protocol": "freedom" }]
}
```
Share link shape: `vless://<UUID>@<SERVER_IP>:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=<decoy.example.com>&fp=chrome&pbk=<PUBLIC_KEY>&sid=<SHORT_ID>&type=tcp#name`

**Client link, VLESS over WebSocket+TLS behind Cloudflare**
`vless://<UUID>@<CLEAN_IP>:443?encryption=none&security=tls&sni=<your.domain>&fp=chrome&type=ws&host=<your.domain>&path=%2F<path>#name`
(Server side: Cloudflare proxied domain -> origin Xray/nginx on the matching WS path. Cloudflare must allow WebSockets; use Full (strict).)

**Xray client fragment (outbounds excerpt)**
```json
{ "tag": "fragment", "protocol": "freedom",
  "settings": { "fragment": { "packets": "tlshello", "length": "100-200", "interval": "10-20" } } }
```
and in the proxy outbound `streamSettings.sockopt.dialerProxy = "fragment"`. Tune length/interval per ISP.

## Panels and clients
- Server panels: 3x-ui, Marzban, x-ui forks. Always change default credentials and panel path, put the panel behind HTTPS or SSH tunnel, never expose it on the default port with default login. Keep Xray updated.
- Clients: v2rayNG, Hiddify, NekoBox/sing-box, Streisand, v2rayN, Nekoray (check which are maintained now).
- Subscriptions: serve a base64/sing-box/clash subscription URL from your own panel or a small Cloudflare Worker. Do not put secrets or user lists in public repos. A Worker used only to host your own subscription or API is fine; using Workers as a general public proxy risks account suspension.

## Server and IP hygiene
- Choose providers/regions whose ranges are not already burned; test an IP from inside Iran *before* deploying users. Keep a pool of spare IPs.
- Few users per IP. Rotate on block. Avoid ports/SNIs that were mass-published.
- Do not publish working configs publicly in bulk; they get blocked fast.
- Keep fallbacks on different protocols and ports.

## Domestic reachability (works when international is limited)
- Domestic hosting/CDN/DNS: ArvanCloud (CDN, cloud, DNS), Liara, Hamravesh, Iranserver, and similar. Host user-facing panels and static assets there if the audience is inside Iran.
- Domestic messengers/services typically stay up: Bale, Eitaa, Rubika, Aparat, Divar, Digikala, Snapp, and banking apps.
- Build apps to degrade gracefully: cache data, queue writes, support a domestic API endpoint plus a foreign fallback, and show clear offline messages.
- Dev dependency mirrors inside Iran (verify they still work): ArvanCloud mirrors for apt/npm/pip/Docker (e.g. `mirror.arvancloud.ir`, `docker.arvancloud.ir`), `registry.docker.ir`, other university/ISP mirrors. Set them in `apt` sources, `npm config set registry`, `pip --index-url`, Docker `registry-mirrors`. Treat mirrors as untrusted for secrets and check hashes where possible.
- Some foreign services (Google Cloud, GitHub parts, Docker Hub, JetBrains, etc.) restrict Iranian IPs because of sanctions. Report exact errors (403/451/geo block) and route through a foreign exit when appropriate.

## Testing a method (do all, from the target network)
1. Record: operator, city, time, device, client app + version, protocol + transport + port, SNI/host.
2. Connectivity per layer: DNS resolves, TCP connects, TLS handshake succeeds, first HTTP response, then sustained download for 10-20 s.
3. Measure: latency, packet loss, download/upload Mbps, stability over 5-10 minutes.
4. Test targets: a neutral site, Google, YouTube (can load page/video bytes and measure throughput; you cannot "watch" it), Instagram, Telegram, GitHub, Cloudflare dashboard/API, your own domain.
5. Compare across at least two operators if possible. Report as a table: method | operator | works? | speed | notes.
6. If you are running outside Iran (a cloud sandbox), state clearly that your results do not represent Iranian networks. You can only validate config syntax and server-side function, not Iranian blocking behavior.

## What the agent can and cannot do
- Can: explain, design setups, write and lint configs, write subscription Workers/scripts, check headers/status/TLS, measure throughput through a given proxy, read text/subtitles/metadata of pages.
- Cannot: literally watch video or see the Instagram app UI; access a Google or Cloudflare account without a connector or API token the user provides; guarantee that something works inside Iran without a test from there.
- If a token is needed (Cloudflare API, Google OAuth), ask the user to create a **scoped** token, store it as a secret, and never echo it back.

## Answer format
Give: (1) the likely cause by layer, (2) 2-3 ranked options with trade-offs, (3) exact steps/config, (4) how to verify, (5) fallback if it fails. Be direct, in Persian when the user writes Persian, with commands and configs in LTR code blocks.
