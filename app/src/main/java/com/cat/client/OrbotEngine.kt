package com.cat.client

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Orbot / Tor engine: a third path that does not depend on any of our own infrastructure.
 *
 * WHY IT IS HERE: everything else in the app is a tunnel to *our* edge or to a subscription —
 * if that edge is blocked as a whole (or the panel is unreachable), there is nothing left to
 * dial. Orbot runs Tor on the same phone and exposes a SOCKS5 listener on loopback, so the
 * whole traffic path becomes: app → 127.0.0.1:9050 → (Orbot's own pluggable transport, e.g.
 * dnstt/WebTunnel) → Tor. The app does not need to know which transport Orbot used, which is
 * the point: the owner can change it inside Orbot without re-installing anything.
 *
 * The engine is a *plain socks5 outbound* for the bundled mihomo core. The core dials it,
 * `udp: false` because Tor's SOCKS5 has no UDP relay, and the whole runtime routes through it
 * while the switch is on ([activate] forces the global-proxy routing shape so no subscription
 * rule can quietly send traffic around it).
 *
 * Loopback is not an arbitrary restriction: Orbot listens on the phone itself, and a
 * "127.0.0.1" that is not loopback would mean a stranger's SOCKS proxy owning the exit. The
 * normalizer refuses anything else instead of silently picking a route the owner did not ask
 * for.
 */
internal data class OrbotProxySettings(
    val host: String = OrbotEnginePolicy.DEFAULT_HOST,
    val port: Int = OrbotEnginePolicy.DEFAULT_PORT,
)

internal object OrbotEnginePolicy {
    const val DEFAULT_HOST = "127.0.0.1"
    /** Orbot's SOCKS5 port (the one its own UI calls «SOCKS»). */
    const val DEFAULT_PORT = 9050
    /** The mihomo proxy name. Prefixed so it cannot collide with a subscription's own names. */
    const val PROXY_NAME = "cat-orbot"

    /** Loopback only: `127.0.0.0/8` and `::1`. */
    fun normalizeHost(value: String): String {
        val host = value.trim().lowercase().removeSurrounding("[", "]")
        val loopback = host == "localhost" ||
            host == "::1" ||
            (host.startsWith("127.") && host.split('.').size == 4 && host.split('.').drop(1).all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
            })
        require(loopback) { "آدرسِ Orbot باید روی همین گوشی باشد (127.0.0.1 یا ::1)" }
        return if (host == "localhost") DEFAULT_HOST else host
    }

    fun normalizePort(value: Int): Int {
        require(value in 1..65535) { "پورتِ Orbot باید عددی بین 1 تا 65535 باشد" }
        return value
    }

    /**
     * The mihomo proxy block, indented for a `proxies:` list. `udp: false` is deliberate: a
     * SOCKS5 relay inside Tor carries no UDP, and claiming otherwise would make QUIC traffic
     * look tunneled while it is dropped.
     */
    fun proxyBlockYaml(host: String, port: Int, name: String = PROXY_NAME): String = buildString {
        append("  - name: $name\n")
        append("    type: socks5\n")
        append("    server: $host\n")
        append("    port: $port\n")
        append("    udp: false\n")
    }

    /**
     * Insert (or replace) the Orbot proxy inside the `proxies:` list of a runtime YAML.
     *
     * Text surgery on purpose: the runtime YAML is the subscription as the owner received it,
     * and re-serialising it through a YAML library would rewrite formatting, comments and
     * anchors we did not create. Replacing an entry with the same name is what makes this
     * idempotent — a runtime that is rebuilt on every reconnect must not grow a second copy.
     */
    fun injectProxy(
        rawYaml: String,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        name: String = PROXY_NAME,
    ): String {
        val block = proxyBlockYaml(host, port, name).trimEnd('\n').split('\n')
        val lines = rawYaml.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        val section = lines.indexOfFirst { it == "proxies:" && !it.startsWith(" ") }
        if (section < 0) {
            val trimmed = lines.dropLastWhile(String::isBlank)
            val out = trimmed + listOf("", "proxies:") + block
            return out.joinToString("\n") + "\n"
        }
        // The list ends at the next top-level line (or the end of the document).
        var end = section + 1
        while (end < lines.size) {
            val line = lines[end]
            if (line.isNotBlank() && !line.first().isWhitespace()) break
            end++
        }
        val itemPrefix = "  - "
        val existing = (section + 1 until end).firstOrNull { index ->
            lines[index].trimStart().startsWith("- ") &&
                lines[index].trim().removePrefix("- name:").trim() == name
        }
        if (existing != null) {
            var stop = existing + 1
            while (stop < end) {
                val line = lines[stop]
                // A new list item sits at the list's own indent (0 or 2 spaces); deeper
                // lines belong to the entry itself and must not end the block early.
                val isNewItem = line.startsWith("- ") || line.startsWith(itemPrefix)
                if (line.isBlank() || isNewItem) break
                stop++
            }
            repeat(stop - existing) { lines.removeAt(existing) }
            end -= (stop - existing)
        }
        val insertAt = (section + 1 until end).lastOrNull { lines[it].isNotBlank() }?.plus(1) ?: (section + 1)
        lines.addAll(insertAt, block)
        return lines.joinToString("\n")
    }

    fun containsProxy(rawYaml: String, name: String = PROXY_NAME): Boolean {
        return rawYaml.replace("\r\n", "\n").lineSequence().any { line ->
            line.trimStart().startsWith("- ") && line.trim().removePrefix("- name:").trim() == name
        }
    }
}

