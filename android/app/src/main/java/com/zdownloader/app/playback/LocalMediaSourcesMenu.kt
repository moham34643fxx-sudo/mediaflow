package com.zdownloader.app.playback

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.MediaFile
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.spectrumStringResource as text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Platform pickers stay available without becoming the everyday Library destination. */
@Composable internal fun LocalMediaSourcesMenu(connection: PlaybackConnection) {
    val context=LocalContext.current
    val files=(context.applicationContext as ZApplication).mediaFiles
    val scope=rememberCoroutineScope()
    var expanded by remember {mutableStateOf(false)}
    var image by remember {mutableStateOf<MediaFile?>(null)}
    var error by remember {mutableStateOf(false)}
    fun openVisual(uri: Uri) {
        retainReadAccess(context,uri)
        scope.launch {
            try {
                val file=files.metadata(uri.toString())
                when {
                    file.image->image=file
                    file.video->connection.playUri(file.uri,file.name,video=true)
                    else->error=true
                }
            } catch(e:CancellationException) {throw e} catch(_:Exception) {error=true}
        }
    }
    val documents=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
        uris.forEach {retainReadAccess(context,it)}
        if(uris.isNotEmpty()) connection.play(uris.map {PlaybackMedia(it.toString(),"",false)})
    }
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri->uri?.let(::openVisual)}
    val imageDocument=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let(::openVisual)}
    Box {
        FlowIconButton(FlowIcons.More,text(R.string.local_sources),{expanded=true},Modifier.testTag("library_external_sources"))
        DropdownMenu(expanded,{expanded=false}) {
            DropdownMenuItem(text={Text(text(R.string.local_photo_picker))},onClick={expanded=false;photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},modifier=Modifier.testTag("library_photo_picker"))
            DropdownMenuItem(text={Text(text(R.string.local_document_picker))},onClick={expanded=false;documents.launch(arrayOf("audio/*","video/*","application/ogg"))},modifier=Modifier.testTag("library_document_picker"))
            DropdownMenuItem(text={Text(text(R.string.local_image_picker))},onClick={expanded=false;imageDocument.launch(arrayOf("image/*"))},modifier=Modifier.testTag("library_image_picker"))
        }
    }
    image?.let {LocalImagePreview(listOf(it),it.uri) {image=null}}
    if(error) AlertDialog(onDismissRequest={error=false},title={Text(text(R.string.problem))},text={Text(text(R.string.fm_operation_failed))},confirmButton={TextButton({error=false}) {Text(text(R.string.ok))}})
}

private fun retainReadAccess(context:android.content.Context,uri:Uri) {
    try {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    catch(_:SecurityException) { /* Some providers offer session-only access; opening still remains valid. */ }
}
