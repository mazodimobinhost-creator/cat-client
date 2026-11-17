---
name: android-kotlin-app
description: Use for building, fixing, or releasing Android apps in Kotlin (Compose or Views), including networking, VPN/proxy-style apps, background work, signing, CI builds, and Play/APK distribution.
---

# Android (Kotlin) apps

## Architecture
- Kotlin + Jetpack Compose (or XML Views if the project already uses them; do not mix without reason). Single Activity, navigation component.
- MVVM/MVI: UI -> ViewModel (StateFlow) -> Repository -> data sources (Retrofit/OkHttp, Room, DataStore). Dependency injection with Hilt or Koin, consistent with the project.
- Coroutines + Flow. Never block the main thread. Use `viewModelScope`, `Dispatchers.IO` for I/O, handle cancellation.
- Keep state in the ViewModel, survive rotation and process death (`SavedStateHandle`).
- Use a sealed `UiState` (Loading / Content / Empty / Error) for every screen.

## Persian / RTL
- `android:supportsRtl="true"`; use `start/end` instead of `left/right`. Provide `values-fa` strings; never hardcode text. Bundle Vazirmatn as a font resource. Handle Jalali dates (e.g. a Persian calendar library) and Persian digit display; normalize to Latin digits before sending to the server.

## Networking
- OkHttp with sane timeouts, retry with backoff for idempotent calls, connection pooling, certificate/network security config. Cleartext HTTP off by default (`networkSecurityConfig`), enable only for explicit debug hosts.
- Handle: no network, captive portals, DNS failures, TLS errors, 4xx/5xx, timeouts, and slow connections. Show clear messages and a retry action.
- For users in filtered networks: support configurable endpoints (primary + fallback domains), DoH via OkHttp's DnsOverHttps, and avoid depending on blocked CDNs/fonts/analytics (Google services may be unavailable; do not hard-require Google Play Services or Firebase for core features).
- Never ship API secrets in the APK. Anything in the app can be extracted. Use short-lived tokens issued by your backend.

## VPN / proxy-style apps
- Use `VpnService` with a foreground service and a persistent notification; handle `onRevoke`, always-on VPN, and per-app split tunneling (`addAllowedApplication`/`addDisallowedApplication`).
- Embed cores (Xray/sing-box/libXray/libbox) via the project's chosen binding (AAR/JNI). Pin versions, verify checksums, and test each ABI (arm64-v8a, armeabi-v7a, x86_64).
- Protect your own server connection from being routed into the VPN (socket protect / exclude your own package) to avoid loops.
- Handle network changes (Wi-Fi <-> mobile) by reconnecting, and battery limits (Doze, OEM task killers: Xiaomi, Huawei, Oppo). Guide users to disable battery optimization where needed.
- Be honest in the UI and store text about what the app does with traffic and logs.

## Background work
- WorkManager for deferrable work, foreground service for ongoing user-visible tasks, exact alarms only when truly needed. Declare the correct foreground service type and permissions for the target SDK.

## Build, signing, release
- Gradle version catalog, pinned plugin/library versions, `minSdk` chosen deliberately, `targetSdk` current enough for the store.
- Release builds: R8/ProGuard on, keep rules for reflection/JNI/serialization libs, test the *minified* release APK, not only debug.
- Signing: keep the keystore outside the repo; in CI use encrypted secrets (base64 keystore + passwords as secrets, decode at build time, never print them). Back up the keystore; losing it blocks updates.
- Split ABIs or use App Bundles for size. Version code must always increase.
- Distribution inside Iran: Google Play is often unavailable; provide direct APK download from a domain you control, plus domestic stores (e.g. Cafe Bazaar, Myket) and verify the SHA-256 of the file in release notes. Keep an in-app update check using your own endpoint.

## CI (GitHub Actions)
- Use `actions/setup-java` and Gradle caching. Run `./gradlew lint test assembleRelease`. Upload the APK as an artifact. Pin actions by version or SHA. Use `secrets.*` only; never echo them.

## Quality checklist before done
- Builds from a clean checkout. Lint and unit tests pass.
- Tested: rotation, no network, slow network, permission denied, background/foreground, low RAM device, Android 8 and the latest version, RTL layout.
- Crash-free on a real device or emulator for the main flows; report what was and was not run.
- No debug logs of tokens/URLs with secrets in release.
