package com.zdownloader.app.domain

data class TransferSnapshot(val bytes:Long,val total:Long?,val kind:String)

/** Per-representation snapshots, never cumulative callback/attempt counters. */
class TransferProgress(private val metadata:List<MediaSize?>,private val combined:MediaSize?=null,retained:List<Long> = metadata.map {0L}) {
    private val bytes=retained.map {it.coerceAtLeast(0)}.toLongArray()
    private val totals=metadata.toMutableList()
    init {require(metadata.size in 1..2&&retained.size==metadata.size)}
    val retainedBytes:Long
        @Synchronized get()=bytes.fold(0L) {sum,value->if(value>Long.MAX_VALUE-sum) Long.MAX_VALUE else sum+value}
    @Synchronized fun update(track:Int,downloaded:Long,total:Long?,kind:String):TransferSnapshot {
        require(track in bytes.indices)
        bytes[track]=downloaded.coerceAtLeast(0)
        totals[track]=if(total!=null&&total>0) MediaSize(total,kind!="EXACT")
            else metadata[track]?.copy(estimated=true)
        val retained=retainedBytes
        val available=if(totals.all {it!=null}) {
            val sum=totals.sumOf {it!!.bytes.toDouble()}
            sum.takeIf {it.isFinite()&&it<Long.MAX_VALUE.toDouble()}?.let {MediaSize(it.toLong(),totals.any {it!!.estimated})}
        } else combined
        val size=available?.takeIf {it.bytes>=retained&&it.bytes>0}
        return TransferSnapshot(retained,size?.bytes,if(size==null) "UNKNOWN" else if(size.estimated) "ESTIMATED" else "EXACT")
    }
}
