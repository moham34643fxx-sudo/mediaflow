package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioCopySelectionTest {
    private fun audio(id:String,codec:String,ext:String,rate:Double?)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$ext").put("ext",ext)
        .put("vcodec","none").put("acodec",codec).put("abr",rate))
    private fun media(vararg formats:Format)=Media("https://example.com/watch","audio-copy","Audio","example.com",
        null,null,60.0,formats.toList())

    @Test fun mp3CopiesOnlyWhenExplicitRateMatchesOrBestSourceWasRequested() {
        val mp3=audio("mp3","mp3","mp3",192.0)
        val source=media(mp3)
        assertFalse(Selection.audio(source,"mp3",mp3,192).convertAudio)
        assertFalse(Selection.audio(source,"mp3",mp3,0).convertAudio)
        assertTrue(Selection.audio(source,"mp3",mp3,128).convertAudio)
        val unknown=audio("unknown-rate","mp3","mp3",null)
        assertTrue(Selection.audio(media(unknown),"mp3",unknown,192).convertAudio)
    }
    @Test fun requestedBitrateSurvivesPersistenceAndDrivesPlanAndEstimate() {
        for(codec in listOf("mp3","aac","opus")) for(sourceRate in listOf(128,192,320)) for(requested in listOf(0,128,192,320)) {
            val extension=if(codec=="aac") "m4a" else codec
            val source=audio("$codec-$sourceRate",codec,extension,sourceRate.toDouble())
            val selection=Selection.audio(media(source),extension,source,requested)
            val restored=Selection.parse(selection.json())
            val converts=requested!=0&&requested!=sourceRate
            assertEquals("$codec/$sourceRate->$requested",converts,restored.convertAudio)
            assertEquals(requested,restored.bitrate)
            assertEquals(if(converts) ProcessingPlan.TRANSCODE_AUDIO else ProcessingPlan.REMUX,restored.plan)
            assertEquals(60L*(if(converts) requested else sourceRate)*125,restored.outputSize!!.bytes)
            assertFalse(Selection.audio(media(source),"original",source,requested).convertAudio)
        }
    }

    @Test fun aacAndOpusCanBeRemuxedWithoutEncodingWhenTheRequestedRateMatches() {
        val aac=audio("aac","mp4a.40.2","m4a",128.0)
        val opus=audio("opus","opus","webm",160.0)
        val source=media(aac,opus)
        assertFalse(Selection.audio(source,"m4a",aac,128).convertAudio)
        assertFalse(Selection.audio(source,"aac",aac,128).convertAudio)
        assertTrue(Selection.audio(source,"aac",aac,192).convertAudio)
        assertFalse(Selection.audio(source,"opus",opus,160).convertAudio)
        assertTrue(Selection.audio(source,"opus",opus,192).convertAudio)
    }

    @Test fun losslessFlacAndAlacM4aAreCopiedWithoutChangingTheRequestedOutput() {
        val flac=audio("flac","flac","flac",900.0)
        val alac=audio("alac","alac","m4a",900.0)
        val source=media(flac,alac)
        val copiedFlac=Selection.audio(source,"flac",flac,192)
        val copiedAlac=Selection.audio(source,"m4a",alac,192)
        assertEquals("flac",copiedFlac.output)
        assertFalse(copiedFlac.convertAudio)
        assertEquals("m4a",copiedAlac.output)
        assertFalse(copiedAlac.convertAudio)
    }

    @Test fun codecOrRequestedBitrateChangesStillSelectEncoding() {
        val opus=audio("opus","opus","webm",128.0)
        val source=media(opus)
        assertTrue(Selection.audio(source,"mp3",opus,128).convertAudio)
        assertTrue(Selection.audio(source,"flac",opus,128).convertAudio)
    }

    @Test fun quickShareUsesMatchingNativeMp3WithoutRequiringTheEncoder() {
        val opus=audio("opus","opus","webm",192.0)
        val mp3=audio("native-mp3","mp3","mp3",192.0)
        val source=media(opus,mp3)
        val preferred=Selection.audio(source,"original").primary
        assertEquals(opus.id,preferred.id)

        val choice=QuickShareFormats.audioChoices(source,preferred,mp3Available=false).single {it.output=="mp3"}
        assertEquals(mp3.id,choice.formatId)
        assertEquals("mp3",choice.resolve(source)!!.output)
        assertEquals(0,choice.resolve(source)!!.bitrate)
        assertFalse(choice.resolve(source)!!.convertAudio)
    }

    @Test fun quickShareDoesNotReplaceSelectedMp3BitrateWithAnotherNativeRate() {
        val selected=audio("mp3-128","mp3","mp3",128.0).copy(language="en")
        val higher=audio("mp3-320","mp3","mp3",320.0).copy(language="en")
        val source=media(selected,higher)
        val choice=QuickShareFormats.audioChoices(source,selected,mp3Available=false).single {it.output=="mp3"}

        assertEquals(selected.id,choice.formatId)
        assertFalse(choice.resolve(source)!!.convertAudio)
        assertEquals(0,choice.resolve(source)!!.bitrate)
    }

    @Test fun unknownOrDifferentRateNativeMp3DoesNotSatisfyKnownSourceRate() {
        val selected=audio("opus-256","opus","webm",256.0).copy(language="en")
        val unknown=audio("mp3-unknown","mp3","mp3",null).copy(language="en")
        val lower=audio("mp3-192","mp3","mp3",192.0).copy(language="en")
        val source=media(selected,unknown,lower)
        val unavailable=QuickShareFormats.audioChoices(source,selected,mp3Available=false)

        assertTrue(unavailable.none {it.output=="mp3"})
        val available=QuickShareFormats.audioChoices(source,selected,mp3Available=true).single {it.output=="mp3"}
        assertEquals(selected.id,available.formatId)
        assertTrue(available.resolve(source)!!.convertAudio)
    }

    @Test fun automaticOutputSelectionUsesACompatibleSourceWithoutChangingLanguage() {
        val englishOpus=audio("english-opus","opus","webm",256.0).copy(language="en",languagePreference=10.0)
        val englishMp3=audio("english-mp3","mp3","mp3",192.0).copy(language="en",languagePreference=10.0)
        val frenchMp3=audio("french-mp3","mp3","mp3",192.0).copy(language="fr",languagePreference=-1.0)
        val source=media(englishOpus,englishMp3,frenchMp3)

        val selected=Selection.audio(source,"mp3")
        assertEquals(englishMp3.id,selected.primary.id)
        assertEquals("mp3",selected.output)
        assertFalse(selected.convertAudio)
    }

    @Test fun quickShareDoesNotSwitchTheSelectedAudioLanguageToFindNativeMp3() {
        val english=audio("english-opus","opus","webm",256.0).copy(language="en",languagePreference=10.0)
        val french=audio("french-mp3","mp3","mp3",192.0).copy(language="fr",languagePreference=-1.0)
        val source=media(english,french)

        val choice=QuickShareFormats.audioChoices(source,english,mp3Available=true).single {it.output=="mp3"}
        assertEquals(english.id,choice.formatId)
        assertTrue(choice.resolve(source)!!.convertAudio)
    }

    @Test fun extractedNativeMp3MetadataRetainsItsSourceIdentityAndAudioProperties() {
        val payload=JSONObject().put("format_id","native-mp3-192").put("url","https://example.com/audio.mp3")
            .put("ext","mp3").put("protocol","https").put("vcodec","none").put("acodec","mp3")
            .put("abr",192).put("audio_channels",2).put("asr",44100)
        val parsed=Media.parse(JSONObject().put("formats",org.json.JSONArray().put(payload)),"https://example.com/watch")
            .audioOptions().single()

        assertEquals("native-mp3-192",parsed.id)
        assertEquals("mp3",parsed.audioCodec)
        assertEquals("mp3",parsed.ext)
        assertEquals(192.0,parsed.audioBitrate!!,0.01)
        assertEquals(2,parsed.audioChannels)
        assertEquals(44100,parsed.sampleRate)
        assertTrue(parsed.streamOnly)
    }
}
