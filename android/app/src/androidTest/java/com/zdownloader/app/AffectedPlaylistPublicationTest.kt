package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two explicitly bounded items from the affected collection, retaining publications and test history. */
class AffectedPlaylistPublicationTest {
    @Test fun realCollectionPublishesTwoValidMp3ItemsSequentially(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val source = checkNotNull(args.getString("playlistSource")) { "Missing playlistSource argument" }
        val evidenceRun = args.getString("evidenceRun", "manual")
        val knownDurations = args.getString("expectedDurationsSeconds")?.split(',')?.map { it.toDouble() }
        val byteBudget = 64L * 1024 * 1024
        val folder = File(app.getExternalFilesDir(null), "advanced-audit/$evidenceRun").apply { mkdirs() }
        val reportFile = File(folder, "affected-playlist-publications.json")

        val report = JSONObject().put("scope", "First two public entries only; actual extraction, audio transfer, conversion and gallery publication. Original failed request is retained.")
        val outputs = JSONArray()
        report.put("outputs", outputs)

        var primaryError: Throwable? = null
        var finalizationError: Throwable? = null

        fun recordFinalizationError(t: Throwable) {
            if (primaryError != null) {
                primaryError!!.addSuppressed(t)
            } else if (finalizationError == null) {
                finalizationError = t
            } else {
                finalizationError!!.addSuppressed(t)
            }
        }

        fun flushReport() {
            try {
                reportFile.writeText(report.toString(2))
            } catch (t: Throwable) {
                recordFinalizationError(t)
            }
        }

        // Initialize report file immediately before any network, database or extraction calls
        flushReport()

        var runner: Job? = null
        var batch: String? = null
        var before: Set<String> = emptySet()

        try {
            // Read-only precondition: Coordinator.run() is global across the application.
            // Check for existing active or runnable downloads (excluding completed, failed, cancelled, paused).
            val preActive = app.database.openHelper.readableDatabase.query(
                "SELECT id, status FROM downloads WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED')"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add("${cursor.getString(0)}:${cursor.getString(1)}")
                    }
                }
            }
            report.put("precondition_active_downloads", JSONArray(preActive))
            flushReport()
            check(preActive.isEmpty()) {
                "Refusing live test: unrelated runnable or active downloads detected in queue: $preActive"
            }

            before = app.database.openHelper.readableDatabase.query("SELECT id FROM downloads").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

            // Live extraction and selection
            val page = PlaylistPage.parse(app.extractor.playlist(source, UUID.randomUUID().toString(), 0), source)
            report.put("playlist_title", page.title).put("playlist_total_items", page.items.size)
            flushReport()

            val chosen = page.items.filter { it.selectable }.take(2)
            assertEquals("Two public entries are needed for this bounded collection check", 2, chosen.size)
            val expectedDurations = chosen.mapIndexed { index, entry ->
                val duration = entry.duration ?: knownDurations?.getOrNull(index)
                require(duration != null && duration.isFinite() && duration > 0 && duration <= 300) {
                    "Refusing an unbounded live source: supply independently verified durations of at most 300 seconds"
                }
                duration
            }
            report.put("selected", JSONArray().apply {
                chosen.forEachIndexed { index, entry ->
                    put(JSONObject().put("position", entry.position).put("url", entry.url)
                        .put("expected_duration_s", expectedDurations[index]))
                }
            })
            flushReport()

            val nonce = UUID.randomUUID().toString()
            batch = app.playlists.submit(
                "verification-$nonce",
                page.copy(title = "MediaFlow verification $nonce"),
                chosen,
                PlaylistPolicy(audio = true, output = "mp3", bitrate = 192),
                numbered = true,
                concurrency = 1
            )
            report.put("batch_id", batch)
            flushReport()

            runner = launch(Dispatchers.IO) { app.coordinator.run() }
            withTimeout(3 * 60_000L) {
                while (true) {
                    val current = app.repository.dao.batchItems(batch!!)
                    report.put("observations", JSONArray().apply {
                        current.forEach { row ->
                            put(
                                JSONObject()
                                    .put("id", row.id)
                                    .put("position", row.playlistPosition)
                                    .put("state", row.status)
                                    .put("error", row.error)
                                    .put("bytes", row.bytes)
                                     .put("checkpoint", row.checkpoint)
                                     .put("output_size", row.outputSize)
                                     .put("uri", row.uri)
                                     .put("duration_s", row.duration)
                            )
                        }
                    })
                    flushReport()
                    check(current.sumOf { it.bytes.coerceAtLeast(0) } <= byteBudget) { "Live-source transfer exceeded 64 MiB budget" }
                    check(current.none { it.error == "RATE_LIMITED" }) { "Provider rate limited the live test; no further live retries will be attempted" }
                    if (current.size == 2 && current.all { it.status in setOf("COMPLETED", "FAILED", "CANCELLED") }) break
                    delay(1000)
                }
            }

            val rows = app.repository.dao.batchItems(batch!!)
            assertEquals("Batch must contain exactly two items", 2, rows.size)

