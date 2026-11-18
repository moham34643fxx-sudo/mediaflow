package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Non-AAC audio must not force a compatible video stream through an encoder. */
class VideoAudioCompatibilityAuditTest {
    @Test fun opusAndVorbisBecomeAacWithoutChangingVideoPackets()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        val root=File(app.cacheDir,"video-audio-compat-${UUID.randomUUID()}").apply {check(mkdirs())}
        val source=File(root,"source.mp4")
        instrumentation.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
        val control=TransferControl()
        val cases=JSONArray()
        val report=JSONObject().put("passed",false).put("cases",cases)
            .put("scope","Local separate-track production Processor with full validation and independent encoded-video packet hash")
        try {
            val video=File(root,"video.mp4")
            // Chapter metadata can synthesize an MP4 text/data track despite explicit -map.
            // Strip chapters only in this generated fixture to isolate the two-track mux contract.
            execute(listOf("-i",source.path,"-t","3","-map","0:v:0","-map_chapters","-1","-an","-c:v","copy",video.path))
            val info=FFprobeKit.getMediaInformation(video.path).mediaInformation!!
            assertEquals("Generated video fixture must contain no chapter/data tracks",1,info.streams.size)
            val stream=info.streams.single {it.type=="video"}
            assertEquals("The retained fixture must exercise H.264 copy","h264",stream.codec)
            val originalPackets=File(root,"source-video.sha256")
            packetHash(video,originalPackets)
            val format=Format.parse(JSONObject().put("format_id","compat-video").put("url","https://compat-audit.example/video")
                .put("ext","mp4").put("vcodec",stream.codec).put("acodec","none").put("protocol","https")
                .put("width",stream.width).put("height",stream.height))
            for(codec in listOf("opus","vorbis")) {
                val audio=File(root,"audio-$codec.webm")
                execute(listOf("-i",source.path,"-t","3","-map","0:a:0","-map_chapters","-1","-vn","-ac","2","-c:a",codec,"-strict","-2",audio.path))
                assertEquals(codec,FFprobeKit.getMediaInformation(audio.path).mediaInformation!!.streams.single().codec)
                // Incorrect extracted audio labels must not override the actual downloaded codec.
                val advertised=Format.parse(JSONObject().put("format_id","compat-audio").put("url","https://compat-audit.example/audio")
                    .put("ext","m4a").put("vcodec","none").put("acodec","aac").put("protocol","https"))
                val output=File(root,"output-$codec.mp4")
                val selection=Selection(format,advertised,output="mp4",embedMetadata=false)
                Processor().process(video,audio,output,selection,control,expectedDuration=info.duration.toDouble())
                val actual=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
                val inventory=JSONArray(actual.streams.map {JSONObject().put("type",it.type).put("codec",it.codec).put("properties",it.allProperties)})
                report.put("last_output_streams",inventory).put("source_video_streams",JSONArray(info.streams.map {it.allProperties}))
                assertEquals("Unexpected output stream inventory: $inventory",2,actual.streams.size)
                assertEquals("h264",actual.streams.single {it.type=="video"}.codec)
                assertEquals("aac",actual.streams.single {it.type=="audio"}.codec)
                assertEquals(info.duration.toDouble(),actual.duration.toDouble(),0.15)
                val outputPackets=File(root,"output-video-$codec.sha256")
                packetHash(output,outputPackets)
                assertEquals("Compatible video was changed during audio-only conversion",originalPackets.readText().trim(),outputPackets.readText().trim())
                cases.put(JSONObject().put("actual_input_audio",codec).put("advertised_input_audio","aac")
                    .put("output_audio","aac").put("video_packets_identical",true).put("duration_seconds",actual.duration.toDouble()))
            }
            report.put("passed",true)
        } finally {withContext(NonCancellable) {
            control.stop();withTimeout(15_000) {NativeWork.awaitIdle(root)}
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"video-audio-compatibility.json").writeText(report.toString(2))
            check(root.deleteRecursively()) {"Could not remove test-owned codec fixture"}
        }}
    }

    private suspend fun packetHash(source:File,output:File)=execute(listOf("-i",source.path,"-map","0:v:0","-an",
        "-c:v","copy","-f","hash","-hash","sha256",output.path))

    private suspend fun execute(arguments:List<String>) {
        val finished=CompletableDeferred<FFmpegSession>()
        val session=FFmpegKit.executeWithArgumentsAsync((listOf("-y","-nostdin","-v","error","-protocol_whitelist","file,pipe")+arguments).toTypedArray(),{finished.complete(it)})
        try {
            val completed=withTimeout(30_000) {finished.await()}
            check(ReturnCode.isSuccess(completed.returnCode)) {"Local codec fixture command failed: ${completed.allLogsAsString.takeLast(500)}"}
        } finally {withContext(NonCancellable) {
            if(!finished.isCompleted) FFmpegKit.cancel(session.sessionId)
            withTimeout(10_000) {finished.await()}
        }}
    }
}
