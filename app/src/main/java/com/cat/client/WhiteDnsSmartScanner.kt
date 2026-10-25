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
        )
    }

    fun probeTlsPortWithSni(host: String, port: Int, sni: String, timeoutMs: Int = 1300): Long? {
        if (port !in CLOUDFLARE_TLS_PORTS) return null
        val bareHost = host.trim().removeSurrounding("[", "]")
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
                    ssl.startHandshake()
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
}
