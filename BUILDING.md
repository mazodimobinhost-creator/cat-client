# Building Cat Client from source

## Quick start

The easiest way to get an APK is to push to `main` or any `arena/*` branch of
this repo. GitHub Actions runs `.github/workflows/android.yml` on every push,
builds debug + release APKs, uploads them as build artifacts, and attaches
them to a new GitHub release.

## Local build (Linux / macOS / Windows)

1. Install **Android Studio** (it installs Android SDK, JDK 21, build-tools
   and platform-tools automatically).
2. Install **Go 1.24+** from https://go.dev/dl/ (needed to compile libmihomo
   for the VPN core).
3. Open this folder in Android Studio, let it sync.
4. Make sure SDK components are installed:
   - Android SDK Platform 36
   - Android SDK Build-Tools 35.0.0
   - NDK 29.0.14206865
   - CMake 3.22.1
   - Android SDK Platform-Tools
5. Run in a terminal at the project root:
   ```bash
   export ANDROID_HOME=$HOME/Android/Sdk         # or ~/Library/Android/sdk on macOS
   export ANDROID_NDK=$ANDROID_HOME/ndk/29.0.14206865
   ./scripts/build-flclash-core.sh              # compile libclash.so for all ABIs (~5 min first time)
   ./gradlew assembleDebug                      # produces app/build/outputs/apk/debug/app-debug.apk
   ```
6. Transfer the APK to your phone and install.

## Release build

```bash
# Generate a signing key (once)
keytool -genkeypair -v -keystore catclient.jks -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass YOURPASS -alias catclient -keypass YOURPASS -dname "CN=Cat Client"

cat > keystore.properties <<EOF
storeFile=$(pwd)/catclient.jks
storePassword=YOURPASS
keyAlias=catclient
keyPassword=YOURPASS
EOF

./scripts/release.sh
# APKs land in release/
```

## Fast local checks (no SDK needed)

```bash
node scripts/panels/cat-panel.test.mjs     # Cat Panel worker: 44 behaviour checks
python3 scripts/kotlin-syntax-check.py app/src/main/java   # tree-sitter Kotlin syntax gate
python3 scripts/branding/generate-icons.py # regenerate launcher / TV / notification art
node scripts/panels/preview-server.mjs 8787 # run the panel in a browser (http://localhost:8787)
```

The Kotlin gate is a parser check (catches syntax slips in seconds); the real
compiler runs in CI's fail-fast `:app:compileDebugKotlin` step.

## Project layout

- `app/src/main/java/com/cat/client/` — Kotlin application sources.
- `app/src/main/res/` — Android resources (layouts, strings, drawables, themes).
- `app/src/main/assets/panels/` — bundled worker JS for the built-in panel.
- `app/src/main/cpp/` — JNI bridge that loads libclash.so (the Mihomo core).
- `SubConv/` — share-link parsing library.
- `scripts/build-flclash-core.sh` — pinned Mihomo v1.19.30 + FlClash JNI build.
- `scripts/panels/cat-panel.test.mjs` — Node test harness for the built-in
  Cat Panel worker (no dependencies: `node scripts/panels/cat-panel.test.mjs`).
- `scripts/panels/preview-server.mjs` — serves the worker locally (panel + API).
- `scripts/branding/generate-icons.py` — Pillow generator for every icon asset.

## Troubleshooting

- **NDK not found**: set `ANDROID_NDK` to the NDK path before running gradle.
- **libclash.so missing**: run `./scripts/build-flclash-core.sh` first.
- **First build is slow**: Gradle downloads dependencies and Go compiles the
  Mihomo core for 4 ABIs (≈600 MB download + compile). Subsequent builds are
  cached.
