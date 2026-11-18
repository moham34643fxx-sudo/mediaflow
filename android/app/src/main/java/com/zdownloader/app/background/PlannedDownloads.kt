package com.zdownloader.app.background

import android.content.Context
import android.os.BatteryManager
import androidx.work.*
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.DownloadDao
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Delayed admission to the existing coordinator, not another transfer implementation. */
@Singleton class PlannedDownloads @Inject constructor(@ApplicationContext private val context:Context,private val dao:DownloadDao) {
    private val receipts=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private suspend fun issue(id:String,batch:Boolean,reason:ErrorCode?) {
        val ids=if(batch) dao.batchItems(id).filter {it.status=="SCHEDULED"}.map {it.id} else listOf(id)
        for(row in ids) if(reason==null) dao.clearSubmissionIssue(row) else dao.submissionIssue(row,reason.name)
    }
    internal fun constraints(row:DownloadEntity):Constraints=Constraints.Builder().setRequiredNetworkType(if(row.scheduleNetwork=="UNMETERED") NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(row.requiresCharging).build()
    internal fun constraintsReport(row:DownloadEntity):org.json.JSONObject=constraints(row).let {org.json.JSONObject().put("charging",it.requiresCharging()).put("network",it.requiredNetworkType.name)}
    internal fun request(id:String,batch:Boolean,row:DownloadEntity):OneTimeWorkRequest = OneTimeWorkRequestBuilder<PlannedDownloadWorker>()
        .setInputData(workDataOf("target" to id,"batch" to batch))
        .setInitialDelay((row.scheduledAt-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
        .setConstraints(constraints(row))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
    private fun name(id:String,batch:Boolean)="mediaflow-planned-${if(batch) "batch" else "item"}-$id"
    suspend fun enqueue(id:String,batch:Boolean=false) = withContext(Dispatchers.IO) {
        val row=if(batch) dao.batchItems(id).firstOrNull {it.status=="SCHEDULED"} else dao.get(id)?.takeIf {it.status=="SCHEDULED"}
        if(row==null) throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
        try {
            val operation=WorkManager.getInstance(context).enqueueUniqueWork(name(id,batch),ExistingWorkPolicy.KEEP,request(id,batch,row))
            try {operation.result.get(15,TimeUnit.SECONDS);issue(id,batch,null);com.zdownloader.app.engine.NetworkDiagnostics.record("scheduler","planned_accepted",null,operation=id,details=constraintsReport(row))}
            catch(_:java.util.concurrent.TimeoutException) {
                issue(id,batch,ErrorCode.START_DELAYED)
                operation.result.addListener({receipts.launch {
                    val reason=try {operation.result.get();null} catch(_:Exception) {ErrorCode.SCHEDULING_FAILED}
                    try {issue(id,batch,reason)} catch(e:CancellationException) {throw e} catch(_:Exception) {com.zdownloader.app.engine.NetworkDiagnostics.record("scheduler","planned_receipt_write_failed",ErrorCode.FILE_WRITE_FAILED)}
                }},java.util.concurrent.Executor {it.run()})
                throw MediaException(ErrorCode.START_DELAYED)
            }
        }
        catch(e:CancellationException) {throw e}
        catch(e:MediaException) {throw e}
        catch(_:Exception) {issue(id,batch,ErrorCode.SCHEDULING_FAILED);throw MediaException(ErrorCode.SCHEDULING_FAILED)}
    }
    suspend fun restore() {for(row in dao.schedules().distinctBy {it.batchId ?: it.id}) enqueue(row.batchId ?: row.id,row.batchId!=null)}
    suspend fun cancel(id:String,batch:Boolean=false) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(name(id,batch)).result.get(15,TimeUnit.SECONDS)
    }
    suspend fun now(id:String,batch:Boolean=false) {dao.startScheduledNow(id,batch);cancel(id,batch)}
}

class PlannedDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as ZApplication;val dao=app.repository.dao
        val id=inputData.getString("target") ?: return Result.failure();val batch=inputData.getBoolean("batch",false)
        val rows=if(batch) dao.batchItems(id) else listOfNotNull(dao.get(id))
        val pending=rows.filter {it.status !in setOf("COMPLETED","CANCELLED","FAILED","PAUSED","PAUSING")}
        if(pending.isEmpty()) return Result.success()
        val row=pending.first()
        if(!app.coordinator.planEligible(row)) return Result.retry()
        return try {
            dao.releaseSchedule(id,batch,System.currentTimeMillis())
            // A scheduled request owns the entire transfer/processing/export
            // chain after its screen has gone away, just like a manual request.
            setForeground(ForegroundInfo(411,app.notifications.summary(),if(android.os.Build.VERSION.SDK_INT>=29)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0))
            // Foreground ownership permits long processing without restarting
            // the same strict decode every seven minutes. Platform stops still
            // cancel the coordinator and preserve its native/Room checkpoints.
            app.coordinator.run()
            val latest=if(batch) dao.batchItems(id) else listOfNotNull(dao.get(id))
            if(latest.any {it.status !in setOf("COMPLETED","CANCELLED","FAILED","PAUSED","PAUSING")}) Result.retry() else Result.success()
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {if(runAttemptCount<5) Result.retry() else Result.failure()}
    }
}
