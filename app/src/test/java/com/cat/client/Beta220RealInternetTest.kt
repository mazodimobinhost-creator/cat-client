package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta220: real internet test after connect, «next route» with memory per network, opt-in report to the panel.
 * Pure logic only (the service/UI wiring is pinned by Beta220WiringTest).
 */
class Beta220RealInternetTest {
    private val now = 1_800_000_000_000L

    /* ---- RealInternetCheck.classify: decisions only from real measurements ---- */

    @Test fun probeAndBigDownloadIsWorks() {
        val r = RealInternetCheck.classify(
            RealInternetCheck.Probe(true, 180),
            RealInternetCheck.Download(200_000, 1_000),
        )
        assertEquals(RealInternetStatus.Works, r.status)
        assertEquals(180L, r.latencyMs)
        assertEquals(1600, r.kbps)
    }

    @Test fun downloadThatCrawlsIsSlow() {
        val r = RealInternetCheck.classify(
            RealInternetCheck.Probe(true, 300),
            RealInternetCheck.Download(60_000, 8_000), // 60 kbit/s
        )
        assertEquals(RealInternetStatus.Slow, r.status)
        assertTrue(r.status.isProblem)
    }

    @Test fun smallRequestsPassButDownloadStallsIsSlowWithTheRealError() {
        val r = RealInternetCheck.classify(
            RealInternetCheck.Probe(true, 250),
            RealInternetCheck.Download(3_000, 9_000, "SocketTimeoutException: Read timed out"),
        )
        assertEquals(RealInternetStatus.Slow, r.status)
        assertTrue(r.detail.contains("Read timed out"))
    }

    @Test fun nothingBackIsNoInternetAndKeepsBothErrors() {
        val r = RealInternetCheck.classify(
            RealInternetCheck.Probe(false, 6_000, "SocketTimeoutException: connect timed out"),
            RealInternetCheck.Download(0, 9_000, "UnknownHostException: speed"),
        )
        assertEquals(RealInternetStatus.NoInternet, r.status)
        assertEquals(-1L, r.latencyMs)
        assertEquals(-1, r.kbps)
        assertTrue(r.detail.contains("connect timed out") && r.detail.contains("UnknownHostException"))
    }

    @Test fun noMeasurementIsNeverReportedAsWorking() {
        val r = RealInternetCheck.classify(null, null)
        assertEquals(RealInternetStatus.NoInternet, r.status)
        assertFalse(r.detail.isBlank())
    }

    @Test fun probesAreHttpsOnlyAndNoFiguresAreHardcoded() {
        assertTrue(RealInternetCheck.PROBE_URLS.all { it.startsWith("https://") })
        assertTrue(RealInternetCheck.DOWNLOAD_URLS.all { it.startsWith("https://") })
        assertEquals(3, RealInternetCheck.MAX_AUTO_HOPS)
        assertEquals(-1, RealInternetCheck.kbps(1000, 0))
    }

    @Test fun statusWireNamesMatchThePanelEndpoint() {
        // the panel keeps only works/slow/none/na
        assertEquals(listOf("checking", "works", "slow", "none", "na"), RealInternetStatus.entries.map { it.wire })
        assertEquals(RealInternetStatus.NoInternet, RealInternetStatus.fromWire("none"))
        assertNull(RealInternetStatus.fromWire("bogus"))
        assertFalse(RealInternetStatus.Works.isProblem)
        assertFalse(RealInternetStatus.NotMeasurable.isProblem)
    }

    /* ---- RouteMemoryPolicy ---- */

    @Test fun networkKeyIsPrivacySafeAndSeparatesOperators() {
        assertEquals("cellular:43211", RouteMemoryPolicy.networkKey(true, false, false, "432-11"))
        assertEquals("cellular:43235", RouteMemoryPolicy.networkKey(true, false, false, "43235"))
        assertEquals("cellular:unknown", RouteMemoryPolicy.networkKey(true, false, false, ""))
        assertEquals("wifi", RouteMemoryPolicy.networkKey(false, true, false, "43211"))
        assertEquals("ethernet", RouteMemoryPolicy.networkKey(false, false, true, null))
    }

