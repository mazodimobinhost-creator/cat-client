package com.cat.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException

internal data class PsiphonCarrierState(
    val state: PsiphonCarrierService.State,
    val socksPort: Int,
    val failure: String? = null,
)

/** Same-UID IPC client; no Psiphon object or Go runtime enters the VPN process. */
internal class PsiphonCarrierClient(private val context: Context) : Closeable {
    private var remote: Messenger? = null
    private var bound = false
    private var connected = CompletableDeferred<PsiphonCarrierState>()
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != PsiphonCarrierService.MSG_STATE) return
            val state = PsiphonCarrierService.State.entries.getOrElse(message.arg1) {
                PsiphonCarrierService.State.Failed
            }
            val value = PsiphonCarrierState(
                state,
                message.arg2,
                message.data.getString(PsiphonCarrierService.EXTRA_FAILURE),
            )
            if (state == PsiphonCarrierService.State.Connected && value.socksPort in 1024..65535) {
                if (!connected.isCompleted) connected.complete(value)
            } else if (state == PsiphonCarrierService.State.Failed && !connected.isCompleted) {
                connected.completeExceptionally(IOException(value.failure ?: "Psiphon failed"))
            }
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = Messenger(binder)
            runCatching {
                remote?.send(Message.obtain(null, PsiphonCarrierService.MSG_REGISTER).apply { replyTo = incoming })
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }

    suspend fun start(upstreamPort: Int, region: String, timeoutMs: Long = 330_000): PsiphonCarrierState {
        require(upstreamPort in 1024..65535)
        require(region.isEmpty() || region.matches(Regex("[A-Z]{2}")))
        connected = CompletableDeferred()
        val serviceIntent = Intent(context, PsiphonCarrierService::class.java)
        bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "Could not bind isolated Psiphon carrier" }
        context.startService(serviceIntent.setAction(PsiphonCarrierService.ACTION_START)
            .putExtra(PsiphonCarrierService.EXTRA_UPSTREAM_PORT, upstreamPort)
            .putExtra(PsiphonCarrierService.EXTRA_REGION, region))
        return withTimeout(timeoutMs) { connected.await() }
    }

    override fun close() {
        context.startService(Intent(context, PsiphonCarrierService::class.java)
            .setAction(PsiphonCarrierService.ACTION_STOP))
        remote?.let { runCatching {
            it.send(Message.obtain(null, PsiphonCarrierService.MSG_UNREGISTER).apply { replyTo = incoming })
        } }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        remote = null
    }
}
