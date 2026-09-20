# Cat Client

**A modern, clean & beautiful Android VPN / Proxy client**  
Black • White • Deep Purple accents • Dark & Light mode

> کلاینت اندروید مدرن، ساده و زیبا با تم مشکی/سفید و اکسنت بنفش

---

## Vision

Cat Client aims to be a polished, user-friendly client inspired by the best open-source projects in the community (ArasClient, WhiteVPN, v2rayNG, V2Box style UX).

**Core goals:**
- Beautiful UI (Jetpack Compose)
- Full protocol support: VLESS, VMess, Trojan, Shadowsocks, Hysteria2, ...
- Subscription + single config import
- Smart Connect (auto select lowest ping)
- Country flag + real IP country detection
- Connection globe animation (Iran → destination)
- Real-time speed + ping in notification
- Dark / Light theme + customizable purple accents
- Persian + English
- Clean IP / SNI scanner tools
- Free configs section (external source, always updated)
- No branding of other panels inside the app

---

## Important Reality Check

Building a **fully working production VPN client** from zero is a large project (native cores, VpnService, routing, battery optimization, etc.).

The recommended professional approach is:

1. **Fork a mature open-source client** as base:
   - [ArasClient](https://github.com/ArasTey/ArasClient) (highly recommended — modern, fast, feature-rich)
   - [WhiteVPN](https://github.com/WhiteDNS/WhiteVPN)
   - [v2rayNG](https://github.com/2dust/v2rayNG)

2. Change branding to **Cat Client** (name, icon, colors, package name)
3. Add the extra features you want on top of a working core

This repository currently contains the project foundation and documentation.

---

## Planned Features

### Connection & Configs
- [ ] Import single configs (VLESS / VMess / Trojan / SS / Hy2 ...)
- [ ] Subscription links (auto update)
- [ ] Smart Connect (ping all + connect fastest)
- [ ] Real delay test + speed test
- [ ] Share / Export configs

### UI / UX
- [x] Dark & Light mode
- [ ] Custom purple accent color picker
- [ ] Country flag next to server
- [ ] Globe + connection line animation
- [ ] Beautiful notification with upload/download speed + current server name
- [ ] Persian / English language switch

### Tools
- [ ] Clean IP Scanner (with SNI / Spoof support)
- [ ] Free configs section (fetched from external source, only healthy ones)
- [ ] Subnet / custom IP list input for scanner

### Panel Integration (optional)
- User can paste Cloudflare API Token → deploy their own Worker panel → get private subscription
- No third-party panel names shown inside the app

---

## App Icon Concept

Black & white cat + purple lightning bolt.

---

## Development Status

This repo is the **starting point**.  
Real development should begin by forking one of the mature clients listed above and renaming it to Cat Client.

---

## License

GPL-3.0 (same as most community clients)

---

Made with ♥ for the community
