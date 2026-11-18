package com.zdownloader.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PreferenceRequestContractTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    @Test fun senderExtrasCannotForgeSavedSubmissionStateOrOperationPaths():Unit=runBlocking {
        val url="https://example.com/video"
        val rejected=runCatching {app.repository.createRequest("../forged",url,DownloadRequest(height=720),null)}.exceptionOrNull()
        assertEquals(ErrorCode.INTERNAL_ENGINE_ERROR,(rejected as MediaException).code)
        val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
            .putExtra("queuedId","forged").putExtra("persistedId","forged").putExtra("requestOperation","../forged")
            .putExtra("qualityPreference",720).putExtra("url",url)
        var model:QuickShareViewModel?=null
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_preference_720").assertExists().assertIsNotSelected()
            compose.onNodeWithTag("share_download").assertIsNotEnabled()
            val state=requireNotNull(model).state.value
            assertNull(state.queuedId);assertNull(state.persistedId);assertNull(state.qualityPreference);assertEquals(url,state.sourceUrl)
        }
    }
    @Test fun unresolvedRequestSurvivesClosingAndReopeningRoomWithoutFormats()=runBlocking {
        val name="preference-reopen-${UUID.randomUUID()}.db"
        val id=UUID.randomUUID().toString();val request=DownloadRequest(height=720,output="mp4",template="{title}-kept",retries=3)
        var database=Room.databaseBuilder(app,ZDatabase::class.java,name).build()
        try {
            database.downloads().insertRequest(DownloadEntity(id,"https://example.com/video","Pending","example.com",null,null,"","Pending",null,id,request=request.json()))
            database.close()
            database=Room.databaseBuilder(app,ZDatabase::class.java,name).build()
            val recovered=database.downloads().get(id)!!
            assertTrue(recovered.selection.isEmpty());assertEquals(request,DownloadRequest.parse(recovered.request!!))
            assertEquals("QUEUED",recovered.status)
            File(folder,"request-reopen.json").writeText(JSONObject().put("same_operation",recovered.id==id).put("request_preserved",true)
                .put("format_not_required",true).put("scope","Real SQLite close/reopen; not an OS cold-restart scheduling claim").toString(2))
        } finally {database.close();app.deleteDatabase(name)}
    }
    @Test fun realQualitySelectionSurvivesActivityRecreation() {
        val url="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4?recreate=${UUID.randomUUID()}"
        val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            var model:QuickShareViewModel?=null
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(90_000) {
                val media=model!!.state.value.media
                media!=null&&media.qualityGroups().any {it.resolution==720}&&
                    compose.onAllNodesWithTag("share_preference_720").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_preference_720").performScrollTo().performClick()
            val selected=requireNotNull(model!!.state.value.media)
            assertEquals(720,verifiedQualityEdge(requireNotNull(VideoPreferenceSelection.ready(selected,720)).primary))
            scenario.recreate()
            compose.waitUntil(30_000) {
                val media=model!!.state.value.media
                media!=null&&media.qualityGroups().any {it.resolution==720}&&
                    compose.onAllNodesWithTag("share_preference_720").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_preference_720").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("share_download").assertIsEnabled()
            scenario.onActivity {val state=ViewModelProvider(it)[QuickShareViewModel::class.java].state.value
                assertEquals(720,state.qualityPreference);assertNull(state.persistedId);assertNull(state.queuedId)
                assertEquals(720,verifiedQualityEdge(requireNotNull(VideoPreferenceSelection.ready(requireNotNull(state.media),720)).primary))}
        }
    }
    private fun failure(url:String,label:String)=runBlocking {
        val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            var model:QuickShareViewModel?=null
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
            compose.waitUntil(120_000) {
                val state=model!!.state.value
                state.error!=null||state.media!=null&&!state.analyzing
            }
            val state=requireNotNull(model).state.value
            assertNotNull("Unsupported media must fail discovery rather than invent a selectable quality",state.error)
            assertNull(state.media)
            assertFalse(state.canSubmit)
            for(height in listOf(360,480,720,1080)) compose.onNodeWithTag("share_video_$height").assertDoesNotExist()
            compose.onNodeWithTag("share_download").assertIsNotEnabled()
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
            File(folder,"unsupported-$label.json").writeText(JSONObject().put("url",url).put("discovery_error",state.error?.name)
                .put("selectable_video_rows",0).put("queued",false).put("scope","No fabricated quality or queue submission when no source representation exists").toString(2))
        }
    }
    @Test fun unsupportedPublicPageFailsInBackgroundWithoutAnotherConfirmation()=failure("https://example.com/","unsupported")
    @Test fun removedPublicMediaFailsInBackgroundWithoutIndefiniteAnalysis()=failure("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/mediaflow-missing-video.mp4","unavailable")
}
