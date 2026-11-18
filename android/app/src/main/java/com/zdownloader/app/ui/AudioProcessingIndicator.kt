package com.zdownloader.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.engine.AudioProcessingPhase
import com.zdownloader.app.engine.AudioProcessingProgress

/** Approximate source-media position, not a percentage of completed publication. */
@Composable internal fun AudioProcessingIndicator(id:String,status:String) {
    val progress by AudioProcessingProgress.state.collectAsStateWithLifecycle()
    val sample=progress[id]?.takeIf {
        if(it.phase==AudioProcessingPhase.VALIDATING) status=="VALIDATING" else status in setOf("CONVERTING","PROCESSING")
    }
    if(sample==null) {FlowLoadingIndicator();return}
    val percent=sample.percent.takeIf {sample.phase==AudioProcessingPhase.ENCODING&&sample.positionMs>0}
    Text(when(sample.phase) {
        AudioProcessingPhase.ENCODING->percent?.let {stringResource(R.string.audio_processing_percent,it)} ?: stringResource(R.string.audio_processing_encoding)
        AudioProcessingPhase.FINALIZING->stringResource(R.string.audio_processing_finalizing)
        AudioProcessingPhase.VALIDATING->stringResource(R.string.audio_processing_validating)
    },Modifier.testTag("audio_processing_$id"),style=MaterialTheme.typography.bodySmall)
    if(percent!=null) MediaFlowProgress(percent/100f) else FlowLoadingIndicator()
}
