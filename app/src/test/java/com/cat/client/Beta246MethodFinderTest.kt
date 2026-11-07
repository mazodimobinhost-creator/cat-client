package com.cat.client

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta246 🧭 method finder + free WireGuard/Hysteria2 lists (pure part). Lines are real rows from the public lists. */
class Beta246MethodFinderTest {
    private val warpRow =
        "wireguard://CEl0fmEpqOC1gJRZyepz5QhWLsVQmto06asvEBbUpnc%3D@162.159.192.7:908?address=172.16.0.2%2F32&reserved=159%2C34%2C157&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280&keepalive=5&wnoise=random&wnoisecount=5#%5B8%5D%20%F0%9F%87%BA%F0%9F%87%B8%20%7C%20US"
    private val warpRow2 =
        "wireguard://UCOeJ5kLImaLpdyvM1A1jJuzfBxXLQqrAZ3741nQWE0%3D@162.159.192.17:4500?address=172.16.0.2%2F32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1280#US"
    private val warpSamePort =
        "wireguard://YIHVg0ZuCfD7xzFZPTVtwIO8xFH%2BIo2WdQ58I%2FA%2FuUs%3D@162.159.192.8:4500?address=172.16.0.2%2F32&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D#US"
    private val realWg =
        "wireguard://cIq%2B3tcHVT7gRMl4kIv0WE68ERiTmDtA7rhyHhLJ%2Fmg%3D@103.1.213.212:80?address=100.80.234.177%2F32&presharedkey=N%2FgfMZSjvP8mbXfVP2hnD5CX8BkGvOyheJ67mCpD%2BQQ%3D&reserved=0%2C0%2C0&publickey=jCi1I5HBTtwCblBbMzOFmOOFNG1GilYOZgst0kyq9gY%3D&mtu=1280#%F0%9F%87%A6%F0%9F%87%BA%20Australia"
    private val hy2Insecure =
        "hysteria2://1d491f8c-ccaa-43e1-b62d-f255c7832e27@129.146.124.201:51000?insecure=1&sni=cn.cremedelamer.com#_US_IPV4"
    private val hy2Pinned =
        "hysteria2://7e68e32f-8015-4f37-9082-70e8b0b58043@54.180.236.157:443?insecure=1&sni=www.bing.com&pinSHA256=89:61:86:FE:B2:0C:B7:40:31:87:A4:5F:F3:BD:5D:DC:8B:FB:DD:41:9D:77:9D:55:D7:86:18:9D:36:73:1A:69#%F0%9F%87%B0%F0%9F%87%B7%20KR"
    private val hy2Domain =
        "hysteria2://41d4f8b3-2a45-4531-a33b-938e2eebb939@oh.sbbz.tech:443?sni=oh.sbbz.tech&obfs=salamander&obfs-password=x19890604#%F0%9F%87%BA%F0%9F%87%B8US-%40SomeChannel"
    private val hy2PlainIp = "hysteria2://pw@203.0.113.9:8443?sni=example.org#plain"

