package com.zdownloader.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.zdownloader.app.ui.*
import com.zdownloader.app.domain.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** UI-only callbacks: no source downloads, network calls or device screen commands. */
class QuickShareFormatsReviewTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun host(language:String="ar",fontScale:Float=1f,content:@Composable ()->Unit) {
        compose.runOnUiThread {compose.activity.setContent {
            val base=LocalContext.current
            val locale=java.util.Locale.forLanguageTag(language)
            val configuration=android.content.res.Configuration(LocalConfiguration.current).apply {
                setLocale(locale);setLayoutDirection(locale);this.fontScale=fontScale
            }
            val context=base.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalSpectrumContext provides context,LocalContext provides context,
                LocalConfiguration provides configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density,fontScale),
                LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                key(language,fontScale) {FlowTheme(Spectrum.Dark) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {content()}
                }}
            }
        }}
        compose.waitForIdle()
    }
    private fun capture(name:String) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")
        val folder=File(instrument.targetContext.getExternalFilesDir(null),"advanced-audit/$run/quickshare-font").apply {
            if(!isDirectory) check(mkdirs())
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
            try {File(folder,"$name.png").outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}
            finally {bitmap.recycle()}
        }
    }
    private fun video(id:String,height:Int,codec:String,container:String,audio:String="none")=Format.parse(org.json.JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$container").put("ext",container)
        .put("width",height*16/9).put("height",height).put("fps",30.0).put("vcodec",codec).put("acodec",audio)
        .put("filesize",1_000_000).put("vbr",1_000.0))
    private fun audio(id:String,codec:String,container:String,bitrate:Int)=Format.parse(org.json.JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$container").put("ext",container)
        .put("vcodec","none").put("acodec",codec).put("language","en").put("abr",bitrate)
        .put("audio_channels",2).put("asr",48_000))
    @Test fun unknownMediaOffersNoInventedVideoOrAudioRepresentations() {
        host {ShareInitialFormats()}
        compose.onNodeWithTag("share_classifying_media").assertIsDisplayed()
        for(height in listOf(360,480,720,1080)) compose.onNodeWithTag("share_video_$height").assertDoesNotExist()
        for(tag in listOf("share_audio_mp3","share_more_audio","share_more"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
    }
    @Test fun preExtractionShortcutsAreSelectablePreferencesNotVerifiedFormats() {
        host {
            var selected by remember {mutableIntStateOf(0)}
            ShareInitialPreferences(
                selectedPreference=selected.takeIf {it>0},audioSelected=selected==-1,enabled=true,
                onPreference={selected=it},onAudio={selected=-1},onMore={}
            )
        }
        val tags=listOf("share_preference_mp3","share_preference_360","share_preference_480","share_preference_720","share_more")
        val bounds=tags.map {compose.onNodeWithTag(it).getUnclippedBoundsInRoot()}
        bounds.zipWithNext().forEach {(above,below)->
            assertTrue("Each preference must be below the previous full-width row",above.bottom<=below.top)
            assertEquals(above.left,below.left)
            assertEquals(above.right,below.right)
        }
        for(tag in tags.dropLast(1)) compose.onNodeWithTag("${tag}_description",useUnmergedTree=true).assertExists()
        for(title in listOf("MP3","360p","480p","720p")) {
            val tag=if(title=="MP3") "mp3" else title.dropLast(1)
            compose.onNodeWithTag("share_preference_${tag}_title",useUnmergedTree=true).assertTextEquals(title)
        }
        val descriptions=listOf("صوت فقط","ملف أصغر، مناسب للتنزيل السريع.","جودة جيدة بحجم معتدل.","جودة عالية وصورة أوضح.")
        tags.dropLast(1).zip(descriptions).forEach {(tag,description)->
            compose.onNodeWithTag("${tag}_description",useUnmergedTree=true).assertTextEquals(description)
            val titleBounds=compose.onNodeWithTag("${tag}_title",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val descriptionBounds=compose.onNodeWithTag("${tag}_description",useUnmergedTree=true).getUnclippedBoundsInRoot()
            assertTrue("Description must be underneath its standalone title",titleBounds.bottom<=descriptionBounds.top)
        }
        compose.onNodeWithText("استكشف جميع الصيغ والجودات المتاحة.",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("share_preference_mp3").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("share_preference_360").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("share_preference_360").assertIsSelected()
        compose.onNodeWithTag("share_preference_480").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("share_preference_720").performScrollTo().assertIsDisplayed()
        listOf(360,480,720).forEach {height->compose.onNodeWithTag("share_preference_$height").assertExists()}
        compose.onNodeWithTag("share_video_quality_unknown").assertDoesNotExist()
        compose.onNodeWithTag("share_more").performScrollTo().assertIsDisplayed()
    }
    @Test fun asynchronousSizeLabelsKeepSelectionAndRowGeometryStable() {
        val sizes=mutableStateOf<Map<Int,MediaSize>>(emptyMap())
        host {
            var selected by remember {mutableIntStateOf(480)}
            ShareInitialPreferences(selected,false,true,{selected=it},{selected=-1},{},sizes=sizes.value,resolvedHeights=mapOf(480 to 360))
        }
        val row=compose.onNodeWithTag("share_preference_480")
        val before=row.getUnclippedBoundsInRoot()
        // Empty size labels use a tagged row spacer rather than an empty Text node.
        compose.onNodeWithTag("share_preference_480_size",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle {sizes.value=mapOf(480 to MediaSize(32_000_000,true))}
        row.assertIsSelected()
        assertEquals(before,row.getUnclippedBoundsInRoot())
        compose.onNodeWithTag("share_preference_480_size",useUnmergedTree=true).assertTextContains("≈",substring=true)
        compose.onNodeWithTag("share_preference_480_description",useUnmergedTree=true).assertTextContains("360p",substring=true)
        compose.onNodeWithTag("share_preference_360_size",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("share_preference_720_size",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun enlargedEnglishAndArabicPreferencesRemainReadable() {
        for((language,name) in listOf("en" to "english","ar" to "arabic")) {
            host(language,1.4f) {
                ShareInitialPreferences(480,false,true,{},{},{})
            }
            for(tag in listOf("share_preference_mp3","share_preference_360","share_preference_480","share_preference_720","share_more"))
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            for(tag in listOf("share_preference_mp3","share_preference_360","share_preference_480","share_preference_720")) {
                val title=compose.onNodeWithTag("${tag}_title",useUnmergedTree=true).getUnclippedBoundsInRoot()
                val description=compose.onNodeWithTag("${tag}_description",useUnmergedTree=true).getUnclippedBoundsInRoot()
                assertTrue("$language $tag subtitle must remain below its title",title.bottom<=description.top)
            }
            capture("$name-font140")
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("share_preference_480_description",useUnmergedTree=true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
            val layout=layouts.single()
            val instrument=InstrumentationRegistry.getInstrumentation()
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")
            val folder=File(instrument.targetContext.getExternalFilesDir(null),"advanced-audit/$run/quickshare-font").apply {mkdirs()}
            File(folder,"$name-font140-layout.json").writeText(org.json.JSONObject()
                .put("text",layout.layoutInput.text.text).put("font_scale",layout.layoutInput.density.fontScale)
                .put("width",layout.size.width).put("height",layout.size.height)
                .put("line_count",layout.lineCount).put("paragraph_height",layout.multiParagraph.height)
                .put("paragraph_width",layout.multiParagraph.width)
                .put("available_width",layout.layoutInput.constraints.maxWidth)
                .put("lines",org.json.JSONArray().apply {for(line in 0 until layout.lineCount) put(org.json.JSONObject()
                    .put("left",layout.getLineLeft(line)).put("right",layout.getLineRight(line)))})
                .put("overflow_width",layout.didOverflowWidth).put("overflow_height",layout.didOverflowHeight)
                .put("last_line_ellipsized",layout.isLineEllipsized(layout.lineCount-1)).toString(2))
            assertEquals(if(language=="ar") androidx.compose.ui.text.style.ResolvedTextDirection.Rtl else androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                layout.getParagraphDirection(0))
            // A short RTL label can report its intrinsic size while its paragraph
            // uses the wider allocated column. Check that space, not intrinsic width.
            val availableWidth=layout.layoutInput.constraints.maxWidth
            assertFalse("$language description must fit vertically at 140%",layout.didOverflowHeight)
            assertTrue("$language paragraph must fit the allocated text column",
                layout.multiParagraph.width<=availableWidth+1f)
            for(line in 0 until layout.lineCount) {
                assertFalse("$language description must not be ellipsized",layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line)>=-1f&&layout.getLineRight(line)<=availableWidth+1f)
            }
        }
    }
    @Test fun moreOptionsUsesAscendingSharedVideoTiersAndPreservesAlternativeAndContainerChoice() {
        val media=Media("https://example.com/format-picker","fixture","Format picker fixture","example.com",null,null,12.0,listOf(
            video("1080-main",1080,"avc1","mp4","aac"),
            video("720-h264",720,"avc1","mp4","aac"),
            video("720-vp9",720,"vp09","webm"),
            video("360-main",360,"avc1","mp4","aac"),
            audio("en-audio","mp4a.40.2","m4a",128)))
        val selected=java.util.concurrent.atomic.AtomicReference("720-h264")
        val selectedOutput=java.util.concurrent.atomic.AtomicReference("")
        host(language="en") {
            var audioMode by remember {mutableStateOf(false)}
            var selectedId by remember {mutableStateOf("720-h264")}
            var videoOutput by remember {mutableStateOf("")}
            FormatPicker(media=media,audio=audioMode,primaryId=selectedId,output="original",videoOutput=videoOutput,bitrate=192,audioSourceId=null,
                onAudio={audioMode=it},onPrimary={selectedId=it;selected.set(it)},onOutput={},onVideoOutput={videoOutput=it;selectedOutput.set(it)},
                onBitrate={},onAudioSource={},close={})
        }
        for(tag in listOf("format_video_quality_1080","format_video_quality_720","format_video_quality_360"))
            compose.onNodeWithTag(tag).assertExists()
        val tierTops=listOf(360,720,1080).map {
            compose.onNodeWithTag("format_video_quality_$it").getUnclippedBoundsInRoot().top
        }
        assertTrue("More Options video tiers should be lowest to highest: $tierTops",tierTops.zipWithNext().all {(low,high)->low<high})
        compose.onAllNodesWithTag("format_video_quality_720").assertCountEquals(1)
        compose.onNodeWithTag("format_audio_language_en").assertDoesNotExist()
        compose.onNodeWithTag("format_video_quality_toggle_360").assertDoesNotExist()
        compose.onNodeWithTag("format_video_quality_toggle_720").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("format_video_variant_720-h264").assertExists()
        compose.onNodeWithTag("format_video_variant_720-vp9").performClick()
        assertEquals("720-vp9",selected.get())
        compose.onNodeWithTag("format_output_mkv").assertExists().performClick()
        assertEquals("mkv",selectedOutput.get())
        compose.onAllNodesWithTag("format_video_quality_720").assertCountEquals(1)
        compose.onNodeWithTag("format_audio").performClick()
        compose.onNodeWithTag("format_audio_language_en").assertExists()
        compose.onNodeWithTag("format_video_quality_720").assertDoesNotExist()
        compose.onNodeWithTag("format_video").performClick()
        compose.onNodeWithTag("format_audio_language_en").assertDoesNotExist()
        compose.onNodeWithTag("format_video_quality_720").assertExists()
    }

    @Test fun videoVariantChipsWrapSelectAndStayReadableAcrossCountsAndRtl() {
        val codecs=listOf("avc1" to "mp4","vp09" to "webm","av01" to "mp4","hev1" to "mkv","theora" to "ogg")
        @Composable fun content(count:Int) {
            var selected by remember {mutableStateOf("chip-0")}
            val chips=codecs.take(count).mapIndexed {index,(codec,container)->
                VideoVariantChipSpec(video("chip-$index",720,codec,container),selected=="chip-$index",
                    recommended=index==0,details="1920×1080 · 30fps · source-$index",
                    tag="video_variant_chip_$index")
            }
            VideoVariantChips(chips,enabled=true,recommendedLabel="Recommended",
                modifier=Modifier.fillMaxWidth().testTag("video_variant_chip_grid")) {selected=it.id}
        }
        for(count in 2..5) {
            host(language="en") {content(count)}
            val grid=compose.onNodeWithTag("video_variant_chip_grid").getUnclippedBoundsInRoot()
            val bounds=(0 until count).map {index->
                val node=compose.onNodeWithTag("video_variant_chip_$index").assertExists().assertIsDisplayed()
                node.getUnclippedBoundsInRoot().also {assertTrue("chip $index must fit inside its grid",it.left>=grid.left&&it.right<=grid.right)}
            }
            for(first in bounds.indices) for(second in first+1 until bounds.size) {
                val a=bounds[first];val b=bounds[second]
                if(kotlin.math.abs((a.top-b.top).value)<12f)
                    assertTrue("chips on one line must not overlap",a.right<=b.left||b.right<=a.left)
            }
            compose.onNodeWithTag("video_variant_chip_1").performClick().assertIsSelected()
            compose.onNodeWithText("VP9",useUnmergedTree=true).assertExists()
        }
        host(language="ar",fontScale=1.4f) {content(5)}
        for(index in 0..4) compose.onNodeWithTag("video_variant_chip_$index").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("H.264",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("AV1",useUnmergedTree=true).assertExists()
        capture("rtl-video-variant-chips-font140")
    }

    @Test fun moreOptionsAudioGroupsKeepAllCodecContainerVariantsAndExactSelection() {
        val low=audio("en-opus-low","opus","webm",96)
        val high=audio("en-opus-high","opus","webm",256)
        val aac=audio("en-aac","mp4a.40.2","m4a",192)
        val media=Media("https://example.com/audio-picker","audio-fixture","Audio picker fixture","example.com",null,null,12.0,
            listOf(video("720",720,"avc1","mp4","aac"),low,high,aac))
        val selected=java.util.concurrent.atomic.AtomicReference(low.id)
        host(language="en") {
            var selectedSource by remember {mutableStateOf(low.id)}
            FormatPicker(media=media,audio=true,primaryId="720",output="original",videoOutput="",bitrate=192,audioSourceId=selectedSource,
                onAudio={},onPrimary={},onOutput={},onVideoOutput={},onBitrate={},
                onAudioSource={selectedSource=it;selected.set(it)},close={})
        }
        compose.onAllNodesWithTag("format_audio_language_en").assertCountEquals(1)
        compose.onNodeWithTag("format_audio_language_toggle_en").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("format_audio_source_${low.id}").assertExists()
        compose.onNodeWithTag("format_audio_source_${high.id}").performClick()
        assertEquals(high.id,selected.get())
        compose.onNodeWithTag("format_audio_source_${aac.id}").assertExists()
        compose.onAllNodesWithTag("format_audio_language_en").assertCountEquals(1)
    }
}
