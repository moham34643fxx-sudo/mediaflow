package com.zdownloader.app.engine

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** Optional event-driven context only. No service, wake lock, persistent setting or polling. */
internal object PerformanceCapture {
    private const val LIMIT_MS=30*60*1000L
    private val handler=Handler(Looper.getMainLooper())
    private val captureState=kotlinx.coroutines.flow.MutableStateFlow(false)
    val state:kotlinx.coroutines.flow.StateFlow<Boolean> = captureState
    @Volatile private var expiresAt=0L
    val active get():Boolean {
        if(!captureState.value) return false
        if(SystemClock.elapsedRealtime()<expiresAt) return true
        handler.post(expiry)
        return false
    }
    @Volatile private var interactive=false
    @Volatile private var foreground=false
    private var application:Application?=null
    private var started=0
    private val processSession=UUID.randomUUID().toString()
    private val expiry=Runnable {stop()}
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            interactive=context.getSystemService(PowerManager::class.java).isInteractive
            marker(if(interactive) "screen_on" else "screen_off")
        }
    }
    private val lifecycle=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity:Activity) {started++;setForeground(true)}
        override fun onActivityStopped(activity:Activity) {started=(started-1).coerceAtLeast(0);if(started==0&&!activity.isChangingConfigurations) setForeground(false)}
        override fun onActivityCreated(activity:Activity,state:Bundle?) {}
        override fun onActivityResumed(activity:Activity) {}
        override fun onActivityPaused(activity:Activity) {}
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
        override fun onActivityDestroyed(activity:Activity) {}
    }
    fun start(activity:Activity) {
        check(com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS) {"Internal diagnostics unavailable"}
        check(Looper.myLooper()==Looper.getMainLooper())
        if(active) return
        // The handler uses uptime; elapsed time also bounds capture across deep sleep.
        if(captureState.value) stop()
        val app=activity.application
        interactive=app.getSystemService(PowerManager::class.java).isInteractive
        foreground=true;started=1
        androidx.core.content.ContextCompat.registerReceiver(app,receiver,
            IntentFilter().apply {addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_SCREEN_OFF)},
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        app.registerActivityLifecycleCallbacks(lifecycle)
        application=app;expiresAt=SystemClock.elapsedRealtime()+LIMIT_MS;captureState.value=true
        marker("capture_started")
        handler.postDelayed(expiry,LIMIT_MS)
    }
    fun stop() {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!captureState.value) return
        marker("capture_stopped")
        captureState.value=false;handler.removeCallbacks(expiry)
        application?.let {app->app.unregisterActivityLifecycleCallbacks(lifecycle);app.unregisterReceiver(receiver)}
        application=null;started=0
    }
    private fun setForeground(value:Boolean) {if(foreground!=value) {foreground=value;marker(if(value) "app_foreground" else "app_background")}}
    private fun marker(stage:String) {NetworkDiagnostics.record("capture",stage,null)}
    fun decorate(row:JSONObject) {
        if(!active) return
        row.put("capture_session",processSession).put("monotonic_ms",SystemClock.elapsedRealtime())
            .put("screen_interactive",interactive).put("app_foreground",foreground)
    }
}
