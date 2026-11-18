package com.zdownloader.app

import android.content.Intent
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.engine.Processor
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * On-phone diagnostic separation and candidate comparison of native mux/audio-conversion region.
 * Uses real FFmpegKit and retained media. Never modifies production code or app configuration.
 */
class MuxAudioConversionBenchmarkTest {

    private data class Digest(val bytes: Long, val sha256: String)
    private data class Mp4Atom(val type: String, val offset: Long, val size: Long)
    private data class FramehashData(val headers: List<String>, val packets: List<String>, val packetCount: Int, val packetsSha: String, val headersSha: String)

    private fun digest(file: File): Digest {
        val hash = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val size = input.read(buffer); if (size < 0) break
                hash.update(buffer, 0, size); bytes += size
            }
        }
        return Digest(bytes, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun readProcIo(): Map<String, Long>? {
        val file = File("/proc/self/io"); if (!file.canRead()) return null
        return try {
            val map = mutableMapOf<String, Long>()
            file.forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim().toLong()
            }
            val req = setOf("rchar", "wchar", "syscr", "syscw", "read_bytes", "write_bytes", "cancelled_write_bytes")
            if (req.all { it in map }) map else null
        } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null } catch (_: NumberFormatException) { null }
    }

    private fun ioDeltaJson(before: Map<String, Long>?, after: Map<String, Long>?): JSONObject {
        if (before == null || after == null) return JSONObject().put("readable", false).put("status", "unknown").put("reason", if (before == null) "before_unreadable" else "after_unreadable")
        val deltas = JSONObject()
        for ((k, v) in after) before[k]?.let { deltas.put("${k}_delta", v - it) }
        return JSONObject().put("readable", true).put("deltas", deltas)
    }

    private fun conditions(app: ZApplication): JSONObject {
        val power = app.getSystemService(android.os.PowerManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val bat = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("thermal_status", power.currentThermalStatus).put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive).put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("available_processors", Runtime.getRuntime().availableProcessors()).put("available_memory_bytes", mem.availMem)
            .put("low_memory", mem.lowMemory).put("battery_temperature_tenths_c", bat?.getIntExtra("temperature", -1) ?: -1)
            .put("battery_plugged", bat?.getIntExtra("plugged", -1) ?: -1)
    }

    private suspend fun executeOp(app: ZApplication, record: JSONObject, args: List<String>, timeoutMs: Long = 180_000L) {
        record.put("args", JSONArray(args)).put("conditions_before", conditions(app))
        val ioBefore = readProcIo(); val cpuBefore = Process.getElapsedCpuTime(); val wallBefore = SystemClock.elapsedRealtime()
        val finished = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { finished.complete(it) })
        var completed: FFmpegSession? = null; var timedOut = false; var unconfirmed = false
        try {
            completed = try { withTimeout(timeoutMs) { finished.await() } } catch (te: TimeoutCancellationException) {
                timedOut = true; throw te
            }
        } finally {
            if (!finished.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                val clean = withContext(NonCancellable) { withTimeoutOrNull(10_000L) { finished.await() } }
                if (clean != null) completed = clean else unconfirmed = true
            } else if (completed == null) {
                completed = finished.getCompleted()
            }
            val retCode = completed?.returnCode?.value
            val isSuccess = completed != null && ReturnCode.isSuccess(completed.returnCode)
            val status = when {
                isSuccess -> "success"
                timedOut -> "timed_out"
                unconfirmed -> "unconfirmed_timeout"
                completed == null -> "cancelled_uncompleted"
                else -> "failed"
            }
            record.put("wall_ms", SystemClock.elapsedRealtime() - wallBefore).put("cpu_ms", Process.getElapsedCpuTime() - cpuBefore)
                .put("io", ioDeltaJson(ioBefore, readProcIo())).put("return_code", retCode ?: JSONObject.NULL)
                .put("completed", completed != null).put("unconfirmed_completion", unconfirmed).put("timed_out", timedOut)
                .put("status", status).put("logs", completed?.allLogsAsString.orEmpty()).put("conditions_after", conditions(app))
        }
        check(!unconfirmed) { "FFmpeg operation timed out and native completion remained unconfirmed after 10s wait" }
        check(completed != null && ReturnCode.isSuccess(completed.returnCode)) {
            "FFmpeg operation failed return_code=${completed?.returnCode?.value} (status=${record.optString("status")}):\n${completed?.allLogsAsString.orEmpty().takeLast(800)}"
        }
    }

    private fun parseMp4TopLevelAtoms(file: File): List<Mp4Atom> {
        val atoms = mutableListOf<Mp4Atom>()
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length(); var pos = 0L
            while (pos < fileLength) {
                check(fileLength - pos >= 8L) { "Truncated box header at offset $pos in ${file.name}" }
                raf.seek(pos); val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4); raf.readFully(typeBytes); val type = String(typeBytes, Charsets.US_ASCII)
                val boxSize: Long = when (size32) {
                    1L -> {
                        check(fileLength - pos >= 16L) { "Truncated 64-bit box at offset $pos in ${file.name}" }
                        raf.readLong().also { check(it >= 16L) { "Invalid 64-bit size $it at offset $pos in ${file.name}" } }
                    }
                    0L -> fileLength - pos
                    else -> { check(size32 >= 8L) { "Invalid 32-bit size $size32 at offset $pos in ${file.name}" }; size32 }
                }
                check(fileLength - pos >= boxSize) { "Box $type at $pos exceeds file length $fileLength in ${file.name}" }
                atoms.add(Mp4Atom(type, pos, boxSize)); pos += boxSize
            }
            check(pos == fileLength) { "Incomplete trailing bytes: parsed $pos of $fileLength in ${file.name}" }
        }
        val moov = checkNotNull(atoms.firstOrNull { it.type == "moov" }) { "Missing moov atom in ${file.name}" }
        val mdat = checkNotNull(atoms.firstOrNull { it.type == "mdat" }) { "Missing mdat atom in ${file.name}" }
        check(moov.offset < mdat.offset) { "moov (${moov.offset}) must precede mdat (${mdat.offset}) in ${file.name}" }
        return atoms
    }

    private fun readFramehash(file: File): FramehashData {
        val lines = file.readLines(); val headers = lines.filter { it.startsWith('#') }; val packets = lines.filter { it.isNotBlank() && !it.startsWith('#') }
        check(headers.isNotEmpty()) { "Empty framehash headers in ${file.name}" }; check(packets.isNotEmpty()) { "Empty framehash packets in ${file.name}" }
        check(headers.any { it.contains("video") || it.contains("vp9") }) { "Missing video stream declaration in ${file.name}" }
        check(headers.any { it.contains("audio") || it.contains("aac") }) { "Missing audio stream declaration in ${file.name}" }
        var vCount = 0; var aCount = 0
        for (p in packets) { when (p.substringBefore(',').trim()) { "0" -> vCount++; "1" -> aCount++ } }
        check(vCount > 0) { "Zero video packets in ${file.name}" }; check(aCount > 0) { "Zero audio packets in ${file.name}" }
        fun shaOf(list: List<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            list.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(10.toByte()) }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return FramehashData(headers, packets, packets.size, shaOf(packets), shaOf(headers))
    }

    private fun findFirstDiff(base: List<String>, candidate: List<String>): Pair<Int, Pair<String, String>?> {
        val maxLen = maxOf(base.size, candidate.size)
        for (i in 0 until maxLen) {
            val b = base.getOrNull(i).orEmpty(); val c = candidate.getOrNull(i).orEmpty()
            if (b != c) return i to (b to c)
        }
        return -1 to null
    }

    private fun probeMedia(file: File): JSONObject {
        val info = FFprobeKit.getMediaInformation(file.path).mediaInformation ?: return JSONObject().put("probed", false).put("error", "null mediaInformation")
        val v = info.streams.firstOrNull { it.type == "video" }; val a = info.streams.firstOrNull { it.type == "audio" }
        val fmt = info.allProperties.optJSONObject("format")
        return JSONObject().put("probed", true).put("duration", info.duration)
            .put("format_name", fmt?.optString("format_name") ?: info.format)
            .put("bit_rate", fmt?.optString("bit_rate") ?: info.bitrate).put("size_bytes", file.length())
            .put("video", v?.let { JSONObject().put("codec", it.codec).put("width", it.width).put("height", it.height).put("bit_rate", it.allProperties.optString("bit_rate")).put("avg_frame_rate", it.allProperties.optString("avg_frame_rate")) } ?: JSONObject.NULL)
            .put("audio", a?.let { JSONObject().put("codec", it.codec).put("sample_rate", it.allProperties.optString("sample_rate")).put("channels", it.allProperties.optInt("channels")).put("bit_rate", it.allProperties.optString("bit_rate")) } ?: JSONObject.NULL)
    }

    private fun assertProbeMedia(probe: JSONObject, file: File, expectVideo: Boolean, expectAudio: Boolean, expectedDuration: Double = 600.026) {
        assertTrue("Probing failed on ${file.name}", probe.optBoolean("probed", false))
        assertTrue("Duration out of tolerance on ${file.name}", kotlin.math.abs(probe.optDouble("duration", 0.0) - expectedDuration) < 1.0)
        if (expectVideo) {
            val v = checkNotNull(probe.optJSONObject("video")) { "Missing video stream on ${file.name}" }
            assertEquals("vp9", v.optString("codec"))
            assertTrue("Video width <= 0 on ${file.name}", v.optInt("width", 0) > 0)
            assertTrue("Video height <= 0 on ${file.name}", v.optInt("height", 0) > 0)
        }
        if (expectAudio) {
            val a = checkNotNull(probe.optJSONObject("audio")) { "Missing audio stream on ${file.name}" }
            assertEquals("aac", a.optString("codec"))
            assertTrue("Audio channels <= 0 on ${file.name}", a.optInt("channels", 0) > 0)
            assertTrue("Audio sample rate <= 0 on ${file.name}", (a.optString("sample_rate").toIntOrNull() ?: 0) > 0)
        }
    }

    private suspend fun validateStrictA(file: File, audioOnly: Boolean, container: String, width: Int?, height: Int?, videoCodec: String?, record: JSONObject) {
        record.put("validation_target", file.name).put("validated", false)
        val startWall = SystemClock.elapsedRealtime(); val startCpu = Process.getElapsedCpuTime()
        try {
            Processor().validate(file, audio = audioOnly, requireAudio = true, expectedHeight = height, expectedDuration = 600.026, expectedContainer = container, expectedWidth = width, expectedVideoCodec = videoCodec)
            record.put("validated", true)
        } finally {
            record.put("wall_ms", SystemClock.elapsedRealtime() - startWall).put("cpu_ms", Process.getElapsedCpuTime() - startCpu)
        }
    }

    @Test
    fun retainedMuxAudioConversionBenchmark(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")) { "Missing required evidenceRun argument" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid evidenceRun slug: $run" }
        val phase = args.getString("phase") ?: "diagnose"
        require(phase in setOf("diagnose", "compare")) { "phase must be 'diagnose' or 'compare', got: $phase" }

        val parentDir = File(app.getExternalFilesDir(null), "mux-audio-benchmark")
        check(parentDir.mkdirs() || parentDir.isDirectory) { "Failed to create shared benchmark directory: ${parentDir.absolutePath}" }
        val benchmarkDir = File(parentDir, run)
        check(benchmarkDir.mkdir()) { "evidenceRun directory already exists or cannot be created: ${benchmarkDir.absolutePath}" }
        val resultFile = File(benchmarkDir, "result.json")

        val expectedSha = "a8582d44c067fd5c89f916f2908b952477f867a9648f984f596cbc74dc00c79e"
        val expectedBytes = 42615077L
        val expectedDuration = 600.026

        val inputCandidates = listOf(
            File(app.getExternalFilesDir(null), "direct-engineering/vp9-paired78-05fc58/vp9-strict-timing/retained-vp9-opus.webm"),
            File("/sdcard/Android/data/com.zdownloader.app/files/direct-engineering/vp9-paired78-05fc58/vp9-strict-timing/retained-vp9-opus.webm")
        )
        val input = inputCandidates.firstOrNull { it.isFile } ?: inputCandidates.first()

        val report = JSONObject()
            .put("scope", "real on-phone diagnostic separation and candidate comparison of native mux/audio-conversion region")
            .put("phase", phase).put("evidence_run", run)
            .put("installed_version_code", app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
            .put("conditions_before", conditions(app)).put("input_path", input.path)

        fun saveResult() {
            val tmp = File(benchmarkDir, "result.json.tmp")
            tmp.writeText(report.toString(2))
            if (!tmp.renameTo(resultFile)) { resultFile.writeText(report.toString(2)); tmp.delete() }
        }
        saveResult()

        val benchmarkStartWall = SystemClock.elapsedRealtime()
        val benchmarkStartCpu = Process.getElapsedCpuTime()
        var primaryFailure: Throwable? = null

        try {
            val stat = StatFs(benchmarkDir.path); val availableBytes = stat.availableBytes
            report.put("storage_available_before_bytes", availableBytes)
            check(availableBytes >= 512L * 1024L * 1024L) { "Storage safety guard: ${availableBytes / (1024 * 1024)}MiB available, requires >= 512MiB" }
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") }) { "App download queue must be idle before benchmark run" }

            check(input.isFile) { "Retained input file missing or unreadable at: ${input.path}" }
            val beforeDigest = digest(input)
            assertEquals("Retained input size mismatch", expectedBytes, beforeDigest.bytes)
            assertEquals("Retained input SHA-256 mismatch", expectedSha, beforeDigest.sha256)
            report.put("input_sha256_before", beforeDigest.sha256).put("input_bytes_before", beforeDigest.bytes)

            val inputProbe = checkNotNull(FFprobeKit.getMediaInformation(input.path).mediaInformation) { "FFprobe failed on input" }
            val inVideo = checkNotNull(inputProbe.streams.firstOrNull { it.type == "video" }) { "Missing input video" }
            val inAudio = checkNotNull(inputProbe.streams.firstOrNull { it.type == "audio" }) { "Missing input audio" }
            assertEquals("vp9", inVideo.codec); assertEquals("opus", inAudio.codec)
            val inWidth = checkNotNull(inVideo.width?.toInt()?.takeIf { it > 0 }) { "Input video stream missing positive width" }
            val inHeight = checkNotNull(inVideo.height?.toInt()?.takeIf { it > 0 }) { "Input video stream missing positive height" }
            report.put("input_probe", JSONObject().put("width", inWidth).put("height", inHeight).put("video_codec", inVideo.codec).put("audio_codec", inAudio.codec).put("duration", inputProbe.duration))

            if (phase == "diagnose") {
                val operations = JSONArray(); report.put("operations", operations)
                val bFile = File(benchmarkDir, "diag-audio-aac192k.m4a")
                val cFile = File(benchmarkDir, "diag-baseline-mux.mp4")
                val dFile = File(benchmarkDir, "diag-remux-copy.mp4")

                data class DiagPlan(val name: String, val desc: String, val file: File?, val audioOnly: Boolean, val container: String?, val args: () -> List<String>)
                val plan = listOf(
                    DiagPlan("audio_decode_to_null", "full audio decode to null sink", null, true, null) {
                        listOf("-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", input.path, "-map", "0:a:0", "-vn", "-f", "null", "-")
                    },
                    DiagPlan("audio_aac192k_m4a", "audio-only native AAC192k to owned M4A with faststart", bFile, true, "m4a") {
                        listOf("-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", input.path, "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-map_metadata", "-1", bFile.path)
                    },
                    DiagPlan("baseline_mux_mp4", "production-equivalent baseline MP4 copy+aac192k+faststart", cFile, false, "mp4") {
                        listOf("-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", input.path, "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-map_metadata", "-1", cFile.path)
                    },
                    DiagPlan("remux_copy_mp4", "stream-copy remux with faststart isolating copy/finalization", dFile, false, "mp4") {
                        listOf("-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", cFile.path, "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", "-map_metadata", "-1", dFile.path)
                    }
                )

                for (step in plan) {
                    val opRecord = JSONObject().put("name", step.name).put("description", step.desc)
                    operations.put(opRecord); saveResult()
                    executeOp(app, opRecord, step.args())
                    if (step.file != null) {
                        opRecord.put("output_file", step.file.name).put("output_bytes", step.file.length())
                        val probe = probeMedia(step.file); opRecord.put("probe", probe)
                        assertProbeMedia(probe, step.file, expectVideo = !step.audioOnly, expectAudio = true, expectedDuration = expectedDuration)
                        val valRecord = JSONObject(); opRecord.put("validation", valRecord)
                        validateStrictA(step.file, step.audioOnly, step.container!!, inWidth.takeIf { !step.audioOnly }, inHeight.takeIf { !step.audioOnly }, "vp9".takeIf { !step.audioOnly }, valRecord)
                    }
                    opRecord.put("passed", true); saveResult()
                }
                report.put("passed", true)
            } else {
                val armsArray = JSONArray(); report.put("arms", armsArray)
                val armConfigs = listOf(
                    Triple("arm_0_faststart", false, listOf("-movflags", "+faststart")),
                    Triple("arm_1_reserved_moov", true, listOf("-moov_size", "2097152")),
                    Triple("arm_2_reserved_moov", true, listOf("-moov_size", "2097152")),
                    Triple("arm_3_faststart", false, listOf("-movflags", "+faststart"))
                )

                var baselineFh: FramehashData? = null; var baselineChannels = -1; var baselineSampleRate = ""

                for ((index, config) in armConfigs.withIndex()) {
                    val (label, isCandidate, specificFlags) = config
                    val outFile = File(benchmarkDir, "$label.mp4")
                    val armRecord = JSONObject().put("arm_index", index).put("arm_label", label).put("is_candidate", isCandidate).put("output_file", outFile.name)
                    armsArray.put(armRecord); saveResult()

                    val cmdArgs = mutableListOf(
                        "-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", input.path, "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-c:a", "aac", "-b:a", "192k"
                    ).apply { addAll(specificFlags); addAll(listOf("-map_metadata", "-1", outFile.path)) }

                    executeOp(app, armRecord, cmdArgs)
                    armRecord.put("output_bytes", outFile.length())

                    val atoms = parseMp4TopLevelAtoms(outFile)
                    armRecord.put("atoms", JSONArray(atoms.map { JSONObject().put("type", it.type).put("offset", it.offset).put("size", it.size) }))
                    val moov = atoms.first { it.type == "moov" }; val mdat = atoms.first { it.type == "mdat" }
                    armRecord.put("moov_offset", moov.offset).put("mdat_offset", mdat.offset).put("front_loaded_moov", true)
                    armRecord.put("whole_file_sha256", digest(outFile).sha256)

                    val probe = probeMedia(outFile); armRecord.put("probe", probe)
                    assertProbeMedia(probe, outFile, expectVideo = true, expectAudio = true, expectedDuration = expectedDuration)

                    val aStream = probe.getJSONObject("audio")
                    val channels = aStream.getInt("channels"); val sampleRate = aStream.getString("sample_rate")
                    if (index == 0) { baselineChannels = channels; baselineSampleRate = sampleRate }
                    else {
                        assertEquals("Channels mismatch in $label", baselineChannels, channels)
                        assertEquals("Sample rate mismatch in $label", baselineSampleRate, sampleRate)
                    }

                    val framehashFile = File(benchmarkDir, "$label-framehash.txt")
                    val fhGenRecord = JSONObject(); armRecord.put("framehash_generation", fhGenRecord); saveResult()
                    executeOp(app, fhGenRecord, listOf(
                        "-y", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", outFile.path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-f", "framehash", "-hash", "sha256", framehashFile.path
                    ))
                    val fhData = readFramehash(framehashFile)

                    if (index == 0) {
                        baselineFh = fhData
                        armRecord.put("framehash", JSONObject().put("packet_count", fhData.packetCount).put("packets_sha256", fhData.packetsSha).put("headers_sha256", fhData.headersSha).put("headers", JSONArray(fhData.headers)).put("matches_baseline_packets", true).put("matches_baseline_headers", true))
                    } else {
                        val base = requireNotNull(baselineFh)
                        val headersMatch = (base.headers == fhData.headers); val packetsMatch = (base.packets == fhData.packets)
                        val (diffIdx, packetDiff) = if (packetsMatch) -1 to null else findFirstDiff(base.packets, fhData.packets)
                        val (diffHdrIdx, hdrDiff) = if (headersMatch) -1 to null else findFirstDiff(base.headers, fhData.headers)

                        armRecord.put("framehash", JSONObject()
                            .put("packet_count", fhData.packetCount).put("packets_sha256", fhData.packetsSha)
                            .put("headers_sha256", fhData.headersSha).put("headers", JSONArray(fhData.headers))
                            .put("matches_baseline_packets", packetsMatch).put("matches_baseline_headers", headersMatch)
                            .put("first_diff_packet_index", if (diffIdx >= 0) diffIdx else JSONObject.NULL)
                            .put("baseline_diff_packet", packetDiff?.first ?: JSONObject.NULL)
                            .put("arm_diff_packet", packetDiff?.second ?: JSONObject.NULL)
                            .put("first_diff_header_index", if (diffHdrIdx >= 0) diffHdrIdx else JSONObject.NULL)
                            .put("baseline_diff_header", hdrDiff?.first ?: JSONObject.NULL)
                            .put("arm_diff_header", hdrDiff?.second ?: JSONObject.NULL))

                        assertTrue("Headers in $label must match baseline (diff at $diffHdrIdx: '${hdrDiff?.first}' vs '${hdrDiff?.second}')", headersMatch)
                        assertTrue("Packets in $label must match baseline (diff at $diffIdx: '${packetDiff?.first}' vs '${packetDiff?.second}')", packetsMatch)
                    }

                    val valRecord = JSONObject(); armRecord.put("validation", valRecord)
                    validateStrictA(outFile, audioOnly = false, container = "mp4", width = inWidth, height = inHeight, videoCodec = "vp9", record = valRecord)

                    armRecord.put("passed", true); saveResult()
                }

                report.put("candidate_reservation_sufficient", true).put("all_arms_front_loaded_moov", true)
                    .put("all_arms_packet_signatures_match_baseline", true).put("all_arms_headers_match_baseline", true)
                    .put("all_arms_durations_valid", true).put("passed", true)
            }
        } catch (t: Throwable) {
            primaryFailure = t
            report.put("passed", false).put("failure", "${t.javaClass.simpleName}:${t.message}").put("failure_stack", t.stackTraceToString())
            throw t
        } finally {
            withContext(NonCancellable) {
                var digestError: Throwable? = null
                val afterDigest = try { if (input.isFile) digest(input) else null } catch (de: Throwable) { digestError = de; null }
                val unchanged = digestError == null && afterDigest != null && afterDigest.bytes == expectedBytes && afterDigest.sha256 == expectedSha

                if (!unchanged) {
                    report.put("passed", false)
                    val reason = when {
                        digestError != null -> "digest_read_error: ${digestError.javaClass.simpleName}:${digestError.message}"
                        afterDigest == null -> "input_file_missing"
                        else -> "input_modified: expected $expectedSha ($expectedBytes bytes), observed ${afterDigest.sha256} (${afterDigest.bytes} bytes)"
                    }
                    report.put("input_integrity_failure", reason)
                    if (!report.has("failure") || report.optString("failure").isEmpty()) report.put("failure", reason)
                }

                report.put("conditions_after", conditions(app))
                    .put("input_sha256_after", afterDigest?.sha256 ?: JSONObject.NULL)
                    .put("input_bytes_after", afterDigest?.bytes ?: JSONObject.NULL)
                    .put("input_unchanged_after", unchanged)
                    .put("total_wall_ms", SystemClock.elapsedRealtime() - benchmarkStartWall)
                    .put("total_cpu_ms", Process.getElapsedCpuTime() - benchmarkStartCpu)

                saveResult()

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
