# Cat Client 1.10.0-beta127 · Panel 6.69.12

- اصلاح session login برای Cloudflare production: امضای cookie دیگر به HMAC `subtle.importKey/sign` وابسته نیست و با SHA-256 موجود در همان runtime و secret در انتهای payload ساخته می‌شود.
- حفظ مقایسه constant-time، انقضای هفت‌روزه و HttpOnly/Secure/SameSite cookie.
- حفظ Panel شفاف بدون camouflage و artifact بدون obfuscation.
