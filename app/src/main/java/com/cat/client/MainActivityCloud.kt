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
import com.cat.client.MainActivity.Companion.SCREEN_SERVERS

// Cloud tab: panel deployment, history, secrets, installers, panel status/manage/recovery, panel update + wizard, panel probe.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/** Worker / Pages / Railway targets are user-owned HTTPS origins; no secret is stored here. */
internal fun MainActivity.addDeploymentProfilesSection(body: LinearLayout) {
    val deploymentPrefs = getSharedPreferences("deployment_profiles", Context.MODE_PRIVATE)
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
        Triple(NodeHostPanel.SMARTERASP, R.string.cloud_smarterasp_title, R.string.cloud_smarterasp_desc),
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

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.buildPanelStatusCard(): View {
    val card = advancedSettingsPanel()
    card.addView(
        TextView(this).apply {
            setText(R.string.cloud_status_title)
            textSize = 15f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        },
    )
    card.addView(
        TextView(this).apply {
            setText(R.string.cloud_status_detail)
            textSize = 12f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    val check = MaterialButton(this).apply {
        text = getString(R.string.cloud_status_check)
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minHeight = dp(44)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(14)
        setPaddingRelative(dp(16), 0, dp(16), 0)
        backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 190))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
        setTextColor(TEXT_PRIMARY)
        setOnClickListener { onPanelStatusCheck() }
    }
    card.addView(check, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    return card
}

internal fun MainActivity.onPanelStatusCheck() {
    val dep = PanelDeploymentStore(this).deployments().firstOrNull()
    if (dep == null) {
        Toast.makeText(this, R.string.cloud_status_none, Toast.LENGTH_SHORT).show()
        return
    }
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.cloud_status_checking))
        .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_status_checking_detail))))
        .setCancelable(false)
        .create()
    progress.show()
    activityScope.launch {
        val health = withContext(Dispatchers.IO) {
            runCatching { CloudflareWorker.checkPanelHealth(dep.workerUrl) }
                .getOrElse { CloudflareWorker.PanelHealth("DOWN", it.message ?: "network error") }
        }
        progress.dismiss()
        if (health.state == "HEALTHY") {
            val deployed = runCatching { PanelUpdate.deployedVersion(dep.workerUrl) }.getOrNull()
            val newest = runCatching { newestPanelScript() }.getOrNull()
            showPanelManageDialog(dep, deployed, newest)
        } else if (health.state == "PENDING") {
            // The route is not live yet — a fresh upload, not a fault. Measured on
            // a real account: up to a minute. The recovery dialog rebuilds the
            // panel under a new name, which is the last thing a working upload
            // needs, so this case gets its own answer and a button that rechecks.
            showPanelActivatingDialog(dep.workerUrl, health.detail)
        } else {
            showPanelRecoveryDialog(dep.workerUrl, health.detail)
        }
    }
}

/** Version + update + delete for a healthy panel (deployed from this app). */
internal fun MainActivity.showPanelManageDialog(dep: PanelDeploymentRecord, deployed: String?, newest: PanelUpdate.PanelScript?) {
    // Strictly newer — `!=` used to flag an OLDER source as «⬆ update available».
    val updateAvailable = newest != null && (deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed))
    val rollbackSummary = PanelDeploymentStore(this).rollbackSummaryFor(dep.workerUrl)
    MaterialAlertDialogBuilder(this)
        .setTitle(
            getString(R.string.panel_manage_title) +
                if (updateAvailable) "  ·  ⬆ v" + newest!!.version else "",
        )
        .setMessage(
            getString(
                R.string.panel_manage_msg,
                dep.workerUrl,
                deployed ?: getString(R.string.panel_version_unknown),
                newest?.version ?: getString(R.string.panel_version_unknown),
            ) + if (rollbackSummary != null) "\n\nRollback snapshot: $rollbackSummary" else "",
        )
        .setPositiveButton(R.string.panel_update_btn) { _, _ ->
            val script = newest
            if (script == null) {
                Toast.makeText(this, R.string.panel_no_source, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            if (blockPanelDowngrade(deployed, script.version)) return@setPositiveButton
            val token = PanelDeploymentStore(this).tokenFor(dep.workerUrl).orEmpty()
            panelUpdateInProgress = true
            // No token? Token-free guided update — NEVER a token prompt.
            if (token.isBlank()) presentTokenlessPanelUpdate(dep, deployed, script, updateAvailable)
            else presentPanelUpdatePasswordDialog(dep, deployed, script, updateAvailable, token)
        }
        .setNeutralButton(R.string.panel_delete) { _, _ -> confirmPanelDelete(dep) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun MainActivity.confirmPanelDelete(dep: PanelDeploymentRecord) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.panel_delete_confirm_title)
        .setMessage(getString(R.string.panel_delete_confirm_msg, dep.workerUrl))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.panel_delete) { _, _ ->
            val token = PanelDeploymentStore(this).tokenFor(dep.workerUrl).orEmpty()
            if (token.isBlank()) {
                Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val progress = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.panel_delete_confirm_title)
                .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_recover_running))))
                .setCancelable(false)
                .create()
            progress.show()
            activityScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { CloudflareWorker.deletePanel(token, dep.workerUrl) }
                }
                progress.dismiss()
                result.onSuccess { summary ->
                    PanelDeploymentStore(this@confirmPanelDelete).remove(dep.workerUrl)
                    Toast.makeText(this@confirmPanelDelete, getString(R.string.panel_delete_done, summary), Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(this@confirmPanelDelete, getString(R.string.panel_delete_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
                renderCloudDeploymentHistory()
            }
        }
        .show()
}

/**
 * «Still activating», not «broken». Shown when the panel's own route answers the
 * edge's 404 page (no Worker on it yet) instead of the panel: normal for the first
 * seconds-to-a-minute after an upload, so it offers a recheck instead of the
 * rebuild flow. Every button here does something: the recheck re-runs the same
 * status check, and the panel button opens the URL.
 */
internal fun MainActivity.showPanelActivatingDialog(panelUrl: String, detail: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_status_pending_title)
        .setMessage(getString(R.string.cloud_status_pending_msg, panelUrl, detail))
        .setPositiveButton(R.string.cloud_status_recheck) { _, _ -> onPanelStatusCheck() }
        .setNeutralButton(R.string.cloud_panel_open) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(panelUrl))) }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun MainActivity.showPanelRecoveryDialog(panelUrl: String, detail: String) {
    val backup = PanelBackup.read(this)
    val explain = TextView(this).apply {
        text = getString(R.string.cloud_recover_explain, panelUrl, detail) + "\n\n" +
            if (backup != null) {
                getString(R.string.cloud_recover_backup_ok, android.text.format.DateUtils.getRelativeTimeSpanString(backup.savedAt).toString())
            } else {
                getString(R.string.cloud_recover_backup_none)
            }
        textSize = 13f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }
    val tokenEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
        hint = getString(R.string.cloud_token_help)
        setText(PanelDeploymentStore(this@showPanelRecoveryDialog).tokenFor(panelUrl).orEmpty())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(explain)
        addView(
            tokenEdit,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(10), dp(20), 0) },
        )
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.cloud_recover_title))
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.cloud_recover_start) { _, _ ->
            val token = tokenEdit.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            runPanelRecovery(token, backup)
        }
        .show()
}

