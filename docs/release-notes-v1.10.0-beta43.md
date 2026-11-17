# Cat Client 1.10.0-beta43 (versionCode 110) — پنل 6.26.0

## 🔍 مقایسه با BPB و Zeus و نهان — چهار شکاف واقعی، هر چهار بسته شد

آنچه سنجیدم (سند: README/مستندات خودشان + کد): BPB (فرگمنت، WARP Pro، DoH خصوصی، روتینگ، chain، ساب چند-کور)، Zeus (سهمیه کاربر، صفحات وضعیت، بکاپ JSON، ربات)، نهان. چیزی که پنل ما **نداشت** و حالا دارد:

### ۱) یک لینک ساب برای همهٔ کلاینت‌ها (مثل BPB)
قبلاً برای هر کلاینت لینک جدا لازم بود (/clash/ /singbox/). الان همان `/sub/<uuid>` خودش می‌فهمد:
- **User-Agent**: ClashMeta/Mihomo/Stash → YAML • SFI/SFA/sing-box/Hiddify → JSON
- **`?target=`**: clash | singbox | xray | base64 | sub (مثل BPB، بر UA اولویت دارد)
همهٔ مسیرها: /sub، /sub64، /clash، /singbox، /xray و /u/<token>

### ۲) بلاک QUIC/HTTP3 (پارتی روتینگ BPB)
تنظیمات → سوییچ «مسدودسازی QUIC (UDP 443)» → در Clash و sing-box و Xray همزمان اعمال می‌شود. روی اپراتورهایی که UDP را خراب می‌کنند کلاینت را به TCP+TLS می‌چسباند.

### ۳) هاردنینگ امنیتی (قرارداد مشترک پنل‌های پابلیک)
مقایسهٔ هش رمز/سشن حالا **constant-time** است (timing attack روی `===` بسته شد) — session، Bearer و login.

### ۴) هدر `profile-web-page-url` در ساب
کلاینت‌های v2rayNG/Hiddify حالا دکمهٔ «صفحهٔ کاربر» را به /info/<token> می‌برند.

### ✅ از قبل داشتیم (مقایسه تأیید کرد): DoH خصوصی /dns-query • subscription-userinfo با انقضا • صفحات وضعیت کاربر /info • فرگمنت داخل Xray-full/singbox • WARP + WARP-in-WARP • chain • بکاپ/بازیابی • ربات تلگرام
### ❌ عمداً نداریم و صادقانه می‌گوییم: سهمیه حجم/ترافیک مثل Zeus (ورکر راه شمارش مطمئن ندارد) • چند-اککانت کلادفلر
### تست‌های جدید: UA→yaml/json • target=override • web-page-url • safeEqualHex • blockQuic در هر سه بیلدر
### قبلی: beta42 چک دوطرفه+منشأ • beta41 باگ &amp; • beta40 باکس وضعیت
