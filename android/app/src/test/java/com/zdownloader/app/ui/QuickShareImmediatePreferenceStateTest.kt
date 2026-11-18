package com.zdownloader.app.ui

import org.junit.Assert.*
import org.junit.Test

class QuickShareImmediatePreferenceStateTest {
    private val url="https://example.com/watch"

    @Test fun videoQualityPreferenceCanBeQueuedBeforeMetadataButRequiresAValidSource() {
        assertTrue(QuickShareState(sourceUrl=url,qualityPreference=360,mp3Available=true).canSubmit)
        assertTrue(QuickShareState(sourceUrl=url,qualityPreference=480,mp3Available=true).canSubmit)
        assertTrue(QuickShareState(sourceUrl=url,qualityPreference=720,mp3Available=true).canSubmit)
        assertTrue(QuickShareState(sourceUrl=url,qualityPreference=0,mp3Available=true).canSubmit)
        assertFalse(QuickShareState(sourceUrl=url).canSubmit)
        assertFalse(QuickShareState(qualityPreference=720).canSubmit)
    }

    @Test fun mp3PreferenceWaitsForNativeEncoderAndSurvivesAnalysisWithoutLosingSelection() {
        val audio = com.zdownloader.app.domain.Format.parse(
            org.json.JSONObject().put("format_id","a1").put("url","https://example.com/a.m4a")
                .put("ext","m4a").put("vcodec","none").put("acodec","aac")
        )
        val mediaWithAudio = com.zdownloader.app.domain.Media(url,"id","Title","source",null,null,30.0,listOf(audio))
        val pending=QuickShareState(sourceUrl=url,media=mediaWithAudio,qualityPreference=-1,audioOutput="mp3")
        assertFalse(pending.canSubmit)
        assertEquals(-1,pending.qualityPreference)
        assertTrue(pending.copy(mp3Available=true).canSubmit)
        assertTrue(pending.copy(mp3Available=true,analyzing=true).canSubmit)
    }

    @Test fun qualityCapCanSubmitWithTheBestVerifiedFallbackAtOrBelowIt() {
        val f480 = com.zdownloader.app.domain.Format.parse(
            org.json.JSONObject().put("format_id","v480").put("url","https://example.com/480.mp4")
                .put("ext","mp4").put("width",854).put("height",480).put("vcodec","h264").put("acodec","none")
        )
        val audio = com.zdownloader.app.domain.Format.parse(
            org.json.JSONObject().put("format_id","a1").put("url","https://example.com/a.m4a")
                .put("ext","m4a").put("vcodec","none").put("acodec","aac")
        )
        val mediaWith480Only = com.zdownloader.app.domain.Media("https://example.com/watch","id","Title","source",null,null,30.0,listOf(f480, audio))

        // When 480p is preferred and exists in media -> canSubmit is true
        val state480 = QuickShareState(sourceUrl=url,media=mediaWith480Only,qualityPreference=480,mp3Available=true)
        assertTrue(state480.canSubmit)
        assertTrue(state480.copy(analyzing=true).canSubmit)

        // A 720p cap may submit the best available 480p source below it.
        val state720Missing = QuickShareState(sourceUrl=url,media=mediaWith480Only,qualityPreference=720,mp3Available=true)
        assertTrue(state720Missing.canSubmit)
        assertEquals("v480",com.zdownloader.app.domain.VideoPreferenceSelection.ready(mediaWith480Only,720)?.primary?.id)

        // A 360p cap cannot upgrade to the available 480p source.
        val state360Missing = QuickShareState(sourceUrl=url,media=mediaWith480Only,qualityPreference=360,mp3Available=true)
        assertFalse(state360Missing.canSubmit)

        // When preference is marked unavailable -> canSubmit is FALSE
        val stateUnavailable = state480.copy(preferenceUnavailable=true)
        assertFalse(stateUnavailable.canSubmit)

        // Exact source is preferred when it matches the cap.
        assertNotNull(com.zdownloader.app.domain.VideoPreferenceSelection.ready(mediaWith480Only, 480))
        assertEquals("v480",com.zdownloader.app.domain.VideoPreferenceSelection.ready(mediaWith480Only, 720)?.primary?.id)
    }
}
