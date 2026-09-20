# 🐱 Cat Client

**Cat Client** is a powerful Android VPN client built on the Mihomo core,
combining one-tap connectivity with advanced tools Iranian users need:

- 🎨 **Custom black/white/purple UI** with dark + light + system themes, user-customizable accent color
- 🌐 **All protocols supported**: VLESS, VMess, Trojan, Shadowsocks, Hysteria 2, TUIC, WireGuard (including AmneziaWG)
- 📥 **Every input format**: single share-links, subscription URLs (plain/Base64), Mihomo/Clash YAML, Xray JSON, clipboard import, QR scan
- 🆓 **Free Configs tab** — auto-fetches healthy public configs from multiple community sources (no more "-1" dead links; every entry is ping-tested live)
- ☁️ **Cloudflare Worker deploy wizard** — paste a Cloudflare API token (instructions in-app) and Cat Client spins up your *own* BPB/Zeus-style worker panel in seconds, then adds the resulting sub to the app
- 🛰️ **IP Scanner with SNI + Spoof** — built-in Cloudflare/Gcore CDN IP ranges + custom subnet input; TLS-ping each candidate with selectable SNI and fronting/spoof mode; export clean IPs straight into your configs
- 🌍 **Live globe + country detection** — connected IP, country name + flag, ISP and ping shown on the dashboard, with an animated arc from Iran to the destination country
- 📲 **Rich notification** — live up/down speed, connected config name, ping, flag
- 🎯 **Per-country "proxy profiles"** — like Zeus, pick a clean scanned IP per target country
- 🌓 **RTL support** with full Persian (فارسی) and English
- 🔔 **Quick-settings tile + home-screen widget**

## Build

Cat Client is a standard Gradle project.

Requirements:
- JDK 21
- Android SDK with platform 36, build-tools 36.0.0, NDK 27, CMake 3.22.1
- Go 1.23+ (to build the Mihomo native core)

```bash
./scripts/build-flclash-core.sh   # builds libclash.so for all ABIs
./gradlew assembleDebug            # debug APK at app/build/outputs/apk/debug/
```

The easiest way is just to push to this repo — GitHub Actions builds debug +
release APKs automatically and attaches them to a release.

## Cloudflare API Token (for in-app worker deploy)

The in-app "Deploy Worker" wizard needs a Cloudflare API token:

1. Log in to https://dash.cloudflare.com/ → **My Profile → API Tokens**
2. **Create Token** → choose **"Edit Cloudflare Workers"** template (recommended)
   or build a Custom token with at least:
   - Account → Workers Scripts → **Edit**
   - Account → Workers Subdomain  → **Read**
   - Account → Account Settings → **Read**
3. Leave Account Resources = **All accounts** (or select the target account)
4. Continue, Create, and copy the token.
5. Paste it into Cat Client → Settings → **Deploy Cloudflare Worker**.

Cat Client will upload a small worker (named `catclient-panel` by default) and
return a subscription URL you can add immediately.

## License

Cat Client is licensed under GPL-3.0 (inherited from the Mihomo core and the
original WhiteVPN base). See [LICENSE](LICENSE).
