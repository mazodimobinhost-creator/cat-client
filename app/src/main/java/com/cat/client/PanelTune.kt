package com.cat.client

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * The owner's fragment recommendation, read from the deployed panel.
 *
 * The app already knows what to do per carrier (that table ships inside the APK and works
 * offline). This is the *override*: when an ISP changes its DPI, the owner retunes the panel
 * and every phone picks it up on the next connect — no APK release.
 *
 * Wire format is deliberately plain text (`GET /api/tune?format=text`), not JSON:
 *  * the app then needs no JSON parser at runtime and none in unit tests (org.json is a stub
 *    on the JVM, so a JSON path would be untestable without a new dependency);
 *  * a leading version line makes «this is really a tune table» checkable, so an error page,
 *    a captive-portal redirect or an old worker can never be mistaken for settings.
 *
 * Three rules keep it safe:
 *  1. **whitelist, always** — only carrier ids from [MobileCarrier] and preset ids from
 *     [DpiFragmentPreset] survive parsing; anything else is dropped, never passed on;
 *  2. **cache on disk** — a failed fetch (offline, panel asleep, wrong URL) keeps the last
 *     good table instead of clearing it, and never blocks a connect;
 *  3. **built-ins win when the panel is silent** — an absent key means «use the app's
 *     choice», which is exactly the pre-tune behaviour.
 */
data class PanelTune(
    val presets: Map<String, String> = emptyMap(),
    val fetchedAt: Long = 0L,
    val version: String = "",
) {
    fun presetIdFor(carrier: MobileCarrier): String? = presets[carrier.id]?.takeIf { it.isNotBlank() }

    val isEmpty: Boolean get() = presets.isEmpty()

    companion object {
        /** First line of the text payload; bump it if the format ever changes. */
        const val MAGIC = "cat-panel-tune v1"

        /**
         * Parse the text payload. Pure and total: never throws, drops everything it does not
         * recognise. `now` is injected so the timestamp is testable.
         */
        fun parse(body: String?, now: Long = System.currentTimeMillis()): PanelTune {
            val lines = body.orEmpty().lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .toList()
            if (lines.firstOrNull() != MAGIC) return PanelTune()
            var version = ""
            val presets = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim().lowercase()
                if (key == "version" || key == "updated") {
                    if (key == "version") version = value
                    continue
                }
                val carrier = MobileCarrier.entries.firstOrNull { it.id == key } ?: continue
                // An id the app does not know would be silently dropped at connect time —
                // refuse it here so the cache only ever holds usable values.
                if (DpiFragmentPreset.entries.none { it.id == value }) continue
                presets[carrier.id] = value
            }
            return PanelTune(presets = presets, fetchedAt = now, version = version)
        }
    }
}

class PanelTuneStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): PanelTune = PanelTune.parse(
        body = preferences.getString(KEY_BODY, null),
        now = preferences.getLong(KEY_AT, 0L),
    )

    fun save(body: String?, version: String = "") {
        if (body.isNullOrBlank()) return
        preferences.edit()
            .putString(KEY_BODY, body)
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_VERSION, version)
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_BODY).remove(KEY_AT).remove(KEY_VERSION).apply()
    }

    private companion object {
        const val PREFERENCES = "cat_client_panel_tune"
        const val KEY_BODY = "body"
        const val KEY_AT = "at"
        const val KEY_VERSION = "version"
    }
}

object PanelTuneClient {
    private const val TIMEOUT_MS = 6_000

    /**
     * `GET /api/tune?format=text` — public on the panel on purpose: the app asks before it has
     * a session, and the response carries no secret (a fragment recommendation, no host, no
     * token). Returns the raw body so the caller can store exactly what the panel said and
     * re-validate it through [PanelTune.parse] on every read.
     */
    fun fetch(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val url = (if (base.startsWith("http")) base else "https://$base") + "/api/tune?format=text"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("accept", "text/plain")
                instanceFollowRedirects = false
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val type = conn.contentType.orEmpty()
                // A captive portal or a login page answers 200 with HTML; the magic line is the
                // second guard, this is the first.
                if (type.isNotEmpty() && !type.startsWith("text/plain")) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Refresh the cached table. Best-effort by design: the return value says whether something
     * usable arrived, and any failure leaves the previous table (or the built-in defaults) in
     * place.
     */
    fun refresh(store: PanelTuneStore, baseUrl: String): PanelTune? {
        val body = fetch(baseUrl) ?: return null
        val parsed = PanelTune.parse(body)
        if (parsed.isEmpty) return null
        store.save(body, parsed.version)
        return parsed
    }
}