internal fun MainActivity.runPanelRecovery(token: String, backup: PanelBackup.Snapshot?) {
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.cloud_recover_title))
        .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_recover_running))))
        .setCancelable(false)
        .create()
    progress.show()
    activityScope.launch {
        try {
            val permissions = CloudflareWorker.verifyToken(token)
            val accountId = permissions.accountId
            if (!permissions.valid || accountId == null) {
                progress.dismiss()
                Toast.makeText(
                    this@runPanelRecovery,
                    getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // The snapshot must be captured BEFORE deploying: the deploy's own
            // stealth step replaces the stored backup with the new panel's state.
            val result = CloudflareWorker.deployBuiltIn(
                this@runPanelRecovery,
                token,
                accountId,
                CloudflareWorker.randomWorkerName(),
                isRepair = true,
            )
            PanelDeploymentStore(this@runPanelRecovery).rememberToken(result.workerUrl, token)
            var usersNote = ""
            if (backup != null) {
                val restored = withContext(Dispatchers.IO) {
                    runCatching {
                        CloudflareWorker.restorePanelState(
                            workerUrl = result.workerUrl,
                            settingsJson = backup.settingsJson,
                            usersJson = backup.usersJson,
                            username = PanelCredentials.defaultUsername(this@runPanelRecovery, result.workerUrl),
                            password = PanelCredentials.defaultPassword(this@runPanelRecovery, result.workerUrl),
                        )
                    }.getOrElse { CloudflareWorker.PanelRestoreResult(false, 0) }
                }
                if (restored.settingsApplied) {
                    usersNote = getString(R.string.cloud_recover_users, restored.usersRestored)
                }
            }
            progress.dismiss()
            showPanelRecoveryDone(result, usersNote)
        } catch (e: Exception) {
            progress.dismiss()
            Toast.makeText(
                this@runPanelRecovery,
                getString(R.string.cloud_recover_failed, e.message ?: e::class.java.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

internal fun MainActivity.showPanelRecoveryDone(result: CloudflareWorker.DeploymentResult, usersNote: String) {
    val message = buildString {
        append(getString(R.string.cloud_recover_done, result.panelUrl))
        append("\n")
        append(getString(R.string.cloud_recover_done_sub, result.subscriptionUrl))
        if (usersNote.isNotEmpty()) {
            append("\n")
            append(usersNote)
        }
        append("\n\n")
        append(getString(R.string.cloud_recover_done_note))
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.cloud_recover_done_title))
        .setMessage(message)
        .setPositiveButton(R.string.cloud_recover_addsub) { _, _ ->
            showAddSubscriptionDialog(result.subscriptionUrl, "Cat Panel")
        }
        .setNeutralButton(R.string.cloud_recover_open) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun MainActivity.runTechNamoozDeployment(token: String, workerName: String, password: String) {
    if (techNamoozDeployInProgress) return
    techNamoozDeployInProgress = true
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_technamooz_deploying)
        .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_technamooz_deploying_detail))))
        .setCancelable(false)
        .create()
    progress.show()
    activityScope.launch {
        try {
            val permissions = CloudflareWorker.verifyToken(token)
            val accountId = permissions.accountId
            if (!permissions.valid || accountId == null) {
                progress.dismiss()
                Toast.makeText(
                    this@runTechNamoozDeployment,
                    getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val result = CloudflareWorker.deployTechNamoozWorker(
                token = token,
                accountId = accountId,
                workerName = workerName,
                panelPassword = password,
            )
            progress.dismiss()
            showTechNamoozDeploymentResult(result)
        } catch (e: Exception) {
            progress.dismiss()
            Toast.makeText(
                this@runTechNamoozDeployment,
                getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            techNamoozDeployInProgress = false
        }
    }
}

internal fun MainActivity.showTechNamoozDeploymentResult(result: CloudflareWorker.TechNamoozWorkerDeploymentResult) {
    val details = buildString {
        append(getString(R.string.cloud_technamooz_result_panel, result.panelUrl))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_password, result.panelPassword))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_uuid, result.uuid))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_subpath, result.subPath))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_subscription, result.subscriptionUrl))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_kv, result.kvNamespaceTitle))
        append("\n")
        append(getString(R.string.cloud_technamooz_result_source, result.sourceCommit, result.sourceSha256))
        if (!result.workersDevEnabled) {
            append("\n\n")
            append(getString(R.string.cloud_technamooz_route_disabled))
        }
        append("\n\n")
        append(getString(R.string.cloud_technamooz_result_note))
    }
    val detailsView = TextView(this).apply {
        text = details
        textSize = 12.5f
        setTextColor(TEXT_PRIMARY)
        setTextIsSelectable(true)
        setPadding(dp(22), dp(8), dp(22), dp(8))
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_technamooz_result_title)
        .setView(ScrollView(this).apply { addView(detailsView) })
        .setPositiveButton(R.string.cloud_technamooz_add_sub) { _, _ ->
            showAddSubscriptionDialog(result.subscriptionUrl, "TechNamooz Worker")
        }
        .setNeutralButton(R.string.cloud_technamooz_open_panel) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
        }
        .setNegativeButton(R.string.cloud_technamooz_copy_details) { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("technamooz-worker-setup", details))
            Toast.makeText(this, R.string.cloud_technamooz_copied, Toast.LENGTH_LONG).show()
        }
        .show()
}

