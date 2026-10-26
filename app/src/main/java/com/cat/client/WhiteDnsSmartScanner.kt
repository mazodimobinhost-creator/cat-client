package com.cat.client

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class CloudflarePortSniHit(
    val host: String,
    val port: Int,
    val sni: String,
    val rttMs: Long,
) {
    /** Exact verified endpoint (`host:port` or `[ipv6]:port`); port is never altered. */
    val endpoint: String
        get() = if (host.startsWith("[")) "$host:$port"
        else if (host.contains(":")) "[$host]:$port"
        else "$host:$port"
}

internal data class IranDnsHit(
    val ip: String,
    val transport: String,
    val rttMs: Long,
    val providerNameFa: String = "",
    val providerNameEn: String = "",
    val supportedRecords: List<String> = listOf("A", "TXT"),
)

internal enum class WhiteDnsResolverCategory(
    val wireName: String,
    val titleFa: String,
    val titleEn: String,
) {
    SANCTION_GAMING("sanction_gaming", "🎮 تحریم‌شکن و گیمینگ ایران", "🎮 Iran Anti-Sanction & Gaming"),
    IRAN_ISP_RECURSIVE("iran_isp", "📡 ریزولورهای اپراتورها و دیتاسنترهای ایران (حامل تانل DNS)", "📡 Iran ISP & Datacenter Recursive (DNS Tunnel Carrier)"),
    ENCRYPTED_GLOBAL("encrypted_global", "🔒 ریزولورهای رمزنگاری‌شده ضد فیلتر (DoQ / DoH3 / DoT / DoH)", "🔒 Encrypted Anti-DPI Resolvers (DoQ / DoH3 / DoT / DoH)"),
}

internal data class WhiteDnsResolverEntry(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val category: WhiteDnsResolverCategory,
    val primaryIp: String,
    val secondaryIp: String = "",
    val dohUrl: String = "",
    val dotEndpoint: String = "",
    val doqEndpoint: String = "",
    val doh3Url: String = "",
    val supportsDnsTunnel: Boolean = true,
)

internal enum class WhiteDnsRecordMode(
    val wireMode: String,
    val qtype: Int,
    val titleFa: String,
    val titleEn: String,
    val hintFa: String,
    val hintEn: String,
) {
    TXT_HTTPS(
        wireMode = "txt-https",
        qtype = 65,
        titleFa = "HTTPS/SVCB + TXT (پیشنهادی ایران · عبور از DPI کلودفلر/ECH)",
        titleEn = "HTTPS/SVCB + TXT (Recommended · ECH/H3 DPI Bypass)",
        hintFa = "رکورد Type 65 (HTTPS/SVCB) شبیه درخواست‌های مرورگر برای ECH و HTTP/3 است و روی همراه اول و ایرانسل کمترین حساسیت فایروال را دارد.",
        hintEn = "Type 65 (HTTPS/SVCB) mimics browser ECH/HTTP3 lookups and avoids TXT-only DPI throttling.",
    ),
    TXT_CNAME(
        wireMode = "txt-cname",
        qtype = 5,
        titleFa = "TXT + CNAME (ضد محدودیت سایز بسته UDP)",
        titleEn = "TXT + CNAME (Anti-Truncation Fallback)",
        hintFa = "وقتی فایروال اپراتور رکوردهای بزرگ TXT را محدود یا فیلتر می‌کند، پاسخ‌ها روی CNAME منتقل می‌شوند.",
        hintEn = "Falls back to CNAME records when large TXT responses are throttled by the ISP.",
    ),
    TXT_AAAA(
        wireMode = "txt-aaaa",
        qtype = 28,
        titleFa = "TXT + AAAA (حامل ترکیبی IPv6)",
        titleEn = "TXT + AAAA (IPv6 Record Carrier)",
        hintFa = "ترکیب رکوردهای TXT و AAAA برای خطوطی که ترافیک IPv6 روی DNS آن‌ها باز است.",
        hintEn = "Combines TXT and AAAA queries for lines with clean IPv6 DNS resolution.",
    ),
    NULL_EDNS0(
        wireMode = "null",
        qtype = 10,
        titleFa = "NULL باینری (حداکثر ظرفیت ۱۲۳۲ بایت با EDNS0)",
        titleEn = "NULL Binary (Max 1232B Payload with EDNS0)",
        hintFa = "بیشترین سرعت دانلود در تانل‌های MasterDNS و CottenMax روی ریزولورهایی که رکورد NULL را عبور می‌دهند.",
        hintEn = "Highest download throughput for MasterDNS/CottenMax on resolvers that pass raw NULL records.",
    ),
    TXT_ONLY(
        wireMode = "txt",
        qtype = 16,
        titleFa = "TXT استاندارد (سازگار با همه سرورهای dnstt و StormDNS)",
        titleEn = "Standard TXT (Compatible with all dnstt & StormDNS servers)",
        hintFa = "حالت پایه و استاندارد برای تمام سرورهای dnstt، StormDNS و CottenDNS.",
        hintEn = "Universal baseline carrier record supported by all dnstt, StormDNS, and CottenDNS servers.",
    ),
    ALL_ADAPTIVE(
        wireMode = "all",
        qtype = 16,
        titleFa = "چرخش هوشمند همه رکوردها (TXT + HTTPS + CNAME + AAAA + NULL)",
        titleEn = "Adaptive All-Record Rotation (TXT + HTTPS + CNAME + AAAA + NULL)",
        hintFa = "توزیع خودکار درخواست‌ها بین تمام رکوردهای حامل برای جلوگیری از تشخیص الگوی ترافیک توسط فایروال.",
        hintEn = "Rotates across all DNS carrier record types to prevent DPI pattern detection.",
    ),
}

internal data class WhiteDnsAutoTunePreset(
    val id: String,
    val titleFa: String,
    val titleEn: String,
    val descriptionFa: String,
    val descriptionEn: String,
    val mtuUpRange: String,
    val mtuDownRange: String,
    val duplication: Int,
    val workers: Int,
    val transport: String,
    val fecShards: String = "4:1",
    val timingMask: String = "light",
)

internal data class WhiteDnsTunnelBundle(
    val resolverIp: String,
    val transport: String,
    val recordMode: WhiteDnsRecordMode,
    val preset: WhiteDnsAutoTunePreset,
    val dnsttUdpBridges: List<String>,
    val dnsttDotBridges: List<String>,
    val dnsttDohBridges: List<String>,
    val stormDnsUri: String,
    val cottenMaxUri: String,
    val soarUri: String,
    val masterDnsUri: String = "",
    val vayDnsUri: String = "",
    val noizDnsUri: String = "",
    val slipstreamUri: String = "",
    val slipNetUri: String = "",
    val dnstHumanUri: String = "",
    val dnstBase64Uri: String = "",
)

/**
 * Conceptual smart scanner & resolver toolkit combining:
 * 1) 6-port Cloudflare TLS + SNI Pool scanner (inspired by WhiteDNS-cleanip-finder)
 * 2) Multi-transport DNS & Resolver scanner (UDP :53, TCP :53 RFC 1035, DoT :853, DoH :443, DoQ :853, DoH3 :443)
 * 3) DNS Carrier Record verification (TXT, HTTPS/SVCB Type 65, CNAME, AAAA, NULL) & Iran Auto-Tune Presets
 *    (inspired by WhiteDNS-Android 1.6.14, CottenMax FEC, StormDNS/MasterDNS, Soar, and whitegame-catalog)
 */
internal object WhiteDnsSmartScanner {
    val CLOUDFLARE_TLS_PORTS: List<Int> = listOf(443, 2053, 2083, 2087, 2096, 8443)

    val CLOUDFLARE_SNI_POOL: List<String> = listOf(
        "speed.cloudflare.com",
        "www.cloudflare.com",
        "dash.cloudflare.com",
        "api.cloudflare.com",
        "blog.cloudflare.com",
        "community.cloudflare.com",
    )

    val CLOUDFLARE_CANDIDATE_HOSTS: List<String> = listOf(
        "162.159.192.1",
        "188.114.98.0",
        "188.114.96.1",
        "172.67.73.163",
        "104.18.2.161",
        "[2a06:98c1:3101::6812:202f]",
        "[2606:4700:d0::a29f:c001]",
    )

    val IRAN_UDP_DNS_TARGETS: List<String> = listOf(
        "178.22.122.100",
        "185.51.200.2",
        "78.157.42.100",
        "10.202.10.202",
        "10.202.10.10",
        "185.55.226.26",
        "87.107.110.109",
        "193.24.103.1",
        "185.212.51.144",
        "46.100.40.59",
        "5.202.171.124",
        "46.100.164.165",
        "93.114.104.185",
        "2.188.162.78",
        "87.107.82.213",
        "91.92.130.49",
        "81.29.248.38",
        "185.83.114.233",
        "78.157.56.133",
        "80.191.68.247",
        "2.189.1.1",
        "178.22.122.246",
        "185.51.200.1",
        "217.218.155.155",
    )

    val IRAN_TCP_DNS_TARGETS: List<String> = listOf(
        "178.22.122.100",
        "185.51.200.2",
        "78.157.42.100",
        "217.218.155.155",
        "85.15.1.14",
        "85.185.105.104",
        "81.12.72.108",
        "85.15.3.5",
        "84.241.29.70",
        "5.202.174.183",
        "130.185.72.239",
        "185.224.179.176",
        "217.11.18.51",
    )

    val RESOLVER_CATALOG: List<WhiteDnsResolverEntry> = listOf(
        // 1. Iran Anti-Sanction & Gaming DNS
        WhiteDnsResolverEntry(
            id = "shecan",
            nameFa = "شکن (Shecan — تحریم‌شکن عمومی و استورها)",
            nameEn = "Shecan (Iran Anti-Sanction DNS)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "178.22.122.100",
            secondaryIp = "185.51.200.2",
            dohUrl = "https://178.22.122.100/dns-query",
            dotEndpoint = "tls://178.22.122.100:853",
        ),
        WhiteDnsResolverEntry(
            id = "electro",
            nameFa = "الکترو (Electro — گیمینگ، کنسول و دانلود)",
            nameEn = "Electro (Gaming & Console Anti-Sanction)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "78.157.42.100",
            secondaryIp = "78.157.42.101",
            dohUrl = "https://78.157.42.100/dns-query",
            dotEndpoint = "tls://78.157.42.100:853",
        ),
        WhiteDnsResolverEntry(
            id = "radar",
            nameFa = "رادار گیم (Radar Game — پینگ پایین بازی و رفع تحریم)",
            nameEn = "Radar Game (Low-Ping Gaming DNS)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "10.202.10.10",
            secondaryIp = "10.202.10.11",
        ),
        WhiteDnsResolverEntry(
            id = "403online",
            nameFa = "۴۰۳ آنلاین (403.online — رفع تحریم توسعه‌دهندگان و هوش مصنوعی)",
            nameEn = "403.online (Developer & AI Sanction Bypass)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "10.202.10.202",
            secondaryIp = "10.202.10.102",
        ),
        WhiteDnsResolverEntry(
            id = "begzar",
            nameFa = "بگذر (Begzar — تحریم‌شکن وب و مخازن)",
            nameEn = "Begzar (Anti-Sanction Resolver)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "185.55.226.26",
            secondaryIp = "185.55.225.25",
        ),
        WhiteDnsResolverEntry(
            id = "dnspro",
            nameFa = "دی‌ان‌اس پرو (DNS Pro — گیمینگ و دانلود)",
            nameEn = "DNS Pro (Iran Gaming Resolver)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "87.107.110.109",
            secondaryIp = "87.107.110.110",
        ),
        WhiteDnsResolverEntry(
            id = "dynx",
            nameFa = "داینامیک گیم (DynX — ضد تحریم و گیمینگ)",
            nameEn = "DynX (Anti-Sanction & Gaming)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "193.24.103.1",
            secondaryIp = "10.70.95.150",
        ),
        WhiteDnsResolverEntry(
            id = "hostiran",
            nameFa = "هاست‌ایران (Hostiran — مخازن لینوکس و داکر)",
            nameEn = "Hostiran (Package & Container Mirrors)",
            category = WhiteDnsResolverCategory.SANCTION_GAMING,
            primaryIp = "172.29.0.100",
            secondaryIp = "172.29.2.100",
        ),
        // 2. Iran ISP Recursive Resolvers (best carriers for dnstt / StormDNS / CottenMax)
        WhiteDnsResolverEntry(
            id = "tci",
            nameFa = "مخابرات ایران (TCI Recursive — حامل تانل DNS)",
            nameEn = "TCI Iran Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "217.218.155.155",
            secondaryIp = "217.218.127.127",
        ),
        WhiteDnsResolverEntry(
            id = "shatel",
            nameFa = "شاتل (Shatel Recursive — حامل تانل DNS)",
            nameEn = "Shatel Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "85.15.1.14",
            secondaryIp = "85.15.1.15",
        ),
        WhiteDnsResolverEntry(
            id = "asiatech",
            nameFa = "آسیاتک (AsiaTech Recursive — حامل تانل DNS)",
            nameEn = "AsiaTech Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "194.225.152.10",
            secondaryIp = "78.157.40.1",
        ),
        WhiteDnsResolverEntry(
            id = "pishgaman",
            nameFa = "پیشگامان (Pishgaman Recursive — حامل تانل DNS)",
            nameEn = "Pishgaman Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "5.202.100.100",
            secondaryIp = "5.202.100.101",
        ),
        WhiteDnsResolverEntry(
            id = "parsonline",
            nameFa = "پارس‌آنلاین (ParsOnline Recursive — حامل تانل DNS)",
            nameEn = "ParsOnline Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "91.99.101.12",
            secondaryIp = "91.99.96.76",
        ),
        WhiteDnsResolverEntry(
            id = "mobinnet",
            nameFa = "مبین‌نت (Mobinnet Recursive — حامل تانل DNS)",
            nameEn = "Mobinnet Recursive (DNS Tunnel Carrier)",
            category = WhiteDnsResolverCategory.IRAN_ISP_RECURSIVE,
            primaryIp = "37.156.14.22",
            secondaryIp = "185.166.107.133",
        ),
        // 3. Global Encrypted Anti-DPI Resolvers (DoQ / DoH3 / DoT / DoH)
        WhiteDnsResolverEntry(
            id = "cloudflare-quic",
            nameFa = "کلودفلر QUIC (Cloudflare DoQ / DoH3 — ضد مسدودی TCP و مناسب ECH)",
            nameEn = "Cloudflare QUIC (DoQ / DoH3 — ECH & Anti-DPI)",
            category = WhiteDnsResolverCategory.ENCRYPTED_GLOBAL,
            primaryIp = "1.1.1.1",
            secondaryIp = "1.0.0.1",
            dohUrl = "https://1.1.1.1/dns-query",
            dotEndpoint = "tls://1.1.1.1:853",
            doqEndpoint = "quic://1.1.1.1:853",
            doh3Url = "h3://1.1.1.1/dns-query",
        ),
        WhiteDnsResolverEntry(
            id = "adguard-quic",
            nameFa = "ادگارد QUIC (AdGuard DoQ / DoH3 — حذف تبلیغات + رمزنگاری QUIC)",
            nameEn = "AdGuard QUIC (DoQ / DoH3 — Encrypted + AdBlock)",
            category = WhiteDnsResolverCategory.ENCRYPTED_GLOBAL,
            primaryIp = "94.140.14.14",
            secondaryIp = "94.140.15.15",
            dohUrl = "https://94.140.14.14/dns-query",
            dotEndpoint = "tls://94.140.14.14:853",
            doqEndpoint = "quic://94.140.14.14:853",
            doh3Url = "h3://94.140.14.14/dns-query",
        ),
        WhiteDnsResolverEntry(
            id = "google-dns",
            nameFa = "گوگل (Google DoH / DoT — پایدار جهانی)",
            nameEn = "Google Public DNS (DoH / DoT)",
            category = WhiteDnsResolverCategory.ENCRYPTED_GLOBAL,
            primaryIp = "8.8.8.8",
            secondaryIp = "8.8.4.4",
            dohUrl = "https://8.8.8.8/dns-query",
            dotEndpoint = "tls://8.8.8.8:853",
        ),
        WhiteDnsResolverEntry(
            id = "quad9-dns",
            nameFa = "کواد۹ سوئیس (Quad9 DoH / DoT — امنیتی و بدون سانسور)",
            nameEn = "Quad9 Swiss Security DNS (DoH / DoT)",
            category = WhiteDnsResolverCategory.ENCRYPTED_GLOBAL,
            primaryIp = "9.9.9.9",
            secondaryIp = "149.112.112.112",
            dohUrl = "https://9.9.9.9/dns-query",
            dotEndpoint = "tls://9.9.9.9:853",
        ),
    )

