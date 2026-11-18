package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PublicMediaKindAcceptanceTest {
    @Test fun publicImageVideoAndAudioLinksKeepTheirSelectedKindThroughProcessing()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val root=File(app.cacheDir,"public-media-kind-${UUID.randomUUID()}").apply {check(mkdirs())}
        val reports=JSONArray()
        try {
            for((kind,url) in listOf("image" to "https://www.w3schools.com/html/img_girl.jpg",
                "video" to "https://www.w3schools.com/html/mov_bbb.mp4","audio" to "https://www.w3schools.com/html/horse.mp3")) {
                val media=withTimeout(100_000) {app.extractor.analyze(url,forceRefresh=true,enrichOptional=false)}
                val selection=when(kind) {
                    "image"->{assertTrue(media.videoOptions().isEmpty());assertTrue(media.audioOptions().isEmpty());Selection.image(media,media.originalImage()!!)}
                    "audio"->{assertTrue(media.videoOptions().isEmpty());assertTrue(media.imageOptions().isEmpty());Selection.audio(media,"original")}
                    else->{assertTrue(media.imageOptions().isEmpty());Selection.video(media,media.videoOptions().first())}
                }
                val source=File(root,"$kind-source.${selection.primary.ext}")
                val output=File(root,"$kind-output.${selection.output}")
                val control=TransferControl()
                withTimeout(180_000) {
                    Transfer(app.extractor).stream(selection.primary,source,"media-kind-${UUID.randomUUID()}",control) {_,_->}
                    Processor().process(source,null,output,selection,control,media.duration)
                }
                assertTrue(output.length()>0)
                if(kind=="image") assertArrayEquals(source.readBytes(),output.readBytes())
                else {
                    val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
                    assertEquals(kind=="video",info.streams.any {it.type=="video"})
                    if(kind=="audio") assertTrue(info.streams.any {it.type=="audio"})
                }
                reports.put(JSONObject().put("kind",kind).put("source",url).put("output_extension",selection.output)
                    .put("output_bytes",output.length()).put("video_options",media.videoOptions().size)
                    .put("image_options",media.imageOptions().size).put("audio_options",media.audioOptions().size))
            }
        } finally {
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(folder,"public-media-kinds.json").writeText(JSONObject().put("cases",reports).toString(2))
            root.deleteRecursively()
        }
    }
}
