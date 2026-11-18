package com.zdownloader.app.engine

import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class Processor @Inject constructor() {
    private val lock = Mutex()
    // Unlike a coroutine mutex, this permit remains owned by an outstanding
    // native session after cancellation until its actual completion callback.
    private val nativeSlot=NativeAdmission()
    internal fun beginCopyVerification(context:android.content.Context,inputs:CopyVerification.Inputs):CopyVerification? {
        inputs.control.check()
        if(!CopyVerification.eligible(context,inputs)) return null
        val admission=nativeSlot.tryAcquireOptional() ?: return null
        return CopyVerification(context,inputs,admission)
    }
    internal suspend fun renderSlideshow(manifest:File,output:File,plan:SlideshowPlan,control:TransferControl) {
        val duration=String.format(java.util.Locale.ROOT,"%.9f",plan.duration)
        execute(listOf("-y","-v","error","-protocol_whitelist","file,pipe","-f","concat","-safe","1","-i",manifest.path,
            "-vf","scale=${plan.width}:${plan.height}:force_original_aspect_ratio=decrease,pad=${plan.width}:${plan.height}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p",
            "-t",duration,"-an","-c:v","h264_mediacodec","-b:v","2000000","-movflags","+faststart",output.path),control,ErrorCode.ENCODER_UNAVAILABLE)
        validate(output,false,false,plan.height,plan.duration,"mp4",control,expectedWidth=plan.width,expectedFps=30.0,expectedVideoCodec="h264")
    }
    suspend fun resumeValidation(output:File,selection:Selection,control:TransferControl,duration:Double?,primary:File) = withControlCancellation(control) {
        lock.withLock {validateSelection(output,selection,control,duration,primary)}
    }
    suspend fun process(primary: File, secondary: File?, output: File, selection: Selection, control: TransferControl, expectedDuration: Double? = null, title: String? = null,subtitle:File?=null,thumbnail:File?=null) = withControlCancellation(control) {
        val videoQueuedAt=if(!selection.audioOnly) android.os.SystemClock.elapsedRealtime() else null
        lock.withLock {
        val videoStartedAt=videoQueuedAt?.let {android.os.SystemClock.elapsedRealtime()}
        control.check()
        NativeWork.awaitIdle(primary.parentFile!!)
        control.onProcessingReady?.invoke()
        control.onProcessingReady=null
        if(selection.imageOnly) {
            ImageFinalizer.copy(primary,output,selection.primary,control)
            return@withLock
        }
        rejectOriginalImage(primary,control)
        secondary?.let {rejectOriginalImage(it,control)}
        if(selection.audioOnly && selection.output=="mp3" && selection.convertAudio) {
            mp3(primary,output,selection,control,expectedDuration,title,thumbnail)
            return@withLock
        }
        val args = mutableListOf("-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", primary.path)
        var copiedAudioTiming:AacCopyTiming?=null
        if (secondary != null) args += listOf("-protocol_whitelist", "file,pipe", "-i", secondary.path)
        if(subtitle!=null) args+=listOf("-protocol_whitelist","file,pipe","-i",subtitle.path)
        if (secondary != null) args += listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy")
        else if (selection.audioOnly) {
            args += listOf("-map", "0:a:0", "-vn")
            // Container/codec labels from extraction can be stale or incorrect.
            // A copy into M4A must be based on the bytes we actually downloaded.
            val sourceAudio = FFprobeKit.getMediaInformation(primary.path).mediaInformation?.streams
                ?.firstOrNull { it.type == "audio" } ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            val codec=sourceAudio.codec ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            if(!selection.convertAudio&&selection.output in listOf("m4a","mp4")&&codec=="aac")
                copiedAudioTiming=AacCopyTiming.fromStream(sourceAudio.allProperties)
            when(selection.output) {
                "m4a", "aac" -> args += when {
                    selection.convertAudio->listOf("-c:a","aac","-b:a","${selection.bitrate}k")
                    codec.startsWith("mp4a")||codec=="aac"||selection.output=="m4a"&&codec=="alac"->listOf("-c:a","copy")
                    // Stale AAC metadata must not prevent the requested M4A/AAC output from WebM audio.
                    codec in listOf("opus","vorbis")->listOf("-c:a","aac","-b:a","${selection.bitrate}k")
                    else->throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                }
                "flac" -> args += if(!selection.convertAudio && codec=="flac") listOf("-c:a","copy") else listOf("-c:a", "flac")
                "opus" -> args += if(!selection.convertAudio && codec.startsWith("opus")) listOf("-c:a","copy") else listOf("-c:a","opus","-strict","-2","-b:a","${selection.bitrate}k")
                "ogg", "webm" -> { if (selection.output == selection.primary.ext && selection.primary.streamOnly || selection.output=="ogg" && codec.startsWith("vorbis")) args += listOf("-c:a", "copy") else throw MediaException(ErrorCode.CONVERSION_FAILED) }
                else -> args += listOf("-c:a", "copy")
            }
        } else args += listOf("-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy")
        if(selection.videoOnly) args+=listOf("-an")
        if(subtitle!=null) args+=listOf("-map",if(secondary!=null) "2:s:0" else "1:s:0","-c:s",if(selection.output=="mp4") "mov_text" else "srt","-metadata:s:s:0","language=${selection.subtitle?.language}")
        // Inspect actual tracks, not the advertised extension, before deciding to remux.
        // Never silently substitute MPEG-4 Part 2 or mislabel output: if genuine H.264
        // encoding is required but unavailable, fail explicitly so UI can offer MKV/original.
        if(!selection.audioOnly && selection.output=="mp4") {
            val primaryInfo=FFprobeKit.getMediaInformation(primary.path).mediaInformation
            val video=primaryInfo?.streams?.firstOrNull {it.type=="video"}
                ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
            // A muxed source contains both streams; do not probe that same file twice.
            val audioInfo=if(secondary==null) primaryInfo else FFprobeKit.getMediaInformation(secondary.path).mediaInformation
            val audio=audioInfo?.streams?.firstOrNull {it.type=="audio"}
            if(!VideoContainerPolicy.canCopyMp4Video(video.codec)) {
                val width = (video.width as? Number)?.toInt() ?: 0
                val height = (video.height as? Number)?.toInt() ?: 0
                val rate=selection.primary.fps ?: video.allProperties.optString("avg_frame_rate").split('/').let {it.getOrNull(0)?.toDoubleOrNull()?.div(it.getOrNull(1)?.toDoubleOrNull()?.takeIf {n->n>0} ?: 1.0)} ?: 30.0
                val bitrate=((selection.primary.videoBitrate ?: selection.primary.bitrate ?: 2000.0)*1000).toInt().coerceAtLeast(500000)
                val inputPixels=video.allProperties.optString("pix_fmt")
                val hardware = width > 0 && height > 0 && inputPixels in listOf("yuv420p","yuvj420p","nv12","nv21") && android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any {it.equals("video/avc",true)} && runCatching {
                        val caps = info.getCapabilitiesForType("video/avc")
                        val v=caps.videoCapabilities
                        val format=android.media.MediaFormat.createVideoFormat("video/avc",width,height).apply {setInteger(android.media.MediaFormat.KEY_BIT_RATE,bitrate);setFloat(android.media.MediaFormat.KEY_FRAME_RATE,rate.toFloat());setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}
                        width % v.widthAlignment==0 && height % v.heightAlignment==0 && v.areSizeAndRateSupported(width,height,rate) && caps.isFormatSupported(format)
                    }.getOrDefault(false)
                }
                if(!hardware) throw MediaException(ErrorCode.ENCODER_UNAVAILABLE)
                args += listOf("-c:v","h264_mediacodec","-b:v",bitrate.toString(),"-pix_fmt","yuv420p")
            }
            if(audio!=null && audio.codec!="aac") args += listOf("-c:a","aac","-b:a","192k")
            else if(audio!=null&&secondary!=null&&!selection.videoOnly) copiedAudioTiming=AacCopyTiming.fromStream(audio.allProperties)
        }
        copiedAudioTiming?.let {
            args+=listOf("-bsf:a",it.bitstreamFilter)
            // A millisecond movie clock rounds edit-list endpoints even when track ticks are exact.
            if(selection.output in listOf("mp4","m4a")) args+=listOf("-movie_timescale",it.movieTimescale)
        }
        AudioCopyContract.persist(output,selection,copiedAudioTiming)
        if (selection.output in listOf("mp4","m4a")) args += listOf("-movflags", "+faststart")
        if(!selection.embedMetadata) args+=listOf("-map_metadata","-1")
        else {if(title != null) args += listOf("-metadata","title=${FileNames.sanitize(title)}");selection.creator?.takeIf {it.isNotBlank()}?.let {args+=listOf("-metadata","artist=${FileNames.sanitize(it)}")}}
        args += output.path
        val videoMuxAt=videoStartedAt?.let {android.os.SystemClock.elapsedRealtime()}
        execute(args,control,if(selection.audioOnly || selection.transcodeVideo || selection.transcodeAudio) ErrorCode.CONVERSION_FAILED else ErrorCode.MERGE_FAILED)
        val videoValidateAt=videoMuxAt?.let {android.os.SystemClock.elapsedRealtime()}
        if(videoQueuedAt!=null&&videoStartedAt!=null&&videoMuxAt!=null&&videoValidateAt!=null) NetworkDiagnostics.record("media","video_mux_timing",null,operation=output.parentFile?.name,
            details=org.json.JSONObject().put("queue_wait_ms",videoStartedAt-videoQueuedAt).put("inspection_ms",videoMuxAt-videoStartedAt)
                .put("mux_ms",videoValidateAt-videoMuxAt).put("video_copy",!args.contains("h264_mediacodec")).put("output_bytes",output.length()))
        if(videoValidateAt==null) validateSelection(output,selection,control,expectedDuration,primary)
        else {
            var validated=false
            try {validateSelection(output,selection,control,expectedDuration,primary);validated=true}
            finally {NetworkDiagnostics.record("media","video_validation_timing",null,operation=output.parentFile?.name,
                 details=org.json.JSONObject().put("validation_ms",android.os.SystemClock.elapsedRealtime()-videoValidateAt).put("validated",validated))}
        }
    } }
    suspend fun validateSelection(output:File,selection:Selection,control:TransferControl,expectedDuration:Double?,primary:File?=null) {
        if(selection.imageOnly) {ImageFinalizer.validate(output,selection.primary,control);return}
        val dimensionsDiverged = selection.primary.sourceQualityLabel != null &&
            selection.primary.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull() != verifiedQualityEdge(selection.primary)
        val dimensionsVerified = selection.primary.dimensionsVerified && !dimensionsDiverged
        validate(output, selection.audioOnly, !selection.videoOnly&&(!selection.audioOnly && selection.primary.audio || selection.secondary != null), if(selection.audioOnly||!dimensionsVerified) null else selection.primary.height, expectedDuration,selection.output,control,
            expectedWidth=if(selection.audioOnly||!dimensionsVerified) null else selection.primary.width,expectedFps=if(selection.audioOnly) null else selection.primary.fps,
            expectedVideoCodec=if(selection.audioOnly) null else if(selection.transcodeVideo) "h264" else codecFamily(selection.primary.videoCodec).takeIf {it.isNotBlank()},copySelection=selection)
        AudioCopyContract.expected(output,selection)?.let {timing->
            val actual=FFprobeKit.getMediaInformation(output.path).mediaInformation?.streams?.firstOrNull {it.type=="audio"}
            if(actual==null||!timing.matches(actual.allProperties)) rejectAudioTiming(output,timing,actual)
        }
        if(selection.subtitle!=null&&FFprobeKit.getMediaInformation(output.path).mediaInformation?.streams?.none {it.type=="subtitle"}!=false) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="subtitle")
        if(selection.videoOnly&&FFprobeKit.getMediaInformation(output.path).mediaInformation?.streams?.any {it.type=="audio"}==true) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="unexpected_audio")
        if(selection.audioOnly&&selection.output=="mp3"&&selection.convertAudio&&primary!=null) {
            val input=FFprobeKit.getMediaInformation(primary.path).mediaInformation.streams.first {it.type=="audio"}.allProperties
            val outputAudio=FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.first {it.type=="audio"}.allProperties
            val channels=input.optInt("channels",0).coerceAtMost(2)
            val rate=input.optString("sample_rate").toIntOrNull().let {if(it in listOf(44100,48000)) it else 44100}
            val bitrate=outputAudio.optString("bit_rate").toLongOrNull() ?: 0
            if(channels !in 1..2 || outputAudio.optInt("channels")!=channels || outputAudio.optString("sample_rate").toIntOrNull()!=rate || bitrate<=0 || selection.bitrate>0&&kotlin.math.abs(bitrate-selection.bitrate*1000L)>32000L) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED)
        }
    }
    private fun rejectAudioTiming(output:File,timing:AacCopyTiming,stream:StreamInformation?):Nothing {
        val observed=org.json.JSONObject()
        listOf("codec_name","sample_rate","time_base","start_pts","duration_ts").forEach {key->
            stream?.allProperties?.opt(key)?.let {observed.put(key,it)}
        }
        NetworkDiagnostics.record("media","validation_rejected_audio_timing",ErrorCode.OUTPUT_VALIDATION_FAILED,
            operation=output.parentFile?.name,details=org.json.JSONObject().put("expected",timing.json()).put("actual",observed))
        throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="audio_timing")
    }
    private suspend fun execute(args:List<String>,control:TransferControl,error:ErrorCode,onStatistics:((Statistics)->Unit)?=null):FFmpegSession = withControlCancellation(control) {
        control.check()
        val finished=CompletableDeferred<ReturnCode?>()
        val watchdog=NativeProgressWatchdog(android.os.SystemClock::elapsedRealtime,10*60_000L)
        val permit=nativeSlot.acquire()
        val directories = args.filter { File(it).isAbsolute && File(it).parentFile?.isDirectory == true }.map { File(it).parentFile!! }.distinctBy { it.absoluteFile.normalize().path }
        val leases = try {directories.map(NativeWork::acquire)} catch(e:Exception) {permit.release();throw e}
        val released=java.util.concurrent.atomic.AtomicBoolean(false)
        fun releaseAll() { if(released.compareAndSet(false,true)) {leases.forEach { runCatching { it.release() } };permit.release()} }
        val session = try { FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { completed ->
            NetworkDiagnostics.captureObservation {
                NetworkDiagnostics.record("media","ffmpeg_completed",null,operation=directories.firstOrNull()?.name,
                    details=org.json.JSONObject().put("return_code",completed.returnCode?.value))
            }
            finished.complete(completed.returnCode)
            releaseAll()
            if(control.session == completed.sessionId) control.session = null
        },null,StatisticsCallback {sample->
            watchdog.progress(sample.time.toLong(),sample.size,sample.videoFrameNumber.toLong())
            onStatistics?.invoke(sample)
        }) } catch(e: Exception) { releaseAll(); throw e }
        val code = try {
            control.session = session.sessionId
            control.check()
            withControlCancellation(control) {
                while(!finished.isCompleted) {
                    if(withTimeoutOrNull(5_000) {finished.await();true}==true) break
                    if(watchdog.stalled()) {
                        NetworkDiagnostics.record("media","native_progress_stalled",ErrorCode.DOWNLOAD_INTERRUPTED)
                        throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                    }
                }
                finished.await()
            }
        } finally {
            if(!finished.isCompleted) FFmpegKit.cancel(session.sessionId)
            // The lease outlives this bounded UI wait. No retry/cleanup may reuse
            // these files until the actual native completion callback releases it.
            withContext(NonCancellable) { withTimeoutOrNull(10_000) { finished.await() } }
            if(finished.isCompleted) control.session = null
        }
        control.check()
        if (!ReturnCode.isSuccess(code)) {
            val adapter=args.contains("av1_mediacodec")
            val logs=if(adapter) session.allLogsAsString else ""
            val unavailable=adapter&&listOf("Failed to get pixel format","No decoder could be found","Failed to create decoder",
                "Failed to configure codec","Unsupported color format","Unknown decoder").any {logs.contains(it,true)}
            val failure=if(unavailable) ErrorCode.DECODER_UNAVAILABLE else error
            NetworkDiagnostics.record("media",if(unavailable) "decoder_unavailable" else "native_operation_failed",failure,
                details=org.json.JSONObject().put("av1_adapter",adapter).put("return_code",code?.value))
            throw MediaException(failure,validationCheck=if(failure==ErrorCode.OUTPUT_VALIDATION_FAILED) "decode" else null)
        }
        session
    }
    private suspend fun mp3(primary:File,output:File,selection:Selection,control:TransferControl,duration:Double?,title:String?,thumbnail:File?=null) {
        if(selection.bitrate !in listOf(0,128,192,256,320)) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        val probe = FFprobeKit.getMediaInformation(primary.path).mediaInformation
        val inStream = probe?.streams?.firstOrNull { it.type == "audio" }
        val inChannels = inStream?.allProperties?.optInt("channels", 0)?.takeIf {it in 1..8} ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        val inRate = inStream?.allProperties?.optString("sample_rate")?.toIntOrNull()
            ?: probe?.streams?.firstOrNull()?.allProperties?.optString("sample_rate")?.toIntOrNull() ?: 44100
        // Genuine mono/stereo handling: preserve 1-2ch, downmix 3ch+ to stereo, keep 44.1/48k natively.
        val channels = when { inChannels == 1 -> 1; inChannels >= 2 -> 2; else -> 2 }
        val sampleRate = when(inRate) { 44100 -> 44100; 48000 -> 48000; else -> 44100 }
        val token=java.util.UUID.randomUUID().toString()
        val raw=File(output.parentFile,"encoded-$token.mp3")
        val seconds=duration ?: probe?.duration?.toDoubleOrNull()
        val encodedBytesPerSec=(if(selection.bitrate==0) 320 else selection.bitrate)*1000L/8
        if(seconds!=null && android.os.StatFs(output.parentFile!!.path).availableBytes<seconds*encodedBytesPerSec*2+16*1024*1024) throw MediaException(ErrorCode.INSUFFICIENT_STORAGE)
        val progress=AudioProcessingProgress.start(output.parentFile!!.name,seconds)
        val encodingStarted=android.os.SystemClock.elapsedRealtime()
        try {
            val result=coroutineScope {
                val pipe=android.os.ParcelFileDescriptor.createPipe()
                val nativeStopped=java.util.concurrent.atomic.AtomicBoolean(control.stopped.get())
                val cancellation=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {nativeStopped.set(true)}}
                val decoder=async(Dispatchers.IO) {
                    try {execute(listOf("-y","-v","error","-stats","-stats_period","1","-protocol_whitelist","file,pipe","-i",primary.path,"-map","0:a:0","-vn",
                        "-ac",channels.toString(),"-ar",sampleRate.toString(),"-f","s16le","pipe:${pipe[1].fd}"),control,ErrorCode.CONVERSION_FAILED,
                        onStatistics={progress.sample(it.time)})}
                    finally {pipe[1].close()}
                }
                try {
                    val lease=NativeWork.acquire(primary.parentFile!!)
                    val encoded=try {withContext(Dispatchers.IO) {LameEncoder.encodePipe(pipe[0].fd,raw.path,selection.bitrate,sampleRate,channels,nativeStopped)}}
                        finally {pipe[0].close();lease.release()}
                    if(encoded!=0) decoder.cancel()
                    else decoder.await()
                    encoded
                } finally {
                    nativeStopped.set(true)
                    pipe[0].close()
                    withContext(NonCancellable) {
                        cancellation.cancelAndJoin()
                        decoder.cancelAndJoin()
                        pipe[1].close()
                    }
                }
            }
            control.check()
            if(result!=0 || !raw.isFile || raw.length() == 0L) { raw.delete(); throw MediaException(when(result) {-2->ErrorCode.DOWNLOAD_INTERRUPTED;-3->ErrorCode.INSUFFICIENT_STORAGE;-4->ErrorCode.FILE_WRITE_FAILED;else->ErrorCode.CONVERSION_FAILED}) }
            val encodedAt=android.os.SystemClock.elapsedRealtime()
            progress.phase(AudioProcessingPhase.FINALIZING)
            NetworkDiagnostics.record("media","mp3_encoding_timing",null,operation=output.parentFile?.name,
                details=org.json.JSONObject().put("encoding_ms",encodedAt-encodingStarted).put("duration_s",seconds)
                    .put("sample_rate",sampleRate).put("channels",channels).put("bitrate_kbps",selection.bitrate).put("encoded_bytes",raw.length()))
            val tags=if(selection.embedMetadata) listOf("-metadata","title=${FileNames.sanitize(title ?: "Audio")}")+selection.creator?.let {listOf("-metadata","artist=${FileNames.sanitize(it)}")}.orEmpty() else listOf("-map_metadata","-1")
            var embeddedSuccess = false
            if (thumbnail != null && thumbnail.isFile && thumbnail.length() > 0 && selection.embedMetadata) {
                try {
                    val embedCmd = listOf("-y","-v","error","-protocol_whitelist","file,pipe",
                        "-i",raw.path,"-i",thumbnail.path,
                         "-map","0:a:0","-map","1:v:0",
                         "-c:a","copy","-c:v","mjpeg",
                         "-disposition:v:0","attached_pic",
                        "-id3v2_version","3",
                        "-metadata:s:v","title=Album cover",
                        "-metadata:s:v","comment=Cover (front)") + tags + output.path
                    execute(embedCmd, control, ErrorCode.CONVERSION_FAILED)
                    // A successful muxer exit is not proof that audio survived
                    // cover insertion. Never validate/publish a cover-only MP3.
                    embeddedSuccess = FFprobeKit.getMediaInformation(output.path).mediaInformation
                        ?.streams?.any {it.type=="audio"&&it.codec=="mp3"}==true
                } catch(error: Exception) {
                    if(error is CancellationException) throw error
                    embeddedSuccess = false
                    output.delete()
                }
            }
            if(!embeddedSuccess) {
                // The encoder produces a complete seekable MP3 (including its
                // LAME duration/gapless header), but no user metadata. In this
                // case a same-directory rename avoids copying the whole file.
                // Keep the strict final decode and the muxer fallback unchanged.
                control.check()
                val moved=!selection.embedMetadata && raw.renameTo(output)
                if(!moved) execute(listOf("-y","-v","error","-protocol_whitelist","file,pipe","-i",raw.path,"-c:a","copy","-id3v2_version","3")+tags+output.path,control,ErrorCode.CONVERSION_FAILED)
            }
            NetworkDiagnostics.record("media","mp3_tags_timing",null,operation=output.parentFile?.name,
                details=org.json.JSONObject().put("tags_ms",android.os.SystemClock.elapsedRealtime()-encodedAt).put("audio_copy",true).put("cover_embedded",embeddedSuccess))
            progress.phase(AudioProcessingPhase.VALIDATING)
            val validationStarted=android.os.SystemClock.elapsedRealtime()
            validate(output,true,true,null,duration,"mp3",control)
            NetworkDiagnostics.record("media","mp3_validation_timing",null,operation=output.parentFile?.name,
                details=org.json.JSONObject().put("validation_ms",android.os.SystemClock.elapsedRealtime()-validationStarted).put("validated",true))
            // Verify actual encoded channels and bitrate inside the APK, not just container presence.
            val info = FFprobeKit.getMediaInformation(output.path).mediaInformation
            val outStream = info?.streams?.firstOrNull { it.type=="audio" }
            if(outStream?.codec != "mp3") throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED)
            val properties=outStream!!.allProperties
            val outChannels = properties.optInt("channels",0)
            val outRate=properties.optString("sample_rate").toIntOrNull()
            val outBitrate=properties.optString("bit_rate").toLongOrNull()
            if(outChannels != channels || (outRate != null && outRate != sampleRate)) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED)
            if(outBitrate != null && outBitrate > 0 && selection.bitrate > 0) {
                if(kotlin.math.abs(outBitrate - selection.bitrate * 1000L) > 32000L) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED)
                }
            }
        } finally {progress.close();NativeWork.afterIdle(primary.parentFile!!) {raw.delete()} }
    }
    suspend fun validate(file: File, audio: Boolean, requireAudio: Boolean = true, expectedHeight: Int? = null, expectedDuration: Double? = null, expectedContainer:String? = null, control:TransferControl = TransferControl(), expectedWidth:Int?=null, expectedFps:Double?=null, expectedVideoCodec:String?=null,copySelection:Selection?=null) = withContext(Dispatchers.IO) {
        control.check()
        rejectOriginalImage(file,control)
        control.verifiedOutputSha256=null
        control.onValidationReady?.invoke();control.onValidationReady=null
        fun reject(check: String, detail: String? = null): Nothing {
            NetworkDiagnostics.record("media","validation_rejected_${check}",ErrorCode.OUTPUT_VALIDATION_FAILED,
                operation=file.parentFile?.name,
                details=org.json.JSONObject().put("bytes",file.length()).put("expected_height",expectedHeight).put("expected_width",expectedWidth)
                    .put("expected_fps",expectedFps).put("expected_video_codec",expectedVideoCodec).put("expected_duration",expectedDuration)
                    .put("expected_container",expectedContainer).put("detail",detail))
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck=check)
        }
        if (!file.isFile || file.length() == 0L) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        val result = FFprobeKit.getMediaInformation(file.path)
        val streams = result.mediaInformation?.streams ?: reject("no_streams")
        if (!audio && streams.none { it.type == "video" } || (audio || requireAudio) && streams.none { it.type == "audio" }) reject("missing_stream",
            streams.joinToString(",") { "${it.type}:${it.codec}" })
        if(expectedHeight != null) {
            val actualShort = streams.filter { it.type == "video" }.mapNotNull { s ->
                val w = s.width?.toInt(); val h = s.height?.toInt()
                if (w != null && h != null) minOf(w, h) else h
            }
            val expectedShort = if (expectedWidth != null) minOf(expectedWidth, expectedHeight) else expectedHeight
            val matches = streams.any { it.type == "video" && (it.height?.toInt() == expectedHeight || (expectedWidth != null && it.width?.toInt() == expectedHeight && it.height?.toInt() == expectedWidth)) } ||
                actualShort.any { it == expectedShort }
            if (!matches) reject("height", streams.filter { it.type=="video" }.joinToString(",") { "${it.width}x${it.height}" })
        }
        val video=streams.firstOrNull {it.type=="video"}
        if(expectedWidth!=null && video!=null) {
            val actualW = video.width?.toInt()
            val actualH = video.height?.toInt()
            val actualLong = if (actualW != null && actualH != null) maxOf(actualW, actualH) else actualW
            val expectedLong = if (expectedHeight != null) maxOf(expectedWidth, expectedHeight) else expectedWidth
            val matchesWidth = actualW == expectedWidth || (expectedHeight != null && actualW == expectedHeight && actualH == expectedWidth) ||
                (actualLong != null && actualLong == expectedLong)
            if (!matchesWidth) reject("width","${video.width}x${video.height}")
        }
        if(expectedVideoCodec!=null&&codecFamily(video?.codec)!=expectedVideoCodec) reject("video_codec",video?.codec)
        if(expectedFps!=null) {
            val actual=video?.allProperties?.optString("avg_frame_rate")?.split('/')?.let { parts -> parts.getOrNull(0)?.toDoubleOrNull()?.div(parts.getOrNull(1)?.toDoubleOrNull()?.takeIf {it>0} ?: 1.0) }
            if(actual==null||kotlin.math.abs(actual-expectedFps)>maxOf(.5,expectedFps*.02)) reject("fps",actual?.toString())
        }
        val format=result.mediaInformation.allProperties.optJSONObject("format")?.optString("format_name").orEmpty().split(',')
        // ADTS has no stored duration. Its bitrate-derived estimate can be wildly
        // wrong for VBR AAC; count samples from the entire strict decode instead.
        val rawAac=audio&&format==listOf("aac")&&streams.singleOrNull()?.let {it.type=="audio"&&it.codec=="aac"}==true
        val duration = if(rawAac) decodedAudioDuration(file,streams.single(),control) else result.mediaInformation?.duration?.toDoubleOrNull()
        if(duration==null || duration<=0) reject("duration",result.mediaInformation?.duration)
        if(expectedDuration != null && !OutputDuration.matches(duration,expectedDuration)) reject("duration_mismatch",duration.toString())
        if(expectedContainer in listOf("mp4","m4a") && "mp4" !in format || expectedContainer=="mp3" && ("mp3" !in format || streams.none {it.type=="audio" && it.codec=="mp3"})) reject("container",format.joinToString("+"))
        if(rawAac) {
            validateSeekable(file)
            NetworkDiagnostics.record("media","validated_aac_decoded_duration",null,operation=file.parentFile?.name,
                details=org.json.JSONObject().put("decoded_duration",duration).put("estimated_duration",result.mediaInformation.duration))
            return@withContext
        }
        var demuxVerified = false
        if(copySelection!=null&&control.copySources!=null&&CopyOutputVerification.eligible(copySelection)) {
            // Strict full demux is retained; the payload decoder evidence came from the live sources.
            execute(listOf("-nostdin","-v","error","-xerror","-err_detect","explode","-protocol_whitelist","file,pipe",
                "-i",file.path,"-map","0:v:0","-map","0:a:0","-c","copy","-f","null","-"),control,ErrorCode.OUTPUT_VALIDATION_FAILED)
            demuxVerified = true
            val certifiedHash=nativeSlot.withPermit {CopyOutputVerification.verify(file,copySelection,control)}
            if(certifiedHash!=null) {
                FastIntegrityAuditor.audit(file, audio = audio, requireAudio = requireAudio, expectedDuration = duration)
                control.verifiedOutputSha256=certifiedHash
                return@withContext
            }
        }
        // All video codecs use strict full-file demux plus bounded decoder evidence.
        // Full decoding is reserved for unsupported/inconclusive windows in the auditor.
        if(!demuxVerified) {
            execute(listOf("-nostdin","-v","error","-xerror","-err_detect","explode","-protocol_whitelist","file,pipe",
                "-i",file.path,"-map","0:v:0?","-map","0:a:0?","-c","copy","-f","null","-"),control,ErrorCode.OUTPUT_VALIDATION_FAILED)
        }
        FastIntegrityAuditor.audit(file, audio = audio, requireAudio = requireAudio, expectedDuration = duration)
        control.check()
        NetworkDiagnostics.record("media","validated_${expectedContainer ?: "source"}_fast_audit",null,operation=file.parentFile?.name)
    }
    /** Fixed-size PCM buffers measure decoded samples without a temporary PCM file or unbounded logs. */
    private suspend fun decodedAudioDuration(file:File,stream:StreamInformation,control:TransferControl):Double = withControlCancellation(control) {
        val rate=stream.allProperties.optString("sample_rate").toIntOrNull()?.takeIf {it in 1..384000}
            ?: throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="sample_rate")
        val pipe=android.os.ParcelFileDescriptor.createPipe()
        // Enter the descriptor-owning try/finally even if cancellation races startup.
        val decoder=async(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
            try {
                execute(listOf("-nostdin","-v","error","-xerror","-err_detect","explode","-protocol_whitelist","file,pipe",
                    "-i",file.path,"-map","0:a:0","-vn","-ac","1","-ar",rate.toString(),"-max_error_rate","0",
                    "-f","s16le","pipe:${pipe[1].fd}"),control,ErrorCode.OUTPUT_VALIDATION_FAILED)
            } finally {pipe[1].close()}
        }
        try {
            val bytes=countPcmBytes(pipe[0],control)
            decoder.await();control.check()
            if(bytes<=0||bytes%2!=0L) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decoded_samples")
            bytes/2.0/rate
        } finally {pipe[0].close();decoder.cancel()}
    }
    private suspend fun countPcmBytes(descriptor:android.os.ParcelFileDescriptor,control:TransferControl):Long =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->
            val buffer=ByteArray(64*1024);var bytes=0L
            while(true) {
                currentCoroutineContext().ensureActive();control.check()
                val count=input.read(buffer)
                if(count<0) break
                bytes+=count
            }
            bytes
        }
    private fun rejectOriginalImage(file:File,control:TransferControl) {
        control.check()
        val prefix=file.inputStream().use {it.readNBytesCompat(32)}
        if(MediaFileSignature.originalImageContainer(prefix))
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="media_kind_mismatch")
    }
    private fun validateSeekable(file: File) {
        try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val trackCount = extractor.trackCount
                if (trackCount <= 0) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "no_streams")
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) {
                        extractor.selectTrack(i)
                        val dUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
                        val endTargetUs = if (dUs > 1_000_000L) dUs - 500_000L else dUs / 2
                        if (endTargetUs > 0) {
                            extractor.seekTo(endTargetUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            val hasSample = if(android.os.Build.VERSION.SDK_INT>=28) extractor.sampleSize>0 else extractor.sampleTime>=0
                            if (!hasSample) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "truncated_stream")
                        }
                        break
                    }
                }
            } finally {
                runCatching { extractor.release() }
            }
            // The full decode above already checked every frame. Re-decoding
            // three thumbnails adds latency without additional payload coverage.
        } catch (e: MediaException) {
            throw e
        } catch (e: LinkageError) {
            throw MediaException(ErrorCode.DECODER_UNAVAILABLE)
        } catch (e: Exception) {
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "probe_${e.javaClass.simpleName}")
        }
    }
}
