package com.cat.client

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * beta220 «real internet test»: «connected» only means the tunnel came up (Session Readiness in CONTEXT.md);
 * it does not prove pages load. About two seconds after every connect (and after every route switch) the service
 * makes real HTTPS requests from the app's own socket — which rides the VPN exactly like Instagram or Chrome
 * (TUN → DNS → core → server) — and reports one of:
 *
 *  * [RealInternetStatus.Works]      a 204 check answered AND ~200 KB downloaded at a usable speed;
 *  * [RealInternetStatus.Slow]       small requests pass but the download stalls or crawls — the classic
 *                                    «connected but Instagram freezes» (MTU / throttled route);
 *  * [RealInternetStatus.NoInternet] nothing came back through the tunnel (checked twice);
 *  * [RealInternetStatus.NotMeasurable] the app itself bypasses the VPN (DNS-tunnel engine), so its own requests
 *                                    would only test the plain line.
 *
 * Only real measurements: no figure is ever made up; failures carry the actual error.
 */
internal enum class RealInternetStatus(val wire: String) {
    Checking("checking"),
    Works("works"),
    Slow("slow"),
    NoInternet("none"),
    NotMeasurable("na"),
    ;

    val isProblem: Boolean get() = this == Slow || this == NoInternet

    companion object {
        fun fromWire(value: String?): RealInternetStatus? = entries.firstOrNull { it.wire == value }
    }
}

internal data class RealInternetResult(
    val status: RealInternetStatus,
    val latencyMs: Long = -1L,
    val kbps: Int = -1,
    /** Real error text of the failing step (empty when it worked). */
    val detail: String = "",
    val fingerprint: String = "",
    val routeLabel: String = "",
    val atMs: Long = 0L,
    /** How many automatic «next route» hops this connection already made (0 = first route). */
    val hops: Int = 0,
    /** True when the service can switch to another route right now (shows the «try next route» button). */
    val canTryNext: Boolean = false,
    /** Set when the service is switching to another route by itself. */
    val autoHopping: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("status", status.wire)
        .put("latencyMs", latencyMs)
        .put("kbps", kbps)
        .put("detail", detail)
        .put("fingerprint", fingerprint)
        .put("routeLabel", routeLabel)
        .put("atMs", atMs)
        .put("hops", hops)
        .put("canTryNext", canTryNext)
        .put("autoHopping", autoHopping)
        .toString()

    companion object {
        fun fromJson(raw: String?): RealInternetResult? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                RealInternetResult(
                    status = RealInternetStatus.fromWire(o.optString("status")) ?: return null,
                    latencyMs = o.optLong("latencyMs", -1L),
                    kbps = o.optInt("kbps", -1),
                    detail = o.optString("detail"),
                    fingerprint = o.optString("fingerprint"),
                    routeLabel = o.optString("routeLabel"),
                    atMs = o.optLong("atMs"),
                    hops = o.optInt("hops"),
                    canTryNext = o.optBoolean("canTryNext"),
                    autoHopping = o.optBoolean("autoHopping"),
                )
            }.getOrNull()
        }
    }
}

internal object RealInternetCheck {
    /** Wait this long after «connected» so the core's DNS/route caches are warm, then measure. */
    const val START_DELAY_MS = 2_000L
    /** One failed check is not enough to blame a route: check again after this pause. */
    const val RECHECK_DELAY_MS = 4_000L
    const val PROBE_TIMEOUT_MS = 6_000
    const val DOWNLOAD_TIMEOUT_MS = 9_000
    const val DOWNLOAD_BYTES = 200_000
    /** A download that delivered at least this much counts as «big transfers pass». */
    const val MIN_DOWNLOAD_BYTES = 48_000
    /** Below ~150 kbit/s pages and Instagram feel frozen even though something trickles through. */
    const val SLOW_KBPS = 150
    /** At most this many automatic hops per connection (the user can always press the button again). */
    const val MAX_AUTO_HOPS = 3

