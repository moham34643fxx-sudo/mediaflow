package com.zdownloader.app.playback

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.zdownloader.app.ZApplication
import com.zdownloader.app.R
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.data.Settings
import com.zdownloader.app.ui.*
import java.util.Locale

class PlayerActivity:ComponentActivity() {
    companion object {private const val STATE_EXTERNAL_REQUEST_ID="mediaflow.external_open_request_id"}
    private var stringContext:Context?=null
    internal var externalRequestId:String?=null
        private set
    internal var waitingForExternalVideo by mutableStateOf(false)
    internal var externalVideoStartupTimedOut by mutableStateOf(false)
    private var audioOnlyHandoff=false
    private var closingPlayer=false
    private var videoClosedOnStop=false
    internal var selectingSubtitle=false
    internal fun playerText(id:Int,vararg args:Any)=(stringContext ?: this).getString(id,*args)
    internal val connection get()=(application as ZApplication).playback
    private val audioHandoff=AudioPlaybackHandoff(this,{connection},{stringContext ?: this}) {audioOnlyHandoff=true;finish()}
    internal fun backgroundAudio()=audioHandoff.background()
    internal fun floatingAudio()=audioHandoff.floating()
    internal fun stopAudio()=audioHandoff.stop()
    internal fun applyVideoOrientation(mode:VideoOrientationMode,aspect:Float?) {
        if(!pip) requestedOrientation=VideoOrientation.requestedOrientation(mode,aspect)
    }
    private fun applyInitialOrientation() {
        if(waitingForExternalVideo) {
            applyVideoOrientation(VideoOrientationMode.FOLLOW_DEVICE,null)
            return
        }
        val state=connection.state.value
        val media=state.current
        val mode=VideoOrientationMode.default(connection.repository.boolean("auto_rotate",true))
        applyVideoOrientation(if(media?.video==true&&!state.extras.getBoolean("audio_only")) mode else VideoOrientationMode.FOLLOW_DEVICE,
            media?.takeIf {it.video}?.displayAspect ?: state.videoDisplayAspect)
    }
    internal var pip by mutableStateOf(false)
    private fun resumePendingExternalOpen() {
        connection.repository.pendingExternalOpen()?.let {pending->
            externalRequestId=pending.id
            connection.playExternal(pending.intent,pending.id)
        }
    }
    val pipSupported get()=Build.VERSION.SDK_INT>=26&&packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(ExternalAudioOpen.acceptsIntent(this,intent)) {
            startActivity(Intent(intent).setClass(this,ExternalAudioOpenActivity::class.java));finish();return
        }
        if(intent.action==Intent.ACTION_VIEW) {
            val requestId=savedInstanceState?.getString(STATE_EXTERNAL_REQUEST_ID) ?: java.util.UUID.randomUUID().toString()
            externalRequestId=requestId
            // Activity saved state means the task was recreated, not that asynchronous
            // provider resolution reached the MediaSession. The handled marker is stored
            // atomically with its queue commit and also records an intentional supersession.
            val handled=connection.repository.externalRequestHandled(requestId)
            waitingForExternalVideo=savedInstanceState==null||!handled
            if(waitingForExternalVideo)
                connection.playExternal(Intent(intent),requestId)
        } else {
            // If the Activity task had to be recreated without its original launch
            // intent, resume only an app-private request that was never queue-committed.
            resumePendingExternalOpen()
        }
        applyInitialOrientation()
        if(Build.VERSION.SDK_INT>=28) window.attributes=window.attributes.apply {layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val preferences by remember {SettingsRepository(applicationContext).flow}.collectAsStateWithLifecycle(initialValue=Settings())
            val current=LocalConfiguration.current
            val configuration=remember(current,preferences.language) {Configuration(current).apply {setLocale(Locale.forLanguageTag(preferences.language))}}
            val localized=remember(configuration) {createConfigurationContext(configuration)}
            SideEffect {stringContext=localized}
            CompositionLocalProvider(LocalSpectrumContext provides localized,LocalConfiguration provides configuration,LocalLayoutDirection provides if(preferences.language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(Spectrum.Dark) {Surface(Modifier.fillMaxSize(),color=Spectrum.Dark.background,contentColor=Spectrum.Dark.onSurface) {PlaybackLifecycle(connection);PlayerScreen(connection,this)}}
            }
        }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(ExternalAudioOpen.acceptsIntent(this,intent)) {
            waitingForExternalVideo=false
            startActivity(Intent(intent).setClass(this,ExternalAudioOpenActivity::class.java));return
        }
        if(intent.action==Intent.ACTION_VIEW) {
            val requestId=java.util.UUID.randomUUID().toString();externalRequestId=requestId;waitingForExternalVideo=true;externalVideoStartupTimedOut=false
            applyVideoOrientation(VideoOrientationMode.FOLLOW_DEVICE,null)
            connection.playExternal(Intent(intent),requestId)
        } else {waitingForExternalVideo=false;externalRequestId=null;resumePendingExternalOpen()}
    }
    override fun onSaveInstanceState(outState:Bundle) {
        externalRequestId?.let {outState.putString(STATE_EXTERNAL_REQUEST_ID,it)}
        super.onSaveInstanceState(outState)
    }
    internal fun updatePip(state:PlaybackState) {
        if(Build.VERSION.SDK_INT<26||!pipSupported) return
        val ratio=state.aspect.coerceIn(1f/2.39f,2.39f)
        val action=RemoteAction(Icon.createWithResource(this,if(state.playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),playerText(if(state.playing) R.string.player_pause else R.string.player_play),playerText(R.string.player_now_playing),
            PendingIntent.getBroadcast(this,57003,Intent(this,PlaybackActionReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val builder=PictureInPictureParams.Builder().setAspectRatio(Rational((ratio*1000).toInt(),1000)).setActions(listOf(action))
        val source=android.graphics.Rect()
        if(window.decorView.getGlobalVisibleRect(source)&&!source.isEmpty) builder.setSourceRectHint(source)
        if(Build.VERSION.SDK_INT>=31) builder.setAutoEnterEnabled(state.current?.video==true&&state.playing&&!state.extras.getBoolean("audio_only"))
        setPictureInPictureParams(builder.build())
    }
    internal fun enterPip() {if(Build.VERSION.SDK_INT>=26&&pipSupported&&connection.state.value.current?.video==true&&!connection.state.value.extras.getBoolean("audio_only")) {
        val state=connection.state.value;updatePip(state)
        val source=android.graphics.Rect();window.decorView.getGlobalVisibleRect(source)
        val builder=PictureInPictureParams.Builder().setAspectRatio(Rational((state.aspect.coerceIn(1f/2.39f,2.39f)*1000).toInt(),1000))
        if(!source.isEmpty) builder.setSourceRectHint(source)
        enterPictureInPictureMode(builder.build())
    }}
    override fun onUserLeaveHint() {super.onUserLeaveHint();if(Build.VERSION.SDK_INT<31&&connection.state.value.playing&&connection.state.value.current?.video==true&&!connection.state.value.extras.getBoolean("audio_only")) enterPip()}
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode:Boolean,newConfig:Configuration) {super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig);pip=isInPictureInPictureMode}
    override fun onStart() {super.onStart();videoClosedOnStop=false}
    override fun onStop() {
        if(!closingPlayer&&!audioOnlyHandoff&&!isChangingConfigurations) {
            // Visible PiP remains STARTED. A stopped PiP Activity is hidden (for
            // example by screen lock), even when Android still reports PiP mode.
            // Retire pending video opens too: metadata may not have reached state.
            if(selectingSubtitle) connection.pause() else {connection.closeVideo();videoClosedOnStop=true}
        }
        super.onStop()
    }
    internal fun closePlayer() {
        if(!closingPlayer&&!audioOnlyHandoff) connection.closeVideo()
        closingPlayer=true
        finish()
    }
    override fun onDestroy() {
        if(isFinishing&&!isChangingConfigurations&&!closingPlayer&&!audioOnlyHandoff&&!videoClosedOnStop) connection.closeVideo()
        super.onDestroy()
    }
    internal fun playCurrentAsAudioOnly() {
        lifecycleScope.launch {
            try {if(connection.useAudioOnly()) {audioOnlyHandoff=true;finish()}}
            catch(error:kotlinx.coroutines.CancellationException) {throw error}
            catch(_:Exception) {connection.option("audio_only",false)}
        }
    }
}

class PlaybackActionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {(context.applicationContext as ZApplication).playback.toggle()}
}
