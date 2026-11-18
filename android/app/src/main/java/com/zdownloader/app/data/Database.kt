package com.zdownloader.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads", indices = [Index("status"), Index("createdAt"), Index("fingerprint"),Index("batchId"),Index("mediaKey"),Index("urlKey")])
data class DownloadEntity(
    @PrimaryKey val id: String, val url: String, val title: String, val source: String,
    val thumbnail: String?, val duration: Double?, val selection: String,
    val filename: String, val destination: String?, val fingerprint: String,
    val status: String = "QUEUED", val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null, val bytes: Long = 0, val total: Long? = null,
    val speed: Long = 0, val uri: String? = null, val pendingUri: String? = null,
    val error: String? = null, val retries: Int = 0, val nextAttempt: Long = 0,
    val outputSize: Long? = null,
    @ColumnInfo(defaultValue="'DOWNLOAD'") val checkpoint:String="DOWNLOAD",
    @ColumnInfo(defaultValue="0") val notificationPending:Boolean=false,
    val batchId:String?=null,
    @ColumnInfo(defaultValue="0") val playlistPosition:Int=0,
    val relativeFolder:String?=null,
    @ColumnInfo(defaultValue="0") val priority:Int=0,
    @ColumnInfo(defaultValue="'UNKNOWN'") val sizeKind:String="UNKNOWN",
    @ColumnInfo(defaultValue="''") val mediaKey:String="",
    @ColumnInfo(defaultValue="''") val urlKey:String="",
    @ColumnInfo(defaultValue="0") val scheduledAt:Long=0,
    @ColumnInfo(defaultValue="'ANY'") val scheduleNetwork:String="ANY",
    @ColumnInfo(defaultValue="0") val requiresCharging:Boolean=false,
    val request:String?=null
)
@Entity(tableName = "events", indices = [Index("downloadId")])
data class DownloadEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val downloadId: String, val time: Long = System.currentTimeMillis(), val state: String, val error: String? = null)
data class QueueSignal(val id:String,val status:String,val nextAttempt:Long)
data class DownloadMatch(val id:String,val uri:String?,val status:String,val fingerprint:String,val mediaKey:String)

/** Source links only: no partial files, signed stream payloads or automatic retry jobs. */
@Entity(tableName="download_trash",indices=[Index("archivedAt")])
data class DownloadTrashEntry(@PrimaryKey val id:String,val url:String,val title:String,val reason:String,val archivedAt:Long=System.currentTimeMillis())

@Entity(tableName="playlist_batches",indices=[Index(value=["submissionKey"],unique=true)])
data class PlaylistBatch(@PrimaryKey val id:String,val submissionKey:String,val url:String,val title:String,val policy:String,val createdAt:Long=System.currentTimeMillis(),val state:String="ACTIVE",val numbered:Boolean=true,
    @ColumnInfo(defaultValue="1") val concurrency:Int=1,val thumbnail:String?=null)
data class BatchSummary(@Embedded val batch:PlaylistBatch,val selected:Int,val completed:Int,val failed:Int,val cancelled:Int,val paused:Int,val active:Int,val scheduled:Int,val scheduledAt:Long,val scheduleNetwork:String,val requiresCharging:Boolean,
    val transferred:Long,val expectedSum:Double,val knownSizes:Int,val estimatedSizes:Int,val networkDone:Int,val currentTitle:String?,val currentState:String?) {
    val waiting get()=(selected-completed-failed-cancelled-paused-active).coerceAtLeast(0)
    val finished get()=completed+failed+cancelled==selected
    val canRemoveCancelled get()=(batch.state=="CANCELLED" && completed+cancelled==selected) || (selected>0 && cancelled==selected)
    val progress get()=com.zdownloader.app.domain.PlaylistProgress(transferred,expectedSum.takeIf {knownSizes==selected&&selected>0&&it.isFinite()&&it<Long.MAX_VALUE.toDouble()&&it>=transferred}?.toLong(),estimatedSizes>0,selected,completed,failed,cancelled,networkDone)
    val status get()=when {batch.state!="ACTIVE"->batch.state;scheduled>0&&active==0->"SCHEDULED";!finished&&paused>0&&active==0&&waiting==0->"PAUSED";!finished->"ACTIVE";completed==selected->"COMPLETED";cancelled==selected->"CANCELLED";failed==selected->"FAILED";else->"PARTIAL"}
}

