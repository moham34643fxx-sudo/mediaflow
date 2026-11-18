package com.zdownloader.app.playback

import android.content.Intent
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zdownloader.app.R
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable internal fun PlaybackLifecycle(connection:PlaybackConnection) {
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,connection) {
        var attached=false
        fun attach() {if(!attached) {connection.attach();attached=true}}
        fun detach() {if(attached) {attached=false;connection.detach()}}
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach()
        val observer=LifecycleEventObserver {_,event->when(event) {Lifecycle.Event.ON_START->attach();Lifecycle.Event.ON_STOP->detach();else->Unit}}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);detach()}
    }
}

@Composable internal fun PlaybackRequestError(connection:PlaybackConnection) {
    val error by remember(connection) {connection.state.map {it.error}.distinctUntilChanged()}.collectAsStateWithLifecycle(null)
    error?.let {message->AlertDialog(onDismissRequest=connection::clearError,title={Text(text(R.string.problem))},text={Text(text(message))},confirmButton={TextButton(connection::clearError) {Text(text(R.string.close))}})}
}

@Composable internal fun PlayerArtwork(media:PlaybackMedia?,modifier:Modifier=Modifier,fallback:(@Composable ()->Unit)?=null) {
    val fallbackBrush = Brush.radialGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.18f))) {
        Box(Modifier.background(fallbackBrush),contentAlignment=Alignment.Center) {
          key(media?.uri,media?.artwork) {
            var loaded by remember {mutableStateOf(false)}
            if(!loaded&&fallback!=null) fallback()
            else if(!loaded) {
                Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f), CircleShape),contentAlignment=Alignment.Center) {
                    Icon(FlowIcons.Audio,null,Modifier.size(36.dp).testTag("artwork_fallback"),tint=MaterialTheme.colorScheme.primary)
                }
            }
            val context=LocalContext.current
            EmbeddedArtwork.safeUri(context,media?.artwork)?.let {uri->
                AsyncImage(remember(uri) {ImageRequest.Builder(context).data(Uri.parse(uri)).size(640).build()},null,Modifier.fillMaxSize().testTag("embedded_artwork"),contentScale=ContentScale.Crop,onSuccess={loaded=true},onError={loaded=false})
            }
          }
        }
    }
}

