package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta223: proxies with Xray-only options (the Irancell «F&F» `finalMask=tlshello-0-len`) run on the real Xray-core
 * behind a local SOCKS inbound; everything else stays on the Cat core; if Xray fails the beta221 ByeByeDPI path runs.
 */
class Beta223XrayEngineTest {
    private val ffBlock = """
        |  - name: 'FF-Node'
        |    type: vless
        |    server: 188.114.98.0
        |    port: 443
        |    uuid: 11111111-2222-4333-8444-555555555555
        |    udp: true
        |    tls: true
        |    skip-cert-verify: false
        |    client-fingerprint: unsafe
        |    alpn:
        |      - http/1.1
        |    servername: a.example.workers.dev
        |    cipher-suites: semi-python
        |    final-mask: tlshello-0-len
        |    network: ws
        |    ws-opts:
        |      path: /?ed=2560
        |      headers:
        |        Host: a.example.workers.dev
    """.trimMargin()

    private val plainBlock = """
        |  - name: 'Plain-Node'
        |    type: vless
        |    server: 104.16.1.1
        |    port: 443
        |    uuid: 11111111-2222-4333-8444-555555555555
        |    tls: true
        |    servername: b.example.workers.dev
        |    network: ws
        |    ws-opts:
        |      path: /
    """.trimMargin()

    private val insecureFfBlock = ffBlock
        .replace("'FF-Node'", "'FF-Insecure'")
        .replace("skip-cert-verify: false", "skip-cert-verify: true")

    /** rawYaml form used by MihomoConfigParser: `  - name:` → `    name:`. */
    private fun parsed(block: String) = MihomoProxyYaml.parse(block.replaceFirst("  - ", "    "))

    private fun yaml(vararg blocks: String) =
        "mixed-port: 7890\nproxies:\n" + blocks.joinToString("\n") + "\nproxy-groups:\n  - name: Auto\n    type: select\n    proxies:\n      - 'FF-Node'\n"

    @Test fun onlyFinalMaskProxiesAreHandedToXray() {
        val raw = yaml(ffBlock, plainBlock, insecureFfBlock)
        assertEquals(listOf("FF-Node"), MihomoXrayPatcher.eligibleNames(raw))
        assertNull(XrayEligibility.reason(parsed(ffBlock)))
        assertEquals("no-final-mask", XrayEligibility.reason(parsed(plainBlock)))
        // Xray removed allowInsecure — such proxies keep the beta221 path instead of breaking Xray's start
        assertEquals("skip-cert-verify", XrayEligibility.reason(parsed(insecureFfBlock)))
    }

    @Test fun yamlKeepsTheNameAndPointsAtTheLocalInbound() {
        val raw = yaml(ffBlock, plainBlock)
        val result = MihomoXrayPatcher.patch(raw, listOf(23456))
        assertTrue(result.applied)
        assertEquals(1, result.routes.size)
        val out = result.yaml
        assertTrue(out, out.contains("  - name: 'FF-Node'\n    type: socks5\n    server: 127.0.0.1\n    port: 23456\n    udp: true"))
        assertFalse(out.contains("188.114.98.0"))
        assertTrue(out.contains("104.16.1.1"))
        assertTrue(out.contains("proxy-groups:"))
        assertTrue(out.contains("      - 'FF-Node'"))
        // no ports → nothing changes
        assertEquals(raw, MihomoXrayPatcher.patch(raw, emptyList()).yaml)
    }

    @Test fun xrayOutboundCarriesTheFullFnFRecipe() {
        val result = MihomoXrayPatcher.patch(yaml(ffBlock), listOf(23456))
        val json = result.configJson!!
        assertTrue(json, JsonCheck.valid(json))
        listOf(
            "\"protocol\":\"vless\"",
            "\"address\":\"188.114.98.0\"",
            "\"port\":443",
            "\"encryption\":\"none\"",
            "\"network\":\"ws\"",
            "\"security\":\"tls\"",
            "\"serverName\":\"a.example.workers.dev\"",
            "\"alpn\":[\"http/1.1\"]",
            "\"fingerprint\":\"unsafe\"",
            "\"cipherSuites\":\"semi-python\"",
            "\"path\":\"/?ed=2560\"",
            "\"host\":\"a.example.workers.dev\"",
            "\"finalmask\":{\"tcp\":[{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\",\"length\":\"100-200\",\"delay\":\"0\"}}]}",
            "\"listen\":\"127.0.0.1\"",
            "\"protocol\":\"socks\"",
            "\"inboundTag\":[\"cat-in-0\"],\"outboundTag\":\"cat-out-0\"",
        ).forEach { assertTrue("$it missing in $json", json.contains(it)) }
        assertFalse("Xray rejects allowInsecure", json.contains("allowInsecure"))
    }

