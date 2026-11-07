package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MapOriginTest {
    @Test
    fun networkCountryWinsThenSim() {
        assertEquals("IR", MapOrigin.telephonyCountry("ir", "de"))
        assertEquals("DE", MapOrigin.telephonyCountry("", "de"))
        assertEquals("DE", MapOrigin.telephonyCountry(null, " De "))
    }

    @Test
    fun junkIsNotACountry() {
        assertNull(MapOrigin.telephonyCountry("", ""))
        assertNull(MapOrigin.telephonyCountry("432", "x1"))
        assertNull(MapOrigin.telephonyCountry(null, null))
        assertNull(MapOrigin.usableCode("1.2.3.4"))
        assertNull(MapOrigin.usableCode(""))
        assertEquals("IR", MapOrigin.usableCode(" ir "))
    }

    @Test
    fun countryNameIsLocalisedWithCodeFallback() {
        assertEquals("Iran", MapOrigin.countryName("IR", Locale.ENGLISH))
        assertEquals("ZZ", MapOrigin.countryName("ZZ", Locale.ENGLISH))
    }
}
