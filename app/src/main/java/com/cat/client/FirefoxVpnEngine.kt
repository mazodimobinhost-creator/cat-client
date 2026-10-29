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
        "151.101.1.140",
        "151.101.65.140",
        "151.101.129.140",
        "151.101.193.140",
        "151.101.2.132",
        "151.101.66.132",
        "151.101.3.5",
        "146.75.30.132",
        "199.232.69.194",
        "199.232.213.194",
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
        val proxyPort: Int = 2499,
        val masqueTemplate: String = "",
        /** Mozilla marks most locations `locked` for free accounts (the node answers 403); REC always works. */
        val locked: Boolean = false,
    ) {
        val exactEndpoint: String get() = "$proxyHost:$proxyPort"
    }

    /** Locations Mozilla's live `vpn-serverlist` offered unlocked (free) on 2026-10-29; the sync refreshes this. */
    internal val FREE_COUNTRY_CODES = setOf("REC", "ZA", "AR", "AU", "JP", "DE", "US")

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
     * Real Firefox built-in VPN (IP Protection) egress nodes, from Mozilla Remote Settings `vpn-serverlist`
     * (snapshot 2026-10-28). Every node is a Fastly MASQUE/HTTP-CONNECT proxy on port 2499 with SNI = hostname.
     * [syncRemoteSettingsCountries] refreshes this list live. Earlier builds shipped invented
     * `proxy-xx.fpn.mozilla.net:443` hosts that do not exist, so no FENIX config could ever connect.
     */
    val BUILTIN_COUNTRIES: List<FirefoxVpnCountry> = listOf<FirefoxVpnCountry>(
        FirefoxVpnCountry("REC", "⭐", "پیشنهاد خود موزیلا (Anycast — نزدیک‌ترین سرور)", "Mozilla Recommended (Anycast)", "Anycast", "p.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("DE", "🇩🇪", "آلمان (مونیخ)", "Germany (Munich)", "Munich", "muc139.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("NL", "🇳🇱", "هلند (آمستردام — سرعت بالا)", "Netherlands (Amsterdam)", "Amsterdam", "ehrd229.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("GB", "🇬🇧", "انگلستان (لندن)", "United Kingdom (London)", "London", "eglc860.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("US", "🇺🇸", "آمریکا (نیویورک / واشنگتن — باز کردن Gemini)", "United States (New York)", "New York", "us.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("FR", "🇫🇷", "فرانسه (پاریس)", "France (Paris)", "Paris", "lfpb115.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("SE", "🇸🇪", "سوئد (استکهلم)", "Sweden (Stockholm)", "Stockholm", "essb127.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("AT", "🇦🇹", "اتریش (وین)", "Austria (Vienna)", "Vienna", "vie63.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("FI", "🇫🇮", "فنلاند (هلسینکی)", "Finland (Helsinki)", "Helsinki", "hel141.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("CA", "🇨🇦", "کانادا (تورنتو)", "Canada (Toronto)", "Toronto", "ca.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("SG", "🇸🇬", "سنگاپور", "Singapore", "Singapore", "wsat188.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("JP", "🇯🇵", "ژاپن (توکیو)", "Japan (Tokyo)", "Tokyo", "rjtf770.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("IT", "🇮🇹", "ایتالیا (میلان)", "Italy (Milan)", "Milan", "mxp69.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("ES", "🇪🇸", "اسپانیا (مادرید)", "Spain (Madrid)", "Madrid", "leto235.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("BE", "🇧🇪", "بلژیک (بروکسل)", "Belgium (Brussels)", "Brussels", "bru148.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("DK", "🇩🇰", "دانمارک (کپنهاگ)", "Denmark (Copenhagen)", "Copenhagen", "cph232.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("NO", "🇳🇴", "نروژ (اسلو)", "Norway (Oslo)", "Oslo", "osl65.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("IE", "🇮🇪", "ایرلند (دوبلین)", "Ireland (Dublin)", "Dublin", "dub43.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("BG", "🇧🇬", "بلغارستان (صوفیه)", "Bulgaria (Sofia)", "Sofia", "sof151.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("PT", "🇵🇹", "پرتغال (لیسبون)", "Portugal (Lisbon)", "Lisbon", "lis149.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("AU", "🇦🇺", "استرالیا (سیدنی)", "Australia (Sydney)", "Sydney", "ysbk106.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("NZ", "🇳🇿", "نیوزیلند (اوکلند)", "New Zealand (Auckland)", "Auckland", "akl103.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("MX", "🇲🇽", "مکزیک", "Mexico", "Querétaro", "mmqt107.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("BR", "🇧🇷", "برزیل (سائوپائولو)", "Brazil (São Paulo)", "São Paulo", "sbsp209.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("AR", "🇦🇷", "آرژانتین (بوئنوس‌آیرس)", "Argentina (Buenos Aires)", "Buenos Aires", "eze223.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("CL", "🇨🇱", "شیلی (سانتیاگو)", "Chile (Santiago)", "Santiago", "scl222.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("CO", "🇨🇴", "کلمبیا (بوگوتا)", "Colombia (Bogotá)", "Bogotá", "skbo234.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("PE", "🇵🇪", "پرو (لیما)", "Peru (Lima)", "Lima", "spjc128.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("ZA", "🇿🇦", "آفریقای جنوبی (ژوهانسبورگ)", "South Africa (Johannesburg)", "Johannesburg", "faor73.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("GH", "🇬🇭", "غنا (آکرا)", "Ghana (Accra)", "Accra", "acc96.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("KR", "🇰🇷", "کره جنوبی (سئول)", "South Korea (Seoul)", "Seoul", "icn145.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("MY", "🇲🇾", "مالزی (کوالالامپور)", "Malaysia (Kuala Lumpur)", "Kuala Lumpur", "kul98.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("PH", "🇵🇭", "فیلیپین (مانیل)", "Philippines (Manila)", "Manila", "mnl215.m1.fastly-masque.net", 2499),
        FirefoxVpnCountry("TH", "🇹🇭", "تایلند (بانکوک)", "Thailand (Bangkok)", "Bangkok", "bkk228.m1.fastly-masque.net", 2499),
    ).map { it.copy(locked = it.code !in FREE_COUNTRY_CODES) }

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

    /** `exp - nbf` (or `exp - iat`) of a JWT: how long the pass lives, independent of the phone's clock. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun jwtLifetimeSeconds(jwt: String): Long? {
        val parts = jwt.trim().split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payloadPart = parts[1].trimEnd('=')
            val padded = payloadPart + "=".repeat((4 - payloadPart.length % 4) % 4)
            val json = JSONObject(Base64.UrlSafe.decode(padded).toString(StandardCharsets.UTF_8))
            val exp = json.optLong("exp", 0L)
            val start = json.optLong("nbf", 0L).takeIf { it > 0L } ?: json.optLong("iat", 0L)
            (exp - start).takeIf { exp > 0L && start > 0L && it in 60L..(30L * 86_400L) }
        }.getOrNull()
    }

    /**
     * Pass -> expiry on THIS phone's clock. Firefox anchors a fresh pass to "now" with the JWT's own lifetime, so a
     * phone whose clock is an hour off (common in Iran since DST was dropped) neither refreshes on every tunnel nor
     * keeps sending a pass Mozilla already considers expired.
     */
    private val passLocalExpiry = ConcurrentHashMap<String, Long>()

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

    /** Why the last ProxyPass request failed (empty when it worked), shown instead of a generic error. */
    @Volatile
    var lastPassError: String = ""
        private set

    /** Mozilla Guardian `/api/v1/fpn/token` status → a plain explanation (same meanings Firefox itself uses). */
    internal fun guardianTokenError(status: Int, retryAfter: String?): String = when {
        status in 200..299 -> ""
        status == 0 -> "Could not reach vpn.mozilla.org to get a proxy pass — check the internet connection and try again"
        status == 401 -> "Firefox sign-in expired (401) — sign in to the Firefox account again"
        status == 403 -> "This Firefox account is not allowed to use the free VPN yet (403) — open Firefox's VPN once or use another account"
        status == 429 -> "Mozilla's free VPN limit for this account is used up (429)" +
            (retryAfter?.trim()?.takeIf { it.isNotEmpty() }?.let { " — try again after $it" } ?: "") +
            " or add another Firefox account"
        status == 451 -> "Mozilla does not offer its free VPN in the region it sees for this request (451)"
        status >= 500 -> "Mozilla's VPN service is having trouble ($status) — try again in a few minutes"
        else -> "Mozilla refused the proxy pass request ($status)"
    }

    /** `X-Quota-Reset` is ISO 8601 in Firefox's client; older builds stored epoch seconds. */
    internal fun parseQuotaReset(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000L else it }
        return runCatching { java.time.OffsetDateTime.parse(v).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(v).epochSecond }.getOrNull()
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

        lastPassError = guardianTokenError(tokenResp?.statusCode ?: 0, tokenResp?.header("Retry-After"))
        if (tokenResp != null && tokenResp.statusCode in 200..299) {
            val j = JSONObject(tokenResp.body)
            val passJwt = j.optString("token")
            val nowSec = System.currentTimeMillis() / 1000L
            val expSec = jwtLifetimeSeconds(passJwt)?.let { nowSec + it }
                ?: j.optLong("expires_at", 0L).takeIf { it > 0L }
                ?: extractJwtExpirySeconds(passJwt)
                ?: (nowSec + 3600L)
            if (passJwt.isNotBlank()) passLocalExpiry[passJwt] = expSec

            val quotaLimit = tokenResp.header("X-Quota-Limit")?.toLongOrNull()
                ?.takeIf { it > 0L } ?: account.quotaLimitBytes
            val quotaRem = tokenResp.header("X-Quota-Remaining")?.toLongOrNull()
                ?: account.quotaRemainingBytes
            val quotaReset = parseQuotaReset(tokenResp.header("X-Quota-Reset"))
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
                    proxyPort = o.optInt("proxyPort", 2499),
                    masqueTemplate = o.optString("masqueTemplate"),
                    locked = if (o.has("locked")) o.optBoolean("locked") else o.optString("code").uppercase(Locale.US) !in FREE_COUNTRY_CODES,
                )
            }
            sanitizeCountries(remoteList)
        }.getOrDefault(BUILTIN_COUNTRIES)
    }

    /**
     * Drops invented hosts cached by older builds (`proxy-xx.fpn.mozilla.net`, port 443), replacing them with the
     * real node of the same country when known, and keeps one entry per country (REC first).
     */
    internal fun sanitizeCountries(list: List<FirefoxVpnCountry>): List<FirefoxVpnCountry> {
        val builtinByCode = BUILTIN_COUNTRIES.associateBy { it.code.uppercase(Locale.US) }
        val seen = HashSet<String>()
        val cleaned = list.mapNotNull { c ->
            val code = c.code.trim().uppercase(Locale.US)
            if (code.isEmpty() || !seen.add(code)) return@mapNotNull null
            val fake = c.proxyHost.isBlank() || c.proxyHost.endsWith(".fpn.mozilla.net", ignoreCase = true)
            if (fake) builtinByCode[code] else c
        }
        if (cleaned.none { it.code != "REC" }) return BUILTIN_COUNTRIES
        val rec = cleaned.firstOrNull { it.code == "REC" } ?: BUILTIN_COUNTRIES.first()
        return listOf(rec) + cleaned.filter { it.code != "REC" }
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
            result += BUILTIN_COUNTRIES.first() // Keep REC at index 0 (host replaced below when the record exists)

            for (i in 0 until dataArr.length()) {
                val rec = dataArr.optJSONObject(i) ?: continue
                val code = rec.optString("code").trim().uppercase(Locale.US)
                if (code.isBlank()) continue
                if (code == "REC") {
                    val recSrv = rec.optJSONArray("cities")?.optJSONObject(0)?.optJSONArray("servers")?.optJSONObject(0)
                    val recHost = recSrv?.optString("hostname").orEmpty()
                    if (recHost.isNotBlank()) {
                        result[0] = result[0].copy(proxyHost = recHost, proxyPort = recSrv?.optInt("port", 2499)?.takeIf { it > 0 } ?: 2499)
                    }
                    continue
                }
                // The list can carry two records for one code (version-filtered); the first one is the current node.
                if (result.any { it.code == code }) continue
                val nameEn = rec.optString("name").ifBlank { code }
                val cities = rec.optJSONArray("cities")
                val firstCity = cities?.optJSONObject(0)
                val cityName = firstCity?.optString("name").orEmpty()
                val servers = firstCity?.optJSONArray("servers")
                val firstSrv = servers?.optJSONObject(0)
                val host = firstSrv?.optString("hostname")?.takeIf { it.isNotBlank() } ?: continue
                val port = firstSrv?.optInt("port", 2499)?.takeIf { it > 0 } ?: 2499
                val existing = builtinByCode[code]
                result += FirefoxVpnCountry(
                    code = code,
                    flag = existing?.flag ?: countryCodeToFlagEmoji(code),
                    nameFa = existing?.nameFa ?: "$nameEn ($code)",
                    nameEn = existing?.nameEn ?: "$nameEn ($cityName)".trimEnd(' ', '(', ')'),
                    cityName = cityName.ifBlank { existing?.cityName.orEmpty() },
                    proxyHost = host,
                    proxyPort = port,
                    locked = rec.optBoolean("locked", false),
                )
            }
            // Countries missing from the live list are not offered by Mozilla any more: do not re-add them.
            if (result.size <= 1) return@runCatching BUILTIN_COUNTRIES
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
                        .put("masqueTemplate", c.masqueTemplate)
                        .put("locked", c.locked),
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
     * Measures each country's REAL Mozilla node (`<node>.m1.fastly-masque.net:2499`): TCP connect time from this
     * network, with a protected socket. Earlier builds pinged generic Fastly IPs and added a made-up per-country
     * delay, so the list looked healthy even when no node was reachable.
     */
    fun pingCountries(
        context: Context,
        maxCountries: Int = 12,
        timeoutMs: Int = 900,
    ): List<CountryPingResult> {
        // Locked locations answer 403 for free accounts, so "fastest" only races the ones that can carry traffic.
        val countries = getAvailableCountries(context).filter { !it.locked }.ifEmpty { getAvailableCountries(context) }
            .take(maxCountries.coerceAtLeast(1))
        val effectiveTimeout = timeoutMs.coerceAtLeast(1_100)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(null, r, "fenix-ping", 128 * 1024L).apply { isDaemon = true }
        }
        return try {
            val futures = countries.map { country ->
                pool.submit<CountryPingResult> {
                    val rtt = probeTcpLatency(country.proxyHost, country.proxyPort, effectiveTimeout)
                    CountryPingResult(country = country, rttMs = rtt ?: 9_999L, reachable = rtt != null)
                }
            }
            futures.mapIndexed { idx, fut ->
                runCatching { fut.get(effectiveTimeout.toLong() + 2_000L, TimeUnit.MILLISECONDS) }
                    .getOrElse { CountryPingResult(country = countries[idx], rttMs = 9_999L, reachable = false) }
            }.sortedWith(compareByDescending<CountryPingResult> { it.reachable }.thenBy { it.rttMs })
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Finds the fastest IP that REALLY serves the Mozilla node on this network: TCP to `IP:2499` plus a full TLS
     * handshake with SNI `p.m1.fastly-masque.net` and certificate hostname verification. Candidates are the DNS answers
     * of the recommended node plus [CLEAN_FASTLY_EDGE_IPS]. A plain port-443 ping of a generic Fastly IP proves nothing
     * for FENIX, so it is no longer counted. Returns null when nothing passes; callers must then keep "auto" (no IP).
     */
    fun findBestCleanFastlyEdgeIp(timeoutMs: Int = 900): Pair<String, Long>? {
        val effectiveTimeout = timeoutMs.coerceAtLeast(2_500)
        val resolved = runCatching {
            java.net.InetAddress.getAllByName(RECOMMENDED_NODE_HOST).mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        val candidates = (resolved + CLEAN_FASTLY_EDGE_IPS).distinct().take(24)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(null, r, "fenix-edge", 128 * 1024L).apply { isDaemon = true }
        }
        return try {
            candidates.map { ip ->
                pool.submit<Pair<String, Long>?> {
                    probeNodeTlsLatency(ip, RECOMMENDED_NODE_HOST, effectiveTimeout)?.let { ip to it }
                }
            }.mapNotNull { fut ->
                runCatching { fut.get(effectiveTimeout.toLong() * 2 + 1_000L, TimeUnit.MILLISECONDS) }.getOrNull()
            }.minByOrNull { it.second }
        } finally {
            pool.shutdownNow()
        }
    }

    /** TCP + verified TLS handshake to `ip:2499` presenting [sni]; returns total ms or null. */
    internal fun probeNodeTlsLatency(ip: String, sni: String, timeoutMs: Int): Long? = runCatching {
        val t0 = System.nanoTime()
        Socket().use { raw ->
            protectOutboundSocket(raw)
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(ip, FASTLY_MASQUE_PORT), timeoutMs)
            raw.soTimeout = timeoutMs
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (sslFactory.createSocket(raw, sni, FASTLY_MASQUE_PORT, false) as SSLSocket).use { ssl ->
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
            }
        }
        ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
    }.getOrNull()

    private fun probeTcpLatency(host: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val t0 = System.nanoTime()
            Socket().use { sock ->
                protectOutboundSocket(sock)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(resolveEdgeAddress(host), port), timeoutMs)
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
    // Every tunnel needs TWO threads (handler + client->upstream pipe). The old pool (core 4 + bounded queue) only
    // grew past 4 threads once 64 tasks were queued, so from the 3rd parallel connection the pipe task sat in the
    // queue, the request never reached Fastly and every page hung: "FENIX configs do not work at all".
    // A SynchronousQueue hands each task to a thread immediately (bounded by maxPoolSize and the semaphore).
    private val fenixWorkerPool: ThreadPoolExecutor = ThreadPoolExecutor(
        0,
        MAX_FENIX_THREADS,
        30L,
        TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(null, runnable, "fenix-pool-${fenixPoolSeq.incrementAndGet()}", 256 * 1024L).apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    // Hard cap on concurrent upstream tunnels (2 threads each, see MAX_FENIX_THREADS)
    private val activeTunnelSemaphore = Semaphore(MAX_FENIX_TUNNELS)

    @Volatile
    private var activeUpstreamHost: String = RECOMMENDED_NODE_HOST
    @Volatile
    private var activeUpstreamPort: Int = 2499
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
                        if (nMethods > 0) readExactly(inp, nMethods) ?: return@use
                        out.write(byteArrayOf(0x05, 0x00))
                        out.flush()

                        val hdr = readExactly(inp, 4) ?: return@use
                        if (hdr[0].toInt() != 0x05) return@use
                        if (hdr[1].toInt() != 0x01) {
                            // Only CONNECT exists on Mozilla's proxy (no UDP ASSOCIATE / BIND): say so instead of hanging up.
                            out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            out.flush()
                            return@use
                        }
                        targetHost = when (hdr[3].toInt() and 0xFF) {
                            0x01 -> InetAddress.getByAddress(readExactly(inp, 4) ?: return@use).hostAddress ?: return@use
                            0x03 -> {
                                val len = inp.read()
                                if (len <= 0) return@use
                                String(readExactly(inp, len) ?: return@use, StandardCharsets.UTF_8)
                            }
                            0x04 -> InetAddress.getByAddress(readExactly(inp, 16) ?: return@use).hostAddress ?: return@use
                            else -> return@use
                        }
                        val portBytes = readExactly(inp, 2) ?: return@use
                        targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
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

                    val upstreamStream = try {
                        openUpstreamTunnelStream(targetHost, targetPort)
                    } catch (error: Throwable) {
                        val reason = error.message.orEmpty().take(200).ifEmpty { error.javaClass.simpleName }
                        if (reason != lastTunnelError) {
                            appContextRef?.let { ctx -> runCatching { DiagnosticLogger.warn(ctx, "fenix.tunnel.fail", reason) } }
                        }
                        lastTunnelError = reason
                        runCatching {
                            if (isSocks5) out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            else out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                        }
                        return@use
                    }
                    // The 20 s timeout is for the handshake only; an idle but alive tunnel (push, long poll,
                    // WebSocket, a slow download pause) must not be killed after 20 s.
                    c.soTimeout = 0
                    runCatching { upstreamStream.soTimeout = 0 }
                    lastTunnelError = ""
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
            // Honest failure: without a signed-in Firefox Account there is no Mozilla proxy pass, so
            // there is no tunnel. Earlier builds silently dialled the target directly here, which
            // looked connected but never changed the IP or opened blocked sites.
            throw IOException(
                lastPassError.takeIf { it.isNotBlank() }
                    ?: "Cat Fox VPN needs a signed-in Firefox Account (no ProxyPass)",
            )
        }

        // Candidate Fastly MASQUE nodes, all on the real port (2499): the last node that worked, an optional
        // user-picked clean IP for the selected node, the selected country's node, then Mozilla's own
        // recommended anycast node (also used when a country is locked for the account: HTTP 403).
        val upstreamPort = activeUpstreamPort.takeIf { it in 1..65535 } ?: FASTLY_MASQUE_PORT
        val candidateEndpoints = buildList {
            lastGoodEndpoint?.takeIf { lastGoodFor == sniHost }?.let { add(it) }
            if (activeCleanEdgeIp.isNotBlank()) add(Triple(activeCleanEdgeIp, upstreamPort, sniHost))
            add(Triple(sniHost, upstreamPort, sniHost))
            if (!sniHost.equals(RECOMMENDED_NODE_HOST, ignoreCase = true)) {
                add(Triple(RECOMMENDED_NODE_HOST, FASTLY_MASQUE_PORT, RECOMMENDED_NODE_HOST))
            }
        }.distinct()

        val authority = if (targetHost.contains(':') && !targetHost.startsWith("[")) {
            "[$targetHost]:$targetPort"
        } else {
            "$targetHost:$targetPort"
        }
        var lastError: Throwable? = null
        var statusError: Throwable? = null // a clear answer from Mozilla (401/403/429) beats a later network error
        for ((connectHost, connectPort, nodeSni) in candidateEndpoints) {
            val key = "$connectHost|$connectPort|$nodeSni"
            // Up to 3 tries per node: a stale HTTP/2 connection (network switch) is replaced once, and a rejected
            // proxy pass (401/407/429) is refreshed/rotated once.
            var passRetried = false
            var sessionRetried = false
            var attempt = 0
            while (attempt++ < 3) {
                var session: FenixH2Session? = null
                try {
                    val (h2, h1Socket) = h2SessionOrTls(key, connectHost, connectPort, nodeSni)
                    session = h2
                    if (h2 != null) {
                        val stream = h2.openConnect(
                            authority,
                            listOf(
                                "proxy-authorization" to "Bearer $pass",
                                "user-agent" to FIREFOX_DESKTOP_USER_AGENT,
                            ),
                            timeoutMs = 12_000L,
                        )
                        lastGoodEndpoint = Triple(connectHost, connectPort, nodeSni)
                        lastGoodFor = sniHost
                        lastTransport = "HTTP/2 · $connectHost:$connectPort"
                        return H2TunnelSocket(stream)
                    }
                    val ssl = h1Socket ?: throw IOException("no TLS socket for $connectHost")
                    val statusLine = http1Connect(ssl, authority, pass, connectHost, connectPort)
                    if (CONNECT_OK.containsMatchIn(statusLine)) {
                        lastGoodEndpoint = Triple(connectHost, connectPort, nodeSni)
                        lastGoodFor = sniHost
                        lastTransport = "HTTP/1.1 · $connectHost:$connectPort"
                        return ssl
                    }
                    runCatching { ssl.close() }
                    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    throw FenixH2StatusException(code, "Fastly node $connectHost:$connectPort returned $statusLine")
                } catch (e: FenixH2StatusException) {
                    if (targetPort != 80 && targetPort != 443 && e.status in setOf(400, 403, 405, 502)) {
                        // Mozilla's edge only tunnels web ports (80/443); this is about the target, not the node or
                        // the account, so do not try other nodes or blame the location.
                        throw IOException(
                            "Cat Fox VPN (Mozilla) only carries web ports 80/443 — $targetHost:$targetPort was refused (${e.status})",
                            e,
                        )
                    }
                    lastError = IOException(fenixStatusMessage(e.status, "$connectHost:$connectPort"), e)
                    if (statusError == null || e.status != 403) statusError = lastError
                    if (lastGoodEndpoint?.first == connectHost) lastGoodEndpoint = null
                    if ((e.status == 401 || e.status == 407 || e.status == 429) && !passRetried) {
                        passRetried = true
                        val next = rotateOrRefreshAccountPassOnFailure()
                        if (next.isNotBlank() && next != pass) {
                            pass = next
                            continue
                        }
                    }
                    break // 403 (location locked for this account) and others: next node
                } catch (e: Throwable) {
                    lastError = e
                    if (session != null && !sessionRetried) {
                        // The pooled connection was dead (no CONNECT answer / reset): drop it and dial a fresh one.
                        sessionRetried = true
                        session.close("tunnel open failed: ${e.message}")
                        dropH2Session(key, session)
                        continue
                    }
                    if (lastGoodEndpoint?.first == connectHost) lastGoodEndpoint = null
                    // A saved "clean IP" that cannot even complete TLS for the node (e.g. the old generic 151.101.1.195
                    // default) would add a 10 s stall to every new connection; skip it for the rest of this session.
                    if (connectHost == activeCleanEdgeIp) activeCleanEdgeIp = ""
                    break
                }
            }
        }
        // No direct fallback: if every Fastly edge failed, the tunnel is down and mihomo must know.
        throw (statusError ?: lastError ?: IOException("All Cat Fox VPN (Fastly) edges failed"))
    }

    // ---- beta211: HTTP/2 transport (what Firefox and every working Firefox-VPN client use) ----------------------

    /** Which transport carried the last working tunnel ("HTTP/2 · host:port"), for the status screen. */
    @Volatile
    var lastTransport: String = ""
        private set

    private val h2Sessions = ConcurrentHashMap<String, MutableList<FenixH2Session>>()
    private val h2CreateLocks = ConcurrentHashMap<String, Any>()
    private val h2DialFailures = ConcurrentHashMap<String, Pair<Long, Throwable>>()

    /** Closes every pooled HTTP/2 connection (VPN stopped, network changed, account switched). */
    fun resetUpstreamSessions(reason: String = "reset") {
        val all = h2Sessions.values.flatMap { list -> synchronized(list) { list.toList() } }
        h2Sessions.clear()
        h2DialFailures.clear()
        all.forEach { runCatching { it.close(reason) } }
        lastGoodEndpoint = null
    }

    private fun dropH2Session(key: String, session: FenixH2Session) {
        h2Sessions[key]?.let { list -> synchronized(list) { list.remove(session) } }
    }

    private fun pooledH2(key: String): FenixH2Session? {
        val list = h2Sessions[key] ?: return null
        synchronized(list) {
            list.removeAll { it.isClosed }
            return list.filter { it.hasCapacity }.minByOrNull { it.activeStreams }
        }
    }

    /**
     * Returns a pooled/new HTTP/2 connection to the node, or — only when the node does not speak HTTP/2 — a fresh
     * TLS socket for the HTTP/1.1 CONNECT fallback. New connections to one node are dialled one at a time, so a
     * page opening 20 tunnels at once shares one handshake instead of 20.
     */
    private fun h2SessionOrTls(key: String, connectHost: String, connectPort: Int, nodeSni: String): Pair<FenixH2Session?, SSLSocket?> {
        pooledH2(key)?.let { return it to null }
        val lock = h2CreateLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            pooledH2(key)?.let { return it to null }
            h2DialFailures[key]?.let { (at, error) ->
                if (System.currentTimeMillis() - at < 4_000L) throw IOException("recent dial failure: ${error.message}", error)
            }
            val ssl = try {
                openNodeTls(connectHost, connectPort, nodeSni, arrayOf("h2", "http/1.1"))
            } catch (e: Throwable) {
                h2DialFailures[key] = System.currentTimeMillis() to e
                throw e
            }
            h2DialFailures.remove(key)
            val alpn = negotiatedAlpn(ssl)
            if (alpn != "h2") return null to ssl
            ssl.soTimeout = 0 // the session pings an idle connection itself
            val session = FenixH2Session(ssl, "$nodeSni via $connectHost:$connectPort", log = { msg ->
                appContextRef?.let { ctx -> runCatching { DiagnosticLogger.info(ctx, "fenix.h2", msg.take(200)) } }
            })
            session.start(handshakeTimeoutMs = 10_000L)
            h2Sessions.getOrPut(key) { mutableListOf() }.let { list -> synchronized(list) { list += session } }
            return session to null
        }
    }

    /** TLS to a Mozilla node: protected socket, optional ClientHello split, certificate check, given ALPN list. */
    private fun openNodeTls(connectHost: String, connectPort: Int, nodeSni: String, alpn: Array<String>): SSLSocket {
        val raw = openSocketWithOptionalSniFragment(
            connectHost = connectHost,
            connectPort = connectPort,
            sniHost = nodeSni,
            fragmentTls = activeFragmentTls,
            timeoutMs = 10_000,
        )
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = sslFactory.createSocket(raw, nodeSni, connectPort, true) as SSLSocket
        try {
            ssl.soTimeout = 15_000
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(nodeSni))
            // Verify the node certificate (*.m1.fastly-masque.net) so the proxy pass is never sent to an impostor.
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) params.applicationProtocols = alpn
            ssl.sslParameters = params
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) setLegacyAlpn(ssl, alpn)
            ssl.startHandshake()
        } catch (e: Throwable) {
            runCatching { ssl.close() }
            throw e
        }
        return ssl
    }

    /** Android 8–9: ALPN through the platform (Conscrypt) socket's own setter. */
    private fun setLegacyAlpn(ssl: SSLSocket, protocols: Array<String>) {
        runCatching {
            val out = ByteArrayOutputStream()
            protocols.forEach { p ->
                val b = p.toByteArray(StandardCharsets.US_ASCII)
                out.write(b.size)
                out.write(b)
            }
            ssl.javaClass.getMethod("setAlpnProtocols", ByteArray::class.java).invoke(ssl, out.toByteArray())
        }
    }

    private fun negotiatedAlpn(ssl: SSLSocket): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { ssl.applicationProtocol }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            runCatching {
                (ssl.javaClass.getMethod("getAlpnSelectedProtocol").invoke(ssl) as? ByteArray)
                    ?.toString(StandardCharsets.US_ASCII)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    /** HTTP/1.1 CONNECT (only for a node that does not negotiate HTTP/2). Returns the status line. */
    private fun http1Connect(ssl: SSLSocket, authority: String, pass: String, connectHost: String, connectPort: Int): String {
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
        return statusLine
    }

    internal fun fenixStatusMessage(status: Int, node: String): String = when (status) {
        401, 407 -> "Mozilla rejected the proxy pass ($status) at $node — sign in again or add another Firefox account"
        403 -> "This location is locked for the account (403) at $node — pick «Recommended» or another country"
        429 -> "Monthly 50 GB quota used up (429) at $node — add another Firefox account"
        else -> "Fastly node $node answered $status"
    }

    /** An HTTP/2 CONNECT stream dressed as a Socket for the bridge's pipe code. */
    private class H2TunnelSocket(private val stream: FenixH2Stream) : Socket() {
        @Volatile private var closedFlag = false
        override fun getInputStream(): InputStream = stream.input
        override fun getOutputStream(): OutputStream = stream.output
        override fun shutdownOutput() = stream.output.close()
        override fun close() {
            closedFlag = true
            stream.close()
        }
        override fun isClosed(): Boolean = closedFlag
        override fun isConnected(): Boolean = true
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getSoTimeout(): Int = 0
    }

    // ---- beta211: DNS for Mozilla nodes that survives a poisoned resolver ------------------------------------------

    private val dohCache = ConcurrentHashMap<String, Pair<Long, InetAddress>>()

    private fun isBogon(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            a.isMulticastAddress || (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 0)

    /**
     * Resolves a Mozilla/Fastly host for a protected socket. When the line's DNS fails or answers with a private /
     * blocked address (Iranian resolvers answer 10.10.34.x for filtered names), asks Cloudflare and Google DNS over
     * HTTPS by IP instead.
     */
    internal fun resolveEdgeAddress(host: String): InetAddress {
        val system = runCatching { resolveForProtectedSocket(host) }
        system.getOrNull()?.takeIf { !isBogon(it) }?.let { return it }
        val clean = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        dohCache[clean]?.let { (at, addr) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return addr }
        val doh = listOf(
            Triple("1.1.1.1", "cloudflare-dns.com", "/dns-query?name=$clean&type=A"),
            Triple("8.8.8.8", "dns.google", "/resolve?name=$clean&type=A"),
            Triple("1.0.0.1", "cloudflare-dns.com", "/dns-query?name=$clean&type=A"),
        )
        for ((ip, sni, path) in doh) {
            val addr = runCatching { dohLookup(ip, sni, path) }.getOrNull() ?: continue
            dohCache[clean] = System.currentTimeMillis() to addr
            return addr
        }
        return system.getOrElse { throw it }
    }

    private fun dohLookup(ip: String, sni: String, path: String): InetAddress? {
        val raw = Socket()
        protectOutboundSocket(raw)
        raw.soTimeout = 4_000
        raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 4_000)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, 443, true) as SSLSocket
        ssl.use { s ->
            val params = s.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            params.endpointIdentificationAlgorithm = "HTTPS"
            s.sslParameters = params
            s.startHandshake()
            s.outputStream.write(
                "GET $path HTTP/1.1\r\nHost: $sni\r\nAccept: application/dns-json\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            s.outputStream.flush()
            val all = s.inputStream.readBytes()
            val text = all.toString(StandardCharsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            if (split < 0) return null
            val head = text.substring(0, split)
            val bodyBytes = all.copyOfRange(split + 4, all.size)
            val body = if (head.contains("chunked", ignoreCase = true)) {
                readChunkedBody(ByteArrayInputStream(bodyBytes)).toString(StandardCharsets.UTF_8)
            } else {
                bodyBytes.toString(StandardCharsets.UTF_8)
            }
            val json = body.substring(body.indexOf('{').coerceAtLeast(0), body.lastIndexOf('}') + 1)
            val answers = JSONObject(json).optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type") != 1) continue
                val data = a.optString("data")
                if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(data)) continue
                val addr = InetAddress.getByName(data)
                if (!isBogon(addr)) return addr
            }
        }
        return null
    }

    /**
     * Ensures the active account has a non-expired `ProxyPass` token, or refreshes/rotates it.
     */
    private fun ensureValidProxyPass(): String {
        val current = activeBearerPass
        val exp = passLocalExpiry[current] ?: run {
            // After an app restart: the saved account carries the clock-anchored expiry; remember it once.
            val saved = appContextRef?.let { ctx ->
                runCatching { loadAccounts(ctx) }.getOrNull()
                    ?.firstOrNull { it.proxyPassToken == current && current.isNotBlank() }?.proxyPassExpiresAtSec
                    ?.takeIf { it > 0L }
            }
            if (saved != null) passLocalExpiry[current] = saved
            saved ?: extractJwtExpirySeconds(current)
        }
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
        try {
            raw.connect(InetSocketAddress(resolveEdgeAddress(connectHost), connectPort), timeoutMs)
        } catch (e: Exception) {
            runCatching { raw.close() } // beta205: no fd leak when a probe target is down
            throw e
        }
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

    /** Reads exactly [n] bytes (a stream read may return fewer), or null on EOF. */
    private fun readExactly(inp: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    /** Last upstream failure (empty after a working tunnel), for the in-app status/diagnostics. */
    @Volatile
    var lastTunnelError: String = ""
        private set

    @Volatile
    private var lastGoodEndpoint: Triple<String, Int, String>? = null
    @Volatile
    private var lastGoodFor: String = ""

    private val CONNECT_OK = Regex("""^HTTP/\d(?:\.\d)?\s+200(?:\s|$)""")
    const val FASTLY_MASQUE_PORT = 2499
    const val RECOMMENDED_NODE_HOST = "p.m1.fastly-masque.net"
    private const val MAX_FENIX_TUNNELS = 64
    private const val MAX_FENIX_THREADS = MAX_FENIX_TUNNELS * 2 + 8

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
            append("🦊 Cat Fox VPN · ")
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

    fun startLocalBridge(context: Context, preferredCountryCode: String? = null): Int {
        val accounts = loadAccounts(context)
        val activeAcc = selectBestActiveAccount(accounts)
        val selCode = preferredCountryCode?.trim()?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2,3}")) }
            ?: readSelectedCountryCode(context)
        val countries = getAvailableCountries(context)
        val country = countries.firstOrNull { it.code.equals(selCode, ignoreCase = true) }
            ?: countries.firstOrNull { it.code == COUNTRY_RECOMMENDED }
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
