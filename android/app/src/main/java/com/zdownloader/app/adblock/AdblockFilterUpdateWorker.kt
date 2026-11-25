package com.zdownloader.app.adblock

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal class AdblockFilterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        BrowserAdblock.initialize(applicationContext)
        val easylist = fetchList(EASYLIST_URL)
        val easyprivacy = fetchList(EASYPRIVACY_URL)
        BrowserAdblock.replaceFromLists(easylist, easyprivacy)
        Result.success()
    } catch (error: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private fun fetchList(source: String): ByteArray {
        var current = source.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.isHttps && current.host == ALLOWED_HOST) {
                "Filter list update was redirected outside its pinned HTTPS origin."
            }
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    require(redirectCount < MAX_REDIRECTS) { "Filter list redirect limit exceeded." }
                    val location = response.header("Location")
                        ?: error("Filter list redirect did not include a Location.")
                    current = current.resolve(location)
                        ?: error("Filter list redirect URL was invalid.")
                    return@repeat
                }
                check(response.isSuccessful) { "Filter list server returned HTTP ${response.code}." }
                val body = response.body ?: error("Filter list response body was empty.")
                require(body.contentLength() in -1..MAX_LIST_BYTES.toLong()) {
                    "Filter list Content-Length exceeded the bounded download limit."
                }
                val output = ByteArrayOutputStream(
                    body.contentLength().takeIf { it > 0 }?.toInt() ?: INITIAL_CAPACITY
                )
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_LIST_BYTES) {
                            "Filter list response exceeded the bounded download limit."
                        }
                        output.write(buffer, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                require(bytes.size >= MIN_LIST_BYTES &&
                    bytes.copyOfRange(0, minOf(bytes.size, HEADER_BYTES))
                        .toString(Charsets.UTF_8).startsWith(FILTER_HEADER)
                ) { "Filter list did not have a recognized Adblock Plus header." }
                return bytes
            }
        }
        error("Filter list redirect loop did not produce a response.")
    }

    private companion object {
        const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        const val ALLOWED_HOST = "easylist.to"
        const val USER_AGENT = "MediaFlow-FilterListUpdater/1.0"
        const val FILTER_HEADER = "[Adblock Plus"
        const val MAX_LIST_BYTES = 8 * 1024 * 1024
        const val MIN_LIST_BYTES = 100 * 1024
        const val HEADER_BYTES = 512
        const val MAX_REDIRECTS = 3
        const val MAX_ATTEMPTS = 5
        const val INITIAL_CAPACITY = 256 * 1024
        const val BUFFER_BYTES = 32 * 1024
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
