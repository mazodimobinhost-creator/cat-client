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
}
