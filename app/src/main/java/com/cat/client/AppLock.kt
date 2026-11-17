package com.cat.client

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App lock (PIN, optionally unlocked with the phone's own fingerprint / face / pattern through
 * the system credential screen). Off by default. The PIN is stored only as a salted PBKDF2 hash.
 */
object AppLock {
    private const val PREFS = "cat_client_app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_USE_DEVICE = "use_device_credential"
    private const val KEY_BACKGROUND_AT = "background_at"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    const val RELOCK_AFTER_MS = 30_000L
    const val MIN_PIN = 4

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false) &&
        !prefs(context).getString(KEY_HASH, null).isNullOrBlank()

    fun useDeviceCredential(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_DEVICE, true)

    fun setUseDeviceCredential(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_DEVICE, value).apply()
    }

    fun enable(context: Context, pin: String) {
        require(pin.length >= MIN_PIN && pin.all(Char::isDigit))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt).toHex())
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    fun disable(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Remaining lockout in ms after repeated wrong PINs (0 = may try now). */
    fun lockoutRemainingMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long =
        (prefs(context).getLong(KEY_LOCKED_UNTIL, 0L) - nowMs).coerceAtLeast(0L)

    fun verify(context: Context, pin: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lockoutRemainingMs(context, nowMs) > 0) return false
        val p = prefs(context)
        val salt = p.getString(KEY_SALT, null)?.fromHex() ?: return false
        val expected = p.getString(KEY_HASH, null)?.fromHex() ?: return false
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        if (ok) {
            p.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
        } else {
            val fails = p.getInt(KEY_FAILS, 0) + 1
            val wait = lockoutForFails(fails)
            p.edit().putInt(KEY_FAILS, fails).putLong(KEY_LOCKED_UNTIL, if (wait > 0) nowMs + wait else 0L).apply()
        }
        return ok
    }

    /** 5 wrong tries → 30 s, then doubling, capped at 15 minutes. */
    fun lockoutForFails(fails: Int): Long = if (fails < 5) 0L else (30_000L shl (fails - 5).coerceAtMost(5)).coerceAtMost(15 * 60_000L)

    fun markBackground(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_BACKGROUND_AT, nowMs).apply()
    }

    fun markUnlocked(context: Context) {
        prefs(context).edit().putLong(KEY_BACKGROUND_AT, Long.MAX_VALUE).apply()
    }

    /** True when the app was in the background longer than [RELOCK_AFTER_MS] (or never unlocked). */
    fun shouldLock(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled(context)) return false
        val at = prefs(context).getLong(KEY_BACKGROUND_AT, 0L)
        if (at == Long.MAX_VALUE) return false
        return nowMs - at >= RELOCK_AFTER_MS
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun String.fromHex(): ByteArray? = runCatching {
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
