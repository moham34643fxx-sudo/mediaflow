package com.zdownloader.app

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.data.MediaFiles
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.status.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Real ContentResolver/SAF/decoder/MediaStore integration using test-owned bytes only. */
class StatusStorageTest {
    private class FakeGrantAccess(initial:Map<Uri,Int>):PersistableGrantAccess {
        val grants=initial.toMutableMap()
        val releases=mutableListOf<Pair<Uri,Int>>()
        override fun persisted()=grants.map {(uri,flags)->PersistedGrantSnapshot(uri,
            (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)}
        override fun take(uri:Uri,flags:Int) {grants[uri]=(grants[uri] ?: 0) or flags}
        override fun release(uri:Uri,flags:Int) {
            releases+=uri to flags
            val left=(grants[uri] ?: 0) and flags.inv()
            if(left==0) grants.remove(uri) else grants[uri]=left
        }
    }
    @Test fun aliasExportMustNeverDeleteTheOriginalOnDuplicateSave()=runBlocking {
        isolated {
            val source=document(image());val item=scanner.inspect(source)
            outputs+=Uri.parse(saver.save(item).uri)
            val alias=source.buildUpon().appendQueryParameter("synthetic_alias","same-document").build()
            problem(StatusProblem.SAVE_FAILED) {saver.save(item,alias)}
            assertArrayEquals(image(),bytes(source))
        };Unit
    }
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val resolver=app.contentResolver
    private val settings=SettingsRepository(app)
    private val permissions=StatusPermissions(app,settings)
    private val scanner=StatusMediaScanner(app,permissions)
    private val databaseName="status-contract-${UUID.randomUUID()}.db"
    private val store=StatusStore(app,databaseName)
    private val saver=StatusSaveCoordinator(app,permissions,scanner,store,settings)
    private val sources=mutableListOf<Uri>()
    private val outputs=mutableSetOf<Uri>()
    private val prefs=app.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE)
    @Test fun libraryFolderReferencePreventsStatusPermissionRelease() {
        val library=app.getSharedPreferences("mediaflow_file_manager",Context.MODE_PRIVATE)
        val previous=library.getStringSet("trees",emptySet()).orEmpty().toSet()
        try {
            library.edit().putStringSet("trees",setOf(wa.toString())).commit()
            assertTrue(MediaFiles.referencesFolderPermission(app,wa))
            assertTrue("A custom provider's opaque folder relationship is uncertain: retain the grant",MediaFiles.referencesFolderPermission(app,wb))
            val parent=DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents","primary:documents")
            val child=DocumentsContract.buildDocumentUriUsingTree(parent,"primary:documents/music/track.mp3")
            val prefixSibling=DocumentsContract.buildDocumentUriUsingTree(parent,"primary:documents-backup/track.mp3")
            assertTrue(MediaFiles.permissionCovers(parent,child))
            assertFalse("A path-prefix sibling must not be treated as a child grant",MediaFiles.permissionCovers(parent,prefixSibling))
            val opaque=DocumentsContract.buildTreeDocumentUri("cloud.fixture.documents","opaque-root")
            val opaqueChild=DocumentsContract.buildDocumentUriUsingTree(opaque,"9f60411e-cfa1-4885-9172-6ab47ebc7c91")
            assertTrue("Opaque child IDs must not cause a still-needed tree grant to be released",MediaFiles.permissionCovers(opaque,opaqueChild))
            assertFalse("A different provider cannot own this grant",MediaFiles.permissionCovers(parent,opaqueChild))
        } finally {library.edit().putStringSet("trees",previous).commit()}
    }
    @Test fun downloadAndPlaybackReferencesKeepSharedTreePermissionOwned()=runBlocking {
        isolated {
            val application=app.applicationContext as ZApplication
            val dao=application.database.downloads()
            val library=application.database.playbackLibrary()
            val id="permission-owner-${UUID.randomUUID()}"
            val document=document(image(),"playback-owner-${UUID.randomUUID()}")
            val download=DownloadEntity(id,"https://example.com/permission-owner","Permission owner","test",null,null,"{}",
                "Permission owner.mp4",wa.toString(),id,status="PAUSED")
            try {
                dao.insert(download)
                assertTrue("A saved download destination remains a permission owner",permissions.hasOtherPermissionOwner(wa))
                dao.removeHistory(id)
                library.favorite(com.zdownloader.app.playback.PlaybackFavorite(document.toString(),"Playback owner",false,
                    "image/png",null,null,null,0))
                assertTrue("A playback document covered by the tree remains an owner",permissions.hasOtherPermissionOwner(wa))
                library.unfavorite(document.toString())
                assertFalse("No stale fixture reference remains",permissions.hasOtherPermissionOwner(wa))
            } finally {
                runCatching {library.unfavorite(document.toString())}
                runCatching {dao.removeHistory(id)}
            }
        };Unit
    }
    @Test fun failedImportDoesNotReleaseAnAlreadyPersistedGrant()=runBlocking {
        isolated {
            val uri=document(image(),"existing-persisted-${UUID.randomUUID()}")
            val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
            val fake=FakeGrantAccess(mapOf(uri to read))
            val isolatedPermissions=StatusPermissions(app,settings,fake)
            val denied=Uri.parse("content://unrelated.invalid/not-readable")
            try {
                try {isolatedPermissions.replaceImports(listOf(uri,denied));fail("The second URI is not readable")}
                catch(error:StatusException) {assertEquals(StatusProblem.ACCESS_REVOKED,error.problem)}
                assertEquals("The grant that existed before replacement must remain",read,fake.grants[uri])
                assertTrue("Rollback must not release a pre-existing permission",fake.releases.isEmpty())
                assertTrue("The previous persisted import selection remains unchanged",isolatedPermissions.importedFiles().isEmpty())
            } finally {prefs.edit().remove("import_files").commit()}
        };Unit
    }
    private fun tree(id:String)=DocumentsContract.buildTreeDocumentUri(StatusFixtureProvider.AUTH,id)
    private val wa get()=tree(StatusFixtureProvider.WA)
    private val wb get()=tree(StatusFixtureProvider.WB)
    private suspend fun grant(revoke:Boolean=false) {
        // Start the test-provider owner from the system foreground path. Starting
        // an Activity from the target's background Context is OEM-policy dependent.
        val launch="am start -W -n com.zdownloader.app.test/com.zdownloader.app.StatusFixtureGrantActivity --ez revoke $revoke"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(launch))
            .bufferedReader().use {output->check(!output.readText().contains("Error:")) {"Fixture grant Activity failed to launch"}}
        withTimeout(5000) {while(permissions.readable(wa)==revoke) delay(25)}
    }
    private fun image():ByteArray {
        val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff24e3d1.toInt())
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray()}
    }
    private fun document(bytes:ByteArray,name:String="fixture-${UUID.randomUUID()}",root:Uri=wa):Uri {
        val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
        val uri=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",name)!!
        sources+=uri;resolver.openOutputStream(uri,"w")!!.use {it.write(bytes)};return uri
    }
    private fun bytes(uri:Uri)=resolver.openInputStream(uri)!!.use {it.readBytes()}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private suspend fun problem(expected:StatusProblem,action:suspend ()->Unit) {
        try {action();fail("Expected $expected")} catch(e:StatusException) {assertEquals(expected,e.problem)}
    }
    private suspend fun isolated(action:suspend ()->Unit) {
        val old=prefs.all.toMap();val destination=settings.current().destination
        val playbackPrefs=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE)
        val oldPlayback=playbackPrefs.all.toMap()
        try {
            // Do not replace a real source through production choose(): that
            // intentionally releases the previous source grant. Isolate the
            // preference namespace first and restore it after synthetic work.
            prefs.edit().clear().commit()
            playbackPrefs.edit().clear().commit()
            com.zdownloader.app.playback.PlaybackGrantReferences.update(emptySet())
            grant();settings.text("destination","");action()
        }
        finally {
            grant()
            com.zdownloader.app.playback.PlaybackGrantReferences.update(emptySet())
            outputs.forEach {runCatching {resolver.delete(it,null,null)}}
            sources.asReversed().forEach {runCatching {DocumentsContract.deleteDocument(resolver,it)}}
            val editor=prefs.edit().clear();old.forEach {(key,value)->if(value is String) editor.putString(key,value)};editor.commit()
            val playbackEditor=playbackPrefs.edit().clear();oldPlayback.forEach {(key,value)->
                when(value) {
                    is String->playbackEditor.putString(key,value)
                    is Boolean->playbackEditor.putBoolean(key,value)
                    is Int->playbackEditor.putInt(key,value)
                    is Long->playbackEditor.putLong(key,value)
                    is Float->playbackEditor.putFloat(key,value)
                }
            };playbackEditor.commit()
            settings.text("destination",destination ?: "");store.close();app.deleteDatabase(databaseName)
        }
    }
    @Test fun rejectedIndexWritePreservesVerifiedOutputAndRecoveryJournal()=runBlocking {
        isolated {
            val source=document(image());val item=scanner.inspect(source)
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_reject_saved BEFORE INSERT ON saved BEGIN SELECT RAISE(ABORT, 'synthetic index failure'); END")
            try {
                try {saver.save(item);fail("Failed index writes must not report success")}
                catch(_:android.database.SQLException) {}
                val pending=store.pending().single()
                val output=Uri.parse(pending.second.getString("uri"));outputs+=output
                assertArrayEquals(image(),bytes(output));assertArrayEquals(image(),bytes(source))
            } finally {store.writableDatabase.execSQL("DROP TRIGGER fixture_reject_saved")}
            saver.recover()
            assertNotNull(store.forSource(item.key));assertTrue(store.pending().isEmpty())
        };Unit
    }
    @Test fun rejectedDocumentExportIndexWritePreservesVerifiedBytesAndRecoversLater()=runBlocking {
        isolated {
            val source=document(image());val target=document(byteArrayOf(1,2,3,4),"explicit-export-${UUID.randomUUID()}")
            val item=scanner.inspect(source)
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_reject_saved BEFORE INSERT ON saved BEGIN SELECT RAISE(ABORT, 'synthetic index failure'); END")
            try {
                try {saver.save(item,target);fail("A failed index write must not report success")}
                catch(_:android.database.SQLException) {}
                val pending=store.pending().single()
                assertEquals(target.toString(),pending.second.getString("uri"))
                outputs+=target
                assertArrayEquals(image(),bytes(target));assertArrayEquals(image(),bytes(source))
            } finally {store.writableDatabase.execSQL("DROP TRIGGER fixture_reject_saved")}
            saver.recover()
            assertEquals(target.toString(),store.forSource(item.key)!!.uri)
            assertTrue(store.pending().isEmpty())
            assertArrayEquals(image(),bytes(target));assertArrayEquals(image(),bytes(source))
        };Unit
    }
    @Test fun narrowFolderValidationEmptyMixedAndBusinessIsolation()=runBlocking {
        isolated {
            problem(StatusProblem.WRONG_FOLDER) {permissions.choose(StatusSource.WHATSAPP,tree(StatusFixtureProvider.WRONG))}
            problem(StatusProblem.WRONG_SOURCE) {permissions.choose(StatusSource.WHATSAPP,wb)}
            permissions.choose(StatusSource.WHATSAPP,wa);permissions.choose(StatusSource.BUSINESS,wb)
            assertTrue(scanner.scan(StatusSource.WHATSAPP).items.isEmpty())
            val photo=document(image(),"unknown-photo")
            document(byteArrayOf(1,2,3),"unsupported.txt")
            val video=document(instrumentation.context.assets.open("status-original.mp4").use {it.readBytes()},"video-original")
            document(image(),"business-photo",wb)
            val result=scanner.scan(StatusSource.WHATSAPP)
            assertEquals(2,result.items.size);assertEquals(1,result.skipped)
            assertEquals(setOf(photo.toString(),video.toString()),result.items.map {it.uri}.toSet())
            assertNull(result.items.first {it.uri==photo.toString()}.bytes)
            assertEquals(1,scanner.scan(StatusSource.BUSINESS).items.size)
            // A reused ancestor grant is represented by a document within its
            // original tree, not by manufacturing a new ungranted tree URI.
            permissions.choose(StatusSource.WHATSAPP,DocumentsContract.buildDocumentUriUsingTree(wa,StatusFixtureProvider.WA))
            assertEquals(2,scanner.scan(StatusSource.WHATSAPP).items.size)
            grant(true)
            problem(StatusProblem.ACCESS_REVOKED) {scanner.scan(StatusSource.WHATSAPP)}
        };Unit
    }
    @Test fun photoAndVideoOriginalBytesTypedGalleryAndConcurrentDeduplication()=runBlocking {
        isolated {
            for((data,video) in listOf(image() to false,instrumentation.context.assets.open("status-original.mp4").use {it.readBytes()} to true)) {
                val source=document(data)
                val item=scanner.inspect(source)
                assertEquals(video,item.type.video)
                val saves=coroutineScope {listOf(async {saver.save(item)},async {saver.save(item)}).awaitAll()}
                val output=Uri.parse(saves.first().uri);outputs+=output
                assertEquals(1,saves.map {it.uri}.distinct().size)
                assertEquals(1,saves.count {it.alreadySaved})
                assertEquals(sha(data),sha(bytes(output)));assertArrayEquals(data,bytes(source))
                assertTrue(output.toString().contains(if(video) "/video/" else "/images/"))
                resolver.query(output,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.MIME_TYPE),null,null,null)!!.use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
                    assertEquals(if(video) "Movies/MediaFlow/WhatsApp Statuses/" else "Pictures/MediaFlow/WhatsApp Statuses/",it.getString(1))
                    assertEquals(item.type.mime,it.getString(2))
                }
                val renamedCopy=scanner.inspect(document(data,"renamed-${UUID.randomUUID()}"))
                assertTrue(saver.save(renamedCopy).alreadySaved)
                assertEquals(output.toString(),saver.savedUri(item))
            }
            assertTrue(store.pending().isEmpty())
        };Unit
    }
    @Test fun deletedAndTruncatedSourcesNeverPublishAndRecoveryRemovesOnlyPending()=runBlocking {
        isolated {
            val source=document(image());val item=scanner.inspect(source)
            DocumentsContract.deleteDocument(resolver,source)
            problem(StatusProblem.SOURCE_UNAVAILABLE) {saver.save(item)}
            val truncated=document(image().dropLast(12).toByteArray())
            // Native bounds can still be readable; the staged PNG end marker is mandatory.
            try {val bad=scanner.inspect(truncated);problem(StatusProblem.CORRUPT) {saver.save(bad)}}
            catch(e:StatusException) {assertEquals(StatusProblem.CORRUPT,e.problem)}
            val id=UUID.randomUUID().toString();val data=image()
            val output=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,".mediaflow-fixture-$id.png")
                put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.IS_PENDING,1)
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/MediaFlow/WhatsApp Statuses/")
            })!!;outputs+=output
            resolver.openOutputStream(output)!!.use {it.write(data)}
            store.pending(id,JSONObject().put("uri",output.toString()).put("gallery",true).put("hash",sha(data))
                .put("bytes",data.size).put("source_key","fixture-$id").put("mime","image/png"))
            saver.recover()
            resolver.query(output,arrayOf(MediaStore.MediaColumns._ID),null,null,null)!!.use {assertFalse(it.moveToFirst())}
            assertTrue(store.pending().isEmpty())
        };Unit
    }
    @Test fun publishedOriginalRecoveredIntoIndexWithoutDeletingVisibleCopy()=runBlocking {
        isolated {
            val id=UUID.randomUUID().toString();val data=image()
            val output=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"MediaFlow-fixture-$id.png");put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/MediaFlow/WhatsApp Statuses/");put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!;outputs+=output
            resolver.openOutputStream(output)!!.use {it.write(data)}
            resolver.update(output,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            store.pending(id,JSONObject().put("uri",output.toString()).put("gallery",true).put("hash",sha(data))
                .put("bytes",data.size).put("source_key","fixture-$id").put("mime","image/png"))
            saver.recover()
            assertArrayEquals(data,bytes(output));assertEquals(output.toString(),store.forSource("fixture-$id")!!.uri)
            assertTrue(store.pending().isEmpty())
        };Unit
    }
    @Test fun revokedProviderKeepsStatusRecoveryJournalUntilAuthorizedRetry()=runBlocking {
        isolated {
            val data=image();val uri=document(data);val id="permission-${UUID.randomUUID()}"
            store.pending(id,JSONObject().put("uri",uri.toString()).put("gallery",false).put("hash",sha(data))
                .put("bytes",data.size).put("source_key","fixture-$id").put("mime","image/png"))
            grant(true)
            saver.recover()
            assertEquals("Uncertain provider access must retain its retry journal",id,store.pending().single().first)
            grant()
            assertTrue("The fixture owner must restore the tree grant",permissions.readable(wa))
            assertTrue("The restored prefix grant must cover the saved document",permissions.readable(uri))
            val restored=resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)
                ?: throw AssertionError("The restored URI query must be available")
            restored.use {assertTrue("The saved provider row must still exist",it.moveToFirst())}
            assertArrayEquals("The provider bytes remain available for recovery",data,bytes(uri))
            saver.recover()
            assertTrue("A verified provider retry should clear its journal: ${store.pending()}",store.pending().isEmpty())
            assertArrayEquals(data,bytes(uri))
            assertEquals(uri.toString(),store.forSource("fixture-$id")!!.uri)
        };Unit
    }
}
