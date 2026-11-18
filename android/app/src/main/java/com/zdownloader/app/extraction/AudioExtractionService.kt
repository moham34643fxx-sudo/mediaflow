package com.zdownloader.app.extraction

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zdownloader.app.MainActivity
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import kotlinx.coroutines.*

/** User-started local processing, with a separate notification and a scoped cancellation token. */
class AudioExtractionService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var work:Job?=null
    private var runningId:String?=null
    private val repository get()=(application as ZApplication).audioExtraction
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val id=intent?.getStringExtra("id")
        if(intent?.action=="cancel") {repository.cancel(id);if(work?.isActive!=true) stopSelf();return START_NOT_STICKY}
        val task=repository.task.value
        if(task==null||task.id!=id||!task.phase.active) {if(work?.isActive!=true) stopSelf();return START_NOT_STICKY}
        if(work?.isActive==true&&runningId==id) return START_NOT_STICKY
        val previous=work
        runningId=id
        try {
            val manager=getSystemService(NotificationManager::class.java)
            if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.extract_title),NotificationManager.IMPORTANCE_LOW))
            // The bundled older AndroidX ServiceCompat masks mediaProcessing to zero.
            // Use the platform overload so Android 15+ receives the declared real type.
            if(Build.VERSION.SDK_INT>=35) startForeground(NOTIFICATION,notification(task),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification(task),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION,notification(task))
        } catch(error:Exception) {scope.launch {repository.failStart(task.id,error);stopSelf()};return START_NOT_STICKY}
        work=scope.launch {
            previous?.join()
            val wake=getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:AudioExtraction")
            try {
                wake.acquire(6*60*60*1000L)
                repository.run(id) {task->runCatching {getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(task))}}
            } catch(error:CancellationException) {
                throw error
            } catch(error:Exception) {
                repository.failStart(task.id,error)
            } finally {
                if(wake.isHeld) wake.release()
                if(runningId==id) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId)}
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(task:ExtractionTask):android.app.Notification {
        val open=PendingIntent.getActivity(this,NOTIFICATION,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,NOTIFICATION,Intent(this,AudioExtractionService::class.java).setAction("cancel").putExtra("id",task.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_zd_notification).setContentTitle(getString(R.string.extract_title))
            .setContentText(getString(task.phase.label)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(task.phase.active)
            .setProgress(100,((task.progress ?: 0f)*100).toInt(),task.progress==null)
            .addAction(0,getString(R.string.cancel),cancel).build()
    }
    override fun onTimeout(startId:Int,fgsType:Int) {repository.cancel();scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy() {scope.cancel();super.onDestroy()}
    companion object {private const val CHANNEL="mediaflow_audio_extraction";private const val NOTIFICATION=4701}
}
