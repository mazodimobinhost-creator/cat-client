import { timingSafeEqual } from 'node:crypto';

const safeEqual = (a, b) => {
  const x = Buffer.from(String(a || '')); const y = Buffer.from(String(b || ''));
  return x.length === y.length && timingSafeEqual(x, y);
};
export class TelegramBot {
  constructor({ token, adminId, secret, fetchImpl = fetch }) {
    this.token = /^\d{6,12}:[A-Za-z0-9_-]{30,80}$/.test(String(token || '')) ? String(token) : '';
    this.adminId = String(adminId || '').match(/^\d{4,20}$/)?.[0] || '';
    this.secret = String(secret || '').match(/^[A-Za-z0-9_-]{20,128}$/)?.[0] || '';
    this.fetch = fetchImpl;
  }
  configured() { return Boolean(this.token && this.adminId && this.secret); }
  async call(method, payload) {
    if (!this.configured()) throw new Error('telegram_not_configured');
    const response = await this.fetch(`https://api.telegram.org/bot${this.token}/${method}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) });
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.ok) throw new Error('telegram_api_error');
    return result.result;
  }
  async setupWebhook(publicHost) {
    if (!/^[a-z0-9.-]+$/i.test(String(publicHost || ''))) throw new Error('invalid_public_host');
    return this.call('setWebhook', { url: `https://${publicHost}/api/telegram/webhook`, secret_token: this.secret, allowed_updates: ['message'], drop_pending_updates: false });
  }
  async handle(request, update, actions) {
    if (!this.configured() || !safeEqual(request.headers['x-telegram-bot-api-secret-token'], this.secret)) return { status: 401 };
    const message = update?.message; const sender = String(message?.from?.id || ''); const chat = String(message?.chat?.id || '');
    if (!safeEqual(sender, this.adminId) || !safeEqual(chat, this.adminId)) return { status: 403 };
    const command = String(message?.text || '').trim().split(/\s+/)[0].split('@')[0].toLowerCase();
    let text;
    if (command === '/status') text = await actions.status();
    else if (command === '/users') text = await actions.users();
    else if (command === '/restart_xray') text = await actions.restartXray();
    else text = 'Cat Railway Panel\n/status\n/users\n/restart_xray';
    await this.call('sendMessage', { chat_id: this.adminId, text: String(text).slice(0, 3500), disable_web_page_preview: true });
    return { status: 200 };
  }
}
