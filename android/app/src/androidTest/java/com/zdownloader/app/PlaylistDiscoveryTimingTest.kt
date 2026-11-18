package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.AnalysisTrace
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaylistDiscoveryTimingTest {
    @Test fun controlledPlaylistDiscoveryReturnsSafeStageTimings()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","playlist_timing_fixture")
        val script="""
import zextractor as z
original = z.AnalysisYoutubeDL.extract_info
def controlled(self, url, *args, **kwargs):
    if 'mediaflow-playlist-timing-fixture' not in url:
        return original(self, url, *args, **kwargs)
    return {'_type': 'playlist', 'title': 'Timing fixture', 'entries': [
        {'_type': 'url', 'id': 'fixture-item', 'title': 'Fixture item',
         'url': 'https://example.com/mediaflow-timing-item'}]}
z.AnalysisYoutubeDL.extract_info = controlled
""".trimIndent()
        python.builtins.callAttr("exec",script,fixture.get("__dict__"))
        val id="playlist-timing-${UUID.randomUUID()}"
        try {
            val result=app.extractor.playlist("https://example.com/mediaflow-playlist-timing-fixture",id)
            assertFalse("Controlled playlist extraction should succeed: $result",result.has("error"))
            val timings=result.optJSONObject("timings_ms")
            assertNotNull("Playlist results expose stage timings",timings)
            for(stage in listOf("total","engine_setup","extraction","normalization",
                "network_headers_inclusive","dns_inclusive","requests","redirects")) {
                assertTrue("Missing playlist timing $stage",timings!!.has(stage))
                assertTrue("Timing $stage must be non-negative",timings.optLong(stage,-1)>=0)
            }
            val traces=JSONArray(AnalysisTrace.snapshot())
            val row=(0 until traces.length()).map {traces.getJSONObject(it)}.firstOrNull {it.optString("id")==id}
            assertNotNull("The playlist request has a timing trace",row)
            for(stage in listOf("python_queued","python_call_start","playlist_request_registered",
                "playlist_extract_started","playlist_extract_finished","playlist_page_ready",
                "python_call_returned","playlist_result_returned")) {
                assertTrue("Missing playlist trace stage $stage",row!!.has(stage))
            }
            assertFalse("Timing output must not contain source URLs",timings.toString().contains("https://"))
            assertFalse("Timing trace must not contain source URLs",AnalysisTrace.snapshot().contains("https://"))
        } finally {
            python.builtins.callAttr("exec","z.AnalysisYoutubeDL.extract_info = original",fixture.get("__dict__"))
        }
    }
}
