package com.zdownloader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Extractor
import com.zdownloader.app.background.Scheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

data class UiState(val text: String = "", val analyzing: Boolean = false, val media: Media? = null, val error: ErrorCode? = null, val batch: List<String> = emptyList(), val batchErrors: Int = 0, val engine: String = "", val message: Boolean = false, val analysisPreview: Media? = null,val duplicate:DuplicateReview?=null,val analysisId:String="",val submitting:Boolean=false)
@HiltViewModel class ZViewModel @Inject constructor(val repository: DownloadRepository, val settingsRepository: SettingsRepository, val storage: StorageRepository, private val extractor: Extractor, private val scheduler: Scheduler,private val playlistRepository:PlaylistRepository,private val planned:com.zdownloader.app.background.PlannedDownloads) : ViewModel() {
    val playlists=PlaylistController(viewModelScope,extractor,playlistRepository,scheduler,planned)
    val sizePreviews get()=extractor.sizePreviews
    val batchSummaries=playlistRepository.summaries.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val mutable = MutableStateFlow(UiState())
    private val confirmations = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val queuedEvents=confirmations.receiveAsFlow()
    val ui = mutable.asStateFlow()
    val settings = settingsRepository.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Settings())
    val downloads = repository.downloads.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val library=repository.completed.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val recent=repository.recent.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val trash=repository.dao.trash().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun forgetTrash(id:String)=act {repository.dao.forgetTrash(id)}
    private var analysis: Job? = null
    private var request = ""
    private data class RetainedSubmission(val operationKey:String,val persistedId:String)
    @Volatile private var retainedSubmission:RetainedSubmission?=null
    fun input(text: String) {
        if(text.length>UrlPolicy.MAX_INPUT_LENGTH) {cancelAnalysis();mutable.update {it.copy(text="",media=null,batch=emptyList(),error=ErrorCode.INVALID_URL,analysisId="")};return}
        if(text!=ui.value.text) {retainedSubmission=null;cancelDuplicate();playlists.close();cancelAnalysis();mutable.update {it.copy(text=text,media=null,batch=emptyList(),error=null,message=false,analysisId="")}}
    }
    fun analyze(forceSingle:Boolean=false) {
        if(ui.value.analyzing||playlists.state.value.saving) return
        cancelAnalysis()
        val links = try {UrlPolicy.inputLinks(ui.value.text)} catch(e:MediaException) {mutable.update {it.copy(error=e.code)};return}
        if(links.size>200) {mutable.update {it.copy(error=ErrorCode.BATCH_LIMIT_EXCEEDED)};return}
        if(links.size > 1) {playlists.fromLinks(links);return}
        val url = links.firstOrNull() ?: ui.value.text.trim()
        if(!forceSingle&&PlaylistUrls.ambiguous(url)) {playlists.ask(url);return}
        if(!forceSingle&&PlaylistUrls.candidate(url)) {playlists.open(url);return}
        request = UUID.randomUUID().toString()
        val token=request
        mutable.update { it.copy(analyzing=true,error=null,media=null,batch=emptyList(),analysisPreview=null,analysisId=token) }
        analysis = viewModelScope.launch {
            try {
                val media=extractor.analyze(url,token,enrichOptional=true,onMetadata={ confirmed ->
                    // This callback contains the complete verified catalog. Sizes
                    // may still be probed, but they must not gate quality selection.
                    viewModelScope.launch {
                        if(request==token&&ui.value.analyzing) {
                            mutable.update {it.copy(media=confirmed,analyzing=false,analysisPreview=null)}
                            com.zdownloader.app.engine.AnalysisTrace.checkpoint(token,"home_catalog_selectable")
                        }
                    }
                })
                ensureActive()
                if(request==token) {
                    mutable.update {it.copy(media=media,analyzing=false,analysisPreview=null)}
                    com.zdownloader.app.engine.AnalysisTrace.checkpoint(token,"home_catalog_selectable")
                }
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(request==token) {
                if((e as? MediaException)?.code==ErrorCode.PLAYLIST_DETECTED) {mutable.update {it.copy(analyzing=false,analysisPreview=null)};playlists.open(url)}
                else mutable.update { it.copy(analyzing=false,analysisPreview=null,error=(e as? MediaException)?.code ?: ErrorCode.INTERNAL_ENGINE_ERROR) }
            } } finally {
                if(request==token&&mutable.value.analyzing) mutable.update {it.copy(analyzing=false,analysisPreview=null,error=it.error ?: ErrorCode.EXTRACTION_FAILED)}
                com.zdownloader.app.engine.AnalysisTrace.checkpoint(token,"home_analysis_terminal")
            }
        }
    }
    fun cancelAnalysis() { retainedSubmission=null; extractor.cancel(request); request="";analysis?.cancel(); mutable.update { it.copy(analyzing=false,analysisPreview=null,media=if(it.analyzing) null else it.media) } }
    fun playlist() {
        cancelAnalysis();playlists.open(ui.value.text)
    }
    fun removeBatch(url: String) { mutable.update { it.copy(batch=it.batch-url) } }
    fun clearBatch() { mutable.update { it.copy(batch=emptyList()) } }
    fun batchDownload(links: List<String>, audio: Boolean, maxHeight: Int) {
        if(links.isNotEmpty()) playlists.fromLinks(links,PlaylistPolicy(audio=audio,height=maxHeight.takeIf {it in listOf(360,480,720,1080)} ?: 720,
            fallback=if(maxHeight==0) QualityFallback.BEST else QualityFallback.AT_MOST))
    }
    private var pendingDuplicate:(()->Unit)?=null
    fun cancelDuplicate() {pendingDuplicate=null;mutable.update {it.copy(duplicate=null)}}
    fun confirmDuplicate() {val next=pendingDuplicate;cancelDuplicate();next?.invoke()}
    fun enqueue(media: Media, selection: Selection, filename: String,allowAgain:Boolean=false,plan:DownloadPlan?=null):Unit {
        if(mutable.value.submitting) return
        val currentToken=mutable.value.analysisId
        val expectedMediaUrl=media.url
        mutable.update {it.copy(submitting=true)}
        val opKey=listOf(currentToken,media.url,selection.json(),filename,plan?.at,plan?.network?.name,plan?.charging).joinToString("|")
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                SubmissionHandoff.run {
                    val previousId=retainedSubmission?.takeIf {it.operationKey==opKey}?.persistedId
                    val existing=previousId?.let {repository.dao.get(it)}?.takeUnless {it.status=="CANCELLED"}
                    val id=existing?.id ?: repository.create(media,selection,filename,allowAgain,plan)
                    checkNotNull(repository.dao.get(id)) {"Queue persistence failed"}
                    retainedSubmission=RetainedSubmission(opKey,id)
                    if(plan!=null) planned.enqueue(id) else {
                        repository.prepareForSubmission(id)
                        scheduler.startFromUser(id)
                    }
                    retainedSubmission=null
                    val current=mutable.value
                    if(current.analysisId==currentToken&&current.media?.url==expectedMediaUrl) {
                        mutable.update {it.copy(submitting=false,media=null,text="",analysisPreview=null,error=null,analysisId="")}
                        extractor.cancel(request);request="";analysis?.cancel()
                    } else {
                        mutable.update {it.copy(submitting=false)}
                    }
                    confirmations.send(Unit)
                }
            }
            catch(e:DuplicateDownloadException) {
                val current=mutable.value
                if(current.analysisId==currentToken&&(current.media==null||current.media?.url==expectedMediaUrl)) {
                    mutable.update {it.copy(submitting=false,duplicate=e.review)}
                    pendingDuplicate={enqueue(media,selection,filename,allowAgain=true,plan=plan)}
                } else {
                    mutable.update {it.copy(submitting=false)}
                }
            }
            catch(e:CancellationException) {
                mutable.update {it.copy(submitting=false)}
                throw e
            }
            catch(e:Exception) {
                val current=mutable.value
                if(current.analysisId==currentToken&&(current.media==null||current.media?.url==expectedMediaUrl)) {
                    val code=(e as? MediaException)?.code ?: if(e is SecurityException) ErrorCode.STORAGE_PERMISSION_ERROR else ErrorCode.INTERNAL_ENGINE_ERROR
                    mutable.update {it.copy(submitting=false,error=code)}
                } else {
                    mutable.update {it.copy(submitting=false)}
                }
            }
        }
    }
    fun startScheduled(id:String,batch:Boolean=false)=act {SubmissionHandoff.run {planned.now(id,batch);scheduler.startFromUser(if(batch) null else id)}}
    fun retrySchedule(id:String,batch:Boolean=false)=act {planned.enqueue(id,batch)}
    fun useMkv(id:String) = act {SubmissionHandoff.run {repository.useMkv(id);scheduler.startFromUser(id)};confirmations.send(Unit)}
    fun pause(id: String) = act { repository.pause(id) }
    fun resume(id: String) = act {
        val row=repository.dao.get(id)
        if(row?.error==ErrorCode.PLAYLIST_DETECTED.name) {
            val request=row.request?.let {runCatching {DownloadRequest.parse(it)}.getOrNull()}
            playlists.open(row.url,PlaylistPolicy(audio=request?.audioOnly==true,output=if(request?.output=="mp3") "mp3" else "original",
                height=request?.height?.takeIf {it in listOf(360,480,720,1080)} ?: 720,
                fallback=if(request?.height==0) QualityFallback.BEST else QualityFallback.AT_MOST,bitrate=request?.bitrate ?: 192))
        } else SubmissionHandoff.run {repository.prepareForSubmission(id); scheduler.startFromUser(id)}
    }
    fun cancel(id: String) = act { repository.cancel(id);planned.cancel(id) }
    fun remove(id: String) = act { repository.remove(id) }
    fun deleteFile(entity: DownloadEntity) = act { fileAction {storage.deleteFile(entity)} }
    fun rename(entity: DownloadEntity, name: String) = act { fileAction {storage.rename(entity,name)} }
    private suspend fun fileAction(block:suspend ()->Unit) {
        try {block()}
        catch(error:FileInUseException) {throw MediaException(ErrorCode.FILE_IN_USE)}
        catch(error:FileNameConflictException) {throw MediaException(ErrorCode.FILE_NAME_CONFLICT)}
    }
    fun textSetting(key: String, value: String) = act { settingsRepository.text(key,value) }
    fun integerSetting(key: String, value: Int) = act { settingsRepository.integer(key,value) }
    fun booleanSetting(key: String, value: Boolean) = act { settingsRepository.boolean(key,value) }
    fun notificationSetting(key:String,value:Boolean)=act {settingsRepository.notification(key,value)}
    fun completeOnboarding() = act { settingsRepository.completeOnboarding() }
    fun continueQueue() = act { scheduler.startFromUser() }
    fun batchItems(batchId:String): kotlinx.coroutines.flow.Flow<List<DownloadEntity>> = repository.dao.batchItemsFlow(batchId)
    fun pauseBatch(id:String)=act {playlistRepository.pause(id)}
    fun resumeBatch(id:String,retry:Boolean=false)=act {SubmissionHandoff.run {playlistRepository.resume(id,retry);scheduler.startFromUser()}}
    fun cancelBatch(id:String)=act {playlistRepository.cancel(id);planned.cancel(id,true)}
    fun removeCancelledBatch(id:String,onRemoved:()->Unit={})=act {
        if(playlistRepository.removeCancelled(id)) {planned.cancel(id,true);onRemoved()}
    }
    fun prioritize(id:String)=act {repository.dao.priority(id,1)}
    fun diagnostics() = act { mutable.update { it.copy(engine=extractor.version()) } }
    fun clearError() { mutable.update { it.copy(error=null,message=false) } }
    fun reportError(code: ErrorCode) { mutable.update { it.copy(error=code) } }
    fun clearCache() = act { downloads.value.filter { it.status in listOf("COMPLETED","CANCELLED") }.forEach { storage.removeTemporary(it.id) } }
    private fun act(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { mutable.update { it.copy(error=(e as? MediaException)?.code ?: if(e is SecurityException) ErrorCode.STORAGE_PERMISSION_ERROR else ErrorCode.INTERNAL_ENGINE_ERROR) } } } }
    override fun onCleared() { retainedSubmission=null; extractor.cancel(request);playlists.cancelDiscovery(); super.onCleared() }
}
