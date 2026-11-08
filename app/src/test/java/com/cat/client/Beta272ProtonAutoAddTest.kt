package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta272ProtonAutoAddTest {

    @Test
    fun versionIsBeta272() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 350"))
        assertTrue(gradle.contains("1.10.0-beta283"))
    }

    @Test
    fun sessionCookieMeansSignedIn() {
        val session = ProtonFreePlanner.sessionFromCookies(
            "Session-Id=abc; AUTH-uid123=tokentok; other=1",
        )
        assertNotNull(session)
        assertEquals("uid123", session!!.uid)
        assertFalse(ProtonFreePlanner.looksSignedIn(null, "https://account.proton.me/vpn/signup"))
        assertFalse(ProtonFreePlanner.looksSignedIn(null, "https://account.proton.me/vpn/login"))
        assertFalse(ProtonFreePlanner.looksSignedIn(null, "https://account.proton.me/vpn/login", ""))
        assertTrue(
            ProtonFreePlanner.looksSignedIn(
                null,
                "https://account.proton.me/vpn/login",
                "OpenVPN\nCredentials\nYou can use the following credentials to connect",
            ),
        )
        assertTrue(ProtonFreePlanner.pageLooksSignedIn("OpenVPN Credentials You can use the following credentials"))
        assertTrue(ProtonFreePlanner.looksSignedIn("AUTH-uid123=tokentok"))
        assertTrue(ProtonFreePlanner.looksSignedIn(null, "https://account.protonvpn.com/downloads"))
        assertTrue(ProtonFreePlanner.looksSignedIn("", "https://account.protonvpn.com/OpenVPN"))
    }

    @Test
    fun ed25519Pkcs8SeedBecomesWireGuardX25519() {
        val body = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        assertEquals("WOhu+3X6TixBD0bhben2rK4aFwNShlG2m8F2wIi+824=", ProtonFreePlanner.x25519PrivateFromPkcs8Body(body))
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        assertEquals(body, ProtonFreePlanner.pemBody(pem))
    }

    @Test
    fun pickServersKeepsFreeOnlyAndReadsX25519PublicKey() {
        val json = """{"LogicalServers":[
            {"Name":"NL-FREE#1","EntryCountry":"NL","ExitCountry":"NL","City":"Amsterdam","Tier":0,"Status":1,"Load":11,
             "Servers":[{"EntryIP":"1.2.3.4","Status":1,"X25519PublicKey":"abcd="}]},
            {"Name":"DE#10","EntryCountry":"DE","ExitCountry":"DE","City":"Frankfurt","Tier":2,"Status":1,"Load":5,
             "Servers":[{"EntryIP":"5.6.7.8","Status":1,"X25519":"efgh="}]}
        ]}"""
        val all = ProtonFreePlanner.servers(json)
        assertEquals(listOf("NL", "DE"), all.map { it.country })
        assertEquals("abcd=", all.first().x25519)
        val free = ProtonFreePlanner.pickServers(all, maxTier = 0)
        assertEquals(listOf("NL"), free.map { it.country })
        val paid = ProtonFreePlanner.pickServers(all, maxTier = 2)
        assertEquals(setOf("NL", "DE"), paid.map { it.country }.toSet())
    }

    @Test
    fun pullWireGuardFilesBuildsConfFromFakeApi() {
        val seed = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        val http = ProtonFreePlanner.Http { method, url, _, _ ->
            when {
                url.endsWith("/vpn") && method == "GET" -> """{"VPN":{"MaxTier":0}}"""
                url.endsWith("/vpn/logicals") -> """{"LogicalServers":[
                    {"Name":"NL-FREE#1","ExitCountry":"NL","Tier":0,"Status":1,"Load":1,
                     "Servers":[{"EntryIP":"9.9.9.9","Status":1,"X25519":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="}]}
                ]}"""
                url.endsWith("/vpn/v1/certificate/key/EC") ->
                    """{"Code":1000,"PrivateKey":"-----BEGIN PRIVATE KEY-----\n$seed\n-----END PRIVATE KEY-----","PublicKey":"-----BEGIN PUBLIC KEY-----\nPUB\n-----END PUBLIC KEY-----"}"""
                url.endsWith("/vpn/v1/certificate") -> """{"Code":1000}"""
                else -> error(url)
            }
        }
        val session = ProtonFreePlanner.Session("uid", "AUTH-uid=tok")
        val files = ProtonFreePlanner.pullWireGuardFiles(session, http = http)
        assertEquals(1, files.size)
        val conf = files.single().second
        assertTrue(ProtonFreePlanner.looksLikeConf(conf))
        assertTrue(conf.contains("PrivateKey = WOhu+3X6TixBD0bhben2rK4aFwNShlG2m8F2wIi+824="))
        assertTrue(conf.contains("Endpoint = 9.9.9.9:51820"))
        assertTrue(WireGuardConfText.toUris(conf).isNotEmpty())
    }

    @Test
    fun screenDropsTheGuideAndAutoPullsAfterLogin() {
        val src = listOf(
            File("app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt"),
            File("src/main/java/com/cat/client/MainActivityFreeNetworks.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("showProtonWebView(emptySet())"))
        assertTrue(src.contains("pullWireGuardFiles"))
        assertTrue(src.contains("pullFromSession"))
        assertFalse(src.contains("صفحهٔ دانلود WireGuard"))
        assertFalse(src.contains("افزودن فایل‌های گرفته‌شده به Cat"))
        assertFalse(src.contains("Cat Proton — کدام کشورها؟"))
    }
}
