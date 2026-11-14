package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta248: Vercel routes learn hand-made Cloudflare panels from subscriptions; Fanoos uses the Cloudflare-fronted host. */
class Beta248FixesTest {
    private val master = "61111111-2222-4333-8444-555555555555"
    private val user = "81111111-2222-4333-8444-555555555555"

    @Test fun subscriptionLinksAndWorkerConfigsBecomePanelRoutes() {
        val found = VercelRoutePlanner.panelsFromSubscriptions(listOf(
            "https://cat.q5c4zw.dev-fmh2o5if.workers.dev/sub/$master",
            "vless://$user@188.114.98.0:443?encryption=none&security=tls&sni=other.me.workers.dev&type=ws&host=other.me.workers.dev&path=%2F#x",
            "https://example.com/sub/not-a-uuid",
            "vless://$user@1.2.3.4:443?host=my.own-domain.com#ignored-not-workers-dev",
        ))
        assertEquals(listOf("https://cat.q5c4zw.dev-fmh2o5if.workers.dev" to master, "https://other.me.workers.dev" to user), found)
        val routes = VercelRoutePlanner.routes(null, found)
        assertEquals("https://cat.q5c4zw.dev-fmh2o5if.workers.dev/vrelay/$master", routes[1].base)
        assertEquals(3, routes.size)
    }

    @Test fun samePanelFromSubLinkWinsOverItsVlessConfig() {
        val found = VercelRoutePlanner.panelsFromSubscriptions(listOf(
            "vless://$user@1.1.1.1:443?host=p.x.workers.dev#a",
            "https://p.x.workers.dev/sub/$master?format=raw",
        ))
        assertEquals(listOf("https://p.x.workers.dev" to master), found)
    }

    @Test fun routeReportNamesMissingRoutesAndOldPanels() {
        val panel = VercelRoutePlanner.panelRoute("https://p.x.workers.dev", master)!!
        val text = VercelRoutePlanner.routeReport(
            listOf(VercelRoutePlanner.direct() to "ConnectException: timeout", panel to "VercelForeignAnswerException: HTTP 404 not found"),
            fa = true,
        )
        assertTrue(text, text.contains("نسخهٔ قدیمی"))
        assertTrue(text, text.contains("برنامه وصل نبود"))
        assertFalse(text, text.contains("هیچ پنلی پیدا نشد"))
        val none = VercelRoutePlanner.routeReport(listOf(VercelRoutePlanner.direct() to "UnknownHostException"), fa = false)
        assertTrue(none, none.contains("none found") && none.contains("not connected"))
    }

    @Test fun fanoosTriesCloudflareFrontedHostFirstAndSkipsBlockPages() {
        assertEquals("https://df.getiantem.org/api/v1/config-new", LanternPlanner.CONFIG_URLS.first())
        assertEquals(LanternPlanner.CONFIG_NEW, LanternPlanner.CONFIG_URLS.last())
        assertTrue(LanternPlanner.isEdgeBlock(403, "<html><head><meta http-equiv=\"content-type\""))
        assertFalse(LanternPlanner.isEdgeBlock(403, "{\"error\":\"bad token\"}"))
        assertFalse(LanternPlanner.isEdgeBlock(200, "<html>"))
        val body = JSONObject(LanternPlanner.configBody("dev", "1", "tok", null))
        assertEquals("sing-box", body.getString("backend"))
    }
}
