package com.zdownloader.app

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One-device post-download benchmark using the already-saved long video. */
class LongVideoMediaStoreBenchmarkTest {
    @Test
    fun measuresLongVideoRemuxValidationAndMediaStoreCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        assertTrue("unexpected target package", app.packageName == "com.zdownloader.app")
        assertTrue("MediaStore pending publication requires API 29+", Build.VERSION.SDK_INT >= 29)

        val run = InstrumentationRegistry.getArguments().getString("evidenceRun")
            ?: throw AssertionError("evidenceRun instrumentation argument required")
        assertTrue("invalid evidenceRun", run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidenceDir = File(requireNotNull(app.getExternalFilesDir(null)), "advanced-audit/$run")
        assertTrue("unable to create evidence directory", evidenceDir.mkdirs() || evidenceDir.isDirectory)
        val reportFile = File(evidenceDir, "long-video-mediastore-benchmark.json")
        assertTrue("refusing to overwrite existing evidence", reportFile.createNewFile())

        val report = JSONObject()
            .put("scope", "existing_saved_video; remux+full_decode+MediaStore_copy_and_readback; download_excluded")
            .put("target_bytes", TARGET_BYTES)
            .put("target_sha256", TARGET_SHA256)
        val scratch = File(app.cacheDir, "long-video-benchmark-$run")
        var pendingUri: Uri? = null
        var primaryFailure: Throwable? = null
        var ownsScratch = false

        try {
            check(scratch.mkdir()) { "benchmark scratch directory already exists" }
            ownsScratch = true
            val source = findAndStageKnownVideo(app, scratch, report)
            val sourceInfo = probe(source.file.path)
            report.put("source_metadata", sourceInfo)
            check(sourceInfo.optBoolean("has_video")) { "known source has no video stream" }
            val extension = when (source.mimeType) {
                "video/webm" -> "webm"
                "video/mp4" -> "mp4"
                else -> throw IllegalStateException("known source container is not a supported benchmark target")
            }
            report.put("container", extension)

            val remuxed = File(scratch, "remux.$extension")
            val muxStarted = SystemClock.elapsedRealtime()
            val muxSession = FFmpegKit.executeWithArguments(remuxArguments(source.file.path, remuxed.path, extension))
            val muxMs = SystemClock.elapsedRealtime() - muxStarted
            report.put("mux_ms", muxMs).put("mux_return_code", muxSession.returnCode?.value ?: -1)
            check(ReturnCode.isSuccess(muxSession.returnCode) && remuxed.isFile && remuxed.length() > 0L) {
                "stream-copy remux did not produce a valid output"
            }
            val remuxInfo = probe(remuxed.path)
            report.put("remux_metadata", remuxInfo)
            val metadataEquivalent = sameCoreMetadata(sourceInfo, remuxInfo)
            report.put("metadata_equivalent", metadataEquivalent)
            check(metadataEquivalent) { "stream-copy remux metadata differs from the saved source" }

            val validationStarted = SystemClock.elapsedRealtime()
            runBlocking {
                Processor().validate(remuxed, audio = false, requireAudio = sourceInfo.optBoolean("has_audio"),
                    expectedDuration = sourceInfo.getLong("duration_ms") / 1000.0,
                    expectedContainer = extension, control = TransferControl())
            }
            val validationMs = SystemClock.elapsedRealtime() - validationStarted
            report.put("validation_ms", validationMs)
                .put("production_processor_validation_passed", true)

            val packetHashStarted = SystemClock.elapsedRealtime()
            val sourceVideoHash = packetHash(source.file, "0:v:0", File(scratch, "source-video.sha256"))
            val remuxVideoHash = packetHash(remuxed, "0:v:0", File(scratch, "remux-video.sha256"))
            val sourceAudioHash = if (sourceInfo.optBoolean("has_audio")) {
                packetHash(source.file, "0:a:0", File(scratch, "source-audio.sha256"))
            } else null
            val remuxAudioHash = if (sourceInfo.optBoolean("has_audio")) {
                packetHash(remuxed, "0:a:0", File(scratch, "remux-audio.sha256"))
            } else null
            val packetHashesMatch = sourceVideoHash == remuxVideoHash && sourceAudioHash == remuxAudioHash
            report.put("packet_hash_ms", SystemClock.elapsedRealtime() - packetHashStarted)
                .put("video_packet_hash_match", sourceVideoHash == remuxVideoHash)
                .put("audio_packet_hash_match", sourceAudioHash == remuxAudioHash)
                .put("encoded_packets_unchanged", packetHashesMatch)
            check(packetHashesMatch) { "stream-copy remux changed encoded packet payloads" }

            pendingUri = createPendingVideo(app, run, extension, source.mimeType)
            val copyStarted = SystemClock.elapsedRealtime()
            val copied = copyToPending(app, remuxed, pendingUri!!)
            val copyWriteHashMs = SystemClock.elapsedRealtime() - copyStarted
            report.put("mediastore_copy_write_and_source_hash_ms", copyWriteHashMs)
                .put("copied_bytes", copied.bytes)
                .put("mediastore_copy_sha256", copied.sha256)
            check(copied.bytes == remuxed.length()) { "MediaStore copy byte count mismatch" }

            val verifyStarted = SystemClock.elapsedRealtime()
            val published = digestUri(app, pendingUri!!)
            val readbackVerifyMs = SystemClock.elapsedRealtime() - verifyStarted
            val exactCopy = published.bytes == remuxed.length() && published.sha256 == copied.sha256
            report.put("mediastore_readback_verify_ms", readbackVerifyMs)
                .put("readback_bytes", published.bytes)
                .put("readback_sha256", published.sha256)
                .put("mediastore_copy_verified", exactCopy)
            check(exactCopy) { "MediaStore read-back verification mismatch" }

            val metadataStarted = SystemClock.elapsedRealtime()
            val descriptor = app.contentResolver.openFileDescriptor(pendingUri!!, "r")
                ?: throw IllegalStateException("pending MediaStore descriptor unavailable")
            val statSize = descriptor.use { it.statSize }
            val pending = queryPending(app, pendingUri!!)
            val pendingMetadataMs = SystemClock.elapsedRealtime() - metadataStarted
            report.put("mediastore_stat_and_pending_check_ms", pendingMetadataMs)
                .put("mediastore_stat_size", statSize)
                .put("is_pending_until_test_cleanup", pending)
                .put("published_to_gallery", false)
            check(statSize == remuxed.length() && pending) { "pending MediaStore output metadata mismatch" }

            val seek = verifySeekableMedia(app, pendingUri!!, remuxInfo.optLong("duration_ms"))
            report.put("media_extractor_seek", seek)
            check(seek.optBoolean("passed")) { "MediaStore output could not seek/read expected tracks" }

            val exportMs = copyWriteHashMs + readbackVerifyMs + pendingMetadataMs
            report.put("export_copy_hash_verify_and_metadata_ms", exportMs)
                .put("mux_plus_validation_plus_export_ms", muxMs + validationMs + exportMs)
                .put("current_pipeline_stages_passed", true)
                .put("publication_unhide_update_measured", false)
                .put("benchmark_is_original_separate_track_mux", false)
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("passed", false).put("failure_type", error.javaClass.simpleName)
        } finally {
            val deleted = pendingUri?.let { uri ->
                runCatching { app.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false)
            } ?: true
            val scratchDeleted = runCatching { !ownsScratch || scratch.deleteRecursively() }.getOrDefault(false)
            report.put("pending_test_media_deleted", deleted)
                .put("scratch_files_deleted", scratchDeleted)
                .put("published", false)
            val encoded = report.toString(2)
            check(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "benchmark evidence exceeds 10KB" }
            reportFile.writeText(encoded)
        }

        primaryFailure?.let { throw AssertionError("long-video benchmark failed: ${it.javaClass.simpleName}", it) }
        assertTrue("pending test media cleanup failed", report.getBoolean("pending_test_media_deleted"))
        assertTrue("scratch cleanup failed", report.getBoolean("scratch_files_deleted"))
        assertTrue("baseline stages did not complete", report.optBoolean("current_pipeline_stages_passed"))
    }