    @Test
    fun publicListRowsAreParsedAndClassified() {
        val warp = MethodFinderPlan.parseFreeServer(warpRow)!!
        assertEquals(FinderFamily.FREE_WG, warp.family)
        assertTrue("WARP's own public key (URL-encoded in the list) → a WARP account, not a server", warp.warp)
        assertEquals("162.159.192.7", warp.host)
        assertEquals("port exactly as listed", 908, warp.port)
        assertEquals("🇺🇸", warp.flag)
        val real = MethodFinderPlan.parseFreeServer(realWg)!!
        assertFalse("a server with its own key is a real WireGuard server", real.warp)
        assertEquals("🇦🇺", real.flag)
        val insecure = MethodFinderPlan.parseFreeServer(hy2Insecure)!!
        assertTrue("insecure=1 without a pin needs certificate checks off", insecure.insecureOnly)
        val pinned = MethodFinderPlan.parseFreeServer(hy2Pinned)!!
        assertFalse("insecure=1 WITH pinSHA256 is verified through the pin, so it stays", pinned.insecureOnly)
        assertTrue(pinned.pinned)
        val domain = MethodFinderPlan.parseFreeServer(hy2Domain)!!
        assertTrue(domain.obfs)
        assertNull("other schemes are not free-server rows", MethodFinderPlan.parseFreeServer("vless://x@1.2.3.4:443#a"))
        assertNull("port 0 is junk", MethodFinderPlan.parseFreeServer("hysteria2://pw@1.2.3.4:0?sni=a#b"))
        assertNull("no port is junk", MethodFinderPlan.parseFreeServer("hysteria2://pw@1.2.3.4?sni=a#b"))
        val v6 = MethodFinderPlan.parseFreeServer("hy2://pw@[2001:db8::1]:443?sni=a.example#x")!!
        assertEquals("2001:db8::1", v6.host)
        assertEquals(443, v6.port)
    }

    @Test
    fun pickingDropsCertificateSkippingRowsDedupesAndPutsRealServersBeforeWarp() {
        val text = listOf(warpRow, warpRow, warpRow2, warpSamePort, realWg, hy2Insecure, hy2PlainIp, hy2Pinned, hy2Domain, "garbage line").joinToString("\n")
        val picked = MethodFinderPlan.pickFreeServers(MethodFinderPlan.parseFreeServers(text))
        val hy2 = picked.filter { it.family == FinderFamily.FREE_HY2 }
        assertEquals("no Hysteria2 that only works with certificate checks off", 0, hy2.count { it.insecureOnly })
        assertEquals("domain/pinned first, obfuscated first among those", listOf("oh.sbbz.tech", "54.180.236.157", "203.0.113.9"), hy2.map { it.host })
        val wg = picked.filter { it.family == FinderFamily.FREE_WG }
        assertEquals("a real server comes before recycled WARP accounts", "103.1.213.212", wg.first().host)
        assertEquals("the same WARP row twice is one row; two WARP rows on one port are one", 3, wg.size)
        assertEquals("WARP rows keep their listed ports", setOf(908, 4500), wg.filter { it.warp }.map { it.port }.toSet())
    }

    @Test
    fun atMostTwoRowsPerHostAndTheCapsHold() {
        val many = (1..9).map { "hysteria2://pw$it@same.example.com:${4000 + it}?sni=same.example.com#x" } +
            (1..30).map { "hysteria2://pw@h$it.example.net:443?sni=h$it.example.net#y" }
        val picked = MethodFinderPlan.pickFreeServers(MethodFinderPlan.parseFreeServers(many.joinToString("\n")))
        assertEquals("at most two rows per host (lists repeat one server on many ports)", 2, picked.count { it.host == "same.example.com" })
        assertEquals("Hysteria2 cap", MethodFinderPlan.MAX_HY2, picked.size)
    }

    @Test
    fun quickFirstOrderGivesEveryMethodAnEarlyTurn() {
        val c = listOf(
            FinderCandidate(FinderFamily.FREE_HY2, hy2Domain, "a"),
            FinderCandidate(FinderFamily.FREE_HY2, hy2Pinned, "b"),
            FinderCandidate(FinderFamily.FREE_HY2, hy2PlainIp, "c"),
            FinderCandidate(FinderFamily.CAT_WARP, "aether://x#w", "w"),
            FinderCandidate(FinderFamily.FREE_WG, realWg, "g"),
        )
        val order = MethodFinderPlan.interleave(c).map { it.label }
        assertEquals("round 1 = first of each method (method order), then the rest", listOf("w", "a", "g", "b", "c"), order)
    }

