package com.cat.client

import android.app.Application
import android.content.Context
import java.io.File
import java.security.SecureRandom

internal object MihomoControllerSecret {
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class CatClientApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(AppTheme.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        // First thing: never die silently — record any uncaught exception and
        // surface it as a notification (see CrashWatch for why).
        CrashWatch.install(this)
        initializeWorkManager()
        initializeCatClientTypefaces(this)
        File(filesDir, "mihomo").mkdirs()
        File(cacheDir, "mihomo").mkdirs()
        DiagnosticLogger.info(this, "mihomo.app.ready", "basePath=${filesDir.absolutePath}")
        VpnWidgetProvider.refresh(this)
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor(ALWAYS_ON_VPN_SETTING), false,
                object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
                    override fun onChange(selfChange: Boolean) {
                        VpnWidgetProvider.refresh(this@CatClientApplication)
                    }
                },
            )
        }.onFailure { DiagnosticLogger.warn(this, "widget.settingsObserver.failed", error = it) }
    }

    /**
     * WorkManager is initialized here, on purpose (see AndroidManifest: its
     * androidx.startup initializer is removed). The automatic path runs before
     * Application.onCreate and creates a Room database by reflection — a failure
     * there kills the process with no chance to catch it. This guarded call
     * keeps any WorkManager problem inside the app, where worst case is a
     * disabled panel monitor instead of an app that will not open.
     */
    private fun initializeWorkManager() {
        if (runCatching { androidx.work.WorkManager.getInstance(this) }.isSuccess) return
        runCatching {
            androidx.work.WorkManager.initialize(
                this,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.WARN)
                    .build(),
            )
        }.onFailure {
            DiagnosticLogger.warn(this, "workmanager.init.failed", "panel monitor disabled", it)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        VpnWidgetProvider.refresh(this)
    }
}
