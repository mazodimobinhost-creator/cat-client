package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedEndpointTest {
    private val now = 1_000_000L
    private fun endpoint(host: String, port: Int, family: EndpointFamily, verdict: EndpointVerdict = EndpointVerdict.VERIFIED, role: EndpointRole = EndpointRole.CLEAN_IP) =
        VerifiedEndpoint(host, port, role, family, verdict, now - 1_000, 42, source = "device-scan")

    @Test fun preservesTheExactMeasuredPort() {
        val result = VerifiedEndpointSelection.subscriptionEntries(
            listOf(endpoint("104.16.1.1", 443, EndpointFamily.IPV4), endpoint("104.16.1.1", 8443, EndpointFamily.IPV4)), now, 60_000)
        assertEquals(listOf("104.16.1.1:443", "104.16.1.1:8443"), result.map { it.authority }.sorted())
    }

    @Test fun bracketsIpv6ExactlyOnce() {
        assertEquals("[2606:4700::1]:2053", endpoint("[2606:4700::1]", 2053, EndpointFamily.IPV6).authority)
    }

    @Test fun parserRequiresAndPreservesExplicitPort() {
        assertEquals("proxy.example:8443", VerifiedEndpointParser.parseAuthority(
            "proxy.example:8443", EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, now, "panel"
        )?.authority)
        assertEquals("[2606:4700::1]:2053", VerifiedEndpointParser.parseAuthority(
            "[2606:4700::1]:2053", EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, now, "scanner"
        )?.authority)
        assertEquals(null, VerifiedEndpointParser.parseAuthority(
            "proxy.example", EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, now, "panel"
        ))
        assertEquals(null, VerifiedEndpointParser.parseAuthority(
            "1.1.1.1:0", EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, now, "scanner"
        ))
    }

    @Test fun cleanScannerAdapterKeepsMeasuredIpv6Port() {
        val endpoint = VerifiedEndpointAdapters.fromCleanIpScan(IpScanner.ScanResult(
            ip = "2606:4700::1", pingMs = 51, sni = "worker.example", tlsOk = true,
            port = 8443, verifyTls = true, countryCode = "DE"
        ), now)
        assertEquals("[2606:4700::1]:8443", endpoint.authority)
        assertEquals(EndpointVerdict.PARTIAL, endpoint.verdict)
    }

    @Test fun proxyScannerAdapterDoesNotMislabelHttpOrSocks4AsSocks5() {
        val http = VerifiedEndpointAdapters.fromProxyScan(
            ProxyScannerPage.Hit("relay.example", 3128, ProxyScannerPage.Proto.HTTP, 80), now)
        val socks4 = VerifiedEndpointAdapters.fromProxyScan(
            ProxyScannerPage.Hit("1.2.3.4", 1080, ProxyScannerPage.Proto.SOCKS4, 90), now)
        assertEquals(EndpointRole.HTTP_RELAY, http.role)
        assertEquals("relay.example:3128", http.authority)
        assertEquals(EndpointRole.SOCKS4, socks4.role)
    }

    @Test fun placementKeepsScannerPortAndWorkerSni() {
        val placement = VerifiedEndpointPlacement.forWorker(
            endpoint("104.16.1.1", 8443, EndpointFamily.IPV4), "https://worker.example/"
        )!!
        assertEquals("104.16.1.1", placement.server)
        assertEquals(8443, placement.port)
        assertEquals("worker.example", placement.hostHeader)
        assertEquals("worker.example", placement.serverName)
    }

    @Test fun relaySerializationKeepsItsOwnPortAndType() {
        val socks = endpoint("2606:4700::2", 1080, EndpointFamily.IPV6, role = EndpointRole.SOCKS5)
        val http = endpoint("relay.example", 3128, EndpointFamily.DOMAIN, role = EndpointRole.HTTP_RELAY)
        assertEquals("socks5://[2606:4700::2]:1080", socks.relayUri())
        assertEquals("http://relay.example:3128", http.relayUri())
        assertEquals(null, endpoint("1.1.1.1", 443, EndpointFamily.IPV4).relayUri())
    }

    @Test fun excludesFailedAndRelayEndpointsFromSubscriptions() {
        val result = VerifiedEndpointSelection.subscriptionEntries(listOf(
            endpoint("1.1.1.1", 443, EndpointFamily.IPV4, EndpointVerdict.FAILED),
            endpoint("proxy.example", 1080, EndpointFamily.DOMAIN, role = EndpointRole.SOCKS5),
        ), now, 60_000)
        assertEquals(emptyList<VerifiedEndpoint>(), result)
    }
}
