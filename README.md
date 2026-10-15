<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="104" alt="Cat Client icon">

# Cat Client

### اپ اندروید + پنل Cloudflare + ابزارهای بررسی مسیر

**یک مجموعهٔ فارسی/انگلیسی برای مدیریت اتصال و پنل شخصی، با نصب fail-closed و تست‌های قابل مشاهده**

<p>
  <a href="https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-arm64-v8a.apk"><img alt="Download ARM64" src="https://img.shields.io/badge/Download-ARM64_APK-7c3aed?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-universal.apk"><img alt="Download Universal" src="https://img.shields.io/badge/Download-Universal_APK-a855f7?style=for-the-badge&logo=android&logoColor=white"></a>
</p>
<p>
  <a href="https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta118"><img alt="Release page" src="https://img.shields.io/badge/Release-beta118-111827?style=flat-square&logo=github"></a>
  <a href="docs/error-1101-fa.md"><img alt="1101 help" src="https://img.shields.io/badge/Help-Error_1101-dc2626?style=flat-square&logo=cloudflare&logoColor=white"></a>
  <a href="https://github.com/mazodimobinhost-creator/cat-client/issues/new"><img alt="Report issue" src="https://img.shields.io/badge/Report-Issue-2563eb?style=flat-square&logo=github"></a>
</p>

