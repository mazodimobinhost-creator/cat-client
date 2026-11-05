# 🔍 بریف بازبینی کامل Cat Client — برای Claude و Grok

> **از طرف صاحب پروژه:** لطفاً **همهٔ بخش‌های پروژه** را بررسی کنید، نه فقط آخرین تغییر: اپ اندروید، همهٔ موتورهای اتصال، Cat Warp،
> Fox VPN، اسکنر و جعبه‌ابزار، تنظیمات، ظاهر/تم، همهٔ پنل‌ها (Cloudflare، Vercel، Node روی هر هاست، Railway)، صفحه‌های HTML، ربات نصب و CI.
> هدف: هر چیزی که خراب، ناقص، تکراری، بی‌اثر یا گیج‌کننده است پیدا شود — با دلیل و محل دقیق.

- **مخزن (عمومی):** https://github.com/mazodimobinhost-creator/cat-client
- **شاخهٔ کار:** `arena/c5b36768-cat-client` (همهٔ کار فقط روی همین شاخه). **PR شمارهٔ ۹ ادغام نمی‌شود.**
- **آخرین نسخه:** `v1.10.0-beta243` (versionCode 310) — پنل `6.81.2`. انتشارها: صفحهٔ Releases مخزن (pre-release).
- **قرارداد کار و قوانین ثابت:** [`docs/COLLABORATION.md`](COLLABORATION.md) — اول این را بخوانید؛ بخش ۲ (قوانین) و بخش ۸ (چک‌لیست) الزامی است.

---

## ۰) اگر به GitHub دسترسی ندارید (صفحه‌های درخت بسته / شبکهٔ ابزار خاموش)

کد در Google Drive هم هست — پوشه: https://drive.google.com/drive/folders/1uS0bj7aGXc-TzHTuEdChAQ6E6kAQdxwY
هر سند زیر ~۱۰۰ هزار نویسه است تا ابزار خواندن شما آن را نبُرد (سند ۱ قدیمی بخش پنلش بریده می‌شد).
- **A — diff کامل اپ beta242 → beta243** (Kotlin + تست‌ها): https://docs.google.com/document/d/12uZVNjv1AEdi9xb0SKwamYBcUm6vmuZu6sx3P44_L8E/edit
- **B — diff کامل پنل beta239 → beta243** (Worker 6.80 → 6.81.2 + `pages/` + تست‌های پنل؛ بدون برش): https://docs.google.com/document/d/1ZAHAn2lb73kW7FSMYBGh7UwWKfKIgFQ6PAgK-Hm7IFA/edit
- **C — `catclient.worker.js` کامل** در ۱۲ بخش به ترتیب (سرآیند هر بخش بازهٔ خط‌ها را می‌گوید؛ `/api/ech-pool` با پارامتر `skip` حدود خط ۸۳۰۱، `dialTarget` حدود ۳۴۴۰):
  - C 01/12: https://docs.google.com/document/d/1nR_Lw7yAibE4p1ZeCi7af6VnJ3Yy_V3NNGG2BQkLeDg/edit
  - C 02/12: https://docs.google.com/document/d/12CxSS960t7VHKtiPNNDWriE3pt8lls1pFHg4B_eo3so/edit
  - C 03/12: https://docs.google.com/document/d/11y6Gd4I9nRI_z3Va4cAHkTJGML6cpVv7xMDi0bvcylo/edit
  - C 04/12: https://docs.google.com/document/d/1B8DhHhtWgFlqPHVAe-H4-lUCRuhF--ODRZlt1-XvjXc/edit
  - C 05/12: https://docs.google.com/document/d/14Nv44jkTvi-1WMWVvjSg5uxUfzSrJ0Ef-_AKLcLATNc/edit
  - C 06/12: https://docs.google.com/document/d/1sdizsfBlqNRPmh710HbboKG6N4LgE2-W9cxsHEZjMEs/edit
  - C 07/12: https://docs.google.com/document/d/1G5UMWj9GbUhCY9gwnkqV-fzMhkUCmKl1ju8J1npq4Xw/edit
  - C 08/12: https://docs.google.com/document/d/1yeXQfRGUbpWec79NnlQDN-WCMU1hKcZaMI66eH7rmwk/edit
  - C 09/12: https://docs.google.com/document/d/1vYcK76FdrS8Ksj4hnf7HBvxYiqhXdrjKmkkBj9qtoQg/edit
  - C 10/12: https://docs.google.com/document/d/10tWkUeBQXypt7w2R5APofTOzDPOOZo8W9jUN2UxKhFY/edit
  - C 11/12: https://docs.google.com/document/d/1odHEBne2aLDYOVGC2B2afmm_44MG_Tp6P3fXaZZlqPA/edit
  - C 12/12: https://docs.google.com/document/d/15NjLgm0mK3pNW1CXDvpjiYZas5JSvVO7QF5lBSTySIk/edit
