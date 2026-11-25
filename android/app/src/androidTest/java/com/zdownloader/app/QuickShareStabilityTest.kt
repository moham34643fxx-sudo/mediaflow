package com.zdownloader.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.*
import java.util.UUID

/** Real share sheet with already-verified in-memory metadata; no live downloads or screen commands. */
class QuickShareStabilityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private fun video(id:String,w:Int?,h:Int?)=Format.parse(org.json.JSONObject().put("format_id",id).put("ext","mp4").put("width",w).put("height",h)
        .put("fps",30.0).put("vcodec","h264").put("acodec","aac").put("filesize",1000).put("tbr",1000.0).put("url","https://example.com/$id.mp4"))
    private fun audio(id:String,codec:String,ext:String,rate:Int,language:String)=Format.parse(org.json.JSONObject()
        .put("format_id",id).put("ext",ext).put("url","https://example.com/$id.$ext")
        .put("vcodec","none").put("acodec",codec).put("abr",rate).put("language",language))
    private fun source()=Media("https://example.com/stability","fixture","Fixture","Fixture",null,null,10.0,listOf(video("1080",1080,1920),video("720",720,1280),video("sd",null,null),video("hd",null,null)))
    private fun deliver(model:QuickShareViewModel,media:Media,loading:Boolean,downloadType:String="ask") {
        QuickShareViewModel::class.java.getDeclaredMethod("acceptMetadata",Media::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,String::class.java).apply {isAccessible=true}.invoke(model,media,720,loading,downloadType)
    }
    private fun click(tag:String) {
         if(tag !in setOf("share_back","share_audio_mode","share_video_mode"))
             compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
        val node=compose.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.OnClick) {it()}
    }
    private fun withPreview(media:Media,loading:Boolean,downloadType:String="ask",body:(QuickShareViewModel)->Unit) {
        check(app.packageName=="com.zdownloader.app.candidate0965")
        val store=ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model:QuickShareViewModel
                scenario.onActivity {activity->
                    model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,
                        SavedStateHandle(),app.playlists,app.plannedDownloads)
                    store.put("preview",model)
                    model.accept(media.url)
                    deliver(model,media,loading,downloadType)
                    activity.setContent {QuickShareSheet(model,{})}
                }
                compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
                body(model)
            }
        } finally {instrument.runOnMainSync {store.clear()}}
    }
    @Test fun cap480CanPersistItsRequestWhileMetadataIsStillLoading():Unit=runBlocking {
        assertTrue("Quick Share state tests must use an isolated package",app.packageName!="com.zdownloader.app")
        val store=ViewModelStore();lateinit var model:QuickShareViewModel
        val settings=SettingsRepository(app)
        val originalDestination=settings.current().destination
        val discovered=source().copy(formats=listOf(video("360",640,360),video("540",960,540),video("720",1280,720),video("1080",1920,1080)))
        var owned:String?=null
        try {
            if(android.os.Build.VERSION.SDK_INT<29) {
                // Production correctly requires a destination on old Android.
                // Use only the isolated provider, with a real URI grant.
                val tree=android.provider.DocumentsContract.buildTreeDocumentUri(app.packageName+".test.documents","root")
                app.startActivity(android.content.Intent().setComponent(android.content.ComponentName(app.packageName+".test","com.zdownloader.app.TestDocumentsGrantActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                withTimeout(10_000) {while(app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)}
                settings.text("destination",tree.toString())
            }
            instrument.runOnMainSync {
                model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
                store.put("pending-cap",model);model.accept(discovered.url)
                deliver(model,discovered,true);model.prefer(480)
                assertTrue("A selected quality cap can be submitted while metadata is still loading",model.state.value.canSubmit)
                model.schedule(DownloadPlan(System.currentTimeMillis()+3_600_000,DownloadNetwork.ANY,false))
            }
            withTimeout(10_000) {while(model.state.value.persistedId==null) {assertNull(model.state.value.error);delay(10)}}
            owned=model.state.value.persistedId
            withTimeout(10_000) {while(model.state.value.queuedId==null) {assertNull(model.state.value.error);delay(10)}}
            val row=app.repository.dao.get(owned!!)!!
            assertEquals("SCHEDULED",row.status)
            assertTrue("A request remains unresolved until background discovery completes",row.selection.isBlank())
            val request=DownloadRequest.parse(requireNotNull(row.request))
            assertEquals(480,request.height)
            assertFalse("480p is a cap, not an exact-resolution demand",request.exact)
            assertEquals(360,verifiedQualityEdge(request.resolve(discovered).primary))
            assertEquals(480,model.state.value.qualityPreference)
        } finally {
            owned?.let {app.plannedDownloads.cancel(it);app.repository.cancel(it);app.coordinator.stopAndAwait(it);app.repository.remove(it)}
            instrument.runOnMainSync {store.clear()}
            if(android.os.Build.VERSION.SDK_INT<29) settings.text("destination",originalDestination.orEmpty())
        }
    }
    @Test fun unavailable480CannotSubmit720AndLaterMetadataCannotReplaceTheChoice() {
        val store=ViewModelStore()
        try {instrument.runOnMainSync {
            val model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
            store.put("exact",model);model.accept(source().url);deliver(model,source(),false);model.prefer(480)
            assertTrue(model.state.value.preferenceUnavailable);assertFalse(model.state.value.canSubmit)
            model.enqueue()
            assertNull(model.state.value.persistedId);assertNull(model.state.value.queuedId)
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE,model.state.value.error)
            val available=source().copy(formats=source().formats+video("480",854,480))
            deliver(model,available,false)
            assertEquals(480,model.state.value.qualityPreference);assertNull(model.state.value.choice)
            assertFalse(model.state.value.preferenceUnavailable);assertTrue(model.state.value.canSubmit)
            assertEquals("480",VideoPreferenceSelection.ready(model.state.value.media,480)?.primary?.id)
        }} finally {instrument.runOnMainSync {store.clear()}}
    }
    @Test fun compactPreferenceAndExpandedTierKeepTheUsersExactSourceChoice() {
        assertTrue("Quick Share UI tests must use an isolated package",app.packageName!="com.zdownloader.app")
        val store=ViewModelStore();lateinit var model:QuickShareViewModel
        instrument.runOnMainSync {
            model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
            store.put("fixture",model);model.accept(source().url);deliver(model,source().copy(formats=source().formats+video("360",640,360)),false);model.prefer(480)
        }
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
             scenario.onActivity {it.setContent {QuickShareSheet(model,{})}}
             click("share_preferences")
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_preference_480").fetchSemanticsNodes().size==1}
            compose.onNodeWithTag("share_preference_480").assertExists().assertIsSelected()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_cap_resolved"))
            compose.onNodeWithTag("share_cap_resolved").assertExists()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_preference_720"))
            compose.onNodeWithTag("share_preference_720").assertExists().assertIsEnabled().assertIsNotSelected()
            click("share_more")
            var currentMedia=requireNotNull(model.state.value.media)
             for(format in selectableQuickShareVideoSources(currentMedia)) {
                 val tag=compose.revealQuickShareVideoSource(currentMedia,format)
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertExists().assertIsEnabled()
            }
            val selectedFormat=currentMedia.formats.single {it.id=="1080"}
            val selectedTag=compose.revealQuickShareVideoSource(currentMedia,selectedFormat)
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(selectedTag))
            compose.onNodeWithTag(selectedTag).performClick().assertIsSelected()
            instrument.runOnMainSync {assertEquals("1080",model.state.value.choice?.formatId);assertNull(model.state.value.qualityPreference)}
            click("share_back")
              compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_video_quality_1080x1920"))
              compose.onNodeWithTag("share_video_quality_1080x1920").assertIsSelected()
            compose.onNodeWithTag("share_cap_resolved").assertDoesNotExist()
            instrument.runOnMainSync {
                deliver(model,source().copy(title="Enriched fixture"),false)
                assertEquals("1080",model.state.value.choice!!.formatId)
                assertNull(model.state.value.qualityPreference)
                assertEquals("1080",Selection.parse(model.state.value.selection!!.json()).primary.id)
            }
            click("share_more")
            currentMedia=requireNotNull(model.state.value.media)
            val refreshed1080=compose.revealQuickShareVideoSource(currentMedia,currentMedia.formats.single {it.id=="1080"})
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(refreshed1080))
            compose.onNodeWithTag(refreshed1080).assertIsSelected()
            for(id in listOf("sd","hd")) {
                val format=currentMedia.formats.single {it.id==id}
                val tag=compose.revealQuickShareVideoSource(currentMedia,format)
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertExists().assertIsEnabled()
            }
            instrument.runOnMainSync {assertEquals("1080",model.state.value.selection!!.primary.id)}
        }} finally {instrument.runOnMainSync {store.clear()}}
    }

    @Test fun videoModeHidesAudioOnlySourcesButKeepsCompatibleCompanionPairing() {
        assertTrue("Quick Share mode tests must use an isolated package",app.packageName!="com.zdownloader.app")
        val picture=Format.parse(org.json.JSONObject().put("format_id","720-silent").put("ext","mp4")
            .put("width",1280).put("height",720).put("fps",30.0).put("vcodec","avc1").put("acodec","none")
            .put("filesize",1000).put("tbr",1000.0).put("url","https://example.com/720-silent.mp4"))
        val english=audio("english-aac","mp4a.40.2","m4a",128,"en")
        val media=Media("https://example.com/video-audio-mode","mode-fixture","Mode fixture","Fixture",
            null,null,10.0,listOf(picture,english))
        val store=ViewModelStore();lateinit var model:QuickShareViewModel
        instrument.runOnMainSync {
            model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
            store.put("format-mode",model);model.accept(media.url);deliver(model,media,false);model.expand()
             assertNull("More must not select a format on the user's behalf",model.state.value.choice)
        }
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.setContent {QuickShareSheet(model,{})}}
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_video_mode").fetchSemanticsNodes().isNotEmpty()}
             compose.onNodeWithTag("share_video_mode").assertIsSelected()
              click("share_video_quality_1280x720")
              compose.onNodeWithTag("share_video_quality_1280x720").assertIsSelected()
             assertEquals(english.id,model.state.value.selection?.secondary?.id)
            compose.onNodeWithTag("share_audio_source_${english.id}").assertDoesNotExist()
            compose.onNodeWithTag("share_audio_original").assertDoesNotExist()

            click("share_audio_mode")
            compose.waitUntil(10_000) {model.state.value.formatSection=="audio"}
            compose.onNodeWithTag("share_audio_mode").assertIsSelected()
             compose.onNodeWithTag("share_video_quality_1280x720").assertDoesNotExist()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_audio_original"))
            compose.onNodeWithTag("share_audio_original").assertExists().assertIsSelected()
            compose.onNodeWithTag("share_audio_source_${english.id}").assertDoesNotExist()
            assertTrue(model.state.value.selection?.audioOnly==true)

            click("share_video_mode")
            compose.waitUntil(10_000) {model.state.value.formatSection=="video"}
             compose.onNodeWithTag("share_video_quality_1280x720").assertIsSelected()
            compose.onNodeWithTag("share_audio_source_${english.id}").assertDoesNotExist()
            assertEquals("720-silent",model.state.value.choice?.formatId)
            assertEquals(english.id,model.state.value.selection?.secondary?.id)
        }} finally {instrument.runOnMainSync {store.clear()}}
    }

    @Test fun theChosenModeAndSourceStayVisibleWhenTheOtherMediaTypeArrives() {
        val picture=Format.parse(JSONObject(video("picture",320,176).payload).put("acodec","none"))
        val track=audio("track","mp3","mp3",192,"en")
        for((first,late,defaultType) in listOf(Triple(picture,track,"audio"),Triple(track,picture,"ask"))) {
            val audioFirst=first==track
             val rowTag=if(audioFirst) "share_audio_original" else "share_video_quality_320x176"
            val modeTag=if(audioFirst) "share_audio_mode" else "share_video_mode"
            val initial=source().copy(formats=listOf(first))
            withPreview(initial,loading=true,downloadType=defaultType) {model->
                compose.waitUntil(10_000) {compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()}
                click(rowTag)
                compose.onNodeWithTag(rowTag).assertIsSelected()
                compose.runOnIdle {deliver(model,initial.copy(formats=listOf(first,late)),false,defaultType)}
                click("share_more")
                compose.waitUntil(10_000) {compose.onAllNodesWithTag(modeTag).fetchSemanticsNodes().isNotEmpty()}
                 compose.onNodeWithTag(modeTag).assertIsDisplayed().assertIsSelected()
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(rowTag))
                compose.onNodeWithTag(rowTag).assertIsDisplayed().assertIsSelected()
                assertEquals(first.id,model.state.value.selection?.primary?.id)
                assertEquals(audioFirst,model.state.value.selection?.audioOnly)
            }
        }
    }

    @Test fun aDisplayedSourceThatExpiresBeforeScheduleConfirmationIsNotPersisted()=runBlocking {
        val url="https://example.com/expiry-${UUID.randomUUID()}.mp4"
        val expiredAt=System.currentTimeMillis()/1_000+22
        val expiring=Format.parse(JSONObject(video("expiring",320,176).payload).put("url","$url?expire=$expiredAt"))
        val media=source().copy(url=url,formats=listOf(expiring))
        try {
            withPreview(media,loading=false) {model->
                 val rowTag="share_video_quality_320x176"
                compose.waitUntil(10_000) {compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()}
                click(rowTag)
                compose.onNodeWithTag(rowTag).assertIsSelected()
                click("share_schedule")
                compose.onNodeWithTag("schedule_confirm").assertIsDisplayed()
                assertTrue("The source must still be eligible when the dialog opens",FormatAvailability.eligible(expiring))
                val untilExpiry=expiredAt*1_000-System.currentTimeMillis()
                compose.waitUntil(untilExpiry+2_000) {System.currentTimeMillis()>=expiredAt*1_000}
                compose.onNodeWithTag("schedule_confirm").performClick()
                compose.waitUntil(10_000) {model.state.value.error!=null||model.state.value.persistedId!=null}
                assertEquals(ErrorCode.MEDIA_URL_EXPIRED,model.state.value.error)
                compose.onNodeWithTag("share_download").assertIsNotEnabled()
            }
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
        } finally {
            for(row in app.repository.dao.observe().first().filter {it.url==url}) {
                app.plannedDownloads.cancel(row.id)
                app.repository.cancel(row.id)
                app.coordinator.stopAndAwait(row.id)
                app.repository.remove(row.id)
            }
        }
    }

    @Test fun audioOutputOrderAndSourceChipsPreserveTheChosenLanguageAndNativeMp3() {
        assertTrue("Quick Share audio tests must use an isolated package",app.packageName!="com.zdownloader.app")
        val english=audio("english-opus","opus","webm",256,"en")
        val french=audio("french-mp3","mp3","mp3",192,"fr")
        val englishAac=audio("english-aac","aac","m4a",192,"en")
        val media=Media("https://example.com/audio-fixture","audio-fixture","Audio fixture","Fixture",
            null,null,30.0,listOf(english,englishAac,french))
        val store=ViewModelStore();lateinit var model:QuickShareViewModel
        instrument.runOnMainSync {
            model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
            store.put("audio-fixture",model);model.accept(media.url);deliver(model,media,false)
            model.choose(QuickShareChoice(true,english.id,"original"))
        }
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.setContent {QuickShareSheet(model,{})}}
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_more").performScrollTo().performClick()
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
             val englishAlternative="share_audio_source_${englishAac.id}"
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(englishAlternative))
            compose.onNodeWithTag(englishAlternative).assertExists().performClick()
            assertEquals(englishAac.id,model.state.value.choice?.formatId)
            assertEquals(englishAac.id,model.state.value.selection?.primary?.id)
            val frenchTag="share_audio_source_${french.id}"
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(frenchTag))
            compose.waitUntil(10_000) {compose.onAllNodesWithTag(frenchTag).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag(frenchTag).performScrollTo().performClick()
            assertEquals(french.id,model.state.value.choice?.formatId)
            assertEquals(french.id,model.state.value.selection?.primary?.id)
            assertTrue(model.state.value.choice?.audio==true)

            val outputs=QuickShareFormats.audioChoices(media,french,model.state.value.mp3Available).map {it.output}
            assertEquals("mp3",outputs.first())
            assertTrue("Audio fixture should expose multiple output rows: $outputs",outputs.size>1)
            val formats=compose.onNodeWithTag("share_formats")
            val rowIndex=formats.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            val indices=outputs.map {output->
                val tag="share_audio_$output"
                formats.performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed()
                rowIndex("audio:$output").also {assertTrue("Missing audio row $output",it>=0)}
            }
            assertTrue("Audio rows must remain ordered: $outputs, indices=$indices",
                indices.zipWithNext().all {(before,after)->before<after})
            compose.onNodeWithTag("share_audio_mp3").performScrollTo().performClick().assertIsSelected()
            assertEquals(french.id,model.state.value.choice?.formatId)
            assertEquals("mp3",model.state.value.choice?.output)
            assertEquals(french.id,model.state.value.selection?.primary?.id)
            assertFalse(model.state.value.selection!!.convertAudio)
            assertNull(model.state.value.queuedId);assertNull(model.state.value.persistedId)
        }} finally {instrument.runOnMainSync {store.clear()}}
    }

    @Test fun expandedQualityTierRemainsSelectableWithSystemReducedMotion() {
        assertTrue("Quick Share motion tests must use an isolated package",app.packageName!="com.zdownloader.app")
        val automation=instrument.uiAutomation
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use {it.readText().trim()}
        val original=shell("settings get global animator_duration_scale")
        val media=Media("https://example.com/reduced-motion","reduced-motion","Motion fixture","Fixture",null,null,10.0,
            listOf(video("720-a",1280,720),video("720-b",1280,720)))
        val store=ViewModelStore();lateinit var model:QuickShareViewModel
        try {
            shell("settings put global animator_duration_scale 0")
            instrument.runOnMainSync {
                model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
                store.put("reduced-motion",model);model.accept(media.url);deliver(model,media,false)
            }
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {it.setContent {QuickShareSheet(model,{})}}
                compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("share_more").performScrollTo().performClick()
                compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
                  val sourceTag=compose.revealQuickShareVideoSource(media,media.formats.last())
                  compose.onNodeWithTag(sourceTag).performScrollTo().performClick()
                  compose.onNodeWithTag(sourceTag).assertIsSelected()
                  compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_video_quality_1280x720"))
                  compose.onNodeWithTag("share_video_quality_1280x720").assertIsSelected()
                assertEquals("720-b",model.state.value.choice?.formatId)
                assertNull(model.state.value.queuedId);assertNull(model.state.value.persistedId)
            }
        } finally {
            shell(if(original=="null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $original")
            instrument.runOnMainSync {store.clear()}
        }
    }
}
