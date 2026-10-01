package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CAT Network Cognitive Engine — phase A (on device).
 *
 * The unit is not "an IP" but an endpoint observed on a given network over time
 * (Endpoint × Network × Time). Every probe becomes an [Observation]; a ring of
 * observations is the endpoint's [EndpointGenome] from which median / p95 /
 * jitter / success-rate / trend / confidence are derived. The engine uses it to
 * classify failures, detect degradation *before* the endpoint is dead, rank
 * candidates on several objectives, and migrate the active fronting address with
 * warm-up + verify instead of a blind swap.
 *
 * Only addresses the user already configured or scanned are ever probed.
 */
data class Observation(
    val at: Long,
    val network: String,
    val rttMs: Long,
    val tlsMs: Long,
    val ok: Boolean,
    /** ok | timeout | tcp | tls | http | slow | latency-excursion | loss-burst */
    val failClass: String,
)

object FailureClass {
    const val OK = "ok"
    const val TIMEOUT = "timeout"
    const val TCP = "tcp"
    const val TLS = "tls"
    const val HTTP = "http"
    const val SLOW = "slow"
    const val LATENCY_EXCURSION = "latency-excursion"
    const val LOSS_BURST = "loss-burst"
}

class EndpointGenome(val ip: String, val samples: List<Observation>) {
    fun forNetwork(network: String?): EndpointGenome =
        if (network == null) this else EndpointGenome(ip, samples.filter { it.network == network }.ifEmpty { samples })

    val count: Int get() = samples.size
    private val okRtts: List<Long> get() = samples.filter { it.ok }.map { it.rttMs }.sorted()
    val successRate: Double get() = if (samples.isEmpty()) 0.0 else samples.count { it.ok } / samples.size.toDouble()
    val median: Long get() = percentile(50)
    val p95: Long get() = percentile(95)
    val p99: Long get() = percentile(99)
    val mean: Double get() = okRtts.takeIf { it.isNotEmpty() }?.average() ?: 0.0
    val jitter: Double
        get() {
            val r = okRtts
            if (r.size < 2) return 0.0
            val m = r.average()
            return sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
        }

    /** Confidence 0..1: grows with sample count, shrinks with failures and age. */
    val confidence: Double
        get() {
            if (samples.isEmpty()) return 0.0
            val n = (count / 12.0).coerceAtMost(1.0)
            val ageH = (System.currentTimeMillis() - samples.last().at) / 3_600_000.0
            val freshness = (1.0 - ageH / 24.0).coerceIn(0.2, 1.0)
            return n * successRate * freshness
        }

    /** Slope: recent window vs. previous window (positive = getting slower). */
    val trend: Double
        get() {
            val r = samples.filter { it.ok }.map { it.rttMs.toDouble() }
            if (r.size < 6) return 0.0
            val recent = r.takeLast(3).average()
            val before = r.dropLast(3).takeLast(3).average()
            return if (before <= 0) 0.0 else (recent - before) / before
        }

    /** Consecutive failures at the tail. */
    val tailFailures: Int get() = samples.asReversed().takeWhile { !it.ok }.size

    fun percentile(p: Int): Long {
        val r = okRtts
        if (r.isEmpty()) return 0
        val idx = ((p / 100.0) * (r.size - 1)).toInt().coerceIn(0, r.size - 1)
        return r[idx]
    }

    /** Anomaly detection against the endpoint's own history (not a fixed timeout). */
    fun anomaly(): Anomaly? {
        if (samples.size < 4) return null
        val last = samples.last()
        val base = EndpointGenome(ip, samples.dropLast(1))
        if (tailFailures >= 2) return Anomaly(FailureClass.LOSS_BURST, 0.9, "${tailFailures}× ${last.failClass}")
        if (!last.ok) return Anomaly(last.failClass, 0.6, last.failClass)
        val med = base.median
        if (med > 0 && last.rttMs > med * 1.8 && last.rttMs - med > 120) {
            return Anomaly(FailureClass.LATENCY_EXCURSION, 0.7, "${med}→${last.rttMs} ms")
        }
        if (trend > 0.35 && samples.size >= 6) return Anomaly("degradation", 0.5, "trend +${(trend * 100).toInt()}%")
        return null
    }

