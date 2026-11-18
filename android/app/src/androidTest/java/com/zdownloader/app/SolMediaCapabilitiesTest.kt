package com.zdownloader.app

import android.media.MediaCodecList
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SolMediaCapabilitiesTest {
    @Test fun runtimeCapabilitiesMatchPlatformAndRejectUnsupportedDimensions() {
        val caps=MediaIntelligence.detectPlatformCapabilities()
        if(Build.VERSION.SDK_INT>=29) {
            val hardware=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {!it.isEncoder&&it.isHardwareAccelerated}
            fun has(mime:String)=hardware.any {it.supportedTypes.any {type->type.equals(mime,true)}}
            assertEquals(has("video/avc"),caps.hasHardwareAvc)
            assertEquals(has("video/hevc"),caps.hasHardwareHevc)
            assertEquals(has("video/av01"),caps.hasHardwareAv1)
            assertEquals(has("video/x-vnd.on2.vp9"),caps.hasHardwareVp9)
        }
        val impossible=Format("impossible","mp4",131072,240.0,"avc1.640028","none",null,null,"https","{}",width=131072)
        assertFalse(MediaIntelligence.hardwareSupported(impossible,caps))
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        val ffmpeg=com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-buildconf"))
        File(folder,"sol-capabilities-native.json").writeText(JSONObject().put("hardware_avc",caps.hasHardwareAvc).put("hardware_hevc",caps.hasHardwareHevc)
            .put("hardware_av1",caps.hasHardwareAv1).put("hardware_vp9",caps.hasHardwareVp9).put("impossible_dimensions_rejected",true)
            .put("ffmpeg_configuration",ffmpeg.allLogsAsString).put("ffmpeg_return_code",ffmpeg.returnCode?.value).toString(2))
    }
}
