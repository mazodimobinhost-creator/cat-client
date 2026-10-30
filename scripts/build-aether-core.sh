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

# Cat patch (beta208): MASQUE-in-MASQUE over HTTP/2. The upstream bridge only sets AETHER_MASQUE_HTTP2 for the
# "h2"/"wom" transports and REMOVES it for "mim", so a PattNG «MIM-H2» profile ran as MIM over HTTP/3 (QUIC), which
# Iranian networks block. The CLI does the same thing with `--protocol mim --h2`; the bridge gets an opt-in
# `masqueHttp2` field for it. Idempotent; fails loudly if upstream code moved.
python3 - "$source_dir/native/android-bridge/src/lib.rs" <<'PY'
import sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
if "masque_http2: bool" not in src:
    field_anchor = "    #[serde(default)]\n    fast_connect: bool,\n}\n"
    env_anchor = 'if self.transport == "h2" || self.transport == "wom" {'
    assert src.count(field_anchor) == 1, "bridge field anchor moved"
    assert src.count(env_anchor) == 1, "bridge env anchor moved"
    src = src.replace(field_anchor, "    #[serde(default)]\n    fast_connect: bool,\n    /// Cat: run masque-in-masque over HTTP/2 (both hops), like `aether --protocol mim --h2`.\n    #[serde(default)]\n    masque_http2: bool,\n}\n")
    src = src.replace(env_anchor, 'if self.transport == "h2" || self.transport == "wom" || (self.masque_http2 && self.transport == "mim") {')
    open(path, "w", encoding="utf-8").write(src)
    print("patched bridge: masqueHttp2 for mim")
PY

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
