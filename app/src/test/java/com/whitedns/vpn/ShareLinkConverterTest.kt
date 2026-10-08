package com.whitedns.vpn

import com.cat.client.SubConvConverter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the share-link → mihomo conversion the app uses to
 * connect with a Cat Panel subscription (`/u/<uuid>` serves plain vless/trojan
 * lines, which UserSubscriptionImporter converts with SubConvConverter).
 *
 * Two bugs found from a «the panel's config does not connect» report are pinned
 * here with the exact links the panel emits:
 *
 *  1. the trojan/ws branch never set the `Host` header, although the vless one
 *     did — for a Cloudflare deployment the Host must be the worker hostname, so
 *     every trojan config connected to the wrong place and never worked;
 *  2. early data arrives inside the path (`/vl/<seed>?ed=2560`, the BPB/Xray
 *     shape) and no client-side handling existed for it, so the 1-RTT first
 *     flight was silently dropped; `ed` must also be stripped from the
 *     transmitted path while other parameters (proxyip) survive.
 */
class ShareLinkConverterTest {

    // Exactly the shapes the panel builds (host = worker, sni = rotated pool
    // domain, alpn pinned to http/1.1 for WS over Cloudflare).
    private val vlessLink =
        "vless://6bd76824-92b3-4b76-a733-a0a0b63ccd7a@104.17.148.22:443?encryption=none&security=tls" +
            "&type=ws&host=edge-pedre.catclient-0ltgml5i.workers.dev" +
            "&path=%2Fvl%2FUNJIG31H6rVC5NioGvmsAXb%3Fed%3D2560&sni=icook.tw&fp=chrome&alpn=http%2F1.1" +
            "#Cat%20Panel"

    private val trojanLink =
        "trojan://6bd76824-92b3-4b76-a733-a0a0b63ccd7a@104.17.148.22:443?security=tls" +
            "&type=ws&host=edge-pedre.catclient-0ltgml5i.workers.dev" +
            "&path=%2Ftr%2FUNJIG31H6rVC5NioGvmsAXb%3Fed%3D2560&sni=www.speedtest.net&fp=chrome&alpn=http%2F1.1" +
            "#Cat%20Panel"

    // Legacy link already handed out by the panel: the proxyip parameter was
    // appended with a second '?' instead of '&' (fixed in the worker).
    private val legacyProxyIpLink =
        "vless://6bd76824-92b3-4b76-a733-a0a0b63ccd7a@104.17.148.22:443?encryption=none&security=tls" +
            "&type=ws&host=edge-pedre.catclient-0ltgml5i.workers.dev" +
            "&path=%2Fvl%2FUNJIG31H6rVC5NioGvmsAXb%3Fed%3D2560%3Fproxyip%3Dtr.diam4.ggff.net" +
            "&sni=cdnjs.cloudflare.com&fp=chrome&alpn=http%2F1.1#PX"

    private fun convertOne(link: String): JSONObject {
        val proxies = SubConvConverter.convert(link)
        assertEquals("one link in, one proxy out", 1, proxies.size)
        return proxies[0]
    }

    @Test
    fun vlessWebSocketCarriesHostHeaderAndEarlyData() {
        val proxy = convertOne(vlessLink)
        assertEquals("vless", proxy.getString("type"))
        assertEquals("104.17.148.22", proxy.getString("server"))
        assertEquals(443, proxy.getInt("port"))
        assertEquals("ws", proxy.getString("network"))
        assertEquals(true, proxy.getBoolean("tls"))
        assertEquals("icook.tw", proxy.getString("servername"))
        assertEquals("chrome", proxy.getString("client-fingerprint"))

        val ws = proxy.getJSONObject("ws-opts")
        // ed=2560 lives inside the path; it must be stripped from the wire path…
        assertEquals("/vl/UNJIG31H6rVC5NioGvmsAXb", ws.getString("path"))
        // …and turned into Sec-WebSocket-Protocol early data.
        assertEquals(2560, ws.getInt("max-early-data"))
        assertEquals("Sec-WebSocket-Protocol", ws.getString("early-data-header-name"))
        assertEquals(
            "edge-pedre.catclient-0ltgml5i.workers.dev",
            ws.getJSONObject("headers").getString("Host"),
        )
    }

    @Test
    fun trojanWebSocketAlsoCarriesTheHostHeader() {
        val proxy = convertOne(trojanLink)
        assertEquals("trojan", proxy.getString("type"))
        assertEquals("6bd76824-92b3-4b76-a733-a0a0b63ccd7a", proxy.getString("password"))
        assertEquals("www.speedtest.net", proxy.getString("sni"))

        val ws = proxy.getJSONObject("ws-opts")
        // THE regression: this Host was missing, so Cloudflare could not route
        // trojan configs to the worker and they never connected.
        assertEquals(
            "edge-pedre.catclient-0ltgml5i.workers.dev",
            ws.getJSONObject("headers").getString("Host"),
        )
        assertEquals("/tr/UNJIG31H6rVC5NioGvmsAXb", ws.getString("path"))
        assertEquals(2560, ws.getInt("max-early-data"))
    }

    @Test
    fun legacyDoubleQuestionMarkLinkStillYieldsAUsableProxy() {
        val proxy = convertOne(legacyProxyIpLink)
        val ws = proxy.getJSONObject("ws-opts")
        assertEquals(
            "edge-pedre.catclient-0ltgml5i.workers.dev",
            ws.getJSONObject("headers").getString("Host"),
        )
        // ed is recognised even in the malformed legacy shape (digits first)…
        assertEquals(2560, ws.getInt("max-early-data"))
        // …and the relay override survives, so the config still pins its exit.
        assertTrue("proxyip must survive: ${ws.getString("path")}", ws.getString("path").contains("proxyip=tr.diam4.ggff.net"))
        assertFalse(ws.getString("path").contains("ed=2560?"))
    }

    @Test
    fun nonTlsLinksDoNotGetTlsOrEarlyDataStripped() {
        val plain =
            "vless://6bd76824-92b3-4b76-a733-a0a0b63ccd7a@104.17.148.22:8080?encryption=none&security=none" +
                "&type=ws&host=edge-pedre.catclient-0ltgml5i.workers.dev&path=%2Fvl%2Fseed1234%3Fed%3D2560#plain"
        val proxy = convertOne(plain)
        assertFalse("security=none keeps tls off", proxy.has("tls"))
        assertEquals("/vl/seed1234", proxy.getJSONObject("ws-opts").getString("path"))
        assertEquals(2560, proxy.getJSONObject("ws-opts").getInt("max-early-data"))
    }

    @Test
    fun topLevelEdIsStillHonoured() {
        val link =
            "vless://6bd76824-92b3-4b76-a733-a0a0b63ccd7a@104.17.148.22:443?encryption=none&security=tls" +
                "&type=ws&host=worker.example.workers.dev&path=%2Fvl%2Fseed1234&ed=2048&sni=icook.tw#v2rayN"
        val ws = convertOne(link).getJSONObject("ws-opts")
        assertEquals(2048, ws.getInt("max-early-data"))
        assertEquals("/vl/seed1234", ws.getString("path"))
    }
}
