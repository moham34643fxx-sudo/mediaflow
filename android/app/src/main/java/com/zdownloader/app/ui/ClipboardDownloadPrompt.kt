package com.zdownloader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.zdownloader.app.R
import com.zdownloader.app.data.ClipboardPreview
import com.zdownloader.app.domain.ClipboardLink
import com.zdownloader.app.ui.spectrumStringResource as stringResource

data class ClipboardPromptUi(val candidate:ClipboardLink,val accept:()->Unit,val dismiss:()->Unit,
    internal val preview:ClipboardPreview=ClipboardPreview(),val loadPreview:()->Unit={},val pausePreview:()->Unit={})

/** Kept mounted so consuming a candidate still plays its coordinated exit. No modal window. */
@Composable fun ClipboardDownloadPrompt(prompt:ClipboardPromptUi?,bottomClearance:androidx.compose.ui.unit.Dp=0.dp) {
    var displayed by remember {mutableStateOf(prompt)}
    val visibility=remember {MutableTransitionState(false)}
    val motion=LocalFlowMotionEnabled.current
    val travel=with(LocalDensity.current) {48.dp.roundToPx()}
    LaunchedEffect(prompt) {if(prompt!=null) displayed=prompt;visibility.targetState=prompt!=null}
    LaunchedEffect(visibility.isIdle,visibility.currentState,prompt) {
        if(prompt==null&&visibility.isIdle&&!visibility.currentState) displayed=null
    }
    BackHandler(prompt!=null) {prompt?.dismiss?.invoke()}
    val shown=displayed ?: return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
        .padding(horizontal=16.dp).padding(bottom=bottomClearance+12.dp),contentAlignment=Alignment.BottomCenter) {
        AnimatedVisibility(visibility,enter=if(motion) slideInVertically(spring(dampingRatio=.86f,stiffness=520f)) {it+travel}+
            fadeIn(tween(150))+scaleIn(spring(dampingRatio=.9f,stiffness=520f),initialScale=.94f,transformOrigin=TransformOrigin(.5f,1f)) else EnterTransition.None,
            exit=if(motion) slideOutVertically(tween(FlowMotion.Exit)) {it+travel}+fadeOut(tween(FlowMotion.Exit))+
                scaleOut(tween(FlowMotion.Exit),targetScale=.96f,transformOrigin=TransformOrigin(.5f,1f)) else ExitTransition.None) {
            val corners by transition.animateDp(transitionSpec={
                if(!motion) snap() else if(targetState==EnterExitState.Visible) spring(dampingRatio=.9f,stiffness=520f) else tween(FlowMotion.Exit)
            },label="dewdropCorners") {
                if(it==EnterExitState.Visible) 26.dp else 42.dp
            }
            ClipboardPreviewCard(shown,Modifier.widthIn(max=560.dp).fillMaxWidth(),RoundedCornerShape(corners),
                interactive=prompt?.candidate?.fingerprint==shown.candidate.fingerprint)
        }
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(prompt?.candidate?.fingerprint,lifecycle) {
        if(prompt!=null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A frame callback precedes drawing; the second leaves one drawn frame for the card.
            withFrameNanos {};withFrameNanos {};prompt.loadPreview()
            try {awaitCancellation()} finally {prompt.pausePreview()}
        }
    }
}

@Composable private fun ClipboardPreviewCard(prompt:ClipboardPromptUi,modifier:Modifier,shape:RoundedCornerShape,interactive:Boolean) {
    val colors=MaterialTheme.colorScheme
    val host=remember(prompt.candidate.url) {java.net.URI(prompt.candidate.url).host.orEmpty().removePrefix("www.")}
    Surface(modifier.testTag("clipboard_download_prompt"),shape=shape,color=colors.surfaceContainerHigh,
        shadowElevation=10.dp,border=BorderStroke(1.dp,colors.primary.copy(alpha=.22f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primary.copy(alpha=.075f),colors.surface.copy(alpha=.1f))))
            .padding(start=16.dp,end=16.dp,top=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Icon(FlowIcons.Paste,null,Modifier.size(14.dp),tint=colors.primary)
                Text(stringResource(R.string.clipboard_download_title),style=MaterialTheme.typography.labelMedium,color=colors.primary,fontWeight=FontWeight.SemiBold)
                Text("· $host",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ClipboardArtwork(prompt.preview)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(prompt.preview.title ?: stringResource(R.string.clipboard_media_fallback),Modifier.testTag("clipboard_preview_title"),
                        style=MaterialTheme.typography.titleSmall,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(stringResource(R.string.clipboard_download_hint),style=MaterialTheme.typography.bodySmall,minLines=2,color=colors.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) {
                TextButton(prompt.dismiss,Modifier.heightIn(min=48.dp).testTag("clipboard_ignore"),enabled=interactive) {Text(stringResource(R.string.clipboard_ignore))}
                val interaction=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
                Button(prompt.accept,Modifier.heightIn(min=48.dp).flowPressFeedback(interaction).testTag("clipboard_download"),enabled=interactive,
                    interactionSource=interaction,shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp)) {
                    Icon(FlowIcons.Downloads,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable private fun ClipboardArtwork(preview:ClipboardPreview) {
    val context=LocalContext.current
    val motion=LocalFlowMotionEnabled.current
    var loaded by remember(preview.artwork) {mutableStateOf(false)}
    val request=remember(preview.artwork,motion) {ImageRequest.Builder(context).data(preview.artwork).size(192)
        .crossfade(if(motion) FlowMotion.Fast else 0).build()}
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary.copy(alpha=.18f),MaterialTheme.colorScheme.tertiary.copy(alpha=.08f))))
        .testTag("clipboard_artwork"),contentAlignment=Alignment.Center) {
        Icon(FlowIcons.Video,null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary.copy(alpha=.8f))
        if(preview.artwork!=null) AsyncImage(request,null,Modifier.fillMaxSize().testTag(if(loaded) "clipboard_thumbnail_loaded" else "clipboard_thumbnail"),
            contentScale=ContentScale.Crop,onSuccess={loaded=true})
    }
}
