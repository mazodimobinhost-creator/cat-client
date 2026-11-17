package com.cat.client

import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Views of the home-screen real internet test card (built by MainActivity.buildRealInternetCard). */
internal class RealInternetCardViews(
    val card: LinearLayout,
    val title: TextView,
    val detail: TextView,
    val actions: LinearLayout,
    val next: MaterialButton,
)
