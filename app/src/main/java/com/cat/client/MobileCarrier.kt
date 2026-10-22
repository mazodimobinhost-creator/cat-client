package com.cat.client

import android.content.Context
import android.telephony.TelephonyManager

/**
 * The mobile carrier behind the current connection, as far as Android is willing to say.
 *
 * Why this exists: Iranian carriers filter the TLS ClientHello differently — the split
 * position that gets a handshake through MCI is not the one that works on Irancell — so
 * ByeDPI needs a different preset per ISP. Until now the user had to guess which chip to
 * tap. This makes the choice automatic and visible.
 *
 * Detection is best-effort and never blocks: a VPN transport, Wi-Fi or a dual-SIM phone can
 * answer «unknown», the raw MCC/MNC is kept for the UI, and the manual chips stay available.
 * `TelephonyManager.networkOperator`/`simOperator` need no permission (they are the operator
 * codes, not the subscriber identity).
 */
enum class MobileCarrier(val id: String) {
    MCI("mci"),
    IRANCELL("irancell"),
    RIGHTEL("rightel"),
    TCI("tci"),
    OTHER("other"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Iran's mobile country code; the MNC below it names the carrier. */
        private const val IRAN_MCC = "432"

        /**
         * Iran's MCC is 432; the MNCs below are the published operator codes. Anything
         * outside that table is [OTHER] — deliberately not guessed, because a wrong
         * preset is worse than the neutral one.
         */
        fun fromOperatorCode(code: String?): MobileCarrier {
            val digits = code.orEmpty().filter(Char::isDigit)
            if (digits.length < 5) return UNKNOWN
            if (!digits.startsWith(IRAN_MCC)) return OTHER
            return when (digits.substring(3)) {
                "11", "19" -> MCI          // Hamrah-e Aval (MCI)
                "35", "70" -> IRANCELL     // MTN Irancell
                "20" -> RIGHTEL
                "32", "14" -> TCI          // Telecom Company of Iran / مخابرات
                else -> OTHER
            }
        }
    }

}

object CarrierDetector {
    /**
     * Prefer the *network* the phone is attached to (that is the operator whose DPI is in
     * the path right now); fall back to the SIM. Cached in memory only — a carrier change
     * (roaming, SIM swap) must be picked up on the next read, not persisted.
     */
    @Volatile
    private var cached: MobileCarrier? = null

    fun detect(context: Context, refresh: Boolean = false): MobileCarrier {
        cached?.takeIf { !refresh }?.let { return it }
        val detected = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return@runCatching MobileCarrier.UNKNOWN
            val network = tm.networkOperator?.takeIf { it.isNotBlank() }
            val sim = tm.simOperator?.takeIf { it.isNotBlank() }
            MobileCarrier.fromOperatorCode(network ?: sim)
        }.getOrDefault(MobileCarrier.UNKNOWN)
        cached = detected
        return detected
    }

    /** The raw `MCC+MNC` (e.g. `43235`) for the UI and for logs — «unknown» is not enough to debug. */
    fun rawOperatorCode(context: Context): String = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperator?.takeIf { it.isNotBlank() } ?: tm?.simOperator.orEmpty()
    }.getOrDefault("")

    fun reset() {
        cached = null
    }
}
