@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.ColorScheme
import com.zdownloader.app.data.ClipboardPreview
import com.zdownloader.app.domain.ClipboardLink
import com.zdownloader.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

class ClipboardPremiumMotionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun pendingPreviewIsImmediatelyActionableAndArtworkDoesNotMoveTheCard() {
        val preview=mutableStateOf(ClipboardPreview())
        var accepted=0;var requests=0
        val candidate=ClipboardLink("https://example.com/watch","premium-fixture")
        compose.setContent {PreviewTheme(Spectrum.Dark) {
            ClipboardDownloadPrompt(ClipboardPromptUi(candidate,{accepted++},{},preview.value,{requests++}))
        }}
        compose.onNodeWithTag("clipboard_download_prompt").assertIsDisplayed()
        compose.onNodeWithTag("clipboard_download").assertIsEnabled().performClick()
        assertEquals(1,accepted);assertEquals(1,requests)
        val bounds=compose.onNodeWithTag("clipboard_download_prompt").fetchSemanticsNode().boundsInRoot
        val artwork=compose.onNodeWithTag("clipboard_artwork").fetchSemanticsNode().boundsInRoot
        assertTrue("The card must be wide, not a portrait dialog",bounds.width>bounds.height)
        val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.rgb(35,160,210))}
        val bytes=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        compose.runOnIdle {preview.value=ClipboardPreview("A beautiful moment worth keeping",artwork=bytes)}
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("clipboard_thumbnail_loaded").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("clipboard_preview_title").assertTextEquals("A beautiful moment worth keeping")
        assertEquals(bounds,compose.onNodeWithTag("clipboard_download_prompt").fetchSemanticsNode().boundsInRoot)
        assertEquals(artwork,compose.onNodeWithTag("clipboard_artwork").fetchSemanticsNode().boundsInRoot)
        capture("preview-loaded")
    }

    @Test fun dismissalConsumesImmediatelyAndFinishesItsReverseTransition() {
        val visible=mutableStateOf(true);var dismissed=0
        compose.setContent {PreviewTheme(Spectrum.Dark) {
            ClipboardDownloadPrompt(if(visible.value) ClipboardPromptUi(ClipboardLink("https://example.com/watch","dismiss"),{},
                {dismissed++;visible.value=false}) else null)
        }}
        compose.onNodeWithTag("clipboard_download_prompt").assertIsDisplayed()
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("clipboard_ignore").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1,dismissed)
        compose.onNodeWithTag("clipboard_download").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("clipboard_download_prompt").assertDoesNotExist()
    }

    @Test fun reducedMotionRtlHasNoWaitForItsActionsOrDismissal() {
        val visible=mutableStateOf(true)
        compose.setContent {PreviewTheme(Spectrum.Light,"ar",1.6f) {
            CompositionLocalProvider(LocalFlowMotionEnabled provides false,LocalLayoutDirection provides LayoutDirection.Rtl) {
                ClipboardDownloadPrompt(if(visible.value) ClipboardPromptUi(ClipboardLink("https://example.com/watch","rtl"),{},
                    {visible.value=false},ClipboardPreview("لحظة جميلة تستحق الحفظ")) else null)
            }
        }}
        compose.onNodeWithTag("clipboard_download").assertIsEnabled()
        capture("preview-rtl-light")
        compose.onNodeWithTag("clipboard_ignore").performClick()
        compose.onNodeWithTag("clipboard_download_prompt").assertDoesNotExist()
    }

    @Test fun entranceTravelsUpAndSettlesWithItsActionsTogether() {
        val visible=mutableStateOf(false)
        compose.setContent {PreviewTheme(Spectrum.Dark) {
            ClipboardDownloadPrompt(if(visible.value) ClipboardPromptUi(ClipboardLink("https://example.com/watch","entrance"),{},{}) else null)
        }}
        compose.mainClock.autoAdvance=false
        try {
            compose.runOnIdle {visible.value=true}
            val samples=org.json.JSONArray()
            var clock=0L
            for(frame in listOf(32L,64L,112L,192L,320L,560L)) {
                compose.mainClock.advanceTimeBy(frame-clock);clock=frame
                val bounds=compose.onNodeWithTag("clipboard_download_prompt").fetchSemanticsNode().boundsInRoot
                samples.put(org.json.JSONObject().put("clock_ms",frame).put("top_px",bounds.top).put("bottom_px",bounds.bottom).put("height_px",bounds.height))
                capture("entrance-$frame")
            }
            compose.onNodeWithTag("clipboard_download").assertIsDisplayed().assertIsEnabled()
            val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"premium-motion")
            File(folder,"entrance-frames.json").writeText(samples.toString(2))
            val drawn=(0 until samples.length()).map {samples.getJSONObject(it)}.filter {it.getDouble("height_px")>0}
            assertTrue("The animation must expose intermediate drawn positions",drawn.size>=2)
            assertTrue("The card must travel upward, not appear in place",drawn.first().getDouble("top_px")>drawn.last().getDouble("top_px"))
        } finally {compose.mainClock.autoAdvance=true}
    }

    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"premium-motion").apply {mkdirs()}
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
            File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }

    @Composable private fun PreviewTheme(colors:ColorScheme,language:String="en",scale:Float=1f,content:@Composable ()->Unit) {
        val base=LocalContext.current
        val current=LocalConfiguration.current
        val config=remember(current,language,scale) {android.content.res.Configuration(current).apply {setLocale(java.util.Locale(language));fontScale=scale}}
        val context=remember(base,config) {base.createConfigurationContext(config)}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalContext provides context,LocalSpectrumContext provides context,LocalConfiguration provides config,
            LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDensity provides Density(density.density,scale)) {FlowTheme(colors) {FlowScreen(content)}}
    }
}
