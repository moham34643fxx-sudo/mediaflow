package com.zdownloader.app.ui

import com.zdownloader.app.background.Scheduler
import com.zdownloader.app.data.PlaylistRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.engine.Extractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class PlaylistUi(val visible:Boolean=false,val ambiguous:Boolean=false,val loading:Boolean=false,val saving:Boolean=false,
    val page:PlaylistPage?=null,val selected:Set<String> = emptySet(),val error:ErrorCode?=null,val queued:String?=null,val persisted:Boolean=false,
    val duplicates:com.zdownloader.app.data.PlaylistDuplicates=com.zdownloader.app.data.PlaylistDuplicates(),val checkingDuplicates:Boolean=false,
    val initialPolicy:PlaylistPolicy=PlaylistPolicy(),val sessionId:String="",val initialPlan:DownloadPlan?=null,
    val sizePolicy:PlaylistPolicy=initialPolicy,val sizes:Map<String,SizeForecast> = emptyMap(),val sizing:Boolean=false)

/** UI discovery is cancellable. Only the finite confirmed intent is handed to durable work. */
class PlaylistController(private val scope:CoroutineScope,private val extractor:Extractor,private val repository:PlaylistRepository,private val scheduler:Scheduler,private val planned:com.zdownloader.app.background.PlannedDownloads) {
    private val mutable=MutableStateFlow(PlaylistUi())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private var request=""
    private var url=""
    private var key=UUID.randomUUID().toString()
    private var duplicateJob:Job?=null
    private var duplicatePolicy=PlaylistPolicy()
    private var sizeJob:Job?=null
    private var sizeRequest=0
    fun estimateSizes(policy:PlaylistPolicy) {
        if(state.value.saving||state.value.persisted) return
        sizeJob?.cancel()
        val generation=++sizeRequest
        val session=state.value.sessionId
        val entries=state.value.page?.items.orEmpty().filter {it.selectable}
        val previews=extractor.sizePreviews
        mutable.update {it.copy(sizePolicy=policy,sizes=entries.associate {entry->entry.key to previews.forecast(entry.url!!,policy,entry.duration)},sizing=entries.isNotEmpty())}
        sizeJob=scope.launch {
            try {
                for(entry in entries) {
                    ensureActive()
                    if(previews.forecast(entry.url!!,policy,entry.duration).output==null&&previews.cached(entry.url)==null) {
                        try {previews.inspect(entry.url)}
                        catch(e:CancellationException) {throw e}
                        catch(_:Exception) { /* Keep unknown visible; never invent a size. */ }
                    }
                    if(state.value.sessionId==session&&sizeRequest==generation) mutable.update {it.copy(sizes=it.sizes+(entry.key to previews.forecast(entry.url,policy,entry.duration)))}
                }
            } finally {if(state.value.sessionId==session&&sizeRequest==generation) mutable.update {it.copy(sizing=false)}}
        }
    }
    fun reviewPolicy(policy:PlaylistPolicy) {if(policy!=duplicatePolicy) {duplicatePolicy=policy;checkDuplicates()}}
    private fun checkDuplicates() {
        duplicateJob?.cancel();val page=state.value.page ?: return
        mutable.update {it.copy(checkingDuplicates=true)}
        val policy=duplicatePolicy
        duplicateJob=scope.launch {try {delay(200);val found=repository.duplicates(page.items,policy);if(state.value.page==page) mutable.update {it.copy(duplicates=found,checkingDuplicates=false)}} catch(e:CancellationException) {throw e} catch(_:Exception) {mutable.update {it.copy(checkingDuplicates=false,error=ErrorCode.STORAGE_PERMISSION_ERROR)}}}
    }
    fun ask(value:String,policy:PlaylistPolicy=PlaylistPolicy(),plan:DownloadPlan?=null) {close();url=value;key=UUID.randomUUID().toString();duplicatePolicy=policy;mutable.value=PlaylistUi(visible=true,ambiguous=true,initialPolicy=policy,sessionId=key,initialPlan=plan)}
    fun open(value:String=url,policy:PlaylistPolicy=duplicatePolicy,plan:DownloadPlan?=state.value.initialPlan) {
        if(state.value.saving) return
        close();url=value;key=UUID.randomUUID().toString();duplicatePolicy=policy;mutable.value=PlaylistUi(visible=true,initialPolicy=policy,sessionId=key,initialPlan=plan)
        loadMore()
    }
    fun fromLinks(links:List<String>,policy:PlaylistPolicy=PlaylistPolicy()) {
        close();key=UUID.randomUUID().toString();url=links.first()
        val entries=links.mapIndexed {i,link->PlaylistEntry("link:$i",UrlPolicy.normalize(link),i+1,null)}
        duplicatePolicy=policy
        mutable.value=PlaylistUi(visible=true,page=PlaylistPage(url,null,null,null,entries.size,entries,entries.size,false),selected=entries.map {it.key}.toSet(),initialPolicy=policy,sessionId=key)
        checkDuplicates()
    }
    fun loadMore() {
        val before=state.value
        if(before.loading||before.saving||before.persisted||before.page?.items?.size?.let {it>=5000}==true) return
        request=UUID.randomUUID().toString();val token=request;val offset=before.page?.nextOffset ?: 0
        AnalysisTrace.mark(token,"playlist_ui_discovery_started")
        mutable.update {it.copy(loading=true,ambiguous=false,error=null)}
        job=scope.launch {
            try {
                val page=PlaylistPage.parse(extractor.playlist(url,token,offset,limit=20),url)
                ensureActive()
                AnalysisTrace.mark(token,"playlist_page_ready")
                if(request==token) mutable.update {old->
                    val combined=(old.page?.items.orEmpty()+page.items).distinctBy {it.key}.take(5000)
                    old.copy(loading=false,page=page.copy(items=combined),selected=old.selected+page.items.filter {it.selectable}.map {it.key})
                }
                if(request==token) AnalysisTrace.mark(token,"playlist_page_published")
                if(request==token) checkDuplicates()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {if(request==token) {AnalysisTrace.mark(token,"playlist_discovery_failed");mutable.update {it.copy(loading=false,error=(e as? MediaException)?.code ?: ErrorCode.EXTRACTION_FAILED)}}}
        }
    }
    fun select(key:String,checked:Boolean) {if(!state.value.saving&&!state.value.persisted) mutable.update {it.copy(selected=if(checked) it.selected+key else it.selected-key)}}
    fun selectAll(all:Boolean) {if(!state.value.saving&&!state.value.persisted) mutable.update {it.copy(selected=if(all) it.page?.items.orEmpty().filter {e->e.selectable}.map {e->e.key}.toSet() else emptySet())}}
    fun range(first:Int,last:Int) {if(first>0&&last>=first&&!state.value.persisted) mutable.update {it.copy(selected=it.page?.items.orEmpty().filter {e->e.selectable&&e.position in first..last}.map {e->e.key}.toSet())}}
    fun cancelDiscovery() {extractor.cancel(request);request="";job?.cancel();duplicateJob?.cancel();++sizeRequest;sizeJob?.cancel();mutable.update {it.copy(loading=false,checkingDuplicates=false,sizing=false)}}
    fun close() {if(state.value.saving) return;cancelDiscovery();mutable.value=PlaylistUi()}
    fun submit(policy:PlaylistPolicy,numbered:Boolean,concurrency:Int,skipExisting:Boolean=false,plan:DownloadPlan?=state.value.initialPlan) {
        val current=state.value;val page=current.page ?: return
        if(current.saving||current.loading||current.selected.isEmpty()) return
        val head=page.items.filter {it.selectable&&it.key in current.selected&&(!skipExisting||
            it.key !in current.duplicates.matchingCompleted&&it.key !in current.duplicates.matchingActive)}.minByOrNull {it.position}
        val handoff=if(plan==null) head?.url?.let(extractor::retainPlaylistItem) else null
        sizeJob?.cancel()
        AnalysisTrace.mark(current.sessionId,"playlist_submit_started")
        mutable.update {it.copy(saving=true,error=null)}
        scope.launch(start=CoroutineStart.UNDISPATCHED) {SubmissionHandoff.run {
            var accepted=false
            try {
                val id=repository.submit(key,page,page.items.filter {it.key in current.selected},policy,numbered,concurrency,skipExisting,plan)
                AnalysisTrace.value(current.sessionId,"playlist_batch_id",id)
                AnalysisTrace.mark(current.sessionId,"playlist_persisted")
                mutable.update {it.copy(persisted=true)}
                // Exactly one platform submission, during the visible confirmation handoff.
                AnalysisTrace.mark(current.sessionId,"playlist_dispatch_started")
                if(plan!=null) planned.enqueue(id,true) else scheduler.startFromUser()
                accepted=true
                AnalysisTrace.mark(current.sessionId,"playlist_dispatch_accepted")
                mutable.update {it.copy(saving=false,queued=id)}
            } catch(e:Exception) {AnalysisTrace.mark(current.sessionId,"playlist_submission_failed");mutable.update {it.copy(saving=false,error=(e as? MediaException)?.code ?: ErrorCode.SCHEDULING_FAILED)}}
            // Successful handoffs are adopted atomically by the durable subscriber;
            // otherwise the existing 15-second lease bounds a delayed owner start.
            finally {if(!accepted) handoff?.close()}
        }}
    }
}
