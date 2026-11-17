package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AetherTunPolicyTest {
    @Test fun acceptsLiteralTunnelAddresses() {
        assertEquals(
            "172.16.0.2" to "fd00::2",
            AetherTunPolicy.validatedAddresses("172.16.0.2", "fd00::2"),
        )
    }

    @Test fun rejectsRoutesHostnamesWrongFamiliesAndWhitespace() {
        listOf(
            "example.com" to "fd00::2",
            "172.16.0.2/32" to "fd00::2",
            "172.16.0.2" to "1.1.1.1",
            "::1" to "fd00::2",
            "172.16.0.2 " to "fd00::2",
        ).forEach { (v4, v6) ->
            assertThrows(IllegalArgumentException::class.java) {
                AetherTunPolicy.validatedAddresses(v4, v6)
            }
        }
    }
}
