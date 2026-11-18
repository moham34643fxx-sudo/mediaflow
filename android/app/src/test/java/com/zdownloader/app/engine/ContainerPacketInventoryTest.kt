package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class ContainerPacketInventoryTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun webmAndFlatMp4DeriveIdenticalCompletePackets() {
        val webm=write("source.webm",webm())
        val mp4=write("output.mp4",flat())
        val source=temporary.newFile();val output=temporary.newFile()
        val original=WebmPacketInventory.write(webm,source) {}
        val copied=Mp4PacketInventory.write(mp4,output,"video") {}
        assertEquals(ContainerInventory(ContainerClock(1_000_000,1_000_000_000),3,100),original)
        assertEquals(ContainerInventory(ContainerClock(1,1000),3,100),copied)
        assertArrayEquals(source.readBytes(),output.readBytes())
        DataInputStream(source.inputStream()).use {input->
            val expected=listOf(0L to 42L,42L to 41L,83L to 17L)
            for((index,timing) in expected.withIndex()) {
                assertEquals(timing.first,input.readLong());assertEquals(timing.second,input.readLong())
                assertEquals(3L,input.readLong());assertEquals(index==0,input.readBoolean())
                val digest=ByteArray(32).also(input::readFully)
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(PAYLOAD.copyOfRange(index*3,index*3+3)),digest)
            }
            assertEquals(-1,input.read())
        }
    }

    @Test fun fragmentedAacUsesDefaultsAndExactDecodeBase() {
        val file=write("source.m4a",fragmented())
        val catalog=temporary.newFile()
        assertEquals(ContainerInventory(ContainerClock(1,1000),3,30),Mp4PacketInventory.write(file,catalog,"audio") {})
        DataInputStream(catalog.inputStream()).use {input->
            for(index in 0..2) {
                assertEquals(index*10L,input.readLong());assertEquals(10L,input.readLong())
                assertEquals(3L,input.readLong());assertTrue(input.readBoolean());input.readFully(ByteArray(32))
            }
            assertEquals(-1,input.read())
        }
    }

    @Test fun malformedMp4TablesAndEndpointsAreRejected() {
        val cases=listOf(
            "empty_duration" to mutate(flat(),"stts",16,0),
            "excess_size_count" to mutate(flat(),"stsz",12,4),
            "data_outside_mdat" to mutate(flat(),"stco",12,0),
            "wrong_description" to mutate(flat(),"stsc",20,2),
            "duplicate_sync" to mutate(flat(),"stss",12,0),
            "media_endpoint" to mutate(flat(),"mdhd",20,101),
            "movie_endpoint" to mutate(flat(),"mvhd",20,101),
            "external_data" to mutate(flat(),"url ",4,0))
        for((name,bytes) in cases) rejects(name) {Mp4PacketInventory.write(write("$name.mp4",bytes),temporary.newFile(),"video") {}}
    }

    @Test fun fragmentedAudioRejectsDiscontinuityUnknownFlagsAndBadRanges() {
        val cases=listOf("gap" to mutate(fragmented(),"tfdt",8,1),
            "no_default_base" to mutate(fragmented(),"tfhd",4,0),
            "no_duration" to mutate(fragmented(),"trex",16,0),
            "out_of_mdat" to mutate(fragmented(),"trun",12,0),
            "depends_on_prior" to mutate(fragmented(),"trex",24,0x01000000))
        for((name,bytes) in cases) rejects(name) {Mp4PacketInventory.write(write("$name.m4a",bytes),temporary.newFile(),"audio") {}}
    }

    @Test fun webmRejectsLacingReorderMissingTerminalDurationAndTruncation() {
        for((name,bytes) in listOf("laced" to webm(flags=0x82),"duplicate_time" to webm(second=0),
            "no_terminal_duration" to webm(terminal=false),"wrong_end" to webm(end=101.0),
            "truncated" to webm().dropLast(1).toByteArray())) {
            rejects(name) {WebmPacketInventory.write(write("$name.webm",bytes),temporary.newFile()) {}}
        }
    }

    @Test fun truncatedHeadersAndOversizedUnsignedOffsetsFailClosed() {
        for(length in 0..15) {
            val file=write("truncated-$length",ByteArray(length) {0xFF.toByte()})
            rejects("box header $length") {Mp4PacketInventory.write(file,temporary.newFile(),"video") {}}
        }
        val file=write("unsigned",ByteArray(8) {0xFF.toByte()})
        rejects("unsigned overflow") {BoundedMediaFile(file) {}.use {it.unsigned(0,8)}}
    }

    @Test fun pageBoundaryReadsAndCancellationDoNotDependOnFileSize() {
        val bytes=ByteArray(200_000) {(it%251).toByte()};val file=write("pages",bytes)
        BoundedMediaFile(file) {}.use {reader->
            assertArrayEquals(bytes.copyOfRange(65530,65550),reader.bytes(65530,20))
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(65000,140000)),reader.hash(65000,75000))
        }
        try {
            BoundedMediaFile(file) {throw java.util.concurrent.CancellationException("test cancellation")}.use {it.hash(0,100)}
            fail("Cancellation was swallowed")
        } catch(_:java.util.concurrent.CancellationException) { /* cooperative cancellation is part of the reader contract */ }
    }

    @Test fun excessTracksAreRejectedBeforeTheirNestedTablesAreMaterialized() {
        val file=write("excess-tracks.mp4",box("moov",box("trak"),box("trak"),box("trak")))
        try {
            Mp4PacketInventory.write(file,temporary.newFile(),"video") {}
            fail("Unsupported track count was admitted")
        } catch(error:BoundedMediaFile.Unsupported) {
            assertEquals("track_topology",error.reason)
        }
    }

    private fun write(name:String,bytes:ByteArray)=temporary.newFile(name).apply {writeBytes(bytes)}
    private fun rejects(name:String,operation:()->Unit) {
        try {operation();fail("Accepted $name")} catch(_:BoundedMediaFile.Unsupported) { }
    }
    private fun mutate(bytes:ByteArray,kind:String,relative:Int,number:Int):ByteArray {
        val marker=kind.toByteArray(Charsets.US_ASCII)
        val offset=bytes.indices.first {start->start+4<=bytes.size&&marker.indices.all {bytes[start+it]==marker[it]}}
        return bytes.copyOf().also {ByteBuffer.wrap(it).putInt(offset+relative,number)}
    }
    private fun integers(vararg numbers:Int):ByteArray=ByteArrayOutputStream().also {buffer->
        DataOutputStream(buffer).use {output->numbers.forEach(output::writeInt)}
    }.toByteArray()
    private fun box(kind:String,vararg payload:ByteArray):ByteArray {
        val content=payload.fold(byteArrayOf()) {acc,bytes->acc+bytes}
        return integers(content.size+8)+kind.toByteArray(Charsets.US_ASCII)+content
    }
    private fun full(kind:String,vararg fields:Int)=box(kind,integers(0,*fields))
    private fun movie(tables:ByteArray,end:Int,audio:Boolean,extra:ByteArray=byteArrayOf()):ByteArray {
        val tkhd=ByteArray(84);ByteBuffer.wrap(tkhd).apply {
            putInt(0,3);putInt(12,1);putInt(20,end)
            putInt(40,65536);putInt(56,65536);putInt(72,1073741824)
        }
        val mdhd=ByteArray(24);ByteBuffer.wrap(mdhd).putInt(12,1000).putInt(16,end)
        val mvhd=ByteArray(100);ByteBuffer.wrap(mvhd).putInt(12,1000).putInt(16,end)
        val reference=box("dinf",box("dref",integers(0,1),box("url ",integers(1))))
        val minf=box("minf",reference,box("stbl",tables))
        val mdia=box("mdia",box("mdhd",mdhd),box("hdlr",integers(0,0), (if(audio) "soun" else "vide").toByteArray()),minf)
        return box("moov",box("mvhd",mvhd),box("trak",box("tkhd",tkhd),mdia),extra)
    }
    private fun description(audio:Boolean):ByteArray {
        val header=ByteArray(if(audio) 28 else 78);header[7]=1
        val entry=if(audio) box("mp4a",header,full("esds")) else box("vp09",header,box("vpcC",ByteArray(12)))
        return box("stsd",integers(0,1),entry)
    }
    private fun flat():ByteArray {
        val ftyp=box("ftyp","isom".toByteArray())
        fun moov(offset:Int)=movie(description(false)+full("stts",3,1,42,1,41,1,17)+full("stsz",0,3,3,3,3)+
            full("stsc",1,1,3,1)+full("stco",1,offset)+full("stss",1,1),100,false)
        val header=moov(0);return ftyp+moov(ftyp.size+header.size+8)+box("mdat",PAYLOAD)
    }
    private fun fragmented():ByteArray {
        val moov=movie(description(true),0,true,box("mvex",full("trex",1,1,10,3,0x02000000)))
        fun moof(offset:Int)=box("moof",full("mfhd",1),box("traf",box("tfhd",integers(0x020000,1)),
            full("tfdt",0),box("trun",integers(1,3,offset))))
        return box("ftyp","isom".toByteArray())+moov+moof(moof(0).size+8)+box("mdat",PAYLOAD)
    }
    private fun element(id:Long,payload:ByteArray):ByteArray {
        val width=when {id<=255->1;id<=65535->2;id<=16777215->3;else->4}
        val identifier=ByteArray(width) {index->(id ushr ((width-index-1)*8)).toByte()}
        val size=if(payload.size<127) byteArrayOf((128 or payload.size).toByte()) else byteArrayOf((64 or (payload.size ushr 8)).toByte(),payload.size.toByte())
        return identifier+size+payload
    }
    private fun webm(flags:Int=128,second:Int=42,terminal:Boolean=true,end:Double=100.0):ByteArray {
        fun block(time:Int,flag:Int,payload:ByteArray)=byteArrayOf(0x81.toByte(),(time ushr 8).toByte(),time.toByte(),flag.toByte())+payload
        val header=element(0x1A45DFA3,element(0x4282,"webm".toByteArray()))
        val info=element(0x1549A966,element(0x2AD7B1,byteArrayOf(0x0F,0x42,0x40))+element(0x4489,ByteBuffer.allocate(8).putDouble(end).array()))
        val track=element(0x1654AE6B,element(0xAE,element(0xD7,byteArrayOf(1))+element(0x83,byteArrayOf(1))+
            element(0x86,"V_VP9".toByteArray())+element(0xE0,element(0xB0,byteArrayOf(16))+element(0xBA,byteArrayOf(16)))))
        val last=element(0xA0,element(0xA1,block(83,0,PAYLOAD.copyOfRange(6,9)))+
            (if(terminal) element(0x9B,byteArrayOf(17)) else byteArrayOf())+element(0xFB,byteArrayOf(-41)))
        val cluster=element(0x1F43B675,element(0xE7,byteArrayOf(0))+element(0xA3,block(0,flags,PAYLOAD.copyOfRange(0,3)))+
            element(0xA3,block(second,0,PAYLOAD.copyOfRange(3,6)))+last)
        return header+element(0x18538067,info+track+cluster)
    }
    companion object {private val PAYLOAD="abcdefghi".toByteArray()}
}
