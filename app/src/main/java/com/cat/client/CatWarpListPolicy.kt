package com.cat.client

import java.net.InetSocketAddress
import java.net.Socket

/**
 * beta214: small pure helpers behind the Cat Warp list fixes.
 *
 * 1. Building a Cat Warp config for one country must leave ONE config in «⚡ Cat Warp Configs» —
 *    earlier every build / country chip was merged in front of the old lines (up to 24), so the list
 *    filled with «United States · Warp», «Germany · Warp», … and Automatic picked any of them.
 * 2. Rows that are a plain SOCKS/HTTP proxy on 127.0.0.1 (an inbound of another app, copied out of a
 *    JSON config) only work while something listens on that port on this phone. When nothing does,
 *    the Cat core failed with the cryptic «health check at 127.0.0.1:2080»; now the user is told why.
 */
internal object CatWarpListPolicy {
    const val SUBSCRIPTION_NAME = "⚡ Cat Warp Configs"
    const val MAX_MERGED_LINES = 24

    fun isCatWarpLink(link: String?): Boolean {
        val l = link?.trim().orEmpty()
        return l.startsWith("aether://", ignoreCase = true) || l.startsWith("masque://", ignoreCase = true)
    }

    /**
     * New text of the Cat Warp subscription. [replaceAll] = true (every builder, wizard and country pick)
     * keeps only [newUri]; false (a link pasted from somewhere) puts it first and keeps the others.
     */
    fun mergedInput(existingInput: String?, newUri: String, replaceAll: Boolean): String {
        val uri = newUri.trim()
        if (replaceAll || existingInput.isNullOrBlank()) return uri
        val newName = uri.substringAfter('#', "")
        val old = existingInput.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { newName.isNotEmpty() && it.substringAfter('#', "") == newName }
        return (listOf(uri) + old).distinct().take(MAX_MERGED_LINES).joinToString("\n")
    }

    /** Index of the subscription line that produced a Cat Warp row (exact link first, then the same #name). */
    fun lineIndexFor(input: String, shareLink: String?): Int {
        val link = shareLink?.trim().orEmpty()
        if (!isCatWarpLink(link)) return -1
        val lines = input.lines()
        val exact = lines.indexOfFirst { it.trim() == link }
        if (exact >= 0) return exact
        val name = link.substringAfter('#', "")
        if (name.isEmpty()) return -1
        return lines.indexOfFirst { isCatWarpLink(it) && it.trim().substringAfter('#', "") == name }
    }

    /** Puts line [index] first (so Automatic, which takes the first Cat Warp row, uses exactly that config). */
    fun moveLineFirst(input: String, index: Int): String {
        val lines = input.lines().toMutableList()
        if (index !in lines.indices || index == 0) return input
        val line = lines.removeAt(index)
        lines.add(0, line)
        return lines.joinToString("\n")
    }
}

internal object LoopbackProfileGuard {
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "0.0.0.0")
    private val PLAIN_TYPES = setOf("socks5", "socks", "socks4", "http")

    fun isLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase().orEmpty()
        return h in LOOPBACK_HOSTS || h.startsWith("127.")
    }

    /**
     * True for a row that is nothing but «connect to a proxy on this phone»: a socks/http entry on a loopback
     * address whose share link is the plain socks/http form. Cat Warp, Cat SSTP, Cat Fox VPN, Cat DNS and paqet
     * rows also sit on 127.0.0.1, but carry their own link (aether://, sstp://, …) and are started by the app.
     */
    fun isPlainLoopback(type: String?, server: String?, shareLink: String?): Boolean {
        if (!isLoopbackHost(server)) return false
        if (type?.trim()?.lowercase() !in PLAIN_TYPES) return false
        val link = shareLink?.trim()?.lowercase().orEmpty()
        return link.isEmpty() || link.startsWith("socks") || link.startsWith("http://") || link.startsWith("https://")
    }

    fun isListening(port: Int, timeoutMs: Int = 400): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    fun deadLocalMessage(port: Int, name: String): String =
        "«$name» فقط یک پروکسی محلی روی 127.0.0.1:$port است و الان هیچ برنامه‌ای روی این گوشی روی این پورت گوش نمی‌دهد " +
            "(این ردیف از کانفیگ یک برنامهٔ دیگر کپی شده و خودش سرور ندارد). برای Cat Warp از «⚡ Cat Warp» → " +
            "«🤖 اسکن و ساخت خودکار» یا «🚀 اتصال مستقیم Cat Warp» استفاده کنید، یا این سابسکریپشن را حذف کنید. " +
            "— «$name» is only a local proxy at 127.0.0.1:$port and nothing on this phone is listening there " +
            "(it was copied from another app's config and has no server of its own)."
}
