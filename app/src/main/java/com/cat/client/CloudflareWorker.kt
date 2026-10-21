package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * PanelCatalog — catalog of supported self-hosted VPN panels, researched 2026-09.
 *
 * Deployment styles:
 *  - AUTO_UPLOAD_SCRIPT: Cat Client uploads its built-in Cat Panel.
 *  - PINNED_EXTERNAL_SCRIPT: an optional third-party bundle, downloaded from an
 *    immutable commit, hash-checked, and uploaded only after user confirmation.
 *  - GUIDE: the user opens a repository / installer and deploys manually.
 */
object CloudflareWorker {

    const val REPO_URL = "https://github.com/mazodimobinhost-creator/cat-client"
    const val WORKER_ASSET_PATH = "panels/catclient.worker.js"
    const val WIZARD_ASSET_PATH = "panels/catclient.wizard.js"

    /**
     * The official paste artifact — `dist-panel/catpanel.min.js`, built by
     * `scripts/panels/minify.mjs` and shipped inside the APK under this name.
     */
    const val PASTE_ASSET_PATH = "panels/catpanel.min.js"

    /** Marker line every readable paste artifact carries (first line of the file). */
    const val PASTE_VERSION_MARKER = "CAT_PANEL_VERSION"

    /** A paste artifact smaller than this is truncated or the wrong file. */
    const val PASTE_MIN_CHARS = 300_000

    /**
     * Official "Deploy to Cloudflare" button for the repo (README): deploys Cat
     * Panel on any Cloudflare account in one click — no app, no token pasting.
     * Sharing this link is the no-app path for friends who do not have Cat Client.
     */
    const val DEPLOY_BUTTON_URL = "https://deploy.workers.cloudflare.com/?url=$REPO_URL/tree/arena/c5b36768-cat-client"

    /**
     * Cloudflare "API token template" URL: opens dash.cloudflare.com with the exact
     * permissions pre-selected (Workers Scripts edit, Workers KV edit, Account Settings
     * read, User Details read). The user only taps Continue to summary → Create Token.
     * https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/
     */
    // Same permission set as the battle-tested BPB-Wizard template (Workers
    // Scripts:Edit, KV Storage:Edit, Pages:Edit, Zone DNS:Edit, User Details:Read)
    // so the dashboard pre-fills it in one tap and account listing works without
    // Account Settings:Read (BPB proved /accounts answers for any account-scoped edit token).
    const val CF_TOKEN_TEMPLATE_URL =
        "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" +
            "%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22workers_kv_storage%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22page%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22dns%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22user_details%22%2C%22type%22%3A%22read%22%7D%5D" +
            "&accountId=*&zoneId=all&name=Cat%20Panel"

    enum class DeployKind {
        /** We upload a JS module to Workers for this panel via API directly. */
        AUTO_UPLOAD_SCRIPT,
        /** A pinned, external bundle; always reviewed and confirmed before upload. */
        PINNED_EXTERNAL_SCRIPT,
        /** We walk the user through creating a token + guide them to the panel URL. */
        GUIDE,
    }

    enum class PanelScope(val labelEn: String, val labelFa: String) {
        CF_WORKER("CF Worker", "کلادفلر ورکر"),
        SERVER("Server / VPS", "سرور / VPS"),
        TUNNEL("Tunnel", "تونل"),
    }

    data class Panel(
        val id: String,
        val displayName: String,
        val displayNameFa: String,
        val scope: PanelScope,
        val description: String,
        val descriptionFa: String,
        val deployKind: DeployKind,
        val url: String,
        val defaultWorkerName: String = "",
    )

