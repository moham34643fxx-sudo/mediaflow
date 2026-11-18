package com.zdownloader.app

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reproduces the reported X link and verifies one public X video through final playback. */
class XReportedPostReproductionTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ZApplication

    @Test fun reportedPrivatePostAndPublicVideoRegression() = runBlocking {
        assertEquals("Run only in the isolated candidate", "com.zdownloader.app.candidate0965", app.packageName)

        val reported = listOf(
            "https://x.com/teresablackus/status/2105845355267141963/video/1",
            "https://x.com/teresablackus/status/2105845355267141963",
        )
        val deletedPost = "https://twitter.com/jaydingeer/status/700207533655363584"
        val publicCanonical = "https://x.com/TopHeroes_/status/2001950365332455490"
        val publicRegression = "https://x.com/TopHeroes_/status/2001950365332455490/video/1"
        val report = JSONObject()
            .put("device", "HONOR 600 AFAM026331002472")
            .put("candidate_package", app.packageName)
            .put("reported_post_id", "2105845355267141963")
            .put("metadata_only_for_reported_post", true)
            .put("download_attempted_for_reported_post", false)
            .put("reported_variants", JSONArray())
            .put("public_regression_post_id", "2001950365332455490")

        val urlRows = JSONArray()
        val testedUrls = reported + listOf(deletedPost, publicCanonical, publicRegression)
        for (url in testedUrls) {
            val parsed = UrlPolicy.normalize(url)
            val shared = QuickShareFormats.sharedUrl(listOf(url))
            val row = JSONObject()
                .put("url", url)
                .put("accepted_by_url_policy", true)
                .put("accepted_by_quick_share", shared == url)
                .put("normalized_url", parsed)
            urlRows.put(row)
        }
        report.put("url_recognition", urlRows)

        val pythonRows = JSONArray()
        val namespace = app.extractor.python().getModule("types")
            .callAttr("ModuleType", "x_reported_url_match").get("__dict__")!!
        val urlsEncoded = Base64.encodeToString(
            JSONArray(reported + publicRegression).toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        app.extractor.python().builtins.callAttr(
            "exec",
            """
import json, re
import base64
from yt_dlp.extractor.twitter import TwitterIE
urls = json.loads(base64.b64decode('$urlsEncoded').decode('utf-8'))
rows = []
for url in urls:
    match = re.match(TwitterIE._VALID_URL, url)
    rows.append({
        'suitable': TwitterIE.suitable(url),
        'matched_post_id': match.group('id') if match else None,
        'selected_media_index': match.group('index') if match else None,
    })
report = json.dumps(rows)
            """.trimIndent(),
            namespace,
        )
        val matches = JSONArray(namespace.callAttr("__getitem__", "report").toString())
        for (index in 0 until matches.length()) pythonRows.put(matches.getJSONObject(index))
        report.put("yt_dlp_url_matching", pythonRows)

        val idsEncoded = Base64.encodeToString(
            JSONArray(listOf("2105845355267141963", "2001950365332455490", "700207533655363584"))
                .toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val endpointNamespace = app.extractor.python().getModule("types")
            .callAttr("ModuleType", "x_reported_endpoint_probe").get("__dict__")!!
        app.extractor.python().builtins.callAttr(
            "exec",
            """
import base64, json
from analysis_engine import AnalysisYoutubeDL
from yt_dlp.extractor.twitter import TwitterIE
ids = json.loads(base64.b64decode('$idsEncoded').decode('utf-8'))
rows = []
class Probe(TwitterIE):
    def _graphql_to_legacy(self, data, twid):
        result = data.get('tweetResult', {}).get('result', {}) if isinstance(data, dict) else {}
        tombstone = result.get('tombstone') if isinstance(result, dict) else None
        text = ''
        if isinstance(tombstone, dict):
            text = str((tombstone.get('text') or {}).get('text') or '').lower()
        rows.append({
            'post_id': twid,
            'typename': result.get('__typename') if isinstance(result, dict) else None,
            'tombstone_present': bool(tombstone),
            'card_name': ((result.get('card') or {}).get('legacy') or {}).get('name')
                if isinstance(result, dict) else None,
            'tombstone_category': ('protected' if 'protect' in text or 'private' in text else
                                   'deleted_or_unavailable' if 'unavailable' in text or 'deleted' in text else
                                   'authentication_required' if 'sign in' in text or 'log in' in text else
                                   'other' if text else None),
            'result_has_legacy': isinstance(result.get('legacy'), dict) if isinstance(result, dict) else False,
            'legacy_media_count': len((result.get('legacy') or {}).get('extended_entities', {}).get('media', []))
                if isinstance(result, dict) else 0,
        })
        return super()._graphql_to_legacy(data, twid)
for post_id in ids:
    with AnalysisYoutubeDL({'quiet': True, 'cachedir': False, 'socket_timeout': 12,
                            'retries': 0, 'extractor_retries': 0,
                            'extractor_args': {'twitter': {'api': ['graphql']}}}) as engine:
        try:
            status = Probe(engine)._extract_status(post_id)
            rows[-1]['resolved_media_count'] = len(status.get('extended_entities', {}).get('media', []))
        except Exception as error:
            if not rows or rows[-1].get('post_id') != post_id:
                rows.append({'post_id': post_id, 'typename': 'no_graphql_result'})
            rows[-1]['extractor_error_class'] = type(error).__name__
report = json.dumps(rows)
            """.trimIndent(),
            endpointNamespace,
        )
        report.put("graphql_probe", JSONArray(endpointNamespace.callAttr("__getitem__", "report").toString()))

        val analyzed = mutableMapOf<String, Media>()
        val extractionRows = JSONArray()
        for (url in testedUrls) {
            val row = JSONObject().put("post_id", Regex("/status/(\\d+)").find(url)?.groupValues?.get(1))
            val started = SystemClock.elapsedRealtime()
            try {
                val requestId = "x-repro-${UUID.randomUUID()}"
                val result = withTimeout(70_000) {
                    JSONObject(app.extractor.python().getModule("zextractor")
                        .callAttr("analyze", url, requestId).toString())
                }
                if (result.has("error")) {
                    val message = result.optString("message").lowercase()
                    row.put("outcome", "extraction_failed")
                        .put("error_code", result.optString("error"))
                    .put("stage", result.optString("stage"))
                    .put("diagnostic", result.optString("diagnostic"))
                    .put("timings_ms", result.optJSONObject("timings_ms") ?: JSONObject())
                    .put("error_category", when {
                            "protect" in message || "private" in message -> "protected_or_private"
                            "sign in" in message || "login" in message || "authentication" in message -> "authentication_required"
                            "deleted" in message || "not found" in message || "unavailable" in message -> "deleted_or_unavailable"
                            else -> "other"
                        })
                    row.put("timings_ms", result.optJSONObject("timings_ms") ?: JSONObject())
                    extractionRows.put(row.put("elapsed_ms", SystemClock.elapsedRealtime() - started))
                    continue
                }
                val media = Media.parse(result, url)
                analyzed[url] = media
                row.put("timings_ms", result.optJSONObject("timings_ms") ?: JSONObject())
                val groups = QuickShareFormats.usable(media).qualityGroups()
                val formatRows = JSONArray()
                for (format in media.formats) {
                    formatRows.put(
                        JSONObject()
                            .put("id", format.id)
                            .put("ext", format.ext)
                            .put("width", format.width)
                            .put("height", format.height)
                            .put("video_codec", format.videoCodec)
                            .put("audio_codec", format.audioCodec)
                            .put("protocol", format.protocol)
                            .put("bitrate_kbps", format.bitrate)
                            .put("video", format.video)
                            .put("audio", format.audio)
                            .put("eligible", FormatAvailability.eligible(format))
                    )
                }
                val groupRows = JSONArray()
                for (group in groups) {
                    groupRows.put(
                        JSONObject()
                            .put("quality_edge", group.resolution)
                            .put("verified_format_ids", JSONArray(group.variantChoices.map { it.id }))
                    )
                }
                row.put("outcome", "formats_found")
                    .put("title_present", media.title.isNotBlank())
                    .put("format_count", media.formats.size)
                    .put("video_format_count", media.videoOptions().size)
                    .put("audio_format_count", media.audioOptions().size)
                    .put("quick_share_quality_groups", groupRows)
                    .put("formats", formatRows)
            } catch (error: TimeoutCancellationException) {
                row.put("outcome", "extraction_failed").put("error_code", "EXTRACTION_TIMEOUT")
            } catch (error: Exception) {
                row.put("outcome", "extraction_failed")
                    .put("error_code", (error as? MediaException)?.code?.name ?: error.javaClass.simpleName)
            }
            row.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            extractionRows.put(row)
        }
        report.put("extraction", extractionRows)
        val evidenceDirectory = File(app.getExternalFilesDir(null), "x-reported-post-audit")
            .apply { check(isDirectory || mkdirs()) }
        val evidenceFile = File(evidenceDirectory, "candidate-reproduction.json")
        evidenceFile.writeText(report.toString(2))

        val publicMedia = analyzed[publicRegression]
        if (publicMedia != null) {
            val usable = QuickShareFormats.usable(publicMedia)
            val downloadable = usable.videoOptions()
                .filter { it.ext == "mp4" && it.protocol in setOf("http", "https") }
                .minWithOrNull(compareBy<Format> { it.height ?: Int.MAX_VALUE }.thenBy { it.bitrate ?: Double.MAX_VALUE })
            if (downloadable != null) {
                val selection = Selection.video(usable, downloadable)
                val id = "x-public-video-${UUID.randomUUID()}"
                val directory = app.storage.temporary(id)
                try {
                    val videoSource = File(directory, "video.${selection.primary.ext}")
                    val audioSource = selection.secondary?.let { File(directory, "audio.${it.ext}") }
                    withTimeout(120_000) {
                        app.transfer.stream(selection.primary, videoSource, id, TransferControl()) { _, _ -> }
                        if (selection.secondary != null && audioSource != null) {
                            app.transfer.stream(selection.secondary, audioSource, id, TransferControl()) { _, _ -> }
                        }
                    }
                    val output = File(directory, "verified.${selection.output}")
                    Processor().process(videoSource, audioSource, output, selection, TransferControl(), usable.duration)

                    val information = FFprobeKit.getMediaInformation(output.path).mediaInformation
                    val streams = information.streams
                    val retriever = MediaMetadataRetriever()
                    val frameAvailable = try {
                        retriever.setDataSource(output.path)
                        retriever.getFrameAtTime(0)?.also { it.recycle() } != null
                    } finally {
                        retriever.release()
                    }

                    val playerReady = CountDownLatch(1)
                    val playerError = arrayOfNulls<String>(1)
                    lateinit var player: ExoPlayer
                    instrument.runOnMainSync {
                        player = ExoPlayer.Builder(app).build()
                        player.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) playerReady.countDown()
                            }
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                playerError[0] = error.errorCodeName
                                playerReady.countDown()
                            }
                        })
                        player.setMediaItem(MediaItem.fromUri(output.toURI().toString()))
                        player.prepare()
                    }
                    try {
                        assertTrue("Candidate player did not prepare the downloaded X video", playerReady.await(30, TimeUnit.SECONDS))
                        assertNull("Candidate player rejected the downloaded X video", playerError[0])
                        instrument.runOnMainSync { player.play() }
                        SystemClock.sleep(1500)
                        val playbackPosition = longArrayOf(0)
                        val playbackState = intArrayOf(Player.STATE_IDLE)
                        instrument.runOnMainSync {
                            playbackPosition[0] = player.currentPosition
                            playbackState[0] = player.playbackState
                        }
                        assertTrue("Playback did not advance", playbackPosition[0] > 0 || playbackState[0] == Player.STATE_ENDED)
                        report.put(
                            "public_download_and_playback",
                            JSONObject()
                                .put("format_id", selection.primary.id)
                                .put("height", selection.primary.height)
                                .put("container", selection.output)
                                .put("paired_audio", selection.secondary != null)
                                .put("bytes", output.length())
                                .put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") { "%02x".format(it) })
                                .put("ffprobe_video_stream", streams.any { it.type == "video" })
                                .put("ffprobe_audio_stream", streams.any { it.type == "audio" })
                                .put("first_frame_decoded", frameAvailable)
                                .put("candidate_player_ready", true)
                                .put("playback_position_ms", playbackPosition[0])
                        )
                        assertTrue(output.isFile && output.length() > 0)
                        assertTrue("No video stream in downloaded output", streams.any { it.type == "video" })
                        assertTrue("Downloaded output did not decode a frame", frameAvailable)
                    } finally {
                        instrument.runOnMainSync { player.release() }
                    }
                } finally {
                    app.storage.removeTemporary(id)
                }
            } else {
                report.put("public_download_and_playback", JSONObject().put("outcome", "no_verified_direct_mp4"))
            }
        } else {
            report.put("public_download_and_playback", JSONObject().put("outcome", "public_extraction_failed"))
        }

        evidenceFile.writeText(report.toString(2))

        assertTrue("The supplied URL must be accepted by URL validation", urlRows.getJSONObject(0).getBoolean("accepted_by_url_policy"))
        assertTrue("The supplied URL must be recognized by yt-dlp", pythonRows.getJSONObject(0).getBoolean("suitable"))
        assertEquals("2105845355267141963", pythonRows.getJSONObject(0).getString("matched_post_id"))
        assertEquals("1", pythonRows.getJSONObject(0).getString("selected_media_index"))
        assertEquals("Exact and canonical URLs identify the same post", "2105845355267141963",
            pythonRows.getJSONObject(1).getString("matched_post_id"))
        assertEquals("The protected post should not be treated as publicly downloadable",
            "PRIVATE_CONTENT", extractionRows.getJSONObject(0).optString("error_code"))
        assertEquals("The canonical protected URL should report the same access restriction",
            "PRIVATE_CONTENT", extractionRows.getJSONObject(1).optString("error_code"))
        assertEquals("Known deleted posts should report unavailable content",
            "VIDEO_NOT_FOUND", extractionRows.getJSONObject(2).optString("error_code"))
        assertNotNull("The canonical public X regression video must extract", analyzed[publicCanonical])
        assertNotNull("The known public X regression video must extract", publicMedia)
        val download = report.getJSONObject("public_download_and_playback")
        assertTrue("The public regression format must download and play", download.optBoolean("candidate_player_ready"))
        assertTrue("The public regression output must contain verified bytes", download.optLong("bytes") > 0)
        assertTrue("The public regression output must decode a video frame", download.optBoolean("first_frame_decoded"))
    }

}
