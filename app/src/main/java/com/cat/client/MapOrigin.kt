package com.cat.client

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Where the user really is, for the Map page, when no IP lookup has answered yet.
 *
 * The IP services the map normally asks (ipify, ipwho.is, Cloudflare trace…) are often slow or
 * filtered on Iranian lines, and the map then stayed empty («not known yet») for good. The
 * country of the mobile network / SIM is known offline, needs no permission and is not changed by
 * any VPN, so it is a safe origin until a real lookup answers. It is shown as such («from your
 * mobile network») and never stored as the recorded origin.
 */
object MapOrigin {
    /** First valid ISO-3166 alpha-2 code: the attached network first (roaming-aware), then the SIM. */
    fun telephonyCountry(networkIso: String?, simIso: String?): String? =
        listOf(networkIso, simIso)
            .map { it.orEmpty().trim().uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    fun telephonyCountry(context: Context): String? = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        telephonyCountry(tm.networkCountryIso, tm.simCountryIso)
    }.getOrNull()

    /** Localised country name («ایران» / «Iran»); the code itself when the platform has no name. */
    fun countryName(code: String, locale: Locale): String =
        runCatching { Locale("", code).getDisplayCountry(locale) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code

    /** A lookup is usable as an origin only with a real two-letter country code. */
    fun usableCode(code: String?): String? =
        code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
}
