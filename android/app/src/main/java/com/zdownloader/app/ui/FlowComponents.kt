package com.zdownloader.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One vector family and one semantic mapping across phone, rail, sheets and media. */
internal object FlowIcons {
    val Home get()=Icons.Outlined.Home
    val Downloads get()=Icons.Outlined.FileDownload
    val Library get()=Icons.Outlined.VideoLibrary
    val Settings get()=Icons.Outlined.Settings
    val Status get()=Icons.Outlined.MotionPhotosOn
    val Playlist get()=Icons.AutoMirrored.Outlined.PlaylistPlay
    val Video get()=Icons.Outlined.SmartDisplay
    val Audio get()=Icons.Outlined.MusicNote
    val Photo get()=Icons.Outlined.Image
    val Folder get()=Icons.Outlined.FolderOpen
    val Success get()=Icons.Outlined.CheckCircle
    val Error get()=Icons.Outlined.ErrorOutline
    val Waiting get()=Icons.Outlined.Schedule
    val Paused get()=Icons.Outlined.PauseCircle
    val Pause get()=Icons.Outlined.Pause
    val Play get()=Icons.Outlined.PlayArrow
    val Retry get()=Icons.Outlined.Refresh
    val Close get()=Icons.Outlined.Close
    val More get()=Icons.Outlined.MoreHoriz
    val Search get()=Icons.Outlined.Search
    val Paste get()=Icons.Outlined.ContentPaste
    val Privacy get()=Icons.Outlined.PrivacyTip
    val Open get()=Icons.AutoMirrored.Outlined.OpenInNew
    val Share get()=Icons.Outlined.Share
    val Edit get()=Icons.Outlined.Edit
    val Delete get()=Icons.Outlined.Delete
    val Tune get()=Icons.Outlined.Tune
    val Back get()=Icons.AutoMirrored.Outlined.ArrowBack
    val Next get()=Icons.AutoMirrored.Outlined.KeyboardArrowRight
    val Browser get()=Icons.Outlined.Language
    val Updates get()=Icons.Outlined.SystemUpdate
    val Security get()=Icons.Outlined.VerifiedUser
}

internal object FlowMotion {
    const val Press=100
    const val Fast=180
    const val Reveal=260
    const val Scene=220
    const val Exit=160
}
internal val LocalFlowMotionEnabled=staticCompositionLocalOf {true}

/** One observer per UI root. Finite Compose animations also obey the platform clock. */
@Composable internal fun FlowTheme(colors:ColorScheme,content:@Composable ()->Unit) {
    val resolver=LocalContext.current.applicationContext.contentResolver
    fun motionEnabled()=Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    var motion by remember(resolver) {mutableStateOf(motionEnabled())}
    DisposableEffect(resolver) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean) {motion=motionEnabled()}
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {resolver.unregisterContentObserver(observer)}
    }
    CompositionLocalProvider(LocalFlowMotionEnabled provides motion) {
        MaterialTheme(colorScheme=colors,typography=Spectrum.localizedTypography(),shapes=Spectrum.Shapes,content=content)
    }
}

@Composable internal fun flowSuccessColor():Color = if(MaterialTheme.colorScheme.background==Spectrum.Dark.background) Spectrum.Success else Color(0xFF126B4E)

/** Common bounded screen surface; consumed parent insets are respected. */
@Composable internal fun FlowScreen(content:@Composable ()->Unit) {
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(mediaFlowBackground())) {content()}
    }
}

private val FlowIconTileShape = RoundedCornerShape(topStart=16.dp,topEnd=16.dp,bottomEnd=16.dp,bottomStart=7.dp)

@Composable internal fun FlowIconTile(icon:ImageVector,tint:Color=MaterialTheme.colorScheme.primary,size:Dp=40.dp,iconSize:Dp=22.dp) {
    val brush = remember(tint) { Brush.linearGradient(listOf(tint.copy(alpha=.14f),tint.copy(alpha=.045f))) }
    val border = remember(tint) { BorderStroke(1.dp,tint.copy(alpha=.13f)) }
    Surface(shape=FlowIconTileShape,color=Color.Transparent,border=border) {
        Box(Modifier.size(size).background(brush,FlowIconTileShape),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(iconSize),tint=tint)
        }
    }
}

