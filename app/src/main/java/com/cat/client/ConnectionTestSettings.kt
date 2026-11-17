package com.cat.client

import android.content.Context

data class ConnectionTestSettings(
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val speedTestMegabytes: Int = DEFAULT_SPEED_TEST_MEGABYTES,
    /** beta240: the address every config's ping is measured against (other clients let the owner pick it). */
    val delayTestUrl: String = DEFAULT_DELAY_TEST_URL,
) {
    fun normalized(): ConnectionTestSettings = copy(
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
        concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        speedTestMegabytes = speedTestMegabytes.coerceIn(
            MIN_SPEED_TEST_MEGABYTES,
            MAX_SPEED_TEST_MEGABYTES,
        ),
        delayTestUrl = delayTestUrl.takeIf { it in DELAY_TEST_URLS } ?: DEFAULT_DELAY_TEST_URL,
    )

    val speedTestBytes: Long
        get() = speedTestMegabytes * BYTES_PER_MEGABYTE

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 30
        const val DEFAULT_CONCURRENCY = 10
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 100
        const val DEFAULT_SPEED_TEST_MEGABYTES = 1
        const val MIN_SPEED_TEST_MEGABYTES = 1
        const val MAX_SPEED_TEST_MEGABYTES = 100
        const val BYTES_PER_MEGABYTE = 1_000_000L
        const val DEFAULT_DELAY_TEST_URL = MihomoRuntimeDefaults.DELAY_TEST_URL

        /** HTTPS «204 No Content» endpoints only: plain HTTP can be answered by a middlebox and fake a good ping. */
        val DELAY_TEST_URLS = listOf(DEFAULT_DELAY_TEST_URL, "https://cp.cloudflare.com/generate_204")
    }
}

class ConnectionTestSettingsPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ConnectionTestSettings = ConnectionTestSettings(
        timeoutSeconds = prefs.getInt(KEY_TIMEOUT_SECONDS, ConnectionTestSettings.DEFAULT_TIMEOUT_SECONDS),
        concurrency = prefs.getInt(KEY_CONCURRENCY, ConnectionTestSettings.DEFAULT_CONCURRENCY),
        speedTestMegabytes = prefs.getInt(
            KEY_SPEED_TEST_MEGABYTES,
            ConnectionTestSettings.DEFAULT_SPEED_TEST_MEGABYTES,
        ),
        delayTestUrl = prefs.getString(KEY_DELAY_TEST_URL, null) ?: ConnectionTestSettings.DEFAULT_DELAY_TEST_URL,
    ).normalized()

    fun save(settings: ConnectionTestSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putInt(KEY_TIMEOUT_SECONDS, normalized.timeoutSeconds)
            .putInt(KEY_CONCURRENCY, normalized.concurrency)
            .putInt(KEY_SPEED_TEST_MEGABYTES, normalized.speedTestMegabytes)
            .putString(KEY_DELAY_TEST_URL, normalized.delayTestUrl)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cat_client_connection_test"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_SPEED_TEST_MEGABYTES = "speed_test_megabytes"
        const val KEY_DELAY_TEST_URL = "delay_test_url"
    }
}
