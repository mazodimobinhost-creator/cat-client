package com.cat.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.SecureRandom

/**
 * 🌐 Cat GP on the phone: settings, the persisted CA, the user's Code.gs, and the local proxy's life cycle.
 * The engine itself (relay / CA / proxy) is pure JVM in CatGpRelay.kt and unit-tested end to end.
 *
 * Life cycle: the subscription «🌐 Cat GP» holds ONE link, `http://catgp:<secret>@127.0.0.1:10871`. When the VPN
 * connects with a config that points at that port, [ensureRunning] starts the proxy inside the VPN service's
 * process (sockets to Google are protected, so they bypass the tunnel). The secret keeps other apps on the phone
 * from using the proxy.
 */
internal object CatGpEngine {
    const val PORT = 10871
    const val USER = "catgp"
    const val SUB_NAME = "🌐 Cat GP"
    const val CORE_UNSUPPORTED =
        "🌐 Cat GP is a browser HTTP relay through Google Apps Script. " +
            "The Cat core cannot health-check it at 127.0.0.1:2080 (no UDP, no CONNECT tunnel). " +
            "Install the certificate and open Chrome; Telegram/Instagram will not open this way."
    private const val PREFS = "cat_gp"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomToken(n: Int): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Secret shared by the app and the user's Code.gs (created once). */
    fun key(c: Context): String = prefs(c).getString("key", null) ?: randomToken(24).also { prefs(c).edit().putString("key", it).apply() }
    private fun proxyPass(c: Context): String = prefs(c).getString("pass", null) ?: randomToken(20).also { prefs(c).edit().putString("pass", it).apply() }

    /** Deployment addresses as typed (one per line; several spread the daily quota). */
    fun deployments(c: Context): String = prefs(c).getString("exec", null)
        ?: VpnRuntimeStateStore.readGasRelayUrl(c).takeIf { CatGpFronter.scriptPath(it) != null }.orEmpty()
    fun saveDeployments(c: Context, text: String) { prefs(c).edit().putString("exec", text.trim()).apply() }
    fun scriptPaths(text: String): List<String> = CatGpScripts.scriptPaths(text)

    /** Front IP that worked last time first, then the built-in list. */
    fun frontIps(c: Context): List<String> {
        val best = prefs(c).getString("ip", null)
        return (listOfNotNull(best) + CatGpFronter.DEFAULT_FRONT_IPS).distinct()
    }
    private fun rememberIp(c: Context, ip: String) { prefs(c).edit().putString("ip", ip).apply() }

    // ── CA ──

    @Volatile private var caCache: CatGpCa? = null

    @Synchronized
    fun ca(c: Context): CatGpCa {
        caCache?.let { return it }
        val dir = File(c.applicationContext.filesDir, "catgp").apply { mkdirs() }
        val keyFile = File(dir, "ca.key")
        val certFile = File(dir, "ca.der")
        val loaded = if (keyFile.isFile && certFile.isFile) runCatching { CatGpCa.load(keyFile.readBytes(), certFile.readBytes()) }.getOrNull() else null
        val ca = loaded ?: CatGpCa.create().also {
            keyFile.writeBytes(it.keyPkcs8)
            certFile.writeBytes(it.caDer)
        }
        caCache = ca
        return ca
    }

