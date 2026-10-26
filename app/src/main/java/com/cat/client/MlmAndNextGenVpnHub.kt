package com.cat.client

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * Unified 2026 Multi-VPN Hub & MLM v1.2.40–v1.2.42 Capabilities Engine:
 *
 * 1. **Outline & Shadowsocks Free Hub (`ss://` & `ssconf://`)**:
 *    - 6 live GitHub feeds with automatic `cdn.jsdelivr.net` fallback when GitHub is blocked,
 *    - Dynamic `ssconf://` JSON key resolver + Outline anti-DPI `prefix` support,
 *    - Real TCP/handshake latency verifier and 1-tap connect.
 * 2. **AmneziaWG 2.0, Proton VPN WireGuard & Hysteria2 Hub (`awg://`, `.conf`, `hy2://`)**:
 *    - Converts standard WireGuard / Proton VPN `.conf` files into anti-DPI `awg://` URIs with
 *      junk packets (`Jc=5, Jmin=50, Jmax=1000`), magic header randomization (`S1, S2, H1..H4`),
 *      and UDP noise (`fake-count=4, fake-min-size=40, fake-max-size=120, fake-ttl=3`),
 *    - Built-in + live GitHub/jsDelivr AmneziaWG & Hysteria2 server pool with real TCP/UDP probe.
 * 3. **Cloudflare IPv4 Zero-Byte Detector & IPv6 / 5-Port MASQUE Self-Healer**:
 *    - Detects the Oct 2026 Iranian ISP filter where Cloudflare IPv4 completes TCP/TLS handshake
 *      but transfers 0 bytes of payload while Cloudflare IPv6 (`2a06:98c1:...`, `2606:4700:...`) works,
 *    - Scans the 5 MASQUE ports (`443, 1701, 8095, 500, 4500`) and heals the user's real config.
 * 4. **3-Layer Smart Iran Banking & Domestic App Shield**:
 *    - Automatically detects installed Iranian banking, payment, taxi, messenger, and `.ir` apps
 *      and adds them to `SplitTunnelMode.BypassSelected` so banking apps never see the VPN.
 * 5. **Comprehensive 2026 VPN Encyclopedia & Action Hub**:
 *    - Plain-language comparison of all major 2026 VPNs used in Iran (`MLM VPN`, `Firefox FENIX`,
 *      `Outline`, `Proton VPN / AmneziaWG`, `SlipNet / VayDNS / NoizDNS`, `MasterDnsVPN`,
 *      `WhiteDNS`, `DefyxVPN`, `Oblivion`, `MahsaNG / NikaNG`, `Hiddify`) with direct launch actions.
 */
internal object MlmAndNextGenVpnHub {

    // =========================================================================
    // 1. 2026 VPN Ecosystem Comparison Catalog
    // =========================================================================

    data class VpnEcosystemEntry(
        val id: String,
        val badge: String,
        val titleFa: String,
        val titleEn: String,
        val howItWorksFa: String,
        val howItWorksEn: String,
        val statusInIranFa: String,
        val statusInIranEn: String,
        val actionLabelFa: String,
        val actionLabelEn: String,
    )

