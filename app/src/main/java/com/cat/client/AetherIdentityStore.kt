package com.cat.client

import java.io.File
import java.util.Base64

/**
 * Read-only view of the WARP identities the Cat Warp core keeps next to `aether.toml`.
 *
 * Core 2.4 keeps one record per Cloudflare device in `aether-store.toml` (`[devices.<id>]` + `[slots]`) and still
 * writes each slot to its old file for one release: `aether.toml` (WireGuard), `aether-secondary.toml`
 * (WireGuard inner hop), `aether-masque.toml` and `aether-masque-secondary.toml` (MASQUE outer / inner hop).
 *
 * Used for two things the user asked for:
 *  - show «our WARP key» the way the PattNG log does (`Outer hop key: device 64c4…, IPv4 …, IPv6 …`);
 *  - give the built-in `awg://auto@…` WireGuard rows a key that was registered through the core's own
 *    fingerprinted/fragmented registration (works on networks where a plain request to the WARP API is blocked).
 *
 * Never writes, never logs secrets (only an 8-char device prefix leaves this object).
 */
internal object AetherIdentityStore {
    data class Device(
        val slot: String,
        val deviceId: String,
        val ipv4: String,
        val ipv6: String,
        val tunnelType: String,
        val refused: Boolean,
        val enrolmentPending: Boolean,
        val hasCertificate: Boolean,
        val wgPrivateKey: String,
        val wgPeerPublicKey: String,
        val clientId: String,
    ) {
        /** Same rule as the core's `wireguard_is_trustworthy`: a MASQUE enrolment destroys the WireGuard key. */
        val wireGuardUsable: Boolean
            get() = tunnelType.equals("wireguard", ignoreCase = true) && !refused && !enrolmentPending &&
                validKey(wgPrivateKey) && validKey(wgPeerPublicKey)

        val shortId: String get() = deviceId.take(8).ifBlank { "?" }
    }

    val SLOT_ORDER = listOf("masque", "masque_inner", "wireguard", "wireguard_inner")

    fun sibling(base: File, suffix: String): File {
        val name = base.name
        val dot = name.lastIndexOf('.')
        val derived = if (dot > 0) "${name.substring(0, dot)}-$suffix${name.substring(dot)}" else "$name-$suffix"
        return File(base.parentFile ?: File("."), derived)
    }

    fun storeFile(base: File): File = sibling(base, "store")

    /** Every filled slot, store first, legacy files as a fallback. */
    fun read(base: File): List<Device> {
        val fromStore = runCatching {
            storeFile(base).takeIf { it.isFile && it.length() in 1..2_000_000 }?.readText()?.let(::parseStore)
        }.getOrNull().orEmpty()
        if (fromStore.isNotEmpty()) return fromStore
        val masque = sibling(base, "masque")
        val legacy = listOf(
            "wireguard" to base,
            "wireguard_inner" to sibling(base, "secondary"),
            "masque" to masque,
            "masque_inner" to sibling(masque, "secondary"),
        )
        return legacy.mapNotNull { (slot, file) ->
            runCatching {
                file.takeIf { it.isFile && it.length() in 1..2_000_000 }?.readText()?.let { parseLegacy(it, slot) }
            }.getOrNull()
        }.sortedBy { SLOT_ORDER.indexOf(it.slot) }
    }

    /** The WireGuard device as a [WarpRegistration.Account], only when Cloudflare still holds its WireGuard key. */
    fun wireGuardAccount(base: File): WarpRegistration.Account? =
        read(base).firstOrNull { it.slot == "wireguard" && it.wireGuardUsable }?.let(::toAccount)

    fun toAccount(d: Device): WarpRegistration.Account? {
        if (!d.wireGuardUsable) return null
        val reserved = runCatching { Base64.getDecoder().decode(d.clientId) }.getOrNull()
            ?.takeIf { it.size >= 3 }?.take(3)?.map { it.toInt() and 0xFF }
        return WarpRegistration.Account(
            privateKey = d.wgPrivateKey,
            publicKey = "",
            peerPublicKey = d.wgPeerPublicKey,
            ipv4 = d.ipv4.substringBefore('/').ifBlank { "172.16.0.2" },
            ipv6 = d.ipv6.substringBefore('/').takeIf(String::isNotBlank),
            reserved = reserved,
            accountId = d.deviceId,
        )
    }

