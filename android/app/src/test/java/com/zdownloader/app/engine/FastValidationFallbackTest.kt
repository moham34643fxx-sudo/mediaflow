package com.zdownloader.app.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FastValidationFallbackTest {
    @Test fun av1FallbackSelectsAndroidDecoderBeforeInputAndRetainsStrictAudioDecode() {
        val file=File("fallback.mp4")
        val args=FastIntegrityAuditor.strictFallbackArguments(file,"video/av01").toList()
        assertEquals("av1_mediacodec",args[args.indexOf("-c:v")+1])
        assertTrue(args.indexOf("-c:v")<args.indexOf("-i"))
        assertEquals("1",args[args.indexOf("-ndk_codec")+1])
        assertEquals(file.path,args[args.indexOf("-i")+1])
        assertTrue(args.contains("0:a:0?"))
        assertFalse(args.contains("copy"))
        assertEquals("explode",args[args.indexOf("-err_detect")+1])
        assertEquals("0",args[args.indexOf("-max_error_rate")+1])
        assertTrue(args.indexOf("-fps_mode")<args.indexOf("-f"))
    }

    @Test fun nonAv1FallbackKeepsExistingSoftwareDecodeWithoutAnAv1Override() {
        for(mime in listOf("video/avc","video/hevc","video/x-vnd.on2.vp9",null)) {
            val args=FastIntegrityAuditor.strictFallbackArguments(File("fallback.mkv"),mime).toList()
            assertFalse("Unexpected AV1 override for $mime",args.contains("av1_mediacodec"))
            assertFalse(args.contains("copy"))
            assertEquals("none",args[args.indexOf("-hwaccel")+1])
            assertTrue(args.containsAll(listOf("-xerror","explode","0:v:0?","0:a:0?")))
        }
    }
}
