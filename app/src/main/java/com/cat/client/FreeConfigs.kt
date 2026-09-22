package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * FreeConfigs — public, community-maintained config sources.
 *
 * Every source is HTTPS and serves either plain share-links or a base64 blob of
 * share-links. GitHub raw URLs are declared through [githubSource], which queues
 * several mirrors (raw → jsDelivr → ghproxy → gitmirror) because
 * raw.githubusercontent.com is regularly blocked inside Iran; the first mirror
 * that answers wins. The Freevlessnode source is a GitHub Pages site whose files
 * are named by date, so [dailyDatedSite] tries today and the previous few days.
 */
object FreeConfigs {

    /** Protocol prefixes we know how to turn into Route Profiles. */
    private val SHARE_PREFIXES = listOf(
        "vless://", "vmess://", "trojan://", "ss://", "ssr://",
        "hysteria2://", "hy2://", "tuic://", "wireguard://", "wg://", "warp://",
    )

    data class FreeSource(
        val id: String,
        val nameEn: String,
        val nameFa: String,
        /** Candidate URLs, tried in order until one answers. */
        val urls: List<String>,
        /** Cap on links taken from this source (keeps big aggregators manageable). */
        val limit: Int = 250,
    )

    data class FreeEntry(
        val link: String,
        val tag: String,
        val sourceId: String,
        val sourceNameFa: String,
        val protocol: String,
        val host: String,
    )

    data class FetchReport(
        val entries: List<FreeEntry>,
        val sourcesOk: List<String>,
        val sourcesFailed: List<String>,
    )

    /** raw.githubusercontent + three mirrors that usually survive Iranian ISPs. */
    private fun githubSource(
        id: String,
        nameEn: String,
        nameFa: String,
        owner: String,
        repo: String,
        branch: String,
        path: String,
        limit: Int = 250,
    ): FreeSource {
        val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        val dotted = "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
        val proxied = "https://ghproxy.net/$raw"
        val mirrored = "https://raw.gitmirror.com/$owner/$repo/$branch/$path"
        return FreeSource(id, nameEn, nameFa, listOf(raw, dotted, proxied, mirrored), limit)
    }

