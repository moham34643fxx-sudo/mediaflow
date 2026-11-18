@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs

class NavigationRenderingTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun actualTabIconsRemainDrawnDuringRepeatedPrimaryTransitions()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val report=JSONObject().put("passed",false).put("scope","Only navigation tab pixels inspected in memory; no screen images retained")
        val samples=JSONArray();report.put("samples",samples)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var controller:NavHostController?=null
                scenario.onActivity {activity->
                    val model=ViewModelProvider(activity)[ZViewModel::class.java]
                    activity.setContent {val nav=rememberMediaFlowController();SideEffect {controller=nav};ZRoot(model,nav)}
                }
                compose.waitUntil(15_000) {controller!=null&&compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()}
                compose.waitForIdle();compose.mainClock.autoAdvance=false
                try {
                    repeat(3) {round->for(target in listOf(PrimaryDestination.DOWNLOADS,PrimaryDestination.LIBRARY,PrimaryDestination.HOME)) {
                        compose.onNodeWithTag("nav_${target.route}").performClick()
                        repeat(16) {frame->
                            compose.mainClock.advanceTimeByFrame()
                            if(frame%4==0) {
                                val displayed=PrimaryDestination.entries.filter {compose.onNodeWithTag("navigation_scene_${it.route}").isDisplayed()}
                                assertTrue("Primary transition displayed no page",displayed.isNotEmpty())
                                for(scene in displayed) for(tab in PrimaryDestination.entries) {
                                    val ink=iconPixels(tab,scene)
                                    samples.put(JSONObject().put("round",round).put("target",target.route).put("scene",scene.route).put("frame",frame).put("tab",tab.route).put("icon_pixels",ink))
                                    assertTrue("Rendered ${tab.route} icon vanished in ${scene.route} during ${target.route} frame $frame",ink>=12)
                                }
                            }
                        }
                        compose.onNode(hasTestTag("nav_${target.route}") and hasAnyAncestor(hasTestTag("navigation_scene_${target.route}"))).assertIsSelected()
                    }}
                } finally {compose.mainClock.autoAdvance=true}
            }
            report.put("passed",true)
        } finally {
            val home=File(app.filesDir,"navigation-rendering/${UUID.randomUUID()}").apply {check(mkdirs())}
            File(home,"result.json").writeText(report.toString(2))
        }
    }

    private fun iconPixels(tab:PrimaryDestination,target:PrimaryDestination):Int {
        val pixels=compose.onNode(hasTestTag("nav_${tab.route}") and hasAnyAncestor(hasTestTag("navigation_scene_${target.route}"))).captureToImage().toPixelMap()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val center=pixels.width/2
        val background=pixels[center,(2*density).toInt().coerceAtMost(pixels.height-1)]
        var ink=0
        for(x in (center-(14*density).toInt()).coerceAtLeast(0) until (center+(14*density).toInt()).coerceAtMost(pixels.width))
            for(y in (8*density).toInt() until (30*density).toInt().coerceAtMost(pixels.height)) {
                val color=pixels[x,y]
                if(maxOf(abs(color.red-background.red),abs(color.green-background.green),abs(color.blue-background.blue))>.10f) ink++
            }
        return ink
    }
}
