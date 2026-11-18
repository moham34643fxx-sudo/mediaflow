package com.zdownloader.app.updates

import com.zdownloader.app.domain.UrlPolicy
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

object UpdateUrlPolicy {
    private val cdns = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    fun https(value: String): HttpUrl {
        if (value.length > 8192 || value.any { it.isISOControl() } || '#' in value || '\\' in value)
            throw UpdateException(UpdateError.UNTRUSTED_URL)
        val url = value.toHttpUrlOrNull() ?: throw UpdateException(UpdateError.UNTRUSTED_URL)
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw UpdateException(UpdateError.UNTRUSTED_URL)
        return url
    }

    fun ownedAsset(value: String, repository: String, tag: String? = null, name: String? = null): String {
        val url = https(value)
        val parts = url.pathSegments
        val repo = repository.split('/')
        if (repo.size != 2 || url.host != "github.com" || url.query != null || parts.size != 6 ||
            !parts[0].equals(repo[0], true) || !parts[1].equals(repo[1], true) ||
            parts[2] != "releases" || parts[3] != "download" ||
            !safeSegment(parts[4]) || !safeSegment(parts[5]) ||
            tag != null && tag != parts[4] || name != null && name != parts[5] ||
            name == null && !parts[5].endsWith(".apk", true)) throw UpdateException(UpdateError.UNTRUSTED_URL)
        return parts[4]
    }

    fun ownedRelease(value: String, repository: String, tag: String) {
        val url = https(value)
        val expected = repository.split('/') + listOf("releases", "tag", tag)
        if (url.host != "github.com" || url.query != null || url.pathSegments.size != expected.size ||
            url.pathSegments.indices.any { i -> if (i < 2) !url.pathSegments[i].equals(expected[i], true) else url.pathSegments[i] != expected[i] })
            throw UpdateException(UpdateError.UNTRUSTED_URL)
    }

    fun assetApi(value: String, repository: String): Boolean {
        val url = https(value)
        val prefix = listOf("repos") + repository.split('/') + listOf("releases", "assets")
        return url.host == "api.github.com" && url.query == null && url.pathSegments.size == 6 &&
            url.pathSegments.take(5).map { it.lowercase() } == prefix.map { it.lowercase() } &&
            url.pathSegments.last().matches(Regex("[1-9][0-9]*"))
    }

    internal fun allowedHop(url: HttpUrl, initial: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (url == initial || url.host in cdns)

    private fun safeSegment(value: String) = value.isNotBlank() && value.length <= 255 &&
        value != "." && value != ".." && value.none { it.isISOControl() || it in "/\\?#" }

    /** New dispatcher/pool; DNS results used by the connection are checked, including every redirect. */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
        .connectionPool(ConnectionPool(2, 2, TimeUnit.MINUTES)).proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !UrlPolicy.publicAddress(it) }) throw UnknownHostException("Update host rejected")
            }
        })
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .build()
}

/** Factory injection is intentionally separate from app-wide download clients. */
class UpdateHttp(private val factory: Call.Factory = UpdateUrlPolicy.client()) {
    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false
    fun begin() { cancelled = false }
    fun cancel() { cancelled = true; call?.cancel() }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMillis: Long = 0): Response {
        val initial = UpdateUrlPolicy.https(url)
        val deadline = if (timeoutMillis > 0) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis) else null
        var next = initial
        repeat(6) { hop ->
            if (cancelled) throw java.io.InterruptedIOException("Update paused")
            if (!UpdateUrlPolicy.allowedHop(next, initial)) throw UpdateException(UpdateError.UNTRUSTED_URL)
            val request = Request.Builder().url(next).header("Accept-Encoding", "identity")
                .header("User-Agent", "MediaFlow-Updater/1").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val current = factory.newCall(request)
            if (deadline != null) current.timeout().deadlineNanoTime(deadline)
            call = current
            if (cancelled) current.cancel()
            val response = current.execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (hop == 5 || location == null) throw UpdateException(UpdateError.HTTP)
            next = next.resolve(location) ?: throw UpdateException(UpdateError.UNTRUSTED_URL)
        }
        throw UpdateException(UpdateError.HTTP)
    }

    fun json(url: String, accept: String = "application/json"): Any? = get(url, mapOf("Accept" to accept), timeoutMillis = 30000).use { response ->
        if (response.code == 404) {
            val body = runCatching { JSONObject(readJson(response)) }.getOrNull()
            if (body?.optString("error") == "no_published_release") return null
        }
        requireSuccess(response)
        UpdateManifest.metadata {
            val source = readJson(response)
            val tokener = JSONTokener(source)
            val value = tokener.nextValue()
            require(tokener.nextClean() == '\u0000' && (value is JSONObject || value is JSONArray))
            value
        }
    }

    private fun readJson(response: Response): String {
        if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw UpdateException(UpdateError.METADATA)
        val body = response.body ?: throw UpdateException(UpdateError.METADATA)
        if (body.contentLength() > UpdateManifest.MAX_JSON_BYTES) throw UpdateException(UpdateError.METADATA)
        val output = ByteArrayOutputStream()
        body.byteStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > UpdateManifest.MAX_JSON_BYTES) throw UpdateException(UpdateError.METADATA)
                output.write(buffer, 0, count)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw UpdateException(UpdateError.METADATA) }
    }

    companion object {
        fun requireSuccess(response: Response) {
            if (response.code == 403 || response.code == 429) throw UpdateException(UpdateError.RATE_LIMITED)
            if (!response.isSuccessful) throw UpdateException(UpdateError.HTTP)
        }
    }
}

