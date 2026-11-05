# 🤝 همکاری سه‌نفره روی Cat Client — عامل Arena + Grok + ملاد

> هدف: همهٔ پنل‌ها (Cloudflare، Vercel، Node روی هر هاست)، همهٔ بخش‌های پنل، اپ اندروید، صفحه‌های HTML و CI
> **درست، خودکار و بی‌نقص** کار کنند — و درست کردن یک جا، جای دیگری را خراب نکند.
> این فایل «قرارداد کار» است: چه کسی چه می‌کند، کار چطور دست‌به‌دست می‌شود، «تمام شد» یعنی چه، و چه چیزهایی هرگز عوض نمی‌شوند.

---

## ۱) اعضا و نقش‌ها

| عضو | نقش اصلی | خروجی‌اش |
|---|---|---|
| **عامل Arena** (همین عامل، روی شاخهٔ `arena/c5b36768-cat-client`) | نوشتن و اصلاح کد، تست‌ها، CI، انتشار نسخه (`v1.10.0-betaNNN`) | کامیت + انتشار سبز + گزارش فارسی |
| **Grok** | بازبین دوم و پژوهشگر: خواندن diff هر نسخه با `REVIEW-PROMPT.md`، پیدا کردن باگ واقعی، تحقیق روش‌ها/هاست‌های تازه و نوشتن «مشخصات» (spec) برای کار بعدی | گزارش بازبینی (فایل:خط + چرا خراب است + کوچک‌ترین اصلاح) و spec در `.agents/HANDOFF.md` |
| **ملاد** | تست واقعی و تأیید نهایی: گوشی واقعی روی همراه اول / ایرانسل، به‌روزرسانی پنل زنده، ساختن حساب هاست‌ها، تصمیم‌های محصول | نتیجهٔ تست با لاگ (کلیدهای لاگ پایین)، «تأیید» یا «رد» هر کار |

> اگر نقش ملاد چیز دیگری است، فقط همین جدول را عوض کنید؛ بقیهٔ قرارداد ثابت می‌ماند.

**قاعدهٔ دست‌به‌دست:** هر کس کارش تمام شد یک بند در `.agents/HANDOFF.md` می‌نویسد (قالبش آن‌جاست). کسی که بعد می‌آید اول همان فایل و `.agents/STATUS.md` را می‌خواند.

---

## ۲) قوانین ثابت (هیچ‌کس عوض نمی‌کند، مگر صاحب پروژه صریحاً بگوید)

- **شاخه:** همهٔ کار روی `arena/c5b36768-cat-client`. PR شمارهٔ ۹ **ادغام نمی‌شود**. reset/clean ممنوع.
- **پورت‌ها:** هر `IP:پورت` که اسکنر پیدا کرد دقیقاً با همان پورت در کانفیگ می‌رود؛ نتیجهٔ 443 هرگز 80 یا پورت حدسی نمی‌شود. آی‌پی، ProxyIP یا SOCKS خودکار روی 80/443/1080/پورت ورودی گذاشته نمی‌شود. ProxyIPهای `103.237.102.191:11111` و `184.75.221.82:3118` با همین پورت می‌مانند.
- **سلامت فقط با تست واقعی:** وضعیت ProxyIP، هاست، WebSocket یا «وصل شد» فقط از تست واقعی گزارش می‌شود؛ عدد ساختگی و نمونهٔ ساختگی ممنوع.
- **اشتراک:** هر کانفیگی که به sub اضافه می‌شود باید کار کند؛ اگر ۱۰ تا اضافه شد، هر ۱۰ تا. فرمت ناقص/تکراری ممنوع.
- **داده:** UUID، KV، D1، Secrets، bindingها و تاریخچه بدون پیش‌نمایش/پشتیبان پاک یا ریست نمی‌شوند. `OPEN_SUB=false` و ورود `admin/admin` می‌مانند.
- **نام‌ها:** روی صفحه فقط برند Cat؛ نام اپ‌های دیگر (MLM، PattNG، FENIX، foxyvpn، Firefox VPN، Zeus، …) نمایش داده نمی‌شود.
- **پنهان‌سازی اختیاری:** پیش‌فرض خاموش، با هشدار، تأیید و بررسی. استتار فریبنده یا دور زدن اجرای قانون ممنوع.
- **نصب‌کننده‌ها:** هر عملیات نصب قبل از اجرا از کاربر تأیید می‌گیرد.
- **مجوزها:** از پروژه‌های دیگر فقط «ایده» برداشته می‌شود؛ کد GPL/AGPL/بدون‌مجوز کپی نمی‌شود. کد MIT (مثل FoxyVPN) فقط با ذکر نام در توضیح کد.
- **Fox VPN:** هرگز بی‌صدا به اتصال مستقیم برنمی‌گردد.
- **ظاهر اپ:** طراحی فعلی می‌ماند («اپ قشنک شده»)؛ فقط شلوغی و دکمهٔ بی‌اثر حذف می‌شود.
- **هر هاست پروفایل خودش را دارد:** قابلیتی که یک هاست ندارد فقط برای همان هاست برداشته می‌شود.