- **D — فایل‌های کامل Aether در beta243** (planner، `AetherFallbackStages`، session، carrier، Psiphon chain، startup سرویس، config، تست‌ها): https://docs.google.com/document/d/12KfQTdFZW9FkEnnfW02hJQ3xMEhIvkaNjdWBpYJKeRI/edit
- قدیمی (beta242): diff beta240→242: https://docs.google.com/document/d/1aPZBK3-ICbRj3HZoku2_DnMYOSiHHKczKifh6N629M8/edit · فایل‌های کامل beta242: https://docs.google.com/document/d/1fzqliv9t-uLYYdyPdTTfH34CWysoGtAuH3T7bFnweUQ/edit

فایل خام هر مسیر هم از این الگو باز می‌شود (بدون صفحهٔ درخت):
`https://raw.githubusercontent.com/mazodimobinhost-creator/cat-client/arena/c5b36768-cat-client/<مسیر>`
مثلاً `…/app/src/main/java/com/cat/client/AetherNetworkPlanner.kt` یا `…/docs/COLLABORATION.md`.

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
scripts/review-worktree.sh origin/arena/c5b36768-cat-client --base 8f97a76   # تغییرات beta241 + beta242
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
| ساخت اپ اندروید (همهٔ ABIها) و انتشار | CI سبز در هر نسخه؛ beta243 در `f840249`: همهٔ اجراهای push و PR سبز، pre-release با ۲۰ فایل | — |
| تست‌های JVM اپ | ۶۹۷ تست در ۱۲۲ فایل؛ در CI سبز | — |
| پنل Cloudflare (Worker) | ۸۳ فایل تست `scripts/panels/*.test.mjs`؛ ترافیک واقعی WebSocket در Miniflare (`worker-realtraffic` ۲۲/۲۲)؛ ورود و صفحه‌ها در UI e2e | پنل‌های CF کاربر باید به 6.81.2 به‌روز شوند و 🔌 (DATA_PLANE) زده شود — **تأیید نشده** |
| پنل Vercel | تست‌های `vercel-*.test.mjs` (beta245: اتصال خودکار Blob هنگام «آپدیت»، ۳۸ ✓) | پنل زنده هنوز روی **6.77.3** و **بدون Storage** است (`cat-panel-v7k2m`) — کاربرها ماندگار نیستند تا «آپدیت» با توکن زده شود |
| beta245: چند کشور خروجی، ➕ به پنل، اسکنر به تفکیک اپراتور، نقطهٔ سبز پنل‌ها | `loc-multi-dom` (۱۵، صفحهٔ واقعی)، `locations` (۵۸)، `Beta245OperatorScanAndLiveDotTest` (۶) | نقطه‌ها و «📶» روی گوشی **تست نشده** |
| پنل Node (KataBump، Bot-Hosting، Lucity، Shiprr، SmarterASP، Zerops، Seenode، Hostim) | `node-host.test.mjs` و تست‌های هاست | SmarterASP آزمایشی بالا آمده (6.79.2)؛ Zerops/Seenode/Hostim **تست نشده**؛ Reality روی KataBump فقط با Xray ساختگی |
| پنل Railway (Xray واقعی) | گردش‌کار جدای «Railway panel tests» | تست زندهٔ اخیر ندارد |
| Cat Warp (Aether: WireGuard، MASQUE-H2، WARP-in-WARP، WoM، MiM، Psiphon/Tor) | تست‌های JVM؛ رفع beta241–243 با `Beta241AetherNetworkPlanTest` (۱۱) و `Beta243AetherReviewRound2Test` (۷ تست رفتاری روی شیء واقعی `AetherRuntimeConfig`) | رفع beta241–243 (کار روی خط دیگران) **روی گوشی تست نشده** |
| Cat Fox VPN (ورود موزیلا، WebChannel، رفع 406) | `Beta237FoxPassTest`، `Beta238FoxWebChannelTest`، تست‌های beta239 | **تست نشده** روی گوشی |
| یابندهٔ پروکسی تلگرام (MTProto) | تست‌های beta239 | **تست نشده** |
| تنظیمات beta240 (Sniffing، FakeDNS، پروکسی HTTP سیستم، آدرس پینگ) | `Beta240CoreOptionsTest` | **تست نشده** روی همراه اول/ایرانسل |
| اسکنر IP / CDN / اینترنت ملی، DNS رمزدار | تست‌های JVM و `scanner-sources.test.mjs` | اسکن DoH/DoT روی دو اپراتور **تست نشده** |
| SSTP (VPN Gate)، تونل‌های DNS، Hysteria v1/hy2، WireGuard/Amnezia | تست‌های JVM و تبدیل لینک | گزارش تست زندهٔ جدید ندارد |

