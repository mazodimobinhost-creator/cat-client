# مطالعهٔ بیرونی: «UAC-SNI-Spoofer» و خودِ تکنیکِ SNI-Spoofing

یادداشتِ پژوهشی — **فقط مطالعه**؛ هیچ کدی از این مخزن‌ها وارد Cat Client نشده و
نباید بدون تصمیمِ صریح وارد شود (مجوزها در بخشِ ۵).

## ۱) آنچه در گیت‌هاب پیدا شد (۱۴ اکتبر ۲۰۲۶)

| مخزن | زبان | ستاره | آخرین push | مجوز |
| --- | --- | --- | --- | --- |
| [`Floxu1/UAC-SNI-Spoofer-Android`](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) | Kotlin/Rust (رسماً Rust برچسب خورده) | 431 | 2026-10-14 | **ندارد** (بدون LICENSE) |
| [`Floxu1/UAC-SNI-Spoofer-Windows`](https://github.com/Floxu1/UAC-SNI-Spoofer-Windows) | Python (PySide6) | 325 | 2026-10-13 | GPL-3.0 |

**تفاوتِ اصلی:** نسخهٔ ویندوز **واقعاً** SNI-Spoofing می‌کند (تزریقِ بسته)، نسخهٔ
اندروید **جعلِ بسته ندارد** و به‌جایش از Xray + finalmask استفاده می‌کند (بخش ۳).

## ۲) نسخهٔ ویندوز: تزریقِ بسته روی درایور

هستهٔ واقعی داخلش کدِ خودش نیست؛ بستهٔ شخصِ ثالث است:

- `third_party/patterniha_sni_spoofing/UPSTREAM.md` ⇒
  **`patterniha/SNI-Spoofing`** با کامیتِ pin‌شدهٔ `13b78cf`، مجوز **GPL-3.0**.
- آن upstream: پایتون + **`pydivert` (WinDivert)** ⇒ `injecter.py` یک `WinDivert`
  می‌سازد و بسته‌ها را می‌گیرد/تزریق می‌کند.
- `config.json` upstream:
  `LISTEN 0.0.0.0:40443` → `CONNECT_IP 188.114.98.0:443` با
  `FAKE_SNI auth.vercel.com`.
- نسخهٔ ویندوزی UAC علاوه بر آن `uac_desktop/fragment_proxy.py`،
  `sni_batch.py`، `sni_cleanip_precheck.py` دارد و «Xray + هستهٔ Patterniha» را
  ترکیب می‌کند؛ اجرا **نیاز به دسترسی ادمین** دارد (درایور + TUN).

نتیجه: این روش **ویندوزی** است. روی اندروید بدون روت، WinDivert/تزریقِ خام وجود
ندارد.

## ۳) نسخهٔ اندروید: همان اسم، مکانیزمِ دیگر (بدونِ روت)

اجزا: `VpnService` + پل `TUN` (`hev-socks5-tunnel`) + **Xray** (به‌صورت باینریِ
همراه، MPL-2.0) + `badvpn/tun2socks` (C، داخل `app/src/main/cpp`) + `aether`
(Rust، MASQUE/h2 روی WARP، در `core/`) + `psiphontunnel` aar + Tor با پل‌های
WebTunnel. سه موتور دارد و فقط یکی هم‌زمان اجرا می‌شود.

«SNI Spoofing» در اندروید اینجا از ماژولِ `com/uacspoofer/mobile/mci/` می‌آید:

- `MciConfig.kt`: لبه‌های **هاردکد** `104.26.13.100:443` (primary)،
  `104.26.14.40:443` (ایرانسل، `finalmaskMaxSplit = 100`)،
  `104.26.14.200:443` (fallback)؛
  `PATTERN_FAKE_SNI = "www.speedtest.net"`؛ `TLS_FINGERPRINT = "unsafe"`؛
  لیستِ ثابتِ cipher-suite‌ها.
- کامنتِ خودشان: «First record stops at the end of the cipher-suite list
  (**byte 104 of an unsafe hello**). The SNI then leaves in its own record.» ⇒
  یعنی ClientHello **قبل از افزونهٔ SNI** بریده می‌شود و SNI در رکوردِ جدا می‌رود
  تا DPI نتواند SNIِ واقعی را ببیند.
- `MciXrayConfigBuilder.kt`: از همین پارامترها کانفیگِ **Xray** می‌سازد
  (`finalmaskEnabled = true`).

**کلیدِ ماجرا:** در `XTLS/Xray-core` یک بستهٔ رسمی به نام
`transport/internet/finalmask/` وجود دارد (زیرشاخه‌ها: `fragment`, `header`,
`noise`, `salamander`, `sudoku`, `realm`, `xdns`, `xicmp`, `xmc`, `udphop`,
`mkcp`) — **بدونِ روت**، در فضای کاربر، روی خودِ جریانِ TLS. این همان چیزی است که
اسمش «SNI-Spoofing» گذاشته شده، ولی در واقع **قطعه‌قطعه‌کردنِ ClientHello** است.

## ۴) این برای Cat Client چه معنایی دارد (مهم)

هستهٔ ما **mihomo** است، نه Xray. بررسیِ کدِ mihomo:

```
search/code  fragment  repo:MetaCubeX/mihomo   → 0
search/code  finalmask repo:MetaCubeX/mihomo   → 0
search/code  TLSFragment repo:MetaCubeX/mihomo → 0
```

یعنی **mihomo این قابلیت را ندارد**؛ پس ما امروز نمی‌توانیم همان ترفند را داخلِ
کانفیگ اجرا کنیم. سه راه پیش رو:

1. **Xray به‌عنوان موتور دوم** در اپ (مثل UAC): کانفیگ‌سازِ Xray + اجرای باینریِ
   Xray + tun2socks جداگانه. کارِ سنگین (موتور موازی، حجم APK، مسیرِ تستِ تازه).
2. **افزودنِ fragment به mihomo** (پچ/فورکِ Go روی هسته‌ای که خودمان در CI
   می‌سازیم): سبک‌تر از گزینهٔ ۱ ولی نگهداریِ فورک لازم دارد.
3. **ماندن روی مسیرِ خودمان** (pin/fronting/SNI-override/ECH) و صادق‌بودن در
   نام‌گذاری: این‌ها DPI را فریب می‌دهند ولی **مکانیزمشان فرق دارد**.

## ۵) مجوزها — خطِ سرخ

| منبع | مجوز | اگر کد برداریم |
| --- | --- | --- |
| `patterniha/SNI-Spoofing` | GPL-3.0 | پروژهٔ ما هم باید GPL-3 شود (copyleft) و سورسِ کامل منتشر بماند |
| `Floxu1/UAC-SNI-Spoofer-Windows` | GPL-3.0 | همان |
| `Floxu1/UAC-SNI-Spoofer-Android` | **بدون LICENSE** | پیش‌فرض = تمام حقوق محفوظ ⇒ **کپیِ کد ممنوع**؛ فقط مطالعهٔ ایده |
| `XTLS/Xray-core` | MPL-2.0 | استفادهٔ کتابخانه‌ای با رعایتِ MPL (فایل‌های تغییر‌یافته باید منتشر شوند) |

## ۶) نکتهٔ صادقانه

هیچ‌کدام از این‌ها «تست‌شده برای ما» نیستند: کدِ UAC فقط **خوانده** شد، اجرا نشد،
و ادعاهای کارکردی‌اش را تأیید نمی‌کنم. هر تصمیمی برای الگوگرفتن باید با اندازه‌گیریِ
خودمان روی خطِ خودت همراه باشد.