---

## ۳) نقشهٔ پروژه — «چه چیزی کجاست»

نقشهٔ کامل و خودکار: [`docs/CODEMAP.md`](CODEMAP.md) (با `python3 scripts/codemap.py` ساخته می‌شود و CI چک می‌کند).

| بخش | فایل‌های اصلی | تست‌های نگهبان |
|---|---|---|
| اتصال و موتورها (mihomo، Xray، WARP/Aether، Psiphon، SSTP، DNS-tunnel) | `CatClientVpnService*.kt`، `MihomoRuntime.kt`، `XrayEngine.kt`، `AetherOperations.kt` | `Beta2xx*Test.kt`، `VpnServiceSources` |
| Cat Fox VPN | `FirefoxVpnEngine.kt`، `FenixHttp2.kt`، `FenixPassPolicy.kt`، `FoxPortPolicy.kt`، `FoxWebChannel.kt`، `MainActivityFox.kt` | `Beta237FoxPassTest`، `Beta238FoxWebChannelTest` |
| اسکنر و جعبه‌ابزار | `IpScanner.kt`، `WhiteDnsToolkit.kt`، `WhiteDnsSmartScanner.kt`، `AppToolsController.kt` | `scanner-sources.test.mjs`، تست‌های JVM اسکنر |
| پنل Cloudflare (Worker) | `app/src/main/assets/panels/catclient.worker.js` (+ `catpanel.min.js`، `dist-panel/`) | `npm test` (همهٔ `scripts/panels/*.test.mjs`) |
| پنل Vercel | `app/src/main/assets/vercel/panel-adapter.mjs` | `vercel-*.test.mjs` |
| پنل Node روی هاست‌ها | `node-panel/index.js` (= `app/src/main/assets/node-panel/index.js` بایت‌به‌بایت)، `NodeHost.kt`، `NodeHostController.kt`، `GitHostProfiles.kt`، `HostRegions.kt`، `ApiHostDeploy.kt` | `node-host.test.mjs`، `NodeHostTest`، `Beta225FreeHostsTest` |
| صفحه‌های HTML (پورتال، fixer، wizard) | `scripts/pages/`، `assets/panels/catclient.wizard.js` | `portal-target.test.mjs`، `cat-fixer-html.test.mjs`، `wizard.test.mjs` |
| CI و انتشار | `.github/workflows/android.yml` | خود CI (Android Build، panel tests، portal، UI e2e) |

**هاست‌های پنل Node و پروفایلشان:**

| هاست | نصب | آدرس | نکتهٔ خاص |
|---|---|---|---|
| KataBump | کلید API (خودکار) | `IP:پورت` یا `*.kdns.fr` پشت CF | تمدید هر ۴ روز؛ قوانینش پروکسی را منع کرده |
| Bot-Hosting | کلید API (خودکار) | `*.apps.bot-hosting.cloud` | تمدید هر ۴ روز |
| Railway | توکن (خودکار) | `*.up.railway.app` | پولی بعد از اعتبار؛ ۴ منطقه |
| Lucity / Shiprr | مخزن GitHub (خودکار) + انتخاب در سایت | `*.lucity.app` / `*.shiprr.dev` | بدون دیسک دائمی |
| SmarterASP | zip (آپلود دستی) | `*-site1.ktempurl.com` | IIS ویندوز / Linux |
| **Zerops** (beta238) | مخزن + `zerops.yml` | `…zerops.app` (Zerops subdomain) | ۱۵$ اعتبار؛ ۱۰۰ گیگ ترافیک ماهانه |
| **Seenode** (beta238) | مخزن | `*.seenode.app` | کادر Port = 8080؛ WebSocket رسمی |
| **Hostim** (beta238) | مخزن + `Dockerfile` | `*.hostim.dev` | پروژهٔ آزمایشی ۵ روزه |
| Slipway | — | — | اضافه نشد: پنل خودمیزبان روی سرور خودتان است، نه هاست رایگان |