    @Test fun failedRouteIsBadForSixHoursUnlessItWorkedAgain() {
        var e = RouteMemoryPolicy.record(emptyList(), "A", ok = false, nowMs = now)
        assertEquals(setOf("A"), RouteMemoryPolicy.bad(e, now + 60_000))
        assertTrue(RouteMemoryPolicy.bad(e, now + RouteMemoryPolicy.BAD_FOR_MS + 1).isEmpty())
        e = RouteMemoryPolicy.record(e, "A", ok = true, nowMs = now + 120_000)
        assertTrue(RouteMemoryPolicy.bad(e, now + 180_000).isEmpty())
        assertEquals(listOf("A"), RouteMemoryPolicy.good(e, now + 180_000))
        assertEquals(1, e.single().okCount)
        assertEquals(1, e.single().failCount)
    }

    @Test fun goodRoutesAreNewestFirstAndExpire() {
        var e = RouteMemoryPolicy.record(emptyList(), "A", ok = true, nowMs = now)
        e = RouteMemoryPolicy.record(e, "B", ok = true, nowMs = now + 1_000)
        assertEquals(listOf("B", "A"), RouteMemoryPolicy.good(e, now + 2_000))
        assertTrue(RouteMemoryPolicy.good(e, now + RouteMemoryPolicy.GOOD_FOR_MS + 2_000).isEmpty())
    }

    @Test fun memoryIsCapped() {
        var e = emptyList<RouteMemoryEntry>()
        repeat(RouteMemoryPolicy.MAX_ENTRIES_PER_NETWORK + 10) { i -> e = RouteMemoryPolicy.record(e, "F$i", true, now + i) }
        assertEquals(RouteMemoryPolicy.MAX_ENTRIES_PER_NETWORK, e.size)
        assertEquals("F${RouteMemoryPolicy.MAX_ENTRIES_PER_NETWORK + 9}", e.first().fingerprint)
        assertEquals(e, RouteMemoryPolicy.record(e, " ", true, now))
    }

    @Test fun nextCandidatePrefersRememberedGoodThenLowestDelayAndSkipsTriedAndBad() {
        val all = listOf("A", "B", "C", "D", "E")
        assertEquals(
            "D",
            RouteMemoryPolicy.nextCandidate(all, current = "A", tried = setOf("A"), good = listOf("D"), bad = emptySet()),
        )
        assertEquals(
            "C",
            RouteMemoryPolicy.nextCandidate(all, "A", setOf("A", "B"), emptyList(), setOf("E"), mapOf("C" to 90L, "D" to 300L)),
        )
        // bad routes are skipped …
        assertEquals("E", RouteMemoryPolicy.nextCandidate(all, "A", setOf("B", "C"), emptyList(), setOf("D")))
        // … unless only bad ones are left
        assertEquals("D", RouteMemoryPolicy.nextCandidate(all, "A", setOf("B", "C", "E"), emptyList(), setOf("D")))
        // nothing left → null (the UI then offers a reconnect instead)
        assertNull(RouteMemoryPolicy.nextCandidate(all, "A", all.toSet(), emptyList(), emptySet()))
        assertNull(RouteMemoryPolicy.nextCandidate(listOf("A"), "A", emptySet(), emptyList(), emptySet()))
    }

    /* ---- AppReport ---- */

    @Test fun configKindIsKindOnly() {
        val link = "vless://11111111-2222-4333-8444-555555555555@104.16.1.1:443?type=ws&security=tls&sni=my.example.com&path=%2Fsecret#Me"
        assertEquals("vless/ws/tls", AppReport.configKind(link, "vless", "ws"))
        assertEquals("vmess/grpc", AppReport.configKind("vmess://eyJhZGQiOiIxLjIuMy40In0=", "vmess", "grpc"))
        assertEquals("aether", AppReport.configKind("aether://abc", "aether", ""))
        assertEquals("wireguard", AppReport.configKind(null, "wireguard", ""))
        assertEquals("unknown", AppReport.configKind("", "", ""))
        assertFalse(AppReport.configKind(link, "vless", "ws").contains("example"))
    }

    @Test fun engineFamily() {
        assertEquals("cat-warp", AppReport.engineFor("aether"))
        assertEquals("cat-sstp", AppReport.engineFor("sstp"))
        assertEquals("cat-dns", AppReport.engineFor("slipnet/udp"))
        assertEquals("local-bridge", AppReport.engineFor("socks5", "127.0.0.1"))
        assertEquals("cat-core", AppReport.engineFor("vless/ws/tls"))
    }

