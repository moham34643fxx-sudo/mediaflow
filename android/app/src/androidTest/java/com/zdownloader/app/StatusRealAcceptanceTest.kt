package com.zdownloader.app

import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.status.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Explicitly user-authorized real-data acceptance; no shell grants or private screenshots. */
class StatusRealAcceptanceTest {
    @Test fun userGrantedFolderAutomaticDiscoveryAndOriginalGalleryCopies()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val settings=SettingsRepository(context)
        val permissions=StatusPermissions(context,settings)
        val scanner=StatusMediaScanner(context,permissions)
        val store=StatusStore(context)
        val saver=StatusSaveCoordinator(context,permissions,scanner,store,settings)
        val report=JSONObject().put("scope","User explicitly authorized real status-folder access and one photo/video copy. No source identifiers or screenshots retained.")
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("outcome","awaiting_user_permission")
        val folder=File(context.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        fun write() {File(folder,"status-real-acceptance.json").writeText(report.toString(2))}
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                val result=CompletableDeferred<Uri?>()
                scenario.onActivity {activity->
                    activity.activityResultRegistry.register("real-status-access",ActivityResultContracts.OpenDocumentTree()) {result.complete(it)}
                        .launch(permissions.initial(StatusSource.WHATSAPP))
                }
                val uri=withTimeoutOrNull(300000) {result.await()}
                if(uri==null) {report.put("outcome","picker_cancelled_or_timed_out");return@use}
                val persistent=permissions.choose(StatusSource.WHATSAPP,uri)
                report.put("persisted_read",persistent).put("outcome","access_granted")
                val scan=scanner.scan(StatusSource.WHATSAPP)
                report.put("discovered_photos",scan.items.count {!it.type.video}).put("discovered_videos",scan.items.count {it.type.video})
                    .put("skipped",scan.skipped).put("limited",scan.limited)
                val samples=listOfNotNull(scan.items.firstOrNull {!it.type.video},scan.items.firstOrNull {it.type.video})
                val rows=JSONArray()
                suspend fun hash(uri:Uri,expected:Long?)=context.contentResolver.openInputStream(uri)!!.use {input->
                    StatusRules.copy(input,object:java.io.OutputStream() {override fun write(value:Int) {};override fun write(bytes:ByteArray,offset:Int,length:Int) {}},expected)
                }
                for(item in samples) {
                    val source=Uri.parse(item.uri);val before=hash(source,item.bytes)
                    val saved=saver.save(item);val output=Uri.parse(saved.uri)
                    val after=hash(source,item.bytes);val published=hash(output,before.bytes)
                    val verified=before.sha256==published.sha256&&before.sha256==after.sha256
                    val pending=context.contentResolver.query(output,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use {if(it.moveToFirst()) it.getInt(0) else -1} ?: -1
                    rows.put(JSONObject().put("kind",if(item.type.video) "video" else "photo").put("mime",item.type.mime)
                        .put("bytes",before.bytes).put("width",item.width).put("height",item.height).put("duration_ms",item.durationMs)
                        .put("original_bytes_match",verified).put("source_unchanged",before.sha256==after.sha256)
                        .put("gallery_published",saved.gallery&&pending==0).put("already_saved",saved.alreadySaved))
                }
                val complete=rows.length()==2&&(0 until rows.length()).all {rows.getJSONObject(it).optBoolean("original_bytes_match")&&rows.getJSONObject(it).optBoolean("gallery_published")}
                report.put("samples",rows).put("outcome",if(complete) "real_photo_video_original_copies_verified" else "access_granted_samples_incomplete")
            }
        } catch(error:Exception) {report.put("outcome","blocked_or_failed").put("error_type",error.javaClass.simpleName)
            .put("problem",(error as? StatusException)?.problem?.name)}
        finally {write();store.close()}
        Unit
    }
}
