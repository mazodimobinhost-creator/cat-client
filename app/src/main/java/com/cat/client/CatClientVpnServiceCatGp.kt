package com.cat.client

import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException

// beta269 🌐 Cat GP connect + dummy TUN (system HTTP proxy). Not the Cat core at :2080.

internal fun CatClientVpnService.closeCatGp(reason: String) {
    runCatching { CatGpEngine.stop() }
    catGpTunFd?.let { runCatching { it.close() } }
    catGpTunFd = null
    DiagnosticLogger.info(this, "catgp.closed", "reason=$reason")
}

internal fun CatClientVpnService.establishCatGpTun(): ParcelFileDescriptor {
    val builder = Builder()
        .setMtu(1500)
        .setSession("${getString(R.string.app_name)} · Cat GP")
        .addAddress("10.8.71.2", 32)
        .addRoute("10.8.71.0", 24)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setMetered(false)
        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", CatGpEngine.PORT))
    }
    return builder.establish() ?: throw IOException("Android rejected the Cat GP VPN session")
}

/**
 * Browser HTTP relay through the owner's Google Apps Script. The Cat core's HTTPS
 * health check at 127.0.0.1:2080 cannot pass (no UDP, no CONNECT tunnel, MITM CA).
 */
internal suspend fun CatClientVpnService.runCatGpStartup(
    eventPrefix: String,
    subscriptionId: String,
    profile: ConnectionProfile,
) {
    check(coreLifecycle.isIdle()) { "Cat core must stop before Cat GP starts" }
    closeCatGp("restart")
    VpnRuntimeStateStore.saveLiveConnectStep(this, "🌐 Cat GP — شروع رلهٔ گوگل")
    val why = CatGpEngine.ensureRunning(applicationContext) { protectOutboundSocket(it) }
    if (why != null) throw IOException("🌐 Cat GP: $why")
    val probe = withContext(Dispatchers.IO) {
        CatGpEngine.test(applicationContext, CatGpEngine.deployments(applicationContext))
    }
    if (!probe.ok) {
        closeCatGp("probe failed")
        val detail = probe.lines.lastOrNull().orEmpty()
        throw IOException(
            "🌐 Cat GP: اسکریپت گوگل جواب نداد ($detail). " +
                "هستهٔ Cat این روش را روی 127.0.0.1:2080 تست نمی‌کند (UDP ندارد). " +
                "کد را دوباره Deploy کنید با Who has access: Anyone، گواهی را نصب کنید، کروم را باز کنید. " +
                "تلگرام و اینستاگرام از این راه باز نمی‌شوند.",
        )
    }
    val tun = try {
        establishCatGpTun()
    } catch (error: Exception) {
        closeCatGp("tun failed")
        throw error
    }
    catGpTunFd = tun
    activeProfile = profile
    activeProfileShowsServer = false
    activeSubscriptionId = subscriptionId
    activeConnectionTag = profile.tag
    activeConnectionFingerprint = profile.fingerprint
    activeConnectionCountryFlag = ConnectionLocationPolicy.countryForProfile(profile)?.flag.orEmpty()
    DiagnosticLogger.info(this, "$eventPrefix.catgp.ready", "port=${CatGpEngine.PORT} udp=no")
    publishState(VpnState.Started)
    try {
        while (isActive && state != VpnState.Stopping && state != VpnState.Stopped) {
            delay(400)
        }
    } finally {
        closeCatGp("tunnel finished")
    }
}
