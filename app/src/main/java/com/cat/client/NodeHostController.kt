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
import android.widget.RadioButton
import android.widget.RadioGroup
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
        /** beta225: which host this was for, so «✍️ type the address» opens the right form (it was always KataBump). */
        val kind: String = "",
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

    /** [update] = files to upload over a running panel: no cat-config.json, so the panel keeps its own UUID. */
    private fun files(kind: String, update: Boolean = false): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (f in NodeHostPanel.LAUNCHER_FILES) out[f] = asset("node-panel/$f")
        // KataBump / SmarterASP keep a disk; Lucity / Shiprr containers start empty after every deploy.
        val keepsDisk = kind == NodeHostPanel.KATABUMP || kind == NodeHostPanel.BOTHOSTING || kind == NodeHostPanel.SMARTERASP
        if (!update) {
            val (uuid, pass) = identity(kind)
            out[NodeHostPanel.CONFIG_FILE] = NodeHostPanel.configJson(uuid, pass, noDisk = !keepsDisk, host = kind).toByteArray()
        }
        if (kind == NodeHostPanel.SMARTERASP) {
            // beta224: IIS needs web.config, a writable App_Data and node_modules/ws (no npm install there).
            out["web.config"] = asset(NodeHostPanel.SMARTERASP_ASSETS + "/web.config")
            out["App_Data/README.txt"] = asset(NodeHostPanel.SMARTERASP_ASSETS + "/App_Data-README.txt")
            for (rel in assetTree(NodeHostPanel.SMARTERASP_WS_ASSETS)) {
                out[NodeHostPanel.smarterAspWsEntry(rel)] = asset(NodeHostPanel.SMARTERASP_WS_ASSETS + "/" + rel)
            }
        }
        return out
    }

    private fun asset(path: String): ByteArray = activity.assets.open(path).use { it.readBytes() }

    /** Every file under an asset folder, as paths relative to it (folders have children, files have none). */
    private fun assetTree(root: String, prefix: String = ""): List<String> {
        val here = if (prefix.isEmpty()) root else "$root/$prefix"
        val names = activity.assets.list(here).orEmpty()
        if (names.isEmpty()) return if (prefix.isEmpty()) emptyList() else listOf(prefix)
        return names.sorted().flatMap { child -> assetTree(root, if (prefix.isEmpty()) child else "$prefix/$child") }
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

    /** beta226 Shiprr regions (shiprr.app/docs/limits, Oct 2026): eu / us / oc / asia — a placement preference. */
    private fun shiprrRegionHint(): String = t(
        "📍 کشور = منطقه‌ای که اینجا انتخاب می‌کنید: eu اروپا (پیشنهادی، پینگ بهتر از ایران)، us آمریکا، asia آسیا، oc استرالیا/اقیانوسیه. اگر آن منطقه جا نداشت Shiprr ممکن است جای نزدیک دیگری بگذارد.",
        "📍 Country = the region you pick here: eu Europe (recommended, better ping from Iran), us USA, asia Asia, oc Australia/Oceania. If that region is full, Shiprr may place it in another nearby one.",
    )

    fun showGuide(kind: String) {
        val text = when (kind) {
            NodeHostPanel.KATABUMP -> t(
                "KataBump یک هاست رایگان Node.js است. پنل Cat روی آن بالا می‌آید و یک کانفیگ VLESS و یک Trojan روی «آی‌پی:پورت» سرور می‌دهد.\n\n" +
                    "📍 کشور: فقط فرانسه (دیتاسنتر OVH در Gravelines) — قابل انتخاب نیست. پورت: همان یک پورتی که KataBump به سرور می‌دهد؛ برنامه خودش آن را می‌خواند، دامنه لازم نیست.\n\n" +
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
                    "📍 Country: France only (OVH data center in Gravelines) — cannot be chosen. Port: the one port KataBump gives the server; the app reads it itself, no domain is needed.\n\n" +
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
            NodeHostPanel.BOTHOSTING -> t(
                "Bot-Hosting.net یک هاست رایگان (۲۵۶ مگ، بدون کارت بانکی) با API رسمی است؛ پنل Cat روی آن بالا می‌آید و با دامنهٔ HTTPS رایگان کانفیگ TLS می‌دهد.\n\n" +
                    "🟢 نصب (همه‌اش خودکار):\n" +
                    "1) «⚡ نصب خودکار» ← «👤 ثبت‌نام / ورود» (Discord یا GitHub).\n" +
                    "2) «🔑 صفحهٔ کلید» ← Create key با تیک‌های گفته‌شده ← کلید bhk_ را کپی ← «📋 چسباندن» ← ادامه ← تیک «خواندم» ← نصب.\n" +
                    "3) برنامه دیپلوی را می‌سازد، فایل‌ها را می‌گذارد، دامنه را فعال و روشن می‌کند، بعد خودش تست واقعی می‌گیرد و «➕ اضافه کردن کانفیگ‌ها» را نشان می‌دهد.\n\n" +
                    "🔁 تمدید: پلن رایگان هر ۴ روز یک کلیک Renew در داشبورد می‌خواهد؛ برنامه یادآوری می‌فرستد. «🔁 تمدید» صفحه‌اش را باز می‌کند.\n" +
                    "🔄 به‌روزرسانی: در «📋 پنل‌های من» ← «🔁 به‌روزرسانی» همان دیپلوی را دوباره نصب می‌کند (UUID عوض نمی‌شود).\n\n" +
                    "اگر bot-hosting.net روی اینترنت شما باز نمی‌شود، اول یک کانفیگ را وصل کنید.",
                "Bot-Hosting.net is a free host (256 MB, no card) with an official API; the Cat panel runs on it and gives TLS configs on a free HTTPS domain.\n\n" +
                    "🟢 Install (all automatic):\n" +
                    "1) «⚡ Install automatically» → «👤 Sign up / log in» (Discord or GitHub).\n" +
                    "2) «🔑 Key page» → Create key with the listed ticks → copy the bhk_ key → «📋 Paste» → Continue → tick «I read this» → Install.\n" +
                    "3) The app creates the deployment, writes the files, activates the domain and starts it, then really tests it and shows «➕ Add configs».\n\n" +
                    "🔁 Renew: the free plan needs one Renew click on the dashboard every 4 days; the app sends a reminder. «🔁 Renew» opens the page.\n" +
                    "🔄 Update: «📋 My panels» → «🔁 Update» reinstalls the same deployment (the UUID stays).\n\n" +
                    "If bot-hosting.net does not open on your line, connect a config first.",
            )
            NodeHostPanel.RAILWAY -> t(
                "Railway یک سکوی ابری است؛ برنامه با توکن شما پروژه + سرویس (ایمیج node:22-alpine) + دامنهٔ *.up.railway.app می‌سازد و پنل Cat را بالا می‌آورد.\n\n" +
                    "⚠️ دو نکتهٔ مهم: ۱) Railway رایگان نیست (بعد از اعتبار آزمایشی پلن پولی لازم است). ۲) قوانین Railway اجرای پروکسی را سوءاستفاده می‌داند و ممکن است سرویس را ببندد. برای رایگان و بی‌دردسر، Bot-Hosting.net بهتر است.\n\n" +
                    "🟢 نصب: «⚡ نصب خودکار» ← «👤 ورود به Railway» ← «🔑 صفحهٔ توکن» ← Create token (بدون انتخاب Project) ← کپی ← «📋 چسباندن» ← ادامه ← تیک ← نصب. برنامه تا وقتی Deploy سبز شود صبر می‌کند و تست واقعی (پنل، اشتراک، WebSocket 101) می‌گیرد.\n\n" +
                    "🔄 «🔁 به‌روزرسانی» همان سرویس را دوباره Deploy می‌کند. Railway دیسک دائمی ندارد: UUID اصلی ثابت است (در متغیرهاست) ولی کاربرانی که در پنل می‌سازید با هر Deploy پاک می‌شوند، مگر Upstash (KV_REST_API_URL/TOKEN) را در Variables بگذارید.",
                "Railway is a cloud platform; with your token the app creates a project + service (node:22-alpine image) + a *.up.railway.app domain and brings up the Cat panel.\n\n" +
                    "⚠️ Two things: 1) Railway is not free (after the trial credit a paid plan is needed). 2) Railway's rules count running proxies as abuse and may close the service. For free and simple, Bot-Hosting.net is better.\n\n" +
                    "🟢 Install: «⚡ Install automatically» → «👤 Log in to Railway» → «🔑 Token page» → Create token (no project) → copy → «📋 Paste» → Continue → tick → Install. The app waits until the deploy is green and runs a real test (panel, subscription, WebSocket 101).\n\n" +
                    "🔄 «🔁 Update» deploys the same service again. Railway has no permanent disk: the main UUID is fixed (it is in the variables) but users you create in the panel vanish on each deploy unless you add Upstash (KV_REST_API_URL/TOKEN) to Variables.",
            )
            NodeHostPanel.SMARTERASP -> smarterAspGuide()
            NodeHostPanel.LUCITY -> t(
                "Lucity یک هاست اروپایی است که برنامه را از یک مخزن GitHub می‌سازد. ۱۴ روز (۵ یورو اعتبار) رایگان، بعد پولی. کانفیگ‌ها TLS روی 443 هستند.\n📍 کشور: فقط اروپا (Lucity در اتحادیهٔ اروپا میزبانی می‌شود و انتخاب کشور ندارد). پورت: Lucity خودش PORT=8080 می‌دهد و لانچر همان را می‌خواند؛ فقط Generate Domain را بزنید.\n\n" +
                    "1) اگر GitHub ندارید: «⚡ ساخت خودکار» ← «ثبت‌نام GitHub».\n" +
                    "2) «⚡ ساخت خودکار» ← «ساخت توکن» (صفحه آماده باز می‌شود) ← پایین صفحه Generate token ← کپی ← «چسباندن» ← ادامه. برنامه یک مخزن خصوصی با ۴ فایل می‌سازد.\n" +
                    "3) دکمهٔ «باز کردن Lucity» ← Login with GitHub ← اجازهٔ دسترسی به همان مخزن را بدهید.\n" +
                    "4) New ← GitHub Repository ← همان مخزن ← ساخت.\n" +
                    "5) Settings ← Platform Domain ← Generate Domain (بدون این آدرس ندارید).\n" +
                    "6) وقتی ساخت سبز شد، آدرس را کپی کنید ← «✍️ پنلم را اضافه کن». برنامه پنل و WebSocket را تست می‌کند و کانفیگ‌ها را اضافه می‌کند.\n\n" +
                    "🔁 «به‌روزرسانی» فایل‌های همین نسخهٔ برنامه را دوباره در مخزن می‌گذارد تا Lucity از نو بسازد.\n" +
                    "💾 بدون Volume، کاربرهای ساخته‌شده در پنل با هر ساخت دوباره پاک می‌شوند (کانفیگ اصلی می‌ماند چون UUID در cat-config.json است).",
                "Lucity is a European host that builds the app from a GitHub repository. 14 days (5 EUR credit) free, then paid. Configs are TLS on 443.\n📍 Country: EU only (Lucity is hosted in the European Union; there is no country picker). Port: Lucity gives PORT=8080 itself and the launcher reads it; you only press Generate Domain.\n\n" +
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
                    "3) Apps ← New app ← یک نام، همان مخزن، منطقه (Region) ← ساخت. " + shiprrRegionHint() + "\n" +
                    "4) وقتی بالا آمد ← «✍️ پنلم را اضافه کن» ← آدرس https://نام.shiprr.dev. برنامه تست می‌کند WebSocket از Shiprr رد می‌شود یا نه (مستندات Shiprr چیزی نگفته) و نتیجهٔ واقعی را می‌گوید.\n\n" +
                    "💾 Shiprr دیسک دائمی ندارد: کاربرهای اضافهٔ پنل با هر دیپلوی پاک می‌شوند؛ کانفیگ اصلی می‌ماند.",
                "Shiprr is a European host that builds the app from GitHub. 5 EUR free credit. Address: https://app-name.shiprr.dev — configs are TLS on 443.\n\n" +
                    "1) «⚡ Build automatically» → «Create a token» → Generate token → copy → «Paste» → Continue. The app creates a private repository with 4 files.\n" +
                    "2) «Open Shiprr» → sign up → connect GitHub.\n" +
                    "3) Apps → New app → a name, that repository, region → create. " + shiprrRegionHint() + "\n" +
                    "4) When it is up → «✍️ Add my panel» → the https://name.shiprr.dev address. The app tests whether WebSocket passes through Shiprr (their docs do not say) and tells you the real result.\n\n" +
                    "💾 Shiprr has no persistent disk: extra panel users vanish on each deploy; the main config stays.",
            )
        }
        message("❓ " + name(kind), text)
    }

    // ------------------------------------------------------------------------------------
    // 🧭 start from zero: account → server/token → key → install, one screen per step
    // ------------------------------------------------------------------------------------

    private class Step(val title: String, val text: String, val actions: List<Pair<String, () -> Unit>>)

    /** «Step n of N» pages with ◀ / ▶ and the step's own buttons (each opens exactly the right page). */
    private fun wizard(title: String, steps: List<Step>, startAt: Int = 0) {
        var index = startAt.coerceIn(0, steps.size - 1)
        val body = column()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scroll(body))
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
        fun render() {
            body.removeAllViews()
            val step = steps[index]
            body.addView(label(t("قدم ", "Step ") + (index + 1) + t(" از ", " of ") + steps.size, 12f, palette.teal, 2))
            body.addView(label(step.title, 16f, palette.textPrimary, 4))
            body.addView(label(step.text, 13.5f, palette.textPrimary, 8).apply { setTextIsSelectable(true) })
            step.actions.forEachIndexed { i, (text, action) ->
                body.addView(button(text, filled = i == 0) { action() }, lp(if (i == 0) 12 else 6))
            }
            val nav = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            val half = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }
            if (index > 0) nav.addView(button(t("▶ قبلی", "◀ Back")) { index--; render() }, half)
            if (index < steps.size - 1) nav.addView(button(t("انجام دادم، بعدی ◀", "Done, next ▶"), filled = true) { index++; render() }, half)
            body.addView(nav, lp(16))
        }
        render()
        showDialog(dialog)
    }

    private fun githubSteps(kind: String): List<Step> = listOf(
        Step(
            t("ساخت حساب GitHub (اگر ندارید)", "Create a GitHub account (if you have none)"),
            t(
                name(kind) + " برنامه را از GitHub برمی‌دارد، پس اول یک حساب GitHub لازم است (رایگان).\n\n" +
                    "1) «🌐 ثبت‌نام GitHub» را بزنید.\n" +
                    "2) ایمیل واقعی ← Continue ← یک رمز (حداقل ۸ حرف با عدد) ← Continue ← یک نام کاربری انگلیسی ← Continue.\n" +
                    "3) پازل «تأیید انسان بودن» را حل کنید ← Create account.\n" +
                    "4) GitHub یک کد ۸ رقمی به ایمیلتان می‌فرستد (Spam را هم ببینید) ← کد را وارد کنید.\n" +
                    "5) سؤال‌های خوشامدگویی را می‌توانید Skip کنید.\n\n" +
                    "حساب GitHub دارید؟ «انجام دادم، بعدی» را بزنید.",
                name(kind) + " takes the app from GitHub, so a (free) GitHub account comes first.\n\n" +
                    "1) Tap «🌐 GitHub sign-up».\n" +
                    "2) A real e-mail → Continue → a password (8+ characters with a number) → Continue → a username → Continue.\n" +
                    "3) Solve the «verify you are human» puzzle → Create account.\n" +
                    "4) GitHub e-mails an 8-digit code (check Spam too) → type it in.\n" +
                    "5) You can Skip the welcome questions.\n\n" +
                    "Already have GitHub? Tap «Done, next».",
            ),
            listOf(t("🌐 ثبت‌نام GitHub", "🌐 GitHub sign-up") to { open(NodeHostPanel.GITHUB_SIGNUP) }),
        ),
        Step(
            t("ساخت توکن GitHub", "Create a GitHub token"),
            t(
                "توکن یعنی اجازه‌ای که برنامه با آن فقط یک مخزن خصوصی برای شما بسازد.\n\n" +
                    "1) «🔑 باز کردن صفحهٔ توکن» را بزنید (اگر خواست وارد GitHub شوید). صفحه آماده باز می‌شود: نام Cat Client panel و تیک repo زده شده.\n" +
                    "2) Expiration: مثلاً 90 days (یا No expiration).\n" +
                    "3) پایین صفحه دکمهٔ سبز Generate token.\n" +
                    "4) متنی که با ghp_ شروع می‌شود فقط همین یک بار نشان داده می‌شود ← کنارش دکمهٔ کپی را بزنید.",
                "A token is a permission the app uses only to create one private repository for you.\n\n" +
                    "1) Tap «🔑 Open the token page» (sign in to GitHub if asked). It opens pre-filled: name Cat Client panel, repo ticked.\n" +
                    "2) Expiration: e.g. 90 days (or No expiration).\n" +
                    "3) The green Generate token button at the bottom.\n" +
                    "4) The text starting with ghp_ is shown only this once → tap the copy icon next to it.",
            ),
            listOf(t("🔑 باز کردن صفحهٔ توکن", "🔑 Open the token page") to { open(NodeHostPanel.GITHUB_TOKEN_URL) }),
        ),
        Step(
            t("ساخت مخزن با برنامه", "Let the app create the repository"),
            t(
                "«⚡ ساخت خودکار» را بزنید ← «📋 چسباندن» ← ادامه ← بساز. برنامه یک مخزن خصوصی به نام cat-node-panel-xxxxx با ۴ فایل پنل می‌سازد و نامش را نشان می‌دهد.\n\nبعد اینجا برگردید و «انجام دادم، بعدی».",
                "Tap «⚡ Build automatically» → «📋 Paste» → Continue → Create. The app creates a private repository named cat-node-panel-xxxxx with the 4 panel files and shows its name.\n\nThen come back here and tap «Done, next».",
            ),
            listOf(t("⚡ ساخت خودکار", "⚡ Build automatically") to { askGithubToken(kind) }),
        ),
        if (kind == NodeHostPanel.LUCITY) Step(
            t("ساخت حساب Lucity", "Create a Lucity account"),
            t(
                "1) «🌐 باز کردن Lucity» ← Login with GitHub ← Authorize (اجازه بدهید).\n" +
                    "2) وقتی خواست «Lucity GitHub App» را نصب کند: Only select repositories ← همان cat-node-panel-xxxxx ← Install.\n" +
                    "3) Lucity ۱۴ روز (۵ یورو اعتبار) رایگان است؛ بعد پولی می‌شود.",
                "1) «🌐 Open Lucity» → Login with GitHub → Authorize.\n" +
                    "2) When it asks to install the «Lucity GitHub App»: Only select repositories → that cat-node-panel-xxxxx → Install.\n" +
                    "3) Lucity is free for 14 days (5 EUR credit), then paid.",
            ),
            listOf(t("🌐 باز کردن Lucity", "🌐 Open Lucity") to { open(NodeHostPanel.LUCITY_LOGIN) }),
        ) else Step(
            t("ساخت حساب Shiprr", "Create a Shiprr account"),
            t(
                "1) «🌐 ثبت‌نام Shiprr» ← نام، ایمیل و رمز ← ثبت‌نام. (۵ یورو اعتبار هدیه)\n" +
                    "2) ایمیل تأیید را باز کنید و روی لینکش بزنید (Spam را هم ببینید) ← وارد شوید.\n" +
                    "3) وقتی Shiprr خواست GitHub را وصل کنید، Authorize/Install بزنید و دسترسی به همان cat-node-panel-xxxxx را بدهید.",
                "1) «🌐 Shiprr sign-up» → name, e-mail and password → sign up. (5 EUR gift credit)\n" +
                    "2) Open the confirmation e-mail and tap its link (check Spam) → sign in.\n" +
                    "3) When Shiprr asks to connect GitHub, tap Authorize/Install and allow that cat-node-panel-xxxxx.",
            ),
            listOf(t("🌐 ثبت‌نام Shiprr", "🌐 Shiprr sign-up") to { open(NodeHostPanel.SHIPRR_SIGNUP) }),
        ),
        if (kind == NodeHostPanel.LUCITY) Step(
            t("ساخت برنامه در Lucity و افزودن پنل", "Create the app on Lucity and add the panel"),
            t(
                "1) New ← GitHub Repository ← cat-node-panel-xxxxx ← ساخت. پورت را لازم نیست بنویسید؛ Lucity خودش می‌دهد.\n" +
                    "2) Settings ← Platform Domain ← Generate Domain (بدون این آدرس ندارید).\n" +
                    "3) صبر کنید تا ساخت (Build) سبز شود ← آدرس را کپی کنید.\n" +
                    "4) «✍️ پنلم را اضافه کن» ← آدرس را بچسبانید ← تست و اضافه.",
                "1) New → GitHub Repository → cat-node-panel-xxxxx → create. No port needed; Lucity provides it.\n" +
                    "2) Settings → Platform Domain → Generate Domain (without it there is no address).\n" +
                    "3) Wait until the build is green → copy the address.\n" +
                    "4) «✍️ Add my panel» → paste the address → Test and add.",
            ),
            listOf(t("✍️ پنلم را اضافه کن", "✍️ Add my panel") to { registerManual(kind) }, t("🌐 باز کردن Lucity", "🌐 Open Lucity") to { open(NodeHostPanel.LUCITY_LOGIN) }),
        ) else Step(
            t("ساخت برنامه در Shiprr و افزودن پنل", "Create the app on Shiprr and add the panel"),
            t(
                "1) Apps ← New app ← یک نام انگلیسی (مثلاً mycat) ← مخزن cat-node-panel-xxxxx ← منطقه (Region) ← ساخت. " + shiprrRegionHint() + "\n" +
                    "2) صبر کنید تا بالا بیاید. آدرس می‌شود https://همان-نام.shiprr.dev\n" +
                    "3) «✍️ پنلم را اضافه کن» ← آدرس ← تست و اضافه. برنامه واقعاً تست می‌کند WebSocket از Shiprr رد می‌شود یا نه.",
                "1) Apps → New app → a name (e.g. mycat) → repository cat-node-panel-xxxxx → region → create. " + shiprrRegionHint() + "\n" +
                    "2) Wait until it is up. The address is https://that-name.shiprr.dev\n" +
                    "3) «✍️ Add my panel» → the address → Test and add. The app really tests whether WebSocket passes through Shiprr.",
            ),
            listOf(t("✍️ پنلم را اضافه کن", "✍️ Add my panel") to { registerManual(kind) }, t("🌐 باز کردن Shiprr", "🌐 Open Shiprr") to { open("https://shiprr.app") }),
        ),
    )

    // ------------------------------------------------------------------------------------
    // beta224 SmarterASP.NET — 60-day free trial, Windows/IIS, Node.js through httpPlatformHandler
    // ------------------------------------------------------------------------------------

    private fun smarterAspGuide(): String = t(
        "SmarterASP.NET یک هاست ویندوزی است که ۶۰ روز آزمایشی رایگان می‌دهد، بدون کارت بانکی، با Node.js و یک دامنهٔ موقت. پنل Cat روی آن بالا می‌آید و کانفیگ VLESS/Trojan روی WebSocket همان دامنه می‌دهد.\n\n" +
            "⚠️ پیش از استفاده بدانید: هاست اشتراکی است؛ ممکن است اتصال‌های بیرونی یا WebSocket را محدود کند و دامنهٔ موقتش روی اینترنت شما باز نباشد. برنامه بعد از نصب واقعاً تست می‌کند (پنل، اشتراک، WebSocket 101) و نتیجهٔ واقعی را می‌گوید. بعد از ۶۰ روز یا باید پلن بخرید یا سایت پاک می‌شود. قوانین استفادهٔ SmarterASP را خودتان بخوانید؛ مسئولیت با شماست.\n\n" +
            "🟢 نصب (دستی، ۵ دقیقه):\n" +
            "1) «🧭 از صفر شروع کن» ← ثبت‌نام آزمایشی رایگان (ایمیل لازم است). 📍 در ثبت‌نام دیتاسنتر را انتخاب کنید: Europe (هلند، آمستردام — پینگ بهتر از ایران) یا USA؛ کشور خروجی پنل همین است و بعداً عوض نمی‌شود.\n" +
            "2) «📦 ذخیرهٔ فایل‌ها» ← یک zip در پوشهٔ Download (همهٔ فایل‌ها آماده: پنل، web.config، node_modules، App_Data).\n" +
            "3) کنترل‌پنل ← سایت ← File Manager ← zip را در پوشهٔ اصلی سایت آپلود و Extract کنید (یا با FTP). web.config باید کنار index.js باشد.\n" +
            "4) Node.js را برای سایت روشن کنید (Hosting Manager ← Node.js).\n" +
            "5) آدرس موقت سایت را کپی کنید ← «✍️ پنلم را اضافه کن». بار اول تا ۲ دقیقه طول می‌کشد چون پنل از GitHub دانلود و با SHA-256 چک می‌شود.\n\n" +
            "🔧 اگر لاگ App_Data/cat-node….log گفت Node قدیمی است: طبق راهنمای خود SmarterASP فایل node.exe نسخهٔ 22 (win-x64) را در App_Data بگذارید و در web.config به‌جای processPath=\"node\" بنویسید processPath=\".\\App_Data\\node.exe\" (دکمهٔ «📖 راهنمای نسخهٔ Node»).\n" +
            "🔒 https: اگر آدرس https دارد، کانفیگ‌ها TLS روی 443 می‌شوند؛ اگر فقط http، کانفیگ‌ها بدون TLS روی 80. با دامنهٔ خودتان پشت Cloudflare کانفیگ‌های کامل Cloudflare هم می‌گیرید.",
        "SmarterASP.NET is a Windows host with a 60-day free trial, no card, Node.js and a temporary domain. The Cat panel runs on it and gives VLESS/Trojan configs over WebSocket on that domain.\n\n" +
            "⚠️ Know before you start: it is shared hosting; it may limit outgoing connections or WebSocket, and its temporary domain may not open on your line. After installing, the app really tests it (panel, subscription, WebSocket 101) and tells you the real result. After 60 days you either buy a plan or the site is removed. Read SmarterASP's usage rules yourself; it is your responsibility.\n\n" +
            "🟢 Install (by hand, 5 minutes):\n" +
            "1) «🧭 Start from zero» → free trial sign-up (an e-mail is needed). 📍 Pick the data center at sign-up: Europe (Amsterdam, Netherlands — better ping from Iran) or USA; that is the panel's exit country and it does not change later.\n" +
            "2) «📦 Save the files» → a zip in the Download folder (everything ready: panel, web.config, node_modules, App_Data).\n" +
            "3) Control panel → the site → File Manager → upload the zip to the site's root and Extract it (or use FTP). web.config must sit next to index.js.\n" +
            "4) Enable Node.js for the site (Hosting Manager → Node.js).\n" +
            "5) Copy the site's temporary address → «✍️ Add my panel». The first start takes up to 2 minutes while the panel downloads from GitHub and is checked with SHA-256.\n\n" +
            "🔧 If the log App_Data/cat-node….log says Node is too old: as SmarterASP's own guide says, put node.exe version 22 (win-x64) in App_Data and in web.config replace processPath=\"node\" with processPath=\".\\App_Data\\node.exe\" («📖 Node version guide» button).\n" +
            "🔒 https: if the address has https, configs are TLS on 443; with http only, configs are without TLS on 80. With your own domain behind Cloudflare you also get the full Cloudflare set.",
    )

    private fun smarterAspSteps(): List<Step> = listOf(
        Step(
            t("ثبت‌نام آزمایشی رایگان ۶۰ روزه", "60-day free trial sign-up"),
            t(
                "1) «🌐 ثبت‌نام SmarterASP» را بزنید (صفحهٔ پلن رایگان باز می‌شود).\n" +
                    "2) ایمیل واقعی، نام کاربری و رمز ← ثبت‌نام. کارت بانکی نمی‌خواهد.\n" +
                    "3) ایمیل فعال‌سازی را باز کنید (Spam را هم ببینید) و وارد کنترل‌پنل شوید.\n\n" +
                    "اگر سایت روی اینترنت شما باز نمی‌شود، اول یک کانفیگ را وصل کنید.",
                "1) Tap «🌐 SmarterASP sign-up» (the free plan page opens).\n" +
                    "2) A real e-mail, a username and a password → sign up. No card needed.\n" +
                    "3) Open the activation e-mail (check Spam) and sign in to the control panel.\n\n" +
                    "If the site does not open on your line, connect a config first.",
            ),
            listOf(t("🌐 ثبت‌نام SmarterASP", "🌐 SmarterASP sign-up") to { open(NodeHostPanel.SMARTERASP_SIGNUP) }),
        ),
        Step(
            t("ذخیرهٔ فایل‌های پنل", "Save the panel files"),
            t(
                "«📦 ذخیرهٔ فایل‌ها» را بزنید. یک zip با همهٔ فایل‌های لازم (پنل، web.config، node_modules/ws، App_Data و شناسهٔ کانفیگ همین گوشی) در پوشهٔ Download ذخیره می‌شود.",
                "Tap «📦 Save the files». A zip with everything needed (panel, web.config, node_modules/ws, App_Data and this phone's config identity) is saved to the Download folder.",
            ),
            listOf(t("📦 ذخیرهٔ فایل‌ها", "📦 Save the files") to { saveFiles(NodeHostPanel.SMARTERASP) }),
        ),
        Step(
            t("آپلود و روشن کردن Node.js", "Upload and enable Node.js"),
            t(
                "1) «🌐 کنترل‌پنل» ← سایت (site1) ← File Manager.\n" +
                    "2) zip را در پوشهٔ اصلی سایت آپلود کنید ← Extract (اگر Extract نبود، محتوای zip را با FTP بگذارید). index.js و web.config باید مستقیم در پوشهٔ اصلی باشند.\n" +
                    "3) Hosting Manager ← Node.js ← برای همین سایت روشن کنید.\n" +
                    "4) آدرس موقت سایت را از صفحهٔ سایت کپی کنید.",
                "1) «🌐 Control panel» → the site (site1) → File Manager.\n" +
                    "2) Upload the zip to the site's root → Extract (no Extract? put the zip's contents there by FTP). index.js and web.config must be directly in the root.\n" +
                    "3) Hosting Manager → Node.js → enable it for this site.\n" +
                    "4) Copy the site's temporary address from the site page.",
            ),
            listOf(
                t("🌐 کنترل‌پنل", "🌐 Control panel") to { open(NodeHostPanel.SMARTERASP_CONTROL) },
                t("📖 راهنمای Node در SmarterASP", "📖 SmarterASP Node guide") to { open(NodeHostPanel.SMARTERASP_NODE_KB) },
                t("📖 راهنمای نسخهٔ Node", "📖 Node version guide") to { open(NodeHostPanel.SMARTERASP_NODE_VERSION_KB) },
            ),
        ),
        Step(
            t("افزودن و تست واقعی", "Add and really test"),
            t(
                "«✍️ پنلم را اضافه کن» ← آدرس را بچسبانید ← تست و اضافه. برنامه تا ۲ دقیقه صبر می‌کند (بار اول پنل دانلود می‌شود)، بعد پنل، لینک اشتراک و WebSocket 101 را از همین گوشی تست می‌کند. اگر WebSocket رد نشد، یعنی این هاست تونل را نمی‌گذارد و برنامه همین را صادقانه می‌گوید.",
                "«✍️ Add my panel» → paste the address → Test and add. The app waits up to 2 minutes (the first start downloads the panel), then tests the panel, the subscription link and WebSocket 101 from this phone. If WebSocket does not pass, this host does not allow the tunnel and the app says exactly that.",
            ),
            listOf(t("✍️ پنلم را اضافه کن", "✍️ Add my panel") to { registerManual(NodeHostPanel.SMARTERASP) }),
        ),
    )

    private fun kataBumpSteps(): List<Step> = listOf(
        Step(
            t("ساخت حساب KataBump", "Create a KataBump account"),
            t(
                "1) «🌐 باز کردن صفحهٔ ثبت‌نام» را بزنید.\n" +
                    "2) First name (نام)، Last name (نام خانوادگی)، یک ایمیل واقعی و یک رمز بنویسید ← Create account. (حساب Discord دارید؟ دکمهٔ Discord هم کار می‌کند.)\n" +
                    "3) ایمیلتان را باز کنید (پوشهٔ Spam را هم ببینید) و روی لینک تأیید بزنید.\n" +
                    "4) با همان ایمیل و رمز وارد شوید (Login).\n\n" +
                    "⚠️ فقط یک حساب بسازید: KataBump به هر نفر و هر خانه یک سرور رایگان می‌دهد؛ چند حساب یا عوض کردن VPN برای حساب دوم = مسدود شدن.\n" +
                    "🌐 اگر سایت باز نشد یا خطا داد، اول VPN/کانفیگ را وصل کنید.",
                "1) Tap «🌐 Open the sign-up page».\n" +
                    "2) First name, Last name, a real e-mail and a password → Create account. (Have Discord? The Discord button works too.)\n" +
                    "3) Open your e-mail (check Spam too) and tap the confirmation link.\n" +
                    "4) Log in with that e-mail and password.\n\n" +
                    "⚠️ Create only one account: KataBump gives one free server per person and household; several accounts, or a VPN to make a second one = ban.\n" +
                    "🌐 If the site does not open or errors, connect a VPN/config first.",
            ),
            listOf(
                t("🌐 باز کردن صفحهٔ ثبت‌نام", "🌐 Open the sign-up page") to { open(NodeHostPanel.KATABUMP_REGISTER) },
                t("حساب دارم — صفحهٔ ورود", "I have an account — log in") to { open(NodeHostPanel.KATABUMP_DASHBOARD) },
            ),
        ),
        Step(
            t("ساخت سرور رایگان Node.js", "Create the free Node.js server"),
            t(
                "1) «🌐 باز کردن داشبورد» ← وارد شوید.\n" +
                    "2) «Create a server» را بزنید (اگر نمی‌بینید، از منوی کنار: Servers یا Order).\n" +
                    "3) Node.js را انتخاب کنید ← پلن Free ← یک نام انگلیسی (مثلاً cat) ← Create.\n" +
                    "4) ۲ تا ۵ دقیقه طول می‌کشد تا سرور ساخته شود؛ صفحه را تازه کنید تا در فهرست بیاید.\n\n" +
                    "نسخهٔ Node را لازم نیست انتخاب کنید؛ برنامه موقع نصب خودش آن را 20 یا بالاتر می‌کند.",
                "1) «🌐 Open the dashboard» → log in.\n" +
                    "2) Tap «Create a server» (not visible? use the side menu: Servers or Order).\n" +
                    "3) Pick Node.js → the Free plan → a name (e.g. cat) → Create.\n" +
                    "4) It takes 2–5 minutes; refresh until the server shows up in the list.\n\n" +
                    "You do not need to pick a Node version; the app moves it to 20 or newer during the install.",
            ),
            listOf(t("🌐 باز کردن داشبورد", "🌐 Open the dashboard") to { open(NodeHostPanel.KATABUMP_DASHBOARD) }),
        ),
        Step(
            t("ورود به پنل کنترل (رمز جدا دارد)", "Log in to the control panel (its own password)"),
            t(
                "سرور را در سایت دومی مدیریت می‌کنند: control.katabump.com. نام کاربری و رمز آن را داشبورد می‌دهد:\n\n" +
                    "1) «🌐 باز کردن داشبورد» ← کنار سرورتان See more ← Access server.\n" +
                    "2) Identifier و Current password را کپی کنید.\n" +
                    "3) «🌐 باز کردن پنل کنترل» ← با همین دو مورد وارد شوید. باید سرورتان را آنجا ببینید.",
                "The server is managed on a second site: control.katabump.com. The dashboard gives its username and password:\n\n" +
                    "1) «🌐 Open the dashboard» → next to your server See more → Access server.\n" +
                    "2) Copy the Identifier and the Current password.\n" +
                    "3) «🌐 Open the control panel» → log in with those two. You should see your server there.",
            ),
            listOf(
                t("🌐 باز کردن داشبورد", "🌐 Open the dashboard") to { open(NodeHostPanel.KATABUMP_DASHBOARD) },
                t("🌐 باز کردن پنل کنترل", "🌐 Open the control panel") to { open(NodeHostPanel.KATABUMP_CONTROL_LOGIN) },
            ),
        ),
        Step(
            t("ساخت کلید API", "Create the API key"),
            t(
                "1) «🔑 باز کردن صفحهٔ کلید» (در پنل کنترل: آیکن حساب بالای صفحه ← API Credentials).\n" +
                    "2) Description: هر چیزی، مثلاً cat. کادر Allowed IPs را خالی بگذارید.\n" +
                    "3) Create ← کلیدی که با ptlc_ شروع می‌شود فقط همین یک بار نشان داده می‌شود؛ همان لحظه کپی کنید.",
                "1) «🔑 Open the key page» (in the control panel: account icon at the top → API Credentials).\n" +
                    "2) Description: anything, e.g. cat. Leave Allowed IPs empty.\n" +
                    "3) Create → the key starting with ptlc_ is shown only this once; copy it right away.",
            ),
            listOf(t("🔑 باز کردن صفحهٔ کلید", "🔑 Open the key page") to { open(NodeHostPanel.KATABUMP_API_KEYS) }),
        ),
        Step(
            t("نصب خودکار", "Automatic install"),
            t(
                "«⚡ نصب خودکار» را بزنید ← تیک قوانین ← کلید را بچسبانید ← سرور ← نصب کن.\n" +
                    "اگر سرور هنوز ساخته نشده باشد، برنامه تا ۶ دقیقه صبر می‌کند تا پیدا شود.\n\n" +
                    "🔁 بعد از نصب: هر ۴ روز یک بار در داشبورد Renew بزنید (فقط در ۲ روز آخر دکمه‌اش فعال است). برنامه روز سوم یادآوری می‌کند.",
                "Tap «⚡ Install automatically» → tick the rules → paste the key → server → Install.\n" +
                    "If the server is not ready yet, the app waits up to 6 minutes for it.\n\n" +
                    "🔁 After the install: press Renew on the dashboard every 4 days (it only works in the last 2 days). The app reminds you on day 3.",
            ),
            listOf(t("⚡ نصب خودکار", "⚡ Install automatically") to { autoInstall(NodeHostPanel.KATABUMP) }),
        ),
    )

    fun showStart(kind: String, startAt: Int = 0) {
        if (NodeHostPanel.isApiHost(kind)) { autoInstall(kind); return }
        val steps = when (kind) {
            NodeHostPanel.KATABUMP -> kataBumpSteps()
            NodeHostPanel.SMARTERASP -> smarterAspSteps()
            else -> githubSteps(kind)
        }
        wizard("🧭 " + name(kind) + t(" — از صفر", " — from zero"), steps, startAt)
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
    // 🤖 Bot-Hosting.net / 🚂 Railway — the whole install through the host's own API
    // ------------------------------------------------------------------------------------

    private fun apiExplain(kind: String, e: Throwable): String {
        if (e !is ApiHostException) return explain(e)
        val msg = e.message.orEmpty()
        val why = if (kind == NodeHostPanel.BOTHOSTING) ApiHostPlan.explainBotHosting(e.code, msg) else ApiHostPlan.explainRailway(msg)
        val plain = when (why) {
            "key" -> t("کلید پذیرفته نشد (۴۰۱). یک کلید تازه بسازید و کامل (با bhk_) بچسبانید.", "The key was refused (401). Create a new key and paste all of it (with bhk_).")
            "scope" -> t("کلید دسترسی کافی ندارد. کلید تازه با این تیک‌ها بسازید: ", "The key lacks a permission. Create a new key with these ticks: ") + ApiHostPlan.BOTHOSTING_SCOPES.joinToString(", ")
            "slot" -> t(
                "جای خالی ندارید: پلن رایگان فقط ۱ دیپلوی دارد. یا دیپلوی قبلی را در bot-hosting.net پاک کنید، یا نامش را cat-panel بگذارید تا برنامه همان را به‌روز کند.",
                "No free slot: the free plan has 1 deployment. Delete the old one on bot-hosting.net, or rename it to cat-panel so the app updates it.",
            )
            "rate" -> t("درخواست زیاد شد (۴۲۹). یک دقیقه صبر کنید و دوباره بزنید.", "Too many requests (429). Wait a minute and try again.")
            "token" -> t(
                "توکن Railway پذیرفته نشد. در railway.com/account/tokens یک توکن حساب (Account / بدون انتخاب Project) بسازید و کامل بچسبانید.",
                "Railway refused the token. On railway.com/account/tokens create an ACCOUNT token (no project selected) and paste all of it.",
            )
            "plan" -> t(
                "حساب Railway اجازهٔ ساخت نمی‌دهد (دورهٔ آزمایشی تمام شده، حساب تأیید نشده یا سقف پلن). در داشبورد Railway وضعیت پلن را ببینید.",
                "The Railway account may not create this (trial over, account not verified or plan limit). Check the plan on the Railway dashboard.",
            )
            "workspace" -> t("Railway فضای کاری (Workspace) خواست و پیدا نشد. یک بار وارد railway.com شوید و یک پروژهٔ خالی بسازید، بعد دوباره بزنید.", "Railway asked for a workspace and none was found. Log in to railway.com once and create an empty project, then try again.")
            else -> t("پاسخ سرویس: ", "Service answer: ") + msg.take(200)
        }
        return plain + (if (why != null) "\n(" + msg.take(160) + ")" else "")
    }

    private fun askBotHostingKey() {
        val box = column()
        box.addView(label(t(
            "Bot-Hosting.net هاست رایگان است (۲۵۶ مگ رم، بدون کارت؛ هر ۴ روز یک «تمدید» با یک کلیک). برنامه با «کلید API» خود شما همه‌چیز را می‌سازد؛ کلید فقط روی همین گوشی می‌ماند.\n\n" +
                "1) «👤 ثبت‌نام / ورود» ← با Discord یا GitHub وارد شوید.\n" +
                "2) «🔑 صفحهٔ کلید» ← Create key ← یک نام ← این تیک‌ها:\n   " + ApiHostPlan.BOTHOSTING_SCOPES.joinToString(" · ") + "\n" +
                "3) کلیدی که با bhk_ شروع می‌شود را کپی کنید (فقط یک بار نشان داده می‌شود) ← برگردید ← «📋 چسباندن» ← ادامه.",
            "Bot-Hosting.net is a free host (256 MB RAM, no card; a one-click «renew» every 4 days). The app builds everything with your own «API key»; the key stays on this phone only.\n\n" +
                "1) «👤 Sign up / log in» → with Discord or GitHub.\n" +
                "2) «🔑 Key page» → Create key → any name → these ticks:\n   " + ApiHostPlan.BOTHOSTING_SCOPES.joinToString(" · ") + "\n" +
                "3) Copy the key that starts with bhk_ (shown once) → come back → «📋 Paste» → Continue.",
        ), 13f, palette.textPrimary, 2))
        val field = input("bhk_…", store.botHostingKey, password = true)
        box.addView(field, lp(8))
        box.addView(button(t("👤 ثبت‌نام / ورود", "👤 Sign up / log in")) { open(ApiHostPlan.BOTHOSTING_LOGIN) }, lp(8))
        box.addView(button(t("🔑 صفحهٔ کلید", "🔑 Key page")) { open(ApiHostPlan.BOTHOSTING_KEYS) }, lp(6))
        box.addView(button(t("📋 چسباندن", "📋 Paste")) { field.setText(clipboardText().trim()) }, lp(6))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("⚡ نصب خودکار روی Bot-Hosting.net", "⚡ Automatic install on Bot-Hosting.net"))
                .setView(scroll(box))
                .setPositiveButton(t("ادامه", "Continue")) { _, _ ->
                    val key = field.text.toString().trim()
                    if (!ApiHostPlan.isBotHostingKey(key)) {
                        toast(t("کلید باید با bhk_ شروع شود — کامل بچسبانید", "The key must start with bhk_ — paste all of it"))
                    } else {
                        store.botHostingKey = key
                        confirmApiInstall(NodeHostPanel.BOTHOSTING) { installBotHosting(key) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    /** Every installer step is listed and needs a tick before anything is created (user rule). */
    private fun confirmApiInstall(kind: String, next: () -> Unit) {
        val box = column()
        val railway = kind == NodeHostPanel.RAILWAY
        box.addView(label(if (railway) t(
            "این کارها با توکن شما روی Railway انجام می‌شود:\n" +
                "• یک پروژهٔ تازه به نام cat-panel\n• یک سرویس از ایمیج رسمی node:22-alpine\n• سرویس در کشوری که انتخاب کردید گذاشته می‌شود\n• متغیرهای UUID و TROJAN_PASS (هویت پنل شما) و PORT=8080 (برنامه خودش می‌گذارد؛ دامنه هم خودکار روی همین پورت ساخته می‌شود و کانفیگ‌ها TLS روی 443 هستند)\n" +
                "• دستور شروع: لانچر همین نسخه از GitHub دانلود و اجرا می‌شود (پنل را با SHA-256 چک می‌کند)\n• یک دامنهٔ *.up.railway.app و یک Deploy\n• بعد برنامه از همین گوشی پنل، اشتراک و WebSocket را واقعاً تست می‌کند.\n\n" +
                "⚠️ قوانین Railway (Acceptable Use) «اجرای پروکسی یا سرویس ناشناس‌سازی» را سوءاستفاده می‌داند؛ ممکن است سرویس یا حساب را ببندند. Railway رایگان نیست: بعد از اعتبار آزمایشی، پلن پولی لازم است. ریسک با خود شماست.",
            "With your token the app does this on Railway:\n" +
                "• a new project named cat-panel\n• one service from the official node:22-alpine image\n• the service is placed in the country you picked\n• variables UUID and TROJAN_PASS (your panel identity) and PORT=8080 (set by the app; the domain is created on that port automatically and the configs are TLS on 443)\n" +
                "• start command: downloads THIS release's launcher from GitHub and runs it (it checks the panel's SHA-256)\n• a *.up.railway.app domain and one Deploy\n• then the app really tests the panel, the subscription and the WebSocket from this phone.\n\n" +
                "⚠️ Railway's Acceptable Use Policy lists «operating proxies or anonymization services» as abuse; they may close the service or the account. Railway is not free: after the trial credit a paid plan is needed. The risk is yours.",
        ) else t(
            "این کارها با کلید شما روی Bot-Hosting.net انجام می‌شود:\n" +
                "• اگر دیپلویی به نام cat-panel دارید همان به‌روز می‌شود، وگرنه یک دیپلوی Node.js 22 ساخته می‌شود (پلن رایگان: ۱ دیپلوی)\n" +
                "• ۴ فایل گذاشته می‌شود: index.js، package.json، README.md و cat-config.json (هویت پنل شما)\n• دستور شروع: npm install && node index.js\n• دامنهٔ رایگان HTTPS (*.apps.bot-hosting.cloud) فعال و دیپلوی روشن می‌شود\n• بعد برنامه از همین گوشی پنل، اشتراک و WebSocket را واقعاً تست می‌کند.\n\n" +
                "📍 کشور: Bot-Hosting خودش سرور را انتخاب می‌کند (طبق قوانینش قابل انتخاب نیست؛ معمولاً فنلاند یا آلمان، گاهی آمریکا). برنامه بعد از نصب نام سرور و کشورش را نشان می‌دهد. پورت و دامنهٔ HTTPS را هم خودش می‌دهد؛ کانفیگ‌ها TLS روی 443.\n\n" +
                "قوانین Bot-Hosting (بند ۵) پروکسی را ممنوع نکرده، ولی فروش/اجارهٔ سرویس به دیگران (Reselling) ممنوع است — فقط استفادهٔ شخصی. پلن رایگان هر ۴ روز تمدید می‌خواهد؛ برنامه یادآوری می‌گذارد.",
            "With your key the app does this on Bot-Hosting.net:\n" +
                "• a deployment named cat-panel is updated if you have one, otherwise a Node.js 22 deployment is created (free plan: 1 deployment)\n" +
                "• 4 files are written: index.js, package.json, README.md and cat-config.json (your panel identity)\n• start command: npm install && node index.js\n• the free HTTPS domain (*.apps.bot-hosting.cloud) is activated and the deployment started\n• then the app really tests the panel, the subscription and the WebSocket from this phone.\n\n" +
                "📍 Country: Bot-Hosting picks the server itself (its rules say it cannot be chosen; usually Finland or Germany, sometimes the USA). After installing, the app shows the server name and its country. It also gives the port and the HTTPS domain; configs are TLS on 443.\n\n" +
                "Bot-Hosting's rules (section 5) do not forbid proxies, but reselling the service to others is forbidden — personal use only. The free plan needs a renewal every 4 days; the app sets a reminder.",
        ), 13f, palette.textPrimary, 2))
        val tick = CheckBox(activity).apply {
            text = t("خواندم؛ نصب کن", "I read this; install")
            setTextColor(palette.textPrimary)
        }
        box.addView(tick, lp(10))
        box.addView(button(t("📜 خواندن قوانین", "📜 Read the rules")) { open(if (railway) ApiHostPlan.RAILWAY_RULES else ApiHostPlan.BOTHOSTING_RULES) }, lp(6))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("پیش از نصب", "Before installing"))
            .setView(scroll(box))
            .setPositiveButton(t("نصب", "Install")) { _, _ -> next() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            tick.setOnCheckedChangeListener { _, checked -> ok.isEnabled = checked }
        }
        showDialog(dialog)
    }

    private fun installBotHosting(key: String) {
        val kind = NodeHostPanel.BOTHOSTING
        runSteps<Outcome>("⚡ Bot-Hosting.net", { step ->
            val (uuid, pass) = identity(kind)
            step(t("1/6 خواندن دیپلوی‌های شما…", "1/6 Reading your deployments…"))
            val list = try { ApiHostClient.bhList(key) } catch (e: Exception) { throw IllegalStateException(apiExplain(kind, e)) }
            var dep = list.firstOrNull { it.name.equals(ApiHostPlan.DEPLOY_NAME, true) }
            if (dep == null) {
                step(t("2/6 ساخت دیپلوی Node.js 22 به نام cat-panel…", "2/6 Creating a Node.js 22 deployment named cat-panel…"))
                dep = try { ApiHostClient.bhCreate(key, ApiHostPlan.DEPLOY_NAME) } catch (e: Exception) { throw IllegalStateException(apiExplain(kind, e)) }
            } else {
                step("2/6 " + t("دیپلوی موجود به‌روز می‌شود: ", "updating the existing deployment: ") + dep.name)
            }
            val id = dep.id
            step(t("3/6 گذاشتن فایل‌ها…", "3/6 Writing the files…"))
            val texts = files(kind).mapValues { String(it.value, Charsets.UTF_8) }
            try { ApiHostClient.bhWriteMany(key, id, texts) } catch (e: Exception) { throw IllegalStateException(apiExplain(kind, e)) }
            texts.keys.forEach { step("   ✓ $it") }
            step(t("4/6 دستور شروع…", "4/6 Start command…"))
            runCatching { ApiHostClient.bhStartup(key, id, ApiHostPlan.BOTHOSTING_START) }
                .onSuccess { step("   ✓ " + ApiHostPlan.BOTHOSTING_START) }
                .onFailure { step("   ⚠️ " + apiExplain(kind, it)) }
            step(t("5/6 دامنهٔ HTTPS رایگان…", "5/6 Free HTTPS domain…"))
            val url = runCatching { ApiHostClient.bhDomains(key, id) }
                .onFailure { step("   ⚠️ " + apiExplain(kind, it)) }
                .getOrNull()
            url?.let { step("   ✓ $it") }
            step(t("6/6 روشن کردن (بار اول npm install چند دقیقه طول می‌کشد)…", "6/6 Starting (the first npm install takes a few minutes)…"))
            runCatching { ApiHostClient.bhPower(key, id, "restart", 45) }
                .recoverCatching { ApiHostClient.bhPower(key, id, "start", 45) }
                .onSuccess { (state, logs) ->
                    step("   state: " + state.ifBlank { "?" })
                    logs.takeLast(4).forEach { step("   │ " + it.take(140)) }
                }
                .onFailure { step("   ⚠️ " + apiExplain(kind, it)) }
            val fresh = runCatching { ApiHostClient.bhGet(key, id) }.getOrNull()
            // beta226: Bot-Hosting picks the node itself (cannot be chosen); say where it really landed.
            fresh?.nodeHost?.let { node ->
                val c = ApiHostPlan.botHostingNodeCountry(node)
                step("   📍 " + t("سرور: ", "server: ") + node + (c?.let { " — " + t(it.first, it.second) } ?: ""))
            }
            val httpsBase = url ?: fresh?.url
            val directBase = fresh?.let { d -> d.nodeHost?.let { h -> d.port?.let { p -> NodeHostPanel.normalizeAddress("$h:$p") } } }
            val first = httpsBase ?: directBase
            if (first == null) {
                Outcome(null, emptyList(), false, t(
                    "دیپلوی ساخته و روشن شد ولی Bot-Hosting آدرس عمومی نداد. در bot-hosting.net ← دیپلوی cat-panel ← Domains ← Activate را بزنید، آدرس را کپی کنید و «✍️ پنلم را اضافه کن».",
                    "The deployment was created and started but Bot-Hosting gave no public address. On bot-hosting.net → cat-panel → Domains → Activate, copy the address and tap «✍️ Add my panel».",
                ), kind = kind)
            } else {
                step(t("تست پنل از همین گوشی (تا ۳ دقیقه)…", "Testing the panel from this phone (up to 3 min)…"))
                var base = first
                var check = verify(kind, first, uuid, step, 180)
                if (!check.ok && directBase != null && directBase != first) {
                    step(t("امتحان آدرس مستقیم سرور: ", "Trying the server's direct address: ") + directBase)
                    val direct = verify(kind, directBase, uuid, step, 30)
                    if (direct.ok || (!check.answered && direct.answered)) { base = directBase; check = direct }
                }
                val now = System.currentTimeMillis()
                val saved = SavedNodeHost("bothosting-$id", kind, dep.name + " · Bot-Hosting", base, uuid, id, now, now, pass)
                store.save(saved)
                Outcome(saved, check.links, check.ok, check.headline)
            }
        }) { outcome, lines -> showOutcome(outcome, lines) }
    }

    private fun askRailwayToken() {
        val box = column()
        box.addView(label(t(
            "برنامه با «توکن» حساب Railway شما پروژه و سرویس را می‌سازد؛ توکن فقط روی همین گوشی می‌ماند.\n\n" +
                "1) «👤 ورود به Railway» ← با GitHub یا ایمیل وارد شوید (حساب باید تأییدشده و دارای اعتبار/پلن باشد).\n" +
                "2) «🔑 صفحهٔ توکن» ← Create token ← یک نام ← Workspace/Team را روی حساب خودتان بگذارید (Project انتخاب نکنید) ← Create.\n" +
                "3) توکن را کپی کنید ← برگردید ← «📋 چسباندن» ← ادامه.",
            "The app creates the project and service with your Railway account «token»; the token stays on this phone only.\n\n" +
                "1) «👤 Log in to Railway» → with GitHub or email (the account must be verified and have credit/a plan).\n" +
                "2) «🔑 Token page» → Create token → any name → leave the workspace on your own account (do not pick a project) → Create.\n" +
                "3) Copy the token → come back → «📋 Paste» → Continue.",
        ), 13f, palette.textPrimary, 2))
        val field = input(t("توکن Railway", "Railway token"), store.railwayToken, password = true)
        box.addView(field, lp(8))
        box.addView(button(t("👤 ورود به Railway", "👤 Log in to Railway")) { open(ApiHostPlan.RAILWAY_LOGIN) }, lp(8))
        box.addView(button(t("🔑 صفحهٔ توکن", "🔑 Token page")) { open(ApiHostPlan.RAILWAY_TOKENS) }, lp(6))
        box.addView(button(t("📋 چسباندن", "📋 Paste")) { field.setText(clipboardText().trim()) }, lp(6))
        // beta226: the region = the country sites see. Railway lets every plan pick one of its 4 regions.
        box.addView(label(t(
            "📍 کشور سرور (سایت‌ها آی‌پی همین کشور را می‌بینند):",
            "📍 Server country (sites see an IP from this country):",
        ), 13f, palette.textPrimary, 14))
        val regionGroup = RadioGroup(activity)
        val regionIds = HashMap<Int, String>()
        ApiHostPlan.RAILWAY_REGIONS.forEach { (id, names) ->
            val rb = RadioButton(activity).apply {
                this.id = View.generateViewId()
                text = t(names.first, names.second)
                setTextColor(palette.textPrimary)
                minHeight = (44 * activity.resources.displayMetrics.density).toInt()
            }
            regionIds[rb.id] = id
            regionGroup.addView(rb)
            if (id == store.railwayRegion) rb.isChecked = true
        }
        box.addView(regionGroup, lp(4))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("🚂 نصب خودکار روی Railway", "🚂 Automatic install on Railway"))
                .setView(scroll(box))
                .setPositiveButton(t("ادامه", "Continue")) { _, _ ->
                    val token = field.text.toString().trim()
                    if (!ApiHostPlan.looksLikeRailwayToken(token)) {
                        toast(t("توکن را کامل بچسبانید", "Paste the whole token"))
                    } else {
                        store.railwayToken = token
                        val region = regionIds[regionGroup.checkedRadioButtonId] ?: ApiHostPlan.RAILWAY_DEFAULT_REGION
                        store.railwayRegion = region
                        confirmApiInstall(NodeHostPanel.RAILWAY) { installRailway(token, region) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun installRailway(token: String, region: String = ApiHostPlan.RAILWAY_DEFAULT_REGION) {
        val kind = NodeHostPanel.RAILWAY
        val tag = NodeHostPanel.releaseTag(BuildConfig.VERSION_NAME)
        runSteps<Outcome>("🚂 Railway", { step ->
            val (uuid, pass) = identity(kind)
            fun <T> api(block: () -> T): T = try { block() } catch (e: Exception) { throw IllegalStateException(apiExplain(kind, e)) }
            step(t("1/8 بررسی توکن…", "1/8 Checking the token…"))
            val who = api { ApiHostClient.rwWhoAmI(token) }
            step("   ✓ " + who.ifBlank { "Railway" })
            step(t("2/8 ساخت پروژهٔ cat-panel…", "2/8 Creating the cat-panel project…"))
            val (projectId, envId) = api { ApiHostClient.rwCreateProject(token, ApiHostPlan.DEPLOY_NAME) }
            step("   ✓ project " + projectId.take(8) + "…")
            step(t("3/8 سرویس از ایمیج ", "3/8 Service from image ") + ApiHostPlan.RAILWAY_IMAGE + "…")
            val serviceId = api { ApiHostClient.rwCreateService(token, projectId, ApiHostPlan.DEPLOY_NAME, ApiHostPlan.RAILWAY_IMAGE) }
            step(t("4/8 کشور سرور: ", "4/8 Server country: ") + (ApiHostPlan.RAILWAY_REGIONS.firstOrNull { it.first == region }?.second?.let { t(it.first, it.second) } ?: region))
            runCatching { ApiHostClient.rwSetRegion(token, serviceId, envId, region) }
                .onSuccess { step("   ✓ " + region) }
                .onFailure { step("   ⚠️ " + apiExplain(kind, it) + t(" — Railway منطقهٔ پیش‌فرض حساب را می‌گذارد (railway.com ← Account ← Preferred region).", " — Railway uses the account's default region (railway.com → Account → Preferred region).")) }
            step(t("5/8 متغیرها (UUID، TROJAN_PASS، PORT)…", "5/8 Variables (UUID, TROJAN_PASS, PORT)…"))
            api {
                ApiHostClient.rwUpsertVariables(token, projectId, envId, serviceId, linkedMapOf(
                    "UUID" to uuid,
                    "TROJAN_PASS" to pass,
                    "PORT" to ApiHostPlan.RAILWAY_PORT.toString(),
                    "CAT_PANEL_TAG" to tag,
                    "CAT_HOST" to NodeHostPanel.RAILWAY,
                ))
            }
            step(t("6/8 دستور شروع (لانچر ", "6/8 Start command (launcher ") + tag + ")…")
            api { ApiHostClient.rwSetStartCommand(token, serviceId, envId, ApiHostPlan.railwayStartCommand(tag)) }
            step(t("7/8 دامنه…", "7/8 Domain…"))
            val domain = api { ApiHostClient.rwCreateDomain(token, serviceId, envId, ApiHostPlan.RAILWAY_PORT) }
            val base = ApiHostPlan.httpsBase(domain) ?: throw IllegalStateException("domain: $domain")
            step("   ✓ $base")
            step(t("8/8 Deploy…", "8/8 Deploy…"))
            runCatching { ApiHostClient.rwDeploy(token, serviceId, envId) }.onFailure { step("   ⚠️ " + apiExplain(kind, it)) }
            var status = ""
            for (i in 0 until 24) {
                delay(10_000)
                status = ApiHostClient.rwStatus(token, serviceId, envId).ifBlank { status }
                if (i % 3 == 2 || status == "SUCCESS" || status == "FAILED" || status == "CRASHED") step("   … " + status.ifBlank { "?" } + " (" + ((i + 1) * 10) + "s)")
                if (status == "SUCCESS" || status == "FAILED" || status == "CRASHED") break
            }
            val ref = ApiHostPlan.railwayRef(projectId, serviceId, envId)
            val now = System.currentTimeMillis()
            val saved = SavedNodeHost("railway-$serviceId", kind, "cat-panel · Railway " + (ApiHostPlan.RAILWAY_REGIONS.firstOrNull { it.first == region }?.second?.first?.substringBefore(' ') ?: ""), base, uuid, ref, now, now, pass)
            store.save(saved)
            if (status == "FAILED" || status == "CRASHED") {
                Outcome(saved, emptyList(), false, t(
                    "Deploy روی Railway شکست خورد (" + status + "). در railway.com ← پروژهٔ cat-panel ← Deployments ← View logs علت را ببینید (معمولاً اعتبار/پلن تمام شده).",
                    "The Railway deploy failed (" + status + "). On railway.com → cat-panel project → Deployments → View logs (usually the credit/plan ran out).",
                ))
            } else {
                step(t("تست پنل از همین گوشی (تا ۳ دقیقه)…", "Testing the panel from this phone (up to 3 min)…"))
                val check = verify(kind, base, uuid, step, 180)
                Outcome(saved, check.links, check.ok, check.headline)
            }
        }) { outcome, lines -> showOutcome(outcome, lines) }
    }

    /** «🔁 Update» on an API host: Bot-Hosting rewrites the files and restarts; Railway redeploys the same service. */
    private fun updateApiHost(item: SavedNodeHost) {
        if (item.kind == NodeHostPanel.BOTHOSTING) {
            val key = store.botHostingKey
            if (!ApiHostPlan.isBotHostingKey(key)) { askBotHostingKey(); return }
            confirmApiInstall(NodeHostPanel.BOTHOSTING) { installBotHosting(key) }
            return
        }
        val token = store.railwayToken
        val ref = ApiHostPlan.parseRailwayRef(item.serverId)
        if (token.isBlank() || ref == null) { askRailwayToken(); return }
        message2(
            t("🔁 به‌روزرسانی روی Railway", "🔁 Update on Railway"),
            t(
                "همان سرویس دوباره Deploy می‌شود؛ لانچر هنگام شروع جدیدترین نسخهٔ پنل را می‌گیرد (UUID و کانفیگ‌ها عوض نمی‌شوند).",
                "The same service is deployed again; at start the launcher fetches the newest panel (the UUID and configs stay the same).",
            ),
            t("Deploy کن", "Deploy"),
        ) {
            runSteps<Outcome>("🚂 Railway", { step ->
                val tag = NodeHostPanel.releaseTag(BuildConfig.VERSION_NAME)
                runCatching {
                    ApiHostClient.rwUpsertVariables(token, ref.first, ref.third, ref.second, mapOf("CAT_PANEL_TAG" to tag))
                    ApiHostClient.rwSetStartCommand(token, ref.second, ref.third, ApiHostPlan.railwayStartCommand(tag))
                }.onFailure { step("   ⚠️ " + apiExplain(NodeHostPanel.RAILWAY, it)) }
                step("Deploy…")
                try { ApiHostClient.rwDeploy(token, ref.second, ref.third) } catch (e: Exception) { throw IllegalStateException(apiExplain(NodeHostPanel.RAILWAY, e)) }
                delay(45_000)
                val check = verify(item.kind, item.base, item.uuid, step, 150)
                Outcome(item, check.links, check.ok, check.headline)
            }) { outcome, lines -> showOutcome(outcome, lines) }
        }
    }

    // ------------------------------------------------------------------------------------
    // 📦 save the files: one zip in Download/ (and a share sheet), ready for «Upload → Unarchive»
    // ------------------------------------------------------------------------------------

    fun saveFiles(kind: String, update: Boolean = false) {
        if (kind == NodeHostPanel.KATABUMP) confirmKataBumpRules { writeZip(kind, update) } else writeZip(kind, update)
    }

    private fun writeZip(kind: String, update: Boolean = false) {
        val fileName = "cat-node-panel-$kind" + (if (update) "-update" else "") + ".zip"
        val result = runCatching {
            val bytes = NodeHostPanel.zip(files(kind, update))
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
            }) + "\n\n" + (if (update) t(
                "این zip برای آپلود روی همان پنل است: cat-config.json داخلش نیست تا پنل همان UUID و کانفیگ‌ها را نگه دارد. روی فایل‌های قبلی آپلود و Extract کنید (Overwrite) و سایت را Restart کنید؛ App_Data دست نمی‌خورد.",
                "This zip is for uploading over the same panel: it has no cat-config.json, so the panel keeps its UUID and configs. Upload and Extract it over the old files (Overwrite) and restart the site; App_Data is not touched.",
            ) else if (kind == NodeHostPanel.SMARTERASP) t(
                "داخلش: index.js و package.json (خود پنل)، web.config (تنظیم IIS برای Node و WebSocket)، پوشهٔ node_modules (کتابخانهٔ ws)، پوشهٔ App_Data (جای ذخیرهٔ کاربرها) و cat-config.json (شناسهٔ کانفیگ همین گوشی).",
                "Inside: index.js and package.json (the panel), web.config (IIS settings for Node and WebSocket), a node_modules folder (the ws library), an App_Data folder (where users are stored) and cat-config.json (this phone's config identity).",
            ) else t(
                "داخلش ۴ فایل است: index.js و package.json (خود پنل)، README.md (راهنما) و cat-config.json (شناسهٔ کانفیگ همین گوشی، تا بعداً برنامه آدرس اشتراک را خودش بسازد).",
                "It holds 4 files: index.js and package.json (the panel), README.md (guide) and cat-config.json (this phone's config identity, so the app can build the subscription address itself).",
            )) + "\n\n" + nextStepsAfterZip(kind),
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
        NodeHostPanel.SMARTERASP -> t(
            "حالا: کنترل‌پنل SmarterASP ← سایت (site1) ← File Manager ← همین zip را در پوشهٔ اصلی سایت آپلود و Extract کنید (یا محتوایش را با FTP بگذارید؛ web.config باید کنار index.js باشد) ← Node.js را برای سایت روشن کنید ← آدرس موقت سایت را کپی کنید ← «✍️ پنلم را اضافه کن». بار اول تا ۲ دقیقه طول می‌کشد چون پنل دانلود می‌شود.",
            "Now: SmarterASP control panel → the site (site1) → File Manager → upload this zip to the site's root and Extract it (or put its contents there by FTP; web.config must sit next to index.js) → enable Node.js for the site → copy the site's temporary address → «✍️ Add my panel». The first start takes up to 2 minutes while the panel downloads.",
        )
        NodeHostPanel.BOTHOSTING -> t(
            "حالا: bot-hosting.net ← دیپلوی Node.js ← Files ← Upload ← همین zip ← Unarchive ← Settings ← Startup: فایل اصلی index.js ← Domains ← Activate ← Start ← آدرس را کپی کنید ← «✍️ پنلم را اضافه کن». راه سریع‌تر: «⚡ نصب خودکار».",
            "Now: bot-hosting.net → a Node.js deployment → Files → Upload → this zip → Unarchive → Settings → Startup: entry file index.js → Domains → Activate → Start → copy the address → «✍️ Add my panel». Faster: «⚡ Install automatically».",
        )
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
        NodeHostPanel.BOTHOSTING -> ApiHostPlan.BOTHOSTING_DASHBOARD
        NodeHostPanel.RAILWAY -> ApiHostPlan.RAILWAY_DASHBOARD
        NodeHostPanel.SMARTERASP -> NodeHostPanel.SMARTERASP_CONTROL
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
        when (kind) {
            NodeHostPanel.KATABUMP -> confirmKataBumpRules { askPteroKey() }
            NodeHostPanel.BOTHOSTING -> askBotHostingKey()
            NodeHostPanel.RAILWAY -> askRailwayToken()
            else -> askGithubToken(kind)
        }
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
        box.addView(button(t("🧭 حساب یا سرور ندارم — قدم‌به‌قدم", "🧭 No account or server yet — step by step")) { showStart(NodeHostPanel.KATABUMP) }, lp(6))
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
        runSteps<List<NodeHostApi.PteroServer>>("KataBump", { step ->
            step(t("گرفتن فهرست سرورهای شما…", "Reading your servers…"))
            NodeHostApi.pteroServers(key)
        }) { servers, _ -> handleServers(key, servers) }
    }

    /** The server takes 2–5 minutes after «Create a server»: poll every 15 s for up to 6 minutes. */
    private fun waitForServer(key: String) {
        runSteps<List<NodeHostApi.PteroServer>>(t("⏳ صبر برای سرور", "⏳ Waiting for the server"), { step ->
            var found = emptyList<NodeHostApi.PteroServer>()
            var round = 0
            while (found.isEmpty() && round < 24) {
                round++
                found = NodeHostApi.pteroServers(key)
                if (found.isEmpty()) {
                    step(t("هنوز نیست… ", "Not yet… ") + (round * 15) + t(" ثانیه", " s"))
                    delay(15_000)
                }
            }
            found
        }) { servers, _ -> handleServers(key, servers) }
    }

    private fun handleServers(key: String, servers: List<NodeHostApi.PteroServer>) {
        when {
            servers.isEmpty() -> {
                val box = column()
                box.addView(label(t(
                    "✅ کلید درست است و به حساب شما وصل شدیم، ولی هنوز سروری در این حساب نیست.\n\n" +
                        "یکی از این دو حالت است:\n" +
                        "• هنوز سرور نساخته‌اید → «🧭 قدم‌به‌قدم: ساخت سرور» را بزنید.\n" +
                        "• همین الان ساختید → ۲ تا ۵ دقیقه طول می‌کشد؛ «⏳ صبر کن تا سرورم ساخته شود» را بزنید تا برنامه خودش هر ۱۵ ثانیه نگاه کند.",
                    "✅ The key is right and we reached your account, but it has no server yet.\n\n" +
                        "One of two things:\n" +
                        "• You have not created a server → tap «🧭 Step by step: create the server».\n" +
                        "• You just created it → it takes 2–5 minutes; tap «⏳ Wait until my server is ready» and the app checks every 15 seconds.",
                ), 13.5f, palette.textPrimary, 2))
                box.addView(button(t("🧭 قدم‌به‌قدم: ساخت سرور", "🧭 Step by step: create the server"), filled = true) {
                    showStart(NodeHostPanel.KATABUMP, 1)
                }, lp(12))
                box.addView(button(t("⏳ صبر کن تا سرورم ساخته شود", "⏳ Wait until my server is ready")) { waitForServer(key) }, lp(6))
                box.addView(button(t("🔄 دوباره نگاه کن", "🔄 Check again")) { loadServers(key) }, lp(6))
                box.addView(button(t("🌐 باز کردن داشبورد KataBump", "🌐 Open the KataBump dashboard")) { open(NodeHostPanel.KATABUMP_DASHBOARD) }, lp(6))
                showDialog(
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(t("سروری نیست", "No server yet"))
                        .setView(scroll(box))
                        .setNegativeButton(t("بستن", "Close"), null)
                        .create(),
                )
            }
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
                ), kind = kind)
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
            val why = if (kind == NodeHostPanel.SMARTERASP) t(
                "پنل روی " + base + " جواب نداد. در کنترل‌پنل SmarterASP: ۱) Node.js برای همین سایت روشن باشد. ۲) web.config و index.js در پوشهٔ اصلی سایت باشند (نه داخل یک پوشهٔ دیگر). ۳) فایل App_Data/cat-node….log را باز کنید: «Node … please pick Node 20» یعنی Node قدیمی است (راهنما ← نسخهٔ Node)، «not writable» یعنی به سایت اجازهٔ Write بدهید، «could not start» یعنی سرور به github.com نرسید. بعد سایت را Restart کنید و دوباره تست بزنید.",
                "The panel did not answer at " + base + ". In the SmarterASP control panel: 1) Node.js must be enabled for this site. 2) web.config and index.js must be in the site's root (not inside another folder). 3) Open App_Data/cat-node….log: «Node … please pick Node 20» means Node is too old (guide → Node version), «not writable» means give the site Write permission, «could not start» means the server could not reach github.com. Then restart the site and test again.",
            ) else if (kind == NodeHostPanel.KATABUMP) t(
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
            ) else if (kind == NodeHostPanel.SMARTERASP) t(
                "آدرس موقت سایت را از کنترل‌پنل SmarterASP بچسبانید (همان‌طور که نوشته، با http:// یا https://)، یا دامنهٔ خودتان. اگر https جواب ندهد، برنامه خودش http را هم امتحان می‌کند.",
                "Paste the site's temporary address from the SmarterASP control panel (as written, with http:// or https://), or your own domain. If https does not answer, the app also tries http.",
            ) else t(
                "آدرس برنامه را بنویسید (مثل https://نام.shiprr.dev یا دامنه‌ای که Lucity ساخت).",
                "Type the app address (like https://name.shiprr.dev or the domain Lucity generated).",
            ),
            13f, palette.textPrimary, 2,
        ))
        val address = input(
            when (kind) {
                NodeHostPanel.KATABUMP -> "1.2.3.4:25565"
                NodeHostPanel.SMARTERASP -> "http://name-001-site1.….com"
                else -> "https://….shiprr.dev"
            },
            addressPrefill,
        )
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
            // beta224: IIS starts the panel on the first request and it downloads itself first (up to 2 min).
            val wait = if (kind == NodeHostPanel.SMARTERASP) 120 else 30
            var check = verify(kind, base, uuid, step, wait)
            var where = base
            val plainHttp = NodeHostPanel.smarterAspHttpFallback(kind, base)
            if (!check.answered && plainHttp != null) {
                step(t("https جواب نداد؛ امتحان ", "https did not answer; trying ") + plainHttp)
                val second = verify(kind, plainHttp, uuid, step, 20)
                if (second.answered) { check = second; where = plainHttp }
            }
            if (!check.answered) {
                Outcome(null, emptyList(), false, check.headline, kind = kind)
            } else {
                val pending = store.pending(kind)
                val pass = if (pending != null && pending.first == uuid) pending.second else ""
                val now = System.currentTimeMillis()
                val existing = store.list().firstOrNull { it.kind == kind && it.base == where }
                val saved = existing?.copy(uuid = uuid, trojanPass = pass.ifBlank { existing.trojanPass })
                    ?: SavedNodeHost(kind + "-" + Integer.toHexString(where.hashCode()), kind, NodeHostPanel.hostPort(where).first, where, uuid, "", now, now, pass)
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
            box.addView(button(t("🔐 اطلاعات ورود و لینک‌ها (پنل، اشتراک، ", "🔐 Logins and links (panel, subscription, ") + name(saved.kind) + ")") {
                showInfo(saved)
            }, lp(6))
            box.addView(button(t("🌐 باز کردن پنل (admin / admin — رمز را عوض کنید)", "🌐 Open the panel (admin / admin — change the password)")) {
                open(NodeHostPanel.panelUrl(saved.base))
            }, lp(6))
            box.addView(button(t("📋 کپی لینک اشتراک (برای برنامه‌های دیگر)", "📋 Copy the subscription link (for other apps)")) {
                copy(NodeHostPanel.subUrl(saved.base, saved.uuid))
            }, lp(6))
            if (saved.kind == NodeHostPanel.KATABUMP || saved.kind == NodeHostPanel.BOTHOSTING) {
                remindLater(saved)
                box.addView(label(if (saved.kind == NodeHostPanel.BOTHOSTING) t(
                    "🔔 یادآوری تمدید برای ۳ روز دیگر تنظیم شد (پلن رایگان Bot-Hosting هر ۴ روز تمدید می‌خواهد، وگرنه معلق می‌شود). اگر امروز تمدید نکرده‌اید، همین حالا «🔁 تمدید» را بزنید.",
                    "🔔 A renewal reminder is set for 3 days from now (Bot-Hosting's free plan needs a renewal every 4 days or it is suspended). If you did not renew today, tap «🔁 Renew» now.",
                ) else t(
                    "🔔 یادآوری تمدید برای ۳ روز دیگر تنظیم شد (KataBump بعد از ۴ روز بدون تمدید پاک می‌کند). اگر امروز تمدید نکرده‌اید، همین حالا «🔁 تمدید» را بزنید.",
                    "🔔 A renewal reminder is set for 3 days from now (KataBump deletes after 4 days without renewal). If you did not renew today, tap «🔁 Renew» now.",
                ), top = 10))
                box.addView(button(t("🔁 تمدید", "🔁 Renew")) { showRenew(saved) }, lp(6))
            }
            if (saved.kind == NodeHostPanel.SMARTERASP) {
                // beta225: the 60-day trial clock (the constant existed, the reminder did not).
                runCatching {
                    NodeHostRenewReminder.schedule(activity, saved.id, saved.name,
                        NodeHostPanel.smarterAspReminderDelay(saved.createdAt, System.currentTimeMillis()), NodeHostPanel.SMARTERASP)
                }
                requestNotifications()
                box.addView(label(t(
                    "🔔 ۵ روز قبل از پایان مهلت ۶۰ روزهٔ رایگان SmarterASP یادآوری می‌شود تا پنل را جای دیگری بسازید.",
                    "🔔 You get a reminder 5 days before SmarterASP's 60-day free trial ends, so you can build the panel elsewhere.",
                ), top = 10))
            }
            if (NodeHostPanel.cfFrontCapable(saved.kind, saved.base)) {
                box.addView(button(t("🟧 جلوی Cloudflare (اگر این نام فیلتر شد)", "🟧 Cloudflare front (if this name gets filtered)")) { askCfFront(saved) }, lp(6))
            }
            if (!o.ok) box.addView(button(t("🔎 دوباره تست کن", "🔎 Test again")) { addChecked(saved.kind, saved.base, saved.uuid) }, lp(6))
        } else {
            box.addView(button(t("✍️ آدرس را خودم می‌نویسم", "✍️ I will type the address")) { registerManual(o.kind.ifBlank { NodeHostPanel.KATABUMP }) }, lp(10))
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
        runCatching { NodeHostRenewReminder.schedule(activity, item.id, item.name, wait, item.kind) }
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
        box.addView(button(t("🧭 قدم‌به‌قدم از صفر", "🧭 Step by step from zero")) { showStart(kind) }, lp(6))
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
                "2) Apps ← New app ← یک نام (مثلاً cat" + repoShort.takeLast(5) + ")، مخزن " + repoShort + "، منطقه (Region) ← ساخت. " + shiprrRegionHint() + "\n" +
                "3) وقتی بالا آمد، آدرس https://نام.shiprr.dev است ← «✍️ ساختم — پنلم را اضافه کن».",
            "✅ The repository is ready: " + full + "\n\nNow on Shiprr (3 steps):\n" +
                "1) «🌐 Open Shiprr» → sign up → connect GitHub.\n" +
                "2) Apps → New app → a name (e.g. cat" + repoShort.takeLast(5) + "), repository " + repoShort + ", region → create. " + shiprrRegionHint() + "\n" +
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
    // 🔐 login info and links — everything the owner needs, in one place, with copy buttons
    // ------------------------------------------------------------------------------------

    private data class KataInfo(val username: String, val email: String, val sftp: Pair<String, Int>?)

    private fun infoRow(box: LinearLayout, title: String, value: String, secret: Boolean = false) {
        box.addView(label(title, 11.5f, palette.textSecondary, 10))
        val shown = label(if (secret) "••••••••" else value, 14f, palette.textPrimary, 0).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(!secret)
        }
        box.addView(shown)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val half = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3); marginStart = dp(3) }
        row.addView(button(t("📋 کپی", "📋 Copy")) { copy(value) }, half)
        if (secret) {
            var visible = false
            row.addView(button(t("👁 نمایش", "👁 Show")) {
                visible = !visible
                shown.text = if (visible) value else "••••••••"
                shown.setTextIsSelectable(visible)
            }, half)
        }
        box.addView(row, lp(2))
    }

    private fun section(box: LinearLayout, title: String) {
        box.addView(label(title, 15f, palette.teal, 18))
    }

    private fun showInfo(item: SavedNodeHost) {
        val key = store.pteroKey
        if (item.kind == NodeHostPanel.KATABUMP && key.isNotBlank() && item.serverId.isNotBlank()) {
            runSteps<KataInfo>(t("🔐 گرفتن اطلاعات", "🔐 Reading the details"), { step ->
                step(t("حساب پنل کنترل…", "Control-panel account…"))
                val account = runCatching { NodeHostApi.pteroAccount(key) }.getOrDefault(Pair("", ""))
                val sftp = runCatching { NodeHostApi.pteroSftp(key, item.serverId) }.getOrNull()
                KataInfo(account.first, account.second, sftp)
            }) { info, _ -> renderInfo(item, info) }
        } else {
            renderInfo(item, null)
        }
    }

    private fun renderInfo(item: SavedNodeHost, kata: KataInfo?) {
        val box = column()
        box.addView(label(t(
            "همهٔ اطلاعات ورود و لینک‌های این پنل. فقط روی همین گوشی است؛ برای کسی نفرستید.",
            "All logins and links of this panel. It stays on this phone; do not send it to anyone.",
        ), 12.5f, palette.textSecondary, 2))

        section(box, t("🐱 پنل Cat (ساخت کاربر، آمار، تنظیمات)", "🐱 Cat panel (users, stats, settings)"))
        infoRow(box, t("آدرس پنل — در مرورگر باز کنید", "Panel address — open it in a browser"), NodeHostPanel.panelUrl(item.base))
        infoRow(box, t("نام کاربری", "Username"), NodeHostPanel.PANEL_USER)
        val panelPass = store.panelPassword(item.id).ifBlank { NodeHostPanel.PANEL_DEFAULT_PASSWORD }
        infoRow(box, t("رمز", "Password"), panelPass, secret = true)
        box.addView(label(
            if (panelPass == NodeHostPanel.PANEL_DEFAULT_PASSWORD) t(
                "⚠️ رمز هنوز پیش‌فرض (admin) است. در پنل ← تنظیمات ← رمز را عوض کنید، بعد «✏️ رمز تازهٔ پنل» را بزنید تا اینجا یادتان بماند.",
                "⚠️ The password is still the default (admin). In the panel → Settings → change it, then tap «✏️ New panel password» so it is remembered here.",
            ) else t("✅ رمز عوض شده و اینجا ذخیره است.", "✅ The password was changed and is saved here."),
            12f, palette.textSecondary, 4,
        ))
        box.addView(button(t("✏️ رمز تازهٔ پنل را ذخیره کن", "✏️ Save the new panel password")) { askPanelPassword(item) }, lp(6))

        section(box, t("🔗 اشتراک و کانفیگ‌ها", "🔗 Subscription and configs"))
        infoRow(box, t("لینک اشتراک — برای v2rayNG / Hiddify / Streisand", "Subscription link — for v2rayNG / Hiddify / Streisand"), NodeHostPanel.subUrl(item.base, item.uuid))
        box.addView(label(t(
            "در خود Cat Client لینک لازم نیست: «➕ گرفتن و اضافه کردن کانفیگ‌ها» کانفیگ‌ها را مستقیم اضافه می‌کند.",
            "Cat Client itself does not need the link: «➕ Get and add the configs» adds the configs directly.",
        ), 12f, palette.textSecondary, 4))
        infoRow(box, "UUID (VLESS)", item.uuid, secret = true)
        if (item.trojanPass.isNotBlank()) infoRow(box, t("رمز Trojan", "Trojan password"), item.trojanPass, secret = true)

        if (item.kind == NodeHostPanel.KATABUMP) {
            section(box, t("🟩 حساب KataBump", "🟩 KataBump account"))
            box.addView(label(t(
                "KataBump دو ورود جدا دارد:\n" +
                    "1) داشبورد (dashboard.katabump.com): با همان ایمیل و رمزی که موقع ثبت‌نام خودتان ساختید. تمدید از اینجاست.\n" +
                    "2) پنل کنترل (control.katabump.com): فایل‌ها و Console. رمزش را داشبورد می‌دهد: See more ← Access server ← Current password.",
                "KataBump has two separate logins:\n" +
                    "1) Dashboard (dashboard.katabump.com): the e-mail and password you chose at sign-up. Renewal is here.\n" +
                    "2) Control panel (control.katabump.com): files and Console. The dashboard gives its password: See more → Access server → Current password.",
            ), 12.5f, palette.textPrimary, 4))
            val username = store.kataIdentifier.ifBlank { kata?.username.orEmpty() }
            if (kata != null && kata.email.isNotBlank()) infoRow(box, t("ایمیل حساب", "Account e-mail"), kata.email)
            if (username.isNotBlank()) infoRow(box, t("نام کاربری پنل کنترل", "Control-panel username"), username)
            if (store.kataPassword.isNotBlank()) infoRow(box, t("رمز پنل کنترل", "Control-panel password"), store.kataPassword, secret = true)
            else box.addView(label(t(
                "رمز پنل کنترل اینجا ذخیره نشده (KataBump آن را به برنامه نمی‌دهد). اگر می‌خواهید همیشه اینجا باشد: داشبورد ← See more ← Access server ← کپی ← «💾 ذخیرهٔ ورود پنل کنترل».",
                "The control-panel password is not saved here (KataBump does not give it to apps). To keep it here: dashboard → See more → Access server → copy → «💾 Save the control-panel login».",
            ), 12f, palette.textSecondary, 6))
            box.addView(button(t("💾 ذخیرهٔ ورود پنل کنترل در همین گوشی", "💾 Save the control-panel login on this phone")) { askKataLogin(item, kata) }, lp(6))
            if (item.serverId.isNotBlank()) infoRow(box, t("شناسهٔ سرور", "Server ID"), item.serverId)
            val sftpUser = NodeHostPanel.sftpUser(username, item.serverId)
            if (kata?.sftp != null && sftpUser != null) {
                infoRow(box, t("SFTP (برای FileZilla) — آدرس", "SFTP (for FileZilla) — address"), "sftp://" + kata.sftp.first + ":" + kata.sftp.second)
                infoRow(box, t("SFTP — نام کاربری (رمز = رمز پنل کنترل)", "SFTP — username (password = control-panel password)"), sftpUser)
            }
            box.addView(button(t("🌐 داشبورد KataBump", "🌐 KataBump dashboard"), filled = true) { open(NodeHostPanel.KATABUMP_DASHBOARD) }, lp(12))
            box.addView(button(t("🌐 پنل کنترل KataBump", "🌐 KataBump control panel")) {
                open(if (item.serverId.isNotBlank()) NodeHostPanel.KATABUMP_CONTROL + "/server/" + item.serverId else NodeHostPanel.KATABUMP_CONTROL_LOGIN)
            }, lp(6))
        } else {
            section(box, "🟦 " + name(item.kind))
            val repo = store.lastRepo(item.kind)
            // beta225: only Lucity / Shiprr log in with GitHub; the others have their own account.
            box.addView(label(when (item.kind) {
                NodeHostPanel.BOTHOSTING -> t(
                    "ورود به Bot-Hosting با حساب خود bot-hosting.net است؛ برنامه فقط کلید API (bhk_…) را روی همین گوشی نگه می‌دارد. تمدید هر ۴ روز از داشبورد.",
                    "You log in to Bot-Hosting with your own bot-hosting.net account; the app only keeps the API key (bhk_…) on this phone. Renew every 4 days on the dashboard.",
                )
                NodeHostPanel.RAILWAY -> t(
                    "ورود به Railway با حساب خود railway.com است؛ برنامه فقط توکن حساب را روی همین گوشی نگه می‌دارد.",
                    "You log in to Railway with your own railway.com account; the app only keeps the account token on this phone.",
                )
                NodeHostPanel.SMARTERASP -> t(
                    "ورود به کنترل‌پنل SmarterASP با ایمیل و رمز ثبت‌نام خودتان است. مهلت رایگان ۶۰ روز است.",
                    "You log in to the SmarterASP control panel with your own sign-up e-mail and password. The free trial is 60 days.",
                )
                else -> t(
                    "ورود به " + name(item.kind) + " با همان حساب GitHub است (Login with GitHub) — رمز جدایی ندارد.",
                    "You log in to " + name(item.kind) + " with your GitHub account (Login with GitHub) — no separate password.",
                )
            }, 12.5f, palette.textPrimary, 4))
            if (repo.isNotBlank() && (item.kind == NodeHostPanel.LUCITY || item.kind == NodeHostPanel.SHIPRR)) {
                infoRow(box, t("مخزن خصوصی GitHub", "Private GitHub repository"), "https://github.com/" + repo)
            }
            box.addView(button(t("🌐 باز کردن ", "🌐 Open ") + name(item.kind), filled = true) { open(siteFor(item.kind)) }, lp(12))
            if (NodeHostPanel.cfFrontCapable(item.kind, item.base)) {
                box.addView(button(t("🟧 جلوی Cloudflare برای این پنل", "🟧 Cloudflare front for this panel")) { askCfFront(item) }, lp(6))
            }
        }
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("🔐 اطلاعات ورود و لینک‌ها", "🔐 Logins and links"))
                .setView(scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        )
    }

    // ------------------------------------------------------------------------------------
    // beta225 🟧 Cloudflare front for a Node host with its own HTTPS name (Railway, Bot-Hosting, Lucity, Shiprr,
    // SmarterASP over https): the same assets/vercel/cf-front.js Worker the Vercel page builds, pointed at this
    // host only. Phone → Cloudflare clean IP (Host = SNI = the Worker's name) → Worker → https://<host>; sites
    // still see the host's own IP. KataBump (plain IP:port) cannot sit behind it — there «🔗 Link panels» in the
    // Cloudflare panel does the same job.
    // ------------------------------------------------------------------------------------

    private fun askCfFront(item: SavedNodeHost) {
        val origin = NodeHostPanel.hostPort(item.base).first.lowercase()
        if (!NodeHostPanel.cfFrontCapable(item.kind, item.base) || !VercelCfFront.isHostname(origin)) {
            toast(t("این پنل نام HTTPS روی ۴۴۳ ندارد؛ جلوی Cloudflare فقط برای چنین پنلی ساخته می‌شود.", "This panel has no HTTPS name on 443; a Cloudflare front only works for such a panel."))
            return
        }
        val cfStore = VercelRelayStore(activity)
        val box = column()
        box.addView(label(t(
            "اگر «$origin» روی خط شما فیلتر است ولی Cloudflare باز است، یک Worker کوچک روی حساب Cloudflare خودتان جلوی همین پنل می‌گذاریم:\n" +
                "• گوشی با آی‌پی تمیز Cloudflare و نام workers.dev وصل می‌شود؛ سایت‌ها هنوز آی‌پی همین هاست را می‌بینند.\n" +
                "• Worker فقط به همین یک آدرس وصل می‌شود (پروکسی باز نیست)، لاگ و حافظه ندارد.\n" +
                "• توکن Cloudflare لازم است (Edit Cloudflare Workers) — فقط روی همین گوشی می‌ماند.",
            "If «$origin» is filtered on your line but Cloudflare is open, a small Worker on your own Cloudflare account goes in front of this panel:\n" +
                "• the phone connects with a Cloudflare clean IP and a workers.dev name; sites still see this host's IP.\n" +
                "• the Worker only ever talks to this one address (not an open proxy), with no logs or storage.\n" +
                "• it needs a Cloudflare token (Edit Cloudflare Workers) — kept on this phone only.",
        ), 13f, palette.textPrimary, 2))
        val tokenInput = input(t("توکن Cloudflare را اینجا بچسبانید", "Paste the Cloudflare token here"), cfStore.cfToken, password = true)
        box.addView(tokenInput, lp(10))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🟧 جلوی Cloudflare برای ", "🟧 Cloudflare front for ") + name(item.kind))
            .setView(scroll(box))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(t("ادامه", "Continue"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = tokenInput.text.toString().trim()
                if (token.length < 20) { tokenInput.error = t("توکن کامل نیست", "The token looks incomplete"); return@setOnClickListener }
                dialog.dismiss()
                confirmCfFront(item, origin, token)
            }
        }
        showDialog(dialog)
    }

    private fun confirmCfFront(item: SavedNodeHost, origin: String, token: String) {
        scope.launch {
            val perms = runCatching { CloudflareWorker.verifyToken(token) }
            val p = perms.getOrNull()
            if (p == null || !p.valid || p.accountId.isNullOrBlank()) {
                val why = perms.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                    ?: (t("توکن معتبر نیست یا دسترسی کم دارد: ", "The token is not valid or lacks access: ") + p?.missingScopes?.joinToString().orEmpty())
                message(t("❌ توکن Cloudflare", "❌ Cloudflare token"), why + t("\n\nاگر api.cloudflare.com روی خط شما باز نیست، اول با یک کانفیگ دیگر وصل شوید.", "\n\nIf api.cloudflare.com is blocked on your line, connect with another config first."))
                return@launch
            }
            VercelRelayStore(activity).cfToken = token
            val accountId = p.accountId.orEmpty()
            val workerName = VercelCfFront.randomWorkerName()
            // Installer confirmation: exactly what is created / changed, before anything runs.
            showDialog(
                MaterialAlertDialogBuilder(activity)
                    .setTitle(t("🟧 ساخت جلوی Cloudflare؟", "🟧 Build the Cloudflare front?"))
                    .setMessage(t(
                        "این کارها انجام می‌شود:\n• یک Worker تازه به نام «$workerName» روی حساب Cloudflare «${p.accountName.orEmpty()}» (هیچ Worker موجودی عوض نمی‌شود).\n" +
                            "• فقط به «$origin» وصل می‌شود.\n• در پنل Cat همین هاست، نام جلو ذخیره می‌شود تا خطوط «🟧» اول اشتراک بیایند (UUID، رمزها و کاربرها عوض نمی‌شوند).",
                        "This will:\n• create a new Worker “$workerName” on the Cloudflare account “${p.accountName.orEmpty()}” (no existing Worker is changed);\n" +
                            "• point it only at “$origin”;\n• save the front's name in this host's Cat panel so “🟧” lines lead its subscription (UUID, passwords and users stay the same).",
                    ))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(t("بساز", "Build")) { _, _ -> buildCfFront(item, origin, token, accountId, workerName) }
                    .create(),
            )
        }
    }

    private fun buildCfFront(item: SavedNodeHost, origin: String, token: String, accountId: String, workerName: String) {
        runSteps<Pair<CloudflareWorker.VercelFrontResult, List<String>>>("🟧 Cloudflare front", { step ->
            step(t("1/3 ساخت Worker روی Cloudflare…", "1/3 Creating the Worker on Cloudflare…"))
            val r = CloudflareWorker.deployVercelFront(activity, token, accountId, origin, workerName)
            step((if (r.verified) "   ✓ " else "   ⚠️ ") + r.host)
            step(t("2/3 ذخیرهٔ نام جلو در پنل Cat…", "2/3 Saving the front's name in the Cat panel…"))
            val pass = store.panelPassword(item.id).ifBlank { NodeHostPanel.PANEL_DEFAULT_PASSWORD }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    CloudflareWorker.restorePanelState(item.base, "{\"vercelCfFront\":\"" + r.host + "\"}", "[]", username = NodeHostPanel.PANEL_USER, password = pass).settingsApplied
                }.getOrDefault(false)
            }
            step(if (saved) "   ✓ vercelCfFront" else t("   ⚠️ ورود به پنل نشد (رمز پنل؟) — خطوط فقط در برنامه اضافه می‌شوند", "   ⚠️ Could not sign in to the panel (password?) — the lines are only added in the app"))
            step(t("3/3 گرفتن کانفیگ‌های «🟧»…", "3/3 Fetching the “🟧” configs…"))
            val sub = withContext(Dispatchers.IO) { runCatching { NodeHostApi.panelGet(item.base, "/sub/" + item.uuid) }.getOrNull() }
            val all = if (sub != null && sub.first == 200) NodeHostPanel.decodeSub(sub.second) else emptyList()
            val front = all.filter { NodeHostPanel.linkParam(it, "sni") == r.host }.ifEmpty { VercelCfFront.links(all, r.host) }
            step("   ✓ " + front.size)
            Pair(r, front)
        }) { res, _ ->
            val (r, links) = res
            val box = column()
            box.addView(label(
                (if (r.verified) t("✅ جلوی Cloudflare ساخته شد و جواب داد: ", "✅ The Cloudflare front was built and answers: ")
                else t("⚠️ Worker ساخته شد ولی هنوز جواب نداد (اسم تازهٔ workers.dev گاهی چند دقیقه طول می‌کشد): ", "⚠️ The Worker was built but does not answer yet (a new workers.dev name can take a few minutes): ")) + r.host,
                13f, if (r.verified) palette.teal else palette.amber, 2))
            box.addView(label(t(
                "• «➕ افزودن» را بزنید و کانفیگ‌های «🟧» را امتحان کنید.\n• اگر خود workers.dev روی خط شما بسته است، اسکنر آی‌پی تمیز Cloudflare برنامه را روی همین کانفیگ‌ها بزنید؛ آی‌پی سالم با همان پورتی که تست شد جایگزین می‌شود.\n• پنل از این آدرس هم باز می‌شود: https://${r.host}/panel/",
                "• Tap «➕ Add» and try the “🟧” configs.\n• If workers.dev itself is blocked on your line, run the app's Cloudflare clean-IP scanner on these configs; a working IP replaces the address with the port it was tested on.\n• The panel also opens at: https://${r.host}/panel/",
            ), 12.5f, palette.textSecondary, 8))
            if (links.isNotEmpty()) {
                box.addView(button(t("➕ افزودن ", "➕ Add ") + links.size + t(" کانفیگ 🟧", " 🟧 configs"), filled = true) {
                    addSubscription(links.joinToString("\n"), "Cat · " + name(item.kind) + " · 🟧 CF")
                }, lp(10))
            }
            showDialog(
                MaterialAlertDialogBuilder(activity)
                    .setTitle("🟧 Cloudflare front")
                    .setView(scroll(box))
                    .setPositiveButton(android.R.string.ok, null)
                    .create(),
            )
        }
    }

    private fun askPanelPassword(item: SavedNodeHost) {
        val box = column()
        box.addView(label(t(
            "رمزی که در پنل Cat (تنظیمات) گذاشتید را بنویسید. فقط روی همین گوشی ذخیره می‌شود تا یادتان نرود؛ رمز خود پنل را عوض نمی‌کند.",
            "Type the password you set in the Cat panel (Settings). It is only saved on this phone so you do not forget it; it does not change the panel itself.",
        ), 13f, palette.textPrimary, 2))
        val field = input(t("رمز تازهٔ پنل", "New panel password"), store.panelPassword(item.id), password = true)
        box.addView(field, lp(8))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("✏️ رمز پنل", "✏️ Panel password"))
                .setView(scroll(box))
                .setPositiveButton(t("ذخیره", "Save")) { _, _ ->
                    store.setPanelPassword(item.id, field.text.toString())
                    toast(t("ذخیره شد", "Saved"))
                    renderInfo(item, null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun askKataLogin(item: SavedNodeHost, kata: KataInfo?) {
        val box = column()
        box.addView(label(t(
            "در داشبورد KataBump ← کنار سرور See more ← Access server ← Identifier و Current password را کپی کنید و اینجا بچسبانید.",
            "On the KataBump dashboard → next to the server See more → Access server → copy the Identifier and the Current password and paste them here.",
        ), 13f, palette.textPrimary, 2))
        val user = input("Identifier", store.kataIdentifier.ifBlank { kata?.username.orEmpty() })
        val pass = input(t("رمز (Current password)", "Password (Current password)"), store.kataPassword, password = true)
        box.addView(user, lp(8))
        box.addView(button(t("📋 چسباندن نام کاربری", "📋 Paste the username")) { user.setText(clipboardText().trim()) }, lp(4))
        box.addView(pass, lp(8))
        box.addView(button(t("📋 چسباندن رمز", "📋 Paste the password")) { pass.setText(clipboardText().trim()) }, lp(4))
        box.addView(button(t("🌐 باز کردن داشبورد", "🌐 Open the dashboard")) { open(NodeHostPanel.KATABUMP_DASHBOARD) }, lp(4))
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(t("💾 ورود پنل کنترل", "💾 Control-panel login"))
                .setView(scroll(box))
                .setPositiveButton(t("ذخیره", "Save")) { _, _ ->
                    store.kataIdentifier = user.text.toString()
                    store.kataPassword = pass.text.toString()
                    toast(t("ذخیره شد", "Saved"))
                    renderInfo(item, kata)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    // ------------------------------------------------------------------------------------
    // 🔁 renew (KataBump) / update (Lucity, Shiprr)
    // ------------------------------------------------------------------------------------

    fun renew(kind: String) {
        if (kind == NodeHostPanel.RAILWAY) {
            val items = store.list().filter { it.kind == kind }
            when {
                items.isEmpty() -> autoInstall(kind)
                items.size == 1 -> updateApiHost(items.first())
                else -> pick(items) { updateApiHost(it) }
            }
            return
        }
        if (kind != NodeHostPanel.KATABUMP && kind != NodeHostPanel.BOTHOSTING) {
            redeploy(kind)
            return
        }
        val items = store.list().filter { it.kind == kind }
        if (kind == NodeHostPanel.BOTHOSTING && items.isEmpty()) {
            message2(
                t("🔁 تمدید Bot-Hosting", "🔁 Renew Bot-Hosting"),
                t(
                    "هنوز پنلی از Bot-Hosting اینجا ثبت نشده. داشبورد را باز کنید ← دیپلوی ← Renew (پلن رایگان هر ۴ روز).",
                    "No Bot-Hosting panel is registered here yet. Open the dashboard → the deployment → Renew (free plan: every 4 days).",
                ),
                t("باز کردن داشبورد", "Open the dashboard"),
            ) { open(ApiHostPlan.BOTHOSTING_DASHBOARD) }
            return
        }
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
        // KataBump opens Renew in the last 2 days; Bot-Hosting 3 days before the due date.
        val window = if (item.kind == NodeHostPanel.BOTHOSTING) 3.0 else 2.0
        return when {
            left == null -> t("⏳ زمان تمدید معلوم نیست", "⏳ Renewal time unknown")
            left <= 0 -> t("⛔ مهلت گذشته — فوراً تمدید کنید", "⛔ Overdue — renew now")
            left > window -> t("⏳ ", "⏳ ") + String.format(java.util.Locale.US, "%.1f", left) + t(" روز مانده — هنوز زود است؛ دکمهٔ Renew از ", " days left — too early; Renew opens in ") +
                String.format(java.util.Locale.US, "%.1f", left - window) + t(" روز دیگر فعال می‌شود", " days")
            else -> t("⏳ ", "⏳ ") + String.format(java.util.Locale.US, "%.1f", left) + t(" روز تا تمدید", " days until renewal")
        }
    }

    private fun showRenew(item: SavedNodeHost) {
        val box = column()
        box.addView(label(item.name + "\n" + countdown(item), 14f, palette.textPrimary, 2))
        if (item.kind == NodeHostPanel.BOTHOSTING) box.addView(label(t(
            "Bot-Hosting پلن رایگان را هر ۴ روز تمدید می‌خواهد (دکمهٔ Renew از ۳ روز مانده به موعد فعال است) و API آن تمدید ندارد، پس کار شما ۱۰ ثانیه است:\n" +
                "1) «🔁 باز کردن صفحهٔ تمدید» ← دیپلوی cat-panel ← Renew.\n2) برگردید ← «✅ تمدید کردم». برنامه ۳ روز بعد دوباره یادآوری می‌کند.\n\nاگر فراموش شود دیپلوی معلق می‌شود و ۳ روز بعد پاک می‌شود (۳۰ روز از Backups قابل برگشت است).",
            "Bot-Hosting's free plan needs a renewal every 4 days (the Renew button unlocks 3 days before the due date) and its API has no renew call, so it takes you 10 seconds:\n" +
                "1) «🔁 Open the renew page» → the cat-panel deployment → Renew.\n2) Come back → «✅ I renewed». The app reminds you again in 3 days.\n\nIf forgotten, the deployment is suspended, then deleted 3 days later (restorable from Backups for 30 days).",
        ), top = 10)) else box.addView(label(t(
            "تمدید KataBump کپچا دارد («من ربات نیستم»)، پس هیچ برنامه‌ای نمی‌تواند بی‌خطر به‌جای شما بزند. کار شما ۲۰ ثانیه است:\n" +
                "1) «🔁 باز کردن صفحهٔ تمدید» ← وارد شوید ← سرورتان ← نوار «Renewal required» ← Renew now ← کپچا ← تأیید.\n2) برگردید ← «✅ تمدید کردم». برنامه ۳ روز بعد دوباره یادآوری می‌کند.\n\nتمدید فقط در ۲ روز آخر ممکن است؛ زودتر دکمه‌اش نمی‌آید. اگر فراموش شود، سرور یک روز معلق و بعد پاک می‌شود.",
            "KataBump's renewal has a captcha («I am not a robot»), so no app can safely press it for you. It takes you 20 seconds:\n" +
                "1) «🔁 Open the renew page» → log in → your server → the «Renewal required» banner → Renew now → captcha → confirm.\n2) Come back → «✅ I renewed». The app reminds you again in 3 days.\n\nRenewal is only possible in the last 2 days; earlier the button does not show. If forgotten, the server is suspended for a day, then deleted.",
        ), top = 10))
        box.addView(button(t("🔁 باز کردن صفحهٔ تمدید", "🔁 Open the renew page"), filled = true) {
            open(if (item.kind == NodeHostPanel.BOTHOSTING) ApiHostPlan.BOTHOSTING_DASHBOARD else NodeHostPanel.KATABUMP_DASHBOARD)
        }, lp(10))
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
        if (item.kind == NodeHostPanel.KATABUMP || item.kind == NodeHostPanel.BOTHOSTING) info.append('\n').append(countdown(item))
        box.addView(label(info.toString(), 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) })
        box.addView(button(t("➕ گرفتن و اضافه کردن کانفیگ‌ها", "➕ Get and add the configs"), filled = true) {
            addChecked(item.kind, item.base, item.uuid)
        }, lp(10))
        box.addView(button(t("🔐 اطلاعات ورود و لینک‌ها", "🔐 Logins and links")) { showInfo(item) }, lp(6))
        box.addView(button(t("🌐 باز کردن پنل", "🌐 Open the panel")) { open(NodeHostPanel.panelUrl(item.base)) }, lp(6))
        box.addView(button(t("📋 کپی لینک اشتراک", "📋 Copy the subscription link")) { copy(NodeHostPanel.subUrl(item.base, item.uuid)) }, lp(6))
        if (item.kind == NodeHostPanel.KATABUMP) {
            box.addView(button(t("🔁 تمدید", "🔁 Renew")) { showRenew(item) }, lp(6))
            box.addView(button(t("🌐 دامنه + TLS (Cloudflare)", "🌐 Domain + TLS (Cloudflare)")) { showDomainTls(item) }, lp(6))
        } else if (NodeHostPanel.isApiHost(item.kind)) {
            if (item.kind == NodeHostPanel.BOTHOSTING) box.addView(button(t("🔁 تمدید", "🔁 Renew")) { showRenew(item) }, lp(6))
            box.addView(button(t("🔁 به‌روزرسانی (نصب دوباره با API)", "🔁 Update (reinstall through the API)")) { updateApiHost(item) }, lp(6))
        } else if (NodeHostPanel.isUploadOnlyHost(item.kind)) {
            // beta224 SmarterASP: the launcher fetches each new panel release itself on restart; an update of the
            // launcher is just a new zip uploaded over the old files (App_Data keeps users and settings).
            box.addView(button(t("📦 فایل‌های تازه برای آپلود دوباره", "📦 Fresh files to upload again")) { saveFiles(item.kind, update = true) }, lp(6))
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
                "1) دامنهٔ رایگان: داشبورد KataBump ← My Domains ← Get my first domain ← یک اسم + پسوند .kdns.fr ← تیک شرایط ← Order the domain.\n" +
                "   Cloudflare (رایگان) ← Add a site ← همان دامنه ← پلن Free ← دو Nameserver که می‌دهد را کپی کنید ← در KataBump صفحهٔ همان دامنه ← Nameservers ← بچسبانید ← چند دقیقه تا چند ساعت صبر تا Cloudflare بگوید Active. (دامنهٔ خودتان را هم می‌شود به همین شکل استفاده کرد.)\n" +
                "2) Cloudflare ← DNS ← Add record: نوع A، نام مثلاً cat، IPv4 = " + host + "، ابر نارنجی روشن.\n" +
                "3) Rules ← Origin Rules ← Create: Hostname برابر cat.دامنهٔ‌شما ← Destination Port = " + port + ".\n" +
                "4) Rules ← Configuration Rules ← همان Hostname ← SSL = Flexible (سرور http ساده است).\n" +
                "5) برگردید ← «✍️ دامنه را اضافه کن» ← https://cat.دامنهٔ‌شما. پنل Cloudflare را می‌شناسد و کانفیگ‌های TLS می‌دهد.",
            "Why: an IP:port config has no TLS and an unusual port; it is easier to block. With Cloudflare in front you get a TLS config on 443 and the full Cloudflare set (with clean IPs).\n\n" +
                "1) Free domain: KataBump dashboard → My Domains → Get my first domain → a name + the .kdns.fr suffix → tick the terms → Order the domain.\n" +
                "   Cloudflare (free) → Add a site → that domain → Free plan → copy the two nameservers it gives → on KataBump, that domain's page → Nameservers → paste → wait minutes to hours until Cloudflare says Active. (Your own domain works the same way.)\n" +
                "2) Cloudflare → DNS → Add record: type A, name e.g. cat, IPv4 = " + host + ", orange cloud on.\n" +
                "3) Rules → Origin Rules → Create: Hostname equals cat.yourdomain → Destination Port = " + port + ".\n" +
                "4) Rules → Configuration Rules → same Hostname → SSL = Flexible (the server speaks plain http).\n" +
                "5) Come back → «✍️ Add the domain» → https://cat.yourdomain. The panel recognises Cloudflare and gives TLS configs.",
        ), 13f, palette.textPrimary, 2).apply { setTextIsSelectable(true) })
        box.addView(button(t("🆓 دامنهٔ رایگان kdns.fr (داشبورد KataBump)", "🆓 Free kdns.fr domain (KataBump dashboard)"), filled = true) { open(NodeHostPanel.KATABUMP_DASHBOARD) }, lp(10))
        box.addView(button(t("🟧 باز کردن Cloudflare", "🟧 Open Cloudflare")) { open(NodeHostPanel.CLOUDFLARE_DASH) }, lp(6))
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
