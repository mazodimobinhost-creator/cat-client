package com.cat.client

import java.net.URLDecoder
import java.util.Locale

/**
 * What to show and edit for a row. Engine configs listen on 127.0.0.1; the public
 * IP/location is in the share link (`dnstt://`, `sni_fwd_target`, `@host:port`).
 */
internal object ConnectionPublicEndpoint {
    data class Shown(
        val kind: String,
        val host: String,
        val port: Int,
        val extra: String = "",
        val sni: String = "",
        val wsHost: String = "",
        val shareLink: String = "",
    ) {
        val isLoopback: Boolean get() = isLoopbackHost(host)
        fun authority(): String = if (':' in host && '.' !in host) "[$host]:$port" else "$host:$port"
    }

    fun of(profile: ConnectionProfile): Shown {
        val link = profile.shareLink?.trim().orEmpty()
        parseTunnel(link)?.let { return it }
        parseSniFwd(link)?.let { return it }
        parseStandard(link, profile)?.let { return it }
        return Shown(
            kind = profile.type.uppercase(Locale.US).ifBlank { "PROXY" },
            host = profile.server.trim().ifBlank { "127.0.0.1" },
            port = profile.port,
            shareLink = link,
        )
    }

    fun detailLine(profile: ConnectionProfile): String {
        val s = of(profile)
        val loc = ConnectionLocationPolicy.countryFromText(Locale.ENGLISH, s.host, s.extra, profile.tag)
        return buildString {
            append(s.kind)
            append(" · ")
            append(s.authority())
            if (s.extra.isNotBlank()) append(" · ").append(s.extra)
            if (s.sni.isNotBlank() && s.sni != s.host && s.sni != s.extra) append(" · SNI ").append(s.sni)
            loc?.let { append(" · ").append(it.flag).append(' ').append(it.country) }
        }
    }

    fun applyEdits(link: String, ip: String, port: Int, sni: String, hostHeader: String): String? {
        val cleanIp = ip.trim().removePrefix("[").removeSuffix("]")
        if (cleanIp.isBlank() || port !in 1..65535) return null
        if (isLoopbackHost(cleanIp)) return null
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://").lowercase(Locale.US)
        if (scheme in WhiteDnsSmartScanner.SUPPORTED_TUNNEL_SCHEMES) {
            return rewriteDns(trimmed, cleanIp, port)
        }
        if ("sni_fwd_target=" in trimmed) {
            return rewriteSniFwd(trimmed, cleanIp, port, sni)
        }
        val rewritten = WhiteDnsToolkit.rewriteEndpoint(trimmed, cleanIp, port) ?: return null
        return patchQuery(rewritten, sni.trim(), hostHeader.trim())
    }

    private fun parseTunnel(link: String): Shown? {
        val parsed = WhiteDnsSmartScanner.parseTunnelUri(link) ?: return null
        return Shown(
            kind = "DNSTT",
            host = parsed.resolverIp,
            port = parsed.resolverPort,
            extra = parsed.tunnelDomain,
            shareLink = link,
        )
    }

    private fun parseSniFwd(link: String): Shown? {
        val raw = Regex("sni_fwd_target=([^&#]+)").find(link)?.groupValues?.getOrNull(1) ?: return null
        val target = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val colon = target.lastIndexOf(':')
        if (colon <= 0) return null
        val host = target.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = target.substring(colon + 1).toIntOrNull() ?: 443
        val sni = queryValue(link, "sni")
        return Shown("SPOOF", host, port, extra = sni, sni = sni, shareLink = link)
    }

    private fun parseStandard(link: String, profile: ConnectionProfile): Shown? {
        if (!link.contains("://")) return null
        val rest = link.substringAfter("://").substringBefore('#').substringBefore('?')
        val authority = rest.substringAfterLast('@').substringBefore('/')
        val hp = splitHostPort(authority) ?: return null
        if (isLoopbackHost(hp.first)) return null
        val sni = queryValue(link, "sni")
        val hostHdr = queryValue(link, "host")
        return Shown(
            kind = profile.type.uppercase(Locale.US).ifBlank { link.substringBefore("://").uppercase(Locale.US) },
            host = hp.first,
            port = hp.second,
            extra = hostHdr,
            sni = sni,
            wsHost = hostHdr,
            shareLink = link,
        )
    }

    private fun rewriteDns(link: String, ip: String, port: Int): String {
        val after = link.substringAfter("://")
        val hash = if ('#' in after) "#" + after.substringAfter('#') else ""
        val beforeHash = after.substringBefore('#')
        val query = if ('?' in beforeHash) "?" + beforeHash.substringAfter('?') else ""
        val path = beforeHash.substringBefore('?').substringAfter('/', missingDelimiterValue = "")
        val pathPart = if (path.isNotBlank()) "/$path" else ""
        val scheme = link.substringBefore("://")
        return "$scheme://$ip:$port$pathPart$query$hash"
    }

    private fun rewriteSniFwd(link: String, ip: String, port: Int, sni: String): String {
        val encoded = java.net.URLEncoder.encode("$ip:$port", "UTF-8")
        var out = link.replace(Regex("sni_fwd_target=[^&#]*"), "sni_fwd_target=$encoded")
        val cleanSni = sni.trim()
        if (cleanSni.isNotBlank()) {
            out = if (out.contains("sni=")) {
                out.replace(Regex("([?&])sni=[^&#]*"), "$1sni=${java.net.URLEncoder.encode(cleanSni, "UTF-8")}")
            } else {
                out.replace("sni_fwd_target=", "sni=$cleanSni&sni_fwd_target=")
            }
        }
        return out
    }

    private fun patchQuery(link: String, sni: String, hostHeader: String): String {
        var out = link
        if (sni.isNotBlank()) {
            out = if ("sni=" in out.substringAfter('?', "")) {
                out.replace(Regex("([?&])sni=[^&#]*"), "$1sni=${java.net.URLEncoder.encode(sni, "UTF-8")}")
            } else {
                val hash = if ('#' in out) "#" + out.substringAfter('#') else ""
                val base = out.substringBefore('#')
                val joiner = if ('?' in base) "&" else "?"
                base + joiner + "sni=" + java.net.URLEncoder.encode(sni, "UTF-8") + hash
            }
        }
        if (hostHeader.isNotBlank()) {
            out = if (Regex("[?&]host=").containsMatchIn(out)) {
                out.replace(Regex("([?&])host=[^&#]*"), "$1host=${java.net.URLEncoder.encode(hostHeader, "UTF-8")}")
            } else {
                val hash = if ('#' in out) "#" + out.substringAfter('#') else ""
                val base = out.substringBefore('#')
                val joiner = if ('?' in base) "&" else "?"
                base + joiner + "host=" + java.net.URLEncoder.encode(hostHeader, "UTF-8") + hash
            }
        }
        return out
    }

    private fun queryValue(link: String, key: String): String {
        val query = link.substringAfter("://").substringBefore('#').substringAfter('?', "")
        if (query.isBlank()) return ""
        query.split('&').forEach { pair ->
            if (pair.substringBefore('=') == key) {
                return runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
            }
        }
        return ""
    }

    private fun splitHostPort(authority: String): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end < 0) return null
            val host = trimmed.substring(1, end)
            val port = trimmed.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        return trimmed.substring(0, colon) to port
    }

    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        return h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "0.0.0.0"
    }
}
