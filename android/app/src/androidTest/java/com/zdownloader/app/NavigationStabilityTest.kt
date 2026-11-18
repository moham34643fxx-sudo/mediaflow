@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
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
import java.util.Collections
import java.util.UUID

/** Per-frame navigation state and real window timings; no private screen captures. */
class NavigationStabilityTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun repeatedPrimaryAndSettingsTransitionsKeepNavigationCoherent()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val home=File(app.filesDir,"navigation-stability/${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val report=JSONObject().put("passed",false).put("scope","Actual root navigation transition sampling and window frame metrics; no screen captures or media mutations")
        val transitions=JSONArray();report.put("transitions",transitions)
        val durations=Collections.synchronizedList(mutableListOf<Long>())
        val metricThread=HandlerThread("navigation-frame-audit").apply {start()}
        val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_->
            if(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L) durations+=metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        var failure:Throwable?=null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var controller:NavHostController?=null
                scenario.onActivity {activity->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.window.addOnFrameMetricsAvailableListener(listener,Handler(metricThread.looper))
                    val model=ViewModelProvider(activity)[ZViewModel::class.java]
                    activity.setContent {
                        val nav=rememberMediaFlowController()
                        SideEffect {controller=nav}
                        ZRoot(model,nav)
                    }
                }
                try {
                    compose.waitUntil(15_000) {controller!=null&&compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()}
                    compose.waitForIdle()
                    compose.mainClock.autoAdvance=false
                    repeat(4) {round->
                        for(destination in listOf(PrimaryDestination.DOWNLOADS,PrimaryDestination.LIBRARY,PrimaryDestination.HOME)) {
                            val samples=JSONArray()
                            compose.runOnUiThread {requireNotNull(controller).selectPrimary(destination)}
                            repeat(16) {
                                compose.mainClock.advanceTimeByFrame()
                                val displayed=PrimaryDestination.entries.filter {compose.onNodeWithTag("navigation_scene_${it.route}").isDisplayed()}
                                assertTrue("Primary transition displayed no page",displayed.isNotEmpty())
                                for(scene in displayed) {
                                    val active=hasAnyAncestor(hasTestTag("navigation_scene_${scene.route}"))
                                    val count=compose.onAllNodes(hasTestTag("bottom_navigation") and active).fetchSemanticsNodes().size
                                    val icons=PrimaryDestination.entries.count {compose.onNode(hasTestTag("nav_${it.route}") and active).isDisplayed()}
                                    compose.onNode(hasTestTag("bottom_navigation") and active).assertIsDisplayed()
                                    samples.put(JSONObject().put("scene",scene.route).put("bars",count).put("tabs",icons))
                                }
                            }
                            transitions.put(JSONObject().put("round",round).put("kind","primary").put("target",destination.route).put("samples",samples))
                            for(index in 0 until samples.length()) {
                                assertEquals("Primary transition removed its navigation bar",1,samples.getJSONObject(index).getInt("bars"))
                                assertEquals("Primary transition removed one of its navigation tabs",3,samples.getJSONObject(index).getInt("tabs"))
                            }
                            compose.onNodeWithTag("navigation_scene_${destination.route}").assertIsDisplayed()
                            val owned=PrimaryDestination.entries.sumOf {origin->compose.onAllNodes(hasTestTag("bottom_navigation") and hasAnyAncestor(hasTestTag("navigation_scene_${origin.route}"))).fetchSemanticsNodes().size}
                            assertEquals("Navigation bar escaped its destination scene",owned,compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().size)
                            compose.onNode(hasTestTag("nav_${destination.route}") and hasAnyAncestor(hasTestTag("navigation_scene_${destination.route}"))).assertIsSelected()
                        }
                        val nav=requireNotNull(controller)
                        val enter=visibilitySamples {nav.openSecondary(SecondaryDestination.SETTINGS)}
                        transitions.put(JSONObject().put("round",round).put("kind","settings_enter").put("samples",enter))
                        compose.onNodeWithTag("settings_list").assertIsDisplayed()
                        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                        val exit=visibilitySamples {nav.closeSecondary()}
                        transitions.put(JSONObject().put("round",round).put("kind","settings_exit").put("samples",exit))
                        compose.onNodeWithTag("nav_home").assertIsSelected()
                        for(samples in listOf(enter,exit)) {
                            val present=(0 until samples.length()).map {samples.getBoolean(it)}
                            assertTrue("Navigation visibility oscillated within one settings transition",present.zipWithNext().count {it.first!=it.second}<=1)
                        }
                    }
                } finally {
                    compose.mainClock.autoAdvance=true
                    scenario.onActivity {activity->activity.window.removeOnFrameMetricsAvailableListener(listener)}
                }
            }
        } catch(error:Throwable) {failure=error}
        finally {
            metricThread.quitSafely();metricThread.join(5_000)
            val ordered=synchronized(durations) {durations.sorted()}
            report.put("window_frames",ordered.size)
                .put("frames_above_32ms",ordered.count {it>32_000_000L})
                .put("frames_above_100ms",ordered.count {it>100_000_000L})
                .put("p95_total_frame_ms",if(ordered.isEmpty()) JSONObject.NULL else ordered[((ordered.size-1)*.95).toInt()]/1_000_000.0)
                .put("maximum_total_frame_ms",ordered.lastOrNull()?.div(1_000_000.0) ?: JSONObject.NULL)
                .put("timing_qualification","Instrumented debug build and per-frame semantics queries add overhead; use only matched controls, not production FPS claims")
                .put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            val output=File(home,"result.json");output.writeText(report.toString(2))
            instrument.sendStatus(2,Bundle().apply {putString("navigation_stability_report",output.path)})
        }
        failure?.let {throw it}
    }

    private fun visibilitySamples(navigate:()->Unit):JSONArray {
        val samples=JSONArray()
        compose.runOnUiThread(navigate)
        repeat(18) {
            compose.mainClock.advanceTimeByFrame()
            samples.put(compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty())
        }
        return samples
    }
}