@Composable internal fun FlowIconButton(icon:ImageVector,description:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,accent:Boolean=false) {
    val interaction=remember {MutableInteractionSource()}
    IconButton(onClick,modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).flowPressFeedback(interaction),enabled=enabled,
        interactionSource=interaction,colors=IconButtonDefaults.iconButtonColors(
            containerColor=if(accent) MaterialTheme.colorScheme.primary.copy(alpha=.075f) else Color.Transparent,
            contentColor=if(accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(icon,description,Modifier.size(22.dp))
    }
}

@Composable internal fun Modifier.flowPressFeedback(interaction:MutableInteractionSource):Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion=LocalFlowMotionEnabled.current
    val compression=animateFloatAsState(if(pressed&&motion) .965f else 1f,
        if(motion) spring(dampingRatio=.9f,stiffness=1200f) else snap(),label="flowPress")
    return graphicsLayer {scaleX=compression.value;scaleY=compression.value}
}

@Composable internal fun FlowFilterChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    val fill by animateColorAsState(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha=.5f),tween(if(LocalFlowMotionEnabled.current) FlowMotion.Fast else 0),label="filterFill")
    FilterChip(selected,onClick,label,modifier.heightIn(min=40.dp),enabled=enabled,shape=RoundedCornerShape(50),
        colors=FilterChipDefaults.filterChipColors(containerColor=fill,selectedContainerColor=fill,
            selectedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,labelColor=MaterialTheme.colorScheme.onSurfaceVariant),
        border=BorderStroke(1.dp,if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.38f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)))
}

/** No perpetual animation when the system requests reduced motion. Still unknown. */
@Composable internal fun FlowLoadingIndicator(modifier:Modifier=Modifier,compact:Boolean=false) {
    if(LocalFlowMotionEnabled.current) {
        if(compact) CircularProgressIndicator(modifier.size(18.dp),color=LocalContentColor.current,strokeWidth=2.dp)
        else LinearProgressIndicator(modifier.fillMaxWidth().height(4.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
    } else {
        Box(modifier,contentAlignment=Alignment.Center) {
            Icon(FlowIcons.Waiting,spectrumStringResource(com.zdownloader.app.R.string.mf_loading),Modifier.size(if(compact) 18.dp else 22.dp).testTag("reduced_motion_loading"),tint=LocalContentColor.current)
        }
    }
}

/** A one-shot fade only on an observed transition; historical successes never replay. */
@Composable internal fun FlowStatusIcon(status:String,modifier:Modifier=Modifier) {
    val completed=status=="COMPLETED"
    var previous by remember {mutableStateOf(completed)}
    val reveal=remember {Animatable(1f)}
    val motion=LocalFlowMotionEnabled.current
    LaunchedEffect(completed,motion) {
        if(completed&&!previous&&motion) {reveal.snapTo(.35f);reveal.animateTo(1f,tween(FlowMotion.Reveal))}
        else reveal.snapTo(1f)
        previous=completed
    }
    val tint by animateColorAsState(when {completed->flowSuccessColor();status=="FAILED"->MaterialTheme.colorScheme.error;else->MaterialTheme.colorScheme.primary},tween(if(motion) FlowMotion.Fast else 0),label="downloadStateTint")
    val icon=when {completed->FlowIcons.Success;status=="FAILED"->FlowIcons.Error;status=="PAUSED"->FlowIcons.Paused;status.startsWith("DOWNLOADING")->FlowIcons.Downloads;else->FlowIcons.Waiting}
    Crossfade(icon,modifier.size(18.dp).graphicsLayer {alpha=reveal.value;scaleX=.94f+.06f*reveal.value;scaleY=scaleX},
        animationSpec=tween(if(motion) FlowMotion.Fast else 0),label="downloadStateIcon") {
        Icon(it,null,Modifier.size(18.dp),tint=tint)
    }
}
