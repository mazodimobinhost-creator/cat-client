# Cat Client 1.10.0-beta95 (versionCode 162) — انتظارِ درست، پیامِ راست، و برچسبِ تست‌ها

این نسخه یک فیکسِ کد و یک فیکسِ متن است، به‌علاوهٔ مستندات. علتِ اصلی از گزارشِ beta94 درآمد.

## ۱) انتظارِ فعال‌شدن مسیر: از ۵۰ ثانیه به ۹۰ ثانیه، و به‌جای «خراب» ⇒ «هنوز در حال فعال‌شدن»

در نسخهٔ قبل، `waitForPanel` ۱۰ بار با فاصلهٔ ۵ ثانیه (۵۰ ثانیه) تلاش می‌کرد و اگر پنل
هنوز زنده نشده بود، کاربر پیامِ خرابی می‌گرفت. روی حسابِ واقعی اندازه‌گیری شده که قفلِ
مسیر تا **۶۶ ثانیه** طول کشید — یعنی بدترین حالتِ اندازه‌گیری‌شده بیرونِ بودجه بود.

حالا:

- بودجه: **۱۹ × ۵۰۰۰ms = ۹۰ ثانیه** (پروب‌ها در 0, 5, 10, …, 90 ثانیه). چون حلقه در
  **اولین پاسخِ سالم** برمی‌گردد، پنلِ سالم هیچ‌وقت بیشتر از قبل منتظر نمی‌ماند.
- پاسخِ `404` خودِ کلودفلر با بدنهٔ **HTML** ⇒ حالتِ میانی `PENDING` (پنل همیشه JSON است،
  پس HTML هرگز ما نیستیم) — نه خرابی.
- دیالوگِ **«⏳ پنل هنوز در حال فعال‌شدن است»** با دکمه‌های **بررسی دوباره** / **بازکردن پنل** / انصراف،
  به‌جای هشدارِ خرابی. `MainActivity` اول شاخهٔ `PENDING` را می‌گیرد و فقط بعد به recovery می‌رسد.

**گاردِ بازگشت:** `scripts/panels/route-pending.test.mjs` (۶ سنجه) وجودِ بودجهٔ ≥۹۰s، شرطِ
PENDING روی ۴۰۴+HTML، **ترتیبِ** PENDING قبل از recovery، و هر دو لوکیل را قفل می‌کند.
با جابه‌جا کردنِ عمدیِ دو شاخه، تست با پیامِ همین گارد قرمز می‌شود.

## ۲) متنِ حالتِ میانی، به‌روزِ چیزی که واقعاً اندازه‌گیری شد

پروبِ **فقط-خواندنی** روی همین حساب (فقط `GET`؛ پیوستِ کامل در `docs/cf-live-test-6.67.0.md`):

```
GET /user/tokens/verify     -> 200   {"status":"active"}
GET /accounts/<id>          -> 403   {"code":9109,"message":"User was deactivated"}
GET /workers/subdomain      -> 403   {"code":10000,"message":"Authentication error"}
GET /workers/scripts        -> 403   {"code":10000,"message":"Authentication error"}
GET /workers/scripts/edge-9ccq0/settings -> 429 {"code":10429,…}
edge-9ccq0.zojfup.workers.dev/health     -> 404, 17 B, "error code: 1042"
edge-vgiab.zojfup.workers.dev/health     -> 404, 17 B, "error code: 1042"
wrangler tail edge-9ccq0 / edge-vgiab    -> refused 10429 (rate limited)
```

یعنی: **توکن active است ولی حساب در حالتِ «User was deactivated» است** و سطحِ ریسورس Workers
با ۴۰۳ بسته می‌شود؛ و آن دو نام الان هیچ ورکری پشتشان ندارند (`1042`). پس پیامِ حالتِ میانی
دیگر نمی‌گوید «خودش درست می‌شود، فقط صبر کن» — می‌گوید ممکن است تا وقتی وضعیتِ آن حساب
از سمتِ کلادفلر درست نشود زنده نشود، و تأکید می‌کند **آپلود سالم است و نیازی به بازسازی نیست**.

## ۳) برچسب‌بندیِ تست‌ها — `docs/tests-and-labels.md`

هر تست یکی از سه برچسب را دارد: **کلودفلر واقعی** / **workerd محلی** / **دستگاه**. خلاصه:

| تست | برچسب | وضعیت |
| --- | --- | --- |
| ورک‌فلوهای Actions با اعتبارنامهٔ واقعی (دیپلوی/A2/B/C) | کلودفلر واقعی | success (رانِ 38264339482) |
| پروبِ فقط-خواندنیِ دو ورکر | کلودفلر واقعی | DNS✅، `/health`⇒۴۰۴ `1042` |
| `npm test` (۵۰ سنجه) و `route-pending.test.mjs` (۶ سنجه) | workerd محلی | ۵۰/۰ و ۶/۰ |
| `workerd serve` روی `catpanel.obf.js` با کانفیگِ آپلودِ اپ | workerd محلی | `/health` ۲۰۰، `/api/version` ۲۰۰، `panel_crash` ۵۰۰ |
| `kotlin-syntax-check.py` | workerd محلی (گرامر، بدون اجرا) | ۱۰۸/۱۱۲ |
| «Smoke test - release APK launches on an emulator» | دستگاه | **skipped** — صادقانه: تست‌نشده |

## ۴) خروجیِ محلیِ همان لحظه (برچسب: workerd محلی)

همان فایل و همان تنظیماتِ `cfUploadWorker` (main_module، compat `2025-03-04`، `nodejs_compat`،
بایندینگ‌های `OPEN_PANEL`/`OPEN_SUB`) روی موتورِ خودِ کلودفلر (`workerd 2026-10-13`):

```
/health                 200 application/json  {"ok":true,"version":"6.67.0","kv":false}
/api/version            200 application/json  {"ok":true,"panel":"cat-panel","version":"6.67.0",…,"open":true}
/api/selfcheck?throw=1  500 application/json  {"error":"panel_crash","title":"Worker Exception Handled",…}
/api/diag               200 application/json  {"…","kv":"NOT BOUND — every write is memory-only, attach CAT_KV",…}
```

و هر سه آرتیفکت (‎6.67.0‎): `catpanel.obf.js` = 1,243,155 B، `catpanel.min.js` = 494,032 B،
`catclient.worker.js` = 655,438 B — همه با `node --check` پارس می‌شوند.

## ۵) چه چیزی عوض نشد

نسخهٔ پنل همان **۶.۶۷.۰** است و ورکرِ مستقر (`catclient.worker.js`) هم دست نخورده؛ فقط
رفتارِ اپ در تشخیصِ وضعیت و متن‌هایش. برای همین `versionCode` یک پله بالا رفت (۱۶۲) و
لینک‌های نصب همان ساختارِ قبلی است.

## ۶) محدودیتِ صادقانه

کدِ Kotlin در محیطِ این گفتگو کامپایل نمی‌شود (ابزارِ اندروید در دسترس نیست)؛ منطق با
معادلِ قابل‌اجرا و گاردِ بایتی بررسی شد و **تأییدِ نهایی همان بیلدِ همین انتشار است**.
اگر ۱۱۰۱ روی حسابِ خودت دوباره دیدی، آن را با متنِ خام و ترجیحاً `wrangler tail` بفرست؛
و اگر حساب در حالتِ «User was deactivated» است، اول آن وضعیت باید از سمتِ کلادفلر درست شود.
