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
import com.cat.client.MainActivity.Companion.SCREEN_SCANNER

// Cloud tab: panel deployment, history, secrets, installers.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/** Worker / Pages / Railway targets are user-owned HTTPS origins; no secret is stored here. */
internal fun MainActivity.addDeploymentProfilesSection(body: LinearLayout) {
    val deploymentPrefs = getSharedPreferences("deployment_profiles", MODE_PRIVATE)
    val store = DeploymentProfileStore(deploymentPrefs)
    val measurementStore = DeploymentMeasurementStore(deploymentPrefs)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = glassSurfaceDrawable(radiusDp = 16)
    }
    card.addView(TextView(this).apply {
        setText(R.string.deployment_profiles_title)
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
    })
    val input = EditText(this).apply {
        hint = getString(R.string.deployment_profiles_hint)
        textSize = 12.5f
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
    }
    card.addView(input, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun networkKey(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork?.toString()
    }
    fun captive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    fun render() {
        list.removeAllViews()
        store.read().forEach { profile ->
            val row = MaterialButton(this).apply {
                text = "${profile.target.name} · ${profile.name}"
                textSize = 11.5f
                isAllCaps = false
                setTextColor(TEXT_PRIMARY)
                backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 24))
                setOnClickListener {
                    isEnabled = false
                    val before = networkKey()
                    activityScope.launch(Dispatchers.IO) {
                        val result = DeploymentProfileProbe().execute(profile, before, ::networkKey, ::captive)
                        measurementStore.record(result, store.read())
                        mainHandler.post {
                            if (isDestroyed) return@post
                            isEnabled = true
                            text = "${profile.target.name} · ${result.verdict.name} · ${result.latencyMs} ms"
                        }
                    }
                }
                setOnLongClickListener { store.remove(profile.id); render(); true }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        }
    }
    card.addView(MaterialButton(this).apply {
        setText(R.string.deployment_profiles_add)
        isAllCaps = false
        setOnClickListener {
            val url = input.text?.toString().orEmpty().trim()
            val target = DeploymentTargetPolicy.infer(url)
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            if (target == null || host.isBlank()) {
                Toast.makeText(this@addDeploymentProfilesSection, R.string.deployment_profiles_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val id = "${target.name.lowercase()}-${host.lowercase().hashCode().toUInt()}"
            if (store.upsert(DeploymentProfile(id, host, target, url))) {
                input.text?.clear()
                render()
            }
        }
    }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
    card.addView(MaterialButton(this).apply {
        setText(R.string.deployment_profiles_auto)
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 48))
        setOnClickListener {
            val button = this
            val profiles = store.read().filter { it.enabled }
            if (profiles.isEmpty()) return@setOnClickListener
            button.isEnabled = false
            activityScope.launch(Dispatchers.IO) {
                val before = networkKey()
                val results = profiles.map { profile ->
                    DeploymentProfileProbe().execute(profile, before, ::networkKey, ::captive)
                }
                results.forEach { measurementStore.record(it, profiles) }
                val best = DeploymentTargetPolicy.selectBest(results, System.currentTimeMillis())
                mainHandler.post {
                    if (isDestroyed) return@post
                    button.isEnabled = true
                    button.text = best?.let { getString(R.string.deployment_profiles_selected, it.target.name, it.name) }
                        ?: getString(R.string.deployment_profiles_none)
                    render()
                }
            }
        }
    }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
    card.addView(TextView(this).apply {
        setText(R.string.deployment_profiles_help)
        textSize = 10.5f
        setTextColor(TEXT_SECONDARY)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    card.addView(list, LinearLayout.LayoutParams(-1, -2))
    render()
    body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
}

internal fun MainActivity.buildCloudScreen(): View {
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
    }
    val body = MaxWidthLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        maxWidthPx = dp(520)
        setPadding(dp(24), dp(20), dp(24), dp(104))
    }

    body.addView(cloudScannerSwitch(scannerActive = false), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    body.addView(featureFinderBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    // Wizard card (BPB/Zeus-style): token → deploy → import → scan, step by step.
    val setupWizardCard = advancedSettingsPanel()
    setupWizardCard.addView(
        TextView(this).apply {
            setText(R.string.wizard_title)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    setupWizardCard.addView(
        TextView(this).apply {
            setText(R.string.wizard_subtitle)
            textSize = 12.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    setupWizardCard.addView(
        cloudActionButton(R.string.wizard_start, R.drawable.ic_cloud_tab, accent = true) { showPanelWizard() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    body.addView(setupWizardCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Vercel XHTTP relay card: deploys into the user's own Vercel account with their token.
    val vercelCard = advancedSettingsPanel()
    vercelCard.addView(
        TextView(this).apply {
            setText(R.string.cloud_vercel_title)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    vercelCard.addView(
        TextView(this).apply {
            setText(R.string.cloud_vercel_desc)
            textSize = 12.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    vercelCard.addView(
        cloudActionButton(R.string.cloud_vercel_panel, R.drawable.ic_cloud_tab, accent = true) { vercelRelay.showPanel() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    vercelCard.addView(
        cloudActionButton(R.string.cloud_vercel_start, R.drawable.ic_cloud_tab, accent = false) { vercelRelay.show() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    vercelCard.addView(
        cloudActionButton(R.string.cloud_vercel_scan, R.drawable.ic_cloud_tab, accent = false) { vercelRelay.showIpScanner() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    // 6.69.44 🟧 Cloudflare front — right on the card (it used to be 4 taps deep under «My projects»).
    vercelCard.addView(
        cloudActionButton(R.string.cloud_vercel_cf_front, R.drawable.ic_cloud_tab, accent = false) { vercelRelay.showCfFrontStart() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    body.addView(vercelCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

    // 6.69.45 the same Cat panel on Node hosts — one card per host, the same buttons on each.
    body.addView(
        advancedSectionLabel(getString(R.string.cloud_section_nodehost)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
    )
    for ((kind, titleRes, descRes) in listOf(
        Triple(NodeHostPanel.KATABUMP, R.string.cloud_katabump_title, R.string.cloud_katabump_desc),
        Triple(NodeHostPanel.LUCITY, R.string.cloud_lucity_title, R.string.cloud_lucity_desc),
        Triple(NodeHostPanel.SHIPRR, R.string.cloud_shiprr_title, R.string.cloud_shiprr_desc),
        Triple(NodeHostPanel.BOTHOSTING, R.string.cloud_bothosting_title, R.string.cloud_bothosting_desc),
        Triple(NodeHostPanel.RAILWAY, R.string.cloud_railway_title, R.string.cloud_railway_desc),
    )) {
        body.addView(nodeHostCard(kind, titleRes, descRes), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    body.addView(
        advancedSectionLabel(getString(R.string.cloud_section_catpanel)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
    )

    val catPanelCard = advancedSettingsPanel()
    catPanelCard.addView(
        TextView(this).apply {
            text = CloudflareWorker.PANELS.first { it.id == "cat-panel" }.let {
                if (appLanguagePreferenceStore.read() == AppLanguage.Persian) it.displayNameFa else it.displayName
            }
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    catPanelCard.addView(
        TextView(this).apply {
            text = getString(R.string.cloud_catpanel_desc)
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(0, dp(6), 0, dp(14))
        },
        LinearLayout.LayoutParams(-1, -2),
    )

    val copyPasteCodeButton = cloudActionButton(R.string.cloud_copy_code, R.drawable.ic_cloud_tab, accent = false) {
        copyPanelPasteCode()
    }

    // The no-app path (v1.9.48): share the official one-click Deploy-to-
    // Cloudflare link — the same button as the README — with anyone. The
    // recipient deploys their own panel straight from their browser.
    val shareDeployLinkButton = cloudActionButton(R.string.cloud_share_deploy_link, R.drawable.ic_cloud_tab, accent = false) {
        shareDeployButtonLink()
    }

    val workerNameInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setText(CloudflareWorker.randomWorkerName())
    }
    val workerNameLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_worker_name_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(workerNameInput)
    }

    // Admin gate: every new panel gets a username + password (management
    // panel — not open access). Suggested values are prefilled.
    val panelUserInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setText("admin")
    }
    val panelUserLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_panel_user_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(panelUserInput)
    }
    val suggestedPanelPassword = "admin"
    val panelPassInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(suggestedPanelPassword)
    }
    val panelPassLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_panel_password_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(panelPassInput)
    }

    val tokenInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val tokenLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_token_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
        helperText = getString(R.string.cloud_token_help)
        isHelperTextEnabled = true
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(tokenInput)
    }
    // Step 1 — open Cloudflare with the permissions pre-selected (token template URL).
    catPanelCard.addView(
        cloudActionButton(R.string.cloud_get_token, R.drawable.ic_cloud_tab, accent = true) {
            openCloudflareTokenPage()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    // No Cloudflare account yet? These two are plain dashboard URLs, so the
    // account can always be created/opened first — the token template link
    // only works once a session exists (see CloudflareAccountLinks).
    val accountRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val accountParams = LinearLayout.LayoutParams(0, -2).apply { weight = 1f }
    accountRow.addView(
        cloudActionButton(R.string.cloud_account_signup, R.drawable.ic_cloud_tab, accent = false) {
            openAccountEntry(CloudflareAccountLinks.SIGNUP)
        },
        accountParams.apply { marginEnd = dp(4) },
    )
    accountRow.addView(
        cloudActionButton(R.string.cloud_account_login, R.drawable.ic_cloud_tab, accent = false) {
            openAccountEntry(CloudflareAccountLinks.LOGIN)
        },
        LinearLayout.LayoutParams(0, -2).apply { weight = 1f; marginStart = dp(4) },
    )
    catPanelCard.addView(
        accountRow,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    catPanelCard.addView(
        advancedSectionDetail(getString(R.string.cloud_account_hint)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    catPanelCard.addView(
        advancedSectionDetail(getString(R.string.cloud_get_token_steps)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    tokenLayout.helperText = getString(R.string.cloud_token_help)
    catPanelCard.addView(
        tokenLayout,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )

    val customWorkerFieldsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(workerNameLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(panelUserLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(panelPassLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    val isFarsiUi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val customFieldsToggle = MaterialButton(this).apply {
        text = if (isFarsiUi) "⚙️ سفارشی‌سازی نام ورکر و رمز ورود (پیش‌فرض: admin / admin)" else "⚙️ Customize Worker name & login (default: admin / admin)"
        isAllCaps = false
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 140))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 140))
        cornerRadius = dp(10)
        minHeight = dp(38)
        setOnClickListener {
            val show = customWorkerFieldsHost.visibility != View.VISIBLE
            customWorkerFieldsHost.visibility = if (show) View.VISIBLE else View.GONE
        }
    }
    catPanelCard.addView(customFieldsToggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    catPanelCard.addView(customWorkerFieldsHost, LinearLayout.LayoutParams(-1, -2))

    val deployButton = cloudActionButton(R.string.cloud_deploy, R.drawable.ic_cloud_tab, accent = true) { }
    val deployProgressHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 24 else 16))
            setStroke(dp(1), withAlpha(TEAL, 96))
        }
    }
    val deployProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 52))
    }
    val deployProgressLabel = TextView(this).apply {
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val deployProgressSubLabel = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        text = if (isFarsiUi) "لطفاً چند ثانیه صبر کنید و از صفحه خارج نشوید…" else "Please wait a few seconds while deployment completes…"
    }
    deployProgressHost.addView(deployProgressLabel, LinearLayout.LayoutParams(-1, -2))
    deployProgressHost.addView(deployProgressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
    deployProgressHost.addView(deployProgressSubLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    fun updateDeployProgressUi(percent: Int, stepFa: String, stepEn: String) {
        runOnUiThread {
            val clamped = percent.coerceIn(0, 100)
            deployProgressHost.visibility = View.VISIBLE
            deployProgressBar.progress = clamped
            val msg = if (isFarsiUi) stepFa else stepEn
            deployProgressLabel.text = "$clamped% — $msg"
            deployButton.text = if (isFarsiUi) "⏳ در حال نصب پنل… ($clamped%)" else "⏳ Installing… ($clamped%)"
        }
    }

    var cloudDeployInProgress = false
    deployButton.setOnClickListener {
        if (cloudDeployInProgress) return@setOnClickListener
        val token = tokenInput.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val workerName = workerNameInput.text?.toString()?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9-]"), "-")
            ?.replace(Regex("-{2,}"), "-")
            ?.trim('-')
            ?.ifEmpty { CloudflareWorker.randomWorkerName() } ?: CloudflareWorker.randomWorkerName()
        val effectiveUser = panelUserInput.text?.toString()?.trim().orEmpty().ifBlank { "admin" }
        val effectivePass = panelPassInput.text?.toString()?.trim().orEmpty().ifBlank { "admin" }
        val bundledVer = CloudflareWorker.bundledPanelVersion(this).ifBlank { "6.69.22" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_deploy)
            .setMessage(
                "Worker: $workerName\n" +
                    "Version: Cat Panel v$bundledVer\n" +
                    "Login: $effectiveUser / $effectivePass\n" +
                    "Bindings: UUID + CAT_KV + OPEN_SUB=false + CF_API_TOKEN (one-click update)",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cloud_deploy) { _, _ ->
                cloudDeployInProgress = true
                deployButton.isEnabled = false
                updateDeployProgressUi(10, "در حال بررسی اعتبار توکن و دسترسی‌های Cloudflare…", "Verifying Cloudflare API token and permissions…")
                activityScope.launch {
                    try {
                        val permissions = CloudflareWorker.verifyToken(token)
                        val accountId = permissions.accountId
                        if (!permissions.valid || accountId == null) {
                            deployProgressHost.visibility = View.GONE
                            Toast.makeText(
                                this@buildCloudScreen,
                                getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        val result = CloudflareWorker.deployBuiltIn(
                            this@buildCloudScreen,
                            token,
                            accountId,
                            workerName,
                            panelUser = effectiveUser,
                            panelPassword = effectivePass,
                            onProgress = { pct, faMsg, enMsg -> updateDeployProgressUi(pct, faMsg, enMsg) },
                        )
                        PanelDeploymentStore(this@buildCloudScreen).rememberToken(result.workerUrl, token)
                        renderCloudDeploymentHistory()
                        showCloudDeploymentDialog(result)
                    } catch (e: Exception) {
                        deployProgressSubLabel.text = e.message ?: e::class.java.simpleName
                        Toast.makeText(
                            this@buildCloudScreen,
                            getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    } finally {
                        cloudDeployInProgress = false
                        deployButton.isEnabled = true
                        deployButton.setText(R.string.cloud_deploy)
                    }
                }
            }
            .show()
    }
    catPanelCard.addView(deployButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    catPanelCard.addView(deployProgressHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    body.addView(catPanelCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val rawSourceCard = advancedSettingsPanel()
    rawSourceCard.addView(TextView(this).apply {
        setText(R.string.cloud_raw_title)
        textSize = 16f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
    }, LinearLayout.LayoutParams(-1, -2))
    rawSourceCard.addView(
        advancedSectionDetail(getString(R.string.cloud_raw_desc)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    val rawSourceInput = TextInputEditText(this).apply {
        hint = getString(R.string.cloud_raw_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setSingleLine(true)
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
    }
    rawSourceCard.addView(rawSourceInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val rawInspectButton = cloudActionButton(R.string.cloud_raw_inspect, R.drawable.ic_cloud_tab, accent = false) { }
    var rawInstallBusy = false
    rawInspectButton.setOnClickListener {
        if (rawInstallBusy) return@setOnClickListener
        val sourceUrl = rawSourceInput.text?.toString()?.trim().orEmpty()
        val initialDecision = WorkerSourcePolicy.validateUrl(sourceUrl)
        if (initialDecision is WorkerSourceDecision.Blocked) {
            Toast.makeText(this, getString(R.string.cloud_raw_invalid, initialDecision.reason), Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        rawInstallBusy = true
        rawInspectButton.isEnabled = false
        activityScope.launch {
            try {
                val downloaded = WorkerSourceDownloader.download(sourceUrl)
                when (val manifestDecision = downloaded.verifyManifest(WorkerArtifactChannel.RAW_URL, downloaded.sha256)) {
                    is WorkerManifestDecision.Rejected -> throw WorkerSourceDownloadException(manifestDecision.reason)
                    is WorkerManifestDecision.Verified -> Unit
                }
                val staticAnalysis = downloaded.staticAnalysis()
                val inspection = staticAnalysis.inspection
                if (!inspection.valid) throw WorkerSourceDownloadException(inspection.reason ?: "source-invalid")
                val workerName = workerNameInput.text?.toString()?.trim().orEmpty()
                    .lowercase(Locale.US)
                    .replace(Regex("[^a-z0-9-]"), "-")
                    .replace(Regex("-{2,}"), "-")
                    .trim('-')
                    .ifEmpty { CloudflareWorker.randomWorkerName() }
                val localSummary = WorkerStaticAnalyzer.formatLocalSummary(staticAnalysis)
                MaterialAlertDialogBuilder(this@buildCloudScreen)
                    .setTitle(R.string.cloud_raw_title)
                    .setMessage(
                        getString(
                            R.string.cloud_raw_preview,
                            inspection.version ?: "—",
                            downloaded.bytes.size,
                            downloaded.sha256,
                            workerName,
                        ) + "\n\n$localSummary",
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton("🤖 Gemini / Redacted Review") { _, _ ->
                        showOptInGeminiWorkerReviewDialog(
                            sourceText = downloaded.bytes.toString(Charsets.UTF_8),
                            localAnalysis = staticAnalysis,
                        )
                    }
                    .setPositiveButton(R.string.cloud_raw_confirm) { _, _ ->
                        activityScope.launch {
                            try {
                                val token = tokenInput.text?.toString()?.trim().orEmpty()
                                if (token.isEmpty()) throw WorkerSourceDownloadException("token-required")
                                val permissions = CloudflareWorker.verifyToken(token)
                                val accountId = permissions.accountId
                                if (!permissions.valid || accountId == null) throw WorkerSourceDownloadException("token-invalid")
                                if (!permissions.accountName.isNullOrBlank()) {
                                    Toast.makeText(this@buildCloudScreen, "Account: ${permissions.accountName}", Toast.LENGTH_SHORT).show()
                                }
                                val result = CloudflareWorker.deployRawSource(
                                    context = this@buildCloudScreen,
                                    token = token,
                                    accountId = accountId,
                                    workerName = workerName,
                                    sourceUrl = sourceUrl,
                                    approvedSha256 = downloaded.sha256,
                                    panelUser = panelUserInput.text?.toString()?.trim().orEmpty(),
                                    panelPassword = panelPassInput.text?.toString()?.trim().orEmpty(),
                                )
                                PanelDeploymentStore(this@buildCloudScreen).rememberToken(result.workerUrl, token)
                                showCloudDeploymentDialog(result)
                            } catch (error: Exception) {
                                Toast.makeText(this@buildCloudScreen, getString(R.string.cloud_deploy_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            } catch (error: Exception) {
                Toast.makeText(this@buildCloudScreen, getString(R.string.cloud_raw_invalid, error.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally {
                rawInstallBusy = false
                rawInspectButton.isEnabled = true
            }
        }
    }
    rawSourceCard.addView(rawInspectButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    val deploymentHistoryCard = advancedSettingsPanel()
    deploymentHistoryCard.addView(
        TextView(this).apply {
            setText(R.string.cloud_my_deployments)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    deploymentHistoryCard.addView(
        advancedSectionDetail(getString(R.string.cloud_no_deployments)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) },
    )
    cloudDeploymentHistoryHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    deploymentHistoryCard.addView(
        cloudDeploymentHistoryHost,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    body.addView(deploymentHistoryCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    renderCloudDeploymentHistory()

    body.addView(buildPanelStatusCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // ---- Cat Wizard: a shareable one-click installer page on the user's own account ----
    val wizardCard = advancedSettingsPanel()
    wizardCard.addView(
        TextView(this).apply {
            text = getString(R.string.cloud_wizard_title)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    wizardCard.addView(
        advancedSectionDetail(getString(R.string.cloud_wizard_desc)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    val lastWizard = PanelDeploymentStore(this).lastWizardUrl()
    val wizardUrlView = TextView(this).apply {
        text = lastWizard ?: ""
        visibility = if (lastWizard.isNullOrBlank()) View.GONE else View.VISIBLE
        textSize = 13f
        setTextColor(TEAL)
        setTextIsSelectable(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setPadding(0, dp(8), 0, 0)
        setOnClickListener {
            val u = text?.toString().orEmpty()
            if (u.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        }
    }
    wizardCard.addView(wizardUrlView, LinearLayout.LayoutParams(-1, -2))
    var wizardInProgress = false
    val wizardButton = cloudActionButton(R.string.cloud_wizard_deploy, R.drawable.ic_cloud_tab, accent = false) { }
    val wizardProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        visibility = View.GONE
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 52))
    }
    val wizardProgressText = TextView(this).apply {
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        visibility = View.GONE
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    wizardButton.setOnClickListener {
        if (wizardInProgress) return@setOnClickListener
        val token = tokenInput.text?.toString()?.trim().orEmpty()
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        wizardInProgress = true
        wizardButton.isEnabled = false
        wizardProgressBar.visibility = View.VISIBLE
        wizardProgressText.visibility = View.VISIBLE
        wizardProgressBar.progress = 15
        wizardProgressText.text = if (isFarsiUi) "۱۵٪ — در حال بررسی توکن Cloudflare…" else "15% — Verifying Cloudflare token…"
        activityScope.launch {
            try {
                val permissions = CloudflareWorker.verifyToken(token)
                val accountId = permissions.accountId
                if (!permissions.valid || accountId == null) {
                    wizardProgressBar.visibility = View.GONE
                    wizardProgressText.visibility = View.GONE
                    Toast.makeText(
                        this@buildCloudScreen,
                        getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                wizardProgressBar.progress = 60
                wizardProgressText.text = if (isFarsiUi) "۶۰٪ — در حال آپلود و راه‌اندازی cat-wizard…" else "60% — Uploading and enabling cat-wizard…"
                val result = CloudflareWorker.deployWizard(this@buildCloudScreen, token, accountId)
                wizardProgressBar.progress = 100
                wizardProgressText.text = if (isFarsiUi) "۱۰۰٪ — ✅ ویزارد آماده شد!" else "100% — Wizard deployed!"
                PanelDeploymentStore(this@buildCloudScreen).rememberWizard(result.wizardUrl)
                wizardUrlView.text = result.wizardUrl
                wizardUrlView.visibility = View.VISIBLE
                showWizardDeployedDialog(result)
            } catch (e: Exception) {
                wizardProgressText.text = e.message ?: e::class.java.simpleName
                Toast.makeText(
                    this@buildCloudScreen,
                    getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                wizardInProgress = false
                wizardButton.isEnabled = true
            }
        }
    }
    wizardCard.addView(wizardButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    wizardCard.addView(wizardProgressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    wizardCard.addView(wizardProgressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) })

    // Cross-link: the built-in panel is only useful with a clean IP — jump to the scanner.
    val scannerLinkCard = advancedSettingsPanel()
    scannerLinkCard.addView(
        advancedSectionDetail(getString(R.string.cloud_scanner_hint)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        },
    )
    scannerLinkCard.addView(
        cloudActionButton(R.string.cloud_open_scanner, R.drawable.ic_speedometer, accent = false) {
            openScreen(SCREEN_SCANNER)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    body.addView(scannerLinkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

    // ---- Collapsible Advanced Cloud Tools & Catalog ----
    val advancedCloudToolsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    val manualCopyShareCard = advancedSettingsPanel().apply {
        addView(
            TextView(this@buildCloudScreen).apply {
                text = if (isFarsiUi) "📋 کپی کد دستی و اشتراک لینک ساخت پنل" else "📋 Manual Paste Code & Share Deploy Link"
                textSize = 15f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(copyPasteCodeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(shareDeployLinkButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    advancedCloudToolsHost.addView(manualCopyShareCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    advancedCloudToolsHost.addView(rawSourceCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    advancedCloudToolsHost.addView(wizardCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    // ---- panel catalog ----
    advancedCloudToolsHost.addView(
        advancedSectionLabel(getString(R.string.cloud_section_catalog)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    advancedCloudToolsHost.addView(
        TextView(this).apply {
            text = getString(R.string.cloud_intro)
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(0, 0, 0, dp(10))
        },
        LinearLayout.LayoutParams(-1, -2),
    )

    val catalogCard = advancedSettingsPanel()
    CloudflareWorker.PANELS.filter { it.id != "cat-panel" }.forEachIndexed { index, panel ->
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(
            TextView(this).apply {
                text = if (isFarsi) panel.displayNameFa else panel.displayName
                textSize = 14.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        nameRow.addView(
            TextView(this).apply {
                text = cloudScopeLabel(panel.scope)
                textSize = 11f
                setTextColor(TEAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(withAlpha(TEAL, 26))
                    setStroke(dp(1), withAlpha(TEAL, 120))
                }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
        row.addView(nameRow, LinearLayout.LayoutParams(-1, -2))
        row.addView(
            TextView(this).apply {
                text = if (isFarsi) panel.descriptionFa else panel.description
                textSize = 12.5f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(2).toFloat(), 1f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(0, dp(4), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        if (panel.id == "technamooz-worker") {
            row.addView(
                TextView(this).apply {
                    setText(R.string.cloud_technamooz_native_match)
                    textSize = 12f
                    setTextColor(TEXT_SECONDARY)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    setPadding(0, dp(8), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        val openButton = MaterialButton(this).apply {
            setText(
                when {
                    panel.id == "technamooz-worker" -> R.string.cloud_technamooz_open_source
                    panel.scope == CloudflareWorker.PanelScope.CF_WORKER -> R.string.cloud_open_builder
                    else -> R.string.cloud_panel_open
                },
            )
            setAllCaps(false)
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(15)
            minHeight = dp(40)
            minimumHeight = dp(40)
            backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 170))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
            elevation = dp(1).toFloat()
            stateListAnimator = null
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(panel.url)))
                }.onFailure {
                    Toast.makeText(this@buildCloudScreen, panel.url, Toast.LENGTH_LONG).show()
                }
            }
        }
        row.addView(
            openButton,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        if (panel.deployKind == CloudflareWorker.DeployKind.PINNED_EXTERNAL_SCRIPT) {
            row.addView(
                cloudActionButton(R.string.cloud_technamooz_deploy, R.drawable.ic_cloud_tab, accent = true) {
                    showTechNamoozInstallDialog(tokenInput.text?.toString()?.trim().orEmpty())
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }
        catalogCard.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (index < CloudflareWorker.PANELS.size - 2) {
            catalogCard.addView(
                View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                },
            )
        }
    }
    advancedCloudToolsHost.addView(catalogCard, LinearLayout.LayoutParams(-1, -2))

    val advancedCloudToggleBtn = MaterialButton(this).apply {
        val closedLabel = if (isFarsiUi) "🛠️ ابزارهای پیشرفته (کپی کد دستی · لینک Raw · ویزارد · سایر پنل‌ها) ▾" else "🛠️ Advanced Tools (Copy Code · Raw URL · Wizard · Other Panels) ▾"
        val openLabel = if (isFarsiUi) "🛠️ بستن ابزارهای پیشرفته ▴" else "🛠️ Hide Advanced Tools ▴"
        text = closedLabel
        isAllCaps = false
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 180))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
        cornerRadius = dp(12)
        minHeight = dp(44)
        setOnClickListener {
            val show = advancedCloudToolsHost.visibility != View.VISIBLE
            advancedCloudToolsHost.visibility = if (show) View.VISIBLE else View.GONE
            text = if (show) openLabel else closedLabel
        }
    }
    body.addView(advancedCloudToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    body.addView(advancedCloudToolsHost, LinearLayout.LayoutParams(-1, -2))

    scroll.addView(body, FrameLayout.LayoutParams(-1, -1))
    return scroll
}

internal fun MainActivity.renderCloudDeploymentHistory() {
    val host = cloudDeploymentHistoryHost ?: return
    host.removeAllViews()
    val history = PanelDeploymentStore(this).deployments()
    history.forEachIndexed { index, deployment ->
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(10), dp(9), dp(8), dp(9))
            background = glassSurfaceDrawable(radiusDp = 14)
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        titleLine.addView(
            TextView(this).apply {
                text = "🐱  " + deployment.workerUrl.removePrefix("https://").take(42)
                textSize = 12.5f
                typeface = CatClientDataTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val versionBadge = TextView(this).apply {
            text = getString(R.string.cloud_panel_version_checking)
            textSize = 11f
            typeface = CatClientDataTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            maxLines = 1
        }
        titleLine.addView(versionBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(titleLine, LinearLayout.LayoutParams(-1, -2))

        fun rowButton(labelText: CharSequence): MaterialButton = MaterialButton(this@renderCloudDeploymentHistory).apply {
            text = labelText
            setAllCaps(false)
            textSize = 11.5f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130))
            setTextColor(TEAL)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val open = rowButton(getString(R.string.cloud_open_deployment)).apply {
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deployment.panelUrl))) }
            }
        }
        val update = rowButton(getString(R.string.cloud_update_panel)).apply {
            setOnClickListener { showPanelUpdateDialog(deployment) }
        }
        buttons.addView(open, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
        buttons.addView(update, LinearLayout.LayoutParams(0, -2, 1f).apply {
            topMargin = dp(8)
            marginStart = dp(8)
        })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        // «📊 وضعیت کامل»: everything the panel currently serves, fetched live
        val status = rowButton(getString(R.string.panel_status_btn)).apply {
            setOnClickListener { showPanelStatusDialog(deployment.workerUrl) }
        }
        card.addView(
            status,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        host.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            if (index > 0) topMargin = dp(7)
        })

        // Live version check: deployed (/api/version) vs newest (release asset / bundle).
        activityScope.launch {
            val deployed = PanelUpdate.deployedVersion(deployment.workerUrl)
            val newest = runCatching { newestPanelScript() }.getOrNull()
            if (!versionBadge.isAttachedToWindow) return@launch
            applyPanelVersionBadge(versionBadge, deployed, newest)
        }
    }
    host.addView(
        MaterialButton(this).apply {
            setText(R.string.cloud_add_existing)
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            cornerRadius = dp(12)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
            setTextColor(TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
            setOnClickListener { onAddExistingPanel() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
}

internal fun MainActivity.showPanelSecretsDialog(
    deployment: PanelDeploymentRecord,
    token: String,
    deployed: String?,
    script: PanelUpdate.PanelScript,
    secretNames: List<String>,
    onValues: (Map<String, String>) -> Unit,
) {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(24), dp(8), dp(24), 0)
    }
    column.addView(
        TextView(this).apply {
            setText(R.string.cloud_secrets_desc)
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(2).toFloat(), 1f)
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
    )
    val inputs = mutableMapOf<String, TextInputEditText>()
    secretNames.forEach { name ->
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
            hint = name
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputs[name] = input
        column.addView(
            TextInputLayout(this).apply {
                this.hint = name
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
                boxStrokeColor = TEAL
                defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
                setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
                addView(input)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_secrets_title)
        .setView(column)
        .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
        .setPositiveButton(R.string.cloud_secrets_confirm, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = mutableMapOf<String, String>()
            for ((name, input) in inputs) {
                val value = input.text?.toString().orEmpty()
                if (value.isEmpty()) {
                    Toast.makeText(this, getString(R.string.cloud_secrets_missing, name), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                values[name] = value
            }
            dialog.dismiss()
            panelUpdateInProgress = true
            onValues(values)
        }
    }
    dialog.setOnCancelListener { panelUpdateInProgress = false }
    dialog.show()
}

internal fun MainActivity.showOptInGeminiWorkerReviewDialog(
    sourceText: String,
    localAnalysis: WorkerStaticAnalysis,
) {
    val prefs = getSharedPreferences("cat_gemini_worker_review", Context.MODE_PRIVATE)
    val savedKey = prefs.getString("gemini_api_key", "").orEmpty()
    val keyInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        hint = "Gemini API Key (optional)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(savedKey)
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
    }
    val keyLayout = TextInputLayout(this).apply {
        hint = "Gemini API Key"
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxStrokeColor = TEAL
        addView(keyInput)
    }
    val optInCheck = android.widget.CheckBox(this).apply {
        text = "I consent to redacting secrets/UUIDs and preparing this Worker for Gemini review"
        textSize = 12.5f
        setTextColor(TEXT_SECONDARY)
        isChecked = true
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(wizardText(WorkerStaticAnalyzer.formatLocalSummary(localAnalysis), secondary = true))
        addView(keyLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(optInCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("🤖 Worker Analysis & Opt-in Gemini Review")
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton("Copy Redacted") { _, _ ->
            when (
                val decision = WorkerStaticAnalyzer.prepareOptInGeminiReview(
                    sourceText = sourceText,
                    optInAccepted = optInCheck.isChecked,
                    apiKeyConfigured = true,
                )
            ) {
                is GeminiWorkerReviewDecision.Ready -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("worker-gemini-review", decision.redactedSnippet))
                    Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
                }
                is GeminiWorkerReviewDecision.Blocked -> {
                    Toast.makeText(this, decision.reason, Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setPositiveButton("Run Gemini") { _, _ ->
            val apiKey = keyInput.text?.toString()?.trim().orEmpty()
            val optIn = optInCheck.isChecked
            if (apiKey.isNotEmpty()) {
                prefs.edit().putString("gemini_api_key", apiKey).apply()
            }
            activityScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    WorkerStaticAnalyzer.runOptInGeminiReview(
                        sourceText = sourceText,
                        optInAccepted = optIn,
                        apiKey = apiKey,
                    )
                }
                when (outcome) {
                    is GeminiWorkerReviewOutcome.Blocked -> {
                        Toast.makeText(this@showOptInGeminiWorkerReviewDialog, outcome.reason, Toast.LENGTH_LONG).show()
                    }
                    is GeminiWorkerReviewOutcome.Error -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("worker-gemini-review", outcome.redactedSnippet))
                        Toast.makeText(
                            this@showOptInGeminiWorkerReviewDialog,
                            "Gemini error (${outcome.message}) — redacted snippet copied",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is GeminiWorkerReviewOutcome.Success -> {
                        MaterialAlertDialogBuilder(this@showOptInGeminiWorkerReviewDialog)
                            .setTitle("🤖 Gemini Worker Review")
                            .setMessage(outcome.reviewText)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
        .show()
}

/** Explicit, opt-in install of the exact pinned third-party Worker from the video. */
internal fun MainActivity.showTechNamoozInstallDialog(token: String) {
    if (token.isBlank()) {
        Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
        return
    }
    if (techNamoozDeployInProgress) return

    val workerNameInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setText(CloudflareWorker.randomWorkerName())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
    val workerNameLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_technamooz_worker_name_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxStrokeColor = TEAL
        addView(workerNameInput)
    }
    val passwordInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(TechNamoozWorkerSource.newSecret(24))
    }
    val passwordLayout = TextInputLayout(this).apply {
        hint = getString(R.string.cloud_technamooz_password_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxStrokeColor = TEAL
        addView(passwordInput)
    }
    val acknowledgement = android.widget.CheckBox(this).apply {
        setText(R.string.cloud_technamooz_ack)
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val review = TextView(this).apply {
        text = getString(
            R.string.cloud_technamooz_review_body,
            TechNamoozWorkerSource.PINNED_COMMIT,
            TechNamoozWorkerSource.EXPECTED_SHA256,
        )
        textSize = 12.5f
        setTextColor(TEXT_SECONDARY)
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextIsSelectable(true)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(6), dp(22), 0)
        addView(review, LinearLayout.LayoutParams(-1, -2))
        addView(workerNameLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(passwordLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(acknowledgement, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_technamooz_review_title)
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.cloud_technamooz_deploy, null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
        isEnabled = false
        setOnClickListener {
            val name = TechNamoozWorkerSource.normalizeWorkerName(
                workerNameInput.text?.toString().orEmpty(),
            )
            if (!TechNamoozWorkerSource.isValidWorkerName(name)) {
                workerNameLayout.error = getString(R.string.cloud_technamooz_name_error)
                return@setOnClickListener
            }
            workerNameLayout.error = null
            val password = passwordInput.text?.toString().orEmpty()
            if (!TechNamoozWorkerSource.isValidPanelPassword(password)) {
                passwordLayout.error = getString(R.string.cloud_technamooz_password_error)
                return@setOnClickListener
            }
            passwordLayout.error = null
            if (!acknowledgement.isChecked) return@setOnClickListener
            dialog.dismiss()
            runTechNamoozDeployment(token, name, password)
        }
    }
    acknowledgement.setOnCheckedChangeListener { _, checked ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked
    }
}
