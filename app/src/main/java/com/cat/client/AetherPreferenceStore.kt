package com.cat.client

import android.content.Context

data class AetherWarmWarpKeyEntry(
    val idSummary: String,
    val tomlContent: String,
    val createdAt: Long,
)

data class AetherSavedProfile(
    val name: String,
    val mode: AetherMode,
    val ipScan: String,
    val endpoint: String,
    val scannedAt: Long,
    val chainTarget: String = "none",
    val exitLocation: String = "auto",
    val psiphonRegion: String = "",
    val masqueTransport: String = "h2",
    val innerEndpoint: String = "",
    val scanMode: String = "balanced",
    val noizePreset: String = "auto",
    val fingerprint: String = "",
    val fragmentEnabled: Boolean = true,
    val fragmentSize: String = "16-32",
    val fragmentDelay: String = "2-10",
    val masqueSni: String = "",
    val echEnabled: Boolean = false,
    val echDns: String = "https://1.1.1.1/dns-query",
    val echDomain: String = "cloudflare-ech.com",
    val psiphonMode: String = "auto",
    val psiphonCdnIps: String = "",
    val psiphonCdnSni: String = "",
    val psiphonBundled: Boolean = true,
    val torBridgesMode: String = "auto",
    val torCustomBridges: String = "",
    val torRelaysMode: String = "auto",
)

data class AetherSmartHint(
    val titleFa: String,
    val bodyFa: String,
    val actionLabelFa: String,
    val titleEn: String,
    val bodyEn: String,
    val actionLabelEn: String,
    val recommendedMode: AetherMode,
    val recommendedIpScan: String,
    val recommendedChain: String,
    val recommendedExitLocation: String,
    val recommendedPsiphonRegion: String,
)

