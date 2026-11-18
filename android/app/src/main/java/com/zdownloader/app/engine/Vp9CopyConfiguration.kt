package com.zdownloader.app.engine

/** Exact FFmpeg 6.1 vpcC mapping for the supported profile-0, 8-bit 4:2:0 keyframe contract. */
internal object Vp9CopyConfiguration {
    private data class Level(val code:Int,val samplesPerSecond:Long,val pixels:Long)
    private val levels=listOf(Level(10,829440,36864),Level(11,2764800,73728),Level(20,4608000,122880),
        Level(21,9216000,245760),Level(30,20736000,552960),Level(31,36864000,983040),
        Level(40,83558400,2228224),Level(41,160432128,2228224),Level(50,311951360,8912896),
        Level(51,588251136,8912896),Level(52,1176502272,8912896),Level(60,1176502272,35651584),
        Level(61,2353004544,35651584),Level(62,4706009088,35651584))

    fun canonical(prefix:ByteArray,width:Int,height:Int,frameRate:String):ByteArray? {
        if(prefix.size<9||width !in 1..8192||height !in 1..8192) return null
        val bits=Bits(prefix)
        if(bits.read(2)!=2||bits.read(1)!=0||bits.read(1)!=0||bits.read(1)!=0||bits.read(1)!=0||bits.read(1)!=1) return null
        bits.read(1)
        if(bits.read(24)!=0x498342||bits.read(3)!=0) return null
        val fullRange=bits.read(1)
        if(bits.read(16)+1!=width||bits.read(16)+1!=height) return null
        if(bits.read(1)==1&&(bits.read(16)+1!=width||bits.read(16)+1!=height)) return null
        if(!bits.complete) return null
        val level=level(width,height,frameRate) ?: return null
        return byteArrayOf(1,0,0,0,0,level.toByte(),(128 or 2 or fullRange).toByte(),2,2,2,0,0)
    }

    private fun level(width:Int,height:Int,frameRate:String):Int? {
        val parts=frameRate.split('/')
        if(parts.size!=2) return null
        val numerator=parts[0].toLongOrNull()?.takeIf {it in 1..1_000_000} ?: return null
        val denominator=parts[1].toLongOrNull()?.takeIf {it in 1..1_000_000} ?: return null
        if(numerator>240*denominator) return null
        val pixels=width.toLong()*height
        return levels.firstOrNull {pixels<=it.pixels&&pixels*numerator<=it.samplesPerSecond*denominator}?.code
    }

    private class Bits(private val bytes:ByteArray) {
        private var position=0
        var complete=true
            private set
        fun read(count:Int):Int {
            if(position+count>bytes.size*8) {complete=false;return -1}
            var bits=0
            repeat(count) {
                bits=(bits shl 1) or ((bytes[position/8].toInt() ushr (7-position%8)) and 1)
                position++
            }
            return bits
        }
    }
}
