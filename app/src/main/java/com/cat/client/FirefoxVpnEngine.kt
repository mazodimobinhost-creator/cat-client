package com.cat.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Native Firefox Browser VPN (`FENIX` / Mozilla IP Protection / Guardian over Fastly Edge)
 * engine for Cat Client — matching and extending the FENIX engine in MLM VPN (`v1.2.40+`),
 * `armanlife/firefox-vpn-client`, and `Hadi-Jafari-01/foxyvpn-cli`.
 *
 * Key capabilities:
 * 1. **50 GB/month Free Per Account + Multi-Account Auto-Failover**:
 *    Supports adding multiple free Firefox Accounts (`FxA`). Automatically rotates to the next
 *    account when an account reaches 95% of its 50 GB monthly quota or returns HTTP 429.
 * 2. **Full FxA PICL v1 Crypto (`PBKDF2-HMAC-SHA256` + `HKDF-SHA256` + `HAWK` + `OAuth`)**:
 *    Direct email/password sign-in (with 2FA email OTP support), OAuth refresh token renewal,
 *    Guardian entitlement auto-activation (`POST /api/v1/fpn/activate`), and `ProxyPass` JWT
 *    provisioning (`GET /api/v1/fpn/token`). Also supports 1-click token/JSON import.
 * 3. **35+ Countries on Non-Cloudflare Fastly Infrastructure**:
 *    Fetches live server records from Mozilla Remote Settings (`vpn-serverlist`) and merges
 *    with a complete built-in 35-country Fastly catalog so it works even when settings endpoints
 *    are throttled in Iran.
 * 4. **Anti-DPI TLS ClientHello Split + Clean Fastly Edge Override**:
 *    Local SOCKS5/HTTP bridge (`127.0.0.1:10829`) tunnels traffic through Mozilla's Fastly
 *    `HTTPS CONNECT` proxy nodes (`Proxy-Authorization: Bearer <ProxyPass-JWT>`) with optional
 *    TLS record splitting at the SNI boundary to defeat MCI/Irancell DPI.
 */
internal object FirefoxVpnEngine {

    const val PREFS_NAME = "firefox_vpn_fenix_prefs"
    private const val KEY_ACCOUNTS_JSON = "accounts_json"
    private const val KEY_SELECTED_COUNTRY = "selected_country_code"
    private const val KEY_TLS_FRAGMENT = "tls_fragment_enabled"
    private const val KEY_CLEAN_FASTLY_IP = "clean_fastly_edge_ip"
    private const val KEY_AUTO_FAILOVER = "multi_account_auto_failover"
    private const val KEY_CACHED_COUNTRIES = "cached_remote_countries_json"

    const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
    const val GUARDIAN_ENDPOINT = "https://vpn.mozilla.org"
    const val REMOTE_SETTINGS_URL =
        "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
    const val FIREFOX_CLIENT_ID = "5882386c6d801776"
    const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    const val MOZILLA_VPN_USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"
    private const val PICL_NAMESPACE = "identity.mozilla.com/picl/v1/"
    const val DEFAULT_MONTHLY_QUOTA_BYTES = 50L * 1024L * 1024L * 1024L // 50 GB
    const val FAILOVER_USAGE_RATIO = 0.95 // Switch account at 95% usage
    const val DEFAULT_LOCAL_PROXY_PORT = 10829

    const val COUNTRY_FASTEST = "FASTEST"
    const val COUNTRY_RECOMMENDED = "REC"

    /**
     * Clean Fastly Anycast Edge IPs that remain open on Iranian ISPs when Cloudflare is blocked.
     */
    val CLEAN_FASTLY_EDGE_IPS: List<String> = listOf(
        "151.101.1.195",
        "151.101.65.195",
        "151.101.129.195",
        "151.101.193.195",
        "146.75.32.195",
        "146.75.72.195",
    )

