package com.zdownloader.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.zdownloader.app.R

private val BrandInterFamily = FontFamily(Font(if(android.os.Build.VERSION.SDK_INT>=26) R.font.inter else R.font.inter_static))
private val DarkBackgroundBrush = Brush.verticalGradient(listOf(Color(0xFF142122),Spectrum.Dark.background,Color(0xFF10161C)))
private val LightBackgroundBrush = Brush.verticalGradient(listOf(Color(0xFFEBF4EE),Spectrum.Light.background,Spectrum.Light.background))

/** A quiet accent field shared by the four native screens, never a screenshot. */
@Composable internal fun mediaFlowBackground(): Brush = if(MaterialTheme.colorScheme.background==Spectrum.Dark.background)
    DarkBackgroundBrush
else LightBackgroundBrush

@Composable internal fun MediaFlowWordmark(modifier:Modifier=Modifier,large:Boolean=false,centered:Boolean=false) {
    val motion=LocalFlowMotionEnabled.current
    var greeted by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(false)}
    val light=remember {Animatable(if(greeted||!motion) 1f else 0f)}
    LaunchedEffect(motion) {if(motion&&!greeted) light.animateTo(1f,tween(480)) else light.snapTo(1f);greeted=true}
    val base=if(large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
    val brandSize=base.fontSize
    val onSurfaceColor=MaterialTheme.colorScheme.onSurface
    val primaryColor=MaterialTheme.colorScheme.primary
    val wordmark=remember(onSurfaceColor,primaryColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color=onSurfaceColor)) {append("Media")}
            withStyle(SpanStyle(color=primaryColor)) {append("Flow")}
        }
    }
    Box(modifier.graphicsLayer {alpha=.72f+.28f*light.value;translationY=(1f-light.value)*3.dp.toPx()},contentAlignment=if(centered) Alignment.Center else Alignment.CenterStart) {
    Text(wordmark,maxLines=1,style=base.copy(fontSize=brandSize,lineHeight=brandSize*1.35f,
        fontFamily=BrandInterFamily,fontWeight=FontWeight.SemiBold,textDirection=TextDirection.Ltr))
    }
}

@Composable internal fun MediaFlowIdentity(modifier:Modifier=Modifier) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.23f))) {
        // The English identity retains its original reading order in Arabic too.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / LocalDensity.current.fontScale < 240f
            if(narrow) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.mediaflow_mark),null,Modifier.width(64.dp).height(52.dp))
                    MediaFlowWordmark()
                    Text(spectrumStringResource(R.string.about_tagline),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(spectrumStringResource(R.string.developer_attribution),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Image(painterResource(R.drawable.mediaflow_mark),null,Modifier.width(64.dp).height(52.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        MediaFlowWordmark()
                        Text(spectrumStringResource(R.string.about_tagline),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(spectrumStringResource(R.string.developer_attribution),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun FeatureAccess(title:String,description:String,icon:ImageVector,tint:Color,onClick:()->Unit,modifier:Modifier=Modifier) {
    Surface(onClick,modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),
        color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(tint.copy(alpha=.055f),Color.Transparent))).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            FlowIconTile(icon,tint,size=46.dp,iconSize=23.dp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall)
                Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(FlowIcons.Next,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))
        }
    }
}

/** Animate only known persisted progress; never estimate a value for unknown totals. */
@Composable internal fun MediaFlowProgress(progress:Float,modifier:Modifier=Modifier) {
    val actual=progress.coerceIn(0f,1f)
    val fill=animateFloatAsState(actual,if(LocalFlowMotionEnabled.current) tween(FlowMotion.Scene) else snap(),label="actualDownloadProgress")
    val track=MaterialTheme.colorScheme.surfaceContainerHighest
    val gradient=flowAccentGradient()
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    Box(modifier.fillMaxWidth().height(6.dp).semantics {progressBarRangeInfo=ProgressBarRangeInfo(actual,0f..1f)}.drawBehind {
        val radius=CornerRadius(size.height/2)
        drawRoundRect(track,cornerRadius=radius)
        val width=size.width*fill.value
        if(width>0f) drawRoundRect(gradient,topLeft=Offset(if(rtl) size.width-width else 0f,0f),size=Size(width,size.height),cornerRadius=radius)
    })
}
