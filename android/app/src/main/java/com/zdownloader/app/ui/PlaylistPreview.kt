package com.zdownloader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zdownloader.app.R
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.data.BatchSummary
import com.zdownloader.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zdownloader.app.ui.spectrumStringResource as text

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlaylistPreview(controller:PlaylistController,single:()->Unit,close:()->Unit,queued:()->Unit,selectQuality:(String)->Unit) {
    SecondaryNavigationOverlay()
    val state by controller.state.collectAsStateWithLifecycle()
    var audio by rememberSaveable(state.sessionId) {mutableStateOf(state.initialPolicy.audio)}
    var output by rememberSaveable(state.sessionId) {mutableStateOf(state.initialPolicy.output)}
    var height by rememberSaveable(state.sessionId) {mutableIntStateOf(state.initialPolicy.height)}
    var fallback by rememberSaveable(state.sessionId) {mutableStateOf(state.initialPolicy.fallback.name)}
    var concurrency by rememberSaveable {mutableIntStateOf(1)}
    var advanced by rememberSaveable {mutableStateOf(false)}
    var bitrate by rememberSaveable(state.sessionId) {mutableIntStateOf(state.initialPolicy.bitrate)}
    var numbered by rememberSaveable {mutableStateOf(true)}
    var skipExisting by rememberSaveable {mutableStateOf(true)}
    var schedule by remember {mutableStateOf(false)}
    var range by remember {mutableStateOf(false)}
    var first by remember {mutableStateOf("1")}
    var last by remember {mutableStateOf("10")}
    var mp3 by remember {mutableStateOf(false)}
    val policy=remember(audio,output,height,fallback,bitrate) {PlaylistPolicy(audio,output,height,QualityFallback.valueOf(fallback),bitrate)}
    LaunchedEffect(policy) {controller.reviewPolicy(policy)}
    LaunchedEffect(state.sessionId,state.page?.items,policy) {controller.estimateSizes(policy)}
    LaunchedEffect(Unit) {mp3=withContext(Dispatchers.IO) {runCatching {com.zdownloader.app.engine.LameEncoder.version().isNotBlank()}.getOrDefault(false)}}
    LaunchedEffect(state.queued) {if(state.queued!=null) queued()}
    BackHandler {if(!state.saving) close()}
    val enabled=!state.saving&&!state.persisted
    val sizes=state.sizes.takeIf {state.sizePolicy==policy}.orEmpty()
    val rowContext=remember(state.selected,enabled,sizes,policy) {PlaylistEntryContext(state.selected,enabled,sizes,policy)}
    val selectedEntries=remember(state.page,state.selected,state.duplicates,skipExisting) {
        state.page?.items.orEmpty().filter {it.key in state.selected&&(!skipExisting||it.key !in state.duplicates.matchingCompleted&&it.key !in state.duplicates.matchingActive)}
            .let {if(skipExisting) it.distinctBy(MediaIdentity::playlistKey) else it}
    }
    val selectedSize=remember(selectedEntries,sizes,policy) {SizeCoverage.of(selectedEntries.map {entry->
        (sizes[entry.key] ?: SizeForecast(policy.durationOutput(entry.duration),null,null)).output
    })}
    val currentPage=state.page
    val seasonGroups=remember(currentPage?.items) {currentPage?.items.orEmpty().filter {it.seasonNumber!=null}.groupBy {it.seasonNumber!!}.toSortedMap()}
    val groupedKeys=remember(seasonGroups) {seasonGroups.values.flatten().mapTo(mutableSetOf()) {it.key}}
    Surface(Modifier.fillMaxSize().background(mediaFlowBackground()).testTag("playlist_preview"),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text(R.string.pl_title),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            FlowIconButton(FlowIcons.Close,text(R.string.close),close,enabled=!state.saving)
        }
        if(state.ambiguous) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(text(R.string.pl_choose_scope))
                Button(single,Modifier.fillMaxWidth().testTag("playlist_single")) {Text(text(R.string.pl_single))}
                OutlinedButton({controller.open()},Modifier.fillMaxWidth().testTag("playlist_entire")) {Text(text(R.string.pl_entire))}
            }
        } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("playlist_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val page=currentPage
            item { FlowPanel(accent=MaterialTheme.colorScheme.tertiary) {
                 val collectionLabel=when(page?.collectionType) {
                     "topcinema_series"->text(R.string.pl_series)
                     "topcinema_season"->text(R.string.pl_season_title,page.seasonNumber?:page.items.firstNotNullOfOrNull {it.seasonNumber}?:0)
                     else->text(R.string.pl_title)
                 }
                 FlowEyebrow(collectionLabel,FlowIcons.Playlist)
                page?.thumbnail?.let {url->AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).size(320,180).build(),null,Modifier.widthIn(max=320.dp).fillMaxWidth().height(120.dp))}
                Text(page?.title ?: text(R.string.pl_title),style=MaterialTheme.typography.headlineSmall)
                page?.creator?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                val loaded=page?.items?.size ?: 0
                val selected=state.selected.size
                Text(text(R.string.pl_loaded,spectrumPluralStringResource(R.plurals.pl_entries_loaded,loaded,loaded),
                    spectrumPluralStringResource(R.plurals.selected_count,selected,selected)),Modifier.testTag("playlist_selected_count"))
                OutputSizeSummary(selectedSize,Modifier.testTag("playlist_expected_size"))
                if(state.sizing) Text(text(R.string.media_size_scanning,selectedSize.measured,selectedSize.count),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("playlist_size_loading"))
                Text(page?.total?.let {spectrumPluralStringResource(R.plurals.pl_known_total,it,it)} ?: text(R.string.pl_unknown_total),style=MaterialTheme.typography.bodySmall)
                page?.unverifiedSeasons.orEmpty().forEach {warning->
                    Text(text(R.string.pl_unverified_season,warning.seasonNumber?.toString()?:text(R.string.pl_unknown_season),warning.title.orEmpty()),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            } }
            if(state.loading) item {Column {FlowLoadingIndicator();Text(text(R.string.pl_discovering));TextButton(controller::cancelDiscovery) {Text(text(R.string.cancel))}}}
            state.error?.let {error->item {Text(text(errorResource(error)),color=MaterialTheme.colorScheme.error)}}
            item {
                Text(text(R.string.pl_loaded_scope),style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton({controller.selectAll(true)},enabled=enabled,modifier=Modifier.testTag("playlist_select_all")) {Text(text(R.string.select_all))}
                    TextButton({controller.selectAll(false)},enabled=enabled,modifier=Modifier.testTag("playlist_clear")) {Text(text(R.string.deselect_all))}
                    TextButton({range=true},enabled=enabled) {Text(text(R.string.pl_range))}
                }
            }
            item {
                if(state.checkingDuplicates) FlowLoadingIndicator()
                Text(text(R.string.duplicate_playlist_counts,state.duplicates.completed.size,state.duplicates.active.size),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("playlist_duplicate_counts"))
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(skipExisting,{skipExisting=it},enabled=enabled);Text(text(R.string.duplicate_playlist_skip))}
                if(!skipExisting) Text(text(R.string.duplicate_quality_hint),style=MaterialTheme.typography.bodySmall)
            }
            item {
                Text(text(R.string.pl_quality),style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!audio,{audio=false},enabled=enabled,label={Text(text(R.string.video))})
                    FilterChip(audio,{audio=true},enabled=enabled,label={Text(text(R.string.audio))},modifier=Modifier.testTag("playlist_audio"))
                }
                if(audio) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(output=="original",{output="original"},enabled=enabled,label={Text(text(R.string.pl_best))})
                        if(mp3) FilterChip(output=="mp3",{output="mp3"},enabled=enabled,label={Text("MP3")},modifier=Modifier.testTag("playlist_mp3"))
                    }
                    if(output=="mp3") FlowRow {listOf(128,192,256,320).forEach {n->FilterChip(bitrate==n,{bitrate=n},enabled=enabled,label={Text("$n kbps")})}}
                } else {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(1080,720,480,360).forEach {n->FilterChip(height==n,{height=n},enabled=enabled&&fallback!="BEST",label={Text("${n}p")},modifier=Modifier.testTag("playlist_quality_$n"))}}
                    listOf("EXACT" to R.string.pl_exact,"AT_MOST" to R.string.pl_at_most,"BEST" to R.string.pl_best).forEach {(value,label)->
                        FilterChip(fallback==value,{fallback=value},enabled=enabled,label={Text(text(label))},modifier=Modifier.testTag("playlist_policy_$value"))
                    }
                    Text(text(R.string.pl_policy_hint),style=MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text(text(R.string.pl_sequential),style=MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment=Alignment.CenterVertically) {Switch(advanced,{advanced=it;if(!it) concurrency=1},enabled=enabled,modifier=Modifier.testTag("playlist_advanced"));Spacer(Modifier.width(8.dp));Text(text(R.string.pl_advanced))}
                if(advanced) {
                    Text(text(R.string.pl_concurrency),style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {(1..5).toList().plus(0).forEach {n->FilterChip(concurrency==n,{concurrency=n},enabled=enabled,label={Text(if(n==0) text(R.string.pl_automatic) else "$n")},modifier=Modifier.testTag("playlist_concurrency_$n"))}}
                    Text(text(R.string.pl_auto_hint),style=MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(numbered,{numbered=it},enabled=enabled);Text(text(R.string.pl_numbering))}
            }
             seasonGroups.forEach {(season,entries)->
                 item(key="playlist-season-$season") {
                     val selectable=entries.filter {it.selectable}
                     val selectedCount=selectable.count {it.key in state.selected}
                     Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                         Checkbox(selectedCount==selectable.size&&selectable.isNotEmpty(),{controller.selectSeason(season,it)},
                             enabled=enabled&&selectable.isNotEmpty(),modifier=Modifier.testTag("playlist_season_$season"))
                         Column(Modifier.weight(1f)) {
                             Text(text(R.string.pl_season_group,season,entries.count {it.episodeNumber!=null}),style=MaterialTheme.typography.titleMedium)
                             Text(text(R.string.pl_season_selection,selectedCount,selectable.size),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                         }
                     }
                 }
                 items(entries,key={it.key}) {entry->
                     PlaylistEntryRow(entry,rowContext,controller,selectQuality)
                 }
             }
             items(page?.items.orEmpty().filter {it.key !in groupedKeys},key={it.key}) {entry->
                 PlaylistEntryRow(entry,rowContext,controller,selectQuality)
             }
            if(page?.items?.isEmpty()==true) item {Text(text(R.string.pl_empty))}
            if(page?.hasMore==true&&page.items.size<5000) item {OutlinedButton(controller::loadMore,enabled=enabled&&!state.loading,modifier=Modifier.fillMaxWidth().testTag("playlist_load_more")) {Text(text(R.string.pl_load_more))}}
        }
        PrimaryAction(onClick={controller.submit(PlaylistPolicy(audio,output,height,QualityFallback.valueOf(fallback),bitrate),numbered,concurrency,skipExisting)},enabled=selectedEntries.isNotEmpty()&&!state.checkingDuplicates&&!state.loading&&!state.saving,
            modifier=Modifier.fillMaxWidth().padding(16.dp).testTag("playlist_download")) {
            if(state.saving) FlowLoadingIndicator(compact=true)
            else {Icon(FlowIcons.Downloads,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(text(R.string.pl_download,selectedEntries.size))}
        }
        TextButton({schedule=true},enabled=state.selected.isNotEmpty()&&!state.loading&&!state.saving&&!state.checkingDuplicates,modifier=Modifier.fillMaxWidth().testTag("playlist_schedule")) {Text(text(R.string.schedule_download))}
        }
      }
    }
    if(schedule) ScheduleDialog({schedule=false}) {plan->schedule=false;controller.submit(PlaylistPolicy(audio,output,height,QualityFallback.valueOf(fallback),bitrate),numbered,concurrency,skipExisting,plan)}
    if(range) AlertDialog(onDismissRequest={range=false},title={Text(text(R.string.pl_range))},text={Column {
        OutlinedTextField(first,{first=it.filter(Char::isDigit).take(7)},label={Text(text(R.string.pl_from))})
        OutlinedTextField(last,{last=it.filter(Char::isDigit).take(7)},label={Text(text(R.string.pl_to))})
    }},confirmButton={TextButton({controller.range(first.toIntOrNull() ?: 0,last.toIntOrNull() ?: 0);range=false}) {Text(text(R.string.ok))}})
}

