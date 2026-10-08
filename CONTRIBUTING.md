# Contributing to Cat Client

Thanks for helping! Cat Client is an Android VPN client built on the Mihomo (Clash Meta) core, plus a Cloudflare Worker panel. Contributions in **English or Persian** are welcome.

## Quick start

```bash
git clone https://github.com/mazodimobinhost-creator/cat-client
cd cat-client
./gradlew :app:assembleDebug        # Android app (JDK 21, Android SDK 35, Go for the core)
npm install && npm test             # Panel / wizard unit tests (Node 22)
```

See [BUILDING.md](BUILDING.md) for toolchain details and [docs/](docs/) for design notes and release notes.

## Project layout

| Path | What |
|------|------|
| `app/src/main/java/com/cat/client/` | Android app (Kotlin, classic Views — no Compose) |
| `app/src/main/assets/panels/catclient.worker.js` | Cloudflare Worker panel (single file, deployed by the app) |
| `app/src/main/assets/panels/catclient.wizard.js` | In-app setup wizard page |
| `scripts/panels/*.test.mjs` | Node tests for the panel/wizard |
| `scripts/build-flclash-core.sh` | Builds the Mihomo core |
| `docs/` | ADRs, specs, release notes |

## Workflow

1. Fork → branch from `main` (`feat/…`, `fix/…`, `panel/…`).
2. Keep PRs focused; one feature or fix per PR.
3. Make sure CI is green: **Cat Client Android Build** and **Panel tests**.
4. Describe *what* and *why* in the PR; add screenshots for UI changes (light + dark, LTR + RTL/Persian).
5. Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md).

## Code style

- Kotlin: official style, 4-space indent, no wildcard imports; prefer explicit `View` code over XML layouts (matches the codebase).
- JavaScript (panel): ES2022, 2-space indent, no build step — the Worker must stay a single self-contained file.
- Strings: every user-facing string goes in `res/values/strings.xml` **and** `res/values-fa/strings.xml`. Check for duplicate names before committing.
- Formatting rules live in [.editorconfig](.editorconfig).

## Commit messages

`area: short imperative summary` — e.g. `panel: cache subscription responses`, `scanner: add SOCKS4 probe`, `ui: fix RTL dock labels`.

## Panel changes

- Bump `CAT_PANEL_VERSION` in `catclient.worker.js` for any user-visible change.
- Never add per-request KV writes (free-tier limits) — see `docs/panel-v6-why-no-ban.md`.
- Run `npm test`; add a case to `scripts/panels/cat-panel.test.mjs` for new endpoints.

## Reporting bugs

Open an issue with: app version (Settings → About), Android version, device, steps, and logs if possible (Settings → Logs). For security issues see [SECURITY.md](SECURITY.md).

## License

By contributing you agree your work is licensed under the repository [LICENSE](LICENSE). Keep third-party attributions in [NOTICE](NOTICE) up to date.
