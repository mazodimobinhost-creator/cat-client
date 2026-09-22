package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * PanelCatalog — catalog of supported self-hosted VPN panels, researched 2026-09.
 *
 * Two deployment styles:
 *  - AUTO_UPLOAD_SCRIPT: Cat Client uploads a bundled JS module to the user's
 *    Cloudflare account directly through the API (only for the built-in panel).
 *  - GUIDE: the user opens the official repository / installer and completes the
 *    deployment in the Cloudflare dashboard or on a VPS.
 */
object CloudflareWorker {

    const val REPO_URL = "https://github.com/mazodimobinhost-creator/cat-client"
    const val WORKER_ASSET_PATH = "panels/catclient.worker.js"
    const val WIZARD_ASSET_PATH = "panels/catclient.wizard.js"

    /**
     * Cloudflare "API token template" URL: opens dash.cloudflare.com with the exact
     * permissions pre-selected (Workers Scripts edit, Workers KV edit, Account Settings
     * read, User Details read). The user only taps Continue to summary → Create Token.
     * https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/
     */
    const val CF_TOKEN_TEMPLATE_URL =
        "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" +
            "%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22workers_kv_storage%22%2C%22type%22%3A%22edit%22%7D%2C" +
            "%7B%22key%22%3A%22account_settings%22%2C%22type%22%3A%22read%22%7D%2C" +
            "%7B%22key%22%3A%22user_details%22%2C%22type%22%3A%22read%22%7D%5D" +
            "&accountId=*&zoneId=all&name=Cat%20Panel"

    enum class DeployKind {
        /** We upload a JS module to Workers for this panel via API directly. */
        AUTO_UPLOAD_SCRIPT,
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
    )

    fun byId(id: String): Panel? = PANELS.firstOrNull { it.id == id }

    /** Bundled Cat Panel worker source (single file, paste-ready for the CF dashboard). */
    fun builtInWorkerScript(context: Context): String = runCatching {
        context.assets.open(WORKER_ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrDefault(MINIMAL_WORKER_SCRIPT)

    /** Bundled Cat Wizard worker source (one-click installer page for friends). */
    fun builtInWizardScript(context: Context): String? = runCatching {
        context.assets.open(WIZARD_ASSET_PATH).bufferedReader().use { it.readText() }
    }.getOrNull()

    data class WizardDeploymentResult(
        val workerName: String,
        val wizardUrl: String,
        val verifiedOnline: Boolean,
    )

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
            verifiedOnline = smokeTestPanel(wizardUrl),
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
    )

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

    suspend fun deployBuiltIn(
        context: Context,
        token: String,
        accountId: String,
        workerName: String,
    ): DeploymentResult = withContext(Dispatchers.IO) {
        // 1. Account workers.dev subdomain: read it, create it when missing.
        val subdomain = resolveWorkersSubdomain(token, accountId)
        val workerUrl = "https://$workerName.$subdomain.workers.dev"

        // 2. Keep the UUID stable across re-deploys (it is the sub secret AND the panel password).
        val uuid = PanelDeploymentStore(context).uuidFor(workerUrl)

        // 3. KV namespace so users / clean IPs / ports survive restarts (optional: token may lack the scope).
        val kvId = runCatching { ensureKvNamespace(token, accountId, "${workerName}-catpanel") }.getOrNull()

        // 4. Upload the worker module (multipart: metadata JSON + worker.js).
        val script = builtInWorkerScript(context)
        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
        val putResult = cfUploadWorker(token, uploadUrl, script, uuid, kvId)
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
        val verifiedOnline = smokeTestPanel(workerUrl)
        DeploymentResult(
            workerName = workerName,
            subdomain = subdomain,
            workerUrl = workerUrl,
            subscriptionUrl = "$workerUrl/sub/$uuid",
            verifiedOnline = verifiedOnline,
            uuid = uuid,
            panelUrl = "$workerUrl/?p=$uuid",
            kvBound = kvId != null,
        )
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

    /** Poll the deployed panel's /health endpoint; true when it answers {"ok":true}. */
    private fun smokeTestPanel(workerUrl: String, attempts: Int = 5, delayMs: Long = 2_500L): Boolean {
        for (i in 0 until attempts) {
            val ok = runCatching {
                val conn = (URL("$workerUrl/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "CatClient/1.0 (panel-smoke-test)")
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                code == 200 && body.contains("\"ok\":true")
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
        val metadata = JSONObject()
            .put("main_module", "worker.js")
            .put("bindings", bindings)
            .put("compatibility_date", "2025-03-04")
            .put("compatibility_flags", JSONArray().put("nodejs_compat"))
        val boundary = "----catclient${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            val w = OutputStreamWriter(out, "UTF-8")
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"metadata\"\r\n")
            w.write("Content-Type: application/json\r\n\r\n")
            w.write(metadata.toString() + "\r\n")
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n")
            w.write("Content-Type: application/javascript+module\r\n\r\n")
            w.write(script)
            w.write("\r\n--$boundary--\r\n")
            w.flush()
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
) {
    val panelUrl: String
        get() = workerUrl.trimEnd('/') + "/?p=" + uuid
}

class PanelDeploymentStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_panel_deploys", Context.MODE_PRIVATE)

    fun uuidFor(workerUrl: String): String {
        val key = "uuid:" + workerUrl.lowercase(Locale.US)
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(key, fresh).apply()
        return fresh
    }

    fun rememberLast(workerUrl: String, uuid: String) {
        val normalized = workerUrl.trimEnd('/')
        val history = deployments()
            .filterNot { it.workerUrl == normalized }
            .toMutableList()
        history.add(0, PanelDeploymentRecord(normalized, uuid, System.currentTimeMillis()))
        val encoded = JSONArray().apply {
            history.take(8).forEach { item ->
                put(JSONObject().put("url", item.workerUrl).put("uuid", item.uuid).put("createdAt", item.createdAt))
            }
        }
        prefs.edit()
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
                        add(PanelDeploymentRecord(url, uuid, item.optLong("createdAt", 0L)))
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

    fun lastWizardUrl(): String? = prefs.getString("last_wizard", null)

    fun lastPanelUrl(): String? = prefs.getString("last_url", null)
    fun lastUuid(): String? = prefs.getString("last_uuid", null)
}
