package com.zdownloader.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class SmartMediaIntegrationTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private suspend fun tree():Uri {
        val uri=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        app.startActivity(Intent().setComponent(android.content.ComponentName("com.zdownloader.app.test","com.zdownloader.app.TestDocumentsGrantActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(10000) {while(app.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)}
        return uri
    }
    private fun media():Media {
        val f=Format.parse(JSONObject().put("format_id","v720").put("url","https://example.com/v.mp4").put("ext","mp4").put("height",720).put("vcodec","h264").put("acodec","aac"))
        return Media("https://example.com/source","fixture-${UUID.randomUUID()}","Fixture","Youtube",null,null,null,listOf(f))
    }
    @Test fun safCopyRenameCollisionAndDeletePreserveOriginalBytes()=runBlocking {
        val tree=tree();val directory=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,tree)!!
        val first=directory.createFile("video/mp4","manager-${UUID.randomUUID()}.mp4")!!
        val data=ByteArray(128*1024) {(it%239).toByte()};app.contentResolver.openOutputStream(first.uri)!!.use {it.write(data)}
        var copy:MediaFile?=null
        try {
            val source=app.mediaFiles.metadata(first.uri.toString()).copy(tree=tree.toString())
            val copied=app.mediaFiles.copied(source,tree.toString());copy=copied
            assertNotEquals(source.uri,copied.uri);assertNotEquals(source.name,copied.name)
            app.contentResolver.openInputStream(Uri.parse(copied.uri))!!.use {assertArrayEquals(data,it.readBytes())}
            try {app.mediaFiles.renamed(copied,source.name);fail("Must not overwrite another document")} catch(_:IllegalArgumentException) {}
            val renamed=app.mediaFiles.renamed(copied,"renamed-${UUID.randomUUID()}.mp4");copy=renamed
            assertTrue(renamed.name.startsWith("renamed-"));assertTrue(app.mediaFiles.accessible(renamed.uri))
            assertTrue(app.mediaFiles.documents(tree.toString()).any {it.uri==renamed.uri})
            app.mediaFiles.deleted(renamed);copy=null
            app.contentResolver.openInputStream(first.uri)!!.use {assertArrayEquals(data,it.readBytes())}
        } finally {copy?.let {runCatching {DocumentsContract.deleteDocument(app.contentResolver,Uri.parse(it.uri))}};first.delete()}
    }
    @Test fun duplicateChecksDistinguishMissingFailedActiveAndDifferentQuality()=runBlocking {
        val tree=tree();val directory=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,tree)!!
        val document=directory.createFile("video/mp4","duplicate-${UUID.randomUUID()}.mp4")!!
        app.contentResolver.openOutputStream(document.uri)!!.use {it.write(byteArrayOf(1,2,3))}
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val repo=DownloadRepository(dao,SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val m=media();val selection=Selection.video(m,m.formats.first())
            val id=repo.create(m,selection,"Fixture")
            assertEquals(id,repo.create(m.copy(url="https://youtu.be/alias"),selection,"Alias"))
            assertTrue(repo.duplicateReview(m).active.any {it.id==id})
            dao.state(id,"FAILED");assertFalse(repo.duplicateReview(m).found)
            dao.insert(dao.get(id)!!.copy(id="completed",status="COMPLETED",uri=document.uri.toString()))
            assertTrue(repo.duplicateReview(m).open?.uri==document.uri.toString())
            try {repo.create(m,selection,"Repeat",false);fail("Completed file must require a decision")} catch(e:DuplicateDownloadException) {assertNotNull(e.review.open)}
            val other=selection.copy(primary=selection.primary.copy(id="v1080",height=1080,payload=JSONObject(selection.primary.payload).put("format_id","v1080").put("height",1080).toString()))
            assertNotEquals(id,repo.create(m,other,"1080p",true))
            document.delete();assertNull(repo.duplicateReview(m).open)
        } finally {database.close();document.delete()}
    }
    @Test fun supportedDocumentMoveAndPermissionRevocationDoNotLoseSourceData()=runBlocking {
        val root=tree();val rootDoc=DocumentsContract.buildDocumentUriUsingTree(root,"root")
        val folder=DocumentsContract.createDocument(app.contentResolver,rootDoc,DocumentsContract.Document.MIME_TYPE_DIR,"manager-folder-${UUID.randomUUID()}")!!
        val folderId=DocumentsContract.getDocumentId(folder);val target=DocumentsContract.buildTreeDocumentUri(root.authority!!,folderId)
        app.contentResolver.call(root,"grantTree",folderId,null)
        val source=DocumentsContract.createDocument(app.contentResolver,rootDoc,"video/mp4","move-${UUID.randomUUID()}.mp4")!!
        app.contentResolver.openOutputStream(source)!!.use {it.write(byteArrayOf(9,8,7,6))}
        var moved:MediaFile?=null
        try {
            val row=app.mediaFiles.documents(root.toString()).first {it.uri==source.toString()}
            assertTrue(row.movable);moved=app.mediaFiles.moved(row,target.toString())
            assertTrue(app.mediaFiles.accessible(moved.uri));assertFalse(app.mediaFiles.accessible(source.toString()))
            app.contentResolver.call(root,"revokeTree",folderId,null)
            app.contentResolver.call(root,"revokeTree","root",null)
            assertFalse(app.mediaFiles.accessible(moved.uri))
            try {app.mediaFiles.renamed(moved,"revoked.mp4");fail("Revoked access must not allow writes")} catch(_:Exception) {}
            app.contentResolver.call(root,"grantTree",folderId,null)
            app.contentResolver.call(root,"grantTree","root",null)
            app.contentResolver.openInputStream(Uri.parse(moved.uri))!!.use {assertArrayEquals(byteArrayOf(9,8,7,6),it.readBytes())}
        } finally {
            app.contentResolver.call(root,"grantTree",folderId,null)
            app.contentResolver.call(root,"grantTree","root",null)
            moved?.let {runCatching {DocumentsContract.deleteDocument(app.contentResolver,Uri.parse(it.uri))}}
            runCatching {DocumentsContract.deleteDocument(app.contentResolver,source)};DocumentsContract.deleteDocument(app.contentResolver,folder)
        }
    }
    @Test fun playlistDuplicateReviewIsOneDecisionAndPreservesExplicitReplay()=runBlocking {
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val settings=SettingsRepository(app)
            val downloads=DownloadRepository(dao,settings,app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val playlists=PlaylistRepository(dao,settings,downloads,app.coordinator,app.mediaFiles)
            val media=media();val selection=Selection.video(media,media.formats.first())
            downloads.create(media,selection,"Already queued")
            val existing=PlaylistEntry("existing",media.url,1,"Existing",mediaId=media.id,extractor=media.source)
            val fresh=PlaylistEntry("new","https://example.com/new",2,"New",mediaId="new-id",extractor="Youtube")
            val entries=listOf(existing,fresh);val page=PlaylistPage(media.url,"Fixture",null,null,2,entries,2,false)
            assertEquals(setOf("existing"),playlists.duplicates(entries).active)
            val skipped=playlists.submit("skip",page,entries,PlaylistPolicy(),true,1,true)
            assertEquals(listOf(2),dao.batchItems(skipped).map {it.playlistPosition})
            val replay=playlists.submit("again",page,entries,PlaylistPolicy(height=1080),true,1,false)
            assertEquals(2,dao.batchItems(replay).size)
            val different=playlists.submit("different-quality",page,listOf(existing),PlaylistPolicy(height=480),true,1,true)
            assertEquals("A different selected resolution must not be silently skipped",1,dao.batchItems(different).size)
        } finally {database.close()}
    }
    @Test fun futureSchedulesCannotBeClaimedByOrdinaryQueueAndConstraintsRemainAfterRelease()=runBlocking {
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val repo=DownloadRepository(dao,SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val m=media();val selection=Selection.video(m,m.formats.first());val at=System.currentTimeMillis()+60*60_000
            val id=repo.create(m,selection,"Scheduled",plan=DownloadPlan(at,DownloadNetwork.WIFI,true))
            assertEquals("SCHEDULED",dao.get(id)!!.status);assertNull(dao.next(Long.MAX_VALUE));assertEquals(0,dao.claim(id))
            assertEquals(0,dao.releaseSchedule(id,false,at-1));assertEquals(1,dao.releaseSchedule(id,false,at))
            assertNull(dao.next(at,wifi=false,charging=true));assertNull(dao.next(at,wifi=true,charging=false))
            assertEquals(id,dao.next(at,wifi=true,charging=true)?.id)
            val constraints=app.plannedDownloads.constraintsReport(dao.get(id)!!)
            assertTrue(constraints.getBoolean("charging"));assertEquals("CONNECTED",constraints.getString("network"))
            assertEquals(1,dao.startScheduledNow(id,false))
            val immediate=dao.get(id)!!
            assertEquals(0L,immediate.scheduledAt);assertFalse(immediate.requiresCharging);assertEquals("ANY",immediate.scheduleNetwork)
        } finally {database.close()}
    }
    @Test fun storageCleanupPreservesPausedFailedAndNativeBusyDirectories()=runBlocking {
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        val dao=database.downloads();val manager=StorageManagement(app,dao,app.mediaFiles,SettingsRepository(app))
        val ids=mutableListOf<String>();val directories=mutableListOf<File>();var lease:NativeWork.Lease?=null
        val outside=File(app.cacheDir,"cleanup-protected-${UUID.randomUUID()}").apply {writeBytes(byteArrayOf(4,3,2,1))}
        try {
            for(status in listOf("PAUSED","FAILED","SCHEDULED","COMPLETED","CANCELLED")) {
                val id="cleanup-${UUID.randomUUID()}";ids+=id
                dao.insert(DownloadEntity(id,"https://example.com/cleanup","Fixture","Test",null,null,"{}","fixture.mp4",null,id,status=status))
                directories+=app.storage.temporary(id).apply {File(this,"primary.part").writeBytes(ByteArray(37))}
            }
            lease=NativeWork.acquire(directories.last())
            android.system.Os.symlink(outside.absolutePath,File(directories[3],"outside-link").absolutePath)
            val diagnosticFolder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            val storageAlias=File(File(app.filesDir,"transfers"),ids[4])
            val diagnostic=JSONObject().put("native_busy_before",NativeWork.busy(directories[4]))
                .put("status_before",dao.get(ids[4])?.status).put("lease_path",directories[4].absolutePath)
                .put("lease_canonical_path",directories[4].canonicalPath).put("storage_root",app.filesDir.absolutePath)
                .put("storage_root_canonical",app.filesDir.canonicalPath)
                .put("native_busy_from_storage_alias_before",NativeWork.busy(storageAlias))
            val removedBytes=manager.cleanTemporary(ids.toSet())
            diagnostic.put("native_busy_after",NativeWork.busy(directories[4]))
                .put("native_busy_from_storage_alias_after",NativeWork.busy(storageAlias)).put("removed_bytes",removedBytes)
                .put("directory_exists_after",org.json.JSONArray(directories.map {it.exists()}))
                .put("native_directory_after",directories[4].absolutePath)
            File(diagnosticFolder,"storage-cleanup-diagnostic.json").writeText(diagnostic.toString(2))
            assertTrue("Paused temporary data must remain recoverable",directories[0].exists())
            assertTrue("Failed temporary data must remain recoverable",directories[1].exists())
            assertTrue("Scheduled temporary data must remain available",directories[2].exists())
            assertFalse("Completed temporary data must be cleaned",directories[3].exists())
            assertTrue("A native lease must protect the same directory when addressed through the storage-root alias",NativeWork.busy(storageAlias))
            assertTrue("Native-busy temporary data must remain until its lease is released",directories[4].exists())
            assertArrayEquals(byteArrayOf(4,3,2,1),outside.readBytes())
        } finally {lease?.release();directories.getOrNull(3)?.let {File(it,"outside-link").delete()};directories.forEach {it.deleteRecursively()};outside.delete();database.close()}
    }
    @Test fun storageTotalsCountActualBytesIncludingUnknownTypesAndMissingRecords()=runBlocking {
        val root=tree();val folder=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,root)!!
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build();val docs=mutableListOf<androidx.documentfile.provider.DocumentFile>()
        try {
            val dao=database.downloads()
            for((index,item) in listOf("video/mp4" to 17,"audio/mp4" to 13,"application/octet-stream" to 11).withIndex()) {
                val doc=folder.createFile(item.first,"storage-total-${UUID.randomUUID()}")!!;docs+=doc
                app.contentResolver.openOutputStream(doc.uri)!!.use {it.write(ByteArray(item.second))}
                dao.insert(DownloadEntity("usage-$index","https://example.com/usage","Fixture","Test",null,null,"{}","fixture",root.toString(),"usage-$index",status="COMPLETED",uri=doc.uri.toString()))
            }
            dao.insert(DownloadEntity("missing","https://example.com/missing","Missing","Test",null,null,"{}","missing",null,"missing",status="COMPLETED"))
            val usage=StorageManagement(app,dao,app.mediaFiles,SettingsRepository(app)).usage()
            assertEquals(17L,usage.video);assertEquals(13L,usage.audio);assertEquals(11L,usage.other);assertEquals(41L,usage.total);assertEquals(3,usage.counted);assertEquals(1,usage.missing)
        } finally {docs.forEach {it.delete()};database.close()}
    }
    @Test fun actualVideoOnlyAndSubtitleProcessingRetainSelectedVideoAndWriteMetadata()=runBlocking {
        val dir=app.storage.temporary("advanced-${UUID.randomUUID()}")
        try {
            val primary=File(dir,"source.mp4");InstrumentationRegistry.getInstrumentation().context.assets.open("status-original.mp4").use {source->primary.outputStream().use {source.copyTo(it)}}
            val info=com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(primary.path).mediaInformation!!
            val stream=info.streams.first {it.type=="video"}
            val format=Format.parse(JSONObject().put("format_id","actual").put("url","https://example.com/source.mp4").put("ext","mp4").put("vcodec",stream.codec).put("acodec",if(info.streams.any {it.type=="audio"}) "aac" else "none"))
            val subtitle=File(dir,"captions.vtt").apply {writeText("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nMediaFlow subtitle verification\n")}
            for(container in listOf("mp4","mkv")) {
                val track=SubtitleTrack("en","vtt",JSONObject().put("url","https://example.com/sub.vtt").toString())
                val selection=Selection(format,output=container,videoOnly=true,subtitle=track,creator="Verification Creator")
                val output=File(dir,"verified.$container")
                Processor().process(primary,null,output,selection,TransferControl(),title="Verification title",subtitle=subtitle)
                val result=com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(output.path).mediaInformation!!
                assertTrue(result.streams.any {it.type=="video"&&it.codec==stream.codec});assertFalse(result.streams.any {it.type=="audio"});assertTrue(result.streams.any {it.type=="subtitle"})
                assertEquals("Verification title",result.allProperties.getJSONObject("format").getJSONObject("tags").getString("title"))
            }
        } finally {NativeWork.awaitIdle(dir);dir.deleteRecursively()}
    }
    @Test fun boundedThumbnailExportAndCaptionTransferUseRealSafAndSharedTransfer()=runBlocking {
        val root=tree();val document=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,root)!!.createFile("image/png","thumbnail-${UUID.randomUUID()}.png")!!
        val directory=app.storage.temporary("assets-${UUID.randomUUID()}")
        val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.CYAN)}
        val image=java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val caption="WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nShared transfer subtitle\n".toByteArray()
        var body=image
        val client=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()}.build()
        try {
            val asset=app.mediaAssets.thumbnail("https://mediaflow-assets.invalid/thumbnail.png",client)
            assertEquals("png",asset.extension);app.mediaAssets.saveThumbnail(document.uri,asset)
            app.contentResolver.openInputStream(document.uri)!!.use {assertArrayEquals(image,it.readBytes())}
            body=ByteArray(ThumbnailData.MAX_BYTES+1)
            try {app.mediaAssets.thumbnail("https://mediaflow-assets.invalid/large.png",client);fail("Oversized thumbnail must be rejected")} catch(_:IllegalArgumentException) {}
            body="<html>not an image</html>".toByteArray()
            try {app.mediaAssets.thumbnail("https://mediaflow-assets.invalid/not-image",client);fail("HTML is not a thumbnail")} catch(_:java.io.IOException) {}
            body=caption
            val format=Format.parse(JSONObject().put("format_id","qa-subtitle").put("url","https://mediaflow-assets.invalid/subtitle.vtt").put("protocol","https").put("ext","vtt").put("vcodec","none").put("acodec","none"))
            val target=File(directory,"captions.vtt")
            Transfer(app.extractor,client).stream(format,target,"qa-assets",TransferControl()) {bytes,_->require(bytes<=2*1024*1024)}
            assertArrayEquals(caption,target.readBytes());assertTrue(File(target.path+".complete").exists())
        } finally {document.delete();directory.deleteRecursively();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
    }
    @Test fun scheduledWorkActuallyDownloadsValidatesAndPublishesThroughExistingPipeline():Unit=runBlocking {
        val token=UUID.randomUUID().toString();var id:String?=null
        try {
            val media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4?mediaflow_qa=$token","planned-$token",forceRefresh=true)
            val selection=Selection.video(media,media.videoOptions().first())
            id=app.repository.create(media,selection,"MediaFlow scheduled QA $token",plan=DownloadPlan())
            assertEquals("SCHEDULED",app.repository.dao.get(id)!!.status)
            app.plannedDownloads.enqueue(id)
            withTimeout(180000) {while(app.repository.dao.get(id)?.status !in setOf("COMPLETED","FAILED","CANCELLED")) delay(100)}
            val completed=app.repository.dao.get(id)!!
            assertEquals("COMPLETED",completed.status);assertTrue(app.mediaFiles.accessible(completed.uri));assertTrue(completed.outputSize!!>0)
            withTimeoutOrNull(15000) {while(app.coordinator.running) delay(50)}
        } finally {id?.let {owned->
            val row=app.repository.dao.get(owned)
            if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(owned);app.coordinator.stopAndAwait(owned)}
            app.plannedDownloads.cancel(owned);app.repository.remove(owned)
        }}
    }
}