---

## ۴) جریان کار (هر تغییر)

1. **انتخاب کار** از «تختهٔ کار» پایین (یا spec تازهٔ Grok در `.agents/HANDOFF.md`).
2. **اصلاح** — کوچک و متمرکز. هر تابع/فایل Kotlin تازه ⇐ `python3 scripts/codemap.py` و بعد `python3 scripts/codemap.py --check` (بدون pipe).
3. **تست محلی:**
   - `python3 scripts/affected-tests.py` — تست‌هایی که از فایل‌های تغییرکرده نگهبانی می‌کنند.
   - تغییر در Worker ⇐ ساخت دوبارهٔ آرتیفکت‌ها (minify → `catpanel.min.js` → obfuscate/stealth → `cat-panel.html`) و `npm test` کامل.
   - تغییر در `node-panel/index.js` ⇐ کپی بایت‌به‌بایت در `app/src/main/assets/node-panel/`.
4. **بازبینی در worktree جدا** (ایدهٔ herdr-redline):
   ```bash
   scripts/review-worktree.sh <commit|origin/branch|شمارهٔ-PR> --base <ref>
   ```
   در `/tmp/cat-review-…` چک‌ها اجرا می‌شوند و `REVIEW.md` + `REVIEW-PROMPT.md` ساخته می‌شود. Grok همان prompt را با diff می‌خواند و فقط باگ واقعی گزارش می‌دهد. پاک کردن: `scripts/review-worktree.sh --clean`.
5. **کامیت و push** روی همین شاخه ⇐ CI (۱۲ تا ۱۵ دقیقه) ⇐ همهٔ اجراهای push و pull_request باید سبز شوند ⇐ انتشار خودکار نسخه.
6. **تست واقعی (ملاد)** روی گوشی و خط واقعی با کلیدهای لاگ؛ نتیجه در `.agents/HANDOFF.md`.
7. **به‌روزرسانی** `.agents/STATUS.md`، `.agents/LOG.md` و `CHANGELOG.md`.

---

## ۵) تختهٔ کار (هر بخش، صاحب، معیار پذیرش)

