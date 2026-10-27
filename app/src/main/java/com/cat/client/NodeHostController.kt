package com.cat.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 6.69.45 Cloud tab → one card per Node host (KataBump · Lucity · Shiprr), each with the same buttons:
 * ⚡ automatic install · 📦 save the files · ✍️ add my panel · 🔁 renew/update · 📋 my panels · ❓ guide.
 *
 * KataBump: its control panel is Pterodactyl, so with the user's own client API key (ptlc_…) the app uploads the
 * 4 files, points the start file at index.js, moves the server to Node 20+, restarts it and waits until the panel
 * answers — then adds the configs. Renewal on KataBump has a captcha, so the app cannot press it; it keeps a
 * countdown and sends a reminder a day before the deadline.
 * Lucity / Shiprr build from a GitHub repository: with the user's GitHub token the app creates a PRIVATE repo with
 * the 4 files; the user picks that repo on the host (2–3 taps, shown step by step), then «add my panel».
 */
class NodeHostController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val isFarsi: () -> Boolean,
    private val addSubscription: (content: String, name: String) -> Unit,
    private val showDialog: (AlertDialog) -> Unit,
    private val requestNotifications: () -> Unit,
) {
    private val palette by lazy { CatClientDesignTokens.forContext(activity) }
    private val store by lazy { NodeHostStore(activity) }
    private var job: Job? = null

    private data class Outcome(
        val saved: SavedNodeHost?,
        val links: List<String>,
        val ok: Boolean,
        val headline: String,
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun t(fa: String, en: String) = if (isFarsi()) fa else en
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun lp(top: Int = 6) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun name(kind: String) = NodeHostPanel.hostName(kind)

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

    private fun input(hint: String, initial: String = "", password: Boolean = false): EditText =
        EditText(activity).apply {
            this.hint = hint
            setText(initial)
            textSize = 13f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            textDirection = View.TEXT_DIRECTION_LTR
        }

    private fun column(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(8))
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    private fun scroll(box: View) = ScrollView(activity).apply { addView(box) }

    private fun copy(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Cat Client", text))
        toast(t("کپی شد", "Copied"))
    }

    private fun clipboardText(): String {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull().orEmpty()
    }

    private fun open(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { copy(url) }
    }

    private fun message(title: String, text: String) {
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll(column().apply { addView(label(text, 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) }) }))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    // ------------------------------------------------------------------------------------
    // files: launcher (assets/node-panel) + cat-config.json with this phone's UUID / Trojan password
    // ------------------------------------------------------------------------------------

    private fun identity(kind: String): Pair<String, String> =
        store.pending(kind) ?: Pair(NodeHostPanel.newUuid(), NodeHostPanel.newTrojanPass()).also { store.setPending(kind, it.first, it.second) }

    /** A fresh identity for the next panel of this kind (after one was added, a second server must not share it). */
    private fun rotateIdentity(kind: String) = store.setPending(kind, NodeHostPanel.newUuid(), NodeHostPanel.newTrojanPass())

    private fun files(kind: String): Map<String, ByteArray> {
        val (uuid, pass) = identity(kind)
        val out = LinkedHashMap<String, ByteArray>()
        for (f in NodeHostPanel.LAUNCHER_FILES) out[f] = activity.assets.open("node-panel/$f").use { it.readBytes() }
        // KataBump keeps a disk; Lucity / Shiprr containers start empty after every deploy.
        out[NodeHostPanel.CONFIG_FILE] = NodeHostPanel.configJson(uuid, pass, noDisk = kind != NodeHostPanel.KATABUMP).toByteArray()
        return out
    }

    // ------------------------------------------------------------------------------------
    // long work with a live step list + Stop
    // ------------------------------------------------------------------------------------

    private fun <T> runSteps(title: String, work: suspend ((String) -> Unit) -> T, done: (T, List<String>) -> Unit) {
        val log = label("", 12.5f, palette.textPrimary, 0).apply { setTextIsSelectable(true) }
        val box = column().apply { addView(log) }
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scroll(box))
            .setNegativeButton(t("توقف", "Stop")) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        showDialog(progress)
        val lines = mutableListOf<String>()
        fun step(text: String) = activity.runOnUiThread {
            lines += text
            log.text = lines.joinToString("\n")
        }
        job?.cancel()
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work(::step) } }
            runCatching { progress.dismiss() }
            result.onSuccess { done(it, lines.toList()) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    message(t("❌ انجام نشد", "❌ Failed"), (lines + "" + explain(error)).joinToString("\n"))
                }
        }
    }

    private fun explain(error: Throwable): String = when {
        error is NodeHostApiException && (error.code == 401 || error.code == 403) -> t(
            "کلید/توکن پذیرفته نشد (" + error.code + "). کلید را دوباره بسازید و کامل کپی کنید. اگر KataBump دسترسی API را بسته باشد، از «📦 ذخیرهٔ فایل‌ها» استفاده کنید (۳ دقیقه کار دستی).",
            "The key/token was refused (" + error.code + "). Create it again and copy all of it. If KataBump has the API switched off, use «📦 Save the files» (3 minutes by hand).",
        )
        error is NodeHostApiException && error.code == 404 -> t(
            "پیدا نشد (404): " + error.message.orEmpty(),
            "Not found (404): " + error.message.orEmpty(),
        )
        error is NodeHostApiException && error.code == 422 -> t(
            "قبول نشد (422): " + error.message.orEmpty() + "\nاگر مخزنی با همین نام دارید، دوباره بزنید (نام تازه ساخته می‌شود).",
            "Rejected (422): " + error.message.orEmpty() + "\nIf a repository with this name exists, try again (a new name is picked).",
        )
        error is UnknownHostException || error is ConnectException || error is SocketTimeoutException ||
            (error is NodeHostApiException && error.code == 0) -> t(
            "به سایت نرسیدیم (" + error.javaClass.simpleName + "). اگر control.katabump.com یا github.com روی اینترنت شما فیلتر است، اول VPN/کانفیگ را وصل کنید و دوباره بزنید.",
            "Could not reach the site (" + error.javaClass.simpleName + "). If control.katabump.com or github.com is filtered on your line, connect a VPN/config first and try again.",
        )
        else -> (error.message ?: error.javaClass.simpleName)
    }

    // ------------------------------------------------------------------------------------
    // ❓ guide — plain words: «tap this → this happens»
    // ------------------------------------------------------------------------------------

    fun showGuide(kind: String) {
        val text = when (kind) {
            NodeHostPanel.KATABUMP -> t(
                "KataBump یک هاست رایگان Node.js است. پنل Cat روی آن بالا می‌آید و یک کانفیگ VLESS و یک Trojan روی «آی‌پی:پورت» سرور می‌دهد.\n\n" +
                    "⚠️ قوانین KataBump (بند 2.4) پروکسی/VPN را ممنوع کرده؛ ممکن است سرور یا حساب را پاک کنند. برای چیز مهم از آن استفاده نکنید.\n\n" +
                    "🟢 راه آسان (خودکار):\n" +
                    "1) katabump.com ← ثبت‌نام رایگان ← یک سرور Node.js بسازید.\n" +
                    "2) «⚡ نصب خودکار» را بزنید ← دکمهٔ «باز کردن صفحهٔ کلید» ← Create API Key ← کلیدی که با ptlc_ شروع می‌شود را کپی کنید ← برگردید و «چسباندن» ← ادامه.\n" +
                    "3) سرورتان را انتخاب کنید. برنامه فایل‌ها را می‌گذارد، Node را 20 یا بالاتر می‌کند، روشن می‌کند، صبر می‌کند تا پنل جواب بدهد و کانفیگ‌ها را اضافه می‌کند.\n\n" +
                    "✋ راه دستی (اگر خودکار نشد):\n" +
                    "1) «📦 ذخیرهٔ فایل‌ها» ← فایل zip در پوشهٔ Download گوشی ذخیره می‌شود.\n" +
                    "2) control.katabump.com ← سرورتان ← Files ← Upload ← همان zip ← روی zip سه‌نقطه ← Unarchive.\n" +
                    "3) Startup ← فایل اصلی (JS file) = index.js و Docker Image = Node 20 یا بالاتر.\n" +
                    "4) Console ← Start. صبر کنید تا «Cat Panel … listening» بیاید.\n" +
                    "5) آدرس بالای صفحهٔ سرور (مثل 1.2.3.4:25565) را کپی کنید ← «✍️ پنلم را اضافه کن».\n\n" +
                    "🔁 تمدید: KataBump هر ۴ روز تمدید می‌خواهد وگرنه سرور پاک می‌شود. تمدید کپچا دارد، پس برنامه نمی‌تواند به‌جای شما بزند؛ ولی شمارش معکوس نشان می‌دهد و یک روز قبل یادآوری می‌فرستد. «🔁 تمدید» را بزنید ← صفحهٔ تمدید باز می‌شود ← Renew ← برگردید و «✅ تمدید کردم».\n\n" +
                    "🔒 بهتر شدن: کانفیگ آی‌پی:پورت بدون TLS است. با یک دامنه پشت Cloudflare کانفیگ TLS هم می‌گیرید (دکمهٔ «🌐 دامنه + TLS» در «📋 پنل‌های من»).",
                "KataBump is a free Node.js host. The Cat panel runs on it and gives one VLESS and one Trojan config on the server's «IP:port».\n\n" +
                    "⚠️ KataBump's rules (section 2.4) forbid proxies/VPNs; they may delete the server or the account. Do not rely on it for anything important.\n\n" +
                    "🟢 Easy way (automatic):\n" +
                    "1) katabump.com → free sign-up → create a Node.js server.\n" +
                    "2) Tap «⚡ Install automatically» → «Open the key page» → Create API Key → copy the key that starts with ptlc_ → come back, «Paste» → Continue.\n" +
                    "3) Pick your server. The app uploads the files, moves it to Node 20+, starts it, waits until the panel answers and adds the configs.\n\n" +
                    "✋ By hand (if automatic fails):\n" +
                    "1) «📦 Save the files» → a zip lands in the phone's Download folder.\n" +
                    "2) control.katabump.com → your server → Files → Upload → that zip → three dots on the zip → Unarchive.\n" +
                    "3) Startup → main file (JS file) = index.js and Docker Image = Node 20 or newer.\n" +
                    "4) Console → Start. Wait for «Cat Panel … listening».\n" +
                    "5) Copy the address at the top of the server page (like 1.2.3.4:25565) → «✍️ Add my panel».\n\n" +
                    "🔁 Renewal: KataBump wants a renewal every 4 days or deletes the server. Renewal has a captcha, so the app cannot press it for you; it shows a countdown and reminds you a day before. Tap «🔁 Renew» → the renew page opens → Renew → come back, «✅ I renewed».\n\n" +
                    "🔒 Better: an IP:port config has no TLS. With a domain behind Cloudflare you also get TLS configs («🌐 Domain + TLS» under «📋 My panels»).",
            )
            NodeHostPanel.LUCITY -> t(
                "Lucity یک هاست اروپایی است که برنامه را از یک مخزن GitHub می‌سازد. ۱۴ روز (۵ یورو اعتبار) رایگان، بعد پولی. کانفیگ‌ها TLS روی 443 هستند.\n\n" +
                    "1) اگر GitHub ندارید: «⚡ ساخت خودکار» ← «ثبت‌نام GitHub».\n" +
                    "2) «⚡ ساخت خودکار» ← «ساخت توکن» (صفحه آماده باز می‌شود) ← پایین صفحه Generate token ← کپی ← «چسباندن» ← ادامه. برنامه یک مخزن خصوصی با ۴ فایل می‌سازد.\n" +
                    "3) دکمهٔ «باز کردن Lucity» ← Login with GitHub ← اجازهٔ دسترسی به همان مخزن را بدهید.\n" +
                    "4) New ← GitHub Repository ← همان مخزن ← ساخت.\n" +
                    "5) Settings ← Platform Domain ← Generate Domain (بدون این آدرس ندارید).\n" +
                    "6) وقتی ساخت سبز شد، آدرس را کپی کنید ← «✍️ پنلم را اضافه کن». برنامه پنل و WebSocket را تست می‌کند و کانفیگ‌ها را اضافه می‌کند.\n\n" +
                    "🔁 «به‌روزرسانی» فایل‌های همین نسخهٔ برنامه را دوباره در مخزن می‌گذارد تا Lucity از نو بسازد.\n" +
                    "💾 بدون Volume، کاربرهای ساخته‌شده در پنل با هر ساخت دوباره پاک می‌شوند (کانفیگ اصلی می‌ماند چون UUID در cat-config.json است).",
                "Lucity is a European host that builds the app from a GitHub repository. 14 days (5 EUR credit) free, then paid. Configs are TLS on 443.\n\n" +
                    "1) No GitHub account? «⚡ Build automatically» → «GitHub sign-up».\n" +
                    "2) «⚡ Build automatically» → «Create a token» (the page opens pre-filled) → Generate token at the bottom → copy → «Paste» → Continue. The app creates a private repository with 4 files.\n" +
                    "3) «Open Lucity» → Login with GitHub → allow access to that repository.\n" +
                    "4) New → GitHub Repository → that repository → create.\n" +
                    "5) Settings → Platform Domain → Generate Domain (without it there is no address).\n" +
                    "6) When the build is green, copy the address → «✍️ Add my panel». The app tests the panel and WebSocket and adds the configs.\n\n" +
                    "🔁 «Update» puts this app version's files into the repository again so Lucity rebuilds.\n" +
                    "💾 Without a Volume, users created in the panel vanish on each rebuild (the main config stays, its UUID is in cat-config.json).",
            )
            else -> t(
                "Shiprr یک هاست اروپایی است که برنامه را از GitHub می‌سازد. ۵ یورو اعتبار رایگان. آدرس: https://نام-برنامه.shiprr.dev — کانفیگ‌ها TLS روی 443.\n\n" +
                    "1) «⚡ ساخت خودکار» ← «ساخت توکن» ← Generate token ← کپی ← «چسباندن» ← ادامه. برنامه یک مخزن خصوصی با ۴ فایل می‌سازد.\n" +
                    "2) «باز کردن Shiprr» ← ثبت‌نام ← GitHub را وصل کنید.\n" +
                    "3) Apps ← New app ← یک نام، همان مخزن، منطقه (Europe) ← ساخت.\n" +
                    "4) وقتی بالا آمد ← «✍️ پنلم را اضافه کن» ← آدرس https://نام.shiprr.dev. برنامه تست می‌کند WebSocket از Shiprr رد می‌شود یا نه (مستندات Shiprr چیزی نگفته) و نتیجهٔ واقعی را می‌گوید.\n\n" +
                    "💾 Shiprr دیسک دائمی ندارد: کاربرهای اضافهٔ پنل با هر دیپلوی پاک می‌شوند؛ کانفیگ اصلی می‌ماند.",
                "Shiprr is a European host that builds the app from GitHub. 5 EUR free credit. Address: https://app-name.shiprr.dev — configs are TLS on 443.\n\n" +
                    "1) «⚡ Build automatically» → «Create a token» → Generate token → copy → «Paste» → Continue. The app creates a private repository with 4 files.\n" +
                    "2) «Open Shiprr» → sign up → connect GitHub.\n" +
                    "3) Apps → New app → a name, that repository, region (Europe) → create.\n" +
                    "4) When it is up → «✍️ Add my panel» → the https://name.shiprr.dev address. The app tests whether WebSocket passes through Shiprr (their docs do not say) and tells you the real result.\n\n" +
                    "💾 Shiprr has no persistent disk: extra panel users vanish on each deploy; the main config stays.",
            )
        }
        message("❓ " + name(kind), text)
    }

    // ------------------------------------------------------------------------------------
    // KataBump rules — a required tick before anything is installed
    // ------------------------------------------------------------------------------------

    private fun confirmKataBumpRules(next: () -> Unit) {
        val box = column()
        box.addView(label(t(
            "⚠️ قوانین استفادهٔ KataBump (بند 2.4) اجرای پروکسی، VPN و تونل را ممنوع کرده است. اگر بفهمند ممکن است سرور یا حسابتان را بدون خبر پاک کنند. " +
                "همچنین هر خانه فقط یک سرویس رایگان مجاز است (بند 2.5).\n\nاین ریسک با خود شماست. Lucity و Shiprr چنین منعی در قوانینشان ننوشته‌اند.",
            "⚠️ KataBump's acceptable-use rules (section 2.4) forbid running proxies, VPNs and tunnels. If they notice, they may delete the server or the account without notice. " +
                "Only one free service per household is allowed (section 2.5).\n\nThis risk is yours. Lucity and Shiprr do not write such a ban in their terms.",
        ), 13f, palette.textPrimary, 2))
        val tick = CheckBox(activity).apply {
            text = t("قوانین را خواندم و ریسکش را می‌پذیرم", "I read the rules and accept the risk")
            setTextColor(palette.textPrimary)
        }
        box.addView(tick, lp(10))
        box.addView(button(t("📜 خواندن قوانین KataBump", "📜 Read KataBump's rules")) { open(NodeHostPanel.KATABUMP_RULES) }, lp(6))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("پیش از ادامه", "Before you go on"))
            .setView(scroll(box))
            .setPositiveButton(t("ادامه", "Continue")) { _, _ -> next() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            tick.setOnCheckedChangeListener { _, checked -> ok.isEnabled = checked }
        }
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // 📦 save the files: one zip in Download/ (and a share sheet), ready for «Upload → Unarchive»
    // ------------------------------------------------------------------------------------

    fun saveFiles(kind: String) {
        if (kind == NodeHostPanel.KATABUMP) confirmKataBumpRules { writeZip(kind) } else writeZip(kind)
    }

    private fun writeZip(kind: String) {
        val fileName = "cat-node-panel-$kind.zip"
        val result = runCatching {
            val bytes = NodeHostPanel.zip(files(kind))
            val dir = File(activity.filesDir, "node-panel").apply { mkdirs() }
            val local = File(dir, fileName).apply { writeBytes(bytes) }
            var inDownloads = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                inDownloads = runCatching {
                    val resolver = activity.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                    true
                }.getOrDefault(false)
            }
            Pair(local, inDownloads)
        }
        result.onFailure { message(t("❌ ذخیره نشد", "❌ Not saved"), explain(it)); return }
        val (local, inDownloads) = result.getOrThrow()
        val box = column()
        box.addView(label(
            (if (inDownloads) {
                t("✅ ذخیره شد: پوشهٔ Download ← ", "✅ Saved: Download folder → ") + fileName
            } else {
                t("✅ آماده شد. با «اشتراک‌گذاری» آن را در Files/Telegram/Drive ذخیره کنید.", "✅ Ready. Use «Share» to save it to Files/Telegram/Drive.")
            }) + "\n\n" + t(
                "داخلش ۴ فایل است: index.js و package.json (خود پنل)، README.md (راهنما) و cat-config.json (شناسهٔ کانفیگ همین گوشی، تا بعداً برنامه آدرس اشتراک را خودش بسازد).",
                "It holds 4 files: index.js and package.json (the panel), README.md (guide) and cat-config.json (this phone's config identity, so the app can build the subscription address itself).",
            ) + "\n\n" + nextStepsAfterZip(kind),
            13f, palette.textPrimary, 2,
        ).apply { setTextIsSelectable(true) })
        box.addView(button(t("📤 اشتراک‌گذاری فایل", "📤 Share the file"), filled = true) { share(local) }, lp(10))
        box.addView(button(t("🌐 باز کردن ", "🌐 Open ") + name(kind)) { open(siteFor(kind)) }, lp(6))
        box.addView(button(t("✍️ آپلود کردم — پنلم را اضافه کن", "✍️ Uploaded — add my panel")) { registerManual(kind) }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle("📦 " + name(kind))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    private fun nextStepsAfterZip(kind: String): String = when (kind) {
        NodeHostPanel.KATABUMP -> t(
            "حالا: control.katabump.com ← سرورتان ← Files ← Upload ← همین zip ← سه‌نقطهٔ کنار zip ← Unarchive ← Startup: فایل اصلی index.js و Node 20+ ← Console ← Start ← آدرس آی‌پی:پورت بالای صفحه را کپی کنید ← «✍️ پنلم را اضافه کن».",
            "Now: control.katabump.com → your server → Files → Upload → this zip → three dots next to it → Unarchive → Startup: main file index.js and Node 20+ → Console → Start → copy the IP:port at the top → «✍️ Add my panel».",
        )
        else -> t(
            "حالا: در GitHub یک مخزن خصوصی بسازید ← Add file ← Upload files ← ۴ فایل داخل zip (نه خود zip) ← Commit. بعد در " + name(kind) + " همان مخزن را انتخاب کنید. راه سریع‌تر: «⚡ ساخت خودکار» همهٔ این‌ها را خودش انجام می‌دهد.",
            "Now: create a private GitHub repository → Add file → Upload files → the 4 files inside the zip (not the zip) → Commit. Then pick that repository on " + name(kind) + ". Faster: «⚡ Build automatically» does all of this for you.",
        )
    }

    private fun siteFor(kind: String): String = when (kind) {
        NodeHostPanel.KATABUMP -> NodeHostPanel.KATABUMP_CONTROL
        NodeHostPanel.LUCITY -> NodeHostPanel.LUCITY_LOGIN
        else -> NodeHostPanel.SHIPRR_SIGNUP
    }

    private fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, file.name))
        }.onFailure { toast(explain(it)) }
    }

    // ------------------------------------------------------------------------------------
    // ⚡ automatic install
    // ------------------------------------------------------------------------------------

    fun autoInstall(kind: String) {
        if (kind == NodeHostPanel.KATABUMP) confirmKataBumpRules { askPteroKey() } else askGithubToken(kind)
    }

    private fun askPteroKey() {
        val box = column()
        box.addView(label(t(
            "برنامه با «کلید API» خود شما به control.katabump.com وصل می‌شود (مثل توکن Vercel، فقط روی همین گوشی می‌ماند).\n\n" +
                "1) «🔑 باز کردن صفحهٔ کلید» را بزنید (اگر خواست، وارد حساب KataBump شوید).\n" +
                "2) Create API Key ← یک نام دلخواه ← Create.\n" +
                "3) کلیدی که با ptlc_ شروع می‌شود را کپی کنید ← برگردید ← «📋 چسباندن» ← ادامه.",
            "The app connects to control.katabump.com with your own «API key» (like the Vercel token, it stays on this phone only).\n\n" +
                "1) Tap «🔑 Open the key page» (sign in to KataBump if asked).\n" +
                "2) Create API Key → any name → Create.\n" +
                "3) Copy the key that starts with ptlc_ → come back → «📋 Paste» → Continue.",
        ), 13f, palette.textPrimary, 2))
        val field = input("ptlc_…", store.pteroKey, password = true)
        box.addView(field, lp(8))
        box.addView(button(t("🔑 باز کردن صفحهٔ کلید", "🔑 Open the key page")) { open(NodeHostPanel.KATABUMP_API_KEYS) }, lp(8))
        box.addView(button(t("📋 چسباندن", "📋 Paste")) { field.setText(clipboardText().trim()) }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("⚡ نصب خودکار روی KataBump", "⚡ Automatic install on KataBump"))
                .setView(scroll(box))
                .setPositiveButton(t("ادامه", "Continue")) { _, _ ->
                    val key = field.text.toString().trim()
                    when {
                        key.startsWith("ptla_") -> message(
                            t("کلید اشتباه", "Wrong key"),
                            t(
                                "این کلید «Application» (مدیر) است و KataBump آن را به کاربر نمی‌دهد. کلید «Client» لازم است که با ptlc_ شروع می‌شود: Account ← API Credentials.",
                                "This is an «Application» (admin) key. A «Client» key is needed, it starts with ptlc_: Account → API Credentials.",
                            ),
                        )
                        key.length < 20 -> toast(t("کلید را کامل بچسبانید", "Paste the whole key"))
                        else -> {
                            store.pteroKey = key
                            loadServers(key)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun loadServers(key: String) {
        runSteps<List<NodeHostApi.PteroServer>>(t("KataBump", "KataBump"), { step ->
            step(t("گرفتن فهرست سرورهای شما…", "Reading your servers…"))
            NodeHostApi.pteroServers(key)
        }) { servers, _ ->
            when {
                servers.isEmpty() -> message(
                    t("سروری نیست", "No server yet"),
                    t(
                        "در حساب شما سروری نیست. katabump.com ← داشبورد ← ساخت سرور رایگان Node.js ← بعد دوباره «⚡ نصب خودکار».",
                        "Your account has no server. katabump.com → dashboard → create a free Node.js server → then «⚡ Install automatically» again.",
                    ),
                )
                servers.size == 1 -> confirmInstall(key, servers.first())
                else -> showDialog(
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(t("کدام سرور؟", "Which server?"))
                        .setItems(servers.map { s -> s.name + "  ·  " + (s.host ?: "?") + ":" + (s.port ?: "?") }.toTypedArray()) { _, i ->
                            confirmInstall(key, servers[i])
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create(),
                )
            }
        }
    }

    private fun confirmInstall(key: String, server: NodeHostApi.PteroServer) {
        message2(
            t("نصب روی «", "Install on «") + server.name + "»?",
            t(
                "این کارها روی همین سرور انجام می‌شود:\n" +
                    "• ۴ فایل index.js، package.json، README.md و cat-config.json گذاشته می‌شوند (اگر فایلی با همین نام هست، جایگزین می‌شود).\n" +
                    "• فایل شروع = index.js.\n• اگر Node قدیمی‌تر از 20 باشد، به جدیدترین Node موجود عوض می‌شود.\n• سرور ری‌استارت می‌شود.\n\n" +
                    "فایل‌های دیگر شما دست نمی‌خورند.",
                "This will be done on this server:\n" +
                    "• 4 files index.js, package.json, README.md and cat-config.json are written (same-named files are replaced).\n" +
                    "• Start file = index.js.\n• If Node is older than 20, it is switched to the newest Node offered.\n• The server is restarted.\n\n" +
                    "Your other files are not touched.",
            ),
            t("نصب کن", "Install"),
        ) { install(key, server) }
    }

    /** A yes/no dialog. */
    private fun message2(title: String, text: String, yes: String, onYes: () -> Unit) {
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll(column().apply { addView(label(text, 13f, palette.textPrimary, 2)) }))
                .setPositiveButton(yes) { _, _ -> onYes() }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun install(key: String, server: NodeHostApi.PteroServer) {
        val kind = NodeHostPanel.KATABUMP
        runSteps<Outcome>(t("⚡ نصب روی ", "⚡ Installing on ") + server.name, { step ->
            val id = server.identifier
            val all = files(kind)
            val (uuid, pass) = identity(kind)
            step(t("1/6 گذاشتن فایل‌ها…", "1/6 Writing the files…"))
            for ((fileName, bytes) in all) {
                NodeHostApi.pteroWrite(key, id, "/" + fileName, bytes)
                step("   ✓ " + fileName)
            }
            step(t("2/6 فایل شروع…", "2/6 Start file…"))
            val startup = runCatching { NodeHostApi.pteroStartup(key, id) }
                .onFailure { step("   ⚠️ " + explain(it)) }
                .getOrDefault(Pair(emptyList<NodeHostApi.PteroVariable>(), emptyMap<String, String>()))
            val main = startup.first.firstOrNull { NodeHostPanel.isMainFileVar(it.env, it.serverValue, it.defaultValue) }
            if (main == null) {
                step(t("   ⚠️ متغیر فایل شروع پیدا نشد — در Startup ببینید index.js باشد.", "   ⚠️ No start-file variable found — check Startup says index.js."))
            } else if (main.serverValue.trim() == "index.js") {
                step("   ✓ " + main.env + " = index.js")
            } else if (main.editable) {
                val set = runCatching { NodeHostApi.pteroSetVariable(key, id, main.env, "index.js") }
                if (set.isSuccess) step("   ✓ " + main.env + " = index.js") else step("   ⚠️ " + explain(set.exceptionOrNull()!!))
            } else {
                // Locked start file (e.g. app.js): put the same launcher under that name too.
                val locked = main.serverValue.trim().removePrefix("/")
                if (locked.endsWith(".js") || locked.endsWith(".mjs")) {
                    NodeHostApi.pteroWrite(key, id, "/" + locked, all.getValue("index.js"))
                    step(t("   ✓ فایل شروع قفل است؛ همان پنل با نام ", "   ✓ The start file is locked; the panel was also written as ") + locked)
                } else {
                    step("   ⚠️ " + main.env + " = " + main.serverValue)
                }
            }
            step(t("3/6 نسخهٔ Node…", "3/6 Node version…"))
            val current = runCatching { NodeHostApi.pteroImage(key, id) }.getOrDefault("")
            val pick = NodeHostPanel.pickNodeImage(startup.second, current)
            if (pick != null) {
                val ok = runCatching { NodeHostApi.pteroSetImage(key, id, pick) }
                if (ok.isSuccess) step("   ✓ → " + (startup.second.entries.firstOrNull { it.value == pick }?.key ?: pick))
                else step("   ⚠️ " + explain(ok.exceptionOrNull()!!))
            } else {
                step("   ✓ " + (NodeHostPanel.nodeVersionOf(current)?.let { "Node " + it } ?: current.ifBlank { "?" }))
            }
            step(t("4/6 روشن کردن دوباره…", "4/6 Restarting…"))
            val restarted = runCatching { NodeHostApi.pteroPower(key, id, "restart") }
            if (restarted.isFailure) NodeHostApi.pteroPower(key, id, "start")
            step(t("5/6 صبر تا روشن شود…", "5/6 Waiting until it runs…"))
            var state = ""
            for (i in 0 until 24) {
                delay(5_000)
                state = runCatching { NodeHostApi.pteroState(key, id) }.getOrDefault(state)
                if (state == "running") break
            }
            step("   " + (if (state == "running") "✓ running" else "⚠️ " + state.ifBlank { "?" }))
            val base = server.host?.let { h -> server.port?.let { p -> NodeHostPanel.normalizeAddress(h + ":" + p) } }
            if (base == null) {
                Outcome(null, emptyList(), false, t(
                    "فایل‌ها نصب شدند ولی KataBump آدرس عمومی سرور را نداد. آدرس آی‌پی:پورت بالای صفحهٔ سرور را کپی کنید و «✍️ پنلم را اضافه کن» را بزنید.",
                    "Files are installed but KataBump did not give the public address. Copy the IP:port at the top of the server page and tap «✍️ Add my panel».",
                ))
            } else {
                step(t("6/6 تست پنل از همین گوشی (تا ۲.۵ دقیقه؛ بار اول پنل دانلود می‌شود)…", "6/6 Testing the panel from this phone (up to 2.5 min; the first start downloads the panel)…"))
                val check = verify(kind, base, uuid, step, 150)
                val now = System.currentTimeMillis()
                val saved = SavedNodeHost("katabump-" + id, kind, server.name, base, uuid, id, now, now, pass)
                store.save(saved)
                Outcome(saved, check.links, check.ok, check.headline)
            }
        }) { outcome, lines -> showOutcome(outcome, lines) }
    }

    private data class Check(val links: List<String>, val ok: Boolean, val headline: String, val answered: Boolean)

    /** /api/version → /sub/<uuid> → a real WebSocket handshake on the first config's path. */
    private suspend fun verify(kind: String, base: String, uuid: String, step: (String) -> Unit, waitSeconds: Int): Check {
        var answered = false
        var tries = 0
        val maxTries = (waitSeconds / 5).coerceAtLeast(1)
        while (!answered && tries < maxTries) {
            val r = runCatching { NodeHostApi.panelGet(base, "/api/version", 6_000) }.getOrNull()
            answered = r != null && r.first == 200 && r.second.contains("cat-panel")
            tries++
            if (!answered && tries < maxTries) {
                if (tries % 6 == 0) step("   … " + (tries * 5) + "s")
                delay(5_000)
            }
        }
        if (!answered) {
            val why = if (kind == NodeHostPanel.KATABUMP) t(
                "پنل روی " + base + " جواب نداد. در KataBump ← Console ببینید خطایی هست یا نه (مثلاً Node قدیمی، فایل شروع اشتباه). اگر Console می‌گوید «listening» ولی اینجا جواب نمی‌دهد، این آی‌پی:پورت روی اینترنت شما بسته است؛ با VPN دوباره «🔎 تست» را بزنید تا مطمئن شوید.",
                "The panel did not answer at " + base + ". On KataBump → Console, look for an error (old Node, wrong start file). If the Console says «listening» but it does not answer here, this IP:port is blocked on your line; tap «🔎 Check» again with a VPN to be sure.",
            ) else t(
                "پنل روی " + base + " جواب نداد. در " + name(kind) + " ببینید ساخت (build) سبز شده و دامنه ساخته شده باشد؛ لاگ برنامه باید «Cat Panel … listening» داشته باشد.",
                "The panel did not answer at " + base + ". On " + name(kind) + " check the build is green and the domain exists; the app log should say «Cat Panel … listening».",
            )
            step("   ❌ /api/version")
            return Check(emptyList(), false, why, false)
        }
        step("   ✓ " + t("پنل جواب داد", "the panel answers"))
        val sub = runCatching { NodeHostApi.panelGet(base, "/sub/" + uuid) }.getOrNull()
        val links = if (sub != null && sub.first == 200) NodeHostPanel.decodeSub(sub.second) else emptyList()
        if (links.isEmpty()) {
            step("   ❌ /sub")
            return Check(emptyList(), false, t(
                "پنل بالا است ولی این UUID مال آن نیست (cat-config.json آپلود نشده یا UUID دیگری دارد). پنل را باز کنید (admin/admin) و لینک اشتراک را از آنجا بردارید، یا UUID درست را در «✍️ پنلم را اضافه کن» بنویسید.",
                "The panel is up but this UUID is not its own (cat-config.json was not uploaded or has another UUID). Open the panel (admin/admin) and take the subscription link there, or type the right UUID in «✍️ Add my panel».",
            ), true)
        }
        step("   ✓ " + links.size + t(" کانفیگ", " configs"))
        val first = links.first()
        val path = NodeHostPanel.linkParam(first, "path") ?: "/"
        val hostHeader = NodeHostPanel.linkParam(first, "host")?.takeIf { it.isNotBlank() }
            ?: NodeHostPanel.hostPort(base).let { (h, p) -> if (p == 80 || p == 443) h else h + ":" + p }
        val ws = runCatching { NodeHostApi.websocketStatus(base, path, hostHeader) }.getOrDefault(0)
        return if (ws == 101) {
            step("   ✓ WebSocket 101")
            Check(links, true, t(
                "✅ پنل کار می‌کند و تونل WebSocket از " + name(kind) + " رد می‌شود. کانفیگ‌ها آماده‌اند.",
                "✅ The panel works and the WebSocket tunnel passes through " + name(kind) + ". The configs are ready.",
            ), true)
        } else {
            step("   ❌ WebSocket " + ws)
            Check(links, false, t(
                "پنل جواب می‌دهد ولی دست‌دادن WebSocket جواب " + ws + " گرفت (باید 101 باشد). یعنی " + name(kind) + " تونل را رد نمی‌کند و کانفیگ‌ها وصل نمی‌شوند. اگر پشت Cloudflare هستید، WebSockets در Network روشن باشد.",
                "The panel answers but the WebSocket handshake got " + ws + " (must be 101). " + name(kind) + " does not pass the tunnel, so the configs will not connect. Behind Cloudflare, make sure Network → WebSockets is on.",
            ), true)
        }
    }

    // ------------------------------------------------------------------------------------
    // ✍️ add my panel (any host, any address the user has)
    // ------------------------------------------------------------------------------------

    fun registerManual(kind: String, uuidPrefill: String? = null, addressPrefill: String = "") {
        val box = column()
        box.addView(label(
            if (kind == NodeHostPanel.KATABUMP) t(
                "آدرس سرور را بنویسید: همان آی‌پی:پورت بالای صفحهٔ سرور در control.katabump.com (مثل 1.2.3.4:25565)، یا دامنهٔ https خودتان اگر پشت Cloudflare گذاشته‌اید.",
                "Type the server address: the IP:port at the top of the server page on control.katabump.com (like 1.2.3.4:25565), or your own https domain if it is behind Cloudflare.",
            ) else t(
                "آدرس برنامه را بنویسید (مثل https://نام.shiprr.dev یا دامنه‌ای که Lucity ساخت).",
                "Type the app address (like https://name.shiprr.dev or the domain Lucity generated).",
            ),
            13f, palette.textPrimary, 2,
        ))
        val address = input(if (kind == NodeHostPanel.KATABUMP) "1.2.3.4:25565" else "https://….shiprr.dev", addressPrefill)
        box.addView(address, lp(6))
        box.addView(button(t("📋 چسباندن", "📋 Paste")) { address.setText(clipboardText().trim()) }, lp(4))
        box.addView(label(t(
            "UUID اصلی (خودکار پر شده، همان که داخل cat-config.json است؛ فقط اگر پنل را جور دیگری ساخته‌اید عوضش کنید):",
            "Main UUID (filled in for you, the one inside cat-config.json; change it only if you built the panel another way):",
        ), top = 12))
        val uuidField = input("UUID", uuidPrefill ?: identity(kind).first)
        box.addView(uuidField, lp(4))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle("✍️ " + name(kind))
                .setView(scroll(box))
                .setPositiveButton(t("تست و اضافه", "Test and add")) { _, _ ->
                    val base = NodeHostPanel.normalizeAddress(address.text.toString())
                    val uuid = uuidField.text.toString().trim()
                    when {
                        base == null -> toast(t("آدرس درست نیست", "The address is not valid"))
                        uuid.length < 8 -> toast(t("UUID خالی است", "The UUID is empty"))
                        else -> addChecked(kind, base, uuid)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun addChecked(kind: String, base: String, uuid: String) {
        runSteps<Outcome>(t("🔎 تست ", "🔎 Testing ") + base, { step ->
            step(t("تست از همین گوشی…", "Testing from this phone…"))
            val check = verify(kind, base, uuid, step, 30)
            if (!check.answered) {
                Outcome(null, emptyList(), false, check.headline)
            } else {
                val pending = store.pending(kind)
                val pass = if (pending != null && pending.first == uuid) pending.second else ""
                val now = System.currentTimeMillis()
                val existing = store.list().firstOrNull { it.kind == kind && it.base == base }
                val saved = existing?.copy(uuid = uuid, trojanPass = pass.ifBlank { existing.trojanPass })
                    ?: SavedNodeHost(kind + "-" + Integer.toHexString(base.hashCode()), kind, NodeHostPanel.hostPort(base).first, base, uuid, "", now, now, pass)
                store.save(saved)
                Outcome(saved, check.links, check.ok, check.headline)
            }
        }) { outcome, lines -> showOutcome(outcome, lines) }
    }

    // ------------------------------------------------------------------------------------
    // result: add configs · open panel · renew
    // ------------------------------------------------------------------------------------

    private fun showOutcome(o: Outcome, lines: List<String>) {
        val saved = o.saved
        if (saved != null && saved.uuid == store.pending(saved.kind)?.first) rotateIdentity(saved.kind)
        val box = column()
        box.addView(label(o.headline, 14f, palette.textPrimary, 2))
        box.addView(label(lines.joinToString("\n"), 11.5f, palette.textSecondary, 8).apply { setTextIsSelectable(true) })
        if (o.links.isNotEmpty()) {
            box.addView(button(t("➕ اضافه کردن ", "➕ Add ") + o.links.size + t(" کانفیگ", " configs"), filled = true) {
                addSubscription(o.links.joinToString("\n"), "Cat · " + name(saved?.kind ?: "") + " · " + (saved?.name ?: ""))
            }, lp(10))
        }
        if (saved != null) {
            box.addView(button(t("🌐 باز کردن پنل (admin / admin — رمز را عوض کنید)", "🌐 Open the panel (admin / admin — change the password)")) {
                open(NodeHostPanel.panelUrl(saved.base))
            }, lp(6))
            box.addView(button(t("📋 کپی لینک اشتراک (برای برنامه‌های دیگر)", "📋 Copy the subscription link (for other apps)")) {
                copy(NodeHostPanel.subUrl(saved.base, saved.uuid))
            }, lp(6))
            if (saved.kind == NodeHostPanel.KATABUMP) {
                remindLater(saved)
                box.addView(label(t(
                    "🔔 یادآوری تمدید برای ۳ روز دیگر تنظیم شد (KataBump بعد از ۴ روز بدون تمدید پاک می‌کند). اگر امروز تمدید نکرده‌اید، همین حالا «🔁 تمدید» را بزنید.",
                    "🔔 A renewal reminder is set for 3 days from now (KataBump deletes after 4 days without renewal). If you did not renew today, tap «🔁 Renew» now.",
                ), top = 10))
                box.addView(button(t("🔁 تمدید", "🔁 Renew")) { showRenew(saved) }, lp(6))
            }
            if (!o.ok) box.addView(button(t("🔎 دوباره تست کن", "🔎 Test again")) { addChecked(saved.kind, saved.base, saved.uuid) }, lp(6))
        } else {
            box.addView(button(t("✍️ آدرس را خودم می‌نویسم", "✍️ I will type the address")) { registerManual(NodeHostPanel.KATABUMP) }, lp(10))
        }
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(if (o.ok) t("✅ آماده", "✅ Ready") else t("⚠️ نتیجه", "⚠️ Result"))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    private fun remindLater(item: SavedNodeHost) {
        val wait = (item.renewedAt + NodeHostPanel.REMIND_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(60_000L)
        runCatching { NodeHostRenewReminder.schedule(activity, item.id, item.name, wait) }
        requestNotifications()
    }

    // ------------------------------------------------------------------------------------
    // Lucity / Shiprr: a private GitHub repository with the 4 files
    // ------------------------------------------------------------------------------------

    private fun askGithubToken(kind: String) {
        val box = column()
        box.addView(label(t(
            name(kind) + " برنامه را از یک مخزن GitHub می‌سازد. برنامه با توکن شما یک مخزن خصوصی با ۴ فایل می‌سازد (توکن فقط روی همین گوشی می‌ماند).\n\n" +
                "1) GitHub ندارید؟ «🆕 ثبت‌نام GitHub».\n" +
                "2) «🔑 ساخت توکن» ← صفحه آماده باز می‌شود (دسترسی repo تیک خورده) ← پایین صفحه Generate token ← کپی.\n" +
                "3) برگردید ← «📋 چسباندن» ← ادامه.",
            name(kind) + " builds the app from a GitHub repository. With your token the app creates a private repository with 4 files (the token stays on this phone only).\n\n" +
                "1) No GitHub? «🆕 GitHub sign-up».\n" +
                "2) «🔑 Create a token» → the page opens pre-filled (repo scope ticked) → Generate token at the bottom → copy.\n" +
                "3) Come back → «📋 Paste» → Continue.",
        ), 13f, palette.textPrimary, 2))
        val field = input("ghp_… / github_pat_…", store.githubToken, password = true)
        box.addView(field, lp(8))
        box.addView(button(t("🆕 ثبت‌نام GitHub", "🆕 GitHub sign-up")) { open(NodeHostPanel.GITHUB_SIGNUP) }, lp(8))
        box.addView(button(t("🔑 ساخت توکن", "🔑 Create a token")) { open(NodeHostPanel.GITHUB_TOKEN_URL) }, lp(6))
        box.addView(button(t("📋 چسباندن", "📋 Paste")) { field.setText(clipboardText().trim()) }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("⚡ ساخت خودکار برای ", "⚡ Automatic build for ") + name(kind))
                .setView(scroll(box))
                .setPositiveButton(t("ادامه", "Continue")) { _, _ ->
                    val token = field.text.toString().trim()
                    if (token.length < 20) {
                        toast(t("توکن را کامل بچسبانید", "Paste the whole token"))
                    } else {
                        store.githubToken = token
                        checkGithub(kind, token)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun checkGithub(kind: String, token: String) {
        runSteps<String>("GitHub", { step ->
            step(t("بررسی توکن…", "Checking the token…"))
            NodeHostApi.githubLogin(token)
        }) { login, _ ->
            val repo = NodeHostPanel.repoName()
            message2(
                t("ساخت مخزن؟", "Create the repository?"),
                t(
                    "در حساب GitHub «" + login + "» یک مخزن خصوصی به نام " + repo + " ساخته می‌شود و ۴ فایل پنل (index.js، package.json، README.md، cat-config.json) داخلش گذاشته می‌شود. به مخزن‌های دیگر شما دست زده نمی‌شود.",
                    "A private repository named " + repo + " is created in GitHub account «" + login + "» with the 4 panel files (index.js, package.json, README.md, cat-config.json). Your other repositories are not touched.",
                ),
                t("بساز", "Create"),
            ) { createRepo(kind, token, repo) }
        }
    }

    private fun createRepo(kind: String, token: String, repo: String) {
        runSteps<String>("GitHub", { step ->
            step(t("ساخت مخزن خصوصی…", "Creating the private repository…"))
            val full = NodeHostApi.githubCreateRepo(token, repo)
            step("   ✓ " + full)
            for ((fileName, bytes) in files(kind)) {
                NodeHostApi.githubPut(token, full, fileName, bytes)
                step("   ✓ " + fileName)
            }
            store.setLastRepo(kind, full)
            full
        }) { full, _ -> showGitNext(kind, full) }
    }

    private fun showGitNext(kind: String, full: String) {
        val repoShort = full.substringAfter('/')
        val steps = if (kind == NodeHostPanel.LUCITY) t(
            "✅ مخزن آماده است: " + full + "\n\nحالا در Lucity (۳ قدم):\n" +
                "1) «🌐 باز کردن Lucity» ← Login with GitHub ← وقتی پرسید، دسترسی به " + repoShort + " را بدهید.\n" +
                "2) New ← GitHub Repository ← " + repoShort + " ← ساخت.\n" +
                "3) Settings ← Platform Domain ← Generate Domain.\n\n" +
                "وقتی ساخت سبز شد، دامنه را کپی کنید ← «✍️ ساختم — پنلم را اضافه کن».",
            "✅ The repository is ready: " + full + "\n\nNow on Lucity (3 steps):\n" +
                "1) «🌐 Open Lucity» → Login with GitHub → when asked, allow access to " + repoShort + ".\n" +
                "2) New → GitHub Repository → " + repoShort + " → create.\n" +
                "3) Settings → Platform Domain → Generate Domain.\n\n" +
                "When the build is green, copy the domain → «✍️ Deployed — add my panel».",
        ) else t(
            "✅ مخزن آماده است: " + full + "\n\nحالا در Shiprr (۳ قدم):\n" +
                "1) «🌐 باز کردن Shiprr» ← ثبت‌نام ← GitHub را وصل کنید.\n" +
                "2) Apps ← New app ← یک نام (مثلاً cat" + repoShort.takeLast(5) + ")، مخزن " + repoShort + "، منطقه Europe ← ساخت.\n" +
                "3) وقتی بالا آمد، آدرس https://نام.shiprr.dev است ← «✍️ ساختم — پنلم را اضافه کن».",
            "✅ The repository is ready: " + full + "\n\nNow on Shiprr (3 steps):\n" +
                "1) «🌐 Open Shiprr» → sign up → connect GitHub.\n" +
                "2) Apps → New app → a name (e.g. cat" + repoShort.takeLast(5) + "), repository " + repoShort + ", region Europe → create.\n" +
                "3) When it is up, the address is https://name.shiprr.dev → «✍️ Deployed — add my panel».",
        )
        val box = column()
        box.addView(label(steps, 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) })
        box.addView(button(t("🌐 باز کردن ", "🌐 Open ") + name(kind), filled = true) { open(siteFor(kind)) }, lp(10))
        box.addView(button(t("📋 کپی نام مخزن", "📋 Copy the repository name")) { copy(repoShort) }, lp(6))
        box.addView(button(t("✍️ ساختم — پنلم را اضافه کن", "✍️ Deployed — add my panel")) { registerManual(kind) }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(name(kind))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    // ------------------------------------------------------------------------------------
    // 🔁 renew (KataBump) / update (Lucity, Shiprr)
    // ------------------------------------------------------------------------------------

    fun renew(kind: String) {
        if (kind != NodeHostPanel.KATABUMP) {
            redeploy(kind)
            return
        }
        val items = store.list().filter { it.kind == kind }
        when {
            items.isEmpty() -> message2(
                t("🔁 تمدید KataBump", "🔁 Renew KataBump"),
                t(
                    "هنوز پنلی از KataBump اینجا ثبت نشده، پس برنامه شمارش معکوس ندارد. صفحهٔ تمدید را باز کنید ← سرورتان ← Renew.",
                    "No KataBump panel is registered here yet, so there is no countdown. Open the renew page → your server → Renew.",
                ),
                t("باز کردن صفحهٔ تمدید", "Open the renew page"),
            ) { open(NodeHostPanel.KATABUMP_DASHBOARD) }
            items.size == 1 -> showRenew(items.first())
            else -> pick(items) { showRenew(it) }
        }
    }

    private fun countdown(item: SavedNodeHost): String {
        val left = NodeHostPanel.daysLeft(item.renewedAt, System.currentTimeMillis())
        return when {
            left == null -> t("⏳ زمان تمدید معلوم نیست", "⏳ Renewal time unknown")
            left <= 0 -> t("⛔ مهلت گذشته — فوراً تمدید کنید (KataBump یک روز بعد پاک می‌کند)", "⛔ Overdue — renew now (KataBump deletes one day later)")
            else -> t("⏳ ", "⏳ ") + String.format(java.util.Locale.US, "%.1f", left) + t(" روز تا تمدید", " days until renewal")
        }
    }

    private fun showRenew(item: SavedNodeHost) {
        val box = column()
        box.addView(label(item.name + "\n" + countdown(item), 14f, palette.textPrimary, 2))
        box.addView(label(t(
            "تمدید KataBump کپچا دارد («من ربات نیستم»)، پس هیچ برنامه‌ای نمی‌تواند بی‌خطر به‌جای شما بزند. کار شما ۲۰ ثانیه است:\n" +
                "1) «🔁 باز کردن صفحهٔ تمدید» ← سرورتان ← Renew ← کپچا.\n2) برگردید ← «✅ تمدید کردم». برنامه ۳ روز بعد دوباره یادآوری می‌کند.",
            "KataBump's renewal has a captcha («I am not a robot»), so no app can safely press it for you. It takes you 20 seconds:\n" +
                "1) «🔁 Open the renew page» → your server → Renew → captcha.\n2) Come back → «✅ I renewed». The app reminds you again in 3 days.",
        ), top = 10))
        box.addView(button(t("🔁 باز کردن صفحهٔ تمدید", "🔁 Open the renew page"), filled = true) { open(NodeHostPanel.KATABUMP_DASHBOARD) }, lp(10))
        box.addView(button(t("✅ تمدید کردم", "✅ I renewed")) {
            store.markRenewed(item.id, System.currentTimeMillis())
            store.list().firstOrNull { it.id == item.id }?.let { remindLater(it) }
            toast(t("ثبت شد؛ ۳ روز دیگر یادآوری می‌کنم", "Saved; I will remind you in 3 days"))
        }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("🔁 تمدید", "🔁 Renew"))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    private fun redeploy(kind: String) {
        val repo = store.lastRepo(kind)
        val token = store.githubToken
        if (repo.isBlank() || token.isBlank()) {
            message(
                t("🔁 به‌روزرسانی", "🔁 Update"),
                t(
                    "اول «⚡ ساخت خودکار» را بزنید تا برنامه مخزن را بسازد؛ بعد این دکمه هر بار فایل‌های نسخهٔ جدید برنامه را در همان مخزن می‌گذارد و " + name(kind) + " از نو می‌سازد.",
                    "First tap «⚡ Build automatically» so the app creates the repository; after that this button puts the new app version's files into it and " + name(kind) + " rebuilds.",
                ),
            )
            return
        }
        message2(
            t("🔁 به‌روزرسانی ", "🔁 Update ") + name(kind) + "?",
            t(
                "فایل‌های پنل (index.js، package.json، README.md) از همین نسخهٔ برنامه در مخزن " + repo + " گذاشته می‌شوند و " + name(kind) + " دوباره می‌سازد (۱ تا ۳ دقیقه). cat-config.json عوض نمی‌شود، پس کانفیگ‌ها همان می‌مانند.",
                "The panel files (index.js, package.json, README.md) from this app version go into " + repo + " and " + name(kind) + " rebuilds (1–3 minutes). cat-config.json is not changed, so the configs stay the same.",
            ),
            t("به‌روزرسانی", "Update"),
        ) {
            runSteps<String>("GitHub", { step ->
                val all = files(kind)
                for (f in NodeHostPanel.LAUNCHER_FILES) {
                    NodeHostApi.githubPut(token, repo, f, all.getValue(f))
                    step("   ✓ " + f)
                }
                NodeHostApi.githubPut(token, repo, ".cat-deploy", (BuildConfig.VERSION_NAME + " " + System.currentTimeMillis()).toByteArray())
                step("   ✓ .cat-deploy")
                repo
            }) { _, lines ->
                message("✅ " + name(kind), (lines + "" + t(
                    "فرستاده شد. چند دقیقه بعد در «📋 پنل‌های من» دکمهٔ «🔎 تست» را بزنید.",
                    "Sent. In a few minutes tap «🔎 Check» under «📋 My panels».",
                )).joinToString("\n"))
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // 📋 my panels
    // ------------------------------------------------------------------------------------

    private fun pick(items: List<SavedNodeHost>, onPick: (SavedNodeHost) -> Unit) {
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("کدام پنل؟", "Which panel?"))
                .setItems(items.map { it.name + "  ·  " + it.base }.toTypedArray()) { _, i -> onPick(items[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    fun showMine(kind: String) {
        val items = store.list().filter { it.kind == kind }.sortedByDescending { it.createdAt }
        if (items.isEmpty()) {
            message(
                "📋 " + name(kind),
                t(
                    "هنوز پنلی اینجا نیست. «⚡ نصب خودکار» را بزنید، یا اگر خودتان نصب کرده‌اید «✍️ پنلم را اضافه کن».",
                    "No panel here yet. Tap «⚡ Install automatically», or if you installed it yourself, «✍️ Add my panel».",
                ),
            )
            return
        }
        if (items.size == 1) showItem(items.first()) else pick(items) { showItem(it) }
    }

    private fun showItem(item: SavedNodeHost) {
        val box = column()
        val info = StringBuilder()
            .append(item.name).append('\n')
            .append(item.base).append('\n')
            .append("UUID: ").append(item.uuid)
        if (item.kind == NodeHostPanel.KATABUMP) info.append('\n').append(countdown(item))
        box.addView(label(info.toString(), 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) })
        box.addView(button(t("➕ گرفتن و اضافه کردن کانفیگ‌ها", "➕ Get and add the configs"), filled = true) {
            addChecked(item.kind, item.base, item.uuid)
        }, lp(10))
        box.addView(button(t("🌐 باز کردن پنل", "🌐 Open the panel")) { open(NodeHostPanel.panelUrl(item.base)) }, lp(6))
        box.addView(button(t("📋 کپی لینک اشتراک", "📋 Copy the subscription link")) { copy(NodeHostPanel.subUrl(item.base, item.uuid)) }, lp(6))
        if (item.kind == NodeHostPanel.KATABUMP) {
            box.addView(button(t("🔁 تمدید", "🔁 Renew")) { showRenew(item) }, lp(6))
            box.addView(button(t("🌐 دامنه + TLS (Cloudflare)", "🌐 Domain + TLS (Cloudflare)")) { showDomainTls(item) }, lp(6))
        } else {
            box.addView(button(t("🔁 به‌روزرسانی", "🔁 Update")) { redeploy(item.kind) }, lp(6))
        }
        box.addView(button(t("🗑 حذف از این فهرست", "🗑 Remove from this list")) {
            message2(
                t("حذف؟", "Remove?"),
                t(
                    "فقط از این فهرست و یادآوری‌ها حذف می‌شود؛ خود سرور روی " + name(item.kind) + " دست نمی‌خورد.",
                    "It is removed from this list and its reminders only; the server on " + name(item.kind) + " is not touched.",
                ),
                t("حذف", "Remove"),
            ) {
                store.remove(item.id)
                runCatching { NodeHostRenewReminder.cancel(activity, item.id) }
                toast(t("حذف شد", "Removed"))
            }
        }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle("📋 " + name(item.kind))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    private fun showDomainTls(item: SavedNodeHost) {
        val (host, port) = NodeHostPanel.hostPort(item.base)
        val box = column()
        box.addView(label(t(
            "چرا: کانفیگ آی‌پی:پورت رمزگذاری TLS ندارد و پورتش غیرعادی است؛ راحت‌تر بسته می‌شود. با Cloudflare جلوی سرور، کانفیگ TLS روی 443 و همهٔ کانفیگ‌های Cloudflare (با آی‌پی تمیز) را می‌گیرید.\n\n" +
                "1) یک دامنه که در Cloudflare (پلن رایگان) باشد. (زیردامنهٔ رایگان kdns.fr از KataBump فقط اسم به‌جای آی‌پی است و خودش TLS نمی‌دهد.)\n" +
                "2) Cloudflare ← DNS ← Add record: نوع A، نام مثلاً cat، IPv4 = " + host + "، ابر نارنجی روشن.\n" +
                "3) Rules ← Origin Rules ← Create: Hostname برابر cat.دامنهٔ‌شما ← Destination Port = " + port + ".\n" +
                "4) Rules ← Configuration Rules ← همان Hostname ← SSL = Flexible (سرور http ساده است).\n" +
                "5) برگردید ← «✍️ دامنه را اضافه کن» ← https://cat.دامنهٔ‌شما. پنل Cloudflare را می‌شناسد و کانفیگ‌های TLS می‌دهد.",
            "Why: an IP:port config has no TLS and an unusual port; it is easier to block. With Cloudflare in front you get a TLS config on 443 and the full Cloudflare set (with clean IPs).\n\n" +
                "1) A domain on Cloudflare (free plan). (KataBump's free kdns.fr subdomain is only a name instead of the IP; it adds no TLS.)\n" +
                "2) Cloudflare → DNS → Add record: type A, name e.g. cat, IPv4 = " + host + ", orange cloud on.\n" +
                "3) Rules → Origin Rules → Create: Hostname equals cat.yourdomain → Destination Port = " + port + ".\n" +
                "4) Rules → Configuration Rules → same Hostname → SSL = Flexible (the server speaks plain http).\n" +
                "5) Come back → «✍️ Add the domain» → https://cat.yourdomain. The panel recognises Cloudflare and gives TLS configs.",
        ), 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) })
        box.addView(button(t("🟧 باز کردن Cloudflare", "🟧 Open Cloudflare"), filled = true) { open(NodeHostPanel.CLOUDFLARE_DASH) }, lp(10))
        box.addView(button(t("📋 کپی آی‌پی سرور", "📋 Copy the server IP")) { copy(host) }, lp(6))
        box.addView(button(t("✍️ دامنه را اضافه کن", "✍️ Add the domain")) { registerManual(item.kind, item.uuid, "https://") }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("🌐 دامنه + TLS", "🌐 Domain + TLS"))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }
}
