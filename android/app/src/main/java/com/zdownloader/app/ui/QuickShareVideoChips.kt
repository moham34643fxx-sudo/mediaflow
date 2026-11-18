package com.zdownloader.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zdownloader.app.domain.Format
import java.util.Locale

internal data class VideoVariantChipSpec(
    val format:Format,
    val selected:Boolean,
    val recommended:Boolean,
    val details:String,
    val tag:String
)

/** Natural-width chips wrap to the available space; this is not a fixed-column grid. */
@Composable internal fun VideoVariantChips(
    variants:List<VideoVariantChipSpec>,
    enabled:Boolean,
    recommendedLabel:String,
    modifier:Modifier=Modifier,
    arrivals:FormatArrivalTracker?=null,
    onSelect:(Format)->Unit
) {
    if(variants.isEmpty()) return
    FlowRow(
        modifier=modifier,
        horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        variants.forEach {variant->
            val itemModifier=Modifier.widthIn(min=104.dp,max=168.dp).weight(1f,fill=false)
            if(arrivals==null) VideoVariantChip(variant,enabled,recommendedLabel,itemModifier) {onSelect(variant.format)}
            else FormatRowArrival(variant.format.id,arrivals,animate=true,modifier=itemModifier) {
                VideoVariantChip(variant,enabled,recommendedLabel,Modifier.fillMaxWidth()) {onSelect(variant.format)}
            }
        }
    }
}

@Composable private fun VideoVariantChip(
    variant:VideoVariantChipSpec,
    enabled:Boolean,
    recommendedLabel:String,
    modifier:Modifier,
    onClick:()->Unit
) {
    val motion=LocalFlowMotionEnabled.current
    val fill by animateColorAsState(
        if(variant.selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        tween(if(motion) FlowMotion.Fast else 0),label="videoVariantChipFill"
    )
    val border by animateColorAsState(
        if(variant.selected) MaterialTheme.colorScheme.primary.copy(alpha=.68f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.8f),
        tween(if(motion) FlowMotion.Fast else 0),label="videoVariantChipBorder"
    )
    val label=codecLabel(variant.format.videoCodec).ifBlank {variant.format.ext.uppercase(Locale.ROOT)}
    Surface(modifier=modifier,shape=RoundedCornerShape(16.dp),color=fill,border=BorderStroke(1.dp,border)) {
        Row(
            Modifier.fillMaxWidth().selectable(variant.selected,enabled,Role.RadioButton,onClick)
                .padding(horizontal=8.dp,vertical=9.dp)
                .testTag(variant.tag),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(6.dp)
        ) {
            FlowSelectionIndicator(variant.selected,enabled)
            Column(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(label,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
                Text(variant.format.ext.uppercase(Locale.ROOT),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(variant.details.isNotBlank()) Text(variant.details,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(variant.recommended) Text(recommendedLabel,style=MaterialTheme.typography.labelSmall,
                    fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