    @Test fun finalMaskForms() {
        assertEquals(
            "{\"tcp\":[{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\",\"length\":\"100-200\",\"delay\":\"0\"}}]}",
            XrayFinalMask.parse("tlshello-0-len"),
        )
        assertTrue(XrayFinalMask.parse("tlshello")!!.contains("\"delay\":\"0\""))
        assertTrue(XrayFinalMask.parse("tlshello-10-20")!!.contains("\"length\":\"20\",\"delay\":\"10\""))
        assertTrue(XrayFinalMask.parse("tlshello-0-6,98,1")!!.contains("\"lengths\":[\"6\",\"98\",\"1\"]"))
        // REALITY form (packets range instead of tlshello)
        val reality = XrayFinalMask.parse("1-3")!!
        assertTrue(reality, reality.contains("\"packets\":\"1-3\"") && reality.contains("\"delay\":\"10-20\""))
        // Xray JSON passes through, legacy {"fragment":…} is converted
        val native = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"]}}]}"""
        assertEquals(native, XrayFinalMask.parse(native))
        val legacy = XrayFinalMask.parse("""{"fragment":{"packets":"tlshello","length":"6,98,1","interval":"1","maxSplit":"3"}}""")!!
        assertTrue(legacy, legacy.contains("\"lengths\":[\"6\",\"98\",\"1\"]") && legacy.contains("\"delay\":\"1\"") && legacy.contains("\"maxSplit\":\"3\""))
        assertNull(XrayFinalMask.parse(""))
        assertNull(XrayFinalMask.parse("nonsense"))
        assertNull(XrayFinalMask.parse("{\"tcp\":[}"))
        listOf("tlshello-0-len", "1-3", native).forEach { assertTrue(it, JsonCheck.valid(XrayFinalMask.parse(it)!!)) }
    }

    @Test fun importedFnFShareLinkIsTakenByXray() {
        val ff = "vless://11111111-2222-4333-8444-555555555555@188.114.98.0:443?type=ws&security=tls" +
            "&sni=a.example.workers.dev&host=a.example.workers.dev&path=%2F&fp=unsafe&alpn=http%2F1.1" +
            "&fm=tlshello-0-len&cs=semi-python#FF-Node"
        val plain = "vless://11111111-2222-4333-8444-555555555555@104.16.1.1:443?type=ws&security=tls" +
            "&sni=b.example.workers.dev&host=b.example.workers.dev&path=%2F&fp=chrome#Plain-Node"
        val imported = UserSubscriptionImporter.import("$ff\n$plain", 1_800_000_000_000L).yaml
        val names = MihomoXrayPatcher.eligibleNames(imported)
        assertEquals(imported, 1, names.size)
        val result = MihomoXrayPatcher.patch(imported, listOf(24000))
        val json = result.configJson!!
        assertTrue(json, JsonCheck.valid(json))
        assertTrue(json, json.contains("\"fingerprint\":\"unsafe\"") && json.contains("\"packets\":\"tlshello\""))
        val proxies = MihomoConfigParser.parse(result.yaml, 1_800_000_000_000L).summary.proxies
        val swapped = proxies.first { it.name == names.single() }
        assertEquals("socks5", swapped.type)
        assertEquals("127.0.0.1", swapped.server)
        assertEquals(24000, swapped.port)
        assertTrue(proxies.any { it.server == "104.16.1.1" })
        assertFalse(MihomoDpiBypassPatcher.hasTlsHelloFinalMask(MihomoXrayPatcher.withoutXrayProxies(imported)))
    }

    @Test fun fingerprintsXrayAccepts() {
        assertEquals("unsafe", XrayConfigBuilder.fingerprint("unsafe"))
        assertEquals("chrome", XrayConfigBuilder.fingerprint(null))
        assertEquals("hellochrome_120", XrayConfigBuilder.fingerprint("chrome120"))
        assertEquals("chrome", XrayConfigBuilder.fingerprint("semi-python"))
        assertEquals("random", XrayConfigBuilder.fingerprint("random"))
    }

    @Test fun trojanXhttpAndInlineEntries() {
        val trojan = "  - {name: 'T-FF', type: trojan, server: 188.114.98.0, port: 443, password: secret, sni: c.example.dev, " +
            "network: xhttp, xhttp-opts: {path: /x, host: c.example.dev, mode: packet-up}, alpn: [h2, http/1.1], final-mask: tlshello-0-len, udp: false}"
        val result = MihomoXrayPatcher.patch(yaml(trojan), listOf(30001))
        assertEquals(1, result.routes.size)
        assertFalse(result.routes.single().udp)
        val json = result.configJson!!
        assertTrue(json, JsonCheck.valid(json))
        assertTrue(json, json.contains("\"protocol\":\"trojan\"") && json.contains("\"password\":\"secret\""))
        assertTrue(json, json.contains("\"xhttpSettings\":{\"path\":\"/x\",\"host\":\"c.example.dev\",\"mode\":\"packet-up\"}"))
        assertTrue(json, json.contains("\"alpn\":[\"h2\",\"http/1.1\"]"))
        assertTrue(result.yaml, result.yaml.contains("port: 30001\n    udp: false"))
    }

