package com.zdownloader.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses only public Activity/Compose APIs: same probe can inspect the old APK. */
class NavigationGeometryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun recordSafeTouchTargetsAndInsets() {
        compose.onNodeWithTag("nav_home").assertIsDisplayed()
        val density=compose.activity.resources.displayMetrics.density
        val insets=compose.activity.window.decorView.rootWindowInsets!!
        val bars=insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        val gestures=insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        val targets=JSONObject()
        for(name in listOf("home","downloads","library")) {
            val rect=compose.onNodeWithTag("nav_$name").fetchSemanticsNode().boundsInRoot
            assertTrue("48dp navigation target",rect.height/density>=48f)
            assertTrue("Navigation above the visible system navigation bar",rect.bottom<=root.bottom-bars+2)
            val breathing=(root.bottom-bars-rect.bottom)/density
            assertTrue("A small, intentional 6–12dp breathing space above the actual system inset",breathing in 6f..12f)
            targets.put(name,JSONObject().put("top_px",rect.top).put("bottom_px",rect.bottom).put("height_dp",rect.height/density).put("breathing_dp",breathing))
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val folder=File(context.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"navigation-geometry.json").writeText(JSONObject()
            .put("installed_version",context.packageManager.getPackageInfo(context.packageName,0).versionName)
            .put("density",density).put("font_scale",compose.activity.resources.configuration.fontScale)
            .put("root_bottom_px",root.bottom).put("navigation_bars_bottom_px",bars).put("mandatory_gesture_bottom_px",gestures)
            .put("targets",targets).toString(2))
    }
}
