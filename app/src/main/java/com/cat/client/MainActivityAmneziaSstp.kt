package com.cat.client

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.text.DateFormat
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.cat.client.MainActivity.AmneziaPreview

// Amnezia / WireGuard config dialogs and Cat SSTP (VPN Gate) dialogs.
// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.

internal fun MainActivity.showAmneziaSectionDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    box.addView(
        TextView(this).apply {
            text = t(
                "کلید Amnezia همان متنی است که با vpn:// شروع می‌شود (یا فایل ‎.conf / ‎.vpn). اینجا بدهیدش؛ برنامه خودش می‌خواند، نشان می‌دهد چه چیزی داخلش است و با یک دکمه وصل می‌شود. نصب برنامهٔ Amnezia لازم نیست.",
                "An Amnezia key is the text starting with vpn:// (or a .conf / .vpn file). Give it here: the app reads it, shows what is inside and connects with one tap. The Amnezia app is not needed.",
            )
            textSize = 13.5f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(0f, 1.15f)
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    var dialog: androidx.appcompat.app.AlertDialog? = null
    fun action(label: String, accent: Boolean = false, run: () -> Unit) {
        box.addView(
            MaterialButton(this).apply {
                text = label
                isAllCaps = false
                textSize = 13.5f
                typeface = CatClientBodyBoldTypeface
                minHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                if (accent) {
                    backgroundTintList = ColorStateList.valueOf(TEAL)
                    setTextColor(palette.onAccent)
                } else {
                    backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 220))
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 140))
                    setTextColor(TEXT_PRIMARY)
                }
                setOnClickListener {
                    dialog?.dismiss()
                    run()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    action(t("📋 کلید را کپی کرده‌ام → بخوان و وصل شو", "📋 I copied the key → read it and connect"), accent = true) {
        val clip = readClipboardConfigText().orEmpty()
        if (clip.isBlank()) {
            Toast.makeText(this, t("کلیپ‌بورد خالی است؛ اول کلید vpn:// را کپی کنید.", "Clipboard is empty; copy the vpn:// key first."), Toast.LENGTH_LONG).show()
        } else {
            previewAndImportAmneziaText(clip)
        }
    }
    action(t("✍️ چسباندن یا نوشتن دستی (vpn:// یا متن ‎.conf)", "✍️ Paste or type it (vpn:// or .conf text)")) { showAmneziaPasteDialog() }
    action(t("📂 باز کردن فایل ‎.vpn یا ‎.conf", "📂 Open a .vpn or .conf file")) { appTools.openImportFilePicker() }
    action(t("📋 کانفیگ‌های Amnezia من (وصل شدن / حذف)", "📋 My Amnezia configs (connect / delete)")) { showMyAmneziaConfigsDialog() }
    action(t("🛡️ سرور رایگان AmneziaWG (روی WARP) + تبدیل پروتون/وایرگارد", "🛡️ Free AmneziaWG (over WARP) + convert Proton/WireGuard")) { showAmneziaAndProtonWgHubDialog() }
    action(t("🔑 کلید WARP ردیف‌های رایگان (کجاست؟ ساختن)", "🔑 WARP key of the free rows (where? create)")) { showWarpKeysStatusDialog() }
    action(t("❓ کلید Amnezia را از کجا بیاورم؟ چه چیزهایی کار می‌کند؟", "❓ Where do I get a key? What works?")) { showAmneziaHelpDialog() }
    val scroll = ScrollView(this).apply { addView(box) }
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(t("🔐 Amnezia (AmneziaVPN و AmneziaWG)", "🔐 Amnezia (AmneziaVPN & AmneziaWG)"))
        .setView(scroll)
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

internal fun MainActivity.showAmneziaPasteDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val input = EditText(this).apply {
        hint = if (isFarsi) "vpn://… یا [Interface] …" else "vpn://… or [Interface] …"
        textSize = 12.5f
        minLines = 5
        maxLines = 12
        gravity = Gravity.TOP or Gravity.START
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "✍️ کلید Amnezia یا متن ‎.conf" else "✍️ Amnezia key or .conf text")
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (isFarsi) "بخوان" else "Read") { _, _ ->
            previewAndImportAmneziaText(input.text?.toString().orEmpty())
        }
        .create()
        .showCatClientDialog()
}

