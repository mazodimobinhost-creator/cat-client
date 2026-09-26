package com.cat.client

import android.content.Context
import androidx.annotation.Keep
import com.follow.clash.core.TunInterface

object DpiBypassDefaults {
    const val PROXY_NAME = "CatClient ByeByeDPI"
    const val PROXY_HOST = "127.0.0.1"
    const val FALLBACK_PROXY_PORT = 1080
    private const val PROTECT_PATH_PLACEHOLDER = "android-vpn-protect"

    fun proxyArgs(port: Int): Array<String> {
        require(port in 1..65_535) { "Invalid ByeByeDPI port: $port" }
        return arrayOf(
            "ciadpi",
            "-i$PROXY_HOST",
            "-p$port",
            "-P$PROTECT_PATH_PLACEHOLDER",
            "-Kt,h",
            "-d1",
            "-f-1",
        )
    }
}

@Keep
object ByeDpiProxy {
    init {
        System.loadLibrary("core")
    }

    fun start(port: Int, protect: TunInterface): Int {
        return jniStartProxy(DpiBypassDefaults.proxyArgs(port), protect)
    }

    fun stop(): Int = jniStopProxy()

    private external fun jniStartProxy(args: Array<String>, protect: TunInterface): Int

    private external fun jniStopProxy(): Int
}

/**
 * Opt-in TLS fragmenting (ByeDPI) — the "Fragment" trick the panel recommends when the
 * worker's SNI is filtered: the ClientHello is split/disordered before it leaves
 * the phone so DPI cannot read the SNI. Off by default; the VPN service falls
 * back to a direct dial automatically if the local proxy fails to start.
 */
class DpiBypassPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun saveEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES = "cat_client_dpi_bypass"
        const val KEY_ENABLED = "enabled"
    }
}
