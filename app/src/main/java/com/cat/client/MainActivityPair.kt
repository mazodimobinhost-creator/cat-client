package com.cat.client

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Claim a panel pairing code generated in Cat Panel → Tools. */
internal fun MainActivity.showPanelPairDialog(deployment: PanelDeploymentRecord) {
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val host = deployment.workerUrl.removePrefix("https://")
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }
    box.addView(
        TextView(this).apply {
            text = if (fa) {
                "در پنل → ابزارها → «جفت‌شدن با اپ» کد ۶ حرفی بساز، بعد اینجا بزن."
            } else {
                "In the panel → Tools → «Pair with app» make a 6-character code, then enter it here."
            }
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
        },
    )
    val code = EditText(this).apply {
        hint = "AB12CD"
        textSize = 18f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(android.text.InputFilter.LengthFilter(8))
    }
    box.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    MaterialAlertDialogBuilder(this)
        .setTitle(if (fa) "جفت‌شدن با پنل" else "Pair with this panel")
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (fa) "ادعا" else "Claim") { _, _ ->
            val typed = code.text?.toString().orEmpty()
            if (!CatPanelPair.looksLikeCode(typed)) {
                Toast.makeText(
                    this,
                    if (fa) "کد باید ۶ حرف باشد" else "Code must be 6 characters",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setPositiveButton
            }
            Toast.makeText(this, if (fa) "در حال جفت‌شدن…" else "Pairing…", Toast.LENGTH_SHORT).show()
            activityScope.launch {
                val result = withContext(Dispatchers.IO) { CatPanelPair.claim(deployment.workerUrl, typed) }
                if (result.ok) {
                    CatPanelPairStore(this@showPanelPairDialog).remember(result.host.ifBlank { host }, result.version)
                    Toast.makeText(
                        this@showPanelPairDialog,
                        if (fa) {
                            "جفت شد · پنل v${result.version.ifBlank { "?" }}"
                        } else {
                            "Paired · panel v${result.version.ifBlank { "?" }}"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this@showPanelPairDialog,
                        if (fa) "جفت نشد: ${result.error}" else "Pair failed: ${result.error}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        .show()
}
