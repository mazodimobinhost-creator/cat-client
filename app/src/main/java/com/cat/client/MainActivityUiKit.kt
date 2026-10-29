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

// Shared card/tile/segment widgets (zed* look) used by many screens.
// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.

/** Tonal container + ink for a hue, the way each Zed settings row gets its own colour. */
internal fun MainActivity.zedTone(hue: Float): Pair<Int, Int> {
    val dark = palette.isDark
    val container = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, if (dark) 0.42f else 0.62f, if (dark) 0.30f else 0.86f))
    val ink = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, if (dark) 0.75f else 0.70f, if (dark) 0.82f else 0.28f))
    return container to ink
}

internal fun MainActivity.zedSectionLabel(@StringRes labelRes: Int): TextView = TextView(this).apply {
    setText(labelRes)
    textSize = 14f
    typeface = CatClientBodyBoldTypeface
    setTextColor(zedScheme.primary)
    includeFontPadding = false
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    textDirection = View.TEXT_DIRECTION_LOCALE
    setPaddingRelative(dp(26), dp(26), dp(16), dp(12))
}

/** Zed grouped list: 28 dp outer corners, 6 dp inner corners, 3 dp gaps. */
internal fun MainActivity.zedApplyGroupCorners(group: LinearLayout) {
    val items = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility != View.GONE }
    val big = dp(28).toFloat()
    val small = dp(6).toFloat()
    items.forEachIndexed { index, item ->
        val top = if (index == 0) big else small
        val bottom = if (index == items.lastIndex) big else small
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        when (val bg = item.background) {
            is RippleDrawable -> (bg.getDrawable(0) as? GradientDrawable)?.cornerRadii = radii
            is GradientDrawable -> bg.cornerRadii = radii
        }
        (item.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = if (index == 0) 0 else dp(3)
            item.layoutParams = it
        }
    }
}

internal fun MainActivity.zedCardBackground(color: Int = zedScheme.surfaceContainer, ripple: Boolean = false): android.graphics.drawable.Drawable {
    val shape = GradientDrawable().apply {
        this.shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(color)
    }
    return if (ripple) RippleDrawable(ColorStateList.valueOf(withAlpha(zedScheme.onSurface, 28)), shape, null) else shape
}

internal fun MainActivity.zedChevron(tint: Int = zedScheme.onSurfaceVariant): ImageView = ImageView(this).apply {
    setImageResource(R.drawable.ic_zed_chevron_right)
    imageTintList = ColorStateList.valueOf(tint)
    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** ZedSecure SettingsItem: shape badge · title · detail · chevron. */
internal fun MainActivity.zedSettingsItem(
    title: CharSequence,
    detail: CharSequence?,
    shape: ZedShape,
    hue: Float,
    @DrawableRes iconRes: Int,
    badge: View? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
): LinearLayout {
    val (container, ink) = if (danger) zedScheme.errorContainer to zedScheme.onErrorContainer else zedTone(hue)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(78)
        setPaddingRelative(dp(16), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = title
        background = zedCardBackground(ripple = true)
        val icon = ZedShapeIconView(this@zedSettingsItem, shape, container, iconRes, ink, 0f, 0.48f)
        setOnClickListener {
            icon.animate().rotationBy(30f).setDuration(320L).start()
            onClick()
        }
        addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(
            LinearLayout(this@zedSettingsItem).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(TextView(this@zedSettingsItem).apply {
                    text = title
                    textSize = 17f
                    typeface = CatClientBodyTypeface
                    setTextColor(if (danger) zedScheme.error else zedScheme.onSurface)
                    includeFontPadding = false
                })
                if (!detail.isNullOrBlank()) {
                    addView(TextView(this@zedSettingsItem).apply {
                        text = detail
                        textSize = 13.5f
                        typeface = CatClientBodyTypeface
                        setTextColor(zedScheme.onSurfaceVariant)
                        includeFontPadding = false
                        setLineSpacing(dp(2).toFloat(), 1f)
                        maxLines = 3
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
        )
        badge?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        addView(zedChevron(), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })
    }
}

/** Big page title with the Zed art in the corner ("Settings · Make it yours", "Interface"). */
internal fun MainActivity.zedPageHero(title: CharSequence, subtitle: CharSequence, art: View, titleSize: Float = 40f): FrameLayout =
    FrameLayout(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        clipChildren = false
        addView(art, FrameLayout.LayoutParams(dp(150), dp(150)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        addView(
            LinearLayout(this@zedPageHero).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(TextView(this@zedPageHero).apply {
                    text = title
                    textSize = titleSize
                    typeface = CatClientDisplayTypeface
                    setTextColor(zedScheme.onSurface)
                    includeFontPadding = false
                })
                addView(TextView(this@zedPageHero).apply {
                    text = subtitle
                    textSize = 15f
                    typeface = CatClientBodyTypeface
                    setTextColor(zedScheme.onSurfaceVariant)
                    includeFontPadding = false
                    maxLines = 2
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            },
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginEnd = dp(140)
            },
        )
        minimumHeight = dp(160)
    }

/** Gradient banner (Zed "Support the project" slot) — here it opens the plain-language guide. */
internal fun MainActivity.zedGuideCard(): LinearLayout {
    val s = zedScheme
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(18), dp(20), dp(18), dp(20))
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.settings_guide_title)
        val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(s.onPrimaryContainer, 30)),
            GradientDrawable(
                if (ltr) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(s.tertiaryContainer, s.primaryContainer),
            ).apply { cornerRadius = dp(34).toFloat() },
            null,
        )
        setOnClickListener { showPlainLanguageFullGuideDialog() }
        addView(
            ZedShapeIconView(this@zedGuideCard, ZedShape.Cookie4, withAlpha(s.onPrimaryContainer, 40), R.drawable.ic_zed_help, s.onPrimaryContainer, 0f, 0.5f),
            LinearLayout.LayoutParams(dp(54), dp(54)),
        )
        addView(
            LinearLayout(this@zedGuideCard).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@zedGuideCard).apply {
                    setText(R.string.settings_guide_title)
                    textSize = 18f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(s.onPrimaryContainer)
                    includeFontPadding = false
                })
                addView(TextView(this@zedGuideCard).apply {
                    setText(R.string.settings_guide_detail)
                    textSize = 14f
                    typeface = CatClientBodyTypeface
                    setTextColor(withAlpha(s.onPrimaryContainer, 210))
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
        )
        addView(zedChevron(s.onPrimaryContainer), LinearLayout.LayoutParams(dp(22), dp(22)))
    }
}

