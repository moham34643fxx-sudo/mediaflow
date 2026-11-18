# Only release APKs explicitly built as instrumentation targets need this ABI.
# Production retains its normal R8 shrinking plus the narrow JNI/Python rules.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.sqlite.db.SupportSQLiteOpenHelper$Configuration** { public *; }
-keep class androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory { public *; }
-keep class androidx.sqlite.db.SupportSQLiteOpenHelper$Callback { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.runtime.** { *; }
# Test-only Compose hosts call layout/scroll default bridges that production
# callsites may inline or specialize. Preserve that cross-APK ABI only here.
-keep,allowobfuscation class androidx.compose.foundation.layout.SizeKt { *; }
-keep,allowobfuscation class androidx.compose.foundation.layout.PaddingKt { *; }
-keep,allowobfuscation class androidx.compose.foundation.layout.ColumnKt { *; }
-keep,allowobfuscation class androidx.compose.foundation.layout.Arrangement { *; }
-keep,allowobfuscation class androidx.compose.foundation.ScrollKt { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.lifecycle.** { *; }
# Cross-APK tests observe controller, entry lifecycle and destination metadata.
-keep class androidx.navigation.** { *; }
-keep class androidx.activity.** { *; }
-keep interface javax.inject.Provider { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# Native solver diagnostics split compile/evaluate across the test APK boundary.
-keep class com.dokar.quickjs.** { *; }
-keep class androidx.documentfile.provider.DocumentFile { public *; }
-keep class androidx.media3.session.MediaController { public *; }
-keep class androidx.media3.common.Tracks** { public *; }
-keep class androidx.media3.common.Format { public *; }
-keep class androidx.media3.common.text.CueGroup { public *; }
-keep class androidx.media3.common.text.Cue { public *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep interface com.google.common.util.concurrent.** { *; }
