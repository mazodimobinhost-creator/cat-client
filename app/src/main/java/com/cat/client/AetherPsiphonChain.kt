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
) : Closeable {
    private val active = AtomicBoolean(false)
    private val aether = AetherProxyCarrier(scope, identityFile, protectSocket, endpoint, aetherPort)
    private val psiphon = PsiphonCarrierClient(context)

    suspend fun start(region: String = ""): PsiphonCarrierState {
        check(active.compareAndSet(false, true)) { "Aether-Psiphon chain is already active" }
        return try {
            aether.start()
            psiphon.start(aetherPort, region)
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
