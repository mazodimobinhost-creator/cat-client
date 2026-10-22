# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| latest release / pre-release (`1.10.x`) | ✅ |
| older releases | ❌ |

Panel (`app/src/main/assets/panels/catclient.worker.js`): only the version bundled with the latest app release (currently **6.4.x**) receives fixes. Re-deploy from the in-app wizard to upgrade.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

- Use GitHub's private reporting: **Security → Report a vulnerability** on this repository, or
- Open an issue with only the title `[security] contact request` (no details) and a maintainer will reach out privately.

Include: affected component (app / panel Worker / Telegram bot / scanner), version, steps to reproduce, and impact. You should get a first response within **7 days**; fixes for confirmed high-impact issues are targeted within **30 days** and shipped as a new release with credit (if you want it).

## Scope

- Android app (`app/`) — VPN service, config import, scanner, Cloudflare deployment flow.
- Cloudflare Worker panel and setup wizard (`app/src/main/assets/panels/`).
- Release/CI workflows (`.github/workflows/`).

Out of scope: issues in upstream Mihomo/Clash Meta core, Cloudflare platform behaviour, or third-party proxy lists.

## Secrets

Never commit keystores, Cloudflare API tokens, Telegram bot tokens or panel passwords. `keystore.properties` / `secrets.properties` are git-ignored; use the `*.example` templates. If you believe a secret leaked, report it as above so it can be rotated.
