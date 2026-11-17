# Architectural & Design Decisions

1. **Secondary `:psiphon` Process Isolation (`CatClientApplication.kt`):**
   - `PsiphonCarrierService` runs in `android:process=":psiphon"`. Running `WorkManager` and background workers in both the main process and `:psiphon` causes SQLite/SharedPreferences contention. `:psiphon` now initializes only `CrashWatch` and returns immediately.
2. **Messenger-First Start for `PsiphonCarrierService`:**
   - Android 12+ throws `BackgroundServiceStartNotAllowedException` if `startService()` is called while the app transitions states. `PsiphonCarrierClient` now binds with `BIND_AUTO_CREATE or BIND_IMPORTANT` and sends `MSG_START` over the bound `Messenger` channel.
3. **`DisableTactics: true` & TCP-Only Protocols for Chained Psiphon (`PsiphonCarrierConfig.kt`):**
   - Upstream `patterniha/Aether` `v2.4.0` (`aether/src/psiphon.rs`) discovered that Psiphon tactics attach a BPF program to TCP dials, failing with `operation not permitted` on unrooted Android when dialing through a local SOCKS5 upstream (`UpstreamProxyURL`). Setting `"DisableTactics": true` and restricting `LimitTunnelProtocols` to the 11 TCP-based protocols fixes chained `WARP -> Psiphon` reliability.
4. **Collapsible Secondary Controls in Android App (`MainActivity.kt`):**
   - Keeps the 3 primary actions (`⚡ اتصال فوری`, `🧭 اسکن و ساخت خودکار`, `🧬 ابزارهای WhiteDNS`) at the top with 44dp touch height while placing secondary selectors inside an expandable section.
5. **Selective String-Table Encoding in `minify.mjs`:**
   - Only string literals, member properties, and object keys matching `ALL_SENSITIVE_GLOBAL_RE` (plus regex literals and template literals) are placed in the ROT13 table `_t`, keeping `catpanel.min.js` at ~587 KB raw and <168 KB gzipped with 0 leaked signatures.
