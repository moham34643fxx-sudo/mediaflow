package com.zdownloader.app

import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.BufferInfo as Media3BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.engine.Processor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Isolated real-device precision evaluation of Media3 Mp4Muxer and Android MediaMuxer
 * compared to native FFmpeg stream-copy baseline.
 */
class Media3MuxerEvaluationTest {

    private data class TrackEosPlan(
        val index: Int,
        val mime: String,
        val endTimeUs: Long,
        val exactNumerator: Long,
        val exactDenominator: Long
    )

    private data class TrackSampleMetrics(
        var sampleCount: Long = 0L,
        var minPtsUs: Long = Long.MAX_VALUE,
        var maxPtsUs: Long = Long.MIN_VALUE
    )

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                hash.update(buf, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun conditions(app: ZApplication): JSONObject {
        val power = app.getSystemService(android.os.PowerManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also {
            app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
        }
        val bat = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else -1
        val pssKb = Debug.getPss()
        return JSONObject()
            .put("thermal_status", thermal)
            .put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive)
            .put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("available_memory_bytes", mem.availMem)
            .put("low_memory", mem.lowMemory)
            .put("app_pss_kb", if (pssKb > 0L) pssKb else "not_measured")
            .put("battery_temperature_tenths_c", bat?.getIntExtra("temperature", -1) ?: -1)
            .put("battery_plugged", bat?.getIntExtra("plugged", -1) ?: -1)
    }

    private fun syncOutput(file: File) {
        if (file.isFile) {
            FileOutputStream(file, true).use { fos ->
                fos.channel.force(true)
            }
        }
    }

    private suspend fun runFFmpegBaseline(
        input: File,
        output: File,
        record: JSONObject,
        timeoutMs: Long = 120_000L
    ) {
        record.put("output_path", output.absolutePath)
        val activeSessions = FFmpegKit.listSessions()?.filter { it.returnCode == null }
        check(activeSessions.isNullOrEmpty()) {
            "Active FFmpeg sessions already running: ${activeSessions?.map { it.sessionId }}"
        }

        val args = listOf(
            "-y", "-v", "error", "-protocol_whitelist", "file,pipe",
            "-i", input.path, "-map", "0:v:0?", "-map", "0:a:0?",
            "-c", "copy", "-movflags", "+faststart", "-map_metadata", "-1", output.path
        )
        record.put("args", JSONArray(args))
        val finished = CompletableDeferred<FFmpegSession>()
        val startWall = SystemClock.elapsedRealtime()
        val startCpu = Process.getElapsedCpuTime()
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { finished.complete(it) }
        var completed: FFmpegSession? = null
        var timedOut = false
        var unconfirmed = false

        try {
            completed = withTimeout(timeoutMs) { finished.await() }
        } catch (te: TimeoutCancellationException) {
            timedOut = true
        } finally {
            if (!finished.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                val clean = withContext(NonCancellable) {
                    withTimeoutOrNull(10_000L) { finished.await() }
                }
                if (clean != null) completed = clean else unconfirmed = true
            } else if (completed == null) {
                completed = withContext(NonCancellable) { finished.await() }
            }
            if (completed != null && !timedOut && !unconfirmed && ReturnCode.isSuccess(completed.returnCode)) {
                syncOutput(output)
            }
        }

        val wallMs = SystemClock.elapsedRealtime() - startWall
        val cpuMs = Process.getElapsedCpuTime() - startCpu
        val retCode = completed?.returnCode?.value
        val isSuccess = completed != null && !timedOut && !unconfirmed && ReturnCode.isSuccess(completed.returnCode)

        record.put("wall_ms", wallMs)
            .put("cpu_ms", cpuMs)
            .put("completed", completed != null)
            .put("unconfirmed_completion", unconfirmed)
            .put("timed_out", timedOut)
            .put("return_code", retCode ?: JSONObject.NULL)
            .put("logs", completed?.allLogsAsString.orEmpty())

        if (isSuccess) {
            record.put("status", "success")
                .put("output_size_bytes", output.length())
                .put("output_sha256", digest(output))
        } else {
            val status = when {
                timedOut -> "timed_out"
                unconfirmed -> "unconfirmed_timeout"
                completed == null -> "cancelled_uncompleted"
                else -> "failed"
            }
            record.put("status", status)
        }

        check(!unconfirmed) { "FFmpeg baseline timed out and native completion remained unconfirmed after 10s wait" }
        check(!timedOut) { "FFmpeg baseline timed out after ${timeoutMs}ms" }
        check(isSuccess) { "FFmpeg baseline execution failed: return_code=$retCode" }
    }

    private fun runAndroidMediaMuxer(
        input: File,
        output: File,
        plans: Map<Int, TrackEosPlan>,
        selectedTracks: List<Int>,
        record: JSONObject
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("MediaExtractor.sampleSize requires API 28+ (current SDK: ${Build.VERSION.SDK_INT})")
        }

        record.put("output_path", output.absolutePath)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        val trackMap = mutableMapOf<Int, Int>()
        val metricsMap = mutableMapOf<Int, TrackSampleMetrics>()
        val startWall = SystemClock.elapsedRealtime()
        val startCpu = Process.getElapsedCpuTime()
        var runException: Throwable? = null

        try {
            extractor.setDataSource(input.path)
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (trackIdx in selectedTracks) {
                val format = extractor.getTrackFormat(trackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val plan = requireNotNull(plans[trackIdx]) { "Missing required EOS plan for track $trackIdx" }
                check(mime == plan.mime) { "MIME mismatch for track $trackIdx: extractor $mime vs plan ${plan.mime}" }

                extractor.selectTrack(trackIdx)
                if (mime.startsWith("video/")) {
                    val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
                    record.put("rotation_degrees", rotation)
                    if (rotation != 0) {
                        muxer.setOrientationHint(rotation)
                    }
                }
                val muxerTrack = muxer.addTrack(format)
                trackMap[trackIdx] = muxerTrack
                metricsMap[trackIdx] = TrackSampleMetrics()
            }

            muxer.start()
            muxerStarted = true

            val bufferCapacity = 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(bufferCapacity)
            val bufferInfo = MediaCodec.BufferInfo()
            val loopStart = SystemClock.elapsedRealtime()

            while (true) {
                if (SystemClock.elapsedRealtime() - loopStart > 120_000L) {
                    throw TimeoutException("Android MediaMuxer loop exceeded 120s deadline")
                }
                if (Thread.interrupted()) {
                    throw InterruptedException("Android MediaMuxer loop thread interrupted")
                }

                val sampleTrack = extractor.sampleTrackIndex
                if (sampleTrack < 0) break
                if (sampleTrack !in trackMap) {
                    extractor.advance()
                    continue
                }

                val flags = extractor.sampleFlags
                check((flags and MediaExtractor.SAMPLE_FLAG_SYNC.inv()) == 0) {
                    "Unexpected flags on track $sampleTrack: $flags (rejecting encrypted/partial/codec-config flags)"
                }

                val sampleSize: Long = extractor.sampleSize
                if (sampleSize < 0L) break
                check(sampleSize <= bufferCapacity.toLong()) {
                    "Oversize sample on track $sampleTrack: $sampleSize bytes exceeds capacity $bufferCapacity"
                }

                buffer.clear()
                val readSize = extractor.readSampleData(buffer, 0)
                if (readSize < 0) break

                val sampleTime = extractor.sampleTime
                val muxerFlags = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0

                bufferInfo.set(0, readSize, sampleTime, muxerFlags)
                muxer.writeSampleData(trackMap.getValue(sampleTrack), buffer, bufferInfo)

                val tm = metricsMap.getValue(sampleTrack)
                tm.sampleCount++
                tm.minPtsUs = minOf(tm.minPtsUs, sampleTime)
                tm.maxPtsUs = maxOf(tm.maxPtsUs, sampleTime)

                extractor.advance()
            }

            // Verify non-empty tracks and write exact EOS per track from plan
            val trackMetricsJson = JSONObject()
            for (trackIdx in selectedTracks) {
                val tm = metricsMap.getValue(trackIdx)
                val plan = requireNotNull(plans[trackIdx])
                check(tm.sampleCount > 0L) { "Track $trackIdx has 0 samples" }
                check(plan.endTimeUs > tm.maxPtsUs) {
                    "Planned EOS ${plan.endTimeUs} <= max real PTS ${tm.maxPtsUs} on track $trackIdx"
                }

                trackMetricsJson.put(trackIdx.toString(), JSONObject()
                    .put("sample_count", tm.sampleCount)
                    .put("min_pts_us", tm.minPtsUs)
                    .put("max_pts_us", tm.maxPtsUs)
                    .put("planned_end_time_us", plan.endTimeUs)
                )

                val eosInfo = MediaCodec.BufferInfo()
                eosInfo.set(0, 0, plan.endTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                buffer.clear()
                muxer.writeSampleData(trackMap.getValue(trackIdx), buffer, eosInfo)
            }
            record.put("track_metrics", trackMetricsJson)

            muxer.stop()
            muxerStopped = true
            muxer.release()
            muxer = null
            syncOutput(output)

            val wallMs = SystemClock.elapsedRealtime() - startWall
            val cpuMs = Process.getElapsedCpuTime() - startCpu
            record.put("wall_ms", wallMs).put("cpu_ms", cpuMs)
            record.put("status", "success")
        } catch (t: Throwable) {
            runException = t
            if (t is InterruptedException || t is CancellationException) throw t
            record.put("status", "failed").put("error", "${t.javaClass.simpleName}:${t.message}")
        } finally {
            var stopError: Throwable? = null
            var releaseError: Throwable? = null
            var extractorError: Throwable? = null

            try {
                if (muxerStarted && !muxerStopped && muxer != null) {
                    muxer.stop()
                    muxerStopped = true
                }
            } catch (se: Throwable) {
                stopError = se
            }
            try {
                muxer?.release()
                muxer = null
            } catch (re: Throwable) {
                releaseError = re
            }
            try {
                extractor.release()
            } catch (ee: Throwable) {
                extractorError = ee
            }

            if (!record.has("wall_ms")) {
                record.put("wall_ms", SystemClock.elapsedRealtime() - startWall)
                    .put("cpu_ms", Process.getElapsedCpuTime() - startCpu)
            }

            val cleanupError = stopError ?: releaseError ?: extractorError
            if (cleanupError != null) {
                record.put("status", "failed")
                    .put("cleanup_error", "${cleanupError.javaClass.simpleName}:${cleanupError.message}")
            }

            if (record.optString("status") == "success" && output.isFile) {
                record.put("output_size_bytes", output.length())
                    .put("output_sha256", digest(output))
            }

            if (cleanupError is Error) throw cleanupError
            if (cleanupError != null) {
                throw IllegalStateException("Android MediaMuxer cleanup failed", cleanupError)
            }
            if (runException is Error) throw runException
        }
    }

    private fun runMedia3Mp4Muxer(
        input: File,
        output: File,
        plans: Map<Int, TrackEosPlan>,
        selectedTracks: List<Int>,
        record: JSONObject
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("MediaExtractor.sampleSize requires API 28+ (current SDK: ${Build.VERSION.SDK_INT})")
        }

        record.put("output_path", output.absolutePath)
        val extractor = MediaExtractor()
        var muxer: Mp4Muxer? = null
        var muxerClosed = false
        val trackMap = mutableMapOf<Int, Int>()
        val metricsMap = mutableMapOf<Int, TrackSampleMetrics>()
        val startWall = SystemClock.elapsedRealtime()
        val startCpu = Process.getElapsedCpuTime()
        var runException: Throwable? = null

        try {
            extractor.setDataSource(input.path)
            muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(output.path))
                .setLastSampleDurationBehavior(
                    Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS
                )
                .setSampleBatchingEnabled(false)
                .build()

            for (trackIdx in selectedTracks) {
                val format = extractor.getTrackFormat(trackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val plan = requireNotNull(plans[trackIdx]) { "Missing required EOS plan for track $trackIdx" }
                check(mime == plan.mime) { "MIME mismatch for track $trackIdx: extractor $mime vs plan ${plan.mime}" }

                extractor.selectTrack(trackIdx)
                if (mime.startsWith("video/")) {
                    val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
                    record.put("rotation_degrees", rotation)
                    if (rotation != 0) {
                        throw UnsupportedOperationException("Media3 Mp4Muxer non-zero rotation ($rotation) is unsupported in this evaluation")
                    }
                }
                val media3Format = MediaFormatUtil.createFormatFromMediaFormat(format)
                val muxerTrack = muxer.addTrack(media3Format)
                trackMap[trackIdx] = muxerTrack
                metricsMap[trackIdx] = TrackSampleMetrics()
            }

            val bufferCapacity = 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(bufferCapacity)
            val loopStart = SystemClock.elapsedRealtime()

            while (true) {
                if (SystemClock.elapsedRealtime() - loopStart > 120_000L) {
                    throw TimeoutException("Media3 Mp4Muxer loop exceeded 120s deadline")
                }
                if (Thread.interrupted()) {
                    throw InterruptedException("Media3 Mp4Muxer loop thread interrupted")
                }

                val sampleTrack = extractor.sampleTrackIndex
                if (sampleTrack < 0) break
                if (sampleTrack !in trackMap) {
                    extractor.advance()
                    continue
                }

                val flags = extractor.sampleFlags
                check((flags and MediaExtractor.SAMPLE_FLAG_SYNC.inv()) == 0) {
                    "Unexpected flags on track $sampleTrack: $flags (rejecting encrypted/partial/codec-config flags)"
                }

                val sampleSize: Long = extractor.sampleSize
                if (sampleSize < 0L) break
                check(sampleSize <= bufferCapacity.toLong()) {
                    "Oversize sample on track $sampleTrack: $sampleSize bytes exceeds capacity $bufferCapacity"
                }

                buffer.clear()
                val readSize = extractor.readSampleData(buffer, 0)
                if (readSize < 0) break

                val sampleTime = extractor.sampleTime
                val media3Flags = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    C.BUFFER_FLAG_KEY_FRAME
                } else 0

                val bufferInfo = Media3BufferInfo(sampleTime, readSize, media3Flags)
                buffer.position(0)
                buffer.limit(readSize)
                muxer.writeSampleData(trackMap.getValue(sampleTrack), buffer, bufferInfo)

                val tm = metricsMap.getValue(sampleTrack)
                tm.sampleCount++
                tm.minPtsUs = minOf(tm.minPtsUs, sampleTime)
                tm.maxPtsUs = maxOf(tm.maxPtsUs, sampleTime)

                extractor.advance()
            }

            // Verify non-empty tracks and write exact EOS per track from plan
            val trackMetricsJson = JSONObject()
            val emptyBuffer = ByteBuffer.allocateDirect(0)
            for (trackIdx in selectedTracks) {
                val tm = metricsMap.getValue(trackIdx)
                val plan = requireNotNull(plans[trackIdx])
                check(tm.sampleCount > 0L) { "Track $trackIdx has 0 samples" }
                check(plan.endTimeUs > tm.maxPtsUs) {
                    "Planned EOS ${plan.endTimeUs} <= max real PTS ${tm.maxPtsUs} on track $trackIdx"
                }

                trackMetricsJson.put(trackIdx.toString(), JSONObject()
                    .put("sample_count", tm.sampleCount)
                    .put("min_pts_us", tm.minPtsUs)
                    .put("max_pts_us", tm.maxPtsUs)
                    .put("planned_end_time_us", plan.endTimeUs)
                )

                val eosInfo = Media3BufferInfo(plan.endTimeUs, 0, C.BUFFER_FLAG_END_OF_STREAM)
                emptyBuffer.clear()
                muxer.writeSampleData(trackMap.getValue(trackIdx), emptyBuffer, eosInfo)
            }
            record.put("track_metrics", trackMetricsJson)

            muxer.close()
            muxerClosed = true
            muxer = null
            syncOutput(output)

            val wallMs = SystemClock.elapsedRealtime() - startWall
            val cpuMs = Process.getElapsedCpuTime() - startCpu
            record.put("wall_ms", wallMs).put("cpu_ms", cpuMs)
            record.put("status", "success")
        } catch (t: Throwable) {
            runException = t
            if (t is InterruptedException || t is CancellationException) throw t
            record.put("status", "failed").put("error", "${t.javaClass.simpleName}:${t.message}")
        } finally {
            var closeError: Throwable? = null
            var extractorError: Throwable? = null

            try {
                if (muxer != null && !muxerClosed) {
                    muxer.close()
                    muxerClosed = true
                    muxer = null
                }
            } catch (ce: Throwable) {
                closeError = ce
            }
            try {
                extractor.release()
            } catch (ee: Throwable) {
                extractorError = ee
            }

            if (!record.has("wall_ms")) {
                record.put("wall_ms", SystemClock.elapsedRealtime() - startWall)
                    .put("cpu_ms", Process.getElapsedCpuTime() - startCpu)
            }

            val cleanupError = closeError ?: extractorError
            if (cleanupError != null) {
                record.put("status", "failed")
                    .put("cleanup_error", "${cleanupError.javaClass.simpleName}:${cleanupError.message}")
            }

            if (record.optString("status") == "success" && output.isFile) {
                record.put("output_size_bytes", output.length())
                    .put("output_sha256", digest(output))
            }

            if (cleanupError is Error) throw cleanupError
            if (cleanupError != null) {
                throw IllegalStateException("Media3 Mp4Muxer cleanup failed", cleanupError)
            }
            if (runException is Error) throw runException
        }
    }

