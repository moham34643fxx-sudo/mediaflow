package com.zdownloader.app.ui

import com.zdownloader.app.domain.*

/** Compute distinguishing fields once per metadata snapshot, not on every row recomposition. */
internal object QuickShareVariantDetails {
    enum class Field { DIMENSIONS, CODEC, FPS, BITRATE, CONTAINER, AUDIO, LANGUAGE, CHANNELS, SAMPLE_RATE, SOURCE_ID }

    fun fields(entries:List<QuickShareCatalog.VideoEntry>):Map<String,Set<Field>> {
        val fieldsBySource=mutableMapOf<String,Set<Field>>()
        for(peers in entries.filter {it.selection!=null}.groupBy {qualityLabel(it.format)}.values) {
            val sharedFields=groupFields(peers)
            // If every visible fact including size is equal, retain honest source identities
            // instead of either inventing a difference or dropping an unproven duplicate.
            val collisions=peers.groupBy(::displaySignature).values.filter {it.size>1}
                .flatten().mapTo(mutableSetOf()) {it.format.id}
            for(entry in peers) fieldsBySource[entry.format.id]=sharedFields+
                (if(entry.format.id in collisions) setOf(Field.SOURCE_ID) else emptySet())
        }
        return fieldsBySource
    }

    private fun groupFields(peers:List<QuickShareCatalog.VideoEntry>):Set<Field> {
        val differences=mutableSetOf<Field>()
        fun addDifference(field:Field,attribute:(QuickShareCatalog.VideoEntry)->Any?) {
            if(peers.map(attribute).distinct().size>1) differences.add(field)
        }
        addDifference(Field.DIMENSIONS) {listOf(it.format.width,it.format.height)}
        if(peers.size>1) differences.add(Field.CODEC)
        addDifference(Field.FPS) {it.format.fps}
        addDifference(Field.BITRATE) {it.format.videoBitrate ?: it.format.bitrate}
        addDifference(Field.CONTAINER) {it.format.ext}
        addDifference(Field.AUDIO) {entry->entry.selection?.let {listOf(it.secondary!=null,it.secondary?.audioCodec ?: it.primary.audioCodec)}}
        addDifference(Field.LANGUAGE) {it.selection?.let {s->(s.secondary ?: s.primary).language}}
        addDifference(Field.CHANNELS) {it.selection?.let {s->(s.secondary ?: s.primary).audioChannels}}
        addDifference(Field.SAMPLE_RATE) {it.selection?.let {s->(s.secondary ?: s.primary).sampleRate}}
        return differences
    }

    private fun displaySignature(entry:QuickShareCatalog.VideoEntry):List<Any?> {
        val format=entry.format;val audio=entry.selection?.let {it.secondary ?: it.primary}
        return listOf(format.width,format.height,codecLabel(format.videoCodec),fpsLabel(format.fps),format.videoBitrate ?: format.bitrate,
            format.ext,format.dynamicRange,format.sourceQualityLabel,entry.selection?.transferSize,
            entry.selection?.secondary!=null,audio?.audioCodec,audio?.language,audio?.audioChannels,audio?.sampleRate)
    }
}
