package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionOptimizerTest {
    private val now = 50_000L

    private fun candidate(
        host: String,
        port: Int,
        role: EndpointRole = EndpointRole.CLEAN_IP,
        verdict: EndpointVerdict = EndpointVerdict.VERIFIED,
        latencyMs: Long = 35L,
    ) = VerifiedEndpoint(
        host = host,
        port = port,
        role = role,
        family = if (host.contains(':')) EndpointFamily.IPV6 else EndpointFamily.IPV4,
        verdict = verdict,
        checkedAtMs = now - 100L,
        latencyMs = latencyMs,
        source = "runtime-probe",
    )

    @Test fun rotatesVerifiedEndpointsPreservesIndependentProxyIpPortsAndKeepsBackup() {
        val lines = listOf(
            "vless://secret-uuid@104.16.0.1:443?type=ws&security=tls&host=worker.example&sni=worker.example&path=%2Fvl%3Fproxyip%3D103.237.102.191%3A11111#Node-1",
            "trojan://secret-pass@104.16.0.2:443?type=ws&security=tls&host=worker.example&sni=worker.example&path=%2Ftr%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111#Node-2",
        )
        val candidates = listOf(
            candidate("104.16.1.1", 8443, latencyMs = 25L),
            candidate("2606:4700::1", 2053, latencyMs = 30L),
            candidate("184.75.221.82", 3118, role = EndpointRole.PROXY_IP, latencyMs = 40L),
        )
        val plan = SubscriptionOptimizer.plan(
            lines = lines,
            candidates = candidates,
            nowMs = now,
            maxAgeMs = 60_000L,
            sniOverride = "scanner.worker.example",
            fingerprintOverride = "chrome",
        )

        assertEquals(2, plan.changedCount)
        assertEquals(lines, plan.backupLines)
        assertEquals(lines.joinToString("\n"), plan.backupPayload())

        val first = plan.optimizedLines[0]
        assertTrue(first.startsWith("vless://secret-uuid@104.16.1.1:8443?"))
        assertTrue(first.contains("host=worker.example"))
        assertTrue(first.contains("sni=scanner.worker.example"))
        assertTrue(first.contains("path=%2Fvl%3Fproxyip%3D184.75.221.82%3A3118"))
        assertTrue(first.endsWith("#Node-1"))

        val second = plan.optimizedLines[1]
        assertTrue(second.startsWith("trojan://secret-pass@[2606:4700::1]:2053?"))
        assertTrue(second.contains("host=worker.example"))
        assertTrue(second.contains("sni=scanner.worker.example"))
        assertTrue(second.contains("path=%2Ftr%3Fed%3D2560%26proxyip%3D184.75.221.82%3A3118"))
        assertTrue(second.endsWith("#Node-2"))

        val diffDump = plan.items.flatMap { it.changes }.toString()
        assertFalse(diffDump.contains("secret-uuid"))
        assertFalse(diffDump.contains("secret-pass"))
    }

    @Test fun neverDowngradesTlsConfigToPlainPortOrUsesPartialCandidates() {
        val original = "vless://uuid@104.16.0.1:443?type=ws&security=tls&host=worker.example&sni=worker.example&path=%2Fvl#TLS"
        val plan = SubscriptionOptimizer.plan(
            lines = listOf(original),
            candidates = listOf(
                candidate("104.16.1.1", 80, verdict = EndpointVerdict.VERIFIED),
                candidate("104.16.1.2", 8443, verdict = EndpointVerdict.PARTIAL),
            ),
            nowMs = now,
            maxAgeMs = 60_000L,
        )
        assertEquals(0, plan.changedCount)
        assertEquals(listOf(original), plan.optimizedLines)
    }

    @Test fun echAndAdvancedFragmentOrCustomSniMaskAreMutuallyExclusive() {
        val original = "vless://uuid@104.16.0.1:443?type=ws&security=tls&host=worker.example&sni=worker.example&path=%2Fvl#TLS"
        val plan = SubscriptionOptimizer.plan(
            lines = listOf(original),
            candidates = listOf(candidate("104.16.1.1", 8443)),
            nowMs = now,
            maxAgeMs = 60_000L,
            sniOverride = "www.speedtest.net",
            echEnabled = true,
            fragmentEnabled = true,
            cipherSuitesOverride = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256",
        )
        assertEquals(0, plan.changedCount)
        assertEquals(listOf(original), plan.optimizedLines)
        assertEquals(
            setOf(
                TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_FRAGMENT,
                TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_CUSTOM_SNI_MASK,
                TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_CUSTOM_CIPHER_SUITES,
            ),
            plan.conflict?.reasons,
        )
    }

    @Test fun mciCdnIpv6ProfileClearsEchCipherSuitesAndFinalMaskSetsChromeAndDetectsPagesDev() {
        val original = "vless://uuid@104.16.0.1:443?type=ws&security=tls&host=my-sub.pages.dev&sni=my-sub.pages.dev&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1&cs=OLD_CS&fm=OLD_FM&fp=firefox&path=%2Fvl#MCI"
        val plan = SubscriptionOptimizer.plan(
            lines = listOf(original),
            candidates = emptyList(),
            nowMs = now,
            firewallProfile = FirewallBypassProfile.MCI_CDN_IPV6,
        )
        assertEquals(1, plan.changedCount)
        assertEquals(listOf("my-sub.pages.dev"), plan.pagesDevDomains)
        val out = plan.optimizedLines.single()
        assertTrue(out.startsWith("vless://uuid@[2a06:98c1:3101::6812:202f]:443?"))
        assertTrue(out.contains("fp=chrome"))
        assertFalse(out.contains("ech="))
        assertFalse(out.contains("cs="))
        assertFalse(out.contains("fm="))
    }

    @Test fun irancellCdnFfProfileSetsFfMaskCipherSuitesUnsafeFingerprintAndTransportAlpn() {
        val wsConfig = "vless://uuid@104.16.0.1:443?type=ws&security=tls&host=worker.workers.dev&sni=worker.workers.dev&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1&path=%2Fvl#WS"
        val xhttpConfig = "vless://uuid@104.16.0.2:443?type=xhttp&security=tls&host=worker.workers.dev&sni=worker.workers.dev&path=%2Fxhttp#XHTTP"
        val plan = SubscriptionOptimizer.plan(
            lines = listOf(wsConfig, xhttpConfig),
            candidates = emptyList(),
            nowMs = now,
            firewallProfile = FirewallBypassProfile.IRANCELL_CDN_FF,
        )
        assertEquals(2, plan.changedCount)
        assertTrue(plan.pagesDevDomains.isEmpty())

        val outWs = VerifiedConfigUri.parse(plan.optimizedLines[0])!!
        assertEquals("188.114.98.0", outWs.entry.host)
        assertEquals("unsafe", outWs.fingerprint)
        assertEquals("http/1.1", outWs.alpn)
        assertEquals( null, outWs.ech)
        assertEquals(FirewallBypassRecipes.FIREWALL_FF_CIPHER_SUITES, outWs.cipherSuites)
        assertEquals(FirewallBypassRecipes.FIREWALL_FF_FINAL_MASK, outWs.finalMask)

        val outXhttp = VerifiedConfigUri.parse(plan.optimizedLines[1])!!
        assertEquals("188.114.98.0", outXhttp.entry.host)
        assertEquals("unsafe", outXhttp.fingerprint)
        assertEquals("h2,http/1.1", outXhttp.alpn)
        assertEquals(null, outXhttp.ech)
        assertEquals(FirewallBypassRecipes.FIREWALL_FF_CIPHER_SUITES, outXhttp.cipherSuites)
        assertEquals(FirewallBypassRecipes.FIREWALL_FF_FINAL_MASK, outXhttp.finalMask)
    }
}
