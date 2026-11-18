package com.zdownloader.app.status

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteFullException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class StatusUiState(val source:StatusSource=StatusSource.WHATSAPP,val items:List<StatusMedia> = emptyList(),
    val loading:Boolean=false,val problem:StatusProblem?=null,val error:StatusProblem?=null,val skipped:Int=0,val limited:Boolean=false,
    val saving:Set<String> = emptySet(),val notice:StatusSaved?=null,val sessionOnly:Boolean=false,val preview:StatusMedia?=null)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> selectedSourceChanges(
    source:Flow<StatusSource>,
    selection:Flow<T>,
    changes:(StatusSource,T)->Flow<Unit>
):Flow<Unit> = combine(source.distinctUntilChanged(),selection.distinctUntilChanged()) {current,selected->current to selected}
    .flatMapLatest {(current,selected)->changes(current,selected)}

internal fun <T> applyCommittedImports(imported:MutableStateFlow<List<T>>,state:MutableStateFlow<StatusUiState>,
    selection:List<T>,sessionOnly:Boolean,selectImports:Boolean=false,selectDefaultWhenEmpty:Boolean=false):Boolean {
    val previous=state.value.source
    imported.value=selection.distinct()
    when {
        selectImports -> state.update {it.copy(source=StatusSource.IMPORTS,items=emptyList(),problem=null,preview=null,sessionOnly=sessionOnly,error=null)}
        imported.value.isEmpty()&&(selectDefaultWhenEmpty||state.value.source==StatusSource.IMPORTS) ->
            state.update {it.copy(source=StatusSource.WHATSAPP,items=emptyList(),preview=null,sessionOnly=false,error=null)}
        state.value.source==StatusSource.IMPORTS -> state.update {it.copy(sessionOnly=sessionOnly)}
    }
    return previous!=state.value.source
}

