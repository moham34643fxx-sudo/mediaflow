package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Processor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * On-device MediaCodec real-hardware evaluation test comparing against strict software decoding.
 * Evaluates runtime hardware codec capabilities and validates decoder behavior against Stage A
 * (Processor.validate baseline) and Stage B (strict FFmpeg video-only SW decode).
 */
class MediaCodecValidationEvaluationTest {

    private data class Digest(val bytes: Long, val sha256: String)

    private fun digest(file: File): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                bytes += n
            }
        }
        return Digest(bytes, md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    private fun conditions(context: Context): JSONObject {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also {
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val bat = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power?.currentThermalStatus ?: -1
        } else -1

        return JSONObject()
            .put("thermal_status", thermal)
            .put("power_save", power?.isPowerSaveMode ?: false)
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("available_memory_bytes", mem.availMem)
            .put("low_memory", mem.lowMemory)
            .put("battery_temperature_tenths_c", bat?.getIntExtra("temperature", -1) ?: -1)
            .put("battery_plugged", bat?.getIntExtra("plugged", -1) ?: -1)
    }

    private suspend fun executeOp(
        context: Context,
        record: JSONObject,
        args: List<String>,
        timeoutMs: Long = 180_000L
    ): FFmpegSession? {
        record.put("args", JSONArray(args)).put("conditions_before", conditions(context))
        val cpuBefore = Process.getElapsedCpuTime()
        val wallBefore = SystemClock.elapsedRealtime()
        val finished = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { finished.complete(it) })
        var completed: FFmpegSession? = null
        var timedOut = false
        var unconfirmed = false

        try {
            completed = try {
                withTimeout(timeoutMs) { finished.await() }
            } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                timedOut = true
                throw te
            }
        } finally {
            if (!finished.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                val clean = withContext(NonCancellable) { withTimeoutOrNull(10_000L) { finished.await() } }
                if (clean != null) completed = clean else unconfirmed = true
            } else if (completed == null) {
                completed = withContext(NonCancellable) { finished.await() }
            }
            val retCode = completed?.returnCode?.value
            val isSuccess = completed != null && ReturnCode.isSuccess(completed.returnCode)
            val status = when {
                unconfirmed -> "unconfirmed_timeout"
                timedOut -> "timed_out"
                completed == null -> "cancelled_uncompleted"
                isSuccess -> "success"
                else -> "failed"
            }
            record.put("wall_ms", SystemClock.elapsedRealtime() - wallBefore)
                .put("cpu_ms", Process.getElapsedCpuTime() - cpuBefore)
                .put("return_code", retCode ?: JSONObject.NULL)
                .put("completed", completed != null)
                .put("unconfirmed_completion", unconfirmed)
                .put("timed_out", timedOut)
                .put("status", status)
                .put("logs", completed?.allLogsAsString.orEmpty())
                .put("conditions_after", conditions(context))
        }
        check(!unconfirmed) { "FFmpeg operation timed out and native completion remained unconfirmed after 10s wait" }
        return completed
    }

    /**
     * Entrypoint 1: Records real runtime MediaCodec capabilities without benchmarking or asserting acceptance.
     */
    @Test
    fun recordsRuntimeCodecCapabilities(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")) { "Missing required evidenceRun argument" }
        require(run.matches(Regex("^[a-zA-Z0-9_-]{1,48}$"))) { "Invalid evidenceRun slug: $run" }

        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) { "externalFilesDir is null" }.canonicalFile
        val baseDir = File(externalRoot, "media-technology-evaluation")
        check(baseDir.mkdirs() || baseDir.isDirectory) { "Failed to create base directory: ${baseDir.absolutePath}" }
        val runDir = File(baseDir, "codec-$run")
        check(runDir.mkdir()) { "Directory collision refused: ${runDir.absolutePath} already exists or could not be created" }
        val reportFile = File(runDir, "report.json")

        val report = JSONObject()
            .put("scope", "real MediaCodec runtime capability observation")
            .put("evidence_run", run)
            .put("device_model", Build.MODEL)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("conditions_before", conditions(context))
            .put("candidate_accepted_for_production", false)
            .put("diagnostic_completed", false)
            .put("passed", false)

        fun saveReport() {
            val tmp = File(runDir, "report.json.tmp")
            tmp.writeText(report.toString(2))
            if (!tmp.renameTo(reportFile)) {
                reportFile.writeText(report.toString(2))
                tmp.delete()
            }
        }
        saveReport()

        val codecArray = JSONArray()
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val targetMimes = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")

            for (info in codecList.codecInfos) {
                val matchingMimes = info.supportedTypes.filter { t -> targetMimes.any { it.equals(t, ignoreCase = true) } }
                if (matchingMimes.isEmpty()) continue

                val cObj = JSONObject()
                cObj.put("name", info.name)
                cObj.put("is_encoder", info.isEncoder)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cObj.put("canonical_name", info.canonicalName)
                    cObj.put("is_hardware_accelerated", info.isHardwareAccelerated)
                    cObj.put("is_software_only", info.isSoftwareOnly)
                    cObj.put("is_vendor", info.isVendor)
                } else {
                    cObj.put("canonical_name", info.name)
                    cObj.put("is_hardware_accelerated", "unknown")
                    cObj.put("is_software_only", "unknown")
                    cObj.put("is_vendor", "unknown")
                }

                val typesArray = JSONArray()
                for (mime in matchingMimes) {
                    val tObj = JSONObject().put("mime", mime)
                    val caps = try {
                        info.getCapabilitiesForType(mime)
                    } catch (e: Exception) {
                        tObj.put("capabilities_query_error", "${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                    if (caps != null) {
                        val isSecureSupported = try { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) } catch (e: Exception) { null }
                        val isSecureRequired = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try { caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) } catch (e: Exception) { null }
                        } else null
                        val isTunneledSupported = try { caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) } catch (e: Exception) { null }
                        val isTunneledRequired = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try { caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) } catch (e: Exception) { null }
                        } else null

                        tObj.put("feature_secure_playback_supported", isSecureSupported ?: JSONObject.NULL)
                            .put("feature_secure_playback_required", isSecureRequired ?: JSONObject.NULL)
                            .put("feature_tunneled_playback_supported", isTunneledSupported ?: JSONObject.NULL)
                            .put("feature_tunneled_playback_required", isTunneledRequired ?: JSONObject.NULL)

                        val colorFormats = JSONArray()
                        caps.colorFormats.forEach { colorFormats.put(it) }
                        tObj.put("color_formats", colorFormats)

                        val profileLevels = JSONArray()
                        caps.profileLevels?.forEach { pl ->
                            profileLevels.put(JSONObject().put("profile", pl.profile).put("level", pl.level))
                        }
                        tObj.put("profile_levels", profileLevels)
                    }
                    typesArray.put(tObj)
                }
                cObj.put("supported_types", typesArray)
                codecArray.put(cObj)
            }

            check(codecArray.length() > 0) { "No target codecs discovered in MediaCodecList" }
            check(reportFile.isFile && reportFile.length() > 0L) { "Report file was not persisted" }

            report.put("codecs", codecArray)
            report.put("observation_note", "API actual runtime is authoritative. On older APIs unknown flags are recorded as unknown, not assumed HW. Capability observation does not constitute benchmark or production acceptance.")
            report.put("diagnostic_completed", true)
            report.put("passed", true)
        } catch (t: Throwable) {
            report.put("passed", false)
                .put("failure", "${t.javaClass.simpleName}:${t.message}")
                .put("failure_stack", t.stackTraceToString())
            throw t
        } finally {
            report.put("conditions_after", conditions(context))
            saveReport()
        }

        assertTrue("Capability listing must discover target video codecs", codecArray.length() > 0)
        assertTrue("Report file must exist and be non-empty", reportFile.isFile && reportFile.length() > 0L)
    }

    /**
     * Entrypoint 2: Compares MediaCodec hardware video-only decoder behavior against strict Stage A validator
     * and Stage B strict software FFmpeg video-only decoding.
     */
    @Test
    fun comparesDecoderBehaviorAgainstStrictA(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")) { "Missing required evidenceRun argument" }
        require(run.matches(Regex("^[a-zA-Z0-9_-]{1,48}$"))) { "Invalid evidenceRun slug: $run" }
        val inputPathArg = requireNotNull(args.getString("inputPath")) { "Missing required inputPath argument" }
        val expectedShaArg = requireNotNull(args.getString("expectedInputSha256")) { "Missing required expectedInputSha256 argument" }
        require(expectedShaArg.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Invalid SHA-256 format: $expectedShaArg" }
        val expectValid = args.getString("expectValid")?.toBooleanStrictOrNull() ?: true

        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) { "externalFilesDir is null" }.canonicalFile
        val rootPrefix = externalRoot.path + File.separator
        val baseDir = File(externalRoot, "media-technology-evaluation")
        check(baseDir.mkdirs() || baseDir.isDirectory) { "Failed to create base directory: ${baseDir.absolutePath}" }
        val runDir = File(baseDir, "codec-$run")
        check(runDir.mkdir()) { "Directory collision refused: ${runDir.absolutePath} already exists or could not be created" }
        val reportFile = File(runDir, "report.json")

        val report = JSONObject()
            .put("scope", "real-device MediaCodec vs strict SW validation evaluation")
            .put("evidence_run", run)
            .put("input_path", inputPathArg)
            .put("expected_valid", expectValid)
            .put("candidate_accepted_for_production", false)
            .put("diagnostic_completed", false)
            .put("passed", false)
            .put("conditions_before", conditions(context))

        fun saveReport() {
            val tmp = File(runDir, "report.json.tmp")
            tmp.writeText(report.toString(2))
            if (!tmp.renameTo(reportFile)) {
                reportFile.writeText(report.toString(2))
                tmp.delete()
            }
        }
        saveReport()

        val stageAObj = JSONObject()
        val stageBObj = JSONObject()
        val stageCObj = JSONObject()
        report.put("stage_A_strict_sw_validator", stageAObj)
        report.put("stage_B_strict_sw_video_only", stageBObj)
        report.put("stage_C_mediacodec", stageCObj)

        var candidateFile: File? = null
        var beforeDigest: Digest? = null
        var primaryFailure: Throwable? = null

        try {
            val fileCandidate = File(inputPathArg).canonicalFile
            check(fileCandidate.path.startsWith(rootPrefix)) {
                "Input path $fileCandidate must be a canonical descendant of target externalFilesDir $externalRoot"
            }
            check(fileCandidate.isFile && fileCandidate.length() > 0L) {
                "Input file missing or empty: ${fileCandidate.path}"
            }
            check(fileCandidate.length() <= 96L * 1024L * 1024L) {
                "Input file exceeds 96MiB limit: ${fileCandidate.length()} bytes"
            }
            candidateFile = fileCandidate

            val activeSessions = FFmpegKit.listSessions().filter {
                it.state == com.arthenica.ffmpegkit.SessionState.RUNNING || it.state == com.arthenica.ffmpegkit.SessionState.CREATED
            }
            check(activeSessions.isEmpty()) { "Guard failed: active FFmpeg native sessions already present (${activeSessions.size} active sessions)" }

            val digestBefore = digest(fileCandidate)
            beforeDigest = digestBefore
            assertEquals("Input SHA-256 mismatch before evaluation", expectedShaArg.lowercase(), digestBefore.sha256.lowercase())
            report.put("input_sha256_before", digestBefore.sha256).put("input_bytes_before", digestBefore.bytes)
            saveReport()

            val probeInfo = checkNotNull(FFprobeKit.getMediaInformation(fileCandidate.path).mediaInformation) { "FFprobe failed on input" }
            val vStream = probeInfo.streams?.firstOrNull { it.type == "video" }
            val inWidth = (vStream?.width as? Number)?.toInt()
            val inHeight = (vStream?.height as? Number)?.toInt()
            val inDuration = probeInfo.duration?.toDoubleOrNull()
            val inCodec = vStream?.codec
            val inContainer = if (fileCandidate.name.endsWith(".mp4", ignoreCase = true)) "mp4" else null

            report.put("input_probe", JSONObject()
                .put("width", inWidth ?: JSONObject.NULL)
                .put("height", inHeight ?: JSONObject.NULL)
                .put("duration", inDuration ?: JSONObject.NULL)
                .put("video_codec", inCodec ?: JSONObject.NULL)
                .put("container", inContainer ?: JSONObject.NULL))
            saveReport()

            // ----------------------------------------------------
            // STAGE A: Original public Processor().validate
            // ----------------------------------------------------
            val startWallA = SystemClock.elapsedRealtime()
            val startCpuA = Process.getElapsedCpuTime()
            var stageAPassed = false
            var stageAError: String? = null
            var stageAFailureCategory: String? = null
            var stageAValidationCheck: String? = null
            var isStrictDecoderRejection = false

            try {
                Processor().validate(
                    file = fileCandidate,
                    audio = false,
                    requireAudio = true,
                    expectedHeight = inHeight,
                    expectedDuration = inDuration,
                    expectedContainer = inContainer,
                    expectedWidth = inWidth
                )
                stageAPassed = true
            } catch (e: Exception) {
                if (e is CancellationException || e is InterruptedException) throw e
                stageAError = "${e.javaClass.simpleName}: ${e.message}"
                if (e is MediaException) {
                    if (e.code == ErrorCode.OUTPUT_VALIDATION_FAILED) {
                        stageAFailureCategory = "media_validation_rejection"
                        stageAValidationCheck = e.validationCheck
                        isStrictDecoderRejection = (e.validationCheck == "decode")
                    } else {
                        stageAFailureCategory = "resource_or_environment_failure"
                    }
                } else {
                    stageAFailureCategory = "unknown_failure"
                }
            } finally {
                val elapsedWallA = SystemClock.elapsedRealtime() - startWallA
                val elapsedCpuA = Process.getElapsedCpuTime() - startCpuA
                stageAObj.put("passed", stageAPassed)
                    .put("error", stageAError ?: JSONObject.NULL)
                    .put("failure_category", stageAFailureCategory ?: JSONObject.NULL)
                    .put("validation_check", stageAValidationCheck ?: JSONObject.NULL)
                    .put("is_strict_decoder_rejection", isStrictDecoderRejection)
                    .put("wall_ms", elapsedWallA)
                    .put("cpu_ms", elapsedCpuA)
                saveReport()
            }

            if (expectValid) {
                check(stageAPassed) { "Stage A strict SW validation failed on valid input: $stageAError" }
            } else {
                stageAObj.put("expected_rejection", !stageAPassed && stageAFailureCategory == "media_validation_rejection")
                stageAObj.put("unexpected_software_acceptance", stageAPassed)
                if (!stageAPassed && stageAFailureCategory != "media_validation_rejection") {
                    report.put("terminated_early", true)
                    report.put("termination_reason", "Stage A terminated with $stageAFailureCategory ($stageAError), not media validation rejection")
                    saveReport()
                    error("Stage A terminated with non-media validation failure: $stageAError")
                }
            }

            val postStageASessions = FFmpegKit.listSessions().filter {
                it.state == com.arthenica.ffmpegkit.SessionState.RUNNING || it.state == com.arthenica.ffmpegkit.SessionState.CREATED
            }
            check(postStageASessions.isEmpty()) { "Unfinished native FFmpeg sessions remain after Stage A (${postStageASessions.size} active sessions)" }

            // ----------------------------------------------------
            // STAGE B: VIDEO-ONLY strict SW decode via FFmpegKit
            // ----------------------------------------------------
            if (!expectValid) {
                stageBObj.put("status", "SKIPPED_FOR_CORRUPT_INPUT")
                saveReport()
            } else {
                val threadBudget = if (inCodec == "vp9" && (inDuration ?: 0.0) >= 600.0 && Runtime.getRuntime().availableProcessors() > 4) 4 else null
                val swArgs = mutableListOf(
                    "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none"
                )
                if (threadBudget != null) {
                    swArgs += listOf("-threads:v", threadBudget.toString())
                }
                swArgs += listOf(
                    "-protocol_whitelist", "file,pipe",
                    "-i", fileCandidate.path,
                    "-map", "0:v:0",
                    "-an",
                    "-max_error_rate", "0",
                    "-f", "null", "-"
                )

                val pssBeforeKb = Debug.getPss()
                val session = executeOp(context, stageBObj, swArgs, timeoutMs = 180_000L)
                val pssAfterKb = Debug.getPss()
                stageBObj.put("pss_before_kb", pssBeforeKb)
                stageBObj.put("pss_after_kb", pssAfterKb)

                val frameNumber = session?.getAllStatistics(1000)?.maxOfOrNull { it.videoFrameNumber } ?: 0
                stageBObj.put("video_frame_number", frameNumber)
                stageBObj.put("thread_budget", threadBudget ?: JSONObject.NULL)
                stageBObj.put("scope_note", "Stage B is VIDEO-ONLY decode. Stage A includes audio/probe/seek; video-only comparison must not be claimed whole-pipeline gain.")
                saveReport()

                check(session != null && ReturnCode.isSuccess(session.returnCode)) {
                    "Stage B strict SW video decode failed: return_code=${session?.returnCode?.value}\n${session?.allLogsAsString?.takeLast(500)}"
                }
                check(frameNumber > 0) { "Stage B reported zero frames decoded for valid video stream" }
            }

            // ----------------------------------------------------
            // STAGE C: MediaCodec VIDEO-ONLY ByteBuffer decode to EOS
            // ----------------------------------------------------
            var codec: MediaCodec? = null
            var codecStarted = false
            val extractor = MediaExtractor()
            var inputAccessUnits = 0L
            var outputFrames = 0L
            var minOutputPtsUs: Long? = null
            var maxOutputPtsUs: Long? = null
            var sawInputEos = false
            var sawOutputEos = false
            var actualOutputFormat: MediaFormat? = null
            val pssBeforeC = Debug.getPss()
            var pssAfterC = pssBeforeC
            var startWallC = 0L
            var elapsedWallC = 0L
            var startCpuC = 0L
            var elapsedCpuC = 0L
            var hwStatus = "INITIALIZED"
            var hwFailureStage = "SETUP"

            try {
                startWallC = SystemClock.elapsedRealtime()
                startCpuC = Process.getElapsedCpuTime()

                hwFailureStage = "PROBE_AND_SELECT"
                extractor.setDataSource(fileCandidate.path)
                var videoTrackIdx = -1
                var trackFormat: MediaFormat? = null
                var mime: String? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (m.startsWith("video/")) {
                        videoTrackIdx = i
                        trackFormat = f
                        mime = m
                        break
                    }
                }
                check(videoTrackIdx >= 0 && trackFormat != null && mime != null) {
                    "No video track found in ${fileCandidate.name}"
                }

                if (mime.equals("video/hevc", true) || mime.equals("video/av01", true)) {
                    hwStatus = "NOT_RUN"
                    stageCObj.put("status", "NOT_RUN")
                        .put("reason", "HEVC/AV1 actual decodes require fixtures not provided; reported as NOT_RUN")
                        .put("candidate_accepted", false)
                } else {
                    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    var selectedCodecInfo: MediaCodecInfo? = null
                    var selectedCaps: MediaCodecInfo.CodecCapabilities? = null

                    for (info in codecList.codecInfos) {
                        if (info.isEncoder) continue
                        if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                        if (info.name.endsWith(".secure", ignoreCase = true)) continue

                        val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            info.isHardwareAccelerated && !info.isSoftwareOnly
                        } else false
                        if (!isHw) continue

                        val caps = try { info.getCapabilitiesForType(mime) } catch (e: Exception) { continue }
                        val reqSecure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try { caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) } catch (e: Exception) { false }
                        } else false
                        val reqTunnel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try { caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) } catch (e: Exception) { false }
                        } else false
                        if (reqSecure || reqTunnel) continue

                        val flexYuv = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        if (caps.colorFormats.contains(flexYuv)) {
                            trackFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, flexYuv)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            if (!caps.isFormatSupported(trackFormat)) continue
                        }

                        selectedCodecInfo = info
                        selectedCaps = caps
                        break
                    }

                    if (selectedCodecInfo == null || selectedCaps == null) {
                        hwStatus = "UNSUPPORTED"
                        stageCObj.put("status", "UNSUPPORTED")
                            .put("candidate_accepted", false)
                            .put("reason", "No hardware-accelerated nonsecure decoder supporting configured format found")
                    } else {
                        val decoderName = selectedCodecInfo.name
                        stageCObj.put("decoder_name", decoderName)
                        extractor.selectTrack(videoTrackIdx)

                        hwFailureStage = "CONFIGURE"
                        codec = MediaCodec.createByCodecName(decoderName)
                        codec.configure(trackFormat, null, null, 0)
                        codec.start()
                        codecStarted = true
                        hwStatus = "RUNNING"
                        hwFailureStage = "DECODE_LOOP"

                        val decodeLoopStart = SystemClock.elapsedRealtime()
                        val bufferInfo = MediaCodec.BufferInfo()
                        val deadlineMs = SystemClock.elapsedRealtime() + 120_000L
                        val dequeueTimeoutUs = 10_000L

                        while (!sawOutputEos) {
                            currentCoroutineContext().ensureActive()
                            if (Thread.interrupted()) throw InterruptedException("Decode thread interrupted")

                            if (SystemClock.elapsedRealtime() > deadlineMs) {
                                hwStatus = "TIMED_OUT"
                                throw TimeoutException("MediaCodec decode loop exceeded 120s limit")
                            }
                            var didWork = false

                            if (!sawInputEos) {
                                val inIdx = codec.dequeueInputBuffer(dequeueTimeoutUs)
                                if (inIdx >= 0) {
                                    val inBuf = codec.getInputBuffer(inIdx)
                                        ?: error("Null input buffer at index $inIdx")
                                    inBuf.clear()

                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                                        hwStatus = "UNSUPPORTED_API_LEVEL"
                                        throw UnsupportedOperationException("MediaExtractor.sampleSize requires API 28+; safe buffer bound unavailable on API ${Build.VERSION.SDK_INT}")
                                    }

                                    val sampleSize = extractor.sampleSize
                                    if (sampleSize > 0 && sampleSize > inBuf.remaining()) {
                                        hwStatus = "UNSUPPORTED_SAMPLE_SIZE"
                                        throw UnsupportedOperationException("Extractor sample size $sampleSize exceeds input buffer capacity ${inBuf.remaining()}")
                                    }

                                    val readBytes = extractor.readSampleData(inBuf, 0)
                                    if (readBytes < 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        sawInputEos = true
                                    } else {
                                        val flags = extractor.sampleFlags
                                        if ((flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                                            hwStatus = "UNSUPPORTED"
                                            throw UnsupportedOperationException("Encrypted access unit encountered")
                                        }
                                        if ((flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                            hwStatus = "UNSUPPORTED"
                                            throw UnsupportedOperationException("Unexpected partial frame flag encountered")
                                        }
                                        val knownFlags = MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
                                        if ((flags and knownFlags.inv()) != 0) {
                                            hwStatus = "UNSUPPORTED"
                                            throw UnsupportedOperationException("Unexpected MediaExtractor flags: 0x${flags.toString(16)}")
                                        }

                                        val codecFlags = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                                        } else 0
                                        val sampleTime = extractor.sampleTime
                                        codec.queueInputBuffer(inIdx, 0, readBytes, sampleTime, codecFlags)
                                        inputAccessUnits++
                                        extractor.advance()
                                    }
                                    didWork = true
                                }
                            }

                            val outIdx = codec.dequeueOutputBuffer(bufferInfo, dequeueTimeoutUs)
                            if (outIdx >= 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    sawOutputEos = true
                                }
                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isCodecConfig && bufferInfo.size > 0) {
                                    outputFrames++
                                    val pts = bufferInfo.presentationTimeUs
                                    if (minOutputPtsUs == null || pts < minOutputPtsUs!!) minOutputPtsUs = pts
                                    if (maxOutputPtsUs == null || pts > maxOutputPtsUs!!) maxOutputPtsUs = pts
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                didWork = true
                            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                actualOutputFormat = codec.outputFormat
                                didWork = true
                            }

                            if (!didWork) {
                                Thread.sleep(5)
                            }
                        }

                        stageCObj.put("decode_loop_ms", SystemClock.elapsedRealtime() - decodeLoopStart)
                        hwStatus = if (inputAccessUnits > 0L && outputFrames > 0L) "COMPLETED" else "ZERO_FRAMES"
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is InterruptedException) throw e
                stageCObj.put("failure_stage", hwFailureStage)
                    .put("error_type", e.javaClass.simpleName)
                    .put("error_message", e.message.orEmpty())
                if (hwStatus == "RUNNING" || hwStatus == "INITIALIZED") {
                    hwStatus = when (e) {
                        is TimeoutException -> "TIMED_OUT"
                        is UnsupportedOperationException -> "UNSUPPORTED"
                        is MediaCodec.CodecException -> "CODEC_EXCEPTION"
                        else -> if (hwFailureStage == "CONFIGURE" || hwFailureStage == "PROBE_AND_SELECT" || hwFailureStage == "SETUP") "FAILED_SETUP" else "FAILED"
                    }
                }
                if (expectValid) throw e
            } finally {
                var releaseFailed = false
                val cleanupErrors = JSONArray()
                if (codecStarted) {
                    try { codec?.stop() } catch (ce: Exception) { cleanupErrors.put("codec_stop: ${ce.message}") }
                }
                try { codec?.release() } catch (ce: Exception) {
                    cleanupErrors.put("codec_release: ${ce.message}")
                    releaseFailed = true
                }
                try { extractor.release() } catch (ce: Exception) {
                    cleanupErrors.put("extractor_release: ${ce.message}")
                    releaseFailed = true
                }

                if (startWallC > 0L) {
                    elapsedWallC = SystemClock.elapsedRealtime() - startWallC
                    elapsedCpuC = Process.getElapsedCpuTime() - startCpuC
                    pssAfterC = Debug.getPss()
                }

                stageCObj.put("status", hwStatus)
                    .put("wall_ms", elapsedWallC)
                    .put("cpu_ms", elapsedCpuC)
                    .put("pss_before_kb", pssBeforeC)
                    .put("pss_after_kb", pssAfterC)
                    .put("input_access_units", inputAccessUnits)
                    .put("output_frame_buffers", outputFrames)
                    .put("min_output_pts_us", minOutputPtsUs ?: JSONObject.NULL)
                    .put("max_output_pts_us", maxOutputPtsUs ?: JSONObject.NULL)
                    .put("saw_input_eos", sawInputEos)
                    .put("saw_output_eos", sawOutputEos)
                    .put("output_format", actualOutputFormat?.toString() ?: JSONObject.NULL)
                    .put("remote_resource_accounting_disclaimer", "Remote mediaserver/codec HAL service CPU and memory are not measured by app process counters")

                if (cleanupErrors.length() > 0) stageCObj.put("cleanup_errors", cleanupErrors)
                if (releaseFailed) stageCObj.put("release_failed", true)
                if (releaseFailed || cleanupErrors.length() > 0) {
                    report.put("diagnostic_completed", false).put("passed", false)
                }
                saveReport()
            }

            if (!expectValid) {
                val provenStrictRejection = !stageAPassed && (stageAFailureCategory == "media_validation_rejection") && isStrictDecoderRejection
                val hwCompletedNonEmpty = (hwStatus == "COMPLETED" && sawOutputEos && outputFrames > 0L)

                val guaranteeMismatch: Any = if (provenStrictRejection && hwCompletedNonEmpty) {
                    true
                } else {
                    "NOT_ESTABLISHED"
                }

                val note = if (guaranteeMismatch == true) {
                    "MISMATCH: Proven strict SW decoder rejection (validationCheck=decode) was followed by non-empty MediaCodec completion to EOS ($outputFrames frames). Hardware completion is NOT a media-integrity pass; candidate cannot be accepted for production."
                } else {
                    "Comparison not established. SW failureCategory=$stageAFailureCategory (validationCheck=$stageAValidationCheck), HW status=$hwStatus (outputFrames=$outputFrames, sawEOS=$sawOutputEos). Hardware timeout/unsupported/setup failure cannot be equated with media-integrity rejection."
                }

                report.put("guarantee_mismatch", guaranteeMismatch)
                report.put("candidate_accepted_for_production", false)
                report.put("diagnostic_notes", note)
            } else {
                val swFrames = stageBObj.optLong("video_frame_number", -1L)
                val framesMatch = (swFrames > 0L && swFrames == outputFrames && hwStatus == "COMPLETED")
                report.put("frames_match", framesMatch)
                report.put("sw_video_frame_number", swFrames)
                report.put("hw_output_frame_buffers", outputFrames)
                report.put("candidate_accepted_for_production", false)
                report.put("guarantee_mismatch", !framesMatch)
            }

            check(!stageCObj.optBoolean("release_failed", false)) { "Codec or extractor release failed during cleanup" }
            check(stageCObj.optJSONArray("cleanup_errors")?.length() ?: 0 == 0) { "Codec cleanup encountered errors" }

            report.put("diagnostic_completed", true)
            report.put("passed", true)
            report.put("conditions_after", conditions(context))
            saveReport()

            assertTrue("Report file must exist and have content", reportFile.isFile && reportFile.length() > 0L)
            assertFalse("Stage B unconfirmed completion must not be present", stageBObj.optBoolean("unconfirmed_completion", false))
            if (expectValid) {
                assertTrue("Stage A must pass on valid input", stageAObj.optBoolean("passed", false))
                assertEquals("Stage B must succeed on valid input", "success", stageBObj.optString("status"))
                val swFrames = stageBObj.optLong("video_frame_number", 0L)
                assertTrue("Stage B must decode positive frames", swFrames > 0L)
            } else {
                assertTrue("Stage A verdict must be captured", stageAObj.has("passed"))
                assertTrue("Stage C status must be captured", stageCObj.has("status"))
            }
        } catch (t: Throwable) {
            primaryFailure = t
            report.put("passed", false).put("failure", "${t.javaClass.simpleName}:${t.message}").put("failure_stack", t.stackTraceToString())
            throw t
        } finally {
            withContext(NonCancellable) {
                var digestError: Throwable? = null
                val afterDigest = try {
                    if (candidateFile != null && candidateFile.isFile) digest(candidateFile) else null
                } catch (de: Throwable) {
                    digestError = de
                    null
                }
                val unchanged = digestError == null &&
                    afterDigest != null &&
                    beforeDigest != null &&
                    afterDigest.bytes == beforeDigest.bytes &&
                    afterDigest.sha256.equals(beforeDigest.sha256, ignoreCase = true)

                if (!unchanged) {
                    report.put("passed", false)
                    val reason = when {
                        digestError != null -> "digest_read_error: ${digestError.javaClass.simpleName}:${digestError.message}"
                        afterDigest == null -> "input_file_missing"
                        beforeDigest == null -> "before_digest_unavailable"
                        else -> "input_modified: expected ${beforeDigest.sha256} (${beforeDigest.bytes} bytes), observed ${afterDigest.sha256} (${afterDigest.bytes} bytes)"
                    }
                    report.put("input_integrity_failure", reason)
                }

                report.put("input_sha256_after", afterDigest?.sha256 ?: JSONObject.NULL)
                    .put("input_bytes_after", afterDigest?.bytes ?: JSONObject.NULL)
                    .put("input_unchanged_after", unchanged)
                saveReport()

                if (!unchanged) {
                    val failureMsg = report.optString("input_integrity_failure")
                    val integrityError = AssertionError("Input integrity failure: $failureMsg", digestError)
                    primaryFailure?.let { integrityError.addSuppressed(it) }
                    throw integrityError
                }
            }
        }
    }
}
