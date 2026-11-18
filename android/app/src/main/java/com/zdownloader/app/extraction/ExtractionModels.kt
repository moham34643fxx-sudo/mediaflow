package com.zdownloader.app.extraction

import com.zdownloader.app.R

internal data class AudioTrack(val index:Int,val codec:String,val channels:Int,val sampleRate:Int,val duration:Double?,val language:String?,val title:String?,val default:Boolean)
internal data class ExtractionSource(val uri:String,val name:String,val bytes:Long?,val duration:Double?,val tracks:List<AudioTrack>)
internal enum class AudioOutput { ORIGINAL, M4A, MP3 }
internal data class ExtractionPlan(val extension:String,val mime:String,val codec:String,val copy:Boolean,val bitrate:Int,val channels:Int,val rate:Int)
internal enum class ExtractionPhase(val label:Int,val active:Boolean=false) {
    QUEUED(R.string.extract_queued,true), READING(R.string.extract_reading,true), EXTRACTING(R.string.extract_processing,true),
    VALIDATING(R.string.extract_validating,true), SAVING(R.string.extract_saving,true),
    COMPLETED(R.string.extract_completed), FAILED(R.string.extract_failed), CANCELLED(R.string.extract_cancelled),
    INTERRUPTED(R.string.extract_interrupted), SAVE_FAILED(R.string.extract_save_failed)
}
internal class ExtractionFailure(val messageId:Int):Exception()

/** Container compatibility is based on probed codec, never on the video's suffix. */
internal object ExtractionPolicy {
    fun original(codec:String):Pair<String,String>?=when(codec) {
        "aac","alac"->"m4a" to "audio/mp4"
        "mp3"->"mp3" to "audio/mpeg"
        "flac"->"flac" to "audio/flac"
        // Matroska retains Opus codec delay/discard padding across video demuxing.
        "opus"->"mka" to "audio/x-matroska"
        "vorbis"->"ogg" to "audio/ogg"
        "pcm_s16le","pcm_s24le","pcm_s32le","pcm_f32le"->"wav" to "audio/wav"
        else->null
    }
    fun canCopy(track:AudioTrack,output:AudioOutput)=when(output) {
        AudioOutput.ORIGINAL->original(track.codec)!=null
        AudioOutput.M4A->track.codec in setOf("aac","alac")
        AudioOutput.MP3->track.codec=="mp3"
    }
    fun plan(track:AudioTrack,output:AudioOutput,bitrate:Int):ExtractionPlan {
        require(track.index>=0&&track.channels in 1..32&&track.sampleRate>0)
        val copy=output==AudioOutput.ORIGINAL||bitrate==0&&canCopy(track,output)
        val format=when(output) {
            AudioOutput.ORIGINAL->original(track.codec) ?: throw ExtractionFailure(R.string.extract_no_original)
            AudioOutput.M4A->"m4a" to "audio/mp4"
            AudioOutput.MP3->"mp3" to "audio/mpeg"
        }
        if(!copy&&bitrate !in listOf(128,192,256,320)) throw ExtractionFailure(R.string.extract_invalid_settings)
        val rate=if(copy) track.sampleRate else if(output==AudioOutput.MP3) track.sampleRate.takeIf {it==44100||it==48000} ?: 44100
            else track.sampleRate.takeIf {it in setOf(7350,8000,11025,12000,16000,22050,24000,32000,44100,48000,64000,88200,96000)} ?: 48000
        return ExtractionPlan(format.first,format.second,if(copy) track.codec else if(output==AudioOutput.MP3) "mp3" else "aac",copy,bitrate,
            if(!copy&&output==AudioOutput.MP3) track.channels.coerceAtMost(2) else track.channels,rate)
    }
    fun filename(source:String,extension:String,id:String):String {
        val stem=source.substringBeforeLast('.',source).replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"),"_").trim().trim('.').take(90).ifBlank {"MediaFlow"}
        return "${stem}_audio_${id.take(8)}.$extension"
    }
}
