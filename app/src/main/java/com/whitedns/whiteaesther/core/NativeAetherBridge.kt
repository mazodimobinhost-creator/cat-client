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

    fun cancelScan(): Boolean = isLoaded && runCatching { nativeCancelScan() }.getOrDefault(false)

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
    @JvmStatic private external fun nativeCancelScan(): Boolean
}

data class NativeResult(val ok: Boolean, val error: String? = null)
data class EndpointScanResult(val peer: String, val rttMillis: Long)
