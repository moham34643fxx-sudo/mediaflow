package com.zdownloader.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R

/** One shared clock per visible list, read in the drawing layer, not row composition. */
@Composable internal fun rememberFormatPulse(active:Boolean):State<Float> {
    if(!active||!LocalFlowMotionEnabled.current) return rememberUpdatedState(.22f)
    return rememberInfiniteTransition(label="formatPending").animateFloat(.14f,.28f,
        infiniteRepeatable(tween(1000),RepeatMode.Reverse),label="formatPendingOpacity")
}

@Composable internal fun FormatPlaceholderBar(pulse:State<Float>,modifier:Modifier=Modifier) {
    Box(modifier.graphicsLayer {alpha=pulse.value}
        .background(Color(0xFFADB3BB),RoundedCornerShape(4.dp)).clearAndSetSemantics {})
}

@Composable internal fun ShareFormatSkeleton(pulse:State<Float>,tag:String) {
    val description=spectrumStringResource(R.string.share_formats_pending)
    Row(Modifier.fillMaxWidth().heightIn(min=80.dp).testTag(tag)
        .clearAndSetSemantics {contentDescription=description}.padding(horizontal=4.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        FormatPlaceholderBar(pulse,Modifier.size(24.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            FormatPlaceholderBar(pulse,Modifier.fillMaxWidth(.62f).height(14.dp))
            FormatPlaceholderBar(pulse,Modifier.fillMaxWidth(.9f).height(9.dp))
        }
        Box(Modifier.width(88.dp).heightIn(min=32.dp),contentAlignment=Alignment.CenterEnd) {
            FormatPlaceholderBar(pulse,Modifier.size(56.dp,12.dp))
        }
        FormatPlaceholderBar(pulse,Modifier.size(20.dp))
    }
}

/** A source row fades only on its first arrival during this visit, never on size updates or scrolling. */
internal class FormatArrivalTracker(initialIds:Collection<String>) {
    private val seen=initialIds.toMutableSet()
    fun firstArrival(id:String)=seen.add(id)
}

@Composable internal fun FormatRowArrival(id:String,tracker:FormatArrivalTracker,animate:Boolean=true,
    modifier:androidx.compose.ui.Modifier=androidx.compose.ui.Modifier.fillMaxWidth(),content:@Composable ()->Unit) {
    val arriving=remember(id,tracker) {tracker.firstArrival(id)}
    val motion=LocalFlowMotionEnabled.current&&animate
    if(!arriving||!motion) {Box(modifier) {content()};return}
    val opacity=remember(id,tracker) {Animatable(.55f)}
    LaunchedEffect(id,tracker,motion) {
        if(motion&&arriving) opacity.animateTo(1f,tween(180)) else opacity.snapTo(1f)
    }
    Box(modifier.graphicsLayer {alpha=opacity.value}) {content()}
}

/** Keep one size slot's bounds; replacing its placeholder never changes the row height. */
@Composable internal fun ShareFormatSize(size:String,pending:Boolean,pulse:State<Float>,tag:String) {
    var wasPending by remember(tag) {mutableStateOf(pending)}
    if(!pending&&!wasPending) {
        Box(Modifier.width(88.dp).heightIn(min=32.dp),contentAlignment=Alignment.CenterEnd) {
            androidx.compose.material3.Text(size,modifier=Modifier.testTag("${tag}_size"),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        return
    }
    val opacity=remember(tag) {Animatable(1f)}
    val motion=LocalFlowMotionEnabled.current
    LaunchedEffect(pending,size,motion) {
        if(wasPending&&!pending&&motion) {opacity.snapTo(.55f);opacity.animateTo(1f,tween(180))}
        else opacity.snapTo(1f)
        wasPending=pending
    }
    Box(Modifier.width(88.dp).heightIn(min=32.dp),contentAlignment=Alignment.CenterEnd) {
        if(pending) FormatPlaceholderBar(pulse,Modifier.size(56.dp,12.dp).testTag("${tag}_size_pending"))
        else androidx.compose.material3.Text(size,modifier=Modifier.testTag("${tag}_size").graphicsLayer {alpha=opacity.value},
            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
