# Cat Client 1.10.0-beta78 (versionCode 145) — ریشهٔ واقعی «اپ باز می‌شود و می‌بندد» پیدا و بسته شد (پنل 6.52.0)

## ریشهٔ قطعی (با استک واقعی از گوشی شما)
ردیاب کرشِ beta77 استک دقیق را از گوشی گرفت:

```
Caused by: NullPointerException: Attempt to invoke... Context.getSharedPreferences(...) on a null object reference
    at android.content.ContextWrapper.getSharedPreferences
    at com.cat.client.MainActivity.<init>(Unknown Source:168)
```

**توضیح ساده:** یک متغیر در `MainActivity` مقدارش را **در همان لحظهٔ ساخته‌شدن آبجکت** از `SharedPreferences` می‌خواند. اما اندروید اکتیویتی را **قبل از اتصال Context** می‌سازد (`Class.newInstance()` قبل از `attachBaseContext()`)، پس آن لحظه Context هنوز `null` است → NPE → «اپ باز می‌شود و بلافاصله می‌بندد».

**از کِی؟** این خط همراه فیچر «اسکنر v6» در **beta70** اضافه شده بود؛ یعنی بیلدهای ۷۰ تا ۷۷ روی *همهٔ* گوشی‌ها همین کرش را داشتند (نه فقط گوشی شما) — و چون CI ما اپ را واقعاً اجرا نمی‌کند (رانر KVM ندارد)، هیچ تستی نمی‌گرفتش.

**فیکس:** مقدار اولیهٔ امن + خواندن پریف‌ها در `onCreate` (جایی که Context واقعی است). یک «مین دوم» از همان جنس (`LinearLayout(this)` در همان فاز) هم پیدا و خنثی شد.

## چطور مطمئن شویم دیگر برنمی‌گردد (سه‌لایه)
1. **گارد استاتیک جدید** `scripts/android-startup-guard.py`: تمام ۸۴ فایل Kotlin را تحلیل می‌کند و هر «استفادهٔ Context در سازندهٔ Activity/Application» را پیدا می‌کند؛ روی بیلدهای ۷۰..۷۷ **هر دو خط مقصر را می‌گیرد** و الان پاس است.
2. **تست دائمی** `scripts/panels/android-guard.test.mjs` (۳ بند): فیکسچرِ خراب باید fail شود، الگوهای سالم (`by lazy`/`lateinit`/مقدار ثابت) باید پاس شوند، و سورس واقعی اپ باید پاک بماند. داخل `npm test` و CI اجرا می‌شود.
3. `CrashWatch` (از beta77) که همین استک را از گوشی شما آورد، سر جایش است: نوتیف + فایل `Downloads/catclient-crash.txt`.

## نکتهٔ صادقانه
فیکس beta75 (R8/WorkManager) و غیرفعال‌کردن راه‌اندازی خودکار WorkManager در beta77 **ریشهٔ این کرش نبودند**؛ آن‌ها سخت‌سازی معتبر «ضدِ همین کلاس از خطاها» هستند و باقی می‌مانند، اما *این* کرش را این خطِ خاص می‌ساخت که حالا رفته است.

## نصب
APK بتا۷۸ (arm64 برای گوشی‌های معمول). پنل همان ۶.۵۲.۰.