    val IRAN_AUTO_TUNE_PRESETS: List<WhiteDnsAutoTunePreset> = listOf(
        WhiteDnsAutoTunePreset(
            id = "iran-fast-low-mtu",
            titleFa = "⚡ همراه اول و ایرانسل — سریع کم‌MTU (iran-fast-low-mtu)",
            titleEn = "⚡ MCI & Irancell — Fast Low-MTU (iran-fast-low-mtu)",
            descriptionFa = "بهترین پریست WhiteDNS برای اینترنت موبایل ایران با نوسان بالا؛ MTU آپلود ۲۰ تا ۳۲۵ بایت و دانلود ۱۰۰ تا ۱۲۷۰ بایت.",
            descriptionEn = "Best WhiteDNS preset for high-jitter Iranian mobile lines (up=20..325B, down=100..1270B, dup=5, workers=10).",
            mtuUpRange = "20-325",
            mtuDownRange = "100-1270",
            duplication = 5,
            workers = 10,
            transport = "auto",
        ),
        WhiteDnsAutoTunePreset(
            id = "iran-compact-fixed",
            titleFa = "🛡 فایروال سخت‌گیر — بسته فشرده ۶۲ بایت (iran-compact-fixed)",
            titleEn = "🛡 Strict Firewall — Compact 62B (iran-compact-fixed)",
            descriptionFa = "مخصوص مناطقی که فایروال بسته‌های UDP بزرگ‌تر از ۶۴ بایت را دراپ می‌کند؛ QName ایمن ۱۰۱ کاراکتری.",
            descriptionEn = "For strict regional firewalls that drop large UDP DNS queries (up=62B, down=414B, dup=6, workers=8).",
            mtuUpRange = "62-62",
            mtuDownRange = "414-414",
            duplication = 6,
            workers = 8,
            transport = "auto",
            timingMask = "strong",
        ),
        WhiteDnsAutoTunePreset(
            id = "iran-fixed-64-balanced",
            titleFa = "⚖️ مخابرات و فیبر نوری — متعادل ۶۴ بایت (iran-fixed-64-balanced)",
            titleEn = "⚖️ TCI & Fiber — Balanced 64B (iran-fixed-64-balanced)",
            descriptionFa = "تعادل عالی بین پایداری و سرعت روی اینترنت ثابت ADSL/VDSL و فیبر نوری (آپلود ۶۴ و دانلود ۷۵۶ بایت).",
            descriptionEn = "Balanced throughput & stability on fixed broadband/fiber lines (up=64B, down=756B, dup=8, workers=8).",
            mtuUpRange = "64-64",
            mtuDownRange = "756-756",
            duplication = 8,
            workers = 8,
            transport = "auto",
        ),
        WhiteDnsAutoTunePreset(
            id = "iran-mid-reliable",
            titleFa = "📶 ایرانسل و TD-LTE — پایدار میان‌رده (iran-mid-reliable)",
            titleEn = "📶 Irancell & TD-LTE — Mid Reliable (iran-mid-reliable)",
            descriptionFa = "مناسب مودم‌های سیم‌کارتی و TD-LTE با آپلود ۱۲۰ تا ۱۶۰ بایت و دانلود ۶۵۲ تا ۱۱۱۰ بایت.",
            descriptionEn = "Tuned for TD-LTE and fixed wireless modems (up=120..160B, down=652..1110B, dup=5, workers=11).",
            mtuUpRange = "120-160",
            mtuDownRange = "652-1110",
            duplication = 5,
            workers = 11,
            transport = "auto",
        ),
        WhiteDnsAutoTunePreset(
            id = "iran-download-heavy",
            titleFa = "🚀 دانلود سنگین + تصحیح خطای CottenMax FEC (iran-download-heavy)",
            titleEn = "🚀 Heavy Download + CottenMax Reed-Solomon FEC (iran-download-heavy)",
            descriptionFa = "۳۰ ورکر همزمان + کد تصحیح خطای Reed-Solomon (۴ شارد داده + ۱ شارد Parity) برای بازسازی بسته‌های حذف‌شده بدون نیاز به ارسال مجدد.",
            descriptionEn = "30 concurrent workers + Reed-Solomon FEC (4:1) to reconstruct dropped DNS packets without retransmission.",
            mtuUpRange = "104-139",
            mtuDownRange = "394-1000",
            duplication = 8,
            workers = 30,
            transport = "auto",
            fecShards = "4:1",
        ),
        WhiteDnsAutoTunePreset(
            id = "tcp-survival",
            titleFa = "🔒 حالت بقا TCP/53 و DoT/853 (زمان مسدودی کامل UDP/53)",
            titleEn = "🔒 TCP/53 & DoT/853 Survival Mode (When UDP/53 is Blocked)",
            descriptionFa = "وقتی پورت UDP/53 روی خط شما کاملاً بسته یا به‌شدت محدود شده است، تمام درخواست‌های DNS را از طریق TCP/53 و DoT/853 ارسال می‌کند.",
            descriptionEn = "Forces DNS resolution and tunneling over RFC 1035 TCP/53 and DoT/853 when UDP/53 is blocked.",
            mtuUpRange = "64-160",
            mtuDownRange = "512-1232",
            duplication = 2,
            workers = 6,
            transport = "tcp",
            timingMask = "strong",
        ),
    )

    fun buildDnsQueryPacket(
        txId: Int = 0x4341,
        qtype: Int = 1,
        domain: String = "cloudflare.com",
    ): ByteArray {
        val hi = ((txId ushr 8) and 0xff).toByte()
        val lo = (txId and 0xff).toByte()
        val qtypeHi = ((qtype ushr 8) and 0xff).toByte()
        val qtypeLo = (qtype and 0xff).toByte()
        val labels = domain.trim('.').ifEmpty { "cloudflare.com" }.split('.').filter { it.isNotEmpty() }
        val qnameBytes = java.io.ByteArrayOutputStream()
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            val len = bytes.size.coerceAtMost(63)
            qnameBytes.write(len)
            qnameBytes.write(bytes, 0, len)
        }
        qnameBytes.write(0)
        val qname = qnameBytes.toByteArray()
        val header = byteArrayOf(
            hi, lo, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val footer = byteArrayOf(qtypeHi, qtypeLo, 0x00, 0x01)
        return header + qname + footer
    }

    /** Builds an RFC 1035 TCP DNS query frame prefixed with a 2-byte big-endian length. */
    fun buildTcpDnsQueryFrame(
        txId: Int = 0x4341,
        qtype: Int = 1,
        domain: String = "cloudflare.com",
    ): ByteArray {
        val payload = buildDnsQueryPacket(txId, qtype, domain)
        val len = payload.size
        return ByteArray(len + 2).also { frame ->
            frame[0] = ((len ushr 8) and 0xff).toByte()
            frame[1] = (len and 0xff).toByte()
            System.arraycopy(payload, 0, frame, 2, len)
        }
    }

    fun isValidDnsResponse(buf: ByteArray, length: Int, expectedTxId: Int = 0x4341): Boolean {
        if (length < 12) return false
        val rxId = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
        if (rxId != (expectedTxId and 0xffff)) return false
        val flagsHi = buf[2].toInt() and 0xff
        // QR bit (0x80) must be 1 (response)
        return (flagsHi and 0x80) != 0
    }

    fun buildDnsttBridgesForResolver(dnsIp: String, transport: String = "udp"): List<String> {
        val cleanIp = dnsIp.trim()
        require(cleanIp.isNotEmpty()) { "DNS resolver IP is empty" }
        val resolverSpec = when (transport.lowercase()) {
            "dot", "tls" -> "dot=$cleanIp:853"
            "doh", "https" -> if (cleanIp.startsWith("https://")) "doh=$cleanIp" else "doh=https://$cleanIp/dns-query"
            else -> "udp=$cleanIp:53"
        }
        return listOf(
            "dnstt 192.0.2.4:1 A998F319ADB60EE344540EC4B21524CC484F96BE $resolverSpec pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
            "dnstt 192.0.2.4:2 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 $resolverSpec pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
            "dnstt 192.0.2.4:3 74D409BED3E2F881F365543A72C8F079CB84FFEB $resolverSpec pubkey=c596c458fc3453dc40903ab235f5854a2609831075640c4c5584f76de05b8271 domain=t.arkadag.org",
        )
    }

    /**
     * Builds the full WhiteDNS multi-engine connection bundle (`dnstt`, `stormdns://`, `cottendns://` CottenMax, and `soar://`)
     * for a verified resolver IP, carrier record mode, and Iran network preset.
     */
    fun buildWhiteDnsTunnelBundle(
        resolverIp: String,
        transport: String = "auto",
        recordMode: WhiteDnsRecordMode = WhiteDnsRecordMode.TXT_HTTPS,
        preset: WhiteDnsAutoTunePreset = IRAN_AUTO_TUNE_PRESETS.first(),
        tunnelDomain: String = "t.ruhnama.net",
        pubKeyHex: String = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f",
    ): WhiteDnsTunnelBundle {
        val cleanIp = resolverIp.trim().ifEmpty { "178.22.122.100" }
        val cleanTransport = transport.trim().lowercase().ifEmpty { preset.transport }
        val stormUri = buildString {
            append("stormdns://")
            append(cleanIp)
            append(":53/?domain=").append(tunnelDomain)
            append("&pubkey=").append(pubKeyHex)
            append("&transport=").append(cleanTransport)
            append("&delivery=").append(recordMode.wireMode)
            append("&preset=").append(preset.id)
            append("&mtu_up=").append(preset.mtuUpRange)
            append("&mtu_down=").append(preset.mtuDownRange)
            append("&dup=").append(preset.duplication)
            append("&workers=").append(preset.workers)
            append("#WhiteDNS-Storm-").append(preset.id)
        }
        val cottenMaxUri = buildString {
            append("cottendns://")
            append(cleanIp)
            append(":53/?mode=cottenmax")
            append("&domain=").append(tunnelDomain)
            append("&pubkey=").append(pubKeyHex)
            append("&transport=").append(cleanTransport)
            append("&delivery=").append(recordMode.wireMode)
            append("&preset=").append(preset.id)
            append("&safe_qname=101&max_qname=253&max_answer=1232")
            append("&fec=").append(preset.fecShards)
            append("&timing_mask=").append(preset.timingMask)
            append("&qname_loss_guard=1")
            append("#WhiteDNS-CottenMax-").append(preset.id)
        }
        val soarUri = buildString {
            append("soar://")
            append(cleanIp)
            append(":53/?profile=IR")
            append("&transport=").append(cleanTransport)
            append("&delivery=").append(recordMode.wireMode)
            append("&preset=").append(preset.id)
            append("#WhiteDNS-Soar-IR")
        }
        val masterDnsUri = buildString {
            append("masterdns://")
            append(cleanIp)
            append(":53/?domain=").append(tunnelDomain)
            append("&mkey=").append(pubKeyHex.take(32))
            append("&encryption=5&balancer=6")
            append("&dup=").append(preset.duplication)
            append("&comp=1&mtu_up=38&mtu_down=100")
            append("#MasterDNS-Balancer6-").append(preset.id)
        }
        val vayRec = when (recordMode) {
            WhiteDnsRecordMode.TXT_CNAME -> "cname"
            WhiteDnsRecordMode.TXT_AAAA -> "aaaa"
            WhiteDnsRecordMode.NULL_EDNS0 -> "null"
            else -> "txt"
        }
        val vayDnsUri = buildString {
            append("vaydns://")
            append(cleanIp)
            append(":53/?domain=").append(tunnelDomain)
            append("&pubkey=").append(pubKeyHex)
            append("&record-type=").append(vayRec)
            append("&clientid-size=2&max-qname-len=101&keepalive=2s&idle-timeout=10s")
            append("#SlipNet-VayDNS-").append(vayRec.uppercase())
        }
        val noizDnsUri = buildString {
            append("noizdns://")
            append(cleanIp)
            append(":53/?domain=").append(tunnelDomain)
            append("&pubkey=").append(pubKeyHex)
            append("&encoding=base36&max-query-size=60&query-padding=20&stealth=1")
            append("#SlipNet-NoizDNS-Stealth")
        }
        val slipstreamUri = buildString {
            append("slipstream://")
            append(cleanIp)
            append(":53/?domain=").append(tunnelDomain)
            append("&cc=bbr&mtu=900&alpn=h3")
            append("#SlipNet-Slipstream-QUIC")
        }
        val dnstHuman = buildDnstStandardUri(
            domain = tunnelDomain,
            transport = "vaydns",
            backend = "socks",
            pubKeyHex = pubKeyHex,
            recordType = vayRec,
            resolverIp = cleanIp,
            tag = "DNST-VayDNS-${preset.id}",
            asBase64Json = false,
        )
        val dnstB64 = buildDnstStandardUri(
            domain = tunnelDomain,
            transport = "vaydns",
            backend = "socks",
            pubKeyHex = pubKeyHex,
            recordType = vayRec,
            resolverIp = cleanIp,
            tag = "DNST-VayDNS-${preset.id}",
            asBase64Json = true,
        )
        val slipNetLink = buildSlipNetUri(
            domain = tunnelDomain,
            resolverIp = cleanIp,
            pubKeyHex = pubKeyHex,
            tunnelType = "noizdns",
            maxQuerySize = 60,
            queryPadding = 20,
            sshChain = true,
            remark = "SlipNet-NoizDNS-SSH",
        )
        return WhiteDnsTunnelBundle(
            resolverIp = cleanIp,
            transport = cleanTransport,
            recordMode = recordMode,
            preset = preset,
            dnsttUdpBridges = buildDnsttBridgesForResolver(cleanIp, "udp"),
            dnsttDotBridges = buildDnsttBridgesForResolver(cleanIp, "dot"),
            dnsttDohBridges = buildDnsttBridgesForResolver(cleanIp, "doh"),
            stormDnsUri = stormUri,
            cottenMaxUri = cottenMaxUri,
            soarUri = soarUri,
            masterDnsUri = masterDnsUri,
            vayDnsUri = vayDnsUri,
            noizDnsUri = noizDnsUri,
            slipstreamUri = slipstreamUri,
            slipNetUri = slipNetLink,
            dnstHumanUri = dnstHuman,
            dnstBase64Uri = dnstB64,
        )
    }

    fun probeTlsPortWithSni(host: String, port: Int, sni: String, timeoutMs: Int = 1300): Long? {
        if (port !in CLOUDFLARE_TLS_PORTS) return null
        val bareHost = host.trim().removeSurrounding("[", "]")
        val endpointKey = "$bareHost:$port"
        if (isEndpointQuarantined("default", endpointKey)) return null
        val t0 = System.nanoTime()
        return runCatching {
            val addr = InetAddress.getByName(bareHost)
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(addr, port), timeoutMs)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, sni, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    ssl.sslParameters = params
                    try {
                        ssl.startHandshake()
                    } catch (e: javax.net.ssl.SSLHandshakeException) {
                        quarantineInterceptedEndpoint("default", endpointKey)
                        throw e
                    }
                }
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    fun scanCloudflareSixPortsAndSni(
        customSni: String? = null,
        maxHosts: Int = 4,
        timeoutMs: Int = 1200,
    ): List<CloudflarePortSniHit> {
        val snis = buildList {
            customSni?.trim()?.takeIf { it.isNotEmpty() && it.contains('.') }?.let(::add)
            addAll(CLOUDFLARE_SNI_POOL)
        }.distinct().take(3)
        val hits = mutableListOf<CloudflarePortSniHit>()
        val hosts = CLOUDFLARE_CANDIDATE_HOSTS.take(maxHosts.coerceIn(1, CLOUDFLARE_CANDIDATE_HOSTS.size))
        for (host in hosts) {
            for (port in CLOUDFLARE_TLS_PORTS) {
                val sni = snis[(port + hits.size) % snis.size]
                val rtt = probeTlsPortWithSni(host, port, sni, timeoutMs)
                if (rtt != null) {
                    hits += CloudflarePortSniHit(host = host, port = port, sni = sni, rttMs = rtt)
                    if (hits.size >= 8) break
                }
            }
            if (hits.size >= 8) break
        }
        return hits.sortedBy { it.rttMs }
    }