private data class PlaylistEntryContext(
    val selected:Set<String>,
    val enabled:Boolean,
    val sizes:Map<String,SizeForecast>,
    val policy:PlaylistPolicy
)

@Composable private fun PlaylistEntryRow(
    entry:PlaylistEntry,
    context:PlaylistEntryContext,
    controller:PlaylistController,
    selectQuality:(String)->Unit
) {
    val chosen=entry.key in context.selected
    val enabled=context.enabled
    val fill by animateColorAsState(if(chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.35f) else MaterialTheme.colorScheme.surface,tween(FlowMotion.Fast),label="playlistSelection")
    Surface(shape=RoundedCornerShape(22.dp),color=fill,contentColor=MaterialTheme.colorScheme.onSurface,
        border=BorderStroke(1.dp,if(chosen) MaterialTheme.colorScheme.primary.copy(alpha=.25f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Checkbox(chosen,{controller.select(entry.key,it)},enabled=enabled&&entry.selectable,modifier=Modifier.testTag("playlist_item_${entry.position}"))
            entry.thumbnail?.let {url->AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).size(128).build(),null,Modifier.size(48.dp))}
            Column(Modifier.weight(1f)) {
                entry.episodeNumber?.let {Text(text(R.string.pl_episode_number,it),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)}
                Text("${entry.position}. ${entry.title ?: text(R.string.pl_item,entry.position)}",maxLines=2,overflow=TextOverflow.Ellipsis)
                entry.duration?.let {Text(duration(it),style=MaterialTheme.typography.bodySmall)}
                val forecast=context.sizes[entry.key] ?: SizeForecast(context.policy.durationOutput(entry.duration),null,null)
                Text(if(forecast.error==ErrorCode.FORMAT_UNAVAILABLE) text(R.string.media_size_quality_unavailable) else sizeLabel(forecast.output),style=MaterialTheme.typography.labelMedium,modifier=Modifier.testTag("playlist_item_size_${entry.position}"))
                if(!context.policy.audio&&forecast.quality!=null) Text(qualityCategoryName(forecast.quality),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.reason!=null) Text(text(if(entry.collection) R.string.pl_collection else R.string.pl_unavailable),style=MaterialTheme.typography.bodySmall)
                if(entry.collection&&entry.url!=null) TextButton({controller.open(entry.url)},enabled=enabled) {Text(text(R.string.open))}
                if(entry.episodeNumber!=null&&entry.selectable&&entry.url!=null) TextButton(
                    {selectQuality(entry.url)},enabled=enabled,modifier=Modifier.testTag("playlist_item_quality_${entry.position}")
                ) {Text(text(R.string.pl_episode_quality))}
            }
        }
    }
}

