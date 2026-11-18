package com.zdownloader.app.updates

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class UpdateDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = ByteArray(800000) { (it % 251).toByte() }
    private val asset = UpdateAsset("arm64-v8a", "https://github.com/owner/repo/releases/download/v2/app.apk", "00".repeat(32), bytes.size.toLong())

    @Test fun interruptedBodyKeepsPartialThenUsesExactRangeAndIfRange() {
        val file = folder.newFile()
        val requests = mutableListOf<Request>()
        val http = UpdateHttp(factory { request ->
            requests += request
            if (requests.size == 1) response(request, 200, brokenBody(bytes, 262144), "ETag" to "\"v2\"")
            else {
                val offset = file.length().toInt()
                assertEquals("bytes=$offset-", request.header("Range"))
                assertEquals("\"v2\"", request.header("If-Range"))
                assertEquals("identity", request.header("Accept-Encoding"))
                response(request, 206, bytes.copyOfRange(offset, bytes.size).toResponseBody(),
                    "ETag" to "\"v2\"", "Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}")
            }
        })
        var etag: String? = null
        try { UpdateDownloader(http).download(asset, file, etag, checkpoint = { etag = it }, progress = {}); fail() }
        catch (_: IOException) { }
        assertTrue(file.length() in 1 until bytes.size.toLong())
        assertEquals("\"v2\"", etag)
        UpdateDownloader(http).download(asset, file, etag, checkpoint = { etag = it }, progress = {})
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(2, requests.size)
    }

    @Test fun rangeIgnoredByServerRestartsRatherThanAppending() {
        val file = folder.newFile().also { it.writeBytes(bytes.copyOfRange(0, 100)) }
        val http = UpdateHttp(factory { request -> response(request, 200, bytes.toResponseBody(), "ETag" to "\"new\"") })
        UpdateDownloader(http).download(asset, file, "\"old\"", checkpoint = {}, progress = {})
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun changedEtagOrMalformedRangeNeverAppends() {
        val file = folder.newFile().also { it.writeBytes(bytes.copyOfRange(0, 100)) }
        val http = UpdateHttp(factory { request -> response(request, 206, bytes.copyOfRange(100, bytes.size).toResponseBody(),
            "ETag" to "\"changed\"", "Content-Range" to "bytes 100-${bytes.lastIndex}/${bytes.size}") })
        rejects(UpdateError.RANGE) { UpdateDownloader(http).download(asset, file, "\"old\"", checkpoint = {}, progress = {}) }
        assertEquals(100L, file.length())
    }

    @Test fun weakValidatorRestartsAndTruncatedOrOversizeBodiesAreRejected() {
        val file = folder.newFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        var request: Request? = null
        val http = UpdateHttp(factory { r -> request = r; response(r, 200, byteArrayOf(4, 5).toResponseBody()) })
        rejects(UpdateError.SIZE) { UpdateDownloader(http).download(asset, file, "W/\"old\"", checkpoint = {}, progress = {}) }
        assertNull(request!!.header("Range"))
        for (length in listOf(bytes.size - 1, bytes.size + 1)) {
            val bounded = UpdateHttp(factory { r -> response(r, 200, ByteArray(length).toResponseBody()) })
            rejects(UpdateError.SIZE) { UpdateDownloader(bounded).download(asset, file, null, checkpoint = {}, progress = {}) }
        }
    }

    @Test fun incomplete416CannotBeMistakenForCompletion() {
        val file = folder.newFile().also { it.writeBytes(bytes.copyOfRange(0, 100)) }
        val http = UpdateHttp(factory { r -> response(r, 416, ByteArray(0).toResponseBody(), "Content-Range" to "bytes */${bytes.size}") })
        rejects(UpdateError.RANGE) { UpdateDownloader(http).download(asset, file, "\"v2\"", checkpoint = {}, progress = {}) }
        assertEquals(100L, file.length())
    }

    @Test fun metadataIsBoundedAndRedirectsCannotEscapeToFeedOrOtherHosts() {
        val tooLarge = UpdateHttp(factory { r -> response(r, 200, ByteArray(UpdateManifest.MAX_JSON_BYTES + 1).toResponseBody()) })
        rejects(UpdateError.METADATA) { tooLarge.json("https://feed.example/update") }
        val redirect = UpdateHttp(factory { r -> response(r, 302, ByteArray(0).toResponseBody(), "Location" to "https://evil.example/payload") })
        rejects(UpdateError.UNTRUSTED_URL) { redirect.json("https://feed.example/update") }
    }

    private fun brokenBody(bytes: ByteArray, limit: Long): ResponseBody = object : ResponseBody() {
        private var read = 0L
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (read >= limit) throw IOException("Controlled interruption")
                return super.read(sink, minOf(byteCount, limit - read)).also { if (it > 0) read += it }
            }
        }.buffer()
        override fun contentLength() = bytes.size.toLong()
        override fun contentType(): MediaType? = null
        override fun source(): BufferedSource = source
    }

    private fun response(request: Request, code: Int, body: ResponseBody, vararg headers: Pair<String, String>): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private fun factory(answer: (Request) -> Response): Call.Factory = Call.Factory { request -> object : Call {
        private var cancelled = false
        private var executed = false
        override fun request() = request
        override fun execute(): Response { executed = true; if (cancelled) throw IOException("Cancelled"); return answer(request) }
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = factory(answer).newCall(request)
    } }

    private fun rejects(error: UpdateError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: UpdateException) { assertEquals(error, e.code) }
    }
}
