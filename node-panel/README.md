# 🐱 پنل Cat روی هاست‌های Node (KataBump · Lucity · Shiprr · Railway · SmarterASP · VPS)

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

## 📱 راه آسان: از داخل برنامهٔ Cat Client

تب **ابر (Cloud)** ← بخش «🆓 پنل Cat روی هاست‌های دیگر» ← برای هر هاست یک کارت با همین دکمه‌ها:

| دکمه | چه می‌کند |
| --- | --- |
| ⚡ نصب خودکار | KataBump: با کلید API خودتان (`ptlc_…`) فایل‌ها را می‌گذارد، Node را 20+ می‌کند، روشن می‌کند، تست می‌کند و کانفیگ اضافه می‌کند. Lucity/Shiprr: با توکن GitHub یک مخزن خصوصی با فایل‌ها می‌سازد. |
| 📦 ذخیرهٔ فایل‌ها | همین فایل‌ها + `cat-config.json` را در یک zip در پوشهٔ Download می‌گذارد. |
| ✍️ پنلم را اضافه کن | آدرس را می‌گیرد، پنل و WebSocket را از گوشی تست می‌کند و کانفیگ‌ها را اضافه می‌کند. |
| 🔁 تمدید / به‌روزرسانی | KataBump: شمارش معکوس ۴ روزه + یادآوری (تمدید کپچا دارد و خودکار نمی‌شود). Lucity/Shiprr: فایل‌های نسخهٔ جدید را در مخزن می‌گذارد. |
| 📋 پنل‌های من | باز کردن پنل، گرفتن دوبارهٔ کانفیگ، تست، دامنه + TLS، حذف از فهرست. |

## `cat-config.json` (اختیاری)

اگر کنار `index.js` باشد، launcher این کلیدها را از آن می‌خواند (متغیر محیطی هاست همیشه برنده است):
`UUID`، `TROJAN_PASS`، `PANEL_PASSWORD`، `CAT_DATA_DIR`، `CAT_NO_DISK`، `CAT_PANEL_TAG`، `KV_REST_API_URL`، `KV_REST_API_TOKEN`، `CAT_HOST`، `CAT_CF_FRONT`، `CAT_KEEPALIVE`.
برنامه این فایل را با UUID و رمز Trojan خودِ گوشی می‌سازد تا لینک اشتراک (`/sub/UUID`) را بدون کپی کردن بداند.
کلیدهای دیگر نادیده گرفته می‌شوند. این فایل رمز دارد؛ مخزن را **خصوصی** نگه دارید.

## ۱) KataBump — رایگان

**ساخت حساب:** dashboard.katabump.com/auth/register ← نام، نام خانوادگی، ایمیل، رمز ← Create account ← لینک تأیید ایمیل.
**ساخت سرور:** داشبورد ← Create a server ← Node.js ← Free ← نام ← Create (۲ تا ۵ دقیقه).
**پنل کنترل:** داشبورد ← See more ← Access server ← Identifier و رمز ← control.katabump.com/auth/login.
(در برنامه: کارت KataBump ← «🧭 از صفر شروع کن» همین قدم‌ها را با دکمهٔ باز کردن هر صفحه نشان می‌دهد.)

> ⚠️ **قوانین KataBump (Acceptable Use، بند 2.4) اجرای پروکسی/VPN/تونل را ممنوع کرده است** و هر خانه فقط یک
> سرویس رایگان مجاز است (بند 2.5). ممکن است سرور یا حساب بدون خبر پاک شود؛ ریسکش با خود شماست.
> Lucity و Shiprr چنین منعی در قوانینشان ننوشته‌اند.

1. در katabump.com ثبت‌نام کنید و یک سرور **Node.js** رایگان بسازید (۳۰۸ مگ رم و ۷۱۶ مگ دیسک کافی است).
2. در پنل سرور ← **Files** ← دو فایل `index.js` و `package.json` را آپلود کنید (یا فایل zip را آپلود و Unarchive کنید).
3. در تب **Startup** فایل اجرایی را `index.js` بگذارید و نسخهٔ Node را **20 یا بالاتر** انتخاب کنید.
4. **Start** را بزنید. در کنسول خطی مثل `Cat Panel … listening on 0.0.0.0:25565` می‌آید.
5. آدرس سرور (آی‌پی:پورت که در صفحهٔ سرور نوشته) را در مرورگر باز کنید: `http://آی‌پی:پورت/panel`.

نکته‌ها:
- کاربرها و تنظیمات در پوشهٔ `cat-data` روی دیسک خود سرور می‌مانند؛ با ری‌استارت پاک نمی‌شوند.
- KataBump سرور رایگان را **هر ۴ روز** باید تمدید کنید (dashboard.katabump.com ← Renew now، با کپچا؛ فقط در ۲ روز آخر ممکن است)، وگرنه حذف می‌شود.
  برنامهٔ Cat Client شمارش معکوس دارد و یک روز قبل از مهلت یادآوری می‌فرستد.
