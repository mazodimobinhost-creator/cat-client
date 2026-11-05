package com.cat.client

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * beta246 🧭: collects what the method finder tests. Network work only (runs on IO); every choice that can be tested
 * without Android lives in [MethodFinderPlan].
 */
internal object MethodFinderSources {
    /** A public list refreshed by its owner. Pre-tested lists first: their rows win the per-host/ dedupe ties. */
    data class Feed(val repo: String, val path: String, val family: FinderFamily)

    val FEEDS = listOf(
        Feed("10ium/VpnClashFaCollector", "sub/all/hysteria2.txt", FinderFamily.FREE_HY2),
        Feed("10ium/VpnClashFaCollector", "sub/all/wireguard.txt", FinderFamily.FREE_WG),
        Feed("morpheusadam/v2ray-config", "subs/bundles/hysteria2.txt", FinderFamily.FREE_HY2),
        Feed("morpheusadam/v2ray-config", "subs/bundles/wireguard.txt", FinderFamily.FREE_WG),
        Feed("rtwo2/FastNodes", "sub/protocols/hysteria2.txt", FinderFamily.FREE_HY2),
        Feed("Argh94/Proxy-List", "WireGuard.txt", FinderFamily.FREE_WG),
    )

    private const val MAX_BYTES = 700 * 1024
    private const val TIMEOUT_MS = 9_000

