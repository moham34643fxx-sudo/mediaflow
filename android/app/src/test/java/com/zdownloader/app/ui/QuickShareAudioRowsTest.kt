package com.zdownloader.app.ui

import com.zdownloader.app.domain.Format
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShareAudioRowsTest {
    private fun audio(id: String, language: String?, codec: String, container: String, bitrate: Int) =
        Format.parse(JSONObject()
            .put("format_id", id)
            .put("url", "https://example.com/$id.$container")
            .put("ext", container)
            .put("vcodec", "none")
            .put("acodec", codec)
            .put("abr", bitrate)
            .put("language", language)
            .put("language_preference", if (language == "fr") 2 else 1)
            .put("audio_channels", 2)
            .put("asr", 48000))

    @Test fun groupsKeepEveryLanguageAndCodecContainerAlternativeAndRankHighestBitrateFirst() {
        val formats = listOf(
            audio("en-low", "en", "opus", "webm", 96),
            audio("fr-native", "fr", "mp3", "mp3", 192),
            audio("en-high", "en", "opus", "webm", 256),
            audio("en-aac", "en", "mp4a.40.2", "m4a", 192)
        )

        val groups = QuickShareAudioRows.grouped(formats, selectedId = "en-aac")

        assertEquals("fr", groups.first().language)
        val english = groups.single { it.language == "en" }
        assertEquals(listOf("en-high", "en-aac", "en-low"), english.candidates.map { it.id })
        assertEquals("en-aac", english.format.id)
        assertTrue(english.selected)
        assertTrue(english.canExpand)
        assertFalse(groups.first().canExpand)
        assertEquals(setOf("en-low", "en-high", "en-aac", "fr-native"),
            groups.flatMap { QuickShareAudioRows.variants(it, "en-aac") }.map { it.format.id }.toSet())
        assertEquals("en-aac", QuickShareAudioRows.variants(english, "en-aac").single { it.selected }.format.id)
        assertTrue(QuickShareAudioRows.grouped(formats, "en-aac", setOf(english.key)).single { it.language == "en" }.expanded)
    }
}
