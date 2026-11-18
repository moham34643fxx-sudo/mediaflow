package com.zdownloader.app.engine

import java.io.File

/** Restricted, ordered, unlaced VP9 track. Unsupported WebM features deny the optional proof. */
internal object WebmPacketInventory {
    private data class Block(val time:Long,val size:Long,val offset:Long,val sync:Boolean,val duration:Long?)
    private data class Element(val id:Long,val body:Long,val end:Long)
    private data class Vint(val number:Long,val width:Int,val unknown:Boolean)
    private data class BlockTiming(val track:Long,val base:Long,val duration:Long?)

    fun write(source:File,catalog:File,checkpoint:()->Unit):ContainerInventory = BoundedMediaFile(source,checkpoint).use {media->
        val top=elements(media,0,media.length)
        val segment=single(media,top,0x18538067)
        val header=single(media,top,0x1A45DFA3)
        val document=single(media,elements(media,header.body,header.end),0x4282)
        media.need(media.bytes(document.body,(document.end-document.body).toInt()).toString(Charsets.US_ASCII)=="webm","webm_document")
        val children=elements(media,segment.body,segment.end)
        val info=elements(media,single(media,children,0x1549A966))
        val scale=optional(media,info,0x2AD7B1)?.let {uint(media,it)} ?: 1_000_000L
        media.need(scale in 1..1_000_000_000,"webm_clock")
        val declaredEnd=duration(media,single(media,info,0x4489))
        val tracks=elements(media,single(media,children,0x1654AE6B))
        val track=elements(media,single(media,tracks,0xAE))
        val number=trackNumber(media,track)
        ContainerPacketCatalog(catalog,checkpoint).use {output->
            var pending:Block?=null
            for(cluster in children.filter {it.id==0x1F43B675L}) {
                checkpoint()
                val blocks=elements(media,cluster)
                val base=uint(media,single(media,blocks,0xE7))
                media.need(base<=Long.MAX_VALUE-32767,"webm_timestamp_overflow")
                for(element in blocks) {
                    val block=when(element.id) {
                        0xA3L->block(media,element,BlockTiming(number,base,null))
                        0xA0L->group(media,element,number,base)
                        else->{media.need(element.id in setOf(0xE7L,0xA7L,0xABL,0xECL,0xBFL),"webm_cluster_feature");continue}
                    }
                    val previous=pending
                    if(previous==null) media.need(block.time==0L&&block.sync,"webm_origin")
                    else emit(media,output,previous,block.time)
                    pending=block
                }
            }
            val last=pending ?: throw BoundedMediaFile.Unsupported("webm_no_packets")
            media.need(last.duration!=null,"webm_terminal_duration")
            emit(media,output,last,declaredEnd)
            ContainerInventory(ContainerClock(scale,1_000_000_000),output.packets,declaredEnd)
        }
    }

    private fun trackNumber(media:BoundedMediaFile,track:List<Element>):Long {
        val allowed=setOf(0xD7L,0x73C5L,0x83L,0x86L,0x63A2L,0xE0L,0x9CL,0xB9L,0x88L,0x55AAL,0x536EL,0x22B59CL)
        media.need(track.all {it.id in allowed},"webm_track_feature")
        optional(media,track,0xB9)?.let {media.need(uint(media,it)==1L,"webm_disabled_track")}
        media.need(uint(media,single(media,track,0x83))==1L,"webm_track_type")
        val codec=single(media,track,0x86)
        media.need(media.bytes(codec.body,(codec.end-codec.body).toInt()).toString(Charsets.US_ASCII)=="V_VP9","webm_codec")
        val geometry=elements(media,single(media,track,0xE0))
        media.need(geometry.all {it.id in setOf(0xB0L,0xBAL,0x54B0L,0x54BAL,0x54B2L)},"webm_video_feature")
        optional(media,geometry,0x54B2)?.let {media.need(uint(media,it)==0L,"webm_display_unit")}
        val number=uint(media,single(media,track,0xD7))
        media.need(number in 1..127,"webm_track_number")
        return number
    }

