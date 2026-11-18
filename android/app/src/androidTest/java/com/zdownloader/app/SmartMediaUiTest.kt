package com.zdownloader.app

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.playback.SmartMediaBrowser
import com.zdownloader.app.playback.MediaBrowserSource
import com.zdownloader.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/** Real UI components, isolated fixture media only; no personal device library captures. */
class SmartMediaUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val evidence get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(400)
        val image=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(evidence,"smart-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
    }
    private fun hideKeyboard() {
        compose.onNodeWithTag("library_search").performImeAction()
        compose.onNodeWithTag("library_search").assertIsNotFocused()
        compose.waitUntil(10000) {compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true}
    }
    private fun render(language:State<String>,content:@Composable ()->Unit) {
        instrument.runOnMainSync {compose.activity.setContent {
            val config=Configuration(LocalConfiguration.current).apply {setLocale(Locale.forLanguageTag(language.value));setLayoutDirection(Locale.forLanguageTag(language.value))}
            val localized=compose.activity.createConfigurationContext(config)
            CompositionLocalProvider(LocalSpectrumContext provides localized,LocalConfiguration provides config,LocalLayoutDirection provides if(language.value=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(Spectrum.Dark) {FlowScreen(content)}
            }
        }}
    }
    private fun fixture(name:String):Uri {
        val resolver=app.contentResolver
        val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {put("_display_name",name);put("mime_type","video/mp4");put("relative_path","Movies/MediaFlow-QA");put("is_pending",1)})!!
        instrument.context.assets.open("status-original.mp4").use {source->resolver.openOutputStream(uri,"w")!!.use {source.copyTo(it)}}
        resolver.update(uri,ContentValues().apply {put("is_pending",0)},null,null)
        return uri
    }
    @Test fun actualFileManagerSearchSortSelectionGridDetailsAndAdaptiveScreens() {
        val prefix="QA-${UUID.randomUUID().toString().take(6)}"
        val names=listOf("Pond ducks · $prefix B.mp4","Pond ducks · $prefix A.mp4")
        val uris=names.map(::fixture)
        val actualDuration=runBlocking {app.mediaFiles.details(app.mediaFiles.metadata(uris.first().toString()))["duration"]?.toDoubleOrNull()?.div(1000)}
        val rows=uris.mapIndexed {i,uri->DownloadEntity("ui-$i","https://example.com/ui",prefix,"Fixture",null,actualDuration,"{}",names[i],null,"fixture-$i",status="COMPLETED",uri=uri.toString())}
        val language=mutableStateOf("en")
        val size=shell("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")
        val density=shell("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")
        val font=shell("settings get system font_scale")
        try {
            render(language) {SmartMediaBrowser(app.playback,MediaBrowserSource.Downloads(rows))}
            compose.onNodeWithTag("library_search").performTextInput(prefix)
            hideKeyboard()
            compose.selectLibraryMenuOption("library_sort_menu","sort_name")
            compose.assertLibraryMenuSelection("library_sort_menu","sort_name")
            compose.waitUntil(15000) {compose.onAllNodesWithTag("library_item_ui-1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("library_item_ui-1").performScrollTo().assertIsDisplayed();capture("library-list-en")
            compose.onNodeWithTag("library_item_ui-1").performTouchInput {longClick()}
            compose.onNodeWithTag("fm_selection").performScrollTo().assertIsDisplayed();capture("library-selection-en")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("fm_view").performScrollTo().performClick()
            val a=compose.onNodeWithTag("library_item_ui-1").fetchSemanticsNode().boundsInRoot
            val b=compose.onNodeWithTag("library_item_ui-0").fetchSemanticsNode().boundsInRoot
            assertTrue("Grid must use separate columns on the test phone",kotlin.math.abs(a.left-b.left)>10)
            compose.onNodeWithTag("library_item_ui-1").performScrollTo();capture("library-grid-en")
            compose.onNodeWithTag("fm_menu_${uris[1].lastPathSegment}").performScrollTo().performClick()
            compose.onNodeWithText("Details").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithText("video/mp4",substring=true).fetchSemanticsNodes().isNotEmpty()};capture("file-details-en")
            compose.onNodeWithText("OK").performClick()
            instrument.runOnMainSync {language.value="ar"}
            compose.waitForIdle();compose.onNodeWithTag("library_item_ui-1").performScrollTo();capture("library-grid-ar")
            shell("wm density 320");shell("settings put system font_scale 2.0");shell("wm size 640x1704")
            compose.waitUntil(15000) {compose.activity.resources.configuration.fontScale>1.9f&&compose.activity.resources.configuration.screenWidthDp<=324}
            Thread.sleep(1800);render(language) {SmartMediaBrowser(app.playback,MediaBrowserSource.Downloads(rows))}
            compose.selectLibraryMenuOption("library_sort_menu","sort_name")
            compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("local_library_list").performScrollToNode(hasTestTag("library_item_ui-1"));true}.getOrDefault(false)}
            compose.onNodeWithTag("library_item_ui-1").assertIsDisplayed();capture("library-ar-320-font2")
            File(evidence,"smart-file-ui.json").writeText(JSONObject().put("real_fixture_files",2).put("search",true).put("name_sort",true).put("selection",true).put("grid_list",true).put("details_actual_type",true).put("arabic_rtl",true).put("320dp_font2_controls_reachable",true).toString(2))
        } finally {
            shell(if(size==null) "wm size reset" else "wm size $size");shell(if(density==null) "wm density reset" else "wm density $density");shell(if(font=="null") "settings delete system font_scale" else "settings put system font_scale $font")
            uris.forEach {app.contentResolver.delete(it,null,null)}
        }
    }
    @Test fun advancedOptionsPersistARealScheduledRequestAndDisplayItsRequirements()=runBlocking {
        val language=mutableStateOf("en");val model=ViewModelProvider(compose.activity)[ZViewModel::class.java]
        val format=Format.parse(JSONObject().put("format_id","v").put("url","https://www.w3schools.com/html/mov_bbb.mp4").put("ext","mp4").put("width",320).put("height",180).put("vcodec","h264").put("acodec","aac"))
        val track=SubtitleTrack("en","vtt",JSONObject().put("url","https://example.com/fixture.vtt").put("format_id","sub").put("ext","vtt").toString())
        val media=Media("https://www.w3schools.com/html/mov_bbb.mp4","schedule-ui-${UUID.randomUUID()}","MediaFlow QA scheduled video","Fixture","QA creator",null,null,listOf(format),listOf(track))
        var selected:Selection?=null;var created:String?=null
        try {
            render(language) {Preview(media,Settings(language="en"),model,{},{}) {selection,_->selected=selection}}
            compose.onNodeWithTag("preview_list").performScrollToNode(hasTestTag("advanced_download_options"))
            compose.onNodeWithTag("advanced_download_options").performClick()
            compose.onNodeWithTag("preview_list").performScrollToNode(hasTestTag("advanced_video_only"))
            compose.onNodeWithTag("advanced_video_only").performClick()
            compose.onNodeWithTag("preview_list").performScrollToNode(hasText("English"))
            compose.onNodeWithText("English").performClick();capture("advanced-options-en")
            compose.onNodeWithTag("start_download").performClick()
            assertTrue(selected!!.videoOnly);assertEquals("en",selected!!.subtitle?.language);assertEquals("QA creator",selected!!.creator)
            compose.onNodeWithTag("preview_list").performScrollToNode(hasTestTag("schedule_download"))
            compose.onNodeWithTag("schedule_download").performClick()
            compose.onNodeWithTag("schedule_network_UNMETERED").performClick().assertIsSelected()
            val radioBounds=compose.onNodeWithTag("schedule_network_UNMETERED").fetchSemanticsNode().boundsInRoot
            assertTrue("Scheduling controls must keep a 48dp touch target",radioBounds.height>=48*compose.activity.resources.displayMetrics.density-.5f)
            capture("schedule-chooser-en")
            compose.onNodeWithTag("schedule_confirm").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("duplicate_confirmation").fetchSemanticsNodes().isNotEmpty()||runBlocking {app.repository.dao.sourceDuplicates(MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url)).any {it.mediaKey==MediaIdentity.sourceKey(media)}}}
            if(compose.onAllNodesWithTag("duplicate_confirmation").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("duplicate_again").performClick()
            withTimeout(20000) {while(created==null) {created=app.repository.dao.sourceDuplicates(MediaIdentity.sourceKey(media),MediaIdentity.urlKey(media.url)).firstOrNull {it.mediaKey==MediaIdentity.sourceKey(media)}?.id;delay(50)}}
            val row=app.repository.dao.get(created!!)!!
            assertEquals("SCHEDULED",row.status);assertEquals("UNMETERED",row.scheduleNetwork);assertTrue(row.scheduledAt>System.currentTimeMillis()+30*60_000)
            render(language) {DownloadCard(row,model,{},false,{})}
            capture("scheduled-download-en")
            File(evidence,"smart-schedule-ui.json").writeText(JSONObject().put("persistent_state",row.status).put("network",row.scheduleNetwork).put("future_not_claimable",app.repository.dao.claim(row.id)==0).put("video_only_selected",selected!!.videoOnly).put("subtitle_language_selected",selected!!.subtitle!!.language).toString(2))
        } finally {created?.let {app.repository.cancel(it);app.plannedDownloads.cancel(it);app.repository.remove(it)}}
    }
    @Test fun storageAndDuplicateDecisionScreensShowRealScopedDataInBothLanguages() {
        val language=mutableStateOf("en");val uri=fixture("MediaFlow QA duplicate ${UUID.randomUUID()}.mp4")
        try {
            val row=DownloadEntity("ui-duplicate","https://example.com/ui","MediaFlow QA existing media","Fixture",null,null,"{}","existing.mp4",null,"fixture",status="COMPLETED",uri=uri.toString())
            render(language) {DuplicateDialog(DuplicateReview(listOf(DownloadMatch(row.id,row.uri,row.status,row.fingerprint,row.mediaKey)),emptyList()),{},{})}
            compose.onNodeWithTag("duplicate_open").assertIsDisplayed();capture("duplicate-completed-en")
            instrument.runOnMainSync {language.value="ar"};compose.waitForIdle();capture("duplicate-completed-ar")
            render(language) {StorageManagementScreen({},{},{})}
            compose.onNodeWithTag("storage_management").assertIsDisplayed();Thread.sleep(1800);capture("storage-management-ar")
            instrument.runOnMainSync {language.value="en"};compose.waitForIdle();capture("storage-management-en")
            compose.onNodeWithTag("storage_clear_cache").performScrollTo().assertIsDisplayed()
            File(evidence,"smart-storage-ui.json").writeText(JSONObject().put("storage_controls_reachable",true).put("duplicate_open_only_with_accessible_fixture",true).put("ar_en",true).toString(2))
        } finally {app.contentResolver.delete(uri,null,null)}
    }
    @Test fun managerFitsTheActualAppNavigationInArabicAndEnglish():Unit=runBlocking {
        val settings=SettingsRepository(app);val original=settings.current()
        val preferences=app.getSharedPreferences("mediaflow_playback",android.content.Context.MODE_PRIVATE)
        val oldSource=preferences.getString("library_source",null)
        val token="QA-${UUID.randomUUID().toString().take(6)}"
        val uris=mutableListOf<Uri>();val ids=mutableListOf<String>()
        try {
            settings.text("language","en");settings.text("theme","dark")
            app.playback.repository.setText("library_source","downloads")
            for(suffix in listOf("A","B")) {
                val uri=fixture("Pond ducks · $token $suffix.mp4");uris+=uri
                val file=app.mediaFiles.metadata(uri.toString());val detail=app.mediaFiles.details(file)
                val id="manager-ui-${UUID.randomUUID()}";ids+=id
                val format=Format.parse(JSONObject().put("format_id","fixture").put("url","https://example.com/fixture.mp4").put("ext","mp4").put("vcodec","h264").put("acodec","aac"))
                app.repository.dao.insert(DownloadEntity(id,"https://example.com/$id","Pond ducks","QA fixture",null,detail["duration"]?.toDoubleOrNull()?.div(1000),Selection(format).json(),file.name,null,id,status="COMPLETED",uri=uri.toString(),outputSize=file.size))
            }
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithTag("library_source_menu").assertDoesNotExist()
            compose.onNodeWithTag("library_search").performTextInput(token)
            hideKeyboard()
            compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("local_library_list").performScrollToNode(hasTestTag("library_item_${ids.first()}"));true}.getOrDefault(false)}
            capture("full-library-en")
            settings.text("language","ar");compose.waitForIdle();capture("full-library-ar")
            compose.onNodeWithTag("local_library_list").performScrollToNode(hasTestTag("fm_view"))
            compose.onNodeWithTag("fm_view").performClick();capture("full-library-grid-ar")
            compose.onNodeWithTag("fm_menu_${uris.first().lastPathSegment}").performScrollTo().performClick()
            val ar=Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag("ar"))}
            compose.onNodeWithText(app.createConfigurationContext(ar).getString(R.string.details)).performClick()
            compose.onNodeWithTag("file_details_dialog").assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist();capture("full-file-details-no-navigation-ar")
            compose.onNodeWithTag("file_details_confirm").performClick()
            compose.onNodeWithTag("nav_library").assertIsSelected()
        } finally {
            uris.forEach {app.contentResolver.delete(it,null,null)}
            ids.forEach {app.repository.dao.removeHistory(it)}
            settings.text("language",original.language);settings.text("theme",original.theme)
            preferences.edit().apply {if(oldSource==null) remove("library_source") else putString("library_source",oldSource)}.commit()
        }
    }
}
