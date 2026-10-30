-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

-keep class com.follow.clash.core.** { *; }
-keep interface com.follow.clash.core.** { *; }
-keep class * implements com.follow.clash.core.InvokeInterface { *; }
-keep class * implements com.follow.clash.core.TunInterface { *; }

-keep class com.whitedns.vpn.ByeDpiProxy { *; }
-keep class com.cat.client.DpiBypass { *; }
-keep interface com.cat.client.TunInterface { *; }
-keep class * implements com.cat.client.TunInterface { *; }

# ── Aether native Rust bridge & JNI callbacks (beta144) ───────────────────
# libwhiteaesther_core.so invokes protectSocket(I)Z on NativeSocketProtector
# and onNativeReady()V on NativeEngineListener via JNI GetMethodID. In R8 full
# mode, synthetic SAM lambdas (e.g. LA0/l;) have their interface methods renamed
# or stripped unless both the interfaces and all implementing classes are kept.
-keep class com.whitedns.whiteaesther.core.** { *; }
-keep interface com.whitedns.whiteaesther.core.** { *; }
-keep interface com.whitedns.whiteaesther.core.NativeSocketProtector { *; }
-keep class * implements com.whitedns.whiteaesther.core.NativeSocketProtector {
    public boolean protectSocket(int);
    *;
}
-keep interface com.whitedns.whiteaesther.core.NativeEngineListener { *; }
-keep class * implements com.whitedns.whiteaesther.core.NativeEngineListener {
    public void onNativeReady();
    *;
}

# ── Psiphon (gomobile) and Tor JNI bridges ────────────────────────────────
-keep class go.** { *; }
-keep interface go.** { *; }
-keep class psi.** { *; }
-keep interface psi.** { *; }
-keep class ca.psiphon.** { *; }
-keep interface ca.psiphon.** { *; }
-keep class * implements ca.psiphon.PsiphonTunnel$HostService { *; }
-keep class org.torproject.jni.** { *; }

# ── R8 full-mode startup-crash guard (beta75) ─────────────────────────────
# WorkManager (androidx.work:work-runtime-ktx) creates its Room database
# (androidx.work.impl.WorkDatabase_Impl) by REFLECTION at app startup, from
# androidx.startup.InitializationProvider — i.e. before ANY app code runs.
# Room's older consumer rule keeps the class NAME but not its no-arg
# constructor, and R8 full mode (the AGP 8 default) removes it, so the release
# APK dies on launch with:
#   RuntimeException: Unable to get provider androidx.startup.InitializationProvider
#   Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
# Verified fix (reprise#1128, nc#7, Android-Remote#319, lynx#1266): keep the
# constructor explicitly. CI re-verifies this against R8's usage.txt so the
# guard cannot silently regress.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
