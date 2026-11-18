package com.zdownloader.app.engine

import java.io.File
import com.zdownloader.app.engine.BoundedMediaFile.Region

/** Reads only supported local, zero-offset, non-reordered sample tables and AAC fragments. */
internal class Mp4PacketInventory private constructor(private val media:BoundedMediaFile,private val checkpoint:()->Unit) {
    private data class Track(val id:Long,val scale:Long,val duration:Long,val trackDuration:Long,
        val parts:List<Region>,val tables:List<Region>,val audio:Boolean)
    private data class Fragment(val moof:Region,val defaults:LongArray,val output:ContainerPacketCatalog)
    private val top=media.boxes()
    private val moov=media.one(top,"moov")
    private val movie=media.children(moov)
    private val mdats=top.filter {it.kind==tag("mdat")}

    private fun track(audio:Boolean):Track {
        val trackBoxes=movie.filter {it.kind==tag("trak")}
        media.need(trackBoxes.size in 1..2,"track_topology")
        val tracks=trackBoxes.map {trak->
            val parts=media.children(trak)
            val tkhd=media.one(parts,"tkhd")
            val flags=media.full(tkhd,setOf(0,1));val version=media.byte(tkhd.body)
            media.need(flags in setOf(3L,7L)&&tkhd.end-tkhd.body==if(version==1) 96L else 84L,"track_header")
            val id=media.unsigned(tkhd.body+if(version==1) 20 else 12,4)
            val trackDuration=media.unsigned(tkhd.body+if(version==1) 28 else 20,if(version==1) 8 else 4)
            media.need(id>0,"track_identity")
            val matrix=media.bytes(tkhd.end-44,36)
            media.need(matrix.contentEquals(IDENTITY_MATRIX),"track_matrix")
            val mdia=media.children(media.one(parts,"mdia"))
            val hdlr=media.one(mdia,"hdlr");media.full(hdlr);media.bounds(hdlr.body,12,hdlr.end)
            val kind=media.unsigned(hdlr.body+8,4)
            media.need(kind in setOf(tag("vide"),tag("soun")),"track_kind")
            val (scale,duration)=clock(media.one(mdia,"mdhd"))
            val minf=media.children(media.one(mdia,"minf"))
            localDataReference(media.one(minf,"dinf"))
            val tables=media.children(media.one(minf,"stbl"))
            Track(id,scale,duration,trackDuration,parts,tables,kind==tag("soun"))
        }
        media.need(tracks.size in 1..2&&tracks.map {it.id}.distinct().size==tracks.size&&
            tracks.map {it.audio}.distinct().size==tracks.size,"track_topology")
        return tracks.singleOrNull {it.audio==audio} ?: throw BoundedMediaFile.Unsupported("missing_track")
    }

    private fun localDataReference(dinf:Region) {
        val dref=media.one(media.children(dinf),"dref")
        media.need(media.full(dref)==0L&&media.unsigned(dref.body+4,4)==1L,"data_reference_count")
        val refs=media.boxes(dref.body+8,dref.end)
        media.need(refs.size==1&&refs[0].kind==tag("url ")&&refs[0].end-refs[0].body==4L&&media.full(refs[0])==1L,"external_data_reference")
    }
    private fun clock(region:Region):Pair<Long,Long> {
        media.need(media.full(region,setOf(0,1))==0L,"clock_flags")
        val version=media.byte(region.body)
        val position=region.body+if(version==1) 20 else 12
        media.bounds(position,if(version==1) 12 else 8,region.end)
        val scale=media.unsigned(position,4)
        val duration=media.unsigned(position+4,if(version==1) 8 else 4)
        media.need(scale>0,"zero_clock");return scale to duration
    }
    private fun description(track:Track) {
        val stsd=media.one(track.tables,"stsd")
        media.need(media.full(stsd)==0L&&media.unsigned(stsd.body+4,4)==1L,"sample_description_count")
        val entries=media.boxes(stsd.body+8,stsd.end)
        media.need(entries.size==1,"sample_entry_count")
        val entry=entries.single();val header=if(track.audio) 28 else 78
        media.bounds(entry.body,header.toLong(),entry.end)
        media.need(entry.kind==tag(if(track.audio) "mp4a" else "vp09")&&media.unsigned(entry.body+6,2)==1L,"sample_entry_type")
        if(track.audio) media.need(media.unsigned(entry.body+8,8)==0L,"audio_entry_version")
        val parts=media.boxes(entry.body+header,entry.end)
        val allowed=if(track.audio) setOf(tag("esds"),tag("btrt")) else setOf(tag("vpcC"),tag("pasp"),tag("btrt"))
        media.need(parts.all {it.kind in allowed}&&parts.map {it.kind}.distinct().size==parts.size,"sample_entry_feature")
        media.one(parts,if(track.audio) "esds" else "vpcC")
    }

