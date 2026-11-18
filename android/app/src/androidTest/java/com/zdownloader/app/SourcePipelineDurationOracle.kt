package com.zdownloader.app

import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.Transfer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DurationOracleResult(
    val expectedDuration: Double,
    val kind: String
)

object SourcePipelineDurationOracle {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val NETWORK_TIMEOUT_SECONDS = 10L

    /**
     * Diagnostic line-based parser for selected HLS media playlists.
     * Enforces:
     * - First significant line is exact #EXTM3U.
     * - Rejection of master playlists (#EXT-X-STREAM-INF, #EXT-X-I-FRAME-STREAM-INF).
     * - Exact #EXT-X-ENDLIST requirement (rejects live / incomplete).
     * - Pairwise matching: each positive finite #EXTINF followed by exactly one segment URI.
     * - Rejection of malformed #EXTINF tags (e.g. #EXTINFbogus), duplicate pending #EXTINF, dangling durations, URIs without durations, media after #EXT-X-ENDLIST.
     * - At least 1 complete segment with finite positive sum.
     * Never interpolates raw playlist content or URI tokens into exceptions.
     */
    fun parseMediaPlaylist(playlistText: String): Double {
        val lines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
        if (!lines.hasNext()) throw IllegalArgumentException("Empty playlist")

        var lineIndex = 1
        val firstLine = lines.next()
        val cleanFirstLine = firstLine.trimStart('\uFEFF')
        if (cleanFirstLine != "#EXTM3U") {
            throw IllegalArgumentException("First line must be exact #EXTM3U")
        }

        var pendingDuration: Double? = null
        var segmentCount = 0
        var totalDuration = 0.0
        var sawEndList = false

        while (lines.hasNext()) {
            lineIndex++
            val line = lines.next()

            if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                throw IllegalArgumentException("Master playlist tag detected at line $lineIndex")
            }

            if (sawEndList) {
                if (!line.startsWith("#") || line.startsWith("#EXTINF")) {
                    throw IllegalArgumentException("Content found after #EXT-X-ENDLIST at line $lineIndex")
                }
                continue
            }

            if (line == "#EXT-X-ENDLIST") {
                if (pendingDuration != null) {
                    throw IllegalArgumentException("Dangling #EXTINF before #EXT-X-ENDLIST at line $lineIndex")
                }
                sawEndList = true
                continue
            }

            if (line.startsWith("#EXTINF")) {
                if (!line.startsWith("#EXTINF:")) {
                    throw IllegalArgumentException("Malformed EXTINF tag at line $lineIndex")
                }
                if (pendingDuration != null) {
                    throw IllegalArgumentException("Duplicate pending #EXTINF at line $lineIndex")
                }
                val rawValue = line.substring("#EXTINF:".length).substringBefore(',').trim()
                val parsed = rawValue.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Malformed EXTINF duration value at line $lineIndex")
                if (!parsed.isFinite() || parsed <= 0.0) {
                    throw IllegalArgumentException("Non-finite or non-positive EXTINF duration at line $lineIndex")
                }
                pendingDuration = parsed
            } else if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-ENDLIST")) {
                    throw IllegalArgumentException("Malformed #EXT-X-ENDLIST line at line $lineIndex")
                }
            } else {
                val duration = pendingDuration
                    ?: throw IllegalArgumentException("Segment URI found without preceding #EXTINF at line $lineIndex")
                totalDuration += duration
                segmentCount++
                pendingDuration = null
            }
        }

        if (!sawEndList) {
            throw IllegalArgumentException("Live or incomplete playlist: missing #EXT-X-ENDLIST")
        }
        if (pendingDuration != null) {
            throw IllegalArgumentException("Dangling #EXTINF at end of playlist")
        }
        if (segmentCount == 0) {
            throw IllegalArgumentException("Empty playlist: at least 1 complete segment required")
        }
        if (!totalDuration.isFinite() || totalDuration <= 0.0) {
            throw IllegalArgumentException("Invalid total duration")
        }

        return totalDuration
    }

    /**
     * Resolves the duration oracle.
     * Non-HLS streams return catalog metadata duration without payload inspection.
     * HLS streams fetch ONLY the selected primary media URL through Transfer.networkClient (<=64KiB, 10s).
     */
    fun resolve(
        media: Media,
        selection: Selection,
        client: OkHttpClient = Transfer.networkClient
    ): DurationOracleResult? {
        val primary = selection.primary
        val protocol = primary.protocol.lowercase(Locale.ROOT)
        val isHls = protocol == "m3u8" || protocol == "m3u8_native" || protocol.contains("m3u8")

        if (!isHls) {
            return media.duration?.let { DurationOracleResult(it, "CATALOG_METADATA") }
        }

        val payload = JSONObject(primary.payload)
        val rawUrl = payload.optString("url")
        if (rawUrl.isBlank()) {
            throw IllegalStateException("Selected HLS primary format ${primary.id} has no valid URL")
        }

        val normalizedUrl = UrlPolicy.normalize(rawUrl)
        val reqBuilder = Request.Builder().url(normalizedUrl)
        payload.optJSONObject("http_headers")?.let { headers ->
            headers.keys().forEach { name ->
                val lower = name.lowercase(Locale.ROOT)
                if (lower in Transfer.DIRECT_HEADERS && lower !in setOf("range", "if-range", "authorization", "cookie")) {
                    reqBuilder.header(name, headers.getString(name))
                }
            }
        }
        reqBuilder.header("Accept-Encoding", "identity")

        val boundedClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val response = boundedClient.newCall(reqBuilder.build()).execute()
        val playlistText = response.use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to fetch selected HLS leaf manifest (${resp.code}) for format ${primary.id}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body for selected HLS leaf manifest of format ${primary.id}")
            val stream = body.byteStream()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException("Selected HLS playlist exceeded 64 KiB limit for format ${primary.id}")
                }
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        }

        val duration = parseMediaPlaylist(playlistText)
        return DurationOracleResult(duration, "HLS_FETCHED_LEAF")
    }
}
