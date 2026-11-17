package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHostPlanTest {
    @Test
    fun botHostingKeyShape() {
        assertTrue(ApiHostPlan.isBotHostingKey("bhk_0123456789abcdefABCDEF"))
        assertFalse(ApiHostPlan.isBotHostingKey("ptlc_0123456789abcdefABCDEF"))
        assertFalse(ApiHostPlan.isBotHostingKey("bhk_short"))
        assertFalse(ApiHostPlan.isBotHostingKey("bhk_0123456789 abcdefABCDEF"))
    }

    @Test
    fun scopesNeverSpendOrDelete() {
        assertFalse(ApiHostPlan.BOTHOSTING_SCOPES.contains("credits:spend"))
        assertTrue(ApiHostPlan.BOTHOSTING_SCOPES.containsAll(listOf("deployments:write", "files:write", "deployments:power")))
    }

    @Test
    fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", ApiHostPlan.jsonString("a\"b\\c\nd"))
        assertEquals("{\"UUID\":\"x\",\"PORT\":\"8080\"}", ApiHostPlan.jsonObject(linkedMapOf("UUID" to "x", "PORT" to "8080")))
        val body = ApiHostPlan.gqlBody("mutation { a }", "{\"id\":\"1\"}")
        assertEquals("{\"query\":\"mutation { a }\",\"variables\":{\"id\":\"1\"}}", body)
    }

    @Test
    fun domainsBecomeHttpsBases() {
        assertEquals("https://cat-panel-production.up.railway.app", ApiHostPlan.httpsBase("cat-panel-production.up.railway.app"))
        assertEquals("https://x.apps.bot-hosting.cloud", ApiHostPlan.httpsBase("https://x.apps.bot-hosting.cloud/"))
        assertNull(ApiHostPlan.httpsBase(""))
        assertNull(ApiHostPlan.httpsBase("localhost"))
        assertEquals("https://my-bot.apps.bot-hosting.cloud", ApiHostPlan.botHostingUrl("my-bot.apps", ""))
        assertEquals("https://a.apps.bot-hosting.cloud", ApiHostPlan.botHostingUrl("ignored.apps", "https://a.apps.bot-hosting.cloud"))
        // A files/download URL is never the app address.
        assertEquals("https://b.apps.bot-hosting.cloud", ApiHostPlan.botHostingUrl("b.apps", "https://bot-hosting.net/files/download?path=%2Fmain.py"))
    }

    @Test
    fun railwayStartCommandPinsTheRelease() {
        val cmd = ApiHostPlan.railwayStartCommand("v1.10.0-beta198")
        assertTrue(cmd.startsWith("sh -c '"))
        assertTrue(cmd.endsWith("exec node index.js'"))
        assertTrue(cmd.contains("https://raw.githubusercontent.com/" + NodeHostPanel.REPO + "/v1.10.0-beta198/node-panel/index.js"))
        assertTrue(cmd.contains("/v1.10.0-beta198/node-panel/package.json"))
        assertEquals(2, Regex("'").findAll(cmd).count())
    }

    @Test
    fun railwayRefRoundTrip() {
        val ref = ApiHostPlan.railwayRef("p1", "s2", "e3")
        assertEquals(Triple("p1", "s2", "e3"), ApiHostPlan.parseRailwayRef(ref))
        assertNull(ApiHostPlan.parseRailwayRef("p1|s2"))
        assertNull(ApiHostPlan.parseRailwayRef("p1||e3"))
    }

    @Test
    fun errorsGetPlainReasons() {
        assertEquals("key", ApiHostPlan.explainBotHosting(401, ""))
        assertEquals("scope", ApiHostPlan.explainBotHosting(403, "missing scope files:write"))
        assertEquals("slot", ApiHostPlan.explainBotHosting(400, "No free slot left in your plan"))
        assertEquals("token", ApiHostPlan.explainRailway("Not Authorized"))
        assertEquals("workspace", ApiHostPlan.explainRailway("You must specify a workspaceId"))
        assertNull(ApiHostPlan.explainRailway("something else"))
    }
}
