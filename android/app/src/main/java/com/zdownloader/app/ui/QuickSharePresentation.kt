package com.zdownloader.app.ui

import androidx.compose.runtime.*
import com.zdownloader.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** Derived UI data for one immutable extraction snapshot. No global cache or transport changes. */
internal class QuickSharePresentation private constructor(
    val media:Media,val groups:List<QuickShareQualityGroup>,val videoSources:List<Format>,
    private val videoSelections:Map<String,Selection>,
    val preferenceSizes:Map<Int,MediaSize>,val mp3Size:MediaSize?,
    val variantFields:Map<String,Set<QuickShareVariantDetails.Field>>,
    val preferenceQualities:Map<Int,Int> = emptyMap()
) {
    fun selection(choice:QuickShareChoice):Selection? {
        if(choice.audio) return choice.resolve(media)
        val prepared=videoSelections[choice.formatId] ?: return null
        return if(choice.output.isBlank()||choice.output==prepared.output) prepared else choice.resolve(media)
    }
    companion object {
        suspend fun prepare(media:Media,labels:SizeLabelCache?=null):QuickSharePresentation = withContext(Dispatchers.Default) {
            ensureActive()
            val entries=QuickShareCatalog.videoEntries(media)
            val groups=media.qualityGroups()
            val variants=QuickShareCatalog.variantEntries(media,groups)
            val sources=QuickShareCatalog.selectableSources(variants)
            val qualityGroups=quickShareQualityGroups(QuickShareCatalog.selectableGroups(groups,sources).flatMap {it.candidates})
            if(labels!=null) {
                labels.label(null)
                for(entry in variants) {
                    ensureActive()
                    labels.label(entry.selection?.transferSize)
                    labels.label(entry.selection?.outputSize)
                }
                // The audio section shows output variants of the default audio
                // source. Prime those few size values once, not on every toggle.
                for(output in AudioOutputFormats.quickShare) {
                    ensureActive()
                    runCatching {Selection.audio(media,output)}.getOrNull()?.let {labels.label(it.outputSize)}
                }
            }
            ensureActive()
            // Resolve each preset through the shared at-or-below quality-cap policy.
            val preferences=QuickShareCatalog.compactPreferences.mapNotNull {height->
                val entry = entries.firstOrNull { QuickShareCatalog.numericVideoQuality(it.format) == height }
                val selection = entry?.selection ?: VideoPreferenceSelection.ready(media, height)
                selection?.let {height to it}
            }.toMap()
            val preferenceSizes=preferences.mapNotNull {(height,selection)->
                val resolvedHeight=QuickShareCatalog.numericVideoQuality(selection.primary) ?: qualityLabel(selection.primary)
                (selection.outputSize ?: selection.transferSize)?.takeIf {it.bytes>0&&resolvedHeight==height}?.let {height to it}
            }.toMap()
            // The genuine tier each cap resolves to (may be lower than the cap, never higher).
            val preferenceQualities=preferences.mapNotNull {(height,selection)->
                (QuickShareCatalog.numericVideoQuality(selection.primary) ?: qualityLabel(selection.primary))?.let {height to it}
            }.toMap()
            val mp3Size=runCatching {
                val selection=Selection.audio(media,"mp3")
                selection.outputSize?.takeIf {it.bytes>0} ?: run {
                    val duration=media.duration ?: selection.duration
                    val bitrate=if(selection.bitrate>0) selection.bitrate.toDouble() else 192.0
                    estimateBytes(duration,bitrate)?.let {MediaSize(it,true)}
                }
            }.getOrNull()
            preferenceSizes.values.forEach {labels?.label(it)}
            mp3Size?.let {labels?.label(it)}
            QuickSharePresentation(media,qualityGroups,sources,variants.mapNotNull {entry->
                entry.selection?.let {entry.format.id to it}
            }.toMap(),preferenceSizes,mp3Size,QuickShareVariantDetails.fields(variants),preferenceQualities)
        }
    }
}

@Composable internal fun rememberQuickSharePresentation(media:Media?,labels:SizeLabelCache):QuickSharePresentation? {
    val latest by rememberUpdatedState(media)
    val prepared by produceState<QuickSharePresentation?>(null,labels,media?.url,media?.id) {
        // Finish the first playable presentation even if a burst of optional
        // size updates arrives while it is being prepared.
        snapshotFlow {latest}.conflate().collect {snapshot->
            value=snapshot?.let {QuickSharePresentation.prepare(it,labels)}
        }
    }
    // Same-source metadata can stay visible while a newer size snapshot is prepared.
    // Never display a previous link's rows after replacing or invalidating the share.
    return prepared?.takeIf {media!=null&&it.media.url==media.url&&it.media.id==media.id}
}
