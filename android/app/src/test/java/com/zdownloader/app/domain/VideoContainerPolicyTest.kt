package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class VideoContainerPolicyTest {
    private val vp9=Format("vp9","mp4",720,30.0,"vp09.00.31.08","none",1000,1000.0,"https","{}",width=1280)
    private val aac=Format("aac","m4a",null,null,"none","aac",1000,128.0,"https","{}")
    @Test fun android12AndLaterPreserveVp9InMp4InsteadOfEncodingH264() {
        for(sdk in listOf(31,33,35,36)) {
            assertTrue(VideoContainerPolicy.canCopyMp4Video("vp9",sdk))
            assertFalse(VideoContainerPolicy.transcodeVideo("vp09.00.31.08","mp4",sdk))
            assertEquals("mp4",videoOutputs(vp9,aac,sdk).first())
        }
    }
    @Test fun olderDevicesPreferOriginalMkvAndRetainExplicitMp4Conversion() {
        for(sdk in listOf(24,28,30)) {
            assertFalse(VideoContainerPolicy.canCopyMp4Video("vp9",sdk))
            assertTrue(VideoContainerPolicy.transcodeVideo("vp9","mp4",sdk))
            assertEquals("mkv",videoOutputs(vp9,aac,sdk).first())
            assertTrue("mp4" in videoOutputs(vp9,aac,sdk))
        }
    }
    @Test fun existingAvcHevcAv1AndUnknownPlansRemainUnchanged() {
        for(codec in listOf("avc1.64001f","hvc1.1.6","av01.0.05M.08")) {
            assertFalse(VideoContainerPolicy.transcodeVideo(codec,"mp4",35))
        }
        assertFalse(VideoContainerPolicy.transcodeVideo(null,"mp4",35))
        assertTrue(VideoContainerPolicy.transcodeVideo("mpeg4","mp4",35))
        assertFalse(VideoContainerPolicy.transcodeVideo("vp9","mkv",24))
    }
}
