package com.zdownloader.app

import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.playback.*
import com.zdownloader.app.updates.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Deliberately opt-in: real forward upgrade, production journal, no fabricated installed identity. */
class UpdateUpgradeAcceptanceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val external get() = File(app.getExternalFilesDir(null), "update-acceptance").apply { mkdirs() }
    private val baseline get() = File(app.filesDir, "update-acceptance/baseline.json")
    private val journal get() = File(app.filesDir, "app-updates/journal.json")
    private val payload get() = File(app.filesDir, "app-updates/payload.apk")
    private val settings get() = SettingsRepository(app)
    private val channel get() = if (BuildConfig.QA_SIGNED_RELEASE) "qa" else "stable"
    private val signer get() = if (BuildConfig.QA_SIGNED_RELEASE) "bc43d1d3a62cc52a692ba2bfbf11823219aaf2cef5c0a68e4499b39aa41d79bc"
        else BuildConfig.PRODUCTION_SIGNER_SHA256
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val fromCode get() = requireNotNull(arguments.getString("fromCode", "50")).toLong()
    private val toCode get() = requireNotNull(arguments.getString("toCode", "51")).toLong()
    private val fromName get() = requireNotNull(arguments.getString("fromName", "0.9.17"))
    private val toName get() = requireNotNull(arguments.getString("toName", "0.9.18"))

    private suspend fun evidence(phase: String, block: suspend (JSONObject) -> Unit) {
        val report = JSONObject().put("phase", phase).put("transport", "CONTROLLED; no live GitHub publication")
        try { block(report); report.put("passed", true) }
        catch (failure: Throwable) { report.put("passed", false).put("failure", failure.toString()); throw failure }
        finally { File(external, "$phase.json").writeText(report.toString(2)) }
    }

    @Test fun prepare() = runBlocking { prepareUpgrade(false) }

    @Test fun prepareForwardRerun() = runBlocking { prepareUpgrade(true) }

    private suspend fun prepareUpgrade(forward: Boolean) {
        evidence(if (forward) "prepare-forward" else "prepare") { report ->
            assertTrue("Only forward upgrades are permitted", toCode > fromCode)
            val identity = UpdateApkVerifier.installedIdentity(app)
            assertEquals(fromCode, identity.versionCode)
            assertEquals(fromName, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            assertEquals(setOf(signer), identity.signerSha256)
            assertEquals("moham34643fxx-sudo/mediaflow", BuildConfig.UPDATE_REPOSITORY)
            val previous = if (forward) preparePreviousFailure(report) else null
            if (!forward) assertFalse("Verify/clean the previous run before preparing again", baseline.exists())
            assertFalse("Do not overwrite an existing updater transfer", payload.exists())
            if (journal.exists()) {
                val existing = JSONObject(journal.readText())
                assertTrue("Existing updater operation must be idle", existing.getString("phase") in
                    (if (forward) setOf("INSTALLED", "NO_RELEASE", "CURRENT", "IDLE") else setOf("IDLE", "NO_RELEASE", "CURRENT")))
                assertFalse("Do not replace pending updater work", existing.optBoolean("autoInstall"))
                assertFalse(existing.optBoolean("installationPending"))
                assertTrue(existing.isNull("sessionId"))
                if (forward && existing.getString("phase") == "INSTALLED")
                    assertEquals(fromCode, existing.getJSONObject("release").getLong("versionCode"))
            }
            assertFalse("A real transfer is active", app.coordinator.running)
            assertTrue("Do not snapshot executable user work", app.repository.dao.observe().first().all {
                it.status in setOf("PAUSED", "COMPLETED", "FAILED", "CANCELLED")
            })
            val incoming = File(external, "incoming.apk")
            assertTrue(incoming.isFile && incoming.length() > 262144)
            @Suppress("DEPRECATION")
            val archive = requireNotNull(app.packageManager.getPackageArchiveInfo(incoming.path, PackageManager.GET_SIGNING_CERTIFICATES))
            assertEquals(app.packageName, archive.packageName)
            assertEquals(toCode, archive.longVersionCode)
            assertEquals(toName, archive.versionName)
            val abis = ZipFile(incoming).use { zip -> zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
            val abi = if (abis.size > 1) "universal" else abis.single()
            val releaseTag = (if (channel == "qa") "qa-" else "") + "local-$toName"
            val asset = UpdateAsset(abi,
                "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/$releaseTag/mediaflow-$abi.apk",
                UpdateApkVerifier.sha256(incoming), incoming.length())
            val release = UpdateRelease(app.packageName, channel, archive.longVersionCode, requireNotNull(archive.versionName),
                requireNotNull(archive.applicationInfo).minSdkVersion, null,
                mapOf("ar" to "اختبار ترقية محلي مضبوط إلى ${archive.versionName}؛ ليس إصدار GitHub منشوراً.",
                    "en" to "Controlled local upgrade to ${archive.versionName}; not a published GitHub release."), listOf(asset), null)
            UpdateApkVerifier(app).verify(incoming, release, asset)
            val currentSnapshot = if (forward) report.getJSONObject("firstUpgradeDifference").getJSONObject("observed") else null
            var metadataRequests = 0
            var apkRequests = 0
            val requests = JSONArray()
            val http = UpdateHttp(factory { request ->
                requests.put(JSONObject().put("url", request.url.toString()).put("range", request.header("Range"))
                    .put("ifRange", request.header("If-Range")))
                if (request.url.host == "controlled.example") {
                    metadataRequests++
                    response(request, 200, release.toJson().toString().toResponseBody())
                } else {
                    assertEquals(asset.url, request.url.toString())
                    apkRequests++
                    val offset = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                    if (apkRequests == 1) assertEquals(0L, offset) else {
                        assertEquals(262144L, offset)
                        assertEquals("\"upgrade-b-$toCode\"", request.header("If-Range"))
                    }
                    response(request, if (offset > 0) 206 else 200, body(incoming, offset, apkRequests == 1),
                        *listOfNotNull("ETag" to "\"upgrade-b-$toCode\"", if (offset > 0)
                            "Content-Range" to "bytes $offset-${incoming.length() - 1}/${incoming.length()}" else null).toTypedArray())
                }
            })
            // No production/Hilt AppUpdater is requested by this class. MainActivity is launched
            // only in the separate driver process, after this journal is durably READY.
            val updater = AppUpdater(app, UpdateConfiguration("https://controlled.example/update", BuildConfig.UPDATE_REPOSITORY, channel), http)
            updater.check(manual = true).join()
            assertEquals(UpdatePhase.AVAILABLE, updater.state.value.phase)
            assertEquals(0, apkRequests)
            updater.download(installWhenReady = false).join()
            assertEquals(UpdatePhase.ERROR, updater.state.value.phase)
            assertEquals(UpdateError.NETWORK, updater.state.value.error)
            assertEquals(262144L, payload.length())
            updater.clearError().join()
            assertEquals(UpdatePhase.PAUSED, updater.state.value.phase)
            assertNull(updater.state.value.error)
            updater.download(installWhenReady = false).join()
            assertEquals(UpdatePhase.READY, updater.state.value.phase)
            assertEquals(asset.sha256, UpdateApkVerifier.sha256(payload))
            assertFalse(updater.state.value.autoInstall)
            assertNull(updater.state.value.sessionId)
            assertEquals(1, metadataRequests)
            assertEquals(2, apkRequests)
            val seed = previous ?: seed()
            seed.remove("verified")
            val freshSnapshot = snapshot()
            if (forward) assertEquals("Forward preparation changed existing user data", currentSnapshot.toString(), freshSnapshot.toString())
            baseline.parentFile!!.mkdirs()
            baseline.writeText(seed.put("snapshot", freshSnapshot).put("bSha256", asset.sha256)
                .put("fromCode", fromCode).put("fromName", fromName).put("toCode", toCode).put("toName", toName)
                .put("aSha256", UpdateApkVerifier.sha256(File(app.applicationInfo.sourceDir)))
                .put("initialPreservation", if (forward) "NOT_PROVEN" else "PENDING")
                .put("forwardRerun", forward).toString(2))
            report.put("installedVersion", identity.versionCode).put("targetVersion", archive.longVersionCode)
                .put("signerSha256", signer).put("bSha256", asset.sha256).put("requests", requests)
                .put("interruptedBytes", 262144).put("resumedHttpStatus", 206).put("journal", JSONObject(journal.readText()))
                .put("fixture", seed).put("ready", true)
        }
    }

    /** Archive the failed test verbatim; the sole accepted discrepancy is the already observed theme change. */
    private suspend fun preparePreviousFailure(report: JSONObject): JSONObject {
        val old = JSONObject(baseline.readText())
        assertFalse("A verified baseline is not a failed-upgrade rerun", old.optBoolean("verified"))
        assertEquals(old.optLong("toCode", 51L), fromCode)
        assertEquals(old.optString("toName", "0.9.18"), fromName)
        val installedHash = UpdateApkVerifier.sha256(File(app.applicationInfo.sourceDir))
        assertEquals("Installed source must be the exact previous target APK", old.getString("bSha256"), installedHash)
        val expected = old.getJSONObject("snapshot")
        val observed = snapshot()
        val changed = (expected.keys().asSequence().toSet() + observed.keys().asSequence().toSet()).sorted()
            .filter { expected.opt(it)?.toString() != observed.opt(it)?.toString() }
        val difference = JSONObject().put("initialPreservation", "NOT_PROVEN").put("initialPreservationProven", false)
            .put("cause", "Unknown; human UI interaction is possible but unproven")
            .put("originalBaseline", old).put("originalExpected", expected).put("observed", observed)
            .put("changedFields", JSONArray(changed)).put("installedVersion", fromCode)
            .put("installedApkSha256", installedHash).put("signerSha256", signer)
            .put("originalJournal", if (journal.exists()) JSONObject(journal.readText()) else JSONObject.NULL)
        val differenceFile = File(external, "first-upgrade-difference.json")
        assertFalse("Keep the first difference evidence immutable", differenceFile.exists())
        differenceFile.writeText(difference.toString(2))
        report.put("initialPreservation", "NOT_PROVEN").put("firstUpgradeDifference", difference)
        // Preserve all prior evidence before prepare/driver/verify can write new reports.
        external.listFiles().orEmpty().filter { it.extension in setOf("json", "xml", "png") &&
            !it.name.startsWith("first-upgrade-") && !it.name.startsWith("baseline-") }.forEach {
            it.copyTo(File(external, "first-upgrade-${it.name}"), overwrite = false)
        }
        assertEquals(setOf("settings", "settingsSha256"), changed.toSet())
        val expectedSettings = expected.getString("settings")
        assertTrue(expectedSettings.contains("theme=light,"))
        assertEquals(expectedSettings.replace("theme=light,", "theme=system,"), observed.getString("settings"))
        // These exact raw PB hashes were recorded by the failed 50->51 verification.
        // Reject any further raw preference change, even one hidden by Settings defaults.
        assertEquals("f0d540d05f65a98bf208a0f25e3ccaf470b6f9b8fc26f98db3d752ddd4aa7a4f", expected.getString("settingsSha256"))
        assertEquals("59d2378a863182a5499a31f85ce75c7be58a2c5dbe213c0926b86b7c9e20b74e", observed.getString("settingsSha256"))
        val archiveName = "baseline-${old.optLong("fromCode", 50L)}-to-$fromCode-failed.json"
        baseline.copyTo(File(baseline.parentFile, archiveName), overwrite = false)
        baseline.copyTo(File(external, archiveName), overwrite = false)
        return JSONObject(old.toString()) // fixture IDs and originalSettings remain exactly the original values
    }

    @Test fun verify() = runBlocking {
        evidence("verify") { report ->
            val before = JSONObject(baseline.readText())
            val identity = UpdateApkVerifier.installedIdentity(app)
            assertEquals(toCode, identity.versionCode)
            assertEquals(toName, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            assertEquals(fromCode, before.optLong("fromCode", 50L))
            assertEquals(fromName, before.optString("fromName", "0.9.17"))
            assertEquals(toCode, before.optLong("toCode", 51L))
            assertEquals(toName, before.optString("toName", "0.9.18"))
            assertEquals(setOf(signer), identity.signerSha256)
            val installedApkSha256 = UpdateApkVerifier.sha256(File(app.applicationInfo.sourceDir))
            assertEquals("Installed package is not the frozen B APK", before.getString("bSha256"), installedApkSha256)
            val after = snapshot()
            report.put("expectedSnapshot", before.getJSONObject("snapshot")).put("observedSnapshot", after)
                .put("initialPreservation", before.optString("initialPreservation", "NOT_PROVEN"))
            assertEquals("Database, events, batches, settings, media or library changed", before.getJSONObject("snapshot").toString(), after.toString())
            val state = JSONObject(journal.readText())
            assertEquals("INSTALLED", state.getString("phase"))
            assertEquals(toCode, state.getJSONObject("release").getLong("versionCode"))
            assertFalse(payload.exists())
            assertFalse(state.optBoolean("autoInstall"))
            assertFalse(state.optBoolean("installationPending"))
            assertTrue(state.isNull("sessionId"))
            assertTrue(state.isNull("error"))
            val wav = File(before.getString("privateWav"))
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, Uri.parse(before.getString("uri")))
                assertEquals("1000", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION))
            } finally { retriever.release() }
            assertEquals(before.getString("wavSha256"), UpdateApkVerifier.sha256(wav))
            before.put("verified", true)
            baseline.writeText(before.toString(2))
            report.put("installedVersion", identity.versionCode).put("journal", state).put("snapshot", after)
                .put("fromCode", fromCode).put("fromName", fromName).put("toCode", toCode).put("toName", toName)
                .put("installedApkSha256", installedApkSha256)
                .put("preserved", true).put("mediaReadableAndDecodable", true).put("payloadRemoved", true)
        }
    }

    @Test fun cleanup() = runBlocking {
        evidence("cleanup") { report ->
            val before = JSONObject(baseline.readText())
            assertTrue("Cleanup requires a successful post-upgrade verification", before.getBoolean("verified"))
            assertEquals(before.getJSONObject("snapshot").toString(), snapshot().toString())
            val id = before.getString("id")
            assertTrue(id.matches(Regex("update-acceptance-[0-9a-f-]{36}")))
            val dao = app.repository.dao
            for (rowId in listOf(id, "$id-paused")) {
                val row = requireNotNull(dao.get(rowId))
                assertEquals("https://example.invalid/update-acceptance/$id", row.url)
                dao.removeHistory(rowId)
                app.storage.removeTemporary(rowId)
                assertNull(dao.get(rowId))
                assertTrue(dao.events(rowId).isEmpty())
            }
            app.database.playbackLibrary().fileDeleted(before.getString("uri"))
            app.database.playbackLibrary().delete(id)
            dao.removeEmptyBatch(id)
            assertNull(dao.batch(id))
            assertEquals(1, app.contentResolver.delete(Uri.parse(before.getString("uri")), null, null))
            assertTrue(File(before.getString("privateWav")).delete())
            val old = before.getJSONObject("originalSettings")
            settings.text("language", old.getString("language"))
            settings.text("theme", old.getString("theme"))
            settings.text("template", old.getString("template"))
            settings.integer("concurrency", old.getInt("concurrency"))
            settings.integer("preferred_height", old.getInt("preferredHeight"))
            settings.boolean("wifi", old.getBoolean("wifiOnly"))
            val restored = settings.current()
            assertEquals(old.getString("language"), restored.language)
            assertEquals(old.getString("theme"), restored.theme)
            assertEquals(old.getString("template"), restored.template)
            assertEquals(old.getInt("concurrency"), restored.concurrency)
            assertEquals(old.getInt("preferredHeight"), restored.preferredHeight)
            assertEquals(old.getBoolean("wifiOnly"), restored.wifiOnly)
            assertFalse(app.database.playbackLibrary().favorites().first().any { it.uri == before.getString("uri") })
            assertFalse(app.database.playbackLibrary().playlists().first().any { it.id == id })
            val incoming = File(external, "incoming.apk")
            assertTrue(!incoming.exists() || incoming.delete())
            assertTrue(baseline.delete())
            report.put("removedOnlyFixtureId", id).put("settingsRestored", true)
        }
    }

    private suspend fun seed(): JSONObject {
        val id = "update-acceptance-${UUID.randomUUID()}"
        val old = settings.current()
        val original = JSONObject().put("language", old.language).put("theme", old.theme).put("template", old.template)
            .put("concurrency", old.concurrency).put("preferredHeight", old.preferredHeight).put("wifiOnly", old.wifiOnly)
        val wav = File(app.filesDir, "update-acceptance/$id.wav").apply { parentFile!!.mkdirs() }
        val size = 16000
        wav.writeBytes(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array() + ByteArray(size))
        assertTrue(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$id.wav"); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MediaFlow/UpdateAcceptance")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { out -> wav.inputStream().use { it.copyTo(out) } }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val selection = Selection(Format("fixture", "wav", null, null, "none", "pcm_s16le", wav.length(), null, "https", "{}"), audioOnly = true, output = "wav", duration = 1.0)
        val row = DownloadEntity(id, "https://example.invalid/update-acceptance/$id", "ترقية محفوظة", "Upgrade acceptance", null, 1.0,
            selection.json(), "$id.wav", null, id, status = "COMPLETED", finishedAt = System.currentTimeMillis(),
            bytes = wav.length(), total = wav.length(), uri = uri.toString(), outputSize = wav.length(), batchId = id, sizeKind = "EXACT")
        val dao = app.repository.dao
        dao.insertBatch(PlaylistBatch(id, id, row.url, "Upgrade acceptance batch", "{}", state = "PAUSED"))
        dao.insert(row)
        dao.insert(row.copy(id = "$id-paused", fingerprint = "$id-paused", status = "PAUSED", uri = null,
            finishedAt = null, bytes = 17, outputSize = null, nextAttempt = Long.MAX_VALUE, playlistPosition = 1))
        File(app.storage.temporary("$id-paused"), "primary.wav.part").writeBytes(wav.readBytes().copyOf(17))
        dao.event(DownloadEvent(downloadId = id, state = "COMPLETED"))
        dao.event(DownloadEvent(downloadId = "$id-paused", state = "PAUSED"))
        val library = app.database.playbackLibrary()
        library.favorite(PlaybackFavorite(uri.toString(), row.title, false, "audio/wav", "QA", "Upgrade", null, 1000))
        library.create(PlaybackList(id, "Upgrade acceptance list"))
        library.insert(PlaybackListItem(id, uri.toString(), 0, row.title, false, "audio/wav", "QA", "Upgrade", null, 1000))
        settings.text("language", "ar"); settings.text("theme", "light")
        settings.text("template", "{title} - Upgrade acceptance")
        settings.integer("concurrency", 1); settings.integer("preferred_height", 480); settings.boolean("wifi", true)
        return JSONObject().put("id", id).put("uri", uri.toString()).put("privateWav", wav.path)
            .put("wavSha256", UpdateApkVerifier.sha256(wav)).put("originalSettings", original)
    }

    private suspend fun snapshot(): JSONObject {
        val result = JSONObject()
        for ((table, order) in listOf("downloads" to "id", "events" to "id", "playlist_batches" to "id",
            "playback_favorites" to "uri", "playback_lists" to "id", "playback_list_items" to "playlistId,uri")) {
            val rows = JSONArray()
            app.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $order").use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONArray().apply {
                    for (i in 0 until cursor.columnCount) put(if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i))
                })
            }
            result.put(table, rows)
        }
        result.put("settings", settings.current().toString())
        result.put("settingsSha256", UpdateApkVerifier.sha256(File(app.filesDir, "datastore/settings.preferences_pb")))
        result.put("persistedUriPermissions", JSONArray(app.contentResolver.persistedUriPermissions.map {
            "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
        }.sorted()))
        val partials = JSONArray()
        File(app.filesDir, "transfers").walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            partials.put(JSONObject().put("path", it.relativeTo(app.filesDir).invariantSeparatorsPath)
                .put("bytes", it.length()).put("sha256", UpdateApkVerifier.sha256(it)))
        }
        result.put("transferFiles", partials)
        val media = JSONArray()
        for (row in app.repository.dao.observe().first().filter { it.id.startsWith("update-acceptance-") && it.uri != null }.sortedBy { it.id }) {
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(app.contentResolver.openInputStream(Uri.parse(row.uri))).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            media.put(JSONObject().put("id", row.id).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }))
        }
        return result.put("media", media)
    }

    private fun body(file: File, offset: Long, interrupt: Boolean): ResponseBody = object : ResponseBody() {
        private var count = 0L
        private val source = object : ForwardingSource(file.inputStream().apply { channel.position(offset) }.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (interrupt && count >= 262144) throw IOException("Controlled interruption after 256 KiB")
                return super.read(sink, if (interrupt) minOf(byteCount, 262144 - count) else byteCount).also { if (it > 0) count += it }
            }
        }.buffer()
        override fun contentLength() = file.length() - offset
        override fun contentType(): MediaType? = null
        override fun source(): BufferedSource = source
    }
    private fun response(request: Request, code: Int, body: ResponseBody, vararg headers: Pair<String, String>): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("controlled").body(body)
            .apply { headers.forEach { (key, value) -> header(key, value) } }.build()
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
