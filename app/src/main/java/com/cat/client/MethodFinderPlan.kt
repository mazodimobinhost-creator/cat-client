package com.cat.client

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * beta246 🧭 «Which method works on my internet?» — the pure part (no Android, unit-tested).
 *
 * The owner's request: one place that tries every method the app has on THIS line and shows which ones really
 * work, best first, with a stop button because nobody needs to wait for the whole run. Idea from another client's
 * method scout; this is Cat's own implementation on Cat's own test path:
 *
 *  - every candidate becomes a row of one subscription and goes through the service's existing REAL delay test —
 *    a request through the method itself (Cat core URL test for configs, a full Cat Warp handshake, a full SSTP
 *    login, a real HTTPS request through Cat Fox VPN). A server that only accepts a TCP connection is NOT working;
 *  - candidates are ordered «quick first»: the first candidate of every method, then the second of every method…
 *    so after a few seconds each method already has an answer and the user can stop;
 *  - free WireGuard / Hysteria2 servers come from public lists that are refreshed hourly by their owners; the
 *    lists are parsed and narrowed here (dedupe, no certificate-skipping Hysteria2, real servers before recycled
 *    WARP accounts).
 */
internal enum class FinderFamily(val id: String, val emoji: String, val fa: String, val en: String) {
    CAT_WARP("catwarp", "🐱", "Cat Warp (وارپ کلادفلر)", "Cat Warp (Cloudflare WARP)"),
    FREE_HY2("hy2", "🚀", "سرورهای رایگان Hysteria2", "Free Hysteria2 servers"),
    FREE_WG("wg", "🛡️", "وایرگارد رایگان", "Free WireGuard"),
    MINE("mine", "🔗", "کانفیگ‌های خودم", "My own configs"),
    FOX("fox", "🦊", "Cat Fox VPN", "Cat Fox VPN"),
    SSTP("sstp", "🔐", "SSTP (VPN Gate)", "SSTP (VPN Gate)"),
    ;

    fun title(fa: Boolean): String = "$emoji " + if (fa) this.fa else en

    companion object {
        fun byId(id: String): FinderFamily? = entries.firstOrNull { it.id == id }
        val FREE_SERVERS: Set<FinderFamily> = setOf(FREE_HY2, FREE_WG)
    }
}

/** One thing to test. [link] already carries the finder's own remark (see [MethodFinderPlan.label]). */
internal data class FinderCandidate(val family: FinderFamily, val link: String, val label: String)

/** What one method did in a run. [bestMs] null = nothing of it worked (yet). */
internal data class FinderFamilyResult(
    val family: FinderFamily,
    val total: Int,
    val tested: Int,
    val working: Int,
    val bestMs: Int?,
    /** Index into the candidate list of the fastest working candidate. */
    val bestIndex: Int?,
)

/** A free server parsed from a public list. */
internal data class FreeServer(
    val family: FinderFamily,
    val link: String,
    val host: String,
    val port: Int,
    /** A WireGuard row that is just a Cloudflare WARP account (WARP's public key). */
    val warp: Boolean = false,
    /** A Hysteria2 row that only works with certificate checks off (`insecure=1`, no pin): Cat never does that. */
    val insecureOnly: Boolean = false,
    val obfs: Boolean = false,
    val pinned: Boolean = false,
    val flag: String = "",
) {
    val identity: String get() = family.id + "|" + host.lowercase() + "|" + port
}

internal object MethodFinderPlan {
    const val SUBSCRIPTION_NAME = "🧭 روش‌های آزموده"
    const val FREE_SUBSCRIPTION_NAME = "🆓 سرورهای رایگان WireGuard و Hysteria2"
    const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    const val MAX_HY2 = 16
    const val MAX_WG = 10
    const val MAX_REAL_WG = 6
    const val MAX_MINE = 10
    private const val MAX_PER_HOST = 2

    /** The finder's own remark: «🚀 Hysteria2 🇩🇪 #7». The «#n» is how a test result finds its candidate again. */
    fun label(family: FinderFamily, text: String, index: Int): String {
        val clean = text.replace('#', ' ').replace(Regex("\\s+"), " ").trim().take(48)
        return family.emoji + " " + clean + " #" + (index + 1)
    }

    /** The candidate index in a profile tag made by [label] (the converter may append « 2» to a duplicate name). */
    fun indexOf(tag: String): Int? =
        Regex("#(\\d{1,3})(?!\\d)").findAll(tag).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

    /** «Quick first»: the 1st candidate of every method, then the 2nd of every method, … (method order kept). */
    fun interleave(candidates: List<FinderCandidate>): List<FinderCandidate> {
        val byFamily = FinderFamily.entries.map { f -> candidates.filter { it.family == f } }.filter { it.isNotEmpty() }
        val out = ArrayList<FinderCandidate>(candidates.size)
        var round = 0
        while (out.size < candidates.size) {
            byFamily.forEach { list -> list.getOrNull(round)?.let(out::add) }
            round++
        }
        return out
    }

