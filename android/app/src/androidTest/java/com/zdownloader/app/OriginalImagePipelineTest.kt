@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContentUris
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class OriginalImagePipelineTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private lateinit var home:File
    private lateinit var database:ZDatabase
    private lateinit var storage:StorageRepository
    private val created=mutableListOf<Pair<String,Uri>>()
    private val dao get()=database.downloads()

    @Before fun isolatedStorage() {
        home=File(app.cacheDir,"original-image-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val context=object:ContextWrapper(app) {override fun getFilesDir()=home}
        database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        storage=StorageRepository(context,dao)
    }

    @Test fun jpegPngAndWebpKeepExactSourceBytesThroughHiddenPublication()=runBlocking<Unit> {coroutineScope {
        for((extension,encoding) in listOf("jpg" to Bitmap.CompressFormat.JPEG,"png" to Bitmap.CompressFormat.PNG,"webp" to Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val source=image(extension,encoding)
            val selection=selection(source,extension)
            val output=File(home,"output.$extension")
            val control=TransferControl()
            Processor().process(source,null,output,selection,control)
            assertEquals(hash(source),hash(output))
            val id="photo-${UUID.randomUUID()}"
            val row=DownloadEntity(id,"https://example.com/photo","Original photo QA","test",null,null,selection.json(),"$id.$extension",null,id,status="PROCESSING")
            dao.insert(row)
            val hidden=CompletableDeferred<Uri>();val approve=CompletableDeferred<Unit>()
            control.awaitPublicationValidation={
                hidden.complete(Uri.parse(requireNotNull(dao.get(id)?.pendingUri)))
                approve.await()
            }
            val export=async(Dispatchers.IO) {storage.commit(row,output,selection,control)}
            val uri=withTimeout(10_000) {hidden.await()};created+=id to uri
            assertEquals(1,pending(uri))
            assertEquals(hash(source),hash(uri))
            assertEquals(storage.mime(extension,false),app.contentResolver.getType(uri))
            assertEquals(1,dao.validated(id));assertEquals(1,dao.processingStage(id,"SAVING"))
            approve.complete(Unit)
            assertEquals(uri,withTimeout(10_000) {export.await()})
            assertEquals(0,pending(uri))
            assertEquals("COMPLETED",dao.get(id)?.status)
            assertEquals(hash(source),hash(uri))
        }
    }}

    @Test fun corruptTruncatedMismatchedAndOversizedImagesNeverPassValidation()=runBlocking<Unit> {
        val valid=image("png",Bitmap.CompressFormat.PNG)
        val selected=selection(valid,"png")
        val corrupt=File(home,"corrupt.png").apply {writeBytes(ByteArray(128) {42})}
        val truncated=File(home,"truncated.png").apply {writeBytes(valid.readBytes().copyOf(valid.length().toInt()/2))}
        val oversized=File(home,"oversized.png").apply {RandomAccessFile(this,"rw").use {it.setLength(32L*1024*1024+1)}}
        for(file in listOf(corrupt,truncated,oversized)) {
            try {Processor().resumeValidation(file,selected,TransferControl(),null,valid);fail("Accepted ${file.name}")}
            catch(error:MediaException) {assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED,error.code)}
            assertFalse(NativeWork.busy(home))
        }
        val mismatch=selection(valid,"png",32,48)
        try {Processor().resumeValidation(valid,mismatch,TransferControl(),null,valid);fail("Accepted false dimensions")}
        catch(error:MediaException) {assertEquals("image_dimensions",error.validationCheck)}
    }

    @Test fun cancelledImageDoesNotStartCopyAndMergedRecoveryDoesNotRequireSources()=runBlocking<Unit> {
        val source=image("png",Bitmap.CompressFormat.PNG)
        val selection=selection(source,"png")
        val output=File(home,"recovered.png")
        val stopped=TransferControl().apply {stop()}
        try {Processor().process(source,null,output,selection,stopped);fail("Stopped image copied")}
        catch(_:CancellationException) {assertFalse(output.exists())}
        Processor().process(source,null,output,selection,TransferControl())
        val expected=hash(source)
        check(source.delete())
        Processor().resumeValidation(output,Selection.parse(selection.json()),TransferControl(),null,source)
        assertEquals(expected,hash(output))
        assertFalse(NativeWork.busy(home))
    }

    private fun image(extension:String,encoding:Bitmap.CompressFormat):File {
        val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        for(x in 0 until 64) for(y in 0 until 48) bitmap.setPixel(x,y,android.graphics.Color.rgb(x*4,y*5,(x+y)*2))
        return File(home,"source.$extension").also {file->
            try {file.outputStream().use {assertTrue(bitmap.compress(encoding,95,it))}} finally {bitmap.recycle()}
        }
    }

    private fun selection(file:File,extension:String,width:Int=64,height:Int=48):Selection {
        val format=Format.parse(JSONObject().put("format_id","photo").put("url","https://example.com/photo.$extension")
            .put("ext",extension).put("width",width).put("height",height).put("vcodec","none").put("acodec","none")
            .put("protocol","https").put("mediaflow_image","original").put("filesize",file.length()))
        return Selection.image(Media("https://example.com/post","id","Photo","test",null,null,null,listOf(format)),format)
    }

    private fun pending(uri:Uri):Int=app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {check(it.moveToFirst());it.getInt(0)}
    private fun hash(file:File)=file.inputStream().use {hash(it)}
    private fun hash(uri:Uri)=app.contentResolver.openInputStream(uri)!!.use {hash(it)}
    private fun hash(input:java.io.InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }

    @After fun cleanupOnlyOwnedImages()=runBlocking<Unit> {
        for((id,uri) in created) {
            check(ContentUris.parseId(uri)!=97218L)
            val name=app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {if(it.moveToFirst()) it.getString(0) else null}
            if(name!=null) {check(name.startsWith(id)||name.startsWith(".$id.pending"));check(app.contentResolver.delete(uri,null,null)==1)}
        }
        if(::database.isInitialized) database.close()
        if(::home.isInitialized) {check(!NativeWork.busy(home));check(home.deleteRecursively())}
    }
}
