package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.DirectMediaResolver
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Metadata/header diagnostics only, isolated from the user's jobs and database. */
class UnratedVideoDiscoveryTest {
    @Test fun inspectUnratedSourceHeaders():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName=="com.zdownloader.app.candidate0965")
        val app=instrument.targetContext.applicationContext as ZApplication
        val url=checkNotNull(InstrumentationRegistry.getArguments().getString("probeSource"))
        val report=JSONObject().put("passed",false)
        val formats=JSONArray();report.put("formats",formats)
        val target=File(app.filesDir,"quickshare-review/unrated-discovery.json").apply {parentFile!!.mkdirs()}
        try {
            val media=app.extractor.analyze(url,enrichOptional=false)
            for(format in media.formats) {
                val row=JSONObject().put("id",format.id).put("width",format.width).put("height",format.height)
                    .put("verifiedEdge",verifiedQualityEdge(format)).put("video",format.video).put("audio",format.audio)
                formats.put(row)
                if(format.video&&verifiedQualityEdge(format)==null) {
                    try {
                        val source=JSONObject(format.payload).getString("url")
                        val probed=withTimeout(12_000) {DirectMediaResolver().resolve(source,allowUnknownExtension=true)}
                        val measured=probed?.formats?.firstOrNull {it.video}
                        row.put("measuredWidth",measured?.width).put("measuredHeight",measured?.height)
                            .put("measuredVideoCodec",measured?.videoCodec).put("measuredAudioCodec",measured?.audioCodec)
                    } catch(e:CancellationException) {throw e}
                    catch(e:Exception) {row.put("probeError",e.javaClass.simpleName)}
                }
            }
            report.put("passed",true)
        } finally {target.writeText(report.toString(2))}
    }
}
