# 🔍 بریف بازبینی کامل Cat Client — برای Claude و Grok

> **از طرف صاحب پروژه:** لطفاً **همهٔ بخش‌های پروژه** را بررسی کنید، نه فقط آخرین تغییر: اپ اندروید، همهٔ موتورهای اتصال، Cat Warp،
> Fox VPN، اسکنر و جعبه‌ابزار، تنظیمات، ظاهر/تم، همهٔ پنل‌ها (Cloudflare، Vercel، Node روی هر هاست، Railway)، صفحه‌های HTML، ربات نصب و CI.
> هدف: هر چیزی که خراب، ناقص، تکراری، بی‌اثر یا گیج‌کننده است پیدا شود — با دلیل و محل دقیق.

- **مخزن (عمومی):** https://github.com/mazodimobinhost-creator/cat-client
- **شاخهٔ کار:** `arena/c5b36768-cat-client` (همهٔ کار فقط روی همین شاخه). **PR شمارهٔ ۹ ادغام نمی‌شود.**
- **آخرین نسخه:** `v1.10.0-beta241` (versionCode 308) — پنل `6.81.0`. انتشارها: صفحهٔ Releases مخزن (pre-release).
- **قرارداد کار و قوانین ثابت:** [`docs/COLLABORATION.md`](COLLABORATION.md) — اول این را بخوانید؛ بخش ۲ (قوانین) و بخش ۸ (چک‌لیست) الزامی است.

---

## ۱) ترتیب خواندن (۱۵ دقیقه)

1. `docs/COLLABORATION.md` — نقش‌ها، قوانین ثابت، جریان کار، چک‌لیست.
2. `.agents/STATUS.md` و `.agents/HANDOFF.md` — وضعیت لحظه‌ای و آخرین دست‌به‌دست.
3. `docs/CODEMAP.md` — «چه چیزی کجاست» (خودکار ساخته می‌شود؛ CI چک می‌کند).
4. `CHANGELOG.md` — از `beta146` به بعد؛ هیچ قابلیتی از `beta146` تا `beta185` نباید از دست رفته باشد.
5. `.agents/DECISIONS.md`، `.agents/METHODS.md`، `.agents/OPEN_ISSUES.md`.

## ۲) ابزار بازبینی

```bash
git clone https://github.com/mazodimobinhost-creator/cat-client && cd cat-client
git checkout arena/c5b36768-cat-client
scripts/review-worktree.sh origin/arena/c5b36768-cat-client --base 8f97a76   # فقط تغییرات beta241
scripts/review-worktree.sh origin/arena/c5b36768-cat-client --base c19589e   # همهٔ کار از beta172 به بعد
npm install --no-save javascript-obfuscator@5 terser acorn jsdom ws@8 @vercel/blob@2 miniflare@4 && npm test
python3 scripts/codemap.py --check
python3 scripts/affected-tests.py
```
تست‌های JVM اپ با Gradle (`./gradlew testDebugUnitTest`) اجرا می‌شوند؛ CI همه را روی هر push اجرا می‌کند.

---

## ۳) وضعیت فعلی — چه چیزی با چه مدرکی کار می‌کند

