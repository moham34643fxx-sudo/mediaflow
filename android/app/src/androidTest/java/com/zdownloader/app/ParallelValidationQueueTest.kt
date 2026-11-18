@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.background.DownloadNotifications
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.UUID

/** Real coordinator/mux/validation/publication. Only HTTPS transport reads retained local media. */
class ParallelValidationQueueTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val args get()=InstrumentationRegistry.getArguments()

    @Test fun actualQueueCertifiesCopiedOutputWithoutRepeatingVideoDecode()=runBlocking<Unit> {
        check(!app.coordinator.running)
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val base=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb")
        val video=File(base,"video.webm");val audio=File(base,"audio.m4a")
        assertEquals("568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83",hash(video))
        assertEquals("be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703",hash(audio))
        val report=JSONObject().put("passed",false).put("actual_production_queue",true)
            .put("transport","Local pinned real media served through an HTTP boundary fixture; no Internet download")
        val repeats=args.getString("parallelLocalRepeats","1")!!.toInt()
        require(repeats in setOf(1,6))
        val longRoot=if(repeats==6) File(app.cacheDir,"parallel-long-${UUID.randomUUID()}").apply {check(mkdirs())} else null
        try {
            val testedVideo=longRoot?.let {repeatLocally(video,File(it,"video.webm"),repeats)} ?: video
            val testedAudio=longRoot?.let {repeatLocally(audio,File(it,"audio.m4a"),repeats)} ?: audio
            report.put("local_repeats",repeats).put("video_bytes",testedVideo.length()).put("audio_bytes",testedAudio.length())
                .put("duration_s",FFprobeKit.getMediaInformation(testedVideo.path).mediaInformation!!.duration.toDouble())
            if(args.getString("parallelPrepareOnly")=="true") {
                check(longRoot!=null)
                report.put("actual_production_queue",false).put("preparation_only",true)
                val videoInventory=WebmPacketInventory.write(testedVideo,File(longRoot,"video-packets.bin")) {}
                val audioInventory=Mp4PacketInventory.write(testedAudio,File(longRoot,"audio-packets.bin"),"audio") {}
                val originalCatalog=File(longRoot,"original-video-packets.bin")
                val originalInventory=WebmPacketInventory.write(video,originalCatalog) {}
                assertEquals(originalInventory.packets*repeats,videoInventory.packets)
                assertEquals(originalInventory.end*repeats,videoInventory.end)
                assertRepeatedVideo(originalCatalog,File(longRoot,"video-packets.bin"),originalInventory,repeats)
                report.put("video_packets",videoInventory.packets).put("audio_packets",audioInventory.packets)
                    .put("original_video_packets_and_durations_preserved",true)
                    .put("passed",true)
                return@runBlocking
            }
            val baseline=if(args.getString("parallelOnly")=="true") null else runQueue(testedVideo,testedAudio,false)
            baseline?.let {report.put("baseline",it)}
            val parallel=runQueue(testedVideo,testedAudio,true)
            report.put("parallel",parallel)
            baseline?.let {assertEquals(it.getString("published_sha256"),parallel.getString("published_sha256"))}
            val stages=parallel.getJSONArray("diagnostics")
            assertTrue("Queue did not actually start the source decoders",hasStage(stages,"source_overlap_started"))
            assertTrue("No final-output certificate; full-decode fallback is not a successful fast path",hasStage(stages,"copy_output_proof_accepted"))
            assertFalse("The output was still fully decoded after certification",hasStage(stages,"validated_mp4"))
            report.put("passed",true)
        } catch(error:Throwable) {
            report.put("failure",error.javaClass.name+":"+error.message)
                .put("failure_stack",error.stackTrace.take(12).joinToString("\n"))
            throw error
        } finally {
            longRoot?.deleteRecursively()
            val run=args.getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"parallel-queue.json").writeText(report.toString(2))
            instrument.sendStatus(2,android.os.Bundle().apply {putString("parallel_queue_summary",report.toString())})
        }
    }

    private suspend fun runQueue(video:File,audio:File,overlap:Boolean):JSONObject {
        val token=UUID.randomUUID().toString()
        val root=File(app.cacheDir,"parallel-queue-$token").apply {check(mkdirs())}
        val context=object:ContextWrapper(app) {override fun getFilesDir()=root}
        val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val dao=database.downloads()
        val storage=StorageRepository(context,dao)
        val settings=SettingsRepository(app)
        val processor=Processor()
        val notifications=DownloadNotifications(app,settings,dao)
        val client=transport(video,audio)
        val transfer=Transfer(app.extractor,client,processor)
        val coordinator=QueueCoordinator(context,dao,settings,transfer,processor,storage,notifications,app.extractor)
        val selection=selection(video,audio)
        val row=DownloadEntity(token,"https://parallel-fixture.example/watch/$token","Parallel validation QA","QA",null,
            selection.duration,selection.json(),"Parallel validation QA $token.mp4",null,token)
        var published:Uri?=null
        try {
            if(!overlap) File(storage.temporary(token),"primary.webm.resume").writeText("{}")
            dao.insert(row)
            val began=SystemClock.elapsedRealtime()
            withTimeout(12*60_000) {coordinator.run()}
            val elapsed=SystemClock.elapsedRealtime()-began
            val completed=requireNotNull(dao.get(token))
            assertEquals(completed.error,"COMPLETED",completed.status)
            published=Uri.parse(requireNotNull(completed.uri))
            val digest=app.contentResolver.openInputStream(published)!!.use {input->
                val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
                while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
                digest.digest().hex()
            }
            val records=awaitDiagnostics(token)
            return JSONObject().put("elapsed_ms",elapsed).put("output_bytes",completed.outputSize)
                .put("published_sha256",digest).put("diagnostics",JSONArray(records))
        } finally {withContext(NonCancellable) {
            val finalRow=dao.get(token)
            val run=args.getString("evidenceRun","manual")!!
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            val diagnostics=diagnostics(token)
            File(evidence,"queue-${if(overlap) "parallel" else "baseline"}-terminal.json").writeText(JSONObject()
                .put("state",finalRow?.status).put("error",finalRow?.error).put("checkpoint",finalRow?.checkpoint)
                .put("bytes",finalRow?.bytes).put("native_busy",NativeWork.busy(root)).put("diagnostics",JSONArray(diagnostics)).toString(2))
            coordinator.stopAndAwait(token)
            published?.let {app.contentResolver.delete(it,null,null)}
            storage.cleanupPending(token)
            notifications.dismiss(token)
            database.close()
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            if(!NativeWork.busy(root)) root.deleteRecursively()
        }}
    }

    private fun transport(video:File,audio:File):OkHttpClient=OkHttpClient.Builder().addInterceptor {chain->
        val file=when(chain.request().url.encodedPath) {"/video.webm"->video;"/audio.m4a"->audio;else->error("Unexpected fixture URL")}
        val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(chain.request().header("Range").orEmpty())
        val start=range?.groupValues?.get(1)?.toLong() ?: 0
        val end=minOf(range?.groupValues?.get(2)?.toLong() ?: file.length()-1,file.length()-1)
        val input=file.inputStream();input.channel.position(start)
        val source=object:ForwardingSource(input.source()) {
            private var remaining=end-start+1
            override fun read(sink:Buffer,byteCount:Long):Long {
                if(chain.call().isCanceled()) throw java.io.IOException("Fixture transport cancelled")
                if(remaining<=0) return -1
                Thread.sleep(8)
                val read=super.read(sink,minOf(byteCount,remaining,128*1024))
                if(read>0) remaining-=read
                return read
            }
        }.buffer()
        val body=object:ResponseBody() {
            override fun contentType():MediaType?=null
            override fun contentLength()=end-start+1
            override fun source()=source
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(range==null) 200 else 206)
            .message("Local pinned transport").header("Content-Range","bytes $start-$end/${file.length()}")
            .header("ETag","\"pinned-fixture\"").body(body).build()
    }.build()

    private fun repeatLocally(source:File,output:File,repeats:Int):File {
        if(output.extension=="webm") return RetainedWebmRepeater(source).use {it.repeat(output,repeats)}
        val command=mutableListOf("-nostdin","-v","error","-stream_loop",(repeats-1).toString(),"-i",source.path,
            "-map","0","-c","copy")
        if(output.extension=="m4a") command+=listOf("-movflags","+frag_keyframe+empty_moov+default_base_moof")
        command+=output.path
        assertTrue("Local stream-copy preparation failed",ReturnCode.isSuccess(FFmpegKit.executeWithArguments(command.toTypedArray()).returnCode))
        assertTrue("Long-video preparation did not exceed the old size limit",output.length()>64L*1024*1024)
        return output
    }

    private fun assertRepeatedVideo(original:File,repeated:File,inventory:ContainerInventory,repeats:Int) {
        DataInputStream(repeated.inputStream().buffered()).use {copied->
            for(repetition in 0 until repeats) DataInputStream(original.inputStream().buffered()).use {source->
                for(index in 0 until inventory.packets) {
                    assertEquals(source.readLong()+repetition*inventory.end,copied.readLong())
                    val expected=ByteArray(49).also(source::readFully)
                    val actual=ByteArray(49).also(copied::readFully)
                    assertArrayEquals("Repeated packet fields/hash $repetition/$index",expected,actual)
                }
                assertEquals(-1,source.read())
            }
            assertEquals(-1,copied.read())
        }
    }

    private fun selection(video:File,audio:File):Selection {
        val videoInfo=requireNotNull(FFprobeKit.getMediaInformation(video.path).mediaInformation)
        val stream=videoInfo.streams.single {it.type=="video"}
        fun format(file:File,videoTrack:Boolean)=Format.parse(JSONObject().put("format_id",if(videoTrack) "fixture-video" else "fixture-audio")
            .put("url","https://parallel-fixture.example/${if(videoTrack) "video.webm" else "audio.m4a"}")
            .put("ext",if(videoTrack) "webm" else "m4a").put("protocol","https").put("filesize",file.length())
            .put("vcodec",if(videoTrack) "vp9" else "none").put("acodec",if(videoTrack) "none" else "aac")
            .put("width",if(videoTrack) stream.width else null).put("height",if(videoTrack) stream.height else null))
        return Selection(format(video,true),format(audio,false),output="mp4",duration=videoInfo.duration.toDouble(),embedMetadata=false)
    }

    private suspend fun awaitDiagnostics(id:String):List<JSONObject> {
        var records=emptyList<JSONObject>()
        withTimeout(10_000) {
            while(true) {
                records=diagnostics(id)
                if(records.any {it.optString("stage")=="video_pipeline_profiling"}) break
                delay(50)
            }
        }
        return records
    }
    private fun diagnostics(id:String)=listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl").flatMap {name->
        File(app.filesDir,name).takeIf {it.isFile}?.readLines().orEmpty()
    }.mapNotNull {runCatching {JSONObject(it)}.getOrNull()}.filter {it.optString("operation")==id}
    private fun hasStage(records:JSONArray,stage:String)=(0 until records.length()).any {records.getJSONObject(it).optString("stage")==stage}
    private fun hash(file:File):String=file.inputStream().use {input->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        digest.digest().hex()
    }
    private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
}
