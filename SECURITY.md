# Security Policy / سیاست امنیتی و گزارش آسیب‌پذیری

## Supported Versions / نسخه‌های پشتیبانی‌شده

| Component | Supported Version | Status |
|-----------|-------------------|--------|
| **Cat Client Android App** | `1.10.0-beta152` (`1.10.x`) | ✅ Supported |
| **Cat Panel Cloudflare Worker** | `6.69.30` (`6.69.x`) | ✅ Supported |
| **Cat Deploy & Healer Bot** | Latest release asset (`cat-deploy-bot.worker.js`) | ✅ Supported |
| Older releases (`< 1.10.0`) | — | ❌ Unsupported |

Re-deploy the Cloudflare Worker panel from the in-app wizard or update channel to stay on the latest `6.69.x` security baseline.

---

## Reporting a Vulnerability / نحوه گزارش آسیب‌پذیری

**Please do not open a public GitHub issue for security vulnerabilities.**
**لطفاً برای مشکلات امنیتی ایشوی عمومی باز نکنید.**

1. Use GitHub's private vulnerability reporting: **Security → Report a vulnerability** ([Security Advisories](https://github.com/mazodimobinhost-creator/cat-client/security/advisories/new)), or
2. Open a minimal issue titled only `[security] contact request` (without exploit details) so a maintainer can establish a private channel.

Please include:
- Affected component (`app/`, `aether/`, `catclient.worker.js`, `deploy-bot/`, or CI workflow)
- Exact version (`versionName` / `CAT_PANEL_VERSION`)
- Reproduction steps or proof-of-concept
- Potential impact (e.g., credential leak, SSRF, auth bypass, DNS/IP leak)

**Response timeline:**
- Initial acknowledgment within **7 days**.
- Confirmed high/critical fixes targeted within **14–30 days** and published in a new signed release with credit (if desired).

---

## Scope & Secret Hygiene / محدوده و حفاظت از کلیدها

- **In scope:**
  - Android client (`app/`), Rust Aether JNI bridge (`aether/`), subscription parsers, scanner engines, and Cloudflare API deployment flow.
  - Bundled Cloudflare Worker panel (`app/src/main/assets/panels/catclient.worker.js`) and setup wizard.
  - Standalone Telegram Deploy Bot (`deploy-bot/`).
- **Never commit secrets:**
  - `keystore.properties`, `secrets.properties`, Cloudflare API tokens, Telegram bot tokens, KV IDs, and private UUIDs must never be committed to Git. Use `keystore.properties.example` and `secrets.properties.example` as templates.