    val PANELS: List<Panel> = listOf(
        Panel(
            id = "cat-panel",
            displayName = "Cat Panel (Built-in)",
            displayNameFa = "Cat Panel (داخلی)",
            scope = PanelScope.CF_WORKER,
            description = "Single-file Cloudflare Worker panel: VLESS-WS + Trojan-WS + WARP, SNI whitelist, " +
                "clean Cloudflare IP variants, Mihomo/Clash YAML, optional REMOTE full-TCP tunnel. " +
                "Paste the code into any Worker — no other services needed.",
            descriptionFa = "پنل تک‌فایل روی کلادفلر ورکر: VLESS-WS + Trojan-WS + WARP، سفیدلیست SNI، " +
                "واریانت‌های IP سفید کلادفلر، خروجی Mihomo/Clash و تونل REMOTE اختیاری برای TCP کامل. " +
                "کد را داخل هر Worker بچسبانید — سرویس دیگر لازم نیست.",
            deployKind = DeployKind.AUTO_UPLOAD_SCRIPT,
            url = REPO_URL,
            defaultWorkerName = "catpanel",
        ),
        Panel(
            id = "zeus",
            displayName = "Z-E-U-S",
            displayNameFa = "پنل Z-E-U-S",
            scope = PanelScope.CF_WORKER,
            description = "Feature-rich worker: chain proxy, clean-IP scanner, private DoH, fragment, Warp+ and full routing.",
            descriptionFa = "پنل پیشرفتهٔ Worker با chain proxy، اسکنر IP تمیز، DoH اختصاصی، فرگمنت، Warp Pro و تنظیمات کامل مسیریابی.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/panel-zeus/Z-E-U-S",
            defaultWorkerName = "zeus-panel",
        ),
        Panel(
            id = "bpb",
            displayName = "BPB Worker Panel",
            displayNameFa = "پنل BPB",
            scope = PanelScope.CF_WORKER,
            description = "VLESS/Trojan/Warp subs, fragment, clean-IP, full Mihomo/Sing-box/Clash/Xray output.",
            descriptionFa = "ساب VLESS/Trojan/Warp، فرگمنت، IP تمیز، خروجی کامل برای Mihomo/Sing-box/Clash/Xray.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/bia-pain-bache/BPB-Worker-Panel",
            defaultWorkerName = "bpb-panel",
        ),
        Panel(
            id = "nova",
            displayName = "Nova Proxy",
            displayNameFa = "نوا پراکسی",
            scope = PanelScope.CF_WORKER,
            description = "Free-tier Cloudflare Worker panel: VLESS/Trojan/Shadowsocks over WS/gRPC/XHTTP, multi-user, " +
                "Nova Radar clean-IP scanner, WARP node for calls, backend mode.",
            descriptionFa = "پنل Worker رایگان: VLESS/Trojan/Shadowsocks روی WS/gRPC/XHTTP، چندکاربره، اسکنر IP تمیز Nova Radar، نود WARP برای تماس و حالت backend.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/IRNova/Nova-Proxy",
            defaultWorkerName = "nova-panel",
        ),
        Panel(
            id = "netra",
            displayName = "Netra Panel",
            displayNameFa = "پنل نترا",
            scope = PanelScope.CF_WORKER,
            description = "Cloudflare Workers VLESS/Trojan panel with Warp/Warp Pro, fragment/noise, full web panel at /panel, " +
                "Telegram installer bot.",
            descriptionFa = "پنل VLESS/Trojan روی کلادفلر ورکر با Warp/Warp Pro، فرگمنت/noise، پنل وب کامل در /panel و بات نصب تلگرامی.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/hghheh224/netra-panel",
            defaultWorkerName = "netra-panel",
        ),
        Panel(
            id = "apex",
            displayName = "Apex Panel",
            displayNameFa = "پنل Apex",
            scope = PanelScope.CF_WORKER,
            description = "Cloudflare Workers + D1 multi-user VLESS/Trojan panel: per-user UUID/password, quotas, expiry, " +
                "panel password + secure path.",
            descriptionFa = "پنل چندکاربره VLESS/Trojan روی Workers + D1: UUID/پسورد اختصاصی، حجم، انقضا، رمز و مسیر امن برای پنل.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/netrair/Apex",
            defaultWorkerName = "apex-panel",
        ),
        Panel(
            id = "epeius",
            displayName = "Epeius",
            displayNameFa = "ایپیوس",
            scope = PanelScope.CF_WORKER,
            description = "Trojan-over-WebSocket proxy + subscription engine on Workers/Pages: Clash/Sing-box/Surge/Loon output, " +
                "preferred clean-IP (PROXYIP) management, SOCKS5 outbound option.",
            descriptionFa = "پراکسی Trojan-over-WS + موتور سابسکریپشن روی Workers/Pages: خروجی Clash/Sing-box/Surge/Loon، مدیریت IP تمیز (PROXYIP) و خروجی SOCKS5.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/cmliu/epeius",
            defaultWorkerName = "epeius",
        ),
        Panel(
            id = "blueknight",
            displayName = "Blue-Knight Panel",
            displayNameFa = "پنل Blue Knight",
            scope = PanelScope.CF_WORKER,
            description = "Proxy panel + encrypted-DNS gateway + client subscription server for Cloudflare's edge or any Node 22 host; " +
                "WARP account registration and Amnezia (noise) profiles.",
            descriptionFa = "پنل پراکسی + دروازهٔ DNS رمزنگاری‌شده + سرور سابسکریپشن برای لبهٔ کلادفلر یا هر هاست Node 22؛ ثبت حساب WARP و پروفایل Amnezia (noise).",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/BlueKnightNet/Blue-Knight-Panel",
            defaultWorkerName = "blueknight",
        ),
        Panel(
            id = "technamooz-worker",
            displayName = "TechNamooz Worker (video · third-party)",
            displayNameFa = "Worker تکناموز (ویدیویی · شخص ثالث)",
            scope = PanelScope.CF_WORKER,
            description = "Opt-in install of the exact pinned Cloudflare Worker shown in the video. External, minified source; review the warning before upload.",
            descriptionFa = "نصب اختیاری همان Cloudflare Worker ویدیو از نسخهٔ ثابت‌شده. سورس فشردهٔ بیرونی است؛ پیش از نصب هشدار را بخوانید.",
            deployKind = DeployKind.PINNED_EXTERNAL_SCRIPT,
            url = "https://github.com/technamooz/panel-technamooz-VPN-cloudflare",
            defaultWorkerName = "technamooz",
        ),
        Panel(
            id = "marzban",
            displayName = "Marzban",
            displayNameFa = "موزبن",
            scope = PanelScope.SERVER,
            description = "The standard Xray-core management panel: users, traffic, expiry, nodes, REST API, Docker install.",
            descriptionFa = "پنل مدیریت استاندارد Xray: کاربر، حجم، انقضا، نودها، API و نصب Docker.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/Gozargah/Marzban",
        ),
        Panel(
            id = "3x-ui",
            displayName = "3x-ui",
            displayNameFa = "پنل 3x-ui",
            scope = PanelScope.SERVER,
            description = "Advanced Xray web panel: multi-protocol, per-client traffic/IP limits, one-click SSL, Telegram bot, API.",
            descriptionFa = "پنل وب پیشرفته Xray: چندپروتکل، محدودیت حجم/IP هر کلاینت، SSL یک‌کلیک، بات تلگرام و API.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/MHSanaei/3x-ui",
        ),
        Panel(
            id = "wui",
            displayName = "w-ui (WireGuard Panel)",
            displayNameFa = "w-ui (پنل WireGuard)",
            scope = PanelScope.SERVER,
            description = "Server-side WireGuard/AmneziaWG/OpenVPN panel for selling access — quotas, expiry, devices, Telegram bot.",
            descriptionFa = "پنل سمت‌سرور WireGuard/AmneziaWG/OpenVPN برای فروش دسترسی — حجم، انقضا، دستگاه، بات تلگرام.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/AbolfazlTafakori/w-ui",
        ),
        Panel(
            id = "nova-server",
            displayName = "Nova Server",
            displayNameFa = "نوا سرور",
            scope = PanelScope.SERVER,
            description = "Self-hosted censorship-resistant proxy server: Xray + sing-box + Hysteria2 + AmneziaWG, multi-node fleet, " +
                "Iran bridge tunnels, clean-IP refresh, Telegram mini-app.",
            descriptionFa = "سرور خودمیزبان ضد سانسور: Xray + sing-box + Hysteria2 + AmneziaWG، ناوگان چندنودی، تونل‌های پل ایران، رفرش IP تمیز و مینی‌اپ تلگرام.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/IRNova/Nova-Server",
        ),
        Panel(
            id = "spider",
            displayName = "Spider Panel",
            displayNameFa = "پنل اسپایدر",
            scope = PanelScope.SERVER,
            description = "VLESS Reality/WS/XHTTP + VMess/Trojan/SS panel with browser-side clean-IP scanner and a Cloudflare Worker " +
                "manager that routes opt-in users through country proxy IPs.",
            descriptionFa = "پنل VLESS Reality/WS/XHTTP + VMess/Trojan/SS با اسکنر IP تمیز سمت مرورگر و مدیر Worker کلادفلر برای مسیریابی کاربران از IP کشور دلخواه.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/amirh00sain/SpiderPanel",
        ),
        Panel(
            id = "technamooz",
            displayName = "Technamooz Panel",
            displayNameFa = "پنل تکناموز",
            scope = PanelScope.SERVER,
            description = "FastAPI config-builder panel: per-ISP clean IPs, separate CDN domain for Host/SNI (SNI-based block bypass), " +
                "XHTTP packet-up, token-bucket speed limiter, Telegram bot.",
            descriptionFa = "پنل ساخت کانفیگ با FastAPI: IP تمیز هر اپراتور، دامنه CDN جدا برای Host/SNI (عبور از بلاک SNI)، XHTTP packet-up، محدودکننده سرعت و بات تلگرام.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/technamooz/Panel_Technamooz_VPN",
        ),
        Panel(
            id = "sulgx",
            displayName = "SulgX Panel",
            displayNameFa = "پنل SulgX",
            scope = PanelScope.SERVER,
            description = "Single-file VLESS subscription panel: per-user bandwidth limits, clean-IP scanner, Clash/Sing-box links, " +
                "XHTTP, DOH link, bilingual Telegram bot, traffic charts.",
            descriptionFa = "پنل تک‌فایل سابسکریپشن VLESS: محدودیت حجم هر کاربر، اسکنر IP تمیز، لینک Clash/Sing-box، XHTTP، لینک DOH، بات تلگرام دوزبانه و نمودار ترافیک.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/rohitanandsharma3-hub/SulgX-Panel",
        ),
        Panel(
            id = "rvg",
            displayName = "RVG Gateway",
            displayNameFa = "RVG Gateway",
            scope = PanelScope.SERVER,
            description = "Multi-protocol proxy gateway (FastAPI, Railway-ready): per-link quotas, live stats, QR codes, " +
                "TLS fingerprint spoofing, Telegram bot, CF-worker domain suggestion.",
            descriptionFa = "درگاه چندپروتکلی (FastAPI، آماده Railway): کتای هر لینک، آمار زنده، QR، جعل fingerprint TLS، بات تلگرام و پیشنهاد دامنه با Worker کلادفلر.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/Taymaz1391/RVG",
        ),
        Panel(
            id = "luffy",
            displayName = "Luffy Panel",
            displayNameFa = "پنل لوفی",
            scope = PanelScope.SERVER,
            description = "Lightweight VLESS+Trojan panel (Render/Railway) that routes through Cloudflare clean IPs: multi-inbound, " +
                "quotas, clean-IP management, /sub/ compatible with v2rayNG/Hiddify.",
            descriptionFa = "پنل سبک VLESS+Trojan (رندر/ریل‌وی) که ترافیک را از IP سفید کلادفلر رد می‌کند: چند اینباند، کتای، مدیریت IP تمیز و /sub/ سازگار با v2rayNG و Hiddify.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/KiwwyQ/LuffyPanelFork",
        ),
        Panel(
            id = "lunel",
            displayName = "Lunel",
            displayNameFa = "لنل",
            scope = PanelScope.SERVER,
            description = "Multi-protocol proxy platform: isolated proxy instances (VLESS WS/xHTTP, Trojan, Shadowsocks) managed from a " +
                "web console with GitHub OAuth, live logs and reverse-proxied endpoints.",
            descriptionFa = "پلتفرم پروکسی چندپروتکلی: اینستنس‌های جدا (VLESS WS/xHTTP، Trojan، Shadowsocks) با کنسول وب، لاگ زنده و endpoint معکوس.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/ArasTey/lunel",
        ),
        Panel(
            id = "apex-railway",
            displayName = "Apex Panel (Railway VPS)",
            displayNameFa = "پنل Apex (ریل‌وی VPS)",
            scope = PanelScope.SERVER,
            description = "Multi-protocol multi-user panel for Railway: Vmess/Vless/Trojan/Shadowsocks/WireGuard/Hysteria/MTProto, " +
                "traffic + expiry + IP limits, Persian UI.",
            descriptionFa = "پنل چندپروتکلی چندکاربره روی ریل‌وی: Vmess/Vless/Trojan/Shadowsocks/WireGuard/Hysteria/MTProto با محدودیت حجم، انقضا و IP و رابط فارسی.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/mohammadtavaaakkooll-glitch/Apex-Panel-Railway-Vpn",
        ),
        Panel(
            id = "x4g-marzban",
            displayName = "x4g — Marzban on Railway",
            displayNameFa = "x4g — موزبن روی ریل‌وی",
            scope = PanelScope.SERVER,
            description = "PasarGuard-style build: clones official Marzban at build time, Railway-compatible (\$PORT), always-upstream. " +
                "Marzban-Node for extra nodes and 3x-ui-multi with Tor country exits.",
            descriptionFa = "سبک PasarGuard: کلون رسمی Marzban در لحظهٔ build، سازگار با Railway (\$PORT) و همیشه به‌روز. Marzban-Node برای نود اضافه و 3x-ui-multi با خروجی تور کشورها.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/x4gKing/Marzban-Panel",
        ),
        Panel(
            id = "vortex",
            displayName = "Vortex Network Panel",
            displayNameFa = "پنل Vortex",
            scope = PanelScope.SERVER,
            description = "Web UI for a sing-box policy-routing gateway: device management, force-direct/force-VPN rules, " +
                "diagnostics, transactional changes, backups and rollback.",
            descriptionFa = "رابط وب برای درگاه sing-box با policy-routing: مدیریت دستگاه، قوانین direct/VPN اجباری، تشخیص خرابی، تغییرات transactional، بکاپ و rollback.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/GariestGary/vortex-network-panel",
        ),
        Panel(
            id = "openvpn",
            displayName = "OpenVPN",
            displayNameFa = "OpenVPN",
            scope = PanelScope.SERVER,
            description = "The classic open-source VPN server (OpenVPN + EasyRSA). Pair with w-ui or openvpn-panel for a web GUI.",
            descriptionFa = "سرور VPN متن‌باز کلاسیک (OpenVPN + EasyRSA). برای رابط وب کنار w-ui یا openvpn-panel استفاده شود.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/OpenVPN/openvpn",
        ),
        Panel(
            id = "wg-easy",
            displayName = "wg-easy (WireGuard UI)",
            displayNameFa = "wg-easy (رابط WireGuard)",
            scope = PanelScope.SERVER,
            description = "Docker-based WireGuard server with a clean web UI, per-client keys and config download.",
            descriptionFa = "سرور WireGuard مبتنی Docker با رابط وب تمیز، کلید اختصاصی هر کلاینت و دانلود کانفیگ.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/wg-easy/wg-easy",
        ),
        Panel(
            id = "vodiwalker",
            displayName = "Vodiwalker",
            displayNameFa = "ودی‌واکر",
            scope = PanelScope.SERVER,
            description = "Self-hosted VPN panel with per-user subscriptions (see official repo for current release).",
            descriptionFa = "پنل خودمیزبان VPN با سابسکریپشن اختصاصی هر کاربر (نسخهٔ فعلی در ریپوی رسمی).",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/Vodiwalker",
        ),
        Panel(
            id = "backpack",
            displayName = "BackPack (Reverse Tunnel)",
            displayNameFa = "BackPack (تونل معکوس)",
            scope = PanelScope.TUNNEL,
            description = "High-performance Go reverse-tunnel engine for edge ⇄ origin — the recommended REMOTE backend for " +
                "Cat Panel's full-TCP mode.",
            descriptionFa = "موتور تونل معکوس پرسرعت Go برای edge ⇄ origin — backend پیشنهادی برای حالت REMOTE (TCP کامل) Cat Panel.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/AminMGMT/BackPack",
        ),
        Panel(
            id = "mlm-gateway",
            displayName = "MLM VPN Gateway",
            displayNameFa = "گیت‌وی MLM VPN",
            scope = PanelScope.SERVER,
            description = "Lightweight FastAPI VLESS gateway with one-click deploy to your own cloud (Docker/ghcr): " +
                "XHTTP relay, per-link speed limits, live stats and a Telegram bot.",
            descriptionFa = "درگاه سبک VLESS با FastAPI و استقرار یک‌کلیکی روی ابر خودت (Docker): رله XHTTP، " +
                "محدودیت سرعت هر لینک، آمار زنده و بات تلگرام.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/mlmvpn/mlmvpn-gateway",
        ),
        Panel(
            id = "mlm-proxy",
            displayName = "mlm-proxy",
            displayNameFa = "mlm-proxy",
            scope = PanelScope.SERVER,
            description = "Tiny JavaScript fetch-proxy for serverless hosts: relays app and subscription traffic " +
                "on restricted networks without running a server.",
            descriptionFa = "پروکسی fetch کوچک جاوااسکریپتی برای هاست سرورلس: ترافیک اپ و سابسکریپشن را در " +
                "شبکه‌های محدود بدون سرور اختصاصی رد می‌کند.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/mlmvpn/mlm-proxy",
        ),
        Panel(
            id = "cloud-web-panel",
            displayName = "Cloud Web Panel",
            displayNameFa = "پنل وب ابری",
            scope = PanelScope.SERVER,
            description = "Self-hosted Cloudflare VPN config panel with per-user password-derived encryption; " +
                "its console deploys multi-user Worker panels with quotas and expiry.",
            descriptionFa = "پنل خودمیزبان ساخت کانفیگ VPN روی کلادفلر با رمزنگاری مشتق از رمز هر کاربر؛ " +
                "از کنسولش می‌توان پنل‌های چندکاربرهٔ Worker با کتای و انقضا نصب کرد.",
            deployKind = DeployKind.GUIDE,
            url = "https://github.com/mlmvpn/cloud-web-panel",
        ),
    )

