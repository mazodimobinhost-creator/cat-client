package com.cat.client

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dedicated TUIC / WireGuard / Cat Warp editors with a Connect button. */
internal fun MainActivity.showProtocolConnectDialog(draft: ProtocolConnect.Draft) {
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }
    fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        setPadding(0, dp(8), 0, dp(2))
    }
    fun field(value: String, hint: String, password: Boolean = false): EditText {
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        box.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }
    box.addView(label(if (fa) "نام" else "Name"))
    val name = field(draft.name, "Cat")
    box.addView(label(if (fa) "سرور" else "Server"))
    val server = field(draft.server, "host")
    box.addView(label(if (fa) "پورت" else "Port"))
    val port = field(draft.port.toString(), "443")
    val uuid: EditText?
    val password: EditText?
    val sni: EditText?
    val privateKey: EditText?
    val publicKey: EditText?
    val address: EditText?
    val aetherMode: EditText?
    val ipScan: EditText?
    when (draft.kind) {
        ProtocolConnect.Kind.Tuic -> {
            box.addView(label("UUID"))
            uuid = field(draft.uuid, "uuid")
            box.addView(label(if (fa) "رمز" else "Password"))
            password = field(draft.password, "password", password = true)
            box.addView(label("SNI"))
            sni = field(draft.sni, "sni")
            privateKey = null
            publicKey = null
            address = null
            aetherMode = null
            ipScan = null
        }
        ProtocolConnect.Kind.WireGuard -> {
            uuid = null
            password = null
            sni = null
            box.addView(label(if (fa) "کلید خصوصی (خالی = auto / WARP)" else "Private key (blank = auto / WARP)"))
            privateKey = field(draft.privateKey, "auto", password = true)
            box.addView(label(if (fa) "کلید عمومی همتا" else "Peer public key"))
            publicKey = field(draft.publicKey, WarpRegistration.WARP_PEER_PUBLIC_KEY)
            box.addView(label(if (fa) "آدرس اینترفیس" else "Interface address"))
            address = field(draft.address, "172.16.0.2/32")
            aetherMode = null
            ipScan = null
        }
        ProtocolConnect.Kind.Aether -> {
            uuid = null
            password = null
            sni = null
            privateKey = null
            publicKey = null
            address = null
            box.addView(label(if (fa) "حالت (masque / wg / gool / mim)" else "Mode (masque / wg / gool / mim)"))
            aetherMode = field(draft.aetherMode, "masque")
            box.addView(label(if (fa) "خانواده آی‌پی (v4 / v6 / both)" else "IP family (v4 / v6 / both)"))
            ipScan = field(draft.ipScan, "v4")
        }
    }
    val scroll = ScrollView(this).apply { addView(box) }
    val title = when (draft.kind) {
        ProtocolConnect.Kind.Tuic -> if (fa) "TUIC — فیلدها + وصل" else "TUIC — fields + Connect"
        ProtocolConnect.Kind.WireGuard -> if (fa) "WireGuard — فیلدها + وصل" else "WireGuard — fields + Connect"
        ProtocolConnect.Kind.Aether -> if (fa) "Cat Warp — فیلدها + وصل" else "Cat Warp — fields + Connect"
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (fa) "ذخیره و وصل" else "Save and connect") { _, _ ->
            val filled = draft.copy(
                name = name.text?.toString()?.trim().orEmpty().ifBlank { draft.name },
                server = server.text?.toString()?.trim().orEmpty(),
                port = port.text?.toString()?.trim()?.toIntOrNull() ?: draft.port,
                uuid = uuid?.text?.toString()?.trim().orEmpty(),
                password = password?.text?.toString().orEmpty(),
                sni = sni?.text?.toString()?.trim().orEmpty(),
                privateKey = privateKey?.text?.toString()?.trim().orEmpty().ifBlank { "auto" },
                publicKey = publicKey?.text?.toString()?.trim().orEmpty().ifBlank { WarpRegistration.WARP_PEER_PUBLIC_KEY },
                address = address?.text?.toString()?.trim().orEmpty().ifBlank { "172.16.0.2/32" },
                aetherMode = aetherMode?.text?.toString()?.trim().orEmpty().ifBlank { "masque" },
                ipScan = ipScan?.text?.toString()?.trim().orEmpty().ifBlank { "v4" },
            )
            if (filled.kind != ProtocolConnect.Kind.Aether && filled.server.isBlank()) {
                Toast.makeText(this, if (fa) "سرور خالی است" else "Server is empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (filled.kind == ProtocolConnect.Kind.Tuic && filled.uuid.isBlank()) {
                Toast.makeText(this, if (fa) "UUID خالی است" else "UUID is empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (filled.kind == ProtocolConnect.Kind.Aether) {
                importAndConnectAetherSelection(ProtocolConnect.toAetherSelection(filled))
            } else {
                addSingleConfig(ProtocolConnect.toShareUri(filled), filled.name.ifBlank { "Cat" }, connectNow = true)
            }
        }
        .show()
}
