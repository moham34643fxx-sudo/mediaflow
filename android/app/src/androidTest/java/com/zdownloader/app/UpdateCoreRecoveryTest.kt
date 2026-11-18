package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.updates.AppUpdater
import com.zdownloader.app.updates.UpdateApkVerifier
import com.zdownloader.app.updates.UpdateAsset
import com.zdownloader.app.updates.UpdateConfiguration
import com.zdownloader.app.updates.UpdateError
import com.zdownloader.app.updates.UpdateHttp
import com.zdownloader.app.updates.UpdatePhase
import com.zdownloader.app.updates.UpdateRelease
import kotlinx.coroutines.runBlocking
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
import okio.source
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** Controlled transport + actual signed APK; never touches real updater files or PackageInstaller. */
class UpdateCoreRecoveryTest {
    @Test fun metadataOnlyCurrentResultStaysCurrentAfterRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "update-current-${UUID.randomUUID()}").apply { mkdirs() }
        val current = UpdateApkVerifier.installedIdentity(context)
        val asset = UpdateAsset("arm64-v8a", "https://github.com/owner/repo/releases/download/qa-fixture/app.apk", "ab".repeat(32), 100)
        val release = UpdateRelease(context.packageName, "qa", current.versionCode, "fixture",
            context.applicationInfo.minSdkVersion, null, mapOf("ar" to "اختبار", "en" to "Fixture"), listOf(asset), null)
        val config = UpdateConfiguration("https://feed.example/update", "owner/repo", "qa", storageRoot = root)
        val http = UpdateHttp(factory { response(it, 200, release.toJson().toString().toResponseBody()) })
        try {
            val updater = AppUpdater(context, config, http)
            updater.check(true).join()
            assertEquals(UpdatePhase.CURRENT, updater.state.value.phase)
            val recovered = AppUpdater(context, config, http)
            recovered.dismissPrompt().join()
            assertEquals(UpdatePhase.CURRENT, recovered.state.value.phase)
            assertNull(recovered.state.value.asset)
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptionResumeVerificationAndNewCoreRecoveryUseRealApkWithoutPublishing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.applicationInfo.sourceDir)
        val root = File(context.cacheDir, "update-core-${UUID.randomUUID()}").apply { mkdirs() }
        val current = UpdateApkVerifier.installedIdentity(context)
        val older = current.copy(versionCode = current.versionCode - 1)
        val nativeAbis = ZipFile(fixture).use { zip -> zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        val abi = if (nativeAbis.size > 1) "universal" else nativeAbis.single()
        val asset = UpdateAsset(abi, "https://github.com/owner/repo/releases/download/qa-fixture/app.apk",
            UpdateApkVerifier.sha256(fixture), fixture.length())
        val release = UpdateRelease(context.packageName, "qa", current.versionCode, "fixture",
            context.applicationInfo.minSdkVersion, null, mapOf("ar" to "اختبار", "en" to "Fixture"), listOf(asset), null)
        val apkRequests = AtomicInteger()
        val metadataRequests = AtomicInteger()
        val config = UpdateConfiguration("https://feed.example/update", "owner/repo", "qa", storageRoot = root)
        val http = UpdateHttp(factory { request ->
            if (request.url.host == "feed.example") {
                metadataRequests.incrementAndGet()
                response(request, 200, release.toJson().toString().toResponseBody())
            } else {
                val number = apkRequests.incrementAndGet()
                val offset = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                if (number > 1) {
                    assertTrue(offset > 0)
                    assertEquals("\"fixture-v1\"", request.header("If-Range"))
                }
                response(request, if (offset > 0) 206 else 200, apkBody(fixture, offset, interrupt = number == 1),
                    *listOfNotNull("ETag" to "\"fixture-v1\"",
                        if (offset > 0) "Content-Range" to "bytes $offset-${fixture.length() - 1}/${fixture.length()}" else null).toTypedArray())
            }
        })
        try {
            val updater = AppUpdater(context, config, http) { older }
            updater.check(manual = true).join()
            assertEquals(UpdatePhase.AVAILABLE, updater.state.value.phase)
            assertEquals(0, apkRequests.get()) // Discovery cannot download an APK.
            updater.download(installWhenReady = false).join()
            assertEquals(UpdatePhase.ERROR, updater.state.value.phase)
            assertEquals(UpdateError.NETWORK, updater.state.value.error)
            val partial = File(root, "app-updates/payload.apk")
            assertTrue(partial.length() in 1 until fixture.length())
            updater.download(installWhenReady = false).join()
            assertEquals(UpdatePhase.READY, updater.state.value.phase)
            assertEquals(fixture.length(), partial.length())
            assertEquals(asset.sha256, UpdateApkVerifier.sha256(partial))
            assertFalse(updater.state.value.autoInstall)
            assertNull(updater.state.value.sessionId)

            val recovered = AppUpdater(context, config, http) { older }
            recovered.dismissPrompt().join() // Wait for asynchronous recovery.
            assertEquals(UpdatePhase.READY, recovered.state.value.phase)
            assertEquals(2, apkRequests.get())
            assertEquals(1, metadataRequests.get())
            assertFalse(recovered.state.value.autoInstall)
        } finally { root.deleteRecursively() }
    }

    private fun apkBody(file: File, offset: Long, interrupt: Boolean): ResponseBody = object : ResponseBody() {
        private val input = file.inputStream().apply { channel.position(offset) }
        private var read = 0L
        private val source = object : ForwardingSource(input.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (interrupt && read >= 262144) throw IOException("Controlled interruption")
                val limit = if (interrupt) minOf(byteCount, 262144 - read) else byteCount
                return super.read(sink, limit).also { if (it > 0) read += it }
            }
        }.buffer()
        override fun contentLength() = file.length() - offset
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
}
