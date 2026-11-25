package com.zdownloader.app.domain

/** Presentation only: never confuse quality preferences with confirmed source representations. */
internal object QuickShareCatalog {
    data class VideoEntry(val format:Format,val selection:Selection?)
    val compactPreferences=listOf(360,480,720)
    internal val basicQualities=setOf(360,480,720)
    private val sourceQualityLabelPattern=Regex("[1-9][0-9]{1,4}p")
    fun compactGroups(groups:List<QualityGroup>,selectedId:String?):List<QualityGroup> {
        // Show actual basic representations, never substitute a row under a made-up label.
        // 240p, nonstandard edges, higher resolutions and variants remain in More formats.
        // Caller owns selection state; non-basic selection stays preserved without mutating basic groups.
        return groups.filter {it.resolution in basicQualities}.sortedBy {it.resolution}
    }
    fun verifiedGroups(media:Media?):List<QualityGroup> = media?.qualityGroups().orEmpty()
        .filter {it.resolution!=null}.sortedBy {it.resolution}
    /** The source's own representation name, when it provided a well-formed one. */
    fun sourceQualityLabel(format:Format):String? = format.sourceQualityLabel
        ?.takeIf {it.matches(sourceQualityLabelPattern)}

    /**
     * Logical quality tier: the source's representation label when present (several DASH tiers may
     * share one container viewport), otherwise the verified pixel edge.
     */
    fun numericVideoQuality(format:Format):Int? = format.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull()?.takeIf {it in 1..32768}
        ?: verifiedQualityEdge(format)

    fun videoDisplayLabel(format:Format):String? = numericVideoQuality(format)?.let {"${it}p"}

    /** Match the row's transfer size, including its selected audio track when needed. */
    fun videoDisplaySize(media:Media,format:Format):MediaSize? = runCatching {Selection.video(media,format).transferSize}.getOrNull()

    /**
     * EXACTLY ONE ENTRY PER LOGICAL VIDEO QUALITY:
     * Group eligible video formats by their logical quality tier and select the single best candidate.
     * Every distinct tier the source offers stays visible; all variants remain in media.formats.
     */
    fun videoEntries(media:Media?,capabilities:MediaIntelligence.CodecCapabilities=MediaIntelligence.platformCapabilities):List<VideoEntry> {
        if(media==null) return emptyList()
        val eligible = media.formats.filter { it.video && FormatAvailability.eligible(it) }
        val preferences = eligible.associate {it.id to MediaIntelligence.recommendationPreference(media,it,capabilities)}
        val grouped = eligible.groupBy { numericVideoQuality(it) }
        val entries = grouped.mapNotNull { (resolution, rows) ->
            val ranked = rows.sortedWith(
                compareBy<Format> { runCatching { Selection.video(media, it) }.isFailure }
                    .thenByDescending { if (resolution == null) 0L else (it.width ?: 0).toLong() * (it.height ?: 0) }
                    .thenByDescending { isHdr(it) }
                    .thenByDescending { it.fps ?: 0.0 }
                    .thenBy { preferences.getValue(it.id) }
                    .thenBy { it.protocol in setOf("m3u8", "m3u8_native") }
                    .thenByDescending { it.videoBitrate ?: it.bitrate ?: 0.0 }
                    .thenByDescending { it.audio }
                    .thenBy { it.ext != "mp4" }
                    .thenBy { it.id }
            )
            val best = ranked.firstOrNull() ?: return@mapNotNull null
            val selection = runCatching { Selection.video(media, best) }.getOrNull()
            VideoEntry(best, selection)
        }
        return entries.sortedWith(compareBy<VideoEntry> { numericVideoQuality(it.format) ?: Int.MAX_VALUE }
            .thenBy { it.selection?.transferSize?.bytes ?: Long.MAX_VALUE }
            .thenBy { it.format.id })
    }
    fun allVideoSources(media:Media?):List<Format> {
        return selectableSources(videoEntries(media))
    }
    /** Expanded pickers retain every eligible source identity, not just the tier recommendation. */
    fun variantEntries(media:Media,groups:List<QualityGroup> = media.qualityGroups()):List<VideoEntry> = groups.sortedBy {it.resolution ?: Int.MAX_VALUE}
        .flatMap {it.candidates}.mapNotNull {format ->
            // A source may expire between grouping and selection; omit only that unavailable row.
            try {VideoEntry(format,Selection.video(media,format))} catch(_: MediaException) {null}
        }
    // Unknown bitrate or equal rounded metadata cannot prove two source IDs redundant.
    fun selectableSources(entries:List<VideoEntry>):List<Format> = entries.filter {it.selection!=null}.map {it.format}
    fun selectableGroups(groups:List<QualityGroup>,sources:List<Format>):List<QualityGroup> {
        val ids=sources.mapTo(hashSetOf()) {it.id}
        return groups.mapNotNull {group->
            val candidates=group.candidates.filter {it.id in ids}.distinctBy {it.id}
            candidates.takeIf {it.isNotEmpty()}?.let {group.copy(candidates=it)}
        }
    }
    fun unratedSources(media:Media?):List<Format> = media?.formats.orEmpty()
        .filter {it.video&&verifiedQualityEdge(it)==null&&FormatAvailability.eligible(it)}.distinctBy {it.id}.sortedBy {it.id}
}
