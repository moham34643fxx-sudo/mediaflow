package com.zdownloader.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.playback.MediaPlaybackService
import com.zdownloader.app.playback.PlaybackRuntime
import com.zdownloader.app.playback.PlayerActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local-only real native/Android boundary checks. Never touches an existing MediaStore item. */
class VideoPipelineEngineeringTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ZApplication
    private fun evidence(name: String): File {
        val run = InstrumentationRegistry.getArguments().getString("evidenceRun") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return File(app.getExternalFilesDir(null), "direct-engineering/$run/$name").also { check(it.mkdirs()) }
    }
    private suspend fun execute(args: List<String>, timeoutMs: Long = 60_000) {
        val finished = CompletableDeferred<com.arthenica.ffmpegkit.FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync((listOf("-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe") + args).toTypedArray(), { finished.complete(it) })
        try {
            val completed = withTimeout(timeoutMs) { finished.await() }
            check(ReturnCode.isSuccess(completed.returnCode)) { "fixture command failed: ${completed.allLogsAsString.takeLast(600)}" }
        } finally {
            if (!finished.isCompleted) FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { withTimeout(10_000) { finished.await() } }
        }
    }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun cleanupOnlyInterruptedSyntheticBackgroundFixture(): Unit = runBlocking {
        val id = "66df09ee-d16b-4cb8-be2b-0469bf130a0b"
        val row = app.repository.dao.get(id)
        if (row != null) {
            check(row.url.startsWith("https://mediaflow-long-fixture.invalid/") && row.filename.contains("background QA"))
            app.plannedDownloads.cancel(id)
            app.repository.cancel(id); app.coordinator.stopAndAwait(id)
            app.repository.remove(id)
        }
        assertNull(app.repository.dao.get(id))
    }

    @Test fun actualWebmAudioMustBecomeAacEvenWhenMetadataClaimsAac(): Unit = runBlocking {
        val reportDir = evidence("actual-audio-codec")
        val scratch = File(app.cacheDir, "audio-codec-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val report = JSONObject().put("scope", "local codec regression; full strict A retained")
        try {
            val asset = File(scratch, "asset.mp4")
            instrument.context.assets.open("status-original.mp4").use { input -> asset.outputStream().use { input.copyTo(it) } }
            val cases = org.json.JSONArray()
            report.put("cases", cases)
            for (codec in listOf("opus", "vorbis")) {
            val source = File(scratch, "actual-$codec.webm")
            execute(listOf("-i", asset.path, "-t", "3", "-map", "0:a:0", "-ac", "2", "-c:a", codec, "-strict", "-2", source.path))
            assertEquals(codec, FFprobeKit.getMediaInformation(source.path).mediaInformation.streams.single().codec)
            val format = Format.parse(JSONObject().put("format_id", "advertised-aac").put("url", "https://fixture.invalid/audio")
                .put("ext", "m4a").put("vcodec", "none").put("acodec", "aac").put("protocol", "https"))
            val output = File(scratch, "output-$codec.m4a")
            val started = SystemClock.elapsedRealtime()
            Processor().process(source, null, output, Selection(format, audioOnly = true, output = "m4a", convertAudio = false), TransferControl(), expectedDuration = 3.0)
            val info = FFprobeKit.getMediaInformation(output.path).mediaInformation
            val audio = info.streams.single { it.type == "audio" }
            cases.put(JSONObject().put("input_codec", codec).put("processing_ms", SystemClock.elapsedRealtime() - started)
                .put("actual_output_codec", audio.codec).put("output_duration_seconds", info.duration))
            assertEquals("m4a must contain actual AAC, never renamed/copied WebM audio", "aac", audio.codec)
            assertEquals(3.0, info.duration.toDouble(), 0.15)
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            report.put("owned_scratch_deleted", scratch.deleteRecursively())
            File(reportDir, "result.json").writeText(report.toString(2))
        }
    }

    @Test fun queueLoadsArtworkOnlyWhenTheSelectedOutputCanEmbedIt(): Unit = runBlocking {
        val directory = evidence("unused-artwork-regression")
        val scratch = File(app.cacheDir, "artwork-work-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val report = JSONObject(); val cases = org.json.JSONArray(); report.put("cases", cases)
        val settings = com.zdownloader.app.data.SettingsRepository(app); val old = settings.current()
        var id: String? = null
        var watcher: android.os.FileObserver? = null
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            settings.boolean("completion_notifications", false)
            val asset = File(scratch, "asset.mp4")
            instrument.context.assets.open("status-original.mp4").use { input -> asset.outputStream().use { input.copyTo(it) } }
            val source = File(scratch, "source.webm")
            execute(listOf("-i", asset.path, "-t", "3", "-map", "0:a:0", "-ac", "2", "-c:a", "opus", "-strict", "-2", source.path))
            val cover = File(scratch, "cover.jpg")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.RED); cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
            finally { bitmap.recycle() }
            val format = Format.parse(JSONObject().put("format_id", "artwork-source").put("url", "https://fixture.invalid/audio")
                .put("ext", "webm").put("vcodec", "none").put("acodec", "opus").put("protocol", "https"))
            for ((container, embed) in listOf("mp3" to false, "m4a" to true, "mp3" to true)) {
                val job = "artwork-work-${UUID.randomUUID()}"; id = job
                val work = app.storage.temporary(job); source.copyTo(File(work, "primary.webm"))
                val selection = Selection(format, audioOnly = true, output = container, convertAudio = true, duration = 3.0, embedMetadata = embed)
                val count = java.util.concurrent.atomic.AtomicInteger()
                val ready = CountDownLatch(1); val readDuringQueue = CountDownLatch(1); val armed = java.util.concurrent.atomic.AtomicBoolean()
                watcher = object : android.os.FileObserver(cover.path, android.os.FileObserver.OPEN) {
                    override fun onEvent(event: Int, path: String?) {
                        if (event and android.os.FileObserver.OPEN != 0) { if (armed.get()) { count.incrementAndGet(); readDuringQueue.countDown() } else ready.countDown() }
                    }
                }.also { it.startWatching() }
                cover.inputStream().use { it.read() }; assertTrue("real filesystem observer must be ready", ready.await(2, TimeUnit.SECONDS)); armed.set(true)
                app.repository.dao.insert(DownloadEntity(job, "https://fixture.invalid/$job", "Artwork regression", "Owned synthetic fixture", Uri.fromFile(cover).toString(),
                    3.0, selection.json(), "$job.$container", null, job, status = "QUEUED", checkpoint = "DOWNLOADED"))
                val started = SystemClock.elapsedRealtime()
                withTimeout(30_000) { app.coordinator.run() }
                val elapsed = SystemClock.elapsedRealtime() - started
                val completed = requireNotNull(app.repository.dao.get(job)); assertEquals(completed.error, "COMPLETED", completed.status)
                val read = readDuringQueue.await(500, TimeUnit.MILLISECONDS); watcher.stopWatching(); watcher = null
                val expectedRead = container == "mp3" && embed
                val row = JSONObject().put("container", container).put("embed_metadata", embed).put("queue_ms", elapsed)
                    .put("cover_open_count", count.get()).put("expected_cover_read", expectedRead).put("observed_cover_read", read)
                cases.put(row)
                assertEquals("Unused artwork must not be copied, decoded or fetched before processing", expectedRead, read)
                val retained = File(directory, "published-$container-$embed.$container")
                requireNotNull(app.contentResolver.openInputStream(Uri.parse(requireNotNull(completed.uri)))).use { input -> retained.outputStream().use { input.copyTo(it) } }
                val info = requireNotNull(FFprobeKit.getMediaInformation(retained.path).mediaInformation)
                assertEquals(if (container == "mp3") "mp3" else "aac", info.streams.first { it.type == "audio" }.codec)
                assertEquals(expectedRead, info.streams.any { it.type == "video" && it.allProperties.optJSONObject("disposition")?.optInt("attached_pic") == 1 })
                assertEquals(3.0, info.duration.toDouble(), 0.15)
                row.put("output_sha256", sha(retained)).put("passed", true)
                app.storage.deleteFile(completed); app.repository.remove(job); app.storage.removeTemporary(job); id = null
            }
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}"); throw error }
        finally {
            withContext(NonCancellable) {
                watcher?.stopWatching()
                id?.let { job ->
                    app.coordinator.stopAndAwait(job)
                    app.repository.dao.get(job)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                    app.repository.cancel(job); app.repository.remove(job); app.storage.removeTemporary(job)
                }
                settings.boolean("completion_notifications", old.completionNotifications)
                scratch.deleteRecursively(); File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }

    @Test fun webmOpusAndVorbisPublishAsOriginalAacAndGenuineMp3(): Unit = runBlocking {
        val directory = evidence("webm-publication-matrix")
        val scratch = File(app.cacheDir, "webm-matrix-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val cases = org.json.JSONArray()
        val report = JSONObject().put("scope", "six local codec/container cases through actual downloaded checkpoint, full strict A and MediaStore; no network-speed claim").put("cases", cases)
        val settings = com.zdownloader.app.data.SettingsRepository(app); val old = settings.current()
        var id: String? = null
        var observer: Job? = null
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            settings.boolean("completion_notifications", false)
            val asset = File(scratch, "asset.mp4")
            instrument.context.assets.open("status-original.mp4").use { input -> asset.outputStream().use { input.copyTo(it) } }
            for (codec in listOf("opus", "vorbis")) {
                val source = File(scratch, "source-$codec.webm")
                execute(listOf("-i", asset.path, "-t", "3", "-map", "0:a:0", "-ac", "2", "-c:a", codec, "-strict", "-2", source.path))
                val originalHash = sha(source)
                assertEquals(codec, FFprobeKit.getMediaInformation(source.path).mediaInformation.streams.single().codec)
                val format = Format.parse(JSONObject().put("format_id", "actual-$codec").put("url", "https://fixture.invalid/audio")
                    .put("ext", "webm").put("vcodec", "none").put("acodec", codec).put("protocol", "https").put("filesize", source.length()))
                for (container in listOf("webm", "m4a", "mp3")) {
                    val job = "webm-matrix-${UUID.randomUUID()}"; id = job
                    val work = app.storage.temporary(job)
                    source.copyTo(File(work, "primary.webm"))
                    val selection = Selection(format, audioOnly = true, output = container, convertAudio = container != "webm", duration = 3.0, embedMetadata = false)
                    val row = JSONObject().put("input_codec", codec).put("output_container", container).put("job_id", job)
                    cases.put(row)
                    app.repository.dao.insert(DownloadEntity(job, "https://fixture.invalid/$job", "WebM matrix", "Owned synthetic fixture", null,
                        3.0, selection.json(), "$job.$container", null, job, status = "QUEUED", checkpoint = "DOWNLOADED"))
                    val beforePublication = java.util.concurrent.atomic.AtomicReference<String?>()
                    observer = launch(Dispatchers.IO) {
                        app.repository.downloads.collect { rows ->
                            if (beforePublication.get() == null && rows.firstOrNull { it.id == job }?.status in setOf("VALIDATING", "SAVING")) {
                                val output = File(work, "output.$container")
                                if (output.isFile) beforePublication.set(sha(output))
                            }
                        }
                    }
                    val started = SystemClock.elapsedRealtime()
                    withTimeout(30_000) { app.coordinator.run() }
                    val completed = requireNotNull(app.repository.dao.get(job))
                    assertEquals(completed.error, "COMPLETED", completed.status)
                    withTimeout(5_000) { while (beforePublication.get() == null) delay(10) }
                    observer.cancelAndJoin(); observer = null
                    val uri = Uri.parse(requireNotNull(completed.uri))
                    app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(completed.outputSize, it.getLong(1))
                        if (container == "webm") {
                            // MediaProvider can normalize finalized .webm to video/webm;
                            // the single actual audio stream is verified below.
                            assertTrue(it.getString(2) in setOf("audio/webm", "video/webm")); assertTrue(it.getString(3).startsWith("Download/MediaFlow"))
                        }
                        row.put("published_mime", it.getString(2)).put("published_relative_path", it.getString(3))
                    }
                    val readback = File(directory, "published-$codec.$container")
                    requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> readback.outputStream().use { input.copyTo(it) } }
                    assertEquals(completed.outputSize, readback.length()); assertEquals(beforePublication.get(), sha(readback))
                    val info = requireNotNull(FFprobeKit.getMediaInformation(readback.path).mediaInformation)
                    val audio = info.streams.single { it.type == "audio" }
                    val expected = when (container) { "m4a" -> "aac"; "mp3" -> "mp3"; else -> codec }
                    assertEquals(expected, audio.codec); assertEquals(2, audio.allProperties.getInt("channels"))
                    assertEquals(3.0, info.duration.toDouble(), 0.15)
                    val events = app.repository.dao.events(job).map { it.state }
                    assertTrue(events.containsAll(listOf("VALIDATING", "SAVING", "COMPLETED")))
                    assertEquals(originalHash, sha(source))
                    row.put("actual_audio_codec", audio.codec).put("duration_seconds", info.duration).put("output_bytes", readback.length())
                        .put("wall_ms", SystemClock.elapsedRealtime() - started).put("sha256", sha(readback)).put("is_pending", 0)
                        .put("local_vs_published_sha_match", true).put("input_unchanged", true).put("passed", true)
                    app.storage.deleteFile(completed); app.repository.remove(job); app.storage.removeTemporary(job); id = null
                }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                observer?.cancelAndJoin()
                id?.let { job ->
                    app.coordinator.stopAndAwait(job)
                    app.repository.dao.get(job)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                    app.repository.cancel(job); app.repository.remove(job); app.storage.removeTemporary(job)
                }
                settings.boolean("completion_notifications", old.completionNotifications)
                report.put("owned_scratch_deleted", scratch.deleteRecursively())
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }

    @Test fun retainedActualDownloadPlaysThroughOwnedContentUri(): Unit = runBlocking {
        val directory = evidence("retained-content-playback")
        val input = File(app.getExternalFilesDir(null), "pipeline-acceptance/network-sanity-retry-8e1b87/published-output.mp4")
        val expected = "16dfd2e70155204c6b0ff0556fb214edbdb4d347769b86726e67ade99e573ae3"
        val report = JSONObject().put("scope", "offline replay of actual downloaded bytes through a new owned MediaStore content URI")
        var uri: Uri? = null
        var activity: PlayerActivity? = null
        val oldQueue = app.playback.repository.savedQueue()
        val started = SystemClock.elapsedRealtime()
        var phase = "PUBLISH_OWNED_COPY"
        val lifecycle = org.json.JSONArray()
        val states = org.json.JSONArray()
        var observeState: Job? = null
        val observer = androidx.test.runner.lifecycle.ActivityLifecycleCallback { current, stage ->
            if (current.packageName == app.packageName) synchronized(lifecycle) {
                lifecycle.put(JSONObject().put("activity", current.javaClass.simpleName).put("stage", stage.name)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                    .put("interactive", app.getSystemService(android.os.PowerManager::class.java).isInteractive))
            }
        }
        withContext(Dispatchers.Main) { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer) }
        try {
            assertFalse("visible playback verification requires an unlocked device", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            observeState = launch(Dispatchers.Main.immediate) {
                app.playback.state.collect { state ->
                    if (states.length() < 400) states.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("phase", phase).put("diagnostic", app.playback.diagnostic)
                        .put("has_current", state.current != null).put("video", state.current?.video)
                        .put("queue_size", state.queue.size).put("position_ms", state.position).put("duration_ms", state.duration)
                        .put("playing", state.playing).put("ready", state.ready).put("buffering", state.buffering).put("error", state.error)
                        .put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked))
                }
            }
            assertEquals(expected, sha(input))
            uri = requireNotNull(app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "engineering-${UUID.randomUUID()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MediaFlow/Engineering/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { output -> input.inputStream().use { it.copyTo(output) } }
            val copied = File(directory, "readback.mp4")
            requireNotNull(app.contentResolver.openInputStream(uri)).use { source -> copied.outputStream().use { source.copyTo(it) } }
            assertEquals(expected, sha(copied)); check(copied.delete())
            assertEquals(1, app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            phase = "OPEN_APP_PLAYER"
            activity = instrument.startActivitySync(Intent(app, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) as PlayerActivity
            await { app.playback.state.value.let { check(it.error == null) { "player error ${it.error}; ${app.playback.diagnostic}" }; it.current?.video == true && it.playing && it.position > 500 } }
            phase = "SEEK_30_SECONDS"
            withContext(Dispatchers.Main) {
                val p = requireNotNull(app.playback.player.value)
                p.repeatMode = Player.REPEAT_MODE_OFF
                assertTrue(p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                assertTrue(p.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
                p.seekTo(30_000)
            }
            await { app.playback.state.value.position in 29_000..40_000 && app.playback.state.value.playing }
            phase = "CAPTURE_ACTUAL_SURFACE"
            var surface: View? = null
            await { surface = findSurface(requireNotNull(activity).window.decorView); when (val v = surface) { is SurfaceView -> v.holder.surface.isValid; is TextureView -> v.isAvailable; else -> false } }
            val frame = if (surface is TextureView) withContext(Dispatchers.Main) { requireNotNull((surface as TextureView).getBitmap(320, 180)) } else {
                val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1); var status = -1
                withContext(Dispatchers.Main) { PixelCopy.request(surface as SurfaceView, bitmap, { status = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
                check(withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) } && status == PixelCopy.SUCCESS)
                bitmap
            }
            try {
                val pixels = IntArray(frame.width * frame.height); frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                val levels = pixels.map { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 }
                assertTrue("actual video surface must not be blank", levels.max() - levels.min() > 20)
                File(directory, "actual-surface.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { frame.recycle() }
            val duration = withContext(Dispatchers.Main) { app.playback.state.value.duration }
            assertTrue(duration > 45_000)
            phase = "SEEK_MIDPOINT"
            withContext(Dispatchers.Main) { app.playback.seek(duration / 2) }
            await { kotlin.math.abs(app.playback.state.value.position - duration / 2) < 5_000 }
            phase = "PLAY_TO_END"
            withContext(Dispatchers.Main) { app.playback.seek(duration - 2_000); app.playback.player.value!!.play() }
            await { app.playback.player.value?.playbackState == Player.STATE_ENDED }
            report.put("passed", true).put("duration_ms", duration).put("nonblank_pixels", true).put("selected_audio_and_video", true)
                .put("midpoint_seek", true).put("ended_state", Player.STATE_ENDED)
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}").put("failure_phase", phase)
                .put("connection_diagnostic", app.playback.diagnostic)
                .put("player_state", withContext(Dispatchers.Main) { app.playback.state.value.toString() })
                .put("controller", withContext(Dispatchers.Main) {
                    app.playback.player.value?.let { controller -> JSONObject().put("connected", controller.isConnected)
                        .put("items", controller.mediaItemCount).put("playback_state", controller.playbackState)
                        .put("play_when_ready", controller.playWhenReady).put("player_error", controller.playerError?.errorCode)
                        .put("activity_finishing", activity?.isFinishing).put("activity_destroyed", activity?.isDestroyed)
                        .put("locked", app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                        .put("interactive", app.getSystemService(android.os.PowerManager::class.java).isInteractive) }
                })
            throw error
        } finally {
            withContext(NonCancellable) {
                observeState?.cancelAndJoin()
                withContext(Dispatchers.Main) { activity?.finish(); app.playback.clear(); app.playback.disconnect(); app.stopService(Intent(app, MediaPlaybackService::class.java)) }
                withTimeout(15_000) { while (PlaybackRuntime.active) delay(50) }
                app.playback.repository.saveQueue(oldQueue.first, oldQueue.second)
                uri?.let { assertEquals(1, app.contentResolver.delete(it, null, null)) }
                report.put("retained_input_unchanged", input.isFile && sha(input) == expected).put("elapsed_ms_including_cleanup", SystemClock.elapsedRealtime() - started)
                withContext(Dispatchers.Main) { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer) }
                report.put("activity_lifecycle", lifecycle).put("state_timeline", states)
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
    @Test fun measuresFullStrictVp9ValidationOnRetainedTenMinuteFixture(): Unit = runBlocking {
        val directory = evidence("vp9-strict-timing")
        val report = JSONObject().put("scope", "measured local strict validation; no network or B fast path")
        val provided = InstrumentationRegistry.getArguments().getString("vp9Fixture")
        val fixture = provided?.let(::File) ?: File(directory, "retained-vp9-opus.webm")
        var activity: MainActivity? = null
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            // Control the benchmark's execution environment. Background ownership
            // is tested separately through the real scheduler, not inferred here.
            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            fun phase(value: String) { File(directory, "phase.json").writeText(JSONObject().put("phase", value).put("elapsed", SystemClock.elapsedRealtime()).toString()) }
            phase("prepare_or_reuse_fixture")
            if (provided == null) {
                // Exact ID and digest protect against selecting a duplicate or unrelated download.
                val original = File(directory, "owned-source-copy.webm")
                try {
                    requireNotNull(app.contentResolver.openInputStream(Uri.parse("content://media/external/video/media/97218"))).use { source ->
                        original.outputStream().use { source.copyTo(it) }
                    }
                    assertEquals(327950051L, original.length())
                    assertEquals("f8efa4744c70482b7f2f550ab353f70ba83105c2fd7651098b55a419017dd7cd", sha(original))
                    execute(listOf("-i", original.path, "-t", "600", "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                        "-map_metadata", "-1", "-map_chapters", "-1", fixture.path))
                } finally { check(original.delete()) }
            }
            val info = requireNotNull(FFprobeKit.getMediaInformation(fixture.path).mediaInformation)
            assertEquals("vp9", info.streams.first { it.type == "video" }.codec)
            assertTrue(requireNotNull(info.duration).toDouble() >= 600)
            val before = sha(fixture)
            val compareLegacy = InstrumentationRegistry.getArguments().getString("measureLegacyProbes") == "true"
            val legacyFirst = InstrumentationRegistry.getArguments().getString("legacyFirst") != "false"
            report.put("legacy_first", legacyFirst).put("benchmark_activity_visible", true)
            suspend fun measureLegacy() {
                phase("legacy_reference")
                // Replay the retained build77 commands on these exact bytes.
                // This is an actual timed sequential reference, not a sum of estimates.
                val legacyStarted = SystemClock.elapsedRealtime()
                val cpuStarted = android.os.Process.getElapsedCpuTime()
                var automaticMs = 0L; var fourMs = 0L
                fun prefix(threads: Int?, offset: Double? = null): List<String> = buildList {
                    addAll(listOf("-xerror", "-err_detect", "explode", "-hwaccel", "none"))
                    if (threads != null) addAll(listOf("-threads:v", threads.toString()))
                    if (offset != null) addAll(listOf("-ss", offset.toString()))
                    addAll(listOf("-i", fixture.path, "-map", "0:v:0?", "-map", "0:a:0?"))
                }
                val duration = info.duration.toDouble()
                for ((offset, threads) in listOf(duration * .25 to null, duration * .25 to 4, duration * .75 to 4, duration * .75 to null)) {
                    val probeStarted = SystemClock.elapsedRealtime()
                    execute(prefix(threads, offset) + listOf("-t", "15", "-max_error_rate", "0", "-f", "null", "-"))
                    val elapsed = SystemClock.elapsedRealtime() - probeStarted
                    if (threads == null) automaticMs += elapsed else fourMs += elapsed
                }
                val threads = if (automaticMs > 0 && fourMs * 100 <= automaticMs * 85) 4 else null
                execute(prefix(threads) + listOf("-max_error_rate", "0", "-f", "null", "-"), 180_000)
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(fixture.path)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
                    extractor.selectTrack(track)
                    val end = extractor.getTrackFormat(track).getLong(android.media.MediaFormat.KEY_DURATION) - 500_000
                    extractor.seekTo(end, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    check(extractor.sampleSize > 0)
                } finally { extractor.release() }
                report.put("legacy_reference_ms", SystemClock.elapsedRealtime() - legacyStarted)
                    .put("legacy_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStarted)
                    .put("legacy_automatic_probe_ms", automaticMs).put("legacy_four_thread_probe_ms", fourMs)
                    .put("legacy_selected_threads", threads ?: 0)
                    .put("legacy_scope", "timed replay of original four calibration windows, complete strict software AV decode, tail seek; no publication/network")
            }
            if (compareLegacy && legacyFirst) measureLegacy()
            phase("current_strict_validator")
            val started = SystemClock.elapsedRealtime()
            val cpuStarted = android.os.Process.getElapsedCpuTime()
            Processor().validate(fixture, audio = false, requireAudio = true, expectedContainer = "webm", expectedVideoCodec = "vp9")
            report.put("validation_ms", SystemClock.elapsedRealtime() - started)
                .put("validation_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStarted)
                .put("duration_seconds", info.duration).put("fixture_bytes", fixture.length()).put("fixture_sha256", before)
                .put("fixture_path", fixture.path).put("input_unchanged", before == sha(fixture)).put("passed", true)
            assertTrue(report.getBoolean("input_unchanged"))
            if (compareLegacy && !legacyFirst) measureLegacy()
            phase("completed")
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { activity?.finish() }
            File(directory, "result.json").writeText(report.toString(2))
        }
    }
    @Test fun separateWebmOpusMergesValidatesAndPublishesAfterUiTaskRemoval(): Unit = runBlocking {
        check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
        val directory = evidence("background-opus-mp4")
        val id = "engineering-${UUID.randomUUID()}"
        val scratch = app.storage.temporary(id)
        val report = JSONObject().put("scope", "real foreground owner, downloaded-track checkpoint, native mux/strict decode/MediaStore; no network")
        var activity: ShareReceiverActivity? = null
        var release: (() -> Unit)? = null
        try {
            val asset = File(scratch, "asset.mp4")
            instrument.context.assets.open("status-original.mp4").use { input -> asset.outputStream().use { input.copyTo(it) } }
            val video = File(scratch, "primary.mp4")
            val audio = File(scratch, "secondary.webm")
            execute(listOf("-i", asset.path, "-map", "0:v:0", "-c", "copy", "-map_chapters", "-1", video.path))
            execute(listOf("-i", asset.path, "-map", "0:a:0", "-ac", "2", "-c:a", "opus", "-strict", "-2", audio.path))
            val info = FFprobeKit.getMediaInformation(video.path).mediaInformation
            val stream = info.streams.first { it.type == "video" }
            fun format(key: String, ext: String, v: String, a: String) = Format.parse(JSONObject().put("format_id", key)
                .put("url", "https://fixture.invalid/$id/$key").put("ext", ext).put("protocol", "https")
                .put("vcodec", v).put("acodec", a).put("width", stream.width).put("height", stream.height))
            val selection = Selection(format("video", "mp4", "h264", "none"), format("audio", "webm", "none", "opus"),
                output = "mp4", duration = info.duration.toDouble())
            suspend fun videoPackets(file: File, label: String): List<String> {
                val table = File(directory, "$label.video-packets")
                execute(listOf("-i", file.path, "-map", "0:v:0", "-c", "copy", "-f", "framehash", "-hash", "sha256", table.path))
                return table.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.substringAfterLast(',').trim() }
            }
            val originalPackets = videoPackets(video, "input")
            app.repository.dao.insert(DownloadEntity(id, "https://fixture.invalid/$id", id, "Background Opus", null, selection.duration,
                selection.json(), "$id.mp4", null, id, status = "QUEUED", checkpoint = "DOWNLOADED"))
            // Model a source/native writer's lease finishing only after UI closure.
            // No fake processor, validator, scheduler or storage implementation is used.
            val lease = NativeWork.acquire(scratch)
            release = { lease.release() }
            activity = instrument.startActivitySync(Intent(app, ShareReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShareReceiverActivity
            val receipt = app.scheduler.startFromUser(id)
            report.put("execution_owner", receipt.mechanism)
            withContext(Dispatchers.Main) { activity!!.finishAndRemoveTask() }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")).use { it.readBytes() }
            val releasedAt = System.currentTimeMillis()
            release.invoke(); release = null
            withTimeout(60_000) { while (app.repository.dao.get(id)?.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) delay(100) }
            val completed = requireNotNull(app.repository.dao.get(id))
            report.put("status", completed.status).put("error", completed.error)
            assertEquals(completed.error, "COMPLETED", completed.status)
            val events = app.repository.dao.events(id)
            assertTrue(events.any { it.state == "VALIDATING" && it.time >= releasedAt })
            assertTrue(events.any { it.state == "SAVING" && it.time >= releasedAt })
            val resumed = withContext(Dispatchers.Main) {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filter { it.packageName == app.packageName }.map { it.javaClass.simpleName }
            }
            assertTrue("no app activity may be needed to complete: $resumed", resumed.isEmpty())
            val uri = Uri.parse(requireNotNull(completed.uri))
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            val published = File(directory, "published.mp4")
            requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> published.outputStream().use { input.copyTo(it) } }
            val actual = FFprobeKit.getMediaInformation(published.path).mediaInformation
            assertEquals("aac", actual.streams.first { it.type == "audio" }.codec)
            assertEquals("h264", actual.streams.first { it.type == "video" }.codec)
            assertEquals(originalPackets, videoPackets(published, "published"))
            assertEquals(selection.duration!!, actual.duration.toDouble(), 0.15)
            val audioStream = actual.streams.first { it.type == "audio" }.allProperties
            val videoStream = actual.streams.first { it.type == "video" }.allProperties
            val audioStart = audioStream.getString("start_time").toDouble()
            val videoStart = videoStream.getString("start_time").toDouble()
            val audioEnd = audioStart + audioStream.getString("duration").toDouble()
            val videoEnd = videoStart + videoStream.getString("duration").toDouble()
            assertEquals(videoStart, audioStart, 0.05)
            assertEquals(videoEnd, audioEnd, 0.15)
            report.put("passed", true).put("ui_removed_before_processing_release", true).put("resumed_activities_at_completion", resumed.toString())
                .put("is_pending", 0).put("audio_codec", "aac").put("video_packets_unchanged", true)
                .put("output_bytes", published.length()).put("output_sha256", sha(published))
                .put("av_start_delta_seconds", audioStart - videoStart).put("av_end_delta_seconds", audioEnd - videoEnd)
                .put("events", org.json.JSONArray(events.map { it.state }))
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            release?.invoke()
            withContext(NonCancellable) {
                app.coordinator.stopAndAwait(id)
                app.repository.dao.get(id)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                app.repository.cancel(id); app.repository.remove(id); app.storage.removeTemporary(id)
                withContext(Dispatchers.Main) { activity?.finish() }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use { it.readBytes() }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard")).use { it.readBytes() }
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
    @Test fun confirmedSubmissionSurvivesClearingViewModelBeforeMainDispatch(): Unit = runBlocking {
        val directory = evidence("submission-handoff")
        val token = UUID.randomUUID().toString()
        val url = "https://fixture.invalid/$token.mp4"
        val format = Format.parse(JSONObject().put("format_id", token).put("url", url).put("ext", "mp4")
            .put("protocol", "https").put("vcodec", "h264").put("acodec", "aac").put("height", 240).put("width", 320))
        val media = Media(url, token, "Handoff $token", "Local test", null, null, 3.0, listOf(format))
        val store = androidx.lifecycle.ViewModelStore()
        val model = com.zdownloader.app.ui.ZViewModel(app.repository, com.zdownloader.app.data.SettingsRepository(app),
            app.storage, app.extractor, app.scheduler, app.playlists, app.plannedDownloads)
        val report = JSONObject().put("scope", "real ViewModel/Room/durable future scheduling; no network transfer")
        try {
            withContext(Dispatchers.Main) {
                store.put("owned", model)
                val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                // Keep Main occupied until the off-Main caller returns, then
                // destroy the VM before any queued Main launch could begin.
                val caller = Thread {
                    try { model.enqueue(media, Selection.video(media, format), "Handoff $token",
                        plan = DownloadPlan(System.currentTimeMillis() + 3_600_000)) }
                    catch (failure: Throwable) { error.set(failure) }
                }
                caller.start(); caller.join(2_000)
                assertFalse("submission entry must return without waiting for Main", caller.isAlive)
                assertNull(error.get())
                store.clear()
            }
            withTimeout(20_000) { while (model.ui.value.submitting) delay(50) }
            val rows = app.repository.downloads.first().filter { it.url == url }
            assertEquals("confirmed request must persist despite immediate ViewModel destruction", 1, rows.size)
            assertEquals("SCHEDULED", rows.single().status)
            assertTrue(rows.single().scheduledAt > System.currentTimeMillis())
            report.put("passed", true).put("persisted_rows", rows.size).put("status", rows.single().status)
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { store.clear() }
                for (row in app.repository.downloads.first().filter { it.url == url }) {
                    app.plannedDownloads.cancel(row.id); app.repository.cancel(row.id); app.repository.remove(row.id)
                }
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
    @Test fun userOwnedTransferContinuesThroughScreenOffAndPublication(): Unit = runBlocking {
        val directory = evidence("user-owned-screen-off")
        check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "CANCELLED", "FAILED", "PAUSED") })
        val token = UUID.randomUUID().toString()
        val source = File(app.cacheDir, "controlled-$token.mp4")
        instrument.context.assets.open("status-original.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val url = "https://mediaflow-background-fixture.invalid/$token.mp4"
        val module = app.extractor.python().getModule("types").callAttr("ModuleType", "owned_background_fixture")
        module.put("fixture_url", url); module.put("fixture_path", source.path)
        val script = """
import json, zextractor as z
original = z.download_stream
def controlled(fmt_json, path, request_id, callback, fragment_concurrency=0):
    if json.loads(fmt_json).get('url') != fixture_url:
        return original(fmt_json, path, request_id, callback, fragment_concurrency)
    event = z.register_request(request_id)
    event.deadline = float('inf')
    event.metadata = False
    try:
        data = open(fixture_path, 'rb').read()
        step = (len(data) + 39) // 40
        written = 0
        with open(path, 'wb') as output:
            for offset in range(0, len(data), step):
                z.check(event)
                chunk = data[offset:offset + step]
                output.write(chunk); output.flush(); written += len(chunk)
                callback.confidence('EXACT'); callback.progress(str(written), str(len(data)))
                event.wait(0.5)
        z.check(event)
        return json.dumps({'ok': True})
    finally:
        z._current.event = None
        with z._lock: z._requests.pop(request_id, None)
z.download_stream = controlled
""".trimIndent()
        app.extractor.python().builtins.callAttr("exec", script, module.get("__dict__"))
        val settings = com.zdownloader.app.data.SettingsRepository(app)
        val before = settings.current()
        val report = JSONObject().put("scope", "real foreground service/Room/native strict decode/publication; controlled local slow transport, not Internet throughput")
        var id: String? = null
        var activity: ShareReceiverActivity? = null
        fun phase(value: String) { File(directory, "phase.json").writeText(JSONObject().put("phase", value).put("elapsed", SystemClock.elapsedRealtime()).toString()) }
        suspend fun shell(command: String) = withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            settings.boolean("completion_notifications", false)
            val info = FFprobeKit.getMediaInformation(source.path).mediaInformation
            val video = info.streams.first { it.type == "video" }
            val format = Format.parse(JSONObject().put("format_id", token).put("url", url).put("ext", "mp4").put("protocol", "m3u8_native")
                .put("vcodec", "h264").put("acodec", "aac").put("width", video.width).put("height", video.height).put("filesize", source.length()))
            val media = Media(url, token, "Controlled background $token", "Fixture", null, null, info.duration.toDouble(), listOf(format))
            activity = instrument.startActivitySync(Intent(app, ShareReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShareReceiverActivity
            id = app.repository.create(media, Selection.video(media, format), "Controlled background $token")
            val started = SystemClock.elapsedRealtime()
            val receipt = app.scheduler.startFromUser(id)
            report.put("execution_owner", receipt.mechanism)
            phase("owner_accepted")
            withContext(Dispatchers.Main) { activity!!.finishAndRemoveTask() }
            phase("ui_removed")
            shell("input keyevent KEYCODE_SLEEP")
            phase("screen_off")
            withTimeout(60_000) { while (app.repository.dao.get(id)!!.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) delay(100) }
            val row = requireNotNull(app.repository.dao.get(id))
            val elapsed = SystemClock.elapsedRealtime() - started
            report.put("status", row.status).put("error", row.error).put("elapsed_ms", elapsed)
            assertEquals(row.error, "COMPLETED", row.status)
            assertTrue("controlled transfer must really take at least 20s", elapsed >= 20_000)
            assertFalse("screen must remain off through publication", app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            app.contentResolver.query(Uri.parse(row.uri), arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(row.outputSize, it.getLong(1))
            }
            val events = app.repository.dao.events(id)
            assertTrue(events.any { it.state == "VALIDATING" }); assertTrue(events.any { it.state == "SAVING" })
            report.put("passed", true).put("screen_off_at_completion", true).put("is_pending", 0).put("output_bytes", row.outputSize)
                .put("events", org.json.JSONArray(events.map { it.state }))
            phase("completed_screen_off")
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                id?.let { key -> app.repository.cancel(key); app.coordinator.stopAndAwait(key)
                    app.repository.dao.get(key)?.takeIf { it.uri != null }?.let { app.contentResolver.delete(Uri.parse(it.uri), null, null) }
                    app.repository.remove(key) }
                app.extractor.python().builtins.callAttr("exec", "z.download_stream = original", module.get("__dict__"))
                settings.boolean("completion_notifications", before.completionNotifications)
                shell("input keyevent KEYCODE_WAKEUP")
                withContext(Dispatchers.Main) { activity?.finish() }
                source.delete()
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(100) }
    private fun findSurface(view: View): View? {
        if (view is SurfaceView || view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
}
