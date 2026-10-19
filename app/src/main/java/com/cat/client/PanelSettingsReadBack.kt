package com.cat.client

import org.json.JSONArray
import org.json.JSONObject

internal data class PanelSettingsSnapshot(
    val kvAvailable: Boolean,
    val endpoints: List<VerifiedEndpoint>,
    val ignored: List<String> = emptyList(),
    val capped: List<String> = emptyList(),
)

/** Parses real `/api/settings` GET/PUT JSON responses into verified endpoints and
 * serializes typed payloads so panel writes are checked against what the Worker
 * actually stored in KV. */
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
        val rawProxyAndRelays = settings.optJSONArray("proxyIps").strings() +
            settings.optJSONArray("relayUris").strings() +
            settings.optJSONArray("relays").strings()
        for (raw in rawProxyAndRelays) {
            parseProxyOrRelay(raw, checkedAtMs)?.let(endpoints::add)
        }
        val rawChains = settings.optJSONArray("tunnelChains").strings() +
            settings.optJSONArray("chains").strings() +
            settings.optString("chain", "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (raw in rawChains) {
            parseChainEndpoint(raw, checkedAtMs)?.let(endpoints::add)
        }
        return PanelSettingsSnapshot(
            kvAvailable = kvOk,
            endpoints = endpoints.distinctBy { Triple(it.role, it.host.lowercase(), it.port) },
            ignored = root.optJSONArray("ignored").strings(),
            capped = root.optJSONArray("capped").strings(),
        )
    }

    fun verifySingle(
        requested: VerifiedEndpoint,
        beforeJson: String?,
        afterJson: String?,
        checkedAtMs: Long,
    ): PanelEndpointSyncResult {
        val before = beforeJson?.let { parse(it, checkedAtMs)?.endpoints }.orEmpty()
        val after = afterJson?.let { parse(it, checkedAtMs) }
        return PanelEndpointSync.verify(
            requested = requested,
            before = before,
            after = after?.endpoints,
            kvAvailable = after?.kvAvailable == true,
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

    /** Builds a non-destructive JSON body for `PUT /api/settings`, merging any
     * existing verified endpoints on distinct `role + host + port` with the new
     * typed `PanelEndpointPayload`. */
    fun toSettingsPutBody(
        payload: PanelEndpointPayload,
        existing: PanelSettingsSnapshot? = null,
    ): String {
        val existingBatch = existing?.let {
            PanelEndpointBatch.build(it.endpoints.map { ep -> ep to null }).payload
        } ?: PanelEndpointPayload()

        val cleanIps = (existingBatch.cleanIps + payload.cleanIps).distinct()
        val proxyIps = (existingBatch.proxyIps + payload.proxyIps).distinct()
        val relayUris = (existingBatch.relayUris + payload.relayUris).distinct()
        val tunnelChains = (existingBatch.tunnelChains + payload.tunnelChains).distinct()

        return JSONObject().apply {
            if (cleanIps.isNotEmpty()) put("ips", JSONArray(cleanIps))
            if (proxyIps.isNotEmpty()) put("proxyIps", JSONArray(proxyIps))
            if (relayUris.isNotEmpty()) put("relayUris", JSONArray(relayUris))
            if (tunnelChains.isNotEmpty()) {
                put("tunnelChains", JSONArray(tunnelChains))
                put("chain", tunnelChains.first())
            }
        }.toString()
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
