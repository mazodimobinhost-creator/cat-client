package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * beta254 — 🟢 Cat Proton: the service's own **free** plan (unlimited data, a handful of countries),
 * used the same way Cat Fox uses a free Firefox account: the user creates / signs in on the official
 * page (human check stays theirs), Cat picks up the WireGuard `.conf` files that page issues and
 * turns them into ordinary `wireguard://` rows grouped by country.
 *
 * Not a Plus crack: a free account only ever gets the countries Proton marks Tier 0; a paid account
 * the user already bought can download every country they paid for. No fake tokens, no identity rotation.
 *
 * Protocol from Proton's published API shape (`LogicalServers`, `Tier`, `X25519`) and the official
 * downloads page; independent Kotlin, no copy of Proton's app.
 */
internal object ProtonFreePlanner {
    const val SIGNUP = "https://account.proton.me/vpn/signup"
    const val LOGIN = "https://account.proton.me/vpn/login"
    const val DOWNLOADS = "https://account.protonvpn.com/downloads"

    data class Country(val code: String, val flag: String, val nameFa: String, val nameEn: String) {
        fun label(fa: Boolean) = flag + " " + (if (fa) nameFa else nameEn)
    }

    /**
     * Countries Proton's free plan has publicly offered (PCMag / Proton, 2025–2026). The live
     * `logicals` list (when the signed-in page gives it) is the authority; this is the picker
     * before the first fetch.
     */
    val FREE_COUNTRIES = listOf(
        Country("NL", "🇳🇱", "هلند", "Netherlands"),
        Country("JP", "🇯🇵", "ژاپن", "Japan"),
        Country("US", "🇺🇸", "آمریکا", "United States"),
        Country("CA", "🇨🇦", "کانادا", "Canada"),
        Country("RO", "🇷🇴", "رومانی", "Romania"),
        Country("PL", "🇵🇱", "لهستان", "Poland"),
        Country("NO", "🇳🇴", "نروژ", "Norway"),
        Country("SG", "🇸🇬", "سنگاپور", "Singapore"),
    )

    fun flagOf(code: String): String = LanternPlanner.flagOf(code)

    data class Server(
        val name: String,
        val country: String,
        val city: String,
        val tier: Int,
        val entryIp: String,
        val x25519: String,
        val load: Int,
    ) {
        val free: Boolean get() = tier == 0
    }

    /** `GET /vpn/logicals` (or `/api/vpn/logicals`) body → online servers with an X25519 key. */
    fun servers(json: String): List<Server> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("LogicalServers") ?: JSONArray()
        val out = ArrayList<Server>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("Status", 1) != 1) continue
            val country = o.optString("ExitCountry").ifBlank { o.optString("EntryCountry") }.trim().uppercase(Locale.US)
            if (country.length != 2) continue
            val name = o.optString("Name").ifBlank { country }
            val city = o.optString("City")
            val tier = o.optInt("Tier", 2)
            val load = o.optInt("Load", 0)
            val nodes = o.optJSONArray("Servers") ?: JSONArray()
            for (j in 0 until nodes.length()) {
                val n = nodes.optJSONObject(j) ?: continue
                if (n.optInt("Status", 1) != 1) continue
                val ip = n.optString("EntryIP").ifBlank { n.optString("ExitIP") }.trim()
                val key = n.optString("X25519").trim()
                if (ip.isBlank() || key.isBlank()) continue
                out += Server(name, country, city, tier, ip, key, load)
                break // one node per logical is enough for a row
            }
        }
        out
    }.getOrDefault(emptyList())

    fun freeServers(all: List<Server>): List<Server> = all.filter { it.free }

    fun countriesOf(servers: List<Server>): List<String> =
        servers.map { it.country }.filter { it.length == 2 }.distinct()

    /** Country code from a Proton `.conf` (filename, `# NL-FREE#1`, `# Netherlands`, …). */
    fun countryOfConf(text: String, filename: String = ""): String {
        val hay = (filename + "\n" + text)
        Regex("""(?i)\b([A-Z]{2})-FREE(?:#\d+)?\b""").find(hay)?.groupValues?.get(1)?.uppercase(Locale.US)?.let { return it }
        Regex("""(?i)\b([A-Z]{2})#\d+\b""").find(hay)?.groupValues?.get(1)?.uppercase(Locale.US)?.let { return it }
        val upper = hay.uppercase(Locale.US)
        FREE_COUNTRIES.firstOrNull { c ->
            Regex("""\b${c.code}\b""").containsMatchIn(upper) ||
                upper.contains(c.nameEn.uppercase(Locale.US))
        }?.let { return it.code }
        return ""
    }

    fun looksLikeConf(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return "[interface]" in lower && "[peer]" in lower && "privatekey" in lower && "publickey" in lower && "endpoint" in lower
    }

    /** Fragment shown on each `wireguard://` row (the URI itself is built with [WireGuardConfText]). */
    fun rowLabel(text: String, filename: String = "", fa: Boolean = true): String {
        val code = countryOfConf(text, filename)
        val country = FREE_COUNTRIES.firstOrNull { it.code == code }
        return when {
            country != null -> country.flag + " " + (if (fa) country.nameFa else country.nameEn)
            code.length == 2 -> flagOf(code) + " " + code
            else -> filename.ifBlank { "Proton" }
        }
    }

    fun subscriptionText(links: List<String>): String =
        "# 🟢 Cat Proton\n" + links.distinct().joinToString("\n")

    fun maxTier(vpnInfoJson: String): Int? = runCatching {
        val o = JSONObject(vpnInfoJson)
        val vpn = o.optJSONObject("VPN") ?: o
        vpn.optInt("MaxTier", -1).takeIf { it >= 0 }
    }.getOrNull()

    /** JS run inside the official Proton page: never fills forms, never solves the human check. */
    const val SCAN_JS =
        "(function(){try{var t=[document.body?document.body.innerText:'',document.title||''];" +
            "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++)t.push(as[i].href||'');" +
            "return t.join('\\n').slice(0,20000);}catch(e){return '';}})()"
}
