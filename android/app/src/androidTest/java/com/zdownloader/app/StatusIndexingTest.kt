package com.zdownloader.app

import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Tests the OEM indexer's hidden-folder semantics with app-owned media only. */
class StatusIndexingTest {
    @Test fun hiddenDirectoryDoesNotProvideOrdinaryMediaStoreImageDiscovery()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver
        val root=File(context.externalMediaDirs.first(),"status-index-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        val hidden=File(root,".Statuses").apply {mkdirs()}
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff24e3d1.toInt())}
        val indexed=mutableListOf<Uri>();val rows=JSONArray()
        try {
            for((directory,isHidden) in listOf(root to false,hidden to true)) {
                val file=File(directory,"fixture.png")
                file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                val result=CompletableDeferred<Uri?>()
                MediaScannerConnection.scanFile(context,arrayOf(file.absolutePath),arrayOf("image/png")) {_,uri->result.complete(uri)}
                val uri=withTimeout(15000) {result.await()}
                if(uri!=null) indexed+=uri
                val imageCount=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA}=?",arrayOf(file.absolutePath),null)!!.use {it.count}
                rows.put(JSONObject().put("app_owned_hidden_fixture",isHidden).put("images_collection_count",imageCount)
                    .put("scanner_returned_uri",uri!=null))
                assertEquals(if(isHidden) 0 else 1,imageCount)
            }
            val folder=File(context.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"status-indexing.json").writeText(JSONObject().put("scope","App-owned visible and hidden folders; no WhatsApp content accessed")
                .put("sdk",android.os.Build.VERSION.SDK_INT).put("results",rows).toString(2))
        } finally {
            bitmap.recycle();indexed.forEach {runCatching {resolver.delete(it,null,null)}};root.deleteRecursively()
        };Unit
    }
}
