# Cat Client v1.10.0-beta16

## پنل ۶.۱۰.۲ — حتی شکل مسیر هم عین BPB
- seed مسیرها حالا **۲۳ کاراکتر حروف بزرگ/کوچک+عدد** است (مثل `2K8pSSCvBFxPrMShrGA5OF` در BPB) — مثلاً `/vl/Kd93jXc02nvPsQ1bA7zLkMf?ed=2560` — و برای هر پنل ثابت و مخصوص خودش
- کانفیگ امضایی BPB تأییدشده روی ساب واقعی:
  `💦 16. VLESS - Clean IP : 8080` → `www.speedtest.net:8080`، `security=none`، `host=<ورکر تو>`، `path=/vl/<seed>?ed=2560`

## آن JSON «رمزنگاری‌شده» نیست
خروجی دکمهٔ Edit خود v2rayNG است؛ اجزایش:
| بخش JSON | از کجا می‌آید |
|---|---|
| remarks، address، port، uuid، host، path | **لینک کانفیگ پنل** ← پنل تو حالا عین BPB می‌سازد |
| dns (FakeDNS، 8.8.8.8)، routing (بلاک تبلیغات/ایران مستقیم)، policy، sockopt | **تنظیمات خود v2rayNG** — برای هر کانفیگی یکسان است، حتی BPB |

یعنی وقتی کانفیگ پنلت را در v2rayNG ویرایش کنی، همان JSON کامل BPB را می‌بینی؛ فقط host/uuid/path مال پنل خودت است.
