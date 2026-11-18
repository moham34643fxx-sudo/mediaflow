package com.zdownloader.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContrastTest {
    private fun contrast(a:Color,b:Color):Float {
        val first=a.luminance();val second=b.luminance()
        return (maxOf(first,second)+.05f)/(minOf(first,second)+.05f)
    }
    @Test fun textActionsAndInputBoundariesMeetContrastTargets() {
        for(colors in listOf(Spectrum.Dark,Spectrum.Light)) {
            assertTrue("Body text",contrast(colors.onSurface,colors.surface)>=4.5f)
            assertTrue("Supporting text",contrast(colors.onSurfaceVariant,colors.surface)>=4.5f)
            assertTrue("Teal action text",contrast(colors.primary,colors.background)>=4.5f)
            assertTrue("Primary button text",contrast(colors.onPrimary,colors.primary)>=4.5f)
            assertTrue("Unfocused input boundary",contrast(colors.outline,colors.surface)>=3f)
        }
    }
}
