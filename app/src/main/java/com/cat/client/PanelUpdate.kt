package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Cat Panel self-update support.
 *
 * The app can update a deployed Cat Panel the same way it updates itself:
 * the newest panel source comes from the official GitHub release assets
 * (`catclient.worker.js`), falling back to the copy bundled in this APK,
 * and the live deployment reports its own version at `/api/version`.
 *
 * Deployable artifacts are normal minified ESM modules. Versions are read from
 * their first-line marker (pinned by panel-update-contract.test.mjs). A live panel is never
 * moved backwards: [isDowngrade] gates every update path.
 */
object PanelUpdate {

    /** A Cat Panel worker source plus the `CAT_PANEL_VERSION` and `SHA-256` it declares. */
    data class PanelScript(val version: String, val text: String, val fromRelease: Boolean) {
        val sha256: String = WorkerDeploymentPlanner.sha256Hex(text.toByteArray(Charsets.UTF_8))

        internal fun verifyManifest(
            channel: WorkerArtifactChannel = if (fromRelease) {
                WorkerArtifactChannel.RELEASE_ASSET
            } else {
                WorkerArtifactChannel.APK_BUNDLE
            },
            expectedSha256: String? = null,
        ): WorkerManifestDecision = WorkerArtifactManifest.verify(
            channel = channel,
            bytes = text.toByteArray(Charsets.UTF_8),
            expectedSha256 = expectedSha256,
        )
    }

    internal val PANEL_VERSION_MARKER =
        Regex("CAT_PANEL_VERSION\\s*=\\s*'([0-9]+(?:\\.[0-9]+)+)'")

    /** Version declared by a worker source, or null when the marker is missing. */
    fun parseVersion(source: String): String? =
        PANEL_VERSION_MARKER.find(source)?.groupValues?.get(1)

    /** True when the published release source is strictly newer than the APK bundle. */
    fun preferRelease(bundledVersion: String, releaseVersion: String?): Boolean =
        !releaseVersion.isNullOrBlank() && AppUpdatePolicy.isNewer(releaseVersion, bundledVersion)

    /** Version reported for a source that carries no readable marker. Never a real release. */
    const val UNKNOWN_VERSION = "0.0.0"

    /**
     * The repository copy (jsDelivr) is only a last-resort mirror for networks where the
     * official release asset cannot be reached. It is NOT the release branch: `main` sat at
     * panel 5.23.13 while the app already shipped 6.53, and because the obfuscated bundle had
     * no readable marker (bundle = 0.0.0) that stale copy looked like an upgrade — one tap on
     * «Update panel» put 5.23.13 over a live 6.x panel. So the mirror is accepted only when
     * this APK knows its own panel version AND the mirror is strictly newer than it.
     */
    fun acceptRepositoryFallback(bundledVersion: String, fallbackVersion: String?): Boolean =
        bundledVersion != UNKNOWN_VERSION &&
            !fallbackVersion.isNullOrBlank() &&
            AppUpdatePolicy.isNewer(fallbackVersion, bundledVersion)

    /** True when installing [candidate] over the live [deployed] panel would move it backwards. */
    fun isDowngrade(deployed: String?, candidate: String): Boolean =
        !deployed.isNullOrBlank() && AppUpdatePolicy.isNewer(deployed, candidate)

    /** Validates a candidate script against [WorkerArtifactManifest] (SHA-256, syntax,
     * and blocked 1101 versions like `6.69.12`) and ensures it is not a downgrade. */
    internal fun validateCandidate(
        candidate: PanelScript,
        channel: WorkerArtifactChannel = if (candidate.fromRelease) {
            WorkerArtifactChannel.RELEASE_ASSET
        } else {
            WorkerArtifactChannel.APK_BUNDLE
        },
        expectedSha256: String? = null,
        deployedVersion: String? = null,
    ): WorkerManifestDecision {
        val decision = candidate.verifyManifest(channel, expectedSha256)
        if (decision is WorkerManifestDecision.Rejected) return decision
        if (isDowngrade(deployedVersion, candidate.version)) {
            return WorkerManifestDecision.Rejected("downgrade-blocked")
        }
        return decision
    }

