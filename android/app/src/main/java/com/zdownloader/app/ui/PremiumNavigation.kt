package com.zdownloader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val measuredWidthCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

/** Insets place the container; ordinary content spacing gives its artwork breathing room. */
@Composable internal fun PremiumBottomNavigation(selected:PrimaryDestination?,navigate:(PrimaryDestination)->Unit) {
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    val labels=PrimaryDestination.entries.map {spectrumStringResource(it.label)}
    val labelStyle=MaterialTheme.typography.labelMedium
    val maxMeasuredWidth=remember(labels,labelStyle,density.density,density.fontScale) {
        val cacheKey="${density.density}-${density.fontScale}-${labels.joinToString(",")}"
        measuredWidthCache.getOrPut(cacheKey) {
            labels.maxOf {measurer.measure(it,style=labelStyle.copy(fontWeight=FontWeight.SemiBold),softWrap=false,maxLines=1).size.width}
        }
    }
    // Spatial anchors are product navigation, not translated text. Keep the
    // familiar Arabic placement in both languages: Home right, Library left.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal))
        .padding(horizontal=12.dp).padding(top=6.dp,bottom=8.dp)) {
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=0.dp,shadowElevation=0.dp,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.48f)),shape=RoundedCornerShape(28.dp)) {
            Box(Modifier.fillMaxWidth().testTag("bottom_navigation")) {
                val maxWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 24.dp
                val eachLabelWidth=with(density) {((maxWidth-20.dp)/3-8.dp).toPx()}
                // Equal touch slots never move when labels or language change.
                // Fit labels within those slots; the line box still grows with
                // accessibility scaling, and full text remains in semantics.
                val fit=((eachLabelWidth-with(density) {4.dp.toPx()})/maxMeasuredWidth.coerceAtLeast(1)).coerceIn(.35f,1f)
                val fittedStyle=labelStyle.copy(fontSize=labelStyle.fontSize*fit)
                val labelHeight=with(density) {labelStyle.lineHeight.toDp()}
                Row(Modifier.fillMaxWidth().selectableGroup().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    PrimaryDestination.entries.forEachIndexed {index,destination->
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val active=selected==destination
                        val visualActive = active || pressed
                        val label=labels[index]
                        val tint=if(visualActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        val fill=if(active) MaterialTheme.colorScheme.primary.copy(alpha=.14f) else if(pressed) MaterialTheme.colorScheme.primary.copy(alpha=.08f) else Color.Transparent
                        Column(Modifier.weight(1f).heightIn(min=60.dp).clip(RoundedCornerShape(22.dp)).background(fill)
                            .selectable(
                                selected = active,
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    if (!active) navigate(destination)
                                }
                            )
                            .semantics(mergeDescendants=true) {}.testTag("nav_${destination.route}").padding(horizontal=4.dp,vertical=8.dp),
                            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp,Alignment.CenterVertically)) {
                            val icon=when(destination) {PrimaryDestination.HOME->FlowIcons.Home;PrimaryDestination.DOWNLOADS->FlowIcons.Downloads;PrimaryDestination.LIBRARY->FlowIcons.Library}
                            Icon(icon,null,Modifier.size(22.dp),tint=tint)
                            Box(Modifier.fillMaxWidth().heightIn(min=labelHeight),contentAlignment=Alignment.Center) {
                                Text(label,modifier=Modifier.fillMaxWidth(),style=fittedStyle,fontWeight=if(visualActive) FontWeight.SemiBold else FontWeight.Medium,
                                    color=tint,textAlign=TextAlign.Center,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Fixture/preview adapter; production selection and visibility use the graph hierarchy. */
@Composable internal fun PremiumBottomNavigation(route:String,navigate:(String)->Unit) =
    PremiumBottomNavigation(PrimaryDestination.entries.firstOrNull {it.route==route}) {navigate(it.route)}

@Preview(name="MediaFlow navigation • Arabic",widthDp=360,locale="ar")
@Composable private fun NavigationPreview() {
    CompositionLocalProvider(LocalSpectrumContext provides LocalContext.current,LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme=Spectrum.Dark,typography=Spectrum.localizedTypography()) {PremiumBottomNavigation(PrimaryDestination.HOME) {}}
    }
}
