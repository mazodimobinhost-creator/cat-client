# Changelog

All notable changes to Cat Client are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the app `versionName`. Detailed Persian release notes live in [`docs/release-notes-*.md`](docs/).

## [Unreleased]

### Fixed
- `wrangler.jsonc` carried a field Wrangler does not define on a KV binding (`name`), which the deploy logged as «Unexpected fields found in kv_namespaces[0]». Removed; the binding is `CAT_KV` and the place where a real 32-hex namespace id goes is a commented line with the exact dashboard path to copy it from. A made-up id in an active `id` field cannot resolve and breaks the deploy, so the file documents that instead of shipping one.
- The deployed upload was 31% larger than the committed artifact: `wrangler deploy` runs the entrypoint through esbuild, which escapes every non-ASCII character (Persian text, emoji) into `\uXXXX` — 602 KiB / 139.40 KiB gzipped instead of 458 KiB / 127.39 KiB. Both deploy paths (repo workflow and Cloudflare Workers Builds) now pass `--no-bundle`, so the bytes Cloudflare stores are the bytes the tests ran against.

### Changed
- Cat Panel (Worker 6.66.3): SOCKS5 relay configs are named «🧦 n. Relay» instead of carrying the proxy `host:port`. A subscription is shared around, and the name was the last place the relay address still travelled as plaintext.
- Panel version advances to 6.66.3 so a deployed panel can discover the update.

### Fixed
- An unknown or deleted `relay:<id>` no longer falls back to connecting directly with no proxy (fail-open). The worker now closes the connection with code `1008 relay unavailable` and records a `relay` error, so a broken relay fails visibly instead of silently exposing the client's own IP to the destination.
- Deleting a saved proxy therefore disables the configs that pointed at it — they stop working instead of quietly becoming unproxied connections. Documented in the README and in the panel's SOCKS5 tab.

## [1.10.0-beta93] — 2026-10-10

### Added
- Aether builder adds a separate WireGuard-over-MASQUE (new Gool over HTTP/2) link while keeping classic Gool (WireGuard-in-WireGuard) available.
- Clean-IP import guidance now covers plain WhiteDNS Scanner exports; the panel already accepts raw IP lists and also preserves optional verified ports and country tags.
- The Builder offers four optional, per-link BlueKnight v5.2.7 firewall profiles (MCI ECH, MCI IPv6, MCI IPv6 + F&F, and Irancell F&F) with an editable entry IP; selections do not change saved panel settings.
- Clean-IP scanner results are now a sortable table: one column each for address, country, ping, jitter and download speed, and tapping a column header re-sorts the whole list (speed high-to-low, everything else low-to-high; rows missing that value sink to the bottom).
- Clean-IP scanner multi-select: every row carries a checkbox, with «all / verified / none» chips and a live «n of m selected» counter. «Copy selected» puts `ip:port#CC` lines on the clipboard for pasting into Panel → Tools → Import scan results, and «send selected to panel» pushes exactly the ticked rows instead of the automatic top 60 (still only TLS-verified ones).
- Cat Panel «Cat Glass» skins (Worker 6.65): six selectable looks — Aurora (default), Nebula, Ocean, Ember, Forest and Steel — switched live from a picker in the sidebar and the mobile drawer. Every skin is a handful of CSS variables, and the wallpapers are layered CSS gradients, so the panel stays a single self-contained file with no image assets, no web fonts and no extra request. The choice is applied immediately, stored in a cookie for KV-less panels and saved to panel settings when KV exists, so the next load is server-rendered with no colour flash. The skin is cosmetic only: links, configs and subscriptions are untouched, and an unknown id falls back to the default. Design notes and the no-copy licensing rationale live in `docs/panel-themes.md`.
- Cloud tab: an explicitly opt-in installer for the third-party Cloudflare Worker shown in the TechNamooz video, pinned to one immutable upstream commit and verified by exact size and SHA-256 before any Cloudflare resource is created. It generates a random UUID, subscription path and KV namespace, refuses to overwrite an existing Worker name, and shows an explicit warning that the minified external bundle is unlicensed and unaudited, that hash-pinning is not a safety audit, and that no anti-ban or AI-access guarantee is made. The catalogue row also points back to the built-in Cat Panel controls.