    fun byId(id: String): Panel? = PANELS.firstOrNull { it.id == id }

    /**
     * The panel version this APK ships. Every shipped artifact carries the marker
     * line — a one-line block comment holding CAT_PANEL_VERSION with the version in
     * plain text (the minifier guarantees it) — so the app
     * can compare «the panel I bundle» with «the panel that answers on the Worker»
     * without parsing the bundle itself.
     */
    fun bundledPanelVersion(context: Context): String =
        Regex("CAT_PANEL_VERSION\\s*=\\s*'([0-9]+(?:\\.[0-9]+)+)'")
            .find(builtInWorkerScript(context))
            ?.groupValues
            ?.get(1)
            .orEmpty()

    /**
     * `av`/`pv` for the panel's own app/panel handshake (6.67.0): non-secret
     * version strings that let the panel warn when the two halves disagree. Sent
     * only on the panel URL, never to the subscription.
     */
    private fun panelHandshakeQuery(bundledVersion: String): String {
        fun safe(v: String) = v.replace(Regex("[^0-9A-Za-z._+~-]"), "").take(24)
        val av = safe(BuildConfig.VERSION_NAME)
        val pv = safe(bundledVersion)
        val parts = mutableListOf<String>()
        if (av.isNotBlank()) parts += "av=$av"
        if (pv.isNotBlank()) parts += "pv=$pv"
        return parts.joinToString("&")
    }

