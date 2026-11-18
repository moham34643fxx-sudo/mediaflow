package com.zdownloader.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads only actual EBML Info/Tracks in a bounded prefix; never scans cluster payload.
 * Incomplete, encrypted or unfamiliar layouts fall back to the general extractor. */
internal data class WebmHeader(val width:Int?,val height:Int?,val duration:Double,
    val videoCodec:String?,val audioCodec:String?) {
    companion object {
        private data class Element(val id:Long,val start:Int,val end:Int,val complete:Boolean)
        fun parse(bytes:ByteArray):WebmHeader? = runCatching {
            if(bytes.size>65536) return null
            fun vint(position:Int,id:Boolean):Pair<Long,Int>? {
                if(position !in bytes.indices) return null
                val first=bytes[position].toInt() and 255
                if(first==0) return null
                val length=Integer.numberOfLeadingZeros(first)-24+1
                if(length>(if(id) 4 else 8) || position+length>bytes.size) return null
                var value=(if(id) first else first and (255 ushr length)).toLong()
                for(index in 1 until length) value=(value shl 8) or (bytes[position+index].toLong() and 255)
                if(!id && value==(1L shl (7*length))-1) value=-1
                return value to length
            }
            fun elements(start:Int,end:Int):List<Element> {
                val rows=mutableListOf<Element>();var position=start
                while(position<end && rows.size<128) {
                    val id=vint(position,true) ?: break
                    val size=vint(position+id.second,false) ?: break
                    val content=position+id.second+size.second
                    if(content>end) break
                    val complete=size.first>=0 && size.first<=end-content
                    val finish=if(complete) content+size.first.toInt() else end
                    rows+=Element(id.first,content,finish,complete)
                    if(!complete) break
                    position=finish
                }
                return rows
            }
            fun unsigned(element:Element?):Long? {
                if(element==null||!element.complete||element.end-element.start !in 1..8) return null
                var value=0L
                for(index in element.start until element.end) value=(value shl 8) or (bytes[index].toLong() and 255)
                return value.takeIf {it>=0}
            }
            fun text(element:Element?)=element?.takeIf {it.complete}?.let {String(bytes,it.start,it.end-it.start,Charsets.UTF_8)}
            val top=elements(0,bytes.size)
            val ebml=top.firstOrNull()?.takeIf {it.id==0x1A45DFA3L&&it.complete} ?: return null
            if(text(elements(ebml.start,ebml.end).firstOrNull {it.id==0x4282L})!="webm") return null
            val segment=top.firstOrNull {it.id==0x18538067L} ?: return null
            val children=elements(segment.start,segment.end)
            val info=children.firstOrNull {it.id==0x1549A966L&&it.complete} ?: return null
            val infoFields=elements(info.start,info.end)
            val scaleField=infoFields.firstOrNull {it.id==0x2AD7B1L}
            val scale=if(scaleField==null) 1_000_000L else unsigned(scaleField)?.takeIf {it>0} ?: return null
            val duration=infoFields.firstOrNull {it.id==0x4489L&&it.complete}?.let {element->
                val buffer=ByteBuffer.wrap(bytes,element.start,element.end-element.start).order(ByteOrder.BIG_ENDIAN)
                when(element.end-element.start) {4->buffer.float.toDouble();8->buffer.double;else->null}
            }?.times(scale.toDouble()/1_000_000_000)?.takeIf {it.isFinite()&&it>0} ?: return null
            val tracks=children.firstOrNull {it.id==0x1654AE6BL&&it.complete} ?: return null
            var video:String?=null;var audio:String?=null;var width:Int?=null;var height:Int?=null
            val entries=elements(tracks.start,tracks.end).filter {it.id==0xAEL}
            if(entries.isEmpty()||entries.any {!it.complete}) return null
            for(entry in entries) {
                val fields=elements(entry.start,entry.end)
                // ContentEncodings can carry encryption; leave these to yt-dlp.
                if(fields.any {it.id==0x6D80L}) return null
                val codec=text(fields.firstOrNull {it.id==0x86L})
                when(unsigned(fields.firstOrNull {it.id==0x83L})) {
                    1L->{
                        if(video!=null) return null
                        video=when(codec) {"V_VP8"->"vp8";"V_VP9"->"vp9";"V_AV1"->"av01";else->return null}
                        val details=fields.firstOrNull {it.id==0xE0L&&it.complete} ?: return null
                        val pixels=elements(details.start,details.end)
                        width=unsigned(pixels.firstOrNull {it.id==0xB0L})?.takeIf {it in 1..32768}?.toInt() ?: return null
                        height=unsigned(pixels.firstOrNull {it.id==0xBAL})?.takeIf {it in 1..32768}?.toInt() ?: return null
                    }
                    2L->{
                        if(audio!=null) return null
                        audio=when(codec) {"A_OPUS"->"opus";"A_VORBIS"->"vorbis";else->return null}
                    }
                }
            }
            if(video==null&&audio==null) return null
            WebmHeader(width,height,duration,video,audio)
        }.getOrNull()
    }
}
