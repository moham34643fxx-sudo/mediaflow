# Error Prone's compile-time annotation metadata references the JDK compiler model.
# Android does not provide that model; test execution does not use it.
-dontwarn javax.lang.model.element.Modifier
-keep class com.zdownloader.app.DeviceEvidenceListener { *; }
