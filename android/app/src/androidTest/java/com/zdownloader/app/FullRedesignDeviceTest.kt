@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.ClipboardLink
import com.zdownloader.app.data.ClipboardPreview
import com.zdownloader.app.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real screens, no download/install/cleanup or Android permission actions. */
class FullRedesignDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun primaryScreensSettingsDialogsAndToolsRemainReachableInBothThemes()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val settings=SettingsRepository(app);val original=settings.current()
        val rowsBefore=app.repository.dao.observe().first()
        val folder=File(app.getExternalFilesDir(null),"full-redesign").apply {mkdirs()}
        fun capture(name:String) {
            compose.waitForIdle()
            val image=checkNotNull(instrument.uiAutomation.takeScreenshot())
            try {File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
        }
        fun settingsItem(tag:String) {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick();compose.waitForIdle()
        }
        try {
            for((language,theme) in listOf("ar" to "dark","en" to "light")) {
                settings.text("language",language);settings.text("theme",theme)
                ActivityScenario.launch(MainActivity::class.java).use {scenario->
                    var controller:NavHostController?=null
                    val clipboardFixture=mutableStateOf<ClipboardPromptUi?>(null)
                    scenario.onActivity {activity->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val model=ViewModelProvider(activity)[ZViewModel::class.java]
                        activity.setContent {val nav=rememberMediaFlowController();SideEffect {controller=nav};ZRoot(model,nav,activity.updater,clipboardFixture.value)}
                    }
                    compose.waitUntil(15_000) {controller!=null&&compose.onAllNodesWithTag("home_list").fetchSemanticsNodes().isNotEmpty()}
                    capture("$language-home")
                    compose.runOnIdle {clipboardFixture.value=ClipboardPromptUi(ClipboardLink("https://example.com/watch","review-fixture"),{},{},ClipboardPreview("A moment worth keeping"))}
                    compose.waitForIdle()
                    val card=compose.onNodeWithTag("clipboard_download_prompt").fetchSemanticsNode().boundsInRoot
                    val navigation=compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
                    assertTrue("The popup must stay above navigation",card.bottom<=navigation.top)
                    capture("$language-home-clipboard-fixture")
                    compose.runOnIdle {clipboardFixture.value=null};compose.waitForIdle()
                    for(destination in listOf(PrimaryDestination.DOWNLOADS,PrimaryDestination.LIBRARY,PrimaryDestination.HOME)) {
                        compose.runOnUiThread {controller!!.selectPrimary(destination)};compose.waitForIdle()
                        compose.onNodeWithTag("nav_${destination.route}").assertIsSelected();capture("$language-${destination.route}")
                        val menuTag=when(destination) {PrimaryDestination.DOWNLOADS->"downloads_filter_menu";PrimaryDestination.LIBRARY->"library_view_menu";else->null}
                        if(menuTag!=null) {
                            compose.onNodeWithTag(menuTag).performScrollTo().performClick();compose.waitForIdle();capture("$language-${destination.route}-menu")
                            instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);compose.waitForIdle()
                        }
                    }
                    compose.onNodeWithTag("open_settings").performClick();compose.waitForIdle();capture("$language-settings")
                    settingsItem("theme_settings");capture("$language-theme-dialog")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);compose.waitForIdle()
                    settingsItem("open_storage_management");compose.onNodeWithTag("storage_management").assertIsDisplayed();capture("$language-storage")
                    compose.runOnUiThread {controller!!.closeSecondary()};compose.waitForIdle()
                    settingsItem("open_privacy_controls");compose.onNodeWithTag("information_privacy").assertIsDisplayed();capture("$language-privacy")
                    compose.onNodeWithTag("information_back").performClick();compose.waitForIdle()
                    settingsItem("open_help_report");capture("$language-help")
                    compose.onNodeWithTag("help_report_problem").performClick();compose.waitForIdle()
                    compose.onNodeWithTag("problem_report_form").assertIsDisplayed();capture("$language-report-sheet")
                    compose.onNodeWithTag("report_type").performClick();compose.waitForIdle();capture("$language-report-menu")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);compose.waitForIdle()
                    compose.onNodeWithTag("report_whatsapp").performScrollTo().assertIsNotEnabled()
                    compose.onNodeWithTag("report_close").performScrollTo().performClick();compose.waitForIdle()
                    compose.onNodeWithTag("help_back").performClick();compose.waitForIdle()
                    settingsItem("open_updates");compose.onNodeWithTag("update_content").assertIsDisplayed();capture("$language-updates")
                    compose.onNodeWithTag("updates_back").performClick();compose.waitForIdle()
                    settingsItem("about_zd");compose.onNodeWithTag("information_about").assertIsDisplayed();capture("$language-about")
                    compose.onNodeWithTag("information_back").performClick();compose.waitForIdle()
                    settingsItem("open_download_trash");compose.onNodeWithTag("download_trash").assertIsDisplayed();capture("$language-trash")
                    compose.onNodeWithTag("trash_back").performClick();compose.waitForIdle()
                    compose.runOnUiThread {controller!!.closeSecondary()};compose.waitForIdle()
                    compose.runOnUiThread {controller!!.openSecondary(SecondaryDestination.EXTRACT_AUDIO)};compose.waitForIdle()
                    compose.onNodeWithTag("audio_extraction_screen").assertIsDisplayed();capture("$language-extraction")
                    compose.runOnUiThread {controller!!.closeSecondary()};compose.waitForIdle()
                    compose.runOnUiThread {controller!!.openSecondary(SecondaryDestination.BROWSER)};compose.waitForIdle()
                    compose.onNodeWithTag("browser_screen").assertIsDisplayed();capture("$language-browser")
                    compose.onNodeWithTag("browser_close").performClick();compose.waitForIdle()
                    compose.onNodeWithTag("nav_home").assertIsSelected()
                }
            }
            assertEquals("UI review must not change the download records",rowsBefore,app.repository.dao.observe().first())
        } finally {
            settings.text("language",original.language);settings.text("theme",original.theme)
            assertEquals(original,settings.current())
        }
    }
}
