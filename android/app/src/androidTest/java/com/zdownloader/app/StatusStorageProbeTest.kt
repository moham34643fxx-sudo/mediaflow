package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Human-operated SAF research. No shell grants or WhatsApp UI/content automation.
 * User chooses the folder and then only the two authorized non-sensitive samples. */
class StatusStorageProbeTest {
    @Test fun userChosenStatusFolderAndSamples()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val report=JSONObject().put("sdk",android.os.Build.VERSION.SDK_INT).put("target_package",context.packageName)
            .put("access_method","user-operated ACTION_OPEN_DOCUMENT_TREE; ContentResolver; read-only persisted grant")
        val folder=File(context.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        fun write() {File(folder,"status-access-probe.json").writeText(report.toString(2))}
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                val chosen=CompletableDeferred<Uri?>()
                var treeLauncher:ActivityResultLauncher<Uri?>?=null
                scenario.onActivity {activity->
                    treeLauncher=activity.activityResultRegistry.register("status-research-tree",ActivityResultContracts.OpenDocumentTree()) {chosen.complete(it)}
                    val hint=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Android/media/com.whatsapp/WhatsApp/Media")
                    treeLauncher!!.launch(hint)
                }
                val tree=withTimeoutOrNull(600000) {chosen.await()}
                if(tree==null) {report.put("outcome","picker_cancelled_or_timed_out");write();return@use}
                val root=DocumentFile.fromTreeUri(context,tree)
                val statuses=when(root?.name) {".Statuses","Statuses"->root;"Media"->root.findFile(".Statuses");else->null}
                if(statuses==null||!statuses.isDirectory) {report.put("outcome","selected_folder_does_not_expose_statuses");write();return@use}
                context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                report.put("persisted_read",context.contentResolver.persistedUriPermissions.any {it.uri==tree&&it.isReadPermission})
                val id=DocumentsContract.getDocumentId(statuses.uri)
                report.put("verified_location",if(id=="primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses")
                    "Android/media/com.whatsapp/WhatsApp/Media/.Statuses" else "nonstandard explicitly selected status directory; identifiers withheld")
                context.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE).edit().putString("whatsapp",tree.toString()).commit()
                report.put("outcome","folder_access_granted");write()

                val selected=CompletableDeferred<List<Uri>>()
                var samplesLauncher:ActivityResultLauncher<Uri?>?=null
                val contract=object:ActivityResultContract<Uri?,List<Uri>>() {
                    override fun createIntent(context:Context,input:Uri?)=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*"))
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                        .putExtra(DocumentsContract.EXTRA_INITIAL_URI,input)
                    override fun parseResult(resultCode:Int,intent:Intent?):List<Uri> {
                        if(resultCode!=android.app.Activity.RESULT_OK||intent==null) return emptyList()
                        return (intent.clipData?.let {clip->(0 until minOf(clip.itemCount,2)).map {clip.getItemAt(it).uri}}
                            ?: listOfNotNull(intent.data)).distinct()
                    }
                }
                scenario.onActivity {activity->
                    samplesLauncher=activity.activityResultRegistry.register("status-research-samples",contract) {selected.complete(it)}
                    samplesLauncher!!.launch(statuses.uri)
                }
                val samples=withTimeoutOrNull(600000) {selected.await()}.orEmpty()
                val rows=JSONArray()
                for(uri in samples) {
                    val item=JSONObject().put("mime",context.contentResolver.getType(uri))
                    context.contentResolver.openFileDescriptor(uri,"r")!!.use {pfd->item.put("readable",true).put("size",pfd.statSize)}
                    val mime=context.contentResolver.getType(uri).orEmpty()
                    if(mime.startsWith("image/")) {
                        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        context.contentResolver.openInputStream(uri)!!.use {BitmapFactory.decodeStream(it,null,options)}
                        item.put("width",options.outWidth).put("height",options.outHeight)
                        val decoded=BitmapFactory.Options().apply {inSampleSize=16}
                        val bitmap=context.contentResolver.openInputStream(uri)!!.use {BitmapFactory.decodeStream(it,null,decoded)}
                        item.put("decodable",bitmap!=null);bitmap?.recycle()
                    } else if(mime.startsWith("video/")) {
                        val retriever=MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context,uri)
                            item.put("width",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
                                .put("height",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
                                .put("duration_ms",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                            val frame=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,96,96)
                            item.put("decodable",frame!=null);frame?.recycle()
                        } finally {retriever.release()}
                    }
                    rows.put(item)
                }
                report.put("selected_samples",rows).put("outcome",if(rows.length()==2) "folder_and_two_selected_samples_read" else "folder_access_granted_samples_incomplete")
                treeLauncher?.unregister();samplesLauncher?.unregister()
            }
        } catch(error:Exception) {report.put("outcome","blocked_or_failed").put("error_type",error.javaClass.simpleName)}
        finally {write()}
        Unit
    }
}