    /** Bundled Cat Panel worker source (single file, paste-ready for the CF dashboard). */
    fun builtInWorkerScript(context: Context): String = runCatching {
        context.assets.open(WORKER_ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrDefault(MINIMAL_WORKER_SCRIPT)

    /**
     * The text the «copy code» button puts on the clipboard: the official 3-line
     * paste artifact (`catpanel.min.js`, ~474 KB), and the same normal minified module
     * the app uploads through the API. Size is the point — a 1.2 MB string is one
     * interrupted paste away from a truncated Worker, and a Worker whose file is cut
     * short throws on every request (Cloudflare's «Error 1101»; see the README
     * section of the same name). Returns null when the APK was built without the
     * asset (local builds), so the caller falls back with a visible warning instead
     * of silently handing over the big file.
     */
    fun builtInPasteScript(context: Context): String? = runCatching {
        context.assets.open(PASTE_ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Sanity gate before anything is copied or deployed: the marker line must be
     * there and the size must be in the right ballpark, so a half-written asset can
     * never reach the clipboard unnoticed.
     */
    fun pasteScriptIsSane(text: String?): Boolean =
        text != null && text.length >= PASTE_MIN_CHARS && text.contains(PASTE_VERSION_MARKER)

    /** Bundled Cat Wizard worker source (one-click installer page for friends). */
    fun builtInWizardScript(context: Context): String? = runCatching {
        context.assets.open(WIZARD_ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrNull()

    data class WizardDeploymentResult(
        val workerName: String,
        val wizardUrl: String,
        val verifiedOnline: Boolean,
    )

    data class TechNamoozWorkerDeploymentResult(
        val workerName: String,
        val workerUrl: String,
        val panelUrl: String,
        val subscriptionUrl: String,
        val uuid: String,
        val panelPassword: String,
        val subPath: String,
        val kvNamespaceTitle: String,
        val workersDevEnabled: Boolean,
        val sourceCommit: String = TechNamoozWorkerSource.PINNED_COMMIT,
        val sourceSha256: String = TechNamoozWorkerSource.EXPECTED_SHA256,
    )

    /**
     * Install the pinned upstream Worker as a separate, opt-in deployment.
     * The script is downloaded from the GitHub Contents API and both its exact
     * size and SHA-256 are checked before any Cloudflare resource is created.
     * This verifies identity only; the third-party source is not independently
     * audited by Cat Client.
     */
    suspend fun deployTechNamoozWorker(
        token: String,
        accountId: String,
        workerName: String,
        panelPassword: String,
    ): TechNamoozWorkerDeploymentResult = withContext(Dispatchers.IO) {
        val safeName = TechNamoozWorkerSource.normalizeWorkerName(workerName)
        require(TechNamoozWorkerSource.isValidWorkerName(safeName)) {
            "Worker name must be 1–63 lowercase letters, digits or hyphens, and start/end with a letter or digit."
        }
        require(TechNamoozWorkerSource.isValidPanelPassword(panelPassword)) {
            "Use a panel password of 16–128 characters with no whitespace."
        }

        // Fetch and pin-check before calling any Cloudflare write endpoint.
        val script = fetchPinnedTechNamoozWorker()
        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$safeName"
        ensureWorkerNameAvailable(token, uploadUrl, safeName)

        val subdomain = resolveWorkersSubdomain(token, accountId)
        val kvTitle = "$safeName-technamooz-kv"
        val kvId = ensureKvNamespace(token, accountId, kvTitle)
        val uuid = UUID.randomUUID().toString()
        val subPath = TechNamoozWorkerSource.newSecret(32).lowercase(Locale.US)
        val bindings = JSONArray()
            .put(JSONObject().put("type", "secret_text").put("name", "UUID").put("text", uuid))
            .put(JSONObject().put("type", "secret_text").put("name", "TR_PASS").put("text", panelPassword))
            .put(JSONObject().put("type", "secret_text").put("name", "SUB_PATH").put("text", subPath))
            .put(JSONObject().put("type", "kv_namespace").put("name", "kv").put("namespace_id", kvId))
        val uploaded = cfUploadWorker(
            token = token,
            url = uploadUrl,
            script = script,
            extraBindings = bindings,
        )
        if (!uploaded.optBoolean("success", false)) {
            throw RuntimeException(
                "TechNamooz Worker upload failed: " +
                    (uploaded.optJSONArray("errors")?.toString() ?: uploaded.optString("message", "unknown")),
            )
        }

        val workersDevEnabled = runCatching {
            cfPost(
                token,
                "$uploadUrl/subdomain",
                JSONObject().put("enabled", true).put("previews_enabled", false).toString(),
            ).optBoolean("success", false)
        }.getOrDefault(false)
        val workerUrl = "https://$safeName.$subdomain.workers.dev"
        TechNamoozWorkerDeploymentResult(
            workerName = safeName,
            workerUrl = workerUrl,
            panelUrl = "$workerUrl/panel",
            subscriptionUrl = TechNamoozWorkerSource.normalXraySubscriptionUrl(workerUrl, subPath),
            uuid = uuid,
            panelPassword = panelPassword,
            subPath = subPath,
            kvNamespaceTitle = kvTitle,
            workersDevEnabled = workersDevEnabled,
        )
    }

    /** Fetch the immutable upstream commit without executing its JavaScript. */
    private fun fetchPinnedTechNamoozWorker(): String {
        val endpoint = "https://api.github.com/repos/${TechNamoozWorkerSource.REPOSITORY}" +
            "/contents/worker.js?ref=${TechNamoozWorkerSource.PINNED_COMMIT}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CatClient/${BuildConfig.VERSION_NAME} (pinned-third-party-worker)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("Could not fetch the pinned TechNamooz source from GitHub API (HTTP $code).")
            }
            val response = String(conn.inputStream.use { readBounded(it, maxBytes = 900_000) }, Charsets.UTF_8)
            val json = JSONObject(response)
            if (json.optString("encoding") != "base64" || json.optInt("size", -1) != TechNamoozWorkerSource.EXPECTED_SIZE_BYTES) {
                throw RuntimeException("GitHub returned unexpected metadata for the pinned TechNamooz source.")
            }
            val encoded = json.optString("content").replace(Regex("\\s+"), "")
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                throw RuntimeException("GitHub returned invalid base64 for the pinned TechNamooz source.")
            }
            return try {
                TechNamoozWorkerSource.verifyAndDecode(bytes)
            } catch (e: IllegalArgumentException) {
                throw RuntimeException(e.message ?: "Pinned TechNamooz source integrity check failed.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw RuntimeException("GitHub response exceeded the size limit.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun ensureWorkerNameAvailable(token: String, uploadUrl: String, workerName: String) {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = conn.responseCode) {
                404 -> Unit
                in 200..299 -> throw RuntimeException("Worker name '$workerName' already exists; choose another name to avoid overwriting it.")
                else -> throw RuntimeException("Could not safely check whether Worker name '$workerName' is available (HTTP $code). No upload was made.")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Deploy the Cat Wizard on the user's account. Anyone who opens the resulting URL
     * can install their own Cat Panel with their own token (nothing is shared).
     * Optional [inviteCode] locks the wizard (WIZARD_PASSWORD secret binding).
     */
    suspend fun deployWizard(
        context: Context,
        token: String,
        accountId: String,
        workerName: String = "cat-wizard",
        inviteCode: String = "",
    ): WizardDeploymentResult = withContext(Dispatchers.IO) {
        val script = builtInWizardScript(context)
            ?: throw RuntimeException("wizard asset missing from the APK")
        val subdomain = resolveWorkersSubdomain(token, accountId)
        val wizardUrl = "https://$workerName.$subdomain.workers.dev"
        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
        val secrets = if (inviteCode.isBlank()) emptyMap() else mapOf("WIZARD_PASSWORD" to inviteCode)
        val putResult = cfUploadWorker(token, uploadUrl, script, uuid = "", kvNamespaceId = null, secrets = secrets)
        if (!putResult.optBoolean("success", false)) {
            val errors = putResult.optJSONArray("errors")?.toString() ?: "unknown"
            throw RuntimeException("Wizard upload failed: $errors")
        }
        runCatching {
            cfPost(
                token,
                "$uploadUrl/subdomain",
                JSONObject().put("enabled", true).put("previews_enabled", false).toString(),
            )
        }
        WizardDeploymentResult(
            workerName = workerName,
            wizardUrl = wizardUrl,
            verifiedOnline = smokeTestPanel(
                wizardUrl,
                extraSubKeys = subscriptionKeys(context, wizardUrl, PanelDeploymentStore(context).uuidFor(wizardUrl)),
            ),
        )
    }

    data class CfTokenPermissions(
        val valid: Boolean,
        val accountId: String?,
        val accountName: String?,
        val missingScopes: List<String>,
    )

    data class DeploymentResult(
        val workerName: String,
        val subdomain: String,
        val subscriptionUrl: String,
        val workerUrl: String,
        val verifiedOnline: Boolean,
        val uuid: String = "",
        val panelUrl: String = workerUrl,
        val kvBound: Boolean = false,
        /** Why the KV binding could not be attached ("" when it was). */
        val kvError: String = "",
        /** What `/api/version` answered — so a failure can be named, not guessed. */
        val health: PanelVersionProbe? = null,
        /** The panel version this APK bundles (for the app/panel skew warning). */
        val bundledVersion: String = "",
    ) {
        val panelVersion: String get() = health?.version.orEmpty()
        val scriptNeverLoaded: Boolean get() = health?.scriptNeverLoaded == true
        val panelUnreachable: Boolean get() = health == null || health.unreachable
        val versionMismatch: Boolean
            get() = bundledVersion.isNotBlank() && panelVersion.isNotBlank() && bundledVersion != panelVersion
    }

    /**
     * What the live Worker answers on `/api/version` — the single URL that tells
     * the two «Error 1101» cases apart (the README section of the same name):
     *
     *   • JSON back → the module LOADED and ran. Anything that failed afterwards
     *     is a *request* failure and the panel reports it itself (owner-only
     *     report, `/api/selfcheck?throw=1`).
     *   • HTML / 5xx / anything else → the edge answered but our code never ran:
     *     the file did not load — a truncated upload, a deploy that did not land.
     *     This is the classic 1101 with no report to show.
     *   • no answer at all → the host is not reachable from this phone (yet).
     */
    data class PanelVersionProbe(
        val online: Boolean,
        val version: String = "",
        val status: Int = 0,
        val bodyHead: String = "",
        val subOk: Boolean = false,
        /** The master key that actually served configs (UUID or subscription token). */
        val subKey: String = "",
        val detail: String = "",
    ) {
        /** The edge answered, but never our script. */
        val scriptNeverLoaded: Boolean get() = !online && status > 0
        /** Nothing answered — not even the edge page. */
        val unreachable: Boolean get() = !online && status == 0

        /**
         * The edge answered with its OWN 404 page: the host resolves, but no Worker
         * is answering on it yet. A freshly created `workers.dev` route does exactly
         * this for a while — measured on a real account (live test, 2026-10-12): the
         * route kept serving that page for 13 s in one run and for most of a minute in
         * another before the Worker took over. It is NOT the «Error 1101 that has no
         * report»: nothing is broken and nothing needs re-uploading, the route is
         * simply not live yet. Our own answers never look like this — the panel
         * always replies with JSON, so an HTML body is the edge, not us.
         */
        val routeNotLiveYet: Boolean
            get() = !online && status == 404 && bodyHead.trimStart().startsWith("<")
    }

    /** One probe of `/api/version`; never throws. */
    fun probePanelVersion(workerUrl: String, timeoutMs: Int = 8_000): PanelVersionProbe {
        if (workerUrl.isBlank()) return PanelVersionProbe(online = false, detail = "no worker url")
        return runCatching {
            val conn = (URL(workerUrl.trimEnd('/') + "/api/version").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "CatClient/1.0 (panel-probe)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val head = body.take(200).replace(Regex("\\s+"), " ").trim()
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            // JSON *and* a version: the module evaluated and its handler ran. A
            // JSON body without a version would be someone else's endpoint.
            if (code == 200 && body.trimStart().startsWith("{") && version.isNotBlank()) {
                PanelVersionProbe(online = true, version = version, status = code, bodyHead = head)
            } else {
                PanelVersionProbe(online = false, status = code, bodyHead = head)
            }
        }.getOrElse { e ->
            PanelVersionProbe(online = false, status = 0, detail = (e.message ?: "network error").take(120))
        }
    }

    /**
     * The probe, retried the way a fresh deploy needs it (workers.dev propagation
     * is not instant). Returns the LAST answer instead of a boolean, so the caller
     * can say which failure it was rather than a bare «pending». `subOk` is set
     * when the subscription also answers, i.e. the panel serves real configs.
     */
    /**
     * Every key a MASTER subscription link may carry, in the order the app should try
     * them: the stored UUID, then the panel's subscription TOKEN when the owner rotated
     * one (panel 6.67.0+).
     *
     * A rotation deliberately kills every UUID link, so a smoke test that only ever
     * fetches `/sub/<uuid>` declares a healthy panel dead and the app keeps showing a
     * stale «$workerUrl/sub/<uuid>» address. The app learns the token because the panel
     * accepts it *as the password* at /api/login, and a rotated token is always 32 hex
     * characters — any other stored credential is an owner password, not a sub key.
     */
    fun subscriptionKeys(context: Context, workerUrl: String, uuid: String): List<String> =
        subscriptionKeyCandidates(
            uuid,
            runCatching { PanelCredentials.password(context, workerUrl) }.getOrDefault(""),
        )

    /**
     * The pure half of [subscriptionKeys]: order and filtering, no Android involved, so
     * `app/src/test/.../PanelSubKeyTest.kt` runs it as real code. The subscription TOKEN that
     * replaces the master UUID in /sub/<key> is always 32 hex characters (that is what the
     * panel rotates to) — any other stored credential is an owner password and is deliberately
     * NOT tried, because guessing wrong would look like a broken panel.
     */
    internal fun subscriptionKeyCandidates(uuid: String, storedCredential: String): List<String> {
        val keys = LinkedHashSet<String>()
        uuid.trim().takeIf { it.isNotBlank() }?.let { keys += it }
        storedCredential.trim().lowercase(java.util.Locale.US)
            .takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?.let { keys += it }
        return keys.toList()
    }

    private fun waitForPanel(
        workerUrl: String,
        uuid: String = "",
        extraSubKeys: List<String> = emptyList(),
        // A fresh workers.dev route answers the edge's own 404 page for a while
        // before it serves the Worker, and the KV write that closes the panel needs
        // its own time to reach the colo answering these requests. Both were measured
        // on a real account: the route took 13 s in one run and most of a minute in
        // another, and the lock took ~66 s. The budget below therefore covers the
        // worst case with room to spare (probes at 0, 5, …, 90 s) instead of expiring
        // in the middle of a normal window and blaming the upload for it. Waiting costs
        // nothing when the panel is up: the loop returns the moment it answers.
        attempts: Int = 19,
        delayMs: Long = 5_000L,
    ): PanelVersionProbe {
        var last = PanelVersionProbe(online = false, detail = "not tried")
        // UUID first, then the rotated subscription token: after a rotation the UUID path is
        // 404 by design, and reading that as «panel broken» is what this loop used to do.
        val subKeys = (listOf(uuid) + extraSubKeys).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (i in 0 until attempts) {
            var health = probePanelVersion(workerUrl)
            if (health.online && subKeys.isNotEmpty()) {
                var served: String? = null
                for (key in subKeys) {
                    val sub = runCatching {
                        val conn = (URL(workerUrl.trimEnd('/') + "/sub/" + key + "?raw=1").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            setRequestProperty("User-Agent", "CatClient/1.0 (panel-smoke-test)")
                        }
                        val code = conn.responseCode
                        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                        code to body
                    }.getOrElse { 0 to "" }
                    if (sub.first == 200 && (sub.second.contains("vless://") || sub.second.contains("trojan://"))) {
                        served = key
                        break
                    }
                }
                if (served != null) {
                    health = health.copy(subOk = true, subKey = served)
                    return health
                }
                last = health
                if (i == attempts - 1) return last
            } else {
                last = health
                if (health.online) return health
            }
            if (i < attempts - 1) Thread.sleep(delayMs)
        }
        return last
    }

    /**
     * True when the live panel accepts this username+password at `/api/login`.
     * Used to authorize a panel update with the panel password instead of
     * asking for the Cloudflare API token again.
     */
    suspend fun verifyPanelLogin(workerUrl: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(workerUrl.trimEnd('/') + "/api/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "CatClient/${BuildConfig.VERSION_NAME} (panel-login-check)")
                }
                conn.outputStream.use { out ->
                    out.write(
                        JSONObject().put("username", username).put("password", password).toString().toByteArray(Charsets.UTF_8),
                    )
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    .orEmpty()
                conn.disconnect()
                code in 200..299 && JSONObject(body).optBoolean("ok", false)
            }.getOrDefault(false)
        }

    suspend fun verifyToken(token: String): CfTokenPermissions = withContext(Dispatchers.IO) {
        val tokenDetails = cfGet(token, "https://api.cloudflare.com/client/v4/user/tokens/verify")
        val tokenOk = tokenDetails.optBoolean("success", false)
        if (!tokenOk) return@withContext CfTokenPermissions(false, null, null,
            listOf("account:read", "workers:edit"))
        val accountsJson = cfGet(token, "https://api.cloudflare.com/client/v4/accounts?per_page=50")
        if (!accountsJson.optBoolean("success", true) && accountsJson.optJSONArray("result") == null) {
            // Token is valid but cannot list accounts (missing Account Settings: Read).
            return@withContext CfTokenPermissions(false, null, null, listOf("account_settings:read"))
        }
        val accounts = accountsJson.optJSONArray("result") ?: JSONArray()
        if (accounts.length() == 0) return@withContext CfTokenPermissions(false, null, null,
            listOf("account_access"))
        val first = accounts.getJSONObject(0)
        CfTokenPermissions(true, first.getString("id"), first.optString("name", ""), emptyList())
    }

    suspend fun deployRawSource(
        context: Context,
        token: String,
        accountId: String,
        workerName: String,
        sourceUrl: String,
        approvedSha256: String,
        panelUser: String = "",
        panelPassword: String = "",
    ): DeploymentResult {
        val downloaded = WorkerSourceDownloader.download(sourceUrl)
        when (val manifestDecision = downloaded.verifyManifest(WorkerArtifactChannel.RAW_URL, approvedSha256)) {
            is WorkerManifestDecision.Rejected -> throw WorkerSourceDownloadException(manifestDecision.reason)
            is WorkerManifestDecision.Verified -> Unit
        }
        val staticAnalysis = downloaded.staticAnalysis()
        if (!staticAnalysis.inspection.valid) {
            throw WorkerSourceDownloadException(staticAnalysis.inspection.reason ?: "source-invalid")
        }
        val plan = downloaded.planDeployment(
            target = CloudflareTargetSelection(accountId, workerName),
            approval = CloudflareOperationApproval(previewAccepted = true),
            desiredBindings = WorkerBindingSet(
                plainTextVars = mapOf(
                    "OPEN_SUB" to "false",
                    "PANEL_USER" to panelUser.ifBlank { "admin" },
                ),
                secretNames = setOf("PANEL_PASSWORD"),
            ),
        )
        if (plan is WorkerDeploymentPlanResult.Blocked) {
            throw WorkerSourceDownloadException(plan.reason)
        }
        return deployBuiltIn(
            context = context,
            token = token,
            accountId = accountId,
            workerName = workerName,
            panelUser = panelUser,
            panelPassword = panelPassword,
            workerScriptOverride = downloaded.bytes.toString(Charsets.UTF_8),
        )
    }

    suspend fun deployBuiltIn(
        context: Context,
        token: String,
        accountId: String,
        workerName: String,
        panelUser: String = "",
        panelPassword: String = "",
        workerScriptOverride: String? = null,
        isRepair: Boolean = false,
    ): DeploymentResult = withContext(Dispatchers.IO) {
        // 1. Account workers.dev subdomain: read it, create it when missing.
        val subdomain = resolveWorkersSubdomain(token, accountId)
        val workerUrl = "https://$workerName.$subdomain.workers.dev"

        // 2. Keep the UUID stable across re-deploys (it is the sub secret AND the panel password).
        val uuid = PanelDeploymentStore(context).uuidFor(workerUrl)

        // 3. KV namespace so users / clean IPs / ports survive restarts (optional: token may lack the scope).
        //    A deployment WITHOUT it looks alive but forgets everything (clean IPs
        //    «disappear», every save button seems broken) — so the failure is kept
        //    and surfaced instead of being swallowed by runCatching{}.
        val kvAttempt = runCatching { ensureKvNamespace(token, accountId, "${workerName}-catpanel") }
        val kvId = kvAttempt.getOrNull()

        // 4. Upload the worker module (multipart: metadata JSON + worker.js).
        val builtInScript = builtInWorkerScript(context)
        val script = workerScriptOverride ?: run {
            val remote = runCatching { PanelUpdate.newestPanel(context) }.getOrNull()
            val builtInVer = PanelUpdate.parseVersion(builtInScript).orEmpty()
            if (remote != null && (builtInVer.isBlank() || PanelUpdate.preferRelease(builtInVer, remote.version))) {
                remote.text
            } else {
                builtInScript
            }
        }
        val channel = when {
            workerScriptOverride != null -> WorkerArtifactChannel.RAW_URL
            isRepair -> WorkerArtifactChannel.REPAIR
            else -> WorkerArtifactChannel.APK_BUNDLE
        }
        val manifestCheck = WorkerArtifactManifest.verify(channel, script.toByteArray(Charsets.UTF_8))
        if (manifestCheck is WorkerManifestDecision.Rejected && script != MINIMAL_WORKER_SCRIPT) {
            throw RuntimeException("Worker artifact rejected: ${manifestCheck.reason}")
        }
        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
        // Admin gate bindings: with a username set, the panel login asks for
        // username + password (a management panel, not open access), and OPEN_SUB stays false.
        val defaults = WorkerBindingSet.canonicalDefaults().plainTextVars
        val effectivePanelUser = panelUser.ifBlank { defaults["PANEL_USER"] ?: "admin" }
        val effectivePanelPassword = panelPassword.ifBlank { defaults["PANEL_PASSWORD"] ?: "admin" }
        val authBindings = JSONArray().apply {
            put(JSONObject().put("type", "plain_text").put("name", "OPEN_SUB").put("text", defaults["OPEN_SUB"] ?: "false"))
            put(JSONObject().put("type", "plain_text").put("name", "PANEL_USER").put("text", effectivePanelUser))
            put(JSONObject().put("type", "secret_text").put("name", "PANEL_PASSWORD").put("text", effectivePanelPassword))
            if (token.isNotBlank()) {
                put(JSONObject().put("type", "secret_text").put("name", "CF_API_TOKEN").put("text", token))
            }
            if (accountId.isNotBlank()) {
                put(JSONObject().put("type", "plain_text").put("name", "CF_ACCOUNT_ID").put("text", accountId))
            }
            if (workerName.isNotBlank()) {
                put(JSONObject().put("type", "plain_text").put("name", "CF_WORKER_NAME").put("text", workerName))
            }
        }
        val putResult = cfUploadWorker(token, uploadUrl, script, uuid, kvId, extraBindings = authBindings)
        if (!putResult.optBoolean("success", false)) {
            val errors = putResult.optJSONArray("errors")?.toString() ?: "unknown"
            throw RuntimeException("Worker upload failed: $errors")
        }

        // 5. Make sure the workers.dev route is enabled for this script.
        runCatching {
            cfPost(
                token,
                "$uploadUrl/subdomain",
                JSONObject().put("enabled", true).put("previews_enabled", false).toString(),
            )
        }

        // 6. Smoke-test the live panel (workers.dev propagation takes a few seconds).
        //    The probe tells three cases apart, so the result dialog can say which
        //    one happened: «the route is not live yet» (the edge's own 404 page —
        //    normal for a fresh route), «the file never loaded» (any other edge error
        //    page instead of JSON — the 1101 that has no report) and «not reachable
        //    at all» (nothing answered).
        val health = waitForPanel(workerUrl, uuid, subscriptionKeys(context, workerUrl, uuid))
        val verifiedOnline = health.online && (uuid.isBlank() || health.subOk)
        val bundledVersion = PanelUpdate.parseVersion(script).orEmpty().ifBlank { bundledPanelVersion(context) }

        // 7. Stealth hardening: snapshot settings into the local recovery backup
        // and move the panel UI to a random hidden path (root then 404s neutrally).
        // The panel may require a username (PANEL_USER binding): keep it so every
        // later «send to panel» / «update panel» action can log in.
        PanelCredentials.remember(context, workerUrl, effectivePanelUser, effectivePanelPassword)
        // Do not rewrite the public entry point into a disguised personal site.
        // Existing owner-selected paths remain supported by the Worker, but fresh
        // deployments keep a transparent panel URL.
        val stealthPath = ""
        if (stealthPath.isNotBlank()) {
            PanelDeploymentStore(context).rememberLast(workerUrl, uuid, stealthPath)
        }

        DeploymentResult(
            workerName = workerName,
            subdomain = subdomain,
            workerUrl = workerUrl,
            // Prefer the key that really served configs: after a token rotation the UUID
            // link is dead, and showing it would send the user to a 404 subscription.
            subscriptionUrl = "$workerUrl/sub/" + health.subKey.ifBlank { uuid },
            verifiedOnline = verifiedOnline,
            uuid = uuid,
            panelUrl = (if (stealthPath.isNotBlank()) {
                "$workerUrl/$stealthPath" + if (panelUser.isNotBlank()) "/" else "/?p=$uuid"
            } else if (panelUser.isNotBlank()) workerUrl else "$workerUrl/?p=$uuid")
                // The panel drops ?p= into a cookie and redirects to the clean URL
                // (6.67.0), so these handshake params survive the hop and the panel
                // can warn the owner when the app and the panel disagree.
                .let { base ->
                    val q = panelHandshakeQuery(bundledVersion)
                    if (q.isBlank()) base else base + (if (base.contains('?')) "&" else "?") + q
                },
            health = health,
            bundledVersion = bundledVersion,
            kvBound = kvId != null,
            kvError = if (kvId == null) (kvAttempt.exceptionOrNull()?.message ?: "KV unavailable").take(160) else "",
        )
    }

    sealed class PanelUpdateOutcome {
        data class Success(
            val workerUrl: String,
            val uuid: String,
            val fromVersion: String,
            val toVersion: String,
            val verifiedOnline: Boolean,
            val kvBound: Boolean,
            val fromRelease: Boolean,
            /** What `/api/version` answered after the upload (6.67.0). */
            val health: PanelVersionProbe? = null,
            val bundledVersion: String = "",
        ) : PanelUpdateOutcome() {
            val panelUrl: String
                get() {
                    val base = workerUrl.trimEnd('/') + "/?p=" + uuid
                    val q = panelHandshakeQuery(bundledVersion)
                    return if (q.isBlank()) base else base + "&" + q
                }
            val panelVersion: String get() = health?.version.orEmpty()
            val scriptNeverLoaded: Boolean get() = health?.scriptNeverLoaded == true
            val versionMismatch: Boolean
                get() = bundledVersion.isNotBlank() && panelVersion.isNotBlank() && bundledVersion != panelVersion
        }

        /** The worker holds secret bindings whose values Cloudflare never returns. */
        data class Blocked(val secretNames: List<String>) : PanelUpdateOutcome()
    }

    /**
     * Update an existing Cat Panel deployment in place: re-upload the newest
     * panel source (GitHub release asset, falling back to the APK bundle) to the
     * same worker name. The UUID password, subscription links, KV store (users,
     * clean IPs, settings) and every readable binding survive untouched.
     */
    suspend fun updateBuiltIn(
        context: Context,
        token: String,
        accountId: String,
        workerUrl: String,
        fromVersion: String,
        script: PanelUpdate.PanelScript,
        secretValues: Map<String, String> = emptyMap(),
    ): PanelUpdateOutcome = withContext(Dispatchers.IO) {
        // Last line of defence — every update path funnels through here: a live panel is
        // never moved backwards (a stale source once put 5.23.13 over a working 6.x panel).
        if (PanelUpdate.isDowngrade(fromVersion, script.version)) {
            throw IllegalStateException("Refusing to downgrade the panel from $fromVersion to ${script.version}")
        }
        val updateChannel = when {
            fromVersion.isNotBlank() && fromVersion == script.version -> WorkerArtifactChannel.REPAIR
            script.fromRelease -> WorkerArtifactChannel.RELEASE_ASSET
            else -> WorkerArtifactChannel.UPDATE
        }
        val manifestCheck = WorkerArtifactManifest.verify(
            updateChannel,
            script.text.toByteArray(Charsets.UTF_8),
            script.sha256.takeIf(String::isNotBlank),
        )
        if (manifestCheck is WorkerManifestDecision.Rejected) {
            throw IllegalStateException("Refusing to upload rejected panel artifact: ${manifestCheck.reason}")
        }
        val normalized = workerUrl.trimEnd('/')
        val workerName = workerNameFromUrl(normalized)
        val uuid = PanelDeploymentStore(context).uuidFor(normalized)
        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"

        // Secrets the user added (e.g. UUID/WIZARD_PASSWORD) survive updates
        // without retyping: keep_secrets=true preserves them server-side, so we
        // simply must not send empty bindings for them (that would clear them).
        val existing = readScriptBindings(token, uploadUrl)
        val keptSecrets = existing
            ?.filter { it.optString("type") == "secret_text" && it.optString("text").isNullOrBlank() }
            ?.mapNotNull { it.optString("name").takeIf { name -> name.isNotBlank() } }
            .orEmpty()
            .filterNot { secretValues.containsKey(it) }

        // Prefer the KV namespace already bound to this worker; fall back to the
        // deterministic "<worker>-catpanel" title the deploy flow creates.
        val kvId = existing
            ?.firstOrNull {
                it.optString("type") == "kv_namespace" && it.optString("name") == "CAT_KV"
            }
            ?.optString("namespace_id")?.takeIf { it.isNotBlank() }
            ?: runCatching { ensureKvNamespace(token, accountId, "${workerName}-catpanel") }.getOrNull()

        // Carry every other readable binding through the re-upload. UUID and
        // CAT_KV are re-attached from known state above.
        val extras = JSONArray()
        existing?.forEach { binding ->
            val name = binding.optString("name")
            if (name.isBlank() || name == "UUID" || name == "CAT_KV" || name == "CF_ACCOUNT_ID" || name == "CF_WORKER_NAME" || name == "CF_API_TOKEN") return@forEach
            when (binding.optString("type")) {
                "plain_text", "secret_text" -> {
                    // Unreadable secrets ride on keep_secrets — sending an empty
                    // text here would wipe them.
                    if (name in keptSecrets) return@forEach
                    val text = secretValues[name] ?: binding.optString("text")
                    extras.put(
                        JSONObject()
                            .put("type", binding.optString("type"))
                            .put("name", name)
                            .put("text", text),
                    )
                }
                else -> extras.put(JSONObject(binding.toString()))
            }
        }
        if (token.isNotBlank()) {
            extras.put(JSONObject().put("type", "secret_text").put("name", "CF_API_TOKEN").put("text", token))
        }
        if (accountId.isNotBlank()) {
            extras.put(JSONObject().put("type", "plain_text").put("name", "CF_ACCOUNT_ID").put("text", accountId))
        }
        if (workerName.isNotBlank()) {
            extras.put(JSONObject().put("type", "plain_text").put("name", "CF_WORKER_NAME").put("text", workerName))
        }

        val rollbackBytes = runCatching { builtInWorkerScript(context).toByteArray(Charsets.UTF_8) }
            .getOrElse { script.text.toByteArray(Charsets.UTF_8) }
        val rollbackSnapshot = WorkerRollbackSnapshot(
            target = CloudflareTargetSelection(accountId, workerName),
            previousVersion = fromVersion.takeIf(String::isNotBlank),
            previousSha256 = WorkerDeploymentPlanner.sha256Hex(rollbackBytes),
            previousSourceBytes = rollbackBytes,
            bindings = WorkerBindingSet(
                kvNamespaces = if (kvId != null) mapOf("CAT_KV" to kvId) else emptyMap(),
                plainTextVars = if (uuid.isNotBlank()) mapOf("UUID" to uuid) else emptyMap(),
                secretNames = keptSecrets.toSet(),
            ),
            panelSettingsJson = runCatching { PanelBackup.read(context)?.settingsJson }.getOrNull(),
            createdAtMs = System.currentTimeMillis(),
        )
        if (rollbackSnapshot.isValidForRollback()) {
            PanelDeploymentStore(context).rememberRollbackSummary(normalized, rollbackSnapshot.describeMetadata())
        }

        val putResult = cfUploadWorker(token, uploadUrl, script.text, uuid, kvId, extraBindings = extras, keepSecrets = keptSecrets.isNotEmpty())
        if (!putResult.optBoolean("success", false)) {
            val errors = putResult.optJSONArray("errors")?.toString() ?: "unknown"
            throw RuntimeException("Worker upload failed: $errors")
        }
        runCatching {
            cfPost(
                token,
                "$uploadUrl/subdomain",
                JSONObject().put("enabled", true).put("previews_enabled", false).toString(),
            )
        }
        val health = waitForPanel(normalized, uuid, subscriptionKeys(context, normalized, uuid))
        val verifiedOnline = health.online && (uuid.isBlank() || health.subOk)
        PanelDeploymentStore(context).rememberLast(normalized, uuid)
        PanelUpdateOutcome.Success(
            workerUrl = normalized,
            uuid = uuid,
            fromVersion = fromVersion,
            toVersion = script.version,
            verifiedOnline = verifiedOnline,
            kvBound = kvId != null,
            fromRelease = script.fromRelease,
            health = health,
            bundledVersion = bundledPanelVersion(context),
        )
    }

    /** Neutral, non-branded worker name for fresh deploys (e.g. "edge-a7k2m9"). */
    fun randomWorkerName(): String = "edge-" + randomToken(5)

    /** Random 10-char hidden panel path used by stealth mode. */
    fun randomPanelPath(): String = randomToken(10)

    private fun randomToken(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }

    /**
     * Stealth hardening for deploys: log into the panel, snapshot its settings
     * into [PanelBackup] (a local-only recovery point), and move the panel UI
     * to a random hidden path so the root URL answers a neutral 404. Cloudflare
     * auto abuse-reporters and workers.dev crawlers fingerprint open proxy
     * panels on the root path — a hidden path plus a brand-free 404 keeps the
     * panel out of that dragnet. Returns the hidden path, or "" when the panel
     * did not accept the login (legacy open layout stays in place).
     */
    fun applyPanelStealth(context: Context, workerUrl: String, panelUser: String, panelPassword: String): String {
        val base = workerUrl.trimEnd('/')
        fun req(path: String, method: String, body: String?, cookie: String?): Triple<Int, String, String> {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (cookie != null) setRequestProperty("Cookie", cookie)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val setCookie = conn.headerFields?.entries
                ?.filter { it.key.equals("set-cookie", true) }
                ?.flatMap { it.value }
                ?.joinToString("; ") { it.substringBefore(';') }
                .orEmpty()
            conn.disconnect()
            return Triple(code, text, setCookie)
        }

        val loginPayload = JSONObject().put("username", panelUser).put("password", panelPassword).toString()
        val (loginCode, loginBody, cookie) = req("/api/login", "POST", loginPayload, null)
        val loginOk = loginCode == 200 && cookie.isNotBlank() &&
            runCatching { JSONObject(loginBody).optBoolean("ok") }.getOrDefault(false)
        if (!loginOk) return ""

        val (_, getBody, _) = req("/api/settings", "GET", null, cookie)
        var existing = ""
        val settingsJson = runCatching { JSONObject(getBody).optJSONObject("settings") }.getOrNull()
        if (settingsJson != null) {
            existing = settingsJson.optString("panelPath").orEmpty()
            PanelBackup.save(context, base, settingsJson.toString(), existing)
        }
        // Users too — recovery recreates them with their original tokens.
        val (_, usersBody, _) = req("/api/users", "GET", null, cookie)
        val usersArr = runCatching { JSONObject(usersBody).optJSONArray("users") }.getOrNull()
        if (usersArr != null) PanelBackup.saveUsers(context, usersArr.toString())
        val pathRegex = Regex("^[a-z0-9][a-z0-9-]{2,22}[a-z0-9]$")
        if (existing.matches(pathRegex)) return existing

        val target = randomPanelPath()
        val (putCode, _, _) = req("/api/settings", "PUT", JSONObject().put("panelPath", target).toString(), cookie)
        return if (putCode == 200) target else ""
    }

    data class PanelHealth(val state: String, val detail: String)

    /**
     * Ask the panel's /health and classify the answer. Cloudflare suspensions
     * after abuse reports answer EVERY route with the Error 1101 page and no
     * code runs at all — that is the "BLOCKED" state the recovery flow handles.
     */
    fun checkPanelHealth(workerUrl: String): PanelHealth {
        val base = workerUrl.trimEnd('/')
        return runCatching {
            val conn = (URL(base + "/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (panel-status)")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val looksHtml = body.trimStart().startsWith("<") || body.contains("<!DOCTYPE", true)
            when {
                code in 200..299 && body.contains("\"ok\"") -> PanelHealth("HEALTHY", "HTTP $code")
                // The edge's OWN 404 page: the host resolves but no Worker answers on
                // it yet. Measured on a real account right after an upload: 13 s in one
                // run, most of a minute in another. This is a wait, not a fault — and
                // sending the user into the recovery flow for it (which rebuilds the
                // panel under a new name) would have been the most expensive possible
                // wrong answer. Our own routes never answer this way: /health always
                // replies JSON, unknown paths reply JSON too.
                code == 404 && looksHtml -> PanelHealth(
                    "PENDING",
                    "HTTP 404 — the workers.dev route is not live yet (the edge's own page, not the panel)",
                )
                body.contains("Error 1101", true) || body.contains("Worker threw exception", true) ->
                    // 1101 means «the code never ran». On a Worker whose file is fine that is
                    // most often a suspension after an abuse report — but a truncated upload
                    // or a deploy that did not land end up in exactly the same place, so the
                    // dialog names the possibilities instead of asserting one (the README
                    // section keeps the same distinction). The state stays BLOCKED: it is
                    // what routes the user to the recovery flow.
                    PanelHealth(
                        "BLOCKED",
                        "Error 1101 — the code never ran: a truncated upload, a deploy that did not land, or a suspension after an abuse report",
                    )
                body.contains("cf-error-details", true) || (body.contains("Cloudflare", true) && code >= 500) ->
                    PanelHealth("BLOCKED", "HTTP $code — Cloudflare error page")
                else -> PanelHealth("DOWN", "HTTP $code")
            }
        }.getOrElse { PanelHealth("DOWN", it.message ?: "network error") }
    }

    /**
     * One route of the three-point panel test.
     *
     * WHY (the owner's 1101 question, answered without a terminal): the two things a
     * «1101» can mean are told apart by *what the worker answers*, not by the error page:
     *
     *   • JSON back → the module LOADED and RAN. A failure afterwards is a request
     *     failure, and the panel's own crash report (`panel_crash`) covers it.
     *   • HTML / «Worker threw exception» / no answer → our code never ran: a truncated
     *     upload, a deploy that did not land, or a suspension. No `try/catch` inside the
     *     file can catch that, because no code inside the file executes.
     *
     * The raw head of every answer is kept, so the verdict can be checked instead of
     * believed.
     */
    data class RouteProbe(
        val label: String,
        val path: String,
        val status: Int,
        val contentType: String,
        val bodyHead: String,
        val verdict: String,
    ) {
        /** `code-ran` · `code-never-ran` · `unreachable` · `other` */
        val codeRan: Boolean get() = verdict == "code-ran"
    }

    /**
     * The three URLs, asked in order: `/health`, `/api/version` (JSON proves the module
     * evaluated) and `/api/selfcheck?throw=1` (proves the 1101 guard works — it throws on
     * purpose and must come back as a JSON `panel_crash`).
     */
    fun probePanelRoutes(workerUrl: String, timeoutMs: Int = 8_000): List<RouteProbe> {
        val base = workerUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val origin = if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
        return listOf(
            "health" to "/health",
            "version" to "/api/version",
            "selfcheck" to "/api/selfcheck?throw=1",
        ).map { (label, path) -> runCatching { probeOneRoute(label, origin + path, timeoutMs) }
            .getOrElse { RouteProbe(label, path, 0, "", it.message.orEmpty().take(120), "unreachable") } }
    }

    private fun probeOneRoute(label: String, url: String, timeoutMs: Int): RouteProbe {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "CatClient")
            setRequestProperty("Accept", "application/json, text/html;q=0.8, */*;q=0.5")
        }
        return try {
            val code = conn.responseCode
            val type = conn.contentType.orEmpty().substringBefore(';')
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = runCatching { stream?.bufferedReader()?.use { r -> r.readText().take(4_000) } }.getOrNull().orEmpty()
            val head = body.trim().replace(Regex("\\s+"), " ").take(200)
            val lower = body.lowercase()
            val verdict = when {
                body.isBlank() -> "other"
                body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "code-ran"
                head.startsWith("<") || lower.contains("error 1101") || lower.contains("worker threw exception") ||
                    lower.contains("cf-error") -> "code-never-ran"
                else -> "other"
            }
            RouteProbe(label, url.substringAfter("://").substringAfter('/').let { "/" + it }, code, type, head, verdict)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    data class PanelRestoreResult(val settingsApplied: Boolean, val usersRestored: Int)

    /**
     * Replay a [PanelBackup.Snapshot] into a freshly deployed panel: PUT the
     * settings and recreate users with their original tokens (POST /api/users).
     * The snapshot is passed in — NOT re-read from storage — because deploying
     * the new panel has already replaced the backup with the new (empty) state.
     * panelPath stays the new worker's fresh hidden path; a masked Telegram
     * token ("••••…") is dropped instead of corrupting the field.
     */
    fun restorePanelState(
        workerUrl: String,
        settingsJson: String,
        usersJson: String,
        username: String = "",
        password: String,
    ): PanelRestoreResult {
        val base = workerUrl.trimEnd('/')
        fun call(path: String, method: String, body: String?, cookie: String?): Triple<Int, String, String> {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (cookie != null) setRequestProperty("Cookie", cookie)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val setCookie = conn.headerFields?.entries
                ?.filter { it.key.equals("set-cookie", true) }
                ?.flatMap { it.value }
                ?.joinToString("; ") { it.substringBefore(';') }
                .orEmpty()
            conn.disconnect()
            return Triple(code, text, setCookie)
        }

        val (loginCode, loginBody, cookie) = call("/api/login", "POST", JSONObject().put("username", username).put("password", password).toString(), null)
        val loginOk = loginCode == 200 && cookie.isNotBlank() &&
            runCatching { JSONObject(loginBody).optBoolean("ok") }.getOrDefault(false)
        if (!loginOk) return PanelRestoreResult(false, 0)

        var settingsOk = false
        if (settingsJson.isNotBlank()) {
            val patch = runCatching { JSONObject(settingsJson) }.getOrNull()
            if (patch != null) {
                patch.remove("panelPath") // keep the fresh stealth path from the deploy
                if (patch.optString("tgToken").contains("•")) patch.remove("tgToken")
                val (code, _, _) = call("/api/settings", "PUT", patch.toString(), cookie)
                settingsOk = code == 200
            }
        }

        var users = 0
        val arr = runCatching { JSONArray(usersJson) }.getOrNull()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i) ?: continue
                val id = u.optString("id")
                if (!id.matches(Regex("^[0-9a-fA-F-]{36}$"))) continue
                val payload = JSONObject()
                    .put("id", id)
                    .put("name", u.optString("name"))
                    .put("enabled", u.optBoolean("enabled", true))
                val expiresAt = u.optLong("expiresAt", 0L)
                if (expiresAt > 0) payload.put("expiresAt", expiresAt)
                val note = u.optString("note", "")
                if (note.isNotBlank()) payload.put("note", note)
                val (code, _, _) = call("/api/users", "POST", payload.toString(), cookie)
                if (code == 200 || code == 201 || code == 409) users++
            }
        }
        return PanelRestoreResult(settingsOk, users)
    }

    /**
     * Delete a panel deployed from this app: removes the worker script and its
     * "<worker>-catpanel" KV namespace. Subscription links die with the worker —
     * the caller asks for confirmation first.
     */
    suspend fun deletePanel(token: String, workerUrl: String): String = withContext(Dispatchers.IO) {
        val workerName = workerNameFromUrl(workerUrl.trimEnd('/'))
        val accounts = cfGet(token, "https://api.cloudflare.com/client/v4/accounts?per_page=5")
        val accountId = accounts.optJSONArray("result")?.optJSONObject(0)?.optString("id").orEmpty()
        if (!accounts.optBoolean("success", false) || accountId.isBlank()) {
            throw RuntimeException("token cannot list accounts")
        }
        val deleted = cfDelete(token, "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName")
        if (!deleted.optBoolean("success", false)) {
            throw RuntimeException("worker delete failed: " + (deleted.optJSONArray("errors")?.toString() ?: "unknown"))
        }
        var kvDeleted = 0
        runCatching {
            val namespaces = cfGet(token, "https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces?per_page=100")
            val arr = namespaces.optJSONArray("result") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val ns = arr.optJSONObject(i) ?: continue
                if (ns.optString("title") == "${workerName}-catpanel") {
                    val id = ns.optString("id")
                    if (id.isNotBlank() && cfDelete(token, "https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces/$id").optBoolean("success", false)) kvDeleted++
                }
            }
        }
        if (kvDeleted > 0) "worker + KV removed" else "worker removed"
    }

    private fun workerNameFromUrl(workerUrl: String): String {
        val host = runCatching { URI(workerUrl).host }.getOrNull()?.lowercase(Locale.US).orEmpty()
        val name = host.substringBefore('.')
        if (!name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) {
            throw RuntimeException("Not a deployable Cat Panel URL: $workerUrl")
        }
        return name
    }

    /** Current script bindings from the Cloudflare API, or null when unreadable. */
    /**
     * Names of `secret_text` bindings on a deployed worker. Cloudflare never
     * returns their values, so the update flow asks the user for each one and
     * carries them through the re-upload instead of dropping them.
     */
    suspend fun protectedSecretNames(token: String, accountId: String, workerUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalized = workerUrl.trimEnd('/')
            val uploadUrl =
                "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/${workerNameFromUrl(normalized)}"
            readScriptBindings(token, uploadUrl)
                ?.filter { it.optString("type") == "secret_text" && it.optString("text").isNullOrBlank() }
                ?.mapNotNull { it.optString("name").takeIf { name -> name.isNotBlank() } }
                .orEmpty()
        }

    private fun readScriptBindings(token: String, uploadUrl: String): List<JSONObject>? {
        val json = runCatching { cfGet(token, uploadUrl) }.getOrNull() ?: return null
        if (!json.optBoolean("success", true) && json.optJSONObject("result") == null) return null
        val bindings = json.optJSONObject("result")?.optJSONArray("bindings") ?: return null
        return buildList {
            for (index in 0 until bindings.length()) {
                bindings.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    /** Find (by title) or create the KV namespace used by the panel; returns its id. */
    private fun ensureKvNamespace(token: String, accountId: String, title: String): String {
        val base = "https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces"
        val listing = cfGet(token, "$base?per_page=100")
        val existing = listing.optJSONArray("result")
        if (existing != null) {
            for (i in 0 until existing.length()) {
                val ns = existing.getJSONObject(i)
                if (ns.optString("title") == title) return ns.getString("id")
            }
        }
        val created = cfPost(token, base, JSONObject().put("title", title).toString())
        val id = created.optJSONObject("result")?.optString("id").orEmpty()
        if (id.isBlank()) {
            throw RuntimeException("KV namespace create failed: ${created.optJSONArray("errors")}")
        }
        return id
    }

    /** GET the account subdomain; create one when the account has none yet. */
    private fun resolveWorkersSubdomain(token: String, accountId: String): String {
        val getJson = cfGet(
            token,
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain",
        )
        getJson.optJSONObject("result")?.optString("subdomain")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Not found or no read permission: create the subdomain (needs Workers Subdomain: Edit).
        val candidate = "catclient-" + randomSubdomainSuffix()
        val putJson = cfPut(
            token,
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain",
            JSONObject().put("subdomain", candidate).toString(),
        )
        putJson.optJSONObject("result")?.optString("subdomain")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val message = putJson.optJSONArray("errors")?.toString()
            ?: getJson.optJSONArray("errors")?.toString()
            ?: "unknown"
        throw RuntimeException(
            "Could not read or create the workers.dev subdomain ($message). " +
                "Use an API token with Account → Workers Subdomain → Read (or Edit).",
        )
    }

    private fun randomSubdomainSuffix(length: Int = 8): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { alphabet.random() }.joinToString("")
    }

    /** Poll health and, for Cat Panel deployments, verify the UUID subscription too. */
    private fun smokeTestPanel(
        workerUrl: String,
        uuid: String = "",
        extraSubKeys: List<String> = emptyList(),
        attempts: Int = 5,
        delayMs: Long = 2_500L,
    ): Boolean {
        for (i in 0 until attempts) {
            val ok = runCatching {
                fun get(path: String): Pair<Int, String> {
                    val conn = (URL(workerUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "CatClient/1.0 (panel-smoke-test)")
                    }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return code to body
                }
                val health = get("/health")
                if (health.first != 200 || !health.second.contains("\"ok\":true")) return@runCatching false
                if (uuid.isBlank()) true
                else {
                    // Same two keys as waitForPanel: UUID, then a rotated subscription token.
                    (listOf(uuid) + extraSubKeys).map { it.trim() }.filter { it.isNotBlank() }.distinct().any { key ->
                        val subscription = get("/sub/" + key + "?raw=1")
                        subscription.first == 200 &&
                            (subscription.second.contains("vless://") || subscription.second.contains("trojan://"))
                    }
                }
            }.getOrDefault(false)
            if (ok) return true
            if (i < attempts - 1) Thread.sleep(delayMs)
        }
        return false
    }

    private fun cfGet(token: String, url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        val code = conn.responseCode
        val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?: return JSONObject().put("success", false).put("message", "HTTP $code (empty)")
        val body = stream.bufferedReader().use { it.readText() }
        return runCatching { JSONObject(body) }
            .getOrElse { JSONObject().put("success", false).put("message", body.take(300)) }
    }

    private fun cfDelete(token: String, url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject().put("success", code in 200..299))
    }

    private fun cfPost(token: String, url: String, jsonBody: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject().put("success", code in 200..299))
    }

    private fun cfPut(token: String, url: String, jsonBody: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }
            .getOrElse { JSONObject().put("success", false).put("message", body.take(300)) }
    }

    private fun cfUploadWorker(
        token: String,
        url: String,
        script: String,
        uuid: String = "",
        kvNamespaceId: String? = null,
        secrets: Map<String, String> = emptyMap(),
        extraBindings: JSONArray = JSONArray(),
        keepSecrets: Boolean = false,
    ): JSONObject {
        val bindings = JSONArray()
        if (uuid.isNotBlank()) {
            bindings.put(JSONObject().put("type", "plain_text").put("name", "UUID").put("text", uuid))
        }
        for ((name, value) in secrets) {
            bindings.put(JSONObject().put("type", "secret_text").put("name", name).put("text", value))
        }
        if (!kvNamespaceId.isNullOrBlank()) {
            bindings.put(
                JSONObject().put("type", "kv_namespace").put("name", "CAT_KV").put("namespace_id", kvNamespaceId),
            )
        }
        for (index in 0 until extraBindings.length()) {
            extraBindings.optJSONObject(index)?.let { bindings.put(it) }
        }
        val metadata = JSONObject()
            .put("main_module", "worker.js")
            .put("bindings", bindings)
            .put("compatibility_date", "2026-09-01")
            // No compatibility flags: the Worker source uses no Node API, so nodejs_compat is not
            // needed. Kept identical to catclient.wizard.js and wrangler.jsonc
            // (scripts/panels/compat-consistency.test.mjs enforces it).
            .put("compatibility_flags", JSONArray())
        // Cloudflare never returns secret values; keep_secrets=true tells the
        // upload to preserve the previous version's secret bindings untouched —
        // so an UPDATE never has to ask the user to retype them.
        if (keepSecrets) metadata.put("keep_secrets", true)
        val boundary = "----catclient${System.currentTimeMillis()}"
        // The whole multipart body is built as BYTES first and sent with an exact
        // Content-Length. It used to be streamed through a Writer of unknown length
        // (chunked), so a short write — a dropped connection, a truncated buffer —
        // could land as a Worker whose module is half a file, and Cloudflare then
        // answers 1101 for EVERY request with no report of ours attached: exactly
        // the failure this flow produced on the workers it created (6.67.0). With a
        // declared length a partial body fails the request loudly instead.
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"metadata\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                metadata.toString() + "\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n" +
                "Content-Type: application/javascript+module\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val scriptBytes = script.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(head.size + scriptBytes.size + tail.size)
        }
        conn.outputStream.use { out ->
            out.write(head)
            out.write(scriptBytes)
            out.write(tail)
            out.flush()
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject().put("success", code in 200..299))
    }

    /** Last-resort fallback so deployment never crashes if the asset is missing. */
    private const val MINIMAL_WORKER_SCRIPT = """
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = request.headers.get('Host') || '';
    if (url.pathname.startsWith('/sub')) {
      const uuid = env.UUID || crypto.randomUUID();
      const vless = `vless://${'$'}{uuid}@${'$'}{host}:443?encryption=none&security=tls&sni=${'$'}{host}&type=ws&path=%2Fws%3Fed%3D2048&host=${'$'}{host}#Cat-Client`;
      return new Response(vless + '\n', { headers: { 'content-type': 'text/plain', 'access-control-allow-origin': '*', 'subscription-userinfo': 'upload=0; download=0; total=1099511627776' } });
    }
    return new Response('Cat Panel (minimal fallback)', { status: 200 });
  }
};
"""

}

/** Remembers the UUID per deployed worker so re-deploys never rotate the secret. */
data class PanelDeploymentRecord(
    val workerUrl: String,
    val uuid: String,
    val createdAt: Long,
    val path: String = "",
) {
    val panelUrl: String
        get() {
            val base = workerUrl.trimEnd('/')
            // Attached external panels have no stored UUID: open them plainly.
            if (uuid.isBlank()) return if (path.isBlank()) base else base + "/" + path
            return if (path.isBlank()) base + "/?p=" + uuid else base + "/" + path + "/?p=" + uuid
        }
}

class PanelDeploymentStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_panel_deploys", Context.MODE_PRIVATE)

    fun uuidFor(workerUrl: String): String {
        val normalized = workerUrl.trimEnd('/')
        val key = "uuid:" + normalized.lowercase(Locale.US)
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }

        // Older app versions only kept last_url/last_uuid. Reuse that secret when
        // upgrading, otherwise a harmless re-deploy silently invalidates every
        // existing /sub/<uuid> link and the panel password at the same worker URL.
        val legacyUrl = prefs.getString("last_url", null)?.trimEnd('/').orEmpty()
        val legacyUuid = prefs.getString("last_uuid", null).orEmpty()
        if (legacyUuid.isNotBlank() && legacyUrl.equals(normalized, ignoreCase = true)) {
            prefs.edit().putString(key, legacyUuid).apply()
            return legacyUuid
        }

        // The deployment history is another source of truth after an interrupted
        // write of the per-worker key.
        deployments().firstOrNull { it.workerUrl.equals(normalized, ignoreCase = true) }
            ?.uuid?.takeIf { it.isNotBlank() }?.let { saved ->
                prefs.edit().putString(key, saved).apply()
                return saved
            }

        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(key, fresh).apply()
        return fresh
    }

    fun rememberLast(workerUrl: String, uuid: String, path: String = "") {
        val normalized = workerUrl.trimEnd('/')
        val pathKey = "path:" + normalized.lowercase(Locale.US)
        // Blank path = don't touch it (worker self-updates must keep the hidden path).
        val effectivePath = path.ifBlank { prefs.getString(pathKey, null).orEmpty() }
        if (effectivePath.isNotBlank()) prefs.edit().putString(pathKey, effectivePath).apply()
        val history = deployments()
            .filterNot { it.workerUrl == normalized }
            .toMutableList()
        history.add(0, PanelDeploymentRecord(normalized, uuid, System.currentTimeMillis(), effectivePath))
        val encoded = JSONArray().apply {
            history.take(8).forEach { item ->
                put(JSONObject().put("url", item.workerUrl).put("uuid", item.uuid).put("createdAt", item.createdAt).put("path", item.path))
            }
        }
        prefs.edit()
            .putString("uuid:" + normalized.lowercase(Locale.US), uuid)
            .putString("last_url", normalized)
            .putString("last_uuid", uuid)
            .putString("history", encoded.toString())
            .apply()
    }

    fun deployments(): List<PanelDeploymentRecord> {
        val raw = prefs.getString("history", null).orEmpty()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trimEnd('/')
                    val uuid = item.optString("uuid")
                    if (url.isNotBlank() && uuid.isNotBlank()) {
                        add(PanelDeploymentRecord(url, uuid, item.optLong("createdAt", 0L), item.optString("path")))
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed
        // Migrate the single deployment saved by older Cat Client builds.
        val legacyUrl = prefs.getString("last_url", null)?.trimEnd('/').orEmpty()
        val legacyUuid = prefs.getString("last_uuid", null).orEmpty()
        return if (legacyUrl.isNotBlank() && legacyUuid.isNotBlank()) {
            listOf(PanelDeploymentRecord(legacyUrl, legacyUuid, 0L))
        } else {
            emptyList()
        }
    }

    fun rememberWizard(url: String) {
        prefs.edit().putString("last_wizard", url).apply()
    }

    /**
     * Cloudflare API token for a worker URL, remembered from the deploy/update
     * flow so later panel updates never ask for it again — they only confirm
     * the panel password instead.
     */
    fun rememberToken(workerUrl: String, token: String) {
        prefs.edit().putString("token:" + workerUrl.trimEnd('/').lowercase(Locale.US), token).apply()
    }

    fun tokenFor(workerUrl: String): String? =
        prefs.getString("token:" + workerUrl.trimEnd('/').lowercase(Locale.US), null)?.takeIf { it.isNotBlank() }

    fun rememberRollbackSummary(workerUrl: String, summary: String) {
        prefs.edit().putString("rollback:" + workerUrl.trimEnd('/').lowercase(Locale.US), summary).apply()
    }

    fun rollbackSummaryFor(workerUrl: String): String? =
        prefs.getString("rollback:" + workerUrl.trimEnd('/').lowercase(Locale.US), null)?.takeIf { it.isNotBlank() }

    /** Forget a deployment completely (worker deleted in the dashboard or via the API). */
    fun remove(workerUrl: String) {
        val normalized = workerUrl.trimEnd('/')
        val key = normalized.lowercase(Locale.US)
        prefs.edit()
            .remove("uuid:$key")
            .remove("path:$key")
            .remove("token:$key")
            .putString(
                "history",
                JSONArray().apply {
                    deployments()
                        .filterNot { it.workerUrl.equals(normalized, ignoreCase = true) }
                        .take(8)
                        .forEach { item ->
                            put(JSONObject().put("url", item.workerUrl).put("uuid", item.uuid).put("createdAt", item.createdAt).put("path", item.path))
                        }
                }.toString(),
            )
            .apply()
    }


    fun lastWizardUrl(): String? = prefs.getString("last_wizard", null)

    fun lastPanelUrl(): String? = prefs.getString("last_url", null)
    fun lastUuid(): String? = prefs.getString("last_uuid", null)
}
