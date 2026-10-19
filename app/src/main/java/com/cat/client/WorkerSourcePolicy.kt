package com.cat.client

import java.net.URI

internal sealed interface WorkerSourceDecision {
    data class Allowed(val normalizedUrl: String) : WorkerSourceDecision
    data class Blocked(val reason: String) : WorkerSourceDecision
}

/** Input policy for the installer's Raw-URL mode. The app never acts as an
 * arbitrary network proxy and rejects local, metadata, credential-bearing and
 * non-HTTPS sources before downloading any bytes. */
internal object WorkerSourcePolicy {
    const val MAX_SOURCE_BYTES = 2 * 1024 * 1024L

    private val blockedHosts = setOf(
        "localhost", "localhost.localdomain", "metadata.google.internal",
        "169.254.169.254", "100.100.100.200",
    )

    fun validateUrl(raw: String): WorkerSourceDecision {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: return WorkerSourceDecision.Blocked("invalid-url")
        if (!uri.scheme.equals("https", ignoreCase = true)) return WorkerSourceDecision.Blocked("https-required")
        if (!uri.userInfo.isNullOrBlank()) return WorkerSourceDecision.Blocked("url-credentials-forbidden")
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return WorkerSourceDecision.Blocked("host-required")
        if (host in blockedHosts || host.endsWith(".localhost")) return WorkerSourceDecision.Blocked("local-host-forbidden")
        if (isPrivateIpv4(host) || host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) {
            return WorkerSourceDecision.Blocked("private-address-forbidden")
        }
        return WorkerSourceDecision.Allowed(uri.normalize().toASCIIString())
    }

    fun validateDownloadedSize(contentLength: Long?, actualBytes: Long): WorkerSourceDecision {
        if (contentLength != null && contentLength > MAX_SOURCE_BYTES) return WorkerSourceDecision.Blocked("source-too-large")
        if (actualBytes < 1L) return WorkerSourceDecision.Blocked("source-empty")
        if (actualBytes > MAX_SOURCE_BYTES) return WorkerSourceDecision.Blocked("source-too-large")
        return WorkerSourceDecision.Allowed("bytes:$actualBytes")
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            octets[0] == 0
    }
}
