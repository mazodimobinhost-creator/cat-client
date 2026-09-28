package com.whitedns.vpn

import com.cat.client.ConnectionLabelPolicy
import com.cat.client.ConnectionProfile
import com.cat.client.EdgeLocationCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLocationAndLabelTest {
    @Test
    fun coloMapsToCityCountryAndFlag() {
        val frankfurt = EdgeLocationCatalog.fromColo("fra")
        assertEquals("Frankfurt", frankfurt?.city)
        assertEquals("Germany", frankfurt?.country)
        assertEquals("🇩🇪", frankfurt?.flag)
    }

    @Test
    fun displayLabelNeverLeaksAnIp() {
        val label = ConnectionLabelPolicy.displayName(
            tag = "🐱 12. VLESS - IPv4 : 443 · 104.16.1.1",
            type = "vless",
            server = "104.16.1.1",
            port = 443,
        )
        assertTrue(label.startsWith("🐱 Cat ·"))
        assertFalse(label.contains("104.16.1.1"))
        assertTrue(label.contains("443"))
    }

    @Test
    fun newCatLocationLabelStaysStable() {
        val profile = ConnectionProfile(
            tag = "🐱 Cat · 🇩🇪 Germany · VLESS · 443 · #01",
            type = "vless",
            server = "104.16.1.1",
            port = 443,
            transport = "ws",
            validationHost = "panel.example.com",
        )
        assertEquals(profile.tag, profile.displayTag)
    }
}
