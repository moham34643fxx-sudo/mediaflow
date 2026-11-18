package com.zdownloader.app.playback

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable internal fun SharedVideoSurface(connection:PlaybackConnection,modifier:Modifier=Modifier,resize:Int=AspectRatioFrameLayout.RESIZE_MODE_FIT,subtitleControlInset:Int=0) {
    val context=LocalContext.current
    val player by connection.player.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val view=remember(context) {PlayerView(context).apply {useController=false;setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)}}
    DisposableEffect(view) {onDispose {view.player=null}}
    AndroidView(factory={view},modifier=modifier,update={
        it.player=player;it.resizeMode=resize
        val color=when(connection.repository.number("subtitle_color",0f).toInt()) {1->android.graphics.Color.CYAN;2->android.graphics.Color.YELLOW;else->android.graphics.Color.WHITE}
        val background=android.graphics.Color.argb((connection.repository.number("subtitle_opacity",.5f)*255).toInt().coerceIn(0,255),0,0,0)
        it.subtitleView?.apply {
            // Keep every cue inside the video surface but above visible transport controls.
            // Pad the native output viewport, not its parent: shrinking the child
            // would also shrink fractional text sizing and can clip larger captions.
            if(childCount>0) getChildAt(0).setPadding(0,0,0,subtitleControlInset)
            setApplyEmbeddedStyles(false)
            setFractionalTextSize(.045f*connection.repository.number("subtitle_size",1f),true)
            setBottomPaddingFraction(connection.repository.number("subtitle_bottom",.15f))
            setStyle(CaptionStyleCompat(color,background,android.graphics.Color.TRANSPARENT,CaptionStyleCompat.EDGE_TYPE_OUTLINE,android.graphics.Color.BLACK,null))
        }
        // Session extras trigger native subtitle style updates without a second renderer.
        state.extras.size()
    })
}

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable internal fun VideoPlayerScreen(connection:PlaybackConnection,state:PlaybackState,activity:PlayerActivity,sheet:(String)->Unit) {
    var controls by rememberSaveable {mutableStateOf(true)}
    var transportHeight by remember {mutableIntStateOf(0)}
    val navigationBottom=WindowInsets.safeDrawing.getBottom(LocalDensity.current)
    var resize by rememberSaveable {mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)}
    var feedback by remember {mutableStateOf<String?>(null)}
    var gesturePosition by remember {mutableStateOf<Long?>(null)}
    var interaction by remember {mutableIntStateOf(0)}
    val audio=remember(activity) {activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager}
    val enabled=connection.repository.boolean("gestures",true)
    val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
    LaunchedEffect(controls,state.playing,interaction) {if(controls&&state.playing&&!activity.pip) {kotlinx.coroutines.delay(3500);controls=false}}
    LaunchedEffect(feedback) {if(feedback!=null&&gesturePosition==null) {kotlinx.coroutines.delay(1200);feedback=null}}
    LaunchedEffect(controls,activity.pip) {
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(controls&&!activity.pip) {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity) {
        val original=activity.window.attributes.screenBrightness
        onDispose {activity.window.attributes=activity.window.attributes.apply {screenBrightness=original};activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);controller.show(WindowInsetsCompat.Type.systemBars())}
    }
    val showLabel=text(R.string.player_show_controls)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video_player").semantics {onClick(showLabel) {controls=true;interaction++;true}}
        .pointerInput(Unit) {awaitEachGesture {awaitFirstDown(requireUnconsumed=false);interaction++;waitForUpOrCancellation()}}) {
        SharedVideoSurface(connection,Modifier.fillMaxSize().testTag("video_surface"),resize,
            if(controls&&!activity.pip) transportHeight+navigationBottom else 0)
        if(!activity.pip) {
            // Gesture detection is inset from Android's navigation edges; no exclusion request.
            Box(Modifier.fillMaxSize().padding(horizontal=24.dp).testTag("video_gesture_area").semantics {onClick(showLabel) {controls=true;true}}
                .pointerInput(enabled) {detectTapGestures(onTap={controls=!controls},onDoubleTap={point->if(enabled) {connection.jump(if(point.x<size.width/2) -10000 else 10000);feedback=playTime(connection.state.value.position)}})}
                .pointerInput(enabled,state.current?.uri) {
                    var dx=0f;var dy=0f;var origin=0L;var startX=0f;var volume=0;var brightness=.5f
                    detectDragGestures(onDragStart={point->dx=0f;dy=0f;origin=connection.state.value.position;startX=point.x;volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);brightness=activity.window.attributes.screenBrightness.takeIf {it>=0} ?: .5f},
                        onDragEnd={gesturePosition?.let(connection::seek);gesturePosition=null;feedback=null},onDragCancel={gesturePosition=null;feedback=null}) {change,delta->
                        if(enabled) {
                            change.consume();dx+=delta.x;dy+=delta.y
                            if(abs(dx)>abs(dy)) {
                                val duration=connection.state.value.duration
                                if(duration>0) {gesturePosition=(origin+(dx/size.width*minOf(duration,120000L)).toLong()).coerceIn(0,duration);feedback=playTime(gesturePosition!!)}
                            } else if(startX<size.width/2) {
                                val value=(brightness-dy/size.height).coerceIn(.02f,1f)
                                activity.window.attributes=activity.window.attributes.apply {screenBrightness=value}
                                feedback=activity.playerText(R.string.player_brightness,(value*100).toInt())
                            } else {
                                val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val value=(volume-dy/size.height*max).toInt().coerceIn(0,max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0)
                                feedback=activity.playerText(R.string.player_volume,if(max>0) value*100/max else 0)
                            }
                        }
                    }
                })
            feedback?.let {Surface(Modifier.align(Alignment.Center),color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),shape=MaterialTheme.shapes.large) {Text(it,Modifier.padding(horizontal=24.dp,vertical=16.dp),style=MaterialTheme.typography.titleMedium)}}
            AnimatedVisibility(controls,enter=fadeIn(tween(FlowMotion.Fast)),exit=fadeOut(tween(FlowMotion.Fast))) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.76f),Color.Transparent,Color.Black.copy(alpha=.88f)))).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    FlowIconButton(FlowIcons.Back,text(R.string.back),activity::closePlayer)
                    Text(state.current?.title.orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    FlowIconButton(Icons.Outlined.VisibilityOff,text(R.string.player_hide_controls),{controls=false})
                    FlowIconButton(FlowIcons.More,text(R.string.settings),{sheet("options")},Modifier.testTag("video_options"))
                }
                Spacer(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().heightIn(max=300.dp).onSizeChanged {transportHeight=it.height}.testTag("video_transport_controls").verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    PlayerSeekBar(connection,state)
                    TransportControls(connection,state,video=true)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        TextButton({sheet("speed")},Modifier.testTag("player_speed")) {Text("${state.speed}×")}
                        FlowIconButton(Icons.Outlined.Subtitles,text(R.string.player_tracks),{sheet("tracks")},Modifier.testTag("player_tracks"))
                        FlowIconButton(FlowIcons.Playlist,text(R.string.player_queue),{sheet("queue")},Modifier.testTag("player_queue"))
                        TextButton({resize=when(resize) {AspectRatioFrameLayout.RESIZE_MODE_FIT->AspectRatioFrameLayout.RESIZE_MODE_FILL;AspectRatioFrameLayout.RESIZE_MODE_FILL->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FIT}}) {
                            Text(text(when(resize) {AspectRatioFrameLayout.RESIZE_MODE_FILL->R.string.player_fill;AspectRatioFrameLayout.RESIZE_MODE_ZOOM->R.string.player_zoom;else->R.string.player_fit}))
                        }
                        FlowIconButton(Icons.Outlined.ScreenRotation,text(R.string.player_orientation_control),{sheet("options")},Modifier.testTag("player_orientation_control"))
                        if(activity.pipSupported) FlowIconButton(Icons.Outlined.PictureInPictureAlt,text(R.string.player_pip),activity::enterPip,Modifier.testTag("player_pip"))
                    }
                }
            }
            }
        }
    }
}
