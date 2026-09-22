# Cat Client v1.5.4 — کپی/اشتراک‌گذاری تک‌کانفیگ از داخل اپ

## چی عوض شد

### 📤 هر اتصال را جداگانه به v2rayNG / V2Box / Streisand بفرست
تا قبل از این فقط **لینک ساب** قابل کپی بود. حالا در صفحهٔ «تست اتصال‌ها» (روی هر ساب → تست) **روی هر اتصال نگه دارید** تا منوی آن باز شود:

- **کپی لینک** — همان اتصال به شکل `vless://` / `trojan://` / `vmess://` / `ss://` / `hysteria2://` / `anytls://` در کلیپ‌بورد.
- **کد QR** — برای اسکن با گوشی دوم.
- **اشتراک‌گذاری…** — ارسال به تلگرام، v2rayNG، V2Box و هر اپ دیگری.

این یعنی وقتی اسکنر یا تست پینگ نشان می‌دهد مثلاً `104.16.x.x [443]` سریع‌ترین است، همان یک کانفیگ را (نه کل ساب ۵۰تایی را) می‌توانید در v2rayNG وارد کنید.

### چطور کار می‌کند
- پارسر Mihomo (`MihomoConfigParser`) حالا **متن خام YAML هر پروکسی** را نگه می‌دارد (`MihomoProxy.rawYaml`).
- `MihomoShareLink` آن را با یک خوانندهٔ کوچک YAML (بلاک + flow `{…}`/`[…]`) به نقشه تبدیل و **معکوس `SubConvConverter`** را انجام می‌دهد: `servername→sni`، `client-fingerprint→fp`، `reality-opts→pbk/sid`، `ws-opts.path + max-early-data→path?ed=`، `ws-opts.headers.Host→host`، `grpc-opts→serviceName`، `http-opts→headerType=http`، `alpn`، `flow`، `allowInsecure`، IPv6 داخل `[]`.
- خروجی دقیقاً همان قالبی است که Cat Panel، BPB و v2rayNG می‌سازند، بنابراین کانفیگ کپی‌شده در همهٔ کلاینت‌ها یک‌جور باز می‌شود.
- `ConnectionProfile.shareLink` به کاتالوگ ذخیره‌شده اضافه شد ولی **در fingerprint حساب نمی‌شود**؛ پس تاخیرهای کش‌شده و انتخاب فعلی شما بعد از آپدیت از بین نمی‌رود.
- Trojan روی پورت‌های بدون TLS پنل (۸۰، ۸۰۸۰، …) با `security=none` تولید می‌شود، مثل خود پنل.
- WireGuard / TUIC / گروه‌های Mihomo قالب لینک استاندارد ندارند؛ برای آن‌ها منو باز نمی‌شود (پیام «لینک قابل اشتراک‌گذاری ندارد»).

### تست‌ها
- `MihomoShareLinkTest`: کانفیگ VLESS/WS پنل، Trojan بدون TLS، Reality+gRPC با IPv6 و YAML سبک flow، VMess (JSON base64)، Shadowsocks و انواع پشتیبانی‌نشده.
- تست‌های قبلی پنل (`cat-panel`, `tunnel-e2e`, `wizard`, `panel-dom`) بدون تغییر سبز هستند.

### نسخه
- اپ: `1.5.4` (versionCode 10)
- پنل: `5.4.0` (بدون تغییر — نیازی به دیپلوی دوبارهٔ ورکر نیست)
