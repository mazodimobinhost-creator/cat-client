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
 */
object PanelUpdate {

    /** A Cat Panel worker source plus the `CAT_PANEL_VERSION` it declares. */
    data class PanelScript(val version: String, val text: String, val fromRelease: Boolean)

    internal val PANEL_VERSION_MARKER =
        Regex("CAT_PANEL_VERSION\\s*=\\s*'([0-9]+(?:\\.[0-9]+)+)'")

    /** Version declared by a worker source, or null when the marker is missing. */
    fun parseVersion(source: String): String? =
        PANEL_VERSION_MARKER.find(source)?.groupValues?.get(1)

    /** True when the published release source is strictly newer than the APK bundle. */
    fun preferRelease(bundledVersion: String, releaseVersion: String?): Boolean =
        !releaseVersion.isNullOrBlank() && AppUpdatePolicy.isNewer(releaseVersion, bundledVersion)

    /** Panel source bundled into this APK (offline fallback and update baseline). */
    fun bundledPanel(context: Context): PanelScript {
        val text = CloudflareWorker.builtInWorkerScript(context)
        return PanelScript(
            version = parseVersion(text) ?: "0.0.0",
            text = text,
            fromRelease = false,
        )
    }

    /** Newest known source: the official release asset when it is newer, else the bundle. */
    suspend fun newestPanel(context: Context): PanelScript = withContext(Dispatchers.IO) {
        val bundled = bundledPanel(context)
        val released = runCatching { releasedPanel() }.getOrNull()
        if (released != null && preferRelease(bundled.version, released.version)) released else bundled
    }

    private suspend fun releasedPanel(): PanelScript {
        val text = GitHubReleaseClient.releaseAsset(WORKER_ASSET_NAME, MAX_PANEL_SOURCE_BYTES)
        val version = parseVersion(text)
            ?: throw IOException("Released panel source has no version marker")
        if (!text.contains("export default") && !text.contains("addEventListener")) {
            throw IOException("Released panel source is not a worker module")
        }
        return PanelScript(version = version, text = text, fromRelease = true)
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
                setRequestProperty("User-Agent", "CatClient/${BuildConfig.VERSION_NAME} (panel-update-check)")
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
    private const val MAX_PANEL_SOURCE_BYTES = 3_000_000
    private const val MAX_VERSION_RESPONSE_BYTES = 16_384
}