    private fun group(media:BoundedMediaFile,group:Element,number:Long,base:Long):Block {
        val parts=elements(media,group)
        media.need(parts.all {it.id in setOf(0xA1L,0x9BL,0xFBL)},"webm_block_group_feature")
        val duration=optional(media,parts,0x9B)?.let {uint(media,it)}
        val packet=block(media,single(media,parts,0xA1),BlockTiming(number,base,duration))
        return packet.copy(sync=parts.none {it.id==0xFBL})
    }
    private fun block(media:BoundedMediaFile,element:Element,timing:BlockTiming):Block {
        val track=vint(media,element.body,element.end,false)
        media.need(!track.unknown&&track.number==timing.track,"webm_block_track")
        val position=element.body+track.width
        media.bounds(position,4,element.end)
        val relative=media.unsigned(position,2).toShort().toLong()
        val flags=media.byte(position+2)
        media.need(flags and 127==0,"webm_lacing_or_block_flags")
        val time=timing.base+relative
        media.need(time>=0,"webm_negative_time")
        return Block(time,element.end-position-3,position+3,flags and 128!=0,timing.duration)
    }
    private fun emit(media:BoundedMediaFile,output:ContainerPacketCatalog,block:Block,end:Long) {
        media.need(end>block.time&&(block.duration==null||block.duration==end-block.time),"webm_duration_conflict")
        output.append(media,ContainerPacketCatalog.Packet(block.time,end-block.time,block.size,block.offset,block.sync))
    }
    private fun duration(media:BoundedMediaFile,element:Element):Long {
        val size=element.end-element.body
        val ticks=when(size) {4L->Float.fromBits(media.unsigned(element.body,4).toInt()).toDouble()
            8L->Double.fromBits(media.unsigned(element.body,8));else->throw BoundedMediaFile.Unsupported("webm_duration_width")}
        media.need(ticks.isFinite()&&ticks>0&&ticks<9_007_199_254_740_992.0&&ticks==ticks.toLong().toDouble(),"webm_duration_precision")
        return ticks.toLong()
    }
    private fun uint(media:BoundedMediaFile,element:Element)=media.unsigned(element.body,(element.end-element.body).toInt())
    private fun single(media:BoundedMediaFile,elements:List<Element>,id:Long)=optional(media,elements,id)
        ?: throw BoundedMediaFile.Unsupported("webm_missing_${id.toString(16)}")
    private fun optional(media:BoundedMediaFile,elements:List<Element>,id:Long):Element? {
        val matches=elements.filter {it.id==id};media.need(matches.size<=1,"webm_duplicate_element");return matches.singleOrNull()
    }
    private fun elements(media:BoundedMediaFile,parent:Element)=elements(media,parent.body,parent.end)
    private fun elements(media:BoundedMediaFile,start:Long,end:Long):List<Element> {
        val elements=ArrayList<Element>();var position=start
        while(position<end) {
            media.need(elements.size<16384,"webm_element_budget")
            val id=vint(media,position,end,true)
            val size=vint(media,position+id.width,end,false)
            val body=position+id.width+size.width
            media.need(!size.unknown||id.number==0x18538067L,"webm_unknown_size")
            val tail=if(size.unknown) end else {media.bounds(body,size.number,end);body+size.number}
            elements+=Element(id.number,body,tail);position=tail
        }
        return elements
    }
    private fun vint(media:BoundedMediaFile,position:Long,end:Long,identifier:Boolean):Vint {
        media.bounds(position,1,end);val first=media.byte(position)
        media.need(first!=0,"webm_vint_marker")
        val width=(1..8).first {first and (1 shl (8-it))!=0}
        media.need(!identifier||width<=4,"webm_identifier_width");media.bounds(position,width.toLong(),end)
        val raw=media.unsigned(position,width)
        val mask=(1L shl (7*width))-1
        return Vint(if(identifier) raw else raw and mask,width,!identifier&&(raw and mask)==mask)
    }
}
