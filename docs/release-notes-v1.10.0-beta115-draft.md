# Cat Client 1.10.0-beta115 — abuse-safe panel recovery

> TEST CANDIDATE. This build cannot remove a Cloudflare Trust & Safety restriction or answer an abuse case for the account owner.

- Fresh Worker deployments are panel/control-plane only by default.
- WebSocket tunnel paths return HTTP 403 unless the owner explicitly sets `DATA_PLANE_ENABLED=true` after provider approval and credential rotation.
- UUID, KV, D1, IOT_DB, Secrets, and custom bindings are not deleted or rewritten by this guard.
- CI includes a regression test proving unrelated bindings cannot accidentally enable the data plane.
- Automatic creation of replacement Workers is not a method for bypassing Trust & Safety enforcement.

To recover an existing reported Worker safely: disable automated redeploy, rotate credentials, resolve the abuse reports, deploy this Worker, verify `/health` repeatedly, and leave `DATA_PLANE_ENABLED` unset until Cloudflare explicitly permits restoration.