    /**
     * Freevlessnode (github.io) publishes `uploads/YYYY/MM/{0..4}-YYYYMMDD.txt`
     * every day. We ask for today and the previous [days] days.
     */
    private fun freevlessnodeSource(baseUrl: String, days: Int = 3, filesPerDay: Int = 5): FreeSource {
        val urls = mutableListOf<String>()
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val month = SimpleDateFormat("yyyy/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        repeat(days) { offset ->
            val date = calendar.time
            for (index in 0 until filesPerDay) {
                urls += "${baseUrl.trimEnd('/')}/uploads/${month.format(date)}/$index-${format.format(date)}.txt"
            }
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        return FreeSource(
            id = "freevlessnode",
            nameEn = "FreeVlessNode (daily)",
            nameFa = "FreeVlessNode (روزانه)",
            urls = urls,
            limit = 400,
        )
    }

    /** Sources that were reachable at the time of writing (verified against the GitHub API). */
    fun sources(baseUrl: String = DEFAULT_FREEVLESSNODE_BASE): List<FreeSource> = listOf(
        freevlessnodeSource(baseUrl),
        githubSource(
            "morpheusadam", "Morpheus measured set", "مورفیوس (تست‌شده)",
            "morpheusadam", "v2ray-config", "main", "subs/bundles/mini.txt", limit = 200,
        ),
        githubSource(
            "morpheusadam-iran", "Morpheus Iran bundle", "مورفیوس (ویژهٔ ایران)",
            "morpheusadam", "v2ray-config", "main", "subs/bundles/iran.txt", limit = 300,
        ),
        githubSource(
            "radikal", "0xRadikal VLESS", "رادیکال VLESS",
            "0xRadikal", "Free-v2ray-Configs", "main", "protocols/vless.txt", limit = 220,
        ),
        githubSource(
            "epodonios", "Epodonios collector", "اپودونیوس",
            "Epodonios", "v2ray-configs", "main", "All_Configs_Sub.txt", limit = 220,
        ),
        githubSource(
            "aliilapro", "ALIILAPRO v2rayNG", "علی‌ال‌آپرو",
            "ALIILAPRO", "v2rayNG-Config", "main", "server.txt", limit = 200,
        ),
        githubSource(
            "mahdibland", "MahdiBland Eternity", "مهدی‌بلند",
            "mahdibland", "ShadowsocksAggregator", "master", "Eternity.txt", limit = 150,
        ),
    )

    const val DEFAULT_FREEVLESSNODE_BASE = "https://freevlessnode.github.io"

    /**
     * Fetches every source, de-duplicates by link and returns entries tagged with
     * source and protocol. [onProgress] receives (sourceNameFa, done, total).
     */
    suspend fun fetchAll(
        context: Context,
        baseUrl: String = DEFAULT_FREEVLESSNODE_BASE,
        onProgress: ((String, Int, Int) -> Unit)? = null,
    ): FetchReport = withContext(Dispatchers.IO) {
        val all = linkedMapOf<String, FreeEntry>()
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val list = sources(baseUrl)
        list.forEachIndexed { index, source ->
            onProgress?.invoke(source.nameFa, index, list.size)
            val text = fetchFirstWorking(source.urls)
            if (text == null) {
                failed += source.nameFa
                return@forEachIndexed
            }
            val links = extractLinks(text).take(source.limit)
            if (links.isEmpty()) {
                failed += source.nameFa
                return@forEachIndexed
            }
            ok += "${source.nameFa} (${links.size})"
            links.forEach { link ->
                if (all.containsKey(link)) return@forEach
                all[link] = FreeEntry(
                    link = link,
                    tag = "${hostOf(link)} · ${source.nameFa}",
                    sourceId = source.id,
                    sourceNameFa = source.nameFa,
                    protocol = protocolOf(link),
                    host = hostOf(link),
                )
            }
        }
        onProgress?.invoke("", list.size, list.size)
        FetchReport(all.values.toList(), ok, failed)
    }

    private fun fetchFirstWorking(urls: List<String>): String? {
        for (url in urls) {
            val body = runCatching { fetchUrl(url) }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    /** Plain links, base64 blobs and list files with prefixes are all supported. */
    internal fun extractLinks(text: String): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()
        val direct = linksIn(raw)
        if (direct.isNotEmpty()) return direct
        val decoded = runCatching {
            String(Base64.getDecoder().decode(raw.filter { !it.isWhitespace() }))
        }.getOrNull() ?: runCatching {
            String(Base64.getUrlDecoder().decode(raw.filter { !it.isWhitespace() }))
        }.getOrNull() ?: return emptyList()
        return linksIn(decoded)
    }

    private fun linksIn(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { line -> SHARE_PREFIXES.any { line.startsWith(it, ignoreCase = true) } }
            .filter { it.length in 12..4096 }
            .toList()

    internal fun protocolOf(link: String): String =
        link.substringBefore("://").lowercase(Locale.US)

    /** Best-effort host extraction for the row label (vmess links carry JSON). */
    internal fun hostOf(link: String): String {
        if (link.startsWith("vmess://", ignoreCase = true)) {
            return runCatching {
                val json = String(Base64.getDecoder().decode(link.removePrefix("vmess://").trim()))
                val obj = org.json.JSONObject(json)
                obj.optString("add").ifBlank { "vmess" }
            }.getOrDefault("vmess")
        }
        val afterScheme = link.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfter('@', authority)
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.ifBlank { protocolOf(link) }
    }

    private fun fetchUrl(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 9_000
        conn.readTimeout = 12_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CatClient/1.1 (+android)")
        conn.setRequestProperty("Accept", "text/plain,*/*;q=0.1")
        conn.useCaches = false
        return try {
            if (conn.responseCode !in 200..299) return ""
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }
}
