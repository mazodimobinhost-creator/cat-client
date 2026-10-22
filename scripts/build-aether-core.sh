#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="${AETHER_SOURCE_DIR:-$root/.aether-upstream}"
out="${AETHER_JNI_OUT:-$root/app/src/main/jniLibs}"
abis="${AETHER_ABIS:-armeabi-v7a,arm64-v8a,x86_64}"

"$root/scripts/verify-aether-upstream.sh" "$source_dir"
command -v cargo >/dev/null || { echo 'cargo is required' >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo 'cargo-ndk is required (cargo install cargo-ndk --locked)' >&2; exit 1; }
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to NDK 29.0.14206865}"
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "NDK not found: $ANDROID_NDK_HOME" >&2; exit 1; }

args=()
IFS=',' read -ra requested <<< "$abis"
for abi in "${requested[@]}"; do
  case "$abi" in
    armeabi-v7a|arm64-v8a|x86_64) args+=( -t "$abi" ) ;;
    *) echo "unsupported Aether ABI: $abi" >&2; exit 1 ;;
  esac
done

mkdir -p "$out"
(
  cd "$source_dir/native/android-bridge"
  cargo ndk --platform 26 "${args[@]}" -o "$out" build --locked --release
)

host=linux-x86_64
case "$(uname -s)" in Darwin) host=darwin-x86_64;; MINGW*|MSYS*) host=windows-x86_64;; esac
for abi in "${requested[@]}"; do
  case "$abi" in
    armeabi-v7a) triple=arm-linux-androideabi ;;
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
  esac
  cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/sysroot/usr/lib/$triple/libc++_shared.so" "$out/$abi/"
  test -s "$out/$abi/libwhiteaesther_core.so"
  test -s "$out/$abi/libc++_shared.so"
done
for abi in "${requested[@]}"; do
  sha256sum "$out/$abi/libwhiteaesther_core.so"
done
