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
)

/**
 * Conceptual smart scanner combining:
 * 1) 6-port Cloudflare TLS + SNI Pool scanner (inspired by WhiteDNS-cleanip-finder)
 * 2) Iranian UDP/TCP DNS resolver scanner & dnstt bridge builder (inspired by IP-Range-Scout-Android)
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
        "178.22.122.100",
        "185.51.200.2",
        "217.218.155.155",
    )

    val IRAN_TCP_DNS_TARGETS: List<String> = listOf(
        "85.185.105.104",
        "81.12.72.108",
        "85.15.3.5",
        "84.241.29.70",
        "5.202.174.183",
        "130.185.72.239",
        "185.224.179.176",
        "217.11.18.51",
    )

    fun buildDnsQueryPacket(txId: Int = 0x4341): ByteArray {
        val hi = ((txId ushr 8) and 0xff).toByte()
        val lo = (txId and 0xff).toByte()
        // Standard recursive DNS query for "cloudflare.com" type A (1) class IN (1)
        return byteArrayOf(
            hi, lo, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0a, 'c'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(),
            'd'.code.toByte(), 'f'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(),
            'r'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01,
        )
    }

    fun isValidDnsResponse(buf: ByteArray, length: Int, expectedTxId: Int = 0x4341): Boolean {
        if (length < 12) return false
        val rxId = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
        if (rxId != (expectedTxId and 0xffff)) return false
        val flagsHi = buf[2].toInt() and 0xff
        // QR bit (0x80) must be 1 (response)
        return (flagsHi and 0x80) != 0
    }

    fun buildDnsttBridgesForResolver(dnsIp: String): List<String> {
        val cleanIp = dnsIp.trim()
        require(cleanIp.isNotEmpty()) { "DNS resolver IP is empty" }
        return listOf(
            "dnstt 192.0.2.4:1 A998F319ADB60EE344540EC4B21524CC484F96BE udp=$cleanIp:53 pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
            "dnstt 192.0.2.4:2 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 udp=$cleanIp:53 pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
            "dnstt 192.0.2.4:3 74D409BED3E2F881F365543A72C8F079CB84FFEB udp=$cleanIp:53 pubkey=c596c458fc3453dc40903ab235f5854a2609831075640c4c5584f76de05b8271 domain=t.arkadag.org",
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

    fun scanIranDnsResolvers(timeoutMs: Int = 900, maxResults: Int = 8): List<IranDnsHit> {
        val results = mutableListOf<IranDnsHit>()
        val query = buildDnsQueryPacket(0x4341)
        for (ip in IRAN_UDP_DNS_TARGETS) {
            val rtt = runCatching {
                val t0 = System.nanoTime()
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    val addr = InetAddress.getByName(ip)
                    sock.send(DatagramPacket(query, query.size, addr, 53))
                    val buf = ByteArray(512)
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    if (!isValidDnsResponse(buf, resp.length, 0x4341)) return@runCatching null
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            if (rtt != null) {
                results += IranDnsHit(ip = ip, transport = "UDP", rttMs = rtt)
                if (results.size >= maxResults) break
            }
        }
        if (results.size < 3) {
            for (ip in IRAN_TCP_DNS_TARGETS.take(4)) {
                val rtt = runCatching {
                    val t0 = System.nanoTime()
                    Socket().use { sock ->
                        sock.soTimeout = timeoutMs
                        sock.connect(InetSocketAddress(InetAddress.getByName(ip), 53), timeoutMs)
                    }
                    ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
                if (rtt != null) {
                    results += IranDnsHit(ip = ip, transport = "TCP", rttMs = rtt)
                }
            }
        }
        return results.sortedBy { it.rttMs }
    }
}
