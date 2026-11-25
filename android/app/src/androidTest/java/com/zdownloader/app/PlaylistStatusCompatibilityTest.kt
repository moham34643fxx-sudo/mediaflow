package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.status.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Exercise the unchanged Status Saver with only isolated test-provider bytes. */
class PlaylistStatusCompatibilityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun statusPhotoVideoOriginalCopiesAndDedupRemainIndependentOfPlaylistSchema()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val settings=SettingsRepository(app);val old=settings.current()
        val prefs=app.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE)
        val saved=prefs.all.toMap()
        val permissions=StatusPermissions(app,settings)
        val scanner=StatusMediaScanner(app,permissions)
        val name="playlist-status-compat-${UUID.randomUUID()}.db"
        val store=StatusStore(app,name)
        val saver=StatusSaveCoordinator(app,permissions,scanner,store,settings)
        val tree=DocumentsContract.buildTreeDocumentUri(StatusFixtureProvider.authority(instrumentation.context),StatusFixtureProvider.WA)
        val sources=mutableListOf<Uri>();val outputs=mutableSetOf<Uri>()
        try {
            prefs.edit().clear().commit()
            app.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,StatusFixtureGrantActivity::class.java.name))
                .putExtra("targetPackage",app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(5000) {while(!permissions.readable(tree)) delay(25)}
            settings.text("destination","");permissions.choose(StatusSource.WHATSAPP,tree)
            val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
            val photo=ByteArrayOutputStream().use {stream->bitmap.eraseColor(0xff24e3d1.toInt());bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);bitmap.recycle();stream.toByteArray()}
            val video=instrumentation.context.assets.open("status-original.mp4").use {it.readBytes()}
            for(data in listOf(photo,video)) {
                val parent=DocumentsContract.buildDocumentUriUsingTree(tree,StatusFixtureProvider.WA)
                val source=DocumentsContract.createDocument(app.contentResolver,parent,"application/octet-stream","playlist-status-${UUID.randomUUID()}")!!
                sources+=source
                app.contentResolver.openOutputStream(source)!!.use {it.write(data)}
                val item=scanner.inspect(source)
                val first=Uri.parse(saver.save(item).uri);outputs+=first
                val duplicate=Uri.parse(saver.save(item).uri)
                assertEquals(first,duplicate)
                assertArrayEquals(data,app.contentResolver.openInputStream(source)!!.use {it.readBytes()})
                assertArrayEquals(data,app.contentResolver.openInputStream(first)!!.use {it.readBytes()})
            }
            assertEquals(2,outputs.size)
        } finally {
            outputs.forEach {app.contentResolver.delete(it,null,null)}
            sources.forEach {DocumentsContract.deleteDocument(app.contentResolver,it)}
            val edit=prefs.edit().clear();saved.forEach {(key,value)->if(value is String) edit.putString(key,value)};edit.commit()
            settings.text("destination",old.destination.orEmpty())
            store.close();app.deleteDatabase(name)
        }
    }
}
