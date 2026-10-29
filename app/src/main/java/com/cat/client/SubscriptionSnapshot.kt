package com.cat.client

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class UserSubscriptionSourceKind(val wireName: String) {
    Inline("inline"),
    Https("https");

    companion object {
        fun fromWireName(value: String?, legacyInput: String): UserSubscriptionSourceKind =
            entries.firstOrNull { it.wireName == value }
                ?: if (
                    legacyInput.startsWith("http://", ignoreCase = true) ||
                    legacyInput.startsWith("https://", ignoreCase = true)
                ) {
                    Https
                } else {
                    Inline
                }
    }
}

internal sealed interface SubscriptionSource {
    data class RemoteHttps(val url: String) : SubscriptionSource

    data class Inline(val content: String) : SubscriptionSource
}

internal data class LoadedSubscription(val content: String, val usageHeader: String?)

internal object SubscriptionSourceLoader {
    suspend fun load(source: SubscriptionSource): String = loadDetailed(source).content

    suspend fun loadDetailed(source: SubscriptionSource): LoadedSubscription = when (source) {
        is SubscriptionSource.Inline -> LoadedSubscription(source.content, null)
        is SubscriptionSource.RemoteHttps -> loadHttps(source.url)
    }

    internal fun candidateHttpsUrls(primaryUrl: String): List<URI> {
        val uri = runCatching { URI(primaryUrl) }
            .getOrElse { throw IOException("Subscription Source URL is invalid", it) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IOException("Subscription Source must use HTTPS")
        }
        val query = uri.rawQuery.orEmpty()
        val fallbackHosts = query.split('&')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val k = pair.substring(0, eq).lowercase()
                if (k !in setOf("fallback_host", "mirror", "backup_host")) return@mapNotNull null
                runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
            }
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.matches(Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\d{1,5})?$""")) }
            .distinct()
        if (fallbackHosts.isEmpty()) return listOf(uri)
        val mirrors = fallbackHosts.mapNotNull { hostPort ->
            val colon = hostPort.indexOf(':')
            val fHost = if (colon > 0) hostPort.substring(0, colon) else hostPort
            val fPort = if (colon > 0) hostPort.substring(colon + 1).toIntOrNull() ?: -1 else uri.port
            if (fHost.equals(uri.host, ignoreCase = true) && fPort == uri.port) return@mapNotNull null
            runCatching {
                URI("https", uri.rawUserInfo, fHost, fPort, uri.rawPath, uri.rawQuery, uri.rawFragment)
            }.getOrNull()
        }
        return listOf(uri) + mirrors
    }

    private suspend fun loadHttps(value: String): LoadedSubscription = runInterruptible(Dispatchers.IO) {
        val candidates = candidateHttpsUrls(value)
        var lastError: IOException? = null
        for (uri in candidates) {
            try {
                return@runInterruptible fetchSingleHttpsUri(uri)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Subscription Source failed")
    }

    private fun fetchSingleHttpsUri(uri: URI): LoadedSubscription {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/yaml,application/json,text/plain,*/*;q=0.1")
        // Cat Panel keys extras (warp://) off this UA so plain v2ray-style clients get a clean list.
        connection.setRequestProperty("User-Agent", "CatClient/${BuildConfig.VERSION_NAME} (+android)")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Subscription Source returned HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use {
                it.readAtMost(UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES + 1)
            }
            if (bytes.size > UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES) {
                throw IOException("Subscription Source exceeds the maximum size")
            }
            return LoadedSubscription(bytes.toString(Charsets.UTF_8), connection.getHeaderField("subscription-userinfo"))
        } finally {
            connection.disconnect()
        }
    }
}

internal fun replaceSubscriptionSnapshot(
    file: File,
    value: String,
    writeMetadata: () -> Unit,
) {
    val previous = file.takeIf(File::isFile)?.readText()
    writeSubscriptionTextAtomically(file, value)
    try {
        writeMetadata()
    } catch (error: Exception) {
        try {
            if (previous == null) {
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to restore Subscription Snapshot")
                }
            } else {
                writeSubscriptionTextAtomically(file, previous)
            }
        } catch (restoreError: Exception) {
            error.addSuppressed(restoreError)
        }
        throw error
    }
}

/**
 * beta217: every tap that needed a subscription (select it, open its list, the picker…) read its YAML file again —
 * up to a few MB, decoded and re-hashed on the main thread — before the parsed-snapshot memo could even answer.
 * The text is kept for the few most recently used files and reused while the file's size and modification time are
 * unchanged. Writes go through [writeSubscriptionTextAtomically], which refreshes the entry, so an edit is never
 * served stale; returning the same String instance also lets the parsed memo reuse its cached hash.
 */
internal object SnapshotTextMemo {
    private const val MAX_ENTRIES = 4

    private class Slot(val length: Long, val modified: Long, val text: String)

    private val slots = LinkedHashMap<String, Slot>(8, 0.75f, true)

    fun read(file: File): String? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L) return null
        val modified = file.lastModified()
        synchronized(slots) {
            slots[file.path]?.takeIf { it.length == length && it.modified == modified }?.let { return it.text }
        }
        val text = file.readText()
        remember(file, length, modified, text)
        return text
    }

    fun stored(file: File, text: String) {
        if (!file.isFile) {
            invalidate(file)
            return
        }
        remember(file, file.length(), file.lastModified(), text)
    }

    fun invalidate(file: File) {
        synchronized(slots) { slots.remove(file.path) }
    }

    private fun remember(file: File, length: Long, modified: Long, text: String) {
        synchronized(slots) {
            slots[file.path] = Slot(length, modified, text)
            while (slots.size > MAX_ENTRIES) slots.remove(slots.keys.first())
        }
    }
}

internal fun writeSubscriptionTextAtomically(file: File, value: String) {
    val parent = file.parentFile
        ?: throw IOException("Subscription Snapshot file does not have a parent directory")
    if (!parent.isDirectory && !parent.mkdirs()) {
        throw IOException("Unable to create Subscription Snapshot directory")
    }
    SnapshotTextMemo.invalidate(file)
    val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
    try {
        temporary.writeText(value)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        SnapshotTextMemo.stored(file, value)
    } finally {
        temporary.delete()
    }
}

internal fun userSubscriptionSource(
    input: String,
    kind: UserSubscriptionSourceKind = UserSubscriptionSourceKind.fromWireName(null, input),
): SubscriptionSource = when (kind) {
    UserSubscriptionSourceKind.Inline -> SubscriptionSource.Inline(input)
    UserSubscriptionSourceKind.Https -> SubscriptionSource.RemoteHttps(input)
}

internal class AndroidSubscriptionSnapshotAdapter(
    private val context: Context,
    private val store: SubscriptionStore,
) : SubscriptionSnapshotPersistence {
    override fun read(id: String): SubscriptionSnapshotEntry? {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            val file = builtInSnapshotFile(id)
            return SubscriptionSnapshotEntry(
                source = managedSource(id),
                cachedYaml = SnapshotTextMemo.read(file),
                fetchedAt = store.readCatalog(id)?.fetchedAt
                    ?: file.lastModified().takeIf { it > 0L }
                    ?: 0L,
            )
        }

        val subscription = store.readUserSubscription(id) ?: return null
        var yaml = store.readUserSubscriptionYaml(id).takeIf(String::isNotBlank)
        if (subscription.format == UserSubscriptionFormat.Links && yaml != null) {
            if ("\\/" in yaml && (": {\"" in yaml || ": [\"" in yaml)) {
                yaml = null
            } else {
                val migrated = MihomoLinkConfigBuilder.migrateGeneratedYaml(yaml)
                if (migrated != yaml) {
                    yaml = migrated
                    store.saveUserSubscription(subscription, migrated)
                }
            }
        }
        return SubscriptionSnapshotEntry(
            source = userSubscriptionSource(subscription.input, subscription.sourceKind),
            cachedYaml = yaml,
            fetchedAt = subscription.fetchedAt,
        )
    }

    override fun save(id: String, subscription: CompiledSubscription) {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            val file = builtInSnapshotFile(id)
            replaceSubscriptionSnapshot(file, subscription.snapshot.rawConfig) {
                store.saveCatalog(id, subscription.snapshot.catalog)
            }
            ParsedSnapshotMemo.seed(id, subscription.snapshot.rawConfig, subscription.snapshot.catalog.fetchedAt, subscription.snapshot)
            return
        }

        val existing = store.readUserSubscription(id)
            ?: throw IOException("Subscription no longer exists")
        val fetchedAt = subscription.snapshot.catalog.fetchedAt
        store.saveUserSubscription(
            existing.copy(
                format = subscription.format,
                connectionCount = subscription.snapshot.catalog.profiles.size,
                updatedAt = fetchedAt,
                fetchedAt = fetchedAt,
                lastError = "",
            ),
            subscription.snapshot.rawConfig,
        )
        ParsedSnapshotMemo.seed(id, subscription.snapshot.rawConfig, fetchedAt, subscription.snapshot)
    }

    override fun recordFailure(id: String, error: Throwable) {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            DiagnosticLogger.warn(
                context,
                "subscription.builtin.refresh.failed",
                "subscription=$id",
                error = error,
            )
            return
        }
        val existing = store.readUserSubscription(id) ?: return
        runCatching {
            store.saveUserSubscription(
                existing.copy(
                    lastError = error.message?.take(160)?.ifBlank { null }
                        ?: "تازه‌سازی ناموفق بود",
                ),
            )
        }
    }

    private fun managedSource(id: String): SubscriptionSource.RemoteHttps {
        val url = if (id == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) {
            CatClientConfig.PRIVATE_MIHOMO_SUBSCRIPTION_URL
        } else {
            CatClientConfig.MIHOMO_SUBSCRIPTION_URL
        }
        return SubscriptionSource.RemoteHttps(url)
    }

    private fun builtInSnapshotFile(id: String): File = File(
        context.filesDir,
        if (id == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
            // Legacy filename contains plaintext YAML; retain existing offline snapshots.
            "mihomo/encrypted_mihomo_subscription.yaml"
        } else {
            "mihomo/private_mihomo_subscription.yaml"
        },
    )
}

internal data class SubscriptionSnapshotEntry(
    val source: SubscriptionSource,
    val cachedYaml: String?,
    val fetchedAt: Long,
)

internal data class CompiledSubscription(
    val format: UserSubscriptionFormat,
    val snapshot: MihomoSubscriptionSnapshot,
)

internal object SubscriptionCompiler {
    fun compile(content: String, fetchedAt: Long): CompiledSubscription {
        val imported = UserSubscriptionImporter.import(content, fetchedAt)
        return CompiledSubscription(
            format = imported.format,
            snapshot = parseSubscriptionSnapshot(imported.yaml, fetchedAt),
        )
    }
}

internal interface SubscriptionSnapshotPersistence {
    fun read(id: String): SubscriptionSnapshotEntry?

    fun save(id: String, subscription: CompiledSubscription)

    fun recordFailure(id: String, error: Throwable)
}

internal enum class SubscriptionSnapshotOrigin {
    FreshCache,
    Refreshed,
    LastKnownGood,
}

internal data class SubscriptionSnapshotResolution(
    val snapshot: MihomoSubscriptionSnapshot,
    val origin: SubscriptionSnapshotOrigin,
)

internal fun isSubscriptionSnapshotFresh(
    fetchedAt: Long,
    nowMs: Long,
    freshnessMs: Long,
): Boolean = fetchedAt > 0L && nowMs - fetchedAt in 0 until freshnessMs

internal enum class SubscriptionRefreshPolicy {
    IfStale,
    Force,
}

internal class SubscriptionSnapshotResolver(
    private val persistence: SubscriptionSnapshotPersistence,
    private val loadSource: suspend (SubscriptionSource) -> String = SubscriptionSourceLoader::load,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val freshnessMs: Long = CatClientConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS,
) {
    suspend fun resolve(
        id: String,
        refreshPolicy: SubscriptionRefreshPolicy = SubscriptionRefreshPolicy.IfStale,
    ): SubscriptionSnapshotResolution {
        val entry = persistence.read(id)
            ?: throw IOException("Subscription no longer exists")
        val cached = entry.cachedYaml?.let { yaml ->
            runCatching { ParsedSnapshotMemo.parse(id, yaml, entry.fetchedAt) }.getOrNull()
        }
        val now = nowMs()
        if (
            refreshPolicy == SubscriptionRefreshPolicy.IfStale &&
            cached != null &&
            isSubscriptionSnapshotFresh(entry.fetchedAt, now, freshnessMs)
        ) {
            return SubscriptionSnapshotResolution(cached, SubscriptionSnapshotOrigin.FreshCache)
        }
        return try {
            val fetchedAt = now
            val compiled = SubscriptionCompiler.compile(loadSource(entry.source), fetchedAt)
            currentCoroutineContext().ensureActive()
            persistence.save(id, compiled)
            SubscriptionSnapshotResolution(
                snapshot = compiled.snapshot,
                origin = SubscriptionSnapshotOrigin.Refreshed,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            runCatching { persistence.recordFailure(id, error) }
            cached?.takeIf { refreshPolicy == SubscriptionRefreshPolicy.IfStale }?.let {
                SubscriptionSnapshotResolution(it, SubscriptionSnapshotOrigin.LastKnownGood)
            } ?: throw IOException("Unable to resolve Subscription Snapshot", error)
        }
    }

    fun cached(id: String): MihomoSubscriptionSnapshot? {
        val entry = persistence.read(id) ?: return null
        val yaml = entry.cachedYaml ?: return null
        return runCatching { ParsedSnapshotMemo.parse(id, yaml, entry.fetchedAt) }.getOrNull()
    }
}

/**
 * Parsing a cached subscription YAML (up to 2 MB, thousands of proxies) took long enough to freeze the screen,
 * and the UI asked for it again on every tap (select a subscription, open its test list, the picker, the
 * optimizer…). The parsed result is kept for the few most recently used subscriptions and reused while the YAML
 * is byte-for-byte the same (same length + hash + fetch time); any refresh or edit parses again.
 */
internal object ParsedSnapshotMemo {
    private const val MAX_ENTRIES = 4

    private class Slot(val fetchedAt: Long, val length: Int, val hash: Int, val snapshot: MihomoSubscriptionSnapshot)

    private val slots = LinkedHashMap<String, Slot>(8, 0.75f, true)

    fun parse(id: String, yaml: String, fetchedAt: Long): MihomoSubscriptionSnapshot {
        val length = yaml.length
        val hash = yaml.hashCode()
        synchronized(slots) {
            val hit = slots[id]
            if (hit != null && hit.fetchedAt == fetchedAt && hit.length == length && hit.hash == hash) return hit.snapshot
        }
        val parsed = parseSubscriptionSnapshot(yaml, fetchedAt)
        synchronized(slots) {
            slots[id] = Slot(fetchedAt, length, hash, parsed)
            while (slots.size > MAX_ENTRIES) slots.remove(slots.keys.first())
        }
        return parsed
    }

    /** Stores an already parsed snapshot (import / refresh) so the next screen does not parse it again. */
    fun seed(id: String, yaml: String, fetchedAt: Long, snapshot: MihomoSubscriptionSnapshot) {
        if (yaml.isBlank()) return
        synchronized(slots) {
            slots[id] = Slot(fetchedAt, yaml.length, yaml.hashCode(), snapshot)
            while (slots.size > MAX_ENTRIES) slots.remove(slots.keys.first())
        }
    }

    fun clear() = synchronized(slots) { slots.clear() }
}

private fun parseSubscriptionSnapshot(yaml: String, fetchedAt: Long): MihomoSubscriptionSnapshot =
    MihomoConfigParser.parse(yaml, fetchedAt).also { snapshot ->
        if (snapshot.catalog.profiles.isEmpty()) {
            throw IOException("Subscription did not contain proxies")
        }
    }
