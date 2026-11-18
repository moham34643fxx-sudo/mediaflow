package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Diagnose real saved user requests without changing, retrying or deleting those rows. */
class UserFailureAnalysisTest {
    @Test fun inspectSavedFailedRequests()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        val rows=app.repository.dao.observe().first().filter {it.status=="FAILED"&&it.url.startsWith("https://www.facebook.com/share/v/")}
        val python=app.extractor.python()
        val diagnostic=python.getModule("types").callAttr("ModuleType","user_failure_diagnostic")
        python.builtins.callAttr("exec","""
import zextractor as z
messages = []
originals = {name: getattr(z.QuietLogger, name) for name in ('debug', 'warning', 'error')}
def observed(self, message):
    if len(messages) < 200:
        messages.append(str(message)[:8000])
    return originals['debug'](self, message)
for name in originals:
    setattr(z.QuietLogger, name, observed)
""".trimIndent(),diagnostic.get("__dict__"))
        try {
            for(row in rows.distinctBy {it.url}) {
                diagnostic.get("messages")!!.callAttr("clear")
                val report=JSONObject().put("original_job_id",row.id).put("original_error",row.error).put("request",JSONObject(row.request!!))
                var stage="PYTHON_ANALYSIS"
                try {
                    val raw=app.extractor.pythonCall("analyze",row.url,"user-analysis-${java.util.UUID.randomUUID()}")
                    File(folder,"${row.id}-raw-formats.json").writeText(raw.toString(2))
                    report.put("raw_error",raw.optString("error")).put("raw_format_count",raw.optJSONArray("formats")?.length() ?: 0)
                    stage="MODEL_NORMALIZATION"
                    val media=Media.parse(raw,row.url)
                    report.put("title",media.title).put("format_count",media.formats.size).put("formats",JSONArray(media.formats.map {format->
                        JSONObject().put("id",format.id).put("width",format.width).put("height",format.height)
                            .put("edge",verifiedQualityEdge(format)).put("protocol",format.protocol).put("video",format.video)
                            .put("audio",format.audio).put("video_codec",format.videoCodec).put("audio_codec",format.audioCodec)
                    }))
                    stage="QUALITY_POLICY"
                    val selection=DownloadRequest.parse(row.request!!).resolve(media)
                    report.put("resolved",true).put("selection",JSONObject(selection.json()))
                } catch(error:Throwable) {
                    report.put("failed_stage",stage).put("error",(error as? MediaException)?.code?.name ?: error.javaClass.name)
                        .put("stack",error.stackTraceToString())
                }
                report.put("python_messages",JSONArray(python.getModule("json").callAttr("dumps",diagnostic.get("messages")!!).toString()))
                File(folder,"${row.id}-analysis.json").writeText(report.toString(2))
            }
        } finally {
            python.builtins.callAttr("exec","for name, original in originals.items(): setattr(z.QuietLogger, name, original)",diagnostic.get("__dict__"))
        }
    }
}
