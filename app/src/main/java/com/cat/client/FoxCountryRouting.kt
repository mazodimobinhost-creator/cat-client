package com.cat.client

import java.io.InputStream
import java.util.Base64
import java.util.Locale

/**
 * beta250: ONE «🦊 Cat Fox VPN» subscription with every country as its own row.
 *
 * All Fox rows point at the same local bridge (127.0.0.1:<port>), so the bridge has to know which country a
 * connection belongs to. Each row therefore carries its country as the SOCKS5 user name (RFC 1929, `fox-DE`,
 * password `cat`); mihomo sends it on every connection and the bridge opens that connection on that country's
 * Mozilla node. A row's ping is then a real request through ITS country — before this every Fox row went out
 * through the one country the bridge was started with, so several Fox rows meant several copies of one exit.
 *
 * Pure JVM (no Android types) so it is unit-tested.
 */
internal object FoxCountryRouting {
    const val USER_PREFIX = "fox-"
    const val PASSWORD = "cat"
    const val SUBSCRIPTION_NAME = "🦊 Cat Fox VPN"

    private val CODE = Regex("[A-Z]{2,3}")

    fun userFor(code: String): String = USER_PREFIX + code.trim().uppercase(Locale.US)

    /** `fox-de` → `DE`; anything else (no user, another app's user) → null = the bridge's default country. */
    fun codeFromUser(user: String?): String? {
        val u = user?.trim().orEmpty()
        if (!u.startsWith(USER_PREFIX, ignoreCase = true)) return null
        return u.substring(USER_PREFIX.length).uppercase(Locale.US).takeIf { CODE.matches(it) }
    }

    /** SOCKS5 greeting methods → which one the bridge answers: user/password (0x02) when offered, else none (0x00). */
    fun chooseSocksMethod(methods: ByteArray): Int =
        if (methods.any { (it.toInt() and 0xFF) == 0x02 }) 0x02 else 0x00

    /**
     * Reads the RFC 1929 sub-negotiation (`01 ulen user plen pass`) and returns the user name, or null on a
     * malformed request (the caller then hangs up).
     */
    fun readUserPass(inp: InputStream): Pair<String, String>? {
        if (inp.read() != 0x01) return null
        val user = readField(inp) ?: return null
        val pass = readField(inp) ?: return null
        return user to pass
    }

    private fun readField(inp: InputStream): String? {
        val len = inp.read()
        if (len < 0) return null
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n <= 0) return null
            off += n
        }
        return String(buf, Charsets.UTF_8)
    }

    /** `Proxy-Authorization: Basic base64(fox-DE:cat)` (the HTTP CONNECT side of the bridge) → `DE`. */
    fun codeFromProxyAuthorization(headerLines: List<String>): String? {
        val value = headerLines.firstOrNull { it.startsWith("proxy-authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return null
        if (!value.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(value.substring(6).trim()), Charsets.UTF_8) }
            .getOrNull() ?: return null
        return codeFromUser(decoded.substringBefore(':'))
    }

    /** The `country=` of a `fenix://` row (its ping and its route), upper-case, or null. */
    fun codeFromFenixUri(uri: String): String? =
        uri.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.startsWith("country=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.uppercase(Locale.US)?.takeIf { CODE.matches(it) }

    /** Row order of the one subscription: Mozilla's own pick, then free countries, then the locked ones. */
    fun <T> ordered(items: List<T>, code: (T) -> String, locked: (T) -> Boolean): List<T> =
        items.distinctBy { code(it).uppercase(Locale.US) }
            .sortedWith(compareBy<T>({ if (code(it).equals("REC", ignoreCase = true)) 0 else 1 }, { if (locked(it)) 1 else 0 }))

    /** Subscription text (a `#` first line, so it is never mistaken for a subscription URL). */
    fun subscriptionText(rows: List<String>): String =
        (listOf("# $SUBSCRIPTION_NAME — every country, one row each") + rows).joinToString("\n")

    /** The row of [code] among [shareLinks] (the country the user picked), else the first row. */
    fun pickRow(shareLinks: List<String>, code: String?): Int {
        val want = code?.trim()?.uppercase(Locale.US).orEmpty()
        val i = shareLinks.indexOfFirst { codeFromFenixUri(it) == want }
        return if (i >= 0) i else 0
    }
}
