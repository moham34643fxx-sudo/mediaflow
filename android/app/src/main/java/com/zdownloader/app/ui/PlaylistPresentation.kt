package com.zdownloader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.data.BatchSummary
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.spectrumStringResource as text

internal data class PlaylistDetailActions(
    val play:(DownloadEntity)->Unit,val open:(DownloadEntity)->Unit,
    val pauseItem:(String)->Unit,val resumeItem:(String)->Unit,val cancelItem:(String)->Unit,
    val pauseAll:()->Unit,val resumeAll:()->Unit,val cancelAll:()->Unit,val retryFailed:()->Unit,val startScheduled:()->Unit,
    val removePlaylist:()->Unit={}
)

private val playlistWorking=setOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","PROCESSING","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING","PAUSING")
private val playlistWaiting=setOf("QUEUED","RESUMING","RETRYING","WAITING_FOR_NETWORK","SCHEDULED")
private fun cleanPlaylistTitle(value:String)=value.replace(Regex("\\S*[\\u2580-\\u259F]\\S*")," ").replace(Regex("\\s+")," ").trim()
private fun playlistMatches(item:DownloadEntity,filter:String)=when(filter) {
    "active"->item.status in playlistWorking
    "waiting"->item.status in playlistWaiting
    "done"->item.status=="COMPLETED"
    "failed"->item.status=="FAILED"
    "paused"->item.status in setOf("PAUSED","INTERRUPTED")
    "cancelled"->item.status=="CANCELLED"
    else->true
}

