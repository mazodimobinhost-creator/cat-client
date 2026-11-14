package com.cat.client

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta301 — Home WebView loads assets/connection_path.html with real config fields only. */
class Beta301ConnectionPathHtmlTest {

    @Test
    fun versionIsBeta301() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 374"))
        assertTrue(gradle.contains("1.10.0-beta307"))
    }

    @Test
    fun toPathJsonFromOutboundOmitsSecrets() {
        val outbound = JSONObject()
            .put("name", "panel-ws")
            .put("type", "vless")
            .put("server", "cdn.example.com")
            .put("port", 443)
            .put("network", "ws")
            .put("tls", true)
            .put("servername", "cdn.example.com")
            .put("uuid", "11111111-1111-1111-1111-111111111111")
            .put("password", "super-secret")
            .put("ws-opts", JSONObject().put("path", "/ws"))
            .toString()
        val profile = ConnectionProfile(
            tag = "panel-ws",
            type = "vless",
            server = "cdn.example.com",
            port = 443,
            transport = "tcp",
            validationHost = "cdn.example.com",
            outboundJson = outbound,
            shareLink = "vless://11111111-1111-1111-1111-111111111111@cdn.example.com:443" +
                "?type=ws&security=tls&sni=cdn.example.com&path=/ws#panel-ws",
        )
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
        val json = ConnectionPath.toPathJson(profile, geo, "panel-ws")
        val raw = json.toString()
        assertFalse(raw.contains("11111111-1111-1111-1111-111111111111"))
        assertFalse(raw.contains("super-secret"))
        assertFalse(raw.contains("uuid"))
        assertFalse(raw.contains("password"))
        val realityJson = ConnectionPath.toPathJson(
            profile.copy(
                outboundJson = JSONObject(outbound)
                    .put("reality-opts", JSONObject().put("public-key", "SECRETKEY").put("short-id", "abcd"))
                    .toString(),
            ),
            geo,
            "panel-ws",
        )
        assertEquals("reality", realityJson.getString("security"))
        assertFalse(realityJson.toString().contains("SECRETKEY"))
        assertFalse(realityJson.toString().contains("public-key"))
        assertFalse(realityJson.toString().contains("short-id"))
        assertEquals("vless", json.getString("protocol"))
        assertEquals("ws", json.getString("transport"))
        assertEquals("tls", json.getString("security"))
        assertEquals("cdn.example.com", json.getString("host"))
        assertEquals(443, json.getInt("port"))
        assertEquals("cdn.example.com", json.getString("sni"))
        assertEquals("/ws", json.getString("path"))
        assertEquals("5.6.7.8", json.getString("localIp"))
        assertFalse(json.has("ip"))
        val keys = json.keys().asSequence().toSet()
        assertTrue(keys.containsAll(setOf("name", "protocol", "transport", "security", "host", "port", "sni", "path", "cdn")))
        assertTrue(ConnectionPath.allowedKeys().containsAll(keys))
    }

    @Test
    fun toPathJsonFromShareLinkAndWorkersCdn() {
        val profile = ConnectionProfile(
            tag = "cat-worker",
            type = "vless",
            server = "name.workers.dev",
            port = 443,
            transport = "ws",
            validationHost = "name.workers.dev",
            shareLink = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@name.workers.dev:443" +
                "?type=ws&security=tls&sni=name.workers.dev&path=/path#cat-worker",
        )
        val json = ConnectionPath.toPathJson(profile, geo = null, tag = "cat-worker")
        assertEquals("ws", json.getString("transport"))
        assertEquals("tls", json.getString("security"))
        assertTrue(json.getBoolean("cdn"))
        assertEquals("/path", json.getString("path"))
        assertFalse(json.toString().contains("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertEquals("tls", ConnectionPath.failStep("TLS handshake failed")?.let { it })
        assertEquals("dns", ConnectionPath.failStep("dns resolve timeout"))
        assertEquals("tcp", ConnectionPath.failStep("ECONNREFUSED"))
        assertEquals("ws", ConnectionPath.failStep("websocket upgrade failed"))
    }

    @Test
    fun htmlAssetExposesCatPathAndHomeUsesWebView() {
        val html = listOf(
            File("app/src/main/assets/connection_path.html"),
            File("src/main/assets/connection_path.html"),
        ).first { it.isFile }.readText()
        assertTrue(html.contains("window.CatPath={load:"))
        assertTrue(html.contains("function setState"))
        assertTrue(html.contains("?app=1") || html.contains("app=1"))
        assertTrue(html.contains("#171a26"))
        assertFalse(html.contains("rocket-loader"))
        assertFalse(html.contains("fonts.googleapis"))
        assertFalse(html.contains("addJavascriptInterface("))
        val wv = src("ConnectionPathWebView.kt")
        assertTrue(wv.contains("file:///android_asset/connection_path.html?app=1"))
        assertTrue(wv.contains("javaScriptEnabled = true"))
        assertTrue(wv.contains("domStorageEnabled = false"))
        assertTrue(wv.contains("blockNetworkLoads = true"))
        assertTrue(wv.contains("allowFileAccessFromFileURLs = false"))
        assertTrue(wv.contains("Color.TRANSPARENT"))
        assertFalse(wv.contains("addJavascriptInterface("))
        val dash = src("MainActivityDashboard.kt")
        assertTrue(dash.contains("ConnectionPathView"))
        assertTrue(dash.contains("ConnectionPathWebView"))
        assertTrue(dash.contains("dp(16)"))
        assertTrue(wv.contains("CatPath.setState"))
        assertTrue(src("MainActivity.kt").contains("connectActionButton.visibility = if (live) View.VISIBLE"))
        assertTrue(src("MainActivity.kt").contains("setPathDiagramVisible(true)"))
    }

    @Test
    fun applyEdgeReplacesLoopbackForCatGp() {
        val o = org.json.JSONObject()
            .put("name", "Cat GP")
            .put("protocol", "http")
            .put("host", "127.0.0.1")
            .put("port", 10871)
        ConnectionPath.applyEdge(
            o,
            host = "www.google.com",
            ip = "203.0.113.10",
            sni = "www.google.com",
            port = 443,
            cdn = true,
            protocol = "https",
            transport = "h2",
            security = "tls",
        )
        assertFalse(o.toString().contains("127.0.0.1"))
        assertFalse(o.toString().contains("10871"))
        assertEquals("www.google.com", o.getString("host"))
        assertEquals("203.0.113.10", o.getString("ip"))
        assertEquals(443, o.getInt("port"))
        assertTrue(o.getBoolean("cdn"))
        assertEquals("https", o.getString("protocol"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
