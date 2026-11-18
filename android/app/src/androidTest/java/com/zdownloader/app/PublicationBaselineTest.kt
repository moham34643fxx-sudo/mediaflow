package com.zdownloader.app

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Read-only pre-fix inspection: no Activity, recovery, processing or playback. */
class PublicationBaselineTest {
    @Test fun inspectExistingCompletedPublications()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val rows=JSONArray()
        for(row in app.repository.dao.completed().first().take(10)) {
            val uri=Uri.parse(row.uri)
            val entry=JSONObject().put("id",row.id).put("uri",uri.toString()).put("status",row.status)
            val columns=arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE)
            runCatching {app.contentResolver.query(uri,columns,null,null,null)?.use {cursor->if(cursor.moveToFirst()) columns.forEachIndexed {index,name->entry.put(name,cursor.getString(index))}}}
                .onFailure {entry.put("query_error",it.javaClass.simpleName)}
            val video=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentUris.parseId(uri))
            entry.put("video_collection_row",runCatching {app.contentResolver.query(video,arrayOf("_id"),null,null,null)?.use {it.moveToFirst()}==true}.getOrDefault(false))
            rows.put(entry)
        }
        val label=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val dir=File(app.getExternalFilesDir(null),"advanced-audit/$label").apply {mkdirs()}
        File(dir,"publication-baseline.json").writeText(JSONObject().put("rows",rows).put("ui_opened",false).toString(2))
    }
}
