package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta276 — Proton OpenVPN/Credentials page means signed in; Cat fetches WireGuard itself. */
class Beta276ProtonLoginAddTest {

    @Test
    fun versionIsBeta276() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 368"))
        assertTrue(gradle.contains("1.10.0-beta301"))
    }

    @Test
    fun credentialsPageIsSignedInAndInPageBlobBecomesConf() {
        assertTrue(ProtonFreePlanner.looksSignedIn(null, "https://account.protonvpn.com/downloads"))
        val seed = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        val blob = org.json.JSONObject()
            .put("vpn", org.json.JSONObject().put("VPN", org.json.JSONObject().put("MaxTier", 0)))
            .put(
                "logicals",
                org.json.JSONObject().put(
                    "LogicalServers",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("Name", "NL-FREE#1")
                            .put("ExitCountry", "NL")
                            .put("Tier", 0)
                            .put("Status", 1)
                            .put("Load", 1)
                            .put(
                                "Servers",
                                org.json.JSONArray().put(
                                    org.json.JSONObject()
                                        .put("EntryIP", "9.9.9.9")
                                        .put("Status", 1)
                                        .put("X25519", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
                                ),
                            ),
                    ),
                ),
            )
            .put(
                "key",
                org.json.JSONObject()
                    .put("Code", 1000)
                    .put("PrivateKey", "-----BEGIN PRIVATE KEY-----\n$seed\n-----END PRIVATE KEY-----")
                    .put("PublicKey", "-----BEGIN PUBLIC KEY-----\nPUB\n-----END PUBLIC KEY-----"),
            )
            .toString()
        val files = ProtonFreePlanner.filesFromInPage(blob)
        assertNotNull(files)
        assertEquals(1, files!!.size)
        val conf = files.single().second
        assertTrue(ProtonFreePlanner.looksLikeConf(conf))
        assertTrue(conf.contains("PrivateKey = WOhu+3X6TixBD0bhben2rK4aFwNShlG2m8F2wIi+824="))
        assertTrue(conf.contains("Endpoint = 9.9.9.9:51820"))
        assertTrue(WireGuardConfText.toUris(conf).isNotEmpty())
    }

    @Test
    fun hubFetchesFromSignedInPageNotJustAuthCookie() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("inpagePullJs"))
        assertTrue(hub.contains("filesFromInPage"))
        assertTrue(hub.contains("looksSignedIn"))
        assertTrue(hub.contains("setAcceptThirdPartyCookies"))
        assertTrue(hub.contains("kickInPage"))
        val engine = src("ProtonFreeEngine.kt")
        assertTrue(engine.contains("credential"))
        assertTrue(engine.contains("openvpn"))
        assertTrue(engine.contains("INPAGE_PULL_JS"))
        assertFalsePlus(engine)
    }

    private fun assertFalsePlus(engine: String) {
        org.junit.Assert.assertFalse(engine.contains("fakePlus"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
