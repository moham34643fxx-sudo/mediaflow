package com.zdownloader.app.playback

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.zdownloader.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/** Permission belongs only to an explicit floating-player request, never to background audio. */
internal class AudioPlaybackHandoff(
    private val activity:ComponentActivity,
    private val connection:()->PlaybackConnection,
    private val text:()->Context,
    private val dismiss:()->Unit
) {
    private val permission=activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {showFloating()}
    fun background()=launchHandoff {
        if(connection().backgroundAudio()) dismiss()
    }
    fun stop()=launchHandoff {connection().stopAudio();dismiss()}
    fun floating() {
        if(Settings.canDrawOverlays(activity)) {showFloating();return}
        android.app.AlertDialog.Builder(activity)
            .setTitle(text().getString(R.string.audio_popup_floating_title))
            .setMessage(text().getString(R.string.audio_popup_overlay_explanation))
            .setPositiveButton(text().getString(R.string.audio_popup_allow_overlay)) {_,_->
                try {permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,"package:${activity.packageName}".toUri()))}
                catch(_:android.content.ActivityNotFoundException) {background()}
                catch(_:SecurityException) {background()}
            }
            .setNegativeButton(text().getString(R.string.audio_popup_notification_only)) {_,_->background()}
            .setNeutralButton(android.R.string.cancel,null).show()
    }
    private fun showFloating()=launchHandoff {
        if(!Settings.canDrawOverlays(activity)||!connection().enableFloatingAudio()) {
            if(!connection().backgroundAudio()) return@launchHandoff
            Toast.makeText(activity,text().getString(R.string.audio_popup_notification_fallback),Toast.LENGTH_LONG).show()
        }
        dismiss()
    }
    private fun launchHandoff(block:suspend ()->Unit) {activity.lifecycleScope.launch {
        try {block()}
        catch(_:TimeoutCancellationException) {failed()}
        catch(error:CancellationException) {throw error}
        catch(_:java.util.concurrent.ExecutionException) {failed()}
        catch(_:IllegalStateException) {failed()}
        catch(_:SecurityException) {failed()}
    }}
    private fun failed() {Toast.makeText(activity,text().getString(R.string.player_error_unavailable),Toast.LENGTH_LONG).show()}
}
