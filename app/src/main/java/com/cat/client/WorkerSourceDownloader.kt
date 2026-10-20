package com.cat.client

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DownloadedWorkerSource(
    val finalUrl: String,
    val bytes: ByteArray,
    val sha256: String,
    val contentType: String,
) {
    fun verifyManifest(
        channel: WorkerArtifactChannel = WorkerArtifactChannel.RAW_URL,
        expectedSha256: String? = null,
    ): WorkerManifestDecision = WorkerArtifactManifest.verify(
        channel = channel,
        bytes = bytes,
        expectedSha256 = expectedSha256,
    )

    fun staticAnalysis(): WorkerStaticAnalysis = WorkerStaticAnalyzer.analyzeLocal(bytes)

    fun planDeployment(
        target: CloudflareTargetSelection,
        approval: CloudflareOperationApproval,
        currentVersion: String? = null,
        currentBindings: WorkerBindingSet = WorkerBindingSet(),
        desiredBindings: WorkerBindingSet = WorkerBindingSet(),
        cleanRebuild: Boolean = false,
        rollbackSnapshot: WorkerRollbackSnapshot? = null,
    ): WorkerDeploymentPlanResult = WorkerDeploymentPlanner.planRawUrlDeploy(
        target = target,
        rawUrl = finalUrl,
        sourceBytes = bytes,
        currentVersion = currentVersion,
        currentBindings = currentBindings,
        desiredBindings = desiredBindings,
        cleanRebuild = cleanRebuild,
        rollbackSnapshot = rollbackSnapshot,
        approval = approval,
    )
}

internal class WorkerSourceDownloadException(val reason: String) : Exception(reason)

/** Bounded Raw-URL downloader for the in-app installer. Redirect destinations
 * are validated again so a public URL cannot bounce into a local endpoint. */
internal object WorkerSourceDownloader {
    private const val MAX_REDIRECTS = 4
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun download(rawUrl: String): DownloadedWorkerSource = withContext(Dispatchers.IO) {
        var current = when (val decision = WorkerSourcePolicy.validateUrl(rawUrl)) {
            is WorkerSourceDecision.Allowed -> decision.normalizedUrl
            is WorkerSourceDecision.Blocked -> throw WorkerSourceDownloadException(decision.reason)
        }
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "text/javascript, application/javascript, text/plain;q=0.8")
                setRequestProperty("User-Agent", "CatClient/worker-installer")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) throw WorkerSourceDownloadException("too-many-redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw WorkerSourceDownloadException("redirect-without-location")
                    val target = URL(URL(current), location).toString()
                    current = when (val decision = WorkerSourcePolicy.validateUrl(target)) {
                        is WorkerSourceDecision.Allowed -> decision.normalizedUrl
                        is WorkerSourceDecision.Blocked -> throw WorkerSourceDownloadException(decision.reason)
                    }
                    return@repeat
                }
                if (status !in 200..299) throw WorkerSourceDownloadException("source-http-$status")
                val length = connection.contentLengthLong.takeIf { it >= 0L }
                when (val size = WorkerSourcePolicy.validateDownloadedSize(length, 1)) {
                    is WorkerSourceDecision.Blocked -> throw WorkerSourceDownloadException(size.reason)
                    is WorkerSourceDecision.Allowed -> Unit
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > WorkerSourcePolicy.MAX_SOURCE_BYTES) throw WorkerSourceDownloadException("source-too-large")
                        output.write(buffer, 0, read)
                    }
                }
                val bytes = output.toByteArray()
                when (val size = WorkerSourcePolicy.validateDownloadedSize(length, bytes.size.toLong())) {
                    is WorkerSourceDecision.Blocked -> throw WorkerSourceDownloadException(size.reason)
                    is WorkerSourceDecision.Allowed -> Unit
                }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                return@withContext DownloadedWorkerSource(
                    finalUrl = current,
                    bytes = bytes,
                    sha256 = sha,
                    contentType = connection.contentType.orEmpty(),
                )
            } finally {
                connection.disconnect()
            }
        }
        throw WorkerSourceDownloadException("too-many-redirects")
    }
}
