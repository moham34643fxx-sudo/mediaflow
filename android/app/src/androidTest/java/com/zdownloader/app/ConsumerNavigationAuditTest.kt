package com.zdownloader.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual navigation, no main-app installation or user downloads; screenshots stay private. */
class ConsumerNavigationAuditTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun primaryAndSecondaryScreensRemainReachableInArabicAndEnglish():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        val settings=SettingsRepository(app);val original=settings.current()
        val folder=File(app.filesDir,"quickshare-review/navigation-audit").apply {mkdirs()}
        val pages=JSONArray()
        fun await(tag:String) {compose.waitUntil(20_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
        fun click(tag:String) {await(tag);compose.onNodeWithTag(tag).performClick();compose.waitForIdle()}
        fun capture(name:String) {
            compose.waitForIdle()
            val image=instrument.uiAutomation.takeScreenshot()!!
            try {File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
            pages.put(name)
            File(folder.parentFile,"navigation-audit.json").writeText(JSONObject().put("pages",pages).toString(2))
        }
        fun settingsItem(tag:String) {compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(tag));click(tag)}
        try {
            for(language in listOf("ar","en")) {
                settings.text("language",language);settings.text("theme",if(language=="ar") "dark" else "light")
                ActivityScenario.launch(MainActivity::class.java).use {
                    await("home_status_saver");capture("$language-home")
                    click("nav_downloads");await("downloads_grid");capture("$language-downloads")
                    compose.onNodeWithTag("download_filter_cancelled").performScrollTo().performClick();capture("$language-cancelled-filter")
                    click("nav_library");await("standalone_library")
                    compose.onNodeWithTag("library_source_menu").assertDoesNotExist();capture("$language-library-downloads")
                    click("library_open_file");await("local_media_screen")
                    for(source in listOf("device","favorites","playlists")) {
                        compose.selectLibraryMenuOption("library_source_menu","library_source_$source");capture("$language-local-$source")
                    }
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);await("standalone_library")
                    click("nav_home");click("open_settings");await("settings_list");capture("$language-settings")
                    settingsItem("open_storage_management");await("storage_management");capture("$language-storage")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);await("settings_list")
                    settingsItem("open_download_trash");await("download_trash");capture("$language-trash");click("trash_back")
                    settingsItem("open_updates");await("updates_back");capture("$language-updates");click("updates_back")
                    settingsItem("about_zd");await("information_about");capture("$language-about");click("information_back")
                    click("settings_back");await("home_status_saver")
                    compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_extract_audio"));click("home_extract_audio")
                    await("audio_extraction_screen");capture("$language-audio-extraction")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);await("home_status_saver")
                    compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_browser"));click("home_browser")
                    await("browser_screen");capture("$language-browser");click("browser_close");await("home_status_saver")
                    compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_status_saver"));click("home_status_saver")
                    await("status_screen");capture("$language-status")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    compose.waitForIdle()
                    if(compose.onAllNodesWithTag("status_back").fetchSemanticsNodes().isNotEmpty()) click("status_back")
                    await("home_status_saver")
                }
            }
        } finally {settings.text("language",original.language);settings.text("theme",original.theme)}
    }
}
