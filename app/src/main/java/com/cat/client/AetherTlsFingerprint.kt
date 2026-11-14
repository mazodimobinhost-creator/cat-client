package com.cat.client

import org.json.JSONObject

/**
 * The TLS ClientHello the native Aether core (2.4) presents to Cloudflare's MASQUE edge.
 *
 * beta206: until now Cat sent every profile a hand-made cipher/group list *and* `tlsKeyShares` equal to the
 * whole group list (four key shares, one of them a 1.2 KB ML-KEM share). No browser sends that, so DPI that
 * fingerprints ClientHellos could single it out, while PattNG's identical-looking `PattNG-MIM-H2-DE` profile
 * connected. The presets below are the browser captures (October 2026) the Aether 2.4 core itself tests
 * against and PattNG passes as `--tls-*` flags; only option *values* are reused, no code. A blank/unknown
 * choice means Chrome, PattNG's default.
 */
internal data class AetherTlsFingerprint(
    val type: String,
    val ciphers: String,
    val grease: Boolean,
    val groups: String,
    val sigalgs: String,
    val tls13Ciphers: String? = null,
    val keyShares: String? = null,
    val greaseSigalgs: Boolean = false,
    val certCompression: String? = null,
    val alps: String? = null,
    val trustAnchors: String? = null,
    val serverPadding: Int? = null,
    val echGrease: Boolean = false,
    val ocspStapling: Boolean = true,
    val sct: Boolean = true,
    val sessionTicket: Boolean = true,
    val permuteExtensions: Boolean = true,
) {
    /** Writes the bridge's camelCase keys (Aether 2.4 `BridgeConfig`). Older cores ignore unknown keys. */
    fun applyTo(json: JSONObject) {
        json.put("tlsCiphers", ciphers)
        json.put("tlsGroups", groups)
        json.put("tlsSigalgs", sigalgs)
        json.put("disableGrease", !grease)
        tls13Ciphers?.let { json.put("tls13Ciphers", it) }
        keyShares?.let { json.put("tlsKeyShares", it) }
        certCompression?.let { json.put("tlsCertCompression", it) }
        alps?.let { json.put("tlsAlps", it) }
        trustAnchors?.let { json.put("tlsTrustAnchors", it) }
        serverPadding?.let { json.put("tlsServerPadding", it.toString()) }
        json.put("enableGreaseSigalgs", greaseSigalgs)
        json.put("enable_grease_sigalgs", greaseSigalgs)
        json.put("enableEchGrease", echGrease)
        json.put("enable_ech_grease", echGrease)
        json.put("disableOcspStapling", !ocspStapling)
        json.put("disableSct", !sct)
        json.put("disableSessionTicket", !sessionTicket)
        json.put("disableExtensionPermutation", !permuteExtensions)
    }

    companion object {
        private const val CHROME_EIGHT_SIGALGS =
            "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:" +
                "ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha512"
        private const val CHROME_TRUST_ANCHORS =
            "44947.2.1:44947.2.6:44947.2.13:44947.2.14:44947.2.15:44947.2.18:44947.2.19:" +
                "44947.2.20:52580.200109.1.7:52580.200109.1.8:52580.200109.1.9:52580.200109.1.10:52580.200109.1.11:" +
                "52580.200109.1.12:52580.200109.1.13:52580.200109.1.18:52580.200109.1.19:11129.9.1:11129.9.4:11129.9.5:" +
                "11129.9.6:11129.9.7:11129.9.8:11129.9.10:11129.9.11:11129.9.12:11129.9.13:11129.9.15"

        val CHROME = AetherTlsFingerprint(
            type = "chrome",
            ciphers = "ALL:!aPSK:!ECDSA+SHA1:!3DES",
            grease = true,
            groups = "X25519MLKEM768:X25519:P-256:P-384",
            sigalgs = "mldsa44:mldsa65:mldsa87:$CHROME_EIGHT_SIGALGS",
            greaseSigalgs = true,
            certCompression = "brotli",
            alps = "new",
            trustAnchors = CHROME_TRUST_ANCHORS,
            serverPadding = 6000,
            echGrease = true,
        )

        val CHROME_120 = AetherTlsFingerprint(
            type = "chrome-120",
            ciphers = "ALL:!aPSK:!ECDSA+SHA1:!3DES",
            grease = true,
            groups = "X25519:P-256:P-384",
            sigalgs = CHROME_EIGHT_SIGALGS,
            certCompression = "brotli",
            alps = "old",
            echGrease = true,
        )

        val FIREFOX = AetherTlsFingerprint(
            type = "firefox",
            ciphers = "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:" +
                "ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:" +
                "ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA:AES256-SHA",
            grease = false,
            groups = "X25519MLKEM768:X25519:P-256:P-384:P-521",
            sigalgs = "ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:rsa_pss_rsae_sha256:" +
                "rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:" +
                "ecdsa_sha1:rsa_pkcs1_sha1",
            tls13Ciphers = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
            keyShares = "X25519MLKEM768:X25519:P-256",
            certCompression = "zlib:brotli:zstd",
            echGrease = true,
            sessionTicket = false,
            permuteExtensions = false,
        )

        val SEMI_PYTHON = AetherTlsFingerprint(
            type = "semi-python",
            ciphers = "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:" +
                "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:" +
                "ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256",
            grease = false,
            groups = "X25519MLKEM768:X25519:P-256:P-384:P-521",
            sigalgs = "mldsa65:mldsa87:mldsa44:ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:" +
                "ed25519:rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:" +
                "rsa_pkcs1_sha512",
            tls13Ciphers = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256",
            ocspStapling = false,
            sct = false,
            permuteExtensions = false,
        )

        val GO = AetherTlsFingerprint(
            type = "go",
            ciphers = "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:" +
                "ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:" +
                "ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA",
            grease = false,
            groups = "X25519MLKEM768:X25519:P-256:P-384:P-521",
            sigalgs = "mldsa44:mldsa65:mldsa87:rsa_pss_rsae_sha256:ecdsa_secp256r1_sha256:ed25519:rsa_pss_rsae_sha384:" +
                "rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:ecdsa_secp384r1_sha384:" +
                "ecdsa_secp521r1_sha512",
            sessionTicket = false,
            permuteExtensions = false,
        )

        val ALL = listOf(CHROME, CHROME_120, FIREFOX, SEMI_PYTHON, GO)

        fun of(raw: String?): AetherTlsFingerprint {
            val key = raw?.trim()?.lowercase().orEmpty()
            return ALL.firstOrNull { it.type == key } ?: CHROME
        }
    }
}
