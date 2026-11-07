# Cat Client 1.10.0-beta120 / Cat Panel 6.69.8

نسخهٔ آزمایشی (Pre-release).

## تغییرات

- idle timeout واقعی tunnel که فقط پس از ده دقیقه بی‌فعالیتی عمل می‌کند و با ترافیک هر دو جهت reset می‌شود.
- cleanup قطعی socket، WebSocket و timer در timeout یا پایان tunnel.
- `OPEN_SUB=false` برای deploymentهای تازه؛ لینک معتبر subscription همچنان کار می‌کند.
- ابزار HTML امن `tools/cat-fixer.html` برای سه probe تازه و exact-version بدون دریافت API Token، رمز، UUID یا Cookie.
- تست‌های regression برای جلوگیری از ورود self-heal ناامن، endpoint پاک‌سازی عمومی یا redeploy بدون rollback.
- Cat Worker 1101 Doctor، ورود bootstrap با admin/admin و درخواست تغییر فوری رمز از نسخه قبلی حفظ شده‌اند.

این نسخه رفع قطعی Cloudflare 1101، نبود policy action یا موفقیت روی اپراتور/گوشی واقعی را ادعا نمی‌کند.
