package com.zdownloader.app

import android.media.MediaCodecList
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

class DecoderCapabilitiesTest {
    @Test fun inspectActualAv1DecoderCapabilities() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val decoders=FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-decoders"))
        val av1=decoders.allLogsAsString.lines().filter {it.contains("av1",true)||it.contains("dav1d",true)||it.contains("libaom",true)}
        val adapter=FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-h","decoder=av1_mediacodec"))
        val platform=JSONArray()
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter {info->!info.isEncoder&&info.supportedTypes.any {it.equals("video/av01",true)}}.forEach {info->
            val row=JSONObject().put("name",info.name)
            if(Build.VERSION.SDK_INT>=29) row.put("software",info.isSoftwareOnly).put("hardware",info.isHardwareAccelerated)
            platform.put(row)
        }
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        val report=JSONObject().put("ffmpeg_av1_decoders",JSONArray(av1)).put("platform_av1_decoders",platform).put("adapter_options",adapter.allLogsAsString)
        InstrumentationRegistry.getArguments().getString("sourcePath")?.let {path->
            val attempts=JSONArray()
            for(ndk in listOf("1","0")) {
                val session=FFmpegKit.executeWithArguments(arrayOf("-v","verbose","-xerror","-err_detect","explode","-c:v","av1_mediacodec",
                    "-ndk_codec",ndk,"-protocol_whitelist","file,pipe","-i",path,"-map","0:v:0","-an","-fps_mode","passthrough","-f","null","-"))
                attempts.put(JSONObject().put("ndk",ndk).put("code",session.returnCode?.value).put("log",session.allLogsAsString.takeLast(12000)))
            }
            report.put("adapter_attempts",attempts)
        }
        File(folder,"av1-decoder-capabilities.json").writeText(report.toString(2))
    }
}
