# Open Issues & Resolved Defects (`OPEN_ISSUES.md`)

| ID | Severity | Location | Symptom | Root Cause | Status | Test / Verification |
|---|---|---|---|---|---|---|
| ISS-01 | Critical | `NativeAetherBridge.kt`, `app/proguard-rules.pro` | `NoSuchMethodError: no non-static method "LA0/l;.protectSocket(I)Z"` when starting Aether VPN in release APK | R8 full mode stripped `protectSocket(I)Z` on synthetic SAM lambda class | Closed (`v1.10.0-beta144`) | `WhiteDnsSmartScannerTest.proguardRulesKeepAetherJniCallbacksAgainstR8Stripping` + Release APK CI build |
| ISS-02 | High | `AetherPreferenceStore.kt` | Validated WireGuard tunnel in PattNG disconnects with `the exit location could not be read, so the policy cannot be honoured; reconnecting` | `exit_loc=!IR` in `aether://` URI triggered `exitloc::settle` on single-hop `wg` before Psiphon | Closed (`v1.10.0-beta144`) | `AetherSelectionTest.aetherUriRoundTripsForIranForeignAndChainConfigs` |
| ISS-03 | High | `PsiphonCarrierService.kt`, `PsiphonCarrierConfig.kt` | Psiphon chain fails on fresh install or when SIM lacks IPv6 route (`host has no IPv6 route`) | Empty `startTunneling("")` bootstrap list and no fallback from `ip=v6` to `ip=both` | Closed (`v1.10.0-beta144`) | `AetherRuntimeConfigTest` + CI step `Stage verified Psiphon bootstrap server entries` |
