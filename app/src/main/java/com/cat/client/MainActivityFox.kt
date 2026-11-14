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
import android.widget.ArrayAdapter
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

// Cat Fox VPN: card, account manager, sign-in dialogs, hub, direct connect.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/**
 * Dedicated card for **Firefox Browser VPN (`FENIX` / Mozilla IP Protection on Fastly)**
 * offering 50 GB/month per free Firefox Account, multi-account auto-failover, and the Recommended + free countries.
 */
internal fun MainActivity.buildFirefoxVpnFenixCard(): View {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val card = advancedSettingsPanel()

    card.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "🦊 Cat Fox VPN (۵۰ گیگ رایگان در ماه)"
            } else {
                "🦊 Cat Fox VPN (50 GB/mo Free on Fastly)"
            }
            textSize = 15.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEAL)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    card.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "ترافیک کل گوشی روی سرورهای Fastly مرورگر فایرفاکس (غیرکلادفلر و باز در ایران) — سرور پیشنهادی و کشورهای رایگان + پشتیبانی از چند اکانت همزمان با چرخش خودکار ۵۰ گیگابایت و برش بسته TLS ضد فیلترینگ.\nفقط وب (پورت‌های 80، 443، 8080، 8443): تماس صوتی/تصویری و بازی آنلاین از این راه رد نمی‌شود."
            } else {
                "Full-device VPN over Mozilla's Fastly edge (Recommended + free countries) — 50 GB/month per free Firefox account with multi-account auto-failover and Anti-DPI TLS splitting.\nWeb only (ports 80, 443, 8080, 8443): voice/video calls and online games do not go through it."
            }
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )

    val statusView = TextView(this).apply {
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(dp(2).toFloat(), 1.05f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    card.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    fun refreshFenixSummary() {
        val accounts = FirefoxVpnEngine.loadAccounts(this)
        val activeAcc = FirefoxVpnEngine.selectBestActiveAccount(accounts)
        val selCode = FirefoxVpnEngine.readSelectedCountryCode(this)
        val countries = FirefoxVpnEngine.getAvailableCountries(this)
        val countryLabel = when (selCode) {
            FirefoxVpnEngine.COUNTRY_FASTEST ->
                if (isFarsi) "⚡ سریع‌ترین کشور (انتخاب خودکار با کمترین پینگ)" else "⚡ Fastest Country (Auto-Ping)"
            else -> {
                val c = countries.firstOrNull { it.code.equals(selCode, ignoreCase = true) } ?: countries.first()
                val ping = FirefoxVpnEngine.lastCountryPings[c.code.uppercase(java.util.Locale.US)]
                val ms = when {
                    ping == null -> ""
                    ping.reachable -> if (isFarsi) " · ${ping.rttMs} ms روی خط شما" else " · ${ping.rttMs} ms on your line"
                    else -> if (isFarsi) " · روی این خط بسته" else " · closed on this line"
                }
                "${c.flag} ${if (isFarsi) c.nameFa else c.nameEn}$ms"
            }
        }
        val cleanIp = FirefoxVpnEngine.readCleanFastlyEdgeIp(this)
            .ifBlank { if (isFarsi) "خودکار (سرور مستقیم Fastly)" else "Auto (Direct Fastly Edge)" }
        val fragOn = FirefoxVpnEngine.isTlsFragmentEnabled(this)

        statusView.text = if (isFarsi) {
            buildString {
                if (activeAcc != null) {
                    append("👤 حساب فعال: ${activeAcc.email} (از ${accounts.size} حساب · چرخش خودکار ۹۵٪)\n")
                    append("📊 حجم رایگان: ${FirefoxVpnEngine.formatQuotaSummaryFa(activeAcc)}\n")
                } else {
                    append("👤 حساب فایرفاکس: هنوز وارد نشده‌اید (روی «👤 حساب ۵۰ گیگ» یا «🦊 اتصال فوری» بزنید تا با ایمیل یا گوگل ۵۰ گیگ رایگان بگیرید)\n")
                }
                append("🌍 کشور خروجی: $countryLabel\n")
                append("🛡️ ضد فیلترینگ: ${if (fragOn) "برش TLS SNI روشن ✅" else "عادی"} · لبه Fastly: $cleanIp")
                FirefoxVpnEngine.lastTransport.takeIf { it.isNotBlank() }?.let {
                    append("\n✅ آخرین تونل سالم: $it")
                }
                FirefoxVpnEngine.lastTunnelError.takeIf { it.isNotBlank() }?.let {
                    append("\n⚠️ آخرین خطای تونل: ${it.take(160)}")
                }
            }
        } else {
            buildString {
                if (activeAcc != null) {
                    append("👤 Active Account: ${activeAcc.email} (${accounts.size} total · 95% auto-failover)\n")
                    append("📊 Free Quota: ${FirefoxVpnEngine.formatQuotaSummaryEn(activeAcc)}\n")
                } else {
                    append("👤 Account: Not signed in yet (Tap '👤 50GB Accounts' or '🦊 Connect' to sign in via Mozilla/Google for 50 GB/mo free)\n")
                }
                append("🌍 Exit Country: $countryLabel\n")
                append("🛡️ Anti-DPI: ${if (fragOn) "TLS SNI Split ON ✅" else "Standard"} · Fastly Edge: $cleanIp")
                FirefoxVpnEngine.lastTransport.takeIf { it.isNotBlank() }?.let {
                    append("\n✅ Last working tunnel: $it")
                }
                FirefoxVpnEngine.lastTunnelError.takeIf { it.isNotBlank() }?.let {
                    append("\n⚠️ Last tunnel error: ${it.take(160)}")
                }
            }
        }
    }
    refreshFenixSummary()

    fun fenixBtn(label: String, accent: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(10), 0, dp(10), 0)
            if (accent) {
                backgroundTintList = ColorStateList.valueOf(TEAL)
                setTextColor(BACKGROUND)
            } else {
                backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 215))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 200))
                setTextColor(TEXT_PRIMARY)
            }
            setOnClickListener { onClick() }
        }

    // Primary 1-Click Connect Button
    card.addView(
        fenixBtn(
            label = if (isFarsi) "🦊 اتصال فوری با Cat Fox VPN (روشن کردن کل گوشی)" else "🦊 Connect Cat Fox VPN Now (Full Device)",
            accent = true,
        ) {
            connectFirefoxVpnDirectly { refreshFenixSummary() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )

    // Row: Country Picker (free first) + Account Manager (50 GB/mo)
    val row1 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            fenixBtn(if (isFarsi) "🌍 کشور ▾" else "🌍 Country ▾") {
                showFirefoxCountryPickerDialog { refreshFenixSummary() }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            fenixBtn(if (isFarsi) "👤 حساب ۵۰ گیگ ▾" else "👤 50GB Accounts ▾") {
                showFirefoxAccountManagerDialog { refreshFenixSummary() }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Secondary Row: Full FENIX Hub & Ping Scanner
    card.addView(
        fenixBtn(
            label = if (isFarsi) "⚙️ پنل کامل Cat Fox VPN، تست پینگ کشورها و اسکن Fastly" else "⚙️ Full Cat Fox VPN Hub, Country Ping & Fastly Scan",
            accent = false,
        ) {
            showFirefoxVpnFenixHubDialog { refreshFenixSummary() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    return card
}

internal fun MainActivity.showFirefoxAccountManagerDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val accounts = FirefoxVpnEngine.loadAccounts(this)
    val accountLines = if (accounts.isEmpty()) {
        if (isFarsi) "هنوز حسابی اضافه نشده است (هر حساب رایگان فایرفاکس = ۵۰ گیگابایت در ماه)."
        else "No Firefox account added yet (each free account gives 50 GB/month)."
    } else {
        accounts.mapIndexed { idx, acc ->
            val quota = if (isFarsi) FirefoxVpnEngine.formatQuotaSummaryFa(acc) else FirefoxVpnEngine.formatQuotaSummaryEn(acc)
            val status = if (acc.hasUnexpiredProxyPass()) "🟢" else "🟡"
            "${idx + 1}. $status ${acc.email}\n   📊 $quota"
        }.joinToString("\n\n")
    }

    val options = if (isFarsi) {
        arrayOf(
            "🌐 ۱) ورود آسان با صفحه رسمی موزیلا / گوگل داخل برنامه (پیشنهادی ⭐)",
            "📧 ۲) ورود مستقیم با ایمیل و رمز عبور فایرفاکس (با حل خودکار چالش Fastly + کد ۲ مرحله‌ای)",
            "🔑 ۳) افزودن سریع با توکن / SessionToken / JSON (از مرورگر فایرفاکس)",
            "🔄 ۴) بروزرسانی حجم ۵۰ گیگابایت و تمدید توکن همه حساب‌ها",
            "🗑️ ۵) حذف یکی از حساب‌های ذخیره‌شده",
        )
    } else {
        arrayOf(
            "🌐 1) Easy Sign-In via In-App Mozilla Page / Google (Recommended ⭐)",
            "📧 2) Direct Sign-In with Firefox Email & Password (Fastly Auto-Solver + 2FA)",
            "🔑 3) Quick Import via Token / SessionToken / JSON (from Firefox)",
            "🔄 4) Refresh 50 GB Quota & Renew ProxyPass for All Accounts",
            "🗑️ 5) Remove a Saved Account",
        )
    }

    val headerView = TextView(this).apply {
        text = accountLines
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setPadding(dp(20), dp(10), dp(20), dp(4))
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "👤 مدیریت حساب‌های ۵۰ گیگ فایرفاکس (پشتیبانی از چند اکانت)"
            else "👤 Cat Fox VPN 50 GB Accounts (Multi-Account Pool)",
        )
        .setCustomTitle(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(4))
                addView(
                    TextView(this@showFirefoxAccountManagerDialog).apply {
                        text = if (isFarsi) "👤 حساب‌های ۵۰ گیگ Cat Fox VPN" else "👤 Cat Fox VPN 50 GB Accounts"
                        textSize = 16f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEAL)
                    },
                )
                addView(headerView)
            },
        )
        .setItems(options) { _, which ->
            when (which) {
                0 -> showFirefoxWebViewLoginDialog(onChanged)
                1 -> showFirefoxEmailLoginDialog(onChanged)
                2 -> showFirefoxTokenImportDialog(onChanged)
                3 -> {
                    if (accounts.isEmpty()) {
                        Toast.makeText(
                            this,
                            if (isFarsi) "ابتدا یک حساب فایرفاکس اضافه کنید" else "Add a Firefox account first",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            if (isFarsi) "⏳ در حال بروزرسانی توکن و سهمیه ۵۰ گیگ حساب‌ها…" else "⏳ Refreshing accounts & 50 GB quota…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        activityScope.launch {
                            withContext(Dispatchers.IO) {
                                accounts.forEach { acc ->
                                    runCatching { FirefoxVpnEngine.refreshAndActivateAccountPass(this@showFirefoxAccountManagerDialog, acc) }
                                }
                            }
                            onChanged()
                            Toast.makeText(
                                this@showFirefoxAccountManagerDialog,
                                if (isFarsi) "✅ توکن و سهمیه ۵۰ گیگ حساب‌ها بروزرسانی شد" else "✅ Refreshed accounts & quota",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                4 -> {
                    if (accounts.isEmpty()) return@setItems
                    val titles = accounts.map { it.email }.toTypedArray()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "حذف حساب فایرفاکس" else "Remove Firefox Account")
                        .setItems(titles) { _, rIdx ->
                            FirefoxVpnEngine.removeAccount(this, accounts[rIdx].id)
                            onChanged()
                            Toast.makeText(
                                this,
                                if (isFarsi) "🗑️ حساب حذف شد" else "🗑️ Account removed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                        .showCatClientDialog()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

    /**
     * In-App Official Mozilla WebView Sign-In (`https://accounts.firefox.com/`):
     * Supports Email/Password, "Continue with Google" (Gmail), Apple Sign-In, new account sign-up,
     * and Fastly CAPTCHA challenges, then automatically extracts `sessionToken` from
     * `window.localStorage.getItem("__fxa_storage.accounts")` and activates 50 GB/month!
     */
    @Suppress("SetJavaScriptEnabled")
    internal fun MainActivity.showFirefoxWebViewLoginDialog(onChanged: () -> Unit = {}) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        var completed = false
        var dialogRef: AlertDialog? = null

        var lastTypedEmail = ""
        val statusBanner = TextView(this).apply {
            text = if (isFarsi) {
                "💡 برای جیمیل (@gmail.com): ایمیل خود را در کادر بالای صفحهٔ موزیلا بنویسید و دکمهٔ آبی را بزنید (روی Continue with Google نزنید تا گوگل خطا ندهد!)."
            } else {
                "💡 Using Gmail? Enter your @gmail.com address in Mozilla's top email box and tap the blue button (avoid 'Continue with Google' to bypass Google's WebView block)."
            }
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEAL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val webView = object : android.webkit.WebView(this) {
            // Crucial for AlertDialog: tells AlertController.canTextInput() that this dialog
            // contains a text editor so it does NOT set FLAG_ALT_FOCUSABLE_IM (which blocks the keyboard).
            override fun onCheckIsTextEditor(): Boolean = true
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(430))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            // beta238: the account site only hands the session over the Firefox WebChannel to a browser that says it
            // is Firefox (see FoxWebChannel).
            settings.userAgentString = FoxWebChannel.firefoxAndroidUserAgent(android.os.Build.VERSION.RELEASE.orEmpty())
            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                    event.action == android.view.MotionEvent.ACTION_UP
                ) {
                    dialogRef?.window?.clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                    )
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                false
            }
        }
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)
        }

        val quickTypeInput = EditText(this).apply {
            hint = if (isFarsi) "ایمیل یا رمز (در صورت نیاز اینجا تایپ/پیست کنید)" else "Type/paste email or password here if needed"
            textSize = 12.5f
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val quickTypeBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = if (isFarsi) "⌨️ درج در صفحه" else "⌨️ Fill Field"
            textSize = 11.5f
            isAllCaps = false
            typeface = CatClientBodyBoldTypeface
            setOnClickListener {
                dialogRef?.window?.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                )
                val typed = quickTypeInput.text?.toString().orEmpty()
                if (typed.isNotEmpty()) {
                    if (typed.contains("@")) lastTypedEmail = typed.trim()
                    val escaped = org.json.JSONObject.quote(typed)
                    webView.evaluateJavascript(
                        """
                        (function(val){
                          try {
                            var el = document.activeElement;
                            if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) {
                              var inputs = document.querySelectorAll('input:not([type=hidden]):not([disabled])');
                              for (var i = 0; i < inputs.length; i++) {
                                if (inputs[i].offsetParent !== null) { el = inputs[i]; break; }
                              }
                            }
                            if (el) {
                              el.focus();
                              var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                              if (setter && setter.set) { setter.set.call(el, val); } else { el.value = val; }
                              el.dispatchEvent(new Event('input', { bubbles: true }));
                              el.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                          } catch(e) {}
                        })($escaped);
                        """.trimIndent(),
                        null,
                    )
                    quickTypeInput.setText("")
                } else {
                    webView.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }
        }
        val quickTypeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(6))
            addView(quickTypeInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(quickTypeBtn, LinearLayout.LayoutParams(-2, -2))
        }

        fun finishFoxLogin(work: () -> FirefoxVpnEngine.LoginOutcome) {
            activityScope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching(work).getOrElse { err ->
                    FirefoxVpnEngine.LoginOutcome.Failure("خطا در تکمیل ورود: ${err.message}", "Sign-in failed: ${err.message}")
                } }
                runCatching { dialogRef?.dismiss() }
                runCatching { webView.destroy() }
                when (outcome) {
                    is FirefoxVpnEngine.LoginOutcome.Success -> {
                        onChanged()
                        Toast.makeText(
                            this@showFirefoxWebViewLoginDialog,
                            if (isFarsi) "✅ حساب «${outcome.account.email}» با ۵۰ گیگابایت سهمیه ماهانه فعال شد!"
                            else "✅ Activated '${outcome.account.email}' with 50 GB monthly quota!",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is FirefoxVpnEngine.LoginOutcome.Failure -> {
                        Toast.makeText(
                            this@showFirefoxWebViewLoginDialog,
                            if (isFarsi) outcome.messageFa else outcome.messageEn,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    else -> Unit
                }
            }
        }

        // beta238: WebChannel bridge — the session is taken only once it is verified (e-mail code / 2FA done) and the
        // token request is sent by the page itself (no 406 from the anti-bot check). Local storage stays a fallback.
        var channelBusy = false
        val channel = FoxWebChannel.Bridge(webView, object : FoxWebChannel.Bridge.Listener {
            override fun onVerifiedSession(sessionToken: String, email: String) {
                if (completed || isFinishing || isDestroyed) return
                channelBusy = true
                statusBanner.text = if (isFarsi) "✅ ورود تأیید شد؛ در حال گرفتن توکن از خود صفحهٔ موزیلا…"
                else "✅ Sign-in verified; getting the token through Mozilla's page…"
                val request = runCatching { FirefoxVpnEngine.webViewOAuthRequest(sessionToken) }.getOrNull()
                if (request == null) {
                    onTokenRequestFailed(sessionToken, email, "hawk")
                    return
                }
                webView.evaluateJavascript(FoxWebChannel.oauthFetchJs(request.first, request.second), null)
            }

            override fun onTokens(sessionToken: String, email: String, accessToken: String, refreshToken: String) {
                if (completed) return
                completed = true
                runCatching { DiagnosticLogger.info(this@showFirefoxWebViewLoginDialog, "fenix.login.webchannel", "ok") }
                statusBanner.text = if (isFarsi) "✅ توکن گرفته شد! در حال فعال‌سازی سهمیه ۵۰ گیگابایت…"
                else "✅ Token received! Activating the 50 GB quota…"
                finishFoxLogin {
                    FirefoxVpnEngine.completeLoginWithOAuthTokens(
                        this@showFirefoxWebViewLoginDialog, email, accessToken, refreshToken, sessionToken,
                    )
                }
            }

            override fun onWaitingForCode() {
                channelBusy = false
                statusBanner.text = if (isFarsi) "✉️ کد تأیید را که موزیلا به ایمیلتان فرستاد در صفحه وارد کنید؛ بعدش خودکار ادامه می‌دهد."
                else "✉️ Type the confirmation code Mozilla e-mailed you into the page; it continues automatically."
            }

            override fun onTokenRequestFailed(sessionToken: String, email: String, reason: String) {
                if (completed) return
                completed = true
                runCatching { DiagnosticLogger.info(this@showFirefoxWebViewLoginDialog, "fenix.login.webchannel", "fallback=$reason") }
                finishFoxLogin {
                    FirefoxVpnEngine.completeSessionLogin(this@showFirefoxWebViewLoginDialog, sessionToken, email)
                }
            }
        })
        webView.addJavascriptInterface(channel, "catFxaChannel")

        fun checkWebViewForFxaSession() {
            if (completed || channelBusy || isFinishing || isDestroyed) return
            runCatching { webView.evaluateJavascript(FoxWebChannel.HOOK_JS, null) }
            webView.evaluateJavascript(
                """
                (function(){
                  try {
                    var a = window.localStorage.getItem('__fxa_storage.accounts');
                    if (a && a.indexOf('sessionToken') !== -1) return a;
                    for (var i = 0; i < window.localStorage.length; i++) {
                      var k = window.localStorage.key(i);
                      var v = window.localStorage.getItem(k);
                      if (v && v.indexOf('sessionToken') !== -1) return v;
                    }
                    for (var j = 0; j < window.sessionStorage.length; j++) {
                      var sk = window.sessionStorage.key(j);
                      var sv = window.sessionStorage.getItem(sk);
                      if (sv && sv.indexOf('sessionToken') !== -1) return sv;
                    }
                    return '';
                  } catch(e) { return ''; }
                })();
                """.trimIndent(),
            ) { rawValue ->
                if (completed || rawValue.isNullOrBlank() || rawValue == "null" || rawValue == "\"\"") return@evaluateJavascript
                if (channelBusy) return@evaluateJavascript
                // beta238: only a confirmed session — an unconfirmed one (code not typed yet) used to end the dialog.
                val pair = FirefoxVpnEngine.extractVerifiedSessionFromWebViewStorageJson(rawValue) ?: return@evaluateJavascript
                statusBanner.text = if (isFarsi) {
                    "✅ نشست «${pair.second}» دریافت شد! در حال فعال‌سازی سهمیه ۵۰ گیگابایت…"
                } else {
                    "✅ Session for '${pair.second}' captured! Activating 50 GB quota…"
                }
                if (FoxWebChannel.isSessionToken(pair.first)) {
                    channel.adoptSession(pair.first, pair.second)
                } else {
                    completed = true
                    finishFoxLogin {
                        FirefoxVpnEngine.completeSessionLogin(this@showFirefoxWebViewLoginDialog, pair.first, pair.second)
                    }
                }
            }
        }

        fun injectFxaDirectEmailGuidance(prefillEmail: String = lastTypedEmail) {
            val escapedPrefill = org.json.JSONObject.quote(prefillEmail)
            val noteText = org.json.JSONObject.quote(
                if (isFarsi) "⚡ جیمیل (@gmail.com) را در همین کادر بالا وارد کنید و دکمهٔ آبی را بزنید (بدون خطای گوگل)"
                else "⚡ Enter your @gmail.com in the top box above and tap the blue button",
            )
            webView.evaluateJavascript(
                """
                (function(prefill, bannerMsg){
                  try {
                    Object.defineProperty(navigator, 'webdriver', { get: function(){ return false; } });
                    // Capture any email typed by the user in any email field
                    var emailInputs = document.querySelectorAll('input[type=email], input[name=email], input[autocomplete=username]');
                    for (var i = 0; i < emailInputs.length; i++) {
                      var inp = emailInputs[i];
                      if (prefill && !inp.value && window.location.hostname.indexOf('firefox.com') !== -1) {
                        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                        if (setter && setter.set) { setter.set.call(inp, prefill); } else { inp.value = prefill; }
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                      }
                      if (!inp.__catHooked) {
                        inp.__catHooked = true;
                        inp.addEventListener('input', function(ev) {
                          try { window.sessionStorage.setItem('__cat_last_email', ev.target.value || ''); } catch(e){}
                        });
                      }
                    }
                    // On Mozilla FxA page, add a clear hint above third-party buttons so users enter Gmail directly in Mozilla's form
                    if (window.location.hostname.indexOf('firefox.com') !== -1 && !document.getElementById('__cat_fxa_hint')) {
                      var firstInput = emailInputs.length > 0 ? emailInputs[0] : null;
                      if (firstInput && firstInput.parentElement) {
                        var hint = document.createElement('div');
                        hint.id = '__cat_fxa_hint';
                        hint.style.cssText = 'background:#0f2922;color:#5eead4;border:1px solid #14b8a6;border-radius:8px;padding:8px 10px;margin:6px 0;font-size:12px;line-height:1.4;text-align:right;direction:rtl;';
                        hint.textContent = bannerMsg;
                        firstInput.parentElement.insertBefore(hint, firstInput);
                      }
                    }
                  } catch(e) {}
                })($escapedPrefill, $noteText);
                """.trimIndent(),
                null,
            )
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val currentUrl = url.orEmpty()
                // Detect Google's embedded-WebView rejection page (/signin/rejected or disallowed_useragent)
                // and automatically return to Mozilla's direct email sign-in/sign-up flow!
                if (currentUrl.contains("accounts.google.com") &&
                    (currentUrl.contains("/signin/rejected") || currentUrl.contains("disallowed_useragent") || currentUrl.contains("/v3/signin/rejected"))
                ) {
                    statusBanner.setTextColor(0xFFFBBF24.toInt())
                    statusBanner.text = if (isFarsi) {
                        "⚠️ گوگل ورود OAuth داخل برنامه‌ها را محدود کرد! صفحه به فرم مستقیم موزیلا برگشت:\nجیمیل خود را در کادر بالای صفحهٔ موزیلا بنویسید و دکمهٔ آبی را بزنید (کد ۶ رقمی به جیمیلتان ارسال می‌شود)."
                    } else {
                        "⚠️ Google blocked embedded OAuth! Returned to Mozilla's direct email form:\nEnter your Gmail in Mozilla's top box and tap the blue button (a 6-digit code will be sent to your Gmail)."
                    }
                    view?.stopLoading()
                    view?.loadUrl(FoxWebChannel.START_URL)
                    return
                }
                runCatching { view?.evaluateJavascript(FoxWebChannel.HOOK_JS, null) }
                if (FoxWebChannel.isSuccessUrl(currentUrl)) channel.onSuccessPage()
                injectFxaDirectEmailGuidance()
            }

            override fun doUpdateVisitedHistory(view: android.webkit.WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (FoxWebChannel.isSuccessUrl(url)) channel.onSuccessPage()
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url.orEmpty()
                if (currentUrl.contains("accounts.google.com") &&
                    (currentUrl.contains("/signin/rejected") || currentUrl.contains("disallowed_useragent"))
                ) {
                    view?.loadUrl(FoxWebChannel.START_URL)
                    return
                }
                runCatching { view?.evaluateJavascript(FoxWebChannel.HOOK_JS, null) }
                if (FoxWebChannel.isSuccessUrl(currentUrl)) channel.onSuccessPage()
                injectFxaDirectEmailGuidance()
                checkWebViewForFxaSession()
                view?.postDelayed({ checkWebViewForFxaSession() }, 1200L)
                view?.postDelayed({ checkWebViewForFxaSession() }, 2800L)
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBanner)
            addView(quickTypeBar)
            addView(webView)
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (!completed && dialogRef?.isShowing == true) {
                    checkWebViewForFxaSession()
                    mainHandler.postDelayed(this, 1800L)
                }
            }
        }

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🌐 ورود رسمی به حساب فایرفاکس (۵۰ گیگ رایگان)"
                else "🌐 Official Firefox Account Sign-In (50 GB Free)",
            )
            .setView(container)
            .setPositiveButton(if (isFarsi) "✅ وارد شدم (بررسی نشست)" else "✅ I Signed In (Check)") { _, _ ->
                checkWebViewForFxaSession()
            }
            .setNeutralButton(if (isFarsi) "📧 ورود با فرم مستقیم" else "📧 Direct Form") { dlg, _ ->
                completed = true
                dlg.dismiss()
                runCatching { webView.destroy() }
                showFirefoxEmailLoginDialog(onChanged)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                completed = true
                runCatching { webView.destroy() }
            }
            .create()

        dialogRef.setOnDismissListener {
            completed = true
            mainHandler.removeCallbacks(pollRunnable)
        }
        dialogRef.showCatClientDialog {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            )
            window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        // beta238: «I signed in» must not close the dialog (closing it cancelled the very check it asked for).
        runCatching {
            dialogRef.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (channelBusy) {
                    Toast.makeText(this, if (isFarsi) "⏳ در حال تکمیل ورود…" else "⏳ Finishing sign-in…", Toast.LENGTH_SHORT).show()
                } else {
                    checkWebViewForFxaSession()
                }
            }
        }
        webView.loadUrl(FoxWebChannel.START_URL)
        webView.requestFocus()
        mainHandler.postDelayed(pollRunnable, 2000L)
    }

internal fun MainActivity.showFirefoxEmailLoginDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val emailInput = EditText(this).apply {
        hint = if (isFarsi) "ایمیل حساب فایرفاکس (مثلاً user@gmail.com)" else "Firefox Account Email"
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val passInput = EditText(this).apply {
        hint = if (isFarsi) "رمز عبور حساب فایرفاکس" else "Password"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(4))
        addView(emailInput)
        addView(passInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    fun handleLoginOutcome(outcome: FirefoxVpnEngine.LoginOutcome) {
        when (outcome) {
            is FirefoxVpnEngine.LoginOutcome.Success -> {
                onChanged()
                Toast.makeText(
                    this@showFirefoxEmailLoginDialog,
                    if (isFarsi) "✅ حساب «${outcome.account.email}» با ۵۰ گیگابایت سهمیه ماهانه فعال شد!"
                    else "✅ Activated '${outcome.account.email}' with 50 GB monthly quota!",
                    Toast.LENGTH_LONG,
                ).show()
            }
            is FirefoxVpnEngine.LoginOutcome.NeedsEmailVerification -> {
                val isTotp = outcome.verificationMethod.equals("totp-2fa", ignoreCase = true)
                val codeInput = EditText(this@showFirefoxEmailLoginDialog).apply {
                    hint = if (isFarsi) {
                        if (isTotp) "کد ۶ رقمی برنامه Authenticator" else "کد ۶ رقمی ارسال‌شده به ایمیل"
                    } else {
                        if (isTotp) "6-digit Authenticator code" else "6-digit code from email"
                    }
                    setSingleLine(true)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                }
                val codeWrap = LinearLayout(this@showFirefoxEmailLoginDialog).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(10), dp(20), dp(4))
                    addView(codeInput)
                }
                MaterialAlertDialogBuilder(this@showFirefoxEmailLoginDialog)
                    .setTitle(
                        if (isFarsi) {
                            if (isTotp) "🔐 ورود کد دو مرحله‌ای (Authenticator)" else "📩 ورود کد تایید ایمیل فایرفاکس"
                        } else {
                            if (isTotp) "🔐 Enter Authenticator 2FA Code" else "📩 Enter Email Verification Code"
                        },
                    )
                    .setMessage(
                        if (isFarsi) {
                            if (isTotp) "کد ۶ رقمی برنامه Authenticator حساب ${outcome.email} را وارد کنید:"
                            else "فایرفاکس یک کد تایید ۶ رقمی به ایمیل ${outcome.email} فرستاد. آن را وارد کنید:"
                        } else {
                            if (isTotp) "Enter the 6-digit code from your authenticator app for ${outcome.email}:"
                            else "Enter the verification code sent to ${outcome.email}:"
                        },
                    )
                    .setView(codeWrap)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(if (isFarsi) "✅ تایید کد و فعال‌سازی" else "✅ Verify & Activate") { _, _ ->
                        val code = codeInput.text?.toString()?.trim().orEmpty()
                        activityScope.launch {
                            val verifiedOutcome = withContext(Dispatchers.IO) {
                                FirefoxVpnEngine.verifyEmail2faAndCompleteLogin(
                                    this@showFirefoxEmailLoginDialog,
                                    outcome.sessionTokenHex,
                                    code,
                                    outcome.email,
                                    outcome.verificationMethod,
                                )
                            }
                            handleLoginOutcome(verifiedOutcome)
                        }
                    }
                    .create()
                    .showCatClientDialog()
            }
            is FirefoxVpnEngine.LoginOutcome.NeedsUnblockCode -> {
                val unblockInput = EditText(this@showFirefoxEmailLoginDialog).apply {
                    hint = if (isFarsi) "کد ۸ کاراکتری رفع مسدودی (ارسال‌شده به ایمیل)" else "8-character unblock code from email"
                    setSingleLine(true)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                }
                val unblockWrap = LinearLayout(this@showFirefoxEmailLoginDialog).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(10), dp(20), dp(4))
                    addView(unblockInput)
                }
                MaterialAlertDialogBuilder(this@showFirefoxEmailLoginDialog)
                    .setTitle(if (isFarsi) "🛡️ کد تایید امنیتی موزیلا (Unblock Code)" else "🛡️ Mozilla Security Unblock Code")
                    .setMessage(
                        if (isFarsi) "موزیلا برای اطمینان از ورود شما یک کد ۸ کاراکتری به ایمیل ${outcome.email} ارسال کرد. آن را وارد کنید:"
                        else "Mozilla emailed an 8-character unblock code to ${outcome.email}. Enter it below:",
                    )
                    .setView(unblockWrap)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(if (isFarsi) "🚀 ادامه ورود" else "🚀 Continue Sign-In") { _, _ ->
                        val unblockCode = unblockInput.text?.toString()?.trim().orEmpty()
                        activityScope.launch {
                            val retryOutcome = withContext(Dispatchers.IO) {
                                FirefoxVpnEngine.loginWithEmailAndPassword(
                                    this@showFirefoxEmailLoginDialog,
                                    outcome.email,
                                    outcome.password,
                                    unblockCode,
                                )
                            }
                            handleLoginOutcome(retryOutcome)
                        }
                    }
                    .create()
                    .showCatClientDialog()
            }
            is FirefoxVpnEngine.LoginOutcome.Failure -> {
                MaterialAlertDialogBuilder(this@showFirefoxEmailLoginDialog)
                    .setTitle(if (isFarsi) "⚠️ راهنمای ورود به فایرفاکس" else "⚠️ Firefox Sign-In Notice")
                    .setMessage(if (isFarsi) outcome.messageFa else outcome.messageEn)
                    .setPositiveButton(
                        if (isFarsi) "🌐 ورود آسان با صفحه رسمی موزیلا / گوگل" else "🌐 Sign In via Mozilla WebView",
                    ) { _, _ ->
                        showFirefoxWebViewLoginDialog(onChanged)
                    }
                    .setNeutralButton(if (isFarsi) "🔄 تلاش مجدد" else "🔄 Retry") { _, _ ->
                        showFirefoxEmailLoginDialog(onChanged)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
            }
        }
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "📧 ورود به حساب فایرفاکس (دریافت ۵۰ گیگ رایگان ماهانه)"
            else "📧 Sign in to Firefox Account (50 GB/mo Free)",
        )
        .setMessage(
            if (isFarsi) "ایمیل و رمز حساب فایرفاکس خود را وارد کنید (یا اگر اکانت شما با جیمیل/گوگل است، روی دکمه «🌐 صفحه وب موزیلا» بزنید):"
            else "Enter your Firefox Account email and password (or tap '🌐 Mozilla Web' for Google Sign-In):",
        )
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "🌐 صفحه وب موزیلا / گوگل" else "🌐 Mozilla Web / Google") { _, _ ->
            showFirefoxWebViewLoginDialog(onChanged)
        }
        .setPositiveButton(if (isFarsi) "🚀 ورود و فعال‌سازی ۵۰ گیگ" else "🚀 Sign In & Activate 50 GB") { _, _ ->
            val email = emailInput.text?.toString()?.trim().orEmpty()
            val pass = passInput.text?.toString().orEmpty()
            Toast.makeText(
                this,
                if (isFarsi) "⏳ در حال حل چالش Fastly و ورود به حساب فایرفاکس…" else "⏳ Solving Fastly challenge & signing in…",
                Toast.LENGTH_SHORT,
            ).show()
            activityScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    FirefoxVpnEngine.loginWithEmailAndPassword(this@showFirefoxEmailLoginDialog, email, pass)
                }
                handleLoginOutcome(outcome)
            }
        }
        .create()
        .showCatClientDialog()
}

