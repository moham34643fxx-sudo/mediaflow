package com.zdownloader.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.qualityGroups

/** Reveal a source inside its real quality tier; singleton tiers are selectable on the tier row. */
internal fun ComposeTestRule.revealQuickShareVideoSource(media:Media,format:Format):String {
    val group=checkNotNull(media.qualityGroups().firstOrNull {candidate->candidate.candidates.any {it.id==format.id}}) {
        "No quality tier for source ${format.id}"
    }
    val tier=group.resolution?.toString() ?: "unknown"
    val tierTag="share_video_quality_$tier"
    onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tierTag))
    if(group.candidates.size==1) return tierTag

    val sourceTag="share_video_variant_${format.id}"
    if(onAllNodesWithTag(sourceTag).fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag("share_video_quality_toggle_$tier").performClick()
        waitUntil(10_000) {onAllNodesWithTag(sourceTag).fetchSemanticsNodes().isNotEmpty()}
    }
    return sourceTag
}
