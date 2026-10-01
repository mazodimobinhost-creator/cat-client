# Cat Client v1.10.0-beta1 — پنل v6 + کشورها + ربات تلگرام (پیش‌انتشار) 🧪

> **بتا**: تغییرات اپ روی دستگاه واقعی هنوز تست نشده‌اند؛ APK از CI ساخته شده و امضای همان کلید عمومی CI را دارد. اگر مشکلی دیدی، Issue بزن یا در تلگرام بگو.

## پنل (catclient.worker.js → v6.4.0 — بازنویسی کامل)
- **بدون لیمیت**: هیچ حساب‌وکتاب ترافیک، هیچ نوشتن دوره‌ای KV، هیچ اسکن سمت ورکر (همان مدل BPB/Zeus). ذخیرهٔ تنظیمات = یک نوشتن KV.
- **داشبورد به سبک Zeus** با پالت بنفش Cat، ورود با رمز/UUID، مدیریت کاربران با انقضا، لینک‌های `/sub` `/clash` `/singbox` و **`/xray` (JSON کامل با Fragment)**.
- **خروجی ثابت (Chain)**: SOCKS5/HTTP روی سرور خودت → IP و کشور همیشه ثابت. `?addr=&limit=1` برای پین‌کردن یک آدرس.
- **کشورها**: برچسب کشور برای هر آدرس (`ip#DE`)، انتخاب کشور اصلی، گروه‌های Clash `fallback` (فقط وقتی همهٔ آی‌پی‌های آن کشور بسته شد به کشور بعدی می‌رود)، Proxy IP هم‌کشور.
- **مسیریابی**: سایت‌های ایرانی مستقیم (پیش‌فرض) + مسدودسازی تبلیغات. **Fragment / ALPN / Cipher suites** (پیش‌فرض خاموش، با تأیید).
- **ربات تلگرام**: `/users /add /renew /toggle /del /link /ips /country /status`.

## اپ
- اگر کشور انتخابی هیچ سرور زنده‌ای نداشت → اتصال خودکار با اعلان (انتخاب حفظ می‌شود).
- اسکنر: **دامنه + IPv4 + IPv6** (فقط اگر شبکه واقعاً IPv6 داشته باشد)، اعمال هم‌زمان بهترین v4 و v6، دکمهٔ **«ارسال به Cat Panel (با کشور)»**.
- سوئیچ **مسدودسازی تبلیغات** در مسیریابی.
- ادغام با v1.9.48–v1.9.51 (اسکنر چند‌SNI، دکمهٔ Deploy to Cloudflare، گیت نشست).

## ناسازگاری
- پنل v6 داده‌های پنل v5 (کلیدهای KV قدیمی) را نمی‌خواند؛ کاربران را دوباره بساز یا از Backup/Restore v6 استفاده کن. ویژگی‌های سمت ورکر v5 مثل «اسکنر دقیق» عمداً حذف شدند (اسکن روی گوشی انجام می‌شود).

## Setup wizard (Cloud tab)

A new card at the top of the Cloud tab walks a first-time user through the whole
"I have nothing yet" path in four dialogs:

1. **Welcome** — what will happen, and that everything runs on the user's own Cloudflare account.
2. **Token** — one tap opens Cloudflare's token-template page with the right scopes pre-selected;
   the pasted token is verified (`verifyToken`) before moving on, with inline errors on failure.
3. **Options** — worker name (sanitised to `[a-z0-9-]`) and an optional panel password.
4. **Deploy** — progress dialog → `deployBuiltIn` (worker + KV binding) → token remembered for
   future one-tap panel updates → the deployment appears in the history list.

The final screen offers **Import & connect** (opens the subscription dialog pre-filled),
**Scan clean IPs** (switches to the scanner with the panel host as SNI) and **Open panel**.
