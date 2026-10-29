#!/usr/bin/env bash
# Builds the official Xray-core (XTLS/Xray-core, MPL-2.0) from source at a pinned tag/commit together
# with Cat Client's small runner (scripts/xray-runner/main.go: JSON config + protect() over a unix
# socket) and packages it as app/src/main/jniLibs/<abi>/libxray.so, so Android extracts it as an
# executable into nativeLibraryDir. XrayEngine.kt launches it as local SOCKS5 inbounds for mihomo.
# Xray-core itself is compiled unmodified; its source is https://github.com/XTLS/Xray-core.
set -euo pipefail

XRAY_REPO="${XRAY_REPO:-https://github.com/XTLS/Xray-core.git}"
XRAY_TAG="${XRAY_TAG:-v26.10.21}"
XRAY_COMMIT="${XRAY_COMMIT:-1b1647cd9ddf0abc34b981d4611fc03bcac1ca74}"
ABIS="${XRAY_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/xray-src"

rm -rf "$WORK"
git init -q "$WORK"
cd "$WORK"
git remote add origin "$XRAY_REPO"
git fetch -q --depth 1 origin "$XRAY_COMMIT"
git checkout -q FETCH_HEAD
test "$(git rev-parse HEAD)" = "$XRAY_COMMIT"

# The runner lives inside Xray's module so it builds against Xray's own go.mod / go.sum.
mkdir -p cat/xrayrunner
cp "$ROOT/scripts/xray-runner/main.go" cat/xrayrunner/main.go

export GOTOOLCHAIN=auto
export CGO_ENABLED=0
go version

for abi in $ABIS; do
  goarm=""
  case "$abi" in
    arm64-v8a)   goarch=arm64 ;;
    armeabi-v7a) goarch=arm; goarm=7 ;;
    x86)         goarch=386 ;;
    x86_64)      goarch=amd64 ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  out="$ROOT/app/src/main/jniLibs/$abi/libxray.so"
  mkdir -p "$(dirname "$out")"
  echo "== building Xray-core $XRAY_TAG for $abi (GOARCH=$goarch${goarm:+ GOARM=$goarm})"
  GOOS=linux GOARCH="$goarch" GOARM="$goarm" \
    go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$out" ./cat/xrayrunner
  chmod 0755 "$out"
  ls -la "$out"
done

echo "Xray-core (MPL-2.0) $XRAY_TAG built from $XRAY_REPO @ $XRAY_COMMIT"
