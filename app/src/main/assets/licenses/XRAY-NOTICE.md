# Xray-core (built-in Xray engine)

Cat Client ships the official **Xray-core** by Project X / XTLS as `lib/<abi>/libxray.so`.

- Upstream: https://github.com/XTLS/Xray-core
- Version: v26.10.21 — commit `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74`
- License: Mozilla Public License 2.0 (full text: `MPL-2.0.txt` next to this file, or https://mozilla.org/MPL/2.0/)
- Xray-core is compiled **unmodified** from that commit by `scripts/build-xray-core.sh`. Its complete source code is
  available at the upstream link above.
- The only Cat code linked into the binary is the runner `scripts/xray-runner/main.go` (starts Xray from a JSON
  config and asks the app to protect each outbound socket from the VPN). It is not part of Xray-core.