@Composable internal fun OutputSizeSummary(coverage:SizeCoverage,modifier:Modifier=Modifier) {
    Text(when {
        coverage.total!=null->text(R.string.media_output_total,sizeLabel(coverage.total))
        coverage.known!=null->text(R.string.media_output_partial,sizeLabel(coverage.known),coverage.measured,coverage.count)
        else->text(R.string.size_unknown)
    },modifier=modifier,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlaylistBatchCard(summary:BatchSummary,pause:()->Unit,resume:()->Unit,cancel:()->Unit,retry:()->Unit,details:()->Unit,expanded:Boolean=false,remove:()->Unit={}) {
    val audio=remember(summary.batch.policy) {runCatching {PlaylistPolicy.parse(summary.batch.policy).audio}.getOrDefault(false)}
    var menu by remember {mutableStateOf(false)}
    var confirmation by remember {mutableStateOf<String?>(null)}
    val removable=summary.canRemoveCancelled
    Card(onClick=details,modifier=Modifier.fillMaxWidth().testTag("playlist_batch_${summary.batch.id}"),shape=RoundedCornerShape(26.dp),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f)),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                MediaArtwork(summary.batch.thumbnail,summary.batch.title,Modifier.size(72.dp),audio=audio)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    FlowEyebrow(text(if(audio) R.string.pl_audio_playlist else R.string.pl_video_playlist),FlowIcons.Playlist)
                    Text(summary.batch.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(spectrumPluralStringResource(if(audio) R.plurals.pl_audio_items else R.plurals.pl_videos,summary.selected,summary.selected),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PlaylistProgressOverview(summary)
            if(summary.failed>0) Text(spectrumPluralStringResource(R.plurals.pl_summary_failed,summary.failed,summary.failed),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
            if(summary.cancelled>0) Text(spectrumPluralStringResource(R.plurals.pl_cancelled_count,summary.cancelled,summary.cancelled),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(details,Modifier.weight(1f).heightIn(min=48.dp).testTag("playlist_batch_details"),shape=RoundedCornerShape(14.dp)) {
                    Text(text(R.string.pl_details));Spacer(Modifier.width(8.dp));Icon(FlowIcons.Next,null,Modifier.size(18.dp))
                }
                if(!summary.finished&&summary.batch.state!="CANCELLED"&&summary.status!="SCHEDULED") {
                    FlowIconButton(if(summary.status=="PAUSED") FlowIcons.Play else FlowIcons.Pause,
                        text(if(summary.status=="PAUSED") R.string.pl_resume_all else R.string.pl_pause_all),if(summary.status=="PAUSED") resume else pause,accent=true)
                }
                if(removable||((!summary.finished&&summary.status!="SCHEDULED"||summary.failed>0)&&summary.batch.state!="CANCELLED")) Box {
                    FlowIconButton(FlowIcons.More,text(R.string.pl_more_actions),{menu=true},Modifier.testTag("playlist_batch_more_${summary.batch.id}"))
                    DropdownMenu(menu,{menu=false}) {
                        if(removable) DropdownMenuItem(text={Text(text(R.string.pl_remove_cancelled))},modifier=Modifier.testTag("playlist_remove_${summary.batch.id}"),onClick={menu=false;confirmation="remove"},leadingIcon={Icon(FlowIcons.Delete,null)})
                        else {
                            if(summary.failed>0) DropdownMenuItem(text={Text(text(R.string.pl_retry_failed_short))},onClick={menu=false;retry()},leadingIcon={Icon(FlowIcons.Retry,null)})
                            if(!summary.finished&&summary.status!="SCHEDULED") DropdownMenuItem(text={Text(text(R.string.pl_cancel_all))},onClick={menu=false;confirmation="cancel"},leadingIcon={Icon(FlowIcons.Close,null)})
                        }
                    }
                }
            }
        }
    }
    confirmation?.let {action->PlaylistRemovalDialog(action=="remove",{confirmation=null}) {confirmation=null;if(action=="remove") remove() else cancel()}}
}