    @Test
    fun evaluatesMuxerPrecisionWithoutChangingProduction(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()

        val evidenceRun = requireNotNull(args.getString("evidenceRun")) { "Missing evidenceRun argument" }
        require(evidenceRun.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid evidenceRun slug: $evidenceRun" }
        val inputPath = requireNotNull(args.getString("inputPath")) { "Missing inputPath argument" }
        val expectedInputSha256 = requireNotNull(args.getString("expectedInputSha256")) { "Missing expectedInputSha256" }
        val eosPlanPath = requireNotNull(args.getString("eosPlanPath")) { "Missing eosPlanPath argument" }
        val eosPlanSha256 = requireNotNull(args.getString("eosPlanSha256")) { "Missing eosPlanSha256" }
        val phase = args.getString("phase") ?: "precision"
        require(phase == "precision") { "Current evaluation scope supports phase=precision only, got: $phase" }

        val externalFilesDir = checkNotNull(app.getExternalFilesDir(null)) { "externalFilesDir is null" }.canonicalFile
        val baseDir = File(externalFilesDir, "media-technology-evaluation")
        check(baseDir.mkdirs() || baseDir.isDirectory) { "Failed to create base evaluation directory" }
        val evalDir = File(baseDir, "mux-$evidenceRun")
        check(evalDir.mkdir()) { "Collision refusal: evaluation directory already exists: ${evalDir.path}" }
        val resultFile = File(evalDir, "result.json")

        val report = JSONObject()
            .put("scope", "Media3/AndroidMuxer real-device precision evaluation")
            .put("phase", phase)
            .put("evidence_run", evidenceRun)
            .put("candidateAcceptedForProduction", false)
            .put("candidateRequiresHostPacketAndEosAudit", true)
            .put("unresolvedHistorical1msDiscrepancy", true)
            .put("evaluation_phase_completed", false)
            .put("conditions_before", conditions(app))

        fun saveResult() {
            val tmp = File(evalDir, "result.json.tmp")
            tmp.writeText(report.toString(2))
            if (!tmp.renameTo(resultFile)) {
                resultFile.writeText(report.toString(2))
                tmp.delete()
            }
        }
        saveResult()

        val inputFile = File(inputPath).canonicalFile
        val planFile = File(eosPlanPath).canonicalFile
        var primaryError: Throwable? = null

        try {
            check(inputFile.path.startsWith(externalFilesDir.path + File.separator)) {
                "inputPath must be canonical descendant of externalFilesDir: ${inputFile.path}"
            }
            check(planFile.path.startsWith(externalFilesDir.path + File.separator)) {
                "eosPlanPath must be canonical descendant of externalFilesDir: ${planFile.path}"
            }
            check(inputFile.isFile && inputFile.length() > 0L && inputFile.length() <= 96L * 1024L * 1024L) {
                "Input file missing or exceeds 96MiB limit (${inputFile.length()} bytes)"
            }
            check(planFile.isFile && planFile.length() > 0L && planFile.length() <= 64L * 1024L) {
                "Plan file missing or exceeds 64KiB limit (${planFile.length()} bytes)"
            }

            val stat = StatFs(evalDir.path)
            val requiredHeadroom = 4L * inputFile.length() + 128L * 1024L * 1024L
            check(stat.availableBytes >= requiredHeadroom) {
                "Storage preflight failed: ${stat.availableBytes} available, requires >= $requiredHeadroom bytes"
            }

            // Separate warm-up SHA-256 verification before timed muxing
            val inputShaBefore = digest(inputFile)
            assertEquals("Source input SHA-256 mismatch", expectedInputSha256.lowercase(), inputShaBefore.lowercase())
            val planShaBefore = digest(planFile)
            assertEquals("EOS plan SHA-256 mismatch", eosPlanSha256.lowercase(), planShaBefore.lowercase())
            report.put("input_sha256_before", inputShaBefore)
            report.put("plan_sha256_before", planShaBefore)
            saveResult()

            // Parse and strictly validate EOS plan
            val planJson = JSONObject(planFile.readText())
            assertEquals("EOS plan input_sha256 mismatch", expectedInputSha256.lowercase(), planJson.getString("input_sha256").lowercase())
            val planTracksArray = planJson.getJSONArray("tracks")
            check(planTracksArray.length() > 0) { "EOS plan contains no tracks" }
            val trackPlans = mutableMapOf<Int, TrackEosPlan>()
            val reportedPlans = JSONArray()

            for (i in 0 until planTracksArray.length()) {
                val tObj = planTracksArray.getJSONObject(i)
                val idx = tObj.getInt("index")
                check(idx !in trackPlans) { "Duplicate track index in EOS plan: $idx" }
                val mime = tObj.getString("mime")
                val endUs = tObj.getLong("end_time_us")
                val num = tObj.getLong("exact_end_numerator")
                val den = tObj.getLong("exact_end_denominator")

                check(den > 0L) { "exact_end_denominator must be positive for track $idx: $den" }
                check(num > 0L) { "exact_end_numerator must be positive for track $idx: $num" }
                check(endUs > 0L) { "end_time_us must be positive for track $idx: $endUs" }

                val calculatedUs = (num.toDouble() * 1_000_000.0) / den.toDouble()
                check(kotlin.math.abs(calculatedUs - endUs.toDouble()) <= 0.5) {
                    "Rational endpoint mismatch for track $idx: calculated $calculatedUs vs end_time_us $endUs"
                }

                val p = TrackEosPlan(idx, mime, endUs, num, den)
                trackPlans[idx] = p
                reportedPlans.put(JSONObject()
                    .put("index", idx)
                    .put("mime", mime)
                    .put("end_time_us", endUs)
                    .put("exact_end_numerator", num)
                    .put("exact_end_denominator", den)
                )
            }
            report.put("eos_plans", reportedPlans)
            saveResult()

            // Preflight check: inspect input streams and validate exact correspondence and MIME to plan
            val extractorProbe = MediaExtractor()
            val selectedTracks = mutableListOf<Int>()
            try {
                extractorProbe.setDataSource(inputFile.path)
                for (i in 0 until extractorProbe.trackCount) {
                    val fmt = extractorProbe.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        selectedTracks.add(i)
                        val plan = checkNotNull(trackPlans[i]) { "Missing required EOS plan for selected AV track $i ($mime)" }
                        check(mime == plan.mime) { "Preflight MIME mismatch on track $i: extracted '$mime' vs plan '${plan.mime}'" }
                    }
                }
            } finally {
                extractorProbe.release()
            }

            check(selectedTracks.isNotEmpty()) { "No AV tracks found in input" }
            check(selectedTracks.toSet() == trackPlans.keys) {
                "Selected AV track indices $selectedTracks do not match planned indices ${trackPlans.keys}"
            }

            val inputInfo = checkNotNull(FFprobeKit.getMediaInformation(inputFile.path).mediaInformation) { "FFprobe input failed" }
            val inVideo = inputInfo.streams.firstOrNull { it.type == "video" }
            val inAudio = inputInfo.streams.firstOrNull { it.type == "audio" }
            val inWidth = inVideo?.width?.toInt()
            val inHeight = inVideo?.height?.toInt()
            val inVideoCodec = inVideo?.codec

            val expectedDurationSec = trackPlans.values.maxOf { it.endTimeUs } / 1_000_000.0
            val outputs = JSONObject()
            report.put("outputs", outputs)
            saveResult()

            val ffmpegOut = File(evalDir, "ffmpeg-streamcopy.mp4")
            val androidOut = File(evalDir, "android-mediamuxer.mp4")
            val media3Out = File(evalDir, "media3-mp4muxer.mp4")

            // Backend 1: FFmpeg Baseline
            val ffmpegRecord = JSONObject()
            outputs.put("ffmpeg_streamcopy", ffmpegRecord)
            saveResult()
            runFFmpegBaseline(inputFile, ffmpegOut, ffmpegRecord)
            saveResult()

            // Backend 2: Android MediaMuxer (API 28+ required for sample inspection; AVC/HEVC + AAC only; VP9 MP4 explicitly ineligible)
            val androidRecord = JSONObject()
            outputs.put("android_mediamuxer", androidRecord)
            val isAndroidEligible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                inVideoCodec in listOf("h264", "hevc") &&
                (inAudio == null || inAudio.codec == "aac")
            androidRecord.put("eligible", isAndroidEligible)
            if (!isAndroidEligible) {
                val reason = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    "Android MediaMuxer evaluation requires API 28+ for sample size inspection (current SDK: ${Build.VERSION.SDK_INT})"
                } else {
                    "Android MediaMuxer does not officially support $inVideoCodec in MP4 container"
                }
                androidRecord.put("status", "ineligible").put("reason", reason)
            } else {
                runAndroidMediaMuxer(inputFile, androidOut, trackPlans, selectedTracks, androidRecord)
            }
            saveResult()

            // Backend 3: Media3 Mp4Muxer
            val media3Record = JSONObject()
            outputs.put("media3_mp4muxer", media3Record)
            saveResult()
            runMedia3Mp4Muxer(inputFile, media3Out, trackPlans, selectedTracks, media3Record)
            saveResult()

            // Strict-A validation: run Processor().validate for EACH completed output
            val validationRecord = JSONObject()
            report.put("validation", validationRecord)
            saveResult()

            val processor = Processor()
            val candidateOutputs = listOf(
                Triple("ffmpeg_streamcopy", ffmpegOut, ffmpegRecord),
                Triple("android_mediamuxer", androidOut, androidRecord),
                Triple("media3_mp4muxer", media3Out, media3Record)
            )

            var anyValidationFailed = false
            for ((label, outFile, rec) in candidateOutputs) {
                if (rec.optString("status") != "success" || !outFile.isFile || outFile.length() == 0L) {
                    continue
                }
                val vRec = JSONObject().put("target", outFile.name).put("validated", false)
                validationRecord.put(label, vRec)
                val vStartWall = SystemClock.elapsedRealtime()
                val vStartCpu = Process.getElapsedCpuTime()
                try {
                    processor.validate(
                        file = outFile,
                        audio = false,
                        requireAudio = inAudio != null,
                        expectedHeight = inHeight,
                        expectedDuration = expectedDurationSec,
                        expectedContainer = "mp4",
                        expectedWidth = inWidth,
                        expectedVideoCodec = inVideoCodec
                    )
                    vRec.put("validated", true)
                } catch (e: Exception) {
                    if (e is InterruptedException || e is CancellationException) throw e
                    anyValidationFailed = true
                    vRec.put("validated", false).put("error", "${e.javaClass.simpleName}:${e.message}")
                } finally {
                    vRec.put("wall_ms", SystemClock.elapsedRealtime() - vStartWall)
                        .put("cpu_ms", Process.getElapsedCpuTime() - vStartCpu)
                    saveResult()
                }
            }

            val ffmpegOk = ffmpegRecord.optString("status") == "success" && validationRecord.optJSONObject("ffmpeg_streamcopy")?.optBoolean("validated") == true
            val androidOk = !isAndroidEligible || (androidRecord.optString("status") == "success" && validationRecord.optJSONObject("android_mediamuxer")?.optBoolean("validated") == true)
            val media3Ok = media3Record.optString("status") == "success" && validationRecord.optJSONObject("media3_mp4muxer")?.optBoolean("validated") == true

            check(ffmpegOk) { "FFmpeg baseline failed or failed strict validation" }
            check(androidOk) { "Android MediaMuxer was eligible but failed or failed strict validation" }
            check(media3Ok) { "Media3 Mp4Muxer failed or failed strict validation" }
            check(!anyValidationFailed) { "One or more completed outputs failed strict validation" }

            report.put("evaluation_phase_completed", true)
            saveResult()
        } catch (t: Throwable) {
            primaryError = t
            report.put("evaluation_phase_completed", false)
                .put("failure", "${t.javaClass.simpleName}:${t.message}")
                .put("failure_stack", t.stackTraceToString())
            saveResult()
            throw t
        } finally {
            withContext(NonCancellable) {
                var digestError: Throwable? = null
                val inputShaAfter = try { if (inputFile.isFile) digest(inputFile) else "missing" } catch (de: Throwable) { digestError = de; "error" }
                val planShaAfter = try { if (planFile.isFile) digest(planFile) else "missing" } catch (pe: Throwable) { digestError = digestError ?: pe; "error" }
                val inputIntact = inputShaAfter.equals(expectedInputSha256, ignoreCase = true)
                val planIntact = planShaAfter.equals(eosPlanSha256, ignoreCase = true)

                report.put("conditions_after", conditions(app))
                    .put("input_sha256_after", inputShaAfter)
                    .put("input_intact", inputIntact)
                    .put("plan_sha256_after", planShaAfter)
                    .put("plan_intact", planIntact)
                saveResult()

                if (!inputIntact || !planIntact) {
                    val integrityMsg = "Integrity failure: inputIntact=$inputIntact (after=$inputShaAfter), planIntact=$planIntact (after=$planShaAfter)"
                    val integrityEx = AssertionError(integrityMsg, digestError)
                    if (primaryError != null) {
                        primaryError.addSuppressed(integrityEx)
                    } else {
                        throw integrityEx
                    }
                }
            }
        }
    }
}
