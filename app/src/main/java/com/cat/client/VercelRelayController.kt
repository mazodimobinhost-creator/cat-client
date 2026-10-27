package com.cat.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * "🔺 Config on Vercel": deploys an XHTTP relay into the user's own Vercel account with their
 * token, then builds ready configs (vercel.com address, project domain, Vercel IPs — all with the project SNI) and adds them.
 */
class VercelRelayController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val isFarsi: () -> Boolean,
    private val subscriptions: () -> UserSubscriptionManager,
    private val addSubscription: (content: String, name: String) -> Unit,
    private val showQr: (links: List<String>, title: String) -> Unit,
    private val showDialog: (AlertDialog) -> Unit,
) {
    private companion object { const val PANEL_RUNTIME = "panel" }
    private val palette by lazy { CatClientDesignTokens.forContext(activity) }
    private val store by lazy { VercelRelayStore(activity) }
    private var job: Job? = null

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun t(fa: String, en: String) = if (isFarsi()) fa else en
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun label(text: String, size: Float = 12.5f, color: Int = palette.textSecondary, top: Int = 6): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(2))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    private fun button(text: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(activity).apply {
            this.text = text
            isAllCaps = false
            textSize = 12.5f
            minHeight = dp(44)
            cornerRadius = dp(10)
            if (!filled) {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(palette.teal)
                strokeWidth = dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(palette.teal)
            }
            setOnClickListener { onClick() }
        }

    private fun input(hint: String, initial: String = "", multiline: Boolean = false, password: Boolean = false): EditText =
        EditText(activity).apply {
            this.hint = hint
            setText(initial)
            textSize = 13f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline) {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            textDirection = View.TEXT_DIRECTION_LTR
        }

    private fun copy(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Cat Client", text))
        toast(t("کپی شد", "Copied"))
    }

    private fun clipboardText(): String {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull().orEmpty()
    }

    private fun lp(top: Int = 6) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    // ------------------------------------------------------------------------------------
    // Main page
    // ------------------------------------------------------------------------------------

    fun show(prefillSource: String = "") {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val scroll = ScrollView(activity).apply { addView(box) }

        box.addView(label(t(
            "Vercel مثل یک واسطهٔ تمیز است: گوشی شما فقط با Vercel حرف می‌زند و Vercel ترافیک را به سرور XHTTP شما می‌رساند. " +
                "به درد شما می‌خورد اگر یک کانفیگ XHTTP دارید که آدرسش فیلتر شده، یا یک سرور شخصی (VPS) دارید.",
            "Vercel acts as a clean middleman: your phone only talks to Vercel and Vercel carries the traffic to your XHTTP server. " +
                "Useful when you have an XHTTP config whose address got filtered, or your own server (VPS).",
        ), 13f, palette.textPrimary, 0))
        box.addView(button(t("🐱 سرور ندارم: پنل کامل Cat + کانفیگ روی Vercel بساز", "🐱 No server: build the full Cat panel + configs on Vercel"), true) { showPanel() }, lp(8))
        box.addView(button(t("🔎 اسکنر آی‌پی Vercel (آی‌پی سالم برای خط من)", "🔎 Vercel IP scanner (IPs that work on my line)")) { showIpScanner() }, lp(6))
        box.addView(button(t("❓ راهنمای ساده (هر دکمه چه می‌کند)", "❓ Simple guide (what each button does)")) { showGuide() }, lp(8))

        box.addView(label(t("۱) توکن Vercel", "1) Vercel token"), 14f, palette.teal, 12))
        val tokenInput = input(t("توکن را اینجا بچسبانید", "Paste the token here"), store.token, password = true)
        box.addView(tokenInput, lp(2))
        box.addView(button(t("🔑 گرفتن توکن از سایت Vercel", "🔑 Get a token on vercel.com")) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vercel.com/account/tokens"))) }
        }, lp(4))
        box.addView(label(t(
            "در صفحه‌ای که باز می‌شود: Create Token ← یک اسم دلخواه ← Scope همان حساب خودتان ← Expiration دلخواه ← Create. متن توکن را کپی و این‌جا بچسبانید. توکن فقط روی همین گوشی ذخیره می‌شود.",
            "On that page: Create Token → any name → Scope = your account → any expiration → Create. Copy the token and paste it here. It is stored only on this phone.",
        ), 11.5f))

        box.addView(label(t("۲) کانفیگ XHTTP شما (سرور مقصد)", "2) Your XHTTP config (the destination server)"), 14f, palette.teal, 12))
        val sourceInput = input("vless://…  type=xhttp", prefillSource, multiline = true)
        box.addView(sourceInput, lp(2))
        val status = label("", 12f)
        box.addView(status)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(t("📋 چسباندن", "📋 Paste")) {
            val links = VercelRelayPlanner.extractLinks(clipboardText())
            sourceInput.setText(links.firstOrNull { VercelRelayPlanner.check(it) is VercelSourceCheck.Ok } ?: links.firstOrNull() ?: clipboardText().trim())
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(button(t("📂 از اشتراک‌های من", "📂 From my list")) {
            pickFromSubscriptions { sourceInput.setText(it) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row, lp(4))
        box.addView(button(t("🖥️ سرور شخصی دارم؛ کانفیگ سرور را برایم بساز", "🖥️ I have a VPS — build the server config for me")) {
            showVpsHelper { link -> sourceInput.setText(link) }
        }, lp(6))

        box.addView(label(t("۳) نوع اجرا و منطقه", "3) Runtime and region"), 14f, palette.teal, 12))
        val runtimeGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val nodeRadio = RadioButton(activity).apply {
            id = View.generateViewId()
            text = t("Node (پیشنهادی) — با آی‌پی، SNI جدا و گواهی شخصی (self-signed) هم کار می‌کند", "Node (recommended) — works with IPs, a separate SNI and self-signed certificates")
            setTextColor(palette.textPrimary)
            isChecked = true
        }
        val edgeRadio = RadioButton(activity).apply {
            id = View.generateViewId()
            text = t("Edge — سبک‌تر، ولی سرور باید دامنه با گواهی معتبر داشته باشد", "Edge — lighter, but the server needs a domain with a valid certificate")
            setTextColor(palette.textPrimary)
        }
        runtimeGroup.addView(nodeRadio)
        runtimeGroup.addView(edgeRadio)
        box.addView(runtimeGroup, lp(2))
        val regionGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val regionIds = VercelRelayPlanner.REGIONS.mapIndexed { index, (code, names) ->
            val radio = RadioButton(activity).apply {
                id = View.generateViewId()
                text = t(names.first, names.second) + "  ($code)"
                setTextColor(palette.textPrimary)
                isChecked = index == 0
            }
            regionGroup.addView(radio)
            radio.id to code
        }.toMap()
        box.addView(label(t("منطقهٔ اجرای واسطه (نزدیک سرور شما بهتر است):", "Relay region (closer to your server is better):"), 11.5f))
        box.addView(regionGroup)

        box.addView(label(t(
            "⚠️ هر بایت در پلن رایگان Vercel دو بار حساب می‌شود؛ مصرف خیلی زیاد ممکن است حساب را ۳۰ روز متوقف کند و این استفاده ممکن است با قوانین Vercel سازگار نباشد. برای استفادهٔ سنگین چند حساب بسازید.",
            "⚠️ Each byte counts twice on the Vercel free plan; very heavy use can pause the account for 30 days and this use may conflict with Vercel's terms. Spread heavy use across accounts.",
        ), 11.5f, palette.textSecondary, 12))

        fun refreshStatus() {
            val raw = sourceInput.text?.toString().orEmpty()
            val first = VercelRelayPlanner.extractLinks(raw).firstOrNull() ?: raw.trim()
            if (first.isBlank()) {
                status.text = t("یک لینک vless/trojan از نوع XHTTP بدهید.", "Give a vless/trojan XHTTP link.")
                status.setTextColor(palette.textSecondary)
                return
            }
            when (val check = VercelRelayPlanner.check(first)) {
                is VercelSourceCheck.Ok -> {
                    val b = check.backend
                    status.text = t("✅ مناسب است. مقصد: ", "✅ Suitable. Destination: ") +
                        "${b.proto}://${b.connect}:${b.port}${b.path}" +
                        (if (b.host != b.connect) "  (Host ${b.host})" else "") +
                        (if (!check.edgeAllowed) t("\nفقط حالت Node برای این سرور کار می‌کند.", "\nOnly Node mode works for this server.") else "")
                    status.setTextColor(palette.teal)
                    if (!check.edgeAllowed && edgeRadio.isChecked) nodeRadio.isChecked = true
                    edgeRadio.isEnabled = check.edgeAllowed
                }
                is VercelSourceCheck.Rejected -> {
                    status.text = "❌ " + t(check.fa, check.en)
                    status.setTextColor(0xFFE5484D.toInt())
                    edgeRadio.isEnabled = true
                }
            }
        }
        sourceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshStatus()
        })
        refreshStatus()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔺 ساخت کانفیگ روی Vercel", "🔺 Build a config on Vercel"))
            .setView(scroll)
            .setPositiveButton(t("🚀 ساخت", "🚀 Build"), null)
            .setNeutralButton(t("پروژه‌های من", "My projects"), null)
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { showProjects() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val token = tokenInput.text?.toString().orEmpty().trim()
                val raw = sourceInput.text?.toString().orEmpty()
                val link = VercelRelayPlanner.extractLinks(raw).firstOrNull() ?: raw.trim()
                if (token.length < 10) {
                    toast(t("اول توکن Vercel را وارد کنید.", "Enter your Vercel token first."))
                    return@setOnClickListener
                }
                val check = VercelRelayPlanner.check(link)
                if (check !is VercelSourceCheck.Ok) {
                    toast((check as? VercelSourceCheck.Rejected)?.let { t(it.fa, it.en) } ?: t("لینک نامعتبر است.", "Invalid link."))
                    return@setOnClickListener
                }
                val runtime = if (edgeRadio.isChecked && check.edgeAllowed) VercelRuntime.Edge else VercelRuntime.Node
                val region = regionIds[regionGroup.checkedRadioButtonId] ?: "fra1"
                confirmAndDeploy(token, check, runtime, region) { dialog.dismiss() }
            }
        }
        showDialog(dialog)
    }

    private fun showGuide() {
        val text = t(
            """
🔑 «گرفتن توکن»: صفحهٔ توکن‌های Vercel باز می‌شود. یک توکن بسازید و این‌جا بچسبانید. بدون توکن برنامه نمی‌تواند در حساب شما پروژه بسازد.

📋 «چسباندن»: کانفیگی که کپی کرده‌اید (vless یا trojan از نوع XHTTP) در کادر می‌نشیند و زیرش نوشته می‌شود مناسب است یا نه.

📂 «از اشتراک‌های من»: از بین کانفیگ‌های خودتان فقط آن‌هایی که روی Vercel کار می‌کنند (XHTTP بدون Reality) را نشان می‌دهد.

🖥️ «سرور شخصی دارم»: اگر VPS دارید، تنظیمات Xray سرور (inbound) و لینک آن را می‌سازد؛ تنظیمات را روی سرور بگذارید، بعد همین لینک برای ساخت استفاده می‌شود.

Node / Edge: اگر نمی‌دانید، همان Node را بگذارید.

🚀 «ساخت»: قبل از هر کاری فهرست کارهایی که در حساب Vercel شما انجام می‌شود را نشان می‌دهد و تأیید می‌خواهد. بعد یک پروژهٔ کوچک می‌سازد، منتشر می‌کند، خودش تست می‌گیرد و چند کانفیگ آماده می‌دهد.

➕ «افزودن به برنامه»: کانفیگ‌های ساخته‌شده به‌صورت یک اشتراک «Vercel» اضافه می‌شوند؛ بعد مثل بقیه پینگ و وصل کنید. اول «آدرس vercel.com» را امتحان کنید، اگر نشد بقیه را. (نام پروژه در SNI دیده می‌شود؛ Vercel اجازهٔ پنهان کردن آن را نمی‌دهد.)

📁 «پروژه‌های من»: پروژه‌هایی که برنامه ساخته را نشان می‌دهد؛ می‌توانید دوباره کانفیگ‌ها را بگیرید یا پروژه را از حساب Vercel پاک کنید.

چرا فقط XHTTP؟ Vercel فقط درخواست‌های معمولی وب را جابه‌جا می‌کند؛ WS، gRPC، TCP و Reality از پشت آن رد نمی‌شوند.
            """.trimIndent(),
            """
🔑 “Get a token”: opens Vercel's token page. Create one and paste it here. Without it the app cannot create a project in your account.

📋 “Paste”: the copied config (vless or trojan, XHTTP) goes into the box and the line below tells you whether it fits.

📂 “From my list”: shows only your configs that can work on Vercel (XHTTP without Reality).

🖥️ “I have a VPS”: builds the Xray server inbound and its link; put the settings on your server, then that link is used for the build.

Node / Edge: if unsure, keep Node.

🚀 “Build”: first lists everything that will happen in your Vercel account and asks for confirmation. Then it creates a small project, deploys it, tests it and gives ready configs.

➕ “Add to app”: the configs are added as a “Vercel” subscription; ping and connect like any other. Try “vercel.com address” first, then the others. (The project name is visible in SNI; Vercel does not allow hiding it.)

📁 “My projects”: projects this app created; get the configs again or delete the project from Vercel.

Why only XHTTP? Vercel only passes normal web requests; WS, gRPC, TCP and Reality cannot go through it.
            """.trimIndent(),
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("❓ راهنمای Vercel", "❓ Vercel guide"))
            .setView(ScrollView(activity).apply { addView(label(text, 13f, palette.textPrimary, 0).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
        showDialog(dialog)
    }

    private fun pickFromSubscriptions(onPick: (String) -> Unit) {
        val found = LinkedHashMap<String, String>() // link -> label
        runCatching { subscriptions().list() }.getOrDefault(emptyList()).forEach { sub ->
            val texts = mutableListOf(sub.input)
            runCatching { decodeMaybeBase64(sub.input) }.getOrNull()?.let(texts::add)
            texts.forEach { text ->
                VercelRelayPlanner.relayableLinks(text).forEach { link ->
                    val remark = VercelRelayPlanner.parse(link)?.remark.orEmpty().ifBlank { link.take(40) }
                    found.putIfAbsent(link, "${sub.name} · $remark")
                }
            }
        }
        if (found.isEmpty()) {
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(t("کانفیگ مناسبی پیدا نشد", "No suitable config found"))
                .setMessage(t(
                    "در اشتراک‌های ذخیره‌شده‌ی شما لینک XHTTP (بدون Reality) پیدا نشد. اشتراک‌های لینک‌دار (URL) این‌جا باز نمی‌شوند؛ لینک یک کانفیگ را کپی و «چسباندن» را بزنید، یا اگر سرور شخصی دارید «سرور شخصی دارم» را بزنید.",
                    "No XHTTP link (without Reality) in your saved subscriptions. URL subscriptions are not expanded here; copy one config link and tap “Paste”, or tap “I have a VPS”.",
                ))
                .setPositiveButton(android.R.string.ok, null)
                .create()
            showDialog(dialog)
            return
        }
        val links = found.keys.toList()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("کانفیگ‌های مناسب Vercel", "Configs that fit Vercel"))
            .setItems(found.values.toTypedArray()) { d, which ->
                d.dismiss()
                onPick(links[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun decodeMaybeBase64(text: String): String? {
        val compact = text.trim().replace("\n", "").replace("\r", "")
        if (compact.isEmpty() || compact.contains("://") || compact.length % 4 == 1) return null
        return runCatching {
            String(java.util.Base64.getMimeDecoder().decode(compact.replace('-', '+').replace('_', '/')), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun showVpsHelper(onUse: (String) -> Unit) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        box.addView(label(t(
            "آی‌پی یا دامنهٔ سرور خود را بنویسید. یک UUID و مسیر تصادفی ساخته شده؛ «کپی تنظیمات سرور» را بزنید و آن را در Xray سرور (یا بخش inbound پنل 3x-ui به‌صورت JSON) بگذارید و پورت را در فایروال سرور باز کنید. سپس «استفاده از این لینک» را بزنید.",
            "Type your server IP or domain. A random UUID and path are ready; tap “Copy server config”, put it into the server's Xray (or a 3x-ui JSON inbound) and open the port in the server firewall. Then tap “Use this link”.",
        ), 12.5f, palette.textPrimary, 0))
        val address = input(t("آی‌پی یا دامنهٔ سرور", "Server IP or domain"))
        val port = input(t("پورت (مثلاً 8080)", "Port (e.g. 8080)"), "8080").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val uuid = input("UUID", VercelRelayPlanner.randomUuid())
        val path = input(t("مسیر", "Path"), VercelRelayPlanner.randomPath())
        listOf(address, port, uuid, path).forEach { box.addView(it, lp(4)) }
        fun portValue() = port.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
        box.addView(button(t("📋 کپی تنظیمات سرور (JSON)", "📋 Copy server config (JSON)")) {
            copy(VercelRelayPlanner.serverInboundJson(uuid.text.toString().trim(), portValue(), path.text.toString()))
        }, lp(8))
        box.addView(label(t(
            "این سرور بدون TLS است چون رمزنگاری بین گوشی و Vercel انجام می‌شود؛ مسیر و UUID تصادفی را به کسی ندهید.",
            "This server has no TLS because encryption happens between the phone and Vercel; keep the random path and UUID private.",
        ), 11.5f))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🖥️ ساخت کانفیگ سرور شخصی", "🖥️ Build a VPS config"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("استفاده از این لینک", "Use this link"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val host = address.text?.toString().orEmpty().trim()
                val id = uuid.text?.toString().orEmpty().trim()
                if (host.isBlank() || id.length < 8) {
                    toast(t("آدرس سرور و UUID لازم است.", "Server address and UUID are required."))
                    return@setOnClickListener
                }
                onUse(VercelRelayPlanner.serverLink(id, host, portValue(), path.text.toString()))
                dialog.dismiss()
            }
        }
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // Deploy
    // ------------------------------------------------------------------------------------

    private fun confirmAndDeploy(token: String, check: VercelSourceCheck.Ok, runtime: VercelRuntime, region: String, onStarted: () -> Unit) {
        val b = check.backend
        val message = t(
            "این کارها در حساب Vercel شما انجام می‌شود:\n\n" +
                "• ساخت یک پروژهٔ جدید با اسم تصادفی (مثل notes-ab12c)\n" +
                "• آپلود ۳ فایل کوچک (package.json، vercel.json، api/index.js) و انتشار روی دامنهٔ ‎*.vercel.app\n" +
                "• خاموش کردن «Vercel Authentication» فقط برای همین پروژه تا کانفیگ‌ها باز شوند\n" +
                "• واسطه فقط مسیر ${b.path} را به ${b.proto}://${b.connect}:${b.port} می‌فرستد؛ بقیهٔ مسیرها یک صفحهٔ ساده نشان می‌دهند\n\n" +
                "نوع اجرا: ${runtime.wire} · منطقه: $region\nبه پروژه‌ها و تنظیمات دیگر حساب شما دست زده نمی‌شود و هر وقت بخواهید از «پروژه‌های من» پاک می‌شود.",
            "This will happen in your Vercel account:\n\n" +
                "• Create one new project with a random name (like notes-ab12c)\n" +
                "• Upload 3 small files (package.json, vercel.json, api/index.js) and publish on *.vercel.app\n" +
                "• Turn off “Vercel Authentication” for this project only so the configs can reach it\n" +
                "• The relay forwards only ${b.path} to ${b.proto}://${b.connect}:${b.port}; other paths show a plain page\n\n" +
                "Runtime: ${runtime.wire} · region: $region\nNothing else in your account is touched; delete it any time from “My projects”.",
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("تأیید ساخت روی Vercel", "Confirm Vercel build"))
            .setMessage(message)
            .setPositiveButton(t("بله، بساز", "Yes, build")) { _, _ ->
                onStarted()
                deploy(token, check, runtime, region)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun deploy(token: String, check: VercelSourceCheck.Ok, runtime: VercelRuntime, region: String) {
        val log = label("", 12.5f, palette.textPrimary, 0).apply { setTextIsSelectable(true) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(log)
        }
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("⏳ در حال ساخت روی Vercel…", "⏳ Building on Vercel…"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton(t("توقف", "Stop")) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        showDialog(progressDialog)
        val lines = mutableListOf<String>()
        fun step(text: String) = activity.runOnUiThread {
            lines += text
            log.text = lines.joinToString("\n")
        }
        job?.cancel()
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { runDeploy(token, check, runtime, region, ::step) } }
            runCatching { progressDialog.dismiss() }
            result.onSuccess { saved -> showResult(saved, lines) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    val dialog = MaterialAlertDialogBuilder(activity)
                        .setTitle(t("❌ ساخت انجام نشد", "❌ Build failed"))
                        .setMessage((lines + "" + explainError(error)).joinToString("\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    showDialog(dialog)
                }
        }
    }

    private fun explainError(error: Throwable): String {
        val message = error.message ?: error.javaClass.simpleName
        val status = (error as? VercelApiException)?.status ?: 0
        return when {
            status == 401 || status == 403 && message.contains("token", true) ->
                t("توکن نامعتبر یا منقضی است؛ یک توکن جدید بسازید.\n", "The token is invalid or expired; create a new one.\n") + message
            status == 429 -> t("محدودیت تعداد درخواست Vercel؛ چند دقیقه بعد دوباره امتحان کنید.\n", "Vercel rate limit; try again in a few minutes.\n") + message
            error is java.net.UnknownHostException || error is java.net.ConnectException || error is SocketTimeoutException ->
                t("به api.vercel.com وصل نشد. اگر روی اینترنت ملی هستید اول با یک کانفیگ دیگر وصل شوید، بعد دوباره «ساخت» را بزنید.\n", "Could not reach api.vercel.com. If your network blocks it, connect with another config first, then build again.\n") + message
            else -> t("خطا: ", "Error: ") + message
        }
    }

    private suspend fun runDeploy(
        token: String,
        check: VercelSourceCheck.Ok,
        runtime: VercelRuntime,
        region: String,
        step: (String) -> Unit,
    ): SavedVercelRelay {
        val api = VercelApi(token)
        step(t("۱/۶ بررسی توکن…", "1/6 Checking the token…"))
        val user = api.user()
        step(t("   ✓ حساب: ", "   ✓ Account: ") + user.optString("username").ifBlank { user.optString("email") })
        store.token = token

        step(t("۲/۶ ساخت پروژه…", "2/6 Creating the project…"))
        var project: org.json.JSONObject? = null
        var name = ""
        repeat(4) {
            if (project != null) return@repeat
            name = VercelRelayPlanner.randomProjectName()
            try {
                project = api.createProject(name)
            } catch (e: VercelApiException) {
                if (e.status != 409 && !e.message.orEmpty().contains("exist", true)) throw e
            }
        }
        val projectId = project?.optString("id").orEmpty().ifBlank { throw VercelApiException(t("پروژه ساخته نشد", "Project was not created")) }
        step("   ✓ $name")
        api.disableProtection(projectId)

        step(t("۳/۶ آپلود و انتشار واسطه (${runtime.wire})…", "3/6 Uploading and deploying the relay (${runtime.wire})…"))
        val templateName = if (runtime == VercelRuntime.Edge) "vercel/relay-edge.js" else "vercel/relay-node.js"
        val template = activity.assets.open(templateName).bufferedReader().use { it.readText() }
        var deployment = try {
            api.deploy(projectId, name, VercelRelayPlanner.files(template, check.backend, runtime, region, name, 300), region.takeIf { runtime == VercelRuntime.Node })
        } catch (e: VercelApiException) {
            if (!e.message.orEmpty().contains("maxDuration", true) && !e.message.orEmpty().contains("duration", true)) throw e
            step(t("   حساب شما زمان ۳۰۰ ثانیه را قبول نکرد؛ با ۶۰ ثانیه…", "   Your plan rejected 300 s; retrying with 60 s…"))
            api.deploy(projectId, name, VercelRelayPlanner.files(template, check.backend, runtime, region, name, 60), region.takeIf { runtime == VercelRuntime.Node })
        }
        val deploymentId = deployment.optString("id")

        step(t("۴/۶ منتظر آماده شدن (معمولاً کمتر از یک دقیقه)…", "4/6 Waiting until ready (usually under a minute)…"))
        var state = deployment.optString("readyState")
        val started = System.currentTimeMillis()
        while (state != "READY") {
            if (state == "ERROR" || state == "CANCELED") {
                val details = api.buildErrors(deploymentId)
                throw VercelApiException(
                    deployment.optString("errorMessage").ifBlank { t("انتشار ناموفق بود", "Deployment failed") } +
                        if (details.isNotBlank()) "\n$details" else "",
                )
            }
            if (System.currentTimeMillis() - started > 180_000) throw VercelApiException(t("آماده شدن بیش از ۳ دقیقه طول کشید؛ بعداً از «پروژه‌های من» ببینید.", "Not ready after 3 minutes; check “My projects” later."))
            delay(3_000)
            deployment = api.deployment(deploymentId)
            state = deployment.optString("readyState")
        }
        // Always ask the project's domain list: the deployment's own alias list may only carry the
        // team-scoped alias, which is behind Vercel Authentication (seen in the live test).
        val aliases = api.projectDomains(projectId).toMutableList()
        deployment.optJSONArray("alias")?.let { arr -> for (i in 0 until arr.length()) aliases += arr.optString(i) }
        deployment.optJSONArray("automaticAliases")?.let { arr -> for (i in 0 until arr.length()) aliases += arr.optString(i) }
        val teamSlug = deployment.optJSONObject("team")?.optString("slug")
        val host = VercelRelayPlanner.pickProductionHost(aliases, name, teamSlug) ?: "$name.vercel.app"
        step("   ✓ https://$host")

        step(t("۵/۶ تست واقعی از همین گوشی…", "5/6 Real test from this phone…"))
        step(probe(host, check.backend.path))

        step(t("۶/۶ ساخت کانفیگ‌ها…", "6/6 Building the configs…"))
        val links = VercelRelayPlanner.buildLinks(check.source, host, name)
        val saved = SavedVercelRelay(projectId, name, host, runtime.wire, System.currentTimeMillis(), check.source.remark, links.map { it.link })
        store.save(saved)
        step(t("   ✓ ${links.size} کانفیگ", "   ✓ ${links.size} configs"))
        return saved
    }

    /** Real HTTP probe of the deployed relay: the guard page, then one request through to the backend. */
    private suspend fun probe(host: String, path: String): String {
        var pageOk = false
        var lastError = ""
        for (attempt in 0 until 6) {
            val r = httpGet("https://$host/", 10_000)
            if (r.code == 200 && r.body.contains("Nothing to see")) { pageOk = true; break }
            lastError = if (r.code > 0) "HTTP ${r.code}" else r.error
            val toLogin = r.code in 300..399 && (r.location.contains("vercel.com/login") || r.location.contains("sso-api"))
            if (r.code == 401 || r.code == 403 || toLogin) {
                return t("   ⚠️ پروژه قفل «Vercel Authentication» دارد. در داشبورد Vercel ← پروژه ← Settings ← Deployment Protection آن را خاموش کنید.", "   ⚠️ The project is behind “Vercel Authentication”. Turn it off in Vercel → project → Settings → Deployment Protection.")
            }
            delay(4_000)
        }
        if (!pageOk) {
            return t("   ⚠️ از این شبکه به ‎$host نرسیدیم ($lastError). ممکن است دامنه روی خط شما بسته باشد؛ کانفیگ «آدرس vercel.com» و آی‌پی‌ها را در برنامه تست کنید.", "   ⚠️ Could not reach $host from this network ($lastError). The domain may be blocked on your line; test the vercel.com-address and IP configs in the app.")
        }
        val base = if (path.endsWith('/')) path.dropLast(1) else path
        val through = httpGet("https://$host$base/cat-probe-${System.currentTimeMillis() % 100000}", 8_000)
        return when {
            through.code == 502 && through.body.contains("upstream unreachable") ->
                t("   ✓ Vercel کار می‌کند، ولی ❌ Vercel به سرور شما نرسید. آدرس/پورت سرور، فایروال و TLS را بررسی کنید.", "   ✓ Vercel works, but ❌ Vercel could not reach your server. Check the server address/port, firewall and TLS.")
            through.vercelError.isNotBlank() ->
                t("   ⚠️ خطای Vercel: ", "   ⚠️ Vercel error: ") + through.vercelError
            through.timedOut || through.code in 200..499 ->
                t("   ✓ Vercel کار می‌کند و ✓ سرور شما از پشت Vercel جواب داد.", "   ✓ Vercel works and ✓ your server answered through Vercel.")
            else -> t("   ⚠️ جواب غیرمنتظره: ", "   ⚠️ Unexpected answer: ") + (if (through.code > 0) "HTTP ${through.code}" else through.error)
        }
    }

    private data class Probe(val code: Int, val body: String, val vercelError: String, val timedOut: Boolean, val error: String, val location: String = "")

    private fun httpGet(url: String, timeoutMs: Int, maxChars: Int = 400): Probe = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) CatClient")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = runCatching {
                stream?.bufferedReader()?.use { r ->
                    val out = StringBuilder()
                    val buf = CharArray(4096)
                    while (out.length < maxChars) {
                        val n = r.read(buf, 0, minOf(buf.size, maxChars - out.length))
                        if (n <= 0) break
                        out.append(buf, 0, n)
                    }
                    out.toString()
                }
            }.getOrNull().orEmpty()
            Probe(code, body, c.getHeaderField("x-vercel-error").orEmpty(), false, "", c.getHeaderField("Location").orEmpty())
        } finally {
            c.disconnect()
        }
    } catch (e: SocketTimeoutException) {
        Probe(0, "", "", true, "timeout")
    } catch (e: Exception) {
        Probe(0, "", "", false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    private fun showResult(saved: SavedVercelRelay, lines: List<String>) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        box.addView(label(lines.joinToString("\n"), 12f, palette.textSecondary, 0))
        var dialog: AlertDialog? = null
        box.addView(button(t("➕ افزودن ${saved.links.size} کانفیگ به برنامه", "➕ Add ${saved.links.size} configs to the app"), true) {
            dialog?.dismiss()
            addSubscription(saved.links.joinToString("\n"), "Vercel · ${saved.projectName}")
        }, lp(10))
        box.addView(button(t("📋 کپی همهٔ کانفیگ‌ها", "📋 Copy all configs")) { copy(saved.links.joinToString("\n")) }, lp(6))
        box.addView(button(t("🔳 نمایش QR", "🔳 Show QR")) { showQr(saved.links, "Vercel · ${saved.projectName}") }, lp(6))
        box.addView(label(t(
            "ترتیب امتحان: ۱) آدرس vercel.com ۲) دامنهٔ پروژه ۳) آی‌پی‌های Vercel. بعد از افزودن، «تست پینگ» برنامه آن‌هایی که روی خط شما کار می‌کنند را نشان می‌دهد.",
            "Try order: 1) vercel.com address 2) project domain 3) Vercel IPs. After adding, the app's ping test shows which work on your line.",
        ), 11.5f, palette.textSecondary, 10))
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("✅ کانفیگ Vercel آماده است", "✅ Vercel config is ready"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("بستن", "Close"), null)
            .create()
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // Cat Panel on Vercel (no server needed)
    // ------------------------------------------------------------------------------------

    fun showPanel() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        box.addView(label(t(
            "بدون سرور شخصی: پنل کامل Cat و یک سرور VLESS/Trojan داخل حساب Vercel خودتان ساخته می‌شود. " +
                "در پایان این‌ها را می‌گیرید: آدرس پنل (ورود admin / admin)، لینک اشتراک، و کانفیگ‌های آماده که همین گوشی قبل از تحویل واقعاً تستشان می‌کند.",
            "No server of your own: the full Cat panel plus a VLESS/Trojan server are built inside your own Vercel account. " +
                "You get the panel address (login admin / admin), a subscription link and ready configs that this phone really tests before handing them over.",
        ), 13f, palette.textPrimary, 0))
        box.addView(label(t(
            "«🚀 ساخت پنل» را بزنی ← پروژه + حافظهٔ دائمی ساخته و منتشر می‌شود و کانفیگ‌ها تست می‌شوند.\n" +
                "«🔎 اسکنر آی‌پی» را بزنی ← آی‌پی‌های Vercel که روی خط تو کار می‌کنند پیدا و کانفیگ می‌شوند.\n" +
                "«🔧 به‌روزرسانی» را بزنی ← پنل قبلی‌ات نسخهٔ جدید + حافظهٔ دائمی می‌گیرد (کاربرهای پنل دیگر پاک نمی‌شوند).\n" +
                "«پروژه‌های من» را بزنی و روی یک پنل بزنی ← اول وضعیتش را می‌گوید (✅ سالم / ⚠️ بدون حافظه = علت «۰ کانفیگ» / 🆕 قدیمی) و اگر لازم باشد دکمهٔ «🔧 همین حالا درستش کن» می‌آید؛ بعد می‌توانی دوباره اضافه یا پاکش کنی.",
            "Tap “🚀 Build panel” → project + permanent storage are created, published and tested.\n" +
                "Tap “🔎 IP scanner” → Vercel IPs that work on your line are found and turned into configs.\n" +
                "Tap “🔧 Update” → your earlier panel gets the new version + permanent storage (panel users are kept).\n" +
                "Tap “My projects” and pick a panel → it first shows its state (✅ healthy / ⚠️ no storage = the “0 configs” cause / 🆕 outdated) and, when needed, a “🔧 Fix it now” button; then add it again or delete it.",
        ), 12f, palette.textSecondary, 8))
        val tools = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button(t("🔎 اسکنر آی‌پی", "🔎 IP scanner")) { showIpScanner() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button(t("🔧 به‌روزرسانی پنل قبلی", "🔧 Update a panel")) { showUpdatePanel() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        box.addView(tools, lp(8))

        box.addView(label(t("۱) توکن Vercel", "1) Vercel token"), 14f, palette.teal, 12))
        val tokenInput = input(t("توکن را اینجا بچسبانید", "Paste the token here"), store.token, password = true)
        box.addView(tokenInput, lp(2))
        box.addView(button(t("🔑 گرفتن توکن از سایت Vercel", "🔑 Get a token on vercel.com")) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vercel.com/account/tokens"))) }
        }, lp(4))
        box.addView(label(t(
            "Create Token ← یک اسم دلخواه ← Scope همان حساب خودتان ← Create. توکن فقط روی همین گوشی ذخیره می‌شود.",
            "Create Token → any name → Scope = your account → Create. The token is stored only on this phone.",
        ), 11.5f))

        box.addView(label(t("۲) منطقهٔ اجرا", "2) Region"), 14f, palette.teal, 12))
        val regionGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val regionIds = VercelRelayPlanner.REGIONS.mapIndexed { index, (code, names) ->
            val radio = RadioButton(activity).apply {
                id = View.generateViewId()
                text = t(names.first, names.second) + "  ($code)"
                setTextColor(palette.textPrimary)
                isChecked = index == 0
            }
            regionGroup.addView(radio)
            radio.id to code
        }.toMap()
        box.addView(regionGroup)

        box.addView(label(t("۳) رمز پنل (اختیاری)", "3) Panel password (optional)"), 14f, palette.teal, 12))
        val passInput = input(t("خالی = همان admin / admin", "Empty = keep admin / admin"), "", password = true)
        box.addView(passInput, lp(2))

        box.addView(label(t(
            "⚠️ پلن رایگان Vercel سقف مصرف دارد و هر بایت دو بار حساب می‌شود؛ مصرف خیلی زیاد ممکن است حساب را ۳۰ روز متوقف کند و این استفاده ممکن است با قوانین Vercel سازگار نباشد. هر اتصال حداکثر ۵ دقیقه می‌ماند و برنامه خودش دوباره وصل می‌شود. کاربرها و تنظیمات پنل در یک حافظهٔ خصوصی Vercel Blob (رایگان) در حساب خودتان ذخیره می‌شوند.",
            "⚠️ The Vercel free plan has usage limits and each byte counts twice; very heavy use can pause the account for 30 days and this use may conflict with Vercel's terms. Each connection lasts up to 5 minutes and clients reconnect by themselves. Panel users and settings are stored in a private Vercel Blob store (free) in your own account.",
        ), 11.5f, palette.textSecondary, 12))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🐱 پنل Cat روی Vercel", "🐱 Cat Panel on Vercel"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("🚀 ساخت پنل", "🚀 Build panel"), null)
            .setNeutralButton(t("پروژه‌های من", "My projects"), null)
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { showProjects() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val token = tokenInput.text?.toString().orEmpty().trim()
                if (token.length < 10) {
                    toast(t("اول توکن Vercel را وارد کنید.", "Enter your Vercel token first."))
                    return@setOnClickListener
                }
                val region = regionIds[regionGroup.checkedRadioButtonId] ?: "fra1"
                val password = passInput.text?.toString().orEmpty().trim()
                confirmPanel(token, region, password) { dialog.dismiss() }
            }
        }
        showDialog(dialog)
    }

    private fun confirmPanel(token: String, region: String, password: String, onStarted: () -> Unit) {
        val message = t(
            "این کارها در حساب Vercel شما انجام می‌شود:\n\n" +
                "• ساخت یک پروژهٔ جدید با اسم تصادفی\n" +
                "• ذخیرهٔ یک UUID و رمز Trojan تازه (و رمز پنل اگر دادید) در تنظیمات همان پروژه\n" +
                "• ساخت یک حافظهٔ خصوصی Vercel Blob (رایگان) و وصل کردنش به همین پروژه، برای کاربرها و تنظیمات پنل\n" +
                "• آپلود ۴ فایل (پنل Cat، فایل اتصال، package.json، vercel.json) و انتشار روی ‎*.vercel.app\n" +
                "• خاموش کردن «Vercel Authentication» فقط برای همین پروژه\n\n" +
                "منطقه: $region\nبه پروژه‌ها و تنظیمات دیگر حساب شما دست زده نمی‌شود و هر وقت بخواهید از «پروژه‌های من» پاک می‌شود.",
            "This will happen in your Vercel account:\n\n" +
                "• Create one new project with a random name\n" +
                "• Store a fresh UUID and Trojan password (and your panel password, if given) in that project's settings\n" +
                "• Create a private Vercel Blob store (free) connected to this project, for panel users and settings\n" +
                "• Upload 4 files (Cat panel, adapter, package.json, vercel.json) and publish on *.vercel.app\n" +
                "• Turn off “Vercel Authentication” for this project only\n\n" +
                "Region: $region\nNothing else in your account is touched; delete it any time from “My projects”.",
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("تأیید ساخت پنل روی Vercel", "Confirm panel build on Vercel"))
            .setMessage(message)
            .setPositiveButton(t("بله، بساز", "Yes, build")) { _, _ ->
                onStarted()
                deployPanel(token, region, password)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun deployPanel(token: String, region: String, password: String) =
        runWithProgress(t("⏳ در حال ساخت پنل روی Vercel…", "⏳ Building the panel on Vercel…")) { step -> runPanelDeploy(token, region, password, step) }

    private suspend fun runPanelDeploy(token: String, region: String, password: String, step: (String) -> Unit): SavedVercelRelay {
        val api = VercelApi(token)
        step(t("۱/۸ بررسی توکن…", "1/8 Checking the token…"))
        val user = api.user()
        step(t("   ✓ حساب: ", "   ✓ Account: ") + user.optString("username").ifBlank { user.optString("email") })
        store.token = token

        step(t("۲/۸ آماده کردن فایل‌های پنل…", "2/8 Preparing the panel files…"))
        val files = panelFiles()
        step("   ✓ ${files.second.size / 1024} KB")

        step(t("۳/۸ ساخت پروژه…", "3/8 Creating the project…"))
        var project: org.json.JSONObject? = null
        var name = ""
        repeat(4) {
            if (project != null) return@repeat
            name = VercelRelayPlanner.randomProjectName()
            try {
                project = api.createProject(name)
            } catch (e: VercelApiException) {
                if (e.status != 409 && !e.message.orEmpty().contains("exist", true)) throw e
            }
        }
        val projectId = project?.optString("id").orEmpty().ifBlank { throw VercelApiException(t("پروژه ساخته نشد", "Project was not created")) }
        step("   ✓ $name")
        api.disableProtection(projectId)
        val uuid = VercelRelayPlanner.randomUuid()
        val trojanPass = VercelPanelPlanner.randomSecret()
        VercelPanelPlanner.envVars(uuid, trojanPass, password).forEach { (k, v) -> api.upsertEnv(projectId, k, v) }
        step(t("   ✓ UUID و رمزها در تنظیمات پروژه ذخیره شد", "   ✓ UUID and secrets stored in the project settings"))

        step(t("۴/۸ حافظهٔ دائمی پنل (Vercel Blob)…", "4/8 Permanent panel storage (Vercel Blob)…"))
        ensureStorage(api, projectId, name, region, emptyMap(), step)

        val host = publishPanel(api, projectId, name, region, files, step, 5)
        val links = testPanel(host, uuid, trojanPass, name, step, 7)
        val saved = SavedVercelRelay(projectId, name, host, PANEL_RUNTIME, System.currentTimeMillis(), VercelPanelPlanner.subUrl(host, uuid), links)
        store.save(saved)
        return saved
    }

    /** Adapter + patched panel bytes, validated before anything touches the account. */
    private fun panelFiles(): Pair<String, ByteArray> {
        val adapter = VercelPanelPlanner.checkAdapter(activity.assets.open(VercelPanelPlanner.ADAPTER_ASSET).bufferedReader().use { it.readText() })
        val panel = VercelPanelPlanner.patchPanel(activity.assets.open(VercelPanelPlanner.PANEL_ASSET).bufferedReader().use { it.readText() })
        return adapter to panel.toByteArray(Charsets.UTF_8)
    }

    /**
     * Without storage a user made in the panel lives in ONE server instance's memory and the next
     * instance answers its subscription with 404 (= "0 configs"). A private Blob store fixes that.
     */
    private fun ensureStorage(api: VercelApi, projectId: String, name: String, region: String, env: Map<String, String>, step: (String) -> Unit): Boolean {
        if (env.containsKey("BLOB_STORE_ID") || env.containsKey("BLOB_READ_WRITE_TOKEN") || env.containsKey("KV_REST_API_URL")) {
            step(t("   ✓ حافظهٔ دائمی از قبل وصل است", "   ✓ Permanent storage already attached"))
            return true
        }
        return try {
            val storeName = ("cat-" + name).take(32)
            val storeId = try {
                api.createBlobStore(storeName, region)
            } catch (e: VercelApiException) {
                if (region == "iad1") throw e
                api.createBlobStore(storeName, "iad1")
            }
            api.connectStore(storeId, projectId)
            step(t("   ✓ حافظهٔ خصوصی Blob ساخته و وصل شد (کاربرها و تنظیمات پنل دیگر پاک نمی‌شوند)", "   ✓ Private Blob store created and connected (panel users and settings now persist)"))
            true
        } catch (e: Exception) {
            step(t(
                "   ⚠️ حافظهٔ دائمی ساخته نشد (${e.message?.take(80)}). کانفیگ‌های اصلی کار می‌کنند، ولی تا وصل نشود پنل اجازهٔ ساخت کاربر نمی‌دهد. بعداً «به‌روزرسانی پنل» را بزنید.",
                "   ⚠️ Storage could not be created (${e.message?.take(80)}). The main configs work, but the panel will not create users until storage is attached. Run “Update panel” later.",
            ))
            false
        }
    }

    private suspend fun publishPanel(
        api: VercelApi,
        projectId: String,
        name: String,
        region: String,
        files: Pair<String, ByteArray>,
        step: (String) -> Unit,
        first: Int,
    ): String {
        val (adapter, panelBytes) = files
        step(t("${fa(first)}/۸ آپلود پنل و انتشار…", "$first/8 Uploading the panel and deploying…"))
        val sha = api.uploadFile(panelBytes)
        fun inlineFiles(maxDuration: Int) = linkedMapOf(
            "package.json" to VercelPanelPlanner.packageJson(name),
            "vercel.json" to VercelPanelPlanner.vercelJson(region, maxDuration),
            "api/index.mjs" to adapter,
        )
        val uploaded = mapOf("lib/catpanel.mjs" to (sha to panelBytes.size))
        var deployment = try {
            api.deployMixed(projectId, name, inlineFiles(300), uploaded)
        } catch (e: VercelApiException) {
            if (!e.message.orEmpty().contains("duration", true)) throw e
            step(t("   حساب شما زمان ۳۰۰ ثانیه را قبول نکرد؛ با ۶۰ ثانیه…", "   Your plan rejected 300 s; retrying with 60 s…"))
            api.deployMixed(projectId, name, inlineFiles(60), uploaded)
        }
        val deploymentId = deployment.optString("id")
        step(t("${fa(first + 1)}/۸ منتظر آماده شدن (معمولاً کمتر از یک دقیقه)…", "${first + 1}/8 Waiting until ready (usually under a minute)…"))
        var state = deployment.optString("readyState")
        val started = System.currentTimeMillis()
        while (state != "READY") {
            if (state == "ERROR" || state == "CANCELED") {
                val details = api.buildErrors(deploymentId)
                throw VercelApiException(
                    deployment.optString("errorMessage").ifBlank { t("انتشار ناموفق بود", "Deployment failed") } + if (details.isNotBlank()) "\n$details" else "",
                )
            }
            if (System.currentTimeMillis() - started > 240_000) throw VercelApiException(t("آماده شدن بیش از ۴ دقیقه طول کشید؛ بعداً از «پروژه‌های من» ببینید.", "Not ready after 4 minutes; check “My projects” later."))
            delay(3_000)
            deployment = api.deployment(deploymentId)
            state = deployment.optString("readyState")
        }
        val aliases = api.projectDomains(projectId).toMutableList()
        deployment.optJSONArray("alias")?.let { arr -> for (i in 0 until arr.length()) aliases += arr.optString(i) }
        deployment.optJSONArray("automaticAliases")?.let { arr -> for (i in 0 until arr.length()) aliases += arr.optString(i) }
        val host = VercelRelayPlanner.pickProductionHost(aliases, name, deployment.optJSONObject("team")?.optString("slug")) ?: "$name.vercel.app"
        step("   ✓ https://$host")
        return host
    }

    private suspend fun testPanel(host: String, uuid: String, trojanPass: String, name: String, step: (String) -> Unit, first: Int): List<String> {
        step(t("${fa(first)}/۸ تست واقعی از همین گوشی…", "$first/8 Real test from this phone…"))
        var version = httpGet("https://$host/api/version", 25_000)
        repeat(3) { if (version.code != 200) { delay(4_000); version = httpGet("https://$host/api/version", 25_000) } }
        step(
            if (version.code == 200 && version.body.contains("cat-panel")) t("   ✓ پنل جواب داد", "   ✓ Panel answered") +
                Regex("\"version\":\"([^\"]+)\"").find(version.body)?.groupValues?.get(1)?.let { " · v$it" }.orEmpty() +
                (if (version.body.contains("\"kv\":true")) t(" · حافظهٔ دائمی ✓", " · storage ✓") else t(" · بدون حافظهٔ دائمی", " · no storage"))
            else t("   ⚠️ پنل از این گوشی جواب نداد: ", "   ⚠️ The panel did not answer from this phone: ") +
                (if (version.timedOut) "timeout" else version.error.ifBlank { "HTTP ${version.code}" }),
        )
        val sub = httpGet(VercelPanelPlanner.subUrl(host, uuid), 25_000, 512 * 1024)
        val subLinks = if (sub.code == 200) VercelPanelPlanner.extractSubLinks(sub.body) else emptyList()
        val tunnels = listOf(host, VercelPanelPlanner.EDGE_ADDRESSES[1]).map { address ->
            address to VercelPanelProbe.vless(address, host, "/ws", uuid)
        }
        tunnels.forEach { (address, r) ->
            step(if (r.ok) "   ✓ VLESS → example.com via $address · ${r.millis} ms" else "   ✗ VLESS via $address: ${r.detail}")
        }
        step(t("${fa(first + 1)}/۸ ساخت کانفیگ‌ها…", "${first + 1}/8 Building the configs…"))
        val links = if (VercelPanelPlanner.linksFitVercel(subLinks, host)) {
            step(t("   ✓ ${subLinks.size} کانفیگ از اشتراک خود پنل", "   ✓ ${subLinks.size} configs from the panel's own subscription"))
            subLinks
        } else {
            val built = VercelPanelPlanner.fallbackLinks(uuid, trojanPass, host, name).map { it.link }
            step(t("   ✓ ${built.size} کانفیگ (اشتراک پنل از این گوشی خوانده نشد؛ کانفیگ‌ها مستقیم ساخته شد)", "   ✓ ${built.size} configs (the panel subscription was unreachable from this phone; configs built directly)"))
            built
        }
        if (tunnels.none { it.second.ok }) {
            step(t(
                "   ⚠️ تونل از این گوشی تست نشد (شاید ‎*.vercel.app روی خط شما بسته است). «🔎 اسکنر آی‌پی Vercel» را بزنید تا آی‌پی سالم خط شما پیدا شود.",
                "   ⚠️ The tunnel could not be tested from this phone (*.vercel.app may be blocked on your line). Tap “🔎 Vercel IP scanner” to find addresses that work on your line.",
            ))
        }
        return links
    }

    private fun fa(n: Int): String = if (isFarsi()) n.toString().map { if (it.isDigit()) ('۰' + (it - '0')) else it }.joinToString("") else n.toString()

    // ------------------------------------------------------------------------------------
    // Update an existing panel project (adds storage, ships the new panel; UUID untouched)
    // ------------------------------------------------------------------------------------

    fun showUpdatePanel(prefill: String = "") {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        box.addView(label(t(
            "پنلی که قبلاً روی Vercel ساخته‌اید را به آخرین نسخه می‌برد و حافظهٔ دائمی (Vercel Blob) به آن وصل می‌کند تا کاربرهایی که در پنل می‌سازید پاک نشوند. UUID، رمزها و لینک‌های فعلی عوض نمی‌شوند.",
            "Brings a panel you already built on Vercel to the latest version and attaches permanent storage (Vercel Blob) so users you create in the panel are kept. The UUID, passwords and current links stay the same.",
        ), 13f, palette.textPrimary, 0))
        box.addView(label(t("اسم پروژه یا آدرس پنل", "Project name or panel address"), 14f, palette.teal, 12))
        val nameInput = input("cat-panel-xxxx  /  https://xxxx.vercel.app", prefill)
        box.addView(nameInput, lp(2))
        box.addView(label(t("توکن Vercel", "Vercel token"), 14f, palette.teal, 12))
        val tokenInput = input(t("توکن را اینجا بچسبانید", "Paste the token here"), store.token, password = true)
        box.addView(tokenInput, lp(2))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔧 به‌روزرسانی پنل روی Vercel", "🔧 Update a panel on Vercel"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("ادامه", "Continue"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val project = nameInput.text?.toString().orEmpty().trim()
                    .removePrefix("https://").removePrefix("http://").substringBefore('/').removeSuffix(".vercel.app")
                val token = tokenInput.text?.toString().orEmpty().trim()
                if (project.isBlank() || token.length < 10) {
                    toast(t("اسم پروژه و توکن را وارد کنید.", "Enter the project name and the token."))
                    return@setOnClickListener
                }
                val confirm = MaterialAlertDialogBuilder(activity)
                    .setTitle(t("تأیید به‌روزرسانی", "Confirm update"))
                    .setMessage(t(
                        "در پروژهٔ «$project»:\n\n• اگر حافظهٔ دائمی ندارد، یک Blob خصوصی ساخته و وصل می‌شود\n• پنل و فایل اتصال با نسخهٔ جدید دوباره منتشر می‌شوند\n\nUUID، رمز Trojan، رمز پنل و بقیهٔ تنظیمات پروژه دست نمی‌خورند.",
                        "In project “$project”:\n\n• If it has no permanent storage, a private Blob store is created and connected\n• The panel and adapter are published again with the new version\n\nThe UUID, Trojan password, panel password and other project settings are not touched.",
                    ))
                    .setPositiveButton(t("بله، به‌روز کن", "Yes, update")) { _, _ ->
                        dialog.dismiss()
                        runWithProgress(t("⏳ به‌روزرسانی پنل…", "⏳ Updating the panel…")) { step -> runPanelUpdate(token, project, step) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                showDialog(confirm)
            }
        }
        showDialog(dialog)
    }

    private suspend fun runPanelUpdate(token: String, projectRef: String, step: (String) -> Unit): SavedVercelRelay {
        val api = VercelApi(token)
        step(t("۱/۸ بررسی توکن…", "1/8 Checking the token…"))
        api.user()
        store.token = token
        step(t("۲/۸ خواندن پروژه…", "2/8 Reading the project…"))
        val project = api.project(projectRef)
        val projectId = project.optString("id")
        val name = project.optString("name").ifBlank { projectRef }
        val env = api.projectEnv(projectId)
        val uuid = env["UUID"].orEmpty()
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(uuid)) {
            throw VercelApiException(t(
                "UUID این پروژه خوانده نشد. این پروژه پنل Cat نیست یا UUID آن «Sensitive» است. از «🚀 ساخت پنل» یک پنل تازه بسازید.",
                "Could not read this project's UUID. It is not a Cat panel, or its UUID is “Sensitive”. Build a fresh panel with “🚀 Build panel”.",
            ))
        }
        val trojanPass = env["TROJAN_PASS"].orEmpty()
        step("   ✓ $name")
        if (env["DATA_PLANE_ENABLED"].isNullOrBlank()) api.upsertEnv(projectId, "DATA_PLANE_ENABLED", "true")
        step(t("۳/۸ آماده کردن فایل‌های پنل…", "3/8 Preparing the panel files…"))
        val files = panelFiles()
        step(t("۴/۸ حافظهٔ دائمی پنل (Vercel Blob)…", "4/8 Permanent panel storage (Vercel Blob)…"))
        // Vercel returns this as an array (["iad1"]); keep the panel in the region it already runs in.
        val region = project.optJSONObject("resourceConfig")?.optJSONArray("functionDefaultRegions")?.optString(0)
            ?.takeIf { it.length == 4 } ?: "fra1"
        ensureStorage(api, projectId, name, region, env, step)
        val host = publishPanel(api, projectId, name, region, files, step, 5)
        val links = testPanel(host, uuid, trojanPass.ifBlank { uuid }, name, step, 7)
        val saved = SavedVercelRelay(projectId, name, host, PANEL_RUNTIME, System.currentTimeMillis(), VercelPanelPlanner.subUrl(host, uuid), links)
        store.save(saved)
        return saved
    }

    /** Progress dialog + background job; success opens the panel result screen. */
    private fun runWithProgress(title: String, work: suspend ((String) -> Unit) -> SavedVercelRelay) {
        val log = label("", 12.5f, palette.textPrimary, 0).apply { setTextIsSelectable(true) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(log)
        }
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton(t("توقف", "Stop")) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        showDialog(progressDialog)
        val lines = mutableListOf<String>()
        fun step(text: String) = activity.runOnUiThread {
            lines += text
            log.text = lines.joinToString("\n")
        }
        job?.cancel()
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work(::step) } }
            runCatching { progressDialog.dismiss() }
            result.onSuccess { saved -> showPanelResult(saved, lines) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    val dialog = MaterialAlertDialogBuilder(activity)
                        .setTitle(t("❌ انجام نشد", "❌ Failed"))
                        .setMessage((lines + "" + explainError(error)).joinToString("\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    showDialog(dialog)
                }
        }
    }

    private fun showPanelResult(saved: SavedVercelRelay, lines: List<String>) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        val panelUrl = VercelPanelPlanner.panelUrl(saved.host)
        val subUrl = saved.sourceName
        box.addView(label(lines.joinToString("\n"), 12f, palette.textSecondary, 0))
        box.addView(label(t("پنل: ", "Panel: ") + panelUrl + t("\nورود: admin / admin (یا رمزی که دادید)", "\nLogin: admin / admin (or the password you set)") +
            t("\nلینک اشتراک: ", "\nSubscription: ") + subUrl, 12.5f, palette.textPrimary, 10).apply { setTextIsSelectable(true) })
        var dialog: AlertDialog? = null
        box.addView(button(t("➕ افزودن ${saved.links.size} کانفیگ به برنامه", "➕ Add ${saved.links.size} configs to the app"), true) {
            dialog?.dismiss()
            addSubscription(saved.links.joinToString("\n"), "Vercel Panel · ${saved.projectName}")
        }, lp(10))
        box.addView(button(t("🔁 افزودن لینک اشتراک (خودکار به‌روز می‌شود)", "🔁 Add the subscription link (auto-updates)")) {
            dialog?.dismiss()
            addSubscription(subUrl, "Vercel Panel · ${saved.projectName}")
        }, lp(6))
        box.addView(button(t("🔎 پیدا کردن آی‌پی سالم Vercel برای خط من", "🔎 Find Vercel IPs that work on my line")) {
            dialog?.dismiss()
            showIpScanner(saved.links.firstOrNull { VercelIpScanner.target(it) != null } ?: subUrl)
        }, lp(6))
        box.addView(button(t("🌐 باز کردن پنل", "🌐 Open the panel")) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(panelUrl))) }
        }, lp(6))
        box.addView(button(t("📋 کپی همهٔ کانفیگ‌ها", "📋 Copy all configs")) { copy(saved.links.joinToString("\n")) }, lp(6))
        box.addView(button(t("📋 کپی لینک اشتراک", "📋 Copy the subscription link")) { copy(subUrl) }, lp(6))
        box.addView(button(t("🔳 نمایش QR", "🔳 Show QR")) { showQr(saved.links, "Vercel Panel · ${saved.projectName}") }, lp(6))
        box.addView(label(t(
            "همهٔ کانفیگ‌ها روی پورت 443 هستند. هر آدرس سه بار آمده، با سه SNI متفاوت: «vercel.app» خالی، نام پروژه با حروف درهم (Aa) و خود نام پروژه. بعضی اپراتورها نام پروژه را می‌بندند ولی بقیه را نه. بعد از افزودن، «تست پینگ» نشان می‌دهد کدام روی خط شما باز است؛ «🔎 اسکنر» همین را روی همهٔ آی‌پی‌های Vercel پیدا می‌کند.",
            "Every config is on port 443. Each address appears three times with three SNIs: bare «vercel.app», the project name in mixed case (Aa) and the project name itself. Some operators block the project name but not the others. After adding, the ping test shows which are open on your line; the 🔎 scanner finds the same across all Vercel IPs.",
        ), 11.5f, palette.textSecondary, 10))
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("✅ پنل Cat روی Vercel آماده است", "✅ Cat Panel on Vercel is ready"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("بستن", "Close"), null)
            .create()
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // Vercel IP scanner
    // ------------------------------------------------------------------------------------

    fun showIpScanner(preset: String = "") {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        box.addView(label(t(
            "آی‌پی‌های خود Vercel را روی خط اینترنت شما امتحان می‌کند و فقط آن‌هایی را نگه می‌دارد که با همین کانفیگ واقعاً تا اینترنت وصل شدند (یک درخواست واقعی تا example.com از داخل تونل). کانفیگ ساخته‌شده همان کانفیگ شماست، فقط آدرسش آی‌پی سالم است و پورتش همان پورت تست‌شده.",
            "Tries Vercel's own IPs on your internet line and keeps only those where this very config really reached the internet (a real request to example.com through the tunnel). Each result is your config with a working IP as the address and the tested port unchanged.",
        ), 13f, palette.textPrimary, 0))
        box.addView(label(t(
            "ℹ️ آی‌پی تمیز Cloudflare (مثل 104.x یا 188.114.x با پورت 2083 و 2096) فقط برای پنل روی Cloudflare است و به Vercel نمی‌رسد؛ برای همین این اسکنر فقط شبکه‌های Vercel را می‌گردد.",
            "ℹ️ Cloudflare clean IPs (104.x, 188.114.x on ports 2083/2096…) only serve panels on Cloudflare and never reach Vercel, so this scanner searches Vercel's networks only.",
        ), 11.5f, palette.textSecondary, 6))
        box.addView(label(t("۱) کانفیگ یا لینک اشتراک Vercel", "1) A Vercel config or subscription link"), 14f, palette.teal, 12))
        val source = input(t("vless://… یا trojan://… یا https://….vercel.app/sub/…", "vless://… or trojan://… or https://….vercel.app/sub/…"), preset, multiline = true)
        box.addView(source, lp(2))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(t("📋 چسباندن", "📋 Paste")) { source.setText(clipboardText().trim()) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button(t("🐱 پنل‌های من", "🐱 My panels")) {
            val panels = store.list().filter { it.runtime == PANEL_RUNTIME }
            if (panels.isEmpty()) toast(t("هنوز پنلی با برنامه نساخته‌اید.", "No panel built with the app yet."))
            else {
                val d = MaterialAlertDialogBuilder(activity)
                    .setItems(panels.map { it.projectName }.toTypedArray()) { dd, which ->
                        dd.dismiss()
                        source.setText(panels[which].links.firstOrNull { VercelIpScanner.target(it) != null } ?: panels[which].sourceName)
                    }
                    .create()
                showDialog(d)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        box.addView(row, lp(4))
        box.addView(button(t("📂 از اشتراک‌های برنامه", "📂 From my subscriptions")) {
            val subs = runCatching { subscriptions().list() }.getOrDefault(emptyList())
            val usable = subs.filter { sub ->
                val text = sub.input.trim()
                text.startsWith("https://") || VercelIpScanner.firstTarget(text) != null ||
                    runCatching { decodeMaybeBase64(text)?.let { VercelIpScanner.firstTarget(it) } != null }.getOrDefault(false)
            }
            if (usable.isEmpty()) toast(t("اشتراکی با کانفیگ WS روی دامنه پیدا نشد.", "No subscription with a WS config on a domain."))
            else {
                val d = MaterialAlertDialogBuilder(activity)
                    .setItems(usable.map { it.name }.toTypedArray()) { dd, which -> dd.dismiss(); source.setText(usable[which].input.trim()) }
                    .create()
                showDialog(d)
            }
        }, lp(4))

        box.addView(label(t("۲) نوع اسکن", "2) Scan type"), 14f, palette.teal, 12))
        val modeGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val quick = RadioButton(activity).apply {
            id = View.generateViewId(); isChecked = true; setTextColor(palette.textPrimary)
            text = t("سریع: ۶۰ آی‌پی (کمتر از ۱ دقیقه)", "Quick: 60 IPs (under a minute)")
        }
        val deep = RadioButton(activity).apply {
            id = View.generateViewId(); setTextColor(palette.textPrimary)
            text = t("کامل: همهٔ ۱۶۷ آی‌پی زندهٔ Vercel (۱ تا ۳ دقیقه)", "Full: all 167 live Vercel IPs (1–3 minutes)")
        }
        modeGroup.addView(quick); modeGroup.addView(deep)
        box.addView(modeGroup)
        box.addView(label(t("۳) چند آی‌پی سالم نگه دارد؟", "3) How many working IPs to keep?"), 14f, palette.teal, 12))
        val keep = input("10", "10").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(keep, lp(2))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔎 اسکنر آی‌پی Vercel", "🔎 Vercel IP scanner"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("شروع اسکن", "Start scan"), null)
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val text = source.text?.toString().orEmpty().trim()
                if (text.isBlank()) { toast(t("یک کانفیگ یا لینک اشتراک Vercel بدهید.", "Give a Vercel config or subscription link.")); return@setOnClickListener }
                val count = keep.text?.toString()?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                dialog.dismiss()
                runScan(text, modeGroup.checkedRadioButtonId == deep.id, count)
            }
        }
        showDialog(dialog)
    }

    private fun runScan(sourceText: String, deep: Boolean, keep: Int) {
        val status = label(t("در حال آماده‌سازی…", "Preparing…"), 13f, palette.textPrimary, 0)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(status)
        }
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔎 اسکن آی‌پی‌های Vercel…", "🔎 Scanning Vercel IPs…"))
            .setView(box)
            .setNegativeButton(t("توقف", "Stop")) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        showDialog(progress)
        fun say(text: String) = activity.runOnUiThread { status.text = text }
        job?.cancel()
        job = scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    // 1) resolve the config to test with
                    val text = if (sourceText.startsWith("https://") && VercelIpScanner.firstTarget(sourceText) == null) {
                        say(t("خواندن لینک اشتراک…", "Reading the subscription…"))
                        val r = httpGet(sourceText, 25_000, 512 * 1024)
                        if (r.code != 200) throw VercelApiException(t(
                            "لینک اشتراک جواب نداد (${if (r.timedOut) "timeout" else r.error.ifBlank { "HTTP ${r.code}" }}). اگر کاربر را در پنلِ بدون حافظهٔ دائمی ساخته‌اید پاک شده است: «🔧 به‌روزرسانی پنل» را بزنید. یا خود یک کانفیگ را بچسبانید.",
                            "The subscription did not answer (${if (r.timedOut) "timeout" else r.error.ifBlank { "HTTP ${r.code}" }}). If the user was made in a panel without permanent storage it is gone: run “🔧 Update panel”. Or paste one config instead.",
                        ))
                        r.body
                    } else sourceText
                    val target = VercelIpScanner.firstTarget(text) ?: runCatching { decodeMaybeBase64(text)?.let { VercelIpScanner.firstTarget(it) } }.getOrNull()
                        ?: throw VercelApiException(t(
                            "کانفیگ قابل اسکن پیدا نشد. باید VLESS یا Trojan با WS و TLS روی یک دامنه باشد (مثل کانفیگ‌های پنل Cat روی Vercel).",
                            "No scannable config found. It must be VLESS or Trojan over WS + TLS on a domain (like the Cat panel's Vercel configs).",
                        ))
                    // 2) stage 0: which SNI passes on THIS line? (bare vercel.app / mixed-case / project name,
                    //    each tried on three known edge IPs — Iranian mobile ISPs choke some of them)
                    val sniAll = VercelIpScanner.sniCandidates(target)
                    val anchors = listOf("76.76.21.21", "216.198.79.65", "64.29.17.65")
                    say(t("بررسی SNIها روی خط شما…", "Checking SNIs on your line…"))
                    val sniReport = kotlinx.coroutines.coroutineScope {
                        sniAll.map { sni -> async { sni to anchors.map { ip -> async { VercelPanelProbe.tls(ip, target.port, sni) } }.awaitAll().count { it.ok } } }.awaitAll()
                    }
                    // SNIs that passed go first; the others are still tried (an anchor may be dead while other IPs live)
                    val sniOrder = sniReport.sortedByDescending { it.second }.map { it.first }
                    // 3) stage 1: TLS handshake per IP, first SNI that passes wins (fast, wide)
                    val candidates = VercelIpScanner.candidates(deep)
                    val tlsOk = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Long>>())
                    val done = java.util.concurrent.atomic.AtomicInteger()
                    val gate = kotlinx.coroutines.sync.Semaphore(32)
                    kotlinx.coroutines.coroutineScope {
                        candidates.map { ip ->
                            launch {
                                gate.acquire()
                                try {
                                    for (sni in sniOrder) {
                                        val r = VercelPanelProbe.tls(ip, target.port, sni)
                                        if (r.ok) { tlsOk += Triple(ip, sni, r.millis); break }
                                    }
                                } finally {
                                    gate.release()
                                    val n = done.incrementAndGet()
                                    if (n % 4 == 0 || n == candidates.size) say(t(
                                        "مرحلهٔ ۱ از ۲ (دست‌دادن TLS با ${sniOrder.size} SNI): $n از ${candidates.size} · جواب داد: ${tlsOk.size}",
                                        "Stage 1 of 2 (TLS with ${sniOrder.size} SNIs): $n of ${candidates.size} · answered: ${tlsOk.size}",
                                    ))
                                }
                            }
                        }.forEach { it.join() }
                    }
                    // 3) stage 2: real tunnel through each TLS winner (fastest first)
                    val ordered = tlsOk.sortedBy { it.third }.take(60)
                    val hits = java.util.Collections.synchronizedList(mutableListOf<VercelScanHit>())
                    val fails = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val done2 = java.util.concurrent.atomic.AtomicInteger()
                    val gate2 = kotlinx.coroutines.sync.Semaphore(8)
                    kotlinx.coroutines.coroutineScope {
                        ordered.map { (ip, sni, tlsMs) ->
                            launch {
                                gate2.acquire()
                                try {
                                    if (hits.size >= keep * 2) return@launch
                                    val r = VercelPanelProbe.tunnel(ip, target.port, sni, target.host, target.path, 9_000) { http -> VercelIpScanner.firstRequest(target, http) }
                                    if (r.ok) hits += VercelScanHit(ip, target.port, tlsMs, r.millis, sni) else fails += r.detail
                                } finally {
                                    gate2.release()
                                    val n2 = done2.incrementAndGet()
                                    say(t(
                                        "مرحلهٔ ۲ از ۲ (اتصال واقعی تونل تا example.com): $n2 از ${ordered.size} · سالم: ${hits.size}",
                                        "Stage 2 of 2 (real tunnel to example.com): $n2 of ${ordered.size} · working: ${hits.size}",
                                    ))
                                }
                            }
                        }.forEach { it.join() }
                    }
                    ScanOutcome(target, candidates.size, tlsOk.size, hits.sortedBy { it.tunnelMillis }.take(keep), sniReport, anchors.size, fails.toList())
                }
            }
            runCatching { progress.dismiss() }
            outcome.onSuccess { o -> showScanResult(o) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    val d = MaterialAlertDialogBuilder(activity)
                        .setTitle(t("❌ اسکن انجام نشد", "❌ Scan failed"))
                        .setMessage(explainError(error))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    showDialog(d)
                }
        }
    }

    private class ScanOutcome(
        val target: VercelScanTarget,
        val tried: Int,
        val tlsCount: Int,
        val hits: List<VercelScanHit>,
        val sniReport: List<Pair<String, Int>>,
        val anchors: Int,
        val failures: List<String> = emptyList(),
    )

    private fun showScanResult(o: ScanOutcome) {
        val target = o.target
        val hits = o.hits
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        box.addView(label(t(
            "${o.tried} آی‌پی امتحان شد · ${o.tlsCount} دست‌دادن TLS موفق · ${hits.size} آی‌پی با تونل واقعی سالم بود.",
            "${o.tried} IPs tried · ${o.tlsCount} TLS handshakes OK · ${hits.size} IPs passed the real tunnel test.",
        ), 13f, palette.textPrimary, 0))
        if (o.sniReport.size > 1) {
            val rows = o.sniReport.joinToString("\n") { (sni, ok) ->
                (if (ok > 0) "✅ " else "❌ ") + VercelIpScanner.sniLabel(sni, target.host) + " · $ok/${o.anchors}"
            }
            box.addView(label(t("SNI روی خط شما (دست‌دادن TLS با ${o.anchors} آی‌پی اصلی Vercel):", "SNI on your line (TLS with ${o.anchors} main Vercel IPs):") + "\n" + rows, 12f, palette.textPrimary, 8))
            box.addView(label(t(
                "هر کانفیگ پایین با همان SNI ساخته شده که روی خط شما رد شد. ❌ یعنی اپراتور شما آن نام را می‌بندد.",
                "Every config below uses exactly the SNI that passed on your line. ❌ means your operator blocks that name.",
            ), 11.5f, palette.textSecondary, 4))
        }
        if (hits.isEmpty()) {
            // 6.69.43: say WHY in plain words (SNI block / TLS cut / Vercel 421 / 403 / 404 / no tunnel answer)
            val cause = VercelScanDiagnosis.cause(o.tlsCount, o.sniReport.count { it.second > 0 }, 0, o.failures)
            box.addView(label(VercelScanDiagnosis.explain(cause, isFarsi()), 12.5f, palette.textPrimary, 8))
            o.failures.groupingBy { it.take(60) }.eachCount().entries.sortedByDescending { it.value }.take(3).takeIf { it.isNotEmpty() }?.let { top ->
                box.addView(label(top.joinToString("\n") { "· " + it.key + " ×" + it.value }, 11.5f, palette.textSecondary, 4).apply { setTextIsSelectable(true) })
            }
            box.addView(label(t(
                "هیچ آی‌پی Vercel روی خط شما تونل را رد نکرد. «کامل» را امتحان کنید، یا خط/اپراتور دیگری. اگر مرحلهٔ TLS هم صفر بود، Vercel روی این خط کامل بسته است.",
                "No Vercel IP carried the tunnel on your line. Try “Full”, or another line/operator. If the TLS stage was also zero, Vercel is fully blocked on this line.",
            ), 12f, palette.textSecondary, 8))
        } else {
            box.addView(label(hits.joinToString("\n") { "▲ ${it.ip}:${it.port} · SNI ${VercelIpScanner.sniLabel(it.sni.ifBlank { target.sni }, target.host)} · ${it.tunnelMillis} ms" }, 12.5f, palette.textPrimary, 8).apply { setTextIsSelectable(true) })
        }
        val links = hits.mapIndexed { i, hit -> VercelIpScanner.linkFor(target, hit, i + 1) }
        var dialog: AlertDialog? = null
        if (links.isNotEmpty()) {
            box.addView(button(t("➕ افزودن ${links.size} کانفیگ به برنامه", "➕ Add ${links.size} configs to the app"), true) {
                dialog?.dismiss()
                addSubscription(links.joinToString("\n"), "Vercel IPs · ${target.sni.substringBefore('.')}")
            }, lp(10))
            box.addView(button(t("📋 کپی کانفیگ‌ها", "📋 Copy the configs")) { copy(links.joinToString("\n")) }, lp(6))
            box.addView(button(t("📋 کپی فقط آی‌پی‌ها", "📋 Copy the IPs only")) { copy(hits.joinToString("\n") { it.ip }) }, lp(6))
            box.addView(button(t("🔳 QR", "🔳 QR")) { showQr(links, "Vercel IPs") }, lp(6))
            box.addView(label(t(
                "این آی‌پی‌ها برای خط فعلی شما تست شده‌اند؛ روی اینترنت دیگر دوباره اسکن کنید.",
                "These IPs were tested on your current line; scan again on another network.",
            ), 11.5f, palette.textSecondary, 8))
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("نتیجهٔ اسکن Vercel", "Vercel scan result"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("بستن", "Close"), null)
            .create()
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // My projects
    // ------------------------------------------------------------------------------------

    fun showProjects() {
        val items = store.list().sortedByDescending { it.createdAt }
        if (items.isEmpty()) {
            toast(t("هنوز پروژه‌ای با برنامه نساخته‌اید.", "No projects created with the app yet."))
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = items.map { "${it.projectName} · ${it.runtime}\n${it.host} · ${format.format(Date(it.createdAt))}" }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("📁 پروژه‌های Vercel من", "📁 My Vercel projects"))
            .setItems(labels.toTypedArray()) { d, which ->
                d.dismiss()
                showProjectActions(items[which])
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        showDialog(dialog)
    }

    private fun showProjectActions(item: SavedVercelRelay) {
        if (item.runtime == PANEL_RUNTIME) { showPanelActions(item); return }
        val options = arrayOf(
            t("➕ افزودن دوبارهٔ کانفیگ‌ها به برنامه", "➕ Add the configs to the app again"),
            t("📋 کپی کانفیگ‌ها", "📋 Copy the configs"),
            t("🌐 باز کردن صفحهٔ پروژه در Vercel", "🌐 Open the project on Vercel"),
            t("🗑️ پاک کردن پروژه از حساب Vercel", "🗑️ Delete the project from Vercel"),
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(item.projectName)
            .setItems(options) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> addSubscription(item.links.joinToString("\n"), "Vercel · ${item.projectName}")
                    1 -> copy(item.links.joinToString("\n"))
                    2 -> runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vercel.com/dashboard"))) }
                    3 -> confirmDelete(item)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun showPanelActions(item: SavedVercelRelay) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val status = label(t("⏳ در حال بررسی وضعیت پنل…", "⏳ Checking the panel…"), 12.5f, palette.textPrimary, 0)
        box.addView(status)
        // shown only when the check says the panel is old / has no storage (the «0 configs» cause)
        val fixRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.addView(fixRow)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("🐱 " + item.projectName)
            .setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun action(text: String, filled: Boolean = false, run: () -> Unit) =
            button(text, filled) { dialog.dismiss(); run() }
        fixRow.addView(action(t("🔧 همین حالا درستش کن: به‌روزرسانی پنل + حافظهٔ دائمی", "🔧 Fix it now: update the panel + permanent storage"), true) {
            showUpdatePanel(item.projectName)
        }, lp(6))
        val actions: List<Pair<String, () -> Unit>> = listOf(
            t("🌐 باز کردن پنل", "🌐 Open the panel") to {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VercelPanelPlanner.panelUrl(item.host)))) }; Unit
            },
            t("➕ افزودن دوبارهٔ کانفیگ‌ها به برنامه", "➕ Add the configs to the app again") to {
                addSubscription(item.links.joinToString("\n"), "Vercel Panel · ${item.projectName}")
            },
            t("🔁 افزودن لینک اشتراک", "🔁 Add the subscription link") to {
                addSubscription(item.sourceName, "Vercel Panel · ${item.projectName}")
            },
            t("📋 کپی لینک اشتراک", "📋 Copy the subscription link") to { copy(item.sourceName) },
            t("🔎 اسکنر آی‌پی Vercel برای این پنل", "🔎 Vercel IP scanner for this panel") to {
                showIpScanner(item.links.firstOrNull { VercelIpScanner.target(it) != null } ?: item.sourceName)
            },
            t("🔧 به‌روزرسانی پنل (+ حافظهٔ دائمی)", "🔧 Update the panel (+ permanent storage)") to { showUpdatePanel(item.projectName) },
            t("🗑️ پاک کردن پروژه از حساب Vercel", "🗑️ Delete the project from Vercel") to { confirmDelete(item) },
        )
        actions.forEach { (text, run) -> box.addView(action(text, run = run), lp(6)) }
        showDialog(dialog)

        val bundled = runCatching {
            PanelUpdate.parseVersion(activity.assets.open(VercelPanelPlanner.PANEL_ASSET).bufferedReader().use { it.readText() })
        }.getOrNull().orEmpty()
        scope.launch {
            val probe = withContext(Dispatchers.IO) { httpGet("https://${item.host}/api/version", 15_000) }
            val health = VercelPanelPlanner.health(probe.code, probe.body, bundled, probe.vercelError.ifBlank { probe.error })
            if (!dialog.isShowing) return@launch
            status.text = healthText(health)
            status.setTextColor(
                when (health) {
                    is VercelPanelHealth.Ok -> palette.teal
                    is VercelPanelHealth.Unreachable -> palette.red
                    else -> palette.amber
                },
            )
            fixRow.visibility = if (health.needsUpdate) View.VISIBLE else View.GONE
        }
    }

    /** Plain-language status line for a deployed panel: what it means and what to press. */
    private fun healthText(health: VercelPanelHealth): String = when (health) {
        is VercelPanelHealth.Ok -> t(
            "✅ پنل سالم است · نسخهٔ ${health.version} · حافظهٔ دائمی دارد؛ کاربرهایی که در پنل می‌سازی در همهٔ برنامه‌ها کانفیگ می‌گیرند.",
            "✅ Panel is healthy · version ${health.version} · permanent storage on; users you create get configs in every app.",
        )
        is VercelPanelHealth.NoStorage -> t(
            "⚠️ این پنل حافظهٔ دائمی ندارد (نسخهٔ ${health.version.ifBlank { "?" }}). برای همین لینک کاربرهایی که در پنل ساخته‌ای در ZedSecure / v2rayNG «۰ کانفیگ» نشان می‌دهد. " +
                "دکمهٔ «🔧 همین حالا درستش کن» را بزن؛ UUID و رمزها عوض نمی‌شوند. بعدش یک کاربر تازه بساز و لینک همان را وارد کن.",
            "⚠️ This panel has no permanent storage (version ${health.version.ifBlank { "?" }}), so links of users made in the panel show “0 configs” in ZedSecure / v2rayNG. " +
                "Tap “🔧 Fix it now”; UUID and passwords stay the same. Then create a new user and import that user's link.",
        )
        is VercelPanelHealth.Outdated -> t(
            "🆕 نسخهٔ پنل ${health.version.ifBlank { "?" }} است و این برنامه ${health.bundled} را دارد. «🔧 همین حالا درستش کن» را بزن تا تازه شود؛ UUID و رمزها و کاربرها می‌مانند.",
            "🆕 The panel runs ${health.version.ifBlank { "?" }}; this app ships ${health.bundled}. Tap “🔧 Fix it now” to update it; UUID, passwords and users are kept.",
        )
        is VercelPanelHealth.Unreachable -> t(
            "❌ پنل جواب نداد (${health.detail}). شاید پروژه پاک یا متوقف شده، یا اینترنت فعلی Vercel را باز نمی‌کند؛ با VPN روشن یا خط دیگر دوباره امتحان کن.",
            "❌ The panel did not answer (${health.detail}). The project may be deleted or paused, or this network blocks Vercel; try again with the VPN on or another line.",
        )
    }

    private fun confirmDelete(item: SavedVercelRelay) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("پاک کردن ${item.projectName}؟", "Delete ${item.projectName}?"))
            .setMessage(t(
                "پروژه از حساب Vercel شما پاک می‌شود و کانفیگ‌هایی که با آن ساخته شده‌اند دیگر کار نمی‌کنند. اشتراک «Vercel» داخل برنامه دست‌نخورده می‌ماند (خودتان پاکش کنید).",
                "The project is deleted from your Vercel account and its configs stop working. The “Vercel” subscription in the app is left as is (delete it yourself).",
            ))
            .setPositiveButton(t("پاک کن", "Delete")) { _, _ ->
                val token = store.token
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { VercelApi(token).deleteProject(item.projectId) } }
                    val gone = result.isSuccess || (result.exceptionOrNull() as? VercelApiException)?.status == 404
                    if (gone) {
                        store.remove(item.projectId)
                        toast(t("پاک شد", "Deleted"))
                    } else {
                        toast(explainError(result.exceptionOrNull() ?: Exception("?")))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }
}
