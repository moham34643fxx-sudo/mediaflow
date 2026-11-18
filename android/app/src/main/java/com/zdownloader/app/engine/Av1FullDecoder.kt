package com.zdownloader.app.engine

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.zdownloader.app.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import java.io.File

/** Full AV1 decode without copying every decoded YUV image back through FFmpeg.
 * Only used with a trustworthy container frame count. No sampling, seek or frame dropping.
 * Other codecs, unknown counts and unsupported configurations retain the strict FFmpeg path.
 */
internal object Av1FullDecoder {
    internal fun increasingTimestamp(previous:Long,current:Long)=current>previous
    private data class Range(val start:Long?,val end:Long?,val frames:Long)
    private suspend fun ranges(file:File,expected:Long,control:TransferControl):List<Range> {
        val whole=listOf(Range(null,null,expected))
        if(expected<300||Runtime.getRuntime().availableProcessors()<4) return whole
        val scan=MediaExtractor()
        try {
            scan.setDataSource(file.path)
            val track=(0 until scan.trackCount).firstOrNull {scan.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/av01"} ?: return whole
            val format=scan.getTrackFormat(track)
            if(format.getInteger(MediaFormat.KEY_WIDTH).toLong()*format.getInteger(MediaFormat.KEY_HEIGHT)>1920L*1080) return whole
            val name=MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) ?: return whole
            val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {it.name==name} ?: return whole
            if(info.getCapabilitiesForType("video/av01").maxSupportedInstances<2) return whole
            scan.selectTrack(track)
            var count=0L;var splitTime:Long?=null;var splitIndex=0L;var previousTime=Long.MIN_VALUE
            while(scan.sampleTime>=0) {
                control.check();currentCoroutineContext().ensureActive()
                // Presentation timestamps are not guaranteed monotonic or unique.
                // Ambiguous coverage/seek boundaries must use continuous decoding.
                if(!increasingTimestamp(previousTime,scan.sampleTime)) return whole
                previousTime=scan.sampleTime
                if(count>0&&scan.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0&&
                    kotlin.math.abs(count-expected/2)<kotlin.math.abs(splitIndex-expected/2)) {
                    splitTime=scan.sampleTime;splitIndex=count
                }
                count++
                if(!scan.advance()) break
            }
            // AV1 may have non-displaying samples; never invent segment frame counts.
            if(count!=expected||splitTime==null||splitIndex<expected/4||splitIndex>expected*3/4) return whole
            return listOf(Range(null,splitTime,splitIndex),Range(splitTime,null,expected-splitIndex))
        } catch(error:Exception) {
            control.check();currentCoroutineContext().ensureActive();return whole
        } finally {scan.release()}
    }
    suspend fun decode(file:File,expectedFrames:Long,control:TransferControl):Long? {
        if(expectedFrames<=0) return null
        val plan=ranges(file,expectedFrames,control)
        NetworkDiagnostics.record("media","av1_validation_plan",null,operation=file.parentFile?.name,
            details=org.json.JSONObject().put("workers",plan.size).put("expected_frames",expectedFrames).put("first_range_frames",plan.first().frames))
        return try {
            val results=coroutineScope {plan.map {range->async(Dispatchers.IO) {decodeRange(file,range,control)}}.awaitAll()}
            if(results.any {it==null}) null else results.filterNotNull().sum()
        } catch(error:MediaException) {
            if(plan.size<2||error.validationCheck!="decoded_frame_count") throw error
            // A split incompatibility is not proof that a valid source is corrupt.
            // Recheck the entire continuous stream before reporting damaged bytes.
            decodeRange(file,Range(null,null,expectedFrames),control)
        }
    }
    private suspend fun decodeRange(file:File,range:Range,control:TransferControl):Long? {
        val expectedFrames=range.frames
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        var started=false
        val lease=NativeWork.acquire(file.parentFile!!)
        try {
            // Setup incompatibility may fall back. Errors after decoding begins must not.
            try {
                extractor.setDataSource(file.path)
                val track=(0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/av01"
                } ?: return null
                val format=extractor.getTrackFormat(track)
                val name=MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) ?: return null
                format.setInteger(MediaFormat.KEY_PRIORITY,0)
                format.setFloat(MediaFormat.KEY_OPERATING_RATE,Short.MAX_VALUE.toFloat())
                decoder=MediaCodec.createByCodecName(name)
                NetworkDiagnostics.record("media","av1_decoder",null,operation=file.parentFile?.name,
                    details=org.json.JSONObject().put("decoder",name).put("range_frames",expectedFrames))
                diagnosticStep(file,"configure")
                decoder.configure(format,null,null,0)
                extractor.selectTrack(track)
                range.start?.let {
                    extractor.seekTo(it,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if(extractor.sampleTime!=it) return null
                }
                diagnosticStep(file,"start")
                decoder.start();started=true
                NetworkDiagnostics.captureObservation {
                    val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {it.name==name}
                    NetworkDiagnostics.record("media","av1_decoder_setup",null,operation=file.parentFile?.name,
                        details=org.json.JSONObject().put("decoder",name).put("initialized",true)
                            .put("hardware",if(android.os.Build.VERSION.SDK_INT>=29) info?.isHardwareAccelerated else null)
                            .put("software",if(android.os.Build.VERSION.SDK_INT>=29) info?.isSoftwareOnly else null))
                }
            } catch(error:Exception) {
                control.check();currentCoroutineContext().ensureActive()
                NetworkDiagnostics.captureObservation {
                    NetworkDiagnostics.record("media","av1_decoder_setup",null,operation=file.parentFile?.name,
                        details=org.json.JSONObject().put("initialized",false)
                            .put("codec_error",(error as? MediaCodec.CodecException)?.errorCode)
                            .put("recoverable",(error as? MediaCodec.CodecException)?.isRecoverable)
                            .put("transient",(error as? MediaCodec.CodecException)?.isTransient))
                }
                return null
            }
            val codec=requireNotNull(decoder)
            val info=MediaCodec.BufferInfo()
            var inputEnded=false
            var outputEnded=false
            var frames=0L
            var submittedSamples=0L
            var lastProgress=SystemClock.elapsedRealtime()
            var lastDiagnostic=lastProgress
            while(!outputEnded) {
                control.check();currentCoroutineContext().ensureActive()
                var progressed=false
                // Fill all currently available input slots before waiting for output.
                // FFmpeg's synchronous adapter can wait ~8ms for each individual frame.
                if(!inputEnded) for(slot in 0 until 16) {
                    val index=codec.dequeueInputBuffer(0)
                    if(index<0) break
                    val buffer=codec.getInputBuffer(index) ?: throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decode")
                    buffer.clear()
                    val count=if(range.end!=null&&submittedSamples>=range.frames) -1 else extractor.readSampleData(buffer,0)
                    if(count<0) {
                        codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded=true
                    } else {
                        if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED!=0)
                            throw MediaException(ErrorCode.DRM_PROTECTED)
                        codec.queueInputBuffer(index,0,count,extractor.sampleTime,0)
                        submittedSamples++
                        extractor.advance()
                    }
                    progressed=true
                    if(inputEnded) break
                }
                for(slot in 0 until 16) {
                    val index=codec.dequeueOutputBuffer(info,if(slot==0&&!progressed) 1000 else 0)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {progressed=true;continue}
                    if(index<0) break
                    if(info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) frames++
                    outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(index,false)
                    progressed=true
                    if(frames>expectedFrames) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decoded_frame_count")
                    if(outputEnded) break
                }
                if(progressed) lastProgress=SystemClock.elapsedRealtime()
                else if(SystemClock.elapsedRealtime()-lastProgress>15_000)
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decode")
                if(PerformanceCapture.active&&SystemClock.elapsedRealtime()-lastDiagnostic>=10_000) {
                    NetworkDiagnostics.captureObservation {
                        NetworkDiagnostics.record("media","av1_decode_progress",null,operation=file.parentFile?.name,
                            details=org.json.JSONObject().put("decoded_frames",frames).put("submitted_samples",submittedSamples)
                                .put("expected_frames",expectedFrames).put("input_eos",inputEnded).put("output_eos",outputEnded))
                    }
                    lastDiagnostic=SystemClock.elapsedRealtime()
                }
            }
            if(frames!=expectedFrames) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decoded_frame_count")
            return frames
        } catch(error:MediaCodec.CodecException) {
            NetworkDiagnostics.captureObservation {
                NetworkDiagnostics.record("media","av1_decode_failure",null,operation=file.parentFile?.name,
                    details=org.json.JSONObject().put("codec_error",error.errorCode).put("recoverable",error.isRecoverable).put("transient",error.isTransient))
            }
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="decode")
        } finally {
            diagnosticStep(file,"stop")
            if(started) runCatching {decoder?.stop()}
            diagnosticStep(file,"release")
            runCatching {decoder?.release()}
            diagnosticStep(file,"released")
            extractor.release()
            lease.release()
        }
    }
    private fun diagnosticStep(file:File,step:String) {
        NetworkDiagnostics.captureObservation {
            NetworkDiagnostics.record("media","av1_decoder_step",null,operation=file.parentFile?.name,
                details=org.json.JSONObject().put("decoder_step",step))
        }
    }
}
