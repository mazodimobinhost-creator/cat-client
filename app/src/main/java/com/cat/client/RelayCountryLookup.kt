package com.cat.client

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Country for relay hosts the trace could not name.
 *
 * The lookup runs over **HTTPS first**: plain-HTTP endpoints (the old
 * `http://ip-api.com/batch`-only path) simply never answered on the filtered networks
 * this app is used from, and the failure was swallowed, so every hit ended up in the
 * «??» / «نامشخص» group with no explanation. A few free HTTPS services are tried per
 * host, with a circuit breaker so a host-blocked endpoint costs a few failures instead
 * of one timeout per relay. The old batch call stays as a last-resort bulk pass.
 *
 * Never guesses: a host the lookup cannot resolve is absent from the result, so the
 * caller labels it honestly.
 *
 * ORDER OF RESOLVERS (beta99 — the report «همه کشورها 🌐 نامشخص» on a line where the
 * third-party endpoints answer nothing):
 *   0. **the owner's own deployed panel worker** `https://<worker>/api/geo?ip=` —
 *      Cloudflare resolves the address server-side, so the answer arrives over a
 *      connection that is known to work from a filtered network (the dashboard
 *      already trusts this endpoint for its own flag);
 *   1. direct HTTPS services (ipwho.is → api.country.is → ipapi.co);
 *   2. the old plain-HTTP `ip-api.com/batch` bulk pass.
 * The circuit breaker counts failures per endpoint and is deliberately lazy: it used
 * to retire an endpoint after TWO failures anywhere in the run (any transient error
 * on the first two hosts killed the endpoint for the whole batch and everything came
 * back «نامشخص»), so a whole-run death is now impossible.
 */
object RelayCountryLookup {
    /** `%s` = host. Order matters: cheapest/most permissive first. */
    private val HTTPS_ENDPOINTS = listOf(
        "https://ipwho.is/%s",           // {"ip":"…","country_code":"DE", …}
        "https://api.country.is/%s",     // {"ip":"…","country":"DE"}
        "https://ipapi.co/%s/country/",  // DE
    )
    private const val PER_REQUEST_TIMEOUT_MS = 4_000
    private const val TOTAL_BUDGET_MS = 20_000L
    private const val PARALLELISM = 8
    /** Four failures retire an endpoint for this call (was 2 → see class comment). */
    private const val BREAKER_LIMIT = 4
    /** Worker geo relay: the user's own domain, one IP per request. */
    private const val WORKER_TIMEOUT_MS = 6_000
    private const val WORKER_BUDGET_MS = 12_000L
    private const val WORKER_PARALLELISM = 6

