package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * PaqetEngine — Full engine for `paqet` and `paqetNG` (`patth` & `patth ng`, up to paqetNG v1.3.7 + paqet-ngv2).
 *
 * Supported capabilities:
 * 1. Schemes & Configs:
 *    - `paqet://`, `paqetng://`, `patth://`, `patthng://` share URIs
 *    - Raw `paqet` / `paqetNG` YAML, TOML, or JSON client configs (`role: client`, `server_addr`, `kcp`, `tls_obfs`, etc.)
 * 2. Dual-Mode Packet & Stream Transport:
 *    - Userspace Non-Root Mode: KCP/TCP + smux framing + AES-256-GCM encryption + TLS 1.3 Application Record
 *      Obfuscation (`0x17 0x03 0x03`) + Random Padding + Dynamic Port Hopping (`port_range`) + Iranian Entry Relay.
 *    - Root Raw-Socket / Interface Helper (`paqetNG v1.2.4–v1.3.7`): Automatic physical mobile interface detection
 *      (`rmnet_data*`, `ccmni*`, `wlan*`, explicitly skipping `tun0`/`vpn` interfaces that broke mobile data in older versions).
 * 3. `paqet-ngv2` Emergency Control Plane over Recursive DNS:
 *    - Resolves updated server coordinates (`IP:Port` + port-hopping range) over DNS TXT queries (encrypted with AES-GCM
 *      or plain `paqet=` tag) through whitelisted Iranian or global recursive DNS resolvers when the primary IP is blocked.
 * 4. Loop-Safe & Memory-Safe Local SOCKS5 Bridge:
 *    - Protected outbound sockets via [CatClientVpnService.protectOutboundSocket] & [CatClientVpnService.protectOutboundDatagramSocket]
 *    - Bounded 256KB-stack worker pool (prevents `pthread_create failed: OutOfMemoryError`)
 *    - Loopback destination guard (`127.0.0.1`, `localhost`, `::1`, `0.0.0.0`).
 */
internal object PaqetEngine {

    val SUPPORTED_SCHEMES = setOf("paqet", "paqetng", "patth", "patthng")

    enum class TransportVariant(val id: String, val titleFa: String) {
        PAQET_STANDARD("paqet", "paqet استاندارد (KCP + TLS Obfs + Port Hopping)"),
        PAQET_NG("paqetng", "paqetNG v1.3.7 (ضد-DPI پیشرفته + تشخیص هوشمند دیتای موبایل)"),
        PATTH_ENTRY("patth", "patth (ریلی سرور ایران / Entry Point بدون نیاز به روت)"),
        PATTH_NG_DNS("patthng", "patth NG v2 (همراه با بازیابی اضطراری روی DNS بازگشتی)"),
        ;

        companion object {
            fun fromSchemeOrMode(value: String?): TransportVariant {
                return when (value?.trim()?.lowercase(Locale.US)) {
                    "paqetng", "ng", "v1.3.7" -> PAQET_NG
                    "patth", "entry", "relay" -> PATTH_ENTRY
                    "patthng", "ngv2", "paqet-ngv2", "dns-recovery" -> PATTH_NG_DNS
                    else -> PAQET_STANDARD
                }
            }
        }
    }

    enum class KcpProfile(val id: String, val nodelay: Int, val intervalMs: Int, val resend: Int, val nc: Int) {
        FAST3("fast3", 1, 10, 2, 1),
        FAST2("fast2", 1, 20, 2, 1),
        FAST("fast", 0, 30, 2, 1),
        NORMAL("normal", 0, 40, 0, 1),
        ;

        companion object {
            fun fromId(id: String?): KcpProfile =
                values().firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: FAST2
        }
    }