/**
 * Full Firefox VPN (`FENIX`) Hub Dialog with 35-country live ping tester, clean Fastly edge
 * scanner, multi-account manager, and 1-click full-device connection.
 */
internal fun MainActivity.showFirefoxVpnFenixHubDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val options = if (isFarsi) {
        arrayOf(
            "🦊 ۱) اتصال فوری با Cat Fox VPN (روشن کردن تانل کل گوشی همین الان)",
            "🌍 ۲) انتخاب کشور با پینگ زنده روی خط شما (${FirefoxVpnEngine.getAvailableCountries(this).size} کشور موزیلا)",
            "⚡ ۳) تست پینگ زنده همه کشورها و انتخاب خودکار سریع‌ترین کشور روی خط من",
            "👤 ۴) مدیریت حساب‌های ۵۰ گیگابایتی فایرفاکس (افزودن چند اکانت + چرخش خودکار)",
            "🛰️ ۵) اسکن آی‌پی تمیز Fastly برای عبور از نت ملی و فایروال همراه اول / ایرانسل",
            "🛡️ ۶) روشن/خاموش کردن برش بسته TLS در مرز SNI (ضد فیلترینگ Cat Fox VPN)",
            "🔗 ۷) پروتکل تونل: ${FoxTransport.shortFa(FirefoxVpnEngine.readTransport(this))}",
            "🛡️ ۸) اگر ورود موزیلا بسته است: رلهٔ کلادفلر",
        )
    } else {
        arrayOf(
            "🦊 1) Connect Cat Fox VPN Immediately (Full-Device Tunnel Now)",
            "🌍 2) Pick country with live ping on your line (${FirefoxVpnEngine.getAvailableCountries(this).size} Mozilla Fastly locations)",
            "⚡ 3) Live Ping All Countries & Auto-Select Fastest on My ISP",
            "👤 4) Manage 50 GB/mo Firefox Accounts (Multi-Account Auto-Failover)",
            "🛰️ 5) Scan Clean Fastly Edge IP for Net Melli & MCI / Irancell Bypass",
            "🛡️ 6) Toggle Anti-DPI TLS ClientHello Split @ SNI",
            "🔗 7) Tunnel protocol: ${FoxTransport.shortEn(FirefoxVpnEngine.readTransport(this))}",
            "🛡️ 8) If Mozilla sign-in is blocked: Cloudflare relay",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🦊 مرکز کامل Cat Fox VPN"
            else "🦊 Cat Fox VPN Hub (50 GB/mo Free)",
        )
        .setItems(options) { _, which ->
            when (which) {
                0 -> connectFirefoxVpnDirectly(onChanged)
                1 -> showFirefoxCountryPickerDialog(onChanged)
                2 -> {
                    Toast.makeText(
                        this,
                        if (isFarsi) "⏳ در حال تست پینگ زنده سرورهای Fastly فایرفاکس در کشورهای مختلف…"
                        else "⏳ Probing live latency across Firefox Fastly country nodes…",
                        Toast.LENGTH_SHORT,
                    ).show()
                    activityScope.launch {
                        val pings = withContext(Dispatchers.IO) {
                            FirefoxVpnEngine.pingCountries(this@showFirefoxVpnFenixHubDialog, maxCountries = 40, timeoutMs = 850)
                        }
                        val best = pings.firstOrNull { it.reachable } ?: pings.firstOrNull()
                        val report = buildString {
                            if (isFarsi) {
                                appendLine("⚡ نتیجه تست پینگ واقعی کشورهای Cat Fox VPN روی خط شما:")
                                appendLine("────────────────────────")
                            } else {
                                appendLine("⚡ Live Cat Fox VPN Country Ping Results:")
                                appendLine("────────────────────────")
                            }
                            pings.forEachIndexed { idx, p ->
                                val c = p.country
                                val name = if (isFarsi) c.nameFa else c.nameEn
                                val st = if (p.reachable) "🟢 ${p.rttMs} ms" else "⚪ Timeout"
                                appendLine("${idx + 1}. ${c.flag} $name (${c.code}) — $st")
                            }
                        }
                        MaterialAlertDialogBuilder(this@showFirefoxVpnFenixHubDialog)
                            .setTitle(
                                if (isFarsi) "⚡ سریع‌ترین کشور: ${best?.country?.flag ?: "🇩🇪"} ${best?.country?.nameFa ?: "آلمان"}"
                                else "⚡ Fastest: ${best?.country?.flag ?: "🇩🇪"} ${best?.country?.nameEn ?: "Germany"}",
                            )
                            .setMessage(report.trim())
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(
                                if (isFarsi) "🦊 انتخاب سریع‌ترین و اتصال فوری" else "🦊 Select Fastest & Connect",
                            ) { _, _ ->
                                if (best != null) {
                                    FirefoxVpnEngine.saveSelectedCountryCode(this@showFirefoxVpnFenixHubDialog, best.country.code)
                                }
                                onChanged()
                                connectFirefoxVpnDirectly(onChanged)
                            }
                            .create()
                            .showCatClientDialog()
                    }
                }
                3 -> showFirefoxAccountManagerDialog(onChanged)
                4 -> {
                    Toast.makeText(
                        this,
                        if (isFarsi) "⏳ تست واقعی آی‌پی‌های Fastly (اتصال + TLS سرور موزیلا روی پورت 2499) روی اینترنت شما…"
                        else "⏳ Scanning clean Fastly Anycast edge IPs on your ISP…",
                        Toast.LENGTH_SHORT,
                    ).show()
                    activityScope.launch {
                        val bestEdge = withContext(Dispatchers.IO) {
                            FirefoxVpnEngine.findBestCleanFastlyEdgeIp(timeoutMs = 900)
                        }
                        if (bestEdge != null) {
                            FirefoxVpnEngine.saveCleanFastlyEdgeIp(this@showFirefoxVpnFenixHubDialog, bestEdge.first)
                            onChanged()
                            Toast.makeText(
                                this@showFirefoxVpnFenixHubDialog,
                                if (isFarsi) "✅ آی‌پی تمیز Fastly پیدا و فعال شد: ${bestEdge.first} (پینگ: ${bestEdge.second} ms)"
                                else "✅ Clean Fastly Edge IP activated: ${bestEdge.first} (${bestEdge.second} ms)",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            // Nothing passed a real TLS test: keep "auto" (direct node by name), never a guessed IP.
                            FirefoxVpnEngine.saveCleanFastlyEdgeIp(this@showFirefoxVpnFenixHubDialog, "")
                            onChanged()
                            Toast.makeText(
                                this@showFirefoxVpnFenixHubDialog,
                                if (isFarsi) "⚠️ هیچ آی‌پی‌ای تست واقعی (اتصال + TLS سرور موزیلا روی پورت 2499) را رد نکرد؛ حالت خودکار ماند"
                                else "⚠️ No IP passed the real test (connect + TLS to the Mozilla node on 2499); kept Auto",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                5 -> {
                    val next = !FirefoxVpnEngine.isTlsFragmentEnabled(this)
                    FirefoxVpnEngine.setTlsFragmentEnabled(this, next)
                    onChanged()
                    Toast.makeText(
                        this,
                        if (isFarsi) {
                            if (next) "🛡️ برش بسته TLS در مرز SNI برای Cat Fox VPN روشن شد ✅"
                            else "⚪ برش بسته TLS Cat Fox VPN خاموش شد"
                        } else {
                            if (next) "🛡️ Cat Fox VPN TLS SNI Split enabled ✅"
                            else "⚪ Cat Fox VPN TLS SNI Split disabled"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                6 -> showFoxTransportDialog(onChanged)
                7 -> showFoxMozillaRelayDialog(onChanged)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

/**
 * Connects the entire device through **Firefox Browser VPN (`FENIX`)** with 1 tap:
 * 1) Selects the best active Firefox Account (auto-rotating if >= 95% of 50 GB used)
 * 2) Resolves the chosen country (or auto-pings to pick the fastest Fastly location)
 * 3) Starts the local FENIX Anti-DPI proxy on `127.0.0.1:10829`
 * 4) Saves & selects the `🦊 فایرفاکس VPN (FENIX)` subscription and starts the VPN tunnel!
 */
internal fun MainActivity.connectFirefoxVpnDirectly(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val initialAccounts = FirefoxVpnEngine.loadAccounts(this)
    if (initialAccounts.isEmpty()) {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🦊 ابتدا وارد حساب رایگان فایرفاکس شوید (۵۰ گیگ در ماه)"
                else "🦊 Sign in to a Free Firefox Account First (50 GB/mo)",
            )
            .setMessage(
                if (isFarsi) {
                    "برای اتصال به سرورهای Fastly Cat Fox VPN، نیاز به یک حساب رایگان فایرفاکس دارید (هر حساب ماهانه ۵۰ گیگابایت اینترنت رایگان می‌دهد).\n\nروش ورود را انتخاب کنید تا بلافاصله پس از ورود متصل شوید:"
                } else {
                    "Cat Fox VPN requires a free Firefox Account (each account provides 50 GB/month on Fastly).\n\nChoose how to sign in and connect immediately:"
                },
            )
            .setPositiveButton(
                if (isFarsi) "🌐 ورود آسان با صفحه موزیلا / گوگل ⭐" else "🌐 Easy Mozilla / Google Sign-In ⭐",
            ) { _, _ ->
                showFirefoxWebViewLoginDialog {
                    onChanged()
                    if (FirefoxVpnEngine.loadAccounts(this).isNotEmpty()) {
                        connectFirefoxVpnDirectly(onChanged)
                    }
                }
            }
            .setNeutralButton(
                if (isFarsi) "📧 ایمیل و رمز عبور" else "📧 Email & Password",
            ) { _, _ ->
                showFirefoxEmailLoginDialog {
                    onChanged()
                    if (FirefoxVpnEngine.loadAccounts(this).isNotEmpty()) {
                        connectFirefoxVpnDirectly(onChanged)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
        return
    }

    Toast.makeText(
        this,
        if (isFarsi) "🦊 در حال آماده‌سازی تانل Cat Fox VPN روی شبکه Fastly و اتصال کل گوشی…"
        else "🦊 Preparing Cat Fox VPN over Fastly Edge & connecting…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        runCatching {
            val (chosenCountry, activeAccount, localPort) = withContext(Dispatchers.IO) {
                val accounts = FirefoxVpnEngine.loadAccounts(this@connectFirefoxVpnDirectly)
                val bestAcc = FirefoxVpnEngine.selectBestActiveAccount(accounts)?.let { acc ->
                    if (acc.hasUnexpiredProxyPass()) acc
                    else runCatching { FirefoxVpnEngine.refreshAndActivateAccountPass(this@connectFirefoxVpnDirectly, acc) }.getOrDefault(acc)
                }
                val selCode = FirefoxVpnEngine.readSelectedCountryCode(this@connectFirefoxVpnDirectly)
                val allCountries = FirefoxVpnEngine.getAvailableCountries(this@connectFirefoxVpnDirectly)
                val country = if (selCode == FirefoxVpnEngine.COUNTRY_FASTEST) {
                    val pings = FirefoxVpnEngine.pingCountries(this@connectFirefoxVpnDirectly, maxCountries = 8, timeoutMs = 700)
                    pings.firstOrNull { it.reachable }?.country
                        ?: allCountries.firstOrNull { it.code == FirefoxVpnEngine.COUNTRY_RECOMMENDED } ?: allCountries.first()
                } else {
                    allCountries.firstOrNull { it.code.equals(selCode, ignoreCase = true) }
                        ?: allCountries.firstOrNull { it.code == FirefoxVpnEngine.COUNTRY_RECOMMENDED } ?: allCountries.first()
                }
                val cleanEdge = FirefoxVpnEngine.readCleanFastlyEdgeIp(this@connectFirefoxVpnDirectly)
                val frag = FirefoxVpnEngine.isTlsFragmentEnabled(this@connectFirefoxVpnDirectly)
                val port = FirefoxVpnEngine.startOrUpdateLocalFenixProxy(
                    context = this@connectFirefoxVpnDirectly,
                    country = country,
                    bearerPass = bestAcc?.proxyPassToken.orEmpty(),
                    cleanEdgeIp = cleanEdge,
                    fragmentTls = frag,
                )
                Triple(country, bestAcc, port)
            }

            if (activeAccount == null || activeAccount.proxyPassToken.isBlank()) {
                // Say WHY Mozilla gave no pass (expired sign-in, quota, region, server trouble) instead of a guess.
                val why = FirefoxVpnEngine.passErrorText(isFarsi)
                Toast.makeText(
                    this@connectFirefoxVpnDirectly,
                    if (isFarsi) "⚠️ موزیلا مجوز اتصال (۵۰ گیگ) رو نداد: " + why.ifBlank { "دوباره وارد حساب فایرفاکس شوید." }
                    else "⚠️ Mozilla gave no proxy pass: " + why.ifBlank { "please sign in to the Firefox account again." },
                    Toast.LENGTH_LONG,
                ).show()
                showFirefoxAccountManagerDialog(onChanged)
                return@launch
            }

            // beta250: ONE «🦊 Cat Fox VPN» subscription with every country as its own row (Mozilla's pick first).
            // Each row
            // names its country in its SOCKS user, so it really leaves through that country and its ping is real.
            val cleanIp = FirefoxVpnEngine.readCleanFastlyEdgeIp(this@connectFirefoxVpnDirectly)
            val frag = FirefoxVpnEngine.isTlsFragmentEnabled(this@connectFirefoxVpnDirectly)
            val countries = FoxCountryRouting.ordered(
                FirefoxVpnEngine.getAvailableCountries(this@connectFirefoxVpnDirectly) + chosenCountry,
                { it.code },
                { false },
            )
            val rows = countries.map { c ->
                FirefoxVpnEngine.buildFenixTunnelUri(
                    country = c,
                    localPort = localPort,
                    accountEmail = "",
                    cleanFastlyIp = if (c.code == chosenCountry.code) cleanIp else "",
                    tlsFragment = frag,
                )
            }
            val subTitle = FoxCountryRouting.SUBSCRIPTION_NAME
            val saved = withContext(Dispatchers.IO) {
                // Older builds made «🦊 Cat Fox VPN (🇩🇪 DE)», «… (🇳🇱 NL)» … — fold them all into the one subscription.
                val olds = userSubscriptionManager.list().filter { it.name.startsWith(subTitle) }
                val keep = olds.firstOrNull()
                olds.drop(1).forEach { runCatching { userSubscriptionManager.delete(it.id) } }
                val text = FoxCountryRouting.subscriptionText(rows)
                if (keep != null) userSubscriptionManager.update(keep.id, subTitle, text) else userSubscriptionManager.add(subTitle, text)
            }
            onChanged()
            renderSubscriptions()

            val countryName = if (isFarsi) chosenCountry.nameFa else chosenCountry.nameEn
            Toast.makeText(
                this@connectFirefoxVpnDirectly,
                if (isFarsi) "🦊 Cat Fox VPN: ${rows.size} کشور در یک اشتراک — اتصال روی «${chosenCountry.flag} $countryName»؛ بعد از وصل شدن پینگ همهٔ کشورها گرفته می‌شود."
                else "🦊 Cat Fox VPN: ${rows.size} countries in one subscription — connecting on '${chosenCountry.flag} $countryName'; every country is pinged once connected.",
                Toast.LENGTH_LONG,
            ).show()
            connectFreeSubscription(saved.id) { profiles ->
                profiles.getOrNull(FoxCountryRouting.pickRow(profiles.map { it.shareLink.orEmpty() }, chosenCountry.code))
            }
        }.onFailure { err ->
            Toast.makeText(
                this@connectFirefoxVpnDirectly,
                if (isFarsi) "خطا در اتصال Cat Fox VPN: ${err.message}"
                else "Cat Fox VPN connection error: ${err.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.showFirefoxCountryPickerDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val pingByCode = java.util.concurrent.ConcurrentHashMap<String, FirefoxVpnEngine.CountryPingResult>()
    FirefoxVpnEngine.lastCountryPings.forEach { (code, r) -> pingByCode[code.uppercase(java.util.Locale.US)] = r }
    val display = FoxCountryRouting.ordered(
        FirefoxVpnEngine.getAvailableCountries(this),
        { it.code },
        { false },
    ).toMutableList()
    fun autoLine(): String = if (isFarsi) {
        "⚡ سریع‌ترین کشور خودکار (پینگ زنده روی همین اینترنت)"
    } else {
        "⚡ Fastest country (live ping on this network)"
    }
    fun countryLine(c: FirefoxVpnEngine.FirefoxVpnCountry): String {
        val name = if (isFarsi) c.nameFa else c.nameEn
        val extra = if (c.extraEndpoints.isEmpty()) {
            ""
        } else if (isFarsi) {
            " · +${c.extraEndpoints.size} سرور"
        } else {
            " · +${c.extraEndpoints.size} servers"
        }
        val ping = pingByCode[c.code.uppercase(java.util.Locale.US)]
        val ms = when {
            ping == null -> if (isFarsi) LiveNetPing.waitingFa() else LiveNetPing.waitingEn()
            ping.reachable -> if (isFarsi) LiveNetPing.labelFa(ping.rttMs) else LiveNetPing.labelEn(ping.rttMs)
            else -> if (isFarsi) LiveNetPing.labelFa(null) else LiveNetPing.labelEn(null)
        }
        return "${c.flag} $name · $ms · ${c.exactEndpoint}$extra"
    }
    val labels = ArrayList<String>(1 + display.size)
    fun rebuildLabels() {
        labels.clear()
        labels += autoLine()
        display.forEach { labels += countryLine(it) }
    }
    rebuildLabels()
    val adapter = ArrayAdapter(this, android.R.layout.select_dialog_singlechoice, ArrayList(labels))
    var dialog: AlertDialog? = null
    fun checkedIndex(): Int {
        val current = FirefoxVpnEngine.readSelectedCountryCode(this)
        if (current.equals(FirefoxVpnEngine.COUNTRY_FASTEST, ignoreCase = true)) return 0
        val i = display.indexOfFirst { it.code.equals(current, ignoreCase = true) }
        return if (i >= 0) i + 1 else 0
    }
    fun pushLabels() {
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        dialog?.listView?.setItemChecked(checkedIndex(), true)
    }
    var pingJob: Job? = null
    val shown = MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🌍 انتخاب کشور Cat Fox VPN — پینگ زنده روی خط شما · ${display.size} کشور"
            else "🌍 Cat Fox VPN country — live ping on your line · ${display.size} locations",
        )
        .setSingleChoiceItems(adapter, checkedIndex()) { dlg, idx ->
            dlg.dismiss()
            val chosenCode = if (idx <= 0) FirefoxVpnEngine.COUNTRY_FASTEST else display.getOrNull(idx - 1)?.code
                ?: FirefoxVpnEngine.COUNTRY_FASTEST
            FirefoxVpnEngine.saveSelectedCountryCode(this, chosenCode)
            onChanged()
            val ping = pingByCode[chosenCode.uppercase(java.util.Locale.US)]
            val extra = when {
                chosenCode == FirefoxVpnEngine.COUNTRY_FASTEST -> ""
                ping == null -> if (isFarsi) " (پینگ هنوز نیامده)" else " (ping still running)"
                ping.reachable -> if (isFarsi) " · ${ping.rttMs} ms روی خط شما" else " · ${ping.rttMs} ms on your line"
                else -> if (isFarsi) " · روی این خط بسته" else " · closed on this line"
            }
            val shown = labels.getOrNull(idx)?.substringBefore(" ·") ?: chosenCode
            Toast.makeText(
                this,
                if (isFarsi) "✅ کشور Cat Fox VPN روی «$shown» تنظیم شد$extra"
                else "✅ Cat Fox VPN country set to '$shown'$extra",
                Toast.LENGTH_SHORT,
            ).show()
        }
        .setNeutralButton(if (isFarsi) "🔄 دریافت آخرین لیست سرورهای موزیلا" else "🔄 Sync Mozilla Servers") { _, _ ->
            activityScope.launch {
                val synced = withContext(Dispatchers.IO) {
                    FirefoxVpnEngine.syncRemoteSettingsCountries(this@showFirefoxCountryPickerDialog)
                }
                onChanged()
                Toast.makeText(
                    this@showFirefoxCountryPickerDialog,
                    if (isFarsi) "✅ لیست ${synced.size} کشور از سرور موزیلا بروزرسانی شد"
                    else "✅ Synced ${synced.size} countries from Mozilla Remote Settings",
                    Toast.LENGTH_SHORT,
                ).show()
                showFirefoxCountryPickerDialog(onChanged)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog = shown
    shown.setOnDismissListener { pingJob?.cancel() }
    shown.showCatClientDialog()
    pingJob = activityScope.launch(Dispatchers.IO) {
        FirefoxVpnEngine.pingCountries(this@showFirefoxCountryPickerDialog, maxCountries = 40, timeoutMs = 850) { r ->
            val code = r.country.code.uppercase(java.util.Locale.US)
            if (code == "??") return@pingCountries
            pingByCode[code] = r
            runOnUiThread {
                if (!shown.isShowing) return@runOnUiThread
                val i = display.indexOfFirst { it.code.equals(code, ignoreCase = true) }
                if (i >= 0) {
                    labels[i + 1] = countryLine(display[i])
                    pushLabels()
                }
            }
        }
        val sorted = display.sortedWith(
            compareBy(
                { pingByCode[it.code.uppercase(java.util.Locale.US)]?.reachable != true },
                { pingByCode[it.code.uppercase(java.util.Locale.US)]?.rttMs ?: 9_999L },
            ),
        )
        withContext(Dispatchers.Main) {
            if (!shown.isShowing) return@withContext
            display.clear()
            display.addAll(sorted)
            rebuildLabels()
            pushLabels()
        }
    }
}

internal fun MainActivity.showFoxTransportDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val modes = FoxTransport.MODES
    val labels = modes.map { if (isFarsi) FoxTransport.labelFa(it) else FoxTransport.labelEn(it) }.toTypedArray()
    val current = FirefoxVpnEngine.readTransport(this)
    val checked = modes.indexOf(current).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "🔗 پروتکل تونل Cat Fox VPN" else "🔗 Cat Fox VPN tunnel protocol")
        .setSingleChoiceItems(labels, checked) { dlg, idx ->
            dlg.dismiss()
            val mode = modes[idx]
            FirefoxVpnEngine.saveTransport(this, mode)
            onChanged()
            Toast.makeText(
                this,
                if (isFarsi) "✅ پروتکل: ${FoxTransport.shortFa(mode)}"
                else "✅ Protocol: ${FoxTransport.shortEn(mode)}",
                Toast.LENGTH_SHORT,
            ).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showFoxMozillaRelayDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val input = EditText(this).apply {
        hint = "https://….workers.dev/?secret=…"
        setText(FirefoxVpnEngine.readMozillaRelayUrl(this@showFoxMozillaRelayDialog))
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 13f
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
        addView(
            TextView(this@showFoxMozillaRelayDialog).apply {
                text = t(
                    "فقط درخواست‌های ورود موزیلا از رله می‌روند، نه ترافیک VPN. ورکر را در کلادفلر بگذار (دامنهٔ خودت هم می‌شود؛ workers.dev لازم نیست)، رمز را در کد و در لینک یکی کن، بعد لینک را اینجا بچسبان.",
                    "Only Mozilla sign-in goes through the relay, not VPN traffic. Paste the Worker in Cloudflare (your own domain is fine; workers.dev is not required), use the same secret in the script and the URL, then paste the URL here.",
                )
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(0f, 1.15f)
            },
        )
        addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(
            MaterialButton(this@showFoxMozillaRelayDialog).apply {
                text = t("📋 کپی اسکریپت ورکر", "📋 Copy Worker script")
                isAllCaps = false
                setOnClickListener {
                    val secret = FoxMozillaRelay.newSecret()
                    val script = FoxMozillaRelay.workerScript(secret)
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Cat Fox relay", script))
                    if (input.text.isNullOrBlank()) {
                        input.setText("https://YOUR-WORKER.YOUR-ACCOUNT.workers.dev/?secret=$secret")
                    }
                    Toast.makeText(
                        this@showFoxMozillaRelayDialog,
                        t("اسکریپت کپی شد — SECRET را در لینک هم بگذار", "Script copied — put the same SECRET in the URL"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(t("🛡️ رلهٔ ورود موزیلا", "🛡️ Mozilla sign-in relay"))
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(t("تست", "Test")) { _, _ ->
            val typed = input.text?.toString().orEmpty()
            Toast.makeText(this, t("⏳ تست رله…", "⏳ Testing relay…"), Toast.LENGTH_SHORT).show()
            activityScope.launch {
                val probe = withContext(Dispatchers.IO) { FirefoxVpnEngine.probeMozillaRelay(typed) }
                Toast.makeText(
                    this@showFoxMozillaRelayDialog,
                    if (probe.ok) t("✅ رله آماده است", "✅ Relay is ready")
                    else t("رله جواب نداد: ${probe.detail}", "Relay did not answer: ${probe.detail}"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        .setPositiveButton(t("ذخیره", "Save")) { _, _ ->
            val typed = input.text?.toString().orEmpty().trim()
            if (typed.isEmpty()) {
                FirefoxVpnEngine.saveMozillaRelayUrl(this, "")
                onChanged()
                Toast.makeText(this, t("رله خاموش شد", "Relay cleared"), Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (FoxMozillaRelay.normalize(typed) == null) {
                Toast.makeText(
                    this,
                    t("لینک باید https باشد و ?secret= داشته باشد", "URL must be https and include ?secret="),
                    Toast.LENGTH_LONG,
                ).show()
                return@setPositiveButton
            }
            FirefoxVpnEngine.saveMozillaRelayUrl(this, typed)
            onChanged()
            Toast.makeText(
                this,
                t("رله ذخیره شد: ${FoxMozillaRelay.redact(typed)}", "Relay saved: ${FoxMozillaRelay.redact(typed)}"),
                Toast.LENGTH_LONG,
            ).show()
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showFirefoxTokenImportDialog(onChanged: () -> Unit = {}) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val labelInput = EditText(this).apply {
        hint = if (isFarsi) "نام یا ایمیل دلخواه (مثلاً اکانت ۱)" else "Label or Email (e.g. Account 1)"
        setSingleLine(true)
    }
    val tokenInput = EditText(this).apply {
        hint = if (isFarsi) "توکن Refresh / Access / ProxyPass JWT (eyJ...) یا JSON" else "Refresh/Access Token, ProxyPass JWT (eyJ...) or JSON"
        minLines = 3
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(4))
        addView(labelInput)
        addView(tokenInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🔑 افزودن سریع حساب با توکن یا JSON"
            else "🔑 Quick Add Account via Token or JSON",
        )
        .setMessage(
            if (isFarsi) "اگر توکن حساب فایرفاکس را از مرورگر فایرفاکس کپی کرده‌اید، اینجا قرار دهید تا بلافاصله ۵۰ گیگابایت فعال شود:"
            else "Paste your Firefox OAuth token, ProxyPass JWT (eyJ...), or account JSON:",
        )
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (isFarsi) "💾 ذخیره و فعال‌سازی" else "💾 Save & Activate") { _, _ ->
            val label = labelInput.text?.toString()?.trim().orEmpty()
            val raw = tokenInput.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(
                    this,
                    if (isFarsi) "لطفاً توکن را وارد کنید" else "Please paste the token",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setPositiveButton
            }
            activityScope.launch {
                val added = withContext(Dispatchers.IO) {
                    FirefoxVpnEngine.addAccountFromTokenOrJson(this@showFirefoxTokenImportDialog, label, raw)
                }
                if (added != null) {
                    onChanged()
                    Toast.makeText(
                        this@showFirefoxTokenImportDialog,
                        if (isFarsi) "✅ حساب «${added.email}» اضافه شد (${FirefoxVpnEngine.formatQuotaSummaryFa(added)})"
                        else "✅ Added '${added.email}' (${FirefoxVpnEngine.formatQuotaSummaryEn(added)})",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        .create()
        .showCatClientDialog()
}