internal suspend fun MainActivity.newestPanelScript(): PanelUpdate.PanelScript =
    newestPanelCache ?: PanelUpdate.newestPanel(this).also { newestPanelCache = it }

/**
 * «📊 وضعیت کامل» — live snapshot of everything this panel currently serves:
 * SNI (with the skk.moe-default warning), clean IPs (+pins), ProxyIP relays,
 * fixed exit (chain), extra SNIs, ports, rotation, sub link. Login uses the
 * stored deploy UUID (panel default password); on 401 the user is asked.
 */
internal fun MainActivity.showPanelStatusDialog(base0: String) {
    val base = base0.trim().trimEnd('/')
    activityScope.launch {
        var password = runCatching { PanelCredentials.defaultPassword(this@showPanelStatusDialog, base) }.getOrNull().orEmpty()
        var result = withContext(Dispatchers.IO) { runCatching { fetchPanelStatus(base, password) } }
        if (result.isFailure && password.isNotBlank()) {
            // stored password rejected (password changed) → ask once
            val asked = kotlinx.coroutines.withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val input = TextInputEditText(this@showPanelStatusDialog).apply {
                        hint = getString(R.string.pip_password_hint)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setText(password)
                    }
                    val box = LinearLayout(this@showPanelStatusDialog).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(12), dp(20), 0)
                        addView(input, LinearLayout.LayoutParams(-1, -2))
                    }
                    MaterialAlertDialogBuilder(this@showPanelStatusDialog)
                        .setTitle(R.string.panel_status_btn)
                        .setMessage(base.removePrefix("https://"))
                        .setView(box)
                        .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(null) }
                        .setPositiveButton(android.R.string.ok) { d, _ -> cont.resume(input.text.toString()); d.dismiss() }
                        .setOnCancelListener { cont.resume(null) }
                        .show()
                }
            }
            if (asked.isNullOrBlank()) return@launch
            password = asked
            result = withContext(Dispatchers.IO) { runCatching { fetchPanelStatus(base, password) } }
            if (result.isSuccess) {
                PanelCredentials.remember(this@showPanelStatusDialog, base, PanelCredentials.defaultUsername(this@showPanelStatusDialog, base), password)
            }
        }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            result.onSuccess { text ->
                MaterialAlertDialogBuilder(this@showPanelStatusDialog)
                    .setTitle(R.string.panel_status_title)
                    .setMessage(text)
                    .setPositiveButton(R.string.panel_status_close, null)
                    .show()
            }.onFailure { e ->
                Toast.makeText(this@showPanelStatusDialog, getString(R.string.pip_save_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.fetchPanelStatus(base: String, password: String): String {
    fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
        val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return conn to stream.bufferedReader().readText()
    }
    val version = runCatching {
        val (v, vb) = call("/api/version", "GET", null, null); v.disconnect()
        org.json.JSONObject(vb).optString("version", "?")
    }.getOrDefault("?")
    val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
    if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
    val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
    login.disconnect()
    val (get, getBody) = call("/api/settings", "GET", null, cookie)
    get.disconnect()
    if (get.responseCode != 200) throw IllegalStateException("HTTP ${get.responseCode}")
    val st = org.json.JSONObject(getBody).optJSONObject("settings") ?: org.json.JSONObject()
    val sni = st.optString("sni", "")
    // Panel >= 6.54 reports sniFront: false means every TLS config carries the worker host as SNI — the only
    // value Cloudflare accepts (it answers 403 when SNI != Host). Older panels have no such key and still
    // front through the skk.moe default / pool, which Cloudflare rejects.
    val sniLine = when {
        st.has("sniFront") && !st.optBoolean("sniFront") -> getString(R.string.panel_st_sni_host)
        st.has("sniFront") -> getString(R.string.panel_st_sni_spoof, sni.ifBlank { "skk.moe" })
        sni.isBlank() -> getString(R.string.panel_st_sni_default)
        else -> sni
    }
    val ips = st.optJSONArray("ips"); var pins = 0
    if (ips != null) { for (i in 0 until ips.length()) if (ips.optString(i).contains(":")) pins++ }
    val px = st.optJSONArray("proxyIps")?.length() ?: 0
    val xs = st.optJSONArray("extraSnis")?.length() ?: 0
    val chain = st.optString("chain", "").ifBlank { getString(R.string.panel_st_chain_off) }
    val tlsArr = st.optJSONArray("tlsPorts")
    val plainArr = st.optJSONArray("plainPorts")
    val tls = (0 until (tlsArr?.length() ?: 0)).take(3).mapNotNull { tlsArr?.optString(it) }.joinToString("/")
    val plain = if (st.optBoolean("plainEnabled")) (0 until (plainArr?.length() ?: 0)).take(3).mapNotNull { plainArr?.optString(it) }.joinToString("/") else null
    val rot = when (st.optString("subRotate", "fetch")) { "off" -> getString(R.string.panel_rot_off); "daily" -> getString(R.string.panel_rot_daily); else -> getString(R.string.panel_rot_fetch) }
    val uuid = runCatching { PanelDeploymentStore(this).uuidFor(base) }.getOrDefault("")
    // The panel knows which key is live better than we do: since 6.67.0 a subscription
    // TOKEN can replace the UUID in /sub/..., and after that rotation the UUID link is
    // dead. Older panels answer no `links` object, so the stored UUID stays the fallback.
    val sub = org.json.JSONObject(getBody).optJSONObject("links")?.optString("sub").orEmpty().ifBlank {
        if (uuid.isNotBlank()) "$base/sub/$uuid" else base + "/sub/<uuid>"
    }
    return getString(R.string.panel_status_body,
        base.removePrefix("https://"), version, sniLine,
        ips?.length() ?: 0, pins, px, chain, xs,
        tls + (plain?.let { " · $it" } ?: ""), rot, sub)
}

/**
 * Attach a panel that was deployed manually in the Cloudflare dashboard (no
 * app record exists). After this the card shows in My Panels with its live
 * version and the Update Panel button — the CF token is asked at update time.
 */
internal fun MainActivity.onAddExistingPanel() {
    val input = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        hint = getString(R.string.cloud_add_existing_hint)
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(6), dp(24), 0)
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_add_existing)
        .setMessage(R.string.cloud_add_existing_msg)
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { button ->
        var raw = input.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return@setOnClickListener
        if (!raw.startsWith("http")) raw = "https://$raw"
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val host = uri?.host.orEmpty()
        if (!host.contains('.')) {
            Toast.makeText(this, R.string.cloud_panel_notcat, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val path = uri?.path.orEmpty()
        val url = "https://" + host + if (path.isNotBlank() && path != "/") path else ""
        button.isEnabled = false
        activityScope.launch {
            val version = runCatching { PanelUpdate.deployedVersion(url) }.getOrNull()
            if (version == null) {
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    MaterialAlertDialogBuilder(this@onAddExistingPanel)
                        .setTitle(R.string.cloud_add_existing)
                        .setMessage(R.string.cloud_panel_notcat_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            PanelDeploymentStore(this@onAddExistingPanel).rememberLast(url, "")
                            renderCloudDeploymentHistory()
                            Toast.makeText(this@onAddExistingPanel, R.string.cloud_panel_added, Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        .show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    PanelDeploymentStore(this@onAddExistingPanel).rememberLast(url, "")
                    renderCloudDeploymentHistory()
                    Toast.makeText(this@onAddExistingPanel, getString(R.string.cloud_panel_added_version, version), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
    }
}

internal fun MainActivity.applyPanelVersionBadge(
    badge: TextView,
    deployed: String?,
    newest: PanelUpdate.PanelScript?,
) {
    when {
        deployed == null -> {
            badge.setTextColor(TEXT_SECONDARY)
            badge.text = getString(R.string.cloud_panel_version_unknown)
        }
        newest != null && AppUpdatePolicy.isNewer(newest.version, deployed) -> {
            badge.setTextColor(TEAL)
            badge.text = getString(R.string.cloud_panel_update_ready, deployed, newest.version)
        }
        else -> {
            badge.setTextColor(TEXT_SECONDARY)
            badge.text = getString(R.string.cloud_panel_uptodate, deployed)
        }
    }
}

/**
 * A live panel is never moved backwards — not offered, not performed. A stale source once
 * installed panel 5.23.13 over a working 6.x deployment («the panel went back to the old
 * version»). Returns true when the move was blocked (the user has been told why).
 */
internal fun MainActivity.blockPanelDowngrade(deployed: String?, candidate: String): Boolean {
    if (!PanelUpdate.isDowngrade(deployed, candidate)) return false
    panelUpdateInProgress = false
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_update_downgrade_title)
        .setMessage(getString(R.string.cloud_update_downgrade_msg, deployed.orEmpty(), candidate))
        .setPositiveButton(android.R.string.ok, null)
        .show()
    return true
}

internal fun MainActivity.showPanelUpdateDialog(deployment: PanelDeploymentRecord) {
    if (panelUpdateInProgress) return
    panelUpdateInProgress = true
    Toast.makeText(this, R.string.cloud_panel_version_checking, Toast.LENGTH_SHORT).show()
    activityScope.launch {
        try {
            val newest = newestPanelScript()
            val deployed = PanelUpdate.deployedVersion(deployment.workerUrl)
            // (The old dialog ignored hasUpdate and installed whatever source it had found.)
            if (blockPanelDowngrade(deployed, newest.version)) return@launch
            val hasUpdate = deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed)
            presentPanelUpdateDialog(deployment, deployed, newest, hasUpdate)
        } catch (e: Exception) {
            panelUpdateInProgress = false
            Toast.makeText(
                this@showPanelUpdateDialog,
                getString(R.string.cloud_update_failed, e.message ?: e::class.java.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

internal fun MainActivity.presentPanelUpdateDialog(
    deployment: PanelDeploymentRecord,
    deployed: String?,
    newest: PanelUpdate.PanelScript,
    hasUpdate: Boolean,
) {
    // The Cloudflare token was saved at deploy/update time: this flow only
    // confirms the panel password, the saved token authorizes the upload.
    val savedToken = PanelDeploymentStore(this).tokenFor(deployment.workerUrl)
    if (!savedToken.isNullOrBlank()) {
        presentPanelUpdatePasswordDialog(deployment, deployed, newest, hasUpdate, savedToken)
        return
    }
    // No stored token: default to the token-free guided update. The user
    // explicitly never wants to be asked for a Cloudflare API token.
    presentTokenlessPanelUpdate(deployment, deployed, newest, hasUpdate)
}

/**
 * Token-free panel update: the newest worker code (release asset, or the copy
 * bundled in this APK when GitHub is unreachable) goes to the clipboard and the
 * Cloudflare dashboard opens — paste into the worker's Edit code and Deploy.
 * No API token is ever requested.
 */
internal fun MainActivity.presentTokenlessPanelUpdate(
    deployment: PanelDeploymentRecord,
    deployed: String?,
    newest: PanelUpdate.PanelScript,
    @Suppress("UNUSED_PARAMETER") hasUpdate: Boolean,
) {
    val host = deployment.workerUrl.removePrefix("https://")
    val workerName = if (host.endsWith(".workers.dev")) {
        host.removeSuffix(".workers.dev").substringAfterLast('.')
    } else {
        host.substringBefore('/')
    }
    val message = getString(R.string.cloud_update_tokenless_msg, workerName, deployed ?: "?", newest.version)
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_update_tokenless_title)
        .setMessage(message)
        .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
        .setNeutralButton(R.string.cloud_update_tokenless_btn) { _, _ ->
            panelUpdateInProgress = false
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", newest.text))
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/?to=/:account/workers")))
            }
            Toast.makeText(this, R.string.cloud_update_tokenless_go, Toast.LENGTH_LONG).show()
        }
        .setPositiveButton(R.string.cloud_update_confirm) { _, _ ->
            Toast.makeText(this, R.string.cloud_update_running, Toast.LENGTH_SHORT).show()
            activityScope.launch {
                val selfUpdated = CloudflareWorker.triggerPanelSelfUpdate(this@presentTokenlessPanelUpdate, deployment.workerUrl)
                if (selfUpdated != null) {
                    panelUpdateInProgress = false
                    renderCloudDeploymentHistory()
                    showCloudUpdateDialog(selfUpdated)
                } else {
                    presentPanelTokenPrompt(deployment, deployed, newest)
                }
            }
        }
        .setOnCancelListener { panelUpdateInProgress = false }
        .show()
}

/** Inline Cloudflare-token prompt (also the recovery path for an expired token). */
internal fun MainActivity.presentPanelTokenPrompt(
    deployment: PanelDeploymentRecord,
    deployed: String?,
    newest: PanelUpdate.PanelScript,
) {
    val hasUpdate = deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed)
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
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(24), dp(6), dp(24), 0)
        addView(tokenLayout, LinearLayout.LayoutParams(-1, -2))
    }
    val message = if (hasUpdate) {
        getString(
            R.string.cloud_update_message,
            deployment.workerUrl.removePrefix("https://"),
            deployed ?: "?",
            newest.version,
        )
    } else {
        getString(R.string.cloud_update_uptodate_message, newest.version)
    }
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_update_title)
        .setMessage(message)
        .setView(box)
        .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
        .setNeutralButton(R.string.cloud_get_token_short) { _, _ ->
            panelUpdateInProgress = false
            openCloudflareTokenPage()
        }
        .setPositiveButton(
            if (hasUpdate) R.string.cloud_update_confirm else R.string.cloud_update_reinstall,
            null,
        )
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val token = tokenInput.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            runPanelUpdate(deployment, token, deployed, newest)
        }
    }
    dialog.setOnCancelListener { panelUpdateInProgress = false }
    dialog.show()
}

/**
 * Update confirmation. The saved Cloudflare token IS the ownership proof —
 * it performs the upload — so the panel password never gates a redeploy.
 * (A crashed panel cannot answer a login challenge; asking for a password
 * first is what used to brick the recovery path.)
 */
internal fun MainActivity.presentPanelUpdatePasswordDialog(
    deployment: PanelDeploymentRecord,
    deployed: String?,
    newest: PanelUpdate.PanelScript,
    hasUpdate: Boolean,
    savedToken: String,
) {
    val message = if (hasUpdate) {
        getString(
            R.string.cloud_update_message,
            deployment.workerUrl.removePrefix("https://"),
            deployed ?: "?",
            newest.version,
        )
    } else {
        getString(R.string.cloud_update_uptodate_message, newest.version)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_update_title)
        .setMessage(message)
        .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
        .setPositiveButton(if (hasUpdate) R.string.cloud_update_confirm else R.string.cloud_update_reinstall) { _, _ ->
            runPanelUpdate(deployment, savedToken, deployed, newest)
        }
        .setOnCancelListener { panelUpdateInProgress = false }
        .show()
}

internal fun MainActivity.runPanelUpdate(
    deployment: PanelDeploymentRecord,
    token: String,
    deployed: String?,
    script: PanelUpdate.PanelScript,
    secretValues: Map<String, String> = emptyMap(),
) {
    Toast.makeText(this, R.string.cloud_update_running, Toast.LENGTH_SHORT).show()
    activityScope.launch {
        try {
            val permissions = CloudflareWorker.verifyToken(token)
            val accountId = permissions.accountId
            if (!permissions.valid || accountId == null) {
                panelUpdateInProgress = false
                Toast.makeText(
                    this@runPanelUpdate,
                    getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                    Toast.LENGTH_LONG,
                ).show()
                // The saved token can no longer upload (expired/revoked/scopes) —
                // NEVER ask for a token again: fall back to the token-free flow.
                panelUpdateInProgress = false
                presentTokenlessPanelUpdate(deployment, deployed, script, true)
                return@launch
            }
            PanelDeploymentStore(this@runPanelUpdate).rememberToken(deployment.workerUrl, token)
            // No secret re-entry prompts: updateBuiltIn preserves unreadable
            // secrets via keep_secrets, so the update just runs.
            val outcome = CloudflareWorker.updateBuiltIn(
                this@runPanelUpdate,
                token,
                accountId,
                deployment.workerUrl,
                deployed ?: "",
                script,
                secretValues,
            )
            when (outcome) {
                is CloudflareWorker.PanelUpdateOutcome.Blocked -> {
                    // Defensive: with keep_secrets this no longer triggers.
                    panelUpdateInProgress = false
                    Toast.makeText(this@runPanelUpdate, R.string.cloud_update_running, Toast.LENGTH_LONG).show()
                }
                is CloudflareWorker.PanelUpdateOutcome.Success -> showCloudUpdateDialog(outcome)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this@runPanelUpdate,
                getString(R.string.cloud_update_failed, e.message ?: e::class.java.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            panelUpdateInProgress = false
            renderCloudDeploymentHistory()
        }
    }
}

/**
 * The deploy/update status line (6.67.0). A bare «pending» hid three different
 * situations; this one names the one that happened, using the `/api/version`
 * probe:
 *   • JSON back      → the file loaded and ran (the version is shown);
 *   • a non-JSON page → the edge answered but our code never ran — the classic
 *                       «Error 1101» with no report, i.e. a broken upload;
 *   • silence        → nothing reachable yet.
 * Plus the app/panel skew warning when the two halves disagree.
 */
internal fun MainActivity.panelStatusLine(
    verifiedOnline: Boolean,
    health: CloudflareWorker.PanelVersionProbe?,
    bundledVersion: String = "",
    panelVersion: String = "",
): String {
    val lines = mutableListOf<String>()
    when {
        verifiedOnline -> lines += if (panelVersion.isNotBlank()) {
            getString(R.string.cloud_panel_online_version, panelVersion)
        } else {
            getString(R.string.cloud_verified_online)
        }
        // A fresh workers.dev route answers the edge's own 404 page until the
        // Worker takes over. Saying «truncated upload, paste the file by hand»
        // for that is the one message that must never be wrong — it is a wait,
        // not a failure, and it clears itself.
        health != null && health.routeNotLiveYet -> {
            lines += getString(R.string.cloud_panel_route_pending, health.status.toString())
            if (health.bodyHead.isNotBlank()) {
                lines += getString(R.string.cloud_panel_never_loaded_body, health.bodyHead.take(120))
            }
        }
        health != null && health.scriptNeverLoaded -> {
            lines += getString(R.string.cloud_panel_never_loaded, health.status.toString())
            if (health.bodyHead.isNotBlank()) {
                lines += getString(R.string.cloud_panel_never_loaded_body, health.bodyHead.take(120))
            }
        }
        health == null || health.unreachable -> lines += getString(R.string.cloud_panel_unreachable)
        else -> lines += getString(R.string.cloud_verify_pending)
    }
    if (bundledVersion.isNotBlank() && panelVersion.isNotBlank() && bundledVersion != panelVersion) {
        lines += getString(R.string.cloud_panel_version_skew, panelVersion, bundledVersion)
    }
    return lines.joinToString("\n")
}

internal fun MainActivity.showCloudUpdateDialog(result: CloudflareWorker.PanelUpdateOutcome.Success) {
    val status = panelStatusLine(
        verifiedOnline = result.verifiedOnline,
        health = result.health,
        bundledVersion = result.bundledVersion,
        panelVersion = result.panelVersion,
    )
    val kvLine = if (result.kvBound) getString(R.string.cloud_kv_bound) else getString(R.string.cloud_kv_missing)
    val message = getString(R.string.cloud_update_done, result.toVersion) + "\n" + status + "\n" + kvLine +
        "\n\n" + result.panelUrl
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_update_title)
        .setMessage(message)
        .setPositiveButton(R.string.cloud_open_panel) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
        }
        .setNegativeButton(android.R.string.ok, null)
        .show()
}

/**
 * Cloudflare account + token entry, in the ONLY order that works when the
 * user is not signed in yet.
 *
 * The token template link carries query parameters; opened while signed
 * out, Cloudflare's login page validates the whole URL as an OAuth
 * redirect_uri and rejects it — the user gets «Invalid redirect_uri» at the
 * exact moment they are creating an account. So this dialog leads with the
 * two parameter-free links (sign-up / login), then the template link, and
 * keeps a plain tokens link as the escape hatch.
 */
internal fun MainActivity.openCloudflareTokenPage() {
    var dialog: androidx.appcompat.app.AlertDialog? = null
    val body = wizardBody(
        wizardText(getString(R.string.cloud_account_tip), secondary = true),
        cloudAccountLinkButton(R.string.cloud_account_signup, CloudflareAccountLinks.SIGNUP) { dialog },
        cloudAccountLinkButton(R.string.cloud_account_login, CloudflareAccountLinks.LOGIN) { dialog },
        cloudAccountLinkButton(R.string.cloud_get_token_open, CloudflareWorker.CF_TOKEN_TEMPLATE_URL) { dialog },
        cloudAccountLinkButton(R.string.cloud_token_plain, CloudflareAccountLinks.TOKENS) { dialog },
    )
    dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_get_token)
        .setView(body)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.show()
}

/** Full-width link button for the account/token chooser. */
internal fun MainActivity.cloudAccountLinkButton(
    @StringRes labelRes: Int,
    url: String,
    dialog: () -> androidx.appcompat.app.AlertDialog?,
): MaterialButton = cloudActionButton(labelRes, R.drawable.ic_cloud_tab, accent = false) {
    openExternalUrl(url)
    Toast.makeText(this, R.string.cloud_account_after, Toast.LENGTH_LONG).show()
    dialog()?.dismiss()
}

/**
 * Copies the official one-click Deploy-to-Cloudflare link and opens the
 * system share sheet — the recipient needs neither Cat Client nor an API
 * token, just a (free) Cloudflare account.
 */
internal fun MainActivity.shareDeployButtonLink() {
    val url = CloudflareWorker.DEPLOY_BUTTON_URL
    runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-deploy-link", url))
    }
    Toast.makeText(this, R.string.cloud_share_deploy_copied, Toast.LENGTH_SHORT).show()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cloud_share_deploy_subject))
        putExtra(Intent.EXTRA_TEXT, getString(R.string.cloud_share_deploy_text, url))
    }
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.cloud_share_deploy_subject))) }
}

