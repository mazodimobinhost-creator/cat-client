package com.cat.client

/**
 * 🛡️ Cat Voidly — free WireGuard `.conf` from voidly.ai/vpn.
 *
 * Keys are made in the browser; Cat does not mint them and does not call an unpublished
 * issue API. The user generates the file, then Cat imports it like any other WireGuard
 * conf (no official WireGuard app). Transport is UDP only — some Iranian operators drop it.
 */
internal object VoidlyPlanner {
    const val PAGE = "https://voidly.ai/vpn"
    const val NAME = "🛡️ Cat Voidly"

    fun looksLikeConf(text: String): Boolean = WireGuardConfText.looksLikeConf(text)

    fun rowLabel(text: String): String {
        val endpoint = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && '=' in it }
            ?.substringAfter('=')
            ?.trim()
            ?.substringBefore(' ')
            .orEmpty()
        return if (endpoint.isNotBlank()) "Voidly $endpoint" else "Voidly"
    }

    fun subscriptionText(links: List<String>): String =
        "# 🛡️ Cat Voidly\n" + links.distinct().joinToString("\n")
}
