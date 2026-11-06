package com.cat.client

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** beta247: the Vercel token build reaches api.vercel.com by direct → Cat's tunnel → the owner's Cloudflare panel relay. */
class Beta247VercelRoutesTest {
    private val uuid = "41111111-2222-4333-8444-555555555555"

    @Test
    fun cleanTokenStripsWhatPeoplePaste() {
        assertEquals("abcDEF123", VercelRoutePlanner.cleanToken("  abcDEF123 \n"))
        assertEquals("abcDEF123", VercelRoutePlanner.cleanToken("Bearer abcDEF123"))
        assertEquals("abcDEF123", VercelRoutePlanner.cleanToken("\"abcDEF123\""))
        assertEquals("abcDEF123", VercelRoutePlanner.cleanToken("abc DEF\u200c123"))
        assertEquals("abcDEF123", VercelRoutePlanner.cleanToken("“bearer abcDEF123”"))
    }

    @Test
    fun panelRouteNeedsHttpsHostAndUuid() {
        val r = VercelRoutePlanner.panelRoute("https://Cat-X.user.workers.dev/panel", uuid.uppercase())
        assertNotNull(r)
        assertEquals("https://cat-x.user.workers.dev/vrelay/$uuid", r!!.base)
        assertEquals("cat-x.user.workers.dev", r.panelHost)
        assertNull(VercelRoutePlanner.panelRoute("http://cat-x.user.workers.dev", uuid))
        assertNull(VercelRoutePlanner.panelRoute("https://cat-x.user.workers.dev", "not-a-uuid"))
        assertNull(VercelRoutePlanner.panelRoute("https://1.2.3.4:8443", uuid))
        assertNull(VercelRoutePlanner.panelRoute("https://", uuid))
    }

    @Test
    fun routeOrderDirectTunnelThenPanelsWorkersFirst() {
        val routes = VercelRoutePlanner.routes(
            2080,
            listOf(
                "https://p1.vercel.app" to uuid,
                "https://a.x.workers.dev" to uuid,
                "https://a.x.workers.dev/other" to uuid,
                "https://bad" to uuid,
                "https://b.example.com" to uuid,
                "https://c.example.com" to uuid,
            ),
        )
        assertEquals(VercelRouteKind.Direct, routes[0].kind)
        assertEquals(VercelRouteKind.Tunnel, routes[1].kind)
        assertEquals(2080, routes[1].proxyPort)
        assertEquals(listOf("a.x.workers.dev", "b.example.com", "c.example.com"), routes.drop(2).map { it.panelHost })
        assertEquals(1, VercelRoutePlanner.routes(null, emptyList()).size)
        assertEquals(VercelRouteKind.Direct, VercelRoutePlanner.routes(0, emptyList()).single().kind)
    }

    @Test
    fun foreignAnswersAreToldApartFromVercelErrors() {
        // A real Vercel error is JSON: not foreign (it is shown to the user as-is).
        assertFalse(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Direct, 403, null, "application/json", "{\"error\":{}}"))
        assertFalse(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Direct, 200, null, "application/json", "{}"))
        // A filter / block page is HTML.
        assertTrue(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Direct, 403, null, "text/html", "<html>blocked</html>"))
        assertTrue(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Direct, 451, null, "application/json", "{}"))
        // An older panel answers /vrelay with its own page and no relay mark.
        assertTrue(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Panel, 404, null, "text/plain", "not found"))
        assertTrue(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Panel, 200, null, "text/html", "<html>"))
        assertTrue(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Panel, 502, "1", "text/plain", "relay: timeout"))
        assertFalse(VercelRoutePlanner.isForeignAnswer(VercelRouteKind.Panel, 401, "1", "application/json", "{\"error\":{}}"))
    }

    @Test
    fun failoverRules() {
        assertTrue(VercelRoutePlanner.failover(ConnectException("refused"), "POST"))
        assertTrue(VercelRoutePlanner.failover(SocketTimeoutException("connect timed out"), "POST"))
        assertTrue(VercelRoutePlanner.failover(SocketTimeoutException("Read timed out"), "GET"))
        // A POST that may already have landed is never replayed on another route (no second project).
        assertFalse(VercelRoutePlanner.failover(SocketTimeoutException("Read timed out"), "POST"))
        assertFalse(VercelRoutePlanner.failover(VercelApiException("Not authorized", 403), "GET"))
        assertTrue(VercelRoutePlanner.failover(VercelForeignAnswerException("HTTP 404"), "POST"))
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun server(stale: AtomicInteger, good: AtomicInteger): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val path = ex.requestURI.path
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val (code, body, mark) = when {
                path.startsWith("/old/") -> { stale.incrementAndGet(); Triple(404, "not found", false) }
                path == "/vrelay/$uuid/v2/user" && auth == "Bearer tok_123456789" -> { good.incrementAndGet(); Triple(200, "{\"user\":{\"username\":\"owner\"}}", true) }
                path == "/vrelay/$uuid/v11/projects" -> { good.incrementAndGet(); Triple(403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"Not authorized\"}}", true) }
                else -> Triple(404, "{\"error\":{\"message\":\"unexpected $path\"}}", true)
            }
            val bytes = body.toByteArray()
            if (mark) ex.responseHeaders.add("x-cat-vrelay", "1")
            ex.responseHeaders.add("Content-Type", if (body.startsWith("{")) "application/json" else "text/plain")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return s
    }

    @Test
    fun liveFailoverReachesTheWorkingRelayAndSticksToIt() {
        val stale = AtomicInteger()
        val good = AtomicInteger()
        val s = server(stale, good)
        try {
            val port = s.address.port
            val routes = listOf(
                VercelRoute(VercelRouteKind.Direct, "http://127.0.0.1:${closedPort()}"),
                VercelRoute(VercelRouteKind.Panel, "http://127.0.0.1:$port/old/$uuid", panelHost = "old.example.com"),
                VercelRoute(VercelRouteKind.Panel, "http://127.0.0.1:$port/vrelay/$uuid", panelHost = "new.example.com"),
            )
            val api = VercelApi("Bearer tok_123456789".removePrefix("Bearer ")) { routes }
            assertEquals("owner", api.user().optString("username"))
            assertEquals("new.example.com", api.activeRoute.panelHost)
            assertEquals(1, stale.get())
            // Sticky: the next call goes straight to the working relay.
            api.user()
            assertEquals(1, stale.get())
            assertEquals(2, good.get())
            // A real Vercel error through a working route is shown as-is, not "unreachable".
            try {
                api.createProject("cat-x")
                fail("expected a Vercel 403")
            } catch (e: VercelApiException) {
                assertEquals(403, e.status)
                assertTrue(e.message.orEmpty().contains("forbidden"))
            }
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun everyRouteFailingGivesOneClearReport() {
        val stale = AtomicInteger()
        val s = server(stale, AtomicInteger())
        try {
            val routes = listOf(
                VercelRoute(VercelRouteKind.Direct, "http://127.0.0.1:${closedPort()}"),
                VercelRoute(VercelRouteKind.Panel, "http://127.0.0.1:${s.address.port}/old/$uuid", panelHost = "old.example.com"),
            )
            try {
                VercelApi("tok_123456789") { routes }.user()
                fail("expected VercelRouteException")
            } catch (e: VercelRouteException) {
                assertEquals(listOf(VercelRouteKind.Direct, VercelRouteKind.Panel), e.attempts.map { it.first.kind })
                assertTrue(e.attempts[0].second.contains("ConnectException"))
                assertTrue(e.attempts[1].second.contains("HTTP 404"))
            }
        } finally {
            s.stop(0)
        }
    }
}
