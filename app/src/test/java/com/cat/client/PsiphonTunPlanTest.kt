package com.cat.client

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonTunPlanTest {
    @Test fun finalLegCanOnlyDialLoopbackPsiphon() {
        val yaml = PsiphonTunPlan.yaml(45678)
        assertTrue(yaml.contains("server: 127.0.0.1"))
        assertTrue(yaml.contains("port: 45678"))
        assertTrue(yaml.contains("MATCH,CAT-AETHER-PSIPHON"))
        assertThrows(IllegalArgumentException::class.java) { PsiphonTunPlan.yaml(80) }
    }

    @Test fun torChainYamlRoutesTorThroughSimpleWarpCarrier() {
        val yaml = PsiphonTunPlan.torChainYaml(aetherSocksPort = 1819, torSocksPort = 9050, torReachable = true)
        assertTrue(yaml.contains("name: 'CAT-WARP-CARRIER'"))
        assertTrue(yaml.contains("port: 1819"))
        assertTrue(yaml.contains("name: 'CAT-TOR-EXIT'"))
        assertTrue(yaml.contains("port: 9050"))
        assertTrue(yaml.contains("dialer-proxy: 'CAT-WARP-CARRIER'"))
        assertTrue(yaml.contains("MATCH,CAT-AETHER-TOR"))
    }
}
