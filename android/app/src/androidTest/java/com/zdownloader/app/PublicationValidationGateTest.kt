@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class PublicationValidationGateTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private lateinit var source: File
    private lateinit var home: File
    private lateinit var database: ZDatabase
    private lateinit var storage: StorageRepository
    private val dao get() = database.downloads()
    private val fixtures = mutableListOf<PublicationFixture>()

    private class PublicationFixture(val row: DownloadEntity, val file: File, val selection: Selection) {
        val control = TransferControl()
        var export: Deferred<Uri>? = null
        var uri: Uri? = null
    }

    @Before fun verifySource() = runBlocking<Unit> {
        check(android.os.Build.VERSION.SDK_INT >= 29) { "MediaStore pending publication requires API 29+" }
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("gateFixturePath"))
        source = File(path).canonicalFile
        val allowed = File(requireNotNull(app.getExternalFilesDir(null)), "source-paired-performance").canonicalPath + File.separator
        require(source.path.startsWith(allowed) && source.isFile && source.length() == 1128375L)
        assertEquals("0cd83d944a6ca7822b4a8306cecc60a36e859b041f6702c6a1ad9ead78924451", hash(source.readBytes()))
        Processor().validate(source, false, true, expectedDuration = 5.055, expectedContainer = "mp4")
        home = File(app.cacheDir, "publication-gates-${UUID.randomUUID()}")
        check(!home.exists() && home.mkdirs())
        val context = object : ContextWrapper(app) { override fun getFilesDir() = home }
        database = Room.inMemoryDatabaseBuilder(context, ZDatabase::class.java).build()
        storage = StorageRepository(context, dao)
    }

    @Test fun hiddenCopyWaitsForApprovalWithoutBlockingOtherPublication() = runBlocking<Unit> { supervisorScope {
        val fixture = newFixture()
        val copied = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        fixture.control.awaitPublicationValidation = { copied.complete(Unit); approval.await() }
        val export = beginExport(fixture)
        withTimeout(10_000) { copied.await() }
        val hidden = pendingUri(fixture)
        assertEquals(1, pendingFlag(fixture, hidden))
        assertArrayEquals(source.readBytes(), app.contentResolver.openInputStream(hidden)!!.use { it.readBytes() })
        assertFalse(export.isCompleted)
        assertTrue(JSONObject(journal(fixture).readText()).getBoolean("validationPending"))

        val other = newFixture()
        val otherUri = withTimeout(10_000) { beginExport(other).await() }
        assertEquals(0, pendingFlag(other, otherUri))
        assertFalse("Another publication must not release this gate", export.isCompleted)

        assertEquals(1, dao.validated(fixture.row.id))
        assertEquals(1, dao.processingStage(fixture.row.id, "SAVING"))
        approval.complete(Unit)
        val published = withTimeout(10_000) { export.await() }
        fixture.uri = published
        assertEquals(0, pendingFlag(fixture, published))
        assertEquals("COMPLETED", dao.get(fixture.row.id)?.status)
        assertFalse(journal(fixture).exists())
        assertArrayEquals(source.readBytes(), app.contentResolver.openInputStream(published)!!.use { it.readBytes() })
    } }

    @Test fun failedValidationDeletesOnlyPendingCopy() = runBlocking<Unit> { supervisorScope {
        val fixture = newFixture()
        val copied = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        fixture.control.awaitPublicationValidation = { copied.complete(Unit); approval.await() }
        val export = beginExport(fixture)
        withTimeout(10_000) { copied.await() }
        val hidden = pendingUri(fixture)
        approval.completeExceptionally(MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED))
        var rejected: MediaException? = null
        try { withTimeout(10_000) { export.await() } } catch (failure: MediaException) { rejected = failure }
        assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, rejected?.code)
        assertGone(hidden)
        assertNull(dao.get(fixture.row.id)?.pendingUri)
        assertNotEquals("COMPLETED", dao.get(fixture.row.id)?.status)
        assertFalse(journal(fixture).exists())
        assertFalse(NativeWork.busy(fixture.file.parentFile!!))
        assertArrayEquals(source.readBytes(), fixture.file.readBytes())
    } }

    @Test fun stoppingValidationWaitCancelsPendingExport() = runBlocking<Unit> { supervisorScope {
        val fixture = newFixture()
        val copied = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        fixture.control.awaitPublicationValidation = { copied.complete(Unit); approval.await() }
        val export = beginExport(fixture)
        withTimeout(10_000) { copied.await() }
        val hidden = pendingUri(fixture)
        fixture.control.stop()
        var stopped = false
        try { withTimeout(10_000) { export.await() } } catch (failure: CancellationException) {
            if (failure is TimeoutCancellationException) throw failure
            stopped = true
        }
        assertTrue(stopped)
        assertTrue(export.isCompleted)
        assertGone(hidden)
        assertNull(dao.get(fixture.row.id)?.pendingUri)
        assertNotEquals("COMPLETED", dao.get(fixture.row.id)?.status)
        assertFalse(NativeWork.busy(fixture.file.parentFile!!))
    } }

    @Test fun recoveryDiscardsCopiedButUnapprovedOutput() = runBlocking<Unit> {
        val other = newFixture()
        val preserved = withTimeout(10_000) { beginExport(other).await() }
        val fixture = newFixture()
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ".${fixture.row.id}.pending.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MediaFlow")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        fixture.uri = uri
        assertEquals(1, pendingFlag(fixture, uri))
        app.contentResolver.openOutputStream(uri)!!.use { it.write(source.readBytes()) }
        journal(fixture).parentFile!!.mkdirs()
        journal(fixture).writeText(JSONObject().put("uri", uri.toString()).put("finalName", fixture.row.filename)
            .put("bytes", source.length()).put("sha256", hash(source.readBytes())).put("validationPending", true).toString())
        dao.pending(fixture.row.id, uri.toString())
        dao.interrupt(fixture.row.id)
        storage.reconcilePublications()
        assertGone(uri)
        assertNotEquals("COMPLETED", dao.get(fixture.row.id)?.status)
        assertEquals(0, pendingFlag(other, preserved))
        assertArrayEquals(source.readBytes(), app.contentResolver.openInputStream(preserved)!!.use { it.readBytes() })
        assertNull(dao.get(fixture.row.id)?.pendingUri)
        assertFalse(journal(fixture).exists())
        assertArrayEquals(source.readBytes(), fixture.file.readBytes())
        storage.reconcilePublications()
        assertGone(uri)
        assertNotEquals("COMPLETED", dao.get(fixture.row.id)?.status)
    }

    private suspend fun newFixture(): PublicationFixture {
        val id = "publication-gate-${UUID.randomUUID()}"
        check(dao.get(id) == null)
        val directory = File(home, "transfers/$id")
        check(!directory.exists())
        check(directory.mkdirs())
        val file = File(directory, "fixture.mp4")
        source.copyTo(file)
        val selection = Selection(Format("gate-fixture", "mp4", null, null, "h264", "aac", source.length(), null, "https", "{}"))
        val row = DownloadEntity(id, "https://example.com/publication-gate", "Publication gate", "test", null, null,
            selection.json(), "$id.mp4", null, id, status = "PROCESSING")
        dao.insert(row)
        return PublicationFixture(row, file, selection).also { fixtures += it }
    }

    private fun CoroutineScope.beginExport(fixture: PublicationFixture): Deferred<Uri> = async(Dispatchers.IO) {
        storage.commit(fixture.row, fixture.file, fixture.selection, fixture.control)
    }.also { fixture.export = it }

    private suspend fun pendingUri(fixture: PublicationFixture): Uri =
        Uri.parse(requireNotNull(dao.get(fixture.row.id)?.pendingUri)).also { fixture.uri = it }

    private fun pendingFlag(fixture: PublicationFixture, uri: Uri): Int {
        check(uri.scheme == "content" && uri.authority == "media")
        check(ContentUris.parseId(uri) > 0 && ContentUris.parseId(uri) != 97218L)
        return requireNotNull(app.contentResolver.query(uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            check(it.count == 1 && it.moveToFirst())
            val pending = it.getInt(1)
            val expectedName = if (pending == 1) ".${fixture.row.id}.pending.mp4" else fixture.row.filename
            check(it.getString(0) == expectedName)
            pending
        }
    }

    private fun assertGone(uri: Uri) {
        val remaining = requireNotNull(app.contentResolver.query(uri,
            arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use { it.count }
        if (remaining == 0) return
        val collection = MediaStore.Files.getContentUri("external")
        fun countAt(target: Uri) = requireNotNull(app.contentResolver.query(target,
            arrayOf(MediaStore.MediaColumns._ID), "_id=?", arrayOf(ContentUris.parseId(uri).toString()), null)).use { it.count }
        @Suppress("DEPRECATION")
        val includingPending = MediaStore.setIncludePending(collection)
        assertEquals("Pending copy remains: direct=$remaining, defaultFiles=${countAt(collection)}, includingPending=${countAt(includingPending)}", 0, remaining)
    }

    private fun journal(fixture: PublicationFixture) = File(home, "publications/${fixture.row.id}.json")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @After fun removeOnlyOwnedFixtures() = runBlocking<Unit> {
        val cleanupFailures = mutableListOf<Throwable>()
        for (fixture in fixtures) try {
            fixture.control.stop()
            fixture.export?.let { withTimeout(10_000) { it.cancelAndJoin() } }
            val completedUri = dao.get(fixture.row.id)?.uri?.let(Uri::parse) ?: fixture.uri
            storage.cleanupPending(fixture.row.id)
            completedUri?.let {
                val exists = requireNotNull(app.contentResolver.query(it,
                    arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use { cursor -> cursor.count > 0 }
                if (exists) {
                    pendingFlag(fixture, it)
                    check(app.contentResolver.delete(it, null, null) == 1)
                }
            }
            assertFalse(NativeWork.busy(fixture.file.parentFile!!))
            dao.cancel(fixture.row.id)
            dao.removeHistory(fixture.row.id)
            storage.removeTemporary(fixture.row.id)
        } catch (failure: Throwable) { cleanupFailures += failure }
        try { if (::database.isInitialized) database.close() } catch (failure: Throwable) { cleanupFailures += failure }
        if (cleanupFailures.isEmpty() && ::home.isInitialized) try {
            check(home.deleteRecursively())
        } catch (failure: Throwable) { cleanupFailures += failure }
        if (cleanupFailures.isNotEmpty()) throw AssertionError("Owned publication fixture cleanup failed").apply {
            cleanupFailures.forEach { addSuppressed(it) }
        }
    }
}
