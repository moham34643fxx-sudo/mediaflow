# Chaquopy and FFmpegKit provide vendor consumer rules; keep application entry points explicitly.
# Python resolves these names through JNI/jclass, beyond R8's static call graph.
-keep class com.zdownloader.app.engine.JsBridge { *; }
-keep interface com.zdownloader.app.ProgressProxy { *; }
-keepclassmembers class * implements com.zdownloader.app.ProgressProxy { public *; }
-keep class com.zdownloader.app.engine.AndroidNetworkBridge { public *; }
-keep class com.zdownloader.app.engine.AnalysisTrace { public *; }
-keep class com.zdownloader.app.engine.FacebookSessionManager { public *; }
-keep class com.zdownloader.app.engine.MetadataHttpBridge { public *; }
# metadata_transport.py calls these getters by name on the returned object.
-keep,allowoptimization class com.zdownloader.app.engine.MetadataHttpResponse {
    public int getStatus();
    public java.lang.String getReason();
    public java.lang.String getHeadersJson();
    public byte[] getBody();
}
-keep class com.zdownloader.app.engine.MetadataTransportException { *; }
-keep class com.zdownloader.app.engine.LameEncoder { *; }
