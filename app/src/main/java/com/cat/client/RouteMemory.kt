package com.cat.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * beta220 «route memory per network»: which route (connection fingerprint) really carried internet — by the
 * [RealInternetCheck], not by a ping — on THIS kind of network. The key is privacy-safe (transport + the operator's
 * public MCC/MNC code for mobile data; never an SSID, phone number or subscriber id), e.g. `cellular:43211` for
 * Hamrah-e Aval and `cellular:43235` for Irancell, so a SIM swap or Wi-Fi ↔ mobile keeps separate memories.
 *
 *  * A route that failed the real check is skipped on that network for [BAD_FOR_MS] (6 h) unless it worked again
 *    after the failure, or every candidate is bad (then nothing is hidden).
 *  * A route that worked recently ([GOOD_FOR_MS], 3 days) is tried first in Automatic mode.
 */
internal data class RouteMemoryEntry(
    val fingerprint: String,
    val okAtMs: Long = 0L,
    val failAtMs: Long = 0L,
    val okCount: Int = 0,
    val failCount: Int = 0,
)

internal object RouteMemoryPolicy {
    const val BAD_FOR_MS = 6L * 60 * 60 * 1000
    const val GOOD_FOR_MS = 3L * 24 * 60 * 60 * 1000
    const val MAX_ENTRIES_PER_NETWORK = 64
    const val MAX_NETWORKS = 12

    fun networkKey(isCellular: Boolean, isWifi: Boolean, isEthernet: Boolean, operatorCode: String?): String {
        val transport = when {
            isCellular -> "cellular"
            isWifi -> "wifi"
            isEthernet -> "ethernet"
            else -> "other"
        }
        val op = if (isCellular) operatorCode.orEmpty().filter(Char::isDigit).takeIf { it.length in 5..6 } ?: "unknown" else ""
        return if (op.isEmpty()) transport else "$transport:$op"
    }

    fun record(entries: List<RouteMemoryEntry>, fingerprint: String, ok: Boolean, nowMs: Long): List<RouteMemoryEntry> {
        if (fingerprint.isBlank()) return entries
        val old = entries.firstOrNull { it.fingerprint == fingerprint } ?: RouteMemoryEntry(fingerprint)
        val updated = if (ok) old.copy(okAtMs = nowMs, okCount = old.okCount + 1)
        else old.copy(failAtMs = nowMs, failCount = old.failCount + 1)
        return (listOf(updated) + entries.filter { it.fingerprint != fingerprint })
            .sortedByDescending { maxOf(it.okAtMs, it.failAtMs) }
            .take(MAX_ENTRIES_PER_NETWORK)
    }

    fun isBad(e: RouteMemoryEntry, nowMs: Long): Boolean =
        e.failAtMs > 0 && e.failAtMs > e.okAtMs && nowMs - e.failAtMs in 0 until BAD_FOR_MS

    fun isGood(e: RouteMemoryEntry, nowMs: Long): Boolean =
        e.okAtMs > 0 && e.okAtMs >= e.failAtMs && nowMs - e.okAtMs in 0 until GOOD_FOR_MS

    fun bad(entries: List<RouteMemoryEntry>, nowMs: Long): Set<String> =
        entries.filter { isBad(it, nowMs) }.mapTo(linkedSetOf()) { it.fingerprint }

    /** Good routes, most recently confirmed first. */
    fun good(entries: List<RouteMemoryEntry>, nowMs: Long): List<String> =
        entries.filter { isGood(it, nowMs) }.sortedByDescending { it.okAtMs }.map { it.fingerprint }

    /**
     * The next route to try after [current] failed: remembered-good first, then by measured delay, never a route
     * already tried in this connection, never a route bad on this network (unless only bad ones are left).
     */
    fun nextCandidate(
        candidates: List<String>,
        current: String,
        tried: Set<String>,
        good: List<String>,
        bad: Set<String>,
        delayMs: Map<String, Long> = emptyMap(),
    ): String? {
        val untried = candidates.distinct().filter { it.isNotBlank() && it != current && it !in tried }
        if (untried.isEmpty()) return null
        val notBad = untried.filter { it !in bad }
        val pool = notBad.ifEmpty { untried }
        val order = candidates.withIndex().associate { it.value to it.index }
        return pool.sortedWith(
            compareBy<String> { fp -> good.indexOf(fp).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .thenBy { delayMs[it] ?: Long.MAX_VALUE }
                .thenBy { order[it] ?: Int.MAX_VALUE },
        ).firstOrNull()
    }

    fun toJson(entries: List<RouteMemoryEntry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().put("f", e.fingerprint).put("ok", e.okAtMs).put("fail", e.failAtMs).put("okN", e.okCount).put("failN", e.failCount))
        }
    }.toString()

    fun fromJson(raw: String?): List<RouteMemoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = o.optString("f").takeIf(String::isNotBlank) ?: return@mapNotNull null
                RouteMemoryEntry(f, o.optLong("ok"), o.optLong("fail"), o.optInt("okN"), o.optInt("failN"))
            }
        }.getOrDefault(emptyList())
    }
}

/** SharedPreferences persistence for [RouteMemoryPolicy]; one JSON list per network key. */
internal class RouteMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(networkKey: String): List<RouteMemoryEntry> = RouteMemoryPolicy.fromJson(prefs.getString(KEY_PREFIX + networkKey, null))

    @Synchronized
    fun record(networkKey: String, fingerprint: String, ok: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (fingerprint.isBlank()) return
        val next = RouteMemoryPolicy.record(entries(networkKey), fingerprint, ok, nowMs)
        val networks = (listOf(networkKey) + prefs.getString(KEY_NETWORKS, "").orEmpty().split(',').filter(String::isNotBlank))
            .distinct()
        val keep = networks.take(RouteMemoryPolicy.MAX_NETWORKS)
        prefs.edit().apply {
            putString(KEY_PREFIX + networkKey, RouteMemoryPolicy.toJson(next))
            putString(KEY_NETWORKS, keep.joinToString(","))
            networks.drop(RouteMemoryPolicy.MAX_NETWORKS).forEach { remove(KEY_PREFIX + it) }
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "cat_client_route_memory"
        private const val KEY_PREFIX = "net."
        private const val KEY_NETWORKS = "networks"
    }
}
