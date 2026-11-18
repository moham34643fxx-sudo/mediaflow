package com.zdownloader.app.updates

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateDiscoveryTest {
    private val repository = "owner/repo"
    private val feed = "https://api.github.com/repos/$repository/releases?per_page=20"
    private val apkUrl = "https://github.com/$repository/releases/download/qa-v2/app.apk"
    private val manifestUrl = "https://github.com/$repository/releases/download/qa-v2/mediaflow-update.json"
    private val apiUrl = "https://api.github.com/repos/$repository/releases/assets/123"
    private fun manifest() = JSONObject().put("schema", 1).put("appId", "com.zdownloader.app").put("channel", "qa")
        .put("versionCode", 51).put("versionName", "0.9.18").put("minSdk", 24)
        .put("notes", JSONObject().put("ar", "تحسينات").put("en", "Improvements"))
        .put("assets", JSONArray().put(JSONObject().put("abi", "arm64-v8a").put("url", apkUrl)
            .put("sha256", "ab".repeat(32)).put("bytes", 100)))

    private fun release() = JSONObject().put("id", 1).put("tag_name", "qa-v2").put("draft", false).put("prerelease", true)
        .put("published_at", "2026-10-27T12:00:00Z").put("assets", JSONArray()
            .put(JSONObject().put("name", "mediaflow-update.json").put("browser_download_url", manifestUrl).put("url", apiUrl).put("size", 1000))
            .put(JSONObject().put("name", "app.apk").put("browser_download_url", apkUrl).put("size", 100)))

    @Test fun githubProviderFetchesOnlyReleaseOwnedManifestAndValidatesApkOwnershipAndSize() {
        val requests = mutableListOf<Request>()
        val http = UpdateHttp(factory { request ->
            requests += request
            response(request, 200, if (request.url.toString() == feed) JSONArray().put(release()).toString() else manifest().toString())
        })
        val result = UpdateDiscovery(http, repository, "com.zdownloader.app").discover(feed, "qa")!!
        assertEquals(51L, result.versionCode)
        assertEquals(listOf(feed, manifestUrl), requests.map { it.url.toString() })
        assertEquals("application/octet-stream", requests.last().header("Accept"))
        assertFalse(requests.any { it.url.toString() == apkUrl })
    }

    @Test fun restAssetContentFallbackIsBoundToSelectedReleaseAndUsesOctetStream() {
        val requests = mutableListOf<Request>()
        val http = UpdateHttp(factory { request ->
            requests += request
            when (request.url.toString()) {
                feed -> response(request, 200, JSONArray().put(release()).toString())
                manifestUrl -> response(request, 404, "{}")
                apiUrl -> response(request, 200, manifest().toString())
                else -> throw AssertionError("Unexpected request")
            }
        })
        assertNotNull(UpdateDiscovery(http, repository, "com.zdownloader.app").discover(feed, "qa"))
        assertEquals("application/octet-stream", requests.last().header("Accept"))
        assertEquals(apiUrl, requests.last().url.toString())
    }

    @Test fun emptyReleasesAndVercelNoReleaseAreNotCurrentOrErrors() {
        val empty = UpdateHttp(factory { response(it, 200, "[]") })
        assertNull(UpdateDiscovery(empty, repository, "com.zdownloader.app").discover(feed, "qa"))
        val missing = UpdateHttp(factory { response(it, 404, "{\"error\":\"no_published_release\"}") })
        assertNull(UpdateDiscovery(missing, repository, "com.zdownloader.app").discover("https://feed.example/update", "qa"))
        val limited = UpdateHttp(factory { response(it, 403, "{}") })
        rejects(UpdateError.RATE_LIMITED) { UpdateDiscovery(limited, repository, "com.zdownloader.app").discover(feed, "qa") }
    }

    @Test fun directManifestUsesRequestedChannelButCannotHostApkItself() {
        var requested: Request? = null
        val direct = UpdateHttp(factory { requested = it; response(it, 200, manifest().toString()) })
        assertNotNull(UpdateDiscovery(direct, repository, "com.zdownloader.app").discover("https://feed.example/update", "qa"))
        assertEquals("qa", requested!!.url.queryParameter("channel"))
        val bad = manifest().also { it.getJSONArray("assets").getJSONObject(0).put("url", "https://feed.example/app.apk") }
        val untrusted = UpdateHttp(factory { response(it, 200, bad.toString()) })
        rejects(UpdateError.UNTRUSTED_URL) { UpdateDiscovery(untrusted, repository, "com.zdownloader.app").discover("https://feed.example/update", "qa") }
    }

    @Test fun corruptNewestReleaseOrWrongAssetSizeCannotSilentlyFallBack() {
        val wrongSize = release().also { it.getJSONArray("assets").getJSONObject(1).put("size", 101) }
        val http = UpdateHttp(factory { request -> response(request, 200,
            if (request.url.toString() == feed) JSONArray().put(wrongSize).toString() else manifest().toString()) })
        rejects(UpdateError.METADATA) { UpdateDiscovery(http, repository, "com.zdownloader.app").discover(feed, "qa") }
    }

    @Test fun githubAssetDigestMustMatchTheManifestWhenPublished() {
        for (digest in listOf<Any>("sha256:" + "cd".repeat(32), "", false, 0)) {
            val source = release().also { it.getJSONArray("assets").getJSONObject(1).put("digest", digest) }
            val http = UpdateHttp(factory { request -> response(request, 200,
                if (request.url.toString() == feed) JSONArray().put(source).toString() else manifest().toString()) })
            rejects(UpdateError.METADATA) { UpdateDiscovery(http, repository, "com.zdownloader.app").discover(feed, "qa") }
        }
        val source = release().also { it.getJSONArray("assets").getJSONObject(1).put("digest", "sha256:" + "ab".repeat(32)) }
        val http = UpdateHttp(factory { request -> response(request, 200,
            if (request.url.toString() == feed) JSONArray().put(source).toString() else manifest().toString()) })
        assertNotNull(UpdateDiscovery(http, repository, "com.zdownloader.app").discover(feed, "qa"))
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()

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

    private fun rejects(error: UpdateError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: UpdateException) { assertEquals(error, e.code) }
    }
}
