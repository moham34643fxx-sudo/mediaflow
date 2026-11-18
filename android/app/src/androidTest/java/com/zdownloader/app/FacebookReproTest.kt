package com.zdownloader.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Processor
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real-world P0 reproduction. URL comes from instrumentation args; never hardcoded. */
class FacebookReproTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
    private fun evidence(name:String,value:JSONObject) { File(folder,"$name.json").writeText(value.toString(2)) }
    private fun sha(file:File):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use {source->val buffer=ByteArray(65536);while(true) {val n=source.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    @Test fun inspectFacebookSourceIdentity()=runBlocking {
        val url=InstrumentationRegistry.getArguments().getString("facebookUrl") ?: error("Supply -e facebookUrl")
        val result=app.extractor.pythonCall("analyze",url,"facebook-identity-${UUID.randomUUID()}")
        assertFalse(result.optString("error"),result.has("error"))
        evidence("facebook-source-identity",JSONObject().put("shared_url",url).put("id",result.optString("id"))
            .put("canonical_url",result.optString("url")).put("title",result.optString("title")).put("duration",result.optDouble("duration")))
    }

    @Test fun sharedFacebookVideoAnalyzesDownloadsAndValidates()=runBlocking {
        val url=InstrumentationRegistry.getArguments().getString("facebookUrl") ?: error("Supply -e facebookUrl")
        val report=JSONObject().put("shared_url",url)
        try {
            val media: Media=try {
                app.extractor.analyze(url,forceRefresh=true)
            } catch(e:MediaException) {
                report.put("extraction_error",e.code.name)
                evidence("facebook-repro",report)
                throw AssertionError("Facebook extraction failed honestly with ${e.code}; no download attempted")
            }
            val formats=JSONArray()
            media.formats.forEach { f->formats.put(JSONObject().put("id",f.id).put("ext",f.ext).put("protocol",f.protocol)
                .put("height",f.height).put("width",f.width).put("vcodec",f.videoCodec).put("acodec",f.audioCodec)
                .put("fps",f.fps).put("tbr",f.bitrate).put("duration",media.duration)) }
            report.put("id",media.id).put("title",media.title).put("duration",media.duration).put("formats",formats)
            evidence("facebook-repro",report)
            assertTrue("Extractor returned no usable formats",media.formats.any { it.video || it.audio })
            val video=media.videoOptions().firstOrNull() ?: error("No downloadable video representation")
            val selection=Selection.video(media,video)
            report.put("selected_id",video.id).put("selected_height",video.height).put("output",selection.output)
            // Path 1: direct transfer + process + strict validate (same engine MainActivity uses).
            val dir=app.storage.temporary("facebook-repro")
            suspend fun probe(file:File):JSONObject {
                return try {
                    val info=com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(file.path).mediaInformation
                    val streams=JSONArray()
                    info?.streams?.forEach { s->streams.put(JSONObject().put("type",s.type).put("codec",s.codec)
                        .put("width",s.width as Any?).put("height",s.height as Any?).put("fps",s.allProperties?.optString("avg_frame_rate"))) }
                    JSONObject().put("bytes",file.length()).put("duration",info?.duration)
                        .put("format",info?.allProperties?.optJSONObject("format")?.optString("format_name")).put("streams",streams)
                } catch(e:Exception) { JSONObject().put("probe_error",e.javaClass.simpleName) }
            }
            try {
                val primary=File(dir,"primary.${selection.primary.ext}")
                var received=0L
                app.transfer.stream(selection.primary,primary,"facebook-direct",com.zdownloader.app.engine.TransferControl()) { bytes,_ -> received=maxOf(received,bytes) }
                report.put("direct_bytes",primary.length()).put("direct_progress_seen",received>0).put("direct_video_sha256",sha(primary))
                val secondary=selection.secondary?.let { track->File(dir,"secondary.${track.ext}").also {
                    app.transfer.stream(track,it,"facebook-direct-audio",com.zdownloader.app.engine.TransferControl()) { _,_-> } } }
                report.put("has_separate_audio",secondary!=null).put("secondary_bytes",secondary?.length()).put("direct_audio_sha256",secondary?.let(::sha))
                val output=File(dir,"output.${selection.output}")
                try {
                    Processor().process(primary,secondary,output,selection,com.zdownloader.app.engine.TransferControl(),media.duration)
                } catch(e:MediaException) {
                    report.put("process_error",e.code.name)
                    report.put("primary_probe",probe(primary))
                    if(secondary!=null) report.put("secondary_probe",probe(secondary))
                    report.put("output_probe",probe(output))
                    if(output.isFile) output.copyTo(File(folder,"facebook-output-failed.mp4"),overwrite=true)
                    val strict=com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(arrayOf("-v","error","-xerror",
                        "-err_detect","explode","-c:v","av1_mediacodec","-protocol_whitelist","file,pipe","-i",output.path,
                        "-map","0:v:0?","-map","0:a:0?","-f","null","-"))
                    report.put("strict_decode_return",strict.returnCode?.value)
                    report.put("strict_decode_log",strict.allLogsAsString.takeLast(2000))
                    kotlinx.coroutines.delay(1500)
                    val diag=File(app.filesDir,"network-diagnostics.jsonl")
                    if(diag.isFile) {
                        val rows=diag.readLines().takeLast(40).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                            .filter { it.optString("stack")=="media" || (it.optString("stack")=="python" && it.optString("operation").startsWith("facebook")) }
                        report.put("diagnostic_tail",JSONArray(rows))
                    }
                    evidence("facebook-repro",report)
                    throw e
                }
                report.put("direct_validated",true).put("output_bytes",output.length())
                report.put("output_probe",probe(output))
                output.copyTo(File(folder,"facebook-validated.mp4"),overwrite=true)
                val retriever=android.media.MediaMetadataRetriever()
                try { retriever.setDataSource(output.path); assertNotNull("Android cannot decode output",retriever.getFrameAtTime(0)) } finally { retriever.release() }
                report.put("android_decode",true)
            } finally { app.storage.removeTemporary("facebook-repro") }
            // Path 2: the exact submit path both Quick Share and MainActivity use.
            val id=app.repository.create(media,selection,"Facebook repro ${UUID.randomUUID()}")
            try {
                app.repository.prepareForSubmission(id)
                val receipt=app.scheduler.startFromUser(id)
                report.put("schedule_mechanism",receipt.mechanism).put("runner_ready",receipt.runnerReady)
                withTimeout(600000) { while(app.repository.dao.get(id)!!.status !in listOf("COMPLETED","FAILED")) delay(500) }
                val row=app.repository.dao.get(id)!!
                report.put("queue_status",row.status).put("queue_error",row.error).put("queue_bytes",row.outputSize)
                assertEquals(row.error,"COMPLETED",row.status)
                val retriever=android.media.MediaMetadataRetriever()
                try { retriever.setDataSource(app,Uri.parse(row.uri)); assertNotNull(retriever.getFrameAtTime(0)) } finally { retriever.release() }
                report.put("queue_decode",true)
                app.storage.deleteFile(row)
            } finally { app.repository.cancel(id); app.coordinator.stopAndAwait(id); app.repository.remove(id) }
            report.put("verdict","PASS")
            evidence("facebook-repro",report)
        } catch(e:Throwable) {
            report.put("verdict","FAIL").put("failure",e.javaClass.simpleName).put("message",e.message)
            evidence("facebook-repro",report)
            throw e
        }
    }
}
