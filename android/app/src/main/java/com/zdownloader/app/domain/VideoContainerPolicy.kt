package com.zdownloader.app.domain

/** FFmpeg writes VP9's standard vp09/vpcC MP4 entries. Android's platform MP4
 * extractor has verified support from API 31; this is not MediaMuxer's codec table.
 * Older devices retain explicit MP4 conversion and prefer original-codec MKV/WebM.
 */
internal object VideoContainerPolicy {
    fun canCopyMp4Video(codec:String?,sdk:Int=android.os.Build.VERSION.SDK_INT):Boolean = when(codecFamily(codec)) {
        "h264","hevc","av1"->true
        "vp9"->sdk>=31
        else->false
    }
    fun transcodeVideo(codec:String?,output:String,sdk:Int=android.os.Build.VERSION.SDK_INT):Boolean =
        output=="mp4"&&codecFamily(codec).isNotBlank()&&!canCopyMp4Video(codec,sdk)
}
