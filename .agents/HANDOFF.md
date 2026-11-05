# Handoff log — Arena agent · Grok · ملاد

Read `docs/COLLABORATION.md` first. Add the newest entry at the top. One entry per finished piece of work.

```
## YYYY-MM-DD — <who> — <short title>
- Version / commit: v1.10.0-betaNNN / <sha>
- Done: …
- Evidence: CI run / phone test (operator, device) / log keys
- Open / next owner: …
```

## 2026-11-05 — Arena agent — beta240 (Panel 6.81.0)
- Version / commit: v1.10.0-beta240 / 8f97a76 — all CI runs green (push + PR), prerelease with 20 assets
- Done: Worker «boots but nothing connects» — 5 root causes fixed: DATA_PLANE_ENABLED missing in every CF deploy path
  (now set everywhere + in-panel 🔌 fix `/api/data-plane`), Blob WebSocket frames on compat ≥2026-03-17 (1002 on every
  handshake), no ProxyIP/SOCKS retry when a CF-hosted site closes a direct dial with 0 bytes (host:port cache 30 min),
  `/api/ip-test` marked good CF clean IPs dead (now `skip:'edge'` → tested in the owner's browser), VPN-viewer warning.
  Settings: sniffing (route-only), FakeDNS switch, system HTTP proxy (Android 10+), ping address (Google/Cloudflare).
- Evidence: `npm test` 159/159; `worker-realtraffic.test.mjs` 22/22 (real VLESS through miniflare; the beta239 worker
  fails it with 1002); Kotlin via CI (`Beta240CoreOptionsTest`).
- Open / next owner: ملاد — on an existing CF panel: update to 6.81.0, press 🔌 if the red banner shows, then connect a
  config on MCI + Irancell; toggle Sniffing / FakeDNS off / HTTP proxy and report any app that breaks.
  Grok — review with `scripts/review-worktree.sh 8f97a76 --base 938cf99`. Still open: chain bug 4 (silent link→direct
  fallback in `all` mode); curated Iran-tested CF IP source (own implementation).

## 2026-11-05 — Arena agent — beta239 released
- Version / commit: v1.10.0-beta239 / a7439a5 — all 6 CI runs green, prerelease with 20 assets
- Done: Fox 406 fix (challenge solved on the host that raised it, same User-Agent as the retried call, per-host cookies,
  Firefox 159 + MozillaVPN identity), Telegram MTProto finder (real FakeTLS handshake test), Hysteria v1 + hy2 pin/port
  hopping, subscription provider headers (title / announcement / support / page / refresh interval).
- Evidence: CI (JUnit incl. an in-process FakeTLS proxy test); Fox NOT phone-tested yet (sandbox cannot reach Mozilla).
- Open / next owner: ملاد — phone test Fox sign-in + connect on MCI and Irancell (report the exact Persian error if any);
  test «✈️ پروکسی تلگرام» on both operators. Grok — review with `scripts/review-worktree.sh a7439a5 --base e1dd27b`.

## 2026-11-05 — Arena agent — beta238 released
- Version / commit: v1.10.0-beta238 / 9fe0fc2 — all CI runs green, release with 20 assets
- Done: Fox login over the official Mozilla WebChannel (no 406, verified sessions only), Zerops/Seenode/Hostim host profiles,
  encrypted DNS (DoH/DoT) scan with poisoning check, `scripts/review-worktree.sh`, this collaboration file.
- Open / next owner: ملاد — phone tests #4, #5, #6, #8 on the task board; Grok — review beta238 with `scripts/review-worktree.sh`.
