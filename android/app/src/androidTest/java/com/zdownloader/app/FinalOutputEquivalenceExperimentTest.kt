package com.zdownloader.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Processor
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Research only. No production hooks, network access, or downloaded-media writes. */
class FinalOutputEquivalenceExperimentTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private data class Probe(val duration: Double, val width: Int, val height: Int, val fps: Double?, val config: String)
    private data class Evidence(val probe: Probe, val packets: String, val nativeSamples: String, val bytesHash: String)
    private data class Verdict(val accepted: Boolean, val ms: Long, val reason: String, val fallback: Boolean = false)
    private data class Box(val type: String, val start: Long, val body: Long, val end: Long)
    private data class Fraction(val numerator: BigInteger, val denominator: BigInteger) {
        operator fun minus(other: Fraction) = fraction(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
        override fun toString() = "$numerator/$denominator"
    }
    companion object {
        private fun fraction(n: BigInteger, d: BigInteger): Fraction {
            require(d.signum() > 0)
            val gcd = n.abs().gcd(d)
            return Fraction(n / gcd, d / gcd)
        }
    }

    @Test fun comparesStrictFinalDecodeWithBoundedMp4Equivalence(): Unit = runBlocking {
        val app = instrument.targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val scratch = File(app.cacheDir, "equivalence-$run")
        check(scratch.mkdir()) { "refusing existing scratch" }
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "approach-b/$run")
        check(directory.mkdirs())
        val report = JSONObject().put("scope", "ISOLATED H264/AAC MP4 unchanged-stream-copy research; no production or network integration")
            .put("production_ready", false).put("published", false)
        val cases = JSONArray()
        report.put("cases", cases)
        val nativeDiagnostics = InstrumentationRegistry.getArguments().getString("caseGroup") == "native_diagnostics"
        var failure: Throwable? = null
        try {
            val asset = File(scratch, "asset.mp4")
            instrument.context.assets.open("status-original.mp4").use { input -> asset.outputStream().use { input.copyTo(it) } }
            val source = File(scratch, "validated-source.mp4")
            execute(listOf("-stream_loop", "4", "-i", asset.path, "-t", "60", "-map", "0:v:0", "-map", "0:a:0",
                "-c", "copy", "-map_metadata", "-1", "-movflags", "+faststart", source.path))
            val sourceProbe = probe(source)
            val beforeHash = digest(source)
            val sourceA = strict(source, sourceProbe)
            report.put("source_validation", verdictJson(sourceA))
            check(sourceA.accepted) { "fixture failed actual production validator A" }
            val certificateStarted = SystemClock.elapsedRealtime()
            val certificate = evidence(source, sourceProbe, scratch, "source")
            check(beforeHash == certificate.bytesHash) { "source changed during validation/certification" }
            report.put("source_evidence_ms", SystemClock.elapsedRealtime() - certificateStarted)
                .put("source_bytes", source.length()).put("source_sha256", certificate.bytesHash)
                .put("source_duration_seconds", sourceProbe.duration)
            val valid = File(scratch, "valid-output.mp4")
            val muxStarted = SystemClock.elapsedRealtime()
            execute(listOf("-i", source.path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                "-map_metadata", "-1", "-movflags", "+faststart", valid.path))
            report.put("mux_ms", SystemClock.elapsedRealtime() - muxStarted)
            fun compare(name: String, file: File, validControl: Boolean = false, reference: Evidence = certificate) {
                val before = resources()
                val a = strict(file, sourceProbe)
                val afterA = resources()
                val b = experimental(file, reference, scratch, name)
                val afterB = resources()
                cases.put(JSONObject().put("name", name).put("valid_control", validControl)
                    .put("A", verdictJson(a)).put("B", verdictJson(b))
                    .put("A_process_counter_delta", resourceDelta(before, afterA))
                    .put("B_process_counter_delta", resourceDelta(afterA, afterB))
                    .put("resource_snapshots", JSONArray(listOf(JSONObject(before), JSONObject(afterA), JSONObject(afterB)))))
                File(directory, "matrix.json").writeText(report.toString(2))
            }
            if (!nativeDiagnostics) {
            compare("valid_ffmpeg_remux", valid, true)
            val copied = File(scratch, "byte-identical.mp4").also { valid.copyTo(it) }
            compare("valid_byte_identical_copy", copied, true)
            val truncated = File(scratch, "late-truncation.mp4").also { valid.copyTo(it) }
            RandomAccessFile(truncated, "rw").use { it.setLength(it.length() * 3 / 4) }
            compare("post_certificate_truncated_output", truncated)
            val offset = File(scratch, "bad-index.mp4").also { valid.copyTo(it) }
            mutateChunkOffset(offset)
            compare("post_certificate_bad_chunk_offset", offset)
            val payload = File(scratch, "bad-payload.mp4").also { valid.copyTo(it) }
            mutatePayload(payload)
            compare("post_certificate_compressed_packet_mutation", payload)
            val config = File(scratch, "bad-config.mp4").also { valid.copyTo(it) }
            mutateConfiguration(config)
            compare("post_certificate_avc_configuration_damage", config)
            }
            var nativeReference: Evidence? = null
            for (kind in if (nativeDiagnostics) listOf("unchanged", "drop", "timestamp") else listOf("unchanged", "drop", "duplicate", "timestamp")) {
                val changed = File(scratch, "native-$kind.mp4")
                nativeRewrite(valid, changed, kind)
                compare("native_mux_$kind", changed, kind == "unchanged")
                if (nativeDiagnostics && kind == "unchanged") {
                    check(cases.getJSONObject(cases.length() - 1).getJSONObject("A").getBoolean("accepted"))
                    nativeReference = evidence(changed, sourceProbe, scratch, "native-reference")
                    compare("native_reference_identity_control", changed, true, requireNotNull(nativeReference))
                    source.copyTo(File(directory, "retained-source.mp4"))
                    changed.copyTo(File(directory, "retained-native-output.mp4"))
                    File(scratch, "source.packets").copyTo(File(directory, "source.packets"))
                    File(scratch, "native-reference.packets").copyTo(File(directory, "native.packets"))
                    val decodeStarted = SystemClock.elapsedRealtime()
                    val signatures = listOf("source" to source, "native" to changed).map { (name, file) ->
                        val decoded = File(directory, "$name.decoded-streamhash")
                        execute(listOf("-i", file.path, "-map", "0:v:0", "-map", "0:a:0", "-fps_mode", "passthrough",
                            "-f", "streamhash", "-hash", "sha256", decoded.path))
                        decoded.readText()
                    }
                    report.put("native_diagnostic_decoded_streams_identical", signatures[0] == signatures[1])
                        .put("native_diagnostic_full_decode_hash_ms", SystemClock.elapsedRealtime() - decodeStarted)
                        .put("native_reference_scope", "Additional strict-A-accepted native control certificate only for fault-specificity diagnostics; NOT a production shortcut")
                } else if (nativeDiagnostics) {
                    compare("native_reference_$kind", changed, reference = requireNotNull(nativeReference))
                }
            }
            if (!nativeDiagnostics) {
            val payload = File(scratch, "bad-payload.mp4")
            val badSource = strict(payload, sourceProbe)
            report.put("corrupted_source_A", verdictJson(badSource))
            val badCertificate = if (badSource.accepted) evidence(payload, sourceProbe, scratch, "mutated-source") else null
            report.put("corrupted_source_certificate_issued", badCertificate != null)
            }
            // No certificate is issued for an A-rejected source. Mutated outputs
            // above are all checked against the previously sealed valid source.
            val rows = (0 until cases.length()).map { cases.getJSONObject(it) }
            val missed = rows.filter { !it.getJSONObject("A").getBoolean("accepted") && it.getJSONObject("B").getBoolean("accepted") }
            val falseRejects = rows.filter { it.getBoolean("valid_control") && it.getJSONObject("B").getString("status") == "REJECT" }
            val fallbacks = rows.filter { it.getJSONObject("B").getString("status") == "FALLBACK_A_REQUIRED" }
            report.put("A_rejections_accepted_by_B", JSONArray(missed.map { it.getString("name") }))
                .put("B_rejected_valid_controls", JSONArray(falseRejects.map { it.getString("name") }))
                .put("B_fallback_cases", JSONArray(fallbacks.map { it.getString("name") }))
                .put("source_unchanged_after_matrix", digest(source) == certificate.bytesHash)
                .put("matrix_completed", true)
                .put("equivalence_demonstrated_in_tested_cases", missed.isEmpty() && falseRejects.isEmpty() && fallbacks.isEmpty())
                .put("timing_scope", "local source validation and per-case verification; no measured download overlap or production speedup")
                .put("unproven", JSONArray(listOf("real network acquisition", "pause/resume/retry/process death", "production native gates", "MediaStore B-only publication", "cross-format transformations")))
                .put("resource_scope", "Process-wide CPU/IO snapshots include instrumentation/background activity; RSS/HWM are absolute process values, not isolated per-arm peaks")
            assertTrue("fixture controls failed A", rows.filter { it.getBoolean("valid_control") }.all { it.getJSONObject("A").getBoolean("accepted") })
            assertTrue("source fixture changed", report.getBoolean("source_unchanged_after_matrix"))
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_message", error.message?.take(400))
        } finally {
            report.put("scratch_deleted", scratch.deleteRecursively())
            File(directory, "result.json").writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("isolated A/B experiment setup/execution failed", it) }
        assertTrue("owned scratch cleanup failed", report.getBoolean("scratch_deleted"))
        // A completed experiment is not an assertion that B is equivalent.
        // The report retains every valid-control failure and false acceptance.
    }

    @Test fun evaluatesRetainedArtifactExplicitEosEquivalence(): Unit = runBlocking {
        val app = instrument.targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun")) { "missing evidenceRun argument" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid evidenceRun: $run" }
        val scratch = File(app.cacheDir, "eos-experiment-$run")
        check(scratch.mkdir()) { "refusing existing scratch: ${scratch.path}" }
        val externalFilesDir = requireNotNull(app.getExternalFilesDir(null)) { "missing external files directory" }
        val directory = File(externalFilesDir, "approach-b/$run")
        check(directory.mkdirs()) { "failed to create evidence directory: ${directory.path}" }

        val report = JSONObject()
            .put("scope", "ISOLATED H264/AAC MP4 explicit-EOS research experiment; no production or network integration")
            .put("production_ready", false)
            .put("published", false)
            .put("timing_scope", "local explicit-EOS causal experiment; no measured download overlap or production speedup")
            .put("unproven", JSONArray(listOf("real network acquisition", "pause/resume/retry/process death", "production native gates", "MediaStore B-only publication", "cross-format transformations", "lifecycle")))

        val retainedDir = File(externalFilesDir, "approach-b/approach-b-native-5ff701")
        val retainedSource = File(retainedDir, "retained-source.mp4")
        val retainedNative = File(retainedDir, "retained-native-output.mp4")
        check(retainedSource.exists() && retainedSource.isFile) { "missing retained source: ${retainedSource.path}" }
        check(retainedNative.exists() && retainedNative.isFile) { "missing retained native output: ${retainedNative.path}" }

        val sourceBeforeHash = digest(retainedSource)
        val nativeBeforeHash = digest(retainedNative)
        val retainedCheck = JSONObject()
            .put("retained_source_path", retainedSource.path)
            .put("retained_source_sha256_before", sourceBeforeHash)
            .put("retained_native_path", retainedNative.path)
            .put("retained_native_sha256_before", nativeBeforeHash)
        report.put("retained_artifacts", retainedCheck)

        var failure: Throwable? = null
        try {
            val source = File(scratch, "source.mp4").also { retainedSource.copyTo(it) }
            val previousNative = File(scratch, "previous-retained-native.mp4").also { retainedNative.copyTo(it) }

            val sourceProbe = probe(source)
            val sourceShaBeforeA = digest(source)
            val sourceA = strict(source, sourceProbe)
            report.put("source_validation", verdictJson(sourceA))
            check(sourceA.accepted) { "retained source failed strict A: ${sourceA.reason}" }
            check(sourceShaBeforeA == digest(source)) { "source modified during strict A" }

            val certStart = SystemClock.elapsedRealtime()
            val certificate = evidence(source, sourceProbe, scratch, "source")
            val certMs = SystemClock.elapsedRealtime() - certStart
            check(sourceShaBeforeA == certificate.bytesHash) { "source modified during certification" }
            report.put("source_certificate", JSONObject()
                .put("sha256", certificate.bytesHash)
                .put("bytes", source.length())
                .put("duration_seconds", sourceProbe.duration)
                .put("cert_ms", certMs))

            val sourcePacketFile = File(scratch, "source.packets")
            check(sourcePacketFile.exists()) { "missing source.packets" }
            val sourcePacketLines = sourcePacketFile.readLines()
            val bases = sourcePacketLines.filter { it.startsWith("#tb ") }.associate { line ->
                val index = line.substringAfter("#tb ").substringBefore(':').trim().toInt()
                val parts = line.substringAfter(':').trim().split('/')
                index to (parts[0].toBigInteger() to parts[1].toBigInteger())
            }
            val videoTb = requireNotNull(bases[0]) { "missing video timebase (#tb 0)" }
            val audioTb = requireNotNull(bases[1]) { "missing audio timebase (#tb 1)" }
            val rows = sourcePacketLines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split(',').map(String::trim) }
            val videoRows = rows.filter { it[0] == "0" }
            val audioRows = rows.filter { it[0] == "1" }
            check(videoRows.isNotEmpty() && audioRows.isNotEmpty()) { "missing audio or video packets in source" }

            val lastVideoRow = videoRows.last()
            val lastVideoIndex = videoRows.size - 1
            val lastPtsTicks = lastVideoRow[2].toBigInteger()
            val lastDurationTicks = lastVideoRow[3].toBigInteger()
            val endpointTicks = lastPtsTicks + lastDurationTicks

            val lastPtsSeconds = fraction(lastPtsTicks * videoTb.first, videoTb.second)
            val lastDurationSeconds = fraction(lastDurationTicks * videoTb.first, videoTb.second)
            val endpointSeconds = fraction(endpointTicks * videoTb.first, videoTb.second)

            val conversionRule = "nearest-half-up integer rounding in microseconds: (2 * endpoint_seconds.numerator * 1_000_000 + endpoint_seconds.denominator) / (2 * endpoint_seconds.denominator)"
            val numUs = endpointSeconds.numerator * 1_000_000.toBigInteger()
            val denUs = endpointSeconds.denominator
            val eosPtsUsBigInt = (numUs * 2.toBigInteger() + denUs) / (denUs * 2.toBigInteger())
            val eosPtsUs = eosPtsUsBigInt.toLong()

            val numTicks = eosPtsUsBigInt * videoTb.second
            val denTicks = 1_000_000.toBigInteger() * videoTb.first
            val roundtripTicks = (numTicks * 2.toBigInteger() + denTicks) / (denTicks * 2.toBigInteger())
            val roundtripPreserved = (roundtripTicks == endpointTicks)
            check(roundtripPreserved) {
                "Microsecond conversion failed roundtrip gate: timescale ticks original=$endpointTicks, roundtrip=$roundtripTicks"
            }

            val derivationReport = JSONObject()
                .put("video_timebase", "${videoTb.first}/${videoTb.second}")
                .put("audio_timebase", "${audioTb.first}/${audioTb.second}")
                .put("source_video_packet_count", videoRows.size)
                .put("source_audio_packet_count", audioRows.size)
                .put("last_video_packet_index", lastVideoIndex)
                .put("source_last_pts_ticks", lastPtsTicks.toString())
                .put("source_last_duration_ticks", lastDurationTicks.toString())
                .put("source_endpoint_ticks", endpointTicks.toString())
                .put("source_last_pts_seconds", lastPtsSeconds.toString())
                .put("source_last_duration_seconds", lastDurationSeconds.toString())
                .put("source_endpoint_seconds", endpointSeconds.toString())
                .put("conversion_rule", conversionRule)
                .put("eos_pts_us", eosPtsUs)
                .put("roundtrip_ticks", roundtripTicks.toString())
                .put("roundtrip_preserved", roundtripPreserved)
            report.put("rational_eos_derivation", derivationReport)

            val noEosOutput = File(scratch, "output-no-eos.mp4")
            val eosOutput = File(scratch, "output-explicit-eos.mp4")

            val muxNoEosStart = SystemClock.elapsedRealtime()
            nativeRewrite(source, noEosOutput, "unchanged", null)
            val muxNoEosMs = SystemClock.elapsedRealtime() - muxNoEosStart

            val muxEosStart = SystemClock.elapsedRealtime()
            nativeRewrite(source, eosOutput, "unchanged", eosPtsUs)
            val muxEosMs = SystemClock.elapsedRealtime() - muxEosStart

            val cases = JSONArray()
            report.put("cases", cases)

            val beforeNoEos = resources()
            val aNoEos = strict(noEosOutput, sourceProbe)
            val afterANoEos = resources()
            val bNoEos = experimental(noEosOutput, certificate, scratch, "no_eos")
            val afterBNoEos = resources()

            cases.put(JSONObject().put("name", "baseline_no_eos")
                .put("mux_wall_ms", muxNoEosMs)
                .put("A", verdictJson(aNoEos)).put("B", verdictJson(bNoEos))
                .put("A_process_counter_delta", resourceDelta(beforeNoEos, afterANoEos))
                .put("B_process_counter_delta", resourceDelta(afterANoEos, afterBNoEos))
                .put("resource_snapshots", JSONArray(listOf(JSONObject(beforeNoEos), JSONObject(afterANoEos), JSONObject(afterBNoEos)))))

            val beforeEos = resources()
            val aEos = strict(eosOutput, sourceProbe)
            val afterAEos = resources()
            val bEos = experimental(eosOutput, certificate, scratch, "explicit_eos")
            val afterBEos = resources()

            cases.put(JSONObject().put("name", "explicit_video_eos")
                .put("mux_wall_ms", muxEosMs)
                .put("A", verdictJson(aEos)).put("B", verdictJson(bEos))
                .put("A_process_counter_delta", resourceDelta(beforeEos, afterAEos))
                .put("B_process_counter_delta", resourceDelta(afterAEos, afterBEos))
                .put("resource_snapshots", JSONArray(listOf(JSONObject(beforeEos), JSONObject(afterAEos), JSONObject(afterBEos)))))

            if (!File(scratch, "no_eos.packets").exists()) {
                packetSignature(noEosOutput, File(scratch, "no_eos.packets"))
            }
            if (!File(scratch, "explicit_eos.packets").exists()) {
                packetSignature(eosOutput, File(scratch, "explicit_eos.packets"))
            }

            File(scratch, "source.packets").copyTo(File(directory, "source.packets"), overwrite = true)
            File(scratch, "no_eos.packets").copyTo(File(directory, "no_eos.packets"), overwrite = true)
            File(scratch, "explicit_eos.packets").copyTo(File(directory, "explicit_eos.packets"), overwrite = true)

            val decodeMap = listOf("source" to source, "no_eos" to noEosOutput, "explicit_eos" to eosOutput).associate { (label, file) ->
                val decodedFile = File(directory, "$label.decoded-streamhash")
                val decodeStart = SystemClock.elapsedRealtime()
                execute(listOf("-i", file.path, "-map", "0:v:0", "-map", "0:a:0", "-fps_mode", "passthrough",
                    "-f", "streamhash", "-hash", "sha256", decodedFile.path))
                val elapsed = SystemClock.elapsedRealtime() - decodeStart
                label to Pair(decodedFile.readText().trim(), elapsed)
            }

            val decodedHashReport = JSONObject()
                .put("source", JSONObject().put("streamhash", decodeMap.getValue("source").first).put("ms", decodeMap.getValue("source").second))
                .put("no_eos", JSONObject().put("streamhash", decodeMap.getValue("no_eos").first).put("ms", decodeMap.getValue("no_eos").second))
                .put("explicit_eos", JSONObject().put("streamhash", decodeMap.getValue("explicit_eos").first).put("ms", decodeMap.getValue("explicit_eos").second))
                .put("no_eos_matches_source", decodeMap.getValue("source").first == decodeMap.getValue("no_eos").first)
                .put("explicit_eos_matches_source", decodeMap.getValue("source").first == decodeMap.getValue("explicit_eos").first)
                .put("no_eos_matches_explicit_eos", decodeMap.getValue("no_eos").first == decodeMap.getValue("explicit_eos").first)
                .put("note", "Decoded content hashes prove content equivalence, not timeline/container identity")
            report.put("decoded_streamhash_comparison", decodedHashReport)

            val packetComparison = JSONObject()
                .put("source", parseLastVideoPacket(File(scratch, "source.packets")))
                .put("no_eos", parseLastVideoPacket(File(scratch, "no_eos.packets")))
                .put("explicit_eos", parseLastVideoPacket(File(scratch, "explicit_eos.packets")))
            report.put("packet_timeline_comparison", packetComparison)

            val nativeSigReport = JSONObject()
            val structuredSeekReport = JSONObject()
            val containerCheckReport = JSONObject()

            for ((label, file) in listOf("source" to source, "no_eos" to noEosOutput, "explicit_eos" to eosOutput)) {
                try {
                    nativeSigReport.put(label, nativeSignature(file))
                } catch (e: Exception) {
                    nativeSigReport.put(label, "error:${e.javaClass.simpleName}:${e.message}")
                }
                try {
                    structuredSeekReport.put(label, JSONArray(extractSeekRecords(file)))
                } catch (e: Exception) {
                    structuredSeekReport.put(label, JSONArray().put("error:${e.javaClass.simpleName}:${e.message}"))
                }
                try {
                    validateTopLevelBoxes(file)
                    containerCheckReport.put(label, JSONObject().put("valid", true))
                } catch (e: Exception) {
                    containerCheckReport.put(label, JSONObject().put("valid", false).put("error", "${e.javaClass.simpleName}:${e.message}"))
                }
            }
            nativeSigReport.put("no_eos_matches_source", nativeSigReport.optString("no_eos") == nativeSigReport.optString("source"))
                .put("explicit_eos_matches_source", nativeSigReport.optString("explicit_eos") == nativeSigReport.optString("source"))
                .put("no_eos_matches_explicit_eos", nativeSigReport.optString("no_eos") == nativeSigReport.optString("explicit_eos"))

            structuredSeekReport.put("note", "Beginning, mid, and end seek samples extracted independently; seek success does not imply identical container metadata")
            report.put("native_extractor_signatures", nativeSigReport)
                .put("structured_seek_records", structuredSeekReport)
                .put("container_top_level_box_checks", containerCheckReport)

            noEosOutput.copyTo(File(directory, "output-no-eos.mp4"), overwrite = true)
            eosOutput.copyTo(File(directory, "output-explicit-eos.mp4"), overwrite = true)
            report.put("preserved_artifacts", JSONArray(listOf(
                "output-no-eos.mp4",
                "output-explicit-eos.mp4",
                "source.packets",
                "no_eos.packets",
                "explicit_eos.packets",
                "source.decoded-streamhash",
                "no_eos.decoded-streamhash",
                "explicit_eos.decoded-streamhash",
                "result.json"
            )))

            val sourceAfterHash = digest(retainedSource)
            val nativeAfterHash = digest(retainedNative)
            check(sourceBeforeHash == sourceAfterHash) { "retained source was modified during experiment" }
            check(nativeBeforeHash == nativeAfterHash) { "retained native output was modified during experiment" }
            retainedCheck.put("retained_source_sha256_after", sourceAfterHash)
                .put("retained_source_unchanged", true)
                .put("retained_native_sha256_after", nativeAfterHash)
                .put("retained_native_unchanged", true)

            report.put("experiment_completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_message", error.message?.take(400))
        } finally {
            report.put("scratch_deleted", scratch.deleteRecursively())
            File(directory, "result.json").writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("isolated explicit-EOS experiment execution failed", it) }
        assertTrue("owned scratch cleanup failed", report.getBoolean("scratch_deleted"))
    }

    @Test fun diagnosesRetainedEosSeekTargets() {
        val app = instrument.targetContext.applicationContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun")) { "missing evidenceRun argument" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid evidenceRun: $run" }
        val externalFilesDir = requireNotNull(app.getExternalFilesDir(null)) { "missing external files directory" }
        val directory = File(externalFilesDir, "approach-b/$run")
        check(directory.mkdirs()) { "failed to create evidence directory: ${directory.path}" }

        val files = listOf(
            "source" to File(externalFilesDir, "approach-b/approach-b-native-5ff701/retained-source.mp4"),
            "no_eos" to File(externalFilesDir, "approach-b/approach-b-eos-93e2ed84/output-no-eos.mp4"),
            "explicit_eos" to File(externalFilesDir, "approach-b/approach-b-eos-93e2ed84/output-explicit-eos.mp4")
        )
        files.forEach { (label, file) -> check(file.isFile) { "missing artifact $label: ${file.path}" } }
        val beforeHashes = files.associate { it.first to digest(it.second) }

        val report = JSONObject()
            .put("scope", "ISOLATED H264/AAC MP4 retained artifact seek and sample diagnostic; no production or network integration")
            .put("production_ready", false).put("published", false)
            .put("timing_scope", "diagnostic seek targets and sample walk; no measured download overlap or production speedup")
            .put("unproven", JSONArray(listOf("real network acquisition", "pause/resume/retry/process death", "production native gates", "MediaStore B-only publication", "cross-format transformations", "whole container preservation")))
            .put("artifacts_before_sha256", JSONObject(beforeHashes))

        var failure: Throwable? = null
        try {
            val sourceDurations = mutableMapOf<String, Long>()
            val sourceExt = MediaExtractor()
            try {
                sourceExt.setDataSource(files.first { it.first == "source" }.second.path)
                val srcAv = (0 until sourceExt.trackCount).filter { sourceExt.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.let { m -> m.startsWith("video/") || m.startsWith("audio/") } == true }
                    .sortedBy { if (sourceExt.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) 0 else 1 }
                for (t in srcAv) {
                    val f = sourceExt.getTrackFormat(t)
                    sourceDurations[if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) "video" else "audio"] = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                }
            } finally { sourceExt.release() }
            report.put("source_track_durations_us", JSONObject(sourceDurations as Map<*, *>))

            val filesReport = JSONObject()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            for ((label, file) in files) {
                val fileObj = JSONObject().put("path", file.path).put("bytes", file.length())
                val tracksArray = JSONArray()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.let { m -> m.startsWith("video/") || m.startsWith("audio/") } == true }
                        .sortedBy { if (extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) 0 else 1 }
                    var origin = 0L
                    for (track in tracks) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                        val type = if (mime.startsWith("video/")) "video" else "audio"
                        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                        extractor.selectTrack(track); extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        if (track == tracks.first()) origin = extractor.sampleTime

                        val formatObj = JSONObject()
                        for (k in listOf("mime", "width", "height", "sample-rate", "channel-count", "rotation-degrees")) {
                            formatObj.put(k, if (format.containsKey(k)) (if (k == "mime") format.getString(k) else format.getInteger(k)) else "MISSING")
                        }
                        val csdObj = JSONObject()
                        for (k in listOf("csd-0", "csd-1", "csd-2")) {
                            val buf = if (format.containsKey(k)) format.getByteBuffer(k)?.duplicate() else null
                            csdObj.put(k, if (buf != null) { val b = ByteArray(buf.remaining()); buf.get(b); JSONObject().put("length", b.size).put("sha256", hash(b)) } else "MISSING")
                        }

                        val content = MessageDigest.getInstance("SHA-256")
                        var count = 0
                        while (extractor.sampleTime >= 0) {
                            buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                            check(size > 0 && size <= buffer.capacity()) { "invalid_indexed_sample" }
                            content.update("${extractor.sampleTime - origin}:${extractor.sampleFlags}:$size:".toByteArray())
                            buffer.position(0); buffer.limit(size); content.update(buffer); count++
                            if (!extractor.advance()) break
                        }
                        check(count > 0) { "no_samples" }

                        fun seekRecords(targets: List<Long>): JSONArray {
                            val arr = JSONArray()
                            for (target in targets) {
                                val record = JSONObject().put("target_us", target)
                                try {
                                    extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                    buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                                    if (size > 0) {
                                        val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes)
                                        record.put("returned_pts_us", extractor.sampleTime).put("sample_time_relative_us", extractor.sampleTime - origin)
                                            .put("flags", extractor.sampleFlags).put("size", size).put("payload_sha256", hash(bytes))
                                    } else record.put("error", "no_sample_returned:size=$size")
                                } catch (e: Exception) { record.put("error", "${e.javaClass.simpleName}:${e.message}") }
                                arr.put(record)
                            }
                            return arr
                        }

                        val srcDur = sourceDurations[type] ?: duration
                        tracksArray.put(JSONObject().put("track_index", track).put("type", type).put("duration_us", duration)
                            .put("format", formatObj).put("csd", csdObj).put("sample_count", count)
                            .put("sample_digest", hex(content.digest()))
                            .put("per_file_seeks", seekRecords(listOf(0L, duration / 2, (duration - 500_000L).coerceAtLeast(0L))))
                            .put("common_source_seeks", seekRecords(listOf(0L, srcDur / 2, (srcDur - 500_000L).coerceAtLeast(0L)))))
                        extractor.unselectTrack(track)
                    }
                    fileObj.put("tracks", tracksArray)
                } finally { extractor.release() }
                filesReport.put(label, fileObj)
            }
            report.put("files", filesReport)

            val afterHashes = files.associate { it.first to digest(it.second) }
            report.put("artifacts_after_sha256", JSONObject(afterHashes))
            for ((key, _) in files) check(beforeHashes[key] == afterHashes[key]) { "artifact $key modified: before=${beforeHashes[key]} after=${afterHashes[key]}" }
            report.put("artifacts_unchanged", true).put("diagnostic_completed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_message", error.message?.take(400))
        } finally {
            File(directory, "result.json").writeText(report.toString(2))
        }
        failure?.let { throw AssertionError("retained eos seek diagnostic failed", it) }
        val finalHashes = files.associate { it.first to digest(it.second) }
        for ((key, _) in files) assertTrue("artifact $key unchanged", beforeHashes[key] == finalHashes[key])
    }

    private fun strict(file: File, expected: Probe): Verdict {
        val started = SystemClock.elapsedRealtime()
        return try {
            runBlocking { Processor().validate(file, audio = false, requireAudio = true, expectedHeight = expected.height,
                expectedDuration = expected.duration, expectedContainer = "mp4", expectedWidth = expected.width,
                expectedFps = expected.fps, expectedVideoCodec = "h264") }
            Verdict(true, SystemClock.elapsedRealtime() - started, "accepted")
        } catch (error: MediaException) {
            Verdict(false, SystemClock.elapsedRealtime() - started, "${error.code}:${error.validationCheck}")
        }
    }

    private fun experimental(file: File, reference: Evidence, scratch: File, label: String): Verdict {
        val started = SystemClock.elapsedRealtime()
        return try {
            validateTopLevelBoxes(file)
            val actual = evidence(file, reference.probe, scratch, label)
            check(actual.probe.config == reference.probe.config) { "codec_configuration_changed" }
            check(actual.packets == reference.packets) { "encoded_packets_or_timeline_changed" }
            check(actual.nativeSamples == reference.nativeSamples) { "independent_indexed_samples_or_seek_changed" }
            Verdict(true, SystemClock.elapsedRealtime() - started, "profile_matched")
        } catch (error: Exception) {
            Verdict(false, SystemClock.elapsedRealtime() - started, "${error.javaClass.simpleName}:${error.message?.take(180)}",
                error.message?.startsWith("unsupported_") == true)
        }
    }

    private fun evidence(file: File, expected: Probe, scratch: File, label: String): Evidence {
        val metadata = probe(file)
        require(kotlin.math.abs(metadata.duration - expected.duration) <= maxOf(1.5, expected.duration * .02)) { "duration_changed" }
        return Evidence(metadata, packetSignature(file, File(scratch, "$label.packets")), nativeSignature(file), digest(file))
    }

    private fun probe(file: File): Probe {
        val info = requireNotNull(FFprobeKit.getMediaInformation(file.path).mediaInformation) { "no_media_information" }
        val streams = info.streams.filter { it.type in listOf("video", "audio") }
        require(streams.count { it.type == "video" } == 1 && streams.count { it.type == "audio" } == 1) { "unsupported_track_layout" }
        val video = streams.first { it.type == "video" }
        require(video.codec == "h264" && streams.first { it.type == "audio" }.codec == "aac") { "unsupported_codec_profile_requires_A" }
        val fields = listOf("codec_name", "profile", "level", "width", "height", "pix_fmt", "sample_rate", "channels", "channel_layout")
        val config = streams.sortedBy { it.type }.joinToString("|") { stream ->
            stream.type + fields.joinToString(",") { key -> "$key=${stream.allProperties.opt(key)}" }
        }
        val rate = video.allProperties.optString("avg_frame_rate").split('/')
        val fps = if (rate.size == 2 && rate[1].toDoubleOrNull()?.let { it > 0 } == true)
            rate[0].toDoubleOrNull()?.div(rate[1].toDouble()) else null
        return Probe(requireNotNull(info.duration?.toDoubleOrNull()), video.width.toInt(), video.height.toInt(), fps, config)
    }

    private fun packetSignature(file: File, output: File): String {
        execute(listOf("-i", file.path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-f", "framehash", "-hash", "sha256", output.path))
        val lines = output.readLines()
        val bases = lines.filter { it.startsWith("#tb ") }.associate { line ->
            val index = line.substringAfter("#tb ").substringBefore(':').trim().toInt()
            val parts = line.substringAfter(':').trim().split('/')
            index to (parts[0].toBigInteger() to parts[1].toBigInteger())
        }
        val rows = lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split(',').map(String::trim) }
        require(rows.any { it[0] == "0" } && rows.any { it[0] == "1" }) { "missing_encoded_track" }
        fun timestamp(row: List<String>, index: Int): Fraction {
            val base = requireNotNull(bases[row[0].toInt()])
            return fraction(row[index].toBigInteger() * base.first, base.second)
        }
        val origin = timestamp(rows.first { it[0] == "0" }, 2)
        val canonical = rows.groupBy { it[0] }.toSortedMap().flatMap { (_, samples) -> samples.map { row ->
            "${row[0]},${timestamp(row, 1) - origin},${timestamp(row, 2) - origin},${timestamp(row, 3)},${row[4]},${row[5]}"
        } }.joinToString("\n")
        return hash(canonical.toByteArray())
    }

    private fun nativeSignature(file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.let { mime -> mime.startsWith("video/") || mime.startsWith("audio/") } == true }
                .sortedBy { if (extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) 0 else 1 }
            val signature = StringBuilder()
            var origin = 0L
            for (track in tracks) {
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (signature.isEmpty()) origin = extractor.sampleTime
                for (key in listOf("mime", "width", "height", "sample-rate", "channel-count", "rotation-degrees")) {
                    val value = if (!format.containsKey(key)) null else if (key == "mime") format.getString(key) else format.getInteger(key)
                    signature.append("$key=$value;")
                }
                for (key in listOf("csd-0", "csd-1", "csd-2")) (if (format.containsKey(key)) format.getByteBuffer(key) else null)?.duplicate()?.let { buffer ->
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); signature.append("$key=${hash(bytes)};")
                }
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val content = MessageDigest.getInstance("SHA-256")
                var count = 0
                while (extractor.sampleTime >= 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    check(size > 0 && size <= buffer.capacity()) { "invalid_indexed_sample" }
                    content.update("${extractor.sampleTime - origin}:${extractor.sampleFlags}:$size:".toByteArray())
                    buffer.position(0); buffer.limit(size); content.update(buffer)
                    count++
                    if (!extractor.advance()) break
                }
                check(count > 0)
                signature.append("count=$count;payload=${hex(content.digest())};")
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                for (target in listOf(0L, duration / 2, (duration - 500_000L).coerceAtLeast(0))) {
                    extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                    check(size > 0) { "seek_failed" }
                    val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes)
                    signature.append("seek=${extractor.sampleTime - origin}:${hash(bytes)};")
                }
                extractor.unselectTrack(track)
            }
            return hash(signature.toString().toByteArray())
        } finally { extractor.release() }
    }

    private fun nativeRewrite(source: File, output: File, mode: String, explicitVideoEosUs: Long? = null) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.path)
            val mapping = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString("mime")?.let { m -> m.startsWith("video/") || m.startsWith("audio/") } == true }
                .associateWith { index -> extractor.selectTrack(index); muxer.addTrack(extractor.getTrackFormat(index)) }
            val video = mapping.keys.first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            var videoIndex = 0
            while (extractor.sampleTrackIndex >= 0) {
                val track = extractor.sampleTrackIndex
                buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                check(size > 0)
                val info = MediaCodec.BufferInfo().apply { set(0, size, extractor.sampleTime + if (mode == "timestamp" && track == video) 250_000L else 0L, extractor.sampleFlags) }
                if (!(mode == "drop" && track == video && videoIndex == 20)) muxer.writeSampleData(mapping.getValue(track), buffer, info)
                if (mode == "duplicate" && track == video && videoIndex == 20) {
                    buffer.position(0); info.presentationTimeUs += 1; muxer.writeSampleData(mapping.getValue(track), buffer, info)
                }
                if (track == video) videoIndex++
                if (!extractor.advance()) break
            }
            if (explicitVideoEosUs != null) {
                val eosBuffer = ByteBuffer.allocate(0)
                val eosInfo = MediaCodec.BufferInfo().apply {
                    set(0, 0, explicitVideoEosUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                muxer.writeSampleData(mapping.getValue(video), eosBuffer, eosInfo)
            }
        } finally {
            try { if (started) muxer.stop() } finally { muxer.release(); extractor.release() }
        }
    }

    private fun parseLastVideoPacket(file: File): JSONObject {
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split(',').map(String::trim) }
        val v = lines.filter { it[0] == "0" }
        val a = lines.filter { it[0] == "1" }
        val last = v.last()
        return JSONObject()
            .put("video_count", v.size)
            .put("audio_count", a.size)
            .put("last_video_pts", last[2])
            .put("last_video_duration", last[3])
            .put("last_video_size", last[4])
            .put("last_video_hash", last[5])
    }

    private fun extractSeekRecords(file: File): List<JSONObject> {
        val extractor = MediaExtractor()
        val records = mutableListOf<JSONObject>()
        try {
            extractor.setDataSource(file.path)
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.let { mime -> mime.startsWith("video/") || mime.startsWith("audio/") } == true
            }.sortedBy { if (extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) 0 else 1 }
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            for (track in tracks) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                extractor.selectTrack(track)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                for (target in listOf(0L, duration / 2, (duration - 500_000L).coerceAtLeast(0))) {
                    extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    check(size > 0) { "seek_failed" }
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.get(bytes)
                    records.add(JSONObject()
                        .put("track", track)
                        .put("mime", mime)
                        .put("target_us", target)
                        .put("returned_pts_us", extractor.sampleTime)
                        .put("flags", extractor.sampleFlags)
                        .put("size", size)
                        .put("payload_sha256", hash(bytes))
                    )
                }
                extractor.unselectTrack(track)
            }
        } finally { extractor.release() }
        return records
    }

    private fun validateTopLevelBoxes(file: File) {
        RandomAccessFile(file, "r").use { input ->
            val boxes = boxes(input, 0, input.length())
            check(boxes.count { it.type == "moov" } == 1 && boxes.any { it.type == "mdat" } && boxes.any { it.type == "ftyp" }) { "incomplete_mp4_structure" }
        }
    }

    private fun boxes(file: RandomAccessFile, start: Long, end: Long): List<Box> {
        val result = ArrayList<Box>(); var position = start
        while (position < end) {
            check(end - position >= 8) { "truncated_box_header" }
            file.seek(position); var size = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { file.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { size = file.readLong(); header = 16 }
            if (size == 0L) size = end - position
            check(size >= header && size <= end - position) { "invalid_box_size:$type" }
            result += Box(type, position, position + header, position + size); position += size
        }
        return result
    }

    private fun mutateChunkOffset(file: File) {
        RandomAccessFile(file, "rw").use { input ->
            fun find(start: Long, end: Long): Box? {
                for (box in boxes(input, start, end)) {
                    if (box.type in listOf("stco", "co64")) return box
                    if (box.type in listOf("moov", "trak", "mdia", "minf", "stbl")) find(box.body, box.end)?.let { return it }
                }
                return null
            }
            val box = requireNotNull(find(0, input.length())) { "no_chunk_offset_fixture" }
            input.seek(box.body + 8)
            if (box.type == "stco") { val offset = input.readInt(); input.seek(box.body + 8); input.writeInt(offset + 37) }
            else { val offset = input.readLong(); input.seek(box.body + 8); input.writeLong(offset + 37) }
        }
    }

    private fun mutatePayload(file: File) {
        val session = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-select_streams", "v:0", "-read_intervals", "1%+#3",
            "-show_packets", "-show_entries", "packet=pos,size", "-of", "json", file.path))
        check(ReturnCode.isSuccess(session.returnCode))
        val rows = JSONObject(session.output).getJSONArray("packets")
        val packet = rows.getJSONObject(rows.length() - 1)
        RandomAccessFile(file, "rw").use { output ->
            val begin = packet.getLong("pos"); val end = begin + packet.getLong("size"); var position = begin
            while (position + 5 < end) {
                output.seek(position); val length = output.readInt().toLong() and 0xffffffffL
                check(length > 0 && length <= end - position - 4)
                val header = output.readUnsignedByte()
                if ((header and 31) in listOf(1, 5) && length > 4) {
                    output.seek(position + 5); output.write(ByteArray((length - 1).toInt())); return
                }
                position += 4 + length
            }
            error("no_slice_fixture_found")
        }
    }

    private fun mutateConfiguration(file: File) {
        val bytes = file.readBytes(); val marker = "avcC".toByteArray()
        val positions = (4..bytes.size - 16).filter { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        check(positions.isNotEmpty())
        RandomAccessFile(file, "rw").use { input -> input.seek(positions.first() + 4L + 5); input.writeByte(0xe0) }
    }

    private fun execute(args: List<String>) {
        val session = FFmpegKit.executeWithArguments((listOf("-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe") + args).toTypedArray())
        check(ReturnCode.isSuccess(session.returnCode)) { "native_fixture_or_packet_operation_failed:${session.returnCode}" }
    }
    private fun verdictJson(v: Verdict) = JSONObject().put("accepted", v.accepted).put("ms", v.ms).put("reason", v.reason)
        .put("status", if (v.fallback) "FALLBACK_A_REQUIRED" else if (v.accepted) "ACCEPT" else "REJECT")
    private fun resources(): Map<String, Long> {
        val result = linkedMapOf("process_cpu_ms" to android.os.Process.getElapsedCpuTime(),
            "native_heap_bytes" to android.os.Debug.getNativeHeapAllocatedSize(),
            "java_heap_bytes" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()))
        val io = File("/proc/self/io")
        if (io.canRead()) io.forEachLine { line ->
            val fields = line.split(':', limit = 2)
            if (fields.size == 2 && fields[0] in listOf("rchar", "wchar", "read_bytes", "write_bytes")) {
                fields[1].trim().toLongOrNull()?.let { result[fields[0]] = it }
            }
        }
        File("/proc/self/status").forEachLine { line ->
            if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) {
                val parts = line.trim().split(Regex("\\s+"))
                result[parts[0].removeSuffix(":") + "_bytes"] = parts[1].toLong() * 1024
            }
        }
        return result
    }
    private fun resourceDelta(before: Map<String, Long>, after: Map<String, Long>): JSONObject {
        val delta = JSONObject()
        for (key in listOf("process_cpu_ms", "rchar", "wchar", "read_bytes", "write_bytes")) {
            if (before[key] != null && after[key] != null) delta.put(key, after.getValue(key) - before.getValue(key))
        }
        return delta
    }
    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun digest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