    @Test fun leftoverFinalMaskProxiesStillGoThroughByeByeDpi() {
        val onlyEligible = MihomoXrayPatcher.withoutXrayProxies(yaml(ffBlock, plainBlock))
        assertFalse(onlyEligible.contains("final-mask"))
        val mixed = MihomoXrayPatcher.withoutXrayProxies(yaml(ffBlock, insecureFfBlock))
        assertTrue(mixed.contains("final-mask: tlshello-0-len"))
    }

    @Test fun serviceStartsXrayFirstAndFallsBack() {
        val service = VpnServiceSources.combined()
        assertTrue(service.contains("MihomoXrayPatcher.eligibleNames(snapshot.rawConfig)"))
        assertTrue(service.contains("catch (error: XrayStartupException)"))
        assertTrue(service.contains("\"xray.fallback.byeDpi\""))
        assertTrue(service.contains("error !is XrayStartupException"))
        val setup = service.substringAfter("suspend fun setupCoreRuntime(").substringBefore("suspend fun startCoreTun(")
        assertTrue(setup, setup.indexOf("startXrayEngine(") in 0 until setup.indexOf("setupCore(paths)"))
        val stop = service.substringAfter("suspend fun stopCoreService(").substringBefore("return coreStopped")
        assertTrue(stop.contains("stopXrayEngine()"))
        assertTrue(service.contains("this@CatClientVpnService.protect(fd)"))
        val plan = File(src(), "SessionPlan.kt").readText()
        assertTrue(plan.contains("MihomoXrayPatcher.patch(optionsYaml, request.xrayPorts)"))
        assertTrue(plan.contains("rawYaml = xrayPatch.yaml"))
    }

    @Test fun engineIsBuiltFromPinnedSourceAndProtectsItsSockets() {
        val root = listOf(File("."), File("..")).first { File(it, "scripts/build-xray-core.sh").isFile }
        val script = File(root, "scripts/build-xray-core.sh").readText()
        assertTrue(script.contains("XRAY_COMMIT=\"\${XRAY_COMMIT:-1b1647cd9ddf0abc34b981d4611fc03bcac1ca74}\""))
        assertTrue(script.contains("libxray.so"))
        val runner = File(root, "scripts/xray-runner/main.go").readText()
        assertTrue(runner.contains("internet.RegisterDialerController"))
        assertTrue(runner.contains("syscall.UnixRights(fd)"))
        assertTrue(runner.contains("watch-stdin"))
        val workflow = File(root, ".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("bash scripts/build-xray-core.sh"))
        assertTrue(File(root, "app/build.gradle.kts").readText().contains("keepDebugSymbols += \"**/libxray.so\""))
        val engine = File(src(), "XrayEngine.kt").readText()
        assertTrue(engine.contains("peerCredentials.uid != myUid"))
        assertTrue(engine.contains("\"-watch-stdin\""))
        assertNotNull(Regex("""noBackupFilesDir""").find(engine))
    }

    private fun src(): File =
        listOf(File("src/main/java/com/cat/client"), File("app/src/main/java/com/cat/client")).first { it.isDirectory }

    /** Minimal JSON syntax check (org.json is not on the JVM test classpath). */
    private object JsonCheck {
        fun valid(text: String): Boolean = runCatching {
            val p = P(text)
            p.value()
            p.ws()
            p.i == text.length
        }.getOrDefault(false)

        private class P(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun value() {
                ws()
                when (s[i]) {
                    '{' -> { i++; ws(); if (s[i] == '}') { i++; return }
                        while (true) { ws(); str(); ws(); check(s[i++] == ':'); value(); ws()
                            if (s[i] == ',') { i++; continue }; check(s[i++] == '}'); return } }
                    '[' -> { i++; ws(); if (s[i] == ']') { i++; return }
                        while (true) { value(); ws(); if (s[i] == ',') { i++; continue }; check(s[i++] == ']'); return } }
                    '"' -> str()
                    else -> { val start = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "-+.")) i++
                        val token = s.substring(start, i)
                        check(token in setOf("true", "false", "null") || token.toDoubleOrNull() != null) }
                }
            }
            fun str() {
                check(s[i++] == '"')
                while (s[i] != '"') { if (s[i] == '\\') i++; i++ }
                i++
            }
        }
    }
}
