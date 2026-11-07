package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** beta249: 🌐 Cat GP — Google Apps Script relay engine (codec, CA, fronting, local MITM proxy). */
class Beta249CatGpTest {

    @Test fun payloadDropsProxyHeadersAndEncodesBody() {
        val p = CatGpCodec.payload(
            "sek\"ret", "https://example.com/a?b=1", "POST",
            listOf("Host" to "example.com", "Proxy-Authorization" to "Basic x", "Connection" to "keep-alive", "Accept-Encoding" to "gzip",
                "Content-Type" to "application/json", "X-A" to "1", "X-A" to "2", "Content-Length" to "3"),
            "{}\n".toByteArray(),
        )
        @Suppress("UNCHECKED_CAST")
        val m = CatGpJson.parse(p) as Map<String, Any?>
        assertEquals("sek\"ret", m["k"])
        assertEquals("https://example.com/a?b=1", m["u"])
        assertEquals("POST", m["m"])
        assertEquals(false, m["r"])
        val h = m["h"] as Map<*, *>
        assertEquals(setOf("Content-Type", "X-A"), h.keys)
        assertEquals("1, 2", h["X-A"])
        assertEquals("{}\n", String(Base64.getDecoder().decode(m["b"] as String)))
        assertEquals("application/json", m["ct"])
        // HEAD is fetched as GET (UrlFetchApp has no HEAD); no body → no b / ct.
        @Suppress("UNCHECKED_CAST")
        val head = CatGpJson.parse(CatGpCodec.payload("k", "http://x.org/", "head", emptyList(), null)) as Map<String, Any?>
        assertEquals("GET", head["m"])
        assertFalse(head.containsKey("b"))
    }

