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

    /**
     * `auto` (the default) resolves per carrier at connect time; anything else is a manual
     * pick. `byId` would swallow `auto` into DEFAULT, so the raw id is kept here.
     */
    fun presetId(): String {
        val stored = preferences.getString(KEY_PRESET, null)?.trim()?.lowercase() ?: return DpiFragmentPreset.AUTO_ID
        if (stored == DpiFragmentPreset.AUTO_ID) return stored
        return DpiFragmentPreset.entries.firstOrNull { it.id == stored }?.id ?: DpiFragmentPreset.AUTO_ID
    }

    fun savePreset(id: String) {
        val normalized = if (id.trim().lowercase() == DpiFragmentPreset.AUTO_ID) {
            DpiFragmentPreset.AUTO_ID
        } else {
            DpiFragmentPreset.byId(id).id
        }
        preferences.edit().putString(KEY_PRESET, normalized).apply()
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
        /** Stored choice meaning «decide from the carrier» — resolved by [resolve]. */
        const val AUTO_ID = "auto"

        fun byId(id: String?): DpiFragmentPreset = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** The built-in choice per carrier: what usually gets a ClientHello through that ISP. */
        fun forCarrier(carrier: MobileCarrier): DpiFragmentPreset = when (carrier) {
            MobileCarrier.MCI -> MCI
            MobileCarrier.IRANCELL -> IRANCELL
            MobileCarrier.RIGHTEL -> RIGHTEL
            MobileCarrier.TCI -> TCI
            MobileCarrier.OTHER, MobileCarrier.UNKNOWN -> DEFAULT
        }

        /**
         * Final preset for this connect: the owner's panel recommendation beats the built-in
         * carrier table, the built-in table beats nothing, and a manual choice always wins.
         *
         * `storedId` is what the user picked in the app (`auto` = let the app decide).
         * An unknown value is treated as `auto` — the same forgiving path the rest of the
         * settings use, so an old/edited preference can never leave fragmenting broken.
         */
        fun resolve(storedId: String?, carrier: MobileCarrier, tune: PanelTune? = null): DpiFragmentPreset {
            val stored = storedId?.trim()?.lowercase()
            if (stored != null && stored != AUTO_ID && stored.isNotEmpty()) {
                val explicit = entries.firstOrNull { it.id == stored }
                if (explicit != null) return explicit
            }
            val recommended = tune?.presetIdFor(carrier)?.let { id ->
                entries.firstOrNull { it.id == id }
            }
            return recommended ?: forCarrier(carrier)
        }
    }
}
