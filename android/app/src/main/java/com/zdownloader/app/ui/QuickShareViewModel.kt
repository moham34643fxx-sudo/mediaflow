package com.zdownloader.app.ui

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdownloader.app.R
import com.zdownloader.app.background.Scheduler
import com.zdownloader.app.data.DownloadRepository
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Extractor
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.engine.FormatSizes
import com.zdownloader.app.engine.LameEncoder
import com.zdownloader.app.engine.NetworkDiagnostics
import com.zdownloader.app.engine.VideoDimensionDiscovery
import com.zdownloader.app.engine.VideoDimensionProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class QuickShareState(
    val media:Media?=null,val analyzing:Boolean=false,val expanded:Boolean=false,
    val choice:QuickShareChoice?=null,val saving:Boolean=false,val error:ErrorCode?=null,
    val queuedId:String?=null,val mp3Available:Boolean=false,val waitingReason:ErrorCode?=null,val traceId:String="",val duplicate:com.zdownloader.app.data.DuplicateReview?=null,
    val qualityPreference:Int?=null,val waitingForBest:Boolean=false,val preferenceUnavailable:Boolean=false,
    val sourceUrl:String?=null,val persistedId:String?=null,val audioOutput:String="original",val formatSection:String="all",val enrichingSizes:Boolean=false,val metadataAt:Long=0,
    val discoveringFormats:Boolean=false,val catalogReady:Boolean=false,val pendingSizeIds:Set<String> = emptySet(),
    val pendingDimensionIds:Set<String> = emptySet(),
    val defaultHeight:Int=0,val defaultDownloadType:String="ask",
    val failedDimensionIds:Set<String> = emptySet(),val discoveryError:ErrorCode?=null
) {
    // This is an immutable UI snapshot. Reuse its resolved selection instead of
    // scanning the media formats for every CTA/error/size read in a composition.
    val selection:Selection? by lazy(LazyThreadSafetyMode.PUBLICATION) {media?.let {choice?.resolve(it)}}
    val defaultChoice:QuickShareChoice? by lazy(LazyThreadSafetyMode.PUBLICATION) {media?.let {QuickShareFormats.defaultChoice(it,defaultHeight,defaultDownloadType)}}
    private val preferenceReady:Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        qualityPreference?.let {height->when {
            height !in DownloadRequest.heights+listOf(-1) -> false
            height==-1&&audioOutput=="mp3"&&!mp3Available -> false
            media==null||analyzing -> true
            height==-1 -> media.audioOptions().isNotEmpty()
            else -> VideoPreferenceSelection.ready(media,height)!=null
        }}==true
    }
    private val canEdit:Boolean get()=sourceUrl!=null&&error==null&&!preferenceUnavailable&&!saving&&queuedId==null&&persistedId==null
    val canSubmit:Boolean get()=canEdit&&(preferenceReady||selection?.let {
        !it.audioOnly||it.output!="mp3"||!it.convertAudio||mp3Available
    }==true)
    val canSubmitDefault:Boolean get()=canEdit&&choice==null&&qualityPreference==null&&defaultChoice!=null
}

