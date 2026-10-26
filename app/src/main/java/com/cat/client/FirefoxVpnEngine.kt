package com.cat.client

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
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
 * `armanlife/firefox-vpn-client`, `meytiii/FoxyVPN-win`, and `ios151/firefox`.
 *
 * Key capabilities:
 * 1. **50 GB/month Free Per Account + Multi-Account Auto-Failover**:
 *    Supports adding multiple free Firefox Accounts (`FxA`). Automatically rotates to the next
 *    account when an account reaches 95% of its 50 GB monthly quota or returns HTTP 429.
 * 2. **Full FxA PICL v1 Crypto + Fastly Anti-Bot Challenge Solver + WebView Sign-In**:
 *    - Automatically solves Fastly `HTTP 406` `/_fs-ch-` challenges (`pow` + `pat` + `clientmetrics`)
 *      and persists challenge cookies across FxA and Guardian requests.
 *    - Handles FxA `errno 120` (email letter-case stretching retry), `errno 107` (parameter fallback),
 *      `errno 125` (`send_unblock_code` + unblock code sign-in), `email-2fa` (`/session/verify_code`),
 *      and `totp-2fa` (`/session/verify/totp`).
 *    - Supports 1-click official Mozilla WebView Sign-In (including "Continue with Google" & CAPTCHA)
 *      by extracting `sessionToken` from `localStorage["__fxa_storage.accounts"]`.
 *    - Enrolls new accounts via both `POST /api/v1/fpn/activate` and `/api/v1/fpn/auth?experiment=ip-protection`.
 * 3. **Bounded Thread Pool (`256KB` stack) + `VpnService.protect` + Loopback Guard**:
 *    Protects all outbound sockets via `protectOutboundSocket(socket)` so traffic never loops back
 *    into `tun0`, enforces a strict concurrency cap (`Semaphore(24)` + bounded `ThreadPoolExecutor`),
 *    and negotiates strict `http/1.1` ALPN on Fastly `CONNECT` tunnels.
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
    const val FXA_OAUTH_SERVER = "https://oauth.accounts.firefox.com/v1"
    const val FXA_WEB_ORIGIN = "https://accounts.firefox.com"
    const val GUARDIAN_ENDPOINT = "https://vpn.mozilla.org"
    const val REMOTE_SETTINGS_URL =
        "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
    const val FIREFOX_CLIENT_ID = "5882386c6d801776"
    const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    const val FIREFOX_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:138.0) Gecko/20100101 Firefox/138.0"
    const val FASTLY_SOLVER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    const val MOZILLA_VPN_USER_AGENT = FIREFOX_DESKTOP_USER_AGENT
    private const val PICL_NAMESPACE = "identity.mozilla.com/picl/v1/"
    const val DEFAULT_MONTHLY_QUOTA_BYTES = 50L * 1024L * 1024L * 1024L // 50 GB
    const val FAILOVER_USAGE_RATIO = 0.95 // Switch account at 95% usage
    const val DEFAULT_LOCAL_PROXY_PORT = 10829

    private const val ERRNO_INVALID_PARAMETER = 107
    private const val ERRNO_INCORRECT_EMAIL_CASE = 120
    private const val ERRNO_REQUEST_BLOCKED = 125

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

    // Shared cookie store for Fastly anti-bot challenge cookies (`_fs_ch_*`)
    private val fastlyCookieStore = ConcurrentHashMap<String, String>()
    private val challengeSolveLock = Any()

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
        data class NeedsUnblockCode(
            val email: String,
            val password: String,
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
    // 1. Input Normalization & PICL v1 Cryptographic Key Derivation
    // =========================================================================

    /**
     * Strips spaces and invisible format characters (`\u200B..D`, `\uFEFF`, `\u00A0`) without
     * altering letter case when [preserveCase] is true (required for FxA `errno 120` key stretching).
     */
    fun normalizeEmail(email: String, preserveCase: Boolean = false): String {
        val sb = StringBuilder(email.length)
        for (ch in email) {
            if (ch.isWhitespace() || ch == '\u00A0' || ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') {
                continue
            }
            sb.append(ch)
        }
        val clean = sb.toString()
        return if (preserveCase) clean else clean.lowercase(Locale.US)
    }

    /**
     * Converts Persian (`۰-۹`) and Arabic-Indic (`٠-٩`) digits to ASCII `0-9` and strips
     * spaces, dashes, and zero-width characters from verification / unblock codes.
     */
    fun normalizeCode(code: String): String {
        val sb = StringBuilder(code.length)
        for (ch in code) {
            when {
                ch in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                ch in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                ch.isWhitespace() || ch == '-' || ch == '\u00A0' || ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Derives `quickStretch` (32 bytes) using `PBKDF2-HMAC-SHA256` with 1000 rounds
     * and salt `identity.mozilla.com/picl/v1/quickStretch:<email>`.
     * Normalizes to lowercase by default, or preserves exact letter case when [preserveEmailCase] is true
     * (used when FxA returns `errno 120` with the server's exact email spelling).
     */
    fun deriveQuickStretch(email: String, password: String, preserveEmailCase: Boolean = false): ByteArray {
        val cleanEmail = normalizeEmail(email, preserveCase = preserveEmailCase)
        val salt = "${PICL_NAMESPACE}quickStretch:$cleanEmail".toByteArray(StandardCharsets.UTF_8)
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
    fun deriveAuthPw(email: String, password: String, preserveEmailCase: Boolean = false): String {
        val quickStretch = deriveQuickStretch(email, password, preserveEmailCase)
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
            val payloadPart = parts[1].trimEnd('=')
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
     * Extracts `sessionToken` and `email` from the `window.localStorage.getItem("__fxa_storage.accounts")`
     * JSON produced by `https://accounts.firefox.com/` after signing in via WebView (including Google Sign-In).
     */
    fun extractSessionFromWebViewStorageJson(rawStorageJson: String): Pair<String, String>? {
        val cleaned = rawStorageJson.trim().let { raw ->
            // evaluateJavascript returns a JSON-quoted string when the value is a string
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault(raw)
            } else {
                raw
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "null" || !cleaned.startsWith("{")) return null

        return runCatching {
            val root = JSONObject(cleaned)
            // Case 1: direct object containing sessionToken
            val directToken = root.optString("sessionToken").trim()
            if (directToken.length >= 32) {
                val directEmail = root.optString("email").ifBlank { "firefox-user@mozilla.org" }
                return@runCatching directToken to directEmail
            }
            // Case 2: map of uid -> account object (standard __fxa_storage.accounts format)
            val keys = root.keys()
            var bestPair: Pair<String, String>? = null
            while (keys.hasNext()) {
                val k = keys.next()
                val accObj = root.optJSONObject(k) ?: continue
                val tok = accObj.optString("sessionToken").trim()
                val em = accObj.optString("email").trim().ifBlank { "firefox-user@mozilla.org" }
                if (tok.length >= 32) {
                    bestPair = tok to em
                    if (accObj.optBoolean("verified", true)) break
                }
            }
            bestPair
        }.getOrNull()
    }

    /**
     * Completes sign-in using a `localStorage["__fxa_storage.accounts"]` JSON captured from the
     * in-app Mozilla WebView sign-in page.
     */
    fun loginFromWebViewStorageJson(
        context: Context,
        rawStorageJson: String,
    ): LoginOutcome {
        val extracted = extractSessionFromWebViewStorageJson(rawStorageJson)
            ?: return LoginOutcome.Failure(
                "نشست فایرفاکس در صفحه وب پیدا نشد؛ لطفاً ورود را کامل کنید.",
                "Firefox session not found in web storage yet; please finish signing in.",
            )
        return completeSessionLogin(
            context = context,
            sessionTokenHex = extracted.first,
            email = extracted.second,
        )
    }

    /**
     * Imports an account from a raw `sessionToken` (64 hex chars), OAuth `refresh_token`,
     * `access_token`, `ProxyPass JWT` (`eyJ...`), `__fxa_storage.accounts` JSON, or `foxyvpn` JSON.
     */
    fun addAccountFromTokenOrJson(
        context: Context,
        emailOrLabel: String,
        rawInput: String,
    ): FirefoxVpnAccount? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val cleanEmail = emailOrLabel.trim().ifBlank { "firefox-${(1000..9999).random()}@mozilla.org" }

        // Check if it's a WebView __fxa_storage.accounts JSON or object containing sessionToken
        extractSessionFromWebViewStorageJson(trimmed)?.let { (sessionTok, extractedEmail) ->
            val finalEmail = if (emailOrLabel.isNotBlank()) emailOrLabel.trim() else extractedEmail
            val outcome = completeSessionLogin(context, sessionTok, finalEmail)
            if (outcome is LoginOutcome.Success) return outcome.account
        }

        // Check if it's a raw 64-character hex sessionToken
        if (trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            val outcome = completeSessionLogin(context, trimmed.lowercase(Locale.US), cleanEmail)
            if (outcome is LoginOutcome.Success) return outcome.account
        }

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
    // 3. Fastly Anti-Bot Challenge Solver (`/_fs-ch-`) & Anti-DPI HTTP Client
    // =========================================================================

    private val fastlyPrefixRegex = Regex("""/_fs-ch-[A-Za-z0-9]+""")
    private val fastlyInitRegex = Regex("""init\((\[[^\]]*\]),\s*"([^"]+)",\s*"([^"]+)"""")

    private data class RawHttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun storeCookiesFromHeaders(headers: Map<String, List<String>>) {
        headers.forEach { (k, values) ->
            if (k.equals("Set-Cookie", ignoreCase = true)) {
                values.forEach { rawCookie ->
                    val pair = rawCookie.substringBefore(';').trim()
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        val name = pair.substring(0, eqIdx).trim()
                        val value = pair.substring(eqIdx + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            fastlyCookieStore[name] = value
                        }
                    }
                }
            }
        }
    }

    private fun buildCookieHeader(): String =
        fastlyCookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * Executes an HTTPS request using an Anti-DPI TLS socket (ClientHello split at SNI boundary +
     * `VpnService.protect` + strict `http/1.1` ALPN) with automatic fallback to `HttpURLConnection`.
     */
    private fun executeAntiDpiHttpRequest(
        method: String,
        urlStr: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray? = null,
        followRedirects: Boolean = true,
        timeoutMs: Int = 18_000,
    ): RawHttpResponse {
        val cookieHeader = buildCookieHeader()
        val mergedHeaders = LinkedHashMap<String, String>()
        mergedHeaders["User-Agent"] = FIREFOX_DESKTOP_USER_AGENT
        mergedHeaders["Accept"] = "*/*"
        if (cookieHeader.isNotBlank()) {
            mergedHeaders["Cookie"] = cookieHeader
        }
        headers.forEach { (k, v) -> mergedHeaders[k] = v }

        // 1. Try Anti-DPI TLS fragmented socket first (bypasses MCI/Irancell SNI filtering on *.firefox.com / *.mozilla.org)
        val rawSocketResp = runCatching {
            executeOverFragmentedTlsSocket(
                method = method,
                urlStr = urlStr,
                headers = mergedHeaders,
                bodyBytes = bodyBytes,
                timeoutMs = timeoutMs,
            )
        }.getOrNull()

        if (rawSocketResp != null && rawSocketResp.statusCode > 0) {
            storeCookiesFromHeaders(rawSocketResp.headers)
            if (followRedirects && rawSocketResp.statusCode in listOf(301, 302, 303, 307, 308)) {
                val loc = rawSocketResp.header("Location")
                if (!loc.isNullOrBlank()) {
                    val resolvedUrl = runCatching { URI(urlStr).resolve(loc).toString() }.getOrDefault(loc)
                    return executeAntiDpiHttpRequest(
                        method = if (rawSocketResp.statusCode == 303) "GET" else method,
                        urlStr = resolvedUrl,
                        headers = headers,
                        bodyBytes = if (rawSocketResp.statusCode == 303) null else bodyBytes,
                        followRedirects = false,
                        timeoutMs = timeoutMs,
                    )
                }
            }
            return rawSocketResp
        }

        // 2. Fallback to standard HttpURLConnection
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = followRedirects
            mergedHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (bodyBytes != null && (method == "POST" || method == "PUT")) {
                doOutput = true
                outputStream.use { it.write(bodyBytes) }
            }
        }
        val code = conn.responseCode
        val respHeaders = mutableMapOf<String, List<String>>()
        conn.headerFields?.forEach { (k, v) ->
            if (k != null && v != null) respHeaders[k] = v
        }
        storeCookiesFromHeaders(respHeaders)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
        return RawHttpResponse(code, text, respHeaders)
    }

    private fun executeOverFragmentedTlsSocket(
        method: String,
        urlStr: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        timeoutMs: Int,
    ): RawHttpResponse {
        val uri = URI(urlStr)
        val host = uri.host ?: throw IOException("Missing host in $urlStr")
        val port = if (uri.port > 0) uri.port else 443
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val pathAndQuery = if (!uri.rawQuery.isNullOrEmpty()) "$rawPath?${uri.rawQuery}" else rawPath

        val raw = openSocketWithOptionalSniFragment(
            connectHost = host,
            connectPort = port,
            sniHost = host,
            fragmentTls = true,
            timeoutMs = timeoutMs,
        )
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = (sslFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
            soTimeout = timeoutMs
            val params = sslParameters
            params.serverNames = listOf(SNIHostName(host))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                params.applicationProtocols = arrayOf("http/1.1")
            }
            sslParameters = params
            startHandshake()
        }

        ssl.use { sock ->
            val out = sock.outputStream
            val reqBuilder = StringBuilder()
            reqBuilder.append("$method $pathAndQuery HTTP/1.1\r\n")
            reqBuilder.append("Host: ${if (port == 443) host else "$host:$port"}\r\n")
            headers.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Connection", ignoreCase = true)
                ) {
                    reqBuilder.append("$k: $v\r\n")
                }
            }
            if (bodyBytes != null) {
                reqBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
            } else if (method == "POST" || method == "PUT") {
                reqBuilder.append("Content-Length: 0\r\n")
            }
            reqBuilder.append("Connection: close\r\n\r\n")

            out.write(reqBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                out.write(bodyBytes)
            }
            out.flush()

            val inp = BufferedInputStream(sock.inputStream)
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = inp.read()
                if (b < 0) break
                headerBytes.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (state == 4 || headerBytes.size() > 32_768) break
            }

            val headerText = headerBytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusLine = lines.firstOrNull().orEmpty()
            val statusParts = statusLine.split(' ')
            val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: 0

            val respHeaders = LinkedHashMap<String, MutableList<String>>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val hk = line.substring(0, colon).trim()
                    val hv = line.substring(colon + 1).trim()
                    respHeaders.getOrPut(hk) { mutableListOf() }.add(hv)
                }
            }

            val bodyRawBytes = if (method.equals("HEAD", ignoreCase = true)) {
                ByteArray(0)
            } else {
                val transferEnc = respHeaders.entries
                    .firstOrNull { it.key.equals("Transfer-Encoding", ignoreCase = true) }
                    ?.value?.firstOrNull().orEmpty()
                val contentLen = respHeaders.entries
                    .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                    ?.value?.firstOrNull()?.toIntOrNull() ?: -1

                if (transferEnc.contains("chunked", ignoreCase = true)) {
                    readChunkedBody(inp)
                } else if (contentLen >= 0) {
                    val buf = ByteArray(contentLen.coerceAtMost(2 * 1024 * 1024))
                    var off = 0
                    while (off < buf.size) {
                        val r = inp.read(buf, off, buf.size - off)
                        if (r <= 0) break
                        off += r
                    }
                    if (off == buf.size) buf else buf.copyOf(off)
                } else {
                    inp.readBytes()
                }
            }

            val contentEnc = respHeaders.entries
                .firstOrNull { it.key.equals("Content-Encoding", ignoreCase = true) }
                ?.value?.firstOrNull().orEmpty()
            val decodedBytes = if (contentEnc.contains("gzip", ignoreCase = true) && bodyRawBytes.isNotEmpty()) {
                runCatching { GZIPInputStream(ByteArrayInputStream(bodyRawBytes)).readBytes() }
                    .getOrDefault(bodyRawBytes)
            } else {
                bodyRawBytes
            }

            return RawHttpResponse(
                statusCode = statusCode,
                body = decodedBytes.toString(StandardCharsets.UTF_8),
                headers = respHeaders,
            )
        }
    }

    private fun readChunkedBody(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val lineBuf = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b < 0) return out.toByteArray()
                if (b == '\n'.code) break
                if (b != '\r'.code) lineBuf.write(b)
            }
            val sizeHex = lineBuf.toByteArray().toString(StandardCharsets.US_ASCII).substringBefore(';').trim()
            if (sizeHex.isEmpty()) continue
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Consume trailing CRLF
                inp.read()
                inp.read()
                break
            }
            val chunk = ByteArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val r = inp.read(chunk, read, chunkSize - read)
                if (r <= 0) break
                read += r
            }
            out.write(chunk, 0, read)
            // Consume CRLF after chunk
            inp.read()
            inp.read()
        }
        return out.toByteArray()
    }

    /**
     * Automatically solves the Fastly Bot Protection challenge (`HTTP 406` -> `/_fs-ch-.../script.js`
     * -> `pow` / `pat` / `clientmetrics` -> `POST /fst-post-back`) on Mozilla FxA hosts.
     */
    fun solveFastlyChallenge(): Boolean = synchronized(challengeSolveLock) {
        val hosts = listOf("https://api.accounts.firefox.com", "https://accounts.firefox.com")
        for (base in hosts) {
            val solved = runCatching { solveFastlyChallengeOnHost(base) }.getOrDefault(false)
            if (solved) return true
        }
        return false
    }

    private fun solveFastlyChallengeOnHost(baseUrl: String): Boolean {
        val pageResp = executeAntiDpiHttpRequest(
            method = "GET",
            urlStr = "$baseUrl/",
            headers = mapOf(
                "User-Agent" to FASTLY_SOLVER_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
        )
        if (!pageResp.body.contains("/_fs-ch-") || !pageResp.body.contains("Client Challenge")) {
            return false
        }
        val prefix = fastlyPrefixRegex.find(pageResp.body)?.value ?: return false
        val prefixUrl = "$baseUrl$prefix"

        val scriptResp = executeAntiDpiHttpRequest(
            method = "GET",
            urlStr = "$prefixUrl/script.js?reload=true",
            headers = mapOf(
                "User-Agent" to FASTLY_SOLVER_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
        )
        if (scriptResp.statusCode != 200) return false

        val matches = fastlyInitRegex.findAll(scriptResp.body).toList()
        val lastMatch = matches.lastOrNull() ?: return false
        var challengesArr = runCatching { JSONArray(lastMatch.groupValues[1]) }.getOrNull() ?: return false
        var token = lastMatch.groupValues[2]

        for (round in 0 until 5) {
            val answers = JSONArray()
            for (i in 0 until challengesArr.length()) {
                val chObj = challengesArr.optJSONObject(i) ?: continue
                val ty = chObj.optString("ty")
                val dataObj = chObj.optJSONObject("data") ?: JSONObject()
                when (ty) {
                    "pow" -> {
                        val base = dataObj.optString("base")
                        val hash = dataObj.optString("hash")
                        val ans = solveFastlyPow(base, hash) ?: return false
                        answers.put(
                            JSONObject()
                                .put("ty", "pow")
                                .put("base", base)
                                .put("answer", ans)
                                .put("hmac", dataObj.opt("hmac"))
                                .put("expires", dataObj.opt("expires")),
                        )
                    }
                    "pat" -> {
                        val encodedTok = URLEncoder.encode(token, "UTF-8")
                        val patResp = runCatching {
                            executeAntiDpiHttpRequest(
                                method = "POST",
                                urlStr = "$prefixUrl/pat?token=$encodedTok",
                                headers = mapOf(
                                    "User-Agent" to FASTLY_SOLVER_USER_AGENT,
                                    "Accept" to "text/plain",
                                    "Content-Type" to "application/json",
                                    "Origin" to baseUrl,
                                ),
                                bodyBytes = ByteArray(0),
                            )
                        }.getOrNull()
                        val patAuth = if (patResp != null && patResp.statusCode == 200) {
                            runCatching { JSONObject(patResp.body).optString("auth") }.getOrDefault("")
                        } else {
                            // 400 or 401 triggers PoW fallback on next post-back round
                            ""
                        }
                        answers.put(
                            JSONObject()
                                .put("ty", "pat")
                                .put("auth", patAuth),
                        )
                    }
                    "clientmetrics" -> {
                        answers.put(
                            JSONObject()
                                .put("ty", "clientmetrics")
                                .put("webdriver", false)
                                .put(
                                    "bot_detection_result",
                                    JSONObject().put("bot_detected", false).put("bot_kind", JSONObject.NULL),
                                )
                                .put(
                                    "browser_metrics",
                                    JSONObject().put("client_data", "{}").put("error_trace", JSONObject.NULL),
                                )
                                .put("detector_results", JSONObject())
                                .put("v", 2),
                        )
                    }
                    else -> return false
                }
            }

            val postBackBody = JSONObject()
                .put("token", token)
                .put("data", answers)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            val pbResp = executeAntiDpiHttpRequest(
                method = "POST",
                urlStr = "$prefixUrl/fst-post-back",
                headers = mapOf(
                    "User-Agent" to FASTLY_SOLVER_USER_AGENT,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Origin" to baseUrl,
                ),
                bodyBytes = postBackBody,
            )
            if (pbResp.statusCode !in 200..299) return false
            val pbJson = runCatching { JSONObject(pbResp.body) }.getOrNull() ?: return false
            if (pbJson.optString("status") == "success") {
                // Probe page once to ensure challenge cookie is committed
                executeAntiDpiHttpRequest(
                    method = "GET",
                    urlStr = "$baseUrl/",
                    headers = mapOf("User-Agent" to FASTLY_SOLVER_USER_AGENT),
                )
                return true
            }
            val nextCh = pbJson.optJSONArray("ch")
            val nextTok = pbJson.optString("tok")
            if (nextCh == null || nextCh.length() == 0 || nextTok.isBlank()) return false
            challengesArr = nextCh
            token = nextTok
        }
        return false
    }

    /**
     * Performs an FxA or Guardian API call and transparently solves Fastly `HTTP 406` challenges.
     */
    private fun fxaDoWithChallengeRetry(
        method: String,
        url: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray? = null,
        followRedirects: Boolean = true,
    ): RawHttpResponse {
        for (attempt in 1..4) {
            val resp = executeAntiDpiHttpRequest(
                method = method,
                url = url,
                headers = headers,
                bodyBytes = bodyBytes,
                followRedirects = followRedirects,
            )
            if (resp.statusCode != 406) {
                return resp
            }
            solveFastlyChallenge()
        }
        return executeAntiDpiHttpRequest(
            method = method,
            url = url,
            headers = headers,
            bodyBytes = bodyBytes,
            followRedirects = followRedirects,
        )
    }

    private fun executeAntiDpiHttpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
    ): RawHttpResponse = executeAntiDpiHttpRequest(
        method = method,
        urlStr = url,
        headers = headers,
        bodyBytes = bodyBytes,
        followRedirects = followRedirects,
        timeoutMs = 18_000,
    )

    // =========================================================================
    // 4. FxA Sign-In, 2FA / TOTP / Unblock Code & Guardian Provisioning
    // =========================================================================

    fun loginWithEmailAndPassword(
        context: Context,
        email: String,
        password: String,
        unblockCode: String = "",
    ): LoginOutcome {
        val typedEmail = normalizeEmail(email, preserveCase = true)
        if (typedEmail.isEmpty() || !typedEmail.contains("@") || password.isEmpty()) {
            return LoginOutcome.Failure(
                "ایمیل و رمز عبور حساب فایرفاکس را کامل وارد کنید.",
                "Please enter both your Firefox Account email and password.",
            )
        }

        return runCatching {
            val firstAttempt = fxaLoginAs(
                email = typedEmail.lowercase(Locale.US),
                originalEmail = "",
                password = password,
                unblockCode = normalizeCode(unblockCode).uppercase(Locale.US),
            )
            val finalAttempt = if (firstAttempt.statusCode >= 400) {
                val errObj = runCatching { JSONObject(firstAttempt.body) }.getOrNull()
                val errno = errObj?.optInt("errno", 0) ?: 0
                val serverEmail = errObj?.optString("email").orEmpty().trim()
                // FxA errno 120: Incorrect email case — re-derive authPW with server's exact email spelling
                if (errno == ERRNO_INCORRECT_EMAIL_CASE && serverEmail.isNotEmpty()) {
                    fxaLoginAs(
                        email = serverEmail,
                        originalEmail = typedEmail,
                        password = password,
                        unblockCode = normalizeCode(unblockCode).uppercase(Locale.US),
                    )
                } else {
                    firstAttempt
                }
            } else {
                firstAttempt
            }

            val effectiveEmail = runCatching {
                JSONObject(finalAttempt.body).optString("email").takeIf { it.isNotBlank() }
            }.getOrNull() ?: typedEmail.lowercase(Locale.US)

            if (finalAttempt.statusCode !in 200..299) {
                val errObj = runCatching { JSONObject(finalAttempt.body) }.getOrNull()
                val errno = errObj?.optInt("errno", 0) ?: 0
                val errMsg = errObj?.optString("message")?.ifBlank { finalAttempt.body } ?: finalAttempt.body

                // FxA errno 125: Unusual sign-in blocked -> automatically request unblock code to email
                if (errno == ERRNO_REQUEST_BLOCKED) {
                    sendUnblockCode(effectiveEmail)
                    return@runCatching LoginOutcome.NeedsUnblockCode(
                        email = effectiveEmail,
                        password = password,
                    )
                }

                val friendlyFa = when (errno) {
                    102 -> "حساب فایرفاکسی با این ایمیل پیدا نشد. می‌توانید با دکمه «ورود با صفحه رسمی موزیلا / گوگل» حساب بسازید یا وارد شوید."
                    103 -> "رمز عبور واردشده اشتباه است (یا این حساب با «ادامه با گوگل» ساخته شده و رمز مجزا ندارد؛ از دکمه «🌐 ورود با صفحه وب موزیلا» استفاده کنید)."
                    114 -> "تعداد تلاش‌ها زیاد بوده است؛ چند دقیقه صبر کنید یا با «🌐 ورود با صفحه وب موزیلا» وارد شوید."
                    else -> if (finalAttempt.statusCode == 406) {
                        "چالش امنیتی Fastly نیاز به مرورگر دارد؛ روی دکمه «🌐 ورود آسان با صفحه رسمی موزیلا (WebView)» بزنید."
                    } else {
                        "خطا در ورود به حساب فایرفاکس ($errno): $errMsg"
                    }
                }
                return@runCatching LoginOutcome.Failure(
                    friendlyFa,
                    "Firefox Account sign-in failed (HTTP ${finalAttempt.statusCode}, errno $errno): $errMsg",
                )
            }

            val respJson = JSONObject(finalAttempt.body)
            val sessionToken = respJson.optString("sessionToken")
            val verified = respJson.optBoolean("verified", true)
            val verificationMethod = respJson.optString("verificationMethod", "email-2fa")
            if (sessionToken.isBlank()) {
                return@runCatching LoginOutcome.Failure(
                    "توکن نشست از سرور موزیلا دریافت نشد.",
                    "Mozilla FxA did not return a sessionToken.",
                )
            }

            if (!verified) {
                return@runCatching LoginOutcome.NeedsEmailVerification(
                    sessionTokenHex = sessionToken,
                    email = effectiveEmail,
                    verificationMethod = verificationMethod,
                )
            }

            completeSessionLogin(context, sessionToken, effectiveEmail)
        }.getOrElse { err ->
            LoginOutcome.Failure(
                "خطای ارتباط با سرور فایرفاکس: ${err.message} — پیشنهاد: از دکمه «🌐 ورود آسان با صفحه رسمی موزیلا» استفاده کنید.",
                "Network error contacting Firefox Accounts: ${err.message}",
            )
        }
    }

    private fun fxaLoginAs(
        email: String,
        originalEmail: String,
        password: String,
        unblockCode: String,
    ): RawHttpResponse {
        val respWith2fa = fxaLoginAttempt(
            email = email,
            originalEmail = originalEmail,
            password = password,
            verificationMethod = "email-2fa",
            unblockCode = unblockCode,
        )
        if (respWith2fa.statusCode >= 400) {
            val errno = runCatching { JSONObject(respWith2fa.body).optInt("errno", 0) }.getOrDefault(0)
            if (errno == ERRNO_INVALID_PARAMETER) {
                return fxaLoginAttempt(
                    email = email,
                    originalEmail = originalEmail,
                    password = password,
                    verificationMethod = "",
                    unblockCode = unblockCode,
                )
            }
        }
        return respWith2fa
    }

    private fun fxaLoginAttempt(
        email: String,
        originalEmail: String,
        password: String,
        verificationMethod: String,
        unblockCode: String,
    ): RawHttpResponse {
        val authPw = deriveAuthPw(email, password, preserveEmailCase = originalEmail.isNotBlank())
        val payload = JSONObject()
            .put("email", email)
            .put("authPW", authPw)
        if (originalEmail.isNotBlank()) payload.put("originalLoginEmail", originalEmail)
        if (verificationMethod.isNotBlank()) payload.put("verificationMethod", verificationMethod)
        if (unblockCode.isNotBlank()) payload.put("unblockCode", unblockCode)

        val bodyBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        return fxaDoWithChallengeRetry(
            method = "POST",
            url = "$FXA_AUTH_SERVER/account/login",
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = bodyBytes,
        )
    }

    fun sendUnblockCode(email: String): Boolean {
        val bodyBytes = JSONObject().put("email", email.trim()).toString().toByteArray(StandardCharsets.UTF_8)
        val resp = runCatching {
            fxaDoWithChallengeRetry(
                method = "POST",
                url = "$FXA_AUTH_SERVER/account/login/send_unblock_code",
                headers = mapOf("Content-Type" to "application/json"),
                bodyBytes = bodyBytes,
            )
        }.getOrNull()
        return resp != null && resp.statusCode in 200..299
    }

    /**
     * Completes 2FA verification (supports both `email-2fa` emailed codes and `totp-2fa`
     * authenticator app codes) and exchanges the session token for an OAuth refresh token + 50 GB ProxyPass.
     */
    fun verifyEmail2faAndCompleteLogin(
        context: Context,
        sessionTokenHex: String,
        code: String,
        email: String,
        verificationMethod: String = "email-2fa",
    ): LoginOutcome {
        val cleanCode = normalizeCode(code)
        if (cleanCode.isEmpty()) {
            return LoginOutcome.Failure("کد تایید را وارد کنید.", "Please enter the verification code.")
        }
        return runCatching {
            val isTotp = verificationMethod.equals("totp-2fa", ignoreCase = true)
            val verifyUrl = if (isTotp) {
                "$FXA_AUTH_SERVER/session/verify/totp"
            } else {
                "$FXA_AUTH_SERVER/session/verify_code"
            }
            val payload = JSONObject().put("code", cleanCode).toString()
            val verifyResp = fxaPostHawkJson(
                url = verifyUrl,
                sessionTokenHex = sessionTokenHex,
                bodyJson = payload,
            )
            if (verifyResp.statusCode !in 200..299) {
                return@runCatching LoginOutcome.Failure(
                    "کد تایید نامعتبر است یا منقضی شده است: ${verifyResp.body}",
                    "2FA verification failed: ${verifyResp.body}",
                )
            }
            if (isTotp) {
                val ok = runCatching { JSONObject(verifyResp.body).optBoolean("success", true) }.getOrDefault(true)
                if (!ok) {
                    return@runCatching LoginOutcome.Failure(
                        "کد برنامه Authenticator پذیرفته نشد.",
                        "Authenticator TOTP code was not accepted.",
                    )
                }
            }
            completeSessionLogin(context, sessionTokenHex, email)
        }.getOrElse { err ->
            LoginOutcome.Failure(
                "خطا در تایید کد دو مرحله‌ای: ${err.message}",
                "Error verifying 2FA code: ${err.message}",
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun completeSessionLogin(
        context: Context,
        sessionTokenHex: String,
        email: String,
    ): LoginOutcome {
        return runCatching {
            // Method A: Direct fxa-credentials OAuth token exchange
            val oauthPayload = JSONObject()
                .put("client_id", FIREFOX_CLIENT_ID)
                .put("grant_type", "fxa-credentials")
                .put("scope", OAUTH_SCOPE)
                .put("access_type", "offline")
                .toString()

            var oauthResp = fxaPostHawkJson(
                url = "$FXA_AUTH_SERVER/oauth/token",
                sessionTokenHex = sessionTokenHex,
                bodyJson = oauthPayload,
            )

            // Method B fallback: PKCE /oauth/authorization -> /oauth/token (from ios151/firefox)
            if (oauthResp.statusCode !in 200..299) {
                val pkceResp = exchangeSessionTokenViaPkce(sessionTokenHex)
                if (pkceResp != null) {
                    oauthResp = pkceResp
                }
            }

            if (oauthResp.statusCode !in 200..299) {
                return@runCatching LoginOutcome.Failure(
                    "خطا در دریافت توکن OAuth موزیلا (${oauthResp.statusCode}): ${oauthResp.body}",
                    "Failed to obtain OAuth token (${oauthResp.statusCode}): ${oauthResp.body}",
                )
            }

            val oauthJson = JSONObject(oauthResp.body)
            val accessToken = oauthJson.optString("access_token")
            val refreshToken = oauthJson.optString("refresh_token")

            if (accessToken.isBlank()) {
                return@runCatching LoginOutcome.Failure(
                    "توکن دسترسی (access_token) در پاسخ موزیلا نبود.",
                    "Missing access_token in OAuth response.",
                )
            }

            // Ensure account is enrolled in Guardian IP Protection before destroying sessionToken
            runCatching { enrollAccountInGuardianIfNeeded(sessionTokenHex, accessToken) }

            // Clean up temporary sign-in session on FxA server (best effort)
            runCatching {
                fxaPostHawkJson(
                    url = "$FXA_AUTH_SERVER/session/destroy",
                    sessionTokenHex = sessionTokenHex,
                    bodyJson = "{}",
                )
            }

            val baseAccount = FirefoxVpnAccount(
                email = email.ifBlank { "firefox-user@mozilla.org" },
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
            val activated = runCatching {
                refreshAndActivateAccountPass(context, baseAccount)
            }.getOrDefault(baseAccount)
            upsertAccount(context, activated)
            LoginOutcome.Success(activated)
        }.getOrElse { err ->
            LoginOutcome.Failure(
                "خطا در تکمیل ورود فایرفاکس: ${err.message}",
                "Failed completing Firefox login: ${err.message}",
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun exchangeSessionTokenViaPkce(sessionTokenHex: String): RawHttpResponse? = runCatching {
        val rndVerifier = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.UrlSafe.encode(rndVerifier).trimEnd('=')
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8))
        val challenge = Base64.UrlSafe.encode(challengeBytes).trimEnd('=')
        val rndState = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val state = Base64.UrlSafe.encode(rndState).trimEnd('=')

        val authBody = JSONObject()
            .put("client_id", FIREFOX_CLIENT_ID)
            .put("response_type", "code")
            .put("scope", OAUTH_SCOPE)
            .put("state", state)
            .put("code_challenge", challenge)
            .put("code_challenge_method", "S256")
            .put("access_type", "offline")
            .toString()

        val authResp = fxaPostHawkJson(
            url = "$FXA_AUTH_SERVER/oauth/authorization",
            sessionTokenHex = sessionTokenHex,
            bodyJson = authBody,
        )
        if (authResp.statusCode !in 200..299) return@runCatching null
        val code = JSONObject(authResp.body).optString("code")
        if (code.isBlank()) return@runCatching null

        val tradeBody = JSONObject()
            .put("client_id", FIREFOX_CLIENT_ID)
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", verifier)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        fxaDoWithChallengeRetry(
            method = "POST",
            url = "$FXA_OAUTH_SERVER/token",
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = tradeBody,
        )
    }.getOrNull()

    /**
     * Enrolls a newly created Firefox Account into Mozilla IP Protection (`experiment=ip-protection`)
     * via both `POST /api/v1/fpn/activate` and the Guardian OAuth enrollment redirect flow.
     */
    private fun enrollAccountInGuardianIfNeeded(sessionTokenHex: String, accessToken: String) {
        // 1. Try direct Guardian activate endpoint
        val activateResp = guardianRequest("POST", "/api/v1/fpn/activate", accessToken, "{}")
        if (activateResp.statusCode in 200..299) return

        // 2. Try full Guardian OAuth enrollment flow (/api/v1/fpn/auth?experiment=ip-protection)
        val startResp = fxaDoWithChallengeRetry(
            method = "GET",
            url = "$GUARDIAN_ENDPOINT/api/v1/fpn/auth?experiment=ip-protection",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            followRedirects = false,
        )
        val location = startResp.header("Location") ?: return
        val locUri = runCatching { URI(location) }.getOrNull() ?: return
        val queryMap = locUri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            } else null
        }.toMap()

        val clientId = queryMap["client_id"] ?: return
        val scope = queryMap["scope"] ?: return
        val state = queryMap["state"] ?: return
        val redirectUri = queryMap["redirect_uri"] ?: return

        val authPayload = JSONObject()
            .put("client_id", clientId)
            .put("response_type", "code")
            .put("scope", scope)
            .put("state", state)
            .put("redirect_uri", redirectUri)
            .put("access_type", queryMap["access_type"] ?: "offline")
            .toString()

        val authzResp = fxaPostHawkJson(
            url = "$FXA_AUTH_SERVER/oauth/authorization",
            sessionTokenHex = sessionTokenHex,
            bodyJson = authPayload,
        )
        if (authzResp.statusCode !in 200..299) return
        val authzJson = JSONObject(authzResp.body)
        val code = authzJson.optString("code")
        val callbackUrl = authzJson.optString("redirect").takeIf { it.contains("code=") } ?: run {
            val sep = if (redirectUri.contains("?")) "&" else "?"
            "$redirectUri${sep}code=${URLEncoder.encode(code, "UTF-8")}&state=${URLEncoder.encode(state, "UTF-8")}"
        }
        fxaDoWithChallengeRetry(
            method = "GET",
            url = callbackUrl,
            headers = emptyMap(),
            followRedirects = false,
        )
    }

    /**
     * Refreshes OAuth access token (if needed), activates Guardian 50 GB free entitlement,
     * and fetches a fresh `ProxyPass` JWT (`GET /api/v1/fpn/token`) along with `X-Quota-*` headers.
     */
    fun refreshAndActivateAccountPass(
        context: Context,
        account: FirefoxVpnAccount,
    ): FirefoxVpnAccount {
        var currentAccess = account.accessToken
        var currentRefresh = account.refreshToken

        // 1. Refresh OAuth access_token if refresh_token is available
        if (currentRefresh.isNotBlank()) {
            val refreshBody = JSONObject()
                .put("client_id", FIREFOX_CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", currentRefresh)
                .put("scope", OAUTH_SCOPE)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            val r = runCatching {
                fxaDoWithChallengeRetry(
                    method = "POST",
                    url = "$FXA_AUTH_SERVER/oauth/token",
                    headers = mapOf("Content-Type" to "application/json"),
                    bodyBytes = refreshBody,
                )
            }.getOrNull()
            if (r != null && r.statusCode in 200..299) {
                val j = JSONObject(r.body)
                val newAccess = j.optString("access_token")
                val newRefresh = j.optString("refresh_token")
                if (newAccess.isNotBlank()) currentAccess = newAccess
                if (newRefresh.isNotBlank()) currentRefresh = newRefresh
            }
        }

        if (currentAccess.isBlank()) {
            return account
        }

        // 2. Fetch ProxyPass JWT & Quota Headers: GET https://vpn.mozilla.org/api/v1/fpn/token
        var tokenResp = runCatching {
            guardianRequest("GET", "/api/v1/fpn/token", currentAccess, null)
        }.getOrNull()

        // If HTTP 403 (not entitled yet), activate entitlement and retry
        if (tokenResp != null && (tokenResp.statusCode == 403 || tokenResp.statusCode == 401)) {
            runCatching {
                guardianRequest("POST", "/api/v1/fpn/activate", currentAccess, "{}")
            }
            tokenResp = runCatching {
                guardianRequest("GET", "/api/v1/fpn/token", currentAccess, null)
            }.getOrNull()
        }

        if (tokenResp != null && tokenResp.statusCode in 200..299) {
            val j = JSONObject(tokenResp.body)
            val passJwt = j.optString("token")
            val expSec = j.optLong("expires_at", 0L).takeIf { it > 0L }
                ?: extractJwtExpirySeconds(passJwt)
                ?: ((System.currentTimeMillis() / 1000L) + 3600L)

            val quotaLimit = tokenResp.header("X-Quota-Limit")?.toLongOrNull()
                ?.takeIf { it > 0L } ?: account.quotaLimitBytes
            val quotaRem = tokenResp.header("X-Quota-Remaining")?.toLongOrNull()
                ?: account.quotaRemainingBytes
            val quotaReset = tokenResp.header("X-Quota-Reset")?.toLongOrNull()
                ?: account.quotaResetEpochSec

            val updated = account.copy(
                accessToken = currentAccess,
                refreshToken = currentRefresh,
                proxyPassToken = passJwt.ifBlank { account.proxyPassToken },
                proxyPassExpiresAtSec = expSec,
                quotaLimitBytes = quotaLimit,
                quotaRemainingBytes = quotaRem,
                localUsedBytes = 0L,
                quotaResetEpochSec = quotaReset,
            )
            upsertAccount(context, updated)
            return updated
        }

        return account.copy(accessToken = currentAccess, refreshToken = currentRefresh)
    }

    // =========================================================================
    // 5. Mozilla Remote Settings Country Sync & Live Ping Scanner
    // =========================================================================

    fun getAvailableCountries(context: Context): List<FirefoxVpnCountry> {
        val cachedJson = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_COUNTRIES, null)
        if (cachedJson.isNullOrBlank()) return BUILTIN_COUNTRIES
        return runCatching {
            val arr = JSONArray(cachedJson)
            val remoteList = mutableListOf<FirefoxVpnCountry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                remoteList += FirefoxVpnCountry(
                    code = o.optString("code"),
                    flag = o.optString("flag"),
                    nameFa = o.optString("nameFa"),
                    nameEn = o.optString("nameEn"),
                    cityName = o.optString("cityName"),
                    proxyHost = o.optString("proxyHost"),
                    proxyPort = o.optInt("proxyPort", 443),
                    masqueTemplate = o.optString("masqueTemplate"),
                )
            }
            if (remoteList.isEmpty()) BUILTIN_COUNTRIES else remoteList
        }.getOrDefault(BUILTIN_COUNTRIES)
    }

    /**
     * Fetches the live server catalog from Mozilla Remote Settings (`vpn-serverlist`)
     * and merges it with [BUILTIN_COUNTRIES] to preserve Persian titles and flags.
     */
    fun syncRemoteSettingsCountries(context: Context): List<FirefoxVpnCountry> {
        val merged = runCatching {
            val resp = executeAntiDpiHttpRequest(
                method = "GET",
                urlStr = REMOTE_SETTINGS_URL,
                headers = mapOf("Accept" to "application/json"),
                timeoutMs = 10_000,
            )
            if (resp.statusCode !in 200..299) return@runCatching BUILTIN_COUNTRIES
            val root = JSONObject(resp.body)
            val dataArr = root.optJSONArray("data") ?: return@runCatching BUILTIN_COUNTRIES
            val builtinByCode = BUILTIN_COUNTRIES.associateBy { it.code.uppercase(Locale.US) }
            val result = mutableListOf<FirefoxVpnCountry>()
            result += BUILTIN_COUNTRIES.first() // Keep REC at index 0

            for (i in 0 until dataArr.length()) {
                val rec = dataArr.optJSONObject(i) ?: continue
                val code = rec.optString("code").trim().uppercase(Locale.US)
                if (code.isBlank() || code == "REC") continue
                val nameEn = rec.optString("name").ifBlank { code }
                val cities = rec.optJSONArray("cities")
                val firstCity = cities?.optJSONObject(0)
                val cityName = firstCity?.optString("name").orEmpty()
                val servers = firstCity?.optJSONArray("servers")
                val firstSrv = servers?.optJSONObject(0)
                val host = firstSrv?.optString("hostname")
                    ?.takeIf { it.isNotBlank() }
                    ?: "proxy-${code.lowercase(Locale.US)}.fpn.mozilla.net"
                val port = firstSrv?.optInt("port", 443)?.takeIf { it > 0 } ?: 443
                val existing = builtinByCode[code]
                result += FirefoxVpnCountry(
                    code = code,
                    flag = existing?.flag ?: countryCodeToFlagEmoji(code),
                    nameFa = existing?.nameFa ?: "$nameEn ($code)",
                    nameEn = existing?.nameEn ?: "$nameEn ($cityName)".trimEnd(' ', '(', ')'),
                    cityName = cityName.ifBlank { existing?.cityName.orEmpty() },
                    proxyHost = host,
                    proxyPort = port,
                )
            }
            BUILTIN_COUNTRIES.forEach { b ->
                if (result.none { it.code.equals(b.code, ignoreCase = true) }) {
                    result += b
                }
            }
            val saveArr = JSONArray()
            result.forEach { c ->
                saveArr.put(
                    JSONObject()
                        .put("code", c.code)
                        .put("flag", c.flag)
                        .put("nameFa", c.nameFa)
                        .put("nameEn", c.nameEn)
                        .put("cityName", c.cityName)
                        .put("proxyHost", c.proxyHost)
                        .put("proxyPort", c.proxyPort)
                        .put("masqueTemplate", c.masqueTemplate),
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_COUNTRIES, saveArr.toString())
                .apply()
            result
        }.getOrDefault(BUILTIN_COUNTRIES)
        return merged
    }

    /**
     * Pings Firefox VPN country endpoints (and clean Fastly edge IPs) to rank countries by live latency.
     */
    fun pingCountries(
        context: Context,
        maxCountries: Int = 12,
        timeoutMs: Int = 900,
    ): List<CountryPingResult> {
        val countries = getAvailableCountries(context).take(maxCountries)
        val cleanIp = readCleanFastlyEdgeIp(context)
        val results = countries.map { country ->
            val targetHost = cleanIp.ifBlank { country.proxyHost }
            val rtt = probeTcpLatency(targetHost, country.proxyPort, timeoutMs)
            CountryPingResult(
                country = country,
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
     * Scans [CLEAN_FASTLY_EDGE_IPS] on port 443 to find the fastest unblocked Fastly edge IP on the user's ISP.
     */
    fun findBestCleanFastlyEdgeIp(timeoutMs: Int = 900): Pair<String, Long>? {
        var best: Pair<String, Long>? = null
        for (ip in CLEAN_FASTLY_EDGE_IPS) {
            val rtt = probeTcpLatency(ip, 443, timeoutMs) ?: continue
            if (best == null || rtt < best.second) {
                best = ip to rtt
            }
        }
        return best
    }

    private fun probeTcpLatency(host: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val t0 = System.nanoTime()
            Socket().use { sock ->
                protectOutboundSocket(sock)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    // =========================================================================
    // 6. Local Anti-DPI Firefox VPN Proxy (`127.0.0.1:10829`) -> Fastly CONNECT
    // =========================================================================

    @Volatile
    private var localProxyServer: ServerSocket? = null
    private val localProxyRunning = AtomicBoolean(false)
    private val activeLocalPort = AtomicInteger(DEFAULT_LOCAL_PROXY_PORT)
    private val sessionTransferredBytes = AtomicLong(0L)

    // Bounded thread pool with small 256KB stacks so pthread_create can NEVER exhaust memory
    private val fenixPoolSeq = AtomicInteger(0)
    private val fenixWorkerPool: ThreadPoolExecutor = ThreadPoolExecutor(
        4,
        40,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
        ThreadFactory { runnable ->
            Thread(null, runnable, "fenix-pool-${fenixPoolSeq.incrementAndGet()}", 256 * 1024L).apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.DiscardPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    // Hard cap on concurrent upstream tunnels
    private val activeTunnelSemaphore = Semaphore(24)

    @Volatile
    private var activeUpstreamHost: String = "proxy-de.fpn.mozilla.net"
    @Volatile
    private var activeUpstreamPort: Int = 443
    @Volatile
    private var activeCleanEdgeIp: String = ""
    @Volatile
    private var activeBearerPass: String = ""
    @Volatile
    private var activeFragmentTls: Boolean = true
    @Volatile
    private var appContextRef: Context? = null

    fun isLocalProxyRunning(): Boolean =
        localProxyRunning.get() && localProxyServer?.isClosed == false

    fun currentLocalProxyPort(): Int = activeLocalPort.get()

    fun activeProxyPort(): Int = activeLocalPort.get()

    /**
     * Starts or updates the local Firefox VPN (`FENIX`) bridge on `127.0.0.1:10829`.
     * Accepts both `SOCKS5` and `HTTP CONNECT` from Mihomo and tunnels traffic over
     * TLS (with optional ClientHello SNI Record Splitting) to Mozilla's Fastly Edge proxy.
     */
    fun startOrUpdateLocalFenixProxy(
        context: Context? = null,
        country: FirefoxVpnCountry,
        bearerPass: String,
        cleanEdgeIp: String = "",
        fragmentTls: Boolean = true,
        preferredPort: Int = DEFAULT_LOCAL_PROXY_PORT,
    ): Int {
        if (context != null) {
            appContextRef = context.applicationContext
        }
        activeUpstreamHost = country.proxyHost
        activeUpstreamPort = country.proxyPort
        activeCleanEdgeIp = cleanEdgeIp.trim()
        activeBearerPass = bearerPass.trim()
        activeFragmentTls = fragmentTls

        val existing = localProxyServer
        if (existing != null && !existing.isClosed && localProxyRunning.get()) {
            return existing.localPort
        }

        return runCatching {
            val server = try {
                ServerSocket(preferredPort, 64, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            }
            localProxyServer = server
            activeLocalPort.set(server.localPort)
            localProxyRunning.set(true)

            Thread(null, Runnable {
                while (!server.isClosed && localProxyRunning.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        fenixWorkerPool.execute {
                            handleFenixClient(client)
                        }
                    }.onFailure {
                        runCatching { client.close() }
                    }
                }
            }, "fenix-vpn-accept-${server.localPort}", 256 * 1024L).apply {
                isDaemon = true
                start()
            }
            server.localPort
        }.getOrDefault(preferredPort)
    }

    fun stopLocalFenixProxy() {
        localProxyRunning.set(false)
        runCatching { localProxyServer?.close() }
        localProxyServer = null
    }

    private fun isLoopbackOrSelfTarget(host: String, port: Int): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "0.0.0.0" || h.startsWith("127.")) {
            return true
        }
        if (port == activeLocalPort.get()) {
            return true
        }
        return false
    }

    private fun handleFenixClient(client: Socket) {
        if (!activeTunnelSemaphore.tryAcquire()) {
            runCatching { client.close() }
            return
        }
        try {
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
                        val parts = reqLine.split(' ')
                        if (parts.size < 2) return@use
                        val hostPort = parts[1].removePrefix("http://").removePrefix("https://").substringBefore('/')
                        // Drain remaining HTTP request headers
                        while (true) {
                            val line = readAsciiLine(inp) ?: break
                            if (line.isEmpty()) break
                        }
                        val colonIdx = hostPort.lastIndexOf(':')
                        if (colonIdx > 0) {
                            targetHost = hostPort.substring(0, colonIdx).removePrefix("[").removeSuffix("]")
                            targetPort = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443
                        } else {
                            targetHost = hostPort
                            targetPort = 443
                        }
                    }

                    // Strict loopback guard: never connect back to 127.0.0.1 / localhost
                    if (isLoopbackOrSelfTarget(targetHost, targetPort)) {
                        return@use
                    }

                    val upstreamStream = openUpstreamTunnelStream(targetHost, targetPort)
                    try {
                        if (isSocks5) {
                            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            out.flush()
                        } else {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                        }

                        val upIn = upstreamStream.getInputStream()
                        val upOut = upstreamStream.getOutputStream()

                        fenixWorkerPool.execute {
                            runCatching {
                                pipeBytesWithAccounting(inp, upOut)
                            }
                            runCatching { upstreamStream.close() }
                        }
                        pipeBytesWithAccounting(upIn, out)
                    } finally {
                        runCatching { upstreamStream.close() }
                    }
                }
            }
        } finally {
            activeTunnelSemaphore.release()
        }
    }

    /**
     * Opens a TLS connection to the Mozilla Fastly proxy endpoint (splitting the TLS ClientHello
     * at the SNI boundary when enabled and protecting the socket with `VpnService.protect`) and
     * issues `CONNECT targetHost:targetPort HTTP/1.1` with `Proxy-Authorization: Bearer <ProxyPass>`.
     */
    private fun openUpstreamTunnelStream(targetHost: String, targetPort: Int): Socket {
        if (isLoopbackOrSelfTarget(targetHost, targetPort)) {
            throw IOException("Blocked loopback target $targetHost:$targetPort")
        }

        val sniHost = activeUpstreamHost
        var pass = ensureValidProxyPass()
        if (pass.isBlank()) {
            throw IOException("Firefox VPN account ProxyPass token is missing; please sign in to Firefox Account first")
        }

        // Candidate Fastly endpoints: primary port (443), alternate Fastly port (2499), and clean Fastly edge IP
        val candidateEndpoints = buildList {
            val primaryHost = activeCleanEdgeIp.ifBlank { activeUpstreamHost }
            add(primaryHost to activeUpstreamPort)
            if (activeUpstreamPort != 2499) {
                add(primaryHost to 2499)
            }
            if (activeCleanEdgeIp.isBlank()) {
                add(CLEAN_FASTLY_EDGE_IPS.first() to 443)
            }
        }.distinct()

        var lastError: Throwable? = null
        for ((connectHost, connectPort) in candidateEndpoints) {
            try {
                val raw = openSocketWithOptionalSniFragment(
                    connectHost = connectHost,
                    connectPort = connectPort,
                    sniHost = sniHost,
                    fragmentTls = activeFragmentTls,
                    timeoutMs = 10_000,
                )
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = (sslFactory.createSocket(raw, sniHost, connectPort, true) as SSLSocket).apply {
                    soTimeout = 20_000
                    val params = sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    // CRITICAL: Must only offer http/1.1 because we send an HTTP/1.1 CONNECT line!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        params.applicationProtocols = arrayOf("http/1.1")
                    }
                    sslParameters = params
                    startHandshake()
                }

                val authority = if (targetHost.contains(':') && !targetHost.startsWith("[")) {
                    "[$targetHost]:$targetPort"
                } else {
                    "$targetHost:$targetPort"
                }
                val connectReq = buildString {
                    append("CONNECT $authority HTTP/1.1\r\n")
                    append("Host: $authority\r\n")
                    append("Proxy-Authorization: Bearer $pass\r\n")
                    append("User-Agent: $FIREFOX_DESKTOP_USER_AGENT\r\n")
                    append("Proxy-Connection: keep-alive\r\n\r\n")
                }
                val sslOut = ssl.outputStream
                val sslIn = ssl.inputStream
                sslOut.write(connectReq.toByteArray(StandardCharsets.UTF_8))
                sslOut.flush()

                val statusLine = readAsciiLine(sslIn) ?: run {
                    ssl.close()
                    throw IOException("Empty CONNECT response from $connectHost:$connectPort")
                }
                while (true) {
                    val hdr = readAsciiLine(sslIn) ?: break
                    if (hdr.isEmpty()) break
                }
                if (statusLine.contains(" 200 ")) {
                    return ssl
                }
                ssl.close()
                // If 401/407/429, rotate or refresh account pass once
                if (statusLine.contains(" 401 ") || statusLine.contains(" 407 ") || statusLine.contains(" 429 ")) {
                    pass = rotateOrRefreshAccountPassOnFailure()
                }
                lastError = IOException("Fastly node $connectHost:$connectPort returned $statusLine")
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw (lastError ?: IOException("Failed connecting to Fastly proxy nodes"))
    }

    /**
     * Ensures the active account has a non-expired `ProxyPass` token, or refreshes/rotates it.
     */
    private fun ensureValidProxyPass(): String {
        val current = activeBearerPass
        val exp = extractJwtExpirySeconds(current)
        val nowSec = System.currentTimeMillis() / 1000L
        if (current.isNotBlank() && (exp == null || exp > nowSec + 60L)) {
            return current
        }
        val ctx = appContextRef ?: return current
        val accounts = loadAccounts(ctx)
        val best = selectBestActiveAccount(accounts) ?: return current
        val refreshed = runCatching { refreshAndActivateAccountPass(ctx, best) }.getOrDefault(best)
        if (refreshed.proxyPassToken.isNotBlank()) {
            activeBearerPass = refreshed.proxyPassToken
            return refreshed.proxyPassToken
        }
        return current
    }

    /**
     * Called when Fastly returns HTTP 401/429 (quota exhausted or token expired): rotates to the
     * next enabled Firefox Account in the pool and updates [activeBearerPass].
     */
    private fun rotateOrRefreshAccountPassOnFailure(): String {
        val ctx = appContextRef ?: return activeBearerPass
        val accounts = loadAccounts(ctx)
        if (accounts.isEmpty()) return activeBearerPass
        val nextCandidate = accounts.firstOrNull {
            it.enabled && it.proxyPassToken != activeBearerPass && !it.isQuotaExhaustedOrNearLimit
        } ?: selectBestActiveAccount(accounts) ?: return activeBearerPass

        val refreshed = runCatching {
            refreshAndActivateAccountPass(ctx, nextCandidate)
        }.getOrDefault(nextCandidate)
        activeBearerPass = refreshed.proxyPassToken
        return refreshed.proxyPassToken
    }

    /**
     * Opens a raw TCP [Socket] protected via [protectOutboundSocket] whose `OutputStream`
     * intercepts the very first TLS `ClientHello` record (`0x16 0x03 ...`) and splits it at
     * the SNI hostname extension boundary via [LocalSniSpoofProxy.splitClientHelloAtSni].
     */
    private fun openSocketWithOptionalSniFragment(
        connectHost: String,
        connectPort: Int,
        sniHost: String,
        fragmentTls: Boolean,
        timeoutMs: Int,
    ): Socket {
        val raw = Socket()
        protectOutboundSocket(raw)
        raw.tcpNoDelay = true
        raw.soTimeout = timeoutMs
        raw.connect(InetSocketAddress(connectHost, connectPort), timeoutMs)
        if (!fragmentTls) return raw

        return object : Socket() {
            private val firstWrite = AtomicBoolean(true)
            override fun getInputStream(): InputStream = raw.getInputStream()
            override fun getOutputStream(): OutputStream {
                val baseOut = raw.getOutputStream()
                return object : OutputStream() {
                    override fun write(b: Int) {
                        baseOut.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (firstWrite.compareAndSet(true, false) && len > 5 && (b[off].toInt() and 0xFF) == 0x16) {
                            val slice = b.copyOfRange(off, off + len)
                            val split = LocalSniSpoofProxy.splitClientHelloAtSni(slice)
                            if (split != null) {
                                baseOut.write(split.first)
                                baseOut.flush()
                                runCatching { Thread.sleep(2) }
                                baseOut.write(split.second)
                                baseOut.flush()
                                return
                            }
                        }
                        baseOut.write(b, off, len)
                    }
                    override fun flush() = baseOut.flush()
                    override fun close() = baseOut.close()
                }
            }
            override fun close() = raw.close()
            override fun isClosed(): Boolean = raw.isClosed
            override fun isConnected(): Boolean = raw.isConnected
            override fun setSoTimeout(timeout: Int) {
                raw.soTimeout = timeout
            }
        }
    }

    private fun pipeBytesWithAccounting(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(16_384)
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            out.flush()
            sessionTransferredBytes.addAndGet(n.toLong())
        }
    }

    private fun readAsciiLine(inp: InputStream): String? {
        val sb = StringBuilder()
        var seenChar = false
        while (true) {
            val b = inp.read()
            if (b < 0) return if (seenChar) sb.toString() else null
            seenChar = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) break
        }
        return sb.toString()
    }

    // =========================================================================
    // 7. Share Link (`fenix://`) & Mihomo Outbound Builder
    // =========================================================================

    /**
     * Builds a `fenix://` share URI that [SubConvConverter] converts into a Mihomo outbound
     * pointing to the local Firefox VPN Fastly bridge (`127.0.0.1:10829`) or direct HTTP CONNECT.
     */
    fun buildFenixTunnelUri(
        country: FirefoxVpnCountry,
        localPort: Int = currentLocalProxyPort(),
        accountEmail: String = "",
        cleanFastlyIp: String = "",
        tlsFragment: Boolean = true,
    ): String {
        val remark = buildString {
            append("🦊 فایرفاکس VPN · ")
            append(country.flag).append(' ').append(country.nameFa.substringBefore(" ("))
            if (accountEmail.isNotBlank()) {
                append(" · ").append(accountEmail.substringBefore('@'))
            }
        }
        val encRemark = runCatching { URLEncoder.encode(remark, "UTF-8") }.getOrDefault("Firefox-VPN")
        val params = buildList {
            add("country=${country.code}")
            add("up_host=${country.proxyHost}")
            add("up_port=${country.proxyPort}")
            if (cleanFastlyIp.isNotBlank()) add("edge_ip=$cleanFastlyIp")
            add("frag=${if (tlsFragment) 1 else 0}")
        }.joinToString("&")
        return "fenix://127.0.0.1:$localPort/?$params#$encRemark"
    }

    fun formatQuotaSummaryFa(account: FirefoxVpnAccount?): String {
        if (account == null) {
            return "بدون حساب (حالت مهمان / آماده ورود — هر حساب رایگان فایرفاکس = ۵۰ گیگابایت در ماه)"
        }
        val remGb = account.effectiveRemainingBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = account.quotaLimitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val pct = (account.usedFraction * 100.0).toInt()
        return String.format(Locale.US, "%.1f گیگ باقی‌مانده از %.0f گیگ (%d%% مصرف‌شده)", remGb, totalGb, pct)
    }

    fun formatQuotaSummaryEn(account: FirefoxVpnAccount?): String {
        if (account == null) {
            return "No account yet (Each free Firefox Account includes 50 GB/month)"
        }
        val remGb = account.effectiveRemainingBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = account.quotaLimitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val pct = (account.usedFraction * 100.0).toInt()
        return String.format(Locale.US, "%.1f GB left of %.0f GB (%d%% used)", remGb, totalGb, pct)
    }

    // =========================================================================
    // Internal HTTP & Hex Helpers
    // =========================================================================

    private fun fxaPostHawkJson(
        url: String,
        sessionTokenHex: String,
        bodyJson: String,
    ): RawHttpResponse {
        val (tokenId, hmacKey) = deriveHawkCredentials(sessionTokenHex)
        val bodyBytes = bodyJson.toByteArray(StandardCharsets.UTF_8)
        for (attempt in 1..4) {
            val hawk = generateHawkHeader(
                method = "POST",
                url = url,
                tokenId = tokenId,
                hmacKey = hmacKey,
                bodyBytes = bodyBytes,
            )
            val resp = executeAntiDpiHttpRequest(
                method = "POST",
                urlStr = url,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to hawk,
                ),
                bodyBytes = bodyBytes,
            )
            if (resp.statusCode != 406) return resp
            solveFastlyChallenge()
        }
        val hawk = generateHawkHeader("POST", url, tokenId, hmacKey, bodyBytes)
        return executeAntiDpiHttpRequest(
            method = "POST",
            urlStr = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to hawk,
            ),
            bodyBytes = bodyBytes,
        )
    }

    private fun guardianRequest(
        method: String,
        path: String,
        accessToken: String,
        bodyJson: String?,
    ): RawHttpResponse {
        val url = "$GUARDIAN_ENDPOINT$path"
        val bodyBytes = bodyJson?.toByteArray(StandardCharsets.UTF_8)
        return fxaDoWithChallengeRetry(
            method = method,
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Cache-Control" to "max-age=0",
                "Authorization" to "Bearer $accessToken",
            ),
            bodyBytes = bodyBytes,
        )
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd-length hex string" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun startLocalBridge(context: Context): Int {
        val accounts = loadAccounts(context)
        val activeAcc = selectBestActiveAccount(accounts)
        val selCode = readSelectedCountryCode(context)
        val countries = getAvailableCountries(context)
        val country = countries.firstOrNull { it.code.equals(selCode, ignoreCase = true) }
            ?: countries.firstOrNull { it.code == "DE" }
            ?: countries.first()
        val cleanIp = readCleanFastlyEdgeIp(context)
        val frag = isTlsFragmentEnabled(context)
        return startOrUpdateLocalFenixProxy(
            context = context,
            country = country,
            bearerPass = activeAcc?.proxyPassToken.orEmpty(),
            cleanEdgeIp = cleanIp,
            fragmentTls = frag,
        )
    }

    private fun countryCodeToFlagEmoji(code: String): String {
        val c = code.trim().uppercase(Locale.US)
        if (c.length != 2 || c[0] !in 'A'..'Z' || c[1] !in 'A'..'Z') return "🌐"
        val first = Character.toChars(0x1F1E6 + (c[0].code - 'A'.code))
        val second = Character.toChars(0x1F1E6 + (c[1].code - 'A'.code))
        return String(first) + String(second)
    }
}
