package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AmneziaVPN `vpn://` keys (Qt qCompress + URL-safe Base64) → runnable share links. */
class AmneziaVpnKeyTest {
    // Built with: json → struct('>I', len) + zlib → urlsafe base64 (keys 1, 2, 4); key 3 is an old uncompressed key.
    private val awgKey = "vpn://AAAD93icjVNrT9tAEPwr1omPabiHX0SARIE2AZFapEpDMEKH71Is2WfrfAmkUf57d-1QokpUtWN5dnZ2rcztbkhWGSdzo21DBt795j2GkMjS6F-5_CRffpIehPAaeBsyZJhkse9H7IiJCHNXGXI-wkI27hH6LHKUk01KuiCFKCX3I-O0XchMP6SpOVPK6qbxTjxG-3Gf9YNDwYG_GE-AO0huRzdnt3ePEPa8g8nl-bfxxS4GUWLzlXT6Wq9Bmw2valV-ofMfrtCzz2s5mz-rr1M7nxUvWVnwu9ltIfk0uPk-OoHaqwxqfARlbtrPt1i-Ag4QTxigKELEMc8CgEMk3_84MpgUHOKQM4GFQ4GdaRz5LBZxW-UDw48Yo1T4nAEzwj7H1uOnx08efY2DmMLFdk93heFRKMIsXERB6FMBeBEq-keXUZrt1zAaSHj5vgpiEYnoFD4Dv_tEa4tOJ8unIs92XvHpWpXTdTac5ujJR_HOqwSO6Flard6cHtv_eXbVZ0VRvWg1SvCUab-9D2nPGwwO0a5Lo-oqNw4togKSjIl-NPAZF3g8CUxm3jht3LXWtSzylUYl2prCsKXkuWrcWJa6m669Fl26rqzDVNsPidItOynjMU3JFicWRe34oggJZ6VpkH2sbeUqzC1VTbbbnvfBilS1NqvaYPEbxFX5exe20OMBNEov5LJw5__YNqWbzOa1yytsRW7W3kTbFUgxZ5puB_vtvaN4R6HDLfVmDdJ7xpDtb0BXKHk"
    private val xrayKey = "vpn://AAADx3icdVJRj9owDP4rKM8UtRR2PR73Np10Omkvk9YJhTSUiDSuEpfCEP99dtoOOG0uAmx_dv3Z31UocCiN0z6Izezn9e6TK2Tj9G8jk7OXFzGfifi7mV2FlQG3BN2bmnHXUhi3g85VoYxtSmFNQO3YK0W2fFmk9GQlNSlFCx45kaVFWsSABwQFdkAHUEdqc_vFKejwue8z9mR1CEPXoBGNqyOQcCenzzgVyaryEcg1yzTnWbJ8UTzPs1rl7HaBljFVmmqkMFqyJEtysmRFlqwfbOi2t9APNWe0IfGtPycnEwy4Ia-d8pcWo88oB04zW3oiDfRaNt-fyTiNPfjjUICqnRirzhu8DGEqs9GZ351Pbfb0X9NAxuFQow4eGj1uodtZo9702O7j61u7Ow7f0-v8Sft3yQWM6Pt-UQPUVqOsG-kk9V4oaEb0gbb6bdye3GXLMdyaSvsfQ5ho34g2K4tvwEqiG7CLXrrAsW28N2eINsHns_9INBxkBX3UDjd4dP8tWAtK2u10-2JdrHm-RuMBqmk7kj7LNDEa90kL9pLl6XjlVoZAJxmRrdzcZchb-qy018fk35d-eSleOYym0ST0GEpvxJKkLyodlDdRKDwxkzpAQN4_-w8qFrc_KWww4A"
    private val oldFieldsKey = "vpn://eyJjb250YWluZXJzIjogW3siY29udGFpbmVyIjogImFtbmV6aWEtYXdnIiwgImF3ZyI6IHsiSmMiOiAiMyIsICJKbWluIjogIjEwIiwgIkptYXgiOiAiMzAiLCAiUzEiOiAiMjAiLCAiUzIiOiAiNDAiLCAiSDEiOiAiNSIsICJIMiI6ICI2IiwgIkgzIjogIjciLCAiSDQiOiAiOCIsICJwb3J0IjogIjU1NTUiLCAibGFzdF9jb25maWciOiAie1wiY2xpZW50X3ByaXZfa2V5XCI6IFwiWTJ4cFpXNTBjSEpwZG1Oc2FXVnVkSEJ5YVhaamJHbGxiblJ3Y21sMk1UST1cIiwgXCJzZXJ2ZXJfcHViX2tleVwiOiBcImMyVnlkbVZ5Y0hWaWMyVnlkbVZ5Y0hWaWMyVnlkbVZ5Y0hWaU1USXpORFU9XCIsIFwicHNrX2tleVwiOiBcIlwiLCBcImNsaWVudF9pcFwiOiBcIjEwLjguMS45XCIsIFwiaG9zdE5hbWVcIjogXCIyMDAxOmRiODo6NVwifSJ9fV0sICJkZXNjcmlwdGlvbiI6ICJPbGQiLCAiaG9zdE5hbWUiOiAiMjAwMTpkYjg6OjUifQ"
    private val premiumKey = "vpn://AAAAe3icq1ZKLMiMz06tVLJSUEpMSlbSAVKlJRnxKYkliUCxamT5CqVaoHRyfl5aZnpYalFxZn4eSNhICSJakpiZBxQFCkXHAkVSUouTizILSqCqHHPzUqsyExUCilJzM0tzlWoBdhon1g"

