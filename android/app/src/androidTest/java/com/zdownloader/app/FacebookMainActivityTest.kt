package com.zdownloader.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Same supplied Facebook source through actual MainActivity URL/preview controls. */
class FacebookMainActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun suppliedFacebookVideoCompletesThroughMainActivity()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=requireNotNull(args.getString("facebookUrl"))
        val before=app.repository.dao.observe().first().map {it.id}.toSet()
        val report=JSONObject().put("source_url",url).put("entry","MainActivity URL input")
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
        var id:String?=null
        try {
            compose.onNodeWithTag("url_input").performTextInput(url)
            compose.onNodeWithTag("analyze_link").performClick()
            compose.waitUntil(120000) {compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty()}
            val language=com.zdownloader.app.data.SettingsRepository(app).current().language
            compose.onAllNodesWithText(if(language=="ar") "فيديو" else "Video").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            // Use actual available quality; the portrait label is 1080p, encoded 1080x1920.
            compose.onAllNodesWithText("1080p").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            compose.onNodeWithTag("start_download").assertIsEnabled().performClick()
            compose.waitUntil(25000) {runBlocking {app.repository.dao.observe().first().any {it.url==url&&it.id !in before}}}
            id=app.repository.dao.observe().first().first {it.url==url&&it.id !in before}.id
            withTimeout(600000) {while(app.repository.dao.get(id!!)!!.status !in listOf("COMPLETED","FAILED","PAUSED","CANCELLED")) delay(200)}
            val row=app.repository.dao.get(id!!)!!
            val selection=Selection.parse(row.selection)
            report.put("request_id",id).put("status",row.status).put("error",row.error).put("retries",row.retries)
                .put("selected_id",selection.primary.id).put("width",selection.primary.width).put("height",selection.primary.height).put("bytes",row.outputSize)
            assertEquals(row.error,"COMPLETED",row.status);assertEquals(0,row.retries)
            assertEquals(1080,selection.primary.width);assertEquals(1920,selection.primary.height)
            val retriever=MediaMetadataRetriever()
            try {retriever.setDataSource(app,Uri.parse(row.uri));val frame=retriever.getFrameAtTime(0);assertNotNull(frame);frame?.recycle()} finally {retriever.release()}
            report.put("android_decode",true).put("state","passed")
        } catch(error:Throwable) {report.put("state","failed").put("error_class",error.javaClass.simpleName);throw error}
        finally {
            File(folder,"facebook-main-activity.json").writeText(report.toString(2))
            id?.takeIf {it !in before}?.let {key->
                val row=app.repository.dao.get(key)
                if(row?.status=="COMPLETED") app.storage.deleteFile(row)
                else {app.repository.cancel(key);app.coordinator.stopAndAwait(key);app.repository.remove(key)}
            }
        }
        Unit
    }
}
