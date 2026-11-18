package com.zdownloader.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.ShareReceiverActivity
import com.zdownloader.app.data.DownloadTrashEntry
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun DownloadTrashScreen(model:ZViewModel,back:()->Unit) {
    val entries by model.trash.collectAsStateWithLifecycle()
    val context=LocalContext.current
    DownloadTrashContent(entries,back,{entry->
        // A fresh share opens normal quality selection; no stale stream URL or partial is reused.
        context.startActivity(Intent(context,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,entry.url))
    },model::forgetTrash)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DownloadTrashContent(entries:List<DownloadTrashEntry>,back:()->Unit,download:(DownloadTrashEntry)->Unit,forget:(String)->Unit) {
    var removing by remember {mutableStateOf<DownloadTrashEntry?>(null)}
    Column(Modifier.fillMaxSize().testTag("download_trash")) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            FlowIconButton(FlowIcons.Back,text(R.string.back),back,Modifier.testTag("trash_back"))
            Text(text(R.string.trash_title),Modifier.padding(12.dp),style=MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text(text(R.string.trash_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(entries.isEmpty()) item {FlowEmptyState(text(R.string.trash_empty),text(R.string.trash_empty_hint),FlowIcons.Delete)}
            items(entries,key={it.id}) {entry->
                FlowPanel(Modifier.testTag("trash_item_${entry.id}")) {
                    Text(entry.title.ifBlank {entry.url},style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(entry.url,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Text(text(if(entry.reason=="CANCELLED") R.string.trash_cancelled else R.string.trash_removed),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton({download(entry)},Modifier.testTag("trash_download_${entry.id}")) {Text(text(R.string.trash_download_again))}
                        TextButton({removing=entry},Modifier.testTag("trash_forget_${entry.id}")) {Text(text(R.string.trash_forget))}
                    }
                }
            }
        }
    }
    removing?.let {entry->AlertDialog(onDismissRequest={removing=null},title={Text(text(R.string.trash_forget))},text={Text(text(R.string.trash_forget_confirm))},
        confirmButton={TextButton({forget(entry.id);removing=null},Modifier.testTag("trash_confirm_forget")) {Text(text(R.string.trash_forget))}},
        dismissButton={TextButton({removing=null}) {Text(text(R.string.cancel))}})}
}
