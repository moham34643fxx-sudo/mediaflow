package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CorrectiveReleaseAcceptanceTest {
    @Test fun validateAndRemoveOnlyRecordedExternalProbePublications():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val arguments=InstrumentationRegistry.getArguments()
        val encoded=requireNotNull(arguments.getString("fixturePublications64"))
        val publications=JSONArray(String(android.util.Base64.decode(encoded,android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),Charsets.UTF_8))
        require(publications.length()>0)
        val results=JSONArray()
        for(index in 0 until publications.length()) {
            val fixture=publications.getJSONObject(index)
            val uri=fixture.getString("uri");val source=fixture.getString("url")
            val sourceUri=android.net.Uri.parse(source)
            val nonce=sourceUri.getQueryParameter("mediaflow_probe") ?: sourceUri.getQueryParameter("mediaflow_r8_probe")
            require(sourceUri.scheme=="https"&&nonce?.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))==true)
            val row=app.repository.dao.completed().first().single {it.uri==uri}
            require(row.url==source)
            val selection=Selection.parse(row.selection)
            assertEquals(fixture.optBoolean("audio"),selection.audioOnly)
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app,android.net.Uri.parse(uri))
                assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
                if(!selection.audioOnly) {
                    val frame=retriever.getFrameAtTime(1000000);assertNotNull(frame);frame?.recycle()
                } else assertEquals("mp3",selection.output)
            } finally {retriever.release()}
            results.put(JSONObject().put("uri",uri).put("url",source).put("audio",selection.audioOnly).put("bytes",row.outputSize).put("validated",true))
            app.coordinator.stopAndAwait(row.id);app.storage.deleteFile(row)
            assertNull(app.repository.dao.get(row.id))
        }
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${arguments.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"external-probes-validated.json").writeText(results.toString(2))
    }
}