/**
 * «Copy Worker code» — hands the clipboard the OFFICIAL paste artifact: the
 * 3-line `catpanel.min.js` (~474 KB) built by CI, not the ~1.2 MB obfuscated
 * bundle this app uploads over the API. A big string is one interrupted paste
 * away from a truncated Worker, and a truncated Worker returns Cloudflare's
 * «Error 1101» for every request.
 *
 * The big bundle stays the fallback for APKs built without the paste asset
 * (local builds); that fallback is announced in the toast instead of being
 * silent, so nobody pastes 1.2 MB believing it is the small file.
 */
internal fun MainActivity.copyPanelPasteCode() {
    val paste = CloudflareWorker.builtInPasteScript(this)
    val official = CloudflareWorker.pasteScriptIsSane(paste)
    val localText = if (official) paste else runCatching { CloudflareWorker.builtInWorkerScript(this) }.getOrNull()
    val remoteText = newestPanelCache?.takeIf {
        CloudflareWorker.pasteScriptIsSane(it.text) &&
            PanelUpdate.preferRelease(PanelUpdate.parseVersion(localText.orEmpty()).orEmpty(), it.version)
    }?.text
    val text = remoteText ?: localText
    if (text.isNullOrBlank()) {
        Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val copied = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", text))
    }.isSuccess
    if (!copied) {
        Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(
        this,
        if (official || remoteText != null) R.string.cloud_code_copied else R.string.cloud_code_copied_large,
        Toast.LENGTH_LONG,
    ).show()
}

internal fun MainActivity.showWizardDeployedDialog(result: CloudflareWorker.WizardDeploymentResult) {
    val status = if (result.verifiedOnline) {
        getString(R.string.cloud_verified_online)
    } else {
        getString(R.string.cloud_verify_pending)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_wizard_deployed)
        .setMessage(status + "\n\n" + result.wizardUrl + "\n\n" + getString(R.string.cloud_wizard_share_hint))
        .setPositiveButton(R.string.cloud_open_panel) { _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.wizardUrl))) }
        }
        .setNeutralButton(R.string.cloud_copy_all) { _, _ ->
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-wizard", result.wizardUrl))
                Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(android.R.string.ok, null)
        .show()
}

