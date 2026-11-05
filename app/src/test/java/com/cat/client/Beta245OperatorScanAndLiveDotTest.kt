package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta245: per-operator scanner memory/suggestions and the 🟢 live dot of the panel lists.
 * Behavioural: real merge/decode/suggest outputs, not source greps.
 */
class Beta245OperatorScanAndLiveDotTest {
    private val now = 1_800_000_000_000L
    private fun hit(ip: String, port: Int, ms: Int, ageDays: Int = 0) =
        OperatorScanHints.Hit(ip, port, ms, now - ageDays * 24L * 3600 * 1000)

    @Test
    fun wifiScansAreNeverLabelledWithTheSimOperator() {
        assertEquals("cellular MCI → mci", "mci", OperatorScanHints.bucket(MobileCarrier.MCI, true))
        assertEquals("Irancell SIM but scanning on Wi-Fi → wifi, not irancell", "wifi", OperatorScanHints.bucket(MobileCarrier.IRANCELL, false))
        assertEquals("transport unknown (only a VPN visible) → do not record", "", OperatorScanHints.bucket(MobileCarrier.MCI, null))
        assertEquals("cellular but operator unknown → do not record", "", OperatorScanHints.bucket(MobileCarrier.UNKNOWN, true))
    }

    @Test
    fun mergeKeepsExactPortsReplacesRetestsAndDropsExpiredRows() {
        val old = listOf(hit("188.114.98.10", 2053, 90, ageDays = 2), hit("104.16.1.1", 443, 70, ageDays = 31), hit("162.159.192.5", 8443, 50, ageDays = 1))
        val fresh = listOf(hit("188.114.98.10", 2053, 60), hit("188.114.98.10", 443, 75))
        val merged = OperatorScanHints.merge(old, fresh, now)
        val keys = merged.map { it.authority }
        assertEquals("re-test of the same IP:port replaces the old row (newest ms kept)", 1, keys.count { it == "188.114.98.10:2053" })
        assertEquals("newest ms wins", 60, merged.first { it.authority == "188.114.98.10:2053" }.ms)
        assertTrue("same IP on another port is a different pair and is kept", "188.114.98.10:443" in keys)
        assertTrue("a valid older row survives", "162.159.192.5:8443" in keys)
        assertFalse("a row older than 30 days is dropped", "104.16.1.1:443" in keys)
        val many = (1..80).map { hit("10.0.0.$it", 443, it) }
        assertEquals("capped per operator", OperatorScanHints.MAX_PER_OP, OperatorScanHints.merge(emptyList(), many, now).size)
    }

    @Test
    fun encodeDecodeRoundTripsAndRejectsJunk() {
        val list = listOf(hit("188.114.98.10", 2053, 60), hit("2606:4700::6810:84e5", 443, 120))
        val back = OperatorScanHints.decode(OperatorScanHints.encode(list))
        assertEquals("round trip keeps every field", list, back)
        assertEquals("IPv6 authority is bracketed", "[2606:4700::6810:84e5]:443", back[1].authority)
        val junk = OperatorScanHints.decode("1.2.3.4|0|5|1\nbad line\n1.2.3.0/24|443|5|1\n5.6.7.8|443|9|$now")
        assertEquals("port 0, broken lines and CIDRs are refused", listOf("5.6.7.8"), junk.map { it.ip })
    }

    @Test
    fun suggestionsPutLearnedIpsFirstThenTheOperatorsStartingRanges() {
        val learned = listOf(hit("188.114.98.20", 443, 140), hit("188.114.98.10", 2053, 60), hit("162.159.192.9", 2053, 200), hit("9.9.9.9", 2053, 10, ageDays = 40))
        val lines = OperatorScanHints.suggestionText(MobileCarrier.IRANCELL.id, learned, 2053, now).lines()
        assertEquals("learned on the chosen port first, fastest first", listOf("188.114.98.10", "162.159.192.9", "188.114.98.20"), lines.take(3))
        assertFalse("expired rows are not suggested", "9.9.9.9" in lines)
        assertTrue("Irancell starting ranges follow", lines.containsAll(OperatorScanHints.startRanges(MobileCarrier.IRANCELL.id)))
        assertTrue("every suggestion is something the scanner can parse", lines.all { IpScannerRangeShape.ok(it) })
        val empty = OperatorScanHints.suggestionText(MobileCarrier.MCI.id, emptyList(), 443, now).lines()
        assertEquals("nothing learned → only the starting ranges", OperatorScanHints.startRanges(MobileCarrier.MCI.id), empty)
        assertTrue("MCI starting set includes an IPv6 edge (MCI's IPv6 profile)", empty.any { it.contains(':') })
    }

    @Test
    fun nodePanelHealthOverPlainHttpIsClassifiedLikeTheWorkerProbe() {
        // the real body of the panel's /health (measured from the worker source: 200 {"ok":true})
        assertEquals("200 + {\"ok\":true} → HEALTHY", "HEALTHY", PanelLiveDot.stateOf(200, "{\"ok\":true}"))
        assertEquals("a 200 that is not the panel (host's HTML page) → DOWN", "DOWN", PanelLiveDot.stateOf(200, "<html>Welcome</html>"))
        assertEquals("502 from the host's proxy while the app sleeps → DOWN", "DOWN", PanelLiveDot.stateOf(502, "{\"ok\":false}"))
        assertEquals("no answer (code 0) → DOWN", "DOWN", PanelLiveDot.stateOf(0, ""))
        assertEquals("…and that maps to a red dot", PanelLiveDot.DOWN, PanelLiveDot.of(PanelLiveDot.stateOf(0, "")))
    }

    @Test
    fun liveDotOnlyTurnsGreenOnAHealthyAnswer() {
        assertEquals("not answered yet → white", PanelLiveDot.CHECKING, PanelLiveDot.of(null))
        assertEquals("HEALTHY → green", PanelLiveDot.UP, PanelLiveDot.of("HEALTHY"))
        assertEquals("PENDING (route not live yet) → yellow", PanelLiveDot.STARTING, PanelLiveDot.of("PENDING"))
        assertEquals("DOWN → red", PanelLiveDot.DOWN, PanelLiveDot.of("DOWN"))
        assertEquals("BLOCKED (1101) → red", PanelLiveDot.DOWN, PanelLiveDot.of("BLOCKED"))
        assertEquals("label puts the dot in front", "🟢  my-panel", PanelLiveDot.label("HEALTHY", "my-panel"))
        assertEquals("bare Vercel host gets https", "https://cat-panel-v7k2m.vercel.app", PanelLiveDot.healthBase("cat-panel-v7k2m.vercel.app"))
        assertEquals("node host keeps http and drops the slash", "http://51.75.118.79:20167", PanelLiveDot.healthBase("http://51.75.118.79:20167/"))
        assertEquals("blank → no probe", null, PanelLiveDot.healthBase("  "))
    }
}

/** Mirror of IpScanner.parseRangeList's acceptance rule for one token (CIDR, IPv4, IPv6), without Android. */
private object IpScannerRangeShape {
    fun ok(t: String): Boolean = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$").matches(t) || (t.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(t))
}
