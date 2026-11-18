package com.zdownloader.app.engine

/** Byte signatures classify original images; filenames, dimensions and MIME alone never do. */
internal object MediaFileSignature {
    fun imageExtension(prefix:ByteArray):String? = when {
        prefix.size>=8&&prefix.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))->"png"
        prefix.size>=3&&prefix[0]==(-1).toByte()&&prefix[1]==(-40).toByte()&&prefix[2]==(-1).toByte()->"jpg"
        prefix.size>=12&&String(prefix,0,4,Charsets.US_ASCII)=="RIFF"&&String(prefix,8,4,Charsets.US_ASCII)=="WEBP"->"webp"
        else->null
    }
    fun originalImageContainer(prefix:ByteArray):Boolean {
        if(imageExtension(prefix)!=null) return true
        if(prefix.size>=6&&String(prefix,0,6,Charsets.US_ASCII) in setOf("GIF87a","GIF89a")) return true
        if(prefix.size>=12&&String(prefix,4,4,Charsets.US_ASCII)=="ftyp")
            return String(prefix,8,4,Charsets.US_ASCII) in setOf("avif","avis","heic","heix","hevc","hevx","mif1","msf1")
        return prefix.size>=2&&String(prefix,0,2,Charsets.US_ASCII)=="BM"
    }
}
