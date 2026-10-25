# Open Issues & Field Verification Checklist

## Resolved up to `v1.10.0-beta147` / Panel `v6.69.28`
- [x] Secondary process `:psiphon` double-initializing `WorkManager` (`CatClientApplication.kt`).
- [x] `PsiphonCarrierService` background start race and disconnected waiter leak (`PsiphonCarrierClient.kt` / `PsiphonCarrierService.kt`).
- [x] Chained Psiphon BPF `operation not permitted` and QUIC-over-SOCKS5 dial failures (`PsiphonCarrierConfig.kt`).
- [x] `AlertDialog` `BadTokenException` when activity finishes during async scan (`MainActivity.kt`).
- [x] `?fam=v6` returning empty subscription when panel has no manual IPv6 entries (`catclient.worker.js`).
- [x] `paste-artifact.test.mjs` gzip budget and `panel-click-audit.mjs` zero dead elements.
- [x] Native parsing & in-app execution of `cottendns://`, `stormdns://`, `masterdns://`, `soar://`, and `dnstt://` via local SOCKS5 bridge (`WhiteDnsSmartScanner.kt`, `SubConvConverter.kt`, `MihomoShareLink.kt`, `MainActivity.kt`).
- [x] In-app Irancell `F&F` (`finalMask=tlshello-0-len` & `cipherSuites=semi-python`) preservation and TLS ClientHello record splitter (`WhiteDnsSmartScanner.splitTlsClientHelloRecord`, `SubConvConverter.kt`, `UserSubscriptions.kt`, `MihomoShareLink.kt`).
- [x] Real `RFC 1928` SOCKS5 handshake testing (`kind: 'socks'`) and object-map result handling in `#skLiveFetch` / `data-sk-test` (`catclient.worker.js`).
- [x] Smart Conditional Chain (`auto-psiphon`: test direct exit first, escalate to `WARP -> Psiphon` only when sanctioned/blocked) (`AetherPreferenceStore.kt`, `CatClientVpnService.kt`, `MainActivity.kt`).
- [x] Top-10 Per-Carrier Ranked Clean Endpoint Pool (`rememberEndpointPoolForNetwork` / `recallEndpointPoolForNetwork` / `nextFailoverEndpointForNetwork`) with automatic failover in `CatClientVpnService.kt`.
- [x] Fallback Worker Domain (`fallbackHost` in `catclient.worker.js` and `SubscriptionSourceLoader.candidateHttpsUrls` in `SubscriptionSnapshot.kt`).
