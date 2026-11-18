package com.zdownloader.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.MediaFile
import com.zdownloader.app.data.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun StorageManagementScreen(back:()->Unit,library:()->Unit,failed:()->Unit) {
    val context = LocalContext.current
    val manager = (context.applicationContext as ZApplication).storageManagement
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var destination by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<List<MediaFile>>?>(null) }

    fun perform(action:suspend ()->Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); revision++ }
            catch (cancelled:CancellationException) { throw cancelled }
            catch (_:Exception) { error = true }
            finally { busy = false }
        }
    }

    LaunchedEffect(revision) {
        try { usage = manager.usage(); destination = manager.destination() }
        catch (cancelled:CancellationException) { throw cancelled }
        catch (_:Exception) { error = true }
    }

    FlowScreen {
        Column(Modifier.fillMaxSize().testTag("storage_management")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                FlowIconButton(FlowIcons.Back,text(R.string.back),back)
                Text(text(R.string.storage_management),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            }
            if (usage==null || busy) FlowLoadingIndicator()
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                usage?.let { measured ->
                    item { StorageUsageHeader(measured) }
                    item { StorageSectionTitle(R.string.storage_saved_media) }
                    item { StorageMetric(R.string.video,measured.video,MaterialTheme.colorScheme.primary) }
                    item { StorageMetric(R.string.audio,measured.audio,MaterialTheme.colorScheme.tertiary) }
                    if (measured.other>0) item { StorageMetric(R.string.storage_other,measured.other,MaterialTheme.colorScheme.secondary) }
                    item { StorageSectionTitle(R.string.storage_processing_cache) }
                    item { StorageMetric(R.string.storage_temporary,measured.temporary) }
                    item { StorageMetric(R.string.storage_recoverable,measured.recoverableTemporary) }
                    item { StorageMetric(R.string.storage_safe_cleanup,measured.safeTemporary,MaterialTheme.colorScheme.primary) }
                    item { StorageMetric(R.string.storage_cache,measured.cache) }
                    item {
                        OutlinedButton({confirmation="temporary"},enabled=!busy&&measured.safeTemporary>0,
                            modifier=Modifier.fillMaxWidth().testTag("storage_clean_temporary")) {
                            Text(text(R.string.storage_clean_temporary))
                        }
                    }
                }
                item {
                    OutlinedButton({confirmation="cache"},enabled=!busy,
                        modifier=Modifier.fillMaxWidth().testTag("storage_clear_cache")) {
                        Text(text(R.string.storage_clear_cache))
                    }
                }
                item { Text(text(R.string.storage_preservation),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { StorageSectionTitle(R.string.save_location) }
                item {
                    Text(destination?.let(::folderLabel) ?: text(R.string.default_folder),style=MaterialTheme.typography.bodyMedium)
                    usage?.let { StorageMetric(R.string.storage_available,it.available) }
                    Text(text(R.string.storage_volume_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { StorageSectionTitle(R.string.storage_review) }
                item { FeatureAccess(text(R.string.storage_failed_review),text(R.string.storage_failed_hint),FlowIcons.Retry,MaterialTheme.colorScheme.primary,failed) }
                item { FeatureAccess(text(R.string.storage_duplicates),text(R.string.storage_hash_hint),Icons.Outlined.ContentCopy,MaterialTheme.colorScheme.primary,{perform {duplicates=manager.duplicates()}}) }
                item { FeatureAccess(text(R.string.storage_manage_downloads),text(R.string.storage_manage_hint),FlowIcons.Library,MaterialTheme.colorScheme.primary,library) }
                duplicates?.let { groups ->
                    if (groups.isEmpty()) item { Text(text(R.string.storage_no_duplicates)) }
                    items(groups) { group ->
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(spectrumPluralStringResource(R.plurals.storage_duplicate_group,group.size,group.size),style=MaterialTheme.typography.titleSmall)
                            group.forEach { Text(it.name,style=MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item {
                    TextButton({runCatching {context.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))}}) {
                        Text(text(R.string.storage_android_settings))
                    }
                    TextButton({revision++},enabled=!busy) { Text(text(R.string.storage_refresh)) }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest={confirmation=null},title={Text(text(R.string.storage_cleanup_confirm))},
            text={Text(text(R.string.storage_preservation))},
            confirmButton={TextButton({confirmation=null;perform {if(action=="cache") manager.clearCaches() else manager.cleanTemporary()}}) {Text(text(R.string.ok))}},
            dismissButton={TextButton({confirmation=null}) {Text(text(R.string.cancel))}})
    }
    if (error) AlertDialog(onDismissRequest={error=false},title={Text(text(R.string.problem))},
        text={Text(text(R.string.fm_operation_failed))},confirmButton={TextButton({error=false}) {Text(text(R.string.ok))}})
}

@Composable private fun StorageUsageHeader(usage:StorageUsage) {
    FlowPanel {
            FlowEyebrow(text(R.string.aurora_collection),FlowIcons.Folder)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FlowIconTile(Icons.Outlined.Storage)
                Column(Modifier.weight(1f)) {
                    Text(text(R.string.storage_downloads),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bytesLabel(usage.total),style=MaterialTheme.typography.headlineMedium)
                }
            }
            if (usage.total>0) Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp))) {
                listOf(usage.video to MaterialTheme.colorScheme.primary,usage.audio to MaterialTheme.colorScheme.tertiary,usage.other to MaterialTheme.colorScheme.secondary).forEach { (bytes,color) ->
                    if (bytes>0) Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color))
                }
            }
            Text(text(R.string.storage_counts,usage.counted,usage.missing),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text(R.string.storage_scope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun StorageSectionTitle(label:Int) {
    Column(Modifier.padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Text(text(label),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun StorageMetric(label:Int,bytes:Long,color:Color?=null) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(16.dp)).padding(horizontal=14.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        color?.let { Box(Modifier.size(7.dp).background(it,CircleShape)) }
        Text(text(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text(bytesLabel(bytes),style=MaterialTheme.typography.titleSmall)
    }
}