    private fun flat(audio:Boolean,output:ContainerPacketCatalog):ContainerInventory {
        media.need(top.all {it.kind in setOf(tag("ftyp"),tag("moov"),tag("free"),tag("mdat"))},"flat_topology")
        media.need(movie.all {it.kind in setOf(tag("mvhd"),tag("trak"),tag("udta"))},"movie_feature")
        val track=track(audio);description(track)
        val allowed=setOf("stsd","stts","ctts","stss","stsc","stsz","stco","co64","sgpd","sbgp").map(::tag).toSet()
        media.need(track.tables.all {it.kind in allowed},"sample_table_feature")
        val sizes=media.one(track.tables,"stsz")
        media.need(media.full(sizes)==0L,"sample_size_flags");media.bounds(sizes.body,12,sizes.end)
        val fixedSize=media.unsigned(sizes.body+4,4);val samples=media.unsigned(sizes.body+8,4)
        media.need(samples in 1..2_000_000&&sizes.end-sizes.body-12==if(fixedSize==0L) samples*4 else 0L,"sample_size_count")
        val durations=Runs(media.one(track.tables,"stts"),samples,true)
        media.optional(track.tables,"ctts")?.let {box->Runs(box,samples,false).requireZeroOffsets()}
        sampleGroups(track,samples)
        val sync=Sync(media.optional(track.tables,"stss"),samples)
        val offsets=listOfNotNull(media.optional(track.tables,"stco"),media.optional(track.tables,"co64"))
        media.need(offsets.size==1,"chunk_offset_count")
        val chunks=offsets.single();val width=if(chunks.kind==tag("co64")) 8 else 4
        val chunkCount=media.count(chunks,width);media.need(chunkCount>0,"empty_chunks")
        val mapping=ChunkRuns(media.one(track.tables,"stsc"),chunkCount,samples)
        var index=0L;var dts=0L;var previousEnd=0L
        for(chunk in 1..chunkCount) {
            var position=media.unsigned(chunks.body+8+(chunk-1)*width,width)
            media.need(position>=previousEnd,"overlapping_chunks")
            repeat(mapping.samples(chunk).toInt()) {
                checkpoint();media.need(index<samples,"extra_chunk_sample")
                val size=if(fixedSize==0L) media.unsigned(sizes.body+12+index*4,4) else fixedSize
                val duration=durations.next()
                sampleRange(position,size)
                output.append(media,ContainerPacketCatalog.Packet(dts,duration,size,position,sync.next(index+1)))
                media.need(duration<=Long.MAX_VALUE-dts,"sample_time_overflow")
                dts+=duration;position+=size;index++
            }
            previousEnd=position
        }
        media.need(index==samples&&track.duration==dts,"media_endpoint")
        sync.finish();durations.finish();edit(track,dts)
        return ContainerInventory(ContainerClock(1,track.scale),samples,dts)
    }

    private fun sampleGroups(track:Track,samples:Long) {
        val group=media.optional(track.tables,"sgpd");val mapping=media.optional(track.tables,"sbgp")
        if(!track.audio) {media.need(group==null&&mapping==null,"video_sample_groups");return}
        media.need(group!=null&&mapping!=null,"audio_roll_missing")
        group!!;mapping!!
        media.need(group.end-group.body==18L&&media.bytes(group.body,18).contentEquals(AAC_ROLL),"audio_roll_description")
        media.need(mapping.end-mapping.body==20L&&media.full(mapping)==0L&&media.unsigned(mapping.body+4,4)==tag("roll")&&
            media.unsigned(mapping.body+8,4)==1L&&media.unsigned(mapping.body+12,4)==samples&&media.unsigned(mapping.body+16,4)==1L,"audio_roll_mapping")
    }
    private fun edit(track:Track,end:Long) {
        val (movieScale,movieDuration)=clock(media.one(movie,"mvhd"))
        val rounded=(end.toBigInteger()*movieScale.toBigInteger()+track.scale.toBigInteger()-java.math.BigInteger.ONE)/track.scale.toBigInteger()
        val movieEnd=movie.filter {it.kind==tag("trak")}.maxOf {trak->
            val tkhd=media.one(media.children(trak),"tkhd")
            val version=media.byte(tkhd.body)
            media.unsigned(tkhd.body+if(version==1) 28 else 20,if(version==1) 8 else 4)
        }
        media.need(rounded.bitLength()<63&&track.trackDuration==rounded.toLong()&&movieDuration==movieEnd,"track_endpoint")
        val edts=media.optional(track.parts,"edts")
        if(edts==null) {media.need(end.toBigInteger()*movieScale.toBigInteger()==rounded*track.scale.toBigInteger(),"missing_identity_edit");return}
        val edits=media.children(edts);media.need(edits.size==1,"edit_feature")
        val elst=media.one(edits,"elst")
        media.need(media.full(elst)==0L&&elst.end-elst.body==20L&&media.unsigned(elst.body+4,4)==1L&&
            media.unsigned(elst.body+8,4)==track.trackDuration&&media.unsigned(elst.body+12,4)==0L&&
            media.unsigned(elst.body+16,4)==65536L,"nonidentity_edit")
    }

