package com.zdownloader.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.LocalFlowMotionEnabled
import com.zdownloader.app.ui.MediaFlowLaunchReveal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LaunchRevealTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lettersAppearInOrderAtFixedPositionsBeforeHandoffAtLargeRtlText() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName == "com.zdownloader.app.candidate0965")
        val start = mutableStateOf(false)
        val events = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, 1.6f),
                        LocalLayoutDirection provides LayoutDirection.Rtl
                    ) {
                        MediaFlowLaunchReveal(start.value, { events += "full word" }) { events += "finished" }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(150)
            val name = compose.onNodeWithTag("brand_launch_name").assertContentDescriptionEquals("MEDIAFLOW")
            val initialBounds = name.fetchSemanticsNode().boundsInRoot
            val mark = compose.onNodeWithTag("brand_launch_mark")
            assertEquals("M", visibleLetters(name))
            assertTrue(hasVisibleEmblem(mark, .23f))
            assertFalse(hasBrightEmblem(mark))
            compose.runOnIdle { assertTrue(events.isEmpty()); start.value = true }
            val prefixes = mutableListOf("M")
            repeat(27) {
                compose.mainClock.advanceTimeByFrame()
                val prefix = visibleLetters(name)
                if (prefix != prefixes.last()) prefixes += prefix
                assertEquals(initialBounds, name.fetchSemanticsNode().boundsInRoot)
            }
            assertEquals((1..9).map { "MEDIAFLOW".take(it) }, prefixes)
            assertTrue(hasBrightEmblem(mark))
            assertEquals("MEDIAFLOW", visibleLetters(name))
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { assertEquals(listOf("full word"), events) }
            // The mount and the first frame of the fade can land on distinct clock ticks.
            compose.mainClock.advanceTimeBy(144)
            compose.runOnIdle { assertEquals(listOf("full word", "finished"), events) }
        }
    }

    private fun hasBrightEmblem(node: SemanticsNodeInteraction) = hasVisibleEmblem(node, .55f)

    private fun hasVisibleEmblem(node: SemanticsNodeInteraction, minimumBlue: Float): Boolean {
        val pixels = node.captureToImage().toPixelMap()
        return (0 until pixels.width step 4).any { x ->
            (0 until pixels.height step 4).any { y -> pixels[x, y].blue > minimumBlue }
        }
    }

    private fun visibleLetters(node: SemanticsNodeInteraction): String {
        val pixels = node.captureToImage().toPixelMap()
        val bands = listOf(4..28, 31..47, 50..69, 72..77, 79..99, 102..116, 120..134, 136..157, 158..187)
        return buildString {
            for ((index, band) in bands.withIndex()) {
                val left = band.first * pixels.width / 192
                val right = band.last * pixels.width / 192
                val visible = (left..right).any { x ->
                    (pixels.height / 4 until pixels.height * 3 / 4).any { y -> pixels[x, y].blue > 0.20f }
                }
                if (visible) append("MEDIAFLOW"[index])
            }
        }
    }

    @Test fun reducedMotionShowsSettledBrandAndCompletesPromptly() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName == "com.zdownloader.app.candidate0965")
        val start = mutableStateOf(false)
        var finishes = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalFlowMotionEnabled provides false) {
                        MediaFlowLaunchReveal(start.value, finished = { finishes++ })
                    }
                }
            }
            assertTrue(hasBrightEmblem(compose.onNodeWithTag("brand_launch_mark")))
            assertEquals("MEDIAFLOW", visibleLetters(compose.onNodeWithTag("brand_launch_name")))
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { assertEquals(0, finishes); start.value = true }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { assertEquals(1, finishes) }
        }
    }
}
