-keep class com.follow.clash.core.** { *; }
-keep class com.whitedns.vpn.ByeDpiProxy { *; }
-keep class go.** { *; }

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