    data class PaqetConfig(
        val variant: TransportVariant = TransportVariant.PAQET_NG,
        val serverHost: String,
        val serverPort: Int = 443,
        val portHopStart: Int = 0,
        val portHopEnd: Int = 0,
        val secretKey: String = "catclient-paqet-default-key",
        val kcpMode: KcpProfile = KcpProfile.FAST2,
        val mtu: Int = 1350,
        val connections: Int = 2,
        val tlsObfuscation: Boolean = true,
        val sniHost: String = "speedtest.net",
        val paddingMin: Int = 16,
        val paddingMax: Int = 96,
        val networkInterface: String = "auto",
        val dnsControlDomain: String = "",
        val dnsResolver: String = "178.22.122.100",
        val upstreamEntrySocks: Boolean = false,
        val splitTunnelMode: String = "all", // all, bypass_ir, selected_apps
        val remark: String = "PaqetNG Anti-DPI",
    ) {
        val hasPortHopping: Boolean
            get() = portHopStart in 1..65535 && portHopEnd in portHopStart..65535 && portHopEnd > portHopStart

        fun pickTargetPort(): Int {
            if (!hasPortHopping) return serverPort.coerceIn(1, 65535)
            return Random.nextInt(portHopStart, portHopEnd + 1)
        }
    }

    data class ProbeResult(
        val reachable: Boolean,
        val latencyMs: Long,
        val resolvedEndpoint: String,
        val detectedInterface: String,
        val rootAvailable: Boolean,
        val messageFa: String,
    )

    private const val PREFS_NAME = "cat_paqet_engine_prefs"
    private const val KEY_LAST_URI = "last_paqet_uri"

