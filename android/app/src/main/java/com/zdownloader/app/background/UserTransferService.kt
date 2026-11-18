package com.zdownloader.app.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zdownloader.app.ZApplication
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.engine.NetworkDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

/** A foreground owner established during a visible, confirmed download action. */
class UserTransferService:Service() {
    companion object {
        private val acceptance=AtomicReference<CompletableDeferred<Boolean>?>()
        suspend fun startFromUser(context:Context):Boolean {
            val ready=CompletableDeferred<Boolean>()
            acceptance.getAndSet(ready)?.complete(false)
            return try {
                ContextCompat.startForegroundService(context,Intent(context,UserTransferService::class.java))
                withTimeoutOrNull(4000) {ready.await()} ?: false
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {
                NetworkDiagnostics.record("scheduler","user_foreground_rejected",ErrorCode.SCHEDULING_FAILED,
                    details=org.json.JSONObject().put("exception_class",error.javaClass.simpleName))
                false
            } finally {acceptance.compareAndSet(ready,null)}
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val starts=Channel<Int>(Channel.CONFLATED)
    private var runner:Job?=null
    private var promoted=false
    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(this,411,(application as ZApplication).notifications.summary(),
                if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            promoted=true
        } catch(error:Exception) {
            NetworkDiagnostics.record("scheduler","user_foreground_promotion_failed",ErrorCode.SCHEDULING_FAILED,
                details=org.json.JSONObject().put("exception_class",error.javaClass.simpleName))
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        acceptance.getAndSet(null)?.complete(promoted)
        if(!promoted) {stopSelf(startId);return START_NOT_STICKY}
        val app=application as ZApplication
        starts.trySend(startId)
        if(runner?.isActive!=true) runner=scope.launch {
            NetworkDiagnostics.record("scheduler","user_foreground_started",null)
            for(acceptedStart in starts) {
                try {app.coordinator.run()}
                catch(error:CancellationException) {throw error}
                catch(error:Exception) {
                    NetworkDiagnostics.record("scheduler","user_foreground_runner_failed",ErrorCode.INTERNAL_ENGINE_ERROR,
                        details=org.json.JSONObject().put("exception_class",error.javaClass.simpleName))
                    runCatching {app.scheduler.scheduleRecovery(0)}
                } finally {
                    // A newer onStartCommand must not be lost in the gap between
                    // the prior coordinator becoming idle and this service stopping.
                    if(currentCoroutineContext().isActive) withContext(Dispatchers.Main.immediate) {stopSelfResult(acceptedStart)}
                }
            }
        } else scope.launch {app.coordinator.signalSubmission()}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        // Checkpoints belong to Room/files; reaching Android's data-sync budget
        // must release foreground ownership without discarding those checkpoints.
        runner?.cancel()
        (application as ZApplication).scheduler.recoverAfterForegroundStop()
        stopSelf(startId)
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy() {
        acceptance.getAndSet(null)?.complete(false)
        starts.close()
        scope.cancel()
        if(promoted) ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