- کانفیگ مستقیم (بدون TLS) ساده است ولی ممکن است در بعضی اپراتورها زود بسته شود. برای کانفیگ‌های
  **TLS + آی‌پی تمیز Cloudflare**:
  1. دامنهٔ رایگان: داشبورد KataBump ← **My Domains** ← Get my first domain ← یک اسم با پسوند `.kdns.fr` ← Order the domain.
     در Cloudflare (رایگان) ← Add a site ← همان دامنه ← Free ← دو Nameserver را در صفحهٔ دامنه در KataBump (بخش Nameservers) بگذارید و صبر کنید تا Active شود.
  2. در Cloudflare یک رکورد **A** با ابر نارنجی بسازید که به آی‌پی سرور اشاره کند.
  3. در Cloudflare ← Rules ← **Origin Rules** یک قانون بسازید: «Destination Port» را روی پورت سرور بگذارید.
     و با **Configuration Rules** (یا SSL/TLS کل دامنه) حالت را برای همان نام روی **Flexible** بگذارید (سرور خودش TLS ندارد؛ با Full خطای 525/526 می‌گیرید).
  4. حالا پنل را از `https://نام.دامنه‌شما/panel` باز کنید و لینک اشتراک را **از همین آدرس** بردارید؛
     پنل خودش کانفیگ‌های Cloudflare (آی‌پی تمیز، پورت‌های 443/8443/2053/…) می‌سازد.

## ۲) Lucity (lucity.cloud) — پولی (حدود ۵ فرانک در ماه، با ۵ فرانک اعتبار)

1. یک مخزن GitHub **خصوصی** بسازید و همین فایل‌ها را داخلش بگذارید (یا در برنامه «⚡ ساخت خودکار»).
2. Lucity ← Login with GitHub ← New ← GitHub Repository ← همان مخزن. Lucity خودش `npm install` و `npm start` را اجرا می‌کند و `PORT` را می‌دهد.
3. Settings ← Platform Domain ← **Generate Domain** (بدون آن آدرسی ندارید) ← آدرس را با `/panel` باز کنید. کانفیگ‌ها TLS روی 443 هستند.
4. برای ماندگاری کاربرها: یک **Volume** بسازید و متغیر `CAT_DATA_DIR` را مسیر آن بگذارید
   (یا Upstash Redis — پایین را ببینید).

## ۳) Shiprr (shiprr.app) — پولی (کیف پول با ۵ یورو اعتبار هدیه)

1. مثل Lucity: مخزن GitHub خصوصی با همین فایل‌ها ← shiprr.app/registration ← Apps ← New app ← همان مخزن (منطقه Europe).
2. دیسک Shiprr **موقت** است؛ پس یکی از این دو کار را بکنید تا با هر ری‌استارت چیزی پاک نشود:
   - ساده: `UUID` و `TROJAN_PASS` ثابت (در `cat-config.json` یا متغیرها؛ لینک اصلی ثابت می‌ماند) و `CAT_NO_DISK=1`.
   - کامل: یک Upstash Redis رایگان بسازید و `KV_REST_API_URL` و `KV_REST_API_TOKEN` را بگذارید.
3. آدرس `*.shiprr.dev` را با `/panel` باز کنید. مستندات Shiprr دربارهٔ WebSocket چیزی نمی‌گوید؛ «✍️ پنلم را اضافه کن» در برنامه آن را واقعاً تست می‌کند.

## ۴) SmarterASP.NET — ۶۰ روز آزمایشی رایگان (ویندوز / IIS)

SmarterASP Node.js را با `httpPlatformHandler` در IIS اجرا می‌کند و `npm install` نمی‌زند؛ پس از داخل برنامه
(کارت SmarterASP ← «📦 ذخیرهٔ فایل‌ها») zip مخصوص آن را بگیرید: علاوه بر این فایل‌ها `web.config`، پوشهٔ
`node_modules/ws` و پوشهٔ `App_Data` (تنها جای قابل‌نوشتن) دارد.

1. در کنترل‌پنل ← سایت ← File Manager، zip را در پوشهٔ اصلی سایت آپلود و Extract کنید (`web.config` کنار `index.js`).
2. Node.js را برای سایت روشن کنید. آدرس موقت سایت + `/panel` را باز کنید (بار اول تا ۲ دقیقه).
3. لاگ در `App_Data/cat-node*.log` است. Node قدیمی؟ `node.exe` نسخهٔ 22 را در `App_Data` بگذارید و در `web.config`
   `processPath=".\App_Data\node.exe"` بنویسید.

