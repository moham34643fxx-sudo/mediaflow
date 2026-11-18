package com.zdownloader.app.status

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.playback.SharedVideoSurface
import com.zdownloader.app.playback.PlaybackLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.zdownloader.app.R
import com.zdownloader.app.ui.PrimaryAction
import com.zdownloader.app.ui.FlowIcons
import com.zdownloader.app.ui.FlowIconButton
import com.zdownloader.app.ui.FlowIconTile
import com.zdownloader.app.ui.FlowLoadingIndicator
import com.zdownloader.app.ui.FlowMotion
import com.zdownloader.app.ui.LocalFlowMotionEnabled
import com.zdownloader.app.ui.flowSuccessColor
import com.zdownloader.app.ui.FlowEyebrow
import com.zdownloader.app.ui.flowRibbon
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import com.zdownloader.app.ui.duration
import com.zdownloader.app.ui.spectrumStringResource as text

internal fun statusProblemText(problem:StatusProblem):Int=when(problem) {
    StatusProblem.ACCESS_REQUIRED->R.string.status_error_access_required
    StatusProblem.ACCESS_REVOKED->R.string.status_error_revoked
    StatusProblem.WRONG_FOLDER->R.string.status_error_wrong_folder
    StatusProblem.WRONG_SOURCE->R.string.status_error_wrong_source
    StatusProblem.FOLDER_UNAVAILABLE->R.string.status_error_folder_unavailable
    StatusProblem.SOURCE_UNAVAILABLE->R.string.status_error_source_unavailable
    StatusProblem.SOURCE_CHANGED->R.string.status_error_source_changed
    StatusProblem.UNSUPPORTED->R.string.status_error_unsupported
    StatusProblem.CORRUPT->R.string.status_error_corrupt
    StatusProblem.TOO_LARGE->R.string.status_error_too_large
    StatusProblem.NO_SPACE->R.string.status_error_no_space
    StatusProblem.LEGACY_EXPORT_REQUIRED->R.string.status_error_legacy
    else->R.string.status_error_save_failed
}
@Composable private fun sourceName(source:StatusSource)=text(when(source) {StatusSource.WHATSAPP->R.string.status_whatsapp;StatusSource.BUSINESS->R.string.status_business;else->R.string.status_imports})

