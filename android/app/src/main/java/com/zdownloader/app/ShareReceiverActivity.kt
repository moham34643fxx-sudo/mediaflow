package com.zdownloader.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zdownloader.app.domain.QuickShareFormats
import com.zdownloader.app.ui.ClipboardPromptViewModel
import com.zdownloader.app.ui.QuickShareSheet
import com.zdownloader.app.ui.QuickShareViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** A normal translucent Android task, never a draw-over-other-apps overlay. */
@AndroidEntryPoint class ShareReceiverActivity:ComponentActivity() {
    private val model:QuickShareViewModel by viewModels()
    private var traceId=""
    private fun shareContract(incoming:Intent):Intent {
        // SavedStateHandle defaults come from Intent extras. A sender supplies a
        // share payload, never our queuedId/persisted request/operation state.
        val text=runCatching {incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()}.getOrNull()
        return Intent(incoming).replaceExtras(Bundle().apply {if(text!=null) putString(Intent.EXTRA_TEXT,text)})
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        setIntent(shareContract(intent))
        traceId=java.util.UUID.randomUUID().toString()
        com.zdownloader.app.engine.AnalysisTrace.mark(traceId,"activity_create_start")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {QuickShareSheet(model,close={dismissShare()})}
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map {it.queuedId}.filterNotNull().distinctUntilChanged().collect {
                    if(!isFinishing) {
                        val configuration=android.content.res.Configuration(resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(model.settings.value.language))
                        }
                        val localized=createConfigurationContext(configuration)
                         Toast.makeText(localized,localized.getString(R.string.download_added),Toast.LENGTH_SHORT).show()
                        ClipboardPromptViewModel.markHandled(this@ShareReceiverActivity,model.state.value.sourceUrl.orEmpty())
                        finish()
                    }
                }
            }
        }
        receive(intent)
        com.zdownloader.app.engine.AnalysisTrace.mark(traceId,"activity_create_end")
    }
    override fun onNewIntent(intent:Intent) {
        val shared=shareContract(intent)
        super.onNewIntent(shared);setIntent(shared)
        traceId=java.util.UUID.randomUUID().toString()
        receive(shared)
    }
    private fun receive(intent:Intent?) {
        com.zdownloader.app.engine.AnalysisTrace.checkpoint(traceId,"intent_received")
        val url=runCatching {
            val incoming=intent ?: return@runCatching null
            if(incoming.action !in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE) ||
                incoming.type?.let {it !in setOf("text/plain","text/uri-list")}==true) return@runCatching null
            val texts=buildList {
                incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::add)
                incoming.clipData?.let {clip->repeat(minOf(clip.itemCount,32)) {i->
                    clip.getItemAt(i).text?.toString()?.let(::add)
                    clip.getItemAt(i).uri?.toString()?.let(::add)
                }}
                incoming.dataString?.let(::add)
            }
            QuickShareFormats.sharedUrl(texts)
        }.getOrNull()
        com.zdownloader.app.engine.AnalysisTrace.mark(traceId,"url_normalized")
        model.accept(url,traceId)
        (application as ZApplication).extractor.prewarmForShare(url,traceId)
    }
    private fun dismissShare() {
        if(model.canDismiss()) {model.cancelAnalysis();finish()}
    }
}
