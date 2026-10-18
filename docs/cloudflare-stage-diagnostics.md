# Cloudflare production stage diagnostics

`diagnostics/cloudflare-stage-01-panel.js` is a temporary, three-line ES-module Worker used to distinguish account/runtime failures from Cat Panel's full networking bundle. It contains no tunnel, subscription, outbound socket, remote loader, or camouflage functionality.

Deploy it only over the existing test Worker that already has a known-good bootstrap version. Do not delete KV or bindings. Expected production probes:

- `/health` → `ok`
- `/api/selfcheck` → JSON with stage `01-panel-runtime` and four `true` binding flags
- `/` or `/panel` → visible Persian diagnostic card

If stage 01 fails with 1100/1101, stop: the failure is outside Cat Panel's networking bundle. If it succeeds, retain its Version ID as the rollback point before testing the next isolated stage.

Stage 02 adds only the official `cloudflare:sockets` module import and verifies that `connect` is available; it never opens a socket. A production pass rules out module-load failure caused merely by the sockets import.

Stage 03 adds a bounded local WebSocket echo at `/ws`. It imports sockets but never calls `connect`, so it tests production WebSocket upgrade/101 handling independently from outbound networking.

Stage 04 performs one bounded, four-second reachability probe to `cloudflare.com:443`, immediately closes the socket, and never relays payloads. It isolates outbound socket permission/runtime from all tunnel protocols.

Stage 05 is a three-line, ~541 KB module containing only an inert static literal. It isolates upload/module-startup size from sockets, WebSockets, bindings, protocols, and remote code. A production pass rules out source size alone as the 1101 trigger.
