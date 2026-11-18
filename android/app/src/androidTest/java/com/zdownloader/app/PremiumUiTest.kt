package com.zdownloader.app

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PremiumUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val preferences get()=SettingsRepository(instrumentation.targetContext)
    private lateinit var original:Settings
    @Before fun setup()=runBlocking {original=preferences.current();preferences.text("language","ar");preferences.text("theme","dark")}
    @After fun restore()=runBlocking {preferences.text("language",original.language);preferences.text("theme",original.theme)}
    private fun capture(name:String,native:Boolean=false) {
        compose.waitForIdle();Thread.sleep(250)
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        val bitmap=if(native) instrumentation.uiAutomation.takeScreenshot()!! else compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun hideKeyboard() {
        instrumentation.runOnMainSync {
            val view=compose.activity.window.decorView
            compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
            view.clearFocus()
        }
        compose.waitUntil(10000) {compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun reducedMotionKeepsNavigationImmediatelyOperable() {
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
        val previous=shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 0")
            compose.onNodeWithTag("nav_library").performClick().assertIsSelected()
            compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected()
            compose.onNodeWithTag("nav_home").performClick().assertIsSelected()
            compose.onNodeWithTag("analyze_link").performScrollTo().assertIsNotEnabled()
        } finally {shell(if(previous=="null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $previous")}
    }
    @Test fun homeFeatureEntriesOpenRealInputAndStatusSaver() {
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_playlist"))
        compose.onNodeWithTag("home_playlist").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("url_input").assertIsFocused();true}.getOrDefault(false)}
        hideKeyboard()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_status_saver"))
        compose.onNodeWithTag("home_status_saver").performClick()
        compose.onNodeWithTag("status_screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("nav_home").assertIsSelected()
    }
    @Test fun refinedCopyLongUrlsKeyboardAndSavedTabState() {
        compose.onNodeWithTag("home_headline").assertTextEquals("كل المحتوى في مكان واحد")
        compose.onNodeWithTag("analyze_link").assertIsNotEnabled()
        capture("home-ar-dark",true)
        val long="https://example.com/video?path="+"long-media-link-".repeat(30)
        compose.onNodeWithTag("url_input").performClick().performTextInput(long)
        compose.onNodeWithTag("url_input").assertTextContains(long)
        compose.onNodeWithTag("analyze_link").performScrollTo().assertIsEnabled()
        capture("home-long-url-keyboard",true)
        hideKeyboard()
        capture("home-long-url",true)
        compose.onNodeWithTag("url_input").performTextClearance()
        hideKeyboard()
        compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected()
        compose.onNodeWithTag("download_filter_queued").performClick().assertIsSelected()
        capture("downloads-ar-dark",true)
        compose.onNodeWithTag("nav_library").performClick().assertIsSelected()
        capture("library-ar-dark",true)
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithTag("download_filter_queued").assertIsSelected()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("url_input").performScrollTo().assertTextContains("")
        compose.onNodeWithTag("open_settings").performClick()
        capture("settings-ar-dark",true)
        compose.onNodeWithTag("language_settings").performClick();compose.onNodeWithTag("language_en").performClick()
        compose.onNodeWithTag("theme_settings").performClick();compose.onNodeWithTag("theme_light").performClick()
        capture("settings-en-light",true)
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("home_list").performScrollToIndex(0)
        compose.onNodeWithTag("home_headline").assertTextEquals("All media in one place")
        compose.onNodeWithTag("analyze_link").performScrollTo().assertTextContains("Get download options")
        capture("home-en-light",true)
    }
    @Test fun realAnalysisResultsAndReopeningDoNotSubmitDownloads() {
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val before=runBlocking {app.repository.dao.recent().first()}.map {it.id}.toSet()
        compose.onNodeWithTag("url_input").performTextInput("https://www.w3schools.com/html/mov_bbb.mp4")
        hideKeyboard();capture("home-link-ar",true)
        compose.onNodeWithTag("analyze_link").performClick()
        compose.waitUntil(90000) {compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("خيارات التنزيل").assertIsDisplayed()
        compose.onNodeWithTag("choose_format").performScrollTo();capture("format-results-ar",true)
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("reopen_qualities").performScrollTo().performClick()
        compose.onNodeWithTag("choose_format").performScrollTo().assertIsDisplayed()
        assertEquals(before,runBlocking {app.repository.dao.recent().first()}.map {it.id}.toSet())
    }
}