    /**
     * Relabels every candidate with its final position (call after [interleave]). Links whose name cannot be set are
     * dropped FIRST, so «#n» is always the candidate's position in the returned list.
     */
    fun numbered(candidates: List<FinderCandidate>): List<FinderCandidate> =
        candidates.filter { ShareLinkRemark.with(it.link, "x") != null }.mapIndexed { i, c ->
            c.copy(link = ShareLinkRemark.with(c.link, label(c.family, c.label, i))!!)
        }

    /**
     * [delays] = candidate index → measured ms for the ones that WORKED; [tested] = indexes that finished (worked or
     * failed). Methods with candidates come back in method order.
     */
    fun summarize(candidates: List<FinderCandidate>, tested: Set<Int>, delays: Map<Int, Int>): List<FinderFamilyResult> =
        FinderFamily.entries.mapNotNull { f ->
            val idx = candidates.indices.filter { candidates[it].family == f }
            if (idx.isEmpty()) return@mapNotNull null
            val ok = idx.filter { it in delays }
            val best = ok.minByOrNull { delays.getValue(it) }
            FinderFamilyResult(f, idx.size, idx.count { it in tested || it in delays }, ok.size, best?.let(delays::get), best)
        }

    /** Working methods first (fastest first), then untested, then those where nothing worked. */
    fun ranked(results: List<FinderFamilyResult>): List<FinderFamilyResult> = results.sortedWith(
        compareBy<FinderFamilyResult> { if (it.working > 0) 0 else if (it.tested < it.total) 1 else 2 }
            .thenBy { it.bestMs ?: Int.MAX_VALUE },
    )

    // ---- free servers from public lists ----

