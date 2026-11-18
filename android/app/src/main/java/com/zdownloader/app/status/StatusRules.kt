package com.zdownloader.app.status

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class StatusSource(val key:String,val packageName:String?) {
    WHATSAPP("whatsapp","com.whatsapp"), BUSINESS("business","com.whatsapp.w4b"), IMPORTS("imports",null)
}
enum class StatusProblem { ACCESS_REQUIRED, ACCESS_REVOKED, WRONG_FOLDER, WRONG_SOURCE, FOLDER_UNAVAILABLE,
    SOURCE_UNAVAILABLE, SOURCE_CHANGED, UNSUPPORTED, CORRUPT, TOO_LARGE, NO_SPACE, SAVE_FAILED, LEGACY_EXPORT_REQUIRED }
class StatusException(val problem:StatusProblem):Exception(problem.name)
data class StatusType(val mime:String,val extension:String,val video:Boolean)
data class StatusMedia(val key:String,val uri:String,val type:StatusType,val bytes:Long?,val modified:Long?,
    val width:Int,val height:Int,val durationMs:Long?=null,val hasAudio:Boolean=false,val savedUri:String?=null)
data class StatusScan(val items:List<StatusMedia>,val skipped:Int=0,val limited:Boolean=false)
data class StatusSaved(val uri:String,val alreadySaved:Boolean,val destinationFallback:Boolean=false,val gallery:Boolean=true)

object StatusRules {
    const val MAX_BYTES=512L*1024*1024
    const val MAX_ITEMS=300
    fun containsDocument(ancestor:String,document:String):Boolean = document==ancestor ||
        document.startsWith(if(ancestor.endsWith(':')) ancestor else ancestor.trimEnd('/')+"/")
    fun type(bytes:ByteArray):StatusType? = when {
        bytes.size>=3 && bytes[0]==0xff.toByte()&&bytes[1]==0xd8.toByte()&&bytes[2]==0xff.toByte()->StatusType("image/jpeg","jpg",false)
        bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->StatusType("image/png","png",false)
        bytes.size>=12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF"&&String(bytes,8,4,Charsets.US_ASCII)=="WEBP"->StatusType("image/webp","webp",false)
        bytes.size>=12 && String(bytes,4,4,Charsets.US_ASCII)=="ftyp" && (
            String(bytes,8,4,Charsets.US_ASCII) in setOf("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","dash","M4V ","MSNV","3gp4","3gp5","3gp6","FACE","qt  ","isml") ||
            (16..bytes.size-4 step 4).any { offset -> String(bytes,offset,4,Charsets.US_ASCII) in setOf("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","dash","M4V ") }
        )->StatusType("video/mp4","mp4",true)
        else->null
    }
    fun fingerprint(uri:String,bytes:Long?,modified:Long?)=MessageDigest.getInstance("SHA-256")
        .digest("$uri|$bytes|$modified".toByteArray()).joinToString("") {"%02x".format(it)}
    data class CopyResult(val bytes:Long,val sha256:String)
    fun copy(source:InputStream,target:OutputStream,expected:Long?,limit:Long=MAX_BYTES,check:()->Unit={}):CopyResult {
        if(expected!=null&&expected>limit) throw StatusException(StatusProblem.TOO_LARGE)
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var bytes=0L
        while(true) {
            check();val count=source.read(buffer);if(count<0) break;if(count==0) continue
            bytes+=count;if(bytes>limit) throw StatusException(StatusProblem.TOO_LARGE)
            target.write(buffer,0,count);hash.update(buffer,0,count)
        }
        check();target.flush()
        if(bytes==0L || expected!=null&&bytes!=expected) throw StatusException(StatusProblem.SOURCE_CHANGED)
        return CopyResult(bytes,hash.digest().joinToString("") {"%02x".format(it)})
    }
    fun customPath(authority:String?,documentId:String?,video:Boolean):String? {
        if(authority!="com.android.externalstorage.documents"||documentId?.startsWith("primary:")!=true) return null
        val path=documentId.removePrefix("primary:").trimEnd('/')
        val parts=path.split('/')
        if(parts.any {it.isBlank()||it=="."||it==".."||it.contains('\\')}) return null
        if(parts.any {it.equals(".Statuses",true)||it.equals("Statuses",true)||it=="com.whatsapp"||it=="com.whatsapp.w4b"}) return null
        val allowed=if(video) setOf("Movies","DCIM") else setOf("Pictures","DCIM")
        return path.takeIf {parts.first() in allowed}?.plus("/")
    }
}
