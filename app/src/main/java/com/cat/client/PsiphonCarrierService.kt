package com.cat.client

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import ca.psiphon.PsiphonTunnel
import java.util.concurrent.atomic.AtomicInteger

/** Psiphon's Go runtime is isolated from Cat's other native runtimes in :psiphon. */
class PsiphonCarrierService : Service() {
    private var tunnel: PsiphonTunnel? = null
    private val clients = mutableSetOf<Messenger>()
    private val socksPort = AtomicInteger(0)
    private var state = State.Stopped
    private var failure: String? = null

    enum class State { Stopped, Connecting, Connected, Failed }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_REGISTER -> message.replyTo?.let { clients += it; send(it) }
                MSG_UNREGISTER -> message.replyTo?.let(clients::remove)
                else -> super.handleMessage(message)
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?) = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCarrier(
                upstreamPort = intent.getIntExtra(EXTRA_UPSTREAM_PORT, 0),
                region = intent.getStringExtra(EXTRA_REGION).orEmpty(),
            )
            ACTION_STOP -> { stopCarrier(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCarrier()
        super.onDestroy()
    }

    private fun startCarrier(upstreamPort: Int, region: String) {
        if (tunnel != null) return
        state = State.Connecting
        failure = null
        socksPort.set(0)
        broadcast()
        val config = runCatching { PsiphonCarrierConfig.render(this, upstreamPort, region) }
            .getOrElse { fail(it.message ?: "Invalid Psiphon carrier configuration"); return }
        val host = object : PsiphonTunnel.HostService {
            override fun getContext(): Context = this@PsiphonCarrierService
            override fun getPsiphonConfig(): String = config
            override fun onListeningSocksProxyPort(port: Int) { socksPort.set(port); broadcast() }
            override fun onConnected() { state = State.Connected; broadcast() }
            override fun onConnecting() { state = State.Connecting; broadcast() }
            override fun onExiting() { if (state != State.Failed) state = State.Stopped; broadcast() }
            override fun onDiagnosticMessage(message: String) {
                DiagnosticLogger.info(this@PsiphonCarrierService, "psiphon.notice", message.take(300))
            }
        }
        runCatching {
            PsiphonTunnel.newPsiphonTunnel(host).also {
                tunnel = it
                it.setVpnMode(false)
                // The signed remote list in the config provides bootstrap entries.
                it.startTunneling("")
            }
        }.onFailure { tunnel = null; fail(it.message ?: "Psiphon failed to start") }
    }

    private fun stopCarrier() {
        tunnel?.let { runCatching { it.stop() } }
        tunnel = null
        socksPort.set(0)
        if (state != State.Failed) state = State.Stopped
        broadcast()
    }

    private fun fail(reason: String) {
        failure = reason.take(300)
        state = State.Failed
        broadcast()
    }

    private fun broadcast() = clients.toList().forEach(::send)

    private fun send(client: Messenger) {
        val message = Message.obtain(null, MSG_STATE, state.ordinal, socksPort.get()).apply {
            data.putString(EXTRA_FAILURE, failure)
        }
        runCatching { client.send(message) }.onFailure { clients.remove(client) }
    }

    companion object {
        const val ACTION_START = "com.cat.client.psiphon.START"
        const val ACTION_STOP = "com.cat.client.psiphon.STOP"
        const val EXTRA_UPSTREAM_PORT = "upstream_port"
        const val EXTRA_REGION = "region"
        const val EXTRA_FAILURE = "failure"
        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_STATE = 3
    }
}
