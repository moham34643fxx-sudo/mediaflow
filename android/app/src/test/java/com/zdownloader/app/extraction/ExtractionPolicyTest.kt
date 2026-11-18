package com.zdownloader.app.extraction

import org.junit.Assert.*
import org.junit.Test

class ExtractionPolicyTest {
    private fun track(codec:String,channels:Int=2,rate:Int=48000)=AudioTrack(3,codec,channels,rate,60.0,"ar",null,true)
    @Test fun copyDependsOnCodecAndContainerRatherThanVideoSuffix() {
        val aac=track("aac")
        assertTrue(ExtractionPolicy.plan(aac,AudioOutput.ORIGINAL,0).copy)
        assertEquals("m4a",ExtractionPolicy.plan(aac,AudioOutput.ORIGINAL,0).extension)
        assertFalse(ExtractionPolicy.canCopy(aac,AudioOutput.MP3))
        assertNull(ExtractionPolicy.original("unknown_codec"))
        assertEquals("mka",ExtractionPolicy.plan(track("opus"),AudioOutput.ORIGINAL,0).extension)
    }
    @Test fun mp3ConversionDownmixesOnlyWhenNecessaryAndUsesSupportedNativeRate() {
        val p=ExtractionPolicy.plan(track("aac",6,96000),AudioOutput.MP3,192)
        assertFalse(p.copy);assertEquals(2,p.channels);assertEquals(44100,p.rate);assertEquals("mp3",p.codec)
        assertEquals(1,ExtractionPolicy.plan(track("aac",1),AudioOutput.MP3,192).channels)
        assertEquals(6,ExtractionPolicy.plan(track("aac",6),AudioOutput.ORIGINAL,0).channels)
    }
    @Test fun explicitConversionDoesNotSilentlyIgnoreTheRequestedBitrate() {
        assertFalse(ExtractionPolicy.plan(track("aac"),AudioOutput.M4A,128).copy)
        assertTrue(ExtractionPolicy.plan(track("mp3"),AudioOutput.MP3,0).copy)
    }
    @Test fun outputNameCannotEscapeTheDestinationAndKeepsAnUnambiguousSuffix() {
        val name=ExtractionPolicy.filename("../../movie.MP4","mp3","12345678-1234")
        assertFalse(name.contains('/'));assertFalse(name.contains('\\'));assertTrue(name.endsWith("_audio_12345678.mp3"))
        assertTrue(ExtractionPolicy.filename("فيديو تجريبي.mp4","m4a","abcdefgh").startsWith("فيديو تجريبي_audio_"))
    }
}
