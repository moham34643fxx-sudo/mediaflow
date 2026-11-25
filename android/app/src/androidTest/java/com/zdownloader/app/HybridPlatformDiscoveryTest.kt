package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live discovery matrix, not a claim of full-download acceptance on every site.
 * Platform login/challenge failures are recorded explicitly, never called success. */
class HybridPlatformDiscoveryTest {
    @Test fun immediatePreferencesAndMetadataReuseAcrossRealPlatforms():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName=="com.zdownloader.app.candidate0965")
        val app=instrument.targetContext.applicationContext as ZApplication
        val sources=listOf(
            "YouTube" to "https://www.youtube.com/watch?v=Z0mxlbvL4VQ",
            "Instagram" to "https://www.instagram.com/reel/Chunk8-jurw/",
            "TikTok" to "https://www.tiktok.com/@scout2015/video/6718335390845095173",
            "HLS" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "DASH" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
            "DirectMP4" to "https://www.w3schools.com/html/mov_bbb.mp4")
        val rows=JSONArray()
        val file=File(app.filesDir,"quickshare-review/platform-discovery.json").apply {parentFile!!.mkdirs()}
        var discovered=0
        for((platform,url) in sources) {
            val row=JSONObject().put("platform",platform).put("discovered",false).put("fullDownloadTested",false)
            rows.put(row);app.extractor.clearMetadataCache();AnalysisTrace.clear()
            val started=SystemClock.elapsedRealtime()
            val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                lateinit var model:QuickShareViewModel
                scenario.onActivity {
                    model=ViewModelProvider(it)[QuickShareViewModel::class.java]
                    model.prefer(480)
                    assertTrue("$platform preference must submit before discovery",model.state.value.canSubmit)
                    assertNull(model.state.value.persistedId)
                }
                row.put("interactiveMs",SystemClock.elapsedRealtime()-started)
                try {
                    val ready=withTimeoutOrNull(115_000) {
                        while(model.state.value.analyzing||model.state.value.media==null&&model.state.value.error==null) delay(25)
                        model.state.value
                    }
                    if(ready?.media!=null) {
                        val media=ready.media
                        val edges=QuickShareCatalog.verifiedGroups(media).mapNotNull {it.resolution}
                        row.put("discovered",true).put("metadataMs",SystemClock.elapsedRealtime()-started).put("edges",JSONArray(edges))
                        assertEquals(480,ready.qualityPreference)
                        val selected=VideoPreferenceSelection.ready(media,480)
                        if(selected!=null) assertTrue(verifiedQualityEdge(selected.primary)!!<=480)
                        row.put("resolved480",selected?.primary?.let(::verifiedQualityEdge))
                        val before=JSONArray(AnalysisTrace.snapshot())
                        val again=app.extractor.analyze(url,enrichOptional=false)
                        val after=JSONArray(AnalysisTrace.snapshot())
                        fun calls(array:JSONArray)=(0 until array.length()).count {array.getJSONObject(it).has("python_call_start")}
                        assertEquals("$platform repeated base extraction",calls(before),calls(after))
                        assertEquals(media.id,again.id)
                        row.put("freshCacheReused",true);discovered++
                    } else row.put("error",ready?.error?.name ?: "DISCOVERY_TIMEOUT")
                } finally {scenario.onActivity {model.cancelAnalysis()};file.writeText(JSONObject().put("sources",rows).toString(2))}
            }
        }
        file.writeText(JSONObject().put("sources",rows).put("discoveredCount",discovered).put("uiChecksPassed",true).toString(2))
        assertTrue("No live platform produced metadata; inspect matrix evidence",discovered>0)
    }
}