@Dao abstract class DownloadDao {
    @Query("SELECT * FROM download_trash ORDER BY archivedAt DESC,id") abstract fun trash():Flow<List<DownloadTrashEntry>>
    @Query("SELECT * FROM download_trash WHERE id=:id") abstract suspend fun trashEntry(id:String):DownloadTrashEntry?
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun archive(entry:DownloadTrashEntry)
    @Query("DELETE FROM download_trash WHERE id=:id") abstract suspend fun forgetTrash(id:String)
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") abstract fun observe(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE status='COMPLETED' ORDER BY createdAt DESC") abstract fun completed():Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT 3") abstract fun recent():Flow<List<DownloadEntity>>
    @Query("SELECT id,status,nextAttempt FROM downloads WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED','PAUSED','SCHEDULED')") abstract fun queueSignals(): Flow<List<QueueSignal>>
    @Query("SELECT * FROM downloads WHERE pendingUri IS NOT NULL AND status NOT IN ('COMPLETED','SAVING')") abstract suspend fun pendingPublications():List<DownloadEntity>
    @Query("SELECT DISTINCT destination FROM downloads WHERE destination IS NOT NULL") abstract suspend fun referencedDestinations():List<String>
    @Query("SELECT * FROM downloads WHERE id=:id") abstract suspend fun get(id: String): DownloadEntity?
    @Query("SELECT d.* FROM downloads d LEFT JOIN playlist_batches b ON b.id=d.batchId WHERE d.pendingUri IS NULL AND d.status IN ('QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING') AND d.nextAttempt<=:now AND d.scheduledAt<=:now AND (d.checkpoint!='DOWNLOAD' OR ((d.scheduleNetwork!='WIFI' OR :wifi) AND (d.scheduleNetwork!='UNMETERED' OR :unmetered) AND (d.requiresCharging=0 OR :charging))) AND (:offline=0 OR d.checkpoint!='DOWNLOAD') AND (d.batchId IS NULL OR (b.state='ACTIVE' AND ((b.concurrency=1 AND NOT EXISTS (SELECT 1 FROM downloads a WHERE a.batchId=d.batchId AND a.id!=d.id AND (a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','PAUSING') OR (a.playlistPosition<d.playlistPosition AND a.status NOT IN ('COMPLETED','FAILED','CANCELLED','PAUSED'))))) OR (b.concurrency!=1 AND (b.concurrency=0 OR (SELECT COUNT(*) FROM downloads a WHERE a.batchId=d.batchId AND a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO'))<b.concurrency))))) ORDER BY d.priority DESC,CASE WHEN :preferSingle THEN d.batchId IS NOT NULL ELSE d.batchId IS NULL END,d.createdAt,d.playlistPosition LIMIT 1") abstract suspend fun next(now: Long,offline:Boolean=false,preferSingle:Boolean=true,wifi:Boolean=true,unmetered:Boolean=true,charging:Boolean=true): DownloadEntity?
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING')") abstract suspend fun pendingRows():List<DownloadEntity>
    // Metadata-only look-ahead. The real next()/claim() rules remain authoritative.
    @Query("SELECT d.* FROM downloads d JOIN playlist_batches b ON b.id=d.batchId JOIN downloads o ON o.id=:owner AND o.batchId=b.id WHERE b.state='ACTIVE' AND b.concurrency=1 AND o.checkpoint!='DOWNLOAD' AND o.status IN ('PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING') AND d.playlistPosition>o.playlistPosition AND d.status='QUEUED' AND d.selection='' AND d.request IS NULL AND d.checkpoint='DOWNLOAD' AND d.bytes=0 AND d.retries=0 AND d.pendingUri IS NULL AND d.nextAttempt<=:now AND d.scheduledAt<=:now AND (d.scheduleNetwork!='WIFI' OR :wifi) AND (d.scheduleNetwork!='UNMETERED' OR :unmetered) AND (d.requiresCharging=0 OR :charging) AND NOT EXISTS (SELECT 1 FROM downloads a WHERE a.batchId=d.batchId AND a.id!=o.id AND (a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','PAUSING') OR (a.playlistPosition<d.playlistPosition AND a.status NOT IN ('COMPLETED','FAILED','CANCELLED','PAUSED')))) ORDER BY d.playlistPosition LIMIT 1")
    abstract suspend fun playlistPreparationCandidate(owner:String,now:Long,wifi:Boolean,unmetered:Boolean,charging:Boolean):DownloadEntity?
    @Query("SELECT COUNT(*) FROM downloads d LEFT JOIN playlist_batches b ON b.id=d.batchId WHERE d.status IN ('QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING') AND (d.batchId IS NULL OR b.state='ACTIVE')") abstract suspend fun remaining(): Int
    @Query("SELECT COUNT(*) FROM downloads WHERE status='INTERRUPTED'") abstract suspend fun interruptedCount(): Int
    // A provider throttle applies to the collection, not just the failed child.
    // Keep explicit pauses, completed outputs and processing checkpoints intact.
    @Query("UPDATE downloads SET status='RETRYING',error='RATE_LIMITED',speed=0,nextAttempt=MAX(nextAttempt,:until) WHERE batchId=:batch AND checkpoint='DOWNLOAD' AND status IN ('QUEUED','RETRYING','RESUMING')")
    abstract suspend fun deferThrottledBatch(batch:String,until:Long):Int
    // A killed owner cannot first turn DOWNLOADING/MERGING/SAVING into
    // INTERRUPTED. Startup must notice those durable rows before a new owner
    // can run recover(); explicit pause and future schedules remain excluded.
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED','PAUSED','SCHEDULED'))") abstract suspend fun recoveryNeeded(): Boolean
    @Query("SELECT * FROM downloads WHERE fingerprint=:fingerprint AND status NOT IN ('COMPLETED','FAILED','CANCELLED') LIMIT 1") abstract suspend fun duplicate(fingerprint: String): DownloadEntity?
    @Query("SELECT id,uri,status,fingerprint,mediaKey FROM downloads WHERE (mediaKey=:key OR urlKey=:urlKey) AND status NOT IN ('FAILED','CANCELLED') ORDER BY createdAt DESC") abstract suspend fun sourceDuplicates(key:String,urlKey:String):List<DownloadMatch>
    @Query("SELECT selection FROM downloads WHERE id=:id") abstract suspend fun savedSelection(id:String):String?
    @Query("SELECT url FROM downloads WHERE id=:id") abstract suspend fun savedSourceUrl(id:String):String?
    @Query("SELECT b.policy FROM playlist_batches b INNER JOIN downloads d ON d.batchId=b.id WHERE d.id=:id") abstract suspend fun requestBatchPolicy(id:String):String?
    @Query("SELECT * FROM downloads WHERE status='SCHEDULED' ORDER BY scheduledAt") abstract suspend fun schedules():List<DownloadEntity>
    @Query("UPDATE downloads SET status='QUEUED',error=NULL WHERE (id=:id OR (:batch=1 AND batchId=:id)) AND status='SCHEDULED' AND scheduledAt<=:now") abstract suspend fun releaseSchedule(id:String,batch:Boolean,now:Long):Int
    @Query("UPDATE downloads SET status=CASE WHEN status IN ('SCHEDULED','PAUSED','INTERRUPTED','WAITING_FOR_NETWORK','RETRYING','RESUMING','QUEUED') THEN CASE WHEN bytes>0 THEN 'RESUMING' ELSE 'QUEUED' END ELSE status END,scheduledAt=0,scheduleNetwork='ANY',requiresCharging=0,nextAttempt=0,error=NULL WHERE (id=:id OR (:batch=1 AND batchId=:id)) AND scheduledAt>0 AND status NOT IN ('COMPLETED','FAILED','CANCELLED')") abstract suspend fun startScheduledNow(id:String,batch:Boolean):Int
    @Query("UPDATE downloads SET mediaKey=:key,urlKey=:urlKey,fingerprint=:fingerprint WHERE id=:id") abstract suspend fun identity(id:String,key:String,urlKey:String,fingerprint:String)
    @Query("UPDATE downloads SET fingerprint=:fingerprint WHERE id=:id") abstract suspend fun fingerprint(id:String,fingerprint:String)
    @Query("SELECT id FROM downloads WHERE status IN ('COMPLETED','CANCELLED')") abstract suspend fun cleanableTransfers():List<String>
    @Insert abstract suspend fun insert(entity: DownloadEntity)
    @Transaction open suspend fun insertRequest(entity:DownloadEntity) {
        require(entity.request!=null&&entity.selection.isEmpty())
        insert(entity)
        event(DownloadEvent(downloadId=entity.id,state="REQUEST_PERSISTED"))
    }
    @Insert abstract suspend fun event(entity: DownloadEvent)
    @Query("UPDATE downloads SET status=:status,error=:error,speed=0 WHERE id=:id AND status!='COMPLETED'") abstract suspend fun state(id: String, status: String, error: String? = null)
    @Query("UPDATE downloads SET status='DOWNLOADING',error=NULL WHERE id=:id AND pendingUri IS NULL AND status IN ('QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING') AND (batchId IS NULL OR EXISTS (SELECT 1 FROM playlist_batches b WHERE b.id=batchId AND b.state='ACTIVE' AND (b.concurrency!=1 OR NOT EXISTS (SELECT 1 FROM downloads a WHERE a.batchId=b.id AND a.id!=:id AND a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','PAUSING')))))") abstract suspend fun claim(id: String): Int
    @Query("UPDATE downloads SET status=:stage WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO')") abstract suspend fun transferStage(id:String,stage:String):Int
    @Query("UPDATE downloads SET status='PROCESSING',checkpoint=CASE WHEN checkpoint='DOWNLOAD' THEN 'DOWNLOADED' ELSE checkpoint END,speed=0 WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO')") abstract suspend fun processing(id: String): Int
    @Query("UPDATE downloads SET checkpoint='VALIDATED' WHERE id=:id AND status IN ('PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING')") abstract suspend fun validated(id:String):Int
    @Query("UPDATE downloads SET checkpoint='MERGED' WHERE id=:id AND status IN ('PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING')") abstract suspend fun merged(id:String):Int
    @Query("UPDATE downloads SET checkpoint='DOWNLOADED' WHERE id=:id AND pendingUri IS NULL AND checkpoint IN ('MERGED','VALIDATED') AND status IN ('PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING')")
    abstract suspend fun reprocessDownloadedTracks(id:String):Int
    @Query("UPDATE downloads SET checkpoint='DOWNLOAD',bytes=:retained,total=NULL,sizeKind='UNKNOWN' WHERE id=:id") abstract suspend fun resetCheckpoint(id:String,retained:Long=0)
    @Query("UPDATE downloads SET status='PAUSED',speed=0 WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED','PAUSED')") abstract suspend fun pauseForUserStop()
    @Query("UPDATE downloads SET status=:stage,speed=0 WHERE id=:id AND status IN ('PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING')") abstract suspend fun stageChange(id: String, stage: String): Int
    @Transaction open suspend fun processingStage(id: String, stage: String): Int { val changed=stageChange(id,stage);if(changed==1) event(DownloadEvent(downloadId=id,state=stage));return changed }
    @Query("SELECT * FROM events WHERE downloadId=:id ORDER BY id") abstract suspend fun events(id:String):List<DownloadEvent>
    @Query("UPDATE downloads SET bytes=:bytes,total=:total,speed=:speed,sizeKind=:kind WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO')") abstract suspend fun progress(id: String, bytes: Long, total: Long?, speed: Long,kind:String="UNKNOWN")
    @Query("UPDATE downloads SET retries=retries+1,nextAttempt=:next,status='RETRYING',checkpoint=CASE WHEN :error='SOURCE_CORRUPT' THEN 'DOWNLOAD' ELSE checkpoint END,error=:error,speed=0 WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','INTERRUPTED')") abstract suspend fun retryRow(id: String, next: Long, error: String): Int
    @Transaction open suspend fun retryLater(id:String,next:Long,error:String):Int {
        val changed=retryRow(id,next,error)
        if(changed==1) event(DownloadEvent(downloadId=id,state="RETRYING",error=error))
        return changed
    }
    @Query("SELECT error FROM events WHERE downloadId=:id AND state='RETRYING' ORDER BY id DESC LIMIT 1") abstract suspend fun lastRetryError(id:String):String?
    @Query("UPDATE downloads SET status='FAILED',error=:error,speed=0 WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','INTERRUPTED')") abstract suspend fun fail(id: String, error: String): Int
    @Query("UPDATE downloads SET status='FAILED',error=:error,speed=0 WHERE id=:id AND pendingUri IS NOT NULL AND status IN ('INTERRUPTED','QUEUED','RESUMING','RETRYING','WAITING_FOR_NETWORK')") abstract suspend fun publicationBlocked(id:String,error:String):Int
    @Query("UPDATE downloads SET status=CASE WHEN status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING') THEN 'PAUSING' ELSE 'PAUSED' END,speed=0 WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING')") abstract suspend fun pause(id: String): Int
    @Query("UPDATE downloads SET status='CANCELLED',speed=0 WHERE id=:id AND status NOT IN ('COMPLETED','CANCELLED')") protected abstract suspend fun cancelRow(id: String): Int
    @Transaction open suspend fun cancel(id:String):Int {
        val row=get(id) ?: return 0
        val changed=cancelRow(id)
        if(changed==1) archive(DownloadTrashEntry(row.id,row.url,row.title,"CANCELLED"))
        return changed
    }
    @Query("UPDATE downloads SET status='WAITING_FOR_NETWORK',error=:reason,speed=0 WHERE id=:id AND checkpoint='DOWNLOAD' AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO')") abstract suspend fun waitForNetwork(id: String,reason:String="NETWORK_UNAVAILABLE"): Int
    @Query("UPDATE downloads SET status='WAITING_FOR_NETWORK',error=:reason,speed=0 WHERE checkpoint='DOWNLOAD' AND status IN ('QUEUED','RESUMING','RETRYING')") abstract suspend fun pendingWaiting(reason:String):Int
    @Query("UPDATE downloads SET status=CASE WHEN bytes>0 THEN 'RESUMING' ELSE 'QUEUED' END,error=NULL WHERE id=:id AND status='WAITING_FOR_NETWORK' AND pendingUri IS NULL")
    abstract suspend fun networkAvailable(id:String):Int
    @Query("UPDATE downloads SET status=CASE WHEN status='PAUSING' THEN 'PAUSED' ELSE 'INTERRUPTED' END,speed=0 WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','PAUSING')") abstract suspend fun interrupt(id: String): Int
    @Query("UPDATE downloads SET pendingUri=:uri WHERE id=:id") abstract suspend fun pending(id: String, uri: String?)
    @Query("UPDATE downloads SET uri=:uri,pendingUri=NULL,status='COMPLETED',notificationPending=1,finishedAt=:now,outputSize=:size,error=NULL,speed=0 WHERE id=:id AND status IN ('PROCESSING','SAVING')") abstract suspend fun complete(id: String, uri: String, size: Long, now: Long): Int
    @Query("UPDATE downloads SET uri=:uri,pendingUri=NULL,status='COMPLETED',notificationPending=1,finishedAt=:now,outputSize=:size,error=NULL,speed=0 WHERE id=:id AND pendingUri=:uri AND status!='COMPLETED'") abstract suspend fun recoverCompletedPublicationRow(id:String,uri:String,size:Long,now:Long):Int
    @Transaction open suspend fun recoverCompletedPublication(id:String,uri:String,size:Long,now:Long):Int {
        val changed=recoverCompletedPublicationRow(id,uri,size,now)
        if(changed==1) event(DownloadEvent(downloadId=id,state="COMPLETED"))
        return changed
    }
    @Query("SELECT * FROM downloads WHERE status='COMPLETED' AND notificationPending=1") abstract suspend fun pendingNotifications():List<DownloadEntity>
    @Query("UPDATE downloads SET notificationPending=0 WHERE id=:id AND status='COMPLETED'") abstract suspend fun notificationDelivered(id:String)
    @Query("UPDATE downloads SET checkpoint=CASE WHEN status='FAILED' AND error='OUTPUT_VALIDATION_FAILED' AND checkpoint IN ('MERGED','VALIDATED') AND pendingUri IS NULL THEN 'DOWNLOADED' ELSE checkpoint END,status=CASE WHEN bytes>0 THEN 'RESUMING' ELSE 'QUEUED' END,error=NULL,nextAttempt=0,retries=0 WHERE id=:id AND status IN ('PAUSED','INTERRUPTED','FAILED')") abstract suspend fun resume(id: String)
    @Query("UPDATE downloads SET status=CASE WHEN bytes>0 THEN 'RESUMING' ELSE 'QUEUED' END,error=NULL,nextAttempt=0,retries=0 WHERE id=:id AND status IN ('QUEUED','WAITING_FOR_NETWORK','RETRYING','RESUMING')") abstract suspend fun resetPendingForUser(id:String):Int
    @Query("UPDATE downloads SET status=CASE WHEN status='PAUSING' THEN 'PAUSED' ELSE 'INTERRUPTED' END,speed=0 WHERE status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING','PAUSING')") abstract suspend fun recover()
    @Query("UPDATE downloads SET status='QUEUED',nextAttempt=0 WHERE status='INTERRUPTED' AND pendingUri IS NULL") abstract suspend fun requeueInterrupted()
    @Query("DELETE FROM downloads WHERE id=:id AND status IN ('COMPLETED','FAILED','CANCELLED','PAUSED','INTERRUPTED')") abstract suspend fun delete(id: String)
    @Query("UPDATE downloads SET filename=:filename WHERE id=:id") abstract suspend fun rename(id: String, filename: String)
    @Query("UPDATE downloads SET filename=:filename,uri=:uri WHERE id=:id AND status='COMPLETED'") abstract suspend fun renamedFile(id: String, filename: String, uri: String)
    @Query("UPDATE downloads SET filename=:name,uri=:newUri WHERE uri=:old AND status='COMPLETED'") abstract suspend fun fileRelinked(old:String,newUri:String,name:String)
    @Query("UPDATE downloads SET uri=NULL WHERE uri=:uri AND status='COMPLETED'") abstract suspend fun fileDeleted(uri:String)
    @Query("UPDATE downloads SET selection=:selection,total=:total WHERE id=:id AND status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO')") abstract suspend fun refreshSelection(id: String, selection: String, total: Long?): Int
    @Query("UPDATE downloads SET status='WAITING_FOR_NETWORK',error=:reason,speed=0 WHERE id=:id AND status IN ('QUEUED','RESUMING','WAITING_FOR_NETWORK','RETRYING')") abstract suspend fun submissionWaiting(id:String,reason:String):Int
    @Query("UPDATE downloads SET error=:reason WHERE id=:id AND status IN ('QUEUED','RESUMING','WAITING_FOR_NETWORK','RETRYING','SCHEDULED')") abstract suspend fun submissionIssue(id:String,reason:String):Int
    @Query("UPDATE downloads SET error=NULL WHERE id=:id AND error IN ('START_DELAYED','SCHEDULING_FAILED') AND status IN ('QUEUED','RESUMING','WAITING_FOR_NETWORK','RETRYING','SCHEDULED')") abstract suspend fun clearSubmissionIssue(id:String):Int
    @Query("UPDATE downloads SET selection=:selection,filename=:filename,total=:total,fingerprint=:fingerprint,checkpoint='DOWNLOAD',status='QUEUED',error=NULL,retries=0,nextAttempt=0 WHERE id=:id AND status='FAILED'") abstract suspend fun useAlternative(id:String,selection:String,filename:String,total:Long?,fingerprint:String):Int
    @Query("DELETE FROM events WHERE downloadId=:id") abstract suspend fun deleteEvents(id: String)
    @Transaction open suspend fun removeHistory(id: String) {
        val row=get(id)
        if(row!=null) {
            if(row.status !in listOf("COMPLETED","FAILED","CANCELLED","PAUSED","INTERRUPTED")) return
            archive(DownloadTrashEntry(row.id,row.url,row.title,"REMOVED"))
        }
        delete(id)
        if(get(id)==null) deleteEvents(id)
    }
    @Transaction open suspend fun transition(id: String, state: String, error: String? = null) { state(id, state, error); event(DownloadEvent(downloadId = id, state = state, error = error)) }
    @Query("SELECT * FROM playlist_batches WHERE id=:id") abstract suspend fun batch(id:String):PlaylistBatch?
    @Query("SELECT * FROM playlist_batches WHERE submissionKey=:key") abstract suspend fun submittedBatch(key:String):PlaylistBatch?
    @Query("SELECT * FROM playlist_batches ORDER BY createdAt DESC") abstract fun batches():Flow<List<PlaylistBatch>>
    @Query("SELECT * FROM downloads WHERE batchId=:id ORDER BY playlistPosition") abstract suspend fun batchItems(id:String):List<DownloadEntity>
    @Query("SELECT * FROM downloads WHERE batchId=:id ORDER BY playlistPosition ASC, createdAt ASC") abstract fun batchItemsFlow(id:String):Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE uri=:uri LIMIT 1") abstract suspend fun byUri(uri:String):DownloadEntity?
    @Query("""SELECT b.*,COUNT(d.id) selected,
        COALESCE(SUM(d.status='COMPLETED'),0) completed,
        COALESCE(SUM(d.status='FAILED'),0) failed,
        COALESCE(SUM(d.status='CANCELLED'),0) cancelled,
        COALESCE(SUM(d.status='SCHEDULED'),0) scheduled,
        COALESCE(MAX(d.scheduledAt),0) scheduledAt,
        COALESCE(MAX(d.scheduleNetwork),'ANY') scheduleNetwork,
        COALESCE(MAX(d.requiresCharging),0) requiresCharging,
        COALESCE(SUM(d.status IN ('PAUSED','PAUSING')),0) paused,
        COALESCE(SUM(d.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING')),0) active,
        COALESCE(SUM(d.bytes),0) transferred,
        COALESCE(SUM(CAST(d.total AS REAL)),0) expectedSum,
        COALESCE(SUM(d.total>0 AND d.sizeKind!='UNKNOWN'),0) knownSizes,
        COALESCE(SUM(d.sizeKind='ESTIMATED'),0) estimatedSizes,
        COALESCE(SUM(d.checkpoint!='DOWNLOAD' OR d.status='COMPLETED'),0) networkDone,
        (SELECT a.title FROM downloads a WHERE a.batchId=b.id AND a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING') ORDER BY a.playlistPosition LIMIT 1) currentTitle,
        (SELECT CASE WHEN a.selection='' AND a.status='DOWNLOADING' THEN 'ANALYZING' ELSE a.status END FROM downloads a WHERE a.batchId=b.id AND a.status IN ('DOWNLOADING','DOWNLOADING_VIDEO','DOWNLOADING_AUDIO','PROCESSING','WAITING_FOR_PROCESSOR','MERGING','CONVERTING','VALIDATING','SAVING') ORDER BY a.playlistPosition LIMIT 1) currentState
        FROM playlist_batches b LEFT JOIN downloads d ON d.batchId=b.id GROUP BY b.id ORDER BY b.createdAt DESC""") abstract fun batchSummaries():Flow<List<BatchSummary>>
    @Insert abstract suspend fun insertBatch(batch:PlaylistBatch)
    @Insert abstract suspend fun insertItems(items:List<DownloadEntity>)
    @Transaction open suspend fun submitBatch(batch:PlaylistBatch,items:List<DownloadEntity>):String {
        submittedBatch(batch.submissionKey)?.let {return it.id}
        require(items.isNotEmpty()&&items.size<=5000&&items.all {it.batchId==batch.id})
        insertBatch(batch);insertItems(items);return batch.id
    }
    @Query("UPDATE playlist_batches SET state=:state WHERE id=:id AND (state!='CANCELLED' OR :state='CANCELLED')") abstract suspend fun batchState(id:String,state:String)
    @Transaction open suspend fun settleCancelledBatchRemoval(id:String):List<DownloadEntity>? {
        val currentBatch=batch(id) ?: return null
        val items=batchItems(id)
        if(!com.zdownloader.app.domain.PlaylistRemovalPolicy.allowed(currentBatch.state,items.map {it.status})) return null
        batchState(id,"CANCELLED")
        return items
    }
    @Query("UPDATE downloads SET batchId=NULL,playlistPosition=0 WHERE batchId=:id AND status='COMPLETED'") protected abstract suspend fun detachCompletedBatchItems(id:String)
    @Transaction open suspend fun removeCancelledBatch(id:String):Boolean {
        val currentBatch=batch(id) ?: return true
        val items=batchItems(id)
        if(!com.zdownloader.app.domain.PlaylistRemovalPolicy.allowed(currentBatch.state,items.map {it.status}) || items.any {it.pendingUri!=null}) return false
        for(item in items.filter {it.status=="CANCELLED"}) {
            delete(item.id);deleteEvents(item.id);forgetTrash(item.id)
        }
        detachCompletedBatchItems(id)
        removeEmptyBatch(id)
        return batch(id)==null
    }
    @Query("DELETE FROM playlist_batches WHERE id=:id AND NOT EXISTS (SELECT 1 FROM downloads WHERE batchId=:id)") abstract suspend fun removeEmptyBatch(id:String)
    @Query("UPDATE downloads SET priority=:priority WHERE id=:id") abstract suspend fun priority(id:String,priority:Int)
    @Query("UPDATE downloads SET selection=:selection WHERE id=:id AND batchId IS NOT NULL AND status='COMPLETED'") abstract suspend fun compactBatchHistory(id:String,selection:String)
    @Query("UPDATE downloads SET selection='',bytes=0,total=NULL,sizeKind='UNKNOWN' WHERE id=:id AND batchId IS NOT NULL AND checkpoint='DOWNLOAD' AND status='FAILED'") abstract suspend fun resetFailedPlaylistIntent(id:String)
    @Query("UPDATE downloads SET title=:title,source=:source,thumbnail=:thumbnail,duration=:duration,selection=:selection,filename=:filename,total=:total,sizeKind=:kind WHERE id=:id AND status='DOWNLOADING'") abstract suspend fun resolvePlaylistItem(id:String,title:String,source:String,thumbnail:String?,duration:Double?,selection:String,filename:String,total:Long?,kind:String="UNKNOWN"):Int
}
@Database(entities = [DownloadEntity::class, DownloadEvent::class,DownloadTrashEntry::class,PlaylistBatch::class,com.zdownloader.app.playback.PlaybackFavorite::class,com.zdownloader.app.playback.PlaybackList::class,com.zdownloader.app.playback.PlaybackListItem::class], version = 8, exportSchema = true)
abstract class ZDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun playbackLibrary():com.zdownloader.app.playback.PlaybackLibraryDao
    companion object {
        val MIGRATION_7_8=object:androidx.room.migration.Migration(7,8) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS download_trash (id TEXT NOT NULL PRIMARY KEY,url TEXT NOT NULL,title TEXT NOT NULL,reason TEXT NOT NULL,archivedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_trash_archivedAt ON download_trash(archivedAt)")
                // Existing cancelled links can be recovered; records already deleted cannot.
                db.execSQL("INSERT OR IGNORE INTO download_trash(id,url,title,reason,archivedAt) SELECT id,url,title,'CANCELLED',createdAt FROM downloads WHERE status='CANCELLED'")
            }
        }
        val MIGRATION_6_7=object:androidx.room.migration.Migration(6,7) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN request TEXT")
            }
        }
        val MIGRATION_5_6=object:androidx.room.migration.Migration(5,6) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN mediaKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN urlKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN scheduleNetwork TEXT NOT NULL DEFAULT 'ANY'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN requiresCharging INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_mediaKey ON downloads(mediaKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_urlKey ON downloads(urlKey)")
                db.query("SELECT id,url,fingerprint FROM downloads").use {c->while(c.moveToNext()) {
                    val fingerprint=c.getString(2)
                    val key=if(fingerprint.matches(Regex("v2:[0-9a-f]{64}:[0-9a-f]{64}"))) fingerprint.substringBeforeLast(':') else "url:"+com.zdownloader.app.domain.MediaIdentity.urlKey(c.getString(1))
                    db.execSQL("UPDATE downloads SET mediaKey=?,urlKey=? WHERE id=?",arrayOf(key,com.zdownloader.app.domain.MediaIdentity.urlKey(c.getString(1)),c.getString(0)))
                }}
            }
        }
        val MIGRATION_4_5=object:androidx.room.migration.Migration(4,5) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_favorites (uri TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,video INTEGER NOT NULL,mime TEXT,artist TEXT,album TEXT,artwork TEXT,duration INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_lists (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_list_items (playlistId TEXT NOT NULL,uri TEXT NOT NULL,position INTEGER NOT NULL,title TEXT NOT NULL,video INTEGER NOT NULL,mime TEXT,artist TEXT,album TEXT,artwork TEXT,duration INTEGER NOT NULL,PRIMARY KEY(playlistId,uri),FOREIGN KEY(playlistId) REFERENCES playback_lists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_list_items_playlistId_position ON playback_list_items(playlistId,position)")
            }
        }
        val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN sizeKind TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE playlist_batches ADD COLUMN concurrency INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE playlist_batches ADD COLUMN thumbnail TEXT")
                db.execSQL("UPDATE downloads SET total=bytes,sizeKind='EXACT' WHERE batchId IS NOT NULL AND status='COMPLETED' AND bytes>0")
            }
        }
        val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_batches (id TEXT NOT NULL PRIMARY KEY,submissionKey TEXT NOT NULL,url TEXT NOT NULL,title TEXT NOT NULL,policy TEXT NOT NULL,createdAt INTEGER NOT NULL,state TEXT NOT NULL,numbered INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_batches_submissionKey ON playlist_batches(submissionKey)")
                db.execSQL("ALTER TABLE downloads ADD COLUMN batchId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN playlistPosition INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN relativeFolder TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_batchId ON downloads(batchId)")
            }
        }
        val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN checkpoint TEXT NOT NULL DEFAULT 'DOWNLOAD'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN notificationPending INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