/** Material 3 connected button group (Theme: System · Light · Dark, Contrast: …). */
internal fun MainActivity.zedSegmented(options: List<Pair<String, Int>>, selectedIndex: Int, onPick: (Int) -> Unit): LinearLayout {
    val s = zedScheme
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        options.forEachIndexed { index, (label, iconRes) ->
            val selected = index == selectedIndex
            val ink = if (selected) s.onPrimaryContainer else s.onSurface
            addView(
                LinearLayout(this@zedSegmented).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (selected) getString(R.string.design_selected_description, label) else label
                    setPadding(dp(8), 0, dp(8), 0)
                    background = RippleDrawable(
                        ColorStateList.valueOf(withAlpha(s.primary, 40)),
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(if (selected) 28 else 14).toFloat()
                            if (selected) setColor(s.primaryContainer) else {
                                setColor(Color.TRANSPARENT)
                                setStroke(dp(1), s.outlineVariant)
                            }
                        },
                        null,
                    )
                    setOnClickListener { if (!selected) onPick(index) }
                    if (iconRes != 0) {
                        addView(ImageView(this@zedSegmented).apply {
                            setImageResource(iconRes)
                            imageTintList = ColorStateList.valueOf(ink)
                        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                    }
                    addView(TextView(this@zedSegmented).apply {
                        text = label
                        textSize = 15f
                        typeface = if (selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
                        setTextColor(ink)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    })
                },
                LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (index > 0) marginStart = dp(4) },
            )
        }
    }
}

internal fun MainActivity.zedTitledCard(@StringRes titleRes: Int, @StringRes detailRes: Int, body: View?): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = zedCardBackground()
        addView(TextView(this@zedTitledCard).apply {
            setText(titleRes)
            textSize = 18f
            typeface = CatClientBodyTypeface
            setTextColor(zedScheme.onSurface)
            includeFontPadding = false
        })
        if (detailRes != 0) {
            addView(TextView(this@zedTitledCard).apply {
                setText(detailRes)
                textSize = 14f
                typeface = CatClientBodyTypeface
                setTextColor(zedScheme.onSurfaceVariant)
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        body?.let { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }) }
    }

