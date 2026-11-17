# Cat Client 1.10.0-beta116 — verified panel installation

- The wizard no longer treats a successful Cloudflare upload as a successful panel.
- A new deployment must pass three consecutive uncached `/health` responses with a valid version before its URL is returned.
- A network error, non-2xx response, malformed health body, `panel_crash`, or Error 1101 resets the consecutive-success counter.
- A failed fresh install has its public workers.dev route disabled; KV/D1/account data are not deleted.
- Readable fallback Worker sources are refused instead of being deployed after the anti-1101 source check.
- Existing bindings remain preserved by the in-place installer behavior.

This prevents a broken runtime from being reported as a completed installation. It cannot remove Cloudflare account enforcement or resolve an existing Trust & Safety report.
