@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class IncompleteFeedException(message: String) : IllegalStateException(message)

@RunWith(AndroidJUnit4::class)
class ConcurrentPipelineProofTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val expectedSize = 63486271L
    private val expectedSha = "a3b4dc4cb675a4ddc56f23a7bf0bf40c2472dac83d7e0c1bbf6f3192489c83ff"

    @Before
    fun assertPrerequisites() {
        assertTrue("Requires Android Q (API 29)+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val running = FFmpegKitConfig.getSessionsByState(SessionState.RUNNING)
        assertTrue("Pre-condition failed: active FFmpeg sessions already running", running.isEmpty())
    }

    private fun loadVerifiedFixture(): File {
        val path = InstrumentationRegistry.getArguments().getString("fixturePath")
            ?: error("Missing required instrumentation argument: fixturePath")
        val file = File(path).canonicalFile
        val baseDir = context.getExternalFilesDir(null)?.let { File(it, "source-paired-performance").canonicalPath }
        requireNotNull(baseDir) { "External files directory unavailable" }
        val allowedDirPrefix = baseDir + File.separator
        assertTrue("Fixture must be inside $allowedDirPrefix, got ${file.path}", file.path.startsWith(allowedDirPrefix))
        assertTrue("Fixture must be an existing file", file.isFile)
        assertEquals("Fixture byte length mismatch", expectedSize, file.length())
        assertEquals("Fixture SHA-256 mismatch", expectedSha, computeSha256(FileInputStream(file), expectedSize))
        return file
    }

    @Test
    fun progressiveFullAvMatchesFileDecodeAndStagesHiddenDestination() {
        if (InstrumentationRegistry.getArguments().getString("networkProducer") == "true") {
            runLiveTransferProof()
            return
        }
        val fixture = loadVerifiedFixture()
        val uuid = UUID.randomUUID().toString()
        val testDir = File(context.getExternalFilesDir(null), "concurrent-pipeline-proof/$uuid")
        assertTrue("Failed to create test directory", testDir.mkdirs() || testDir.isDirectory)

        var primaryError: Throwable? = null
        val report = JSONObject().put("scenario", "progressiveFullAvMatchesFileDecodeAndStagesHiddenDestination")
        val displayName = "proof-$uuid.mp4"
        var targetUri: Uri? = null

        val lease = NativeWork.acquire(testDir)
        var refSession: FFmpegSession? = null
        val refLatch = CountDownLatch(1)
        var refReturn: ReturnCode? = null

        var pipePath: String? = null
        var pipeFd: java.io.FileDescriptor? = null
        var progSession: FFmpegSession? = null
        val progLatch = CountDownLatch(1)
        var progReturn: ReturnCode? = null

        try {
            val refOut = File(testDir, "ref_framemd5.txt")
            val progOut = File(testDir, "prog_framemd5.txt")

            // 1. Reference ordinary-file software decode to framemd5
            refSession = FFmpegKit.executeWithArgumentsAsync(buildDecodeArgs(fixture.path, refOut.path), { s ->
                refReturn = s.returnCode; refLatch.countDown()
            }, null, null)
            val refDone = refLatch.await(150, TimeUnit.SECONDS)
            if (!refDone) {
                FFmpegKit.cancel(refSession.sessionId)
                refLatch.await(10, TimeUnit.SECONDS)
                error("Reference decode timed out after 150s")
            }
            assertTrue("Reference decode failed: ${refReturn?.value}", ReturnCode.isSuccess(refReturn))

            // 2. Progressive pipe execution + hidden destination staging
            pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            pipeFd = Os.open(pipePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)

            val firstVideoFrameAt = AtomicLong(-1L)
            val bytesAtFirstFrame = AtomicLong(-1L)
            val eofAtFirstFrame = AtomicBoolean(false)
            val producerBytesSupplied = AtomicLong(0L)
            val producerEof = AtomicBoolean(false)

            progSession = FFmpegKit.executeWithArgumentsAsync(buildDecodeArgs(pipePath, progOut.path), { s ->
                progReturn = s.returnCode; progLatch.countDown()
            }, null, { stat ->
                val nowEof = producerEof.get()
                val nowBytes = producerBytesSupplied.get()
                if (stat.videoFrameNumber > 0 && firstVideoFrameAt.compareAndSet(-1L, SystemClock.elapsedRealtime())) {
                    bytesAtFirstFrame.set(nowBytes)
                    eofAtFirstFrame.set(nowEof)
                }
            })

            // MediaStore insert and ownership verification BEFORE write
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/concurrent-proof")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: error("MediaStore insert failed")
            targetUri = uri
            assertMediaStoreOwnership(uri, displayName)

            val monotonicDeadline = SystemClock.elapsedRealtime() + 150_000L
            val buffer = ByteArray(64 * 1024)
            val fedDigest = MessageDigest.getInstance("SHA-256")

            context.contentResolver.openOutputStream(uri, "w")?.use { destOut ->
                FileInputStream(fixture).use { fis ->
                    while (true) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        destOut.write(buffer, 0, read)
                        writeFifoBounded(pipeFd, buffer, read, progLatch, monotonicDeadline)
                        fedDigest.update(buffer, 0, read)
                        producerBytesSupplied.addAndGet(read.toLong())
                    }
                    destOut.flush()
                }
            } ?: error("Failed to open output stream for destination URI")

            producerEof.set(true)
            val eofTime = SystemClock.elapsedRealtime()

            // Close writer FD BEFORE awaiting EOF
            Os.close(pipeFd)
            pipeFd = null

            val progDone = progLatch.await(150, TimeUnit.SECONDS)
            val progEndTime = SystemClock.elapsedRealtime()
            if (!progDone) {
                FFmpegKit.cancel(progSession.sessionId)
                progLatch.await(10, TimeUnit.SECONDS)
                error("Progressive decode timed out after 150s")
            }

            // Verify progress began BEFORE producer EOF
            val firstProgressMs = firstVideoFrameAt.get()
            val bytesAtFirst = bytesAtFirstFrame.get()
            val eofAtFirst = eofAtFirstFrame.get()
            assertTrue("Video progress callback never arrived", firstProgressMs > 0)
            assertFalse("Video progress must occur before producer EOF flag", eofAtFirst)
            assertTrue("Progress timestamp must be before EOF timestamp", firstProgressMs < eofTime)
            assertTrue("Progress bytes must be between 1 and expectedSize: $bytesAtFirst", bytesAtFirst in 1 until expectedSize)

            // Completeness gate verification with actual fed hash
            val actualFedSha = fedDigest.digest().joinToString("") { "%02x".format(it) }
            verifyCompletionGate(producerBytesSupplied.get(), actualFedSha, progReturn, progDone)

            // Validate full AV framemd5 parity (all lines including headers)
            val refRecords = parseFramemd5(refOut)
            val progRecords = parseFramemd5(progOut)
            val dataRecords = progRecords.filter { !it.startsWith("#") }
            val vCount = dataRecords.count { it.startsWith("0,") }
            val aCount = dataRecords.count { it.startsWith("1,") }
            assertTrue("Missing video data records in framemd5", vCount > 0)
            assertTrue("Missing audio data records in framemd5", aCount > 0)
            assertEquals("Framemd5 records mismatch against reference", refRecords, progRecords)

            // Validate staged destination integrity and verify still IS_PENDING=1
            val (destSize, destSha, isPending) = inspectDestination(uri, displayName)
            assertEquals("Staged byte size mismatch", expectedSize, destSize)
            assertEquals("Staged SHA-256 mismatch", expectedSha, destSha)
            assertEquals("Destination must remain IS_PENDING=1", 1, isPending)

            // Final oracle: existing Processor.validate on fixture
            runBlocking {
                Processor().validate(fixture, audio = false, requireAudio = true, expectedDuration = 888.053, expectedContainer = "mp4")
            }

            report.put("bytesSupplied", producerBytesSupplied.get())
                .put("actualFedSha", actualFedSha)
                .put("bytesAtFirstFrame", bytesAtFirst)
                .put("firstFrameBeforeEofMs", eofTime - firstProgressMs)
                .put("eofToCompletionMs", progEndTime - eofTime)
                .put("videoRecords", vCount)
                .put("audioRecords", aCount)
                .put("nativeReturn", progReturn?.value)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            primaryError = settleAllAndClean(
                sessions = listOfNotNull(refSession?.let { it to refLatch }, progSession?.let { it to progLatch }),
                pipeFd = pipeFd,
                pipePath = pipePath,
                lease = lease,
                targetUri = targetUri,
                displayName = displayName,
                report = report,
                primaryError = primaryError
            )
            primaryError = writeReport(testDir, report, primaryError)
            primaryError?.let { throw it }
        }
    }

    @Test
    fun truncatedProducerCannotCertifyOrPublish() {
        val fixture = loadVerifiedFixture()
        val uuid = UUID.randomUUID().toString()
        val testDir = File(context.getExternalFilesDir(null), "concurrent-pipeline-proof/$uuid")
        assertTrue("Failed to create test directory", testDir.mkdirs() || testDir.isDirectory)

        var primaryError: Throwable? = null
        val report = JSONObject().put("scenario", "truncatedProducerCannotCertifyOrPublish")
        val lease = NativeWork.acquire(testDir)
        var pipePath: String? = null
        var pipeFd: java.io.FileDescriptor? = null
        var progSession: FFmpegSession? = null
        val progLatch = CountDownLatch(1)
        var progReturn: ReturnCode? = null

        try {
            val progOut = File(testDir, "prog_trunc.txt")
            pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            pipeFd = Os.open(pipePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)

            progSession = FFmpegKit.executeWithArgumentsAsync(buildDecodeArgs(pipePath, progOut.path), { s ->
                progReturn = s.returnCode; progLatch.countDown()
            }, null, null)

            var bytesSupplied = 0L
            val limit = 1024 * 1024L // 1 MiB
            val monotonicDeadline = SystemClock.elapsedRealtime() + 30_000L
            val buffer = ByteArray(64 * 1024)
            val fedDigest = MessageDigest.getInstance("SHA-256")

            FileInputStream(fixture).use { fis ->
                while (bytesSupplied < limit) {
                    val toRead = minOf(buffer.size.toLong(), limit - bytesSupplied).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    writeFifoBounded(pipeFd, buffer, read, progLatch, monotonicDeadline)
                    fedDigest.update(buffer, 0, read)
                    bytesSupplied += read
                }
            }

            Os.close(pipeFd)
            pipeFd = null
            val isTerminal = progLatch.await(30, TimeUnit.SECONDS)
            assertTrue("Native session must be terminal within 30s", isTerminal)

            val actualFedSha = fedDigest.digest().joinToString("") { "%02x".format(it) }
            var caughtIncomplete: IncompleteFeedException? = null
            try {
                verifyCompletionGate(bytesSupplied, actualFedSha, progReturn, isTerminal)
            } catch (e: IncompleteFeedException) {
                caughtIncomplete = e
            }
            assertNotNull("Truncated feed must throw IncompleteFeedException", caughtIncomplete)

            val records = if (progOut.exists()) parseFramemd5(progOut) else emptyList()
            report.put("bytesSupplied", bytesSupplied)
                .put("actualFedSha", actualFedSha)
                .put("nativeCode", progReturn?.value)
                .put("recordsProduced", records.size)
                .put("gateRejectedMessage", caughtIncomplete?.message)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            primaryError = settleAllAndClean(
                sessions = listOfNotNull(progSession?.let { it to progLatch }),
                pipeFd = pipeFd,
                pipePath = pipePath,
                lease = lease,
                targetUri = null,
                displayName = null,
                report = report,
                primaryError = primaryError
            )
            primaryError = writeReport(testDir, report, primaryError)
            primaryError?.let { throw it }
        }
    }

    @Test
    fun cancellationStopsOnlyOwnedSession() {
        val fixture = loadVerifiedFixture()
        val uuid = UUID.randomUUID().toString()
        val testDir = File(context.getExternalFilesDir(null), "concurrent-pipeline-proof/$uuid")
        assertTrue("Failed to create test directory", testDir.mkdirs() || testDir.isDirectory)

        var primaryError: Throwable? = null
        val report = JSONObject().put("scenario", "cancellationStopsOnlyOwnedSession")
        val lease = NativeWork.acquire(testDir)
        var pipePath: String? = null
        var pipeFd: java.io.FileDescriptor? = null
        var progSession: FFmpegSession? = null
        val progLatch = CountDownLatch(1)
        var progReturn: ReturnCode? = null

        try {
            val progOut = File(testDir, "prog_cancel.txt")
            pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            pipeFd = Os.open(pipePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)

            progSession = FFmpegKit.executeWithArgumentsAsync(buildDecodeArgs(pipePath, progOut.path), { s ->
                progReturn = s.returnCode; progLatch.countDown()
            }, null, null)

            // Feed 1 MiB so moov (562 KiB) is fully consumed and decoder active
            var bytesSupplied = 0L
            val limit = 1024 * 1024L
            val monotonicDeadline = SystemClock.elapsedRealtime() + 30_000L
            val buffer = ByteArray(64 * 1024)

            FileInputStream(fixture).use { fis ->
                while (bytesSupplied < limit) {
                    val toRead = minOf(buffer.size.toLong(), limit - bytesSupplied).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    writeFifoBounded(pipeFd, buffer, read, progLatch, monotonicDeadline)
                    bytesSupplied += read
                }
            }

            // Cancel only this owned session
            FFmpegKit.cancel(progSession.sessionId)
            Os.close(pipeFd)
            pipeFd = null

            val isTerminal = progLatch.await(10, TimeUnit.SECONDS)
            assertTrue("Session must terminate within 10s after cancel", isTerminal)
            assertFalse("Cancelled session must not report success", ReturnCode.isSuccess(progReturn))
            report.put("cancelledSessionId", progSession.sessionId).put("returnCode", progReturn?.value)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            primaryError = settleAllAndClean(
                sessions = listOfNotNull(progSession?.let { it to progLatch }),
                pipeFd = pipeFd,
                pipePath = pipePath,
                lease = lease,
                targetUri = null,
                displayName = null,
                report = report,
                primaryError = primaryError
            )
            primaryError = writeReport(testDir, report, primaryError)
            primaryError?.let { throw it }
        }
    }

    private fun runLiveTransferProof() {
        val args = InstrumentationRegistry.getArguments()
        val tailMode = args.getString("networkTailMode") ?: "serial"
        require(tailMode in setOf("serial", "parallel", "transfer_only")) { "Invalid networkTailMode" }
        val parallelTail = tailMode == "parallel"
        val sourceUrl = requireNotNull(args.getString("networkSourceUrl")) { "Missing networkSourceUrl" }
        val normalizedUrl = UrlPolicy.normalize(sourceUrl)
        require(normalizedUrl.startsWith("https://")) { "networkSourceUrl must be HTTPS: $normalizedUrl" }

        val networkBytes = requireNotNull(args.getString("networkBytes")?.toLongOrNull()) { "Missing networkBytes" }
        require(networkBytes in 1..(96L * 1024 * 1024)) { "networkBytes must be 1..96MiB: $networkBytes" }

        val networkSha256 = requireNotNull(args.getString("networkSha256")) { "Missing networkSha256" }
        require(networkSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "networkSha256 must be 64 hex characters" }

        val networkDuration = requireNotNull(args.getString("networkDuration")?.toDoubleOrNull()) { "Missing networkDuration" }
        require(networkDuration in 0.001..1800.0) { "networkDuration must be 0.001..1800s: $networkDuration" }

        val refPath = requireNotNull(args.getString("networkReferencePath")) { "Missing networkReferencePath" }
        val refFile = File(refPath).canonicalFile
        val baseDir = context.getExternalFilesDir(null)?.let { File(it, "source-paired-performance").canonicalPath }
        requireNotNull(baseDir) { "External files directory unavailable" }
        val allowedDirPrefix = baseDir + File.separator
        assertTrue("Reference must be inside $allowedDirPrefix, got ${refFile.path}", refFile.path.startsWith(allowedDirPrefix))
        assertTrue("Reference must be an existing file", refFile.isFile)
        assertEquals("Reference byte length mismatch", networkBytes, refFile.length())
        assertEquals("Reference SHA-256 mismatch", networkSha256, computeSha256(FileInputStream(refFile), networkBytes))

        val uuid = UUID.randomUUID().toString()
        val testDir = File(context.getExternalFilesDir(null), "concurrent-pipeline-proof/$uuid")
        assertTrue("Failed to create test directory", testDir.mkdirs() || testDir.isDirectory)
        val requiredFree = (networkBytes * 3) + (10L * 1024 * 1024)
        require(testDir.usableSpace >= requiredFree) { "Insufficient storage for live transfer run" }

        var primaryError: Throwable? = null
        val report = JSONObject().put("scenario", "liveTransferProgressivePipeline")
            .put("network", true)
            .put("tailMode", tailMode)
            .put("sourceUrl", normalizedUrl)
            .put("expectedBytes", networkBytes)
            .put("expectedSha", networkSha256)
            .put("statisticsPeriodSeconds", 0.05)
            .put("observabilityOnlyChange", tailMode == "serial")
            .put("parallelFinalVerification", parallelTail)
            .put("conditionsBefore", deviceConditions())
        val displayName = "proof-$uuid.mp4"
        var targetUri: Uri? = null

        val lease = NativeWork.acquire(testDir)
        var refSession: FFmpegSession? = null
        val refLatch = CountDownLatch(1)
        var refReturn: ReturnCode? = null

        var pipePath: String? = null
        var pipeFd: java.io.FileDescriptor? = null
        var progSession: FFmpegSession? = null
        val progLatch = CountDownLatch(1)
        var progReturn: ReturnCode? = null

        val control = TransferControl()
        val producerSupervisorJob = SupervisorJob()
        val producerScope = CoroutineScope(producerSupervisorJob + Dispatchers.IO)
        val producerError = AtomicReference<Throwable?>(null)
        var producerJob: Job? = null
        var producerTerminal = true
        var stagingJob: Deferred<StagedDestination>? = null
        var oracleJob: Deferred<Long>? = null
        val oracleControl = TransferControl()

        try {
            val refOut = File(testDir, "ref_framemd5.txt")
            val progOut = File(testDir, "prog_framemd5.txt")

            refSession = FFmpegKit.executeWithArgumentsAsync(arrayOf("-stats_period", "0.05", *buildDecodeArgs(refFile.path, refOut.path)), { s ->
                refReturn = s.returnCode; refLatch.countDown()
            }, null, null)
            val refDone = refLatch.await(150, TimeUnit.SECONDS)
            if (!refDone) {
                FFmpegKit.cancel(refSession.sessionId)
                refLatch.await(10, TimeUnit.SECONDS)
                error("Reference decode timed out after 150s")
            }
            assertTrue("Reference decode failed: ${refReturn?.value}", ReturnCode.isSuccess(refReturn))

            val app = context.applicationContext as ZApplication
            val transfer = Transfer(app.extractor, Transfer.networkClient, Processor())
            val formatPayload = JSONObject().put("format_id", "live-proof-$uuid").put("url", normalizedUrl).put("ext", "mp4").put("vcodec", "h264").put("acodec", "aac").put("protocol", "https").put("filesize", networkBytes)
            val format = Format.parse(formatPayload)
            val outFile = File(testDir, "live_download.mp4")
            if (tailMode == "transfer_only") {
                runTransferOnlyControl(transfer, format, outFile, control, networkBytes, networkSha256, report)
                return
            }

            pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            pipeFd = Os.open(pipePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            val writerFd = requireNotNull(pipeFd)

            val firstVideoFrameAt = AtomicLong(-1L)
            val bytesAtFirstFrame = AtomicLong(-1L)
            val eofAtFirstFrame = AtomicBoolean(false)
            val producerBytesSupplied = AtomicLong(0L)
            val transferCompleted = AtomicBoolean(false)

            progSession = FFmpegKit.executeWithArgumentsAsync(arrayOf("-stats_period", "0.05", *buildDecodeArgs(pipePath, progOut.path)), { s ->
                progReturn = s.returnCode; progLatch.countDown()
            }, null, { stat ->
                val nowCompleted = transferCompleted.get()
                val nowBytes = producerBytesSupplied.get()
                if (stat.videoFrameNumber > 0 && firstVideoFrameAt.compareAndSet(-1L, SystemClock.elapsedRealtime())) {
                    bytesAtFirstFrame.set(nowBytes)
                    eofAtFirstFrame.set(nowCompleted)
                }
            })

            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/concurrent-proof")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: error("MediaStore insert failed")
            targetUri = uri
            assertMediaStoreOwnership(uri, displayName)

            val partFile = File(testDir, "live_download.mp4.part")
            val wakeups = Channel<Unit>(Channel.CONFLATED)
            val stagingWakeups = Channel<Unit>(Channel.CONFLATED)
            val transferSuccess = AtomicBoolean(false)
            val transferStartAt = AtomicLong(0L)
            val transferEndAt = AtomicLong(0L)
            val fedDigest = MessageDigest.getInstance("SHA-256")
            val monotonicDeadline = SystemClock.elapsedRealtime() + 150_000L
            val consumerFirstReadAt = AtomicLong(-1L)
            val consumerLastFeedAt = AtomicLong(-1L)
            val firstByteAt = AtomicLong(-1L)
            val uidRxBefore = AtomicLong(-1L)
            val uidRxAfter = AtomicLong(-1L)
            var consumedBytes = 0L

            producerTerminal = false
            val producerJobHandle = producerScope.launch {
                try {
                    transferStartAt.set(SystemClock.elapsedRealtime())
                    uidRxBefore.set(android.net.TrafficStats.getUidRxBytes(context.applicationInfo.uid))
                    transfer.stream(format, outFile, "proof-$uuid", control, awaitIdle = false) { downloaded, _ ->
                        producerBytesSupplied.set(downloaded)
                        if (downloaded > 0) firstByteAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                        wakeups.trySend(Unit)
                        if (parallelTail) stagingWakeups.trySend(Unit)
                        if (downloaded > networkBytes + (256L * 1024L)) {
                            throw IOException("Producer exceeded size budget: $downloaded > $networkBytes")
                        }
                    }
                    transferSuccess.set(true)
                } catch (t: Throwable) {
                    producerError.compareAndSet(null, t)
                } finally {
                    transferEndAt.set(SystemClock.elapsedRealtime())
                    uidRxAfter.set(android.net.TrafficStats.getUidRxBytes(context.applicationInfo.uid))
                    transferCompleted.set(true)
                    wakeups.close()
                    stagingWakeups.close()
                }
            }
            producerJob = producerJobHandle

            if (parallelTail) {
                stagingJob = producerScope.async {
                    withTimeout(180_000L) {
                        stageVisibleDownload(partFile, outFile, uri, displayName, stagingWakeups,
                            transferSuccess, producerError, networkBytes, networkSha256, monotonicDeadline)
                    }
                }
                oracleJob = producerScope.async {
                    withTimeout(180_000L) {
                        producerJobHandle.join()
                        producerError.get()?.let { throw it }
                        check(transferSuccess.get()) { "Final oracle requires successful Transfer" }
                        val began = SystemClock.elapsedRealtime()
                        Processor().validate(outFile, audio = false, requireAudio = true,
                            expectedDuration = networkDuration, expectedContainer = "mp4", control = oracleControl)
                        SystemClock.elapsedRealtime() - began
                    }
                }
            }

            runBlocking {
                withTimeout(180_000L) {
                    val destOut = if (parallelTail) null else requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
                    try {
                        var inStream: FileInputStream? = null
                        var inChannel: java.nio.channels.FileChannel? = null
                        val buffer = ByteArray(64 * 1024)

                        fun tryOpen(): Boolean {
                            if (inStream != null) return true
                            try {
                                val s = FileInputStream(partFile)
                                inStream = s
                                inChannel = s.channel
                                return true
                            } catch (_: java.io.FileNotFoundException) {
                                if (outFile.isFile) {
                                    val s = FileInputStream(outFile)
                                    inStream = s
                                    inChannel = s.channel
                                    return true
                                }
                                return false
                            }
                        }

                        fun drainAvailable() {
                            val ch = inChannel ?: return
                            val s = inStream ?: return
                            while (true) {
                                val availableOnDisk = ch.size()
                                val unread = availableOnDisk - ch.position()
                                if (unread <= 0) break
                                val toRead = minOf(buffer.size.toLong(), unread).toInt()
                                val r = s.read(buffer, 0, toRead)
                                if (r <= 0) break
                                consumerFirstReadAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                                consumedBytes += r
                                if (consumedBytes > networkBytes) {
                                    throw IllegalStateException("Downloaded bytes exceeded expected size: $consumedBytes > $networkBytes")
                                }
                                destOut?.write(buffer, 0, r)
                                writeFifoBounded(writerFd, buffer, r, progLatch, monotonicDeadline)
                                consumerLastFeedAt.set(SystemClock.elapsedRealtime())
                                fedDigest.update(buffer, 0, r)
                            }
                        }

                        try {
                            while (true) {
                                tryOpen()
                                if (inStream != null) {
                                    drainAvailable()
                                }
                                val res = wakeups.receiveCatching()
                                if (res.isClosed) break
                            }

                            val term = withContext(NonCancellable) {
                                withTimeoutOrNull(10_000L) {
                                    producerJobHandle.join()
                                    true
                                } ?: false
                            }
                            producerTerminal = term
                            producerError.get()?.let { throw it }
                            check(term) { "Producer did not terminate in bounded window" }
                            check(transferSuccess.get()) { "Transfer failed before completing download" }

                            tryOpen()
                            checkNotNull(inStream) { "No input stream opened after transfer completed" }
                            drainAvailable()
                            destOut?.flush()

                            if (consumedBytes != networkBytes) {
                                throw IncompleteFeedException("Consumer fed $consumedBytes != expected $networkBytes")
                            }
                        } finally {
                            inStream?.close()
                        }
                    } finally {
                        destOut?.close()
                    }
                }
            }

            Os.close(pipeFd)
            pipeFd = null

            val progDone = progLatch.await(150, TimeUnit.SECONDS)
            val progEndTime = SystemClock.elapsedRealtime()
            report.put("progEndAt", progEndTime)
                .put("nativeReturn", progReturn?.value)
            if (!progDone) {
                FFmpegKit.cancel(progSession.sessionId)
                progLatch.await(10, TimeUnit.SECONDS)
                error("Progressive decode timed out after 150s")
            }

            val actualFedSha = fedDigest.digest().joinToString("") { "%02x".format(it) }
            val firstProgressMs = firstVideoFrameAt.get()
            val bytesAtFirst = bytesAtFirstFrame.get()
            val eofAtFirst = eofAtFirstFrame.get()
            val transferDoneTime = transferEndAt.get()
            val overlapObserved = firstProgressMs > 0 && !eofAtFirst && firstProgressMs < transferDoneTime && bytesAtFirst in 1 until networkBytes

            report.put("bytesSupplied", consumedBytes)
                .put("actualFedSha", actualFedSha)
                .put("transferStartAt", transferStartAt.get())
                .put("transferEndAt", transferDoneTime)
                .put("transferSuccess", transferSuccess.get())
                .put("firstByteAt", firstByteAt.get())
                .put("transferDurationMs", transferDoneTime - transferStartAt.get())
                .put("payloadMBps", networkBytes.toDouble() / (transferDoneTime - transferStartAt.get()).coerceAtLeast(1) / 1000.0)
                .put("uidRxBefore", uidRxBefore.get()).put("uidRxAfter", uidRxAfter.get())
                .put("consumerFirstReadAt", consumerFirstReadAt.get())
                .put("consumerLastFeedAt", consumerLastFeedAt.get())
                .put("firstVideoFrameAt", firstProgressMs)
                .put("bytesAtFirstFrame", bytesAtFirst)
                .put("transferToNativeTailMs", progEndTime - transferDoneTime)
                .put("overlapObserved", overlapObserved)

            // Completeness & integrity verification
            verifyCompletionGate(consumedBytes, actualFedSha, progReturn, progDone, networkBytes, networkSha256)

            assertEquals("Transfer output byte length mismatch", networkBytes, outFile.length())
            val outputSha = computeSha256(FileInputStream(outFile), networkBytes)
            assertEquals("Transfer output SHA mismatch", networkSha256, outputSha)
            report.put("outputSha", outputSha)

            val refRecords = parseFramemd5(refOut)
            val progRecords = parseFramemd5(progOut)
            val dataRecords = progRecords.filter { !it.startsWith("#") }
            val vCount = dataRecords.count { it.startsWith("0,") }
            val aCount = dataRecords.count { it.startsWith("1,") }
            assertTrue("Missing video data records in framemd5", vCount > 0)
            assertTrue("Missing audio data records in framemd5", aCount > 0)
            assertEquals("Framemd5 records mismatch against reference", refRecords, progRecords)
            report.put("videoRecords", vCount).put("audioRecords", aCount)

            val readbackStart = SystemClock.elapsedRealtime()
            val staged = stagingJob?.let { job -> runBlocking { withTimeout(180_000L) { job.await() } } }
            val (destSize, destSha, isPending) = staged?.let { Triple(it.bytes, it.sha256, it.pending) }
                ?: inspectDestination(uri, displayName, networkBytes)
            val readbackDurationMs = staged?.readbackDurationMs ?: (SystemClock.elapsedRealtime() - readbackStart)
            report.put("readbackDurationMs", readbackDurationMs).put("destSha", destSha)
            staged?.let { report.put("destinationReadyAt", it.readyAt).put("destinationCopyFinishedAt", it.copyFinishedAt) }
            assertEquals("Staged byte size mismatch", networkBytes, destSize)
            assertEquals("Staged SHA-256 mismatch", networkSha256, destSha)
            assertEquals("Destination must remain IS_PENDING=1", 1, isPending)

            val validateStart = SystemClock.elapsedRealtime()
            val strictOracleDurationMs = oracleJob?.let { job -> runBlocking { withTimeout(180_000L) { job.await() } } }
                ?: runBlocking {
                    Processor().validate(outFile, audio = false, requireAudio = true, expectedDuration = networkDuration, expectedContainer = "mp4")
                    SystemClock.elapsedRealtime() - validateStart
                }
            report.put("strictOracleDurationMs", strictOracleDurationMs)

            // All integrity gates passed - record flag before asserting overlap
            val integrityReadyAt = SystemClock.elapsedRealtime()
            report.put("integrityPassed", true).put("integrityReadyAt", integrityReadyAt)
                .put("verifiedTailMs", integrityReadyAt - transferDoneTime)

            // Assert overlap; if this fails, integrityPassed=true and all evidence remain safely recorded
            assertTrue("Video progress callback never arrived", firstProgressMs > 0)
            assertFalse("Video progress must occur before transfer completion", eofAtFirst)
            assertTrue("Progress timestamp must be before transfer completion timestamp", firstProgressMs < transferDoneTime)
            assertTrue("Progress bytes must be between 1 and networkBytes: $bytesAtFirst", bytesAtFirst in 1 until networkBytes)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            control.stop()
            oracleControl.stop()
            val ownedJobs = listOfNotNull(producerJob, stagingJob, oracleJob)
            var allJobsTerminal = false
            try {
                allJobsTerminal = runBlocking {
                    withContext(NonCancellable) {
                        ownedJobs.filter { !it.isCompleted }.forEach { it.cancel() }
                        withTimeoutOrNull(10_000L) {
                            ownedJobs.forEach { it.join() }
                            true
                        } ?: false
                    }
                }
            } catch (cleanup: Throwable) {
                if (primaryError == null) primaryError = cleanup else primaryError.addSuppressed(cleanup)
            }
            val backgroundFailures = listOfNotNull(stagingJob, oracleJob).filter { it.isCompleted && !it.isCancelled }
            backgroundFailures.forEach { job ->
                try { runBlocking { job.await() } } catch (error: Throwable) {
                    if (primaryError == null) primaryError = error else if (primaryError !== error) primaryError.addSuppressed(error)
                }
            }
            producerTerminal = producerJob?.isCompleted != false
            producerSupervisorJob.cancel()
            val pErr = producerError.get()
            if (pErr != null) {
                if (primaryError == null) {
                    primaryError = pErr
                } else if (primaryError !== pErr) {
                    primaryError.addSuppressed(pErr)
                }
            }
            report.put("producerTerminal", producerTerminal)
                .put("allJobsTerminal", allJobsTerminal)
                .put("oracleNativeTerminal", oracleControl.session == null)
                .put("conditionsAfter", deviceConditions())
            primaryError = settleAllAndClean(
                sessions = listOfNotNull(refSession?.let { it to refLatch }, progSession?.let { it to progLatch }),
                pipeFd = pipeFd,
                pipePath = pipePath,
                lease = lease,
                targetUri = targetUri,
                displayName = displayName,
                report = report,
                primaryError = primaryError,
                externalOwnerTerminal = producerTerminal && allJobsTerminal && oracleControl.session == null
            )
            primaryError = writeReport(testDir, report, primaryError)
            primaryError?.let { throw it }
        }
    }

    private fun deviceConditions(): JSONObject {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return JSONObject().put("thermalStatus", power.currentThermalStatus)
            .put("powerSave", power.isPowerSaveMode).put("interactive", power.isInteractive)
    }

    private data class StagedDestination(
        val bytes: Long,
        val sha256: String,
        val pending: Int,
        val readbackDurationMs: Long,
        val copyFinishedAt: Long,
        val readyAt: Long
    )

    private suspend fun stageVisibleDownload(
        partFile: File,
        finalFile: File,
        uri: Uri,
        displayName: String,
        wakeups: Channel<Unit>,
        transferSuccess: AtomicBoolean,
        producerError: AtomicReference<Throwable?>,
        expectedBytes: Long,
        expectedHash: String,
        deadlineMs: Long
    ): StagedDestination {
        assertMediaStoreOwnership(uri, displayName)
        var input: FileInputStream? = null
        var copied = 0L
        val sourceHash = MessageDigest.getInstance("SHA-256")
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                val buffer = ByteArray(64 * 1024)
                fun openAvailable() {
                    if (input != null) return
                    input = try { FileInputStream(partFile) }
                    catch (_: java.io.FileNotFoundException) {
                        if (finalFile.isFile) FileInputStream(finalFile) else null
                    }
                }
                suspend fun drainVisible() {
                    val stream = input ?: return
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(SystemClock.elapsedRealtime() <= deadlineMs) { "Destination staging deadline exceeded" }
                        val available = stream.channel.size() - stream.channel.position()
                        if (available <= 0) break
                        val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), available).toInt())
                        if (count < 0) break
                        check(count > 0) { "No progress while reading visible bytes" }
                        copied += count
                        check(copied <= expectedBytes) { "Destination source exceeds pinned byte budget" }
                        output.write(buffer, 0, count)
                        sourceHash.update(buffer, 0, count)
                    }
                }
                while (true) {
                    openAvailable()
                    drainVisible()
                    if (wakeups.receiveCatching().isClosed) break
                }
                producerError.get()?.let { throw it }
                check(transferSuccess.get()) { "Destination staging requires successful Transfer" }
                openAvailable()
                checkNotNull(input) { "Completed source not found for destination staging" }
                drainVisible()
                output.flush()
            }
        } finally {
            input?.close()
        }
        check(copied == expectedBytes) { "Incomplete staged source: $copied/$expectedBytes" }
        val copiedHash = sourceHash.digest().joinToString("") { "%02x".format(it) }
        check(copiedHash == expectedHash) { "Staged source hash differs from oracle" }
        val copyFinishedAt = SystemClock.elapsedRealtime()
        val readbackStarted = SystemClock.elapsedRealtime()
        val (bytes, hash, pending) = inspectDestination(uri, displayName, expectedBytes)
        check(bytes == expectedBytes && hash == expectedHash && pending == 1) { "Pending destination readback mismatch" }
        val readyAt = SystemClock.elapsedRealtime()
        return StagedDestination(bytes, hash, pending, readyAt - readbackStarted, copyFinishedAt, readyAt)
    }

    private fun runTransferOnlyControl(
        transfer: Transfer,
        format: Format,
        output: File,
        control: TransferControl,
        expectedBytes: Long,
        expectedHash: String,
        report: JSONObject
    ) {
        val uid = context.applicationInfo.uid
        val rxBefore = android.net.TrafficStats.getUidRxBytes(uid)
        val began = SystemClock.elapsedRealtime()
        var firstByteAt = -1L
        runBlocking {
            withTimeout(180_000L) {
                transfer.stream(format, output, "control-${UUID.randomUUID()}", control, awaitIdle = false) { bytes, _ ->
                    if (bytes > 0 && firstByteAt < 0) firstByteAt = SystemClock.elapsedRealtime()
                    check(bytes <= expectedBytes + 256 * 1024) { "Transfer control byte budget exceeded" }
                }
            }
        }
        val finished = SystemClock.elapsedRealtime()
        val rxAfter = android.net.TrafficStats.getUidRxBytes(uid)
        assertEquals("Control byte count", expectedBytes, output.length())
        val actualHash = computeSha256(FileInputStream(output), expectedBytes)
        assertEquals("Control source hash", expectedHash, actualHash)
        report.put("transferOnlyControl", true).put("transferBytesVerified", true)
            .put("transferStartAt", began).put("transferEndAt", finished)
            .put("firstByteAt", firstByteAt).put("transferDurationMs", finished - began)
            .put("payloadMBps", expectedBytes.toDouble() / (finished - began).coerceAtLeast(1) / 1000.0)
            .put("uidRxBefore", rxBefore).put("uidRxAfter", rxAfter).put("outputSha", actualHash)
    }

    private fun verifyCompletionGate(bytesSupplied: Long, sha: String, returnCode: ReturnCode?, isTerminal: Boolean, targetExpectedSize: Long = expectedSize, targetExpectedSha: String = expectedSha) {
        if (bytesSupplied != targetExpectedSize) throw IncompleteFeedException("Incomplete bytes supplied: $bytesSupplied != $targetExpectedSize")
        if (!sha.equals(targetExpectedSha, ignoreCase = true)) throw IncompleteFeedException("Payload SHA mismatch: $sha != $targetExpectedSha")
        if (!isTerminal) throw IllegalStateException("Native session not terminal")
        if (!ReturnCode.isSuccess(returnCode)) throw IllegalStateException("Native exit code non-success: ${returnCode?.value}")
    }

    private fun settleAllAndClean(
        sessions: List<Pair<FFmpegSession, CountDownLatch>>,
        pipeFd: java.io.FileDescriptor?,
        pipePath: String?,
        lease: NativeWork.Lease,
        targetUri: Uri?,
        displayName: String?,
        report: JSONObject,
        primaryError: Throwable?,
        externalOwnerTerminal: Boolean = true
    ): Throwable? {
        var err = primaryError
        if (pipeFd != null && pipeFd.valid()) {
            try { Os.close(pipeFd) } catch (t: Throwable) { if (err == null) err = t else err.addSuppressed(t) }
        }

        var allTerminal = true
        for ((session, latch) in sessions) {
            if (latch.count > 0L) {
                try { FFmpegKit.cancel(session.sessionId) } catch (t: Throwable) { if (err == null) err = t else err.addSuppressed(t) }
                val done = try { latch.await(10, TimeUnit.SECONDS) } catch (t: Throwable) { if (err == null) err = t else err.addSuppressed(t); false }
                if (!done) allTerminal = false
            }
        }

        if (!allTerminal || !externalOwnerTerminal) {
            val unconfirmedErr = IllegalStateException(if (!allTerminal) "Native session terminal confirmation failed; retaining resources" else "External owner job not terminal; retaining resources")
            if (err == null) err = unconfirmedErr else err.addSuppressed(unconfirmedErr)
            report.put("cleanupConfirmed", false)
            captureFailureDetails(report, sessions, err)
            return err
        }

        report.put("cleanupConfirmed", true)
        if (targetUri != null && displayName != null) {
            try {
                assertMediaStoreOwnership(targetUri, displayName)
                val deleted = context.contentResolver.delete(targetUri, null, null)
                assertEquals("Failed to delete owned destination URI", 1, deleted)
                report.put("cleanupUriDeleted", true)
            } catch (t: Throwable) {
                if (err == null) err = t else err.addSuppressed(t)
                report.put("cleanupUriDeleted", false)
            }
        }

        if (pipePath != null) {
            try { FFmpegKitConfig.closeFFmpegPipe(pipePath) } catch (t: Throwable) { if (err == null) err = t else err.addSuppressed(t) }
        }
        try { lease.release() } catch (t: Throwable) { if (err == null) err = t else err.addSuppressed(t) }
        captureFailureDetails(report, sessions, err)
        return err
    }

    private fun captureFailureDetails(report: JSONObject, sessions: List<Pair<FFmpegSession, CountDownLatch>>, err: Throwable?) {
        if (err != null) {
            report.put("primaryErrorClass", err.javaClass.name)
            report.put("primaryErrorMessage", err.message)
            val suppressedArr = JSONArray()
            err.suppressed.forEach { suppressedArr.put("${it.javaClass.name}: ${it.message}") }
            report.put("suppressedErrors", suppressedArr)
            val sessionLogsArr = JSONArray()
            for ((session, _) in sessions) {
                val logSample = runCatching { session.allLogsAsString }.getOrNull()?.takeLast(1500)
                sessionLogsArr.put(JSONObject().put("sessionId", session.sessionId).put("tailLogs", logSample))
            }
            report.put("sessionLogs", sessionLogsArr)
        }
        report.put("passed", err == null)
    }

    private fun buildDecodeArgs(input: String, output: String): Array<String> = arrayOf(
        "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode", "-hwaccel", "none",
        "-protocol_whitelist", "file,pipe", "-i", input,
        "-map", "0:v:0", "-map", "0:a:0",
        "-c:v", "rawvideo", "-c:a", "pcm_s16le",
        "-fps_mode", "passthrough",
        "-max_error_rate", "0",
        "-f", "framemd5", output
    )

    private fun writeFifoBounded(fd: java.io.FileDescriptor, buffer: ByteArray, length: Int, latch: CountDownLatch, deadlineMs: Long) {
        var written = 0
        val pollFd = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLOUT.toShort() })
        while (written < length) {
            if (latch.count == 0L) throw IllegalStateException("Reader terminated prematurely during write")
            val remaining = deadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IllegalStateException("FIFO write monotonic deadline exceeded")
            try {
                val n = Os.write(fd, buffer, written, length - written)
                if (n > 0) written += n
            } catch (e: android.system.ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                if (e.errno == OsConstants.EAGAIN) {
                    val pollTimeout = minOf(100L, remaining).toInt()
                    if (pollTimeout > 0) Os.poll(pollFd, pollTimeout)
                } else throw e
            }
        }
    }

    private fun parseFramemd5(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun assertMediaStoreOwnership(uri: Uri, expectedDisplayName: String) {
        check(uri.scheme == "content" && uri.authority == "media") { "Invalid URI authority: $uri" }
        val id = ContentUris.parseId(uri)
        check(id > 0 && id != 97218L) { "Invalid MediaStore ID: $id" }
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.IS_PENDING), null, null, null)?.use { cursor ->
            check(cursor.count == 1) { "Expected exactly one row for $uri, found ${cursor.count}" }
            check(cursor.moveToFirst()) { "Failed to move cursor for $uri" }
            val name = cursor.getString(0)
            check(name == expectedDisplayName) { "DisplayName mismatch: expected $expectedDisplayName, got $name" }
            val isPending = cursor.getInt(1)
            check(isPending == 1) { "Expected IS_PENDING=1, got $isPending" }
        } ?: error("Failed to query MediaStore for $uri")
    }

    private fun inspectDestination(uri: Uri, displayName: String, targetExpectedSize: Long = expectedSize): Triple<Long, String, Int> {
        assertMediaStoreOwnership(uri, displayName)
        var isPending = -1
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) isPending = cursor.getInt(0)
        }
        val stream = context.contentResolver.openInputStream(uri) ?: error("Failed to open $uri")
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { s ->
            val buf = ByteArray(64 * 1024)
            while (totalBytes <= targetExpectedSize) {
                val toRead = minOf(buf.size.toLong(), (targetExpectedSize + 1L) - totalBytes).toInt()
                val r = s.read(buf, 0, toRead)
                if (r <= 0) break
                digest.update(buf, 0, r)
                totalBytes += r
            }
        }
        return Triple(totalBytes, digest.digest().joinToString("") { "%02x".format(it) }, isPending)
    }

    private fun computeSha256(stream: InputStream, maxBytes: Long): String = stream.use { s ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var readTotal = 0L
        while (readTotal < maxBytes) {
            val toRead = minOf(buf.size.toLong(), maxBytes - readTotal).toInt()
            val r = s.read(buf, 0, toRead)
            if (r <= 0) break
            digest.update(buf, 0, r)
            readTotal += r
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeReport(dir: File, report: JSONObject, primaryError: Throwable?): Throwable? {
        var err = primaryError
        try {
            val file = File(dir, "report.json").apply { writeText(report.toString(2)) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("proof_report", file.path) })
        } catch (t: Throwable) {
            if (err == null) err = t else err.addSuppressed(t)
        }
        return err
    }
}
