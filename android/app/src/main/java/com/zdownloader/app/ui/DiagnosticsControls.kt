package com.zdownloader.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zdownloader.app.R
import com.zdownloader.app.engine.DiagnosticsExport
import com.zdownloader.app.engine.NetworkDiagnostics
import com.zdownloader.app.engine.PerformanceCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun DiagnosticsControls() {
    if(!com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS) return
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    val capturing by PerformanceCapture.state.collectAsState()
    var status by remember {mutableStateOf<Int?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {uri->
        if(uri!=null) scope.launch {
            busy=true;status=null
            try {
                val rows=NetworkDiagnostics.exportSnapshot()
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"w")).bufferedWriter().use {out->
                        DiagnosticsExport().write(rows,out)
                    }
                }
                status=R.string.diagnostics_saved
            } catch(e:kotlinx.coroutines.CancellationException) {throw e}
            catch(_:Exception) {status=R.string.diagnostics_failed}
            finally {busy=false}
        }
    }
    Column {
        Text(stringResource(R.string.diagnostics_title))
        Text(stringResource(R.string.diagnostics_privacy))
        TextButton(enabled=!busy,onClick={save.launch("MediaFlow-diagnostics.jsonl")}) {Text(stringResource(R.string.diagnostics_export))}
        TextButton(enabled=!busy,onClick={
            val activity=context.activity()
            try {
                if(PerformanceCapture.active) PerformanceCapture.stop() else if(activity!=null) PerformanceCapture.start(activity)
            } catch(_:SecurityException) {status=R.string.diagnostics_failed}
        }) {Text(stringResource(if(capturing) R.string.diagnostics_stop else R.string.diagnostics_start))}
        status?.let {Text(stringResource(it))}
    }
}
private fun Context.activity():Activity? = when(this) {is Activity->this;is ContextWrapper->baseContext.takeIf {it!==this}?.activity();else->null}
