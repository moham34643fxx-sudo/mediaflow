package com.zdownloader.app

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.LameEncoder
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class StoragePublicationRegressionTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as ZApplication
    private val resolver get() = app.contentResolver

    @Test fun safPublicationPreservesSpecificProviderFailuresAndValidDestinations() = runBlocking<Unit> {
        val tree = grantFixtureTree()
        for ((fault, code) in mapOf(
            "no-space" to ErrorCode.INSUFFICIENT_STORAGE,
            "permission" to ErrorCode.STORAGE_PERMISSION_ERROR,
            "unavailable" to ErrorCode.STORAGE_DESTINATION_UNAVAILABLE,
            "write" to ErrorCode.FILE_WRITE_FAILED
        )) {
            val id = "audit-publication-${UUID.randomUUID()}"
            val output = mp3(id)
            val expected = output.readBytes()
            val selection = selection(output.length())
            val row = row(id, tree, selection, output.length())
            try {
                app.repository.dao.insert(row)
                assertEquals(1, app.repository.dao.claim(id))
                assertEquals(1, app.repository.dao.processing(id))
                injectCreateFailure(tree, id, fault)

                val failure = runCatching { app.storage.commit(row, output, selection, TransferControl()) }.exceptionOrNull()
                assertEquals("SAF failure $fault", code, (failure as? MediaException)?.code)
                assertEquals(tree.toString(), app.repository.dao.get(id)?.destination)
                assertNull("No unresolved publication", app.repository.dao.get(id)?.pendingUri)
                assertArrayEquals(expected, output.readBytes())

                if (fault == "write") {
                    // The provider is available again, so the same custom destination must still work.
                    val uri = app.storage.commit(row, output, selection, TransferControl())
                    assertEquals(expected.size.toLong(), app.repository.dao.get(id)?.outputSize)
                    assertEquals(row.filename, app.repository.dao.get(id)?.filename)
                    assertPublishedMp3(uri, expected)
                }
            } finally {
                clean(id)
            }
        }
    }

    @Test fun validatedMp3RetriesInDefaultFolderDespiteLargeOriginalSizeEstimate() = runBlocking<Unit> {
        assumeTrue("Scoped MediaStore destination", Build.VERSION.SDK_INT >= 29)
        val tree = grantFixtureTree()
        requireIdleQueue()
        val id = "audit-publication-${UUID.randomUUID()}"
        val output = mp3(id)
        val expected = output.readBytes()
        val selection = selection(1L shl 40) // Source estimate, not space needed for this 1-second MP3.
        val row = row(id, tree, selection, output.length())
        try {
            injectCreateFailure(tree, id, "unavailable")
            app.repository.dao.insert(row)
            withTimeout(15_000) { app.coordinator.run() }
            val failed = requireNotNull(app.repository.dao.get(id))
            assertEquals("FAILED", failed.status)
            assertEquals(ErrorCode.STORAGE_DESTINATION_UNAVAILABLE.name, failed.error)
            assertEquals("VALIDATED", failed.checkpoint)
            assertNull(failed.pendingUri)
            assertEquals(tree.toString(), failed.destination)
            assertArrayEquals(expected, output.readBytes())

            app.repository.retryInDefaultDestination(id)
            app.repository.prepareForSubmission(id)
            withTimeout(15_000) { app.coordinator.run() }
            val saved = requireNotNull(app.repository.dao.get(id))
            assertEquals(saved.error, "COMPLETED", saved.status)
            assertEquals(row.filename, saved.filename)
            assertNull(saved.destination)
            assertEquals(expected.size.toLong(), saved.outputSize)
            val uri = Uri.parse(requireNotNull(saved.uri))
            assertEquals("media", uri.authority)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertPublishedMp3(uri, expected)
            assertTrue(app.repository.dao.events(id).none { it.state in setOf("DOWNLOADING_AUDIO", "CONVERTING") })
        } finally {
            clean(id)
        }
    }

    @Test fun queueConvertsLocalVideoAudioToPlayableMp3AndPublishesItsMetadata() = runBlocking<Unit> {
        assumeTrue("Scoped MediaStore destination", Build.VERSION.SDK_INT >= 29)
        requireIdleQueue()
        val id = "audit-publication-${UUID.randomUUID()}"
        val source = File(app.storage.temporary(id), "primary.mp4")
        try {
            instrument.context.assets.open("status-original.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val duration = requireNotNull(FFprobeKit.getMediaInformation(source.path).mediaInformation).duration.toDouble()
            val selection = Selection(Format.parse(JSONObject().put("format_id", "audit-video")
                .put("url", "https://fixture.invalid/video.mp4").put("ext", "mp4").put("protocol", "https")
                .put("vcodec", "h264").put("acodec", "aac").put("filesize", source.length())),
                audioOnly = true, output = "mp3", convertAudio = true, duration = duration)
            val title = "اختبار تحويل الصوت"
            val filename = "$title $id.mp3"
            val row = DownloadEntity(id, "https://fixture.invalid/$id", title, "Synthetic fixture", null, duration,
                selection.json(), filename, null, id, checkpoint = "DOWNLOADED", bytes = source.length())
            app.repository.dao.insert(row)
            withTimeout(20_000) { app.coordinator.run() }

            val saved = requireNotNull(app.repository.dao.get(id))
            assertEquals(saved.error, "COMPLETED", saved.status)
            assertEquals(filename, saved.filename)
            assertPublishedMediaStoreMp3(Uri.parse(requireNotNull(saved.uri)), saved.outputSize!!)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, Uri.parse(saved.uri))
                assertEquals(title, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                val publishedMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertEquals(duration, publishedMs / 1000.0, 0.2)
            } finally {
                retriever.release()
            }
        } finally {
            clean(id)
        }
    }

    @Test fun legacyPendingMp3IsCleanedAfterMediaProviderNormalizesItsName() = runBlocking<Unit> {
        assumeTrue("Scoped MediaStore destination", Build.VERSION.SDK_INT >= 29)
        check(app.packageName == "com.zdownloader.app.candidate0965")
        val id = "audit-publication-${UUID.randomUUID()}"
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ".$id.pending.mp3")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MediaFlow")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var removed = false
        try {
            app.repository.dao.insert(DownloadEntity(id, "https://fixture.invalid/$id", "Legacy recovery", "Synthetic fixture",
                null, null, "{}", "$id.mp3", null, id, status = "INTERRUPTED", pendingUri = uri.toString()))
            app.storage.cleanupPending(id)
            removed = true
            assertNull(app.repository.dao.get(id)?.pendingUri)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            try { if (!removed) resolver.delete(uri, null, null) }
            finally { clean(id) }
        }
    }

    private suspend fun grantFixtureTree(): Uri {
        check(app.packageName == "com.zdownloader.app.candidate0965")
        val owner = instrument.context.packageName
        check(owner == "com.zdownloader.app.test") { "Expected the isolated SAF fixture package" }
        app.startActivity(Intent().setComponent(ComponentName(owner, TestDocumentsGrantActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val tree = DocumentsContract.buildTreeDocumentUri("$owner.documents", "root")
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        withTimeout(5_000) {
            while (app.checkUriPermission(tree, android.os.Process.myPid(), app.applicationInfo.uid, grants) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)
        }
        return tree
    }

    private suspend fun requireIdleQueue() {
        check(app.packageName == "com.zdownloader.app.candidate0965")
        check(app.repository.downloads.first().none {
            it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") || it.pendingUri != null
        }) { "Test requires an idle isolated QA queue" }
    }

    private fun injectCreateFailure(tree: Uri, id: String, fault: String) {
        requireNotNull(resolver.call(tree, "failAuditCreate", ".$id.pending", Bundle().apply {
            putString("failure", fault)
        }))
    }

    private fun mp3(id: String): File {
        val directory = app.storage.temporary(id)
        val pcm = File(directory, "tone.pcm")
        val samples = ByteBuffer.allocate(44_100 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44_100) { samples.putShort((sin(it * 2 * Math.PI * 440 / 44_100) * 5000).toInt().toShort()) }
        pcm.writeBytes(samples.array())
        return File(directory, "output.mp3").also { output ->
            assertEquals(0, LameEncoder.encodeEx(pcm.path, output.path, 128, 44_100, 1, AtomicBoolean(false)))
        }
    }

    private fun selection(size: Long) =
        Selection(Format.parse(JSONObject().put("format_id", "audit-mp3")
            .put("url", "https://fixture.invalid/retained.mp3").put("ext", "mp3").put("protocol", "https")
            .put("vcodec", "none").put("acodec", "mp3").put("filesize", size)),
            audioOnly = true, output = "mp3", bitrate = 128).also {
            assertEquals(size, Selection.parse(it.json()).size)
        }

    private fun row(id: String, tree: Uri, selection: Selection, bytes: Long) =
        DownloadEntity(id, "https://fixture.invalid/$id", "اختبار الصوت", "Synthetic fixture", null, 1.0,
            selection.json(), "اختبار الصوت $id.mp3", tree.toString(), id, checkpoint = "VALIDATED", bytes = bytes)

    private fun assertPublishedMp3(uri: Uri, expected: ByteArray) {
        val published = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(expected, published)
        assertMp3Track(uri)
    }

    private fun assertPublishedMediaStoreMp3(uri: Uri, size: Long) {
        assertEquals("media", uri.authority)
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals(size, it.getLong(1))
        }
        assertMp3Track(uri)
    }

    private fun assertMp3Track(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            assertEquals(1, extractor.trackCount)
            assertEquals("audio/mpeg", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            assertTrue(extractor.readSampleData(ByteBuffer.allocate(2048), 0) > 0)
        } finally {
            extractor.release()
        }
    }

    private suspend fun clean(id: String) {
        app.coordinator.stopAndAwait(id)
        app.repository.dao.get(id)?.let { row ->
            if (row.status == "COMPLETED") {
                val uri = Uri.parse(requireNotNull(row.uri))
                if (row.destination == null) check(resolver.delete(uri, null, null) == 1)
                else check(DocumentsContract.deleteDocument(resolver, uri))
            } else app.repository.dao.cancel(id)
            app.storage.cleanupPending(id)
            app.repository.dao.removeHistory(id)
        }
        app.storage.removeTemporary(id)
    }
}