internal fun MainActivity.wizardField(hint: String, password: Boolean = false, initial: String = ""): Pair<TextInputLayout, TextInputEditText> {
    val edit = TextInputEditText(this).apply {
        setText(initial)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textDirection = View.TEXT_DIRECTION_LTR
        typeface = CatClientBodyTypeface
    }
    val layout = TextInputLayout(this).apply {
        this.hint = hint
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        addView(edit, LinearLayout.LayoutParams(-1, -2))
    }
    return layout to edit
}

internal fun MainActivity.wizardBody(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    setPadding(dp(24), dp(12), dp(24), dp(4))
    views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
}

internal fun MainActivity.wizardText(text: CharSequence, secondary: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = if (secondary) 12.5f else 14f
    typeface = CatClientBodyTypeface
    setTextColor(if (secondary) TEXT_SECONDARY else TEXT_PRIMARY)
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
}

/** Step 1/4 — what is about to happen. */
internal fun MainActivity.showPanelWizard() {
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.wizard_step, 1, 4) + " · " + getString(R.string.wizard_title))
        .setView(wizardBody(wizardText(getString(R.string.wizard_intro)), wizardText(getString(R.string.wizard_intro_note), secondary = true)))
        .setPositiveButton(R.string.wizard_next) { _, _ -> showPanelWizardToken() }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .show()
}

