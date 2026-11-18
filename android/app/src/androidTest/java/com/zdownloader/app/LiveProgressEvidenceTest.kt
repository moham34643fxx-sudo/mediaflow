package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Separate UI-observation test; not used as screen-off/no-MainActivity acceptance. */
class LiveProgressEvidenceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    @Test fun realTransfersRenderExactAndEstimatedByteCounts()=runBlocking {
        val python=app.extractor.python();val fixture=python.getModule("types").callAttr("ModuleType","progress_evidence_fixture")
        val script="""
import yt_dlp, zextractor as z
original_init = yt_dlp.YoutubeDL.__init__
original_extract = z.AnalysisYoutubeDL.extract_info
fixture_urls = set()
def limited_init(self, params=None, *args, **kwargs):
    params = dict(params or {})
    if params.get('outtmpl') and '/transfers/' in str(params['outtmpl']):
        params['ratelimit'] = 128 * 1024
    original_init(self, params, *args, **kwargs)
def native_fixture(self, url, *args, **kwargs):
    result = original_extract(self, url, *args, **kwargs)
    if url in fixture_urls and isinstance(result, dict):
        for fmt in result.get('formats', []):
            if fmt.get('protocol') in ('http', 'https'):
                fmt['downloader_options'] = {'http_chunk_size': 256 * 1024}
    return result
yt_dlp.YoutubeDL.__init__ = limited_init
z.AnalysisYoutubeDL.extract_info = native_fixture
""".trimIndent()
        python.builtins.callAttr("exec",script,fixture.get("__dict__"))
        val settings=SettingsRepository(app);val old=settings.current();val records=JSONArray()
        try {
            settings.text("language","en")
            for((label,url) in listOf("exact" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?progress-evidence=1",
                "estimated" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?progress-evidence=1")) {
                fixture.get("fixture_urls")!!.callAttr("add",url)
                var id:String?=null
                try {
                    val share=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
                    ActivityScenario.launch<ShareReceiverActivity>(share).use {scenario->
                        var model:QuickShareViewModel?=null;scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
                        compose.waitUntil(90_000) {
                            val media=model!!.state.value.media
                            media!=null&&media.qualityGroups().any {it.resolution==480}&&
                                compose.onAllNodesWithTag("share_preference_480").fetchSemanticsNodes().isNotEmpty()
                        }
                        val preview=requireNotNull(model!!.state.value.media)
                        assertEquals(480,verifiedQualityEdge(requireNotNull(VideoPreferenceSelection.ready(preview,480)).primary))
                        compose.onNodeWithTag("share_preference_480").performScrollTo().assertIsEnabled().performClick()
                        compose.onNodeWithTag("share_download").performClick()
                        withTimeout(20000) {while(model!!.state.value.queuedId==null) {assertNull(model!!.state.value.error);delay(50)}}
                        id=model!!.state.value.queuedId!!
                        withTimeout(90000) {while(app.repository.dao.get(id!!)!!.bytes<=0) delay(50)}
                    }
                    // Work has already started autonomously. Main is now opened only
                    // to photograph its real card; no Resume/queue nudge is invoked.
                    ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                        compose.waitUntil(15000) {compose.onAllNodesWithTag("nav_downloads").fetchSemanticsNodes().isNotEmpty()}
                        compose.onNodeWithTag("nav_downloads").performClick()
                        compose.onNodeWithTag("downloads_grid").performScrollToNode(hasTestTag("download_card_$id"))
                        withTimeout(60000) {while(true) {val row=app.repository.dao.get(id!!)!!;if(row.bytes>0&&row.total!=null&&row.status.startsWith("DOWNLOADING")&&row.sizeKind==if(label=="exact") "EXACT" else "ESTIMATED") break;assertTrue("Transfer ended before the expected live sample: $row",row.status !in setOf("FAILED","COMPLETED"));delay(50)}}
                        compose.waitUntil(10000) {compose.onAllNodesWithTag("download_bytes_$id").fetchSemanticsNodes().isNotEmpty()}
                        val row=app.repository.dao.get(id!!)!!
                        val bitmap=compose.onNodeWithTag("download_card_$id").captureToImage().asAndroidBitmap()
                        File(folder,"live-progress-$label.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                        records.put(JSONObject().put("label",label).put("bytes",row.bytes).put("total",row.total).put("kind",row.sizeKind).put("speed",row.speed)
                            .put("real_public_media",url).put("scope","Live production card; test-only native downloader rate limit and chunk option; Main opened for screenshot only"))
                        withTimeout(240000) {while(app.repository.dao.get(id!!)!!.status !in setOf("COMPLETED","FAILED")) delay(200)}
                        assertEquals("COMPLETED",app.repository.dao.get(id!!)!!.status)
                    }
                } finally {id?.let {key->app.coordinator.stopAndAwait(key);val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.repository.remove(key)}}}
            }
            File(folder,"live-progress-captures.json").writeText(records.toString(2))
        } finally {
            settings.text("language",old.language)
            python.builtins.callAttr("exec","yt_dlp.YoutubeDL.__init__ = original_init\nz.AnalysisYoutubeDL.extract_info = original_extract",fixture.get("__dict__"))
        }
    }
}
