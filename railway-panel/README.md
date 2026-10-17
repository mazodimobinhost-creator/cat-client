# Cat Railway Panel

Independent Railway target for Cat Client. It does not run or replace the canonical Cloudflare Worker.

## Required variable

- `CAT_ADMIN_TOKEN`: a long random bearer token. The service fails closed for protected APIs when it is absent.
- `CAT_DATA_DIR`: persistent volume path, normally `/data`.

## Current API

- `GET /health`
- `GET /api/version`
- `GET /runtime`
- `GET /api/candidates` (admin)
- `POST /api/candidates` (admin; validates and stores IPv4/IPv6 + SNI candidates)

Candidate data is written atomically with mode `0600`. Secrets are never returned by runtime endpoints.