    /** Multi-objective score (higher is better). Weights are user-tunable. */
    fun score(w: ScoreWeights = ScoreWeights()): Double {
        if (okRtts.isEmpty()) return 0.0
        val lat = 1.0 / (1.0 + median / 150.0)
        val tail = 1.0 / (1.0 + p95 / 400.0)
        val jit = 1.0 / (1.0 + jitter / 60.0)
        val conf = confidence
        return w.latency * lat + w.tail * tail + w.reliability * successRate + w.jitter * jit + w.confidence * conf
    }

    /** Adaptive probing: stable endpoints are checked less often, noisy ones more. */
    fun nextProbeDelayMinutes(baseMinutes: Int): Int {
        if (samples.size < 4) return baseMinutes.coerceAtMost(5)
        val stable = successRate >= 0.95 && jitter < 40 && abs(trend) < 0.15
        val suspicious = anomaly() != null || successRate < 0.8
        return when {
            suspicious -> (baseMinutes / 3).coerceAtLeast(1)
            stable -> (baseMinutes * 3).coerceAtMost(240)
            else -> baseMinutes
        }
    }
}

data class Anomaly(val type: String, val confidence: Double, val detail: String)

data class ScoreWeights(
    val latency: Double = 0.35,
    val tail: Double = 0.15,
    val reliability: Double = 0.30,
    val jitter: Double = 0.10,
    val confidence: Double = 0.10,
)

/** Engine counters the system exposes about itself (observability). */
data class EngineStats(
    val probes: Long = 0,
    val probeSuccesses: Long = 0,
    val failovers: Long = 0,
    val predictedDegradations: Long = 0,
    val predictionsConfirmed: Long = 0,
    val lastRecoveryMs: Long = 0,
    val lastFailoverAt: Long = 0,
    val lastFailoverFrom: String = "",
    val lastFailoverTo: String = "",
    val lastFailoverReason: String = "",
    val observations: Long = 0,
    val chainHead: String = "",
) {
    val probeSuccessRate: Double get() = if (probes == 0L) 0.0 else probeSuccesses / probes.toDouble()
    val predictionAccuracy: Double get() = if (predictedDegradations == 0L) 0.0 else predictionsConfirmed / predictedDegradations.toDouble()
}

/** Prefs-backed temporal memory: per-endpoint observation rings + engine counters + hash chain. */
class NetworkGenomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_genome", Context.MODE_PRIVATE)

    fun genome(ip: String): EndpointGenome = EndpointGenome(ip, parse(prefs.getString("g:$ip", null)))

    fun genomes(ips: Collection<String>): Map<String, EndpointGenome> = ips.associateWith { genome(it) }

    @Synchronized
    fun record(ip: String, obs: Observation) {
        val ring = (parse(prefs.getString("g:$ip", null)) + obs).takeLast(RING)
        val stats = stats()
        val head = sha256(stats.chainHead + "|" + ip + "|" + obs.at + "|" + obs.network + "|" + obs.rttMs + "|" + obs.failClass).take(16)
        prefs.edit()
            .putString("g:$ip", serialize(ring))
            .putString("stats", serializeStats(stats.copy(
                probes = stats.probes + 1,
                probeSuccesses = stats.probeSuccesses + if (obs.ok) 1 else 0,
                observations = stats.observations + 1,
                chainHead = head,
            )))
            .apply()
    }

    fun forget(ip: String) = prefs.edit().remove("g:$ip").apply()

    fun stats(): EngineStats = parseStats(prefs.getString("stats", null))

    @Synchronized
    fun updateStats(block: (EngineStats) -> EngineStats) {
        prefs.edit().putString("stats", serializeStats(block(stats()))).apply()
    }

    var autoFailover: Boolean
        get() = prefs.getBoolean("autoFailover", true)
        set(value) = prefs.edit().putBoolean("autoFailover", value).apply()

    var nextProbeAt: Map<String, Long>
        get() = runCatching {
            val o = JSONObject(prefs.getString("next", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optLong(it) }
        }.getOrDefault(emptyMap())
        set(value) = prefs.edit().putString("next", JSONObject(value).toString()).apply()

    fun clear() = prefs.edit().clear().apply()

    private fun parse(raw: String?): List<Observation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    add(Observation(o.optLong("t"), o.optString("n"), o.optLong("r"), o.optLong("s", -1), o.optBoolean("ok"), o.optString("f", FailureClass.OK)))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serialize(list: List<Observation>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.at).put("n", it.network).put("r", it.rttMs).put("s", it.tlsMs).put("ok", it.ok).put("f", it.failClass)) }
    }.toString()

    private fun parseStats(raw: String?): EngineStats {
        if (raw.isNullOrBlank()) return EngineStats()
        return runCatching {
            val o = JSONObject(raw)
            EngineStats(
                probes = o.optLong("p"), probeSuccesses = o.optLong("ps"), failovers = o.optLong("f"),
                predictedDegradations = o.optLong("pd"), predictionsConfirmed = o.optLong("pc"),
                lastRecoveryMs = o.optLong("rec"), lastFailoverAt = o.optLong("fa"),
                lastFailoverFrom = o.optString("ff"), lastFailoverTo = o.optString("ft"), lastFailoverReason = o.optString("fr"),
                observations = o.optLong("o"), chainHead = o.optString("h"),
            )
        }.getOrDefault(EngineStats())
    }

    private fun serializeStats(s: EngineStats): String = JSONObject()
        .put("p", s.probes).put("ps", s.probeSuccesses).put("f", s.failovers).put("pd", s.predictedDegradations)
        .put("pc", s.predictionsConfirmed).put("rec", s.lastRecoveryMs).put("fa", s.lastFailoverAt)
        .put("ff", s.lastFailoverFrom).put("ft", s.lastFailoverTo).put("fr", s.lastFailoverReason)
        .put("o", s.observations).put("h", s.chainHead).toString()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val RING = 32
    }
}