@Composable internal fun MiniPlayer(connection:PlaybackConnection,modifier:Modifier=Modifier) {
    val state by connection.state.collectAsStateWithLifecycle()
    val media=state.current ?: return
    if(media.video&&!state.extras.getBoolean("audio_only")) return
    val context=LocalContext.current
    val progress=(state.position.toDouble()/state.duration.coerceAtLeast(1)).coerceIn(0.0,1.0).toFloat()
    Surface(onClick={connection.open(context)},modifier=modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp).testTag("mini_player"),
        shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.2f))) {
        Column {
            Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                PlayerArtwork(media,Modifier.size(42.dp))
                Column(Modifier.weight(1f)) {
                    Text(media.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    media.artist?.let {Text(it,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                FlowIconButton(if(state.playing) FlowIcons.Pause else FlowIcons.Play,text(if(state.playing) R.string.player_pause else R.string.player_play),connection::toggle,Modifier.testTag("mini_play_pause"),accent=true)
                FlowIconButton(Icons.Outlined.SkipNext,text(R.string.player_next),connection::next,enabled=state.queue.size>1)
            }
            if(state.duration>0) LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(2.dp),color=MaterialTheme.colorScheme.primary,trackColor=Color.Transparent,drawStopIndicator={})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerScreen(connection:PlaybackConnection,activity:PlayerActivity) {
    val state by connection.state.collectAsStateWithLifecycle()
    val externalResolution by connection.externalMediaResolution.collectAsStateWithLifecycle()
    var sheet by rememberSaveable {mutableStateOf<String?>(null)}
    val language=LocalConfiguration.current.locales[0].toLanguageTag()
    val configuration=LocalConfiguration.current
    val autoRotate=connection.repository.boolean("auto_rotate",true)
    val defaultOrientation=VideoOrientationMode.default(autoRotate)
    // A manual choice belongs to this item only. Keep it across configuration
    // updates, but do not restore a previous item's saved choice for a new URI.
    var orientationKey by remember(state.current?.uri,defaultOrientation.key) {mutableStateOf(defaultOrientation.key)}
    val orientationMode=VideoOrientationMode.fromKey(orientationKey)
    var externalOrientationFallback by rememberSaveable(activity.externalRequestId) {mutableStateOf(false)}
    val subtitlePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) {
        runCatching {activity.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        connection.subtitle(uri)
    };activity.selectingSubtitle=false}
    LaunchedEffect(Unit) {runCatching {connection.connect()}}
    LaunchedEffect(state.playing,state.aspect,state.current?.video,state.extras.getBoolean("audio_only"),language) {activity.updatePip(state)}
    LaunchedEffect(activity.externalRequestId,activity.waitingForExternalVideo) {
        val requestId=activity.externalRequestId ?: return@LaunchedEffect
        if(!activity.waitingForExternalVideo) return@LaunchedEffect
        val resolution=withTimeoutOrNull(8_000) {
            connection.externalMediaResolution.first {it.requestId==requestId&&it.complete}
        }
        if(resolution==null) {
            activity.externalVideoStartupTimedOut=true
            return@LaunchedEffect
        }
        val media=resolution.media
        if(media?.video!=true) {
            activity.waitingForExternalVideo=false
            return@LaunchedEffect
        }
        val current=withTimeoutOrNull(5_000) {connection.state.first {it.current?.uri==media.uri}}
        if(current==null&&activity.waitingForExternalVideo) activity.externalVideoStartupTimedOut=true
    }
    LaunchedEffect(activity.externalRequestId,activity.waitingForExternalVideo,externalResolution.requestId,
        externalResolution.complete,externalResolution.media?.uri,externalResolution.media?.displayAspect,
        state.current?.uri,configuration.orientation,defaultOrientation,externalOrientationFallback) {
        val requestId=activity.externalRequestId ?: return@LaunchedEffect
        val media=externalResolution.media
        if(!activity.waitingForExternalVideo||externalResolution.requestId!=requestId||!externalResolution.complete||media?.video!=true) return@LaunchedEffect
        val requested=if(externalOrientationFallback) VideoOrientationMode.FOLLOW_DEVICE
            else defaultOrientation
        activity.applyVideoOrientation(requested,media.displayAspect)
        val expected=VideoOrientation.expectedConfiguration(VideoOrientation.requestedOrientation(requested,media.displayAspect))
        if(!externalOrientationFallback&&expected!=null&&configuration.orientation!=expected) {
            kotlinx.coroutines.delay(2_000)
            if(activity.resources.configuration.orientation!=expected) {
                externalOrientationFallback=true
                activity.applyVideoOrientation(VideoOrientationMode.FOLLOW_DEVICE,null)
                return@LaunchedEffect
            }
        }
        if(state.current?.uri==media.uri&&(expected==null||externalOrientationFallback||activity.resources.configuration.orientation==expected))
            activity.waitingForExternalVideo=false
    }
    LaunchedEffect(state.current?.uri,state.current?.video,state.videoDisplayAspect,state.extras.getBoolean("audio_only"),
        orientationMode,activity.pip,activity.waitingForExternalVideo) {
        if(!activity.pip&&!activity.waitingForExternalVideo) {
            val media=state.current
            val isVideo=media?.video==true&&!state.extras.getBoolean("audio_only")
            activity.applyVideoOrientation(if(isVideo) orientationMode else VideoOrientationMode.FOLLOW_DEVICE,
                if(isVideo) state.videoDisplayAspect ?: media?.displayAspect else null)
        }
    }
    SideEffect {if(state.current?.video==true&&!state.extras.getBoolean("audio_only")&&state.playing&&!activity.pip) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    BackHandler {activity.closePlayer()}
    val openSheet:(String)->Unit={if(it=="effects") connection.command(PlaybackCommands.EFFECTS);sheet=it}
    fun chooseOrientation(mode:VideoOrientationMode) {
        orientationKey=mode.key
        val current=connection.state.value
        val media=current.current
        if(!activity.pip&&media?.video==true&&!current.extras.getBoolean("audio_only"))
            activity.applyVideoOrientation(mode,current.videoDisplayAspect ?: media.displayAspect)
    }
    if(activity.waitingForExternalVideo) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("video_startup_gate"),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(!activity.externalVideoStartupTimedOut) CircularProgressIndicator()
                Text(text(if(activity.externalVideoStartupTimedOut) R.string.player_orientation_unavailable else R.string.player_orientation_resolving),
                    color=Color.White,style=MaterialTheme.typography.bodyLarge)
                if(activity.externalVideoStartupTimedOut) TextButton(activity::closePlayer) {Text(text(R.string.close))}
            }
        }
    } else {
        if(state.current?.video==true&&!state.extras.getBoolean("audio_only")) VideoPlayerScreen(connection,state,activity,openSheet)
        else AudioPlayerScreen(connection,state,activity,openSheet)
        if(sheet!=null&&!activity.pip) ModalBottomSheet(onDismissRequest={sheet=null},containerColor=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(topStart=30.dp,topEnd=30.dp),
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),dragHandle={FlowSheetHandle()}) {
            when(sheet) {
                "queue"->QueuePanel(connection,state)
                "tracks"->TrackPanel(connection,state,{activity.selectingSubtitle=true;subtitlePicker.launch(arrayOf("text/*","application/x-subrip","application/ttml+xml","application/octet-stream"))})
                else->Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    when(sheet) {
                        "speed"->{Text(text(R.string.player_speed),style=MaterialTheme.typography.titleLarge);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(.25f,.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f).forEach {speed->FlowFilterChip(state.speed==speed,{connection.speed(speed)},label={Text("${speed}×")},modifier=Modifier.testTag("player_speed_$speed"))}
                        }}
                        "sleep"->{Text(text(R.string.player_sleep),style=MaterialTheme.typography.titleLarge)
                            listOf(5,15,30,60).forEach {minutes->TextButton({connection.sleep(minutes);sheet=null},Modifier.fillMaxWidth().testTag("player_sleep_$minutes")) {Text(spectrumPluralStringResource(R.plurals.player_sleep_minutes,minutes,minutes))}}
                            TextButton({connection.sleep(-1);sheet=null},Modifier.fillMaxWidth().testTag("player_sleep_end")) {Text(text(R.string.player_sleep_end))}
                            TextButton({connection.sleep(0);sheet=null},Modifier.fillMaxWidth()) {Text(text(R.string.player_off))}
                        }
                        "effects"->EffectsPanel(connection,state)
                        "options"->{
                            Text(text(R.string.settings),style=MaterialTheme.typography.titleLarge)
                            Text(text(R.string.player_orientation_for_video),style=MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                VideoOrientationMode.entries.forEach {mode->
                                    FlowFilterChip(orientationMode==mode,{chooseOrientation(mode)},label={Text(text(mode.label))},
                                        modifier=Modifier.testTag("player_orientation_${mode.key}"))
                                }
                            }
                            Text(text(R.string.player_orientation_mode_hint),style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            OptionSwitch(text(R.string.player_gestures),connection.repository.boolean("gestures",true)) {connection.repository.setBoolean("gestures",it);connection.command(PlaybackCommands.OPTIONS);sheet=null}
                            OptionSwitch(text(R.string.player_auto_rotate),autoRotate) {connection.repository.setBoolean("auto_rotate",it);connection.command(PlaybackCommands.OPTIONS);sheet=null}
                            Text(text(R.string.player_gestures_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(activity::playCurrentAsAudioOnly,Modifier.testTag("video_play_audio_only")) {Text(text(R.string.player_audio_only))}
                        }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
        state.error?.let {message->AlertDialog(onDismissRequest=connection::clearError,title={Text(text(R.string.problem))},text={Text(text(message))},
            confirmButton={TextButton({connection.clearError();connection.retry()}) {Text(text(R.string.retry))}},dismissButton={TextButton(connection::clearError) {Text(text(R.string.close))}})}
    }
}

@Composable private fun AudioPlayerScreen(connection:PlaybackConnection,state:PlaybackState,activity:PlayerActivity,sheet:(String)->Unit) {
    val context=LocalContext.current
    val favorites by remember(connection) {connection.repository.library.favorites()}.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().background(mediaFlowBackground()).windowInsetsPadding(WindowInsets.safeDrawing).testTag("audio_player")) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(FlowIcons.Back,text(R.string.back),activity::closePlayer,Modifier.testTag("player_back"))
            Text(text(R.string.player_now_playing),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,
                textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(Build.VERSION.SDK_INT>=34) FlowIconButton(Icons.Outlined.SpeakerGroup,text(R.string.player_output),{runCatching {MediaRouter2.getInstance(context).showSystemOutputSwitcher()}})
            else FlowIconButton(Icons.Outlined.Timer,text(R.string.player_sleep),{sheet("sleep")})
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(activity::backgroundAudio,Modifier.weight(1f).testTag("player_background")) {Text(text(R.string.audio_popup_background),style=MaterialTheme.typography.labelMedium)}
            FlowIconButton(Icons.Outlined.PictureInPictureAlt,text(R.string.audio_popup_floating_title),activity::floatingAudio,Modifier.testTag("player_floating"))
            FlowIconButton(Icons.Outlined.Stop,text(R.string.audio_popup_stop),activity::stopAudio,Modifier.testTag("player_stop"))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val wide=maxWidth>600.dp&&maxWidth>maxHeight
            val compact=maxHeight<560.dp||LocalDensity.current.fontScale>1.35f
            val artworkLimit=minOf(360.dp,maxWidth-24.dp,maxHeight*.55f)
            val artwork:@Composable (Modifier)->Unit={modifier->Box(modifier.flowEntrance(),contentAlignment=Alignment.Center) {
                PlayerArtwork(state.current,Modifier.sizeIn(maxWidth=artworkLimit,maxHeight=artworkLimit).aspectRatio(1f)
                    .shadow(12.dp,RoundedCornerShape(28.dp),ambientColor=MaterialTheme.colorScheme.primary.copy(alpha=.10f),spotColor=Color.Black.copy(alpha=.25f))
                    .testTag("player_artwork"))
            }}
            val controls:@Composable (Modifier)->Unit={modifier->Column(modifier.padding(horizontal=if(wide) 16.dp else 12.dp,vertical=if(compact) 4.dp else 8.dp),
                verticalArrangement=Arrangement.spacedBy(if(compact) 4.dp else 8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(state.current?.title ?: text(R.string.player_empty),
                        style=if(compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                        textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).testTag("player_title"))
                    val favorite=favorites.any {it.uri==state.current?.uri}
                    FlowIconButton(if(favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,text(R.string.player_favorites),{state.current?.let {media->connection.editLibrary {connection.repository.favorite(media,!favorite)}}},Modifier.testTag("player_favorite"),enabled=state.current!=null,accent=favorite)
                }
                listOfNotNull(state.current?.artist,state.current?.album,state.current?.mime?.substringAfter('/')?.uppercase()).takeIf {it.isNotEmpty()}?.let {
                    Text(it.joinToString(" · "),style=if(compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.fillMaxWidth().testTag("player_metadata"))
                }
                PlayerSeekBar(connection,state)
                TransportControls(connection,state)
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    TextButton({sheet("speed")},Modifier.testTag("player_speed")) {Text("${state.speed}×")}
                    FlowIconButton(FlowIcons.Playlist,text(R.string.player_queue),{sheet("queue")},Modifier.testTag("player_queue"))
                    FlowIconButton(Icons.Outlined.Timer,text(R.string.player_sleep),{sheet("sleep")},Modifier.testTag("player_sleep"),accent=state.extras.getLong("sleep_deadline")>0||state.extras.getBoolean("sleep_end"))
                    FlowIconButton(Icons.Outlined.GraphicEq,text(R.string.player_effects),{sheet("effects")},Modifier.testTag("player_effects"))
                }
                if(state.extras.getLong("sleep_deadline")>0) Text(text(R.string.player_sleep_active,playTime((state.extras.getLong("sleep_deadline")-SystemClock.elapsedRealtime()).coerceAtLeast(0))),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(state.extras.getBoolean("sleep_end")) Text(text(R.string.player_sleep_end),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }}
            if(wide) Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                artwork(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.weight(1.15f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                artwork(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun playTime(milliseconds:Long,locale:java.util.Locale=java.util.Locale.ROOT):String {
    val seconds=milliseconds.coerceAtLeast(0)/1000
    return if(seconds>=3600) String.format(locale,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60) else String.format(locale,"%d:%02d",seconds/60,seconds%60)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerSeekBar(connection:PlaybackConnection,state:PlaybackState,
    times:@Composable RowScope.(Long,Long)->Unit={position,duration->
        Text(playTime(position),modifier=Modifier.testTag("player_position"),
            style=MaterialTheme.typography.labelMedium.copy(textDirection=TextDirection.Ltr))
        Text(playTime(duration),modifier=Modifier.testTag("player_duration"),
            style=MaterialTheme.typography.labelMedium.copy(textDirection=TextDirection.Ltr),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
    val player by connection.player.collectAsStateWithLifecycle()
    var drag by remember(state.current?.uri) {mutableStateOf<Float?>(null)}
    val label=text(R.string.player_seek)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            Slider(value=drag ?: state.position.toFloat().coerceAtMost(state.duration.toFloat()),onValueChange={drag=it},
                onValueChangeFinished={drag?.let {connection.seek(it.toLong())};drag=null},valueRange=0f..state.duration.coerceAtLeast(1).toFloat(),
                 enabled=state.duration>0&&player?.isCurrentMediaItemSeekable==true,
                 thumb={Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary,CircleShape))},
                  track={slider->Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                      Box(Modifier.fillMaxHeight().fillMaxWidth((slider.value/state.duration.coerceAtLeast(1).toFloat()).coerceIn(0f,1f)).background(Spectrum.Gradient))
                  }},
                  modifier=Modifier.testTag("player_seek").semantics {contentDescription=label})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                times(drag?.toLong() ?: state.position,state.duration)
            }
        }
    }
}

@Composable internal fun TransportControls(connection:PlaybackConnection,state:PlaybackState,video:Boolean=false) {
    val repeatLabel=text(R.string.player_repeat,text(when(state.repeat) {Player.REPEAT_MODE_ONE->R.string.player_one;Player.REPEAT_MODE_ALL->R.string.player_all;else->R.string.player_off}))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            FlowIconButton(if(video) Icons.Outlined.Replay10 else Icons.Outlined.Shuffle,text(if(video) R.string.player_rewind else R.string.player_shuffle),{if(video) connection.jump(-10000) else connection.shuffle()},Modifier.testTag("player_shuffle"),accent=!video&&state.shuffle)
            FlowIconButton(Icons.Outlined.SkipPrevious,text(R.string.player_previous),connection::previous,Modifier.testTag("player_previous"),enabled=state.current!=null)
            val interaction=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
            FilledIconButton(connection::toggle,Modifier.size(68.dp).flowPressFeedback(interaction).background(Spectrum.Gradient,CircleShape).testTag("player_play_pause"),
                interactionSource=interaction,enabled=state.current!=null,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.Transparent,contentColor=Color(0xFF06292D))) {
                if(state.buffering) FlowLoadingIndicator(compact=true) else Icon(if(state.playing) FlowIcons.Pause else FlowIcons.Play,text(if(state.playing) R.string.player_pause else R.string.player_play),Modifier.size(32.dp))
            }
            FlowIconButton(Icons.Outlined.SkipNext,text(R.string.player_next),connection::next,Modifier.testTag("player_next"),enabled=state.queue.size>1)
            FlowIconButton(if(video) Icons.Outlined.Forward10 else if(state.repeat==Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,if(video) text(R.string.player_forward) else repeatLabel,{if(video) connection.jump(10000) else connection.repeat()},Modifier.testTag("player_repeat"),accent=!video&&state.repeat!=Player.REPEAT_MODE_OFF)
        }
    }
}

@Composable private fun QueuePanel(connection:PlaybackConnection,state:PlaybackState) {
    var save by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().heightIn(max=620.dp).padding(horizontal=16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text(text(R.string.player_queue),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(connection::clear) {Text(text(R.string.player_clear_queue))}}
        TextButton({save=true},enabled=state.queue.isNotEmpty(),modifier=Modifier.testTag("save_queue_playlist")) {Text(text(R.string.player_save_playlist))}
        Row {
            FlowIconButton(Icons.Outlined.Shuffle,text(R.string.player_shuffle),connection::shuffle,accent=state.shuffle)
            FlowIconButton(if(state.repeat==Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,text(R.string.player_repeat,text(when(state.repeat) {Player.REPEAT_MODE_ONE->R.string.player_one;Player.REPEAT_MODE_ALL->R.string.player_all;else->R.string.player_off})),connection::repeat,accent=state.repeat!=Player.REPEAT_MODE_OFF)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false).testTag("playback_queue")) {
            itemsIndexed(state.queue,key={index,item->"$index:${item.uri}"}) {index,item->
                Surface(onClick={connection.select(index)},color=if(index==state.index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            FlowIconTile(if(index==state.index) FlowIcons.Audio else FlowIcons.Play,size=36.dp,iconSize=18.dp)
                            Text(item.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                        }
                        Row {
                            FlowIconButton(Icons.Outlined.KeyboardArrowUp,text(R.string.player_move_up),{connection.move(index,index-1)},enabled=index>0)
                            FlowIconButton(Icons.Outlined.KeyboardArrowDown,text(R.string.player_move_down),{connection.move(index,index+1)},enabled=index<state.queue.lastIndex)
                            Spacer(Modifier.weight(1f));FlowIconButton(FlowIcons.Close,text(R.string.remove),{connection.remove(index)})
                        }
                    }
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
    if(save) PlaylistPicker(connection,state.queue) {save=false}
}

@Composable private fun OptionSwitch(title:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Text(title,Modifier.weight(1f));Switch(checked,change)}
}

@Composable private fun EffectsPanel(connection:PlaybackConnection,state:PlaybackState) {
    val extras=state.extras;val levels=extras.getIntArray("eq_levels");val bass=extras.getBoolean("bass_available")
    Text(text(R.string.player_effects),style=MaterialTheme.typography.titleLarge)
    if(levels==null&&!bass) Text(text(R.string.player_effects_unavailable),style=MaterialTheme.typography.bodyMedium)
    else {
        OptionSwitch(text(R.string.player_enable_effects),extras.getBoolean("effects_enabled")) {connection.command(PlaybackCommands.EFFECTS,Bundle().apply {putBoolean("enabled",it)})}
        levels?.forEachIndexed {band,level->
            val frequencies=extras.getIntArray("eq_frequencies")
            Text("${frequencies?.getOrNull(band) ?: 0} Hz",style=MaterialTheme.typography.labelLarge)
            var dragged by remember(band,level) {mutableFloatStateOf(level.toFloat())}
            Slider(dragged,{dragged=it},onValueChangeFinished={connection.command(PlaybackCommands.EFFECTS,Bundle().apply {putInt("band",band);putInt("level",dragged.toInt())})},valueRange=extras.getInt("eq_min").toFloat()..extras.getInt("eq_max").coerceAtLeast(extras.getInt("eq_min")+1).toFloat(),enabled=extras.getBoolean("effects_enabled"))
        }
        if(bass) {
            Text(text(R.string.player_bass));var strength by remember(extras.getInt("bass_strength")) {mutableFloatStateOf(extras.getInt("bass_strength").toFloat())}
            Slider(strength,{strength=it},onValueChangeFinished={connection.command(PlaybackCommands.EFFECTS,Bundle().apply {putInt("bass",strength.toInt())})},valueRange=0f..1000f,enabled=extras.getBoolean("effects_enabled"))
        }
    }
}

@Composable private fun TrackPanel(connection:PlaybackConnection,state:PlaybackState,external:()->Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(text(R.string.player_tracks),style=MaterialTheme.typography.titleLarge)
        listOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT).forEach {type->
            val groups=state.tracks.groups.filter {it.type==type}
            if(groups.isNotEmpty()) {
                Text(text(if(type==C.TRACK_TYPE_AUDIO) R.string.player_audio_tracks else R.string.player_subtitles),style=MaterialTheme.typography.titleMedium)
                TextButton({connection.track(type)}) {Text(text(R.string.player_auto))}
                if(type==C.TRACK_TYPE_TEXT) TextButton({connection.track(type,off=true)},Modifier.testTag("player_subtitles_off")) {Text(text(R.string.player_off))}
                var number=0
                groups.forEach {group->for(i in 0 until group.length) {number++;val format=group.getTrackFormat(i)
                    val locale=LocalSpectrumContext.current.resources.configuration.locales[0]
                    val label=if(format.id?.contains("mediaflow_external")==true) text(R.string.player_external_subtitle) else
                        listOfNotNull(format.label,format.language?.let {java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale)}).distinct().joinToString(" · ").ifBlank {text(R.string.player_track_number,number)}
                    FlowFilterChip(group.isTrackSelected(i),{connection.track(type,group,i)},label={Text(label)},enabled=group.isTrackSupported(i),modifier=Modifier.testTag("player_track_${type}_$number"))
                }}
            }
        }
        OutlinedButton(external,Modifier.fillMaxWidth().testTag("player_external_subtitle")) {Icon(Icons.Outlined.Subtitles,null);Spacer(Modifier.width(8.dp));Text(text(R.string.player_choose_subtitle))}
        Text(text(R.string.player_subtitle_size),style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(.75f,1f,1.25f,1.5f).forEach {scale->FlowFilterChip(connection.repository.number("subtitle_size",1f)==scale,{connection.repository.setNumber("subtitle_size",scale);connection.command(PlaybackCommands.OPTIONS)},label={Text("${scale}×")})}}
        Text(text(R.string.player_subtitle_color),style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(R.string.player_white,R.string.player_cyan,R.string.player_yellow).forEachIndexed {i,label->FlowFilterChip(connection.repository.number("subtitle_color",0f)==i.toFloat(),{connection.repository.setNumber("subtitle_color",i.toFloat());connection.command(PlaybackCommands.OPTIONS)},label={Text(text(label))})}}
        listOf("subtitle_opacity" to R.string.player_subtitle_background,"subtitle_bottom" to R.string.player_subtitle_position).forEach {(key,label)->
            Text(text(label),style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {(if(key=="subtitle_opacity") listOf(0f,.5f,.8f) else listOf(.05f,.15f,.25f)).forEach {value->FlowFilterChip(connection.repository.number(key,if(key=="subtitle_opacity") .5f else .15f)==value,{connection.repository.setNumber(key,value);connection.command(PlaybackCommands.OPTIONS)},label={Text("${(value*100).toInt()}%")})}}
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
