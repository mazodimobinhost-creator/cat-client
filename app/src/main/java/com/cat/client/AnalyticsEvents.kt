package com.cat.client

import android.content.Context

/**
 * AnalyticsEvents — Cat Client does NOT ship any third-party tracking.
 * All events are logged locally to DiagnosticLogger only. This keeps the
 * app privacy-friendly with zero telemetry leaving the device.
 */
object AnalyticsEvents {
    const val APP_OPENED = "app_opened"
    const val VPN_CONNECTED = "vpn_connected"
    const val CONNECTION_TRY_FAILED = "connection_try_failed"
    const val VPN_DISCONNECTED = "vpn_disconnected"

    fun appOpened(context: Context) = log(context, APP_OPENED)
    fun connectionTryFailed(context: Context) = log(context, CONNECTION_TRY_FAILED)

    fun log(context: Context, eventName: String) {
        DiagnosticLogger.info(context, "catclient.event", "name=$eventName")
    }
}

object VpnAnalyticsEventPolicy {
    fun forStatePublished(previousState: VpnState, newState: VpnState): String? = when {
        previousState != VpnState.Started && newState == VpnState.Started -> AnalyticsEvents.VPN_CONNECTED
        previousState == VpnState.Starting && newState is VpnState.Error -> AnalyticsEvents.CONNECTION_TRY_FAILED
        else -> null
    }

    fun forDisconnectFinished(wasConnected: Boolean): String? {
        return if (wasConnected) AnalyticsEvents.VPN_DISCONNECTED else null
    }
}
