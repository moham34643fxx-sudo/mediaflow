package com.zdownloader.app.domain

internal object WebmCompanionAudio {
    fun select(options:List<Format>,original:Format):Format? {
        if(codecFamily(original.audioCodec) in setOf("opus","vorbis")) return original
        // Reuse the established conservative guard for language, channels, sample rate and audio quality.
        return options.filter {it.streamOnly && codecFamily(it.audioCodec) in setOf("opus","vorbis") &&
            Mp4CompanionAudio.preservesProfile(it,original)}
            .maxWithOrNull(compareBy<Format> {it.protocol !in setOf("m3u8","m3u8_native")}
                .thenBy {it.audioBitrate ?: it.bitrate ?: 0.0})
    }
}
