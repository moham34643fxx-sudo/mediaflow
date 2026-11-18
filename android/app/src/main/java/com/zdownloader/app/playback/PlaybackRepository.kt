package com.zdownloader.app.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.MediaFiles
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Snapshot of the live MediaSession queue, used when deciding whether a SAF grant is shared. */
internal object PlaybackGrantReferences {
    @Volatile private var activeUris:Set<String> = emptySet()
    fun update(uris:Collection<String>) {activeUris=uris.toSet()}

    suspend fun needed(context:Context,grant:Uri):Boolean {
        val application=context.applicationContext as? com.zdownloader.app.ZApplication ?: return true
        return try {
            val repository=PlaybackRepository(application)
            val durable=repository.savedQueue().first.map {it.uri}+
                application.database.playbackLibrary().referencedUris()
            (activeUris+durable).any {reference->
                runCatching {MediaFiles.permissionCovers(grant,Uri.parse(reference))}.getOrDefault(true)
            }
        } catch(error:kotlin.coroutines.cancellation.CancellationException) {throw error}
        catch(_:Exception) {true} // Ownership uncertainty must preserve access.
    }
}

internal object LocalPlaybackPolicy {
    const val MAX_QUEUE=500
    fun queue(items:List<PlaybackMedia>,index:Int):Pair<List<PlaybackMedia>,Int> {
        val wanted=items.getOrNull(index)?.uri
        val valid=items.filter {accepts(it.uri)}.distinctBy {it.uri}
        val selected=valid.indexOfFirst {it.uri==wanted}.coerceAtLeast(0)
        val start=(selected-MAX_QUEUE/2).coerceIn(0,maxOf(0,valid.size-MAX_QUEUE))
        return valid.drop(start).take(MAX_QUEUE) to (selected-start)
    }
    fun accepts(value:String):Boolean=runCatching {
        val uri=java.net.URI(value)
        value.length<=8192&&uri.scheme=="content"&&!uri.rawAuthority.isNullOrBlank()&&uri.userInfo==null&&uri.port==-1&&uri.fragment==null
    }.getOrDefault(false)
    fun resume(position:Long,duration:Long):Long=if(position<0||duration>0&&position>=duration-1500) 0 else position
    fun subtitleMime(name:String):String?=when(name.substringAfterLast('.').lowercase()) {
        "srt"->"application/x-subrip";"vtt"->"text/vtt";"ttml","dfxp"->"application/ttml+xml";"ssa","ass"->"text/x-ssa";else->null
    }
}

internal data class PlaybackMedia(val uri:String,val title:String,val video:Boolean,val mime:String?=null,
    val artist:String?=null,val album:String?=null,val artwork:String?=null,val duration:Long=0,
    val videoWidth:Int=0,val videoHeight:Int=0,val pixelWidthHeightRatio:Float=1f,val rotationDegrees:Int=0) {
    val displayAspect:Float? get()=if(video) VideoOrientation.displayAspect(videoWidth,videoHeight,pixelWidthHeightRatio,rotationDegrees) else null
    fun item():MediaItem=MediaItem.Builder().setMediaId(uri).setUri(uri).setMimeType(mime).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setArtworkUri(artwork?.let(Uri::parse))
            .setExtras(Bundle().apply {
                putBoolean("video",video);putString("format",mime?.substringAfter('/')?.uppercase())
                putInt("video_width",videoWidth);putInt("video_height",videoHeight)
                putFloat("video_pixel_ratio",pixelWidthHeightRatio);putInt("video_rotation",rotationDegrees)
            }).build()).build()
    fun json()=JSONObject().put("uri",uri).put("title",title).put("video",video).put("mime",mime).put("artist",artist)
        .put("album",album).put("artwork",artwork).put("duration",duration).put("video_width",videoWidth)
        .put("video_height",videoHeight).put("video_pixel_ratio",pixelWidthHeightRatio).put("video_rotation",rotationDegrees)
    companion object {
        fun from(item:MediaItem):PlaybackMedia {
            val m=item.mediaMetadata
            val extras=m.extras
            return PlaybackMedia(item.mediaId,m.title?.toString().orEmpty(),m.extras?.getBoolean("video")==true,
                item.localConfiguration?.mimeType,m.artist?.toString(),m.albumTitle?.toString(),m.artworkUri?.toString(),
                videoWidth=extras?.getInt("video_width") ?: 0,videoHeight=extras?.getInt("video_height") ?: 0,
                pixelWidthHeightRatio=extras?.getFloat("video_pixel_ratio",1f) ?: 1f,
                rotationDegrees=extras?.getInt("video_rotation") ?: 0)
        }
        fun from(row:DownloadEntity):PlaybackMedia? {
            val uri=row.uri?.takeIf(LocalPlaybackPolicy::accepts) ?: return null
            if(row.status!="COMPLETED") return null
            val selection=runCatching {Selection.parse(row.selection)}.getOrNull()
            return PlaybackMedia(uri,row.title,selection?.audioOnly!=true,duration=((row.duration ?: 0.0)*1000).toLong())
        }
    }
}

