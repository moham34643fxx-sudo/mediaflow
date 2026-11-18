package com.zdownloader.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Aurora: quiet light, generous rhythm, strong content. No blur/render effects. */
internal object FlowSpace {
    val Small=8.dp
    val Content=20.dp
    val Section=24.dp
    val Card=26.dp
}

/** One-shot entrance; never replays because progress or playback position changes. */
internal fun Modifier.flowEntrance():Modifier=composed {
    val motion=LocalFlowMotionEnabled.current
    val reveal=remember {Animatable(if(motion) 0f else 1f)}
    LaunchedEffect(motion) {if(motion) reveal.animateTo(1f,tween(FlowMotion.Scene,easing=FastOutSlowInEasing)) else reveal.snapTo(1f)}
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    graphicsLayer {alpha=reveal.value;translationY=(1f-reveal.value)*12f*density}
}

/** Cached, static ribbon artwork. Decorative only: no frame loop, network or bitmap. */
internal fun Modifier.flowRibbon(tint:Color):Modifier=drawWithCache {
    val paths=(0..3).map {i->Path().apply {
        moveTo(size.width*.45f,size.height*(.05f+i*.10f))
        cubicTo(size.width*.98f,-size.height*.28f,size.width*.60f,size.height*1.04f,size.width*1.25f,size.height*(.55f+i*.12f))
    }}
    onDrawBehind {
        paths.forEachIndexed {i,path->drawPath(path,tint.copy(alpha=.045f+i*.012f),style=Stroke(width=1.dp.toPx()))}
    }
}

@Composable internal fun FlowPanel(modifier:Modifier=Modifier,accent:Color=MaterialTheme.colorScheme.primary,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(FlowSpace.Card),color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.48f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha=.035f),Color.Transparent))).padding(FlowSpace.Content),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable internal fun FlowEyebrow(label:String,icon:ImageVector=FlowIcons.Downloads) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(icon,null,Modifier.size(15.dp),tint=MaterialTheme.colorScheme.primary)
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun FlowSectionHeader(title:String,subtitle:String?=null,icon:ImageVector?=null) {
    Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(icon!=null) FlowIconTile(icon,size=44.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
            if(!subtitle.isNullOrBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun FlowEmptyState(title:String,hint:String,icon:ImageVector=FlowIcons.Library) {
    FlowPanel(accent=MaterialTheme.colorScheme.secondary) {
        Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowIconTile(icon,size=64.dp,iconSize=28.dp)
            Text(title,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            if(hint.isNotBlank()) Text(hint,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** The row owns radio semantics; this purely visual mark has no duplicate tap target. */
@Composable internal fun FlowSelectionIndicator(selected:Boolean,enabled:Boolean=true) {
    val amount=animateFloatAsState(if(selected) 1f else 0f,tween(if(LocalFlowMotionEnabled.current) FlowMotion.Fast else 0),label="selectionCheck")
    val active=MaterialTheme.colorScheme.primary.copy(alpha=if(enabled) 1f else .4f)
    val inactive=MaterialTheme.colorScheme.outline.copy(alpha=if(enabled) .8f else .35f)
    val ink=MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(22.dp)) {
        val fraction=amount.value
        drawCircle(if(selected) active else inactive,style=Stroke(1.5.dp.toPx()))
        drawCircle(active.copy(alpha=fraction),radius=size.minDimension*.5f*fraction)
        if(fraction>0f) {
            val path=Path().apply {moveTo(size.width*.27f,size.height*.51f);lineTo(size.width*.43f,size.height*.66f);lineTo(size.width*.73f,size.height*.34f)}
            drawPath(path,ink.copy(alpha=fraction),style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
        }
    }
}

@Composable internal fun FlowMetric(value:String,label:String,tint:Color,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).background(tint,CircleShape))
        Text(value,style=MaterialTheme.typography.titleLarge)
        Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
