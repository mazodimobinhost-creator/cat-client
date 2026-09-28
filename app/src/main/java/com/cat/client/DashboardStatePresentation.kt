package com.cat.client

import androidx.annotation.StringRes

enum class DashboardTone {
    Neutral,
    Progress,
    Connected,
    Error,
}

data class DashboardStatePresentation(
    @param:StringRes val titleRes: Int,
    val tone: DashboardTone,
    val showProgress: Boolean,
) {
    val showTransferSpeeds: Boolean
        get() = tone == DashboardTone.Connected
}

object DashboardStatePresenter {
    fun forState(state: VpnState): DashboardStatePresentation = when (state) {
        VpnState.Stopped -> DashboardStatePresentation(
            titleRes = R.string.state_ready,
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        VpnState.Starting -> DashboardStatePresentation(
            titleRes = R.string.state_connecting,
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.Started -> DashboardStatePresentation(
            titleRes = R.string.state_connected,
            tone = DashboardTone.Connected,
            showProgress = false,
        )
        VpnState.Stopping -> DashboardStatePresentation(
            titleRes = R.string.state_disconnecting,
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.DailyLimitReached -> DashboardStatePresentation(
            titleRes = R.string.state_daily_limit,
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        is VpnState.Error -> DashboardStatePresentation(
            titleRes = R.string.state_connection_error,
            tone = DashboardTone.Error,
            showProgress = false,
        )
    }
}

object ConnectionDetailsPresenter {
    fun forProfile(
        profile: ConnectionProfile,
        showServer: Boolean = false,
        latencyMs: Long? = null,
        frontingIp: String = "",
        stringFor: (Int) -> String = ::englishString,
    ): String {
        val normalizedType = profile.type.lowercase()
        val outbound = when (normalizedType) {
            "mihomo-group" -> "Mihomo"
            "wireguard" -> "WireGuard"
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "ss" -> "Shadowsocks"
            "trojan" -> "Trojan"
            else -> profile.type
        }
        val ech = when {
            normalizedType == "wireguard" -> stringFor(R.string.connection_detail_ech_not_applicable)
            normalizedType == "mihomo-group" -> stringFor(R.string.connection_detail_ech_unknown)
            !profile.echCapable -> stringFor(R.string.connection_detail_ech_not_applicable)
            profile.echEnabled -> stringFor(R.string.connection_detail_ech_enabled)
            else -> stringFor(R.string.connection_detail_ech_disabled)
        }
        return buildList {
            add(stringFor(R.string.connection_detail_outbound).format(outbound))
            latencyMs?.takeIf { it > 0L }?.let {
                add(stringFor(R.string.connection_detail_ping).format(it))
            }
            val endpoint = frontingIp.takeIf { it.isNotBlank() }
                ?: profile.server.takeIf { isIpLiteral(it) }
            endpoint?.let {
                add(stringFor(R.string.connection_detail_edge_ip).format(it))
            }
            if (showServer && profile.server != endpoint) add(truncateServer(profile.server))
            profile.amneziaNoise?.let {
                add(stringFor(R.string.connection_detail_amnezia).format(it.count, it.minSize, it.maxSize))
            }
            add(ech)
        }.joinToString("  •  ")
    }

    private fun englishString(@StringRes id: Int): String = when (id) {
        R.string.connection_detail_outbound -> "%1\$s outbound"
        R.string.connection_detail_ping -> "%1\$d ms"
        R.string.connection_detail_edge_ip -> "edge %1\$s"
        R.string.connection_detail_ech_not_applicable -> "ECH not applicable"
        R.string.connection_detail_ech_unknown -> "ECH unknown"
        R.string.connection_detail_ech_enabled -> "ECH enabled"
        R.string.connection_detail_ech_disabled -> "ECH disabled"
        R.string.connection_detail_amnezia -> "Amnezia %1\$d×%2\$d–%3\$d B"
        else -> error("Unsupported connection detail string: $id")
    }

    private fun isIpLiteral(value: String): Boolean {
        val normalized = value.trim().removePrefix("[").removeSuffix("]")
        return normalized.contains(":") ||
            normalized.split(".").let { parts ->
                parts.size == 4 && parts.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
            }
    }

    private fun truncateServer(server: String, maxLength: Int = 24): String =
        server.trim().let { value ->
            if (value.length <= maxLength) value else value.take(maxLength - 1) + "…"
        }
}