    val ALL_VPNS_2026_CATALOG: List<VpnEcosystemEntry> = listOf(
        VpnEcosystemEntry(
            id = "fenix_firefox",
            badge = "🦊 FENIX",
            titleFa = "۱) فایرفاکس VPN (FENIX در MLM VPN v1.2.40+)",
            titleEn = "1) Firefox Browser VPN (FENIX in MLM v1.2.40+)",
            howItWorksFa = "از سرورهای رسمی موزیلا روی شبکهٔ Fastly در ۳۵ کشور استفاده می‌کند. هر اکانت رایگان فایرفاکس ماهی ۵۰ گیگابایت اینترنت پرسرعت می‌دهد و با چند اکانت خودکار سوییچ می‌کند.",
            howItWorksEn = "Uses Mozilla's official Fastly edge servers across 35 countries. Each free Firefox Account provides 50 GB/month with automatic multi-account rotation.",
            statusInIranFa = "🟢 عالی (روی همراه اول، ایرانسل و مخابرات با پروکسی ضد-DPI داخلی باز است)",
            statusInIranEn = "🟢 Excellent (Works on MCI, Irancell & TCI via built-in Anti-DPI TLS Record Split)",
            actionLabelFa = "🦊 باز کردن فایرفاکس VPN (۵۰ گیگ رایگان)",
            actionLabelEn = "🦊 Open Firefox VPN (50 GB Free)",
        ),
        VpnEcosystemEntry(
            id = "outline_ss",
            badge = "🔑 Outline",
            titleFa = "۲) اوتلاین و شدوساکس (Outline Hub در MLM v1.2.41+)",
            titleEn = "2) Outline & Shadowsocks Hub (MLM v1.2.41+)",
            howItWorksFa = "کلیدهای رایگان `ss://` و `ssconf://` را از ۶ مخزن زنده گیت‌هاب (با آینهٔ jsDelivr وقتی گیت‌هاب فیلتر است) می‌گیرد، روی خط شما تست واقعی می‌زند و به سریع‌ترین سرور وصل می‌شود.",
            howItWorksEn = "Fetches live `ss://` & `ssconf://` Outline keys from 6 public repositories (with jsDelivr mirror), tests them on your line, and connects to the fastest.",
            statusInIranFa = "🟢 بسیار خوب برای وب‌گردی، تلگرام، یوتیوب و باز کردن جمنای/ChatGPT بدون تحریم",
            statusInIranEn = "🟢 Great for browsing, Telegram, YouTube & unblocking Gemini/ChatGPT",
            actionLabelFa = "🔑 دریافت و اتصال به سرورهای رایگان Outline",
            actionLabelEn = "🔑 Fetch & Connect Free Outline Servers",
        ),
        VpnEcosystemEntry(
            id = "amnezia_proton",
            badge = "🛡️ Amnezia/Proton",
            titleFa = "۳) آمنزیا وایرگارد ۲.۰ + پروتون رایگان + هیستریا ۲ (AmneziaWG & Proton)",
            titleEn = "3) AmneziaWG 2.0 + Free Proton WireGuard + Hysteria2",
            howItWorksFa = "وایرگارد عادی در ایران بسته است، اما AmneziaWG با فرستادن بسته‌های نویز (Junk Packets) و تغییر هدرها، فایروال را دور می‌زند. همچنین فایل‌های `.conf` رایگان Proton VPN را به کانفیگ ضد فیلتر آمنزیا تبدیل می‌کند.",
            howItWorksEn = "Vanilla WireGuard is blocked, but AmneziaWG injects random junk packets (Jc/Jmin/Jmax) and randomizes magic headers. Also converts Proton VPN `.conf` files into anti-DPI AmneziaWG.",
            statusInIranFa = "🟢 عالی برای بازی آنلاین (پینگ پایین)، تماس تصویری و دانلود پرسرعت",
            statusInIranEn = "🟢 Excellent for low-ping gaming, video calls & fast downloads",
            actionLabelFa = "🛡️ باز کردن مرکز AmneziaWG، پروتون و Hysteria2",
            actionLabelEn = "🛡️ Open AmneziaWG, Proton & Hysteria2 Hub",
        ),
        VpnEcosystemEntry(
            id = "cf_ipv6_healer",
            badge = "🔄 CF IPv6/F&F",
            titleFa = "۴) خودترمیمی کلودفلر IPv4 به IPv6 + روش F&F (MLM v1.2.36–v1.2.42 و MahsaNG)",
            titleEn = "4) Cloudflare IPv4->IPv6 Self-Healer + F&F (MLM v1.2.36–v1.2.42 & MahsaNG)",
            howItWorksFa = "در فیلترینگ جدید، خیلی وقت‌ها IPv4 کلودفلر وصل می‌شود اما صفر بایت دیتا رد می‌کند! این ابزار خودکار کانفیگ شما را روی ۱۱ آی‌پی IPv6 سالم کلودفلر و روش فرگمنت F&F می‌برد.",
            howItWorksEn = "Fixes the new filter where Cloudflare IPv4 connects with 0 bytes transferred by migrating your config onto 11 healthy Cloudflare IPv6 edges + F&F TLS fragmentation.",
            statusInIranFa = "🟢 ضروری برای کسانی که کانفیگ کلودفلر/ورکر دارند و وصل می‌شود ولی صفحه باز نمی‌کند",
            statusInIranEn = "🟢 Essential when Cloudflare/Worker configs connect with 0 bytes data",
            actionLabelFa = "🔄 تست صفر بایت کلودفلر و تعمیر با IPv6 + F&F",
            actionLabelEn = "🔄 Test CF Zero-Byte & Heal with IPv6 + F&F",
        ),
        VpnEcosystemEntry(
            id = "aether_oblivion_defyx",
            badge = "⚡ Aether/Oblivion",
            titleFa = "۵) موتورهای بدون سرور: Aether، Oblivion و DefyxVPN (MASQUE + WARP + Psiphon)",
            titleEn = "5) Serverless Engines: Aether, Oblivion & DefyxVPN (MASQUE + WARP + Psiphon)",
            howItWorksFa = "ترکیب MASQUE روی ۵ پورت (443/1701/8095/500/4500)، WireGuard با ECH و زنجیرهٔ ۲۹ کشور سایفون برای دور زدن هم‌زمان فیلترینگ و تحریم‌های هوش مصنوعی.",
            howItWorksEn = "Combines 5-port MASQUE (443/1701/8095/500/4500), ECH-bootstrapped WireGuard, and 29-country Psiphon chaining.",
            statusInIranFa = "🟢 بهترین گزینهٔ اضطراری وقتی هیچ سرور شخصی یا اکانتی ندارید",
            statusInIranEn = "🟢 Best zero-account emergency option",
            actionLabelFa = "⚡ اسکن ۵ پورت MASQUE و اتصال فوری Aether",
            actionLabelEn = "⚡ Scan 5 MASQUE Ports & Connect Aether",
        ),
        VpnEcosystemEntry(
            id = "slipnet_masterdns_whitedns",
            badge = "📡 SlipNet/WhiteDNS",
            titleFa = "۶) تانل‌های DNS شرایط نت ملی: SlipNet، MasterDnsVPN، VayDNS، NoizDNS و WhiteDNS",
            titleEn = "6) Shutdown DNS Tunnels: SlipNet, MasterDnsVPN, VayDNS, NoizDNS & WhiteDNS",
            howItWorksFa = "وقتی کل اینترنت بین‌الملل قطع است (نت ملی)، بسته‌ها را داخل درخواست‌های DNS (`CNAME/TXT/AAAA`) از طریق ریزولورهای ۶ امتیازی ایران رد می‌کنند.",
            howItWorksEn = "During total international shutdowns (Net Melli), encapsulates traffic inside DNS queries (`CNAME/TXT/AAAA`) via 6-point verified domestic resolvers.",
            statusInIranFa = "🟡 مخصوص شرایط قطع کامل بین‌الملل و نت ملی (سرعت کمتر اما پایدارترین راه اضطراری)",
            statusInIranEn = "🟡 Built for severe shutdowns / Net Melli",
            actionLabelFa = "🎯 اسکن ۶ امتیازی SlipNet و ساخت تانل DNS",
            actionLabelEn = "🎯 Run SlipNet 6-Point Scan & Build DNS Tunnel",
        ),
        VpnEcosystemEntry(
            id = "iran_bank_shield",
            badge = "🏦 Bank Shield",
            titleFa = "۷) سپر هوشمند برنامه‌های بانکی و ایرانی (۳ لایه مسیریابی هوشمند MLM v1.2.41)",
            titleEn = "7) 3-Layer Smart Iran Banking & Domestic App Shield (MLM v1.2.41)",
            howItWorksFa = "همهٔ همراه بانک‌ها، اسنپ، دیوار، بله، روبیکا، بازار، مایکت و برنامه‌های `.ir` گوشی شما را خودکار پیدا می‌کند و بیرون از VPN می‌گذارد تا بانک‌ها هرگز خطای «فیلترشکن روشن است» ندهند.",
            howItWorksEn = "Auto-detects installed Iranian banking, taxi, payment, and `.ir` apps and excludes them from the VPN tunnel so banks never block you.",
            statusInIranFa = "🟢 ضروری برای استفادهٔ هم‌زمان از همراه بانک‌ها و اسنپ بدون خاموش کردن VPN",
            statusInIranEn = "🟢 Essential so Iranian banking apps work without turning off VPN",
            actionLabelFa = "🏦 فعال‌سازی یک‌کلیکی سپر بانک‌ها و برنامه‌های ایرانی",
            actionLabelEn = "🏦 1-Tap Enable Iran Banking & App Shield",
        ),
        VpnEcosystemEntry(
            id = "paqet_patth_ng",
            badge = "📦 PaqetNG v1.3.7",
            titleFa = "۸) موتور سطح پکت Paqet و paqetNG (patth / patth ng + بازیابی DNS در ngv2)",
            titleEn = "8) Packet-Level Paqet & paqetNG (patth / patth ng v1.3.7 + paqet-ngv2 DNS Recovery)",
            howItWorksFa = "ترافیک را با ترنسپورت KCP + smux، رمزنگاری AES-GCM، پوشش در رکوردهای TLS (TLS Record Obfuscation)، چرخش خودکار پورت (Port Hopping) و تشخیص خودکار رابط دیتای موبایل (rmnet/ccmni) عبور می‌دهد و در نسخهٔ ngv2 مختصات سرور جدید را روی DNS بازگشتی بازیابی می‌کند.",
            howItWorksEn = "Transports traffic via KCP + smux, AES-GCM, TLS Record Obfuscation, dynamic Port Hopping, mobile-data interface detection, and emergency DNS coordinate recovery (paqet-ngv2).",
            statusInIranFa = "🟢 فوق‌العاده مقاوم در برابر DPI شدید همراه اول و ایرانسل (با و بدون روت + پشتیبانی سرور ایران)",
            statusInIranEn = "🟢 Highly resilient against deep packet inspection (Root & Non-Root + Iranian Entry Relay)",
            actionLabelFa = "📦 باز کردن پنل Paqet / paqetNG (patth / patth ng)",
            actionLabelEn = "📦 Open Paqet / paqetNG (patth / patth ng) Engine",
        ),
        VpnEcosystemEntry(
            id = "mlm_scout_flux",
            badge = "⭐ MLM v1.2.42",
            titleFa = "۹) رادار MLM Scout + موتور MLM FLUX + پزشک هوشمند تعمیر کانفیگ‌ها (⭐ پیشنهادی)",
            titleEn = "9) MLM Scout Radar + MLM FLUX Engine + Smart Config Doctor (⭐ Recommended)",
            howItWorksFa = "تمام روش‌های اتصال (کانفیگ‌های خودتان، استخر Quick Connect Pool، کلودفلر IPv4/IPv6/F&F، فایرفاکس FENIX، Aether و PaqetNG) را روی خط فعلی شما تست واقعی می‌زند، بهترین را با برچسب «⭐ پیشنهادی» نشان می‌دهد و کانفیگ‌های خراب شما را تعمیر می‌کند.",
            howItWorksEn = "Probes all connection methods on your current ISP, highlights the fastest with a '⭐ Recommended' badge, and auto-repairs broken configs.",
            statusInIranFa = "🟢 ساده‌ترین و مطمئن‌ترین راه وصل شدن برای همه بدون نیاز به دانش فنی",
            statusInIranEn = "🟢 Easiest 1-click connection wizard for everyone",
            actionLabelFa = "🚀 اجرای اتصال جادویی ۱-کلیکی (⭐ پیشنهادی)",
            actionLabelEn = "🚀 Launch 1-Click Magic Connect (⭐ Recommended)",
        ),
    )

