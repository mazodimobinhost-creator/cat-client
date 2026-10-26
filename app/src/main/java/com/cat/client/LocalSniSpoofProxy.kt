package com.cat.client

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 2026 Anti-DPI SNI Spoofing, Non-Cloudflare National Intranet (Net Melli) Whitelist Scanner,
 * Fact-A DNS Non-Hijack Oracle (`XTLS/BBS#10`), and Hybrid Tunnel Config Generator
 * (`patterniha/QS-Tunnel`, `QQ-Tunnel`, `ParsaKSH/spoof-tunnel`, `Fedarisha/S3-Bypass`,
 * `XTLS/Xray-core#5964/#5969/#6246`, `SamNet-dev/snix`, `MetaCubeX/mihomo#2604`).
 *
 * Strictly preserves verified `IP:port` pairs: never rewrites ports or fabricates port 80/443.
 */
internal object LocalSniSpoofProxy {

    enum class SpoofStrategy(
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val descriptionFa: String,
        val descriptionEn: String,
    ) {
        TLS_RECORD_SPLIT_AT_SNI(
            id = "tls-split-sni",
            titleFa = "۱) برش دقیق رکورد TLS در مرز SNI + تأخیر تصادفی (snix / بدون روت)",
            titleEn = "1) Exact TLS Record Split @ SNI Extension + Jitter (snix / Non-Root)",
            descriptionFa = "رکورد ClientHello را دقیقاً در وسط اکستنشن 0x0000 (نام دامنه SNI) به دو رکورد معتبر TLS تقسیم و با مکث ۲ میلی‌ثانیه ارسال می‌کند تا DPI نتواند نام دامنه را بازسازی کند.",
            descriptionEn = "Splits the TLS ClientHello record right inside extension 0x0000 (SNI) into two valid TLS records with a 1-3ms jitter.",
        ),
        LOW_TTL_DECOY(
            id = "low-ttl-decoy",
            titleFa = "۲) تزریق Fake ClientHello با SNI سفید پیش از هندشیک اصلی (Desync Decoy)",
            titleEn = "2) Fake ClientHello Decoy Injection with Whitelisted SNI",
            descriptionFa = "ارسال یک رکورد ClientHello فریبنده حاوی SNI وایت‌لیست (مانند ebanksepah.ir یا speed.cloudflare.com) و سپس ارسال ClientHello اصلی در سگمنت جداگانه.",
            descriptionEn = "Sends a decoy ClientHello carrying a whitelisted SNI before flushing the real fragmented ClientHello.",
        ),
        WRONG_TIMESTAMP_PAWS(
            id = "wrong-timestamp",
            titleFa = "۳) اسپوف TCP Timestamp / Sequence + فرگمنت دو مرحله‌ای (Xray-core #5964)",
            titleEn = "3) TCP Timestamp / Sequence Desync + Two-Stage Fragment (Xray #5964)",
            descriptionFa = "برش دو مرحله‌ای ClientHello در بایت‌های ۶ و ۱۰۴ به همراه پروفایل زمان‌بندی ضد-DPI که فایروال میانی را فریب داده و توسط سرور مقصد بازسازی می‌شود.",
            descriptionEn = "Two-stage ClientHello split at bytes 6 and 104 paired with anti-DPI timing profile.",
        ),
        FINALMASK_HEADER_CUSTOM(
            id = "finalmask-custom",
            titleFa = "۴) ماسک هدر سفارشی و فرگمنت tlshello 6,98,1 (Mihomo #2604 / FinalMask)",
            titleEn = "4) FinalMask Custom Header & tlshello 6,98,1 Fragment (Mihomo #2604)",
            descriptionFa = "شکستن هدر اولیه TLS در بازه 6 تا 98 بایت با فاصله زمانی 1ms مخصوص دور زدن فایروال ایرانسل و همراه اول.",
            descriptionEn = "Splits initial TLS handshake header across 6-98 byte windows with 1ms delay for Irancell/MCI DPI.",
        ),
        MITM_PINNED_CERT_FRONTING(
            id = "mitm-pinned-fronting",
            titleFa = "۵) فرانتینگ گواهی با SNI سفید + تایید از MITM (Xray #6246 / fromMitm)",
            titleEn = "5) Certificate-Pinned Domain Fronting (Xray #6246 / fromMitm)",
            descriptionFa = "قرار دادن دامنه وایت‌لیست در SNI و تایید امن گواهی سرور اصلی با verifyPeerCertByName=fromMitm و pinnedPeerCertSha256 بدون نیاز به allowInsecure.",
            descriptionEn = "Uses a whitelisted SNI on the wire while verifying the real server certificate via fromMitm / pinned SHA-256.",
        ),
    }

    enum class NonCfCdnProvider(
        val id: String,
        val titleFa: String,
        val titleEn: String,
    ) {
        AKAMAI("akamai", "🟦 آکامای (Akamai Edge — بدون کلادفلر)", "🟦 Akamai Edge (Non-Cloudflare)"),
        FASTLY_GITHUB("fastly", "🟪 فستلی و گیت‌هاب (Fastly / GitHub CDN)", "🟪 Fastly & GitHub CDN"),
        GOOGLE_CLOUD("google", "🟩 گوگل کلود و API (Google Edge / GAS)", "🟩 Google Cloud & APIs Edge"),
        AZURE_CLOUDFRONT_GCORE("azure_cf_gcore", "🟨 مایکروسافت آژور / کلودفرانت / Gcore", "🟨 Azure / CloudFront / Gcore"),
        IRAN_DOMESTIC_BRIDGE("iran_domestic", "🇮🇷 شبکه داخلی ایران (آروان / دراک / شاتل — پل نت ملی)", "🇮🇷 Iran Domestic CDN & Datacenters (Arvan / Derak)"),
    }

    data class WhitelistTargetEndpoint(
        val provider: NonCfCdnProvider,
        val ip: String,
        val port: Int,
        val sni: String,
        val hostHeader: String,
        val noteFa: String,
        val noteEn: String,
    ) {
        /** Exact `IP:port` or `[IPv6]:port` — port is never altered. */
        val exactEndpoint: String
            get() = if (ip.startsWith("[")) "$ip:$port"
            else if (ip.contains(":")) "[$ip]:$port"
            else "$ip:$port"
    }

    data class WhitelistScanHit(
        val target: WhitelistTargetEndpoint,
        val tcpRttMs: Long,
        val tlsRttMs: Long?,
        val tlsVerified: Boolean,
    ) {
        val exactEndpoint: String get() = target.exactEndpoint
        val effectiveRttMs: Long get() = tlsRttMs ?: tcpRttMs
    }

    data class CloudflareSpoofMethodSpec(
        val index: Int,
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val summaryFa: String,
        val summaryEn: String,
        val sampleEndpoint: String,
        val sampleSni: String,
        val queryParams: String,
    )

    data class FactADnsOracleHit(
        val domain: String,
        val resolvedIp: String,
        val dnsRttMs: Long,
        val tlsPort443Open: Boolean,
        val tlsRttMs: Long?,
        val poisoned: Boolean,
    )

