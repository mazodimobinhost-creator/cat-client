#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
lock="$root/aether/upstream.lock"
read_value() { sed -n "s/^$1=//p" "$lock"; }
repo="$(read_value repository)"
commit="$(read_value commit)"
dest="${1:-$root/.aether-upstream}"
rm -rf "$dest"
git init -q "$dest"
git -C "$dest" remote add origin "$repo"
git -C "$dest" fetch -q --depth 1 origin "$commit"
git -C "$dest" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$dest" rev-parse HEAD)" == "$commit" ]]
check() {
  local key="$1" file="$2" expected actual
  expected="$(read_value "$key")"
  actual="$(sha256sum "$dest/$file" | cut -d' ' -f1)"
  [[ "$actual" == "$expected" ]] || { echo "checksum mismatch: $file" >&2; exit 1; }
}
check license_sha256 LICENSE
check android_bridge_lock_sha256 native/android-bridge/Cargo.lock
check aether_lock_sha256 native/aether/aether/Cargo.lock
echo "Verified Aether upstream $commit"
