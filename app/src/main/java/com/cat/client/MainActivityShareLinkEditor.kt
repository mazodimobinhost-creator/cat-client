package com.cat.client

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v2rayNG / V2Box-style sectioned editor for one share link. */
internal fun MainActivity.showShareLinkEditor(
    draft: ShareLinkDraft,
    onSave: (String) -> Unit,
) {
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(p: String, e: String) = if (fa) p else e
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
    }
    fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        setPadding(0, dp(12), 0, dp(2))
    }
    fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        setPadding(0, dp(2), 0, dp(2))
    }
    fun field(value: String, hint: String, password: Boolean = false, number: Boolean = false): EditText {
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        box.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }
    val kind = draft.type.lowercase()
    box.addView(heading(t("عمومی", "General")))
    box.addView(caption(t("پروتکل: $kind", "Protocol: $kind")))
    val name = field(draft.name, t("نام / Remarks", "Name / remarks"))
    val server = field(draft.server, t("آدرس / Address", "Address"))
    val port = field(draft.port.toString(), t("پورت", "Port"), number = true)

    val uuid: EditText?
    val password: EditText?
    val alterId: EditText?
    val cipher: EditText?
    val flow: EditText?
    val encryption: EditText?
    val username: EditText?
    when (kind) {
        "vless", "vmess", "tuic" -> {
            box.addView(heading(t("کاربر", "User")))
            uuid = field(draft.uuid, "UUID / ID", password = true)
            password = if (kind == "tuic") field(draft.password, t("رمز", "Password"), password = true) else null
            alterId = if (kind == "vmess") field(draft.alterId, "alterId") else null
            cipher = when (kind) {
                "vmess" -> field(draft.cipher.ifBlank { "auto" }, t("رمزنگاری (scy)", "Cipher (scy)"))
                "tuic" -> field(draft.cipher.ifBlank { "bbr" }, "congestion (bbr)")
                else -> null
            }
            flow = if (kind == "vless") field(draft.flow, "flow (xtls-rprx-vision)") else null
            encryption = if (kind == "vless") field(draft.encryption.ifBlank { "none" }, "encryption") else null
            username = null
        }
        "trojan", "ss", "hysteria2", "hy2", "hysteria", "anytls" -> {
            box.addView(heading(t("کاربر", "User")))
            uuid = null
            password = field(draft.password, t("رمز / Password", "Password"), password = true)
            alterId = null
            cipher = if (kind == "ss") field(draft.cipher, t("متد SS", "SS method")) else null
            flow = null
            encryption = null
            username = null
        }
        "socks5", "socks", "http" -> {
            box.addView(heading(t("کاربر", "User")))
            uuid = null
            username = field(draft.username, t("نام کاربری", "Username"))
            password = field(draft.password, t("رمز", "Password"), password = true)
            alterId = null
            cipher = null
            flow = null
            encryption = null
        }
        "wireguard" -> {
            box.addView(heading("WireGuard"))
            uuid = null
            password = null
            alterId = null
            cipher = null
            flow = null
            encryption = null
            username = null
        }
        else -> {
            uuid = null
            password = null
            alterId = null
            cipher = null
            flow = null
            encryption = null
            username = null
        }
    }

    val showTransport = kind in setOf("vless", "vmess", "trojan", "anytls")
    val network: EditText?
    val path: EditText?
    val host: EditText?
    val serviceName: EditText?
    val headerType: EditText?
    if (showTransport) {
        box.addView(heading(t("شبکه / Transport", "Network / transport")))
        box.addView(caption(t("tcp · ws · grpc · httpupgrade · h2 · xhttp", "tcp · ws · grpc · httpupgrade · h2 · xhttp")))
        network = field(draft.network.ifBlank { "tcp" }, "network")
        headerType = field(draft.headerType, "headerType (none / http)")
        host = field(draft.host, t("Host (وب‌سوکت)", "Host (WebSocket)"))
        path = field(draft.path, t("path", "path"))
        serviceName = field(draft.serviceName, "gRPC serviceName")
    } else {
        network = null
        path = null
        host = null
        serviceName = null
        headerType = null
    }

    val showTls = kind in setOf("vless", "vmess", "trojan", "anytls", "tuic")
    val security: EditText?
    val sni: EditText?
    val fingerprint: EditText?
    val alpn: EditText?
    val publicKey: EditText?
    val shortId: EditText?
    val spiderX: EditText?
    if (showTls || kind in setOf("hysteria2", "hy2", "hysteria")) {
        box.addView(heading(t("TLS / Reality", "TLS / Reality")))
        security = if (showTls) field(draft.security.ifBlank { "tls" }, "security (none / tls / reality)") else null
        sni = field(draft.sni, "SNI / serverName")
        fingerprint = if (showTls) field(draft.fingerprint, t("Fingerprint (chrome / firefox / …)", "Fingerprint (chrome / firefox / …)")) else null
        alpn = field(draft.alpn, "alpn (h2,http/1.1)")
        publicKey = if (showTls) field(draft.publicKey, t("کلید عمومی Reality (pbk)", "Reality public key (pbk)"), password = true) else null
        shortId = if (showTls) field(draft.shortId, "shortId (sid)") else null
        spiderX = if (kind == "vless") field(draft.spiderX, "spiderX (spx)") else null
        box.addView(caption(t("بررسی گواهی همیشه روشن است (skip-cert-verify نمی‌شود).", "Certificate checks stay on (no skip-cert-verify).")))
    } else {
        security = null
        sni = null
        fingerprint = null
        alpn = null
        publicKey = null
        shortId = null
        spiderX = null
    }

    val obfs: EditText?
    val obfsPassword: EditText?
    val ports: EditText?
    if (kind in setOf("hysteria2", "hy2", "hysteria")) {
        box.addView(heading("Hysteria"))
        obfs = field(draft.obfs, "obfs (salamander)")
        obfsPassword = field(draft.obfsPassword, "obfs-password", password = true)
        ports = field(draft.ports, "mport / ports")
    } else {
        obfs = null
        obfsPassword = null
        ports = null
    }

    val plugin: EditText? = if (kind == "ss") {
        box.addView(heading("Shadowsocks plugin"))
        field(draft.plugin, "plugin")
    } else {
        null
    }

    val privateKey: EditText?
    val peerPublicKey: EditText?
    val address: EditText?
    val reserved: EditText?
    if (kind == "wireguard") {
        privateKey = field(draft.privateKey.ifBlank { "auto" }, t("کلید خصوصی", "Private key"), password = true)
        peerPublicKey = field(draft.peerPublicKey, t("کلید عمومی همتا", "Peer public key"))
        address = field(draft.address.ifBlank { "172.16.0.2/32" }, t("آدرس اینترفیس", "Interface address"))
        reserved = field(draft.reserved, "reserved")
    } else {
        privateKey = null
        peerPublicKey = null
        address = null
        reserved = null
    }

    val mode: EditText? = if (kind == "tuic") field(draft.mode.ifBlank { "native" }, "udp_relay_mode") else null

    MaterialAlertDialogBuilder(this)
        .setTitle(t("✏️ ویرایش کانفیگ — $kind", "✏️ Edit config — $kind"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(t("💾 ذخیره", "💾 Save")) { _, _ ->
            val filled = draft.copy(
                name = name.text?.toString()?.trim().orEmpty(),
                server = server.text?.toString()?.trim().orEmpty(),
                port = port.text?.toString()?.trim()?.toIntOrNull() ?: draft.port,
                uuid = uuid?.text?.toString()?.trim().orEmpty().ifBlank { draft.uuid },
                password = password?.text?.toString().orEmpty().ifBlank { draft.password },
                alterId = alterId?.text?.toString()?.trim().orEmpty().ifBlank { draft.alterId },
                cipher = cipher?.text?.toString()?.trim().orEmpty().ifBlank { draft.cipher },
                flow = flow?.text?.toString()?.trim().orEmpty(),
                encryption = encryption?.text?.toString()?.trim().orEmpty().ifBlank { draft.encryption },
                network = network?.text?.toString()?.trim().orEmpty().ifBlank { draft.network },
                path = path?.text?.toString()?.trim().orEmpty(),
                host = host?.text?.toString()?.trim().orEmpty(),
                serviceName = serviceName?.text?.toString()?.trim().orEmpty(),
                headerType = headerType?.text?.toString()?.trim().orEmpty(),
                security = security?.text?.toString()?.trim().orEmpty().ifBlank { draft.security },
                sni = sni?.text?.toString()?.trim().orEmpty(),
                fingerprint = fingerprint?.text?.toString()?.trim().orEmpty(),
                alpn = alpn?.text?.toString()?.trim().orEmpty(),
                publicKey = publicKey?.text?.toString()?.trim().orEmpty(),
                shortId = shortId?.text?.toString()?.trim().orEmpty(),
                spiderX = spiderX?.text?.toString()?.trim().orEmpty(),
                obfs = obfs?.text?.toString()?.trim().orEmpty(),
                obfsPassword = obfsPassword?.text?.toString().orEmpty(),
                ports = ports?.text?.toString()?.trim().orEmpty(),
                plugin = plugin?.text?.toString()?.trim().orEmpty(),
                username = username?.text?.toString()?.trim().orEmpty(),
                privateKey = privateKey?.text?.toString()?.trim().orEmpty(),
                peerPublicKey = peerPublicKey?.text?.toString()?.trim().orEmpty(),
                address = address?.text?.toString()?.trim().orEmpty(),
                reserved = reserved?.text?.toString()?.trim().orEmpty(),
                mode = mode?.text?.toString()?.trim().orEmpty(),
            )
            if (filled.server.isBlank() || ConnectionPublicEndpoint.isLoopbackHost(filled.server)) {
                Toast.makeText(this, t("آدرس خالی است یا 127.0.0.1 است.", "Address is empty or 127.0.0.1."), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val link = filled.toLink()
            if (link.isNullOrBlank()) {
                Toast.makeText(this, t("این فیلدها لینک درستی نساختند.", "These fields did not build a valid link."), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            onSave(link)
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.replaceSubscriptionLine(item: UserSubscription, lineIndex: Int, newLink: String) {
    val lines = item.input.lines().toMutableList()
    if (lineIndex !in lines.indices) {
        Toast.makeText(this, if (appLanguagePreferenceStore.read() == AppLanguage.Persian) "ذخیره نشد." else "Not saved.", Toast.LENGTH_SHORT).show()
        return
    }
    lines[lineIndex] = newLink
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    activityScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                userSubscriptionManager.update(item.id, item.name, lines.joinToString("\n"))
            }
        }.onSuccess {
            renderSubscriptions()
            Toast.makeText(this@replaceSubscriptionLine, if (fa) "✅ ذخیره شد" else "✅ Saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this@replaceSubscriptionLine, if (fa) "ذخیره نشد." else "Not saved.", Toast.LENGTH_LONG).show()
        }
    }
}