@HiltViewModel class StatusSaverViewModel @Inject constructor(val permissions:StatusPermissions,
    private val scanner:StatusMediaScanner,private val saves:StatusSaveCoordinator,settingsRepository:SettingsRepository):ViewModel() {
    private val mutable=MutableStateFlow(StatusUiState(loading=true,source=if(!permissions.installed(StatusSource.WHATSAPP)&&permissions.installed(StatusSource.BUSINESS)) StatusSource.BUSINESS else StatusSource.WHATSAPP))
    val state=mutable.asStateFlow()
    val settings=settingsRepository.flow.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),Settings())
    private var scanning:Job?=null
    private val imported=MutableStateFlow(permissions.importedFiles())
    private val permissionMutations=Mutex()
    private val mutationGeneration=AtomicLong()
    fun sources():List<StatusSource> = buildList {
        add(StatusSource.WHATSAPP)
        if(permissions.installed(StatusSource.BUSINESS)||permissions.configured(StatusSource.BUSINESS)!=null) add(StatusSource.BUSINESS)
        if(imported.value.isNotEmpty()) add(StatusSource.IMPORTS)
    }
    fun select(source:StatusSource) {if(mutable.value.source!=source) {mutable.update {it.copy(source=source,items=emptyList(),preview=null,problem=null)};refresh()}}
    suspend fun observeChanges() {
        selectedSourceChanges(mutable.map {it.source},imported,scanner::changes)
            .collect {refresh()}
    }
    fun refresh() {
        scanning?.cancel()
        val source=mutable.value.source;val files=imported.value
        scanning=viewModelScope.launch {
            mutable.update {it.copy(loading=true,problem=null)}
            try {
                saves.recover()
                val scan=scanner.scan(source,files)
                val items=scan.items.map {item->item.copy(savedUri=saves.savedUri(item))}
                currentCoroutineContext().ensureActive()
                if(mutable.value.source==source&&(source!=StatusSource.IMPORTS||imported.value==files)) mutable.update {state->state.copy(items=items,loading=false,skipped=scan.skipped,limited=scan.limited,problem=null,
                    preview=state.preview?.let {preview->items.firstOrNull {it.key==preview.key} ?: preview.takeIf {it.savedUri!=null}})}
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                if(mutable.value.source==source&&(source!=StatusSource.IMPORTS||imported.value==files))
                    mutable.update {it.copy(loading=false,items=emptyList(),preview=null,problem=problem(error))}
            }
        }
    }
    private suspend fun reconcileAfterCancellation(source:StatusSource?,syncImports:Boolean) {
        withContext(NonCancellable) {
            val changed=if(syncImports) {
                val saved=withContext(Dispatchers.IO) {permissions.importedFiles()}
                applyCommittedImports(imported,mutable,saved,permissions.importsAreSessionOnly())
            } else false
            if(source!=null&&mutable.value.source==source) mutable.update {it.copy(sessionOnly=permissions.sourceIsSessionOnly(source))}
            if(changed||source==null||mutable.value.source==source||syncImports&&mutable.value.source==StatusSource.IMPORTS) refresh()
        }
    }
    fun chooseFolder(source:StatusSource,uri:Uri) {
        val operation=mutationGeneration.incrementAndGet()
        viewModelScope.launch {
            try {
                permissionMutations.withLock {
                    val persistent=permissions.choose(source,uri)
                    currentCoroutineContext().ensureActive()
                    if(mutable.value.source==source) {
                        mutable.update {it.copy(sessionOnly=!persistent,error=null)}
                        refresh()
                    }
                }
            } catch(error:CancellationException) {reconcileAfterCancellation(source,false);throw error}
            catch(error:Exception) {reportMutationError(operation,source,error)}
        }
    }
    fun forget() {
        val source=mutable.value.source;val operation=mutationGeneration.incrementAndGet()
        viewModelScope.launch {
            try {
                permissionMutations.withLock {
                    permissions.forget(source)
                    currentCoroutineContext().ensureActive()
                    if(mutable.value.source==source) {
                        mutable.update {it.copy(sessionOnly=false)}
                        refresh()
                    }
                }
            } catch(error:CancellationException) {reconcileAfterCancellation(source,false);throw error}
            catch(error:Exception) {reportMutationError(operation,source,error)}
        }
    }
    fun importFiles(uris:List<Uri>) {
        val chosen=uris.filter {it.scheme=="content"}.distinct().take(StatusRules.MAX_ITEMS)
        if(chosen.isEmpty()) {mutable.update {it.copy(error=StatusProblem.UNSUPPORTED)};return}
        val operation=mutationGeneration.incrementAndGet()
        viewModelScope.launch {
            try {
                permissionMutations.withLock {
                    val sessionOnly=permissions.replaceImports(chosen)
                    currentCoroutineContext().ensureActive()
                    // A successful repository commit is authoritative even when a newer
                    // UI operation makes this request's navigation outcome obsolete.
                    val select=operation==mutationGeneration.get()
                    val changed=applyCommittedImports(imported,mutable,permissions.importedFiles(),sessionOnly,selectImports=select)
                    if(select||changed) refresh()
                }
            } catch(error:CancellationException) {reconcileAfterCancellation(null,true);throw error}
            catch(error:Exception) {reportMutationError(operation,null,error)}
        }
    }
    fun clearImports() {
        val operation=mutationGeneration.incrementAndGet()
        viewModelScope.launch {
            try {
                permissionMutations.withLock {
                    permissions.rememberImports(emptyList())
                    currentCoroutineContext().ensureActive()
                    val latest=operation==mutationGeneration.get()
                    val changed=applyCommittedImports(imported,mutable,emptyList(),false,selectDefaultWhenEmpty=latest)
                    if(latest||changed) refresh()
                }
            } catch(error:CancellationException) {reconcileAfterCancellation(null,true);throw error}
            catch(error:Exception) {reportMutationError(operation,null,error)}
        }
    }
    private fun reportMutationError(operation:Long,source:StatusSource?,error:Exception) {
        if(operation==mutationGeneration.get()&&(source==null||mutable.value.source==source))
            mutable.update {it.copy(error=problem(error))}
    }
    fun preview(item:StatusMedia?) {mutable.update {it.copy(preview=item)}}
    fun leaveScreen() {scanning?.cancel();mutable.update {it.copy(loading=false,preview=null)}}
    fun previewFailed() {mutable.update {it.copy(preview=null,error=StatusProblem.SOURCE_UNAVAILABLE)}}
    fun clearError() {mutable.update {it.copy(error=null)}}
    fun clearNotice() {mutable.update {it.copy(notice=null)}}
    fun save(item:StatusMedia,export:Uri?=null) {
        if(item.key in mutable.value.saving) return
        mutable.update {it.copy(saving=it.saving+item.key)}
        viewModelScope.launch {
            try {
                val result=saves.save(item,export)
                mutable.update {state->state.copy(items=state.items.map {if(it.key==item.key) it.copy(savedUri=result.uri) else it},
                    preview=state.preview?.let {if(it.key==item.key) it.copy(savedUri=result.uri) else it},notice=result)}
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {mutable.update {it.copy(error=problem(error))}}
            finally {mutable.update {it.copy(saving=it.saving-item.key)}}
        }
    }
    companion object {
        fun problem(error:Exception):StatusProblem=when(error) {
            is StatusException->error.problem
            is SecurityException->StatusProblem.ACCESS_REVOKED
            is java.io.FileNotFoundException->StatusProblem.SOURCE_UNAVAILABLE
            is SQLiteFullException->StatusProblem.NO_SPACE
            else->if(generateSequence(error as Throwable?) {it.cause}.any {it.message?.contains("ENOSPC")==true}) StatusProblem.NO_SPACE else StatusProblem.SAVE_FAILED
        }
    }
}
