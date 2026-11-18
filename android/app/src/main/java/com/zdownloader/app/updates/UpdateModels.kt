package com.zdownloader.app.updates

import android.content.pm.PackageInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class UpdatePhase {
    IDLE, CHECKING, AVAILABLE, DOWNLOADING, PAUSED, VERIFYING, READY,
    WAITING_PERMISSION, INSTALLING, CURRENT, NO_RELEASE, INSTALLED, ERROR
}

enum class UpdateError {
    NETWORK, HTTP, RATE_LIMITED, METADATA, UNTRUSTED_URL, INCOMPATIBLE_SDK, INCOMPATIBLE_ABI,
    STORAGE, RANGE, SIZE, CHECKSUM, INVALID_APK, PACKAGE, VERSION, SIGNATURE,
    INSTALL_PERMISSION, INSTALL_CANCELLED, INSTALL_FAILED, INSTALL_CONFLICT, INSTALL_BLOCKED,
    INSTALL_INVALID, INSTALL_INCOMPATIBLE, SESSION_LOST, INTERNAL
}

internal object UpdateInstallErrors {
    fun fromStatus(status:Int):UpdateError=when(status) {
        PackageInstaller.STATUS_FAILURE_ABORTED->UpdateError.INSTALL_CANCELLED
        PackageInstaller.STATUS_FAILURE_CONFLICT->UpdateError.INSTALL_CONFLICT
        PackageInstaller.STATUS_FAILURE_BLOCKED->UpdateError.INSTALL_BLOCKED
        PackageInstaller.STATUS_FAILURE_INVALID->UpdateError.INSTALL_INVALID
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE->UpdateError.INSTALL_INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_STORAGE->UpdateError.STORAGE
        else->UpdateError.INSTALL_FAILED
    }
}

/** Error messages deliberately contain no URLs (GitHub redirect URLs carry credentials). */
class UpdateException(val code: UpdateError) : java.io.IOException(code.name)

data class UpdateAsset(val abi: String, val url: String, val sha256: String, val bytes: Long)
data class UpdateRelease(
    val appId: String, val channel: String, val versionCode: Long, val versionName: String,
    val minSdk: Int, val publishedAt: String?, val notes: Map<String, String>,
    val assets: List<UpdateAsset>, val releaseUrl: String?
) {
    fun selectAsset(deviceAbis: List<String>): UpdateAsset? =
        deviceAbis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi } }
            ?: assets.firstOrNull { it.abi == "universal" && deviceAbis.any { abi -> abi in setOf("arm64-v8a", "x86_64") } }

    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("appId", appId)
        .put("channel", channel).put("versionCode", versionCode).put("versionName", versionName)
        .put("minSdk", minSdk).put("publishedAt", publishedAt).put("notes", JSONObject(notes))
        .put("releaseUrl", releaseUrl).put("assets", JSONArray().also { array ->
            assets.forEach { array.put(JSONObject().put("abi", it.abi).put("url", it.url)
                .put("sha256", it.sha256).put("bytes", it.bytes)) }
        })
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateRelease? = null,
    val asset: UpdateAsset? = null,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: UpdateError? = null,
    val automaticPrompt: Boolean = false,
    val checkedAt: Long = 0,
    val autoInstall: Boolean = false,
    val installationPending: Boolean = false,
    val sessionId: Int? = null
) {
    val progress: Float get() = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

object UpdateManifest {
    const val MAX_JSON_BYTES = 1024 * 1024
    const val MAX_APK_BYTES = 1024L * 1024 * 1024

    fun parse(json: JSONObject, repository: String, channel: String, appId: String): UpdateRelease = metadata {
        require(integer(json, "schema", 1, 1) == 1L)
        require(json.getString("appId") == appId && json.getString("channel") == channel)
        require(channel in setOf("stable", "qa"))
        val version = integer(json, "versionCode", 1, Int.MAX_VALUE.toLong())
        val name = text(json, "versionName", 100).also { require(it.none(Char::isISOControl)) }
        val minSdk = integer(json, "minSdk", 1, 1000).toInt()
        val notes = json.getJSONObject("notes")
        val localized = mapOf("ar" to text(notes, "ar", 16000), "en" to text(notes, "en", 16000))
        val published = if (json.has("publishedAt")) json.getString("publishedAt").also(::timestamp) else null
        val rows = json.getJSONArray("assets")
        require(rows.length() in 1..3)
        var tag: String? = null
        val assets = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val abi = row.getString("abi").also { require(it in setOf("arm64-v8a", "x86_64", "universal")) }
            val url = row.getString("url")
            val ownedTag = UpdateUrlPolicy.ownedAsset(url, repository)
            require(tag == null || tag == ownedTag)
            tag = ownedTag
            val hash = row.getString("sha256").also { require(it.matches(Regex("[a-fA-F0-9]{64}"))) }
            UpdateAsset(abi, url, hash.lowercase(Locale.ROOT), integer(row, "bytes", 1, MAX_APK_BYTES))
        }
        require(assets.distinctBy { it.abi }.size == assets.size && assets.distinctBy { it.url }.size == assets.size)
        val releaseUrl = if (json.has("releaseUrl")) json.getString("releaseUrl").also {
            UpdateUrlPolicy.ownedRelease(it, repository, tag!!)
        } else null
        require(if (channel == "qa") tag!!.startsWith("qa-", true) else !tag!!.startsWith("qa-", true))
        UpdateRelease(appId, channel, version, name, minSdk, published, localized, assets, releaseUrl)
    }

    internal fun integer(json: JSONObject, key: String, min: Long, max: Long): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in min..max) }
    }

    private fun text(json: JSONObject, key: String, max: Int): String = (json.get(key) as? String
        ?: throw UpdateException(UpdateError.METADATA)).also {
        require(it.isNotBlank() && it.length <= max && it.none { c -> c.code in 0..8 || c.code in 11..12 || c.code in 14..31 || c.code == 127 })
    }

    internal fun timestamp(value: String): Long {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")))
        val normalized = if ('.' in value) value.substringBefore('.') + "." + value.substringAfter('.').removeSuffix("Z").padEnd(3, '0') + "Z" else value
        val format = SimpleDateFormat(if ('.' in value) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.isLenient = false
        format.timeZone = TimeZone.getTimeZone("UTC")
        val position = ParsePosition(0)
        return requireNotNull(format.parse(normalized, position)).also { require(position.index == normalized.length) }.time
    }

    internal inline fun <T> metadata(block: () -> T): T = try { block() }
        catch (e: UpdateException) { throw e }
        catch (e: java.io.IOException) { throw e }
        catch (_: Exception) { throw UpdateException(UpdateError.METADATA) }
}
