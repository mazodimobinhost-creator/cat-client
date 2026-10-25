# Shared Agent Log

- **2026-10-25 (Skill Setup):** Installed all 14 specialist skills (`01` through `14`) from Google Drive folder `1Ib_oKMGA8a9M2Z5rg8c6-O6HyE8ml5rQ` into `.agents/skills/`.
- **2026-10-25 (Crash Hunter & Android Service Fixes):**
  - `CatClientApplication.kt`: Added `isSecondaryCarrierProcess()` guard so `:psiphon` secondary process exits early after `CrashWatch.install(this)` without double-initializing `WorkManager` or `PanelBackgroundRefreshWorker`.
  - `PsiphonCarrierService.kt` & `PsiphonCarrierClient.kt`: Added explicit `MSG_START = 4` / `MSG_STOP = 5` IPC messages over `Messenger` with `Context.BIND_IMPORTANT`, completed `startWaiter` on `onServiceDisconnected`, and guarded `startService` with `runCatching`.
  - `PsiphonCarrierConfig.kt`: Applied `Aether v2.4.0` chained Psiphon fixes (`"DisableTactics": true` to prevent non-root BPF `operation not permitted` failures, `CHAINED_TCP_TUNNEL_PROTOCOLS` restricting `LimitTunnelProtocols` to 11 TCP protocols without QUIC/INPROXY, `InproxyTunnelProtocolPreferProbability = 0.0`, `FrontedMeekCDNScanUseBuiltInSpec = true`).
  - `CatClientVpnService.kt`: Added defensive cleanup of existing `aetherSession`, `aetherPsiphonChain`, and `aetherTorCarrier` before starting or retrying a session.
  - `MainActivity.kt`: Guarded `AlertDialog.showCatClientDialog()` against `isFinishing || isDestroyed` (`BadTokenException`), replaced `.show()` calls with `.create().showCatClientDialog()`, made `quickControlsHost` in `buildAntiCensorshipEnginesCard()` and `guideBanner` in `showAddAetherDialog()` collapsible with 44dp primary action buttons.
- **2026-10-25 (WhiteDNS Resolver & DNS Tunnel Integration):**
  - `WhiteDnsSmartScanner.kt`: Added 52-server categorized `RESOLVER_CATALOG` (Iranian Unblock/Gaming, Domestic ISP, International DoQ/DoH3/DoT/DoH), `WhiteDnsRecordMode` (`HTTPS/SVCB`, `TXT+CNAME`, `TXT+AAAA`, `NULL 1232B`, `All`), 9 `IRAN_AUTO_TUNE_PRESETS`, `buildWhiteDnsTunnelBundle` (`cottendns://`, `stormdns://`, `masterdns://`, `soar://`, `dnstt`), and active UDP/TCP/DoT/Record probes.
  - `catclient.worker.js` (`v6.69.26`): Added `#wdnsCard` (WhiteDNS Resolver & DNS Tunnel Lab) inside `#v-scan`, fixed `?fam=v6` empty subscription fallback, and wired `#kvWarnOk` dismissal attribute.
- **2026-10-25 (Verification & Live Preview):**
  - `npm test`: 116/116 tests passed.
  - `panel-click-audit.mjs`: 221 elements across 10 views audited; 0 load errors, 0 thrown clicks, 0 dead elements.
  - `builder-link-repro.mjs`: All 27 subscription & token-rotation variants passed.
  - `ui-preview-audit.test.mjs`: All 15 preview checks passed.
  - Live preview server started on `0.0.0.0:8787`.