**مشکلات باز شناخته‌شده:** (باگ ۴ زنجیره در beta242 بسته شد — پایین)؛ بهینه‌سازی از دادهٔ واقعی (بسته به به‌روزرسانی پنل زنده)؛
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

### پاسخ به دور اول بازبینی (beta242) — هر مورد با کد بررسی شد

| # | فرضیهٔ بازبین | نتیجهٔ بررسی کد | کار انجام‌شده |
|---|---|---|---|
| 1 | تشخیص IPv6: ULA/link-local، VPN فعال، 464XLAT | ULA (fc00::/7)، link-local، site-local و loopback از قبل حذف بودند؛ 464XLAT واقعاً IPv6 دارد (درست). **ایراد واقعی:** وقتی تونل خودمان بالاست، «اولین شبکهٔ غیر VPN» دلخواه انتخاب می‌شد | یک انتخاب‌گر مشترک `pickUnderlyingNetwork`: شبکهٔ فعال غیر VPN، وگرنه اول validated، بعد Wi-Fi/Ethernet، بعد داده — برای کلید شبکه و تشخیص IPv6 |
| 1b | IPv6 که آدرس و مسیر دارد ولی کار نمی‌کند | بدون پروب قابل تشخیص نیست؛ endpoint لینک فقط ۶۰ ثانیه فرصت دارد و بعد هسته روی هر دو خانواده جست‌وجو می‌کند | تغییری نکرد (محدودیت شناخته‌شده) |
| 2 | کلید شبکه با SSID/BSSID؟ عوض شدن خروجی با «حالت یادگرفته»؟ | کلید Wi-Fi = هش gateway + DNS (بدون SSID، بدون مجوز مکان)؛ داده = `cell:<MCCMNC>`. **ایراد واقعی:** WARP-in-WARP با خروجی «auto» اگر WireGuard یاد گرفته می‌شد، تک‌لایه `wg` (آی‌پی ایران) می‌شد | قاعدهٔ مشترک `alternateMode` / `exitForAlternate`: جایگزینِ حالت دولایه همیشه دولایه (خارج) می‌ماند؛ در planner، `AetherSession` و `AetherProxyCarrier`؛ فقط همان یک جایگزین از حافظه پذیرفته می‌شود؛ تست برای همهٔ حالت‌ها × همهٔ خروجی‌ها |
| 3 | `hits.firstOrNull()` در ویزارد | لیست خالی ⇐ `null` (endpoint ساختگی نمی‌آید). **ایراد واقعی:** نتیجه‌ای که تست اتصال کامل را رد کرده بود هم «✅ وصل می‌شود» نشان داده می‌شد | فیلد `confirmed`؛ نمایش «🟡 پاسخ داد ولی اتصال کامل تأیید نشد» و رتبهٔ پایین‌تر؛ پاسخ پروب TCP/UDP هم تأیید حساب نمی‌شود |
| 4 | باگ ۴ زنجیره fail-open | **تأیید شد:** در پنل، «خروج از پنل لینک‌شده — همهٔ سایت‌ها» با قطع لینک بی‌صدا به direct / ProxyIP می‌رفت (آی‌پی و کشور عوض می‌شد) | حالت `all` حالا fail-closed: خطای روشن `link exit down (all sites)` و `/api/links` ⇐ `exitDown`؛ حالت `cf` همان fallback را دارد. پنل 6.81.1 |
| 5 | ProxyIPهای سخت‌کدشده | فقط نمونه/بذر هستند؛ پنل از تنظیمات + فیدها می‌خواند؛ Lite Worker با `?proxyip=` یا `PROXYIP` عوض می‌شود. پورت‌ها به دستور صاحب پروژه ثابت‌اند | تغییری نکرد |

### پاسخ به دور دوم (beta243) — Grok (پنل) و Claude (اپ)؛ هر مورد با کد بررسی شد

