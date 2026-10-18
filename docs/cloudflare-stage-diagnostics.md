# Cloudflare production stage diagnostics

`diagnostics/cloudflare-stage-01-panel.js` is a temporary, three-line ES-module Worker used to distinguish account/runtime failures from Cat Panel's full networking bundle. It contains no tunnel, subscription, outbound socket, remote loader, or camouflage functionality.

Deploy it only over the existing test Worker that already has a known-good bootstrap version. Do not delete KV or bindings. Expected production probes:

- `/health` → `ok`
- `/api/selfcheck` → JSON with stage `01-panel-runtime` and four `true` binding flags
- `/` or `/panel` → visible Persian diagnostic card

If stage 01 fails with 1100/1101, stop: the failure is outside Cat Panel's networking bundle. If it succeeds, retain its Version ID as the rollback point before testing the next isolated stage.
