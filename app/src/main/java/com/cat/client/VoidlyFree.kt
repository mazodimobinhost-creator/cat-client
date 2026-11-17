package com.cat.client

import org.json.JSONObject

/**
 * 🛡️ Cat Voidly — pick a live region, generate on their page, Cat brings the `.conf` in.
 *
 * Public API is availability only (no issue). Keys stay on the device. UDP only.
 */
internal object VoidlyPlanner {
    const val PAGE = "https://voidly.ai/vpn"
    const val AVAILABILITY = "https://api.voidly.ai/v1/vpn/availability"
    const val NAME = "🛡️ Cat Voidly"

    data class Region(val id: String, val city: String, val country: String, val status: String) {
        val ok: Boolean get() = status.equals("available", ignoreCase = true)
        fun countryFa(): String = when (country) {
            "Germany" -> "آلمان"
            "United States" -> "آمریکا"
            "India" -> "هند"
            "Japan" -> "ژاپن"
            "Singapore" -> "سنگاپور"
            "United Kingdom" -> "بریتانیا"
            "Canada" -> "کانادا"
            "Mexico" -> "مکزیک"
            "Australia" -> "استرالیا"
            "Netherlands" -> "هلند"
            "Brazil" -> "برزیل"
            "Spain" -> "اسپانیا"
            "South Korea" -> "کرهٔ جنوبی"
            "South Africa" -> "آفریقای جنوبی"
            else -> country
        }
        fun label(fa: Boolean): String {
            val land = if (fa) countryFa() else country
            return "$city · $land"
        }
    }

    fun looksLikeConf(text: String): Boolean = WireGuardConfText.looksLikeConf(text)

    fun parseAvailability(json: String): List<Region> {
        val a = JSONObject(json).optJSONArray("regions") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val city = o.optString("city").trim()
            if (id.isEmpty() || city.isEmpty()) return@mapNotNull null
            Region(id, city, o.optString("country").trim(), o.optString("status").trim())
        }
    }

    fun fetchRegions(): List<Region> {
        val body = FreeNetHttp.request(AVAILABILITY, timeoutMs = 8_000).body
        return parseAvailability(body).sortedWith(compareBy({ !it.ok }, { it.country }, { it.city }))
    }

    fun rowLabel(text: String, region: Region? = null): String {
        val endpoint = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && '=' in it }
            ?.substringAfter('=')
            ?.trim()
            ?.substringBefore(' ')
            .orEmpty()
        val where = region?.label(false)?.takeIf { it.isNotBlank() }
        return when {
            where != null && endpoint.isNotBlank() -> "Voidly $where · $endpoint"
            where != null -> "Voidly $where"
            endpoint.isNotBlank() -> "Voidly $endpoint"
            else -> "Voidly"
        }
    }

    fun subscriptionText(links: List<String>): String =
        "# 🛡️ Cat Voidly\n" + links.distinct().joinToString("\n")

    /** Pulls a WireGuard conf out of the generator page (textarea / pre / body). */
    const val SCAN_JS =
        "(function(){try{" +
            "function ok(t){return t&&t.indexOf('[Interface]')>=0&&t.indexOf('PrivateKey')>=0&&t.indexOf('[Peer]')>=0;}" +
            "var nodes=document.querySelectorAll('textarea,pre,code');" +
            "for(var i=0;i<nodes.length;i++){var t=nodes[i].value||nodes[i].innerText||'';if(ok(t))return t;}" +
            "var body=(document.body&&document.body.innerText)||'';" +
            "if(ok(body)){var i=body.indexOf('[Interface]');return body.substring(i,i+8000);}" +
            "return '';" +
            "}catch(e){return '';}})()"
}
