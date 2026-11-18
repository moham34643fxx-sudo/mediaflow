package com.zdownloader.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real WindowManager resizing: exercises window-size classes, rather than scaling a mock image. */
class ResponsiveDesignTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun waitForTag(tag:String) {compose.waitUntil(15000) {runCatching {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(1200)
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"obsidian-screenshots/$run").apply {mkdirs()}
        // Capture the application's surface, never the notification shade or a
        // different app which the device owner may open during the test.
        val dialogs=compose.onAllNodes(isDialog())
        val visible=if(dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else compose.onAllNodes(isRoot()).onFirst()
        val image=visible.captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.startsWith("three-button")||name.startsWith("gesture")) {
            val automation=instrumentation.uiAutomation
            if(automation.rootInActiveWindow?.packageName?.toString()=="com.zdownloader.app") {
                automation.takeScreenshot()?.let {native->File(folder,"$name-native.png").outputStream().use {native.compress(Bitmap.CompressFormat.PNG,100,it)}}
            }
        }
    }
    @Test fun realWindowsSupportNarrowExpandedAndLargeText() {
        val settings=SettingsRepository(instrumentation.targetContext)
        val original=runBlocking {settings.current()}
        val font=shell("settings get system font_scale")
        val size=shell("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")
        val density=shell("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")
        data class Case(val width:Int,val language:String,val theme:String,val scale:Float)
        try {
            shell("wm density 320")
            listOf(Case(320,"ar","dark",1f),Case(320,"ar","dark",1.6f),Case(320,"ar","dark",2f),Case(393,"en","light",1.3f),Case(480,"ar","dark",1f),Case(600,"ar","dark",1f),Case(840,"en","light",1.6f)).forEach { case ->
                runBlocking {settings.text("language",case.language);settings.text("theme",case.theme)}
                shell("settings put system font_scale ${case.scale}")
                shell("wm size ${case.width*2}x1704")
                compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.screenWidthDp-case.width)<=4&&kotlin.math.abs(compose.activity.resources.configuration.fontScale-case.scale)<.05f}.getOrDefault(false)}
                waitForTag("nav_home")
                compose.onNodeWithTag("nav_home").performClick()
                compose.onNodeWithTag("url_input").performScrollTo().assertIsDisplayed()
                compose.activityRule.scenario.onActivity { activity ->
                    if(android.os.Build.VERSION.SDK_INT>=30) assertEquals("Status-bar contrast at ${case.width}dp",case.theme=="light",activity.window.insetsController!!.systemBarsAppearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
                }
                val suffix="${case.width}-${case.language}-${case.theme}-${case.scale}"
                capture("home-$suffix")
                 compose.onNodeWithTag("analyze_link").performScrollTo().assertIsDisplayed()
                 compose.onNodeWithTag("nav_downloads").performClick()
                 compose.onNodeWithTag("download_filter_all").assertIsDisplayed()
                 capture("downloads-$suffix")
                 compose.onNodeWithTag("nav_home").performClick()
                 compose.onNodeWithTag("open_settings").performClick()
                  capture("settings-$suffix")
                  compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                  compose.onNodeWithTag("nav_home").assertDoesNotExist()
                 compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("language_settings"))
                 compose.onNodeWithTag("language_settings").assertIsDisplayed()
                 compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("default_quality"))
                 compose.onNodeWithTag("default_quality").assertIsDisplayed().performClick()
                compose.onNodeWithTag("quality_720").assertIsDisplayed()
                capture("quality-dialog-$suffix")
                compose.onNodeWithText(if(case.language=="ar") "إغلاق" else "Close").performClick()
                compose.onNodeWithTag("settings_back").performClick()
                compose.onNodeWithTag("nav_library").performClick()
                compose.onNodeWithTag("library_search").assertIsDisplayed()
                capture("library-$suffix")
            }
        } finally {
            shell(if(size==null) "wm size reset" else "wm size $size")
            shell(if(density==null) "wm density reset" else "wm density $density")
            shell(if(font=="null") "settings delete system font_scale" else "settings put system font_scale $font")
            runBlocking {settings.text("language",original.language);settings.text("theme",original.theme)}
        }
    }
    @Test fun landscapeNavigationAndCutoutRemainUsable() {
        val rotation=shell("settings get system user_rotation").takeIf {it in listOf("0","1","2","3")} ?: "0"
        val automatic=shell("settings get system accelerometer_rotation")=="1"
        try {
            shell("wm user-rotation lock 1")
            compose.waitUntil(15000) {compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("open_settings").assertIsDisplayed().performClick()
             compose.onNodeWithTag("settings_list").assertIsDisplayed()
             compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
             compose.onNodeWithTag("nav_home").assertDoesNotExist()
            capture("landscape-settings")
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("nav_home").performScrollTo().performClick()
            compose.onNodeWithTag("url_input").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("analyze_link").performScrollTo().assertIsDisplayed()
            capture("landscape-home-actions")
        } finally {shell(if(automatic) "wm user-rotation free" else "wm user-rotation lock $rotation")}
    }
    @Test fun threeButtonNavigationRespectsSystemInsets() {
        val overlay=shell("cmd overlay list android").lineSequence().firstOrNull {it.startsWith("[x]")&&it.contains("com.android.internal.systemui.navbar.")}?.substringAfter("] ")
            ?: throw AssertionError("Could not identify navigation overlay to restore")
        val gesture=shell("settings get secure secure_gesture_navigation")
        val hidden=shell("settings get global navigationbar_is_min")
        try {
            shell("settings put secure secure_gesture_navigation 0");shell("settings put global navigationbar_is_min 0")
            val output=shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
            assertFalse(output,output.contains("Exception"))
            compose.waitUntil(15000) {compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom?.let {it>=24*compose.activity.resources.displayMetrics.density}==true}
            compose.onNodeWithTag("nav_home").performClick()
            compose.onNodeWithTag("url_input").performScrollTo().assertIsDisplayed()
            capture("three-button-home")
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithTag("library_search").assertIsDisplayed()
            capture("three-button-library")
        } finally {
            shell("cmd overlay enable-exclusive --category $overlay")
            shell(if(gesture=="null") "settings delete secure secure_gesture_navigation" else "settings put secure secure_gesture_navigation $gesture")
            shell(if(hidden=="null") "settings delete global navigationbar_is_min" else "settings put global navigationbar_is_min $hidden")
        }
    }
    @Test fun gestureNavigationRespectsProtectedBottomArea() {
        val overlay=shell("cmd overlay list android").lineSequence().firstOrNull {it.startsWith("[x]")&&it.contains("com.android.internal.systemui.navbar.")}?.substringAfter("] ")
            ?: throw AssertionError("Could not identify navigation overlay to restore")
        val gesture=shell("settings get secure secure_gesture_navigation")
        val hidden=shell("settings get global navigationbar_is_min")
        try {
            shell("settings put secure secure_gesture_navigation 1");shell("settings put global navigationbar_is_min 1")
            val output=shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            assertFalse(output,output.contains("Exception"))
            compose.onNodeWithTag("nav_home").performClick()
            compose.onNodeWithTag("analyze_link").performScrollTo().assertIsDisplayed()
            capture("gesture-home")
        } finally {
            shell("cmd overlay enable-exclusive --category $overlay")
            shell(if(gesture=="null") "settings delete secure secure_gesture_navigation" else "settings put secure secure_gesture_navigation $gesture")
            shell(if(hidden=="null") "settings delete global navigationbar_is_min" else "settings put global navigationbar_is_min $hidden")
        }
    }
}