data class AetherSelection(
    val enabled: Boolean = false,
    val mode: AetherMode = AetherMode.MasqueHttp2,
    val endpoint: String? = null,
    val innerEndpoint: String? = null,
    val endpointScannedAt: Long = 0L,
    val psiphonRegion: String = "",
    val ipScan: String = "both",
    val profileName: String = "",
    val chainTarget: String = "none",
    val exitLocation: String = "auto",
    val masqueTransport: String = "h2",
    val masqueSni: String = "",
    val echEnabled: Boolean = false,
    val echDns: String = "https://1.1.1.1/dns-query",
    val echDomain: String = "cloudflare-ech.com",
    val scanMode: String = "balanced",
    val noizePreset: String = "auto",
    val fingerprint: String = "",
    val fragmentEnabled: Boolean = true,
    val fragmentSize: String = "16-32",
    val fragmentDelay: String = "2-10",
    val psiphonMode: String = "auto",
    val psiphonCdnIps: String = "",
    val psiphonCdnSni: String = "",
    val psiphonBundled: Boolean = true,
    val torBridgesMode: String = "auto",
    val torCustomBridges: String = "",
    val torRelaysMode: String = "auto",
) {
    fun freshEndpoint(now: Long = System.currentTimeMillis()): String? = endpoint?.takeIf {
        endpointScannedAt > 0L && now >= endpointScannedAt && now - endpointScannedAt <= ENDPOINT_MAX_AGE_MS
    }

    /**
     * Resolves the carrier mode when chaining Psiphon or Tor (`warp -> psiphon/tor` or `reverse`).
     * Preserves `MasqueInMasque` (`MASQUE in MASQUE` over `HTTP/2`), `WarpInWarp`,
     * `MasqueHttp2`, and `WireGuard` as requested by the user/profile.
     */
    fun effectiveCarrierMode(): AetherMode = when (mode) {
        AetherMode.MasqueInMasque -> AetherMode.MasqueInMasque
        AetherMode.WarpInWarp -> AetherMode.WarpInWarp
        AetherMode.WireGuard -> AetherMode.WireGuard
        AetherMode.MasqueHttp2 -> AetherMode.MasqueHttp2
        AetherMode.WireGuardOverMasque -> AetherMode.MasqueHttp2
        else -> if (ipScan == "v6") AetherMode.WireGuard else AetherMode.MasqueHttp2
    }

    /**
     * Effective exit location policy:
     * - If Psiphon/Tor chain is active, exit is always `"foreign"`.
     * - If `exitLocation` is explicitly `"iran"` or `"foreign"`, returns it.
     * - If `exitLocation == "auto"`, two-hop modes (`MasqueInMasque`, `WarpInWarp`, `WireGuardOverMasque`)
     *   imply `"foreign"` and single-hop implies `"iran"`.
     */
    fun effectiveExitLocation(): String = when {
        chainTarget in setOf("psiphon", "psiphon-reverse", "tor", "tor-reverse", "auto-psiphon") ||
            mode == AetherMode.MasquePsiphon -> "foreign"
        exitLocation in setOf("iran", "foreign") -> exitLocation
        mode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque) -> "foreign"
        else -> "iran"
    }

    /**
     * Contextual recommendation engine: explains which companion buttons/settings
     * work best with the user's current selection or last clicked button.
     */
    fun smartRecommendation(lastClicked: String = ""): AetherSmartHint {
        val effExit = effectiveExitLocation()
        val curRegion = psiphonRegion.ifBlank { "DE" }
        return when {
            lastClicked == "mim" || mode == AetherMode.MasqueInMasque -> AetherSmartHint(
                titleFa = "💡 آموزش رسمی PattNG: متد MASQUE in MASQUE + خروجی Psiphon",
                bodyFa = "• طبق آموزش استاندارد PattNG:\n" +
                    "  ۱) ابتدا یک بار «🔑 Get new WARP keys» را بزنید تا هر دو کلید MASQUE ثبت شوند.\n" +
                    "  ۲) پروتکل (Protocol): روی «MASQUE in MASQUE» قرار دارد.\n" +
                    "  ۳) ترنسپورت (Transport): روی «HTTP/2» قرار گیرد.\n" +
                    "  ۴) بخش Psiphon: روی «WARP > Psiphon» و کشور (Exit country) روی کشور دلخواه (${curRegion}).\n" +
                    "  ۵) در پایان «🔍 Scan for an endpoint» را بزنید و ذخیره (✅) کنید.",
                actionLabelFa = "✨ اعمال کامل تنظیمات آموزش (MASQUE in MASQUE + HTTP/2 + WARP > Psiphon)",
                titleEn = "💡 PattNG Guide: MASQUE in MASQUE + HTTP/2 + WARP > Psiphon",
                bodyEn = "• Recommended PattNG setup:\n" +
                    "  1) Tap '🔑 Get new WARP keys' once.\n" +
                    "  2) Protocol = MASQUE in MASQUE · Transport = HTTP/2.\n" +
                    "  3) Psiphon = WARP > Psiphon · Exit country = $curRegion.\n" +
                    "  4) Tap '🔍 Scan for an endpoint' and Save (✅).",
                actionLabelEn = "✨ Apply Full Guide Preset (MIM + HTTP/2 + WARP > Psiphon)",
                recommendedMode = AetherMode.MasqueInMasque,
                recommendedIpScan = "v4",
                recommendedChain = "psiphon",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = curRegion,
            )
            lastClicked == "mci" || (ipScan == "v6" && mode == AetherMode.WireGuard && lastClicked.isEmpty()) -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای فایروال همراه اول (WARP + IPv6)",
                bodyFa = "• با «WireGuard + IPv6» بهترین مکمل:\n" +
                    "  ۱) برای بیشترین سرعت: «🇮🇷 آی‌پی ایران» یا «🌍 آی‌پی خارج (دو لایه Gool)» بدون چین.\n" +
                    "  ۲) برای رفع کامل تحریم: «🔓 چین WARP → Psiphon» با کشور «🇩🇪 آلمان (DE)» یا «🇳🇱 هلند (NL)».\n" +
                    "  ۳) اگر خط شما IPv6 نداشت، دکمه «🟪 MASQUE-HTTP/2» را بزنید (روی IPv4 هم وصل می‌شود).",
                actionLabelFa = "✨ پیشنهاد مکمل: آی‌پی خارج + سایفون آلمان (DE)",
                titleEn = "💡 Smart Tip for MCI Firewall (WARP + IPv6)",
                bodyEn = "• Best companions with WireGuard + IPv6:\n" +
                    "  1) Max speed: '🇮🇷 Iran IP' or '🌍 Foreign IP (Double-Hop Gool)' without chain.\n" +
                    "  2) Full sanction bypass: 'WARP → Psiphon' + '🇩🇪 Germany (DE)'.\n" +
                    "  3) If your line lacks IPv6, switch to '🟪 MASQUE-HTTP/2'.",
                actionLabelEn = "✨ Apply Combo: Foreign IP + Psiphon DE",
                recommendedMode = AetherMode.WireGuard,
                recommendedIpScan = "v6",
                recommendedChain = "psiphon",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = curRegion,
            )
            lastClicked == "mtn" -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای فایروال ایرانسل (WARP + IPv4/IPv6)",
                bodyFa = "• روی فایروال ایرانسل:\n" +
                    "  ۱) پروتکل «WireGuard (WARP)» با «هر دو IPv4 + IPv6» بدون محدودیت پورت کار می‌کند.\n" +
                    "  ۲) اگر آی‌پی خارج بدون افت سرعت می‌خواهید: دکمه «🌍 آی‌پی خارج (دو لایه Gool)» با همین گزینه عالی کار می‌کند.\n" +
                    "  ۳) اگر رفع تحریم خاص (مثل هوش مصنوعی) می‌خواهید: «WARP → Psiphon» با کشور «🇩🇪 آلمان» یا «🇺🇸 آمریکا» را بزنید.",
                actionLabelFa = "✨ پیشنهاد مکمل: آی‌پی خارج پرسرعت (Gool)",
                titleEn = "💡 Smart Tip for Irancell Firewall (WARP Any IP)",
                bodyEn = "• Best companions on Irancell firewall:\n" +
                    "  1) WireGuard + Both IPv4/IPv6 works cleanly.\n" +
                    "  2) For fast foreign IP: pair with '🌍 Foreign IP (Double-Hop Gool)'.\n" +
                    "  3) For strict sanction bypass: pair with 'WARP → Psiphon (DE/US)'.",
                actionLabelEn = "✨ Apply Combo: Fast Foreign IP (Gool)",
                recommendedMode = AetherMode.WireGuard,
                recommendedIpScan = "both",
                recommendedChain = "none",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = "",
            )
            lastClicked == "masque" || mode == AetherMode.MasqueHttp2 || mode == AetherMode.WireGuardOverMasque -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای پروتکل MASQUE-HTTP/2",
                bodyFa = "• پروتکل MASQUE-HTTP/2 روی هر دو فایروال همراه اول و ایرانسل (حتی بدون IPv6) کار می‌کند:\n" +
                    "  ۱) بهترین نوع آی‌پی برای آن: «هر دو IPv4 + IPv6» (پورت 443).\n" +
                    "  ۲) برای آی‌پی خارج پرسرعت: با «🌍 آی‌پی خارج (WG-over-MASQUE)» ترکیب شود.\n" +
                    "  ۳) اگر سایفون/تور چین می‌کنید: روی MASQUE-HTTP/2 ساده (تک‌لایه) بماند تا افت سرعت نداشته باشد.",
                actionLabelFa = "✨ پیشنهاد مکمل: MASQUE-H2 + آی‌پی خارج (Gool)",
                titleEn = "💡 Smart Tip for MASQUE-HTTP/2",
                bodyEn = "• MASQUE-HTTP/2 works on both MCI & Irancell firewalls over TLS 443:\n" +
                    "  1) Best IP family: 'Both IPv4 + IPv6'.\n" +
                    "  2) For fast foreign IP: pair with '🌍 Foreign IP (WG-over-MASQUE)'.",
                actionLabelEn = "✨ Apply Combo: MASQUE-H2 + Foreign IP",
                recommendedMode = AetherMode.WireGuardOverMasque,
                recommendedIpScan = "both",
                recommendedChain = "none",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = "",
            )
            chainTarget == "psiphon" || lastClicked == "psiphon" || lastClicked == "region" -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای چین WARP → سایفون (${if (psiphonRegion.isEmpty()) "خودکار" else psiphonRegion})",
                bodyFa = "• وقتی سایفون را چین می‌کنید:\n" +
                    "  ۱) بهترین پروتکل پایه: «WireGuard ساده» یا «MASQUE-HTTP/2 ساده» (پروتکل دولایه خاموش شود تا سرعت نیفتد).\n" +
                    "  ۲) بهترین کشور سایفون برای پینگ پایین از ایران: «🇩🇪 آلمان (DE)»، «🇳🇱 هلند (NL)» یا «🇫🇮 فنلاند (FI)».\n" +
                    "  ۳) برای ChatGPT / سرویس‌های آمریکایی: کشور سایفون را روی «🇺🇸 آمریکا (US)» بگذارید.",
                actionLabelFa = "✨ تنظیم بهینه: WireGuard ساده + کشور آلمان 🇩🇪",
                titleEn = "💡 Smart Tip for WARP → Psiphon Chain",
                bodyEn = "• Best companions with Psiphon chain:\n" +
                    "  1) Simple WireGuard or MASQUE-HTTP/2 carrier (avoid double-hop).\n" +
                    "  2) Fastest countries for Iran: 🇩🇪 Germany (DE), 🇳🇱 Netherlands (NL), 🇫🇮 Finland (FI).\n" +
                    "  3) For US AI services: select 🇺🇸 United States (US).",
                actionLabelEn = "✨ Apply Optimal: Simple WireGuard + 🇩🇪 DE",
                recommendedMode = AetherMode.WireGuard,
                recommendedIpScan = ipScan,
                recommendedChain = "psiphon",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = curRegion,
            )
            chainTarget == "tor" || lastClicked == "tor" -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای چین WARP → تور (Orbot)",
                bodyFa = "• با چین تور (Orbot):\n" +
                    "  ۱) پروتکل پایه حتماً ساده («WireGuard» یا «MASQUE-HTTP/2») باشد.\n" +
                    "  ۲) چون تور سرعت متوسطی دارد، اگر فقط آی‌پی خارج پرسرعت با کشور مشخص می‌خواهید، «WARP → سایفون (آلمان DE)» بسیار سریع‌تر و بدون نیاز به برنامه جانبی کار می‌کند.",
                actionLabelFa = "✨ تغییر به جایگزین سریع‌تر: WARP → سایفون (آلمان 🇩🇪)",
                titleEn = "💡 Smart Tip for WARP → Tor Chain",
                bodyEn = "• Tor has medium speed. For faster foreign IP with country selection built-in, switch to WARP → Psiphon (🇩🇪 DE).",
                actionLabelEn = "✨ Switch to Faster: WARP → Psiphon (🇩🇪 DE)",
                recommendedMode = AetherMode.WireGuard,
                recommendedIpScan = ipScan,
                recommendedChain = "psiphon",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = "DE",
            )
            effExit == "iran" || lastClicked == "iran" -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای 🇮🇷 آی‌پی ایران (تک‌لایه سریع)",
                bodyFa = "• وقتی «🇮🇷 آی‌پی ایران» را انتخاب کردید:\n" +
                    "  ۱) چین سایفون/تور باید «بدون چین (مستقیم)» باشد تا آی‌پی شما در ایران (loc=IR) بماند و بالاترین سرعت را بگیرید.\n" +
                    "  ۲) روی همراه اول «WireGuard + IPv6» و روی ایرانسل «WireGuard + هر دو IPv4/IPv6» یا «MASQUE-HTTP/2» بهترین مکمل است.",
                actionLabelFa = "✨ پیشنهاد مکمل: MASQUE-HTTP/2 + هر دو IPv4/IPv6",
                titleEn = "💡 Smart Tip for 🇮🇷 Iran Exit IP",
                bodyEn = "• Keep Chain set to 'Direct (No Chain)' so your exit IP stays in Iran (loc=IR) at full speed.\n" +
                    "• Best companion on MCI: WireGuard + IPv6; on Irancell/All: MASQUE-HTTP/2.",
                actionLabelEn = "✨ Apply Combo: MASQUE-H2 + Both IPv4/IPv6",
                recommendedMode = AetherMode.MasqueHttp2,
                recommendedIpScan = "both",
                recommendedChain = "none",
                recommendedExitLocation = "iran",
                recommendedPsiphonRegion = "",
            )
            else -> AetherSmartHint(
                titleFa = "💡 پیشنهاد هوشمند برای 🌍 آی‌پی خارج (دو لایه Gool)",
                bodyFa = "• شما «🌍 آی‌پی خارج (دو لایه Gool)» را انتخاب کرده‌اید:\n" +
                    "  ۱) این حالت بدون نیاز به سایفون/تور و بدون افت سرعت، با دو لایه کلودفلر آی‌پی شما را خارج از ایران (loc!=IR) می‌کند.\n" +
                    "  ۲) اگر کشور خاصی (مثل آلمان 🇩🇪، هلند 🇳🇱 یا آمریکا 🇺🇸) می‌خواهید، دکمه «🔓 چین WARP → Psiphon» و سپس «🌍 کشور سایفون» را بزنید.",
                actionLabelFa = "✨ انتخاب کشور مشخص خارج با سایفون (🇩🇪 آلمان)",
                titleEn = "💡 Smart Tip for 🌍 Foreign Exit IP (Double-Hop Gool)",
                bodyEn = "• Double-Hop Gool gives a non-Iran IP at full speed.\n" +
                    "• Want a specific country (🇩🇪 DE, 🇳🇱 NL, 🇺🇸 US)? Enable 'WARP → Psiphon' + pick country.",
                actionLabelEn = "✨ Pick Specific Country via Psiphon (🇩🇪 DE)",
                recommendedMode = AetherMode.WireGuard,
                recommendedIpScan = ipScan,
                recommendedChain = "psiphon",
                recommendedExitLocation = "foreign",
                recommendedPsiphonRegion = "DE",
            )
        }
    }

    /**
     * Builds a shareable/copyable `aether://[host:port]?...#Name` URI representing this Aether configuration,
     * 100% compatible with both Cat Client and PattNG (`AetherFmt.kt`).
     *
     * Note: `exit_loc` is intentionally omitted because in upstream Aether CLI / PattNG, `--exit-loc !IR`
     * runs `exitloc::settle` on the raw WARP netstack before exposing SOCKS5 (and before Psiphon),
     * tearing down validated WireGuard tunnels when port-80 trace fails or single-hop WARP reports `loc=IR`.
     * Instead, Iran vs Foreign exit is determined structurally (`wg`/`masque` single-hop = IR;
     * `gool`/`wg-over-masque` two-hop or `psiphon=chain`/`tor=chain` = Foreign).
     */
    fun toAetherUri(): String {
        val effExit = effectiveExitLocation()
        val finalMode = simplifyModeForChain(mode, chainTarget)
        val proto = when {
            finalMode == AetherMode.MasqueInMasque -> "mim"
            finalMode == AetherMode.WarpInWarp -> "gool"
            chainTarget in setOf("psiphon", "tor") ->
                if (finalMode == AetherMode.WireGuard) "wg" else "masque"
            finalMode == AetherMode.WireGuard && effExit == "foreign" -> "gool"
            finalMode == AetherMode.WireGuard -> "wg"
            finalMode == AetherMode.WireGuardOverMasque || (finalMode == AetherMode.MasqueHttp2 && effExit == "foreign") ->
                "wg-over-masque"
            else -> "masque"
        }
        val twoHops = proto in setOf("gool", "wg-over-masque", "mim")
        val cleanPeer = endpoint?.trim()?.takeIf { it.isNotBlank() }
        val cleanInner = innerEndpoint?.trim()?.takeIf { it.isNotBlank() } ?: run {
            if (proto == "mim" && cleanPeer != null) {
                val outerHost = cleanPeer.substringBeforeLast(':')
                if (cleanPeer.startsWith("[")) "[2606:4700:d0::a29f:c002]:443"
                else if (outerHost == "162.159.198.1") "162.159.198.2:443"
                else "162.159.198.1:443"
            } else if (proto == "gool" && cleanPeer != null) {
                val outerHost = cleanPeer.substringBeforeLast(':')
                if (cleanPeer.startsWith("[")) "[2606:4700:d0::a29f:c002]:2408"
                else if (outerHost == "162.159.192.1") "162.159.193.1:2408"
                else "162.159.192.1:2408"
            } else null
        }
        val effectiveIp = when {
            ipScan in setOf("v4", "v6") -> ipScan
            cleanPeer != null && cleanPeer.startsWith("[") -> "v6"
            cleanPeer != null && !cleanPeer.startsWith("[") -> "v4"
            else -> "both"
        }
        val validScan = scanMode.takeIf { it in VALID_SCAN_MODES } ?: "balanced"
        val params = mutableListOf(
            "protocol=$proto",
            "scan=$validScan",
            "ip=$effectiveIp",
        )
        val validNoize = noizePreset.takeIf { it in VALID_NOIZE_PRESETS } ?: "auto"
        if (validNoize != "auto") {
            params += "noize=$validNoize"
        }
        if (proto in setOf("masque", "wg-over-masque", "mim")) {
            val tr = if (masqueTransport == "h3") "h3" else "h2"
            params += "transport=$tr"
            if (tr == "h2" && fragmentEnabled) {
                params += "fragment=1"
                params += "fragment_size=${fragmentSize.ifBlank { "16-32" }}"
                params += "fragment_delay=${fragmentDelay.ifBlank { "2-10" }}"
            }
            val effFp = fingerprint.takeIf { it in VALID_FINGERPRINTS }
                ?: if (proto == "mim") "chrome" else "semi-python"
            params += "fingerprint=$effFp"
            val cleanMasqueSni = masqueSni.trim()
            if (cleanMasqueSni.isNotEmpty()) {
                params += "masque_sni=${java.net.URLEncoder.encode(cleanMasqueSni, "UTF-8")}"
            }
            if (echEnabled) {
                params += "ech=1"
                if (echDns.isNotBlank() && echDns != "https://1.1.1.1/dns-query") {
                    params += "ech_dns=${java.net.URLEncoder.encode(echDns.trim(), "UTF-8")}"
                }
                if (echDomain.isNotBlank() && echDomain != "cloudflare-ech.com") {
                    params += "ech_domain=${java.net.URLEncoder.encode(echDomain.trim(), "UTF-8")}"
                }
            }
        }
        if (twoHops && cleanPeer != null) {
            params += "outer=${java.net.URLEncoder.encode(cleanPeer, "UTF-8")}"
            if (cleanInner != null) {
                params += "inner=${java.net.URLEncoder.encode(cleanInner, "UTF-8")}"
            }
        }
        val effPsiphonMode = psiphonMode.takeIf { it in VALID_PSIPHON_MODES } ?: "auto"
        if (chainTarget in setOf("psiphon", "psiphon-reverse", "auto-psiphon") || mode == AetherMode.MasquePsiphon) {
            val psiVal = when (chainTarget) {
                "psiphon-reverse" -> "reverse"
                "auto-psiphon" -> "auto"
                else -> "chain"
            }
            params += "psiphon=$psiVal"
            params += "psiphon_mode=$effPsiphonMode"
            if (effPsiphonMode == "cdn") {
                if (psiphonCdnIps.isNotBlank()) {
                    params += "cdn_ips=${java.net.URLEncoder.encode(psiphonCdnIps.trim(), "UTF-8")}"
                }
                if (psiphonCdnSni.isNotBlank()) {
                    params += "cdn_sni=${java.net.URLEncoder.encode(psiphonCdnSni.trim(), "UTF-8")}"
                }
            }
            if (!psiphonBundled) {
                params += "psiphon_bundled=0"
            }
            if (psiphonRegion.matches(Regex("[A-Z]{2}"))) {
                params += "region=$psiphonRegion"
            }
        } else if (chainTarget in setOf("tor", "tor-reverse")) {
            params += "tor=${if (chainTarget == "tor-reverse") "reverse" else "chain"}"
            val effTorBridges = torBridgesMode.takeIf { it in VALID_TOR_BRIDGES_MODES } ?: "auto"
            val effTorRelays = torRelaysMode.takeIf { it in VALID_TOR_RELAYS_MODES } ?: "auto"
            params += "tor_bridges=$effTorBridges"
            if (effTorBridges == "own" && torCustomBridges.isNotBlank()) {
                params += "bridges=${java.net.URLEncoder.encode(torCustomBridges.trim(), "UTF-8")}"
            }
            params += "tor_relays=$effTorRelays"
        }
        val effTorBridgesAlways = torBridgesMode.takeIf { it in VALID_TOR_BRIDGES_MODES } ?: "auto"
        if (chainTarget !in setOf("tor", "tor-reverse") && effTorBridgesAlways != "auto") {
            params += "tor_bridges=$effTorBridgesAlways"
            if (effTorBridgesAlways == "own" && torCustomBridges.isNotBlank()) {
                params += "bridges=${java.net.URLEncoder.encode(torCustomBridges.trim(), "UTF-8")}"
            }
        }
        val labelBase = profileName.trim().ifEmpty {
            when (proto) {
                "mim" -> if (chainTarget.startsWith("psiphon")) "PattNG-MIM-Psiphon" else "Aether-MIM-H2"
                "gool" -> "Aether-Gool"
                "wg-over-masque" -> "Aether-WG-MASQUE"
                "wg" -> "Aether-WARP"
                else -> "Aether-MASQUE-H2"
            }
        }
        val hash = java.net.URLEncoder.encode(labelBase, "UTF-8").replace("+", "%20")
        val authority = if (!twoHops && cleanPeer != null) cleanPeer else ""
        return "aether://$authority?${params.joinToString("&")}#$hash"
    }

    companion object {
        const val ENDPOINT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        val VALID_CHAIN_TARGETS = setOf("none", "psiphon", "psiphon-reverse", "tor", "tor-reverse", "auto-psiphon")
        val VALID_EXIT_LOCATIONS = setOf("auto", "iran", "foreign")
        val VALID_SCAN_MODES = setOf("turbo", "balanced", "thorough", "verified", "ironclad")
        val VALID_NOIZE_PRESETS = setOf("auto", "off", "light", "firewall", "balanced", "gfw", "aggressive")
        val VALID_FINGERPRINTS = setOf("chrome", "chrome-120", "firefox", "semi-python", "go")
        val VALID_PSIPHON_MODES = setOf("auto", "cdn", "direct")
        val VALID_TOR_BRIDGES_MODES = setOf("auto", "first", "never", "own")
        val VALID_TOR_BRIDGES = VALID_TOR_BRIDGES_MODES
        val VALID_TOR_RELAYS_MODES = setOf("auto", "only", "off")

        /**
         * Applies the exit the user picked in the UI on top of a saved `aether://` link. Without this, connecting
         * from an older list entry replaced e.g. "Germany" with the link's own (often empty) region and the user
         * ended up on a single-hop WARP with an Iranian IP.
         */
        internal fun applyExitChoice(parsed: AetherSelection, choice: AetherExitChoice?): AetherSelection {
            if (choice == null) return parsed
            val region = choice.psiphonRegion.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
            return when (choice.exitLocation) {
                "iran" -> parsed.copy(
                    exitLocation = "iran",
                    chainTarget = "none",
                    mode = when (parsed.mode) {
                        AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque, AetherMode.MasquePsiphon -> AetherMode.MasqueHttp2
                        AetherMode.WarpInWarp -> AetherMode.WireGuard
                        else -> parsed.mode
                    },
                    innerEndpoint = null,
                )
                "foreign" -> {
                    val chain = when {
                        region.isNotEmpty() && choice.chainTarget !in setOf("psiphon", "psiphon-reverse") -> "psiphon"
                        choice.chainTarget in VALID_CHAIN_TARGETS -> choice.chainTarget
                        else -> "none"
                    }
                    parsed.copy(
                        exitLocation = "foreign",
                        chainTarget = chain,
                        psiphonRegion = if (chain.startsWith("psiphon") || chain == "auto-psiphon") region else parsed.psiphonRegion,
                        mode = simplifyModeForChain(parsed.mode, chain),
                    )
                }
                else -> parsed
            }
        }

        fun simplifyModeForChain(mode: AetherMode, chainTarget: String): AetherMode {
            if (chainTarget !in setOf("psiphon", "psiphon-reverse", "tor", "tor-reverse", "auto-psiphon")) return mode
            return when (mode) {
                AetherMode.MasqueInMasque,
                AetherMode.WarpInWarp,
                AetherMode.WireGuard,
                AetherMode.MasqueHttp2,
                -> mode
                else -> AetherMode.WireGuard
            }
        }

        fun fromAetherUri(raw: String, nowMs: Long = System.currentTimeMillis()): AetherSelection? {
            val trimmed = raw.trim()
            val schemeLower = when {
                trimmed.startsWith("aether://", ignoreCase = true) -> "aether"
                trimmed.startsWith("masque://", ignoreCase = true) -> "masque"
                trimmed.startsWith("wireguard://", ignoreCase = true) -> "wireguard"
                else -> return null
            }
            val withoutScheme = trimmed.substringAfter("://")
            val hashIdx = withoutScheme.indexOf('#')
            val beforeHash = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
            val rawFragment = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else ""
            val qIdx = beforeHash.indexOf('?')
            val rawAuthority = if (qIdx >= 0) beforeHash.substring(0, qIdx).trim().trim('/') else beforeHash.trim().trim('/')
            val authority = if ('@' in rawAuthority) rawAuthority.substringAfterLast('@') else rawAuthority
            val query = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else ""
            val map = mutableMapOf<String, String>()
            query.split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = runCatching { java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault("")
                    val v = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault("")
                    if (k.isNotBlank()) map[k.lowercase()] = v.trim()
                }
            }
            val proto = map["protocol"].orEmpty().lowercase().ifEmpty {
                when (schemeLower) {
                    "masque" -> "masque"
                    "wireguard" -> "wg"
                    else -> ""
                }
            }
            val psiphonParam = map["psiphon"].orEmpty().lowercase()
            val torParam = map["tor"].orEmpty().lowercase()
            val legacyChain = (map["chain"] ?: map["cfil"]).orEmpty().lowercase()
            val chainTarget = when {
                psiphonParam == "reverse" || legacyChain == "psiphon-reverse" -> "psiphon-reverse"
                psiphonParam == "auto" || legacyChain == "auto-psiphon" -> "auto-psiphon"
                psiphonParam in setOf("chain", "only", "1", "true") || legacyChain == "psiphon" -> "psiphon"
                torParam == "reverse" || legacyChain == "tor-reverse" -> "tor-reverse"
                torParam in setOf("chain", "only", "1", "true") || legacyChain == "tor" -> "tor"
                else -> "none"
            }
            val exitLocRaw = map["exit_loc"].orEmpty().uppercase()
            val exitLocation = when {
                chainTarget != "none" -> "foreign"
                exitLocRaw == "IR" -> "iran"
                exitLocRaw == "!IR" || proto in setOf("gool", "wiw", "warp-in-warp", "wg-over-masque", "wom", "mim", "masque-in-masque") -> "foreign"
                else -> "iran"
            }
            val baseMode = when (proto) {
                "mim", "masque-in-masque" -> AetherMode.MasqueInMasque
                "gool", "wiw", "warp-in-warp" ->
                    if (chainTarget != "none") AetherMode.WarpInWarp else AetherMode.WireGuard
                "wg", "wireguard" -> AetherMode.WireGuard
                "wg-over-masque", "wom" ->
                    if (chainTarget != "none") AetherMode.MasqueHttp2 else AetherMode.WireGuardOverMasque
                else -> AetherMode.MasqueHttp2
            }
            val transportParam = map["transport"].orEmpty().lowercase().takeIf { it in setOf("h2", "h3") } ?: "h2"
            val ipScan = map["ip"].orEmpty().lowercase().takeIf { it in setOf("both", "v4", "v6") } ?: "both"
            val peer = (map["endpoint"] ?: map["outer"] ?: map["peer"] ?: authority.takeIf { it.isNotEmpty() })
                ?.takeIf(AetherOperations::isSafePublicPeer)
            val innerPeer = map["inner"]?.takeIf(AetherOperations::isSafePublicPeer)
            val region = map["region"].orEmpty().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
            val masqueSniParam = (map["masque_sni"] ?: map["sni"]).orEmpty().trim()
            val echParam = map["ech"].orEmpty().lowercase() in setOf("1", "true", "yes", "on")
            val echDnsParam = map["ech_dns"]?.trim()?.takeIf { it.isNotEmpty() } ?: "https://1.1.1.1/dns-query"
            val echDomainParam = map["ech_domain"]?.trim()?.takeIf { it.isNotEmpty() } ?: "cloudflare-ech.com"
            val scanModeParam = map["scan"].orEmpty().lowercase().takeIf { it in VALID_SCAN_MODES } ?: "balanced"
            val noizeParam = map["noize"].orEmpty().lowercase().takeIf { it in VALID_NOIZE_PRESETS } ?: "auto"
            val fpParam = map["fingerprint"].orEmpty().lowercase().takeIf { it in VALID_FINGERPRINTS }.orEmpty()
            val fragEnabledParam = map["fragment"].orEmpty().lowercase() !in setOf("0", "false", "off")
            val fragSizeParam = map["fragment_size"]?.trim()?.takeIf { it.isNotEmpty() } ?: "16-32"
            val fragDelayParam = map["fragment_delay"]?.trim()?.takeIf { it.isNotEmpty() } ?: "2-10"
            val psiModeParam = map["psiphon_mode"].orEmpty().lowercase().takeIf { it in VALID_PSIPHON_MODES } ?: "auto"
            val cdnIpsParam = map["cdn_ips"].orEmpty().trim()
            val cdnSniParam = map["cdn_sni"].orEmpty().trim()
            val psiBundledParam = map["psiphon_bundled"].orEmpty().lowercase() !in setOf("0", "false", "off")
            val torBridgesParam = map["tor_bridges"].orEmpty().lowercase().takeIf { it in VALID_TOR_BRIDGES_MODES } ?: "auto"
            val customBridgesParam = map["bridges"].orEmpty().trim()
            val torRelaysParam = map["tor_relays"].orEmpty().lowercase().takeIf { it in VALID_TOR_RELAYS_MODES } ?: "auto"
            val name = runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }.getOrDefault(rawFragment).trim()
            return AetherSelection(
                enabled = true,
                mode = simplifyModeForChain(baseMode, chainTarget),
                endpoint = peer,
                innerEndpoint = innerPeer,
                endpointScannedAt = if (peer != null) nowMs else 0L,
                psiphonRegion = region,
                ipScan = ipScan,
                profileName = name.ifEmpty { "Aether" },
                chainTarget = chainTarget,
                exitLocation = exitLocation,
                masqueTransport = transportParam,
                masqueSni = masqueSniParam,
                echEnabled = echParam,
                echDns = echDnsParam,
                echDomain = echDomainParam,
                scanMode = scanModeParam,
                noizePreset = noizeParam,
                fingerprint = fpParam,
                fragmentEnabled = fragEnabledParam,
                fragmentSize = fragSizeParam,
                fragmentDelay = fragDelayParam,
                psiphonMode = psiModeParam,
                psiphonCdnIps = cdnIpsParam,
                psiphonCdnSni = cdnSniParam,
                psiphonBundled = psiBundledParam,
                torBridgesMode = torBridgesParam,
                torCustomBridges = customBridgesParam,
                torRelaysMode = torRelaysParam,
            )
        }
    }
}

