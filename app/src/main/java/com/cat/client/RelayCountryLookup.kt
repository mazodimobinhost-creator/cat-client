package com.cat.client

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Country for relay hosts whose `/cdn-cgi/trace` carried no usable `loc=`.
 *
 * One ip-api batch call for the whole list — never one request per host, and never
 * a guessed country: a host the lookup cannot resolve is simply absent from the
 * result, so the caller can label it honestly («نامشخص») instead of printing a
 * placeholder that looks like a country code.
 *
 * The endpoint is plain HTTP and sits OUTSIDE Cloudflare, so on a network whose
 * only working path is a CF-bound tunnel the whole call fails fast. That used to
 * happen silently (every hit stayed in the «??» group with no explanation); the
 * failure is now logged so the diagnostics page can show *why* a list came back
 * empty instead of leaving the user to guess.
 */
object RelayCountryLookup {
    /** `host -> ISO-3166 alpha-2`. Empty map when the lookup itself failed. */
    fun resolve(context: Context?, hosts: List<String>): Map<String, String> {
        val pending = hosts.filter { it.isNotBlank() }.distinct()
        if (pending.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        pending.chunked(100).forEach { chunk ->
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
                DiagnosticLogger.warn(context, "relay.country.lookup", "hosts=${chunk.size}", failure)
            }
        }
        return out
    }
}
