package com.cat.client

internal enum class RelayProbeStage { UNTESTED, TCP, TLS, PROTOCOL, WEBSOCKET, RUNTIME, FAILED }

internal data class RelayProbeDisplay(
    val stage: RelayProbeStage,
    val healthy: Boolean,
    val latencyMs: Long?,
    val detail: String?,
)

internal object RelayProbeDisplayPolicy {
    fun from(kind: PortProbeKind, evidence: EndpointProbeEvidence?): RelayProbeDisplay {
        if (evidence == null) return RelayProbeDisplay(RelayProbeStage.UNTESTED, false, null, null)
        val verdict = EndpointProbeEvaluator.verdict(kind, evidence)
        if (verdict == EndpointVerdict.FAILED) {
            return RelayProbeDisplay(RelayProbeStage.FAILED, false, evidence.latencyMs, evidence.error)
        }
        if (verdict == EndpointVerdict.VERIFIED) {
            return RelayProbeDisplay(RelayProbeStage.RUNTIME, true, evidence.latencyMs, null)
        }
        val stage = when {
            evidence.runtimeCompleted == true -> RelayProbeStage.RUNTIME
            evidence.websocketCompleted == true -> RelayProbeStage.WEBSOCKET
            evidence.protocolHandshakeCompleted == true -> RelayProbeStage.PROTOCOL
            evidence.tlsCompleted == true -> RelayProbeStage.TLS
            evidence.tcpConnected -> RelayProbeStage.TCP
            else -> RelayProbeStage.UNTESTED
        }
        return RelayProbeDisplay(stage, false, evidence.latencyMs, evidence.error)
    }
}
