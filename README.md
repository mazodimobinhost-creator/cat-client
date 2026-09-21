# 🐱 Cat Client

**Cat Client** is a powerful Android VPN client built on the Mihomo core,
combining one-tap connectivity with all the tools Iranian users need.

## ✨ Features

- 🎨 **Black / white / deep-purple UI** — dark, light, and system themes with user-pickable accent color (purple / blue / pink / green / custom)
- 🐱 **Icon**: black-and-white cat with a purple lightning bolt
- 🌐 **Every protocol supported**: VLESS, VMess, Trojan, Shadowsocks, Hysteria 2, TUIC, WireGuard (including AmneziaWG options)
- 📥 **Every input format**: single share-links, subscription URLs (plain / Base64), Mihomo / Clash YAML, Xray JSON, clipboard import, QR scan
- 🆓 **Free tab** — auto-fetches healthy public configs from multiple community sources and live-pings them before use (no more "-1" dead entries)
- ☁️ **Cloud tab — Cat Panel + researched panel catalog** — deploy the built-in panel with one API token, copy its single-file worker code for any Cloudflare account, or open install guides for 20+ researched panels (Z-E-U-S, BPB, Nova, Netra, Apex, Epeius, Marzban, 3x-ui, w-ui, Spider, Technamooz, SulgX, RVG, Luffy, Lunel, x4g, OpenVPN, wg-easy, BackPack …):
  - 🐱 **Cat Panel (built-in)** — single-file Cloudflare Worker panel: VLESS-WS + Trojan-WS + WARP links, **SNI whitelist** (rejects unknown SNIs), **clean Cloudflare IP variants** (server=any CF edge IP, SNI stays the panel host), Mihomo/Clash YAML output, optional `REMOTE` wss relay for full-TCP mode, panel password. *Copy the code → paste into any Worker → open the panel → import the sub.*
- 🛰️ **IP Scanner with SNI + Spoof** — built-in Cloudflare/Gcore CDN IP ranges + custom subnet input; TLS-ping each candidate with selectable SNI and fronting/spoof mode; apply clean IPs straight to your configs
- 🌍 **Live globe + country detection** — shows connected IP, country name + flag, ISP and ping on the dashboard with an animated arc from Iran to the destination
- 📲 **Rich notification** — live up/down speed, connected config name, ping, country flag
- 🔤 **Bilingual**: full Persian (فارسی) and English with RTL
- 🧭 **Quick-settings tile + home-screen widget**
- 🚫 **Zero tracking** — no Firebase / third-party analytics.

## 🛠️ Build

Requirements: JDK 21, Android SDK (platform 36, build-tools 35.0.0, NDK 29, CMake 3.22.1), Go 1.24+.

```bash
./scripts/build-flclash-core.sh     # builds Mihomo native core for all ABIs
./gradlew assembleDebug             # debug APK at app/build/outputs/apk/debug/
```

The easiest path is to push to this repo — GitHub Actions builds debug + release APKs automatically and attaches them to a new release.

## 🔑 Cloudflare API Token (for in-app Worker deploy)

1. Log in to https://dash.cloudflare.com/ → **My Profile → API Tokens**
2. **Create Token** → use **"Edit Cloudflare Workers"** template (recommended),
   or build a Custom token with at least:
   - Account → Workers Scripts → **Edit**
   - Account → Workers Subdomain → **Read**
   - Account → Account Settings → **Read**
3. Leave Account Resources = **All accounts** (or select your target account)
4. Continue → Create Token → copy the token
5. Open Cat Client → **Cloud** tab → pick a panel → paste the token → Deploy.

## 🐱 Cat Panel (built-in Cloudflare Worker)

`app/src/main/assets/panels/catclient.worker.js` is the whole panel — one file.

**Two ways to run it**

1. **No token, pure paste** — Cloudflare Dashboard → Workers & Pages → *Create
   Worker* → paste the file (Cat Client → Cloud tab → *Copy Worker code*) →
   Deploy. Open the worker URL: the panel shows your sub link, all configs,
   clean-IP list and Mihomo/Clash YAML.
2. **Through Cat Client** — Cloud tab → paste an API token → *Deploy on my
   Cloudflare*. The app uploads the worker, then offers to import the
   subscription.

**SNI + clean Cloudflare IPs (ایپی سفید)**

- Clients can point the *server* at any Cloudflare edge IP and keep the panel
  hostname as the TLS SNI. The worker validates `X-Forwarded-Sni` against the
  host + `SNI` + `SNI_LIST` and returns 403 for anything else.
- Set `CF_IPS` (comma-separated) and `/sub` automatically includes a VLESS +
  Trojan variant per IP. Cat Client's IP Scanner (SNI + Spoof) finds the
  fastest ones for your ISP.

**Worker environment variables (all optional)**

| Var | Default | Meaning |
| --- | --- | --- |
| `UUID` | derived from host (stable) | UUID in generated links |
| `SNI` | worker host | SNI written into links |
| `SNI_LIST` | — | extra accepted SNIs (comma list) |
| `CF_IPS` | — | clean Cloudflare IPs published in `/sub` |
| `PORT` | `443` | link port |
| `VLESS_PATH` | `/ws?ed=2048` | VLESS WebSocket path |
| `TROJAN_PATH` | `/trojan` | Trojan WebSocket path |
| `TROJAN_PASS` | `UUID` | Trojan password |
| `REMOTE` | — | `wss://` relay (BackPack tunnel / remote VLESS-WS) for full-TCP mode |
| `PANEL_PASSWORD` | — | require `?p=<pass>` on the panel page |
| `ENABLE_WARP` | `true` | omit the `warp://` link when `false` |
| `USER_TOTAL` | 1 TiB | `subscription-userinfo` total |

**Endpoints**: `/` (panel), `/sub`, `/sub64`, `/clash` (Mihomo YAML),
`/health`, plus the VLESS/Trojan WebSocket paths.

The worker is tested on Node: `node scripts/panels/cat-panel.test.mjs`.

## 📄 License

GPL-3.0 (inherited from the Mihomo core base). See [LICENSE](LICENSE).