    fun caPem(c: Context): String {
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(ca(c).caDer)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    /**
     * Saves `CatGP-CA.crt` to Downloads (API 29+, no permission) — the file Android's
     * «Install a certificate → CA certificate» screen asks for. Returns a human path or null.
     */
    fun exportCa(c: Context): String? {
        val name = "CatGP-CA.crt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val resolver = c.contentResolver
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                runCatching { resolver.delete(collection, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name)) }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(collection, values) ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(caPem(c).toByteArray()) }
                "Download/$name"
            }.getOrNull()
        }
        return runCatching {
            val dir = c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching null
            File(dir, name).writeText(caPem(c))
            File(dir, name).absolutePath
        }.getOrNull()
    }

    /** Android ≤ 10 installs a CA straight from the app; 11+ only from Settings (the intent then just explains that). */
    fun directInstallIntent(c: Context): Intent? =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            android.security.KeyChain.createInstallIntent().apply {
                putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, ca(c).caDer)
                putExtra(android.security.KeyChain.EXTRA_NAME, CatGpCa.CA_CN)
            }
        } else null

    fun securitySettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)

    /** True when the CA is in the phone's user trust store (what Chrome checks). */
    fun caInstalled(c: Context): Boolean = runCatching {
        val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val mine = ca(c).caCert
        ks.aliases().toList().any { a -> a.startsWith("user:") && (ks.getCertificate(a) == mine) }
    }.getOrDefault(false)

    // ── Subscription + VPN hook ──

    /** Proxy text must not start with http(s):// (that is read as a subscription URL) → a comment line first. */
    fun subscriptionText(c: Context): String =
        "# 🌐 Cat GP — your own Google Apps Script relay (local engine inside Cat)\n" +
            "http://$USER:${proxyPass(c)}@127.0.0.1:$PORT#" + Uri.encode(SUB_NAME)

    fun referencedIn(yaml: String): Boolean = CatGpScripts.referencedIn(yaml, PORT)

    fun isProfile(type: String?, server: String?, port: Int, shareLink: String?): Boolean =
        CatGpScripts.isLocalRelay(type, server, port, shareLink, PORT)

    fun isProfile(profile: ConnectionProfile): Boolean =
        isProfile(profile.type, profile.server, profile.port, profile.shareLink)

    /**
     * Real delay: one HTTP generate_204 through Google → Code.gs. Never the Cat core at :2080.
     * Null = the script did not answer (no /exec, Anyone, or Google front blocked).
     */
    fun delayMs(c: Context, timeoutMs: Int = 15_000): Int? {
        val t0 = System.nanoTime()
        val r = test(c, deployments(c))
        if (!r.ok) return null
        return ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1).coerceAtMost(timeoutMs)
    }

    @Volatile private var proxy: CatGpProxy? = null
    @Volatile private var fronter: CatGpFronter? = null
    @Volatile private var runningFor: String = ""

    /** Starts (or keeps) the local proxy. Returns null when running, else a plain reason. */
    @Synchronized
    fun ensureRunning(c: Context, protect: (java.net.Socket) -> Unit): String? {
        val text = deployments(c)
        val paths = scriptPaths(text)
        if (paths.isEmpty()) return "no Apps Script address saved (🌐 Cat GP → step 2)"
        val signature = paths.joinToString(",") + "|" + key(c)
        proxy?.let { if (it.isRunning && runningFor == signature) return null }
        stop()
        val ctx = c.applicationContext
        val f = CatGpFronter(paths, frontIps(ctx), protect = protect)
        val relay = catGpRelayFunction(f) { VpnRuntimeStateStore.incrementGasDailyUsage(ctx, 1) }
        return try {
            proxy = CatGpProxy(PORT, USER, proxyPass(ctx), ca(ctx), relay, key(ctx)).start()
            fronter = f
            runningFor = signature
            null
        } catch (e: Exception) {
            f.close()
            "local port $PORT busy: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { proxy?.stop() }
        runCatching { fronter?.close() }
        proxy = null
        fronter = null
        runningFor = ""
    }

    // ── Real test (from the dialog) ──

    class TestResult(val ok: Boolean, val lines: List<String>)

    /** One real request through Google → the script → www.gstatic.com/generate_204 (and through each front IP until one works). */
    fun test(c: Context, deploymentsText: String): TestResult {
        val paths = scriptPaths(deploymentsText)
        if (paths.isEmpty()) return TestResult(false, listOf(CatGpScripts.addressProblem(deploymentsText) ?: "not-exec"))
        val lines = mutableListOf<String>()
        for (ip in frontIps(c).take(5)) {
            val f = CatGpFronter(paths.take(1), listOf(ip), protect = { protectOutboundSocket(it) })
            val started = System.nanoTime()
            try {
                val reply = catGpRelayFunction(f)(key(c), "http://www.gstatic.com/generate_204", "GET", emptyList(), null)
                val ms = (System.nanoTime() - started) / 1_000_000
                VpnRuntimeStateStore.incrementGasDailyUsage(c, 1)
                rememberIp(c, ip)
                lines += "✓ Google front $ip → script → site: HTTP ${reply.status} in $ms ms"
                return TestResult(reply.status in 200..399, lines)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                lines += "✗ $ip: " + msg.take(140)
                // The script itself answered (key / login / quota / not found): another IP will not change that.
                if (msg.startsWith("script:") || msg.startsWith("Apps Script") || msg.contains("Anyone") || msg.contains("doPost")) return TestResult(false, lines)
            } finally {
                f.close()
            }
        }
        return TestResult(false, lines)
    }

    // ── The user's Code.gs ──

    /** The script the user pastes into script.google.com (template: assets/catgp/Code.gs, tested by cat-gp-codegs.test.mjs). */
    fun codeGs(c: Context, workerUrl: String = ""): String {
        val template = c.assets.open("catgp/Code.gs").use { String(it.readBytes(), Charsets.UTF_8) }
        return CatGpScripts.fill(template, key(c), workerUrl)
    }

    fun hopCandidates(c: Context, panels: List<Pair<String, String>>): List<String> =
        CatGpScripts.hopCandidates(panels, key(c))
}
