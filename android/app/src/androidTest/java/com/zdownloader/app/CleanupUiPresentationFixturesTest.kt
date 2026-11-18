package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Remove only this session's unmistakable synthetic notification fixtures. */
class CleanupUiPresentationFixturesTest {
    @Test fun removeOnlyOwnedNotificationPresentationRows()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val rows=app.repository.dao.observe().first()
        val owned=rows.filter {it.id.matches(Regex("ui-notification-[0-9a-f-]{36}"))&&it.source=="UI fixture"&&
            it.url=="https://example.com/presentation.mp4"&&it.title=="معاينة إشعار التنزيل — عنوان طويل"}
        val removed=JSONArray()
        for(row in owned) {
            assertNotEquals("Synthetic presentation fixture unexpectedly owns media","COMPLETED",row.status)
            app.repository.cancel(row.id);app.coordinator.stopAndAwait(row.id);app.notifications.dismiss(row.id)
            app.repository.remove(row.id);app.storage.removeTemporary(row.id);removed.put(row.id)
        }
        val remaining=app.repository.dao.observe().first()
        assertEquals(rows.map {it.id}.toSet()-owned.map {it.id}.toSet(),remaining.map {it.id}.toSet())
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"owned-ui-fixture-cleanup.json").writeText(JSONObject().put("removed_ids",removed).put("other_row_ids_preserved",true).toString(2))
    }
}
