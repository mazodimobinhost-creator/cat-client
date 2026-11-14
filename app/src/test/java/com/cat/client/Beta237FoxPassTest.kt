package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta237 — Cat Fox VPN checked against Firefox's own IP Protection code:
 *  - the ProxyPass is renewed once, in the background, minutes before it ends (Firefox: ProxyPass.rotationTimePoint),
 *    not by every tunnel in its last minute;
 *  - Guardian 401 → fresh sign-in token, 403 → enroll, each once (GuardianClient.toError); enrollment uses
 *    `experiment=alpha`;
 *  - a split ClientHello that breaks the handshake at once is retried unsplit (Android 8–10 layered TLS);
 *  - after a network switch the pooled HTTP/2 connections are dropped;
 *  - plain-HTTP proxy requests are forwarded to port 80 instead of being tunnelled to 443.
 */
class Beta237FoxPassTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) File(it, "app/src/main") else File(it, "src/main") }

    private fun src(name: String) = File(root(), "java/com/cat/client/$name").readText()

    @Test
    fun passStateRenewsMinutesBeforeTheEnd() {
        val now = 1_000_000L
        assertEquals(FenixPassPolicy.State.NONE, FenixPassPolicy.state("", now + 3600, now))
        assertEquals(FenixPassPolicy.State.FRESH, FenixPassPolicy.state("jwt", now + 3600, now))
        assertEquals(FenixPassPolicy.State.FRESH, FenixPassPolicy.state("jwt", null, now))
        assertEquals(FenixPassPolicy.State.FRESH, FenixPassPolicy.state("jwt", now + FenixPassPolicy.ROTATE_BEFORE_SEC + 1, now))
        assertEquals(FenixPassPolicy.State.ROTATE_SOON, FenixPassPolicy.state("jwt", now + FenixPassPolicy.ROTATE_BEFORE_SEC, now))
        assertEquals(FenixPassPolicy.State.ROTATE_SOON, FenixPassPolicy.state("jwt", now + 120, now))
        assertEquals(FenixPassPolicy.State.EXPIRED, FenixPassPolicy.state("jwt", now + FenixPassPolicy.HARD_MARGIN_SEC, now))
        assertEquals(FenixPassPolicy.State.EXPIRED, FenixPassPolicy.state("jwt", now - 5, now))
        // Firefox renews 2 minutes before the end; Cat must start no later than that.
        assertTrue(FenixPassPolicy.ROTATE_BEFORE_SEC >= 120)
        assertTrue(FenixPassPolicy.usable(FenixPassPolicy.State.ROTATE_SOON))
        assertFalse(FenixPassPolicy.usable(FenixPassPolicy.State.EXPIRED))
        assertFalse(FenixPassPolicy.usable(FenixPassPolicy.State.NONE))
    }

    @Test
    fun guardianStatusesFollowFirefox() {
        val p = FenixPassPolicy
        assertEquals(FenixPassPolicy.Next.USE, p.afterTokenStatus(200, renewedLogin = false, enrolled = false))
        assertEquals(FenixPassPolicy.Next.RENEW_LOGIN, p.afterTokenStatus(401, renewedLogin = false, enrolled = false))
        assertEquals(FenixPassPolicy.Next.STOP, p.afterTokenStatus(401, renewedLogin = true, enrolled = false))
        assertEquals(FenixPassPolicy.Next.ENROLL, p.afterTokenStatus(403, renewedLogin = true, enrolled = false))
        assertEquals(FenixPassPolicy.Next.STOP, p.afterTokenStatus(403, renewedLogin = true, enrolled = true))
        for (s in listOf(429, 451, 500, 503, 418)) {
            assertEquals("$s", FenixPassPolicy.Next.STOP, p.afterTokenStatus(s, renewedLogin = false, enrolled = false))
        }
    }

    @Test
    fun splitHandshakeFallsBackOnlyForImmediateFailures() {
        assertTrue(FenixPassPolicy.retryWithoutSplit(true, NullPointerException("fd == null")))
        assertTrue(FenixPassPolicy.retryWithoutSplit(true, javax.net.ssl.SSLHandshakeException("bad record")))
        assertTrue(FenixPassPolicy.retryWithoutSplit(true, java.net.SocketException("Socket is closed")))
        assertFalse(FenixPassPolicy.retryWithoutSplit(true, java.net.SocketTimeoutException("Read timed out")))
        assertFalse(FenixPassPolicy.retryWithoutSplit(false, java.net.SocketException("reset")))
    }

    @Test
    fun networkSwitchDropsConnectionsOnlyForARealChange() {
        val w = FoxNetworkReset()
        assertFalse("first report", w.onNetwork("wlan0|30|true|false|false", bridgeRunning = true))
        assertFalse("same network", w.onNetwork("wlan0|30|true|false|false", bridgeRunning = true))
        assertFalse("no network in between", w.onNetwork("none", bridgeRunning = true))
        assertTrue("Wi-Fi → mobile through «none»", w.onNetwork("rmnet0|12|false|true|false", bridgeRunning = true))
        assertFalse("bridge off", w.onNetwork("wlan0|30|true|false|false", bridgeRunning = false))
        assertEquals("wlan0|30|true|false|false", w.lastRealKey)
    }

    @Test
    fun httpProxyRequestsAreParsedLikeAProxy() {
        assertEquals(FoxHttpProxyRequest.Plan("example.com", 443, "", true), FoxHttpProxyRequest.parse("CONNECT", "example.com:443"))
        assertEquals(FoxHttpProxyRequest.Plan("example.com", 443, "", true), FoxHttpProxyRequest.parse("CONNECT", "example.com"))
        assertEquals(FoxHttpProxyRequest.Plan("2001:db8::1", 8443, "", true), FoxHttpProxyRequest.parse("CONNECT", "[2001:db8::1]:8443"))
        assertEquals(FoxHttpProxyRequest.Plan("example.com", 80, "/a/b?x=1", false), FoxHttpProxyRequest.parse("GET", "http://example.com/a/b?x=1"))
        assertEquals(FoxHttpProxyRequest.Plan("example.com", 8080, "/", false), FoxHttpProxyRequest.parse("GET", "http://example.com:8080"))
        assertNull(FoxHttpProxyRequest.parse("GET", "http:///nohost"))
        assertNull(FoxHttpProxyRequest.parse("CONNECT", "example.com:99999"))
        val req = String(
            FoxHttpProxyRequest.originRequest("GET", "/x", "HTTP/1.1", listOf("Host: example.com", "Proxy-Connection: keep-alive", "Accept: */*")),
            Charsets.ISO_8859_1,
        )
        assertEquals("GET /x HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\n\r\n", req)
    }

    @Test
    fun engineIsWiredToThePolicies() {
        val fox = src("FirefoxVpnEngine.kt")
        // one renewal for everyone + a background renewer started with the bridge
        assertTrue(fox.contains("passRenewLock.tryLock("))
        assertTrue(fox.contains("private fun startPassRenewer()"))
        assertTrue(Regex("""startPassRenewer\(\)\s*\n\s*server\.localPort""").containsMatchIn(fox))
        assertTrue(fox.contains("FenixPassPolicy.State.ROTATE_SOON -> {"))
        assertTrue(fox.contains("rotateOrRefreshAccountPassOnFailure(pass, e.status)"))
        // Guardian rules and Firefox's enrollment experiment
        assertTrue(fox.contains("FenixPassPolicy.afterTokenStatus("))
        assertTrue(fox.contains("const val GUARDIAN_EXPERIMENT = \"alpha\""))
        assertFalse(fox.contains("experiment=ip-protection"))
        // the bridge renews an old pass before the VPN is up
        val bridge = fox.substringAfter("fun startLocalBridge(").substringBefore("return startOrUpdateLocalFenixProxy(")
        assertTrue(bridge.contains("refreshAndActivateAccountPass(context, acc)"))
        // unsplit retry for node TLS and for Mozilla API requests, with host-name check
        assertTrue(fox.contains("unsplitTlsNodes += key"))
        assertTrue(fox.contains("fragmentTls = false,"))
        val api = fox.substringAfter("private fun executeOverFragmentedTlsSocket(").substringBefore("ssl.use {")
        assertTrue(api.contains("endpointIdentificationAlgorithm = \"HTTPS\""))
        // HTTP proxy requests
        assertTrue(fox.contains("FoxHttpProxyRequest.parse("))
        assertFalse(fox.contains("strict `http/1.1` ALPN on Fastly"))
        // network switch
        val net = src("CatClientVpnServiceNetworkChange.kt")
        assertTrue(net.contains("FirefoxVpnEngine.networkWatch.onNetwork("))
        assertTrue(net.contains("FirefoxVpnEngine.resetUpstreamSessions(\"network changed\")"))
    }
}