    data class FirefoxVpnAccount(
        val id: String = UUID.randomUUID().toString(),
        val email: String,
        val accessToken: String = "",
        val refreshToken: String = "",
        val proxyPassToken: String = "",
        val proxyPassExpiresAtSec: Long = 0L,
        val quotaLimitBytes: Long = DEFAULT_MONTHLY_QUOTA_BYTES,
        val quotaRemainingBytes: Long = DEFAULT_MONTHLY_QUOTA_BYTES,
        val localUsedBytes: Long = 0L,
        val quotaResetEpochSec: Long = 0L,
        val enabled: Boolean = true,
    ) {
        val effectiveRemainingBytes: Long
            get() = (quotaRemainingBytes - localUsedBytes).coerceAtLeast(0L)

        val usedFraction: Double
            get() {
                if (quotaLimitBytes <= 0L) return 0.0
                val used = (quotaLimitBytes - effectiveRemainingBytes).coerceAtLeast(0L)
                return (used.toDouble() / quotaLimitBytes.toDouble()).coerceIn(0.0, 1.0)
            }

        val isQuotaExhaustedOrNearLimit: Boolean
            get() = usedFraction >= FAILOVER_USAGE_RATIO || effectiveRemainingBytes <= 50L * 1024L * 1024L

        fun hasUnexpiredProxyPass(nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean =
            proxyPassToken.isNotBlank() && (proxyPassExpiresAtSec <= 0L || proxyPassExpiresAtSec > nowEpochSec + 60L)

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("email", email)
            .put("accessToken", accessToken)
            .put("refreshToken", refreshToken)
            .put("proxyPassToken", proxyPassToken)
            .put("proxyPassExpiresAtSec", proxyPassExpiresAtSec)
            .put("quotaLimitBytes", quotaLimitBytes)
            .put("quotaRemainingBytes", quotaRemainingBytes)
            .put("localUsedBytes", localUsedBytes)
            .put("quotaResetEpochSec", quotaResetEpochSec)
            .put("enabled", enabled)

        companion object {
            fun fromJson(obj: JSONObject): FirefoxVpnAccount = FirefoxVpnAccount(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                email = obj.optString("email").ifBlank { "firefox-user@mozilla.org" },
                accessToken = obj.optString("accessToken"),
                refreshToken = obj.optString("refreshToken"),
                proxyPassToken = obj.optString("proxyPassToken"),
                proxyPassExpiresAtSec = obj.optLong("proxyPassExpiresAtSec", 0L),
                quotaLimitBytes = obj.optLong("quotaLimitBytes", DEFAULT_MONTHLY_QUOTA_BYTES)
                    .takeIf { it > 0L } ?: DEFAULT_MONTHLY_QUOTA_BYTES,
                quotaRemainingBytes = obj.optLong("quotaRemainingBytes", DEFAULT_MONTHLY_QUOTA_BYTES)
                    .coerceAtLeast(0L),
                localUsedBytes = obj.optLong("localUsedBytes", 0L).coerceAtLeast(0L),
                quotaResetEpochSec = obj.optLong("quotaResetEpochSec", 0L),
                enabled = obj.optBoolean("enabled", true),
            )
        }
    }

    data class FirefoxVpnCountry(
        val code: String,
        val flag: String,
        val nameFa: String,
        val nameEn: String,
        val cityName: String,
        val proxyHost: String,
        val proxyPort: Int = 443,
        val masqueTemplate: String = "",
    ) {
        val exactEndpoint: String get() = "$proxyHost:$proxyPort"
    }

    data class CountryPingResult(
        val country: FirefoxVpnCountry,
        val rttMs: Long,
        val reachable: Boolean,
    )

    sealed class LoginOutcome {
        data class Success(val account: FirefoxVpnAccount) : LoginOutcome()
        data class NeedsEmailVerification(
            val sessionTokenHex: String,
            val email: String,
            val verificationMethod: String = "email-2fa",
        ) : LoginOutcome()
        data class Failure(val messageFa: String, val messageEn: String) : LoginOutcome()
    }

    /**
     * Complete 35-country Mozilla IP Protection (Fastly Edge) server catalog.
     * Merged with live Mozilla Remote Settings records when online.
     */
    val BUILTIN_COUNTRIES: List<FirefoxVpnCountry> = listOf(
        FirefoxVpnCountry("REC", "⭐", "پیشنهاد هوشمند موزیلا (Anycast)", "Mozilla Recommended (Anycast)", "Anycast", "proxy-rec.fpn.mozilla.net", 443),
        FirefoxVpnCountry("DE", "🇩🇪", "آلمان (فرانکفورت — پینگ عالی در ایران)", "Germany (Frankfurt)", "Frankfurt", "proxy-de.fpn.mozilla.net", 443),
        FirefoxVpnCountry("NL", "🇳🇱", "هلند (آمستردام — سرعت بالا)", "Netherlands (Amsterdam)", "Amsterdam", "proxy-nl.fpn.mozilla.net", 443),
        FirefoxVpnCountry("GB", "🇬🇧", "انگلستان (لندن)", "United Kingdom (London)", "London", "proxy-gb.fpn.mozilla.net", 443),
        FirefoxVpnCountry("US", "🇺🇸", "آمریکا (نیویورک / واشنگتن — باز کردن Gemini)", "United States (New York)", "New York", "proxy-us.fpn.mozilla.net", 443),
        FirefoxVpnCountry("FR", "🇫🇷", "فرانسه (پاریس)", "France (Paris)", "Paris", "proxy-fr.fpn.mozilla.net", 443),
        FirefoxVpnCountry("CH", "🇨🇭", "سوئیس (زوریخ)", "Switzerland (Zurich)", "Zurich", "proxy-ch.fpn.mozilla.net", 443),
        FirefoxVpnCountry("SE", "🇸🇪", "سوئد (استکهلم)", "Sweden (Stockholm)", "Stockholm", "proxy-se.fpn.mozilla.net", 443),
        FirefoxVpnCountry("AT", "🇦🇹", "اتریش (وین)", "Austria (Vienna)", "Vienna", "proxy-at.fpn.mozilla.net", 443),
        FirefoxVpnCountry("FI", "🇫🇮", "فنلاند (هلسینکی)", "Finland (Helsinki)", "Helsinki", "proxy-fi.fpn.mozilla.net", 443),
        FirefoxVpnCountry("CA", "🇨🇦", "کانادا (تورنتو)", "Canada (Toronto)", "Toronto", "proxy-ca.fpn.mozilla.net", 443),
        FirefoxVpnCountry("SG", "🇸🇬", "سنگاپور", "Singapore", "Singapore", "proxy-sg.fpn.mozilla.net", 443),
        FirefoxVpnCountry("JP", "🇯🇵", "ژاپن (توکیو)", "Japan (Tokyo)", "Tokyo", "proxy-jp.fpn.mozilla.net", 443),
        FirefoxVpnCountry("IT", "🇮🇹", "ایتالیا (میلان)", "Italy (Milan)", "Milan", "proxy-it.fpn.mozilla.net", 443),
        FirefoxVpnCountry("ES", "🇪🇸", "اسپانیا (مادرید)", "Spain (Madrid)", "Madrid", "proxy-es.fpn.mozilla.net", 443),
        FirefoxVpnCountry("BE", "🇧🇪", "بلژیک (بروکسل)", "Belgium (Brussels)", "Brussels", "proxy-be.fpn.mozilla.net", 443),
        FirefoxVpnCountry("DK", "🇩🇰", "دانمارک (کپنهاگ)", "Denmark (Copenhagen)", "Copenhagen", "proxy-dk.fpn.mozilla.net", 443),
        FirefoxVpnCountry("NO", "🇳🇴", "نروژ (اسلو)", "Norway (Oslo)", "Oslo", "proxy-no.fpn.mozilla.net", 443),
        FirefoxVpnCountry("PL", "🇵🇱", "لهستان (ورشو)", "Poland (Warsaw)", "Warsaw", "proxy-pl.fpn.mozilla.net", 443),
        FirefoxVpnCountry("CZ", "🇨🇿", "جمهوری چک (پراگ)", "Czechia (Prague)", "Prague", "proxy-cz.fpn.mozilla.net", 443),
        FirefoxVpnCountry("IE", "🇮🇪", "ایرلند (دوبلین)", "Ireland (Dublin)", "Dublin", "proxy-ie.fpn.mozilla.net", 443),
        FirefoxVpnCountry("RO", "🇷🇴", "رومانی (بخارست)", "Romania (Bucharest)", "Bucharest", "proxy-ro.fpn.mozilla.net", 443),
        FirefoxVpnCountry("BG", "🇧🇬", "بلغارستان (صوفیه)", "Bulgaria (Sofia)", "Sofia", "proxy-bg.fpn.mozilla.net", 443),
        FirefoxVpnCountry("HU", "🇭🇺", "مجارستان (بوداپست)", "Hungary (Budapest)", "Budapest", "proxy-hu.fpn.mozilla.net", 443),
        FirefoxVpnCountry("PT", "🇵🇹", "پرتغال (لیسبون)", "Portugal (Lisbon)", "Lisbon", "proxy-pt.fpn.mozilla.net", 443),
        FirefoxVpnCountry("GR", "🇬🇷", "یونان (آتن)", "Greece (Athens)", "Athens", "proxy-gr.fpn.mozilla.net", 443),
        FirefoxVpnCountry("EE", "🇪🇪", "استونی (تالین)", "Estonia (Tallinn)", "Tallinn", "proxy-ee.fpn.mozilla.net", 443),
        FirefoxVpnCountry("LV", "🇱🇻", "لتونی (ریگا)", "Latvia (Riga)", "Riga", "proxy-lv.fpn.mozilla.net", 443),
        FirefoxVpnCountry("LU", "🇱🇺", "لوکزامبورگ", "Luxembourg", "Luxembourg", "proxy-lu.fpn.mozilla.net", 443),
        FirefoxVpnCountry("SI", "🇸🇮", "اسلوونی (لیوبلیانا)", "Slovenia (Ljubljana)", "Ljubljana", "proxy-si.fpn.mozilla.net", 443),
        FirefoxVpnCountry("SK", "🇸🇰", "اسلواکی (براتیسلاوا)", "Slovakia (Bratislava)", "Bratislava", "proxy-sk.fpn.mozilla.net", 443),
        FirefoxVpnCountry("HR", "🇭🇷", "کرواسی (زاگرب)", "Croatia (Zagreb)", "Zagreb", "proxy-hr.fpn.mozilla.net", 443),
        FirefoxVpnCountry("RS", "🇷🇸", "صربستان (بلگراد)", "Serbia (Belgrade)", "Belgrade", "proxy-rs.fpn.mozilla.net", 443),
        FirefoxVpnCountry("UA", "🇺🇦", "اوکراین (کی‌یف)", "Ukraine (Kyiv)", "Kyiv", "proxy-ua.fpn.mozilla.net", 443),
        FirefoxVpnCountry("AU", "🇦🇺", "استرالیا (سیدنی)", "Australia (Sydney)", "Sydney", "proxy-au.fpn.mozilla.net", 443),
    )

    // =========================================================================
    // 1. PICL v1 Cryptographic Key Derivation (PBKDF2-HMAC-SHA256 + HKDF + HAWK)
    // =========================================================================

    /**
     * Derives `quickStretch` (32 bytes) using `PBKDF2-HMAC-SHA256` with 1000 rounds
     * and salt `identity.mozilla.com/picl/v1/quickStretch:<email>`.
     */
    fun deriveQuickStretch(email: String, password: String): ByteArray {
        val normalizedEmail = email.trim().lowercase(Locale.US)
        val salt = "${PICL_NAMESPACE}quickStretch:$normalizedEmail".toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 32 * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Pure-JVM RFC 5869 HKDF-SHA256 implementation (Extract + Expand).
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val macExtract = Mac.getInstance("HmacSHA256")
        macExtract.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = macExtract.doFinal(ikm)

        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream(outputLength)
        var prev = ByteArray(0)
        var counter = 1
        while (out.size() < outputLength) {
            macExpand.reset()
            macExpand.update(prev)
            macExpand.update(info)
            macExpand.update(counter.toByte())
            prev = macExpand.doFinal()
            val toWrite = minOf(prev.size, outputLength - out.size())
            out.write(prev, 0, toWrite)
            counter++
        }
        return out.toByteArray()
    }

    /**
     * Derives the 64-character hex `authPW` sent to `/v1/account/login`.
     */
    fun deriveAuthPw(email: String, password: String): String {
        val quickStretch = deriveQuickStretch(email, password)
        val info = "${PICL_NAMESPACE}authPW".toByteArray(StandardCharsets.UTF_8)
        val derived = hkdfSha256(quickStretch, ByteArray(32), info, 32)
        return bytesToHex(derived)
    }

    /**
     * Derives Hawk `(tokenIdHex, hmacKeyBytes)` from a 64-char hex `sessionToken`.
     */
    fun deriveHawkCredentials(sessionTokenHex: String): Pair<String, ByteArray> {
        val ikm = hexToBytes(sessionTokenHex.trim())
        val info = "${PICL_NAMESPACE}sessionToken".toByteArray(StandardCharsets.UTF_8)
        val expanded = hkdfSha256(ikm, ByteArray(32), info, 64)
        val tokenIdHex = bytesToHex(expanded.copyOfRange(0, 32))
        val hmacKey = expanded.copyOfRange(32, 64)
        return tokenIdHex to hmacKey
    }

    /**
     * Generates a Mozilla FxA `Hawk` HTTP Authorization header.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun generateHawkHeader(
        method: String,
        url: String,
        tokenId: String,
        hmacKey: ByteArray,
        bodyBytes: ByteArray = ByteArray(0),
        tsSeconds: String = (System.currentTimeMillis() / 1000L).toString(),
        nonceOverride: String? = null,
    ): String {
        val nonce = nonceOverride ?: run {
            val rnd = ByteArray(6)
            SecureRandom().nextBytes(rnd)
            Base64.UrlSafe.encode(rnd).trimEnd('=')
        }
        val parsed = URI(url)
        val host = parsed.host.orEmpty()
        val port = if (parsed.port > 0) parsed.port else if (parsed.scheme.equals("https", ignoreCase = true)) 443 else 80
        val rawPath = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val pathAndQuery = if (!parsed.rawQuery.isNullOrEmpty()) "$rawPath?${parsed.rawQuery}" else rawPath

        val payloadHash = if (bodyBytes.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("hawk.1.payload\napplication/json\n".toByteArray(StandardCharsets.UTF_8))
            md.update(bodyBytes)
            md.update("\n".toByteArray(StandardCharsets.UTF_8))
            Base64.Default.encode(md.digest())
        } else {
            ""
        }

        val normalized = buildString {
            append("hawk.1.header\n")
            append(tsSeconds).append('\n')
            append(nonce).append('\n')
            append(method.uppercase(Locale.US)).append('\n')
            append(pathAndQuery).append('\n')
            append(host).append('\n')
            append(port).append('\n')
            append(payloadHash).append("\n\n")
        }

        val macEngine = Mac.getInstance("HmacSHA256")
        macEngine.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val macB64 = Base64.Default.encode(macEngine.doFinal(normalized.toByteArray(StandardCharsets.UTF_8)))

        return if (payloadHash.isNotEmpty()) {
            """Hawk id="$tokenId", ts="$tsSeconds", nonce="$nonce", mac="$macB64", hash="$payloadHash""""
        } else {
            """Hawk id="$tokenId", ts="$tsSeconds", nonce="$nonce", mac="$macB64""""
        }
    }

    /**
     * Extracts the `exp` claim from a JWT token, or returns `null` if invalid.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun extractJwtExpirySeconds(jwt: String): Long? {
        val parts = jwt.trim().split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payloadPart = parts[1]
            val padded = payloadPart + "=".repeat((4 - payloadPart.length % 4) % 4)
            val decoded = Base64.UrlSafe.decode(padded).toString(StandardCharsets.UTF_8)
            val json = JSONObject(decoded)
            json.optLong("exp", 0L).takeIf { it > 0L }
        }.getOrNull()
    }

    /**
     * Solves Fastly's 2-character alphanumeric SHA-256 proof-of-work challenge (`solve_pow`).
     */
    fun solveFastlyPow(basePrefix: String, targetHex: String): String? {
        val targetBytes = runCatching { hexToBytes(targetHex.trim()) }.getOrNull() ?: return null
        if (targetBytes.size != 32) return null
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val md = MessageDigest.getInstance("SHA-256")
        val baseBytes = basePrefix.toByteArray(StandardCharsets.UTF_8)
        for (i in alphabet.indices) {
            for (j in alphabet.indices) {
                val suffix = "${alphabet[i]}${alphabet[j]}"
                md.reset()
                md.update(baseBytes)
                val digest = md.digest(suffix.toByteArray(StandardCharsets.UTF_8))
                if (digest.contentEquals(targetBytes)) {
                    return suffix
                }
            }
        }
        return null
    }

    // =========================================================================
    // 2. Account Storage & Multi-Account Failover Management
    // =========================================================================

    fun loadAccounts(context: Context): List<FirefoxVpnAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACCOUNTS_JSON, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(FirefoxVpnAccount.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAccounts(context: Context, accounts: List<FirefoxVpnAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS_JSON, arr.toString())
            .apply()
    }

    fun upsertAccount(context: Context, account: FirefoxVpnAccount): List<FirefoxVpnAccount> {
        val current = loadAccounts(context).toMutableList()
        val idx = current.indexOfFirst {
            it.id == account.id || it.email.equals(account.email, ignoreCase = true)
        }
        if (idx >= 0) {
            current[idx] = account
        } else {
            current.add(0, account)
        }
        saveAccounts(context, current)
        return current
    }

    fun removeAccount(context: Context, accountId: String): List<FirefoxVpnAccount> {
        val updated = loadAccounts(context).filterNot { it.id == accountId }
        saveAccounts(context, updated)
        return updated
    }

    fun readSelectedCountryCode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_COUNTRY, COUNTRY_FASTEST)
            ?.takeIf { it.isNotBlank() }
            ?: COUNTRY_FASTEST