    private data class StagedSource(val file: File, val mimeType: String)
    private data class DigestResult(val bytes: Long, val sha256: String)

    @Test
    fun measuresBoundedPipeDecodeOverlapOnKnownVideo(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidenceDir = File(requireNotNull(app.getExternalFilesDir(null)), "advanced-audit/$run")
        check(evidenceDir.mkdirs()) { "refusing existing evidence directory" }
        val scratch = File(app.cacheDir, "pipe-prototype-$run")
        check(scratch.mkdir())
        val report = JSONObject().put("scope", "TEST ONLY: complete120s fixture through rate-limited local FIFO; not production or network acceptance")
        var failure: Throwable? = null
        try {
            val source = findAndStageKnownVideo(app, scratch, report)
            val clip = File(scratch, "clip.webm")
            val remux = FFmpegKit.executeWithArguments(arrayOf("-nostdin", "-v", "error", "-ss", "900",
                "-i", source.file.path, "-t", "120", "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", clip.path))
            check(ReturnCode.isSuccess(remux.returnCode) && clip.length() > 0)
            val baselineHash = File(scratch, "baseline.framehash")
            val baseline = FFmpegKit.executeWithArguments(pipePrototypeArguments(clip.path, baselineHash))
            check(ReturnCode.isSuccess(baseline.returnCode)) { "complete fixture baseline decode failed" }
            val pipeHash = File(scratch, "pipe.framehash")
            val pipe = requireNotNull(com.arthenica.ffmpegkit.FFmpegKitConfig.registerNewFFmpegPipe(app))
            val completed = java.util.concurrent.CompletableFuture<com.arthenica.ffmpegkit.FFmpegSession>()
            var descriptor: java.io.FileDescriptor? = null
            var session: com.arthenica.ffmpegkit.FFmpegSession? = null
            val started = SystemClock.elapsedRealtime()
            val rate = 512L * 1024L
            var sent = 0L
            val sentHash = MessageDigest.getInstance("SHA-256")
            try {
                // Nonblocking RDWR avoids FIFO open/write deadlocks if the
                // native reader fails. Publication is never involved here.
                descriptor = android.system.Os.open(pipe, android.system.OsConstants.O_RDWR or android.system.OsConstants.O_NONBLOCK, 0)
                session = FFmpegKit.executeWithArgumentsAsync(pipePrototypeArguments(pipe, pipeHash)) { completed.complete(it) }
                clip.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        var offset = 0
                        while (offset < count) {
                            check(!completed.isDone) { "native decoder ended before complete fixture delivery" }
                            check(SystemClock.elapsedRealtime() - started < 90_000L) { "bounded FIFO feeder timed out" }
                            val ahead = sent * 1000 / rate - (SystemClock.elapsedRealtime() - started)
                            if (ahead > 0) kotlinx.coroutines.delay(minOf(ahead, 25L))
                            else try {
                                val written = android.system.Os.write(descriptor, buffer, offset, count - offset)
                                check(written > 0)
                                sentHash.update(buffer, offset, written)
                                sent += written
                                offset += written
                            } catch (error: android.system.ErrnoException) {
                                if (error.errno != android.system.OsConstants.EAGAIN && error.errno != android.system.OsConstants.EINTR) throw error
                                kotlinx.coroutines.delay(5)
                            }
                        }
                    }
                }
                android.system.Os.close(descriptor)
                descriptor = null
                val feedMs = SystemClock.elapsedRealtime() - started
                val decoded = completed.get(30, java.util.concurrent.TimeUnit.SECONDS)
                check(ReturnCode.isSuccess(decoded.returnCode)) { "FIFO strict decode failed" }
                val elapsed = SystemClock.elapsedRealtime() - started
                val deliveredHash = sentHash.digest().hex()
                val sourceHash = clip.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    digest.digest().hex()
                }
                val baselineLines = baselineHash.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.sorted()
                val pipeLines = pipeHash.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.sorted()
                check(baselineLines.any { it.substringBefore(',').trim() == "0" } && baselineLines.any { it.substringBefore(',').trim() == "1" })
                check(baselineLines == pipeLines) { "FIFO changed decoded video/audio frames or timestamps" }
                check(sent == clip.length() && deliveredHash == sourceHash) { "FIFO feeder omitted or changed bytes" }
                report.put("fixture_bytes", clip.length()).put("feed_rate_bytes_per_second", rate)
                    .put("baseline_full_fixture_decode_and_hash_ms", baseline.duration)
                    .put("feed_ms", feedMs).put("overlap_total_ms", elapsed)
                    .put("post_feed_wait_ms", elapsed - feedMs)
                    .put("serialized_estimate_ms_not_measured_network", feedMs + baseline.duration)
                    .put("delivered_sha256", deliveredHash).put("all_fixture_bytes_delivered", true)
                    .put("decoded_video_and_audio_records", baselineLines.size).put("decoded_outputs_identical", true)
                    .put("prototype_passed", true)
            } finally {
                try {
                    descriptor?.let { android.system.Os.close(it) }
                    if (session != null && !completed.isDone) {
                        FFmpegKit.cancel(session.sessionId)
                        completed.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } finally {
                    com.arthenica.ffmpegkit.FFmpegKitConfig.closeFFmpegPipe(pipe)
                    report.put("fifo_removed", !File(pipe).exists())
                }
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_message", error.message?.take(300))
        } finally {
            report.put("scratch_files_deleted", scratch.deleteRecursively()).put("published", false)
            File(evidenceDir, "pipe-overlap-prototype.json").writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("bounded FIFO prototype failed", it) }
        assertTrue(report.getBoolean("fifo_removed") && report.getBoolean("scratch_files_deleted"))
    }

    private fun pipePrototypeArguments(input: String, framehash: File): Array<String> = arrayOf(
        "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none", "-threads:v", "4",
        "-protocol_whitelist", "file,pipe", "-i", input, "-map", "0:v:0", "-map", "0:a:0",
        "-max_error_rate", "0", "-f", "framehash", "-hash", "sha256", framehash.path
    )

    @Test
    fun comparesStrictSoftwareDecodeThreadCountsOnKnownVideo() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val requestedBudgets = InstrumentationRegistry.getArguments().getString("threadBudgets")
        require(requestedBudgets == null || requestedBudgets.matches(Regex("[1-8](,[1-8]){0,3}")))
        val candidates = requestedBudgets?.split(",") ?: listOf("1", "2", "4", "8")
        val referenceThreads = if (requestedBudgets == null) "auto" else "4"
        require(candidates.distinct().size == candidates.size && referenceThreads !in candidates)
        val settings = listOf(referenceThreads) + candidates
        val evidenceDir = File(requireNotNull(app.getExternalFilesDir(null)), "advanced-audit/$run")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
        val evidence = File(evidenceDir, "strict-decode-thread-experiment.json")
        check(evidence.createNewFile()) { "refusing to overwrite experiment evidence" }
        val scratch = File(app.cacheDir, "strict-decode-threads-$run")
        check(scratch.mkdir()) { "refusing to reuse existing scratch" }
        val samples = org.json.JSONArray()
        val signatures = JSONObject()
        val report = JSONObject().put("scope", "120s interior slice; not full-file acceptance")
            .put("start_seconds", 900).put("duration_seconds", 120)
            .put("samples", samples).put("decoded_frame_signatures", signatures)
            .put("reference_threads", referenceThreads).put("candidate_threads", org.json.JSONArray(candidates))
        var failure: Throwable? = null
        try {
            val source = findAndStageKnownVideo(app, scratch, report)
            val metadata = probe(source.file.path)
            check(metadata.getString("video_codec") == "vp9" && metadata.getString("audio_codec") == "opus")
            report.put("source_metadata", metadata)
            val warmup = FFmpegKit.executeWithArguments(strictThreadArguments(source.file, referenceThreads, null))
            check(ReturnCode.isSuccess(warmup.returnCode)) { "strict decode warmup failed" }
            val schedule = candidates.flatMap { listOf(referenceThreads, it) } +
                candidates.reversed().flatMap { listOf(it, referenceThreads) }
            val thermal = app.getSystemService(android.os.PowerManager::class.java)
            for ((index, threads) in schedule.withIndex()) {
                val cpuStarted = android.os.Process.getElapsedCpuTime()
                val started = SystemClock.elapsedRealtime()
                val session = FFmpegKit.executeWithArguments(strictThreadArguments(source.file, threads, null))
                samples.put(JSONObject().put("trial", index).put("threads", threads)
                    .put("wall_ms", SystemClock.elapsedRealtime() - started)
                    .put("process_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStarted)
                    .put("thermal_status", thermal.currentThermalStatus)
                    .put("return_code", session.returnCode?.value ?: -1))
                check(ReturnCode.isSuccess(session.returnCode)) { "strict decode failed for $threads threads" }
            }
            // Null-output timings exclude frame hashing. This separate pass
            // checks all decoded frames and audio blocks in the same window.
            for (threads in settings) {
                val framehash = File(scratch, "frames-$threads.sha256")
                val session = FFmpegKit.executeWithArguments(strictThreadArguments(source.file, threads, framehash))
                check(ReturnCode.isSuccess(session.returnCode) && framehash.isFile) { "framehash failed for $threads threads" }
                val lines = framehash.readLines()
                val frames = lines.filter { it.isNotBlank() && !it.startsWith("#") }
                check(frames.any { it.substringBefore(',').trim() == "0" } &&
                    frames.any { it.substringBefore(',').trim() == "1" }) { "missing decoded video or audio" }
                val format = lines.filter { it.startsWith("#tb") || it.startsWith("#dimensions") || it.startsWith("#sample_rate") }
                val canonical = (format + frames).sorted().joinToString("\n").toByteArray(Charsets.UTF_8)
                signatures.put(threads, JSONObject().put("frame_and_audio_block_count", frames.size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(canonical).hex()))
            }
            val reference = signatures.getJSONObject(referenceThreads).getString("sha256")
            val equivalent = candidates.all {
                signatures.getJSONObject(it).getString("sha256") == reference
            }
            report.put("decoded_outputs_identical", equivalent)
            check(equivalent) { "thread settings changed decoded output or timestamps" }
            report.put("experiment_completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName)
        } finally {
            report.put("scratch_files_deleted", scratch.deleteRecursively()).put("published", false)
            evidence.writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("strict decode thread experiment failed", it) }
        assertTrue("experiment scratch cleanup failed", report.getBoolean("scratch_files_deleted"))
    }

    private fun strictThreadArguments(input: File, threads: String, framehash: File?): Array<String> {
        val arguments = mutableListOf("-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none")
        if (threads != "auto") arguments += listOf("-threads:v", threads)
        arguments += listOf("-ss", "900", "-protocol_whitelist", "file,pipe", "-i", input.path,
            "-t", "120", "-map", "0:v:0", "-map", "0:a:0", "-max_error_rate", "0")
        arguments += if (framehash == null) listOf("-f", "null", "-")
            else listOf("-f", "framehash", "-hash", "sha256", framehash.path)
        return arguments.toTypedArray()
    }

    @Test
    fun comparesFullStrictSoftwareDecodeUsingFourThreads() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidenceDir = File(requireNotNull(app.getExternalFilesDir(null)), "advanced-audit/$run")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
        val evidence = File(evidenceDir, "full-strict-decode-thread-comparison.json")
        check(evidence.createNewFile()) { "refusing to overwrite experiment evidence" }
        val scratch = File(app.cacheDir, "full-strict-decode-$run")
        check(scratch.mkdir()) { "refusing to reuse existing scratch" }
        val samples = org.json.JSONArray()
        val report = JSONObject().put("scope", "complete known file; strict software video AND audio decode")
            .put("samples", samples).put("partial_decode", false)
        var failure: Throwable? = null
        try {
            val source = findAndStageKnownVideo(app, scratch, report)
            report.put("source_metadata", probe(source.file.path))
            for (threads in listOf("4", "auto")) {
                val arguments = mutableListOf("-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none")
                if (threads != "auto") arguments += listOf("-threads:v", threads)
                arguments += listOf("-protocol_whitelist", "file,pipe", "-i", source.file.path,
                    "-map", "0:v:0", "-map", "0:a:0", "-max_error_rate", "0", "-f", "null", "-")
                val cpuStarted = android.os.Process.getElapsedCpuTime()
                val started = SystemClock.elapsedRealtime()
                val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
                samples.put(JSONObject().put("threads", threads)
                    .put("wall_ms", SystemClock.elapsedRealtime() - started)
                    .put("process_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStarted)
                    .put("thermal_status", app.getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
                    .put("return_code", session.returnCode?.value ?: -1))
                check(ReturnCode.isSuccess(session.returnCode)) { "complete strict decode failed for $threads threads" }
            }
            report.put("experiment_completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName)
        } finally {
            report.put("scratch_files_deleted", scratch.deleteRecursively()).put("published", false)
            evidence.writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("full strict decode comparison failed", it) }
        assertTrue("full-decode experiment scratch cleanup failed", report.getBoolean("scratch_files_deleted"))
    }

    @Test
    fun malformedVp9FrameIsRejectedWithDefaultAndFourThreadDecode() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidenceDir = File(requireNotNull(app.getExternalFilesDir(null)), "advanced-audit/$run")
        check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
        val evidence = File(evidenceDir, "malformed-vp9-thread-validation.json")
        check(evidence.createNewFile())
        val scratch = File(app.cacheDir, "malformed-vp9-$run")
        check(scratch.mkdir())
        val report = JSONObject()
        var failure: Throwable? = null
        try {
            val source = findAndStageKnownVideo(app, scratch, report)
            val packets = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-select_streams", "v:0",
                "-read_intervals", "60%+#3", "-show_packets", "-show_data_hash", "sha256",
                "-show_entries", "packet=pos,pts_time,size,data_hash", "-of", "json", source.file.path))
            check(ReturnCode.isSuccess(packets.returnCode)) { "FFprobe packet export failed: ${packets.returnCode}" }
            val packetJson = packets.output
            check(packetJson.length in 1..8192) { "missing or oversized packet metadata" }
            // Packet hashes avoid large native log strings. Match the entire
            // payload because WebM packet.pos includes the block header.
            val rows = try {
                JSONObject(packetJson).getJSONArray("packets")
            } catch (_: org.json.JSONException) {
                throw IllegalStateException("FFprobe packet metadata is malformed (${packetJson.length} characters)")
            }
            check(rows.length() > 0)
            val packet = rows.getJSONObject(rows.length() / 2)
            val damagedTime = packet.getString("pts_time").toDouble()
            check(damagedTime > 15.0) { "damage must be beyond the initial fifteen seconds" }
            report.put("packet_json_characters", packetJson.length).put("damaged_packet_time_seconds", damagedTime)
            val packetSize = packet.getInt("size")
            check(packetSize in 1..1_048_576)
            val expectedHash = packet.getString("data_hash").substringAfter(':').lowercase()
            check(expectedHash.matches(Regex("[0-9a-f]{64}")))
            java.io.RandomAccessFile(source.file, "rw").use { file ->
                val position = packet.getLong("pos")
                check(position >= 0 && position <= file.length() - packetSize)
                file.seek(position)
                val window = ByteArray(minOf(packetSize + 127L, file.length() - position).toInt())
                file.readFully(window)
                val matches = (0..window.size - packetSize).filter { offset ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(window, offset, packetSize)
                    digest.digest().hex() == expectedHash
                }
                check(matches.size == 1) { "cannot uniquely locate the hash-verified packet payload" }
                val marker = window[matches.single()].toInt()
                check((marker and 0xC0) == 0x80) { "fixture must contain a valid VP9 frame marker" }
                val payload = position + matches.single()
                file.seek(payload)
                file.writeByte(marker and 0x3F)
                report.put("damaged_payload_offset", payload).put("packet_payload_hash_verified_before_damage", true)
            }
            val metadata = probe(source.file.path)
            check(metadata.getString("video_codec") == "vp9" && metadata.getBoolean("has_audio"))
            val durationSeconds = metadata.getLong("duration_ms") / 1000.0
            for (offset in listOf(durationSeconds * 0.25, durationSeconds * 0.75)) {
                check(damagedTime !in (offset - 5.0)..(offset + 20.0)) { "fixture damage overlaps an interior calibration window" }
            }
            report.put("container_remains_readable", true)
            val codes = JSONObject()
            report.put("strict_return_codes", codes)
            for (threads in listOf("auto", "4")) {
                val args = mutableListOf("-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none")
                if (threads != "auto") args += listOf("-threads:v", threads)
                args += listOf("-protocol_whitelist", "file,pipe", "-i", source.file.path,
                    "-map", "0:v:0", "-map", "0:a:0", "-max_error_rate", "0", "-f", "null", "-")
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                codes.put(threads, session.returnCode?.value ?: -1)
                check(session.returnCode != null && !ReturnCode.isSuccess(session.returnCode)) { "malformed VP9 passed with $threads threads" }
            }
            val previousSessionIds = FFmpegKit.listSessions().map { it.sessionId }.toSet()
            val validationStarted = SystemClock.elapsedRealtime()
            runBlocking {
                try {
                    Processor().validate(source.file, audio = false, requireAudio = true,
                        expectedDuration = metadata.getLong("duration_ms") / 1000.0,
                        expectedContainer = "webm", expectedVideoCodec = "vp9")
                    throw AssertionError("production validation accepted a malformed VP9 frame after the calibration window")
                } catch (error: com.zdownloader.app.domain.MediaException) {
                    check(error.code == com.zdownloader.app.domain.ErrorCode.OUTPUT_VALIDATION_FAILED)
                    report.put("production_processor_rejected", true)
                }
            }
            report.put("processor_validation_wall_ms", SystemClock.elapsedRealtime() - validationStarted)
            val validationSessions = FFmpegKit.listSessions().filter { it.sessionId !in previousSessionIds }
            report.put("actual_processor_ffmpeg_sessions", org.json.JSONArray(validationSessions.map { session ->
                JSONObject().put("session_id", session.sessionId).put("arguments", org.json.JSONArray(session.arguments.toList()))
                    .put("command", session.command).put("native_duration_ms", session.duration)
                    .put("native_start_epoch_ms", session.startTime?.time).put("native_end_epoch_ms", session.endTime?.time)
                    .put("session_create_epoch_ms", session.createTime.time).put("return_code", session.returnCode?.value)
            }))
            check(validationSessions.any { "-t" !in it.arguments && "-ss" !in it.arguments }) {
                "no untrimmed production decode session was captured"
            }
            report.put("ffmpeg_version", com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion())
                .put("ffmpeg_package", com.arthenica.ffmpegkit.Packages.getPackageName())
                .put("external_libraries", org.json.JSONArray(com.arthenica.ffmpegkit.Packages.getExternalLibraries()))
            val decoderListing = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-decoders"))
            check(ReturnCode.isSuccess(decoderListing.returnCode)) { "decoder inventory failed" }
            report.put("available_relevant_decoders", org.json.JSONArray(decoderListing.output.lineSequence()
                .filter { Regex("\\b(vp9|libvpx-vp9|h264|av1)\\b").containsMatchIn(it) }.toList()))
            report.put("experiment_completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName)
        } finally {
            report.put("scratch_files_deleted", scratch.deleteRecursively()).put("published", false)
            evidence.writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("malformed VP9 thread validation failed", it) }
        assertTrue("malformed VP9 scratch cleanup failed", report.getBoolean("scratch_files_deleted"))
    }

    private fun findAndStageKnownVideo(context: Context, scratch: File, report: JSONObject): StagedSource {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE)
        val matches = ArrayList<Pair<Uri, String>>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.SIZE}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf(TARGET_BYTES.toString()),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext() && matches.size < 4) {
                val id = cursor.getLong(idColumn)
                matches += ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id) to
                    cursor.getString(mimeColumn).orEmpty()
            }
        } ?: throw IllegalStateException("targeted MediaStore query failed")
        report.put("exact_size_candidate_count", matches.size)
        check(matches.size == 1) { "expected one saved video matching the known size" }

        val (uri, mimeType) = matches.single()
        val extension = when (mimeType) {
            "video/webm" -> "webm"
            "video/mp4" -> "mp4"
            else -> "media"
        }
        val target = File(scratch, "source.$extension")
        val started = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                }
            }
        } ?: throw IllegalStateException("known saved video could not be opened")
        val hash = digest.digest().hex()
        report.put("source_staging_ms", SystemClock.elapsedRealtime() - started)
            .put("source_bytes", bytes)
            .put("source_sha256_matches_known_file", bytes == TARGET_BYTES && hash == TARGET_SHA256)
        check(bytes == TARGET_BYTES && hash == TARGET_SHA256) { "saved video does not match the known long-video sample" }
        return StagedSource(target, mimeType)
    }

    private fun remuxArguments(input: String, output: String, container: String): Array<String> {
        val arguments = mutableListOf(
            "-nostdin", "-y", "-v", "error", "-xerror", "-err_detect", "explode",
            "-protocol_whitelist", "file,pipe", "-i", input,
            "-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "copy"
        )
        if (container == "mp4") arguments += listOf("-movflags", "+faststart")
        arguments += listOf("-f", container, output)
        return arguments.toTypedArray()
    }

    private fun probe(path: String): JSONObject {
        val info = FFprobeKit.getMediaInformation(path).mediaInformation
            ?: throw IllegalStateException("FFprobe returned no media information")
        val streams = info.streams.orEmpty()
        val video = streams.firstOrNull { it.type == "video" }
        val audio = streams.firstOrNull { it.type == "audio" }
        val durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000.0).toLong() } ?: -1L
        return JSONObject()
            .put("has_video", video != null)
            .put("has_audio", audio != null)
            .put("video_codec", video?.codec ?: JSONObject.NULL)
            .put("audio_codec", audio?.codec ?: JSONObject.NULL)
            .put("width", video?.width?.toInt() ?: 0)
            .put("height", video?.height?.toInt() ?: 0)
            .put("duration_ms", durationMs)
            .put("video_start_ms", streamTimeMs(video))
            .put("audio_start_ms", streamTimeMs(audio))
    }

    private fun streamTimeMs(stream: com.arthenica.ffmpegkit.StreamInformation?): Long {
        val value = stream?.allProperties?.optString("start_time")?.toDoubleOrNull() ?: return -1L
        return (value * 1000.0).toLong()
    }

    private fun sameCoreMetadata(left: JSONObject, right: JSONObject): Boolean {
        if (left.optBoolean("has_video") != right.optBoolean("has_video") ||
            left.optBoolean("has_audio") != right.optBoolean("has_audio") ||
            left.optString("video_codec") != right.optString("video_codec") ||
            left.optString("audio_codec") != right.optString("audio_codec") ||
            left.optInt("width") != right.optInt("width") ||
            left.optInt("height") != right.optInt("height")) return false
        val sourceDuration = left.optLong("duration_ms", -1L)
        val outputDuration = right.optLong("duration_ms", -1L)
        return sourceDuration > 0L && outputDuration > 0L &&
            kotlin.math.abs(sourceDuration - outputDuration) <= maxOf(500L, sourceDuration * 3L / 100L)
    }

    private fun packetHash(input: File, stream: String, output: File): String {
        val session = FFmpegKit.executeWithArguments(arrayOf(
            "-nostdin", "-v", "error", "-xerror", "-protocol_whitelist", "file,pipe", "-i", input.path,
            "-map", stream, "-c", "copy", "-f", "hash", "-hash", "sha256", output.path
        ))
        check(ReturnCode.isSuccess(session.returnCode) && output.isFile) { "packet hash failed" }
        return output.readText().trim().removePrefix("SHA256=").lowercase()
    }

    private fun createPendingVideo(context: Context, run: String, extension: String, mimeType: String): Uri =
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ".mediaflow-benchmark-$run.pending.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MediaFlow/PerformanceAudit")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IllegalStateException("could not create hidden pending MediaStore item")

    private fun copyToPending(context: Context, file: File, uri: Uri): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openOutputStream(uri, "w")?.use { sink ->
            file.inputStream().buffered().use { source ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                }
                sink.flush()
                if (sink is FileOutputStream) sink.fd.sync()
            }
        } ?: throw IllegalStateException("MediaStore output stream unavailable")
        return DigestResult(bytes, digest.digest().hex())
    }

    private fun digestUri(context: Context, uri: Uri): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
        } ?: throw IllegalStateException("MediaStore read-back stream unavailable")
        return DigestResult(bytes, digest.digest().hex())
    }

    private fun queryPending(context: Context, uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        } ?: false

    private fun verifySeekableMedia(context: Context, uri: Uri, durationMs: Long): JSONObject {
        val result = JSONObject().put("passed", false)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var audioTrack = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = index
                if (audioTrack < 0 && mime.startsWith("audio/")) audioTrack = index
            }
            val midpointUs = (durationMs.coerceAtLeast(1L) * 500L).coerceAtLeast(1_000_000L)
            val videoRead = readSampleAfterSeek(extractor, videoTrack, midpointUs)
            val audioRead = if (audioTrack >= 0) readSampleAfterSeek(extractor, audioTrack, midpointUs) else true
            result.put("video_track_found", videoTrack >= 0)
                .put("audio_track_found", audioTrack >= 0)
                .put("video_midpoint_sample_read", videoRead)
                .put("audio_midpoint_sample_read", audioRead)
                .put("passed", videoTrack >= 0 && videoRead && audioRead && (audioTrack < 0 || audioRead))
        } catch (error: Exception) {
            result.put("failure_type", error.javaClass.simpleName)
        } finally {
            extractor.release()
        }
        return result
    }

    private fun readSampleAfterSeek(extractor: MediaExtractor, track: Int, atUs: Long): Boolean {
        if (track < 0) return false
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val maxInput = (if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1024 * 1024).coerceIn(64 * 1024, 8 * 1024 * 1024)
        val buffer = java.nio.ByteBuffer.allocate(maxInput)
        extractor.selectTrack(track)
        return try {
            extractor.seekTo(atUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val bytes = extractor.readSampleData(buffer, 0)
            bytes > 0 && mime.isNotBlank() && extractor.sampleTime >= 0L
        } finally {
            extractor.unselectTrack(track)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TARGET_BYTES = 327_950_051L
        const val TARGET_SHA256 = "f8efa4744c70482b7f2f550ab353f70ba83105c2fd7651098b55a419017dd7cd"
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_REPORT_BYTES = 10 * 1024
    }
}
