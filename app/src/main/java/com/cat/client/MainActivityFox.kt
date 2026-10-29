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
                "ترافیک کل گوشی روی سرورهای Fastly مرورگر فایرفاکس (غیرکلادفلر و باز در ایران) — سرور پیشنهادی و کشورهای رایگان + پشتیبانی از چند اکانت همزمان با چرخش خودکار ۵۰ گیگابایت و برش بسته TLS ضد فیلترینگ."
            } else {
                "Full-device VPN over Mozilla's Fastly edge (Recommended + free countries) — 50 GB/month per free Firefox account with multi-account auto-failover and Anti-DPI TLS splitting."
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
                "${c.flag} ${if (isFarsi) c.nameFa else c.nameEn}"
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
            // Use the device's genuine Chromium UA stripped of '; wv' and 'Version/4.0' so
            // navigator.userAgent matches the Blink engine & Sec-CH-UA headers (preventing mismatch blocks).
            val rawUa = runCatching { android.webkit.WebSettings.getDefaultUserAgent(this@showFirefoxWebViewLoginDialog) }
                .getOrDefault("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            settings.userAgentString = rawUa
                .replace("; wv)", ")")
                .replace("; wv;", ";")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
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

        fun checkWebViewForFxaSession() {
            if (completed || isFinishing || isDestroyed) return
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
                val pair = FirefoxVpnEngine.extractSessionFromWebViewStorageJson(rawValue) ?: return@evaluateJavascript
                completed = true
                statusBanner.text = if (isFarsi) {
                    "✅ نشست «${pair.second}» دریافت شد! در حال فعال‌سازی سهمیه ۵۰ گیگابایت…"
                } else {
                    "✅ Session for '${pair.second}' captured! Activating 50 GB quota…"
                }
                activityScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        FirefoxVpnEngine.loginFromWebViewStorageJson(this@showFirefoxWebViewLoginDialog, rawValue)
                    }
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
                    view?.loadUrl("https://accounts.firefox.com/")
                    return
                }
                injectFxaDirectEmailGuidance()
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url.orEmpty()
                if (currentUrl.contains("accounts.google.com") &&
                    (currentUrl.contains("/signin/rejected") || currentUrl.contains("disallowed_useragent"))
                ) {
                    view?.loadUrl("https://accounts.firefox.com/")
                    return
                }
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
        webView.loadUrl("https://accounts.firefox.com/")
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
            "🌍 ۲) انتخاب کشور (✅ رایگان‌ها اول؛ 🔒 قفل‌ها خودکار میرن رو پیشنهادی)",
            "⚡ ۳) تست پینگ زنده همه کشورها و انتخاب خودکار سریع‌ترین کشور روی خط من",
            "👤 ۴) مدیریت حساب‌های ۵۰ گیگابایتی فایرفاکس (افزودن چند اکانت + چرخش خودکار)",
            "🛰️ ۵) اسکن آی‌پی تمیز Fastly برای عبور از نت ملی و فایروال همراه اول / ایرانسل",
            "🛡️ ۶) روشن/خاموش کردن برش بسته TLS در مرز SNI (ضد فیلترینگ Cat Fox VPN)",
        )
    } else {
        arrayOf(
            "🦊 1) Connect Cat Fox VPN Immediately (Full-Device Tunnel Now)",
            "🌍 2) Select Exit Country from 35 Mozilla Fastly Locations",
            "⚡ 3) Live Ping All Countries & Auto-Select Fastest on My ISP",
            "👤 4) Manage 50 GB/mo Firefox Accounts (Multi-Account Auto-Failover)",
            "🛰️ 5) Scan Clean Fastly Edge IP for Net Melli & MCI / Irancell Bypass",
            "🛡️ 6) Toggle Anti-DPI TLS ClientHello Split @ SNI",
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
                            FirefoxVpnEngine.pingCountries(this@showFirefoxVpnFenixHubDialog, maxCountries = 14, timeoutMs = 850)
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

            val fenixUri = FirefoxVpnEngine.buildFenixTunnelUri(
                country = chosenCountry,
                localPort = localPort,
                accountEmail = activeAccount.email,
                cleanFastlyIp = FirefoxVpnEngine.readCleanFastlyEdgeIp(this@connectFirefoxVpnDirectly),
                tlsFragment = FirefoxVpnEngine.isTlsFragmentEnabled(this@connectFirefoxVpnDirectly),
            )

            val subTitle = "🦊 Cat Fox VPN (${chosenCountry.flag} ${chosenCountry.code})"
            val existing = userSubscriptionManager.list().firstOrNull {
                it.name.startsWith("🦊 Cat Fox VPN")
            }
            val saved = withContext(Dispatchers.IO) {
                if (existing != null) {
                    userSubscriptionManager.update(existing.id, subTitle, fenixUri)
                } else {
                    userSubscriptionManager.add(subTitle, fenixUri)
                }
            }
            userSubscriptionManager.select(saved.id)

            val curAether = aetherPreferenceStore.read()
            if (curAether.enabled) {
                aetherPreferenceStore.save(curAether.copy(enabled = false))
            }
            orbotEnginePreferenceStore.setEnabled(false)

            onChanged()
            renderSubscriptions()
            onSubscriptionSelected()
            renderAdvancedControls()

            val countryName = if (isFarsi) chosenCountry.nameFa else chosenCountry.nameEn
            Toast.makeText(
                this@connectFirefoxVpnDirectly,
                if (isFarsi) "🦊 Cat Fox VPN روی کشور «${chosenCountry.flag} $countryName» فعال شد — در حال اتصال…"
                else "🦊 Cat Fox VPN active on '${chosenCountry.flag} $countryName' — connecting…",
                Toast.LENGTH_LONG,
            ).show()

            openScreen(SCREEN_HOME)
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            } else {
                beginConnectFlow(Actions.CONNECT)
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
    // Free (unlocked) locations first; locked ones answer 403 for a free account and fall back to «Recommended».
    val countries = FirefoxVpnEngine.getAvailableCountries(this).let { all ->
        all.filter { !it.locked } + all.filter { it.locked }
    }
    val codes = listOf(FirefoxVpnEngine.COUNTRY_FASTEST) + countries.map { it.code }
    val labels = buildList {
        add(
            if (isFarsi) "⚡ سریع‌ترین کشور خودکار (تست پینگ لحظه‌ای هنگام اتصال)"
            else "⚡ Fastest Country Automatically (Live Ping on Connect)",
        )
        countries.forEach { c ->
            val name = if (isFarsi) c.nameFa else c.nameEn
            val tag = when {
                !c.locked -> if (isFarsi) "✅ رایگان" else "✅ free"
                isFarsi -> "🔒 قفل برای حساب رایگان (خودکار میره رو پیشنهادی)"
                else -> "🔒 locked for free accounts (falls back to Recommended)"
            }
            add("${c.flag} $name · $tag · ${c.exactEndpoint}")
        }
    }.toTypedArray()
    val currentCode = FirefoxVpnEngine.readSelectedCountryCode(this)
    val checkedIdx = codes.indexOfFirst { it.equals(currentCode, ignoreCase = true) }.coerceAtLeast(0)

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🌍 انتخاب کشور Cat Fox VPN — ${countries.count { !it.locked }} کشور رایگان از ${countries.size}"
            else "🌍 Cat Fox VPN country — ${countries.count { !it.locked }} free of ${countries.size}",
        )
        .setSingleChoiceItems(labels, checkedIdx) { dlg, idx ->
            dlg.dismiss()
            val chosenCode = codes[idx]
            FirefoxVpnEngine.saveSelectedCountryCode(this, chosenCode)
            onChanged()
            Toast.makeText(
                this,
                if (isFarsi) "✅ کشور Cat Fox VPN روی «${labels[idx].substringBefore(" ·")}» تنظیم شد"
                else "✅ Cat Fox VPN country set to '${labels[idx].substringBefore(" ·")}'",
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
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
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