| بخش | مدرک موجود | تست واقعی روی گوشی/خط ایران |
|---|---|---|
| ساخت اپ اندروید (همهٔ ABIها) و انتشار | CI سبز در هر نسخه؛ beta241 در `88748b0`: هر ۸ اجرای push و PR سبز، pre-release با ۲۰ فایل | — |
| تست‌های JVM اپ | ۶۹۷ تست در ۱۲۲ فایل؛ در CI سبز | — |
| پنل Cloudflare (Worker) | ۸۳ فایل تست `scripts/panels/*.test.mjs`؛ ترافیک واقعی WebSocket در Miniflare (`worker-realtraffic` ۲۲/۲۲)؛ ورود و صفحه‌ها در UI e2e | پنل‌های CF کاربر باید به 6.81.0 به‌روز شوند و 🔌 (DATA_PLANE) زده شود — **تأیید نشده** |
| پنل Vercel | تست‌های `vercel-*.test.mjs` | پنل زنده هنوز روی **6.77.3** است (`cat-panel-v7k2m`) — به‌روزرسانی با ملاد |
| پنل Node (KataBump، Bot-Hosting، Lucity، Shiprr، SmarterASP، Zerops، Seenode، Hostim) | `node-host.test.mjs` و تست‌های هاست | SmarterASP آزمایشی بالا آمده (6.79.2)؛ Zerops/Seenode/Hostim **تست نشده**؛ Reality روی KataBump فقط با Xray ساختگی |
| پنل Railway (Xray واقعی) | گردش‌کار جدای «Railway panel tests» | تست زندهٔ اخیر ندارد |
| Cat Warp (Aether: WireGuard، MASQUE-H2، WARP-in-WARP، WoM، MiM، Psiphon/Tor) | تست‌های JVM؛ رفع beta241 با `Beta241AetherNetworkPlanTest` | رفع beta241 (کار روی خط دیگران) **روی گوشی تست نشده** |
| Cat Fox VPN (ورود موزیلا، WebChannel، رفع 406) | `Beta237FoxPassTest`، `Beta238FoxWebChannelTest`، تست‌های beta239 | **تست نشده** روی گوشی |
| یابندهٔ پروکسی تلگرام (MTProto) | تست‌های beta239 | **تست نشده** |
| تنظیمات beta240 (Sniffing، FakeDNS، پروکسی HTTP سیستم، آدرس پینگ) | `Beta240CoreOptionsTest` | **تست نشده** روی همراه اول/ایرانسل |
| اسکنر IP / CDN / اینترنت ملی، DNS رمزدار | تست‌های JVM و `scanner-sources.test.mjs` | اسکن DoH/DoT روی دو اپراتور **تست نشده** |
| SSTP (VPN Gate)، تونل‌های DNS، Hysteria v1/hy2، WireGuard/Amnezia | تست‌های JVM و تبدیل لینک | گزارش تست زندهٔ جدید ندارد |

**مشکلات باز شناخته‌شده:** باگ ۴ زنجیره (در حالت `all` نباید بی‌صدا به direct برگردد)؛ بهینه‌سازی از دادهٔ واقعی (بسته به به‌روزرسانی پنل زنده)؛
منبع IP تمیز CF که در ایران تست شده باشد؛ ثبت‌نام WARP روی دستگاه تازه در ایران مسدود است (اپ کلید گرم دارد — بررسی کنید کافی است).

---

## ۴) پنل‌ها با چه روشی وصل می‌شوند (برای بررسی درستی)

| پنل | ورودی (کانفیگ‌های sub) | خروجی سمت سرور |
|---|---|---|
| Cloudflare Worker | VLESS و Trojan **فقط روی WebSocket**: `type=ws` با TLS (sni / fp / alpn `http/1.1` / ech) روی پورت‌های https، یا `security=none` روی پورت‌های http؛ آدرس: IP تمیز / workerOnly / لبهٔ Vercel | `connect()` مستقیم ⇐ در صورت شکست ProxyIP یا SOCKS5 ⇐ یا زنجیره به لینک کانفیگ (TLS + ws/httpupgrade/tcp). سرور xhttp/grpc/httpupgrade ندارد. |
| Node (همان باندل) | KataBump با IP:پورت: WS بدون TLS + Reality (`tcp`/`reality`/`xtls-rprx-vision`)؛ هاست‌های HTTPS: WS + TLS روی 443؛ پشت CF: کانفیگ‌های CF | اتصال مستقیم از هاست |
| Vercel | فقط WebSocket (httpupgrade رد می‌شود)؛ فقط SNI `*.vercel.app` جواب 101 می‌دهد | اختیاری: Worker جلویی CF که به Vercel رله می‌کند |
| Railway | Xray واقعی؛ ورودی VLESS روی 127.0.0.1، منتشرشده به‌صورت `tls` + `ws` | Xray |
| لینک `aether://` پنل | بدون endpoint — اپ خودش روی خط کاربر جست‌وجو می‌کند | — |

---

## ۵) آخرین رفع (beta241) — لطفاً دقیق بررسی شود

**مشکل:** «کانفیگ‌های Aether که اپ می‌سازد فقط روی اینترنت خودم کار می‌کند.» کانفیگ ویزارد، endpoint و خانوادهٔ IP (IPv6 روی همراه اول) و حالت (WireGuard/UDP)
همان خطی را که روی آن ساخته شده در خود دارد. روی خط دیگر: جست‌وجوی فقط-IPv6 روی شبکهٔ بدون IPv6، WireGuard روی خطی که UDP را می‌بندد، هر مرحله ۱۸۰ ثانیه، و endpoint لینک قبل از امتحان در حافظهٔ شبکهٔ جدید ذخیره می‌شد.

