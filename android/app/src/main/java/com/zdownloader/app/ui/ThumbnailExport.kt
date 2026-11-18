package com.zdownloader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.ThumbnailData
import com.zdownloader.app.domain.FileNames
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.awaitResponse
import kotlinx.coroutines.*
import okhttp3.Request
import com.zdownloader.app.ui.spectrumStringResource as text

/** Explicit original-image export through Android's Create Document UI. Bounded, public HTTPS only. */
@Composable internal fun ThumbnailExport(url:String?,title:String,onError:()->Unit) {
    if(url==null) return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val assets=(context.applicationContext as ZApplication).mediaAssets
    var thumbnail by remember {mutableStateOf<ThumbnailData?>(null)}
    var busy by remember {mutableStateOf(false)}
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        val data=thumbnail;thumbnail=null
        val uri=result.data?.data
        if(result.resultCode==Activity.RESULT_OK&&uri!=null) {
            busy=true
            scope.launch {try {if(data==null) throw java.io.IOException();assets.saveThumbnail(uri,data)}
                catch(e:CancellationException) {throw e} catch(_:Exception) {onError();runCatching {android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}} finally {busy=false}}
        }
    }
    TextButton({if(!busy) {busy=true;scope.launch {
        try {
            val data=assets.thumbnail(url);thumbnail=data
            destination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(data.mime).putExtra(Intent.EXTRA_TITLE,FileNames.sanitize(title)+".${data.extension}"))
        } catch(e:CancellationException) {throw e} catch(_:Exception) {onError()} finally {busy=false}
    }}},enabled=!busy,modifier=Modifier.testTag("save_thumbnail")) {Text(text(R.string.advanced_save_thumbnail))}
}
