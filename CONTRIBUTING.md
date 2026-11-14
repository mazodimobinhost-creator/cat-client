# Contributing to Cat Client / راهنمای مشارکت در Cat Client

Thanks for helping! **Cat Client** is an Android VPN & anti-censorship client built on the **Mihomo (Clash Meta)** core and the **Aether (WARP / MASQUE-HTTP/2 / Psiphon / WireGuard-over-MASQUE / Orbot-Tor / dnstt)** engine, paired with the **Cat Panel** Cloudflare Worker (`v6.69.30`). Contributions in **Persian (فارسی)** or **English** are welcome.

ممنون از مشارکت شما! پروژه **Cat Client** شامل اپلیکیشن اندروید (مبتنی بر هسته Mihomo و موتور ضدسانسور Aether) و پنل کلودفلر ورکر (**Cat Panel**) است. مشارکت‌ها به هر دو زبان **فارسی** و **انگلیسی** پذیرفته می‌شوند.

---

## Quick Start / شروع سریع

```bash
git clone https://github.com/mazodimobinhost-creator/cat-client
cd cat-client
python3 scripts/android-startup-guard.py   # Verify no constructor-time Context access
npm install && npm test                    # Panel / wizard / guard unit tests (Node 22+)
./gradlew :app:assembleDebug               # Android app (JDK 21, Android SDK 36, Go & Rust for cores)
```

See [BUILDING.md](BUILDING.md) for toolchain details and [docs/](docs/) for design notes and Persian/English walkthroughs.

---

## Project Layout / ساختار پروژه

| Path | Description |
|------|-------------|
| `app/src/main/java/com/cat/client/` | Android app (Kotlin, programmatic classic `View`s — no Compose) |
| `aether/` | Rust JNI anti-censorship core (WARP, MASQUE-HTTP/2, Psiphon, Gool) |
| `app/src/main/assets/panels/catclient.worker.js` | Cloudflare Worker panel (`v6.69.30`, single self-contained file) |
| `app/src/main/assets/panels/catpanel.min.js` | 3-line minified Worker paste artifact for Cloudflare dashboard |
| `app/src/main/assets/panels/catclient.wizard.js` | In-app setup wizard script |
| `deploy-bot/` | Standalone Cloudflare Worker Telegram Deploy & 1101 Healer Bot |
| `scripts/panels/*.test.mjs` | Node.js & workerd integration and static-guard test suites |
| `docs/` | Architecture notes, circumvention research, and release notes |

---

## Workflow / روند ارسال تغییرات

1. Create a focused branch for your feature or bugfix (`feat/…`, `fix/…`, `panel/…`).
2. Keep pull requests focused: one logical feature or fix per PR.
3. Run the verification suite before pushing:
   - `python3 scripts/android-startup-guard.py`
   - `npm test`
   - `./gradlew testDebugUnitTest`
4. Describe *what* changed and *why* in the PR description; include screenshots for UI changes (Light + Dark mode, RTL Persian + LTR English).
5. Add an entry under **`[Unreleased]`** in [CHANGELOG.md](CHANGELOG.md).

---

## Code Style & Guardrails / قواعد کدنویسی

- **Kotlin (Android):**
  - 4-space indentation, no wildcard imports, explicit programmatic `View` hierarchy (matching `MainActivity.kt` and `DashboardViews.kt`).
  - **Startup Guard:** Never call `Context` methods (`getSharedPreferences`, `resources`, `View(this)`, etc.) inside `Activity` or `Application` property initializers; use `by lazy`, `lateinit`, or initialize inside `onCreate()`.
  - **Theme & Palette:** Always read colors from `CatClientDesignTokens.forContext(context)` (`palette`) so Light, Dark, System, and all 8 `AppAccent` presets work seamlessly.
- **JavaScript (Cat Panel Worker):**
  - ES2022, 2-space indentation.
  - Bump `CAT_PANEL_VERSION` across `catclient.worker.js`, `deploy-bot/worker.js`, `tools/cat-fixer.html`, `cat-panel.html`, and version tests whenever changing panel behavior, then regenerate `dist-panel/` and `app/src/main/assets/panels/catpanel.min.js` via `node scripts/panels/minify.mjs`.
- **Localization:**
  - Every user-facing string resource must exist in both `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-fa/strings.xml` (Persian) with identical format placeholders.
- **Formatting:** See [.editorconfig](.editorconfig).

---

## Reporting Bugs & Security / گزارش باگ و امنیت

- **Bug reports:** Open a GitHub Issue with your app version, Android version, device model, carrier/firewall type, steps to reproduce, and diagnostic logs.
- **Security vulnerabilities:** Please do **not** open a public issue. Follow the instructions in [SECURITY.md](SECURITY.md).

## License / مجوز

By contributing to Cat Client, you agree that your contributions will be licensed under the repository's [GPL-3.0 LICENSE](LICENSE). Keep third-party attributions in [NOTICE](NOTICE) up to date.
