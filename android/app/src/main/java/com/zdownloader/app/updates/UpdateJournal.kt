package com.zdownloader.app.updates

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal data class UpdateRecord(val state: UpdateState, val etag: String? = null, val permissionRequested: Boolean = false)

/** Only filesDir/app-updates is touched. AtomicFile preserves the last complete journal on death. */
internal class UpdateJournal(root: File, private val repository: String, private val channel: String, private val appId: String) {
    val directory = File(root, "app-updates")
    val apk = File(directory, "payload.apk")
    private val journal = AtomicFile(File(directory, "journal.json"))

    @Synchronized fun read(): UpdateRecord? = try {
        val bytes = journal.openRead().use { it.readBytesLimited() }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val release = json.optJSONObject("release")?.let { UpdateManifest.parse(it, repository, channel, appId) }
        val asset = release?.assets?.firstOrNull { it.abi == json.optString("abi") }
        UpdateRecord(UpdateState(
            phase = UpdatePhase.valueOf(json.getString("phase")), release = release, asset = asset,
            downloaded = if (apk.isFile) apk.length() else 0, total = asset?.bytes ?: 0,
            error = json.optString("error").takeIf { it.isNotEmpty() }?.let(UpdateError::valueOf),
            checkedAt = json.optLong("checkedAt"), autoInstall = json.optBoolean("autoInstall"),
            installationPending = json.optBoolean("installationPending"),
            sessionId = json.optInt("sessionId", -1).takeIf { it >= 0 }
        ), json.optString("etag").takeIf { it.isNotEmpty() }, json.optBoolean("permissionRequested"))
    } catch (_: Exception) { null }

    @Synchronized fun write(record: UpdateRecord) {
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException(UpdateError.STORAGE)
        val state = record.state
        val json = JSONObject().put("phase", state.phase.name).put("release", state.release?.toJson())
            .put("abi", state.asset?.abi).put("error", state.error?.name).put("checkedAt", state.checkedAt)
            .put("autoInstall", state.autoInstall).put("installationPending", state.installationPending)
            .put("sessionId", state.sessionId).put("etag", record.etag).put("permissionRequested", record.permissionRequested)
        val stream = journal.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch (e: Exception) { journal.failWrite(stream); throw e }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > UpdateManifest.MAX_JSON_BYTES) throw UpdateException(UpdateError.METADATA)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
