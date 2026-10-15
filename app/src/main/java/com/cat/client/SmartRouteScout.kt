package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL


internal object SmartScoutVerdictPolicy {
    fun endToEndHttp(status: Int?): SmartRouteVerdict =
        if (status != null && status in 200..399) SmartRouteVerdict.WORKS else SmartRouteVerdict.FAILED

    /** A healthy /health proves Worker code, never the tunnel carried user traffic. */
    fun workerControlPlane(state: String): SmartRouteVerdict = when (state) {
        "HEALTHY" -> SmartRouteVerdict.INCONCLUSIVE
        "PENDING" -> SmartRouteVerdict.NEEDS_SETUP
        else -> SmartRouteVerdict.FAILED
    }
}

internal data class SmartScoutTarget(
    val id: String,
    val kind: SmartRouteKind,
    val label: String,
)

/** Non-profile routes. Every WORKS verdict below includes a real HTTP response. */
internal object SmartRouteScout {
    const val DIRECT_ID = "smart-direct"
    const val ORBOT_ID = "smart-orbot"

    suspend fun run(context: Context): List<SmartRouteMeasurement> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        buildList {
            add(probeDirect(context, now))
            add(probeOrbot(context, now))
            PanelDeploymentStore(context).deployments().forEach { deployment ->
                add(probeWorkerControlPlane(deployment.workerUrl, now))
            }
        }
    }

    private fun probeDirect(context: Context, now: Long): SmartRouteMeasurement {
        val started = System.nanoTime()
        val status = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.allNetworks.firstOrNull { candidate ->
                val capabilities = manager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } ?: return measurement(DIRECT_ID, SmartRouteKind.DIRECT, "Direct", SmartRouteVerdict.FAILED, now)
            val connection = network.openConnection(URL(MihomoRuntimeDefaults.HEALTH_URL)) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            val response = connection.responseCode
            connection.disconnect()
            response
        }.getOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return measurement(
            DIRECT_ID,
            SmartRouteKind.DIRECT,
            "Direct",
            SmartScoutVerdictPolicy.endToEndHttp(status),
            now,
            elapsed,
        )
    }

    private fun probeOrbot(context: Context, now: Long): SmartRouteMeasurement {
        val settings = OrbotEnginePreferenceStore(context)
        val host = settings.readHost()
        val port = settings.readPort()
        if (host != OrbotEnginePolicy.DEFAULT_HOST) {
            // The shared HTTP helper is intentionally loopback-only and currently accepts a port.
            return measurement(ORBOT_ID, SmartRouteKind.ORBOT, "Orbot", SmartRouteVerdict.INCONCLUSIVE, now)
        }
        val started = System.nanoTime()
        val status = runCatching {
            MihomoRuntimeHealth.httpStatusThroughSocksProxy(port = port, timeoutMs = 5_000)
        }.getOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return measurement(
            ORBOT_ID,
            SmartRouteKind.ORBOT,
            "Orbot / Tor",
            SmartScoutVerdictPolicy.endToEndHttp(status),
            now,
            elapsed,
        )
    }

    /** Healthy panel code is useful diagnosis, but it does not prove its VLESS data path. */
    private fun probeWorkerControlPlane(workerUrl: String, now: Long): SmartRouteMeasurement {
        val host = runCatching { URL(workerUrl).host }.getOrDefault(workerUrl)
        val started = System.nanoTime()
        val health = CloudflareWorker.checkPanelHealth(workerUrl)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return measurement(
            id = "worker-control:" + host.lowercase(),
            kind = SmartRouteKind.WORKER,
            label = "Worker · $host",
            verdict = SmartScoutVerdictPolicy.workerControlPlane(health.state),
            now = now,
            latencyMs = elapsed,
            requiresSetup = health.state == "PENDING",
        )
    }

    private fun measurement(
        id: String,
        kind: SmartRouteKind,
        label: String,
        verdict: SmartRouteVerdict,
        now: Long,
        latencyMs: Long? = null,
        requiresSetup: Boolean = false,
    ) = SmartRouteMeasurement(
        id = id,
        kind = kind,
        label = label,
        verdict = verdict,
        medianRttMs = latencyMs,
        p95RttMs = latencyMs,
        successRate = if (verdict == SmartRouteVerdict.WORKS) 1.0 else 0.0,
        sampleCount = 1,
        measuredAtMs = now,
        supportsUdp = false,
        requiresSetup = requiresSetup,
    )
}

internal class SmartRouteScoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_smart_scout", Context.MODE_PRIVATE)

    fun save(networkKey: String, measurements: List<SmartRouteMeasurement>) {
        val history = readRawHistory(networkKey).toMutableList()
        history.addAll(measurements)
        val bounded = history.groupBy { it.id }.values.flatMap { routeSamples ->
            routeSamples.sortedBy { it.measuredAtMs }.takeLast(SmartRouteHistory.MAX_SAMPLES_PER_ROUTE)
        }
        prefs.edit().putString("history:$networkKey", encode(bounded).toString()).apply()
    }

    fun read(networkKey: String): List<SmartRouteMeasurement> {
        val history = readRawHistory(networkKey)
        if (history.isNotEmpty()) {
            return history.groupBy { it.id }.values.mapNotNull { SmartRouteHistory.aggregate(it)?.measurement }
        }
        // One-time compatibility with snapshots written before bounded history existed.
        return decode(prefs.getString("routes:$networkKey", null))
    }

    private fun readRawHistory(networkKey: String): List<SmartRouteMeasurement> =
        decode(prefs.getString("history:$networkKey", null))

    private fun encode(items: List<SmartRouteMeasurement>) = JSONArray().also { array ->
        items.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id).put("kind", item.kind.name).put("label", item.label)
                .put("verdict", item.verdict.name).put("rtt", item.medianRttMs ?: JSONObject.NULL)
                .put("throughput", item.throughputKbps ?: JSONObject.NULL)
                .put("at", item.measuredAtMs).put("setup", item.requiresSetup)
                .put("country", item.countryCode ?: JSONObject.NULL).put("ip", item.exitIp ?: JSONObject.NULL))
        }
    }

    private fun decode(raw: String?): List<SmartRouteMeasurement> {
        if (raw == null) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val verdict = runCatching { SmartRouteVerdict.valueOf(item.optString("verdict")) }.getOrNull() ?: continue
                    val kind = runCatching { SmartRouteKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
                    val rtt = item.optLong("rtt", -1L).takeIf { it >= 0 }
                    add(SmartRouteMeasurement(
                        id = item.optString("id"), kind = kind, label = item.optString("label"), verdict = verdict,
                        countryCode = item.optString("country").takeIf { it.length == 2 },
                        exitIp = item.optString("ip").takeIf { it.isNotBlank() && it != "null" },
                        medianRttMs = rtt, p95RttMs = rtt,
                        throughputKbps = item.optLong("throughput", -1L).takeIf { it >= 0 },
                        successRate = if (verdict == SmartRouteVerdict.WORKS) 1.0 else 0.0,
                        sampleCount = 1, measuredAtMs = item.optLong("at"), requiresSetup = item.optBoolean("setup"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}
