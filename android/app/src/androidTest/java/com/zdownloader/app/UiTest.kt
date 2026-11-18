package com.zdownloader.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import java.io.File

class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var original: Settings
    private val settings get() = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    @Before fun setupLanguage() = runBlocking { original=settings.current(); settings.text("language","ar");settings.text("theme","dark") }
    @After fun restoreSettings() = runBlocking { settings.text("language",original.language);settings.text("theme",original.theme);settings.boolean("notifications",original.notifications);settings.integer("preferred_height",original.preferredHeight);settings.text("download_type",original.downloadType) }
    private fun screenshot(name:String,settle:Boolean=true) {
        compose.waitForIdle()
        if(settle) Thread.sleep(500)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val folder=File(app.getExternalFilesDir(null),"screenshots/$run").apply { mkdirs() }
        val dialogs=compose.onAllNodes(isDialog())
        val visible=if(dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else compose.onAllNodes(isRoot()).onFirst()
        val bitmap=visible.captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun premiumFormatsAndLibraryControlsWorkAndCaptureScreenshots() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        runBlocking { settings.boolean("notifications",false) }
        screenshot("home-ar-dark")
        compose.onNodeWithTag("url_input").performTextInput("https://www.w3schools.com/html/mov_bbb.mp4")
        compose.onNodeWithTag("analyze_link").performClick()
        compose.waitUntil(90000) { compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("choose_format").performScrollTo()
        screenshot("preview-ar-dark")
        compose.onNodeWithTag("choose_format").performClick()
        compose.onNodeWithTag("format_sheet").assertIsDisplayed()
        screenshot("formats-video-ar-dark")
        compose.onNodeWithTag("format_audio").performClick()
        compose.onNodeWithTag("audio_output_mp3").performScrollTo().performClick()
        screenshot("formats-audio-ar-dark")
        compose.onNodeWithTag("format_confirm").assertIsDisplayed().performClick()
        val before=runBlocking { app.repository.dao.observe().first().map { it.id }.toSet() }
        compose.onNodeWithTag("start_download").assertIsDisplayed().performClick()
        compose.onNodeWithTag("start_download").assertIsDisplayed()
        compose.onNodeWithText("عرض التنزيلات",substring=false).performClick()
        screenshot("downloads-ar-dark")
        var saved:com.zdownloader.app.data.DownloadEntity?=null
        try {
            compose.waitUntil(120000) { saved=runBlocking { app.repository.dao.observe().first().firstOrNull { it.id !in before } };saved?.status in listOf("COMPLETED","FAILED") }
            assertEquals(saved?.error,"COMPLETED",saved?.status)
            assertTrue(saved!!.filename.endsWith(".mp3"))
            assertEquals("audio/mpeg",app.contentResolver.getType(android.net.Uri.parse(saved!!.uri)))
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithText("صوت",substring=false).performClick()
            screenshot("library-ar-dark")
            compose.onNodeWithTag("library_search").performTextInput("no-such-file-123")
            compose.onNodeWithText("لا توجد وسائط مطابقة").assertIsDisplayed()
            compose.onNodeWithTag("library_search").performTextClearance()
            compose.selectLibraryMenuOption("library_sort_menu","sort_size")
            compose.assertLibraryMenuSelection("library_sort_menu","sort_size")
            compose.activityRule.scenario.onActivity {activity->activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("nav_home").performClick()
            compose.onNodeWithTag("home_list").assertIsDisplayed().performScrollToIndex(0)
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("language_settings").performClick()
            compose.onNodeWithTag("language_en").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme_settings").performClick()
            compose.onNodeWithTag("theme_light").performClick()
            screenshot("settings-en-light")
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("nav_library").performClick()
            screenshot("library-en-light")
            compose.onNodeWithTag("nav_home").performClick()
            compose.onNodeWithTag("url_input").assertIsDisplayed()
            screenshot("home-en-light")
        } finally { saved?.let { row -> runBlocking { if(row.status=="COMPLETED") app.storage.deleteFile(row) else { app.repository.cancel(row.id);app.repository.remove(row.id) } } } }
    }
    @Test fun arabicHomeAndEnglishLightThemeAreUsable() {
        compose.onNodeWithTag("url_input").assertIsDisplayed().performTextInput("https://example.com/video")
        compose.onNodeWithTag("url_input").assertTextContains("https://example.com/video")
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("language_settings").performClick()
        compose.onNodeWithTag("language_en").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("theme_settings").performClick()
        compose.onNodeWithTag("theme_light").performClick()
        compose.waitUntil(5000) { runBlocking {settings.current().theme=="light"} }
        compose.onNodeWithTag("theme_settings").performClick()
        compose.onNodeWithTag("theme_light").assertIsSelected()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithText("Continue pending downloads").assertIsDisplayed()
    }
    @Test fun sharedMalformedInputReportsErrorAndDoesNotCrash() {
        val originalIntent = compose.activity.intent
        compose.activityRule.scenario.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,android.content.Intent(activity,MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,"javascript:alert(1)"))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Unable to complete this action").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("تعذر إكمال هذا الإجراء").fetchSemanticsNodes().isNotEmpty() }
        screenshot("error-ar-dark")
        compose.onNodeWithText("حسنًا").performClick()
        // ActivityScenario identifies lifecycle events by its original launch intent.
        compose.activityRule.scenario.onActivity { it.intent = originalIntent }
    }
    @Test fun allRealQualitiesAndConsumerSettingsRemainUsable() {
        compose.onNodeWithTag("url_input").performTextInput("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
        compose.onNodeWithTag("analyze_link").performClick()
        compose.waitUntil(90000) { compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("choose_format").performScrollTo().performClick()
        screenshot("dash-qualities-ar-dark")
        compose.onAllNodesWithText("576p · MP4").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("format_audio").performClick()
        screenshot("audio-outputs-ar-dark")
        compose.onNodeWithText("OPUS",substring=false).assertIsDisplayed()
        compose.onNodeWithTag("format_confirm").assertIsDisplayed().performClick()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("open_settings").performClick()
        screenshot("settings-ar-dark")
        compose.onNodeWithTag("default_quality").performScrollTo().performClick()
        compose.onNodeWithTag("quality_720").performClick()
        compose.waitUntil(5000) {runBlocking {settings.current().preferredHeight==720}}
        compose.onNodeWithTag("default_type").performScrollTo().performClick()
        compose.onNodeWithTag("type_audio").performClick()
        compose.waitUntil(5000) {runBlocking {settings.current().downloadType=="audio"}}
        screenshot("download-settings-ar-dark")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
        compose.onNodeWithTag("about_zd").performClick()
        compose.onNodeWithTag("privacy_information").performScrollTo().performClick()
        compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag("open_source_licenses"))
        compose.onNodeWithTag("open_source_licenses").performClick()
        compose.onNodeWithTag("information_licenses").assertIsDisplayed()
        screenshot("legal-ar-dark")
        compose.onNodeWithTag("information_back").performClick()
        runBlocking { settings.integer("preferred_height",original.preferredHeight);settings.text("download_type",original.downloadType) }
    }
    @Test fun largeTextAndSettingsAreUsable() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String):String=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val scale=shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 1.3")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale>=1.25f }
            compose.onNodeWithTag("url_input").performClick().performTextInput("https://www.w3schools.com/html/mov_bbb.mp4")
            screenshot("home-large-ar-dark")
            compose.onNodeWithTag("open_settings").performClick()
            screenshot("settings-large-ar-dark")
            compose.onNodeWithTag("language_settings").performScrollTo().performClick()
            compose.onNodeWithTag("language_en").performClick()
            compose.onNodeWithTag("theme_settings").performScrollTo().performClick()
            compose.onNodeWithTag("theme_light").performClick()
            screenshot("settings-large-en-light")
            compose.onNodeWithTag("concurrent_downloads").performScrollTo().performClick()
            compose.onNodeWithTag("concurrency_3").performClick()
            compose.waitUntil(5000) {runBlocking {settings.current().concurrency==3}}
            compose.onNodeWithTag("wifi_only").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { settings.current().wifiOnly==!original.wifiOnly } }
            val next=runBlocking { settings.current() }
            assertEquals(3,next.concurrency)
            assertEquals(!original.wifiOnly,next.wifiOnly)
            compose.activityRule.scenario.recreate()
            if(compose.onAllNodesWithTag("open_settings").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("concurrent_downloads").performScrollTo().performClick()
            compose.onNodeWithTag("concurrency_3").assertIsSelected()
            compose.onNodeWithText("Close").performClick()
        } finally {
            if(scale=="null") shell("settings delete system font_scale") else shell("settings put system font_scale $scale")
            runBlocking { settings.integer("concurrency",original.concurrency);settings.boolean("wifi",original.wifiOnly) }
        }
    }
    @Test fun realActiveAndCompletedCardsCaptureTransferInformation() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val media=runBlocking { app.extractor.analyze("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd") }
        val selection=com.zdownloader.app.domain.Selection.video(media,media.formats.filter {it.video&&it.height!=null}.minBy {it.height!!})
        val id=runBlocking { app.repository.create(media,selection,"اختبار معلومات التنزيل واسم ملف طويل — Z Downloader ${java.util.UUID.randomUUID()}") }
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        val work=scope.launch { app.coordinator.run() }
        var row:com.zdownloader.app.data.DownloadEntity?=null
        try {
            compose.onNodeWithTag("nav_downloads").performClick()
            compose.waitUntil(120000) { compose.onAllNodesWithText("المتبقي",substring=true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("active-progress-ar-dark",false)
            compose.waitUntil(120000) { row=runBlocking { app.repository.dao.get(id) };row?.status in listOf("COMPLETED","FAILED") }
            assertEquals(row?.error,"COMPLETED",row?.status)
            screenshot("completed-details-ar-dark")
        } finally {
            runBlocking {
                if(row?.status=="COMPLETED") app.storage.deleteFile(row!!)
                else {app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.repository.remove(id)}
                work.cancelAndJoin()
            }
            scope.cancel()
        }
    }
}