/** Reads vpn:// keys or .conf text, shows what is inside, then adds (and optionally connects). */
internal fun MainActivity.previewAndImportAmneziaText(rawText: String) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val text = rawText.trim()
    if (text.isBlank()) return
    activityScope.launch {
        val preview: AmneziaPreview = withContext(Dispatchers.IO) {
            val keys = AmneziaVpnKey.extractKeys(text)
            if (keys.isNotEmpty()) {
                val results = keys.mapNotNull { AmneziaVpnKey.decode(it) }
                AmneziaPreview(
                    links = results.sumOf { it.links.size },
                    server = results.firstOrNull()?.serverName ?: "Amnezia",
                    ok = results.flatMap { it.protocols }.distinct(),
                    bad = results.flatMap { it.unsupported }.distinct(),
                    problem = if (results.isEmpty()) t("کلید خراب یا ناقص است (کامل کپی نشده؟)", "The key is broken or incomplete (copied fully?)")
                    else results.firstNotNullOfOrNull { if (isFarsi) it.problemFa else it.problemEn },
                )
            } else {
                val uris = WireGuardConfText.toUris(text)
                val endpoint = uris.firstOrNull()?.substringAfter('@')?.substringBefore('?').orEmpty()
                AmneziaPreview(
                    links = uris.size,
                    server = endpoint.ifBlank { "WireGuard" },
                    ok = uris.map { if (it.startsWith("awg://")) "AmneziaWG" else "WireGuard" }.distinct(),
                    bad = emptyList(),
                    problem = if (uris.isEmpty()) t(
                        "این متن نه کلید vpn:// است نه فایل ‎.conf کامل (PrivateKey، PublicKey و Endpoint لازم است).",
                        "This is neither a vpn:// key nor a complete .conf (PrivateKey, PublicKey and Endpoint are needed).",
                    ) else null,
                )
            }
        }
        val message = buildString {
            appendLine(t("🖥 سرور: ", "🖥 Server: ") + preview.server)
            if (preview.ok.isNotEmpty()) appendLine(t("✅ قابل اجرا: ", "✅ Runs here: ") + preview.ok.joinToString(", ") + " · " + t("${preview.links} کانفیگ", "${preview.links} config(s)"))
            if (preview.bad.isNotEmpty()) appendLine(t("⛔ اینجا اجرا نمی‌شود: ", "⛔ Cannot run here: ") + preview.bad.joinToString(", "))
            if (preview.links == 0 && preview.problem != null) {
                appendLine()
                appendLine(preview.problem)
            }
        }.trim()
        val builder = MaterialAlertDialogBuilder(this@previewAndImportAmneziaText)
            .setTitle(if (preview.links > 0) t("🔐 کلید خوانده شد", "🔐 Key read") else t("⚠️ قابل استفاده نیست", "⚠️ Cannot be used"))
            .setMessage(message)
        if (preview.links > 0) {
            builder
                .setPositiveButton(t("🚀 افزودن و اتصال", "🚀 Add & connect")) { _, _ -> saveAmneziaSubscription(text, preview.server, connect = true) }
                .setNeutralButton(t("➕ فقط افزودن", "➕ Add only")) { _, _ -> saveAmneziaSubscription(text, preview.server, connect = false) }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(t("باشه", "OK"), null)
                .setNeutralButton(t("❓ راهنما", "❓ Help")) { _, _ -> showAmneziaHelpDialog() }
        }
        builder.create().showCatClientDialog()
    }
}

