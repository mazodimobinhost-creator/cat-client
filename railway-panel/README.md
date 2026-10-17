# Cat Railway Panel

Independent Railway target for Cat Client. It does not run or replace the canonical Cloudflare Worker.

## Required variable

- `CAT_ADMIN_TOKEN`: a long random bearer token. The service fails closed for protected APIs when it is absent.
- `CAT_DATA_DIR`: persistent volume path, normally `/data`.
- `CAT_INITIAL_ADMIN_PASSWORD`: required on the first boot, minimum 12 characters; ignored after the admin record exists.
- `CAT_XRAY_BINARY`: path to an administrator-provided Xray binary.
- `CAT_XRAY_SHA256`: mandatory exact SHA-256 of that binary. Xray stays disabled on mismatch or when unset.

## Current API

- `GET /health`
- `GET /api/version`
- `GET /runtime`
- `POST /api/login`
- `GET /api/me` (session)
- `GET /api/users`, `POST /api/users` (admin; quota and expiry supported)
- `GET /api/candidates` (admin)
- `POST /api/candidates` (admin; validates and stores IPv4/IPv6 + SNI candidates)
- `GET /api/xray/status` (admin)
- `POST /api/xray/config` (admin; validates with `xray run -test` before atomic install)
- `POST /api/xray/start`, `/stop`, `/restart` (admin)

Candidate data is written atomically with mode `0600`. Secrets are never returned by runtime endpoints.
