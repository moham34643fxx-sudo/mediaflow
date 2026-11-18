package com.zdownloader.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.DownloadRequest
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.ui.QuickShareViewModel
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class QuickSharePlaylistRegressionTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val source="https://youtube.com/playlist?list=PLSUcSqxe9RhyS7_hi2I5D7CH2sACsPjFW"
    @Test fun compactAudioPlaylistOpensBatchReviewAndRetainsMp3InsteadOfPersistingASingleFile() {
        val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            scenario.onActivity {activity->
                val model=ViewModelProvider(activity)[QuickShareViewModel::class.java]
                model.prefer(-1);model.audioOutput("mp3");model.enqueue()
                assertTrue(model.playlists.state.value.visible)
                assertTrue(model.playlists.state.value.initialPolicy.audio)
                assertEquals("mp3",model.playlists.state.value.initialPolicy.output)
                assertNull("A collection must not be committed as one unresolved file",model.state.value.persistedId)
                assertNull(model.state.value.queuedId)
            }
            compose.waitUntil(15000) {compose.onAllNodesWithTag("playlist_preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("playlist_list").performScrollToNode(hasTestTag("playlist_audio"))
            compose.onNodeWithTag("playlist_audio").assertIsSelected()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("playlist_mp3").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("playlist_mp3").assertIsSelected()
        }
    }
    @Test fun retryOfAnOldMisroutedPlaylistOpensAudioReviewAndPreservesItsHistory():Unit=runBlocking {
        val id="playlist-review-${UUID.randomUUID()}"
        app.repository.dao.insert(DownloadEntity(id,source,"Playlist review fixture","Fixture",null,null,"","fixture",null,id,
            status="FAILED",error=ErrorCode.PLAYLIST_DETECTED.name,request=DownloadRequest(audioOnly=true,output="mp3").json()))
        val store=ViewModelStore();var model:ZViewModel?=null
        try {
            withContext(Dispatchers.Main) {
                model=ZViewModel(app.repository,SettingsRepository(app),app.storage,app.extractor,app.scheduler,app.playlists,app.plannedDownloads)
                store.put("playlist-review",model!!);model!!.resume(id)
            }
            withTimeout(10000) {while(model!!.playlists.state.value.visible.not()) delay(25)}
            assertTrue(model!!.playlists.state.value.initialPolicy.audio)
            assertEquals("mp3",model!!.playlists.state.value.initialPolicy.output)
            assertEquals("FAILED",app.repository.dao.get(id)!!.status)
        } finally {withContext(Dispatchers.Main) {store.clear()};app.repository.dao.removeHistory(id)}
    }
}
