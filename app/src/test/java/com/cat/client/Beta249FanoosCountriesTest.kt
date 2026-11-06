package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** beta249: 🏮 Cat Fanoos names every server by its country and can ask for another country. */
class Beta249FanoosCountriesTest {
    private val answer = """{"country":"IR","ip":"5.6.7.8",
        "servers":[{"country":"Germany","city":"Frankfurt","country_code":"DE","latitude":50.1,"longitude":8.6},
                   {"country":"United States","city":"Ashburn","country_code":"US"},
                   {"country":"Japan","city":"Tokyo","country_code":"JP"},
                   {"country":"Germany","city":"Frankfurt","country_code":"DE"}],
        "outbound_locations":{"de-1":{"country":"Germany","city":"Frankfurt","country_code":"DE"},
                              "us-1":{"country":"United States","city":"Ashburn","country_code":"US"}},
        "options":{"outbounds":[
          {"type":"shadowsocks","tag":"de-1","server":"1.2.3.4","server_port":8388,"method":"chacha20-ietf-poly1305","password":"pw"},
          {"type":"hysteria2","tag":"us-1","server":"h.example.com","server_port":443,"password":"hp","tls":{"enabled":true,"server_name":"h.example.com"}},
          {"type":"shadowsocks","tag":"nowhere","server":"1.2.3.9","server_port":8388,"method":"aes-256-gcm","password":"pw"}
        ]}}"""

    private fun nameOf(link: String) = URLDecoder.decode(link.substringAfter('#'), "UTF-8")

    @Test fun linksCarryCountryAndCity() {
        val tr = LanternPlanner.translate(answer)
        assertEquals(3, tr.links.size)
        assertEquals("🏮 Cat Fanoos 🇩🇪 Germany Frankfurt #1", nameOf(tr.links[0]))
        assertEquals("🏮 Cat Fanoos 🇺🇸 United States Ashburn #2", nameOf(tr.links[1]))
        assertEquals("🏮 Cat Fanoos #3", nameOf(tr.links[2]))
        assertEquals(listOf("DE", "US", null), tr.linkLocations.map { it?.code })
        assertEquals("IR", tr.clientCountry)
        // Offered list is de-duplicated and keeps the service's order.
        assertEquals(listOf("DE", "US", "JP"), tr.offered.map { it.code })
        assertEquals("🇩🇪 Germany ×1 · 🇺🇸 United States ×1 · ? ×1", tr.countrySummary("?"))
    }

    @Test fun preferredLocationIsCopiedFromTheOfferedList() {
        val jp = LanternPlanner.translate(answer).offered.first { it.code == "JP" }
        val body = JSONObject(LanternPlanner.configBody("dev", "1", "t", jp))
        val pl = body.optJSONObject("preferred_location")!!
        assertEquals("Japan", pl.optString("country"))
        assertEquals("Tokyo", pl.optString("city"))
        assertEquals("JP", pl.optString("country_code"))
        assertNull(JSONObject(LanternPlanner.configBody("dev", "1", "t", null)).optJSONObject("preferred_location"))
    }

    @Test fun flagsAndHonouredCheck() {
        assertEquals("🇩🇪", LanternPlanner.flagOf("de"))
        assertEquals("", LanternPlanner.flagOf("X1"))
        val tr = LanternPlanner.translate(answer)
        val jp = LanternPlanner.Location("Japan", "Tokyo", "JP")
        assertEquals(false, LanternPlanner.honoured(jp, tr.linkLocations))
        assertEquals(true, LanternPlanner.honoured(LanternPlanner.Location("Germany", "", "DE"), tr.linkLocations))
        assertNull(LanternPlanner.honoured(null, tr.linkLocations))
    }

    @Test fun mergeKeepsOtherCountries() {
        val old = listOf("ss://a#us" to "US", "ss://b#de" to "DE", "ss://c" to "")
        val fresh = listOf("ss://d#de" to "DE", "ss://e#jp" to "JP")
        val merged = LanternPlanner.merge(old, fresh)
        assertEquals(listOf("ss://d#de", "ss://e#jp", "ss://a#us"), merged.map { it.first })
        // Unknown-location answer replaces everything; empty answer keeps the old list.
        assertEquals(listOf("ss://x"), LanternPlanner.merge(old, listOf("ss://x" to "")).map { it.first })
        assertEquals(old, LanternPlanner.merge(old, emptyList()))
        assertTrue(LanternPlanner.merge(old, (1..50).map { "ss://$it" to "NL" }).size <= 40)
    }

    @Test fun storePersistsOfferedAndLinks() {
        val ctx = android.content.Context()
        val store = FreeNetworkStore(ctx)
        assertTrue(store.lanternOfferedList().isEmpty())
        store.lanternOffered = org.json.JSONArray(LanternPlanner.translate(answer).offered.map { it.toJson() }).toString()
        assertEquals(listOf("Germany", "United States", "Japan"), store.lanternOfferedList().map { it.country })
        store.lanternLinks = listOf("ss://a" to "US")
        assertEquals(listOf("ss://a" to "US"), store.lanternLinks)
        assertFalse(store.lanternLinks.isEmpty())
    }
}
