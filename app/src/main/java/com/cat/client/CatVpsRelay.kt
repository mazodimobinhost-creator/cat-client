package com.cat.client

/**
 * Cat VPS workshop — Iran middlebox → foreign exits. Not 3X-UI, not RegionHop.
 * The phone only copies a script; the user runs it on their own VPS.
 */
internal object CatVpsRelay {
    data class Hop(
        val tag: String,
        val foreignHost: String,
        val foreignPort: Int,
        val listenPort: Int,
    )

    fun parseHops(extraLines: String, defaultForeign: String, defaultPort: Int): List<Hop> {
        val out = ArrayList<Hop>()
        val seenPorts = HashSet<Int>()
        extraLines.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@forEach
            val tag = parts[0].trim().lowercase().replace(Regex("[^a-z0-9-]"), "").ifBlank { return@forEach }
            val hp = splitHostPort(parts[1].trim(), defaultPort) ?: return@forEach
            var listen = 10_000 + (out.size * 10) + 443
            while (listen in seenPorts || listen == 443 && out.isNotEmpty()) listen++
            if (listen !in 1..65535) return@forEach
            seenPorts += listen
            out += Hop(tag, hp.first, hp.second, listen)
        }
        val main = defaultForeign.trim().removePrefix("[").removeSuffix("]")
        if (main.isNotBlank() && isHost(main) && defaultPort in 1..65535) {
            return listOf(Hop("exit", main, defaultPort, 443)) + out
        }
        return out
    }

    fun script(iranHint: String, hops: List<Hop>): String? {
        if (hops.isEmpty()) return null
        val iran = iranHint.trim().ifBlank { "IRAN_IP" }
        val sb = StringBuilder()
        sb.appendLine("#!/bin/bash")
        sb.appendLine("# Cat VPS relay — run on the IRAN server (needs root).")
        sb.appendLine("# After this, paste configs in Cat with address=$iran")
        sb.appendLine("# and the listen port below; keep SNI/Host as the foreign server.")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        sb.appendLine("sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>/dev/null || true")
        sb.appendLine("iptables -t nat -N CAT_RELAY 2>/dev/null || iptables -t nat -F CAT_RELAY")
        sb.appendLine("iptables -t nat -C PREROUTING -j CAT_RELAY 2>/dev/null || iptables -t nat -A PREROUTING -j CAT_RELAY")
        hops.forEach { hop ->
            val dest = hostForIptables(hop.foreignHost)
            sb.appendLine("iptables -t nat -A CAT_RELAY -p tcp --dport ${hop.listenPort} -j DNAT --to-destination ${dest}:${hop.foreignPort}")
            sb.appendLine("echo \"${hop.tag}: $iran:${hop.listenPort}  ->  ${hop.foreignHost}:${hop.foreignPort}\"")
        }
        sb.appendLine("iptables -t nat -C POSTROUTING -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -j MASQUERADE")
        sb.appendLine("echo \"Cat relay ready. In Cat: address=$iran  port=<listen>  SNI=foreign host.\"")
        return sb.toString()
    }

    fun splitHostPort(raw: String, defaultPort: Int): Pair<String, Int>? {
        val s = raw.trim()
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            if (!isHost(host) || port !in 1..65535) return null
            return host to port
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) {
            val host = s.substring(0, colon)
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            if (!isHost(host) || port !in 1..65535) return null
            return host to port
        }
        if (!isHost(s) || defaultPort !in 1..65535) return null
        return s to defaultPort
    }

    fun isHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isBlank() || h.length > 253 || ' ' in h || '/' in h) return false
        if (h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "0.0.0.0") return false
        return h.any { it.isLetterOrDigit() }
    }

    private fun hostForIptables(host: String): String =
        if (':' in host && '.' !in host) "[$host]" else host
}
