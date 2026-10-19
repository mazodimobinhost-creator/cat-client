package com.cat.client

internal data class WorkerSourceInspection(
    val valid: Boolean,
    val module: Boolean,
    val version: String?,
    val reason: String? = null,
)

internal object WorkerSourceInspector {
    private val versionPattern = Regex("CAT_PANEL_VERSION\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val modulePattern = Regex("(?m)^\\s*export\\s+(default|const|function|class|\\{)")

    fun inspect(bytes: ByteArray): WorkerSourceInspection {
        if (bytes.isEmpty()) return WorkerSourceInspection(false, false, null, "source-empty")
        if (bytes.any { it == 0.toByte() }) return WorkerSourceInspection(false, false, null, "nul-byte-forbidden")
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: return WorkerSourceInspection(false, false, null, "invalid-utf8")
        if (text.isBlank()) return WorkerSourceInspection(false, false, null, "source-empty")
        val module = modulePattern.containsMatchIn(text)
        val hasHandler = text.contains("fetch(") || text.contains("fetch:") || text.contains("addEventListener('fetch") || text.contains("addEventListener(\"fetch")
        if (!hasHandler) return WorkerSourceInspection(false, module, null, "fetch-handler-not-found")
        return WorkerSourceInspection(true, module, versionPattern.find(text)?.groupValues?.getOrNull(1))
    }
}
