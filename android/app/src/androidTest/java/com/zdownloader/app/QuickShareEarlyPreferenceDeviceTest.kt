package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.DownloadRequest
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.verifiedQualityEdge
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

/** Real controls: submit before discovery, then resolve, process and publish without the share UI. */
class QuickShareEarlyPreferenceDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun early720PreferenceIsDurableAndResolvedByTheBackgroundQueue()=earlyPreferenceCompletes(720)
    @Test fun earlyMp3PreferenceProducesAudioOnlyAfterTheShareCloses()=earlyPreferenceCompletes(-1)

    private fun earlyPreferenceCompletes(preference:Int)=runBlocking {
        val audio=preference==-1
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?immediate=${UUID.randomUUID()}"
        val intent=Intent(Intent.ACTION_SEND).setClass(instrumentation.targetContext,ShareReceiverActivity::class.java)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
        var activity:ShareReceiverActivity?=null
        var model:QuickShareViewModel?=null
        var id:String?=null
        try {
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                scenario.onActivity {receiver->
                    activity=receiver
                    model=ViewModelProvider(receiver)[QuickShareViewModel::class.java]
                    assertNull(model!!.state.value.media)
                }
                compose.waitUntil(15_000) {
                    model!!.state.value.media==null&&
                        compose.onAllNodesWithTag("share_preference_720").fetchSemanticsNodes().isNotEmpty()
                }
                val tag=if(audio) "share_preference_mp3" else "share_preference_$preference"
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
                assertEquals(preference,model!!.state.value.qualityPreference)
                if(audio) compose.waitUntil(10_000) {model!!.state.value.mp3Available}
                assertNull("Submit before source metadata arrives",model!!.state.value.media)
                assertTrue("The real pre-extraction selection must enable submission",model!!.state.value.canSubmit)
                compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
                withTimeout(30_000) {
                    while(model!!.state.value.queuedId==null) {
                        model!!.state.value.error?.let {fail("Early preference was not accepted: $it")}
                        delay(50)
                    }
                }
                id=model!!.state.value.queuedId
                val row=requireNotNull(app.repository.dao.get(id!!))
                val request=DownloadRequest.parse(requireNotNull(row.request) {"Early choice must persist as a DownloadRequest"})
                if(!audio) assertEquals(preference,request.height)
                assertEquals(audio,request.audioOnly)
                if(audio) assertEquals("mp3",request.output)
                assertFalse(request.exact)
                val deadline=SystemClock.elapsedRealtime()+20_000
                while(scenario.state!=Lifecycle.State.DESTROYED&&!activity!!.isFinishing&&!activity!!.isDestroyed) {
                    if(SystemClock.elapsedRealtime()>=deadline) {
                        fail("Queue accepted but share activity stayed open; lifecycle=${scenario.state}, state=${model!!.state.value}")
                    }
                    Thread.sleep(50)
                }
            }

            val resolved=withTimeout(120_000) {
                while(true) {
                    val row=app.repository.dao.get(id!!) ?: error("Accepted request disappeared")
                    if(row.selection.isNotBlank()||row.status in setOf("FAILED","CANCELLED")) return@withTimeout row
                    delay(100)
                }
                error("unreachable")
            }
            assertTrue("Background request failed before resolving: ${resolved.error}",resolved.selection.isNotBlank())
            val selected=Selection.parse(resolved.selection)
            assertEquals(audio,selected.audioOnly)
            if(!audio) {
                val edge=verifiedQualityEdge(selected.primary)
                assertNotNull("Background resolver must select a real pixel quality",edge)
                assertTrue("Resolved format exceeded the ${preference}p cap: $edge",edge!!<=preference)
            }
            val completed=withTimeout(600_000) {
                while(true) {
                    val row=requireNotNull(app.repository.dao.get(id!!))
                    if(row.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")) return@withTimeout row
                    delay(200)
                }
                error("unreachable")
            }
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertEquals("VALIDATED",completed.checkpoint)
            val uri=Uri.parse(requireNotNull(completed.uri))
            app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));assertTrue(cursor.getLong(1)>0)
            }
            val proof=File(app.storage.temporary("vertical-proof-$id"),"published.${selected.output}")
            app.contentResolver.openInputStream(uri)!!.use {input->proof.outputStream().use {input.copyTo(it)}}
            val info=requireNotNull(FFprobeKit.getMediaInformation(proof.path).mediaInformation)
            assertTrue(info.streams.any {it.type=="audio"})
            if(audio) {
                assertFalse(info.streams.any {it.type=="video"})
                assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            } else {
                val video=info.streams.first {it.type=="video"}
                assertEquals(selected.primary.width,video.width?.toInt())
                assertEquals(selected.primary.height,video.height?.toInt())
                val retriever=android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(proof.path)
                    val frame=retriever.getFrameAtTime(1_000_000)
                    assertNotNull("Published file must decode",frame);frame?.recycle()
                } finally {retriever.release()}
            }
            val events=app.repository.dao.events(id!!).map {it.state}
            assertTrue(events.containsAll(listOf("TRANSFER_COMPLETE","VALIDATING","SAVING")))
            if(selected.secondary!=null) assertTrue(events.contains(if(selected.transcodeAudio||selected.transcodeVideo) "CONVERTING" else "MERGING"))
            if(audio) assertTrue("MP3 encoding must run in the background",events.contains("CONVERTING"))
        } finally {
            (id ?: model?.state?.value?.persistedId)?.let {key->
                app.storage.removeTemporary("vertical-proof-$key")
                app.coordinator.stopAndAwait(key)
                val row=app.repository.dao.get(key)
                if(row?.status=="COMPLETED") {
                    app.storage.deleteFile(row)
                    app.repository.remove(key)
                }
                else {
                    app.repository.cancel(key)
                    app.coordinator.stopAndAwait(key)
                    app.repository.remove(key)
                }
            }
            activity?.let {receiver->instrumentation.runOnMainSync {if(!receiver.isFinishing) receiver.finish()}}
        }
    }
}
