# 🐱 پنل Cat روی هاست‌های Node (KataBump · Lucity · Shiprr · Railway · VPS)

این پوشه فقط **۲ فایل** دارد: `index.js` و `package.json`. همین‌ها را روی هاست بگذارید و روشن کنید؛
بقیه کار خودکار است: آخرین نسخهٔ پنل Cat از صفحهٔ Release همین پروژه دانلود می‌شود، با فهرست SHA-256
همان Release چک می‌شود (فایل دست‌کاری‌شده اجرا نمی‌شود) و پنل روی پورتی که هاست می‌دهد بالا می‌آید.

بعد از روشن شدن: آدرس هاست + `/panel` را باز کنید ← نام کاربری `admin` و رمز `admin` ← **رمز را عوض کنید** ←
لینک اشتراک را در اپ Cat (یا v2rayNG/Hiddify…) اضافه کنید.

> پنل خودش می‌فهمد گوشی از چه راهی به آن رسیده و فقط کانفیگ‌هایی می‌سازد که از همان راه کار می‌کنند:
> - مستقیم به آی‌پی و پورت هاست (مثل KataBump): **یک VLESS + یک Trojan** روی همان آی‌پی:پورت، بدون TLS.
> - از پشت HTTPS خود هاست (Lucity / Shiprr / Railway): **یک VLESS + یک Trojan** روی پورت 443 با TLS و نام همان هاست.
> - از پشت Cloudflare (دامنهٔ رایگان kdns.fr یا دامنهٔ خودتان با ابر نارنجی): همهٔ کانفیگ‌های Cloudflare، با آی‌پی تمیز و پورت‌های مختلف.

---

## ۱) KataBump — رایگان (پیشنهاد اول)

1. در katabump.com ثبت‌نام کنید و یک سرور **Node.js** رایگان بسازید (۳۰۸ مگ رم و ۷۱۶ مگ دیسک کافی است).
2. در پنل سرور ← **Files** ← دو فایل `index.js` و `package.json` را آپلود کنید (یا فایل zip را آپلود و Unarchive کنید).
3. در تب **Startup** فایل اجرایی را `index.js` بگذارید و نسخهٔ Node را **20 یا بالاتر** انتخاب کنید.
4. **Start** را بزنید. در کنسول خطی مثل `Cat Panel … listening on 0.0.0.0:25565` می‌آید.
5. آدرس سرور (آی‌پی:پورت که در صفحهٔ سرور نوشته) را در مرورگر باز کنید: `http://آی‌پی:پورت/panel`.

نکته‌ها:
- کاربرها و تنظیمات در پوشهٔ `cat-data` روی دیسک خود سرور می‌مانند؛ با ری‌استارت پاک نمی‌شوند.
- KataBump سرور رایگان را **هر ۴ روز** باید تمدید کنید (دکمهٔ Renew در سایت)، وگرنه حذف می‌شود.
- کانفیگ مستقیم (بدون TLS) ساده است ولی ممکن است در بعضی اپراتورها زود بسته شود. برای کانفیگ‌های
  **TLS + آی‌پی تمیز Cloudflare**:
  1. در KataBump یک زیردامنهٔ رایگان `*.kdns.fr` بگیرید و Nameserverهایش را روی Cloudflare بگذارید.
  2. در Cloudflare یک رکورد **A** با ابر نارنجی بسازید که به آی‌پی سرور اشاره کند.
  3. در Cloudflare ← Rules ← **Origin Rules** یک قانون بسازید: «Destination Port» را روی پورت سرور بگذارید.
     و در **SSL/TLS** حالت را روی **Flexible** بگذارید (سرور خودش TLS ندارد؛ با Full خطای 525/526 می‌گیرید).
  4. حالا پنل را از `https://دامنه‌شما.kdns.fr/panel` باز کنید و لینک اشتراک را **از همین آدرس** بردارید؛
     پنل خودش کانفیگ‌های Cloudflare (آی‌پی تمیز، پورت‌های 443/8443/2053/…) می‌سازد.