    @Test
    fun numberedLabelsRoundTripThroughTheProfileTag() {
        val c = MethodFinderPlan.numbered(
            listOf(
                FinderCandidate(FinderFamily.CAT_WARP, "aether://warp?protocol=masque#old", "Cat Warp · MASQUE #bad"),
                FinderCandidate(FinderFamily.FREE_HY2, hy2Domain, "Hysteria2 🇺🇸"),
                FinderCandidate(FinderFamily.MINE, "ftp://not-a-config", "x"),
            ),
        )
        assertEquals("a link whose name cannot be set safely is left out", 2, c.size)
        val tag = java.net.URLDecoder.decode(c[1].link.substringAfter('#'), "UTF-8")
        assertEquals("🚀 Hysteria2 🇺🇸 #2", tag)
        assertEquals(1, MethodFinderPlan.indexOf(tag))
        assertEquals("a « 2» the converter adds to a duplicate name does not break the index", 1, MethodFinderPlan.indexOf("$tag 2"))
        assertEquals("a '#' inside the source name never fakes an index", 0, MethodFinderPlan.indexOf(java.net.URLDecoder.decode(c[0].link.substringAfter('#'), "UTF-8")))
        assertTrue("everything before the name is untouched", c[1].link.startsWith(hy2Domain.substringBefore('#') + "#"))
        assertNull(MethodFinderPlan.indexOf("no index here"))
    }

    @Test
    fun vmessNameIsSetInsideItsJson() {
        val json = "{\"v\":\"2\",\"ps\":\"old \\\"name\\\"\",\"add\":\"1.2.3.4\",\"port\":\"443\",\"id\":\"u\"}"
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val out = ShareLinkRemark.with(link, "🔗 mine \"x\" #3")
        assertNotNull(out)
        val decoded = String(Base64.getDecoder().decode(out!!.removePrefix("vmess://")))
        assertTrue(decoded, decoded.contains("\"ps\":\"🔗 mine \\\"x\\\" #3\""))
        assertTrue("the rest of the JSON is unchanged", decoded.contains("\"add\":\"1.2.3.4\"") && decoded.contains("\"id\":\"u\""))
        val noPs = "vmess://" + Base64.getEncoder().encodeToString("{\"add\":\"h\",\"port\":\"80\"}".toByteArray())
        assertTrue(String(Base64.getDecoder().decode(ShareLinkRemark.with(noPs, "n #1")!!.removePrefix("vmess://"))).startsWith("{\"ps\":\"n #1\","))
        assertNull("not base64 JSON → left out", ShareLinkRemark.with("vmess://%%%", "n"))
    }

    @Test
    fun summaryCountsOnlyRealResultsAndRanksWorkingMethodsFirst() {
        val c = listOf(
            FinderCandidate(FinderFamily.CAT_WARP, "", "w1"),
            FinderCandidate(FinderFamily.FREE_HY2, "", "h1"),
            FinderCandidate(FinderFamily.FREE_WG, "", "g1"),
            FinderCandidate(FinderFamily.FREE_HY2, "", "h2"),
            FinderCandidate(FinderFamily.SSTP, "", "s1"),
        )
        val s = MethodFinderPlan.summarize(c, tested = setOf(0, 1, 2, 3), delays = mapOf(1 to 300, 3 to 120, 2 to 900))
        val hy2 = s.first { it.family == FinderFamily.FREE_HY2 }
        assertEquals(2, hy2.working)
        assertEquals("best = fastest", 120, hy2.bestMs)
        assertEquals(3, hy2.bestIndex)
        val warp = s.first { it.family == FinderFamily.CAT_WARP }
        assertEquals("tested and failed → 0 working, no figure", 0, warp.working)
        assertNull(warp.bestMs)
        val sstp = s.first { it.family == FinderFamily.SSTP }
        assertEquals("not reached yet (stopped early)", 0, sstp.tested)
        assertEquals(
            "working (fastest first) → not tested yet → nothing worked",
            listOf(FinderFamily.FREE_HY2, FinderFamily.FREE_WG, FinderFamily.SSTP, FinderFamily.CAT_WARP),
            MethodFinderPlan.ranked(s).map { it.family },
        )
    }

