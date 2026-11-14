package com.cat.client

/**
 * Cat VPS workshop — Iran middlebox → foreign exits. Not 3X-UI, not RegionHop.
 * The phone only copies a script; the user runs it on their own VPS.
 *
 * Two shapes, same hops:
 *  - iptables DNAT (raw TCP)
 *  - Xray-core inbounds + outbounds + routing (inbound tag → outbound tag)
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
        sb.appendLine("# Do not also bind Xray on the same listen ports.")
        sb.appendLine("set -euo pipefail")
        sb.append(CatVpsSh.requireRoot())
        sb.append(CatVpsSh.aptInstall("iptables curl ca-certificates", "iptables curl ca-certificates"))
        sb.append(CatVpsSh.persistForward())
        sb.appendLine("iptables -t nat -N CAT_RELAY 2>/dev/null || iptables -t nat -F CAT_RELAY")
        sb.appendLine("iptables -t nat -C PREROUTING -j CAT_RELAY 2>/dev/null || iptables -t nat -A PREROUTING -j CAT_RELAY")
        hops.forEach { hop ->
            val dest = hostForIptables(hop.foreignHost)
            sb.appendLine("iptables -t nat -A CAT_RELAY -p tcp --dport ${hop.listenPort} -j DNAT --to-destination ${dest}:${hop.foreignPort}")
            sb.appendLine("iptables -t nat -A CAT_RELAY -p udp --dport ${hop.listenPort} -j DNAT --to-destination ${dest}:${hop.foreignPort}")
            sb.append(CatVpsSh.ufwAllow(hop.listenPort, "tcp"))
            sb.append(CatVpsSh.ufwAllow(hop.listenPort, "udp"))
            sb.appendLine("echo \"${hop.tag}: $iran:${hop.listenPort}  ->  ${hop.foreignHost}:${hop.foreignPort}  (tcp+udp)\"")
        }
        sb.appendLine("iptables -t nat -C POSTROUTING -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -j MASQUERADE")
        sb.append(CatVpsSh.dnatForwardAccept())
        sb.append(CatVpsSh.persistIptables())
        sb.appendLine("echo \"Cat relay ready. In Cat: address=$iran  port=<listen>  SNI=foreign host.\"")
        return sb.toString()
    }

    /** Xray JSON: one dokodemo inbound per hop, freedom outbound, routing inboundTag → outboundTag. */
    fun xrayConfig(hops: List<Hop>): String? {
        if (hops.isEmpty()) return null
        val inbounds = hops.map { hop ->
            linkedMapOf(
                "tag" to "in-${hop.tag}",
                "listen" to "0.0.0.0",
                "port" to hop.listenPort,
                "protocol" to "dokodemo-door",
                "settings" to linkedMapOf(
                    "address" to hop.foreignHost,
                    "port" to hop.foreignPort,
                    "network" to "tcp,udp",
                    "followRedirect" to false,
                ),
            )
        }
        val outbounds = hops.map { hop ->
            linkedMapOf(
                "tag" to "out-${hop.tag}",
                "protocol" to "freedom",
                "settings" to emptyMap<String, Any?>(),
            )
        } + listOf(
            linkedMapOf("tag" to "direct", "protocol" to "freedom"),
            linkedMapOf("tag" to "block", "protocol" to "blackhole"),
        )
        val rules = hops.map { hop ->
            linkedMapOf(
                "type" to "field",
                "inboundTag" to listOf("in-${hop.tag}"),
                "outboundTag" to "out-${hop.tag}",
            )
        }
        return XrayJson.write(
            linkedMapOf(
                "log" to mapOf("loglevel" to "warning"),
                "inbounds" to inbounds,
                "outbounds" to outbounds,
                "routing" to linkedMapOf(
                    "domainStrategy" to "AsIs",
                    "rules" to rules,
                ),
            ),
        )
    }

    fun xrayScript(iranHint: String, hops: List<Hop>): String? {
        val json = xrayConfig(hops) ?: return null
        val iran = iranHint.trim().ifBlank { "IRAN_IP" }
        val sb = StringBuilder()
        sb.appendLine("#!/bin/bash")
        sb.appendLine("# Cat VPS Xray — inbounds / outbounds / routing. Run on the IRAN server (root).")
        sb.appendLine("# Official Xray-core only — not a web panel. Do not also run iptables DNAT on the same ports.")
        sb.appendLine("set -euo pipefail")
        sb.appendLine("umask 077")
        sb.append(CatVpsSh.requireRoot())
        sb.append(CatVpsSh.aptInstall("curl ca-certificates unzip", "curl ca-certificates unzip"))
        sb.appendLine("if ! command -v xray >/dev/null 2>&1; then")
        sb.appendLine("  bash -c \"\$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install")
        sb.appendLine("fi")
        sb.appendLine("command -v xray >/dev/null 2>&1 || { echo \"xray install failed\"; exit 1; }")
        sb.appendLine("mkdir -p /usr/local/etc/xray")
        sb.appendLine("if [ -f /usr/local/etc/xray/config.json ]; then cp -a /usr/local/etc/xray/config.json /usr/local/etc/xray/config.json.bak.cat; fi")
        sb.appendLine("cat > /usr/local/etc/xray/config.json << 'CAT_XRAY'")
        sb.appendLine(json)
        sb.appendLine("CAT_XRAY")
        hops.forEach { hop ->
            sb.append(CatVpsSh.ufwAllow(hop.listenPort, "tcp"))
            sb.append(CatVpsSh.ufwAllow(hop.listenPort, "udp"))
            sb.appendLine(
                "echo \"in-${hop.tag}: $iran:${hop.listenPort}  ->  out-${hop.tag} ${hop.foreignHost}:${hop.foreignPort}\"",
            )
        }
        sb.appendLine("systemctl enable xray >/dev/null 2>&1 || true")
        sb.appendLine("if systemctl restart xray; then")
        sb.appendLine("  echo \"xray running\"")
        sb.appendLine("else")
        sb.appendLine("  xray run -c /usr/local/etc/xray/config.json >/var/log/cat-xray.log 2>&1 &")
        sb.appendLine("  echo \"xray started in background\"")
        sb.appendLine("fi")
        sb.appendLine("echo \"Xray ready. In Cat: address=$iran  port=<listen>  SNI=foreign host.\"")
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