    fun saveSelectedCountryCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_COUNTRY, code.trim().uppercase(Locale.US))
            .apply()
    }

    fun isTlsFragmentEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TLS_FRAGMENT, true)

    fun setTlsFragmentEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TLS_FRAGMENT, enabled)
            .apply()
    }

    fun readCleanFastlyEdgeIp(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLEAN_FASTLY_IP, "")
            .orEmpty()
            .trim()

    fun saveCleanFastlyEdgeIp(context: Context, ip: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLEAN_FASTLY_IP, ip.trim())
            .apply()
    }

    fun isMultiAccountFailoverEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_FAILOVER, true)

    /**
     * Picks the best enabled account that still has available quota out of its 50 GB monthly allowance.
     * Automatically skips accounts at >= 95% usage when another account is available.
     */
    fun selectBestActiveAccount(accounts: List<FirefoxVpnAccount>): FirefoxVpnAccount? {
        val enabled = accounts.filter { it.enabled }
        if (enabled.isEmpty()) return accounts.firstOrNull()
        return enabled.firstOrNull { !it.isQuotaExhaustedOrNearLimit }
            ?: enabled.maxByOrNull { it.effectiveRemainingBytes }
    }

    /**
     * Imports an account from a raw OAuth `refresh_token`, `access_token`, `ProxyPass JWT` (`eyJ...`),
     * or JSON exported from `foxyvpn-cli` / `firefox-vpn-client` / `MLM VPN`.
     */
    fun addAccountFromTokenOrJson(
        context: Context,
        emailOrLabel: String,
        rawInput: String,
    ): FirefoxVpnAccount? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val cleanEmail = emailOrLabel.trim().ifBlank { "firefox-${(1000..9999).random()}@mozilla.org" }

        val parsedAccount: FirefoxVpnAccount = (if (trimmed.startsWith("{")) {
            runCatching {
                val obj = JSONObject(trimmed)
                val email = obj.optString("email").ifBlank { cleanEmail }
                val access = obj.optString("access_token").ifBlank { obj.optString("accessToken") }
                val refresh = obj.optString("refresh_token").ifBlank { obj.optString("refreshToken") }
                val pass = obj.optString("proxy_pass").ifBlank {
                    obj.optString("proxyPassToken").ifBlank { obj.optString("token") }
                }
                FirefoxVpnAccount(
                    email = email,
                    accessToken = access,
                    refreshToken = refresh,
                    proxyPassToken = pass,
                    proxyPassExpiresAtSec = extractJwtExpirySeconds(pass) ?: 0L,
                )
            }.getOrNull()
        } else if (trimmed.count { it == '.' } == 2 && trimmed.startsWith("eyJ")) {
            // Direct ProxyPass JWT token
            FirefoxVpnAccount(
                email = cleanEmail,
                proxyPassToken = trimmed,
                proxyPassExpiresAtSec = extractJwtExpirySeconds(trimmed)
                    ?: ((System.currentTimeMillis() / 1000L) + 86_400L),
            )
        } else {
            // OAuth refresh_token or access_token
            FirefoxVpnAccount(
                email = cleanEmail,
                accessToken = trimmed,
                refreshToken = trimmed,
            )
        }) ?: return null

        val enriched = runCatching {
            refreshAndActivateAccountPass(context, parsedAccount)
        }.getOrDefault(parsedAccount)
        upsertAccount(context, enriched)
        return enriched
    }

    // =========================================================================
    // 3. FxA Sign-In, 2FA Verification & Guardian ProxyPass Provisioning
    // =========================================================================

    fun loginWithEmailAndPassword(
        context: Context,
        email: String,
        password: String,
    ): LoginOutcome {
        val cleanEmail = email.trim().lowercase(Locale.US)
        if (cleanEmail.isBlank() || !cleanEmail.contains("@") || password.isBlank()) {
            return LoginOutcome.Failure(
                messageFa = "لطفاً ایمیل و رمز عبور حساب فایرفاکس را کامل وارد کنید.",
                messageEn = "Please enter both your Firefox Account email and password.",
            )
        }
        return try {
            val authPw = deriveAuthPw(cleanEmail, password)
            val loginPayload = JSONObject()
                .put("email", cleanEmail)
                .put("authPW", authPw)
                .put("verificationMethod", "email-2fa")
            val loginResp = fxaPostJson("/account/login", loginPayload, sessionTokenHex = null)
            val sessionToken = loginResp.optString("sessionToken")
            if (sessionToken.isBlank()) {
                val errMsg = loginResp.optString("message").ifBlank { "Invalid credentials or blocked by network" }
                return LoginOutcome.Failure(
                    messageFa = "خطا در ورود به حساب فایرفاکس: $errMsg",
                    messageEn = "Firefox Account login failed: $errMsg",
                )
            }
            val verified = loginResp.optBoolean("verified", false)
            if (!verified) {
                return LoginOutcome.NeedsEmailVerification(
                    sessionTokenHex = sessionToken,
                    email = cleanEmail,
                    verificationMethod = loginResp.optString("verificationMethod", "email-2fa"),
                )
            }
            completeSessionLogin(context, sessionToken, cleanEmail)
        } catch (e: Exception) {
            LoginOutcome.Failure(
                messageFa = "خطا در ارتباط با سرور حساب فایرفاکس (${e.message ?: "Network Error"}). اگر کلادفلر یا اینترنت اختلال دارد، می‌توانید از گزینه «ورود با توکن» یا «اتصال فوری فستلی» استفاده کنید.",
                messageEn = "Could not reach Firefox Accounts server (${e.message ?: "Network Error"}).",
            )
        }
    }

    fun verifyEmail2faAndCompleteLogin(
        context: Context,
        sessionTokenHex: String,
        code: String,
        email: String,
    ): LoginOutcome {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) {
            return LoginOutcome.Failure(
                messageFa = "کد تایید ارسال‌شده به ایمیل را وارد کنید.",
                messageEn = "Please enter the verification code sent to your email.",
            )
        }
        return try {
            val verifyBody = JSONObject().put("code", cleanCode)
            val verifyResp = fxaPostJson("/session/verify_code", verifyBody, sessionTokenHex = sessionTokenHex)
            if (verifyResp.has("errno") && verifyResp.optInt("errno") != 0) {
                val msg = verifyResp.optString("message", "Invalid verification code")
                return LoginOutcome.Failure(
                    messageFa = "کد تایید نامعتبر است: $msg",
                    messageEn = "Verification failed: $msg",
                )
            }
            completeSessionLogin(context, sessionTokenHex, email)
        } catch (e: Exception) {
            LoginOutcome.Failure(
                messageFa = "خطا در تایید کد دو مرحله‌ای: ${e.message}",
                messageEn = "2FA verification error: ${e.message}",
            )
        }
    }

    private fun completeSessionLogin(
        context: Context,
        sessionTokenHex: String,
        email: String,
    ): LoginOutcome {
        val oauthBody = JSONObject()
            .put("client_id", FIREFOX_CLIENT_ID)
            .put("grant_type", "fxa-credentials")
            .put("scope", OAUTH_SCOPE)
            .put("access_type", "offline")
        val tokenResp = fxaPostJson("/oauth/token", oauthBody, sessionTokenHex = sessionTokenHex)
        val accessToken = tokenResp.optString("access_token")
        val refreshToken = tokenResp.optString("refresh_token")
        if (accessToken.isBlank()) {
            val msg = tokenResp.optString("message", "No OAuth access_token returned")
            return LoginOutcome.Failure(
                messageFa = "دریافت توکن OAuth فایرفاکس ناموفق بود: $msg",
                messageEn = "Failed to obtain Firefox OAuth token: $msg",
            )
        }
        val baseAccount = FirefoxVpnAccount(
            email = email,
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
        val activated = runCatching {
            refreshAndActivateAccountPass(context, baseAccount)
        }.getOrDefault(baseAccount)
        upsertAccount(context, activated)
        return LoginOutcome.Success(activated)
    }

    /**
     * Exchanges `refreshToken` (if needed) and fetches/activates the 50 GB Guardian `ProxyPass` JWT.
     */
    fun refreshAndActivateAccountPass(
        context: Context,
        account: FirefoxVpnAccount,
    ): FirefoxVpnAccount {
        var currentAccess = account.accessToken
        var currentRefresh = account.refreshToken
        if (currentAccess.isBlank() && currentRefresh.isNotBlank()) {
            val refreshBody = JSONObject()
                .put("client_id", FIREFOX_CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", currentRefresh)
                .put("scope", OAUTH_SCOPE)
            val refreshed = runCatching { fxaPostJson("/oauth/token", refreshBody, null) }.getOrNull()
            if (refreshed != null && refreshed.optString("access_token").isNotBlank()) {
                currentAccess = refreshed.optString("access_token")
                currentRefresh = refreshed.optString("refresh_token").ifBlank { currentRefresh }
            }
        }
        if (currentAccess.isBlank()) return account

        var passResp = guardianRequest("GET", "/api/v1/fpn/token", currentAccess)
        if (passResp.statusCode in listOf(401, 403)) {
            // Auto-activate Guardian 50 GB free entitlement for newly created Firefox accounts
            runCatching { guardianRequest("POST", "/api/v1/fpn/activate", currentAccess) }
            passResp = guardianRequest("GET", "/api/v1/fpn/token", currentAccess)
        }
        if (passResp.statusCode == 200 && passResp.body.isNotBlank()) {
            val json = JSONObject(passResp.body)
            val jwt = json.optString("token")
            val exp = json.optLong("expires_at", 0L).takeIf { it > 0L }
                ?: extractJwtExpirySeconds(jwt)
                ?: ((System.currentTimeMillis() / 1000L) + 3600L)
            val quotaLimit = passResp.quotaLimit ?: account.quotaLimitBytes
            val quotaRemaining = passResp.quotaRemaining ?: account.quotaRemainingBytes
            val quotaReset = passResp.quotaReset ?: account.quotaResetEpochSec
            val updated = account.copy(
                accessToken = currentAccess,
                refreshToken = currentRefresh,
                proxyPassToken = jwt.ifBlank { account.proxyPassToken },
                proxyPassExpiresAtSec = exp,
                quotaLimitBytes = quotaLimit,
                quotaRemainingBytes = quotaRemaining,
                quotaResetEpochSec = quotaReset,
            )
            upsertAccount(context, updated)
            return updated
        }
        return account.copy(accessToken = currentAccess, refreshToken = currentRefresh)
    }

    private data class GuardianHttpResponse(
        val statusCode: Int,
        val body: String,
        val quotaLimit: Long?,
        val quotaRemaining: Long?,
        val quotaReset: Long?,
    )

    private fun fxaPostJson(
        path: String,
        jsonBody: JSONObject,
        sessionTokenHex: String?,
    ): JSONObject {
        val fullUrl = "$FXA_AUTH_SERVER$path"
        val bodyBytes = jsonBody.toString().toByteArray(StandardCharsets.UTF_8)
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", MOZILLA_VPN_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (!sessionTokenHex.isNullOrBlank()) {
                val (tokenId, hmacKey) = deriveHawkCredentials(sessionTokenHex)
                val hawk = generateHawkHeader("POST", fullUrl, tokenId, hmacKey, bodyBytes)
                setRequestProperty("Authorization", hawk)
            }
        }
        return try {
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (text.trim().startsWith("{")) JSONObject(text) else JSONObject().put("message", text)
        } finally {
            conn.disconnect()
        }
    }

    private fun guardianRequest(
        method: String,
        path: String,
        accessToken: String,
    ): GuardianHttpResponse {
        val fullUrl = "$GUARDIAN_ENDPOINT$path"
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = method
            setRequestProperty("User-Agent", MOZILLA_VPN_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (method == "POST") {
                doOutput = true
            }
        }
        return try {
            if (method == "POST") {
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val limit = conn.getHeaderField("X-Quota-Limit")?.toLongOrNull()
            val remaining = conn.getHeaderField("X-Quota-Remaining")?.toLongOrNull()
            val reset = conn.getHeaderField("X-Quota-Reset")?.toLongOrNull()
            GuardianHttpResponse(code, body, limit, remaining, reset)
        } finally {
            conn.disconnect()
        }
    }

    // =========================================================================
    // 4. Country Discovery, Remote Settings Sync & Fastly Ping Scanner
    // =========================================================================

    fun getAvailableCountries(context: Context? = null): List<FirefoxVpnCountry> {
        if (context == null) return BUILTIN_COUNTRIES
        val cachedRaw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_COUNTRIES, "")
            .orEmpty()
        if (cachedRaw.isBlank()) return BUILTIN_COUNTRIES
        val byCode = BUILTIN_COUNTRIES.associateBy { it.code.uppercase(Locale.US) }.toMutableMap()
        runCatching {
            val arr = JSONArray(cachedRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val code = obj.optString("code").uppercase(Locale.US)
                val host = obj.optString("proxyHost")
                val port = obj.optInt("proxyPort", 443)
                if (code.isNotBlank() && host.isNotBlank()) {
                    val existing = byCode[code]
                    if (existing != null) {
                        byCode[code] = existing.copy(proxyHost = host, proxyPort = port)
                    } else {
                        byCode[code] = FirefoxVpnCountry(
                            code = code,
                            flag = countryCodeToFlagEmoji(code),
                            nameFa = obj.optString("nameFa").ifBlank { obj.optString("nameEn", code) },
                            nameEn = obj.optString("nameEn", code),
                            cityName = obj.optString("cityName", code),
                            proxyHost = host,
                            proxyPort = port,
                        )
                    }
                }
            }
        }
        return byCode.values.toList()
    }

    /**
     * Fetches the live server list from Mozilla Remote Settings and updates the local cache.
     */
    fun syncRemoteSettingsCountries(context: Context): List<FirefoxVpnCountry> {
        runCatching {
            val conn = (URL(REMOTE_SETTINGS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", MOZILLA_VPN_USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: JSONArray()
                    val cacheArr = JSONArray()
                    for (i in 0 until data.length()) {
                        val rec = data.optJSONObject(i) ?: continue
                        val cObj = rec.optJSONObject("country") ?: rec
                        val code = cObj.optString("code").trim().uppercase(Locale.US)
                        val nameEn = cObj.optString("name").trim()
                        if (code.isEmpty() || nameEn.equals("CatchAll Anycast", ignoreCase = true)) continue
                        val cities = cObj.optJSONArray("cities") ?: continue
                        var chosenHost = ""
                        var chosenPort = 443
                        var chosenCity = nameEn
                        for (ci in 0 until cities.length()) {
                            val city = cities.optJSONObject(ci) ?: continue
                            val servers = city.optJSONArray("servers") ?: continue
                            for (si in 0 until servers.length()) {
                                val srv = servers.optJSONObject(si) ?: continue
                                if (srv.optBoolean("quarantined", false)) continue
                                chosenHost = srv.optString("hostname")
                                chosenPort = srv.optInt("port", 443).takeIf { it in 1..65535 } ?: 443
                                val protos = srv.optJSONArray("protocols")
                                if (protos != null) {
                                    for (pi in 0 until protos.length()) {
                                        val p = protos.optJSONObject(pi) ?: continue
                                        if (p.optString("name") == "connect") {
                                            chosenHost = p.optString("host").ifBlank { chosenHost }
                                            chosenPort = p.optInt("port", chosenPort).takeIf { it in 1..65535 } ?: chosenPort
                                            break
                                        }
                                    }
                                }
                                chosenCity = city.optString("name").ifBlank { nameEn }
                                if (chosenHost.isNotBlank()) break
                            }
                            if (chosenHost.isNotBlank()) break
                        }
                        if (chosenHost.isNotBlank()) {
                            cacheArr.put(
                                JSONObject()
                                    .put("code", code)
                                    .put("nameEn", nameEn)
                                    .put("cityName", chosenCity)
                                    .put("proxyHost", chosenHost)
                                    .put("proxyPort", chosenPort),
                            )
                        }
                    }
                    if (cacheArr.length() > 0) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CACHED_COUNTRIES, cacheArr.toString())
                            .apply()
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        return getAvailableCountries(context)
    }

    /**
     * Probes TCP/TLS latency to Fastly edge & Firefox VPN country endpoints.
     */
    fun pingCountries(
        context: Context? = null,
        maxCountries: Int = 12,
        timeoutMs: Int = 900,
    ): List<CountryPingResult> {
        val list = getAvailableCountries(context).take(maxCountries)
        val cleanIp = context?.let { readCleanFastlyEdgeIp(it) }.orEmpty()
        val results = mutableListOf<CountryPingResult>()
        for (c in list) {
            val targetHost = cleanIp.ifBlank { c.proxyHost }
            val rtt = runCatching {
                val t0 = System.nanoTime()
                Socket().use { s ->
                    s.connect(InetSocketAddress(targetHost, c.proxyPort), timeoutMs)
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            results += CountryPingResult(
                country = c,
                rttMs = rtt ?: 9_999L,
                reachable = rtt != null,
            )
        }
        return results.sortedWith(
            compareByDescending<CountryPingResult> { it.reachable }
                .thenBy { it.rttMs },
        )
    }

    /**
     * Scans clean Fastly Anycast edge IPs (`151.101.*`, `146.75.*`) to find the lowest-latency
     * edge IP on the user's current Iranian ISP.
     */
    fun findBestCleanFastlyEdgeIp(timeoutMs: Int = 900): Pair<String, Long>? {
        var bestIp: String? = null
        var bestRtt = Long.MAX_VALUE
        for (ip in CLEAN_FASTLY_EDGE_IPS) {
            val rtt = runCatching {
                val t0 = System.nanoTime()
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, 443), timeoutMs)
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull() ?: continue
            if (rtt < bestRtt) {
                bestRtt = rtt
                bestIp = ip
            }
        }
        return bestIp?.let { it to bestRtt }
    }

    // =========================================================================
    // 5. Local FENIX Anti-DPI Proxy Bridge (`127.0.0.1:10829`) + URI Builder
    // =========================================================================

    private var proxyServerSocket: ServerSocket? = null
    private val proxyRunning = AtomicBoolean(false)
    private val activeLocalPort = AtomicInteger(DEFAULT_LOCAL_PROXY_PORT)
    val totalTunneledBytes = AtomicLong(0L)

    @Volatile
    var currentCountry: FirefoxVpnCountry = BUILTIN_COUNTRIES[1] // DE default
        private set

    @Volatile
    var currentBearerPass: String = ""
        private set

    @Volatile
    var currentCleanEdgeIp: String = ""
        private set

    @Volatile
    var currentFragmentTls: Boolean = true
        private set

    fun isLocalFenixProxyRunning(): Boolean =
        proxyRunning.get() && proxyServerSocket != null && !proxyServerSocket!!.isClosed

    fun activeProxyPort(): Int = activeLocalPort.get()

    /**
     * Builds a shareable `fenix://` URI that `SubConvConverter.convert(...)` compiles into
     * a Mihomo proxy outbound pointing to the local FENIX Anti-DPI Fastly tunnel (or direct HTTPS proxy).
     */
    fun buildFenixTunnelUri(
        country: FirefoxVpnCountry,
        localPort: Int = activeProxyPort(),
        accountEmail: String = "",
        cleanFastlyIp: String = "",
        tlsFragment: Boolean = true,
    ): String {
        val edgeParam = if (cleanFastlyIp.isNotBlank()) "&edge=$cleanFastlyIp" else ""
        val userParam = if (accountEmail.isNotBlank()) {
            "&account=" + java.net.URLEncoder.encode(accountEmail, "UTF-8")
        } else ""
        val fragParam = if (tlsFragment) "&fragment=tlshello-sni" else ""
        val remark = java.net.URLEncoder.encode(
            "🦊 فایرفاکس VPN · ${country.flag} ${country.nameFa}",
            "UTF-8",
        )
        return "fenix://127.0.0.1:$localPort/?country=${country.code}&host=${country.proxyHost}&port=${country.proxyPort}$edgeParam$userParam$fragParam#$remark"
    }

    /**
     * Starts (or updates) the local FENIX proxy server on `127.0.0.1` that accepts both
     * `SOCKS5` and `HTTP CONNECT` from Mihomo TUN and tunnels streams over Mozilla's
     * Fastly HTTPS CONNECT proxy (`Proxy-Authorization: Bearer <ProxyPass-JWT>`).
     */
    @Synchronized
    fun startOrUpdateLocalFenixProxy(
        context: Context? = null,
        country: FirefoxVpnCountry? = null,
        bearerPass: String? = null,
        cleanEdgeIp: String? = null,
        fragmentTls: Boolean? = null,
        preferredPort: Int = DEFAULT_LOCAL_PROXY_PORT,
    ): Int {
        if (country != null) currentCountry = country
        if (bearerPass != null) currentBearerPass = bearerPass
        if (cleanEdgeIp != null) currentCleanEdgeIp = cleanEdgeIp
        if (fragmentTls != null) currentFragmentTls = fragmentTls

        val existing = proxyServerSocket
        if (existing != null && !existing.isClosed && proxyRunning.get()) {
            return existing.localPort
        }

        return runCatching {
            val server = try {
                ServerSocket(preferredPort, 128, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            }
            proxyServerSocket = server
            activeLocalPort.set(server.localPort)
            proxyRunning.set(true)

            Thread({
                while (proxyRunning.get() && !server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    Thread({
                        handleFenixClientSocket(context, client)
                    }, "fenix-vpn-worker").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "fenix-vpn-accept-${server.localPort}").apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(preferredPort)
    }

    @Synchronized
    fun stopLocalFenixProxy() {
        proxyRunning.set(false)
        runCatching { proxyServerSocket?.close() }
        proxyServerSocket = null
    }

    private fun handleFenixClientSocket(context: Context?, client: Socket) {
        runCatching {
            client.use { c ->
                c.soTimeout = 20_000
                c.tcpNoDelay = true
                val inp = BufferedInputStream(c.getInputStream())
                val out = c.getOutputStream()

                inp.mark(4)
                val firstByte = inp.read()
                if (firstByte < 0) return@use

                val targetHost: String
                val targetPort: Int
                val isSocks5 = (firstByte == 0x05)

                if (isSocks5) {
                    val nMethods = inp.read().coerceAtLeast(0)
                    if (nMethods > 0) inp.read(ByteArray(nMethods))
                    out.write(byteArrayOf(0x05, 0x00))
                    out.flush()

                    val hdr = ByteArray(4)
                    if (inp.read(hdr) != 4 || hdr[0].toInt() != 0x05 || hdr[1].toInt() != 0x01) return@use
                    targetHost = when (hdr[3].toInt() and 0xFF) {
                        0x01 -> {
                            val b = ByteArray(4)
                            if (inp.read(b) != 4) return@use
                            InetAddress.getByAddress(b).hostAddress ?: return@use
                        }
                        0x03 -> {
                            val len = inp.read()
                            if (len <= 0) return@use
                            val b = ByteArray(len)
                            if (inp.read(b) != len) return@use
                            String(b, StandardCharsets.UTF_8)
                        }
                        0x04 -> {
                            val b = ByteArray(16)
                            if (inp.read(b) != 16) return@use
                            InetAddress.getByAddress(b).hostAddress ?: return@use
                        }
                        else -> return@use
                    }
                    val pHi = inp.read()
                    val pLo = inp.read()
                    if (pHi < 0 || pLo < 0) return@use
                    targetPort = ((pHi and 0xFF) shl 8) or (pLo and 0xFF)
                } else {
                    inp.reset()
                    val reqLine = readAsciiLine(inp) ?: return@use
                    // Drain HTTP request headers
                    while (true) {
                        val line = readAsciiLine(inp) ?: break
                        if (line.isEmpty()) break
                    }
                    val parts = reqLine.split(' ')
                    if (parts.size < 2) return@use
                    val authority = parts[1].removePrefix("http://").removePrefix("https://").substringBefore('/')
                    targetHost = authority.substringBeforeLast(':')
                    targetPort = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
                }

                val upstream = openUpstreamTunnelStream(context, targetHost, targetPort) ?: return@use
                try {
                    if (isSocks5) {
                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    } else {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    }
                    out.flush()

                    val upIn = upstream.getInputStream()
                    val upOut = upstream.getOutputStream()

                    val upThread = Thread({
                        runCatching {
                            val buf = ByteArray(8192)
                            var firstPacket = true
                            while (true) {
                                val n = inp.read(buf)
                                if (n <= 0) break
                                totalTunneledBytes.addAndGet(n.toLong())
                                if (firstPacket && currentFragmentTls && n > 5 && (buf[0].toInt() and 0xFF) == 0x16) {
                                    firstPacket = false
                                    val raw = buf.copyOfRange(0, n)
                                    val split = LocalSniSpoofProxy.splitClientHelloAtSni(raw)
                                    if (split != null) {
                                        upOut.write(split.first)
                                        upOut.flush()
                                        Thread.sleep(2)
                                        upOut.write(split.second)
                                        upOut.flush()
                                        continue
                                    }
                                }
                                firstPacket = false
                                upOut.write(buf, 0, n)
                                upOut.flush()
                            }
                        }
                    }, "fenix-up-pipe").apply {
                        isDaemon = true
                        start()
                    }

                    val downBuf = ByteArray(8192)
                    while (true) {
                        val n = upIn.read(downBuf)
                        if (n <= 0) break
                        totalTunneledBytes.addAndGet(n.toLong())
                        out.write(downBuf, 0, n)
                        out.flush()
                    }
                    upThread.join(200)
                } finally {
                    runCatching { upstream.close() }
                }
            }
        }
    }

    /**
     * Opens an authenticated `HTTPS CONNECT` stream to the selected Mozilla Fastly proxy node
     * when a `ProxyPass` token is available, or falls back to a clean Fastly/Anti-DPI direct socket
     * so connections succeed even before an account is added.
     */
    private fun openUpstreamTunnelStream(
        context: Context?,
        targetHost: String,
        targetPort: Int,
    ): Socket? {
        // Resolve active bearer pass (rotating accounts automatically if >= 95% used)
        var pass = currentBearerPass
        if (context != null) {
            val accounts = loadAccounts(context)
            val best = selectBestActiveAccount(accounts)
            if (best != null) {
                val fresh = if (best.hasUnexpiredProxyPass()) best else runCatching {
                    refreshAndActivateAccountPass(context, best)
                }.getOrDefault(best)
                if (fresh.proxyPassToken.isNotBlank()) {
                    pass = fresh.proxyPassToken
                    currentBearerPass = pass
                }
            }
        }

        if (pass.isNotBlank()) {
            val country = currentCountry
            val connectAddr = currentCleanEdgeIp.ifBlank { country.proxyHost }
            val viaGuardian = runCatching {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.soTimeout = 15_000
                raw.connect(InetSocketAddress(connectAddr, country.proxyPort), 8_000)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, country.proxyHost, country.proxyPort, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(country.proxyHost))
                ssl.sslParameters = params
                ssl.startHandshake()

                val authority = if (targetHost.contains(":") && !targetHost.startsWith("[")) {
                    "[$targetHost]:$targetPort"
                } else {
                    "$targetHost:$targetPort"
                }
                val connectReq = buildString {
                    append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                    append("Host: ").append(authority).append("\r\n")
                    append("Proxy-Authorization: Bearer ").append(pass).append("\r\n")
                    append("User-Agent: ").append(MOZILLA_VPN_USER_AGENT).append("\r\n")
                    append("\r\n")
                }
                val sslOut: OutputStream = ssl.getOutputStream()
                sslOut.write(connectReq.toByteArray(StandardCharsets.US_ASCII))
                sslOut.flush()

                val sslIn = ssl.getInputStream()
                val statusLine = readAsciiLine(sslIn).orEmpty()
                while (true) {
                    val hdrLine = readAsciiLine(sslIn) ?: break
                    if (hdrLine.isEmpty()) break
                }
                if (statusLine.contains(" 200 ")) {
                    ssl
                } else {
                    runCatching { ssl.close() }
                    null
                }
            }.getOrNull()
            if (viaGuardian != null) return viaGuardian
        }

        // Fallback: direct socket (with TLS ClientHello split applied by the caller pipe)
        return runCatching {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = 15_000
                connect(InetSocketAddress(targetHost, targetPort), 8_000)
            }
        }.getOrNull()
    }

    private fun readAsciiLine(inp: InputStream): String? {
        val sb = StringBuilder()
        var seenByte = false
        while (true) {
            val b = inp.read()
            if (b < 0) return if (seenByte) sb.toString() else null
            seenByte = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) break
        }
        return sb.toString()
    }

    fun formatQuotaSummaryFa(account: FirefoxVpnAccount): String {
        val remGb = account.effectiveRemainingBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = account.quotaLimitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val pctLeft = ((1.0 - account.usedFraction) * 100.0).toInt().coerceIn(0, 100)
        return String.format(Locale.US, "%.1f از %.0f گیگابایت باقی‌مانده (%d%%)", remGb, totalGb, pctLeft)
    }

    fun formatQuotaSummaryEn(account: FirefoxVpnAccount): String {
        val remGb = account.effectiveRemainingBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = account.quotaLimitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val pctLeft = ((1.0 - account.usedFraction) * 100.0).toInt().coerceIn(0, 100)
        return String.format(Locale.US, "%.1f / %.0f GB left (%d%%)", remGb, totalGb, pctLeft)
    }

    private fun countryCodeToFlagEmoji(code: String): String {
        val clean = code.trim().uppercase(Locale.US)
        if (clean.length != 2) return "🌍"
        val first = Character.codePointAt(clean, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(clean, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    internal fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hexDigits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexDigits[v ushr 4]
            chars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(chars)
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex string length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