### Changed
- Aether HTTP/2 links use PattNG's supported lowercase `fingerprint` key and native Aether `fragment` parameters; the classic `protocol=gool` link remains explicit and separate.
- BlueKnight F&F profiles emit the exact Xray-compatible `fm`/`cs` and Xray JSON `streamSettings.finalmask` values, keep the old Fragment outbound from stacking, and reject incompatible Clash/sing-box exports instead of silently dropping the profile.
- BPB-style clean-IP feed injection now defaults ON for new panels: up to 8 free feed IPs are appended after the owner's saved IPs in generated subscriptions, without being saved into the manual list. Legacy saved-off defaults are migrated once to ON unless the latest recorded `repo-auto` action was an explicit OFF; feed IPs are not represented as tested on the owner's network.
- Scanner-result paste preserves country tags and scan-pinned ports. Cat Client now formats scanned IPv6 entries as `[address]:port#CC`, so the panel can keep the verified port and country.
- Scanner results list shows up to 40 rows (was 24) — with per-row selection the old cap hid most of a scan.
- Panel UI refresh, written from scratch: glass surfaces with backdrop blur, translucent table rows and inputs, pill-shaped buttons, gradient numerals and accent rails on the dashboard stat cards, a gradient-hairline hero card, a restyled nav rail with an active indicator, and a rounded «wallpaper window» slot on the sidebar's inner edge. Browsers without `backdrop-filter` get opaque surfaces, and `prefers-reduced-motion` stops every animation.
- Panel Worker version advances to 6.66.2 so deployed panels can discover the update.
- Panel «data-display» pass (Worker 6.66.0), shaped against a public UI-pattern library (page header + toolbar, stat card with a trend pill, data table with a toolbar, empty state): view headers got a hairline divider; stat cards got a pill-shaped trend line, tabular numerals and an accent rail that matches each card's own colour; tables got card-shaped rows with rounded ends, zebra banding, a stronger hover with a violet inset rail and quieter uppercase headers (larger letter-spacing, bottom rule); the empty state got a glass icon bubble and a readable caption (both lines used to inherit the 40px icon size) inside a dashed frame; and status chips got a matching coloured dot. CSS only — no fonts, images or extra requests.

### Fixed
- IPv6 results now actually appear. Candidates are derived from the IPv4 pool through Cloudflare's dual-stack mapping (104.16.132.229 → `2606:4700::6810:84e5`, same address in `2a06:98c0::`) and from the /48 blocks that really answer; the old walk drew random addresses out of the whole `2606:4700::/32`, a 2^96 space where nearly every draw is dark — which is why v6 rows were always empty. The reachability probe also tries 443 and 80 on four hosts before giving up.
- Manual «add a single IP» accepts `ip:port`, `[v6]:port`, a bare v6 literal and host names. It used to strip only the square brackets, so any `ip:port` input kept its colon and the TCP connect failed on a bogus host — the box looked broken.
- Manual add now reports why an address failed: TCP did not open on that port, or TCP opened but no valid TLS answer came back for the chosen SNI (the address is still listed as TCP-only, with a badge, and is never sent to the panel).
- **The free clean-IP library no longer overtakes the owner's own addresses.** Library (repo-feed) IPs are pinned to `:443`, and the «pinned entries first» pass hoisted them ahead of the saved list — so on any panel with a populated feed cache the eight library addresses led the subscription and the owner's own IPs fell behind them. Pinned library entries now skip that pass and are picked up by the 443 wave in their normal position: still on `:443` only, still behind the owner's list, and `?norepo=1` still drops them.
- **Plain-port configs (80 / 8080) are back for normal-sized panels.** The subscription walk gave every port its own wave but let the first wave (443) consume the whole entry limit, so from about eight addresses onward the non-TLS configs silently disappeared — only panels with a handful of IPs ever saw them. Each wave now gets a fair share of the limit, and each wave starts where the previous one stopped, so the limit spreads across *different* addresses instead of repeating the same few on every port; address diversity is unchanged (12+ distinct addresses in a 48-entry sub) and the owner's own IPs still lead.
- A scan with no IPv6 now says so: the status line adds a hint to switch on «🌐 Force IPv6» instead of silently skipping v6.
- The Cloudflare deploy workflow now builds the exact entrypoint configured in `wrangler.jsonc` and verifies the live `/api/version`. Previously it obfuscated the readable source in place while Wrangler deployed the separate committed snapshot, and a stale snapshot could pass the generic `/health` check.
- The 1101 help card now describes the documented runtime-exception meaning and directs owners to Worker logs; it no longer labels every 1101 a ban or suggests switching accounts/subdomains.

