package com.cat.client

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One place for the "everyday" tools that were missing: import a config file (SAF), backup /
 * restore subscriptions, app lock, automatic subscription refresh + quota/expiry alerts, the
 * real-engine status, and the WhiteDNS-style scanner toolkit. Kept outside MainActivity so the
 * (already huge) activity only forwards a few lifecycle calls.
 */
class AppToolsController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val isFarsi: () -> Boolean,
    private val subscriptions: () -> UserSubscriptionManager,
    private val addSubscription: (content: String, name: String) -> Unit,
    private val sendRangesToScanner: (String) -> Unit,
    private val afterSubscriptionsChanged: () -> Unit,
    private val showDialog: (AlertDialog) -> Unit,
) {
    private val palette by lazy { CatClientDesignTokens.forContext(activity) }
    private var lockCover: View? = null
    private var pendingBackupJson: String? = null

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun t(fa: String, en: String) = if (isFarsi()) fa else en
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------------------------
    // Activity plumbing
    // ------------------------------------------------------------------------------------

    fun onCreate(coldStart: Boolean) {
        if (coldStart && AppLock.isEnabled(activity)) AppLock.markBackground(activity, 0L)
        SubscriptionAutoRefresh.sync(activity)
    }

    fun onStart(root: ViewGroup?) {
        if (root != null && AppLock.shouldLock(activity)) showLock(root)
    }

    fun onStop() {
        if (AppLock.isEnabled(activity) && lockCover == null) AppLock.markBackground(activity)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_IMPORT_FILE -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) importFromUri(uri)
            }
            REQ_BACKUP_CREATE -> {
                val uri = data?.data
                val json = pendingBackupJson
                pendingBackupJson = null
                if (resultCode == Activity.RESULT_OK && uri != null && json != null) writeBackup(uri, json)
            }
            REQ_RESTORE_OPEN -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) restoreBackup(uri)
            }
            REQ_DEVICE_CREDENTIAL -> {
                if (resultCode == Activity.RESULT_OK) unlock()
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------------------------
    // File import / backup / restore (Storage Access Framework — no storage permission)
    // ------------------------------------------------------------------------------------

    fun openImportFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/*", "application/json", "application/x-yaml", "application/yaml", "application/toml", "application/octet-stream"),
            )
        }
        runCatching { activity.startActivityForResult(intent, REQ_IMPORT_FILE) }
            .onFailure { toast(t("انتخابگر فایل در این گوشی در دسترس نیست", "No file picker available")) }
    }

    private fun importFromUri(uri: Uri) {
        val incoming = IncomingConfigIntents.fromFile(activity, uri)
        if (incoming == null) {
            toast(t("فایل خوانده نشد یا بیش از ۲ مگابایت است", "Could not read the file (or it is larger than 2 MB)"))
            return
        }
        val backup = SubscriptionBackup.parse(incoming.content)
        if (backup != null) {
            confirmRestore(backup)
            return
        }
        addSubscription(incoming.content, incoming.name)
    }

    fun startBackup() {
        val items = subscriptions().list()
        if (items.isEmpty()) {
            toast(t("هنوز اشتراکی برای پشتیبان‌گیری ندارید", "No subscriptions to back up yet"))
            return
        }
        pendingBackupJson = SubscriptionBackup.export(items)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "cat-client-backup-$stamp.json")
        }
        runCatching { activity.startActivityForResult(intent, REQ_BACKUP_CREATE) }
            .onFailure { toast(t("ذخیره فایل در این گوشی در دسترس نیست", "Saving files is not available")) }
    }

    private fun writeBackup(uri: Uri, json: String) {
        val ok = runCatching {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        toast(
            if (ok) t("✅ پشتیبان ذخیره شد. این فایل شامل لینک اشتراک‌هاست؛ آن را جای امن نگه دارید.", "✅ Backup saved. It contains your subscription links — keep it private.")
            else t("ذخیرهٔ پشتیبان ناموفق بود", "Saving the backup failed"),
        )
    }

    fun startRestore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        runCatching { activity.startActivityForResult(intent, REQ_RESTORE_OPEN) }
            .onFailure { toast(t("انتخابگر فایل در دسترس نیست", "No file picker available")) }
    }

    private fun restoreBackup(uri: Uri) {
        val text = IncomingConfigIntents.readText(activity, uri)
        val entries = text?.let(SubscriptionBackup::parse)
        if (entries.isNullOrEmpty()) {
            toast(t("این فایل پشتیبان Cat Client نیست", "This is not a Cat Client backup file"))
            return
        }
        confirmRestore(entries)
    }

    private fun confirmRestore(entries: List<SubscriptionBackup.Entry>) {
        val existing = subscriptions().list().map { it.input.trim() }.toSet()
        val fresh = entries.filter { it.input.trim() !in existing }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("♻️ بازگردانی پشتیبان", "♻️ Restore backup"))
            .setMessage(
                t(
                    "${entries.size} اشتراک در فایل است؛ ${fresh.size} مورد جدید اضافه می‌شود و اشتراک‌های فعلی شما دست نمی‌خورند (چیزی پاک نمی‌شود).",
                    "${entries.size} subscriptions in the file; ${fresh.size} new ones will be added. Existing ones are kept (nothing is deleted).",
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(t("بازگردانی", "Restore")) { _, _ -> runRestore(fresh) }
            .create()
        showDialog(dialog)
    }

    private fun runRestore(entries: List<SubscriptionBackup.Entry>) {
        if (entries.isEmpty()) {
            toast(t("همهٔ اشتراک‌های این پشتیبان از قبل موجودند", "All subscriptions in this backup already exist"))
            return
        }
        scope.launch {
            var ok = 0
            val failed = ArrayList<String>()
            entries.forEach { entry ->
                val result = withContext(Dispatchers.IO) { runCatching { subscriptions().add(entry.name, entry.input) } }
                if (result.isSuccess) ok++ else failed += entry.name
            }
            afterSubscriptionsChanged()
            toast(
                t("✅ $ok اشتراک بازگردانی شد", "✅ Restored $ok subscriptions") +
                    if (failed.isNotEmpty()) t(" · ناموفق: ${failed.joinToString("، ")}", " · failed: ${failed.joinToString(", ")}") else "",
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // App lock
    // ------------------------------------------------------------------------------------

    private fun showLock(root: ViewGroup) {
        if (lockCover != null) return
        val cover = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(palette.background)
            isClickable = true
            isFocusable = true
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val title = TextView(activity).apply {
            text = t("🔒 Cat Client قفل است", "🔒 Cat Client is locked")
            textSize = 20f
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER
        }
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
            text = t("رمز عددی (PIN) را وارد کنید", "Enter your PIN")
        }
        val pin = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            hint = "••••"
        }
        val unlockButton = MaterialButton(activity).apply {
            text = t("باز کردن", "Unlock")
            isAllCaps = false
            setOnClickListener {
                val wait = AppLock.lockoutRemainingMs(activity)
                when {
                    wait > 0 -> status.text = t("تلاش زیاد؛ ${wait / 1000} ثانیه صبر کنید", "Too many tries; wait ${wait / 1000}s")
                    AppLock.verify(activity, pin.text?.toString().orEmpty()) -> unlock()
                    else -> {
                        pin.setText("")
                        val after = AppLock.lockoutRemainingMs(activity)
                        status.text = if (after > 0) t("رمز اشتباه؛ ${after / 1000} ثانیه صبر کنید", "Wrong PIN; wait ${after / 1000}s")
                        else t("رمز اشتباه است", "Wrong PIN")
                    }
                }
            }
        }
        cover.addView(title)
        cover.addView(status)
        cover.addView(pin, LinearLayout.LayoutParams(dp(220), -2))
        cover.addView(unlockButton, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(12) })
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (AppLock.useDeviceCredential(activity) && keyguard?.isDeviceSecure == true) {
            val bio = MaterialButton(activity).apply {
                text = t("👆 اثر انگشت / قفل گوشی", "👆 Fingerprint / phone lock")
                isAllCaps = false
                setOnClickListener { requestDeviceCredential() }
            }
            cover.addView(bio, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(8) })
        }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        cover.bringToFront()
        lockCover = cover
    }

    @Suppress("DEPRECATION")
    private fun requestDeviceCredential() {
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            t("باز کردن Cat Client", "Unlock Cat Client"),
            t("با اثر انگشت یا قفل گوشی تأیید کنید", "Confirm with fingerprint or screen lock"),
        ) ?: return
        runCatching { activity.startActivityForResult(intent, REQ_DEVICE_CREDENTIAL) }
    }

    private fun unlock() {
        AppLock.markUnlocked(activity)
        lockCover?.let { cover -> (cover.parent as? ViewGroup)?.removeView(cover) }
        lockCover = null
    }

    fun showAppLockSettings() {
        val enabled = AppLock.isEnabled(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val info = TextView(activity).apply {
            text = t(
                "وقتی روشن باشد، هر بار که برنامه بیش از ۳۰ ثانیه در پس‌زمینه بماند، برای باز شدن رمز عددی (یا اثر انگشت گوشی) می‌خواهد. رمز فقط به شکل هش روی همین گوشی ذخیره می‌شود.",
                "When on, the app asks for a PIN (or the phone's fingerprint) after it has been in the background for 30 s. The PIN is stored only as a hash on this phone.",
            )
            setTextColor(palette.textSecondary)
            textSize = 13f
        }
        box.addView(info)
        val pin1 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = t("رمز عددی جدید (حداقل ۴ رقم)", "New PIN (min 4 digits)")
        }
        val pin2 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = t("تکرار رمز", "Repeat PIN")
        }
        val useDevice = android.widget.CheckBox(activity).apply {
            text = t("اجازهٔ باز کردن با اثر انگشت / قفل گوشی", "Allow fingerprint / phone lock")
            isChecked = AppLock.useDeviceCredential(activity)
        }
        if (!enabled) {
            box.addView(pin1)
            box.addView(pin2)
        }
        box.addView(useDevice)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔒 قفل برنامه", "🔒 App lock") + if (enabled) t(" (روشن)", " (on)") else t(" (خاموش)", " (off)"))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
        if (enabled) {
            builder.setNeutralButton(t("خاموش کردن قفل", "Turn off")) { _, _ ->
                AppLock.disable(activity)
                toast(t("قفل برنامه خاموش شد", "App lock turned off"))
            }
            builder.setPositiveButton(t("ذخیره", "Save")) { _, _ -> AppLock.setUseDeviceCredential(activity, useDevice.isChecked) }
        } else {
            builder.setPositiveButton(t("روشن کردن", "Turn on")) { _, _ ->
                val a = pin1.text?.toString().orEmpty()
                val b = pin2.text?.toString().orEmpty()
                when {
                    a.length < AppLock.MIN_PIN || !a.all(Char::isDigit) -> toast(t("رمز باید حداقل ۴ رقم باشد", "PIN must be at least 4 digits"))
                    a != b -> toast(t("دو رمز یکی نیستند", "PINs do not match"))
                    else -> {
                        AppLock.enable(activity, a)
                        AppLock.setUseDeviceCredential(activity, useDevice.isChecked)
                        AppLock.markUnlocked(activity)
                        toast(t("✅ قفل برنامه روشن شد", "✅ App lock is on"))
                    }
                }
            }
        }
        showDialog(builder.create())
    }

    // ------------------------------------------------------------------------------------
    // Hub
    // ------------------------------------------------------------------------------------

    fun showToolsHub() {
        val root = ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(list)
        var dialog: AlertDialog? = null
        fun section(text: String) {
            list.addView(
                TextView(activity).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(palette.teal)
                    setPadding(0, dp(12), 0, dp(4))
                },
            )
        }
        fun item(label: String, help: String, filled: Boolean = false, action: () -> Unit) {
            list.addView(
                actionButton(label, filled) {
                    dialog?.dismiss()
                    action()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
            list.addView(
                TextView(activity).apply {
                    text = help
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                },
            )
        }
        section(t("📁 فایل و پشتیبان", "📁 Files & backup"))
        item(t("📂 وارد کردن فایل کانفیگ", "📂 Import config file"), t("فایل .yaml / .json / .conf (وایرگارد) / .txt / .toml را از حافظه انتخاب کن تا مثل اشتراک اضافه شود.", "Pick a .yaml / .json / .conf (WireGuard) / .txt / .toml file; it is added like a subscription."), true) { openImportFilePicker() }
        item(t("💾 پشتیبان‌گیری از اشتراک‌ها", "💾 Back up subscriptions"), t("همهٔ اشتراک‌ها در یک فایل JSON ذخیره می‌شوند تا بعد از نصب دوباره برگردانی.", "Saves all subscriptions into one JSON file you can restore later.")) { startBackup() }
        item(t("♻️ بازگردانی پشتیبان", "♻️ Restore backup"), t("فایل پشتیبان را انتخاب کن؛ فقط موارد جدید اضافه می‌شوند و چیزی پاک نمی‌شود.", "Pick a backup file; only new entries are added, nothing is deleted.")) { startRestore() }
        section(t("🔐 امنیت و خودکارسازی", "🔐 Security & automation"))
        item(t("🔒 قفل برنامه (PIN / اثر انگشت)", "🔒 App lock (PIN / fingerprint)"), t("برای باز کردن برنامه رمز یا اثر انگشت بخواهد.", "Require a PIN or fingerprint to open the app.")) { showAppLockSettings() }
        item(t("🔄 رفرش خودکار اشتراک‌ها و هشدار حجم/انقضا", "🔄 Auto-refresh subscriptions & quota alerts"), t("هر ۶ ساعت اشتراک‌های لینک‌دار به‌روز می‌شوند و اگر حجم رو به اتمام یا تاریخ نزدیک باشد نوتیفیکیشن می‌آید.", "Every 6 h URL subscriptions refresh; you get a notification when quota or expiry is close.")) { showAutoRefreshSettings() }
        item(t("🧬 وضعیت موتورهای واقعی تانل", "🧬 Real tunnel engine status"), t("نشان می‌دهد کدام نوع کانفیگ واقعاً موتور دارد (DNS، WARP و …) و کدام قبلاً فقط وانمود می‌کرد.", "Shows which config types have a real engine (DNS, WARP, …) and which only pretended before.")) { showEngineStatus() }
        section(t("🛰️ جعبه‌ابزار اسکنر ", "🛰️ Scanner toolkit "))
        item(t("🌐 خروجی ASN → رنج آی‌پی", "🌐 ASN export → IP ranges"), t("رنج‌های آی‌پی هر اپراتور/شرکت (مخابرات، همراه اول، ایرانسل، آروان، کلادفلر و …) را از RIPE می‌گیرد؛ کپی کن یا مستقیم به اسکنر بفرست.", "Fetches the IP ranges of an operator/company from RIPE; copy them or send them to the scanner.")) { showAsnExport() }
        item(t("🧩 کانفیگ‌ساز (آی‌پی تمیز → کانفیگ)", "🧩 Config Maker (clean IP → configs)"), t("کانفیگ‌هایت را بده و آی‌پی‌های تمیز را؛ برای هر آی‌پی کانفیگ جدید با همان SNI ساخته می‌شود. «IP:پورت» دقیقاً با همان پورت.", "Give configs + clean IPs; a new config is built per IP keeping SNI. IP:port keeps exactly that port.")) { showConfigMaker() }
        item(t("🔌 اسکن پروکسی HTTP باز (۳ مرحله)", "🔌 Open HTTP proxy scan (3 waves)"), t("اتصال TCP ← دست‌دادن CONNECT ← درخواست واقعی؛ فقط پروکسی‌های واقعاً کارا می‌مانند.", "TCP → CONNECT handshake → real request; only really working proxies remain.")) { showProxyScan(WhiteDnsToolkit.ProxyKind.HTTP) }
        item(t("🧦 اسکن SOCKS5", "🧦 SOCKS5 scan"), t("همان سه مرحله برای SOCKS5 (بدون رمز).", "Same three waves for SOCKS5 (no auth).")) { showProxyScan(WhiteDnsToolkit.ProxyKind.SOCKS5) }
        item(t("🔐 اسکنر SNI (دامنه‌فرانتینگ)", "🔐 SNI scanner (domain fronting)"), t("روی یک آی‌پی تمیز، دست‌دادن TLS با چند SNI را واقعاً امتحان می‌کند تا ببینی کدام SNI روی خطت رد می‌شود.", "Really tries TLS handshakes with many SNIs on one clean IP to see which pass on your line.")) { showSniScan() }
        item(t("⚡ رتبه‌بندی سرعت و افت بسته", "⚡ Speed & loss rank"), t("برای هر آی‌پی چند بار اتصال می‌گیرد (افت ٪، پینگ، نوسان) و برای بهترین‌ها سرعت دانلود واقعی را می‌سنجد.", "Several connects per IP (loss %, latency, jitter) plus a real download test for the best.")) { showSpeedRank() }
        item(t("📡 اسکن ریزالور DNS / آمادگی تانل", "📡 DNS resolver / tunnel-readiness scan"), t("بازگشتی بودن، EDNS0، عبور نام طولانی و TXT را می‌سنجد؛ امتیاز ۴ یعنی مناسب برای تانل DNS.", "Checks recursion, EDNS0, long names and TXT; score 4 means good for a DNS tunnel.")) { showResolverScan() }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🧰 جعبه‌ابزار Cat Client", "🧰 Cat Client toolbox"))
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        showDialog(dialog)
    }

    private fun actionButton(label: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(activity).apply {
            text = label
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

    fun showAutoRefreshSettings() {
        val on = SubscriptionAutoRefresh.isEnabled(activity)
        val last = SubscriptionAutoRefresh.lastRunMs(activity)
        val lastText = if (last <= 0) t("هنوز اجرا نشده", "not run yet")
        else android.text.format.DateUtils.getRelativeTimeSpanString(last).toString()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🔄 رفرش خودکار اشتراک‌ها", "🔄 Subscription auto-refresh"))
            .setMessage(
                t(
                    "وضعیت: ${if (on) "روشن" else "خاموش"}\nآخرین اجرا: $lastText\n\nهر ${SubscriptionAutoRefresh.INTERVAL_HOURS} ساعت (فقط وقتی اینترنت هست) اشتراک‌های لینک‌دار به‌روز می‌شوند. اگر پنل حجم/تاریخ بفرستد، در این حالت‌ها نوتیفیکیشن می‌آید: کمتر از ۳ روز مانده، منقضی شده، بیش از ۹۰٪ مصرف، حجم تمام شده.",
                    "Status: ${if (on) "on" else "off"}\nLast run: $lastText\n\nEvery ${SubscriptionAutoRefresh.INTERVAL_HOURS} h (only with internet) URL subscriptions refresh. If the panel reports quota/expiry you are notified when: < 3 days left, expired, > 90% used, quota used up.",
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (on) t("خاموش کن", "Turn off") else t("روشن کن", "Turn on")) { _, _ ->
                SubscriptionAutoRefresh.setEnabled(activity, !on)
                toast(if (!on) t("✅ رفرش خودکار روشن شد", "✅ Auto-refresh on") else t("رفرش خودکار خاموش شد", "Auto-refresh off"))
            }
            .create()
        showDialog(dialog)
    }

    fun showEngineStatus() {
        val dnsEngine = SlipNetCliEngine.isInstalled(activity)
        val warp = WarpRegistration.cached(activity)
        val text = t(
            "✅ پروتکل‌های عادی (VLESS، VMess، Trojan، Shadowsocks، Hysteria2، TUIC، WireGuard با کلید واقعی، AnyTLS، SOCKS/HTTP): روی هستهٔ mihomo واقعاً تونل می‌زنند.\n\n" +
                (if (dnsEngine) "✅ تانل DNS واقعی (dnstt، NoizDNS، VayDNS و slipnet://): موتور Cat DNS داخل برنامه نصب است و واقعاً از روی DNS تونل می‌زند.\n\n"
                else "⚠️ تانل DNS: موتور Cat DNS در این نسخه بسته‌بندی نشده؛ کانفیگ‌های DNS «در دسترس نیست» نشان داده می‌شوند (دیگر وانمود به اتصال نمی‌کنند).\n\n") +
                (if (warp != null) "✅ WARP / AWG خودکار: حساب واقعی WARP ثبت شده (${warp.ipv4}${warp.ipv6?.let { " · IPv6" } ?: ""}).\n\n"
                else "ℹ️ WARP / AWG خودکار: اولین اتصال، یک حساب واقعی WARP از کلادفلر ثبت می‌کند (قبلاً کلید جعلی بود و هیچ‌وقت وصل نمی‌شد).\n\n") +
                "❌ بدون موتور واقعی (قبلاً بی‌صدا مستقیم وصل می‌شدند و فقط ظاهر اتصال داشتند): CottenDNS، StormDNS، MasterDNS، Soar، Slipstream، QS/QQ/Spoof Tunnel، S3 Bypass، رلهٔ GAS، Phantun بدون روت. این‌ها حالا صادقانه «در دسترس نیست» می‌شوند.\n\n" +
                "⚠️ Paqet / paqetNG: پروتکل واقعی paqet بستهٔ خام می‌سازد و روی گوشی روت لازم دارد؛ فقط اگر سرور ورودی SOCKS5 داشته باشد (entry_socks=1) کار می‌کند.\n\n" +
                "⚠️ Cat Fox VPN: فقط با ورود به حساب فایرفاکس واقعاً تونل می‌زند؛ بدون حساب، دیگر مستقیم وصل نمی‌شود.",
            "✅ Regular protocols (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard with a real key, AnyTLS, SOCKS/HTTP) really tunnel on the mihomo core.\n\n" +
                (if (dnsEngine) "✅ Real DNS tunnel (dnstt, NoizDNS, VayDNS, slipnet://): the Cat DNS engine is installed and really tunnels over DNS.\n\n"
                else "⚠️ DNS tunnel: the Cat DNS engine is not packaged in this build; DNS configs show as unavailable (no more fake connections).\n\n") +
                (if (warp != null) "✅ WARP / AWG auto: a real WARP account is registered.\n\n"
                else "ℹ️ WARP / AWG auto: the first connect registers a real WARP account (previously a fake key that could never connect).\n\n") +
                "❌ No real engine (previously connected directly in silence): CottenDNS, StormDNS, MasterDNS, Soar, Slipstream, QS/QQ/Spoof tunnels, S3 bypass, GAS relay, Phantun without root.\n\n" +
                "⚠️ Paqet / paqetNG: real paqet needs root on the phone; works only when the server exposes a SOCKS5 entry (entry_socks=1).\n\n" +
                "⚠️ Cat Fox VPN: tunnels only when signed in to a Firefox account.",
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(t("🧬 وضعیت موتورهای واقعی", "🧬 Real engine status"))
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (warp != null) setNeutralButton(t("ساخت حساب WARP جدید", "New WARP account")) { _, _ ->
                    WarpRegistration.forget(activity)
                    toast(t("در اتصال بعدی حساب جدید ثبت می‌شود", "A new account is registered on next connect"))
                }
            }
            .create()
        showDialog(dialog)
    }

    // ------------------------------------------------------------------------------------
    // Scanner toolkit UI
    // ------------------------------------------------------------------------------------

    private inner class ToolPage(title: String, help: String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val progress = TextView(activity).apply {
            textSize = 12f
            setTextColor(palette.amber)
            setPadding(0, dp(6), 0, dp(4))
        }
        val output = TextView(activity).apply {
            textSize = 12f
            setTextColor(palette.textPrimary)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var job: Job? = null
        @Volatile var cancelled = false
        var resultText: String = ""
        val dialog: AlertDialog

        init {
            box.addView(
                TextView(activity).apply {
                    text = help
                    textSize = 12.5f
                    setTextColor(palette.textSecondary)
                    setPadding(0, 0, 0, dp(6))
                },
            )
            val scroll = ScrollView(activity).apply { addView(box) }
            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(t("بستن", "Close"), null)
                .create()
            dialog.setOnDismissListener {
                cancelled = true
                job?.cancel()
            }
        }

        fun input(hint: String, initial: String = "", multiline: Boolean = false, number: Boolean = false): EditText {
            val edit = EditText(activity).apply {
                this.hint = hint
                setText(initial)
                textSize = 13f
                setTextColor(palette.textPrimary)
                setHintTextColor(palette.textSecondary)
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    minLines = 3
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            box.addView(edit, LinearLayout.LayoutParams(-1, -2))
            return edit
        }

        fun button(label: String, filled: Boolean = true, onClick: () -> Unit): MaterialButton {
            val b = actionButton(label, filled, onClick)
            box.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            return b
        }

        fun finishLayout() {
            box.addView(progress)
            box.addView(output)
            box.addView(actions)
        }

        fun show() {
            finishLayout()
            showDialog(dialog)
        }

        fun run(runButton: MaterialButton, work: suspend () -> Pair<String, ResultActions>) {
            if (job?.isActive == true) {
                cancelled = true
                job?.cancel()
                return
            }
            cancelled = false
            actions.removeAllViews()
            output.text = ""
            val label = runButton.text
            runButton.text = t("⏹ توقف", "⏹ Stop")
            progress.text = t("در حال اجرا… (برای نتیجهٔ دقیق بهتر است VPN خاموش باشد)", "Running… (best with the VPN off)")
            job = scope.launch {
                val result = runCatching { work() }
                runButton.text = label
                result.onSuccess { (text, extra) ->
                    resultText = text
                    output.text = text.ifBlank { t("نتیجه‌ای پیدا نشد.", "No results.") }
                    progress.text = ""
                    addResultActions(extra)
                }.onFailure { error ->
                    progress.text = if (error is kotlinx.coroutines.CancellationException) t("متوقف شد", "Stopped")
                    else t("خطا: ", "Error: ") + (error.message ?: error.javaClass.simpleName)
                }
            }
        }

        fun progressPost(text: String) {
            activity.runOnUiThread { progress.text = text }
        }

        private fun addResultActions(extra: ResultActions) {
            if (extra.copy.isNotBlank()) {
                actions.addView(actionButton(t("📋 کپی", "📋 Copy"), false) { copy(extra.copy) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                actions.addView(actionButton(t("📤 اشتراک‌گذاری", "📤 Share"), false) { share(extra.copy) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            if (extra.links.isNotEmpty()) {
                actions.addView(
                    actionButton(t("➕ افزودن ${extra.links.size} کانفیگ به اشتراک‌ها", "➕ Add ${extra.links.size} configs as subscription"), true) {
                        dialog.dismiss()
                        addSubscription(extra.links.joinToString("\n"), extra.subscriptionName)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
                )
            }
            if (extra.ranges.isNotBlank()) {
                actions.addView(
                    actionButton(t("🛰️ ارسال به اسکنر آی‌پی", "🛰️ Send to IP scanner"), true) {
                        dialog.dismiss()
                        sendRangesToScanner(extra.ranges)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
                )
            }
        }
    }

    private data class ResultActions(
        val copy: String = "",
        val links: List<String> = emptyList(),
        val ranges: String = "",
        val subscriptionName: String = "Toolkit",
    )

    private fun copy(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Cat Client", text))
        toast(t("کپی شد", "Copied"))
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, "Cat Client")) }
    }

    fun showAsnExport() {
        val page = ToolPage(
            t("🌐 خروجی ASN → رنج آی‌پی", "🌐 ASN export → IP ranges"),
            t(
                "شمارهٔ ASN را بنویس (مثلاً 58224 یا چند تا با فاصله) یا از دکمه‌های آماده یکی را بزن. رنج‌ها از RIPE گرفته می‌شود. «تبدیل به لیست آی‌پی» از هر رنج چند آی‌پی پخش‌شده برمی‌دارد.",
                "Type ASNs (e.g. 58224, several separated by spaces) or tap a preset. Ranges come from RIPE. “Expand to IPs” takes spread-out IPs from every range.",
            ),
        )
        val asnInput = page.input(t("ASN ها (مثلاً 58224 44244)", "ASNs (e.g. 58224 44244)"), "58224")
        val perPrefix = page.input(t("تعداد آی‌پی از هر رنج (برای تبدیل)", "IPs per range (for expansion)"), "8", number = true)
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        WhiteDnsToolkit.ASN_PRESETS.chunked(2).forEach { row ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { preset ->
                line.addView(
                    actionButton("AS${preset.asn} · ${if (isFarsi()) preset.titleFa else preset.titleEn}", false) {
                        val current = asnInput.text?.toString().orEmpty().trim()
                        val token = preset.asn.toString()
                        if (token !in current.split(Regex("\\s+"))) asnInput.setText(if (current.isBlank()) token else "$current $token")
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); topMargin = dp(4) },
                )
            }
            chips.addView(line)
        }
        page.box.addView(chips)
        fun asns(): List<Int> = asnInput.text?.toString().orEmpty().split(Regex("[\\s,;]+"))
            .mapNotNull { it.trim().removePrefix("AS").removePrefix("as").toIntOrNull() }.distinct()
        lateinit var cidrButton: MaterialButton
        lateinit var ipButton: MaterialButton
        cidrButton = page.button(t("📥 گرفتن رنج‌ها (CIDR)", "📥 Fetch ranges (CIDR)")) {
            val list = asns()
            if (list.isEmpty()) { toast(t("ASN معتبر وارد کن", "Enter a valid ASN")); return@button }
            page.run(cidrButton) {
                val all = withContext(Dispatchers.IO) { list.flatMap { asn -> WhiteDnsToolkit.fetchAsnPrefixes(asn) } }.distinct()
                val text = all.joinToString("\n")
                "# ${list.joinToString(" ") { "AS$it" }} — ${all.size} ${t("رنج", "ranges")}\n$text" to
                    ResultActions(copy = text, ranges = all.joinToString(", "))
            }
        }
        ipButton = page.button(t("🔢 تبدیل به لیست آی‌پی", "🔢 Expand to IP list"), filled = false) {
            val list = asns()
            if (list.isEmpty()) { toast(t("ASN معتبر وارد کن", "Enter a valid ASN")); return@button }
            val per = perPrefix.text?.toString()?.toIntOrNull()?.coerceIn(1, 256) ?: 8
            page.run(ipButton) {
                val ips = withContext(Dispatchers.IO) {
                    WhiteDnsToolkit.expandCidrs(list.flatMap { WhiteDnsToolkit.fetchAsnPrefixes(it) }.distinct(), per)
                }
                val text = ips.joinToString("\n")
                "# ${ips.size} IP\n$text" to ResultActions(copy = text, ranges = ips.take(4000).joinToString(", "))
            }
        }
        page.show()
    }

    fun showConfigMaker() {
        val page = ToolPage(
            t("🧩 کانفیگ‌ساز", "🧩 Config Maker"),
            t(
                "بالا: کانفیگ‌هایت (vless / vmess / trojan / ss / hy2 / tuic). پایین: آی‌پی‌های تمیز (هر خط یکی؛ اگر «IP:پورت» بنویسی همان پورت استفاده می‌شود، وگرنه پورت خود کانفیگ). دامنهٔ اصلی به‌عنوان SNI/Host حفظ می‌شود.",
                "Top: your configs (vless / vmess / trojan / ss / hy2 / tuic). Bottom: clean IPs (one per line; “IP:port” keeps that port, otherwise the config's own port). The original domain is kept as SNI/Host.",
            ),
        )
        val configs = page.input(t("کانفیگ‌ها (هر خط یکی)", "Configs (one per line)"), multiline = true)
        val ips = page.input(t("آی‌پی‌های تمیز (مثلاً 188.114.98.0 یا 104.17.1.1:2053)", "Clean IPs (e.g. 188.114.98.0 or 104.17.1.1:2053)"), multiline = true)
        lateinit var makeButton: MaterialButton
        lateinit var extractButton: MaterialButton
        makeButton = page.button(t("⚙️ ساخت کانفیگ‌ها", "⚙️ Build configs")) {
            val links = configs.text?.toString().orEmpty().lines().filter { it.contains("://") }
            val targets = WhiteDnsToolkit.parseTargets(ips.text?.toString().orEmpty(), perCidr = 16, max = 500)
            if (links.isEmpty() || targets.isEmpty()) { toast(t("هم کانفیگ و هم آی‌پی لازم است", "Both configs and IPs are required")); return@button }
            page.run(makeButton) {
                val made = withContext(Dispatchers.Default) { WhiteDnsToolkit.makeConfigs(links, targets) }
                val text = made.joinToString("\n")
                "# ${made.size} ${t("کانفیگ ساخته شد", "configs built")}\n$text" to
                    ResultActions(copy = text, links = made, subscriptionName = t("کانفیگ‌ساز", "Config Maker"))
            }
        }
        extractButton = page.button(t("🔎 استخراج IP:پورت از کانفیگ‌ها", "🔎 Extract IP:port from configs"), filled = false) {
            val links = configs.text?.toString().orEmpty().lines().filter { it.contains("://") }
            if (links.isEmpty()) { toast(t("کانفیگ وارد کن", "Enter configs")); return@button }
            page.run(extractButton) {
                val endpoints = WhiteDnsToolkit.extractEndpoints(links)
                val text = endpoints.joinToString("\n")
                "# ${endpoints.size} endpoint\n$text" to ResultActions(copy = text)
            }
        }
        page.show()
    }

    fun showProxyScan(kind: WhiteDnsToolkit.ProxyKind) {
        val http = kind == WhiteDnsToolkit.ProxyKind.HTTP
        val page = ToolPage(
            if (http) t("🔌 اسکن پروکسی HTTP باز", "🔌 Open HTTP proxy scan") else t("🧦 اسکن SOCKS5", "🧦 SOCKS5 scan"),
            t(
                "آی‌پی، رنج (CIDR) یا «IP:پورت» بنویس. بدون پورت، پورت‌های رایج امتحان می‌شوند. سه مرحله: اتصال ← دست‌دادن ← درخواست واقعی.",
                "Enter IPs, CIDR ranges or IP:port. Without a port, common ports are tried. Three waves: connect → handshake → real request.",
            ),
        )
        val targets = page.input(t("آی‌پی / رنج / IP:پورت", "IP / CIDR / IP:port"), multiline = true)
        val ports = page.input(
            t("پورت‌های پیش‌فرض", "Default ports"),
            (if (http) WhiteDnsToolkit.DEFAULT_HTTP_PORTS else WhiteDnsToolkit.DEFAULT_SOCKS_PORTS).joinToString(","),
        )
        lateinit var runButton: MaterialButton
        runButton = page.button(t("▶️ شروع اسکن", "▶️ Start scan")) {
            val list = WhiteDnsToolkit.parseTargets(targets.text?.toString().orEmpty(), perCidr = 64, max = 2048)
            val portList = ports.text?.toString().orEmpty().split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } }
            if (list.isEmpty()) { toast(t("هدف وارد کن", "Enter targets")); return@button }
            page.run(runButton) {
                val hits = withContext(Dispatchers.IO) {
                    WhiteDnsToolkit.scanOpenProxies(
                        list, kind, portList.ifEmpty { listOf(if (http) 8080 else 1080) },
                        progress = { d, total, h -> if (d % 8 == 0 || d == total) page.progressPost("$d / $total · ✅ $h") },
                        cancelled = { page.cancelled },
                    )
                }
                val links = hits.map { it.link() }
                val text = hits.joinToString("\n") { "${it.host}:${it.port}  ${it.latencyMs}ms" }
                "# ${hits.size} ${t("پروکسی سالم", "working proxies")}\n$text" to
                    ResultActions(copy = links.joinToString("\n"), links = links, subscriptionName = if (http) "HTTP proxies" else "SOCKS5 proxies")
            }
        }
        page.show()
    }

    fun showSniScan() {
        val page = ToolPage(
            t("🔐 اسکنر SNI", "🔐 SNI scanner"),
            t(
                "یک آی‌پی تمیز (مثلاً کلادفلر 188.114.98.0) و پورت بده؛ برای هر SNI دست‌دادن TLS واقعی انجام می‌شود. ✅ یعنی روی خطت رد می‌شود؛ 🔏 یعنی گواهی هم با همان دامنه می‌خواند (مناسب دامنه‌فرانتینگ).",
                "Give a clean IP (e.g. Cloudflare 188.114.98.0) and port; a real TLS handshake is made per SNI. ✅ passes on your line; 🔏 the certificate also matches (good for fronting).",
            ),
        )
        val ip = page.input(t("آی‌پی", "IP"), "188.114.98.0")
        val port = page.input(t("پورت", "Port"), "443", number = true)
        val snis = page.input(t("SNI ها (هر خط یکی)", "SNIs (one per line)"), WhiteDnsToolkit.DEFAULT_SNIS.joinToString("\n"), multiline = true)
        lateinit var runButton: MaterialButton
        runButton = page.button(t("▶️ شروع", "▶️ Start")) {
            val host = ip.text?.toString().orEmpty().trim()
            val p = port.text?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            val list = snis.text?.toString().orEmpty().lines().map(String::trim).filter { it.isNotEmpty() }.distinct()
            if (host.isEmpty() || list.isEmpty()) { toast(t("آی‌پی و SNI لازم است", "IP and SNIs are required")); return@button }
            page.run(runButton) {
                val results = withContext(Dispatchers.IO) {
                    WhiteDnsToolkit.scanSni(host, p, list, progress = { d, total -> page.progressPost("$d / $total") }, cancelled = { page.cancelled })
                }
                val text = results.joinToString("\n") { r ->
                    "${if (r.ok) "✅" else "❌"}${if (r.certMatches) "🔏" else "  "} ${r.sni}  ${r.latencyMs}ms  ${r.detail}"
                }
                text to ResultActions(copy = results.filter { it.ok }.joinToString("\n") { it.sni })
            }
        }
        page.show()
    }

    fun showSpeedRank() {
        val page = ToolPage(
            t("⚡ رتبه‌بندی سرعت و افت", "⚡ Speed & loss rank"),
            t(
                "آی‌پی‌ها (یا IP:پورت، یا رنج) را بده. برای هر کدام ۸ بار اتصال گرفته می‌شود و بر اساس افت، پینگ و نوسان مرتب می‌شود؛ برای ۵ تای اول سرعت دانلود واقعی هم سنجیده می‌شود.",
                "Give IPs (or IP:port, or ranges). Each gets 8 connects and is ranked by loss, latency and jitter; the top 5 also get a real download test.",
            ),
        )
        val targets = page.input(t("آی‌پی‌ها", "IPs"), multiline = true)
        val port = page.input(t("پورت پیش‌فرض", "Default port"), "443", number = true)
        lateinit var runButton: MaterialButton
        runButton = page.button(t("▶️ شروع", "▶️ Start")) {
            val list = WhiteDnsToolkit.parseTargets(targets.text?.toString().orEmpty(), perCidr = 16, max = 512)
            val p = port.text?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            if (list.isEmpty()) { toast(t("آی‌پی وارد کن", "Enter IPs")); return@button }
            page.run(runButton) {
                val ranked = withContext(Dispatchers.IO) {
                    WhiteDnsToolkit.rankSpeedAndLoss(list, p, progress = { d, total -> page.progressPost("$d / $total") }, cancelled = { page.cancelled })
                }
                val text = ranked.mapIndexed { i, r ->
                    val speed = r.downloadKbps?.let { " · ⬇ ${it} kB/s" }.orEmpty()
                    val ms = if (r.received == 0) "—" else "${r.medianMs}ms ±${r.jitterMs}"
                    "${i + 1}. ${r.endpoint}  loss ${r.lossPercent}%  $ms$speed"
                }.joinToString("\n")
                text to ResultActions(copy = ranked.filter { it.received > 0 }.joinToString("\n") { it.endpoint })
            }
        }
        page.show()
    }

    fun showResolverScan() {
        val page = ToolPage(
            t("📡 اسکن ریزالور DNS", "📡 DNS resolver scan"),
            t(
                "ریزالورها را بده (پیش‌فرض: ریزالورهای رایج ایران و خارج). امتیاز ۰ تا ۴: بازگشتی + EDNS0 + نام طولانی + TXT. برای تانل DNS (dnstt / NoizDNS / VayDNS) ریزالور با امتیاز ۴ را در کانفیگ بگذار.",
                "Give resolvers (default: common Iranian and foreign ones). Score 0–4: recursive + EDNS0 + long names + TXT. For DNS tunnels use a score-4 resolver.",
            ),
        )
        val targets = page.input(t("ریزالورها", "Resolvers"), WhiteDnsToolkit.DEFAULT_RESOLVERS.joinToString("\n"), multiline = true)
        lateinit var runButton: MaterialButton
        runButton = page.button(t("▶️ شروع", "▶️ Start")) {
            val list = WhiteDnsToolkit.parseTargets(targets.text?.toString().orEmpty(), perCidr = 64, max = 1024)
            if (list.isEmpty()) { toast(t("ریزالور وارد کن", "Enter resolvers")); return@button }
            page.run(runButton) {
                val results = withContext(Dispatchers.IO) {
                    WhiteDnsToolkit.scanResolvers(list, progress = { d, total -> page.progressPost("$d / $total") }, cancelled = { page.cancelled })
                }
                val text = results.joinToString("\n") { r ->
                    if (!r.reachable) "❌ ${r.endpoint}  —"
                    else "${"★".repeat(r.tunnelScore)}${"☆".repeat(4 - r.tunnelScore)} ${r.endpoint}  ${r.latencyMs}ms" +
                        "  ${if (r.recursive) "REC" else "rec✗"} ${if (r.edns0) "EDNS" else "edns✗"} ${if (r.longQnameOk) "LONG" else "long✗"} ${if (r.txtOk) "TXT" else "txt✗"}"
                }
                text to ResultActions(copy = results.filter { it.tunnelScore >= 3 }.joinToString("\n") { it.endpoint })
            }
        }
        page.show()
    }

    companion object {
        const val REQ_IMPORT_FILE = 7101
        const val REQ_BACKUP_CREATE = 7102
        const val REQ_RESTORE_OPEN = 7103
        const val REQ_DEVICE_CREDENTIAL = 7104
    }
}