/** Selectable preview tile with a label underneath and a ✓ badge on the chosen one. */
internal fun MainActivity.zedPickTile(preview: View, label: String, selected: Boolean, tileColor: Int, heightDp: Int, onPick: () -> Unit): LinearLayout {
    val s = zedScheme
    val frame = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(tileColor)
            setStroke(dp(if (selected) 3 else 1), if (selected) s.primary else s.outlineVariant)
        }
        clipToOutline = true
        val inset = dp(if (selected) 3 else 1)
        addView(preview, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
        if (selected) {
            addView(
                ZedShapeIconView(this@zedPickTile, ZedShape.Circle, s.primaryContainer, R.drawable.ic_zed_check, s.onPrimaryContainer, 0f, 0.56f),
                FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(8)
                    marginEnd = dp(8)
                },
            )
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        contentDescription = if (selected) getString(R.string.design_selected_description, label) else label
        setOnClickListener { if (!selected) onPick() }
        ZedIosMotion.press(this)
        addView(frame, LinearLayout.LayoutParams(-1, dp(heightDp)))
        addView(TextView(this@zedPickTile).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = if (selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
            setTextColor(if (selected) s.primary else s.onSurfaceVariant)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
}

/** A Cloud-tab card for one Node host: title, one plain sentence, and the same 7 buttons on every host (🧭 from zero first). */
internal fun MainActivity.nodeHostCard(kind: String, @StringRes titleRes: Int, @StringRes descRes: Int): View {
    val card = advancedSettingsPanel()
    card.addView(
        TextView(this).apply {
            setText(titleRes)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    card.addView(
        TextView(this).apply {
            setText(descRes)
            textSize = 12.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    val git = kind == NodeHostPanel.LUCITY || kind == NodeHostPanel.SHIPRR
    // Bot-Hosting / Railway are driven end-to-end by their API: «⚡» IS the from-zero path (no 🧭 wizard, and
    // Railway has no files to upload, so no 📦 zip there).
    val api = NodeHostPanel.isApiHost(kind)
    // beta224 SmarterASP: upload-only (no API, no GitHub build, nothing to renew) — no ⚡ and no 🔁 buttons.
    val uploadOnly = NodeHostPanel.isUploadOnlyHost(kind)
    val buttons = listOfNotNull<Pair<Int, () -> Unit>>(
        if (api) null else Pair(R.string.cloud_node_start, { nodeHosts.showStart(kind) }),
        if (uploadOnly) null else Pair(if (git) R.string.cloud_node_auto_git else R.string.cloud_node_auto, { nodeHosts.autoInstall(kind) }),
        if (kind == NodeHostPanel.RAILWAY) null else Pair(R.string.cloud_node_zip, { nodeHosts.saveFiles(kind) }),
        Pair(R.string.cloud_node_register, { nodeHosts.registerManual(kind) }),
        if (uploadOnly) null else Pair(if (git || kind == NodeHostPanel.RAILWAY) R.string.cloud_node_redeploy else R.string.cloud_node_renew, { nodeHosts.renew(kind) }),
        Pair(R.string.cloud_node_mine, { nodeHosts.showMine(kind) }),
        Pair(R.string.cloud_node_guide, { nodeHosts.showGuide(kind) }),
    )
    buttons.forEachIndexed { i, (labelRes, action) ->
        card.addView(
            cloudActionButton(labelRes, R.drawable.ic_cloud_tab, accent = i == 0) { action() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 10 else 8) },
        )
    }
    return card
}

internal fun MainActivity.cloudActionButton(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    accent: Boolean,
    action: (View) -> Unit,
): MaterialButton = MaterialButton(this).apply {
    setText(labelRes)
    setIconResource(iconRes)
    iconTint = ColorStateList.valueOf(if (accent) TEAL else TEXT_SECONDARY)
    iconSize = dp(18)
    iconPadding = dp(8)
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    setAllCaps(false)
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
    textSize = 14f
    typeface = CatClientBodyBoldTypeface
    minWidth = 0
    minimumWidth = 0
    minHeight = dp(48)
    minimumHeight = dp(48)
    insetTop = 0
    insetBottom = 0
    cornerRadius = dp(18)
    setPaddingRelative(dp(16), 0, dp(16), 0)
    backgroundTintList = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 42) else withAlpha(palette.surfaceElevated2, 190))
    strokeWidth = dp(1)
    strokeColor = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 180) else withAlpha(OUTLINE, 220))
    rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 36))
    elevation = dp(2).toFloat()
    stateListAnimator = null
    setTextColor(if (accent) TEAL else TEXT_PRIMARY)
    setOnClickListener(action)
}

internal fun MainActivity.cloudScopeLabel(scope: CloudflareWorker.PanelScope): String = when (scope) {
    CloudflareWorker.PanelScope.CF_WORKER -> getString(R.string.cloud_scope_worker)
    CloudflareWorker.PanelScope.SERVER -> getString(R.string.cloud_scope_server)
    CloudflareWorker.PanelScope.TUNNEL -> getString(R.string.cloud_scope_tunnel)
}
