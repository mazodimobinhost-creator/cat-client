package com.cat.client

import android.text.Editable
import android.text.TextWatcher
import android.view.View

/**
 * beta217: several boxes re-did heavy work on *every* key press — decoding every pasted config to show the
 * «detected N configs» line, filtering and redrawing the whole app list, rebuilding the scanner source list.
 * Typing in them lagged behind the fingers. This watcher waits until typing pauses for [delayMs] and then runs
 * [onSettled] once with the final text (on the main thread, through [host]'s message queue).
 */
internal class DebouncedTextWatcher(
    private val host: View,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val onSettled: (String) -> Unit,
) : TextWatcher {
    private var pending: Runnable? = null

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        val text = s?.toString().orEmpty()
        pending?.let { host.removeCallbacks(it) }
        val task = Runnable {
            pending = null
            onSettled(text)
        }
        pending = task
        host.postDelayed(task, delayMs)
    }

    internal companion object {
        const val DEFAULT_DELAY_MS = 250L
    }
}