[![Android CI](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/android.yml/badge.svg?branch=arena%2Fc5b36768-cat-client)](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/android.yml)
[![Panel tests](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/panel.yml/badge.svg?branch=arena%2Fc5b36768-cat-client)](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/panel.yml)
[![UI E2E](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/ui-e2e.yml/badge.svg?branch=arena%2Fc5b36768-cat-client)](https://github.com/mazodimobinhost-creator/cat-client/actions/workflows/ui-e2e.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

</div>

> [!WARNING]
> **`beta118` نسخهٔ آزمایشی است.** CI موفق است، اما تست گوشی فیزیکی و deploy واقعی روی حساب Cloudflare هنوز تأیید نشده‌اند. Wizard فقط برای نصب تازه است و Worker موجود را overwrite نمی‌کند.

## از کجا شروع کنم؟

| کاری که می‌خواهید انجام دهید | مسیر سریع | وضعیت |
|---|---|---|
| نصب روی بیشتر گوشی‌های جدید | **[دانلود ARM64](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-arm64-v8a.apk)** | Test Candidate |
| نصب وقتی ABI را نمی‌دانید | **[دانلود Universal](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-universal.apk)** | حجم بیشتر |
| نصب پنل روی نام Worker آزاد | **[Wizard 1.3.1](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/catclient.wizard.js)** | فقط fresh install |
| بررسی Worker دارای 1101 | **[راهنمای تشخیص 1101](docs/error-1101-fa.md)** | بدون حذف داده |
| توسعه و اجرای تست‌ها | **[ساخت و تست](#-ساخت-و-تست)** | JDK + Android SDK + Node |

## نمای کلی

| Android | Cat Panel | ایمنی استقرار |
|---|---|---|
| Mihomo، subscription، QR و ابزارهای شبکه | Worker تک‌فایلی، کاربران، خروجی‌های مختلف و KV | data-plane پیش‌فرض خاموش |
| فارسی/انگلیسی، RTL و تم روشن/تیره | health، diagnostics و backup ماسک‌شده | سه health متوالی برای نصب تازه |
| کنترل update، checksum، ABI و امضا | normal minified ESM | update Worker موجود fail-closed |

<details>
<summary><strong>فهرست بخش‌ها</strong></summary>

- [دانلودها](#-دانلود)
- [قابلیت‌های اصلی](#-قابلیتهای-اصلی)
- [پنل Cloudflare](#-پنل-cloudflare)
- [تشخیص Error 1101](#-خطای-cloudflare-1101)
- [وضعیت تست‌ها](#-وضعیت-تأیید)
- [معماری](#-معماری)
- [ساخت و تست](#-ساخت-و-تست)
- [امنیت و مشارکت](#-امنیت-و-گزارش-مشکل)
- [English summary](#english-summary)

</details>

## 📥 دانلود

### Test Candidate فعلی

| فایل | کاربرد |
|---|---|
| [ARM64 APK](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-arm64-v8a.apk) | بیشتر گوشی‌های جدید |
| [Universal APK](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/CatClient-V1.10.0-beta118-universal.apk) | وقتی ABI دستگاه را نمی‌دانید |
| [SHA256SUMS](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/SHA256SUMS) | بررسی صحت APKها |
| [Worker ESM](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/catclient.worker.js) | artifact عادی و minified پنل |
| [Wizard 1.3.1](https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta118/catclient.wizard.js) | فقط نصب تازه |

[صفحه کامل beta118 و همه ABIها](https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta118)

> [!NOTE]
> APKهای CI ممکن است با کلید آزمایشی امضا شده باشند. برای upgrade-in-place پایدار، تنظیمات [Release signing](docs/release-signing.md) را ببینید.

## ✨ قابلیت‌های اصلی

### Android

- واردکردن subscription و share link؛
- پشتیبانی از فرمت‌های Mihomo/Clash، Xray و Sing-box در مسیرهای مرتبط؛
- QR import/export؛
- اسکن IP و ابزارهای بررسی اتصال؛
- رابط فارسی و انگلیسی، RTL، تم روشن/تیره؛
- Quick Settings tile، widget و notification اتصال؛
- بررسی update با کنترل version، checksum، package، ABI و signing certificate؛
- مسیرهای Orbot و dnstt با وضعیت‌های مستقل در بخش‌های پیاده‌سازی‌شده.

### Cat Panel

- پنل تک‌فایلی Cloudflare Worker؛
- مدیریت کاربران، UUIDها و لینک‌های subscription؛
- خروجی raw، Base64، Mihomo، Sing-box و Xray؛
- KV اختیاری برای تنظیمات پایدار؛
- backup با masking پیش‌فرض Secretها؛
- health، diagnostics و crash report محافظت‌شده؛
- UI فارسی/انگلیسی و phone-first؛
- Worker قابل‌استقرار به‌شکل **normal minified ESM**، نه runtime سنگین RC4/control-flow-obfuscated.

### اصول ایمنی

- `DATA_PLANE_ENABLED` در نصب تازه به‌صورت پیش‌فرض خاموش است؛
- `/health` و control-plane باقی می‌مانند؛
- tunnel/WebSocket تا opt-in صریح مالک، `403` می‌دهد؛
- موفقیت upload به معنی موفقیت runtime نیست؛
- Wizard فقط بعد از سه پاسخ سالم و متوالی `/health` نتیجه موفق می‌دهد؛
- خطاهای network، non-2xx، JSON خراب و runtime crash شمارنده سلامت را reset می‌کنند؛
- هیچ اصلاحی نمی‌تواند enforcement حساب Cloudflare یا «ban نشدن» را تضمین کند.

## ☁️ پنل Cloudflare

### وضعیت مسیرهای استقرار

| مسیر | وضعیت |
|---|---|
| نصب تازه با Wizard و نام Worker آزاد | پشتیبانی‌شده؛ نیازمند سه health موفق |
| update/reinstall Worker موجود با Wizard | **مسدود** تا حفظ همه bindingها اثبات شود |
| release asset | `catclient.worker.js` عادی و minified، حدود 540 KB |
| Wrangler | ورودی `dist-panel/catpanel.min.js` |
| کپی دستی | ممکن، اما اندازه و کامل‌بودن فایل باید کنترل شود |

### چرا update موجود مسدود است؟

Cloudflare deployment اعلامی است. ارسال فهرست ناقص bindings ممکن است اتصال `secret_text`، D1، R2، IOT_DB یا binding سفارشی را از نسخه جدید Worker جدا کند. Wizard فعلی فقط این حالت را fresh می‌داند:

```text
GET Worker settings → HTTP 404
```

و در این حالت‌ها پیش از KV، upload و route mutation متوقف می‌شود:

```text
HTTP 200، حتی bindings=[]  → Worker موجود
HTTP 401/403/5xx           → نامشخص
Network error              → نامشخص
Malformed response         → نامشخص
```

> [!CAUTION]
> Worker خراب را حذف نکنید، Worker تصادفی جایگزین نسازید و KV/D1/Secrets را پاک نکنید. ابتدا Deployments و Live Logs را بررسی کنید.

راهنمای جزئی‌تر: [Panel deployment safety](docs/panel-deploy-safety.md)

## 🧯 خطای Cloudflare 1101

Cloudflare، خطای `1101` را runtime exception معرفی می‌کند. Ray ID به‌تنهایی stack trace نیست.

### تشخیص اولیه

```text
/health
/api/version
/api/selfcheck?throw=1   (فقط مالک)
```

اگر همه مسیرها صفحه 1101 می‌دهند، احتمال خطای load/global startup یا runtime kill مطرح است. اگر Worker پاسخ JSON می‌دهد، خطا احتمالاً در یک route مشخص رخ داده است.

### اطلاعات لازم برای تشخیص

از **Workers & Pages → Worker → Logs / Live Logs** درخواست `/health` را بازتولید و این موارد را بردارید:

- اولین exception؛
- stack trace؛
- deployment/version ID؛
- request path و زمان.

این موارد را ارسال نکنید:

```text
Authorization · Cookie · API Token · UUID · Password · Secret · subscription URL
```

راهنمای کامل فارسی: [docs/error-1101-fa.md](docs/error-1101-fa.md)

### تغییر beta118

نسخه قبلی artifact حدود 1.34 MB با RC4 string-array و control-flow flattening منتشر می‌کرد. این artifact در mockهای Node موفق بود، اما اجرای Cloudflare را ثابت نمی‌کرد و در گزارش واقعی از شروع `1101` داد. beta118 تمام مسیرهای deploy/update را به artifact عادی minified ESM منتقل کرده است.

این تغییر یک علت فنی محتمل را حذف می‌کند؛ **بدون Live Log نمی‌توان گفت علت تمام 1101ها همین بوده است.**

## 🧪 وضعیت تأیید

برای beta118 موارد زیر در CI موفق‌اند:

- panel و Wizard tests؛
- UI end-to-end؛
- Kotlin compile؛
- JVM tests؛
- debug/release APK؛
- R8، packaging، `apksigner`، `zipalign` و SHA-256؛
- syntax/import/health روی artifact minified؛
- تست fail-closed برای 200، 401، 403، 500، network failure و malformed settings.

موارد تأییدنشده:

- deploy واقعی beta118 روی حساب Cloudflare کاربر؛
- تست گوشی فیزیکی؛
- multi-SIM، roaming، captive portal و جابه‌جایی واقعی Wi‑Fi/Cellular؛
- تضمین عملکرد در همه ISPها یا کشورها.

[برنامه تست گوشی beta113](docs/beta113-device-test-plan.md) · [Smart Connection roadmap](docs/SMART-CONNECTION-ROADMAP.md)

## 🧱 معماری

```text
Cat Client Android
├── Mihomo runtime
├── Subscription/config parsers
├── Network probes and scanners
├── Panel deployment management
└── Update verification

Cat Panel Worker
├── Control plane: panel, auth, health, diagnostics
├── Subscription builders
├── Optional KV persistence
└── Data plane: explicit owner opt-in

Cat Wizard
├── Token/account verification
├── Proven-fresh Worker check
├── Artifact upload
├── Route activation
└── 3× runtime health gate
```

## 🛠️ ساخت و تست

### نیازمندی‌ها

- JDK 21؛
- Android SDK 36 و Build Tools 35؛
- NDK 29 و CMake 3.22.1؛
- Go 1.24+؛
- Node.js 22.

### Android

```bash
./scripts/build-flclash-core.sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
```

### Panel و Wizard

```bash
npm install
npm test
node scripts/panels/wizard.test.mjs
node scripts/panels/abuse-safe-mode.test.mjs
```

### ساخت artifact قابل‌استقرار

```bash
node scripts/panels/minify.mjs \
  app/src/main/assets/panels/catclient.worker.js \
  dist-panel/catpanel.min.js \
  --check
```

هیچ نتیجه تستی را بدون اجرای command مربوط ثبت نکنید. تست روی emulator جای تست گوشی و Cloudflare واقعی را نمی‌گیرد.

## 🖥️ نسخه Windows

نسخه دسکتاپ مستقل در [`windows/`](windows/README.md) قرار دارد. وضعیت و روش اجرای آن را در README همان پوشه ببینید.

## 🔐 امنیت و گزارش مشکل

هنگام ثبت Issue:

- نسخه اپ، نسخه پنل و مسیر خراب را بنویسید؛
- log را redact کنید؛
- credential را در Issue، screenshot یا CI log قرار ندهید؛
- برای عملیات destructive ابتدا backup و تأیید مجدد در نظر بگیرید؛
- درباره نتیجه‌ای که روی گوشی یا Cloudflare واقعی تست نشده، صریح باشید.

[ثبت Issue](https://github.com/mazodimobinhost-creator/cat-client/issues/new) · [Release notes beta118](docs/release-notes-v1.10.0-beta118-draft.md)

## 🤝 مشارکت

1. Issue مشخص با evidence ایجاد کنید؛
2. تغییر را کوچک و قابل بازبینی نگه دارید؛
3. برای failure case تست اضافه کنید؛
4. UUID، KV، D1، Secret و history موجود را reset نکنید؛
5. نتیجه CI و موارد تأییدنشده را در PR بنویسید.

کد پروژه تحت [GPL-3.0](LICENSE) منتشر شده است. برای استفاده از کد پروژه‌های GPL دیگر، شرایط مجوز و attribution را رعایت کنید.

## سپاس و منابع طراحی

ساختار README با الهام از مجموعه [awesome-readme](https://github.com/matiassingers/awesome-readme) بازطراحی شده است. این attribution مربوط به الگوی ارائه است؛ متن یا کد پروژه‌های فهرست‌شده کپی نشده است.

---

## English summary

Cat Client combines an Android Mihomo client, a single-file Cloudflare Worker panel, and a fresh-install Wizard. The project prioritizes evidence-based probes, credential redaction, binding preservation, and fail-closed deployment behavior.

**Current status:** `1.10.0-beta118` is a pre-release test candidate. Automated Android, panel, Wizard, and UI tests pass, but physical-device and real-account Cloudflare validation are still pending. The Wizard refuses to overwrite an existing Worker until all Cloudflare binding types can be preserved safely.

- [Download beta118](https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta118)
- [Cloudflare 1101 troubleshooting](docs/error-1101-fa.md)
- [Build instructions](#-ساخت-و-تست)
- [License: GPL-3.0](LICENSE)
