package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import org.json.JSONObject

/** Observes the real policy without modifying its inputs or result. No URLs or source IDs. */
internal object FormatDiagnostics {
    fun catalog(media:Media,operation:String) {
        NetworkDiagnostics.captureObservation {
        val caps=MediaIntelligence.platformCapabilities
        NetworkDiagnostics.record("formats","format_capabilities",null,operation=operation,
            details=JSONObject().put("hardware_av1",caps.hasHardwareAv1).put("hardware_vp9",caps.hasHardwareVp9)
                .put("hardware_avc",caps.hasHardwareAvc).put("hardware_hevc",caps.hasHardwareHevc))
        NetworkDiagnostics.record("formats","format_catalog",null,operation=operation,details=JSONObject().put("count",media.formats.size).put("recorded_formats",minOf(64,media.formats.size)))
        media.formats.take(64).forEachIndexed {index,format->
            NetworkDiagnostics.record("formats","format_row",null,operation=operation,details=properties(format)
                .put("format_alias","f$index").put("preference",MediaIntelligence.recommendationPreference(media,format,caps))
                .put("hardware",MediaIntelligence.hardwareSupported(format,caps)))
        }
        }
    }
    fun selection(media:Media,selection:Selection,operation:String) {
        NetworkDiagnostics.captureObservation {
        val video=selection.primary;val audio=selection.secondary
        NetworkDiagnostics.record("formats","format_choice",null,operation=operation,details=properties(video)
            .put("video_alias","f${media.formats.indexOf(video)}").put("audio_alias",audio?.let {"f${media.formats.indexOf(it)}"})
            .put("container",selection.output))
        // MP4 may substitute AAC after container eligibility was decided using Opus.
        // Query each real selection instead of inferring eligibility from that replacement.
        val outputs=if(selection.audioOnly) emptyList() else listOf("mp4","mkv","webm").filter {container->
            try {Selection.video(media,video,container);true} catch(_:MediaException) {false}
        }
        for(container in listOf("mp4","mkv","webm")) NetworkDiagnostics.record("formats","container_option",null,operation=operation,
            details=JSONObject().put("container",container).put("reason",if(container in outputs) "offered" else if(container=="webm") "codec_audio_incompatible" else "policy_not_offered"))
        audio?.let {NetworkDiagnostics.record("formats","format_row",null,operation=operation,details=properties(it).put("format_alias","f${media.formats.indexOf(it)}"))}
        }
    }
    private fun properties(format:Format)=JSONObject().put("width",format.width).put("height",format.height).put("fps",format.fps)
        .put("extension",format.ext)
        .put("video_codec",codecFamily(format.videoCodec).ifBlank {"unknown"})
        .put("audio_codec",codecFamily(format.audioCodec).ifBlank {"unknown"})
}
