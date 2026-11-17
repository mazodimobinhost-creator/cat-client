# Cat Client 1.10.0-beta124 · Panel 6.69.10

- فایل Worker داخل APK و دکمه‌های update/repair همگی روی Panel 6.69.10 بدون بایت NUL خام قرار گرفتند.
- دکمه‌های پنل دیگر از مسیر stable-only گیت‌هاب استفاده نمی‌کنند؛ جدیدترین Pre-release غیر-draft را resolve و همان `catclient.worker.js` نسخه‌دار را دریافت می‌کنند.
- URL و نام asset پیش از دانلود با repository/tag مورد انتظار اعتبارسنجی می‌شود.
- KV، UUID، bindingها و داده‌های persistent حفظ می‌شوند.