| # | کار | صاحب | معیار «تمام شد» |
|---|---|---|---|
| 1 | به‌روزرسانی پنل زندهٔ Vercel از 6.77.3 به 6.80.0 (از داخل اپ) | ملاد | `/api/version` = 6.80.0؛ ورود `admin/admin` و همهٔ sub ها سالم |
| 2 | بهینه‌سازی از دادهٔ واقعی (بعد از #1) | Arena + Grok | گزارش اپ از پنل جدید خوانده و پیشنهادها با عدد واقعی اعمال شود |
| 3 | باگ ۴ زنجیره: در حالت `all` لینک بی‌صدا به direct برنگردد | Arena | تست JVM + تست گوشی: قطع لینک ⇐ خطای روشن، نه اتصال مستقیم |
| 4 | تست گوشی اصلاحات Fox VPN نسخهٔ beta237 | ملاد | ۳۰ دقیقه وصل بدون قطعی؛ لاگ `fenix.pass.renew`، `fenix.tls.unsplit`، `fenix.network.reset` |
| 5 | تست گوشی ورود رسمی موزیلا (beta238): ایمیل+رمز، کد ۶ رقمی، 2FA | ملاد | حساب بدون خطای 406 / «unconfirmed» اضافه شود؛ لاگ `fenix.login.webchannel` = `ok` |
| 6 | تست واقعی هاست‌های تازه: Zerops، Seenode، Hostim | ملاد (حساب) + Arena (اصلاح) | پنل بالا بیاید، تست WebSocket 101 سبز، کانفیگ‌ها روی همراه اول و ایرانسل وصل شوند |
| 7 | Reality روی KataBump با Xray واقعی (تا الان فقط با Xray ساختگی تست شده) | ملاد + Arena | اتصال واقعی از گوشی |
| 8 | اسکن DNS رمزدار (beta238) روی دو اپراتور | ملاد | فهرست DoH/DoT سالم هر اپراتور در `METHODS.md` |
| 9 | بازبینی هر نسخه با `review-worktree.sh` | Grok | گزارش بازبینی در `HANDOFF.md` تا قبل از نسخهٔ بعد |
| 10 | صفحه‌های HTML (پورتال / fixer / wizard): بررسی موبایل، متن ≥۱۲px، دکمهٔ آیکنی ≥44px با aria-label | Grok (بازبینی) + Arena | `panel-a11y`، `ui-preview-audit` سبز + تأیید چشمی ملاد |

---

## ۶) گزارش باگ (قالب)

```
دستگاه / اندروید:            مثلاً Samsung A52 / Android 13
اپراتور:                      همراه اول | ایرانسل | ثابت (مخابرات/شاتل)
نسخهٔ اپ و پنل:               v1.10.0-beta238 / 6.80.0
نوع کانفیگ / هاست:            VLESS-WS روی Seenode | Fox VPN | WARP | …
قدم‌ها:                        1) … 2) … 3) …
انتظار / آن‌چه شد:            …
لاگ:                          دکمهٔ «کپی گزارش عیب‌یابی» در اپ (کلیدهای fenix.* و …)
```

---

## ۷) خودکارسازی — چه چیزی الان خودکار است و هدف بعدی

**خودکار است:** ساخت و انتشار نسخه با هر push (CI)؛ به‌روزرسانی خود پنل‌ها از اپ؛ نصب کامل روی Bot-Hosting / Railway / KataBump با کلید؛ ساخت مخزن GitHub برای هاست‌های Git (با `zerops.yml` / `Dockerfile` هر هاست)؛ تشخیص آی‌پی خود هاست و گذاشتن آن در کانفیگ‌ها؛ تست واقعی پنل/sub/WebSocket بعد از نصب؛ تمدید پاس Fox VPN در پس‌زمینه؛ یادآوری تمدید هاست‌های رایگان.

**هدف بعدی (برای همکاری):** ساخت خودکار اپ در هاست‌های Git از طریق API خود هاست (اگر API عمومی داشتند، مثل Zerops CLI/API) تا دکمهٔ «ساخت برنامه» هم دستی نباشد؛ گزارش خودکار سلامت هر هاست به اپ؛ اجرای `review-worktree.sh` در CI برای هر PR.

---

## ۸) چک‌لیست بازبینی

<!-- review-checklist:start -->
- [ ] هیچ پورت اسکن‌شده / ProxyIP / SOCKS عوض نشده؛ هیچ آی‌پی خودکار روی 80/443/1080 نرفته.
- [ ] هر کانفیگ تازه در sub کامل و قابل اتصال است (VLESS/Trojan، IPv4/IPv6، SNI، Host، ALPN، fingerprint معتبر؛ بدون `allowInsecure`).
- [ ] هیچ «موفق» / عدد سلامت بدون تست واقعی نمایش داده نمی‌شود.
- [ ] UUID / KV / Secrets / تاریخچه بدون پشتیبان پاک نمی‌شود.
- [ ] نام اپ‌های دیگر روی صفحه نیامده (فقط Cat).
- [ ] قابلیت‌های هر هاست فقط برای همان هاست حذف/اضافه شده؛ هاست‌های دیگر دست نخورده.
- [ ] اپ: بدون کرش در onCreate/دیالوگ‌ها (`isFinishing`/`isDestroyed`)، بدون کار شبکه روی UI thread، سوکت‌های تست `protect` شده.
- [ ] Worker: بدون `${` در قالب سمت کلاینت، بدون «trojan» پیوسته، آرتیفکت‌ها دوباره ساخته شده‌اند.
- [ ] `node-panel/index.js` و کپی assets یکسان‌اند؛ codemap به‌روز است.
- [ ] متن‌ها برای کاربر عادی ساده‌اند («اینو بزنی این میشه…»)؛ دکمهٔ تکراری یا بی‌اثر اضافه نشده.
<!-- review-checklist:end -->

---

## ۹) «تمام شد» یعنی

1. کد + تست نگهبان در مخزن، codemap به‌روز.
2. همهٔ اجراهای CI سبز و نسخه منتشر شده.
3. بازبینی Grok بدون باگ باز (یا باگ‌ها اصلاح شده).
4. تست واقعی ملاد روی حداقل یک اپراتور (برای کارهای شبکه‌ای: هر دو).
5. `STATUS.md`، `LOG.md`، `CHANGELOG.md` و گزارش فارسی.
