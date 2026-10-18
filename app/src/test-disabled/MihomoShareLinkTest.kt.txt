package com.whitedns.vpn

import com.cat.client.MihomoConfigParser
import com.cat.client.MihomoShareLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class MihomoShareLinkTest {
    private fun query(link: String): Map<String, String> =
        link.substringAfter('?').substringBefore('#').split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }

    @Test
    fun catPanelVlessOverWebSocketBecomesV2rayNgLink() {
        val yaml = """
            proxies:
              - name: '🐱 Cat | 104.16.1.1 [443]'
                type: vless
                server: 104.16.1.1
                port: 443
                uuid: 42fff517-e307-47a5-8521-809bdf020c6f
                udp: true
                tls: true
                servername: panel.example.workers.dev
                client-fingerprint: chrome
                network: ws
                ws-opts:
                  path: '/vl'
                  headers:
                    Host: panel.example.workers.dev
                  max-early-data: 2048
                  early-data-header-name: Sec-WebSocket-Protocol
        """.trimIndent()

        val profile = MihomoConfigParser.parse(yaml).catalog.profiles.single()
        val link = requireNotNull(profile.shareLink)

        assertTrue(link.startsWith("vless://42fff517-e307-47a5-8521-809bdf020c6f@104.16.1.1:443?"))
        val params = query(link)
        assertEquals("none", params["encryption"])
        assertEquals("tls", params["security"])
        assertEquals("panel.example.workers.dev", params["sni"])
        assertEquals("chrome", params["fp"])
        assertEquals("ws", params["type"])
        assertEquals("panel.example.workers.dev", params["host"])
        assertEquals("/vl?ed=2048", params["path"])
        assertEquals("🐱 Cat | 104.16.1.1 [443]", URLDecoder.decode(link.substringAfter('#'), "UTF-8"))
    }

    @Test
    fun plainPortTrojanKeepsSecurityNone() {
        val yaml = """
            proxies:
              - name: 'plain'
                type: trojan
                server: 104.16.1.2
                port: 80
                password: 'p@ss:word'
                tls: false
                network: ws
                ws-opts:
                  path: '/tr'
                  headers:
                    Host: panel.example.workers.dev
        """.trimIndent()

        val link = requireNotNull(MihomoConfigParser.parse(yaml).catalog.profiles.single().shareLink)

        assertTrue(link.startsWith("trojan://p%40ss%3Aword@104.16.1.2:80?"))
        assertEquals("none", query(link)["security"])
        assertEquals("/tr", query(link)["path"])
    }

    @Test
    fun flowStyleYamlFromLinkImporterRoundTrips() {
        val yaml = """
            proxies:
              - name: 'Reality'
                type: vless
                server: '2001:db8::1'
                port: 8443
                uuid: '00000000-0000-0000-0000-000000000001'
                tls: true
                client-fingerprint: 'chrome'
                servername: 'www.example.com'
                reality-opts: {'public-key': 'pbk123', 'short-id': 'ab12'}
                flow: 'xtls-rprx-vision'
                network: 'grpc'
                grpc-opts: {'grpc-service-name': 'svc'}
                alpn: ['h2', 'http/1.1']
        """.trimIndent()

        val link = requireNotNull(MihomoConfigParser.parse(yaml).catalog.profiles.single().shareLink)

        assertTrue(link.startsWith("vless://00000000-0000-0000-0000-000000000001@[2001:db8::1]:8443?"))
        val params = query(link)
        assertEquals("reality", params["security"])
        assertEquals("pbk123", params["pbk"])
        assertEquals("ab12", params["sid"])
        assertEquals("xtls-rprx-vision", params["flow"])
        assertEquals("grpc", params["type"])
        assertEquals("svc", params["serviceName"])
        assertEquals("h2,http/1.1", params["alpn"])
    }

    @Test
    fun vmessProducesBase64Json() {
        val yaml = """
            proxies:
              - name: 'VM'
                type: vmess
                server: vm.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000003
                alterId: 0
                cipher: auto
                tls: true
                servername: vm.example.com
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    Host: vm.example.com
        """.trimIndent()

        val link = requireNotNull(MihomoConfigParser.parse(yaml).catalog.profiles.single().shareLink)
        assertTrue(link.startsWith("vmess://"))
        val json = JSONObject(String(Base64.getDecoder().decode(link.removePrefix("vmess://"))))
        assertEquals("VM", json.getString("ps"))
        assertEquals("vm.example.com", json.getString("add"))
        assertEquals("443", json.getString("port"))
        assertEquals("ws", json.getString("net"))
        assertEquals("/ws", json.getString("path"))
        assertEquals("vm.example.com", json.getString("host"))
        assertEquals("tls", json.getString("tls"))
    }

    @Test
    fun shadowsocksAndUnsupportedTypes() {
        val ss = MihomoShareLink.build(
            mapOf("type" to "ss", "name" to "SS", "server" to "ss.example.com", "port" to "8388",
                "cipher" to "aes-256-gcm", "password" to "secret"),
        )
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:secret".toByteArray())
        assertEquals("ss://$expected@ss.example.com:8388#SS", ss)

        assertNull(MihomoShareLink.build(mapOf("type" to "wireguard", "server" to "1.1.1.1", "port" to "51820")))
        assertNull(MihomoShareLink.build(mapOf("type" to "vless", "server" to "x", "port" to "443")))
        assertNull(MihomoShareLink.fromYamlBlock(""))
    }
}
