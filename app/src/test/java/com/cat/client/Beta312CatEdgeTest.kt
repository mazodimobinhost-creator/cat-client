package com.cat.client

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta312 — Cat Edge: signed list + RSA session + VMess Cat already runs. */
class Beta312CatEdgeTest {

    @Test
    fun versionIsBeta312() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 380"))
        assertTrue(gradle.contains("1.10.0-beta313"))
    }

    @Test
    fun rsaSpkiAndTrustedKeysLoad() {
        val der = CatEdgePlanner.rsaSpki()
        assertEquals(294, der.size)
        CatEdgePlanner.TRUSTED_KEYS.forEach { (_, b64) ->
            assertTrue(CatEdgePlanner.b64(b64).size in 80..120)
        }
        assertTrue(CatEdgePlanner.LIST_URLS.size >= 3)
        assertTrue(CatEdgePlanner.LIST_URLS.any { it.contains("jsdelivr") })
    }

    @Test
    fun authResponseRoundTripYieldsUuid() {
        val auth = CatEdgePlanner.makeAuth(nowMs = 1_700_000_000_000L)
        assertTrue(auth.authorization.isNotBlank())
        assertFalse(auth.authorization.contains("="))
        val uuid = "11111111-2222-3333-4444-555555555555"
        val cipher = CatEdgePlanner.encryptAuthResponse(
            JSONObject().put("uuid", uuid).put("path", "/edge").put("token", "tok"),
            auth,
        )
        val session = CatEdgePlanner.decryptAuth(cipher, auth)
        assertEquals(uuid, session.uuid)
        assertEquals("/edge", session.path)
        assertEquals("tok", session.token)
    }

    @Test
    fun envelopeRoundTripWithTestKeyAndNodesFromIrAndGlobal() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val pubB64 = CatEdgePlanner.b64out(pair.public.encoded)
        val parameters = JSONObject()
            .put("server_ir_aa", """{"profile":"ir","server":"zuemy.wltfszy.com","port":443,"headers":{"Host":"AUTO"}}""")
            .put("server_global_bb", """{"profile":"global","server":"s1.example.com","port":443}""")
            .put("server_ir_badport", """{"profile":"ir","server":"bad.example.com","port":80}""")
            .put("falcon_template", """{"falconVersion":1,"port":443}""")
        val envelope = CatEdgePlanner.encodeEnvelope(parameters, "testkey", "9", 1793910585L) { data ->
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(pair.private)
            sig.update(data)
            sig.sign()
        }
        val (_, meta) = CatEdgePlanner.verifyEnvelope(envelope, mapOf("testkey" to pubB64))
        val nodes = CatEdgePlanner.nodesFrom(meta.parameters)
        assertEquals(setOf("zuemy.wltfszy.com", "s1.example.com"), nodes.map { it.host }.toSet())
        assertFalse(nodes.any { it.port == 80 || it.port == 1080 })
        assertEquals("ir", nodes.first { it.host.startsWith("zuemy") }.profile)
        assertEquals("public", nodes.first().kind)
    }

    @Test
    fun pasteParsesFalconAndRejectsBadPorts() {
        val falcon = CatEdgePlanner.parsePasted("duort.lybsijm.com", kind = "falcon")
        assertEquals(1, falcon.size)
        assertEquals("falcon", falcon[0].kind)
        assertEquals(443, falcon[0].port)
        val mixed = CatEdgePlanner.parsePasted("a.example.com:443\nb.example.com:80\n1.2.3.4:1080\nnot a host\nhttps://tzav.wltfszy.com/path")
        assertEquals(setOf("a.example.com", "tzav.wltfszy.com"), mixed.map { it.host }.toSet())
        assertTrue(mixed.all { it.port == 443 })
    }

    @Test
    fun handshakeThenVmessConvertsWithoutInsecure() {
        val node = CatEdgePlanner.Node("id", "zuemy.wltfszy.com", 443, "ir", "public")
        val auth = CatEdgePlanner.makeAuth(nowMs = 1L)
        val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val body = CatEdgePlanner.encryptAuthResponse(JSONObject().put("uuid", uuid).put("path", "/"), auth)
        val ready = CatEdgePlanner.handshake(
            node,
            { _, _ -> 200 to body },
            attempt = auth,
        )
        assertEquals(uuid, ready.session.uuid)
        assertTrue(ready.link.startsWith("vmess://"))
        assertFalse(ready.link.contains("insecure", ignoreCase = true))
        val converted = SubConvConverter.convert(ready.link)
        assertEquals(1, converted.size)
        assertEquals("vmess", converted.first().optString("type"))
        assertEquals("zuemy.wltfszy.com", converted.first().optString("server"))
        assertEquals(443, converted.first().optInt("port"))
        assertEquals(uuid, converted.first().optString("uuid"))
        assertTrue(converted.first().optBoolean("tls"))
        assertFalse(converted.first().optBoolean("skip-cert-verify"))
    }

    @Test
    fun fetchSignedListReadsGithubWrapper() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val pubB64 = CatEdgePlanner.b64out(pair.public.encoded)
        val parameters = JSONObject().put(
            "server_ir_1",
            """{"profile":"ir","server":"tzav.wltfszy.com","port":443}""",
        )
        val envelope = CatEdgePlanner.encodeEnvelope(parameters, "testkey", "3", 10L) { data ->
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(pair.private)
            sig.update(data)
            sig.sign()
        }
        val wrapper = JSONObject()
            .put("encoding", "base64")
            .put("content", java.util.Base64.getEncoder().encodeToString(envelope.toByteArray()))
            .toString()
        val list = CatEdgePlanner.fetchSignedList(
            { _, _ -> 200 to wrapper },
            urls = listOf("https://example.invalid/list"),
            keys = mapOf("testkey" to pubB64),
        )
        assertEquals("3", list.serial)
        assertEquals("tzav.wltfszy.com", list.nodes.single().host)
    }

    @Test
    fun hubLaunchesCatEdgeAndDoesNotNameTheOtherAppInTheTitle() {
        val hub = src("MlmAndNextGenVpnHub.kt")
        assertTrue(hub.contains("id = \"cat_edge\""))
        assertTrue(hub.contains("Cat Edge"))
        val launch = src("MainActivityAntiFilterTools.kt")
        assertTrue(launch.contains("\"cat_edge\" -> showCatEdgeDialog()"))
        val ui = src("MainActivityFreeNetworks.kt")
        assertTrue(ui.contains("fun MainActivity.showCatEdgeDialog()"))
        assertFalse(ui.contains("آرگو VPN"))
        assertEquals(24, MlmAndNextGenVpnHub.ALL_VPNS_2026_CATALOG.size)
        assertEquals("cat_edge", MlmAndNextGenVpnHub.ALL_VPNS_2026_CATALOG.last().id)
    }

    @Test
    fun raceDropsFailedHandshakes() {
        val nodes = listOf(
            CatEdgePlanner.Node("a", "a.example.com", 443, "ir", "public"),
            CatEdgePlanner.Node("b", "b.example.com", 443, "ir", "public"),
        )
        val none = CatEdgePlanner.raceHandshakes(nodes, { _, _ -> 500 to "no" }, maxParallel = 2, maxKeep = 2)
        assertTrue(none.isEmpty())
    }

    @Test
    fun oneTapPrefersIranProfile() {
        val nodes = listOf(
            CatEdgePlanner.Node("g", "s1.example.com", 443, "global", "public"),
            CatEdgePlanner.Node("i", "zuemy.wltfszy.com", 443, "ir", "public"),
        )
        val picked = CatEdgePlanner.pickForOneTap(nodes, limit = 1)
        assertEquals("ir", picked.single().profile)
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}
