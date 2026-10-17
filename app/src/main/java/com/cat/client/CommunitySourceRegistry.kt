package com.cat.client

import java.net.URI

internal data class CommunitySource(
    val id: String,
    val label: String,
    val url: String,
    val license: String,
    val enabledByDefault: Boolean = false,
    val refreshHours: Int = 6,
    val maxBytes: Int = 2 * 1024 * 1024,
)

internal object CommunitySourceRegistry {
    val sources = listOf(
        CommunitySource("v2go-vless", "v2go · VLESS", "https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Protocol/vless.txt", "GPL-3.0"),
        CommunitySource("v2go-trojan", "v2go · Trojan", "https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Protocol/trojan.txt", "GPL-3.0"),
    )

    fun validate(source: CommunitySource): CommunitySource? {
        if (!source.id.matches(Regex("[a-z0-9-]{3,48}")) || source.enabledByDefault) return null
        val uri = runCatching { URI(source.url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "raw.githubusercontent.com" || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        if (source.refreshHours !in 1..168 || source.maxBytes !in 1_024..2 * 1024 * 1024) return null
        return source
    }

    fun extractLinks(text: String, maxEntries: Int = 500): List<String> {
        if (text.toByteArray().size > 2 * 1024 * 1024 || maxEntries !in 1..500) return emptyList()
        return text.lineSequence()
            .map(String::trim)
            .filter { it.length in 16..4096 && (it.startsWith("vless://", true) || it.startsWith("trojan://", true)) }
            .filter(::safePublicLink)
            .distinct()
            .take(maxEntries)
            .toList()
    }

    private fun safePublicLink(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank() || uri.port !in 1..65535 || uri.userInfo.isNullOrBlank()) return false
        if (host == "localhost" || host == "::1" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") || host.startsWith("fc") || host.startsWith("fd") || Regex("^fe[89ab]").containsMatchIn(host)) return false
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val second = parts[1].toInt()
            if (parts[0] == "172" && second in 16..31) return false
        }
        return true
    }
}
