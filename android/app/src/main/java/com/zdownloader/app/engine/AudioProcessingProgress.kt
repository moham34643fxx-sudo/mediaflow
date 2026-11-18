package com.zdownloader.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AudioProcessingPhase { ENCODING, FINALIZING, VALIDATING }
internal data class AudioProcessingSample(val phase:AudioProcessingPhase,val positionMs:Long=0,val durationMs:Long?=null) {
    val percent:Int? get()=durationMs?.takeIf {it>0}?.let {(positionMs.toDouble()*100/it).toInt().coerceIn(0,99)}
}

/** UI telemetry only. Never changes encoded samples, transfer counters, durable
 * checkpoints or validation. Old native callbacks cannot revive a closed attempt. */
internal object AudioProcessingProgress {
    private data class Entry(val owner:Any,val sample:AudioProcessingSample,val updated:Long)
    private val entries=mutableMapOf<String,Entry>()
    private val mutable=MutableStateFlow<Map<String,AudioProcessingSample>>(emptyMap())
    val state=mutable.asStateFlow()
    private fun publish() {mutable.value=entries.mapValues {it.value.sample}}
    @Synchronized fun start(id:String,durationSeconds:Double?,clock:()->Long={System.nanoTime()/1_000_000}):Attempt {
        val owner=Any()
        val duration=durationSeconds?.takeIf {it.isFinite()&&it>0&&it<Long.MAX_VALUE/1000.0}?.let {(it*1000).toLong()}
        entries[id]=Entry(owner,AudioProcessingSample(AudioProcessingPhase.ENCODING,durationMs=duration),clock()-1000)
        publish()
        return Attempt(id,owner,clock)
    }
    internal class Attempt internal constructor(private val id:String,private val owner:Any,private val clock:()->Long):AutoCloseable {
        fun sample(positionMs:Double)=synchronized(AudioProcessingProgress) {
            val old=entries[id]?.takeIf {it.owner===owner&&it.sample.phase==AudioProcessingPhase.ENCODING} ?: return@synchronized
            if(!positionMs.isFinite()||positionMs<0||positionMs>=Long.MAX_VALUE.toDouble()) return@synchronized
            val position=positionMs.toLong().coerceAtMost(old.sample.durationMs ?: Long.MAX_VALUE)
            val now=clock()
            if(position<=old.sample.positionMs||now-old.updated<1000) return@synchronized
            entries[id]=old.copy(sample=old.sample.copy(positionMs=position),updated=now);publish()
        }
        fun phase(phase:AudioProcessingPhase)=synchronized(AudioProcessingProgress) {
            val old=entries[id]?.takeIf {it.owner===owner&&phase.ordinal>=it.sample.phase.ordinal} ?: return@synchronized
            entries[id]=old.copy(sample=old.sample.copy(phase=phase),updated=clock());publish()
        }
        override fun close()=synchronized(AudioProcessingProgress) {
            if(entries[id]?.owner===owner) {entries.remove(id);publish()}
        }
    }
}
