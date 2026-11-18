package com.zdownloader.app.ui

import com.zdownloader.app.domain.Format
import java.util.Locale

/** Audio has no video-resolution tiers; group every source for a language and rank its options by fidelity. */
internal data class QuickShareAudioGroupRow(
    val language: String?,
    val candidates: List<Format>,
    val selectedId: String?,
    val expanded: Boolean
) {
    val key: String get() = QuickShareAudioRows.key(language)
    val tag: String get() = QuickShareAudioRows.tag(language)
    val toggleTag: String get() = QuickShareAudioRows.toggleTag(language)
    val formatTag: String get() = QuickShareAudioRows.formatTag(language)
    val formatToggleTag: String get() = QuickShareAudioRows.formatToggleTag(language)
    val format: Format get() = candidates.firstOrNull { it.id == selectedId } ?: candidates.first()
    val selected: Boolean get() = candidates.any { it.id == selectedId }
    val canExpand: Boolean get() = candidates.size > 1
}

internal data class QuickShareAudioRow(val format: Format, val selected: Boolean)

internal object QuickShareAudioRows {
    fun grouped(formats: List<Format>, selectedId: String?, expandedKeys: Set<String> = emptySet()): List<QuickShareAudioGroupRow> {
        val groups = formats.asSequence().filter { it.audio }.distinctBy { it.id }
            .groupBy { languageKey(it.language) }
        return groups.map { (language, formatsInLanguage) ->
            val ordered = formatsInLanguage.sortedWith(
                compareByDescending<Format> { audioRate(it) }
                    .thenByDescending { it.audioChannels ?: 0 }
                    .thenByDescending { it.sampleRate ?: 0 }
                    .thenBy { it.ext }
                    .thenBy { it.id }
            )
            QuickShareAudioGroupRow(language, ordered, selectedId, key(language) in expandedKeys)
        }.sortedWith(
            compareByDescending<QuickShareAudioGroupRow> { it.candidates.maxOfOrNull { format -> format.languagePreference ?: 0.0 } ?: 0.0 }
                .thenByDescending { it.candidates.maxOfOrNull(::audioRate) ?: 0.0 }
                .thenBy { it.language ?: "" }
        )
    }

    fun variants(group: QuickShareAudioGroupRow, selectedId: String?): List<QuickShareAudioRow> =
        group.candidates.map { QuickShareAudioRow(it, it.id == selectedId) }

    fun key(language: String?) = "audio-language:${languageKey(language) ?: "unknown"}"

    fun tag(language: String?) = "share_audio_language_${tagToken(language)}"

    fun toggleTag(language: String?) = "share_audio_language_toggle_${tagToken(language)}"

    fun formatTag(language: String?) = "format_audio_language_${tagToken(language)}"

    fun formatToggleTag(language: String?) = "format_audio_language_toggle_${tagToken(language)}"

    private fun languageKey(language: String?): String? = language?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { Locale.forLanguageTag(it).toLanguageTag().takeUnless { tag -> tag == "und" } ?: it.lowercase(Locale.ROOT) }

    private fun tagToken(language: String?) = languageKey(language)?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9_-]"), "_")?.take(48)?.ifBlank { "unknown" } ?: "unknown"

    private fun audioRate(format: Format): Double = (format.audioBitrate ?: if (format.streamOnly) format.bitrate else null)
        ?.takeIf { it.isFinite() && it > 0 } ?: 0.0
}
