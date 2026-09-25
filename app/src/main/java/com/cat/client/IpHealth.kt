package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tracked clean IP: last probe state plus the failure streak used for
 * automatic eviction.
 */
data class IpHealthEntry(
    val ip: String,
    val port: Int = 443,
    val sni: String = "",
    val sourceRange: String? = null,
    val pingMs: Long = 0,
    val tlsMs: Long? = null,
    val colo: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
    val checkedAt: Long = 0,
    val fails: Int = 0,
)

/** Rotation log entry: an evicted IP and (optionally) the replacement drawn for it. */
data class IpRotationEvent(
    val at: Long,
    val removedIp: String,
    val addedIp: String?,
    /** Machine code: timeout | tcp | tls | slow — localized by the UI. */
    val reason: String,
)

data class IpSweepResult(
    val kept: List<IpHealthEntry>,
    val added: List<IpHealthEntry>,
    val removed: List<IpRotationEvent>,
    val checked: Int,
)

/** Prefs-backed store for the managed IP pool, its rotation log and settings. */
class IpHealthStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_ip_health", Context.MODE_PRIVATE)

    fun entries(): List<IpHealthEntry> = parseEntries(prefs.getString("pool", null))

    fun saveEntries(entries: List<IpHealthEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("ip", entry.ip)
                    .put("port", entry.port)
                    .put("sni", entry.sni)
                    .put("range", entry.sourceRange ?: "")
                    .put("pingMs", entry.pingMs)
                    .put("tlsMs", entry.tlsMs ?: -1L)
                    .put("colo", entry.colo ?: "")
                    .put("cc", entry.countryCode ?: "")
                    .put("cn", entry.countryName ?: "")
                    .put("at", entry.checkedAt)
                    .put("fails", entry.fails),
            )
        }
        prefs.edit().putString("pool", array.toString()).apply()
    }

    fun events(): List<IpRotationEvent> {
        val raw = prefs.getString("events", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        IpRotationEvent(
                            at = item.optLong("at", 0L),
                            removedIp = item.optString("removed"),
                            addedIp = item.optString("added").takeIf { it.isNotBlank() },
                            reason = item.optString("reason", "tcp"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordEvents(events: List<IpRotationEvent>) {
        if (events.isEmpty()) return
        val merged = (events.reversed() + events()).take(12)
        val array = JSONArray()
        merged.forEach { event ->
            array.put(
                JSONObject()
                    .put("at", event.at)
                    .put("removed", event.removedIp)
                    .put("added", event.addedIp ?: "")
                    .put("reason", event.reason),
            )
        }
        prefs.edit().putString("events", array.toString()).apply()
    }

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", false)
        set(value) = prefs.edit().putBoolean("auto", value).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 15).coerceIn(1, 240)
        set(value) = prefs.edit().putInterval(value).apply()

    var lastSweepAt: Long
        get() = prefs.getLong("lastSweep", 0L)
        set(value) = prefs.edit().putLong("lastSweep", value).apply()

    private fun android.content.SharedPreferences.Editor.putInterval(value: Int) =
        putInt("interval", value.coerceIn(1, 240))

    private fun parseEntries(raw: String?): List<IpHealthEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val ip = item.optString("ip")
                    if (ip.isBlank()) continue
                    add(
                        IpHealthEntry(
                            ip = ip,
                            port = item.optInt("port", 443),
                            sni = item.optString("sni"),
                            sourceRange = item.optString("range").takeIf { it.isNotBlank() },
                            pingMs = item.optLong("pingMs", 0L),
                            tlsMs = item.optLong("tlsMs", -1L).takeIf { it >= 0 },
                            colo = item.optString("colo").takeIf { it.isNotBlank() },
                            countryCode = item.optString("cc").takeIf { it.isNotBlank() },
                            countryName = item.optString("cn").takeIf { it.isNotBlank() },
                            checkedAt = item.optLong("at", 0L),
                            fails = item.optInt("fails", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Live clean-IP health with automatic replacement.
 *
 * A sweep re-probes every tracked IP. Anything unreachable, TLS-broken or far
 * too slow accumulates failures; once an IP is broken twice it is evicted and a
 * fresh healthy candidate is drawn from the scanner library to take its place,
 * so the active list always stays whole.
 */
object IpHealthMonitor {
    const val MAX_FAILS = 2
    const val SLOW_MS = 1_800L
    const val POOL_SIZE = 12
    const val REPLACEMENT_TRIES = 28

    suspend fun sweep(
        store: IpHealthStore,
        sni: String,
        port: Int,
        onProgress: (String) -> Unit = {},
    ): IpSweepResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entries = store.entries()
        if (entries.isEmpty()) return@withContext IpSweepResult(emptyList(), emptyList(), emptyList(), 0)

        val options = IpScanner.ScanOptions(
            sni = sni,
            port = port,
            includeBuiltin = false,
            includeIranLibrary = false,
            verifyHttp = true,
        )

        val kept = mutableListOf<IpHealthEntry>()
        val removed = mutableListOf<IpRotationEvent>()
        coroutineScope {
            entries.map { entry ->
                async { entry to IpScanner.probe(entry.ip, options) }
            }.forEach { deferred ->
                val (entry, result) = deferred.await()
                val nowStamp = System.currentTimeMillis()
                val healthy = result != null && result.tlsOk && result.pingMs <= SLOW_MS
                if (healthy) {
                    kept += entry.copy(
                        pingMs = result.pingMs,
                        tlsMs = result.tlsMs,
                        colo = result.colo,
                        countryCode = result.countryCode ?: EdgeLocationCatalog.countryForColo(result.colo),
                        countryName = result.countryName
                            ?: EdgeLocationCatalog.fromColo(result.colo)?.country,
                        checkedAt = nowStamp,
                        fails = 0,
                    )
                } else {
                    val reason = when {
                        result == null -> "tcp"
                        !result.tlsOk -> "tls"
                        else -> "slow"
                    }
                    val fails = entry.fails + 1
                    if (fails >= MAX_FAILS) {
                        removed += IpRotationEvent(nowStamp, entry.ip, null, reason)
                        onProgress(entry.ip)
                    } else {
                        kept += entry.copy(fails = fails, checkedAt = nowStamp)
                    }
                }
            }
        }

        // Draw replacements from the public libraries until the pool is whole again
        // (first run simply fills the pool from scratch).
        val added = mutableListOf<IpHealthEntry>()
        val need = (POOL_SIZE - kept.size).coerceAtLeast(0)
        if (need > 0) {
            val taken = (kept.map { it.ip } + removed.map { it.removedIp }).toHashSet()
            val candidates = (IpScanner.IRAN_LIBRARY + CommunityIpLibrary.ips)
                .filterNot(taken::contains)
                .shuffled()
                .take(REPLACEMENT_TRIES)
            val fresh = coroutineScope {
                candidates.map { ip ->
                    async { ip to IpScanner.probe(ip, options) }
                }.mapNotNull { deferred ->
                    val (ip, result) = deferred.await()
                    if (result != null && result.tlsOk && result.pingMs <= SLOW_MS) {
                        IpHealthEntry(
                            ip = ip,
                            port = port,
                            sni = sni,
                            pingMs = result.pingMs,
                            tlsMs = result.tlsMs,
                            colo = result.colo,
                            countryCode = result.countryCode ?: EdgeLocationCatalog.countryForColo(result.colo),
                            countryName = result.countryName
                                ?: EdgeLocationCatalog.fromColo(result.colo)?.country,
                            checkedAt = System.currentTimeMillis(),
                            fails = 0,
                        )
                    } else {
                        null
                    }
                }.take(need)
            }
            added += fresh
            // Pair each replacement with the oldest eviction for the log.
            val evictions = removed.toMutableList()
            fresh.forEach { entry ->
                val victim = evictions.removeFirstOrNull()
                if (victim != null) {
                    val index = removed.indexOf(victim)
                    removed[index] = victim.copy(addedIp = entry.ip)
                }
            }
        }

        val finalList = (kept + added).sortedByDescending { it.pingMs == 0L }.take(POOL_SIZE)
        store.saveEntries(finalList)
        store.recordEvents(removed)
        store.lastSweepAt = now
        IpSweepResult(finalList, added, removed, entries.size)
    }

    /** Seed the pool from the freshest scanner results (first-time setup). */
    fun seed(store: IpHealthStore, results: List<IpScanner.ScanResult>, sni: String, port: Int) {
        val entries = results
            .filter { it.tlsOk }
            .sortedBy { it.pingMs }
            .take(POOL_SIZE)
            .map {
                IpHealthEntry(
                    ip = it.ip,
                    port = it.port,
                    sni = sni,
                    sourceRange = it.sourceRange,
                    pingMs = it.pingMs,
                    tlsMs = it.tlsMs,
                    colo = it.colo,
                    countryCode = it.countryCode ?: EdgeLocationCatalog.countryForColo(it.colo),
                    countryName = it.countryName ?: EdgeLocationCatalog.fromColo(it.colo)?.country,
                    checkedAt = System.currentTimeMillis(),
                )
            }
        store.saveEntries(entries)
    }
}
