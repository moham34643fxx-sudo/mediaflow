package com.zdownloader.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.MainActivity
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.MediaIntelligence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Offline candidate-device UI test seeded from the saved HONOR catalog's codec/container shape. */
class CodecContainerPickerDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()

    private fun video(id:String,codec:String,ext:String)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$ext").put("ext",ext)
        .put("width",1280).put("height",720).put("fps",30).put("dynamic_range","SDR")
        .put("vcodec",codec).put("acodec","none").put("vbr",1500))

    private fun audio(id:String,codec:String,ext:String)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$ext").put("ext",ext)
        .put("vcodec","none").put("acodec",codec).put("language","ar")
        .put("language_preference",10).put("audio_channels",2).put("asr",48000).put("abr",128))

    private fun savedCatalogShape()=Media("https://example.com/watch","saved-format-shape","Fixture","example.com",
        null,null,30.0,listOf(
            video("av1-720-mp4","av01.0.08M.08","mp4"),
            video("vp9-720-mp4","vp09.00.31.08","mp4"),
            video("vp9-720-webm","vp09.00.31.08","webm"),
            video("h264-720-mp4","avc1.64001f","mp4"),
            audio("aac-ar","mp4a.40.2","m4a"),audio("opus-ar","opus","webm")))

    @Test fun sameQualityAlternativesAndCompatibleWebmAreRenderedAndSelectable() {
        val media=savedCatalogShape()
        val primary=mutableStateOf("av1-720-mp4")
        val output=mutableStateOf("mp4")
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                val context=LocalContext.current
                CompositionLocalProvider(LocalSpectrumContext provides context) {
                    FlowTheme(Spectrum.Dark) {
                        FormatPicker(media=media,audio=false,primaryId=primary.value,output="original",
                            videoOutput=output.value,bitrate=192,audioSourceId=null,onAudio={},
                            onPrimary={primary.value=it},onOutput={},onVideoOutput={output.value=it},
                            onBitrate={},onAudioSource={},close={})
                    }
                }
            }}
            compose.waitForIdle()

            val ids=listOf("av1-720-mp4","vp9-720-mp4","vp9-720-webm","h264-720-mp4")
            compose.onAllNodesWithTag("format_video_quality_720").assertCountEquals(1)
            compose.onNodeWithTag("format_video_quality_toggle_720").performScrollTo().performClick()
            compose.waitForIdle()
            for(id in ids) compose.onNodeWithTag("format_video_variant_$id")
                .performScrollTo().assertIsDisplayed()

            compose.onNodeWithTag("format_video_variant_vp9-720-mp4").performScrollTo().performClick()
            assertEquals("vp9-720-mp4",primary.value)
            compose.onNodeWithTag("format_output_webm").performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals("webm",output.value)

            val caps=MediaIntelligence.platformCapabilities
            val device=InstrumentationRegistry.getInstrumentation().targetContext
            println("CANDIDATE_FORMAT_UI model=${android.os.Build.MODEL} device=${android.os.Build.DEVICE} api=${android.os.Build.VERSION.SDK_INT} " +
                "hw_av1=${caps.hasHardwareAv1} hw_vp9=${caps.hasHardwareVp9} hw_avc=${caps.hasHardwareAvc} hw_hevc=${caps.hasHardwareHevc} " +
                "visible_sources=${ids.size} selected=${primary.value} output=${output.value} package=${device.packageName}")
            assertTrue("WebM output was not committed by the rendered chip",output.value=="webm")
        }
    }
}
