package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «منابع اسکن» card and the two reports behind it.
 *
 * WHY THESE EXIST — the owner's report on the scanner was «فک کنم منبع نداره اسکن
 * کنه» (it looks like it has nothing to scan) and «داخل اسکنر ای‌پی‌های v6 نیست».
 * The v6 machinery was already there (`deriveIpv6Candidates`, `IPV6_SUBNETS`,
 * `V6_LIBRARY`) but it is gated behind a connectivity probe, and the screen showed
 * no numbers at all — so a v4-only walk and a zero-length walk looked identical.
 * These tests pin what each source really contributes, so the card can print a
 * number that cannot drift away from what the scan walks:
 *
 *   * every source in the report is exactly the list the scan will walk (union
 *     equality with buildCandidateList — a source can never be forgotten), and
 *   * v6 is either fully in (parseable literals, count > 0) or fully out.
 */
class IpScannerSourcesTest {

    private fun options(
        customSubnets: String = "",
        includeBuiltin: Boolean = true,
        includeIran: Boolean = true,
        includeIpv6: Boolean = false,
        perRange: Int = 24,
    ) = IpScanner.ScanOptions(
        customSubnets = customSubnets,
        includeBuiltin = includeBuiltin,
        includeIranLibrary = includeIran,
        includeIpv6 = includeIpv6,
        perRange = perRange,
        randomSample = false,
    )

    @Test
    fun reportHasTheFourSourcesInWalkOrder() {
        val keys = IpScanner.sourceReport(options()).map { it.key }
        assertEquals(listOf("mine", "cloudflare", "ipv6", "iran"), keys)
    }

    @Test
    fun theUnionOfSourcesIsExactlyTheCandidateList() {
        val opts = options(includeIpv6 = true)
        val perSource = IpScanner.sourceCandidates(opts).values.flatten().toSet()
        val walked = IpScanner.buildCandidateList(opts).toSet()
        assertEquals("a source is missing candidates the scan walks", walked, perSource)
    }

    @Test
    fun defaultRunWalksTheCloudflareLibraryAndTheIranCollections() {
        val report = IpScanner.sourceReport(options())
        val byKey = report.associateBy { it.key }
        // DEFAULT_RANGES: 24 hosts each, no custom CIDR typed → the built-in library applies.
        assertTrue("cloudflare source is empty", byKey.getValue("cloudflare").count > 0)
        assertTrue("iran source is empty", byKey.getValue("iran").count > 0)
        assertTrue("the empty range box must not report candidates", byKey.getValue("mine").count == 0)
        assertFalse("an empty range box is not an active source", byKey.getValue("mine").enabled)
    }

    @Test
    fun switchingASourceOffRemovesExactlyThatSource() {
        val on = IpScanner.sourceReport(options())
        val off = IpScanner.sourceReport(options(includeIran = false, includeBuiltin = false))
        assertTrue(on.associateBy { it.key }.getValue("iran").count > 0)
        assertEquals(0, off.associateBy { it.key }.getValue("iran").count)
        assertEquals(0, off.associateBy { it.key }.getValue("cloudflare").count)
        assertFalse(off.any { it.enabled && it.count > 0 })
    }

    @Test
    fun aTypedCidrReplacesTheBuiltinLibraryOnlyForCidrs() {
        // Historical rule, kept: the built-in Cloudflare ranges join the walk only when the
        // owner typed no CIDR of their own. A single plain IP in the box does not suppress it.
        val cidr = IpScanner.sourceReport(options(customSubnets = "104.16.0.0/13"))
        assertEquals(0, cidr.associateBy { it.key }.getValue("cloudflare").count)
        assertTrue(cidr.associateBy { it.key }.getValue("mine").count > 0)

        val plainIp = IpScanner.sourceReport(options(customSubnets = "104.16.1.1"))
        assertTrue(plainIp.associateBy { it.key }.getValue("cloudflare").count > 0)
        assertEquals(1, plainIp.associateBy { it.key }.getValue("mine").count)
    }

    @Test
    fun ipv6IsFullyOffWhenTheGateSaysNo() {
        // This is the complaint, as a test: with the v6 source off nothing v6 is walked —
        // and, importantly, nothing v6 is silently probed from the libraries either.
        val opts = options(includeIpv6 = false, customSubnets = "2606:4700::/48, 104.16.1.1")
        val report = IpScanner.sourceReport(opts)
        assertEquals(0, report.associateBy { it.key }.getValue("ipv6").count)
        val walked = IpScanner.buildCandidateList(opts)
        assertTrue("v6 literals survived a v6-off run", walked.none { IpScanner.isIpv6(it) })
        // The v4 half of the same box is untouched.
        assertTrue(walked.contains("104.16.1.1"))
    }

    @Test
    fun ipv6BringsDerivedAnycastAndLibraryAddressesWhenOn() {
        val opts = options(includeIpv6 = true)
        val report = IpScanner.sourceReport(opts).associateBy { it.key }.getValue("ipv6")
        assertTrue("the v6 source produced nothing", report.count > 0)
        assertTrue(report.enabled)
        assertTrue(report.ipv6)
        val v6 = IpScanner.sourceCandidates(opts).getValue("ipv6")
        // The v4 → v6 mapping of a Cloudflare edge, plus the static library.
        assertTrue(v6.contains("2606:4700::6810:84e5"))
        assertTrue(v6.any { it in IpScanner.V6_LIBRARY })
        v6.forEach { address -> assertTrue("$address is not a parseable v6 literal", IpScanner.isValidIpv6(address)) }
    }

    @Test
    fun everyReportedCountIsTheListLengthTheScanWalks() {
        val opts = options(includeIpv6 = true, customSubnets = "104.16.0.0/13")
        val lists = IpScanner.sourceCandidates(opts)
        IpScanner.sourceReport(opts).forEach { source ->
            assertEquals("count for ${source.key} disagrees with its list", lists.getValue(source.key).size, source.count)
        }
    }

    @Test
    fun perRangeScalesTheCountsNotJustTheLabels() {
        val small = IpScanner.sourceReport(options(perRange = 2)).associateBy { it.key }.getValue("cloudflare").count
        val big = IpScanner.sourceReport(options(perRange = 8)).associateBy { it.key }.getValue("cloudflare").count
        assertEquals("perRange is not driving the built-in library", small * 4, big)
    }
}
