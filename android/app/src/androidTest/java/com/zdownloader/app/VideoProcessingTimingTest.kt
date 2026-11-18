package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline AV1 fixture: verifies stream-copy plus full validation and separate stage timings. */
class VideoProcessingTimingTest {
    @Test fun remuxAndFullAv1ValidationAreTimedSeparatelyWithoutTranscoding()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        check(context.packageName=="com.zdownloader.app.designreview")
        val directory=File(context.filesDir,"video-timing-${UUID.randomUUID()}").apply {mkdirs()}
        val input=File(directory,"input.mp4");val output=File(directory,"output.mp4")
        try {
            instrument.context.assets.open("av1-validation.mp4").use {source->input.outputStream().use {source.copyTo(it)}}
            val info=FFprobeKit.getMediaInformation(input.path).mediaInformation
            val video=info.streams.first {it.type=="video"};val audio=info.streams.first {it.type=="audio"}
            val duration=info.duration.toDouble()
            val source=Format("fixture","mp4",video.height?.toInt() ?: error("Missing fixture height"),null,video.codec,audio.codec,input.length(),null,"https","{\"url\":\"https://example.com/fixture.mp4\"}",width=video.width?.toInt() ?: error("Missing fixture width"))
            val selection=Selection(source,output="mp4",duration=duration)
            assertFalse(selection.transcodeVideo);assertFalse(selection.transcodeAudio)
            val control=TransferControl();var validationStarted=0L
            control.onValidationReady={validationStarted=android.os.SystemClock.elapsedRealtime()}
            val start=android.os.SystemClock.elapsedRealtime()
            Processor().process(input,null,output,selection,control,duration,"Fixture")
            val end=android.os.SystemClock.elapsedRealtime()
            assertTrue(validationStarted>=start);assertTrue(output.length()>0)
            assertEquals("av1",FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.first {it.type=="video"}.codec)
            val evidence=File(context.filesDir,"quickshare-review").apply {mkdirs()}
            File(evidence,"video-processing-timing.json").writeText(JSONObject().put("scope","Offline 2-second 128x96 AV1 fixture; not the user's full video or a before/after benchmark")
                .put("sourceBytes",input.length()).put("outputBytes",output.length()).put("codec","av1").put("streamCopy",true)
                .put("inspectionAndMuxMs",validationStarted-start).put("validationMs",end-validationStarted).put("fullValidationPassed",true).toString(2))
        } finally {input.delete();output.delete();directory.delete()}
    }
}
