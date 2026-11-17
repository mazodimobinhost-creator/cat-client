package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** beta245 📶 the phone-side store for [OperatorScanHints] (SharedPreferences, this phone only). */
internal class OperatorScanMemory(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cat_operator_scan", Context.MODE_PRIVATE)

    /** true = cellular, false = Wi-Fi/Ethernet, null = cannot tell (only a VPN network visible, or no network). */
    fun onCellular(): Boolean? = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching null
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> false
            else -> null
        }
    }.getOrNull()

    /** The bucket this phone is in right now (operator id, «wifi», or "" when unknown). */
    fun currentBucket(): String = OperatorScanHints.bucket(CarrierDetector.detect(app, refresh = true), onCellular())

    fun learned(bucket: String): List<OperatorScanHints.Hit> =
        if (bucket.isBlank()) emptyList() else OperatorScanHints.decode(prefs.getString("op_$bucket", null))

    /** Records only verified rows (TLS + HTTP answered); returns how many are kept for [bucket]. */
    fun record(bucket: String, results: List<IpScanner.ScanResult>, now: Long = System.currentTimeMillis()): Int {
        if (bucket.isBlank()) return 0
        val fresh = results.filter { it.tlsOk }.sortedBy { it.pingMs }.take(40)
            .map { OperatorScanHints.Hit(it.ip, it.port, it.pingMs.toInt().coerceIn(1, 9999), now) }
        if (fresh.isEmpty()) return learned(bucket).size
        val merged = OperatorScanHints.merge(learned(bucket), fresh, now)
        prefs.edit().putString("op_$bucket", OperatorScanHints.encode(merged)).apply()
        return merged.size
    }
}
