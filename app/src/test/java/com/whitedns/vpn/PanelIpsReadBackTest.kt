package com.whitedns.vpn

import com.cat.client.PanelIpsReadBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelIpsReadBackTest {
    @Test fun exactRequestedCleanIpPortsAreCountedFromReadBack() {
        val requested = listOf("104.16.1.1:8443#DE", "[2606:4700::1]:2053#NL")
        val json = """{"ok":true,"persisted":true,"count":12,"ips":["104.16.1.1:8443","[2606:4700::1]:2053","1.1.1.1:443"]}"""
        val out = PanelIpsReadBack.verify(json, requested)
        assertTrue(out.persisted)
        assertEquals(2, out.stored)
        assertEquals(emptyList<String>(), out.missing)
    }

    @Test fun portRewriteOrBareIpIsNotCountedAsStored() {
        val requested = listOf("104.16.1.1:8443#DE", "104.16.1.2:2053#FR")
        val json = """{"ok":true,"persisted":true,"count":2,"ips":["104.16.1.1:443","104.16.1.2"]}"""
        val out = PanelIpsReadBack.verify(json, requested)
        assertEquals(0, out.stored)
        assertEquals(listOf("104.16.1.1:8443", "104.16.1.2:2053"), out.missing)
    }

    @Test fun unpersistedWriteReportsFalseEvenWhenIpsReturned() {
        val requested = listOf("104.16.1.1:8443#DE")
        val json = """{"ok":true,"persisted":false,"noKv":true,"count":1,"ips":["104.16.1.1:8443"],"added":["104.16.1.1:8443"],"duplicates":["1.1.1.1:443"],"invalid":["bad:999999"]}"""
        val out = PanelIpsReadBack.verify(json, requested)
        assertFalse(out.persisted)
        assertTrue(out.noKv)
        assertEquals(1, out.stored)
        assertEquals(listOf("104.16.1.1:8443"), out.added)
        assertEquals(listOf("1.1.1.1:443"), out.duplicates)
        assertEquals(listOf("bad:999999"), out.invalid)
    }
}