    @Test fun replyParsingAndHttpFraming() {
        val r = CatGpCodec.parseReply("""{"s":201,"h":{"Content-Type":"text/plain","Set-Cookie":["a=1","b=2\nc=3"],"Content-Encoding":"gzip","Transfer-Encoding":"chunked"},"b":"${Base64.getEncoder().encodeToString("hi".toByteArray())}"}""")
        assertEquals(201, r.status)
        assertEquals(listOf("a=1", "b=2", "c=3"), r.headers.filter { it.first == "Set-Cookie" }.map { it.second })
        val http = String(CatGpCodec.toHttp(r, head = false), Charsets.ISO_8859_1)
        assertTrue(http, http.startsWith("HTTP/1.1 201 Created\r\n"))
        assertTrue(http.contains("Content-Length: 2\r\n"))
        assertFalse(http.contains("gzip"))
        assertFalse(http.contains("chunked"))
        assertTrue(http.endsWith("\r\n\r\nhi"))
        val headOnly = String(CatGpCodec.toHttp(r, head = true), Charsets.ISO_8859_1)
        assertTrue(headOnly.contains("Content-Length: 2\r\n"))
        assertTrue(headOnly.endsWith("\r\n\r\n"))
        try { CatGpCodec.parseReply("""{"e":"unauthorized"}"""); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("unauthorized")) }
        try { CatGpCodec.parseReply("<html>accounts.google.com/ServiceLogin</html>"); fail() } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("Anyone")) }
    }

    @Test fun deploymentAddressForms() {
        val id = "AKfycbx" + "a".repeat(40)
        assertEquals("/macros/s/$id/exec", CatGpFronter.scriptPath("https://script.google.com/macros/s/$id/exec"))
        assertEquals("/macros/s/$id/exec", CatGpFronter.scriptPath("  https://script.google.com/macros/s/$id/exec?x=1 "))
        assertEquals("/a/macros/corp.example/s/$id/exec", CatGpFronter.scriptPath("https://script.google.com/a/macros/corp.example/s/$id/exec"))
        assertEquals("/macros/s/$id/exec", CatGpFronter.scriptPath(id))
        assertEquals(null, CatGpFronter.scriptPath("https://example.com/"))
    }

    @Test fun glueHelpers() {
        val yaml = "proxies:\n  - name: \"🌐 Cat GP\"\n    type: http\n    server: 127.0.0.1\n    port: 10871\n    username: catgp\n"
        assertTrue(CatGpScripts.referencedIn(yaml, 10871))
        assertFalse(CatGpScripts.referencedIn(yaml.replace("10871", "108711"), 10871))
        assertFalse(CatGpScripts.referencedIn("server: 127.0.0.1\n    port: 1819\n", 10871))
        val id = "AKfycb" + "z".repeat(40)
        assertEquals(2, CatGpScripts.scriptPaths("https://script.google.com/macros/s/$id/exec\nhttps://script.google.com/macros/s/${id}2/exec junk").size)
        val tpl = "const CAT_KEY = __CAT_KEY__;\nconst CAT_WORKER = __CAT_WORKER__;"
        val uuid = "41111111-2222-4333-8444-555555555555"
        assertEquals("const CAT_KEY = \"a\\\"b\";\nconst CAT_WORKER = \"https://p.example.workers.dev/gp/$uuid\";",
            CatGpScripts.fill(tpl, "a\"b", "https://p.example.workers.dev/gp/$uuid"))
        assertEquals("const CAT_KEY = \"toktoktoktoktok1\";\nconst CAT_WORKER = \"https://p.example.workers.dev/gp/$uuid/toktoktoktoktok1\";",
            CatGpScripts.fill(tpl, "toktoktoktoktok1", "https://p.example.workers.dev/gp/$uuid/toktoktoktoktok1"))
        // Anything that is not a /gp/<uuid>[/<key>] address is never written into the script.
        assertTrue(CatGpScripts.fill(tpl, "k", "javascript:alert(1)").endsWith("CAT_WORKER = \"\";"))
        assertEquals(listOf("https://p.example.workers.dev/gp/$uuid"),
            CatGpScripts.hopCandidates(listOf("https://p.example.workers.dev" to uuid.uppercase(), "https://x.vercel.app" to uuid)))
        assertEquals(listOf("https://p.example.workers.dev/gp/$uuid/toktoktoktoktok1"),
            CatGpScripts.hopCandidates(listOf("https://p.example.workers.dev" to uuid.uppercase(), "https://x.vercel.app" to uuid), "toktoktoktoktok1"))
    }

    @Test fun gunzipCapsStackedLayers() {
        fun gz(data: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            GZIPOutputStream(o).use { it.write(data) }
            return o.toByteArray()
        }
        var x = "hello".toByteArray()
        repeat(3) { x = gz(x) }
        assertEquals("hello", String(CatGpCodec.gunzip(x)))
        var bomb = "x".toByteArray()
        repeat(CatGpCodec.MAX_GZIP_LAYERS + 1) { bomb = gz(bomb) }
        try {
            CatGpCodec.gunzip(bomb)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("gzip"))
        }
    }

    @Test fun caAndLeafAreRealCertificatesThatValidate() {
        val ca = CatGpCa.create()
        assertTrue(ca.caCert.basicConstraints >= 0)
        ca.caCert.verify(ca.caCert.publicKey)
        val leaf = ca.leaf("www.example.com")
        leaf.verify(ca.caCert.publicKey)
        assertEquals(-1, leaf.basicConstraints)
        assertEquals(listOf("www.example.com"), leaf.subjectAlternativeNames.map { it[1] })
        assertTrue(leaf.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        assertTrue(leaf.notAfter.time - leaf.notBefore.time <= 399L * 86_400_000L)
        // Full PKIX path validation (what a browser does once the CA is installed).
        val cf = CertificateFactory.getInstance("X.509")
        val params = PKIXParameters(Collections.singleton(TrustAnchor(ca.caCert, null))).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(listOf(leaf)), params)
        // IP literal → iPAddress SAN.
        assertEquals(7, ca.leaf("10.1.2.3").subjectAlternativeNames.first()[0])
        // Persist / reload keeps the same CA.
        val again = CatGpCa.load(ca.keyPkcs8, ca.caDer)
        assertEquals(ca.caCert, again.caCert)
        again.leaf("a.example").verify(ca.caCert.publicKey)
    }

    private fun trusting(ca: CatGpCa): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca.caCert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    /** Fake Google front: exec → 302 → googleusercontent echo answers {s,h,b} (gzip + chunked). */
    private class FakeGoogle(googleCa: CatGpCa, val key: String) {
        val payloads = Collections.synchronizedList(ArrayList<String>())
        val connections = AtomicInteger(0)
        val server = googleCa.serverContext("www.google.com").serverSocketFactory
            .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port get() = server.localPort
        init {
            Thread {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    Thread { runCatching { serve(s as SSLSocket) }; runCatching { s.close() } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(s: SSLSocket) {
            val input = s.inputStream.buffered()
            val out = s.outputStream
            var pending: String? = null
            while (true) {
                val head = CatGpCodec.readHead(input) ?: return
                val body = CatGpCodec.readBody(input, head, untilClose = false)
                val path = head.firstLine.split(' ')[1]
                if (head.firstLine.startsWith("POST /macros/s/") && head.header("host") == "script.google.com") {
                    pending = String(body)
                    payloads += pending
                    out.write("HTTP/1.1 302 Moved Temporarily\r\nLocation: https://script.googleusercontent.com/macros/echo?user_content_key=abc&lib=x\r\nContent-Length: 0\r\n\r\n".toByteArray())
                } else if (path.startsWith("/macros/echo?user_content_key=abc") && head.header("host") == "script.googleusercontent.com" && pending != null) {
                    @Suppress("UNCHECKED_CAST")
                    val q = CatGpJson.parse(pending) as Map<String, Any?>
                    val json = if (q["k"] != key) """{"e":"unauthorized"}""" else
                        """{"s":200,"h":{"Content-Type":"text/plain","Set-Cookie":["a=1","b=2"],"X-Url":${CatGpJson.quote(q["u"].toString())}},"b":"${Base64.getEncoder().encodeToString("hello cat ${q["m"]}".toByteArray())}"}"""
                    val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(json.toByteArray()) } }.toByteArray()
                    val half = gz.size / 2
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    out.write("${Integer.toHexString(half)}\r\n".toByteArray()); out.write(gz, 0, half); out.write("\r\n".toByteArray())
                    out.write("${Integer.toHexString(gz.size - half)}\r\n".toByteArray()); out.write(gz, half, gz.size - half); out.write("\r\n0\r\n\r\n".toByteArray())
                    pending = null
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
                out.flush()
            }
        }
    }

    private fun readResponse(input: java.io.InputStream): Pair<CatGpCodec.Head, String> {
        val head = CatGpCodec.readHead(input)!!
        return head to String(CatGpCodec.readBody(input, head, untilClose = false))
    }

    @Test fun endToEndConnectMitmThroughFrontedRelay() {
        val googleCa = CatGpCa.create()
        val fake = FakeGoogle(googleCa, "k123")
        val fronter = CatGpFronter(
            listOf("/macros/s/AKfycb" + "x".repeat(30) + "/exec"), listOf("127.0.0.1"), port = fake.port,
            snis = listOf("www.google.com"), tls = trusting(googleCa).socketFactory,
        )
        val calls = AtomicInteger(0)
        val mitmCa = CatGpCa.create()
        val proxy = CatGpProxy(0, "catgp", "pw", mitmCa, catGpRelayFunction(fronter) { calls.incrementAndGet() }, "k123").start()
        try {
            // 1) No credentials → 407.
            Socket("127.0.0.1", proxy.boundPort).use { s ->
                s.outputStream.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray())
                assertTrue(CatGpCodec.readHead(s.inputStream)!!.firstLine.contains("407"))
            }
            val auth = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("catgp:pw".toByteArray()) + "\r\n"
            // 2) CONNECT + TLS that trusts ONLY Cat GP's CA, with hostname verification.
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", proxy.boundPort), 5_000)
            raw.soTimeout = 20_000
            raw.outputStream.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n$auth\r\n".toByteArray())
            assertTrue(CatGpCodec.readHead(raw.inputStream)!!.firstLine.contains(" 200 "))
            val tls = trusting(mitmCa).socketFactory.createSocket(raw, "example.com", 443, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS"; serverNames = listOf(SNIHostName("example.com")) }
            tls.startHandshake()
            val inp = tls.inputStream.buffered()
            tls.outputStream.write("GET /path?q=1 HTTP/1.1\r\nHost: example.com\r\nUser-Agent: t\r\n\r\n".toByteArray())
            val (h1, b1) = readResponse(inp)
            assertTrue(h1.firstLine, h1.firstLine.startsWith("HTTP/1.1 200"))
            assertEquals("hello cat GET", b1)
            assertEquals("https://example.com/path?q=1", h1.header("x-url"))
            assertEquals(2, h1.headers.count { it.first.equals("Set-Cookie", true) })
            // Keep-alive: a POST on the same browser connection.
            tls.outputStream.write("POST /up HTTP/1.1\r\nHost: example.com\r\nContent-Type: text/plain\r\nContent-Length: 4\r\n\r\nabcd".toByteArray())
            val (h2, b2) = readResponse(inp)
            assertTrue(h2.firstLine.startsWith("HTTP/1.1 200"))
            assertEquals("hello cat POST", b2)
            tls.close()
            // 3) Plain proxy request (absolute URI).
            Socket("127.0.0.1", proxy.boundPort).use { s ->
                s.soTimeout = 20_000
                s.outputStream.write("GET http://plain.example/x HTTP/1.1\r\nHost: plain.example\r\n${auth}Connection: close\r\n\r\n".toByteArray())
                val (h3, b3) = readResponse(s.inputStream.buffered())
                assertEquals("hello cat GET", b3)
                assertEquals("http://plain.example/x", h3.header("x-url"))
            }
            assertEquals(3, calls.get())
            assertEquals(3, fronter.calls.get())
            // What the script received: key, url, method, body; never Host / proxy headers.
            @Suppress("UNCHECKED_CAST")
            val post = CatGpJson.parse(fake.payloads[1]) as Map<String, Any?>
            assertEquals("k123", post["k"])
            assertEquals("https://example.com/up", post["u"])
            assertEquals("abcd", String(Base64.getDecoder().decode(post["b"] as String)))
            assertFalse((post["h"] as Map<*, *>).keys.any { it.toString().equals("host", true) })
            // The Google-side connection was reused (keep-alive pool), not one TLS handshake per request.
            assertEquals(1, fake.connections.get())
            // Wrong key → the browser gets a clear 502 page, not a hang.
            val bad = CatGpProxy(0, "", "", mitmCa, catGpRelayFunction(fronter), "WRONG").start()
            try {
                Socket("127.0.0.1", bad.boundPort).use { s ->
                    s.soTimeout = 20_000
                    s.outputStream.write("GET http://plain.example/ HTTP/1.1\r\nHost: plain.example\r\n\r\n".toByteArray())
                    val (h4, b4) = readResponse(s.inputStream.buffered())
                    assertTrue(h4.firstLine.contains("502"))
                    assertTrue(b4, b4.contains("unauthorized"))
                }
            } finally { bad.stop() }
        } finally {
            proxy.stop()
            fronter.close()
        }
    }

    @Test fun fronterExplainsGoogleErrors() {
        val googleCa = CatGpCa.create()
        val server = googleCa.serverContext("www.google.com").serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as ServerSocket
        Thread {
            val s = server.accept()
            CatGpCodec.readHead(s.inputStream)
            s.outputStream.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            s.outputStream.flush()
            Thread.sleep(300); s.close()
        }.apply { isDaemon = true }.start()
        val f = CatGpFronter(listOf("/macros/s/" + "y".repeat(40) + "/exec"), listOf("127.0.0.1"), port = server.localPort,
            snis = listOf("www.google.com"), tls = trusting(googleCa).socketFactory)
        try { f.relay("{}"); fail() } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("Anyone")) }
        server.close()
        assertNotNull(CatGpFronter.DEFAULT_FRONT_IPS.firstOrNull())
    }
}
