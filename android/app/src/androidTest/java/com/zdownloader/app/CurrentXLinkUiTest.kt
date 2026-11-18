package com.zdownloader.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Regression for the HONOR 600 input failure; normal analysis/picker UI, no download. */
class CurrentXLinkUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun enteredXVideoReachesRealFormatPickerWithoutDownloading() {
        val url="https://x.com/guaakuhelmi/status/2108610685756436951/video/1"
        compose.onNodeWithTag("url_input").performScrollTo().performTextReplacement(url)
        compose.onNodeWithTag("url_input").assertTextEquals(url)
        compose.onNodeWithTag("analyze_link").performScrollTo().performClick()
        compose.waitUntil(90_000) {compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("choose_format").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("format_sheet").assertIsDisplayed()
        compose.onNodeWithText("public-http-0",substring=true).assertExists()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.getExternalFilesDir(null),"three-fixes-evidence").apply {check(isDirectory||mkdirs())}
        File(directory,"current-x-ui.json").writeText(JSONObject().put("post_id","2108610685756436951")
            .put("normal_analysis_and_format_picker",true).put("metadata_only",true).toString(2))
    }
}
