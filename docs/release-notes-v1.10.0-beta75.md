# Cat Client 1.10.0-beta75 (versionCode 142) — فیکس ریشه‌ای کرشِ باز شدن اپ (پنل 6.52.0)

## 🩺 کرش «اپ باز نمی‌شود» — ریشه پیدا و بسته شد
**علت دقیق:** در beta71 موتور «مانیتور پنل» به اپ اضافه شد که از `androidx.work` (WorkManager) استفاده می‌کند. WorkManager در **استارتاپ** — از طریق `androidx.startup.InitializationProvider` و **قبل از اجرای هر کد اپ** — دیتابیس Room خود (`WorkDatabase_Impl`) را با **reflection** می‌سازد. قاعدهٔ قدیمی Room فقط *نام کلاس* را نگه می‌داشت، و **R8 در حالت full** (پیش‌فرض AGP 8، فعال در همهٔ بیلدهای release این پروژه) **سازندهٔ بدون‌آرگومان آن را حذف می‌کرد** → پروسه در همان لحظه می‌مرد:
```
RuntimeException: Unable to get provider androidx.startup.InitializationProvider
Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
```
یعنی **همهٔ APKهای release از beta71 تا beta74** این کرش را داشتند (بیلدهای debug نه — R8 روی آن‌ها اجرا نمی‌شود).

**فیکس (دو لایه):**
1. `app/proguard-rules.pro`: کیپ‌روی صریح `-keep class * extends androidx.room.RoomDatabase { <init>(); }` (همان فیکسی که چند پروژهٔ دیگر این ماه تأیید کرده‌اند)
2. **گارد دائمی در CI**: بعد از `assembleRelease`، خودِ `usage.txt` خروجی R8 خوانده می‌شود؛ اگر `WorkDatabase_Impl.<init>()` دوباره حذف شده باشد، **بیلد fail می‌شود** — این باگ دیگر نمی‌تواند بی‌صدا برگردد

## اگر همین حالا اپت باز نمی‌شود
- **راه فوری:** از رلیز beta74 فایل `app-arm64-v8a-debug.apk` را نصب کن (نسخهٔ debug بدون R8 است و باز می‌شود) — یا مستقیم همین beta75 را نصب کن.
- اگر پیام «App not installed» دیدی: نسخهٔ قبلی را از تنظیمات حذف کن و دوباره نصب کن (امضای همهٔ بیلدهای CI یکی و پایدار است).

## بازبینی فایل‌های پنل (در همین دور)
- کل سویه: ۱۵/۱۵ + تونل e2e + ECH (۱۸ بند) + کیفیت کشورها (۱۶ بند) — روی سورس و روی آرتیفکت مبهم: **همه سبز**
- ابمب‌سازی: امضای متنی صفر (vless/trojan/proxyip=0) · `dist-panel` و `cat-panel.html` توسط CI رفرش می‌شوند · ویزارد/دکمهٔ دیپلوی/گیت سلامت دیپلوی — قبلاً بسته شده بودند و در این دور هم دوباره تأیید شدند

## نصب
APK بتا۷۵. پنل همان ۶.۵۲.۰ (فایل‌های پنل در این دور تغییری نداشتند).
