package com.zdownloader.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.*
import org.junit.Rule
import org.junit.Test

/** Live platform-setting contract, not a synthetic screenshot of a shipping screen. */
class FlowMotionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    @Test fun changingSystemMotionSettingStopsLoadingMotionWithoutDisablingActions() {
        val original=shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 1")
            compose.activityRule.scenario.onActivity {activity->activity.setContent {
                CompositionLocalProvider(LocalSpectrumContext provides activity) {
                    FlowTheme(Spectrum.Dark) {
                        FlowLoadingIndicator(Modifier.testTag("probe_loading"),compact=true)
                    }
                }
            }}
            compose.onNodeWithTag("probe_loading").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
            shell("settings put global animator_duration_scale 0")
            compose.waitUntil(10000) {compose.onAllNodesWithTag("reduced_motion_loading").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("probe_loading").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
            // Recreate the real app UI and verify native navigation at scale zero.
            // Avoid compiling test-owned Material/layout defaults against R8's
            // optimized internal singleton fields in the release target APK.
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected()
            compose.onNodeWithTag("nav_home").performClick().assertIsSelected()
            compose.activityRule.scenario.onActivity {activity->activity.setContent {
                CompositionLocalProvider(LocalSpectrumContext provides activity) {
                    FlowTheme(Spectrum.Dark) {FlowLoadingIndicator(Modifier.testTag("probe_loading"),compact=true)}
                }
            }}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("reduced_motion_loading").fetchSemanticsNodes().isNotEmpty()}
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(10000) {compose.onAllNodesWithTag("reduced_motion_loading").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("probe_loading").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        } finally {shell(if(original=="null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $original")}
    }
}
