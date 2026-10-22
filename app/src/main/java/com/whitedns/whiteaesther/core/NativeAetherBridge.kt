package com.whitedns.whiteaesther.core

import androidx.annotation.Keep
import org.json.JSONObject

/**
 * Narrow ABI adapter for the reviewed WhiteAesther native core.
 *
 * The package and exported JNI names intentionally remain upstream-compatible;
 * Cat Client does not rename or represent the AGPL Aether implementation as its own.
 */
@Keep
object NativeAetherBridge {
    val loadResult: Result<Unit> by lazy { runCatching { System.loadLibrary("whiteaesther_core") } }
    val isLoaded: Boolean get() = loadResult.isSuccess

    fun versionOrNull(): String? = call(::nativeVersion).getOrNull()

    fun validate(configJson: String): NativeResult =
        call { validateConfig(configJson) }.toNativeResult()

    fun provision(configJson: String): Result<List<String>> =
        call { nativeProvision(configJson) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Provisioning failed") }
            val devices = json.getJSONArray("devices")
            List(devices.length()) { devices.getString(it) }
        }

    fun scan(configJson: String): Result<List<EndpointScanResult>> =
        call { nativeScan(configJson) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Endpoint scan failed") }
            val values = json.getJSONArray("results")
            List(values.length()) { index ->
                values.getJSONObject(index).let {
                    EndpointScanResult(it.getString("peer"), it.getLong("rttMs"))
                }
            }
        }

    fun prepare(configJson: String): Result<PreparedEngine> =
        call { nativePrepare(configJson) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Preparation failed") }
            PreparedEngine(json.getString("ipv4"), json.getString("ipv6"), json.getString("peer"))
        }

    fun testEndpoint(configJson: String): Result<EndpointScanResult> =
        call { nativeTestEndpoint(configJson) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Endpoint test failed") }
            EndpointScanResult(json.getString("peer"), json.getLong("rttMs"))
        }

    fun scanProgress(): NativeScanProgress? =
        call(::nativeScanProgress).getOrNull()?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                val results = json.optJSONArray("results")
                NativeScanProgress(
                    completed = json.optInt("completed"),
                    total = json.optInt("total"),
                    failures = json.optString("failures"),
                    scanId = json.optLong("scanId"),
                    results = if (results == null) emptyList() else List(results.length()) { index ->
                        results.getJSONObject(index).let {
                            EndpointScanResult(it.getString("peer"), it.getLong("rttMs"))
                        }
                    },
                )
            }.getOrNull()
        }

    fun exportIdentity(configPath: String): Result<String> =
        call { nativeExportIdentity(configPath) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Nothing to export") }
            json.getString("payload")
        }

    fun importIdentity(configPath: String, payload: String): NativeResult =
        call { nativeImportIdentity(configPath, payload) }.toNativeResult()

    fun drainLog(): List<String> {
        if (!isLoaded) return emptyList()
        return runCatching { nativeDrainLog()?.filterNotNull().orEmpty() }.getOrDefault(emptyList())
    }

    fun migrate(): Boolean = isLoaded && runCatching { nativeMigrate() }.getOrDefault(false)

    fun run(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): NativeResult = call {
        nativeRun(configJson, preparedPeer, tunFd, JniEngineListenerBridge(listener))
    }.toNativeResult()

    fun setSocketProtector(protector: NativeSocketProtector?) {
        loadResult.getOrThrow()
        nativeSetSocketProtector(protector?.let(::JniSocketProtectorBridge))
    }

    fun cancelScan(): Boolean = isLoaded && runCatching { nativeCancelScan() }.getOrDefault(false)
    fun cancelPrepare(): Boolean = isLoaded && runCatching { nativeCancelPrepare() }.getOrDefault(false)
    fun stop(): Boolean = isLoaded && runCatching { nativeStop() }.getOrDefault(false)

    private inline fun call(block: () -> String): Result<String> = loadResult.fold(
        onSuccess = { runCatching(block) },
        onFailure = { Result.failure(it) },
    )

    private fun Result<String>.toNativeResult(): NativeResult = fold(
        onSuccess = { raw ->
            runCatching {
                JSONObject(raw).let {
                    NativeResult(it.optBoolean("ok"), it.optString("error").takeIf(String::isNotBlank))
                }
            }.getOrElse { NativeResult(false, "Invalid native response") }
        },
        onFailure = { NativeResult(false, "Native core unavailable") },
    )

    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun validateConfig(configJson: String): String
    @JvmStatic private external fun nativeProvision(configJson: String): String
    @JvmStatic private external fun nativeScan(configJson: String): String
    @JvmStatic private external fun nativeScanProgress(): String
    @JvmStatic private external fun nativeTestEndpoint(configJson: String): String
    @JvmStatic private external fun nativePrepare(configJson: String): String
    @JvmStatic private external fun nativeExportIdentity(configPath: String): String
    @JvmStatic private external fun nativeImportIdentity(configPath: String, payload: String): String
    @JvmStatic private external fun nativeDrainLog(): Array<String?>?
    @JvmStatic private external fun nativeMigrate(): Boolean
    @JvmStatic private external fun nativeRun(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): String
    @JvmStatic private external fun nativeSetSocketProtector(protector: NativeSocketProtector?)
    @JvmStatic private external fun nativeCancelScan(): Boolean
    @JvmStatic private external fun nativeCancelPrepare(): Boolean
    @JvmStatic private external fun nativeStop(): Boolean
}

@Keep
private class JniSocketProtectorBridge(
    private val delegate: NativeSocketProtector,
) : NativeSocketProtector {
    @Keep
    override fun protectSocket(fd: Int): Boolean = delegate.protectSocket(fd)
}

@Keep
private class JniEngineListenerBridge(
    private val delegate: NativeEngineListener,
) : NativeEngineListener {
    @Keep
    override fun onNativeReady() = delegate.onNativeReady()
}

@Keep
fun interface NativeSocketProtector {
    @Keep
    fun protectSocket(fd: Int): Boolean
}

@Keep
fun interface NativeEngineListener {
    @Keep
    fun onNativeReady()
}

@Keep
data class NativeResult(val ok: Boolean, val error: String? = null)
@Keep
data class EndpointScanResult(val peer: String, val rttMillis: Long)
@Keep
data class NativeScanProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val failures: String = "",
    val results: List<EndpointScanResult> = emptyList(),
    val scanId: Long = 0,
)
@Keep
data class PreparedEngine(val ipv4: String, val ipv6: String, val peer: String)
