package com.zdownloader.app.ui

import com.zdownloader.app.domain.*

/** Compute distinguishing fields once per metadata snapshot, not on every row recomposition. */
internal object QuickShareVariantDetails {
    enum class Field { FPS, BITRATE, AUDIO, LANGUAGE, CHANNELS, SAMPLE_RATE }

    fun fields(entries:List<QuickShareCatalog.VideoEntry>):Map<String,Set<Field>> {
        val fieldsBySource=mutableMapOf<String,Set<Field>>()
        for(peers in entries.filter {it.selection!=null}.groupBy {QuickShareDimensions.from(it.format)}.values) {
            val sharedFields=groupFields(peers)
            for(entry in peers) fieldsBySource[entry.format.id]=sharedFields
        }
        return fieldsBySource
    }

    private fun groupFields(peers:List<QuickShareCatalog.VideoEntry>):Set<Field> {
        val differences=mutableSetOf<Field>()
        fun addDifference(field:Field,attribute:(QuickShareCatalog.VideoEntry)->Any?) {
            if(peers.map(attribute).distinct().size>1) differences.add(field)
        }
        addDifference(Field.FPS) {it.format.fps}
        addDifference(Field.BITRATE) {it.format.videoBitrate ?: it.format.bitrate}
        addDifference(Field.AUDIO) {entry->entry.selection?.let {listOf(it.secondary!=null,it.secondary?.audioCodec ?: it.primary.audioCodec)}}
        addDifference(Field.LANGUAGE) {it.selection?.let {s->(s.secondary ?: s.primary).language}}
        addDifference(Field.CHANNELS) {it.selection?.let {s->(s.secondary ?: s.primary).audioChannels}}
        addDifference(Field.SAMPLE_RATE) {it.selection?.let {s->(s.secondary ?: s.primary).sampleRate}}
        return differences
    }

}
