package com.zdownloader.app.playback

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zdownloader.app.R
import com.zdownloader.app.ZApplication
import java.lang.ref.WeakReference
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only a live preview owner; never starts, restores, or publishes a MediaSession. */
internal object ExternalAudioPreview {
    private var owner=WeakReference<ExternalAudioOpenActivity>(null)
    val player:Player? get()=owner.get()?.previewPlayer
    fun register(activity:ExternalAudioOpenActivity) {
        if(owner.get()!==activity) stop()
        owner=WeakReference(activity)
    }
    fun stop() {owner.get()?.takeUnless {it.handoffInProgress||it.resolvingPreview}?.closePreview()}
    fun unregister(activity:ExternalAudioOpenActivity) {if(owner.get()===activity) owner.clear()}
}

/** Nonvisual resolver and URI-grant owner for the lifetime of one floating-only preview. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ExternalAudioOpenActivity:ComponentActivity() {
    internal var previewPlayer:ExoPlayer?=null
        private set
    private var card:FloatingAudioWindow?=null
    private var previewMedia:PlaybackMedia?=null
    private var request:Job?=null
    private var handoffJob:Job?=null
    private var generation=0L
    internal var handoffInProgress=false
        private set
    internal var resolvingPreview=false
        private set
    private var previewWasPlaying=false
    private var movedBehind=false
    private val previewEvents=object:Player.Listener {
        override fun onIsPlayingChanged(isPlaying:Boolean) {
            if(isPlaying&&!movedBehind) {
                movedBehind=true
                moveTaskToBack(true)
            }
        }
        override fun onPlayerError(error:PlaybackException) {unavailable()}
    }
    private val permission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(Settings.canDrawOverlays(this)) receive() else closePreview()
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // A killed/recreated preview never silently resumes playback.
        if(savedInstanceState!=null||!ExternalAudioOpen.acceptsIntent(this,intent)) {finishAndRemoveTask();return}
        ExternalAudioPreview.register(this)
        receive()
    }

    private fun releasePlayer() {
        val oldCard=card;card=null
        val oldPlayer=previewPlayer;previewPlayer=null
        previewMedia=null
        oldCard?.release()
        oldPlayer?.release()
    }

    private fun receive() {
        val owner=++generation
        resolvingPreview=true
        handoffJob?.cancel();handoffJob=null;handoffInProgress=false
        request?.cancel()
        releasePlayer()
        if(!ExternalAudioOpen.acceptsIntent(this,intent)) {closePreview();return}
        movedBehind=false
        val incoming=Intent(intent)
        request=lifecycleScope.launch {
            try {
                val media=resolveMedia(incoming)
                if(owner!=generation) return@launch
                if(!Settings.canDrawOverlays(this@ExternalAudioOpenActivity)) {
                    permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                    return@launch
                }
                endNormalPlayback()
                if(owner!=generation) return@launch
                startPreview(media)
            } catch(_:TimeoutCancellationException) {if(owner==generation) unavailable()}
            catch(error:CancellationException) {throw error}
            catch(_:IOException) {if(owner==generation) unavailable()}
            catch(_:SecurityException) {if(owner==generation) unavailable()}
            catch(_:IllegalArgumentException) {if(owner==generation) unavailable()}
            catch(_:IllegalStateException) {if(owner==generation) unavailable()}
        }
    }

    private suspend fun resolveMedia(incoming:Intent)=withContext(Dispatchers.IO) {
        val uri=ExternalAudioOpen.contentUri(this@ExternalAudioOpenActivity,incoming)
        PlaybackRepository(this@ExternalAudioOpenActivity).resolve(uri.toString())
    }

    private suspend fun endNormalPlayback() {
        // No service connection is created on a cold external open.
        if(!PlaybackRuntime.active) return
        val connection=(application as ZApplication).playback
        connection.attach()
        try {connection.endNormalForExternalPreview()} finally {connection.detach()}
    }

    private fun startPreview(media:PlaybackMedia) {
        resolvingPreview=false
        val engine=ExoPlayer.Builder(this).setMediaSourceFactory(
            DefaultMediaSourceFactory(DataSource.Factory {LocalPlaybackDataSource(this)})).build()
        previewPlayer=engine
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
        engine.setHandleAudioBecomingNoisy(true)
        engine.addListener(previewEvents)
        engine.setMediaItem(media.item())
        engine.prepare()
        previewMedia=media
        val window=FloatingAudioWindow(this,engine,
            onPreviewClosed={closePreview()},
            onPreviewOpen={openInFullPlayer()})
        card=window
        if(!window.enable()) {closePreview();return}
        engine.play()
    }

    private fun openInFullPlayer() {
        if(handoffInProgress||isFinishing) return
        val engine=previewPlayer ?: return
        val media=previewMedia ?: return
        handoffInProgress=true
        previewWasPlaying=engine.isPlaying||engine.playWhenReady
        val position=engine.currentPosition.coerceAtLeast(0)
        engine.pause()
        val owner=generation
        handoffJob=lifecycleScope.launch {handoffToFullPlayer(media,position,owner)}
    }

    private suspend fun handoffToFullPlayer(media:PlaybackMedia,position:Long,owner:Long) {
        val connection=(application as ZApplication).playback
        var attachedConnection:PlaybackConnection?=null
        try {
            connection.attach();attachedConnection=connection
            connection.handoffExternalPreview(media,position) {owner==generation&&!isFinishing}
            if(owner!=generation||isFinishing) return
            startActivity(Intent(this,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            connection.detach();attachedConnection=null
            handoffInProgress=false
            closePreview()
        } catch(error:TimeoutCancellationException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } catch(_:SecurityException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } catch(_:IllegalStateException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } catch(_:IllegalArgumentException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } catch(_:android.content.ActivityNotFoundException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } catch(_:java.util.concurrent.ExecutionException) {
            recoverFailedHandoff(attachedConnection,media,owner)
        } finally {
            attachedConnection?.detach()
            if(owner==generation&&!isFinishing) {
                handoffInProgress=false
                handoffJob=null
            }
        }
    }

    private suspend fun recoverFailedHandoff(connection:PlaybackConnection?,media:PlaybackMedia,owner:Long) {
        if(owner!=generation||isFinishing) return
        var previewMayResume=!PlaybackRuntime.active
        if(connection!=null&&PlaybackRuntime.active) {
            previewMayResume=try {
                connection.stopPreviewHandoff(media.uri)
            } catch(_:TimeoutCancellationException) {false}
            catch(_:SecurityException) {false}
            catch(_:IllegalStateException) {false}
            catch(_:java.util.concurrent.ExecutionException) {false}
        }
        if(owner!=generation||isFinishing) return
        if(previewMayResume&&previewWasPlaying) previewPlayer?.play()
        Toast.makeText(this,R.string.player_error_unavailable,Toast.LENGTH_LONG).show()
    }

    private fun unavailable() {
        Toast.makeText(this,R.string.player_error_unavailable,Toast.LENGTH_LONG).show()
        closePreview()
    }

    internal fun closePreview() {
        generation++
        resolvingPreview=false
        if(handoffInProgress) {
            handoffInProgress=false
            handoffJob?.cancel();handoffJob=null
            previewMedia?.uri?.let {uri->if(PlaybackRuntime.active) (application as ZApplication).playback.cancelPreviewHandoff(uri)}
        }
        request?.cancel();request=null
        ExternalAudioPreview.unregister(this)
        releasePlayer()
        if(!isFinishing) finishAndRemoveTask()
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);receive()
    }

    override fun onDestroy() {
        generation++;handoffJob?.cancel()
        request?.cancel()
        ExternalAudioPreview.unregister(this)
        releasePlayer()
        super.onDestroy()
    }
}
