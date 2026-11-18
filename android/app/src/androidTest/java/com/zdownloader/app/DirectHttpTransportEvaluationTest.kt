package com.zdownloader.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in diagnostic test comparing current adaptive ranges vs single GET request without production changes. */
class DirectHttpTransportEvaluationTest {

    private enum class ArmKind { CURRENT_TRANSFER, SINGLE_GET, INITIAL_2M_NO_HINT }

    private class ArmTelemetry : EventListener() {
        data class Event(val method: String, val range: String?, val host: String, @Volatile var code: Int = -1, @Volatile var length: Long = -1L, val startNs: Long = System.nanoTime(), @Volatile var reqEndNs: Long = -1L, @Volatile var respEndNs: Long = -1L, @Volatile var callEndNs: Long = -1L, @Volatile var contentRange: String? = null) {
            fun json(): JSONObject = JSONObject().put("method", method).put("range", range ?: JSONObject.NULL).put("host", host).put("code", code).put("length", length).put("content_range", contentRange ?: JSONObject.NULL)
                .put("headers_ms", if (respEndNs > startNs) (respEndNs - startNs) / 1_000_000 else -1)
                .put("total_ms", if (callEndNs > startNs) (callEndNs - startNs) / 1_000_000 else -1)
        }
        val events = CopyOnWriteArrayList<Event>()
        private val active = ConcurrentHashMap<Call, Event>()
        override fun callStart(call: Call) { val r = call.request(); val ev = Event(r.method, r.header("Range"), r.url.host); active[call] = ev; events.add(ev) }
        override fun requestHeadersEnd(call: Call, request: Request) { active[call]?.reqEndNs = System.nanoTime() }
        override fun responseHeadersEnd(call: Call, response: Response) {
            active[call]?.let { it.respEndNs = System.nanoTime(); it.code = response.code; it.length = response.body?.contentLength() ?: -1L; it.contentRange = response.header("Content-Range") }
        }
        override fun callEnd(call: Call) { active.remove(call)?.callEndNs = System.nanoTime() }
        override fun callFailed(call: Call, ioe: IOException) { active.remove(call)?.callEndNs = System.nanoTime() }
    }