**رفع:** `AetherNetworkPlanner.kt` (منطق خالص) + `CatClientVpnServiceCatWarp.kt` (`runAetherStartup`) + `AetherSession.kt` + `AetherPreferenceStore.kt`
(`underlyingNetworkHasIpv6`، `rememberWorkingModeForNetwork`، کلیدهای `netpool2_`/`netmem2_`).

سؤال‌های مشخص برای بازبین:
- آیا تشخیص IPv6 (`LinkProperties`: آدرس سراسری + مسیر پیش‌فرض IPv6) روی اندروید ۸ تا ۱۵ درست است؟ اگر شبکهٔ فعال VPN خودمان باشد چه؟
- آیا «حالت یادگرفته» (مثلاً WireGuard ⇐ MASQUE) ممکن است جایی خروجی ایران/خارج یا زنجیرهٔ Psiphon/Tor را عوض کند؟
- آیا حذف مرحلهٔ تکراری «کشف خودکار» در `AetherSession` حالتی را جا انداخته است؟
- آیا جای دیگری هنوز endpoint تأییدنشده را «سالم» حساب می‌کند (مثلاً `?: hits.firstOrNull()` در ویزارد `MainActivityCatWarp.kt` — نتیجهٔ اسکن هسته است، نه حدس؛ نظرتان؟)

---

## ۶) چک‌لیست «همهٔ بخش‌ها»

- **اتصال:** mihomo، Xray، Cat Warp (همهٔ حالت‌ها و زنجیره‌ها)، Psiphon، Tor، SSTP، تونل‌های DNS، Hysteria، WireGuard/Amnezia، Fox VPN — هیچ مسیری بی‌صدا به اتصال مستقیم برنگردد؛ سوکت‌ها `protect` شوند؛ MTU زیر 1280 بدون IPv6.
- **اشتراک‌ها:** هر کانفیگ کامل و قابل اتصال؛ تکراری/ناقص ممنوع؛ پورت اسکن‌شده عوض نشود؛ ProxyIPهای `103.237.102.191:11111` و `184.75.221.82:3118` با همان پورت.
- **اسکنر و ابزارها:** نتیجه فقط از تست واقعی؛ افزودن خودکار نتایج؛ قابل فهم برای کاربر عادی («اینو بزنی این میشه…»).
- **تنظیمات و ظاهر:** هر گزینه واقعاً اثر دارد؛ دکمهٔ تکراری/بی‌اثر نباشد؛ طراحی فعلی حفظ شود؛ تم‌ها؛ تب «🛡️ اتصال اضطراری و ضدسانسور» جدا.
- **پنل‌ها:** ورود `admin/admin`، `OPEN_SUB=false`، هیچ پاک‌سازی بدون پشتیبان؛ هر هاست پروفایل خودش؛ DATA_PLANE در همهٔ مسیرهای نصب؛ متن ≥۱۲px و دکمهٔ آیکنی ≥44px با aria-label.
- **امنیت:** بدون `allowInsecure` / `skip-cert-verify: true`؛ بدون ECH `tcp://`؛ هیچ کلید یا رمزی در مخزن.
- **نام‌ها و مجوزها:** روی صفحه فقط برند Cat؛ کد GPL/AGPL/بدون‌مجوز کپی نشده باشد (WhiteAestherMobile = AGPL، فقط ایده/پیوند)؛ MIT با ذکر نام.

## ۷) قالب گزارش (لطفاً دقیقاً همین)

```
[شدت: بحرانی | مهم | جزئی]  عنوان کوتاه
محل:        path/to/File.kt:123 (یا تابع)
مدرک:       «اجرا کردم و دیدم …» یا «از خواندن کد: …» — این دو را جدا بنویسید
بازتولید:   1) … 2) …
پیشنهاد:    تغییر مشخص (بدون شکستن قوانین بخش ۲ COLLABORATION)
```
گزارش را در `.agents/HANDOFF.md` بنویسید (یا به صاحب پروژه بدهید تا عامل Arena اعمال کند). حدس را به‌عنوان واقعیت گزارش نکنید؛ «سالم است» هم مدرک می‌خواهد.

**تقسیم پیشنهادی (هر دو همه‌چیز را می‌بینند):** Claude از سمت اپ اندروید شروع کند (`CatClientVpnService*.kt`، Aether، Fox، اسکنر، تنظیمات)،
Grok از سمت پنل‌ها و هاست‌ها (`catclient.worker.js`، `node-panel/`، `railway-panel/`، Vercel، HTML، CI)؛ بعد جابه‌جا کنید.
