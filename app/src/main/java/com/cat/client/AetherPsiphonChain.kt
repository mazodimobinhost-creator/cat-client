package com.cat.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Starts and owns the two carrier legs; the caller attaches [PsiphonTunPlan] last. */
internal class AetherPsiphonChain(
    context: Context,
    scope: CoroutineScope,
    identityFile: File,
    protectSocket: (Int) -> Boolean,
    endpoint: String? = null,
    private val aetherPort: Int = 1819,
    carrierMode: AetherMode = AetherMode.MasqueHttp2,
    ipScan: String = "both",
    masqueTransport: String = "h2",
    innerEndpoint: String? = null,
) : Closeable {
    private val active = AtomicBoolean(false)
    private val aether = AetherProxyCarrier(
        scope = scope,
        identityFile = identityFile,
        protectSocket = protectSocket,
        endpoint = endpoint,
        port = aetherPort,
        carrierMode = carrierMode,
        ipScan = ipScan,
        masqueTransport = masqueTransport,
        innerEndpoint = innerEndpoint,
    )
    private val psiphon = PsiphonCarrierClient(context)

    suspend fun start(region: String = ""): PsiphonCarrierState {
        check(active.compareAndSet(false, true)) { "Aether-Psiphon chain is already active" }
        return try {
            aether.start()
            try {
                psiphon.start(aetherPort, region, timeoutMs = if (region.isNotEmpty()) 65_000L else 210_000L)
            } catch (firstError: Throwable) {
                if (region.isNotEmpty()) {
                    // Fallback to fastest available foreign country if the requested region timed out
                    runCatching { psiphon.close() }
                    psiphon.start(aetherPort, "", timeoutMs = 150_000L)
                } else {
                    throw firstError
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun close() {
        psiphon.close()
        aether.close()
        active.set(false)
    }
}
