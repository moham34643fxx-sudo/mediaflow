package com.zdownloader.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import android.view.WindowManager

/** Live metadata/UI checks. No user download is submitted or cancelled. */
class SizePreviewUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val source="https://www.youtube.com/watch?v=tldDnDX5UKM"
    private val second="https://www.youtube.com/watch?v=jNQXAC9IVRw"
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private suspend fun localizedContext():android.content.Context {
        val locale=Locale.forLanguageTag(SettingsRepository(app).current().language)
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {setLocale(locale)})
    }
    private suspend fun label(size:MediaSize?):String {
        val context=localizedContext()
        return size?.let {"\u2066${if(it.estimated) "≈ " else ""}${Formatter.formatShortFileSize(context,it.bytes)}\u2069"} ?: context.getString(R.string.size_unknown)
    }
    private fun shot(name:String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(folder,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun share():ActivityScenario<ShareReceiverActivity> {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        for(command in listOf("input keyevent 224","wm dismiss-keyguard")) android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()}
        return ActivityScenario.launch<ShareReceiverActivity>(Intent(app,ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,source)).also {scenario->
                scenario.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            }
    }
    private fun node(list:String,tag:String,parent:String=tag):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(parent))
        return compose.onNodeWithTag(tag,useUnmergedTree=true)
    }

    @Test fun compactAndExpandedChoicesShowTheirOwnRealOutputSizes():Unit=runBlocking {
        val before=app.repository.dao.observe().first().map {it.id}.toSet()
        share().use {scenario->
            var model:QuickShareViewModel?=null
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(180000) {model!!.state.value.media!=null&&!model!!.state.value.analyzing}
            val media=model!!.state.value.media!!
            compose.onAllNodesWithText(localizedContext().getString(R.string.analyzing)).assertCountEquals(0)
            compose.onAllNodesWithTag("share_audio_original").assertCountEquals(0)
            val prefs=SettingsRepository(app).current()
            val groups=QuickShareFormats.compactGroups(media,prefs.preferredHeight,null)
            assertTrue("A real multi-quality source is required",media.qualityGroups().size>=2)
            val evidence=JSONArray()
            for(group in groups) {
                val size=Selection.video(media,group.recommended).outputSize
                val tag="share_video_${group.resolution ?: "source"}"
                node("share_formats","${tag}_size",tag).assertTextEquals(label(size))
                evidence.put(JSONObject().put("quality",group.resolution).put("output_bytes",size?.bytes).put("estimated",size?.estimated))
            }
            val mp3=Selection.audio(media,"mp3").outputSize!!
            assertEquals((media.duration!!*192000/8).toLong(),mp3.bytes)
            node("share_formats","share_audio_mp3_size","share_audio_mp3").assertTextEquals(label(mp3))
            shot("sizes-compact.png")
            node("share_formats","share_more_audio").performClick()
            compose.waitUntil(10000) {model!!.state.value.expanded&&model!!.state.value.formatSection=="audio"}
            val sourceAudio=Selection.audio(media,"original").primary
            for(choice in QuickShareFormats.audioChoices(media,sourceAudio,true)) {
                node("share_formats","share_audio_${choice.output}_size","share_audio_${choice.output}").assertTextEquals(label(choice.resolve(media)!!.outputSize))
            }
            compose.onAllNodesWithText(localizedContext().getString(R.string.quick_original_audio)).assertCountEquals(0)
            shot("sizes-audio-more.png")
            compose.onNodeWithTag("share_back").performClick()
            compose.waitUntil(10000) {!model!!.state.value.expanded}
            node("share_formats","share_more").performClick()
            compose.waitUntil(10000) {model!!.state.value.expanded&&model!!.state.value.formatSection=="video"}
            for(group in media.qualityGroups()) {
                val tag="share_video_${group.resolution ?: "source"}"
                node("share_formats","${tag}_size",tag).assertTextEquals(label(Selection.video(media,group.recommended).outputSize))
            }
            shot("sizes-expanded.png")
            File(folder,"single-size-preview.json").writeText(JSONObject().put("complete",true).put("compact",evidence)
                .put("silent_compact",true).put("audio_more_scoped",true).put("video_more_scoped",true)
                .put("expanded_qualities",media.qualityGroups().size).put("mp3_output_bytes",mp3.bytes)
                .put("scope","Live public metadata and rendered sizes; media bodies were not downloaded for this test").toString(2))
        }
        assertTrue("Existing jobs must survive preview",app.repository.dao.observe().first().map {it.id}.toSet().containsAll(before))
    }

    @Test fun playlistPolicySizesArePerItemAndPersistIntoPausedDetails():Unit=runBlocking {
        val before=app.repository.dao.observe().first().map {it.id}.toSet()
        val policy=PlaylistPolicy(height=720,fallback=QualityFallback.AT_MOST)
        share().use {scenario->
            var model:QuickShareViewModel?=null
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(180000) {model!!.state.value.media!=null&&!model!!.state.value.analyzing}
            scenario.onActivity {model!!.playlists.fromLinks(listOf(source,second),policy)}
            try {compose.waitUntil(120000) {val state=model!!.playlists.state.value
                state.sizePolicy==policy&&!state.sizing&&!state.checkingDuplicates&&state.sizes.size==2}}
            catch(error:Throwable) {
                val state=model!!.playlists.state.value
                File(folder,"playlist-size-failure.json").writeText(JSONObject().put("visible",state.visible).put("sizing",state.sizing)
                    .put("policy",state.sizePolicy.json()).put("error",state.error?.name).put("sizes",JSONObject().also {out->state.sizes.forEach {(key,value)->out.put(key,value.json())}}).toString(2))
                shot("playlist-size-failure.png");throw error
            }
            val entries=model!!.playlists.state.value.page!!.items
            val initial=model!!.playlists.state.value.sizes
            File(folder,"playlist-initial-sizes.json").writeText(JSONObject().also {out->initial.forEach {(key,value)->out.put(key,value.json())}}.toString(2))
            initial.values.forEach {assertNotNull("Expected metadata for the selected ceiling policy: ${it.json()}",it.output);assertTrue(it.quality!!<=720)}
            for(entry in entries) node("playlist_list","playlist_item_size_${entry.position}").assertTextEquals(label(initial[entry.key]!!.output))
            val total=sumMediaSizes(initial.values.map {it.output})!!
            node("playlist_list","playlist_expected_size").assertTextContains(label(total),substring=true)
            shot("sizes-playlist-720.png")
            node("playlist_list","playlist_quality_480").performClick()
            node("playlist_list","playlist_policy_EXACT").performClick()
            compose.waitUntil(20000) {val state=model!!.playlists.state.value
                state.sizePolicy.height==480&&!state.sizing&&state.sizes[entries.last().key]?.error==ErrorCode.FORMAT_UNAVAILABLE}
            val partial=SizeCoverage.of(model!!.playlists.state.value.sizes.values.map {it.output})
            assertNull(partial.total);assertEquals(1,partial.measured)
            node("playlist_list","playlist_expected_size").assertTextContains(label(partial.known),substring=true)
            node("playlist_list","playlist_item_size_2").assertTextEquals(localizedContext().getString(R.string.media_size_quality_unavailable))
            shot("sizes-playlist-incomplete.png")
            node("playlist_list","playlist_audio").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("playlist_mp3").fetchSemanticsNodes().isNotEmpty()}
            node("playlist_list","playlist_mp3").performClick()
            compose.waitUntil(20000) {val state=model!!.playlists.state.value
                state.sizePolicy.audio&&state.sizePolicy.output=="mp3"&&!state.sizing&&!state.checkingDuplicates&&state.sizes.values.all {it.output!=null}}
            val mp3Sizes=model!!.playlists.state.value.sizes
            for(entry in entries) node("playlist_list","playlist_item_size_${entry.position}").assertTextEquals(label(mp3Sizes[entry.key]!!.output))
            val current=model!!.playlists.state.value
            val included=entries.filter {it.key !in current.duplicates.matchingCompleted&&it.key !in current.duplicates.matchingActive}.distinctBy(MediaIdentity::playlistKey)
            val mp3Total=sumMediaSizes(included.map {mp3Sizes[it.key]!!.output})!!
            node("playlist_list","playlist_expected_size").assertTextContains(label(mp3Total),substring=true)
            shot("sizes-playlist-mp3.png")
            File(folder,"playlist-size-preview.json").writeText(JSONObject().put("complete",true).put("video_720_total",total.bytes)
                .put("video_policy",policy.json()).put("actual_video_qualities",JSONArray(initial.values.map {it.quality}))
                .put("mp3_total",mp3Total.bytes).put("mp3_items_after_duplicate_filter",included.size).put("unknown_quality_not_counted_as_zero",true).put("downloads_submitted",0).toString(2))
        }

        val reopened=SizePreviewRepository(app,app.extractor)
        withTimeout(10000) {reopened.state.first {it.containsKey(MediaIdentity.urlKey(source))&&it.containsKey(MediaIdentity.urlKey(second))}}
        for(url in listOf(source,second)) assertEquals(app.extractor.sizePreviews.forecast(url,policy,null),reopened.forecast(url,policy,null))

        // Paused fixture rows only, isolated from the scheduler and existing history.
        val id=UUID.randomUUID().toString()
        val rows=listOf(source,second).mapIndexed {index,url->DownloadEntity(UUID.randomUUID().toString(),url,"Size preview ${index+1}","Fixture",null,null,"","",null,UUID.randomUUID().toString(),status="PAUSED",batchId=id,playlistPosition=index+1)}
        val batch=PlaylistBatch(id,id,source,"Size preview fixture",policy.json(),state="PAUSED")
        try {
            app.repository.dao.submitBatch(batch,rows)
            val summary=app.repository.dao.batchSummaries().first {list->list.any {it.batch.id==id}}.single {it.batch.id==id}
            val context=localizedContext()
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {activity->val model=ViewModelProvider(activity)[ZViewModel::class.java]
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {CompositionLocalProvider(LocalSpectrumContext provides context) {FlowTheme(Spectrum.Dark) {PlaylistDetailScreen(id,summary,model,{}, {})}}}}
                val expected=rows.map {app.extractor.sizePreviews.forecast(it.url,policy,null).output}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("playlist_detail_item_size_1",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                rows.forEachIndexed {index,row->node("playlist_detail_list","playlist_detail_item_size_${row.playlistPosition}","playlist_item_${row.id}").assertTextEquals(label(expected[index]))}
                node("playlist_detail_list","playlist_detail_expected_size").assertTextContains(label(sumMediaSizes(expected)),substring=true)
                shot("sizes-playlist-details.png")
            }
        } finally {for(row in rows) app.repository.dao.removeHistory(row.id);app.repository.dao.removeEmptyBatch(id)}
        assertEquals(before,app.repository.dao.observe().first().map {it.id}.toSet())
    }
}
