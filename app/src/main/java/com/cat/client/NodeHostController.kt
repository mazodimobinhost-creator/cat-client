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
