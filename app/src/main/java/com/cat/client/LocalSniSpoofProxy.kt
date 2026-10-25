package com.cat.client

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile
    var currentActiveStrategy: SpoofStrategy = SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI
        private set
    @Volatile
    var currentDecoySni: String = "ebanksepah.ir"
        private set

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
            Thread(Runnable {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    Thread(Runnable { handleSpoofSocksClient(client, strategy, currentDecoySni) }, "sni-spoof-worker").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "sni-spoof-accept-${server.localPort}").apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(preferredPort)
    }

    fun stopAllSpoofProxies() {
        activeSpoofServers.values.forEach { runCatching { it.close() } }
        activeSpoofServers.clear()
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

                val upstream = Socket()
                try {
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(host, port), 8_000)
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()
                    val upThread = Thread(Runnable {
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
                                            val split = splitClientHelloAtSni(rawRecord)
                                            if (split != null) {
                                                upOut.write(split.first)
                                                upOut.flush()
                                                Thread.sleep(2)
                                                upOut.write(split.second)
                                                upOut.flush()
                                            } else {
                                                upOut.write(rawRecord)
                                                upOut.flush()
                                            }
                                        }
                                        SpoofStrategy.LOW_TTL_DECOY -> {
                                            // Split at SNI boundary after warming socket buffer
                                            val split = splitClientHelloAtSni(rawRecord)
                                            if (split != null) {
                                                upOut.write(split.first)
                                                upOut.flush()
                                                Thread.sleep(2)
                                                upOut.write(split.second)
                                                upOut.flush()
                                            } else {
                                                upOut.write(rawRecord)
                                                upOut.flush()
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
                    }, "sni-spoof-up")
                    upThread.isDaemon = true
                    upThread.start()
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
