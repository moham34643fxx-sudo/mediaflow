package com.zdownloader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.platform.LocalConfiguration
import com.zdownloader.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Compose dialog windows replace LocalContext with their window context. Keep the
// selected in-app locale independent of that context (including dialog slots).
internal val LocalSpectrumContext=staticCompositionLocalOf<android.content.Context> {error("Missing Z Spectrum locale")}
@Composable internal fun spectrumStringResource(id:Int,vararg args:Any):String {
    val resources=LocalSpectrumContext.current.resources
    return if(args.isEmpty()) resources.getString(id) else resources.getString(id,*args)
}
@Composable internal fun spectrumPluralStringResource(id:Int,quantity:Int,vararg args:Any):String =
    LocalSpectrumContext.current.resources.getQuantityString(id,quantity,*args)
@Composable internal fun itemCountLabel(count:Int):String = LocalSpectrumContext.current.resources.getQuantityString(R.plurals.player_item_count,count.coerceAtLeast(0),count.coerceAtLeast(0))

internal object Spectrum {
    val Gradient=Brush.linearGradient(listOf(Color(0xFFA3F1DD),Color(0xFF79D9DD),Color(0xFF90C7F3)))
    val Success=Color(0xFF88DDB4)
    val Warning=Color(0xFFEBC38C)
    val Error=Color(0xFFFF9BA7)
    val Dark=darkColorScheme(
        primary=Color(0xFF91E3D1),onPrimary=Color(0xFF07352F),primaryContainer=Color(0xFF1B3C38),onPrimaryContainer=Color(0xFFC6F8E9),
        secondary=Color(0xFFACC9EB),onSecondary=Color(0xFF162F48),secondaryContainer=Color(0xFF25394D),onSecondaryContainer=Color(0xFFD3E6FF),
        tertiary=Color(0xFFC7B9E7),onTertiary=Color(0xFF322644),tertiaryContainer=Color(0xFF3D3450),onTertiaryContainer=Color(0xFFECDDFF),
        background=Color(0xFF0C1014),onBackground=Color(0xFFEDF1F0),
        surface=Color(0xFF171E23),onSurface=Color(0xFFEDF1F0),surfaceVariant=Color(0xFF283239),onSurfaceVariant=Color(0xFFACBBC0),
        surfaceContainerLowest=Color(0xFF0A0E12),surfaceContainerLow=Color(0xFF12191E),surfaceContainer=Color(0xFF171E23),surfaceContainerHigh=Color(0xFF20292F),surfaceContainerHighest=Color(0xFF303B42),
        outline=Color(0xFF7F9298),outlineVariant=Color(0xFF344249),error=Color(0xFFFFADB7),onError=Color(0xFF5B1223),errorContainer=Color(0xFF48252D),onErrorContainer=Color(0xFFFFD9DF),
        inverseSurface=Color(0xFFDEE5E3),inverseOnSurface=Color(0xFF25312E),inversePrimary=Color(0xFF1A6B5C),surfaceTint=Color(0xFF91E3D1))
    val Light=lightColorScheme(
        primary=Color(0xFF176B5C),onPrimary=Color.White,primaryContainer=Color(0xFFD4F1E7),onPrimaryContainer=Color(0xFF133D32),
        secondary=Color(0xFF3C6284),onSecondary=Color.White,secondaryContainer=Color(0xFFDFEAF5),onSecondaryContainer=Color(0xFF1C3954),
        tertiary=Color(0xFF6D5585),onTertiary=Color.White,tertiaryContainer=Color(0xFFEEE2F6),onTertiaryContainer=Color(0xFF412C57),
        background=Color(0xFFF5F7F4),onBackground=Color(0xFF1C2928),surface=Color(0xFFFCFDFB),onSurface=Color(0xFF1C2928),
        surfaceVariant=Color(0xFFE5ECE7),onSurfaceVariant=Color(0xFF52635F),surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF0F4EF),surfaceContainer=Color(0xFFFAFCF8),surfaceContainerHigh=Color(0xFFEAF0E9),surfaceContainerHighest=Color(0xFFDCE6DE),
        outline=Color(0xFF70837B),outlineVariant=Color(0xFFCBD8CF),error=Color(0xFFAA3046),onError=Color.White,errorContainer=Color(0xFFFFE1E5),onErrorContainer=Color(0xFF681728),
        inverseSurface=Color(0xFF25332F),inverseOnSurface=Color(0xFFEDF3EC),inversePrimary=Color(0xFF91E3D1),surfaceTint=Color(0xFF176B5C))
    private val PlexArabicFamily=FontFamily(Font(R.font.plex_arabic))
    private val InterFamily=FontFamily(Font(if(android.os.Build.VERSION.SDK_INT>=26) R.font.inter else R.font.inter_static))
    private fun type(size:Int,line:Int,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight)
    val Typography=Typography(displayLarge=type(32,40,FontWeight.SemiBold),displayMedium=type(32,40,FontWeight.SemiBold),displaySmall=type(32,40,FontWeight.SemiBold),
        headlineLarge=type(30,40,FontWeight.SemiBold),headlineMedium=type(27,37,FontWeight.SemiBold),headlineSmall=type(24,34,FontWeight.SemiBold),
        titleLarge=type(21,31,FontWeight.SemiBold),titleMedium=type(17,27,FontWeight.SemiBold),titleSmall=type(15,24,FontWeight.Medium),
        bodyLarge=type(16,24),bodyMedium=type(14,22),bodySmall=type(12,18),labelLarge=type(14,20,FontWeight.Medium),labelMedium=type(12,18,FontWeight.Medium),labelSmall=type(12,18))
    private fun Typography.withFamily(family:FontFamily)=copy(displayLarge=displayLarge.copy(fontFamily=family),displayMedium=displayMedium.copy(fontFamily=family),displaySmall=displaySmall.copy(fontFamily=family),headlineLarge=headlineLarge.copy(fontFamily=family),headlineMedium=headlineMedium.copy(fontFamily=family),headlineSmall=headlineSmall.copy(fontFamily=family),titleLarge=titleLarge.copy(fontFamily=family),titleMedium=titleMedium.copy(fontFamily=family),titleSmall=titleSmall.copy(fontFamily=family),bodyLarge=bodyLarge.copy(fontFamily=family),bodyMedium=bodyMedium.copy(fontFamily=family),bodySmall=bodySmall.copy(fontFamily=family),labelLarge=labelLarge.copy(fontFamily=family),labelMedium=labelMedium.copy(fontFamily=family),labelSmall=labelSmall.copy(fontFamily=family))
    private val ArabicTypography=Typography.withFamily(PlexArabicFamily)
    private val EnglishTypography=Typography.withFamily(InterFamily)
    @Composable fun localizedTypography():Typography =
        if(LocalConfiguration.current.locales[0].language=="ar") ArabicTypography else EnglishTypography
    val Shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(32.dp))
}

