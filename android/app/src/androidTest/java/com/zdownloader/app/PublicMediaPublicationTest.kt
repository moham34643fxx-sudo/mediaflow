package com.zdownloader.app

import android.content.ContentResolver
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.codecFamily
import com.zdownloader.app.playback.PlayerActivity
import com.zdownloader.app.ui.QuickShareDimensions
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Only the authorized public fixtures, from actual Quick Share choices to playback of the published URI. */
class PublicMediaPublicationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private enum class Sample(val url: String, val dimensions: Pair<Int, Int>? = null) {
        DIRECT("https://www.w3schools.com/html/mov_bbb.mp4", 320 to 176),
        DASH_AVC("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd", 600 to 480),
        MP3("https://www.w3schools.com/html/mov_bbb.mp4");

        val video get() = dimensions != null
        val output get() = if (video) "mp4" else "mp3"
        val mime get() = if (video) "video/mp4" else "audio/mpeg"
    }

    private data class PublishedBytes(val length: Long, val sha256: String)

    @Test fun directMp4PublishesAndPlays() = verify(Sample.DIRECT)
    @Test fun exactDashAvcMergesPublishesAndPlays() = verify(Sample.DASH_AVC)
    @Test fun convertedMp3PublishesAndPlays() = verify(Sample.MP3)

    private fun verify(sample: Sample): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        check(context.packageName == "com.zdownloader.app.candidate0965"
            && Build.VERSION.SDK_INT == 35 && Build.HARDWARE == "ranchu"
            && run.matches(Regex("qa129-\\d{2}-media-(direct|dash-avc|mp3)"))) {
            "Run only on the isolated API 35 QA emulator"
        }
        val app = context.applicationContext as ZApplication
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "media-acceptance/$run").apply { mkdirs() }
        val evidence = File(directory, "publication.json")
        check(evidence.createNewFile()) { "Do not overwrite a prior run" }
        val report = JSONObject().put("source", sample.url).put("sample", sample.name).put("passed", false)
        val existing = app.repository.downloads.first().associateBy { it.id }
        val intent = Intent(Intent.ACTION_SEND).setClass(context, ShareReceiverActivity::class.java)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, sample.url)
        var model: QuickShareViewModel? = null
        var preservedVideo: Pair<DownloadEntity, PublishedBytes>? = null
        try {
            report.put("stage", "analysis_and_selection")
            val requested = ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
                scenario.onActivity { model = ViewModelProvider(it)[QuickShareViewModel::class.java] }
                val share = requireNotNull(model)
                compose.waitUntil(90_000) { share.state.value.catalogReady || share.state.value.error != null }
                val state = share.state.value
                assertEquals("Extraction", null, state.error)
                val media = requireNotNull(state.media)
                if (sample.video) {
                    val format = selectableQuickShareVideoSources(media).firstOrNull {
                        (it.width to it.height) == sample.dimensions && codecFamily(it.videoCodec) == "h264"
                    } ?: error("No selectable ${sample.dimensions} AVC source")
                    clickRow("share_more")
                    compose.waitUntil(10_000) { share.state.value.expanded }
                    val qualityKey = QuickShareDimensions.from(format).key
                    val sourceRow = compose.revealQuickShareVideoSource(media, format)
                    clickRow(sourceRow)
                    if (sourceRow == "share_video_quality_$qualityKey") {
                        clickRow("share_video_quality_toggle_$qualityKey")
                    }
                    clickRow("share_video_output_mp4")
                    assertEquals("Explicit video source", format.id, share.state.value.selection?.primary?.id)
                } else {
                    // The compact MP3 option is added after the native encoder check,
                    // which can still be running after the source catalog is ready.
                    compose.waitUntil(20_000) {
                        val audioState = share.state.value
                        report.put("mp3Available", audioState.mp3Available)
                        audioState.mp3Available || audioState.error != null
                    }
                    assertEquals("MP3 discovery", null, share.state.value.error)
                    clickRow("share_audio_mp3")
                }
                val selection = requireNotNull(share.state.value.selection)
                assertEquals(sample.output, selection.output)
                assertEquals(!sample.video, selection.audioOnly)
                if (sample == Sample.DASH_AVC) assertNotNull("Separate DASH audio", selection.secondary)
                if (sample == Sample.MP3) assertTrue("The MP3 fixture must exercise encoding", selection.convertAudio)
                report.put("sourceFormatId", selection.primary.id)
                    .put("audioFormatId", selection.secondary?.id)
                    .put("stage", "enqueue")
                compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
                compose.waitUntil(25_000) { share.state.value.queuedId != null || share.state.value.error != null || share.state.value.duplicate != null }
                share.state.value.duplicate?.let { duplicate ->
                    check(sample == Sample.MP3 && duplicate.active.isEmpty()) { "Unexpected active or non-MP3 duplicate" }
                    val previous = requireNotNull(existing[duplicate.completed.single().id])
                    check(previous.status == "COMPLETED" && previous.url == sample.url
                        && previous.uri?.contains("/video/media/") == true) { "Only the prior public MP4 fixture is expected" }
                    val previousUri = Uri.parse(requireNotNull(previous.uri))
                    preservedVideo = previous to measure(app.contentResolver, previousUri)
                    compose.onNodeWithTag("duplicate_again").assertIsEnabled().performClick()
                    compose.waitUntil(25_000) { share.state.value.queuedId != null || share.state.value.error != null }
                    report.put("confirmedDownloadAgainForAudio", true)
                }
                assertEquals("Enqueue", null, share.state.value.error)
                selection
            }
            val id = requireNotNull(model?.state?.value?.queuedId)
            assertTrue("Do not reuse an existing download", id !in existing)
            report.put("stage", "background_publication").put("qaDownloadId", id)
            val row = withTimeout(180_000) {
                var current = requireNotNull(app.repository.dao.get(id))
                while (current.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED")) {
                    report.put("lastStatus", current.status)
                    delay(200)
                    current = requireNotNull(app.repository.dao.get(id))
                }
                current
            }
            report.put("lastStatus", row.status).put("downloadError", row.error).put("checkpoint", row.checkpoint)
            assertEquals(row.error, "COMPLETED", row.status)
            assertEquals("VALIDATED", row.checkpoint)
            val persisted = Selection.parse(row.selection)
            assertEquals(requested.primary.id, persisted.primary.id)
            assertEquals(requested.secondary?.id, persisted.secondary?.id)
            assertEquals(sample.output, persisted.output)
            report.put("stage", "published_integrity")
            val uri = verifyPublished(app, row, sample, report)
            report.put("stage", "playback")
            verifyPlayback(app, uri, sample, report)
            preservedVideo?.let { (prior, bytes) ->
                val current = requireNotNull(app.repository.dao.get(prior.id))
                assertEquals("COMPLETED", current.status)
                assertEquals(prior.uri, current.uri)
                assertEquals("Prior MP4 bytes", bytes, measure(app.contentResolver, Uri.parse(requireNotNull(current.uri))))
                report.put("priorVideoPreserved", true)
            }
            report.put("stage", "finished").put("passed", true)
        } finally {
            // Preserve the emulator's exact fixture rows for inspection; no cleanup of prior downloads.
            evidence.writeText(report.toString(2))
        }
    }

    private fun clickRow(tag: String) {
        compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun verifyPublished(app: ZApplication, row: DownloadEntity, sample: Sample, report: JSONObject): Uri {
        val uri = Uri.parse(requireNotNull(row.uri))
        assertEquals(ContentResolver.SCHEME_CONTENT, uri.scheme)
        assertEquals(MediaStore.AUTHORITY, uri.authority)
        assertTrue(uri.path.orEmpty().contains(if (sample.video) "/video/media/" else "/audio/media/"))
        val columns = arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        val size = requireNotNull(app.contentResolver.query(uri, columns, null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(sample.mime, cursor.getString(2))
            cursor.getLong(1)
        }
        assertTrue("MediaStore size differs from the validated output", size > 0 && size == row.outputSize)
        val (read, sha256) = measure(app.contentResolver, uri)
        assertEquals("Published bytes", size, read)
        report.put("uri", uri.toString()).put("bytes", read).put("sha256", sha256)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            assertEquals(if (sample.video) 1 else 0, video.size)
            val audio = tracks.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            assertEquals(if (sample.video) "audio/mp4a-latm" else "audio/mpeg", audio.getString(MediaFormat.KEY_MIME))
            report.put("audioCodec", audio.getString(MediaFormat.KEY_MIME))
            sample.dimensions?.let { (width, height) ->
                val track = video.single()
                assertEquals("video/avc", track.getString(MediaFormat.KEY_MIME))
                assertEquals(width, track.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(height, track.getInteger(MediaFormat.KEY_HEIGHT))
                report.put("videoCodec", track.getString(MediaFormat.KEY_MIME)).put("width", width).put("height", height)
            }
        } finally {
            extractor.release()
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            assertEquals("Actual container", sample.mime, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            report.put("durationMs", duration)
            val expected = if (sample == Sample.DASH_AVC) 59_000L..61_000L else 9_000L..11_000L
            assertTrue("Published duration $duration outside the fixture range $expected", duration != null && duration in expected)
            if (sample.video) {
                val frame = retriever.getFrameAtTime(1_000_000)
                assertNotNull("Published URI must decode a frame", frame)
                frame?.recycle()
                report.put("decodedVideoFrame", true)
            }
        } finally {
            retriever.release()
        }
        return uri
    }

    private fun measure(resolver: ContentResolver, uri: Uri): PublishedBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        requireNotNull(resolver.openInputStream(uri)).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = stream.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                bytes += count
                count = stream.read(buffer)
            }
        }
        return PublishedBytes(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private suspend fun verifyPlayback(app: ZApplication, uri: Uri, sample: Sample, report: JSONObject) {
        val playback = app.playback
        withContext(Dispatchers.Main) {
            check(playback.state.value.current == null) { "Do not interrupt an existing playback session" }
            playback.attach()
            playback.playUri(uri.toString(), sample.name, video = sample.video, open = true)
        }
        try {
            withTimeout(25_000) {
                while (true) {
                    val state = playback.state.value
                    report.put("playerError", state.error).put("playerPositionMs", state.position).put("playerDurationMs", state.duration)
                    assertEquals("MediaFlow player reported an error", null, state.error)
                    if (state.current?.uri == uri.toString() && state.playing && state.position > 250) break
                    delay(50)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (playback.state.value.current?.uri == uri.toString()) playback.clear()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().forEach { it.finish() }
                playback.detach()
            }
        }
    }
}
