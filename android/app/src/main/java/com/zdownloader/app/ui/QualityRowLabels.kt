package com.zdownloader.app.ui

import androidx.compose.runtime.Composable
import com.zdownloader.app.R
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.QuickShareCatalog

/** Consumer wording only. Source-label bands never change pixels, ranking, or selection. */
internal object QualityRowLabels {
    enum class Band { UNKNOWN, LOW, STANDARD, HIGH, FULL_HD }
    fun band(quality:Int?):Band = when {
        quality==null||quality<=0 -> Band.UNKNOWN
        quality<360 -> Band.LOW
        quality<720 -> Band.STANDARD
        quality<1080 -> Band.HIGH
        else -> Band.FULL_HD
    }
    fun titleResource(quality:Int?):Int = when(band(quality)) {
        Band.UNKNOWN -> R.string.share_video_format
        Band.LOW,Band.STANDARD -> R.string.share_quality_fast_title
        Band.HIGH,Band.FULL_HD -> R.string.share_quality_high_title
    }
    fun descriptionResource(quality:Int?):Int? = when(band(quality)) {
        Band.UNKNOWN -> null
        Band.LOW -> R.string.share_quality_low_description
        Band.STANDARD -> R.string.share_quality_standard_description
        Band.HIGH -> R.string.share_quality_high_description
        Band.FULL_HD -> R.string.share_quality_full_description
    }
    fun isolatedLabel(label:String)="\u2066$label\u2069"
}

@Composable internal fun sourceQualityRowTitle(format:Format):String {
    if(format.generatedSlideshow) return spectrumStringResource(R.string.generated_slideshow)
    val label=QuickShareCatalog.videoDisplayLabel(format) ?: return spectrumStringResource(R.string.share_video_format)
    return spectrumStringResource(QualityRowLabels.titleResource(QuickShareCatalog.numericVideoQuality(format)),QualityRowLabels.isolatedLabel(label))
}

@Composable internal fun sourceQualityRowDescription(format:Format):String =
    QualityRowLabels.descriptionResource(QuickShareCatalog.numericVideoQuality(format))?.let {spectrumStringResource(it)}.orEmpty()