    /** Mirrors in order: jsDelivr (usually reachable in Iran), GitHub raw, jsDelivr's Fastly edge. */
    fun urls(feed: Feed): List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/${feed.repo}@main/${feed.path}",
        "https://raw.githubusercontent.com/${feed.repo}/main/${feed.path}",
        "https://fastly.jsdelivr.net/gh/${feed.repo}@main/${feed.path}",
    )

    data class Gathered(
        val candidates: List<FinderCandidate>,
        /** A method that could not be offered this time and the honest reason (shown greyed out). */
        val skipped: Map<FinderFamily, String>,
    )

    suspend fun gather(activity: MainActivity, families: Set<FinderFamily>, fa: Boolean): Gathered = coroutineScope {
        fun t(f: String, e: String) = if (fa) f else e
        val skipped = LinkedHashMap<FinderFamily, String>()
        val out = ArrayList<FinderCandidate>()
        val freeJob = if (families.any { it in FinderFamily.FREE_SERVERS }) async(Dispatchers.IO) { freeServers(families) } else null
        val sstpJob = if (FinderFamily.SSTP in families) {
            async(Dispatchers.IO) { runCatching { SstpServerList.fetch("speed", 6) }.getOrNull().orEmpty() }
        } else {
            null
        }
        if (FinderFamily.CAT_WARP in families) out += catWarp(activity, fa)
        if (FinderFamily.FOX in families) {
            val fox = fox(activity)
            if (fox.isEmpty()) {
                skipped[FinderFamily.FOX] = t(
                    "وارد حساب Cat Fox VPN نشده‌اید؛ از کارت «Cat Fox VPN» همین صفحه وارد شوید تا این هم امتحان شود.",
                    "You are not signed in to Cat Fox VPN; sign in on the Cat Fox VPN card on this page to include it.",
                )
            }
            out += fox
        }
        if (FinderFamily.MINE in families) {
            val mine = mine(activity)
            if (mine.isEmpty()) skipped[FinderFamily.MINE] = t("هنوز کانفیگ یا سابسکریپشنی اضافه نکرده‌اید.", "You have not added any configs or subscriptions yet.")
            out += mine
        }
        freeJob?.await()?.let { (servers, reached) ->
            FinderFamily.FREE_SERVERS.filter { it in families }.forEach { f ->
                val list = servers.filter { it.family == f }
                if (list.isEmpty()) {
                    skipped[f] = if (reached == 0) {
                        t(
                            "فهرست‌های عمومی سرور رایگان (jsDelivr و GitHub) از این اینترنت باز نشدند. یک بار با هر روشی که کار کرد وصل شوید و دوباره «بررسی» را بزنید؛ فهرست از داخل اتصال گرفته می‌شود.",
                            "The public free-server lists (jsDelivr and GitHub) did not open on this internet. Connect once with any method that works and tap Check again; the list is then fetched through that connection.",
                        )
                    } else {
                        t("در فهرست‌ها سرور قابل‌اعتمادی از این نوع نبود.", "The lists had no trustworthy server of this kind right now.")
                    }
                }
                out += list.map { FinderCandidate(f, it.link, MethodFinderPlan.freeLabel(it)) }
            }
        }
        sstpJob?.await()?.let { servers ->
            if (servers.isEmpty()) {
                skipped[FinderFamily.SSTP] = t("فهرست سرورهای VPN Gate باز نشد.", "The VPN Gate server list did not open.")
            }
            servers.forEach { s ->
                val link = s.toLink().toUri()
                out += FinderCandidate(FinderFamily.SSTP, link, "SSTP " + listOf(s.flag, s.countryCode).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        Gathered(out, skipped)
    }

    /** The four Cat Warp ways on the user's own Cat Warp settings (scanned endpoint kept), never chained. */
    private fun catWarp(activity: MainActivity, fa: Boolean): List<FinderCandidate> {
        val base = activity.aetherPreferenceStore.read().copy(chainTarget = "none", psiphonRegion = "")
        val modes = listOf(
            AetherMode.MasqueHttp2 to "Cat Warp · MASQUE",
            AetherMode.WireGuard to "Cat Warp · WireGuard",
            AetherMode.WarpInWarp to (if (fa) "Cat Warp · وارپ در وارپ" else "Cat Warp · WARP in WARP"),
            AetherMode.MasqueInMasque to (if (fa) "Cat Warp · MASQUE دوبل" else "Cat Warp · MASQUE in MASQUE"),
        )
        return modes.mapNotNull { (mode, label) ->
            runCatching { base.copy(mode = mode, profileName = label).toAetherUri() }.getOrNull()
                ?.let { FinderCandidate(FinderFamily.CAT_WARP, it, label) }
        }
    }

    /** Cat Fox VPN only when an account is signed in (Mozilla's own suggestion + one free country). */
    private fun fox(ctx: Context): List<FinderCandidate> {
        if (FirefoxVpnEngine.selectBestActiveAccount(FirefoxVpnEngine.loadAccounts(ctx)) == null) return emptyList()
        return FirefoxVpnEngine.BUILTIN_COUNTRIES.filter { it.code == "REC" || it.code == "DE" }.map { c ->
            FinderCandidate(FinderFamily.FOX, FirefoxVpnEngine.buildFenixTunnelUri(c), "Cat Fox VPN " + c.flag + " " + c.code)
        }
    }

    /** The user's own configs: last ones that worked first, at most 4 per subscription, 10 in total. */
    private fun mine(activity: MainActivity): List<FinderCandidate> {
        val store = SubscriptionStore(activity)
        val engineSchemes = listOf("aether://", "masque://", "sstp://", "fenix://", "firefoxvpn://")
        val picked = ArrayList<FinderCandidate>()
        val subs = runCatching { activity.userSubscriptionManager.list() }.getOrDefault(emptyList())
            .filterNot { it.name == MethodFinderPlan.SUBSCRIPTION_NAME || it.name.startsWith("🆓") }
        for (sub in subs) {
            val profiles = activity.userSubscriptionManager.cachedSnapshot(sub.id)?.catalog?.profiles.orEmpty()
                .filter { p -> val l = p.shareLink.orEmpty(); l.contains("://") && engineSchemes.none { l.startsWith(it, ignoreCase = true) } }
            if (profiles.isEmpty()) continue
            val records = runCatching { store.readConnectionDelayRecords(sub.id, profiles) }.getOrDefault(emptyList())
                .associateBy { it.fingerprint }
            val ordered = profiles.sortedWith(
                compareBy<ConnectionProfile>(
                    { when (records[it.fingerprint]?.status) { ConnectionDelayStatus.Success -> 0; null -> 1; else -> 2 } },
                    { records[it.fingerprint]?.delayMs ?: Int.MAX_VALUE },
                ),
            )
            ordered.take(4).forEach { p ->
                picked += FinderCandidate(FinderFamily.MINE, p.shareLink.orEmpty(), p.tag.take(32))
            }
            if (picked.size >= MethodFinderPlan.MAX_MINE) break
        }
        return picked.take(MethodFinderPlan.MAX_MINE)
    }

    /** Downloads the public lists in parallel and narrows them. Returns the picks and how many lists opened. */
    private suspend fun freeServers(families: Set<FinderFamily>): Pair<List<FreeServer>, Int> = coroutineScope {
        val feeds = FEEDS.filter { it.family in families }
        val texts = feeds.map { feed -> async(Dispatchers.IO) { fetchFirst(urls(feed)) } }.awaitAll()
        val reached = texts.count { it != null }
        val all = texts.filterNotNull().flatMap { MethodFinderPlan.parseFreeServers(maybeBase64(it)) }
        MethodFinderPlan.pickFreeServers(all.filter { it.family in families }) to reached
    }

    private fun fetchFirst(urls: List<String>): String? {
        for (u in urls) {
            val text = runCatching { httpsGet(u) }.getOrNull()
            if (!text.isNullOrBlank() && text.contains("://")) return text
            if (!text.isNullOrBlank() && maybeBase64(text).contains("://")) return text
        }
        return null
    }

    private fun httpsGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CatClient")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                val text = out.toString("UTF-8")
                // A list cut at the size cap ends mid-row: drop that half row so no broken server is tested.
                if (out.size() >= MAX_BYTES) text.substringBeforeLast('\n') else text
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Some lists are served as one base64 blob. */
    private fun maybeBase64(text: String): String {
        val t = text.trim()
        if (t.contains("://") || t.length < 16 || t.any { it.isWhitespace() && it != '\n' && it != '\r' }) return text
        return runCatching { String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            .getOrDefault(text)
    }
}