`web.config` هر آدرس را به `/__cat_edge/<on|off>/<IP>/…` بازنویسی می‌کند تا launcher بفهمد گوشی با https آمده یا http
(`CAT_BEHIND_IIS=1`)؛ کاربرها در `App_Data/cat-data` می‌مانند. هاست اشتراکی است و ممکن است WebSocket یا اتصال بیرونی را
محدود کند — برنامه بعد از افزودن واقعاً تست می‌کند.

## متغیرهای اختیاری

| متغیر | کارش |
| --- | --- |
| `CAT_PANEL_TAG` | یک نسخهٔ مشخص (مثلاً `v1.10.0-beta190`) به‌جای جدیدترین |
| `CAT_DATA_DIR` | محل ذخیرهٔ کاربرها و تنظیمات (پیش‌فرض `./cat-data`) |
| `CAT_NO_DISK=1` | هیچ چیز روی دیسک ذخیره نشود |
| `KV_REST_API_URL` + `KV_REST_API_TOKEN` | Upstash Redis به‌جای دیسک (همه‌جا کار می‌کند) |
| `UUID` / `TROJAN_PASS` | شناسهٔ اصلی ثابت |
| `PANEL_PASSWORD` | رمز اولیهٔ پنل به‌جای admin |
| `CAT_HOST` | نام هاست (`katabump`، `bothosting`، `railway`، `lucity`، `shiprr`، `smarterasp`) — برنامه خودش می‌نویسد. با `katabump` پنل می‌داند جلوی پورت هیچ پراکسی‌ای نیست و هدرهای «forwarded» را از کاربر قبول نمی‌کند |
| `CAT_CF_FRONT` | نام Worker «🟧 جلوی Cloudflare» (فقط هاست‌هایی که نام HTTPS روی ۴۴۳ دارند) — خطوط «🟧» اول اشتراک می‌آیند |
| `CAT_KEEPALIVE=0` | SmarterASP/IIS: سایت هر ۹ دقیقه بیدار نگه داشته نشود (IIS سایتِ بی‌کار را خاموش می‌کند) |

**ترکیب هاست‌ها:** KataBump (فقط یک پورت، بدون TLS) را در پنل Cloudflare خودتان ← «🔗 اتصال پنل‌ها» وصل و «خروجی» کنید:
گوشی با Cloudflare (TLS و آی‌پی تمیز) حرف می‌زند و سایت‌ها آی‌پی KataBump را می‌بینند. هاست‌های HTTPS (Railway، Bot-Hosting،
Lucity، Shiprr، SmarterASP با https) اگر نامشان فیلتر شد: برنامه ← همان هاست ← «🟧 جلوی Cloudflare».

## اگر بالا نیامد

- `could not start … github.com`: هاست باید **یک بار** به github.com دسترسی داشته باشد؛ بعد از آن نسخهٔ ذخیره‌شده
  (پوشهٔ `cat-code`) استفاده می‌شود. بدون اینترنت: `catclient.worker.js` و `cat-panel-adapter.mjs` و
  `cat-node-panel.sha256` را از صفحهٔ Release در یک پوشه بگذارید و `CAT_PANEL_SOURCE_DIR` را مسیر آن کنید.
- `SHA-256 mismatch`: فایل دانلودشده با فهرست Release یکی نیست و عمداً اجرا نشد؛ دوباره Start کنید.
- `Cannot find package 'ws'`: هاست `npm install` را اجرا نکرده؛ در کنسول `npm install` بزنید یا `package.json` را کنار `index.js` بگذارید.

---

## English (short)

Easiest: Cat Client → Cloud tab → one card per host (⚡ automatic install / 📦 save the files / ✍️ add my panel / 🔁 renew-update / 📋 my panels).
**KataBump's acceptable-use policy (§2.4) forbids proxies/VPNs — the server may be deleted.** An optional `cat-config.json` next to `index.js`
may set UUID, TROJAN_PASS, PANEL_PASSWORD, CAT_DATA_DIR, CAT_NO_DISK, CAT_PANEL_TAG, KV_REST_API_URL, KV_REST_API_TOKEN,
CAT_HOST, CAT_CF_FRONT, CAT_KEEPALIVE (host env wins). `CAT_HOST=katabump` = direct port: forwarded headers from clients are ignored.

Upload `index.js` + `package.json` to any Node 20+ host and start it. The launcher downloads the newest Cat Panel
release (`catclient.worker.js` + `cat-panel-adapter.mjs`), verifies both against `cat-node-panel.sha256`, caches them
in `./cat-code` and serves the panel on `SERVER_PORT` / `PORT` (default 8080). Open `/panel`, sign in with
admin / admin and change the password. Storage: `./cat-data` (or `CAT_DATA_DIR`), Upstash via
`KV_REST_API_URL` + `KV_REST_API_TOKEN`, or none with `CAT_NO_DISK=1`. Configs follow how the phone reached the host:
direct port → plain ws on that port; HTTPS edge → TLS 443 with the host's own SNI; Cloudflare → the full CF set.
License: GPL-3.0 (Cat Client).
