package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Executes unchanged installed app code: usable with both retained build77 and candidate78. */
class LocalPipelineComparisonTest {
    private data class Digest(val bytes: Long, val sha256: String)
    private fun digest(file: File): Digest = file.inputStream().use(::digest)
    private fun digest(input: java.io.InputStream): Digest {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024); var bytes = 0L
        while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size); bytes += size }
        return Digest(bytes, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    @Test fun replaysRetainedAcquisitionThroughActualQueueValidationAndPublication(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")); require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "local-pipeline-comparison/$run").also { check(it.mkdirs()) }
        val input = File(app.getExternalFilesDir(null), "direct-engineering/vp9-paired78-05fc58/vp9-strict-timing/retained-vp9-opus.webm")
        val expected = "a8582d44c067fd5c89f916f2908b952477f867a9648f984f596cbc74dc00c79e"
        val id = "local-pair-${UUID.randomUUID()}"
        val report = JSONObject().put("scope", "measured LOCAL acquisition replay + actual QueueCoordinator inspection/audio conversion/mux/full strict A/MediaStore publication/readback; no network extraction or throughput claim")
            .put("arm", args.getString("comparisonArm")).put("installed_version_code", app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
        val settings = SettingsRepository(app); val old = settings.current()
        val power = app.getSystemService(android.os.PowerManager::class.java)
        fun conditions(): JSONObject {
            val memory = android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
            val battery = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return JSONObject().put("thermal_status", power.currentThermalStatus).put("power_save", power.isPowerSaveMode)
                .put("interactive", power.isInteractive).put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                .put("available_processors", Runtime.getRuntime().availableProcessors()).put("available_memory_bytes", memory.availMem)
                .put("low_memory", memory.lowMemory).put("battery_temperature_tenths_c", battery?.getIntExtra("temperature", -1))
                .put("battery_plugged", battery?.getIntExtra("plugged", -1))
        }
        var activity: MainActivity? = null
        var observer: Job? = null
        val localHash = AtomicReference<Digest?>()
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            assertEquals(Digest(42615077, expected), digest(input))
            settings.boolean("completion_notifications", false)
            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertFalse("comparison requires controlled unlocked execution", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val info = requireNotNull(FFprobeKit.getMediaInformation(input.path).mediaInformation)
            val video = info.streams.first { it.type == "video" }
            assertEquals("vp9", video.codec); assertEquals("opus", info.streams.first { it.type == "audio" }.codec)
            val format = Format.parse(JSONObject().put("format_id", "retained-vp9-opus").put("url", "https://fixture.invalid/$id")
                .put("ext", "webm").put("protocol", "https").put("vcodec", "vp9").put("acodec", "opus")
                .put("width", video.width).put("height", video.height).put("filesize", input.length()))
            val selection = Selection(format, output = "mp4", duration = info.duration.toDouble(), embedMetadata = false)
            val scratch = app.storage.temporary(id)
            report.put("conditions_before", conditions()).put("input_sha256", expected).put("input_bytes", input.length()).put("input_duration_seconds", info.duration)
            val cpuBefore = android.os.Process.getElapsedCpuTime()
            val totalStarted = SystemClock.elapsedRealtime()
            val source = File(scratch, "primary.webm")
            java.io.FileOutputStream(source).use { output -> input.inputStream().use { it.copyTo(output, 1024 * 1024) }; output.fd.sync() }
            val stagedAt = SystemClock.elapsedRealtime()
            assertEquals(Digest(42615077, expected), digest(source))
            report.put("source_staging_ms", stagedAt - totalStarted).put("staged_readback_sha_ms", SystemClock.elapsedRealtime() - stagedAt)
            app.repository.dao.insert(DownloadEntity(id, "https://fixture.invalid/$id", "Local paired pipeline", "Retained fixture", null,
                selection.duration, selection.json(), "$id.mp4", null, id, status = "QUEUED", checkpoint = "DOWNLOADED"))
            observer = launch(Dispatchers.IO) {
                app.repository.downloads.collect { rows ->
                    if (localHash.get() == null && rows.firstOrNull { it.id == id }?.status in setOf("VALIDATING", "SAVING")) {
                        val output = File(scratch, "output.mp4")
                        if (output.isFile) localHash.set(digest(output))
                    }
                }
            }
            val queueStarted = SystemClock.elapsedRealtime()
            withTimeout(180_000) { app.coordinator.run() }
            val queueReturned = SystemClock.elapsedRealtime()
            val completed = requireNotNull(app.repository.dao.get(id))
            assertEquals(completed.error, "COMPLETED", completed.status)
            withTimeout(10_000) { while (localHash.get() == null) delay(25) }
            observer.cancelAndJoin(); observer = null
            val uri = android.net.Uri.parse(requireNotNull(completed.uri))
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(completed.outputSize, it.getLong(1))
            }
            val readbackStarted = SystemClock.elapsedRealtime()
            val published = requireNotNull(app.contentResolver.openInputStream(uri)).use(::digest)
            assertEquals(requireNotNull(localHash.get()), published)
            val finished = SystemClock.elapsedRealtime()
            report.put("queue_call_ms", queueReturned - queueStarted).put("independent_published_readback_ms", finished - readbackStarted)
                .put("full_local_pipeline_wall_ms", finished - totalStarted).put("process_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuBefore)
                .put("output_bytes", published.bytes).put("output_sha256", published.sha256).put("is_pending", 0)
                .put("local_vs_published_sha_match", true).put("conditions_after", conditions())
                .put("timing_boundary", "starts before local input copy/fsync and ends after completed QueueCoordinator and independent published readback; includes extra observer SHA read, excludes preparation and cleanup")
            val diagnostics = listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl").flatMap { name ->
                File(app.filesDir, name).takeIf { it.isFile }?.readLines().orEmpty()
            }.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.filter { it.optString("operation") == id }
            report.put("measured_stage_records", JSONArray(diagnostics.filter { it.optString("stage") in setOf("video_mux_timing", "video_validation_timing", "mediastore_export_timing", "video_pipeline_profiling", "vp9_thread_calibration") }))
                .put("events", JSONArray(app.repository.dao.events(id).map { it.state }))
                .put("input_unchanged", digest(input).sha256 == expected).put("passed", true)
            assertTrue(report.getBoolean("input_unchanged"))
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                observer?.cancelAndJoin()
                app.coordinator.stopAndAwait(id)
                app.repository.dao.get(id)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                app.repository.cancel(id); app.repository.remove(id); app.storage.removeTemporary(id)
                settings.boolean("completion_notifications", old.completionNotifications)
                withContext(Dispatchers.Main) { activity?.finish() }
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
}
