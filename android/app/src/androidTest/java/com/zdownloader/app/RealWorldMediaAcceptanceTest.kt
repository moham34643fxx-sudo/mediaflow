package com.zdownloader.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in public-source acceptance. No radio, account, destination, or notification changes. */
class RealWorldMediaAcceptanceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val args get()=InstrumentationRegistry.getArguments()
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}

    @Test fun publicMediaSurvivesUiClosureAndPublishesTheRequestedType()=runBlocking<Unit> {
        val url=requireNotNull(args.getString("probeSource"))
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val kind=requireNotNull(args.getString("mediaKind"));require(kind in listOf("video","audio","image"))
        val folder=File(app.getExternalFilesDir(null),"real-world/$run").also {check(it.mkdirs())}
        val report=JSONObject().put("source",url).put("kind",kind).put("passed",false)
        val power=app.getSystemService(PowerManager::class.java)
        val locked=args.getString("lockScreen")=="true"
        val limit=64L*1024*1024
        var activity:MainActivity?=null
        var id:String?=null
        var phase="PREFLIGHT"
        val proofId="real-world-proof-${UUID.randomUUID()}"
        try {
            val preExisting=app.repository.downloads.first()
            assertTrue("Unrelated active requests must not be disturbed",preExisting
                .none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            assertFalse("Unlock the phone before starting the live case",app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val began=SystemClock.elapsedRealtime()
            phase="DISCOVERY"
            val enrichSizes=args.getString("enrichSizes")=="true"
            report.put("optional_size_enrichment",enrichSizes)
            val media=withTimeout(100_000) {app.extractor.analyze(url,forceRefresh=true,enrichOptional=enrichSizes)}
            report.put("analysis_ms",SystemClock.elapsedRealtime()-began).put("duration",media.duration)
                .put("source_name",media.source).put("format_count",media.formats.size).put("source_catalog",catalog(media))
            val selection=selection(media,kind,limit)
            report.put("selection_mode",if(args.getString("sourceId")!=null) "exact_source_id" else "verified_quality_cap")
                .put("advertised_dimensions_verified",selection.primary.dimensionsVerified)
            report.put("video_id",selection.primary.id).put("audio_id",selection.secondary?.id)
                .put("width",selection.primary.width).put("height",selection.primary.height)
                .put("video_codec",selection.primary.videoCodec).put("audio_codec",selection.secondary?.audioCodec ?: selection.primary.audioCodec)
                .put("output",selection.output).put("estimated_transfer_bytes",selection.transferSize?.bytes)
                .put("merge_expected",selection.secondary!=null).put("audio_conversion_expected",selection.transcodeAudio)
            val opened=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=opened
            phase="QUEUE"
            assertFalse("Do not reuse an existing source request",app.repository.duplicateReview(media).found)
            val job=app.repository.create(media,selection,"Real-world QA $run",allowCompleted=false)
            check(preExisting.none {it.id==job}) {"Repository returned a pre-existing request; no cleanup is authorized"}
            check(app.repository.dao.get(job)?.title=="Real-world QA $run")
            id=job
            report.put("request_id",job)
            app.repository.prepareForSubmission(job)
            val queueBegan=SystemClock.elapsedRealtime()
            val receipt=app.scheduler.startFromUser(job)
            report.put("runner",receipt.mechanism).put("runner_ready",receipt.runnerReady)
            instrument.runOnMainSync {opened.finish()}
            report.put("ui_closed",opened.isFinishing||opened.isDestroyed)
            if(locked) {
                shell("input keyevent KEYCODE_SLEEP")
                withTimeout(3_000) {while(power.isInteractive) delay(50)}
                report.put("screen_off_confirmed",true).put("screen_off_at_wall",System.currentTimeMillis())
            }
            val observations=JSONArray();var previous="";var maxBytes=0L;var peakSpeed=0L
            report.put("states",observations)
            var screenOffThroughout=locked&&!power.isInteractive
            var keyguardObserved=false
            withTimeout(480_000) {
                while(true) {
                    val row=requireNotNull(app.repository.dao.get(job))
                    screenOffThroughout=screenOffThroughout&&!power.isInteractive
                    val keyguard=app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                    keyguardObserved=keyguardObserved||keyguard
                    maxBytes=maxOf(maxBytes,row.bytes);peakSpeed=maxOf(peakSpeed,row.speed)
                    assertTrue("64 MiB transfer limit exceeded",row.bytes<=limit)
                    if(row.status!=previous) {
                        observations.put(JSONObject().put("state",row.status).put("ms",SystemClock.elapsedRealtime()-queueBegan)
                            .put("interactive",power.isInteractive).put("keyguard_locked",keyguard).put("bytes",row.bytes))
                        previous=row.status
                    }
                    if(row.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")) break
                    delay(75)
                }
            }
            val row=requireNotNull(app.repository.dao.get(job))
            report.put("queue_ms",SystemClock.elapsedRealtime()-queueBegan).put("status",row.status).put("queue_error",row.error)
                .put("states",observations).put("max_progress_bytes",maxBytes).put("peak_reported_bytes_per_second",peakSpeed)
                .put("interactive_at_completion",power.isInteractive).put("screen_off_requested",locked)
                .put("screen_off_throughout_observation",screenOffThroughout).put("keyguard_lock_observed",keyguardObserved)
            assertEquals(row.error,"COMPLETED",row.status)
            assertEquals("VALIDATED",row.checkpoint)
            val events=app.repository.dao.events(job)
            report.put("events",JSONArray(events.map {JSONObject().put("state",it.state).put("time",it.time)}))
            phase="PUBLISHED_OUTPUT"
            val uri=Uri.parse(requireNotNull(row.uri))
            app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.MIME_TYPE),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals(row.outputSize,it.getLong(1))
                report.put("stored_mime",it.getString(2))
            }
            val collection=when {
                selection.audioOnly&&selection.output=="webm"->"/downloads/"
                kind=="image"->"/images/media/"
                kind=="audio"->"/audio/media/"
                else->"/video/media/"
            }
            assertTrue("Wrong MediaStore collection",uri.toString().contains(collection))
            val requestedMime=app.storage.mime(selection.output,selection.audioOnly)
            val providerMime=app.contentResolver.getType(uri)
            report.put("requested_mime",requestedMime).put("provider_mime",providerMime)
            val proof=File(app.storage.temporary(proofId),"published.${selection.output}")
            app.contentResolver.openInputStream(uri)!!.use {input->proof.outputStream().use {input.copyTo(it)}}
            assertEquals(row.outputSize,proof.length());assertTrue(proof.length()>0)
            report.put("published_bytes",proof.length()).put("is_pending",0).put("mime",app.contentResolver.getType(uri))
            if(kind=="image") verifyImage(proof,selection,report) else verifyAv(proof,uri,selection,report)
            assertPublishedMime(selection,requestedMime,providerMime)
            val verifiedMime=app.contentResolver.getType(uri)
            report.put("verified_provider_mime",verifiedMime)
            assertPublishedMime(selection,requestedMime,verifiedMime)
            report.put("pipeline_profile",profile(job)).put("source_to_verified_output_ms",SystemClock.elapsedRealtime()-began)
                .put("passed",true)
        } catch(error:Throwable) {
            report.put("failure_phase",phase).put("failure_class",error.javaClass.simpleName)
                .put("failure_code",(error as? MediaException)?.code?.name).put("failure_message",error.message?.take(500))
            throw error
        } finally {withContext(NonCancellable) {
            id?.let {job->
                check(app.repository.dao.get(job)?.title=="Real-world QA $run")
                report.put("diagnostics",diagnostics(job))
                app.coordinator.stopAndAwait(job)
                val row=app.repository.dao.get(job)
                row?.let {report.put("final_request_state",JSONObject().put("status",it.status).put("error",it.error)
                    .put("checkpoint",it.checkpoint).put("bytes",it.bytes).put("next_attempt",it.nextAttempt))}
                report.put("events",JSONArray(app.repository.dao.events(job).map {JSONObject().put("state",it.state).put("time",it.time)}))
                if(row?.status=="COMPLETED") app.storage.deleteFile(row) else app.repository.cancel(job)
                app.coordinator.stopAndAwait(job);app.repository.remove(job)
            }
            app.storage.removeTemporary(proofId)
            instrument.runOnMainSync {activity?.finish()}
            if(locked) {shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")}
            File(folder,"result.json").writeText(report.toString(2))
        }}
    }

    private fun selection(media:Media,kind:String,limit:Long):Selection {
        val output=args.getString("output") ?: "original"
        val codec=args.getString("codec")
        val sourceExt=args.getString("sourceExt")
        val sourceId=args.getString("sourceId")
        val target=args.getString("targetHeight")?.toInt() ?: 720
        val candidates=when(kind) {
            "image"->media.imageOptions().filter {sourceId==null||it.id==sourceId}.map {Selection.image(media,it)}
            "audio"->media.audioOptions().filter {
                (codec==null||codecFamily(it.audioCodec)==codec)&&(sourceExt==null||it.ext==sourceExt&&it.streamOnly)
            }.sortedBy {if(it.streamOnly) 0 else 1}.map {Selection.audio(media,output,it)}
            else->media.formats.filter {it.video&&(codec==null||codecFamily(it.videoCodec)==codec)&&
                (if(sourceId!=null) it.id==sourceId else verifiedQualityEdge(it)?.let {edge->edge<=target}==true)}
                .sortedWith(compareByDescending<Format> {verifiedQualityEdge(it)}.thenBy {it.size(media.duration)?.bytes ?: Long.MAX_VALUE})
                .mapNotNull {runCatching {Selection.video(media,it,output.takeUnless {it=="original"})}.getOrNull()}
        }
        return candidates.firstOrNull {candidate->
            candidate.transferSize?.bytes?.let {bytes->bytes in 1..limit}==true||
                (candidate.imageOnly&&candidate.transferSize==null&&candidate.primary.let {
                    (it.width ?: 0).toLong()*(it.height ?: 0) in 1..12_000_000
                })
        }
            ?: error("No source-backed $kind selection matching the requested codec/container/cap has size evidence within 64 MiB; no unbounded transfer attempted")
    }

    private fun verifyImage(proof:File,selection:Selection,report:JSONObject) {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(proof.path,bounds)
        assertEquals(selection.primary.width,bounds.outWidth);assertEquals(selection.primary.height,bounds.outHeight)
        val options=BitmapFactory.Options().apply {inSampleSize=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/512)}
        val bitmap=BitmapFactory.decodeFile(proof.path,options)
        assertNotNull("Published image cannot decode",bitmap);bitmap?.recycle()
        assertTrue(selection.imageOnly);assertFalse(selection.primary.video)
        report.put("decoded_image",true).put("actual_width",bounds.outWidth).put("actual_height",bounds.outHeight)
    }

    private suspend fun verifyAv(proof:File,uri:Uri,selection:Selection,report:JSONObject) {
        val info=requireNotNull(FFprobeKit.getMediaInformation(proof.path).mediaInformation)
        val video=info.streams.filter {it.type=="video"&&it.allProperties.optJSONObject("disposition")?.optInt("attached_pic",0)!=1}
        val audio=info.streams.filter {it.type=="audio"}
        val expectsAudio=selection.audioOnly||selection.primary.audio||selection.secondary?.audio==true
        assertEquals(expectsAudio,audio.isNotEmpty())
        assertEquals(!selection.audioOnly,video.isNotEmpty())
        report.put(if(selection.output=="aac") "estimated_probe_duration" else "actual_duration",info.duration).put("actual_video_codec",video.firstOrNull()?.codec)
            .put("actual_width",video.firstOrNull()?.width).put("actual_height",video.firstOrNull()?.height)
            .put("actual_audio_codec",audio.firstOrNull()?.codec).put("attached_artwork_count",info.streams.count {
                it.type=="video"&&it.allProperties.optJSONObject("disposition")?.optInt("attached_pic",0)==1
            })
        assertTrue(info.duration.toDouble()>0)
        verifyFullDecode(proof,video.firstOrNull()?.codec)
        report.put("independent_full_decode",true)
        if(selection.audioOnly) {
            if(selection.output=="aac") assertEquals("aac",info.allProperties.getJSONObject("format").getString("format_name"))
            val expected=if(selection.convertAudio) when(selection.output) {"m4a","aac"->"aac";else->selection.output} else codecFamily(selection.primary.audioCodec)
            assertEquals(expected,codecFamily(audio.single().codec))
            val player=MediaPlayer()
            try {
                player.setDataSource(app,uri);player.setVolume(0f,0f);player.prepare();player.start()
                withTimeout(8_000) {while(player.currentPosition<=0) delay(50)}
                report.put("muted_playback_position_ms",player.currentPosition)
            } finally {player.release()}
        } else {
            assertEquals(selection.primary.width,video.single().width.toInt());assertEquals(selection.primary.height,video.single().height.toInt())
            assertEquals(if(selection.transcodeVideo) "h264" else codecFamily(selection.primary.videoCodec),codecFamily(video.single().codec))
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(proof.path)
                val frame=retriever.getFrameAtTime(1_000_000)
                if(frame==null&&args.getString("playbackProbe")=="true") report.put("android_playback_probe",androidPlaybackProbe(uri))
                assertNotNull("Android thumbnail extraction failed",frame);frame?.recycle()
                report.put("decoded_video_frame",true)
            } finally {retriever.release()}
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun androidPlaybackProbe(uri:Uri):JSONObject {
        val firstFrame=CompletableDeferred<Unit>();val failure=CompletableDeferred<String>()
        val texture=android.graphics.SurfaceTexture(0)
        val surface=android.view.Surface(texture)
        val player=withContext(Dispatchers.Main) {
            androidx.media3.exoplayer.ExoPlayer.Builder(app).build().apply {
                volume=0f;setVideoSurface(surface)
                addListener(object:androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() {firstFrame.complete(Unit)}
                    override fun onPlayerError(error:androidx.media3.common.PlaybackException) {failure.complete(error.errorCodeName)}
                })
                setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));prepare();play()
            }
        }
        return try {
            val outcome=withTimeoutOrNull(15_000) {
                kotlinx.coroutines.selects.select<String> {
                    firstFrame.onAwait {"rendered_first_frame"}
                    failure.onAwait {it}
                }
            } ?: "timeout"
            JSONObject().put("outcome",outcome).put("rendered_first_frame",firstFrame.isCompleted)
                .put("position_ms",withContext(Dispatchers.Main) {player.currentPosition})
        } finally {withContext(NonCancellable+Dispatchers.Main) {player.release();surface.release();texture.release()}}
    }

    private fun assertPublishedMime(selection:Selection,requested:String,actual:String?) {
        // Android's scanner labels raw ADTS AAC with this audio-only alias.
        // This does not permit a video MIME for audio, nor bypass container/codec/full-decode checks.
        val adtsAlias=selection.audioOnly&&selection.output=="aac"&&requested=="audio/aac"&&actual=="audio/aac-adts"
        assertTrue("Provider MIME $actual differs from requested $requested",actual==requested||adtsAlias)
    }

    private fun verifyFullDecode(proof:File,videoCodec:String?) {
        val command=mutableListOf("-nostdin","-v","error","-xerror","-err_detect","explode","-hwaccel","none")
        if(videoCodec=="av1") command+=listOf("-c:v","av1_mediacodec","-ndk_codec","1")
        command+=listOf("-protocol_whitelist","file,pipe","-i",proof.path,"-map","0:v:0?","-map","0:a:0?",
            "-max_error_rate","0","-f","null","-")
        val decoded=FFmpegKit.executeWithArguments(command.toTypedArray())
        assertTrue("Published output failed independent strict decode (exit ${decoded.returnCode?.value})",ReturnCode.isSuccess(decoded.returnCode))
    }

    private fun catalog(media:Media):JSONArray=JSONArray(media.formats.take(64).map {format->
        JSONObject().put("id",format.id).put("ext",format.ext).put("width",format.width).put("height",format.height)
            .put("verified_edge",verifiedQualityEdge(format)).put("video_codec",format.videoCodec).put("audio_codec",format.audioCodec)
            .put("bytes",format.bytes).put("estimated_bytes",format.size(media.duration)?.bytes)
            .put("bitrate",format.bitrate).put("video_bitrate",format.videoBitrate).put("audio_bitrate",format.audioBitrate)
            .put("protocol",format.protocol).put("image",format.image)
    })

    private suspend fun profile(job:String):JSONObject? {
        val allowed=listOf("video_bytes","audio_bytes","output_bytes","video_download_ms","audio_download_ms","post_download_total_ms","end_to_end_ms","stream_copy","transcoded")
        repeat(20) {
            for(name in listOf("network-diagnostics.jsonl","network-diagnostics.previous.jsonl")) {
                val file=File(app.filesDir,name)
                if(!file.isFile) continue
                val matched=file.readLines().takeLast(200).mapNotNull {runCatching {JSONObject(it)}.getOrNull()}
                    .lastOrNull {it.optString("operation")==job&&it.optString("stage")=="video_pipeline_profiling"}
                val details=matched?.optJSONObject("details")
                if(details!=null) return JSONObject().apply {allowed.forEach {key->if(details.has(key)) put(key,details.get(key))}}
            }
            delay(100)
        }
        return null
    }

    private fun diagnostics(job:String):JSONArray {
        val stages=setOf("video_mux_timing","video_validation_timing","validation_rejected_audio_timing",
            "processing_failure","validated_mp4_full_av1","av1_validation_plan","video_pipeline_profiling","validated_aac_decoded_duration")
        val result=JSONArray()
        for(name in listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl")) {
            val file=File(app.filesDir,name)
            if(!file.isFile) continue
            file.useLines {lines->lines.forEach {line->
                val entry=runCatching {JSONObject(line)}.getOrNull()
                if(entry?.optString("operation")==job&&entry.optString("stage") in stages)
                    result.put(JSONObject().put("stage",entry.getString("stage")).put("code",entry.optString("code"))
                        .put("details",entry.optJSONObject("details")))
            }}
        }
        return result
    }
}
