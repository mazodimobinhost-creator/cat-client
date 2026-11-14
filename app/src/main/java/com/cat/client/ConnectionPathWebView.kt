package com.cat.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Home WebView for assets/connection_path.html?app=1. JS on, no file access to the
 * rest of the device, no addJavascriptInterface. CatPath.load / setState via evaluateJavascript.
 * Sits in the hero circle only — the rest of Home stays native.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ConnectionPathWebView(context: Context) : WebView(context) {
    private var pageReady = false
    private var pendingJson: String? = null
    private var pendingState: String = "idle"
    private var pendingFail: String? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.connection_path_title)
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isNestedScrollingEnabled = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        // android_asset needs file access on API 26–29; FromFileURLs stays off so the page cannot read the rest of the device.
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.blockNetworkLoads = true
        settings.blockNetworkImage = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                if (pendingJson != null) {
                    flush()
                } else {
                    pauseJs()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
        loadUrl("file:///android_asset/connection_path.html?app=1")
    }

    fun bind(json: JSONObject?, state: String, failMsg: String?) {
        if (json == null) {
            pendingJson = null
            pauseJs()
            return
        }
        pendingJson = json.toString()
        pendingState = state
        pendingFail = failMsg
        contentDescription = when (state) {
            "connected" -> context.getString(R.string.connection_path_connected)
            "failed" -> context.getString(R.string.connection_path_failed)
            "connecting" -> context.getString(R.string.connection_path_connecting)
            else -> context.getString(R.string.connection_path_title)
        }
        if (visibility == VISIBLE) resumeJs()
        if (pageReady) flush()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lockParentScroll(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && pendingJson != null) resumeJs() else pauseJs()
    }

    override fun onDetachedFromWindow() {
        pauseJs()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && pendingJson != null) resumeJs() else pauseJs()
    }

    private fun lockParentScroll(lock: Boolean) {
        var p: View? = this
        while (p != null) {
            (p.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(lock)
            p = p.parent as? View
        }
    }

    private fun resumeJs() {
        onResume()
    }

    private fun pauseJs() {
        runCatching { onPause() }
    }

    private fun flush() {
        val j = pendingJson ?: return
        val st = when (pendingState) {
            "connected", "failed", "connecting", "idle", "ready" -> pendingState
            else -> "ready"
        }
        val msg = pendingFail.orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .take(180)
        val js = when {
            st == "ready" -> "try{CatPath.load($j)}catch(e){}"
            st == "failed" && msg.isNotBlank() ->
                "try{CatPath.load($j);CatPath.setState(\"failed\",\"$msg\")}catch(e){}"
            else -> "try{CatPath.load($j);CatPath.setState(\"$st\")}catch(e){}"
        }
        evaluateJavascript(js, null)
    }
}
