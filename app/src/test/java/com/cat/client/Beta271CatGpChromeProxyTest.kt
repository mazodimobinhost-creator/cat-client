package com.cat.client

import java.io.File
import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta271CatGpChromeProxyTest {

    @Test
    fun versionIsBeta271() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 353"))
        assertTrue(gradle.contains("1.10.0-beta286"))
    }

    @Test
    fun vpnSetsSystemHttpProxyWithoutCredentialsAndStartsOpenLoopback() {
        val vpn = src("CatClientVpnServiceCatGp.kt")
        assertTrue(vpn.contains("ProxyInfo.buildDirectProxy(\"127.0.0.1\", CatGpEngine.PORT)"))
        assertFalse(vpn.contains("Username"))
        val engine = src("CatGpEngine.kt")
        assertTrue(engine.contains("CatGpProxy(PORT, \"\", \"\""))
        assertFalse(engine.contains("CatGpProxy(PORT, USER, proxyPass"))
    }

    @Test
    fun emptyAuthDoesNot407ChromeConnect() {
        val mitmCa = CatGpCa.create()
        val proxy = CatGpProxy(
            0,
            "",
            "",
            mitmCa,
            { _, _, _, _, _ -> CatGpCodec.Reply(204, emptyList(), ByteArray(0)) },
            "k",
        ).start()
        try {
            Socket("127.0.0.1", proxy.boundPort).use { socket ->
                socket.soTimeout = 5_000
                socket.outputStream.write(
                    "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(),
                )
                val line = CatGpCodec.readHead(socket.inputStream)!!.firstLine
                assertTrue(line, line.contains("200"))
                assertFalse(line, line.contains("407"))
            }
        } finally {
            proxy.stop()
        }
    }

    private fun src(name: String): String = listOf(
        File("app/src/main/java/com/cat/client/$name"),
        File("src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}