/** UI-only state: search and filters never reorder, mutate or restart the actual queue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlaylistDetailContent(batchId:String,summary:BatchSummary?,items:List<DownloadEntity>,policy:PlaylistPolicy,
    sizes:Map<String,MediaSize?>,coverage:SizeCoverage,actions:PlaylistDetailActions,onBack:()->Unit) {
    var filter by rememberSaveable(batchId) {mutableStateOf("all")}
    var query by rememberSaveable(batchId) {mutableStateOf("")}
    var confirmation by remember(batchId) {mutableStateOf<String?>(null)}
    val visible=remember(items,filter,query) {items.filter {playlistMatches(it,filter)&&(query.isBlank()||cleanPlaylistTitle(it.title).contains(query.trim(),ignoreCase=true))}}
    val current=items.firstOrNull {it.status in playlistWorking}
    Scaffold(containerColor=Color.Transparent,topBar={
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(FlowIcons.Back,text(R.string.back),onBack,Modifier.testTag("playlist_detail_back"))
            Text(text(R.string.pl_playlist_details),Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.titleLarge)
        }
    }) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("playlist_detail_list"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item("summary") {
                FlowPanel(Modifier.testTag("playlist_detail_header_card")) {
                    Row(Modifier.flowRibbon(MaterialTheme.colorScheme.primary),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        MediaArtwork(summary?.batch?.thumbnail,summary?.batch?.title.orEmpty(),Modifier.size(80.dp),audio=policy.audio)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            FlowEyebrow(text(if(policy.audio) R.string.pl_audio_playlist else R.string.pl_video_playlist),if(policy.audio) FlowIcons.Audio else FlowIcons.Video)
                            Text(summary?.batch?.title.orEmpty(),style=MaterialTheme.typography.titleLarge,maxLines=4,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    Text(text(if(summary?.batch?.concurrency==1) R.string.pl_order_sequential else R.string.pl_order_parallel),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(summary!=null) PlaylistProgressOverview(summary)
                    OutputSizeSummary(coverage,Modifier.testTag("playlist_detail_expected_size"))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        PlaylistCount(text(R.string.completed),items.count {it.status=="COMPLETED"},flowSuccessColor())
                        PlaylistCount(text(R.string.queued),items.count {it.status in playlistWaiting},MaterialTheme.colorScheme.primary)
                        val failed=items.count {it.status=="FAILED"}
                        if(failed>0) PlaylistCount(text(R.string.failed),failed,MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(summary!=null&&!summary.finished&&summary.batch.state!="CANCELLED") {
                            when(summary.status) {
                                "SCHEDULED"->FilledTonalButton(actions.startScheduled,Modifier.testTag("playlist_action_start")) {Text(text(R.string.schedule_start_now))}
                                "PAUSED"->FilledTonalButton(actions.resumeAll,Modifier.testTag("playlist_action_resume")) {Icon(FlowIcons.Play,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(text(R.string.pl_resume_all))}
                                else->FilledTonalButton(actions.pauseAll,Modifier.testTag("playlist_action_pause")) {Icon(FlowIcons.Pause,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(text(R.string.pl_pause_all))}
                            }
                        }
                        if(summary?.failed?.let {it>0}==true&&summary.batch.state!="CANCELLED") OutlinedButton(actions.retryFailed,Modifier.testTag("playlist_action_retry_failed")) {Text(text(R.string.pl_retry_failed_short))}
                        if(summary!=null&&!summary.finished&&summary.batch.state!="CANCELLED") TextButton({confirmation="cancel"},Modifier.testTag("playlist_action_cancel")) {Text(text(R.string.pl_cancel_all))}
                        if(summary?.canRemoveCancelled==true) TextButton({confirmation="remove"},Modifier.testTag("playlist_action_remove")) {Icon(FlowIcons.Delete,null);Spacer(Modifier.width(6.dp));Text(text(R.string.pl_remove_cancelled))}
                    }
                }
            }
            if(current!=null) item("current") {
                Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.35f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        FlowIconTile(FlowIcons.Downloads)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(text(R.string.pl_current_position,current.playlistPosition),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            Text(cleanPlaylistTitle(current.title),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(playlistItemState(current,null),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("search") {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(text(R.string.pl_files_heading,visible.size,items.size),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().testTag("playlist_search"),singleLine=true,
                        label={Text(text(R.string.pl_search_files))},leadingIcon={Icon(FlowIcons.Search,null)},
                        trailingIcon={if(query.isNotEmpty()) FlowIconButton(FlowIcons.Close,text(R.string.pl_clear_search),{query=""})},shape=RoundedCornerShape(16.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("all" to R.string.all,"active" to R.string.active,"waiting" to R.string.queued,"done" to R.string.completed,"paused" to R.string.paused,"failed" to R.string.failed,"cancelled" to R.string.cancelled).forEach {(key,label)->
                            val count=items.count {playlistMatches(it,key)}
                            FlowFilterChip(filter==key,{filter=key},label={Text("${text(label)}  $count")},modifier=Modifier.testTag("playlist_items_filter_$key"))
                        }
                    }
                }
            }
            if(visible.isEmpty()) item("empty") {
                if(items.isEmpty()&&summary?.selected?.let {it>0}==true) FlowLoadingIndicator(Modifier.padding(vertical=24.dp))
                else FlowEmptyState(text(R.string.pl_no_matching_items),text(R.string.pl_search_hint),FlowIcons.Search)
            }
            itemsIndexed(visible,key={_,item->item.id},contentType={_,_->"playlist_file"}) {index,item->
                PlaylistFileCard(item,item.playlistPosition.takeIf {it>0} ?: index+1,policy,current?.playlistPosition,sizes[item.id],actions)
            }
        }
    }
    confirmation?.let {action->PlaylistRemovalDialog(action=="remove",{confirmation=null}) {confirmation=null;if(action=="remove") actions.removePlaylist() else actions.cancelAll()}}
}

@Composable private fun PlaylistCount(label:String,count:Int,tint:Color) {
    Surface(shape=RoundedCornerShape(10.dp),color=tint.copy(alpha=.08f)) {
        Text("$count  $label",Modifier.padding(horizontal=10.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium,color=tint)
    }
}

/** Network progress retains the existing exact/estimated/unknown and finalizing semantics. */
@Composable internal fun PlaylistProgressOverview(summary:BatchSummary) {
    val progress=summary.progress
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text(R.string.pl_completed_fraction,summary.completed,summary.selected),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            progress.percent?.let {Text(text(if(progress.estimated&&!progress.successful) R.string.pl_estimated_percent else R.string.pl_percent,it),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
        }
        if(progress.indeterminate&&summary.status=="ACTIVE") FlowLoadingIndicator(Modifier.testTag("playlist_aggregate_progress"))
        else if(progress.successful||progress.fraction!=null) MediaFlowProgress(if(progress.successful) 1f else progress.fraction ?: 0f,Modifier.testTag("playlist_aggregate_progress"))
        else {
            val unknown=text(R.string.pl_size_unknown)
            Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest,RoundedCornerShape(8.dp))
                .testTag("playlist_aggregate_progress").semantics {contentDescription=unknown})
        }
        Text(if(progress.expected!=null) text(R.string.pl_transfer_amount,bytesLabel(progress.bytes),(if(progress.estimated) "≈ " else "")+bytesLabel(progress.expected)) else text(R.string.pl_transferred,bytesLabel(progress.bytes)),
            Modifier.testTag("playlist_bytes_${summary.batch.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(summary.status=="PARTIAL") text(R.string.pl_partial) else if(progress.finalizing&&summary.status=="ACTIVE") text(R.string.pl_finalizing) else if(summary.status=="ACTIVE") text(R.string.pl_batch_notice) else statusLabel(summary.status),
            Modifier.testTag("playlist_operation_${summary.batch.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlaylistFileCard(entity:DownloadEntity,position:Int,policy:PlaylistPolicy,currentPosition:Int?,outputSize:MediaSize?,actions:PlaylistDetailActions) {
    var expanded by rememberSaveable(entity.id) {mutableStateOf(false)}
    val selection=remember(entity.selection) {runCatching {Selection.parse(entity.selection)}.getOrNull()}
    val working=entity.status in playlistWorking
    val transferring=entity.status.startsWith("DOWNLOADING")
    val failed=entity.status=="FAILED"
    val done=entity.status=="COMPLETED"
    val tint=when {done->flowSuccessColor();failed->MaterialTheme.colorScheme.error;working->MaterialTheme.colorScheme.primary;else->MaterialTheme.colorScheme.onSurfaceVariant}
    Surface(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().testTag("playlist_item_${entity.id}"),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,if(working||failed) tint.copy(alpha=.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
        Column(Modifier.padding(14.dp).testTag("playlist_child_${entity.id}"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box {
                    MediaArtwork(entity.thumbnail,entity.title,Modifier.size(58.dp),entity.uri,policy.audio)
                    Surface(Modifier.align(Alignment.BottomEnd),shape=RoundedCornerShape(topStart=8.dp,bottomEnd=12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(position.toString(),Modifier.padding(horizontal=7.dp,vertical=3.dp),style=MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(cleanPlaylistTitle(entity.title),style=MaterialTheme.typography.titleSmall,maxLines=if(expanded) 8 else 2,overflow=TextOverflow.Ellipsis)
                    val format=selection?.output?.uppercase() ?: "MP3".takeIf {policy.audio&&policy.output=="mp3"}
                    val subtitle=listOfNotNull(format,entity.duration?.let(::duration)).joinToString(" · ")
                    if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(done) FlowIconButton(FlowIcons.Play,text(R.string.play),{actions.play(entity)},Modifier.testTag("playlist_play_${entity.id}"),accent=true)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FlowStatusIcon(entity.status,Modifier.size(15.dp))
                    Text(playlistItemState(entity,currentPosition),style=MaterialTheme.typography.labelMedium,color=tint)
                }
                Text(sizeLabel(outputSize),Modifier.testTag("playlist_detail_item_size_${entity.playlistPosition}"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(working) {
                if(transferring&&entity.total!=null&&entity.total>0) MediaFlowProgress((entity.bytes.toFloat()/entity.total).coerceIn(0f,.99f))
                else if(!transferring) AudioProcessingIndicator(entity.id,entity.status) else FlowLoadingIndicator()
                FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(entity.bytes>0) Text(bytesLabel(entity.bytes)+(entity.total?.let {" / "+bytesLabel(it)} ?: ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(transferring&&entity.speed>0) Text(text(R.string.pl_rate,bytesLabel(entity.speed)),style=MaterialTheme.typography.labelMedium,color=tint)
                }
            }
            if(failed) Text(text(errorResource(ErrorCode.parse(entity.error))),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(expanded) {
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
                Column(Modifier.testTag("playlist_file_details_${entity.id}"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(entity.filename.isNotBlank()) PlaylistFileInfo(text(R.string.pl_filename),entity.filename)
                    PlaylistFileInfo(text(R.string.pl_folder),entity.relativeFolder ?: text(R.string.default_folder))
                    if(entity.retries>0) Text(text(R.string.pl_retry_count,entity.retries),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(entity.status=="RETRYING"&&entity.nextAttempt>0) Text(text(R.string.pl_retry_at,java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(entity.nextAttempt))),style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(done) OutlinedButton({actions.open(entity)}) {Icon(FlowIcons.Open,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(text(R.string.open))}
                        else if(entity.status!="CANCELLED") {
                            if(entity.status in setOf("PAUSED","INTERRUPTED","FAILED")) FilledTonalButton({actions.resumeItem(entity.id)},Modifier.testTag("playlist_resume_${entity.id}")) {Text(text(if(failed) R.string.retry else R.string.resume))}
                            else OutlinedButton({actions.pauseItem(entity.id)},Modifier.testTag("playlist_pause_${entity.id}")) {Text(text(R.string.pause))}
                            TextButton({actions.cancelItem(entity.id)},Modifier.testTag("playlist_cancel_${entity.id}")) {Text(text(R.string.cancel))}
                        }
                    }
                }
            }
            TextButton({expanded=!expanded},Modifier.align(Alignment.End).heightIn(min=48.dp).testTag("playlist_toggle_${entity.id}"),contentPadding=PaddingValues(horizontal=8.dp)) {
                Text(text(if(expanded) R.string.pl_hide_details else R.string.details));Spacer(Modifier.width(4.dp));Icon(if(expanded) FlowIcons.Close else FlowIcons.Next,null,Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun PlaylistFileInfo(label:String,value:String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium)
    }
}