@HiltViewModel class QuickShareViewModel @Inject constructor(
    private val extractor:Extractor,private val repository:DownloadRepository,
    private val settingsRepository:SettingsRepository,private val scheduler:Scheduler,
    private val saved:SavedStateHandle,private val playlistRepository:com.zdownloader.app.data.PlaylistRepository,private val planned:com.zdownloader.app.background.PlannedDownloads
):ViewModel() {
    val playlists=PlaylistController(viewModelScope,extractor,playlistRepository,scheduler,planned)
    private val mutable=MutableStateFlow(QuickShareState(expanded=saved["expanded"] ?: false,
        choice=saved.get<String>("choiceId")?.let {QuickShareChoice(saved["choiceAudio"] ?: false,it,saved["choiceOutput"] ?: "")},
        queuedId=saved["queuedId"],waitingReason=saved.get<String>("waitingReason")?.let(ErrorCode::parse),
        qualityPreference=saved["qualityPreference"],sourceUrl=saved["url"],persistedId=saved["persistedId"],audioOutput=saved["audioOutput"] ?: "original",formatSection=saved["formatSection"] ?: "all"))
    val state=mutable.asStateFlow()
    val settings=settingsRepository.flow.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),Settings())
    private val _toastEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val toastEvents = _toastEvents.asSharedFlow()
    private var analysis:Job?=null
    private var sizeJob:Job?=null
    private var request=""
    private var dimensionInput:Media?=null
    private var acceptedUrl:String?=saved["url"]
    private var awaitingFrame:Pair<String,String>?=null
    private var nativeReadyStarted=false
    /** Called after the share surface's first draw, never from ViewModel construction. */
    fun windowDrawn(traceId:String) {
        val pending=awaitingFrame?.takeIf {it.second==traceId} ?: return
        awaitingFrame=null
        AnalysisTrace.mark(traceId,"preferences_interactive")
        // Draw immediately, then discover real choices/sizes without blocking a
        // preference-only durable submission on optional preview metadata.
        val current=mutable.value
        if(current.persistedId==null&&!current.saving) {
            if(!nativeReadyStarted) {
                nativeReadyStarted=true
                viewModelScope.launch {
                    val available=withContext(Dispatchers.IO) {runCatching {LameEncoder.version().isNotBlank()}.getOrDefault(false)}
                    mutable.update {it.copy(mp3Available=available)}
                }
            }
            if(analysis?.isActive==true||current.error!=null||current.media!=null||playlists.state.value.visible) return
            when {
                saved.get<Boolean>("singleItemScope")==true->analyze(pending.first,traceId)
                PlaylistUrls.ambiguous(pending.first)->playlists.ask(pending.first,playlistPolicy())
                PlaylistUrls.candidate(pending.first)->playlists.open(pending.first,playlistPolicy())
                else->analyze(pending.first,traceId)
            }
        }
    }
    fun accept(url:String?,traceId:String=UUID.randomUUID().toString()) {
        if(mutable.value.saving||mutable.value.queuedId!=null||playlists.state.value.saving) return
        val valid=url?.let {runCatching {SharedLinks.normalize(it)}.getOrNull()}
        if(valid!=null&&acceptedUrl==valid&&mutable.value.sourceUrl!=null) {
            val current=mutable.value
            // A second intent is not a new analysis. A restored ViewModel can
            // have a saved URL but no surviving preview or first-frame callback.
            if(current.media==null&&current.error==null&&analysis?.isActive!=true&&!playlists.state.value.visible) {
                awaitingFrame=valid to traceId
                mutable.update {it.copy(traceId=traceId,analyzing=true,discoveringFormats=true)}
            }
            return
        }
        // A source owns its selection, plan and durable submission identity. Reset
        // them together rather than going through editing guards for an old row.
        cancelAnalysis();playlists.close()
        listOf("choiceId","choiceAudio","choiceOutput","qualityPreference","waitingForBest","expanded","audioOutput","formatSection",
            "plannedAt","plannedNetwork","plannedCharging","persistedId","persistedKey","requestOperation","requestJson","queuedId","waitingReason","singleItemScope",
            "videoChoiceId","videoChoiceOutput","videoPreference","audioChoiceId","audioChoiceOutput","audioPreference")
            .forEach {saved.remove<Any?>(it)}
        acceptedUrl=valid;saved["url"]=valid
        awaitingFrame=valid?.let {it to traceId}
        val isLikelyImg=QuickShareFormats.isLikelyImageUrl(valid)
        if(isLikelyImg) saved["qualityPreference"]=0
        mutable.value=QuickShareState(sourceUrl=valid,traceId=traceId,mp3Available=mutable.value.mp3Available,
            qualityPreference=if(isLikelyImg) 0 else null,
            analyzing=valid!=null,discoveringFormats=valid!=null,
            error=if(valid==null) ErrorCode.INVALID_URL else null)
    }
    fun retry() {
        val current=mutable.value
        if(current.saving||current.queuedId!=null||analysis?.isActive==true) return
        current.persistedId?.let {resubmit(it);return}
        current.sourceUrl?.let {analyze(it,current.traceId,refresh=current.error==ErrorCode.MEDIA_URL_EXPIRED)}
    }
    private fun analyze(url:String,traceId:String=UUID.randomUUID().toString(),refresh:Boolean=false) {
        if(analysis?.isActive==true) return
        sizeJob?.cancel()
        val token=traceId;val operation=UUID.randomUUID().toString();request=operation
        mutable.update {it.copy(analyzing=true,discoveringFormats=true,catalogReady=false,enrichingSizes=false,
            pendingSizeIds=emptySet(),pendingDimensionIds=emptySet(),failedDimensionIds=emptySet(),discoveryError=null,
            media=if(refresh) it.media?.let(QuickShareFormats::usable)?.takeIf {media->media.formats.isNotEmpty()} else it.media,
            error=null,traceId=token)}
        analysis=viewModelScope.launch {
            try {
                val prefs=settingsRepository.current()
                val result=collectProgress<Media,Media>(
                    apply={partial->if(request==operation) acceptMetadata(partial,prefs.preferredHeight,true,prefs.downloadType)}
                ) {publish->extractor.analyze(url,token,forceRefresh=refresh,enrichOptional=false,onMetadata=publish)}
                ensureActive()
                if(request!=operation) return@launch
                val ids=VideoDimensionDiscovery.pendingIds(result)
                AnalysisTrace.checkpoint(token,"quality_catalog_ready")
                val verified=if(ids.isEmpty()) VideoDimensionProgress(result) else {
                    // Joining an in-flight base extraction can miss its single callback.
                    // Its returned snapshot must still be visible before header inspection.
                    acceptMetadata(result,prefs.preferredHeight,true,prefs.downloadType)
                    confirmDimensions(result,ids,operation,token,prefs.preferredHeight,prefs.downloadType)
                }
                if(request!=operation) return@launch
                finishCatalog(verified,prefs.preferredHeight,prefs.downloadType)
                AnalysisTrace.checkpoint(token,"quick_share_catalog_ready")
                mutable.value.media?.let {launchSizeEnrichment(it,operation,token)}
                AnalysisTrace.mark(token,"analysis_finished")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {if(request==operation) {
                val code=(e as? MediaException)?.code ?: ErrorCode.EXTRACTION_FAILED
                if(code==ErrorCode.PLAYLIST_DETECTED&&mutable.value.media==null) {
                    saved["singleItemScope"]=false
                    playlists.open(url,playlistPolicy())
                } else mutable.update {state->
                    if(state.media==null) state.copy(error=code)
                    else state.copy(discoveryError=code,catalogReady=true,
                        failedDimensionIds=state.failedDimensionIds+VideoDimensionDiscovery.pendingIds(state.media))
                }
            }} finally {
                if(request==operation) mutable.update {it.copy(analyzing=false,discoveringFormats=false,pendingDimensionIds=emptySet())}
                AnalysisTrace.checkpoint(token,"share_analysis_terminal")
            }
        }
    }

    /** Both producers publish whole snapshots; keep the latest, in order, on the ViewModel dispatcher. */
    private suspend fun <Update,Result> collectProgress(apply:(Update)->Unit,work:suspend ((Update)->Unit)->Result):Result=coroutineScope {
        val updates=Channel<Update>(Channel.CONFLATED)
        val collector=launch {for(update in updates) apply(update)}
        try {work {updates.trySend(it)}} finally {updates.close();collector.join()}
    }

    private suspend fun confirmDimensions(media:Media,ids:Set<String>,operation:String,trace:String,
        height:Int,downloadType:String,retry:Boolean=false):VideoDimensionProgress {
        dimensionInput=media
        mutable.update {it.copy(pendingDimensionIds=ids)}
        return try {
            collectProgress<VideoDimensionProgress,VideoDimensionProgress>(apply={progress->
                if(request==operation) {
                    acceptMetadata(progress.media,height,true,downloadType)
                    mutable.update {it.copy(pendingDimensionIds=progress.pendingIds,failedDimensionIds=progress.failedIds)}
                }
            }) {publish->extractor.videoDimensions(media,trace,ids.takeIf {retry},publish)}
        } catch(error:Exception) {
            currentCoroutineContext().ensureActive()
            val current=mutable.value
            VideoDimensionProgress(current.media ?: media,failedIds=current.failedDimensionIds+current.pendingDimensionIds)
        } finally {
            if(request==operation) dimensionInput=null
        }
    }

    private fun finishCatalog(progress:VideoDimensionProgress,height:Int,downloadType:String) {
        acceptMetadata(progress.media,height,false,downloadType)
        mutable.update {it.copy(discoveringFormats=false,catalogReady=it.media!=null,
            pendingDimensionIds=emptySet(),failedDimensionIds=progress.failedIds)}
    }

    fun retrySources() {
        val current=mutable.value
        if(current.saving||current.persistedId!=null||current.queuedId!=null||analysis?.isActive==true) return
        val media=current.media ?: return
        val ids=VideoDimensionDiscovery.pendingIds(media,current.failedDimensionIds)
        if(ids.isEmpty()) return
        sizeJob?.cancel()
        val operation=UUID.randomUUID().toString()
        request=operation
        mutable.update {it.copy(analyzing=true,discoveringFormats=true,pendingDimensionIds=ids,
            discoveryError=null,
            failedDimensionIds=it.failedDimensionIds-ids,enrichingSizes=false,pendingSizeIds=emptySet())}
        analysis=viewModelScope.launch {
            val result=confirmDimensions(media,ids,operation,current.traceId,current.defaultHeight,current.defaultDownloadType,retry=true)
            if(request==operation) finishCatalog(result.copy(failedIds=result.failedIds+(current.failedDimensionIds-ids)),
                current.defaultHeight,current.defaultDownloadType)
        }
    }

    private fun launchSizeEnrichment(media:Media,operation:String,traceId:String) {
        sizeJob?.cancel()
        sizeJob=viewModelScope.launch {
            try {
                if(request!=operation) return@launch
                AnalysisTrace.checkpoint(traceId,"optional_enrichment_started")
                mutable.update {it.copy(enrichingSizes=true)}
                val enriched=collectProgress<Pair<Media,Set<String>>,Media>(apply={(snapshot,pending)->
                    if(request==operation) mutable.update {it.copy(media=snapshot,pendingSizeIds=pending)}
                }) {publish->
                    withContext(Dispatchers.IO) {
                        FormatSizes.enrich(media,onProgress={snapshot,pending->publish(QuickShareFormats.usable(snapshot) to pending)})
                            .let(QuickShareFormats::usable)
                    }
                }
                if(request==operation) mutable.update {it.copy(media=enriched)}
                AnalysisTrace.checkpoint(traceId,"optional_enrichment_finished")
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {AnalysisTrace.checkpoint(traceId,"optional_enrichment_finished")}
            finally {
                if(request==operation) mutable.update {it.copy(enrichingSizes=false,pendingSizeIds=emptySet())}
            }
        }
    }
    private fun acceptMetadata(media:Media,preferredHeight:Int,loading:Boolean,downloadType:String) {
        val start=android.os.SystemClock.elapsedRealtime()
        val usable=QuickShareFormats.usable(media)
        if(usable.formats.isEmpty()) {
            if(!loading) {
                mutable.update {state->
                    val existing=state.media?.let(QuickShareFormats::usable)?.takeIf {it.formats.isNotEmpty()}
                    if(existing!=null) state.copy(media=existing,analyzing=false,discoveryError=ErrorCode.FORMAT_UNAVAILABLE)
                    else state.copy(media=null,analyzing=false,error=ErrorCode.FORMAT_UNAVAILABLE)
                }
            }
            return
        }
        val isImageOnly=usable.videoOptions().isEmpty()&&usable.imageOptions().isNotEmpty()
        if(isImageOnly&&!loading&&mutable.value.qualityPreference!=null) {
            saved["qualityPreference"]=null;saved["waitingForBest"]=false
            mutable.update {it.copy(qualityPreference=null,waitingForBest=false,preferenceUnavailable=false)}
        }
        // Opening/closing a section must never choose a different quality on
        // the user's behalf when metadata or optional sizes arrive.
        val defaultChoice=if(isImageOnly&&!loading) QuickShareFormats.defaultChoice(usable) else null
        val choice=mutable.value.choice ?: defaultChoice
        if(choice!=null&&mutable.value.choice==null) {
            saveChoice(choice)
        }
        val wasUnavailable=mutable.value.preferenceUnavailable
        val unavailable=!loading&&when {
            choice!=null->choice.resolve(usable)==null
            isImageOnly->false
            else->mutable.value.qualityPreference?.let {height->
                if(height==-1) usable.audioOptions().isEmpty()
                else height>0&&VideoPreferenceSelection.ready(usable,height)==null
            }==true
        }
        // Enrichment may finish while enqueue or a folder grant fails. It must
        // not erase that error or reset an explicitly selected candidate.
        mutable.update {it.copy(media=usable,choice=choice,analyzing=loading,metadataAt=android.os.SystemClock.elapsedRealtime(),
            defaultHeight=preferredHeight,defaultDownloadType=downloadType,
            preferenceUnavailable=unavailable,
            error=if(wasUnavailable&&!unavailable&&it.error==ErrorCode.QUALITY_UNAVAILABLE) null else it.error)}
        if(unavailable&&!wasUnavailable&&choice==null) {
            _toastEvents.tryEmit(R.string.quality_not_available)
        }
        AnalysisTrace.value(mutable.value.traceId,"selection_grouping_ms",android.os.SystemClock.elapsedRealtime()-start)
        AnalysisTrace.mark(mutable.value.traceId,"compose_data_delivered")
    }
    private fun saveChoice(choice:QuickShareChoice?) {
        saved["choiceId"]=choice?.formatId;saved["choiceAudio"]=choice?.audio;saved["choiceOutput"]=choice?.output
    }
    fun choose(choice:QuickShareChoice?) {
        val current=mutable.value
        if(current.saving||current.persistedId!=null) return
        if(choice!=null&&current.media?.let {choice.resolve(it)}==null) return
        val section=choice?.let {if(it.audio) "audio" else "video"} ?: current.formatSection
        if(section!=current.formatSection) rememberSectionSelection(current)
        saveChoice(choice)
        if(choice!=null) saved["qualityPreference"]=null
        saved["formatSection"]=section
        saved["waitingForBest"]=false
        mutable.update {it.copy(choice=choice,formatSection=section,error=null,waitingForBest=false,
            qualityPreference=if(choice==null) it.qualityPreference else null,preferenceUnavailable=false)}
    }
    fun prefer(height:Int) {
        val current=mutable.value
        if(height !in DownloadRequest.heights+listOf(-1)||current.saving||current.persistedId!=null) return
        val section=if(height==-1) "audio" else "video"
        if(section!=current.formatSection) rememberSectionSelection(current)
        saveChoice(null)
        saved["requestJson"]=null;saved["requestOperation"]=null
        saved["qualityPreference"]=height;saved["waitingForBest"]=false
        saved["formatSection"]=section
        mutable.update {
            val resolved=!it.analyzing&&it.media!=null&&freshMetadata(it)
            val unavailable=resolved&&when {
                height==-1->it.media?.audioOptions().isNullOrEmpty()
                height>0->VideoPreferenceSelection.ready(it.media,height)==null
                else->false
            }
            it.copy(qualityPreference=height,choice=null,waitingForBest=false,error=null,formatSection=section,
                preferenceUnavailable=unavailable)
        }
    }
    fun audioOutput(output:String) {
        if(output !in listOf("original","mp3")||mutable.value.saving||mutable.value.persistedId!=null) return
        saved["audioOutput"]=output;saved["requestJson"]=null;saved["requestOperation"]=null
        mutable.update {it.copy(audioOutput=output)}
    }
    fun requestBestWhenReady() {
        // Quality selection never triggers an automatic download; only explicit enqueue() starts a download.
    }
    fun expand() {
        if(mutable.value.saving||mutable.value.expanded) return
        saved["expanded"]=true
        mutable.update {it.copy(expanded=true)}
    }
    fun showAudio()=selectSection("audio")
    fun showVideo()=selectSection("video")

    private fun rememberSectionSelection(current:QuickShareState) {
        val audio=current.choice?.audio ?: current.qualityPreference?.let {it==-1} ?: return
        val section=if(audio) "audio" else "video"
        saved["${section}ChoiceId"]=current.choice?.formatId
        saved["${section}ChoiceOutput"]=current.choice?.output
        saved["${section}Preference"]=current.qualityPreference
    }

    private fun selectSection(section:String) {
        val current=mutable.value
        if(current.saving||current.queuedId!=null||current.persistedId!=null||current.formatSection==section) return
        rememberSectionSelection(current)
        val audioMode=section=="audio"
        val previousChoice=saved.get<String>("${section}ChoiceId")?.let {id->
            QuickShareChoice(audioMode,id,saved.get<String>("${section}ChoiceOutput").orEmpty())
        }
        val preference=saved.get<Int>("${section}Preference")
        val choice=previousChoice ?: if(preference==null) current.media?.let {media->
            if(audioMode) QuickShareFormats.defaultChoice(media,downloadType="audio")?.takeIf {it.audio}
            else QuickShareFormats.defaultChoice(media)?.takeUnless {it.audio}
        } else null
        // Keep an explicit prior source even if it expired in the other tab.
        // The existing eligibility gate must prevent submitting a substitute.
        val unavailable=!current.analyzing&&current.media?.let {media->
            when {
                choice!=null->choice.resolve(media)==null
                preference==-1->media.audioOptions().isEmpty()
                preference!=null&&preference>0->VideoPreferenceSelection.ready(media,preference)==null
                else->false
            }
        }==true
        saveChoice(choice)
        saved["qualityPreference"]=if(choice==null) preference else null
        saved["waitingForBest"]=false
        saved["formatSection"]=section
        mutable.update {it.copy(formatSection=section,choice=choice,waitingForBest=false,
            qualityPreference=if(choice==null) preference else null,preferenceUnavailable=unavailable,
            error=it.error?.takeUnless {error->error==ErrorCode.QUALITY_UNAVAILABLE})}
    }
    fun collapse() {saved["expanded"]=false;mutable.update {it.copy(expanded=false)}}
    fun canDismiss():Boolean=!mutable.value.saving&&!playlists.state.value.saving
    fun videoOnly() {saved["singleItemScope"]=true;playlists.close();acceptedUrl?.let {analyze(it)}}
    fun selectPlaylistItem(url:String) {
        if(mutable.value.saving||mutable.value.queuedId!=null||playlists.state.value.saving) return
        val valid=runCatching {SharedLinks.normalize(url)}.getOrNull()
        if(valid==null) {mutable.update {it.copy(error=ErrorCode.INVALID_URL)};return}
        val traceId=UUID.randomUUID().toString()
        accept(valid,traceId)
        saved["singleItemScope"]=true
        playlists.close()
        showVideo()
        expand()
    }
    private fun playlistPolicy(current:QuickShareState=mutable.value):PlaylistPolicy {
        val selected=current.selection
        val audio=current.qualityPreference==-1||selected?.audioOnly==true
        val output=if(audio&&(selected?.output ?: current.audioOutput)=="mp3") "mp3" else "original"
        return PlaylistPolicy(audio=audio,output=output,height=current.qualityPreference?.takeIf {it in DownloadRequest.heights&&it>0} ?: 720,
            fallback=if(current.qualityPreference==0) QualityFallback.BEST else QualityFallback.AT_MOST,bitrate=selected?.bitrate ?: 192)
    }
    fun cancelAnalysis() {
        awaitingFrame=null;request="";dimensionInput=null;analysis?.cancel();sizeJob?.cancel()
        mutable.update {it.copy(analyzing=false,discoveringFormats=false,enrichingSizes=false,pendingSizeIds=emptySet(),pendingDimensionIds=emptySet())}
    }
    private fun freshMetadata(current:QuickShareState)=current.media?.let {FormatAvailability.reusable(it,android.os.SystemClock.elapsedRealtime()-current.metadataAt)}==true
    fun storageError() {mutable.update {it.copy(error=ErrorCode.STORAGE_PERMISSION_ERROR)}}
    fun setDestination(uri:String) {viewModelScope.launch {
        try {settingsRepository.text("destination",uri);mutable.update {it.copy(error=null)}}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {storageError()}
    }}
    fun cancelDuplicate() {mutable.update {it.copy(duplicate=null)}}
    fun confirmDuplicate() {val review=mutable.value.duplicate;cancelDuplicate();if(review!=null) enqueue(allowAgain=true)}
    fun schedule(plan:DownloadPlan) {
        if(mutable.value.saving||mutable.value.persistedId!=null) return
        saved["plannedAt"]=plan.at;saved["plannedNetwork"]=plan.network.name;saved["plannedCharging"]=plan.charging
        if(mutable.value.choice==null&&mutable.value.qualityPreference==null) {
            mutable.value.defaultChoice?.let(::choose)
        }
        enqueue()
    }
    fun enqueue(allowAgain:Boolean=false) {
        val current=mutable.value
        if(current.saving||current.queuedId!=null) return
        val url=current.sourceUrl
        if(url!=null&&saved.get<Boolean>("singleItemScope")!=true&&(PlaylistUrls.ambiguous(url)||PlaylistUrls.candidate(url))) {
            cancelAnalysis()
            val policy=playlistPolicy(current)
            val plan=saved.get<Long>("plannedAt")?.let {DownloadPlan(it,DownloadNetwork.valueOf(saved.get<String>("plannedNetwork") ?: "ANY"),saved["plannedCharging"] ?: false)}
            if(PlaylistUrls.ambiguous(url)) playlists.ask(url,policy,plan) else playlists.open(url,policy,plan)
            return
        }
        current.persistedId?.let {resubmit(it);return}
        if(current.qualityPreference!=null&&
            (current.media==null||current.analyzing||!freshMetadata(current))) {
            enqueuePreference(current,allowAgain)
            return
        }
        if(current.media==null) return
        val isImageOnly=current.media.videoOptions().isEmpty()&&current.media.imageOptions().isNotEmpty()
        if(isImageOnly&&current.qualityPreference!=null) {
            saved["qualityPreference"]=null;saved["waitingForBest"]=false
            mutable.update {it.copy(qualityPreference=null,waitingForBest=false,preferenceUnavailable=false)}
        }
        // A ready preview already contains verified stream URLs. Reusing
        // the same policy result avoids cancelling it and discovering it again
        // in the queue.
        val fresh=freshMetadata(current)
        val readyVideo=if(fresh&&!isImageOnly) VideoPreferenceSelection.ready(current.media,current.qualityPreference) else null
        val readyAudio=if(fresh&&current.qualityPreference==-1) {
            runCatching {
                val usable=QuickShareFormats.usable(current.media)
                Selection.audio(usable,current.audioOutput,bitrate=192)
            }.getOrNull()
        } else null
        val readySelection=readyVideo ?: readyAudio
        if(!isImageOnly&&current.qualityPreference!=null&&readySelection==null) {
            unavailableVideoQuality();return
        }
        val media=current.media
        val currentChoice=current.choice ?: current.defaultChoice
        val selection=readySelection ?: currentChoice?.resolve(media) ?: run {
            val expired=media.formats.firstOrNull {it.id==currentChoice?.formatId}?.let {
                FormatAvailability.unavailableReason(it)=="expired_url"
            }==true
            mutable.update {it.copy(error=if(expired) ErrorCode.MEDIA_URL_EXPIRED else ErrorCode.FORMAT_UNAVAILABLE)};return
        }
        if(selection.audioOnly&&selection.output=="mp3"&&selection.convertAudio&&!current.mp3Available) return
        AnalysisTrace.checkpoint(current.traceId,"download_pressed")
        if(readySelection!=null) {
            AnalysisTrace.checkpoint(current.traceId,if(readyVideo!=null) "verified_video_preview_reused" else "verified_audio_preview_reused")
        }
        mutable.update {it.copy(saving=true,error=null)}
        viewModelScope.launch {
          SubmissionHandoff.run {
            try {
                val plan=saved.get<Long>("plannedAt")?.let {DownloadPlan(it,DownloadNetwork.valueOf(saved.get<String>("plannedNetwork") ?: "ANY"),saved["plannedCharging"] ?: false)}
                if(Build.VERSION.SDK_INT<29&&settingsRepository.current().destination==null) throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
                val key=listOf(media.url,selection.primary.id,selection.secondary?.id,selection.audioOnly,selection.output,selection.bitrate,selection.convertAudio).joinToString("|")
                 val previous=if(saved.get<String>("persistedKey")==key) saved.get<String>("persistedId")?.let {repository.dao.get(it)}?.takeUnless {it.status=="CANCELLED"} else null
                 val persisted=withContext(Dispatchers.IO) {
                    val unavailable=listOfNotNull(selection.primary,selection.secondary).firstNotNullOfOrNull {
                        FormatAvailability.unavailableReason(it)
                    }
                    if(unavailable!=null) throw MediaException(if(unavailable=="expired_url") ErrorCode.MEDIA_URL_EXPIRED else ErrorCode.FORMAT_UNAVAILABLE)
                    com.zdownloader.app.engine.FormatDiagnostics.catalog(media,current.traceId)
                    com.zdownloader.app.engine.FormatDiagnostics.selection(media,selection,current.traceId)
                    val id=previous?.id ?: repository.create(media,selection,media.title,allowAgain,plan)
                    checkNotNull(repository.dao.get(id)) {"Queue persistence failed"}
                }
                val id=persisted.id
                // Keep the durable row identity even if scheduling fails or the
                // Activity is recreated. Retrying must not create a second request.
                saved["persistedKey"]=key;saved["persistedId"]=id
                mutable.update {it.copy(persistedId=id)}
                NetworkDiagnostics.record("share","queue_saved",null,operation=id)
                val prepared=if(plan!=null) persisted else repository.prepareForSubmission(id)
                val receipt=if(prepared.status=="COMPLETED") com.zdownloader.app.background.ScheduleReceipt("already_completed",true)
                    else if(plan!=null) {planned.enqueue(id);com.zdownloader.app.background.ScheduleReceipt("scheduled",false)} else scheduler.startFromUser(id)
                saved["waitingReason"]=receipt.waitingReason?.name
                saved["queuedId"]=id
                mutable.update {it.copy(saving=false,queuedId=id,waitingReason=receipt.waitingReason)}
                cancelAnalysis()
            } catch(e:CancellationException) {throw e}
            catch(e:com.zdownloader.app.data.DuplicateDownloadException) {mutable.update {it.copy(saving=false,duplicate=e.review)}}
            catch(e:Exception) {
                val code=(e as? MediaException)?.code ?: ErrorCode.INTERNAL_ENGINE_ERROR
                NetworkDiagnostics.record("share","enqueue_failed",code)
                mutable.update {it.copy(saving=false,error=code)}
            }
          }
        }
    }
    private fun unavailableVideoQuality() {
        saved["expanded"]=true;saved["formatSection"]="video"
        mutable.update {it.copy(saving=false,preferenceUnavailable=true,error=ErrorCode.QUALITY_UNAVAILABLE,expanded=true,formatSection="video")}
        _toastEvents.tryEmit(R.string.quality_not_available)
    }
    /** Retry the committed row, including after process recreation lost preview metadata. */
    private fun resubmit(id:String) {
        mutable.update {it.copy(saving=true,error=null)}
        cancelAnalysis()
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {SubmissionHandoff.run {
            try {
                val row=repository.dao.get(id) ?: throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                if(row.status=="CANCELLED") throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                val receipt=when(row.status) {
                    "COMPLETED"->com.zdownloader.app.background.ScheduleReceipt("already_completed",true)
                    "SCHEDULED"->{planned.enqueue(id);com.zdownloader.app.background.ScheduleReceipt("scheduled",false)}
                    else->{repository.prepareForSubmission(id);scheduler.startFromUser(id)}
                }
                saved["queuedId"]=id;saved["waitingReason"]=receipt.waitingReason?.name
                mutable.update {it.copy(saving=false,queuedId=id,waitingReason=receipt.waitingReason)}
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {mutable.update {it.copy(saving=false,error=(error as? MediaException)?.code ?: ErrorCode.INTERNAL_ENGINE_ERROR)}}
        }}
    }
    private fun enqueuePreference(current:QuickShareState,allowAgain:Boolean=false) {
        val url=current.sourceUrl ?: return
        val preference=current.qualityPreference ?: return
        AnalysisTrace.checkpoint(current.traceId,"download_pressed")
        val confirmed=System.currentTimeMillis()
        // The preview uses the same base-discovery key as the queue. Reserve its
        // ownership before cancelling the UI subscriber, including Activity closure.
        val handoff=extractor.retainVideoPreview(url,dimensionInput ?: current.media)
        NetworkDiagnostics.record("share","metadata_handoff_reserved",null,
            details=org.json.JSONObject().put("retained",handoff!=null).put("cap",preference))
        val operation=saved.get<String>("requestOperation") ?: UUID.randomUUID().toString().also {saved["requestOperation"]=it}
        mutable.update {it.copy(saving=true,error=null)}
        cancelAnalysis()
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            SubmissionHandoff.run {
                try {
                    val prefs=settingsRepository.current()
                    if(Build.VERSION.SDK_INT<29&&prefs.destination==null) throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
                    val request=saved.get<String>("requestJson")?.let(DownloadRequest::parse) ?: DownloadRequest(
                        height=preference.coerceAtLeast(0),audioOnly=preference==-1,output=if(preference==-1) current.audioOutput else "auto",template=prefs.template,retries=prefs.retries,
                        wifiOnly=prefs.wifiOnly,traceId=current.traceId,confirmedAt=confirmed,exact=false).also {saved["requestJson"]=it.json()}
                    val plan=saved.get<Long>("plannedAt")?.let {DownloadPlan(it,DownloadNetwork.valueOf(saved.get<String>("plannedNetwork") ?: "ANY"),saved["plannedCharging"] ?: false)}
                    val id=withContext(Dispatchers.IO) {repository.createRequest(operation,url,request,prefs.destination,plan,allowAgain)}
                    saved["persistedId"]=id
                    mutable.update {it.copy(persistedId=id)}
                    AnalysisTrace.value(current.traceId,"operation_id",id)
                    AnalysisTrace.checkpoint(current.traceId,"request_persisted")
                    val prepared=if(plan==null) repository.prepareForSubmission(id) else repository.dao.get(id)!!
                    val receipt=if(prepared.status=="COMPLETED") com.zdownloader.app.background.ScheduleReceipt("already_completed",true)
                        else if(plan!=null) {planned.enqueue(id);com.zdownloader.app.background.ScheduleReceipt("scheduled",false)}
                        else scheduler.startFromUser(id)
                    AnalysisTrace.checkpoint(current.traceId,"request_accepted")
                    saved["queuedId"]=id;saved["waitingReason"]=receipt.waitingReason?.name
                    mutable.update {it.copy(saving=false,queuedId=id,waitingReason=receipt.waitingReason)}
                } catch(e:CancellationException) {handoff?.close();throw e}
                catch(e:com.zdownloader.app.data.DuplicateDownloadException) {handoff?.close();mutable.update {it.copy(saving=false,duplicate=e.review)}}
                catch(e:Exception) {
                    handoff?.close()
                    val code=(e as? MediaException)?.code ?: ErrorCode.INTERNAL_ENGINE_ERROR
                    NetworkDiagnostics.record("share","preference_submission_failed",code,operation=operation)
                    mutable.update {it.copy(saving=false,error=code)}
                }
            }
        }
    }
    override fun onCleared() {cancelAnalysis();playlists.cancelDiscovery();super.onCleared()}
}
