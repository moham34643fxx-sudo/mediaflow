package com.zdownloader.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.*
import com.zdownloader.app.ui.*
import org.junit.Rule
import org.junit.Test

class AudioProcessingProgressUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun approximateEncodingAndFinalizationAreDistinctFromFullValidation() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.zdownloader.app.designreview")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id="processing-ui-check"
        val progress=AudioProcessingProgress.start(id,10.0)
        var status by mutableStateOf("CONVERTING")
        try {ActivityScenario.launch(ComponentActivity::class.java).use {scenario->
            scenario.onActivity {it.setContent {FlowTheme(Spectrum.Dark) {AudioProcessingIndicator(id,status)}}}
            progress.sample(5000.0)
            compose.onNodeWithTag("audio_processing_$id").assertTextEquals(context.getString(R.string.audio_processing_percent,50))
            progress.phase(AudioProcessingPhase.FINALIZING)
            compose.onNodeWithTag("audio_processing_$id").assertTextEquals(context.getString(R.string.audio_processing_finalizing))
            scenario.onActivity {status="VALIDATING";progress.phase(AudioProcessingPhase.VALIDATING)}
            compose.onNodeWithTag("audio_processing_$id").assertTextEquals(context.getString(R.string.audio_processing_validating))
            progress.close()
            compose.onNodeWithTag("audio_processing_$id").assertDoesNotExist()
        }} finally {progress.close()}
    }
}
