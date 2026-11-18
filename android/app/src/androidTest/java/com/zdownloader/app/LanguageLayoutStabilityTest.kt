package com.zdownloader.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Check actual on-device physical bounds through the in-app language switch. */
class LanguageLayoutStabilityTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun changingLanguageKeepsNavigationAndToolsInTheirOriginalSlots():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val prefs=SettingsRepository(app);val original=prefs.current()
        val nav=listOf("nav_home","nav_downloads","nav_library")
        val tools=listOf("home_browser","home_playlist","home_status_saver")
        fun bounds(tags:List<String>)=tags.associateWith {compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot}
        fun json(values:Map<String,Rect>)=JSONObject().apply {values.forEach {(tag,r)->put(tag,JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom))}}
        try {
            prefs.text("language","ar");prefs.text("theme","dark")
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20000) {compose.onAllNodesWithTag("home_status_saver").fetchSemanticsNodes().isNotEmpty()}
                compose.waitForIdle()
                val arabic=bounds(nav+tools)
                compose.onNodeWithTag("open_settings").performClick()
                compose.onNodeWithTag("language_settings").performClick()
                compose.onNodeWithTag("language_en").performClick()
                compose.onNodeWithTag("settings_back").performClick()
                compose.waitUntil(20000) {compose.onAllNodesWithTag("home_status_saver").fetchSemanticsNodes().isNotEmpty()}
                compose.waitForIdle()
                compose.onNodeWithTag("home_headline").assertTextEquals("All media in one place")
                val english=bounds(nav+tools)
                for(tag in nav+tools) {
                    val first=arabic.getValue(tag);val second=english.getValue(tag)
                    assertEquals("$tag physical left",first.left,second.left,1f)
                    assertEquals("$tag physical right",first.right,second.right,1f)
                    if(tag in nav) {
                        assertEquals("$tag physical top",first.top,second.top,1f)
                        assertEquals("$tag physical bottom",first.bottom,second.bottom,1f)
                    }
                }
                val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
                File(folder,"language-anchor-bounds.json").writeText(JSONObject().put("arabic",json(arabic)).put("english",json(english)).put("maximum_allowed_difference_px",1)
                    .put("scope","Navigation: all physical bounds. Home tools: horizontal slots; localized font metrics may change vertical text rhythm.").toString(2))
            }
        } finally {prefs.text("language",original.language);prefs.text("theme",original.theme)}
    }
}
