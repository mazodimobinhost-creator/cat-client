package com.cat.client

import org.json.JSONArray
import org.json.JSONObject

internal data class PanelIpsPushOutcome(
    val stored: Int,
    val missing: List<String>,
    val persisted: Boolean,
    val added: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
    val noKv: Boolean = !persisted,
)

/** Verifies `POST /api/ips` responses by checking `persisted` (KV bound) and
 * confirming each requested `host:port` authority is present in the read-back `ips` list,
 * while also surfacing `added`, `duplicates`, `invalid` and `noKv` from the shared validator. */
internal object PanelIpsReadBack {
    fun verify(rawJson: String, requestedLines: List<String>): PanelIpsPushOutcome {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return PanelIpsPushOutcome(0, requestedLines, persisted = false)
        if (!root.optBoolean("ok", false)) {
            return PanelIpsPushOutcome(0, requestedLines, persisted = false)
        }
        val persisted = root.has("persisted") && root.optBoolean("persisted", false)
        val noKv = !persisted || root.optBoolean("noKv", false)
        val arr = root.optJSONArray("ips")
            ?: return PanelIpsPushOutcome(0, requestedLines, persisted = false, noKv = noKv)
        val storedAuthorities = HashSet<String>()
        for (i in 0 until arr.length()) {
            val raw = arr.optString(i)?.substringBefore('#')?.trim().orEmpty()
            VerifiedEndpointParser.parseAuthority(
                raw, EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, 0L, "panel-ips",
            )?.authority?.lowercase()?.let(storedAuthorities::add)
        }
        val requestedAuthorities = requestedLines.mapNotNull { line ->
            line.substringBefore('#').trim().takeIf { it.isNotEmpty() }
        }
        val matched = requestedAuthorities.count { it.lowercase() in storedAuthorities }
        val missing = requestedAuthorities.filter { it.lowercase() !in storedAuthorities }
        return PanelIpsPushOutcome(
            stored = matched,
            missing = missing,
            persisted = persisted,
            added = parseStringList(root.optJSONArray("added")),
            duplicates = parseStringList(root.optJSONArray("duplicates")),
            invalid = parseStringList(root.optJSONArray("invalid")),
            noKv = noKv,
        )
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i)?.trim().orEmpty()
                if (item.isNotEmpty()) add(item)
            }
        }
    }
}
