# Handoff log — Arena agent · Grok · ملاد

Read `docs/COLLABORATION.md` first. Add the newest entry at the top. One entry per finished piece of work.

```
## YYYY-MM-DD — <who> — <short title>
- Version / commit: v1.10.0-betaNNN / <sha>
- Done: …
- Evidence: CI run / phone test (operator, device) / log keys
- Open / next owner: …
```

## 2026-11-05 — Arena agent — beta241 (Cat Warp on other people's lines)
- Version / commit: v1.10.0-beta241 / 2fd70e9 (code) + 88748b0 (test fix, brief) — all 8 CI runs green, prerelease 20 assets
- Done: «Cat Warp configs the app builds only work on my own internet». Cause: wizard configs carry the builder line's
  endpoint, IP family (IPv6) and mode (WireGuard/UDP); on another line the service dialled exactly that, each fallback
  stage with 180 s, and the link endpoint was saved into the new network's memory BEFORE trying it. Fix:
  `AetherNetworkPlanner` (pure) + `runAetherStartup`: IPv6→IPv4 when the underlying network has no IPv6 (IPv6 endpoints
  dropped), endpoint verified on this network first, link endpoint as a 60 s hint, else core search; learned working mode
  per network (7 days); endpoint/mode remembered only after `Started` from the peer the core really used
  (`AetherSession.lastPeer`); old memories not trusted (`netpool2_`/`netmem2_`); duplicate discovery stage skipped.
  Also: `panel-680` `/api/ech-pool` test made deterministic; `docs/REVIEW-BRIEF.md` for Claude/Grok.
- Evidence: `Beta241AetherNetworkPlanTest`; planner run in a local kotlinc harness (6 cases); `npm test` 159/159; CI green.
  NOT phone-tested. Log key on the phone: `aether.plan` (net=… mode/ip/endpoint/budget + reasons), `aether.plan.learned`.
- Open / next owner: ملاد — build a Cat Warp config on MCI, connect it on Irancell and on a Wi-Fi without IPv6 (and on
  a friend's phone); send the «کپی گزارش عیب‌یابی» lines `aether.plan*`. Claude/Grok — full review per `docs/REVIEW-BRIEF.md`.

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