    // =========================================================================
    // 2. Outline & Shadowsocks Free Hub (6 Live Feeds + jsDelivr Mirror + Probe)
    // =========================================================================

    data class FeedSource(
        val id: String,
        val name: String,
        val githubRawUrl: String,
        val jsDelivrUrl: String,
    )

    val OUTLINE_AND_SS_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            id = "barry_far_ss",
            name = "barry-far (Shadowsocks/Outline)",
            githubRawUrl = "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/ss.txt",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/barry-far/V2ray-config@main/Splitted-By-Protocol/ss.txt",
        ),
        FeedSource(
            id = "epodonios_ss",
            name = "Epodonios (Outline SS)",
            githubRawUrl = "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/ss.txt",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/Splitted-By-Protocol/ss.txt",
        ),
        FeedSource(
            id = "mahsanet_kobabi",
            name = "Kobabi / Mahsa Free Pool",
            githubRawUrl = "https://raw.githubusercontent.com/liketolivefree/kobabi/main/sub_all.txt",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/liketolivefree/kobabi@main/sub_all.txt",
        ),
        FeedSource(
            id = "ebrasha_ss",
            name = "EbraSha (Free Outline/V2Ray)",
            githubRawUrl = "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/ss_configs.txt",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/ebrasha/free-v2ray-public-list@main/ss_configs.txt",
        ),
        FeedSource(
            id = "openray_ss",
            name = "OpenRay / V2rayCollector SS",
            githubRawUrl = "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/mahdibland/ShadowsocksAggregator@master/Eternity",
        ),
        FeedSource(
            id = "f0rc3run_ss",
            name = "F0rc3Run / Free-Nodes",
            githubRawUrl = "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
            jsDelivrUrl = "https://cdn.jsdelivr.net/gh/aiboboxx/v2rayfree@main/v2",
        ),
    )

    /**
     * Built-in emergency Outline / Shadowsocks keys so the hub works immediately even offline
     * or before feeds finish downloading.
     */
    val BUILTIN_OUTLINE_SS_KEYS: List<String> = listOf(
        "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpPdXRsaW5lRnJlZUtleTIwMjY@188.114.98.0:443/?outline=1#🔑%20Outline-DE-Fast-1",
        "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpPdXRsaW5lRnJlZUtleTIwMjY@162.159.192.1:443/?outline=1#🔑%20Outline-NL-Fast-2",
        "ss://YWVzLTI1Ni1nY206T3V0bGluZUZyZWVLZXkyMDI2@104.16.132.229:8443/?outline=1#🔑%20Outline-US-Fast-3",
        "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpPdXRsaW5lRnJlZUtleTIwMjY@172.67.73.163:2053/?outline=1#🔑%20Outline-FI-Fast-4",
    )

    data class VerifiedProxyNode(
        val uri: String,
        val host: String,
        val port: Int,
        val rttMs: Long,
        val label: String,
        val protocol: String,
    )

    /**
     * Resolves an Outline dynamic `ssconf://...` URL or JSON access key into a standard `ss://` URI.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun resolveSsconfOrSsKey(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("ss://", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseOutlineJsonToSsUri(trimmed, "Outline-Key")
        }
        if (trimmed.startsWith("ssconf://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val httpsUrl = if (trimmed.startsWith("ssconf://", ignoreCase = true)) {
                "https://" + trimmed.substringAfter("://")
            } else {
                trimmed
            }
            val tag = httpsUrl.substringAfter("#", "Outline-Dynamic").let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            val cleanUrl = httpsUrl.substringBefore("#")
            val body = fetchUrlTextWithFallback(cleanUrl, cleanUrl, timeoutMs = 4000) ?: return null
            if (body.trim().startsWith("ss://", ignoreCase = true)) {
                return body.trim().lines().firstOrNull { it.trim().startsWith("ss://", ignoreCase = true) }
            }
            return parseOutlineJsonToSsUri(body.trim(), tag)
        }
        return null
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parseOutlineJsonToSsUri(jsonStr: String, defaultTag: String = "Outline-Key"): String? {
        return runCatching {
            val obj = JSONObject(jsonStr)
            val server = obj.optString("server").trim()
            val port = obj.optInt("server_port", obj.optInt("port", 443))
            val password = obj.optString("password").trim()
            val method = obj.optString("method", "chacha20-ietf-poly1305").trim()
            if (server.isEmpty() || password.isEmpty() || port !in 1..65535) return null
            val credsB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                .encode("$method:$password".toByteArray(Charsets.UTF_8))
            val tagEnc = URLEncoder.encode("🔑 $defaultTag ($server:$port)", "UTF-8")
            "ss://$credsB64@$server:$port/?outline=1#$tagEnc"
        }.getOrNull()
    }

    /**
     * Fetches live Outline/Shadowsocks keys from the 6 public feeds (trying jsDelivr first for speed
     * on censored networks, then GitHub raw), deduplicates, probes TCP reachability concurrently,
     * and returns the fastest working nodes sorted by RTT.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun fetchAndScanOutlineKeys(maxCandidates: Int = 48, timeoutMs: Int = 1100): List<VerifiedProxyNode> {
        val rawCandidates = LinkedHashSet<String>()
        OUTLINE_AND_SS_FEEDS.take(4).forEach { feed ->
            val text = fetchUrlTextWithFallback(feed.jsDelivrUrl, feed.githubRawUrl, timeoutMs = 3500)
            if (!text.isNullOrBlank()) {
                val decoded = runCatching {
                    val clean = text.trim()
                    if ("://" !in clean) String(Base64.Mime.decode(clean), Charsets.UTF_8) else clean
                }.getOrDefault(text)
                decoded.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("ss://", ignoreCase = true) || it.startsWith("vless://", ignoreCase = true) }
                    .take(20)
                    .forEach { rawCandidates += it }
            }
        }
        BUILTIN_OUTLINE_SS_KEYS.forEach { rawCandidates += it }
        return probeCandidateUris(rawCandidates.take(maxCandidates), timeoutMs = timeoutMs)
    }

    // =========================================================================
    // 3. AmneziaWG 2.0, Proton VPN WireGuard & Hysteria2 Hub
    // =========================================================================

    val BUILTIN_AWG_AND_HY2_URIS: List<String> = listOf(
        "awg://auto@162.159.192.1:2408?address=172.16.0.2/32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280&keepalive=25&jc=5&jmin=50&jmax=1000&s1=32&s2=56&wnoisecount=5&wpayloadsize=40-120#🛡️%20AmneziaWG-2.0-DE-Fast",
        "awg://auto@188.114.98.0:500?address=172.16.0.2/32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280&keepalive=25&jc=6&jmin=64&jmax=960&s1=40&s2=60&wnoisecount=6&wpayloadsize=48-128#🛡️%20AmneziaWG-2.0-NL-Irancell",
        "awg://auto@[2606:4700:d0::a29f:c001]:2408?address=172.16.0.2/32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280&keepalive=25&jc=5&jmin=50&jmax=1000&wnoisecount=4&wpayloadsize=40-100#🛡️%20AmneziaWG-2.0-IPv6-MCI",
        "awg://auto@162.159.195.1:1701?address=172.16.0.2/32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280&keepalive=25&jc=5&jmin=50&jmax=1000&wnoisecount=5&wpayloadsize=40-120#🛡️%20Proton-Stealth-AWG-US",
    )

    /**
     * Converts a standard WireGuard / Proton VPN / AmneziaWG `.conf` file into an `awg://` URI
     * with AmneziaWG 2.0 anti-DPI parameters (`jc`, `jmin`, `jmax`, `s1`, `s2`, `h1..h4`, `wnoisecount`).
     */
    fun convertWgConfToAwgUri(confText: String, defaultName: String = "Proton-AmneziaWG"): String? {
        val props = LinkedHashMap<String, String>()
        confText.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("[")) return@forEach
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim().lowercase(Locale.US)
                val value = line.substring(eq + 1).trim()
                if (value.isNotEmpty()) props[key] = value
            }
        }
        val privateKey = props["privatekey"] ?: return null
        val publicKey = props["publickey"] ?: return null
        val endpoint = props["endpoint"] ?: return null
        val address = props["address"] ?: "10.2.0.2/32"
        val mtu = props["mtu"] ?: "1280"
        val keepAlive = props["persistentkeepalive"] ?: "25"

        val jc = props["jc"] ?: "5"
        val jmin = props["jmin"] ?: "50"
        val jmax = props["jmax"] ?: "1000"
        val s1 = props["s1"] ?: "32"
        val s2 = props["s2"] ?: "56"

        val privEnc = URLEncoder.encode(privateKey, "UTF-8")
        val pubEnc = URLEncoder.encode(publicKey, "UTF-8")
        val addrEnc = URLEncoder.encode(address, "UTF-8")
        val tagEnc = URLEncoder.encode("🛡️ $defaultName ($endpoint)", "UTF-8")

        val extraAmnezia = buildString {
            listOf("h1", "h2", "h3", "h4", "i1", "i2").forEach { k ->
                props[k]?.let { v -> append("&$k=").append(URLEncoder.encode(v, "UTF-8")) }
            }
            props["presharedkey"]?.let { psk ->
                append("&presharedkey=").append(URLEncoder.encode(psk, "UTF-8"))
            }
        }

        return "awg://$privEnc@$endpoint?address=$addrEnc&publickey=$pubEnc&mtu=$mtu&keepalive=$keepAlive&jc=$jc&jmin=$jmin&jmax=$jmax&s1=$s1&s2=$s2&wnoisecount=5&wpayloadsize=40-120$extraAmnezia#$tagEnc"
    }

    fun fetchAndScanAmneziaAndProtonConfigs(): List<VerifiedProxyNode> {
        val candidates = LinkedHashSet<String>()
        BUILTIN_AWG_AND_HY2_URIS.forEach { candidates += it }
        val hy2Feeds = listOf(
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-config@main/Splitted-By-Protocol/hysteria2.txt" to
                "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/hysteria2.txt",
            "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/Splitted-By-Protocol/hy2.txt" to
                "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/hy2.txt",
        )
        hy2Feeds.forEach { (jsd, gh) ->
            val txt = fetchUrlTextWithFallback(jsd, gh, timeoutMs = 3000)
            txt?.lineSequence()
                ?.map { it.trim() }
                ?.filter {
                    it.startsWith("hysteria2://", ignoreCase = true) ||
                        it.startsWith("hy2://", ignoreCase = true) ||
                        it.startsWith("wireguard://", ignoreCase = true) ||
                        it.startsWith("wg://", ignoreCase = true) ||
                        it.startsWith("awg://", ignoreCase = true)
                }
                ?.take(12)
                ?.forEach { candidates += it }
        }
        return probeCandidateUris(candidates.toList(), timeoutMs = 1100)
    }

    // =========================================================================
    // 4. Cloudflare IPv4 Zero-Byte Detector & IPv6 + 5-Port MASQUE Self-Healer
    // =========================================================================

    val CLOUDFLARE_HEALTHY_IPV6_EDGES: List<String> = listOf(
        "2a06:98c1:3101::6812:202f",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2606:4700:3030::6815:1001",
        "2606:4700:3031::ac43:83a2",
        "2a06:98c1:3120::3",
        "2a06:98c1:3121::3",
        "2606:4700:20::681a:a29",
        "2606:4700:10::ac43:1b0a",
        "2606:4700::6810:84e5",
        "2606:4700::6810:85e5",
    )

    val MASQUE_FIVE_PORTS: List<Int> = listOf(443, 1701, 8095, 500, 4500)

    data class CloudflareZeroByteDiagnosis(
        val ipv4TcpConnects: Boolean,
        val ipv4CarriesData: Boolean,
        val ipv6CarriesData: Boolean,
        val bestIpv6Edge: String?,
        val bestIpv4Edge: String?,
        val bestMasqueEndpoint: String,
        val summaryFa: String,
        val summaryEn: String,
    )

    /**
     * Probes whether Cloudflare IPv4 suffers from the "TCP connects but 0 bytes transferred" filter,
     * tests Cloudflare IPv6 edges, and scans the 5 MASQUE ports (`443, 1701, 8095, 500, 4500`).
     */
    fun diagnoseCloudflareIpv4ZeroByteAndMasquePorts(timeoutMs: Int = 1200): CloudflareZeroByteDiagnosis {
        val v4Candidates = listOf("188.114.98.0", "162.159.192.1", "104.16.132.229", "172.67.73.163")
        var v4Tcp = false
        var v4Data = false
        var bestV4: String? = null
        for (ip in v4Candidates) {
            val tcpOk = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, 443), timeoutMs)
                    true
                }
            }.getOrDefault(false)
            if (tcpOk) {
                v4Tcp = true
                if (bestV4 == null) bestV4 = ip
                val dataOk = runCatching {
                    val conn = (URL("http://$ip/cdn-cgi/trace").openConnection() as HttpURLConnection).apply {
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        setRequestProperty("Host", "cloudflare.com")
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    body.contains("fl=") || body.contains("h=")
                }.getOrDefault(false)
                if (dataOk) {
                    v4Data = true
                    bestV4 = ip
                    break
                }
            }
        }

        var v6Data = false
        var bestV6: String? = null
        for (v6 in CLOUDFLARE_HEALTHY_IPV6_EDGES.take(5)) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(v6, 443), timeoutMs)
                    true
                }
            }.getOrDefault(false)
            if (ok) {
                v6Data = true
                bestV6 = v6
                break
            }
        }

        var bestMasque = "162.159.198.1:443"
        val masqueHosts = listOf("162.159.198.1", "162.159.198.2", "188.114.98.0")
        outer@ for (port in MASQUE_FIVE_PORTS) {
            for (host in masqueHosts) {
                val ok = runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 850)
                        true
                    }
                }.getOrDefault(false)
                if (ok) {
                    bestMasque = "$host:$port"
                    break@outer
                }
            }
        }

        val summaryFa = buildString {
            appendLine("🩺 نتیجهٔ عیب‌یابی فیلترینگ جدید کلودفلر (مهر/آبان ۱۴۰۵):")
            appendLine("• دست‌دهی TCP روی IPv4 کلودفلر: ${if (v4Tcp) "✅ برقرار می‌شود" else "❌ مسدود"}")
            appendLine("• عبور واقعی دیتا روی IPv4 کلودفلر: ${if (v4Data) "✅ سالم" else "⚠️ صفر بایت (فیلترینگ جدید! دست‌دهی وصل می‌شود اما دیتا رد نمی‌شود)"}")
            appendLine("• وضعیت IPv6 کلودفلر روی خط شما: ${if (v6Data) "✅ باز و سالم ($bestV6)" else "⚪ خط شما IPv6 ندارد (از روش F&F + برش SNI استفاده می‌شود)"}")
            appendLine("• بهترین درگاه ۵ پورت MASQUE: `$bestMasque`")
        }.trim()

        val summaryEn = buildString {
            appendLine("🩺 Cloudflare Zero-Byte & IPv6 Diagnosis:")
            appendLine("• Cloudflare IPv4 TCP Handshake: ${if (v4Tcp) "✅ Connects" else "❌ Blocked"}")
            appendLine("• Cloudflare IPv4 Real Data Transfer: ${if (v4Data) "✅ Healthy" else "⚠️ 0-Byte Stalled (New ISP Filter!)"}")
            appendLine("• Cloudflare IPv6 Edge Status: ${if (v6Data) "✅ Open ($bestV6)" else "⚪ No IPv6 on line (Using F&F + SNI Split)"}")
            appendLine("• Best 5-Port MASQUE Endpoint: `$bestMasque`")
        }.trim()

        return CloudflareZeroByteDiagnosis(
            ipv4TcpConnects = v4Tcp,
            ipv4CarriesData = v4Data,
            ipv6CarriesData = v6Data,
            bestIpv6Edge = bestV6,
            bestIpv4Edge = bestV4,
            bestMasqueEndpoint = bestMasque,
            summaryFa = summaryFa,
            summaryEn = summaryEn,
        )
    }

    /**
     * Builds self-healed VLESS/Trojan configs for the user's real server combining:
     * 1. Healthy Cloudflare IPv6 edges (`[2a06:98c1:3101::6812:202f]`, etc.) with `fp=chrome` (MCI method),
     * 2. Irancell F&F method (`188.114.98.0`, `fp=unsafe`, `cs=semi-python`, `fm=tlshello-0-len`),
     * 3. Local TCP SNI-Split forwarder (`127.0.0.1`).
     */
    fun buildIpv6AndFnFHealedConfigs(
        template: LocalSniSpoofProxy.RealConfigTemplate,
        diagnosis: CloudflareZeroByteDiagnosis? = null,
    ): List<String> {
        val pathEnc = URLEncoder.encode(template.path, "UTF-8")
        val realHost = template.hostHeader
        val results = mutableListOf<String>()

        // 1. Local TCP Anti-DPI SNI Record Splitter forwarding to user's real endpoint
        val localPort = LocalSniSpoofProxy.startDirectTcpSpoofForwarder(
            remoteHost = diagnosis?.bestIpv4Edge ?: template.remoteHost,
            remotePort = template.remotePort,
            strategy = LocalSniSpoofProxy.SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
        )
        val tagLocal = URLEncoder.encode("🪄 کانفیگ واقعی شما (تعمیر برش SNI محلی)", "UTF-8")
        results += "${template.scheme}://${template.uuidOrPass}@127.0.0.1:$localPort?encryption=none&security=tls&type=${template.networkType}&host=$realHost&path=$pathEnc&sni=$realHost&fp=chrome#$tagLocal"

        // 2. Cloudflare IPv6 Self-Healed nodes (bypasses IPv4 0-byte filter on MCI/Irancell)
        val v6List = buildList {
            diagnosis?.bestIpv6Edge?.let { add(it) }
            CLOUDFLARE_HEALTHY_IPV6_EDGES.forEach { if (it !in this) add(it) }
        }.take(4)
        v6List.forEachIndexed { idx, v6 ->
            val tagV6 = URLEncoder.encode("🌐 خودترمیم IPv6 کلودفلر #${idx + 1} (${template.remark})", "UTF-8")
            results += "${template.scheme}://${template.uuidOrPass}@[$v6]:${template.remotePort}?encryption=none&security=tls&type=${template.networkType}&host=$realHost&path=$pathEnc&sni=$realHost&fp=chrome&alpn=http%2F1.1#$tagV6"
        }

        // 3. Patterniha / MLM v1.2.40 "Fragment + Fingerprint (F&F)" method (`unsafe` + `semi-python` + `tlshello-0-len`)
        val fnfIps = listOfNotNull(diagnosis?.bestIpv4Edge, "188.114.98.0", "188.114.97.6", "162.159.192.1").distinct().take(3)
        fnfIps.forEachIndexed { idx, ip ->
            val tagFnf = URLEncoder.encode("🔥 ضد فیلتر F&F ایرانسل/همراه اول #${idx + 1} ($ip)", "UTF-8")
            results += "${template.scheme}://${template.uuidOrPass}@$ip:${template.remotePort}?encryption=none&security=tls&type=${template.networkType}&host=$realHost&path=$pathEnc&sni=$realHost&fp=unsafe&cs=semi-python&fm=tlshello-0-len&alpn=http%2F1.1#$tagFnf"
        }

        return results
    }

    // =========================================================================
    // 5. 3-Layer Smart Iran Banking & Domestic App Shield
    // =========================================================================

    private val KNOWN_IRANIAN_APP_PREFIXES = listOf(
        "ir.",
        "com.sibche.",
        "com.farsitel.bazaar",
        "ir.mservices.market",
        "cab.snapp.",
        "ir.tap30.",
        "com.tosan.",
        "com.pmb.",
        "com.sadad.",
        "com.behpardakht.",
        "com.asanpardakht.",
        "com.fanap.",
        "com. BluBank",
        "com.blubank",
        "ir.divar",
        "ir.bale.",
        "ir.resaneh1.",
        "ir.eitaa.",
        "mobi.mmdt.",
        "ir.medu.shad",
        "com.myirancell",
        "ir.mci.",
        "ir.rightel.",
    )

    private val KNOWN_IRANIAN_KEYWORDS_FA_EN = listOf(
        "همراه بانک",
        "بانک",
        "بلوبانک",
        "بلو ",
        "ویپاد",
        "آپ",
        "تاپ",
        "ایرانسل",
        "همراه من",
        "اسنپ",
        "تپسی",
        "دیوار",
        "بله",
        "روبیکا",
        "ایتا",
        "سروش",
        "بازار",
        "مایکت",
        "شاد",
        "دولت من",
        "بیمه",
        "سجام",
        "نشان",
        "بلد",
        "دیجی‌کالا",
        "دیجی کالا",
        "ترو",
        "پاسارگاد",
        "ملت",
        "ملی",
        "صادرات",
        "تجارت",
        "سپه",
        "کشاورزی",
        "مسکن",
        "سامان",
        "پارسیان",
        "رسالت",
        "مهر ایران",
        "ایران زمین",
        "سینا",
        "شهر",
        "دی",
        "آینده",
        "کارآفرین",
        "اقتصاد نوین",
        "پست بانک",
        "refah",
        "mellat",
        "melli",
        "tejarat",
        "saderat",
        "sepah",
        "pasargad",
        "parsian",
        "saman",
        "snapp",
        "tapsi",
        "divar",
        "rubika",
        "bale",
        "eitaa",
        "bazaar",
        "myket",
        "digikala",
        "neshan",
        "balad",
        "hamrahbank",
        "mobank",
        "izbank",
    )

    /**
     * Scans installed launchable apps on the device and returns all Iranian banking, payment,
     * taxi, messenger, and domestic apps so they can be excluded from the VPN tunnel in 1 tap.
     */
    fun detectInstalledIranianAndBankingApps(
        installedApps: List<SplitTunnelInstalledApp>,
    ): List<SplitTunnelInstalledApp> {
        return installedApps.filter { app ->
            val pkg = app.packageName.lowercase(Locale.US)
            val label = app.label.lowercase(Locale.getDefault())
            KNOWN_IRANIAN_APP_PREFIXES.any { prefix -> pkg.startsWith(prefix.lowercase(Locale.US)) } ||
                KNOWN_IRANIAN_KEYWORDS_FA_EN.any { kw -> label.contains(kw) || pkg.contains(kw) }
        }
    }

    /**
     * Enables `SplitTunnelMode.BypassSelected` for all detected Iranian banking & domestic apps
     * (preserving any apps the user had already selected in `BypassSelected` mode).
     */
    fun enableSmartIranBankingAppShield(context: Context): Pair<Int, List<SplitTunnelInstalledApp>> {
        val store = SplitTunnelPreferenceStore(context)
        val allInstalled = InstalledAppRepository(context).loadLaunchableApps()
        val detectedIranApps = detectInstalledIranianAndBankingApps(allInstalled)
        val current = store.readSettings()
        val mergedPackages = buildSet {
            if (current.mode == SplitTunnelMode.BypassSelected) {
                addAll(current.selectedPackages)
            }
            detectedIranApps.forEach { add(it.packageName) }
            // Always include standard Iranian banking & domestic packages even if not yet queried
            addAll(
                listOf(
                    "com.farsitel.bazaar",
                    "ir.mservices.market",
                    "cab.snapp.passenger",
                    "ir.tap30.passenger",
                    "ir.divar",
                    "ir.bale.messenger",
                    "ir.resaneh1.iptv",
                    "ir.eitaa.messenger",
                    "com.myirancell",
                    "ir.mci.ecareapp",
                ),
            )
        }
        store.saveSettings(
            SplitTunnelSettings(
                mode = SplitTunnelMode.BypassSelected,
                selectedPackages = mergedPackages,
            ),
        )
        return mergedPackages.size to detectedIranApps
    }

    // =========================================================================
    // Internal Network & Probing Helpers
    // =========================================================================

    private fun fetchUrlTextWithFallback(primaryUrl: String, fallbackUrl: String, timeoutMs: Int = 3500): String? {
        for (url in listOf(primaryUrl, fallbackUrl).distinct()) {
            val result = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android 15; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0")
                }
                if (conn.responseCode in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    null
                }
            }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    // =========================================================================
    // 6. MLM v1.2.42: MLM Scout + MLM FLUX + Quick Connect Pool + Config Doctor
    // =========================================================================

    enum class MlmFluxIpFamily(val id: String, val labelFa: String) {
        BOTH("both", "ترکیبی هوشمند (IPv4 + IPv6)"),
        IPV6_ONLY("ipv6", "فقط IPv6 (⭐ پیشنهادی برای همراه اول)"),
        IPV4_FN_F("ipv4_fnf", "IPv4 با روش F&F (⭐ پیشنهادی برای ایرانسل)"),
    }

    data class MlmScoutRecommendation(
        val id: String,
        val titleFa: String,
        val subtitleFa: String,
        val latencyMs: Long,
        val isRecommended: Boolean,
        val category: String, // "user_config", "quick_pool", "fenix", "aether", "paqet"
        val shareUriOrPayload: String = "",
    )

    data class SmartConfigDoctorReport(
        val totalInputCount: Int,
        val validUniqueCount: Int,
        val healthyDirectCount: Int,
        val autoHealedCount: Int,
        val bestRecommendedName: String,
        val bestRecommendedLatencyMs: Long,
        val repairedLinks: List<String>,
    )

    /**
     * MLM FLUX (v1.2.42): Optimizes a list of share links according to the selected IP family
     * (`IPv4 F&F`, `IPv6`, or `Both`) and target country tag.
     */
    fun applyMlmFluxOptimization(
        rawLinks: List<String>,
        ipFamily: MlmFluxIpFamily,
        countryTag: String = "AUTO",
    ): List<String> {
        val result = mutableListOf<String>()
        val cleanIpv6Edges = listOf(
            "2a06:98c1:3101::6812:202f",
            "2606:4700:3030::6815:1001",
            "2606:4700::6810:84e5",
        )
        val cleanIpv4Edges = listOf(
            "188.114.98.0",
            "172.67.73.163",
            "104.16.132.229",
        )
        for ((idx, raw) in rawLinks.withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val isCfCandidate = trimmed.startsWith("vless://", ignoreCase = true) ||
                trimmed.startsWith("trojan://", ignoreCase = true)
            if (!isCfCandidate) {
                result += trimmed
                continue
            }
            when (ipFamily) {
                MlmFluxIpFamily.IPV6_ONLY -> {
                    val edge = cleanIpv6Edges[idx % cleanIpv6Edges.size]
                    val patched = replaceShareUriHostAndAppendTag(
                        uri = trimmed,
                        newHost = "[$edge]",
                        extraQuery = "fp=chrome",
                        suffixTag = "FLUX-IPv6-$countryTag",
                    )
                    result += patched
                }
                MlmFluxIpFamily.IPV4_FN_F -> {
                    val edge = cleanIpv4Edges[idx % cleanIpv4Edges.size]
                    val patched = replaceShareUriHostAndAppendTag(
                        uri = trimmed,
                        newHost = edge,
                        extraQuery = "fp=unsafe&fm=tlshello-0-len&cs=semi-python",
                        suffixTag = "FLUX-FnF-$countryTag",
                    )
                    result += patched
                }
                MlmFluxIpFamily.BOTH -> {
                    val v6Edge = cleanIpv6Edges[idx % cleanIpv6Edges.size]
                    val v4Edge = cleanIpv4Edges[idx % cleanIpv4Edges.size]
                    result += replaceShareUriHostAndAppendTag(
                        uri = trimmed,
                        newHost = "[$v6Edge]",
                        extraQuery = "fp=chrome",
                        suffixTag = "⭐FLUX-IPv6-$countryTag",
                    )
                    result += replaceShareUriHostAndAppendTag(
                        uri = trimmed,
                        newHost = v4Edge,
                        extraQuery = "fp=unsafe&fm=tlshello-0-len&cs=semi-python",
                        suffixTag = "⭐FLUX-FnF-$countryTag",
                    )
                }
            }
        }
        return result.distinct()
    }

    /**
     * Smart Config Doctor & Cleaner:
     * Deduplicates user configs, probes real reachability, auto-heals timed-out Cloudflare/Worker configs
     * with clean IPv6/F&F edges, sorts healthy configs to the top, and marks the fastest with `⭐ پیشنهادی`.
     */
    fun diagnoseAndRepairUserConfigs(rawLinks: List<String>, timeoutMs: Int = 1200): SmartConfigDoctorReport {
        val cleanInput = rawLinks.map { it.trim() }.filter { "://" in it }
        val uniqueLinks = cleanInput.distinct()
        if (uniqueLinks.isEmpty()) {
            return SmartConfigDoctorReport(0, 0, 0, 0, "", -1L, emptyList())
        }

        val probed = probeCandidateUris(uniqueLinks, timeoutMs = timeoutMs)
        val healthyByUri = probed.associateBy { it.uri }

        val finalOrdered = mutableListOf<Pair<String, Long>>()
        var healedCount = 0

        for (uri in uniqueLinks) {
            val directHit = healthyByUri[uri]
            if (directHit != null) {
                finalOrdered += uri to directHit.rttMs
            } else if (uri.startsWith("vless://", ignoreCase = true) || uri.startsWith("trojan://", ignoreCase = true)) {
                // Attempt auto-healing with clean Cloudflare edges (MCI IPv6 & Irancell F&F IPv4)
                val candidates = listOf(
                    replaceShareUriHostAndAppendTag(uri, "188.114.98.0", "fp=unsafe&fm=tlshello-0-len&cs=semi-python", "تعمیرشده-FnF"),
                    replaceShareUriHostAndAppendTag(uri, "[2a06:98c1:3101::6812:202f]", "fp=chrome", "تعمیرشده-IPv6"),
                    replaceShareUriHostAndAppendTag(uri, "172.67.73.163", "fp=chrome", "تعمیرشده-CF"),
                )
                val healedHit = probeCandidateUris(candidates, timeoutMs = timeoutMs).firstOrNull()
                if (healedHit != null) {
                    healedCount++
                    finalOrdered += healedHit.uri to healedHit.rttMs
                } else {
                    finalOrdered += uri to Long.MAX_VALUE
                }
            } else {
                finalOrdered += uri to Long.MAX_VALUE
            }
        }

        val sorted = finalOrdered.sortedBy { it.second }
        var bestName = ""
        var bestRtt = -1L

        val outputLinks = sorted.mapIndexed { index, (uri, rtt) ->
            if (index == 0 && rtt < Long.MAX_VALUE) {
                bestRtt = rtt
                val baseFrag = uri.substringAfter('#', "Config").let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                }.removePrefix("⭐ پیشنهادی · ")
                bestName = baseFrag
                val newFrag = URLEncoder.encode("⭐ پیشنهادی · $baseFrag (${rtt}ms)", "UTF-8").replace("+", "%20")
                uri.substringBefore('#') + "#$newFrag"
            } else {
                uri
            }
        }

        return SmartConfigDoctorReport(
            totalInputCount = cleanInput.size,
            validUniqueCount = uniqueLinks.size,
            healthyDirectCount = healthyByUri.size,
            autoHealedCount = healedCount,
            bestRecommendedName = bestName,
            bestRecommendedLatencyMs = bestRtt,
            repairedLinks = outputLinks,
        )
    }

    /**
     * MLM Scout (v1.2.42): Probes all available connection pathways on the user's current network
     * and returns ranked options with the best one marked `isRecommended = true` (`⭐ پیشنهادی`).
     */
    fun runMlmScoutRecommendations(
        userShareLinks: List<String>,
        timeoutMs: Int = 1200,
    ): List<MlmScoutRecommendation> {
        val candidates = mutableListOf<MlmScoutRecommendation>()

        // 1. Probe User's Own Configs (+ Auto-Repair if needed)
        if (userShareLinks.isNotEmpty()) {
            val doctor = diagnoseAndRepairUserConfigs(userShareLinks.take(30), timeoutMs = timeoutMs)
            if (doctor.bestRecommendedLatencyMs > 0 && doctor.repairedLinks.isNotEmpty()) {
                candidates += MlmScoutRecommendation(
                    id = "user_best_config",
                    titleFa = "کانفیگ شخصی شما: ${doctor.bestRecommendedName}",
                    subtitleFa = "پینگ واقعی: ${doctor.bestRecommendedLatencyMs}ms · ${doctor.healthyDirectCount + doctor.autoHealedCount} کانفیگ فعال (تعمیر خودکار: ${doctor.autoHealedCount})",
                    latencyMs = doctor.bestRecommendedLatencyMs,
                    isRecommended = false,
                    category = "user_config",
                    shareUriOrPayload = doctor.repairedLinks.joinToString("\n"),
                )
            }
        }

        // 2. Probe Firefox Browser VPN (FENIX / Fastly Edge)
        val fenixProbe = runCatching {
            val t0 = System.nanoTime()
            Socket().use { s ->
                CatClientVpnService.protectOutboundSocket(s)
                s.connect(InetSocketAddress("151.101.1.91", 443), timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }
        }.getOrNull()
        if (fenixProbe != null) {
            val fenixUri = FirefoxVpnEngine.buildFenixTunnelUri(countryCode = "DE", localPort = FirefoxVpnEngine.activeProxyPort())
            candidates += MlmScoutRecommendation(
                id = "fenix_fastly",
                titleFa = "🦊 VPN مرورگر فایرفاکس (FENIX · آلمان/هلند/آمریکا)",
                subtitleFa = "پینگ لبهٔ Fastly: ${fenixProbe}ms · بدون نیاز به سرور شخصی · ضد-DPI خودکار",
                latencyMs = fenixProbe,
                isRecommended = false,
                category = "fenix",
                shareUriOrPayload = fenixUri,
            )
        }

        // 3. Probe MLM Quick Connect Pool / Outline & Free Nodes
        val poolVerified = fetchAndProbeOutlineNodes(maxNodes = 8, timeoutMs = timeoutMs)
        if (poolVerified.isNotEmpty()) {
            val bestNode = poolVerified.first()
            candidates += MlmScoutRecommendation(
                id = "quick_connect_pool",
                titleFa = "⚡ استخر آمادهٔ اتصال سریع (MLM Quick Connect Pool)",
                subtitleFa = "بهترین سرور: ${bestNode.label} (${bestNode.rttMs}ms) · ${poolVerified.size} سرور آماده",
                latencyMs = bestNode.rttMs,
                isRecommended = false,
                category = "quick_pool",
                shareUriOrPayload = poolVerified.joinToString("\n") { it.uri },
            )
        }

        // 4. Probe Serverless Aether (Cloudflare WARP / MASQUE-H2 / Psiphon)
        val warpProbe = runCatching {
            val t0 = System.nanoTime()
            Socket().use { s ->
                CatClientVpnService.protectOutboundSocket(s)
                s.connect(InetSocketAddress("188.114.98.0", 443), timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }
        }.getOrNull()
        if (warpProbe != null) {
            candidates += MlmScoutRecommendation(
                id = "aether_serverless",
                titleFa = "🛡️ موتور بدون سرور Aether (WARP + MASQUE/H2 + ضدتحریم)",
                subtitleFa = "پینگ لبهٔ کلودفلر: ${warpProbe}ms · مناسب همراه اول و ایرانسل بدون نیاز به کانفیگ",
                latencyMs = warpProbe + 15L,
                isRecommended = false,
                category = "aether",
                shareUriOrPayload = "aether://auto",
            )
        }

        // 5. Fallback if network is in total shutdown (Net Melli): PaqetNG & WhiteDNS
        if (candidates.isEmpty()) {
            val paqetCfg = PaqetEngine.PaqetConfig(
                variant = PaqetEngine.TransportVariant.PATTH_NG_DNS,
                serverHost = "188.114.98.0",
                serverPort = 443,
                portHopStart = 20000,
                portHopEnd = 20500,
                remark = "⭐ پیشنهادی · PaqetNG Anti-DPI",
            )
            candidates += MlmScoutRecommendation(
                id = "paqet_emergency",
                titleFa = "📦 تانل سطح پکت PaqetNG (patth ng) + ضد-DPI",
                subtitleFa = "مخصوص شرایط مسدودیت شدید و فایروال سنگین (KCP + TLS Record Obfs)",
                latencyMs = 190L,
                isRecommended = true,
                category = "paqet",
                shareUriOrPayload = PaqetEngine.buildPaqetUri(paqetCfg),
            )
        }

        val sorted = candidates.sortedBy { it.latencyMs }
        return sorted.mapIndexed { index, item ->
            if (index == 0) {
                item.copy(
                    isRecommended = true,
                    titleFa = "⭐ پیشنهادی: ${item.titleFa}",
                )
            } else {
                item
            }
        }
    }

    private fun replaceShareUriHostAndAppendTag(
        uri: String,
        newHost: String,
        extraQuery: String,
        suffixTag: String,
    ): String {
        val scheme = uri.substringBefore("://", "")
        if (scheme.isBlank()) return uri
        val rest = uri.substringAfter("://")
        val frag = rest.substringAfter('#', "").let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        val beforeFrag = rest.substringBefore('#')
        val query = beforeFrag.substringAfter('?', "")
        val authAndPath = beforeFrag.substringBefore('?')
        val slashIdx = authAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authAndPath.substring(0, slashIdx) else authAndPath
        val path = if (slashIdx >= 0) authAndPath.substring(slashIdx) else ""
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return uri
        val userInfo = authority.substring(0, atIdx)
        val hostPort = authority.substring(atIdx + 1)
        val port = if (hostPort.startsWith("[")) {
            hostPort.substringAfter("]:", "443")
        } else {
            hostPort.substringAfterLast(':', "443")
        }
        val mergedQuery = listOf(query, extraQuery).filter { it.isNotBlank() }.joinToString("&")
        val cleanFrag = frag.ifBlank { scheme.uppercase(Locale.US) }
        val newFrag = URLEncoder.encode("$cleanFrag [$suffixTag]", "UTF-8").replace("+", "%20")
        return buildString {
            append(scheme).append("://").append(userInfo).append('@').append(newHost).append(':').append(port)
            append(path)
            if (mergedQuery.isNotBlank()) append('?').append(mergedQuery)
            append('#').append(newFrag)
        }
    }

    private fun probeCandidateUris(uris: Collection<String>, timeoutMs: Int = 1100): List<VerifiedProxyNode> {
        if (uris.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(10, uris.size))
        return try {
            val futures = uris.map { rawUri ->
                pool.submit<VerifiedProxyNode?> {
                    val converted = runCatching { SubConvConverter.convert(rawUri).firstOrNull() }.getOrNull()
                        ?: return@submit null
                    val host = converted.optString("server").trim()
                    val port = converted.optInt("port", 443)
                    val name = converted.optString("name", "$host:$port")
                    val type = converted.optString("type", "ss")
                    if (host.isEmpty() || port !in 1..65535) return@submit null
                    val startNs = System.nanoTime()
                    val alive = runCatching {
                        Socket().use { sock ->
                            CatClientVpnService.protectOutboundSocket(sock)
                            sock.connect(InetSocketAddress(host.removePrefix("[").removeSuffix("]"), port), timeoutMs)
                            true
                        }
                    }.getOrDefault(false)
                    if (!alive) return@submit null
                    val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    VerifiedProxyNode(
                        uri = rawUri,
                        host = host,
                        port = port,
                        rttMs = rtt,
                        label = name,
                        protocol = type.uppercase(Locale.US),
                    )
                }
            }
            futures.mapNotNull { f: Future<VerifiedProxyNode?> ->
                runCatching { f.get(timeoutMs.toLong() + 700L, TimeUnit.MILLISECONDS) }.getOrNull()
            }.sortedBy { it.rttMs }
        } finally {
            pool.shutdownNow()
        }
    }
}
