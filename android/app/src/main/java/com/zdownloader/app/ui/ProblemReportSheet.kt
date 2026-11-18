package com.zdownloader.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.domain.ProblemReport
import com.zdownloader.app.domain.ReportProblem
import com.zdownloader.app.domain.safeReportUrl
import com.zdownloader.app.ui.spectrumStringResource as text

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProblemReportSheet(visible:Boolean,url:String="",error:String="",close:()->Unit) {
    var link by rememberSaveable {mutableStateOf(safeReportUrl(url).orEmpty())}
    var description by rememberSaveable {mutableStateOf("")}
    var problem by rememberSaveable {mutableStateOf(reportProblemFor(error).name)}
    var capturedError by rememberSaveable {mutableStateOf(error)}
    var edited by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(visible,url,error) {
        if(visible&&!edited) {link=safeReportUrl(url).orEmpty();capturedError=error;problem=reportProblemFor(error).name}
    }
    var openFailure by rememberSaveable {mutableStateOf(false)}
    val context=LocalContext.current
    if(!visible) return
    val report=ProblemReport(link,ReportProblem.valueOf(problem),description,capturedError)
    val validation=report.validationError()
    val preview=if(validation==null) report.message(BuildConfig.VERSION_NAME) else null
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surfaceContainerLow,
        shape=MaterialTheme.shapes.extraLarge,dragHandle={FlowSheetHandle()}) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal=24.dp).testTag("problem_report_form"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(text(R.string.report_title),style=MaterialTheme.typography.headlineSmall)
            Text(text(R.string.report_privacy),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(link,{link=it;edited=true;openFailure=false},Modifier.fillMaxWidth().testTag("report_url"),
                label={Text(text(R.string.report_url))},maxLines=3,isError=validation=="url")
            var expanded by remember {mutableStateOf(false)}
            Box {
                OutlinedButton({expanded=true},Modifier.fillMaxWidth().testTag("report_type")) {Text(text(problemLabel(report.problem)))}
                DropdownMenu(expanded,{expanded=false}) {ReportProblem.entries.forEach {kind->
                    DropdownMenuItem(text={Text(text(problemLabel(kind)))},onClick={problem=kind.name;edited=true;expanded=false},modifier=Modifier.testTag("report_type_${kind.name}"))
                }}
            }
            OutlinedTextField(description,{description=it;edited=true;openFailure=false},Modifier.fillMaxWidth().testTag("report_description"),
                label={Text(text(R.string.report_description))},minLines=3,maxLines=6)
            if(validation!=null) Text(text(if(validation=="url") R.string.report_invalid_url else R.string.report_required),style=MaterialTheme.typography.bodySmall)
            if(preview!=null) {
                Text(text(R.string.report_preview),style=MaterialTheme.typography.titleSmall)
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
                    Text(preview,Modifier.fillMaxWidth().padding(16.dp).testTag("report_preview"),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(openFailure) Text(text(R.string.report_open_failed),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("report_open_failed"))
            PrimaryAction(onClick={
                openFailure=!openProblemReport(context,report.whatsAppUrl(BuildConfig.VERSION_NAME))
            },modifier=Modifier.fillMaxWidth().testTag("report_whatsapp"),enabled=validation==null) {Text(text(R.string.report_send))}
            Text(text(R.string.report_manual_send),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(close,Modifier.fillMaxWidth().testTag("report_close")) {Text(text(R.string.report_keep_draft))}
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun problemLabel(problem:ReportProblem):Int=when(problem) {
    ReportProblem.UNSUPPORTED->R.string.report_type_unsupported
    ReportProblem.ANALYSIS->R.string.report_type_analysis
    ReportProblem.DOWNLOAD->R.string.report_type_download
    ReportProblem.MEDIA->R.string.report_type_media
    ReportProblem.OTHER->R.string.report_type_other
}

internal fun reportProblemFor(error:String):ReportProblem=when(error) {
    "UNSUPPORTED_PLATFORM"->ReportProblem.UNSUPPORTED
    "INVALID_URL","URL_POLICY_REJECTED","EXTRACTION_FAILED","EXTRACTION_TIMEOUT","VIDEO_NOT_FOUND","PRIVATE_CONTENT","AUTHENTICATION_REQUIRED","DRM_PROTECTED","GEO_RESTRICTED","FORMAT_UNAVAILABLE","QUALITY_UNAVAILABLE"->ReportProblem.ANALYSIS
    "SOURCE_CORRUPT","ENCODER_UNAVAILABLE","DECODER_UNAVAILABLE","MERGE_FAILED","CONVERSION_FAILED","OUTPUT_VALIDATION_FAILED","MEDIA_METADATA_MISMATCH"->ReportProblem.MEDIA
    ""->ReportProblem.OTHER
    else->ReportProblem.DOWNLOAD
}

internal fun openProblemReport(context:Context,url:String):Boolean {
    for(targetPackage in listOf("com.whatsapp","com.whatsapp.w4b",null)) {
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply {setPackage(targetPackage)}
        try {context.startActivity(intent);return true}
        catch(_:ActivityNotFoundException) { /* Try the other installed client, then the system browser. */ }
        catch(_:SecurityException) { /* A restricted client must not prevent the browser fallback. */ }
    }
    return false
}

@Composable internal fun HelpAndReport(back:()->Unit,onReport:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text(text(R.string.report_help_title),style=MaterialTheme.typography.headlineSmall)
        Text(text(R.string.report_help_solutions),style=MaterialTheme.typography.bodyLarge)
        PrimaryAction(onReport,Modifier.fillMaxWidth().testTag("help_report_problem")) {Text(text(R.string.report_action))}
        TextButton(back,Modifier.testTag("help_back")) {Text(text(R.string.back))}
    }
}
