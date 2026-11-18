package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.codecFamily
import com.zdownloader.app.engine.DiagnosticsExport
import com.zdownloader.app.engine.NetworkDiagnostics
import com.zdownloader.app.engine.PerformanceCapture
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicitly invoked on dedicated phones. Original media and queue records are retained. */
class DeviceReliabilityInvestigationTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication

    @Test fun pauseUnfinishedTestJobsWithoutRemovingTheirCheckpoints() = runBlocking<Unit> {
        val unfinished=app.repository.downloads.first().filter {
            it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED","SCHEDULED")
        }
        for(row in unfinished) {
            if(row.status=="INTERRUPTED") app.repository.resume(row.id)
            app.repository.pause(row.id)
            val paused=requireNotNull(app.repository.dao.get(row.id))
            assertTrue(paused.status in setOf("PAUSING","PAUSED"))
            assertEquals(row.checkpoint,paused.checkpoint)
            assertEquals(row.selection,paused.selection)
        }
    }

    @Test fun replayRetained4kAv1ValidationOnACopyWithoutChangingTheSavedJob() = runBlocking<Unit> {
        val rows=app.repository.downloads.first()
        assertTrue("Pause outstanding test jobs before isolated replay",rows.all {
            it.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")
        })
        val folder=evidenceDirectory()
        val targetJobId=requireNotNull(InstrumentationRegistry.getArguments().getString("targetJobId"))
        val rowFacts=JSONArray()
        rows.forEachIndexed {index,row->
            val selected=runCatching {Selection.parse(row.selection)}.getOrNull()
            val primary=selected?.primary
            rowFacts.put(JSONObject().put("alias","job${index+1}").put("target",row.id==targetJobId)
                .put("status",row.status).put("checkpoint",row.checkpoint)
                .put("selectionParsed",selected!=null)
                .put("videoCodec",primary?.videoCodec?:JSONObject.NULL)
                .put("height",primary?.height?:JSONObject.NULL).put("fps",primary?.fps?:JSONObject.NULL))
        }
        File(folder,"precondition.json").writeText(JSONObject().put("rows",rowFacts).toString(2))
        val targetRows=rows.filter {it.id==targetJobId}
        assertEquals("The explicitly identified retained job must still exist",1,targetRows.size)
        val original=targetRows.single()
        val selection=Selection.parse(original.selection)
        assertTrue("Retained job must have a completed validation checkpoint",original.checkpoint in setOf("MERGED","VALIDATED"))
        assertEquals("Retained job must be AV1", "av1",codecFamily(selection.primary.videoCodec))
        assertEquals("Retained job must be 4K",2160,selection.primary.height)
        assertEquals("Retained job must be 30 fps",30.0,selection.primary.fps)
        val report=JSONObject().put("scope","Retained output validation replay on a private copy; no download, publication or original-job mutation")
        val source=File(app.storage.temporary(original.id),"output.${selection.output}")
        val published=original.status=="COMPLETED"
        if(!published) assertTrue("Original merged output must still exist",source.isFile)
        fun originalInput()=if(published) requireNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(requireNotNull(original.uri)))) else source.inputStream()
        val sourceBytes=if(published) requireNotNull(original.outputSize) else source.length()
        val copied=File(folder,"replay.${selection.output}")
        assertTrue("Preserve free space before copying retained media",
            android.os.StatFs(folder.path).availableBytes>sourceBytes+128L*1024*1024)
        val sourceHash=originalInput().use(::sha256)
        copied.outputStream().use {destination->originalInput().use {it.copyTo(destination)}}
        assertEquals(sourceHash,sha256(copied))
        report.put("input_sha256",sourceHash).put("input_bytes",copied.length())
            .put("width",selection.primary.width).put("height",selection.primary.height)
            .put("fps",selection.primary.fps).put("container",selection.output)
        val activity=instrument.startActivitySync(Intent(app,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val control=TransferControl()
        try {
            withContext(Dispatchers.Main) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                PerformanceCapture.start(activity)
            }
            report.put("stage","validation_started")
            File(folder,"result.json").writeText(report.toString(2))
            val started=SystemClock.elapsedRealtime()
            val cpuStarted=android.os.Process.getElapsedCpuTime()
            Processor().resumeValidation(copied,selection,control,selection.duration,
                copied)
            report.put("validation_ms",SystemClock.elapsedRealtime()-started)
                .put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStarted)
                .put("stage","validation_completed")
            assertEquals(sourceHash,sha256(copied))
            assertEquals(sourceHash,originalInput().use(::sha256))
            assertEquals(original,app.repository.dao.get(original.id))
            report.put("passed",true)
        } catch(error:Throwable) {
            report.put("passed",false).put("failure_type",error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    File(folder,"result.json").writeText(report.toString(2))
                    File(folder,"diagnostics.jsonl").bufferedWriter().use {
                        DiagnosticsExport().write(NetworkDiagnostics.exportSnapshot(),it)
                    }
                } finally {withContext(Dispatchers.Main) {PerformanceCapture.stop();activity.finish()}}
            }
        }
    }

    private fun evidenceDirectory():File {
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return File(app.getExternalFilesDir(null),"device-reliability/$run").also {check(it.mkdirs())}
    }

    @Test fun processHashMatchedVideoAndAudioThroughActualProcessor() = runBlocking<Unit> {
        assertTrue("Pause other jobs before controlled measurement",app.repository.downloads.first().all {
            it.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")
        })
        val fixtures=File(app.getExternalFilesDir(null),"controlled-fixtures")
        val manifest=JSONObject(File(fixtures,"device-fixtures.json").readText())
        val name=requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureName"))
        require(name.matches(Regex("(1080p|4k)-(vp9|av1)\\.(webm|mp4)")))
        val videos=manifest.getJSONArray("video")
        val metadata=(0 until videos.length()).map {videos.getJSONObject(it)}.single {it.getString("file")==name}
        val audioMetadata=manifest.getJSONObject("audioByDuration").getJSONObject(metadata.getInt("duration").toString())
        val audioName=audioMetadata.getString("file")
        require(audioName.matches(Regex("shared-audio(-10s)?\\.m4a")))
        val originalVideo=File(fixtures,name);val originalAudio=File(fixtures,audioName)
        assertEquals(metadata.getString("sha256"),sha256(originalVideo))
        assertEquals(audioMetadata.getString("sha256"),sha256(originalAudio))
        val folder=evidenceDirectory()
        val video=originalVideo.copyTo(File(folder,"primary.${originalVideo.extension}"))
        val audio=originalAudio.copyTo(File(folder,"secondary.m4a"))
        assertEquals(metadata.getString("sha256"),sha256(video))
        assertEquals(audioMetadata.getString("sha256"),sha256(audio))
        val videoInfo=requireNotNull(FFprobeKit.getMediaInformation(video.path).mediaInformation).streams.single {it.type=="video"}
        assertEquals(metadata.getInt("width"),videoInfo.width.toInt())
        assertEquals(metadata.getInt("height"),videoInfo.height.toInt())
        assertEquals(metadata.getString("codec"),videoInfo.codec)
        fun format(file:File,videoTrack:Boolean)=Format.parse(JSONObject().put("format_id",if(videoTrack) "controlled-video" else "controlled-audio")
            .put("url","https://fixture.invalid/not-requested").put("ext",file.extension)
            .put("vcodec",if(videoTrack) videoInfo.codec else "none").put("acodec",if(videoTrack) "none" else "aac")
            .put("width",if(videoTrack) metadata.getInt("width") else null)
            .put("height",if(videoTrack) metadata.getInt("height") else null)
            .put("fps",if(videoTrack) metadata.getInt("fps") else null))
        val selection=Selection(format(video,true),format(audio,false),output="mp4",
            duration=metadata.getDouble("duration"),embedMetadata=false)
        val output=File(folder,"output.mp4")
        val report=JSONObject().put("scope","Hash-matched local inputs through actual Processor mux and validation; NOT network acquisition, queue ownership or publication")
            .put("video_sha256",metadata.getString("sha256")).put("audio_sha256",audioMetadata.getString("sha256"))
            .put("width",videoInfo.width).put("height",videoInfo.height).put("fps",metadata.getInt("fps"))
            .put("expected_frames",metadata.getInt("frames")).put("codec",videoInfo.codec).put("container","mp4")
        val activity=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withContext(Dispatchers.Main) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                PerformanceCapture.start(activity)
            }
            report.put("screen_interactive",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
                .put("keyguard_locked",app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                .put("stage","processing_started")
            File(folder,"result.json").writeText(report.toString(2))
            val started=SystemClock.elapsedRealtime();val cpuStarted=android.os.Process.getElapsedCpuTime()
            Processor().process(video,audio,output,selection,TransferControl(),selection.duration)
            report.put("processor_wall_ms",SystemClock.elapsedRealtime()-started)
                .put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStarted)
                .put("output_sha256",sha256(output)).put("output_bytes",output.length())
                .put("stage","processing_completed")
            assertEquals(metadata.getString("sha256"),sha256(video))
            assertEquals(audioMetadata.getString("sha256"),sha256(audio))
            report.put("passed",true)
        } catch(error:Throwable) {
            report.put("passed",false).put("failure_type",error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    File(folder,"result.json").writeText(report.toString(2))
                    File(folder,"diagnostics.jsonl").bufferedWriter().use {DiagnosticsExport().write(NetworkDiagnostics.exportSnapshot(),it)}
                } finally {withContext(Dispatchers.Main) {PerformanceCapture.stop();activity.finish()}}
            }
        }
    }

    private fun sha256(file:File):String=file.inputStream().use(::sha256)
    private fun sha256(input:java.io.InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(1024*1024)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
    }
}
