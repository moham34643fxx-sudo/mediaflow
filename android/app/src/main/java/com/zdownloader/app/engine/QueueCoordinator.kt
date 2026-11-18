package com.zdownloader.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.background.DownloadNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private data class ProgressUpdate(val bytes: Long, val total: Long?, val speed: Long,val kind:String)

@Singleton class QueueCoordinator @Inject constructor(@param:ApplicationContext private val context: Context, private val dao: DownloadDao, private val settings: SettingsRepository, private val transfer: Transfer, private val processor: Processor, private val storage: StorageRepository, private val notifications: DownloadNotifications, private val extractor: Extractor) {
    companion object {
        internal fun shouldRefreshSelection(
            checkpoint: String,
            justResolved: Boolean,
            retryCause: String?,
            expired: Boolean
        ): Boolean {
            if (justResolved || checkpoint != "DOWNLOAD") return false
            val urlDead = retryCause in setOf(
                ErrorCode.HTTP_FORBIDDEN.name,
                ErrorCode.MEDIA_URL_EXPIRED.name,
                ErrorCode.AUTHENTICATION_REQUIRED.name,
                ErrorCode.SOURCE_CORRUPT.name
            )
            return urlDead || expired
        }
    }
    private val runLock = Mutex()
    private val admissionLock=Mutex()
    private var submissionWake:Channel<Unit>?=null
    private var submissionOwner:Job?=null
    private val controls = ConcurrentHashMap<String,TransferControl>()
    private val adaptive=AdaptiveConcurrency()
    private var preferSingle=true
    @Volatile var running = false
        private set
    fun stop(id: String) { controls[id]?.stop() }
    fun active(id: String) = controls.containsKey(id)
    suspend fun stopAndAwait(id: String) { controls[id]?.let { it.stop(); it.finished.await() } }
    suspend fun signalSubmission():Boolean=admissionLock.withLock {
        if(submissionOwner?.isActive!=true) false else submissionWake?.trySend(Unit)?.isSuccess==true
    }
    suspend fun run() {
        // A replacement platform job must wait for a stopping run, not return
        // successfully while leaving the new durable requests unclaimed.
        runLock.lock()
        running = true
         try { withContext(Dispatchers.IO) { supervisorScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val queueChanges = launch { dao.queueSignals().distinctUntilChanged().collect { wake.trySend(Unit) } }
             val preferenceChanges = launch { settings.flow.distinctUntilChanged().collect { wake.trySend(Unit) } }
             val batchChanges=launch {dao.batches().collect {wake.trySend(Unit)}}
            val preparation=PlaylistPreparation(this) {target->
                val request="${target.childId}:preparation"
                AnalysisTrace.value(request,"download_id",target.childId)
                extractor.analyzePlaylistItem(target.url,request);Unit
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val networkChanges = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { wake.trySend(Unit) }
                override fun onLost(network: android.net.Network) { wake.trySend(Unit) }
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) { wake.trySend(Unit) }
            }
            NetworkCallbackRegistration({manager.registerDefaultNetworkCallback(networkChanges)},
                {manager.unregisterNetworkCallback(networkChanges)}).use {
            val underlyingChanges=object:ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:android.net.Network) {wake.trySend(Unit)}
                override fun onLost(network:android.net.Network) {wake.trySend(Unit)}
                override fun onCapabilitiesChanged(network:android.net.Network,capabilities:NetworkCapabilities) {wake.trySend(Unit)}
            }
            NetworkCallbackRegistration({manager.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),underlyingChanges)},
                {manager.unregisterNetworkCallback(underlyingChanges)}).use {
            try {
            dao.recover()
            storage.reconcilePublications()
            notifications.deliverPending()
            dao.requeueInterrupted()
            admissionLock.withLock {submissionWake=wake;submissionOwner=currentCoroutineContext().job}
            val active = mutableMapOf<String,Job>()
            while(isActive) {
                active.entries.removeAll { it.value.isCompleted }
                // Publication recovery is not just a process-start task. A
                // cancelled finalization can leave a journal in this live owner.
                if(active.isEmpty()&&dao.pendingRows().any {it.pendingUri!=null&&it.nextAttempt<=System.currentTimeMillis()}) {
                    storage.reconcilePublications();notifications.deliverPending();dao.requeueInterrupted()
                }
                val prefs = settings.current()
                val blocked=waitingReason(prefs)
                if(blocked==null) {
                    // Waiting followers must recover their truthful queue state
                    // when connectivity returns, not keep yesterday's error
                    // until each one eventually reaches the head of a playlist.
                    for(row in dao.pendingRows()) if(row.status=="WAITING_FOR_NETWORK"&&planEligible(row)) dao.networkAvailable(row.id)
                }
                run {
                    val snapshots=active.keys.mapNotNull {dao.get(it)}
                    val processing=snapshots.count {it.checkpoint!="DOWNLOAD"}
                    val power=context.getSystemService(android.os.PowerManager::class.java)
                    val memory=android.app.ActivityManager.MemoryInfo().also {context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)}
                    val cap=manager.getNetworkCapabilities(manager.activeNetwork)
                    val constrained=memory.lowMemory||power.isPowerSaveMode||(android.os.Build.VERSION.SDK_INT>=29&&power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_MODERATE)
                    val limit=if(prefs.concurrency==0) adaptive.choose(SystemClock.elapsedRealtime(),cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true,constrained,processing,snapshots.sumOf {it.speed}) else prefs.concurrency.coerceIn(1,5)
                    var transfers=snapshots.count {it.checkpoint=="DOWNLOAD"}
                    while(transfers<limit&&active.size<limit+2) {
                        val next = dao.next(System.currentTimeMillis(),offline=blocked!=null,preferSingle=preferSingle,wifi=isWifi(),unmetered=!manager.isActiveNetworkMetered,charging=isCharging()) ?: break
                        if(next.id in active) break
                         if(dao.claim(next.id) != 1) continue
                         AnalysisTrace.checkpoint(next.id,"queue_claimed")
                        preferSingle=next.batchId!=null
                        val control = TransferControl()
                        // Allocate per download first. A source with one audio
                        // track must not pay for a nonexistent second video track.
                         control.fragmentConcurrency=FragmentBudget.perTrack((transfers+1).coerceAtLeast(1),1,constrained)
                        controls[next.id] = control
                         val prepared=preparation.take(next.id)
                          active[next.id] = launch { execute(next,control,prepared) }.also { job -> job.invokeOnCompletion { prepared?.cancel();controls.remove(next.id,control); control.finished.complete(Unit); wake.trySend(Unit) } }
                         if(next.checkpoint=="DOWNLOAD") transfers++
                     }
                    // Do not compete with live transfers or other active work. Observe only;
                    // sequential next()/claim() still wait for the predecessor's publication.
                    val owner=snapshots.singleOrNull()?.takeIf {it.batchId!=null&&it.checkpoint!="DOWNLOAD"}
                    val candidate=if(blocked==null&&!constrained&&transfers==0&&active.size==1&&owner!=null)
                        dao.playlistPreparationCandidate(owner.id,System.currentTimeMillis(),isWifi(),!manager.isActiveNetworkMetered,isCharging()) else null
                    val completedOwner=if(candidate==null&&blocked==null&&!constrained&&transfers==0)
                        preparation.currentTarget?.let {completedPreparationOwner(it,manager)} else null
                    preparation.update(candidate?.let {PlaylistPreparationTarget(requireNotNull(owner).id,it.id,it.url,manager.activeNetwork?.networkHandle)},completedOwner)
                }
                if(blocked!=null) {
                    dao.pendingWaiting(blocked.name)
                    active.keys.toList().forEach { id ->
                         if(dao.waitForNetwork(id,blocked.name) == 1) controls[id]?.stop()
                    }
                }
                active.keys.toList().forEach {id->dao.get(id)?.takeIf {it.scheduledAt>0&&it.checkpoint=="DOWNLOAD"&&!planEligible(it)}?.let {if(dao.waitForNetwork(id,"START_DELAYED")==1) controls[id]?.stop()}}
                if(active.isEmpty()) {
                    val pending=dao.pendingRows()
                    if(pending.isNotEmpty()&&pending.all {it.scheduledAt>0&&!planEligible(it)}) break
                }
                 val idle = active.isEmpty() && dao.remaining() == 0
                  if(withTimeoutOrNull(if(idle) 1250L else 5000L) { wake.receive() } == null && idle) {
                      val closeAdmission=admissionLock.withLock {
                          if(dao.remaining()==0) {submissionWake=null;submissionOwner=null;true} else false
                      }
                      if(closeAdmission) break
                  }
            }
             } finally {
                preparation.close()
                withContext(NonCancellable) {admissionLock.withLock {submissionWake=null;submissionOwner=null}}
                queueChanges.cancel(); preferenceChanges.cancel();batchChanges.cancel(); wake.close()
            }
            }}
        } } } finally { controls.values.forEach { it.stop() }; controls.clear(); running = false; runLock.unlock() }
    }
    fun waitingReason(prefs:Settings):ErrorCode? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        NetworkDiagnostics.connectionIssue(manager)?.let {return it}
        val cap = manager.getNetworkCapabilities(manager.activeNetwork) ?: return ErrorCode.NETWORK_UNAVAILABLE
        if(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return ErrorCode.CAPTIVE_PORTAL
        // NetworkDiagnostics already evaluates VPN and bearer validation together.
        if(!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ErrorCode.NETWORK_UNVALIDATED
        val wifi=cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && manager.allNetworks.any {network-> manager.getNetworkCapabilities(network)?.let {it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
        return if(prefs.wifiOnly&&!wifi) ErrorCode.WIFI_REQUIRED else null
    }
    private fun isWifi():Boolean {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val cap=manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&manager.allNetworks.any {network->manager.getNetworkCapabilities(network)?.let {it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
    }
    private fun isCharging()=context.getSystemService(android.os.BatteryManager::class.java).isCharging
    private suspend fun completedPreparationOwner(target:PlaylistPreparationTarget,manager:ConnectivityManager):String? {
        if(target.network!=manager.activeNetwork?.networkHandle) return null
        val owner=dao.get(target.ownerId)?.takeIf {it.status=="COMPLETED"} ?: return null
        val child=dao.get(target.childId) ?: return null
        if(child.batchId!=owner.batchId||child.url!=target.url||child.status!="QUEUED"||child.selection.isNotBlank()||
            child.request!=null||child.checkpoint!="DOWNLOAD"||child.bytes!=0L||child.retries!=0||child.pendingUri!=null||
            child.nextAttempt>System.currentTimeMillis()||!planEligible(child)) return null
        val batch=child.batchId?.let {dao.batch(it)} ?: return null
        return owner.id.takeIf {batch.state=="ACTIVE"&&batch.concurrency==1}
    }
    fun planEligible(row:DownloadEntity):Boolean {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        return DownloadPlan(row.scheduledAt,runCatching {DownloadNetwork.valueOf(row.scheduleNetwork)}.getOrDefault(DownloadNetwork.ANY),row.requiresCharging)
            .eligible(System.currentTimeMillis(),isWifi(),!manager.isActiveNetworkMetered,isCharging())
    }
    private suspend fun execute(initial: DownloadEntity, control: TransferControl,prepared:Deferred<Unit>?=null) {
        // Foreground ownership keeps the process eligible, not the CPU awake.
        // Hold only while an admitted request is doing work, never for an idle
        // queue waiting for Wi-Fi. Renew a bounded lease for legitimately long jobs.
        val cpu=context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:transfer")
        try {withRenewableWakeLock(cpu) {executeOwned(initial,control,prepared)}} finally {prepared?.cancel()}
    }
    private suspend fun executeOwned(initial:DownloadEntity,control:TransferControl,prepared:Deferred<Unit>?) {
        var entity=initial
        var justResolved=false
        try {
            if(dao.get(entity.id)?.status != "DOWNLOADING") throw CancellationException("Transfer state changed before start")
            if(entity.selection.isBlank()&&entity.request!=null) {
                // All numbered consumer requests, including unresolved legacy
                // exact rows, now mean a cap. Keep serialized identity untouched.
                val intent=DownloadRequest.parse(entity.request!!).let {if(!it.audioOnly&&it.height>0) it.copy(exact=false) else it}
                val request=entity.id
                control.addCanceller(request) {extractor.cancel(request)}
                try {
                    dao.event(DownloadEvent(downloadId=entity.id,state="ANALYZING"))
                    AnalysisTrace.checkpoint(entity.id,"background_extraction_started")
                    notifications.analyzing(entity)
                    val media=withControlCancellation(control) {
                        val base=extractor.analyze(entity.url,request,enrichOptional=false)
                        if(intent.audioOnly) base else extractor.videoMetadata(base,request)
                    }
                    control.check()
                    val selected=intent.resolve(media)
                    FormatDiagnostics.selection(media,selected,entity.id)
                    selected.transferSize?.let {AnalysisTrace.checkpoint(entity.id,"first_reliable_size")}
                    if(selected.json().toByteArray().size>1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                    val name=FileNames.output(media.title,selected.output,intent.template)
                    if(dao.resolvePlaylistItem(entity.id,media.title,media.source,media.thumbnail,media.duration,selected.json(),name,selected.size,
                        selected.transferSize?.let {if(it.estimated) "ESTIMATED" else "EXACT"} ?: "UNKNOWN")!=1) throw CancellationException("State changed during extraction")
                    dao.identity(entity.id,MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url),entity.fingerprint)
                    dao.event(DownloadEvent(downloadId=entity.id,state="FORMATS_RESOLVED"))
                    AnalysisTrace.checkpoint(entity.id,"background_formats_resolved")
                    entity=dao.get(entity.id) ?: throw CancellationException("Request removed")
                    justResolved=true
                } finally {control.removeCanceller(request)}
            }
            if(entity.selection.isBlank()&&entity.batchId!=null) {
                val batch=dao.batch(entity.batchId!!) ?: throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                val request=java.util.UUID.randomUUID().toString()
                AnalysisTrace.value(request,"download_id",entity.id)
                control.addCanceller(request) {extractor.cancel(request)}
                try {
                    dao.event(DownloadEvent(downloadId=entity.id,state="ANALYZING"))
                    val media=withControlCancellation(control) {
                        // Adopt the existing subscriber before normal cache/expiry validation.
                        // A failed preparation reaches the unchanged retry/cooldown path once,
                        // only now that this child has actually been claimed.
                        awaitPlaylistPreparation(prepared)
                        control.check()
                        extractor.analyzePlaylistItem(entity.url,request,subscriber=AnalysisSubscriber.DURABLE)
                    }
                    val policy=PlaylistPolicy.parse(batch.policy)
                    val verified=if(policy.audio) media else withControlCancellation(control) {extractor.videoMetadata(media,request)}
                    val selected=policy.copy(fallback=if(policy.fallback==QualityFallback.EXACT) QualityFallback.AT_MOST else policy.fallback).resolve(verified)
                    FormatDiagnostics.selection(verified,selected,entity.id)
                    if(selected.json().toByteArray().size>1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                    val prefix=if(batch.numbered) "${entity.playlistPosition.toString().padStart(3,'0')} - " else ""
                    val name=FileNames.output(prefix+media.title,selected.output,"{title}")
                    if(dao.resolvePlaylistItem(entity.id,media.title,media.source,media.thumbnail,media.duration,selected.json(),name,selected.size,selected.transferSize?.let {if(it.estimated) "ESTIMATED" else "EXACT"} ?: "UNKNOWN")!=1) throw CancellationException("State changed during extraction")
                    dao.identity(entity.id,MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url),RequestFingerprint.create(media,selected))
                    entity=dao.get(entity.id) ?: throw CancellationException("Item removed")
                    justResolved=true
                } finally {control.removeCanceller(request)}
            }
            val initialStartAt = SystemClock.elapsedRealtime()
            var videoDownloadMs = 0L
            var audioDownloadMs = 0L
            var processingAdmitted=false
            var networkTracksFinishedAt=0L
            val dir = storage.temporary(entity.id)
            withControlCancellation(control) { NativeWork.awaitIdle(dir) }
            var selection = Selection.parse(entity.selection)
            var refreshed = false
            val retryCause = if(entity.retries > 0) dao.lastRetryError(entity.id) ?: entity.error else null
            val expired = listOfNotNull(selection.primary, selection.secondary).any {
                it.expiresAt?.let { deadline -> deadline <= System.currentTimeMillis() + 5_000 } == true
            }
            if (shouldRefreshSelection(entity.checkpoint, justResolved, retryCause, expired)) {
                selection = refresh(entity, selection, control)
                refreshed = true
            }
            if(selection.size != null && selection.size!! > android.os.StatFs(context.filesDir.path).availableBytes/2) throw MediaException(ErrorCode.INSUFFICIENT_STORAGE)
            val primary = File(dir,"primary.${selection.primary.ext}")
            val secondary = selection.secondary?.let { File(dir,"secondary.${it.ext}") }
            // A sequential playlist usually has one network-active child even
            // when the saved global queue limit is 2-5. Do not reserve fragment
            // connections for those idle slots or for CPU-only finalization.
            val networkDownloads=controls.keys.mapNotNull {dao.get(it)}.count {it.checkpoint=="DOWNLOAD"}.coerceAtLeast(1)
            val power=context.getSystemService(android.os.PowerManager::class.java)
            val memory=android.app.ActivityManager.MemoryInfo().also {context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)}
            val constrained=memory.lowMemory||power.isPowerSaveMode||(android.os.Build.VERSION.SDK_INT>=29&&power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_MODERATE)
            control.fragmentConcurrency=FragmentBudget.perTrack(networkDownloads,if(secondary==null) 1 else 2,constrained)
            NetworkDiagnostics.record("queue","fragment_budget_allocated",null,operation=entity.id,
                details=org.json.JSONObject().put("network_downloads",networkDownloads)
                    .put("tracks",if(secondary==null) 1 else 2).put("per_track",control.fragmentConcurrency).put("constrained",constrained))
            var lastTime = SystemClock.elapsedRealtime(); var lastBytes: Long? = null; var lastAdvanceTime = lastTime; var speed = 0.0
            val speedSamples = java.util.ArrayDeque<Pair<Long, Long>>()
            fun progressState():TransferProgress {
                val formats=listOfNotNull(selection.primary,selection.secondary)
                val files=listOfNotNull(primary,secondary)
                return TransferProgress(formats.map {it.size(selection.duration)},selection.transferSize,
                    formats.zip(files).map {(format,file)->transfer.retainedBytes(format,file)})
            }
            var aggregate=progressState()
            var progressSent=false
            val progressLock = Any()
            val progressUpdates=Channel<ProgressUpdate>(Channel.CONFLATED)
            val progressWriter=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                for(update in progressUpdates) {
                    dao.progress(entity.id,update.bytes,update.total,update.speed,update.kind)
                    AnalysisTrace.mark(entity.id,"first_progress_updated")
                    notifications.progress(entity.copy(status=dao.get(entity.id)?.status ?: entity.status,sizeKind=update.kind),update.bytes,update.total,update.speed)
                }
            }
            var lastSnapshot: TransferSnapshot? = null
            val stallMonitor=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                while(isActive) {
                    delay(1000)
                    var stallUpdate: ProgressUpdate? = null
                    synchronized(progressLock) {
                        val now = SystemClock.elapsedRealtime()
                        if(progressSent && (now - lastAdvanceTime) >= 1500L && speed > 0.0) {
                            speed = 0.0
                            speedSamples.clear()
                            val snap = lastSnapshot
                            if(snap != null) {
                                stallUpdate = ProgressUpdate(snap.bytes, snap.total, 0L, snap.kind)
                            }
                        }
                    }
                    stallUpdate?.let { progressUpdates.trySend(it) }
                }
            }
            fun progress(track: Int, bytes: Long, total: Long?) {
                control.check()
                synchronized(progressLock) {
                    val format=if(track==0) selection.primary else selection.secondary!!
                    val metadataSize=format.size(selection.duration)
                    val kind=if(total!=null) control.totalKinds[format.id] ?: "ESTIMATED" else metadataSize?.let {if(it.estimated) "ESTIMATED" else "EXACT"} ?: "UNKNOWN"
                    val snapshot=aggregate.update(track,bytes,total,kind)
                    val transferred=snapshot.bytes
                    lastSnapshot=snapshot
                    if(snapshot.total!=null) AnalysisTrace.mark(entity.id,"first_reliable_size")
                    val now = SystemClock.elapsedRealtime()
                    if(lastBytes==null || transferred > lastBytes!!) {
                        lastAdvanceTime = now
                    }
                    speedSamples.addLast(Pair(now, transferred))
                    while(speedSamples.size > 1 && now - speedSamples.first.first > 2000L) {
                        speedSamples.removeFirst()
                    }
                    if(!progressSent || now-lastTime>=500) {
                        if(progressSent&&lastBytes!=null) {
                            val oldest = speedSamples.first
                            val dt = now - oldest.first
                            val db = (transferred - oldest.second).coerceAtLeast(0)
                            val raw = if(dt >= 200) db.toDouble() * 1000.0 / dt else 0.0
                            val isStalled = (now - lastAdvanceTime) >= 1500L
                            speed = if(isStalled) 0.0 else if(speed == 0.0) raw else (0.35 * raw + 0.65 * speed)
                        }
                        lastBytes=transferred;lastTime=now
                        progressSent=true
                        // Enqueue while holding the aggregation lock so two track
                        // threads cannot publish an older snapshot after a newer one.
                        progressUpdates.trySend(ProgressUpdate(transferred,snapshot.total,speed.toLong(),snapshot.kind))
                    }
                }
            }
            if(entity.checkpoint=="DOWNLOAD") {
                val phase=if(selection.audioOnly) "DOWNLOADING_AUDIO" else "DOWNLOADING_VIDEO"
                if(dao.transferStage(entity.id,phase)!=1) throw CancellationException("Transfer state changed")
                dao.event(DownloadEvent(downloadId=entity.id,state=phase))
                AnalysisTrace.checkpoint(entity.id,"transfer_started")
                notifications.progress(entity.copy(status=phase,sizeKind=selection.transferSize?.let {if(it.estimated) "ESTIMATED" else "EXACT"} ?: "UNKNOWN"),0,selection.size)
            }
            suspend fun downloadTracks() = coroutineScope {
                // On signed-URL refresh the cancelled sibling's Python worker may
                // still own the files even after its coroutine returned.
                withControlCancellation(control) {NativeWork.awaitIdle(dir)}
                synchronized(progressLock) {
                    aggregate=progressState()
                    lastBytes=aggregate.retainedBytes;lastTime=SystemClock.elapsedRealtime();lastAdvanceTime=lastTime;speed=0.0;speedSamples.clear();progressSent=false
                }
                val overlap=if(secondary!=null&&CopyOutputVerification.eligible(selection))
                    processor.beginCopyVerification(context,CopyVerification.Inputs(primary,secondary,control)) else null
                control.copySources=null
                if(overlap!=null) NetworkDiagnostics.record("media","source_overlap_started",null,operation=entity.id)
                try {
                if(secondary == null) {
                    val trackStart = SystemClock.elapsedRealtime()
                    transfer.stream(selection.primary,primary,entity.id,control,awaitIdle=false,
                        onFirstNewByte={AnalysisTrace.checkpoint(entity.id,"first_live_bytes")}) { bytes,total -> progress(0,bytes,total) }
                    videoDownloadMs = SystemClock.elapsedRealtime() - trackStart
                } else {
                    // Video and audio tracks are independent files; fetch them
                    // concurrently and join. Progress state is synchronized above.
                    var vMs = 0L; var aMs = 0L
                    val first = async {
                        val vStart = SystemClock.elapsedRealtime()
                        transfer.stream(selection.primary,primary,entity.id,control,awaitIdle=false,
                            onFirstNewByte={AnalysisTrace.checkpoint(entity.id,"first_live_bytes")}) { bytes,total -> progress(0,bytes,total);overlap?.progress(primary) }
                        overlap?.transferred(primary)
                        vMs = SystemClock.elapsedRealtime() - vStart
                    }
                    val second = async {
                        val aStart = SystemClock.elapsedRealtime()
                        dao.event(DownloadEvent(downloadId=entity.id,state="AUDIO_TRACK_STARTED"))
                        transfer.stream(requireNotNull(selection.secondary),secondary,entity.id,control,awaitIdle=false,
                            onFirstNewByte={AnalysisTrace.checkpoint(entity.id,"first_live_bytes")}) { bytes,total -> progress(1,bytes,total);overlap?.progress(secondary) }
                        overlap?.transferred(secondary)
                        dao.event(DownloadEvent(downloadId=entity.id,state="AUDIO_TRACK_COMPLETE"))
                        aMs = SystemClock.elapsedRealtime() - aStart
                    }
                    first.await()
                    if(!second.isCompleted) {dao.transferStage(entity.id,"DOWNLOADING_AUDIO");dao.event(DownloadEvent(downloadId=entity.id,state="DOWNLOADING_AUDIO"))}
                    second.await()
                    videoDownloadMs = vMs
                    audioDownloadMs = aMs
                }
                networkTracksFinishedAt=SystemClock.elapsedRealtime()
                if(overlap!=null) {
                    val tailStarted=SystemClock.elapsedRealtime()
                    AnalysisTrace.checkpoint(entity.id,"network_tracks_completed")
                    AnalysisTrace.checkpoint(entity.id,"transfer_completed")
                    dao.event(DownloadEvent(downloadId=entity.id,state="TRANSFER_COMPLETE"))
                    if(dao.processing(entity.id)!=1||dao.processingStage(entity.id,"VALIDATING")!=1)
                        throw CancellationException("Transfer state changed")
                    processingAdmitted=true
                    notifications.processing(entity,"VALIDATING")
                    control.copySources=overlap.awaitSources()
                    NetworkDiagnostics.record("media","source_overlap_completed",null,operation=entity.id,
                        details=org.json.JSONObject().put("decode_tail_ms",SystemClock.elapsedRealtime()-tailStarted)
                            .put("complete_source_evidence",control.copySources!=null)
                            .put("video_decoded_before_eof",control.copySources?.video?.decodedBeforeTransferEnd)
                            .put("audio_decoded_before_eof",control.copySources?.audio?.decodedBeforeTransferEnd))
                }
                } finally {
                    overlap?.cancel()
                    if(overlap!=null&&!overlap.awaitSettled()) {
                        control.copySources=null
                        throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                    }
                }
            }
            try {
                try { if(entity.checkpoint=="DOWNLOAD") downloadTracks() } catch(e: MediaException) {
                    if(e.code !in listOf(ErrorCode.AUTHENTICATION_REQUIRED,ErrorCode.HTTP_FORBIDDEN,ErrorCode.MEDIA_URL_EXPIRED) || refreshed) throw e
                    selection=refresh(entity,selection,control)
                    refreshed=true
                    downloadTracks()
                }
            } finally {
                stallMonitor.cancel()
                progressUpdates.close()
                progressWriter.join()
            }
            control.check()
            if(!processingAdmitted) dao.event(DownloadEvent(downloadId=entity.id,state="TRANSFER_COMPLETE"))
            AnalysisTrace.checkpoint(entity.id,"transfer_completed")
            if(entity.checkpoint in setOf("DOWNLOAD","DOWNLOADED")) {
                val retained=primary.length()+(secondary?.length() ?: 0)
                dao.progress(entity.id,retained,retained.takeIf {it>0},0,if(retained>0) "EXACT" else "UNKNOWN")
            }
            val subtitleFile=selection.subtitle?.let {track->
                require(!selection.audioOnly&&selection.output in setOf("mp4","mkv"))
                val target=File(dir,"subtitle.${track.extension}")
                if(entity.checkpoint !in setOf("MERGED","VALIDATED")||!File(dir,"output.${selection.output}").isFile) {
                    transfer.stream(Format.parse(org.json.JSONObject(track.payload)),target,entity.id,control) {bytes,_->if(bytes>2*1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)}
                    if(target.length()>2*1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                }
                target
            }
            val postDownloadStartAt = networkTracksFinishedAt.takeIf {it>0} ?: SystemClock.elapsedRealtime()
            if(!processingAdmitted&&dao.processing(entity.id) != 1) throw CancellationException("Transfer state changed")
            val stage = if(selection.transcodeVideo || selection.transcodeAudio) "CONVERTING" else if(selection.secondary != null) "MERGING" else "PROCESSING"
            if(dao.processingStage(entity.id,"WAITING_FOR_PROCESSOR") != 1) throw CancellationException("Transfer state changed")
             notifications.processing(entity,"WAITING_FOR_PROCESSOR")
             control.onProcessingReady={
                 AnalysisTrace.checkpoint(entity.id,"processor_admitted")
                 if(dao.processingStage(entity.id,stage)!=1) throw CancellationException("Transfer state changed")
                 notifications.processing(entity,stage)
             }
            val output = File(dir,"output.${selection.output}")
            val committed = coroutineScope {
            val publicationApproved=CompletableDeferred<Unit>()
            var pendingExport:Deferred<android.net.Uri>?=null
            // SAF providers need not hide temporary documents; keep their serial path.
            val canStage=entity.destination.isNullOrBlank()&&android.os.Build.VERSION.SDK_INT>=29
            if(canStage) control.awaitPublicationValidation={publicationApproved.await()}
            control.onValidationReady={
                if(dao.merged(entity.id)!=1 || dao.processingStage(entity.id,"VALIDATING")!=1) throw CancellationException("Transfer state changed")
                 AnalysisTrace.checkpoint(entity.id,"processing_completed");notifications.processing(entity,"VALIDATING")
                 AnalysisTrace.checkpoint(entity.id,"validation_started")
                // The muxer has closed output; copying may overlap strict decode, never publication.
                // A packet-proof path binds the exact output hash before export starts.
                 if(canStage&&control.copySources==null) pendingExport=async(Dispatchers.IO) {
                     AnalysisTrace.checkpoint(entity.id,"publication_staging_started")
                     storage.commit(entity,output,selection,control)
                 }
             }
             try {
             // Avoid optional network/image work when the encoder will not consume a cover.
             val needsCover=selection.audioOnly&&selection.output=="mp3"&&selection.convertAudio&&selection.embedMetadata&&!entity.thumbnail.isNullOrBlank()
             AnalysisTrace.value(entity.id,"cover_required",needsCover)
             AnalysisTrace.checkpoint(entity.id,"cover_retrieval_started")
             val thumbTemp = try {if (needsCover) {
                runCatching {
                    val src = entity.thumbnail
                    val temp = File(dir, "thumb-${java.util.UUID.randomUUID()}.jpg")
                    if (src.startsWith("http://") || src.startsWith("https://")) {
                        val req = okhttp3.Request.Builder()
                            .url(src)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .build()
                        val call=Transfer.networkClient.newBuilder().callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build().newCall(req)
                        control.add(call)
                        try {call.execute().use {resp->
                            if(resp.isSuccessful) resp.body?.let {body->
                                if(body.contentLength()>4*1024*1024) throw java.io.IOException("Cover too large")
                                body.byteStream().use {input->temp.outputStream().use {sink->
                                    val buffer=ByteArray(32*1024);var size=0
                                    while(true) {
                                        control.check();currentCoroutineContext().ensureActive()
                                        val count=input.read(buffer);if(count<0) break
                                        size+=count;if(size>4*1024*1024) throw java.io.IOException("Cover too large")
                                        sink.write(buffer,0,count)
                                    }
                                }}
                            }
                        }} finally {control.remove(call)}
                    } else if (src.startsWith("file://")) {
                        File(android.net.Uri.parse(src).path ?: "").copyTo(temp, overwrite = true)
                    } else {
                        val f = File(src)
                        if (f.isFile) f.copyTo(temp, overwrite = true)
                    }
                    if(temp.isFile&&temp.length() in 1..com.zdownloader.app.playback.EmbeddedArtwork.MAX_ENCODED_BYTES.toLong()) {
                        // Use Android's bounded web-image decoder before muxing;
                        // this temporary cover does not evict player artwork.
                        if(com.zdownloader.app.playback.EmbeddedArtwork.normalizeTemporaryCover(temp.readBytes(),temp)) temp
                        else {temp.delete();null}
                    } else {temp.delete();null}
                }.getOrElse {error->if(error is CancellationException) throw error;null}
             } else null} finally {AnalysisTrace.checkpoint(entity.id,"cover_retrieval_finished")}
             try {
                 try {
                     AnalysisTrace.checkpoint(entity.id,"processor_requested")
                    if(entity.checkpoint in listOf("MERGED","VALIDATED") && output.isFile) processor.resumeValidation(output,selection,control,entity.duration,primary)
                    else processor.process(primary,secondary,output,selection,control,entity.duration,entity.title,subtitleFile,thumbTemp)
                    if(dao.validated(entity.id)!=1) throw CancellationException("Transfer state changed")
                    AnalysisTrace.checkpoint(entity.id,"output_validated")
                } finally {
                    thumbTemp?.delete()
                }
            } catch(e:MediaException) {
                NetworkDiagnostics.record("media","processing_failure",e.code,operation=entity.id,
                    details=org.json.JSONObject().put("validation_check",e.validationCheck))
                // Source diagnosis must never start an export of the failed output.
                control.onValidationReady=null
                try {cancelPendingExport(pendingExport)} catch(cleanup:Exception) {e.addSuppressed(cleanup);throw e}
                // Diagnose each source independently; never discard the good track.
                if(e.code in listOf(ErrorCode.OUTPUT_VALIDATION_FAILED,ErrorCode.MERGE_FAILED,ErrorCode.CONVERSION_FAILED)) {
                    var corrupt = false
                    withControlCancellation(control) {NativeWork.awaitIdle(dir)}
                    for((track,format) in listOfNotNull(primary to selection.primary,secondary?.let { it to selection.secondary!! })) {
                        // Check intrinsic integrity separately from extractor claims.
                        // A height/FPS/duration mismatch is not proof of broken bytes.
                        try {
                            if(format.image) ImageFinalizer.validate(track,format,control)
                            else processor.validate(track,!format.video,format.audio,control=control)
                        }
                        catch(validation: MediaException) {
                            if(validation.code==ErrorCode.DECODER_UNAVAILABLE) {
                                // An unavailable diagnostic decoder cannot replace the actual processing failure.
                                e.addSuppressed(validation);throw e
                            }
                            if(validation.code != ErrorCode.OUTPUT_VALIDATION_FAILED) throw validation
                            if(validation.validationCheck !in setOf("no_streams","decode","duration","decoded_frame_count")) throw validation
                            TrackFiles.invalidate(track,dir);corrupt=true
                        }
                    }
                    if(corrupt) {dao.refreshSelection(entity.id,selection.json(),selection.size);dao.resetCheckpoint(entity.id,primary.length()+(secondary?.length() ?: 0));throw MediaException(ErrorCode.SOURCE_CORRUPT)}
                    if(e.code==ErrorCode.OUTPUT_VALIDATION_FAILED&&e.validationCheck in setOf("no_streams","decode","missing_stream","audio_timing","audio_timing_contract")) {
                        // The source tracks passed full integrity checks. A bad
                        // generated container must be regenerated on retry, not
                        // repeatedly validated or downloaded from the network.
                        if(output.exists()&&!output.delete()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                        if(dao.reprocessDownloadedTracks(entity.id)!=1) throw CancellationException("Transfer state changed")
                        throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED,validationCheck="regenerate_valid_sources")
                    }
                    if(e.validationCheck in setOf("height","width","fps","video_codec","duration_mismatch","container","missing_stream"))
                        throw MediaException(ErrorCode.MEDIA_METADATA_MISMATCH,validationCheck=e.validationCheck)
                }
                throw e
            }
            control.check()
            if(dao.processingStage(entity.id,"SAVING") != 1) throw CancellationException("Transfer state changed")
             notifications.processing(entity,"SAVING")
             AnalysisTrace.checkpoint(entity.id,"publication_validation_released")
             publicationApproved.complete(Unit)
             pendingExport?.await() ?: run {
                 AnalysisTrace.checkpoint(entity.id,"publication_staging_started")
                 storage.commit(entity,output,selection,control)
             }
            } finally {
                publicationApproved.cancel()
                pendingExport?.cancel()
            }
            }
            AnalysisTrace.checkpoint(entity.id,"gallery_publication_completed")
            val postDownloadTotalMs = SystemClock.elapsedRealtime() - postDownloadStartAt
            val endToEndMs = SystemClock.elapsedRealtime() - initialStartAt
            NetworkDiagnostics.record("media","video_pipeline_profiling",null,operation=entity.id,
                details=org.json.JSONObject()
                    .put("video_bytes",primary.length())
                    .put("audio_bytes",secondary?.length() ?: 0L)
                    .put("output_bytes",output.length())
                    .put("video_codec",selection.primary.videoCodec ?: "unknown")
                    .put("audio_codec",selection.secondary?.audioCodec ?: selection.primary.audioCodec ?: "unknown")
                    .put("container",selection.output)
                    .put("stream_copy",!selection.transcodeVideo && !selection.transcodeAudio)
                    .put("transcoded",selection.transcodeVideo || selection.transcodeAudio)
                    .put("file_copy_count",1)
                    .put("video_download_ms",videoDownloadMs)
                    .put("audio_download_ms",audioDownloadMs)
                    .put("post_download_total_ms",postDownloadTotalMs)
                    .put("end_to_end_ms",endToEndMs))
            dao.event(DownloadEvent(downloadId=entity.id,state="COMPLETED"))
            notifications.completed(entity,committed,selection)
            storage.removeTemporary(entity.id)
        } catch (e: CancellationException) {
            val ownerAlive=currentCoroutineContext().isActive
            withContext(NonCancellable) {
                dao.interrupt(entity.id)
                settledStopNotice(entity.id)
                // A control/native child interruption is not a user pause. A
                // cancelled platform owner instead leaves its checkpoint for
                // the already durable JobScheduler/WorkManager recovery task.
                if(ownerAlive) recoverOrFail(entity,ErrorCode.DOWNLOAD_INTERRUPTED)
                else NetworkDiagnostics.record("queue","platform_owner_interrupted",ErrorCode.DOWNLOAD_INTERRUPTED,operation=entity.id,
                    details=org.json.JSONObject().put("checkpoint",dao.get(entity.id)?.checkpoint))
            }
        } catch(e: Exception) {
            if(control.stopped.get()) { dao.interrupt(entity.id); settledStopNotice(entity.id);recoverOrFail(entity,ErrorCode.DOWNLOAD_INTERRUPTED);return }
             val code = when(e) { is MediaException -> e.code; is SecurityException -> ErrorCode.STORAGE_PERMISSION_ERROR; is java.io.IOException -> if(e.message.orEmpty().contains("ENOSPC",true)) ErrorCode.INSUFFICIENT_STORAGE else ErrorCode.FILE_WRITE_FAILED; else -> ErrorCode.INTERNAL_ENGINE_ERROR }
            adaptive.failure(code,SystemClock.elapsedRealtime())
            recoverOrFail(entity,code,(e as? MediaException)?.retryAfterMillis,e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                try {
                    val status = dao.get(entity.id)?.status
                    if(status == "PAUSING") dao.interrupt(entity.id)
                    if(status == "CANCELLED" || status == null) { notifications.dismiss(entity.id);storage.cleanupPending(entity.id); storage.removeTemporary(entity.id) }
                } finally { controls.remove(entity.id); control.finished.complete(Unit) }
            }
        }
    }
    private suspend fun cancelPendingExport(export:Job?) {
        if(export==null) return
        withContext(NonCancellable) {
            export.cancel()
            if(withTimeoutOrNull(10_000L) {export.join();true}!=true)
                throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        }
    }
    private suspend fun settledStopNotice(id:String) {
        val row=dao.get(id)
        if(row?.status=="WAITING_FOR_NETWORK") notifications.waiting(row,ErrorCode.parse(row.error)) else notifications.dismiss(id)
    }
    private suspend fun recoverOrFail(entity:DownloadEntity,code:ErrorCode,retryAfter:Long?=null,exceptionClass:String?=null) {
        val latest=dao.get(entity.id) ?: return
        // Explicit pause/cancel and completion always win over late callbacks.
        if(latest.status in setOf("PAUSING","PAUSED","CANCELLED","COMPLETED","WAITING_FOR_NETWORK")) return
        val offline=if(code.recoverable&&latest.checkpoint=="DOWNLOAD") waitingReason(settings.current()) else null
        if(offline!=null&&dao.waitForNetwork(entity.id,offline.name)==1) {
            notifications.waiting(latest,offline)
            NetworkDiagnostics.record("queue","waiting_for_validated_route",offline,operation=entity.id,
                details=org.json.JSONObject().put("bytes",latest.bytes).put("retries_preserved",latest.retries).put("trigger",code.name))
            return
        }
        val retryLimit=latest.request?.let {runCatching {DownloadRequest.parse(it).retries}.getOrNull()} ?: settings.current().retries
        val canRetry=code.recoverable&&latest.retries<retryLimit
        val next=System.currentTimeMillis()+maxOf(RetryPolicy.delayMillis(latest.retries,retryAfter),if(code==ErrorCode.RATE_LIMITED) 60_000L else 0L)
        val changed=if(canRetry) dao.retryLater(entity.id,next,code.name) else dao.fail(entity.id,code.name)
        if(code==ErrorCode.RATE_LIMITED&&changed==1&&latest.batchId!=null) {
            val delayed=dao.deferThrottledBatch(latest.batchId,next)
            NetworkDiagnostics.record("queue","playlist_provider_cooldown",code,operation=entity.id,
                details=org.json.JSONObject().put("delayed_items",delayed).put("next_attempt",next))
        }
        NetworkDiagnostics.record("queue",if(canRetry) "automatic_retry" else "terminal_failure",code,operation=entity.id,
            details=org.json.JSONObject().put("state",latest.status).put("checkpoint",latest.checkpoint).put("bytes",latest.bytes)
                .put("attempt",latest.retries).put("transition_applied",changed==1).put("next_attempt",if(canRetry) next else null)
                .put("exception_class",exceptionClass))
        if(!canRetry&&changed==1) notifications.failed(entity,code)
    }
    private suspend fun refresh(entity: DownloadEntity, selection: Selection, control: TransferControl): Selection {
        val request = java.util.UUID.randomUUID().toString()
        control.addCanceller(request) { extractor.cancel(request) }
        try {
            control.check()
            // Older extractor selections contain language/HDR/channel metadata that
            // the lightweight direct-header resolver cannot reconstruct.
            val base = extractor.analyze(entity.url,request,forceRefresh=true,enrichOptional=false,
                forceExtractor=selection.primary.protocol in listOf("http","https") && selection.primary.id!="direct")
            // Refresh the SAME selected track's measured geometry, not a label-
            // derived replacement. Priority only affects this private refresh;
            // normal catalogue order and the two-header inspection budget stay intact.
            val media=if(!selection.audioOnly&&selection.primary.dimensionsVerified)
                extractor.videoMetadata(base.copy(formats=base.formats.sortedByDescending {it.id==selection.primary.id}),request)
                else base
            control.check()
            fun replacement(old: Format): Format {
                media.formats.firstOrNull { it.preservesSelection(old) }?.let { return it }
                // Preserve pre-0.3 direct requests whose generic extractor ID differs from the native resolver ID.
                if(old.protocol in listOf("https","http") && org.json.JSONObject(old.payload).optString("url")==entity.url) {
                    media.formats.singleOrNull()?.let { direct ->
                        // The bounded MP4 header resolver does not compute FPS.
                        // Retain the known value so output validation still checks it.
                        val compatible=direct.copy(id=old.id,fps=direct.fps ?: old.fps,
                            payload=org.json.JSONObject(direct.payload).put("format_id",old.id).put("fps",direct.fps ?: old.fps).toString())
                        if(compatible.preservesSelection(old)) return compatible
                    }
                }
                throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            }
            val fresh = selection.copy(primary=replacement(selection.primary),secondary=selection.secondary?.let(::replacement),duration=selection.duration ?: media.duration,subtitle=selection.subtitle?.let {old->media.subtitles.firstOrNull {it.language==old.language} ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)})
            if(dao.refreshSelection(entity.id,fresh.json(),fresh.size) != 1) throw CancellationException("State changed during media refresh")
            return fresh
        } finally { control.removeCanceller(request) }
    }
}
