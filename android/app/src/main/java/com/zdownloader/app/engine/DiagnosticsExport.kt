package com.zdownloader.app.engine

import org.json.JSONObject

/** Fail closed: unknown fields and all untrusted free text are discarded, not redacted. */
internal class DiagnosticsExport {
    private val aliases=linkedMapOf<String,String>()
    private val clocks=mutableMapOf<String,Long>()
    private val numeric=setOf("elapsedMs","attempt","httpStatus","dropped","writeFailures","networkGeneration",
        "queue_wait_ms","inspection_ms","mux_ms","validation_ms","extraction_ms","expected_frames","decoded_frames","submitted_samples","range_frames","workers","first_range_frames",
        "video_bytes","audio_bytes","output_bytes","video_download_ms","audio_download_ms","post_download_total_ms","end_to_end_ms",
        "copy_duration_ms","readback_validation_ms","copy_and_verification_ms","bytes","buffer_size_kb","return_code","codec_error","width","height","fps",
        "preference","count","recorded_formats","decode_tail_ms","normalized_formats","accepted_formats","accepted_video_sources","accepted_audio_sources")
    private val flags=setOf("cancelled","recoverable","transient","input_eos","output_eos","validated","video_copy","stream_copy","transcoded","full_audio_decode","initialized","hardware","software",
        "screen_interactive","app_foreground","hardware_av1","hardware_vp9","hardware_avc","hardware_hevc","available","validated","wifi","cellular","vpn","proxy","privateDns","underlyingValidated","underlyingInternetPresent",
        "complete_source_evidence","video_decoded_before_eof","audio_decoded_before_eof")
    private val enums=mapOf("stack" to setOf("android","analysis","media","queue","scheduler","storage","capture","formats","python","okhttp","share","notification"),
        "container" to setOf("mp4","mkv","webm","m4a","aac","mp3","opus","ogg","flac"),
        "extension" to setOf("mp4","webm","mkv","m4a","aac","opus","ogg","mp3","flac","ts"),
        "video_codec" to setOf("av1","vp9","vp8","h264","hevc","unknown","none"),
        "audio_codec" to setOf("aac","opus","vorbis","mp3","flac","unknown","none"),
        "reason" to setOf("offered","codec_audio_incompatible","policy_not_offered"),
        "decoder_step" to setOf("configure","start","stop","release","released"))
    private fun alias(value:String)=aliases.getOrPut(value) {"op${aliases.size+1}"}
    fun write(rows:List<String>,destination:java.io.Writer) {
        check(com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS) {"Internal diagnostics unavailable"}
        val safe=rows.mapNotNull(::sanitize)
        val header=JSONObject().put("schema",1).put("app_version",com.zdownloader.app.BuildConfig.VERSION_NAME)
            .put("version_code",com.zdownloader.app.BuildConfig.VERSION_CODE).put("api",android.os.Build.VERSION.SDK_INT)
            .put("rows_omitted",rows.size-safe.size).put("capture_active",PerformanceCapture.active)
        destination.append(header.toString()).append('\n')
        safe.forEach {destination.append(it.toString()).append('\n')}
    }
    fun sanitize(line:String):JSONObject? {
        if(line.length>65536) return null
        val source=runCatching {JSONObject(line)}.getOrNull() ?: return null
        val result=JSONObject()
        val session=source.optString("capture_session")
        val clockKey=session.ifBlank {"legacy"}
        val time=source.optLong("monotonic_ms",-1).takeIf {it>=0} ?: source.optLong("time",-1)
        if(time>=0) {
            val base=clocks.getOrPut(clockKey) {time}
            result.put("relative_ms",time-base).put("clock",if(session.isBlank()) "wall_relative" else "monotonic")
        }
        if(session.isNotBlank()) result.put("session",alias("session:$session"))
        source.keys().forEach {key->
            val value=source.opt(key)
            when {
                key in numeric && value is Number && value.toDouble().isFinite() -> result.put(key,value)
                key in flags && value is Boolean -> result.put(key,value)
                key in enums && value in enums.getValue(key) -> result.put(key,value)
                key=="operation" && value is String && value.length<=512 -> result.put(key,alias(value))
                key=="stage" && value is String && value.matches(Regex("[a-z][a-z0-9_]{0,79}")) && safeStage(value) -> result.put(key,value)
                key=="code" && value is String && (value=="OK"||com.zdownloader.app.domain.ErrorCode.entries.any {it.name==value}) -> result.put(key,value)
                key in setOf("network","details") && value is JSONObject -> result.put(key,sanitizeObject(value))
            }
        }
        return result.takeIf {it.has("stage")}
    }
    private fun safeStage(value:String):Boolean = value in setOf("capture_started","capture_stopped","screen_on","screen_off","app_foreground","app_background",
        "format_catalog","format_row","format_choice","format_capabilities","container_option","av1_decoder_setup","av1_decoder_step","av1_decode_progress","av1_decode_failure","ffmpeg_completed") ||
        value in stages
    private fun sanitizeObject(source:JSONObject):JSONObject {
        val out=JSONObject()
        source.keys().forEach {key->val value=source.opt(key)
            when {
                key in numeric && value is Number && value.toDouble().isFinite()->out.put(key,value)
                key in flags && value is Boolean->out.put(key,value)
                key in setOf("video_codec","audio_codec") && value is String && com.zdownloader.app.domain.codecFamily(value) in enums.getValue(key)->out.put(key,com.zdownloader.app.domain.codecFamily(value))
                key in enums && value in enums.getValue(key)->out.put(key,value)
                key in setOf("format_alias","video_alias","audio_alias") && value is String && value.matches(Regex("f[0-9]{1,4}"))->out.put(key,value)
                key=="decoder" && value is String && value in decoders->out.put(key,value)
                key=="validation_check" && value is String && value in setOf("decode","decoded_frame_count","audio_timing","audio_timing_contract","height","width","fps","duration","duration_mismatch","container","missing_stream","no_streams","video_codec","regenerate_valid_sources")->out.put(key,value)
            }
        }
        return out
    }
    companion object {
        private val decoders=setOf("c2.android.av1-dav1d.decoder","c2.android.av1.decoder","c2.qti.av1.decoder","c2.qti.av1.decoder.low_latency",
            "c2.qti.av1.decoder.secure","c2.mtk.av1.decoder","c2.mtk.vp9.decoder","c2.qti.vp9.decoder","c2.android.vp9.decoder",
            "android_av1_batched","av1_mediacodec")
        private val stages=setOf("network","network_lost","extraction_completed","transfer_started","first_live_bytes","network_tracks_completed","transfer_completed",
            "processor_requested","processor_admitted","processing_completed","validation_started","output_validated","publication_staging_started",
            "publication_validation_released","pending_export_verified","gallery_publication_completed","video_mux_timing","video_validation_timing",
            "video_pipeline_profiling","mediastore_export_timing","av1_validation_plan","av1_decoder","validated_mp4_full_av1","validated_mkv_full_av1",
            "validated_webm_full_av1","validated_mp4_fast_audit","validated_mkv_fast_audit","validated_webm_fast_audit","native_operation_failed","decoder_unavailable","processing_failure","native_progress_stalled",
            "source_overlap_started","source_overlap_completed","copy_output_proof_accepted","copy_output_proof_fallback","copy_output_proof_phase",
            "python_queued","python_worker_started","python_runtime_start","python_module_start","python_call_start","python_call_returned",
            "background_extraction_started","background_formats_resolved","queue_claimed","queue_saved","terminal_failure","automatic_retry",
            "uidt_job_started","uidt_job_stopped","user_foreground_started","user_foreground_runner_failed","worker_started","worker_failed",
            "mp3_encoding_timing","mp3_tags_timing","mp3_validation_timing")
    }
}