    private fun digest(file: File): String = file.inputStream().use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        while (true) { val n = stream.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun comparesCurrentRangesWithSingleRequestWithoutChangingProduction(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val compareInitialRangeHints = args.getString("compareInitialRangeHints") == "true"

        val run = requireNotNull(args.getString("evidenceRun")) { "evidenceRun required" }
        require(run.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) { "evidenceRun must be safe ASCII 1..48" }

        val sourceUrl = requireNotNull(args.getString("sourceUrl")) { "sourceUrl required" }
        require(sourceUrl.length <= UrlPolicy.MAX_URL_LENGTH) { "sourceUrl too long" }
        val normalizedUrl = UrlPolicy.normalize(sourceUrl)
        val uri = URI(normalizedUrl)
        require(uri.scheme?.lowercase() == "https") { "sourceUrl must be https" }
        require(uri.userInfo == null && !uri.rawAuthority.orEmpty().contains("@") && uri.query == null) { "sourceUrl invalid" }

        val expectedBytes = requireNotNull(args.getString("expectedBytes")?.toLongOrNull()) { "expectedBytes required" }
        require(expectedBytes in 1..(32L * 1024 * 1024)) { "expectedBytes must be in 1..32MiB" }

        val expectedSha256 = requireNotNull(args.getString("expectedSha256")) { "expectedSha256 required" }
        require(expectedSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "expectedSha256 must be 64 hex characters" }

        val externalDir = requireNotNull(app.getExternalFilesDir(null)) { "External files dir unavailable" }
        val directory = File(externalDir, "direct-http-evaluation/$run")
        check(!directory.exists() && directory.mkdirs()) { "Collision refusal: directory exists or cannot be created" }

        val trialPayload = 4L * expectedBytes
        require(trialPayload <= 128L * 1024 * 1024) { "Whole trial payload exceeds 128 MiB" }
        val requiredFreeSpace = trialPayload + (128L * 1024 * 1024)
        require(requiredFreeSpace > 0 && directory.usableSpace >= requiredFreeSpace) { "Insufficient storage for trial preflight" }

        val power = app.getSystemService(android.os.PowerManager::class.java)
        fun conditions(): JSONObject = JSONObject().put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1).put("power_save", power.isPowerSaveMode).put("interactive", power.isInteractive).put("uid_rx_bytes", android.net.TrafficStats.getUidRxBytes(app.applicationInfo.uid))

        val resultFile = File(directory, "result.json")
        val report = JSONObject().put("run", run).put("source_url", normalizedUrl).put("expected_bytes", expectedBytes).put("expected_sha256", expectedSha256)
            .put("passed", false).put("candidateAcceptedForProduction", false).put("non_resumable_comparison_only", !compareInitialRangeHints).put("baselinepipelinegain_not_established", true)
            .put("boundary_difference", if (compareInitialRangeHints) "fresh HTTP initial windows only, NOT resume/background/universal network capacity" else "CURRENT_TRANSFER includes on-disk .complete marker bookkeeping; SINGLE_GET is non-resumable diagnostic").put("conditions_before", conditions())
        if (compareInitialRangeHints) report.put("mode", "compare_initial_range_hints").put("scope", "fresh HTTP initial windows only, NOT resume/background/universal network capacity")
        resultFile.writeText(report.toString(2))

        var activity: MainActivity? = null; var activeControl: TransferControl? = null; var activeCall: Call? = null; var primaryError: Throwable? = null; var all4Passed = false

        try {
            check(!app.coordinator.running) { "Refusing run: app coordinator is running" }
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") }) { "Refusing run: pending user jobs" }

            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

            val headClient = Transfer.networkClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
            val headReq = Request.Builder().url(normalizedUrl).head().build()
            val headCall = headClient.newCall(headReq); activeCall = headCall
            var headETag: String? = null
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { headCall.cancel() } }
                    try {
                        headCall.execute().use { r ->
                            require(r.code == 200) { "HEAD response code must be 200, got ${r.code}" }
                            val len = r.header("Content-Length")?.toLongOrNull() ?: error("Missing Content-Length in HEAD")
                            require(len == expectedBytes) { "HEAD length $len != expectedBytes $expectedBytes" }
                            headETag = r.header("ETag")?.takeUnless { it.startsWith("W/") }
                            report.put("head_record", JSONObject().put("code", r.code).put("content_length", len).put("etag", headETag ?: JSONObject.NULL))
                        }
                    } finally {
                        withContext(NonCancellable) { headCall.cancel(); watcher.cancelAndJoin(); activeCall = null }
                    }
                }
            }
            resultFile.writeText(report.toString(2))

            val armOrder = if (compareInitialRangeHints) listOf(ArmKind.INITIAL_2M_NO_HINT, ArmKind.CURRENT_TRANSFER, ArmKind.CURRENT_TRANSFER, ArmKind.INITIAL_2M_NO_HINT)
                else listOf(ArmKind.CURRENT_TRANSFER, ArmKind.SINGLE_GET, ArmKind.SINGLE_GET, ArmKind.CURRENT_TRANSFER)
            val armRecords = JSONArray(); val shas = mutableListOf<String>()

            withTimeout(780_000L) {
                for ((idx, arm) in armOrder.withIndex()) {
                    val outFile = File(directory, "arm_${idx}_${arm.name.lowercase()}.bin")
                    check(!outFile.exists() && !File(outFile.path + ".part").exists() && !File(outFile.path + ".resume").exists() && !File(outFile.path + ".complete").exists()) { "Arm $idx file collision" }

                    val telemetry = ArmTelemetry()
                    val armClient = Transfer.networkClient.newBuilder().followRedirects(false).followSslRedirects(false).eventListenerFactory { telemetry }.build()
                    val armRecord = JSONObject().put("arm_index", idx).put("arm_type", arm.name).put("status", "STARTED").put("start_ns", System.nanoTime())
                    armRecords.put(armRecord); report.put("arm_records", armRecords); resultFile.writeText(report.toString(2))

                    var bodyConfirmedClosed = false; val t0 = SystemClock.elapsedRealtime()
                    try {
                        withTimeout(180_000L) {
                            when (arm) {
                                ArmKind.CURRENT_TRANSFER, ArmKind.INITIAL_2M_NO_HINT -> {
                                    val control = TransferControl(); activeControl = control
                                    val formatPayload = JSONObject().put("format_id", "eval-$run-$idx").put("url", normalizedUrl).put("ext", "bin").put("vcodec", "none").put("acodec", "none").put("protocol", "https").put("filesize", expectedBytes)
                                    val parsed = Format.parse(formatPayload)
                                    val format = if (arm == ArmKind.INITIAL_2M_NO_HINT) parsed.copy(bytes = null) else parsed
                                    try {
                                        Transfer(app.extractor, armClient, Processor()).stream(format, outFile, "op-$run-$idx", control, awaitIdle = false) { _, _ -> }
                                        FileOutputStream(outFile, true).use { it.fd.sync() }; bodyConfirmedClosed = true
                                    } finally {
                                        withContext(NonCancellable) { control.stop(); activeControl = null }
                                    }
                                }
                                ArmKind.SINGLE_GET -> {
                                    val req = Request.Builder().url(normalizedUrl).header("Accept-Encoding", "identity").build()
                                    val call = armClient.newCall(req); activeCall = call
                                    try {
                                        withContext(Dispatchers.IO) {
                                            coroutineScope {
                                                val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                                                try {
                                                    call.execute().use { resp ->
                                                        require(resp.code == 200) { "SINGLE_GET HTTP ${resp.code}" }
                                                        val body = resp.body ?: error("Empty body")
                                                        require(body.contentLength() == expectedBytes) { "SINGLE_GET length ${body.contentLength()} != $expectedBytes" }
                                                        if (headETag != null) {
                                                            val respETag = resp.header("ETag")?.takeUnless { it.startsWith("W/") }
                                                            require(respETag != null && respETag == headETag) { "ETag mismatch: HEAD had $headETag, GET had $respETag" }
                                                        }
                                                        FileOutputStream(outFile).use { fos ->
                                                            body.byteStream().use { input ->
                                                                val buf = ByteArray(256 * 1024); var readCount = 0L
                                                                while (true) {
                                                                    currentCoroutineContext().ensureActive()
                                                                    val n = input.read(buf); if (n < 0) break
                                                                    require(readCount + n <= expectedBytes) { "Exceeded expectedBytes" }
                                                                    fos.write(buf, 0, n); readCount += n
                                                                }
                                                                require(readCount == expectedBytes) { "Incomplete response: $readCount < $expectedBytes" }
                                                            }
                                                            fos.flush(); fos.fd.sync()
                                                        }
                                                        bodyConfirmedClosed = true
                                                    }
                                                } finally {
                                                    withContext(NonCancellable) { call.cancel(); watcher.cancelAndJoin() }
                                                }
                                            }
                                        }
                                    } finally {
                                        activeCall = null
                                    }
                                }
                            }
                        }
                    } catch (armErr: Throwable) {
                        armRecord.put("status", "FAILED").put("error", "${armErr.javaClass.simpleName}:${armErr.message}")
                        throw armErr
                    } finally {
                        val elapsedMs = SystemClock.elapsedRealtime() - t0
                        val partialBytes = if (outFile.isFile) outFile.length() else 0L
                        armRecord.put("elapsed_ms", elapsedMs).put("partial_bytes", partialBytes).put("requests", JSONArray(telemetry.events.map { it.json() }))
                        if (bodyConfirmedClosed) {
                            val actualSha = digest(outFile); val sec = elapsedMs / 1000.0
                            val mbps = if (sec > 0) (partialBytes * 8.0) / (sec * 1_000_000.0) else 0.0
                            val decimalMbPerSec = if (sec > 0) (partialBytes / 1_000_000.0) / sec else 0.0
                            val kibPerSec = if (sec > 0) (partialBytes / 1024.0) / sec else 0.0
                            armRecord.put("status", "COMPLETED").put("actual_bytes", partialBytes).put("actual_sha256", actualSha)
                                .put("throughput", JSONObject().put("mbps", String.format(java.util.Locale.US, "%.3f", mbps).toDouble())
                                    .put("decimal_MB_per_sec", String.format(java.util.Locale.US, "%.3f", decimalMbPerSec).toDouble())
                                    .put("kib_per_sec", String.format(java.util.Locale.US, "%.3f", kibPerSec).toDouble()))
                        }
                        resultFile.writeText(report.toString(2))
                    }
                    require(bodyConfirmedClosed) { "Arm $idx writer not confirmed closed" }
                    val actualBytes = outFile.length(); val actualSha = armRecord.getString("actual_sha256")
                    require(actualBytes == expectedBytes) { "Arm $idx byte count mismatch: $actualBytes != $expectedBytes" }
                    require(actualSha.equals(expectedSha256, ignoreCase = true)) { "Arm $idx SHA mismatch: $actualSha != $expectedSha256" }
                    shas.add(actualSha)
                    assertEquals("COMPLETED", armRecord.getString("status"))
                    if (compareInitialRangeHints) {
                        val firstRange = telemetry.events.firstOrNull()?.range
                        assertEquals(if (arm == ArmKind.INITIAL_2M_NO_HINT) "bytes=0-2097151" else "bytes=0-16777215", firstRange)
                    }
                }
            }

            assertEquals(4, shas.size); assertEquals(expectedSha256.lowercase(), shas[0].lowercase())
            assertEquals(shas[0].lowercase(), shas[1].lowercase()); assertEquals(shas[1].lowercase(), shas[2].lowercase()); assertEquals(shas[2].lowercase(), shas[3].lowercase())

            val currentArms = (0 until armRecords.length()).map { armRecords.getJSONObject(it) }.filter { it.getString("arm_type") == ArmKind.CURRENT_TRANSFER.name }
            fun median(list: List<Double>) = list.sorted().let { (it[0] + it[1]) / 2.0 }
            val medians = JSONObject()
                .put("current_transfer_ms", median(currentArms.map { it.getLong("elapsed_ms").toDouble() }))
                .put("current_transfer_decimal_MB_per_sec", median(currentArms.map { it.getJSONObject("throughput").getDouble("decimal_MB_per_sec") }))
            if (compareInitialRangeHints) {
                val initial2mArms = (0 until armRecords.length()).map { armRecords.getJSONObject(it) }.filter { it.getString("arm_type") == ArmKind.INITIAL_2M_NO_HINT.name }
                medians.put("initial_2m_no_hint_ms", median(initial2mArms.map { it.getLong("elapsed_ms").toDouble() }))
                    .put("initial_2m_no_hint_decimal_MB_per_sec", median(initial2mArms.map { it.getJSONObject("throughput").getDouble("decimal_MB_per_sec") }))
            } else {
                val singleArms = (0 until armRecords.length()).map { armRecords.getJSONObject(it) }.filter { it.getString("arm_type") == ArmKind.SINGLE_GET.name }
                medians.put("single_get_ms", median(singleArms.map { it.getLong("elapsed_ms").toDouble() }))
                    .put("single_get_decimal_MB_per_sec", median(singleArms.map { it.getJSONObject("throughput").getDouble("decimal_MB_per_sec") }))
            }
            report.put("medians", medians)
            all4Passed = true
        } catch (t: Throwable) {
            primaryError = t; report.put("passed", false).put("error", "${t.javaClass.simpleName}:${t.message}")
            runCatching { resultFile.writeText(report.toString(2)) }; throw t
        } finally {
            var cleanupFailed = false
            try {
                activeControl?.stop(); activeCall?.cancel()
            } catch (cleanupErr: Throwable) {
                cleanupFailed = true; report.put("cleanup_error", "${cleanupErr.javaClass.simpleName}:${cleanupErr.message}")
                if (primaryError != null) primaryError.addSuppressed(cleanupErr) else primaryError = cleanupErr
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.Main) { activity?.finish() }
                } catch (e: Throwable) {
                    cleanupFailed = true; report.put("activity_cleanup_error", "${e.javaClass.simpleName}:${e.message}")
                    if (primaryError != null) primaryError.addSuppressed(e) else primaryError = e
                }
                report.put("passed", all4Passed && !cleanupFailed && primaryError == null)
                report.put("conditions_after", conditions())
                runCatching { resultFile.writeText(report.toString(2)) }
                if (primaryError != null) throw primaryError
            }
        }
    }
}