    private fun fragmented(output:ContainerPacketCatalog):ContainerInventory {
        val track=track(true);description(track)
        media.need(movie.count {it.kind==tag("trak")}==1&&media.optional(track.parts,"edts")==null,"fragmented_audio_topology")
        val trex=media.one(media.children(media.one(movie,"mvex")),"trex")
        media.need(media.full(trex)==0L&&trex.end-trex.body==24L&&media.unsigned(trex.body+4,4)==track.id&&
            media.unsigned(trex.body+8,4)==1L,"fragment_defaults")
        val inherited=LongArray(3) {media.unsigned(trex.body+12+it*4,4)}
        var dts=0L
        for(moof in top.filter {it.kind==tag("moof")}) {
            checkpoint()
            val moofParts=media.children(moof)
            media.need(moofParts.all {it.kind in setOf(tag("mfhd"),tag("traf"))},"fragment_feature")
            val traf=media.one(moofParts,"traf");val parts=media.children(traf)
            media.need(parts.all {it.kind in setOf(tag("tfhd"),tag("tfdt"),tag("trun"))},"fragment_track_feature")
            val tfhd=media.one(parts,"tfhd");val flags=media.full(tfhd)
            media.need(flags and 0x020000!=0L&&flags and 0x02003A.inv().toLong()==0L&&media.unsigned(tfhd.body+4,4)==track.id,"fragment_track_header")
            var cursor=tfhd.body+8
            if(flags and 2!=0L) {media.need(media.unsigned(cursor,4)==1L,"fragment_description");cursor+=4}
            val defaults=inherited.copyOf()
            for(index in 0..2) if(flags and (8L shl index)!=0L) {media.bounds(cursor,4,tfhd.end);defaults[index]=media.unsigned(cursor,4);cursor+=4}
            media.need(cursor==tfhd.end,"fragment_header_bounds")
            val tfdt=media.one(parts,"tfdt");media.need(media.full(tfdt,setOf(0,1))==0L,"fragment_decode_flags")
            val width=if(media.byte(tfdt.body)==1) 8 else 4
            media.need(tfdt.end-tfdt.body==4L+width&&media.unsigned(tfdt.body+4,width)==dts,"fragment_discontinuity")
            val fragment=Fragment(moof,defaults,output)
            for(run in parts.filter {it.kind==tag("trun")}) dts=fragmentRun(run,fragment,dts)
        }
        media.need(output.packets>0&&(track.duration==0L||track.duration==dts),"fragment_endpoint")
        return ContainerInventory(ContainerClock(1,track.scale),output.packets,dts)
    }
    private fun fragmentRun(run:Region,fragment:Fragment,start:Long):Long {
        val moof=fragment.moof;val output=fragment.output
        val flags=media.full(run,setOf(0,1))
        media.need(flags and 1!=0L&&flags and 0xF05.inv().toLong()==0L&&!(flags and 4!=0L&&flags and 1024!=0L),"fragment_run_flags")
        media.bounds(run.body,12,run.end)
        val count=media.unsigned(run.body+4,4)
        media.need(count in 1..2_000_000-output.packets,"fragment_run_count")
        val relative=media.signed32(run.body+8)
        media.need(relative>=0&&relative<=media.length-moof.start,"fragment_data_offset")
        var position=moof.start+relative;var cursor=run.body+12;var dts=start
        val firstFlags=if(flags and 4!=0L) {media.bounds(cursor,4,run.end);media.unsigned(cursor,4).also {cursor+=4}} else null
        repeat(count.toInt()) {index->
            checkpoint()
            val fields=fragment.defaults.copyOf()
            if(index==0&&firstFlags!=null) fields[2]=firstFlags
            for(field in 0..2) if(flags and (256L shl field)!=0L) {media.bounds(cursor,4,run.end);fields[field]=media.unsigned(cursor,4);cursor+=4}
            if(flags and 2048!=0L) {media.bounds(cursor,4,run.end);media.need(media.unsigned(cursor,4)==0L,"fragment_composition_offset");cursor+=4}
            media.need(fields[2] in setOf(0L,0x02000000L),"fragment_sample_dependency")
            sampleRange(position,fields[1])
            output.append(media,ContainerPacketCatalog.Packet(dts,fields[0],fields[1],position,true))
            media.need(fields[0]<=Long.MAX_VALUE-dts,"fragment_time_overflow")
            position+=fields[1];dts+=fields[0]
        }
        media.need(cursor==run.end,"fragment_run_bounds");return dts
    }
    private fun sampleRange(position:Long,size:Long) {
        media.need(size>0&&mdats.any {position>=it.body&&position<=it.end&&size<=it.end-position},"sample_outside_mdat")
    }

