package com.zdownloader.app

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.status.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class StatusUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private val settings get()=SettingsRepository(app)
    private val prefs get()=app.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE)
    private fun waitTag(tag:String) {compose.waitUntil(20000) {runCatching {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
    private fun open() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_status_saver"))
        compose.onNodeWithTag("open_status_saver").performClick();waitTag("status_screen")
    }
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(400)
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        val dialogs=compose.onAllNodes(isDialog())
        val root=if(dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else compose.onAllNodes(isRoot()).onFirst()
        File(folder,"status-$name.png").outputStream().use {root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    @Test fun settingsEntryExplainsPermissionInArabicEnglishAndAdaptiveWindows() {
        val old=runBlocking {settings.current()};val saved=prefs.all.toMap()
        val size=shell("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")
        val density=shell("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")
        val font=shell("settings get system font_scale")
        try {
            // Removing preferences is no longer a permission-denied fixture:
            // production can correctly reuse the user's existing real SAF grant.
            // Point only at an ungranted synthetic tree, never revoke that grant.
            prefs.edit().putString("whatsapp","content://${StatusFixtureProvider.authority(instrumentation.context)}/tree/denied-fixture").remove("business").commit()
            runBlocking {settings.text("language","ar")};open()
            waitTag("status_choose_folder");compose.onNodeWithTag("status_choose_folder").performScrollTo().assertIsDisplayed()
            capture("permission-ar")
            for((width,scale,language) in listOf(Triple(320,2f,"ar"),Triple(840,1f,"en"))) {
                runBlocking {settings.text("language",language)}
                shell("wm density 320");shell("settings put system font_scale $scale");shell("wm size ${width*2}x1704")
                compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.screenWidthDp-width)<=4}.getOrDefault(false)}
                waitTag("status_choose_folder")
                compose.onNodeWithTag("status_choose_folder").performScrollTo().assertIsDisplayed()
                capture("permission-$width-$language-font$scale")
            }
        } finally {
            shell(if(size==null) "wm size reset" else "wm size $size");shell(if(density==null) "wm density reset" else "wm density $density")
            shell(if(font=="null") "settings delete system font_scale" else "settings put system font_scale $font")
            val edit=prefs.edit().clear();saved.forEach {(k,v)->if(v is String) edit.putString(k,v)};edit.commit()
            runBlocking {settings.text("language",old.language)}
        }
    }
    @Test fun controlledPhotoVideoFilteringPreviewAndSaveFromSettings() {
        val old=runBlocking {settings.current()};val saved=prefs.all.toMap()
        val tree=DocumentsContract.buildTreeDocumentUri(StatusFixtureProvider.authority(instrumentation.context),StatusFixtureProvider.WA)
        val permissions=StatusPermissions(app,settings)
        val scanner=StatusMediaScanner(app,permissions)
        val store=StatusStore(app)
        val sources=mutableListOf<Uri>();val outputs=mutableSetOf<Uri>()
        try {
            prefs.edit().clear().commit()
            app.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,StatusFixtureGrantActivity::class.java.name))
                .putExtra("targetPackage",app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runBlocking {withTimeout(5000) {while(!permissions.readable(tree)) delay(25)}}
            runBlocking {settings.text("language","ar");settings.text("destination","");permissions.choose(StatusSource.WHATSAPP,tree)}
            val image=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).let {bitmap->
                bitmap.eraseColor(0xff24e3d1.toInt());ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray()}
            }
            val data=listOf(image,instrumentation.context.assets.open("status-original.mp4").use {it.readBytes()})
            val items=data.map {bytes->
                val parent=DocumentsContract.buildDocumentUriUsingTree(tree,StatusFixtureProvider.WA)
                val uri=DocumentsContract.createDocument(app.contentResolver,parent,"application/octet-stream","ui-${UUID.randomUUID()}")!!
                sources+=uri;app.contentResolver.openOutputStream(uri)!!.use {it.write(bytes)}
                runBlocking {scanner.inspect(uri)}
            }
            // The grant Activity is a separate task; moveToState cannot bring a
            // covered Activity to the foreground. Reorder the existing test task.
            instrumentation.runOnMainSync {compose.activity.startActivity(Intent(compose.activity.intent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
            compose.waitUntil(10000) {compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}
            open()
            waitTag("status_filter_0");capture("fixture-grid-ar")
            val parent=DocumentsContract.buildDocumentUriUsingTree(tree,StatusFixtureProvider.WA)
            val appeared=DocumentsContract.createDocument(app.contentResolver,parent,"application/octet-stream","appeared-${UUID.randomUUID()}")!!
            sources+=appeared;app.contentResolver.openOutputStream(appeared)!!.use {it.write(image)}
            val appearedItem=runBlocking {scanner.inspect(appeared)}
            compose.waitUntil(20000) {
                compose.onNodeWithTag("status_preview_${items[0].key.take(12)}").assertExists()
                compose.onAllNodesWithTag("status_preview_${appearedItem.key.take(12)}").fetchSemanticsNodes().isNotEmpty()
            }
            DocumentsContract.deleteDocument(app.contentResolver,appeared)
            compose.waitUntil(20000) {compose.onAllNodesWithTag("status_preview_${appearedItem.key.take(12)}").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("status_filter_1").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("status_preview_${items[0].key.take(12)}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("status_preview_${items[1].key.take(12)}").assertDoesNotExist()
            compose.onNodeWithTag("status_filter_0").performScrollTo().performClick()
            items.forEachIndexed {index,item->
                compose.onNodeWithTag("status_preview_${item.key.take(12)}").performScrollTo().performClick()
                waitTag("status_preview_dialog");Thread.sleep(if(item.type.video) 2500 else 500)
                capture(if(item.type.video) "video-preview-ar" else "photo-preview-ar")
                compose.onNodeWithTag("status_preview_save").performClick()
                compose.waitUntil(20000) {runBlocking {store.forSource(item.key)!=null}}
                val output=Uri.parse(store.forSource(item.key)!!.uri);outputs+=output
                assertArrayEquals(data[index],app.contentResolver.openInputStream(output)!!.use {it.readBytes()})
                compose.onNodeWithTag("status_preview_save").assertIsNotEnabled()
                compose.onNodeWithContentDescription("إغلاق").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithTag("status_preview_dialog").fetchSemanticsNodes().isEmpty()}
            }
            capture("fixture-saved-ar")
        } finally {
            outputs.forEach {app.contentResolver.delete(it,null,null)}
            sources.forEach {runCatching {DocumentsContract.deleteDocument(app.contentResolver,it)}}
            val edit=prefs.edit().clear();saved.forEach {(k,v)->if(v is String) edit.putString(k,v)};edit.commit();store.close()
            runBlocking {settings.text("language",old.language);settings.text("destination",old.destination ?: "")}
        }
    }
}