internal data class PendingExternalOpen(val id:String,val intent:android.content.Intent)

/** On-demand metadata for existing/user-granted URIs. Never scans private folders. */
internal class PlaybackRepository(context:Context) {
    private companion object {
        const val PENDING_EXTERNAL_OPEN="pending_external_open"
        const val HANDLED_EXTERNAL_REQUEST="handled_external_request_id"
    }
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE)
    val library get()=(app as com.zdownloader.app.ZApplication).database.playbackLibrary()
    suspend fun favorite(media:PlaybackMedia,enabled:Boolean) {
        if(enabled) library.favorite(PlaybackFavorite(media.uri,media.title,media.video,media.mime,media.artist,media.album,media.artwork,media.duration)) else library.unfavorite(media.uri)
    }
    suspend fun deviceMedia(audio:Boolean,limit:Int=500):List<PlaybackMedia> = withContext(Dispatchers.IO) {
        val collection=if(audio) android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns=if(audio) arrayOf("_id","title","_display_name","mime_type","duration","artist","album") else arrayOf("_id","title","_display_name","mime_type","duration")
        app.contentResolver.query(collection,columns,null,null,"title COLLATE NOCASE ASC")?.use {cursor->buildList {
            while(cursor.moveToNext()&&size<limit.coerceAtLeast(1)) {
                val uri=android.content.ContentUris.withAppendedId(collection,cursor.getLong(0)).toString()
                fun field(index:Int)=cursor.getString(index)?.takeUnless {it.isBlank()||it=="<unknown>"}?.take(512)
                add(PlaybackMedia(uri,field(1) ?: field(2) ?: "MediaFlow",!audio,field(3),if(audio) field(5) else null,if(audio) field(6) else null,duration=cursor.getLong(4).coerceAtLeast(0)))
            }
        }} ?: emptyList()
    }
    private fun key(uri:String)=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") {"%02x".format(it)}
    fun position(uri:String)=prefs.getLong("position_${key(uri)}",0)
    fun savePosition(uri:String,value:Long) {if(LocalPlaybackPolicy.accepts(uri)) {
        val edit=prefs.edit().putLong("position_${key(uri)}",value.coerceAtLeast(0))
        val positions=prefs.all.keys.filter {it.startsWith("position_")}
        if(positions.size>1000) positions.filter {it!="position_${key(uri)}"}.take(positions.size-1000).forEach {edit.remove(it)}
        edit.apply()
    }}
    fun boolean(name:String,default:Boolean=false)=prefs.getBoolean(name,default)
    fun setBoolean(name:String,value:Boolean) {prefs.edit().putBoolean(name,value).apply()}
    fun number(name:String,default:Float)=prefs.getFloat(name,default)
    fun setNumber(name:String,value:Float) {prefs.edit().putFloat(name,value).apply()}
    fun text(name:String,default:String)=prefs.getString(name,default) ?: default
    fun setText(name:String,value:String) {prefs.edit().putString(name,value).apply()}
    fun saveQueue(items:List<PlaybackMedia>,index:Int,handledExternalRequestId:String?=null) {
        val array=JSONArray();items.take(LocalPlaybackPolicy.MAX_QUEUE).forEach {array.put(it.json())}
        val edit=prefs.edit().putString("queue",array.toString()).putInt("index",index)
        handledExternalRequestId?.let {id->
            edit.putString(HANDLED_EXTERNAL_REQUEST,id)
            if(pendingExternalOpenId()==id) edit.remove(PENDING_EXTERNAL_OPEN)
        }
        edit.apply()
    }
    fun savePendingExternalOpen(id:String,uri:Uri,mime:String?):Boolean {
        if(!LocalPlaybackPolicy.accepts(uri.toString())||!ExternalMediaOpen.acceptsMime(mime)) return false
        val value=JSONObject().put("id",id).put("uri",uri.toString()).put("mime",mime).toString()
        return prefs.edit().putString(PENDING_EXTERNAL_OPEN,value).commit()
    }
    private fun pendingExternalOpenId():String?=runCatching {JSONObject(prefs.getString(PENDING_EXTERNAL_OPEN,"{}") ?: "{}").optString("id").takeIf {it.isNotBlank()}}.getOrNull()
    fun pendingExternalOpen():PendingExternalOpen?=runCatching {
        val value=JSONObject(prefs.getString(PENDING_EXTERNAL_OPEN,"{}") ?: "{}")
        val id=value.optString("id").takeIf {it.isNotBlank()} ?: return@runCatching null
        if(prefs.getString(HANDLED_EXTERNAL_REQUEST,null)==id) return@runCatching null
        val uri=Uri.parse(value.getString("uri"))
        val mime=value.optString("mime").takeUnless {it.isBlank()||it=="null"}
        if(!LocalPlaybackPolicy.accepts(uri.toString())||!ExternalMediaOpen.acceptsMime(mime)) return@runCatching null
        val intent=android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData=android.content.ClipData.newRawUri("Audio",uri)
        PendingExternalOpen(id,intent)
    }.getOrNull()
    fun externalRequestHandled(id:String):Boolean=prefs.getString(HANDLED_EXTERNAL_REQUEST,null)==id
    fun markExternalRequestHandled(id:String) {
        val edit=prefs.edit().putString(HANDLED_EXTERNAL_REQUEST,id)
        if(pendingExternalOpenId()==id) edit.remove(PENDING_EXTERNAL_OPEN)
        edit.apply()
    }
    fun savedQueue():Pair<List<PlaybackMedia>,Int> = runCatching {
                val array=JSONArray(prefs.getString("queue","[]"));val rows=buildList {
                for(i in 0 until minOf(array.length(),LocalPlaybackPolicy.MAX_QUEUE)) {
                    val o=array.getJSONObject(i);val uri=o.getString("uri")
                    if(LocalPlaybackPolicy.accepts(uri)) add(PlaybackMedia(uri,o.optString("title").take(512),o.optBoolean("video"),
                        o.optString("mime").takeUnless {it=="null"||it.isBlank()},o.optString("artist").takeUnless {it=="null"||it.isBlank()},
                    o.optString("album").takeUnless {it=="null"||it.isBlank()},o.optString("artwork").takeUnless {it=="null"||it.isBlank()},
                    o.optLong("duration"),o.optInt("video_width"),o.optInt("video_height"),o.optDouble("video_pixel_ratio",1.0).toFloat(),
                    o.optInt("video_rotation")))
            }
        };rows to prefs.getInt("index",0).coerceIn(0,maxOf(0,rows.lastIndex))
    }.getOrDefault(emptyList<PlaybackMedia>() to 0)
    suspend fun resolve(uri:String,title:String?=null,videoHint:Boolean=false):PlaybackMedia=withContext(Dispatchers.IO) {
        require(LocalPlaybackPolicy.accepts(uri))
        val content=Uri.parse(uri);val resolver=app.contentResolver
        val name=resolver.query(content,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) it.getString(0) else null}
        val type=resolver.getType(content)
        var artist:String?=null;var album:String?=null;var duration=0L;var video=type?.startsWith("video/")==true||videoHint
        var art:String?=null;var embeddedTitle:String?=null;var videoWidth=0;var videoHeight=0;var pixelWidthHeightRatio=1f;var rotationDegrees=0
        resolver.openFileDescriptor(content,"r")?.use {fd->
            require(fd.statSize!=0L)
            val videoFormat=if(video) videoTrackFormat(fd.fileDescriptor) else null
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(fd.fileDescriptor)
                fun field(key:Int)=retriever.extractMetadata(key)?.filterNot {it.isISOControl()}?.trim()?.take(512)?.takeIf {it.isNotEmpty()}
                embeddedTitle=field(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist=field(MediaMetadataRetriever.METADATA_KEY_ARTIST);album=field(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                duration=field(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                video=field(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes"||video
                if(video) {
                    fun formatInt(key:String)=runCatching {videoFormat?.getInteger(key)}.getOrNull()
                    videoWidth=formatInt(MediaFormat.KEY_WIDTH)?.takeIf {it>0}
                        ?: field(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf {it>0} ?: 0
                    videoHeight=formatInt(MediaFormat.KEY_HEIGHT)?.takeIf {it>0}
                        ?: field(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf {it>0} ?: 0
                    val sarWidth=formatInt(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)?.takeIf {it>0}
                    val sarHeight=formatInt(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)?.takeIf {it>0}
                    if(sarWidth!=null&&sarHeight!=null) pixelWidthHeightRatio=sarWidth.toFloat()/sarHeight
                    val formatRotation=formatInt(MediaFormat.KEY_ROTATION)
                    val metadataRotation=field(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                    rotationDegrees=(formatRotation?.takeIf {it%360!=0} ?: metadataRotation ?: 0).let {((it%360)+360)%360}
                }
                art=EmbeddedArtwork.cache(app,retriever.embeddedPicture)
                if(art==null) {
                    runCatching {
                        val appInstance=app as? com.zdownloader.app.ZApplication
                        val downloadEntity=appInstance?.database?.downloads()?.byUri(uri)
                        val thumb=downloadEntity?.thumbnail?.takeIf {it.isNotBlank()}
                        if(thumb!=null) {
                            if(thumb.startsWith("http://")||thumb.startsWith("https://")) {
                                val req = okhttp3.Request.Builder()
                                    .url(thumb)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                    .build()
                                com.zdownloader.app.engine.Transfer.networkClient.newBuilder().callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                                    if(resp.isSuccessful) {
                                        val bytes = resp.body?.takeUnless {it.contentLength()>EmbeddedArtwork.MAX_ENCODED_BYTES}
                                            ?.byteStream()?.use(::boundedArtwork)
                                        art = EmbeddedArtwork.cache(app, bytes)
                                    }
                                }
                            } else if(thumb.startsWith("file://")) {
                                val f=File(Uri.parse(thumb).path ?: "")
                                if(f.isFile) art=EmbeddedArtwork.cache(app,f.inputStream().use(::boundedArtwork))
                            } else {
                                val f=File(thumb)
                                if(f.isFile) art=EmbeddedArtwork.cache(app,f.inputStream().use(::boundedArtwork))
                            }
                        }
                    }
                }
            } catch(_:RuntimeException) { /* Let Media3 distinguish unsupported codec from malformed data. */ }
            finally {retriever.release()}
            // A valid file without embedded artwork still resolves successfully.
            // Do not let the nullable artwork expression become use()'s result.
            Unit
        } ?: throw java.io.FileNotFoundException()
        PlaybackMedia(uri,(embeddedTitle ?: title?.takeIf {it.isNotBlank()} ?: name ?: "MediaFlow").filterNot {it.isISOControl()}.take(512),
            video,type?.takeUnless {it=="application/octet-stream"},artist,album,art,duration,videoWidth,videoHeight,pixelWidthHeightRatio,rotationDegrees)
    }
    private fun videoTrackFormat(descriptor:java.io.FileDescriptor):MediaFormat? {
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(descriptor)
            (0 until extractor.trackCount).firstNotNullOfOrNull {index->
                extractor.getTrackFormat(index).takeIf {it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
            }
        } catch(_:Exception) {null}
        finally {extractor.release()}
    }
    private fun boundedArtwork(input:java.io.InputStream):ByteArray? {
        val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer);if(count<0) break
            if(bytes.size()+count>EmbeddedArtwork.MAX_ENCODED_BYTES) return null
            bytes.write(buffer,0,count)
        }
        return bytes.toByteArray()
    }
    suspend fun subtitle(uri:Uri):String=withContext(Dispatchers.IO) {
        require(LocalPlaybackPolicy.accepts(uri.toString()))
        val name=app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) it.getString(0) else null}.orEmpty()
        val mime=requireNotNull(LocalPlaybackPolicy.subtitleMime(name))
        app.contentResolver.openInputStream(uri)?.use {stream->
            val buffer=ByteArray(8192);var total=0
            while(true) {val count=stream.read(buffer);if(count<0) break;total+=count;require(total<=2*1024*1024)}
        } ?: throw java.io.FileNotFoundException()
        mime
    }
}
