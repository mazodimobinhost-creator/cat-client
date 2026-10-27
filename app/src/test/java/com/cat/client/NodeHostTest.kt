package com.cat.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 6.69.45 Cat panel on other Node hosts (KataBump / Lucity / Shiprr) — the pure helpers. */
class NodeHostTest {
    @Test
    fun zipComesFromThisVersionsRelease() {
        assertEquals(
            "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta191/cat-node-panel.zip",
            NodeHostPanel.zipUrl("1.10.0-beta191"),
        )
        assertEquals("v1.10.0-beta191", NodeHostPanel.releaseTag("v1.10.0-beta191"))
        assertEquals(listOf("katabump", "lucity", "shiprr"), NodeHostPanel.HOSTS.map { it.id })
        assertTrue(NodeHostPanel.HOSTS.first().free)
        assertTrue(NodeHostPanel.HOSTS.all { it.url.startsWith("https://") })
        assertEquals("KataBump", NodeHostPanel.hostName("katabump"))
    }

    @Test
    fun addressesBecomeTheRightBaseUrl() {
        assertEquals("http://1.2.3.4:25565", NodeHostPanel.normalizeAddress(" 1.2.3.4:25565 "))
        assertEquals("http://1.2.3.4:25565", NodeHostPanel.normalizeAddress("http://1.2.3.4:25565/panel/"))
        assertEquals("http://1.2.3.4", NodeHostPanel.normalizeAddress("1.2.3.4"))
        assertEquals("http://cat.kdns.fr:25565", NodeHostPanel.normalizeAddress("cat.kdns.fr:25565"))
        assertEquals("https://cat.kdns.fr", NodeHostPanel.normalizeAddress("cat.kdns.fr"))
        assertEquals("https://my-app.shiprr.dev", NodeHostPanel.normalizeAddress("https://My-App.shiprr.dev/panel?x=1"))
        assertEquals("https://x.lucity.app", NodeHostPanel.normalizeAddress("x.lucity.app:443"))
        assertNull(NodeHostPanel.normalizeAddress(""))
        assertNull(NodeHostPanel.normalizeAddress("1.2.3.4:99999"))
        assertNull(NodeHostPanel.normalizeAddress("999.1.1.1:80"))
        assertNull(NodeHostPanel.normalizeAddress("user@evil.com"))
        assertNull(NodeHostPanel.normalizeAddress("bad host"))
        assertEquals("http://1.2.3.4:25565/panel", NodeHostPanel.panelUrl("http://1.2.3.4:25565"))
        assertEquals("https://a.b/sub/u", NodeHostPanel.subUrl("https://a.b/", "u"))
        assertEquals(Pair("1.2.3.4", 25565), NodeHostPanel.hostPort("http://1.2.3.4:25565"))
        assertEquals(Pair("x.lucity.app", 443), NodeHostPanel.hostPort("https://x.lucity.app"))
        assertEquals(Pair("1.2.3.4", 80), NodeHostPanel.hostPort("http://1.2.3.4"))
    }

    @Test
    fun configJsonCarriesOnlyTheIdentity() {
        val json = NodeHostPanel.configJson("6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4", "cat-abc", noDisk = false)
        assertTrue(json.contains("\"UUID\": \"6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4\""))
        assertTrue(json.contains("\"TROJAN_PASS\": \"cat-abc\""))
        assertFalse(json.contains("CAT_NO_DISK"))
        assertTrue(NodeHostPanel.configJson("u", "p", noDisk = true).contains("\"CAT_NO_DISK\": \"1\""))
        // quotes / backslashes cannot break out of the JSON string
        assertTrue(NodeHostPanel.configJson("a\"b\\c", "p", false).contains("\"UUID\": \"a\\\"b\\\\c\""))
        val pass = NodeHostPanel.newTrojanPass()
        assertTrue(pass.startsWith("cat-") && pass.length == 20 && pass.drop(4).all { it.isLetterOrDigit() })
        assertTrue(NodeHostPanel.repoName().matches(Regex("cat-node-panel-[a-z0-9]{5}")))
    }

    @Test
    fun zipHoldsExactlyTheFilesGiven() {
        val files = linkedMapOf("index.js" to "a".toByteArray(), "cat-config.json" to "{}".toByteArray())
        val zip = NodeHostPanel.zip(files)
        assertArrayEquals(zip, NodeHostPanel.zip(files)) // deterministic
        val seen = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                seen[e.name] = zis.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(mapOf("index.js" to "a", "cat-config.json" to "{}"), seen)
    }

