package com.zdownloader.app.ui

import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareVariantDetails.Field
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickShareVariantDetailsTest {
    private fun video(id:String,rate:Double?=null)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.mp4").put("ext","mp4").put("width",720).put("height",720)
        .put("vcodec","av01").put("acodec","aac").put("tbr",rate))
    private fun fields(vararg formats:Format):Map<String,Set<Field>> {
        val media=Media("https://example.com/watch","fixture","Fixture","Generic",null,null,60.0,formats.toList())
        return QuickShareVariantDetails.fields(formats.map { QuickShareCatalog.VideoEntry(it, Selection.video(media, it)) })
    }
    @Test fun sameGeometryWithDifferentSourceLabelsStillExposesUnroundedBitrate() {
        val flags=fields(video("a",209.1).copy(sourceQualityLabel="240p"),video("b",209.9).copy(sourceQualityLabel="720p"))
        assertEquals(setOf(Field.BITRATE),flags.getValue("a"))
        assertEquals(flags["a"],flags["b"])
    }
    @Test fun identicalMetadataNeedsNoInventedDistinguishingFields() {
        val flags=fields(video("a"),video("b"))
        assertEquals(mapOf("a" to emptySet<Field>(),"b" to emptySet()),flags)
    }
    @Test fun onlyRelevantEncodingAndAudioDifferencesAreAdded() {
        val first=video("a").copy(fps=30.0,audioChannels=2,sampleRate=44100,language="en")
        val alternative=video("b").copy(fps=60.0,audioChannels=6,sampleRate=48000,language="ar",audioCodec="opus",ext="webm")
        val expected=setOf(Field.FPS,Field.AUDIO,Field.LANGUAGE,Field.CHANNELS,Field.SAMPLE_RATE)
        assertEquals(expected,fields(first,alternative).getValue("a"))
        assertTrue(fields(first).getValue("a").isEmpty())
        assertTrue(fields().isEmpty())
    }
}
