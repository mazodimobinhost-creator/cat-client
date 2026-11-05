package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta238 — Cat Fox VPN sign-in through Mozilla's page via the Firefox WebChannel: the session is taken only once it is
 * confirmed (e-mail code / 2FA), the token request is sent by the page itself (no 406 from the anti-bot check), and the
 * «I signed in» button no longer closes the dialog it is checking.
 */
class Beta238FoxWebChannelTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) File(it, "app/src/main") else File(it, "src/main") }

    private fun src(name: String) = File(root(), "java/com/cat/client/$name").readText()

    @Test
    fun sessionTokensAndHostsAreStrict() {
        assertTrue(FoxWebChannel.isSessionToken("a".repeat(64)))
        assertTrue(FoxWebChannel.isSessionToken("0123456789abcdef".repeat(4)))
        assertFalse(FoxWebChannel.isSessionToken("a".repeat(63)))
        assertFalse(FoxWebChannel.isSessionToken("g".repeat(64)))
        assertFalse(FoxWebChannel.isSessionToken(null))
        assertTrue(FoxWebChannel.isTrustedHost("accounts.firefox.com"))
        assertFalse(FoxWebChannel.isTrustedHost("accounts.firefox.com.evil.example"))
        assertFalse(FoxWebChannel.isTrustedHost("firefox.com"))
    }

    @Test
    fun pendingCodeIsNotAVerifiedSession() {
        assertTrue(FoxWebChannel.isVerified(true, null))
        assertFalse(FoxWebChannel.isVerified(false, null))
        assertFalse(FoxWebChannel.isVerified(null, "login"))
        assertTrue(FoxWebChannel.isVerified(null, null))
        assertTrue(FoxWebChannel.isUnconfirmedAnswer(400, """{"code":400,"errno":104,"message":"Unconfirmed session"}"""))
        assertTrue(FoxWebChannel.isUnconfirmedAnswer(400, """{"errno":138,"message":"Unverified session"}"""))
        assertFalse(FoxWebChannel.isUnconfirmedAnswer(406, "Not Acceptable"))
        assertFalse(FoxWebChannel.isUnconfirmedAnswer(200, "{}"))
    }

    @Test
    fun successPagesAndStartUrl() {
        assertTrue(FoxWebChannel.isSuccessUrl("https://accounts.firefox.com/connect_another_device?showSuccessMessage=true"))
        assertTrue(FoxWebChannel.isSuccessUrl("https://accounts.firefox.com/settings"))
        assertFalse(FoxWebChannel.isSuccessUrl("https://accounts.firefox.com/signin_token_code"))
        assertFalse(FoxWebChannel.isSuccessUrl("https://evil.example/settings"))
        assertEquals("https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email", FoxWebChannel.START_URL)
        assertTrue(FoxWebChannel.firefoxAndroidUserAgent("13").contains("Android 13; Mobile; rv:152.0"))
        assertTrue(FoxWebChannel.firefoxAndroidUserAgent("").contains("Firefox/152.0"))
    }

    @Test
    fun loginDialogUsesTheChannelAndKeepsTheFallback() {
        val ui = src("MainActivityFox.kt")
        assertTrue(ui.contains("webView.addJavascriptInterface(channel, \"catFxaChannel\")"))
        assertTrue(ui.contains("webView.loadUrl(FoxWebChannel.START_URL)"))
        assertTrue(ui.contains("FirefoxVpnEngine.extractVerifiedSessionFromWebViewStorageJson(rawValue)"))
        assertTrue(ui.contains("channel.adoptSession(pair.first, pair.second)"))
        assertTrue(ui.contains("dialogRef.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener"))
        assertFalse(ui.contains("webView.loadUrl(\"https://accounts.firefox.com/\")"))
        val ch = src("FoxWebChannel.kt")
        assertTrue(ch.contains("if (host !== 'accounts.firefox.com') return;"))
        assertTrue(ch.contains("fetch('https://api.accounts.firefox.com/v1/oauth/token'"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("fun webViewOAuthRequest(sessionTokenHex: String): Pair<String, String>"))
        assertTrue(engine.contains("completeLoginWithOAuthTokens(context, email, accessToken, refreshToken, sessionTokenHex)"))
    }

    @Test
    fun newGitHostsHaveTheirOwnProfile() {
        for (kind in listOf(NodeHostPanel.ZEROPS, NodeHostPanel.SEENODE, NodeHostPanel.HOSTIM)) {
            assertTrue(kind, NodeHostPanel.isGitHost(kind))
            assertTrue(kind, kind in NodeHostPanel.HOST_KINDS)
            val p = GitHostProfiles.of(kind)!!
            assertTrue(p.site.startsWith("https://") && p.signup.startsWith("https://"))
            assertTrue(p.appEn.contains("8080") && p.appFa.contains("8080"))
            assertTrue(NodeHostPanel.configJson("u", "p", noDisk = true, host = kind).contains("\"CAT_HOST\": \"$kind\""))
            assertTrue(HostRegions.choices(kind).isNotEmpty())
        }
        assertTrue(NodeHostPanel.isGitHost(NodeHostPanel.LUCITY) && NodeHostPanel.isGitHost(NodeHostPanel.SHIPRR))
        assertFalse(NodeHostPanel.isGitHost(NodeHostPanel.KATABUMP) || NodeHostPanel.isGitHost(NodeHostPanel.RAILWAY))
        // Zerops reads zerops.yml (HTTP port 8080, node index.js); Hostim builds from a Dockerfile; Seenode needs neither.
        val z = GitHostProfiles.of(NodeHostPanel.ZEROPS)!!.extraFiles["zerops.yml"]!!
        assertTrue(z.contains("- setup: app") && z.contains("- port: 8080") && z.contains("httpSupport: true") && z.contains("start: node index.js"))
        val d = GitHostProfiles.of(NodeHostPanel.HOSTIM)!!.extraFiles["Dockerfile"]!!
        assertTrue(d.contains("FROM node:22-alpine") && d.contains("EXPOSE 8080") && d.contains("CMD [\"node\", \"index.js\"]"))
        assertTrue(GitHostProfiles.of(NodeHostPanel.SEENODE)!!.extraFiles.isEmpty())
        assertTrue(GitHostProfiles.of(NodeHostPanel.SEENODE)!!.appEn.contains("from 80 to 8080"))
        assertEquals(null, GitHostProfiles.of("slipway"))
        val cloud = src("MainActivityCloud.kt")
        assertTrue(cloud.contains("NodeHostPanel.ZEROPS, R.string.cloud_zerops_title"))
        assertTrue(cloud.contains("NodeHostPanel.HOSTIM, R.string.cloud_hostim_title"))
        val ctl = src("NodeHostController.kt")
        assertTrue(ctl.contains("GitHostProfiles.of(kind)?.let { p -> for ((f, text) in p.extraFiles) out[f] = text.toByteArray() }"))
        assertTrue(ctl.contains("NodeHostPanel.ZEROPS, NodeHostPanel.SEENODE, NodeHostPanel.HOSTIM -> gitProfileGuide(kind)"))
    }

    @Test
    fun encryptedResolverSpecsAndAnswers() {
        val doh = WhiteDnsToolkit.parseEncryptedResolver("https://dns.nextdns.io:8443/abc123")!!
        assertEquals("doh", doh.protocol); assertEquals(8443, doh.port); assertEquals("/abc123", doh.path)
        assertEquals("/dns-query", WhiteDnsToolkit.parseEncryptedResolver("https://1.1.1.1")!!.path)
        val dot = WhiteDnsToolkit.parseEncryptedResolver("tls://dns.google")!!
        assertEquals("dot", dot.protocol); assertEquals(853, dot.port)
        assertEquals(853, WhiteDnsToolkit.parseEncryptedResolver("9.9.9.9")!!.port)
        assertEquals(null, WhiteDnsToolkit.parseEncryptedResolver("quic://x"))
        assertEquals(null, WhiteDnsToolkit.parseEncryptedResolver("  # note"))
        for (line in WhiteDnsToolkit.DEFAULT_ENCRYPTED_RESOLVERS) assertEquals(line, WhiteDnsToolkit.parseEncryptedResolver(line)!!.label)

        val q = WhiteDnsToolkit.buildDnsQuery("www.youtube.com", 1, false)
        val hdr = q.copyOfRange(0, 12).also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 2 }
        val cname = byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0.toByte(), 12)
        val a = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 10, 34, 35)
        assertEquals(listOf("10.10.34.35"), WhiteDnsToolkit.parseAddressAnswers(hdr + q.copyOfRange(12, q.size) + cname + a))
        assertTrue(WhiteDnsToolkit.parseAddressAnswers(ByteArray(5)).isEmpty())
        val tk = src("WhiteDnsToolkit.kt")
        assertTrue("certificate + name checked inside the handshake", tk.contains("endpointIdentificationAlgorithm = \"HTTPS\""))
        assertTrue(src("AppToolsController.kt").contains("{ showEncryptedResolverScan() }"))
    }
}
