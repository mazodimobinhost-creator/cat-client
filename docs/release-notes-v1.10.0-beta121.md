# Cat Client 1.10.0-beta121 / Cat Panel 6.69.9

نسخهٔ آزمایشی برای تست مالک.

- API وضعیت self-heal فقط برای مالک و بدون افشای secret.
- پاک‌سازی cache امن با POST، same-origin، confirmation header و rate limit پنج‌دقیقه‌ای.
- عدم تغییر KV، کاربران، session، password و subscription token در maintenance.
- ProxyIPهای سفارشی در Base64، Clash، Sing-box و Xray حفظ و تست شده‌اند.
- idle timeout واقعی tunnel و `OPEN_SUB=false` از نسخه قبل حفظ شده‌اند.

این انتشار موفقیت روی Cloudflare production، گوشی یا اپراتور واقعی را پیشاپیش ادعا نمی‌کند.
