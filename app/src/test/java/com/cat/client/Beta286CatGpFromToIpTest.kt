package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta286 — Cat GP home shows from/to countries + exit IP via GAS, not MITM :10871. */
class Beta286CatGpFromToIpTest {

    @Test
    fun versionIsBeta286() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 366"))
        assertTrue(gradle.contains("1.10.0-beta299"))
    }

    @Test
    fun parseTraceReadsCloudflareAndIpify() {
        val body = """
            fl=123f
            h=www.cloudflare.com
            ip=104.21.12.34
            ts=1
            visit_scheme=https
            colo=FRA
            loc=DE
            tls=TLSv1.3
            warp=off
        """.trimIndent()
        val t = CatGpScripts.parseTrace(body)
        assertNotNull(t)
        assertEquals("104.21.12.34", t!!.ip)
        assertEquals("DE", t.countryCode)
        assertEquals("FRA", t.colo)
        val plain = CatGpScripts.parseTrace("8.8.8.8\n")
        assertNotNull(plain)
        assertEquals("8.8.8.8", plain!!.ip)
        assertEquals("", plain.countryCode)
    }

    @Test
    fun routeLineIsFromToIpWithoutRtlArrow() {
        val hop = "https://raspy-sky-8be5.giciza3742.workers.dev/"
        assertEquals(
            "Cat GP · گوگل+کلادفلر · در حال خواندن IP",
            CatGpScripts.routeLine(hop, hopOn = true, fa = true),
        )
        assertEquals(
            "Cat GP · گوگل+کلادفلر · از ایران · به آلمان · \u2066104.21.12.34\u2069",
            CatGpScripts.routeLine(hop, hopOn = true, fa = true, "ایران", "آلمان", "104.21.12.34"),
        )
        assertEquals(
            "Cat GP · Google+Cloudflare · from Iran · to Germany · \u2066104.21.12.34\u2069",
            CatGpScripts.routeLine(hop, hopOn = true, fa = false, "Iran", "Germany", "104.21.12.34"),
        )
        val googleOnly = CatGpScripts.routeLine("", hopOn = true, fa = true, "ایران", "آمریکا", "142.250.1.1")
        assertTrue(googleOnly.contains("فقط گوگل اسکریپت"))
        assertTrue(googleOnly.contains("از ایران"))
        assertTrue(googleOnly.contains("به آمریکا"))
        assertTrue(googleOnly.contains("142.250.1.1"))
        assertFalse(CatGpScripts.routeLine(hop, hopOn = true, fa = true).contains("→"))
        assertFalse(CatGpScripts.routeLine(hop, hopOn = true, fa = true).contains("گوگل →"))
        assertTrue(CatGpEngine.isActiveTag("🐱 Cat · Auto · Cat GP · 🌐"))
        assertFalse(CatGpEngine.isActiveTag("🐱 Cat · Germany · Warp"))
    }

    @Test
    fun homeReadsExitThroughGasNotMitm() {
        val ui = src("MainActivity.kt")
        assertTrue(ui.contains("CatGpEngine.exitWhere"))
        assertTrue(ui.contains("catGpLiveInfo"))
        assertTrue(ui.contains("catGpLocationLine"))
        assertFalse(ui.contains("CatGpEngine.exitProxy()"))
        val engine = src("CatGpEngine.kt")
        assertTrue(engine.contains("fun exitWhere"))
        assertTrue(engine.contains("cdn-cgi/trace"))
        assertTrue(engine.contains("api.ipify.org"))
        val geo = src("IpGeolocation.kt")
        assertTrue(geo.contains("fun physical"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