| # | از | یافته | نتیجهٔ بررسی | کار انجام‌شده |
|---|---|---|---|---|
| G1 | Grok | `pages/wrangler.jsonc` با `OPEN_PANEL`/`OPEN_SUB` = "true" | **تأیید شد:** روی استقرار Pages، `/sub` بدون کلید لینک‌های اصلی را می‌داد و پنل تا گذاشتن رمز بدون ورود باز بود (ربات و نصب Worker از قبل false می‌گذاشتند) | هر دو "false"؛ `pages-target.test.mjs` آن را چک می‌کند |
| G2 | Grok | نیمهٔ `settings.chain` باگ ۴ | **تأیید شد:** با زنجیره «همه» و کلید Strict خاموش (پیش‌فرض) قطع زنجیره به direct می‌رفت و چون لاگ در تولید خاموش است دیده نمی‌شد | کلید Strict («اگر سرور زنجیره در دسترس نبود، قطع شو») انتخاب صاحب پنل می‌ماند (عوض کردن پیش‌فرض کاربران را قطع می‌کرد)؛ ولی حالا fallback ثبت می‌شود: `/api/settings` ⇐ `exitDown` و با باز کردن پنل toast قرمز «⛓️✗ chain: …» (برای خروج لینک هم). پنل 6.81.2 |
| G3 | Grok | ابهام پورتال / Pages در مستندات | **درست:** `portal/` پورتال حساب جدا (D1) است و `pages/` همان پنل روی Pages؛ COLLABORATION پورتال را زیر `scripts/pages/` نوشته بود | ردیف‌های جدا در COLLABORATION بخش ۳ |
| G4 | Grok | پنل زنده قدیمی است | درست (Vercel روی 6.77.3) | کار محصولی؛ به‌روزرسانی با ملاد |
| C1 | Claude | ویزارد peer را بدون تأیید در حافظهٔ شبکه می‌نوشت | **تأیید شد:** وقتی هیچ‌چیز جواب نمی‌داد peer ثابت جایگزین (rtt 9999) «تأییدشده در این شبکه» ثبت می‌شد | فقط `reachable && confirmed` ثبت می‌شود؛ پروفایل همچنان آن را به‌عنوان hint دارد. بقیهٔ فراخوانی‌ها (۸۵۲، ۱۱۸۰، ۲۳۳۴، ۲۳۷۵) پاسخ واقعی اسکن native همین خط‌اند |
| C2 | Claude | بودجهٔ ۶۰ ثانیهٔ hint به مراحل بعد نشت می‌کند | **تأیید شد** (`config.copy` بودجه را نگه می‌داشت) | مراحل کشف و حالت دیگر همیشه بودجهٔ کامل جست‌وجو (`SEARCH_BUDGET_MS`) |
| C3 | Claude | نیمهٔ زنجیرهٔ باگ ۴ | همان G2 | همان G2 |
| C4 | Claude | `AetherProxyCarrier` رفع‌ها را ندارد + جدول جایگزین جدا | **تأیید شد:** کشف تکراری، بدون بودجه، جدول دیگر (WoM ⇐ MASQUE-H2 + خارج = باز هم `wom`) | یک زنجیرهٔ مشترک `AetherFallbackStages` برای session و carrier؛ carrierهای Psiphon/Tor بودجهٔ plan را می‌گیرند. **باگ تازه که تست رفتاری پیدا کرد:** جایگزین WARP-in-WARP یعنی WireGuard + خارج دوباره `wiw` می‌شد (همان ترابری UDP) ⇐ حالا WARP-over-MASQUE (دولایه، TCP/H2) |
| C5 | Claude | پنجرهٔ ۷ روزهٔ حالت یادگرفته می‌لغزد | **تأیید شد** | زمان اولین یادگیری حفظ می‌شود؛ بعد از ۷ روز حالت درخواستی دوباره امتحان می‌شود |
| C6 | Claude | جلسهٔ failover بدون watchdog | **تأیید شد** (و بدتر: با `state` باقی‌مانده از جلسهٔ قبل) | watchdog جدا برای جلسهٔ retry؛ یادگیری فقط بعد از تمام شدن prepare همان جلسه (`lastMode`) |
| C7 | Claude | تست‌ها فقط grep منبع‌اند | منصفانه | تابع‌های خالص جدا شدند و با JVM تست رفتاری دارند: مراحل fallback، رکورد حالت یادگرفته، طبقهٔ آدرس IPv6 با `InetAddress`، رتبهٔ شبکه |

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