/** Step 2/4 — Cloudflare API token (verified before moving on). */
internal fun MainActivity.showPanelWizardToken(prefill: String = "", error: String? = null) {
    val (tokenLayout, tokenEdit) = wizardField(getString(R.string.cloud_token_hint), initial = prefill)
    if (error != null) tokenLayout.error = error
    val getToken = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.wizard_get_token)
        isAllCaps = false
        typeface = CatClientBodyBoldTypeface
        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CloudflareWorker.CF_TOKEN_TEMPLATE_URL))) } }
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.wizard_step, 2, 4) + " · " + getString(R.string.wizard_token_title))
        .setView(wizardBody(wizardText(getString(R.string.wizard_token_help)), getToken, tokenLayout, wizardText(getString(R.string.wizard_token_privacy), secondary = true)))
        .setPositiveButton(R.string.wizard_next, null)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val token = tokenEdit.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) { tokenLayout.error = getString(R.string.cloud_token_required); return@setOnClickListener }
            tokenLayout.error = null
            tokenLayout.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            activityScope.launch {
                val permissions = runCatching { CloudflareWorker.verifyToken(token) }.getOrNull()
                val accountId = permissions?.accountId
                if (permissions == null || !permissions.valid || accountId == null) {
                    dialog.dismiss()
                    showPanelWizardToken(token, getString(R.string.cloud_token_invalid, permissions?.missingScopes?.joinToString(" + ") ?: "network"))
                } else {
                    dialog.dismiss()
                    showPanelWizardOptions(token, accountId)
                }
            }
        }
    }
    dialog.show()
}