    @Test
    fun lastRunPerNetworkSurvivesEncodeDecodeAndRejectsJunk() {
        val s = listOf(
            FinderFamilyResult(FinderFamily.FREE_HY2, 16, 9, 3, 140, 4),
            FinderFamilyResult(FinderFamily.CAT_WARP, 4, 4, 0, null, null),
        )
        val back = MethodFinderPlan.decode(MethodFinderPlan.encode(s))
        assertEquals(2, back.size)
        assertEquals(140, back[0].bestMs)
        assertEquals(3, back[0].working)
        assertNull(back[1].bestMs)
        assertTrue("more working than tested / unknown method / junk are dropped",
            MethodFinderPlan.decode("hy2|4|2|3|100\nnope|1|1|1|1\nwg|x|1|1|1\n").isEmpty())
    }

    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun entryPointsUseTheRealTestAndNoLongerTheTcpOnlyScout() {
        val guides = src("MainActivityGuides.kt")
        assertTrue("«🚀 I just want to connect» opens the real finder", guides.contains("showMethodFinderDialog(FinderFamily.entries.toSet(), freeOnly = false)"))
        assertFalse("the TCP-only scout is no longer what the user sees", guides.contains("runMlmScoutRecommendations("))
        val engines = src("MainActivityEngines.kt")
        assertFalse(engines.contains("fetchAndScanAmneziaAndProtonConfigs()"))
        assertTrue(engines.contains("0 -> showMethodFinderDialog(FinderFamily.FREE_SERVERS, freeOnly = true)"))
        assertTrue("the anti-censorship tab shows the finder card", src("MainActivityCatWarp.kt").contains("buildMethodFinderCard()"))
        assertTrue("the Amnezia/WireGuard section offers the free servers", src("MainActivityAmneziaSstp.kt").contains("showMethodFinderDialog(FinderFamily.FREE_SERVERS, freeOnly = true)"))
        val tests = src("CatClientVpnServiceConnectionTests.kt")
        assertTrue("live per-row delay only for rows the network check calls WORKS",
            tests.contains("delays = if (verdict == SmartRouteVerdict.WORKS && delayMs != null)"))
        val ui = src("MainActivityMethodFinder.kt")
        assertTrue("the finder runs the service's real delay test", ui.contains("setAction(Actions.TEST_CONNECTION_DELAYS)"))
        assertTrue("stop = the service's cancel (results so far stay)", ui.contains("setAction(Actions.CANCEL_CONNECTION_DELAY_TEST)"))
        assertTrue("a live connection is only paused after the user agrees", ui.contains("confirmFinderDisconnect(fa)"))
        val sources = src("MethodFinderSources.kt")
        assertFalse("lists over HTTPS only", sources.contains("\"http://"))
    }

    @Test
    fun brokenWireGuardKeysAreDroppedNotFedToTheCore() {
        val badPub = "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=@1.2.3.4:51820?publickey=.....#x"
        fun dropped(uri: String) = runCatching { SubConvConverter.convert(uri) }.getOrDefault(emptyList()).isEmpty()
        assertTrue(dropped(badPub))
        assertTrue(dropped("wireguard://not-a-key@1.2.3.4:51820?publickey=bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=#x"))
        assertTrue(SubConvConverter.convert(realWg).isNotEmpty())
        assertNull(MlmAndNextGenVpnHub.convertWgConfToAwgUri("[Interface]\nPrivateKey = ...\n[Peer]\nPublicKey = ...\nEndpoint = 1.2.3.4:51820\n"))
        val fa = MethodFinderPlan.plainCoreError("proxy 37: decode peer public key: illegal base64: data at input byte 5", fa = true)
        assertTrue(fa.contains("وایرگارد"))
        assertFalse(fa.contains("proxy 37"))
    }
}
