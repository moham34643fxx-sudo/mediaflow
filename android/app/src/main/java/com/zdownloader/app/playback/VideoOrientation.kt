package com.zdownloader.app.playback

import android.content.pm.ActivityInfo
import android.content.res.Configuration

/** Orientation selected for the current video. Manual selections are scoped to that video. */
internal enum class VideoOrientationMode(val key:String,val label:Int) {
    SMART("smart",com.zdownloader.app.R.string.player_orientation_smart),
    PORTRAIT("portrait",com.zdownloader.app.R.string.player_orientation_portrait),
    LANDSCAPE("landscape",com.zdownloader.app.R.string.player_orientation_landscape),
    FOLLOW_DEVICE("device",com.zdownloader.app.R.string.player_orientation_device);

    companion object {
        fun fromKey(value:String?)=entries.firstOrNull {it.key==value} ?: SMART
        fun default()=SMART
        fun default(autoRotate:Boolean)=if(autoRotate) default() else FOLLOW_DEVICE
    }
}

/** Resolves display shape from encoded dimensions, sample aspect ratio, and container rotation. */
internal object VideoOrientation {
    private const val LANDSCAPE_THRESHOLD=1.08f
    private const val PORTRAIT_THRESHOLD=1f/LANDSCAPE_THRESHOLD

    fun preferredAxis(aspect:Float?):VideoOrientationMode?=when {
        aspect==null||!aspect.isFinite()->null
        aspect>=LANDSCAPE_THRESHOLD->VideoOrientationMode.LANDSCAPE
        aspect<=PORTRAIT_THRESHOLD->VideoOrientationMode.PORTRAIT
        else->null
    }

    fun displayAspect(width:Int,height:Int,pixelWidthHeightRatio:Float=1f,rotationDegrees:Int=0):Float? {
        if(width<=0||height<=0||!pixelWidthHeightRatio.isFinite()||pixelWidthHeightRatio !in .1f..10f) return null
        var aspect=width.toFloat()*pixelWidthHeightRatio/height
        val rotation=((rotationDegrees%360)+360)%360
        when(rotation) {
            90,270->aspect=1f/aspect
            0,180->Unit
            else->return null
        }
        return aspect.takeIf {it.isFinite()&&it>0f}
    }

    /**
     * Ambiguous shapes intentionally follow the device. This keeps square/near-square
     * content from forcing an otherwise unnecessary orientation change.
     */
    fun requestedOrientation(mode:VideoOrientationMode,aspect:Float?):Int {
        val axis=when(mode) {
            VideoOrientationMode.PORTRAIT->VideoOrientationMode.PORTRAIT
            VideoOrientationMode.LANDSCAPE->VideoOrientationMode.LANDSCAPE
            VideoOrientationMode.FOLLOW_DEVICE->VideoOrientationMode.FOLLOW_DEVICE
            VideoOrientationMode.SMART->preferredAxis(aspect) ?: VideoOrientationMode.FOLLOW_DEVICE
        }
        return when(axis) {
            VideoOrientationMode.PORTRAIT->ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            VideoOrientationMode.LANDSCAPE->ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else->ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    fun expectedConfiguration(requestedOrientation:Int):Int?=when(requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT->Configuration.ORIENTATION_PORTRAIT
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE->Configuration.ORIENTATION_LANDSCAPE
        else->null
    }

    fun nextToggleMode(mode:VideoOrientationMode,currentOrientation:Int,aspect:Float?):VideoOrientationMode {
        val currentAxis=when(mode) {
            VideoOrientationMode.PORTRAIT->VideoOrientationMode.PORTRAIT
            VideoOrientationMode.LANDSCAPE->VideoOrientationMode.LANDSCAPE
            else->when(currentOrientation) {
                Configuration.ORIENTATION_PORTRAIT->VideoOrientationMode.PORTRAIT
                Configuration.ORIENTATION_LANDSCAPE->VideoOrientationMode.LANDSCAPE
                else->preferredAxis(aspect) ?: VideoOrientationMode.PORTRAIT
            }
        }
        return if(currentAxis==VideoOrientationMode.LANDSCAPE) VideoOrientationMode.PORTRAIT
            else VideoOrientationMode.LANDSCAPE
    }
}
