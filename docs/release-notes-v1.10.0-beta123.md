# Cat Client 1.10.0-beta123 · Panel 6.69.10

- حذف یک بایت NUL خام از Worker که می‌توانست مسیر upload/parser کلادفلر را خراب کند و پیش از اجرای handler به 1101 برسد.
- افزودن gate ساخت برای رد قطعی هر Worker source یا artifact دارای بایت NUL خام.
- حفظ KV، UUID، bindings و داده‌های پنل؛ هیچ reset یا حذف داده‌ای انجام نمی‌شود.
- حفظ اصلاح Update Checker برای شناسایی Pre-releaseها.

این نسخه آزمایشی است. موفقیت تست و CI جایگزین evidence اجرای واقعی روی حساب Cloudflare نیست.
