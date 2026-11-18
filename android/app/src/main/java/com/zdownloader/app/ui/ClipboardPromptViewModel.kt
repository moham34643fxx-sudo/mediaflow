package com.zdownloader.app.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zdownloader.app.data.ClipboardPreview
import com.zdownloader.app.data.ClipboardPreviewLoader
import com.zdownloader.app.domain.ClipboardLink
import com.zdownloader.app.domain.ClipboardLinkPrompt
import com.zdownloader.app.domain.QuickShareFormats
import com.zdownloader.app.domain.UrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

/** Called only from a resumed, focused Activity; no background clipboard observer. */
class ClipboardPromptViewModel(application:Application):AndroidViewModel(application) {
    private val preferences=application.getSharedPreferences("mediaflow_clipboard_prompt",Context.MODE_PRIVATE)
    private val policy=ClipboardLinkPrompt(preferences.getString("last_handled_fingerprint",null))
    private var persisted=policy.lastHandledFingerprint
    private var alreadyHandling:String?=null
    private val mutable=MutableStateFlow<ClipboardLink?>(null)
    val prompt=mutable.asStateFlow()
    private val mutablePreview=MutableStateFlow(ClipboardPreview())
    internal val preview=mutablePreview.asStateFlow()
    private var previewJob:Job?=null
    private var previewAttempted:String?=null

    /** The caller starts this only after the visible card's first frame. */
    internal fun loadPreview(candidate:ClipboardLink) {
        if(mutable.value?.fingerprint!=candidate.fingerprint||previewJob!=null||previewAttempted==candidate.fingerprint) return
        previewJob=viewModelScope.launch {
            try {
                val metadata=ClipboardPreviewLoader.metadata(candidate.url)
                if(mutable.value?.fingerprint!=candidate.fingerprint) return@launch
                mutablePreview.value=metadata
                metadata.imageUrl?.let {url->
                    val artwork=ClipboardPreviewLoader.artwork(url)
                    if(mutable.value?.fingerprint==candidate.fingerprint) mutablePreview.value=metadata.copy(artwork=artwork)
                }
            } catch(error:CancellationException) {throw error}
            catch(_:java.io.IOException) { /* Preview failure keeps the usable local card. */ }
            catch(_:com.zdownloader.app.domain.MediaException) { /* A preview cannot loosen URL policy. */ }
            catch(_:IllegalArgumentException) { /* Unsupported/oversized artwork keeps the placeholder. */ }
            finally {
                if(currentCoroutineContext().isActive&&mutable.value?.fingerprint==candidate.fingerprint) previewAttempted=candidate.fingerprint
            }
        }
    }

    internal fun pausePreview() {previewJob?.cancel();previewJob=null}
    private fun withdrawPreview() {pausePreview();previewAttempted=null;mutablePreview.value=ClipboardPreview()}

    fun onForegroundWindow() {
        try {
            val clipboard=getApplication<Application>().getSystemService(ClipboardManager::class.java)
            val description=clipboard.primaryClipDescription
            val clip=if(description!=null&&!sensitive(description)&&description.hasMimeType("text/*")) clipboard.primaryClip else null
            val text=clip?.takeIf {it.itemCount==1&&!sensitive(it.description)}?.getItemAt(0)?.text
            val lastHandledUrl=preferences.getString("last_handled_url",null)
            val activeAlreadyHandling=alreadyHandling ?: lastHandledUrl
            policy.offer(text?.takeIf {it.length<=UrlPolicy.MAX_INPUT_LENGTH}?.toString(),clip?.description?.let(::copiedAt) ?: 0,activeAlreadyHandling)
            alreadyHandling=null
            if(lastHandledUrl!=null) preferences.edit().remove("last_handled_url").apply()
            persistHandled()
            if(mutable.value?.fingerprint!=policy.pending?.fingerprint) withdrawPreview()
            mutable.value=policy.pending
        } catch(error:SecurityException) {
            // Focus can be revoked between the Activity check and Android's read.
            policy.withdraw();mutable.value=null;withdrawPreview()
        }
    }

    fun handlingSharedText(text:String) {
        alreadyHandling=text.takeIf {it.length<=UrlPolicy.MAX_INPUT_LENGTH}?.let {QuickShareFormats.sharedUrl(listOf(it))}
        policy.withdraw();mutable.value=null;withdrawPreview()
    }

    fun consume() {policy.consume();persistHandled();mutable.value=null;withdrawPreview()}

    private fun sensitive(description:ClipDescription)=description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)==true
    // Older descriptions cannot distinguish re-copies; focus alone must not replay ignored text.
    private fun copiedAt(description:ClipDescription)=if(Build.VERSION.SDK_INT>=26) description.timestamp else 0L
    private fun persistHandled() {
        if(persisted==policy.lastHandledFingerprint) return
        persisted=policy.lastHandledFingerprint
        preferences.edit().putString("last_handled_fingerprint",persisted).apply()
    }

    companion object {
        fun markHandled(context:Context,url:String) {
            if(url.isBlank()) return
            val normalized=runCatching {QuickShareFormats.sharedUrl(listOf(url)) ?: UrlPolicy.normalize(url)}.getOrDefault(url)
            val prefs=context.applicationContext.getSharedPreferences("mediaflow_clipboard_prompt",Context.MODE_PRIVATE)
            prefs.edit().putString("last_handled_url",normalized).apply()
        }
    }
}
