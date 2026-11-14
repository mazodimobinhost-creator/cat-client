# بررسی بستهٔ self-heal ارسالی

## موارد واردشده

- timeout بیکار واقعی برای tunnel: تایمر با عبور هر داده در هر دو جهت reset می‌شود؛ اتصال فعال محدودیت طول عمر ثابت ندارد.
- cleanup قطعی socket و WebSocket در timeout یا پایان pump.
- `OPEN_SUB=false` در deployment پیش‌فرض، مطابق تصمیم مالک.
- تست‌های regression برای قرارداد بالا و جلوگیری از ورود اجزای ناامن patch.

قابلیت‌های error containment، پاسخ-ندادن handler، cacheهای محدود، cron feed، diagnostics، سه probe دقیق نسخه، rollback و Deploy Bot از قبل در Cat وجود داشتند و دوباره‌سازی نشدند.

## موارد واردنشده و علت

- `passThroughOnException`: exception را تعمیر نمی‌کند و ممکن است پاسخ کنترل‌شده را به origin ناموجود واگذار کند.
- `/api/self-heal/clear-cache`: در patch بدون احراز هویت بود.
- `/api/self-heal/status`: جزئیات داخلی و ProxyIPها را عمومی می‌کرد.
- Doctor دارای API token: در شکست خواندن settings با bindings خالی redeploy می‌کرد، source شناور branch را بدون checksum می‌گرفت، exact-version verification و rollback نداشت.
- refresh منابع ProxyIP ثالث: دادهٔ اعتبارسنجی‌نشده را مستقیم وارد مسیر relay می‌کرد.
- `OPEN_PANEL=true`: قفل مدیریت را دور می‌زد.
- `nodejs_compat` و `web_socket_compression`: بدون نیاز اثبات‌شده و قرارداد تست پیشنهاد شده بودند.
- «پاک‌سازی isolate»: Worker امکان restart isolate ندارد؛ پاک‌کردن چند Map چنین اثری ندارد.
- timeout ثابت ده‌دقیقه‌ای patch: اتصال سالم را نیز پس از ده دقیقه قطع می‌کرد.
- کد patch دارای خطای scope بود: `store` بیرون بلوک تعریفش استفاده می‌شد و خطا با catch خالی پنهان می‌شد.

این بررسی تضمین نبودن 1101 یا policy action نیست؛ رخداد واقعی همچنان به Ray ID، Event ID، outcome و Workers Logs نیاز دارد.
