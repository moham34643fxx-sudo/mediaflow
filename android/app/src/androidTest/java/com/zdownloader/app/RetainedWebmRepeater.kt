package com.zdownloader.app

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Test-only repetition of pinned packets. No transcode, stale cues, CRCs or newly inferred frame duration. */
internal class RetainedWebmRepeater(source:File):AutoCloseable {
    private data class Element(val id:Long,val start:Long,val body:Long,val end:Long)
    private val input=RandomAccessFile(source,"r")
    private val buffer=ByteArray(65536)

    fun repeat(output:File,repeats:Int):File {
        require(repeats in 1..6)
        val top=children(0,input.length())
        val segment=top.single {it.id==0x18538067L}
        val parts=children(segment.body,segment.end)
        val info=parts.single {it.id==0x1549A966L}
        val infoParts=children(info.body,info.end)
        val duration=infoParts.single {it.id==0x4489L}
        check(duration.end-duration.body in setOf(4L,8L))
        input.seek(duration.body)
        val end=if(duration.end-duration.body==8L) Double.fromBits(input.readLong()) else Float.fromBits(input.readInt()).toDouble()
        check(end.isFinite()&&end>0&&end==end.toLong().toDouble()&&end*repeats<9_007_199_254_740_992.0)
        val tracks=parts.single {it.id==0x1654AE6BL}
        output.outputStream().buffered().use {sink->
            copy(top.single {it.id==0x1A45DFA3L},sink)
            sink.write(byteArrayOf(0x18,0x53,0x80.toByte(),0x67,1,-1,-1,-1,-1,-1,-1,-1))
            writeInfo(sink,infoParts,end*repeats)
            copy(tracks,sink)
            for(repetition in 0 until repeats) for(cluster in parts.filter {it.id==0x1F43B675L})
                writeCluster(sink,cluster,end.toLong()*repetition)
        }
        return output
    }
    private fun writeInfo(sink:OutputStream,parts:List<Element>,duration:Double) {
        val durationBytes=ByteBuffer.allocate(8).putDouble(duration).array()
        val payload=parts.sumOf {if(it.id==0x4489L) header(0x4489,8).size+8L else it.end-it.start}
        sink.write(header(0x1549A966,payload))
        for(part in parts) if(part.id==0x4489L) {sink.write(header(part.id,8));sink.write(durationBytes)} else copy(part,sink)
    }
    private fun writeCluster(sink:OutputStream,cluster:Element,offset:Long) {
        val parts=children(cluster.body,cluster.end)
        val time=parts.single {it.id==0xE7L}
        input.seek(time.body)
        val original=unsigned((time.end-time.body).toInt())
        check(original<=Long.MAX_VALUE-offset)
        val rebased=integer(original+offset)
        val blocks=parts.filter {it.id in setOf(0xA3L,0xA0L)}
        check(parts.all {it.id in setOf(0xE7L,0xA3L,0xA0L,0xA7L,0xABL,0xECL,0xBFL)})
        val size=header(0xE7,rebased.size.toLong()).size+rebased.size.toLong()+blocks.sumOf {it.end-it.start}
        sink.write(header(0x1F43B675,size));sink.write(header(0xE7,rebased.size.toLong()));sink.write(rebased)
        for(block in blocks) copy(block,sink)
    }
    private fun copy(element:Element,sink:OutputStream) {
        input.seek(element.start)
        var remaining=element.end-element.start
        while(remaining>0) {
            val count=minOf(buffer.size.toLong(),remaining).toInt()
            input.readFully(buffer,0,count);sink.write(buffer,0,count);remaining-=count
        }
    }
    private fun children(start:Long,end:Long):List<Element> {
        check(start>=0&&start<=end&&end<=input.length())
        val elements=ArrayList<Element>();var position=start
        while(position<end) {
            check(elements.size<16384)
            input.seek(position)
            val id=vint(true);val size=vint(false)
            val body=input.filePointer
            check(size>=0||id==0x18538067L)
            check(size<0||size<=end-body)
            val tail=if(size<0) end else body+size
            elements+=Element(id,position,body,tail);position=tail
        }
        return elements
    }
    private fun vint(identifier:Boolean):Long {
        val first=input.readUnsignedByte();check(first!=0)
        val width=(1..8).first {first and (1 shl (8-it))!=0}
        check(!identifier||width<=4)
        var number=if(identifier) first.toLong() else (first and ((1 shl (8-width))-1)).toLong()
        repeat(width-1) {number=(number shl 8) or input.readUnsignedByte().toLong()}
        if(!identifier&&number==(1L shl (7*width))-1) return -1
        return number
    }
    private fun unsigned(width:Int):Long {
        check(width in 1..8)
        var number=0L
        repeat(width) {number=(number shl 8) or input.readUnsignedByte().toLong()}
        check(number>=0);return number
    }
    private fun integer(number:Long):ByteArray {
        check(number>=0)
        val width=(1..8).first {it==8||number ushr (it*8)==0L}
        return ByteArray(width) {(number ushr ((width-it-1)*8)).toByte()}
    }
    private fun header(id:Long,size:Long):ByteArray {
        check(size>=0)
        val width=(1..8).first {size<(1L shl (it*7))-1}
        val encoded=size or (1L shl (width*7))
        return integer(id)+ByteArray(width) {(encoded ushr ((width-it-1)*8)).toByte()}
    }
    override fun close() {input.close()}
}
