package com.zdownloader.app.extraction

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ExtractionSelection(val source:ExtractionSource?=null,val loading:Boolean=false,val error:Int?=null)
class AudioExtractionViewModel(application:Application):AndroidViewModel(application) {
    internal val repository=(application as ZApplication).audioExtraction
    private val mutable=MutableStateFlow(ExtractionSelection())
    internal val selection=mutable.asStateFlow()
    private var probe:Job?=null
    init {viewModelScope.launch {runCatching {repository.recover()}}}
    internal fun select(uri:Uri) {
        probe?.cancel()
        probe=viewModelScope.launch {
            mutable.value=ExtractionSelection(loading=true)
            try {mutable.value=ExtractionSelection(repository.engine.inspect(uri))}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {mutable.value=ExtractionSelection(error=(e as? ExtractionFailure)?.messageId ?: R.string.extract_unreadable)}
        }
    }
    internal fun problem(id:Int?) {mutable.value=mutable.value.copy(error=id)}
    internal fun start(track:Int,output:AudioOutput,bitrate:Int,export:Uri?=null) {
        val source=selection.value.source ?: return
        val selected=source.tracks.firstOrNull {it.index==track} ?: return
        viewModelScope.launch {
            try {repository.start(source,selected,ExtractionPolicy.plan(selected,output,bitrate),export)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {problem((e as? ExtractionFailure)?.messageId ?: R.string.extract_background_error)}
        }
    }
    internal fun retrySave(export:Uri?=null) {viewModelScope.launch {try {repository.retrySave(export)} catch(e:CancellationException) {throw e} catch(_:Exception) {problem(R.string.extract_save_error)}}}
    internal fun discard() {viewModelScope.launch {try {repository.discard()} catch(e:CancellationException) {throw e} catch(_:Exception) {problem(R.string.extract_save_error)}}}
}
