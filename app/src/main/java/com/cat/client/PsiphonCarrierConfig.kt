package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Minimal Psiphon proxy-only configuration for the isolated carrier process. */
internal object PsiphonCarrierConfig {
    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "1111111111111111"
    private const val SERVER_ENTRY_SIGNATURE_PUBLIC_KEY =
        "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
    private const val REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    private const val REMOTE_SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"
    const val SERVER_ENTRIES_ASSET = "psiphon_server_entries.txt"

    private fun regionsFile(context: Context) = File(context.noBackupFilesDir, "psiphon/regions.txt")
    private fun entriesCacheFile(context: Context) = File(context.noBackupFilesDir, "psiphon/server_entries.txt")

    internal fun unpackSignedServerList(
        packed: ByteArray,
        signingKey: String = REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY,
    ): String {
        val text = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(packed)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val json = JSONObject(text)
        val data = json.optString("data").trim()
        val keyDigest = json.optString("signingPublicKeyDigest").trim()
        val signature = json.optString("signature").trim()
        check(data.isNotEmpty() && keyDigest.isNotEmpty() && signature.isNotEmpty()) {
            "Signed Psiphon server list is incomplete"
        }
        val b64 = java.util.Base64.getDecoder()
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(signingKey.toByteArray(Charsets.US_ASCII))
        check(b64.decode(keyDigest).contentEquals(expectedDigest)) {
            "Psiphon server list key digest mismatch"
        }
        val pubKey = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(b64.decode(signingKey)))
        val verifier = java.security.Signature.getInstance("SHA256withRSA").apply {
            initVerify(pubKey)
            update(data.toByteArray(Charsets.UTF_8))
        }
        check(verifier.verify(b64.decode(signature))) {
            "Psiphon server list RSA signature verification failed"
        }
        return data
    }

    fun loadOrFetchBootstrapEntries(context: Context, upstreamPort: Int): String {
        runCatching {
            context.assets.open(SERVER_ENTRIES_ASSET).bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { bundled ->
            return bundled
        }
        val cacheFile = entriesCacheFile(context)
        runCatching {
            if (cacheFile.isFile) cacheFile.readText().trim() else ""
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cached ->
            return cached
        }
        if (upstreamPort in 1024..65535) {
            runCatching {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", upstreamPort),
                )
                val conn = (java.net.URL(REMOTE_SERVER_LIST_URL).openConnection(proxy) as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val packed = conn.inputStream.use { it.readBytes() }
                        val unpacked = unpackSignedServerList(packed)
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(unpacked)
                        return unpacked
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
        return ""
    }

    val DEFAULT_REGIONS: List<String> = listOf(
        "DE", "NL", "FI", "FR", "GB", "CH", "SE", "AT", "US", "CA",
        "BE", "DK", "NO", "IT", "ES", "PL", "CZ", "IE", "EE", "LV",
        "RO", "BG", "HU", "SK", "SG", "JP", "AU", "IN", "BR",
    )

    private val REGION_NAMES_FA: Map<String, String> = mapOf(
        "DE" to "🇩🇪 آلمان (DE) — پیشنهادی · پرسرعت و پینگ پایین",
        "NL" to "🇳🇱 هلند (NL) — پیشنهادی · پایدار و پرسرعت",
        "FI" to "🇫🇮 فنلاند (FI) — پینگ عالی",
        "FR" to "🇫🇷 فرانسه (FR) — پرسرعت اروپا",
        "GB" to "🇬🇧 انگلیس (GB) — مناسب سایت‌های بریتانیا",
        "CH" to "🇨🇭 سوئیس (CH) — حریم خصوصی بالا",
        "SE" to "🇸🇪 سوئد (SE) — پایدار اروپا",
        "AT" to "🇦🇹 اتریش (AT) — پینگ مناسب",
        "US" to "🇺🇸 آمریکا (US) — مناسب ChatGPT / Gemini / سرویس‌های آمریکا",
        "CA" to "🇨🇦 کانادا (CA)",
        "BE" to "🇧🇪 بلژیک (BE)",
        "DK" to "🇩🇰 دانمارک (DK)",
        "NO" to "🇳🇴 نروژ (NO)",
        "IT" to "🇮🇹 ایتالیا (IT)",
        "ES" to "🇪🇸 اسپانیا (ES)",
        "PL" to "🇵🇱 لهستان (PL)",
        "CZ" to "🇨🇿 جمهوری چک (CZ)",
        "IE" to "🇮🇪 ایرلند (IE)",
        "EE" to "🇪🇪 استونی (EE)",
        "LV" to "🇱🇻 لتونی (LV)",
        "RO" to "🇷🇴 رومانی (RO)",
        "BG" to "🇧🇬 بلغارستان (BG)",
        "HU" to "🇭🇺 مجارستان (HU)",
        "SK" to "🇸🇰 اسلواکی (SK)",
        "SG" to "🇸🇬 سنگاپور (SG)",
        "JP" to "🇯🇵 ژاپن (JP)",
        "AU" to "🇦🇺 استرالیا (AU)",
        "IN" to "🇮🇳 هند (IN)",
        "BR" to "🇧🇷 برزیل (BR)",
    )

    private val REGION_NAMES_EN: Map<String, String> = mapOf(
        "DE" to "🇩🇪 Germany (DE) — Recommended · Fast & Low Ping",
        "NL" to "🇳🇱 Netherlands (NL) — Recommended · Stable & Fast",
        "FI" to "🇫🇮 Finland (FI) — Low Latency",
        "FR" to "🇫🇷 France (FR) — Fast EU",
        "GB" to "🇬🇧 United Kingdom (GB)",
        "CH" to "🇨🇭 Switzerland (CH)",
        "SE" to "🇸🇪 Sweden (SE)",
        "AT" to "🇦🇹 Austria (AT)",
        "US" to "🇺🇸 United States (US) — Best for AI & US Services",
        "CA" to "🇨🇦 Canada (CA)",
        "BE" to "🇧🇪 Belgium (BE)",
        "DK" to "🇩🇰 Denmark (DK)",
        "NO" to "🇳🇴 Norway (NO)",
        "IT" to "🇮🇹 Italy (IT)",
        "ES" to "🇪🇸 Spain (ES)",
        "PL" to "🇵🇱 Poland (PL)",
        "CZ" to "🇨🇿 Czechia (CZ)",
        "IE" to "🇮🇪 Ireland (IE)",
        "EE" to "🇪🇪 Estonia (EE)",
        "LV" to "🇱🇻 Latvia (LV)",
        "RO" to "🇷🇴 Romania (RO)",
        "BG" to "🇧🇬 Bulgaria (BG)",
        "HU" to "🇭🇺 Hungary (HU)",
        "SK" to "🇸🇰 Slovakia (SK)",
        "SG" to "🇸🇬 Singapore (SG)",
        "JP" to "🇯🇵 Japan (JP)",
        "AU" to "🇦🇺 Australia (AU)",
        "IN" to "🇮🇳 India (IN)",
        "BR" to "🇧🇷 Brazil (BR)",
    )

    fun regionDisplayLabel(code: String, isFarsi: Boolean, automaticLabel: String? = null): String {
        val clean = code.trim().uppercase(Locale.US)
        if (clean.isEmpty()) {
            return automaticLabel ?: if (isFarsi) "⚡ خودکار (سریع‌ترین کشور سایفون)" else "⚡ Automatic (Fastest Psiphon Country)"
        }
        return if (isFarsi) {
            REGION_NAMES_FA[clean] ?: "🌍 کشور $clean"
        } else {
            REGION_NAMES_EN[clean] ?: "🌍 Region $clean"
        }
    }

    fun regionShortLabel(code: String, isFarsi: Boolean): String {
        val clean = code.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return if (isFarsi) "⚡ خودکار" else "⚡ Auto"
        val full = regionDisplayLabel(clean, isFarsi)
        return full.substringBefore(" —").trim()
    }

    fun availableRegions(context: Context): List<String> {
        val dynamic = runCatching {
            regionsFile(context).readLines()
                .map { it.trim().uppercase(Locale.US) }
                .filter { it.matches(Regex("[A-Z]{2}")) }
        }.getOrDefault(emptyList())
        return (DEFAULT_REGIONS + dynamic).distinct()
    }

    fun rememberRegions(context: Context, regions: List<String>) {
        val clean = regions.map { it.uppercase(Locale.US) }
            .filter { it.matches(Regex("[A-Z]{2}")) }
            .distinct().sorted()
        if (clean.isEmpty()) return
        runCatching {
            val target = regionsFile(context).apply { parentFile?.mkdirs() }
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(clean.joinToString("\n"))
            if (!temporary.renameTo(target)) {
                target.writeText(clean.joinToString("\n"))
                temporary.delete()
            }
        }
    }

    fun render(context: Context, upstreamPort: Int, region: String = ""): String =
        render(File(context.noBackupFilesDir, "psiphon"), BuildConfig.VERSION_CODE, upstreamPort, region)

    internal val CHAINED_TCP_TUNNEL_PROTOCOLS: List<String> = listOf(
        "SSH",
        "OSSH",
        "TLS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
        "SHADOWSOCKS-OSSH",
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
    )

    internal fun render(dataRoot: File, clientVersion: Int, upstreamPort: Int, region: String): String {
        require(dataRoot.isAbsolute)
        require(upstreamPort in 1024..65535)
        require(region.isEmpty() || region.matches(Regex("[A-Z]{2}")))
        return JSONObject()
            .put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            .put("SponsorId", SPONSOR_ID)
            .put("ClientVersion", clientVersion.toString())
            .put("DataRootDirectory", dataRoot.absolutePath)
            .put("ServerEntrySignaturePublicKey", SERVER_ENTRY_SIGNATURE_PUBLIC_KEY)
            .put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY)
            .put("RemoteServerListUrl", REMOTE_SERVER_LIST_URL)
            .put("EgressRegion", region)
            .put("UpstreamProxyUrl", "socks5://127.0.0.1:$upstreamPort")
            .put("DisableTactics", true)
            .put("LimitTunnelProtocols", org.json.JSONArray(CHAINED_TCP_TUNNEL_PROTOCOLS))
            .put("InproxyTunnelProtocolPreferProbability", 0.0)
            .put("InproxyTunnelProtocolSelectionProbability", 0.0)
            .put("FrontedMeekCDNScanUseBuiltInSpec", true)
            .put("EstablishTunnelTimeoutSeconds", 300)
            .put("ConnectionWorkerPoolSize", 12)
            .put("LocalSocksProxyPort", 0)
            .put("DisableLocalHTTPProxy", true)
            .put("EmitDiagnosticNotices", true)
            .put("EmitDiagnosticNetworkParameters", false)
            .toString()
    }
}
