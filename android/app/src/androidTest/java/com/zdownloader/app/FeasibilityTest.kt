package com.zdownloader.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeasibilityTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val publicUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
    @Test fun embeddedQuickJsAndEjsInitialization() = runBlocking {
        assertEquals("42",JsBridge.run("console.log(6 * 7)"))
        app.extractor.python().getModule("android_jsc")
        assertEquals("2026.08.19",JSONObject(app.extractor.version()).getString("extractor"))
    }
    @Test fun pythonAnalyzesAuthorizedPublicMedia() = runBlocking {
        val media = app.extractor.analyze(publicUrl)
        assertTrue(media.title.isNotBlank())
        assertTrue(media.formats.isNotEmpty())
    }
    @Test fun userInitiatedJobSavesVideoAndAudioAndPersistsHistory() = runBlocking {
        val media = app.extractor.analyze(publicUrl)
        val video = Selection.video(media,media.formats.first { it.video })
        val videoId = app.repository.create(media,video,"اختبار فيديو Z ${UUID.randomUUID()}")
        val audioId = app.repository.create(media,Selection.audio(media,"m4a"),"اختبار صوت Z ${UUID.randomUUID()}")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java))
        try {
            scenario.onActivity { runBlocking { app.scheduler.startFromUser() } }
            try { withTimeout(20000) { while(!app.coordinator.running) delay(10) } } catch(e: TimeoutCancellationException) {
                val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys jobscheduler com.zdownloader.app")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { android.util.Log.e("ZQueue",it.readText()) }
                throw e
            }
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            val deadline = System.currentTimeMillis()+120_000
            while(System.currentTimeMillis()<deadline) {
                val v = app.repository.dao.get(videoId)!!; val a = app.repository.dao.get(audioId)!!
                if(v.status in listOf("COMPLETED","FAILED") && a.status in listOf("COMPLETED","FAILED")) break
                delay(500)
            }
            listOf(videoId,audioId).forEach { id ->
                val entity = app.repository.dao.get(id)!!
                assertEquals(entity.error,"COMPLETED",entity.status)
                val uri = Uri.parse(entity.uri)
                app.contentResolver.openFileDescriptor(uri,"r").use { descriptor -> assertNotNull(descriptor); assertTrue(descriptor!!.statSize>0);assertEquals(descriptor.statSize,entity.outputSize) }
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app,uri)
                    assertTrue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
                    if(id==videoId) { val frame=retriever.getFrameAtTime(0); assertNotNull("Android could not decode video",frame); frame?.recycle() }
                } finally { retriever.release() }
                app.storage.deleteFile(entity)
            }
        } finally { scenario.close(); listOf(videoId,audioId).forEach { id -> app.repository.cancel(id) } }
    }
    @Test fun ffmpegMergesSeparateTracksAndConvertsAudio() = runBlocking {
        val media = app.extractor.analyze(publicUrl)
        val format = media.formats.first { it.video }
        val dir = app.storage.temporary("processing-test")
        try {
            val source = File(dir,"source.mp4")
            val callback = object : ProgressProxy { override fun progress(downloaded: String,total:String) { assertTrue(downloaded.toLong()>=0) } }
            val result = JSONObject(app.extractor.python().getModule("zextractor").callAttr("download_stream",format.payload,source.path,"fixture",callback).toString())
            assertFalse(result.toString(),result.has("error"))
            val video = File(dir,"video.mp4"); val audio = File(dir,"audio.m4a")
            listOf(arrayOf("-y","-i",source.path,"-map","0:v:0","-c","copy",video.path),arrayOf("-y","-i",source.path,"-map","0:a:0","-c","copy",audio.path)).forEach { args -> val s = FFmpegKit.executeWithArguments(args); assertTrue(s.allLogsAsString,ReturnCode.isSuccess(s.returnCode)) }
            val v = format.copy(audioCodec="none"); val a = format.copy(ext="m4a",videoCodec="none",audioCodec="aac")
            val processor = Processor()
            val merged = File(dir,"merged.mp4")
            processor.process(video,audio,merged,Selection(v,a,output="mp4"),TransferControl())
            val streams = FFprobeKit.getMediaInformation(merged.path).mediaInformation.streams
            assertTrue(streams.any { it.type == "video" })
            assertTrue(streams.any { it.type == "audio" })
            val flac = File(dir,"audio.flac")
            processor.process(source,null,flac,Selection(format,audioOnly=true,output="flac"),TransferControl())
            assertTrue(flac.length()>0)
        } finally { app.storage.removeTemporary("processing-test") }
    }
    @Test fun httpPausePreservesPartialAndRangeResumeProducesValidMedia() = runBlocking {
        val media = app.extractor.analyze(publicUrl)
        val format = media.formats.first { it.protocol=="https" }
        val dir = app.storage.temporary("resume-test")
        try {
            val file = File(dir,"resumed.mp4")
            val control = TransferControl()
            val transfer = Transfer(app.extractor)
            try { transfer.stream(format,file,"resume-test",control) { bytes,_ -> if(bytes>=65536) control.stop() }; fail("Pause did not stop transfer") } catch(_: CancellationException) { }
            // Native fallback can acknowledge cancellation before its buffered file closes.
            withTimeout(60000) {NativeWork.awaitIdle(dir)}
            assertFalse(file.exists())
            assertTrue(File(file.path+".part").length()>0)
            transfer.stream(format,file,"resume-test",TransferControl()) { _,_-> }
            Processor().validate(file,audio=false)
            assertTrue(file.length()>65536)
        } finally { app.storage.removeTemporary("resume-test") }
    }
    @Test fun queueDeduplicationAndPauseCancelPersist() = runBlocking {
        val media = app.extractor.analyze(publicUrl)
        val selection = Selection.video(media,media.formats.first { it.video })
        val id = app.repository.create(media,selection,"Queue persistence test")
        try {
            assertEquals(id,app.repository.create(media,selection,"Duplicate title"))
            app.repository.pause(id)
            assertEquals("PAUSED",app.repository.dao.get(id)!!.status)
            app.repository.resume(id)
            assertEquals("QUEUED",app.repository.dao.get(id)!!.status)
            app.repository.cancel(id)
            assertEquals("CANCELLED",app.repository.dao.get(id)!!.status)
        } finally { app.repository.cancel(id);app.repository.remove(id) }
    }
    @Test fun publicHlsDownloadsRealSegmentsAndValidatesOutput() = adaptiveSample("https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8","hls-test")
    @Test fun publicDashDownloadsAndMergesRealTracks() = adaptiveSample("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd","dash-test")
    private fun adaptiveSample(url: String, id: String) = runBlocking {
        val media = app.extractor.analyze(url)
        val format = media.formats.filter { it.video }.minByOrNull { it.height ?: Int.MAX_VALUE } ?: error("No video formats")
        val selection = Selection.video(media,format)
        val dir = app.storage.temporary(id)
        try {
            val primary = File(dir,"primary.${selection.primary.ext}")
            var received = 0L
            app.transfer.stream(selection.primary,primary,id,TransferControl()) { bytes,_ -> received=maxOf(received,bytes) }
            assertTrue("No real progress callbacks",received > 0)
            val secondary = selection.secondary?.let { track -> File(dir,"secondary.${track.ext}").also { app.transfer.stream(track,it,id,TransferControl()) { _,_-> } } }
            val output = File(dir,"output.${selection.output}")
            Processor().process(primary,secondary,output,selection,TransferControl(),media.duration)
            assertTrue(output.length() > 0)
            val retriever = android.media.MediaMetadataRetriever()
            try { retriever.setDataSource(output.path); val frame=retriever.getFrameAtTime(0); assertNotNull("Adaptive output cannot be decoded by Android",frame); frame?.recycle() } finally { retriever.release() }
            android.util.Log.i("ZCompatibility","Verified $id: bytes=${output.length()}, height=${format.height}, separateAudio=${secondary != null}")
            Unit
        } finally { app.storage.removeTemporary(id) }
    }
}
