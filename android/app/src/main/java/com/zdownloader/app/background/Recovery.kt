package com.zdownloader.app.background

import android.content.*
import androidx.work.*
import com.zdownloader.app.ZApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Reboot/background recovery uses ordinary quota-managed work, never a new UIDT/FGS. */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueueUniqueWork("z-recovery",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<RecoveryWorker>().build())
    }
}
class RecoveryWorker(context: Context,params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZApplication
        if(app.coordinator.running) return Result.retry()
        val dao=app.repository.dao
        app.plannedDownloads.restore()
        app.storage.reconcilePublications()
        val noticesDelivered=app.notifications.deliverPending()
        if(dao.remaining()==0 && dao.interruptedCount()==0 && dao.queueSignals().first().isEmpty()) return if(noticesDelivered) Result.success() else if(runAttemptCount<2) Result.retry() else Result.failure()
        return try {
            // Recovery may own a complete merge/decode/export, not just a short
            // database repair. Use WorkManager's foreground execution owner for
            // the same pipeline as QueueWorker; never start an Activity or FGS
            // directly from a boot receiver. Platform rejection remains retryable.
            setForeground(ForegroundInfo(411,app.notifications.summary(),if(android.os.Build.VERSION.SDK_INT>=29)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0))
            // This is now a long-running foreground Worker. An ordinary-work
            // seven-minute cutoff would repeatedly cancel a long full decode
            // at the same checkpoint. Android/WorkManager still own cancellation.
            app.coordinator.run()
            if(dao.remaining()>0 || dao.interruptedCount()>0) Result.retry() else if(app.notifications.deliverPending()) Result.success() else if(runAttemptCount<2) Result.retry() else Result.failure()
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            com.zdownloader.app.engine.NetworkDiagnostics.record("scheduler","recovery_failed",com.zdownloader.app.domain.ErrorCode.INTERNAL_ENGINE_ERROR)
            if(runAttemptCount<2) Result.retry() else Result.failure()
        }
    }
}