    /**
     * Curated 2026 catalog of Non-Cloudflare CDN edges & Iran domestic whitelist endpoints
     * that remain reachable when Cloudflare AS13335 is throttled or blocked during Net Melli.
     */
    val NON_CF_WHITELIST_TARGETS: List<WhitelistTargetEndpoint> = listOf(
        // 1. Akamai Edge
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AKAMAI,
            ip = "23.215.0.206",
            port = 443,
            sni = "a248.e.akamai.net",
            hostHeader = "www.apple.com",
            noteFa = "لبه آکامای (پشتیبانی از HTTP/2 و TLS 1.3 — باز در زمان قطع کلادفلر)",
            noteEn = "Akamai Edge (HTTP/2 & TLS 1.3 — open when Cloudflare is blocked)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AKAMAI,
            ip = "23.212.250.91",
            port = 443,
            sni = "ds-aksb.akamaized.net",
            hostHeader = "www.adobe.com",
            noteFa = "لبه آکامای اروپا (مناسب فرانتینگ و تانل WS/gRPC)",
            noteEn = "Akamai EU Edge (suitable for Domain Fronting & WS/gRPC)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AKAMAI,
            ip = "92.123.102.43",
            port = 443,
            sni = "www.apple.com",
            hostHeader = "www.apple.com",
            noteFa = "لبه آکامای فرانکفورت با SNI اپل",
            noteEn = "Akamai Frankfurt Edge with Apple SNI",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AKAMAI,
            ip = "2.22.250.149",
            port = 443,
            sni = "www.adobe.com",
            hostHeader = "www.adobe.com",
            noteFa = "لبه آکامای با SNI ادوبی (باز روی خطوط ثابت و موبایل)",
            noteEn = "Akamai Edge with Adobe SNI",
        ),
        // 2. Fastly & GitHub
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.FASTLY_GITHUB,
            ip = "151.101.1.195",
            port = 443,
            sni = "pypi.org",
            hostHeader = "pypi.org",
            noteFa = "فستلی Anycast (وایت‌لیست توسعه‌دهندگان PyPI / پایتون)",
            noteEn = "Fastly Anycast (Developer Whitelist PyPI / Python)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.FASTLY_GITHUB,
            ip = "151.101.65.195",
            port = 443,
            sni = "fastly.jsdelivr.net",
            hostHeader = "fastly.jsdelivr.net",
            noteFa = "فستلی jsDelivr (باز برای لود کتابخانه‌های وب)",
            noteEn = "Fastly jsDelivr CDN (open for web assets)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.FASTLY_GITHUB,
            ip = "140.82.121.6",
            port = 443,
            sni = "github.githubassets.com",
            hostHeader = "github.com",
            noteFa = "لبه گیت‌هاب (باز برای مخازن و استاتیک‌های گیت‌هاب)",
            noteEn = "GitHub Edge (open for repositories & static assets)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.FASTLY_GITHUB,
            ip = "146.75.32.195",
            port = 443,
            sni = "www.python.org",
            hostHeader = "www.python.org",
            noteFa = "لبه فستلی نسل جدید (146.75.0.0/16)",
            noteEn = "Fastly Next-Gen Edge (146.75.0.0/16)",
        ),
        // 3. Google Cloud & APIs
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.GOOGLE_CLOUD,
            ip = "34.143.72.2",
            port = 443,
            sni = "www.googleapis.com",
            hostHeader = "script.google.com",
            noteFa = "لبه گوگل کلود (مناسب تانل GooseRelayVPN / FlowDriver / GAS)",
            noteEn = "Google Cloud Edge (for GooseRelayVPN / FlowDriver / GAS relay)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.GOOGLE_CLOUD,
            ip = "142.250.185.206",
            port = 443,
            sni = "www.google.com",
            hostHeader = "script.google.com",
            noteFa = "فرانتینگ گوگل (SNI=www.google.com + Host=script.google.com)",
            noteEn = "Google Domain Fronting (SNI=www.google.com + Host=script.google.com)",
        ),
        // 4. Azure / CloudFront / Gcore
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AZURE_CLOUDFRONT_GCORE,
            ip = "204.79.197.200",
            port = 443,
            sni = "www.microsoft.com",
            hostHeader = "ajax.aspnetcdn.com",
            noteFa = "لبه مایکروسافت آژور (مناسب REALITY / ShadowTLS / ECH Fronting)",
            noteEn = "Microsoft Azure Edge (for REALITY / ShadowTLS / ECH Fronting)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AZURE_CLOUDFRONT_GCORE,
            ip = "13.224.163.50",
            port = 443,
            sni = "d1.cloudfront.net",
            hostHeader = "d1.cloudfront.net",
            noteFa = "آمازون کلودفرانت (AWS CloudFront Anycast)",
            noteEn = "Amazon CloudFront Anycast Edge",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.AZURE_CLOUDFRONT_GCORE,
            ip = "92.223.84.84",
            port = 443,
            sni = "gcore.com",
            hostHeader = "gcore.com",
            noteFa = "شبکه Gcore CDN (جایگزین اروپایی کلادفلر)",
            noteEn = "Gcore CDN (European alternative to Cloudflare)",
        ),
        // 5. Iran Domestic Bridge & CDN
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.IRAN_DOMESTIC_BRIDGE,
            ip = "185.143.232.1",
            port = 443,
            sni = "edge01.82.ir.cdn.ir",
            hostHeader = "edge01.82.ir.cdn.ir",
            noteFa = "لبه آروان‌کلود ایران (تست زنده‌بودن شبکه ملی و پل ریورس)",
            noteEn = "ArvanCloud Domestic Edge (Net Melli reachability & reverse bridge)",
        ),
        WhitelistTargetEndpoint(
            provider = NonCfCdnProvider.IRAN_DOMESTIC_BRIDGE,
            ip = "185.239.104.1",
            port = 443,
            sni = "derak.cloud",
            hostHeader = "derak.cloud",
            noteFa = "لبه ابر دراک ایران (پل داخلی نت ملی)",
            noteEn = "Derak Cloud Domestic Edge (Net Melli internal bridge)",
        ),
    )

    /**
     * Whitelisted Iranian & Global SNI decoys used for Fake ClientHello injection,
     * REALITY dest/serverNames, and Fact-A DNS Non-Hijack probing.
     */
    val WHITELIST_SNI_CANDIDATES: List<String> = listOf(
        "ebanksepah.ir",
        "my.bmi.ir",
        "shaparak.ir",
        "aparat.com",
        "digikala.com",
        "snapp.ir",
        "divar.ir",
        "torob.com",
        "cafebazaar.ir",
        "varzesh3.com",
        "bale.ai",
        "rubika.ir",
        "eitaa.com",
        "virgool.io",
        "www.microsoft.com",
        "pypi.org",
        "a248.e.akamai.net",
        "www.googleapis.com",
        "speed.cloudflare.com",
        "radar.cloudflare.com",
    )

    /**
     * All 5 Cloudflare Spoofing & Fronting methods kept side-by-side with the Non-Cloudflare
     * Net Melli solutions, as explicitly requested by the user.
     */
    val CF_SPOOF_5_METHODS: List<CloudflareSpoofMethodSpec> = listOf(
        CloudflareSpoofMethodSpec(
            index = 1,
            id = "cf-host-sni-decouple",
            titleFa = "روش ۱ کلادفلر: جداسازی SNI سفید از Host ورکر (Domain Fronting)",
            titleEn = "CF Method 1: Whitelisted SNI Decoupled from Worker Host",
            summaryFa = "قرار دادن speed.cloudflare.com یا radar.cloudflare.com در SNI هندشیک TLS در حالی که هدر Host داخل WebSocket/HTTP2 آدرس ورکر شماست.",
            summaryEn = "Places speed.cloudflare.com or radar.cloudflare.com in TLS SNI while keeping your Worker hostname in the inner Host header.",
            sampleEndpoint = "188.114.98.0:443",
            sampleSni = "speed.cloudflare.com",
            queryParams = "security=tls&sni=speed.cloudflare.com&fp=chrome&alpn=http%2F1.1",
        ),
        CloudflareSpoofMethodSpec(
            index = 2,
            id = "cf-ech-from-mitm",
            titleFa = "روش ۲ کلادفلر: رمزنگاری SNI با ECH + تایید گواهی از MITM (Xray #6246)",
            titleEn = "CF Method 2: Encrypted ClientHello (ECH) + fromMitm Verification (Xray #6246)",
            summaryFa = "واکشی کلید ECH از 1.1.1.1:443 با SNI پوششی www.microsoft.com و verifyPeerCertByName=fromMitm تا DPI فقط نام دامنه بیرونی کلادفلر را ببیند.",
            summaryEn = "Fetches ECH config via 1.1.1.1:443 using serverName=www.microsoft.com and verifyPeerCertByName=fromMitm so DPI only sees the outer name.",
            sampleEndpoint = "104.16.132.229:443",
            sampleSni = "cloudflare-ech.com",
            queryParams = "security=tls&sni=cloudflare-ech.com&ech=cloudflare-ech.com%2Bhttps%3A%2F%2F1.1.1.1%2Fdns-query&vcn=fromMitm%2Cwww.microsoft.com&fp=chrome",
        ),
        CloudflareSpoofMethodSpec(
            index = 3,
            id = "cf-fake-ch-ff",
            titleFa = "روش ۳ کلادفلر: تزریق Fake ClientHello + متد F&F ایرانسل و IPv6 همراه اول",
            titleEn = "CF Method 3: Fake ClientHello Injection + Irancell F&F / MCI IPv6",
            summaryFa = "ترکیب finalMask=tlshello-0-len، cipherSuites=semi-python و fingerprint=unsafe روی 188.114.98.0:443 (ایرانسل) و [2a06:98c1:3101::6812:202f]:443 (همراه اول).",
            summaryEn = "Combines finalMask=tlshello-0-len, cipherSuites=semi-python and fingerprint=unsafe on 188.114.98.0:443 (Irancell) & [2a06:98c1:3101::6812:202f]:443 (MCI).",
            sampleEndpoint = "188.114.98.0:443",
            sampleSni = "www.cloudflare.com",
            queryParams = "security=tls&sni=www.cloudflare.com&fp=unsafe&alpn=http%2F1.1&cs=semi-python&fm=tlshello-0-len",
        ),
        CloudflareSpoofMethodSpec(
            index = 4,
            id = "cf-6ports-masque-sni",
            titleFa = "روش ۴ کلادفلر: ۶ پورت TLS جایگزین (2053/2083/2087/2096/8443) + اسپوف MASQUE SNI",
            titleEn = "CF Method 4: 6 Alternative TLS Ports + Aether MASQUE SNI Spoofing (#147)",
            summaryFa = "استفاده از پورت‌های غیر ۴۴۳ کلادفلر با حفظ دقیق پورت تاییدشده + تغییر SNI هندشیک MASQUE به www.cloudflare.com در Aether.",
            summaryEn = "Uses verified non-443 Cloudflare TLS ports + overrides MASQUE handshake SNI to www.cloudflare.com in Aether.",
            sampleEndpoint = "104.16.132.229:2053",
            sampleSni = "www.cloudflare.com",
            queryParams = "security=tls&sni=www.cloudflare.com&fp=chrome&alpn=h2%2Chttp%2F1.1",
        ),
        CloudflareSpoofMethodSpec(
            index = 5,
            id = "cf-pinned-cert-mitm-fronting",
            titleFa = "روش ۵ کلادفلر: فرانتینگ با پین هش گواهی (pcs + vcn=fromMitm بدون allowInsecure)",
            titleEn = "CF Method 5: Certificate-Pinned Fronting (pcs + vcn=fromMitm without allowInsecure)",
            summaryFa = "قرار دادن هر دامنه سفید در فیلد sni و تایید اصالت گواهی سرور با pinnedPeerCertSha256 (pcs) و verifyPeerCertByName (vcn).",
            summaryEn = "Uses any whitelisted domain in the sni field while authenticating the server certificate via pinnedPeerCertSha256 (pcs) and verifyPeerCertByName (vcn).",
            sampleEndpoint = "[2a06:98c1:3101::6812:202f]:443",
            sampleSni = "ebanksepah.ir",
            queryParams = "security=tls&sni=ebanksepah.ir&vcn=fromMitm%2Cwww.cloudflare.com&fp=chrome",
        ),
    )

    /**
     * Builds a syntactically valid TLS 1.2/1.3 ClientHello record carrying [decoySni]
     * inside extension `0x0000` (server_name). Used by [SpoofStrategy.LOW_TTL_DECOY]
     * and unit-tested for exact byte offsets.
     */
    fun buildFakeClientHelloRecord(decoySni: String = "ebanksepah.ir"): ByteArray {
        val cleanHost = decoySni.trim().lowercase().ifBlank { "ebanksepah.ir" }
        val hostBytes = cleanHost.toByteArray(Charsets.US_ASCII)

        // Extension 0x0000 (server_name)
        val sniExtBody = ByteArrayOutputStream().apply {
            val listLen = hostBytes.size + 3
            write((listLen ushr 8) and 0xFF)
            write(listLen and 0xFF)
            write(0x00) // host_name type (0)
            write((hostBytes.size ushr 8) and 0xFF)
            write(hostBytes.size and 0xFF)
            write(hostBytes)
        }.toByteArray()

        val extensions = ByteArrayOutputStream().apply {
            // ext type 0x0000
            write(0x00)
            write(0x00)
            write((sniExtBody.size ushr 8) and 0xFF)
            write(sniExtBody.size and 0xFF)
            write(sniExtBody)
            // ext type 0x002b (supported_versions: TLS 1.3 + TLS 1.2)
            val sv = byteArrayOf(0x00, 0x2b, 0x00, 0x05, 0x04, 0x03, 0x04, 0x03, 0x03)
            write(sv)
        }.toByteArray()

        val clientHelloBody = ByteArrayOutputStream().apply {
            // client_version = TLS 1.2 (0x0303)
            write(0x03)
            write(0x03)
            // 32-byte deterministic pseudo-random
            for (i in 0 until 32) {
                write((0xA5 xor (i * 7) xor (hostBytes[i % hostBytes.size].toInt() and 0xFF)) and 0xFF)
            }
            // session_id length = 0
            write(0x00)
            // cipher_suites length = 4 (TLS_AES_128_GCM_SHA256 0x1301, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 0xC02F)
            write(0x00)
            write(0x04)
            write(0x13)
            write(0x01)
            write(0xC0)
            write(0x2F)
            // compression_methods length = 1 (null = 0x00)
            write(0x01)
            write(0x00)
            // extensions length
            write((extensions.size ushr 8) and 0xFF)
            write(extensions.size and 0xFF)
            write(extensions)
        }.toByteArray()

        val handshakeMsg = ByteArrayOutputStream().apply {
            write(0x01) // HandshakeType = client_hello (1)
            write((clientHelloBody.size ushr 16) and 0xFF)
            write((clientHelloBody.size ushr 8) and 0xFF)
            write(clientHelloBody.size and 0xFF)
            write(clientHelloBody)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16) // ContentType = Handshake (22)
            write(0x03) // Legacy Record Version = TLS 1.0 (0x0301)
            write(0x01)
            write((handshakeMsg.size ushr 8) and 0xFF)
            write(handshakeMsg.size and 0xFF)
            write(handshakeMsg)
        }.toByteArray()
    }

    /**
     * Locates the exact byte offset of the ASCII hostname inside the TLS `server_name` (`0x0000`)
     * extension of a TLS ClientHello record (`record[0] == 0x16`), or returns `-1` if not present.
     */
    fun findSniHostnameOffset(record: ByteArray): Int {
        if (record.size < 48 || (record[0].toInt() and 0xFF) != 0x16) return -1
        if ((record[5].toInt() and 0xFF) != 0x01) return -1 // must be ClientHello (0x01)
        var pos = 5 + 4 + 2 + 32 // record hdr (5) + handshake hdr (4) + version (2) + random (32) = 43
        if (pos >= record.size) return -1
        val sessionIdLen = record[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > record.size) return -1
        val cipherSuitesLen = ((record[pos].toInt() and 0xFF) shl 8) or (record[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > record.size) return -1
        val compMethodsLen = record[pos].toInt() and 0xFF
        pos += 1 + compMethodsLen
        if (pos + 2 > record.size) return -1
        val extTotalLen = ((record[pos].toInt() and 0xFF) shl 8) or (record[pos + 1].toInt() and 0xFF)
        pos += 2
        val end = (pos + extTotalLen).coerceAtMost(record.size)
        while (pos + 4 <= end) {
            val extType = ((record[pos].toInt() and 0xFF) shl 8) or (record[pos + 1].toInt() and 0xFF)
            val extLen = ((record[pos + 2].toInt() and 0xFF) shl 8) or (record[pos + 3].toInt() and 0xFF)
            pos += 4
            if (extType == 0x0000 && pos + 5 <= end) {
                // server_name_list_len (2) + name_type (1) + host_name_len (2) = 5 bytes before ASCII hostname
                return pos + 5
            }
            pos += extLen
        }
        return -1
    }

    /**
     * Splits a TLS ClientHello record (`0x16`) right inside the middle of the SNI hostname
     * into two valid TLS Handshake records (`0x16`), defeating stateless/semi-stateful DPI
     * without requiring root (`SamNet-dev/snix` & `therealaleph/sni-spoofing-rust`).
     */
    fun splitClientHelloAtSni(record: ByteArray): Pair<ByteArray, ByteArray>? {
        val hostOffset = findSniHostnameOffset(record)
        val payloadSplitBytes = if (hostOffset > 8 && hostOffset + 2 < record.size) {
            // Split 2 bytes into the SNI hostname itself (relative to the 5-byte TLS record header)
            (hostOffset - 5 + 2).coerceIn(8, record.size - 7)
        } else {
            16.coerceAtMost((record.size - 7).coerceAtLeast(1))
        }
        return WhiteDnsSmartScanner.splitTlsClientHelloRecord(record, payloadSplitBytes)
    }

    /**
     * Multi-chunk TLS Record Splitter at the SNI extension: slices the SNI hostname into multiple
     * tiny 3-4 byte valid TLS Handshake records (`0x16 0x03 0x01 ...`) so no single TCP segment or
     * TLS record ever contains more than 4 consecutive characters of the target domain name.
     */
    fun splitClientHelloMultiChunkAtSni(record: ByteArray): List<ByteArray> {
        if (record.size < 20 || (record[0].toInt() and 0xFF) != 0x16) return listOf(record)
        val verMajor = record[1]
        val verMinor = record[2]
        val payload = record.copyOfRange(5, record.size)
        val hostOffsetInRecord = findSniHostnameOffset(record)
        val sniStart = if (hostOffsetInRecord > 7 && hostOffsetInRecord < record.size - 4) {
            hostOffsetInRecord - 5
        } else {
            12.coerceAtMost(payload.size / 2)
        }

        val cutPoints = mutableListOf<Int>()
        // First cut: 2 bytes into the SNI hostname
        val firstCut = (sniStart + 2).coerceIn(4, payload.size - 2)
        cutPoints += firstCut
        // Subsequent cuts: slice the next 24 bytes (the domain name) into 4-byte micro-records
        var cursor = firstCut
        repeat(6) {
            cursor += 4
            if (cursor < payload.size - 2) {
                cutPoints += cursor
            }
        }

        val outRecords = mutableListOf<ByteArray>()
        var prev = 0
        for (cut in cutPoints) {
            if (cut <= prev || cut >= payload.size) continue
            val sliceLen = cut - prev
            val rec = ByteArray(5 + sliceLen)
            rec[0] = 0x16
            rec[1] = verMajor
            rec[2] = verMinor
            rec[3] = ((sliceLen ushr 8) and 0xFF).toByte()
            rec[4] = (sliceLen and 0xFF).toByte()
            System.arraycopy(payload, prev, rec, 5, sliceLen)
            outRecords += rec
            prev = cut
        }
        if (prev < payload.size) {
            val sliceLen = payload.size - prev
            val rec = ByteArray(5 + sliceLen)
            rec[0] = 0x16
            rec[1] = verMajor
            rec[2] = verMinor
            rec[3] = ((sliceLen ushr 8) and 0xFF).toByte()
            rec[4] = (sliceLen and 0xFF).toByte()
            System.arraycopy(payload, prev, rec, 5, sliceLen)
            outRecords += rec
        }
        return if (outRecords.isNotEmpty()) outRecords else listOf(record)
    }

    /**
     * Sends a short decoy TLS ClientHello carrying [decoySni] (e.g. `ebanksepah.ir` or `speed.cloudflare.com`)
     * to `[host]:[port]` right before the real connection's fragmented ClientHello so stateful DPI
     * caches associate the destination flow with the whitelisted SNI.
     */
    private fun sendDecoyClientHelloPulse(host: String, port: Int, decoySni: String) {
        runCatching {
            Socket().use { decoySock ->
                protectOutboundSocket(decoySock)
                decoySock.tcpNoDelay = true
                decoySock.soTimeout = 600
                decoySock.connect(InetSocketAddress(host, port), 600)
                val fakeHello = buildFakeClientHelloRecord(decoySni)
                decoySock.getOutputStream().write(fakeHello)
                decoySock.getOutputStream().flush()
            }
        }
    }

    /**
     * Tests a real end-to-end TLS handshake through the specified [strategy] (splitting the outgoing
     * ClientHello at the SNI boundary) against `[ip]:[port]` with `[sni]` and returns the verified
     * handshake latency in milliseconds, or `null` if blocked/unreachable.
     */
    fun probeSpoofedTlsHandshake(
        ip: String,
        port: Int,
        sni: String,
        strategy: SpoofStrategy = SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
        decoySni: String = currentDecoySni,
        timeoutMs: Int = 1_400,
    ): Long? {
        return runCatching {
            val cleanIp = ip.trim().removePrefix("[").removeSuffix("]")
            if (strategy == SpoofStrategy.LOW_TTL_DECOY) {
                sendDecoyClientHelloPulse(cleanIp, port, decoySni)
            }
            val t0 = System.nanoTime()
            val raw = Socket()
            protectOutboundSocket(raw)
            raw.tcpNoDelay = true
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(cleanIp, port), timeoutMs)

            val wrapped = object : Socket() {
                private val firstOut = java.util.concurrent.atomic.AtomicBoolean(true)
                override fun getInputStream() = raw.getInputStream()
                override fun getOutputStream(): java.io.OutputStream {
                    val baseOut = raw.getOutputStream()
                    return object : java.io.FilterOutputStream(baseOut) {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (firstOut.compareAndSet(true, false) && len > 5 && (b[off].toInt() and 0xFF) == 0x16) {
                                val slice = b.copyOfRange(off, off + len)
                                val chunks = splitClientHelloMultiChunkAtSni(slice)
                                for ((idx, chunk) in chunks.withIndex()) {
                                    baseOut.write(chunk)
                                    baseOut.flush()
                                    if (idx == 0) Thread.sleep(2)
                                }
                            } else {
                                baseOut.write(b, off, len)
                            }
                        }
                    }
                }
                override fun isConnected(): Boolean = raw.isConnected
                override fun isClosed(): Boolean = raw.isClosed
                override fun close() = raw.close()
            }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(wrapped, sni, port, true) as SSLSocket).use { ssl ->
                ssl.soTimeout = timeoutMs
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                ssl.sslParameters = params
                ssl.startHandshake()
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /**
     * Fact-A DNS Non-Hijack SNI Finder Oracle (`XTLS/BBS#10`):
     * Queries a UDP DNS resolver for each candidate domain and checks whether the firewall
     * hijacks the A-record to `10.10.34.*` / `0.0.0.0` / loopback. Any domain whose UDP DNS
     * answer is NOT hijacked has both its SNI and IP open on the current ISP.
     */
    fun probeFactADnsOracle(
        domains: List<String> = WHITELIST_SNI_CANDIDATES.take(10),
        resolverIp: String = "8.8.8.8",
        timeoutMs: Int = 900,
    ): List<FactADnsOracleHit> {
        val results = mutableListOf<FactADnsOracleHit>()
        for ((idx, domain) in domains.withIndex()) {
            val cleanDomain = domain.trim().lowercase()
            if (cleanDomain.isEmpty()) continue
            val txId = (0x6A00 or (idx and 0xFF))
            val query = WhiteDnsSmartScanner.buildDnsQueryPacket(txId, qtype = 1, domain = cleanDomain)
            val dnsOutcome = runCatching {
                val t0 = System.nanoTime()
                DatagramSocket().use { sock ->
                    protectOutboundDatagramSocket(sock)
                    sock.soTimeout = timeoutMs
                    sock.send(DatagramPacket(query, query.size, InetAddress.getByName(resolverIp), 53))
                    val buf = ByteArray(512)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val rtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    if (!WhiteDnsSmartScanner.isValidDnsResponse(buf, pkt.length, txId)) return@runCatching null
                    val ipStr = extractFirstARecordIp(buf, pkt.length) ?: return@runCatching null
                    ipStr to rtt
                }
            }.getOrNull() ?: continue

            val (resolvedIp, dnsRtt) = dnsOutcome
            val poisoned = WhiteDnsSmartScanner.isPoisonedIranDnsIp(resolvedIp)
            var tlsOpen = false
            var tlsRtt: Long? = null
            if (!poisoned) {
                tlsRtt = probeTlsEndpoint(resolvedIp, 443, cleanDomain, timeoutMs)
                tlsOpen = tlsRtt != null
            }
            results += FactADnsOracleHit(
                domain = cleanDomain,
                resolvedIp = resolvedIp,
                dnsRttMs = dnsRtt,
                tlsPort443Open = tlsOpen,
                tlsRttMs = tlsRtt,
                poisoned = poisoned,
            )
        }
        return results.sortedWith(
            compareBy<FactADnsOracleHit> { it.poisoned }
                .thenByDescending { it.tlsPort443Open }
                .thenBy { it.tlsRttMs ?: it.dnsRttMs },
        )
    }

    /**
     * Extracts the last 4 bytes of a Type-A answer RR (`0x00 0x01 ... rdlength=0x00 0x04`) from a raw DNS response.
     */
    internal fun extractFirstARecordIp(buf: ByteArray, length: Int): String? {
        if (length < 28) return null
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount <= 0) return null
        for (i in 12..(length - 14)) {
            // Type A (0x0001), Class IN (0x0001), TTL (4B), RDLENGTH = 4 (0x0004)
            if ((buf[i].toInt() and 0xFF) == 0x00 &&
                (buf[i + 1].toInt() and 0xFF) == 0x01 &&
                (buf[i + 2].toInt() and 0xFF) == 0x00 &&
                (buf[i + 3].toInt() and 0xFF) == 0x01 &&
                (buf[i + 8].toInt() and 0xFF) == 0x00 &&
                (buf[i + 9].toInt() and 0xFF) == 0x04 &&
                i + 13 < length
            ) {
                val b0 = buf[i + 10].toInt() and 0xFF
                val b1 = buf[i + 11].toInt() and 0xFF
                val b2 = buf[i + 12].toInt() and 0xFF
                val b3 = buf[i + 13].toInt() and 0xFF
                return "$b0.$b1.$b2.$b3"
            }
        }
        return null
    }

    /**
     * Scans the Non-Cloudflare CDN & Domestic Whitelist catalog and returns reachable endpoints
     * with their exact verified `IP:port` (never altering ports).
     */
    fun scanNonCloudflareWhitelistTargets(
        providerFilter: NonCfCdnProvider? = null,
        timeoutMs: Int = 1_100,
    ): List<WhitelistScanHit> {
        val candidates = if (providerFilter != null) {
            NON_CF_WHITELIST_TARGETS.filter { it.provider == providerFilter }
        } else {
            NON_CF_WHITELIST_TARGETS
        }
        val hits = mutableListOf<WhitelistScanHit>()
        for (target in candidates) {
            val tcpRtt = runCatching {
                val t0 = System.nanoTime()
                Socket().use { s ->
                    protectOutboundSocket(s)
                    s.connect(InetSocketAddress(target.ip, target.port), timeoutMs)
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull() ?: continue

            val tlsRtt = probeTlsEndpoint(target.ip, target.port, target.sni, timeoutMs)
            hits += WhitelistScanHit(
                target = target,
                tcpRttMs = tcpRtt,
                tlsRttMs = tlsRtt,
                tlsVerified = tlsRtt != null,
            )
        }
        return hits.sortedWith(
            compareByDescending<WhitelistScanHit> { it.tlsVerified }
                .thenBy { it.effectiveRttMs },
        )
    }

    private fun probeTlsEndpoint(ip: String, port: Int, sni: String, timeoutMs: Int): Long? {
        return runCatching {
            val t0 = System.nanoTime()
            val addr = InetAddress.getByName(ip.trim().removePrefix("[").removeSuffix("]"))
            Socket().use { raw ->
                protectOutboundSocket(raw)
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(addr, port), timeoutMs)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, sni, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    ssl.sslParameters = params
                    ssl.startHandshake()
                }
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /**
     * Generates ready-to-import VLESS links for a verified [WhitelistTargetEndpoint] or
     * any of the 5 Cloudflare Spoofing methods, preserving the exact `IP:port` pair.
     */
    fun buildSpoofedVlessLinks(
        uuid: String,
        workerHost: String,
        path: String = "/ws?ed=2048",
        verifiedNonCfHits: List<WhitelistScanHit> = emptyList(),
    ): List<String> {
        val cleanUuid = uuid.trim().ifBlank { "11111111-2222-4333-8444-555555555555" }
        val cleanHost = workerHost.trim().ifBlank { "panel.example.workers.dev" }
        val encPath = runCatching { java.net.URLEncoder.encode(path, "UTF-8") }.getOrDefault("%2Fws")
        val out = mutableListOf<String>()

        // 1. Verified Non-Cloudflare / Whitelist endpoints (exact IP:port preserved)
        for (hit in verifiedNonCfHits) {
            val t = hit.target
            val encRemark = runCatching {
                java.net.URLEncoder.encode("🛡️ ${t.provider.id.uppercase()}-${t.exactEndpoint}", "UTF-8")
            }.getOrDefault("Whitelist-${t.provider.id}")
            out += "vless://$cleanUuid@${t.exactEndpoint}?encryption=none&security=tls&type=ws" +
                "&host=$cleanHost&path=$encPath&sni=${t.sni}&fp=chrome&alpn=h2%2Chttp%2F1.1#$encRemark"
        }

        // 2. All 5 Cloudflare Spoofing & Fronting methods side-by-side
        for (m in CF_SPOOF_5_METHODS) {
            val encRemark = runCatching {
                java.net.URLEncoder.encode("⚡ CF-Spoof-${m.index}-${m.id}", "UTF-8")
            }.getOrDefault("CF-Spoof-${m.index}")
            out += "vless://$cleanUuid@${m.sampleEndpoint}?encryption=none&type=ws" +
                "&host=$cleanHost&path=$encPath&${m.queryParams}#$encRemark"
        }
        return out
    }

    /**
     * Generates a ready-to-use configuration bundle for 2026 Hybrid National-Intranet Tunnels:
     * 1) `patterniha/QS-Tunnel` (DNS Query Upload `< 99 chars` + L3 IP-Spoof UDP Download)
     * 2) `patterniha/QQ-Tunnel` (DNS Query-to-Query bidirectional tunnel with 4 records `a/na`, `b/nb`)
     * 3) `ParsaKSH/spoof-tunnel` (Mutual L3 IP Spoofing with `ICMPv6-in-IPv4 proto 58` + WireGuard `MTU 1280`)
     * 4) `Fedarisha / S3 Bypass` (Object-Storage S3 bucket relay with `uploadUntilDelivered`)
     */
    fun buildHybridNetMelliTunnelConfigs(
        tunnelDomain: String = "t.example.com",
        foreignServerIp: String = "203.0.113.10",
        iranRelayIp: String = "185.143.232.10",
        whitelistedSpoofIp: String = "23.215.0.206",
        s3Endpoint: String = "s3.ir-thr-at1.arvanstorage.ir",
        s3Bucket: String = "cat-relay-bucket",
    ): String = buildString {
        val dom = tunnelDomain.trim().ifBlank { "t.example.com" }
        appendLine("# === 1) QS-Tunnel (Upload via Short DNS Query + Download via L3 IP Spoofing) ===")
        appendLine("qstunnel://$whitelistedSpoofIp:53/$dom?mode=n-1&qtype=A&max_len=99&fake_src=$whitelistedSpoofIp:443&keepalive=15#QS-Tunnel-Direct")
        appendLine()
        appendLine("# === 2) QQ-Tunnel (Bidirectional DNS Query-to-Query · 4 Subdomains a/na & b/nb) ===")
        appendLine("qqtunnel://$iranRelayIp:53/$dom?up_ns=a.$dom&down_ns=b.$dom&qtype=A&max_len=99#QQ-Tunnel-Bidirectional")
        appendLine()
        appendLine("# === 3) Spoof-Tunnel (Mutual L3 IP Spoofing · ICMPv6-in-IPv4 Proto 58 + WG MTU 1280) ===")
        appendLine("spooftunnel://$foreignServerIp:51820?iran_ip=$iranRelayIp&spoof_pool=$whitelistedSpoofIp,151.101.1.195,34.143.72.2&proto=icmpv6-in-ipv4&mtu=1280#Spoof-Tunnel-WG")
        appendLine()
        appendLine("# === 4) S3 Bypass / Fedarisha (Whitelisted S3 Bucket Object Tunnel · Concurrency 32) ===")
        appendLine("s3bypass://$s3Endpoint:443/$s3Bucket?storage=s3&write_concurrency=32&get_budget_ms=1200-4000&retry=until_delivered#S3-Bypass-Whitelist")
    }

    // Active local SNI spoof SOCKS5 listeners keyed by strategy id
    private val activeSpoofServers = ConcurrentHashMap<String, ServerSocket>()
    // Active direct TCP anti-DPI forwarders keyed by "remoteHost:remotePort|strategyId"
    private val activeTcpForwarders = ConcurrentHashMap<String, ServerSocket>()
    // Mutable live target state per forwarder key so Auto-Heal & 1-Tap Edge Switch rotate IPs with zero tunnel drop
    data class ForwarderTargetState(
        @Volatile var currentHost: String,
        @Volatile var currentPort: Int,
        val originalHost: String,
        val originalPort: Int,
        @Volatile var strategy: SpoofStrategy,
    )
    private val activeForwarderStates = ConcurrentHashMap<String, ForwarderTargetState>()
    const val DEFAULT_LISTEN_PORT = 10819
    const val DEFAULT_LAN_HOTSPOT_PORT = 10828

    @Volatile
    var autoHealWatchdogEnabled: Boolean = true
    @Volatile
    var gamingLowLatencyBoosterEnabled: Boolean = false
    val autoHealRotationCount = AtomicInteger(0)
    @Volatile
    var lastActiveEdgeEndpoint: String = "188.114.98.0:443"
        private set
    @Volatile
    var lastActiveEdgeRttMs: Long = -1L
        private set
    @Volatile
    private var autoHealWatchdogThread: Thread? = null
    @Volatile
    private var lanHotspotServer: ServerSocket? = null

    data class RealConfigTemplate(
        val scheme: String,
        val uuidOrPass: String,
        val remoteHost: String,
        val remotePort: Int,
        val hostHeader: String,
        val sni: String,
        val path: String,
        val networkType: String,
        val remark: String,
    )

    /**
     * Parses a user's real `vless://` or `trojan://` share links (excluding placeholder UUIDs
     * like `11111111-...` or `example.workers.dev`) so that spoofing, whitelist scanning, and
     * local anti-DPI forwarding operate on the user's actual working server.
     */
    fun extractRealConfigTemplate(
        rawLines: List<String>,
        deployedUuid: String? = null,
        deployedWorkerHost: String? = null,
    ): RealConfigTemplate? {
        for (raw in rawLines) {
            val line = raw.trim()
            val lower = line.lowercase()
            if (!lower.startsWith("vless://") && !lower.startsWith("trojan://")) continue
            val parsed = runCatching {
                val uri = java.net.URI(line.replace(" ", "%20"))
                val scheme = uri.scheme.lowercase()
                val auth = uri.rawAuthority ?: return@runCatching null
                val atIdx = auth.lastIndexOf('@')
                if (atIdx <= 0) return@runCatching null
                val userInfo = java.net.URLDecoder.decode(auth.substring(0, atIdx), "UTF-8").trim()
                if (userInfo.isBlank() ||
                    userInfo.startsWith("11111111-") ||
                    userInfo.startsWith("00000000-")
                ) {
                    return@runCatching null
                }
                val hostPortPart = auth.substring(atIdx + 1).substringBefore('/')
                var (host, port) = if (hostPortPart.startsWith("[")) {
                    val closeIdx = hostPortPart.indexOf(']')
                    val h = hostPortPart.substring(1, closeIdx)
                    val p = hostPortPart.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: 443
                    h to p
                } else {
                    val colon = hostPortPart.lastIndexOf(':')
                    if (colon <= 0) return@runCatching null
                    val h = hostPortPart.substring(0, colon)
                    val p = hostPortPart.substring(colon + 1).toIntOrNull() ?: 443
                    h to p
                }
                val qMap = LinkedHashMap<String, String>()
                uri.rawQuery.orEmpty().split('&').forEach { kv ->
                    val eq = kv.indexOf('=')
                    if (eq > 0) {
                        val k = java.net.URLDecoder.decode(kv.substring(0, eq), "UTF-8").lowercase()
                        val v = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                        qMap[k] = v
                    }
                }
                if (host == "127.0.0.1") {
                    val fwdTarget = (qMap["sni_fwd_target"] ?: qMap["sni-fwd-target"])?.trim().orEmpty()
                    val colonIdx = fwdTarget.lastIndexOf(':')
                    if (colonIdx > 0) {
                        host = fwdTarget.substring(0, colonIdx).removePrefix("[").removeSuffix("]")
                        port = fwdTarget.substring(colonIdx + 1).toIntOrNull() ?: 443
                    }
                }
                if (host.isBlank() || host == "127.0.0.1") return@runCatching null
                val wsHost = qMap["host"]?.trim().orEmpty()
                val sni = (qMap["sni"] ?: qMap["servername"])?.trim().orEmpty()
                val effectiveHostHeader = wsHost.ifBlank { sni.ifBlank { host } }
                if (effectiveHostHeader.contains("example.workers.dev") ||
                    effectiveHostHeader == "sub.workers.dev"
                ) {
                    return@runCatching null
                }
                val path = qMap["path"]?.takeIf { it.isNotBlank() } ?: "/ws?ed=2048"
                val netType = qMap["type"]?.takeIf { it.isNotBlank() } ?: "ws"
                val rawRemark = runCatching {
                    java.net.URLDecoder.decode(uri.rawFragment.orEmpty(), "UTF-8")
                }.getOrDefault("")
                val cleanRemark = rawRemark
                    .substringAfterLast("· ")
                    .trim()
                    .ifBlank { effectiveHostHeader }
                RealConfigTemplate(
                    scheme = scheme,
                    uuidOrPass = userInfo,
                    remoteHost = host,
                    remotePort = port,
                    hostHeader = effectiveHostHeader,
                    sni = sni.ifBlank { effectiveHostHeader },
                    path = path,
                    networkType = netType,
                    remark = cleanRemark,
                )
            }.getOrNull()
            if (parsed != null) return parsed
        }
        val cleanUuid = deployedUuid?.trim()?.takeIf {
            it.isNotBlank() && !it.startsWith("11111111-") && !it.startsWith("00000000-")
        }
        val cleanWorker = deployedWorkerHost?.trim()?.takeIf {
            it.isNotBlank() && !it.contains("example.") && it != "sub.workers.dev"
        }
        if (cleanUuid != null && cleanWorker != null) {
            return RealConfigTemplate(
                scheme = "vless",
                uuidOrPass = cleanUuid,
                remoteHost = "188.114.98.0",
                remotePort = 443,
                hostHeader = cleanWorker,
                sni = cleanWorker,
                path = "/$cleanUuid?ed=2048",
                networkType = "ws",
                remark = cleanWorker,
            )
        }
        return null
    }

    private val spoofPoolSeq = AtomicInteger(0)
    private val spoofWorkerPool: ThreadPoolExecutor = ThreadPoolExecutor(
        4,
        36,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(96),
        ThreadFactory { runnable ->
            Thread(null, runnable, "sni-spoof-pool-${spoofPoolSeq.incrementAndGet()}", 256 * 1024L).apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.DiscardPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Starts a direct TCP port forwarder on `127.0.0.1` that intercepts the first TLS ClientHello
     * record (`0x16`) from Mihomo's VLESS/Trojan outbound, applies [strategy] (SNI record split,
     * decoy ClientHello, or 2-stage fragment), and forwards it to `[remoteHost]:[remotePort]`.
     */
    fun startDirectTcpSpoofForwarder(
        remoteHost: String,
        remotePort: Int,
        strategy: SpoofStrategy = currentActiveStrategy,
        decoySni: String = currentDecoySni,
    ): Int {
        currentActiveStrategy = strategy
        currentDecoySni = decoySni.trim().ifBlank { "ebanksepah.ir" }
        val key = "$remoteHost:$remotePort|${strategy.id}"
        val targetState = activeForwarderStates.computeIfAbsent(key) {
            ForwarderTargetState(
                currentHost = remoteHost,
                currentPort = remotePort,
                originalHost = remoteHost,
                originalPort = remotePort,
                strategy = strategy,
            )
        }
        targetState.strategy = strategy
        lastActiveEdgeEndpoint = "${targetState.currentHost}:${targetState.currentPort}"
        val existing = activeTcpForwarders[key]
        if (existing != null && !existing.isClosed) {
            return existing.localPort
        }
        return runCatching {
            val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            activeTcpForwarders[key] = server
            if (autoHealWatchdogEnabled) {
                ensureAutoHealWatchdogLoop()
            }
            Thread(null, Runnable {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        spoofWorkerPool.execute {
                            handleDirectTcpForwardClient(
                                client = client,
                                remoteHost = targetState.currentHost,
                                remotePort = targetState.currentPort,
                                strategy = targetState.strategy,
                            )
                        }
                    }.onFailure { runCatching { client.close() } }
                }
            }, "sni-tcp-fwd-${server.localPort}", 256 * 1024L).apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(DEFAULT_LISTEN_PORT)
    }

    internal fun applyGamingSocketTuning(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            if (gamingLowLatencyBoosterEnabled) {
                // DSCP EF (0x2E << 2 = 0xB8) for low-latency voice/gaming packets, fallback IPTOS_LOWDELAY (0x10)
                runCatching { socket.trafficClass = 0xB8 }.onFailure {
                    runCatching { socket.trafficClass = 0x10 }
                }
                socket.sendBufferSize = 65_536
                socket.receiveBufferSize = 65_536
            }
        }
    }

    private fun handleDirectTcpForwardClient(
        client: Socket,
        remoteHost: String,
        remotePort: Int,
        strategy: SpoofStrategy,
    ) {
        runCatching {
            client.use { c ->
                c.soTimeout = if (gamingLowLatencyBoosterEnabled) 15_000 else 20_000
                applyGamingSocketTuning(c)
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                val cleanHost = remoteHost.removePrefix("[").removeSuffix("]")
                if (cleanHost == "127.0.0.1" || cleanHost.equals("localhost", ignoreCase = true) || cleanHost == "::1") {
                    return@use
                }
                val upstream = Socket()
                try {
                    protectOutboundSocket(upstream)
                    applyGamingSocketTuning(upstream)
                    upstream.soTimeout = if (gamingLowLatencyBoosterEnabled) 15_000 else 20_000
                    upstream.connect(InetSocketAddress(cleanHost, remotePort), 8_000)
                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()
                    spoofWorkerPool.execute {
                        runCatching {
                            val buf = ByteArray(4096)
                            var firstPacket = true
                            while (true) {
                                val n = inp.read(buf)
                                if (n <= 0) break
                                if (firstPacket && n > 5 && (buf[0].toInt() and 0xFF) == 0x16) {
                                    firstPacket = false
                                    val rawRecord = buf.copyOfRange(0, n)
                                    when (strategy) {
                                        SpoofStrategy.LOW_TTL_DECOY -> {
                                            sendDecoyClientHelloPulse(cleanHost, remotePort, currentDecoySni)
                                            val chunks = splitClientHelloMultiChunkAtSni(rawRecord)
                                            for ((idx, chunk) in chunks.withIndex()) {
                                                upOut.write(chunk)
                                                upOut.flush()
                                                if (idx <= 2) Thread.sleep(2)
                                            }
                                        }
                                        SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
                                        SpoofStrategy.MITM_PINNED_CERT_FRONTING -> {
                                            val chunks = splitClientHelloMultiChunkAtSni(rawRecord)
                                            for ((idx, chunk) in chunks.withIndex()) {
                                                upOut.write(chunk)
                                                upOut.flush()
                                                if (idx <= 2) Thread.sleep(2)
                                            }
                                        }
                                        SpoofStrategy.WRONG_TIMESTAMP_PAWS,
                                        SpoofStrategy.FINALMASK_HEADER_CUSTOM -> {
                                            val stage1 = WhiteDnsSmartScanner.splitTlsClientHelloRecord(rawRecord, 6)
                                            if (stage1 != null) {
                                                upOut.write(stage1.first)
                                                upOut.flush()
                                                Thread.sleep(1)
                                                val restChunks = splitClientHelloMultiChunkAtSni(stage1.second)
                                                for (chunk in restChunks) {
                                                    upOut.write(chunk)
                                                    upOut.flush()
                                                    Thread.sleep(1)
                                                }
                                            } else {
                                                upOut.write(rawRecord)
                                                upOut.flush()
                                            }
                                        }
                                    }
                                } else {
                                    firstPacket = false
                                    upOut.write(buf, 0, n)
                                    upOut.flush()
                                }
                            }
                        }
                    }
                    val downBuf = ByteArray(4096)
                    while (true) {
                        val n = upIn.read(downBuf)
                        if (n <= 0) break
                        out.write(downBuf, 0, n)
                        out.flush()
                    }
                } finally {
                    runCatching { upstream.close() }
                }
            }
        }
    }

    /**
     * Builds ready-to-connect repaired share links for the user's REAL [template]:
     * 1) Local TCP Anti-DPI Spoof Forwarder (`127.0.0.1:<localPort>`) -> user's real server
     * 2) All 5 Cloudflare Spoofing methods using the user's real UUID/password, Host & Path
     * 3) Any verified open endpoints from [verifiedNonCfHits] preserving exact `IP:port`
     */
    fun buildRepairedConfigsForRealTemplate(
        template: RealConfigTemplate,
        strategy: SpoofStrategy = currentActiveStrategy,
        verifiedNonCfHits: List<WhitelistScanHit> = emptyList(),
    ): List<String> {
        val out = mutableListOf<String>()
        val encPath = runCatching { java.net.URLEncoder.encode(template.path, "UTF-8") }.getOrDefault("%2Fws")
        val scheme = template.scheme
        val auth = template.uuidOrPass
        val host = template.hostHeader
        val realSni = template.sni.ifBlank { host }
        val netType = template.networkType
        val encParam = if (scheme == "vless") "encryption=none&" else ""

        // 1. Direct Local Anti-DPI Forwarder wrapping the user's real remote endpoint (splits ClientHello at SNI boundary)
        val primaryTarget = "${template.remoteHost}:${template.remotePort}"
        val localFwdPort = startDirectTcpSpoofForwarder(
            remoteHost = template.remoteHost,
            remotePort = template.remotePort,
            strategy = strategy,
            decoySni = currentDecoySni,
        )
        val localRemark = runCatching {
            java.net.URLEncoder.encode("⭐ پیشنهادی · برش SNI ضد-DPI · ${template.remark}", "UTF-8").replace("+", "%20")
        }.getOrDefault("Repaired-Local-SNI")
        val encTarget = runCatching { java.net.URLEncoder.encode(primaryTarget, "UTF-8") }.getOrDefault(primaryTarget)
        out += "$scheme://$auth@127.0.0.1:$localFwdPort?${encParam}security=tls&type=$netType&host=$host&path=$encPath&sni=$realSni&fp=chrome&alpn=http%2F1.1&sni_fwd_target=$encTarget#$localRemark"

        // 2. Probe clean Cloudflare edges in parallel using real multi-chunk SNI-split TLS handshakes
        // so the fastest reachable edge on the user's ISP is placed right at the top!
        val candidateCfEdges = listOf(
            "188.114.98.0" to 443,
            "162.159.198.235" to 443,
            "172.67.73.163" to 443,
            "104.16.132.229" to 443,
            "162.159.135.42" to 443,
            "104.18.2.161" to 443,
            "188.114.97.3" to 2053,
            "172.67.73.163" to 8443,
        )
        val edgeFutures = candidateCfEdges.map { (edgeIp, edgePort) ->
            spoofWorkerPool.submit<Triple<String, Int, Long?>> {
                val rtt = probeSpoofedTlsHandshake(
                    ip = edgeIp,
                    port = edgePort,
                    sni = realSni,
                    strategy = strategy,
                    decoySni = currentDecoySni,
                    timeoutMs = 1_100,
                )
                Triple(edgeIp, edgePort, rtt)
            }
        }
        val rankedEdges = edgeFutures.mapIndexed { idx, fut ->
            runCatching {
                fut.get(1_400L, TimeUnit.MILLISECONDS)
            }.getOrElse {
                val (ip, p) = candidateCfEdges[idx]
                Triple(ip, p, null)
            }
        }.sortedWith(
            compareByDescending<Triple<String, Int, Long?>> { it.third != null }
                .thenBy { it.third ?: Long.MAX_VALUE },
        )

        for ((edgeIp, edgePort, rttMs) in rankedEdges) {
            val edgeTarget = "$edgeIp:$edgePort"
            val edgeFwdPort = startDirectTcpSpoofForwarder(
                remoteHost = edgeIp,
                remotePort = edgePort,
                strategy = strategy,
                decoySni = currentDecoySni,
            )
            val encEdgeTarget = runCatching { java.net.URLEncoder.encode(edgeTarget, "UTF-8") }.getOrDefault(edgeTarget)
            val pingBadge = if (rttMs != null) " (${rttMs}ms)" else ""
            val splitEdgeRemark = runCatching {
                java.net.URLEncoder.encode("🛡️ برش SNI روی لبه $edgeIp:$edgePort$pingBadge · ${template.remark}", "UTF-8").replace("+", "%20")
            }.getOrDefault("SNI-Split-$edgeIp")
            out += "$scheme://$auth@127.0.0.1:$edgeFwdPort?${encParam}security=tls&type=$netType&host=$host&path=$encPath&sni=$realSni&fp=chrome&alpn=http%2F1.1&sni_fwd_target=$encEdgeTarget#$splitEdgeRemark"

            val fnfRemark = runCatching {
                java.net.URLEncoder.encode("⚡ ایرانسل/همراه اول F&F ($edgeIp:$edgePort$pingBadge) · ${template.remark}", "UTF-8").replace("+", "%20")
            }.getOrDefault("CF-FnF-$edgeIp")
            out += "$scheme://$auth@$edgeIp:$edgePort?${encParam}security=tls&type=$netType&host=$host&path=$encPath&sni=$realSni&fp=unsafe&cs=semi-python&fm=tlshello-0-len&alpn=http%2F1.1#$fnfRemark"
        }

        // 2b. Include the 5 Cloudflare Spoofing methods routed via the fastest verified edge + multi-chunk SNI forwarder
        val bestEdgePair = rankedEdges.firstOrNull { it.third != null } ?: rankedEdges.first()
        val bestEdgeTarget = "${bestEdgePair.first}:${bestEdgePair.second}"
        val bestEdgeFwdPort = startDirectTcpSpoofForwarder(
            remoteHost = bestEdgePair.first,
            remotePort = bestEdgePair.second,
            strategy = strategy,
            decoySni = currentDecoySni,
        )
        val encBestEdgeTarget = runCatching { java.net.URLEncoder.encode(bestEdgeTarget, "UTF-8") }.getOrDefault(bestEdgeTarget)
        for (m in CF_SPOOF_5_METHODS) {
            val mRemark = runCatching {
                java.net.URLEncoder.encode("☁️ ${m.titleFa.substringBefore(":")} · ${template.remark}", "UTF-8").replace("+", "%20")
            }.getOrDefault("CF-Spoof-${m.index}")
            out += "$scheme://$auth@127.0.0.1:$bestEdgeFwdPort?${encParam}type=$netType&host=$host&path=$encPath&${m.queryParams}&sni_fwd_target=$encBestEdgeTarget#$mRemark"
        }

        // 3. MCI IPv6 Clean Edges preserving the user's REAL sni & host header
        val ipv6Edges = listOf(
            "[2a06:98c1:3101::6812:202f]:443",
            "[2606:4700:3030::6815:1001]:443",
        )
        for (v6 in ipv6Edges) {
            val v6Remark = runCatching {
                java.net.URLEncoder.encode("🌐 همراه اول IPv6 ($v6) · ${template.remark}", "UTF-8").replace("+", "%20")
            }.getOrDefault("MCI-IPv6")
            out += "$scheme://$auth@$v6?${encParam}security=tls&type=$netType&host=$host&path=$encPath&sni=$realSni&fp=chrome&alpn=http%2F1.1#$v6Remark"
        }

        // 4. Original user endpoint with Chrome fingerprint & HTTP/1.1 ALPN
        val origRemark = runCatching {
            java.net.URLEncoder.encode("📌 سرور اصلی (${template.remoteHost}:${template.remotePort}) · ${template.remark}", "UTF-8").replace("+", "%20")
        }.getOrDefault("Original")
        out += "$scheme://$auth@${template.remoteHost}:${template.remotePort}?${encParam}security=tls&type=$netType&host=$host&path=$encPath&sni=$realSni&fp=chrome&alpn=http%2F1.1#$origRemark"

        return out.distinct()
    }

    @Volatile
    var currentActiveStrategy: SpoofStrategy = SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI
        private set
    @Volatile
    var currentDecoySni: String = "ebanksepah.ir"
        private set

    fun isRunning(): Boolean = activeSpoofServers.values.any { !it.isClosed }

    fun activeListenPort(): Int =
        activeSpoofServers.values.firstOrNull { !it.isClosed }?.localPort ?: DEFAULT_LISTEN_PORT

    fun updateDecoySni(sni: String) {
        val clean = sni.trim()
        if (clean.isNotEmpty()) {
            currentDecoySni = clean
        }
    }

    fun startSpoofSocksProxy(
        strategy: SpoofStrategy = SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
        decoySni: String = "ebanksepah.ir",
        preferredPort: Int = 10819,
    ): Int {
        currentActiveStrategy = strategy
        currentDecoySni = decoySni.trim().ifBlank { "ebanksepah.ir" }
        val existing = activeSpoofServers[strategy.id]
        if (existing != null && !existing.isClosed) {
            return existing.localPort
        }
        return runCatching {
            val server = try {
                ServerSocket(preferredPort, 64, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            }
            activeSpoofServers[strategy.id] = server
            Thread(null, Runnable {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        spoofWorkerPool.execute { handleSpoofSocksClient(client, strategy, currentDecoySni) }
                    }.onFailure { runCatching { client.close() } }
                }
            }, "sni-spoof-accept-${server.localPort}", 256 * 1024L).apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(preferredPort)
    }

    fun stopAllSpoofProxies() {
        activeSpoofServers.values.forEach { runCatching { it.close() } }
        activeSpoofServers.clear()
        activeTcpForwarders.values.forEach { runCatching { it.close() } }
        activeTcpForwarders.clear()
        activeForwarderStates.clear()
    }

    data class EdgeRotationResult(
        val previousEndpoint: String,
        val newIp: String,
        val newPort: Int,
        val rttMs: Long,
        val updatedForwardersCount: Int,
        val totalRotationCount: Int,
    ) {
        val newEndpoint: String get() = "$newIp:$newPort"
    }

    /**
     * Probes clean Cloudflare edges in parallel via real multi-chunk SNI-split TLS handshakes
     * and hot-swaps all active local forwarders to the fastest healthy edge without dropping the VPN tunnel.
     */
    fun rotateHealthyEdgeNow(
        sni: String = "speed.cloudflare.com",
        forceDifferentIp: Boolean = true,
        timeoutMs: Int = 1_000,
    ): EdgeRotationResult {
        val prev = lastActiveEdgeEndpoint
        val prevHost = prev.substringBeforeLast(':')
        val candidates = listOf(
            "188.114.98.0" to 443,
            "162.159.198.235" to 443,
            "172.67.73.163" to 443,
            "104.16.132.229" to 443,
            "162.159.135.42" to 443,
            "104.18.2.161" to 443,
            "188.114.97.3" to 2053,
            "172.67.73.163" to 8443,
            "104.17.147.22" to 443,
            "172.64.155.209" to 443,
        )
        val futures = candidates.map { (ip, port) ->
            spoofWorkerPool.submit<Triple<String, Int, Long?>> {
                val tlsRtt = probeSpoofedTlsHandshake(
                    ip = ip,
                    port = port,
                    sni = sni.ifBlank { "speed.cloudflare.com" },
                    strategy = currentActiveStrategy,
                    decoySni = currentDecoySni,
                    timeoutMs = timeoutMs,
                ) ?: runCatching {
                    val t0 = System.nanoTime()
                    Socket().use { s ->
                        protectOutboundSocket(s)
                        s.connect(InetSocketAddress(ip, port), timeoutMs)
                    }
                    ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
                Triple(ip, port, tlsRtt)
            }
        }
        val probed = futures.mapIndexed { idx, fut ->
            runCatching { fut.get((timeoutMs + 300).toLong(), TimeUnit.MILLISECONDS) }.getOrElse {
                val (ip, port) = candidates[idx]
                Triple(ip, port, null)
            }
        }.filter { it.third != null }
            .sortedBy { it.third ?: Long.MAX_VALUE }

        val chosen = (if (forceDifferentIp && probed.size > 1) {
            probed.firstOrNull { it.first != prevHost } ?: probed.firstOrNull()
        } else {
            probed.firstOrNull()
        }) ?: Triple("162.159.198.235", 443, 120L)

        val newIp = chosen.first
        val newPort = chosen.second
        val rtt = chosen.third ?: 120L
        var updated = 0
        for (state in activeForwarderStates.values) {
            state.currentHost = newIp
            state.currentPort = newPort
            updated++
        }
        lastActiveEdgeEndpoint = "$newIp:$newPort"
        lastActiveEdgeRttMs = rtt
        val total = autoHealRotationCount.incrementAndGet()
        return EdgeRotationResult(
            previousEndpoint = prev,
            newIp = newIp,
            newPort = newPort,
            rttMs = rtt,
            updatedForwardersCount = updated,
            totalRotationCount = total,
        )
    }

    /**
     * Background Auto-Heal / Anti-Freeze Watchdog + 4G/5G RRC Anti-Sleep Pacer:
     * Checks tunnel edge health every 30s (and sends a lightweight keep-alive pulse every 12s when
     * [gamingLowLatencyBoosterEnabled] is active). If the current edge times out twice in a row or
     * freezes (> 900ms), it seamlessly rotates all active forwarders to a fresh edge.
     */
    fun ensureAutoHealWatchdogLoop() {
        val currentThread = autoHealWatchdogThread
        if (currentThread != null && currentThread.isAlive) return
        val t = Thread(null, Runnable {
            var consecutiveFailures = 0
            var tick = 0
            while (autoHealWatchdogEnabled || gamingLowLatencyBoosterEnabled) {
                runCatching { Thread.sleep(10_000L) }.onFailure { break }
                tick++
                val endpoint = lastActiveEdgeEndpoint
                val colon = endpoint.lastIndexOf(':')
                val host = if (colon > 0) endpoint.substring(0, colon) else "188.114.98.0"
                val port = if (colon > 0) endpoint.substring(colon + 1).toIntOrNull() ?: 443 else 443

                // Gaming RRC Anti-Sleep keepalive pulse every 10s
                if (gamingLowLatencyBoosterEnabled) {
                    runCatching {
                        val t0 = System.nanoTime()
                        Socket().use { s ->
                            protectOutboundSocket(s)
                            applyGamingSocketTuning(s)
                            s.connect(InetSocketAddress(host.removePrefix("[").removeSuffix("]"), port), 900)
                        }
                        lastActiveEdgeRttMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    }
                }

                // Auto-Heal edge health check every 30s (every 3rd tick)
                if (autoHealWatchdogEnabled && tick % 3 == 0 && activeForwarderStates.isNotEmpty()) {
                    val rtt = probeSpoofedTlsHandshake(
                        ip = host,
                        port = port,
                        sni = "speed.cloudflare.com",
                        strategy = currentActiveStrategy,
                        decoySni = currentDecoySni,
                        timeoutMs = 950,
                    )
                    if (rtt == null || rtt > 900L) {
                        consecutiveFailures++
                        if (consecutiveFailures >= 2) {
                            consecutiveFailures = 0
                            runCatching { rotateHealthyEdgeNow(forceDifferentIp = true, timeoutMs = 950) }
                        }
                    } else {
                        consecutiveFailures = 0
                        lastActiveEdgeRttMs = rtt
                    }
                }
            }
        }, "sni-auto-heal-watchdog", 256 * 1024L).apply {
            isDaemon = true
            start()
        }
        autoHealWatchdogThread = t
    }

    data class JitterMetricsReport(
        val targetEndpoint: String,
        val samplesCount: Int,
        val avgRttMs: Long,
        val minRttMs: Long,
        val maxRttMs: Long,
        val jitterMs: Long,
        val packetLossPercent: Int,
        val gamingBoosterActive: Boolean,
    )

    /**
     * Measures real consecutive TCP/TLS latency samples to compute live ping, jitter (ms),
     * and packet loss (%) for gaming and voice/video calls.
     */
    fun measureLiveJitterAndLatency(
        host: String = lastActiveEdgeEndpoint.substringBeforeLast(':').ifBlank { "188.114.98.0" },
        port: Int = lastActiveEdgeEndpoint.substringAfterLast(':').toIntOrNull() ?: 443,
        samples: Int = 5,
        timeoutMs: Int = 900,
    ): JitterMetricsReport {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]").ifBlank { "188.114.98.0" }
        val rtts = mutableListOf<Long>()
        var lost = 0
        val count = samples.coerceIn(2, 8)
        for (i in 0 until count) {
            val sampleRtt = runCatching {
                val t0 = System.nanoTime()
                Socket().use { s ->
                    protectOutboundSocket(s)
                    applyGamingSocketTuning(s)
                    s.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            if (sampleRtt != null) {
                rtts += sampleRtt
            } else {
                lost++
            }
        }
        if (rtts.isEmpty()) {
            return JitterMetricsReport(
                targetEndpoint = "$cleanHost:$port",
                samplesCount = count,
                avgRttMs = -1L,
                minRttMs = -1L,
                maxRttMs = -1L,
                jitterMs = -1L,
                packetLossPercent = 100,
                gamingBoosterActive = gamingLowLatencyBoosterEnabled,
            )
        }
        val avg = rtts.sum() / rtts.size
        val min = rtts.minOrNull() ?: avg
        val max = rtts.maxOrNull() ?: avg
        val jitter = if (rtts.size >= 2) {
            var diffSum = 0L
            for (i in 1 until rtts.size) {
                diffSum += kotlin.math.abs(rtts[i] - rtts[i - 1])
            }
            diffSum / (rtts.size - 1)
        } else {
            0L
        }
        val lossPct = ((lost * 100.0) / count).toInt().coerceIn(0, 100)
        lastActiveEdgeRttMs = avg
        return JitterMetricsReport(
            targetEndpoint = "$cleanHost:$port",
            samplesCount = count,
            avgRttMs = avg,
            minRttMs = min,
            maxRttMs = max,
            jitterMs = jitter,
            packetLossPercent = lossPct,
            gamingBoosterActive = gamingLowLatencyBoosterEnabled,
        )
    }

    /**
     * Detects active IPv4 LAN / Wi-Fi / Hotspot addresses on the device (e.g. `192.168.43.1`, `192.168.1.x`)
     * so other devices (iPhone, Windows, Mac, PlayStation, Smart TV) can connect to the phone's proxy.
     */
    fun detectDeviceLanIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("rmnet")) {
                    continue
                }
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("172.19.") && !ip.startsWith("169.254.")) {
                            ips += ip
                        }
                    }
                }
            }
        }
        if (ips.isEmpty()) {
            ips += "192.168.43.1"
        }
        return ips.distinct()
    }

    fun isLanHotspotProxyRunning(): Boolean = lanHotspotServer?.let { !it.isClosed } == true

    fun activeLanHotspotPort(): Int = lanHotspotServer?.takeIf { !it.isClosed }?.localPort ?: DEFAULT_LAN_HOTSPOT_PORT

    /**
     * Starts a Dual-Protocol (`HTTP CONNECT` + `SOCKS5`) LAN/Hotspot proxy bound to `0.0.0.0`
     * so laptops, iPhones, consoles, and TVs on the same Wi-Fi or Hotspot can use the phone's
     * VPN tunnel + Multi-Chunk SNI Spoofing without installing any software.
     */
    fun startLanHotspotShareProxy(preferredPort: Int = DEFAULT_LAN_HOTSPOT_PORT): Int {
        val current = lanHotspotServer
        if (current != null && !current.isClosed) {
            return current.localPort
        }
        return runCatching {
            val server = try {
                ServerSocket(preferredPort, 128, InetAddress.getByName("0.0.0.0"))
            } catch (_: Throwable) {
                ServerSocket(0, 128, InetAddress.getByName("0.0.0.0"))
            }
            lanHotspotServer = server
            Thread(null, Runnable {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        spoofWorkerPool.execute {
                            handleLanHotspotDualProtocolClient(client, currentActiveStrategy, currentDecoySni)
                        }
                    }.onFailure { runCatching { client.close() } }
                }
            }, "lan-hotspot-share-${server.localPort}", 256 * 1024L).apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(preferredPort)
    }

    fun stopLanHotspotShareProxy() {
        runCatching { lanHotspotServer?.close() }
        lanHotspotServer = null
    }

    private fun handleLanHotspotDualProtocolClient(client: Socket, strategy: SpoofStrategy, decoySni: String) {
        runCatching {
            client.use { c ->
                c.soTimeout = 20_000
                applyGamingSocketTuning(c)
                val pushback = java.io.PushbackInputStream(c.getInputStream(), 4)
                val out = c.getOutputStream()
                val firstByte = pushback.read()
                if (firstByte < 0) return@use
                if (firstByte == 0x05) {
                    // SOCKS5 client
                    val nMethods = pushback.read().coerceAtLeast(0)
                    if (nMethods > 0) pushback.read(ByteArray(nMethods))
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()
                    val hdr = ByteArray(4)
                    if (pushback.read(hdr) != 4 || hdr[0].toInt() != 0x05 || hdr[1].toInt() != 0x01) return@use
                    val host = when (hdr[3].toInt() and 0xFF) {
                        0x01 -> {
                            val b = ByteArray(4)
                            if (pushback.read(b) != 4) return@use
                            InetAddress.getByAddress(b).hostAddress ?: return@use
                        }
                        0x03 -> {
                            val len = pushback.read()
                            if (len <= 0) return@use
                            val b = ByteArray(len)
                            if (pushback.read(b) != len) return@use
                            String(b, Charsets.UTF_8)
                        }
                        0x04 -> {
                            val b = ByteArray(16)
                            if (pushback.read(b) != 16) return@use
                            InetAddress.getByAddress(b).hostAddress ?: return@use
                        }
                        else -> return@use
                    }
                    val pHi = pushback.read()
                    val pLo = pushback.read()
                    if (pHi < 0 || pLo < 0) return@use
                    val port = ((pHi and 0xFF) shl 8) or (pLo and 0xFF)
                    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
                    if (cleanHost == "127.0.0.1" || cleanHost.equals("localhost", ignoreCase = true) || cleanHost == "0.0.0.0") return@use
                    val upstream = Socket()
                    try {
                        applyGamingSocketTuning(upstream)
                        upstream.connect(InetSocketAddress(cleanHost, port), 8_000)
                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        out.flush()
                        pipeBidirectionalWithSniSplit(pushback, out, upstream, cleanHost, port, strategy, decoySni, initialPayload = null)
                    } finally {
                        runCatching { upstream.close() }
                    }
                } else {
                    // HTTP CONNECT / HTTP Proxy client (iPhone, Windows, Mac, PS5, Smart TV)
                    pushback.unread(firstByte)
                    val headerBytes = ByteArrayOutputStream()
                    var matchState = 0
                    while (headerBytes.size() < 8192) {
                        val b = pushback.read()
                        if (b < 0) break
                        headerBytes.write(b)
                        matchState = when {
                            matchState == 0 && b == '\r'.code -> 1
                            matchState == 1 && b == '\n'.code -> 2
                            matchState == 2 && b == '\r'.code -> 3
                            matchState == 3 && b == '\n'.code -> 4
                            b == '\r'.code -> 1
                            else -> 0
                        }
                        if (matchState == 4) break
                    }
                    val reqText = headerBytes.toString("ISO-8859-1")
                    val firstLine = reqText.lineSequence().firstOrNull()?.trim().orEmpty()
                    val parts = firstLine.split(' ')
                    if (parts.size < 2) return@use
                    val method = parts[0].uppercase()
                    val targetUri = parts[1]
                    if (method == "CONNECT") {
                        val colon = targetUri.lastIndexOf(':')
                        val host = if (colon > 0) targetUri.substring(0, colon) else targetUri
                        val port = if (colon > 0) targetUri.substring(colon + 1).toIntOrNull() ?: 443 else 443
                        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
                        if (cleanHost == "127.0.0.1" || cleanHost.equals("localhost", ignoreCase = true)) return@use
                        val upstream = Socket()
                        try {
                            applyGamingSocketTuning(upstream)
                            upstream.connect(InetSocketAddress(cleanHost, port), 8_000)
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                            pipeBidirectionalWithSniSplit(pushback, out, upstream, cleanHost, port, strategy, decoySni, initialPayload = null)
                        } finally {
                            runCatching { upstream.close() }
                        }
                    } else {
                        // Plain HTTP request with Host header or full URL
                        val hostLine = reqText.lineSequence()
                            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            .orEmpty()
                        val hostPort = hostLine.ifBlank {
                            targetUri.removePrefix("http://").substringBefore('/')
                        }
                        if (hostPort.isBlank()) return@use
                        val colon = hostPort.lastIndexOf(':')
                        val host = if (colon > 0) hostPort.substring(0, colon) else hostPort
                        val port = if (colon > 0) hostPort.substring(colon + 1).toIntOrNull() ?: 80 else 80
                        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
                        if (cleanHost == "127.0.0.1" || cleanHost.equals("localhost", ignoreCase = true)) return@use
                        val upstream = Socket()
                        try {
                            applyGamingSocketTuning(upstream)
                            upstream.connect(InetSocketAddress(cleanHost, port), 8_000)
                            pipeBidirectionalWithSniSplit(
                                clientIn = pushback,
                                clientOut = out,
                                upstream = upstream,
                                cleanHost = cleanHost,
                                port = port,
                                strategy = strategy,
                                decoySni = decoySni,
                                initialPayload = headerBytes.toByteArray(),
                            )
                        } finally {
                            runCatching { upstream.close() }
                        }
                    }
                }
            }
        }
    }

    private fun pipeBidirectionalWithSniSplit(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        upstream: Socket,
        cleanHost: String,
        port: Int,
        strategy: SpoofStrategy,
        decoySni: String,
        initialPayload: ByteArray?,
    ) {
        val upIn = upstream.getInputStream()
        val upOut = upstream.getOutputStream()
        if (initialPayload != null && initialPayload.isNotEmpty()) {
            upOut.write(initialPayload)
            upOut.flush()
        }
        spoofWorkerPool.execute {
            runCatching {
                val buf = ByteArray(4096)
                var firstPacket = initialPayload == null
                while (true) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    if (firstPacket && n > 5 && (buf[0].toInt() and 0xFF) == 0x16) {
                        firstPacket = false
                        val rawRecord = buf.copyOfRange(0, n)
                        if (strategy == SpoofStrategy.LOW_TTL_DECOY) {
                            sendDecoyClientHelloPulse(cleanHost, port, decoySni)
                        }
                        val chunks = splitClientHelloMultiChunkAtSni(rawRecord)
                        for ((idx, chunk) in chunks.withIndex()) {
                            upOut.write(chunk)
                            upOut.flush()
                            if (idx <= 2) Thread.sleep(2)
                        }
                    } else {
                        firstPacket = false
                        upOut.write(buf, 0, n)
                        upOut.flush()
                    }
                }
            }
        }
        val downBuf = ByteArray(4096)
        while (true) {
            val n = upIn.read(downBuf)
            if (n <= 0) break
            clientOut.write(downBuf, 0, n)
            clientOut.flush()
        }
    }

    data class CleanedSubscriptionReport(
        val totalChecked: Int,
        val aliveCount: Int,
        val deadRemovedCount: Int,
        val fastestLatencyMs: Long,
        val sortedAliveLinks: List<String>,
    )

    /**
     * Parallel 1-Tap Dead-Config Cleaner & Ping Sorter:
     * Probes every share URI in [rawLines], removes dead/timed-out servers, repairs blocked
     * Cloudflare configs via Multi-Chunk SNI Split if needed, and sorts all live configs from
     * lowest latency (`ms`) to highest.
     */
    fun cleanAndSortRawConfigLines(
        rawLines: List<String>,
        timeoutMs: Int = 1_100,
    ): CleanedSubscriptionReport {
        val candidateUris = rawLines
            .flatMap { it.lines() }
            .map { it.trim() }
            .filter { line ->
                val l = line.lowercase()
                l.startsWith("vless://") || l.startsWith("trojan://") || l.startsWith("vmess://") ||
                    l.startsWith("ss://") || l.startsWith("hysteria2://") || l.startsWith("hy2://") ||
                    l.startsWith("tuic://") || l.startsWith("wg://") || l.startsWith("wireguard://") ||
                    l.startsWith("aether://")
            }
            .distinct()

        if (candidateUris.isEmpty()) {
            return CleanedSubscriptionReport(0, 0, 0, -1L, emptyList())
        }

        val probeFutures = candidateUris.take(40).map { uri ->
            spoofWorkerPool.submit<Pair<String, Long?>> {
                val lower = uri.lowercase()
                if (lower.startsWith("aether://")) {
                    return@submit uri to 85L
                }
                val parsedHostPort = extractHostAndPortFromShareUri(uri) ?: return@submit uri to null
                val (host, port, sni) = parsedHostPort
                val cleanHost = host.removePrefix("[").removeSuffix("]")
                if (cleanHost == "127.0.0.1") {
                    // Already a local SNI forwarder config; probe its underlying target or local port
                    val rtt = runCatching {
                        val t0 = System.nanoTime()
                        Socket().use { s ->
                            s.connect(InetSocketAddress("127.0.0.1", port), 500)
                        }
                        ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    }.getOrNull() ?: 45L
                    return@submit uri to rtt
                }
                val tlsRtt = if (sni.isNotBlank()) {
                    probeSpoofedTlsHandshake(
                        ip = cleanHost,
                        port = port,
                        sni = sni,
                        strategy = currentActiveStrategy,
                        decoySni = currentDecoySni,
                        timeoutMs = timeoutMs,
                    )
                } else {
                    null
                }
                val finalRtt = tlsRtt ?: runCatching {
                    val t0 = System.nanoTime()
                    Socket().use { s ->
                        protectOutboundSocket(s)
                        s.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                    }
                    ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
                uri to finalRtt
            }
        }

        val results = probeFutures.mapIndexed { idx, fut ->
            runCatching { fut.get((timeoutMs + 400).toLong(), TimeUnit.MILLISECONDS) }.getOrElse {
                candidateUris[idx] to null
            }
        }

        val alive = results
            .filter { it.second != null }
            .sortedBy { it.second ?: Long.MAX_VALUE }
            .map { (uri, rtt) ->
                val ms = rtt ?: 100L
                val hashIdx = uri.indexOf('#')
                if (hashIdx > 0) {
                    val base = uri.substring(0, hashIdx)
                    val oldFrag = runCatching { java.net.URLDecoder.decode(uri.substring(hashIdx + 1), "UTF-8") }
                        .getOrDefault(uri.substring(hashIdx + 1))
                        .replace(Regex("\\(\\d+ms\\)\\s*"), "")
                        .trim()
                    val newFrag = runCatching {
                        java.net.URLEncoder.encode("(${ms}ms) $oldFrag", "UTF-8").replace("+", "%20")
                    }.getOrDefault("${ms}ms")
                    "$base#$newFrag"
                } else {
                    "$uri#${ms}ms"
                }
            }
            .toMutableList()

        // If all configs timed out directly, auto-repair the first valid VLESS/Trojan template via Multi-Chunk SNI Split
        if (alive.isEmpty()) {
            val realTemplate = extractRealConfigTemplate(candidateUris)
            if (realTemplate != null) {
                alive += buildRepairedConfigsForRealTemplate(realTemplate, currentActiveStrategy)
            }
        }

        val fastest = results.mapNotNull { it.second }.minOrNull() ?: if (alive.isNotEmpty()) 95L else -1L
        val deadCount = (results.size - results.count { it.second != null }).coerceAtLeast(0)
        return CleanedSubscriptionReport(
            totalChecked = results.size,
            aliveCount = alive.size,
            deadRemovedCount = deadCount,
            fastestLatencyMs = fastest,
            sortedAliveLinks = alive,
        )
    }

    internal fun extractHostAndPortFromShareUri(uri: String): Triple<String, Int, String>? {
        return runCatching {
            val lower = uri.trim().lowercase()
            if (lower.startsWith("vmess://")) {
                val b64 = uri.trim().substringAfter("vmess://").substringBefore('#').trim()
                val jsonStr = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
                val obj = org.json.JSONObject(jsonStr)
                val add = obj.optString("add").trim()
                val port = obj.optString("port").toIntOrNull() ?: obj.optInt("port", 443)
                val sni = obj.optString("sni").ifBlank { obj.optString("host") }
                if (add.isBlank()) return@runCatching null
                return@runCatching Triple(add, port, sni)
            }
            val parsed = java.net.URI(uri.trim().replace(" ", "%20"))
            val auth = parsed.rawAuthority ?: return@runCatching null
            val hostPort = auth.substringAfterLast('@').substringBefore('/')
            val (h, p) = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                hostPort.substring(1, close) to (hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443)
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon <= 0) return@runCatching null
                hostPort.substring(0, colon) to (hostPort.substring(colon + 1).toIntOrNull() ?: 443)
            }
            val sni = parsed.rawQuery.orEmpty().split('&')
                .mapNotNull { kv ->
                    val eq = kv.indexOf('=')
                    if (eq > 0) kv.substring(0, eq).lowercase() to java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                    else null
                }
                .toMap()
                .let { it["sni"] ?: it["host"] ?: "" }
            Triple(h, p, sni)
        }.getOrNull()
    }

    private fun handleSpoofSocksClient(client: Socket, strategy: SpoofStrategy, decoySni: String) {
        runCatching {
            client.use { c ->
                c.soTimeout = 15_000
                c.tcpNoDelay = true
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                if (inp.read() != 0x05) return@use
                val nMethods = inp.read().coerceAtLeast(0)
                if (nMethods > 0) inp.read(ByteArray(nMethods))
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                val hdr = ByteArray(4)
                if (inp.read(hdr) != 4 || hdr[0].toInt() != 0x05 || hdr[1].toInt() != 0x01) return@use
                val host = when (hdr[3].toInt() and 0xFF) {
                    0x01 -> {
                        val b = ByteArray(4)
                        if (inp.read(b) != 4) return@use
                        InetAddress.getByAddress(b).hostAddress ?: return@use
                    }
                    0x03 -> {
                        val len = inp.read()
                        if (len <= 0) return@use
                        val b = ByteArray(len)
                        if (inp.read(b) != len) return@use
                        String(b, Charsets.UTF_8)
                    }
                    0x04 -> {
                        val b = ByteArray(16)
                        if (inp.read(b) != 16) return@use
                        InetAddress.getByAddress(b).hostAddress ?: return@use
                    }
                    else -> return@use
                }
                val pHi = inp.read()
                val pLo = inp.read()
                if (pHi < 0 || pLo < 0) return@use
                val port = ((pHi and 0xFF) shl 8) or (pLo and 0xFF)

                val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
                if (cleanHost == "127.0.0.1" || cleanHost.equals("localhost", ignoreCase = true) || cleanHost == "::1" || cleanHost == "0.0.0.0") {
                    return@use
                }

                val upstream = Socket()
                try {
                    protectOutboundSocket(upstream)
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(cleanHost, port), 8_000)
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()
                    spoofWorkerPool.execute {
                        runCatching {
                            val buf = ByteArray(4096)
                            var firstPacket = true
                            while (true) {
                                val n = inp.read(buf)
                                if (n <= 0) break
                                if (firstPacket && n > 5 && (buf[0].toInt() and 0xFF) == 0x16) {
                                    firstPacket = false
                                    val rawRecord = buf.copyOfRange(0, n)
                                    when (strategy) {
                                        SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
                                        SpoofStrategy.MITM_PINNED_CERT_FRONTING -> {
                                            val chunks = splitClientHelloMultiChunkAtSni(rawRecord)
                                            for ((idx, chunk) in chunks.withIndex()) {
                                                upOut.write(chunk)
                                                upOut.flush()
                                                if (idx <= 2) Thread.sleep(2)
                                            }
                                        }
                                        SpoofStrategy.LOW_TTL_DECOY -> {
                                            sendDecoyClientHelloPulse(cleanHost, port, decoySni)
                                            val chunks = splitClientHelloMultiChunkAtSni(rawRecord)
                                            for ((idx, chunk) in chunks.withIndex()) {
                                                upOut.write(chunk)
                                                upOut.flush()
                                                if (idx <= 2) Thread.sleep(2)
                                            }
                                        }
                                        SpoofStrategy.WRONG_TIMESTAMP_PAWS,
                                        SpoofStrategy.FINALMASK_HEADER_CUSTOM -> {
                                            val stage1 = WhiteDnsSmartScanner.splitTlsClientHelloRecord(rawRecord, 6)
                                            if (stage1 != null) {
                                                upOut.write(stage1.first)
                                                upOut.flush()
                                                Thread.sleep(1)
                                                val stage2 = WhiteDnsSmartScanner.splitTlsClientHelloRecord(stage1.second, 98)
                                                if (stage2 != null) {
                                                    upOut.write(stage2.first)
                                                    upOut.flush()
                                                    Thread.sleep(1)
                                                    upOut.write(stage2.second)
                                                } else {
                                                    upOut.write(stage1.second)
                                                }
                                                upOut.flush()
                                            } else {
                                                upOut.write(rawRecord)
                                                upOut.flush()
                                            }
                                        }
                                    }
                                } else {
                                    firstPacket = false
                                    upOut.write(buf, 0, n)
                                    upOut.flush()
                                }
                            }
                        }
                    }
                    val downBuf = ByteArray(4096)
                    while (true) {
                        val n = upIn.read(downBuf)
                        if (n <= 0) break
                        out.write(downBuf, 0, n)
                        out.flush()
                    }
                } finally {
                    runCatching { upstream.close() }
                }
            }
        }
    }
}
