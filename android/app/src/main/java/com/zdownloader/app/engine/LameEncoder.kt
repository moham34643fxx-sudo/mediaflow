package com.zdownloader.app.engine

/** Actual libmp3lame built from pinned source for each APK ABI, not host FFmpeg. */
object LameEncoder {
    init { System.loadLibrary("zlame") }
    external fun version():String
    external fun encode(input:String,output:String,bitrate:Int,stopped:java.util.concurrent.atomic.AtomicBoolean):Int
    external fun encodeEx(input:String,output:String,bitrate:Int,sampleRate:Int,channels:Int,stopped:java.util.concurrent.atomic.AtomicBoolean):Int
    external fun encodePipe(inputFd:Int,output:String,bitrate:Int,sampleRate:Int,channels:Int,stopped:java.util.concurrent.atomic.AtomicBoolean):Int
}
