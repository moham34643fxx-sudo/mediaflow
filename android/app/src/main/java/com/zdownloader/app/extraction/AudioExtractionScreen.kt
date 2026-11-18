package com.zdownloader.app.extraction

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import com.zdownloader.app.playback.PlaybackMedia
import com.zdownloader.app.playback.playTime
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun AudioExtractionScreen(model:AudioExtractionViewModel,onBack:()->Unit,onLibrary:()->Unit,initialUri:String?=null,onInitialConsumed:()->Unit={}) {
    val context=LocalContext.current
    val app=context.applicationContext as ZApplication
    val selection by model.selection.collectAsStateWithLifecycle()
    val task by model.repository.task.collectAsStateWithLifecycle()
    val downloads by remember(app) {app.repository.dao.completed()}.collectAsStateWithLifecycle(emptyList())
    var library by rememberSaveable {mutableStateOf(false)}
    var selectedIndex by rememberSaveable(selection.source?.uri) {mutableIntStateOf(selection.source?.tracks?.firstOrNull {it.default}?.index ?: selection.source?.tracks?.firstOrNull()?.index ?: -1)}
    var output by rememberSaveable {mutableStateOf(AudioOutput.ORIGINAL.name)}
    var bitrate by rememberSaveable {mutableIntStateOf(0)}
    var savingRetained by rememberSaveable {mutableStateOf(false)}
    val track=selection.source?.tracks?.firstOrNull {it.index==selectedIndex}
    val format=AudioOutput.valueOf(output)
    val canCopy=track?.let {ExtractionPolicy.canCopy(it,format)}==true
    val plan=track?.let {runCatching {ExtractionPolicy.plan(it,format,if(canCopy) bitrate else bitrate.takeIf {n->n>0} ?: 192)}.getOrNull()}
    val active=task?.phase?.active==true
    val retained=task?.let {it.phase==ExtractionPhase.SAVE_FAILED&&it.valid}==true
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) {
        runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        model.select(uri)
    }}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if(savingRetained) task?.plan?.mime ?: "audio/mp4" else plan?.mime ?: "audio/mp4")) {uri->if(uri!=null) {
        runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
        if(savingRetained) model.retrySave(uri) else model.start(selectedIndex,format,plan?.bitrate ?: 192,uri)
    }}
    LaunchedEffect(initialUri) {if(initialUri!=null) {model.select(Uri.parse(initialUri));onInitialConsumed()}}
    LaunchedEffect(track?.index,selection.source?.uri) {if(track!=null&&!ExtractionPolicy.canCopy(track,AudioOutput.valueOf(output))&&output==AudioOutput.ORIGINAL.name) {output=AudioOutput.M4A.name;bitrate=192}}
    FlowScreen {
        Column(Modifier.fillMaxSize().testTag("audio_extraction_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                FlowIconButton(FlowIcons.Back,text(R.string.back),onBack)
                Text(text(R.string.extract_title),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("extraction_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    FlowPanel {
                        FlowEyebrow(text(R.string.extract_title),FlowIcons.Audio)
                        Text(text(R.string.extract_subtitle),style=MaterialTheme.typography.headlineSmall)
                        Text(text(R.string.extract_description),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryAction({picker.launch(arrayOf("video/*"))},Modifier.fillMaxWidth().testTag("extraction_choose"),enabled=!active) {
                            Icon(FlowIcons.Video,null);Spacer(Modifier.width(8.dp));Text(text(R.string.extract_choose))
                        }
                        TextButton({library=true},Modifier.fillMaxWidth().testTag("extraction_library"),enabled=!active) {Text(text(R.string.extract_library))}
                    }
                }
                if(selection.loading) item {FlowLoadingIndicator()}
                selection.error?.let {error->item {Text(text(error),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("extraction_error"))}}
                selection.source?.let {source->
                    item {
                        FlowPanel {
                            FlowEyebrow(text(R.string.extract_source),FlowIcons.Video)
                            Text(source.name,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
                            Text(listOfNotNull(source.bytes?.let {bytesLabel(it)},source.duration?.let {playTime((it*1000).toLong())}).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text(R.string.extract_track),style=MaterialTheme.typography.labelLarge)
                            source.tracks.forEachIndexed {number,audio->
                                Surface(onClick={selectedIndex=audio.index},enabled=!active,shape=RoundedCornerShape(14.dp),
                                    color=if(selectedIndex==audio.index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    modifier=Modifier.fillMaxWidth().testTag("extraction_track_${audio.index}")) {
                                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                        FlowSelectionIndicator(selectedIndex==audio.index,!active)
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                            Text(listOfNotNull(audio.title ?: text(R.string.extract_track_number,number+1),audio.language).joinToString(" · "),style=MaterialTheme.typography.titleSmall)
                                            Text("${audio.codec.uppercase(java.util.Locale.ROOT)} · "+text(R.string.extract_channels,audio.channels,audio.sampleRate),style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        FlowPanel {
                            FlowEyebrow(text(R.string.extract_format),FlowIcons.Audio)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                AudioOutput.entries.forEach {choice->FlowFilterChip(format==choice,{
                                    output=choice.name;bitrate=if(track?.let {ExtractionPolicy.canCopy(it,choice)}==true) 0 else 192
                                },label={Text(if(choice==AudioOutput.ORIGINAL) text(R.string.extract_original) else choice.name)},enabled=!active&&(choice!=AudioOutput.ORIGINAL||track?.let {ExtractionPolicy.original(it.codec)}!=null),modifier=Modifier.testTag("extraction_format_${choice.name}"))}
                            }
                            if(format!=AudioOutput.ORIGINAL) {
                                Text(text(R.string.extract_quality),style=MaterialTheme.typography.labelLarge)
                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    (if(canCopy) listOf(0,128,192,256,320) else listOf(128,192,256,320)).forEach {rate->
                                        FlowFilterChip((plan?.bitrate ?: bitrate)==rate,{bitrate=rate},label={Text(if(rate==0) text(R.string.extract_original) else text(R.string.extract_bitrate,rate))},enabled=!active,modifier=Modifier.testTag("extraction_bitrate_$rate"))
                                    }
                                }
                            }
                            plan?.let {p->
                                Text(if(p.copy) text(R.string.extract_copy) else text(R.string.extract_encode,p.bitrate),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
                                Text(text(if(p.copy) R.string.extract_copy_hint else R.string.extract_encode_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(!p.copy&&p.codec=="mp3"&&track.channels>2) Text(text(R.string.extract_downmix),style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                task?.let {current->item {
                    FlowPanel(Modifier.testTag("extraction_task")) {
                        FlowEyebrow(text(current.phase.label),if(current.phase==ExtractionPhase.COMPLETED) FlowIcons.Success else FlowIcons.Audio)
                        Text(current.name,style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                        if(current.phase.active) {
                            current.progress?.let {fraction->
                                MediaFlowProgress(fraction,Modifier.fillMaxWidth())
                                Text("${(fraction*100).toInt()}%",style=MaterialTheme.typography.labelLarge)
                            } ?: FlowLoadingIndicator()
                            OutlinedButton({model.repository.cancel(current.id)},Modifier.fillMaxWidth().testTag("extraction_cancel")) {Text(text(R.string.cancel))}
                        }
                        current.error?.let {Text(text(it),color=MaterialTheme.colorScheme.error)}
                        if(current.phase==ExtractionPhase.SAVE_FAILED&&current.valid) {
                            Button({if(Build.VERSION.SDK_INT<29) {savingRetained=true;save.launch(current.name)} else model.retrySave()},Modifier.fillMaxWidth().testTag("extraction_retry_save")) {Text(text(R.string.extract_retry_save))}
                            TextButton(model::discard,Modifier.fillMaxWidth()) {Text(text(R.string.extract_discard))}
                        }
                        if(current.phase==ExtractionPhase.COMPLETED&&current.output!=null) {
                            Text(bytesLabel(current.bytes),style=MaterialTheme.typography.bodyMedium)
                            Text(text(if(current.export==null) R.string.extract_saved_location else R.string.extract_saved_document),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton({app.playback.playUri(current.output,open=true)},Modifier.testTag("extraction_play")) {Icon(FlowIcons.Play,null);Spacer(Modifier.width(6.dp));Text(text(R.string.play))}
                                OutlinedButton({
                                    val uri=Uri.parse(current.output)
                                    runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type=current.plan.mime;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("audio",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},null))}.onFailure {model.problem(R.string.extract_share_error)}
                                },Modifier.testTag("extraction_share")) {Icon(FlowIcons.Share,null);Spacer(Modifier.width(6.dp));Text(text(R.string.share))}
                            }
                            TextButton({app.playback.repository.setText("library_source","device");onLibrary()},Modifier.fillMaxWidth()) {Text(text(R.string.extract_open_library))}
                        }
                    }
                }}
            }
            if(selection.source!=null) Surface(color=MaterialTheme.colorScheme.surface) {
                PrimaryAction({if(Build.VERSION.SDK_INT<29) {savingRetained=false;save.launch(ExtractionPolicy.filename(selection.source!!.name,plan!!.extension,java.util.UUID.randomUUID().toString()))} else model.start(selectedIndex,format,plan!!.bitrate)},
                    Modifier.fillMaxWidth().padding(16.dp).testTag("extraction_start"),enabled=!active&&!retained&&!selection.loading&&plan!=null) {
                    Icon(FlowIcons.Audio,null);Spacer(Modifier.width(8.dp));Text(text(R.string.extract_begin))
                }
            }
        }
    }
    if(library) AlertDialog(onDismissRequest={library=false},title={Text(text(R.string.extract_library))},text={
        val videos=downloads.mapNotNull {PlaybackMedia.from(it)}.filter {it.video}.take(500)
        LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {Text(text(R.string.extract_select_hint),style=MaterialTheme.typography.bodySmall)}
            if(videos.isEmpty()) item {Text(text(R.string.extract_no_library_video))}
            items(videos,key={it.uri}) {video->TextButton({library=false;model.select(Uri.parse(video.uri))},Modifier.fillMaxWidth()) {Text(video.title,maxLines=3,overflow=TextOverflow.Ellipsis)}}
        }
    },confirmButton={TextButton({library=false;picker.launch(arrayOf("video/*"))}) {Text(text(R.string.extract_choose))}},dismissButton={TextButton({library=false}) {Text(text(R.string.cancel))}})
}
