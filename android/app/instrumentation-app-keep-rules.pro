# Cross-APK release instrumentation needs the un-obfuscated app API surface.
# Production release builds do not apply this test-only compatibility rule.
-keep class com.zdownloader.app.** { public *; }
