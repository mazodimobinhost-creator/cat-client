# Panel & service research (2026-09-21)

Research pass over the panel list requested for Cat Client. For every open
project we captured the canonical repository and, where it fits the app, added
it to the **Cloud tab catalog** (`CloudflareWorker.kt`). Commercial/closed
services are listed for reference only.

## Added to the in-app catalog

### Cloudflare Worker panels (paste into a Worker, no VPS)

| Name | Repo | Notes |
| --- | --- | --- |
| **Cat Panel (built-in)** | this repo | VLESS-WS/Trojan-WS/WARP, SNI whitelist, clean CF IPs, REMOTE full-TCP mode |
| Z-E-U-S | [panel-zeus/Z-E-U-S](https://github.com/panel-zeus/Z-E-U-S) | chain proxy, clean-IP scanner, DoH, fragment, Warp+ |
| BPB Worker Panel | [bia-pain-bache/BPB-Worker-Panel](https://github.com/bia-pain-bache/BPB-Worker-Panel) | VLESS/Trojan/Warp, fragment, clean-IP, full format output |
| BUB Panel | [hoabba3i-dev/BUB-Panel](https://github.com/hoabba3i-dev/BUB-Panel) | multi-protocol (Warp/VLESS/Chain) |
| Nova Proxy | [IRNova/Nova-Proxy](https://github.com/IRNova/Nova-Proxy) | free-tier worker, VLESS/Trojan/SS over WS/gRPC/XHTTP, Nova Radar clean-IP scanner, WARP node, backend mode |
| Netra Panel | [hghheh224/netra-panel](https://github.com/hghheh224/netra-panel) | CF Workers VLESS/Trojan + Warp Pro, fragment/noise, web panel at `/panel`, Telegram installer bot |
| Apex Panel | [netrair/Apex](https://github.com/netrair/Apex) | CF Workers + D1, multi-user, quotas/expiry, panel password + secure path |
| Epeius | [cmliu/epeius](https://github.com/cmliu/epeius) | Trojan-over-WS proxy + subscription engine (Clash/Sing-box/Surge/Loon), PROXYIP preferred clean-IP management |
| Blue-Knight Panel | [BlueKnightNet/Blue-Knight-Panel](https://github.com/BlueKnightNet/Blue-Knight-Panel) | CF edge or Node 22, encrypted-DNS gateway, WARP registration, Amnezia (noise) profiles |

### Server / VPS panels (install guides)

| Name | Repo | Notes |
| --- | --- | --- |
| Marzban | [Gozargah/Marzban](https://github.com/Gozargah/Marzban) | standard Xray management panel, Docker, REST API |
| 3x-ui | [MHSanaei/3x-ui](https://github.com/MHSanaei/3x-ui) | advanced Xray panel, per-client traffic/IP limits, one-click SSL, Telegram bot |
| w-ui | [AbolfazlTafakori/w-ui](https://github.com/AbolfazlTafakori/w-ui) | WireGuard/AmneziaWG/OpenVPN selling panel |
| Nova Server | [IRNova/Nova-Server](https://github.com/IRNova/Nova-Server) | Xray + sing-box + Hysteria2 + AmneziaWG, multi-node, Iran bridge tunnels, clean-IP refresh |
| Spider Panel | [amirh00sain/SpiderPanel](https://github.com/amirh00sain/SpiderPanel) | Reality/WS/XHTTP, browser clean-IP scanner, CF Worker country routing |
| Technamooz Panel | [technamooz/Panel_Technamooz_VPN](https://github.com/technamooz/Panel_Technamooz_VPN) | per-ISP clean IPs, separate CDN domain for Host/SNI (SNI-block bypass), XHTTP packet-up, Telegram bot |
| SulgX Panel | [rohitanandsharma3-hub/SulgX-Panel](https://github.com/rohitanandsharma3-hub/SulgX-Panel) | single-file VLESS sub panel, quotas, clean-IP scanner, DOH link, traffic charts |
| RVG Gateway | [Taymaz1391/RVG](https://github.com/Taymaz1391/RVG) | FastAPI multi-protocol gateway, per-link quotas, TLS fingerprint spoofing |
| Luffy Panel | [KiwwyQ/LuffyPanelFork](https://github.com/KiwwyQ/LuffyPanelFork) | VLESS+Trojan on Render/Railway, routes through CF clean IPs, `/sub/` |
| Lunel | [ArasTey/lunel](https://github.com/ArasTey/lunel) | isolated proxy instances (VLESS WS/xHTTP, Trojan, SS) + web console |
| Apex Panel (Railway VPS) | [mohammadtavaaakkooll-glitch/Apex-Panel-Railway-Vpn](https://github.com/mohammadtavaaakkooll-glitch/Apex-Panel-Railway-Vpn) | multi-protocol multi-user on Railway, Persian UI |
| x4g — Marzban on Railway | [x4gKing/Marzban-Panel](https://github.com/x4gKing/Marzban-Panel) | PasarGuard-style build-time clone of upstream Marzban; also [Marzban-Node](https://github.com/x4gKing/Marzban-Node) and [3x-ui-multi](https://github.com/x4gKing/3x-ui-multi) (Tor country exits) |
| Vortex Network Panel | [GariestGary/vortex-network-panel](https://github.com/GariestGary/vortex-network-panel) | sing-box policy-routing gateway UI, backups/rollback |
| OpenVPN | [OpenVPN/openvpn](https://github.com/OpenVPN/openvpn) | classic server; pair with w-ui / openvpn-panel |
| wg-easy (WireGuard UI) | [wg-easy/wg-easy](https://github.com/wg-easy/wg-easy) | Docker WireGuard + web UI (covers "Vpn ui") |
| Vodiwalker | [Vodiwalker](https://github.com/Vodiwalker) | self-hosted VPN panel (official org) |

### Tunnels

| Name | Repo | Notes |
| --- | --- | --- |
| BackPack | [AminMGMT/BackPack](https://github.com/AminMGMT/BackPack) | Go reverse tunnel — the recommended `REMOTE` backend for Cat Panel full-TCP mode |

## Research notes (not added to catalog)

- **Nahan** — Nahan VPN (nahanvpn.com/panel) is a commercial reseller VPN
  service, not open source. Nothing to add in-app.
- **Pasargurd** — the *PasarGuard* method for deploying Marzban on Railway;
  covered via the x4g catalog entry (same technique, upstream clones).
- **X4g** — developer `x4gKing`; catalog entry points to his Marzban/Railway
  repositories.
- **Edg tunnel** — no verifiable project found under this name.
- **Matix, Stang, Isspanel, Px panel, Rail panel** — no verifiable open-source
  project found under these names (searches returned unrelated results or
  commercial services). If you have the exact repos, send them and we'll add
  them to the catalog.
- **Trendify Nexus** — "Trendify Panel" is an SMM (social media marketing)
  panel, unrelated to VPNs.
- **Open vpn** — covered by the OpenVPN entry.

## Cat Panel v3 — what was adopted (2026-09-22)

Cat Panel (the built-in worker in `app/src/main/assets/panels/catclient.worker.js`)
was rebuilt as **v3.0.0 "purple night"**. Instead of shipping the other panels, the
best ideas from the requested panels were folded into it:

| Feature in Cat Panel v3 | Borrowed idea from | Notes |
| --- | --- | --- |
| Multi-format subscription (`/sub`, `/sub64`, `/clash`, `/singbox`, `/all`) | Blue-Knight Panel, Epeius, BPB | one link per client type; `/clash` also ships DNS + Iran-direct rules |
| Encrypted DNS resolver `/dns-query` (RFC 8484 GET + POST) with upstream latency table | Blue-Knight Panel (encrypted-DNS tab) | upstream changeable via `DNS_UPSTREAM`; ready-made instructions for Cat Client, Chrome/Firefox, Intra/RethinkDNS and Mihomo |
| In-panel clean-IP scanner (browser probes per IP) + `/api/ping` server probe | Z-E-U-S, BPB, Nova Radar, Technamooz | browser tests measure *your* ISP, not the CDN edge |
| One-tap "build config from selected IPs" + `catclient://scan` deep link | Z-E-U-S scanner → app hand-off | pushes clean IPs into Cat Client's fronting list |
| Offline QR codes (`/qr.svg`, self-contained encoder) | Blue-Knight Panel, v2box-style flows | no external CDN, no Google Charts — works even if every third-party host is blocked |
| Persian + English UI, dark/light themes, sticky tab bar, FAQ accordions | Blue-Knight Panel (10 themes, mobile drawer) | two themes, RTL-first copy, purple/black/white palette |
| SNI whitelist gate + clean-IP variants, `REMOTE` full-TCP relay, `warp://` | already in v2, kept | unchanged behaviour, more tests |
| Panel → app deep links (`add-sub`, `scan`) | v2 (`add-sub`) | new `scan` host registered in `AndroidManifest.xml` |

### App side (Cat Client v1.1.0)

- New **Scanner tab** (`IpScanner` + fronting endpoints): built-in Cloudflare/Gcore/
  Fastly ranges, custom CIDR input, TLS-ping with the panel SNI, "Use" per row and
  "Apply the fastest IP" → writes `frontingIps` and reconnects live.
- Palette re-authored to **violet / black / white** in `CatClientDesignTokens`
  (`DashboardViews.kt`) — light theme is now pure white with Cat purple accents.
- Branding assets regenerated (`scripts/branding/generate-icons.py`): the WhiteDNS
  logo is replaced by the cat + bolt mark, adaptive icon foreground/background
  reworked, TV banner redrawn.
- The Cloud tab is now **"My Panel"**: deploy Cat Panel, jump to the scanner, then
  drive everything from the panel page.
