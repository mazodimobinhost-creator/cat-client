package com.cat.client

import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * beta239: Mozilla's sign-in (accounts.firefox.com) and VPN (vpn.mozilla.org) servers sit behind Fastly's bot check.
 * A request it does not like gets HTTP 406 and a «Client Challenge» page; the client has to answer it (proof of work
 * / PAT / client metrics) and send the cookie it earns with the next requests.
 *
 * What Cat Fox VPN got wrong (compared with FoxyVPN 2.0.1, MIT, github.com/Vauth/FoxyVPN — ideas re-implemented):
 *  - it answered the challenge with a Chrome identity and then retried with the Firefox one — Fastly ties the cookie
 *    to the client identity, so the retry was challenged again, every time ⇒ «Fox does not work at all»;
 *  - it never answered the page that actually challenged it: a 406 from vpn.mozilla.org was «solved» on
 *    accounts.firefox.com;
 *  - all cookies lived in one map: same-named challenge cookies of two hosts overwrote each other.
 * These helpers are pure (no Android, no org.json) so the JVM tests can check them.
 */
internal object FoxFastlyChallenge {
    /** Only these hosts may hand us a challenge (and receive the cookies it earns). */
    val TRUSTED_HOSTS: Set<String> = linkedSetOf(
        "api.accounts.firefox.com",
        "accounts.firefox.com",
        "oauth.accounts.firefox.com",
        "vpn.mozilla.org",
    )

    /** Fastly sends follow-up challenges after a post-back; give up after this many rounds. */
    const val MAX_ROUNDS = 8

    private val PREFIX = Regex("/_fs-ch-[A-Za-z0-9]+")
    private val INIT_CALL = Regex("""\binit\s*\(\s*\[""")

    fun isChallengePage(text: String?): Boolean =
        text != null && text.length in 1..2_000_000 && PREFIX.containsMatchIn(text) &&
            (text.contains("Client Challenge", ignoreCase = true) || text.contains("script.js") || text.contains("fst-post-back"))

    /** `/_fs-ch-XXXX` — the folder of the challenge script on the challenged host. */
    fun prefix(page: String): String? = PREFIX.find(page)?.value

    /** `https://<host>` when [url] is an https URL on one of [TRUSTED_HOSTS] (port 443), else null. */
    fun trustedOrigin(url: String): String? = runCatching {
        val u = URI(url.trim())
        val host = u.host?.lowercase(Locale.US) ?: return null
        if (!u.scheme.equals("https", ignoreCase = true) || (u.port != -1 && u.port != 443) || u.rawUserInfo != null) return null
        if (host !in TRUSTED_HOSTS) return null
        "https://$host"
    }.getOrNull()

    /**
     * The last `init([...], "token", …)` call of the challenge script → (challenges as JSON array text, token).
     * Brackets inside strings are skipped, so a nested array in a challenge's data does not cut it short.
     */
    fun parseInit(script: String): Pair<String, String>? {
        for (m in INIT_CALL.findAll(script).toList().asReversed()) {
            val open = m.range.last
            val close = matchingBracket(script, open) ?: continue
            var i = skipSpaces(script, close + 1)
            if (i >= script.length || script[i] != ',') continue
            i = skipSpaces(script, i + 1)
            if (i >= script.length || script[i] != '"') continue
            val (token, after) = readString(script, i) ?: continue
            val j = skipSpaces(script, after)
            if (j >= script.length || (script[j] != ',' && script[j] != ')')) continue
            val array = script.substring(open, close + 1)
            if (token.isBlank() || !array.contains("\"ty\"")) continue
            return array to token
        }
        return null
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun matchingBracket(s: String, open: Int): Int? {
        var depth = 0
        var quote = 0.toChar()
        var escaped = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = 0.toChar()
                }
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return if (c == ']') i else null
                        if (depth < 0) return null
                    }
                }
            }
            i++
        }
        return null
    }

    /** A JavaScript double-quoted string starting at [start] → (value, index after the closing quote). */
    private fun readString(s: String, start: Int): Pair<String, Int>? {
        val out = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return out.toString() to (i + 1)
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> {
                            val hex = s.substring(i + 2, minOf(i + 6, s.length))
                            val code = hex.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(n)
                    }
                    i += 2
                    continue
                }
                c == '\n' -> return null
                else -> out.append(c)
            }
            i++
        }
        return null
    }
}

/**
 * beta239: cookies of Mozilla's control plane, kept per host (a `Domain=` cookie under that domain) — the old single
 * map sent every cookie to every host and let two hosts' same-named challenge cookies overwrite each other.
 */
internal class FoxCookieJar {
    private val byDomain = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private fun clean(host: String) = host.trim().trimStart('.').lowercase(Locale.US)

    /** Applies the `Set-Cookie` header values a response from [host] carried. */
    fun store(host: String, setCookieValues: List<String>) {
        val h = clean(host)
        if (h.isEmpty()) return
        for (raw in setCookieValues) {
            val parts = raw.split(';')
            val pair = parts.first().trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            val attrs = parts.drop(1).map { it.trim() }
            val domain = attrs.firstOrNull { it.startsWith("domain=", ignoreCase = true) }
                ?.substringAfter('=')?.let { clean(it) }
            // A Domain attribute counts only for the host itself or a parent of it (a browser ignores the rest).
            val target = if (domain != null && domain.contains('.') && (h == domain || h.endsWith(".$domain"))) domain else h
            val maxAge = attrs.firstOrNull { it.startsWith("max-age=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.toLongOrNull()
            val gone = value.isEmpty() || (maxAge != null && maxAge <= 0L)
            if (gone) byDomain[target]?.remove(name)
            else byDomain.getOrPut(target) { ConcurrentHashMap() }[name] = value
        }
    }

    fun put(host: String, name: String, value: String) {
        byDomain.getOrPut(clean(host)) { ConcurrentHashMap() }[name] = value
    }

    /** The `Cookie` header for a request to [host] ("" when none); the most specific domain wins a name clash. */
    fun header(host: String): String {
        val h = clean(host)
        val merged = LinkedHashMap<String, String>()
        byDomain.keys.filter { h == it || h.endsWith(".$it") }.sortedBy { it.length }.forEach { d ->
            byDomain[d]?.forEach { (k, v) -> merged[k] = v }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Every cookie in this jar (most specific domain wins), e.g. the ones a challenge just earned. */
    fun all(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        byDomain.keys.sortedBy { it.length }.forEach { d -> byDomain[d]?.forEach { (k, v) -> merged[k] = v } }
        return merged
    }

    /** Puts every cookie of this jar into [target] for each of [hosts] (as FoxyVPN does with challenge cookies). */
    fun copyInto(target: FoxCookieJar, hosts: Collection<String>) {
        val cookies = all()
        for (host in hosts) for ((k, v) in cookies) target.put(host, k, v)
    }

    fun isEmpty(): Boolean = byDomain.values.all { it.isEmpty() }

    fun clear() = byDomain.clear()
}