    fun parseFreeServers(text: String): List<FreeServer> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length < 4096 }
        .mapNotNull(::parseFreeServer)
        .toList()

    fun parseFreeServer(line: String): FreeServer? {
        val scheme = line.substringBefore("://", "").lowercase()
        val family = when (scheme) {
            "wireguard", "wg" -> FinderFamily.FREE_WG
            "hysteria2", "hy2" -> FinderFamily.FREE_HY2
            else -> return null
        }
        val rest = line.substringAfter("://")
        val authority = rest.substringBefore('#').substringBefore('?').substringBefore('/').substringAfterLast('@')
        val (host, port) = hostPort(authority) ?: return null
        val query = queryOf(rest.substringBefore('#').substringAfter('?', ""))
        val fragment = decode(rest.substringAfter('#', ""))
        return when (family) {
            FinderFamily.FREE_WG -> {
                val key = query["publickey"] ?: query["public-key"] ?: query["peer"] ?: ""
                FreeServer(family, line, host, port, warp = key == WARP_PUBLIC_KEY, flag = flagOf(fragment))
            }
            else -> {
                val insecure = query["insecure"]?.lowercase() in setOf("1", "true") || query["allowinsecure"]?.lowercase() in setOf("1", "true")
                val pinned = !query["pinsha256"].isNullOrBlank()
                FreeServer(
                    family, line, host, port,
                    insecureOnly = insecure && !pinned,
                    obfs = !query["obfs"].isNullOrBlank(),
                    pinned = pinned,
                    flag = flagOf(fragment),
                )
            }
        }
    }

    /**
     * Narrows the public lists to what is worth a real test: one row per server, at most two per host, no Hysteria2
     * that needs certificate checks off (it would fail Cat's verified TLS anyway), domain/pinned/obfuscated
     * Hysteria2 first, real WireGuard servers before recycled WARP accounts, WARP rows on different ports.
     */
    fun pickFreeServers(all: List<FreeServer>, maxHy2: Int = MAX_HY2, maxWg: Int = MAX_WG): List<FreeServer> {
        val unique = all.distinctBy { it.identity }
        fun capPerHost(list: List<FreeServer>): List<FreeServer> {
            val seen = HashMap<String, Int>()
            return list.filter { s -> (seen.merge(s.host.lowercase(), 1, Int::plus) ?: 1) <= MAX_PER_HOST }
        }
        val hy2 = capPerHost(
            unique.filter { it.family == FinderFamily.FREE_HY2 && !it.insecureOnly }
                .sortedWith(compareBy<FreeServer>({ !(it.pinned || isDomain(it.host)) }, { !it.obfs })),
        ).take(maxHy2)
        val wgAll = unique.filter { it.family == FinderFamily.FREE_WG }
        val real = capPerHost(wgAll.filter { !it.warp }).take(minOf(MAX_REAL_WG, maxWg))
        val warp = wgAll.filter { it.warp }.distinctBy { it.port }.take((maxWg - real.size).coerceAtLeast(0))
        return hy2 + real + warp
    }

    fun freeLabel(s: FreeServer): String = when (s.family) {
        FinderFamily.FREE_WG -> (if (s.warp) "WARP" else "WireGuard") + (if (s.flag.isNotEmpty()) " " + s.flag else "")
        else -> "Hysteria2" + (if (s.flag.isNotEmpty()) " " + s.flag else "") + (if (s.obfs) " obfs" else "")
    }

    // ---- per-network memory of the last run ----

    /** `family|total|tested|working|bestMs` lines; bestMs -1 = none. */
    fun encode(results: List<FinderFamilyResult>): String = results.joinToString("\n") {
        listOf(it.family.id, it.total, it.tested, it.working, it.bestMs ?: -1).joinToString("|")
    }

    fun decode(raw: String?): List<FinderFamilyResult> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 5) return@mapNotNull null
        val f = FinderFamily.byId(p[0]) ?: return@mapNotNull null
        val n = p.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
        if (n[0] < 0 || n[1] !in 0..n[0] || n[2] !in 0..n[1]) return@mapNotNull null
        FinderFamilyResult(f, n[0], n[1], n[2], n[3].takeIf { it > 0 }, null)
    }.toList()

    // ---- small helpers ----

    private fun hostPort(authority: String): Pair<String, Int>? {
        val a = authority.trim()
        val (host, portText) = if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0) return null
            a.substring(1, end) to a.substring(end + 1).removePrefix(":")
        } else {
            val c = a.lastIndexOf(':')
            if (c <= 0) return null
            a.substring(0, c) to a.substring(c + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank()) return null
        return host to port
    }

    private fun queryOf(q: String): Map<String, String> = q.split('&').mapNotNull { kv ->
        val k = kv.substringBefore('=').trim().lowercase()
        if (k.isEmpty()) null else k to decode(kv.substringAfter('=', ""))
    }.toMap()

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun isDomain(host: String): Boolean = host.any { it.isLetter() } && ':' !in host

    /** The first flag emoji (two regional-indicator letters) in a remark, or "". */
    fun flagOf(text: String): String {
        val cps = text.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) return String(cps, i, 2)
        }
        return ""
    }

    /** Core start errors like «proxy 37: decode peer public key» → one plain line. */
    fun plainCoreError(raw: String, fa: Boolean): String {
        val s = raw.lowercase()
        return when {
            "decode peer public key" in s || "illegal base64" in s ->
                if (fa) "یک کانفیگ وایرگارد کلید خراب دارد و آزمون را خواباند. آن ردیف را حذف کنید و دوباره «بررسی» را بزنید."
                else "A WireGuard config has a broken key and stopped the test. Remove that row and tap Check again."
            else -> raw.take(160)
        }
    }
}

/** Sets the remark (display name) of a share link without touching anything else in it. */
internal object ShareLinkRemark {
    private val FRAGMENT_SCHEMES = setOf(
        "vless", "trojan", "ss", "ssr", "hysteria2", "hy2", "hysteria", "tuic", "wireguard", "wg", "awg", "amneziawg",
        "socks", "socks5", "http", "https", "anytls", "sstp", "aether", "masque", "fenix", "firefoxvpn", "mieru",
    )

    /** null = a link whose name cannot be set safely (it is then left out rather than renamed wrongly). */
    fun with(link: String, remark: String): String? {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        if (scheme == "vmess") return vmess(trimmed, remark)
        if (scheme !in FRAGMENT_SCHEMES) return null
        return trimmed.substringBefore('#') + "#" + URLEncoder.encode(remark, "UTF-8").replace("+", "%20")
    }

    /** `vmess://` + base64(JSON) keeps its name in `"ps"`. */
    private fun vmess(link: String, remark: String): String? {
        val body = link.substringAfter("://").substringBefore('#').trim()
        val json = runCatching {
            String(Base64.getDecoder().decode(body.padEnd((body.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
        }.getOrNull()?.trim() ?: return null
        if (!json.startsWith("{") || !json.endsWith("}")) return null
        val esc = buildString {
            remark.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(ch)
                }
            }
        }
        val ps = Regex("\"ps\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")
        val out = if (ps.containsMatchIn(json)) {
            ps.replaceFirst(json, Regex.escapeReplacement("\"ps\":\"$esc\""))
        } else {
            "{\"ps\":\"$esc\"," + json.removePrefix("{")
        }
        return "vmess://" + Base64.getEncoder().encodeToString(out.toByteArray(Charsets.UTF_8))
    }
}
