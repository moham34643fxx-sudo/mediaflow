package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoDimensionDiscoveryTest {
    private fun source(id:String,w:Int?=null,h:Int?=null)=Format.parse(JSONObject()
        .put("format_id",id).put("ext","mp4").put("url","https://example.com/$id.mp4")
        .put("width",w).put("height",h))
    @Test fun measuredSdKeepsItsTransportAndBecomesEligibleFor480WithoutGuessing() {
        val original=source("sd")
        val measured=Format.parse(JSONObject(source("direct",640,360).payload).put("vcodec","avc1").put("acodec","mp4a"))
        val result=VideoDimensionDiscovery.merge(original,measured)!!
        assertEquals("sd",result.id);assertEquals("https://example.com/sd.mp4",JSONObject(result.payload).getString("url"))
        val media=Media("https://example.com/watch","id","Title","Source",null,null,10.0,listOf(source("720",1280,720),result))
        assertEquals("sd",VideoPreferenceSelection.ready(media,480)?.primary?.id)
        assertEquals("sd",VideoPreferenceSelection.ready(media,360)?.primary?.id)
        assertFalse(VideoPreferenceSelection.ready(media,360)!!.audioOnly)
    }
    @Test fun unconfirmedOrAboveCapHeadersNeverBecomeALowerQuality() {
        assertNull(VideoDimensionDiscovery.merge(source("sd"),source("direct")))
        val result=VideoDimensionDiscovery.merge(source("hd"),source("direct",1280,720))!!
        val media=Media("https://example.com/watch","id","Title","Source",null,null,10.0,listOf(result))
        assertNull(VideoPreferenceSelection.ready(media,480))
    }

    private fun prefix(codec:String):ByteArray {
        fun box(name:String,bytes:ByteArray)=ByteBuffer.allocate(8+bytes.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8+bytes.size).put(name.toByteArray()).put(bytes).array()
        val movie=ByteBuffer.allocate(24).apply {putInt(12,1000);putInt(16,10000)}.array()
        val handler=ByteArray(12).apply {"vide".toByteArray().copyInto(this,8)}
        val sample=ByteBuffer.allocate(44).apply {
            putInt(4,1);putInt(8,36);position(12);put(codec.toByteArray());putShort(40,576);putShort(42,1024)
        }.array()
        return box("ftyp","isom".toByteArray())+box("moov",box("mvhd",movie)+
            box("trak",box("mdia",box("hdlr",handler)+box("minf",box("stbl",box("stsd",sample))))))
    }

    @Test fun sourceAwarePrefixMeasuresEncodedGeometryAndPreservesScopedTransport() {
        val original=Format.parse(JSONObject(source("sd").payload)
            .put("cookies","session=synthetic; Domain=example.com; Path=/; Secure"))
        val measured=VideoDimensionDiscovery.measuredPrefix(original,prefix("avc1"))!!
        assertEquals(576,verifiedQualityEdge(measured))
        assertEquals("sd",measured.id)
        assertEquals(JSONObject(original.payload).getString("cookies"),JSONObject(measured.payload).getString("cookies"))
        assertEquals("measured_header",JSONObject(measured.payload).getString("mediaflow_dimensions"))
    }

    @Test fun truncatedOrEncryptedSourceAwarePrefixNeverConfirmsGeometry() {
        assertNull(VideoDimensionDiscovery.measuredPrefix(source("sd"),prefix("avc1").copyOf(32)))
        assertNull(VideoDimensionDiscovery.measuredPrefix(source("sd"),prefix("encv")))
        assertNull(VideoDimensionDiscovery.measuredPrefix(source("sd"),byteArrayOf()))
    }
}
