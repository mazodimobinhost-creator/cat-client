# Cat Client 1.10.0-beta119 (آزمایشی)

این نسخه فقط به‌صورت **Pre-release** منتشر می‌شود.

## تغییرات

- ورود امن نصب تازهٔ Cat Panel با `admin / admin` و درخواست واضح برای تغییر فوری رمز.
- حذف نمایش عمومی UUID و لینک ورود یک‌لمسی مبتنی بر UUID.
- بسته‌بودن مدیریت پنل به‌صورت پیش‌فرض (`OPEN_PANEL=false`).
- اجرای تست کامل پیش از انتشار Worker، سه probe تازه با تطبیق نسخه و rollback خودکار در شکست verification.
- ابزار محلی `Cat Worker 1101 Doctor` برای بررسی syntax/runtime-risk، پاک‌سازی secrets از گزارش و probe سه‌مرحله‌ای deployment.
- اصلاح UI E2E برای ورود واقعی با username و password جدید به‌جای استفاده نادرست از UUID.

## وضعیت اعتبارسنجی

این نسخه تا موفق‌شدن workflowهای Panel، Android و UI E2E منتشر نمی‌شود. موفقیت تست محلی یا CI به معنی اثبات نبودن Cloudflare 1101، policy action، اختلال اپراتور یا موفقیت روی گوشی واقعی نیست.
