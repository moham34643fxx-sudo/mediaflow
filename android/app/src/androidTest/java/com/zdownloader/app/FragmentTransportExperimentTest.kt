package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.audioOptions
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Test-process-only budget experiment on one signed representation and the legacy native transport. */
class FragmentTransportExperimentTest {
    @Test fun compareNativeFragmentBudgets():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        assertEquals(0,app.repository.dao.remaining())
        val media=app.extractor.analyze(checkNotNull(args.getString("probeSource")),forceExtractor=true,enrichOptional=false)
        val format=Selection.audio(media,"mp3").primary
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        val report=JSONObject().put("format_id",format.id).put("protocol",format.protocol)
            .put("audio_options",JSONArray(media.audioOptions().map {track->JSONObject().put("id",track.id).put("protocol",track.protocol)
                .put("bitrate",track.audioBitrate ?: track.bitrate).put("language",track.language).put("language_preference",track.languagePreference)}))
        val trials=JSONArray();report.put("trials",trials)
        val module=app.extractor.python().getModule("zextractor")
        val namespace=module.get("__dict__")!!
        app.extractor.python().builtins.callAttr("exec","""
import builtins as _budget_builtins
_budget_original_min=globals().get('min')
_budget_original_install=globals().get('install_pooled_http')
_budget_original_ytdl=yt_dlp.YoutubeDL
_budget_observed=[]
class _BudgetObservedYoutubeDL(_budget_original_ytdl):
    def __init__(self,params,*args,**kwargs):
        _budget_observed.append(params.get('concurrent_fragment_downloads'))
        super().__init__(params,*args,**kwargs)
yt_dlp.YoutubeDL=_BudgetObservedYoutubeDL
def min(*values,**kwargs):
    if len(values)==2 and values[0]==8 and isinstance(values[1],int):
        return _budget_builtins.min(32,values[1])
    return _budget_builtins.min(*values,**kwargs)
if _budget_original_install is not None:
    install_pooled_http=lambda *args:False
""".trimIndent(),namespace)
        try {
            for(budget in listOf(8,16,32,32,16,8)) {
                val id="transport-${UUID.randomUUID()}"
                val dir=app.storage.temporary(id)
                val control=TransferControl().apply {fragmentConcurrency=budget}
                val began=android.os.SystemClock.elapsedRealtime()
                var bytes=0L
                var first:Long?=null
                val trial=JSONObject().put("fragment_concurrency",budget);trials.put(trial)
                try {
                    try {
                        withTimeout(150_000) {
                            app.transfer.stream(format,File(dir,"source.${format.ext}"),id,control) {count,_->
                                bytes=maxOf(bytes,count)
                                if(count>0&&first==null) first=android.os.SystemClock.elapsedRealtime()
                                if(bytes>=2*1024*1024) control.stop()
                            }
                        }
                    } catch(_:CancellationException) {assertTrue("Bounded transfer timed out",bytes>=2*1024*1024)}
                    trial.put("bytes",bytes).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-began)
                        .put("first_bytes_ms",first?.minus(began))
                    val effective=JSONArray(module.get("_budget_observed").toString())
                    trial.put("effective_budget",effective.getInt(effective.length()-1))
                    assertEquals(budget,effective.getInt(effective.length()-1))
                } finally {
                    control.stop()
                    withTimeout(30_000) {NativeWork.awaitIdle(dir)}
                    dir.deleteRecursively()
                    File(folder,"fragment-transport-experiment.json").writeText(report.toString(2))
                }
            }
            report.put("complete",true)
        } finally {
            app.extractor.python().builtins.callAttr("exec","""
if _budget_original_min is None:
    del min
else:
    min=_budget_original_min
if _budget_original_install is not None:
    install_pooled_http=_budget_original_install
yt_dlp.YoutubeDL=_budget_original_ytdl
del _budget_original_min,_budget_original_install,_budget_builtins,_budget_original_ytdl,_budget_observed,_BudgetObservedYoutubeDL
""".trimIndent(),namespace)
            File(folder,"fragment-transport-experiment.json").writeText(report.toString(2))
        }
    }
}
