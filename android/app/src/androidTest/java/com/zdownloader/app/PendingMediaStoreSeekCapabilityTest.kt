package com.zdownloader.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class PendingMediaStoreSeekCapabilityTest {
    private fun verifyOwnershipGuard(resolver: ContentResolver, uri: Uri, expectedName: String) {
        check(uri.scheme == "content") { "Unsafe scheme: ${uri.scheme}" }
        check(uri.authority == "media") { "Unsafe authority: ${uri.authority}" }
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        check(id > 0L && id != 97218L) { "Unsafe ID $id for $uri" }

        var foundName: String? = null
        var foundPending: Int? = null
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                foundName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                foundPending = c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
            }
        }
        check(foundName == expectedName) { "DisplayName mismatch: expected $expectedName, got $foundName" }
        check(foundPending == 1) { "IS_PENDING must be 1, got $foundPending" }
    }

    @Test fun pendingVideoSupportsSeekedWritesAndReadbackWithoutPublication() {
        assertTrue("API 29+ required for MediaStore IS_PENDING", Build.VERSION.SDK_INT >= 29)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uuid = UUID.randomUUID().toString()
        val expectedName = "probe-$uuid.mp4"
        var uri: Uri? = null
        var primaryError: Throwable? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, expectedName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MediaFlowProbes")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            uri = resolver.insert(collection, values) ?: throw AssertionError("MediaStore insert returned null")
            verifyOwnershipGuard(resolver, uri!!, expectedName)

            val pfd = resolver.openFileDescriptor(uri!!, "rw") ?: throw AssertionError("openFileDescriptor rw returned null")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                val channel = out.channel
                channel.position(0L); channel.write(ByteBuffer.wrap("HEAD".toByteArray(Charsets.US_ASCII)))
                channel.position(8L); channel.write(ByteBuffer.wrap("TAIL".toByteArray(Charsets.US_ASCII)))
                channel.position(4L); channel.write(ByteBuffer.wrap("MID!".toByteArray(Charsets.US_ASCII)))
                channel.force(true)
            }

            val readBuf = ByteArray(13)
            val totalRead = resolver.openInputStream(uri!!)?.use { input ->
                var total = 0
                while (total < readBuf.size) {
                    val count = input.read(readBuf, total, readBuf.size - total)
                    if (count == -1) break
                    total += count
                }
                assertEquals("EOF expected after exactly 12 bytes", -1, input.read())
                total
            } ?: throw AssertionError("openInputStream returned null")
            assertEquals("Exact 12 bytes required", 12, totalRead)
            assertArrayEquals("HEADMID!TAIL".toByteArray(Charsets.US_ASCII), readBuf.copyOf(totalRead))

            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
            resolver.query(uri!!, projection, null, null, null)?.use { cursor ->
                assertTrue("MediaStore entry must exist", cursor.moveToFirst())
                assertEquals(expectedName, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
                assertFalse("Single row expected", cursor.moveToNext())
            } ?: throw AssertionError("query returned null cursor")
        } catch (t: Throwable) {
            primaryError = t; throw t
        } finally {
            if (uri != null) {
                try {
                    val targetUri = uri!!
                    verifyOwnershipGuard(resolver, targetUri, expectedName)
                    check(resolver.delete(targetUri, null, null) == 1) { "Delete failed: expected 1 row for $targetUri" }
                } catch (cleanupError: Throwable) {
                    if (primaryError != null) { primaryError.addSuppressed(cleanupError); throw primaryError } else throw cleanupError
                }
            }
        }
    }
}
