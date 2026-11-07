# «قانون» کلودفلر برای ۱۱۰۱ — چه چیزی هست، چه چیزی نیست

پرسش: «میشه یک rule در کلودفلر تعریف کرد که این مورد را برطرف کند؟»

پاسخ کوتاه: برای **کدِ لود‌نشده** هیچ rule‌ای وجود ندارد که درستش کند — چون
WAF/Rate limiting/Redirect همگی روی **درخواست** کار می‌کنند و به runtimeِ
خرابِ Worker دسترسی ندارند. اما چهار چیزِ کلودفلری واقعاً نقشِ «قانون» را بازی
می‌کنند و هر کدام یک کلاسِ خطا را می‌گیرند.

---

## ۱) سه کلاسِ خطا، و اینکه کدام ابزار کلودفلر به کدام می‌خورد

| کلاس | نشانه | ابزار کلودفلر که کمک می‌کند |
|---|---|---|
| **لود نشدن** (پیست/آپلود ناقص، دیپلوی ننشسته) | ۱۱۰۱ خام، HTML | **Rollback نسخه** + نسخه‌بندیِ دیپلوی |
| **استثنا در پردازشِ درخواست** | JSONِ `panel_crash` (مالک) | خودِ گاردِ ورکر (کد) — rule کلودفلر بی‌اثر است |
| **عبور از سقفِ CPU/حافظه** | **۱۱۰۲** («Worker exceeded resource limits») | Rate limiting binding (سبک‌سازیِ بار) + بهینه‌سازیِ کد؛ روی پلنِ پرداختی: بالا بردنِ سقفِ CPU |

> تصحیحِ مهم نسبت به تحلیلِ قبلیِ من: عبور از سقفِ CPU/حافظه **۱۱۰۱ نیست، ۱۱۰۲
> است**، و در داشبورد به‌شکلِ `Exceeded CPU Time Limits` / `Exceeded Memory` زیرِ
> **Metrics → Errors → Invocation Statuses** دیده می‌شود (outcome در Logpush:
> `exceededCpu` / `exceededMemory`). ۱۱۰۱ یعنی «استثنای گرفته‌نشده / کد اجرا نشد».

---

## ۲) Rollback — نزدیک‌ترین چیز به «قانونِ» موردنظر

آخرین نسخه‌ای که سالم بالا می‌آمد، فوری برمی‌گردد؛ نه آپلودِ دوباره لازم است نه
پاک‌کردنِ ورکر.

- **داشبورد:** Workers & Pages → ورکر → **Deployments** → ⋯ کنارِ نسخهٔ سالم →
  **Rollback**.
- **Wrangler:** `npx wrangler rollback [VERSION_ID]` (و `npx wrangler versions list`).
- تا **۱۰۰ نسخهٔ آخر** قابلِ بازگشت است.
- ⚠️ ریسورس‌ها (KV/D1) با rollback **برنمی‌گردند** — کد برمی‌گردد، داده نه.

## ۳) نسخه‌بندیِ دیپلوی — تا اصلاً به rollback نیاز نشود

- `npx wrangler versions upload` نسخه می‌سازد **بدونِ** پخشِ ترافیک، و بعد
  `npx wrangler versions deploy <id>@10% <current>@90%` رول‌اوتِ تدریجی می‌دهد.
- اتصالِ ریپو به **Workers Builds** یعنی هر push خودش نسخه می‌سازد و داشبورد لیستِ
  نسخه‌ها را نگه می‌دارد (همین ریپو `wrangler.jsonc` را برای این مسیر آماده دارد:
  `main = dist-panel/catpanel.stealth.js`، دیپلوی: `npx --yes wrangler@latest deploy --no-bundle`).
- ⚠️ در Workers Builds نامِ Worker در داشبورد باید **دقیقاً** با نامِ داخلِ
  `wrangler.jsonc` یکی باشد، وگرنه همان ۱۱۰۱ تکرار می‌شود.

## ۴) Rate limiting — دو نوعِ متفاوت، که فقط یکی روی workers.dev کار می‌کند

**الف) Rate limiting binding (داخلِ خودِ Worker) — روی `*.workers.dev` هم کار می‌کند.**
بک‌اندِ همان زیرساختِ rate-limiting rules است، از پلنِ رایگان هم در دسترس است و
فقط یک تغییرِ کد/config می‌خواهد:

```jsonc
// wrangler.jsonc
"ratelimits": [
  { "name": "PANEL_RATE", "namespace_id": "1001", "simple": { "limit": 120, "period": 60 } }
]
```
```js
const { success } = await env.PANEL_RATE.limit({ key: path + ':' + (request.headers.get('cf-connecting-ip') || '') });
if (!success) return new Response('{"ok":false,"error":"rate_limited"}', { status: 429 });
```
- محدودیت‌ها: period فقط **۱۰ یا ۶۰ ثانیه**، شمارنده‌ها **per-location** و
  eventually-consistent («ترمز، نه سهمیهٔ سخت»)، و در داشبورد دیده نمی‌شوند
  (فقط در Workers Logs به‌شکلِ پاسخ‌های ۴۲۹).