/** The result of one probe of Orbot's SOCKS port. */
internal data class OrbotProbeResult(
    val reachable: Boolean,
    val latencyMs: Long?,
    val detail: String,
)

/**
 * A TCP connect to Orbot's SOCKS port — the only honest way to answer «is Orbot running?».
 *
 * The SOCKS handshake is deliberately NOT completed: a listener that answers TCP is what the
 * core needs to be able to dial, and Orbot refuses a SOCKS request it cannot serve anyway.
 * A probe that took longer than its timeout is reported as *unreachable*, never as «maybe»:
 * a route that hangs is worse than one that fails instantly.
 */
internal object OrbotProbe {
    fun probe(
        host: String = OrbotEnginePolicy.DEFAULT_HOST,
        port: Int = OrbotEnginePolicy.DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): OrbotProbeResult {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            OrbotProbeResult(true, elapsed, "connected")
        } catch (error: Exception) {
            OrbotProbeResult(false, null, error.javaClass.simpleName + ": " + (error.message ?: ""))
        }
    }

    const val DEFAULT_TIMEOUT_MS = 400
}

internal class OrbotEnginePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_orbot", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun readHost(): String {
        return runCatching { OrbotEnginePolicy.normalizeHost(prefs.getString(KEY_HOST, null) ?: "") }
            .getOrDefault(OrbotEnginePolicy.DEFAULT_HOST)
    }

    fun readPort(): Int {
        return OrbotEnginePolicy.normalizePort(prefs.getInt(KEY_PORT, OrbotEnginePolicy.DEFAULT_PORT))
    }

    fun save(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_HOST, OrbotEnginePolicy.normalizeHost(host))
            .putInt(KEY_PORT, OrbotEnginePolicy.normalizePort(port))
            .apply()
    }

    /** Explicit owner evidence only; SOCKS success cannot reveal Orbot's hidden transport. */
    fun readDnsttEvidence(): DnsttTransportEvidence = runCatching {
        DnsttTransportEvidence.valueOf(prefs.getString(KEY_DNSTT_EVIDENCE, null).orEmpty())
    }.getOrDefault(DnsttTransportEvidence.UNKNOWN)

    fun setDnsttEvidence(evidence: DnsttTransportEvidence) {
        prefs.edit().putString(KEY_DNSTT_EVIDENCE, evidence.name).apply()
    }

    /** What the runtime builder needs — or null while the engine is off. */
    fun readActiveSettings(): OrbotProxySettings? {
        if (!isEnabled()) return null
        return OrbotProxySettings(readHost(), readPort())
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_DNSTT_EVIDENCE = "dnstt_transport_evidence"
    }
}