    /** Panel source bundled into this APK (offline fallback and update baseline). */
    fun bundledPanel(context: Context): PanelScript {
        val text = CloudflareWorker.builtInPasteScript(context) ?: CloudflareWorker.builtInWorkerScript(context)
        return PanelScript(
            version = parseVersion(text) ?: UNKNOWN_VERSION,
            text = text,
            fromRelease = false,
        )
    }

    /** Newest known source: the official release asset when it is newer, else the bundle. */
    suspend fun newestPanel(context: Context): PanelScript = withContext(Dispatchers.IO) {
        val bundled = bundledPanel(context)
        val released = runCatching { releasedPanel(bundled.version) }.getOrNull()
        if (released != null && released.version == bundled.version) {
            val bDec = bundled.verifyManifest(WorkerArtifactChannel.APK_BUNDLE)
            val rDec = released.verifyManifest(WorkerArtifactChannel.RELEASE_ASSET)
            if (bDec is WorkerManifestDecision.Verified && rDec is WorkerManifestDecision.Verified) {
                WorkerArtifactManifest.verifyCanonicalConsistency(bDec.record, rDec.record)
            }
        }
        if (released != null && preferRelease(bundled.version, released.version)) released else bundled
    }

    private suspend fun releasedPanel(bundledVersion: String): PanelScript {
        var lastError: IOException? = null
        // 1) the GitHub release asset (with its mirror chain) — the official channel;
        // 2) the committed minified snapshot via jsDelivr — reachable where GitHub is
        //    not, but only a fallback (see acceptRepositoryFallback).
        val sources = listOf(
            suspend { GitHubReleaseClient.releaseAsset(WORKER_ASSET_NAME, MAX_PANEL_SOURCE_BYTES) },
            suspend { GitHubReleaseClient.repositoryFile(SNAPSHOT_PATH, MAX_PANEL_SOURCE_BYTES) },
        )
        for ((index, source) in sources.withIndex()) {
            try {
                val text = source()
                val version = parseVersion(text)
                    ?: throw IOException("Released panel source has no version marker")
                if (!text.contains("export default") && !text.contains("addEventListener")) {
                    throw IOException("Released panel source is not a worker module")
                }
                if (index > 0 && !acceptRepositoryFallback(bundledVersion, version)) {
                    throw IOException("Repository copy $version is not newer than the bundled panel $bundledVersion")
                }
                val expectedSha = if (index == 0) {
                    runCatching { GitHubReleaseClient.releaseAssetSha256(WORKER_ASSET_NAME) }.getOrNull()
                } else null
                val candidate = PanelScript(version = version, text = text, fromRelease = true)
                when (val decision = validateCandidate(candidate, WorkerArtifactChannel.RELEASE_ASSET, expectedSha256 = expectedSha)) {
                    is WorkerManifestDecision.Rejected ->
                        throw IOException("Released panel $version failed manifest validation: ${decision.reason}")
                    is WorkerManifestDecision.Verified -> Unit
                }
                return candidate
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw (lastError ?: IOException("Panel source download failed"))
    }

    /**
     * Version the live deployment reports at `/api/version`, or null when the
     * worker is unreachable, not a Cat Panel, or reports nothing usable.
     */
    suspend fun deployedVersion(workerUrl: String): String? = withContext(Dispatchers.IO) {
        val normalized = workerUrl.trimEnd('/')
        val uri = runCatching { URI("$normalized/api/version") }.getOrNull() ?: return@withContext null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return@withContext null
        runCatching {
            val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@runCatching null
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(2_048)
                    while (output.size() < MAX_VERSION_RESPONSE_BYTES) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val json = JSONObject(body)
                if (json.optString("panel") != "cat-panel") return@runCatching null
                json.optString("version").takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}")) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private const val WORKER_ASSET_NAME = "catclient.worker.js"
    /** CI-committed minified panel (the deploy/update source). */
    private const val SNAPSHOT_PATH = "dist-panel/catpanel.min.js"
    private const val MAX_PANEL_SOURCE_BYTES = 3_000_000
    private const val MAX_VERSION_RESPONSE_BYTES = 16_384
}
