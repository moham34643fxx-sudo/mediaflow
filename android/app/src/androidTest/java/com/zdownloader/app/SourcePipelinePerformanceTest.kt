package com.zdownloader.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in real-source measurement through the unchanged production queue and strict validator. */
class SourcePipelinePerformanceTest {
    private data class Digest(val bytes: Long, val sha256: String)
    private fun digest(input: InputStream): Digest = input.use {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024); var bytes = 0L
        while (true) { val n = it.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); bytes += n }
        Digest(bytes, hash.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
    }

    @Test fun userSourceCompletesThroughStrictValidationAndPublication(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val source = requireNotNull(args.getString("probeSource"))
        val run = requireNotNull(args.getString("evidenceRun")); require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "source-performance/$run").also { check(it.mkdirs()) }
        val inspectOnly = args.getString("inspectOnly") == "true"
        val maxBytes = 96L * 1024 * 1024
        val report = JSONObject().put("source", source).put("inspect_only", inspectOnly).put("media_byte_limit", maxBytes)
            .put("scope", "actual installed production analysis/queue/transfer/mux/full strict A/publication; single source observation, not a before-after improvement claim")
        val power = app.getSystemService(android.os.PowerManager::class.java)
        fun conditions() = JSONObject().put("thermal_status", power.currentThermalStatus).put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive).put("uid_rx_bytes", android.net.TrafficStats.getUidRxBytes(app.applicationInfo.uid))
        var activity: MainActivity? = null
        var id: String? = null
        var observer: Job? = null
        var phase = "PREFLIGHT"
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            report.put("conditions_before", conditions()).put("python_started_before_analysis", com.chaquo.python.Python.isStarted())
            app.extractor.clearMetadataCache(); AnalysisTrace.clear()
            val began = SystemClock.elapsedRealtime()
            phase = "SOURCE_ANALYSIS"
            val media = withTimeout(100_000) { app.extractor.analyze(source, "source-$run", forceRefresh = true, enrichOptional = false) }
            report.put("analysis_ms", SystemClock.elapsedRealtime() - began).put("title", media.title).put("duration_seconds", media.duration)
                .put("analysis_trace", JSONArray(AnalysisTrace.snapshot()))
                .put("formats", JSONArray(media.formats.map { format ->
                    JSONObject().put("id", format.id).put("ext", format.ext).put("width", format.width).put("height", format.height)
                        .put("video_codec", format.videoCodec).put("audio_codec", format.audioCodec).put("protocol", format.protocol)
                        .put("bytes", format.size(media.duration)?.bytes).put("size_estimated", format.size(media.duration)?.estimated)
                        .put("fragment_count", JSONObject(format.payload).optJSONArray("fragments")?.length())
                }))
            if (inspectOnly) { report.put("passed", true); return@runBlocking }
            val target = args.getString("targetHeight")?.toInt() ?: 360
            val requestedId = args.getString("formatId")
            val choices = media.videoOptions().filter { it.height in 1..720 && (requestedId == null || it.id == requestedId) }
                .sortedWith(compareBy<Format> { kotlin.math.abs(requireNotNull(it.height) - target) }.thenBy { it.size(media.duration)?.bytes ?: Long.MAX_VALUE })
            val selection = choices.mapNotNull { format -> runCatching { Selection.video(media, format, "mp4") }.getOrNull() }
                .firstOrNull { it.transferSize?.bytes?.let { bytes -> bytes in 1..maxBytes } == true }
                ?: error("No selected complete MP4-compatible representation with an estimated size within 96 MiB; no unbounded download attempted")
            report.put("selection", JSONObject().put("video_id", selection.primary.id).put("audio_id", selection.secondary?.id)
                .put("video_protocol", selection.primary.protocol).put("audio_protocol", selection.secondary?.protocol)
                .put("width", selection.primary.width).put("height", selection.primary.height).put("video_codec", selection.primary.videoCodec)
                .put("audio_codec", selection.secondary?.audioCodec ?: selection.primary.audioCodec).put("output", selection.output)
                .put("estimated_transfer_bytes", selection.transferSize?.bytes))
            val job = app.repository.create(media, selection, "Source performance $run"); id = job
            val localHash = AtomicReference<Digest?>(); val largestBytes = AtomicLong(); val firstProgressMs = AtomicLong(-1)
            val states = linkedSetOf<String>()
            val queueStart = SystemClock.elapsedRealtime()
            observer = launch(Dispatchers.IO) {
                app.repository.downloads.collect { rows -> rows.firstOrNull { it.id == job }?.let { row ->
                    states += row.status; largestBytes.accumulateAndGet(row.bytes, ::maxOf)
                    if (row.bytes > maxBytes) { app.repository.cancel(job); app.coordinator.stopAndAwait(job); error("96 MiB transfer guard exceeded") }
                    if (row.bytes > 0) firstProgressMs.compareAndSet(-1, SystemClock.elapsedRealtime() - queueStart)
                    if (localHash.get() == null && row.status in setOf("VALIDATING", "SAVING")) {
                        val output = File(app.storage.temporary(job), "output.${selection.output}")
                        if (output.isFile) localHash.set(digest(output.inputStream()))
                    }
                } }
            }
            phase = "TRANSFER_PROCESSING_PUBLICATION"
            withTimeout(780_000) { app.coordinator.run() }
            val queueEnd = SystemClock.elapsedRealtime()
            val completed = requireNotNull(app.repository.dao.get(job))
            report.put("queue_wall_ms", queueEnd - queueStart).put("final_status", completed.status).put("job_error", completed.error)
            assertEquals(completed.error, "COMPLETED", completed.status)
            withTimeout(10_000) { while (localHash.get() == null) delay(25) }
            observer.cancelAndJoin(); observer = null
            phase = "INDEPENDENT_PUBLICATION_VERIFICATION"
            val uri = Uri.parse(requireNotNull(completed.uri))
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(completed.outputSize, it.getLong(1))
            }
            val readbackStart = SystemClock.elapsedRealtime()
            val published = digest(requireNotNull(app.contentResolver.openInputStream(uri)))
            assertEquals(requireNotNull(localHash.get()), published)
            report.put("independent_readback_ms", SystemClock.elapsedRealtime() - readbackStart).put("source_to_verified_publication_ms", SystemClock.elapsedRealtime() - began)
                .put("output_bytes", published.bytes).put("output_sha256", published.sha256).put("is_pending", 0)
                .put("local_vs_published_sha_match", true).put("largest_observed_transfer_bytes", largestBytes.get()).put("first_progress_ms", firstProgressMs.get())
                .put("observed_states", JSONArray(states.toList()))
            val retained = File(directory, "published-output.${selection.output}")
            requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> retained.outputStream().use { input.copyTo(it) } }
            assertEquals(published, digest(retained.inputStream()))
            val info = requireNotNull(FFprobeKit.getMediaInformation(retained.path).mediaInformation)
            val video = info.streams.first { it.type == "video" }; val audio = info.streams.first { it.type == "audio" }
            assertEquals(selection.primary.width, video.width.toInt()); assertEquals(selection.primary.height, video.height.toInt())
            assertEquals(if (selection.transcodeVideo) "h264" else codecFamily(selection.primary.videoCodec), codecFamily(video.codec)); assertEquals("aac", audio.codec)
            val actualDuration = info.duration.toDouble()
            report.put("actual_duration_seconds", info.duration)
                .put("actual_video_codec", video.codec)
                .put("actual_audio_codec", audio.codec)
            val oracleBegan = SystemClock.elapsedRealtime()
            val oracle = try {
                SourcePipelineDurationOracle.resolve(media, selection)
            } finally {
                report.put("duration_oracle_resolve_ms", SystemClock.elapsedRealtime() - oracleBegan)
            }
            report.put("duration_oracle_kind", oracle?.kind)
                .put("duration_oracle_expected", oracle?.expectedDuration)
            val expectedDuration = if (selection.primary.protocol.startsWith("m3u8")) {
                requireNotNull(oracle?.expectedDuration) { "Authoritative HLS duration oracle unavailable" }
            } else {
                oracle?.expectedDuration
            }
            expectedDuration?.let { assertEquals(it, actualDuration, 0.25) }
            var records = emptyList<JSONObject>()
            withTimeout(10_000) {
                while (true) {
                    records = listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl").flatMap { name ->
                        File(app.filesDir, name).takeIf { it.isFile }?.readLines().orEmpty()
                    }.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.filter { it.optString("operation") == job }
                    if (records.any { it.optString("stage") == "video_pipeline_profiling" }) break
                    delay(100)
                }
            }
            report.put("diagnostics", JSONArray(records)).put("events", JSONArray(app.repository.dao.events(job).map { it.state }))
                .put("conditions_after", conditions()).put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure_phase", phase).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                observer?.cancelAndJoin()
                id?.let { job ->
                    app.coordinator.stopAndAwait(job)
                    app.repository.dao.get(job)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                    app.repository.cancel(job); app.repository.remove(job); app.storage.removeTemporary(job)
                }
                withContext(Dispatchers.Main) { activity?.finish() }
                report.put("analysis_trace", JSONArray(AnalysisTrace.snapshot()))
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
}
