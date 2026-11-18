package com.zdownloader.app.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.data.Settings
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.spectrumStringResource as stringResource

internal data class ImagePreviewActions(val back:()->Unit,val folder:()->Unit,val enqueue:(Selection,String)->Unit)

@Composable internal fun OriginalImagePreview(media:Media,settings:Settings,model:ZViewModel,actions:ImagePreviewActions) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val images=remember(media) {media.imageOptions().sortedByDescending {(it.width ?: 0).toLong()*(it.height ?: 0)}}
    var selectedId by rememberSaveable(media.url) {mutableStateOf(images.first().id)}
    var title by rememberSaveable(media.url) {mutableStateOf(media.title)}
    var schedule by remember {mutableStateOf(false)}
    val selected=images.firstOrNull {it.id==selectedId}
    val selection=selected?.let {Selection.image(media,it)}
    val ready=selection!=null&&title.isNotBlank()&&!ui.submitting&&(Build.VERSION.SDK_INT>=29||settings.destination!=null)
    if(schedule) ScheduleDialog({schedule=false}) {plan->schedule=false;selection?.let {model.enqueue(media,it,title,plan=plan)}}
    Column(Modifier.fillMaxSize().testTag("original_image_preview")) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(FlowIcons.Back,stringResource(R.string.back),actions.back)
            Text(stringResource(R.string.original_photo),style=MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.weight(1f).testTag("preview_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {MediaArtwork(media.thumbnail,media.title,Modifier.fillMaxWidth().aspectRatio((selected?.width ?: 1).toFloat()/(selected?.height ?: 1)))}
            item {Text(media.title,style=MaterialTheme.typography.titleMedium);Text(stringResource(R.string.photo_original_hint),style=MaterialTheme.typography.bodySmall)}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                images.forEach {image->FilterChip(selectedId==image.id,{selectedId=image.id},label={Text("${image.width}×${image.height} · ${image.ext.uppercase()}")})}
            }}
            item {OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.filename))},shape=RoundedCornerShape(14.dp))}
            item {Text(settings.destination?.let {stringResource(R.string.selected_folder,scopedFolderLabel(it))} ?: stringResource(R.string.photo_default_folder));TextButton(actions.folder) {Text(stringResource(R.string.choose_folder))}}
            item {Text(stringResource(if(media.audioOptions().isEmpty()) R.string.photo_audio_unavailable else R.string.photo_audio_source_hint),style=MaterialTheme.typography.bodySmall)}
            if(media.audioOptions().isNotEmpty()) item {
                val audio=Selection.audio(media,"original")
                OutlinedButton({actions.enqueue(audio,title)},enabled=!ui.submitting,modifier=Modifier.fillMaxWidth().testTag("photo_companion_audio")) {Text(stringResource(R.string.photo_download_audio))}
            }
            item {OutlinedButton({schedule=true},enabled=ready,modifier=Modifier.fillMaxWidth().testTag("schedule_download")) {Text(stringResource(R.string.schedule_download))}}
        }
        PrimaryAction({selection?.let {actions.enqueue(it,title)}},enabled=ready,
            modifier=Modifier.fillMaxWidth().padding(20.dp).testTag("start_download")) {Text(stringResource(if(ui.submitting) R.string.download_adding else R.string.photo_save_original))}
    }
}