internal fun MainActivity.saveAmneziaSubscription(text: String, serverName: String, connect: Boolean) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val name = "$amneziaSubscriptionPrefix · ${serverName.take(40)}"
    activityScope.launch {
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val existing = userSubscriptionManager.list().firstOrNull { it.name == name }
                if (existing != null) userSubscriptionManager.update(existing.id, name, text)
                else userSubscriptionManager.add(name, text)
            }
        }.getOrElse { error ->
            Toast.makeText(
                this@saveAmneziaSubscription,
                (if (isFarsi) "افزودن نشد: " else "Could not add: ") + (error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        Toast.makeText(
            this@saveAmneziaSubscription,
            if (isFarsi) "✅ «$name» اضافه شد (${saved.connectionCount} کانفیگ)" else "✅ Added “$name” (${saved.connectionCount} configs)",
            Toast.LENGTH_LONG,
        ).show()
        renderSubscriptions()
        if (connect) selectSubscriptionAndConnect(saved.id)
    }
}

/** Selects a subscription and connects (or reconnects onto it), turning off engines that would override it. */
internal fun MainActivity.selectSubscriptionAndConnect(subscriptionId: String) {
    userSubscriptionManager.select(subscriptionId)
    val curAether = aetherPreferenceStore.read()
    if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
    orbotEnginePreferenceStore.setEnabled(false)
    val wasStarted = buttonModel.state == VpnState.Started
    onSubscriptionSelected() // reconnects by itself when already connected
    renderAdvancedControls()
    openScreen(SCREEN_HOME)
    if (!wasStarted && buttonModel.state != VpnState.Starting) beginConnectFlow(Actions.CONNECT)
}

internal fun MainActivity.showMyAmneziaConfigsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    activityScope.launch {
        val items = withContext(Dispatchers.IO) {
            userSubscriptionManager.list().filter { sub ->
                sub.name.startsWith(amneziaSubscriptionPrefix) ||
                    AmneziaVpnKey.looksLikeKey(sub.input) ||
                    sub.input.trimStart().startsWith("awg://", ignoreCase = true) ||
                    sub.input.trimStart().startsWith("amneziawg://", ignoreCase = true)
            }
        }
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this@showMyAmneziaConfigsDialog)
                .setTitle(t("📋 کانفیگ Amnezia ندارید", "📋 No Amnezia configs yet"))
                .setMessage(t("هنوز کلید Amnezia اضافه نکرده‌اید. کلید vpn:// را کپی کنید و «📋 کلید را کپی کرده‌ام» را بزنید.", "No Amnezia key added yet. Copy a vpn:// key and tap “📋 I copied the key”."))
                .setPositiveButton(t("باشه", "OK"), null)
                .create().showCatClientDialog()
            return@launch
        }
        val selectedId = userSubscriptionManager.selectedId()
        val labels = items.map { sub ->
            (if (sub.id == selectedId) "● " else "") + sub.name + " · " + t("${sub.connectionCount} کانفیگ", "${sub.connectionCount} configs")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this@showMyAmneziaConfigsDialog)
            .setTitle(t("📋 کانفیگ‌های Amnezia من", "📋 My Amnezia configs"))
            .setItems(labels) { _, which ->
                val sub = items[which]
                MaterialAlertDialogBuilder(this@showMyAmneziaConfigsDialog)
                    .setTitle(sub.name)
                    .setItems(
                        arrayOf(t("🚀 وصل شو با این", "🚀 Connect with this"), t("🗑️ حذف", "🗑️ Delete")),
                    ) { _, action ->
                        when (action) {
                            0 -> selectSubscriptionAndConnect(sub.id)
                            1 -> MaterialAlertDialogBuilder(this@showMyAmneziaConfigsDialog)
                                .setTitle(t("حذف «${sub.name}»؟", "Delete “${sub.name}”?"))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(t("حذف", "Delete")) { _, _ ->
                                    activityScope.launch {
                                        withContext(Dispatchers.IO) { runCatching { userSubscriptionManager.delete(sub.id) } }
                                        renderSubscriptions()
                                        refreshLocationOptions()
                                    }
                                }
                                .create().showCatClientDialog(positiveColor = ERROR)
                        }
                    }
                    .create().showCatClientDialog()
            }
            .setNegativeButton(t("بستن", "Close"), null)
            .create().showCatClientDialog()
    }
}

    internal fun MainActivity.catSstpGuideText(isFarsi: Boolean): String = if (isFarsi) {
        """
        🌏 Cat SSTP چیه؟
        SSTP یه VPN استاندارده (همونی که ویندوز داره) که داخل HTTPS معمولی می‌ره. سرورهای رایگان داوطلبانه VPN Gate (بیشتر ژاپن و کره) با نام کاربری vpn و رمز vpn کار می‌کنن. سایت خود VPN Gate تو ایران فیلتره؛ برای همین فهرست از sstp.wireconf.workers.dev گرفته می‌شه.

        👆 «📥 دریافت سرورها» رو بزنی چی میشه؟
        • فهرست تازه سرورها گرفته می‌شه و یه اشتراک به اسم «🌏 Cat SSTP» ساخته (یا به‌روز) می‌شه.
        • «🚀 دریافت و اتصال» همون کار رو می‌کنه و بعد کل گوشی رو وصل می‌کنه.
        • اگه سرور اول جواب نده، برنامه خودش تا ۶ سرور بعدی رو امتحان می‌کنه.

        👆 بعدش چی؟
        • تو صفحه سرورها یکی از ردیف‌های «🐱 Cat · Japan · SSTP …» رو انتخاب کن و دکمه اتصال رو بزن.
        • «تست پینگ» یه دست‌دادن کامل SSTP انجام می‌ده (تا گرفتن آی‌پی). عددش از پینگ معمولی بزرگ‌تره ولی واقعیه؛ ردیفی که جواب نده یعنی الان روی خط شما کار نمی‌کنه.
        • عدد Mbps سرعتیه که خود سرور اعلام کرده، نه سرعت خط شما.
        • این سرورها هر روز عوض می‌شن؛ اگه بیشترشون جواب ندادن دوباره «📥 دریافت سرورها» رو بزن.

        ⚠️ صادقانه:
        • گواهی این سرورها خودامضاست و بررسی نمی‌شه؛ صاحب سرور داوطلبه. برای کارهای حساس فقط سایت‌های https (قفل‌دار) رو باز کن.
        • فقط IPv4 از تونل رد می‌شه؛ IPv6 بسته می‌شه که نشت نکنه.
        • روی بعضی خطوط SSTP شناسایی و بسته می‌شه؛ اون وقت یه روش دیگه (Cat Warp یا Cat Fox VPN) رو امتحان کن.
        • لینک دستی هم قبول می‌شه: sstp://vpn:vpn@host:port یا فقط اسم هاست مثل vpn123.opengw.net:1270
        """.trimIndent()
    } else {
        """
        🌏 What is Cat SSTP?
        SSTP is a standard VPN (the one built into Windows) carried inside ordinary HTTPS. The free volunteer VPN Gate servers (mostly Japan/Korea) accept user vpn / password vpn. The VPN Gate site is filtered in Iran, so the list comes from sstp.wireconf.workers.dev.

        👆 Tap “📥 Get servers”:
        • A fresh server list is downloaded into a subscription named “🌏 Cat SSTP” (created or updated).
        • “🚀 Get & connect” does the same and then connects the whole phone.
        • If the first server does not answer, the app tries up to 6 more by itself.

        👆 Then:
        • On the servers screen pick a “🐱 Cat · Japan · SSTP …” row and tap connect.
        • The ping test runs a full SSTP handshake (until an IP is assigned) — bigger than a normal ping but real; no answer means it does not work on your line right now.
        • The Mbps figure is the server's own advertised speed, not your line.
        • These servers change daily; if most fail, tap “📥 Get servers” again.

        ⚠️ Honestly:
        • Their certificates are self-signed and not verified; the operators are volunteers. Use only https sites for anything sensitive.
        • Only IPv4 goes through the tunnel; IPv6 is blocked so it cannot leak.
        • Some networks detect and block SSTP; then try another method (Cat Warp or Cat Fox VPN).
        • Manual links work too: sstp://vpn:vpn@host:port, or just a host such as vpn123.opengw.net:1270
        """.trimIndent()
    }

