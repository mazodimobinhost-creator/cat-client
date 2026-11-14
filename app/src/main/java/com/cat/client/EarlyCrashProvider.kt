package com.cat.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * The earliest app code Android runs. Content providers are created before
 * Application.onCreate, so a crash inside another provider's initialization
 * (the classic «WorkDatabase created during androidx.startup», but any provider
 * qualifies) would otherwise kill the process before CrashWatch exists.
 *
 * initOrder is Int.MAX_VALUE — providers are initialized in initOrder order,
 * and this one has to be the very first so its uncaught handler is installed in
 * time. It only installs the watchdog; it never touches storage, network or
 * anything that could fail on its own.
 */
class EarlyCrashProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context
        if (ctx != null) {
            runCatching { CrashWatch.installEarly(ctx.applicationContext ?: ctx) }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
