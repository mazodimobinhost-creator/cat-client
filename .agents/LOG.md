# Engineering Log (`LOG.md`)

## 2026-10-25 — `v1.10.0-beta144` (`versionCode = 211`)
- **Bug Hunter & Android Programmer**:
  - Diagnosed `catclient-crash (2).txt` (`java.lang.NoSuchMethodError: no non-static method "LA0/l;.protectSocket(I)Z"` at `NativeAetherBridge.nativePrepare`): R8 full mode renamed/stripped the SAM lambda method when `CatClientVpnService` passed `NativeSocketProtector` to JNI.
  - Wrapped `NativeSocketProtector` and `NativeEngineListener` in concrete `@Keep` bridge classes (`JniSocketProtectorBridge`, `JniEngineListenerBridge`) in `NativeAetherBridge.kt` and added explicit `-keep` rules in `app/proguard-rules.pro` plus regression test `proguardRulesKeepAetherJniCallbacksAgainstR8Stripping`.
- **Network/Iran Specialist**:
  - Diagnosed `Screenshot_20261022_142725_PattNG (F-Droid).jpg` (`WireGuard tunnel ended: other: the exit location could not be read, so the policy cannot be honoured; reconnecting`): `--exit-loc !IR` runs `exitloc::settle` on the raw WARP tunnel before SOCKS5 / Psiphon. Updated `AetherSelection.toAetherUri()` and `fromAetherUri()` to match `patterniha/PattNG` (`AetherFmt.kt`) without emitting `exit_loc`.
  - Added `peerFallback = true` in `AetherRuntimeConfig.toNativeJson()`, automatic fallback from `ip=v6`/pinned peer to `ip=both` auto-discovery in `AetherSession` & `AetherProxyCarrier`, and verified Psiphon bootstrap server list staging (`app/src/main/assets/psiphon_server_entries.txt`).
- **Scribe**:
  - Imported all 14 `.SKILL.md` files (including `14-html-viewer-app-testing.SKILL.md`) from Google Drive folder `1Ib_oKMGA8a9M2Z5rg8c6-O6HyE8ml5rQ` into `.agents/skills/`.
