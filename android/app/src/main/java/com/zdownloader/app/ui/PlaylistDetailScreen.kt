package com.zdownloader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.data.BatchSummary
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.PlaylistPolicy
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.MediaSize
import com.zdownloader.app.domain.SizeCoverage
import com.zdownloader.app.domain.durationOutput
import kotlinx.coroutines.CancellationException
import com.zdownloader.app.ui.spectrumStringResource as stringResource

private val workingPlaylistStates=setOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","PROCESSING","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING","PAUSING")
@Composable internal fun PlaylistDetailScreen(batchId:String,summary:BatchSummary?,model:ZViewModel,play:(DownloadEntity)->Unit,onBack:()->Unit) {
    BackHandler(onBack=onBack)
    val savedItems by model.batchItems(batchId).collectAsStateWithLifecycle(emptyList())
    val items=remember(savedItems) {savedItems.sortedWith(compareBy<DownloadEntity> {it.playlistPosition}.thenBy {it.createdAt})}
    val policy=remember(summary?.batch?.policy) {runCatching {PlaylistPolicy.parse(summary?.batch?.policy.orEmpty())}.getOrDefault(PlaylistPolicy())}
    val catalogs by model.sizePreviews.state.collectAsStateWithLifecycle()
    val storedSizes=remember(items.map {it.id to it.selection}) {items.associate {it.id to runCatching {Selection.parse(it.selection).outputSize}.getOrNull()}}
    val outputSizes=remember(items,policy,catalogs,storedSizes) {items.associate {entity->entity.id to
        (entity.outputSize?.takeIf {entity.status=="COMPLETED"}?.let {MediaSize(it,false)} ?: storedSizes[entity.id]
            ?: model.sizePreviews.forecast(entity.url,policy,entity.duration).output)}}
    val coverage=remember(items,outputSizes) {SizeCoverage.of(items.filter {it.status!="CANCELLED"}.map {outputSizes[it.id]})}
    LaunchedEffect(batchId,items.map {Triple(it.id,it.url,it.status)},policy) {
        for(entity in items.filter {it.selection.isBlank()&&it.status=="PAUSED"}) {
            if(policy.durationOutput(entity.duration)==null) try {model.sizePreviews.inspect(entity.url)}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) { /* The per-item unknown remains explicit. */ }
        }
    }
    val context=LocalContext.current
    PlaylistDetailContent(batchId,summary,items,policy,outputSizes,coverage,
        PlaylistDetailActions(play,{openFile(context,it,false)},model::pause,model::resume,model::cancel,
            {model.pauseBatch(batchId)},{model.resumeBatch(batchId)},{model.cancelBatch(batchId)},
            {model.resumeBatch(batchId,true)},{model.startScheduled(batchId,true)},
            {model.removeCancelledBatch(batchId,onBack)}),onBack)
}

@Composable internal fun playlistItemState(entity:DownloadEntity,currentPosition:Int?):String=when {
    entity.status=="DOWNLOADING"&&entity.selection.isBlank()->stringResource(R.string.pl_analyzing_item)
    entity.status in setOf("QUEUED","RESUMING")&&currentPosition!=null&&entity.playlistPosition>currentPosition->stringResource(R.string.pl_waiting_for_previous,currentPosition)
    entity.status=="RETRYING"&&entity.error==ErrorCode.RATE_LIMITED.name->stringResource(R.string.pl_provider_cooldown)
    entity.status=="WAITING_FOR_NETWORK"&&entity.error!=null->stringResource(errorResource(ErrorCode.parse(entity.error)))
    else->statusLabel(entity.status)
}
