package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Exports only timing fields for the previously accepted long video. */
class VideoPhaseDiagnosticsSnapshotTest {
    private data class Event(
        val operation: String,
        val stage: String,
        val eventTimeMs: Long?,
        val fields: JSONObject
    )

    @Test
    fun snapshotsExistingLongVideoDiagnosticsOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        assertTrue("unexpected target package", app.packageName == "com.zdownloader.app")

        val run = InstrumentationRegistry.getArguments().getString("evidenceRun")
            ?: throw AssertionError("evidenceRun instrumentation argument required")
        assertTrue("invalid evidenceRun", run.matches(Regex("[A-Za-z0-9_-]{1,64}")))

        val base = app.getExternalFilesDir(null)
            ?: throw AssertionError("external app files directory unavailable")
        val reportFile = File(File(base, "advanced-audit/$run"), "video-phase-diagnostics.json")
        reportFile.parentFile?.mkdirs()
        assertTrue("refusing to overwrite existing evidence", reportFile.createNewFile())

        val targetBytes = 327_950_051L
        val events = ArrayList<Event>()
        var linesRead = 0
        for (name in listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl")) {
            val source = File(app.filesDir, name)
            if (!source.isFile) continue
            source.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    linesRead++
                    val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    val operation = row.optString("operation").takeIf { it.isNotBlank() } ?: return@forEach
                    val stage = row.optString("stage")
                    val details = row.optJSONObject("details") ?: JSONObject()
                    val fields = when (stage) {
                        "video_mux_timing" -> {
                            if (details.optLong("output_bytes", -1L) != targetBytes) return@forEach
                            scalarFields(details, listOf("queue_wait_ms", "inspection_ms", "mux_ms", "video_copy", "output_bytes"))
                        }
                        "video_validation_timing" -> scalarFields(details, listOf("validation_ms", "validated"))
                        "transfer_completed", "processing_completed", "output_validated", "gallery_publication_completed" ->
                            scalarFields(details, listOf("elapsed_realtime_ms"))
                        else -> return@forEach
                    }
                    events += Event(operation, stage, row.optLong("time", -1L).takeIf { it >= 0L }, fields)
                }
            }
        }

        val targetOperations = events.asSequence()
            .filter { it.stage == "video_mux_timing" }
            .map { it.operation }
            .toSet()
        val matches = JSONArray()
        for (operation in targetOperations) {
            val related = events.filter { it.operation == operation }
            val checkpoints = JSONObject()
            related.filter { it.stage in CHECKPOINTS }.forEach { event ->
                if (event.fields.has("elapsed_realtime_ms")) {
                    checkpoints.put(event.stage, event.fields.getLong("elapsed_realtime_ms"))
                }
            }
            val phaseDurations = JSONObject()
            duration(checkpoints, "transfer_completed", "processing_completed")
                ?.let { phaseDurations.put("transfer_to_processing_ms", it) }
            duration(checkpoints, "processing_completed", "output_validated")
                ?.let { phaseDurations.put("processing_to_validation_complete_ms", it) }
            duration(checkpoints, "output_validated", "gallery_publication_completed")
                ?.let { phaseDurations.put("validation_to_publication_complete_ms", it) }

            val item = JSONObject()
                .put("operation_hash_prefix", operationHash(operation).take(12))
                .put("mux_events", JSONArray())
                .put("validation_events", JSONArray())
                .put("phase_checkpoints_elapsed_realtime_ms", checkpoints)
                .put("derived_phase_durations", phaseDurations)
            related.forEach { event ->
                val output = JSONObject().put("event_time_epoch_ms", event.eventTimeMs ?: JSONObject.NULL)
                val keys = when (event.stage) {
                    "video_mux_timing" -> listOf("queue_wait_ms", "inspection_ms", "mux_ms", "video_copy", "output_bytes")
                    "video_validation_timing" -> listOf("validation_ms", "validated")
                    else -> emptyList()
                }
                keys.forEach { key -> if (event.fields.has(key)) output.put(key, event.fields.get(key)) }
                when (event.stage) {
                    "video_mux_timing" -> item.getJSONArray("mux_events").put(output)
                    "video_validation_timing" -> item.getJSONArray("validation_events").put(output)
                }
            }
            matches.put(item)
        }

        val report = JSONObject()
            .put("target_bytes", targetBytes)
            .put("diagnostic_lines_read", linesRead)
            .put("found", matches.length() > 0)
            .put("matches", matches)
        val encoded = report.toString(2)
        assertTrue("sanitized evidence exceeds 10KB", encoded.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES)
        reportFile.writeText(encoded)
    }

    private fun scalarFields(source: JSONObject, keys: List<String>): JSONObject = JSONObject().apply {
        keys.forEach { key ->
            val value = source.opt(key)
            if (value is Number || value is Boolean) put(key, value)
        }
    }

    private fun duration(checkpoints: JSONObject, start: String, end: String): Long? {
        if (!checkpoints.has(start) || !checkpoints.has(end)) return null
        return checkpoints.getLong(end) - checkpoints.getLong(start)
    }

    private fun operationHash(operation: String): String = MessageDigest.getInstance("SHA-256")
        .digest(operation.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_REPORT_BYTES = 10 * 1024
        val CHECKPOINTS = setOf(
            "transfer_completed",
            "processing_completed",
            "output_validated",
            "gallery_publication_completed"
        )
    }
}
