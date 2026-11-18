package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Observe/clean only the exact public fixture after the autonomous crash probe. */
class AutonomousProcessObservationTest {
    @Test fun recordDeferredPreferenceState()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val since=requireNotNull(args.getString("deferredStartedAt")).toLong()
        val rows=app.repository.dao.observe().first().filter {it.createdAt>=since&&it.url=="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8"}
        val report=JSONObject().put("scope","Diagnostic after explicit process restart; not autonomous cold-restart proof")
            .put("recovery_needed",app.repository.dao.recoveryNeeded()).put("queue_running",app.coordinator.running)
            .put("rows",JSONArray(rows.map {row->JSONObject().put("id",row.id).put("status",row.status).put("checkpoint",row.checkpoint)
                .put("has_selection",row.selection.isNotBlank()).put("error",row.error).put("bytes",row.bytes).put("uri",row.uri)
                .put("events",JSONArray(app.repository.dao.events(row.id).map {JSONObject().put("state",it.state).put("time",it.time)}))}))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"deferred-preference-state.json").writeText(report.toString(2))
        assertTrue("Expected exactly the public process-loss fixture",rows.size==1)
    }
    @Test fun verifyPublicationAlreadyCompletedBeforeTargetInstrumentation()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val uri=requireNotNull(args.getString("verifiedPublicationUri"))
        val row=app.repository.dao.completed().first().single {it.uri==uri}
        val expected=args.getString("verifiedSourceUrl","https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8")
        require(expected=="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8"||expected.startsWith("https://www.w3schools.com/html/mov_bbb.mp4?mediaflow_r8_probe="))
        require(row.url==expected)
        assertEquals(720,com.zdownloader.app.domain.DownloadRequest.parse(row.request!!).height)
        val selection=Selection.parse(row.selection)
        assertTrue(com.zdownloader.app.domain.verifiedQualityEdge(selection.primary)!!<=720)
        val retriever=android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(app,android.net.Uri.parse(uri))
            val frame=retriever.getFrameAtTime(1000000);assertNotNull(frame);frame?.recycle()
            assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            assertEquals(selection.primary.width.toString(),retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals(selection.primary.height.toString(),retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        } finally {retriever.release()}
        val events=app.repository.dao.events(row.id)
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"uninstrumented-publication-verified.json").writeText(JSONObject().put("status",row.status).put("uri",uri)
            .put("width",selection.primary.width).put("height",selection.primary.height).put("bytes",row.outputSize)
            .put("frame_and_audio_verified",true).put("manual_resume",false)
            .put("events",JSONArray(events.map {JSONObject().put("state",it.state).put("time",it.time)}))
            .put("scope","External UID driver observed publication before target instrumentation was started; this test verifies the already-complete file").toString(2))
        app.coordinator.stopAndAwait(row.id);app.storage.deleteFile(row)
    }
    @Test fun recordOutcomeAfterInstrumentationRestartWithoutManualResume()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val id=requireNotNull(args.getString("processFixtureId"))
        val row=requireNotNull(app.repository.dao.get(id))
        require(row.url=="https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd"&&row.title=="manifest")
        val events=app.repository.dao.events(id)
        val report=JSONObject().put("id",id).put("state",row.status).put("checkpoint",row.checkpoint).put("error",row.error)
            .put("uri",row.uri).put("bytes",row.outputSize).put("events",JSONArray(events.map {it.state}))
            .put("scope","Observed after restarting target instrumentation; NOT autonomous cold process restart proof")
            .put("manual_resume",false)
        if(row.status=="COMPLETED") {
            val selection=Selection.parse(row.selection)
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app,android.net.Uri.parse(row.uri))
                val frame=retriever.getFrameAtTime(1000000)
                assertNotNull(frame);frame?.recycle()
                assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals(selection.primary.height.toString(),retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                report.put("frame_and_audio_verified",true)
            } finally {retriever.release()}
        }
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"process-after-instrumentation.json").writeText(report.toString(2))
        app.coordinator.stopAndAwait(id)
        if(row.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(id);app.repository.remove(id)}
        Unit
    }
}