class UpdateDiscovery(private val http: UpdateHttp, private val repository: String, private val appId: String) {
    fun discover(feed: String, channel: String): UpdateRelease? {
        val url = UpdateUrlPolicy.https(feed)
        val github = url.host == "api.github.com"
        if (github && !url.encodedPath.equals("/repos/$repository/releases", true)) throw UpdateException(UpdateError.UNTRUSTED_URL)
        val endpoint = if (github) feed else url.newBuilder().setQueryParameter("channel", channel).build().toString()
        val json = http.json(endpoint) ?: return null
        if (json is JSONObject) return UpdateManifest.parse(json, repository, channel, appId)
        if (!github || json !is JSONArray) throw UpdateException(UpdateError.METADATA)
        val all = JSONArray()
        var page: JSONArray = json
        for (index in 1..5) {
            for (i in 0 until page.length()) all.put(page.get(i))
            val pageSize = url.queryParameter("per_page")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            if (page.length() < pageSize) break
            if (index == 5) throw UpdateException(UpdateError.METADATA)
            page = http.json(url.newBuilder().setQueryParameter("page", (index + 1).toString()).build().toString()) as? JSONArray
                ?: throw UpdateException(UpdateError.METADATA)
        }
        return UpdateManifest.metadata { parseSelectedRelease(all, channel) }
    }

    private fun parseSelectedRelease(all: JSONArray, channel: String): UpdateRelease? {
        val release = selectRelease(all, channel) ?: return null
        val tag = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        val manifests = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == "mediaflow-update.json" }
        if (manifests.size != 1) throw UpdateException(UpdateError.METADATA)
        val manifest = manifests.single()
        if (manifest.has("state") && manifest.getString("state") != "uploaded") throw UpdateException(UpdateError.METADATA)
        val browser = manifest.optString("browser_download_url")
        val contentUrl = if (browser.isNotBlank()) {
            UpdateUrlPolicy.ownedAsset(browser, repository, tag, "mediaflow-update.json")
            browser
        } else manifest.getString("url").also {
            if (!UpdateUrlPolicy.assetApi(it, repository)) throw UpdateException(UpdateError.UNTRUSTED_URL)
        }
        if (manifest.has("size")) UpdateManifest.integer(manifest, "size", 1, UpdateManifest.MAX_JSON_BYTES.toLong())
        val parsed = try { http.json(contentUrl, "application/octet-stream") }
        catch (e: UpdateException) {
            val api = manifest.optString("url")
            if (e.code != UpdateError.HTTP || contentUrl == api || !UpdateUrlPolicy.assetApi(api, repository)) throw e
            http.json(api, "application/octet-stream")
        } as? JSONObject ?: throw UpdateException(UpdateError.METADATA)
        val result = UpdateManifest.parse(parsed, repository, channel, appId)
        result.assets.forEach { asset ->
            UpdateUrlPolicy.ownedAsset(asset.url, repository, tag)
            val owned = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("browser_download_url") == asset.url }
            if (owned.size != 1 || UpdateManifest.integer(owned.single(), "size", 1, UpdateManifest.MAX_APK_BYTES) != asset.bytes)
                throw UpdateException(UpdateError.METADATA)
            if (owned.single().has("state") && owned.single().getString("state") != "uploaded") throw UpdateException(UpdateError.METADATA)
            val digest = owned.single().opt("digest")
            if (digest != null && digest != JSONObject.NULL &&
                (digest !is String || !digest.matches(Regex("sha256:[a-fA-F0-9]{64}")) ||
                    !digest.substringAfter(':').equals(asset.sha256, true))) throw UpdateException(UpdateError.METADATA)
        }
        return result
    }

    companion object {
        fun selectRelease(rows: JSONArray, channel: String): JSONObject? = UpdateManifest.metadata {
            require(channel == "qa" || channel == "stable")
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter { release ->
                val qaTag = release.getString("tag_name").startsWith("qa-", true)
                !release.getBoolean("draft") && !release.isNull("published_at") &&
                    (if (channel == "qa") release.getBoolean("prerelease") && qaTag else !release.getBoolean("prerelease") && !qaTag) &&
                    release.getJSONArray("assets").let { assets -> (0 until assets.length()).any { assets.getJSONObject(it).optString("name") == "mediaflow-update.json" } }
            }.maxWithOrNull(compareBy<JSONObject> { UpdateManifest.timestamp(it.getString("published_at")) }.thenBy { it.optLong("id") })
        }
    }
}
