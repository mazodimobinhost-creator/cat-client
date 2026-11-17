package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** beta239 — Hysteria v1 + Hysteria2 pin/port hopping, and the Telegram (MTProto) proxy finder. */
class Beta239ProtocolsTelegramTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) File(it, "app/src/main") else File(it, "src/main") }

    private fun src(name: String) = File(root(), "java/com/cat/client/$name").readText()

    private val pinColons = (0 until 32).joinToString(":") { "%02X".format(it * 7 and 0xff) }
    private val pinHex = pinColons.replace(":", "").lowercase()

    @Test fun hysteriaV1LinksAreNoLongerDropped() {
        val link = "hysteria://203.0.113.9:443?protocol=udp&auth=s3cret&peer=hy.example.com&insecure=1&upmbps=20&downmbps=80&alpn=hysteria&obfsParam=xyz#HY1"
        val p = SubConvConverter.convert(link).single()
        assertEquals("hysteria", p.optString("type"))
        assertEquals("203.0.113.9", p.optString("server"))
        assertEquals(443, p.optInt("port"))
        assertEquals("s3cret", p.optString("auth-str"))
        assertEquals("hy.example.com", p.optString("sni"))
        assertEquals("20 Mbps", p.optString("up"))
        assertEquals("80 Mbps", p.optString("down"))
        assertEquals("xyz", p.optString("obfs"))
        assertEquals("udp", p.optString("protocol"))
        assertEquals("hysteria", p.optJSONArray("alpn")!!.getString(0))
        // certificate checking stays on for imported proxies even when the link asks for insecure=1
        assertFalse(p.optBoolean("skip-cert-verify", true))
        val yaml = MihomoLinkConfigBuilder.build(listOf(p))
        assertTrue(Regex("type: '?hysteria'?\\s").containsMatchIn(yaml))
    }

    @Test fun hysteriaV1WithoutSpeedsGetsMihomosRequiredDefaults() {
        val p = SubConvConverter.convert("hysteria://198.51.100.4:8443?auth=a#x").single()
        assertEquals("50 Mbps", p.optString("up"))
        assertEquals("100 Mbps", p.optString("down"))
    }

    @Test fun hysteria2KeepsPinAndPortHopping() {
        val a = SubConvConverter.convert("hysteria2://pw@203.0.113.7:443?sni=h.example.com&pinSHA256=$pinColons&mport=20000-30000&alpn=h3#A").single()
        assertEquals(pinHex, a.optString("fingerprint"))
        assertEquals("20000-30000", a.optString("ports"))
        assertEquals("h3", a.optJSONArray("alpn")!!.getString(0))
        assertFalse(a.optBoolean("skip-cert-verify", true))
        val b = SubConvConverter.convert("hy2://pw@203.0.113.7:443,20000-30000/?sni=h.example.com#B").single()
        assertEquals(443, b.optInt("port"))
        assertEquals("443,20000-30000", b.optString("ports"))
        val bad = SubConvConverter.convert("hy2://pw@203.0.113.7:443?pinSHA256=nothex&mport=9-99999#C").single()
        assertFalse(bad.has("fingerprint"))
        assertFalse(bad.has("ports"))
        val ipv6 = SubConvConverter.convert("hy2://pw@[2001:db8::1]:443?sni=h.example.com#D").single()
        assertEquals(443, ipv6.optInt("port"))
    }

    @Test fun hysteriaRoundTripsThroughTheShareLinkBuilder() {
        val p = SubConvConverter.convert("hysteria://203.0.113.9:443?protocol=udp&auth=s3cret&peer=hy.example.com&upmbps=20&downmbps=80&mport=1000-2000#HY1").single()
        val map = HashMap<String, Any?>()
        p.keys().forEach { k -> map[k] = p.get(k).let { v -> if (v is org.json.JSONArray) (0 until v.length()).map { v.get(it) } else v } }
        val back = MihomoShareLink.build(map)!!
        assertTrue(back.startsWith("hysteria://203.0.113.9:443?"))
        assertTrue(back.contains("auth=s3cret") && back.contains("peer=hy.example.com") && back.contains("mport=1000-2000"))
        assertEquals("hysteria", SubConvConverter.convert(back).single().optString("type"))
        assertTrue(src("FreeConfigs.kt").contains("\"hysteria://\""))
    }

    /* ------------------------------------------------------------- Telegram proxy finder */

    private val domain = "www.microsoft.com"
    private val key = ByteArray(16) { (it * 13 + 7).toByte() }
    private val secret = "ee" + (key + domain.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun parsesTgAndTmeLinksAndSecretKinds() {
        val text = """x tg://proxy?server=1.2.3.4&port=443&secret=$secret
            <a href="https://t.me/proxy?server=1.2.3.4&amp;port=443&amp;secret=$secret">dup</a>
            tg://proxy?server=5.6.7.8&port=443&secret=dd0123456789abcdef0123456789abcdef
            tg://proxy?server=9.9.9.9&port=99999&secret=$secret
            tg://proxy?server=8.8.8.8&port=443&secret=0123456789abcdef0123456789abcdef
            tg://proxy?server=7.7.7.7&port=443&secret=zz"""
        val list = TelegramProxyFinder.parseLinks(text)
        assertEquals(3, list.size)
        assertEquals(TelegramProxyFinder.Kind.FAKE_TLS, list[0].kind)
        assertEquals(TelegramProxyFinder.Kind.SECURE, list[1].kind)
        assertEquals(TelegramProxyFinder.Kind.CLASSIC, list[2].kind)
        assertEquals(domain, TelegramProxyFinder.fakeTlsParts(secret)!!.second)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(TelegramProxyFinder.secretBytes(secret)!!)
        assertEquals(domain, TelegramProxyFinder.fakeTlsParts(b64)!!.second)
        assertNull(TelegramProxyFinder.fakeTlsParts("dd0123456789abcdef0123456789abcdef"))
        assertTrue(TelegramProxyFinder.tgLink(list[0]).startsWith("tg://proxy?server=1.2.3.4&port=443&secret=ee"))
        assertTrue(TelegramProxyFinder.shareLink(list[0]).startsWith("https://t.me/proxy?"))
    }

    private fun hmac(vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    /** Minimal FakeTLS proxy: validates the client digest + timestamp, answers with a signed ServerHello. */
    private fun fakeProxy(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    c.use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val head = ByteArray(5).also { input.readFully(it) }
                        val len = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
                        val hello = head + ByteArray(len).also { input.readFully(it) }
                        val digest = hello.copyOfRange(11, 43)
                        val zeroed = hello.copyOf().also { java.util.Arrays.fill(it, 11, 43, 0) }
                        val x = hmac(zeroed).mapIndexed { i, b -> (b.toInt() xor digest[i].toInt()).toByte() }
                        if (x.take(28).any { it != 0.toByte() }) return@use
                        val srv = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0x20) + ByteArray(32) + byteArrayOf(0x13, 0x01, 0)
                        val out = ByteArrayOutputStream()
                        out.write(byteArrayOf(0x16, 3, 3, ((srv.size + 4) shr 8).toByte(), (srv.size + 4).toByte(), 2, 0, (srv.size shr 8).toByte(), srv.size.toByte()))
                        out.write(srv)
                        out.write(byteArrayOf(0x14, 3, 3, 0, 1, 1, 0x17, 3, 3, 0, 64))
                        out.write(ByteArray(64))
                        val pkt = out.toByteArray()
                        System.arraycopy(hmac(digest, pkt), 0, pkt, 11, 32)
                        s.getOutputStream().write(pkt)
                        s.getOutputStream().flush()
                        Thread.sleep(200)
                    }
                }
            }
        }
        return server
    }

    @Test fun fakeTlsHandshakeIsReallyVerified() {
        val (hello, _) = TelegramProxyFinder.buildClientHello(key, domain, System.currentTimeMillis() / 1000)
        assertEquals(517, hello.size)
        fakeProxy().use { server ->
            val good = TelegramProxyFinder.Proxy("127.0.0.1", server.localPort, secret)
            val ok = TelegramProxyFinder.probe(good, 3_000) {}
            assertEquals(TelegramProxyFinder.Verdict.VERIFIED, ok.verdict)
            val wrongSecret = "ee" + ByteArray(16) { 1 }.joinToString("") { "%02x".format(it) } + domain.toByteArray().joinToString("") { "%02x".format(it) }
            val bad = TelegramProxyFinder.probe(good.copy(secret = wrongSecret), 3_000) {}
            assertEquals(TelegramProxyFinder.Verdict.FAILED, bad.verdict)
            val sorted = TelegramProxyFinder.scan(listOf(good.copy(secret = wrongSecret), good), protect = {})
            assertEquals(TelegramProxyFinder.Verdict.VERIFIED, sorted.first().verdict)
        }
    }

    @Test fun finderIsReachableFromTheToolsPage() {
        val tools = src("AppToolsController.kt")
        assertTrue(tools.contains("{ showTelegramProxyFinder() }"))
        assertTrue(tools.contains("protectOutboundSocket(it)"))
        assertNotNull(TelegramProxyFinder.MIRRORS.firstOrNull { it.contains("jsdelivr") })
    }

    /* ------------------------------------------------------------- subscription provider headers */

    private fun b64(s: String) = "base64:" + java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun providerHeadersAreParsedAndSanitised() {
        val info = SubscriptionProviderInfoPolicy.parse(
            mapOf(
                "Profile-Title" to b64("سرور طلایی"),
                "profile-update-interval" to "12",
                "announce" to b64("تمدید تا جمعه\u0007\n\n\n\nتخفیف"),
                "support-url" to "https://t.me/support_bot",
                "profile-web-page-url" to "javascript:alert(1)",
            ),
        )!!
        assertEquals("سرور طلایی", info.title)
        assertEquals(12, info.updateIntervalHours)
        assertEquals("تمدید تا جمعه\n\nتخفیف", info.announce)
        assertEquals("https://t.me/support_bot", info.supportUrl)
        assertEquals("", info.webPageUrl)
        assertEquals("", SubscriptionProviderInfoPolicy.safeUrl("intent://x#Intent;end"))
        assertEquals("", SubscriptionProviderInfoPolicy.safeUrl("http://plain.example"))
        assertEquals("tg://resolve?domain=x", SubscriptionProviderInfoPolicy.safeUrl("tg://resolve?domain=x"))
        assertNull(SubscriptionProviderInfoPolicy.parse(mapOf("x" to "y", "profile-update-interval" to "0")))
        assertEquals(64, SubscriptionProviderInfoPolicy.parse(mapOf("profile-title" to "x".repeat(200)))!!.title.length)
        assertEquals("AB", SubscriptionProviderInfoPolicy.parse(mapOf("profile-title" to "A\u202EB"))!!.title)
        val card = SubscriptionProviderInfoPolicy.cardLines(info, "My sub")
        assertTrue(card.contains("🏷 سرور طلایی") && card.contains("📢 "))
        assertEquals("", SubscriptionProviderInfoPolicy.cardLines(info.copy(announce = ""), "سرور طلایی"))
    }

    @Test fun providerRefreshIntervalIsHonoured() {
        val h = 3_600_000L
        assertTrue(SubscriptionProviderInfoPolicy.shouldRefresh(0, 12, 100 * h))
        assertTrue(SubscriptionProviderInfoPolicy.shouldRefresh(10 * h, null, 11 * h))
        assertFalse(SubscriptionProviderInfoPolicy.shouldRefresh(10 * h, 12, 16 * h))
        assertTrue(SubscriptionProviderInfoPolicy.shouldRefresh(10 * h, 12, 22 * h - 300_000))
    }

    @Test fun providerHeadersAreFetchedStoredAndShown() {
        assertTrue(src("SubscriptionSnapshot.kt").contains("SubscriptionProviderInfoPolicy.HEADERS.mapNotNull"))
        assertTrue(src("UserSubscriptions.kt").contains("SubscriptionProviderInfoStore(appContext).save(input, info)"))
        val subs = src("MainActivitySubscriptions.kt")
        assertTrue(subs.contains("SubscriptionProviderInfoPolicy.cardLines(provider, item.name)"))
        assertTrue(subs.contains("R.string.subscription_action_support"))
        assertTrue(src("SubscriptionAutoRefresh.kt").contains("SubscriptionProviderInfoPolicy.shouldRefresh("))
    }
}
