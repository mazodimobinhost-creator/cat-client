package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta220 wiring: service ↔ app ↔ panel for the real internet test, route memory and the opt-in report. */
class Beta220WiringTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    private val service by lazy { src("CatClientVpnService.kt") }

    @Test fun serviceHandlesTheTwoButtonsAndMeasuresAfterEveryStart() {
        assertTrue(service.contains("Actions.TRY_NEXT_ROUTE -> tryNextRoute()"))
        assertTrue(service.contains("Actions.RECHECK_REAL_INTERNET -> if (state == VpnState.Started)"))
        assertTrue(service.contains("onRealInternetCheckState(previousState, newState)"))
        assertTrue(service.contains("RealInternetCheck.run()"))
    }

    @Test fun automaticHopsAreBoundedAndOnlyInAutomaticMode() {
        val block = service.substringAfter("private fun scheduleRealInternetCheck(").substringBefore("private fun routeNetworkKey(")
        assertTrue(block.contains("realCheckSessionAutomatic"))
        assertTrue(block.contains("realCheckHops < RealInternetCheck.MAX_AUTO_HOPS"))
        // a single failure is re-checked before a route is blamed
        assertTrue(block.contains("RealInternetCheck.RECHECK_DELAY_MS"))
        // the user's own pick is never saved over by an automatic hop
        assertTrue(service.contains("switchActiveConnection(subscriptionId, fingerprint, persistSelection = false)"))
    }

    @Test fun routeMemoryFeedsTheAutomaticOrder() {
        assertTrue(service.contains("networkGood = routeMemoryGood()"))
        assertTrue(service.contains("networkBad = routeMemoryBad()"))
    }

    @Test fun realCheckIsCancelledWhenTheServiceDies() {
        val onDestroy = service.substringAfter("override fun onDestroy()").substringBefore("override fun onBind(")
        assertTrue(onDestroy.contains("realCheckJob?.cancel()"))
    }

    @Test fun appListensForResultsAndShowsTheCardInEveryDesign() {
        val main = MainActivitySources.combined()
        assertTrue(main.contains("addAction(Actions.REAL_INTERNET_CHECK)"))
        assertTrue(main.contains("Actions.REAL_INTERNET_CHECK -> handleRealInternetBroadcast(intent)"))
        assertTrue(main.contains("renderRealInternetCard(VpnRuntimeStateStore.readRealInternetResult(this))"))
        val dashboard = src("MainActivityDashboard.kt")
        assertEquals(3, Regex("""addView\(realInternetCard, """).findAll(dashboard).count())
        val ui = src("MainActivityRealInternet.kt")
        // buttons go through startVpnService so the service sees EXTRA_APP_INITIATED
        assertTrue(ui.contains("startVpnService(Actions.TRY_NEXT_ROUTE)"))
        assertTrue(ui.contains("startVpnService(Actions.RECHECK_REAL_INTERNET)"))
    }

    @Test fun reportIsOptInAndShowsWhatIsSent() {
        val ui = src("MainActivityRealInternet.kt")
        val dialog = ui.substringAfter("internal fun MainActivity.showAppReportDialog()")
        // nothing is sent before the user taps «Send»: send happens only inside the positive button
        val beforeButton = dialog.substringBefore("setPositiveButton(")
        assertFalse(beforeButton.contains("AppReport.send("))
        assertTrue(dialog.contains("AppReport.preview(facts, fa)"))
        assertTrue(dialog.contains("if (target != null)"))
        // failure never loses the report: it is copied
        assertTrue(dialog.contains("copy(AppReport.preview(f, fa))"))
    }

    @Test fun panelEndpointMatchesTheAppPayload() {
        val worker = listOf(File("src/main/assets/panels/catclient.worker.js"), File("app/src/main/assets/panels/catclient.worker.js"))
            .first { it.isFile }.readText()
        assertTrue(worker.contains("if (path === '/api/app-report')"))
        assertTrue(worker.contains("body.app !== 'cat-client'"))
        assertTrue(src("AppReport.kt").contains("const val PATH = \"/api/app-report\""))
        // the public endpoint sits before the owner gate, the owner view after it
        val gate = worker.indexOf("/* ---- everything below is owner-only ---- */")
        assertTrue(worker.indexOf("if (path === '/api/app-report')") in 1 until gate)
        assertTrue(worker.indexOf("if (path === '/api/app-reports')") > gate)
    }
}
