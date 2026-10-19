package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerStaticAnalyzerTest {
    @Test fun detectsCapabilitiesAndCleanSafetyOnValidWorker() {
        val script = """
            import { connect } from 'cloudflare:sockets';
            const CAT_PANEL_VERSION = '6.68.1';
            const OPEN_SUB = false;
            export default {
              async fetch(request, env) {
                await env.CAT_KV.get('k');
                await env.CAT_KV.put('k', 'v');
                return new Response('vless trojan socks5 httpConnect');
              }
            };
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val report = WorkerStaticAnalyzer.analyzeLocal(script)
        assertTrue(report.inspection.valid)
        assertEquals("6.68.1", report.inspection.version)
        assertTrue(WorkerCapability.VLESS in report.capabilities)
        assertTrue(WorkerCapability.TROJAN in report.capabilities)
        assertTrue(WorkerCapability.SOCKS5 in report.capabilities)
        assertTrue(WorkerCapability.HTTP_CONNECT in report.capabilities)
        assertTrue(WorkerCapability.CLOUDFLARE_SOCKETS in report.capabilities)
        assertTrue(WorkerCapability.KV_STORAGE in report.capabilities)
        assertTrue(report.warnings.isEmpty())
    }

    @Test fun flagsOpenSubDefaultHardcodedCredentialAndMissingSockets() {
        val risky = """
            const OPEN_SUB = true;
            const token = 'ghp_1234567890abcdefghij1234567890';
            export default {
              async fetch(request) { return new Response('vless'); }
            };
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val report = WorkerStaticAnalyzer.analyzeLocal(risky)
        assertEquals(
            setOf(
                WorkerStaticWarning.OPEN_SUB_ENABLED_BY_DEFAULT,
                WorkerStaticWarning.HARDCODED_CREDENTIAL,
                WorkerStaticWarning.TUNNEL_WITHOUT_SOCKETS_API,
            ),
            report.warnings,
        )
    }

    @Test fun geminiReviewRequiresExplicitOptInAndRedactsSecrets() {
        val raw = "uuid=550e8400-e29b-41d4-a716-446655440000 token=ghp_1234567890abcdefghij1234567890 tg=1234567:ABCDEFGHIJKLMNOPQRSTUV"
        assertEquals(
            GeminiWorkerReviewDecision.Blocked("gemini-opt-in-required"),
            WorkerStaticAnalyzer.prepareOptInGeminiReview(raw, optInAccepted = false, apiKeyConfigured = true),
        )
        assertEquals(
            GeminiWorkerReviewDecision.Blocked("gemini-api-key-required"),
            WorkerStaticAnalyzer.prepareOptInGeminiReview(raw, optInAccepted = true, apiKeyConfigured = false),
        )
        val ready = WorkerStaticAnalyzer.prepareOptInGeminiReview(raw, optInAccepted = true, apiKeyConfigured = true)
            as GeminiWorkerReviewDecision.Ready
        assertFalse(ready.redactedSnippet.contains("550e8400"))
        assertFalse(ready.redactedSnippet.contains("ghp_123456"))
        assertFalse(ready.redactedSnippet.contains("ABCDEFGHIJKLMNOPQRSTUV"))
        assertTrue(ready.redactedSnippet.contains("[REDACTED_UUID]"))
        assertTrue(ready.redactedSnippet.contains("[REDACTED_GH_TOKEN]"))
        assertTrue(ready.redactedSnippet.contains("[REDACTED_TG_TOKEN]"))
    }
}
