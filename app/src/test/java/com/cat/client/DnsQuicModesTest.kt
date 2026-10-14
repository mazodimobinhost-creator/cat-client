package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two QUIC-based resolver modes (DoQ / DoH3).
 *
 * WHY THEY EXIST: ECH (Encrypted ClientHello) is only as private as the *lookup* that fetches
 * its key — the ECHConfig rides in the zone's HTTPS/SVCB record, so a censored or poisoned
 * plaintext lookup never delivers it. Iran has repeatedly blocked external DoH/DoT resolvers,
 * while its middleboxes do not parse QUIC; DoQ and DoH-over-HTTP/3 therefore stay reachable
 * when the TCP-based encrypted resolvers stop. These tests pin the normalizers, because the
 * strings they return are written straight into the core's `nameserver:` list — a malformed
 * one is a silently broken tunnel, not a visible error.
 */
class DnsQuicModesTest {

    // ---------- DoQ ----------

    @Test
    fun doqHostWithoutPortGetsTheStandardPort() {
        assertEquals("quic://dns.example.org:853", DnsPrivacyPolicy.normalizeDoqEndpoint("quic://dns.example.org"))
        assertEquals("quic://dns.example.org:853", DnsPrivacyPolicy.normalizeDoqEndpoint("dns.example.org"))
        assertEquals("quic://dns.example.org:853", DnsPrivacyPolicy.normalizeDoqEndpoint("  dns.example.org  "))
    }

    @Test
    fun doqKeepsAnExplicitPort() {
        assertEquals("quic://dns.example.org:784", DnsPrivacyPolicy.normalizeDoqEndpoint("quic://dns.example.org:784"))
    }

    @Test
    fun doqBracketsABareIpv6Literal() {
        // A v6 literal must be bracketed or the core reads the colons as a port separator, so
        // this is the case a naive `java.net.URI` split gets wrong (it would take `1111` as a
        // port and leave a broken host behind). Pinned here because the failure would be a
        // silently dead resolver, not an error message.
        assertEquals("quic://[2606:4700:4700::1111]:853", DnsPrivacyPolicy.normalizeDoqEndpoint("quic://2606:4700:4700::1111"))
        assertEquals("quic://[2606:4700:4700::1111]:784", DnsPrivacyPolicy.normalizeDoqEndpoint("[2606:4700:4700::1111]:784"))
    }

    @Test
    fun doqRefusesGarbagePortsAndStrayParts() {
        val bad = listOf(
            "quic://dns.example.org:0",
            "quic://dns.example.org:70000",
            "quic://user:pass@dns.example.org",
            "quic://dns.example.org#proxy",
            "quic://dns.example.org?x=1",
            "quic://dns.example.org/dns-query",
            "tls://1.1.1.1",
            "quic://",
            "",
        )
        bad.forEach { input ->
            val threw = runCatching { DnsPrivacyPolicy.normalizeDoqEndpoint(input) }.isFailure
            assertTrue("accepted invalid DoQ endpoint: '$input'", threw)
        }
    }

    @Test
    fun defaultDoqEndpointIsAnIpSoNoPlaintextLookupIsNeeded() {
        // The default must not require resolving a hostname: that lookup is exactly what the
        // censor poisons. 1.1.1.1 is Cloudflare's DoQ endpoint.
        assertEquals("quic://1.1.1.1:853", DnsPrivacyPolicy.DEFAULT_DOQ_ENDPOINT)
        assertTrue(DnsPrivacyPolicy.normalizeDoqEndpoint(DnsPrivacyPolicy.DEFAULT_DOQ_ENDPOINT).contains(":853"))
    }

    // ---------- DoH3 ----------

    @Test
    fun doh3AddsTheDefaultPath() {
        assertEquals("h3://dns.example.org/dns-query", DnsPrivacyPolicy.normalizeDoh3Url("h3://dns.example.org"))
    }

    @Test
    fun doh3KeepsAnExplicitPathAndPort() {
        assertEquals("h3://dns.example.org:8443/resolve", DnsPrivacyPolicy.normalizeDoh3Url("h3://dns.example.org:8443/resolve"))
    }

    @Test
    fun anHttpsUrlIsConvertedToTheH3Scheme() {
        // The owner may paste a DoH URL they already use; the mode they picked decides the
        // transport, so the stored string must say h3:// rather than https://.
        assertEquals("h3://1.1.1.1/dns-query", DnsPrivacyPolicy.normalizeDoh3Url("https://1.1.1.1/dns-query"))
        assertEquals(
            "h3://dns.google/resolve",
            DnsPrivacyPolicy.normalizeDoh3Url("https://dns.google/resolve"),
        )
    }

    @Test
    fun doh3RejectsCredentialsFragmentsAndBadPorts() {
        val bad = listOf(
            "h3://user:pass@dns.example.org/dns-query",
            "h3://dns.example.org/dns-query#proxy",
            "h3://dns.example.org/dns-query?x=1",
            "h3://dns.example.org:0/dns-query",
            "h3://dns.example.org:99999/dns-query",
            "tls://1.1.1.1",
            "h3://",
            "",
        )
        bad.forEach { input ->
            val threw = runCatching { DnsPrivacyPolicy.normalizeDoh3Url(input) }.isFailure
            assertTrue("accepted invalid DoH3 URL: '$input'", threw)
        }
    }

    @Test
    fun everyModeHasItsOwnWireNameSoPrefsSurviveAnUpgrade() {
        // The wire names are stored in SharedPreferences: renaming one would silently reset
        // the owner's choice (and an old value must still read back as that mode).
        val wire = DnsPrivacyMode.values().associateBy { it.wireName }
        assertEquals(
            setOf("automatic", "doh", "dot", "doq", "doh3"),
            wire.keys,
        )
        assertEquals(DnsPrivacyMode.DoQ, DnsPrivacyMode.fromWireName("doq"))
        assertEquals(DnsPrivacyMode.DoH3, DnsPrivacyMode.fromWireName("doh3"))
        // Unknown/legacy values fall back to Automatic rather than crashing a start-up read.
        assertEquals(DnsPrivacyMode.Automatic, DnsPrivacyMode.fromWireName("nonsense"))
        assertEquals(DnsPrivacyMode.Automatic, DnsPrivacyMode.fromWireName(null))
    }
}
