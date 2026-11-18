package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class Vp9CopyConfigurationTest {
    @Test fun supportedKeyframeDerivesMp4ConfigurationAndRange() {
        for(range in 0..1) {
            val expected=byteArrayOf(1,0,0,0,0,21,(130 or range).toByte(),2,2,2,0,0)
            assertArrayEquals(expected,Vp9CopyConfiguration.canonical(header(range),640,272,"24/1"))
        }
    }

    @Test fun truncatedAndUnsupportedHeadersCannotCertifyConfiguration() {
        val valid=header(0)
        // Every byte boundary, including a missing render-size flag, must fail closed.
        for(size in 0 until valid.size) {
            assertNull("Prefix length $size",Vp9CopyConfiguration.canonical(valid.copyOf(size),640,272,"24/1"))
        }
        val mutations=listOf(0 to 0x40,0 to 0x20,0 to 0x08,0 to 0x04,0 to 0x02,1 to 0x01,4 to 0x20,5 to 0x01)
        for((index,mask) in mutations) {
            val changed=valid.copyOf().also {it[index]=(it[index].toInt() xor mask).toByte()}
            assertNull("Header mutation $index/$mask",Vp9CopyConfiguration.canonical(changed,640,272,"24/1"))
        }
    }

    @Test fun invalidGeometryAndFrameRateCannotInferLevel() {
        for(rate in listOf("0/1","24/0","-24/1","24","NaN/1","241/1","1000001/1")) {
            assertNull(rate,Vp9CopyConfiguration.canonical(header(0),640,272,rate))
        }
        for(width in listOf(0,639,641,8193)) {
            assertNull("Width $width",Vp9CopyConfiguration.canonical(header(0),width,272,"24/1"))
        }
    }

    private fun header(range:Int):ByteArray {
        val fields=listOf(2 to 2,0 to 1,0 to 1,0 to 1,0 to 1,1 to 1,0 to 1,
            0x498342 to 24,0 to 3,range to 1,639 to 16,271 to 16,0 to 1)
        val bits=fields.joinToString("") {(number,count)->number.toString(2).padStart(count,'0')}
        return bits.padEnd((bits.length+7)/8*8,'0').chunked(8).map {it.toInt(2).toByte()}.toByteArray()
    }
}
