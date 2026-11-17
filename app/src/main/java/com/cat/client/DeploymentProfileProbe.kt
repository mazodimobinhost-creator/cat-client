package com.cat.client

import java.net.HttpURLConnection
import java.net.URL

internal class DeploymentProfileProbe(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class HttpEvidence(val healthStatus: Int?, val versionStatus: Int?, val latencyMs: Long)

    fun execute(
        profile: DeploymentProfile,
        networkBefore: String?,
        networkAfter: () -> String?,
        captivePortal: () -> Boolean,
    ): DeploymentProbeMeasurement {
        val normalized = profile.normalized()
        if (normalized == null || !profile.enabled) return DeploymentProbeMeasurement(profile, DeploymentProbeVerdict.NOT_CONFIGURED, -1, clock())
        val started = clock()
        val health = requestStatus(normalized.baseUrl + "/health")
        val version = requestStatus(normalized.baseUrl + "/api/version")
        val finished = clock()
        val verdict = DeploymentProbePolicy.classify(normalized, networkBefore, networkAfter(), captivePortal(), health, version)
        return DeploymentProbeMeasurement(normalized, verdict, (finished - started).coerceAtLeast(0), finished)
    }

    private fun requestStatus(url: String): Int? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.responseCode
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
