// Xray engine start/stop for the Cat core (beta223): F&F / finalMask proxies on the real Xray-core.
// Engine part of CatClientVpnService (beta222 service split); see docs/CODEMAP.md.
package com.cat.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun CatClientVpnService.startXrayEngine(configJson: String, ports: List<Int>) {
    val result = withContext(Dispatchers.IO) {
        XrayEngine.start(applicationContext, configJson, ports) { fd -> this@startXrayEngine.protect(fd) }
    }
    when (result) {
        is XrayEngine.StartResult.Ready ->
            DiagnosticLogger.info(this, "xray.engine.started", "inbounds=${result.ports.size}")
        is XrayEngine.StartResult.Failed -> {
            DiagnosticLogger.warn(
                this,
                "xray.engine.failed",
                "reason=${result.reason.take(200)} log=${result.log.takeLast(3).joinToString(" | ").take(400)}",
            )
            throw IOException("Xray engine did not start: ${result.reason}")
        }
    }
}

internal suspend fun CatClientVpnService.stopXrayEngine() {
    if (!XrayEngine.isRunning()) {
        withContext(Dispatchers.IO) { runCatching { XrayEngine.stop(applicationContext) } }
        return
    }
    withContext(Dispatchers.IO) { runCatching { XrayEngine.stop(applicationContext) } }
    DiagnosticLogger.info(this, "xray.engine.stopped")
}
