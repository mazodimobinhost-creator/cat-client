#!/usr/bin/env bash
# Builds the real DNS-tunnel engine (SlipNet CLI: dnstt / NoizDNS / VayDNS / SlipNet SSH,
# SOCKS5, VLESS, DoH) from the open-source anonvector/SlipNet repository (AGPL-3.0) and packages
# it as app/src/main/jniLibs/<abi>/libslipnet.so so Android extracts it as an executable into
# nativeLibraryDir. SlipNetCliEngine.kt launches it as a local SOCKS5 proxy for mihomo.
set -euo pipefail

SLIPNET_REPO="${SLIPNET_REPO:-https://github.com/anonvector/SlipNet.git}"
SLIPNET_REF="${SLIPNET_REF:-552ab5078d90ae84294fcd6ef649b8e023ba2a63}"
ABIS="${SLIPNET_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/slipnet-src"

rm -rf "$WORK"
git init -q "$WORK"
cd "$WORK"
git remote add origin "$SLIPNET_REPO"
git fetch -q --depth 1 origin "$SLIPNET_REF"
git checkout -q FETCH_HEAD
for sub in dnstt noizdns vaydns vaydns-mobile; do
  git submodule update --init --depth 1 "$sub" || git submodule update --init "$sub"
done

cd cli
cp ../app/src/main/res/raw/resolvers.txt resolvers.txt
rm -f key_generated.go   # keyless build: slipnet-enc:// stays unsupported (needs SlipNet's private key)

export GOTOOLCHAIN=auto
export GOFLAGS=-mod=mod
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
  out="$ROOT/app/src/main/jniLibs/$abi/libslipnet.so"
  mkdir -p "$(dirname "$out")"
  echo "== building SlipNet CLI for $abi (GOARCH=$goarch${goarm:+ GOARM=$goarm})"
  GOOS=linux GOARCH="$goarch" GOARM="$goarm" \
    go build -trimpath -ldflags "-s -w -X main.version=cat-${SLIPNET_REF:0:7}" -o "$out" .
  chmod 0755 "$out"
  ls -la "$out"
done

echo "SlipNet CLI (AGPL-3.0) built from $SLIPNET_REPO @ $SLIPNET_REF"