    /** `host -> ISO-3166 alpha-2`. Empty map when every lookup failed. */
    fun resolve(context: Context?, hosts: List<String>, workerHost: String? = null): Map<String, String> {
        val pending = hosts.filter { it.isNotBlank() }.distinct()
        if (pending.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val started = System.currentTimeMillis()
        val worker = normalizeWorkerHost(workerHost)
        var fromWorker = 0
        if (worker != null) {
            val found = workerLookup(worker, pending, started)
            found.forEach { (host, cc) -> out[host] = cc }
            fromWorker = found.size
        }
        var fromHttps = 0
        val missing = pending.filterNot { out.containsKey(it) }
        if (missing.isNotEmpty()) {
            val failures = AtomicIntegerArray(HTTPS_ENDPOINTS.size)
            val pool = runCatching { Executors.newFixedThreadPool(PARALLELISM) }.getOrNull()
            if (pool != null) {
                try {
                    val futures = missing.map { ip ->
                        pool.submit {
                            if (System.currentTimeMillis() - started > TOTAL_BUDGET_MS) return@submit
                            httpsCountry(ip, failures)?.let { cc -> synchronized(out) { out[ip] = cc } }
                        }
                    }
                    futures.forEach { runCatching { it.get(TOTAL_BUDGET_MS, TimeUnit.MILLISECONDS) } }
                } finally {
                    runCatching { pool.shutdownNow() }
                }
            }
            fromHttps = missing.count { out.containsKey(it) }
        }
        var fromBatch = 0
        val stillMissing = pending.filterNot { out.containsKey(it) }
        if (stillMissing.isNotEmpty()) {
            val bulk = ipApiBatch(context, stillMissing)
            bulk.forEach { (host, cc) -> synchronized(out) { out[host] = cc } }
            fromBatch = stillMissing.count { out.containsKey(it) }
        }
        if (context != null) {
            // Written every run: the next «نامشخص» report then says WHICH resolver
            // was reachable (worker / https / batch) instead of leaving it to a guess.
            val detail = "hosts=${pending.size} worker=${if (worker != null) "yes" else "none"} " +
                "workerHits=$fromWorker httpsHits=$fromHttps batchHits=$fromBatch resolved=${out.size}"
            if (out.isEmpty()) DiagnosticLogger.warn(context, "relay.country.lookup.failed", detail)
            else DiagnosticLogger.info(context, "relay.country.lookup", detail)
        }
        return out
    }

    /**
     * The deployed panel worker (`https://name.sub.workers.dev` → `name.sub.workers.dev`).
     * Null when the owner never deployed one — the HTTPS chain is then the only path.
     */
    fun deployedWorkerHost(context: Context?): String? = runCatching {
        context ?: return@runCatching null
        normalizeWorkerHost(PanelDeploymentStore(context).deployments().firstOrNull()?.workerUrl)
    }.getOrNull()

    private fun normalizeWorkerHost(raw: String?): String? {
        val host = raw?.trim().orEmpty()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').trimEnd('/').trim()
        return host.takeIf { it.isNotBlank() && !it.contains(' ') }
    }

    /** Worker pass: `/api/geo?ip=` answers `{"ok":true,"geo":{"<ip>":{"cc":"DE"}}}`. */
    private fun workerLookup(workerHost: String, hosts: List<String>, started: Long): Map<String, String> {
        val out = HashMap<String, String>()
        val pool = runCatching { Executors.newFixedThreadPool(WORKER_PARALLELISM) }.getOrNull() ?: return out
        try {
            val futures = hosts.map { ip ->
                pool.submit {
                    if (System.currentTimeMillis() - started > WORKER_BUDGET_MS) return@submit
                    val cc = runCatching { fetchWorkerCountry(workerHost, ip) }.getOrNull() ?: return@submit
                    synchronized(out) { out[ip] = cc }
                }
            }
            futures.forEach { runCatching { it.get(WORKER_BUDGET_MS, TimeUnit.MILLISECONDS) } }
        } finally {
            runCatching { pool.shutdownNow() }
        }
        return out
    }

    private fun fetchWorkerCountry(workerHost: String, ip: String): String? {
        val url = "https://$workerHost/api/geo?ip=" + java.net.URLEncoder.encode(ip, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = WORKER_TIMEOUT_MS
            conn.readTimeout = WORKER_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "CatClient")
            if (conn.responseCode >= 400) return null
            val body = conn.inputStream.bufferedReader().readText()
            return parseWorkerCountry(body, ip)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Worker body → code. Tolerates a flat `cc`/`country_code` answer too. */
    private fun parseWorkerCountry(body: String, ip: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.optBoolean("ok", false)) return null
        val geo = json.optJSONObject("geo") ?: return null
        val entry = geo.optJSONObject(ip)
            ?: geo.keys().asSequence().firstOrNull()?.let { geo.optJSONObject(it) }
            ?: return null
        val raw = entry.optString("cc").ifBlank { entry.optString("country_code") }
        return normalizeCode(raw)
    }

    /** First HTTPS endpoint that answers with a two-letter code wins. */
    private fun httpsCountry(ip: String, failures: AtomicIntegerArray): String? {
        for (i in HTTPS_ENDPOINTS.indices) {
            if (failures.get(i) >= BREAKER_LIMIT) continue
            val code = runCatching { fetchCountry(HTTPS_ENDPOINTS[i].format(ip)) }.getOrNull()
            if (code != null) return code
            failures.incrementAndGet(i)
        }
        return null
    }

    private fun fetchCountry(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = PER_REQUEST_TIMEOUT_MS
            conn.readTimeout = PER_REQUEST_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json, text/plain")
            conn.setRequestProperty("User-Agent", "CatClient")
            val code = conn.responseCode
            if (code >= 400) return null
            val body = conn.inputStream.bufferedReader().readText().trim()
            return parseCountry(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Accepts `{"country_code":"DE"}`, `{"country":"DE"}` and a bare `DE` body. */
    private fun parseCountry(body: String): String? {
        val code = if (body.startsWith("{")) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (json.isNull("success") && !json.has("country_code") && !json.has("country")) return null
            if (!json.optBoolean("success", true)) return null
            json.optString("country_code").ifBlank { json.optString("country") }
        } else {
            body
        }
        return normalizeCode(code)
    }

    /** `DE` / `de` / `" DE "` → `DE`; anything that is not two letters → null. */
    private fun normalizeCode(raw: String?): String? {
        val cc = raw.orEmpty().trim().uppercase(java.util.Locale.ROOT).take(2)
        return cc.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    /** Bulk fallback: the original plain-HTTP batch, kept for networks where it works. */
    private fun ipApiBatch(context: Context?, hosts: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        hosts.chunked(100).forEach { chunk ->
            val failure = runCatching {
                val body = JSONArray().apply {
                    chunk.forEach { put(JSONObject().put("query", it)) }
                }.toString()
                val conn = URL("http://ip-api.com/batch?fields=countryCode,query").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 8_000; conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val text = stream.bufferedReader().readText()
                conn.disconnect()
                if (code >= 400) throw IllegalStateException("ip-api HTTP $code")
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cc = o.optString("countryCode").uppercase(java.util.Locale.ROOT)
                    if (cc.length == 2) out[o.optString("query")] = cc
                }
            }.exceptionOrNull()
            if (failure != null && context != null) {
                DiagnosticLogger.warn(context, "relay.country.lookup.batch", "hosts=${chunk.size}", failure)
            }
        }
        return out
    }
}
