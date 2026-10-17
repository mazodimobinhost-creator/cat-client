# Cat Railway Panel

Independent Railway target for Cat Client. It does not run or replace the canonical Cloudflare Worker.

The root route serves a self-contained, mobile-friendly RTL admin UI for login, runtime/Xray status, users, candidate IP/SNI management, and country-first automatic inbound creation. It loads no third-party scripts or styles.

## Required variable

- `CAT_ADMIN_TOKEN`: a long random bearer token. The service fails closed for protected APIs when it is absent.
- `CAT_DATA_DIR`: persistent volume path, normally `/data`.
- `CAT_INITIAL_ADMIN_PASSWORD`: required on the first boot, minimum 12 characters; ignored after the admin record exists.
- `CAT_XRAY_BINARY`: path to an administrator-provided Xray binary.
- `CAT_XRAY_SHA256`: mandatory exact SHA-256 of that binary. Xray stays disabled on mismatch or when unset.
- `CAT_TUNNEL_PATH_TOKEN`: random 20–64 character URL-safe token used by the fixed WebSocket upgrade route.
- `CAT_XRAY_WS_PORT`: local-only Xray WS port, default `18080`.
- `CAT_BACKUP_KEY`: exactly 32 random bytes encoded as Base64; required for encrypted backup/restore.
- `CAT_TELEGRAM_BOT_TOKEN`, `CAT_TELEGRAM_ADMIN_ID`, `CAT_TELEGRAM_WEBHOOK_SECRET`: optional Telegram administration; the secret must be 20–128 URL-safe characters.

## Current API

- `GET /health`
- `GET /api/version`
- `GET /runtime`
- `POST /api/login`
- `GET /api/me` (session)
- `GET /api/users`, `POST /api/users` (admin; quota, expiry, UUID, and one-time subscription token)
- `PUT /api/users/:id/links` (admin; deduplicated VLESS/Trojan links only)
- `GET /sub/:token` (private raw or base64 subscription with quota/expiry headers)
- `GET /api/candidates` (admin)
- `POST /api/candidates` (admin; validates and stores public IPv4/IPv6 + SNI candidates)
- `POST /api/candidates/probe` (admin; TLS + HTTP evidence over the same registered candidate connection)
- `POST /api/auto-plan` (admin; choose a country and optionally IP version; only fresh proven candidates can become primary/fallback)
- `POST /api/auto-inbound` (admin; country + user ID → proven endpoint, local-only multi-user VLESS/WS inbound, validated Xray apply, private subscription link)
- `GET /api/backup`, `POST /api/restore` (admin; authenticated AES-256-GCM archive, strict file allowlist)
- `POST /api/telegram/setup` (admin; register a secret-protected webhook)
- `POST /api/telegram/webhook` (Telegram only; numeric admin ID + secret header; `/status`, `/users`, `/restart_xray`)
- `GET /api/nodes`, `POST /api/nodes` (admin; multi-location node registration, one-time API key)
- `POST /api/node/heartbeat` (node; hashed key authentication)
- `POST /api/nodes/select` (admin; country selection uses only fresh healthy heartbeat evidence)
- `GET /api/xray/status` (admin)
- `POST /api/xray/config` (admin; validates with `xray run -test` before atomic install)
- `POST /api/xray/apply` (admin; validate, restart, and automatically restore the last known-good config if startup fails)
- `POST /api/xray/start`, `/stop`, `/restart` (admin)

Candidate data is written atomically with mode `0600`. Secrets are never returned by runtime endpoints.