/** What the user picked in the UI for the Aether exit. */
internal data class AetherExitChoice(
    val exitLocation: String,
    val chainTarget: String,
    val psiphonRegion: String,
    val atMs: Long,
)

/**
 * A measured Aether exit. [requested] is `IR`, `!IR` or a Psiphon country code; [countryCode] is empty when the
 * exit could not be measured. [via] is `direct`, `psiphon`, `psiphon-auto`, `warp-2hop` or `tor`.
 */
internal data class AetherExitStatus(
    val requested: String,
    val countryCode: String,
    val ip: String,
    val via: String,
    val atMs: Long,
) {
    val measured: Boolean get() = countryCode.matches(Regex("[A-Z]{2}"))
    /** True when a foreign exit (or a specific country) was requested but Cloudflare sees an Iranian IP. */
    val wrongIran: Boolean get() = requested != "IR" && countryCode == "IR"
    /** True when a specific Psiphon country was requested and the measured exit is another country. */
    val otherCountry: Boolean get() = requested.matches(Regex("[A-Z]{2}")) && requested != "IR" && measured && countryCode != requested
}

class AetherPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_aether", Context.MODE_PRIVATE)

    /** Remembers the exit the user picked in the UI (Iran / foreign / Psiphon country) so a saved list link cannot silently override it. */
    fun saveExitChoice(exitLocation: String, chainTarget: String, psiphonRegion: String) {
        prefs.edit()
            .putString("exit_choice_loc", exitLocation)
            .putString("exit_choice_chain", chainTarget)
            .putString("exit_choice_region", psiphonRegion.uppercase(java.util.Locale.US).takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty())
            .putLong("exit_choice_at", System.currentTimeMillis())
            .apply()
    }

    internal fun readExitChoice(): AetherExitChoice? {
        val at = prefs.getLong("exit_choice_at", 0L)
        if (at <= 0L) return null
        return AetherExitChoice(
            exitLocation = prefs.getString("exit_choice_loc", "auto").orEmpty(),
            chainTarget = prefs.getString("exit_choice_chain", "none").orEmpty(),
            psiphonRegion = prefs.getString("exit_choice_region", "").orEmpty(),
            atMs = at,
        )
    }

    /** Last really measured exit of an Aether connection (Cloudflare trace), shown in the Aether panel. */
    internal fun saveLastExit(status: AetherExitStatus) {
        prefs.edit()
            .putString("last_exit_requested", status.requested)
            .putString("last_exit_loc", status.countryCode)
            .putString("last_exit_ip", status.ip)
            .putString("last_exit_via", status.via)
            .putLong("last_exit_at", status.atMs)
            .apply()
    }

    internal fun readLastExit(): AetherExitStatus? {
        val at = prefs.getLong("last_exit_at", 0L)
        if (at <= 0L) return null
        return AetherExitStatus(
            requested = prefs.getString("last_exit_requested", "").orEmpty(),
            countryCode = prefs.getString("last_exit_loc", "").orEmpty(),
            ip = prefs.getString("last_exit_ip", "").orEmpty(),
            via = prefs.getString("last_exit_via", "").orEmpty(),
            atMs = at,
        )
    }

    fun read(): AetherSelection {
        val enabled = prefs.getBoolean("enabled", false)
        val mode = AetherMode.entries.firstOrNull { it.wireName == prefs.getString("mode", null) }
            ?: AetherMode.MasqueHttp2
        val endpoint = prefs.getString("endpoint", null)?.takeIf(AetherOperations::isSafePublicPeer)
        val innerEndpoint = prefs.getString("innerEndpoint", null)?.takeIf(AetherOperations::isSafePublicPeer)
        val region = prefs.getString("psiphonRegion", "").orEmpty()
            .takeIf { it.isEmpty() || it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        val ipScan = prefs.getString("ipScan", "both").orEmpty()
            .takeIf { it in setOf("both", "v4", "v6") } ?: "both"
        val profileName = prefs.getString("profileName", "").orEmpty().trim()
        val rawChain = prefs.getString("chainTarget", null).orEmpty()
        val chainTarget = when {
            rawChain in AetherSelection.VALID_CHAIN_TARGETS -> rawChain
            mode == AetherMode.MasquePsiphon -> "psiphon"
            else -> "none"
        }
        val exitLocation = prefs.getString("exitLocation", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
        val masqueTransport = prefs.getString("masqueTransport", "h2").orEmpty()
            .takeIf { it in setOf("h2", "h3") } ?: "h2"
        val masqueSni = prefs.getString("masqueSni", "").orEmpty().trim()
        val echEnabled = prefs.getBoolean("echEnabled", false)
        val echDns = prefs.getString("echDns", "https://1.1.1.1/dns-query").orEmpty().ifBlank { "https://1.1.1.1/dns-query" }
        val echDomain = prefs.getString("echDomain", "cloudflare-ech.com").orEmpty().ifBlank { "cloudflare-ech.com" }
        val scanMode = prefs.getString("scanMode", "balanced").orEmpty()
            .takeIf { it in AetherSelection.VALID_SCAN_MODES } ?: "balanced"
        val noizePreset = prefs.getString("noizePreset", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_NOIZE_PRESETS } ?: "auto"
        val fingerprint = prefs.getString("fingerprint", "").orEmpty()
            .takeIf { it.isEmpty() || it in AetherSelection.VALID_FINGERPRINTS }.orEmpty()
        val fragmentEnabled = prefs.getBoolean("fragmentEnabled", true)
        val fragmentSize = prefs.getString("fragmentSize", "16-32").orEmpty().ifBlank { "16-32" }
        val fragmentDelay = prefs.getString("fragmentDelay", "2-10").orEmpty().ifBlank { "2-10" }
        val psiphonMode = prefs.getString("psiphonMode", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_PSIPHON_MODES } ?: "auto"
        val psiphonCdnIps = prefs.getString("psiphonCdnIps", "").orEmpty().trim()
        val psiphonCdnSni = prefs.getString("psiphonCdnSni", "").orEmpty().trim()
        val psiphonBundled = prefs.getBoolean("psiphonBundled", true)
        val torBridgesMode = prefs.getString("torBridgesMode", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_TOR_BRIDGES_MODES } ?: "auto"
        val torCustomBridges = prefs.getString("torCustomBridges", "").orEmpty().trim()
        val torRelaysMode = prefs.getString("torRelaysMode", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_TOR_RELAYS_MODES } ?: "auto"
        return AetherSelection(
            enabled = enabled,
            mode = mode,
            endpoint = endpoint,
            innerEndpoint = innerEndpoint,
            endpointScannedAt = prefs.getLong("endpointScannedAt", 0L),
            psiphonRegion = region,
            ipScan = ipScan,
            profileName = profileName,
            chainTarget = chainTarget,
            exitLocation = exitLocation,
            masqueTransport = masqueTransport,
            masqueSni = masqueSni,
            echEnabled = echEnabled,
            echDns = echDns,
            echDomain = echDomain,
            scanMode = scanMode,
            noizePreset = noizePreset,
            fingerprint = fingerprint,
            fragmentEnabled = fragmentEnabled,
            fragmentSize = fragmentSize,
            fragmentDelay = fragmentDelay,
            psiphonMode = psiphonMode,
            psiphonCdnIps = psiphonCdnIps,
            psiphonCdnSni = psiphonCdnSni,
            psiphonBundled = psiphonBundled,
            torBridgesMode = torBridgesMode,
            torCustomBridges = torCustomBridges,
            torRelaysMode = torRelaysMode,
        )
    }

    fun save(selection: AetherSelection) {
        require(selection.psiphonRegion.isEmpty() || selection.psiphonRegion.matches(Regex("[A-Z]{2}")))
        val validIpScan = selection.ipScan.takeIf { it in setOf("both", "v4", "v6") } ?: "both"
        val validExitLoc = selection.exitLocation.takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
        val validChain = if (validExitLoc == "iran") {
            "none"
        } else {
            selection.chainTarget.takeIf { it in AetherSelection.VALID_CHAIN_TARGETS } ?: "none"
        }
        val normalizedMode = when {
            validExitLoc == "iran" && selection.mode in setOf(AetherMode.WireGuardOverMasque, AetherMode.MasqueInMasque) ->
                AetherMode.MasqueHttp2
            validExitLoc == "iran" && selection.mode == AetherMode.WarpInWarp ->
                AetherMode.WireGuard
            else -> AetherSelection.simplifyModeForChain(selection.mode, validChain)
        }
        val validTransport = selection.masqueTransport.takeIf { it in setOf("h2", "h3") } ?: "h2"
        val validScan = selection.scanMode.takeIf { it in AetherSelection.VALID_SCAN_MODES } ?: "balanced"
        val validNoize = selection.noizePreset.takeIf { it in AetherSelection.VALID_NOIZE_PRESETS } ?: "auto"
        val validFp = selection.fingerprint.takeIf { it.isEmpty() || it in AetherSelection.VALID_FINGERPRINTS }.orEmpty()
        val validPsiMode = selection.psiphonMode.takeIf { it in AetherSelection.VALID_PSIPHON_MODES } ?: "auto"
        val validTorBridges = selection.torBridgesMode.takeIf { it in AetherSelection.VALID_TOR_BRIDGES_MODES } ?: "auto"
        val validTorRelays = selection.torRelaysMode.takeIf { it in AetherSelection.VALID_TOR_RELAYS_MODES } ?: "auto"
        prefs.edit()
            .putBoolean("enabled", selection.enabled)
            .putString("mode", normalizedMode.wireName)
            .putString("endpoint", selection.endpoint)
            .putString("innerEndpoint", selection.innerEndpoint)
            .putLong("endpointScannedAt", selection.endpointScannedAt)
            .putString("psiphonRegion", selection.psiphonRegion)
            .putString("ipScan", validIpScan)
            .putString("profileName", selection.profileName.trim())
            .putString("chainTarget", validChain)
            .putString("exitLocation", validExitLoc)
            .putString("masqueTransport", validTransport)
            .putString("masqueSni", selection.masqueSni.trim())
            .putBoolean("echEnabled", selection.echEnabled)
            .putString("echDns", selection.echDns.trim().ifBlank { "https://1.1.1.1/dns-query" })
            .putString("echDomain", selection.echDomain.trim().ifBlank { "cloudflare-ech.com" })
            .putString("scanMode", validScan)
            .putString("noizePreset", validNoize)
            .putString("fingerprint", validFp)
            .putBoolean("fragmentEnabled", selection.fragmentEnabled)
            .putString("fragmentSize", selection.fragmentSize.trim().ifBlank { "16-32" })
            .putString("fragmentDelay", selection.fragmentDelay.trim().ifBlank { "2-10" })
            .putString("psiphonMode", validPsiMode)
            .putString("psiphonCdnIps", selection.psiphonCdnIps.trim())
            .putString("psiphonCdnSni", selection.psiphonCdnSni.trim())
            .putBoolean("psiphonBundled", selection.psiphonBundled)
            .putString("torBridgesMode", validTorBridges)
            .putString("torCustomBridges", selection.torCustomBridges.trim())
            .putString("torRelaysMode", validTorRelays)
            .apply()
    }

    /**
     * Warm WARP Key Pool (stores up to 3 pre-provisioned `aether.toml` key bundles
     * containing Primary & Secondary WARP/MASQUE keys for instant offline/zero-wait config creation).
     */
    fun readWarpKeyPool(): List<AetherWarmWarpKeyEntry> {
        val raw = prefs.getString("warpKeyPoolJson", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val toml = obj.optString("tomlContent").trim()
                    if (toml.length < 40) continue
                    add(
                        AetherWarmWarpKeyEntry(
                            idSummary = obj.optString("idSummary", "WARP-Key-${i + 1}"),
                            tomlContent = toml,
                            createdAt = obj.optLong("createdAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun pushWarmWarpKeyEntry(entry: AetherWarmWarpKeyEntry, maxEntries: Int = 3) {
        if (entry.tomlContent.trim().length < 40) return
        val existing = readWarpKeyPool().filterNot { it.tomlContent.trim() == entry.tomlContent.trim() }
        val updated = (listOf(entry) + existing).take(maxEntries.coerceIn(1, 5))
        val arr = org.json.JSONArray()
        updated.forEach { item ->
            arr.put(
                org.json.JSONObject()
                    .put("idSummary", item.idSummary)
                    .put("tomlContent", item.tomlContent)
                    .put("createdAt", item.createdAt),
            )
        }
        prefs.edit().putString("warpKeyPoolJson", arr.toString()).apply()
    }

    fun applyWarmWarpKeyToFileIfNeeded(identityFile: java.io.File, forceReplace: Boolean = false): AetherWarmWarpKeyEntry? {
        if (!forceReplace && identityFile.exists() && identityFile.length() > 80L) return null
        val pool = readWarpKeyPool()
        val picked = pool.firstOrNull() ?: return null
        return runCatching {
            identityFile.parentFile?.mkdirs()
            identityFile.writeText(picked.tomlContent)
            picked
        }.getOrNull()
    }

    /**
     * 1-Tap quick Psiphon egress country switcher for saved/active `MASQUE in MASQUE` or `WARP > Psiphon` configs.
     */
    fun switchPsiphonCountryQuick(regionCode: String): AetherSelection {
        val cleanRegion = regionCode.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        val cur = read()
        val newChain = if (cur.chainTarget in setOf("psiphon", "psiphon-reverse", "auto-psiphon")) {
            cur.chainTarget
        } else {
            "psiphon"
        }
        val tag = cleanRegion.ifBlank { "Auto" }
        val baseName = cur.profileName.substringBefore("-Psiphon").ifBlank {
            if (cur.mode == AetherMode.MasqueInMasque) "PattNG-MIM" else "WARP"
        }
        val updated = cur.copy(
            enabled = true,
            psiphonRegion = cleanRegion,
            chainTarget = newChain,
            exitLocation = "foreign",
            profileName = "$baseName-Psiphon-$tag",
        )
        save(updated)
        return updated
    }

    fun readSavedProfiles(): List<AetherSavedProfile> {
        val raw = prefs.getString("savedProfilesJson", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim().ifEmpty { continue }
                    val mode = AetherMode.entries.firstOrNull { it.wireName == obj.optString("mode") }
                        ?: AetherMode.MasqueHttp2
                    val ipScan = obj.optString("ipScan", "both").takeIf { it in setOf("both", "v4", "v6") } ?: "both"
                    val ep = obj.optString("endpoint").trim().takeIf(AetherOperations::isSafePublicPeer) ?: continue
                    val ts = obj.optLong("scannedAt", 0L)
                    val chain = obj.optString("chainTarget", "none")
                        .takeIf { it in AetherSelection.VALID_CHAIN_TARGETS } ?: "none"
                    val exitLoc = obj.optString("exitLocation", "auto")
                        .takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
                    val region = obj.optString("psiphonRegion", "").trim().uppercase()
                        .takeIf { it.isEmpty() || it.matches(Regex("[A-Z]{2}")) }.orEmpty()
                    val tr = obj.optString("masqueTransport", "h2").takeIf { it in setOf("h2", "h3") } ?: "h2"
                    val innerEp = obj.optString("innerEndpoint", "").trim()
                        .takeIf(AetherOperations::isSafePublicPeer).orEmpty()
                    val scanM = obj.optString("scanMode", "balanced")
                        .takeIf { it in AetherSelection.VALID_SCAN_MODES } ?: "balanced"
                    val noizeP = obj.optString("noizePreset", "auto")
                        .takeIf { it in AetherSelection.VALID_NOIZE_PRESETS } ?: "auto"
                    val psiM = obj.optString("psiphonMode", "auto")
                        .takeIf { it in AetherSelection.VALID_PSIPHON_MODES } ?: "auto"
                    add(AetherSavedProfile(name, mode, ipScan, ep, ts, chain, exitLoc, region, tr, innerEp, scanM, noizeP, psiM))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveNamedProfile(profile: AetherSavedProfile) {
        val existing = readSavedProfiles().filterNot { it.name.equals(profile.name, ignoreCase = true) }
        val updated = (listOf(profile) + existing).take(16)
        val arr = org.json.JSONArray()
        updated.forEach { item ->
            arr.put(
                org.json.JSONObject()
                    .put("name", item.name)
                    .put("mode", item.mode.wireName)
                    .put("ipScan", item.ipScan)
                    .put("endpoint", item.endpoint)
                    .put("scannedAt", item.scannedAt)
                    .put("chainTarget", item.chainTarget)
                    .put("exitLocation", item.exitLocation)
                    .put("psiphonRegion", item.psiphonRegion)
                    .put("masqueTransport", item.masqueTransport)
                    .put("innerEndpoint", item.innerEndpoint)
                    .put("scanMode", item.scanMode)
                    .put("noizePreset", item.noizePreset)
                    .put("psiphonMode", item.psiphonMode),
            )
        }
        prefs.edit().putString("savedProfilesJson", arr.toString()).apply()
    }

    /**
     * Remembers a verified endpoint specifically for the current SIM operator or Wi-Fi network
     * (inspired by WhiteAestherMobile's EndpointDiscoveryMemory + NetworkIdentity) and updates
     * the Top-10 ranked clean endpoint pool for that carrier.
     */
    fun rememberEndpointForNetwork(
        networkKey: String,
        mode: AetherMode,
        ipScan: String,
        peer: String,
        rttMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (networkKey.isBlank() || !AetherOperations.isSafePublicPeer(peer)) return
        val family = if (mode == AetherMode.WireGuard) "warp" else "masque"
        val slotKey = "netmem_${networkKey}_${family}_$ipScan"
        val payload = org.json.JSONObject()
            .put("peer", peer)
            .put("rttMs", rttMs.coerceAtLeast(0L))
            .put("savedAt", nowMs)
            .toString()
        prefs.edit().putString(slotKey, payload).apply()
        rememberEndpointPoolForNetwork(networkKey, mode, ipScan, peer, rttMs, nowMs)
    }

    fun recallEndpointForNetwork(
        networkKey: String,
        mode: AetherMode,
        ipScan: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        if (networkKey.isBlank()) return null
        val poolFirst = recallEndpointPoolForNetwork(networkKey, mode, ipScan, nowMs).firstOrNull()?.peer
        val family = if (mode == AetherMode.WireGuard) "warp" else "masque"
        val slotKey = "netmem_${networkKey}_${family}_$ipScan"
        val raw = prefs.getString(slotKey, null) ?: return poolFirst
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val savedAt = obj.optLong("savedAt", 0L)
            val age = nowMs - savedAt
            if (age !in 0L..(7L * 24 * 60 * 60 * 1000L)) return@runCatching null
            obj.optString("peer").trim().takeIf(AetherOperations::isSafePublicPeer)
        }.getOrNull() ?: poolFirst
    }

    data class AetherCachedEndpoint(
        val peer: String,
        val rttMs: Long,
        val savedAt: Long,
        val networkKey: String = "",
    )

    fun rememberEndpointPoolForNetwork(
        networkKey: String,
        mode: AetherMode,
        ipScan: String,
        peer: String,
        rttMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (networkKey.isBlank() || !AetherOperations.isSafePublicPeer(peer)) return
        val family = if (mode == AetherMode.WireGuard) "warp" else "masque"
        val poolKey = "netpool_${networkKey}_${family}_$ipScan"
        val existing = recallEndpointPoolForNetwork(networkKey, mode, ipScan, nowMs)
            .filterNot { it.peer.equals(peer.trim(), ignoreCase = true) }
        val added = AetherCachedEndpoint(
            peer = peer.trim(),
            rttMs = rttMs.coerceAtLeast(0L),
            savedAt = nowMs,
            networkKey = networkKey,
        )
        val merged = (listOf(added) + existing)
            .sortedWith(compareBy<AetherCachedEndpoint> { if (it.rttMs > 0L) it.rttMs else 9_999L }.thenByDescending { it.savedAt })
            .take(10)
        val arr = org.json.JSONArray()
        merged.forEach { item ->
            arr.put(
                org.json.JSONObject()
                    .put("peer", item.peer)
                    .put("rttMs", item.rttMs)
                    .put("savedAt", item.savedAt)
                    .put("networkKey", item.networkKey),
            )
        }
        prefs.edit().putString(poolKey, arr.toString()).apply()
    }

    fun recallEndpointPoolForNetwork(
        networkKey: String,
        mode: AetherMode,
        ipScan: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<AetherCachedEndpoint> {
        if (networkKey.isBlank()) return emptyList()
        val family = if (mode == AetherMode.WireGuard) "warp" else "masque"
        val poolKey = "netpool_${networkKey}_${family}_$ipScan"
        val raw = prefs.getString(poolKey, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val maxAge = 7L * 24 * 60 * 60 * 1000L
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val peer = obj.optString("peer").trim().takeIf(AetherOperations::isSafePublicPeer) ?: continue
                    val savedAt = obj.optLong("savedAt", 0L)
                    val age = nowMs - savedAt
                    if (age !in 0L..maxAge) continue
                    val rttMs = obj.optLong("rttMs", 0L).coerceAtLeast(0L)
                    add(AetherCachedEndpoint(peer = peer, rttMs = rttMs, savedAt = savedAt, networkKey = networkKey))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun nextFailoverEndpointForNetwork(
        networkKey: String,
        mode: AetherMode,
        ipScan: String,
        failedPeer: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val pool = recallEndpointPoolForNetwork(networkKey, mode, ipScan, nowMs)
        return pool.firstOrNull { !it.peer.equals(failedPeer?.trim(), ignoreCase = true) }?.peer
    }

    companion object {
        /**
         * Identifies the underlying non-VPN network (SIM 1 vs SIM 2 operator MCC+MNC or Wi-Fi gateway/DNS)
         * without requiring any extra permissions.
         */
        fun underlyingNetworkKey(context: Context): String = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return "default"
            fun usable(net: android.net.Network): Boolean {
                val cap = cm.getNetworkCapabilities(net) ?: return false
                return cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
            @Suppress("DEPRECATION")
            val net = cm.activeNetwork?.takeIf(::usable) ?: cm.allNetworks.firstOrNull(::usable) ?: return "default"
            val cap = cm.getNetworkCapabilities(net) ?: return "default"
            when {
                cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                    val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    val scoped = if (tm != null && subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        tm.createForSubscriptionId(subId)
                    } else tm
                    val op = scoped?.networkOperator?.trim().orEmpty().ifEmpty { "mobile" }
                    "cell:$op"
                }
                cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val lp = cm.getLinkProperties(net)
                    val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress.orEmpty()
                    val dns = lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted().joinToString(",")
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest("wifi|$gw|$dns".toByteArray(Charsets.UTF_8))
                        .take(6)
                        .joinToString("") { "%02x".format(it) }
                    "wifi:$digest"
                }
                else -> "other"
            }
        }.getOrDefault("default")
    }
}
