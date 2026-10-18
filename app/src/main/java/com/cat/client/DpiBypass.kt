package com.cat.client

import android.content.Context
import androidx.annotation.Keep
import com.follow.clash.core.TunInterface

object DpiBypassDefaults {
    const val PROXY_NAME = "CatClient ByeByeDPI"
    const val PROXY_HOST = "127.0.0.1"
    const val FALLBACK_PROXY_PORT = 1080
    private const val PROTECT_PATH_PLACEHOLDER = "android-vpn-protect"

    fun proxyArgs(port: Int, preset: DpiFragmentPreset = DpiFragmentPreset.DEFAULT): Array<String> {
        require(port in 1..65_535) { "Invalid ByeByeDPI port: $port" }
        return arrayOf(
            "ciadpi",
            "-i$PROXY_HOST",
            "-p$port",
            "-P$PROTECT_PATH_PLACEHOLDER",
            *preset.ciadpiArgs,
        )
    }
}

@Keep
object ByeDpiProxy {
    init {
        System.loadLibrary("core")
    }

    fun start(port: Int, protect: TunInterface, preset: DpiFragmentPreset = DpiFragmentPreset.DEFAULT): Int {
        return jniStartProxy(DpiBypassDefaults.proxyArgs(port, preset), protect)
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

    fun presetId(): String = preferences.getString(KEY_PRESET, null) ?: DpiFragmentPreset.DEFAULT.id

    fun savePreset(id: String) {
        preferences.edit().putString(KEY_PRESET, DpiFragmentPreset.byId(id).id).apply()
    }

    private companion object {
        const val PREFERENCES = "cat_client_dpi_bypass"
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
    }
}

/**
 * ISP-specific ByeDPI fragmentation presets — starting points tuned per Iranian
 * carrier community feedback (the split position / disorder combination that
 * usually gets ClientHello through that network's DPI). Carriers vary by region
 * and change over time, so the UI presents them as "try one, keep what works".
 */
enum class DpiFragmentPreset(
    val id: String,
    val ciadpiArgs: Array<String>,
) {
    DEFAULT("default", arrayOf("-Kt,h", "-d1", "-f-1")),
    MCI("mci", arrayOf("-Kt,h", "-d2", "-f3")),
    IRANCELL("irancell", arrayOf("-Kt,h", "-d1", "-f5")),
    RIGHTEL("rightel", arrayOf("-Kt,h", "-d3", "-f2")),
    TCI("tci", arrayOf("-Kt", "-d2", "-f8")),
    GAMING("gaming", arrayOf("-Kt,h", "-f-2")),
    // PattN-style aggressive split: disorder from byte 104 of the ClientHello,
    // mimicking the popular two-stage tlshello 0/104/1 recipe as closely as
    // socket-level fragmentation allows.
    PATTN("pattn", arrayOf("-Kt,h", "-d1", "-f104")),
    ;

    companion object {
        fun byId(id: String?): DpiFragmentPreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