### Changed / Security
- **Cat Deploy Bot (breaking):** `/deploy` now requires a stable `PANEL_UUID` Worker Secret. The bot copies that value to the panel Worker as the compatible `UUID` binding on every deploy and never mints a random UUID at deploy time, so re-deploying the bot can no longer silently rotate the panel secret and break every existing `/sub/<uuid>` link. If the secret is missing or invalid, the bot stops with a specific setup checklist before any Cloudflare or Telegram call. Bot version advances to 2.2.0; see `docs/deploy-bot.md` for generating the UUID once.

### Diagnostics
- User-save and subscription-preview errors now include the response Content-Type and Cloudflare `CF-Ray` when available. Raw response bodies remain hidden; this adds evidence for troubleshooting but does not resolve an HTTP 500.

### Tests
- Cover feed auto-on defaults and explicit opt-out, country-tagged scanner paste, IPv6 scan entries, safe HTTP error diagnostics, 1101 help copy, and deploy artifact/version verification.
- Add deploy-bot coverage for the `PANEL_UUID` gate (missing/invalid blocks every Cloudflare call), stable UUID reuse across updates, and legacy panels migrated to the pinned secret with a one-time subscription-link warning.
- Add Kotlin unit coverage for the third-party installer's pin/hash verification, worker-name and password validation, and the normal Xray subscription URL.
- Add panel regressions for the ordering traps behind both: a seeded feed cache (no network) asserting that the owner's IPs lead, the library IPs still appear on `:443` behind them, and `?norepo=1` still excludes them; and
- Add a panel regression for the shared entry limit: a 30-address panel must still emit `:80` and `:8080` entries, keep at least 12 distinct addresses, and stay inside the limit — the case that used to produce a 443-only subscription. The «manual IP leads the sub» check now compares positions in the emitted *address* order instead of raw text offsets, because a single default address may legitimately fall outside the window (and raw text also matched SNI values).
- Add `IpScannerIpv6Test` (Kotlin unit): the v4 → v6 mapping on both Cloudflare blocks, zero-padded hextets, derived addresses that really parse as IPv6, every declared v6 subnet being /48 or tighter, and each shape the manual box must accept (`ip`, `ip:port`, `[v6]:port`, bare v6, host:port) or reject (blank, non-numeric port).
- Add `scripts/panels/panel-theme.test.mjs` (wired into `npm test`): the inline stylesheet parses, all six skins are declared, the picker applies a skin live, writes the `catTheme` cookie, `PUT`s `uiTheme` to `/api/settings`, closes on backdrop/Esc, and the saved skin is server-rendered on the next load; an unknown skin id falls back to the default.
- Add worker regression coverage for all four firewall presets, exact VLESS/Trojan URI and Xray JSON values, no Fragment stacking, incompatible Clash rejection, and no settings persistence; extend Builder DOM checks for preset/IP wiring and saved-setting isolation.

## [1.10.0-beta92] — 2026-10-10

### Fixed
- Subscriptions no longer come back blank when the owner has no saved addresses and both default/worker-host switches are off: built-in addresses and the worker hostname are used as a render-only fallback, without changing saved settings.
- The config builder now reports HTTP/request failures and empty or malformed subscription responses instead of misdiagnosing every failure as «no clean IPs».
- The empty-IP hint distinguishes built-in fallback from repository feeds: clean-IP feed auto-append remains opt-in and fetched entries are not silently persisted into the owner's IP list.
- New-user save failures now show the HTTP status and server error detail (with UUIDs and URLs redacted) rather than a generic «error» toast. If the create succeeds but the follow-up user-list refresh fails, the panel says the user was saved and reports the refresh error separately.

### Tests
- Add worker regression coverage for empty-list fallback/non-persistence and the screenshot's exact new-user values; extend DOM tests for POST failure, post-save refresh failure, and successful/empty/failed subscription previews.

