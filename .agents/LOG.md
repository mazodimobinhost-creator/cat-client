# Shared Agent Log

- **2026-10-25 (Skill Setup Phase 1 & 2):** Installed all 19 specialist skills (`01` through `19`) from Google Drive folder `1Ib_oKMGA8a9M2Z5rg8c6-O6HyE8ml5rQ` into `.agents/skills/` and created `.agents/STATUS.md`.
- **2026-10-25 (Crash Hunter & Android Service Fixes — `v1.10.0-beta145` / `6.69.26`):**
  - `CatClientApplication.kt`, `PsiphonCarrierService.kt`, `PsiphonCarrierClient.kt`, `PsiphonCarrierConfig.kt`, `CatClientVpnService.kt`, `MainActivity.kt`: Fixed `:psiphon` secondary process isolation, Messenger IPC start, `DisableTactics: true` + 11 TCP protocols for chained Psiphon, and `BadTokenException` dialog guards.
- **2026-10-25 (Dedicated SOCKS5 Hub & 3 Smart Tools — `v1.10.0-beta146` / `6.69.27`):**
  - `ProxyScannerPage.kt`, `WhiteDnsSmartScanner.kt`, `MainActivity.kt`, `catclient.worker.js`: Added Dedicated SOCKS5 Live Hub (7 live feeds + 4-mirror GitHub API fallback + 62 built-in seeds + 1-tap direct & pool connect), Regional Firewall Fingerprinter, Auto-MTU & Carrier Record Finder, and Hybrid Iran IP + Auto Sanction-Bypass DNS.
- **2026-10-25 (Complete 6 Remaining Items — `v1.10.0-beta147` / `6.69.28`):**
  - Native in-app parsing & local SOCKS5 bridge execution for `cottendns://`, `stormdns://`, `masterdns://`, `soar://`, and `dnstt://` (`WhiteDnsSmartScanner.kt`, `SubConvConverter.kt`, `MihomoShareLink.kt`, `MainActivity.kt`).
  - Irancell `F&F` (`finalMask=tlshello-0-len`, `cipherSuites=semi-python`) preservation in `SubConvConverter.kt`, `UserSubscriptions.kt`, `MihomoShareLink.kt` and TLS ClientHello record splitter (`splitTlsClientHelloRecord`).
  - Server-side `RFC 1928` SOCKS5 handshake testing (`kind: 'socks'`) in `/api/ip-test`, `data-sk-test`, and `#skLiveFetch` in `catclient.worker.js`.
  - Aether Smart Conditional Chain (`auto-psiphon`) and Top-10 Per-Carrier Clean Endpoint Pool with automatic failover (`AetherPreferenceStore.kt`, `CatClientVpnService.kt`, `MainActivity.kt`).
  - Fallback Worker Domain (`fallbackHost` in `catclient.worker.js` and `SubscriptionSourceLoader.candidateHttpsUrls` in `SubscriptionSnapshot.kt`).
- **2026-10-25 (6 Dr.Net-Inspired Capabilities — `v1.10.0-beta148` / `6.69.28`):**
  - Mid-Stream Freeze & Throttling Detector (`evaluateMidStreamTransfer`, `probeMidStreamFreezeAndThroughput`, `runMidStreamFreezeDetectorDialog`).
  - Domain Fronting & Unpoisoned Real IP Finder (`isPoisonedIranDnsIp`, `resolveUnpoisonedRealIp`, `showDomainFrontingAndUnpoisonResolverDialog`).
  - Google Apps Script (`GAS`) Relay (`gasrelay://` URI scheme, `buildGoogleAppsScriptRelaySource`, `buildGasRelayUri`, daily 20k quota tracker in `VpnRuntimeStateStore`, and `showGoogleAppsScriptRelayDialog`).
  - Cat Client Lite Worker Generator (`buildLiteWorkerScript` with zero-KV quota, Irancell F&F, MCI IPv6, and explicit-port ProxyIP).
  - Independent Auto-Reconnect on Drop, Auto-Connect on Boot/Update (both default off), and Universal Kill Switch (`VpnRuntimeStateStore`, `showConnectionResilienceAndKillSwitchDialog`).
  - Step-by-Step Live Connection Progress (`VpnRuntimeStateStore.saveLiveConnectStep` in `CatClientVpnService.kt`).
