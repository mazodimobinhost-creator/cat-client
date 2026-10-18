import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const root = new URL('../../', import.meta.url);
const load = path => readFile(new URL(path, root), 'utf8');

test('portal remains an independent control plane with D1 and static assets', async () => {
  const [config, schema, worker, html] = await Promise.all([
    load('portal/wrangler.jsonc'), load('portal/schema.sql'), load('portal/src/_worker.js'), load('portal/public/index.html'),
  ]);
  assert.match(config, /"name"\s*:\s*"cat-account-portal"/);
  assert.match(config, /"binding"\s*:\s*"DB"/);
  assert.match(schema, /CREATE TABLE IF NOT EXISTS users/);
  assert.match(schema, /CREATE TABLE IF NOT EXISTS sessions/);
  assert.match(worker, /verifyTelegramInit/);
  assert.match(worker, /\/api\/account\/credentials/);
  assert.match(worker, /\/api\/sessions\/revoke-others/);
  assert.match(worker, /\/api\/deployments/);
  assert.match(worker, /global_fetch_strictly_public|publicHttpsOrigin/);
  assert.match(worker, /command === '\/status'/);
  assert.match(worker, /HttpOnly; Secure; SameSite=Lax/);
  assert.match(worker, /env\.ASSETS\.fetch/);
  assert.doesNotMatch(worker, /cloudflare:sockets/);
  assert.doesNotMatch(html, /<script[^>]+src=/i);
  assert.match(html, /tgWebAppData/);
});

test('portal never stores plaintext session tokens or bot secrets in schema', async () => {
  const [schema, config] = await Promise.all([load('portal/schema.sql'), load('portal/wrangler.jsonc')]);
  assert.match(schema, /token_hash TEXT PRIMARY KEY/);
  assert.doesNotMatch(schema, /bot_token|api_token/i);
  assert.doesNotMatch(config, /TELEGRAM_BOT_TOKEN|TELEGRAM_WEBHOOK_SECRET/);
});
