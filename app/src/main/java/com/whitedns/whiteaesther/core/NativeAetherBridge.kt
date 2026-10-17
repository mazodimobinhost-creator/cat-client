package com.whitedns.whiteaesther.core

import org.json.JSONObject

/**
 * Narrow ABI adapter for the reviewed WhiteAesther native core.
 *
 * The package and exported JNI names intentionally remain upstream-compatible;
 * Cat Client does not rename or represent the AGPL Aether implementation as its own.
 */
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

    fun run(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): NativeResult = call { nativeRun(configJson, preparedPeer, tunFd, listener) }.toNativeResult()

    fun setSocketProtector(protector: NativeSocketProtector?) {
        loadResult.getOrThrow()
        nativeSetSocketProtector(protector)
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
    @JvmStatic private external fun nativePrepare(configJson: String): String
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

fun interface NativeSocketProtector { fun protectSocket(fd: Int): Boolean }
fun interface NativeEngineListener { fun onNativeReady() }
data class NativeResult(val ok: Boolean, val error: String? = null)
data class EndpointScanResult(val peer: String, val rttMillis: Long)
data class PreparedEngine(val ipv4: String, val ipv6: String, val peer: String)
