package com.cat.client

import java.net.URI

internal enum class DeploymentTarget { WORKER, PAGES, RAILWAY }
internal enum class DeploymentProbeVerdict { SUCCESS, FAILED, INCONCLUSIVE, NOT_CONFIGURED }

internal data class DeploymentProfile(
    val id: String,
    val name: String,
    val target: DeploymentTarget,
    val baseUrl: String,
    val enabled: Boolean = true,
) {
    fun normalized(): DeploymentProfile? {
        val cleanId = id.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: return null
        val cleanName = name.trim().take(80).ifBlank { return null }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank() || uri.port != -1 || uri.query != null || uri.fragment != null) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isNotEmpty()) return null
        return copy(id = cleanId, name = cleanName, baseUrl = "https://${uri.host.lowercase()}")
    }
}

internal object DeploymentProbePolicy {
    fun classify(
        profile: DeploymentProfile?,
        networkBefore: String?,
        networkAfter: String?,
        captivePortal: Boolean,
        healthStatus: Int?,
        versionStatus: Int?,
    ): DeploymentProbeVerdict {
        if (profile?.normalized() == null || profile.enabled.not()) return DeploymentProbeVerdict.NOT_CONFIGURED
        if (captivePortal || networkBefore.isNullOrBlank() || networkBefore != networkAfter) return DeploymentProbeVerdict.INCONCLUSIVE
        return if (healthStatus in 200..299 && versionStatus in 200..299) DeploymentProbeVerdict.SUCCESS else DeploymentProbeVerdict.FAILED
    }
}
