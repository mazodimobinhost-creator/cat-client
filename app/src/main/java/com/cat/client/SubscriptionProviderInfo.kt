package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

/**
 * beta239 — the provider headers that subscription panels (Marzban, 3x-ui, Hiddify-style and others) send next to
 * `subscription-userinfo`. Cat only read the usage header before; these add:
 *
 *  - `profile-title` — the name the provider gives the subscription (shown when it differs from the user's name)
 *  - `profile-update-interval` — hours between refreshes the provider asks for (the background refresh honours it)
 *  - `announce` — a message from the provider (shown on the card; a NEW message raises one notification)
 *  - `support-url` / `profile-web-page-url` — support contact and the provider's own usage page
 *
 * Values may be plain or `base64:<…>` (common panel convention). Everything is sanitised: control characters are
 * dropped, lengths are capped, and links are only accepted as `https://` or `tg://` so a hostile panel cannot inject
 * other intent schemes.
 */
internal data class SubscriptionProviderInfo(
    val title: String = "",
    val updateIntervalHours: Int? = null,
    val announce: String = "",
    val supportUrl: String = "",
    val webPageUrl: String = "",
) {
    val isEmpty: Boolean
        get() = title.isEmpty() && updateIntervalHours == null && announce.isEmpty() && supportUrl.isEmpty() && webPageUrl.isEmpty()
}

internal object SubscriptionProviderInfoPolicy {
    val HEADERS = listOf("profile-title", "profile-update-interval", "announce", "support-url", "profile-web-page-url")
    const val MAX_TITLE = 64
    const val MAX_ANNOUNCE = 500
    const val MAX_URL = 300

    /** Header names are matched case-insensitively. Returns null when nothing usable is present. */
    fun parse(headers: Map<String, String?>): SubscriptionProviderInfo? {
        val lower = headers.entries.associate { it.key.lowercase(Locale.US) to it.value }
        val info = SubscriptionProviderInfo(
            title = text(lower["profile-title"], MAX_TITLE, multiline = false),
            updateIntervalHours = lower["profile-update-interval"]?.trim()?.toIntOrNull()?.takeIf { it in 1..24 * 30 },
            announce = text(lower["announce"], MAX_ANNOUNCE, multiline = true),
            supportUrl = safeUrl(lower["support-url"]),
            webPageUrl = safeUrl(lower["profile-web-page-url"]),
        )
        return info.takeUnless { it.isEmpty }
    }

    /** `base64:<…>` → decoded text; plain values pass through. */
    fun decodeValue(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (!v.startsWith("base64:", ignoreCase = true)) return v
        val body = v.substring(7).trim()
        return runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(body.trimEnd('=')), Charsets.UTF_8) }
            .getOrDefault("")
    }

    private fun text(raw: String?, max: Int, multiline: Boolean): String {
        val decoded = decodeValue(raw)
        val cleaned = decoded.filter { c ->
            (multiline && c == '\n') || (!c.isISOControl() && Character.getType(c) != Character.FORMAT.toInt())
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (cleaned.length > max) cleaned.take(max - 1).trimEnd() + "…" else cleaned
    }

    fun safeUrl(raw: String?): String {
        val v = decodeValue(raw).trim()
        if (v.isEmpty() || v.length > MAX_URL || v.any { it.isWhitespace() || it.isISOControl() }) return ""
        val lower = v.lowercase(Locale.US)
        val ok = when {
            lower.startsWith("https://") -> runCatching { java.net.URI(v).host }.getOrNull().orEmpty().isNotBlank()
            lower.startsWith("tg://") -> true
            else -> false
        }
        return if (ok) v else ""
    }

    /**
     * The background job runs every [SubscriptionAutoRefresh.INTERVAL_HOURS]; a provider asking for a LONGER interval
     * is respected (10 min slack so a 12 h request is not skipped by a few seconds). Never refreshed → refresh now.
     */
    fun shouldRefresh(updatedAtMs: Long, intervalHours: Int?, nowMs: Long): Boolean {
        if (intervalHours == null || updatedAtMs <= 0L) return true
        return nowMs - updatedAtMs >= intervalHours * 3_600_000L - 600_000L
    }

    /** Short card text: provider title (when it differs) and the announcement. */
    fun cardLines(info: SubscriptionProviderInfo?, userName: String): String {
        info ?: return ""
        val lines = mutableListOf<String>()
        if (info.title.isNotEmpty() && !info.title.equals(userName.trim(), ignoreCase = true)) lines += "🏷 ${info.title}"
        if (info.announce.isNotEmpty()) lines += "📢 " + info.announce.lineSequence().joinToString(" ").let { if (it.length > 160) it.take(159) + "…" else it }
        return if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n")
    }
}

internal class SubscriptionProviderInfoStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(input: String, info: SubscriptionProviderInfo) {
        val payload = JSONObject()
            .put("title", info.title)
            .put("interval", info.updateIntervalHours ?: 0)
            .put("announce", info.announce)
            .put("support", info.supportUrl)
            .put("page", info.webPageUrl)
        prefs.edit().putString(SubscriptionUsagePolicy.sourceKey(input), payload.toString()).apply()
    }

    fun read(input: String): SubscriptionProviderInfo? {
        val raw = prefs.getString(SubscriptionUsagePolicy.sourceKey(input), null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return SubscriptionProviderInfo(
            title = o.optString("title"),
            updateIntervalHours = o.optInt("interval", 0).takeIf { it > 0 },
            announce = o.optString("announce"),
            supportUrl = o.optString("support"),
            webPageUrl = o.optString("page"),
        ).takeUnless { it.isEmpty }
    }

    /** True once per new, non-empty announcement for this subscription (used for a single notification). */
    fun markAnnouncementSeen(input: String, announce: String): Boolean {
        if (announce.isBlank()) return false
        val key = "seen_" + SubscriptionUsagePolicy.sourceKey(input)
        val hash = Integer.toHexString(announce.hashCode())
        if (prefs.getString(key, null) == hash) return false
        prefs.edit().putString(key, hash).apply()
        return true
    }

    private companion object {
        const val PREFERENCES_NAME = "cat_client_subscription_provider"
    }
}
