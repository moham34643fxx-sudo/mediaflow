package com.zdownloader.app.domain

internal object Mp4CompanionAudio {
    fun select(options:List<Format>,original:Format):Format {
        if(codecFamily(original.audioCodec) !in setOf("opus","vorbis")) return original
        return options.filter {it.streamOnly&&codecFamily(it.audioCodec)=="aac"&&preservesProfile(it,original)}
            .maxWithOrNull(
                compareBy<Format> {it.protocol !in setOf("m3u8","m3u8_native")}
                    .thenBy {!isHeAac(it)}
                    .thenBy {isAacLc(it)}
                    .thenBy {!isDrc(it)}
                    .thenBy {it.audioBitrate ?: it.bitrate ?: 0.0}
            ) ?: original
    }

    private fun isDrc(format:Format):Boolean =
        format.id.contains("drc",ignoreCase=true) || format.payload.contains("\"drc\":",ignoreCase=true)

    private fun isHeAac(format:Format):Boolean {
        val codec=format.audioCodec.orEmpty().lowercase()
        return codec.contains("mp4a.40.5") || codec.contains("mp4a.40.29") || codec.contains("he-aac") || codec.contains("heaac")
    }

    private fun isAacLc(format:Format):Boolean {
        val codec=format.audioCodec.orEmpty().lowercase()
        return codec=="aac" || codec.contains("mp4a.40.2") || codec.contains("lc") || (!isHeAac(format) && (format.sampleRate ?: 0) >= 44100)
    }

    internal fun preservesProfile(candidate:Format,original:Format):Boolean {
        val channels=original.audioChannels?.takeIf {it>0} ?: return false
        val originalSampleRate=original.sampleRate?.takeIf {it>0} ?: return false
        val originalRate=(original.audioBitrate ?: original.bitrate)?.takeIf {it.isFinite()&&it>0} ?: return false

        // Preserve channel configuration strictly (stereo remains stereo, mono remains mono)
        if(candidate.audioChannels != channels) return false

        // Accept standard broadcast/consumer sample rates for MP4 AAC (44.1 kHz / 48 kHz).
        // Reject unsuitable low-rate telephony streams (22.05 kHz, 16 kHz, 8 kHz).
        val candidateSampleRate=candidate.sampleRate?.takeIf {it>0} ?: return false
        if(candidateSampleRate !in setOf(44100, 48000)) return false

        // Language matching:
        // - If original language is explicitly known, candidate must match.
        // - If original language is missing/null, candidate must also have no conflicting language.
        val originalLang=original.language?.trim()?.takeIf {it.isNotEmpty()}
        val candidateLang=candidate.language?.trim()?.takeIf {it.isNotEmpty()}
        if(originalLang != null) {
            if(!candidateLang.equals(originalLang, ignoreCase = true)) return false
        } else {
            if(candidateLang != null) return false
        }
        if((candidate.languagePreference ?: 0.0) != (original.languagePreference ?: 0.0)) return false

        // Bitrate & quality evaluation:
        // - Quality floor: reject clearly low-quality streams (< 96 kbps, e.g. 48.8 kbps HE-AAC Format 139).
        // - Prevent significant quality downgrade from high-bitrate sources.
        val candidateRate=(candidate.audioBitrate ?: candidate.bitrate)?.takeIf {it.isFinite()&&it>0} ?: return false
        if(candidateRate < 96.0) return false
        if(originalRate >= 200.0 && candidateRate < originalRate * 0.70) return false

        return true
    }
}
