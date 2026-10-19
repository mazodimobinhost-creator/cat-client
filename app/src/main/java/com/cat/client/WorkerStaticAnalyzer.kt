package com.cat.client

internal enum class WorkerCapability {
    VLESS,
    TROJAN,
    SOCKS5,
    HTTP_CONNECT,
    CLOUDFLARE_SOCKETS,
    KV_STORAGE,
    D1_DATABASE,
    TELEGRAM_BOT,
}

internal enum class WorkerStaticWarning {
    OPEN_SUB_ENABLED_BY_DEFAULT,
    HARDCODED_CREDENTIAL,
    TUNNEL_WITHOUT_SOCKETS_API,
}

internal data class WorkerStaticAnalysis(
    val inspection: WorkerSourceInspection,
    val sha256: String,
    val capabilities: Set<WorkerCapability>,
    val warnings: Set<WorkerStaticWarning>,
)

internal sealed interface GeminiWorkerReviewDecision {
    data class Ready(val redactedSnippet: String) : GeminiWorkerReviewDecision
    data class Blocked(val reason: String) : GeminiWorkerReviewDecision
}

/** Local-first Worker static analyzer with optional, strictly opt-in and
 * secret-redacted Gemini review preparation. */
internal object WorkerStaticAnalyzer {
    private val githubTokenPattern = Regex("(?:ghp|github_pat)_[A-Za-z0-9_]{20,}")
    private val telegramTokenPattern = Regex("\\b\\d{6,}:[A-Za-z0-9_-]{20,}\\b")
    private val uuidPattern = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val openSubTruePattern = Regex("OPEN_SUB\\s*[:=]\\s*(?:true|'true'|\"true\"|1)")

    fun analyzeLocal(bytes: ByteArray): WorkerStaticAnalysis {
        val inspection = WorkerSourceInspector.inspect(bytes)
        val sha256 = if (bytes.isEmpty()) "" else WorkerDeploymentPlanner.sha256Hex(bytes)
        if (!inspection.valid) {
            return WorkerStaticAnalysis(inspection, sha256, emptySet(), emptySet())
        }
        val text = bytes.toString(Charsets.UTF_8)
        val caps = LinkedHashSet<WorkerCapability>()
        if (text.contains("vless", ignoreCase = true)) caps += WorkerCapability.VLESS
        if (text.contains("trojan", ignoreCase = true)) caps += WorkerCapability.TROJAN
        if (text.contains("socks5", ignoreCase = true)) caps += WorkerCapability.SOCKS5
        if (text.contains("httpConnect", ignoreCase = true) || text.contains("CONNECT ", ignoreCase = false)) {
            caps += WorkerCapability.HTTP_CONNECT
        }
        if (text.contains("cloudflare:sockets") || text.contains("connect(")) {
            caps += WorkerCapability.CLOUDFLARE_SOCKETS
        }
        if (text.contains("CAT_KV") || text.contains(".get(") && text.contains(".put(")) {
            caps += WorkerCapability.KV_STORAGE
        }
        if (text.contains(".prepare(")) caps += WorkerCapability.D1_DATABASE
        if (text.contains("api.telegram.org")) caps += WorkerCapability.TELEGRAM_BOT

        val warnings = LinkedHashSet<WorkerStaticWarning>()
        if (openSubTruePattern.containsMatchIn(text)) {
            warnings += WorkerStaticWarning.OPEN_SUB_ENABLED_BY_DEFAULT
        }
        if (githubTokenPattern.containsMatchIn(text) || telegramTokenPattern.containsMatchIn(text)) {
            warnings += WorkerStaticWarning.HARDCODED_CREDENTIAL
        }
        if ((WorkerCapability.VLESS in caps || WorkerCapability.TROJAN in caps) &&
            WorkerCapability.CLOUDFLARE_SOCKETS !in caps
        ) {
            warnings += WorkerStaticWarning.TUNNEL_WITHOUT_SOCKETS_API
        }
        return WorkerStaticAnalysis(inspection, sha256, caps, warnings)
    }

    fun prepareOptInGeminiReview(
        sourceText: String,
        optInAccepted: Boolean,
        apiKeyConfigured: Boolean,
    ): GeminiWorkerReviewDecision {
        if (!optInAccepted) return GeminiWorkerReviewDecision.Blocked("gemini-opt-in-required")
        if (!apiKeyConfigured) return GeminiWorkerReviewDecision.Blocked("gemini-api-key-required")
        if (sourceText.isBlank()) return GeminiWorkerReviewDecision.Blocked("source-empty")
        val redacted = sourceText
            .replace(githubTokenPattern, "[REDACTED_GH_TOKEN]")
            .replace(telegramTokenPattern, "[REDACTED_TG_TOKEN]")
            .replace(uuidPattern, "[REDACTED_UUID]")
        return GeminiWorkerReviewDecision.Ready(redacted)
    }
}
