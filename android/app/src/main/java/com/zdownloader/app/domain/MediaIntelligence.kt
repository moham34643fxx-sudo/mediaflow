package com.zdownloader.app.domain

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Format recommendations are preferences, never a promise of equivalent visual quality. */
object MediaIntelligence {
    data class CodecCapabilities(
        val hasHardwareAvc: Boolean = false,
        val hasHardwareHevc: Boolean = false,
        val hasHardwareAv1: Boolean = false,
        val hasHardwareVp9: Boolean = false,
        internal val supportsFormat: ((Format) -> Boolean)? = null
    )

    internal val platformCapabilities: CodecCapabilities by lazy { detectPlatformCapabilities() }

    internal fun hardwareDecoder(name: String, encoder: Boolean, hardwareFlag: Boolean?): Boolean =
        !encoder && (hardwareFlag ?: (name.startsWith("OMX.", true) &&
            !name.startsWith("OMX.google.", true) && !name.contains(".sw.", true)))

    private fun mime(codec: String?): String? = when(codecFamily(codec)) {
        "h264" -> "video/avc"; "hevc" -> "video/hevc"
        "vp9" -> "video/x-vnd.on2.vp9"; "vp8" -> "video/x-vnd.on2.vp8"; "av1" -> "video/av01"; else -> null
    }

    @androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
    internal fun detectPlatformCapabilities(): CodecCapabilities = runCatching {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            hardwareDecoder(it.name, it.isEncoder, if(Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else null)
        }
        fun has(type: String) = decoders.any { it.supportedTypes.any { t -> t.equals(type,true) } }
        val supportCache=android.util.LruCache<String,Boolean>(256)
        CodecCapabilities(has("video/avc"), has("video/hevc"), has("video/av01"), has("video/x-vnd.on2.vp9")) { format ->
            val key=listOf(format.videoCodec,format.width,format.height,format.fps,format.dynamicRange).joinToString("|")
            val cached=supportCache.get(key)
            val type = mime(format.videoCodec)
            cached ?: (type != null && decoders.any { decoder -> runCatching {
                val caps = decoder.getCapabilitiesForType(type)
                val request = MediaFormat().apply {
                    setString(MediaFormat.KEY_MIME,type)
                    format.width?.let { setInteger(MediaFormat.KEY_WIDTH,it) }
                    format.height?.let { setInteger(MediaFormat.KEY_HEIGHT,it) }
                    format.fps?.let { setFloat(MediaFormat.KEY_FRAME_RATE,it.toFloat()) }
                }
                val profile = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(
                    androidx.media3.common.Format.Builder().setSampleMimeType(type).setCodecs(format.videoCodec).build())
                // Unknown profile/HDR support is not advertised as confirmed hardware support.
                if(profile == null && (isHdr(format) || format.videoCodec.orEmpty().contains('.'))) false
                else {
                    profile?.let { request.setInteger(MediaFormat.KEY_PROFILE,it.first); request.setInteger(MediaFormat.KEY_LEVEL,it.second) }
                    caps.isFormatSupported(request)
                }
            }.getOrDefault(false) }).also { supportCache.put(key,it) }
        }
    }.getOrDefault(CodecCapabilities())

    internal fun hardwareSupported(format: Format, capabilities: CodecCapabilities): Boolean =
        capabilities.supportsFormat?.invoke(format) ?: when(codecFamily(format.videoCodec)) {
            "h264" -> capabilities.hasHardwareAvc; "hevc" -> capabilities.hasHardwareHevc
            "av1" -> capabilities.hasHardwareAv1; "vp9" -> capabilities.hasHardwareVp9; else -> false
        }

    /** Used by the real quality picker and recommendation entry point alike.
     * Compare only after resolution/HDR/FPS; bitrate is meaningful within a codec, not across codecs.
     * Prefer hardware paths, then efficient families; software fallback favors AVC compatibility.
     */
    internal fun preference(format: Format, capabilities: CodecCapabilities): Int {
        val family = codecFamily(format.videoCodec)
        return if(hardwareSupported(format,capabilities)) when(family) {
            "av1" -> 0; "hevc" -> 1; "vp9" -> 2; "h264" -> 3; else -> 4
        } else when(family) {
            "h264" -> 10; "hevc" -> 11; "vp9" -> 12; "av1" -> 13; else -> 14
        }
    }

    /** Rank actual conversion work before the codec tie-breaker; all codecs now share fast validation.
     * Resolution, HDR and FPS are still compared first; explicit choices never pass here.
     */
    internal fun recommendationPreference(media: Media, format: Format, capabilities: CodecCapabilities): Int {
        val preference = preference(format, capabilities)
        if(!format.video) return preference
        val selection = try { Selection.video(media,format) } catch(_: MediaException) { return preference }
        // Ordering tiers, not time estimates: avoid conversion before breaking ties by codec family.
        val conversionPenalty=when {
            selection.transcodeVideo -> 40
            selection.transcodeAudio -> 20
            else -> 0
        }
        return preference + conversionPenalty
    }

    // Retained API for diagnostics; production ranking uses the same preference function.
    fun score(format: Format, preferredHeight: Int = 0, powerConstrained: Boolean = false,
              capabilities: CodecCapabilities = platformCapabilities): Double {
        if(!format.video) return Double.MAX_VALUE
        val distance = if(preferredHeight > 0) kotlin.math.abs((qualityLabel(format) ?: 0)-preferredHeight) else 0
        return distance*100.0 + preference(format,capabilities) +
            if(powerConstrained && !hardwareSupported(format,capabilities)) 10.0 else 0.0
    }

    fun recommend(media: Media, preferredHeight: Int = 0,
                  capabilities: CodecCapabilities = platformCapabilities): Format? {
        val groups=media.qualityGroups(capabilities)
        return if(preferredHeight>0) groups.firstOrNull { (it.resolution ?: Int.MAX_VALUE)<=preferredHeight }?.recommended
            else groups.firstOrNull()?.recommended
    }
}
