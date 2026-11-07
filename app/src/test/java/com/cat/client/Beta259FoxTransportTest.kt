package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta259: Fox protocols HTTP/1.1·HTTP/2·HTTP/3 and every Mozilla country is offered. */
class Beta259FoxTransportTest {

    @Test
    fun protocolNormalizesAndAlpnMatchesTheChoice() {
        assertEquals(FoxTransport.H1, FoxTransport.normalize("HTTP/1.1"))
        assertEquals(FoxTransport.H3, FoxTransport.normalize("quic"))
        assertEquals(FoxTransport.AUTO, FoxTransport.normalize(""))
        assertTrue(FoxTransport.forceH1("h1"))
        assertFalse(FoxTransport.forceH1("h2"))
        assertEquals(listOf("http/1.1"), FoxTransport.alpn("h1").toList())
        assertEquals(listOf("h2"), FoxTransport.alpn("h2").toList())
        assertEquals(listOf("h2", "http/1.1"), FoxTransport.alpn("auto").toList())
        assertEquals("HTTP/2", FoxTransport.wireName("h3"))
    }

    @Test
    fun mozillaRecordKeepsEveryCityServerAndConnectHost() {
        val rec = JSONObject(
            """
            {"code":"US","name":"United States","cities":[
              {"name":"New York","servers":[
                {"hostname":"us.m1.fastly-masque.net","port":2499,
                 "protocols":[
                   {"name":"connect","host":"us.m1.fastly-masque.net","port":2499},
                   {"name":"masque","host":"us-masque.m1.fastly-masque.net","port":443,"templateString":"https://us-masque.m1.fastly-masque.net/{target_host}/{target_port}"}
                 ]},
                {"hostname":"iad.m1.fastly-masque.net","port":2499}
              ]},
              {"name":"Los Angeles","servers":[
                {"hostname":"lax.m1.fastly-masque.net","port":2499}
              ]}
            ]}
            """.trimIndent(),
        )
        val nodes = FoxTransport.nodesFromRecord(rec)
        assertEquals(
            listOf(
                "us.m1.fastly-masque.net:2499",
                "us-masque.m1.fastly-masque.net:443",
                "iad.m1.fastly-masque.net:2499",
                "lax.m1.fastly-masque.net:2499",
            ),
            nodes.map { it.endpoint },
        )
        assertTrue(nodes.any { it.masqueTemplate.contains("{target_host}") })
        assertEquals(3, FoxTransport.extraEndpointsJson(nodes).length())
    }

    @Test
    fun builtinOffersEveryCountryAndNamesCoverNewCodes() {
        val all = FirefoxVpnEngine.BUILTIN_COUNTRIES
        assertTrue(all.size >= 34)
        assertTrue(all.none { it.locked })
        assertTrue(all.any { it.code == "NL" })
        assertTrue(all.any { it.code == "CO" })
        assertTrue(all.any { it.code == "KR" })
        assertEquals("لهستان", FoxCountryNames.of("PL").fa)
        assertEquals("🇨🇭", FoxCountryNames.of("CH").flag)
        assertEquals("⭐", FoxCountryNames.flagEmoji("REC"))
    }

    @Test
    fun hubWiresProtocolPickerAndStopsLockingCountries() {
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("showFoxTransportDialog"))
        assertTrue(fox.contains("FoxTransport.MODES"))
        assertFalse(fox.contains("قفل‌ها خودکار میرن رو پیشنهادی"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("FoxTransport.alpn(activeTransport)"))
        assertTrue(engine.contains("altsForCode"))
        assertTrue(engine.contains("KEY_TRANSPORT"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
