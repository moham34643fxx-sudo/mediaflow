@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zdownloader.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FlowProgressRenderingTest {
    @get:Rule val compose=createComposeRule()

    @Test fun progressIsTruthfulAndFillsFromTheReadingEdgeInBothDirections() {
        val direction=mutableStateOf(LayoutDirection.Ltr)
        val progress=mutableFloatStateOf(0f)
        val scheme=mutableStateOf(Spectrum.Dark)
        compose.setContent {MaterialTheme(colorScheme=scheme.value) {
            CompositionLocalProvider(LocalFlowMotionEnabled provides false,LocalLayoutDirection provides direction.value) {
                MediaFlowProgress(progress.floatValue,Modifier.width(240.dp).testTag("progress_fixture"))
            }
        }}
        for(colors in listOf(Spectrum.Dark,Spectrum.Light)) for(layout in listOf(LayoutDirection.Ltr,LayoutDirection.Rtl)) for(fraction in listOf(0f,.5f,1f)) {
            compose.runOnIdle {scheme.value=colors;direction.value=layout;progress.floatValue=fraction}
            val node=compose.onNodeWithTag("progress_fixture")
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(fraction,0f..1f)))
            val pixels=node.captureToImage().toPixelMap()
            for(position in listOf(.25f,.75f)) {
                val pixel=pixels[(pixels.width*position).toInt(),pixels.height/2]
                val track=colors.surfaceContainerHighest
                val filled=maxOf(kotlin.math.abs(pixel.red-track.red),kotlin.math.abs(pixel.green-track.green),kotlin.math.abs(pixel.blue-track.blue))>.1f
                val expected=if(layout==LayoutDirection.Ltr) position<fraction else position>1f-fraction
                assertEquals("$layout progress=$fraction pixel=$position",expected,filled)
            }
        }
    }
}