            for ((index, row) in rows.withIndex()) {
                val itemDiag = JSONObject()
                    .put("id", row.id)
                    .put("position", row.playlistPosition)
                    .put("state", row.status)
                    .put("error", row.error)
                    .put("source_bytes", row.bytes)
                    .put("output_bytes", row.outputSize)
                    .put("checkpoint", row.checkpoint)
                    .put("events", JSONArray().apply {
                        app.repository.dao.events(row.id).forEach {
                            put(JSONObject().put("state", it.state).put("time", it.time).put("error", it.error))
                        }
                    })

                var mediaStoreValid = false
                var probedAudioCodec: String? = null
                var hasAttachedPic = false
                var probedDuration = 0.0

                if (row.status == "COMPLETED" && row.uri != null) {
                    val uri = android.net.Uri.parse(row.uri!!)
                    itemDiag.put("uri", row.uri)

                    // Single MediaStore query
                    app.contentResolver.query(uri, arrayOf("_size", "is_pending"), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val size = cursor.getLong(0)
                            val isPending = cursor.getInt(1)
                            itemDiag.put("mediastore_size", size).put("is_pending", isPending)
                            mediaStoreValid = (size > 0 && isPending == 0)
                        }
                    }

                    // Single probe via transient cache copy (probe transient deleted, final publication retained)
                    val probeFile = File(app.cacheDir, "playlist-probe-$nonce-${row.id}.mp3")
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            probeFile.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var copied = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    check(copied <= byteBudget) { "Published output exceeded probe budget" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (probeFile.isFile && probeFile.length() > 0) {
                            val info = FFprobeKit.getMediaInformation(probeFile.path).mediaInformation
                            if (info != null) {
                                probedAudioCodec = info.streams.firstOrNull { it.type == "audio" }?.codec
                                hasAttachedPic = info.streams.filter { it.type == "video" }.all {
                                    it.allProperties.optJSONObject("disposition")?.optInt("attached_pic") == 1
                                }
                                probedDuration = info.duration?.toDoubleOrNull() ?: 0.0
                                itemDiag.put("probe_audio_codec", probedAudioCodec)
                                    .put("has_attached_pic", hasAttachedPic)
                                    .put("duration_s", info.duration)
                                val decoded = FFmpegKit.executeWithArguments(arrayOf(
                                    "-v", "error", "-xerror", "-i", probeFile.path,
                                    "-map", "0:a:0", "-f", "null", "-"
                                ))
                                itemDiag.put("full_decode_verified", ReturnCode.isSuccess(decoded.returnCode))
                                assertTrue("Entire published audio must decode", ReturnCode.isSuccess(decoded.returnCode))
                            }
                        }
                    } catch (probeError: Throwable) {
                        itemDiag.put("probe_error", probeError.message)
                    } finally {
                        probeFile.delete()
                    }
                }

                outputs.put(itemDiag)
                flushReport()

                // Evaluate assertions using the single probe result
                assertEquals("$row", "COMPLETED", row.status)
                assertTrue("MediaStore entry must be valid and unpending for $row", mediaStoreValid)
                assertEquals("Audio stream must be mp3", "mp3", probedAudioCodec)
                assertTrue("Entire published audio must decode", itemDiag.optBoolean("full_decode_verified"))
                assertTrue("Cover image must have attached_pic disposition", hasAttachedPic)
                val expectedDuration = expectedDurations[index]
                assertTrue(
                    "Duration difference |$probedDuration - $expectedDuration| exceeds tolerance for $row",
                    kotlin.math.abs(probedDuration - expectedDuration) < maxOf(.5, expectedDuration * .03)
                )

                itemDiag.put("published_mp3_verified", true)
                    .put("source_bytes", row.bytes)
                    .put("position", row.playlistPosition)
                flushReport()
            }

            val secondStart = app.repository.dao.events(rows[1].id).first { it.state == "ANALYZING" }.time
            val firstFinish = checkNotNull(rows[0].finishedAt) { "First item missing finishedAt" }
            report.put("first_finished_at", firstFinish).put("second_analyzing_at", secondStart)
            flushReport()
            assertTrue(
                "The next item must start only after the first is published ($secondStart >= $firstFinish)",
                secondStart >= firstFinish
            )
            report.put("complete", true).put("sequential_publication_verified", true)
            flushReport()
        } catch (t: Throwable) {
            primaryError = t
            try {
                report.put("failure", JSONObject().put("type", t.javaClass.name).put("message", t.message))
            } catch (jsonErr: Throwable) {
                recordFinalizationError(jsonErr)
            }
            flushReport()
        } finally {
            // 1. Stop the runner if active to prevent uncontrolled background work
            if (runner != null) {
                try {
                    runner!!.cancelAndJoin()
                } catch (t: Throwable) {
                    recordFinalizationError(t)
                }
            }

            // 2. Cancel and stop ONLY nonterminal items belonging to this test's OWN batch
            // Retain completed publications, test history, and batch records. Do not delete files or clear playback.
            if (batch != null) {
                try {
                    val batchRows = app.repository.dao.batchItems(batch!!)
                    report.put("final_states", JSONArray().apply {
                        batchRows.forEach { row ->
                            put(JSONObject().put("id", row.id).put("state", row.status)
                                .put("error", row.error).put("uri", row.uri).put("output_size", row.outputSize))
                        }
                    })
                    for (row in batchRows) {
                        if (row.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) {
                            try {
                                app.repository.cancel(row.id)
                                app.coordinator.stopAndAwait(row.id)
                            } catch (cancelErr: Throwable) {
                                recordFinalizationError(cancelErr)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    recordFinalizationError(t)
                }
            }

            // 3. Verify baseline history IDs survived (read-only verification)
            try {
                val after = app.database.openHelper.readableDatabase.query("SELECT id FROM downloads").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val historyPreserved = after.containsAll(before)
                report.put("history_preserved", historyPreserved)
                if (!historyPreserved) {
                    val histErr = AssertionError("Original history IDs must survive the bounded verification (missing: ${before - after})")
                    recordFinalizationError(histErr)
                }
            } catch (t: Throwable) {
                recordFinalizationError(t)
            }

            // 4. Final flush of evidence to disk
            flushReport()

            // 5. Propagate primary error without it ever being replaced or masked
            if (primaryError != null) {
                if (finalizationError != null) {
                    primaryError!!.addSuppressed(finalizationError!!)
                }
                throw primaryError!!
            }
            if (finalizationError != null) {
                throw finalizationError!!
            }
        }
    }
}