- ⚠️ **مسیرهای دیتاپلین (WS/تونل/ساب) باید از قاعده مستثنا باشند**، وگرنه خودِ
  کاربرانِ VPN را ۴۲۹ می‌زنی. فقط `/api/*` و صفحهٔ پنل را بشمار.

**ب) Rate limiting rule (zone-level) — فقط با دامنهٔ اختصاصی.**
روی `*.workers.dev` اعمال **نمی‌شود**، چون آن زیردامنه zoneِ تو نیست. اگر ورکر را
با **Custom Domain** روی دامنهٔ خودت (مثلاً `panel.example.com`) بیاوری، مسیرِ
داشبورد باز می‌شود: Security → **Security rules → Rate limiting rules**، با
expression مثل:

```
(http.host eq "panel.example.com" and http.request.uri.path contains "/api/")
```
و شمارشِ per-IP (مثلاً ۶۰ در دقیقه برای ورود/پنل).

- ⚠️ **دام: WAF/Bot را کورکورانه روی همین هاست روشن نکن.** Bot Fight Mode،
  Security Level بالا و «Under Attack» می‌توانند دیتاپلین (WebSocket/ساب) را
  بشکنند. هر rule را به مسیرهای پنل محدود کن و مسیرهای تونل را exclude کن.
- فایدهٔ جانبی: دامنهٔ اختصاصی خودش ریسکِ تعلیق و گزارشِ سوءاستفاده را کم می‌کند
  (همین نکته در `docs/panel-deploy-safety.md` هم آمده).

## ۵) هشدار — «قانونی» که قبل از کاربران باخبرت می‌کند

- **داشبورد:** Workers & Pages → ورکر → **Metrics → Errors → Invocation Statuses**:
  اینجا بینِ `Worker threw exception` (۱۱۰۱)، `exceededCpu`/`exceededMemory`
  (۱۱۰۲) و لبه‌های دیگر فرق می‌گذاری.
- **Notifications:** در حساب کلودفلر → Notifications، آلارمِ مربوط به Workers
  (خطای زیاد / نزدیک شدن به سقف‌ها) را روی ایمیل روشن کن؛ حداقل روزی یک‌بار اگر
  اشتباهی رخ داد خبردار می‌شوی، نه از زبانِ کاربر.
- **اپ** هم مانیتورِ خودش را دارد (زمان‌بندیِ ۳۰ دقیقه‌ای + اعلانِ «کد اجرا نشد») و
  «تستِ سه‌نقطه‌ای پنل» که همین حالا سه آدرس را یک‌ضربه چک می‌کند.

## ۶) چه چیزی راه‌حل **نیست** (تا عمرت را برایش نگذاری)

- **Cloudflare Access** جلوی ورکر ⇒ کاربرانِ واقعی VPN را پشتِ صفحهٔ لاگین قفل
  می‌کند؛ برای پنلِ عمومی نادرست است.
- **Redirect rule** ⇒ rule‌ها روی *درخواست* ارزیابی می‌شوند، نه روی پاسخ؛
  نمی‌شود گفت «اگر ۱۱۰۱ آمد، برو به صفحهٔ فلان».
- **Load Balancing / failover** ⇒ روی `workers.dev` نیست و ورکرِ خراب را زنده نمی‌کند.
- **متنِ استاتیکِ «پنل پایین است»** ⇒ اگر بخواهی هم فقط با Worker دومی که route را
  بگیرد ممکن است (و آن هم پیچیدگیِ خودش را دارد). ساده‌ترش: همان rollback.

## ۷) چک‌لیستِ عملی

1. ورکر را باز کن → **Deployments** → ببین نسخهٔ قبلی سالم هست؟ اگر بله، **Rollback**.
2. یک بار **`/api/version`** را بزن: JSON ⇒ لود سالم است؛ HTML ⇒ فایل ننشسته.
3. اگر CPU/حافظه مظنون است: **Metrics → Errors → Invocation Statuses** را ببین
   (۱۱۰۱ در برابر `exceededCpu`/`exceededMemory`). روی پلنِ پرداختی سقفِ CPU را
   بالا ببر؛ روی رایگان، بارِ کرون را تقسیم کن (هر تیک یک فید، یا صفحه‌به‌صفحه).
4. برای «ترمزِ» بار: یا **Rate limiting binding** (روی workers.dev هم کار می‌کند،
   ولی تغییرِ کد ورکر می‌خواهد و باید دیتاپلین را مستثنا کند) یا **دامنهٔ اختصاصی +
   Rate limiting rule**.
5. آلارمِ Notifications را روشن کن و هر ماه یک‌بار داشبورد را برای ۱۱۰۱/۱۱۰۲ نگاه کن.
6. روتینِ آپلود: همیشه **همان نامِ Worker**، فایلِ سالم از تگِ انتشار، و بعد از هر
   دیپلوی یک `/api/version` تمیز (JSON).
