package com.zdownloader.app.data

import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.QueueCoordinator
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Singleton class DownloadRepository @Inject constructor(val dao: DownloadDao, private val settings: SettingsRepository, private val coordinator: QueueCoordinator,private val storage: StorageRepository,private val notifications:com.zdownloader.app.background.DownloadNotifications,private val files:MediaFiles) {
    val downloads = dao.observe()
    val completed = dao.completed().distinctUntilChanged()
    val recent = dao.recent().map {rows->rows.map {it.copy(bytes=0,total=null,speed=0)}}.distinctUntilChanged()
    private val createLock = Mutex()
    internal suspend fun <T> serializeSubmission(block:suspend ()->T):T=createLock.withLock {block()}
    private suspend fun legacyDefaultMatches(id:String,storedFingerprint:String,media:Media,selection:Selection):Boolean {
        val representationMatches=if(storedFingerprint.startsWith("v2:")) {
            storedFingerprint.substringAfterLast(':')==RequestFingerprint.create(media,selection.copy(creator=null)).substringAfterLast(':')
        } else dao.savedSourceUrl(id)?.let {storedFingerprint==RequestFingerprint.legacy(it,selection)}==true
        if(!representationMatches) return false
        // The stored fingerprint already proves the historical track/container
        // choice. Older payloads may omit format_id, so parsing them into today's
        // Format defaults must not fabricate a different identity during reuse.
        val old=runCatching {dao.savedSelection(id)?.let {org.json.JSONObject(it)}}.getOrNull() ?: return false
        return old.optBoolean("videoOnly")==selection.videoOnly&&
            old.optBoolean("embedMetadata",true)==selection.embedMetadata&&
            old.optJSONObject("subtitle")?.optString("language")==selection.subtitle?.language&&
            old.text("creator")==null&&selection.creator==media.uploader?.take(512)
    }
    suspend fun duplicateReview(media:Media)=reviewDuplicates(media,files)
    suspend fun createRequest(id:String,url:String,request:DownloadRequest,destination:String?,plan:DownloadPlan?=null,allowAgain:Boolean=false):String=createLock.withLock {
        if(!runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)) throw MediaException(ErrorCode.INTERNAL_ENGINE_ERROR)
        val normalized=UrlPolicy.normalize(url)
        val urlKey=MediaIdentity.urlKey(normalized)
        // Acknowledgement retries belong to the same operation, not a second
        // user request. Validate identity before reviewing other duplicate rows.
        dao.get(id)?.let {
            if(it.url!=normalized||it.request!=request.json()) throw MediaException(ErrorCode.INTERNAL_ENGINE_ERROR)
            if(it.status=="CANCELLED") throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
            return@withLock it.id
        }
        if(!allowAgain) {
            val candidates=dao.sourceDuplicates(urlKey,urlKey)
            val completed=mutableListOf<DownloadMatch>()
            for(row in candidates.filter {it.status=="COMPLETED"}) if(files.accessible(row.uri)) completed+=row
            val review=DuplicateReview(completed,candidates.filter {it.status!="COMPLETED"})
            if(review.found) throw DuplicateDownloadException(review)
        }
        val identity=listOf(urlKey,request.height,request.audioOnly,request.output,request.bitrate,
             destination,request.template,plan?.at,plan?.network,plan?.charging).joinToString("|")+
            (if(request.exact) "|exact" else if(!request.audioOnly&&request.height>0) "|bounded-v2" else "")
        val fingerprint="request:v1:"+MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it)}
        dao.duplicate(fingerprint)?.let {return@withLock it.id}
        val host=java.net.URI(normalized).host.removePrefix("www.")
        dao.insertRequest(DownloadEntity(id,normalized,host,host,null,null,"",FileNames.sanitize(host),destination,fingerprint,
            status=if(plan==null) "QUEUED" else "SCHEDULED",urlKey=urlKey,request=request.json(),
            scheduledAt=plan?.at ?: 0,scheduleNetwork=plan?.network?.name ?: if(request.wifiOnly) "WIFI" else "ANY",requiresCharging=plan?.charging ?: false))
        id
    }
    suspend fun create(media: Media, selection: Selection, title: String,allowCompleted:Boolean=true,plan:DownloadPlan?=null): String = createLock.withLock {
        val prefs = settings.current()
        val fingerprint = RequestFingerprint.create(media,selection)
        if(!allowCompleted) {val review=duplicateReview(media);if(review.found) throw DuplicateDownloadException(review)}
        val direct=dao.duplicate(fingerprint)
        val legacy=if(direct==null) dao.duplicate(RequestFingerprint.legacy(media.url,selection))?.takeIf {legacyDefaultMatches(it.id,it.fingerprint,media,selection)} else null
        (direct ?: legacy)?.let {dao.identity(it.id,MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url),fingerprint);return@withLock it.id}
        for(match in dao.sourceDuplicates(MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url))) {
            if(match.status!="COMPLETED"&&(match.fingerprint.startsWith("v2:")&&match.fingerprint.substringAfterLast(':')==fingerprint.substringAfterLast(':')||legacyDefaultMatches(match.id,match.fingerprint,media,selection))) return@withLock match.id
        }
        val serialized=selection.json()
        if(serialized.toByteArray(Charsets.UTF_8).size>1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        val id = UUID.randomUUID().toString()
        dao.insert(DownloadEntity(id,media.url,title,media.source,media.thumbnail,media.duration,serialized,FileNames.output(title,selection.output,prefs.template),prefs.destination,fingerprint,status=if(plan==null) "QUEUED" else "SCHEDULED",total=selection.size,mediaKey=MediaIdentity.sourceKey(media),urlKey=MediaIdentity.urlKey(media.url),scheduledAt=plan?.at ?: 0,scheduleNetwork=plan?.network?.name ?: "ANY",requiresCharging=plan?.charging ?: false))
        id
    }
    suspend fun pause(id: String) {
        if(dao.pause(id) == 1) {
            coordinator.stop(id)
            if(!coordinator.active(id)) {dao.interrupt(id);notifications.dismiss(id)}
        }
    }
    suspend fun resume(id: String) = dao.resume(id)
    suspend fun retryInDefaultDestination(id:String) {
        if(android.os.Build.VERSION.SDK_INT<29||dao.retryInDefaultDestination(id)!=1)
            throw MediaException(ErrorCode.STORAGE_DESTINATION_UNAVAILABLE)
    }
    /** A confirmed submit may attach to an older request whose pause is still settling. */
    suspend fun prepareForSubmission(id:String):DownloadEntity {
        val before=dao.get(id) ?: throw MediaException(ErrorCode.INTERNAL_ENGINE_ERROR)
        if(before.status=="FAILED") dao.resetFailedPlaylistIntent(id)
        if(before.status=="PAUSING") {
            coordinator.stopAndAwait(id)
            if(!coordinator.active(id)) dao.interrupt(id)
        }
        val settled=dao.get(id) ?: throw MediaException(ErrorCode.INTERNAL_ENGINE_ERROR)
        if(settled.status in listOf("PAUSED","INTERRUPTED","FAILED")) dao.resume(id)
        dao.resetPendingForUser(id)
        val ready=dao.get(id) ?: throw MediaException(ErrorCode.INTERNAL_ENGINE_ERROR)
        // Cancellation/newer active pause must win over a late submit callback.
        if(ready.status in listOf("CANCELLED","PAUSING","PAUSED")) throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
        return ready
    }
    suspend fun useMkv(id:String) {
        val entity=dao.get(id) ?: return
        val selection=Selection.parse(entity.selection)
        if(entity.status!="FAILED" || selection.audioOnly || selection.output!="mp4") return
        val alternative=selection.copy(output="mkv")
        val fingerprint = RequestFingerprint.alternative(entity.fingerprint,entity.url,alternative)
        dao.useAlternative(id,alternative.json(),entity.filename.substringBeforeLast('.')+".mkv",alternative.size,fingerprint)
    }
    suspend fun cancel(id: String) { if(dao.cancel(id) == 1) { coordinator.stop(id);notifications.dismiss(id); if(!coordinator.active(id)) {storage.cleanupPending(id);storage.removeTemporary(id)} } }
    suspend fun remove(id: String) {
        val entity = dao.get(id) ?: return
        if(entity.status !in listOf("COMPLETED","FAILED","CANCELLED","PAUSED","INTERRUPTED")) return
        coordinator.stopAndAwait(id)
        // Re-validate after the stop boundary: a concurrent resume may have moved the
        // row to an active state while we waited. Deleting files on a stale snapshot
        // would orphan a live RESUMING entry from its partial.
        val current = dao.get(id) ?: return
        if(current.status !in listOf("COMPLETED","FAILED","CANCELLED","PAUSED","INTERRUPTED")) return
        storage.cleanupPending(id)
        storage.removeTemporary(id)
        dao.removeHistory(id)
    }
    /** Cleanup only; collection removal owns the later atomic history transaction. */
    suspend fun prepareCancelledRemoval(id:String):Boolean {
        if(dao.get(id)?.status!="CANCELLED") return false
        coordinator.stopAndAwait(id)
        if(dao.get(id)?.status!="CANCELLED") return false
        storage.cleanupPending(id)
        storage.removeTemporary(id)
        return dao.get(id)?.status=="CANCELLED"
    }
}
