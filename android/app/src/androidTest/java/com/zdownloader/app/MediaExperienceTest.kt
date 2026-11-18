package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaExperienceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val settings get()=com.zdownloader.app.data.SettingsRepository(app)
    @Test fun metadataBoundaryPreservesUnknownCodecsAndSizeProvenance() {
        val exact=Format.parse(JSONObject("""{"format_id":"v","ext":"mp4","resolution":"1920x1080","vcodec":null,"acodec":"none","filesize":2000,"filesize_approx":3000,"vbr":900,"abr":128}"""))
        assertTrue(exact.video);assertFalse(exact.audio);assertEquals(1080,exact.height)
        assertEquals(MediaSize(2000,false),exact.size(60.0))
        val approximate=Format.parse(JSONObject("""{"format_id":"a","ext":"m4a","vcodec":"none","acodec":null,"filesize_approx":1234}"""))
        assertTrue(approximate.streamOnly);assertEquals(MediaSize(1234,true),approximate.size(null))
        assertEquals(60.0,ManifestMetadata.dashDuration("<MPD type=\"static\" mediaPresentationDuration=\"PT1M\">"))
        assertNull(ManifestMetadata.hlsDuration("#EXTM3U\n#EXTINF:5,\nx.ts"))
        assertEquals(10.0,ManifestMetadata.hlsDuration("#EXTM3U\n#EXTINF:5,\nx.ts\n#EXTINF:5,\ny.ts\n#EXT-X-ENDLIST"))
    }
    @Test fun allDashQualitiesAndRealMp4WebmOutputsMatchSelection() = runBlocking {
        val media=app.extractor.analyze("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
        assertEquals(setOf(144,240,360,480,576),media.videoOptions().mapNotNull { it.height }.toSet())
        assertEquals(10,media.videoOptions().size)
        assertEquals(60.0,media.duration!!,.01)
        val reports=org.json.JSONArray()
        for((height,ext) in listOf(144 to "mp4",360 to "mp4",240 to "webm")) {
            val format=media.videoOptions().first { it.height==height && it.ext==ext }
            val selection=Selection.video(media,format)
            assertEquals(ext,selection.output)
            assertNotNull(selection.secondary)
            assertNotNull(selection.outputSize)
            assertTrue(selection.outputSize!!.estimated)
            assertEquals(selection,Selection.parse(selection.json()))
            val id="quality-$height-$ext";val dir=app.storage.temporary(id)
            try {
                val primary=File(dir,"primary.${format.ext}")
                val secondary=File(dir,"secondary.${selection.secondary!!.ext}")
                app.transfer.stream(format,primary,id,TransferControl()) { _,_-> }
                app.transfer.stream(selection.secondary!!,secondary,id,TransferControl()) { _,_-> }
                val output=File(dir,"output.$ext")
                Processor().process(primary,secondary,output,selection,TransferControl(),media.duration)
                val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
                assertTrue(info.streams.any { it.type=="video" && it.height?.toInt()==height })
                assertTrue(info.streams.any { it.type=="audio" })
                assertTrue(info.format.contains(if(ext=="mp4") "mp4" else "webm"))
                val retriever=android.media.MediaMetadataRetriever()
                try { retriever.setDataSource(output.path);val frame=retriever.getFrameAtTime(0);assertNotNull(frame);frame?.recycle() } finally { retriever.release() }
                reports.put(JSONObject().put("height",height).put("container",ext).put("actual_bytes",output.length()).put("estimated_bytes",selection.outputSize!!.bytes))
            } finally { app.storage.removeTemporary(id) }
        }
        File(app.getExternalFilesDir(null),"quality-verification.json").writeText(reports.toString(2))
    }
    @Test fun directSizeAndRealAudioConversionsAreValid() = runBlocking {
        val media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4",forceRefresh=true)
        assertEquals(788493L,media.formats.first().bytes)
        assertFalse(media.formats.first().sizeEstimated)
        val dir=app.storage.temporary("audio-formats")
        try {
            val source=File(dir,"source.mp4")
            app.transfer.stream(media.formats.first(),source,"audio-formats",TransferControl()) { _,_-> }
            assertEquals(media.formats.first().bytes,source.length())
            val video=FFprobeKit.getMediaInformation(source.path).mediaInformation.streams.first { it.type=="video" }
            assertEquals(176,video.height.toInt())
            media.formats.first().height?.let { assertEquals(it,video.height.toInt()) }
            for((ext,codec) in listOf("m4a" to "aac","aac" to "aac","flac" to "flac","opus" to "opus")) {
                val selection=Selection.audio(media,ext)
                val output=File(dir,"audio.$ext")
                Processor().process(source,null,output,selection,TransferControl(),media.duration)
                assertTrue(FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.any { it.type=="audio" && it.codec==codec })
                assertTrue(output.length()>0)
            }
        } finally { app.storage.removeTemporary("audio-formats") }
    }
    @Test fun preferencesPersistWithoutResettingInternalOptions() = runBlocking {
        val old=settings.current()
        try {
            settings.integer("preferred_height",720);settings.text("download_type","audio")
            settings.boolean("completion_notifications",false);settings.boolean("failure_notifications",true)
            val next=com.zdownloader.app.data.SettingsRepository(app).current()
            assertEquals(720,next.preferredHeight);assertEquals("audio",next.downloadType)
            assertFalse(next.completionNotifications);assertTrue(next.failureNotifications)
            assertEquals(old.template,next.template);assertEquals(old.retries,next.retries)
        } finally { settings.integer("preferred_height",old.preferredHeight);settings.text("download_type",old.downloadType);settings.boolean("completion_notifications",old.completionNotifications);settings.boolean("failure_notifications",old.failureNotifications) }
    }
    @Test fun realQueueProgressStagesAndSavedSizeAreConsistent() = runBlocking {
        val media=app.extractor.analyze("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
        val selection=Selection.video(media,media.videoOptions().first { it.height==480 && it.ext=="mp4" })
        val id=app.repository.create(media,selection,"Progress verification ${java.util.UUID.randomUUID()}")
        var positiveSpeed=false;var increasingBytes=false;var previous=0L;var saved:com.zdownloader.app.data.DownloadEntity?=null
        val collector=launch(Dispatchers.IO) {
            app.repository.downloads.collect { rows -> rows.firstOrNull { it.id==id }?.let { row ->
                if(row.status in listOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO")) { if(row.bytes>previous) increasingBytes=true;previous=row.bytes;if(row.speed>0) positiveSpeed=true }
            } }
        }
        try {
            withTimeout(120000) { app.coordinator.run() }
            saved=app.repository.dao.get(id)!!
            assertEquals(saved.error,"COMPLETED",saved.status)
            assertTrue("No increasing real-byte updates",increasingBytes)
            assertTrue("No measured transfer speed",positiveSpeed)
            assertTrue(saved.bytes>1000000)
            val events=app.repository.dao.events(id).map { it.state }
            assertTrue(events.contains("MERGING"));assertTrue(events.contains("VALIDATING"));assertTrue(events.contains("SAVING"))
            app.contentResolver.openFileDescriptor(android.net.Uri.parse(saved.uri),"r")!!.use { assertEquals(it.statSize,saved.outputSize) }
            File(app.getExternalFilesDir(null),"progress-verification.json").writeText(JSONObject().put("transferred_bytes",saved.bytes).put("final_output_bytes",saved.outputSize).put("observed_positive_speed",positiveSpeed).put("phases",org.json.JSONArray(events)).toString(2))
        } finally { collector.cancelAndJoin();saved?.takeIf { it.status=="COMPLETED" }?.let { app.storage.deleteFile(it) } ?: app.repository.cancel(id) }
    }
}