    @Test fun redactRemovesAddressesKeysAndIds() {
        val raw = "connect 104.16.1.1:443 via [2a06:98c1:3101::6812:202f]:443 host=my.example.com " +
            "uuid 11111111-2222-4333-8444-555555555555 url https://x.workers.dev/sub?k=1 " +
            "password=hunter2 token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9xyz domain cdn.example.ir"
        val red = AppReport.redact(raw)
        listOf("104.16.1.1", "2a06", "6812", "example", "1111", "workers.dev", "hunter2", "eyJhbGci", "cdn.").forEach {
            assertFalse("leaked $it in: $red", red.contains(it))
        }
    }

    @Test fun redactKeepsLogEventNamesAndClockTimes() {
        val line = "12:34:56.789 W realCheck.result status=none mihomo.postConnect.health.failed"
        assertEquals(line, AppReport.redact(line))
    }

    @Test fun logExcerptKeepsOnlyUsefulLinesCappedAndRedacted() {
        val log = (1..200).joinToString("\n") { i ->
            if (i % 2 == 0) "12:00:00 I realCheck.result status=none server=1.2.3.$i" else "12:00:00 I ui.tap button=$i"
        }
        val ex = AppReport.logExcerpt(log)
        assertTrue(ex.length <= AppReport.MAX_LOG_CHARS)
        assertFalse(ex.contains("ui.tap"))
        assertFalse(ex.contains("1.2.3."))
        assertTrue(ex.contains("realCheck.result"))
    }

    @Test fun payloadHasTheAppMarkerAndOnlyAnonymousFields() {
        val facts = AppReportFacts(
            appVersion = "1.10.0-beta220", sdk = 34, operator = "mci", operatorCode = "432-11", network = "cellular",
            engine = "cat-core", configKind = "vless/ws/tls",
            result = RealInternetResult(RealInternetStatus.NoInternet, hops = 2, fingerprint = "secret-fp", routeLabel = "🐱 Cat · DE"),
            dnsMode = "fake-ip", note = "nothing loads \"quoted\"\nline2 at 5.6.7.8",
            logExcerpt = "realCheck.result status=none",
        )
        val json = AppReport.payload(facts)
        assertTrue(json.startsWith("{\"app\":\"cat-client\""))
        assertTrue(json.contains("\"st\":\"none\""))
        assertTrue(json.contains("\"opc\":\"43211\""))
        assertTrue(json.contains("\"hops\":2"))
        assertTrue(json.contains("\\\"quoted\\\"") && json.contains("\\n"))
        assertFalse(json.contains("5.6.7.8"))
        assertFalse(json.contains("secret-fp"))
        assertFalse(json.contains("Cat · DE"))
        // a check still running is sent as «not measured»
        assertTrue(AppReport.payload(facts.copy(result = RealInternetResult(RealInternetStatus.Checking))).contains("\"st\":\"na\""))
        val preview = AppReport.preview(facts, fa = true)
        assertTrue(preview.contains("وصل ولی بدون اینترنت"))
        assertFalse(preview.contains("5.6.7.8"))
    }

    @Test fun reportTargetIsTheUsersOwnPanelNeverAFileHost() {
        assertEquals("https://cat.example.workers.dev", AppReport.targetOrigin("https://cat.example.workers.dev/panel/", null))
        assertEquals(
            "https://my-panel.vercel.app",
            AppReport.targetOrigin(null, "https://my-panel.vercel.app/sub/11111111-2222-4333-8444-555555555555"),
        )
        assertNull(AppReport.targetOrigin(null, "https://raw.githubusercontent.com/a/b/main/sub.txt"))
        assertNull(AppReport.targetOrigin(null, "https://cdn.jsdelivr.net/gh/a/b/sub"))
        assertNull(AppReport.targetOrigin(null, "http://plain.example.com/sub")) // cleartext is off app-wide
        assertNull(AppReport.targetOrigin(null, "vless://x@1.2.3.4:443"))
        assertNull(AppReport.originOf("https://user:pw@host.example.com/"))
        assertNotNull(AppReport.originOf("https://HOST.example.com:8443/x"))
        assertEquals("https://host.example.com:8443", AppReport.originOf("https://HOST.example.com:8443/x"))
    }

    @Test fun jsonStringEscapesControlCharacters() {
        assertEquals("\"a\\u0001b\\tc\"", AppReport.jsonString("a\u0001b\tc"))
    }
}
