package com.cat.client

import android.content.Context
import org.json.JSONObject

/** Download (rx) / upload (tx) measured on this phone while a config was the live tunnel. */
data class ConfigTraffic(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
) {
    val usedBytes: Long get() = (rxBytes + txBytes).coerceAtLeast(0L)
}

object ConfigTrafficPolicy {
    const val MAX_FINGERPRINTS = 400

    fun applyDelta(root: JSONObject, fingerprint: String, rxDelta: Long, txDelta: Long): JSONObject {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return root
        val dRx = rxDelta.coerceAtLeast(0L)
        val dTx = txDelta.coerceAtLeast(0L)
        if (dRx == 0L && dTx == 0L) return root
        if (!root.has(fp) && root.length() >= MAX_FINGERPRINTS) return root
        val cur = root.optJSONObject(fp) ?: JSONObject()
        cur.put("rx", cur.optLong("rx") + dRx)
        cur.put("tx", cur.optLong("tx") + dTx)
        root.put(fp, cur)
        return root
    }

    fun read(root: JSONObject, fingerprint: String): ConfigTraffic {
        val cur = root.optJSONObject(fingerprint.trim()) ?: return ConfigTraffic()
        return ConfigTraffic(rxBytes = cur.optLong("rx"), txBytes = cur.optLong("tx"))
    }

    fun total(root: JSONObject, fingerprints: Collection<String>): ConfigTraffic {
        var rx = 0L
        var tx = 0L
        for (fp in fingerprints) {
            val row = read(root, fp)
            rx += row.rxBytes
            tx += row.txBytes
        }
        return ConfigTraffic(rx, tx)
    }

    fun formatLine(traffic: ConfigTraffic): String {
        if (traffic.usedBytes <= 0L) return ""
        return "↓ ${SubscriptionUsagePolicy.formatBytes(traffic.rxBytes)}  ↑ ${SubscriptionUsagePolicy.formatBytes(traffic.txBytes)}"
    }
}

class ConfigTrafficStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(fingerprint: String, rxDelta: Long, txDelta: Long) {
        val root = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        ConfigTrafficPolicy.applyDelta(root, fingerprint, rxDelta, txDelta)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    @Synchronized
    fun read(fingerprint: String): ConfigTraffic {
        val root = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        return ConfigTrafficPolicy.read(root, fingerprint)
    }

    @Synchronized
    fun total(fingerprints: Collection<String>): ConfigTraffic {
        val root = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        return ConfigTrafficPolicy.total(root, fingerprints)
    }

    private companion object {
        const val PREFERENCES_NAME = "cat_client_config_traffic"
        const val KEY = "by_fp"
    }
}
