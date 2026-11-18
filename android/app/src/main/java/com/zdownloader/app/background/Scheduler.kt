package com.zdownloader.app.background

import android.app.job.*
import android.content.*
import android.os.Build
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import androidx.work.*
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.data.DownloadDao
import com.zdownloader.app.data.DownloadEvent
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.QueueCoordinator
import com.zdownloader.app.engine.NetworkDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleReceipt(val mechanism:String,val runnerReady:Boolean,val waitingReason:ErrorCode?=null)

internal data class UserStartupOwners(val mechanism:String,val recoveryAccepted:Boolean)

/** Recovery stays a structured child, but its acceptance cannot gate the immediate owner attempt. */
internal suspend fun requestUserStartupOwners(recovery:suspend ()->Boolean,immediateOwner:suspend ()->String):UserStartupOwners=coroutineScope {
    val acceptance=async(Dispatchers.IO) {recovery()}
    val mechanism=immediateOwner()
    UserStartupOwners(mechanism,acceptance.await())
}

@Singleton class Scheduler @Inject constructor(@ApplicationContext private val context: Context, private val queue: QueueCoordinator, private val settings: SettingsRepository,private val dao:DownloadDao) {
    private val submissionLock=Mutex()
    private val receiptScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private suspend fun userExecutionOwner():String {
        if(UserTransferService.startFromUser(context)) return "user_foreground"
        return if(queue.signalSubmission()) "active_queue" else ""
    }
    fun recoverAfterForegroundStop() {receiptScope.launch {runCatching {scheduleRecovery(0)}}}
    private fun observePendingAcceptance(operation:Operation,requestId:String?,otherAccepted:Boolean) {
        operation.result.addListener({receiptScope.launch {
            try {
                operation.result.get()
                requestId?.let {dao.clearSubmissionIssue(it);dao.event(DownloadEvent(downloadId=it,state="SCHEDULING_ACCEPTED"))}
                NetworkDiagnostics.record("scheduler","late_acceptance_confirmed",null,operation=requestId)
            } catch(e:Exception) {
                if(!otherAccepted) requestId?.let {dao.submissionIssue(it,ErrorCode.SCHEDULING_FAILED.name)}
                NetworkDiagnostics.record("scheduler","late_acceptance_failed",ErrorCode.SCHEDULING_FAILED,operation=requestId,
                    details=JSONObject().put("exception_class",e.javaClass.simpleName))
            }
        }},java.util.concurrent.Executor {it.run()})
    }
    suspend fun scheduleRecovery(delaySeconds:Long=30) {
        val recovery=OneTimeWorkRequestBuilder<RecoveryWorker>().setInitialDelay(delaySeconds,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build()
        try {withContext(Dispatchers.IO) {WorkManager.getInstance(context).enqueueUniqueWork("z-recovery",ExistingWorkPolicy.KEEP,recovery).result.get(15,TimeUnit.SECONDS)}}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {NetworkDiagnostics.record("scheduler","recovery_rejected",ErrorCode.SCHEDULING_FAILED);throw MediaException(ErrorCode.SCHEDULING_FAILED)}
    }
    suspend fun startFromUser(requestId:String?=null):ScheduleReceipt = withContext(NonCancellable) {submissionLock.withLock {
        val prefs = settings.current()
        val submitted=requestId?.let {dao.get(it) ?: throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)}
        if(submitted?.status=="COMPLETED") return@withLock ScheduleReceipt("already_completed",true)
        val postProcessing=submitted?.checkpoint?.let {it!="DOWNLOAD"}==true
        // A durable, quota-managed safety net survives loss of the UIDT process/job.
        // Accept it before returning the scheduling receipt. Recovery never
        // attempts to start a foreground service from a boot/background callback.
        requestId?.let {dao.event(DownloadEvent(downloadId=it,state="SCHEDULING_REQUESTED"))}
        // Accepting a UIDT job does not prove that it will keep its execution
        // lease after the share Activity closes. Establish the explicit user's
        // foreground owner before returning the receipt that dismisses that UI.
        val owners=requestUserStartupOwners(recovery={
            try {scheduleRecovery(if(postProcessing) 0 else 30);true}
            catch(e:Exception) {NetworkDiagnostics.record("scheduler","recovery_acceptance_failed",ErrorCode.SCHEDULING_FAILED,operation=requestId);false}
        },immediateOwner={userExecutionOwner()})
        val recoveryAccepted=owners.recoveryAccepted
        var mechanism=owners.mechanism
        if(mechanism.isEmpty()&&postProcessing&&recoveryAccepted) mechanism="workmanager_processing"
        if(mechanism.isEmpty()&&Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Keep the durable job independent of a stale transport preference.
            // Coordinator enforces Wi-Fi (including underlying VPN transport).
            val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val job = JobInfo.Builder(JOB_ID,ComponentName(context,DownloadJobService::class.java)).setRequiredNetwork(network).setUserInitiated(true).build()
            val pending=scheduler.getPendingJob(JOB_ID)!=null
            val attempt=runCatching {scheduler.schedule(job)}
            val result=attempt.getOrNull()
            NetworkDiagnostics.record("scheduler",if(result==JobScheduler.RESULT_SUCCESS) "uidt_accepted" else "uidt_rejected",
                if(result==JobScheduler.RESULT_SUCCESS) null else ErrorCode.SCHEDULING_FAILED,operation=requestId,
                details=JSONObject().put("job_id",JOB_ID).put("pending_before",pending).put("platform_result",result)
                    .put("exception_class",attempt.exceptionOrNull()?.javaClass?.simpleName))
            if(result == JobScheduler.RESULT_SUCCESS) mechanism="uidt"
            else if(pending) mechanism="uidt_pending"
        }
        // Unmetered is not synonymous with Wi-Fi (a Wi-Fi hotspot may be metered).
        // QueueCoordinator enforces the actual transport preference on every wake.
        if(mechanism.isEmpty()) {
            val worker = OneTimeWorkRequestBuilder<QueueWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            try {
                val operation=WorkManager.getInstance(context).enqueueUniqueWork("z-queue",ExistingWorkPolicy.APPEND_OR_REPLACE,worker)
                // Bound only the scheduling confirmation, never execution. Room
                // already owns the queued intent; an unfinished future is observed
                // independently if its transaction exceeds this confirmation window.
                val confirmed=try {withContext(Dispatchers.IO) {operation.result.get(15,TimeUnit.SECONDS)};true}
                catch(e:java.util.concurrent.TimeoutException) {
                    // The future is still pending, not rejected. Its late result
                    // belongs to the durable queue, never the submitting Activity.
                    observePendingAcceptance(operation,requestId,recoveryAccepted)
                    if(!recoveryAccepted) throw MediaException(ErrorCode.START_DELAYED)
                    false
                }
                mechanism=if(confirmed) "workmanager" else "workmanager_acceptance_pending"
                NetworkDiagnostics.record("scheduler",if(confirmed) "workmanager_accepted" else "workmanager_acceptance_pending",null,operation=requestId,details=JSONObject().put("work_id",worker.id.toString()))
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                if(e is MediaException&&e.code==ErrorCode.START_DELAYED) {
                    requestId?.let {dao.submissionIssue(it,ErrorCode.START_DELAYED.name)}
                    throw e
                }
                NetworkDiagnostics.record("scheduler","workmanager_rejected",ErrorCode.SCHEDULING_FAILED,operation=requestId,
                    details=JSONObject().put("exception_class",e.javaClass.simpleName))
                // A fallback rejection cannot undo an already durable recovery
                // acceptance or work that has meanwhile advanced in Room.
                if(recoveryAccepted) mechanism="workmanager_recovery"
                else if(requestId?.let {dao.get(it)?.status in handledStates}==true) mechanism="persisted_progress"
                else {
                    requestId?.let {dao.submissionIssue(it,ErrorCode.SCHEDULING_FAILED.name)}
                    throw MediaException(ErrorCode.SCHEDULING_FAILED)
                }
            }
        }
        var blocked=if(postProcessing) null else queue.waitingReason(prefs)
        if(blocked!=null&&requestId!=null) {
            dao.submissionWaiting(requestId,blocked.name)
            if(dao.get(requestId)?.status in handledStates) blocked=null
        }
        if(requestId==null) {
            // Best-effort wake without a durable row to verify against.
            // Never fail a generic queue nudge; the runner reports real state.
            NetworkDiagnostics.record("scheduler","nudge_accepted",blocked,details=JSONObject().put("mechanism",mechanism))
            return@withLock ScheduleReceipt(mechanism,queue.signalSubmission(),blocked)
        }
        // Acceptance and execution are separate milestones. Android is allowed
        // to defer an accepted job; a UI deadline is not a download failure.
        val ready=dao.get(requestId)?.status in handledStates || queue.signalSubmission()
        if(mechanism!="workmanager_acceptance_pending") dao.clearSubmissionIssue(requestId)
        dao.event(DownloadEvent(downloadId=requestId,state="SUBMITTED"))
        dao.event(DownloadEvent(downloadId=requestId,state=if(ready) "SCHEDULER_RUNNER_READY" else if(blocked!=null) "SCHEDULER_WAITING_FOR_NETWORK" else "SCHEDULER_QUEUED",error=blocked?.name))
        NetworkDiagnostics.record("scheduler",if(ready) "runner_ready" else "accepted_waiting",blocked,operation=requestId,details=JSONObject().put("mechanism",mechanism))
        ScheduleReceipt(mechanism,ready,blocked)
    }}
    companion object {
        const val JOB_ID = 410
        private val handledStates=setOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","PROCESSING","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING","COMPLETED","FAILED")
    }
}
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var runner: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        NetworkDiagnostics.record("scheduler","uidt_job_started",null,details=JSONObject().put("job_id",params.jobId))
        val app = application as ZApplication
        if(Build.VERSION.SDK_INT >= 34) setNotification(params,411,app.notifications.summary(),JOB_END_NOTIFICATION_POLICY_REMOVE)
        runner = scope.launch {
            var reschedule=false
            try {app.coordinator.run()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {reschedule=true;NetworkDiagnostics.record("scheduler","uidt_runner_failed",ErrorCode.INTERNAL_ENGINE_ERROR,details=JSONObject().put("exception_class",e.javaClass.simpleName))}
            finally {if(currentCoroutineContext().isActive) jobFinished(params,reschedule)}
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val reason=if(Build.VERSION.SDK_INT>=31) params.stopReason else -1
        NetworkDiagnostics.record("scheduler","uidt_job_stopped",ErrorCode.DOWNLOAD_INTERRUPTED,details=JSONObject().put("job_id",params.jobId).put("stop_reason",reason))
        runner?.cancel()
        if(Build.VERSION.SDK_INT>=31 && reason==JobParameters.STOP_REASON_USER) scope.launch(NonCancellable) {
            (application as ZApplication).repository.dao.pauseForUserStop()
        }
        return Build.VERSION.SDK_INT<31 || reason !in listOf(JobParameters.STOP_REASON_USER,JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZApplication
        return try {
            setForeground(ForegroundInfo(411,app.notifications.summary(),if(Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0))
            NetworkDiagnostics.record("scheduler","worker_started",null,details=JSONObject().put("attempt",runAttemptCount))
            app.coordinator.run();Result.success()
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            NetworkDiagnostics.record("scheduler","worker_failed",ErrorCode.SCHEDULING_FAILED,details=JSONObject().put("attempt",runAttemptCount).put("exception_class",e.javaClass.simpleName))
            if(runAttemptCount<2) Result.retry() else Result.failure()
        }
    }
}
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { val app = context.applicationContext as ZApplication; val id = intent.getStringExtra("id") ?: return@launch
                when(intent.getStringExtra("action")) { "pause" -> app.repository.pause(id); "cancel" -> app.repository.cancel(id)
                    "batch_pause"->app.playlists.pause(id);"batch_cancel"->app.playlists.cancel(id) }
            } finally { pending.finish() }
        }
    }
}
