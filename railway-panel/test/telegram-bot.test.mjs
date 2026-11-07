import test from 'node:test';
import assert from 'node:assert/strict';
import { TelegramBot } from '../telegram-bot.js';

const token = '123456789:abcdefghijklmnopqrstuvwxyz_ABCDE12345';
const secret = 'a_very_long_webhook_secret_123';
const make = () => {
  const calls = [];
  const bot = new TelegramBot({ token, adminId: '12345678', secret, fetchImpl: async (url, options) => { calls.push({ url, body: JSON.parse(options.body) }); return { ok: true, json: async () => ({ ok: true, result: true }) }; } });
  return { bot, calls };
};
const actions = { status: async () => 'healthy', users: async () => 'users: 2', restartXray: async () => 'restarted' };

test('rejects missing secret and non-admin sender', async () => {
  const { bot, calls } = make();
  assert.equal((await bot.handle({ headers: {} }, {}, actions)).status, 401);
  const request = { headers: { 'x-telegram-bot-api-secret-token': secret } };
  assert.equal((await bot.handle(request, { message: { from: { id: 9 }, chat: { id: 9 }, text: '/status' } }, actions)).status, 403);
  assert.equal(calls.length, 0);
});

test('admin commands return a bounded Telegram message', async () => {
  const { bot, calls } = make(); const request = { headers: { 'x-telegram-bot-api-secret-token': secret } };
  const update = { message: { from: { id: 12345678 }, chat: { id: 12345678 }, text: '/status' } };
  assert.equal((await bot.handle(request, update, actions)).status, 200);
  assert.match(calls[0].url, /sendMessage$/);
  assert.equal(calls[0].body.text, 'healthy');
});

test('webhook setup is pinned to HTTPS Railway host', async () => {
  const { bot, calls } = make(); await bot.setupWebhook('cat.up.railway.app');
  assert.equal(calls[0].body.url, 'https://cat.up.railway.app/api/telegram/webhook');
  await assert.rejects(() => bot.setupWebhook('bad host/path'), /invalid_public_host/);
});
