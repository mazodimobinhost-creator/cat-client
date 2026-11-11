package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta300 — home shows the live hops when connected: phone → protocol → server → exit. */
class Beta300HomeConnectionPathTest {

    @Test
    fun versionIsBeta300() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 368"))
        assertTrue(gradle.contains("1.10.0-beta301"))
    }

    @Test
    fun snapshotKeepsExactPeerPortAndDropsLoopback() {
        val wg = ConnectionProfile(
            tag = "Cat Warp",
            type = "wireguard",
            server = "162.159.192.1",
            port = 2408,
            transport = "udp",
            validationHost = "162.159.192.1",
        )
        assertEquals("162.159.192.1:2408", ConnectionPath.peerLabel(wg))
        val v6 = ConnectionProfile(
            tag = "v6",
            type = "wireguard",
            server = "2606:4700:d0::a29f:c001",
            port = 2408,
            transport = "udp",
            validationHost = "2606:4700:d0::a29f:c001",
        )
        assertEquals("[2606:4700:d0::a29f:c001]:2408", ConnectionPath.peerLabel(v6))
        val local = ConnectionProfile(
            tag = "socks",
            type = "socks",
            server = "127.0.0.1",
            port = 10808,
            transport = "tcp",
            validationHost = "127.0.0.1",
        )
        assertNull(ConnectionPath.peerLabel(local))
        assertNull(ConnectionPath.publicIp("127.0.0.1"))
        assertNull(ConnectionPath.publicIp("::1"))
        assertEquals("8.8.8.8", ConnectionPath.publicIp("8.8.8.8"))

        val geo = IpGeolocation.Info(
            ip = "1.1.1.1",
            countryCode = "DE",
            countryName = "Germany",
            city = "Frankfurt",
            isp = null,
            realIp = "5.6.7.8",
            realCountryCode = "IR",
            realCountryName = "Iran",
        )
        val snap = ConnectionPath.snapshot(fa = true, profile = wg, geo = geo, chainHops = 1, tag = "Cat Warp")
        assertTrue(snap.caption, snap.caption.contains("وایرگارد") || snap.caption.contains("Cat Warp"))
        assertTrue(snap.caption, snap.caption.contains("Iran"))
        assertTrue(snap.caption, snap.caption.contains("Germany"))
        assertTrue(snap.caption, snap.caption.contains("1.1.1.1"))
        assertTrue(snap.hops.any { it.detail == "162.159.192.1:2408" })
        assertFalse(snap.hops.any { it.detail.contains("127.0.0.1") })
        assertFalse(snap.hops.any { it.detail == "5.6.7.8:80" })
        val masque = ConnectionPath.protocolLabel("masque-h2", "Cat Warp", fa = false)
        assertEquals("Cat Warp", masque)
        assertEquals("Cat GP", ConnectionPath.protocolLabel("http", "🌐 Cat GP", fa = true))
    }

    @Test
    fun homeShowsPathWhenConnectedWithoutExtraButton() {
        val dash = src("MainActivityDashboard.kt")
        assertTrue(dash.contains("connectionPathWebView = ConnectionPathWebView"))
        assertTrue(dash.contains("addView(connectionPathWebView") || dash.contains("pixelCard?.addView(connectionPathWebView"))
        assertFalse(dash.contains("Protocol Explorer") || dash.contains("protocol-explorer"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("fun renderConnectionPath"))
        assertTrue(main.contains("ConnectionPath.toPathJson"))
        assertTrue(main.contains("CatPath") || src("ConnectionPathWebView.kt").contains("CatPath.load"))
        assertTrue(main.contains("renderConnectionPath(state)"))
        assertFalse(src("MainActivityGuides.kt").contains("ConnectionPathWebView"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
