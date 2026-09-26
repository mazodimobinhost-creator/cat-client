package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Subscription usage reported by a panel through the
 * `subscription-userinfo` response header
 * (`upload=…; download=…; total=…; expire=…`).
 *
 * Cat Panel v3 sends this header, so the Subscriptions tab can show how much of
 * the quota is used and when it expires — the same data v2rayNG/Hiddify users
 * are used to seeing.
 */
data class SubscriptionUsage(
    val uploadBytes: Long,
    val downloadBytes: Long,
    val totalBytes: Long,
    val expireEpochSeconds: Long?,
) {
    val usedBytes: Long get() = (uploadBytes + downloadBytes).coerceAtLeast(0L)
    val remainingBytes: Long get() = if (totalBytes > 0) (totalBytes - usedBytes).coerceAtLeast(0L) else 0L
    val usedPercent: Int
        get() = if (totalBytes <= 0) 0 else ((usedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()

    /** Share of the quota already consumed, for progress bars (0..1). */
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

object SubscriptionUsagePolicy {
    /** Parses the `subscription-userinfo` header; returns null when nothing usable is present. */
    fun parse(header: String?): SubscriptionUsage? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire: Long? = null
        raw.split(';', ',').forEach { part ->
            val key = part.substringBefore('=', "").trim().lowercase(Locale.US)
            val value = part.substringAfter('=', "").trim()
            if (key.isEmpty() || value.isEmpty()) return@forEach
            when (key) {
                "upload" -> upload = positiveLong(value) ?: upload
                "download" -> download = positiveLong(value) ?: download
                "total" -> total = positiveLong(value) ?: total
                "expire" -> expire = positiveLong(value)?.takeIf { it > 0 }
            }
        }
        if (total <= 0 && upload <= 0 && download <= 0 && expire == null) return null
        return SubscriptionUsage(
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireEpochSeconds = expire,
        )
    }

    /** Stable key for a Subscription Source so usage survives renames. */
    fun sourceKey(input: String): String = Integer.toHexString(input.trim().hashCode())

    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
            value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
            value >= 1024L -> String.format(Locale.US, "%.0f KB", value / 1024.0)
            else -> "$value B"
        }
    }

    private fun positiveLong(value: String): Long? =
        value.toLongOrNull()?.takeIf { it >= 0 }
}

class SubscriptionUsageStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(input: String, usage: SubscriptionUsage, nowMs: Long = System.currentTimeMillis()) {
        val payload = JSONObject()
            .put("upload", usage.uploadBytes)
            .put("download", usage.downloadBytes)
            .put("total", usage.totalBytes)
            .put("expire", usage.expireEpochSeconds ?: 0L)
            .put("at", nowMs)
        prefs.edit().putString(SubscriptionUsagePolicy.sourceKey(input), payload.toString()).apply()
    }

    fun read(input: String): SubscriptionUsage? {
        val raw = prefs.getString(SubscriptionUsagePolicy.sourceKey(input), null) ?: return null
        val item = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return SubscriptionUsage(
            uploadBytes = item.optLong("upload", 0L),
            downloadBytes = item.optLong("download", 0L),
            totalBytes = item.optLong("total", 0L),
            expireEpochSeconds = item.optLong("expire", 0L).takeIf { it > 0L },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "cat_client_subscription_usage"
    }
}
