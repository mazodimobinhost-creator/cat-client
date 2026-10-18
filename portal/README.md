# Cat Account Portal

Independent Cloudflare control plane for account login, Telegram Mini App authentication and per-user deployment status. It does not relay tunnel traffic and does not replace the canonical Cat Worker or the independent Pages data-plane target.

## Production setup

1. Create D1: `npx wrangler d1 create cat-portal` and replace the placeholder in `wrangler.jsonc`.
2. Apply schema: `npx wrangler d1 execute cat-portal --remote --file schema.sql`.
3. Set `PORTAL_URL` to the final HTTPS custom domain.
4. Add encrypted secrets with `wrangler secret put TELEGRAM_BOT_TOKEN` and `wrangler secret put TELEGRAM_WEBHOOK_SECRET`.
5. Deploy from this directory with `npx wrangler deploy`.
6. Register `https://<domain>/api/telegram/webhook` using Telegram `setWebhook`, including the same `secret_token`.
7. Configure the final HTTPS domain as the bot Mini App URL.

Never commit bot or Cloudflare tokens. The frontend contains no third-party JavaScript. Telegram Mini App authentication uses the signed `tgWebAppData` launch parameter and is verified server-side.

## Security properties

- PBKDF2-SHA256 password hashes with random salts and 210,000 iterations.
- Opaque 256-bit session tokens; only SHA-256 hashes are stored in D1.
- Secure, HttpOnly, SameSite=Lax cookies.
- Telegram init-data HMAC, freshness and user validation.
- Secret-protected webhook and bounded JSON bodies.
- Portal/data-plane separation, no Cloudflare customer token collection.
- Scheduled expired-session cleanup and structured errors instead of uncaught 1101 responses.