/** Step 3/4 — name + password, then deploy with a progress dialog. */
internal fun MainActivity.showPanelWizardOptions(token: String, accountId: String) {
    val (nameLayout, nameEdit) = wizardField(getString(R.string.cloud_worker_name_hint), initial = CloudflareWorker.randomWorkerName())
    val (passLayout, passEdit) = wizardField(getString(R.string.cloud_panel_password_hint), password = true, initial = "admin")
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.wizard_step, 3, 4) + " · " + getString(R.string.wizard_options_title))
        .setView(wizardBody(wizardText(getString(R.string.wizard_options_help)), nameLayout, passLayout, wizardText(getString(R.string.wizard_options_note), secondary = true)))
        .setPositiveButton(R.string.wizard_deploy) { _, _ ->
            val workerName = nameEdit.text?.toString()?.trim()?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9-]"), "-")?.replace(Regex("-{2,}"), "-")?.trim('-')?.ifEmpty { CloudflareWorker.randomWorkerName() } ?: CloudflareWorker.randomWorkerName()
            val password = passEdit.text?.toString()?.trim().orEmpty()
            val progress = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wizard_step, 4, 4) + " · " + getString(R.string.wizard_deploying_title))
                .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_deploying, workerName))))
                .setCancelable(false)
                .show()
            activityScope.launch {
                try {
                    val result = CloudflareWorker.deployBuiltIn(this@showPanelWizardOptions, token, accountId, workerName, panelPassword = password)
                    PanelDeploymentStore(this@showPanelWizardOptions).rememberToken(result.workerUrl, token)
                    PanelDeploymentStore(this@showPanelWizardOptions).rememberLast(result.workerUrl, result.uuid)
                    renderCloudDeploymentHistory()
                    progress.dismiss()
                    showPanelWizardDone(result)
                } catch (e: Exception) {
                    progress.dismiss()
                    MaterialAlertDialogBuilder(this@showPanelWizardOptions)
                        .setTitle(R.string.wizard_failed_title)
                        .setMessage(getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName))
                        .setPositiveButton(R.string.wizard_retry) { _, _ -> showPanelWizardOptions(token, accountId) }
                        .setNegativeButton(R.string.split_tunnel_cancel, null)
                        .show()
                }
            }
        }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .show()
}

/**
 * Panel base for push APIs (ips / extraSnis). Priority: KNOWN deployments
 * recorded in this app (deploys + Add-existing + wizard) — never a guess;
 * falls back to panel hosts detected inside subscriptions only when no
 * explicit record exists.
 */