internal fun MainActivity.showCatSstpDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val sortLabels = arrayOf(
        t("⭐ ترتیب پیشنهادی فهرست", "⭐ List's recommended order"),
        t("⚡ پرسرعت‌ترین (سرعت اعلامی سرور)", "⚡ Fastest (server's advertised speed)"),
        t("🕐 پایدارترین (بیشترین روز روشن بودن)", "🕐 Most stable (longest uptime)"),
        t("🆕 تازه‌ترین سرورها", "🆕 Newest servers"),
    )
    var sortIndex = 0
    val message = TextView(this).apply {
        text = catSstpGuideText(isFarsi)
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(22), dp(8), dp(22), dp(8))
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
    }
    val sortGroup = android.widget.RadioGroup(this).apply {
        orientation = android.widget.RadioGroup.VERTICAL
        setPadding(dp(18), dp(4), dp(18), dp(8))
        sortLabels.forEachIndexed { i, label ->
            addView(
                android.widget.RadioButton(this@showCatSstpDialog).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 13.5f
                    minHeight = dp(44)
                    setTextColor(TEXT_PRIMARY)
                    isChecked = i == 0
                    setOnCheckedChangeListener { _, checked -> if (checked) sortIndex = i }
                },
            )
        }
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(message)
        addView(
            TextView(this@showCatSstpDialog).apply {
                text = t("ترتیب فهرست:", "Order:")
                textSize = 13.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                setPadding(dp(22), dp(8), dp(22), 0)
            },
        )
        addView(sortGroup)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(t("🌏 Cat SSTP — سرورهای رایگان SSTP", "🌏 Cat SSTP — free SSTP servers"))
        .setView(ScrollView(this).apply { addView(column) })
        .setPositiveButton(t("🚀 دریافت و اتصال", "🚀 Get & connect")) { _, _ ->
            fetchAndSaveCatSstpServers(SstpServerList.SORTS[sortIndex], connect = true)
        }
        .setNeutralButton(t("📥 فقط دریافت سرورها", "📥 Get servers only")) { _, _ ->
            fetchAndSaveCatSstpServers(SstpServerList.SORTS[sortIndex], connect = false)
        }
        .setNegativeButton(t("✍️ لینک دستی", "✍️ Manual link")) { _, _ -> showManualCatSstpDialog() }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.fetchAndSaveCatSstpServers(sort: String, connect: Boolean) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال گرفتن فهرست تازه سرورهای SSTP…" else "⏳ Downloading a fresh SSTP server list…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val servers = runCatching { withContext(Dispatchers.IO) { SstpServerList.fetch(sort, CAT_SSTP_LIST_LIMIT) } }
            .getOrElse { error ->
                MaterialAlertDialogBuilder(this@fetchAndSaveCatSstpServers)
                    .setTitle(if (isFarsi) "⚠️ فهرست سرورها گرفته نشد" else "⚠️ Could not get the server list")
                    .setMessage(
                        (if (isFarsi) {
                            "دلیل: ${error.message ?: error.javaClass.simpleName}\n\nاگه sstp.wireconf.workers.dev روی خط شما باز نمی‌شه، اول با یه روش دیگه وصل شو و دوباره بزن، یا لینک/هاست سرور رو دستی وارد کن."
                        } else {
                            "Reason: ${error.message ?: error.javaClass.simpleName}\n\nIf sstp.wireconf.workers.dev does not open on your line, connect another way first and retry, or enter a server link/host manually."
                        }),
                    )
                    .setPositiveButton(if (isFarsi) "✍️ لینک دستی" else "✍️ Manual link") { _, _ -> showManualCatSstpDialog() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
                return@launch
            }
        saveCatSstpSubscription(SstpServerList.subscriptionText(servers), connect)
    }
}

