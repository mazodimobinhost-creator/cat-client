package com.cat.client

import org.json.JSONArray
import org.json.JSONObject

internal data class PanelSettingsSnapshot(
    val kvAvailable: Boolean,
    val endpoints: List<VerifiedEndpoint>,
    val ignored: List<String> = emptyList(),
    val capped: List<String> = emptyList(),
)

/** Parses real `/api/settings` GET/PUT JSON responses into verified endpoints so
 * panel writes are checked against what the Worker actually stored in KV. */
internal object PanelSettingsReadBack {
    fun parse(rawJson: String, checkedAtMs: Long): PanelSettingsSnapshot? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        if (!root.optBoolean("ok", false)) return null
        val settings = root.optJSONObject("settings") ?: return null
        val hasKvFlag = root.has("kv") || root.has("persisted")
        val kvOk = (!root.has("kv") || root.optBoolean("kv", false)) &&
            (!root.has("persisted") || root.optBoolean("persisted", false)) && hasKvFlag

        val endpoints = ArrayList<VerifiedEndpoint>()
        for (raw in settings.optJSONArray("ips").strings()) {
            val clean = raw.substringBefore('#').trim()
            VerifiedEndpointParser.parseAuthority(
                clean, EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, checkedAtMs, "panel-readback",
            )?.let(endpoints::add)
        }
        for (raw in settings.optJSONArray("proxyIps").strings()) {
            parseProxyOrRelay(raw, checkedAtMs)?.let(endpoints::add)
        }
        val chain = settings.optString("chain", "").trim()
        if (chain.isNotEmpty()) {
            parseChainEndpoint(chain, checkedAtMs)?.let(endpoints::add)
        }
        return PanelSettingsSnapshot(
            kvAvailable = kvOk,
            endpoints = endpoints,
            ignored = root.optJSONArray("ignored").strings(),
            capped = root.optJSONArray("capped").strings(),
        )
    }

    fun verifyBatch(
        requested: List<VerifiedEndpoint>,
        beforeJson: String?,
        afterJson: String?,
        checkedAtMs: Long,
    ): PanelEndpointBatchSyncResult {
        val before = beforeJson?.let { parse(it, checkedAtMs)?.endpoints }.orEmpty()
        val after = afterJson?.let { parse(it, checkedAtMs) }
        return PanelEndpointBatchSync.verify(
            requested = requested,
            before = before,
            after = after?.endpoints,
            kvAvailable = after?.kvAvailable == true,
        )
    }

    private fun parseProxyOrRelay(raw: String, checkedAtMs: Long): VerifiedEndpoint? {
        val clean = raw.substringBefore('#').trim()
        if (clean.isEmpty()) return null
        val normalized = clean.replaceFirst(Regex("^socks5h://", RegexOption.IGNORE_CASE), "socks5://")
        if (Regex("^(socks4|socks5|http|https)://", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
            return VerifiedRelayParser.parse(normalized, checkedAtMs, EndpointVerdict.VERIFIED, "panel-readback")?.endpoint
        }
        return VerifiedEndpointParser.parseAuthority(
            clean, EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, checkedAtMs, "panel-readback",
        )
    }

    private fun parseChainEndpoint(raw: String, checkedAtMs: Long): VerifiedEndpoint? {
        val clean = raw.substringBefore('#').trim()
        if (Regex("^(vless|trojan)://", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return VerifiedTunnelChainParser.parse(clean, checkedAtMs, EndpointVerdict.VERIFIED, "panel-readback")?.endpoint
        }
        return parseProxyOrRelay(clean, checkedAtMs)
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }
}
