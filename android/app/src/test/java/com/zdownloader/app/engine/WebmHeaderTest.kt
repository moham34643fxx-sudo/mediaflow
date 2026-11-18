package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class WebmHeaderTest {
    private fun element(id:Long,data:ByteArray):ByteArray {
        val raw=ByteBuffer.allocate(8).putLong(id).array().dropWhile {it==0.toByte()}.toByteArray()
        val size=if(data.size<127) byteArrayOf((128 or data.size).toByte())
            else byteArrayOf((64 or (data.size ushr 8)).toByte(),data.size.toByte())
        return raw+size+data
    }
    private fun number(id:Long,value:Int)=element(id,byteArrayOf((value ushr 8).toByte(),value.toByte()))
    private fun document(audio:Boolean=true,video:Boolean=true,encrypted:Boolean=false,codec:String="V_VP9"):ByteArray {
        val ebml=element(0x1A45DFA3,element(0x4282,"webm".toByteArray()))
        val info=element(0x1549A966,element(0x4489,ByteBuffer.allocate(8).putDouble(12000.0).array()))
        val v=if(video) element(0xAE,number(0x83,1)+element(0x86,codec.toByteArray())+
            element(0xE0,number(0xB0,1080)+number(0xBA,1920))+
            if(encrypted) element(0x6D80,byteArrayOf(0)) else byteArrayOf()) else byteArrayOf()
        val a=if(audio) element(0xAE,number(0x83,2)+element(0x86,"A_OPUS".toByteArray())) else byteArrayOf()
        return ebml+byteArrayOf(0x18,0x53,0x80.toByte(),0x67,0xff.toByte())+info+element(0x1654AE6B,v+a)
    }
    @Test fun actualTrackGeometryAndCodecsAreNotGuessed() {
        val header=WebmHeader.parse(document())!!
        assertEquals(1080,header.width);assertEquals(1920,header.height)
        assertEquals("vp9",header.videoCodec);assertEquals("opus",header.audioCodec);assertEquals(12.0,header.duration,0.001)
    }
    @Test fun silentVideoAndAudioOnlyRemainDistinct() {
        assertNull(WebmHeader.parse(document(audio=false))!!.audioCodec)
        assertNull(WebmHeader.parse(document(video=false))!!.videoCodec)
    }
    @Test fun incompleteEncryptedAndUnknownTracksFallBack() {
        assertNull(WebmHeader.parse(document(encrypted=true)))
        assertNull(WebmHeader.parse(document(codec="V_UNSUPPORTED")))
        val valid=document()
        for(end in 0 until valid.size) assertNull("Truncated at $end",WebmHeader.parse(valid.copyOf(end)))
    }
    @Test fun untrustedPrefixesAreBoundedAndCannotSpoofNestedHeaders() {
        assertNull(WebmHeader.parse(ByteArray(65537)))
        assertNull(WebmHeader.parse(element(0x1F43B675,document())))
        repeat(128) {seed->assertNull(WebmHeader.parse(ByteArray(128) {index->(seed*index).toByte()}))}
    }
}
