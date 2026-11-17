# Smart Connection delivery roadmap

This document tracks verified implementation work. A checked item means code exists and CI has validated it; it does not imply physical-device validation.

## Phase 1 — Safe recommendation core
- [x] Per-network snapshot and freshness gates
- [x] Country automatic/prefer/lock policy
- [x] Reject captive, unvalidated, stale, port-only, and control-plane-only evidence
- [x] JVM policy tests

## Phase 2 — Real route scouting and application
- [x] End-to-end Direct HTTP probe
- [x] End-to-end Orbot SOCKS HTTP probe for the supported loopback endpoint
- [x] Worker control-plane diagnosis kept separate from tunnel success
- [x] Apply Direct, Orbot/DNSTT, or an existing profile only after confirmation
- [x] Run existing profile tests without stale country/type filters
- [x] Link clean-IP scanner and panel health/recovery flows
- [ ] CI validation of the latest MaterialSwitch fix (in progress)

## Phase 3 — Network history and resilient failover
- [ ] Bounded samples per network and route
- [ ] Success rate, median, p95, jitter, throughput, freshness, and failure streak
- [ ] Cross-route failover with cooldown, circuit breaker, live canary, and rollback
- [ ] UI showing active route, exit IP/country, reason, score, and last success

## Phase 4 — Protocol evidence
- [ ] TLS/HTTP data-plane verification for every TCP profile
- [ ] Real UDP/QUIC verification for TUIC and Hysteria2
- [ ] dnstt data-path verification
- [ ] Parser/import/runtime audits for Hysteria2, AnyTLS, Reality/XHTTP, WireGuard, AmneziaWG, WARP, and TUIC
- [ ] Outline `ss://` and `ssconf://` import, racing, and public-source trust warnings
- [ ] Orbot WebTunnel recovery bridge

## Phase 5 — Cloudflare and panel recovery
- [ ] Multi-probe diagnosis and binding/version mismatch reporting
- [ ] Preserve UUID, KV, D1, IOT_DB, and secrets during recovery
- [ ] Pinned redeploy, verify, rollback, and recovery history inside the APK
- [ ] Panel route status, config tests, exit metadata, backup/restore, and standard subscriptions

Cloudflare Workers can manage or publish external TUIC, Hysteria2, WireGuard, and OpenVPN servers, but cannot host those raw servers themselves.

## Phase 6 — Optional independent engines
Psiphon, Geph, MASQUE, and NaiveProxy remain gated on license, binary-size, lifecycle, clean shutdown, network-switching, and physical-device audits. They will not be presented as supported before those checks pass.

## Validation labels
- **CI verified:** automated compile/tests/build passed.
- **Runtime verified:** real end-to-end traffic passed in the implementation.
- **Device verified:** exercised on a physical Android device; currently no new engine may claim this label.
