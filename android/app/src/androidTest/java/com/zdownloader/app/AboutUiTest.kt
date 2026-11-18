package com.zdownloader.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AboutUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val settings get()=SettingsRepository(instrumentation.targetContext)
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(250)
        val out=File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        val image=compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        File(out,"about-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun approvedIdentityPrivacyAndNoticesWorkInArabicEnglishAndAdaptiveLayouts() {
        val old=runBlocking {settings.current()}
        val size=shell("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")
        val density=shell("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")
        val font=shell("settings get system font_scale")
        data class Case(val language:String,val theme:String,val width:Int,val scale:Float)
        try {
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
            compose.onNodeWithTag("about_zd").performClick()
            for(case in listOf(Case("ar","dark",360,1f),Case("en","dark",360,1f),Case("en","light",840,1f),Case("ar","dark",320,2f))) {
                runBlocking {settings.text("language",case.language);settings.text("theme",case.theme)}
                shell("wm density 320");shell("settings put system font_scale ${case.scale}");shell("wm size ${case.width*2}x1704")
                compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.screenWidthDp-case.width)<=4&&
                    kotlin.math.abs(compose.activity.resources.configuration.fontScale-case.scale)<.05f}.getOrDefault(false)}
                compose.onNodeWithTag("about_title").performScrollTo().assertTextEquals("MediaFlow").assertIsDisplayed()
                 compose.onNodeWithTag("about_logo").assertExists()
                 compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                 compose.onNodeWithTag("nav_home").assertDoesNotExist()
                compose.onAllNodesWithText("MediaFlow",substring=false).assertCountEquals(1)
                compose.onNodeWithTag("about_subtitle").assertTextEquals("Video & Audio Downloader")
                compose.onNodeWithTag("about_version").assertTextEquals(if(case.language=="ar") "الإصدار ${BuildConfig.VERSION_NAME}" else "Version ${BuildConfig.VERSION_NAME}")
                compose.onNodeWithTag("about_developer").assertTextEquals("Powered by Mohamed Abuzaid")
                compose.onNodeWithTag("open_source_licenses").assertDoesNotExist()
                compose.onNodeWithTag("legal_information").assertDoesNotExist()
                capture("${case.language}-${case.theme}-${case.width}-font${case.scale}")
                compose.onNodeWithTag("about_copyright").performScrollTo().assertTextEquals("© 2026 Mohamed Abuzaid. All rights reserved.").assertIsDisplayed()
                if(case.scale>1f) capture("${case.language}-${case.width}-large-text-footer")
                compose.onNodeWithTag("privacy_information").performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("privacy_information").performClick()
            compose.onNodeWithTag("privacy_practices").assertExists()
            compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag("playback_privacy_practices"))
            compose.onNodeWithTag("playback_privacy_practices").assertIsDisplayed()
            capture("playback-privacy-ar")
            runBlocking {settings.text("language","en")}
            compose.onNodeWithTag("playback_privacy_practices").assertTextContains("Favorites, playback playlists",substring=true)
            capture("playback-privacy-en")
            compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag("open_source_licenses"))
            compose.onNodeWithTag("open_source_licenses").assertIsDisplayed().performClick()
            compose.onNodeWithTag("information_licenses").assertIsDisplayed()
            compose.waitUntil(10000) {compose.onAllNodesWithText("THIRD-PARTY",substring=true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("LAME",substring=true).fetchSemanticsNodes().isNotEmpty()}
            capture("third-party-notices")
            compose.onNodeWithTag("information_back").performClick()
            compose.onNodeWithTag("information_privacy").assertIsDisplayed()
        } finally {
            shell(if(size==null) "wm size reset" else "wm size $size");shell(if(density==null) "wm density reset" else "wm density $density")
            shell(if(font=="null") "settings delete system font_scale" else "settings put system font_scale $font")
            runBlocking {settings.text("language",old.language);settings.text("theme",old.theme)}
        }
    }
}
