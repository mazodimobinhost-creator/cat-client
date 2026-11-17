/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.cat.client

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Integrity and input helpers for the opt-in, third-party TechNamooz Worker
 * installer. The upstream bundle is fetched only when the user deploys it; it
 * is deliberately not copied into this repository or APK.
 *
 * A matching SHA-256 proves only that the downloaded bytes match this pin. It
 * does not mean the external code has been audited or is safe.
 */
object TechNamoozWorkerSource {
    const val REPOSITORY = "technamooz/panel-technamooz-VPN-cloudflare"
    const val PINNED_COMMIT = "c88d2bfc0a0a123b9da791e8a3f5b398305e78a8"
    const val EXPECTED_SIZE_BYTES = 406_285
    const val EXPECTED_SHA256 = "1c475c36a7cd720c23b5ec236eeb5548e14fbf90ee8f90d449ecb704ec1e7829"

    private val requiredMarkers = listOf(
        "UUID",
        "TR_PASS",
        "SUB_PATH",
        "PROXY_IP",
        "PREFIX",
        "api.cloudflareclient.com",
        "ip-api.com",
        "case\"panel\"",
    )
    private val secureRandom = SecureRandom()
    private const val SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** Verifies the exact pinned bytes and returns UTF-8 source for upload. */
    fun verifyAndDecode(bytes: ByteArray): String {
        require(bytes.size == EXPECTED_SIZE_BYTES) {
            "Pinned TechNamooz bundle size mismatch (${bytes.size} bytes). No Cloudflare resources were changed."
        }
        val digest = sha256Hex(bytes)
        require(digest.equals(EXPECTED_SHA256, ignoreCase = true)) {
            "Pinned TechNamooz bundle SHA-256 mismatch. No Cloudflare resources were changed."
        }
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        require(requiredMarkers.all { marker -> decoded.contains(marker) }) {
            "Pinned TechNamooz bundle did not contain the expected setup markers. No Cloudflare resources were changed."
        }
        return decoded
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun normalizeWorkerName(raw: String): String = raw.trim().lowercase(Locale.US)

    fun isValidWorkerName(name: String): Boolean =
        name.length in 1..63 && name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))

    fun isValidPanelPassword(password: String): Boolean =
        password.length in 16..128 && password.none { it.isWhitespace() }

    /** Cryptographically random ASCII secret for the private subscription path. */
    fun newSecret(length: Int = 32): String {
        require(length in 16..128)
        return buildString(length) {
            repeat(length) {
                append(SECRET_ALPHABET[secureRandom.nextInt(SECRET_ALPHABET.length)])
            }
        }
    }

    fun normalXraySubscriptionUrl(workerUrl: String, subPath: String): String =
        "${workerUrl.trimEnd('/')}/sub/normal/$subPath?app=xray"
}
