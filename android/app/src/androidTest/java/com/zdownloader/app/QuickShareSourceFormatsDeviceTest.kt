package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareDimensions
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Source URL is supplied at runtime. Evidence excludes transport URLs and headers. */
class QuickShareSourceFormatsDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun rawExtractorFormatsMatchTheSelectableCatalog()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=requireNotNull(args.getString("sourceUrl"))
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"format-review/$run")
        check(folder.mkdirs())
        val raw=app.extractor.pythonCall("analyze",url,"format-review-${UUID.randomUUID()}")
        assertFalse(raw.optString("error"),raw.has("error"))
        val media=Media.parse(raw,url)
        val keys=listOf("format_id","format","format_note","resolution","width","height","ext",
            "protocol","vcodec","acodec","fps","tbr","vbr","abr","combined_tbr","filesize",
            "filesize_approx","dynamic_range","language","audio_channels","asr","mediaflow_source_quality_label")
        val formats=requireNotNull(raw.optJSONArray("formats"))
        val sanitized=JSONArray((0 until formats.length()).map {i->
            val entry=formats.getJSONObject(i)
            JSONObject().apply {keys.forEach {key->if(entry.has(key)) put(key,entry.get(key))}}
        })
        val preferences=JSONArray(QuickShareCatalog.compactPreferences.map {height->
            val selection=VideoPreferenceSelection.ready(media,height)
            JSONObject().put("preference",height).put("resolved_id",selection?.primary?.id)
                .put("resolved_edge",selection?.primary?.let(::verifiedQualityEdge))
                .put("output_bytes",selection?.outputSize?.bytes).put("estimated",selection?.outputSize?.estimated)
        })
        val entries=QuickShareCatalog.variantEntries(media)
        val displayed=entries.filter {it.selection!=null}.map {it.format}
        File(folder,"report.json").writeText(JSONObject().put("source_url",url).put("id",media.id)
            .put("duration",media.duration).put("raw_formats",sanitized).put("preferences",preferences)
            .put("parsed_ids",JSONArray(media.formats.map {it.id}))
            .put("selectable_ids",JSONArray(entries.filter {it.selection!=null}.map {it.format.id}))
            .put("displayed_ids",JSONArray(displayed.map {it.id})).toString(2))
        assertTrue("Source has no selectable videos",entries.any {it.selection!=null})
        assertEquals(displayed.size,displayed.map {it.id}.distinct().size)
        assertEquals(entries.filter {it.selection!=null}.map {it.format.id},displayed.map {it.id})
        displayed.forEach {format->assertEquals(format.id,QuickShareChoice(false,format.id).resolve(media)?.primary?.id)}
    }

    @Test fun liveShareShowsActualPixelsAndIndividuallySelectableEncodings() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=requireNotNull(args.getString("sourceUrl"))
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"format-ui/$run")
        check(folder.mkdirs())
        fun screenshot(name:String) {
            val bitmap=requireNotNull(instrument.uiAutomation.takeScreenshot())
            try {File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
            finally {bitmap.recycle()}
        }
        val intent=Intent(Intent.ACTION_SEND).setClass(app,ShareReceiverActivity::class.java)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            lateinit var model:QuickShareViewModel
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_preferences"))
            compose.onNodeWithTag("share_preferences").performClick()
            compose.onNodeWithTag("share_preference_720").performScrollTo().performClick()
            compose.waitUntil(90_000) {model.state.value.media!=null&&!model.state.value.analyzing}
            val media=requireNotNull(model.state.value.media)
            val sizes=JSONArray()
            for(height in QuickShareCatalog.compactPreferences) {
                val tag="share_preference_$height"
                compose.onNodeWithTag(tag).performScrollTo()
                compose.onNodeWithTag("${tag}_title",useUnmergedTree=true).assertTextEquals("${height}p")
                val selection=VideoPreferenceSelection.ready(media,height)
                if(selection==null||selection.outputSize==null)
                    compose.onNodeWithTag("${tag}_size",useUnmergedTree=true).assertDoesNotExist()
                sizes.put(JSONObject().put("preference",height).put("resolved_id",selection?.primary?.id)
                    .put("bytes",selection?.outputSize?.bytes))
            }
            compose.onNodeWithTag("share_preference_720").assertIsSelected()
            compose.onNodeWithTag("share_preference_mp3").performScrollTo()
            screenshot("compact")
            compose.onNodeWithTag("share_more").performScrollTo().performClick()
            compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
        val entries=QuickShareCatalog.variantEntries(media).filter {it.selection!=null}
        val sources=entries.map {it.format}
        assertEquals(entries.map {it.format.id},sources.map {it.id})
        val rows=JSONArray()
        for(source in sources) {
                val tag=compose.revealQuickShareVideoSource(media,source)
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick().assertIsSelected()
                assertEquals(source.id,model.state.value.choice?.formatId)
                assertEquals(source.id,model.state.value.selection?.primary?.id)
                val dimensions=QuickShareDimensions.from(source)
                if(tag.startsWith("share_video_variant_")) {
                    val details=compose.onNodeWithTag("${tag}_description",useUnmergedTree=true)
                    details.assertTextContains(source.ext.uppercase(),substring=true)
                    val rates=sources.filter {QuickShareDimensions.from(it)==dimensions}
                        .map {it.videoBitrate ?: it.bitrate}.distinct()
                    if(rates.size>1) (source.videoBitrate ?: source.bitrate)?.let {rate->
                        details.assertTextContains(java.math.BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString(),substring=true)
                    }
                } else {
                    val title=compose.onNodeWithTag("${tag}_title",useUnmergedTree=true)
                    if(dimensions.width!=null) title.assertTextContains("${dimensions.width}×${dimensions.height}",substring=true)
                    else if(dimensions.height!=null) title.assertTextContains("${dimensions.height}p",substring=true)
                }
                rows.put(JSONObject().put("id",source.id).put("width",source.width).put("height",source.height)
                    .put("rate",source.videoBitrate ?: source.bitrate).put("bytes",QuickShareCatalog.videoDisplaySize(media,source)?.bytes))
            }
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(compose.revealQuickShareVideoSource(media,sources.first())))
            screenshot("expanded")
            File(folder,"report.json").writeText(JSONObject().put("passed",true).put("sizes",sizes).put("rows",rows)
                .put("language",model.settings.value.language).put("download_enqueued",false).toString(2))
        }
    }
}