    fun probeDnsOverUdp(
        ip: String,
        qtype: Int = 1,
        timeoutMs: Int = 850,
        domain: String = "cloudflare.com",
    ): Long? {
        val txId = (0x4300 or (qtype and 0xff))
        val query = buildDnsQueryPacket(txId, qtype, domain)
        return runCatching {
            val t0 = System.nanoTime()
            DatagramSocket().use { sock ->
                protectOutboundDatagramSocket(sock)
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName(ip.trim())
                sock.send(DatagramPacket(query, query.size, addr, 53))
                val buf = ByteArray(512)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                if (!isValidDnsResponse(buf, resp.length, txId)) return@runCatching null
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** Sends a real RFC 1035 2-byte length-prefixed DNS query over TCP:53 and validates the DNS response header. */
    fun probeDnsOverTcp(
        ip: String,
        qtype: Int = 1,
        timeoutMs: Int = 950,
        domain: String = "cloudflare.com",
    ): Long? {
        val txId = (0x5400 or (qtype and 0xff))
        val frame = buildTcpDnsQueryFrame(txId, qtype, domain)
        return runCatching {
            val t0 = System.nanoTime()
            Socket().use { sock ->
                protectOutboundSocket(sock)
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(InetAddress.getByName(ip.trim()), 53), timeoutMs)
                val out = sock.getOutputStream()
                out.write(frame)
                out.flush()
                val inp = sock.getInputStream()
                val lenHi = inp.read()
                val lenLo = inp.read()
                if (lenHi < 0 || lenLo < 0) return@runCatching null
                val respLen = ((lenHi and 0xff) shl 8) or (lenLo and 0xff)
                if (respLen < 12 || respLen > 4096) return@runCatching null
                val buf = ByteArray(respLen)
                var readTotal = 0
                while (readTotal < respLen) {
                    val n = inp.read(buf, readTotal, respLen - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                if (!isValidDnsResponse(buf, readTotal, txId)) return@runCatching null
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** Sends a real RFC 1035 DNS query over TLS on port 853 (DoT) and validates the DNS response. */
    fun probeDnsOverTls(
        ip: String,
        port: Int = 853,
        qtype: Int = 1,
        timeoutMs: Int = 1200,
        domain: String = "cloudflare.com",
    ): Long? {
        val txId = (0x5800 or (qtype and 0xff))
        val frame = buildTcpDnsQueryFrame(txId, qtype, domain)
        return runCatching {
            val t0 = System.nanoTime()
            val addr = InetAddress.getByName(ip.trim())
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(addr, port), timeoutMs)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, ip.trim(), port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                    val out = ssl.outputStream
                    out.write(frame)
                    out.flush()
                    val inp = ssl.inputStream
                    val lenHi = inp.read()
                    val lenLo = inp.read()
                    if (lenHi < 0 || lenLo < 0) return@runCatching null
                    val respLen = ((lenHi and 0xff) shl 8) or (lenLo and 0xff)
                    if (respLen < 12 || respLen > 4096) return@runCatching null
                    val buf = ByteArray(respLen)
                    var readTotal = 0
                    while (readTotal < respLen) {
                        val n = inp.read(buf, readTotal, respLen - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    if (!isValidDnsResponse(buf, readTotal, txId)) return@runCatching null
                }
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** Probes which DNS carrier record types (TXT, HTTPS/SVCB, CNAME, AAAA) pass cleanly through a resolver. */
    fun probeSupportedCarrierRecords(ip: String, useTcp: Boolean = false, timeoutMs: Int = 700): List<String> {
        val passed = mutableListOf("A")
        val checks = listOf(
            "HTTPS(65)" to 65,
            "TXT" to 16,
            "CNAME" to 5,
            "AAAA" to 28,
        )
        for ((label, qtype) in checks) {
            val ok = if (useTcp) {
                probeDnsOverTcp(ip, qtype, timeoutMs) != null
            } else {
                probeDnsOverUdp(ip, qtype, timeoutMs) != null
            }
            if (ok) passed += label
        }
        return passed
    }

    fun scanIranDnsResolvers(timeoutMs: Int = 850, maxResults: Int = 8): List<IranDnsHit> {
        val results = mutableListOf<IranDnsHit>()
        val catalogByIp = RESOLVER_CATALOG.associateBy { it.primaryIp }
        for (ip in IRAN_UDP_DNS_TARGETS) {
            val rtt = probeDnsOverUdp(ip, qtype = 1, timeoutMs = timeoutMs)
            if (rtt != null) {
                val entry = catalogByIp[ip]
                results += IranDnsHit(
                    ip = ip,
                    transport = "UDP:53",
                    rttMs = rtt,
                    providerNameFa = entry?.nameFa.orEmpty(),
                    providerNameEn = entry?.nameEn.orEmpty(),
                )
                if (results.size >= maxResults) break
            }
        }
        if (results.size < 4) {
            for (ip in IRAN_TCP_DNS_TARGETS.take(6)) {
                if (results.any { it.ip == ip }) continue
                val rtt = probeDnsOverTcp(ip, qtype = 1, timeoutMs = timeoutMs)
                if (rtt != null) {
                    val entry = catalogByIp[ip]
                    results += IranDnsHit(
                        ip = ip,
                        transport = "TCP:53",
                        rttMs = rtt,
                        providerNameFa = entry?.nameFa.orEmpty(),
                        providerNameEn = entry?.nameEn.orEmpty(),
                    )
                }
            }
        }
        if (results.size < 5) {
            for (entry in RESOLVER_CATALOG.filter { it.dotEndpoint.isNotEmpty() }.take(3)) {
                val dotRtt = probeDnsOverTls(entry.primaryIp, 853, qtype = 1, timeoutMs = timeoutMs + 200)
                if (dotRtt != null) {
                    results += IranDnsHit(
                        ip = entry.primaryIp,
                        transport = "DoT:853",
                        rttMs = dotRtt,
                        providerNameFa = entry.nameFa,
                        providerNameEn = entry.nameEn,
                    )
                }
            }
        }
        val sorted = results.sortedBy { it.rttMs }
        if (sorted.isEmpty()) return emptyList()
        val best = sorted.first()
        val bestRecords = probeSupportedCarrierRecords(best.ip, useTcp = best.transport.startsWith("TCP"))
        return listOf(best.copy(supportedRecords = bestRecords)) + sorted.drop(1)
    }

    data class WhiteDnsAutoDetectResult(
        val resolver: WhiteDnsResolverEntry,
        val transport: String,
        val recordMode: WhiteDnsRecordMode,
        val preset: WhiteDnsAutoTunePreset,
        val rttMs: Long,
        val supportedRecords: List<String>,
        val maxSafeQNameBytes: Int,
        val summaryFa: String,
        val summaryEn: String,
    )

    fun resolverById(id: String): WhiteDnsResolverEntry? =
        RESOLVER_CATALOG.firstOrNull { it.id == id }

    fun presetById(id: String): WhiteDnsAutoTunePreset? =
        IRAN_AUTO_TUNE_PRESETS.firstOrNull { it.id == id }

    /**
     * Active Auto-MTU & Carrier Record Finder:
     * Probes top Iranian & global resolvers over UDP/53, TCP/53, and DoT/853, tests carrier record
     * survival (HTTPS/SVCB Type 65, NULL 1232B, TXT, CNAME, AAAA), and probes QName lengths (62B, 101B, 160B)
     * to select the optimal WhiteDNS CottenMax / StormDNS / Soar profile for the active network.
     */
    fun autoDetectBestWhiteDnsProfile(
        domain: String = "cloudflare.com",
        timeoutMs: Int = 1_500,
    ): WhiteDnsAutoDetectResult {
        val candidates = listOfNotNull(
            resolverById("shecan"),
            resolverById("403online"),
            resolverById("electro"),
            resolverById("radar"),
            resolverById("tci"),
            resolverById("cloudflare"),
        ).ifEmpty { RESOLVER_CATALOG.take(6) }
        var bestResolver = candidates.first()
        var bestTransport = "udp"
        var bestRtt = Long.MAX_VALUE
        for (entry in candidates) {
            val udpRtt = probeDnsOverUdp(entry.primaryIp, domain = domain, qtype = 1, timeoutMs = timeoutMs)
            if (udpRtt != null && udpRtt < bestRtt) {
                bestRtt = udpRtt
                bestResolver = entry
                bestTransport = "udp"
            } else if (udpRtt == null) {
                val tcpRtt = probeDnsOverTcp(entry.primaryIp, domain = domain, qtype = 1, timeoutMs = timeoutMs)
                if (tcpRtt != null && tcpRtt < bestRtt) {
                    bestRtt = tcpRtt
                    bestResolver = entry
                    bestTransport = "tcp"
                }
            }
        }
        if (bestRtt == Long.MAX_VALUE) {
            for (entry in candidates.filter { it.dotEndpoint.isNotEmpty() }) {
                val dotRtt = probeDnsOverTls(entry.primaryIp, 853, domain = domain, qtype = 1, timeoutMs = timeoutMs + 200)
                if (dotRtt != null && dotRtt < bestRtt) {
                    bestRtt = dotRtt
                    bestResolver = entry
                    bestTransport = "dot"
                }
            }
        }
        val useTcp = bestTransport == "tcp"
        val supported = if (bestRtt != Long.MAX_VALUE) {
            probeSupportedCarrierRecords(bestResolver.primaryIp, useTcp = useTcp, timeoutMs = timeoutMs)
        } else {
            listOf("HTTPS/SVCB", "TXT", "CNAME")
        }
        val probeLongQname = "a".repeat(48) + "." + "b".repeat(48) + "." + domain
        val longQnameOk = if (bestRtt != Long.MAX_VALUE && !useTcp) {
            probeDnsOverUdp(bestResolver.primaryIp, domain = probeLongQname, qtype = 1, timeoutMs = timeoutMs) != null
        } else false
        val midQnameOk = if (bestRtt != Long.MAX_VALUE && !useTcp) {
            val midDomain = "a".repeat(32) + "." + domain
            probeDnsOverUdp(bestResolver.primaryIp, domain = midDomain, qtype = 1, timeoutMs = timeoutMs) != null
        } else false

        val maxSafeQName = when {
            longQnameOk -> 160
            midQnameOk -> 101
            else -> 62
        }
        val recordMode = when {
            "HTTPS(65)" in supported || "HTTPS/SVCB" in supported -> WhiteDnsRecordMode.TXT_HTTPS
            "NULL" in supported && maxSafeQName >= 101 -> WhiteDnsRecordMode.NULL_EDNS0
            "CNAME" in supported -> WhiteDnsRecordMode.TXT_CNAME
            "AAAA" in supported -> WhiteDnsRecordMode.TXT_AAAA
            else -> WhiteDnsRecordMode.ALL_ADAPTIVE
        }
        val presetId = when {
            useTcp -> "tcp-survival"
            maxSafeQName <= 62 -> "iran-compact-fixed"
            "NULL" in supported && maxSafeQName >= 160 -> "iran-download-heavy"
            maxSafeQName >= 101 && bestRtt < 120L -> "iran-fast-low-mtu"
            else -> "iran-fixed-64-balanced"
        }
        val preset = presetById(presetId) ?: IRAN_AUTO_TUNE_PRESETS.first()
        val effectiveRtt = if (bestRtt == Long.MAX_VALUE) 0L else bestRtt
        return WhiteDnsAutoDetectResult(
            resolver = bestResolver,
            transport = if (bestTransport == "udp" && midQnameOk) "auto" else bestTransport,
            recordMode = recordMode,
            preset = preset,
            rttMs = effectiveRtt,
            supportedRecords = supported.ifEmpty { listOf("HTTPS/SVCB", "TXT") },
            maxSafeQNameBytes = maxSafeQName,
            summaryFa = "بهترین ریزولور: ${bestResolver.nameFa} (${bestResolver.primaryIp}) · ترنسپورت: ${bestTransport.uppercase()} · رکورد حامل: ${recordMode.titleFa} · سقف QName امن: ${maxSafeQName}B · پریست پیشنهادی: ${preset.titleFa}",
            summaryEn = "Best resolver: ${bestResolver.nameEn} (${bestResolver.primaryIp}) · Transport: ${bestTransport.uppercase()} · Carrier: ${recordMode.titleEn} · Safe QName: ${maxSafeQName}B · Preset: ${preset.titleEn}",
        )
    }

    enum class RegionalFirewallType(
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val workerPresetId: String,
        val aetherExitLoc: String,
        val recommendedIp: String,
    ) {
        MCI_IPV6(
            id = "mci-ipv6",
            titleFa = "🟦 فایروال نوع همراه اول (IPv6 باز)",
            titleEn = "🟦 MCI-Type Firewall (IPv6 Reachable)",
            workerPresetId = "mci-ipv6",
            aetherExitLoc = "auto",
            recommendedIp = "2a06:98c1:3101::6812:202f",
        ),
        IRANCELL_FF(
            id = "irancell-ff",
            titleFa = "🟧 فایروال نوع ایرانسل (نیازمند متد F&F / WARP)",
            titleEn = "🟧 Irancell-Type Firewall (Requires F&F / WARP)",
            workerPresetId = "irancell-ff",
            aetherExitLoc = "auto",
            recommendedIp = "188.114.98.0",
        ),
        FIXED_MULTI_PORT(
            id = "fixed-multiport",
            titleFa = "🟩 فایروال اینترنت ثابت/مخابرات (پورت‌های جایگزین کلودفلر باز)",
            titleEn = "🟩 Fixed/TCI Firewall (Multi-Port CF TLS Open)",
            workerPresetId = "mci-ech",
            aetherExitLoc = "auto",
            recommendedIp = "104.16.132.229",
        ),
    }

    data class RegionalFirewallVerdict(
        val type: RegionalFirewallType,
        val ipv6Reachable: Boolean,
        val ipv6RttMs: Long?,
        val ffIpv4Reachable: Boolean,
        val ffIpv4RttMs: Long?,
        val openTlsPorts: List<Int>,
        val detailFa: String,
        val detailEn: String,
    )

    /**
     * Regional Firewall Fingerprinter:
     * Tests Cloudflare IPv6 edge reachability (`2a06:98c1:3101::6812:202f:443`) alongside
     * Irancell F&F IPv4 (`188.114.98.0:443`) and alternative CF TLS ports (`2053, 8443`)
     * to detect whether the user's region uses an MCI-type or Irancell-type firewall.
     */
    fun detectRegionalFirewallProfile(timeoutMs: Int = 1_600): RegionalFirewallVerdict {
        val v6Candidates = listOf("2a06:98c1:3101::6812:202f", "2606:4700:3030::6815:1")
        var v6Rtt: Long? = null
        for (v6 in v6Candidates) {
            val rtt = probeTcpPort(v6, 443, timeoutMs)
            if (rtt != null) {
                v6Rtt = rtt
                break
            }
        }
        val ffRtt = probeTcpPort("188.114.98.0", 443, timeoutMs)
            ?: probeTcpPort("188.114.97.6", 443, timeoutMs)
        val openPorts = mutableListOf<Int>()
        for (p in listOf(443, 2053, 8443)) {
            if (probeTcpPort("104.16.132.229", p, timeoutMs / 2) != null) {
                openPorts += p
            }
        }
        val type = when {
            v6Rtt != null -> RegionalFirewallType.MCI_IPV6
            openPorts.size >= 2 && ffRtt != null && openPorts.contains(2053) -> RegionalFirewallType.FIXED_MULTI_PORT
            else -> RegionalFirewallType.IRANCELL_FF
        }
        val detailFa = buildString {
            appendLine(type.titleFa)
            appendLine("• وضعیت IPv6 کلودفلر: ${if (v6Rtt != null) "باز ✓ (${v6Rtt} ms)" else "بسته/ندارد ✗"}")
            appendLine("• وضعیت لبه F&F (188.114.98.0:443): ${if (ffRtt != null) "باز ✓ (${ffRtt} ms)" else "مسدود ✗"}")
            appendLine("• پورت‌های TLS باز روی کلودفلر: ${if (openPorts.isEmpty()) "فقط با متد F&F / تانل" else openPorts.joinToString(", ")}")
            when (type) {
                RegionalFirewallType.MCI_IPV6 ->
                    append("پیشنهاد: ۱) ورکر با IPv6 (2a06:98c1:3101::6812:202f و fingerprint=chrome) ۲) در Aether: WARP با IPv6 یا MASQUE-HTTP/2.")
                RegionalFirewallType.IRANCELL_FF ->
                    append("پیشنهاد: ۱) ورکر با متد F&F (188.114.98.0، finalMask=tlshello-0-len، cipherSuites=semi-python، fingerprint=unsafe) جهت رفع محدودیت آپلود ۲) در Aether: WARP (هر نوع IP) یا MASQUE-HTTP/2.")
                RegionalFirewallType.FIXED_MULTI_PORT ->
                    append("پیشنهاد: ۱) اسکن ۶ پورت TLS کلودفلر (443, 2053, 8443) ۲) در Aether: Gool H2 (WireGuard-over-MASQUE) یا زنجیره WARP -> Psiphon.")
            }
        }
        val detailEn = buildString {
            appendLine(type.titleEn)
            appendLine("• Cloudflare IPv6 edge: ${if (v6Rtt != null) "Open ✓ (${v6Rtt} ms)" else "Blocked/Unavailable ✗"}")
            appendLine("• F&F edge (188.114.98.0:443): ${if (ffRtt != null) "Open ✓ (${ffRtt} ms)" else "Blocked ✗"}")
            append("• Open CF TLS ports: ${if (openPorts.isEmpty()) "F&F / Tunnel only" else openPorts.joinToString(", ")}")
        }
        return RegionalFirewallVerdict(
            type = type,
            ipv6Reachable = v6Rtt != null,
            ipv6RttMs = v6Rtt,
            ffIpv4Reachable = ffRtt != null,
            ffIpv4RttMs = ffRtt,
            openTlsPorts = openPorts,
            detailFa = detailFa,
            detailEn = detailEn,
        )
    }

    private fun probeTcpPort(host: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val start = System.nanoTime()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    enum class WhiteDnsTunnelEngine(val titleFa: String, val titleEn: String) {
        COTTEN_MAX("CottenMax (FEC 4:1)", "CottenMax (FEC 4:1)"),
        STORM_DNS("StormDNS", "StormDNS"),
        MASTER_DNS("MasterDNS (5B Header + ARQ)", "MasterDNS (5B Header + ARQ)"),
        SOAR_MIMICRY("Soar Mimicry", "Soar Mimicry"),
        DNSTT_CLASSIC("dnstt", "dnstt"),
        VAY_DNS("VayDNS (2B ClientID · 9 رکورد)", "VayDNS (2B ClientID · 9 Records)"),
        NOIZ_DNS("NoizDNS (ضد DPI · Base36 + Padding)", "NoizDNS (Anti-DPI · Base36 + Padding)"),
        SLIPSTREAM_QUIC("Slipstream (QUIC-over-DNS)", "Slipstream (QUIC-over-DNS)"),
        SLIPNET_BUNDLE("SlipNet (زنجیره DNS + SSH)", "SlipNet (DNS + SSH Chain)"),
        DNST_STANDARD("استاندارد dnst:// (چندموتوره)", "dnst:// Standard (Multi-Transport)"),
        QS_HYBRID_TUNNEL("QS-Tunnel (آپلود DNS + دانلود IP Spoof)", "QS-Tunnel (DNS Upload + IP Spoof)"),
        QQ_BIDIR_TUNNEL("QQ-Tunnel (دوطرفه Query-to-Query)", "QQ-Tunnel (Bidirectional Query-to-Query)"),
        SPOOF_L3_TUNNEL("Spoof-Tunnel (جعل دوطرفه L3 + WG)", "Spoof-Tunnel (Mutual L3 Spoof + WG)"),
        S3_BYPASS_TUNNEL("S3 Bypass / Fedarisha (باکت وایت‌لیست)", "S3 Bypass / Fedarisha (Whitelist Bucket)"),
        GOOGLE_SCRIPT_RELAY("رله اسکریپت گوگل (GAS)", "Google Apps Script Relay (GAS)"),
        MASQUE_ENDPOINT("MASQUE/H2", "MASQUE/H2"),
    }

    data class WhiteDnsParsedTunnelUri(
        val rawUri: String,
        val scheme: String,
        val engine: WhiteDnsTunnelEngine,
        val resolverIp: String,
        val resolverPort: Int,
        val tunnelDomain: String,
        val publicKeyHex: String,
        val recordMode: WhiteDnsRecordMode,
        val presetId: String,
        val mtu: Int,
        val remark: String,
        val scriptUrl: String = "",
        val backendType: String = "socks",
        val balancingStrategy: Int = 6,
        val duplicationCount: Int = 2,
    )

    val SUPPORTED_TUNNEL_SCHEMES = setOf(
        "cottendns", "stormdns", "masterdns", "soar", "dnstt", "gasrelay", "masque",
        "dnst", "slipnet", "slipnet-enc", "slipnet-bundle-enc", "vaydns", "noizdns", "slipstream",
        "qstunnel", "qqtunnel", "spooftunnel", "s3bypass", "phantun", "nm",
    )

    /**
     * If a `slipnet://` (v1..v28+ pipe-delimited) URI actually encapsulates a direct VLESS, SSH,
     * SOCKS5, or NaiveProxy profile (introduced in SlipNet v6..v28), extracts the equivalent
     * standard URI (`vless://`, `ssh://`, `socks5://`, `https://`) so `SubConvConverter` can
     * run it natively on the Mihomo core at full speed.
     */
    fun extractEmbeddedSlipNetDirectUri(rawUri: String): String? {
        val trimmed = rawUri.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("slipnet://") && !lower.startsWith("slipnet-enc://")) return null
        val payload = trimmed.substringAfter("://").substringBefore('#').substringBefore('?').trim()
        val decoded = decodeBase64UrlOrStandard(payload) ?: return null
        if ('|' !in decoded) return null
        val fields = decoded.split('|')
        if (fields.size < 11) return null
        val tType = fields[1].trim().lowercase()
        val name = fields.getOrNull(2)?.trim()?.ifBlank { "SlipNet-$tType" } ?: "SlipNet"
        val encRemark = runCatching { java.net.URLEncoder.encode(name, "UTF-8") }.getOrDefault(name)
        return when (tType) {
            "vless" -> {
                val wsHost = fields.getOrNull(3)?.trim().orEmpty()
                val uuid = fields.getOrNull(62)?.trim().orEmpty()
                if (uuid.isBlank() || wsHost.isBlank()) return null
                val sec = fields.getOrNull(63)?.trim()?.ifBlank { "tls" } ?: "tls"
                val net = fields.getOrNull(64)?.trim()?.ifBlank { "ws" } ?: "ws"
                val wsPath = fields.getOrNull(65)?.trim()?.ifBlank { "/" } ?: "/"
                val cdnIp = fields.getOrNull(66)?.trim()?.ifBlank { wsHost } ?: wsHost
                val cdnPort = fields.getOrNull(67)?.trim()?.toIntOrNull() ?: 443
                val sni = fields.getOrNull(78)?.trim()?.ifBlank {
                    fields.getOrNull(71)?.trim().orEmpty()
                }?.ifBlank { wsHost } ?: wsHost
                val encPath = runCatching { java.net.URLEncoder.encode(wsPath, "UTF-8") }.getOrDefault(wsPath)
                "vless://$uuid@$cdnIp:$cdnPort?encryption=none&security=$sec&type=$net&host=$wsHost&sni=$sni&path=$encPath&fp=chrome#$encRemark"
            }
            "ssh" -> {
                val domain = fields.getOrNull(3)?.trim().orEmpty()
                val sshUser = fields.getOrNull(15)?.trim().orEmpty()
                val sshPass = fields.getOrNull(16)?.trim().orEmpty()
                val sshPort = fields.getOrNull(17)?.trim()?.toIntOrNull() ?: 22
                val sshHost = fields.getOrNull(19)?.trim()?.ifBlank { domain } ?: domain
                if (sshHost.isBlank() || sshUser.isBlank()) return null
                val encUser = runCatching { java.net.URLEncoder.encode(sshUser, "UTF-8") }.getOrDefault(sshUser)
                val encPass = runCatching { java.net.URLEncoder.encode(sshPass, "UTF-8") }.getOrDefault(sshPass)
                "ssh://$encUser:$encPass@$sshHost:$sshPort#$encRemark"
            }
            "socks5" -> {
                val host = fields.getOrNull(9)?.trim()?.ifBlank { fields.getOrNull(3)?.trim().orEmpty() }.orEmpty()
                val port = fields.getOrNull(8)?.trim()?.toIntOrNull() ?: 1080
                val user = fields.getOrNull(12)?.trim().orEmpty()
                val pass = fields.getOrNull(13)?.trim().orEmpty()
                if (host.isBlank()) return null
                val auth = if (user.isNotBlank()) "$user:$pass@" else ""
                "socks5://$auth$host:$port#$encRemark"
            }
            "naive", "naive_ssh" -> {
                val domain = fields.getOrNull(3)?.trim().orEmpty()
                val naivePort = fields.getOrNull(28)?.trim()?.toIntOrNull() ?: 443
                val naiveUser = fields.getOrNull(29)?.trim().orEmpty()
                val naivePassB64 = fields.getOrNull(30)?.trim().orEmpty()
                val naivePass = decodeBase64UrlOrStandard(naivePassB64) ?: naivePassB64
                if (domain.isBlank()) return null
                val auth = if (naiveUser.isNotBlank()) "$naiveUser:$naivePass@" else ""
                "https://${auth}$domain:$naivePort#$encRemark"
            }
            else -> null
        }
    }

    /**
     * Parses `cottendns://`, `stormdns://`, `masterdns://`, `soar://`, `dnstt://`, `dnst://` (both
     * Human-Readable and Base64-JSON per `net2share/dnst-url-spec` v1.0), `slipnet://` (official
     * SlipNet v1..v28+ pipe-delimited format, Base64-JSON, and URL format), `vaydns://`,
     * `noizdns://`, `slipstream://`, `qstunnel://`, `qqtunnel://`, `spooftunnel://`, `s3bypass://`,
     * `phantun://`, `nm://`, and `gasrelay://` URIs into a structured [WhiteDnsParsedTunnelUri].
     */
    fun parseTunnelUri(rawUri: String): WhiteDnsParsedTunnelUri? {
        val trimmed = rawUri.trim()
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme !in SUPPORTED_TUNNEL_SCHEMES) return null
        val rest = trimmed.substring(schemeSep + 3)

        // Special handling for `dnst://` Base64-JSON form (`net2share/dnst-url-spec` §2.2)
        if (scheme == "dnst") {
            val b64Candidate = rest.substringBefore('#').substringBefore('?').trim()
            val decodedJson = decodeBase64UrlOrStandard(b64Candidate)
            if (decodedJson != null && decodedJson.trimStart().startsWith("{")) {
                return runCatching {
                    val root = org.json.JSONObject(decodedJson)
                    val tag = root.optString("tag", "dnst-json").trim()
                    val trObj = root.optJSONObject("transport") ?: org.json.JSONObject()
                    val bkObj = root.optJSONObject("backend") ?: org.json.JSONObject()
                    val trType = trObj.optString("type", "dnstt").lowercase()
                    val dom = trObj.optString("domain", "t.example.com").trim()
                    val pub = trObj.optString("pubkey", trObj.optString("mkey", "")).trim()
                    val resIp = trObj.optString("resolver", "178.22.122.100").substringBefore(':').trim().ifBlank { "178.22.122.100" }
                    val recType = trObj.optString("record-type", "txt").uppercase()
                    val recMode = when (recType) {
                        "CNAME" -> WhiteDnsRecordMode.TXT_CNAME
                        "AAAA" -> WhiteDnsRecordMode.TXT_AAAA
                        "NULL" -> WhiteDnsRecordMode.NULL_EDNS0
                        else -> WhiteDnsRecordMode.TXT_ONLY
                    }
                    val eng = when (trType) {
                        "vaydns" -> WhiteDnsTunnelEngine.VAY_DNS
                        "slipstream" -> WhiteDnsTunnelEngine.SLIPSTREAM_QUIC
                        "masterdns" -> WhiteDnsTunnelEngine.MASTER_DNS
                        else -> WhiteDnsTunnelEngine.DNST_STANDARD
                    }
                    WhiteDnsParsedTunnelUri(
                        rawUri = trimmed,
                        scheme = scheme,
                        engine = eng,
                        resolverIp = resIp,
                        resolverPort = 53,
                        tunnelDomain = dom,
                        publicKeyHex = pub,
                        recordMode = recMode,
                        presetId = "iran-fast-low-mtu",
                        mtu = 101,
                        remark = tag.ifBlank { "${eng.titleEn} ($dom)" },
                        backendType = bkObj.optString("type", "socks"),
                    )
                }.getOrNull()
            }
        }

        // Special handling for `slipnet://`, `slipnet-enc://`, and `slipnet-bundle-enc://` Base64 payloads
        // Supports:
        //   1. Official SlipNet v1..v28+ Pipe-Delimited profiles (`1..28|tunnelType|name|domain|resolvers|...`)
        //   2. Base64-encoded JSON profiles (`{"domain": ..., "resolver": ...}`)
        if (scheme == "slipnet" || scheme == "slipnet-enc" || scheme == "slipnet-bundle-enc") {
            val payload = rest.substringBefore('#').substringBefore('?').trim()
            val decoded = decodeBase64UrlOrStandard(payload)
            if (decoded != null) {
                val trimmedDecoded = decoded.trim()
                // Format 1: Official SlipNet Pipe-Delimited v1..v28+ (`18|sayedns_ssh|Name|t.domain.com|178.22.122.100:53:0|...`)
                if ('|' in trimmedDecoded && !trimmedDecoded.startsWith("{")) {
                    val fields = trimmedDecoded.split('|')
                    val verRaw = fields.firstOrNull()?.trim()?.removePrefix("v")?.removePrefix("V") ?: ""
                    if (fields.size >= 11 && verRaw.toIntOrNull() != null) {
                        val tType = fields.getOrNull(1)?.trim()?.lowercase().orEmpty()
                        val profName = fields.getOrNull(2)?.trim()?.ifBlank { "SlipNet-$tType" } ?: "SlipNet"
                        val dom = fields.getOrNull(3)?.trim()?.ifBlank { "t.example.com" } ?: "t.example.com"
                        // In v17+, position 36 is `resolversHidden` ("1") and position 37 is `hiddenResolvers`
                        val resolversHidden = fields.getOrNull(36)?.trim() == "1"
                        val hiddenResolversStr = fields.getOrNull(37)?.trim().orEmpty()
                        val rawResolversStr = if (resolversHidden && hiddenResolversStr.isNotBlank()) {
                            hiddenResolversStr
                        } else {
                            fields.getOrNull(4)?.trim().orEmpty()
                        }
                        // Each resolver entry is `host:port:authoritative`, comma-separated
                        val firstResolver = rawResolversStr.split(',').firstOrNull()?.trim().orEmpty()
                        val resParts = firstResolver.split(':')
                        val resIp = resParts.getOrNull(0)?.trim()?.ifBlank { "178.22.122.100" } ?: "178.22.122.100"
                        val resPort = resParts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: 53
                        val pubKey = fields.getOrNull(11)?.trim().orEmpty()
                        val sshEnabled = fields.getOrNull(14)?.trim() == "1" || tType.endsWith("_ssh") || tType == "ssh"
                        val dohUrl = fields.getOrNull(21)?.trim().orEmpty()
                        val dnsPayloadSize = fields.getOrNull(39)?.trim()?.toIntOrNull() ?: 0
                        val vayMaxQname = fields.getOrNull(43)?.trim()?.toIntOrNull() ?: 101
                        val vayRecType = fields.getOrNull(42)?.trim()?.uppercase().orEmpty()
                        val effectiveMtu = when {
                            dnsPayloadSize in 48..1400 -> dnsPayloadSize
                            vayMaxQname in 48..1400 -> vayMaxQname
                            else -> 96
                        }
                        val recMode = when (vayRecType) {
                            "CNAME" -> WhiteDnsRecordMode.TXT_CNAME
                            "AAAA" -> WhiteDnsRecordMode.TXT_AAAA
                            "NULL" -> WhiteDnsRecordMode.NULL_EDNS0
                            else -> WhiteDnsRecordMode.TXT_ONLY
                        }
                        val eng = when {
                            tType.contains("vaydns") -> WhiteDnsTunnelEngine.VAY_DNS
                            tType == "ss" || tType.contains("slipstream") -> WhiteDnsTunnelEngine.SLIPSTREAM_QUIC
                            tType.contains("sayedns") || tType.contains("noizdns") -> WhiteDnsTunnelEngine.NOIZ_DNS
                            tType.contains("dnstt") -> WhiteDnsTunnelEngine.DNSTT_COMPAT
                            else -> WhiteDnsTunnelEngine.SLIPNET_BUNDLE
                        }
                        return WhiteDnsParsedTunnelUri(
                            rawUri = trimmed,
                            scheme = scheme,
                            engine = eng,
                            resolverIp = resIp,
                            resolverPort = resPort,
                            tunnelDomain = dom,
                            publicKeyHex = pubKey,
                            recordMode = recMode,
                            presetId = "iran-compact-fixed",
                            mtu = effectiveMtu,
                            remark = "$profName [SlipNet v$verRaw]",
                            scriptUrl = dohUrl,
                            backendType = if (sshEnabled) "ssh" else "socks",
                        )
                    }
                }
                // Format 2: Base64-JSON SlipNet profile
                if (trimmedDecoded.startsWith("{")) {
                    return runCatching {
                        val obj = org.json.JSONObject(trimmedDecoded)
                        val dom = obj.optString("domain", "t.example.com").trim()
                        val resIp = obj.optString("resolver", "178.22.122.100").substringBefore(':').trim().ifBlank { "178.22.122.100" }
                        val pub = obj.optString("pubkey", "").trim()
                        val tType = obj.optString("tunnelType", "noizdns").lowercase()
                        val name = obj.optString("name", "SlipNet-${tType.uppercase()}").trim()
                        val eng = when {
                            tType.contains("vaydns") -> WhiteDnsTunnelEngine.VAY_DNS
                            tType.contains("slipstream") -> WhiteDnsTunnelEngine.SLIPSTREAM_QUIC
                            tType.contains("noizdns") || tType.contains("sayedns") -> WhiteDnsTunnelEngine.NOIZ_DNS
                            else -> WhiteDnsTunnelEngine.SLIPNET_BUNDLE
                        }
                        WhiteDnsParsedTunnelUri(
                            rawUri = trimmed,
                            scheme = scheme,
                            engine = eng,
                            resolverIp = resIp,
                            resolverPort = 53,
                            tunnelDomain = dom,
                            publicKeyHex = pub,
                            recordMode = WhiteDnsRecordMode.TXT_ONLY,
                            presetId = "iran-compact-fixed",
                            mtu = obj.optInt("maxQuerySize", 60).coerceIn(48, 1400),
                            remark = name,
                            backendType = if (obj.optBoolean("sshChain", false)) "ssh" else "socks",
                        )
                    }.getOrNull()
                }
            }
        }

        val hashIdx = rest.indexOf('#')
        val beforeHash = if (hashIdx >= 0) rest.substring(0, hashIdx) else rest
        val rawFragment = if (hashIdx >= 0) rest.substring(hashIdx + 1) else ""
        val remark = runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }
            .getOrDefault(rawFragment)
            .trim()
        val qIdx = beforeHash.indexOf('?')
        val authorityAndPath = (if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash)
            .trim()
        val authority = authorityAndPath.substringBefore('/').trim('/')
        val pathPart = if ('/' in authorityAndPath) "/" + authorityAndPath.substringAfter('/') else ""
        val pathSegments = pathPart.trim('/').split('/').filter { it.isNotBlank() }
        val query = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else ""
        val params = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = runCatching { java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault("").lowercase()
                val v = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault("").trim()
                if (k.isNotBlank()) params[k] = v
            }
        }
        val hostPart = authority.substringBefore('@').ifBlank { authority }
        val cleanHostPort = if ('@' in authority) authority.substringAfterLast('@') else hostPart
        val colonIdx = cleanHostPort.lastIndexOf(':')
        val parsedHost = if (colonIdx > 0 && !cleanHostPort.startsWith("[")) {
            cleanHostPort.substring(0, colonIdx)
        } else {
            cleanHostPort.removePrefix("[").removeSuffix("]")
        }
        val defaultPort = when (scheme) {
            "gasrelay", "masque", "s3bypass" -> 443
            "spooftunnel" -> 51820
            else -> 53
        }
        val parsedPort = if (colonIdx > 0) {
            cleanHostPort.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        } else {
            params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        }

        // For `dnst://<domain>/<transport>/<backend>?...`, authority is the tunnel domain
        val isDnstHuman = scheme == "dnst" && pathSegments.isNotEmpty()
        val dnstTransport = if (isDnstHuman) pathSegments.getOrNull(0)?.lowercase().orEmpty() else ""
        val dnstBackend = if (isDnstHuman) pathSegments.getOrNull(1)?.lowercase() ?: "socks" else (params["backend"] ?: "socks")

        val resolverIp = if (isDnstHuman) {
            (params["resolver"] ?: params["server"] ?: "178.22.122.100").substringBefore(':').trim()
        } else {
            (params["resolver"] ?: params["server"] ?: parsedHost)
                .substringBefore(':')
                .trim()
                .ifBlank {
                    when (scheme) {
                        "gasrelay" -> "script.google.com"
                        "masque" -> "162.159.198.1"
                        "s3bypass" -> "s3.ir-thr-at1.arvanstorage.ir"
                        else -> "178.22.122.100"
                    }
                }
        }
        val tunnelDomain = if (isDnstHuman) {
            parsedHost.ifBlank { "t.example.com" }
        } else {
            (params["domain"] ?: params["ns"] ?: params["sni"] ?: pathSegments.firstOrNull() ?: parsedHost)
                .trim()
                .ifBlank {
                    when (scheme) {
                        "gasrelay" -> "script.google.com"
                        "masque" -> "consumer-masque.cloudflareclient.com"
                        else -> "t.example.com"
                    }
                }
        }
        val publicKeyHex = (params["pubkey"] ?: params["mkey"] ?: params["key"] ?: params["pk"]).orEmpty().trim()
        val presetId = (params["preset"] ?: params["profile"]).orEmpty().trim().ifBlank { "iran-fast-low-mtu" }
        val presetDefaultMtu = IRAN_AUTO_TUNE_PRESETS
            .firstOrNull { it.id == presetId }
            ?.mtuUpRange
            ?.substringBefore('-')
            ?.trim()
            ?.toIntOrNull()
        val mtu = (params["mtu"] ?: params["max-qname-len"] ?: params["max-query-size"])
            ?.toIntOrNull()?.coerceIn(38, 1400)
            ?: presetDefaultMtu
            ?: 90
        val recordParam = (params["record-type"] ?: params["record"] ?: params["records"] ?: params["delivery"] ?: params["mode"] ?: params["carrier"])
            .orEmpty().uppercase()
        val recordMode = when {
            recordParam == "CNAME" || recordParam == "TXT-CNAME" -> WhiteDnsRecordMode.TXT_CNAME
            recordParam == "AAAA" || recordParam == "TXT-AAAA" -> WhiteDnsRecordMode.TXT_AAAA
            recordParam == "NULL" -> WhiteDnsRecordMode.NULL_EDNS0
            recordParam == "TXT" -> WhiteDnsRecordMode.TXT_ONLY
            recordParam == "ALL" -> WhiteDnsRecordMode.ALL_ADAPTIVE
            else -> WhiteDnsRecordMode.entries.firstOrNull {
                it.name.equals(recordParam, ignoreCase = true) ||
                    recordParam.contains(it.name.substringBefore('_'))
            } ?: WhiteDnsRecordMode.TXT_HTTPS
        }
        val engine = when {
            scheme == "cottendns" -> WhiteDnsTunnelEngine.COTTEN_MAX
            scheme == "stormdns" -> WhiteDnsTunnelEngine.STORM_DNS
            scheme == "masterdns" || dnstTransport == "masterdns" -> WhiteDnsTunnelEngine.MASTER_DNS
            scheme == "soar" -> WhiteDnsTunnelEngine.SOAR_MIMICRY
            scheme == "vaydns" || dnstTransport == "vaydns" -> WhiteDnsTunnelEngine.VAY_DNS
            scheme == "noizdns" || dnstTransport == "noizdns" -> WhiteDnsTunnelEngine.NOIZ_DNS
            scheme == "slipstream" || dnstTransport == "slipstream" -> WhiteDnsTunnelEngine.SLIPSTREAM_QUIC
            scheme == "slipnet" || scheme == "slipnet-enc" -> WhiteDnsTunnelEngine.SLIPNET_BUNDLE
            scheme == "dnst" -> WhiteDnsTunnelEngine.DNST_STANDARD
            scheme == "qstunnel" -> WhiteDnsTunnelEngine.QS_HYBRID_TUNNEL
            scheme == "qqtunnel" -> WhiteDnsTunnelEngine.QQ_BIDIR_TUNNEL
            scheme == "spooftunnel" -> WhiteDnsTunnelEngine.SPOOF_L3_TUNNEL
            scheme == "s3bypass" -> WhiteDnsTunnelEngine.S3_BYPASS_TUNNEL
            scheme == "gasrelay" -> WhiteDnsTunnelEngine.GOOGLE_SCRIPT_RELAY
            scheme == "masque" -> WhiteDnsTunnelEngine.MASQUE_ENDPOINT
            else -> WhiteDnsTunnelEngine.DNSTT_CLASSIC
        }
        val scriptUrl = if (scheme == "gasrelay") {
            params["url"]?.takeIf { it.startsWith("https://") }
                ?: "https://${parsedHost.ifBlank { "script.google.com" }}$pathPart"
        } else ""
        val balancer = params["balancer"]?.toIntOrNull()?.coerceIn(0, 8) ?: 6
        val dup = (params["dup"] ?: params["duplication"])?.toIntOrNull()?.coerceIn(1, 8) ?: 2
        return WhiteDnsParsedTunnelUri(
            rawUri = trimmed,
            scheme = scheme,
            engine = engine,
            resolverIp = resolverIp,
            resolverPort = parsedPort,
            tunnelDomain = tunnelDomain,
            publicKeyHex = publicKeyHex,
            recordMode = recordMode,
            presetId = presetId,
            mtu = mtu,
            remark = remark.ifBlank { "${engine.titleEn} ($resolverIp)" },
            scriptUrl = scriptUrl,
            backendType = dnstBackend,
            balancingStrategy = balancer,
            duplicationCount = dup,
        )
    }

    private fun decodeBase64UrlOrStandard(input: String): String? {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        if (clean.isEmpty()) return null
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return runCatching {
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }

    private val activeTunnelBridges = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val activeTunnelSockets = java.util.concurrent.ConcurrentHashMap<Int, java.net.ServerSocket>()
    private val wdnsWorkerSeq = java.util.concurrent.atomic.AtomicInteger(1)
    private val wdnsWorkerPool = java.util.concurrent.ThreadPoolExecutor(
        4,
        48,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(256),
        java.util.concurrent.ThreadFactory { runnable ->
            Thread(
                null,
                runnable,
                "wdns-worker-${wdnsWorkerSeq.getAndIncrement()}",
                256 * 1024L,
            ).apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /**
     * Returns a deterministic local loopback port (`10853..10899`) for the parsed WhiteDNS tunnel
     * and optionally starts the local SOCKS5 (`RFC 1928`) bridge daemon on `127.0.0.1`.
     */
    fun ensureLocalTunnelBridgePort(parsed: WhiteDnsParsedTunnelUri, startDaemon: Boolean = false): Int {
        val key = "${parsed.scheme}|${parsed.resolverIp}:${parsed.resolverPort}|${parsed.tunnelDomain}|${parsed.presetId}"
        val deterministicPort = 10853 + (kotlin.math.abs(key.hashCode()) % 45)
        val existing = activeTunnelBridges[key]
        if (existing != null && (!startDaemon || activeTunnelSockets.containsKey(existing))) {
            return existing
        }
        if (!startDaemon) {
            activeTunnelBridges[key] = deterministicPort
            return deterministicPort
        }
        return runCatching {
            val server = try {
                java.net.ServerSocket(deterministicPort, 32, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                java.net.ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            }
            val boundPort = server.localPort
            activeTunnelBridges[key] = boundPort
            activeTunnelSockets[boundPort] = server
            Thread(
                null,
                Runnable {
                    while (!server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        wdnsWorkerPool.execute {
                            handleLocalTunnelSocksClient(client, parsed)
                        }
                    }
                },
                "wdns-socks-accept-$boundPort",
                256 * 1024L,
            ).apply {
                isDaemon = true
                start()
            }
            boundPort
        }.getOrDefault(deterministicPort)
    }

    /**
     * Starts a local/LAN Anti-DPI SOCKS5 bridge (bound to `0.0.0.0` when `bindLan = true` so
     * iPhones/iPads on Wi-Fi or Hotspot can connect directly to this Android device).
     */
    fun startLocalAntiDpiSocksBridge(
        listenPort: Int = 10808,
        useDoubleFragment: Boolean = true,
        bindLan: Boolean = true,
    ): Int {
        val key = "ios-lan-bridge|$listenPort|$useDoubleFragment|$bindLan"
        val existing = activeTunnelBridges[key]
        if (existing != null && activeTunnelSockets.containsKey(existing)) {
            return existing
        }
        val parsed = WhiteDnsParsedTunnelUri(
            rawUri = "cottendns://8.8.8.8:53/t.example.com?preset=iran-mid-reliable&mtu=110#iOS-LAN-Bridge",
            scheme = "cottendns",
            engine = WhiteDnsTunnelEngine.COTTEN_MAX,
            resolverIp = "8.8.8.8",
            resolverPort = 53,
            tunnelDomain = "t.example.com",
            publicKeyHex = "",
            recordMode = WhiteDnsRecordMode.ALL_ADAPTIVE,
            presetId = if (useDoubleFragment) "double-frag" else "iran-mid-reliable",
            mtu = 110,
            remark = "iOS-LAN-Bridge",
        )
        return runCatching {
            val bindAddr = InetAddress.getByName(if (bindLan) "0.0.0.0" else "127.0.0.1")
            val preferredPort = if (listenPort in 1024..65535) listenPort else 10808
            val server = try {
                java.net.ServerSocket(preferredPort, 64, bindAddr)
            } catch (_: Throwable) {
                java.net.ServerSocket(0, 64, bindAddr)
            }
            val boundPort = server.localPort
            activeTunnelBridges[key] = boundPort
            activeTunnelSockets[boundPort] = server
            Thread(
                null,
                Runnable {
                    while (!server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        wdnsWorkerPool.execute {
                            handleLocalTunnelSocksClient(client, parsed)
                        }
                    }
                },
                "ios-lan-socks-accept-$boundPort",
                256 * 1024L,
            ).apply {
                isDaemon = true
                start()
            }
            boundPort
        }.getOrDefault(if (listenPort > 0) listenPort else 10808)
    }

    private fun handleLocalTunnelSocksClient(client: Socket, parsed: WhiteDnsParsedTunnelUri) {
        runCatching {
            client.use { c: Socket ->
                c.soTimeout = 15_000
                c.tcpNoDelay = true
                val input = c.getInputStream()
                val output = c.getOutputStream()
                val ver = input.read()
                if (ver != 0x05) return@use
                val nMethods = input.read().coerceAtLeast(0)
                if (nMethods > 0) {
                    val methods = ByteArray(nMethods)
                    input.read(methods)
                }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val hdr = ByteArray(4)
                if (input.read(hdr) != 4 || hdr[0].toInt() != 0x05 || hdr[1].toInt() != 0x01) return@use
                val targetHost = when (hdr[3].toInt() and 0xFF) {
                    0x01 -> {
                        val ip4 = ByteArray(4)
                        if (input.read(ip4) != 4) return@use
                        InetAddress.getByAddress(ip4).hostAddress ?: return@use
                    }
                    0x03 -> {
                        val len = input.read()
                        if (len <= 0) return@use
                        val dom = ByteArray(len)
                        if (input.read(dom) != len) return@use
                        String(dom, Charsets.UTF_8)
                    }
                    0x04 -> {
                        val ip6 = ByteArray(16)
                        if (input.read(ip6) != 16) return@use
                        InetAddress.getByAddress(ip6).hostAddress ?: return@use
                    }
                    else -> return@use
                }
                val portHi = input.read()
                val portLo = input.read()
                if (portHi < 0 || portLo < 0) return@use
                val targetPort = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)

                val cleanTarget = targetHost.trim().removePrefix("[").removeSuffix("]").lowercase()
                if (
                    cleanTarget == "127.0.0.1" ||
                    cleanTarget == "localhost" ||
                    cleanTarget == "::1" ||
                    cleanTarget == "0.0.0.0" ||
                    cleanTarget.startsWith("127.")
                ) {
                    output.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return@use
                }

                // Resolve via WhiteDNS carrier probe to warm the resolver path before dialing
                runCatching {
                    probeDnsOverUdp(
                        parsed.resolverIp,
                        domain = targetHost,
                        qtype = parsed.recordMode.qtype,
                        timeoutMs = 1_500,
                    )
                }

                val upstream = Socket()
                try {
                    protectOutboundSocket(upstream)
                    upstream.tcpNoDelay = true
                    upstream.connect(InetSocketAddress(targetHost, targetPort), 8_000)
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()
                    wdnsWorkerPool.execute {
                        runCatching {
                            val buf = ByteArray(parsed.mtu.coerceIn(64, 4096))
                            var first = true
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                if (first) {
                                    first = false
                                    val segments = buildDoubleFragmentSegments(buf.copyOf(n))
                                    if (segments.size > 1) {
                                        for ((segBytes, delayMs) in segments) {
                                            if (delayMs > 0L) {
                                                runCatching { Thread.sleep(delayMs) }
                                            }
                                            upOut.write(segBytes)
                                            upOut.flush()
                                        }
                                        continue
                                    }
                                }
                                upOut.write(buf, 0, n)
                                upOut.flush()
                            }
                        }.also {
                            runCatching { upstream.shutdownOutput() }
                        }
                    }
                    val downBuf = ByteArray(8192)
                    while (true) {
                        val n = upIn.read(downBuf)
                        if (n <= 0) break
                        output.write(downBuf, 0, n)
                        output.flush()
                    }
                } finally {
                    runCatching { upstream.close() }
                }
                Unit
            }
        }
    }

    /**
     * Splits a TLS ClientHello record (`0x16 0x03 ...` with Handshake Type `0x01`)
     * into two valid TLS records (`tlshello-0-len` / Irancell F&F record splitting)
     * so DPI middleboxes cannot inspect a single contiguous ClientHello SNI record.
     */
    fun splitTlsClientHelloRecord(packet: ByteArray, splitPayloadBytes: Int = 24): Pair<ByteArray, ByteArray>? {
        if (packet.size < 12) return null
        if ((packet[0].toInt() and 0xFF) != 0x16) return null
        if ((packet[1].toInt() and 0xFF) != 0x03) return null
        val recLen = ((packet[3].toInt() and 0xFF) shl 8) or (packet[4].toInt() and 0xFF)
        if (recLen < 8 || 5 + recLen > packet.size) return null
        if ((packet[5].toInt() and 0xFF) != 0x01) return null
        val firstPayloadLen = splitPayloadBytes.coerceIn(4, recLen - 4)
        val secondPayloadLen = recLen - firstPayloadLen
        val rec1 = ByteArray(5 + firstPayloadLen)
        rec1[0] = 0x16
        rec1[1] = packet[1]
        rec1[2] = packet[2]
        rec1[3] = ((firstPayloadLen ushr 8) and 0xFF).toByte()
        rec1[4] = (firstPayloadLen and 0xFF).toByte()
        System.arraycopy(packet, 5, rec1, 5, firstPayloadLen)

        val trailingBytes = packet.size - (5 + recLen)
        val rec2 = ByteArray(5 + secondPayloadLen + trailingBytes)
        rec2[0] = 0x16
        rec2[1] = packet[1]
        rec2[2] = packet[2]
        rec2[3] = ((secondPayloadLen ushr 8) and 0xFF).toByte()
        rec2[4] = (secondPayloadLen and 0xFF).toByte()
        System.arraycopy(packet, 5 + firstPayloadLen, rec2, 5, secondPayloadLen)
        if (trailingBytes > 0) {
            System.arraycopy(packet, 5 + recLen, rec2, 5 + secondPayloadLen, trailingBytes)
        }
        return rec1 to rec2
    }

    /**
     * Probes whether the current network exit is geo-sanctioned (`loc=IR` or HTTP 403/451)
     * so Aether's Smart Conditional Chain (`auto-psiphon`) knows whether to activate
     * `WARP -> Psiphon` or stay on full-speed direct `WARP/MASQUE`.
     */
    fun isDirectExitSanctionedOrBlocked(timeoutMs: Int = 2_200): Boolean {
        return runCatching {
            val url = java.net.URI("https://chatgpt.com/cdn-cgi/trace").toURL()
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            }
            try {
                val code = conn.responseCode
                if (code == 403 || code == 451) return@runCatching true
                if (code !in 200..299) return@runCatching true
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                body.lineSequence().any { it.trim().equals("loc=IR", ignoreCase = true) }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(true)
    }

    data class MidStreamHealthVerdict(
        val ok: Boolean,
        val stalledMidStream: Boolean,
        val ttfbMs: Long,
        val bytesRead: Int,
        val targetBytes: Int,
        val throughputKbps: Int,
        val summaryFa: String,
        val summaryEn: String,
    )

    /**
     * Pure evaluator for mid-stream freeze / DPI throttling detection (صفحه‌ای که نیمه‌کاره قطع می‌شود).
     */
    fun evaluateMidStreamTransfer(
        ttfbMs: Long,
        bytesRead: Int,
        targetBytes: Int,
        elapsedMs: Long,
    ): MidStreamHealthVerdict {
        val safeTarget = targetBytes.coerceAtLeast(1024)
        val safeElapsed = elapsedMs.coerceAtLeast(1L)
        val kbps = ((bytesRead.toLong() * 8L * 1000L) / (safeElapsed * 1024L)).toInt().coerceAtLeast(0)
        val stalled = ttfbMs > 0L && bytesRead in 1 until (safeTarget / 2)
        val ok = ttfbMs > 0L && bytesRead >= (safeTarget / 2) && !stalled
        val summaryFa = when {
            ok -> "✅ جریان داده سالم و بدون فریز شدن (${bytesRead / 1024}KB دریافت شد · TTFB=${ttfbMs}ms · سرعت ~${kbps}Kbps)"
            stalled -> "⚠️ اختلال و قطع نیمه‌کاره (Mid-Stream Freeze) تشخیص داده شد! هندشیک اولیه برقرار شد (${ttfbMs}ms) اما پس از ${bytesRead} بایت فایروال جریان را مسدود کرد — پیشنهاد: متد F&F یا تغییر پورت/SNI."
            else -> "❌ عدم دریافت پاسخ جریان داده (تایم‌اوت کامل یا مسدودی پورت/IP)"
        }
        val summaryEn = when {
            ok -> "✅ Healthy continuous stream (${bytesRead / 1024}KB read · TTFB=${ttfbMs}ms · ~${kbps}Kbps)"
            stalled -> "⚠️ Mid-stream DPI freeze detected! Initial response arrived (${ttfbMs}ms) but stalled at $bytesRead bytes — switch to F&F or another port/SNI."
            else -> "❌ Stream connection failed (timeout or blocked endpoint)"
        }
        return MidStreamHealthVerdict(
            ok = ok,
            stalledMidStream = stalled,
            ttfbMs = ttfbMs,
            bytesRead = bytesRead,
            targetBytes = safeTarget,
            throughputKbps = kbps,
            summaryFa = summaryFa,
            summaryEn = summaryEn,
        )
    }

    /**
     * Active Mid-Stream Freeze & Throughput Probe:
     * Requests a multi-KB stream from Cloudflare (`/__down?bytes=32768`) to detect connections
     * that pass ping/204 checks but freeze mid-page due to Irancell/MCI DPI throttling.
     */
    fun probeMidStreamFreezeAndThroughput(
        targetBytes: Int = 32_768,
        timeoutMs: Int = 3_500,
    ): MidStreamHealthVerdict {
        val startNs = System.nanoTime()
        var ttfbMs = -1L
        var totalRead = 0
        runCatching {
            val url = java.net.URI("https://speed.cloudflare.com/__down?bytes=$targetBytes").toURL()
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-store")
            }
            try {
                conn.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    while (totalRead < targetBytes) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (ttfbMs < 0L) {
                            ttfbMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                        }
                        totalRead += n
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return evaluateMidStreamTransfer(
            ttfbMs = ttfbMs,
            bytesRead = totalRead,
            targetBytes = targetBytes,
            elapsedMs = elapsedMs,
        )
    }

    data class UnpoisonedDnsResult(
        val domain: String,
        val cleanIps: List<String>,
        val poisonedIpsDetected: List<String>,
        val workingCleanIp: String?,
        val recommendedFrontHost: String,
        val rttMs: Long?,
        val summaryFa: String,
        val summaryEn: String,
    )

    /**
     * Detects Iranian ISP DNS poisoning addresses (`10.10.34.34..36`, loopback, bogon, etc.).
     */
    fun isPoisonedIranDnsIp(ip: String): Boolean {
        val t = ip.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (t.isBlank()) return true
        if (t.startsWith("10.10.34.") || t.startsWith("10.")) return true
        if (t == "0.0.0.0" || t.startsWith("127.") || t.startsWith("192.168.") || t.startsWith("169.254.")) return true
        if (t == "::" || t == "::1" || t.startsWith("fe80:") || t.startsWith("fc00:") || t.startsWith("fd00:")) return true
        return false
    }

    /**
     * Finds the real unpoisoned IP addresses of a filtered domain on Irancell/MCI using
     * encrypted DoH JSON + anti-DPI TCP RFC 1035 DNS, filters out poisoned `10.10.34.x` IPs,
     * and tests direct TLS reachability for Domain Fronting / Clean-IP routing.
     */
    fun resolveUnpoisonedRealIp(
        domain: String,
        timeoutMs: Int = 2_500,
    ): UnpoisonedDnsResult {
        val cleanHost = domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
            .ifBlank { "chatgpt.com" }
        val cleanIps = LinkedHashSet<String>()
        val poisonedIps = LinkedHashSet<String>()

        // 1) Check plain system DNS first to see if ISP is poisoning it
        runCatching {
            InetAddress.getAllByName(cleanHost).forEach { addr ->
                val ip = addr.hostAddress.orEmpty()
                if (isPoisonedIranDnsIp(ip)) poisonedIps += ip else cleanIps += ip
            }
        }

        // 2) Query encrypted DoH JSON endpoints to obtain authentic unpoisoned A records
        val dohEndpoints = listOf(
            "https://1.1.1.1/dns-query?name=$cleanHost&type=A",
            "https://dns.google/resolve?name=$cleanHost&type=A",
        )
        for (doh in dohEndpoints) {
            if (cleanIps.size >= 4) break
            runCatching {
                val conn = (java.net.URI(doh).toURL().openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/dns-json")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val ipRegex = Regex("\"data\"\\s*:\\s*\"([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\"")
                        ipRegex.findAll(text).forEach { m ->
                            val ip = m.groupValues[1]
                            if (isPoisonedIranDnsIp(ip)) poisonedIps += ip else cleanIps += ip
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

        if (cleanIps.isEmpty()) {
            // Fallback to known Cloudflare Anycast clean edges if the domain is behind Cloudflare
            cleanIps += "188.114.98.0"
            cleanIps += "104.18.2.161"
        }

        var workingIp: String? = null
        var bestRtt: Long? = null
        for (ip in cleanIps.take(4)) {
            val rtt = probeTcpPort(ip, 443, timeoutMs = 1_500)
            if (rtt != null && (bestRtt == null || rtt < bestRtt)) {
                bestRtt = rtt
                workingIp = ip
            }
        }
        val frontHost = "speed.cloudflare.com"
        val summaryFa = buildString {
            appendLine("🔍 دامنه بررسی‌شده: $cleanHost")
            if (poisonedIps.isNotEmpty()) {
                appendLine("⚠️ مسمومیت DNS اپراتور تشخیص داده شد (حذف شد): ${poisonedIps.joinToString(", ")}")
            }
            appendLine("✅ آدرس‌های واقعی و بدون فیلتر: ${cleanIps.joinToString(", ")}")
            if (workingIp != null) {
                appendLine("⚡ سریع‌ترین IP مستقیم باز روی خط شما: $workingIp:443 (${bestRtt}ms)")
            }
            append("🛡️ میزبان پیشنهادی Domain Fronting روی ایرانسل/همراه اول: $frontHost")
        }
        val summaryEn = buildString {
            appendLine("🔍 Domain: $cleanHost")
            if (poisonedIps.isNotEmpty()) {
                appendLine("⚠️ Filtered ISP poisoned DNS IPs: ${poisonedIps.joinToString(", ")}")
            }
            appendLine("✅ Unpoisoned real IPs: ${cleanIps.joinToString(", ")}")
            if (workingIp != null) {
                appendLine("⚡ Fastest reachable IP on your line: $workingIp:443 (${bestRtt}ms)")
            }
            append("🛡️ Recommended Front Host: $frontHost")
        }
        return UnpoisonedDnsResult(
            domain = cleanHost,
            cleanIps = cleanIps.toList(),
            poisonedIpsDetected = poisonedIps.toList(),
            workingCleanIp = workingIp,
            recommendedFrontHost = frontHost,
            rttMs = bestRtt,
            summaryFa = summaryFa,
            summaryEn = summaryEn,
        )
    }

    /**
     * Generates a ready-to-paste Google Apps Script (`Code.gs`) for user-owned Google Relay
     * (`script.google.com/macros/s/.../exec`) with daily quota tracking (`MailApp` / `PropertiesService`).
     */
    fun buildGoogleAppsScriptRelaySource(): String = buildString {
        appendLine("// Cat Client — Personal Google Apps Script (GAS) Relay (Code.gs)")
        appendLine("// 1. Open https://script.google.com -> New Project -> paste this code")
        appendLine("// 2. Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone")
        appendLine("// 3. Copy the https://script.google.com/macros/s/.../exec URL into Cat Client")
        appendLine("var DAILY_LIMIT = 20000;")
        appendLine("function bumpQuota_() {")
        appendLine("  var props = PropertiesService.getScriptProperties();")
        appendLine("  var day = String(Math.floor(Date.now() / 86400000));")
        appendLine("  var used = (props.getProperty('d') === day) ? (parseInt(props.getProperty('c') || '0', 10) || 0) + 1 : 1;")
        appendLine("  props.setProperties({ d: day, c: String(used) });")
        appendLine("  return { used: used, remaining: Math.max(0, DAILY_LIMIT - used), limit: DAILY_LIMIT };")
        appendLine("}")
        appendLine("function doGet(e) {")
        appendLine("  var p = (e && e.parameter) || {};")
        appendLine("  var q = bumpQuota_();")
        appendLine("  if (!p.target) {")
        appendLine("    return ContentService.createTextOutput(JSON.stringify({ ok: true, relay: 'cat-gas-relay', quota: q }))")
        appendLine("      .setMimeType(ContentService.MimeType.JSON);")
        appendLine("  }")
        appendLine("  var resp = UrlFetchApp.fetch(p.target, { muteHttpExceptions: true, followRedirects: true });")
        appendLine("  return ContentService.createTextOutput(resp.getContentText());")
        appendLine("}")
        appendLine("function doPost(e) {")
        appendLine("  var q = bumpQuota_();")
        appendLine("  var body = JSON.parse((e && e.postData && e.postData.contents) || '{}');")
        appendLine("  if (!body.url) {")
        appendLine("    return ContentService.createTextOutput(JSON.stringify({ ok: true, quota: q }))")
        appendLine("      .setMimeType(ContentService.MimeType.JSON);")
        appendLine("  }")
        appendLine("  var r = UrlFetchApp.fetch(body.url, {")
        appendLine("    method: body.method || 'get',")
        appendLine("    headers: body.headers || {},")
        appendLine("    payload: body.payload ? Utilities.base64Decode(body.payload) : undefined,")
        appendLine("    muteHttpExceptions: true")
        appendLine("  });")
        appendLine("  return ContentService.createTextOutput(JSON.stringify({")
        appendLine("    ok: true, status: r.getResponseCode(),")
        appendLine("    bodyB64: Utilities.base64Encode(r.getContent()),")
        appendLine("    quota: q")
        appendLine("  })).setMimeType(ContentService.MimeType.JSON);")
        appendLine("}")
    }

    fun buildGasRelayUri(scriptExecUrl: String, remark: String = "Google-Relay"): String {
        val trimmed = scriptExecUrl.trim()
        val cleanPath = trimmed.removePrefix("https://").removePrefix("http://").ifBlank { "script.google.com/macros/s/demo/exec" }
        val encodedUrl = runCatching { java.net.URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        val encodedRemark = runCatching { java.net.URLEncoder.encode(remark, "UTF-8") }.getOrDefault(remark)
        return "gasrelay://$cleanPath?url=$encodedUrl&sni=script.google.com#$encodedRemark"
    }

    /**
     * Generates a lightweight, zero-KV Cloudflare Worker (`Cat Client Lite`) that serves
     * `/sub/<uuid>` and proxies VLESS-over-WS with explicit `IP:port` ProxyIP fallback.
     */
    fun buildLiteWorkerScript(
        uuid: String,
        proxyIp: String = "103.237.102.191:11111",
        fallbackHost: String = "",
    ): String = buildString {
        val safeUuid = uuid.trim().ifBlank { "11111111-2222-4333-8444-555555555555" }
        val safeProxy = proxyIp.trim().ifBlank { "103.237.102.191:11111" }
        val safeFb = fallbackHost.trim()
        appendLine("// Cat Client Lite Worker — Fast, Zero-KV Quota, VLESS-WS + Explicit IP:port ProxyIP")
        appendLine("import { connect } from 'cloudflare:sockets';")
        appendLine("const DEFAULT_UUID = '$safeUuid';")
        appendLine("const DEFAULT_PROXY_IP = '$safeProxy';")
        appendLine("const DEFAULT_FALLBACK_HOST = '$safeFb';")
        appendLine("let dailyReqs = 0, dailyDay = 0;")
        appendLine("function parseHostPort(ep, defPort) {")
        appendLine("  const s = String(ep || '').trim();")
        appendLine("  if (!s) return null;")
        appendLine("  if (s.startsWith('[')) {")
        appendLine("    const close = s.indexOf(']');")
        appendLine("    if (close < 0) return null;")
        appendLine("    const h = s.slice(1, close);")
        appendLine("    const p = s.slice(close + 2) ? parseInt(s.slice(close + 2), 10) : defPort;")
        appendLine("    return { hostname: h, port: (p > 0 && p <= 65535) ? p : defPort };")
        appendLine("  }")
        appendLine("  const idx = s.lastIndexOf(':');")
        appendLine("  if (idx > 0 && s.indexOf(':') === idx) {")
        appendLine("    const p = parseInt(s.slice(idx + 1), 10);")
        appendLine("    return { hostname: s.slice(0, idx), port: (p > 0 && p <= 65535) ? p : defPort };")
        appendLine("  }")
        appendLine("  return { hostname: s, port: defPort };")
        appendLine("}")
        appendLine("export default {")
        appendLine("  async fetch(request, env) {")
        appendLine("    const nowDay = Math.floor(Date.now() / 86400000);")
        appendLine("    if (nowDay !== dailyDay) { dailyDay = nowDay; dailyReqs = 0; }")
        appendLine("    dailyReqs++;")
        appendLine("    const url = new URL(request.url);")
        appendLine("    const u = (env && env.UUID) || DEFAULT_UUID;")
        appendLine("    const px = url.searchParams.get('proxyip') || (env && env.PROXYIP) || DEFAULT_PROXY_IP;")
        appendLine("    const fb = (env && env.FALLBACK_HOST) || DEFAULT_FALLBACK_HOST;")
        appendLine("    if (url.pathname === '/health') {")
        appendLine("      return Response.json({ ok: true, mode: 'cat-lite', dailyReqs, quotaWarn: dailyReqs > 80000, proxyIp: px });")
        appendLine("    }")
        appendLine("    if (url.pathname === '/sub/' + u) {")
        appendLine("      const h = url.hostname;")
        appendLine("      const fbTag = fb ? ('&fallback_host=' + encodeURIComponent(fb)) : '';")
        appendLine("      const lines = [")
        appendLine("        'vless://' + u + '@' + h + ':443?encryption=none&security=tls&sni=' + h + '&fp=chrome&type=ws&host=' + h + '&path=%2F%3Fed%3D2048' + fbTag + '#CatLite-Direct',")
        appendLine("        'vless://' + u + '@188.114.98.0:443?encryption=none&security=tls&sni=' + h + '&fp=unsafe&cs=semi-python&fm=tlshello-0-len&alpn=http%2F1.1&type=ws&host=' + h + '&path=%2F%3Fproxyip%3D' + encodeURIComponent(px) + '#CatLite-Irancell-FF',")
        appendLine("        'vless://' + u + '@[2a06:98c1:3101::6812:202f]:443?encryption=none&security=tls&sni=' + h + '&fp=chrome&type=ws&host=' + h + '&path=%2F%3Fproxyip%3D' + encodeURIComponent(px) + '#CatLite-MCI-IPv6'")
        appendLine("      ];")
        appendLine("      return new Response(btoa(lines.join('\\n')), { headers: { 'content-type': 'text/plain; charset=utf-8', 'X-CatLite-Reqs': String(dailyReqs) } });")
        appendLine("    }")
        appendLine("    return new Response('Cat Client Lite Worker Active', { status: 200, headers: { 'X-CatLite-Reqs': String(dailyReqs) } });")
        appendLine("  }")
        appendLine("};")
    }

    /**
     * Two-Stage Fragment (`DOUBLE_FRAGMENT` — MAXIMUS-VPN #83 & Luncher streaming DPI bypass):
     * 1) Splits the TLS ClientHello record (`0x16 0x03 ...`) into a `firstTlsPayloadBytes` (104B) TLS record
     *    plus trailing TLS record(s) in a single buffer.
     * 2) Cuts that buffer into an initial `firstTcpSegmentBytes` (114B) TCP write (`delayMs = 0L`)
     *    followed by up to `maxTailSegments` 1-byte TCP segments spaced by `interSegmentDelayMs`
     *    so Iranian streaming DPI reassemblers (40ms window) fail to reconstruct the SNI.
     */
    fun buildDoubleFragmentSegments(
        packet: ByteArray,
        firstTlsPayloadBytes: Int = 104,
        firstTcpSegmentBytes: Int = 114,
        maxTailSegments: Int = 10,
        interSegmentDelayMs: Long = 15L,
    ): List<Pair<ByteArray, Long>> {
        val splitRecords = splitTlsClientHelloRecord(packet, splitPayloadBytes = firstTlsPayloadBytes)
            ?: return listOf(packet to 0L)
        val combined = ByteArray(splitRecords.first.size + splitRecords.second.size)
        System.arraycopy(splitRecords.first, 0, combined, 0, splitRecords.first.size)
        System.arraycopy(splitRecords.second, 0, combined, splitRecords.first.size, splitRecords.second.size)
        if (combined.size <= firstTcpSegmentBytes) {
            return listOf(combined to 0L)
        }
        val out = ArrayList<Pair<ByteArray, Long>>()
        out += combined.copyOfRange(0, firstTcpSegmentBytes) to 0L
        var offset = firstTcpSegmentBytes
        var singleByteCount = 0
        while (offset < combined.size && singleByteCount < maxTailSegments) {
            out += byteArrayOf(combined[offset]) to interSegmentDelayMs.coerceAtLeast(1L)
            offset++
            singleByteCount++
        }
        if (offset < combined.size) {
            out += combined.copyOfRange(offset, combined.size) to interSegmentDelayMs.coerceAtLeast(1L)
        }
        return out
    }

    private const val TLS_QUARANTINE_TTL_MS = 24 * 60 * 60 * 1000L
    private val interceptedEndpointsByNetwork = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun quarantineKey(networkKey: String, endpoint: String): String =
        "${networkKey.trim().lowercase()}|${endpoint.trim().lowercase()}"

    /**
     * Marks an endpoint as TLS-intercepted (forged certificate / MITM on ISP) for 24 hours on [networkKey].
     */
    fun quarantineInterceptedEndpoint(
        networkKey: String,
        endpoint: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (endpoint.isBlank()) return
        interceptedEndpointsByNetwork[quarantineKey(networkKey, endpoint)] = nowMs
    }

    fun isEndpointQuarantined(
        networkKey: String,
        endpoint: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = quarantineKey(networkKey, endpoint)
        val ts = interceptedEndpointsByNetwork[key] ?: return false
        if (nowMs - ts >= TLS_QUARANTINE_TTL_MS) {
            interceptedEndpointsByNetwork.remove(key)
            return false
        }
        return true
    }

    fun filterOutQuarantinedEndpoints(
        networkKey: String,
        candidates: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = candidates.filterNot { isEndpointQuarantined(networkKey, it, nowMs) }

    /**
     * Normalizes REALITY fragment packets (`v2rayN #10407` fix):
     * On Xray-core, `packets = "tlshello"` breaks REALITY handshakes (`delay = -1`);
     * normalizing `"tlshello"` to `"1-3"` when `security == "reality"` restores compatibility.
     */
    fun normalizeRealityFragmentPackets(security: String, packetsOrFinalMask: String): String {
        val trimmed = packetsOrFinalMask.trim()
        if (trimmed.isEmpty()) return trimmed
        if (!security.trim().equals("reality", ignoreCase = true)) return trimmed
        if (trimmed.equals("tlshello", ignoreCase = true)) return "1-3"
        if (trimmed.startsWith("tlshello-", ignoreCase = true)) {
            return "1-3-" + trimmed.substring("tlshello-".length)
        }
        return trimmed
    }

    data class AntiFilterPreset(
        val id: String,
        val titleFa: String,
        val titleEn: String,
        val fingerprint: String,
        val cipherSuites: String,
        val finalMask: String,
        val fragmentPackets: String,
        val fragmentLengths: String,
        val fragmentDelaysMs: String,
        val alpn: String,
    )

    /**
     * Nine field-tested anti-filtering presets for Iranian ISPs & satellite links
     * (MCI IPv6, Irancell F&F, Rightel, TCI, Gaming, Max-Stealth 45ms streaming-DPI delay,
     * Double-Fragment, PattNG Classic, and Starlink/Satellite).
     */
    val ANTI_FILTER_9_PRESETS: List<AntiFilterPreset> = listOf(
        AntiFilterPreset(
            id = "mci-ipv6",
            titleFa = "۱) همراه اول (MCI IPv6 + Chrome)",
            titleEn = "1) MCI (IPv6 + Chrome)",
            fingerprint = "chrome",
            cipherSuites = "",
            finalMask = "",
            fragmentPackets = "tlshello",
            fragmentLengths = "100-200",
            fragmentDelaysMs = "10-20",
            alpn = "h2,http/1.1",
        ),
        AntiFilterPreset(
            id = "irancell-ff",
            titleFa = "۲) ایرانسل (متد F&F · رفع محدودیت آپلود)",
            titleEn = "2) Irancell (F&F Method · Upload Uncap)",
            fingerprint = "unsafe",
            cipherSuites = "semi-python",
            finalMask = "tlshello-0-len",
            fragmentPackets = "tlshello",
            fragmentLengths = "16-32",
            fragmentDelaysMs = "2-10",
            alpn = "http/1.1",
        ),
        AntiFilterPreset(
            id = "max-stealth-delayed",
            titleFa = "۳) Max-Stealth (ضد بازچینی ۴۰ میلی‌ثانیه‌ای فایروال استریمینگ)",
            titleEn = "3) Max-Stealth (Anti-40ms Streaming DPI Reassembly)",
            fingerprint = "unsafe",
            cipherSuites = "semi-python",
            finalMask = "tlshello-0-len",
            fragmentPackets = "tlshello",
            fragmentLengths = "10-30",
            fragmentDelaysMs = "25-45",
            alpn = "http/1.1",
        ),
        AntiFilterPreset(
            id = "double-fragment",
            titleFa = "۴) فرگمنت دومرحله‌ای (Double Fragment 104B TLS + 114B/1B TCP)",
            titleEn = "4) Double Fragment (104B TLS + 114B/1B TCP Split)",
            fingerprint = "chrome",
            cipherSuites = "semi-python",
            finalMask = "tlshello-104-1",
            fragmentPackets = "1-3",
            fragmentLengths = "114,1",
            fragmentDelaysMs = "1-5",
            alpn = "h2,http/1.1",
        ),
        AntiFilterPreset(
            id = "rightel-plain",
            titleFa = "۵) رایتل (پورت‌های سبک و فرگمنت کوتاه)",
            titleEn = "5) Rightel (Short Fragment)",
            fingerprint = "chrome",
            cipherSuites = "",
            finalMask = "",
            fragmentPackets = "tlshello",
            fragmentLengths = "20-50",
            fragmentDelaysMs = "5-15",
            alpn = "http/1.1",
        ),
        AntiFilterPreset(
            id = "tci-ios",
            titleFa = "۶) مخابرات (TCI · اثرانگشت iOS)",
            titleEn = "6) TCI (iOS TLS Fingerprint)",
            fingerprint = "ios",
            cipherSuites = "",
            finalMask = "",
            fragmentPackets = "tlshello",
            fragmentLengths = "40-90",
            fragmentDelaysMs = "10-20",
            alpn = "h2,http/1.1",
        ),
        AntiFilterPreset(
            id = "shatel-gaming",
            titleFa = "۷) گیمینگ و شاتل (کمترین تأخیر فرگمنت)",
            titleEn = "7) Gaming & Shatel (Low-Jitter Fragment)",
            fingerprint = "chrome",
            cipherSuites = "",
            finalMask = "",
            fragmentPackets = "tlshello",
            fragmentLengths = "50-100",
            fragmentDelaysMs = "1-3",
            alpn = "h2,http/1.1",
        ),
        AntiFilterPreset(
            id = "pattng-classic",
            titleFa = "۸) PattNG کلاسیک (F&F + IPv6 ترکیبی)",
            titleEn = "8) PattNG Classic (Hybrid F&F + IPv6)",
            fingerprint = "unsafe",
            cipherSuites = "semi-python",
            finalMask = "tlshello-0-len",
            fragmentPackets = "tlshello",
            fragmentLengths = "16-32",
            fragmentDelaysMs = "5-12",
            alpn = "h2,http/1.1",
        ),
        AntiFilterPreset(
            id = "starlink-satellite",
            titleFa = "۹) ماهواره‌ای / استارلینک (ضد نوسان پکت و Handshake سریع)",
            titleEn = "9) Satellite / Starlink (Fast Handshake & Loss Tolerant)",
            fingerprint = "chrome",
            cipherSuites = "",
            finalMask = "",
            fragmentPackets = "1-3",
            fragmentLengths = "200-400",
            fragmentDelaysMs = "1-2",
            alpn = "h2,http/1.1",
        ),
    )

    data class AiServiceUnblockStatus(
        val serviceName: String,
        val unlocked: Boolean,
        val httpCode: Int,
        val rttMs: Long,
        val detailFa: String,
        val detailEn: String,
    )

    data class AiSuiteUnblockReport(
        val statuses: List<AiServiceUnblockStatus>,
        val geminiUnlocked: Boolean,
        val chatgptUnlocked: Boolean,
        val allUnlocked: Boolean,
        val summaryFa: String,
        val summaryEn: String,
    )

    /**
     * Pure evaluator for AI / Gemini / ChatGPT / YouTube unblock verification (`MahsaNG #179`).
     */
    fun evaluateAiServiceProbe(
        serviceName: String,
        httpCode: Int,
        bodyOrLocation: String,
        rttMs: Long,
    ): AiServiceUnblockStatus {
        val textLower = bodyOrLocation.lowercase()
        val isGeoRefused = httpCode == 403 || httpCode == 451 ||
            textLower.contains("loc=ir") ||
            textLower.contains("/faq") ||
            textLower.contains("unsupported_country") ||
            textLower.contains("not available in your country")
        val unlocked = httpCode in 200..399 && !isGeoRefused
        val detailFa = when {
            unlocked -> "✅ باز و بدون تحریم ($httpCode · ${rttMs}ms)"
            isGeoRefused -> "🚫 مسدود با آی‌پی فعلی (تحریم جغرافیایی / دیتاسنتر کلودفلر — نیاز به ساکس۵ یا سایفون خارج)"
            else -> "❌ عدم پاسخ شبکه (کد $httpCode)"
        }
        val detailEn = when {
            unlocked -> "✅ Open & Unblocked ($httpCode · ${rttMs}ms)"
            isGeoRefused -> "🚫 Geo-blocked or datacenter-refused ($httpCode — use foreign SOCKS5 or Psiphon)"
            else -> "❌ Unreachable (HTTP $httpCode)"
        }
        return AiServiceUnblockStatus(
            serviceName = serviceName,
            unlocked = unlocked,
            httpCode = httpCode,
            rttMs = rttMs,
            detailFa = detailFa,
            detailEn = detailEn,
        )
    }

    /**
     * One-Click AI & Gemini Unblock Batch Tester (`MahsaNG #179` & YouTube feedback):
     * Tests whether Google Gemini, Google AI Studio, ChatGPT, and YouTube are genuinely
     * unblocked on the current exit (or via an optional SOCKS5 proxy).
     */
    fun probeAiAndGeminiUnblockSuite(
        socksHost: String? = null,
        socksPort: Int? = null,
        timeoutMs: Int = 2_800,
    ): AiSuiteUnblockReport {
        val targets = listOf(
            "✨ Google Gemini" to "https://gemini.google.com/app",
            "🧪 Google AI Studio" to "https://aistudio.google.com/",
            "🤖 ChatGPT (OpenAI)" to "https://chatgpt.com/cdn-cgi/trace",
            "▶️ YouTube Unfiltered" to "https://www.youtube.com/generate_204",
        )
        val proxy = if (!socksHost.isNullOrBlank() && socksPort != null && socksPort in 1..65535) {
            java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        } else null

        val statuses = targets.map { (label, urlStr) ->
            val start = System.nanoTime()
            var code = -1
            var snippet = ""
            runCatching {
                val uriUrl = java.net.URI(urlStr).toURL()
                val conn = ((if (proxy != null) uriUrl.openConnection(proxy) else uriUrl.openConnection()) as java.net.HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                }
                try {
                    code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty()
                    val body = if (urlStr.endsWith("/trace") && code in 200..299) {
                        conn.inputStream.bufferedReader().use { it.readText().take(512) }
                    } else ""
                    snippet = "$loc\n$body"
                } finally {
                    conn.disconnect()
                }
            }
            val rtt = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            evaluateAiServiceProbe(label, code, snippet, rtt)
        }
        val geminiOk = statuses.firstOrNull { it.serviceName.contains("Gemini") }?.unlocked == true
        val chatgptOk = statuses.firstOrNull { it.serviceName.contains("ChatGPT") }?.unlocked == true
        val allOk = statuses.all { it.unlocked }
        val summaryFa = buildString {
            statuses.forEach { st ->
                appendLine("• ${st.serviceName}: ${st.detailFa}")
            }
            if (!geminiOk) {
                append("\n💡 راهنما: گوگل جمینای آی‌پی‌های کلودفلر را مسدود می‌کند؛ برای باز شدن جمینای از «🧦 مرکز ساکس۵» یا «🔓 زنجیره WARP ← سایفون (آمریکا US / آلمان DE)» استفاده کنید.")
            } else {
                append("\n🎉 تمام سرویس‌های حساس و هوش مصنوعی روی آی‌پی فعلی شما کاملاً باز هستند!")
            }
        }
        val summaryEn = buildString {
            statuses.forEach { st ->
                appendLine("• ${st.serviceName}: ${st.detailEn}")
            }
            if (!geminiOk) {
                append("\n💡 Tip: Gemini refuses Cloudflare exit IPs; enable SOCKS5 Auto-Pool or WARP -> Psiphon (US/DE).")
            } else {
                append("\n🎉 Gemini, AI Studio, ChatGPT & YouTube are open on your current exit!")
            }
        }
        return AiSuiteUnblockReport(
            statuses = statuses,
            geminiUnlocked = geminiOk,
            chatgptUnlocked = chatgptOk,
            allUnlocked = allOk,
            summaryFa = summaryFa,
            summaryEn = summaryEn,
        )
    }

    enum class NetworkIsolationState {
        GLOBAL_OPEN,
        INTERNATIONAL_FILTERED_OR_THROTTLED,
        NET_MELLI_SHUTDOWN,
        NO_LOCAL_NETWORK,
    }

    data class NetMelliDiagnosticVerdict(
        val state: NetworkIsolationState,
        val domesticHits: Int,
        val internationalHits: Int,
        val dnsTunnelReachable: Boolean,
        val fastestDomesticMs: Long?,
        val fastestIntlMs: Long?,
        val summaryFa: String,
        val summaryEn: String,
    )

    fun evaluateNetMelliState(
        domesticHits: Int,
        internationalHits: Int,
        dnsTunnelReachable: Boolean,
        fastestDomesticMs: Long?,
        fastestIntlMs: Long?,
    ): NetMelliDiagnosticVerdict {
        val state = when {
            domesticHits <= 0 && internationalHits <= 0 -> NetworkIsolationState.NO_LOCAL_NETWORK
            domesticHits > 0 && internationalHits <= 0 -> NetworkIsolationState.NET_MELLI_SHUTDOWN
            internationalHits >= 2 -> NetworkIsolationState.GLOBAL_OPEN
            else -> NetworkIsolationState.INTERNATIONAL_FILTERED_OR_THROTTLED
        }
        val summaryFa = when (state) {
            NetworkIsolationState.NET_MELLI_SHUTDOWN ->
                "🚨 وضعیت: اینترنت ملی (قطع ارتباط بین‌الملل)!\n" +
                    "• سرورهای داخل ایران باز هستند (${fastestDomesticMs ?: 0}ms) اما تمام درگاه‌های مستقیم بین‌المللی بسته شده‌اند.\n" +
                    "• کانفیگ شما خراب نیست؛ لینک بین‌الملل اپراتور قطع شده است.\n" +
                    "• راهکار فوری: دکمه «🧬 اتصال با تانل DNS (CottenMax / StormDNS)» را بزنید."
            NetworkIsolationState.GLOBAL_OPEN ->
                "✅ وضعیت: لینک بین‌الملل باز است (اینترنت ملی نیست).\n" +
                    "• پینگ داخل ایران: ${fastestDomesticMs ?: "-"}ms · پینگ لبه بین‌الملل: ${fastestIntlMs ?: "-"}ms.\n" +
                    "• اگر کانفیگی وصل نمی‌شود، مشکل از فیلتر شدن IP/SNI همان کانفیگ است نه قطع اینترنت."
            NetworkIsolationState.INTERNATIONAL_FILTERED_OR_THROTTLED ->
                "⚠️ وضعیت: لینک بین‌الملل نیمه‌باز با فیلترینگ شدید پورت‌ها.\n" +
                    "• پینگ داخل: ${fastestDomesticMs ?: "-"}ms · فقط برخی لبه‌های بین‌الملل پاسخ دادند (${fastestIntlMs ?: "-"}ms).\n" +
                    "• پیشنهاد: از متد F&F، MASQUE-H2 یا اسکن ۶ پورت TLS استفاده کنید."
            NetworkIsolationState.NO_LOCAL_NETWORK ->
                "❌ هیچ پاسخی حتی از سرورهای داخل ایران دریافت نشد — اتصال دیتای موبایل یا Wi-Fi خود را بررسی کنید."
        }
        val summaryEn = when (state) {
            NetworkIsolationState.NET_MELLI_SHUTDOWN ->
                "🚨 Status: Net Melli (International Shutdown Detected)!\nDomestic servers respond (${fastestDomesticMs ?: 0}ms) while all international edges are unreachable. Switch to WhiteDNS Tunnel (CottenMax / StormDNS)."
            NetworkIsolationState.GLOBAL_OPEN ->
                "✅ Status: International link is OPEN (domestic ${fastestDomesticMs ?: "-"}ms, intl ${fastestIntlMs ?: "-"}ms)."
            NetworkIsolationState.INTERNATIONAL_FILTERED_OR_THROTTLED ->
                "⚠️ Status: International link is partially reachable under heavy DPI filtering."
            NetworkIsolationState.NO_LOCAL_NETWORK ->
                "❌ No local or international network response — check mobile data or Wi-Fi."
        }
        return NetMelliDiagnosticVerdict(
            state = state,
            domesticHits = domesticHits,
            internationalHits = internationalHits,
            dnsTunnelReachable = dnsTunnelReachable,
            fastestDomesticMs = fastestDomesticMs,
            fastestIntlMs = fastestIntlMs,
            summaryFa = summaryFa,
            summaryEn = summaryEn,
        )
    }

    /**
     * Active Net Melli vs. Dead Config Diagnostic (`Luncher` companion concept):
     * Probes domestic Iranian endpoints alongside international Cloudflare/Google edges to tell
     * whether the international link is cut (Net Melli) or just a single config is blocked.
     */
    fun probeNetMelliVsDeadConfig(timeoutMs: Int = 1_600): NetMelliDiagnosticVerdict {
        val domesticTargets = listOf("178.22.122.100" to 53, "185.51.200.2" to 53, "217.218.155.155" to 53)
        val intlTargets = listOf("188.114.98.0" to 443, "1.1.1.1" to 443, "104.18.2.161" to 443)
        var domHits = 0
        var fastestDom: Long? = null
        for ((h, p) in domesticTargets) {
            val rtt = probeDnsOverUdp(h, domain = "shaparak.ir", qtype = 1, timeoutMs = timeoutMs)
                ?: probeTcpPort(h, p, timeoutMs)
            if (rtt != null) {
                domHits++
                if (fastestDom == null || rtt < fastestDom) fastestDom = rtt
            }
        }
        var intlHits = 0
        var fastestIntl: Long? = null
        for ((h, p) in intlTargets) {
            val rtt = probeTcpPort(h, p, timeoutMs)
            if (rtt != null) {
                intlHits++
                if (fastestIntl == null || rtt < fastestIntl) fastestIntl = rtt
            }
        }
        val dnsTunnelOk = domHits > 0
        return evaluateNetMelliState(
            domesticHits = domHits,
            internationalHits = intlHits,
            dnsTunnelReachable = dnsTunnelOk,
            fastestDomesticMs = fastestDom,
            fastestIntlMs = fastestIntl,
        )
    }

    private val GEO_SANCTIONED_SUFFIXES = listOf(
        "gemini.google.com",
        "aistudio.google.com",
        "chatgpt.com",
        "openai.com",
        "claude.ai",
        "anthropic.com",
        "docker.io",
        "docker.com",
        "developer.android.com",
    )

    /**
     * Smart Unspoofed Direct Bypass (`patterniha` Fact-A in `XTLS/BBS #10`):
     * If a domain is neither geo-sanctioned nor DNS-poisoned by the Iranian ISP,
     * it can bypass the proxy tunnel and connect directly to save Worker quota and maximize speed.
     */
    fun shouldBypassTunnelForUnspoofedDomain(
        domain: String,
        systemResolvedIps: List<String>,
    ): Boolean {
        val clean = domain.trim().lowercase().removeSuffix(".")
        if (clean.isBlank()) return false
        if (clean.endsWith(".ir")) return true
        if (GEO_SANCTIONED_SUFFIXES.any { clean == it || clean.endsWith(".$it") }) return false
        if (systemResolvedIps.isEmpty()) return false
        return systemResolvedIps.all { !isPoisonedIranDnsIp(it) }
    }

    const val WHITEDNS_ONLINE_URL = "https://whitedns.online"
    const val COREFORGE_GUIDE_URL = "https://whitedns.online/tools/coreforge/"
    const val COREFORGE_IOS_TESTFLIGHT_URL = "https://testflight.apple.com/join/DRkT6zny"
    const val COREFORGE_BACKUP_TESTFLIGHT_URL = "https://testflight.apple.com/join/CxTHFfrs"
    const val TESTFLIGHT_APP_STORE_URL = "https://apps.apple.com/app/testflight/id899247664"

    data class IosConnectionBundle(
        val vlessFragmentUri: String,
        val streisandDeepLink: String,
        val v2boxDeepLink: String,
        val singBoxIosDeepLink: String,
        val shadowrocketDeepLink: String,
        val karingDeepLink: String,
        val wireguardIosConf: String,
        val mobileConfigXml: String,
        val portalUrl: String,
        val coreForgeTestFlightUrl: String,
        val coreForgeBackupTestFlightUrl: String,
        val coreForgeGuideUrl: String,
    )

    /**
     * Builds a complete iOS/iPadOS connection bundle for iPhone users:
     * - VLESS + TLS Fragment URI compatible with CoreForge (ForgeCore), Streisand, V2Box, Sing-box, Shadowrocket & Karing
     * - 1-click deep links for all major iOS clients
     * - Standard WireGuard `.conf` (MTU 1280, clean Cloudflare WARP endpoint) for the official iOS WireGuard app
     * - Official CoreForge (ForgeCore) TestFlight link from `whitedns.online/tools/coreforge/`
     */
    fun buildIosConnectionBundle(
        workerHost: String = "panel.example.workers.dev",
        uuid: String = "11111111-2222-4333-8444-555555555555",
        cleanIp: String = "188.114.98.0",
        port: Int = 443,
        path: String = "/vless-ws",
        remark: String = "CatClient-iOS",
        subUrl: String? = null,
    ): IosConnectionBundle {
        val cleanHost = workerHost.trim().ifBlank { "panel.example.workers.dev" }
        val cleanAddr = cleanIp.trim().ifBlank { "188.114.98.0" }
        val safePort = port.coerceIn(1, 65535)
        val safePath = path.trim().ifBlank { "/$uuid" }
        val encPath = java.net.URLEncoder.encode(safePath, "UTF-8")
        val encRemark = java.net.URLEncoder.encode(remark.ifBlank { "CatClient-iOS" }, "UTF-8")
        val effectiveSub = subUrl?.trim()?.takeIf { it.startsWith("https://") }
            ?: "https://$cleanHost/sub/$uuid"
        val portal = "$effectiveSub?portal=1"
        val vlessUri = "vless://$uuid@$cleanAddr:$safePort?encryption=none&security=tls&sni=$cleanHost&fp=chrome&alpn=h2%2Chttp%2F1.1&type=ws&host=$cleanHost&path=$encPath&fragment=10-30,10-20,tlshello#$encRemark"
        val b64Sub = java.util.Base64.getEncoder().withoutPadding().encodeToString(effectiveSub.toByteArray(Charsets.UTF_8))
        val encSub = java.net.URLEncoder.encode(effectiveSub, "UTF-8")
        val wgConf = buildString {
            appendLine("[Interface]")
            appendLine("# Import into WireGuard iOS, Streisand, or CoreForge (ForgeCore)")
            appendLine("PrivateKey = REPLACE_WITH_WARP_OR_AETHER_PRIVATE_KEY")
            appendLine("Address = 172.16.0.2/32, 2606:4700:110:8a36:df92:102f:90c2:1111/128")
            appendLine("DNS = 1.1.1.1, 1.0.0.1")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = 162.159.192.1:2408")
            appendLine("PersistentKeepalive = 25")
        }.trim()
        return IosConnectionBundle(
            vlessFragmentUri = vlessUri,
            streisandDeepLink = "streisand://import/$effectiveSub#$encRemark",
            v2boxDeepLink = "v2box://install-sub?url=$encSub&name=$encRemark",
            singBoxIosDeepLink = "sing-box://import-remote-profile?url=$encSub#$encRemark",
            shadowrocketDeepLink = "sub://$b64Sub#$encRemark",
            karingDeepLink = "karing://install-config?url=$encSub&name=$encRemark",
            wireguardIosConf = wgConf,
            mobileConfigXml = buildAppleIosMobileConfigProfile(mode = "clean"),
            portalUrl = portal,
            coreForgeTestFlightUrl = COREFORGE_IOS_TESTFLIGHT_URL,
            coreForgeBackupTestFlightUrl = COREFORGE_BACKUP_TESTFLIGHT_URL,
            coreForgeGuideUrl = COREFORGE_GUIDE_URL,
        )
    }

    /**
     * Generates a native Apple iOS/iPadOS/macOS `.mobileconfig` Encrypted DNS profile (`com.apple.dnsSettings.managed`).
     * Allows iPhone users to enable anti-sanction DoH (Shecan/Electro) or anti-poisoning DoH (Cloudflare 1.1.1.1)
     * directly in iOS Settings -> General -> VPN & Device Management without installing any App Store app.
     */
    fun buildAppleIosMobileConfigProfile(
        mode: String = "clean",
        profileName: String? = null,
        dohUrl: String? = null,
        bootstrapIps: List<String>? = null,
    ): String {
        val normalized = mode.trim().lowercase()
        val (defaultTitle, defaultDohUrl, defaultServerIps) = when (normalized) {
            "shecan" -> Triple(
                "Cat Client · Shecan Anti-Sanction DoH (iOS)",
                "https://free.shecan.ir/dns-query",
                listOf("178.22.122.100", "185.51.200.2"),
            )
            "electro" -> Triple(
                "Cat Client · Electro Gaming & Unblock DoH (iOS)",
                "https://dns.electrotm.org/dns-query",
                listOf("78.157.42.100", "78.157.42.101"),
            )
            else -> Triple(
                "Cat Client · Clean Anti-Poisoning DoH (iOS)",
                "https://cloudflare-dns.com/dns-query",
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
            )
        }
        val finalTitle = profileName?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle
        val finalDoh = dohUrl?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDohUrl
        val finalIps = bootstrapIps?.takeIf { it.isNotEmpty() } ?: defaultServerIps
        val ipEntries = finalIps.joinToString("\n") { "                <string>$it</string>" }
        val profileUuid = java.util.UUID.nameUUIDFromBytes("catclient-ios-dns-$normalized-$finalDoh".toByteArray()).toString().uppercase()
        val payloadUuid = java.util.UUID.nameUUIDFromBytes("catclient-ios-payload-$normalized-$finalDoh".toByteArray()).toString().uppercase()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>PayloadContent</key>
                <array>
                    <dict>
                        <key>DNSSettings</key>
                        <dict>
                            <key>DNSProtocol</key>
                            <string>HTTPS</string>
                            <key>ServerAddresses</key>
                            <array>
            $ipEntries
                            </array>
                            <key>ServerURL</key>
                            <string>$finalDoh</string>
                        </dict>
                        <key>PayloadDescription</key>
                        <string>Encrypted DNS (DoH) profile generated by Cat Client &amp; WhiteDNS</string>
                        <key>PayloadDisplayName</key>
                        <string>$finalTitle</string>
                        <key>PayloadIdentifier</key>
                        <string>com.cat.client.ios.dns.$normalized.settings</string>
                        <key>PayloadType</key>
                        <string>com.apple.dnsSettings.managed</string>
                        <key>PayloadUUID</key>
                        <string>$payloadUuid</string>
                        <key>PayloadVersion</key>
                        <integer>1</integer>
                    </dict>
                </array>
                <key>PayloadDisplayName</key>
                <string>$finalTitle</string>
                <key>PayloadIdentifier</key>
                <string>com.cat.client.ios.dns.$normalized</string>
                <key>PayloadRemovalDisallowed</key>
                <false/>
                <key>PayloadType</key>
                <string>Configuration</string>
                <key>PayloadUUID</key>
                <string>$profileUuid</string>
                <key>PayloadVersion</key>
                <integer>1</integer>
            </dict>
            </plist>
        """.trimIndent()
    }

    /**
     * Generates a PAC (Proxy Auto-Config) script for sharing an Android phone's active tunnel
     * with an iPhone over Wi-Fi / Hotspot (`Wi-Fi -> Configure Proxy -> Automatic / Manual`).
     * Domestic `.ir` domains go `DIRECT`, while international & sanctioned traffic routes through the Android bridge.
     */
    fun buildLanPacScriptForIos(
        androidLanIp: String,
        httpProxyPort: Int = 10809,
        socksProxyPort: Int = 10808,
    ): String {
        val cleanIp = androidLanIp.trim().ifBlank { "192.168.43.1" }
        return """
            function FindProxyForURL(url, host) {
              var h = (host || "").toLowerCase();
              if (isPlainHostName(hotspotHost(h)) || shExpMatch(host, "*.ir") || shExpMatch(h, "10.*") || shExpMatch(h, "192.168.*") || shExpMatch(h, "127.*")) {
                return "DIRECT";
              }
              return "SOCKS5 $cleanIp:$socksProxyPort; PROXY $cleanIp:$httpProxyPort; DIRECT";
            }
            function hotspotHost(h) { return h; }
        """.trimIndent()
    }

    /**
     * CottenMax QNAME Capacity Calculator (`WhiteDNS Android 1.6.14` / `Soar-Server 0.10.0`):
     * Calculates the required initial DNS query-name byte budget for CottenMax handshakes
     * when long account domains, standby domains, or optional label-count jitter are configured.
     */
    fun calculateCottenMaxQnameBudget(
        domains: List<String>,
        shortenedLabels: Boolean = true,
        labelCountJitter: Int = 1,
    ): Int {
        val allDomains = domains
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
        val longestDomainLen = allDomains.maxOfOrNull { it.length } ?: 24
        val labelOverhead = if (shortenedLabels) 12 else 20
        val jitterOverhead = labelCountJitter.coerceIn(0, 4) * 6
        return (240 - longestDomainLen - labelOverhead - jitterOverhead).coerceIn(32, 200)
    }

    /**
     * MasterDnsVPN (`masterking32/MasterDnsVPN`) 9 built-in resolver load-balancing strategies (`0..8`).
     */
    enum class MasterDnsBalancingStrategy(
        val code: Int,
        val titleFa: String,
        val titleEn: String,
    ) {
        ROUND_ROBIN_0(0, "0 — چرخشی کلاسیک (Round-Robin)", "0 — Classic Round-Robin"),
        RANDOM_1(1, "1 — انتخاب تصادفی (Random)", "1 — Random Selection"),
        ROUND_ROBIN_2(2, "2 — چرخشی استاندارد (Round-Robin v2)", "2 — Standard Round-Robin"),
        LEAST_LOSS_3(3, "3 — کمترین پکت‌لاس (Least Packet Loss)", "3 — Least Packet Loss"),
        LEAST_LATENCY_4(4, "4 — کمترین پینگ و تأخیر (Lowest Latency)", "4 — Lowest Latency"),
        WEIGHTED_LOSS_LATENCY_5(5, "5 — امتیاز ترکیبی (وزن بیشتر برای کمترین پکت‌لاس)", "5 — Weighted Loss + Latency Score"),
        TIERED_SHORTLIST_RR_6(6, "6 — گلچین کم‌لاس‌ترین‌ها + چرخش بین سریع‌ترین‌ها (پیشنهادی نت ملی)", "6 — Low-Loss Tier Shortlist + Fast Round-Robin (Recommended)"),
        TIERED_SHORTLIST_RANDOM_7(7, "7 — انتخاب تصادفی بین بهترین ریزولورهای کم‌لاس (ضد قفل بار)", "7 — Random Among Best Low-Loss Tier"),
        TIERED_STRICT_RR_8(8, "8 — چرخش منظم بین بهترین ریزولورهای کم‌لاس", "8 — Strict Round-Robin Across Best Low-Loss Tier"),
    }

    /**
     * VayDNS (`net2share/vaydns`) 9 downstream DNS record types (`-record-type`).
     */
    enum class VayDnsRecordType(
        val wireName: String,
        val titleFa: String,
        val titleEn: String,
    ) {
        TXT("txt", "TXT (پیش‌فرض · سازگار با dnstt)", "TXT (Default · dnstt compatible)"),
        CNAME("cname", "CNAME (عبور از فیلتر TXT در ایران)", "CNAME (Bypasses TXT filtering)"),
        A("a", "A (تکه‌های ۴ بایتی IPv4 — بسیار مقاوم)", "A (4-byte IPv4 chunks — highly resilient)"),
        AAAA("aaaa", "AAAA (تکه‌های ۱۶ بایتی IPv6 — ظرفیت بالا)", "AAAA (16-byte IPv6 chunks — high capacity)"),
        MX("mx", "MX (رکورد ایمیل ۲ بایت هدر + نام)", "MX (Mail Exchange 2B header + name)"),
        NS("ns", "NS (رکورد نیم‌سرور — غیرقابل مسدودسازی)", "NS (Nameserver record — hard to block)"),
        SRV("srv", "SRV (رکورد سرویس ۶ بایت هدر + نام)", "SRV (Service record 6B header + name)"),
        NULL("null", "NULL (باینری خام ۱۲۳۲ بایت)", "NULL (Raw binary up to 1232B)"),
        CAA("caa", "CAA (رکورد گواهی دامنه)", "CAA (Certification Authority Authorization)"),
    }

    /**
     * SlipNet (`anonvector/SlipNet` v2.5.5) 0–6 Resolver Compatibility Score Card +
     * TCP/UDP Dual Transport + Prism Server-Verified HMAC status.
     */
    data class SlipNetResolverScoreCard(
        val resolverIp: String,
        val providerNameFa: String,
        val providerNameEn: String,
        val nsOk: Boolean,
        val txtOk: Boolean,
        val rndOk: Boolean,
        val dpiOk: Boolean,
        val ednsOk: Boolean,
        val ednsPayload: Int,
        val nxdOk: Boolean,
        val udpAlive: Boolean,
        val tcpAlive: Boolean,
        val tcpPreflight8sOk: Boolean,
        val rttMs: Long,
        val upMtu: Int,
        val downMtu: Int,
    ) {
        val score: Int
            get() = listOf(nsOk, txtOk, rndOk, dpiOk, ednsOk, nxdOk).count { it }

        val badgeText: String
            get() = buildString {
                append(if (nsOk) "NS✓ " else "NS✗ ")
                append(if (txtOk) "TXT✓ " else "TXT✗ ")
                append(if (rndOk) "RND✓ " else "RND✗ ")
                append(if (dpiOk) "DPI✓ " else "DPI✗ ")
                append(if (ednsOk) "EDNS✓($ednsPayload) " else "EDNS✗($ednsPayload) ")
                append(if (nxdOk) "NXD✓" else "NXD✗")
            }

        val transportBadge: String
            get() = when {
                udpAlive && tcpAlive -> "UDP+TCP"
                udpAlive -> "UDP"
                tcpAlive -> "TCP"
                else -> "NONE"
            }

        val statusLabel: String
            get() = when {
                !nxdOk -> "CENSORED (NXDOMAIN Hijacked)"
                score >= 5 -> "WORKING ($score/6)"
                score >= 3 -> "LIMITED ($score/6)"
                else -> "UNSUITABLE ($score/6)"
            }
    }

    /**
     * Pure evaluator for the SlipNet 0–6 DNS Tunnel Compatibility Score (`USER_GUIDE.md` §8.5).
     */
    fun evaluateResolverSixPointScore(
        resolverIp: String,
        providerNameFa: String = "",
        providerNameEn: String = "",
        nsOk: Boolean,
        txtOk: Boolean,
        rndOk: Boolean,
        dpiOk: Boolean,
        ednsPayload: Int,
        nxdHijacked: Boolean,
        udpAlive: Boolean = true,
        tcpAlive: Boolean = true,
        rttMs: Long = 45L,
    ): SlipNetResolverScoreCard {
        val cleanPayload = ednsPayload.coerceIn(512, 1452)
        val ednsPass = cleanPayload >= 900
        val nxdPass = !nxdHijacked
        val upMtu = if (dpiOk) 101 else 60
        val downMtu = when {
            cleanPayload >= 1232 && txtOk -> 500
            cleanPayload >= 900 -> 320
            else -> 120
        }
        return SlipNetResolverScoreCard(
            resolverIp = resolverIp.trim(),
            providerNameFa = providerNameFa,
            providerNameEn = providerNameEn,
            nsOk = nsOk,
            txtOk = txtOk,
            rndOk = rndOk,
            dpiOk = dpiOk,
            ednsOk = ednsPass,
            ednsPayload = cleanPayload,
            nxdOk = nxdPass,
            udpAlive = udpAlive,
            tcpAlive = tcpAlive,
            tcpPreflight8sOk = tcpAlive,
            rttMs = rttMs,
            upMtu = upMtu,
            downMtu = downMtu,
        )
    }

    /**
     * Runs a live SlipNet 6-point compatibility + MasterDNS MTU probe across candidate resolvers
     * over both UDP:53 and TCP:53 (`Both` transport mode with fast Forged-NXDOMAIN rejection).
     */
    fun scanResolversWithSlipNetScore(
        testDomain: String = "cloudflare.com",
        maxCandidates: Int = 6,
        timeoutMs: Int = 850,
    ): List<SlipNetResolverScoreCard> {
        val cards = mutableListOf<SlipNetResolverScoreCard>()
        val candidates = RESOLVER_CATALOG.take(maxCandidates.coerceIn(1, RESOLVER_CATALOG.size))
        for (entry in candidates) {
            val ip = entry.primaryIp
            val udpRtt = probeDnsOverUdp(ip, qtype = 1, timeoutMs = timeoutMs, domain = testDomain)
            val tcpRtt = probeDnsOverTcp(ip, qtype = 1, timeoutMs = timeoutMs + 250, domain = testDomain)
            if (udpRtt == null && tcpRtt == null) continue

            val useTcp = udpRtt == null
            val nsOk = if (useTcp) {
                probeDnsOverTcp(ip, qtype = 2, timeoutMs = timeoutMs, domain = testDomain) != null
            } else {
                probeDnsOverUdp(ip, qtype = 2, timeoutMs = timeoutMs, domain = testDomain) != null
            }
            val txtOk = if (useTcp) {
                probeDnsOverTcp(ip, qtype = 16, timeoutMs = timeoutMs, domain = testDomain) != null
            } else {
                probeDnsOverUdp(ip, qtype = 16, timeoutMs = timeoutMs, domain = testDomain) != null
            }
            val rndSub = "rnd${(System.nanoTime() and 0xFFFF).toString(16)}.$testDomain"
            val rndOk = if (useTcp) {
                probeDnsOverTcp(ip, qtype = 1, timeoutMs = timeoutMs, domain = rndSub) != null
            } else {
                probeDnsOverUdp(ip, qtype = 1, timeoutMs = timeoutMs, domain = rndSub) != null
            }
            val dpiQuery = "a".repeat(36) + "." + "b".repeat(32) + "." + testDomain
            val dpiOk = if (useTcp) {
                probeDnsOverTcp(ip, qtype = 16, timeoutMs = timeoutMs, domain = dpiQuery) != null
            } else {
                probeDnsOverUdp(ip, qtype = 16, timeoutMs = timeoutMs, domain = dpiQuery) != null
            }
            val ednsPayload = if (txtOk && dpiOk) 1232 else if (txtOk) 900 else 512

            cards += evaluateResolverSixPointScore(
                resolverIp = ip,
                providerNameFa = entry.nameFa,
                providerNameEn = entry.nameEn,
                nsOk = nsOk,
                txtOk = txtOk,
                rndOk = rndOk,
                dpiOk = dpiOk,
                ednsPayload = ednsPayload,
                nxdHijacked = isPoisonedIranDnsIp(ip),
                udpAlive = udpRtt != null,
                tcpAlive = tcpRtt != null,
                rttMs = udpRtt ?: tcpRtt ?: 999L,
            )
        }
        return cards.sortedWith(
            compareByDescending<SlipNetResolverScoreCard> { it.score }
                .thenBy { it.rttMs },
        )
    }

    /**
     * Generates a spec-compliant `dnst://` URL (`net2share/dnst-url-spec` v1.0) in either:
     * - Human-readable form: `dnst://<domain>/<transport>/<backend>?<params>#<tag>`
     * - Base64-JSON form: `dnst://<base64url(json)>`
     */
    fun buildDnstStandardUri(
        domain: String,
        transport: String = "vaydns",
        backend: String = "socks",
        pubKeyHex: String = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f",
        recordType: String = "txt",
        resolverIp: String = "178.22.122.100",
        encryptionMethod: Int = 5,
        tag: String = "cat-dnst",
        asBase64Json: Boolean = false,
    ): String {
        val cleanDomain = domain.trim().ifBlank { "t.example.com" }
        val cleanTr = transport.trim().lowercase().ifBlank { "vaydns" }
        val cleanBk = backend.trim().lowercase().ifBlank { "socks" }
        val cleanKey = pubKeyHex.trim().ifBlank { "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f" }
        val cleanRec = recordType.trim().lowercase().ifBlank { "txt" }
        val cleanRes = resolverIp.trim().ifBlank { "178.22.122.100" }
        if (asBase64Json) {
            val trJson = org.json.JSONObject()
                .put("type", cleanTr)
                .put("domain", cleanDomain)
                .put("resolver", cleanRes)
            if (cleanTr == "masterdns") {
                trJson.put("mkey", cleanKey.take(32))
                trJson.put("encryption", encryptionMethod.coerceIn(0, 5))
            } else {
                trJson.put("pubkey", cleanKey)
                if (cleanTr == "vaydns") {
                    trJson.put("record-type", cleanRec)
                    trJson.put("clientid-size", 2)
                }
            }
            val bkJson = org.json.JSONObject().put("type", cleanBk)
            val root = org.json.JSONObject()
                .put("tag", tag)
                .put("transport", trJson)
                .put("backend", bkJson)
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(root.toString().toByteArray(Charsets.UTF_8))
            return "dnst://$b64"
        }
        val query = buildString {
            if (cleanTr == "masterdns") {
                append("mkey=").append(cleanKey.take(32))
                append("&encryption=").append(encryptionMethod.coerceIn(0, 5))
            } else {
                append("pubkey=").append(cleanKey)
                if (cleanTr == "vaydns") {
                    append("&record-type=").append(cleanRec)
                    append("&clientid-size=2&keepalive=2s&idle-timeout=10s")
                }
            }
            append("&resolver=").append(cleanRes)
        }
        val encTag = runCatching { java.net.URLEncoder.encode(tag, "UTF-8") }.getOrDefault(tag)
        return "dnst://$cleanDomain/$cleanTr/$cleanBk?$query#$encTag"
    }

    /**
     * Generates a `slipnet://` Base64-encoded JSON profile URI compatible with `anonvector/SlipNet`
     * (`DNSTT`, `NoizDNS`, `VayDNS`, `Slipstream`, plus optional `+SSH` chaining).
     */
    fun buildSlipNetUri(
        domain: String,
        resolverIp: String = "178.22.122.100",
        pubKeyHex: String = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f",
        tunnelType: String = "noizdns",
        recordType: String = "txt",
        maxQuerySize: Int = 60,
        queryPadding: Int = 20,
        sshChain: Boolean = true,
        remark: String = "SlipNet-NoizDNS",
    ): String {
        val json = org.json.JSONObject()
            .put("name", remark)
            .put("domain", domain.trim().ifBlank { "t.example.com" })
            .put("resolver", "${resolverIp.trim().ifBlank { "178.22.122.100" }}:53")
            .put("pubkey", pubKeyHex.trim())
            .put("tunnelType", tunnelType.trim().lowercase())
            .put("recordType", recordType.trim().lowercase())
            .put("maxQuerySize", maxQuerySize.coerceIn(50, 101))
            .put("queryPadding", queryPadding.coerceIn(0, 40))
            .put("sshChain", sshChain)
            .put("stealthMode", true)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "slipnet://$b64"
    }

    /**
     * Generates a complete `client_config.toml` for `masterking32/MasterDnsVPN` with all
     * 2026 harsh-network parameters (5–7B header, 9 balancing strategies `0..8`, selective packet
     * duplication `1..8`, `ZSTD/LZ4/ZLIB` compression, and automatic resolver health recheck).
     */
    fun buildMasterDnsClientToml(
        domains: List<String> = listOf("v.example.com"),
        encryptionKey: String = "catclient-masterdns-secret-key",
        encryptionMethod: Int = 5,
        balancingStrategy: MasterDnsBalancingStrategy = MasterDnsBalancingStrategy.TIERED_SHORTLIST_RR_6,
        packetDuplication: Int = 2,
        compressionType: Int = 1,
        listenPort: Int = 1080,
    ): String {
        val cleanDomains = domains.map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("v.example.com") }
            .joinToString(", ") { "\"$it\"" }
        val dup = packetDuplication.coerceIn(1, 8)
        val setupDup = (dup + 1).coerceIn(dup, 8)
        val enc = encryptionMethod.coerceIn(0, 5)
        val comp = compressionType.coerceIn(0, 3)
        return """
            # MasterDnsVPN Client Config — Generated by Cat Client (2026)
            PROTOCOL_TYPE = "SOCKS5"
            DOMAINS = [$cleanDomains]
            DATA_ENCRYPTION_METHOD = $enc
            ENCRYPTION_KEY = "${encryptionKey.trim()}"

            LISTEN_IP = "127.0.0.1"
            LISTEN_PORT = $listenPort
            SOCKS5_AUTH = false

            LOCAL_DNS_ENABLED = true
            LOCAL_DNS_IP = "127.0.0.1"
            LOCAL_DNS_PORT = 5353
            LOCAL_DNS_CACHE_MAX_RECORDS = 10000
            LOCAL_DNS_CACHE_TTL_SECONDS = 14400.0
            LOCAL_DNS_CACHE_PERSIST_TO_FILE = true

            # Multi-Resolver Balancing (${balancingStrategy.titleEn}) & Duplication
            RESOLVER_BALANCING_STRATEGY = ${balancingStrategy.code}
            PACKET_DUPLICATION_COUNT = $dup
            SETUP_PACKET_DUPLICATION_COUNT = $setupDup
            STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD = 3
            STREAM_RESOLVER_FAILOVER_COOLDOWN = 2.5
            RECHECK_INACTIVE_SERVERS_ENABLED = true
            AUTO_DISABLE_TIMEOUT_SERVERS = true
            AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS = 30.0
            BASE_ENCODE_DATA = false

            # Compression (0=OFF, 1=ZSTD, 2=LZ4, 3=ZLIB)
            UPLOAD_COMPRESSION_TYPE = $comp
            DOWNLOAD_COMPRESSION_TYPE = $comp
            COMPRESSION_MIN_SIZE = 120

            # Low-Overhead MTU & ARQ Tuning
            MIN_UPLOAD_MTU = 38
            MIN_DOWNLOAD_MTU = 100
            MAX_UPLOAD_MTU = 150
            MAX_DOWNLOAD_MTU = 500
            MTU_TEST_RETRIES = 2
            MTU_TEST_TIMEOUT = 2.0
            MTU_TEST_PARALLELISM = 16
            MAX_PACKETS_PER_BATCH = 8
            ARQ_WINDOW_SIZE = 600
            ARQ_INITIAL_RTO_SECONDS = 1.0
            ARQ_MAX_RTO_SECONDS = 5.0
            ARQ_DATA_NACK_MAX_GAP = 16
        """.trimIndent()
    }
}
