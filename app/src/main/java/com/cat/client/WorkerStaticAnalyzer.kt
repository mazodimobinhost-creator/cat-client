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

internal sealed interface GeminiWorkerReviewOutcome {
    data class Success(val reviewText: String, val redactedSnippet: String) : GeminiWorkerReviewOutcome
    data class Blocked(val reason: String) : GeminiWorkerReviewOutcome
    data class Error(val message: String, val redactedSnippet: String) : GeminiWorkerReviewOutcome
}

/** Local-first Worker static analyzer with optional, strictly opt-in and
 * secret-redacted Gemini review preparation and execution. */
internal object WorkerStaticAnalyzer {
    private val githubTokenPattern = Regex("(?:ghp|github_pat)_[A-Za-z0-9_]{20,}")
    private val telegramTokenPattern = Regex("\\b\\d{6,}:[A-Za-z0-9_-]{20,}\\b")
    private val uuidPattern = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val secretAssignPattern = Regex("((?:PANEL_PASSWORD|WIZARD_PASSWORD|TR_PASS|API_TOKEN|CF_API_TOKEN)\\s*[:=]\\s*['\"])([^'\"]+)(['\"])")
    private val openSubTruePattern = Regex("OPEN_SUB\\s*[:=]\\s*(?:true|'true'|\"true\"|1)")

    fun analyzeLocal(bytes: ByteArray): WorkerStaticAnalysis {
        val inspection = WorkerSourceInspector.inspect(bytes)
        val sha256 = if (bytes.isEmpty()) "" else WorkerDeploymentPlanner.sha256Hex(bytes)
        if (!inspection.valid) {
            return WorkerStaticAnalysis(inspection, sha256, emptySet(), emptySet())
        }
        val text = bytes.toString(Charsets.UTF_8).replace("~^", "")
        val caps = LinkedHashSet<WorkerCapability>()
        if (text.contains("vless", ignoreCase = true) || text.contains("dmxlc3M=") || text.contains("\"vl\"+\"ess\"") || text.contains("'vl'+'ess'")) {
            caps += WorkerCapability.VLESS
        }
        if (text.contains("trojan", ignoreCase = true) || text.contains("\"tr\"+\"ojan\"") || text.contains("\"tro\"+\"jan\"") || text.contains("'tr'+'ojan'") || text.contains("'tro'+'jan'")) {
            caps += WorkerCapability.TROJAN
        }
        if (text.contains("socks5", ignoreCase = true) || text.contains("\"so\"+\"cks5\"") || text.contains("\"soc\"+\"ks5\"")) caps += WorkerCapability.SOCKS5
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
        if (text.contains("api.telegram.org") || text.contains("api.tele")) caps += WorkerCapability.TELEGRAM_BOT

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

    fun formatLocalSummary(analysis: WorkerStaticAnalysis): String {
        val capsText = if (analysis.capabilities.isEmpty()) "—" else analysis.capabilities.joinToString(", ") { it.name }
        val warnText = if (analysis.warnings.isEmpty()) "None" else analysis.warnings.joinToString(", ") { it.name }
        return "Version: ${analysis.inspection.version ?: "—"}\n" +
            "SHA-256: ${analysis.sha256.take(16)}…\n" +
            "Capabilities: $capsText\n" +
            "Warnings: $warnText"
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
            .replace(secretAssignPattern, "$1[REDACTED_SECRET]$3")
        return GeminiWorkerReviewDecision.Ready(redacted)
    }

    fun buildGeminiRequestBody(redactedSnippet: String): String {
        val prompt = "Review this redacted Cloudflare Worker script for Error 1101 risks, WebSocket lifecycle, " +
            "compatibility flags, and credential hygiene. Keep the answer concise:\n\n" +
            redactedSnippet.take(24_000)
        val escaped = buildString(prompt.length + 64) {
            for (ch in prompt) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append(' ') else append(ch)
                }
            }
        }
        return """{"contents":[{"parts":[{"text":"$escaped"}]}]}"""
    }

    fun parseGeminiResponseText(responseJson: String): String? {
        val match = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""").find(responseJson) ?: return null
        val raw = match.groupValues[1]
        return raw
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun runOptInGeminiReview(
        sourceText: String,
        optInAccepted: Boolean,
        apiKey: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ): GeminiWorkerReviewOutcome {
        val cleanKey = apiKey.trim()
        return when (val prep = prepareOptInGeminiReview(sourceText, optInAccepted, cleanKey.isNotEmpty())) {
            is GeminiWorkerReviewDecision.Blocked -> GeminiWorkerReviewOutcome.Blocked(prep.reason)
            is GeminiWorkerReviewDecision.Ready -> {
                val payload = buildGeminiRequestBody(prep.redactedSnippet)
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
                val conn = runCatching {
                    java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
                }.getOrNull() ?: return GeminiWorkerReviewOutcome.Error("connection-open-failed", prep.redactedSnippet)
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("x-goog-api-key", cleanKey)
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        return GeminiWorkerReviewOutcome.Error("HTTP $code", prep.redactedSnippet)
                    }
                    val parsed = parseGeminiResponseText(body)
                        ?: return GeminiWorkerReviewOutcome.Error("empty-gemini-response", prep.redactedSnippet)
                    GeminiWorkerReviewOutcome.Success(parsed, prep.redactedSnippet)
                } catch (e: Exception) {
                    GeminiWorkerReviewOutcome.Error(e.message ?: "network-error", prep.redactedSnippet)
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
        }
    }
}
