package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import com.zdownloader.app.ui.formatSizeLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Live metadata and real controls; this test never enqueues a download. */
class QuickShareVerticalLiveDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun verticalPreferencesShowRealSizesAndMoreFormatsContainsEverySelectableSource() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"vertical-layout/$run")
        check(folder.mkdirs())
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?vertical=${UUID.randomUUID()}"
        val intent=Intent(Intent.ACTION_SEND).setClass(app,ShareReceiverActivity::class.java)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            lateinit var model:QuickShareViewModel
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_preference_720").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_preference_720").performScrollTo().performClick()
            compose.waitUntil(90_000) {model.state.value.media!=null&&!model.state.value.analyzing}
            val media=requireNotNull(model.state.value.media)
            val context=app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(model.settings.value.language))
            })
            val sizes=JSONArray()
            var knownSizes=0
            for(height in QuickShareCatalog.compactPreferences) {
                val tag="share_preference_$height"
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val selection=VideoPreferenceSelection.ready(media,height)
                val size=selection?.outputSize
                val edge=selection?.primary?.let(::verifiedQualityEdge)
                val expected=size?.let {
                    val label=formatSizeLabel(context,it)
                    if(edge!=null&&edge!=height) "$label\n\u2066${edge}p\u2069" else label
                }.orEmpty()
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasTestTag("${tag}_size") and hasText(expected),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("${tag}_size",useUnmergedTree=true).assertTextEquals(expected)
                if(size!=null) knownSizes++
                sizes.put(JSONObject().put("preference",height).put("resolved_edge",edge).put("bytes",size?.bytes).put("estimated",size?.estimated))
            }
            assertTrue("Live HLS metadata should provide at least one reliable size estimate",knownSizes>0)
            compose.onNodeWithTag("share_preference_720").assertIsSelected()
            assertEquals(720,model.state.value.qualityPreference)
            compose.onNodeWithTag("share_preference_mp3").performScrollTo()
            val tags=listOf("share_preference_mp3","share_preference_360","share_preference_480","share_preference_720","share_more")
            val bounds=tags.map {compose.onNodeWithTag(it).getUnclippedBoundsInRoot()}
            bounds.zipWithNext().forEach {(above,below)->
                assertTrue(above.bottom<=below.top)
                assertEquals(above.left,below.left);assertEquals(above.right,below.right)
            }
            compose.waitForIdle()
            val bitmap=requireNotNull(instrument.uiAutomation.takeScreenshot())
            try {File(folder,"vertical-ready.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
                finally {bitmap.recycle()}
            compose.onNodeWithTag("share_more").performScrollTo().performClick()
            compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
            val sources=QuickShareCatalog.variantEntries(media).map {it.format}
            assertTrue(sources.isNotEmpty())
            for(source in sources) {
                val tag=compose.revealQuickShareVideoSource(media,source)
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                assertNotNull(QuickShareChoice(false,source.id).resolve(media))
            }
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_audio_mp3"))
            compose.onNodeWithTag("share_audio_mp3").assertIsDisplayed()
            compose.onNodeWithTag("share_back").performClick()
            compose.onNodeWithTag("share_preference_720").performScrollTo().assertIsSelected()
            File(folder,"report.json").writeText(JSONObject().put("state","passed").put("source",media.source)
                .put("sizes",sizes).put("selectable_source_ids",JSONArray(sources.map {it.id}))
                .put("selection_preserved",true).put("download_enqueued",false).toString(2))
        }
    }
}