/** Foreground accents need contrast in light mode; action fills keep their own dark ink. */
@Composable internal fun flowAccentGradient():Brush {
    val primary=MaterialTheme.colorScheme.primary
    val secondary=MaterialTheme.colorScheme.secondary
    return remember(primary,secondary) {Brush.linearGradient(listOf(primary,secondary))}
}

/** Restrained branded primary action with native disabled, ripple and focus behavior. */
@Composable internal fun PrimaryAction(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(18.dp),content:@Composable RowScope.()->Unit) {
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    // Compose's animation clock respects Android's animator duration scale.
    val scale by animateFloatAsState(if(pressed&&enabled&&LocalFlowMotionEnabled.current) .985f else 1f,tween(FlowMotion.Press),label="buttonPress")
    val timing=if(LocalFlowMotionEnabled.current) FlowMotion.Fast else 0
    val start by animateColorAsState(if(enabled) Color(0xFFA3F1DD) else MaterialTheme.colorScheme.primary.copy(alpha=.12f),tween(timing),label="actionStart")
    val middle by animateColorAsState(if(enabled) Color(0xFF88DDCF) else MaterialTheme.colorScheme.secondary.copy(alpha=.10f),tween(timing),label="actionMiddle")
    val end by animateColorAsState(if(enabled) Color(0xFF92D4DC) else MaterialTheme.colorScheme.tertiary.copy(alpha=.12f),tween(timing),label="actionEnd")
    val brush = remember(start,middle,end) { Brush.linearGradient(listOf(start,middle,end)) }
    Button(onClick,modifier.heightIn(min=56.dp).graphicsLayer {scaleX=scale;scaleY=scale}
        .background(brush,shape),border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=if(enabled) .12f else .16f)),
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,contentColor=Color(0xFF061322),disabledContainerColor=Color.Transparent,disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant),
        enabled=enabled,interactionSource=interaction,shape=shape,contentPadding=PaddingValues(horizontal=20.dp,vertical=14.dp),content=content)
}
