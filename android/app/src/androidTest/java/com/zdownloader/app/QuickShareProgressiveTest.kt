package com.zdownloader.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.DirectMediaResolver
import com.zdownloader.app.engine.Extractor
import com.zdownloader.app.engine.ExtractorMetadata
import com.zdownloader.app.engine.MetadataRequest
import com.zdownloader.app.engine.MetadataRoute
import com.zdownloader.app.ui.QuickShareSheet
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QuickShareProgressiveTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication

    @Test fun moreRevealsReadyRowsWithoutStartingDiscoveryAndRetriesOnlyTheFailedSource() {
        val fast=HeaderReply(854,480)
        val failed=HeaderReply()
        val retry=HeaderReply(640,176,blocked=false)
        val headers=HeaderFixture(mapOf("fast" to listOf(fast),"flaky" to listOf(failed,retry)))
        val media=media("ready",video("ready",320,176),video("fast"),video("flaky"))

        withSheet(headers,media) {model->
            waitForVisible("share_video_quality_320x176")
            assertEquals(setOf("fast","flaky"),model.state.value.pendingDimensionIds)
            tap("share_more")
            compose.onNodeWithTag("share_more_checking",useUnmergedTree=true).assertIsDisplayed()
            tap("share_video_quality_320x176")
            compose.onNodeWithTag("share_download").assertIsEnabled()
            compose.runOnIdle {model.accept(media.url);model.expand();model.expand()}

            fast.release()
            waitForVisible("share_video_quality_854x480")
            assertEquals(setOf("flaky"),model.state.value.pendingDimensionIds)
            assertEquals("ready",model.state.value.selection?.primary?.id)
            compose.onNodeWithTag("share_video_quality_320x176").assertIsSelected()

            failed.release()
            compose.waitUntil(10_000) {model.state.value.catalogReady&&!model.state.value.discoveringFormats}
            tap("share_more")
            tap("share_more")
            show("share_more_failure").assertIsDisplayed()
            compose.onNodeWithTag("share_more_checking",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithTag("share_download").assertIsEnabled()
            assertEquals(mapOf("fast" to 1,"flaky" to 1),headers.counts.toMap())

            tap("share_retry_sources")
            compose.waitUntil(10_000) {model.state.value.failedDimensionIds.isEmpty()&&!model.state.value.discoveringFormats}
            assertEquals(mapOf("fast" to 1,"flaky" to 2),headers.counts.toMap())
            show("share_video_quality_320x176").assertIsSelected()
            show("share_video_quality_640x176").assertIsEnabled().assertIsNotSelected()
            assertEquals("ready",model.state.value.selection?.primary?.id)
        }
    }

    @Test fun selectsAndSchedulesARealRowWhileItsOtherSourceIsStillPending()=runBlocking {
        val slow=HeaderReply(1280,720)
        val headers=HeaderFixture(mapOf("slow" to listOf(slow)))
        val media=media("planning",video("ready",320,176),video("slow"))
        val plan=DownloadPlan(System.currentTimeMillis()+3_600_000,DownloadNetwork.WIFI,true)
        try {
            withSheet(headers,media) {model->
                waitForVisible("share_video_quality_320x176")
                tap("share_video_quality_320x176")
                show("share_schedule").assertIsEnabled()
                compose.runOnIdle {
                    assertEquals(setOf("slow"),model.state.value.pendingDimensionIds)
                    model.schedule(plan)
                    model.schedule(plan)
                    model.enqueue()
                }
                compose.waitUntil(10_000) {model.state.value.queuedId!=null}
            }
            val row=app.repository.dao.observe().first().single {it.url==media.url}
            assertEquals("SCHEDULED",row.status)
            assertEquals("ready",Selection.parse(row.selection).primary.id)
            assertNull(row.request)
            assertEquals(plan.at,row.scheduledAt)
            assertEquals("WIFI",row.scheduleNetwork)
            assertTrue(row.requiresCharging)
            assertEquals(mapOf("slow" to 1),headers.counts.toMap())
        } finally {removeFixtureRows(media.url)}
    }

    @Test fun replacingTheLinkCannotPublishOrReuseTheOldSelection() {
        val late=HeaderReply(1280,720)
        val headers=HeaderFixture(mapOf("old-probe" to listOf(late)))
        val old=media("old",video("ready",320,176),video("old-probe"))
        val replacement=media("replacement",video("ready",640,360,path="new-file"))
        withSheet(headers,old,replacement) {model->
            waitForVisible("share_video_quality_320x176")
            tap("share_video_quality_320x176")
            compose.waitUntil(5_000) {headers.counts["old-probe"]==1}
            compose.runOnIdle {model.accept(replacement.url)}
            late.release()

            waitForVisible("share_video_quality_640x360")
            compose.waitUntil(10_000) {model.state.value.catalogReady}
            assertNull(model.state.value.choice)
            compose.onNodeWithTag("share_video_quality_320x176").assertDoesNotExist()
            compose.onNodeWithTag("share_video_quality_1280x720").assertDoesNotExist()
            tap("share_more")
            show("share_no_more_sources").assertIsDisplayed()
            tap("share_video_quality_640x360")
            val selected=requireNotNull(model.state.value.selection)
            assertEquals("https://example.com/new-file.mp4",JSONObject(selected.primary.payload).getString("url"))
            assertEquals(mapOf("old-probe" to 1),headers.counts.toMap())
        }
    }

    @Test fun portraitRowsAndFixedControlsKeepTheirSelectionDuringScrollAndLateDiscovery() {
        val late=HeaderReply(1080,1920)
        val headers=HeaderFixture(mapOf("late" to listOf(late)))
        val original=video("representation-very-long-dash-720-x1280-native",720,1280).copy(sourceQualityLabel="360p")
        val alternate=video("representation-very-long-dash-720-x1280-alternate",720,1280).copy(sourceQualityLabel="720p")
        val qualities=listOf(144,180,240,270,320,360,400,480,540,600,640,760,800,900)
            .map {edge->video("clip-$edge",edge,edge*16/9)}
        fun voice(id:String,rate:Int,language:String?=null)=Format.parse(JSONObject()
            .put("format_id",id).put("url","https://example.com/$id.mp3").put("ext","mp3")
            .put("vcodec","none").put("acodec","mp3").put("abr",rate).put("language",language))
        val audio=voice("voice",192)
        val otherLanguages=listOf("ar","de","es","fa","fr","hi","it","ja","ko","pt","tr","zh")
            .map {voice("voice-$it",96,it)}
        val media=media("portrait",*(qualities+original+alternate+audio+otherLanguages+video("late")).toTypedArray())

        withSheet(headers,media) {model->
            val videoMode=compose.onNodeWithTag("share_video_mode")
            val audioMode=compose.onNodeWithTag("share_audio_mode")
            compose.waitUntil(10_000) {compose.onNodeWithTag("share_audio_mp3").isDisplayed()}
            val fixedControls=listOf("share_header","share_media_mode","share_download")
            fun fixedBounds()=fixedControls.associateWith {tag->
                val control=compose.onNodeWithTag(tag)
                assertTrue("$tag must remain displayed in details",control.isDisplayed())
                control.getUnclippedBoundsInRoot()
            }
            val formats=compose.onNodeWithTag("share_formats")
            val videoRowTags=Regex("""share_video_quality_\d+x\d+""")
            val audioRowTags=Regex("share_audio_source_voice(?:-[a-z]{2})?")
            val rowTag="share_video_quality_720x1280"
            show(rowTag).assertIsDisplayed()
            compose.onAllNodesWithTag(rowTag).assertCountEquals(1)
             compose.onNodeWithTag("${rowTag}_title",useUnmergedTree=true).assertTextContains("720p",substring=true)
            compose.onNodeWithTag("share_media_mode").assertDoesNotExist()
            tap("share_more")
            videoMode.assertIsDisplayed()
            audioMode.assertIsDisplayed()
            val tag=compose.revealQuickShareVideoSource(requireNotNull(model.state.value.media),original)
            show(tag).performClick().assertIsSelected()
            tap("share_video_output_mkv")
            assertEquals(QuickShareChoice(false,original.id,"mkv"),model.state.value.choice)
            assertEquals(setOf("late"),model.state.value.pendingDimensionIds)
            val bounds=fixedBounds()
            fun assertFixed()=assertEquals("Header, media mode and CTA must stay put",bounds,fixedBounds())
            val trace=model.state.value.traceId
            late.release()
            compose.awaitQuickShareCatalog(trace)
            assertEquals(QuickShareChoice(false,original.id,"mkv"),model.state.value.choice)
            assertFixed()
            captureControls("portrait-options")

            formats.performScrollToIndex(0)
            val videoTop=viewport(videoRowTags)
            formats.performTouchInput {swipeDown(durationMillis=280)}
            assertEquals("A swipe at the top of the list must not move the sheet",videoTop,viewport(videoRowTags))
            assertFixed()
            repeat(2) {
                formats.performTouchInput {swipeUp(durationMillis=280)}
                assertFixed()
            }
            captureControls("portrait-scrolled")
            val scrolled=viewport(videoRowTags)
            assertTrue("Video list must actually scroll",scrolled.scrollAxis>videoTop.scrollAxis)
            assertFixed()

            audioMode.performClick().assertIsSelected()
            val audioTop=viewport(audioRowTags)
            assertEquals("Audio must not inherit video scroll",0f,audioTop.scrollAxis,0f)
            tap("share_audio_mp3")
            assertEquals(QuickShareChoice(true,audio.id,"mp3"),model.state.value.choice)
            repeat(2) {formats.performTouchInput {swipeUp(durationMillis=280)}}
            val audioScrolled=viewport(audioRowTags)
            assertTrue("Audio list must actually scroll",audioScrolled.scrollAxis>audioTop.scrollAxis)
            assertFixed()

            videoMode.performClick().assertIsSelected()
            assertEquals(QuickShareChoice(false,original.id,"mkv"),model.state.value.choice)
            captureControls("portrait-restored")
            val restored=viewport(videoRowTags)
            assertFixed()
            show("share_video_quality_1080x1920").assertIsNotSelected()
            show(tag).assertIsSelected()
            show("share_video_output_mkv").assertIsSelected()

            audioMode.performClick().assertIsSelected()
            val audioRestored=viewport(audioRowTags)
            controlEvidenceDirectory()?.let {directory->
                File(directory,"portrait-scroll.json").writeText(JSONObject()
                    .put("scrolled",scrolled.toJson()).put("restored",restored.toJson())
                    .put("audioScrolled",audioScrolled.toJson()).put("audioRestored",audioRestored.toJson()).toString(2))
            }
            assertTrue("Both saved positions must contain visible rows",scrolled.rows.isNotEmpty()&&audioScrolled.rows.isNotEmpty())
            assertEquals("Video rows and offset moved after switching sections",scrolled,restored)
            assertEquals("Audio rows and offset moved after switching sections",audioScrolled,audioRestored)
            show("share_audio_mp3").assertIsSelected()
            assertEquals(QuickShareChoice(true,audio.id,"mp3"),model.state.value.choice)
            assertFixed()
            assertEquals(trace,model.state.value.traceId)
            assertEquals(mapOf("late" to 1),headers.counts.toMap())
            assertNull(model.state.value.queuedId)
        }
    }

    @Test fun arabicOverviewShowsMp3BeforeVerifiedTiersAndKeepsBothChoicesInDetails()=runBlocking {
        val settings=SettingsRepository(app)
        val oldLanguage=settings.current().language
        val small=video("small",640,360)
        val medium=video("portrait",480,854)
        val sameTierLandscape=video("landscape",854,480)
        val large=video("large",1280,720)
        val audio=Format.parse(JSONObject().put("format_id","mp3-source").put("ext","mp3")
            .put("url","https://example.com/track.mp3").put("vcodec","none").put("acodec","mp3")
            .put("filesize",123_456).put("abr",192))
        val input=media("reference",large,medium,sameTierLandscape,audio,small)
        try {
            settings.text("language","ar")
            withSheet(HeaderFixture(emptyMap()),input) {model->
                compose.awaitQuickShareCatalog(model.state.value.traceId)
                val rows=listOf("share_audio_mp3","share_video_quality_640x360",
                    "share_video_quality_480x854","share_video_quality_1280x720")
                compose.onNodeWithTag("share_overview_preview").assertIsDisplayed()
                compose.onNodeWithTag("share_media_mode").assertDoesNotExist()
                val rowBounds=rows.map {compose.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot()}
                rowBounds.zipWithNext().forEach {(above,below)->assertTrue(above.bottom<=below.top)}
                val least=rows[1]
                fun rowLabel(suffix:String)=compose.onNodeWithTag("${least}_$suffix",useUnmergedTree=true)
                assertTrue("RTL places size to the left of the quality",
                    rowLabel("size").getUnclippedBoundsInRoot().right<=rowLabel("title").getUnclippedBoundsInRoot().left)
                listOf("360p" to "ملف أصغر","480p" to "جودة جيدة","720p" to "جودة عالية").forEachIndexed {index,(title,description)->
                    val tag=rows[index+1]
                    compose.onNodeWithTag("${tag}_title",useUnmergedTree=true).assertTextEquals("\u2066$title\u2069")
                    compose.onNodeWithTag("${tag}_description",useUnmergedTree=true).assertTextContains(description,substring=true)
                }
                compose.onNodeWithTag("share_video_quality_854x480").assertDoesNotExist()
                compose.onNodeWithTag("share_more").assertTextContains("مزيد من الصيغ",substring=true)
                val cta=compose.onNodeWithTag("share_download")
                val ctaBounds=cta.assertIsDisplayed().getUnclippedBoundsInRoot()

                tap("share_audio_mp3")
                compose.onNodeWithTag("share_overview_selected").assertTextContains("MP3",substring=true)
                tap(least)
                val summary=compose.onNodeWithTag("share_overview_selected").assertTextContains("360p",substring=true)
                val compactGap=summary.getUnclippedBoundsInRoot().top-
                    compose.onNodeWithTag("share_schedule").assertIsDisplayed().getUnclippedBoundsInRoot().bottom
                assertTrue("The Arabic overview has a blank footer gap: $compactGap",compactGap in 0.dp..20.dp)
                captureControls("reference-overview-ar")
                tap("share_more")
                compose.onNodeWithTag("share_video_mode").assertIsSelected()
                show(least).assertIsSelected()
                show(rows[2]).assertIsDisplayed()
                compose.onNodeWithTag("${rows[2]}_description",useUnmergedTree=true).assertTextContains("480×854",substring=true)
                show("share_video_quality_854x480").assertIsDisplayed()
                compose.onNodeWithTag("share_video_quality_854x480_description",useUnmergedTree=true).assertTextContains("854×480",substring=true)
                val detailsGap=cta.assertIsDisplayed().getUnclippedBoundsInRoot().top-
                    compose.onNodeWithTag("share_schedule").assertIsDisplayed().getUnclippedBoundsInRoot().bottom
                assertTrue("Short details must not leave space above Download: $detailsGap",detailsGap in 0.dp..24.dp)
                captureControls("reference-details-ar")
                controlEvidenceDirectory()?.let {directory->
                    File(directory,"reference-footer-layout.json").writeText(
                        JSONObject().put("overviewGapDp",compactGap.value).put("detailsGapDp",detailsGap.value).toString(2)
                    )
                }
                compose.onNodeWithTag("share_audio_mode").performClick().assertIsSelected()
                show("share_audio_mp3").assertIsSelected()
                assertEquals(QuickShareChoice(true,audio.id,"mp3"),model.state.value.choice)
                compose.onNodeWithTag("share_video_mode").performClick().assertIsSelected()
                assertEquals(small.id,model.state.value.choice?.formatId)
                compose.onNodeWithTag("share_back").performClick()
                compose.onNodeWithTag("share_compact").assertIsDisplayed()
                show(least).assertIsSelected()
                assertEquals(ctaBounds,cta.assertIsDisplayed().getUnclippedBoundsInRoot())
                tap("share_more")
                tap("share_video_quality_854x480")
                compose.onNodeWithTag("share_back").performClick()
                show("share_video_quality_854x480").assertIsSelected()
                compose.onNodeWithTag(rows[2]).assertDoesNotExist()
                assertEquals(sameTierLandscape.id,model.state.value.choice?.formatId)
                tap("share_more")
                compose.onNodeWithTag("share_drag_handle",useUnmergedTree=true).performTouchInput {
                    swipe(center,center+Offset(0f,320.dp.toPx()),durationMillis=280)
                }
                compose.onNodeWithTag("share_compact").assertIsDisplayed()
                assertNull(model.state.value.queuedId)
            }
        } finally {
            settings.text("language",oldLanguage)
        }
    }

    @Test fun shortOverviewKeepsActionsAndFooterTogether()=runBlocking {
        val settings=SettingsRepository(app)
        val oldLanguage=settings.current().language
        try {
            settings.text("language","en")
            withSheet(HeaderFixture(emptyMap()),media("short-clip",video("small",640,360))) {model->
                compose.awaitQuickShareCatalog(model.state.value.traceId)
                val actions=listOf("share_more","share_preferences","share_schedule").map {tag->
                    compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                }
                assertTrue("Overview actions must share both edges",actions.all {
                    it.left==actions[0].left&&it.right==actions[0].right
                })
                val summary=compose.onNodeWithTag("share_overview_selected").assertIsDisplayed().getUnclippedBoundsInRoot()
                val footerGap=summary.top-actions.last().bottom
                captureControls("short-overview-en")
                assertTrue("Unexpected blank space above selection: $footerGap",footerGap in 0.dp..20.dp)
                val cta=compose.onNodeWithTag("share_download").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
                assertTrue("The CTA must fit below the selection",cta.top>=summary.bottom)
                assertEquals(summary.left,cta.left)
                assertEquals(summary.right,cta.right)
                controlEvidenceDirectory()?.let {directory->
                    File(directory,"short-overview-layout.json").writeText(JSONObject()
                        .put("actionToSummaryGapDp",footerGap.value)
                        .put("actionsLeftDp",actions[0].left.value).put("actionsRightDp",actions[0].right.value)
                        .put("summaryTopDp",summary.top.value).put("ctaTopDp",cta.top.value).toString(2))
                }
            }
        } finally {
            settings.text("language",oldLanguage)
        }
    }

    @Test fun landscapeDownloadButtonQueuesTheDisplayed176SourceAndPublishesVideo()=runBlocking {
        check(app.packageName=="com.zdownloader.app.candidate0965")
        val url="https://www.w3schools.com/html/mov_bbb.mp4?quick-share-176=${UUID.randomUUID()}"
        val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        try {
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                lateinit var model:QuickShareViewModel
                scenario.onActivity {
                    it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    model=ViewModelProvider(it)[QuickShareViewModel::class.java]
                }
                compose.waitUntil(10_000) {
                    var landscape=false
                    scenario.onActivity {landscape=it.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
                    landscape
                }
                compose.waitUntil(45_000) {model.state.value.catalogReady||model.state.value.error!=null}
                assertNull(model.state.value.error)
                compose.awaitQuickShareCatalog(model.state.value.traceId)
                 val tag="share_video_quality_320x176"
                show(tag).assertIsDisplayed().assertIsEnabled().performClick().assertIsSelected()
                 compose.onNodeWithTag("${tag}_title",useUnmergedTree=true).assertTextContains("176p",substring=true)
                val sourceId=requireNotNull(model.state.value.selection).primary.id
                compose.onNodeWithTag("share_compact").assertIsDisplayed()
                compose.onNodeWithTag("share_media_mode").assertDoesNotExist()
                val cta=compose.onNodeWithTag("share_download").assertIsDisplayed().assertIsEnabled()
                val ctaBounds=cta.getUnclippedBoundsInRoot()
                captureControls("landscape-compact")
                show("share_more").performScrollTo().assertIsDisplayed().assertIsEnabled()
                assertEquals("Download must stay fixed while revealing actions",ctaBounds,cta.getUnclippedBoundsInRoot())
                captureControls("landscape-actions")
                cta.performClick()

                val row=withTimeout(90_000) {app.repository.dao.observe().first {rows->
                    rows.any {it.url==url&&it.status in setOf("COMPLETED","FAILED")}
                }}.filter {it.url==url}.single()
                assertEquals(row.error,"COMPLETED",row.status)
                assertEquals(sourceId,Selection.parse(row.selection).primary.id)
                assertTrue((row.outputSize ?: 0L)>0L)
                val uri=Uri.parse(requireNotNull(row.uri))
                assertEquals("content",uri.scheme)
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app,uri)
                    assertEquals("320",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals("176",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                } finally {retriever.release()}
            }
        } finally {removeFixtureRows(url)}
    }

    private fun show(tag:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun tap(tag:String) {show(tag).assertIsEnabled().performClick()}

    private data class VisibleRow(val tag:String,val offsetPx:Float,val heightPx:Int)

    private data class Viewport(val scrollAxis:Float,val maxAxis:Float,val heightPx:Int,val rows:List<VisibleRow>) {
        fun toJson()=JSONObject().put("scrollAxis",scrollAxis).put("maxAxis",maxAxis).put("heightPx",heightPx)
            .put("visibleRows",JSONArray(rows.map {row->JSONObject()
                .put("tag",row.tag).put("offsetPx",row.offsetPx).put("heightPx",row.heightPx)}))
    }

    private fun viewport(rowTags:Regex):Viewport {
        val list=compose.onNodeWithTag("share_formats",useUnmergedTree=true).fetchSemanticsNode()
        val range=list.config[SemanticsProperties.VerticalScrollAxisRange]
        val height=list.size.height
        fun descendants(node:SemanticsNode):Sequence<SemanticsNode> =
            node.children.asSequence().flatMap { sequenceOf(it)+descendants(it) }
        val rows=descendants(list).mapNotNull {row->
            val tag=row.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
            if(!rowTags.matches(tag)) return@mapNotNull null
            val offset=row.positionInRoot.y-list.positionInRoot.y
            // Prefetched/clipped neighbors can be composed in only one of the two trees.
            if(offset<0f||offset+row.size.height>height) return@mapNotNull null
            VisibleRow(tag,offset,row.size.height)
        }.sortedBy {it.offsetPx}.toList()
        return Viewport(range.value(),range.maxValue(),height,rows)
    }

    private fun controlEvidenceDirectory():File? {
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: return null
        require(run.matches(Regex("[a-z0-9-]{1,64}")))
         val directory=File(app.getExternalFilesDir(null),"qa128-controls/$run")
        check(directory.isDirectory||directory.mkdirs())
        return directory
    }

    private fun captureControls(name:String) {
        val directory=controlEvidenceDirectory() ?: return
        // Compose nodes can be ready before the physical window finishes opening or releasing a ripple.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val automation=instrument.uiAutomation
        automation.waitForIdle(350,5_000)
        val bitmap=requireNotNull(automation.takeScreenshot())
        try {File(directory,"$name.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
    }

    private fun waitForVisible(tag:String) {
        compose.waitUntil(15_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        show(tag).assertIsDisplayed()
    }

    private fun withSheet(headers:HeaderFixture,initial:Media,vararg additional:Media,body:(QuickShareViewModel)->Unit) {
        check(app.packageName=="com.zdownloader.app.candidate0965")
        val extractor=Extractor(app,DirectMediaResolver(headers.client),connectionIssue={null})
        // Prime only the base cache of this private extractor. Header checks,
        // subscriber cancellation, and UI state all use the actual pipeline.
        val metadata=Extractor::class.java.getDeclaredField("metadata").apply {isAccessible=true}.get(extractor) as ExtractorMetadata
        val network=app.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle
        runBlocking {
            for(media in listOf(initial)+additional) {
                metadata.analyze(MetadataRequest(media.url,network,MetadataRoute.AUTOMATIC)) {media}
            }
        }
        val store=ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model:QuickShareViewModel
                scenario.onActivity {
                    model=QuickShareViewModel(extractor,app.repository,SettingsRepository(app),app.scheduler,
                        SavedStateHandle(),app.playlists,app.plannedDownloads)
                    store.put("progressive-fixture",model)
                    model.accept(initial.url)
                    it.setContent {QuickShareSheet(model,{})}
                }
                body(model)
            }
        } finally {
            headers.releaseAll()
            instrument.runOnMainSync {store.clear()}
        }
    }

    private suspend fun removeFixtureRows(url:String) {
        for(row in app.repository.dao.observe().first().filter {it.url==url}) {
            if(row.status=="COMPLETED") app.storage.deleteFile(row)
            else {
                app.plannedDownloads.cancel(row.id)
                app.repository.cancel(row.id)
                app.coordinator.stopAndAwait(row.id)
                app.repository.remove(row.id)
            }
        }
    }

    private fun media(name:String,vararg sources:Format):Media {
        val id="$name-${UUID.randomUUID()}"
        return Media("https://example.com/$id.mp4",id,"Fixture $name","Fixture",null,null,10.0,sources.toList())
    }

    private fun video(id:String,width:Int?=null,height:Int?=null,path:String=id)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$path.mp4").put("ext","mp4")
        .put("width",width).put("height",height).put("vcodec","avc1").put("acodec","none").put("filesize",1000))

    private class HeaderFixture(private val replies:Map<String,List<HeaderReply>>) {
        val counts=ConcurrentHashMap<String,Int>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request()
            val id=request.url.encodedPath.removePrefix("/").removeSuffix(".mp4")
            val index=requireNotNull(counts.merge(id,1,Int::plus))-1
            val reply=replies[id]?.getOrNull(index) ?: throw IOException("Unexpected fixture request: $id")
            val bytes=reply.bytes()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if(bytes.isEmpty()) 503 else 206).message("Fixture")
                .header("Content-Range","bytes 0-${bytes.size-1}/1000").body(bytes.toResponseBody()).build()
        }.build()
        fun releaseAll() {replies.values.flatten().forEach {it.release()}}
    }

    private class HeaderReply(private val width:Int?=null,private val height:Int?=null,blocked:Boolean=true) {
        private val released=CountDownLatch(if(blocked) 1 else 0)
        fun release()=released.countDown()
        fun bytes():ByteArray {
            if(!released.await(12,TimeUnit.SECONDS)) throw IOException("Fixture header gate timed out")
            if(width==null||height==null) return byteArrayOf()
            fun box(name:String,data:ByteArray)=ByteBuffer.allocate(8+data.size)
                .putInt(8+data.size).put(name.toByteArray(Charsets.US_ASCII)).put(data).array()
            val movie=ByteBuffer.allocate(24).apply {putInt(12,1000);putInt(16,10_000)}.array()
            val handler=ByteArray(12).apply {"vide".toByteArray().copyInto(this,8)}
            val sample=ByteBuffer.allocate(44).apply {
                putInt(4,1);putInt(8,36);position(12);put("avc1".toByteArray())
                putShort(40,width.toShort());putShort(42,height.toShort())
            }.array()
            return box("ftyp","isom".toByteArray())+box("moov",box("mvhd",movie)+
                box("trak",box("mdia",box("hdlr",handler)+box("minf",box("stbl",box("stsd",sample))))))
        }
    }
}
