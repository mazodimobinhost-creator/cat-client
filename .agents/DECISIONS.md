# Architectural & Technical Decisions (`DECISIONS.md`)

1. **Concrete `@Keep` JNI Callback Wrappers + ProGuard Keep Rules (`2026-10-25`)**:
   - *Context*: `libwhiteaesther_core.so` invokes `protectSocket(I)Z` and `onNativeReady()V` via JNI `GetMethodID`. Kotlin `fun interface` lambdas are compiled to synthetic classes (e.g. `LA0/l;`) whose methods are renamed/stripped by R8 full mode unless explicitly kept.
   - *Decision*: Use both concrete `@Keep` wrapper classes inside `com.whitedns.whiteaesther.core` (`JniSocketProtectorBridge`, `JniEngineListenerBridge`) and explicit `-keep class * implements ...` rules in `app/proguard-rules.pro`, enforced by a JVM unit test.
2. **Structural Iran vs. Foreign Exit in `aether://` URIs (`2026-10-25`)**:
   - *Context*: Upstream Aether CLI / `PattNG` runs `exitloc::settle` on the raw WARP tunnel before exposing SOCKS5 or chaining Psiphon. Passing `exit_loc=!IR` on single-hop `wg` tears down validated tunnels.
   - *Decision*: Omit `exit_loc` from generated `aether://` URIs; represent Iran exit via single-hop `wg`/`masque` and Foreign exit via two-hop `gool`/`wg-over-masque` (`outer=host:port`) or `psiphon=chain&psiphon_mode=auto&region=XX`.
3. **Bundled + Runtime-Verified Psiphon Bootstrap Server List (`2026-10-25`)**:
   - *Context*: `PsiphonTunnel.startTunneling("")` has no candidate servers on a fresh install until a remote server list download succeeds, causing region-specific connections to time out.
   - *Decision*: Stage the RSA-SHA256 verified Psiphon server entries into `app/src/main/assets/psiphon_server_entries.txt` during CI build and support runtime verification/caching via the local Aether SOCKS5 proxy in `PsiphonCarrierConfig.loadOrFetchBootstrapEntries`.