    private inner class Runs(private val box:Region,private val samples:Long,private val positive:Boolean) {
        private val entries=media.count(box,8)
        private var entry=0L;private var remaining=0L;private var ticks=0L;private var consumed=0L
        fun next():Long {
            if(remaining==0L) {
                media.need(entry<entries,"missing_time_run")
                remaining=media.unsigned(box.body+8+entry*8,4);ticks=media.unsigned(box.body+12+entry*8,4);entry++
                media.need(remaining in 1..samples-consumed&&(!positive||ticks>0),"invalid_time_run")
            }
            remaining--;consumed++;return ticks
        }
        fun requireZeroOffsets() {repeat(samples.toInt()) {media.need(next()==0L,"composition_offset")};finish()}
        fun finish() {media.need(consumed==samples&&entry==entries&&remaining==0L,"extra_time_run")}
    }
    private inner class Sync(box:Region?,private val samples:Long) {
        private val table=box;private val count=box?.let {media.count(it,4)} ?: 0
        private var index=0L;private var next=if(table!=null&&count>0) media.unsigned(table.body+8,4) else Long.MAX_VALUE
        fun next(sample:Long):Boolean {
            if(table==null) return true
            media.need(next>=sample&&next<=samples||next==Long.MAX_VALUE,"invalid_sync_index")
            if(sample!=next) return false
            index++
            val following=if(index<count) media.unsigned(table.body+8+index*4,4) else Long.MAX_VALUE
            media.need(following>next,"sync_index_order");next=following;return true
        }
        fun finish() {media.need(index==count,"extra_sync_index")}
    }
    private inner class ChunkRuns(private val box:Region,private val chunks:Long,private val samples:Long) {
        private val count=media.count(box,12)
        private var index=0L;private var amount=0L;private var next=1L
        init {media.need(count>0&&media.unsigned(box.body+8,4)==1L,"chunk_run_origin")}
        fun samples(chunk:Long):Long {
            if(chunk==next) {
                val position=box.body+8+index*12
                amount=media.unsigned(position+4,4)
                media.need(amount in 1..samples&&media.unsigned(position+8,4)==1L,"chunk_description")
                index++
                val following=if(index<count) media.unsigned(box.body+8+index*12,4) else Long.MAX_VALUE
                media.need(following>chunk&&(following<=chunks||following==Long.MAX_VALUE),"chunk_run_order")
                next=following
            }
            return amount
        }
    }
    companion object {
        private fun tag(text:String)=BoundedMediaFile.tag(text)
        private val IDENTITY_MATRIX=byteArrayOf(0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,64,0,0,0)
        private val AAC_ROLL=byteArrayOf(1,0,0,0,114,111,108,108,0,0,0,2,0,0,0,1,-1,-1)
        fun write(source:File,catalog:File,kind:String,checkpoint:()->Unit):ContainerInventory = BoundedMediaFile(source,checkpoint).use {media->
            val reader=Mp4PacketInventory(media,checkpoint)
            ContainerPacketCatalog(catalog,checkpoint).use {output->
                if(reader.top.any {it.kind==tag("moof")}) {
                    media.need(kind=="audio","fragmented_video");reader.fragmented(output)
                } else reader.flat(kind=="audio",output)
            }
        }
    }
}
