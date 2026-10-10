# Cat Client 1.10.0-beta53 (versionCode 120) — ربات دیپلوی 2.0.0

## 🛡️ ضد-1101: از این به بعد دیپلویِ نیم‌بند وجود ندارد

ورکرت دو بار با 1101 down شد — علت: paste موبایل. این نسخه کل زنجیرهٔ دیپلوی را بسته می‌کند:

### ربات تلگرام (نسخهٔ 2.0.0)
- **health-gate بعد از هر /deploy:** آپلود که تمام شد، خودش `/health` را می‌پرسد: «🫀 health OK — پنل زنده است و همین نسخه را سرو می‌کند» یا «⚠️ health FAILED … ⏪ /rollback»
- **/doctor:** چک کامل — توکن، ورکر، KV، health؛ اگر ورکر down باشد راه می‌دهد
- **/rollback:** برگشت فوری به نسخهٔ قبل

### GitHub Actions (اگر Secrets را بگذاری، بهترین راه می‌شود)
- Secrets: `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` → هر بتا **خودش** روی ورکرت دیپلوی می‌شود
- گام نو «Verify deployed health»: بعد از دیپلوی `/health` باید همان نسخه را بگوید؛ نگفت → رد قرمز (سبزِ دروغین ممنوع)
- وقتی Secrets نیست، هشدار بلند: «این رد سبز هیچ‌چیز دیپلوی نکرد»

### 📖 راهنمای کامل: `docs/panel-deploy-safety.md` — قانون طلایی: آپدیت فقط با /deploy یا Actions یا دکمهٔ Deploy-to-CF؛ هیچ‌وقت paste موبایل.
