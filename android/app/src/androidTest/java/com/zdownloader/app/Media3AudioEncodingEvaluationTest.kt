package com.zdownloader.app

import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.zdownloader.app.engine.Processor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Diagnostic comparison of audio-only AAC 192k encoding: Native FFmpegKit software baseline
 * vs. Media3 Transformer. Does not change production code or establish quality equivalence.
 */
class Media3AudioEncodingEvaluationTest {

    private data class Digest(val bytes: Long, val sha256: String)

    private fun digest(file: File): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
                bytes += r
            }
        }
        return Digest(bytes, md.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun guardNoActiveSessions(phase: String) {
        val active = FFmpegKit.listSessions()?.filter {
            it.state == SessionState.RUNNING || it.state == SessionState.CREATED
        }.orEmpty()
        check(active.isEmpty()) {
            "Active FFmpeg sessions detected during $phase: ${active.map { "${it.sessionId}:${it.state}" }}"
        }
    }

    private fun fsyncFile(file: File) {
        if (file.isFile) {
            FileOutputStream(file, true).use { it.fd.sync() }
        }
    }

    @Test
    fun comparesAudioEncodingWithoutChangingProduction(): Unit = runBlocking(Dispatchers.Default) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val evidenceRun = requireNotNull(args.getString("evidenceRun")) { "Missing required evidenceRun" }
        require(evidenceRun.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) { "Invalid evidenceRun (must match ASCII 1..48): $evidenceRun" }

        val extFilesDir = checkNotNull(context.getExternalFilesDir(null)) { "Target externalFilesDir is null" }
        val parentDir = File(extFilesDir, "media-technology-evaluation")
        if (!parentDir.exists()) check(parentDir.mkdirs() || parentDir.isDirectory) { "Failed to create: ${parentDir.path}" }

        val benchmarkDir = File(parentDir, "audio-$evidenceRun")
        check(!benchmarkDir.exists() && benchmarkDir.mkdir()) {
            "Collision refusal: benchmark dir already exists or cannot be created: ${benchmarkDir.absolutePath}"
        }
        val resultFile = File(benchmarkDir, "result.json")

        val inputPathStr = args.getString("inputPath").orEmpty()
        val expectedInputSha256 = args.getString("expectedInputSha256").orEmpty()

        val report = JSONObject()
            .put("diagnostic_completed", false)
            .put("productionAccepted", false)
            .put("candidateAcceptedForProduction", false)
            .put("quality_equivalence_established", false)
            .put("hardware_inferred", false)
            .put("resource_scope", "application_process_only; remote media codec services excluded")
            .put("measurement_scope", "audio-only transcode; single-trial diagnostic; not whole-queue speedup")
            .put("evidence_run", evidenceRun)
            .put("input_path", inputPathStr)
            .put("expected_input_sha256", expectedInputSha256)

        fun saveResult() {
            val tmp = File(benchmarkDir, "result.json.tmp")
            tmp.writeText(report.toString(2))
            if (!tmp.renameTo(resultFile)) {
                resultFile.writeText(report.toString(2))
                tmp.delete()
            }
        }
        saveResult()

        val benchmarkStartWall = SystemClock.elapsedRealtime()
        val benchmarkStartCpu = Process.getElapsedCpuTime()
        var beforeDigest: Digest? = null
        var primaryFailure: Throwable? = null
        var targetInputFile: File? = null

        val armAFile = File(benchmarkDir, "arm-a-ffmpeg.m4a")
        val armBFile = File(benchmarkDir, "arm-b-transformer.m4a")

        try {
            require(inputPathStr.isNotBlank()) { "Missing required inputPath argument" }
            require(expectedInputSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                "expectedInputSha256 must be a 64-char hex string: $expectedInputSha256"
            }

            val canonicalExtDir = extFilesDir.canonicalFile
            val inputFile = File(inputPathStr).canonicalFile
            check(inputFile.path.startsWith(canonicalExtDir.path + File.separator)) {
                "Input path must be a canonical child of externalFilesDir: ${inputFile.path} not under ${canonicalExtDir.path}"
            }
            check(inputFile.isFile && inputFile.length() > 0L) { "Input file must exist with positive size: ${inputFile.path}" }
            check(inputFile.length() <= 64L * 1024L * 1024L) { "Input file size ${inputFile.length()} exceeds 64MiB limit" }
            targetInputFile = inputFile

            val stat = StatFs(benchmarkDir.path)
            val availableBytes = stat.availableBytes
            val requiredStorage = 3L * inputFile.length() + 128L * 1024L * 1024L
            report.put("storage_available_before_bytes", availableBytes)
            check(availableBytes >= requiredStorage) {
                "Storage preflight failed: available ${availableBytes}B, required ${requiredStorage}B (3x input + 128MiB)"
            }

            beforeDigest = digest(inputFile)
            report.put("input_sha256_before", beforeDigest.sha256)
                .put("input_bytes_before", beforeDigest.bytes)
            check(beforeDigest.sha256.equals(expectedInputSha256, ignoreCase = true)) {
                "Input SHA-256 mismatch before run: expected $expectedInputSha256, observed ${beforeDigest.sha256}"
            }
            saveResult()

            val inProbe = checkNotNull(FFprobeKit.getMediaInformation(inputFile.path).mediaInformation) {
                "FFprobe failed to inspect input: ${inputFile.path}"
            }
            val inAudio = checkNotNull(inProbe.streams?.firstOrNull { it.type == "audio" }) {
                "Input contains no audio stream: ${inputFile.path}"
            }
            val inCodec = inAudio.codec?.lowercase().orEmpty()
            check(inCodec == "opus" || inCodec == "vorbis") {
                if (inCodec.contains("aac") || inCodec.contains("mp4a")) {
                    "Reject AAC input: codec '$inCodec' would be transmux, not encoding benchmark"
                } else {
                    "Unsupported input audio codec: '$inCodec' (must be opus or vorbis)"
                }
            }
            val inChannels = inAudio.allProperties.optInt("channels", 0)
            check(inChannels in 1..2) { "Unsupported channels: $inChannels (only 1..2 supported, downmix rejected)" }
            val inSampleRate = inAudio.allProperties.optString("sample_rate").toIntOrNull()
            check(inSampleRate != null && inSampleRate > 0) {
                "Malformed input sample rate: ${inAudio.allProperties.optString("sample_rate")}"
            }
            val inDuration = inProbe.duration?.toDoubleOrNull()
            check(inDuration != null && inDuration > 0.0) { "Malformed input duration: ${inProbe.duration}" }

            report.put("input_probe", JSONObject()
                .put("codec", inCodec)
                .put("channels", inChannels)
                .put("sample_rate", inSampleRate)
                .put("duration", inDuration))
            saveResult()

            // =========================================================================
            // ARM A: Native FFmpegKit software AAC baseline (AUDIO ONLY)
            // =========================================================================
            guardNoActiveSessions("pre-Arm A")
            val armAArgs = listOf(
                "-nostdin",
                "-y",
                "-v", "error",
                "-xerror",
                "-err_detect", "explode",
                "-protocol_whitelist", "file,pipe",
                "-i", inputFile.path,
                "-map", "0:a:0",
                "-vn",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", inSampleRate.toString(),
                "-ac", inChannels.toString(),
                "-movflags", "+faststart",
                armAFile.path
            )
            val armARecord = JSONObject()
                .put("output_file", armAFile.name)
                .put("absolute_output_path", armAFile.absolutePath)
                .put("command", JSONArray(armAArgs))
            report.put("arm_a_ffmpeg", armARecord)
            saveResult()

            val finishedA = CompletableDeferred<FFmpegSession>()
            val wallStartA = SystemClock.elapsedRealtime()
            val cpuStartA = Process.getElapsedCpuTime()

            val sessionA = FFmpegKit.executeWithArgumentsAsync(armAArgs.toTypedArray()) { session ->
                finishedA.complete(session)
            }

            var completedA: FFmpegSession? = null
            var timedOutA = false
            var unconfirmedA = false

            try {
                completedA = try {
                    withTimeout(180_000L) { finishedA.await() }
                } catch (te: TimeoutCancellationException) {
                    timedOutA = true
                    throw te
                }
                fsyncFile(armAFile)
            } finally {
                if (!finishedA.isCompleted) {
                    FFmpegKit.cancel(sessionA.sessionId)
                    val clean = withContext(NonCancellable) {
                        withTimeoutOrNull(10_000L) { finishedA.await() }
                    }
                    if (clean != null) completedA = clean else unconfirmedA = true
                } else if (completedA == null) {
                    completedA = withContext(NonCancellable) { finishedA.await() }
                }

                val wallMsA = SystemClock.elapsedRealtime() - wallStartA
                val cpuMsA = Process.getElapsedCpuTime() - cpuStartA
                val pssKbA = Debug.getPss()

                val retCodeA = completedA?.returnCode?.value
                val isSuccessA = !timedOutA && !unconfirmedA && completedA != null && ReturnCode.isSuccess(completedA.returnCode)
                val statusA = when {
                    timedOutA -> "timed_out"
                    unconfirmedA -> "unconfirmed_timeout"
                    isSuccessA -> "success"
                    completedA == null -> "cancelled_uncompleted"
                    else -> "failed"
                }

                armARecord.put("wall_ms", wallMsA)
                    .put("cpu_ms", cpuMsA)
                    .put("app_pss_kb", pssKbA)
                    .put("return_code", retCodeA ?: JSONObject.NULL)
                    .put("status", statusA)
                    .put("timed_out", timedOutA)
                    .put("unconfirmed_completion", unconfirmedA)
                    .put("logs_excerpt", completedA?.allLogsAsString.orEmpty().takeLast(1000))
                    .put("output_size_bytes", if (armAFile.isFile && !timedOutA && !unconfirmedA) armAFile.length() else 0L)
                saveResult()
            }

            check(!timedOutA) { "Arm A FFmpeg timed out after 180s" }
            check(!unconfirmedA) { "Arm A FFmpeg cancel unconfirmed after 10s wait" }
            check(completedA != null && !timedOutA && !unconfirmedA && ReturnCode.isSuccess(completedA.returnCode)) {
                "Arm A FFmpeg failed with return_code=${completedA?.returnCode?.value}: ${completedA?.allLogsAsString.orEmpty().takeLast(800)}"
            }
            check(armAFile.isFile && armAFile.length() > 0L) { "Arm A output missing or empty" }

            // =========================================================================
            // ARM B: Media3 Transformer forced audio AAC 192k output
            // =========================================================================
            guardNoActiveSessions("between arms")
            val armBRecord = JSONObject()
                .put("output_file", armBFile.name)
                .put("absolute_output_path", armBFile.absolutePath)
            report.put("arm_b_transformer", armBRecord)
            saveResult()

            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build()

            val finishedB = CompletableDeferred<ExportResult>()
            var exportExceptionB: ExportException? = null

            val listenerB = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finishedB.complete(exportResult)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    exportExceptionB = exportException
                    finishedB.completeExceptionally(exportException)
                }
            }

            var ownedTransformer: Transformer? = null
            var completedResultB: ExportResult? = null
            var timedOutB = false
            var cancelReturnedB = false
            var cancellationCompletionProvenB = false

            val wallStartB = SystemClock.elapsedRealtime()
            val cpuStartB = Process.getElapsedCpuTime()

            try {
                val audioSettings = AudioEncoderSettings.Builder().setBitrate(192_000).build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(audioSettings)
                    .setEnableFallback(false)
                    .build()

                withContext(Dispatchers.Main.immediate) {
                    val t = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactory)
                        .addListener(listenerB)
                        .build()
                    ownedTransformer = t
                    t.start(editedMediaItem, armBFile.path)
                }

                completedResultB = try {
                    withTimeout(180_000L) { finishedB.await() }
                } catch (te: TimeoutCancellationException) {
                    timedOutB = true
                    throw te
                }
                fsyncFile(armBFile)
            } finally {
                if (!finishedB.isCompleted && ownedTransformer != null) {
                    try {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            ownedTransformer?.cancel()
                        }
                        cancelReturnedB = true
                        cancellationCompletionProvenB = false
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                        cancelReturnedB = false
                        cancellationCompletionProvenB = false
                    }
                }

                val wallMsB = SystemClock.elapsedRealtime() - wallStartB
                val cpuMsB = Process.getElapsedCpuTime() - cpuStartB
                val pssKbB = Debug.getPss()

                val statusB = when {
                    timedOutB -> "timed_out"
                    completedResultB != null -> "success"
                    exportExceptionB != null -> "export_exception"
                    cancelReturnedB -> "cancelled"
                    else -> "failed"
                }

                armBRecord.put("wall_ms", wallMsB)
                    .put("cpu_ms", cpuMsB)
                    .put("app_pss_kb", pssKbB)
                    .put("status", statusB)
                    .put("timed_out", timedOutB)
                    .put("cancel_returned", cancelReturnedB)
                    .put("cancellation_completion_proven", cancellationCompletionProvenB)
                    .put("output_size_bytes", if (armBFile.isFile) armBFile.length() else 0L)

                val res = completedResultB
                if (res != null) {
                    armBRecord.put("export_result", JSONObject()
                        .put("audio_encoder_name", res.audioEncoderName ?: JSONObject.NULL)
                        .put("audio_decoder_name", "NOT_AVAILABLE_PUBLIC_API")
                        .put("approximate_duration_ms", res.approximateDurationMs)
                        .put("channel_count", res.channelCount)
                        .put("sample_rate", res.sampleRate)
                        .put("average_audio_bitrate", res.averageAudioBitrate)
                        .put("file_size_bytes", res.fileSizeBytes)
                        .put("hardware_inferred", false))
                }
                if (exportExceptionB != null) {
                    armBRecord.put("export_exception", "${exportExceptionB?.errorCodeName}:${exportExceptionB?.message}")
                }
                saveResult()
            }

            check(!timedOutB) { "Arm B Transformer timed out after 180s (cancel_returned=$cancelReturnedB, cancellation_completion_proven=false)" }
            check(completedResultB != null) {
                "Arm B Transformer export failed: ${exportExceptionB?.errorCodeName} - ${exportExceptionB?.message}"
            }
            check(armBFile.isFile && armBFile.length() > 0L) { "Arm B output missing or empty" }

            // =========================================================================
            // POST-BENCHMARK: Strict-A validation and independent FFprobe inspection
            // =========================================================================
            val validationFailures = mutableListOf<Throwable>()

            suspend fun inspectAndValidate(file: File, armRecord: JSONObject) {
                guardNoActiveSessions("before validating ${file.name}")
                armRecord.put("absolute_output_path", file.absolutePath)
                saveResult()

                val outDigest = digest(file)
                val probeInfo = checkNotNull(FFprobeKit.getMediaInformation(file.path).mediaInformation) {
                    "FFprobe failed to inspect output ${file.name}"
                }
                val outAudio = checkNotNull(probeInfo.streams?.firstOrNull { it.type == "audio" }) {
                    "Missing audio stream in ${file.name}"
                }
                val outCodec = outAudio.codec?.lowercase().orEmpty()
                val outChannels = outAudio.allProperties.optInt("channels", 0)
                val outRate = outAudio.allProperties.optString("sample_rate").toIntOrNull() ?: 0
                val outBitrate = outAudio.allProperties.optString("bit_rate").toLongOrNull()
                    ?: (probeInfo.allProperties.optJSONObject("format")?.optString("bit_rate")?.toLongOrNull() ?: 0L)
                val outDuration = probeInfo.duration?.toDoubleOrNull() ?: 0.0

                val probeRecord = JSONObject()
                    .put("sha256", outDigest.sha256)
                    .put("size_bytes", outDigest.bytes)
                    .put("codec", outCodec)
                    .put("channels", outChannels)
                    .put("sample_rate", outRate)
                    .put("bit_rate", outBitrate)
                    .put("duration", outDuration)
                armRecord.put("probe", probeRecord)
                saveResult()

                val valRecord = JSONObject().put("validated", false)
                armRecord.put("strict_a_validation", valRecord)
                saveResult()

                val valStart = SystemClock.elapsedRealtime()
                var valError: Throwable? = null
                try {
                    Processor().validate(
                        file = file,
                        audio = true,
                        requireAudio = true,
                        expectedContainer = "m4a",
                        expectedDuration = inDuration
                    )
                    valRecord.put("validated", true)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                    valError = e
                    valRecord.put("error", "${e.javaClass.simpleName}:${e.message}")
                } finally {
                    valRecord.put("wall_ms", SystemClock.elapsedRealtime() - valStart)
                    saveResult()
                }

                try {
                    if (valError != null) throw valError
                    check(outCodec == "aac") { "AAC codec required for ${file.name}, got $outCodec" }
                    check(outChannels == inChannels) { "Channel count mismatch in ${file.name}: expected $inChannels, got $outChannels" }
                    check(outRate == inSampleRate) { "Sample rate mismatch in ${file.name}: expected $inSampleRate, got $outRate" }
                    check(outDuration > 0.0) { "Non-positive duration in ${file.name}: $outDuration" }
                    check(outDigest.bytes > 0L) { "Non-positive size in ${file.name}: ${outDigest.bytes}" }
                    check(kotlin.math.abs(outBitrate - 192_000L) <= 32_000L) {
                        "Bitrate $outBitrate bps outside 192k +/- 32k policy in ${file.name}"
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                    validationFailures.add(e)
                    armRecord.put("validation_assertion_failure", "${e.javaClass.simpleName}:${e.message}")
                    saveResult()
                }
            }

            inspectAndValidate(armAFile, armARecord)
            inspectAndValidate(armBFile, armBRecord)

            if (validationFailures.isNotEmpty()) {
                val first = validationFailures.first()
                for (i in 1 until validationFailures.size) {
                    first.addSuppressed(validationFailures[i])
                }
                throw first
            }

            report.put("diagnostic_completed", true)
            saveResult()

        } catch (t: Throwable) {
            primaryFailure = t
            report.put("diagnostic_completed", false)
                .put("failure", "${t.javaClass.simpleName}:${t.message}")
            saveResult()
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            throw t
        } finally {
            withContext(NonCancellable) {
                var digestErr: Throwable? = null
                val afterDigest = try {
                    val f = targetInputFile
                    if (f != null && f.isFile) digest(f) else null
                } catch (de: Throwable) {
                    digestErr = de
                    null
                }
                val unchanged = (digestErr == null && afterDigest != null &&
                        beforeDigest != null &&
                        afterDigest.bytes == beforeDigest?.bytes &&
                        afterDigest.sha256.equals(beforeDigest?.sha256, ignoreCase = true))

                report.put("input_sha256_after", afterDigest?.sha256 ?: JSONObject.NULL)
                    .put("input_bytes_after", afterDigest?.bytes ?: JSONObject.NULL)
                    .put("input_unchanged_after", unchanged)
                    .put("total_wall_ms", SystemClock.elapsedRealtime() - benchmarkStartWall)
                    .put("total_cpu_ms", Process.getElapsedCpuTime() - benchmarkStartCpu)

                if (!unchanged && beforeDigest != null) {
                    report.put("diagnostic_completed", false)
                    val reason = when {
                        digestErr != null -> "digest_read_error: ${digestErr.javaClass.simpleName}:${digestErr.message}"
                        afterDigest == null -> "input_file_missing"
                        else -> "input_modified: expected ${beforeDigest?.sha256} (${beforeDigest?.bytes}B), observed ${afterDigest.sha256} (${afterDigest.bytes}B)"
                    }
                    report.put("input_integrity_failure", reason)
                }
                saveResult()

                if (!unchanged && beforeDigest != null) {
                    val integrityErr = AssertionError("Input integrity violation: ${report.optString("input_integrity_failure")}", digestErr)
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(integrityErr)
                    } else {
                        throw integrityErr
                    }
                }
            }
        }
    }
}
