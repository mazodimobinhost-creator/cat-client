package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta310 — while connected, Cat keeps a real HTTPS probe going and hops if internet dies.
 * Ninety-style quality engine using Cat's own RealInternetCheck, not a clone and not ping-only.
 */
class Beta310QualityWatchdogTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun versionIsBeta310() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 389"))
        assertTrue(gradle.contains("1.10.0-beta322"))
    }

    @Test
    fun watchdogUsesLightProbeThenFullConfirmAndSameHopRules() {
        val check = src("RealInternetCheck.kt")
        assertTrue(check.contains("const val WATCHDOG_INTERVAL_MS"))
        assertTrue(check.contains("fun runLight()"))
        assertTrue(check.contains("fun fromLightProbe("))
        val service = src("CatClientVpnServiceRealInternet.kt")
        assertTrue(service.contains("RealInternetCheck.runLight()"))
        assertTrue(service.contains("WATCHDOG_INTERVAL_MS"))
        assertTrue(service.contains("realCheck.watchdog"))
        assertTrue(service.contains("realCheckSessionAutomatic && realCheckHops < autoNextHopLimit()"))
        assertFalse("NaiveProxy / TrustTunnel / Ninety sidecars are not added", service.contains("NaiveProxy"))
        assertFalse(service.contains("TrustTunnel"))
        assertFalse(service.contains("Ninety"))
    }
}
