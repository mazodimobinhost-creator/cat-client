---
name: security-review
description: Use for final security review of apps, Cloudflare Workers, panels, GitHub repos and Actions, deploy bots, and servers. Covers secrets, auth, input handling, dependencies, and safe deployment.
---

# Security review

## Scope and approach
Review only systems the user owns or is authorized to test. Go through the checklist, mark each item PASS / FAIL / N/A / NOT CHECKED, and give a fix for every FAIL. Rank findings: Critical, High, Medium, Low. Never claim "secure"; say "no issues found in the items checked".

## 1. Secrets
- Search the repo AND git history for keys, tokens, passwords, keystores, `.env`, private keys, Cloudflare/Telegram/GitHub tokens. If one was ever committed, treat it as leaked: rotate it, then clean history if needed.
- Secrets live in: GitHub Actions secrets, `wrangler secret`, environment variables, or a secret manager. Never in code, client apps, logs, error messages, or screenshots.
- Use least-privilege scoped tokens (Cloudflare API token limited to one zone/account and needed permissions; GitHub fine-grained tokens limited to one repo). Set expiry. Never use Cloudflare's Global API Key.
- `.gitignore` covers `.env`, `*.jks`, `*.keystore`, `local.properties`, build outputs.

## 2. Authentication and authorization
- Passwords hashed with Argon2id/bcrypt; never stored in plain text. Rate-limit and lock out login attempts.
- Every endpoint checks auth AND that the user may access that specific object (prevent IDOR). Admin routes separated and protected.
- Sessions/JWT: short expiry, httpOnly + Secure + SameSite cookies, validate signature/algorithm/audience, revoke on logout. Webhooks (e.g. Telegram, GitHub) verified by secret token or signature.
- Admin panels: not on default path/port, behind HTTPS, ideally behind Cloudflare Access/IP allowlist/VPN, with 2FA.

## 3. Input and output
- Validate and sanitize all input server-side (type, length, format). Parameterized queries only (no string-built SQL).
- Prevent XSS (escape output, CSP), CSRF (tokens or SameSite), SSRF (block internal IP ranges in any URL-fetching feature, including Workers that fetch user-supplied URLs), path traversal, open redirects, command injection (never pass user input to a shell).
- File uploads: check type by content, size limits, random names, store outside web root.
- Error messages generic to users; details only in server logs.

## 4. Cloudflare Workers / edge
- No secrets in `wrangler.toml`; use `wrangler secret put`. Bindings scoped minimally.
- CORS: allow only needed origins, not `*` for authenticated APIs.
- Rate limiting and WAF rules on sensitive routes. Validate `Host`/origin. Avoid becoming an open proxy: require auth or an allowlist for any forwarding Worker.
- KV is eventually consistent; do not use it alone for security-critical counters or locks (use Durable Objects).
- Log without sensitive data.

## 5. GitHub and CI/CD
- Branch protection on main, required review, no force push. Enable secret scanning and Dependabot.
- Actions: pin third-party actions to a version/SHA, set `permissions:` to least privilege (default read), avoid `pull_request_target` with untrusted code, never print secrets, be careful with `${{ github.event.* }}` in shell (script injection).
- Deploy bots/automation: identify who can trigger them, validate commands against an allowlist, require confirmation for destructive actions, log actions, use separate low-privilege credentials per environment.

## 6. Android app
- No secrets in the APK/BuildConfig/strings. Cleartext traffic disabled; consider certificate pinning with a backup pin and rotation plan.
- `android:exported` set deliberately; protect components with permissions; no sensitive data in logs or backups (`allowBackup=false` if needed). Use EncryptedSharedPreferences/Keystore for stored tokens. R8 on, release signed, keystore protected.
- Validate deep links and intents. WebViews: disable file access and JS unless needed.

## 7. Servers and proxies
- SSH keys only, root login off, firewall allowing only needed ports, automatic security updates, fail2ban, backups tested. Proxy panels (3x-ui/Marzban): changed default credentials/path, HTTPS, up to date, minimal logging, no user data exposed.
- Keep user logs minimal and rotate/delete them; people in restricted countries can be harmed by leaked logs or user lists.

## 8. Dependencies
- Run `npm audit`/`pip-audit`/Gradle dependency check. Update vulnerable packages, remove unused ones, pin versions, prefer well-maintained libraries. Verify checksums of downloaded binaries (Xray, sing-box).

## 9. Privacy
- Collect the minimum. Say clearly what is stored and for how long. Provide deletion. Encrypt data in transit (TLS) and sensitive data at rest.

## Report format
1. Summary and overall risk.
2. Findings table: ID | severity | where | issue | fix.
3. What was checked and what was NOT checked (and why).
4. Immediate actions (rotate leaked keys, close exposed ports), then improvements.
Be specific with file paths and line numbers. Do not paste full secrets in the report; show only the last 4 characters.
