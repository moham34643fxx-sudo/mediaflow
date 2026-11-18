package com.zdownloader.app.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Fast integrity, track and sample auditor:
 * Validates container atom bounds, track integrity, multi-point keyframe/sample seekability,
 * audio stream configuration and A/V timing, and hardware decoding with strict validation fallback.
 */
object FastIntegrityAuditor {

    internal enum class DecoderStatus {
        PASS,
        FAIL,
        INCONCLUSIVE,
        UNSUPPORTED;

        val isSuccess get() = this == PASS
    }

    internal data class WindowResult(
        val status: DecoderStatus,
        val initialTimeUs: Long = -1L,
        val framesProduced: Int = 0,
        val samplesFed: Int = 0,
        val reason: String? = null
    )

    internal fun isDecodedOutput(size: Int, flags: Int): Boolean =
        size > 0 && (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0

    // AV1 obu_forbidden_bit and obu_reserved_1bit must both be zero.
    internal fun validAv1ObuHeader(header: Byte): Boolean = (header.toInt() and 0x81) == 0

    private fun verifyAv1SampleHeaders(
        extractor: MediaExtractor,
        buffer: ByteBuffer,
        maxSamples: Int,
        initialTimeUs: Long
    ): Boolean {
        try {
            repeat(maxSamples) {
                if (extractor.sampleTime < 0) return true
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0 || !validAv1ObuHeader(buffer.get(0))) return false
                if (!extractor.advance()) return true
            }
            return true
        } finally {
            // Reuse the decoder buffer and restore the same sync sample; no
            // extra decoder, packet inventory, or full-file payload walk.
            extractor.seekTo(initialTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            buffer.clear()
        }
    }

    @JvmOverloads
    fun audit(
        file: File,
        audio: Boolean,
        requireAudio: Boolean = true,
        expectedDuration: Double? = null,
        strictFallback: ((File) -> Unit)? = null
    ) {
        if (!file.isFile || file.length() < 64) {
            throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        }

        // 1. ISO-BMFF (MP4 / M4A) container atom and box bounds verification
        if (isIsoBmff(file)) {
            auditMp4Atoms(file)
        }

        // 2. Hardware / Native MediaExtractor tracks and seekability audit
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: MediaException) {
                throw e
            } catch (e: Exception) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "probe_${e.javaClass.simpleName}")
            }

            val trackCount = extractor.trackCount
            if (trackCount <= 0) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "no_streams")
            }

            var hasVideo = false
            var hasAudio = false
            var videoTrackIndex = -1
            var audioTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (videoTrackIndex < 0) videoTrackIndex = i
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (audioTrackIndex < 0) audioTrackIndex = i
                }
            }

            if (!audio && !hasVideo) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "missing_video_stream")
            }
            if ((audio || requireAudio) && !hasAudio) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "missing_audio_stream")
            }

            var videoDurationUs = 0L
            var videoOriginTime = 0L
            var videoDecoderNeedsStrictFallback = false

            // 3. Video track seekability, sample readability, and window hardware decoding
            if (hasVideo && videoTrackIndex >= 0) {
                extractor.selectTrack(videoTrackIndex)
                val format = extractor.getTrackFormat(videoTrackIndex)
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                if (width <= 0 || height <= 0) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "invalid_video_dimensions")
                }

                videoDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    expectedDuration?.let { (it * 1_000_000).toLong() } ?: 0L
                }

                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

                // Window 1: Origin (0s)
                val originResult = verifyDecoderWindow(extractor, videoTrackIndex, mime, format, 0L, maxSamples = 20)
                videoOriginTime = originResult.initialTimeUs
                when (originResult.status) {
                    DecoderStatus.PASS -> {}
                    DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "video_origin_decoder_corrupt")
                    DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> videoDecoderNeedsStrictFallback = true
                }

                // Window 2: Midpoint
                if (videoDurationUs > 2_000_000L) {
                    val midTargetUs = videoDurationUs / 2
                    val midResult = verifyDecoderWindow(extractor, videoTrackIndex, mime, format, midTargetUs, maxSamples = 20)
                    when (midResult.status) {
                        DecoderStatus.PASS -> {}
                        DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "video_mid_decoder_corrupt")
                        DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> videoDecoderNeedsStrictFallback = true
                    }
                }

                // Window 3: Near end of file
                if (videoDurationUs > 1_000_000L) {
                    val endTargetUs = maxOf(0L, videoDurationUs - 500_000L)
                    val endResult = verifyDecoderWindow(extractor, videoTrackIndex, mime, format, endTargetUs, maxSamples = 20)
                    when (endResult.status) {
                        DecoderStatus.PASS -> {}
                        DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "video_end_decoder_corrupt")
                        DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> videoDecoderNeedsStrictFallback = true
                    }
                }
                extractor.unselectTrack(videoTrackIndex)
            }

            var audioDurationUs = 0L
            var audioOriginTime = 0L
            var audioDecoderNeedsStrictFallback = false

            // 4. Audio track inspection, seekability, sample readability, and window hardware decoding
            if (hasAudio && audioTrackIndex >= 0) {
                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                if (sampleRate <= 0 || channels <= 0) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "invalid_audio_properties")
                }

                audioDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    videoDurationUs.takeIf { it > 0 } ?: (expectedDuration?.let { (it * 1_000_000).toLong() } ?: 0L)
                }

                // Window 1: Origin (0s)
                val audioOriginResult = verifyDecoderWindow(extractor, audioTrackIndex, mime, format, 0L, maxSamples = 10)
                audioOriginTime = audioOriginResult.initialTimeUs
                when (audioOriginResult.status) {
                    DecoderStatus.PASS -> {}
                    DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "audio_origin_decoder_corrupt")
                    DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> audioDecoderNeedsStrictFallback = true
                }

                // Window 2: Midpoint
                if (audioDurationUs > 2_000_000L) {
                    val midTargetUs = audioDurationUs / 2
                    val audioMidResult = verifyDecoderWindow(extractor, audioTrackIndex, mime, format, midTargetUs, maxSamples = 10)
                    when (audioMidResult.status) {
                        DecoderStatus.PASS -> {}
                        DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "audio_mid_decoder_corrupt")
                        DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> audioDecoderNeedsStrictFallback = true
                    }
                }

                // Window 3: Near end of file
                if (audioDurationUs > 1_000_000L) {
                    val endTargetUs = maxOf(0L, audioDurationUs - 500_000L)
                    val audioEndResult = verifyDecoderWindow(extractor, audioTrackIndex, mime, format, endTargetUs, maxSamples = 10)
                    when (audioEndResult.status) {
                        DecoderStatus.PASS -> {}
                        DecoderStatus.FAIL -> throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "audio_end_decoder_corrupt")
                        DecoderStatus.UNSUPPORTED, DecoderStatus.INCONCLUSIVE -> audioDecoderNeedsStrictFallback = true
                    }
                }
                extractor.unselectTrack(audioTrackIndex)
            }

            // 5. A/V timing verification when both streams exist
            if (hasVideo && hasAudio && videoDurationUs > 0 && audioDurationUs > 0) {
                val durationDiffUs = kotlin.math.abs(videoDurationUs - audioDurationUs)
                val maxDuration = maxOf(videoDurationUs, audioDurationUs)
                if (maxDuration > 5_000_000L && durationDiffUs > 3_000_000L && durationDiffUs.toDouble() / maxDuration > 0.25) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "av_timing_duration_desync")
                }
                val originDiffUs = kotlin.math.abs(videoOriginTime - audioOriginTime)
                if (originDiffUs > 2_500_000L) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "av_timing_origin_desync")
                }
            }

            // 6. Strict validation fallback if hardware decoder was unsupported or inconclusive
            if (videoDecoderNeedsStrictFallback || audioDecoderNeedsStrictFallback) {
                try {
                    NetworkDiagnostics.record(
                        "media", "fast_auditor_strict_fallback_engaged", null,
                        operation = file.parentFile?.name,
                        details = org.json.JSONObject()
                            .put("video_fallback", videoDecoderNeedsStrictFallback)
                            .put("audio_fallback", audioDecoderNeedsStrictFallback)
                    )
                } catch (_: Exception) {}
                if (strictFallback != null) {
                    strictFallback(file)
                } else {
                    val videoMime = if(videoTrackIndex >= 0) extractor.getTrackFormat(videoTrackIndex).getString(MediaFormat.KEY_MIME) else null
                    runStrictFallback(file, videoMime)
                }
            } else {
                try {
                    NetworkDiagnostics.record(
                        "media", "fast_auditor_hardware_verified", null,
                        operation = file.parentFile?.name,
                        details = org.json.JSONObject()
                            .put("video_windows_verified", hasVideo)
                            .put("audio_windows_verified", hasAudio)
                    )
                } catch (_: Exception) {}
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: LinkageError) {
            throw MediaException(ErrorCode.DECODER_UNAVAILABLE)
        } catch (e: Exception) {
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "probe_${e.javaClass.simpleName}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun isIsoBmff(file: File): Boolean {
        if (file.length() < 12) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(4)
                val boxType = ByteArray(4)
                raf.readFully(boxType)
                val typeStr = String(boxType, Charsets.ISO_8859_1)
                typeStr in setOf("ftyp", "moov", "mdat")
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun auditMp4Atoms(file: File) {
        val length = file.length()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            var foundMoov = false
            var foundMdat = false
            var atomCount = 0

            while (offset < length && atomCount < 1000) {
                raf.seek(offset)
                if (length - offset < 8) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "truncated_trailing_bytes")
                }
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.ISO_8859_1)

                val boxSize = when (size) {
                    0L -> length - offset
                    1L -> {
                        if (length - offset < 16) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "corrupt_extended_box")
                        raf.readLong()
                    }
                    else -> size
                }

                val minBoxSize = if (size == 1L) 16L else 8L
                if (boxSize < minBoxSize || boxSize > length - offset) {
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "truncated_atom_$type")
                }

                if (type == "moov") foundMoov = true
                if (type == "mdat") foundMdat = true

                offset += boxSize
                atomCount++
            }

            if (offset != length) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "truncated_trailing_bytes")
            }

            if (!foundMoov) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "missing_moov_atom")
            }
            if (!foundMdat && length > 512 * 1024) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "missing_mdat_atom")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDecoderWindow(
        extractor: MediaExtractor,
        trackIndex: Int,
        mime: String,
        format: MediaFormat,
        targetUs: Long,
        maxSamples: Int = 20
    ): WindowResult {
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
            return WindowResult(DecoderStatus.PASS)
        }

        // 1. Seek to target sync point
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (extractor.sampleTime < 0) {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        if (extractor.sampleTime < 0) {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
        }
        val initialTimeUs = extractor.sampleTime
        if (initialTimeUs < 0) {
            return WindowResult(DecoderStatus.FAIL, initialTimeUs = -1L, reason = "seek_failed")
        }

        // 2. Initialize MediaCodec
        var codec: MediaCodec? = null
        try {
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                return WindowResult(DecoderStatus.UNSUPPORTED, initialTimeUs = initialTimeUs, reason = "create_decoder_failed_${e.javaClass.simpleName}")
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                return WindowResult(DecoderStatus.UNSUPPORTED, initialTimeUs = initialTimeUs, reason = "configure_decoder_failed_${e.javaClass.simpleName}")
            }

            var inputEosQueued = false
            var framesProduced = 0
            var samplesFed = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var triesWithoutProgress = 0
            val maxTriesWithoutProgress = 20 // bounded attempts (200ms total timeout)
            val timeoutUs = 10_000L // 10ms per dequeue call

            while (framesProduced == 0 && triesWithoutProgress < maxTriesWithoutProgress) {
                var progress = false

                // A. Feed input buffer if EOS not yet queued
                if (!inputEosQueued) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            return WindowResult(DecoderStatus.FAIL, initialTimeUs = initialTimeUs, reason = "null_input_buffer")
                        }
                        // Some hardware decoders produce the first valid frame
                        // before reporting damage in later queued packets. Inspect
                        // only this window's existing sample budget before feeding.
                        if (samplesFed == 0 && mime == "video/av01" &&
                            !verifyAv1SampleHeaders(extractor, inputBuffer, maxSamples, initialTimeUs)) {
                            return WindowResult(DecoderStatus.FAIL, initialTimeUs = initialTimeUs, reason = "invalid_av1_obu_header")
                        }
                        inputBuffer.clear()

                        if (samplesFed >= maxSamples || extractor.sampleTime < 0) {
                            // Queue EOS to signal end of window and flush decoder pipeline
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEosQueued = true
                            progress = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize <= 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEosQueued = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                val sampleFlags = extractor.sampleFlags
                                val flags = if (sampleFlags != 0) sampleFlags else {
                                    if (samplesFed == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                }
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, flags)
                                samplesFed++
                                extractor.advance()
                            }
                            progress = true
                        }
                    }
                }

                // B. Drain output buffer
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputIndex >= 0 -> {
                        progress = true
                        if (isDecodedOutput(bufferInfo.size, bufferInfo.flags)) {
                            framesProduced++
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        // Empty EOS is not decoder evidence. Stop immediately and retain strict fallback.
                        if (framesProduced > 0 || (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progress = true
                        // Decoder output format changed, continue draining to get real decoded frame
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        progress = true
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Temporary wait for decoder output
                    }
                }

                if (progress) {
                    triesWithoutProgress = 0
                } else {
                    triesWithoutProgress++
                }
            }

            val status = when {
                framesProduced > 0 -> DecoderStatus.PASS
                else -> DecoderStatus.INCONCLUSIVE
            }
            return WindowResult(
                status = status,
                initialTimeUs = initialTimeUs,
                framesProduced = framesProduced,
                samplesFed = samplesFed
            )
        } catch (e: MediaCodec.CodecException) {
            return if (!e.isRecoverable && !e.isTransient) {
                WindowResult(DecoderStatus.FAIL, initialTimeUs = initialTimeUs, reason = "fatal_codec_exception_${e.errorCode}")
            } else {
                WindowResult(DecoderStatus.INCONCLUSIVE, initialTimeUs = initialTimeUs, reason = "transient_codec_exception")
            }
        } catch (e: Exception) {
            return WindowResult(DecoderStatus.FAIL, initialTimeUs = initialTimeUs, reason = "unexpected_exception_${e.javaClass.simpleName}")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    internal fun strictFallbackArguments(file: File, videoMime: String?): Array<String> = buildList {
        addAll(listOf("-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
            "-protocol_whitelist", "file,pipe", "-hwaccel", "none"))
        // The bundled generic AV1 decoder cannot reliably emit frames; use its explicit Android adapter only on fallback.
        if(videoMime == "video/av01") addAll(listOf("-c:v", "av1_mediacodec", "-ndk_codec", "1"))
        addAll(listOf("-i", file.path, "-map", "0:v:0?", "-map", "0:a:0?", "-max_error_rate", "0"))
        if(videoMime == "video/av01") addAll(listOf("-stats", "-fps_mode", "passthrough"))
        addAll(listOf("-f", "null", "-"))
    }.toTypedArray()

    private fun runStrictFallback(file: File, videoMime: String?) {
        try {
            val session = FFmpegKit.executeWithArguments(
                strictFallbackArguments(file, videoMime)
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "strict_fallback_failed")
            }
            if(videoMime == "video/av01" && session.getAllStatistics(1000).none {it.videoFrameNumber > 0}) {
                throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "strict_fallback_no_video_frames")
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: LinkageError) {
            throw MediaException(ErrorCode.DECODER_UNAVAILABLE)
        } catch (e: Exception) {
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED, validationCheck = "strict_fallback_${e.javaClass.simpleName}")
        }
    }
}
