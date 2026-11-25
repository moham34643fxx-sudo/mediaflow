package com.zdownloader.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.QuickShareCatalog
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.QuickShareDimensions
import org.json.JSONArray

/** Wait for the asynchronously prepared catalog to reach its actual first drawn frame. */
internal fun ComposeTestRule.awaitQuickShareCatalog(traceId:String,timeoutMillis:Long=15_000) {
    waitUntil(timeoutMillis) {
        val traces=JSONArray(AnalysisTrace.snapshot())
        (0 until traces.length()).any {index->
            val trace=traces.getJSONObject(index)
            trace.optString("id")==traceId&&trace.has("complete_catalog_drawn")
        }
    }
}

/** Match the UI's actual source list, excluding formats that cannot currently resolve. */
internal fun selectableQuickShareVideoSources(media:Media):List<Format> =
    QuickShareCatalog.selectableSources(QuickShareCatalog.variantEntries(media))

/** Find the actual row or alternative representing this eligible source. */
internal fun ComposeTestRule.revealQuickShareVideoSource(media:Media,format:Format):String {
    val selectable=selectableQuickShareVideoSources(media)
    val source=checkNotNull(selectable.firstOrNull {it.id==format.id}) {
        "Source ${format.id} is not a currently selectable Quick Share video"
    }
    val dimensions=QuickShareDimensions.from(source)
    val tier=dimensions.key
    val tierTag="share_video_quality_$tier"
    val formats=onNodeWithTag("share_formats")
    formats.performScrollToNode(hasTestTag(tierTag))
    if(selectable.none {it.id!=source.id&&QuickShareDimensions.from(it)==dimensions}) return tierTag
    val sourceTag="share_video_variant_${format.id}"
    if(!runCatching {formats.performScrollToNode(hasTestTag(sourceTag))}.isSuccess) {
        val toggleTag="share_video_quality_toggle_$tier"
        formats.performScrollToNode(hasTestTag(toggleTag))
        onNodeWithTag(toggleTag).performClick()
        formats.performScrollToNode(hasTestTag(sourceTag))
    }
    return sourceTag
}
