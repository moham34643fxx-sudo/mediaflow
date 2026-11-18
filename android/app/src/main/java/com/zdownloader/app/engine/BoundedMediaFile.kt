package com.zdownloader.app.engine

import java.io.File
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.security.MessageDigest

/** Untrusted container offsets never escape their parent; cache size is independent of media length. */
internal class BoundedMediaFile(file:File,private val checkpoint:()->Unit):AutoCloseable {
    class Unsupported(val reason:String):Exception(reason)
    data class Region(val kind:Long,val start:Long,val body:Long,val end:Long)
    private val input=RandomAccessFile(file,"r")
    val length=input.length()
    private val pages=object:LinkedHashMap<Long,ByteArray>(8,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,ByteArray>?)=size>8
    }

    fun need(condition:Boolean,reason:String) {if(!condition) throw Unsupported(reason)}
    fun bounds(offset:Long,count:Long,end:Long=length) {
        need(offset>=0&&end<=length&&offset<=end&&count>=0&&count<=end-offset,"read_bounds")
    }
    fun byte(offset:Long):Int {
        bounds(offset,1)
        return page(offset)[(offset%65536).toInt()].toInt() and 255
    }
    private fun page(offset:Long):ByteArray {
        val page=offset/65536
        return pages[page] ?: run {
            checkpoint()
            val start=page*65536
            val bytes=ByteArray(minOf(65536L,length-start).toInt())
            input.seek(start);input.readFully(bytes);pages[page]=bytes;bytes
        }
    }
    fun hash(offset:Long,count:Long):ByteArray {
        bounds(offset,count)
        val digest=MessageDigest.getInstance("SHA-256")
        var position=offset
        val end=offset+count
        while(position<end) {
            checkpoint()
            val cached=page(position);val index=(position%65536).toInt()
            val amount=minOf((cached.size-index).toLong(),end-position).toInt()
            digest.update(cached,index,amount);position+=amount
        }
        return digest.digest()
    }
    fun unsigned(offset:Long,count:Int):Long {
        need(count in 1..8,"integer_width");bounds(offset,count.toLong())
        var number=0L
        repeat(count) {number=(number shl 8) or byte(offset+it).toLong()}
        need(count<8||number>=0,"unsigned_long_overflow")
        return number
    }
    fun signed32(offset:Long)=unsigned(offset,4).toInt().toLong()
    fun bytes(offset:Long,count:Int):ByteArray {
        need(count in 0..65536,"byte_array_budget");bounds(offset,count.toLong())
        return ByteArray(count) {byte(offset+it).toByte()}
    }
    fun boxes(start:Long=0,end:Long=length):List<Region> {
        bounds(start,end-start)
        val regions=ArrayList<Region>()
        var offset=start
        while(offset<end) {
            checkpoint();bounds(offset,8,end)
            val declared=unsigned(offset,4);val header=if(declared==1L) 16L else 8L
            bounds(offset,header,end)
            val size=when(declared) {0L->end-offset;1L->unsigned(offset+8,8);else->declared}
            need(size>=header&&size<=end-offset,"box_size")
            need(regions.size<16384,"box_budget")
            regions+=Region(unsigned(offset+4,4),offset,offset+header,offset+size)
            offset+=size
        }
        return regions
    }
    fun children(region:Region)=boxes(region.body,region.end)
    fun one(regions:List<Region>,kind:String):Region=optional(regions,kind) ?: throw Unsupported("missing_$kind")
    fun optional(regions:List<Region>,kind:String):Region? {
        val matches=regions.filter {it.kind==tag(kind)}
        need(matches.size<=1,"duplicate_$kind");return matches.singleOrNull()
    }
    fun full(region:Region,versions:Set<Int> = setOf(0)):Long {
        bounds(region.body,4,region.end);need(byte(region.body) in versions,"fullbox_version")
        return unsigned(region.body+1,3)
    }
    fun count(region:Region,width:Int):Long {
        need(full(region)==0L,"table_flags");bounds(region.body,8,region.end)
        val count=unsigned(region.body+4,4)
        need(count<=2_000_000&&region.end-region.body-8==count*width,"table_count")
        return count
    }
    override fun close() {input.close();pages.clear()}
    companion object {
        fun tag(text:String):Long {
            require(text.length==4)
            return text.fold(0L) {number,char->(number shl 8) or char.code.toLong()}
        }
    }
}
