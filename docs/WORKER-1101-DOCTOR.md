# Cat Worker 1101 Doctor

ابزار محلی تشخیص ریسک‌های رایج Cloudflare Worker است. این ابزار برای استتار، تغییر ماهیت ترافیک یا دورزدن enforcement ساخته نشده است و رفع همهٔ خطاهای 1101 را تضمین نمی‌کند.

## بررسی سورس canonical

```bash
npm run doctor:1101 > doctor-report.json
```

گزارش شامل نسخه، اندازه، SHA-256، خطاهای قطعی و هشدارهای احتمالی است. خروجی قبل از چاپ، credentialهای شناخته‌شده را حذف می‌کند.

## بررسی deployment واقعی

```bash
npm run doctor:1101 -- --url https://YOUR-WORKER.workers.dev > doctor-report.json
```

ابزار `/api/version` را سه مرتبه با nonce متفاوت و `cache-control: no-cache` می‌خواند. موفقیت فقط وقتی اعلام می‌شود که هر سه پاسخ HTTP 2xx، تازه و دقیقاً هم‌نسخهٔ source باشند. در هر mismatch، cache hit، timeout یا exception خروجی fail-closed است.

## تفسیر

- `ok: true` یعنی checks همین ابزار موفق بوده‌اند؛ به معنی تضمین نبودن policy action یا خرابی اپراتور نیست.
- `DYNAMIC_CODE` و lifecycle ناقص WebSocket خطای audit محسوب می‌شوند.
- body buffering، concurrency نامحدود، catch خالی و Promise مشکوک هشدارند و باید با context بررسی شوند.
- برای 1101 واقعی همچنان Ray ID، Event ID، Workers Logs outcome و stack همان invocation لازم است.

هیچ API token، password، UUID، cookie یا محتوای KV را داخل گزارش عمومی قرار ندهید.
