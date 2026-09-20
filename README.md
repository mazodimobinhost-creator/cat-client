# 🐱 Cat Client

**Cat Client** is a powerful Android VPN client built on the Mihomo core,
combining one-tap connectivity with all the tools Iranian users need.

## ✨ Features

- 🎨 **Black / white / deep-purple UI** — dark, light, and system themes with user-pickable accent color (purple / blue / pink / green / custom)
- 🐱 **Icon**: black-and-white cat with a purple lightning bolt
- 🌐 **Every protocol supported**: VLESS, VMess, Trojan, Shadowsocks, Hysteria 2, TUIC, WireGuard (including AmneziaWG options)
- 📥 **Every input format**: single share-links, subscription URLs (plain / Base64), Mihomo / Clash YAML, Xray JSON, clipboard import, QR scan
- 🆓 **Free tab** — auto-fetches healthy public configs from multiple community sources and live-pings them before use (no more "-1" dead entries)
- ☁️ **Cloud tab — multi-panel Cloudflare Worker deploy wizard** — paste a Cloudflare API token once, choose a panel, and Cat Client deploys it for you:
  - 🐱 **Cat Client (built-in)** — lightweight purple-themed panel, fastest to deploy
  - ⚡ **Z-E-U-S** — IP scanner, chain proxies, DoH, fragment, Warp pro, routing (full Zeus bundled in-app)
  - 🟣 **BPB-Worker-Panel** — VLESS/Trojan/Warp configs, clean-IP, fragment, private DoH, cross-platform cores
  - 🫧 **BUB-Panel** — free multi-protocol panel
  - 🧭 **w-ui** (server install guide) — WireGuard / AmneziaWG / OpenVPN panel with quotas, expiry & Telegram bot
  - 🎒 **BackPack** (server install guide) — high-performance reverse-tunnel engine
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

## 📄 License

GPL-3.0 (inherited from the Mihomo core base). See [LICENSE](LICENSE).