internal fun MainActivity.showManualCatSstpDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val input = EditText(this).apply {
        hint = "sstp://vpn:vpn@public-vpn-79.opengw.net:443"
        minLines = 3
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
        addView(
            TextView(this@showManualCatSstpDialog).apply {
                text = if (isFarsi) {
                    "هر خط یک سرور: sstp://user:pass@host:port یا فقط هاست VPN Gate مثل vpn123.opengw.net:1270 (نام کاربری و رمز پیش‌فرض vpn). پورت دقیقاً همونی که می‌نویسی استفاده می‌شه."
                } else {
                    "One server per line: sstp://user:pass@host:port, or just a VPN Gate host such as vpn123.opengw.net:1270 (default user/password vpn). The port is used exactly as written."
                }
                textSize = 12.5f
                setTextColor(TEXT_SECONDARY)
            },
        )
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "✍️ افزودن سرور SSTP" else "✍️ Add SSTP server")
        .setView(wrap)
        .setPositiveButton(if (isFarsi) "🚀 افزودن و اتصال" else "🚀 Add & connect") { _, _ ->
            val links = input.text.toString().lines().mapNotNull { SstpLink.parse(it) }
            if (links.isEmpty()) {
                Toast.makeText(
                    this,
                    if (isFarsi) "⚠️ هیچ لینک یا هاست SSTP درستی پیدا نشد" else "⚠️ No valid SSTP link or host found",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                saveCatSstpSubscription(links.joinToString("\n") { it.toUri() }, connect = true)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.saveCatSstpSubscription(text: String, connect: Boolean) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val name = catSstpSubscriptionName
    activityScope.launch {
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val existing = userSubscriptionManager.list().firstOrNull { it.name == name }
                if (existing != null) userSubscriptionManager.update(existing.id, name, text)
                else userSubscriptionManager.add(name, text)
            }
        }.getOrElse { error ->
            Toast.makeText(
                this@saveCatSstpSubscription,
                (if (isFarsi) "افزودن نشد: " else "Could not add: ") + (error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        Toast.makeText(
            this@saveCatSstpSubscription,
            if (isFarsi) "✅ «$name» آماده شد (${saved.connectionCount} سرور)" else "✅ “$name” ready (${saved.connectionCount} servers)",
            Toast.LENGTH_LONG,
        ).show()
        renderSubscriptions()
        if (connect) selectSubscriptionAndConnect(saved.id) else userSubscriptionManager.select(saved.id)
    }
}

    internal fun MainActivity.showAmneziaHelpDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val body = if (isFarsi) {
            """
            🔑 کلید Amnezia چیست؟
            متنی که با vpn:// شروع می‌شود، یا فایل ‎.conf / ‎.vpn. همهٔ اطلاعات اتصال داخل همین متن است.

            📥 از کجا بیاورم؟
            • اگر سرور Amnezia دارید: در برنامهٔ Amnezia → «اشتراک‌گذاری» → سرور و پروتکل را انتخاب کنید → «فرمت AmneziaVPN» (vpn://) یا «فرمت AmneziaWG» (فایل ‎.conf) → کپی یا ذخیره.
            • فروشنده‌ها و ربات‌هایی که «کانفیگ Amnezia» می‌فروشند همین vpn:// یا فایل ‎.conf را می‌دهند.

            ✅ چه چیزهایی اینجا کار می‌کند؟
            AmneziaWG (نسخهٔ ۱، ۱.۵ و ۲)، WireGuard، XRay (VLESS / Reality) و Shadowsocks.

            ⛔ چه چیزهایی کار نمی‌کند؟
            • OpenVPN، Cloak و IKEv2 داخل کلید (برنامه آن‌ها را نشان می‌دهد ولی اجرا نمی‌کند).
            • کلیدهای «Amnezia Premium» و «Amnezia Free»: فقط توکن حساب دارند و سرورشان را خودِ برنامهٔ Amnezia می‌گیرد.
            • کلید «دسترسی کامل مدیر» (SSH): کانفیگ کاربر نیست؛ در برنامهٔ Amnezia یک کاربر بسازید و آن را بفرستید.

            🔒 امنیت: کلید شما فقط روی همین گوشی ذخیره می‌شود و برای کسی فرستاده نمی‌شود.
            """.trimIndent()
        } else {
            """
            🔑 What is an Amnezia key?
            Text starting with vpn://, or a .conf / .vpn file. Everything needed to connect is inside it.

            📥 Where do I get one?
            • Your own Amnezia server: Amnezia app → Share → pick server and protocol → “AmneziaVPN format” (vpn://) or “AmneziaWG format” (.conf) → copy or save.
            • Sellers and bots that sell “Amnezia configs” give exactly this vpn:// text or a .conf file.

            ✅ What works here?
            AmneziaWG (v1, 1.5 and 2), WireGuard, XRay (VLESS / Reality) and Shadowsocks.

            ⛔ What does not?
            • OpenVPN, Cloak and IKEv2 inside a key (shown, but not run).
            • “Amnezia Premium” / “Amnezia Free” keys: they only hold an account token; only the Amnezia app can get their servers.
            • Full-access admin keys (SSH): not a user config; create a user in the Amnezia app and share that.

            🔒 Privacy: the key is stored only on this phone and is not sent anywhere.
            """.trimIndent()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "❓ راهنمای Amnezia" else "❓ Amnezia guide")
            .setMessage(body)
            .setPositiveButton(if (isFarsi) "فهمیدم" else "Got it", null)
            .create()
            .showCatClientDialog()
    }
