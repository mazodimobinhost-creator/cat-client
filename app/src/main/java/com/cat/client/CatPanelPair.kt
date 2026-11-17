package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Panel ↔ app pairing. The owner generates a 6-character code in the panel;
 * this phone claims it over HTTPS. No UUID is transferred. Cat branding only.
 */
object CatPanelPair {
    private val CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")

    data class Result(
        val ok: Boolean,
        val version: String = "",
        val host: String = "",
        val error: String = "",
    )

    fun normalizeCode(raw: String): String =
        raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun looksLikeCode(raw: String): Boolean = CODE.matches(normalizeCode(raw))

    fun httpsOrigin(workerUrl: String): String? {
        val trimmed = workerUrl.trim().removeSuffix("/")
        if (trimmed.isBlank()) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> return null
            else -> "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        return "https://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    /** Blocking HTTPS POST to `/api/pair/claim`. */
    fun claim(workerUrl: String, code: String): Result {
        val origin = httpsOrigin(workerUrl) ?: return Result(ok = false, error = "https")
        val normalized = normalizeCode(code)
        if (!CODE.matches(normalized)) return Result(ok = false, error = "code")
        return runCatching {
            val conn = (URL("$origin/api/pair/claim").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
            }
            try {
                conn.outputStream.use { it.write("""{"code":"$normalized"}""".toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(1_024)
                    while (out.size() < 8_192) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@runCatching Result(ok = false, error = "json")
                if (status !in 200..299 || !json.optBoolean("ok")) {
                    return@runCatching Result(ok = false, error = json.optString("error").ifBlank { "claim" })
                }
                if (json.optString("panel") != "cat-panel") {
                    return@runCatching Result(ok = false, error = "not-cat")
                }
                Result(
                    ok = true,
                    version = json.optString("version"),
                    host = json.optString("host").ifBlank { URI(origin).host },
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Result(ok = false, error = (it.message ?: it.javaClass.simpleName).take(80)) }
    }
}

class CatPanelPairStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(host: String, version: String) {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_VERSION, version)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastHost(): String = prefs.getString(KEY_HOST, "").orEmpty()
    fun lastVersion(): String = prefs.getString(KEY_VERSION, "").orEmpty()

    companion object {
        private const val PREFS = "cat_client_panel_pair"
        private const val KEY_HOST = "host"
        private const val KEY_VERSION = "version"
        private const val KEY_AT = "at"
    }
}
