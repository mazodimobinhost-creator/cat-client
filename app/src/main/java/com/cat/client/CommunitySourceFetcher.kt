package com.cat.client

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface CommunityFetchResult {
    data class Success(val source: CommunitySource, val links: List<String>, val bytes: Int) : CommunityFetchResult
    data class Failure(val source: CommunitySource, val reason: Reason) : CommunityFetchResult
    enum class Reason { INVALID_SOURCE, HTTP, TOO_LARGE, NETWORK, EMPTY }
}

/** Manual/page-visible fetch only. This class does not schedule background work. */
internal class CommunitySourceFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) {
    fun fetch(source: CommunitySource): CommunityFetchResult {
        val valid = CommunitySourceRegistry.validate(source)
            ?: return CommunityFetchResult.Failure(source, CommunityFetchResult.Reason.INVALID_SOURCE)
        val connection = runCatching { URL(valid.url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.NETWORK)
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain,application/octet-stream;q=0.8")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode !in 200..299) return CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.HTTP)
            val declared = connection.contentLengthLong
            if (declared > valid.maxBytes) return CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.TOO_LARGE)
            val output = ByteArrayOutputStream(minOf(valid.maxBytes, 64 * 1024))
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > valid.maxBytes) return CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            val links = CommunitySourceRegistry.extractLinks(output.toString(Charsets.UTF_8.name()))
            if (links.isEmpty()) CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.EMPTY)
            else CommunityFetchResult.Success(valid, links, output.size())
        } catch (_: Exception) {
            CommunityFetchResult.Failure(valid, CommunityFetchResult.Reason.NETWORK)
        } finally {
            connection.disconnect()
        }
    }
}
