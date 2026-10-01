# Changelog

All notable changes to Cat Client are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the app `versionName`. Detailed Persian release notes live in [`docs/release-notes-*.md`](docs/).

## [Unreleased]

### Added
- Public proxy scanner (Scanner tab): collects candidates from public lists (monosans, proxifly), pasted IPs/`IP:Port`/CIDR ranges × port set; real SOCKS5 / SOCKS4 / HTTP CONNECT handshake probes with thread + timeout budget; country flags, protocol/country filters, copy / TXT / JSON export; one-tap import of healthy SOCKS5 proxies as configs.
- Theme presets (Lavender, Aurora, Ember, Midnight, Lime, Pink, Amber, Mono) applied to the whole app, including Material widgets and dialogs via per-preset theme overlays.
- "Vibrate on connect" toggle (Settings → Appearance).
- Repository hygiene: `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `.editorconfig`, Dependabot, panel test workflow, Dockerfile for reproducible builds.

### Changed
- Home screen rebuilt to match the ZedSecure layout (hero blob, state label, FAB cluster, traffic tiles, dock) in both connected and disconnected states; Map and Speed Test pages.
- Servers screen, add-server sheet and Settings restyled (ZedSecure-like).

### Fixed
- Second FAB (Speed Test) clipped on the Home screen.
- RTL issues: round button jumping, dock label truncation, location pill shown while disconnected.

## [1.10.0-beta1] — 2026-09

### Added
- Panel **v6.4**: lean rewrite (no KV traffic metering, no server-side scan), Zeus-style dashboard, users with expiry, `/sub` `/clash` `/singbox` `/xray` links, fixed-exit **chain** (SOCKS5/HTTP), country tags + preferred exit country with fallback groups, routing toggles (bypass Iran / block ads), opt-in Fragment / ALPN / cipher suites, **Telegram bot** for management.
- In-app setup wizard: Cloudflare token → deploy Worker + KV → import subscription → scan clean IPs.
- Scanner: real IPv6 probing, dual-stack scan/apply, hostnames, send results to the panel with country tags.
- Deploy-to-Cloudflare button and `wrangler.jsonc`.

See [docs/release-notes-v1.10.0-beta1.md](docs/release-notes-v1.10.0-beta1.md).

## Older releases

1.1.0 → 1.9.51: see the per-version notes in [`docs/`](docs/).

[Unreleased]: https://github.com/mazodimobinhost-creator/cat-client/compare/v1.10.0-beta1...HEAD
[1.10.0-beta1]: https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta1
