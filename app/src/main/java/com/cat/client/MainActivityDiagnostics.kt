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

// Diagnostics: copy/share logs and crash, update check, external links.
// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.

internal fun MainActivity.copyDiagnosticsToClipboard() {
    val diagnostics = DiagnosticLogger.read(this).ifBlank { getString(R.string.diagnostics_empty) }
    val clipboard = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Cat Client diagnostics", diagnostics).apply {
        // Keeps the clipboard preview toast from rendering the log on screen (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    DiagnosticLogger.info(this, "diagnostics.copy", "chars=${diagnostics.length}")
}

/** Copies the last crash (thread + version + stack) for a chat paste. */
internal fun MainActivity.copyCrashToClipboard() {
    val crash = runCatching { CrashWatch.lastCrashText(this) }.getOrDefault("")
    if (crash.isBlank()) {
        Toast.makeText(this, R.string.crash_report_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Cat Client crash", crash).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
    DiagnosticLogger.info(this, "crash.copy", "chars=${crash.length}")
}

internal fun MainActivity.shareDiagnostics() {
    activityScope.launch {
        val file = runCatching {
            withContext(Dispatchers.IO) { DiagnosticLogger.reportFile(this@shareDiagnostics) }
        }.getOrNull()
        if (file == null) {
            Toast.makeText(this@shareDiagnostics, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
            return@launch
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this@shareDiagnostics, "${packageName}.updates", file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this@shareDiagnostics, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
            return@launch
        }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.app_name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.diagnostics_share)))
            Toast.makeText(this@shareDiagnostics, R.string.diagnostics_shared, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.info(this@shareDiagnostics, "diagnostics.share")
        } catch (error: Exception) {
            DiagnosticLogger.warn(this@shareDiagnostics, "diagnostics.share.failed", error = error)
            Toast.makeText(this@shareDiagnostics, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun MainActivity.checkForUpdates() {
    appUpdateUi.check(manual = false)
}

internal fun MainActivity.openFooterLink() = openExternalUrl(getString(R.string.footer_url))

internal fun MainActivity.openExternalUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { error ->
        Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        DiagnosticLogger.warn(this, "external.open.failed", "url=$url", error)
    }
}

/** Opens a parameter-free Cloudflare account link and explains the order. */
internal fun MainActivity.openAccountEntry(url: String) {
    openExternalUrl(url)
    Toast.makeText(this, R.string.cloud_account_after, Toast.LENGTH_LONG).show()
}

@Suppress("DEPRECATION")
internal fun MainActivity.configureSystemBars() {
    window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    window.decorView.textDirection = View.TEXT_DIRECTION_LOCALE
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    var flags = window.decorView.systemUiVisibility
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        flags = if (palette.isDark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        flags = if (palette.isDark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    window.decorView.systemUiVisibility = flags
}
