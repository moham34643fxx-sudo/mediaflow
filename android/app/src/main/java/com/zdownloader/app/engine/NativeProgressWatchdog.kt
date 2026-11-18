package com.zdownloader.app.engine

/** Repeated statistics callbacks are not evidence that a native operation advances. */
internal class NativeProgressWatchdog(private val clock:()->Long,private val idleMillis:Long) {
    private var lastProgress=clock()
    private var mediaMillis=0L
    private var bytes=0L
    private var frames=0L

    init {require(idleMillis>0)}

    @Synchronized fun progress(mediaMillis:Long,bytes:Long,frames:Long) {
        if(mediaMillis>this.mediaMillis||bytes>this.bytes||frames>this.frames) lastProgress=clock()
        this.mediaMillis=maxOf(this.mediaMillis,mediaMillis)
        this.bytes=maxOf(this.bytes,bytes)
        this.frames=maxOf(this.frames,frames)
    }

    @Synchronized fun stalled():Boolean=clock()-lastProgress>=idleMillis
}
