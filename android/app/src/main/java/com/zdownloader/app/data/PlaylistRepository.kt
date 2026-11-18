package com.zdownloader.app.data

import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.QueueCoordinator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PlaylistDuplicates(val completed:Set<String> = emptySet(),val active:Set<String> = emptySet(),val matchingCompleted:Set<String> = emptySet(),val matchingActive:Set<String> = emptySet())
@Singleton class PlaylistRepository @Inject constructor(private val dao:DownloadDao,private val settings:SettingsRepository,private val downloads:DownloadRepository,private val coordinator:QueueCoordinator,private val files:MediaFiles) {
    val summaries=dao.batchSummaries()
    suspend fun duplicates(entries:List<PlaylistEntry>,policy:PlaylistPolicy=PlaylistPolicy()):PlaylistDuplicates {
        val completed=mutableSetOf<String>();val active=mutableSetOf<String>()
        val matchingCompleted=mutableSetOf<String>();val matchingActive=mutableSetOf<String>()
        val checked=mutableMapOf<String,List<Boolean>>()
        suspend fun matches(row:DownloadMatch,entry:PlaylistEntry):Boolean {
            val value=dao.savedSelection(row.id).orEmpty()
            if(value.isBlank()) return dao.requestBatchPolicy(row.id)==policy.json()
            val selection=runCatching {Selection.parse(value)}.getOrNull() ?: return false
            if(policy.audio) return selection.audioOnly&&policy.output=="mp3"&&selection.output=="mp3"&&selection.convertAudio&&selection.bitrate==policy.bitrate
            if(selection.audioOnly||selection.videoOnly) return false
            val hint=entry.sizeHint?.takeIf {it.video}?.quality
            val expected=when(policy.fallback) {
                QualityFallback.EXACT->policy.height
                QualityFallback.AT_MOST->hint?.takeIf {it<=policy.height} ?: policy.height
                QualityFallback.BEST->hint ?: return false
            }
            return qualityLabel(selection.primary)==expected
        }
        for(entry in entries.filter {it.selectable}) {
            val identity=MediaIdentity.playlistKey(entry)
            val found=checked[identity] ?: run {
                val rows=dao.sourceDuplicates(identity,MediaIdentity.urlKey(entry.url!!))
                var saved=false;var sameSaved=false;var sameActive=false
                for(row in rows) {
                    if(row.status=="COMPLETED") {
                        if(files.accessible(row.uri)) {saved=true;if(!sameSaved&&matches(row,entry)) sameSaved=true}
                    } else if(!sameActive&&matches(row,entry)) sameActive=true
                }
                listOf(saved,rows.any {it.status!="COMPLETED"},sameSaved,sameActive).also {checked[identity]=it}
            }
            if(found[0]) completed+=entry.key;if(found[1]) active+=entry.key
            if(found[2]) matchingCompleted+=entry.key;if(found[3]) matchingActive+=entry.key
        }
        return PlaylistDuplicates(completed,active,matchingCompleted,matchingActive)
    }
    suspend fun submit(key:String,page:PlaylistPage,entries:List<PlaylistEntry>,policy:PlaylistPolicy,numbered:Boolean,concurrency:Int,skipExisting:Boolean=false,plan:DownloadPlan?=null):String = downloads.serializeSubmission {submitLocked(key,page,entries,policy,numbered,concurrency,skipExisting,plan)}
    private suspend fun submitLocked(key:String,page:PlaylistPage,entries:List<PlaylistEntry>,policy:PlaylistPolicy,numbered:Boolean,concurrency:Int,skipExisting:Boolean,plan:DownloadPlan?):String {
        require(concurrency in 0..5)
        require(entries.size in 1..5000&&entries.all {it.selectable}&&entries.map {it.key}.distinct().size==entries.size)
        dao.submittedBatch(key)?.let {return it.id}
        val prefs=settings.current()
        val batch=PlaylistBatch(UUID.randomUUID().toString(),key,UrlPolicy.normalize(page.url),page.title ?: "MediaFlow",policy.json(),numbered=numbered,concurrency=concurrency,thumbnail=page.thumbnail)
        val folder=FileNames.sanitize(batch.title)
        val duplicate=if(skipExisting) duplicates(entries,policy) else PlaylistDuplicates()
        val chosen=entries.filter {it.key !in duplicate.matchingCompleted&&it.key !in duplicate.matchingActive}.let {if(skipExisting) it.distinctBy(MediaIdentity::playlistKey) else it}
        if(chosen.isEmpty()) throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
        val items=chosen.map {entry->
            val id=UUID.randomUUID().toString()
            val size=policy.expected(entry)
            DownloadEntity(id,UrlPolicy.normalize(entry.url!!),entry.title ?: "${entry.position}","",entry.thumbnail,entry.duration,"", "",prefs.destination,
                "playlist:${batch.id}:${entry.key}",status=if(plan==null) "QUEUED" else "SCHEDULED",createdAt=batch.createdAt,batchId=batch.id,playlistPosition=entry.position,relativeFolder=folder,total=size?.bytes,sizeKind=size?.let {if(it.estimated) "ESTIMATED" else "EXACT"} ?: "UNKNOWN",mediaKey=MediaIdentity.playlistKey(entry),urlKey=MediaIdentity.urlKey(entry.url),scheduledAt=plan?.at ?: 0,scheduleNetwork=plan?.network?.name ?: "ANY",requiresCharging=plan?.charging ?: false)
        }
        val id=dao.submitBatch(batch,items)
        return id
    }
    suspend fun pause(id:String) {
        if(dao.batch(id)?.state=="CANCELLED") return
        dao.batchState(id,"PAUSED")
        for(item in dao.batchItems(id)) if(item.status !in setOf("COMPLETED","FAILED","CANCELLED")) downloads.pause(item.id)
        coordinator.signalSubmission()
    }
    suspend fun resume(id:String,retryFailed:Boolean=false) {
        val batch=dao.batch(id) ?: return
        if(batch.state=="CANCELLED") return
        for(item in dao.batchItems(id)) if(item.status in (if(retryFailed) setOf("FAILED") else setOf("PAUSED","PAUSING","INTERRUPTED"))) {
            downloads.prepareForSubmission(item.id)
        }
        dao.batchState(id,"ACTIVE")
        coordinator.signalSubmission()
    }
    suspend fun cancel(id:String) {
        dao.batchState(id,"CANCELLED")
        for(item in dao.batchItems(id)) if(item.status!="COMPLETED") downloads.cancel(item.id)
        coordinator.signalSubmission()
    }
    suspend fun removeCancelled(id:String):Boolean {
        if(dao.batch(id)==null) return true
        val items=dao.settleCancelledBatchRemoval(id) ?: return false
        for(item in items.filter {it.status=="CANCELLED"}) if(!downloads.prepareCancelledRemoval(item.id)) return false
        return dao.removeCancelledBatch(id)
    }
}
