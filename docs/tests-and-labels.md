# تست‌ها و برچسب‌شان — کدام تست «واقعی» است و کدام نیست

هر تست در این مخزن دقیقاً یکی از این سه برچسب را دارد. قاعده: چیزی که اجرا نشده،
«تست‌نشده» است؛ حدس و فرض جای نتیجه نمی‌نشیند.

| برچسب | معنی | کجا اجرا می‌شود | نمونه در این مخزن |
|---|---|---|---|
| **کلودفلر واقعی** | روی حساب، دامنه و رانتایمِ واقعیِ کلودفلر؛ خروجی خام ثبت شده | ورک‌فلوهای Actions که `CLOUDFLARE_*` دارند + دیپلوی از خودِ اپ | `docs/cf-live-test-6.67.0.md`، رانِ سبزِ `38264339482`، پیوستِ فقط-خواندنیِ ۲۰۲۶-۱۰-۱۳ |
| **workerd محلی** | روی همان موتورِ کلودفلر ولی روی همین ماشین، بدون حساب | `workerd serve` روی `dist-panel/catpanel.obf.js` با همان کانفیگِ آپلودِ اپ | بخشِ «ج» در `docs/cf-live-test-6.67.0.md` |
| **دستگاه** | روی خودِ اندروید/امولاتورِ همین ریپو | استپِ «Smoke test - release APK launches on an emulator» در `android-build.yml` | آخرین ران: **skipped** — «Emulator capability check (KVM)» سبز شد ولی امولاتور بالا نیامد |

## فهرستِ تست‌های مخزن با برچسب

| تست / گارد | برچسب | دستور | آخرین وضعیت |
|---|---|---|---|
| `scripts/panels/route-pending.test.mjs` (۶ سنجه: بودجهٔ ۹۰s، ۴۰۴+HTML⇒PENDING، ترتیبِ PENDING قبل از recovery، استرینگ‌های دو لوکیل) | **workerd محلی** (تستِ منبع/بایت) | `node scripts/panels/route-pending.test.mjs` | ۶ pass / ۰ fail |
| `scripts/panels/*.test.mjs` (نقشهٔ مسیرها، auth، ساب، mask، rotate-uuid) | **workerd محلی** | `npm test` | ۵۰ pass / ۰ fail |
| «Panel worker tests (data plane, subscriptions, auth)» | **کلودفلر واقعی** | Android Build workflow | success در رانِ انتشارِ beta94 |
| «Unit tests (JVM)» | **دستگاه نیست** — میزبانِ JVM | Android Build workflow | success |
| `kotlin-syntax-check.py` | **workerd محلی** (ترکیبِ گرامر، بدون اجرا) | `python3 scripts/ci/kotlin-syntax-check.py` | ۱۰۸ pass / ۴ fail |
| «Smoke test - release APK launches on an emulator» | **دستگاه** | Android Build workflow | **skipped** — برچسبِ صادقانه: تست‌نشده |
| `scripts/panels/ui-e2e.mjs` + `.github/workflows/ui-e2e.yml` — مرورگرِ واقعی (Chromium 156) پنل را بوت می‌کند، از فرم لاگین می‌کند، وریفای می‌کند، و کد را از داخل مرورگر صدا می‌زند | **workerd محلی** (کارِ الگو: Cloudflare نیست) | `UI end-to-end` workflow — گیتِ دائمی: هر تغییرِ پنل/ابزارِ پنل روی `main` و `arena/*` و روی pull request | ۱۳→۱۵ سنجه؛ با همین تست یک باگِ واقعیِ SVG پیدا و رفع شد |
| پروبِ فقط-خواندنیِ دو ورکر (`edge-9ccq0`/`edge-vgiab`) | **کلودفلر واقعی** | ورک‌فلوی موقت (حذف شد؛ خروجی در پیوستِ `docs/cf-live-test-6.67.0.md`) | DNS✅، `/health`⇒۴۰۴ `1042`، API⇒۴۰۳/۴۲۹ |
