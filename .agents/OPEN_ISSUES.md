# Open Issues & Field Verification Checklist

## Resolved in `v1.10.0-beta145` / Panel `v6.69.26`
- [x] Secondary process `:psiphon` double-initializing `WorkManager` (`CatClientApplication.kt`).
- [x] `PsiphonCarrierService` background start race and disconnected waiter leak (`PsiphonCarrierClient.kt` / `PsiphonCarrierService.kt`).
- [x] Chained Psiphon BPF `operation not permitted` and QUIC-over-SOCKS5 dial failures (`PsiphonCarrierConfig.kt`).
- [x] `AlertDialog` `BadTokenException` when activity finishes during async scan (`MainActivity.kt`).
- [x] `?fam=v6` returning empty subscription when panel has no manual IPv6 entries (`catclient.worker.js`).
- [x] `paste-artifact.test.mjs` gzip budget and `panel-click-audit.mjs` zero dead elements.

## Remaining Field Verification (Requires Real Device / Carrier SIM)
- [ ] Verify `WARP -> Psiphon` (`DisableTactics: true` + 11 TCP protocols) connection time on MCI and Irancell SIM cards in `v1.10.0-beta145` APK.
- [ ] Verify `WhiteDNS` `HTTPS/SVCB` vs `NULL` carrier record throughput on regional TCI/Shatel vs MCI/Irancell resolvers.