    /** PattNG-style lines: «Outer hop key: device 64c41a30…, IPv4 172.16.0.2, IPv6 2606:…». */
    fun describe(devices: List<Device>, farsi: Boolean): List<String> = devices.map { d ->
        val role = when (d.slot) {
            "masque" -> if (farsi) "کلید هاپ بیرونی MASQUE (Outer)" else "Outer hop key (MASQUE)"
            "masque_inner" -> if (farsi) "کلید هاپ داخلی MASQUE (Inner)" else "Inner hop key (MASQUE)"
            "wireguard" -> if (farsi) "کلید WireGuard" else "WireGuard key"
            else -> if (farsi) "کلید داخلی WireGuard (WARP in WARP)" else "Inner WireGuard key (WARP in WARP)"
        }
        val state = when {
            d.refused -> if (farsi) " ⛔ کلادفلر ردش کرده — «کلید جدید» بگیرید" else " ⛔ refused by Cloudflare — get new keys"
            d.slot.startsWith("wireguard") && !d.wireGuardUsable ->
                if (farsi) " ⚠️ برای WireGuard قابل استفاده نیست" else " ⚠️ not usable for WireGuard"
            else -> ""
        }
        buildString {
            append(role).append(": device ").append(d.shortId).append("…")
            if (d.ipv4.isNotBlank()) append(", IPv4 ").append(d.ipv4.substringBefore('/'))
            if (d.ipv6.isNotBlank()) append(", IPv6 ").append(d.ipv6.substringBefore('/'))
            append(state)
        }
    }

    private fun validKey(b64: String): Boolean =
        runCatching { Base64.getDecoder().decode(b64.trim()).size == 32 }.getOrDefault(false)

    // ---------------------------------------------------------------- tiny TOML reader (only what the core writes)

    internal fun parseStore(text: String): List<Device> {
        val tables = parseTables(text)
        val slots = tables["slots"].orEmpty()
        return SLOT_ORDER.mapNotNull { slot ->
            val id = slots[slot]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val fields = tables["devices.$id"] ?: return@mapNotNull null
            deviceFrom(slot, id, fields)
        }
    }

    internal fun parseLegacy(text: String, slot: String): Device? {
        val fields = parseTables(text)[""] ?: return null
        val id = fields["device_id"].orEmpty()
        if (id.isBlank()) return null
        val hasCert = fields["cert_pem"].orEmpty().isNotBlank()
        // A legacy file cannot say whether a MASQUE enrolment replaced the key; a certificate means it did.
        val type = if (slot.startsWith("masque") || hasCert) "masque" else "wireguard"
        return deviceFrom(slot, id, fields + ("tunnel_type" to type))
    }

    private fun deviceFrom(slot: String, id: String, f: Map<String, String>) = Device(
        slot = slot,
        deviceId = id,
        ipv4 = f["ipv4"].orEmpty(),
        ipv6 = f["ipv6"].orEmpty(),
        tunnelType = f["tunnel_type"].orEmpty().ifBlank { "wireguard" },
        refused = (f["refused_at"]?.toLongOrNull() ?: 0L) != 0L,
        enrolmentPending = (f["enrolment_pending_since"]?.toLongOrNull() ?: 0L) != 0L,
        hasCertificate = f["cert_pem"].orEmpty().isNotBlank() && f["key_pem"].orEmpty().isNotBlank(),
        wgPrivateKey = f["wg_private_key"].orEmpty(),
        wgPeerPublicKey = f["wg_peer_public_key"].orEmpty(),
        clientId = f["client_id"].orEmpty(),
    )

    /** table name ("" = root, `devices.<id>` with quotes removed) → key → scalar value as text. */
    internal fun parseTables(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var table = ""
        out[table] = LinkedHashMap()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            i++
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && !line.startsWith("[[")) {
                table = line.removePrefix("[").substringBeforeLast("]").replace("\"", "").replace("'", "")
                    .split('.').joinToString(".") { it.trim() }
                out.getOrPut(table) { LinkedHashMap() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().removeSurrounding("\"")
            val raw = line.substring(eq + 1).trim()
            val multi = listOf("\"\"\"", "'''").firstOrNull { raw.startsWith(it) }
            val value = if (multi != null) {
                val sb = StringBuilder(raw.removePrefix(multi))
                while (!sb.contains(multi) && i < lines.size) { sb.append('\n').append(lines[i]); i++ }
                sb.toString().substringBefore(multi)
            } else when {
                raw.startsWith("\"") -> unescape(closeBasicString(raw.drop(1)))
                raw.startsWith("'") -> raw.drop(1).substringBefore('\'')
                else -> raw.substringBefore('#').trim()
            }
            out.getOrPut(table) { LinkedHashMap() }[key] = value
        }
        return out
    }

    private fun closeBasicString(s: String): String {
        var k = 0
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k += 2
                '"' -> return s.substring(0, k)
                else -> k += 1
            }
        }
        return s
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length) {
                when (val n = s[k + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(n)
                }
                k += 2
            } else { sb.append(c); k++ }
        }
        return sb.toString()
    }
}