@Composable fun StatusSaverScreen(model:StatusSaverViewModel,back:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val snack=remember {SnackbarHostState()}
    var menu by remember {mutableStateOf(false)}
    var filter by rememberSaveable(state.source.key) {mutableIntStateOf(0)}
    var pickerSource by rememberSaveable {mutableStateOf(StatusSource.WHATSAPP.name)}
    var exportItem by remember {mutableStateOf<StatusMedia?>(null)}
    val treePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->
        if(uri!=null) model.chooseFolder(StatusSource.valueOf(pickerSource),uri)
    }
    val filesPicker=rememberLauncherForActivityResult(remember {object:ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context:Context,input:Array<String>)=super.createIntent(context,input).putExtra(Intent.EXTRA_LOCAL_ONLY,true)
    }}) {uris->if(uris.isNotEmpty()) model.importFiles(uris)}
    val exportPicker=rememberLauncherForActivityResult(remember {object:ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context:Context,input:String)=super.createIntent(context,input).setType(exportItem?.type?.mime ?: "application/octet-stream")
    }}) {uri->val item=exportItem;exportItem=null;if(uri!=null&&item!=null) model.save(item,uri)}
    val legacyPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) model.refresh() else treePicker.launch(model.permissions.initial(StatusSource.valueOf(pickerSource)))
    }
    val chooseFolder:()->Unit={
        pickerSource=state.source.name
        if(Build.VERSION.SDK_INT<=28&&androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.READ_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else treePicker.launch(model.permissions.initial(state.source))
    }
    val importFiles:()->Unit={filesPicker.launch(arrayOf("image/*","video/*"))}
    val save:(StatusMedia)->Unit={item->
        if(Build.VERSION.SDK_INT>=29) model.save(item)
        else {exportItem=item;exportPicker.launch("MediaFlow_Status_${System.currentTimeMillis()}.${item.type.extension}")}
    }
    LaunchedEffect(lifecycle,model) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {model.observeChanges()}
    }
    DisposableEffect(model) {onDispose {model.leaveScreen()}}
    val notice=state.notice
    val noticeText=notice?.let {text(when {it.alreadySaved->R.string.status_already_saved;it.destinationFallback->R.string.status_destination_fallback;!it.gallery->R.string.status_saved_document;else->R.string.status_saved_gallery})}
    LaunchedEffect(notice,noticeText) {if(noticeText!=null) {snack.showSnackbar(noticeText);model.clearNotice()}}
    Column(Modifier.fillMaxSize().testTag("status_screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(FlowIcons.Back,text(R.string.back),back,Modifier.testTag("status_back"))
            Text(text(R.string.status_title),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            FlowIconButton(FlowIcons.Retry,text(R.string.status_refresh),model::refresh,Modifier.testTag("status_refresh"),enabled=!state.loading,accent=true)
            Box {
                FlowIconButton(FlowIcons.More,text(R.string.details),{menu=true})
                 DropdownMenu(menu,{menu=false}) {
                     DropdownMenuItem(text={Text(text(R.string.status_import_files))},onClick={menu=false;importFiles()})
                    if(state.source==StatusSource.IMPORTS) DropdownMenuItem(text={Text(text(R.string.status_clear_imports))},onClick={menu=false;model.clearImports()})
                    else if(model.permissions.configured(state.source)!=null) {
                        DropdownMenuItem(text={Text(text(R.string.status_change_folder))},onClick={menu=false;chooseFolder()})
                        DropdownMenuItem(text={Text(text(R.string.status_forget_folder))},onClick={menu=false;model.forget()})
                    }
                }
            }
        }
        if(state.loading) FlowLoadingIndicator(Modifier.fillMaxWidth().testTag("status_loading"))
        val visible=state.items.filter {filter==0||filter==1&&!it.type.video||filter==2&&it.type.video}
        LazyVerticalGrid(GridCells.Adaptive(152.dp),Modifier.weight(1f).fillMaxWidth().testTag("status_grid"),
            contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(span={GridItemSpan(maxLineSpan)}) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(text(R.string.status_saver_description),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val sources=model.sources()
                    if(sources.size>1) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        sources.forEach {source->FilterChip(state.source==source,{model.select(source)},label={Text(sourceName(source))},modifier=Modifier.heightIn(min=48.dp).testTag("status_source_${source.key}"))}
                    }
                     if(state.source==StatusSource.IMPORTS) OutlinedButton(onClick=importFiles,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("status_import_files")) {Icon(FlowIcons.Photo,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(text(R.string.status_import_files))}
                    if(state.sessionOnly) Text(text(R.string.status_session_only),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(state.problem!=null) item(span={GridItemSpan(maxLineSpan)}) {
                Surface(shape=RoundedCornerShape(26.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))) {
                Column(Modifier.fillMaxWidth().flowRibbon(MaterialTheme.colorScheme.tertiary).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    FlowIconTile(FlowIcons.Status,MaterialTheme.colorScheme.tertiary)
                    Text(text(R.string.status_access_title),style=MaterialTheme.typography.titleMedium)
                    Text(text(statusProblemText(state.problem!!)),style=MaterialTheme.typography.bodyMedium)
                    Text(text(R.string.status_access_description),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.source!=StatusSource.IMPORTS) PrimaryAction(chooseFolder,Modifier.fillMaxWidth().testTag("status_choose_folder")) {Text(text(R.string.status_choose_folder))}
                }
                }
            }
            // A metadata refresh must not remove the entire existing grid or
            // its active controls while the new snapshot is being inspected.
            if(state.problem==null&&(!state.loading||state.items.isNotEmpty())) {
                item(span={GridItemSpan(maxLineSpan)}) {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.all,R.string.status_photos,R.string.status_videos).forEachIndexed {index,label->
                        FilterChip(filter==index,{filter=index},label={Text(text(label))},modifier=Modifier.heightIn(min=48.dp).testTag("status_filter_$index"))
                    }
                }}
                if(state.limited||state.skipped>0) item(span={GridItemSpan(maxLineSpan)}) {
                    Text(text(if(state.limited) R.string.status_scan_limited else R.string.status_skipped),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(visible.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
                    Column(Modifier.padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(text(when {state.items.isNotEmpty()->R.string.status_filtered_empty;state.skipped>0->R.string.status_no_supported;state.source==StatusSource.IMPORTS->R.string.status_import_empty;else->R.string.status_empty}),style=MaterialTheme.typography.titleSmall)
                        Text(text(if(state.skipped>0) R.string.status_supported_hint else R.string.status_empty_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(state.source!=StatusSource.IMPORTS&&model.permissions.installed(state.source)) TextButton(onClick={
                            state.source.packageName?.let {context.packageManager.getLaunchIntentForPackage(it)}?.let(context::startActivity)
                        }) {Text(text(R.string.status_open_whatsapp))}
                    }
                }
                items(visible,key={it.key}) {item->
                    val saved=item.savedUri!=null
                    val edge by animateColorAsState(if(saved) flowSuccessColor().copy(alpha=.45f) else MaterialTheme.colorScheme.outlineVariant,tween(FlowMotion.Reveal),label="savedStatusBorder")
                    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,edge),modifier=Modifier.testTag("status_item_${item.key.take(12)}")) {
                        Column {
                            Surface(onClick={model.preview(item)},shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().aspectRatio(1f).testTag("status_preview_${item.key.take(12)}")) {
                                Box(contentAlignment=Alignment.Center) {
                                    Icon(if(item.type.video) FlowIcons.Video else FlowIcons.Photo,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                    val motion=LocalFlowMotionEnabled.current
                                    val request=remember(item.uri,item.savedUri,motion) {ImageRequest.Builder(context).data(Uri.parse(item.savedUri ?: item.uri)).crossfade(if(motion) FlowMotion.Fast else 0).videoFrameMillis(0).build()}
                                    AsyncImage(request,text(if(item.type.video) R.string.status_video else R.string.status_photo),Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                                    if(item.type.video) {
                                        Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.scrim.copy(alpha=0.6f),modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                                            Row(Modifier.padding(horizontal=6.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                                                Icon(FlowIcons.Video,null,tint=androidx.compose.ui.graphics.Color.White,modifier=Modifier.size(14.dp))
                                                item.durationMs?.let {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(duration(it/1000.0),style=MaterialTheme.typography.labelSmall,color=androidx.compose.ui.graphics.Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(text(if(item.type.video) R.string.status_video else R.string.status_photo),style=MaterialTheme.typography.labelLarge)
                                val details=listOfNotNull(item.durationMs?.let {duration(it/1000.0)},item.bytes?.let {android.text.format.Formatter.formatShortFileSize(context,it)}).joinToString(" · ")
                                if(details.isNotBlank()) Text(details,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick={save(item)},enabled=item.key !in state.saving&&item.savedUri==null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("status_save_${item.key.take(12)}"),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),
                                    colors=ButtonDefaults.buttonColors(disabledContainerColor=if(saved) flowSuccessColor().copy(alpha=.12f) else MaterialTheme.colorScheme.surfaceVariant,disabledContentColor=if(saved) flowSuccessColor() else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    if(item.key in state.saving) FlowLoadingIndicator(compact=true)
                                    else Icon(if(saved) FlowIcons.Success else FlowIcons.Downloads,null,Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp));Text(text(when {item.key in state.saving->R.string.status_saving;item.savedUri!=null->R.string.status_saved;else->R.string.status_save}))
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack)
    }
    state.error?.let {error->AlertDialog(onDismissRequest=model::clearError,title={Text(text(R.string.problem))},text={Text(text(statusProblemText(error)))},confirmButton={TextButton(onClick=model::clearError) {Text(text(R.string.ok))}})}
    state.preview?.let {item->StatusPreview(item,item.key in state.saving,{save(item)},{model.preview(null)},model::previewFailed)}
}

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable private fun StatusPreview(item:StatusMedia,saving:Boolean,save:()->Unit,close:()->Unit,failed:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    val connection=remember(context) {(context.applicationContext as com.zdownloader.app.ZApplication).playback}
    var fullScreen by remember {mutableStateOf(false)}
    val closePreview:()->Unit={if(!fullScreen&&connection.state.value.current?.uri in listOf(item.uri,item.savedUri)) connection.pause();close()}
    Dialog(closePreview,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max=720.dp).fillMaxHeight(.9f).testTag("status_preview_dialog"),shape=RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(text(R.string.status_preview),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(item.type.video) FlowIconButton(FlowIcons.Video,text(R.string.player_now_playing),{fullScreen=true;connection.open(context);close()})
                    FlowIconButton(FlowIcons.Close,text(R.string.close),closePreview)
                }
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    if(item.type.video) {
                        PlaybackLifecycle(connection)
                        LaunchedEffect(item.key) {connection.playUri(item.savedUri ?: item.uri,video=true,open=false)}
                        DisposableEffect(owner,item.key) {
                            fun pausePreview() {if(!fullScreen&&connection.state.value.current?.uri in listOf(item.uri,item.savedUri)) connection.pause()}
                            val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) pausePreview()}
                            owner.lifecycle.addObserver(observer)
                            onDispose {owner.lifecycle.removeObserver(observer);pausePreview()}
                        }
                        SharedVideoSurface(connection,Modifier.fillMaxSize())
                    } else AsyncImage(Uri.parse(item.savedUri ?: item.uri),text(R.string.status_photo),Modifier.fillMaxSize(),contentScale=ContentScale.Fit,onError={failed()})
                }
                PrimaryAction(save,Modifier.fillMaxWidth().padding(16.dp).testTag("status_preview_save"),enabled=!saving&&item.savedUri==null) {
                    Text(text(when {saving->R.string.status_saving;item.savedUri!=null->R.string.status_saved;else->R.string.status_save}))
                }
            }
        }
    }
}
