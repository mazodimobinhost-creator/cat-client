# Shared Agent Log

- **2026-10-25 (Skill Setup Phase 1):** Installed specialist skills `01` through `14` from Google Drive folder `1Ib_oKMGA8a9M2Z5rg8c6-O6HyE8ml5rQ` into `.agents/skills/`.
- **2026-10-25 (Crash Hunter & Android Service Fixes):**
  - `CatClientApplication.kt`: Added `isSecondaryCarrierProcess()` guard so `:psiphon` secondary process exits early after `CrashWatch.install(this)` without double-initializing `WorkManager` or `PanelBackgroundRefreshWorker`.
  - `PsiphonCarrierService.kt` & `PsiphonCarrierClient.kt`: Added explicit `MSG_START = 4` / `MSG_STOP = 5` IPC messages over `Messenger` with `Context.BIND_IMPORTANT`, completed `startWaiter` on `onServiceDisconnected`, and guarded `startService` with `runCatching`.
  - `PsiphonCarrierConfig.kt`: Applied `Aether v2.4.0` chained Psiphon fixes (`"DisableTactics": true` to prevent non-root BPF `operation not permitted` failures, `CHAINED_TCP_TUNNEL_PROTOCOLS` restricting `LimitTunnelProtocols` to 11 TCP protocols without QUIC/INPROXY, `InproxyTunnelProtocolPreferProbability = 0.0`, `FrontedMeekCDNScanUseBuiltInSpec = true`).
  - `CatClientVpnService.kt`: Added defensive cleanup of existing `aetherSession`, `aetherPsiphonChain`, and `aetherTorCarrier` before starting or retrying a session.
  - `MainActivity.kt`: Guarded `AlertDialog.showCatClientDialog()` against `isFinishing || isDestroyed` (`BadTokenException`), replaced `.show()` calls with `.create().showCatClientDialog()`, made `quickControlsHost` and `guideBanner` collapsible with 44dp primary action buttons.
- **2026-10-25 (WhiteDNS Resolver & DNS Tunnel Integration — `v1.10.0-beta145` / `6.69.26`):**
  - `WhiteDnsSmartScanner.kt`: Added categorized `RESOLVER_CATALOG`, `WhiteDnsRecordMode` (`HTTPS/SVCB`, `TXT+CNAME`, `TXT+AAAA`, `NULL 1232B`, `All`), `IRAN_AUTO_TUNE_PRESETS`, `buildWhiteDnsTunnelBundle` (`cottendns://`, `stormdns://`, `masterdns://`, `soar://`, `dnstt`), and active UDP/TCP/DoT/Record probes.
  - `catclient.worker.js`: Added `#wdnsCard` inside `#v-scan`.
- **2026-10-25 (Dedicated SOCKS5 Hub & 3 Smart Tools — `v1.10.0-beta146` / `6.69.27`):**
  - `ProxyScannerPage.kt` & `MainActivity.kt`: Added dedicated SOCKS5 Live Hub (`showSocks5HubPage`), 7 live SOCKS5 feeds + 4-mirror `fetchFeedLinesWithMirrors` (with GitHub Contents API Base64 fallback) + 62 `BUILTIN_SOCKS5_SEEDS`, auto-refresh on open, 1-tap direct connect (`connectSocks5HitDirectly`) and smart failover pool connect (`connectSocks5PoolDirectly`).
  - `WhiteDnsSmartScanner.kt` & `MainActivity.kt`: Added `autoDetectBestWhiteDnsProfile` (Auto-MTU & Carrier Record Finder), `detectRegionalFirewallProfile` (Regional Firewall Fingerprinter), and Hybrid `🇮🇷+🔓 Iran IP + Auto Sanction-Bypass DNS` mode.
  - Published GitHub Release `v1.10.0-beta146` (`versionCode = 213` / Panel `6.69.27`).
- **2026-10-25 (Skill Setup Phase 2):** Installed the 5 newly added skills (`15-project-manager-reporter.SKILL.md`, `16-problem-analyst.SKILL.md`, `17-devops-cloudflare-guardian.SKILL.md`, `18-critics-panel.SKILL.md`, `19-overseer-advisor.SKILL.md`) from Google Drive into `.agents/skills/` and created `.agents/STATUS.md` (total: 19/19 skills active).