    @Test
    fun nodeImagePickOnlyUpgradesOldNode() {
        val images = mapOf(
            "Nodejs 18" to "ghcr.io/x/yolks:nodejs_18",
            "Nodejs 20" to "ghcr.io/x/yolks:nodejs_20",
            "Nodejs 22" to "ghcr.io/x/yolks:nodejs_22",
            "Python 3.12" to "ghcr.io/x/yolks:python_3.12",
        )
        assertEquals("ghcr.io/x/yolks:nodejs_22", NodeHostPanel.pickNodeImage(images, "ghcr.io/x/yolks:nodejs_18"))
        assertNull(NodeHostPanel.pickNodeImage(images, "ghcr.io/x/yolks:nodejs_20"))
        assertNull(NodeHostPanel.pickNodeImage(images, "ghcr.io/x/yolks:nodejs_22"))
        assertNull(NodeHostPanel.pickNodeImage(mapOf("Nodejs 16" to "n16"), "n16")) // nothing better offered
        assertEquals(22, NodeHostPanel.nodeVersionOf("Node.js 22"))
        assertEquals(20, NodeHostPanel.nodeVersionOf("node:20-alpine"))
    }

    @Test
    fun mainFileVariableIsFoundAndOthersLeftAlone() {
        assertTrue(NodeHostPanel.isMainFileVar("JS_FILE", "bot.js", "index.js"))
        assertTrue(NodeHostPanel.isMainFileVar("MAIN_FILE", "", "index.js"))
        assertTrue(NodeHostPanel.isMainFileVar("BOT_JS_FILE", "app.mjs", ""))
        assertFalse(NodeHostPanel.isMainFileVar("NODE_PACKAGES", "", ""))
        assertFalse(NodeHostPanel.isMainFileVar("AUTO_UPDATE", "0", "0"))
        assertFalse(NodeHostPanel.isMainFileVar("GIT_ADDRESS", "", ""))
        assertFalse(NodeHostPanel.isMainFileVar("USER_UPLOAD", "0", "0"))
    }

    @Test
    fun allocationAddressNeverPrivate() {
        assertEquals("de1.katabump.com", NodeHostPanel.publicHost("10.0.0.5", "de1.katabump.com"))
        assertEquals("51.75.1.2", NodeHostPanel.publicHost("51.75.1.2", null))
        assertNull(NodeHostPanel.publicHost("0.0.0.0", ""))
        assertNull(NodeHostPanel.publicHost("172.18.0.1", null))
        assertNull(NodeHostPanel.publicHost("192.168.1.1", null))
    }

    @Test
    fun renewCountdown() {
        val day = 24L * 60 * 60 * 1000
        assertNull(NodeHostPanel.daysLeft(0L, 5 * day))
        assertEquals(4.0, NodeHostPanel.daysLeft(10 * day, 10 * day)!!, 0.0001)
        assertEquals(1.0, NodeHostPanel.daysLeft(10 * day, 13 * day)!!, 0.0001)
        assertTrue(NodeHostPanel.daysLeft(10 * day, 15 * day)!! < 0)
        assertEquals(3 * day, NodeHostPanel.REMIND_AFTER_MS)
    }

    @Test
    fun subscriptionAndHttpParsing() {
        val lines = "vless://a@h:1?path=%2Fvl%2Fx%3Fed%3D2560&type=ws#A\ntrojan://p@h:1?path=%2Ftr#B\nvmess://zzz\n"
        assertEquals(2, NodeHostPanel.decodeSub(lines).size)
        val b64 = java.util.Base64.getEncoder().encodeToString(lines.toByteArray())
        assertEquals(NodeHostPanel.decodeSub(lines), NodeHostPanel.decodeSub(b64))
        assertTrue(NodeHostPanel.decodeSub("not base64 !!").isEmpty())
        assertEquals("/vl/x?ed=2560", NodeHostPanel.linkParam(NodeHostPanel.decodeSub(lines)[0], "path"))
        assertEquals("ws", NodeHostPanel.linkParam(NodeHostPanel.decodeSub(lines)[0], "type"))
        assertNull(NodeHostPanel.linkParam("vless://a@h:1", "path"))
        val (code, body) = NodeHostPanel.parseHttpResponse("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nhello".toByteArray())
        assertEquals(200, code)
        assertEquals("hello", body)
        assertEquals(0, NodeHostPanel.parseHttpResponse("garbage".toByteArray()).first)
        val gh = NodeHostPanel.githubContentBody("Cat panel: index.js", "hi".toByteArray())
        assertEquals("{\"message\":\"Cat panel: index.js\",\"content\":\"aGk=\"}", gh)
    }

    @Test
    fun appShipsTheSameLauncherAsTheRepo() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "node-panel/index.js").exists() }
        for (name in NodeHostPanel.LAUNCHER_FILES) {
            val repo = File(root, "node-panel/$name").readBytes()
            val asset = File(root, "app/src/main/assets/node-panel/$name").readBytes()
            assertArrayEquals("assets/node-panel/$name must equal node-panel/$name", repo, asset)
        }
        val launcher = File(root, "node-panel/index.js").readText()
        assertTrue(launcher.contains("cat-config.json") && launcher.contains("CONFIG_KEYS"))
        assertTrue(launcher.contains("cat-node-panel.sha256") && launcher.contains("SHA-256 mismatch"))
        assertTrue(launcher.contains("Promise.resolve(globalThis.__catSockets)"))
        val adapter = File(root, "app/src/main/assets/vercel/panel-adapter.mjs").readText()
        assertTrue(adapter.contains("export function fileKv(") && adapter.contains("CAT_NODE_EDGE"))
    }
}