/** Current network context: the same endpoint behaves differently per ISP / transport. */
object NetworkContext {
    fun key(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val op = tm?.networkOperator?.takeIf { it.isNotBlank() } ?: tm?.simOperator.orEmpty()
                "cell:" + op.ifBlank { "?" }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
    }.getOrDefault("unknown")
}

object CognitiveEngine {
    /** Map a raw probe outcome to a forensic failure class. */
    fun classify(result: IpScanner.ScanResult?, slowMs: Long, genome: EndpointGenome): String = when {
        result == null -> if (genome.samples.lastOrNull()?.ok == false) FailureClass.TIMEOUT else FailureClass.TCP
        !result.tlsOk -> FailureClass.TLS
        result.httpStatus != null && result.httpStatus >= 500 -> FailureClass.HTTP
        result.pingMs > slowMs -> FailureClass.SLOW
        genome.median > 0 && result.pingMs > genome.median * 2 && result.pingMs - genome.median > 150 -> FailureClass.LATENCY_EXCURSION
        else -> FailureClass.OK
    }

    /** Rank pool entries: genome score when there is history, otherwise fall back to last ping. */
    fun rank(entries: List<IpHealthEntry>, genomes: Map<String, EndpointGenome>, network: String?, weights: ScoreWeights = ScoreWeights()): List<IpHealthEntry> =
        entries.sortedByDescending { e ->
            val g = genomes[e.ip]?.forNetwork(network)
            if (g != null && g.count >= 2) g.score(weights) else (if (e.pingMs > 0) 1.0 / (1.0 + e.pingMs / 150.0) * 0.5 else 0.0)
        }

    data class MigrationPlan(val from: String, val to: IpHealthEntry, val reason: String, val predicted: Boolean)

    /**
     * Zero-downtime migration: the active address is replaced only when its own history
     * shows an anomaly AND a standby candidate has just been verified healthy twice.
     */
    fun planMigration(
        active: String?,
        pool: List<IpHealthEntry>,
        genomes: Map<String, EndpointGenome>,
        network: String?,
        verify: (IpHealthEntry) -> Boolean,
    ): MigrationPlan? {
        if (active.isNullOrBlank()) return null
        val g = genomes[active]?.forNetwork(network) ?: return null
        val anomaly = g.anomaly() ?: return null
        val gone = anomaly.type == FailureClass.LOSS_BURST || anomaly.type == FailureClass.TCP || anomaly.type == FailureClass.TIMEOUT || anomaly.type == FailureClass.TLS
        if (!gone && anomaly.confidence < 0.5) return null
        val ranked = rank(pool.filter { it.ip != active && it.fails == 0 }, genomes, network)
        for (candidate in ranked.take(3)) {
            if (verify(candidate) && verify(candidate)) return MigrationPlan(active, candidate, anomaly.type + " (" + anomaly.detail + ")", predicted = !gone)
        }
        return null
    }
}