internal fun MainActivity.panelBaseUrl(): String? {
    PanelDeploymentStore(this).deployments().firstOrNull()?.let { return it.workerUrl.trimEnd('/') }
    return detectPanelSnisFromSubscriptions().firstOrNull()?.let { "https://$it" }
}

/** Panel base picked EXPLICITLY by the user when several are known. */
internal suspend fun MainActivity.pickPanelBaseInteractive(): String? {
    val known = PanelDeploymentStore(this).deployments().map { it.workerUrl.trimEnd('/') }.distinct()
    val detected = detectPanelSnisFromSubscriptions().map { "https://$it" }
    val all = (known + detected).distinct()
    if (all.isEmpty()) return null
    if (all.size == 1) return all[0]
    return suspendCancellableCoroutine { cont ->
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pip_pick_panel)
            .setItems(all.map { it.removePrefix("https://") }.toTypedArray()) { _, which -> cont.resume(all[which]) }
            .setOnCancelListener { cont.resume(null) }
            .show()
    }
}

internal fun MainActivity.showCloudDeploymentDialog(result: CloudflareWorker.DeploymentResult) {
    PanelDeploymentStore(this).rememberLast(result.workerUrl, result.uuid)
    renderCloudDeploymentHistory()
    val status = panelStatusLine(
        verifiedOnline = result.verifiedOnline,
        health = if (result.panelUnreachable) null else result.health,
        bundledVersion = result.bundledVersion,
        panelVersion = result.panelVersion,
    )
    val kvLine = if (result.kvBound) getString(R.string.cloud_kv_bound) else getString(R.string.cloud_kv_missing)
    val message = status + "\n" + kvLine + "\n\n" +
        getString(R.string.cloud_panel_url) + ":\n" + result.panelUrl + "\n\n" +
        getString(R.string.cloud_sub_label) + ":\n" + result.subscriptionUrl + "\n\n" +
        getString(R.string.cloud_uuid_is_password, result.uuid)
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cloud_deployed)
        .setMessage(message)
        .setPositiveButton(R.string.cloud_import_sub) { _, _ ->
            showAddSubscriptionDialog(result.subscriptionUrl, "Cat Panel")
        }
        .setNegativeButton(R.string.cloud_open_panel) { _, _ ->
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl)))
            }
        }
        .setNeutralButton(R.string.cloud_copy_all) { _, _ ->
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = "Panel: ${result.panelUrl}\nSub: ${result.subscriptionUrl}\nUUID / password: ${result.uuid}"
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel", text))
                Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_LONG).show()
            }
        }
        .setOnCancelListener {
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-sub", result.subscriptionUrl))
                Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_LONG).show()
                openScreen(SCREEN_SERVERS)
            }
        }
        .show()
}

/**
 * 🩺 Three-point panel test: `/health`, `/api/version`, `/api/selfcheck?throw=1`.
 *
 * The three answers separate the two meanings of «Error 1101» — JSON back means the
 * module loaded and ran (so the failure is per-request and the panel reports it
 * itself), anything else means the file never loaded (re-upload; only if that
 * repeats on a fresh name is it a suspension). Runs against the deployed worker, or
 * against a URL the owner pastes when nothing is stored.
 */
internal fun MainActivity.showPanelProbeDialog() {
    val deployed = runCatching {
        PanelDeploymentStore(this).deployments().firstOrNull()?.workerUrl
    }.getOrNull().orEmpty().trim()
    if (deployed.isBlank()) {
        val input = EditText(this).apply {
            hint = getString(R.string.panel_probe_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setText("https://")
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.panel_probe_title)
            .setView(input)
            .setPositiveButton(R.string.panel_probe_title) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isBlank() || typed == "https://") {
                    Toast.makeText(this, R.string.panel_probe_need_url, Toast.LENGTH_SHORT).show()
                } else {
                    runPanelProbe(typed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
        return
    }
    runPanelProbe(deployed)
}

internal fun MainActivity.runPanelProbe(workerUrl: String) {
    Toast.makeText(this, R.string.panel_probe_running, Toast.LENGTH_SHORT).show()
    activityScope.launch {
        val probes = withContext(Dispatchers.IO) {
            runCatching { CloudflareWorker.probePanelRoutes(workerUrl) }.getOrDefault(emptyList())
        }
        if (probes.isEmpty()) {
            Toast.makeText(this@runPanelProbe, R.string.panel_probe_need_url, Toast.LENGTH_SHORT).show()
            return@launch
        }
        val text = buildString {
            append(workerUrl.trimEnd('/')).append('\n')
            probes.forEach { probe ->
                append('\n').append(probe.path).append("  →  HTTP ").append(probe.status)
                if (probe.contentType.isNotBlank()) append("  ").append(probe.contentType)
                append('\n')
                append(
                    getString(
                        when (probe.verdict) {
                            "code-ran" -> R.string.panel_probe_ran
                            "code-never-ran" -> R.string.panel_probe_never
                            "unreachable" -> R.string.panel_probe_unreachable
                            else -> R.string.panel_probe_other
                        },
                    ),
                ).append('\n')
                if (probe.bodyHead.isNotBlank()) append("   ").append(probe.bodyHead.take(140)).append('\n')
            }
            append('\n')
            append(getString(if (probes.any { it.codeRan }) R.string.panel_probe_hint_ran else R.string.panel_probe_hint_never))
        }
        DiagnosticLogger.info(this@runPanelProbe, "panel.probe", text.replace('\n', ' ').take(400))
        val view = TextView(this@runPanelProbe).apply {
            this.text = text
            textSize = 10.5f
            typeface = CatClientDataTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val scroll = android.widget.ScrollView(this@runPanelProbe).apply { addView(view) }
        MaterialAlertDialogBuilder(this@runPanelProbe)
            .setTitle(R.string.panel_probe_title)
            .setView(scroll)
            .setPositiveButton(R.string.panel_probe_copy) { _, _ ->
                (getSystemService(ClipboardManager::class.java))
                    .setPrimaryClip(ClipData.newPlainText("Cat Client panel probe", text))
                Toast.makeText(this@runPanelProbe, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