    /** HTTPS only (cleartext is disabled app-wide); all of them answer 204 with an empty body. */
    val PROBE_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
    )
    val DOWNLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES",
        "https://proof.ovh.net/files/1Mb.dat",
    )

    data class Probe(val ok: Boolean, val latencyMs: Long, val error: String = "")
    data class Download(val bytes: Int, val elapsedMs: Long, val error: String = "")

    /** Pure decision from the two real measurements (unit-tested). */
    fun classify(probe: Probe?, download: Download?): RealInternetResult {
        val probeOk = probe?.ok == true
        val downloaded = download != null && download.bytes >= MIN_DOWNLOAD_BYTES && download.elapsedMs > 0
        val kbps = if (downloaded) kbps(download!!.bytes, download.elapsedMs) else -1
        val latency = probe?.takeIf { it.ok }?.latencyMs ?: -1L
        return when {
            downloaded && kbps < SLOW_KBPS -> RealInternetResult(RealInternetStatus.Slow, latency, kbps, "download ${kbps}kbps")
            downloaded -> RealInternetResult(RealInternetStatus.Works, latency, kbps)
            probeOk -> RealInternetResult(
                RealInternetStatus.Slow,
                latency,
                download?.takeIf { it.bytes > 0 && it.elapsedMs > 0 }?.let { kbps(it.bytes, it.elapsedMs) } ?: -1,
                download?.error?.ifBlank { "download stalled after ${download.bytes} bytes" } ?: "download not tried",
            )
            else -> RealInternetResult(
                RealInternetStatus.NoInternet,
                detail = listOfNotNull(probe?.error?.takeIf(String::isNotBlank), download?.error?.takeIf(String::isNotBlank))
                    .joinToString(" · ").ifBlank { "no answer through the tunnel" },
            )
        }
    }

    fun kbps(bytes: Int, elapsedMs: Long): Int =
        if (elapsedMs <= 0) -1 else ((bytes.toLong() * 8L) / elapsedMs).toInt().coerceAtLeast(0)

    /** Real measurement through whatever route the app's own sockets take (the VPN when connected). */
    fun run(): RealInternetResult {
        var probe: Probe? = null
        for (url in PROBE_URLS) {
            probe = probeOnce(url)
            if (probe.ok) break
        }
        var download: Download? = null
        for (url in DOWNLOAD_URLS) {
            val d = downloadOnce(url)
            if (download == null || d.bytes > download.bytes) download = d
            if (d.bytes >= MIN_DOWNLOAD_BYTES) break
        }
        return classify(probe, download)
    }

    fun probeOnce(url: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Probe {
        val started = SystemClock.elapsedRealtime()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "CatClient-RealCheck")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = conn.responseCode
                val elapsed = SystemClock.elapsedRealtime() - started
                if (code == 204 || code in 200..299) Probe(true, elapsed) else Probe(false, elapsed, "HTTP $code")
            } finally {
                conn.disconnect()
            }
        } catch (error: Throwable) {
            Probe(false, SystemClock.elapsedRealtime() - started, shortError(error))
        }
    }

    fun downloadOnce(url: String, maxBytes: Int = DOWNLOAD_BYTES, timeoutMs: Int = DOWNLOAD_TIMEOUT_MS): Download {
        val started = SystemClock.elapsedRealtime()
        var total = 0
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "CatClient-RealCheck")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return Download(0, SystemClock.elapsedRealtime() - started, "HTTP $code")
                val deadline = started + timeoutMs
                val buffer = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (total < maxBytes && SystemClock.elapsedRealtime() < deadline) {
                        val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                        if (n < 0) break
                        total += n
                    }
                }
                Download(total, SystemClock.elapsedRealtime() - started)
            } finally {
                conn.disconnect()
            }
        } catch (error: Throwable) {
            Download(total, SystemClock.elapsedRealtime() - started, shortError(error))
        }
    }

    private fun shortError(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(160)
}