See [docs/release-notes-v1.10.0-beta92.md](docs/release-notes-v1.10.0-beta92.md).

## [1.10.0-beta91] — 2026-10-09

### Added
- Scanner: test Google, YouTube, Play Store, Telegram, WhatsApp and Instagram over the device's current network/VPN tunnel; show HTTP status, latency and separate DNS/TLS/timeout/server/rate-limit outcomes.
- Optional, user-initiated sharing of a redacted diagnostic report. Results remain on-device unless the user shares them; the report excludes IPs, UUIDs, subscription links, configs and credentials.
- Preserve the existing ChatGPT, Claude, Gemini API/Web and AI Studio checks, with clearer wording that an HTTP response alone does not prove the service is fully usable.

### Limits
- An endpoint response is not proof that every page, login, API or app feature works. This is a connectivity diagnostic, not a new transport or a guarantee of access.

See [docs/release-notes-v1.10.0-beta91.md](docs/release-notes-v1.10.0-beta91.md).

## [1.10.0-beta2] — 2026-10-01

### Added
- Network Cognitive Engine (phase A): per-endpoint × network observation history (median/p95/jitter/success/trend/confidence), failure forensics classes, adaptive probing intervals, multi-objective ranking of the clean-IP pool, anomaly-driven zero-downtime failover (verify standby twice → promote → reconnect) with a "Self-healing" toggle, and engine observability in the Scanner tab.
- Public proxy scanner (Scanner tab): collects candidates from public lists (monosans, proxifly), pasted IPs/`IP:Port`/CIDR ranges × port set; real SOCKS5 / SOCKS4 / HTTP CONNECT handshake probes with thread + timeout budget; country flags, protocol/country filters, copy / TXT / JSON export; one-tap import of healthy SOCKS5 proxies as configs.
- Theme presets (Lavender, Aurora, Ember, Midnight, Lime, Pink, Amber, Mono) applied to the whole app, including Material widgets and dialogs via per-preset theme overlays.
- "Vibrate on connect" toggle (Settings → Appearance).
- Country-aware self-healing: failover and pool replacements stay in the active (or locked) country; pool grouped by location with country-lock chips.
- Repository hygiene: `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `.editorconfig`, Dependabot, panel test workflow, Dockerfile for reproducible builds.

### Changed
- Home screen rebuilt to match the ZedSecure layout (hero blob, state label, FAB cluster, traffic tiles, dock) in both connected and disconnected states; Map and Speed Test pages.
- Servers screen, add-server sheet and Settings restyled (ZedSecure-like).

### Fixed
- Second FAB (Speed Test) clipped on the Home screen.
- RTL issues: round button jumping, dock label truncation, location pill shown while disconnected.

See [docs/release-notes-v1.10.0-beta2.md](docs/release-notes-v1.10.0-beta2.md).

## [1.10.0-beta1] — 2026-09

### Added
- Panel **v6.4**: lean rewrite (no KV traffic metering, no server-side scan), Zeus-style dashboard, users with expiry, `/sub` `/clash` `/singbox` `/xray` links, fixed-exit **chain** (SOCKS5/HTTP), country tags + preferred exit country with fallback groups, routing toggles (bypass Iran / block ads), opt-in Fragment / ALPN / cipher suites, **Telegram bot** for management.
- In-app setup wizard: Cloudflare token → deploy Worker + KV → import subscription → scan clean IPs.
- Scanner: real IPv6 probing, dual-stack scan/apply, hostnames, send results to the panel with country tags.
- Deploy-to-Cloudflare button and `wrangler.jsonc`.

See [docs/release-notes-v1.10.0-beta1.md](docs/release-notes-v1.10.0-beta1.md).

## Older releases

1.1.0 → 1.9.51: see the per-version notes in [`docs/`](docs/).

[Unreleased]: https://github.com/mazodimobinhost-creator/cat-client/compare/v1.10.0-beta92...HEAD
[1.10.0-beta92]: https://github.com/mazodimobinhost-creator/cat-client/compare/v1.10.0-beta91...v1.10.0-beta92
[1.10.0-beta91]: https://github.com/mazodimobinhost-creator/cat-client/compare/v1.10.0-beta90...v1.10.0-beta91
[1.10.0-beta2]: https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta2
[1.10.0-beta1]: https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta1
