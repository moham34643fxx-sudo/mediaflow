-keep class com.chaquo.** { *; }
-keep class com.arthenica.** { *; }
# Retain the app's public contract used by generated lifecycle/Hilt factories
# and independently restored Android entries. Third-party internals still shrink.
-keep class com.zdownloader.app.** { public *; }
# Python resolves these names through JNI/jclass, beyond R8's static call graph.
-keep class com.zdownloader.app.engine.JsBridge { *; }
-keep interface com.zdownloader.app.ProgressProxy { *; }
-keepclassmembers class * implements com.zdownloader.app.ProgressProxy { public *; }
-keep class com.zdownloader.app.engine.AndroidNetworkBridge { *; }
-keep class com.zdownloader.app.engine.LameEncoder { *; }