    @Test
    fun awgKeyBecomesAwgLinkWithJunkPacketSpacesKept() {
        val r = AmneziaVpnKey.decode(awgKey)
        assertNotNull(r)
        r!!
        assertEquals("My Server", r.serverName)
        assertEquals(listOf("AmneziaWG"), r.protocols)
        assertEquals(listOf("OpenVPN"), r.unsupported)
        val link = r.links.single()
        assertTrue(link.startsWith("awg://"))
        assertTrue(link.contains("@203.0.113.7:41234?"))
        assertTrue(link.contains("&h1=1844719137&"))
        assertTrue(link.contains("&s1=77&s2=115&"))
        // `<r 2><b 0x…>` must keep its spaces (they were stripped before → broken I1).
        assertTrue(link.contains("i1=%3Cr%202%3E%3Cb%200x8580"))
        assertTrue(link.contains("mtu=1280"))
        assertTrue(link.contains("presharedkey="))
    }

    @Test
    fun xrayAndShadowsocksContainers() {
        val r = AmneziaVpnKey.decode(xrayKey)!!
        assertEquals(2, r.links.size)
        val vless = r.links[0]
        assertTrue(vless.startsWith("vless://11111111-2222-3333-4444-555555555555@203.0.113.8:443?"))
        listOf("type=tcp", "security=reality", "sni=www.googletagmanager.com", "fp=chrome", "pbk=PBKpbkPBKpbk", "sid=ab12", "flow=xtls-rprx-vision", "encryption=none")
            .forEach { assertTrue(it, vless.contains(it)) }
        assertTrue(r.links[1].startsWith("ss://"))
        assertTrue(r.links[1].contains("@203.0.113.9:6789#"))
    }

    @Test
    fun oldUncompressedKeyWithFieldsOnlyAndIpv6() {
        val r = AmneziaVpnKey.decode(oldFieldsKey)!!
        val link = r.links.single()
        assertTrue(link.contains("@[2001:db8::5]:5555?"))
        assertTrue(link.contains("address=10.8.1.9%2F32"))
        assertTrue(link.contains("&h4=8"))
    }

    @Test
    fun premiumAccountKeyIsExplainedNotGuessed() {
        val r = AmneziaVpnKey.decode(premiumKey)!!
        assertTrue(r.links.isEmpty())
        assertNotNull(r.problemFa)
        assertTrue(r.problemFa!!.contains("Premium"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(AmneziaVpnKey.decode("vpn://!!!"))
        assertNull(AmneziaVpnKey.decode("vpn://"))
        assertTrue(AmneziaVpnKey.toUris("hello world").isEmpty())
        assertEquals(4, AmneziaVpnKey.toUris(listOf(awgKey, xrayKey, oldFieldsKey, premiumKey).joinToString("\n")).size)
    }

    @Test
    fun confImportKeepsJunkPacketSpacesAndV15Fields() {
        val conf = "[Interface]\nPrivateKey = a2V5\nAddress = 10.0.0.2/32\nJc = 3\nI1 = <b 0x01 02>\nJ1 = <t>\nItime = 120\n[Peer]\nPublicKey = cHVi\nEndpoint = 198.51.100.1:51820\n"
        val uri = WireGuardConfText.toUris(conf).single()
        assertTrue(uri, uri.contains("i1=%3Cb%200x01%2002%3E"))
        assertTrue(uri, uri.contains("j1=%3Ct%3E"))
        assertTrue(uri, uri.contains("itime=120"))
    }
}
