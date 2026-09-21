# Cat Panel — Cloudflare Worker

پنل سبک Cat Panel برای ساخت subscription لینک‌های VLESS روی Worker. این پروژه **خودش هسته VPN یا اسکنر IP نیست**؛ Worker فقط وقتی به‌عنوان endpoint ترافیک استفاده می‌شود که کد انتقال WebSocket/TCP معتبر و مجاز در اختیار داشته باشید. Cloudflare Worker به‌تنهایی «IP سفید» یا تضمین دورزدن محدودیت ایجاد نمی‌کند.

## Deploy

```bash
cd workers/cat-panel
npm i -D wrangler
npx wrangler login
npx wrangler secret put UUID          # UUID v4
npx wrangler secret put ADMIN_TOKEN   # یک رمز تصادفی بلند
npx wrangler secret put PROXY_IPS     # اختیاری: IPها با comma جدا شوند
npx wrangler deploy
```

پس از deploy، `/` پنل و `/sub` subscription را ارائه می‌کند. پنل با `X-Admin-Token` محافظت شده است. ابتدا `SNI` را در `wrangler.toml` به hostname واقعی Worker یا دامنه متصل تغییر دهید. برای کلاینت‌هایی مثل v2rayNG، آدرس subscription را وارد کنید.

## نکات امنیتی و محدودیت

- توکن و UUID را در Git commit نکنید؛ از Cloudflare Secrets استفاده کنید.
- فقط IP/domainهایی را وارد کنید که مالک آن هستید یا اجازه استفاده دارید.
- Workerهای رایگان محدودیت درخواست/UDP دارند؛ تماس صوتی/تصویری و UDP تضمین نمی‌شود.
- اتصال واقعی VLESS نیازمند هسته/relay سازگار است؛ این scaffold عمداً relay ناشناخته یا open proxy ایجاد نمی‌کند.
- قبل از استفاده عمومی، با `curl https://DOMAIN/health` تست کنید.

## بررسی پروژه‌های فهرست‌شده

نام‌هایی مانند BPB Worker Panel، Tabora و پروژه‌های VLESS Worker از الگوی Worker + subscription استفاده می‌کنند. این Cat Panel فقط بخش قابل نگهداری و کم‌خطر آن الگو (تنظیمات، احراز هویت و تولید لینک) را پیاده می‌کند؛ کپی‌کردن کد دیگران یا ادعای سازگاری کامل با Nova/Zeus/... بدون منبع و تست قابل اتکا نیست.