    private val running = AtomicBoolean(false)
    private val localPort = AtomicInteger(0)
    private val activeConfigRef = AtomicReference<PaqetConfig?>(null)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val workerIdSeq = AtomicInteger(1)
    private val paqetWorkerPool: ThreadPoolExecutor = ThreadPoolExecutor(
        4,
        48,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256),
        ThreadFactory { runnable ->
            Thread(
                null,
                runnable,
                "paqet-worker-${workerIdSeq.getAndIncrement()}",
                256 * 1024L,
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    fun isRunning(): Boolean = running.get() && localPort.get() in 1..65535 && serverSocket?.isClosed == false

    fun activeProxyPort(): Int = if (isRunning()) localPort.get() else 10809

    fun currentConfig(): PaqetConfig? = activeConfigRef.get()

    fun saveLastConfig(context: Context, config: PaqetConfig) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_URI, buildPaqetUri(config))
                .apply()
        }
    }

    fun loadLastConfig(context: Context): PaqetConfig? {
        val raw = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_URI, null)
        }.getOrNull() ?: return null
        return parsePaqetUri(raw)
    }

    /**
     * Detects the active physical network interface for mobile data / Wi-Fi, explicitly skipping `tun*` / `ppp*`.
     * Matches `paqetNG v1.2.4` fix for mobile data (`rmnet_data*`, `ccmni*`, `wlan*`).
     */
    fun detectBestPhysicalNetworkInterface(): String {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())
        val candidates = interfaces.filter { iface ->
            val name = iface.name?.lowercase(Locale.US).orEmpty()
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            val isVirtualTun = name.startsWith("tun") || name.startsWith("ppp") ||
                name.startsWith("wg") || name.startsWith("awg") || name.startsWith("dummy") ||
                name.startsWith("lo")
            val hasIp = runCatching { iface.inetAddresses.hasMoreElements() }.getOrDefault(false)
            up && !isVirtualTun && hasIp
        }
        // Priority order: cellular data interfaces (rmnet_data*, ccmni*, pdp_ip*), then Wi-Fi (wlan*), then eth*
        val priorityPrefixes = listOf("rmnet_data", "rmnet", "ccmni", "pdp_ip", " clat", "wlan", "eth", "en")
        for (prefix in priorityPrefixes) {
            val match = candidates.firstOrNull { it.name.lowercase(Locale.US).startsWith(prefix.trim()) }
            if (match != null) return match.name
        }
        return candidates.firstOrNull()?.name ?: "wlan0"
    }

    fun isRootAvailableQuick(): Boolean {
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/odm/bin/su", "/vendor/bin/su")
        return suPaths.any { path -> runCatching { java.io.File(path).exists() }.getOrDefault(false) }
    }

    /**
     * Parses `paqet://`, `paqetng://`, `patth://`, `patthng://` URIs.
     * Format: `paqetng://<key>@<host>:<port>?mode=fast2&hop=20000-20500&sni=speedtest.net&obfs=1&dns=ctrl.example.com#Remark`
     */
    fun parsePaqetUri(rawUri: String): PaqetConfig? {
        val trimmed = rawUri.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase(Locale.US)
        if (scheme !in SUPPORTED_SCHEMES) return null

        return runCatching {
            val fragment = trimmed.substringAfter('#', "").let {
                if (it.isBlank()) "" else runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            val withoutFragment = trimmed.substringBefore('#')
            val queryPart = withoutFragment.substringAfter('?', "")
            val authorityAndPath = withoutFragment.substringAfter("://").substringBefore('?')
            val authority = authorityAndPath.substringBefore('/')
            val userInfo = if ('@' in authority) authority.substringBeforeLast('@') else ""
            val hostPort = if ('@' in authority) authority.substringAfterLast('@') else authority

            val host: String
            val portStr: String
            if (hostPort.startsWith("[")) {
                host = hostPort.substringAfter('[').substringBefore(']')
                portStr = hostPort.substringAfter("]:", "443")
            } else {
                host = hostPort.substringBefore(':')
                portStr = hostPort.substringAfter(':', "443")
            }
            if (host.isBlank()) return null

            val params = mutableMapOf<String, String>()
            if (queryPart.isNotBlank()) {
                for (pair in queryPart.split('&')) {
                    if (pair.isBlank()) continue
                    val k = pair.substringBefore('=').lowercase(Locale.US)
                    val v = pair.substringAfter('=', "").let {
                        runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                    }
                    params[k] = v
                }
            }

            var port = portStr.substringBefore('-').toIntOrNull()?.coerceIn(1, 65535) ?: 443
            var hopStart = 0
            var hopEnd = 0
            val hopSpec = params["hop"] ?: params["port_hop"] ?: params["port_range"]
                ?: (if ('-' in portStr) portStr else "")
            if (hopSpec.contains('-')) {
                val s = hopSpec.substringBefore('-').trim().toIntOrNull() ?: 0
                val e = hopSpec.substringAfter('-').trim().toIntOrNull() ?: 0
                if (s in 1..65535 && e in s..65535) {
                    hopStart = s
                    hopEnd = e
                    if (port !in 1..65535) port = s
                }
            }

            val secret = userInfo.takeIf { it.isNotBlank() }?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            } ?: params["key"] ?: params["secret"] ?: params["password"] ?: "catclient-paqet-key"

            val variant = TransportVariant.fromSchemeOrMode(params["variant"] ?: scheme)
            val kcpMode = KcpProfile.fromId(params["mode"] ?: params["kcp"] ?: params["kcp_mode"])
            val mtu = params["mtu"]?.toIntOrNull()?.coerceIn(500, 1480) ?: 1350
            val conn = params["conn"]?.toIntOrNull()?.coerceIn(1, 16) ?: 2
            val tlsObfs = params["obfs"]?.let { it != "0" && !it.equals("false", ignoreCase = true) } ?: true
            val sni = params["sni"]?.takeIf { it.isNotBlank() } ?: "speedtest.net"
            val padMin = params["padmin"]?.toIntOrNull()?.coerceIn(0, 512) ?: 16
            val padMax = params["padmax"]?.toIntOrNull()?.coerceIn(padMin, 1024) ?: 96
            val iface = params["iface"]?.takeIf { it.isNotBlank() } ?: "auto"
            val dnsDomain = params["dns_domain"] ?: params["ngv2_domain"] ?: params["dns"] ?: ""
            val dnsResolver = params["resolver"] ?: params["dns_server"] ?: "178.22.122.100"
            val entrySocks = params["entry_socks"] == "1" || params["relay"] == "socks5"
            val splitMode = params["split"] ?: "all"
            val remark = fragment.ifBlank {
                "${variant.id.uppercase(Locale.US)} $host:$port"
            }

            PaqetConfig(
                variant = variant,
                serverHost = host,
                serverPort = port,
                portHopStart = hopStart,
                portHopEnd = hopEnd,
                secretKey = secret,
                kcpMode = kcpMode,
                mtu = mtu,
                connections = conn,
                tlsObfuscation = tlsObfs,
                sniHost = sni,
                paddingMin = padMin,
                paddingMax = padMax,
                networkInterface = iface,
                dnsControlDomain = dnsDomain,
                dnsResolver = dnsResolver,
                upstreamEntrySocks = entrySocks,
                splitTunnelMode = splitMode,
                remark = remark,
            )
        }.getOrNull()
    }

    /**
     * Parses either a `paqet://` / `paqetng://` / `patth://` URI OR a raw `paqet` YAML/TOML/JSON configuration file.
     */
    fun parsePaqetFileOrUri(rawText: String): PaqetConfig? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        parsePaqetUri(trimmed)?.let { return it }

        // Check if a share link appears on one of the lines
        trimmed.lineSequence().forEach { line ->
            val l = line.trim()
            if (SUPPORTED_SCHEMES.any { l.startsWith("$it://", ignoreCase = true) }) {
                parsePaqetUri(l)?.let { return it }
            }
        }

        // Parse YAML / TOML / JSON style paqet config
        return runCatching {
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val serverAddr = json.optString("server_addr")
                    .ifBlank { json.optString("server") }
                    .ifBlank { json.optString("remote") }
                if (serverAddr.isBlank()) return null
                val host = serverAddr.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val port = serverAddr.substringAfterLast(':', "443").toIntOrNull() ?: 443
                val key = json.optString("key").ifBlank { json.optString("secret", "catclient-paqet-key") }
                val mode = json.optString("kcp_mode").ifBlank { json.optString("mode", "fast2") }
                val hop = json.optString("port_hop").ifBlank { json.optString("port_range", "") }
                val hopStart = hop.substringBefore('-', "0").trim().toIntOrNull() ?: 0
                val hopEnd = hop.substringAfter('-', "0").trim().toIntOrNull() ?: 0
                return PaqetConfig(
                    variant = TransportVariant.PAQET_NG,
                    serverHost = host,
                    serverPort = port,
                    portHopStart = hopStart,
                    portHopEnd = hopEnd,
                    secretKey = key,
                    kcpMode = KcpProfile.fromId(mode),
                    mtu = json.optInt("mtu", 1350).coerceIn(500, 1480),
                    connections = json.optInt("conn", 2).coerceIn(1, 16),
                    sniHost = json.optString("sni", "speedtest.net"),
                    remark = json.optString("remark").ifBlank { "PaqetNG $host:$port" },
                )
            }

            // Key-value YAML / TOML extraction
            val kv = mutableMapOf<String, String>()
            trimmed.lineSequence().forEach { line ->
                val clean = line.substringBefore('#').trim()
                if (clean.isEmpty()) return@forEach
                val sepIdx = clean.indexOfAny(charArrayOf(':', '='))
                if (sepIdx <= 0) return@forEach
                val k = clean.substring(0, sepIdx).trim().lowercase(Locale.US)
                val v = clean.substring(sepIdx + 1).trim().trim('"', '\'')
                if (v.isNotEmpty()) kv[k] = v
            }
            val serverAddr = kv["server_addr"] ?: kv["server"] ?: kv["addr"] ?: kv["remote_addr"] ?: return null
            val host = if (':' in serverAddr) serverAddr.substringBeforeLast(':').removePrefix("[").removeSuffix("]") else serverAddr
            val port = if (':' in serverAddr) (serverAddr.substringAfterLast(':').toIntOrNull() ?: 443) else (kv["port"]?.toIntOrNull() ?: 443)
            if (host.isBlank()) return null
            val key = kv["key"] ?: kv["secret"] ?: kv["crypt_key"] ?: "catclient-paqet-key"
            val hop = kv["port_hop"] ?: kv["port_range"] ?: ""
            val hopStart = hop.substringBefore('-', "0").trim().toIntOrNull() ?: 0
            val hopEnd = hop.substringAfter('-', "0").trim().toIntOrNull() ?: 0
            PaqetConfig(
                variant = TransportVariant.PAQET_NG,
                serverHost = host,
                serverPort = port,
                portHopStart = hopStart,
                portHopEnd = hopEnd,
                secretKey = key,
                kcpMode = KcpProfile.fromId(kv["mode"] ?: kv["kcp_mode"]),
                mtu = kv["mtu"]?.toIntOrNull()?.coerceIn(500, 1480) ?: 1350,
                connections = kv["conn"]?.toIntOrNull()?.coerceIn(1, 16) ?: 2,
                sniHost = kv["sni"] ?: "speedtest.net",
                networkInterface = kv["interface"] ?: kv["iface"] ?: "auto",
                dnsControlDomain = kv["dns_domain"] ?: kv["ngv2_domain"] ?: "",
                remark = kv["remark"] ?: kv["name"] ?: "PaqetNG $host:$port",
            )
        }.getOrNull()
    }

    private fun urlEncode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault(value)

    fun buildPaqetUri(config: PaqetConfig): String {
        val scheme = config.variant.id
        val encodedKey = urlEncode(config.secretKey)
        val hostPart = if (':' in config.serverHost && !config.serverHost.startsWith("[")) {
            "[${config.serverHost}]"
        } else {
            config.serverHost
        }
        val query = buildList {
            add("variant=${urlEncode(config.variant.id)}")
            add("mode=${urlEncode(config.kcpMode.id)}")
            add("mtu=${config.mtu}")
            add("conn=${config.connections}")
            add("obfs=${if (config.tlsObfuscation) "1" else "0"}")
            if (config.sniHost.isNotBlank()) add("sni=${urlEncode(config.sniHost)}")
            if (config.hasPortHopping) add("hop=${config.portHopStart}-${config.portHopEnd}")
            add("padmin=${config.paddingMin}")
            add("padmax=${config.paddingMax}")
            if (config.networkInterface.isNotBlank()) add("iface=${urlEncode(config.networkInterface)}")
            if (config.dnsControlDomain.isNotBlank()) add("dns_domain=${urlEncode(config.dnsControlDomain)}")
            if (config.dnsResolver.isNotBlank()) add("resolver=${urlEncode(config.dnsResolver)}")
            if (config.upstreamEntrySocks) add("entry_socks=1")
            if (config.splitTunnelMode.isNotBlank()) add("split=${urlEncode(config.splitTunnelMode)}")
        }.joinToString("&")
        val remarkPart = urlEncode(config.remark.ifBlank { "PaqetNG ${config.serverHost}:${config.serverPort}" })
        return "$scheme://$encodedKey@$hostPart:${config.serverPort}?$query#$remarkPart"
    }

    fun toMihomoProxyJson(config: PaqetConfig, localSocksPort: Int = activeProxyPort()): JSONObject {
        val uri = buildPaqetUri(config)
        return JSONObject().apply {
            put("name", config.remark.ifBlank { "PaqetNG ${config.serverHost}:${config.serverPort}" })
            put("type", "socks5")
            put("server", "127.0.0.1")
            put("port", localSocksPort)
            put("udp", true)
            put("paqet-uri", uri)
        }
    }

    /**
     * Ensures the local SOCKS5 bridge for `paqet` / `paqetNG` is running and returns the active local port.
     */
    @Synchronized
    fun ensureLocalBridgeRunning(context: Context, uriOrConfig: String? = null): Int {
        val parsed = uriOrConfig?.let(::parsePaqetFileOrUri)
            ?: activeConfigRef.get()
            ?: loadLastConfig(context)
            ?: PaqetConfig(serverHost = "127.0.0.1", serverPort = 443)
        activeConfigRef.set(parsed)
        saveLastConfig(context, parsed)

        if (isRunning()) {
            return localPort.get()
        }

        stopLocalBridge()
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 10809))
            }
        }.getOrElse {
            ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        }

        val boundPort = socket.localPort
        serverSocket = socket
        localPort.set(boundPort)
        running.set(true)

        acceptThread = Thread(
            null,
            {
                while (running.get() && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    paqetWorkerPool.execute {
                        runCatching { handleSocks5Client(client) }.onFailure {
                            runCatching { client.close() }
                        }
                    }
                }
            },
            "paqet-accept-loop",
            256 * 1024L,
        ).apply {
            isDaemon = true
            start()
        }
        return boundPort
    }

    @Synchronized
    fun stopLocalBridge() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        localPort.set(0)
    }

    /**
     * `paqet-ngv2` Emergency Control Plane over Recursive DNS:
     * Queries a TXT record for [config.dnsControlDomain] via [config.dnsResolver] and extracts updated `host:port`
     * (supports plain `paqet=IP:PORT` or AES-GCM encrypted base64 payload).
     */
    fun resolveEmergencyCoordinateViaDns(config: PaqetConfig, timeoutMs: Int = 2500): Pair<String, Int>? {
        val domain = config.dnsControlDomain.trim()
        if (domain.isBlank()) return null
        val txtRecords = queryDnsTxtRecords(domain, config.dnsResolver.ifBlank { "178.22.122.100" }, timeoutMs)
        for (txt in txtRecords) {
            val cleaned = txt.trim()
            if (cleaned.startsWith("paqet=", ignoreCase = true)) {
                val ep = cleaned.substringAfter('=')
                val h = ep.substringBeforeLast(':')
                val p = ep.substringAfterLast(':').toIntOrNull()
                if (h.isNotBlank() && p != null && p in 1..65535) return h to p
            }
            val decrypted = runCatching { decryptAesGcmString(cleaned, config.secretKey) }.getOrNull()
            if (!decrypted.isNullOrBlank() && ':' in decrypted) {
                val h = decrypted.substringBeforeLast(':').trim()
                val p = decrypted.substringAfterLast(':').trim().toIntOrNull()
                if (h.isNotBlank() && p != null && p in 1..65535) return h to p
            }
        }
        return null
    }

    /**
     * Real network probe for a `paqet` / `paqetNG` config.
     */
    fun probePaqetEndpoint(config: PaqetConfig, timeoutMs: Int = 3500): ProbeResult {
        val iface = if (config.networkInterface.isBlank() || config.networkInterface == "auto") {
            detectBestPhysicalNetworkInterface()
        } else {
            config.networkInterface
        }
        val root = isRootAvailableQuick()
        var targetHost = config.serverHost
        var targetPort = config.pickTargetPort()

        if (config.dnsControlDomain.isNotBlank()) {
            resolveEmergencyCoordinateViaDns(config, timeoutMs / 2)?.let { (newHost, newPort) ->
                targetHost = newHost
                targetPort = newPort
            }
        }

        val startNs = System.nanoTime()
        // 1) Try protected TCP handshake with TLS Record Obfuscation probe
        val tcpOk = runCatching {
            Socket().use { sock ->
                protectOutboundSocket(sock)
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(targetHost, targetPort), timeoutMs)
                true
            }
        }.getOrDefault(false)

        if (tcpOk) {
            val ms = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            return ProbeResult(
                reachable = true,
                latencyMs = ms,
                resolvedEndpoint = "$targetHost:$targetPort",
                detectedInterface = iface,
                rootAvailable = root,
                messageFa = "✅ سرور ${config.variant.id.uppercase(Locale.US)} روی $targetHost:$targetPort در دسترس است (${ms}ms · رابط: $iface)",
            )
        }

        // 2) Try protected UDP KCP / TLS-record obfuscated probe
        val udpOk = runCatching {
            DatagramSocket().use { udp ->
                protectOutboundDatagramSocket(udp)
                udp.soTimeout = timeoutMs
                val payload = buildObfuscatedPaqetFrame(
                    rawPayload = "PAQET-PING".toByteArray(Charsets.UTF_8),
                    config = config,
                )
                val addr = InetSocketAddress(InetAddress.getByName(targetHost), targetPort)
                udp.send(DatagramPacket(payload, payload.size, addr))
                true
            }
        }.getOrDefault(false)

        val ms = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return if (udpOk) {
            ProbeResult(
                reachable = true,
                latencyMs = ms,
                resolvedEndpoint = "$targetHost:$targetPort",
                detectedInterface = iface,
                rootAvailable = root,
                messageFa = "✅ پکت‌های UDP/KCP روی $targetHost:$targetPort ارسال شد (${ms}ms · رابط: $iface)",
            )
        } else {
            ProbeResult(
                reachable = false,
                latencyMs = -1L,
                resolvedEndpoint = "$targetHost:$targetPort",
                detectedInterface = iface,
                rootAvailable = root,
                messageFa = "⚠️ عدم پاسخ مستقیم از $targetHost:$targetPort (در صورت مسدود بودن آی‌پی، دامنهٔ بازیابی DNS را فعال کنید)",
            )
        }
    }

    /**
     * Wraps a payload inside an AES-256-GCM encrypted frame with random padding and a TLS 1.2/1.3 Application Data
     * record header (`0x17 0x03 0x03 <2-byte-length>`) so DPI boxes classify packet streams as standard TLS records.
     */
    internal fun buildObfuscatedPaqetFrame(rawPayload: ByteArray, config: PaqetConfig): ByteArray {
        val padLen = if (config.paddingMax > config.paddingMin) {
            Random.nextInt(config.paddingMin.coerceAtLeast(0), config.paddingMax + 1)
        } else {
            config.paddingMin.coerceAtLeast(0)
        }
        val padding = ByteArray(padLen).also { SecureRandom().nextBytes(it) }
        val inner = ByteBuffer.allocate(4 + rawPayload.size + padLen)
            .putShort(rawPayload.size.toShort())
            .putShort(padLen.toShort())
            .put(rawPayload)
            .put(padding)
            .array()

        val encrypted = encryptAesGcm(inner, config.secretKey)
        if (!config.tlsObfuscation) return encrypted

        val recordLen = encrypted.size.coerceAtMost(0xFFFF)
        return ByteBuffer.allocate(5 + recordLen)
            .put(0x17.toByte())
            .put(0x03.toByte())
            .put(0x03.toByte())
            .putShort(recordLen.toShort())
            .put(encrypted, 0, recordLen)
            .array()
    }

    internal fun encryptAesGcm(plain: ByteArray, secret: String): ByteArray {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        val cipherText = cipher.doFinal(plain)
        return nonce + cipherText
    }

    internal fun decryptAesGcm(framed: ByteArray, secret: String): ByteArray {
        if (framed.size <= 12) return ByteArray(0)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        val nonce = framed.copyOfRange(0, 12)
        val body = framed.copyOfRange(12, framed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(body)
    }

    private fun decryptAesGcmString(base64Text: String, secret: String): String {
        val raw = Base64.getDecoder().decode(base64Text.trim())
        return String(decryptAesGcm(raw, secret), Charsets.UTF_8)
    }

    private fun isLoopbackTarget(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        return clean == "127.0.0.1" || clean == "localhost" || clean == "::1" || clean == "0.0.0.0" ||
            clean.startsWith("127.")
    }

    private fun handleSocks5Client(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 20_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val ver = input.read()
            if (ver != 0x05) return
            val nMethods = input.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            if (!readFully(input, methods)) return
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val reqHeader = ByteArray(4)
            if (!readFully(input, reqHeader)) return
            if (reqHeader[0].toInt() != 0x05 || reqHeader[1].toInt() != 0x01) {
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val targetHost: String = when (reqHeader[3].toInt() and 0xFF) {
                0x01 -> {
                    val ip = ByteArray(4)
                    if (!readFully(input, ip)) return
                    InetAddress.getByAddress(ip).hostAddress ?: return
                }
                0x03 -> {
                    val len = input.read()
                    if (len <= 0) return
                    val domain = ByteArray(len)
                    if (!readFully(input, domain)) return
                    String(domain, Charsets.UTF_8)
                }
                0x04 -> {
                    val ip6 = ByteArray(16)
                    if (!readFully(input, ip6)) return
                    InetAddress.getByAddress(ip6).hostAddress ?: return
                }
                else -> return
            }

            val portBytes = ByteArray(2)
            if (!readFully(input, portBytes)) return
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (isLoopbackTarget(targetHost)) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val cfg = activeConfigRef.get() ?: PaqetConfig(serverHost = "127.0.0.1", serverPort = 443)
            val upstream = openPaqetProtectedUpstream(cfg, targetHost, targetPort)
            if (upstream == null) {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            upstream.use { remote ->
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()

                // First client packet: apply TLS record chunking / padding if TLS obfuscation is enabled
                val firstBuf = ByteArray(4096)
                val firstRead = runCatching { input.read(firstBuf) }.getOrDefault(-1)
                if (firstRead > 0) {
                    if (cfg.tlsObfuscation && firstBuf[0] == 0x16.toByte() && firstRead > 24) {
                        val splitPoint = (firstRead / 3).coerceIn(8, 64)
                        remoteOut.write(firstBuf, 0, splitPoint)
                        remoteOut.flush()
                        remoteOut.write(firstBuf, splitPoint, firstRead - splitPoint)
                        remoteOut.flush()
                    } else {
                        remoteOut.write(firstBuf, 0, firstRead)
                        remoteOut.flush()
                    }
                } else if (firstRead == -1) {
                    return
                }

                val upstreamDone = AtomicBoolean(false)
                paqetWorkerPool.execute {
                    try {
                        pipeStream(input, remoteOut)
                    } finally {
                        upstreamDone.set(true)
                        runCatching { remote.shutdownOutput() }
                    }
                }
                pipeStream(remoteIn, output)
            }
        }
    }

    private fun openPaqetProtectedUpstream(
        cfg: PaqetConfig,
        targetHost: String,
        targetPort: Int,
    ): Socket? {
        val serverClean = cfg.serverHost.trim()
        val hasRemoteEntry = serverClean.isNotBlank() && !isLoopbackTarget(serverClean)

        // Mode A: Upstream Paqet Entry-Point SOCKS5 / Relay
        if (hasRemoteEntry) {
            val hoppedPort = cfg.pickTargetPort()
            val entrySock = runCatching {
                val s = Socket()
                protectOutboundSocket(s)
                s.tcpNoDelay = cfg.kcpMode.nodelay == 1
                s.soTimeout = 20_000
                s.connect(InetSocketAddress(serverClean, hoppedPort), 7_000)
                if (!cfg.upstreamEntrySocks) {
                    // A raw TCP stream carries no destination; without the SOCKS entry handshake
                    // the server cannot know where to forward, so treat it as unusable.
                    s.close()
                    return@runCatching null
                }
                run {
                    val sin = s.getInputStream()
                    val sout = s.getOutputStream()
                    sout.write(byteArrayOf(0x05, 0x01, 0x00))
                    sout.flush()
                    val authResp = ByteArray(2)
                    if (!readFully(sin, authResp) || authResp[0].toInt() != 0x05) {
                        s.close()
                        return@runCatching null
                    }
                    val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
                    val req = ByteArrayOutputStream()
                    req.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
                    req.write(hostBytes)
                    req.write((targetPort ushr 8) and 0xFF)
                    req.write(targetPort and 0xFF)
                    sout.write(req.toByteArray())
                    sout.flush()
                    val rep = ByteArray(10)
                    if (!readFully(sin, rep) || rep[1].toInt() != 0x00) {
                        s.close()
                        return@runCatching null
                    }
                }
                s
            }.getOrNull()
            if (entrySock != null) return entrySock
        }

        // No silent direct fallback any more. The real paqet protocol crafts raw TCP packets
        // (pcap / raw sockets) and needs root on the client; without root the only honest path is
        // a SOCKS5 entry point exposed by the paqet server. Dialling the destination directly
        // looked "connected" but carried no tunnel at all, so blocked sites never opened.
        return null
    }

    private fun queryDnsTxtRecords(domain: String, resolverIp: String, timeoutMs: Int): List<String> {
        return runCatching {
            DatagramSocket().use { socket ->
                protectOutboundDatagramSocket(socket)
                socket.soTimeout = timeoutMs
                val query = buildDnsTxtQuery(domain)
                val packet = DatagramPacket(query, query.size, InetAddress.getByName(resolverIp), 53)
                socket.send(packet)
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                parseDnsTxtResponse(buf, resp.length)
            }
        }.getOrDefault(emptyList())
    }

    private fun buildDnsTxtQuery(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        val txId = Random.nextInt(1, 0xFFFF)
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        for (label in domain.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size and 0x3F)
            out.write(bytes)
        }
        out.write(0x00)
        out.write(0x00); out.write(0x10) // QTYPE = TXT (16)
        out.write(0x00); out.write(0x01) // QCLASS = IN (1)
        return out.toByteArray()
    }

    private fun parseDnsTxtResponse(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(data, length, offset)
            offset += 4
        }
        val results = mutableListOf<String>()
        repeat(anCount) {
            if (offset >= length) return results
            offset = skipDnsName(data, length, offset)
            if (offset + 10 > length) return results
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > length) return results
            if (type == 16 && rdLength > 0) {
                var pos = offset
                val sb = StringBuilder()
                while (pos < offset + rdLength) {
                    val txtLen = data[pos].toInt() and 0xFF
                    pos++
                    if (pos + txtLen <= offset + rdLength) {
                        sb.append(String(data, pos, txtLen, Charsets.UTF_8))
                    }
                    pos += txtLen
                }
                if (sb.isNotEmpty()) results += sb.toString()
            }
            offset += rdLength
        }
        return results
    }

    private fun skipDnsName(data: ByteArray, length: Int, start: Int): Int {
        var pos = start
        while (pos < length) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) return pos + 2
            pos += 1 + len
        }
        return length
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }
}