## ۲) Lucity (lucity.cloud) — پولی (حدود ۵ فرانک در ماه، با ۵ فرانک اعتبار)

1. یک مخزن GitHub بسازید و همین دو فایل را داخلش بگذارید.
2. در Lucity پروژهٔ جدید ← از GitHub ← همان مخزن. Lucity خودش `npm install` و `npm start` را اجرا می‌کند.
3. آدرس `*.lucity.app` که می‌دهد را با `/panel` باز کنید. کانفیگ‌ها TLS روی 443 هستند.
4. برای ماندگاری کاربرها: یک **Volume** بسازید و متغیر `CAT_DATA_DIR` را مسیر آن بگذارید
   (یا Upstash Redis — پایین را ببینید).

## ۳) Shiprr (shiprr.app) — پولی (کیف پول با ۵ یورو اعتبار هدیه)

1. مثل Lucity: مخزن GitHub با همین دو فایل ← در Shiprr اپ جدید از آن مخزن.
2. دیسک Shiprr **موقت** است؛ پس یکی از این دو کار را بکنید تا با هر ری‌استارت چیزی پاک نشود:
   - ساده: متغیرهای `UUID` و `TROJAN_PASS` را خودتان بگذارید (لینک اصلی ثابت می‌ماند) و `CAT_NO_DISK=1`.
   - کامل: یک Upstash Redis رایگان بسازید و `KV_REST_API_URL` و `KV_REST_API_TOKEN` را بگذارید.
3. آدرس `*.shiprr.dev` را با `/panel` باز کنید.

## متغیرهای اختیاری

| متغیر | کارش |
| --- | --- |
| `CAT_PANEL_TAG` | یک نسخهٔ مشخص (مثلاً `v1.10.0-beta190`) به‌جای جدیدترین |
| `CAT_DATA_DIR` | محل ذخیرهٔ کاربرها و تنظیمات (پیش‌فرض `./cat-data`) |
| `CAT_NO_DISK=1` | هیچ چیز روی دیسک ذخیره نشود |
| `KV_REST_API_URL` + `KV_REST_API_TOKEN` | Upstash Redis به‌جای دیسک (همه‌جا کار می‌کند) |
| `UUID` / `TROJAN_PASS` | شناسهٔ اصلی ثابت |
| `PANEL_PASSWORD` | رمز اولیهٔ پنل به‌جای admin |

## اگر بالا نیامد

- `could not start … github.com`: هاست باید **یک بار** به github.com دسترسی داشته باشد؛ بعد از آن نسخهٔ ذخیره‌شده
  (پوشهٔ `cat-code`) استفاده می‌شود. بدون اینترنت: `catclient.worker.js` و `cat-panel-adapter.mjs` و
  `cat-node-panel.sha256` را از صفحهٔ Release در یک پوشه بگذارید و `CAT_PANEL_SOURCE_DIR` را مسیر آن کنید.
- `SHA-256 mismatch`: فایل دانلودشده با فهرست Release یکی نیست و عمداً اجرا نشد؛ دوباره Start کنید.
- `Cannot find package 'ws'`: هاست `npm install` را اجرا نکرده؛ در کنسول `npm install` بزنید یا `package.json` را کنار `index.js` بگذارید.

---

## English (short)

Upload `index.js` + `package.json` to any Node 20+ host and start it. The launcher downloads the newest Cat Panel
release (`catclient.worker.js` + `cat-panel-adapter.mjs`), verifies both against `cat-node-panel.sha256`, caches them
in `./cat-code` and serves the panel on `SERVER_PORT` / `PORT` (default 8080). Open `/panel`, sign in with
admin / admin and change the password. Storage: `./cat-data` (or `CAT_DATA_DIR`), Upstash via
`KV_REST_API_URL` + `KV_REST_API_TOKEN`, or none with `CAT_NO_DISK=1`. Configs follow how the phone reached the host:
direct port → plain ws on that port; HTTPS edge → TLS 443 with the host's own SNI; Cloudflare → the full CF set.
License: GPL-3.0 (Cat Client).
