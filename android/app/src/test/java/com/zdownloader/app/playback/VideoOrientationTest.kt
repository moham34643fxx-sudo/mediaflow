package com.zdownloader.app.playback

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOrientationTest {
    @Test fun displayAspectUsesPixelRatioAndQuarterTurnRotation() {
        assertEquals(16f/9f,VideoOrientation.displayAspect(720,576,64f/45f)!!,.001f)
        assertEquals(9f/16f,VideoOrientation.displayAspect(1920,1080,1f,90)!!,.001f)
        assertEquals(16f/9f,VideoOrientation.displayAspect(1920,1080,1f,180)!!,.001f)
        assertNull(VideoOrientation.displayAspect(0,1080))
        assertNull(VideoOrientation.displayAspect(1920,1080,Float.NaN))
        assertNull(VideoOrientation.displayAspect(1920,1080,1f,45))
    }

    @Test fun smartModeUsesStableFallbackForSquareAndAmbiguousShapes() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            VideoOrientation.requestedOrientation(VideoOrientationMode.SMART,16f/9f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            VideoOrientation.requestedOrientation(VideoOrientationMode.SMART,9f/16f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            VideoOrientation.requestedOrientation(VideoOrientationMode.SMART,1f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            VideoOrientation.requestedOrientation(VideoOrientationMode.SMART,1.04f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            VideoOrientation.requestedOrientation(VideoOrientationMode.SMART,null))
    }

    @Test fun manualModesOverrideSmartAndFollowDeviceDoesNotForceAnAxis() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            VideoOrientation.requestedOrientation(VideoOrientationMode.PORTRAIT,16f/9f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            VideoOrientation.requestedOrientation(VideoOrientationMode.LANDSCAPE,9f/16f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            VideoOrientation.requestedOrientation(VideoOrientationMode.FOLLOW_DEVICE,16f/9f))
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
            VideoOrientation.expectedConfiguration(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))
        assertNull(VideoOrientation.expectedConfiguration(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR))
    }
}
