# Handoff log — Arena agent · Grok · ملاد

Read `docs/COLLABORATION.md` first. Add the newest entry at the top. One entry per finished piece of work.

```
## YYYY-MM-DD — <who> — <short title>
- Version / commit: v1.10.0-betaNNN / <sha>
- Done: …
- Evidence: CI run / phone test (operator, device) / log keys
- Open / next owner: …
```

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
