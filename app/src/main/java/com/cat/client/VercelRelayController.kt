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
 * token, then builds ready configs (vercel.com front, project domain, Vercel IPs) and adds them.
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
            "Vercel مثل یک واسطهٔ تمیز است: گوشی شما فقط با vercel.com حرف می‌زند و Vercel ترافیک را به سرور XHTTP شما می‌رساند. " +
                "به درد شما می‌خورد اگر یک کانفیگ XHTTP دارید که آدرسش فیلتر شده، یا یک سرور شخصی (VPS) دارید.",
            "Vercel acts as a clean middleman: your phone only talks to vercel.com and Vercel carries the traffic to your XHTTP server. " +
                "Useful when you have an XHTTP config whose address got filtered, or your own server (VPS).",
        ), 13f, palette.textPrimary, 0))
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

➕ «افزودن به برنامه»: کانفیگ‌های ساخته‌شده به‌صورت یک اشتراک «Vercel» اضافه می‌شوند؛ بعد مثل بقیه پینگ و وصل کنید. اول «vercel.com» را امتحان کنید، اگر نشد بقیه را.

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

➕ “Add to app”: the configs are added as a “Vercel” subscription; ping and connect like any other. Try “vercel.com” first, then the others.

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
        val aliases = mutableListOf<String>()
        deployment.optJSONArray("alias")?.let { arr -> for (i in 0 until arr.length()) aliases += arr.optString(i) }
        if (aliases.none { it.endsWith(".vercel.app") }) aliases += api.projectDomains(projectId)
        val host = VercelRelayPlanner.pickProductionHost(aliases, name) ?: "$name.vercel.app"
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
            if (r.code == 401 || r.code == 403) {
                return t("   ⚠️ پروژه قفل «Vercel Authentication» دارد. در داشبورد Vercel ← پروژه ← Settings ← Deployment Protection آن را خاموش کنید.", "   ⚠️ The project is behind “Vercel Authentication”. Turn it off in Vercel → project → Settings → Deployment Protection.")
            }
            delay(4_000)
        }
        if (!pageOk) {
            return t("   ⚠️ از این شبکه به ‎$host نرسیدیم ($lastError). ممکن است دامنه روی خط شما بسته باشد؛ کانفیگ «vercel.com» و آی‌پی‌ها را در برنامه تست کنید.", "   ⚠️ Could not reach $host from this network ($lastError). The domain may be blocked on your line; test the vercel.com and IP configs in the app.")
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

    private data class Probe(val code: Int, val body: String, val vercelError: String, val timedOut: Boolean, val error: String)

    private fun httpGet(url: String, timeoutMs: Int): Probe = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) CatClient")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = runCatching { stream?.bufferedReader()?.use { r -> CharArray(400).let { buf -> val n = r.read(buf); if (n > 0) String(buf, 0, n) else "" } } }.getOrNull().orEmpty()
            Probe(code, body, c.getHeaderField("x-vercel-error").orEmpty(), false, "")
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
            "ترتیب امتحان: ۱) vercel.com ۲) دامنهٔ پروژه ۳) آی‌پی‌های Vercel. بعد از افزودن، «تست پینگ» برنامه آن‌هایی که روی خط شما کار می‌کنند را نشان می‌دهد.",
            "Try order: 1) vercel.com 2) project domain 3) Vercel IPs. After adding, the app's ping test shows which work on your line.",
        ), 11.5f, palette.textSecondary, 10))
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("✅ کانفیگ Vercel آماده است", "✅ Vercel config is ready"))
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
